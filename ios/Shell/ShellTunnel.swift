import Foundation
import WebKit

// 2.5 A3: Web-Anrufe der Mac-Huelle ueber den Medien-Tunnel (Vertrag vertrag-a3, docs/APP-API-HOST.md "Medien-Tunnel").
// Hinter einem Proxy, der TLS selbst beendet (z. B. Nginx Proxy Manager mit "Websockets Support"), erreicht WebKits WebRTC
// den Knoten nicht (kein ICE-TCP/TURN-TLS am Proxy vorbei). Die Huelle oeffnet darum je Leitung denselben Tunnel wie die
// iOS-App (HornTunnel, gemeinsame Quelle App/HornTunnel.swift): WebSocket wss://<Saga>/api/sfu/ws (saga-tunnel.1), lokal
// ein Lauscher auf 127.0.0.1 fuer genau eine Verbindung. Das Web biegt den passiven ICE-TCP-Kandidaten des Knotens auf
// diesen Port (SagaHorn.bend) - WebKits WebRTC verbindet sich aktiv mit 127.0.0.1, die Bytes gehen durch den WebSocket.
// Regeln (Nachrichten, Herkunft, Grenzen): ShellTunnelLogic. Entitlement com.apple.security.network.server (Lauscher in
// der Sandbox, nur 127.0.0.1). Aufraeumen: close vom Web (Auflegen, Wechsel), alles bei Seitenwechsel/Neuladen
// (ShellWeb didCommit), Absturz des Inhaltsprozesses und Abmelden.

/// Sitzung, die HornTunnel ohne Angabe nimmt (in der iOS-App steht HornNet in App/HornNode.swift; die Huelle braucht nur
/// diese): ohne Cookies/Cache, Zertifikate wie das System
enum HornNet {
    nonisolated(unsafe) static var session: URLSession = {
        let c = URLSessionConfiguration.ephemeral
        c.timeoutIntervalForRequest = 15
        c.httpCookieStorage = nil; c.urlCache = nil; c.requestCachePolicy = .reloadIgnoringLocalCacheData
        c.waitsForConnectivity = false
        return URLSession(configuration: c)
    }()
}

/// Bruecke `hkTunnel` (Nachricht mit Antwort). server: aktuelle Saga-Adresse der Huelle (Herkunftspruefung)
@MainActor
final class ShellTunnelBridge: NSObject, WKScriptMessageHandlerWithReply {
    private let server: @MainActor () -> URL
    private let session: URLSession
    private var open: [Int: HornTunnel] = [:]
    private var seq = 0
    /// letzte Aufbaufehler/Ereignisse (Diagnose, Tests)
    private(set) var events: [String] = []

    init(server: @escaping @MainActor () -> URL, session: URLSession = HornNet.session) {
        self.server = server; self.session = session
    }

    var count: Int { open.count }

    func userContentController(_ uc: WKUserContentController, didReceive m: WKScriptMessage,
                               replyHandler: @escaping @MainActor (Any?, String?) -> Void) {
        let o = m.frameInfo.securityOrigin
        guard ShellTunnelLogic.allowed(mainFrame: m.frameInfo.isMainFrame, scheme: o.protocol, host: o.host,
                                       port: o.port == 0 ? nil : o.port, server: server()) else { return replyHandler(nil, "ws Herkunft") }
        guard let op = ShellTunnelLogic.parse(m.body) else { return replyHandler(nil, "ws Anfrage") }
        switch op {
        case let .open(role, url, user, cred):
            guard open.count < ShellTunnelLogic.maxOpen else { return replyHandler(nil, "ws Grenze") }
            guard let t = HornTunnel(base: url.absoluteString, user: user, pass: cred, role: role, session: session) else {
                return replyHandler(nil, "ws Anfrage")
            }
            seq += 1
            let id = seq
            open[id] = t
            t.onClose = { [weak self, weak t] why in
                Task { @MainActor in
                    guard let self, let t, self.open[id] === t else { return }
                    self.open[id] = nil
                    self.note("\(id) \(role) zu \(why)")
                }
            }
            Task { @MainActor in
                do {
                    try await t.connect()
                    guard self.open[id] === t else { t.close(); return replyHandler(nil, "ws zu") }
                    replyHandler(["id": id], nil)
                } catch {
                    if self.open[id] === t { self.open[id] = nil }
                    t.close()
                    let why = (error as? HornTunnel.Fail)?.errorDescription ?? "ws nicht erreichbar"
                    self.note("\(id) \(role) \(why)")
                    replyHandler(nil, why)
                }
            }
        case .listen(let id):
            guard let t = open[id] else { return replyHandler(nil, "ws zu") }
            do { replyHandler(["port": Int(try t.listen())], nil) }
            catch { replyHandler(nil, (error as? HornTunnel.Fail)?.errorDescription ?? "ws Lauscher") }
        case .close(let id):
            open.removeValue(forKey: id)?.close()
            replyHandler(true, nil)
        case .stats(let id):
            replyHandler(open[id]?.stats.line, nil)
        }
    }

    /// alle Tunnel zu (Seitenwechsel, Neuladen, Inhaltsprozess weg, Abmelden)
    func closeAll() {
        guard !open.isEmpty else { return }
        for t in open.values { t.close() }
        open.removeAll()
    }

    private func note(_ s: String) {
        events.append(s)
        if events.count > 20 { events.removeFirst() }
    }
}
