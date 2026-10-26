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
    /// Wie in der App: Bein ohne Bildschirm-Spur aufbauen, Teilen beginnt erst im laufenden Anruf (Fehler 26.10.: ohne
    /// Spur legte webrtc-sdk keinen FrameCryptor an, das Gegenueber sah nur eine schwarze Flaeche)
    func testScreenShareStartedLater() async throws { try await screenShare(path: "tcp", late: true) }
    // Hinweis: volle iPhone-Aufloesung 1290x2796 sendet die App nie (Erweiterung verkleinert auf lange Kante 1920); im
    // Simulator (Software-VP8, 1,2 Mbit/s) staut sie nach ~15 s - daher kein Test dafuer.
    private func screenShare(path: String, w: Int? = nil, h: Int? = nil, late: Bool = false) async throws {
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
        let a = try await join(room: room, peer: "peerSA", dev: "devA", provider: pa, camera: nil, path: path, tiles: tiles, mobile: true, screen: late ? nil : scr)
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
        if late {
            XCTAssertNil(a["video"]!.scr?.sender.track, "vor dem Teilen keine Spur am Bildschirm-Sender")
            try await Task.sleep(nanoseconds: 1_000_000_000)
            a["video"]!.scr?.sender.track = scr                             // wie hornScreen()
            try await Task.sleep(nanoseconds: 1_500_000_000)                 // Platz belegen, Schluesselbild
        }
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
                      tiles: Int = 8, mobile: Bool = false, screen: RTCVideoTrack? = nil, leg: String? = nil) async throws -> [String: HornLeg] {
        var out: [String: HornLeg] = [:]
        var turn: HornLeg.Turn?
        if path == "turn" {
            let tok = try await HornNet.json(base + "/api/dev/token", body: ["room": room, "peer": peer, "role": "audio", "leg": peer + "-turn", "device": "desktop"])["token"] as? String ?? ""
            let t = try await HornNet.turn(base, token: tok)
            let hp = HornLogic.Candidate(node: "", url: base).hostPort!
            turn = HornLeg.Turn(url: "turns:\(hp.host):\(hp.port)?transport=tcp", user: t.user, pass: t.pass, insecure: true)
        }
        for role in path == "udp" ? ["both"] : ["audio", "video"] {     // wie hornConnect: UDP eine Leitung
            var tb: [String: Any] = ["room": room, "peer": peer, "role": role, "device": mobile ? "mobile" : "desktop", "udp": path == "udp"]
            if let leg { tb["leg"] = leg + "-" + role }                      // weiteres Bein derselben Person (Wechsel)
            let tok = try await HornNet.json(base + "/api/dev/token", body: tb)["token"] as? String ?? ""
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
            return "\(f("rid")):\(f("framesEncoded"))f k\(f("keyFramesEncoded")) sent\(f("framesSent")) \(f("frameWidth"))x\(f("frameHeight")) act=\(f("active")) lim=\(f("qualityLimitationReason")) tgt=\(f("targetBitrate")) bytes=\(f("bytesSent")) delay=\(f("totalPacketSendDelay")) pkts=\(f("packetsSent")) pli=\(f("pliCount")) nack=\(f("nackCount")) rtx=\(f("retransmittedPacketsSent")) impl=\(f("encoderImplementation"))"
        }.sorted().joined(separator: " | ") + " src=" + r.statistics.values.filter { $0.type == "media-source" && ($0.values["kind"] as? String) == "video" }
            .map { "\($0.values["frames"] ?? 0 as NSNumber)@\($0.values["width"] ?? 0 as NSNumber)" }.joined(separator: ",")
            + " rr=" + r.statistics.values.filter { $0.type == "remote-inbound-rtp" && ($0.values["kind"] as? String) == "video" }
            .map { "\($0.values["packetsLost"] ?? "-" as NSString)/\($0.values["fractionLost"] ?? "-" as NSString)" }.joined(separator: ",")
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

// Bughunt 26.10. (3): Ablaeufe der App, die die Tests oben nicht nachbauen - Kamera erst im laufenden Anruf (hornCam),
// dritte Person (Schicht "l" kommt spaeter dazu, hornTune), Teilen beenden/neu/anderer Teilender (hornScreen, bind bei
// Besitzerwechsel), Schluesselwechsel mitten im Anruf (setKeyIndex), UDP-Weg (eine Leitung "both", drei Schichten).
// UDP nur mit SAGA_HORN_NODE_UDP (zweiter lokaler Knoten mit HORN_UDP=1), sonst uebersprungen.
extension HornNodeTests {
    /// Teilnehmer wie die App: Beine, Ereigniskanal (Belegung, CSRC -> Geraet, Cryptor je Platz), Schichten wie hornTune
    final class Dev {
        let dev: String, peer: String, path: String
        var legs: [String: HornLeg] = [:]
        var peers: [String: UInt32] = [:]
        var map: [UInt32: String] = [:]
        init(dev: String, peer: String, path: String) { self.dev = dev; self.peer = peer; self.path = path }
        var v: HornLeg { legs["video"] ?? legs["both"]! }
    }
    private func wire(_ d: Dev, devOf: [String: String]) {
        for L in d.legs.values {
            guard let ev = HornEvents(base: base, leg: L.id ?? "") else { XCTFail("events \(d.dev)"); continue }
            L.events = ev
            var peers: [String: UInt32] = [:]
            ev.onEvent = { e in
                switch e {
                case .hello(_, let p, let s, _, _): peers = p; L.slots = s
                case .peers(let p): peers = p
                case .layout(let s, _): L.slots = s
                default: break
                }
                let m = HornLogic.csrcMap(peers: peers, slots: L.slots, me: d.dev) { devOf[$0] }
                if L === d.v { d.map = m; d.peers = peers }
                let bySlot = Dictionary(L.slots.map { ($0.i, $0) }, uniquingKeysWith: { x, _ in x })
                L.bind { i in HornLogic.owner(bySlot[i], m) }
                let (on, fps) = HornLogic.camActive(path: d.path, others: peers.keys.filter { devOf[$0] != d.dev }.count, lowPower: false)
                L.tuneCam(on: on, fps: fps)
            }
            ev.start()
        }
    }
    private func up(_ ds: [Dev]) async throws -> Bool {
        for _ in 0..<40 {
            try await Task.sleep(nanoseconds: 250_000_000)
            if ds.allSatisfy({ $0.legs.values.allSatisfy { $0.state == .connected } }) { return true }
        }
        return false
    }
    /// dekodierte Bilder je Platz-Art des Bild-Beins, nur Plaetze mit Besitzer `from` (nil = alle)
    private func frames(_ d: Dev, cls: String, from: String? = nil) async -> Int {
        let L = d.v
        guard let pc = L.pc else { return 0 }
        let mids = Set(L.slots.filter { ($0.cls == "screen") == (cls == "screen") && $0.cls != "audio" && $0.i < L.recv.count
            && (from == nil || HornLogic.owner($0, d.map) == from) }.map { L.recv[$0.i].mid })
        let r: RTCStatisticsReport = await withCheckedContinuation { k in pc.statistics { k.resume(returning: $0) } }
        return r.statistics.values.filter { $0.type == "inbound-rtp" && mids.contains(($0.values["mid"] as? String) ?? "") }
            .reduce(0) { $0 + ((($1.values["framesDecoded"] as? NSNumber)?.intValue) ?? 0) }
    }
    private func cam720(_ id: String) -> (RTCVideoSource, TestPattern, RTCVideoTrack) {
        let s = Self.factory.videoSource(), c = TestPattern(delegate: s)
        c.W = 1280; c.H = 720; c.ms = 33
        return (s, c, Self.factory.videoTrack(with: s, trackId: id))
    }
    private func useUDP() throws {
        guard let u = ProcessInfo.processInfo.environment["SAGA_HORN_NODE_UDP"], !u.isEmpty else {
            throw XCTSkip("SAGA_HORN_NODE_UDP nicht gesetzt (lokaler Knoten mit HORN_UDP=1)")
        }
        base = u
    }

    /// Kamera erst im laufenden Anruf (App: hornCam setzt die Spur an den Kamera-Sender, der ohne Spur verhandelt wurde)
    func testOneToOneCameraStartedLater() async throws { try await camLater(path: "tcp") }
    func testOneToOneCameraStartedLaterUDP() async throws { try useUDP(); try await camLater(path: "udp") }
    private func camLater(path: String) async throws {
        let room = "apple-cl-\(UInt32.random(in: 1...UInt32.max))"
        _ = try await HornNet.json(base + "/api/dev/room", body: ["room": room])
        let kA = Data(repeating: 0x5A, count: 32), kB = Data(repeating: 0x3C, count: 32)
        let (sA, cA, tA) = cam720("cl-a"), (sB, cB, tB) = cam720("cl-b")
        cA.start(); cB.start(); defer { cA.stop(); cB.stop() }
        let devOf = ["peerCLA": "devA", "peerCLB": "devB"]
        let pa = HornCryptor.keyProvider(), pb = HornCryptor.keyProvider()
        pa.setKey(kA, with: 0, forParticipant: "devA"); HornCryptor.setSlots(pa, device: "devB", newest: 0, key: kB)
        pb.setKey(kB, with: 0, forParticipant: "devB"); HornCryptor.setSlots(pb, device: "devA", newest: 0, key: kA)
        let tiles = HornLogic.tileSlots(mobile: true)
        let a = Dev(dev: "devA", peer: "peerCLA", path: path), b = Dev(dev: "devB", peer: "peerCLB", path: path)
        a.legs = try await join(room: room, peer: a.peer, dev: a.dev, provider: pa, camera: nil, path: path, tiles: tiles, mobile: true)
        b.legs = try await join(room: room, peer: b.peer, dev: b.dev, provider: pb, camera: nil, path: path, tiles: tiles, mobile: true)
        wire(a, devOf: devOf); wire(b, devOf: devOf)
        let ok = try await up([a, b])
        XCTAssertTrue(ok, "verbunden (\(path))")
        try await Task.sleep(nanoseconds: 1_000_000_000)
        a.v.cam?.sender.track = tA; b.v.cam?.sender.track = tB              // wie hornCam()
        try await Task.sleep(nanoseconds: 8_000_000_000)
        for (d, o) in [(a, "devB"), (b, "devA")] {
            let f = await frames(d, cls: "cam", from: o)
            print("camLater \(path) \(d.dev): \(f) Bilder von \(o) out: \(await sent(d.v))")
            XCTAssertGreaterThan(f, 10, "\(d.dev) dekodiert die spaeter eingeschaltete Kamera von \(o) (\(path))")
        }
        for d in [a, b] { for L in d.legs.values { L.close() } }
        withExtendedLifetime((sA, sB, tA, tB)) {}
    }

    /// 1:1 ueber UDP (eine Leitung, Schichten l/m/h, ohne "l" zu zweit) wie am iPhone im WLAN
    func testOneToOneVideoUDP() async throws {
        try useUDP()
        let room = "apple-u-\(UInt32.random(in: 1...UInt32.max))"
        _ = try await HornNet.json(base + "/api/dev/room", body: ["room": room])
        let kA = Data(repeating: 0x5A, count: 32), kB = Data(repeating: 0x3C, count: 32)
        let (sA, cA, tA) = cam720("u-a"), (sB, cB, tB) = cam720("u-b")
        cA.start(); cB.start(); defer { cA.stop(); cB.stop() }
        let devOf = ["peerUA": "devA", "peerUB": "devB"]
        let pa = HornCryptor.keyProvider(), pb = HornCryptor.keyProvider()
        pa.setKey(kA, with: 0, forParticipant: "devA"); HornCryptor.setSlots(pa, device: "devB", newest: 0, key: kB)
        pb.setKey(kB, with: 0, forParticipant: "devB"); HornCryptor.setSlots(pb, device: "devA", newest: 0, key: kA)
        let tiles = HornLogic.tileSlots(mobile: true)
        let a = Dev(dev: "devA", peer: "peerUA", path: "udp"), b = Dev(dev: "devB", peer: "peerUB", path: "udp")
        a.legs = try await join(room: room, peer: a.peer, dev: a.dev, provider: pa, camera: tA, path: "udp", tiles: tiles, mobile: true)
        b.legs = try await join(room: room, peer: b.peer, dev: b.dev, provider: pb, camera: tB, path: "udp", tiles: tiles, mobile: true)
        wire(a, devOf: devOf); wire(b, devOf: devOf)
        let ok = try await up([a, b])
        XCTAssertTrue(ok, "verbunden (udp)")
        try await Task.sleep(nanoseconds: 8_000_000_000)
        for (d, o) in [(a, "devB"), (b, "devA")] {
            let f = await frames(d, cls: "cam", from: o)
            print("udp \(d.dev): \(f) Bilder von \(o) out: \(await sent(d.v)) slots \(d.v.slots.map { "\($0.cls)\($0.i):\($0.layer ?? "-")\($0.paused ? "P" : "")" })")
            XCTAssertGreaterThan(f, 10, "\(d.dev) dekodiert \(o) ueber UDP")
        }
        for d in [a, b] { for L in d.legs.values { L.close() } }
        withExtendedLifetime((sA, sB, tA, tB)) {}
    }

    /// Dritte Person kommt dazu: A schaltet Schicht "l" zu (hornTune, andere 1 -> 2); B muss weiter, C neu dekodieren
    func testThirdJoinsLayersChange() async throws {
        let room = "apple-3-\(UInt32.random(in: 1...UInt32.max))"
        _ = try await HornNet.json(base + "/api/dev/room", body: ["room": room])
        let kA = Data(repeating: 0x5A, count: 32)
        let (sA, cA, tA) = cam720("3-a")
        cA.start(); defer { cA.stop() }
        let devOf = ["peer3A": "devA", "peer3B": "devB", "peer3C": "devC"]
        let pa = HornCryptor.keyProvider(), pb = HornCryptor.keyProvider(), pc = HornCryptor.keyProvider()
        pa.setKey(kA, with: 0, forParticipant: "devA")
        HornCryptor.setSlots(pb, device: "devA", newest: 0, key: kA); HornCryptor.setSlots(pc, device: "devA", newest: 0, key: kA)
        let tiles = HornLogic.tileSlots(mobile: true)
        let a = Dev(dev: "devA", peer: "peer3A", path: "tcp"), b = Dev(dev: "devB", peer: "peer3B", path: "tcp"), c = Dev(dev: "devC", peer: "peer3C", path: "tcp")
        a.legs = try await join(room: room, peer: a.peer, dev: a.dev, provider: pa, camera: tA, path: "tcp", tiles: tiles, mobile: true)
        b.legs = try await join(room: room, peer: b.peer, dev: b.dev, provider: pb, camera: nil, path: "tcp", tiles: tiles, mobile: true)
        wire(a, devOf: devOf); wire(b, devOf: devOf)
        var ok = try await up([a, b])
        XCTAssertTrue(ok, "A+B verbunden")
        try await Task.sleep(nanoseconds: 6_000_000_000)
        let b1 = await frames(b, cls: "cam", from: "devA")
        XCTAssertGreaterThan(b1, 10, "B dekodiert A zu zweit")
        c.legs = try await join(room: room, peer: c.peer, dev: c.dev, provider: pc, camera: nil, path: "tcp", tiles: tiles, mobile: true)
        wire(c, devOf: devOf)
        ok = try await up([a, b, c])
        XCTAssertTrue(ok, "C verbunden")
        try await Task.sleep(nanoseconds: 3_000_000_000)
        let b2 = await frames(b, cls: "cam", from: "devA"), c2 = await frames(c, cls: "cam", from: "devA")
        try await Task.sleep(nanoseconds: 5_000_000_000)
        let b3 = await frames(b, cls: "cam", from: "devA"), c3 = await frames(c, cls: "cam", from: "devA")
        let e = a.v.cam?.sender.parameters.encodings.map { "\($0.rid ?? "-"):\($0.isActive)" } ?? []
        print("drei: B \(b1)/\(b2)/\(b3) C \(c2)/\(c3) A-Schichten \(e) out: \(await sent(a.v))")
        XCTAssertTrue(e.contains("l:true") && e.contains("m:true"), "zu dritt l und m an: \(e)")
        XCTAssertGreaterThan(b3 - b2, 40, "B dekodiert A nach dem Zuschalten von l weiter (5 s): \(b2) -> \(b3)")
        XCTAssertGreaterThan(c3 - c2, 40, "C dekodiert A (5 s): \(c2) -> \(c3)")
        for d in [a, b, c] { for L in d.legs.values { L.close() } }
        withExtendedLifetime((sA, tA)) {}
    }

    /// Teilen beenden, neu teilen (neue Quelle/Spur wie screenStarted), dann teilt B (Besitzerwechsel am Bildschirm-Platz)
    func testScreenShareStopRestartAndOtherSharer() async throws { try await shareCycle(path: "tcp") }
    /// Offener Fund (Bughunt 26.10., 3): auf dem UDP-Weg bricht nach einigen Sekunden ohne Bild die Sendeschaetzung beim
    /// Wiederaufnehmen des Bildschirms auf ~160 kbit/s ein (bwe vorher 3-4 Mbit/s, danach ~3 Bilder/s, Warteschlange im
    /// Pacer); in etwa jedem zweiten Lauf, nur Bildschirm (884x1920), nicht Kamera, nicht TCP. Ursache noch offen.
    static let udpIdleOpen = "offen: UDP-Sendeschaetzung bricht beim Wiederaufnehmen des Bildschirms nach Pause ein"
    /// nur A: teilen, 8 s nichts senden, wieder teilen (Eingrenzung: Schaetzung nach Pause)
    func testScreenShareAfterIdleUDP() async throws { try useUDP(); try await shareCycle(path: "udp", idle: 8) }
    func testScreenShareAfterIdleTCP() async throws { try await shareCycle(path: "tcp", idle: 8) }
    func testScreenShareStopRestartAndOtherSharerUDP() async throws { try useUDP(); try await shareCycle(path: "udp") }
    private func shareCycle(path: String, idle: Double? = nil) async throws {
        let room = "apple-sr-\(UInt32.random(in: 1...UInt32.max))"
        _ = try await HornNet.json(base + "/api/dev/room", body: ["room": room])
        let kA = Data(repeating: 0x5A, count: 32), kB = Data(repeating: 0x3C, count: 32)
        let (w, h) = ScreenFrame.scaled(width: 1290, height: 2796, maxLong: ScreenFrame.maxLongEdge)
        func share(_ id: String) -> (RTCVideoSource, TestPattern, RTCVideoTrack) {
            let s = Self.factory.videoSource(forScreenCast: true), c = TestPattern(delegate: s)
            c.W = w; c.H = h; c.ms = 1000 / ScreenFrame.fps
            return (s, c, Self.factory.videoTrack(with: s, trackId: id))
        }
        let devOf = ["peerRA": "devA", "peerRB": "devB"]
        let pa = HornCryptor.keyProvider(), pb = HornCryptor.keyProvider()
        pa.setKey(kA, with: 0, forParticipant: "devA"); HornCryptor.setSlots(pa, device: "devB", newest: 0, key: kB)
        pb.setKey(kB, with: 0, forParticipant: "devB"); HornCryptor.setSlots(pb, device: "devA", newest: 0, key: kA)
        let tiles = HornLogic.tileSlots(mobile: true)
        let a = Dev(dev: "devA", peer: "peerRA", path: path), b = Dev(dev: "devB", peer: "peerRB", path: path)
        a.legs = try await join(room: room, peer: a.peer, dev: a.dev, provider: pa, camera: nil, path: path, tiles: tiles, mobile: true)
        b.legs = try await join(room: room, peer: b.peer, dev: b.dev, provider: pb, camera: nil, path: path, tiles: tiles, mobile: true)
        wire(a, devOf: devOf); wire(b, devOf: devOf)
        let ok = try await up([a, b])
        XCTAssertTrue(ok, "verbunden")
        func phase(_ name: String, from: Dev, to: Dev) async throws -> Int {
            let (s, c, t) = share("sr-\(name)")
            print("vor teilen \(name): \(await sent(from.v))")
            c.start()
            from.v.scr?.sender.track = t                                    // hornScreen: Teilen beginnt
            try await Task.sleep(nanoseconds: 2_000_000_000)
            let f0 = await frames(to, cls: "screen", from: from.dev)
            try await Task.sleep(nanoseconds: 4_000_000_000)
            let f1 = await frames(to, cls: "screen", from: from.dev)
            print("teilen \(name): \(from.dev) -> \(to.dev) \(f0) -> \(f1) slots \(to.v.slots.map { "\($0.cls)\($0.i):\($0.peer ?? "-")" }) map \(to.map) out: \(await sent(from.v))")
            from.v.scr?.sender.track = nil                                  // hornScreen: Teilen beendet
            c.stop(); t.isEnabled = false
            withExtendedLifetime(s) {}
            try await Task.sleep(nanoseconds: 1_500_000_000)
            return f1 - f0
        }
        let p1 = try await phase("1", from: a, to: b)
        XCTAssertGreaterThan(p1, 20, "erstes Teilen kommt an")
        if let idle {
            try await Task.sleep(nanoseconds: UInt64(idle * 1e9))
            let p2 = try await phase("2-nach-pause", from: a, to: b)
            if path == "udp" { XCTExpectFailure(Self.udpIdleOpen, strict: false) }
            XCTAssertGreaterThan(p2, 20, "Teilen nach \(idle) s Pause kommt fluessig an")
            for d in [a, b] { for L in d.legs.values { L.close() } }
            return
        }
        let p2 = try await phase("2", from: a, to: b)
        XCTAssertGreaterThan(p2, 20, "erneutes Teilen (neue Spur) kommt an")
        if path == "udp" { XCTExpectFailure(Self.udpIdleOpen, strict: false) }   // B sendet zum ersten Mal, A nach Pause
        let p3 = try await phase("3", from: b, to: a)
        XCTAssertGreaterThan(p3, 20, "anderer Teilender (B) kommt bei A an")
        let p4 = try await phase("4", from: a, to: b)
        XCTAssertGreaterThan(p4, 20, "wieder A")
        for d in [a, b] { for L in d.legs.values { L.close() } }
    }

    /// Schluesselwechsel mitten im Anruf (hornKeyIndex nach fkey-Ratsche/Abgang): Empfaenger dekodiert weiter
    func testKeyIndexSwitchMidCall() async throws {
        let room = "apple-k-\(UInt32.random(in: 1...UInt32.max))"
        _ = try await HornNet.json(base + "/api/dev/room", body: ["room": room])
        let k0 = Data(repeating: 0x5A, count: 32), k7 = Data(repeating: 0x77, count: 32)
        let (sA, cA, tA) = cam720("k-a")
        cA.start(); defer { cA.stop() }
        let devOf = ["peerKA": "devA", "peerKB": "devB"]
        let pa = HornCryptor.keyProvider(), pb = HornCryptor.keyProvider()
        pa.setKey(k0, with: 0, forParticipant: "devA"); pa.setKey(k7, with: 7, forParticipant: "devA")
        HornCryptor.setSlots(pb, device: "devA", newest: 0, key: k0); pb.setKey(k7, with: 7, forParticipant: "devA")
        let tiles = HornLogic.tileSlots(mobile: true)
        let a = Dev(dev: "devA", peer: "peerKA", path: "tcp"), b = Dev(dev: "devB", peer: "peerKB", path: "tcp")
        a.legs = try await join(room: room, peer: a.peer, dev: a.dev, provider: pa, camera: tA, path: "tcp", tiles: tiles, mobile: true)
        b.legs = try await join(room: room, peer: b.peer, dev: b.dev, provider: pb, camera: nil, path: "tcp", tiles: tiles, mobile: true)
        wire(a, devOf: devOf); wire(b, devOf: devOf)
        let ok = try await up([a, b])
        XCTAssertTrue(ok, "verbunden")
        try await Task.sleep(nanoseconds: 5_000_000_000)
        let f0 = await frames(b, cls: "cam", from: "devA")
        for L in a.legs.values { L.setKeyIndex(7) }
        try await Task.sleep(nanoseconds: 5_000_000_000)
        let f1 = await frames(b, cls: "cam", from: "devA")
        print("schluessel: \(f0) -> \(f1)")
        XCTAssertGreaterThan(f0, 10)
        XCTAssertGreaterThan(f1 - f0, 40, "nach setKeyIndex dekodiert B weiter: \(f0) -> \(f1)")
        for d in [a, b] { for L in d.legs.values { L.close() } }
        withExtendedLifetime((sA, tA)) {}
    }

    /// iPhone im Hochformat: die Kamera liefert 1280x720 mit Drehung 90. Der Knoten handelt keine Drehung (CVO) aus -
    /// libwebrtc muss selbst drehen, sonst laege das Bild beim Gegenueber quer
    func testPortraitCameraArrivesUpright() async throws {
        let room = "apple-rot-\(UInt32.random(in: 1...UInt32.max))"
        _ = try await HornNet.json(base + "/api/dev/room", body: ["room": room])
        let kA = Data(repeating: 0x5A, count: 32)
        let (sA, cA, tA) = cam720("rot-a")
        cA.rot = ._90
        cA.start(); defer { cA.stop() }
        let devOf = ["peerOA": "devA", "peerOB": "devB"]
        let pa = HornCryptor.keyProvider(), pb = HornCryptor.keyProvider()
        pa.setKey(kA, with: 0, forParticipant: "devA"); HornCryptor.setSlots(pb, device: "devA", newest: 0, key: kA)
        let tiles = HornLogic.tileSlots(mobile: true)
        let a = Dev(dev: "devA", peer: "peerOA", path: "tcp"), b = Dev(dev: "devB", peer: "peerOB", path: "tcp")
        a.legs = try await join(room: room, peer: a.peer, dev: a.dev, provider: pa, camera: tA, path: "tcp", tiles: tiles, mobile: true)
        b.legs = try await join(room: room, peer: b.peer, dev: b.dev, provider: pb, camera: nil, path: "tcp", tiles: tiles, mobile: true)
        wire(a, devOf: devOf); wire(b, devOf: devOf)
        let ok = try await up([a, b])
        XCTAssertTrue(ok, "verbunden")
        try await Task.sleep(nanoseconds: 6_000_000_000)
        let f = await frames(b, cls: "cam", from: "devA")
        guard let pc = b.v.pc else { return XCTFail("pc") }
        let r: RTCStatisticsReport = await withCheckedContinuation { k in pc.statistics { k.resume(returning: $0) } }
        let dims = r.statistics.values.filter { $0.type == "inbound-rtp" && ($0.values["kind"] as? String) == "video" && (($0.values["framesDecoded"] as? NSNumber)?.intValue ?? 0) > 0 }
            .map { (($0.values["frameWidth"] as? NSNumber)?.intValue ?? 0, ($0.values["frameHeight"] as? NSNumber)?.intValue ?? 0) }
        print("hochformat: \(f) Bilder, Groesse \(dims) out: \(await sent(a.v))")
        XCTAssertGreaterThan(f, 10)
        XCTAssertFalse(dims.isEmpty)
        for (w, h) in dims { XCTAssertGreaterThan(h, w, "Hochformat kommt aufrecht an (\(w)x\(h))") }
        for d in [a, b] { for L in d.legs.values { L.close() } }
        withExtendedLifetime((sA, tA)) {}
    }

    /// Kamera aus und nach einer Pause wieder an (toggleCam -> hornCam), 1:1
    func testCameraOffOnUDP() async throws { try useUDP(); try await camPause(path: "udp") }
    func testCameraOffOnTCP() async throws { try await camPause(path: "tcp") }
    private func camPause(path: String) async throws {
        let room = "apple-cp-\(UInt32.random(in: 1...UInt32.max))"
        _ = try await HornNet.json(base + "/api/dev/room", body: ["room": room])
        let kA = Data(repeating: 0x5A, count: 32)
        let (sA, cA, tA) = cam720("cp-a")
        cA.start(); defer { cA.stop() }
        let devOf = ["peerPCA": "devA", "peerPCB": "devB"]
        let pa = HornCryptor.keyProvider(), pb = HornCryptor.keyProvider()
        pa.setKey(kA, with: 0, forParticipant: "devA"); HornCryptor.setSlots(pb, device: "devA", newest: 0, key: kA)
        let tiles = HornLogic.tileSlots(mobile: true)
        let a = Dev(dev: "devA", peer: "peerPCA", path: path), b = Dev(dev: "devB", peer: "peerPCB", path: path)
        a.legs = try await join(room: room, peer: a.peer, dev: a.dev, provider: pa, camera: tA, path: path, tiles: tiles, mobile: true)
        b.legs = try await join(room: room, peer: b.peer, dev: b.dev, provider: pb, camera: nil, path: path, tiles: tiles, mobile: true)
        wire(a, devOf: devOf); wire(b, devOf: devOf)
        let ok = try await up([a, b])
        XCTAssertTrue(ok, "verbunden")
        try await Task.sleep(nanoseconds: 5_000_000_000)
        let f0 = await frames(b, cls: "cam", from: "devA")
        a.v.cam?.sender.track = nil                                         // Kamera aus
        try await Task.sleep(nanoseconds: 8_000_000_000)
        print("kamera pause \(path): vorher \(f0) out: \(await sent(a.v))")
        a.v.cam?.sender.track = tA                                          // Kamera wieder an
        try await Task.sleep(nanoseconds: 2_000_000_000)
        let f1 = await frames(b, cls: "cam", from: "devA")
        try await Task.sleep(nanoseconds: 4_000_000_000)
        let f2 = await frames(b, cls: "cam", from: "devA")
        print("kamera wieder an \(path): \(f0) / \(f1) -> \(f2) out: \(await sent(a.v))")
        XCTAssertGreaterThan(f0, 50)
        XCTAssertGreaterThan(f2 - f1, 60, "Kamera nach Pause fluessig (4 s): \(f1) -> \(f2)")
        for d in [a, b] { for L in d.legs.values { L.close() } }
        withExtendedLifetime((sA, tA)) {}
    }

    /// Wegwechsel waehrend A teilt (hornSwitch .udp, make-before-break wie hornAdopt): neue Beine entstehen mit der
    /// Bildschirm-Spur (hornLeg: sharing ? scrTrack), dann geht die Spur von den alten weg, die alten schliessen
    func testPathSwitchWhileSharing() async throws {
        try useUDP()
        let room = "apple-sw-\(UInt32.random(in: 1...UInt32.max))"
        _ = try await HornNet.json(base + "/api/dev/room", body: ["room": room])
        let kA = Data(repeating: 0x5A, count: 32)
        let (w, h) = ScreenFrame.scaled(width: 1290, height: 2796, maxLong: ScreenFrame.maxLongEdge)
        let src = Self.factory.videoSource(forScreenCast: true), cap = TestPattern(delegate: src)
        cap.W = w; cap.H = h; cap.ms = 1000 / ScreenFrame.fps
        let t = Self.factory.videoTrack(with: src, trackId: "sw-a")
        cap.start(); defer { cap.stop() }
        let devOf = ["peerWA": "devA", "peerWB": "devB"]
        let pa = HornCryptor.keyProvider(), pb = HornCryptor.keyProvider()
        pa.setKey(kA, with: 0, forParticipant: "devA"); HornCryptor.setSlots(pb, device: "devA", newest: 0, key: kA)
        let tiles = HornLogic.tileSlots(mobile: true)
        let a1 = Dev(dev: "devA", peer: "peerWA", path: "tcp"), b = Dev(dev: "devB", peer: "peerWB", path: "tcp")
        a1.legs = try await join(room: room, peer: a1.peer, dev: a1.dev, provider: pa, camera: nil, path: "tcp", tiles: tiles, mobile: true, leg: "wa1")
        b.legs = try await join(room: room, peer: b.peer, dev: b.dev, provider: pb, camera: nil, path: "tcp", tiles: tiles, mobile: true)
        wire(a1, devOf: devOf); wire(b, devOf: devOf)
        var ok = try await up([a1, b])
        XCTAssertTrue(ok, "verbunden")
        a1.v.scr?.sender.track = t
        try await Task.sleep(nanoseconds: 4_000_000_000)
        let f0 = await frames(b, cls: "screen", from: "devA")
        // neue Verbindung (UDP) mit der laufenden Bildschirm-Spur
        let a2 = Dev(dev: "devA", peer: "peerWA", path: "udp")
        a2.legs = try await join(room: room, peer: a2.peer, dev: a2.dev, provider: pa, camera: nil, path: "udp", tiles: tiles, mobile: true, screen: t, leg: "wa2")
        wire(a2, devOf: devOf)
        ok = try await up([a2])
        XCTAssertTrue(ok, "neue Verbindung")
        try await Task.sleep(nanoseconds: 1_000_000_000)
        for L in a1.legs.values { L.scr?.sender.track = nil }                // hornAdopt: Bild nur noch ueber die neue
        let f1 = await frames(b, cls: "screen", from: "devA")
        try await Task.sleep(nanoseconds: 2_000_000_000)
        for L in a1.legs.values { L.close() }                               // alte schliessen (hornMon)
        try await Task.sleep(nanoseconds: 4_000_000_000)
        let f2 = await frames(b, cls: "screen", from: "devA")
        print("wechsel: \(f0) / \(f1) -> \(f2) slots \(b.v.slots.map { "\($0.cls)\($0.i):\($0.peer ?? "-")" }) map \(b.map) out: \(await sent(a2.v))")
        XCTAssertGreaterThan(f0, 20, "vor dem Wechsel")
        XCTAssertGreaterThan(f2 - f1, 30, "Bildschirm laeuft nach dem Wechsel weiter (6 s): \(f1) -> \(f2)")
        for d in [a2, b] { for L in d.legs.values { L.close() } }
        withExtendedLifetime((src, t)) {}
    }
}
