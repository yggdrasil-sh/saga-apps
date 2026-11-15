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

    /// Erste Textnachricht: Zugang nur hier (nie in URL, Query oder Kopf - der Proxy protokolliert die Anfragezeile).
    /// Inhalt: kurzlebige TURN-Zugangsdaten des Medien-Tokens (POST /api/sfu/turn) und mode "ice" (A3).
    static func hello(user: String, pass: String) -> String {
        let o: [String: Any] = ["v": 1, "mode": "ice", "username": user, "credential": pass]
        guard let d = try? JSONSerialization.data(withJSONObject: o, options: [.sortedKeys]), let s = String(data: d, encoding: .utf8) else { return "{}" }
        return s
    }

    /// Antwort des Knotens auf die erste Nachricht
    enum Reply: Equatable { case ok, denied(String), bad }
    static func reply(_ text: String) -> Reply {
        guard let d = text.data(using: .utf8), let o = (try? JSONSerialization.jsonObject(with: d)) as? [String: Any] else { return .bad }
        if o["ok"] as? Bool == true { return .ok }
        if let e = o["error"] as? String, !e.isEmpty { return .denied(String(e.prefix(40))) }
        return .bad
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
    /// Quittieren: sofort ab so vielen unquittierten Bytes, sonst spaetestens nach ackMs (Vertrag 5: 10-20 ms statt 5)
    static let ackBytes: UInt64 = 4096
    static let ackMs = 15

    /// Weg im Body von POST /api/sfu/leg: fuer den Knoten ist ein Tunnel-Bein ein ICE-TCP-Bein (er kennt den Tunnel aus
    /// mux.Inject); die App fuehrt intern den Weg "ws" (Diagnose, Senderate wie TCP)
    static func legPath(_ p: String) -> String { p == "ws" ? "tcp" : p }

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
