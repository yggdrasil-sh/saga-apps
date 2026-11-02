import Foundation

// Saga 2.5 Aufgabenquellen (tasksrc1): eine Liste aus Inbox-Aufgaben und Datenbank-Zeilen, Abhaken mit Rueckgaengig,
// Offline-Warteschlange. Reine Logik (App, Widget, Logik-Tests). Server: GET /api/tasks?with_sources=1,
// PATCH /api/tasks/src/{zeile}, POST /api/app/tasks/src/{zeile}/done, GET /api/app/today?sources=1.

// Antwort von GET /api/app/today (Widgets, Kurzbefehle) - hier, damit die Logik-Tests sie lesen koennen
struct TodayEvent: Codable, Hashable {
    var summary: String; var start: String; var end: String; var allday: Bool
    var location: String?; var color: String?; var calendar: String?
}
struct TodayTask: Codable, Hashable { var id: Int; var text: String; var due: String?; var prio: Int?; var overdue: Bool? }
struct TodayData: Codable {
    var date: String; var events: [TodayEvent]; var tasks: [TodayTask]; var errors: [String?]?
    /// 2.5 tasksrc1 (?sources=1): gemeinsame Liste aus Inbox und Quellen (hoechstens 20), Rest, Quellen (db/title/icon)
    var items: [TaskItem]? = nil
    var more: Int? = nil
    var sources: [TaskSource]? = nil
}

/// Ein Eintrag der gemeinsamen Liste (`items`, fertig sortiert vom Server)
struct TaskItem: Codable, Hashable, Identifiable {
    var key: String                  // "t:12" | "r:345" - ueber beide Arten eindeutig
    var kind: String                 // "task" | "row"
    var id: Int
    var src: Int?
    var text: String
    var due: String?
    var time: String?
    var due_end: String?
    var prio: Int?
    var `repeat`: String?
    var done: Bool?
    var overdue: Bool?
    var edit: Bool?
    var assignees: [String]?
    var isRow: Bool { kind == "row" }
    /// nur lesen (Zeile ohne Bearbeiten-Recht): Haekchen ausgrauen
    var readOnly: Bool { isRow && edit == false }
}

/// Quelle (abonnierte Datenbank)
struct TaskSource: Codable, Hashable, Identifiable {
    var db: Int
    var title: String?
    var icon: String?
    var role: String?
    var scope: String?
    var own: Bool?
    var count: Int?
    var truncated: Bool?
    var inactive: Bool?
    var unmapped: Bool?
    var no_assignee: Bool?
    var map: TaskSourceMap?
    var id: Int { db }
    var name: String { (title ?? "").isEmpty ? String(localized: "Unbenannt") : title! }
    /// Symbol nur als Emoji (eigene Bilder gehen in Widgets nicht)
    var emoji: String? { icon.flatMap { $0.isEmpty || $0.count > 4 || $0.hasPrefix("/") ? nil : $0 } }
    /// Ziel fuer neue Aufgaben: mit Zuordnung, aktiv, Rolle Eigentuemer/Bearbeiten
    var canAdd: Bool { map != nil && inactive != true && unmapped != true && (role == "owner" || role == "edit") }
    /// "nur mir zugewiesene" moeglich (Personen-Eigenschaft zugeordnet)
    var canAssigned: Bool { map?.assignee != nil }
}

struct TaskSourceMap: Codable, Hashable {
    var done: String?
    var done_type: String?
    var done_value: String?
    var open_value: String?
    var due: String?
    var prio: String?
    var assignee: String?
}

/// Person aus `people` (nie Anmeldenamen)
struct TaskPerson: Codable, Hashable {
    var name: String?
    var avatar: String?
    var me: Bool?
    var remote: Bool?
    var gone: Bool?
}

/// Antwort von GET /api/tasks?with_sources=1 (nur die neuen Felder; open/done liest die App wie bisher)
struct TaskSourcesList: Decodable {
    var items: [TaskItem]?
    var sources: [TaskSource]?
    var people: [String: TaskPerson]?
}

enum TaskSourceLogic {
    /// Abfrage der Liste: heutiges Datum und Zeitzone des Geraets (Reihenfolge rechnet der Server)
    static func listPath(today: String, tz: String) -> String {
        "api/tasks?with_sources=1&today=\(today)&tz=" + (tz.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed.subtracting(CharacterSet(charactersIn: "+/&="))) ?? tz)
    }
    /// Datum/Uhrzeit/Zeitraum zum Anzeigen (Tag ueber `day`, z. B. "Heute"/"29. Okt.")
    static func when(_ t: TaskItem, day: (String) -> String) -> String? {
        guard let d = t.due, !d.isEmpty else { return nil }
        var s = day(d)
        if let tm = t.time, !tm.isEmpty { s += " " + tm }
        if let e = t.due_end, !e.isEmpty, e != d { s += " – " + day(e) }
        return s
    }
    /// Zugewiesene als Namen (Reihenfolge wie geliefert; unbekannte Kennung -> "Unbekannte Person")
    static func names(_ t: TaskItem, people: [String: TaskPerson]) -> [String] {
        (t.assignees ?? []).map { r in people[r]?.name.flatMap { $0.isEmpty ? nil : $0 } ?? String(localized: "Unbekannte Person") }
    }
    /// Rumpf zum Abhaken/Wieder-Oeffnen einer Zeile (Rueckgaengig: value = prev)
    static func doneBody(done: Bool, value: TaskValue?) -> [String: Any] {
        var b: [String: Any] = ["done": done]
        if !done, let v = value { b["value"] = v.json }
        return b
    }
    /// Ziele fuer "Neue Aufgabe": Inbox (nil) + Quellen, die es erlauben
    static func targets(_ sources: [TaskSource]) -> [TaskSource] { sources.filter(\.canAdd) }
}

// MARK: - Kurzbefehl "Neue Aufgabe" mit Ziel (capsrc1, APP-API "Teilen und Kurzbefehle")

/// Ziel-Auswahl fuer den Kurzbefehl (App-Schluessel, laeuft auch ohne geoeffnete App). Der App-Schluessel sieht keine
/// Rollen/Zuordnungen (GET /api/tasks/sources nur mit Sitzung): die App merkt sich die erlaubten Ziele beim Laden der
/// Aufgabenliste; /api/app/today?sources=1 (nur db/title/icon) entfernt inzwischen abbestellte Quellen.
enum CaptureTargetLogic {
    static let feature = "capsrc1"
    /// gemeinsame Einstellungen (App-Gruppe): erlaubte Ziele, Server meldet capsrc1, zuletzt gewaehltes Ziel (0 = Inbox)
    static let targetsKey = "capTargets", featureKey = "capFeature", lastKey = "capLastTarget"
    /// Inbox als Eintrag der Auswahl
    static let inbox = 0

    /// Rumpf fuer POST /api/app/capture {kind: "task"}. src nur, wenn der Server capsrc1 kann (aeltere legten sonst
    /// stillschweigend eine Inbox-Aufgabe an) und ein Ziel gewaehlt ist.
    static func body(title: String, text: String = "", url: String? = nil, due: String? = nil, src: Int?, capsrc: Bool) -> [String: Any] {
        var b: [String: Any] = ["kind": "task", "title": title, "text": text]
        if let url, !url.isEmpty { b["url"] = url }
        if let due { b["due"] = due }
        if capsrc, let src, src > 0 { b["src"] = src }
        return b
    }
    /// Ziel, das wirklich gesendet wird (nil = Inbox)
    static func src(target: Int?, capsrc: Bool) -> Int? { capsrc ? target.flatMap { $0 > 0 ? $0 : nil } : nil }
    /// Live-Abfrage gewinnt; ohne Antwort (offline) der zuletzt von der App gemerkte Stand
    static func capsrc(live: Set<String>?, cached: Bool) -> Bool { live.map { $0.contains(feature) } ?? cached }

    /// Ziele zum Auswaehlen: gemerkte erlaubte Ziele, abgeglichen mit den aktuell abonnierten (nil = nicht abrufbar).
    /// Ohne gemerkte Liste (App hat noch nie mit tasksrc1 geladen): die abonnierten - der Server prueft die Rechte (403).
    static func choices(cached: [TaskSource]?, current: [TaskSource]?) -> [TaskSource] {
        guard let cached else { return current ?? [] }
        guard let current else { return cached }
        let byDB = Dictionary(current.map { ($0.db, $0) }, uniquingKeysWith: { a, _ in a })
        return cached.compactMap { c in
            guard let n = byDB[c.db] else { return nil }
            var x = c; if let t = n.title { x.title = t }; if let i = n.icon { x.icon = i }; return x
        }
    }
    /// Vorgabe: zuletzt gewaehltes Ziel, wenn es noch waehlbar ist, sonst Inbox
    static func defaultTarget(last: Int?, choices: [TaskSource]) -> Int {
        guard let l = last, l > 0, choices.contains(where: { $0.db == l }) else { return inbox }
        return l
    }
    /// Gemerkte Ziele: nur erlaubte (canAdd), schlank (ohne Zaehler/Personen)
    static func encodeTargets(_ sources: [TaskSource]) -> Data? {
        try? JSONEncoder().encode(TaskSourceLogic.targets(sources).map {
            TaskSource(db: $0.db, title: $0.title, icon: $0.icon, role: $0.role, map: $0.map)
        })
    }
    static func decodeTargets(_ d: Data?) -> [TaskSource]? { d.flatMap { try? JSONDecoder().decode([TaskSource].self, from: $0) } }

    enum Failure: Equatable { case forbidden, gone, invalid(String), other }
    /// Fehler beim Anlegen in einer Quelle (Server: 400 keine/ungueltige Quelle, 403 nur Lesen, 404 unbekannt)
    static func failure(code: Int, message: String, src: Int?) -> Failure {
        guard src != nil else { return .other }
        switch code {
        case 403: return .forbidden
        case 404: return .gone
        case 400: return .invalid(message)
        default: return .other
        }
    }
    /// Verstaendliche Meldung fuer den Kurzbefehl (nil = allgemeine Fehlermeldung)
    static func message(_ f: Failure, name: String) -> String? {
        switch f {
        case .forbidden: return String(localized: "In „\(name)“ darfst du keine Aufgaben anlegen (nur Lesen).")
        case .gone: return String(localized: "Die Aufgabenquelle „\(name)“ gibt es nicht mehr oder du hast keinen Zugriff.")
        case .invalid(let m): return m.isEmpty ? String(localized: "„\(name)“ ist keine Aufgabenquelle mehr.") : m
        case .other: return nil
        }
    }
    /// Nach 403/404/400 das gemerkte Ziel vergessen (naechster Aufruf wieder Inbox)
    static func forgetLast(_ f: Failure) -> Bool { f != .other }
}

/// Wert der Erledigt-Eigenschaft vor dem Abhaken (prev): Option-ID, Haekchen true/false oder null
enum TaskValue: Codable, Hashable {
    case text(String), bool(Bool), null
    init(_ v: Any?) {
        if let s = v as? String { self = .text(s) }
        else if let n = v as? NSNumber, CFGetTypeID(n) == CFBooleanGetTypeID() { self = .bool(n.boolValue) }
        else if let b = v as? Bool { self = .bool(b) }
        else { self = .null }
    }
    var json: Any { switch self { case .text(let s): return s; case .bool(let b): return b; case .null: return NSNull() } }
    init(from decoder: Decoder) throws {
        let c = try decoder.singleValueContainer()
        if c.decodeNil() { self = .null } else if let b = try? c.decode(Bool.self) { self = .bool(b) } else { self = .text(try c.decode(String.self)) }
    }
    func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        switch self { case .text(let s): try c.encode(s); case .bool(let b): try c.encode(b); case .null: try c.encodeNil() }
    }
}

// MARK: - Offline-Warteschlange (APP-API "Offline-Warteschlange (Apps)")

/// Eine ausstehende Aktion je Eintrag (Schluessel t:/r:), letzte gewinnt
struct TaskQueueEntry: Codable, Equatable {
    var key: String
    var done: Bool
    /// Wert vor dem Abhaken (fuer das Wieder-Oeffnen): aus prev bzw. offline aus den zuletzt geladenen Werten
    var prev: TaskValue?
    /// Zustand, den der Server zuletzt bestaetigt hat (abhaken + wieder oeffnen = nichts senden)
    var serverDone: Bool
}

struct TaskQueue: Codable, Equatable {
    private(set) var entries: [String: TaskQueueEntry] = [:]
    var isEmpty: Bool { entries.isEmpty }
    /// Aktion vormerken. Entspricht sie dem bestaetigten Stand des Servers, faellt der Eintrag weg (letzte gewinnt).
    mutating func put(key: String, done: Bool, serverDone: Bool, prev: TaskValue?) {
        let base = entries[key]?.serverDone ?? serverDone
        if done == base { entries.removeValue(forKey: key); return }
        let keepPrev = entries[key]?.prev ?? prev
        entries[key] = TaskQueueEntry(key: key, done: done, prev: keepPrev, serverDone: base)
    }
    mutating func remove(_ key: String) { entries.removeValue(forKey: key) }
    /// in fester Reihenfolge nachholen
    var pending: [TaskQueueEntry] { entries.values.sorted { $0.key < $1.key } }
    /// Anfrage zum Nachholen: Inbox PATCH /api/tasks/{id}, Zeile PATCH /api/tasks/src/{id} (mit value = prev beim Oeffnen)
    static func request(_ e: TaskQueueEntry) -> (path: String, body: [String: Any])? {
        let p = e.key.split(separator: ":"); guard p.count == 2, let id = Int(p[1]) else { return nil }
        if p[0] == "t" { return ("api/tasks/\(id)", ["done": e.done]) }
        if p[0] == "r" { return ("api/tasks/src/\(id)", TaskSourceLogic.doneBody(done: e.done, value: e.prev)) }
        return nil
    }
    /// Was nach dem Nachholen? 2xx: weg (danach Liste neu laden); 403/404: verwerfen + neu laden; 400: Zuordnung
    /// geaendert -> verwerfen, neu laden (map neu lesen), nicht wiederholen; Netzfehler/5xx/sonst: behalten
    enum Outcome: Equatable { case done, drop, keep }
    static func outcome(_ code: Int?) -> Outcome {
        guard let c = code else { return .keep }
        if (200..<300).contains(c) { return .done }
        if [400, 403, 404].contains(c) { return .drop }
        return .keep
    }
}
