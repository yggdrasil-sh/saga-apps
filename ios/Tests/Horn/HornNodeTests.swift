import XCTest
import WebRTC

// Gjallarhorn P11 Stufe 2: Integrationstest gegen einen lokal gestarteten Knoten (gjallarhorn node, HORN_DEV=1, nur
// 127.0.0.1, selbst signiertes Zertifikat). Nur mit Umgebung SAGA_HORN_NODE=https://127.0.0.1:<port> (xcodebuild:
// TEST_RUNNER_SAGA_HORN_NODE), sonst uebersprungen. Knoten dafuer mit HORN_ICE_IPS=127.0.0.1 starten (sonst bietet er
// keine Loopback-Kandidaten an und testTwoDevicesOverTCP scheitert mit ICE "failed"; siehe README). Benutzt den App-Code (HornNet, HornLeg, HornLogic, HornCryptor):
// - Messung ueber /probe (TLS + 5 Pings)
// - zwei Geraete im Raum ueber ICE-TCP (Ton- und Bild-Leitung getrennt): A sendet ein Testbild (aus dem Speicher) mit
//   FrameCryptor, B empfaengt ueber den Knoten und entschluesselt mit den vorab gesetzten Plaetzen; Ereigniskanal liefert
//   hello/peers/layout, die CSRC-Zuordnung findet A
// - C ohne Schluessel bekommt dieselben Pakete, dekodiert aber nichts (der Knoten sieht nur verschluesselte Rahmen)
// - TLS-Weg: TURN-TLS ueber den Knoten
// Nie Kamera oder Mikrofon: Testbild, stilles Audio-Geraet; Medien-Tests nur im iOS-Simulator (am Mac nur die Messung).
@MainActor
final class HornNodeTests: XCTestCase {
    private static let trust = TrustLocal()
    private var base: String = ""

    override func setUp() async throws {
        guard let b = ProcessInfo.processInfo.environment["SAGA_HORN_NODE"], !b.isEmpty else {
            throw XCTSkip("SAGA_HORN_NODE nicht gesetzt (lokaler gjallarhorn-Knoten)")
        }
        base = b
        // Am Mac oeffnete die WebRTC-Fabrik CoreAudio (webrtc-sdk nimmt das stille Audio-Geraet dort nicht an): Medien-Tests
        // nur im iOS-Simulator; die Messung (nur HTTPS/WebSocket) laeuft ueberall
        #if os(macOS)
        if !name.contains("testProbe") { throw XCTSkip("Medien ueber den Knoten nur im iOS-Simulator (stilles Audio-Geraet)") }
        #endif
        let c = URLSessionConfiguration.ephemeral
        c.connectionProxyDictionary = [:]                                    // lokal, ohne Proxy
        HornNet.session = URLSession(configuration: c, delegate: Self.trust, delegateQueue: nil)
    }

    static let factory: RTCPeerConnectionFactory = {
        RTCInitializeSSL()
        let f = RTCPeerConnectionFactory(encoderFactory: VP8Only(), decoderFactory: VP8Only(), audioDevice: SilentAudioDevice())
        let o = RTCPeerConnectionFactoryOptions()
        o.ignoreLoopbackNetworkAdapter = false                               // Knoten nur auf 127.0.0.1
        f.setOptions(o)
        return f
    }()

    func testProbe() async throws {
        let m = await HornNet.probe(HornLogic.Candidate(node: "local", url: base), ms: 3000)
        XCTAssertTrue(m.ok, "Probe")
        XCTAssertEqual(m.rtts.count, HornLogic.pings)
        XCTAssertNotNil(m.tls)
        XCTAssertLessThan(HornLogic.score(m, HornLogic.Candidate(node: "local", here: true)), 100)
        let bad = await HornNet.probe(HornLogic.Candidate(node: "x", url: "https://127.0.0.1:9"), ms: 500)
        XCTAssertFalse(bad.ok)
    }

    func testTwoDevicesOverTCP() async throws { try await twoDevices(path: "tcp") }
    func testTwoDevicesOverTURN() async throws { try await twoDevices(path: "turn") }

    private func twoDevices(path: String) async throws {
        let room = "apple-\(path)-\(UInt32.random(in: 1...UInt32.max))"
        _ = try await HornNet.json(base + "/api/dev/room", body: ["room": room])
        let kA = Data(repeating: 0x5A, count: 32)
        let src = Self.factory.videoSource(), cap = TestPattern(delegate: src)
        let cam = Self.factory.videoTrack(with: src, trackId: "horn-node-cam")
        cap.start(); defer { cap.stop() }
        let devOf = ["peerAAAA": "devA", "peerBBBB": "devB", "peerCCCC": "devC"]

        // A: sendet (Bild mit Simulcast + Ton), Schluessel K fuer devA, Platz 0
        let pa = HornCryptor.keyProvider()
        pa.setKey(kA, with: 0, forParticipant: "devA")
        let a = try await join(room: room, peer: "peerAAAA", dev: "devA", provider: pa, camera: cam, path: path)
        // B: empfaengt mit den Plaetzen fuer devA (n = 0 und vier Ratschenstufen)
        let pb = HornCryptor.keyProvider()
        HornCryptor.setSlots(pb, device: "devA", newest: 0, key: kA)
        let b = try await join(room: room, peer: "peerBBBB", dev: "devB", provider: pb, camera: nil, path: path)
        // C: ohne Schluessel
        let c = try await join(room: room, peer: "peerCCCC", dev: "devC", provider: HornCryptor.keyProvider(), camera: nil, path: path)

        var events: [HornLogic.Event] = []
        var map: [UInt32: String] = [:]
        var peersOf: [String: [String: UInt32]] = [:]
        for (who, me, legs) in [("B", "devB", b), ("C", "devC", c)] {
            guard let v = legs["video"], let ev = HornEvents(base: base, leg: v.id ?? "") else { XCTFail("events \(who)"); continue }
            v.events = ev
            ev.onEvent = { e in
                switch e {
                case .hello(_, let p, let s, _, _): peersOf[who] = p; v.slots = s
                case .peers(let p): peersOf[who] = p
                case .layout(let s, _): v.slots = s
                default: break
                }
                let m = HornLogic.csrcMap(peers: peersOf[who] ?? [:], slots: v.slots, me: me) { devOf[$0] }
                if who == "B" { events.append(e); map = m }
                let bySlot = Dictionary(v.slots.map { ($0.i, $0) }, uniquingKeysWith: { x, _ in x })
                v.bind { i in HornLogic.owner(bySlot[i], m) }               // FrameCryptor je Platz mit der Geraete-ID des Absenders
            }
            ev.start()
        }
        // verbinden und laufen lassen
        var up = false
        for _ in 0..<40 {
            try await Task.sleep(nanoseconds: 250_000_000)
            up = [a, b, c].allSatisfy { $0.values.allSatisfy { $0.state == .connected } }
            if up { break }
        }
        XCTAssertTrue(up, "alle Beine verbunden (\(path)): \([a, b, c].map { $0.mapValues { $0.state.rawValue } })")
        try await Task.sleep(nanoseconds: 6_000_000_000)
        let rb = await decoded(b["video"]!), rc = await decoded(c["video"]!)
        XCTAssertTrue(events.contains { if case .hello = $0 { return true }; return false }, "hello")
        XCTAssertTrue(events.contains { if case .layout = $0 { return true }; return false } || b["video"]!.slots.contains { $0.peer != nil }, "Belegung")
        XCTAssertTrue(map.values.contains("devA"), "CSRC -> devA: \(map)")
        XCTAssertGreaterThan(rb.frames, 10, "B dekodiert Bilder von A ueber den Knoten (\(path)): \(rb)")
        XCTAssertGreaterThan(rc.packets, 10, "C bekommt Pakete")
        XCTAssertEqual(rc.frames, 0, "C ohne Schluessel dekodiert nichts: \(rc)")
        for legs in [a, b, c] { for L in legs.values { L.close() } }
        withExtendedLifetime((src, cap, cam)) {}
    }

    // 1:1 ueber den Knoten wie am iPhone (Fehler 26.10.): beide senden Kamera (Testbild 1280x720, 30 fps), Handy-Kacheln
    // (Last-N 4), Schichten wie die App bei genau einem Gegenueber (HornLogic.camActive: ohne "l"), Bild-Platz wie
    // hornDraw gewaehlt (nicht pausiert, Besitzer per CSRC) - jede Seite muss Bilder der anderen dekodieren. Vor dem Fix:
    // 0 Rahmen in beide Richtungen (Schichten mit verschiedener Bildrate/zeitlichen Schichten kodierten 720p nicht)
    func testOneToOneVideoBothWays() async throws { try await oneToOne(big: true) }       // 1280x720 wie die Kamera
    func testOneToOneVideoSmall() async throws { try await oneToOne(big: false) }
    func testOneToOneVideoLowPower() async throws { try await oneToOne(big: true, lowPower: true) }   // gemeinsame Grenze 15 fps
    private func oneToOne(big: Bool, lowPower: Bool = false) async throws {
        let room = "apple-dm-\(UInt32.random(in: 1...UInt32.max))"
        _ = try await HornNet.json(base + "/api/dev/room", body: ["room": room])
        let kA = Data(repeating: 0x5A, count: 32), kB = Data(repeating: 0x3C, count: 32)
        let srcA = Self.factory.videoSource(), capA = TestPattern(delegate: srcA), camA = Self.factory.videoTrack(with: srcA, trackId: "dm-a")
        let srcB = Self.factory.videoSource(), capB = TestPattern(delegate: srcB), camB = Self.factory.videoTrack(with: srcB, trackId: "dm-b")
        if big { capA.W = 1280; capA.H = 720; capB.W = 1280; capB.H = 720 }
        if big { capA.ms = 33; capB.ms = 33 }                              // 30 fps
        capA.start(); capB.start(); defer { capA.stop(); capB.stop() }
        let devOf = ["peerDMA": "devA", "peerDMB": "devB"]
        let pa = HornCryptor.keyProvider(), pb = HornCryptor.keyProvider()
        pa.setKey(kA, with: 0, forParticipant: "devA"); HornCryptor.setSlots(pa, device: "devB", newest: 0, key: kB)
        pb.setKey(kB, with: 0, forParticipant: "devB"); HornCryptor.setSlots(pb, device: "devA", newest: 0, key: kA)
        let tiles = HornLogic.tileSlots(mobile: true)
        let a = try await join(room: room, peer: "peerDMA", dev: "devA", provider: pa, camera: camA, path: "tcp", tiles: tiles, mobile: true)
        let b = try await join(room: room, peer: "peerDMB", dev: "devB", provider: pb, camera: camB, path: "tcp", tiles: tiles, mobile: true)
        if let s = a["video"]?.cam?.sender {
            let e = s.parameters.encodings
            XCTAssertTrue(HornCamPlan.uniform(fps: e.map { $0.maxFramerate?.intValue }, temporal: e.map { $0.numTemporalLayers?.intValue }),
                          "Schichten mit gleichen Werten (sonst kodiert libwebrtc bei 720p nichts)")
        }
        var maps: [String: [UInt32: String]] = [:]
        for (who, me, legs) in [("A", "devA", a), ("B", "devB", b)] {
            for L in legs.values {
                guard let ev = HornEvents(base: base, leg: L.id ?? "") else { XCTFail("events \(who)"); continue }
                L.events = ev
                var peers: [String: UInt32] = [:]
                ev.onEvent = { e in
                    switch e {
                    case .hello(_, let p, let s, _, _): peers = p; L.slots = s
                    case .peers(let p): peers = p
                    case .layout(let s, _): L.slots = s
                    default: break
                    }
                    let m = HornLogic.csrcMap(peers: peers, slots: L.slots, me: me) { devOf[$0] }
                    if L.role == "video" { maps[who] = m }
                    let bySlot = Dictionary(L.slots.map { ($0.i, $0) }, uniquingKeysWith: { x, _ in x })
                    L.bind { i in HornLogic.owner(bySlot[i], m) }
                    // Schichten wie CallManager.hornTune: andere = Kennungen ausser der eigenen
                    let (on, fps) = HornLogic.camActive(path: "tcp", others: peers.keys.filter { devOf[$0] != me }.count, lowPower: lowPower)
                    L.tuneCam(on: on, fps: fps)
                }
                ev.start()
            }
        }
        var up = false
        for _ in 0..<40 {
            try await Task.sleep(nanoseconds: 250_000_000)
            up = [a, b].allSatisfy { $0.values.allSatisfy { $0.state == .connected } }
            if up { break }
        }
        XCTAssertTrue(up, "1:1 verbunden: \([a, b].map { $0.mapValues { $0.state.rawValue } })")
        try await Task.sleep(nanoseconds: 8_000_000_000)
        for (who, other, legs) in [("A", "devB", a), ("B", "devA", b)] {
            let v = legs["video"]!
            let r = await decoded(v)
            // Platz wie hornDraw: Bild-Platz (nicht Ton), Besitzer = Gegenueber, nicht pausiert
            let shown = v.slots.filter { $0.cls != "audio" && $0.cls != "screen" && !$0.paused && HornLogic.owner($0, maps[who] ?? [:]) == other }
            XCTAssertFalse(shown.isEmpty, "\(who): Bild-Platz des Gegenuebers belegt: \(v.slots) map \(maps[who] ?? [:])")
            XCTAssertNotNil(shown.first.flatMap { v.track($0.i) as? RTCVideoTrack }, "\(who): Spur am Platz")
            XCTAssertGreaterThan(r.frames, 10, "\(who) dekodiert Bilder des Gegenuebers (1:1 ueber Knoten): \(r)")
            print("dm \(who) out: \(await sent(legs["video"]!))")
            print("dm \(who): frames \(r.frames) packets \(r.packets) slots \(v.slots.map { "\($0.cls)\($0.i):\($0.layer ?? "-")\($0.paused ? "P" : "")" })")
        }
        for legs in [a, b] { for L in legs.values { L.close() } }
        withExtendedLifetime((srcA, capA, camA, srcB, capB, camB)) {}
    }

    // Bildschirm teilen vom iPhone ueber den Knoten (Fehler 26.10.: Bild kam stark verzoegert an und blieb stehen).
    // A teilt wie die App: RTCVideoSource(forScreenCast:), Hochformat wie die ReplayKit-Erweiterung nach dem
    // Verkleinern, erst bewegt, dann statisch (ReplayKit liefert bei stehendem Bildschirm keine neuen Bilder), dann
    // wieder bewegt. B muss am Bildschirm-Platz fortlaufend dekodieren - auch nach der Ruhephase.
    func testScreenShareFromPhone() async throws { try await screenShare(path: "tcp") }
    // Hinweis: volle iPhone-Aufloesung 1290x2796 sendet die App nie (Erweiterung verkleinert auf lange Kante 1920); im
    // Simulator (Software-VP8, 1,2 Mbit/s) staut sie nach ~15 s - daher kein Test dafuer.
    private func screenShare(path: String, w: Int? = nil, h: Int? = nil) async throws {
        let room = "apple-scr-\(UInt32.random(in: 1...UInt32.max))"
        _ = try await HornNet.json(base + "/api/dev/room", body: ["room": room])
        let kA = Data(repeating: 0x5A, count: 32)
        let (sw, sh) = (w, h) == (nil, nil) ? ScreenFrame.scaled(width: 1290, height: 2796, maxLong: ScreenFrame.maxLongEdge) : (w!, h!)
        let src = Self.factory.videoSource(forScreenCast: true), cap = TestPattern(delegate: src)
        cap.W = sw; cap.H = sh; cap.ms = 1000 / ScreenFrame.fps
        let scr = Self.factory.videoTrack(with: src, trackId: "scr-a")
        let devOf = ["peerSA": "devA", "peerSB": "devB"]
        let pa = HornCryptor.keyProvider(), pb = HornCryptor.keyProvider()
        pa.setKey(kA, with: 0, forParticipant: "devA")
        HornCryptor.setSlots(pb, device: "devA", newest: 0, key: kA)
        let tiles = HornLogic.tileSlots(mobile: true)
        let a = try await join(room: room, peer: "peerSA", dev: "devA", provider: pa, camera: nil, path: path, tiles: tiles, mobile: true, screen: scr)
        let b = try await join(room: room, peer: "peerSB", dev: "devB", provider: pb, camera: nil, path: path, tiles: tiles, mobile: true)
        var map: [UInt32: String] = [:]
        for (me, legs) in [("devA", a), ("devB", b)] {
            for L in legs.values {
                guard let ev = HornEvents(base: base, leg: L.id ?? "") else { XCTFail("events"); continue }
                L.events = ev
                var peers: [String: UInt32] = [:]
                ev.onEvent = { e in
                    switch e {
                    case .hello(_, let p, let s, _, _): peers = p; L.slots = s
                    case .peers(let p): peers = p
                    case .layout(let s, _): L.slots = s
                    default: break
                    }
                    let m = HornLogic.csrcMap(peers: peers, slots: L.slots, me: me) { devOf[$0] }
                    if me == "devB" && L.role == "video" { map = m }
                    let bySlot = Dictionary(L.slots.map { ($0.i, $0) }, uniquingKeysWith: { x, _ in x })
                    L.bind { i in HornLogic.owner(bySlot[i], m) }
                }
                ev.start()
            }
        }
        cap.start()
        var up = false
        for _ in 0..<40 {
            try await Task.sleep(nanoseconds: 250_000_000)
            up = [a, b].allSatisfy { $0.values.allSatisfy { $0.state == .connected } }
            if up { break }
        }
        XCTAssertTrue(up, "verbunden")
        let v = b["video"]!
        func screenFrames() async -> (frames: Int, w: Int, h: Int) {
            guard let s = v.slots.first(where: { $0.cls == "screen" }), s.i < v.recv.count, let pc = v.pc else { return (0, 0, 0) }
            let mid = v.recv[s.i].mid
            let r: RTCStatisticsReport = await withCheckedContinuation { k in pc.statistics { k.resume(returning: $0) } }
            for x in r.statistics.values where x.type == "inbound-rtp" && (x.values["mid"] as? String) == mid {
                return ((x.values["framesDecoded"] as? NSNumber)?.intValue ?? 0, (x.values["frameWidth"] as? NSNumber)?.intValue ?? 0,
                        (x.values["frameHeight"] as? NSNumber)?.intValue ?? 0)
            }
            return (0, 0, 0)
        }
        // Phase 1: bewegt; jede Sekunde muessen neue Bilder kommen
        var last = 0, seq: [Int] = []
        for _ in 0..<8 {
            try await Task.sleep(nanoseconds: 1_000_000_000)
            let f = await screenFrames().frames; seq.append(f - last); last = f
        }
        let p1 = await screenFrames()
        print("scr \(sw)x\(sh) bewegt: je s \(seq) -> \(p1) out: \(await sent(a["video"]!)) slots \(v.slots.map { "\($0.cls)\($0.i):\($0.layer ?? "-")\($0.paused ? "P" : "")" }) map \(map)")
        XCTAssertGreaterThan(seq.suffix(4).min() ?? 0, 3, "Bildschirm kommt fortlaufend an (\(sw)x\(sh)): je s \(seq)")
        XCTAssertGreaterThan(p1.w * p1.h, 0, "Bildgroesse beim Empfaenger")
        // Phase 2: statisch (ReplayKit liefert nichts), dann wieder bewegt - Bild muss sofort weiterlaufen
        cap.stop()
        try await Task.sleep(nanoseconds: 4_000_000_000)
        let still = await screenFrames().frames
        let stillSlot = v.slots.first { $0.cls == "screen" }
        print("scr statisch: \(still - p1.frames) Bilder in 4 s, Platz \(String(describing: stillSlot))")
        cap.start()
        last = await screenFrames().frames; seq = []
        for _ in 0..<5 {
            try await Task.sleep(nanoseconds: 1_000_000_000)
            let f = await screenFrames().frames; seq.append(f - last); last = f
        }
        print("scr wieder bewegt: je s \(seq) out: \(await sent(a["video"]!))")
        XCTAssertGreaterThan(seq.suffix(3).min() ?? 0, 3, "nach statischer Phase laeuft der Bildschirm weiter: je s \(seq)")
        cap.stop()
        for legs in [a, b] { for L in legs.values { L.close() } }
        withExtendedLifetime((src, cap, scr)) {}
    }

    // Wie ReplayKit: Bilder nur bei Aenderung (NV12 aus der Erweiterung, 884x1920), ueber ScreenSink wie die App.
    // Ein Bild beim Start, dann 5 s Ruhe, dann alle 2 s eine einzelne Aenderung. Ohne Wiederholung (Verhalten vor dem
    // Fix) kommt das erste Bild erst mit einer spaeteren Aenderung an (das Schluesselbild, das der Knoten beim Belegen
    // des Platzes anfordert, kodiert libwebrtc erst mit dem naechsten Eingangsbild).
    func testScreenShareSparseFramesOld() async throws { try await sparse(repeatMs: nil) }
    func testScreenShareSparseFrames() async throws { try await sparse(repeatMs: ScreenFrame.repeatMs) }
    private func sparse(repeatMs: Int?) async throws {
        let room = "apple-sp-\(UInt32.random(in: 1...UInt32.max))"
        _ = try await HornNet.json(base + "/api/dev/room", body: ["room": room])
        let kA = Data(repeating: 0x5A, count: 32)
        let (w, h) = ScreenFrame.scaled(width: 1290, height: 2796, maxLong: ScreenFrame.maxLongEdge)
        let src = Self.factory.videoSource(forScreenCast: true), sink = ScreenSink(repeatMs: repeatMs)
        let scr = Self.factory.videoTrack(with: src, trackId: "scr-sp")
        let devOf = ["peerPA": "devA", "peerPB": "devB"]
        let pa = HornCryptor.keyProvider(), pb = HornCryptor.keyProvider()
        pa.setKey(kA, with: 0, forParticipant: "devA")
        HornCryptor.setSlots(pb, device: "devA", newest: 0, key: kA)
        let tiles = HornLogic.tileSlots(mobile: true)
        let a = try await join(room: room, peer: "peerPA", dev: "devA", provider: pa, camera: nil, path: "tcp", tiles: tiles, mobile: true, screen: scr)
        let b = try await join(room: room, peer: "peerPB", dev: "devB", provider: pb, camera: nil, path: "tcp", tiles: tiles, mobile: true)
        for (me, legs) in [("devA", a), ("devB", b)] {
            for L in legs.values {
                guard let ev = HornEvents(base: base, leg: L.id ?? "") else { XCTFail("events"); continue }
                L.events = ev
                var peers: [String: UInt32] = [:]
                ev.onEvent = { e in
                    switch e {
                    case .hello(_, let p, let s, _, _): peers = p; L.slots = s
                    case .peers(let p): peers = p
                    case .layout(let s, _): L.slots = s
                    default: break
                    }
                    let m = HornLogic.csrcMap(peers: peers, slots: L.slots, me: me) { devOf[$0] }
                    let bySlot = Dictionary(L.slots.map { ($0.i, $0) }, uniquingKeysWith: { x, _ in x })
                    L.bind { i in HornLogic.owner(bySlot[i], m) }
                }
                ev.start()
            }
        }
        var up = false
        for _ in 0..<40 {
            try await Task.sleep(nanoseconds: 250_000_000)
            up = [a, b].allSatisfy { $0.values.allSatisfy { $0.state == .connected } }
            if up { break }
        }
        XCTAssertTrue(up, "verbunden")
        let v = b["video"]!
        func frames() async -> Int {
            guard let s = v.slots.first(where: { $0.cls == "screen" }), s.i < v.recv.count, let pc = v.pc else { return 0 }
            let mid = v.recv[s.i].mid
            let r: RTCStatisticsReport = await withCheckedContinuation { k in pc.statistics { k.resume(returning: $0) } }
            return r.statistics.values.first { $0.type == "inbound-rtp" && ($0.values["mid"] as? String) == mid }
                .flatMap { ($0.values["framesDecoded"] as? NSNumber)?.intValue } ?? 0
        }
        sink.set(src)
        sink.deliver(Self.nv12(w, h, k: 0), rotation: ._0)                 // Teilen beginnt: ein Bild, dann Ruhe
        try await Task.sleep(nanoseconds: 5_000_000_000)
        let first = await frames()
        var after: [Int] = []
        for k in 1...4 {                                                    // einzelne Aenderungen
            sink.deliver(Self.nv12(w, h, k: k), rotation: ._0)
            try await Task.sleep(nanoseconds: 2_000_000_000)
            after.append(await frames())
        }
        print("sparse repeat=\(repeatMs.map(String.init) ?? "aus"): nach 5 s Ruhe \(first) Bilder, dann \(after), wiederholt \(sink.repeats) out: \(await sent(a["video"]!))")
        sink.set(nil)
        if repeatMs != nil {
            XCTAssertGreaterThan(first, 0, "erstes Bild kommt ohne weitere Aenderung an")
            for i in after.indices { XCTAssertGreaterThan(after[i], i == 0 ? first : after[i - 1], "jede Aenderung kommt an: \(after)") }
        }
        for legs in [a, b] { for L in legs.values { L.close() } }
        withExtendedLifetime((src, scr, sink)) {}
    }
    /// NV12 wie aus der Erweiterung (IOSurface), Inhalt je k verschieden
    static func nv12(_ w: Int, _ h: Int, k: Int) -> CVPixelBuffer {
        var pb: CVPixelBuffer?
        let attrs: [CFString: Any] = [kCVPixelBufferIOSurfacePropertiesKey: [:] as [CFString: Any]]
        CVPixelBufferCreate(nil, w, h, kCVPixelFormatType_420YpCbCr8BiPlanarFullRange, attrs as CFDictionary, &pb)
        CVPixelBufferLockBaseAddress(pb!, [])
        for plane in 0..<2 {
            let base = CVPixelBufferGetBaseAddressOfPlane(pb!, plane)!, st = CVPixelBufferGetBytesPerRowOfPlane(pb!, plane)
            let rows = plane == 0 ? h : h / 2
            for r in 0..<rows { memset(base + r * st, Int32(plane == 0 ? (40 + (r / 64 + k * 7) * 23) % 200 : 128), st) }
        }
        CVPixelBufferUnlockBaseAddress(pb!, [])
        return pb!
    }

    private func join(room: String, peer: String, dev: String, provider: RTCFrameCryptorKeyProvider, camera: RTCVideoTrack?, path: String,
                      tiles: Int = 8, mobile: Bool = false, screen: RTCVideoTrack? = nil) async throws -> [String: HornLeg] {
        var out: [String: HornLeg] = [:]
        var turn: HornLeg.Turn?
        if path == "turn" {
            let tok = try await HornNet.json(base + "/api/dev/token", body: ["room": room, "peer": peer, "role": "audio", "leg": peer + "-turn", "device": "desktop"])["token"] as? String ?? ""
            let t = try await HornNet.turn(base, token: tok)
            let hp = HornLogic.Candidate(node: "", url: base).hostPort!
            turn = HornLeg.Turn(url: "turns:\(hp.host):\(hp.port)?transport=tcp", user: t.user, pass: t.pass, insecure: true)
        }
        for role in ["audio", "video"] {
            let tok = try await HornNet.json(base + "/api/dev/token", body: ["room": room, "peer": peer, "role": role, "device": mobile ? "mobile" : "desktop"])["token"] as? String ?? ""
            let L = HornLeg(role: role, path: path, me: dev, tiles: tiles, factory: Self.factory, provider: provider)
            XCTAssertTrue(L.build(turn: turn, audio: nil, camera: camera, screen: screen, keyIndex: 0, sid: "s" + peer))
            let offer = try await L.offer()
            XCTAssertTrue(offer.contains("VP8") || role == "audio")
            let ans = try await HornNet.leg(base, L.body(token: tok, offer: offer, mobile: mobile))
            L.id = ans.id; L.slots = ans.slots
            try await L.answer(ans.answer)
            out[role] = L
        }
        return out
    }

    /// Sende-Statistik je rid (Bild): Rahmen, Groesse, Grund der Begrenzung
    private func sent(_ L: HornLeg) async -> String {
        guard let pc = L.pc else { return "-" }
        let r: RTCStatisticsReport = await withCheckedContinuation { k in pc.statistics { k.resume(returning: $0) } }
        return r.statistics.values.filter { $0.type == "outbound-rtp" && ($0.values["kind"] as? String) == "video" }.map { s in
            let v = s.values
            func f(_ k: String) -> String { v[k].map { "\($0)" } ?? "-" }
            return "\(f("rid")):\(f("framesEncoded"))f k\(f("keyFramesEncoded")) sent\(f("framesSent")) \(f("frameWidth"))x\(f("frameHeight")) act=\(f("active")) lim=\(f("qualityLimitationReason")) tgt=\(f("targetBitrate")) bytes=\(f("bytesSent")) delay=\(f("totalPacketSendDelay")) pkts=\(f("packetsSent")) pli=\(f("pliCount")) impl=\(f("encoderImplementation"))"
        }.sorted().joined(separator: " | ") + " src=" + r.statistics.values.filter { $0.type == "media-source" && ($0.values["kind"] as? String) == "video" }
            .map { "\($0.values["frames"] ?? 0 as NSNumber)@\($0.values["width"] ?? 0 as NSNumber)" }.joined(separator: ",")
            + " bwe=" + r.statistics.values.filter { $0.type == "candidate-pair" && ($0.values["nominated"] as? NSNumber)?.boolValue == true }
            .map { "\($0.values["availableOutgoingBitrate"] ?? 0 as NSNumber)" }.joined(separator: ",")
    }
    private func decoded(_ L: HornLeg) async -> (frames: Int, packets: Int) {
        guard let pc = L.pc else { return (0, 0) }
        let r: RTCStatisticsReport = await withCheckedContinuation { k in pc.statistics { k.resume(returning: $0) } }
        var f = 0, p = 0
        for s in r.statistics.values where s.type == "inbound-rtp" && (s.values["kind"] as? String) == "video" {
            f += (s.values["framesDecoded"] as? NSNumber)?.intValue ?? 0
            p += (s.values["packetsReceived"] as? NSNumber)?.intValue ?? 0
        }
        return (f, p)
    }
}

/// nur Tests: selbst signiertes Zertifikat des lokalen Knotens (127.0.0.1) annehmen
final class TrustLocal: NSObject, URLSessionDelegate, @unchecked Sendable {
    func urlSession(_ session: URLSession, didReceive challenge: URLAuthenticationChallenge) async -> (URLSession.AuthChallengeDisposition, URLCredential?) {
        guard challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust,
              ["127.0.0.1", "localhost"].contains(challenge.protectionSpace.host), let t = challenge.protectionSpace.serverTrust else {
            return (.performDefaultHandling, nil)
        }
        return (.useCredential, URLCredential(trust: t))
    }
}
