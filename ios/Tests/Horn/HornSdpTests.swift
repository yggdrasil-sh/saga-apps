import XCTest
import WebRTC

// Gjallarhorn P9: Der Knoten setzt auf TCP-/TLS-Beinen fuer Bild die RTP-Kopferweiterung playout-delay (statt
// jitterBufferTarget, das die ObjC-API von webrtc-sdk nicht anbietet). Er kann sie nur setzen, wenn das Angebot der App
// sie aushandelt - die Apps veraendern die SDP nicht. Geprueft wird das Angebot des echten Beins (HornLeg), ohne Knoten.
// Am Mac oeffnet die WebRTC-Fabrik CoreAudio (stilles Audio-Geraet wird dort nicht angenommen): nur im iOS-Simulator.
@MainActor
final class HornSdpTests: XCTestCase {
    static let playoutDelay = "http://www.webrtc.org/experiments/rtp-hdrext/playout-delay"

    override func setUpWithError() throws {
        #if os(macOS)
        throw XCTSkip("Angebot mit WebRTC-Fabrik nur im iOS-Simulator (stilles Audio-Geraet)")
        #endif
    }

    func testOfferKeepsPlayoutDelay() async throws {
        for (role, path) in [("video", "tcp"), ("video", "turn"), ("both", "udp")] {
            let L = HornLeg(role: role, path: path, me: String(repeating: "a1", count: 16), tiles: 4,
                            factory: Loopback.factory, provider: HornCryptor.keyProvider())
            XCTAssertTrue(L.build(turn: nil, audio: nil, camera: nil, screen: nil, keyIndex: 0, sid: "s-test"))
            let sdp = try await L.offer()
            L.pc?.close()
            // jede Bild-m-Zeile traegt die Erweiterung (Kamera, Bildschirm, Empfangsplaetze)
            let sections = sdp.components(separatedBy: "\r\nm=").dropFirst()
            let video = sections.filter { $0.hasPrefix("video") }
            XCTAssertFalse(video.isEmpty, "\(role)/\(path): Bild im Angebot")
            for m in video {
                XCTAssertTrue(m.contains("a=extmap:") && m.contains(Self.playoutDelay), "\(role)/\(path): playout-delay fehlt im Angebot")
            }
        }
    }
}
