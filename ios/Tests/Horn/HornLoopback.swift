import Foundation
import WebRTC

// Loopback fuer die FrameCryptor-Tests: PeerConnection A sendet ein Testbild (aus dem Speicher) mit FrameCryptor,
// B empfaengt mit FrameCryptor; gezaehlt werden die dekodierten Bilder (getStats inbound-rtp framesDecoded). Audio-Geraet ist still (nie Mikrofon), keine
// ICE-Server (nur Host-Kandidaten im Prozess), Beschreibungen erst nach vollstaendigem Sammeln (kein Trickle).
final class Loopback: NSObject, RTCPeerConnectionDelegate, RTCFrameCryptorDelegate, @unchecked Sendable {
    static let factory: RTCPeerConnectionFactory = {
        RTCInitializeSSL()
        // nur VP8 (Knoten handeln nur VP8/Opus aus; H.264 per VideoToolbox laeuft im Simulator nicht)
        return RTCPeerConnectionFactory(encoderFactory: VP8Only(), decoderFactory: VP8Only(), audioDevice: SilentAudioDevice())
    }()
    struct Outcome { let frames: Int; let bytesIn: Int; let states: [RTCFrameCryptorState]; let codec: String; let ice: [RTCIceConnectionState]; let stats: String }

    private let lock = NSLock()
    private var seen: [RTCFrameCryptorState] = [], ice: [RTCIceConnectionState] = []

    static func run(seconds: Double = 4, sender: (RTCFrameCryptorKeyProvider, RTCFrameCryptor) -> Int,
                    receiver: (RTCFrameCryptorKeyProvider) -> Void) async throws -> Outcome {
        let f = factory, L = Loopback()
        let cfg = RTCConfiguration(); cfg.sdpSemantics = .unifiedPlan; cfg.iceServers = []
        let c = RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)
        guard let a = f.peerConnection(with: cfg, constraints: c, delegate: L),
              let b = f.peerConnection(with: cfg, constraints: c, delegate: L) else { throw Err.setup("pc") }
        defer { a.close(); b.close() }
        let src = f.videoSource(), cap = TestPattern(delegate: src)
        let track = f.videoTrack(with: src, trackId: "horn-test-video")
        let ti = RTCRtpTransceiverInit(); ti.direction = .sendOnly
        guard let tx = a.addTransceiver(with: track, init: ti) else { throw Err.setup("transceiver") }
        // Sender-Cryptor VOR dem ersten Bild und vor der Verhandlung
        let pa = HornCryptor.keyProvider()
        guard let ca = HornCryptor.sender(f, tx.sender, device: HornE2EVectorsTests.devA, provider: pa) else { throw Err.setup("cryptor tx") }
        ca.keyIndex = Int32(sender(pa, ca)); ca.enabled = true
        cap.start(); defer { cap.stop() }

        try await a.hornSetLocal(a.hornOffer(c)); try await gathered(a)
        try await b.hornSetRemote(a.localDescription!)
        guard let rx = b.transceivers.first?.receiver else { throw Err.setup("receiver") }
        let pb = HornCryptor.keyProvider()
        guard let cb = HornCryptor.receiver(f, rx, from: HornE2EVectorsTests.devA, provider: pb) else { throw Err.setup("cryptor rx") }
        cb.delegate = L
        receiver(pb); cb.enabled = true
        try await b.hornSetLocal(b.hornAnswer(c)); try await gathered(b)
        try await a.hornSetRemote(b.localDescription!)
        try await Task.sleep(nanoseconds: UInt64(seconds * 1e9))
        let stats = await summary(a, ["outbound-rtp": ["framesEncoded", "framesSent", "bytesSent", "keyFramesEncoded"]])
            + " | " + summary(b, ["inbound-rtp": ["framesReceived", "framesDecoded", "framesDropped", "bytesReceived", "keyFramesDecoded", "pliCount"]])
        let inb = await inbound(b)
        let sdp = b.remoteDescription?.sdp ?? ""
        let codec = sdp.split(whereSeparator: \.isNewline).first { $0.hasPrefix("a=rtpmap:") }.map { String($0.split(separator: " ").last ?? "") } ?? ""
        // Quelle, Spur und Cryptoren bis hier halten (Delegaten sind schwach, Swift gibt Lokale sonst frueh frei)
        withExtendedLifetime((src, cap, track, tx, rx, ca, cb, pa, pb)) {}
        L.lock.lock(); defer { L.lock.unlock() }
        return Outcome(frames: inb.decoded, bytesIn: inb.bytes, states: L.seen, codec: codec.trimmingCharacters(in: .whitespacesAndNewlines), ice: L.ice, stats: stats)
    }
    enum Err: Error { case setup(String), timeout }
    private static func inbound(_ pc: RTCPeerConnection) async -> (decoded: Int, bytes: Int) {
        let r: RTCStatisticsReport = await withCheckedContinuation { k in pc.statistics { k.resume(returning: $0) } }
        let st = r.statistics.values.first { $0.type == "inbound-rtp" && ($0.values["kind"] as? String) == "video" }
        return ((st?.values["framesDecoded"] as? NSNumber)?.intValue ?? 0, (st?.values["bytesReceived"] as? NSNumber)?.intValue ?? 0)
    }
    private static func summary(_ pc: RTCPeerConnection, _ want: [String: [String]]) async -> String {
        let r: RTCStatisticsReport = await withCheckedContinuation { k in pc.statistics { k.resume(returning: $0) } }
        return r.statistics.values.compactMap { st -> String? in
            guard let keys = want[st.type], (st.values["kind"] as? String) == "video" else { return nil }
            return st.type + " " + keys.map { "\($0)=\(st.values[$0].map { "\($0)" } ?? "-")" }.joined(separator: " ")
        }.joined(separator: "; ")
    }
    private static func gathered(_ pc: RTCPeerConnection) async throws {
        for _ in 0..<200 { if pc.iceGatheringState == .complete { return }; try await Task.sleep(nanoseconds: 50_000_000) }
        throw Err.timeout
    }

    // RTCFrameCryptorDelegate
    func frameCryptor(_ frameCryptor: RTCFrameCryptor, didStateChangeWithParticipantId participantId: String, with stateChanged: RTCFrameCryptorState) {
        lock.lock(); seen.append(stateChanged); lock.unlock()
    }
    // RTCPeerConnectionDelegate (nur Pflicht)
    func peerConnection(_ pc: RTCPeerConnection, didChange stateChanged: RTCSignalingState) {}
    func peerConnection(_ pc: RTCPeerConnection, didAdd stream: RTCMediaStream) {}
    func peerConnection(_ pc: RTCPeerConnection, didRemove stream: RTCMediaStream) {}
    func peerConnectionShouldNegotiate(_ pc: RTCPeerConnection) {}
    func peerConnection(_ pc: RTCPeerConnection, didChange newState: RTCIceConnectionState) { lock.lock(); ice.append(newState); lock.unlock() }
    func peerConnection(_ pc: RTCPeerConnection, didChange newState: RTCIceGatheringState) {}
    func peerConnection(_ pc: RTCPeerConnection, didGenerate candidate: RTCIceCandidate) {}
    func peerConnection(_ pc: RTCPeerConnection, didRemove candidates: [RTCIceCandidate]) {}
    func peerConnection(_ pc: RTCPeerConnection, didOpen dataChannel: RTCDataChannel) {}
}

extension RTCPeerConnection {
    func hornOffer(_ c: RTCMediaConstraints) async throws -> RTCSessionDescription {
        try await withCheckedThrowingContinuation { k in offer(for: c) { s, e in if let s { k.resume(returning: s) } else { k.resume(throwing: e ?? Loopback.Err.setup("offer")) } } }
    }
    func hornAnswer(_ c: RTCMediaConstraints) async throws -> RTCSessionDescription {
        try await withCheckedThrowingContinuation { k in answer(for: c) { s, e in if let s { k.resume(returning: s) } else { k.resume(throwing: e ?? Loopback.Err.setup("answer")) } } }
    }
    func hornSetLocal(_ d: RTCSessionDescription) async throws {
        try await withCheckedThrowingContinuation { (k: CheckedContinuation<Void, Error>) in setLocalDescription(d) { e in if let e { k.resume(throwing: e) } else { k.resume() } } }
    }
    func hornSetRemote(_ d: RTCSessionDescription) async throws {
        try await withCheckedThrowingContinuation { (k: CheckedContinuation<Void, Error>) in setRemoteDescription(d) { e in if let e { k.resume(throwing: e) } else { k.resume() } } }
    }
}

/// Testbild 320x240 bei 15 fps mit wanderndem Balken (Inhalt aendert sich, der Kodierer liefert laufend Rahmen)
final class TestPattern: RTCVideoCapturer, @unchecked Sendable {
    private var timer: DispatchSourceTimer?
    private var n = 0
    /// Groesse (Standard 320x240; 1:1-Test auch 1280x720 wie die Kamera)
    var W = 320, H = 240
    /// Abstand der Rahmen (Standard 66 ms = 15 fps)
    var ms = 66
    func start() {
        let t = DispatchSource.makeTimerSource(queue: DispatchQueue(label: "saga.horntest.video"))
        t.schedule(deadline: .now(), repeating: .milliseconds(ms))
        t.setEventHandler { [weak self] in self?.frame() }
        t.resume(); timer = t
    }
    func stop() { timer?.cancel(); timer = nil }
    private var cache: [RTCI420Buffer] = []
    private func frame() {
        n += 1
        let W = self.W, H = self.H
        if cache.isEmpty {                                                  // 8 vorberechnete Rahmen (schnell auch bei 720p)
            for k in 0..<8 {
                let bar = k * W / 8
                // zeilenweise aus einer Grundzeile kopieren (auch 1290x2796 im Debug-Build schnell genug)
                let base = (0..<(W + H)).map { UInt8(30 + $0 % 180) }
                var y = [UInt8](repeating: 0, count: W * H)
                y.withUnsafeMutableBufferPointer { p in
                    base.withUnsafeBufferPointer { b in
                        for r in 0..<H {
                            let row = p.baseAddress! + r * W
                            row.update(from: b.baseAddress! + r, count: W)
                            let x0 = max(0, bar - 15), x1 = min(W, bar + 16)
                            if x1 > x0 { (row + x0).update(repeating: 235, count: x1 - x0) }
                        }
                    }
                }
                let u = [UInt8](repeating: 90, count: W * H / 4), v = [UInt8](repeating: 200, count: W * H / 4)
                cache.append(RTCI420Buffer(width: Int32(W), height: Int32(H), dataY: y, dataU: u, dataV: v))
            }
        }
        let buf = cache[n % cache.count]
        delegate?.capturer(self, didCapture: RTCVideoFrame(buffer: buf, rotation: ._0, timeStampNs: Int64(DispatchTime.now().uptimeNanoseconds)))
    }
}

final class VP8Only: NSObject, RTCVideoEncoderFactory, RTCVideoDecoderFactory {
    func createEncoder(_ info: RTCVideoCodecInfo) -> (any RTCVideoEncoder)? { RTCVideoEncoderVP8.vp8Encoder() }
    func createDecoder(_ info: RTCVideoCodecInfo) -> (any RTCVideoDecoder)? { RTCVideoDecoderVP8.vp8Decoder() }
    func supportedCodecs() -> [RTCVideoCodecInfo] { [RTCVideoCodecInfo(name: kRTCVideoCodecVp8Name)] }
}

/// Stilles Audio-Geraet: nimmt nie auf, gibt nie aus (die Fabrik fasst so das echte Mikrofon nicht an)
final class SilentAudioDevice: NSObject, RTCAudioDevice, @unchecked Sendable {
    private var delegate: RTCAudioDeviceDelegate?
    var deviceInputSampleRate: Double { 48_000 }
    var inputIOBufferDuration: TimeInterval { 0.01 }
    var inputNumberOfChannels: Int { 1 }
    var inputLatency: TimeInterval { 0 }
    var deviceOutputSampleRate: Double { 48_000 }
    var outputIOBufferDuration: TimeInterval { 0.01 }
    var outputNumberOfChannels: Int { 1 }
    var outputLatency: TimeInterval { 0 }
    var isInitialized: Bool { delegate != nil }
    func initialize(with delegate: RTCAudioDeviceDelegate) -> Bool { self.delegate = delegate; return true }
    func terminateDevice() -> Bool { delegate = nil; return true }
    var isPlayoutInitialized: Bool { delegate != nil }
    func initializePlayout() -> Bool { true }
    var isPlaying: Bool { false }
    func startPlayout() -> Bool { true }
    func stopPlayout() -> Bool { true }
    var isRecordingInitialized: Bool { delegate != nil }
    func initializeRecording() -> Bool { true }
    var isRecording: Bool { false }
    func startRecording() -> Bool { true }
    func stopRecording() -> Bool { true }
}
