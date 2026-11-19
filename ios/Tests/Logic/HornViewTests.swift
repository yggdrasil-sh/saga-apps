import Foundation

// Bugs-DB 2193: Teilen beginnt/endet mitten im Knoten-Anruf. Die Anrufansicht (CallScreen) beobachtet nur den CallManager;
// hornDraw meldet ihm jede Aenderung des Stands, den die Ansicht aus der HornSession liest (HornLogic.ViewKey). Modell:
// die "Ansicht" uebernimmt den Stand nur, wenn neu gezeichnet wird.
func hornViewTests() {
    final class Spur {}
    let camA = Spur(), camB = Spur(), scrB = Spur(), camB2 = Spur()
    var last: HornLogic.ViewKey?
    var shownShare: String?, shownMissing = false, shownVid: [String: ObjectIdentifier] = [:], draws = 0
    // ein hornDraw: Stand berechnen, bei Aenderung neu zeichnen (die Ansicht liest dann share/st/vid neu)
    func draw(share: String?, track: Spur?, vid: [String: Spur], st: [String: HornLogic.PeerState]) {
        let k = HornLogic.ViewKey(share: share, shareTrack: track, vid: vid, st: st)
        guard HornLogic.viewChanged(last, k) else { return }
        last = k; draws += 1
        shownShare = share
        shownMissing = st.contains { $0.value.screen && $0.key != share }   // Kachel "Ueber den Knoten nicht verfuegbar"
        shownVid = k.vid
    }
    let st0: [String: HornLogic.PeerState] = ["a": .init(mic: true, cam: true, screen: false), "b": .init(mic: true, cam: true, screen: false)]
    // im Anruf, niemand teilt
    draw(share: nil, track: nil, vid: ["a": camA, "b": camB], st: st0)
    check(shownShare == nil && draws == 1 && shownVid.count == 2, "Anruf laeuft: zwei Kamerabilder, kein Bildschirm")
    // Takt (hornMon) ohne Aenderung: nicht neu zeichnen
    for _ in 0..<5 { draw(share: nil, track: nil, vid: ["a": camA, "b": camB], st: st0) }
    check(draws == 1, "gleicher Stand: kein Neuzeichnen im Takt")
    // b beginnt zu teilen: erst state (screen:true), dann belegt der Knoten den Bildschirmplatz
    var st1 = st0; st1["b"]?.screen = true
    draw(share: nil, track: nil, vid: ["a": camA, "b": camB], st: st1)
    check(draws == 2 && shownMissing, "state screen:true mitten im Anruf: Ansicht neu (Bildschirm-Kachel erscheint)")
    draw(share: "b", track: scrB, vid: ["a": camA, "b": camB], st: st1)
    check(draws == 3 && shownShare == "b" && !shownMissing, "Bildschirmplatz belegt mitten im Anruf: Bildschirm sichtbar")
    for _ in 0..<5 { draw(share: "b", track: scrB, vid: ["a": camA, "b": camB], st: st1) }
    check(draws == 3, "waehrend des Teilens: kein Neuzeichnen im Takt")
    // Platz zuerst, state danach (umgekehrte Reihenfolge): bleibt sichtbar, sobald beides da ist
    last = nil; draws = 0
    draw(share: nil, track: nil, vid: ["a": camA, "b": camB], st: st0)       // Platz belegt, H.st[b].screen noch false -> scr = nil
    draw(share: "b", track: scrB, vid: ["a": camA, "b": camB], st: st1)
    check(shownShare == "b", "state nach dem Platz: Bildschirm sichtbar")
    // Teilen beenden: state screen:false bzw. Platz frei -> sofort zurueck zur Kamera (kein eingefrorenes Bild)
    draw(share: nil, track: nil, vid: ["a": camA, "b": camB], st: st0)
    check(shownShare == nil && !shownMissing, "Teilen beendet mitten im Anruf: Bildschirm sofort weg")
    // Kamerabild der Teilenden kommt spaeter (anderer Platz bzw. neue Spur): Kachel bekommt das Bild
    draw(share: nil, track: nil, vid: ["a": camA], st: st0)
    draw(share: nil, track: nil, vid: ["a": camA, "b": camB2], st: st0)
    check(shownVid["b"] == ObjectIdentifier(camB2), "Kamerabild kommt nach: Kachel neu gezeichnet")
    // neue Spur auf dem Bildschirmplatz (anderes Bein nach Wechsel): neu zeichnen
    let scrB2 = Spur(); let n = draws
    draw(share: "b", track: scrB, vid: ["a": camA, "b": camB2], st: st1)
    draw(share: "b", track: scrB2, vid: ["a": camA, "b": camB2], st: st1)
    check(draws == n + 2, "andere Bildschirm-Spur: neu zeichnen")
    // nur mic geaendert (Stumm-Zeichen in der Kachel): neu zeichnen
    var st2 = st1; st2["a"]?.mic = false
    draw(share: "b", track: scrB2, vid: ["a": camA, "b": camB2], st: st2)
    check(draws == n + 3, "state mic geaendert: neu zeichnen")
}
