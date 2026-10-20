import Foundation
import WebRTC

// Anrufe 2.4 (E4/E5): eine Verbindung des Raums zu GENAU einem anderen Geraet (Mesh: je Geraetepaar eine
// PeerConnection, pc = sortiertes Paar der device_ids). Alles, was je Verbindung gilt, steht hier: PeerConnection,
// Perfect Negotiation (E0), ICE je Generation, Faehigkeiten und Zustand der Gegenstelle, Bildschirm-Betriebsart und
// stateDue (E3), Bitrate, Pruefung des DTLS-Fingerabdrucks. Den Ablauf steuert CallManager (Calls.swift, CallRoom.swift);
// ein 1:1-Anruf ist der Raum mit genau einer Verbindung.

@MainActor
final class CallLink: NSObject {
    weak var mgr: CallManager?
    /// Geraet der Gegenstelle; nil = Anrufer im 1:1, bevor ein Geraet angenommen hat (Signale an alle Geraete der Person)
    var dev: String?
    /// Person: Benutzername (lokal) bzw. Handle (saga-call/v2, foederiert)
    var who: String
    var name: String
    var avatar: String?
    var devName = ""
    /// Geraet der eigenen Person (Begleitgeraet, Uebernahme): Ton nie abspielen
    var own = false
    /// 2.3: saga-call/v2 - Handles (eigener, Gegenstelle)
    var v2: (me: String, peer: String)?
    /// signierte Geraeteliste der Person (Zertifikat vom Identitaetsschluessel, TOFU wie im Chat)
    var devs: [String: CallPeerDevice] = [:]
    /// wer das erste Angebot gemacht hat: "caller" = ich
    var role = "caller"

    var pc: RTCPeerConnection?
    /// pc-Kennung: legt der Anbietende fest (sortiertes Paar), die Gegenstelle uebernimmt sie
    var pcID: String?
    var fps: [String] = []
    var peerCaps: [String]?
    var neg = CallV3.Negotiator()
    var remoteIce: [CallLogic.Candidate] = []
    var outIce: [CallLogic.Candidate] = []
    var outTask: Task<Void, Never>?
    /// ICE-ufrags frueherer Remote-SDPs (Kandidaten dieser Generationen verwerfen)
    var oldUfrags = Set<String>()
    var remote = CallRemoteState()
    var gotState = false
    var peerTracks: [String: String] = [:]
    var remoteWant = CallV3.Want.large
    var remotePath: CallV3.Path?
    var appliedVideo: CallV3.Limits?
    var appliedAudio: Int?
    var appliedScreen: CallV3.Limits?
    // 2.4 E3 je Verbindung: Betriebsart des Teilens, eigene Bildschirm-m-Zeile, Zustand erst nach dem Angebot senden
    var scrMode: ScreenShare.Mode?
    var scrTr: RTCRtpTransceiver?
    var stateDue = false
    var negPending = false
    /// erstes Angebot/Antwort fertig
    var established = false
    var connected = false
    var verified = false
    var relay = false
    var path: CallV3.Path?
    var restarts = 0
    var reconnecting = false
    var restartTask: Task<Void, Never>?
    var connTask: Task<Void, Never>?
    var remoteVideo: RTCVideoTrack?
    var remoteSide: RTCVideoTrack?
    /// Pegel der empfangenen Tonspur (Sprecher)
    var level = 0.0
    var gone = false
    var swap = false
    var paused = false

    init(mgr: CallManager?, dev: String?, who: String, name: String) {
        self.mgr = mgr; self.dev = dev; self.who = who; self.name = name
        super.init()
    }

    /// Kamera-m-Zeile = erste Video-m-Zeile (2.3 und alle ersten Angebote haben genau eine)
    var camTr: RTCRtpTransceiver? { pc?.transceivers.first { $0.mediaType == .video } }
    var audioTr: RTCRtpTransceiver? { pc?.transceivers.first { $0.mediaType == .audio } }
    /// Signale an: das festgelegte Geraet bzw. (1:1 vor der Antwort) alle Geraete der Person
    var targets: [String] { dev.map { [$0] } ?? Array(devs.keys) }
    func pcFor(_ me: String, _ d: String) -> String { pcID ?? CallV3.pairPC(me, d) }
    func canReneg(_ mine: [String]) -> Bool { CallV3.canReneg(mine: mine, peer: peerCaps) }

    /// Verbindung schliessen (Raum: Geraet hat verlassen bzw. Fehler nur hier)
    func close() {
        gone = true
        outTask?.cancel(); restartTask?.cancel(); connTask?.cancel()
        pc?.close(); pc = nil; scrTr = nil
        remoteVideo = nil; remoteSide = nil
    }
}

// MARK: - WebRTC-Delegat je Verbindung (Rueckrufe kommen auf dem WebRTC-Signalisierungs-Thread)

extension CallLink: RTCPeerConnectionDelegate {
    nonisolated func peerConnection(_ pc: RTCPeerConnection, didChange stateChanged: RTCSignalingState) {}
    nonisolated func peerConnection(_ pc: RTCPeerConnection, didAdd stream: RTCMediaStream) {}
    nonisolated func peerConnection(_ pc: RTCPeerConnection, didRemove stream: RTCMediaStream) {}
    nonisolated func peerConnectionShouldNegotiate(_ pc: RTCPeerConnection) {}
    nonisolated func peerConnection(_ pc: RTCPeerConnection, didChange newState: RTCIceConnectionState) {
        Task { @MainActor [weak self] in guard let self, self.pc === pc else { return }; self.mgr?.iceChanged(self, newState) }
    }
    nonisolated func peerConnection(_ pc: RTCPeerConnection, didChange newState: RTCIceGatheringState) {}
    nonisolated func peerConnection(_ pc: RTCPeerConnection, didRemove candidates: [RTCIceCandidate]) {}
    nonisolated func peerConnection(_ pc: RTCPeerConnection, didOpen dataChannel: RTCDataChannel) {}
    nonisolated func peerConnection(_ pc: RTCPeerConnection, didGenerate candidate: RTCIceCandidate) {
        guard !candidate.sdp.isEmpty else { return }
        var c = CallLogic.Candidate(candidate: candidate.sdp, sdpMid: candidate.sdpMid, sdpMLineIndex: candidate.sdpMLineIndex)
        c.usernameFragment = CallV3.candidateUfrag(c)                      // 2.4: usernameFragment mitsenden
        Task { @MainActor [weak self] in guard let self, self.pc === pc else { return }; self.mgr?.localCandidate(self, c) }
    }
    nonisolated func peerConnection(_ pc: RTCPeerConnection, didChange newState: RTCPeerConnectionState) {
        Task { @MainActor [weak self] in guard let self, self.pc === pc else { return }; self.mgr?.connectionChanged(self, newState) }
    }
    nonisolated func peerConnection(_ pc: RTCPeerConnection, didStartReceivingOn transceiver: RTCRtpTransceiver) {
        Task { @MainActor [weak self] in guard let self, self.pc === pc else { return }; self.mgr?.findRemoteVideo(self) }
    }
}
