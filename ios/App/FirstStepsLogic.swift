import Foundation

// 2.6 (Bugs-DB 2031): "Erste Schritte" und leere Zustaende mit naechstem Schritt - reine Entscheidungen ohne SwiftUI
// (Tests/Logic/FirstStepsTests.swift). Server: GET/POST /api/first-steps (docs/APP-API.md, saga/firststeps.py).
//   GET  -> {enabled, ask, state: null|"yes"|"no", page: id|null, items: [{key, done}]}; jeder Abruf hakt auf dem Server ab
//   POST {"answer":"yes"} -> {ok, page} (legt die Seite an bzw. neu an), {"answer":"no"} -> {ok}; enabled:false -> 403
//   aeltere Server: 404 -> still nichts tun
enum FirstStepsLogic {
    static let path = "api/first-steps"
    /// GET hoechstens so oft (Start und Rueckkehr in den Vordergrund, gedrosselt)
    static let minInterval: TimeInterval = 60

    struct Item: Equatable { let key: String; let done: Bool }
    struct State: Equatable {
        var enabled: Bool
        var ask: Bool
        var answer: String?                                  // nil | "yes" | "no"
        var page: Int?
        var items: [Item]
        var done: Int { items.filter(\.done).count }
    }

    enum Fetch: Equatable {
        case off                                             // aelterer Server (404) bzw. vom Admin abgeschaltet: nichts zeigen
        case state(State)
        case failed                                          // offline/5xx/kaputt: bisherigen Stand behalten
    }

    static func parse(status: Int, data: Data) -> Fetch {
        if status == 404 || status == 405 || status == 403 { return .off }
        guard status == 200, let j = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { return .failed }
        guard j["enabled"] as? Bool == true else { return .off }
        let items = (j["items"] as? [[String: Any]] ?? []).compactMap { d -> Item? in
            guard let k = d["key"] as? String, !k.isEmpty else { return nil }
            return Item(key: k, done: d["done"] as? Bool == true)
        }
        let st = j["state"] as? String
        return .state(State(enabled: true, ask: j["ask"] as? Bool == true, answer: st == "yes" || st == "no" ? st : nil,
                            page: pageID(j["page"]), items: items))
    }

    /// Seiten-ID: nur positive ganze Zahlen (kein Bool, keine Zeichenkette)
    static func pageID(_ v: Any?) -> Int? {
        guard let n = v as? NSNumber, CFGetTypeID(n) != CFBooleanGetTypeID() else { return nil }
        let i = n.intValue
        return i > 0 && Double(i) == n.doubleValue ? i : nil
    }

    static func body(yes: Bool) -> [String: Any] { ["answer": yes ? "yes" : "no"] }

    enum Post: Equatable {
        case ok(page: Int?)
        case off                                             // 403 (abgeschaltet) bzw. 404 (aelterer Server)
        case error(String)
    }

    static func parsePost(status: Int, data: Data) -> Post {
        let j = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
        if status == 403 || status == 404 || status == 405 { return .off }
        guard (200..<300).contains(status), j?["ok"] as? Bool == true else { return .error(j?["error"] as? String ?? "") }
        return .ok(page: pageID(j?["page"]))
    }

    /// Stand nach einer Antwort (wie im Web: "yes" bleibt "yes", die Frage ist erledigt)
    static func answered(_ s: State?, yes: Bool, page: Int?) -> State? {
        guard var s else { return nil }
        s.ask = false
        s.answer = s.answer == "yes" || yes ? "yes" : "no"
        if yes { s.page = page ?? s.page }
        return s
    }

    /// Drosselung des GET (Start, Vordergrund); `force` z. B. nach einer neuen Anmeldung
    struct Throttle: Equatable {
        private(set) var last: Date?
        mutating func allow(now: Date, force: Bool = false) -> Bool {
            if !force, let l = last, now.timeIntervalSince(l) < FirstStepsLogic.minInterval, now >= l { return false }
            last = now
            return true
        }
        mutating func reset() { last = nil }
    }

    /// Frage jetzt zeigen? Nie ueber anderen Dialogen/Blaettern, nie waehrend Anmeldung, Kopplung, Chat-Einrichtung
    /// oder einem Anruf im Vollbild - sie wartet dann, bis das vorbei ist.
    struct Blockers: Equatable {
        var login = false, pairing = false, sheet = false, chatSetup = false, call = false
        var any: Bool { login || pairing || sheet || chatSetup || call }
    }
    static func showAsk(pending: Bool, blockers: Blockers) -> Bool { pending && !blockers.any }

    /// Einstellungen > Info: Abschnitt "Erste Schritte"
    enum Help: Equatable {
        case open(done: Int, all: Int)                       // Seite vorhanden: "{n} von {all} erledigt" + "Erste Schritte oeffnen"
        case create                                          // keine Seite: Erklaerung + "Erste Schritte anlegen"
    }
    static func help(_ s: State?) -> Help? {
        guard let s, s.enabled else { return nil }
        return s.page != nil ? .open(done: s.done, all: s.items.count) : .create
    }
}

/// 2031: leere Listen - was erscheint hier, welcher Knopf
enum EmptyStateLogic {
    enum Tasks: Equatable {
        case open                                            // offene Aufgaben da
        case allDone                                         // alles erledigt (es gab schon Aufgaben)
        case never                                           // noch nie eine Aufgabe: Satz + "Aufgabe notieren"
    }
    static func tasks(open: Int, done: Int) -> Tasks { open > 0 ? .open : done > 0 ? .allDone : .never }

    enum Rows: Equatable {
        case shown                                           // Eintraege sichtbar
        case filtered                                        // Eintraege da, aber keiner passt zu Filtern/Suche
        case empty                                           // Datenbank ohne Eintraege: Satz + "+ Neuer Eintrag"
    }
    static func rows(total: Int, shown: Int) -> Rows { shown > 0 ? .shown : total > 0 ? .filtered : .empty }

    /// GET api/settings/calendars -> mindestens ein Kalender-Konto verbunden? nil: unbekannt (offline, aelterer Server,
    /// Kalender aus) - dann kein Hinweis
    static func calendarConnected(status: Int, data: Data) -> Bool? {
        guard status == 200, let j = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let a = j["accounts"] as? [Any] else { return nil }
        return !a.isEmpty
    }
}
