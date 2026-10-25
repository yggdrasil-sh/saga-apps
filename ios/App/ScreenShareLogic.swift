import Foundation

// Bildschirm teilen (Saga 2.4, Etappe E3, "Bildschirm v2"): reine Logik ohne WebRTC/UI (mit swiftc pruefbar).
// Vertrag: Saga-Server docs/APP-API.md, Abschnitt "Anrufe: Bildschirm v2". Vorbild Web: static/calls.js.
// Betriebsart B (eigene Videospur ueber Neuverhandlung) nur, wenn BEIDE Seiten "reneg" und "screen2" melden und die
// erste Aushandlung fertig ist; sonst A (Bildschirm statt Kamera auf der Kamera-m-Zeile, wie 2.1/2.3 im Web).
// Systemton wird in die vorhandene Tonspur gemischt (keine dritte m-Zeile) - am Mac/iPhone mit dieser WebRTC-Fassung
// nicht moeglich (siehe ScreenCapture.swift), daher screen_audio = false.

enum ScreenShare {
    enum Mode: String, Equatable {
        /// B: eigene m-Zeile (Rolle "screen"), Kamera laeuft gedrosselt weiter
        case track
        /// A: replaceTrack auf dem Kamera-Sender, Kamera pausiert
        case replace
    }
    /// B nur, wenn beide Seiten reneg + screen2 melden und die erste Aushandlung fertig ist
    static func mode(mine: [String], peer: [String]?, established: Bool) -> Mode {
        let both = CallV3.canReneg(mine: mine, peer: peer) && mine.contains("screen2") && peer?.contains("screen2") == true
        return both && established ? .track : .replace
    }

    // MARK: Eigene Rollen (tracks/msid)

    /// eigene m-Zeile: mid (nil = noch nicht ausgehandelt), feste Rolle, Stream-ID (nil = sendet gerade nichts)
    struct Line: Equatable {
        var mid: String?
        var role: String
        var stream: String?
    }
    /// tracks = Rolle je eigener mid (bleibt fest, auch nach dem Ende des Teilens), msid = Stream-ID je mid
    static func roles(_ lines: [Line]) -> CallV3.Roles {
        var r = CallV3.Roles()
        for l in lines {
            guard let m = l.mid, CallV3.validMid(m), r.tracks.count < 8 else { continue }
            r.tracks[m] = l.role
            if let s = l.stream, !s.isEmpty { r.msid[m] = s }
        }
        return r
    }
    /// mid, auf der gerade MEIN Bildschirm laeuft: B = Bildschirm-m-Zeile, A = Kamera-m-Zeile; nicht teilen = nil
    static func screenMid(sharing: Bool, mode: Mode?, screenLine: String?, camLine: String?) -> String? {
        guard sharing, let mode else { return nil }
        return mode == .track ? screenLine : camLine
    }

    // MARK: Bildschirm-m-Zeile finden oder anlegen (hoechstens eine je Verbindung)

    /// Video-m-Zeile der eigenen PeerConnection (Reihenfolge wie die Transceiver)
    struct VideoLine: Equatable {
        var mid: String?
        var isCam: Bool
    }
    /// Wiederverwenden: eigene Bildschirm-m-Zeile aus einem frueheren Teilen, sonst eine, die das Gegenueber als
    /// "screen" angelegt hat; nil = neuen Transceiver anlegen
    static func reuseMid(own: String?, lines: [VideoLine], peerTracks: [String: String]) -> String? {
        if let own, lines.contains(where: { $0.mid == own && !$0.isCam }) { return own }
        return lines.first { l in !l.isCam && l.mid.map { peerTracks[$0] == "screen" } == true }?.mid
    }

    // MARK: Empfang - welche Videospur ist Kamera, welche Bildschirm?

    struct RemoteView: Equatable {
        var cam: String?
        var screen: String?
    }
    /// videoMids = alle Video-m-Zeilen (Reihenfolge der Transceiver), receiving = davon die, auf denen das Gegenueber
    /// gerade sendet. Massgeblich ist state.screen_mid; ohne Angaben (2.1/2.3/E0, Betriebsart A) ist die einzige
    /// Videospur bei screen = true der Bildschirm. Ohne tracks: die erste Video-m-Zeile ist die Kamera.
    static func classify(videoMids: [String], receiving: Set<String>, peerTracks: [String: String],
                         screen: Bool, screenMid: String?) -> RemoteView {
        let camMid = videoMids.first { peerTracks[$0] == "cam" } ?? peerTracks.keys.sorted().first { peerTracks[$0] == "cam" } ?? videoMids.first
        var cam = camMid.flatMap { receiving.contains($0) ? $0 : nil }
        var scr: String?
        if screen {
            if let sm = screenMid {
                if receiving.contains(sm) { scr = sm }
                if scr != nil && scr == cam { cam = nil }                  // A mit screen_mid: Bildschirm auf der Kamera-Zeile
            } else {
                scr = cam; cam = nil                                       // 2.1/2.3/E0: die einzige Videospur
            }
        }
        return RemoteView(cam: cam, screen: scr)
    }
    /// Anzeige: Bildschirm gross (aspect fit), Kamera klein; swap tauscht. Ohne Bildschirm: Kamera gross.
    /// camOn = state.cam des Gegenuebers (Kamera aus: keine kleine Ansicht)
    static func layout(_ v: RemoteView, camOn: Bool, swap: Bool) -> (big: String?, small: String?) {
        guard let s = v.screen else { return (v.cam, nil) }
        let c = camOn ? v.cam : nil
        if swap, let c { return (c, s) }
        return (s, c)
    }
    /// Neues Teilen beginnt wieder mit dem Bildschirm gross
    static func swapAfter(state wasScreen: Bool, isScreen: Bool, swap: Bool) -> Bool { isScreen && !wasScreen ? false : swap }

    // MARK: Aufnahme

    /// Aufnahme hoechstens 1080p (lange Kante 1920), Seitenverhaeltnis bleibt, gerade Werte (NV12)
    static let maxLongEdge = 1920
    static func captureSize(width: Double, height: Double, scale: Double, maxLong: Int = maxLongEdge) -> (width: Int, height: Int) {
        let w = max(2, width * max(scale, 1)), h = max(2, height * max(scale, 1)), m = Double(maxLong)
        func even(_ x: Double) -> Int { max(2, Int(x.rounded(.down)) & ~1) }
        if max(w, h) <= m { return (even(w), even(h)) }
        return w >= h ? (even(m), even(h * m / w)) : (even(w * m / h), even(m))     // lange Kante exakt, keine Rundungsfehler
    }
    /// Mac (2.5-Fix): Nur die Auswahl des Systems (SCContentSharingPicker) gibt ohne die Freigabe "Bildschirm- und
    /// Systemaudioaufnahme" frei. Ein eigener Filter (ganzer Bildschirm ohne Saga-Fenster) und setConfiguration(for:)
    /// verlangen die dauerhafte Freigabe - ohne sie lehnte macOS ab (SCStreamError -3801), legte den Eintrag in den
    /// Systemeinstellungen an und schickte zum Freigeben dorthin; das Einschalten beendet die App samt Anruf.
    /// Daher nur mit vorhandener Freigabe (CGPreflightScreenCaptureAccess, fragt nie nach).
    static func excludeSelf(display: Bool, hasAccess: Bool) -> Bool { display && hasAccess }
    static func pickerConfigPerStream(hasAccess: Bool) -> Bool { hasAccess }
    /// Bildrate der Aufnahme je Weg: 15, ueber Relais TCP 8 (wie maxFramerate des Bildschirm-Senders)
    static func captureFps(path: CallV3.Path?) -> Int { path == .relayTCP ? 8 : 15 }

    // MARK: Hinweis vor dem ersten Teilen (je Geraet abschaltbar)

    static let hintKey = "sagaScreenHintOff"
    static func showHint(_ d: UserDefaults) -> Bool { !d.bool(forKey: hintKey) }
}
