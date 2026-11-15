import Foundation

// Saga 2.5 A3 "ICE-TCP im WebSocket-Tunnel": Wegwahl, SDP-Biegen und Tunnel-Protokoll (HornWire) - dieselben Faelle wie
// HornTunnelTest.kt (Android).
func hornTunnelTests() {
    typealias L = HornLogic
    // ---- Kandidat: ws/tcp/tls
    var c = L.Candidate(["node": "n1", "url": "https://saga.example.org/", "udp": true, "ws": true, "tcp": false, "tls": false])!
    check(c.ws && !c.tcp && !c.tls && c.udp, "Kandidat: ws/tcp/tls gelesen")
    check(c.url == "https://saga.example.org", "Kandidat: url ohne Schraegstrich")
    c = L.Candidate(["node": "n1", "url": "https://saga.example.org"])!
    check(!c.ws && c.tcp && c.tls, "aelterer Server ohne Felder: kein ws, tcp/tls wie bisher")
    c = L.Candidate(["node": "n1", "url": "https://saga.example.org", "ws": "ja"])!
    check(!c.ws, "ws nur als Wahrheitswert")

    // ---- Wegwahl (Vertrag 10)
    func cand(ws: Bool = false, tcp: Bool = true, tls: Bool = true) -> L.Candidate { L.Candidate(node: "n", url: "https://x.example", ws: ws, tcp: tcp, tls: tls) }
    check(L.ways(cand(), udp: true) == L.Ways(race: ["udp"], then: ["tcp", "turn"]), "ohne ws: UDP -> TCP -> TLS wie bisher")
    check(L.ways(cand(), udp: false) == L.Ways(race: [], then: ["tcp", "turn"]), "ohne ws und ohne UDP: TCP -> TLS")
    check(L.ways(cand(ws: true, tcp: false, tls: false), udp: true) == L.Ways(race: ["udp", "ws"], then: []), "Proxy-Betrieb mit UDP: UDP | Tunnel parallel, kein TCP/TLS")
    check(L.ways(cand(ws: true, tcp: false, tls: false), udp: false) == L.Ways(race: ["ws"], then: []), "Proxy-Betrieb ohne UDP: nur Tunnel, ohne Wartezeit")
    check(L.ways(cand(ws: true), udp: true) == L.Ways(race: ["udp", "ws"], then: ["tcp", "turn"]), "ws neben TCP/TLS (Ein Port): erst UDP | Tunnel, dann TCP, TLS")
    check(L.ways(cand(tcp: false), udp: false) == L.Ways(race: [], then: ["turn"]), "tcp:false: gleich TLS")
    check(L.ways(cand(tcp: false, tls: false), udp: false) == L.Ways(race: [], then: ["turn"]), "nichts angeboten: TLS als letzter Versuch (wie bisher)")
    check(!L.ways(cand(), udp: true).race.contains("ws") && !L.ways(cand(), udp: true).then.contains("ws"), "Tunnel nur, wenn der Kandidat ihn anbietet")
    check(L.wsHeadUDP > L.wsHeadUDPMaybe && L.wsHeadUDP < L.waitUDP, "UDP-Vorsprung kleiner als die UDP-Wartezeit")
    check(L.streamPath(cand(ws: true)) == "ws" && L.streamPath(cand()) == "tcp", "Weg ohne UDP: ws bzw. tcp")

    // ---- Umschalter: UDP-Verlust -> ws
    var sw = L.Switcher(node: "n", path: "udp", now: 0)
    _ = sw.udp(1000, loss: 0.5, stream: "ws")
    check(sw.udp(1000 + L.udpLossFor, loss: 0.5, stream: "ws") == L.Decision(why: .udpLoss, path: "ws"), "UDP-Verlust mit Tunnel: Wechsel auf ws")
    sw = L.Switcher(node: "n", path: "udp", now: 0)
    _ = sw.udp(1000, loss: 0.5)
    check(sw.udp(1000 + L.udpLossFor, loss: 0.5)?.path == "tcp", "UDP-Verlust ohne Tunnel: tcp wie bisher")
    sw = L.Switcher(node: "n", path: "ws", now: 0)
    _ = sw.udp(1000, loss: 0, ok: true, stream: "ws")
    check(sw.udp(1000 + L.udpBackFor, loss: 0, ok: true, stream: "ws")?.path == "udp", "vom Tunnel zurueck auf UDP, wenn UDP wieder geht")

    // ---- Senderate und Namen
    check(HornTx.caps["ws"] == HornTx.caps["tcp"], "ws: Deckel wie TCP (Vertrag 11)")
    check(HornCamPlan.layers(path: "ws").map(\.kbit) == [150, 600] && HornCamPlan.screenRate(path: "ws") == (900, 8), "ws: Kamera l 150/m 600, Bildschirm 900/8")
    check(HornTx.plan(path: "ws", level: 0, others: 1).cam["m"]!.maxBps == 600_000, "ws: Plan wie TCP")
    check(L.pathName("ws") == "WS" && L.pathName("tcp") == "TCP", "Wegname ws")
    check(CallDiag.transport(horn: "ws") == "ws" && CallDiag.transport(horn: "turn") == "tls", "calldiag: transport ws")
    check(HornWire.legPath("ws") == "tcp" && HornWire.legPath("udp") == "udp" && HornWire.legPath("turn") == "turn", "Leg-Body: Tunnel-Bein ist fuer den Knoten tcp")

    // ---- SDP biegen
    let ans = ["v=0", "a=candidate:1 1 udp 2130706431 203.0.113.7 443 typ host",
               "a=candidate:2 1 tcp 1671430143 203.0.113.7 443 typ host tcptype passive",
               "a=candidate:3 1 tcp 1671430142 2001:db8::7 443 typ host tcptype passive",
               "a=candidate:4 1 tcp 1671430141 203.0.113.7 9 typ host tcptype active",
               "a=end-of-candidates", ""].joined(separator: "\r\n")
    let b = HornTunnelLogic.bend(ans, port: 50123)
    let lines = b.components(separatedBy: "\r\n")
    check(lines.contains("a=candidate:2 1 tcp 1671430143 127.0.0.1 50123 typ host tcptype passive"), "passiver TCP-Kandidat -> 127.0.0.1:<Tunnelport>")
    check(!b.contains("2001:db8::7"), "weitere passive TCP-Kandidaten fallen weg (eine lokale Verbindung je Leitung)")
    check(lines.contains("a=candidate:1 1 udp 2130706431 203.0.113.7 443 typ host"), "UDP-Kandidat unveraendert")
    check(lines.contains("a=candidate:4 1 tcp 1671430141 203.0.113.7 9 typ host tcptype active"), "aktiver TCP-Kandidat unveraendert")
    check(lines.first == "v=0" && lines.last == "" && lines.contains("a=end-of-candidates"), "andere Zeilen und Zeilenende bleiben")
    check(HornTunnelLogic.bendable(ans) && !HornTunnelLogic.bendable("v=0\r\na=candidate:1 1 udp 1 203.0.113.7 443 typ host\r\n"), "biegbar nur mit passivem TCP-Kandidaten")
    check(HornTunnelLogic.bend("a=candidate:2 1 TCP 1 203.0.113.7 443 typ srflx tcptype passive", port: 1) == "a=candidate:2 1 TCP 1 203.0.113.7 443 typ srflx tcptype passive", "nur host-Kandidaten")

    // ---- Tunnel-Protokoll (HornWire)
    check(HornWire.url("https://saga.example.org:8443/x?y=1")?.absoluteString == "wss://saga.example.org:8443/api/sfu/ws", "WS-Adresse: wss, Pfad /api/sfu/ws, ohne Query")
    check(HornWire.url("http://127.0.0.1:8080")?.absoluteString == "ws://127.0.0.1:8080/api/sfu/ws", "http (Tests): ws")
    check(HornWire.url("ftp://x") == nil && HornWire.url("") == nil, "andere Schemata nicht")
    check(HornWire.proto == "saga-tunnel.1", "Unterprotokoll saga-tunnel.1")
    let h = HornWire.hello(user: "1700000000:abc", pass: "p/w+=")
    let hj = (try? JSONSerialization.jsonObject(with: Data(h.utf8))) as? [String: Any] ?? [:]
    check(hj["mode"] as? String == "ice" && hj["username"] as? String == "1700000000:abc" && hj["credential"] as? String == "p/w+=", "erste Nachricht: mode ice + Zugangsdaten")
    check(!(HornWire.url("https://saga.example.org")?.absoluteString ?? "").contains("abc"), "Zugang nie in der Adresse")
    check(HornWire.reply(#"{"ok":true}"#) == .ok, "Antwort ok")
    check(HornWire.reply(#"{"ok":false,"error":"auth"}"#) == .denied("auth"), "Antwort abgelehnt mit Code")
    check(HornWire.reply("kaputt") == .bad && HornWire.reply("{}") == .bad, "Antwort unlesbar")
    let d = Data([1, 2, 3])
    check(HornWire.data(d) == Data([0, 1, 2, 3]) && HornWire.frame(HornWire.data(d)) == .data(d), "Datenrahmen: Art 0 + Nutzbytes")
    check(HornWire.ack(0x0102030405060708) == Data([1, 1, 2, 3, 4, 5, 6, 7, 8]), "Quittung: Art 1 + 8 Byte big endian")
    check(HornWire.frame(HornWire.ack(70_000)) == .ack(70_000), "Quittung gelesen")
    check(HornWire.frame(Data()) == .unknown && HornWire.frame(Data([0])) == .unknown && HornWire.frame(Data([1, 0])) == .unknown && HornWire.frame(Data([9, 1])) == .unknown, "leere/kurze/unbekannte Rahmen ignorieren")
    check(HornWire.ackMs >= 10 && HornWire.ackMs <= 20, "Quittungsabstand 10-20 ms (Vertrag 5)")
    check(HornWire.failText(status: 426).contains("Proxy reicht WebSocket nicht durch"), "426: verstaendliche Meldung")
    check(HornWire.failText(status: 0, code: "auth") == "ws abgelehnt auth" && HornWire.failText(status: 502) == "ws HTTP 502", "andere Fehler")
}
