import Foundation
import SwiftUI
import Darwin
#if os(iOS)
import UIKit
#else
import AppKit
#endif

// 2.5 Fehlerberichte und "Diagnose senden" (nur wenn /api/version features "clientlog1" meldet; Vertrag
// docs/APP-API.md "Fehlerberichte und Diagnose"). Logik: ClientLogLogic.swift.
// - Absturz: NSSetUncaughtExceptionHandler + Signal-Handler schreiben eine kurze Notiz (Text, kein Speicherabbild) in eine
//   beim Start geoeffnete Datei; beim naechsten Start (angemeldet, Feature da) als ein Bericht {k:"crash"}.
// - Fehler an zentralen Stellen (API 5xx/Dekodieren, Speichern, Anruf-Aufbau) als {k:"error"}: gesammelt, gleiche gezaehlt,
//   hoechstens ein Bericht je 30 s; nie Inhalte, Titel, Namen, Adressen.
// - Ringpuffer der letzten 200 Ereignisse nur im Speicher - verlaesst das Geraet nur ueber "Diagnose senden".

// MARK: Absturz-Handler (nur async-signal-sichere Schritte im Signal-Handler: vorbereitete Datei, write, backtrace)

nonisolated(unsafe) private var crashFD: Int32 = -1
nonisolated(unsafe) private var crashDone: Int32 = 0
nonisolated(unsafe) private var crashFrames: UnsafeMutablePointer<UnsafeMutableRawPointer?>?
nonisolated(unsafe) private var crashLines: UnsafeMutablePointer<UnsafeMutablePointer<CChar>?>?   // je Signalnummer "SIG ...\n"

private func crashSignal(_ sig: Int32) {
    if crashFD >= 0, crashDone == 0, sig >= 0, sig < 32, let lines = crashLines, let p = lines[Int(sig)] {
        crashDone = 1
        _ = write(crashFD, p, strlen(p))
        if let f = crashFrames {
            let n = backtrace(f, 64)
            backtrace_symbols_fd(f, n, crashFD)
        }
    }
    signal(sig, SIG_DFL)                                                   // Standardverhalten: Absturz wie bisher
    raise(sig)
}

enum CrashNote {
    private static var dir: URL {
        let d = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("hk", isDirectory: true)
        try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        return d
    }
    private static var file: URL { dir.appendingPathComponent("crash-note.txt") }
    /// Notiz des letzten Laufs (vor dem Einrichten gelesen und entfernt)
    nonisolated(unsafe) private(set) static var previous: String?
    nonisolated(unsafe) private static var installed = false

    /// einmal frueh beim Start: alte Notiz einlesen, Datei leer neu oeffnen, Handler setzen
    static func install() {
        guard !installed else { return }
        installed = true
        #if DEBUG
        if ProcessInfo.processInfo.environment["XCTestConfigurationFilePath"] != nil { return }
        #endif
        let url = file
        if let d = try? Data(contentsOf: url), !d.isEmpty, let s = String(data: d.prefix(32_000), encoding: .utf8) { previous = s }
        try? FileManager.default.removeItem(at: url)
        crashFD = url.withUnsafeFileSystemRepresentation { p in p.map { open($0, O_WRONLY | O_CREAT | O_TRUNC, 0o600) } ?? -1 }
        guard crashFD >= 0 else { return }
        crashFrames = .allocate(capacity: 64)
        _ = backtrace(crashFrames!, 64)                                      // einmal vorab: laedt alles Noetige (im Handler kein dyld)
        let lines = UnsafeMutablePointer<UnsafeMutablePointer<CChar>?>.allocate(capacity: 32)
        lines.initialize(repeating: nil, count: 32)
        for (n, name) in ClientLogLogic.signals { lines[Int(n)] = strdup(ClientLogLogic.signalLine(name)) }
        crashLines = lines
        // eigener Stapel fuer den Handler (Stapelueberlauf auf dem Hauptthread)
        let size = 64 * 1024
        var ss = stack_t(ss_sp: UnsafeMutableRawPointer.allocate(byteCount: size, alignment: 16), ss_size: size, ss_flags: 0)
        sigaltstack(&ss, nil)
        for (n, _) in ClientLogLogic.signals {
            var a = sigaction()
            a.__sigaction_u.__sa_handler = crashSignal
            a.sa_flags = SA_ONSTACK
            sigemptyset(&a.sa_mask)
            sigaction(n, &a, nil)
        }
        NSSetUncaughtExceptionHandler { e in
            guard crashFD >= 0, crashDone == 0 else { return }
            crashDone = 1
            let text = "EXC \(e.name.rawValue): \(e.reason ?? "")".replacingOccurrences(of: "\n", with: " ").prefix(600)
                + "\n" + e.callStackSymbols.prefix(ClientLogLogic.stackLines).joined(separator: "\n") + "\n"
            text.utf8CString.withUnsafeBufferPointer { b in _ = write(crashFD, b.baseAddress, max(0, b.count - 1)) }
        }
    }
    /// gesendet bzw. verworfen
    static func consume() { previous = nil }

    /// Swift-Namen lesbar machen (Laufzeit der App; nil, wenn nicht verfuegbar)
    static func demangle(_ s: String) -> String? {
        typealias F = @convention(c) (UnsafePointer<CChar>?, Int, UnsafeMutablePointer<CChar>?, UnsafeMutablePointer<Int>?, UInt32) -> UnsafeMutablePointer<CChar>?
        guard let sym = dlsym(UnsafeMutableRawPointer(bitPattern: -2), "swift_demangle") else { return nil }   // RTLD_DEFAULT
        let f = unsafeBitCast(sym, to: F.self)
        let name = s.hasPrefix("_") ? String(s.dropFirst()) : s
        return name.withCString { p in
            guard let r = f(p, strlen(p), nil, nil, 0) else { return nil }
            defer { free(r) }
            return String(cString: r)
        }
    }
}

// MARK: Sammeln und Senden

@MainActor
final class ClientLog {
    static let shared = ClientLog()
    weak var store: Store?
    private let t0 = Date()
    private let sid = ClientLogLogic.newSID()
    private var reporter = ClientLogLogic.Reporter()
    private var ring = ClientLogLogic.Ring()
    private var timer: Task<Void, Never>?
    private var sending = false
    /// grober Ort fuer Fehler (pages, chat, calls, settings ...)
    private(set) var screen = "dashboard"

    private init() { ring.push("life", t: 0, ["st": "start"]) }

    private var now: Double { Date().timeIntervalSince(t0) * 1000 }
    var on: Bool { store?.features.has(ClientLogLogic.feature) == true }

    // Ringpuffer
    func ev(_ k: String, _ f: [String: Any] = [:]) { ring.push(k, t: now, f) }
    private var places = ClientLogScreen.Stack()
    /// Hauptansicht gewechselt (Seitenleiste)
    func view(_ s: String) { places.base = ClientLogLogic.screen(s); placeChanged() }
    /// Einstellungen/Anruf ein- bzw. ausgeblendet
    func overlay(_ s: String, _ on: Bool) { places.show(ClientLogLogic.screen(s), on); placeChanged() }
    private func placeChanged() {
        let s = places.current
        guard s != screen else { return }
        screen = s; ev("view", ["screen": s])
    }
    func life(_ st: String) { if ClientLogLogic.lifeStates.contains(st) { ev("life", ["st": st]) } }
    func conn(_ ch: String, _ st: String) {
        guard ClientLogLogic.connChannels.contains(ch), ClientLogLogic.connStates.contains(st) else { return }
        ev("conn", ["ch": ch, "st": st])
    }
    func save(_ st: String, status: Int? = nil) {
        guard ClientLogLogic.saveStates.contains(st) else { return }
        var f: [String: Any] = ["st": st]
        if let status { f["s"] = status }
        ev("save", f)
    }

    /// Antwort einer API-Anfrage (Store.send): Status je Endpunkt-Muster; 5xx als Fehlerbericht
    func net(url: URL?, method: String, status: Int, ms: Double) {
        guard let url, let p = ClientLogLogic.endpoint(url.absoluteString), !p.hasPrefix("/api/client/") else { return }
        ring.net(method: method, pattern: p, status: status, ms: ms, t: now)
        if status >= 500 { report("error", ClientLogLogic.httpMessage(code: status, method: method, pattern: p)) }
    }
    /// Antwort liess sich nicht lesen
    func decodeFailed(_ e: Error, path: String, method: String = "GET") {
        guard let p = ClientLogLogic.endpoint(path) else { return }
        report("error", ClientLogLogic.decodeMessage(e, method: method, pattern: p))
    }

    /// Fehler aufnehmen (bereinigt). Ohne Feature: nur in den Ringpuffer
    func report(_ k: String, _ msg: String, src: String? = nil, line: Int? = nil) {
        let it = ClientLogLogic.Item.make(k, msg, src: src, line: line, screen: screen, t: now)
        ring.err(it)
        guard on else { return }
        if reporter.add(it) { schedule() }
    }

    /// angemeldet und /api/version bekannt (nach einem Abgleich): Absturz-Notiz senden bzw. ohne Feature verwerfen
    func ready() {
        guard let store, store.serverVersion != nil else { return }        // offline/unbekannt: Notiz aufheben
        guard on else { CrashNote.consume(); return }                     // ohne Feature: nichts aufheben, was spaeter ginge
        if let n = CrashNote.previous {
            CrashNote.consume()
            if let it = ClientLogLogic.crashItem(n, t: now, demangle: CrashNote.demangle) { reporter.add(it) }
        }
        schedule()
    }

    private func schedule() {
        guard timer == nil, !reporter.isEmpty, !reporter.off else { return }
        let wait = max(1500, reporter.wait(now: now))                     // kurz sammeln (Folgefehler in einem Bericht)
        timer = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(Int(wait)))
            guard let self else { return }
            self.timer = nil
            await self.flush()
        }
    }

    private func flush() async {
        guard on, !sending, let store, !store.needsLogin, !store.loginPostponed else { return }
        guard let items = reporter.take(now: now), let data = ClientLogLogic.logBody(head: head(), items: items),
              let body = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { schedule(); return }
        sending = true
        defer { sending = false }
        var code = 0, retry: Double?
        if let (d, c) = try? await store.apiResponse(ClientLogLogic.logPath, method: "POST", body: body, timeout: 15) {
            code = c
            retry = (ShareJSON.obj(d)?["retry_s"] as? NSNumber)?.doubleValue
        }
        reporter.response(code: code, retryS: retry, items: items, now: now)
        schedule()
    }

    private func head() -> [String: Any] {
        let major = ProcessInfo.processInfo.operatingSystemVersion.majorVersion
        #if os(macOS)
        return ClientLogLogic.head(app: "mac", ver: Store.appVersionLabel, os: ClientLogLogic.osLabel(name: "macOS", major: major), dev: "desktop", sid: sid)
        #else
        let pad = UIDevice.current.userInterfaceIdiom == .pad
        return ClientLogLogic.head(app: "ios", ver: Store.appVersionLabel, os: ClientLogLogic.osLabel(name: pad ? "iPadOS" : "iOS", major: major),
                                   dev: pad ? "tablet" : "phone", sid: sid)
        #endif
    }

    // MARK: Diagnose senden

    enum DiagResult: Equatable { case sent(String), off, tooMany, failed(String) }
    /// Nur auf Knopfdruck: Ringpuffer + optionaler Freitext -> Kennung D-XXXXX
    func sendDiag(note: String) async -> DiagResult {
        guard on, let store else { return .off }
        guard let data = ClientLogLogic.diagBody(head: head(), ring: ring.events, note: note),
              let body = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { return .failed(String(localized: "Diagnose zu groß.")) }
        do {
            let (d, c) = try await store.apiResponse(ClientLogLogic.diagPath, method: "POST", body: body, timeout: 20)
            switch c {
            case 200: return ClientLogLogic.diagID(d).map { .sent($0) } ?? .failed(String(localized: "Server-Fehler \(c)"))
            case 404: return .off
            case 429: return .tooMany
            default: return .failed(APIError.http(c, ShareJSON.obj(d).flatMap { ShareJSON.str($0["error"]) } ?? "").localizedDescription)
            }
        } catch { return .failed(error.localizedDescription) }
    }
}

// MARK: Einstellungen -> Info -> Hilfe

struct DiagnoseSection: View {
    @EnvironmentObject private var store: Store
    @State private var note = ""
    @State private var busy = false
    @State private var result: ClientLog.DiagResult?
    @State private var copied = false
    private var on: Bool { store.features.has(ClientLogLogic.feature) }

    var body: some View {
        Section("Hilfe") {
            if on {
                TextField("Was ist passiert? (optional)", text: $note, axis: .vertical)
                    .lineLimit(2...6)
                    .disabled(busy)
                    .onChange(of: note) { _, v in if v.count > ClientLogLogic.noteMax { note = String(v.prefix(ClientLogLogic.noteMax)) } }
            }
            Button {
                Task { await send() }
            } label: {
                if busy { Label("Wird gesendet …", systemImage: "hourglass") } else { Label("Diagnose senden", systemImage: "stethoscope") }
            }
            .disabled(!on || busy)
            Text(on ? LocalizedStringKey("Schickt die letzten Ereignisse dieser App (Fehler, Statuscodes, Ansichtswechsel – ohne Inhalte, Titel oder Namen) an deinen Saga-Server. Dein Text oben geht so an den Admin, wie du ihn schreibst.")
                    : LocalizedStringKey("Dieser Server nimmt keine Diagnose an (ab Saga-Server 2.5, vom Admin abschaltbar)."))
                .font(.footnote).foregroundStyle(.secondary)
            switch result {
            case .sent(let id)?:
                VStack(alignment: .leading, spacing: 6) {
                    Text("Gesendet. Nenne dem Admin diese Kennung:").font(.footnote)
                    HStack {
                        Text(id).font(.title3.monospaced().weight(.semibold)).textSelection(.enabled)
                        Spacer()
                        Button(copied ? LocalizedStringKey("Kopiert.") : LocalizedStringKey("Kennung kopieren")) { copy(id) }
                            .buttonStyle(.borderless)
                    }
                }
            case .off?: Text("Dieser Server nimmt keine Diagnose an (ab Saga-Server 2.5, vom Admin abschaltbar).").font(.footnote).foregroundStyle(.orange)
            case .tooMany?: Text("Zu viele Diagnosen – bitte später erneut versuchen.").font(.footnote).foregroundStyle(.orange)
            case .failed(let m)?: Text("Diagnose nicht gesendet: \(m)").font(.footnote).foregroundStyle(.red)
            case nil: EmptyView()
            }
        }
    }

    private func send() async {
        busy = true; copied = false
        let r = await ClientLog.shared.sendDiag(note: note)
        busy = false; result = r
        if case .sent = r { note = "" }
    }
    private func copy(_ id: String) {
        #if os(macOS)
        NSPasteboard.general.clearContents(); NSPasteboard.general.setString(id, forType: .string)
        #else
        UIPasteboard.general.string = id
        #endif
        copied = true
    }
}
