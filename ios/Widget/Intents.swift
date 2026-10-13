import AppIntents
import WidgetKit

// MARK: - Aktionen direkt im Widget (ohne die App zu oeffnen)

/// Aufgabe abhaken / wieder oeffnen (gleiche Logik wie im Web, inkl. Folgeaufgabe bei Wiederholung)
struct ToggleTaskIntent: AppIntent {
    static var title: LocalizedStringResource = "Aufgabe abhaken"
    static var isDiscoverable = false
    @Parameter(title: "Aufgabe") var taskID: Int
    @Parameter(title: "Erledigt") var done: Bool
    init() {}
    init(taskID: Int, done: Bool) { self.taskID = taskID; self.done = done }
    func perform() async throws -> some IntentResult {
        WidgetState.markTask(taskID, done: done)          // sofort sichtbar, auch bevor der Server antwortet
        try await API.taskDone(taskID, done: done)
        return .result()
    }
}

/// Haekchen-Feld einer Datenbank-Zeile setzen (z. B. "Häkchen" in den ToDos)
struct CheckRowIntent: AppIntent {
    static var title: LocalizedStringResource = "Häkchen setzen"
    static var isDiscoverable = false
    @Parameter(title: "Zeile") var rowID: Int
    @Parameter(title: "Feld") var pid: String
    @Parameter(title: "Wert") var value: Bool
    init() {}
    init(rowID: Int, pid: String, value: Bool) { self.rowID = rowID; self.pid = pid; self.value = value }
    func perform() async throws -> some IntentResult {
        try await API.check(row: rowID, pid: pid, value: value)      // das Widget laedt erst danach neu
        return .result()
    }
}

/// Kurzzeitiger Zwischenstand, damit ein Tipp sofort sichtbar ist (der Server bestaetigt beim naechsten Laden).
enum WidgetState {
    private static var store: UserDefaults { AppConfig.store }
    static func markTask(_ id: Int, done: Bool) { var d = tasks(); d[String(id)] = done ? Date().timeIntervalSince1970 : nil; store.set(d, forKey: "w.tasks") }
    static func tasks() -> [String: Double] { (store.dictionary(forKey: "w.tasks") as? [String: Double] ?? [:]).filter { Date().timeIntervalSince1970 - $0.value < 120 } }
}

// MARK: - Auswahl: welche Seite / Datenbank-Ansicht zeigt das Widget?

/// "p:12" = Seite 12 (zeigt ihre Unterseiten), "v:7:tab" = Datenbank 7, Ansicht "tab"
struct TargetEntity: AppEntity {
    static var typeDisplayRepresentation: TypeDisplayRepresentation = "Seite oder Ansicht"
    static var defaultQuery = TargetQuery()
    let id: String
    let name: String
    let icon: String?
    var displayRepresentation: DisplayRepresentation { DisplayRepresentation(title: "\(icon.map { $0 + " " } ?? "")\(name)") }

    var pageID: Int? { let p = id.split(separator: ":"); return p.count >= 2 ? Int(p[1]) : nil }
    var viewID: String? { let p = id.split(separator: ":", omittingEmptySubsequences: false); return p.first == "v" && p.count == 3 && !p[2].isEmpty ? String(p[2]) : nil }
    var isDatabase: Bool { id.hasPrefix("v:") }
}

struct TargetQuery: EntityStringQuery {
    /// Datenbanken je Ansicht ("ToDos · Archiv"), Seiten einzeln (Datenbank-Zeilen nicht - davon gibt es zu viele)
    static func all() async throws -> [TargetEntity] {
        let pages = try await API.pages()
        let dbIDs = Set(pages.filter(\.isDatabase).map(\.id))
        var out: [TargetEntity] = []
        for p in pages where p.parent_id.map({ !dbIDs.contains($0) }) ?? true {
            let name = p.title.isEmpty ? String(localized: "Unbenannt") : p.title
            if p.isDatabase {
                let views = p.views ?? []
                if views.isEmpty { out.append(TargetEntity(id: "v:\(p.id):", name: name, icon: p.emoji ?? "🗂")) }
                for v in views { out.append(TargetEntity(id: "v:\(p.id):\(v.id ?? "")", name: views.count > 1 ? "\(name) · \(v.name ?? String(localized: "Ansicht"))" : name, icon: p.emoji ?? "🗂")) }
            } else {
                out.append(TargetEntity(id: "p:\(p.id)", name: name, icon: p.emoji ?? "📄"))
            }
        }
        return out
    }
    func entities(for identifiers: [String]) async throws -> [TargetEntity] {
        let all = try await Self.all(); return identifiers.compactMap { id in all.first { $0.id == id } }
    }
    func entities(matching string: String) async throws -> [TargetEntity] {
        try await Self.all().filter { $0.name.localizedCaseInsensitiveContains(string) }
    }
    func suggestedEntities() async throws -> [TargetEntity] {
        let all = try await Self.all()
        return all.filter(\.isDatabase) + all.filter { !$0.isDatabase }      // Datenbanken zuerst
    }
}

struct PageWidgetIntent: WidgetConfigurationIntent {
    static var title: LocalizedStringResource = "Seite oder Datenbank"
    static var description = IntentDescription("Zeigt eine Datenbank-Ansicht (mit Filter und Sortierung) oder die Unterseiten einer Seite.")
    @Parameter(title: "Anzeigen") var target: TargetEntity?
    init() {}
}
