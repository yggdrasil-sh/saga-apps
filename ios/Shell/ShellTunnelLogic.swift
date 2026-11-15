import Foundation

/// 2.5 A3: Medien-Tunnel der Mac-Huelle - reine Regeln der Bruecke `hkTunnel` (ShellTunnel.swift), ohne WebKit/Sockets,
/// damit Tests/Logic/run.sh sie mit swiftc prueft. Vertrag: docs/APP-API-HOST.md ("Medien-Tunnel") im Server-Repo,
/// Protokoll zum Knoten: HornWire (App/HornTunnelLogic.swift, gemeinsam mit der iOS-App).
/// Das Web (static/calls.js ueber sagaHost.tunnel()) holt sich je Anruf-Leitung einen Tunnel:
///   {op:"open", v:1, role:"audio"|"video", url, user, cred} -> {id}   WebSocket zum Knoten steht, Zugang angenommen
///   {op:"listen", v:1, id}                                    -> {port} Lauscher auf 127.0.0.1 (eine Verbindung)
///   {op:"close", v:1, id}                                     -> true
///   {op:"stats", v:1, id}                                     -> Zeile (Diagnose) bzw. null
/// Fehler kommen als Text zurueck (das Web bekommt eine abgelehnte Promise), z. B. "ws 426 Proxy reicht WebSocket nicht durch".
enum ShellTunnelLogic {
    static let handlerName = "hkTunnel"
    /// Faehigkeit in sagaHostInfo.caps (nur, wenn die Bruecke eingerichtet ist)
    static let cap = "tunnel"
    /// hoechstens so viele Tunnel gleichzeitig (Anruf: 2 Leitungen; Wechsel make-before-break und Wettlauf kurz 2 mehr)
    static let maxOpen = 8

    enum Op: Equatable {
        case open(role: String, url: URL, user: String, cred: String)
        case listen(Int), close(Int), stats(Int)
    }

    /// Nachricht aus dem Web pruefen; nil = verworfen ("ws Anfrage")
    static func parse(_ body: Any?) -> Op? {
        guard let d = body as? [String: Any], let op = d["op"] as? String, (d["v"] as? NSNumber)?.intValue == 1 else { return nil }
        func id() -> Int? {
            guard let n = d["id"] as? NSNumber, CFGetTypeID(n) != CFBooleanGetTypeID() else { return nil }
            let v = n.doubleValue
            return v >= 1 && v <= 1e9 && v == v.rounded() ? Int(v) : nil
        }
        switch op {
        case "open":
            guard let role = d["role"] as? String, role == "audio" || role == "video",
                  let u = nodeURL(d["url"]), let user = d["user"] as? String, let cred = d["cred"] as? String,
                  token(user), token(cred) else { return nil }
            return .open(role: role, url: u, user: user, cred: cred)
        case "listen": return id().map { .listen($0) }
        case "close": return id().map { .close($0) }
        case "stats": return id().map { .stats($0) }
        default: return nil
        }
    }

    /// Zugangsdaten von POST /api/sfu/turn: druckbares ASCII, nicht leer, kurz (gehen nur in die erste WS-Nachricht)
    static func token(_ s: String) -> Bool {
        !s.isEmpty && s.utf8.count <= 512 && s.unicodeScalars.allSatisfy { $0.value > 0x20 && $0.value < 0x7f }
    }

    /// Adresse des Knotens (Kandidaten-URL, im Tunnel-Betrieb = BASE_URL): nur https mit Host, ohne Benutzer/Kennwort
    static func nodeURL(_ v: Any?) -> URL? {
        guard let s = v as? String, s.count <= 2048, let c = URLComponents(string: s), c.scheme?.lowercased() == "https",
              let h = c.host, !h.isEmpty, c.user == nil, c.password == nil, let u = c.url else { return nil }
        return u
    }

    /// Nur das Hauptfenster der eigenen Saga-Herkunft der Huelle darf Tunnel oeffnen (keine fremden Seiten, keine iframes,
    /// nicht die lokale Offline-Seite)
    static func allowed(mainFrame: Bool, scheme: String?, host: String?, port: Int?, server: URL) -> Bool {
        guard mainFrame, let o = ShellLogic.origin(scheme: scheme, host: host, port: port), let s = ShellLogic.origin(server) else { return false }
        return o == s && (scheme ?? "").lowercased() == "https"
    }
}
