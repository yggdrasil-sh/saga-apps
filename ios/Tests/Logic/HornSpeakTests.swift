import Foundation

// Bugs-DB 2457 (Fund aus 2125): Sprechring ueber den Knoten auf dem iPhone - Pegelauswertung HornLogic.SpeakLevels (Schwelle 0,03,
// Nachlauf 600 ms, stumm sofort aus, Rueckfall auf den Sprecherplatz nur ohne je gesehenen Pegel) und der eigene Pegel
// (HornLogic.selfLevel: veraltet = 0). Takt wie hornTick: 500 ms, Zeit in ms.

func hornSpeakTests() {
    typealias S = HornLogic.SpeakLevels
    let A = "dev-a", B = "dev-b"

    // Schwelle und Abklingen
    var s = S()
    check(s.update(levels: [A: 0.02], muted: [], spk: A, lvSeen: true, now: 0).isEmpty, "2457: Pegel 0,02 (unter der Schwelle) - kein Ring")
    check(s.update(levels: [A: 0.5], muted: [], spk: A, lvSeen: true, now: 500) == [A], "2457: Pegel 0,5 - Ring an A")
    check(s.update(levels: [A: 0.001], muted: [], spk: A, lvSeen: true, now: 1000) == [A], "2457: Sprechpause - 500 ms Nachlauf noch an")
    check(s.update(levels: [A: 0.001], muted: [], spk: A, lvSeen: true, now: 1500).isEmpty, "2457: Sprechpause - nach 1000 ms aus (Nachlauf 600 ms)")
    check(s.update(levels: [A: 0.001], muted: [], spk: A, lvSeen: true, now: 6000).isEmpty, "2457: lange Pause - bleibt aus")

    // Kern des Funds: Pegel gesehen, dann ein Takt ohne Pegel (Platz ohne Rahmen: DTX-Pause, Gegenueber stumm) ->
    // frueher Rueckfall auf den Sprecherplatz = Ring dauerhaft an
    var p = S()
    p.update(levels: [A: 0.4], muted: [], spk: A, lvSeen: true, now: 0)
    var lit = 0
    for k in 1...20 { if !p.update(levels: [:], muted: [], spk: A, lvSeen: true, now: Double(k) * 500).isEmpty { lit += 1 } }
    check(lit == 1, "2457: Pegel einmal gesehen, dann 10 s ohne Pegel - nur der Nachlauf (1 Takt), kein Dauerleuchten am Sprecherplatz (\(lit) Takte an)")
    // Gegenprobe mit der alten Regel (HornCall.hornLevels bis c941ff8): Ring in allen 20 Takten
    var old: [String: Double] = [:], oldLit = 0
    func oldRule(_ lv: [String: Double], spk: String?, now: Double) -> Set<String> {
        var sp = Set<String>()
        for (d, l) in lv where l > 0.03 { old[d] = now }
        for (d, t) in old where now - t < 600 { sp.insert(d) }
        if sp.isEmpty, lv.isEmpty, let s = spk { sp.insert(s) }
        return sp
    }
    _ = oldRule([A: 0.4], spk: A, now: 0)
    for k in 1...20 { if !oldRule([:], spk: A, now: Double(k) * 500).isEmpty { oldLit += 1 } }
    check(oldLit == 20, "2457: Gegenprobe alte Regel - Ring in allen 20 Takten der Pause (\(oldLit))")

    // Knoten ohne Pegel (nie gesehen): Sprecherplatz wie bisher, aber nicht bei stummem Sprecherplatz
    var n = S()
    check(n.update(levels: [:], muted: [], spk: B, lvSeen: false, now: 0) == [B], "2457: Knoten ohne Pegel - Ring am Sprecherplatz (wie Web lvSeen false)")
    check(n.update(levels: [:], muted: [B], spk: B, lvSeen: false, now: 500).isEmpty, "2457: Knoten ohne Pegel, Sprecherplatz stumm - kein Ring")
    check(n.update(levels: [:], muted: [], spk: nil, lvSeen: false, now: 1000).isEmpty, "2457: ohne Pegel und ohne Sprecherplatz - kein Ring")

    // Mikro aus: sofort aus (kein Nachlauf), laut aber stumm zaehlt nicht
    var m = S()
    m.update(levels: [A: 0.5, B: 0.5], muted: [], spk: A, lvSeen: true, now: 0)
    check(m.update(levels: [A: 0.5, B: 0.5], muted: [A], spk: A, lvSeen: true, now: 500) == [B], "2457: A schaltet stumm - A sofort aus, B bleibt")

    // Meeting-Hysterese: Kandidat Sprecherplatz nur ohne je gesehenen Pegel
    check(S.meetLevels([:], spk: A, lvSeen: true).isEmpty, "2457: Meeting - Pegel gesehen, Takt ohne Pegel: kein erfundener Sprecher")
    check(S.meetLevels([:], spk: A, lvSeen: false) == [A: 0.1], "2457: Meeting - Knoten ohne Pegel: Sprecherplatz als Kandidat")
    check(S.meetLevels([B: 0.2], spk: A, lvSeen: false) == [B: 0.2], "2457: Meeting - mit Pegel kein Rueckfall")

    // Eigener Pegel: frisch gilt, veraltet (frueher nur beim Knotenwechsel gemessen) = 0, Mikro aus = 0
    check(HornLogic.selfLevel(0.4, at: 10_000, now: 10_500, mic: true) == 0.4, "2457: eigener Pegel frisch (500 ms) gilt")
    check(HornLogic.selfLevel(0.4, at: 10_000, now: 12_000, mic: true) == 0, "2457: eigener Pegel veraltet (2 s) - zaehlt nicht mehr")
    check(HornLogic.selfLevel(0.4, at: 0, now: 500, mic: true) == 0, "2457: eigener Pegel nie gemessen - 0")
    check(HornLogic.selfLevel(0.4, at: 10_000, now: 10_100, mic: false) == 0, "2457: Mikro aus - 0")
    // ... und mit der Meeting-Hysterese (Sekunden): letzte Messung bei 0,5 s, danach keine mehr -> eigener Ring geht aus
    var sp = Meeting.Speakers()
    sp.update(["me": 0.4], me: "me", now: 0); sp.update(["me": 0.4], me: "me", now: 0.5)
    check(sp.speaking.contains("me"), "2457: eigene Stimme - Ring an")
    for t in [2.0, 2.5, 3.0] { sp.update(["me": HornLogic.selfLevel(0.4, at: 500, now: t * 1000, mic: true)], me: "me", now: t) }
    check(!sp.speaking.contains("me"), "2457: danach keine Messung mehr - eigener Ring aus (frueher blieb der alte Wert stehen)")
}
