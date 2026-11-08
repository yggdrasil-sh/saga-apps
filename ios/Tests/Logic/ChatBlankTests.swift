import Foundation

// 2.5 (leerchat): Waechter gegen eine leere Nachrichtenflaeche (App/ChatBlankLogic.swift)
func chatBlankTests() {
    typealias L = ChatBlankLogic
    // sichtbarer Bereich ausserhalb des Inhalts
    check(!L.outside(offsetY: 0, contentH: 5000, containerH: 700), "oben: im Inhalt")
    check(!L.outside(offsetY: 4300, contentH: 5000, containerH: 700), "unten: im Inhalt")
    check(L.outside(offsetY: 6200, contentH: 5000, containerH: 700), "Offset hinter dem Inhalt")
    check(L.outside(offsetY: -900, contentH: 5000, containerH: 700), "Offset vor dem Inhalt")
    check(L.outside(offsetY: 0, contentH: 0, containerH: 700), "Inhalt ohne Hoehe")
    check(!L.outside(offsetY: 0, contentH: 0, containerH: 0), "noch nicht ausgelegt: kein Befund")
    check(!L.outside(offsetY: -40, contentH: 300, containerH: 700), "kurzer Chat unten verankert (negativer Offset) ist sichtbar")

    check(!L.blank(count: 0, visible: 0, outside: false), "leerer Chat ist kein Fehler")
    check(L.blank(count: 73, visible: 0, outside: false), "Nachrichten da, nichts sichtbar")
    check(L.blank(count: 73, visible: 3, outside: true), "Zeilen gemeldet, aber Bereich ausserhalb")
    check(!L.blank(count: 73, visible: 3, outside: false), "sichtbar")

    // alles gut: keine Massnahme, keine Meldung
    var g = L.Guard()
    check(g.check(count: 73, visible: 0, outside: false, active: true) == .none, "nicht scharf: nichts")
    g.arm(.load)
    check(g.check(count: 73, visible: 5, outside: false, active: true) == .none && !g.armed, "sichtbar: fertig ohne Meldung")

    // leer -> springen -> geheilt
    g.arm(.load)
    check(g.check(count: 73, visible: 0, outside: false, active: true) == .scroll, "leer: erst ans Ende springen")
    check(g.why == "vis", "Befund: keine Zeile sichtbar")
    g.arm(.change)                                                          // Aenderung waehrend der Heilung: nicht zuruecksetzen
    check(g.step == 1 && g.trigger == .load, "laufende Heilung bleibt")
    check(g.check(count: 73, visible: 4, outside: false, active: true) == .report(healed: true, step: 1), "nach dem Sprung sichtbar: geheilt (1)")
    check(!g.armed, "danach aus")

    // leer -> springen -> neu aufbauen -> geheilt
    g.arm(.resume)
    check(g.check(count: 73, visible: 2, outside: true, active: true) == .scroll && g.why == "geo", "Bereich ausserhalb: springen")
    check(g.check(count: 73, visible: 0, outside: false, active: true) == .rebuild, "weiter leer: neu aufbauen")
    check(g.check(count: 73, visible: 6, outside: false, active: true) == .report(healed: true, step: 2), "nach dem Neuaufbau sichtbar: geheilt (2)")

    // nichts hilft -> Meldung nicht geheilt, kein Endlos-Kreis
    g.arm(.load)
    _ = g.check(count: 73, visible: 0, outside: false, active: true)
    _ = g.check(count: 73, visible: 0, outside: false, active: true)
    check(g.check(count: 73, visible: 0, outside: false, active: true) == .report(healed: false, step: 2), "nicht geheilt melden")
    check(g.check(count: 73, visible: 0, outside: false, active: true) == .none, "danach still")

    // Hintergrund: nichts tun, aus
    g.arm(.resume)
    check(g.check(count: 73, visible: 0, outside: false, active: false) == .none && !g.armed, "im Hintergrund: aus")
    // Chat leer geworden
    g.arm(.change)
    check(g.check(count: 0, visible: 0, outside: false, active: true) == .none && !g.armed, "keine Nachrichten: aus")

    // Meldung: nur Programmwoerter, Groessenklassen, ueberlebt die Bereinigung unveraendert
    check(L.bucket(3) == "n_tiny" && L.bucket(10) == "n_small" && L.bucket(73) == "n_mid" && L.bucket(150) == "n_large" && L.bucket(5000) == "n_huge", "Groessenklassen")
    let m = L.message(count: 73, calls: 67, healed: true, step: 1, trigger: .load, why: "vis")
    check(m == "chat_blank healed_scroll on_load why_vis n_mid calls_many", "Meldung: \(m)")
    check(L.message(count: 120, calls: 5, healed: false, step: 2, trigger: .resume, why: "geo") == "chat_blank not_healed on_resume why_geo n_large calls_few", "Meldung nicht geheilt")
    check(L.message(count: 20, calls: 0, healed: true, step: 2, trigger: .change, why: "") == "chat_blank healed_rebuild on_change why_vis n_small calls_few", "Meldung Neuaufbau")
    for msg in [m, L.message(count: 500, calls: 1, healed: false, step: 2, trigger: .resume, why: "geo")] {
        check(ClientLogLogic.scrub(msg) == msg, "Bereinigung laesst die Meldung stehen: \(ClientLogLogic.scrub(msg))")
    }
    let it = ClientLogLogic.Item.make("warn", m, screen: "chat", t: 1000)
    check(it.k == "warn", "Art warn (Server: KINDS)")
}
