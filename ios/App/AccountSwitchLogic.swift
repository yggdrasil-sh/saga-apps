import Foundation

/// Neue Anmeldung OHNE vorheriges Abmelden (Sitzung abgelaufen, Anmeldebildschirm, QR-Code): Gehoert sie zu einem
/// anderen Konto (oder Server), darf von der alten Anmeldung nichts mehr zu sehen sein - auch nicht kurz, bis der neue
/// Seitenbaum da ist. Gleiches Konto: nichts verwerfen (kein unnoetiges Neuladen des ganzen Baums).
enum AccountSwitch {
    struct Owner: Equatable { var origin: String?; var account: String? }
    enum Verdict: Equatable { case same, other }

    /// Gleich nur, wenn vorher und nachher sicher bekannt und gleich; unbekannt gilt als anderes Konto (Datenschutz)
    static func verdict(before: Owner, after: Owner) -> Verdict {
        guard let o = before.origin, let a = before.account, !a.isEmpty,
              let o2 = after.origin, let a2 = after.account else { return .other }
        return o == o2 && a == a2 ? .same : .other
    }

    /// Bisheriges Konto: aus der Konto-Zuordnung (Entwuerfe), sonst aus dem Abgleich-Stand der Liste (gehoert zu ihr)
    static func owner(origin: String?, known: String?, cursor: NoteCursor?) -> Owner {
        if let known, !known.isEmpty { return Owner(origin: origin, account: known) }
        if let c = cursor, c.origin == origin, !c.account.isEmpty { return Owner(origin: origin, account: c.account) }
        return Owner(origin: origin, account: nil)
    }

    /// Was nach der Neuanmeldung mit den Daten des bisherigen Kontos geschieht
    struct Plan: Equatable {
        /// sofort (vor jeder weiteren Anfrage) ausblenden: Liste, Geteiltes, Heute
        var hideNow: Bool
        /// ausgeblendeten Stand wieder zeigen (gleiches Konto: kein Neuladen, Abgleich-Stand bleibt)
        var restore: Bool
        /// Liste, Zwischenspeicher, Abgleich-Stand, Systemsuche, Widget-Schluessel des alten Kontos verwerfen
        var wipe: Bool
    }
    /// Vor der Kontopruefung: Wer vorher sauber abgemeldet wurde (anderer Server / Rueckfrage), hat nichts mehr zu zeigen
    static func before(loggedOut: Bool) -> Plan { Plan(hideNow: !loggedOut, restore: false, wipe: false) }
    /// Nach der Kontopruefung (/api/account)
    static func after(loggedOut: Bool, _ v: Verdict) -> Plan {
        if loggedOut { return Plan(hideNow: false, restore: false, wipe: false) }   // logout hat schon alles geleert
        return v == .same ? Plan(hideNow: false, restore: true, wipe: false) : Plan(hideNow: false, restore: false, wipe: true)
    }
}

/// Abgleich und Neuanmeldung: Waehrend eine neue Anmeldung uebernommen wird, startet kein Abgleich. Ein schon laufender
/// wird abgebrochen und nach einer stillen Erneuerung (401 -> Geraete-Schluessel, inzwischen der NEUE) nicht mit dem
/// alten Stand wiederholt - sonst kam der Baum zweimal ganz (verworfene Antwort + danach delta=1).
struct RefreshGate {
    private(set) var adopting = false
    private(set) var epoch = 0
    /// darf ein Abgleich starten?
    var canStart: Bool { !adopting }
    mutating func beginAdopt() { adopting = true; epoch += 1 }
    mutating func endAdopt() { adopting = false }
    /// Anfrage des Abgleichs, gestartet in `startedAt`, ist ueberholt (neue Anmeldung kam dazwischen)
    func stale(startedAt e: Int) -> Bool { adopting || e != epoch }
}
