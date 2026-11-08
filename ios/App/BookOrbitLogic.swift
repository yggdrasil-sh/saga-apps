import Foundation

// Saga 2.5 BookOrbit (bookorbit1, Phase 1 nur lesen): reine Logik ohne SwiftUI, mit swiftc pruefbar
// (Tests/Logic/BookOrbitTests.swift). Widget "Lesen" (kind "reading", data aus GET /api/notes/{nid}/widgets),
// Karte "Lesen" unter Heute (GET /api/bookorbit/reading) und Einstellungen (GET /api/bookorbit/status).
// Die App spricht nie selbst mit BookOrbit - auch Cover kommen ueber den Saga-Server (mit Sitzung/X-Saga).

enum BookOrbitLogic {
    /// Faehigkeit in /api/version features
    static let feature = "bookorbit1"

    enum State: String, Equatable { case ok, none, expired, unavailable }
    enum Show: String, Equatable { case current, goal, streak }

    struct Book: Equatable, Identifiable {
        var id: Int
        var title: String
        var authors: [String]
        /// 0 ... 100
        var progress: Double
        /// geprueft: nur "/api/bookorbit/cover/<id>", sonst nil
        var cover: String?
        var format: String?
        /// ganze Prozent fuer Text und Bedienhilfe
        var percent: Int { Int(progress.rounded()) }
        /// hoechstens zwei Namen, wie im Web
        var authorLine: String { authors.prefix(2).joined(separator: ", ") }
    }

    struct Goal: Equatable {
        /// nil: in BookOrbit kein Leseziel gesetzt
        var goal: Int?
        var done: Int
        var year: Int?
        /// Anteil 0 ... 1 fuer den Balken (nil ohne Ziel)
        var fraction: Double? { guard let g = goal, g > 0 else { return nil }; return min(1, max(0, Double(done) / Double(g))) }
    }

    struct Streak: Equatable {
        var current: Int
        var longest: Int
        /// letzte 7 Tage, aeltester zuerst
        var last7: [Bool]
        var daysRead: Int { last7.filter { $0 }.count }
    }

    /// Daten des Widgets bzw. der Leseliste
    struct Reading: Equatable {
        var state: State
        var show: Show = .current
        var stale = false
        var name: String?
        var books: [Book] = []
        var more = 0
        var goal: Goal?
        var streak: Streak?
    }

    /// Widget-`data` bzw. Antwort von GET /api/bookorbit/reading lesen. nil: keine Daten (Fehler-Widget, kaputt).
    /// Unbekannter Zustand -> unavailable (freundlicher Hinweis statt leerer Karte).
    static func reading(_ d: [String: Any]?) -> Reading? {
        guard let d else { return nil }
        var r = Reading(state: State(rawValue: d["state"] as? String ?? "") ?? .unavailable)
        r.show = Show(rawValue: d["show"] as? String ?? "") ?? .current
        r.stale = d["stale"] as? Bool == true
        r.name = (d["name"] as? String).flatMap { $0.isEmpty ? nil : $0 }
        r.books = (d["books"] as? [Any] ?? []).compactMap { book($0 as? [String: Any]) }
        r.more = max(0, int(d["more"]) ?? 0)
        if let g = d["goal"] as? [String: Any] {
            r.goal = Goal(goal: int(g["goal"]).flatMap { $0 > 0 ? $0 : nil }, done: max(0, int(g["done"]) ?? 0), year: int(g["year"]))
        }
        if let s = d["streak"] as? [String: Any] {
            r.streak = Streak(current: max(0, int(s["current"]) ?? 0), longest: max(0, int(s["longest"]) ?? 0),
                              last7: (s["last7"] as? [Any] ?? []).prefix(7).map { $0 as? Bool == true })
        }
        return r
    }

    static func book(_ x: [String: Any]?) -> Book? {
        guard let x, let id = int(x["id"]) else { return nil }
        let p = num(x["progress"]) ?? 0
        return Book(id: id, title: (x["title"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines),
                    authors: (x["authors"] as? [Any] ?? []).compactMap { ($0 as? String).flatMap { $0.isEmpty ? nil : $0 } },
                    progress: p.isFinite ? min(100, max(0, p)) : 0,
                    cover: coverPath(x["cover"] as? String), format: x["format"] as? String)
    }

    /// Cover nur ueber den Saga-Server und nur genau diese Form (keine fremden Adressen, kein "..")
    static func coverPath(_ s: String?) -> String? {
        guard let s, s.hasPrefix("/api/bookorbit/cover/") else { return nil }
        let rest = s.dropFirst("/api/bookorbit/cover/".count)
        return !rest.isEmpty && rest.count <= 18 && rest.allSatisfy({ $0.isASCII && $0.isNumber }) ? s : nil
    }

    /// Was die Karte zeigt
    enum Content: Equatable {
        case hint(State)          // none/expired/unavailable: Hinweis (mit Weg zu den Einstellungen ausser bei unavailable)
        case books([Book], more: Int)
        case noBooks
        case goal(Goal)
        case noGoal
        case streak(Streak)
        case noStreak
    }

    static func content(_ r: Reading) -> Content {
        guard r.state == .ok else { return .hint(r.state) }
        switch r.show {
        case .current: return r.books.isEmpty ? .noBooks : .books(r.books, more: r.more)
        case .goal: return r.goal.map { .goal($0) } ?? .noGoal
        case .streak: return r.streak.map { .streak($0) } ?? .noStreak
        }
    }

    /// Hinweis mit Knopf "Einstellungen oeffnen"? (nicht erreichbar: Neu-Anmelden hilft nicht)
    static func offersSettings(_ s: State) -> Bool { s == .none || s == .expired }

    /// Heute-Karte: nur, wenn verbunden (none -> ausgeblendet, wie im Web); hoechstens 3 Buecher
    static func todayVisible(_ r: Reading?) -> Bool { r != nil && r?.state != State.none }
    static let todayLimit = 3

    // MARK: Einstellungen (GET /api/bookorbit/status)

    struct Status: Equatable {
        var available: Bool
        var connected: Bool
        var state: State
        var name: String?
        var url: String?
        var since: Double?
        var suggest: String?
        /// Adresse ohne https:// fuer die Anzeige
        var host: String { (url ?? "").replacingOccurrences(of: "https://", with: "").replacingOccurrences(of: "http://", with: "") }
    }

    static func status(_ d: [String: Any]?) -> Status? {
        guard let d else { return nil }
        return Status(available: d["available"] as? Bool != false, connected: d["connected"] as? Bool == true,
                      state: State(rawValue: d["state"] as? String ?? "") ?? .none,
                      name: (d["name"] as? String).flatMap { $0.isEmpty ? nil : $0 },
                      url: (d["url"] as? String).flatMap { $0.isEmpty ? nil : $0 },
                      since: num(d["since"]),
                      suggest: (d["suggest"] as? String).flatMap { $0.isEmpty ? nil : $0 })
    }

    /// Body fuer POST /api/bookorbit/connect (Adresse/Name ohne Leerraum, Passwort unveraendert)
    static func connectBody(url: String, username: String, password: String) -> [String: Any] {
        ["url": url.trimmingCharacters(in: .whitespacesAndNewlines), "username": username.trimmingCharacters(in: .whitespacesAndNewlines), "password": password]
    }

    static func canConnect(url: String, username: String, password: String) -> Bool {
        !url.trimmingCharacters(in: .whitespaces).isEmpty && !username.trimmingCharacters(in: .whitespaces).isEmpty && !password.isEmpty
    }

    // MARK: Hilfen

    static func num(_ v: Any?) -> Double? {
        if let n = v as? NSNumber { return CFGetTypeID(n) == CFBooleanGetTypeID() ? nil : n.doubleValue }
        return nil
    }
    static func int(_ v: Any?) -> Int? {
        guard let d = num(v), d.isFinite, abs(d) < 1e15 else { return nil }
        return Int(d)
    }
}
