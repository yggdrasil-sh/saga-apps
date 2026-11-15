import Foundation

// 2.5 A3: Bruecke hkTunnel der Mac-Huelle (Shell/ShellTunnelLogic.swift) - Nachrichten, Herkunft, Adresse des Knotens
func shellTunnelTests() {
    typealias T = ShellTunnelLogic
    let srv = URL(string: "https://saga.example.org")!
    check(ShellLogic.caps.contains(T.cap) && T.handlerName == "hkTunnel", "Tunnel: Faehigkeit gemeldet, eigener Name der Bruecke")
    check(ShellLogic.hostInfoScript(version: "2.5.0").contains("'tunnel'"), "Tunnel: in sagaHostInfo.caps")
    // Herkunft
    check(T.allowed(mainFrame: true, scheme: "https", host: "saga.example.org", port: nil, server: srv), "Tunnel: eigene Herkunft, Hauptfenster")
    check(T.allowed(mainFrame: true, scheme: "https", host: "SAGA.example.org", port: 443, server: srv), "Tunnel: Standardport/Gross-Klein egal")
    check(!T.allowed(mainFrame: false, scheme: "https", host: "saga.example.org", port: nil, server: srv), "Tunnel: kein iframe")
    check(!T.allowed(mainFrame: true, scheme: "https", host: "evil.test", port: nil, server: srv)
          && !T.allowed(mainFrame: true, scheme: "https", host: "saga.example.org", port: 8443, server: srv)
          && !T.allowed(mainFrame: true, scheme: "http", host: "saga.example.org", port: nil, server: URL(string: "http://saga.example.org")!)
          && !T.allowed(mainFrame: true, scheme: "file", host: "", port: nil, server: srv), "Tunnel: fremde Herkunft, anderer Port, http, Offline-Seite")
    check(T.allowed(mainFrame: true, scheme: "https", host: "127.0.0.1", port: 18651, server: URL(string: "https://127.0.0.1:18651")!), "Tunnel: Port gehoert zur Herkunft")
    // Nachrichten
    let good: [String: Any] = ["op": "open", "v": 1, "role": "video", "url": "https://saga.example.org", "user": "1794775499:x", "cred": "abc+/="]
    check(T.parse(good) == .open(role: "video", url: URL(string: "https://saga.example.org")!, user: "1794775499:x", cred: "abc+/="), "Tunnel: open")
    var bad = good; bad["role"] = "both"
    check(T.parse(bad) == nil, "Tunnel: Rolle nur audio/video")
    bad = good; bad["url"] = "http://saga.example.org"
    check(T.parse(bad) == nil, "Tunnel: Knoten nur https")
    bad = good; bad["url"] = "https://u:p@saga.example.org"
    check(T.parse(bad) == nil, "Tunnel: keine Zugangsdaten in der Adresse")
    bad = good; bad["url"] = "wss://saga.example.org/api/sfu/ws"
    check(T.parse(bad) == nil, "Tunnel: Adresse ist die Saga-/Knoten-Adresse, nicht der WS")
    bad = good; bad["cred"] = ""
    check(T.parse(bad) == nil, "Tunnel: leerer Zugang")
    bad = good; bad["user"] = "a b"
    check(T.parse(bad) == nil, "Tunnel: Zugang ohne Leer-/Steuerzeichen")
    bad = good; bad["cred"] = String(repeating: "x", count: 513)
    check(T.parse(bad) == nil, "Tunnel: Zugang hoechstens 512 Zeichen")
    bad = good; bad["v"] = 2
    check(T.parse(bad) == nil, "Tunnel: Version 1")
    check(T.parse(["op": "listen", "v": 1, "id": 3]) == .listen(3) && T.parse(["op": "close", "v": 1, "id": 7]) == .close(7)
          && T.parse(["op": "stats", "v": 1, "id": 1]) == .stats(1), "Tunnel: listen/close/stats")
    check(T.parse(["op": "listen", "v": 1, "id": 0]) == nil && T.parse(["op": "listen", "v": 1, "id": 1.5]) == nil
          && T.parse(["op": "listen", "v": 1, "id": "1"]) == nil && T.parse(["op": "listen", "v": 1, "id": true]) == nil
          && T.parse(["op": "listen", "v": 1]) == nil, "Tunnel: id ganze Zahl >= 1")
    check(T.parse(["op": "x", "v": 1]) == nil && T.parse("open") == nil && T.parse(nil) == nil, "Tunnel: Unbekanntes verworfen")
    check(T.maxOpen >= 4 && T.maxOpen <= 16, "Tunnel: Grenze je Seite")
    // Weg zum Knoten (HornWire, gemeinsam mit der iOS-App)
    check(HornWire.url("https://saga.example.org:8551")?.absoluteString == "wss://saga.example.org:8551/api/sfu/ws", "Tunnel: WS-Adresse aus der Saga-Adresse")
}
