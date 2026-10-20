import Foundation
import WebRTC

// Gjallarhorn (Saga 2.5): FrameCryptor von webrtc-sdk mit den Einstellungen aus docs/HORN-E2E.md §6.
// Noch nicht im Anrufablauf verwendet (Knoten-Beine und Verteilung kommen mit den naechsten Paketen); geprueft von
// Tests/Horn/HornE2EVectorsTests.swift (HKDF gegen die Vektoren, Loopback zweier PeerConnections).
enum HornCryptor {
    /// Ein KeyProvider je Anruf: je Sender ein Schluessel (participantId = Geraete-ID), HKDF mit dem Rahmen-Salz,
    /// FrameCryptor-Ratsche aus (Saga ratscht selbst), ein kaputter Rahmen sperrt nichts, 16 Plaetze, ohne Schluessel
    /// verwerfen, keine unverschluesselten Magic-Bytes.
    static func keyProvider() -> RTCFrameCryptorKeyProvider {
        RTCFrameCryptorKeyProvider(ratchetSalt: HornE2E.frameSalt, ratchetWindowSize: 0, sharedKeyMode: false,
                                   uncryptedMagicBytes: nil, failureTolerance: -1, keyRingSize: Int32(HornE2E.ring),
                                   discardFrameWhenCryptorNotReady: true, keyDerivationAlgorithm: .HKDF)
    }
    /// Vor dem ersten Rahmen anhaengen (Sender: bevor der Track Bilder liefert bzw. vor der Verhandlung)
    static func sender(_ f: RTCPeerConnectionFactory, _ s: RTCRtpSender, device: String, provider: RTCFrameCryptorKeyProvider) -> RTCFrameCryptor? {
        RTCFrameCryptor(factory: f, rtpSender: s, participantId: device, algorithm: .aesGcm, keyProvider: provider)
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
