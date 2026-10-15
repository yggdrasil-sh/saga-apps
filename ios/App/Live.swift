import SwiftUI
import Combine

// Live-Kanal (Saga 1.4): EINE Verbindung GET /api/events, nur im Vordergrund; Ereignisse ohne Inhalt.
// Benachrichtigungen (Glocke), Anwesenheit auf Seiten, Hinweis "{name} hat diese Seite geaendert". Logik: LiveLogic.swift

/// Ereignis vom Server (typ + JSON)
struct LiveEvent { let type: String; let data: [String: Any] }

@MainActor
final class LiveHub: ObservableObject {
    weak var store: Store?
    @Published private(set) var connected = false
    /// Alle Ereignisse fuer Ansichten (offene Seite, Chat ...)
    let events = PassthroughSubject<LiveEvent, Never>()
    private var task: Task<Void, Never>?
    private var lastID: String?
    private var lastLine = Date()
    private var fails = 0
    private lazy var session: URLSession = {
        let c = URLSessionConfiguration.default
        c.timeoutIntervalForRequest = 80; c.timeoutIntervalForResource = 7200
        c.requestCachePolicy = .reloadIgnoringLocalCacheData
        return URLSession(configuration: c)
    }()

    /// Verbinden (App im Vordergrund, angemeldet). Mehrfach aufrufbar.
    func start() {
        guard task == nil, let store, AppConfig.hasServer, !store.needsLogin else { return }
        task = Task { [weak self] in await self?.run() }
    }
    /// Trennen (Hintergrund, Abmelden)
    func stop() { task?.cancel(); task = nil; if connected { connected = false } }

    private func run() async {
        while !Task.isCancelled {
            let wait = await connectOnce()
            if Task.isCancelled { break }
            switch wait {
            case .stop: task = nil; connected = false; return
            case .now: continue
            case .resumeSession:
                if await store?.resumeSession() != .ok { try? await Task.sleep(for: .seconds(30)) }
            case .after(let s): try? await Task.sleep(for: .seconds(s))
            }
        }
    }

    /// Eine Verbindung bis zum Ende -> was danach
    private func connectOnce() async -> LiveReconnect {
        guard let store else { return .stop }
        var path = "api/events"
        if let l = lastID, let e = l.addingPercentEncoding(withAllowedCharacters: .alphanumerics) { path += "?last=" + e }
        do {
            let (bytes, code, _) = try await store.openStream(path, headers: lastID.map { ["Last-Event-ID": $0] } ?? [:], session: session)
            if code == 401 { return .resumeSession }
            if code == 404 || code == 405 { return .stop }                     // aelterer Server ohne Live-Kanal
            guard code == 200 else { fails += 1; return .after(LiveReconnect.backoff(fails)) }
            lastLine = Date()
            var outcome: LiveReconnect = .after(LiveReconnect.backoff(1))
            // Lesen im Hintergrund (nicht Byte fuer Byte auf dem Hauptthread); Waechter: ohne Ping > 70 s neu verbinden
            let reader = Task.detached { [weak self] () -> LiveReconnect? in
                var parser = SSEParser()
                var buf = Data()
                for try await b in bytes {
                    if b == 0x0A {
                        buf.append(b)
                        let evs = parser.feed(bytes: buf); buf.removeAll(keepingCapacity: true)
                        await MainActor.run { self?.lastLine = Date() }
                        for e in evs { if let r = await self?.handle(e) { return r } }
                    } else { buf.append(b) }
                }
                return nil
            }
            let watchdog = Task { [weak self] in
                while !Task.isCancelled {
                    try? await Task.sleep(for: .seconds(10))
                    guard let self else { return }
                    if Date().timeIntervalSince(self.lastLine) > 70 { reader.cancel(); return }
                }
            }
            defer { watchdog.cancel(); reader.cancel() }
            if let r = try await withTaskCancellationHandler(operation: { try await reader.value }, onCancel: { reader.cancel() }) { outcome = r }
            else { fails += 1; outcome = .after(LiveReconnect.backoff(fails)) }
            connected = false
            return outcome
        } catch {
            connected = false
            fails += 1
            return .after(LiveReconnect.backoff(fails))
        }
    }

    /// Ereignis verarbeiten; Rueckgabe != nil beendet die Verbindung (bye)
    private func handle(_ e: SSEEvent) -> LiveReconnect? {
        if let id = e.id, !id.isEmpty { lastID = id }
        let d = e.json
        switch e.event {
        case "hello": connected = true; fails = 0
        case "bye": return LiveReconnect.after(bye: d["reason"] as? String ?? "closed")
        case "reset":
            lastID = nil
            Task { await store?.refresh(quiet: true); await store?.notifs.load() }
        case "notification": store?.notifs.apply(d)
        case "shared.changed": Task { await store?.loadShared(); await store?.loadFedShared() }
        case "comments.changed":
            if let nid = ShareJSON.int(d["note_id"]), let c = d["counts"] as? [String: Any] { store?.setUnread(nid, (ShareJSON.int(c["unread"]) ?? 0) > 0) }
        default: break
        }
        if e.event.hasPrefix("chat.") { store?.chat.live(e.event, d) }
        events.send(LiveEvent(type: e.event, data: d))
        return nil
    }
}

extension Store {
    /// Ungelesen-Punkt in Liste/Baum (comments.changed)
    func setUnread(_ id: Int, _ on: Bool) {
        if let i = shared.firstIndex(where: { $0.id == id }), (shared[i].unread ?? false) != on { shared[i].unread = on }
        if let i = pages.firstIndex(where: { $0.id == id }), (pages[i].unread ?? false) != on { pages[i].unread = on }
    }
}
