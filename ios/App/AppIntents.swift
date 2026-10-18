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
            let when = due.formatted(SagaDate.dateTime().weekday(.wide).day().month(.wide))
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

/// Seiten in der Systemsuche. Abgleich im Hintergrund (nie auf dem Main-Thread), gebuendelt (nur der neueste Stand
/// zaehlt) und inkrementell: nach dem ersten Abgleich je Prozess werden nur geaenderte/neue Eintraege indiziert und
/// entfernte geloescht (bisher: nach jeder Aenderung alles loeschen und neu). Inhalt der Eintraege unveraendert.
enum SpotlightIndex {
    private static let worker = SpotlightWorker()
    /// Nach jeder Aenderung der Seitenliste (Aufruf vom Main-Thread kostet nur das Uebergeben des Arrays)
    static func update(_ pages: [Page]) { worker.submit(pages) }
    static func clear() { worker.clear() }
    /// "page-12" aus einem Spotlight-Treffer
    static func pageID(_ activity: NSUserActivity) -> Int? {
        (activity.userInfo?[CSSearchableItemActivityIdentifier] as? String).flatMap { $0.hasPrefix("page-") ? Int($0.dropFirst(5)) : nil }
    }
}

/// Ein Arbeiter fuer alle Spotlight-Auftraege: nacheinander, im Hintergrund; neue Staende ersetzen noch nicht
/// begonnene (bei schnellen Aenderungen wird nur der letzte indiziert). Abmelden bricht laufendes Indizieren ab.
final class SpotlightWorker: @unchecked Sendable {
    private let lock = NSLock()
    private var pending: [Page]?
    private var clearWanted = false
    private var running = false
    private var epoch = 0                                   // zaehlt bei jedem clear(): laufender Abgleich hoert auf
    private var known: [Int: SpotlightEntry]?               // was dieser Prozess indiziert hat (nur im Arbeiter benutzt)
    private static let chunk = 500
    private static let domain = "pages"

    func submit(_ pages: [Page]) {
        lock.lock(); pending = pages; let start = !running; running = true; lock.unlock()
        if start { Task.detached(priority: .utility) { await self.drain() } }
    }
    func clear() {
        lock.lock(); pending = nil; clearWanted = true; epoch += 1; let start = !running; running = true; lock.unlock()
        if start { Task.detached(priority: .utility) { await self.drain() } }
    }
    private func currentEpoch() -> Int { lock.lock(); defer { lock.unlock() }; return epoch }

    /// Naechsten Auftrag holen (nil: nichts mehr zu tun, Arbeiter endet)
    private func take() -> (wipe: Bool, next: [Page]?, epoch: Int)? {
        lock.lock(); defer { lock.unlock() }
        let wipe = clearWanted, next = pending
        clearWanted = false; pending = nil
        if !wipe && next == nil { running = false; return nil }
        return (wipe, next, epoch)
    }

    private func drain() async {
        while let (wipe, next, ep) = take() {
            let idx = CSSearchableIndex.default()
            if wipe { known = nil; try? await idx.deleteSearchableItems(withDomainIdentifiers: [Self.domain]) }
            if let next { await apply(next, epoch: ep, idx) }
        }
    }

    private func apply(_ pages: [Page], epoch ep: Int, _ idx: CSSearchableIndex) async {
        let entries = SpotlightPlan.entries(pages)
        let d = SpotlightPlan.diff(old: known, new: entries)
        if d.full { try? await idx.deleteSearchableItems(withDomainIdentifiers: [Self.domain]) }
        if !d.delete.isEmpty { try? await idx.deleteSearchableItems(withIdentifiers: d.delete.map { "page-\($0)" }) }
        var i = 0, failed = false
        while i < d.upsert.count {
            guard currentEpoch() == ep else { known = nil; return }        // abgemeldet: der folgende clear() raeumt auf
            let items = d.upsert[i..<min(i + Self.chunk, d.upsert.count)].map(Self.item)
            do { try await idx.indexSearchableItems(items) } catch { failed = true }
            i += Self.chunk
        }
        // Fehler beim Indizieren: beim naechsten Mal wieder alles (wie bisher), sonst fehlten Eintraege dauerhaft
        known = currentEpoch() == ep && !failed ? SpotlightPlan.known(entries) : nil
    }

    private static func item(_ e: SpotlightEntry) -> CSSearchableItem {
        let a = CSSearchableItemAttributeSet(contentType: .text)
        a.title = e.title
        a.contentDescription = e.desc
        if let dn = e.display { a.displayName = dn }
        return CSSearchableItem(uniqueIdentifier: "page-\(e.id)", domainIdentifier: domain, attributeSet: a)
    }
}
