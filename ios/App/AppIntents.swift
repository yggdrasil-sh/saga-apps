import AppIntents
import CoreSpotlight
import Foundation
#if canImport(UniformTypeIdentifiers)
import UniformTypeIdentifiers
#endif

// Siri, Kurzbefehle, Action-Button und Spotlight-Aktionen.
// Siri-Saetze: hier Englisch (Entwicklungssprache), Deutsch/Spanisch in AppShortcuts.xcstrings.
// Ablegen laeuft ueber den App-Schluessel (/api/app/*) - funktioniert auch, ohne die App zu oeffnen.

struct AddTaskIntent: AppIntent {
    static var title: LocalizedStringResource = "Aufgabe anlegen"
    static var description = IntentDescription("Legt eine Aufgabe in Saga an – optional mit Fälligkeit.")
    @Parameter(title: "Aufgabe", requestValueDialog: "Was soll auf die Liste?") var text: String
    @Parameter(title: "Fällig am") var due: Date?
    static var parameterSummary: some ParameterSummary { Summary("Aufgabe \(\.$text) anlegen") { \.$due } }
    func perform() async throws -> some IntentResult & ProvidesDialog {
        try await API.capture(kind: "task", title: text, text: "", url: nil, due: due)
        if let due {
            let when = due.formatted(.dateTime.weekday(.wide).day().month(.wide))
            return .result(dialog: "Aufgabe „\(text)“ angelegt – fällig \(when).")
        }
        return .result(dialog: "Aufgabe „\(text)“ angelegt.")
    }
}

struct CaptureNoteIntent: AppIntent {
    static var title: LocalizedStringResource = "Notiz ablegen"
    static var description = IntentDescription("Legt eine Notiz als neue Seite im Eingang ab.")
    @Parameter(title: "Notiz", inputOptions: String.IntentInputOptions(multiline: true), requestValueDialog: "Was möchtest du notieren?") var text: String
    static var parameterSummary: some ParameterSummary { Summary("Notiz \(\.$text) ablegen") }
    func perform() async throws -> some IntentResult & ProvidesDialog {
        let lines = text.split(separator: "\n", maxSplits: 1, omittingEmptySubsequences: false)
        let title = String(lines.first ?? "").trimmingCharacters(in: .whitespaces)
        try await API.capture(kind: "page", title: String(title.prefix(80)), text: lines.count > 1 ? String(lines[1]) : (title.count > 80 ? text : ""), url: nil)
        return .result(dialog: "Notiz im Eingang abgelegt.")
    }
}

/// Seite fuer "Seite öffnen" (Auswahl mit Suche)
struct HKPageEntity: AppEntity {
    static var typeDisplayRepresentation: TypeDisplayRepresentation = "Seite"
    static var defaultQuery = HKPageQuery()
    let id: Int
    let name: String
    let icon: String?
    var displayRepresentation: DisplayRepresentation { DisplayRepresentation(title: "\(icon.map { $0 + " " } ?? "")\(name)") }
}
struct HKPageQuery: EntityStringQuery {
    private static func all() async throws -> [HKPageEntity] {
        try await API.pages().map { HKPageEntity(id: $0.id, name: $0.title.isEmpty ? String(localized: "Unbenannt") : $0.title, icon: $0.emoji) }
    }
    func entities(for identifiers: [Int]) async throws -> [HKPageEntity] { let a = try await Self.all(); return identifiers.compactMap { i in a.first { $0.id == i } } }
    func entities(matching string: String) async throws -> [HKPageEntity] { try await Self.all().filter { $0.name.localizedCaseInsensitiveContains(string) } }
    func suggestedEntities() async throws -> [HKPageEntity] { Array(try await Self.all().prefix(60)) }
}

struct OpenPageIntent: AppIntent {
    static var title: LocalizedStringResource = "Seite öffnen"
    static var description = IntentDescription("Öffnet eine Seite in Saga.")
    @Parameter(title: "Seite") var page: HKPageEntity
    static var parameterSummary: some ParameterSummary { Summary("\(\.$page) öffnen") }
    func perform() async throws -> some IntentResult & OpensIntent {
        .result(opensIntent: OpenURLIntent(URL(string: "hlidskjalf://page/\(page.id)")!))
    }
}

struct OpenTasksIntent: AppIntent {
    static var title: LocalizedStringResource = "Aufgaben öffnen"
    static var description = IntentDescription("Zeigt die offenen Aufgaben.")
    func perform() async throws -> some IntentResult & OpensIntent { .result(opensIntent: OpenURLIntent(URL(string: "hlidskjalf://tasks")!)) }
}

struct HKShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(intent: AddTaskIntent(), phrases: ["New task in \(.applicationName)", "Add a task in \(.applicationName)", "\(.applicationName) task"],
                    shortTitle: "Aufgabe anlegen", systemImageName: "checklist")
        AppShortcut(intent: CaptureNoteIntent(), phrases: ["Note in \(.applicationName)", "Save a note in \(.applicationName)"],
                    shortTitle: "Notiz ablegen", systemImageName: "square.and.pencil")
        AppShortcut(intent: OpenPageIntent(), phrases: ["Open a page in \(.applicationName)", "Open \(\.$page) in \(.applicationName)"],
                    shortTitle: "Seite öffnen", systemImageName: "doc.text")
        AppShortcut(intent: OpenTasksIntent(), phrases: ["Tasks in \(.applicationName)", "Show \(.applicationName) tasks"],
                    shortTitle: "Aufgaben", systemImageName: "checkmark.circle")
    }
}

// MARK: - Spotlight: alle Seiten in der Systemsuche (Tippen oeffnet die Seite)

enum SpotlightIndex {
    private static var lastHash = 0
    /// Nach jedem Laden der Seitenliste - indiziert nur neu, wenn sich Titel/Pfade geaendert haben
    static func update(_ pages: [Page]) {
        let byId = Dictionary(pages.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
        func path(_ p: Page) -> String {
            var out: [String] = [], cur = p.parent_id, guardN = 0
            while let c = cur, let pp = byId[c], guardN < 20 { out.insert(pp.label, at: 0); cur = pp.parent_id; guardN += 1 }
            return out.joined(separator: " / ")
        }
        let items = pages.map { p -> CSSearchableItem in
            let a = CSSearchableItemAttributeSet(contentType: .text)
            a.title = p.label
            let pth = path(p); a.contentDescription = pth.isEmpty ? (p.isDatabase ? String(localized: "Datenbank") : String(localized: "Seite")) : pth
            if let ic = p.icon, !ic.isEmpty, ic.count <= 4, !ic.hasPrefix("/") { a.displayName = ic + " " + p.label }
            return CSSearchableItem(uniqueIdentifier: "page-\(p.id)", domainIdentifier: "pages", attributeSet: a)
        }
        var h = Hasher(); for i in items { h.combine(i.uniqueIdentifier); h.combine(i.attributeSet.title); h.combine(i.attributeSet.contentDescription) }
        let hash = h.finalize(); guard hash != lastHash else { return }; lastHash = hash
        let idx = CSSearchableIndex.default()
        idx.deleteSearchableItems(withDomainIdentifiers: ["pages"]) { _ in idx.indexSearchableItems(items) { _ in } }
    }
    static func clear() { lastHash = 0; CSSearchableIndex.default().deleteSearchableItems(withDomainIdentifiers: ["pages"]) { _ in } }
    /// "page-12" aus einem Spotlight-Treffer
    static func pageID(_ activity: NSUserActivity) -> Int? {
        (activity.userInfo?[CSSearchableItemActivityIdentifier] as? String).flatMap { $0.hasPrefix("page-") ? Int($0.dropFirst(5)) : nil }
    }
}
