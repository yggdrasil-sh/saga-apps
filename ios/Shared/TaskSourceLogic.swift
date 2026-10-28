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
