import XCTest
import WebRTC

// Gjallarhorn P11 Stufe 2: Integrationstest gegen einen lokal gestarteten Knoten (gjallarhorn node, HORN_DEV=1, nur
// 127.0.0.1, selbst signiertes Zertifikat). Nur mit Umgebung SAGA_HORN_NODE=https://127.0.0.1:<port> (xcodebuild:
// TEST_RUNNER_SAGA_HORN_NODE), sonst uebersprungen. Benutzt den App-Code (HornNet, HornLeg, HornLogic, HornCryptor):
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

    private func join(room: String, peer: String, dev: String, provider: RTCFrameCryptorKeyProvider, camera: RTCVideoTrack?, path: String) async throws -> [String: HornLeg] {
        var out: [String: HornLeg] = [:]
        var turn: HornLeg.Turn?
        if path == "turn" {
            let tok = try await HornNet.json(base + "/api/dev/token", body: ["room": room, "peer": peer, "role": "audio", "leg": peer + "-turn", "device": "desktop"])["token"] as? String ?? ""
            let t = try await HornNet.turn(base, token: tok)
            let hp = HornLogic.Candidate(node: "", url: base).hostPort!
            turn = HornLeg.Turn(url: "turns:\(hp.host):\(hp.port)?transport=tcp", user: t.user, pass: t.pass, insecure: true)
        }
        for role in ["audio", "video"] {
            let tok = try await HornNet.json(base + "/api/dev/token", body: ["room": room, "peer": peer, "role": role, "device": "desktop"])["token"] as? String ?? ""
            let L = HornLeg(role: role, path: path, me: dev, tiles: 8, factory: Self.factory, provider: provider)
            XCTAssertTrue(L.build(turn: turn, audio: nil, camera: camera, screen: nil, keyIndex: 0, sid: "s" + peer))
            let offer = try await L.offer()
            XCTAssertTrue(offer.contains("VP8") || role == "audio")
            let ans = try await HornNet.leg(base, L.body(token: tok, offer: offer, mobile: false))
            L.id = ans.id; L.slots = ans.slots
            try await L.answer(ans.answer)
            out[role] = L
        }
        return out
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
