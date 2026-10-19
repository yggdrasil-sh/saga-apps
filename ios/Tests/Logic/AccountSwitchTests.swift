import Foundation

// Neuanmeldung ohne Abmelden (Sitzung abgelaufen) - AccountSwitchLogic.swift:
// anderes Konto -> sofort ausblenden und alles des alten Kontos verwerfen; gleiches Konto -> nichts verwerfen;
// laufender Abgleich wird nicht mit dem alten Stand wiederholt -> genau ein Abruf danach.
func accountSwitchTests() {
    typealias O = AccountSwitch.Owner
    let s = "https://saga.example", t = "https://andere.example"
    // Urteil
    check(AccountSwitch.verdict(before: O(origin: s, account: "anna"), after: O(origin: s, account: "anna")) == .same, "gleiches Konto")
    check(AccountSwitch.verdict(before: O(origin: s, account: "anna"), after: O(origin: s, account: "bert")) == .other, "anderes Konto")
    check(AccountSwitch.verdict(before: O(origin: s, account: "anna"), after: O(origin: t, account: "anna")) == .other, "anderer Server")
    check(AccountSwitch.verdict(before: O(origin: s, account: nil), after: O(origin: s, account: "anna")) == .other, "vorher unbekannt -> verwerfen")
    check(AccountSwitch.verdict(before: O(origin: s, account: "anna"), after: O(origin: s, account: nil)) == .other, "nachher unbekannt (offline) -> verwerfen")
    check(AccountSwitch.verdict(before: O(origin: s, account: ""), after: O(origin: s, account: "")) == .other, "leeres Konto gilt als unbekannt")
    // Bisheriges Konto: Zuordnung, sonst Abgleich-Stand (nur desselben Servers)
    let cur = NoteCursor(value: "c1", origin: s, account: "anna", fullAt: 1)
    check(AccountSwitch.owner(origin: s, known: "bert", cursor: cur) == O(origin: s, account: "bert"), "Zuordnung hat Vorrang")
    check(AccountSwitch.owner(origin: s, known: nil, cursor: cur) == O(origin: s, account: "anna"), "sonst aus dem Stand")
    check(AccountSwitch.owner(origin: t, known: nil, cursor: cur) == O(origin: t, account: nil), "Stand eines anderen Servers zaehlt nicht")
    check(AccountSwitch.owner(origin: s, known: nil, cursor: nil) == O(origin: s, account: nil), "nichts bekannt")
    // Plan: ohne Abmelden sofort ausblenden; danach gleiches Konto zeigen, anderes verwerfen
    check(AccountSwitch.before(loggedOut: false).hideNow, "Kontowechsel: sofort ausblenden (vor jeder weiteren Anfrage)")
    check(!AccountSwitch.before(loggedOut: true).hideNow, "abgemeldet: nichts mehr zu zeigen")
    let other = AccountSwitch.after(loggedOut: false, .other), same = AccountSwitch.after(loggedOut: false, .same)
    check(other.wipe && !other.restore, "anderes Konto: verwerfen, nicht wieder zeigen")
    check(same.restore && !same.wipe, "gleiches Konto: Zwischenspeicher/Stand behalten")
    check(!AccountSwitch.after(loggedOut: true, .other).wipe, "nach logout nicht doppelt verwerfen")

    // Ablauf nachgestellt (wie Store.doRefresh/send/adoptServer): Ergebnis = Liste der Abrufe (since, nil = ganz)
    func run(_ v: AccountSwitch.Verdict, cursor: String?) -> (fetches: [String?], shownOld: [Bool]) {
        var gate = RefreshGate(), fetches: [String?] = [], shown: [Bool] = []
        var c = cursor, visibleOld = true
        let started = gate.epoch                          // Abgleich laeuft mit altem Stand (Antwort: 401)
        gate.beginAdopt()                                 // Kopplung/Anmeldung kommt zurueck
        let plan0 = AccountSwitch.before(loggedOut: false)
        if plan0.hideNow { visibleOld = false }
        shown.append(visibleOld)
        // 401 -> stille Erneuerung (inzwischen mit dem NEUEN Geraete-Schluessel) -> wiederholen?
        if !gate.stale(startedAt: started) { fetches.append(c) }
        // Fenster wird aktiv, Live verbindet, Takt: waehrend der Uebernahme kein Abgleich
        for _ in 0..<3 where gate.canStart { fetches.append(c) }
        let plan = AccountSwitch.after(loggedOut: false, v)
        if plan.wipe { c = nil; visibleOld = false }
        if plan.restore { visibleOld = true }
        shown.append(visibleOld)
        gate.endAdopt()
        if gate.canStart { fetches.append(c) }            // pair(): genau ein Abgleich danach
        check(gate.stale(startedAt: started), "alter Lauf bleibt ueberholt")
        return (fetches, shown)
    }
    let a = run(.other, cursor: "c-anna")
    check(a.fetches.count == 1 && a.fetches[0] == nil, "anderes Konto: genau EIN voller Abruf (\(a.fetches))")
    check(!a.shownOld.contains(true), "anderes Konto: alte Seiten zu keinem Zeitpunkt sichtbar")
    let b = run(.same, cursor: "c-anna")
    check(b.fetches == ["c-anna"], "gleiches Konto: ein Abgleich mit Stand, kein ganzer Baum (\(b.fetches))")
    check(b.shownOld.last == true, "gleiches Konto: Liste wieder da ohne Neuladen")
    // ohne Neuanmeldung: Lauf bleibt gueltig, wiederholen nach 401 erlaubt (wie bisher)
    var g = RefreshGate(); let e = g.epoch
    check(!g.stale(startedAt: e) && g.canStart, "normaler Lauf: nicht ueberholt")
    g.beginAdopt(); g.endAdopt()
    check(g.canStart && g.stale(startedAt: e) && !g.stale(startedAt: g.epoch), "neuer Lauf nach der Anmeldung gueltig")
}
