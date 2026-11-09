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
    // Hand-Schluessel auf iPhone UND iPad; der Server hat ihn dem iPad zugeordnet (/app/resume). Neuanmeldung am iPhone
    // (eigener Geraete-Schluessel bekannt): nur die alte iPhone-Anmeldung loeschen, nie die des iPads
    let ipad = D(id: 5, prefix: "hkd_IPADxx", keyID: 15, keyPrefix: "hk_OLDxyz", current: false)
    let iphoneOld = D(id: 6, prefix: "hkd_ALTxyz", keyID: nil, keyPrefix: nil, current: false)
    kp = AppKeyCleanup.plan(devices: [ipad, iphoneOld, curD], keys: [], oldToken: oldTok, oldDevice: oldDev, newKey: newTok)
    check(kp.deleteDevices == [6] && kp.use == .new && kp.deleteKey == nil, "geteilter Schluessel: iPad bleibt angemeldet: \(kp)")
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

/// Widget-Nacharbeit 6: kein neuer Geraete-Schluessel je Start, wenn der Schluesselbund ihn nicht haelt (gestaffelt 1 h / 24 h)
func deviceKeyMintTests() {
    typealias M = DeviceKeyMint
    let o = "https://saga.example", t0 = Date(timeIntervalSince1970: 1_800_000_000)
    check(M.allowed(marker: nil, origin: o, now: t0) && !M.allowed(marker: nil, origin: nil, now: t0) && !M.allowed(marker: nil, origin: "", now: t0), "ohne Marke erlaubt, ohne Server nie")
    let m1 = M.after(stored: false, origin: o, now: t0)
    check(m1 != nil && M.count(marker: m1, origin: o) == 1 && M.after(stored: true, previous: m1, origin: o, now: t0) == nil && M.after(stored: false, origin: nil, now: t0) == nil, "Marke nur, wenn nicht gespeichert; Erfolg setzt zurueck")
    // 1. Fehler: Starts innerhalb der Stunde holen keinen neuen, danach ein Versuch
    let starts = (1...10).map { t0.addingTimeInterval(Double($0) * 5 * 60) }
    check(starts.allSatisfy { !M.allowed(marker: m1, origin: o, now: $0) }, "10 Starts in 50 min holen keinen neuen Schluessel")
    check(M.allowed(marker: m1, origin: o, now: t0.addingTimeInterval(M.retryFirst)), "nach dem 1. Fehler: nach 1 h ein neuer Versuch")
    // 2. Fehler in Folge: 24 h
    let t1 = t0.addingTimeInterval(M.retryFirst)
    let m2 = M.after(stored: false, previous: m1, origin: o, now: t1)
    check(M.count(marker: m2, origin: o) == 2 && !M.allowed(marker: m2, origin: o, now: t1.addingTimeInterval(M.retryFirst))
          && !M.allowed(marker: m2, origin: o, now: t1.addingTimeInterval(M.retry - 60)) && M.allowed(marker: m2, origin: o, now: t1.addingTimeInterval(M.retry)), "ab dem 2. Fehler in Folge 24 h")
    let m3 = M.after(stored: false, previous: m2, origin: o, now: t1)
    check(M.count(marker: m3, origin: o) == 3 && M.wait(count: 3) == M.retry, "Zaehler steigt weiter, bleibt bei 24 h")
    check(M.count(marker: M.after(stored: false, previous: m2, origin: "https://other.example", now: t1), origin: "https://other.example") == 1, "anderer Server zaehlt neu")
    check(M.allowed(marker: m1, origin: "https://other.example", now: t0), "Marke eines anderen Servers zaehlt nicht")
    check(M.allowed(marker: m2, origin: o, now: t1.addingTimeInterval(-60)), "Uhr zurueckgestellt: wieder erlaubt")
    // wie aus UserDefaults gelesen (NSNumber); alte Marke ohne Zaehler = 1 Fehler
    let ud: [String: Any] = ["origin": o, "at": NSNumber(value: t0.timeIntervalSince1970), "n": NSNumber(value: 2)]
    check(!M.allowed(marker: ud, origin: o, now: t0.addingTimeInterval(2 * 3600)) && M.count(marker: ["origin": o, "at": 1.0], origin: o) == 1
          && M.allowed(marker: ["origin": o], origin: o, now: t0), "Marke aus den Einstellungen; ohne Zaehler = 1; kaputte Marke sperrt nicht")
}
