import XCTest

// Gjallarhorn P11 Stufe 2: Entscheidungen der Apple-Apps (App/HornLogic.swift) mit denselben Faellen wie das Web
// (tests/horn_logik.js gegen static/horn.js): Punktzahl, Rangfolge, Umschalter mit Hysterese, UDP-Regeln,
// make-before-break, Kacheln/Last-N, Qualitaet, Weg. Dazu: Kandidaten, Bein-Antwort und Ereigniskanal lesen,
// CSRC-Zuordnung, Schluesselplan, Empfaenger-Plaetze fuer FrameCryptor und Nachforderung.
final class HornLogicTests: XCTestCase {
    typealias H = HornLogic
    private func m(_ r: [Double], udp: Double? = nil) -> H.Measure { .pings(r, udp: udp) }
    private func c(_ node: String = "x", udp: Bool = false, here: Bool = false, load: Double = 0) -> H.Candidate {
        H.Candidate(node: node, udp: udp, here: here, load: load)
    }

    // MARK: Punktzahl (horn_logik.js "Punktzahl")

    func testMedianJitter() {
        XCTAssertEqual(H.median([5, 1, 3]), 3)
        XCTAssertEqual(H.median([1, 2, 3, 4]), 2.5)
        XCTAssertEqual(H.jitter([10, 20, 10]), 10)
        XCTAssertEqual(H.jitter([7]), 0)
    }
    func testScore() {
        XCTAssertEqual(H.score(m([20, 20, 20, 20, 20]), c()), 35, "TCP-Kandidat: rtt 20 + UDP fehlt 15")
        XCTAssertEqual(H.score(m([20, 20, 20]), c(here: true)), 10, "Bonus Anruf schon hier")
        XCTAssertEqual(H.score(m([20, 20, 20], udp: 18), c(udp: true)), 20, "UDP mit Probe: kein Abzug")
        XCTAssertEqual(H.score(m([20, 20, 20], udp: nil), c(udp: true)), 35, "UDP ohne Probe am Geraet")
        XCTAssertEqual(H.score(m([20, 40, 20]), c()), 75, "Jitter doppelt")
        XCTAssertEqual(H.loadPenalty(0.5), 0)
        XCTAssertEqual(H.loadPenalty(0.8), 20, accuracy: 1e-9)
        XCTAssertEqual(H.loadPenalty(0.95), 1000)
        XCTAssertEqual(H.score(H.Measure(), c()), .infinity)
        XCTAssertEqual(H.score(nil, c()), .infinity)
        XCTAssertEqual(H.score(m([20, 20, 20], udp: 5), c(udp: true), devUdp: false), 35, "Geraet ohne UDP")
    }
    func testRank() {
        let r = H.rank([c("a", here: true), c("b"), c("c")], ["a": m([60, 60, 60]), "b": m([20, 20, 20]), "c": H.Measure()])
        XCTAssertEqual(r.map(\.cand.node), ["b", "a", "c"])
        XCTAssertEqual(r.map(\.score), [35, 50, .infinity])
        let r2 = H.rank([c("a", here: true), c("b")], ["a": m([40, 40]), "b": m([20, 20])])
        XCTAssertEqual(r2.first?.cand.node, "a", "Bonus haelt den Anruf auf einem Knoten (30 vor 35)")
        let r3 = H.rank([c("a"), c("b")], ["a": m([20]), "b": m([20])])
        XCTAssertEqual(r3.map(\.cand.node), ["a", "b"], "Gleichstand: Reihenfolge des Servers")
    }
    func testBetter() {
        XCTAssertTrue(H.better(100, 70)); XCTAssertFalse(H.better(100, 80))
        XCTAssertTrue(H.better(40, 9)); XCTAssertFalse(H.better(40, 15))
        XCTAssertTrue(H.better(.infinity, 50)); XCTAssertFalse(H.better(50, .infinity))
    }

    // MARK: Umschalter (horn_logik.js "Umschalter")

    func testConnLostAndDisconnected() {
        var s = H.Switcher(node: "a", path: "tcp", now: 0)
        XCTAssertEqual(s.conn(1000, "failed")?.why, .lost)
        XCTAssertNil(s.conn(1000, "disconnected")); XCTAssertNil(s.conn(2900, "disconnected"))
        XCTAssertEqual(s.conn(3100, "disconnected")?.why, .lost)
        _ = s.conn(4000, "connected")
        XCTAssertNil(s.conn(5000, "disconnected")); XCTAssertNil(s.conn(6500, "disconnected"), "kurz getrennt: zurueckgesetzt")
        XCTAssertEqual(s.draining().why, .draining)
    }
    func testBetterFor20s() {
        var s = H.Switcher(node: "a", path: "tcp", now: 0)
        let t0 = 100_000.0
        var got: H.Decision?, at = 0.0
        var t = t0
        while t <= t0 + 25_000 && got == nil { got = s.observe(t, cur: 100, others: [("b", 60)]); at = t; t += 1000 }
        XCTAssertEqual(got?.to, "b"); XCTAssertEqual(at - t0, 20_000)
    }
    func testInterruptionResets() {
        var s = H.Switcher(node: "a", path: "tcp", now: 0)
        let t0 = 100_000.0
        var flap: Double?
        for i in 0...40 {
            let t = t0 + Double(i) * 1000
            let sc = Int(t / 1000) % 15 == 0 ? 95.0 : 60.0
            if s.observe(t, cur: 100, others: [("b", sc)]) != nil, flap == nil { flap = t }
        }
        XCTAssertNil(flap, "alle 15 s kurz nicht besser: kein Wechsel")
    }
    func testSlightlyBetterNever() {
        var s = H.Switcher(node: "a", path: "tcp", now: 0)
        for i in 0..<40 { XCTAssertNil(s.observe(100_000 + Double(i) * 1000, cur: 100, others: [("b", 80)])) }
    }
    func testDwell60s() {
        let t0 = 100_000.0
        var s = H.Switcher(node: "a", path: "tcp", now: t0)
        var first: Double?
        var t = t0
        while t <= t0 + 90_000 && first == nil { if s.observe(t, cur: 100, others: [("b", 50)]) != nil { first = t }; t += 1000 }
        XCTAssertEqual(first.map { $0 - t0 }, 60_000)
    }
    func testAtMost3In10Min() {
        var s = H.Switcher(node: "a", path: "tcp", now: 0)
        var moves: [Double] = [], node = "a", t = 0.0
        while t < 1_200_000 {
            let other = node == "a" ? "b" : "a"
            if let x = s.observe(t, cur: 100, others: [(other, 40)]) { moves.append(t); node = x.to!; s.moved(t, node: node, path: "tcp", why: .better) }
            t += 1000
        }
        let in10 = moves.filter { $0 < 600_000 + moves[0] }.count
        XCTAssertLessThanOrEqual(in10, 3)
        for (i, x) in moves.enumerated() {
            if i >= 3 { XCTAssertGreaterThanOrEqual(x - moves[i - 3], 600_000) }
            if i >= 1 { XCTAssertGreaterThanOrEqual(x - moves[i - 1], 60_000) }
        }
        XCTAssertGreaterThan(moves.count, 3)
        XCTAssertEqual(s.conn(t, "failed")?.why, .lost, "Abbruch zaehlt nicht gegen die Grenze")
    }
    func testUDPLossAndReturn() {
        var s = H.Switcher(node: "a", path: "udp", now: 0)
        XCTAssertNil(s.udp(1000, loss: 0.25)); XCTAssertNil(s.udp(3500, loss: 0.25))
        XCTAssertEqual(s.udp(4000, loss: 0.25)?.path, "tcp")
        s = H.Switcher(node: "a", path: "udp", now: 0)
        XCTAssertNil(s.udp(0, loss: 0.25)); XCTAssertNil(s.udp(2000, loss: 0.05)); XCTAssertNil(s.udp(4000, loss: 0.25)); XCTAssertNil(s.udp(6500, loss: 0.25))
        s.moved(10_000, node: "a", path: "tcp", why: .udpLoss)
        var back: Double?, x = 10_000.0
        while x < 60_000 && back == nil { if s.udp(x, loss: 0, ok: true) != nil { back = x }; x += 1000 }
        XCTAssertEqual(back.map { $0 - 10_000 }, 30_000)
        s.moved(back!, node: "a", path: "udp", why: .udp); s.moved(back! + 5000, node: "a", path: "tcp", why: .udpLoss)
        var back2: Double?
        x = back! + 5000
        while x < back! + 600_000 && back2 == nil { if s.udp(x, loss: 0, ok: true) != nil { back2 = x }; x += 1000 }
        XCTAssertGreaterThanOrEqual(back2! - back!, 300_000, "hoechstens 1x je 5 min")
        var n = H.Switcher(node: "a", path: "tcp")
        for i in 0..<400 { XCTAssertNil(n.udp(Double(i) * 1000, loss: 0, ok: false)) }
    }
    func testCloseOld() {
        XCTAssertFalse(H.closeOld(now: 5000, up: 0, waiting: 0))
        XCTAssertFalse(H.closeOld(now: 1900, up: 1, waiting: 0))
        XCTAssertTrue(H.closeOld(now: 2001, up: 1, waiting: 0))
        XCTAssertFalse(H.closeOld(now: 14_000, up: 1, waiting: 2))
        XCTAssertTrue(H.closeOld(now: 15_001, up: 1, waiting: 2))
    }

    // MARK: Kacheln, Qualitaet, Weg

    func testTiles() {
        XCTAssertEqual([1, 4, 9, 16, 25, 5, 10, 17].map { H.cols($0, width: 1280) }, [1, 2, 3, 4, 5, 3, 4, 5])
        XCTAssertEqual(H.cols(1, width: 390), 1); XCTAssertEqual(H.cols(12, width: 390), 2)
        XCTAssertEqual(H.lastN(mobile: false), 9); XCTAssertEqual(H.lastN(mobile: true), 4)
        XCTAssertEqual(H.tileSlots(mobile: true), 3); XCTAssertEqual(H.tileSlots(mobile: false), 8)
        let devs = (0..<30).map { "d\($0)" }, mv = Set(devs.prefix(9))
        let tl = H.tiles(devs, moving: mv, speaker: "d12", view: "grid")
        XCTAssertEqual(tl.list.count, 25); XCTAssertEqual(tl.more, 5)
        XCTAssertEqual(tl.list.filter(\.moving).count, 9); XCTAssertEqual(tl.list.filter { !$0.moving }.count, 16)
        XCTAssertEqual(tl.list.first?.dev, "d0")
        let sp = H.tiles(Array(devs.prefix(6)), moving: mv, speaker: "d4", view: "speaker")
        XCTAssertEqual(sp.list.first?.dev, "d4"); XCTAssertTrue(sp.list[0].big); XCTAssertTrue(sp.list.dropFirst().allSatisfy { !$0.big })
        XCTAssertEqual(sp.list.count, 6)
        XCTAssertEqual(H.tiles(["x", "y"], moving: [], speaker: nil, view: "speaker").list.first?.dev, "x")
    }
    func testQualityAndPath() {
        XCTAssertEqual(H.quality(rtt: 40, loss: 0), .good)
        XCTAssertEqual(H.quality(rtt: 250), .mid)
        XCTAssertEqual(H.quality(rtt: 40, loss: 0.03), .mid)
        XCTAssertEqual(H.quality(rtt: 40, freezes: 4), .bad)
        XCTAssertEqual(H.quality(rtt: 500), .bad)
        XCTAssertEqual(H.pathName("udp"), "UDP"); XCTAssertEqual(H.pathName("tcp"), "TCP"); XCTAssertEqual(H.pathName("turn"), "TLS")
    }

    // MARK: Kandidaten, Bein, Ereigniskanal

    func testCandidates() {
        let blk: [String: Any] = ["e2e": "saga-horn/v1", "ttl": 60, "candidates": [
            ["node": "horn-0.example.org", "url": "https://horn-0.example.org/", "udp": false, "here": true, "load": 0.12, "peer": "p0", "exp": 1_760_000_000,
             "tokens": ["audio": "h1.a", "video": "h1.v"]],
            ["node": "", "url": "https://x"], ["node": "y", "url": "ftp://y"],
            ["node": "horn-1.example.org", "url": "https://horn-1.example.org:8443", "udp": true, "tokens": ["audio": "a", "video": "v", "both": "b"]],
        ]]
        let l = H.candidates(blk)
        XCTAssertEqual(l.map(\.node), ["horn-0.example.org", "horn-1.example.org"])
        XCTAssertEqual(l[0].url, "https://horn-0.example.org")
        XCTAssertEqual(l[0].tokens["video"], "h1.v"); XCTAssertTrue(l[0].here); XCTAssertEqual(l[0].load, 0.12)
        XCTAssertEqual(l[1].hostPort?.port, 8443); XCTAssertEqual(l[0].hostPort?.port, 443)
        XCTAssertEqual(l[1].wsURL, "wss://horn-1.example.org:8443")
        XCTAssertTrue(l[0].young(now: (1_760_000_000 - 40) * 1000)); XCTAssertFalse(l[0].young(now: (1_760_000_000 - 20) * 1000))
    }
    func testLegAnswerAndEvents() {
        let a = H.LegAnswer(["id": "L1", "answer": "v=0\r\n", "csrc": 7, "state": "ok", "peers": ["pa": 11, "pb": 12, "": 3],
                             "slots": [["i": 0, "class": "audio", "peer": "pa", "csrc": 11], ["i": 1, "class": "speaker", "paused": true], ["class": "x"]]])
        XCTAssertEqual(a?.id, "L1"); XCTAssertEqual(a?.csrc, 7); XCTAssertEqual(a?.peers, ["pa": 11, "pb": 12])
        XCTAssertEqual(a?.slots.count, 2); XCTAssertEqual(a?.slots[1].paused, true); XCTAssertEqual(a?.slots[0].csrc, 11)
        XCTAssertNil(H.LegAnswer(["id": "L1", "answer": "kaputt"]))
        XCTAssertEqual(H.event(#"{"t":"hello","leg":"L1","peers":{"pa":11},"slots":[{"i":2,"class":"tile","peer":"pa","csrc":11,"layer":"l","tid":1}],"est_kbit":900,"state":"ok"}"#),
                       .hello(leg: "L1", peers: ["pa": 11], slots: [H.Slot(i: 2, cls: "tile", peer: "pa", csrc: 11, layer: "l")], est: 900, state: "ok"))
        XCTAssertEqual(H.event(#"{"t":"peers","peers":{"pa":11,"pb":12}}"#), .peers(["pa": 11, "pb": 12]))
        XCTAssertEqual(H.event(#"{"t":"layout","slots":[],"est_kbit":300}"#), .layout(slots: [], est: 300))
        XCTAssertEqual(H.event(#"{"t":"state","state":"draining"}"#), .state("draining"))
        XCTAssertEqual(H.event(#"{"t":"closed","why":"entfernt"}"#), .closed("entfernt"))
        XCTAssertEqual(H.event(#"{"t":"closed"}"#), .closed("zu"))
        XCTAssertEqual(H.event(#"{"t":"pong","n":4}"#), .pong(4))
        XCTAssertNil(H.event(#"{"t":"neu"}"#)); XCTAssertNil(H.event("kein json"))
        XCTAssertEqual(H.ping(3), #"{"t":"ping","n":3}"#)
    }
    func testCsrcMap() {
        let devs = ["pa": "devA", "pb": "devB", "pm": "me"]
        let map = H.csrcMap(peers: ["pa": 11, "pm": 9, "px": 99], slots: [H.Slot(i: 0, cls: "audio", peer: "pb", csrc: 12)], me: "me") { devs[$0] }
        XCTAssertEqual(map, [11: "devA", 12: "devB"], "eigenes Geraet und Unbekannte fehlen")
        XCTAssertTrue(H.unknownRefs(peers: ["pa": 11, "px": 99], mine: "pm") { devs[$0] })
        XCTAssertFalse(H.unknownRefs(peers: ["pa": 11, "pm": 9], mine: "pm") { devs[$0] })
        XCTAssertEqual(H.owner(H.Slot(i: 0, cls: "audio", csrc: 12), map), "devB")
        XCTAssertEqual(H.ref(hp: "x", hpn: [:], node: "n"), "x"); XCTAssertEqual(H.ref(hp: "x", hpn: ["n": "y"], node: "n"), "y")
    }

    // MARK: Schluessel

    func testOwnKey() {
        let k0 = Data(repeating: 1, count: 32)
        var o = H.OwnKey(s: "0123456789abcdef", key: k0)
        XCTAssertEqual(o.active.n, 0); XCTAssertEqual(o.active.kid, 0)
        let r = o.ratchet()
        XCTAssertEqual(r.n, 1); XCTAssertEqual(r.key, HornE2E.ratchet(k0)); XCTAssertEqual(o.active, r, "Beitritt: sofort aktiv")
        let k2 = Data(repeating: 9, count: 32)
        let p = o.rekey(now: 5, key: k2)
        XCTAssertEqual(p.n, 2); XCTAssertEqual(o.active.n, 1, "Abgang: wartet auf das Umschalten")
        let r2 = o.ratchet()
        XCTAssertEqual(r2.n, 3); XCTAssertEqual(r2.key, HornE2E.ratchet(k2), "Beitritt waehrend Wechsel: wartenden ratschen")
        XCTAssertEqual(o.active.n, 1)
        XCTAssertEqual(o.commit()?.n, 3); XCTAssertEqual(o.active.n, 3); XCTAssertNil(o.commit())
        let b = o.body(o.active)
        XCTAssertEqual(HornE2E.parseFKey(try? JSONSerialization.jsonObject(with: Data(b.utf8))), HornE2E.FKey(n: 3, key: o.active.key, s: "0123456789abcdef"))
        XCTAssertEqual(H.OwnKey.session().count, 16)
        XCTAssertEqual(H.parseAck(try? JSONSerialization.jsonObject(with: Data(H.ackBody(n: -1, s: "0123456789abcdef", ok: false).utf8))),
                       H.Ack(n: -1, s: "0123456789abcdef", ok: false))
        XCTAssertEqual(H.parseState(try? JSONSerialization.jsonObject(with: Data(H.stateBody(mic: false, cam: true, screen: false).utf8))),
                       H.PeerState(mic: false, cam: true, screen: false))
    }
    func testStateHp() {
        let plain = try? JSONSerialization.jsonObject(with: Data(H.stateBody(mic: true, cam: false, screen: false).utf8))
        XCTAssertNil(H.parseHp(plain), "ohne Kennungen kein hp")
        let b = H.stateBody(mic: true, cam: false, screen: false, hp: ["p1", "p2", "", String(repeating: "x", count: 65), "p3", "p4", "p5"])
        let o = try? JSONSerialization.jsonObject(with: Data(b.utf8))
        XCTAssertEqual(H.parseHp(o), ["p1", "p2", "p3", "p4"], "hoechstens 4, leere und zu lange verworfen")
        XCTAssertEqual(H.parseState(o), H.PeerState(mic: true, cam: false, screen: false))
        XCTAssertEqual(H.parseHp(["t": "state", "hp": ["a", 3, "b"]]), ["a", "b"])
        XCTAssertNil(H.parseHp(["t": "fkey", "hp": ["a"]]))
        XCTAssertEqual(H.myRefs(["a", "b", "a", "", "c", "d", "e"]), ["a", "b", "c", "d"])
    }
    func testKeyPlan() {
        var p = H.KeyPlan()
        XCTAssertEqual(p.update(["a", "b"]), .init(add: ["a", "b"], gone: [], first: true))
        XCTAssertEqual(p.update(["a", "b", "c"]), .init(add: ["c"], gone: [], first: false))
        XCTAssertEqual(p.update(["a"]), .init(add: [], gone: ["b", "c"], first: false))
        XCTAssertEqual(p.update(["a"]), .init(add: [], gone: [], first: false))
    }
    func testPeerKeys() {
        var k = H.PeerKeys()
        let K = Data(repeating: 3, count: 32)
        let a = k.fkey(HornE2E.FKey(n: 5, key: K, s: "aaaaaaaaaaaaaaaa"), now: 0)!
        XCTAssertEqual(Set(a.set.keys), [5, 6, 7, 8, 9], "neueste + 4 Ratschenstufen")
        XCTAssertEqual(a.set[6], HornE2E.ratchet(K)); XCTAssertEqual(a.set[9], HornE2E.ratchet(K, times: 4))
        let j = k.joined(now: 1000)!                                         // Beitritt: Sender ratscht (n = 6)
        XCTAssertEqual(k.n, 6); XCTAssertEqual(Set(j.set.keys), [6, 7, 8, 9, 10]); XCTAssertEqual(j.set[10], HornE2E.ratchet(K, times: 5))
        XCTAssertEqual(k.due(now: 3999), []); XCTAssertEqual(k.due(now: 4000), [2, 3, 4], "Plaetze vor n=5 3 s spaeter ueberschreiben (n=5 bleibt)")
        XCTAssertNil(k.fkey(HornE2E.FKey(n: 4, key: K, s: "aaaaaaaaaaaaaaaa"), now: 5000), "alte Generation derselben Sitzung")
        let r = k.fkey(HornE2E.FKey(n: 0, key: K, s: "bbbbbbbbbbbbbbbb"), now: 6000)!
        XCTAssertTrue(r.reset); XCTAssertEqual(k.n, 0, "neue Sitzung: von vorn")
        var e = H.PeerKeys()
        XCTAssertNil(e.joined(now: 0), "ohne Schluessel nichts vorab")
        // Abgang: neuer Schluessel n+1, alter Platz erst nach 1,5 s + 3 s
        var g = H.PeerKeys()
        _ = g.fkey(HornE2E.FKey(n: 15, key: K, s: "cccccccccccccccc"), now: 0)
        _ = g.fkey(HornE2E.FKey(n: 16, key: Data(repeating: 4, count: 32), s: "cccccccccccccccc"), now: 0)
        XCTAssertEqual(g.due(now: 4499), []); XCTAssertEqual(g.due(now: 4500), [13, 14, 15])
    }
    func testNeedWatch() {
        var w = H.NeedWatch()
        w.bad("a", now: 0)
        XCTAssertEqual(w.due(now: 1999), []); XCTAssertEqual(w.due(now: 2000), ["a"])
        XCTAssertEqual(w.due(now: 4000), [], "hoechstens alle 5 s"); XCTAssertEqual(w.due(now: 7000), ["a"])
        w.good("a"); XCTAssertEqual(w.due(now: 20_000), [])
        XCTAssertNil(H.unreadable(prev: nil, now: .init(packets: 10, decoded: 0)))
        XCTAssertEqual(H.unreadable(prev: .init(packets: 10, decoded: 3), now: .init(packets: 20, decoded: 3)), true)
        XCTAssertEqual(H.unreadable(prev: .init(packets: 10, decoded: 3), now: .init(packets: 20, decoded: 5)), false)
        XCTAssertNil(H.unreadable(prev: .init(packets: 10, decoded: 3), now: .init(packets: 10, decoded: 3)))
    }
}
