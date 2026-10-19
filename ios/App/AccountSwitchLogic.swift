import Foundation

/// Neue Anmeldung OHNE vorheriges Abmelden (Sitzung abgelaufen, Anmeldebildschirm, QR-Code): Gehoert sie zu einem
/// anderen Konto (oder Server), darf von der alten Anmeldung nichts mehr zu sehen sein - auch nicht kurz, bis der neue
/// Seitenbaum da ist. Gleiches Konto: nichts verwerfen (kein unnoetiges Neuladen des ganzen Baums).
enum AccountSwitch {
    struct Owner: Equatable { var origin: String?; var account: String? }
    /// unknown: vorher oder nachher nicht feststellbar (kein Konto gemerkt, /api/account faellt aus)
    enum Verdict: Equatable { case same, other, unknown }

    /// Gleich nur, wenn vorher und nachher sicher bekannt und gleich; anders nur, wenn beide sicher bekannt und
    /// verschieden (oder der Server ein anderer ist)
    static func verdict(before: Owner, after: Owner) -> Verdict {
        if let o = before.origin, let o2 = after.origin, o != o2 { return .other }
        guard before.origin != nil, after.origin != nil, let a = before.account, !a.isEmpty,
              let a2 = after.account, !a2.isEmpty else { return .unknown }
        return a == a2 ? .same : .other
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
        /// offene Seiten NICHT als fremd behandeln (Konto unbekannt): ungesicherter Text wird weiter gesendet; gehoert
        /// die Seite doch einem anderen Konto, lehnt der Server ab und es greift der Kopier-Dialog (lostAccess)
        var keepOpenPages = false
    }
    /// Vor der Kontopruefung: Wer vorher sauber abgemeldet wurde (anderer Server / Rueckfrage), hat nichts mehr zu zeigen
    static func before(loggedOut: Bool) -> Plan { Plan(hideNow: !loggedOut, restore: false, wipe: false) }
    /// Nach der Kontopruefung (/api/account)
    static func after(loggedOut: Bool, _ v: Verdict) -> Plan {
        if loggedOut { return Plan(hideNow: false, restore: false, wipe: false) }   // logout hat schon alles geleert
        switch v {
        case .same: return Plan(hideNow: false, restore: true, wipe: false)
        case .other: return Plan(hideNow: false, restore: false, wipe: true)
        case .unknown: return Plan(hideNow: false, restore: false, wipe: true, keepOpenPages: true)   // Liste trotzdem weg (Datenschutz)
        }
    }
}

/// Abgleich und Neuanmeldung: Waehrend eine neue Anmeldung uebernommen wird, startet kein Abgleich. Ein schon laufender
/// wird abgebrochen und nach einer stillen Erneuerung (401 -> Geraete-Schluessel, inzwischen der NEUE) nicht mit dem
/// alten Stand wiederholt - sonst kam der Baum zweimal ganz (verworfene Antwort + danach delta=1).
/// Zaehler: zwei gleichzeitige Anmeldungen (zwei Fenster) - frei erst, wenn beide fertig sind.
struct RefreshGate {
    private(set) var active = 0
    private(set) var epoch = 0
    var adopting: Bool { active > 0 }
    /// darf ein Abgleich starten?
    var canStart: Bool { active == 0 }
    mutating func beginAdopt() { active += 1; epoch += 1 }
    mutating func endAdopt() { active = max(0, active - 1) }
    /// Anfrage des Abgleichs, gestartet in `startedAt`, ist ueberholt (neue Anmeldung kam dazwischen)
    func stale(startedAt e: Int) -> Bool { adopting || e != epoch }
}

/// Neuanmeldung beim GLEICHEN Konto: Die Anmeldung legt eine neue Geraete-Anmeldung samt App-Schluessel an. Die alte
/// (noch gueltig, z. B. Schluesselbund nicht lesbar BUG-104) darf nicht liegen bleiben - sonst ist nach ~10
/// Neuanmeldungen kein App-Schluessel mehr frei (APP_TOKEN_MAX). Grundlage: GET /api/settings/apps.
enum AppKeyCleanup {
    struct Device: Equatable { var id: Int; var prefix: String; var keyID: Int?; var keyPrefix: String?; var current: Bool }
    struct Key: Equatable { var id: Int; var prefix: String }
    enum Use: Equatable { case new, old, mint, none }
    struct Plan: Equatable {
        /// alte Geraete-Anmeldungen dieses Geraets (samt ihrem Schluessel) loeschen
        var deleteDevices: [Int] = []
        /// ueberzaehligen neuen Schluessel loeschen (alter, von Hand angelegter Schluessel bleibt in Gebrauch)
        var deleteKey: Int? = nil
        var use: Use = .none
    }
    static let keyPrefixLen = 9, devicePrefixLen = 10
    /// Antwort von /api/settings/apps lesen (nil: alter Server / Fehler)
    static func parse(_ d: Data) -> (devices: [Device], keys: [Key])? {
        guard let j = (try? JSONSerialization.jsonObject(with: d)) as? [String: Any], let ds = j["devices"] as? [[String: Any]] else { return nil }
        let devs = ds.compactMap { x -> Device? in
            guard let id = x["id"] as? Int else { return nil }
            let k = x["key"] as? [String: Any]
            return Device(id: id, prefix: x["prefix"] as? String ?? "", keyID: k?["id"] as? Int, keyPrefix: k?["prefix"] as? String,
                          current: x["current"] as? Bool ?? false)
        }
        let keys = (j["keys"] as? [[String: Any]] ?? []).compactMap { x -> Key? in
            guard let id = x["id"] as? Int, let p = x["prefix"] as? String else { return nil }
            return Key(id: id, prefix: p)
        }
        return (devs, keys)
    }
    /// `oldToken`/`oldDevice`: App-/Geraete-Schluessel VOR der Neuanmeldung (falls lesbar), `newKey`: neu ausgestellt
    static func plan(devices: [Device], keys: [Key], oldToken: String?, oldDevice: String?, newKey: String?) -> Plan {
        var p = Plan()
        let tp = oldToken.map { String($0.prefix(keyPrefixLen)) }, dp = oldDevice.map { String($0.prefix(devicePrefixLen)) }
        for d in devices where !d.current {
            if (dp != nil && d.prefix == dp) || (tp != nil && d.keyPrefix == tp) { p.deleteDevices.append(d.id) }
        }
        let oldIsDeviceKey = tp != nil && devices.contains { !$0.current && $0.keyPrefix == tp }
        if let tp, !oldIsDeviceKey, keys.contains(where: { $0.prefix == tp }) {
            // alter Schluessel ist ein eigener (von Hand angelegt/keinem Geraet zugeordnet) und gilt noch: weiter benutzen
            p.use = .old
            if let n = newKey { p.deleteKey = devices.first { $0.current && $0.keyPrefix == String(n.prefix(keyPrefixLen)) }?.keyID }
            return p
        }
        // alter Schluessel weg/ungueltig: neuen benutzen bzw. (keiner ausgestellt, z. B. Grenze erreicht) anlegen
        p.use = newKey != nil ? .new : (tp != nil ? .mint : .none)
        return p
    }
}
