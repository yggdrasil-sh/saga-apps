import Foundation

// 2.5 (L7, Vertrag docs/APP-API.md "Anrufe: Erneut klingeln und Nicht dabei"): reine Logik ohne SwiftUI.
// - Abschnitt "Nicht dabei" im Gruppenanruf: Liste `invites` aus dem Anruf-Objekt bzw. Ereignis call.invites mit eigenem
//   Stand (rv) - eine aeltere Liste ueberschreibt keine neuere. "klingelt" laeuft nach since + ring_s auch ohne Ereignis ab
//   (Server-Uhr ueber now). Klick: sofort "klingelt …" (vorlaeufig), bei Fehler zuruecknehmen.
// - Eigenes erneutes Klingeln: Alter ab invite_since (nicht created); ein hier schon erledigter Anruf klingelt wieder,
//   wenn der Server ein NEUES Klingeln meldet (invite_since neuer als das schon behandelte).

enum Invites {
    /// Zustand einer Person, Reihenfolge der Liste = Reihenfolge hier
    enum State: String, CaseIterable, Equatable {
        case ringing, declined, missed, left, none, removed, old
        /// unbekannte Zustaende wie "none"
        init(raw: String?) { self = raw.flatMap(State.init(rawValue:)) ?? .none }
        var order: Int { State.allCases.firstIndex(of: self) ?? 4 }
    }

    struct Entry: Equatable, Identifiable {
        var who: String
        var name: String
        var avatar: String?
        var handle: String?
        var host: String?
        var state: State
        var since: Double?
        var id: String { who }
    }

    /// Eintraege aus `invites` (nil = Feld fehlt: aelterer Server, Abschnitt nicht zeigen)
    static func parse(_ any: Any?) -> [Entry]? {
        guard let list = any as? [Any] else { return nil }
        return list.compactMap { x -> Entry? in
            guard let d = x as? [String: Any], let who = d["who"] as? String, !who.isEmpty else { return nil }
            func s(_ k: String) -> String? { (d[k] as? String).flatMap { $0.isEmpty ? nil : $0 } }
            return Entry(who: who, name: s("name") ?? who, avatar: s("avatar"), handle: s("handle"), host: s("host"),
                         state: State(raw: d["state"] as? String), since: (d["since"] as? NSNumber)?.doubleValue)
        }
    }

    /// Knopf je Zeile
    enum Action: Equatable {
        /// keiner (klingelt schon bzw. entfernt und ich bin nicht Gastgeber)
        case hidden
        /// "Klingeln" (nie geklingelt)
        case ring
        /// "Erneut klingeln"
        case again
        /// deaktiviert: "App zu alt"
        case old
    }
    static func action(_ s: State, host: Bool) -> Action {
        switch s {
        case .ringing: return .hidden
        case .none: return .ring
        case .removed: return host ? .again : .hidden
        case .old: return .old
        case .declined, .missed, .left: return .again
        }
    }

    struct Row: Equatable, Identifiable {
        var entry: Entry
        var state: State
        /// vorlaeufig (Klick, Antwort steht aus)
        var pending: Bool
        var id: String { entry.who }
    }

    /// Stand des Abschnitts fuer einen Anruf
    struct List: Equatable {
        /// nil = noch keine Liste (aelterer Server) - Abschnitt nicht zeigen
        var entries: [Entry]?
        var more = 0
        /// Stand der zuletzt uebernommenen Liste (eigener Zaehler, nicht der von call.roster)
        var rv = 0.0
        /// Server-Uhr minus Geraete-Uhr (Sekunden)
        var skew = 0.0
        var ringS = 45.0
        /// vorlaeufig klingelnd: who -> bis (Geraete-Uhr)
        var pending: [String: Double] = [:]

        var known: Bool { entries != nil }
        /// Abschnitt zeigen? (Liste bekannt und nicht leer)
        var visible: Bool { (entries.map { !$0.isEmpty } ?? false) || (entries != nil && more > 0) }

        /// Liste aus Anruf-Objekt, Ereignis call.invites oder /invite-Antwort uebernehmen - nur, wenn sie nicht aelter ist.
        /// device = Geraete-Uhr beim Empfang. Ergebnis: uebernommen?
        @discardableResult
        mutating func apply(_ d: [String: Any], device: Double) -> Bool {
            guard let list = Invites.parse(d["invites"]) else { return false }
            let r = (d["rv"] as? NSNumber)?.doubleValue ?? 0
            if r > 0 && rv > 0 && r < rv { return false }               // aelterer Stand kam nach einem neueren
            if r > 0 { rv = r }
            if let n = (d["now"] as? NSNumber)?.doubleValue, n > 0 { skew = n - device }
            if let s = (d["ring_s"] as? NSNumber)?.doubleValue, s > 0 { ringS = s }
            entries = list
            more = max(0, (d["invites_more"] as? NSNumber)?.intValue ?? 0)
            return true
        }
        /// Server-Uhr jetzt
        func now(device: Double) -> Double { device + skew }
        /// angezeigter Zustand: vorlaeufig "klingelt", "klingelt" nur innerhalb der Klingeldauer
        func state(_ e: Entry, device: Double) -> State {
            if let p = pending[e.who], p > device { return .ringing }
            if e.state == .ringing, let s = e.since, s + ringS <= now(device: device) { return .missed }
            return e.state
        }
        /// Zeilen in Anzeige-Reihenfolge (Zustand, darin nach Name)
        func rows(device: Double) -> [Row] {
            (entries ?? []).enumerated().map { (i, e) in (i, Row(entry: e, state: state(e, device: device), pending: (pending[e.who] ?? 0) > device)) }
                .sorted { a, b in
                    if a.1.state.order != b.1.state.order { return a.1.state.order < b.1.state.order }
                    let c = a.1.entry.name.localizedCaseInsensitiveCompare(b.1.entry.name)
                    return c == .orderedSame ? a.0 < b.0 : c == .orderedAscending
                }
                .map(\.1)
        }
        /// Anzahl in der Ueberschrift ("Nicht dabei (n)")
        func count() -> Int { (entries?.count ?? 0) + more }
        /// Sekunden bis zum naechsten Ablauf ("klingelt" -> "nicht angenommen" bzw. vorlaeufig zurueck), nil = keiner
        func nextChange(device: Double) -> Double? {
            let n = now(device: device)
            var next = Double.infinity
            for e in entries ?? [] where e.state == .ringing {
                if let s = e.since { let t = s + ringS - n; if t > 0 { next = min(next, t) } }
            }
            for p in pending.values where p > device { next = min(next, p - device) }
            return next.isFinite ? next : nil
        }
        /// Klick: vorlaeufig "klingelt …" (bis die Antwort kommt, hoechstens `hold` s)
        mutating func ringStart(_ who: [String], device: Double, hold: Double = Invites.pendingHold) -> Double {
            let until = device + hold
            for w in who { pending[w] = until }
            return until
        }
        /// Fehler bzw. Antwort: vorlaeufigen Zustand dieses Klicks zuruecknehmen
        mutating func ringEnd(_ who: [String], until: Double) {
            for w in who where pending[w] == until { pending[w] = nil }
        }
    }
    /// So lange gilt ein Klick hoechstens als "klingelt …", wenn keine Antwort kommt
    static let pendingHold = 15.0
    /// /invite: hoechstens so viele Kennungen
    static let ringMax = 50

    /// "Alle erneut klingeln": nur, wenn mehr als eine Person missed/declined/none hat - genau diese (hoechstens 50)
    static func ringAll(_ rows: [Row]) -> [String] {
        let w = rows.filter { [.missed, .declined, .none].contains($0.state) }.map(\.entry.who)
        return w.count > 1 ? Array(w.prefix(ringMax)) : []
    }

    /// Fehler von POST /invite -> Hinweis. 429: fester Text (nil = Aufrufer setzt ihn), sonst Text des Servers
    enum Failure: Equatable { case tooOften, server(String), generic }
    static func failure(code: Int?, body: [String: Any]?) -> Failure? {
        guard let c = code else { return .generic }
        if (200..<300).contains(c) { return nil }
        if c == 429 { return .tooOften }
        if let e = body?["error"] as? String, !e.trimmingCharacters(in: .whitespaces).isEmpty { return .server(e) }
        return .generic
    }

    // MARK: Eigenes Klingeln (Angerufene)

    /// Alter des eigenen Klingelns in Sekunden (Server-Uhr): ab invite_since, aeltere Server ab created
    static func ringAge(now: Double?, device: Double, inviteSince: Double?, created: Double?) -> Double {
        let n = now ?? device
        return n - (inviteSince ?? created ?? n)
    }
    /// Erneut klingeln, obwohl der Anruf hier schon erledigt war (abgelehnt, verpasst, verlassen)? Nur mit invite_since
    /// (neuere Server), nur fuer ein NEUES Klingeln (nach dem schon behandelten `handled`), invite "ringing", kein eigenes
    /// Geraet im Anruf, nicht beendet, Restdauer > 0. Ergebnis: Restdauer in Sekunden bzw. nil
    static func ringAgain(invite: String?, mine: Int, state: String?, inviteSince: Double?, now: Double?, device: Double,
                          ringS: Double, handled: Double?) -> Double? {
        guard let since = inviteSince, invite == "ringing", mine == 0, state != "ended" else { return nil }
        if let h = handled, since <= h + 0.0005 { return nil }               // dieses Klingeln war hier schon
        let left = since + ringS - (now ?? device)
        return left > 0 ? left : nil
    }
}
