import Foundation
import WebRTC

// Gjallarhorn (Saga 2.5): FrameCryptor von webrtc-sdk mit den Einstellungen aus docs/HORN-E2E.md §6.
// Verwendet von HornLeg (HornMedia.swift); geprueft von Tests/Horn/HornE2EVectorsTests.swift (HKDF gegen die Vektoren,
// Loopback zweier PeerConnections, auch mit spaeter gesetzter Spur).
enum HornCryptor {
    /// Ein KeyProvider je Anruf: je Sender ein Schluessel (participantId = Geraete-ID), HKDF mit dem Rahmen-Salz,
    /// FrameCryptor-Ratsche aus (Saga ratscht selbst), ein kaputter Rahmen sperrt nichts, 16 Plaetze, ohne Schluessel
    /// verwerfen, keine unverschluesselten Magic-Bytes.
    static func keyProvider() -> RTCFrameCryptorKeyProvider {
        RTCFrameCryptorKeyProvider(ratchetSalt: HornE2E.frameSalt, ratchetWindowSize: 0, sharedKeyMode: false,
                                   uncryptedMagicBytes: nil, failureTolerance: -1, keyRingSize: Int32(HornE2E.ring),
                                   discardFrameWhenCryptorNotReady: true, keyDerivationAlgorithm: .HKDF)
    }
    /// Vor dem ersten Rahmen anhaengen (Sender: bevor der Track Bilder liefert bzw. vor der Verhandlung).
    /// Fehler 26.10. (iPhone teilt den Bildschirm ueber den Knoten, beim Gegenueber bleibt die Flaeche schwarz):
    /// webrtc-sdk (RTCFrameCryptor.mm, M150) legt fuer einen Sender OHNE Spur keinen Cryptor an (`track() == nullptr`
    /// -> nil). Der Bildschirm-Sender ist beim Aufbau des Beins leer, die Spur kommt erst mit dem Teilen - die Bilder
    /// gingen dann unverschluesselt zum Knoten, und der Empfaenger verwarf sie. Wie Android (HornSession.sender): eine
    /// Platzhalter-Spur der passenden Art nur fuer das Anlegen, danach wieder ab. Der Transformer bleibt am Sender
    /// (RtpSenderBase::SetFrameTransformer) und gilt auch fuer jede spaeter gesetzte Spur.
    static func sender(_ f: RTCPeerConnectionFactory, _ s: RTCRtpSender, kind: RTCRtpMediaType, device: String,
                       provider: RTCFrameCryptorKeyProvider) -> RTCFrameCryptor? {
        guard s.track == nil else {
            return RTCFrameCryptor(factory: f, rtpSender: s, participantId: device, algorithm: .aesGcm, keyProvider: provider)
        }
        s.track = placeholder(f, kind)
        defer { s.track = nil }
        return RTCFrameCryptor(factory: f, rtpSender: s, participantId: device, algorithm: .aesGcm, keyProvider: provider)
    }
    /// Platzhalter-Spur (ohne Quelle, liefert nie Rahmen) - je Aufruf neu, sie haengt nur kurz am Sender
    static func placeholder(_ f: RTCPeerConnectionFactory, _ kind: RTCRtpMediaType) -> RTCMediaStreamTrack {
        if kind == .audio {
            let t = f.audioTrack(with: f.audioSource(with: nil), trackId: "horn-none-a"); t.isEnabled = false
            return t
        }
        return f.videoTrack(with: f.videoSource(), trackId: "horn-none-v")
    }
    static func receiver(_ f: RTCPeerConnectionFactory, _ r: RTCRtpReceiver, from device: String, provider: RTCFrameCryptorKeyProvider) -> RTCFrameCryptor? {
        RTCFrameCryptor(factory: f, rtpReceiver: r, participantId: device, algorithm: .aesGcm, keyProvider: provider)
    }
    /// Empfaenger: gehaltene neueste Generation und die vier folgenden Saga-Ratschenstufen vorab einsetzen
    static func setSlots(_ p: RTCFrameCryptorKeyProvider, device: String, newest n: Int, key: Data) {
        for s in HornE2E.cryptorSlots(newest: n, key: key) { p.setKey(s.key, with: Int32(s.kid), forParticipant: device) }
    }
    /// Abgelaufenen Platz unbrauchbar machen (FrameCryptor kann nicht loeschen)
    static func scrub(_ p: RTCFrameCryptorKeyProvider, device: String, kid: Int) {
        p.setKey(HornE2E.scrubKey(), with: Int32(kid), forParticipant: device)
    }
}
