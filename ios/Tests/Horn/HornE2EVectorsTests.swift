import XCTest
import CryptoKit
import WebRTC

// Gjallarhorn (Saga 2.5): saga-horn/v1 gegen tests/vectors/horn_e2e.json (Server-Repo, Kopie in ios/Tests/vectors).
// 1. Ableitungen und Rahmenformat mit CryptoKit (App/HornE2E.swift, eigener Saga-Transformer)
// 2. FrameCryptor (webrtc-sdk) selbst: HKDF-Ableitung bytegenau gegen die Vektoren, Loopback zweier PeerConnections
//    mit Testbild aus dem Speicher und stillem Audio-Geraet (nie Kamera/Mikrofon)
final class HornE2EVectorsTests: XCTestCase {
    typealias H = HornE2E

    static let v: [String: Any] = {
        let url = Bundle(for: HornE2EVectorsTests.self).url(forResource: "horn_e2e", withExtension: "json")!
        return try! JSONSerialization.jsonObject(with: Data(contentsOf: url)) as! [String: Any]
    }()
    static var keys: [[String: Any]] { v["keys"] as! [[String: Any]] }
    static func key(_ n: Int) -> Data { hex(keys.first { $0["n"] as! Int == n }!["K"] as! String) }

    static func hex(_ s: String) -> Data {
        var d = Data(capacity: s.count / 2), i = s.startIndex
        while i < s.endIndex { let j = s.index(i, offsetBy: 2); d.append(UInt8(s[i..<j], radix: 16)!); i = j }
        return d
    }
    static func hexs(_ d: Data) -> String { d.map { String(format: "%02x", $0) }.joined() }
    static func raw(_ k: SymmetricKey) -> Data { k.withUnsafeBytes { Data($0) } }

    func testFormat() {
        XCTAssertEqual(Self.v["format"] as? String, "saga-horn/v1")
        let p = Self.v["params"] as! [String: Any]
        XCTAssertEqual(p["frame_salt"] as? String, "saga-horn/v1/frame")
        XCTAssertEqual(p["ratchet_salt"] as? String, "saga-horn-ratchet")
        XCTAssertEqual(p["trailer_len"] as? Int, H.trailerLen)
        XCTAssertEqual(p["window"] as? Int, H.window)
        XCTAssertEqual(p["ratchet_ahead"] as? Int, H.ahead)
    }

    // MARK: Ableitungen

    func testKeysAndRatchet() {
        let ks = Self.keys
        XCTAssertEqual(ks.count, 3)
        for (i, k) in ks.enumerated() {
            let K = Self.hex(k["K"] as! String)
            XCTAssertEqual(Self.hexs(Self.raw(H.frameKey(K))), k["fk"] as? String, "FK n=\(k["n"]!)")
            XCTAssertEqual(k["kid"] as? Int, (k["n"] as! Int) % H.ring)
            if i + 1 < ks.count { XCTAssertEqual(Self.hexs(H.ratchet(K)), ks[i + 1]["K"] as? String, "Ratsche n=\(i)") }
        }
        XCTAssertEqual(H.ratchet(Self.key(0), times: 2), Self.key(2))
    }

    /// FrameCryptors Schluesselableitung im HKDF-Modus: ratchetKey liefert HKDF(K, ratchetSalt, 128 x 00, 32) - dieselbe
    /// Funktion, mit der er das FK (16 Byte) bildet. Die ersten 16 Byte muessen das FK der Vektoren sein (HKDF-Expand,
    /// erster Block). Zugleich der Beleg fuer §1: FrameCryptors Ratsche ist NICHT die Saga-Ratsche und gaebe das FK preis.
    func testFrameCryptorHKDFMatchesVectors() {
        for k in Self.keys {
            let K = Self.hex(k["K"] as! String), n = k["n"] as! Int
            let p = HornCryptor.keyProvider()
            let dev = String(repeating: "ab", count: 16)
            p.setKey(K, with: Int32(n % H.ring), forParticipant: dev)
            XCTAssertEqual(p.exportKey(dev, with: Int32(n % H.ring)), K, "setKey/exportKey n=\(n)")
            let r = p.ratchetKey(dev, with: Int32(n % H.ring))
            XCTAssertEqual(r.count, 32)
            XCTAssertEqual(Self.hexs(r.prefix(16)), k["fk"] as? String, "FrameCryptor-HKDF = FK n=\(n)")
            XCTAssertNotEqual(r, H.ratchet(K), "FrameCryptor-Ratsche darf nicht die Saga-Ratsche sein")
        }
        // Gegenprobe: PBKDF2-Modus (Vorgabe von FrameCryptor) ergibt ein anderes FK
        let pb = RTCFrameCryptorKeyProvider(ratchetSalt: H.frameSalt, ratchetWindowSize: 0, sharedKeyMode: false, uncryptedMagicBytes: nil,
                                            failureTolerance: -1, keyRingSize: 16, discardFrameWhenCryptorNotReady: true, keyDerivationAlgorithm: .PBKDF2)
        pb.setKey(Self.key(0), with: 0, forParticipant: "x")
        XCTAssertNotEqual(Self.hexs(pb.ratchetKey("x", with: 0).prefix(16)), Self.keys[0]["fk"] as? String)
    }

    func testCryptorSlots() {
        let s = H.cryptorSlots(newest: 0, key: Self.key(0))
        XCTAssertEqual(s.map(\.n), [0, 1, 2, 3, 4])
        XCTAssertEqual(s.map(\.kid), [0, 1, 2, 3, 4])
        XCTAssertEqual(s[1].key, Self.key(1)); XCTAssertEqual(s[2].key, Self.key(2))
        let w = H.cryptorSlots(newest: 14, key: Self.key(0))
        XCTAssertEqual(w.map(\.kid), [14, 15, 0, 1, 2], "Ring 16 laeuft ueber")
        XCTAssertEqual(H.scrubKey().count, 32)
        XCTAssertNotEqual(H.scrubKey(), H.scrubKey())
    }

    // MARK: Rahmenformat (eigener Transformer, CryptoKit)

    func testFramesOpenAndSealBitExact() {
        let frames = Self.v["frames"] as! [[String: Any]]
        XCTAssertEqual(frames.count, 5)
        for f in frames {
            let name = f["name"] as! String, kind = H.Kind(rawValue: f["kind"] as! String)!, type = f["type"] as? String
            let n = f["key_n"] as! Int, K = Self.key(n), fk = H.frameKey(K)
            let plain = Self.hex(f["plain"] as! String), out = Self.hex(f["out"] as! String), iv = Self.hex(f["iv"] as! String)
            XCTAssertEqual(H.headerLen(kind, type), f["h"] as? Int, name)
            XCTAssertEqual(out.count, plain.count + 30, "\(name): 30 Byte Zusatz")
            XCTAssertEqual(Array(out.suffix(2)), [0x0C, UInt8(n % 16)], "\(name): Trailer")
            XCTAssertEqual(out.suffix(14).prefix(12), iv, "\(name): IV im Trailer")
            // oeffnen
            guard case .success(let p) = H.parse(out, h: f["h"] as? Int) else { return XCTFail("\(name): parse") }
            XCTAssertEqual(p.ssrc, UInt32(f["ssrc"] as! Int)); XCTAssertEqual(p.ts, UInt32(f["ts"] as! Int)); XCTAssertEqual(p.kid, n % 16)
            XCTAssertEqual(H.open(fk, out, h: f["h"] as! Int, p), plain, "\(name): oeffnen")
            // erzeugen
            if f["iv_mode"] as? String != "fc" {
                XCTAssertEqual(H.iv(ssrc: UInt32(f["ssrc"] as! Int), ts: UInt32(f["ts"] as! Int), ctr: UInt32(f["ctr"] as! Int)), iv, "\(name): IV")
                XCTAssertEqual(H.seal(fk, frame: plain, h: f["h"] as! Int, kid: n % 16, iv: iv), out, "\(name): bitgleich")
            }
        }
    }

    func testSenderCounterAndRoundtrip() {
        var s = H.Sender(n: 1, key: Self.key(1))
        let f = Self.hex("310c00") + Data(repeating: 7, count: 50)
        let a = s.encrypt(f, kind: .video, type: "delta", ssrc: 5, ts: 900)!, b = s.encrypt(f, kind: .video, type: "delta", ssrc: 5, ts: 3900)!
        XCTAssertEqual(a.suffix(14).prefix(12), H.iv(ssrc: 5, ts: 900, ctr: 0))
        XCTAssertEqual(b.suffix(14).prefix(12), H.iv(ssrc: 5, ts: 3900, ctr: 1))
        XCTAssertEqual(a.last, 1)
        XCTAssertNil(s.encrypt(Data(), kind: .video, type: "delta", ssrc: 5, ts: 1), "leer: verwerfen")
        XCTAssertNil(s.encrypt(f, kind: .video, type: "empty", ssrc: 5, ts: 1), "type empty: verwerfen")
        let r = H.Receiver(session: "aaaaaaaaaaaaaaaa")
        r.add(n: 1, key: Self.key(1))
        XCTAssertEqual(try r.decrypt(a, kind: .video, type: "delta", now: 0).get(), f)
        XCTAssertEqual(try r.decrypt(b, kind: .video, type: "delta", now: 0).get(), f)
        XCTAssertEqual(r.decrypt(a, kind: .video, type: "delta", now: 0).failure, .replay)
    }

    func testReceiverCases() {
        let cases = Self.v["cases"] as! [[String: Any]]
        XCTAssertEqual(cases.count, 16)
        var steps = 0
        for c in cases {
            let name = c["name"] as! String, r = H.Receiver(session: "aaaaaaaaaaaaaaaa")
            for n in c["have"] as! [Int] { r.add(n: n, key: Self.key(n)) }
            for (i, s) in (c["steps"] as! [[String: Any]]).enumerated() {
                steps += 1
                let res = r.decrypt(Self.hex(s["data"] as! String), kind: H.Kind(rawValue: s["kind"] as! String)!, type: s["type"] as? String,
                                    now: (s["at"] as! NSNumber).doubleValue)
                let want = s["expect"] as! String
                switch res {
                case .success(let out):
                    XCTAssertEqual(want, "ok", "\(name)#\(i)")
                    if let p = s["plain"] as? String { XCTAssertEqual(Self.hexs(out), p, "\(name)#\(i): Klartext") }
                case .failure(let e):
                    XCTAssertEqual(e.rawValue, want, "\(name)#\(i)")
                }
            }
        }
        XCTAssertGreaterThan(steps, 30)
    }

    func testReplayWindow() {
        let w = Self.v["window"] as! [String: Any]
        XCTAssertEqual(w["size"] as? Int, 128)
        var win = H.ReplayWindow(size: w["size"] as! Int)
        for (i, s) in (w["steps"] as! [[String: Any]]).enumerated() {
            let t = UInt32((s["ts"] as! NSNumber).uint64Value), ok = win.check(t)
            XCTAssertEqual(ok, s["ok"] as? Bool, "Fenster #\(i) ts=\(t)")
            if ok { win.accept(t) }
        }
    }

    func testRunes() {
        XCTAssertEqual(H.runes.count, 24); XCTAssertEqual(H.runeNames.count, 24)
        XCTAssertEqual(String(H.runes), Self.v["rune_alphabet"] as? String)
        for r in Self.v["runes"] as! [[String: Any]] {
            let s = H.runeSeal(callId: r["call_id"] as! String, signPks: (r["devices"] as! [String]).map { Self.hex($0) })
            XCTAssertEqual(Self.hexs(s.hash), r["hash"] as? String, r["name"] as! String)
            XCTAssertEqual(s.idx, r["idx"] as? [Int]); XCTAssertEqual(s.runes, r["runes"] as? String)
        }
    }

    func testFKey() throws {
        let f = Self.v["fkey"] as! [String: Any]
        let body = try JSONSerialization.jsonObject(with: Data((f["body"] as! String).utf8))
        let k = try XCTUnwrap(H.parseFKey(body))
        XCTAssertEqual(k.n, f["n"] as? Int); XCTAssertEqual(Self.hexs(k.key), f["key_hex"] as? String); XCTAssertEqual(k.s, "0123456789abcdef")
        let bad = f["invalid"] as! [[String: Any]]
        XCTAssertEqual(bad.count, 8)
        for (i, b) in bad.enumerated() {
            // ueber JSON-Text, damit Zahlentypen wie im Signal ankommen (1.5 bleibt Gleitkomma)
            let o = try JSONSerialization.jsonObject(with: JSONSerialization.data(withJSONObject: b))
            XCTAssertNil(H.parseFKey(o), "ungueltig #\(i)")
        }
        var b = body as! [String: Any]; b["n"] = true
        XCTAssertNil(H.parseFKey(try JSONSerialization.jsonObject(with: JSONSerialization.data(withJSONObject: b))), "n als Wahrheitswert")
    }

    // MARK: FrameCryptor im Loopback (zwei PeerConnections im Prozess, nur Video, VP8)

    static let devA = String(repeating: "a1", count: 16)

    /// Am Mac nimmt webrtc-sdk das stille Audio-Geraet nicht an (die Fabrik oeffnet CoreAudio) - Loopback deshalb nur im
    /// iOS-Simulator, damit kein Test je das Mikrofon des Macs beruehrt
    override func setUpWithError() throws {
        #if os(macOS)
        if name.contains("Loopback") { throw XCTSkip("Loopback nur im iOS-Simulator (stilles Audio-Geraet)") }
        #endif
    }

    /// Sender und Empfaenger mit K0 auf Platz 0: Bilder kommen an, Zustand ok
    func testLoopbackSameKey() async throws {
        let r = try await Loopback.run { tx, _ in
            tx.setKey(Self.key(0), with: 0, forParticipant: Self.devA); return 0
        } receiver: { rx in rx.setKey(Self.key(0), with: 0, forParticipant: Self.devA) }
        XCTAssertGreaterThan(r.frames, 30, "Bilder nach Entschluesselung, ICE \(r.ice.map(\.rawValue)), Zustand \(r.states.map(\.rawValue)), \(r.stats)")
        XCTAssertTrue(r.states.contains(.ok), "Zustand ok: \(r.states)")
        XCTAssertEqual(r.codec, "VP8/90000", "bevorzugter Codec")
    }

    /// Empfaenger ohne Schluessel: nichts dekodiert (kein Klartext-Durchreichen)
    func testLoopbackNoKey() async throws {
        let r = try await Loopback.run { tx, _ in
            tx.setKey(Self.key(0), with: 0, forParticipant: Self.devA); return 0
        } receiver: { _ in }
        XCTAssertEqual(r.frames, 0, r.stats)
        XCTAssertGreaterThan(r.bytesIn, 10_000, r.stats)
        XCTAssertTrue(r.states.contains(.missingKey) || r.states.contains(.decryptionFailed), "Zustand: \(r.states)")
    }

    /// Falscher Schluessel auf dem richtigen Platz: nichts dekodiert, Schluessel bleibt (failureTolerance -1)
    func testLoopbackWrongKey() async throws {
        let r = try await Loopback.run { tx, _ in
            tx.setKey(Self.key(0), with: 0, forParticipant: Self.devA); return 0
        } receiver: { rx in rx.setKey(Self.key(2), with: 0, forParticipant: Self.devA) }
        XCTAssertEqual(r.frames, 0, r.stats)
        XCTAssertGreaterThan(r.bytesIn, 10_000, "Pakete kommen an, nur entschluesseln geht nicht: \(r.stats)")
        // Befund: mit failureTolerance -1 meldet FrameCryptor DecryptionFailed nie (KeyHandler::DecryptionFailure gibt
        // false zurueck) - "need" (§4 Nachforderung) muss deshalb aus getStats kommen (Bytes ja, framesReceived 0)
        XCTAssertFalse(r.states.contains(.ok), "kein ok mit falschem Schluessel: \(r.states.map(\.rawValue))")
    }

    /// Saga-Beitritt: Sender ratscht selbst (K1 auf Platz 1), Empfaenger hat nur K0 und die vorab eingesetzten Stufen
    func testLoopbackSagaRatchetAhead() async throws {
        let r = try await Loopback.run { tx, _ in
            tx.setKey(Self.key(0), with: 0, forParticipant: Self.devA)
            tx.setKey(H.ratchet(Self.key(0)), with: 1, forParticipant: Self.devA); return 1
        } receiver: { rx in HornCryptor.setSlots(rx, device: Self.devA, newest: 0, key: Self.key(0)) }
        XCTAssertGreaterThan(r.frames, 3, r.stats)
        // und ein ueberschriebener (abgelaufener) Platz ist wirklich unbrauchbar
        let s = try await Loopback.run { tx, _ in
            tx.setKey(Self.key(0), with: 0, forParticipant: Self.devA); return 0
        } receiver: { rx in
            rx.setKey(Self.key(0), with: 0, forParticipant: Self.devA); HornCryptor.scrub(rx, device: Self.devA, kid: 0)
        }
        XCTAssertEqual(s.frames, 0, s.stats)
        XCTAssertGreaterThan(s.bytesIn, 10_000, s.stats)
    }
}

extension Result {
    var failure: Failure? { if case .failure(let e) = self { return e }; return nil }
}
