import Foundation
import SwiftUI
import WebKit
import CryptoKit
#if canImport(WidgetKit)
import WidgetKit
#endif
#if os(iOS)
import UIKit
#else
import AppKit
#endif

// Page/PageOwner: PageListLogic.swift (Seitenliste im Hintergrund aufbereiten)

struct OpenTask: Decodable, Identifiable, Hashable {
    let id: Int; var text: String; var due: String?; var prio: Int?; var rep: String?; var done: Bool?
    enum CodingKeys: String, CodingKey { case id, text, due, prio, rep = "repeat", done }
}
struct CalEvent: Decodable, Identifiable, Hashable {
    var summary: String; var start: String; var end: String; var allday: Bool
    var location: String?; var description: String?; var color: String?; var calendar: String?
    var href: String?; var etag: String?; var uid: String?; var series: Bool?; var writable: Bool?; var account: Int?; var cal: String?
    var id: String { (href ?? "") + start }
}
struct CalTarget: Identifiable, Hashable { let account: Int; let cal: String; let name: String; let color: String?; var id: String { "\(account)|\(cal)" } }

/// Native Daten fuer den App-Rahmen. Nutzt die ANMELDUNG DER WEB-ANSICHT (Sitzungs-Cookie aus dem gemeinsamen
/// WKWebsiteDataStore) - kein zusaetzlicher Schluessel noetig. Laeuft sie ab -> needsLogin.
@MainActor
final class Store: ObservableObject {
    @Published var pages: [Page] = []
    @Published var needsLogin = false
    /// Anmeldebildschirm abgebrochen (Server bekannt): Zwischenspeicher zeigen, Anmelden ueber den Hinweis oben
    @Published var loginPostponed = false
    @Published var pendingPair: PairRequest?               // Kopplungslink wartet auf Bestaetigung
    @Published var serverVersion: String?                // aus /api/version
    @Published var minApp: String?                       // aelteste App-Version, die der Server voll unterstuetzt
    var appTooOld: Bool { minApp.map { Store.versionLess(Store.appVersion, $0) } ?? false }
    nonisolated static var appVersion: String { Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "0" }
    /// Angezeigte Version: "2.2.0" im offiziellen Release, sonst "2.2.0-rc.N" (Info.plist SagaVersionLabel, tools/version-label.sh).
    /// Vergleiche (minApp) laufen weiter ueber die numerische appVersion.
    nonisolated static var appVersionLabel: String {
        let l = (Bundle.main.infoDictionary?["SagaVersionLabel"] as? String ?? "").trimmingCharacters(in: .whitespaces)
        return l.isEmpty || l.hasPrefix("$(") ? appVersion : l
    }
    nonisolated static var appBuild: String { Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "" }
    /// "1.2.0" < "1.10.0" (SemVer, Zusaetze wie -beta werden ignoriert)
    nonisolated static func versionLess(_ a: String, _ b: String) -> Bool { ShareLogic.versionLess(a, b) }
    /// Server-Version abfragen (oeffentlich, keine Anmeldung noetig); aeltere Server ohne /api/version: nichts anzeigen
    func checkVersion() async {
        guard AppConfig.hasServer, let url = URL(string: AppConfig.server.absoluteString.trimmingCharacters(in: CharacterSet(charactersIn: "/")) + "/api/version"),
              let res = try? await URLSession.shared.data(from: url), (res.1 as? HTTPURLResponse)?.statusCode == 200,
              let j = (try? JSONSerialization.jsonObject(with: res.0)) as? [String: Any] else { serverVersion = nil; minApp = nil; return }
        serverVersion = j["version"] as? String; minApp = j["min_app"] as? String
    }
    @Published var loading = false
    /// Eigenes Profilbild (2.1, aus /api/account): relative Adresse oder nil
    @Published var myAvatar: String?
    @Published var error: String?
    /// 2.5: Fehler eines Links von aussen (z. B. kaputter Kopplungslink) - die Anmeldung zeigt ihn unter dem Feld
    @Published var linkError: String?
    @Published var tasks: [OpenTask] = []
    @Published var doneTasks: [OpenTask] = []
    @Published var events: [CalEvent] = []
    @Published var recent: [Int] = UserDefaults.standard.array(forKey: "recent") as? [Int] ?? []
    private var cookieHeader: [String: String]?
    private var cookieAt = Date.distantPast
    private let images = NSCache<NSString, NSData>()
    private let cacheDir: URL = {
        let d = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("hk", isDirectory: true)
        try? FileManager.default.createDirectory(at: d.appendingPathComponent("files"), withIntermediateDirectories: true)
        return d
    }()

    // Saga 2.0: Live-Kanal + Benachrichtigungen, Chat, Mitteilungen (Push) - eigene Modelle, die den Store benutzen
    let live = LiveHub()
    let notifs = NotificationsModel()
    let chat = ChatModel()
    let push = PushModel()
    let calls = CallManager()                                        // Anrufe (2.1)

    init() {                                                        // sofort aus dem Zwischenspeicher
        // Seitenverweise im Text zeigen den aktuellen Titel (nur auf dem Main-Thread, dort wird gezeichnet)
        PageLinkTitles.lookup = { [weak self] id in
            guard Thread.isMainThread else { return nil }
            return MainActor.assumeIsolated { self?.page(id).map { PageLinkTitles.Target(title: $0.title, icon: $0.icon) } }
        }
        // einmalig nach dem Update: alte Entwuerfe (nur je Server) gehoeren zur aktuell bestehenden Anmeldung
        DraftOwner.app.migrate(origin: AppConfig.serverOrigin, signedIn: DeviceKey.get() != nil || AppConfig.hasToken)
        // Zwischenspeicher der Seitenliste: kleine Listen sofort (erstes Bild wie bisher mit Seitenleiste), grosse im
        // Hintergrund aufbereiten - der Start blockiert dann nicht (60.000 Notizen: ~0,4 s auf dem Main-Thread)
        // Abgleich (2.2): der Stand gehoert zur zwischengespeicherten Liste und wird nur zusammen mit ihr uebernommen
        if let d = cachedData("api/notes") {
            let cur = cachedCursor()
            if d.count < Store.syncCacheLimit {
                if let p = try? PageList.decode(d), let u = PageList.update(p, previous: nil) { applyList(u); noteCursor = cur; savedCursor = cur }
            } else {
                let gen = listGen
                cacheLoad = Task { [weak self] in
                    let u = await Task.detached(priority: .userInitiated) { (try? PageList.decode(d)).flatMap { PageList.update($0, previous: nil) } }.value
                    guard let self else { return }
                    self.cacheLoad = nil
                    guard let u, self.listGen == gen else { return }   // schon frisch vom Server: Zwischenstand nicht mehr zeigen
                    self.applyList(u); self.noteCursor = cur; self.savedCursor = cur
                }
            }
        }
        live.store = self; notifs.store = self; chat.store = self; push.store = self
        AvatarCache.shared.store = self
        calls.store = self; CallSystem.shared.manager = calls
        ChatVideoUploads.shared.attach(self)                           // 2.4: Video-Uploads (auch nach Neustart fortsetzen)
    }

    /// Anfrage mit Sitzung fuer Stroeme (SSE: Live-Kanal, KI-Assistent). Abgelaufene Sitzung (401): einmal still mit dem
    /// Geraete-Schluessel erneuern und wiederholen. Status != 2xx: Antwort-Text (JSON {error}) mitliefern.
    func openStream(_ path: String, method: String = "GET", body: [String: Any]? = nil, headers: [String: String] = [:],
                    session: URLSession) async throws -> (URLSession.AsyncBytes, Int, Data?) {
        guard AppConfig.hasServer, let url = URL(string: AppConfig.server.absoluteString.trimmingCharacters(in: CharacterSet(charactersIn: "/")) + "/" + path)
        else { throw APIError.other(String(localized: "Ungültige Adresse")) }
        for attempt in 0..<2 {
            var req = URLRequest(url: url, timeoutInterval: 90)
            req.httpMethod = method; req.httpShouldHandleCookies = false
            req.setValue("1", forHTTPHeaderField: "X-Hlidskjalf"); req.setValue("1", forHTTPHeaderField: "X-Saga")
            req.setValue("text/event-stream", forHTTPHeaderField: "Accept")
            req.setValue(AppConfig.appLanguage, forHTTPHeaderField: "Accept-Language")
            req.setValue("3", forHTTPHeaderField: "X-Saga-Chat")
            for (k, v) in headers { req.setValue(v, forHTTPHeaderField: k) }
            if let body { req.setValue("application/json", forHTTPHeaderField: "Content-Type"); req.httpBody = try JSONSerialization.data(withJSONObject: body) }
            for (k, v) in await cookies(for: url) { req.setValue(v, forHTTPHeaderField: k) }
            let (bytes, resp) = try await session.bytes(for: req)
            let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
            if code == 401 && attempt == 0 {
                cookieHeader = nil
                if await resumeSession() == .ok { continue }
            }
            guard (200..<300).contains(code) else {
                var d = Data()
                for try await b in bytes { d.append(b); if d.count > 65_536 { break } }
                return (bytes, code, d)
            }
            return (bytes, code, nil)
        }
        throw APIError.http(401, String(localized: "Bitte anmelden"))
    }

    /// Rohdaten senden (verschluesselte Chat-Anhaenge: application/octet-stream)
    func apiRaw(_ path: String, method: String = "POST", data: Data, contentType: String = "application/octet-stream") async throws -> Data {
        guard AppConfig.hasServer, let url = URL(string: AppConfig.server.absoluteString.trimmingCharacters(in: CharacterSet(charactersIn: "/")) + "/" + path)
        else { throw APIError.other(String(localized: "Ungültige Adresse")) }
        var req = URLRequest(url: url, timeoutInterval: 120)
        req.httpMethod = method; req.httpShouldHandleCookies = false
        req.setValue("1", forHTTPHeaderField: "X-Hlidskjalf"); req.setValue(contentType, forHTTPHeaderField: "Content-Type")
        let (d, code) = try await send(req) { try await URLSession.shared.upload(for: $0, from: data) }
        guard (200..<300).contains(code) else {
            throw APIError.http(code, (try? JSONSerialization.jsonObject(with: d) as? [String: Any])?["error"] as? String ?? "")
        }
        return d
    }

    private func cacheURL(_ path: String) -> URL { cacheDir.appendingPathComponent(path.replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "?", with: "_")) }
    func cachedData(_ path: String) -> Data? { try? Data(contentsOf: cacheURL(path)) }
    /// Zwischenspeicher einer Antwort verwerfen (z. B. Freigabe entzogen: fremder Inhalt nicht offline lesbar lassen)
    func dropCache(_ path: String) { try? FileManager.default.removeItem(at: cacheURL(path)) }
    /// GET mit Speichern im Zwischenspeicher (fuer sofortige Anzeige beim naechsten Mal)
    func get(_ path: String) async throws -> Data {
        let d = try await api(path); try? d.write(to: cacheURL(path), options: .atomic); return d
    }

    private func cookies(for url: URL) async -> [String: String] {
        if let h = cookieHeader, Date().timeIntervalSince(cookieAt) < 60 { return h }
        let cs = await WKWebsiteDataStore.default().httpCookieStore.allCookies().filter { Store.cookieMatches($0, url) }
        let h = HTTPCookie.requestHeaderFields(with: cs); cookieHeader = h; cookieAt = Date(); return h
    }

    /// Kopfzeilen einer angemeldeten Anfrage (Sitzungs-Cookie, X-Saga, Sprache) fuer eigene URLSessions - z. B. die
    /// Hintergrund-Uploads von Videos (2.4). `fresh`: Cookie neu lesen (nach einer stillen Erneuerung)
    func authHeaders(for url: URL, fresh: Bool = false) async -> [String: String] {
        if fresh { cookieHeader = nil }
        var h = await cookies(for: url)
        h["X-Hlidskjalf"] = "1"; h["X-Saga"] = "1"; h["X-Saga-Chat"] = "3"
        h["Accept-Language"] = AppConfig.appLanguage
        return h
    }
    /// Volle Adresse eines API-Pfads (nil ohne Server)
    func apiURL(_ path: String) -> URL? {
        guard AppConfig.hasServer else { return nil }
        return URL(string: AppConfig.server.absoluteString.trimmingCharacters(in: CharacterSet(charactersIn: "/")) + "/" + path)
    }

    /// Cookie gehoert zu dieser Adresse? Domain nur an Punktgrenzen (evilexample.com passt nicht auf example.com),
    /// Secure nur ueber https, Pfad-Praefix.
    nonisolated static func cookieMatches(_ c: HTTPCookie, _ url: URL) -> Bool {
        guard let host = url.host?.lowercased() else { return false }
        let d = c.domain.lowercased().trimmingCharacters(in: CharacterSet(charactersIn: "."))
        guard !d.isEmpty, host == d || host.hasSuffix("." + d) else { return false }
        if c.isSecure && url.scheme?.lowercased() != "https" { return false }
        let path = url.path.isEmpty ? "/" : url.path
        return c.path.isEmpty || c.path == "/" || path == c.path || path.hasPrefix(c.path.hasSuffix("/") ? c.path : c.path + "/")
    }

    /// `stale`: Anfrage ist ueberholt (z. B. Abgleich, waehrend eine neue Anmeldung uebernommen wird) - nach einer stillen
    /// Erneuerung (401) dann nicht wiederholen, sondern abbrechen (CancellationError, kein Anmeldefenster)
    func api(_ path: String, method: String = "GET", body: [String: Any]? = nil, timeout: TimeInterval = 20,
             stale: (@MainActor () -> Bool)? = nil) async throws -> Data {
        let (data, code) = try await apiResponse(path, method: method, body: body, timeout: timeout, stale: stale)
        guard (200..<300).contains(code) else {
            let msg = (try? JSONSerialization.jsonObject(with: data) as? [String: Any])?["error"] as? String ?? ""
            throw APIError.http(code, msg)
        }
        return data
    }

    /// Wie `api`, aber Fehlerantworten (4xx/5xx) kommen mit Code zurueck statt als Fehler - fuer Antworten mit
    /// zusaetzlichen Feldern (z. B. 409 need_name beim Veroeffentlichen). Abgelaufene Sitzung wie bei `api`.
    func apiResponse(_ path: String, method: String = "GET", body: [String: Any]? = nil, timeout: TimeInterval = 20,
                     stale: (@MainActor () -> Bool)? = nil) async throws -> (Data, Int) {
        // NICHT appendingPathComponent: das kodiert "?" als %3F und Abfrage-Parameter werden zum Pfad (404)
        guard AppConfig.hasServer else { needsLogin = true; throw APIError.http(401, String(localized: "Bitte Server eintragen")) }   // noch kein Server -> Anmeldebildschirm
        guard let url = URL(string: AppConfig.server.absoluteString.trimmingCharacters(in: CharacterSet(charactersIn: "/")) + "/" + path) else { throw APIError.other(String(localized: "Ungültige Adresse")) }
        var req = URLRequest(url: url, timeoutInterval: timeout)
        req.httpMethod = method
        req.setValue("1", forHTTPHeaderField: "X-Hlidskjalf"); req.httpShouldHandleCookies = false   // Cookie selbst setzen
        if let body { req.setValue("application/json", forHTTPHeaderField: "Content-Type"); req.httpBody = try JSONSerialization.data(withJSONObject: body) }
        return try await send(req, stale: stale) { try await URLSession.shared.data(for: $0) }
    }

    /// Anfrage mit Sitzungs-Cookie; abgelaufene Sitzung (401) -> einmal still mit dem Geraete-Schluessel erneuern
    /// und wiederholen. Erst wenn das nicht geht, kommt das Anmeldefenster.
    /// Abgebrochen (Task) oder ueberholt (`stale`): nicht erneuern/wiederholen, sondern CancellationError.
    private func send(_ base: URLRequest, stale: (@MainActor () -> Bool)? = nil,
                      _ run: (URLRequest) async throws -> (Data, URLResponse)) async throws -> (Data, Int) {
        for attempt in 0..<2 {
            var req = base
            if let url = req.url { for (k, v) in await cookies(for: url) { req.setValue(v, forHTTPHeaderField: k) } }
            req.setValue(AppConfig.appLanguage, forHTTPHeaderField: "Accept-Language")   // Fehlertexte des Servers in der App-Sprache
            req.setValue("3", forHTTPHeaderField: "X-Saga-Chat")          // 2 = saga-chat/v2 (2.3), 3 = Chat-Stufe 3 (Handles, 2.5)
            let (data, resp) = try await run(req)
            let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
            guard code == 401 else { return (data, code) }
            cookieHeader = nil
            guard attempt == 0 else { break }
            if Task.isCancelled || stale?() == true { throw CancellationError() }
            let r = await resumeSession()
            if r == .offline { throw APIError.other(String(localized: "Server nicht erreichbar")) }
            if r == .rejected { break }
            // inzwischen neu angemeldet (evtl. anderes Konto): nicht mit dem alten Stand wiederholen
            if Task.isCancelled || stale?() == true { throw CancellationError() }
        }
        if !loginPostponed { needsLogin = true }                          // abgebrochen: nicht bei jeder Anfrage wieder aufdraengen
        throw APIError.http(401, String(localized: "Bitte anmelden"))
    }

    /// Anfrage mit Sitzung (z. B. Profilbilder): wie api, aber ohne JSON und mit eigenem URLRequest
    func sendQuiet(_ req: URLRequest) async throws -> (Data, Int) { try await send(req) { try await URLSession.shared.data(for: $0) } }

    enum ResumeResult { case ok, rejected, offline }
    private var resuming: Task<ResumeResult, Never>?

    /// Sitzung mit dem Geraete-Schluessel erneuern (gleichzeitige Anfragen teilen sich einen Versuch)
    func resumeSession() async -> ResumeResult {
        if let t = resuming { return await t.value }
        let t = Task { await doResume() }
        resuming = t
        let r = await t.value
        resuming = nil
        return r
    }

    private func doResume() async -> ResumeResult {
        // Schluessel nur an den Server, der ihn ausgestellt hat, und nur verschluesselt
        guard AppConfig.hasServer, AppConfig.isAllowedServer(AppConfig.server), let tok = DeviceKey.get() else { return .rejected }
        let url = AppConfig.server.appendingPathComponent("app/resume")
        var req = URLRequest(url: url, timeoutInterval: 20)
        req.httpMethod = "POST"; req.httpShouldHandleCookies = false
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        var body: [String: Any] = ["device_token": tok]
        if AppConfig.hasToken { body["app_key"] = AppConfig.token }      // Widget-Schluessel diesem Geraet zuordnen (Einstellungen: eine Liste)
        req.httpBody = try? JSONSerialization.data(withJSONObject: body)
        guard let r = try? await URLSession.shared.data(for: req), let http = r.1 as? HTTPURLResponse else { return .offline }
        if http.statusCode == 401 || http.statusCode == 403 { DeviceKey.set(nil); return .rejected }   // abgemeldet/gesperrt (gilt nur fuer diesen Server)
        guard http.statusCode == 200 else { return .offline }
        await storeCookies(http, url)
        return .ok
    }

    private func storeCookies(_ http: HTTPURLResponse, _ url: URL) async {
        let headers = http.allHeaderFields.reduce(into: [String: String]()) { r, kv in if let k = kv.key as? String, let v = kv.value as? String { r[k] = v } }
        for c in HTTPCookie.cookies(withResponseHeaderFields: headers, for: url) { await WKWebsiteDataStore.default().httpCookieStore.setCookie(c) }
        cookieHeader = nil
    }

    /// Antwort der Anmeldung: Geraete-Schluessel sichern, App-Schluessel (Widget/Teilen) uebernehmen, falls noch keiner da ist
    /// Rueckgabe false: Geraete-Schluessel kam, liess sich aber nicht im Schluesselbund speichern (BUG-104)
    /// `takeAppKey: false`: App-Schluessel nicht uebernehmen, nur zurueckgeben (Neuanmeldung: erst Konto pruefen)
    @discardableResult
    private func adoptKeys(_ d: Data, takeAppKey: Bool = true) -> (ok: Bool, appKey: String?) {
        let j = (try? JSONSerialization.jsonObject(with: d)) as? [String: Any]
        var ok = true
        if let t = j?["device_token"] as? String, t.hasPrefix("hkd_") { DeviceKey.set(t); ok = DeviceKey.stored() == t }
        let k = (j?["app_key"] as? String).flatMap { $0.hasPrefix("hk_") ? $0 : nil }
        if takeAppKey, let k, !AppConfig.hasToken { AppConfig.token = k; reloadWidgets() }
        return (ok, k)
    }
    /// Geraete-Anmeldung beim Server loeschen, der sie ausgestellt hat (nie an einen anderen senden)
    private func forgetDevice(_ tok: String, origin: String?) async {
        guard let o = origin, let base = URL(string: o), AppConfig.isAllowedServer(base) else { return }
        var req = URLRequest(url: base.appendingPathComponent("app/forget"), timeoutInterval: 10)
        req.httpMethod = "POST"; req.httpShouldHandleCookies = false
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.httpBody = try? JSONSerialization.data(withJSONObject: ["device_token": tok])
        _ = try? await URLSession.shared.data(for: req)
    }
    /// Text, wenn der Geraete-Schluessel nach Anmeldung/Kopplung nicht gespeichert werden konnte
    static var keychainFailedText: String {
        String(localized: "Der Geräte-Schlüssel konnte nicht im Schlüsselbund gespeichert werden. Die Anmeldung hält deshalb nur einige Stunden.")
    }
    private func reloadWidgets() {
        #if canImport(WidgetKit)
        WidgetCenter.shared.reloadAllTimelines()
        #endif
    }

    /// Schon angemeldet (z. B. vor diesem Update), aber noch kein Geraete-Schluessel -> einmal still nachholen
    private var deviceKeyTried = false
    private func ensureDeviceKey() async {
        guard !deviceKeyTried, DeviceKey.get() == nil else { return }
        deviceKeyTried = true
        if let d = try? await api("app/device", method: "POST", body: ["device": DeviceKey.deviceName, "app_key": !AppConfig.hasToken]) { adoptKeys(d) }
    }

    private var lastRefresh = Date.distantPast
    /// Laufender Abgleich und ggf. ein danach geplanter: gleichzeitige Aufrufe (Aktion + Takt + Fenster) teilen sich
    /// einen Lauf. Wer waehrend eines Laufs kommt, bekommt genau EINEN weiteren Lauf danach (sieht also sicher den
    /// Stand nach seiner Aktion) - statt jeder Aufruf einen eigenen vollen Abgleich.
    private var refreshRun: (token: Int, task: Task<Void, Never>)?
    private var refreshFollow: Task<Void, Never>?
    private var refreshToken = 0
    func refresh(quiet: Bool = false) async {
        guard gate.canStart else { return }                               // neue Anmeldung wird uebernommen: danach gleich ein Abgleich
        if !quiet { loading = true }
        defer { if !quiet { loading = false } }
        if let f = refreshFollow { await f.value; return }
        if let r = refreshRun?.task {
            let f = Task { await r.value; if Task.isCancelled { return }; await self.runRefresh() }   // abgebrochen (Anmeldung): nicht mehr
            refreshFollow = f
            await f.value
            return
        }
        await runRefresh()
    }
    private func runRefresh() async {
        refreshFollow = nil
        refreshToken += 1
        let tok = refreshToken
        let t = Task { await self.doRefresh() }
        refreshRun = (tok, t)
        await t.value
        if refreshRun?.token == tok { refreshRun = nil }
    }
    private func doRefresh() async {
        guard gate.canStart, !Task.isCancelled else { return }
        lastRefresh = Date()
        if let c = cacheLoad { await c.value }                           // grosser Zwischenspeicher: erst fertig lesen (Grundlage des Abgleichs)
        let ge = gate.epoch                                               // neue Anmeldung danach: diesen Lauf verwerfen
        do {
            let gen = sessionGen, ins = localInserts, ep = cursorEpoch
            // Abgleich (2.2): mit gueltigem Stand nur Geaendertes holen, sonst den ganzen Baum mit Stand.
            // Aeltere Server ignorieren beides und liefern die Liste wie bisher.
            let origin = AppConfig.serverOrigin, account = origin.flatMap { DraftOwner.app.account(origin: $0) }
            let since = noteCursor.flatMap { $0.usable(origin: origin, account: account) ? $0 : nil }
            let d: Data
            do { d = try await api(NoteSync.path(since: since?.value), stale: { [unowned self] in self.gate.stale(startedAt: ge) }) }
            catch APIError.http(let code, let msg) where since != nil && code != 401 {
                if cursorEpoch == ep { noteCursor = nil }                  // Stand abgelehnt (z. B. 400): naechstes Mal ganz laden
                throw APIError.http(code, msg)
            }
            // Dekodieren, Einarbeiten, Vergleich und Index im Hintergrund; auf dem Main-Thread nur noch zuweisen
            let prev = list
            let (o, u) = try await Task.detached(priority: .userInitiated) { () throws -> (NoteSync.Outcome, PageList.Update?) in
                let o = NoteSync.outcome(try NoteSync.parse(d), base: prev.all, sentSince: since != nil)
                return (o, o.pages.flatMap { PageList.update($0, previous: prev) })
            }.value
            guard !Task.isCancelled, !gate.stale(startedAt: ge) else { return }        // neue Anmeldung: die holt selbst ab
            guard gen == sessionGen, AppConfig.serverOrigin == origin else { return }   // inzwischen abgemeldet/anderes Konto/anderer Server
            // waehrend der Anfrage lokal eine Seite angelegt: diese Antwort kennt sie evtl. noch nicht - nicht uebernehmen
            // (sonst verschwaende sie kurz), sondern gleich danach noch einmal abgleichen
            // ... oder der Stand wurde inzwischen verworfen (Live "reset", Anmeldung): Antwort verwerfen, neu abgleichen
            if localInserts != ins || cursorEpoch != ep { Task { await self.refresh(quiet: true) }; return }
            var cur: NoteCursor?
            if let c = o.cursor, let og = origin, let a = account {
                let fullAt: Double = o.full ? Date().timeIntervalSince1970 : (since?.fullAt ?? 0)
                cur = NoteCursor(value: c, origin: og, account: a, fullAt: fullAt)
            }
            noteCursor = cur
            if let u { applyList(u) }
            // Zwischenspeicher nur bei Aenderung schreiben (bisher jede Minute die ganze Antwort), nicht auf dem Main-Thread:
            // die Liste (Array wie bisher) und danach ihr Stand. Liste unveraendert: nur den Stand (passt zur Liste auf
            // der Platte, die ja dieselbe ist).
            if u != nil || listUnsaved || cur != savedCursor {
                let all: [Page]? = u != nil || listUnsaved ? list.all : nil
                let raw = o.legacy && u != nil && !listUnsaved ? d : nil   // alter Server: rohe Antwort wie bisher (= die neue Liste)
                listUnsaved = false; savedCursor = cur
                let url = cacheURL("api/notes"), curURL = cacheURL(Store.cursorCache)
                Store.cacheQueue.async {
                    if let all {
                        guard let data = raw ?? NoteSync.encodeCache(all) else { try? FileManager.default.removeItem(at: curURL); return }
                        try? data.write(to: url, options: .atomic)
                    }
                    if let cur, let c = try? JSONEncoder().encode(cur) { try? c.write(to: curURL, options: .atomic) }
                    else { try? FileManager.default.removeItem(at: curURL) }
                }
            }
            if o.refetch { Task { await self.refresh(quiet: true) } }    // Stand wich ab: gleich die ganze Liste holen
            if needsLogin { needsLogin = false }
            if loginPostponed { loginPostponed = false }          // Sitzung wieder da (z. B. still erneuert)
            if Task.isCancelled || gate.stale(startedAt: ge) { return }       // neue Anmeldung: Rest laedt deren Abgleich
            await loadShared()
            Task { await loadFedShared() }                        // fremde Server koennen dauern: nicht darauf warten
            Task { await notifs.loadCount(); await chat.refreshUnread(); await loadAIStatus() }
            if error != nil, error != Store.keychainFailedText { error = nil }   // Schluesselbund-Problem bleibt sichtbar (BUG-104)
            if serverVersion == nil { await checkVersion() }
            await loadToday()
            await ensureDeviceKey()
            // Konto fuer die Entwuerfe: einmal je Start, und solange es fuer diesen Server noch unbekannt ist
            if !accountChecked || AppConfig.serverOrigin.flatMap({ DraftOwner.app.account(origin: $0) }) == nil {
                accountChecked = true; await identifyAccount()
            }
            Task { await resendDrafts() }                         // offline Geaendertes nachsenden (BUG-020)
        } catch {
            if Store.isCancel(error) || gate.stale(startedAt: ge) { return }      // abgebrochen (neue Anmeldung): keine Meldung
            if !needsLogin, self.error != error.localizedDescription { self.error = error.localizedDescription }
        }
    }
    nonisolated static func isCancel(_ e: Error) -> Bool { e is CancellationError || (e as? URLError)?.code == .cancelled }
    /// Liegengebliebene Entwuerfe (offline geaendert, Seite inzwischen verlassen) senden, sobald der Server wieder
    /// antwortet - mit der Revision, auf der sie beruhen: hat sich die Seite inzwischen geaendert (409), wird nichts
    /// ueberschrieben, der Entwurf wird dann beim Oeffnen angeboten. Offene Seiten senden selbst (BUG-020).
    private var resendingDrafts = false
    func resendDrafts() async {
        guard !resendingDrafts, let o = AppConfig.serverOrigin, DraftOwner.app.account(origin: o) != nil,
              let ds = DraftOwner.app.store(origin: o) else { return }
        resendingDrafts = true; defer { resendingDrafts = false }
        let gen = sessionGen
        for f in ds.files {
            guard let id = DraftResend.pageID(f.lastPathComponent), let dr = ds.load(id),
                  DraftResend.shouldSend(dr, id: id, open: SaveRegistry.openIDs) else { continue }
            let b: [String: Any] = ["title": dr.title, "body": dr.body, "format": "blocks", "rev": dr.rev]
            guard let (_, code) = try? await apiResponse("api/notes/\(id)", method: "PATCH", body: b) else { return }   // offline: spaeter
            // abgemeldet/anderes Konto oder Seite inzwischen geoeffnet bzw. Entwurf geaendert: nichts mehr anfassen
            guard gen == sessionGen, AppConfig.serverOrigin == o, ds.load(id) == dr else { return }
            switch DraftResend.outcome(code) {
            case .sent: ds.remove(id); dropCache("api/notes/\(id)"); updateTitle(id, dr.title)
            case .hold: var c = dr; c.conflict = true; ds.save(id, c)
            case .retry: return
            }
        }
    }
    /// 60-s-Abgleich: still, hoechstens einmal pro Takt fuer alle Fenster, und nur Geaendertes neu setzen
    /// (sonst zeichnen alle Ansichten mit dem Store jede Minute neu). Bei verbundenem Live-Kanal seltener (RefreshPolicy).
    func periodicRefresh() async {
        guard gate.canStart else { return }
        let now = Date()
        if RefreshPolicy.due(last: lastRefresh, now: now, liveConnected: live.connected, treeEvents: live.treeEvents) { await refresh(quiet: true); return }
        // Live-Kanal verbunden, Seitenbaum noch nicht wieder dran: alles andere (klein) weiter jede Minute wie bisher -
        // Mit mir geteilt, andere Server, Glocke, Chats, KI, Heute (Aufgaben/Termine), liegengebliebene Entwuerfe
        guard RefreshPolicy.due(last: max(lastRefresh, lastLight), now: now, liveConnected: false), !needsLogin else { return }
        lastLight = now
        await loadShared()
        Task { await loadFedShared() }
        Task { await notifs.loadCount(); await chat.refreshUnread(); await loadAIStatus() }
        await loadToday()
        Task { await resendDrafts() }
    }
    private var lastLight = Date.distantPast
    /// Live: Seitenbaum geaendert (notes.tree - neu/geaendert/verschoben/Papierkorb/Archiv, auch aus Web und von
    /// anderen Geraeten). Kurz entprellt, dann EIN Abgleich (Inhalt des Ereignisses wird bewusst nicht ausgewertet:
    /// Zusatzfelder/neue Arten schaden nicht, der Abgleich bringt den Stand des Servers)
    private var treeDebounce: Task<Void, Never>?
    func treeChanged() {
        treeDebounce?.cancel()
        treeDebounce = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(1200))
            guard !Task.isCancelled, let self else { return }
            await self.refresh(quiet: true)
        }
    }
    /// Live-Kanal (wieder) verbunden: Verpasstes nachholen (Netzwechsel, Aufwachen, Server-Neustart)
    func liveConnected() async {
        guard RefreshPolicy.catchUp(last: lastRefresh, now: Date()) else { return }
        await refresh(quiet: true)
    }

    func loadToday() async {
        struct T: Decodable { let open: [OpenTask]; let done: [OpenTask]? }
        if let d = try? await api("api/tasks"), let t = try? JSONDecoder().decode(T.self, from: d) {
            if tasks != t.open { tasks = t.open }
            if doneTasks != (t.done ?? []) { doneTasks = t.done ?? [] }
        }
        if events.isEmpty, let c = cachedEvents(days: 1), !c.isEmpty { events = c }   // sofort anzeigen
        if let e = await self.events(days: 1), e != events { events = e }            // Fehler: bisherigen Stand behalten
    }

    func search(_ q: String) async -> [Page] {
        guard q.trimmingCharacters(in: .whitespaces).count >= 2,
              let d = try? await api("api/notes/search", method: "POST", body: ["q": q]) else { return [] }
        return (try? JSONDecoder().decode([Page].self, from: d)) ?? []
    }

    func newPage(parent: Int? = nil) async -> Int? {
        struct R: Decodable { let id: Int; let updated: Double? }
        var b: [String: Any] = ["title": ""]
        if let parent { b["parent_id"] = parent }
        guard let d = try? await api("api/notes", method: "POST", body: b), let r = try? JSONDecoder().decode(R.self, from: d) else { return nil }
        await insertCreated(.created(id: r.id, parent: parent, updated: r.updated ?? Date().timeIntervalSince1970))
        return r.id
    }

    /// Gerade angelegte Seite/Eintrag lokal in die Liste (statt die ganze Seitenliste neu zu laden). Gehoert die
    /// Elternseite nicht zum eigenen Baum (Unterseite einer geteilten Seite), wie bisher neu laden.
    func insertCreated(_ p: Page) async {
        if FedLogic.isRemote(p.id) { await loadFedShared(); return }         // Unterseite/Zeile einer fremden Seite (2.3)
        for _ in 0..<3 {
            if list.ids.contains(p.id) { return }                               // ein Abgleich war schneller
            // noch keine Liste (offline gestartet, nichts zwischengespeichert) oder fremder Baum: wie bisher neu laden
            guard !list.all.isEmpty, let all = PageList.inserting(p, into: list.all, ids: list.ids) else { await refresh(); return }
            let prev = list, gen = listGen
            let u = await Task.detached(priority: .userInitiated) { PageList.update(all, previous: prev) }.value
            guard listGen == gen else { continue }                              // Liste hat sich inzwischen geaendert: neu einfuegen
            if let u { applyList(u); localInserts += 1; listUnsaved = true }
            return
        }
        await refresh()
    }
    /// zaehlt lokal eingefuegte Seiten (ein gleichzeitig laufender Abgleich mit aelterem Stand wird dann wiederholt)
    private var localInserts = 0

    // MARK: Abgleich des Seitenbaums (2.2, NoteSyncLogic.swift)
    /// Stand der Liste `list` (nur zusammen mit ihr gesetzt); nil = beim naechsten Mal den ganzen Baum (delta=1)
    private var noteCursor: NoteCursor?
    /// Stand, der zuletzt in den Zwischenspeicher geschrieben wurde (gehoert zur Liste auf der Platte)
    private var savedCursor: NoteCursor?
    /// Liste lokal geaendert (eingefuegt, Titel ...), aber noch nicht in den Zwischenspeicher geschrieben: dann beim
    /// naechsten Abgleich mitschreiben - sonst passte ein neuerer Stand auf der Platte nicht mehr zur alten Liste
    private var listUnsaved = false
    /// grosser Zwischenspeicher wird beim Start im Hintergrund gelesen (der erste Abgleich wartet darauf)
    private var cacheLoad: Task<Void, Never>?
    /// zaehlt verworfene Staende (laufender Abgleich mit altem Stand wird dann nicht uebernommen)
    private var cursorEpoch = 0
    static let cursorCache = "api/notes.cursor"
    private func cachedCursor() -> NoteCursor? { cachedData(Store.cursorCache).flatMap { try? JSONDecoder().decode(NoteCursor.self, from: $0) } }
    /// Stand verwerfen: naechster Abgleich holt den ganzen Baum (Live "reset", Anmeldung, Serverwechsel)
    func forgetNoteCursor() {
        cursorEpoch += 1; noteCursor = nil; savedCursor = nil
        let u = cacheURL(Store.cursorCache)
        Store.cacheQueue.async { try? FileManager.default.removeItem(at: u) }
    }

    // MARK: Neuanmeldung ohne Abmelden (AccountSwitchLogic.swift)
    /// Waehrend eine neue Anmeldung uebernommen wird: kein Abgleich; laufende werden abgebrochen, nicht wiederholt
    private var gate = RefreshGate()
    private func beginAdopt() {
        gate.beginAdopt()
        refreshRun?.task.cancel(); refreshFollow?.cancel(); refreshFollow = nil; treeDebounce?.cancel()
    }
    /// Ausgeblendeter Stand des bisherigen Kontos (bis feststeht, ob die neue Anmeldung dasselbe Konto ist)
    private struct HiddenView { var pages, archived, shared, fedShared: [Page]; var tasks, doneTasks: [OpenTask]; var events: [CalEvent]; var recent: [Int] }
    private var hiddenView: HiddenView?
    private func hideAccountView() {
        hiddenView = HiddenView(pages: pages, archived: archivedPages, shared: shared, fedShared: fedShared,
                                tasks: tasks, doneTasks: doneTasks, events: events, recent: recent)
        pages = []; archivedPages = []; shared = []; fedShared = []; tasks = []; doneTasks = []; events = []; recent = []
    }
    /// Gleiches Konto: alles wie vorher zeigen (Liste und Abgleich-Stand bleiben - kein Neuladen des ganzen Baums)
    private func restoreAccountView() {
        guard let h = hiddenView else { return }
        hiddenView = nil
        pages = h.pages; archivedPages = h.archived; shared = h.shared; fedShared = h.fedShared
        tasks = h.tasks; doneTasks = h.doneTasks; events = h.events; recent = h.recent
    }
    /// Bisheriges Konto (vor der Neuanmeldung)
    private func currentOwner() -> AccountSwitch.Owner {
        let o = AppConfig.serverOrigin
        return AccountSwitch.owner(origin: o, known: o.flatMap { DraftOwner.app.account(origin: $0) }, cursor: noteCursor ?? savedCursor)
    }

    // MARK: Aufgaben (wie das Web: /api/tasks; Wiederholungen legt der Server beim Abhaken an)
    func addTask(_ text: String, due: String? = nil, prio: Int = 2, rep: String? = nil) async {
        var b: [String: Any] = ["text": text, "prio": prio]
        if let due { b["due"] = due }; if let rep { b["repeat"] = rep }
        let f = DateFormatter.isoDay(); b["today"] = f.string(from: .now)
        _ = try? await api("api/tasks", method: "POST", body: b); await loadToday()
    }
    func updateTask(_ id: Int, _ fields: [String: Any]) async { _ = try? await api("api/tasks/\(id)", method: "PATCH", body: fields); await loadToday() }
    func deleteTask(_ id: Int) async { tasks.removeAll { $0.id == id }; doneTasks.removeAll { $0.id == id }; _ = try? await api("api/tasks/\(id)", method: "DELETE"); await loadToday() }

    // MARK: Termine (externe Kalender)
    // Termine: sofort aus dem Zwischenspeicher, gleiche Anfragen zusammengelegt, laufen weiter wenn die Ansicht verschwindet
    @Published var eventsTick = 0                          // "Aktualisieren" -> Listen laden frisch
    private var evInflight: [String: Task<[CalEvent]?, Never>] = [:]
    private struct EvResp: Decodable { let events: [CalEvent] }
    private func evPath(_ days: Int) -> String {
        let f = DateFormatter.isoDay()
        let t0 = Calendar.current.startOfDay(for: .now), t1 = Calendar.current.date(byAdding: .day, value: days, to: t0)!
        return "api/calendar/events?start=\(f.string(from: t0))&end=\(f.string(from: t1))"
    }
    func cachedEvents(days: Int) -> [CalEvent]? {
        cachedData(evPath(days)).flatMap { try? JSONDecoder().decode(EvResp.self, from: $0).events }
    }
    /// nil = Fehler (dann den bisherigen Stand behalten, nicht leeren)
    func events(days: Int, fresh: Bool = false) async -> [CalEvent]? {
        let base = evPath(days), key = base + (fresh ? "&fresh=1" : "")
        if let running = evInflight[key] { return await running.value }
        let task = Task { () -> [CalEvent]? in
            guard let d = try? await self.api(key), let e = try? JSONDecoder().decode(EvResp.self, from: d) else { return nil }
            try? d.write(to: self.cacheURL(base), options: .atomic)
            return e.events
        }
        evInflight[key] = task
        let r = await task.value
        evInflight[key] = nil
        return r
    }
    func refreshEvents() { eventsTick += 1 }

    /// Eintraege angelegt/geaendert (Schaltflaeche, "Neu", Eigenschaften, Peek geschlossen) -> Datenbank-Ansichten laden neu
    @Published var dataTick = 0
    func dataChanged() { dataTick += 1 }

    /// Startseite dieses Geraets (wird beim App-Start geoeffnet)
    @Published var homePage: Int? = UserDefaults.standard.object(forKey: "hk.homePage") as? Int
    /// Startseite merkt sich ihr Konto (Server + Benutzername): ein anderes Konto oeffnet sie nie (BUG-060)
    func setHome(_ id: Int?) {
        homePage = id
        let d = UserDefaults.standard
        if let id {
            d.set(id, forKey: "hk.homePage")
            if let o = AppConfig.serverOrigin, let a = DraftOwner.app.account(origin: o) { d.set(HomeOwner.key(origin: o, account: a), forKey: "hk.homeOwner") }
        } else { d.removeObject(forKey: "hk.homePage"); d.removeObject(forKey: "hk.homeOwner") }
    }
    /// Konto erkannt: Startseite eines anderen Kontos vergessen
    private func checkHomeOwner(origin: String, account: String) {
        if homePage != nil, HomeOwner.mustForget(stored: UserDefaults.standard.string(forKey: "hk.homeOwner"), origin: origin, account: account) { setHome(nil) }
        else if homePage != nil, UserDefaults.standard.string(forKey: "hk.homeOwner") == nil { setHome(homePage) }   // alte Fassung: Besitzer nachtragen
    }
    func calTargets() async -> [CalTarget] {
        guard let d = try? await api("api/settings/calendars"), let j = try? JSONSerialization.jsonObject(with: d) as? [String: Any] else { return [] }
        return (j["accounts"] as? [[String: Any]] ?? []).flatMap { a in
            (a["calendars"] as? [[String: Any]] ?? []).compactMap { c -> CalTarget? in
                guard c["show"] as? Bool != false, c["writable"] as? Bool != false, let href = c["href"] as? String, let aid = a["id"] as? Int else { return nil }
                return CalTarget(account: aid, cal: href, name: ((a["label"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? (a["username"] as? String ?? "")) + " · " + (c["name"] as? String ?? ""), color: c["color"] as? String)
            }
        }
    }
    func saveEvent(_ e: [String: Any], edit: Bool) async throws { _ = try await api("api/calendar/events", method: edit ? "PATCH" : "POST", body: e); await loadToday() }
    func deleteEvent(_ e: CalEvent) async throws {
        _ = try await api("api/calendar/events/delete", method: "POST", body: ["account": e.account ?? 0, "cal": e.cal ?? "", "href": e.href ?? "", "etag": e.etag ?? ""]); await loadToday()
    }

    func completeTask(_ t: OpenTask) async {
        tasks.removeAll { $0.id == t.id }
        _ = try? await api("api/tasks/\(t.id)", method: "PATCH", body: ["done": true])
        await loadToday()
    }

    func visited(_ id: Int) {
        recent.removeAll { $0 == id }; recent.insert(id, at: 0); recent = Array(recent.prefix(8))
        UserDefaults.standard.set(recent, forKey: "recent")
    }

    // MARK: Mit mir geteilt (1.3)
    /// Fuer mich freigegebene fremde Seiten (flach; root = oberste geteilte Seite). Aeltere Server: leer.
    @Published var shared: [Page] = []
    /// Teilen abgeschaltet, aber Freigaben vorhanden -> Hinweis in der Seitenleiste
    @Published var shareOffHint = false
    func loadShared() async {
        guard let (d, code) = try? await apiResponse("api/shared") else { return }      // offline: bisherigen Stand behalten
        guard code == 200, let list = try? JSONDecoder().decode([Page].self, from: d) else {
            if code == 404 || code == 405 { if !shared.isEmpty { shared = [] }; shareOffHint = false }   // alter Server: alles Neue aus
            return
        }
        // nicht mehr geteilt: zwischengespeicherte Inhalte dieser Seiten verwerfen (offline nicht mehr lesbar)
        let gone = Set(shared.map(\.id)).subtracting(list.map(\.id)).subtracting(pages.map(\.id))
        for g in gone { dropCache("api/notes/\(g)"); dropCache("api/notes/\(g)/rows") }
        if list != shared { shared = list }
        var hint = false
        if list.isEmpty, let (i, c) = try? await apiResponse("api/share/info"), c == 200, let info = SharedInfo(data: i) {
            hint = info.showOffHint(sharedCount: 0)
        }
        if hint != shareOffHint { shareOffHint = hint }
    }
    /// Oberste geteilte Seiten bzw. Unterseiten einer geteilten Seite (ohne Datenbank-Eintraege, wie im eigenen Baum);
    /// enthaelt Seiten anderer Saga-Instanzen (Foederation, negative IDs)
    func sharedChildren(of id: Int?) -> [Page] {
        let all = shared + fedShared
        guard let id else { return all.filter { $0.root == true } }
        if all.first(where: { $0.id == id })?.isDatabase == true { return [] }
        return all.filter { $0.parent_id == id && $0.root != true }
    }

    // MARK: Foederation (2.0): mit mir geteilte Seiten anderer Saga-Instanzen
    @Published var fedShared: [Page] = []
    /// KI-Assistent fuer dieses Konto eingeschaltet (1.6)
    @Published var aiEnabled = false
    /// Persona der KI (2.1): Name + Profilbild; nil = aelterer Server (bisherige Beschriftung)
    @Published var aiPersona: AIPersona?
    /// "KI in Chats erlauben" auf dem Server an (2.1) und Ziel der Inhalte (host)
    @Published var aiChat = false
    @Published var aiHost = ""
    /// Server, die gerade nicht erreichbar sind (dezent anzeigen, kein Fehler)
    @Published var fedUnreachable: [String] = []
    private var fedLoading = false
    /// Getrennt laden (bis ~4 s je fremdem Server) - die Seitenleiste wartet nicht darauf
    func loadFedShared() async {
        guard !fedLoading else { return }
        fedLoading = true; defer { fedLoading = false }
        guard let (d, code) = try? await apiResponse("api/fed/shared") else { return }
        guard code == 200 else { if code == 404 || code == 405 { if !fedShared.isEmpty { fedShared = [] }; fedUnreachable = [] }; return }
        let r = FedLogic.parseShared(d, as: Page.self)
        let gone = Set(fedShared.map(\.id)).subtracting(r.items.map(\.id))
        for g in gone { dropCache("api/notes/\(g)") }
        if r.items != fedShared { fedShared = r.items }
        if r.unreachable != fedUnreachable { fedUnreachable = r.unreachable }
    }

    // Seitenbaum: oberste Ebene + Kinder (Datenbank-Zeilen erscheinen nicht im Baum, wie im Web)
    // Nachschlagen ueber den im Hintergrund gebauten Index (bisher lineare Suche ueber alle Notizen je Aufruf)
    func page(_ id: Int) -> Page? { pageIndex(id).map { pages[$0] } ?? shared.first { $0.id == id } ?? fedShared.first { $0.id == id } }
    func children(of id: Int?) -> [Page] {
        if let id, page(id)?.isDatabase == true { return [] }             // Datenbank-Eintraege nicht im Baum
        if pages.count == list.visible.count, let pos = list.kids[id] {
            let out = pos.compactMap { $0 < pages.count && pages[$0].parent_id == id ? pages[$0] : nil }
            if out.count == pos.count { return out }
        } else if pages.count == list.visible.count && list.kids[id] == nil { return [] }
        return pages.filter { $0.parent_id == id }                        // Index passt nicht: wie bisher suchen
    }
    var favorites: [Page] { pages.filter { $0.pinned == true } }

    /// Datei hochladen (wie der Web-Editor: Formularfeld "image") -> /files/<id>
    /// `note`: Seite eines anderen Kontos (Rolle Bearbeiten) - die Datei gehoert dann deren Eigentuemer (X-Saga-Note)
    func upload(_ data: Data, name: String, note: Int? = nil) async throws -> String {
        let url = AppConfig.server.appendingPathComponent("api/files")
        var req = URLRequest(url: url, timeoutInterval: 60)
        req.httpMethod = "POST"; req.httpShouldHandleCookies = false
        req.setValue("1", forHTTPHeaderField: "X-Hlidskjalf")
        if let note { req.setValue(String(note), forHTTPHeaderField: "X-Saga-Note") }
        let boundary = "hk-" + UUID().uuidString
        req.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
        var body = Data()
        body.append("--\(boundary)\r\nContent-Disposition: form-data; name=\"image\"; filename=\"\(name)\"\r\nContent-Type: application/octet-stream\r\n\r\n".data(using: .utf8)!)
        body.append(data); body.append("\r\n--\(boundary)--\r\n".data(using: .utf8)!)
        let (d, code) = try await send(req) { try await URLSession.shared.upload(for: $0, from: body) }
        let j = (try? JSONSerialization.jsonObject(with: d)) as? [String: Any]
        guard code == 200, let f = j?["file"] as? [String: Any], let u = f["url"] as? String else {
            throw APIError.other(j?["error"] as? String ?? String(localized: "Upload fehlgeschlagen"))
        }
        return u
    }

    /// Datei-Anhang hochladen (Formularfeld "file": beliebige Datei) -> {url, name, size, extension, title}
    func uploadFile(_ data: Data, name: String, note: Int? = nil) async throws -> [String: Any] {
        let url = AppConfig.server.appendingPathComponent("api/files")
        var req = URLRequest(url: url, timeoutInterval: 120)
        req.httpMethod = "POST"; req.httpShouldHandleCookies = false
        req.setValue("1", forHTTPHeaderField: "X-Hlidskjalf")
        if let note { req.setValue(String(note), forHTTPHeaderField: "X-Saga-Note") }
        let boundary = "hk-" + UUID().uuidString
        req.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
        let safe = name.replacingOccurrences(of: "\"", with: "_")
        var body = Data()
        body.append("--\(boundary)\r\nContent-Disposition: form-data; name=\"file\"; filename=\"\(safe)\"\r\nContent-Type: application/octet-stream\r\n\r\n".data(using: .utf8)!)
        body.append(data); body.append("\r\n--\(boundary)--\r\n".data(using: .utf8)!)
        let (d, code) = try await send(req) { try await URLSession.shared.upload(for: $0, from: body) }
        let j = (try? JSONSerialization.jsonObject(with: d)) as? [String: Any]
        guard code == 200, let f = j?["file"] as? [String: Any] else {
            throw APIError.other(j?["error"] as? String ?? String(localized: "Upload fehlgeschlagen"))
        }
        return f
    }

    /// Bild-Icon (/files/...) mit der Sitzung laden
    func imageData(_ path: String) async -> Data? {
        // Dateien aendern sich nie (eigene Adresse je Upload) -> Speicher + Platte
        if let m = images.object(forKey: path as NSString) { return m as Data }
        let disk = cacheDir.appendingPathComponent("files").appendingPathComponent(path.replacingOccurrences(of: "/", with: "_"))
        if let d = try? Data(contentsOf: disk) { images.setObject(d as NSData, forKey: path as NSString); return d }
        // Bilder duerfen dauern (Original ohne Vorschau bis 10 MB, Mobilfunk): 20 s reichten sonst nie - das Titelbild
        // blieb dann bei jedem Oeffnen leer, waehrend der Browser es zeigte
        guard let d = try? await api(String(path.drop(while: { $0 == "/" })), timeout: 90) else { return nil }
        try? d.write(to: disk, options: .atomic); images.setObject(d as NSData, forKey: path as NSString)
        return d
    }
    /// Gespeichertes Bild verwerfen (liess sich nicht entpacken): beim naechsten Mal frisch vom Server
    func forgetImage(_ path: String) {
        images.removeObject(forKey: path as NSString)
        try? FileManager.default.removeItem(at: cacheDir.appendingPathComponent("files").appendingPathComponent(path.replacingOccurrences(of: "/", with: "_")))
    }

    // MARK: Seiten organisieren (dieselben Server-Wege wie das Web)
    @Published var undo: (id: Int, title: String)?          // "In den Papierkorb verschoben - Rueckgaengig"

    /// Icon, Titelbild, Position, Favorit, volle Breite aendern (ohne Inhalt -> keine Revision noetig)
    func patch(_ id: Int, _ fields: [String: Any]) async throws {
        _ = try await api("api/notes/\(id)", method: "PATCH", body: fields)
        try? FileManager.default.removeItem(at: cacheURL("api/notes/\(id)"))
        if fields["icon"] != nil || fields["pinned"] is Bool {
            editPage(id) { p in
                if let v = fields["icon"] { p.icon = v as? String }
                if let v = fields["pinned"] as? Bool { p.pinned = v }
            }
        }
    }
    /// Archiv: nicht im Baum/Suche, aber per Link erreichbar (wie im Web)
    @Published var archivedPages: [Page] = []
    /// Aufbereitete Seitenliste (im Hintergrund gebaut, PageListLogic.swift) - Quelle fuer pages/archivedPages und den Index
    private var list = PageList()
    /// zaehlt jede Uebernahme einer Liste (veraltete Hintergrund-Ergebnisse erkennen)
    private var listGen = 0
    /// Zwischenspeicher bis zu dieser Groesse beim Start sofort lesen (kleine Konten: wie bisher), groesser im Hintergrund
    static let syncCacheLimit = 1_000_000
    /// Zwischenspeicher-Dateien der Seitenliste schreiben (nacheinander, nicht auf dem Main-Thread; Abmelden wartet darauf)
    static let cacheQueue = DispatchQueue(label: "saga.cache", qos: .utility)
    /// Fertig aufbereitete Liste uebernehmen: nur noch Zuweisungen auf dem Main-Thread
    private func applyList(_ u: PageList.Update) {
        list = u.list; listGen += 1
        if u.visibleChanged {
            pages = u.list.visible
            SpotlightIndex.update(u.list.visible)                         // Systemsuche: im Hintergrund, nur Geaendertes
        }
        if u.archivedChanged { archivedPages = u.list.archivedTop }
    }
    /// Eine Seite lokal aendern (Titel, Symbol, Favorit, Ungelesen) - in der Anzeige UND in der aufbereiteten Liste,
    /// damit spaetere lokale Uebernahmen (neue Seite) die Aenderung nicht zuruecknehmen
    func editPage(_ id: Int, _ f: (inout Page) -> Void) {
        if let i = pageIndex(id) {
            var p = pages[i]; f(&p)
            if p != pages[i] {
                // Titel/Symbol: auch in die Systemsuche - der naechste Abgleich ist danach "unveraendert" (nil) und
                // wuerde Spotlight sonst nicht mehr anfassen (bisher: neuer Titel kam mit dem naechsten Abgleich hinein)
                let spot = p.title != pages[i].title || p.icon != pages[i].icon
                pages[i] = p
                if spot { SpotlightIndex.update(pages) }
            }
            if i < list.visible.count, list.visible[i].id == id { list.visible[i] = p }
        }
        if let j = list.all.firstIndex(where: { $0.id == id }) {
            let before = list.all[j]; f(&list.all[j])
            if list.all[j] != before { listUnsaved = true }
        }
    }
    /// Position in `pages` ueber den Index (Index passt nicht mehr: wie bisher suchen)
    private func pageIndex(_ id: Int) -> Int? {
        if let i = list.index[id], i < pages.count, pages[i].id == id { return i }
        if pages.count == list.visible.count && list.index[id] == nil { return nil }   // Index aktuell: nicht vorhanden
        return pages.firstIndex { $0.id == id }
    }
    func setArchived(_ id: Int, _ on: Bool) async {
        _ = try? await api("api/notes/\(id)/archive", method: "POST", body: ["archived": on])
        await refresh(); dataChanged()
    }
    func trash(_ id: Int) async -> Bool {
        let t = page(id)?.label ?? ""
        guard (try? await api("api/notes/\(id)", method: "DELETE")) != nil else { return false }
        undo = (id, t); await refresh()
        Task { try? await Task.sleep(for: .seconds(6)); if undo?.id == id { undo = nil } }
        return true
    }
    func restore(_ id: Int) async { undo = nil; _ = try? await api("api/trash/\(id)/restore", method: "POST", body: [:]); await refresh() }
    func move(_ id: Int, to parent: Int?) async throws {
        _ = try await api("api/notes/\(id)/move", method: "POST", body: ["parent_id": parent.map { $0 as Any } ?? NSNull(), "index": 0])
        await refresh()
        if FedLogic.isRemote(id) { await loadFedShared() }                 // fremde Seite: "Mit mir geteilt" gleich aktuell
    }
    func duplicate(_ id: Int) async -> Int? {
        struct R: Decodable { let id: Int }
        guard let d = try? await api("api/notes/\(id)/duplicate", method: "POST", body: [:]), let r = try? JSONDecoder().decode(R.self, from: d) else { return nil }
        await refresh(); return r.id
    }
    /// Pfad der uebergeordneten Seiten (fuer die Leiste)
    func path(_ id: Int) -> [Page] {
        var out: [Page] = [], cur = page(id)?.parent_id, guardN = 0
        while let c = cur, let p = page(c), guardN < 20 { out.insert(p, at: 0); cur = p.parent_id; guardN += 1 }
        return out
    }
    /// Seiten, die sich selbst oder ihre Unterseiten nicht als Ziel haben duerfen
    func descendants(_ id: Int) -> Set<Int> {
        var s: Set<Int> = [id], changed = true
        while changed { changed = false; for p in pages where p.parent_id.map(s.contains) == true && !s.contains(p.id) { s.insert(p.id); changed = true } }
        return s
    }
    /// Bild hochladen wie im Editor (HEIC -> JPEG), GIF/PNG/JPEG/WebP unveraendert
    func uploadImage(_ raw: Data, note: Int? = nil) async throws -> String {
        let head = [UInt8](raw.prefix(4))
        if head.starts(with: [0x47, 0x49, 0x46]) { return try await upload(raw, name: "bild.gif", note: note) }
        if head.starts(with: [0x89, 0x50, 0x4E, 0x47]) { return try await upload(raw, name: "bild.png", note: note) }
        if head.starts(with: [0xFF, 0xD8]) || (raw.count > 12 && raw[8..<12] == Data("WEBP".utf8)) { return try await upload(raw, name: "bild.jpg", note: note) }
        #if os(iOS)
        guard let j = UIImage(data: raw)?.jpegData(compressionQuality: 0.85) else { throw APIError.other(String(localized: "Bildformat nicht unterstützt")) }
        #else
        guard let img = NSImage(data: raw), let tiff = img.tiffRepresentation, let j = NSBitmapImageRep(data: tiff)?.representation(using: .jpeg, properties: [.compressionFactor: 0.85]) else { throw APIError.other(String(localized: "Bildformat nicht unterstützt")) }
        #endif
        return try await upload(j, name: "bild.jpg", note: note)
    }

    func updateTitle(_ id: Int, _ title: String) { if page(id)?.title != title { editPage(id) { $0.title = title } } }

    /// Anmeldung ueber das System-Anmeldefenster: Code + Geheimnis -> Sitzung (auch fuer die Web-Ansichten).
    /// `server` ist der beim Start der Anmeldung vorgemerkte Server. Er wird erst nach Erfolg uebernommen
    /// (wie bei `pair`); Abbruch, Fehler oder Tippfehler im Feld aendern nichts am bisherigen Server.
    func exchange(server: URL, code: String, verifier: String) async throws {
        guard AppConfig.isAllowedServer(server) else { throw APIError.other(String(localized: "Nur https-Adressen sind erlaubt.")) }
        let switching = AppConfig.serverOrigin != AppConfig.origin(server)
        let url = server.appendingPathComponent("app/exchange")
        var req = URLRequest(url: url, timeoutInterval: 20)
        req.httpMethod = "POST"; req.httpShouldHandleCookies = false
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.setValue(AppConfig.appLanguage, forHTTPHeaderField: "Accept-Language")       // Fehlertexte in der App-Sprache
        // device: Geraete-Anmeldung (bleibt angemeldet); app_key: Schluessel fuer Widget/Teilen, falls (fuer diesen Server) noch keiner da ist
        // immer: auch auf demselben Server kann es ein anderes Konto sein, und der alte Schluessel ging mit der alten
        // Geraete-Anmeldung (adoptServer entscheidet nach der Kontopruefung)
        let body: [String: Any] = ["code": code, "verifier": verifier, "device": DeviceKey.deviceName, "app_key": true]
        req.httpBody = try JSONSerialization.data(withJSONObject: body)
        let (d, resp) = try await URLSession.shared.data(for: req)
        guard let http = resp as? HTTPURLResponse, http.statusCode == 200 else {
            let msg = (try? JSONSerialization.jsonObject(with: d) as? [String: Any])?["error"] as? String ?? String(localized: "Anmeldung fehlgeschlagen")
            throw APIError.other(msg)
        }
        await adoptServer(server, switching: switching, http: http, url: url, data: d)
    }

    /// Erfolgreich angemeldet/gekoppelt: erst jetzt den Server uebernehmen. Anderer Server -> vorher sauber vom alten abmelden.
    /// `signOut`: auch auf demselben Server das bisherige Konto abloesen (QR-Code kann zu einem anderen Konto gehoeren);
    /// die Startseite wird dabei mit vergessen (Seiten-IDs gehoeren zum alten Konto).
    /// Ohne vorheriges Abmelden (Sitzung abgelaufen, gleicher Server): Liste & Co. sofort ausblenden; nach der Kontopruefung
    /// gleiches Konto -> unveraendert wieder zeigen (Abgleich-Stand bleibt), anderes/unbekanntes Konto -> alles des alten
    /// Kontos verwerfen (Liste, Zwischenspeicher, Abgleich-Stand, Systemsuche, Widget-Schluessel), bevor etwas Neues kommt.
    /// Laufende Abgleiche werden abgebrochen und nicht mit dem alten Stand wiederholt (genau ein Abruf danach).
    private func adoptServer(_ server: URL, switching: Bool, signOut: Bool = false, http: HTTPURLResponse, url: URL, data d: Data) async {
        let loggedOut = switching || signOut
        let before = currentOwner()
        let oldDevice = DeviceKey.stored(), oldDeviceOrigin = AppConfig.keyOrigin(device: true)
        let oldToken = AppConfig.hasToken ? AppConfig.token : nil
        beginAdopt()
        defer { gate.endAdopt() }
        if AccountSwitch.before(loggedOut: loggedOut).hideNow {
            if let c = cacheLoad { await c.value }                        // grosser Zwischenspeicher: nicht nach dem Ausblenden einblenden
            hideAccountView()
        }
        if loggedOut {
            await logout(switchingServer: true)                           // alter Server/altes Konto: Schluessel, Cookies, Zwischenspeicher weg
            forgetNoteCursor()                                            // neue Anmeldung: Baum ganz laden
        }
        AppConfig.server = server; cookieHeader = nil; serverVersion = nil; minApp = nil
        await storeCookies(http, url)
        let keys = adoptKeys(d, takeAppKey: loggedOut); deviceKeyTried = true
        var me = await identifyAccount()                                  // Entwuerfe gehoeren zu Server + Konto
        for _ in 0..<2 where me == nil && !loggedOut {                   // kurz nicht erreichbar: nicht vorschnell verwerfen
            try? await Task.sleep(for: .seconds(1)); me = await identifyAccount()
        }
        let plan = AccountSwitch.after(loggedOut: loggedOut, AccountSwitch.verdict(before: before, after: .init(origin: AppConfig.serverOrigin, account: me)))
        if plan.restore { restoreAccountView() }
        if plan.wipe {
            hiddenView = nil
            forgetNoteCursor()                                            // anderes Konto: Baum ganz laden (genau einmal)
            await clearAccountData(keepWebCookies: true, newSession: !plan.keepOpenPages)
            if me == nil { DraftOwner.app.signedOut(); accountChecked = false; accountName = nil }   // Konto unbekannt: alte Entwuerfe nicht zeigen
        }
        if plan.restore || plan.keepOpenPages {
            // gleiches (oder nicht feststellbares) Konto: alte Geraete-Anmeldung samt Schluessel dieses Kontos abloesen
            await cleanupOldAppKeys(oldToken: oldToken, oldDevice: oldDevice, newKey: keys.appKey)
        } else if plan.wipe {
            AppConfig.token = keys.appKey ?? ""; reloadWidgets()          // Widget/Teilen nie mit dem Schluessel des alten Kontos
            if let t = oldDevice, t != DeviceKey.stored() { await forgetDevice(t, origin: oldDeviceOrigin) }
        }
        let wasShown = !needsLogin                                        // z. B. Kopplung aus "Nicht angemeldet" (Zwischenspeicher)
        needsLogin = false; loginPostponed = false
        if plan.wipe && !plan.keepOpenPages && wasShown {
            // kein Anmeldefenster dazwischen: was sonst beim Schliessen startet (RootView/NativePage), hier anstossen
            live.start(); push.appStarted(); Task { await chat.load() }
            accountSwitches += 1                                          // offene Seite des alten Kontos neu laden
        }
        if !keys.ok { error = Store.keychainFailedText }                  // nicht still: sonst spaeter unerklaert abgemeldet
    }
    /// zaehlt Kontowechsel ohne Anmeldefenster dazwischen (offene Seiten laden dann neu wie nach dem Anmeldefenster)
    @Published private(set) var accountSwitches = 0

    /// Neuanmeldung beim gleichen Konto (AppKeyCleanup): alte Geraete-Anmeldung dieses Geraets samt App-Schluessel loeschen,
    /// sonst sammeln sich je Neuanmeldung Schluessel an (APP_TOKEN_MAX). Nur Eintraege aus der Liste des NEUEN Kontos.
    private func cleanupOldAppKeys(oldToken: String?, oldDevice: String?, newKey: String?) async {
        let newDevice = DeviceKey.stored()
        guard let d = try? await api("api/settings/apps"), let l = AppKeyCleanup.parse(d) else {
            // alter Server/Fehler: wie bisher - alte Geraete-Anmeldung (falls bekannt) abmelden, neuen Schluessel nehmen
            if let t = oldDevice, t != newDevice { await forgetDevice(t, origin: AppConfig.serverOrigin) }
            if let k = newKey { AppConfig.token = k; reloadWidgets() }
            return
        }
        let p = AppKeyCleanup.plan(devices: l.devices, keys: l.keys, oldToken: oldToken, oldDevice: oldDevice == newDevice ? nil : oldDevice, newKey: newKey)
        for id in p.deleteDevices { _ = try? await api("api/settings/devices/\(id)", method: "DELETE") }
        if let k = p.deleteKey { _ = try? await api("api/settings/tokens/\(k)", method: "DELETE") }
        switch p.use {
        case .new: if let k = newKey { AppConfig.token = k }
        case .old: if let t = oldToken { AppConfig.token = t }
        case .mint:                                                       // keiner ausgestellt (Grenze): jetzt, nach dem Aufraeumen
            let r = try? await api("api/settings/tokens", method: "POST", body: ["name": DeviceKey.deviceName + " (automatisch)"])
            let k = r.flatMap { (try? JSONSerialization.jsonObject(with: $0)) as? [String: Any] }?["token"] as? String
            AppConfig.token = k.flatMap { $0.hasPrefix("hk_") ? $0 : nil } ?? ""
        case .none: break
        }
        reloadWidgets()
    }

    /// Anmeldung per QR-Code (Kopplungs-Code aus dem Web). Der Server wird erst nach erfolgreicher Kopplung
    /// uebernommen; vorher wird sauber abgemeldet - vom alten Server, und auch auf demselben Server vom bisherigen Konto
    /// (so sagt es die Rueckfrage, `PairNotice`): sonst blieben Widget-Schluessel und Geraete-Anmeldung des alten Kontos liegen.
    func pair(server: URL, code: String) async throws {
        guard AppConfig.isAllowedServer(server) else { throw APIError.other(String(localized: "Nur https-Adressen sind erlaubt.")) }
        let switching = AppConfig.serverOrigin != AppConfig.origin(server)
        let signOut = switching || signedIn
        let url = server.appendingPathComponent("app/pair")
        var req = URLRequest(url: url, timeoutInterval: 20)
        req.httpMethod = "POST"; req.httpShouldHandleCookies = false
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.setValue(AppConfig.appLanguage, forHTTPHeaderField: "Accept-Language")       // Fehlertexte in der App-Sprache
        req.httpBody = try JSONSerialization.data(withJSONObject: ["code": code, "device": DeviceKey.deviceName, "app_key": true])   // wie exchange
        let (d, resp) = try await URLSession.shared.data(for: req)
        guard let http = resp as? HTTPURLResponse, http.statusCode == 200 else {
            let msg = (try? JSONSerialization.jsonObject(with: d) as? [String: Any])?["error"] as? String ?? String(localized: "Anmeldung fehlgeschlagen")
            throw APIError.other(msg)
        }
        await adoptServer(server, switching: switching, signOut: signOut, http: http, url: url, data: d)
        await refresh()
    }

    /// Angemeldet (fuer die Kopplungs-Rueckfrage): Server eingetragen, nicht auf dem Anmeldebildschirm, Schluessel vorhanden
    var signedIn: Bool { !needsLogin && AppConfig.hasServer && (DeviceKey.stored() != nil || AppConfig.hasToken) }
    /// Was die Kopplungs-Rueckfrage ueber die bisherige Anmeldung sagt
    func pairNotice(for target: URL) -> PairNotice {
        PairNotice.plan(target: target, current: AppConfig.hasServer ? AppConfig.server : nil, signedIn: signedIn, account: accountName)
    }

    // MARK: Konto (Entwuerfe gehoeren zu Server + Konto)

    private var accountChecked = false
    /// Anzeigename des angemeldeten Kontos (sonst Benutzername); nur im Speicher, nur fuer Rueckfragen
    private(set) var accountName: String?
    /// Konto beim Server erfragen (Konto-ID = Benutzername aus /api/account) und fuer die Entwuerfe merken.
    /// Fehler (offline, alter Server): bisherigen Stand behalten.
    /// Rueckgabe: Benutzername (nil: unbekannt)
    @discardableResult
    func identifyAccount() async -> String? {
        guard let o = AppConfig.serverOrigin, let d = try? await api("api/account"),
              let j = try? JSONSerialization.jsonObject(with: d) as? [String: Any],
              let u = j["username"] as? String, !u.isEmpty else { return nil }
        DraftOwner.app.signedIn(origin: o, account: u)
        checkHomeOwner(origin: o, account: u)
        let n = (j["name"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        accountName = n.isEmpty ? u : n
        let av = ShareJSON.str(j["avatar"])
        if av != myAvatar { myAvatar = av }
        return u
    }

    /// Anderen Server eintragen (Einstellungen): anderer Server = vom alten sauber abmelden
    func switchServer(to server: URL) async {
        if AppConfig.serverOrigin == AppConfig.origin(server) {
            AppConfig.server = server; await serverChanged(); return
        }
        if AppConfig.hasServer && (DeviceKey.stored() != nil || !pages.isEmpty) { await logout(switchingServer: true) }
        forgetNoteCursor()                                                // Stand gilt nur fuer den alten Server
        AppConfig.server = server; cookieHeader = nil; serverVersion = nil; minApp = nil
        needsLogin = true
    }

    /// Server-Adresse geaendert (gleicher Server): neu verbinden
    func serverChanged() async { cookieHeader = nil; serverVersion = nil; minApp = nil; await refresh() }

    /// Kopplungs-Link aus dem QR-Code: https://server/app/pair?c=CODE oder <app>://pair?u=https://server&c=CODE
    nonisolated static func parsePairLink(_ s: String) -> (URL, String)? {
        guard let u = URLComponents(string: s.trimmingCharacters(in: .whitespacesAndNewlines)) else { return nil }
        let q = u.queryItems ?? []
        guard let code = q.first(where: { $0.name == "c" })?.value, code.count >= 20 else { return nil }
        if u.scheme == "https" || u.scheme == "http", u.path == "/app/pair", let host = u.host {
            var b = URLComponents(); b.scheme = u.scheme; b.host = host; b.port = u.port
            return b.url.flatMap { AppConfig.isAllowedServer($0) ? ($0, code) : nil }    // nur https (Demo: localhost im DEBUG-Build)
        }
        if ["hlidskjalf", "saga"].contains(u.scheme ?? ""), u.host == "pair", let srv = q.first(where: { $0.name == "u" })?.value,
           let s = AppConfig.normalizedServer(srv) {
            return (s, code)
        }
        return nil
    }

    /// Zaehlt Abmeldungen/Kontowechsel: eine Seite, die vorher geladen wurde, gehoert zum alten Konto (PageDoc.staleSession)
    private(set) var sessionGen = 0

    /// Auf diesem Geraet abmelden: Sitzung + Zwischenspeicher loeschen. `switchingServer`: auch Startseite vergessen
    /// (Seiten-IDs gehoeren zum alten Server).
    func logout(switchingServer: Bool = false) async {
        sessionGen += 1                                                   // offene Seiten des alten Kontos senden nichts mehr
        // Geraete-Anmeldung beim Server loeschen, der sie ausgestellt hat (nie an einen anderen senden)
        if let tok = DeviceKey.stored() { await forgetDevice(tok, origin: AppConfig.keyOrigin(device: true)) }
        DeviceKey.set(nil); deviceKeyTried = false
        if error == Store.keychainFailedText { error = nil }
        DraftOwner.app.signedOut(); accountChecked = false; accountName = nil // Entwuerfe bleiben liegen, aber nur fuer ihr Konto lesbar
        AppConfig.token = ""; reloadWidgets()                           // der Server loescht den Widget-Schluessel dieses Geraets mit
        myAvatar = nil
        ChatVideoUploads.shared.cancelAll()                             // 2.4: laufende Video-Uploads verwerfen
        await clearAccountData(keepWebCookies: false)
        if switchingServer { serverVersion = nil; minApp = nil }
        needsLogin = true
    }

    /// Alles Lokale eines Kontos verwerfen: Liste, Zwischenspeicher, Abgleich-Stand, Systemsuche, Web-Daten, Live/Glocke/
    /// Chat/Push im Speicher, Startseite. `keepWebCookies`: Kontowechsel ohne Abmelden - die Sitzung der NEUEN Anmeldung
    /// (Cookie) bleibt, alles andere der Web-Ansichten (Speicher, Caches) des alten Kontos geht.
    /// `newSession: false` (Konto unbekannt): offene Seiten gelten nicht als fremd und senden ungesicherten Text weiter.
    private func clearAccountData(keepWebCookies: Bool, newSession: Bool = true) async {
        if newSession { sessionGen += 1 }                                 // offene Seiten des alten Kontos senden nichts mehr
        let ds = WKWebsiteDataStore.default()
        var types = WKWebsiteDataStore.allWebsiteDataTypes()
        if keepWebCookies { types.remove(WKWebsiteDataTypeCookies) }
        let recs = await ds.dataRecords(ofTypes: types)
        await ds.removeData(ofTypes: types, for: recs)
        Store.cacheQueue.sync {}                                          // laufende Schreibvorgaenge abwarten, dann loeschen
        try? FileManager.default.removeItem(at: cacheDir)
        try? FileManager.default.createDirectory(at: cacheDir.appendingPathComponent("files"), withIntermediateDirectories: true)
        images.removeAllObjects(); cookieHeader = nil; AvatarCache.shared.reset()
        URLCache.shared.removeAllCachedResponses()                      // Profilbilder (private, max-age) u. a. nicht liegen lassen
        live.stop(); live.forgetServer(); notifs.reset(); calls.signedOut(); chat.signedOut(); push.signedOut()   // 2.0: Live, Glocke, Chat-Schluessel im Speicher, Push-Abo
        cursorEpoch += 1; noteCursor = nil; savedCursor = nil; listUnsaved = false; cacheLoad?.cancel(); cacheLoad = nil   // Abgleich-Stand gehoert zum Konto
        pages = []; archivedPages = []; list = PageList(); listGen += 1; shared = []; fedShared = []; aiEnabled = false; aiPersona = nil; aiChat = false; aiHost = ""; fedUnreachable = []; shareOffHint = false; tasks = []; doneTasks = []; events = []; recent = []; UserDefaults.standard.removeObject(forKey: "recent")
        AppConfig.store.removeObject(forKey: "w.tasks")                 // Widget: eben abgehakte Aufgaben (IDs des alten Kontos)
        // Startseite immer vergessen: die naechste Anmeldung kann ein anderes Konto sein - dessen Start sonst die
        // Seiten-ID des alten Kontos an den Server schickte (BUG-060)
        setHome(nil)
        SpotlightIndex.clear()                                            // abgemeldet/anderes Konto: nichts mehr in der Systemsuche
    }

    static func pkce() -> (verifier: String, challenge: String) {
        let b64 = { (d: Data) in d.base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "") }
        var bytes = [UInt8](repeating: 0, count: 48); _ = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        let v = b64(Data(bytes))
        return (v, b64(Data(SHA256.hash(data: Data(v.utf8)))))
    }
}
