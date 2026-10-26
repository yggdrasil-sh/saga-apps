import Foundation
import WebRTC

// Gjallarhorn (Saga 2.5, P11 Stufe 2): ein Bein zu einem Knoten = eine PeerConnection (H1: auf TCP/TLS getrennte Ton-
// und Bild-Leitung, auf UDP eine Leitung mit BUNDLE). Ohne CallManager/UI, damit der Integrationstest dieselbe Klasse
// gegen einen lokalen Knoten benutzt (Tests/Horn/HornNodeTests.swift). Vertrag: docs/APP-API.md "Am Knoten",
// gjallarhorn/NODE.md "Erwartungen an Clients", docs/HORN-E2E.md §2/§6:
// - Angebot: zuerst die eigenen Sendespuren, dann die Empfangsplaetze in fester Reihenfolge (Ton: 3; Bild: Sprecher,
//   Bildschirm, Kacheln = Last-N - 1)
// - Kamera mit Simulcast: TCP/TLS l (1/4) + m (voll); UDP l/m/h - alle T3, gleiche Bildrate (HornCamPlan). Bildschirm als eigener
//   Sender (Stream-ID "screen…" und screen_mid), Raten wie 2.4 relay-tcp bzw. relay-udp
// - FrameCryptor an JEDEM Sender vor der Verhandlung (kein Rahmen geht unverschluesselt raus), an jedem Empfangsplatz mit
//   der Geraete-ID des Besitzers (wechselt der Besitzer, neuer Cryptor); ohne Besitzer ein Platzhalter ohne Schluessel
//   (verwirft alles)
// Hinweis: jitterBufferTarget (Web: 150 ms fuer Bild auf TCP/TLS) bietet die ObjC-API von webrtc-sdk M150 nicht an.

@MainActor
final class HornLeg: NSObject {
    let role: String                                                        // audio | video | both
    let path: String                                                        // udp | tcp | turn
    let me: String
    let tiles: Int
    private let factory: RTCPeerConnectionFactory
    private let provider: RTCFrameCryptorKeyProvider
    private(set) var pc: RTCPeerConnection?
    var id: String?
    /// Empfangsplaetze in der Reihenfolge des Angebots (= Slot.i)
    private(set) var recv: [RTCRtpTransceiver] = []
    private(set) var kinds: [String] = []
    var slots: [HornLogic.Slot] = []
    var est = 0
    var events: HornEvents?
    private(set) var tr: RTCRtpTransceiver?
    private(set) var cam: RTCRtpTransceiver?
    private(set) var scr: RTCRtpTransceiver?
    private var senders: [RTCFrameCryptor] = []
    private var rx: [Int: (dev: String, c: RTCFrameCryptor)] = [:]
    private var lastRtp: [Int: UInt32] = [:]
    private var liveAt: [Int: Double] = [:]
    private var liveSince: [Int: Double] = [:]
    private var csrcAt: [Int: (csrc: UInt32, t: Double)] = [:]
    var rxStats: [String: HornLogic.RxSample] = [:]
    private(set) var state: RTCPeerConnectionState = .new
    var onState: ((RTCPeerConnectionState) -> Void)?
    /// FrameCryptor-Zustand je Sender (Geraet): ok bzw. MissingKey/DecryptionFailed (Nachforderung)
    var onCryptor: ((String, Bool) -> Void)?
    nonisolated static let none = "-"
    static let audioSlots = 3

    struct Turn { var url: String; var user: String; var pass: String; var insecure = false }

    init(role: String, path: String, me: String, tiles: Int, factory: RTCPeerConnectionFactory, provider: RTCFrameCryptorKeyProvider) {
        self.role = role; self.path = path; self.me = me; self.tiles = tiles; self.factory = factory; self.provider = provider
        super.init()
    }

    /// PeerConnection mit Sendern und Empfangsplaetzen; turn = TLS-Weg (nur Relais zum Knoten selbst)
    func build(turn: Turn?, audio: RTCAudioTrack?, camera: RTCVideoTrack?, screen: RTCVideoTrack?, keyIndex: Int, sid: String) -> Bool {
        let c = RTCConfiguration()
        c.sdpSemantics = .unifiedPlan
        c.bundlePolicy = .maxBundle
        c.rtcpMuxPolicy = .require
        c.tcpCandidatePolicy = .enabled                                     // ICE-TCP (aktiv) zum Knoten auf 443
        c.continualGatheringPolicy = .gatherOnce
        if let t = turn {
            c.iceServers = [RTCIceServer(urlStrings: [t.url], username: t.user, credential: t.pass,
                                         tlsCertPolicy: t.insecure ? .insecureNoCheck : .secure)]
            c.iceTransportPolicy = .relay
        } else { c.iceServers = [] }
        guard let pc = factory.peerConnection(with: c, constraints: RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: ["DtlsSrtpKeyAgreement": "true"]), delegate: self) else { return false }
        self.pc = pc
        if role != "video" {                                                // Ton-Leitung bzw. UDP-Weg: eigener Ton + 3 Tonplaetze
            let i = RTCRtpTransceiverInit(); i.direction = .sendOnly; i.streamIds = [sid]
            tr = audio.flatMap { pc.addTransceiver(with: $0, init: i) } ?? pc.addTransceiver(of: .audio, init: i)
            if let s = tr?.sender { cryptSender(s, keyIndex) }
            for _ in 0..<Self.audioSlots { addRecv(.audio) }
        }
        if role != "audio" {
            let ci = RTCRtpTransceiverInit(); ci.direction = .sendOnly; ci.streamIds = [sid]
            ci.sendEncodings = Self.encodings(path: path)
            cam = camera.flatMap { pc.addTransceiver(with: $0, init: ci) } ?? pc.addTransceiver(of: .video, init: ci)
            if let s = cam?.sender { cryptSender(s, keyIndex) }
            let si = RTCRtpTransceiverInit(); si.direction = .sendOnly; si.streamIds = ["screen" + sid]
            let e = RTCRtpEncodingParameters()
            e.maxBitrateBps = NSNumber(value: path == "udp" ? 1_800_000 : 1_200_000); e.maxFramerate = NSNumber(value: path == "udp" ? 15 : 8)
            si.sendEncodings = [e]
            scr = screen.flatMap { pc.addTransceiver(with: $0, init: si) } ?? pc.addTransceiver(of: .video, init: si)
            if let s = scr?.sender { cryptSender(s, keyIndex) }
            addRecv(.video)                                                 // Sprecher
            addRecv(.video)                                                 // Bildschirm
            for _ in 0..<tiles { addRecv(.video) }                          // Kacheln (Last-N)
        }
        return true
    }
    /// Simulcast (NODE.md): TCP-Wege l = 1/4, m = voll; UDP zusaetzlich h (m dann halbe Groesse). Alle Schichten mit
    /// denselben zeitlichen Schichten (T3) und ohne eigene Bildrate - verschiedene Werte je Schicht (frueher l 20 fps/T2,
    /// m 30 fps/T3) liessen libwebrtc bei 720p gar nichts kodieren (HornCamPlan, Fehler 26.10.)
    static func encodings(path: String) -> [RTCRtpEncodingParameters] {
        // Zeitliche Schichten ueber numTemporalLayers: scalabilityMode lehnt libwebrtc vor der Aushandlung ab, solange die
        // Codec-Liste noch nicht auf VP8 festgelegt ist (UNSUPPORTED_OPERATION, Transceiver fehlt dann)
        HornCamPlan.layers(path: path).map { x in
            let e = RTCRtpEncodingParameters()
            e.rid = x.rid; e.scaleResolutionDownBy = NSNumber(value: x.scale); e.maxBitrateBps = NSNumber(value: x.kbit * 1000)
            e.numTemporalLayers = NSNumber(value: HornCamPlan.temporalLayers); e.isActive = true
            return e
        }
    }
    /// 2.5 Akku: Kamera-Schichten an/aus und Bildrate (HornLogic.camActive) - setzt nur bei Aenderung; -> geaendert?
    /// Bildrate gemeinsam fuer alle Schichten (HornCamPlan.maxFps), nie je Schicht verschieden
    @discardableResult
    func tuneCam(on: Set<String>, fps: Int?) -> Bool {
        guard let s = cam?.sender else { return false }
        let p = s.parameters
        var diff = false
        let f = HornCamPlan.maxFps(fps)
        for e in p.encodings {
            guard let rid = e.rid else { continue }
            let a = on.contains(rid)
            if e.isActive != a { e.isActive = a; diff = true }
            if e.maxFramerate?.intValue != f { e.maxFramerate = f.map { NSNumber(value: $0) }; diff = true }
        }
        if diff { s.parameters = p }
        return diff
    }
    private func addRecv(_ t: RTCRtpMediaType) {
        guard let pc else { return }
        let i = RTCRtpTransceiverInit(); i.direction = .recvOnly
        guard let x = pc.addTransceiver(of: t, init: i) else { return }
        recv.append(x); kinds.append(t == .audio ? "audio" : "video")
        cryptReceiver(recv.count - 1, dev: Self.none)                      // vor dem ersten Rahmen: ohne Schluessel verwerfen
    }
    private func cryptSender(_ s: RTCRtpSender, _ kid: Int) {
        guard let c = HornCryptor.sender(factory, s, device: me, provider: provider) else { return }
        c.keyIndex = Int32(kid); c.enabled = true; c.delegate = self
        senders.append(c)
    }
    private func cryptReceiver(_ i: Int, dev: String) {
        guard i < recv.count, let c = HornCryptor.receiver(factory, recv[i].receiver, from: dev, provider: provider) else { return }
        c.enabled = true; c.delegate = self
        rx[i] = (dev, c)
    }
    /// eigener Schluessel: welcher Platz gesendet wird
    func setKeyIndex(_ kid: Int) { for c in senders { c.keyIndex = Int32(kid) } }
    /// Empfangsplaetze ihren Besitzern zuordnen (Geraet je Platz; nil = keiner)
    func bind(_ owner: (Int) -> String?) {
        for i in recv.indices {
            let want = owner(i) ?? Self.none
            if rx[i]?.dev != want { cryptReceiver(i, dev: want) }
        }
    }
    func ownerOfCryptor(_ i: Int) -> String? { rx[i].flatMap { $0.dev == Self.none ? nil : $0.dev } }

    // MARK: Aushandlung

    func offer() async throws -> String {
        guard let pc else { throw HornNet.HTTPError(status: 0, message: "pc") }
        let c = RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: ["DtlsSrtpKeyAgreement": "true"])
        let o: RTCSessionDescription = try await withCheckedThrowingContinuation { k in
            pc.offer(for: c) { d, e in if let d { k.resume(returning: d) } else { k.resume(throwing: e ?? HornNet.HTTPError(status: 0, message: "offer")) } }
        }
        try await withCheckedThrowingContinuation { (k: CheckedContinuation<Void, Error>) in
            pc.setLocalDescription(o) { e in if let e { k.resume(throwing: e) } else { k.resume() } }
        }
        return pc.localDescription?.sdp ?? o.sdp
    }
    func body(token: String, offer: String, mobile: Bool) -> [String: Any] {
        ["token": token, "path": path, "offer": offer, "audio": role != "video" ? Self.audioSlots : 0,
         "speaker": role != "audio", "screen": role != "audio", "tiles": role != "audio" ? tiles : 0,
         "device": mobile ? "mobile" : "desktop", "screen_mid": scr.map { $0.mid } ?? ""]
    }
    func answer(_ sdp: String) async throws {
        guard let pc else { throw HornNet.HTTPError(status: 0, message: "pc") }
        try await withCheckedThrowingContinuation { (k: CheckedContinuation<Void, Error>) in
            pc.setRemoteDescription(RTCSessionDescription(type: .answer, sdp: sdp)) { e in if let e { k.resume(throwing: e) } else { k.resume() } }
        }
    }
    func close() {
        events?.close(); events = nil
        onState = nil; onCryptor = nil
        for (_, x) in rx { x.c.enabled = true }                            // nie setEnabled(false): FrameCryptor reichte Klartext durch
        pc?.close(); pc = nil
        rx = [:]; senders = []
    }

    // MARK: Spuren, Pegel, wer liefert gerade

    func track(_ i: Int) -> RTCMediaStreamTrack? { i < recv.count ? recv[i].receiver.track : nil }
    var audioRecv: [Int] { kinds.indices.filter { kinds[$0] == "audio" } }
    /// Abfrage alle 0,5 s: liefert der Platz gerade Rahmen (RTP-Zeitstempel bewegt sich)?
    func poll(now: Double) {
        for i in recv.indices {
            let src = recv[i].receiver.sources
            if let s = src.first(where: { $0.sourceType == .SSRC }) ?? src.first {
                if lastRtp[i] != s.rtpTimestamp { lastRtp[i] = s.rtpTimestamp; liveAt[i] = now; if liveSince[i] == nil { liveSince[i] = now } }
            }
            if now - (liveAt[i] ?? -.infinity) > 700 { liveSince[i] = nil }
            if let c = src.filter({ $0.sourceType == .CSRC }).max(by: { $0.timestampUs < $1.timestampUs }) {
                if csrcAt[i]?.csrc != c.sourceId { csrcAt[i] = (c.sourceId, now) }
            }
        }
    }
    /// Bild: liefert seit hoechstens 700 ms
    func live(_ i: Int, now: Double) -> Bool { now - (liveAt[i] ?? -.infinity) < 700 }
    /// Ton: liefert seit mindestens 1 s ohne Aussetzer (erst dann die alte Verbindung fuer diesen Absender stumm)
    func steady(_ i: Int, now: Double) -> Bool { liveSince[i].map { now - $0 >= 1000 } ?? false }
    func anyLive(now: Double) -> Bool { recv.indices.contains { live($0, now: now) } }
    /// tatsaechlicher Absender (CSRC der zuletzt angekommenen Rahmen; der Knoten wechselt die Quelle erst am Schluesselbild)
    func actualCSRC(_ i: Int) -> UInt32? { csrcAt[i]?.csrc }
    func level(_ i: Int) -> Double? {
        let src = i < recv.count ? recv[i].receiver.sources : []
        return (src.first { $0.sourceType == .SSRC } ?? src.first)?.audioLevel?.doubleValue
    }
}

extension HornLeg: RTCPeerConnectionDelegate {
    nonisolated func peerConnection(_ pc: RTCPeerConnection, didChange stateChanged: RTCSignalingState) {}
    nonisolated func peerConnection(_ pc: RTCPeerConnection, didAdd stream: RTCMediaStream) {}
    nonisolated func peerConnection(_ pc: RTCPeerConnection, didRemove stream: RTCMediaStream) {}
    nonisolated func peerConnectionShouldNegotiate(_ pc: RTCPeerConnection) {}
    nonisolated func peerConnection(_ pc: RTCPeerConnection, didChange newState: RTCIceConnectionState) {}
    nonisolated func peerConnection(_ pc: RTCPeerConnection, didChange newState: RTCIceGatheringState) {}
    nonisolated func peerConnection(_ pc: RTCPeerConnection, didGenerate candidate: RTCIceCandidate) {}   // Knoten ist ICE-lite: kein Trickle
    nonisolated func peerConnection(_ pc: RTCPeerConnection, didRemove candidates: [RTCIceCandidate]) {}
    nonisolated func peerConnection(_ pc: RTCPeerConnection, didOpen dataChannel: RTCDataChannel) {}
    nonisolated func peerConnection(_ pc: RTCPeerConnection, didChange newState: RTCPeerConnectionState) {
        Task { @MainActor [weak self] in
            guard let self, self.pc === pc else { return }
            self.state = newState
            self.onState?(newState)
        }
    }
}

extension HornLeg: RTCFrameCryptorDelegate {
    nonisolated func frameCryptor(_ frameCryptor: RTCFrameCryptor, didStateChangeWithParticipantId participantId: String, with stateChanged: RTCFrameCryptorState) {
        guard participantId != HornLeg.none else { return }
        let ok: Bool?
        switch stateChanged {
        case .ok, .keyRatcheted: ok = true
        case .missingKey, .decryptionFailed: ok = false
        default: ok = nil
        }
        guard let ok else { return }
        Task { @MainActor [weak self] in guard let self, participantId != self.me else { return }; self.onCryptor?(participantId, ok) }
    }
}
