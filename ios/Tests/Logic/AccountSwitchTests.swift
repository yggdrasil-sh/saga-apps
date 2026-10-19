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
    check(AccountSwitch.verdict(before: O(origin: s, account: nil), after: O(origin: s, account: "anna")) == .unknown, "vorher unbekannt")
    check(AccountSwitch.verdict(before: O(origin: s, account: "anna"), after: O(origin: s, account: nil)) == .unknown, "nachher unbekannt (offline)")
    check(AccountSwitch.verdict(before: O(origin: s, account: ""), after: O(origin: s, account: "")) == .unknown, "leeres Konto gilt als unbekannt")
    check(AccountSwitch.verdict(before: O(origin: s, account: nil), after: O(origin: t, account: nil)) == .other, "anderer Server: sicher anders")
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
    let unk = AccountSwitch.after(loggedOut: false, .unknown)
    check(unk.wipe && !unk.restore && unk.keepOpenPages, "unbekannt: Liste weg (Datenschutz), offene Seite behaelt ungesicherten Text")
    check(!other.keepOpenPages && !same.keepOpenPages, "nur bei unbekanntem Konto offene Seiten behalten")

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

    // Zaehler: zwei gleichzeitige Anmeldungen (zwei Fenster) - frei erst nach der zweiten
    var g2 = RefreshGate(); let e0 = g2.epoch
    g2.beginAdopt(); g2.beginAdopt(); g2.endAdopt()
    check(!g2.canStart && g2.stale(startedAt: g2.epoch), "eine von zwei Anmeldungen fertig: weiter gesperrt")
    g2.endAdopt()
    check(g2.canStart && g2.stale(startedAt: e0) && !g2.stale(startedAt: g2.epoch), "beide fertig: frei")
    g2.endAdopt()
    check(g2.active == 0 && g2.canStart, "ueberzaehliges Ende: nicht negativ")

    // App-Schluessel beim gleichen Konto (AppKeyCleanup): nichts sammelt sich an
    typealias D = AppKeyCleanup.Device; typealias K = AppKeyCleanup.Key
    let oldTok = "hk_OLDxyz" + "rest", newTok = "hk_NEWabc" + "rest", oldDev = "hkd_ALTxyz" + "rest"
    let curD = D(id: 9, prefix: "hkd_NEUabc", keyID: 19, keyPrefix: "hk_NEWabc", current: true)
    // alte Geraete-Anmeldung (Schluessel unlesbar, BUG-104) noch da: ueber den Schluessel finden und loeschen
    let devs = [D(id: 3, prefix: "hkd_ALTxyz", keyID: 13, keyPrefix: "hk_OLDxyz", current: false), D(id: 4, prefix: "hkd_ANDERS", keyID: 14, keyPrefix: "hk_FREMDx", current: false), curD]
    var kp = AppKeyCleanup.plan(devices: devs, keys: [], oldToken: oldTok, oldDevice: nil, newKey: newTok)
    check(kp.deleteDevices == [3] && kp.use == .new && kp.deleteKey == nil, "altes Geraet (ueber Schluessel) loeschen, neuen nehmen: \(kp)")
    kp = AppKeyCleanup.plan(devices: devs, keys: [], oldToken: nil, oldDevice: oldDev, newKey: newTok)
    check(kp.deleteDevices == [3] && kp.use == .new, "altes Geraet (ueber Geraete-Schluessel) loeschen")
    check(!kp.deleteDevices.contains(4) && !kp.deleteDevices.contains(9), "andere Geraete und das aktuelle bleiben")
    // alter Schluessel von Hand angelegt (keinem Geraet zugeordnet): weiter benutzen, neuen loeschen
    kp = AppKeyCleanup.plan(devices: [curD], keys: [K(id: 7, prefix: "hk_OLDxyz")], oldToken: oldTok, oldDevice: nil, newKey: newTok)
    check(kp.use == .old && kp.deleteKey == 19 && kp.deleteDevices.isEmpty, "eigener Schluessel bleibt, ueberzaehliger neuer weg: \(kp)")
    // alter Schluessel ungueltig (Geraet war entfernt): neuen nehmen; keiner ausgestellt: anlegen
    kp = AppKeyCleanup.plan(devices: [curD], keys: [], oldToken: oldTok, oldDevice: nil, newKey: newTok)
    check(kp.use == .new && kp.deleteDevices.isEmpty && kp.deleteKey == nil, "alter weg: neuer")
    kp = AppKeyCleanup.plan(devices: [D(id: 9, prefix: "hkd_NEUabc", keyID: nil, keyPrefix: nil, current: true)], keys: [], oldToken: oldTok, oldDevice: nil, newKey: nil)
    check(kp.use == .mint, "Grenze erreicht und alter weg: Schluessel anlegen")
    check(AppKeyCleanup.plan(devices: [curD], keys: [], oldToken: nil, oldDevice: nil, newKey: nil).use == .none, "nichts da, nichts ausgestellt")
    // Simulation: 12 Neuanmeldungen mit unlesbarem Geraete-Schluessel -> Anzahl Geraete/Schluessel bleibt konstant
    var sdevs: [D] = [D(id: 1, prefix: "hkd_d00001", keyID: 101, keyPrefix: "hk_k00001", current: false)]
    var token = "hk_k00001" + "x", next = 2
    for _ in 0..<12 {
        let nk = String(format: "hk_k%05d", next) + "x"
        let nd = D(id: next, prefix: String(format: "hkd_d%05d", next), keyID: 100 + next, keyPrefix: String(nk.prefix(9)), current: true)
        let all = sdevs.map { var d = $0; d.current = false; return d } + [nd]
        let pl = AppKeyCleanup.plan(devices: all, keys: [], oldToken: token, oldDevice: nil, newKey: nk)
        sdevs = all.filter { !pl.deleteDevices.contains($0.id) }
        if pl.use == .new { token = nk }
        next += 1
    }
    check(sdevs.count == 1 && token.hasPrefix(String(format: "hk_k%05d", next - 1)), "12 Neuanmeldungen: 1 Geraet, aktueller Schluessel (\(sdevs.count))")
    let js = #"{"devices":[{"id":3,"name":"x","prefix":"hkd_ALTxyz","current":false,"key":{"id":13,"prefix":"hk_OLDxyz"}},{"id":9,"prefix":"hkd_NEUabc","current":true,"key":null}],"keys":[{"id":7,"prefix":"hk_OLDxyz","name":"m"}]}"#
    let pr = AppKeyCleanup.parse(Data(js.utf8))
    check(pr?.devices.count == 2 && pr?.devices[0].keyID == 13 && pr?.devices[1].current == true && pr?.devices[1].keyID == nil && pr?.keys == [K(id: 7, prefix: "hk_OLDxyz")], "Antwort /api/settings/apps lesen")
    check(AppKeyCleanup.parse(Data("[]".utf8)) == nil, "alter Server (Liste statt Objekt): nil")
}
