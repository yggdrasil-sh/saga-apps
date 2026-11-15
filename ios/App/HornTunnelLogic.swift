import Foundation

// Saga 2.5 A3 "ICE-TCP im WebSocket-Tunnel" (Vertrag vertrag-a3.md, Konzept ws-apps §4/§6.2/§6.4/§9/§10): reine Regeln
// ohne Sockets und ohne WebRTC, damit sie sich im Logic-Runner pruefen lassen (Tests/Logic/HornTunnelTests.swift). Die
// Stellen, die das Protokoll des Knotens festlegt (erste Nachricht, Antwort, Rahmen, Quittungsabstand), stehen nur hier in
// HornWire - HornTunnel.swift (Sockets, URLSessionWebSocketTask) benutzt sie, ohne selbst Formate zu kennen.
// Android: call/HornTunnel.kt (HornWire, bend) mit denselben Faellen in HornTunnelTest.kt.

enum HornWire {
    /// Endpunkt an der Saga-Adresse (BASE_URL; saga-media reicht ihn roh an den eingebauten Knoten)
    static let path = "/api/sfu/ws"
    /// Unterprotokoll der Apps (saga-frames.1 ist fuer den Browser-Weg B2 reserviert)
    static let proto = "saga-tunnel.1"
    /// Frist fuer Aufbau + Zugangs-Antwort (der Knoten schliesst selbst nach 10 s ohne erste Nachricht)
    static let readyMs = 10_000.0

    /// wss://<Host der Kandidaten-URL>/api/sfu/ws (http nur fuer Tests: ws)
    static func url(_ base: String) -> URL? {
        guard var c = URLComponents(string: base), let s = c.scheme, s == "https" || s == "http", c.host != nil else { return nil }
        c.scheme = s == "http" ? "ws" : "wss"
        c.path = path; c.query = nil; c.fragment = nil
        return c.url
    }

    /// Erste Textnachricht (Vertrag S2): Zugang nur hier (nie in URL, Query oder Kopf - der Proxy protokolliert die
    /// Anfragezeile). user/cred = POST /api/sfu/turn mit dem Ton-Token (ein Satz fuer beide WS), mode "ice" (A3),
    /// role nur fuer die Diagnose am Knoten
    static func hello(user: String, pass: String, role: String? = nil) -> String {
        var o: [String: Any] = ["t": "auth", "v": 1, "mode": "ice", "user": user, "cred": pass]
        if let role, role == "audio" || role == "video" { o["role"] = role }
        guard let d = try? JSONSerialization.data(withJSONObject: o, options: [.sortedKeys]), let s = String(data: d, encoding: .utf8) else { return "{}" }
        return s
    }

    /// Antwort des Knotens auf die erste Nachricht: {"t":"ok","v":1,"ack_bytes":…,"ack_ms":…} bzw.
    /// {"t":"err","v":1,"code":"auth|version|mode|bad|timeout|unavailable"}
    struct Acking: Equatable { var bytes: UInt64 = HornWire.ackBytes; var ms = HornWire.ackMs }
    enum Reply: Equatable { case ok(Acking), denied(String), bad }
    static func reply(_ text: String) -> Reply {
        guard let d = text.data(using: .utf8), let o = (try? JSONSerialization.jsonObject(with: d)) as? [String: Any] else { return .bad }
        switch o["t"] as? String {
        case "ok":
            var a = Acking()
            if let b = (o["ack_bytes"] as? NSNumber)?.intValue, b >= 1, b <= 1 << 20 { a.bytes = UInt64(b) }
            if let m = (o["ack_ms"] as? NSNumber)?.intValue, m >= 1, m <= 1000 { a.ms = m }
            return .ok(a)
        case "err":
            let c = (o["code"] as? String).map { String($0.prefix(40)) } ?? ""
            return .denied(c.isEmpty ? "err" : c)
        default: return .bad
        }
    }

    /// Binaerrahmen: erstes Byte = Art
    static let kData: UInt8 = 0, kAck: UInt8 = 1
    enum Frame: Equatable { case data(Data), ack(UInt64), unknown }
    static func data(_ d: Data) -> Data { var m = Data(capacity: d.count + 1); m.append(kData); m.append(d); return m }
    /// Quittung = Summe der empfangenen Nutzbytes (8 Byte, big endian)
    static func ack(_ total: UInt64) -> Data {
        var m = Data([kAck])
        for i in (0..<8).reversed() { m.append(UInt8((total >> (UInt64(i) * 8)) & 0xff)) }
        return m
    }
    static func frame(_ m: Data) -> Frame {
        guard let k = m.first else { return .unknown }
        let p = m.dropFirst()
        switch k {
        case kData: return p.isEmpty ? .unknown : .data(Data(p))
        case kAck: return p.count >= 8 ? .ack(p.prefix(8).reduce(UInt64(0)) { $0 << 8 | UInt64($1) }) : .unknown
        default: return .unknown
        }
    }
    /// Quittieren (Vertrag S3): sofort ab ackBytes unquittierten Bytes, sonst spaetestens ackMs nach dem ersten
    /// unquittierten Byte; Werte aus der ok-Antwort, fehlen sie: 16384 / 15
    static let ackBytes: UInt64 = 16384
    static let ackMs = 15

    /// Weg im Body von POST /api/sfu/leg (Vertrag S4): "ws" - fuer den Knoten ein ICE-TCP-Bein, auch ohne oeffentliches
    /// ICE-TCP angenommen; aeltere Knoten antworten 400 (naechster Weg)
    static func legPath(_ p: String) -> String { p }

    /// Fehler beim Aufbau in Worten fuer Diagnose (calldiag/clientlog/Protokoll). 426 = Proxy ohne "Websockets Support"
    static func failText(status: Int, code: String? = nil) -> String {
        if status == 426 { return "ws 426 Proxy reicht WebSocket nicht durch" }
        if let code { return "ws abgelehnt \(code)" }
        return status > 0 ? "ws HTTP \(status)" : "ws nicht erreichbar"
    }
}

enum HornTunnelLogic {
    /// A3: passiven ICE-TCP-Kandidaten (host) des Knotens im Antwort-SDP auf 127.0.0.1:<Tunnelport> biegen - libwebrtc
    /// verbindet sich dann aktiv mit dem lokalen Lauscher. Nur der erste passive TCP-Kandidat bleibt (gebogen), weitere
    /// (z. B. IPv6) fallen weg: sonst kaemen mehrere lokale Verbindungen fuer dieselbe Leitung. Andere Zeilen unveraendert.
    static func bend(_ sdp: String, port: UInt16) -> String {
        var done = false
        var out: [String] = []
        for l in sdp.components(separatedBy: "\r\n") {
            let f = l.split(separator: " ", omittingEmptySubsequences: false).map(String.init)
            guard l.hasPrefix("a=candidate:"), f.count >= 8, f[2].lowercased() == "tcp", f[6] == "typ", f[7] == "host",
                  let i = f.firstIndex(of: "tcptype"), i + 1 < f.count, f[i + 1] == "passive" else { out.append(l); continue }
            if done { continue }
            var g = f; g[4] = "127.0.0.1"; g[5] = String(port)
            out.append(g.joined(separator: " ")); done = true
        }
        return out.joined(separator: "\r\n")
    }
    /// Enthaelt die Antwort einen biegbaren Kandidaten? (sonst kann der Tunnel-Weg nicht gehen)
    static func bendable(_ sdp: String) -> Bool { bend(sdp, port: 9).contains(" 127.0.0.1 9 typ host") }
}
