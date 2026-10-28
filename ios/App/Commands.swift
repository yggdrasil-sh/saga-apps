import SwiftUI

// Menue-Befehle mit Tastenkuerzeln (Mac-Menueleiste, iPad mit Tastatur) und die Schnellerfassung in der Menueleiste.

struct HKCommands {
    var back: () -> Void
    var forward: () -> Void
    var newPage: () -> Void
    var search: () -> Void
    var copyLink: () -> Void
    var hasPage: Bool
    var today: () -> Void
    var tasks: () -> Void
    var events: () -> Void
    var graph: (() -> Void)? = nil                  // 2.5: Graph der Seiten (nil = Server zu alt)
    var fromTemplate: (() -> Void)? = nil           // 2.5: Vorlagengalerie (nil = Server ohne Galerie)
    var toggleSidebar: (() -> Void)? = nil          // 2.5 Notion-Gefuehl: Seitenleiste ein/aus (⌘\\)
}
struct HKCommandsKey: FocusedValueKey { typealias Value = HKCommands }
extension FocusedValues {
    var hk: HKCommands? { get { self[HKCommandsKey.self] } set { self[HKCommandsKey.self] = newValue } }
}
/// Kennung des Hauptfensters (Mac: "Neues Fenster" ueber openWindow)
enum HKScene { static let main = "main" }
extension Notification.Name { static let hkCaptured = Notification.Name("hkCaptured") }

struct HKMenuCommands: Commands {
    @FocusedValue(\.hk) private var hk
    #if os(macOS)
    @Environment(\.openWindow) private var openWindow
    #endif
    var body: some Commands {
        // 2.5 Notion-Gefuehl: ⌘N = neue Seite (wie Notion); "Neues Fenster" (Mac) wandert auf ⇧⌘N
        CommandGroup(replacing: .newItem) {
            Button("Neue Seite") { hk?.newPage() }.keyboardShortcut("n", modifiers: .command).disabled(hk == nil)
            Button("Aus Vorlage …") { hk?.fromTemplate?() }.disabled(hk?.fromTemplate == nil)
            #if os(macOS)
            Divider()
            Button("Neues Fenster") { openWindow(id: HKScene.main) }.keyboardShortcut("n", modifiers: [.command, .shift])
            #endif
        }
        CommandGroup(before: .sidebar) {
            Button("Seitenleiste ein-/ausblenden") { hk?.toggleSidebar?() }.keyboardShortcut("\\", modifiers: .command).disabled(hk?.toggleSidebar == nil)
        }
        CommandMenu("Gehe zu") {
            Button("Zurück") { hk?.back() }.keyboardShortcut("[", modifiers: .command).disabled(hk == nil)
            Button("Vor") { hk?.forward() }.keyboardShortcut("]", modifiers: .command).disabled(hk == nil)
            Divider()
            Button("Seiten durchsuchen") { hk?.search() }.keyboardShortcut("k", modifiers: .command).disabled(hk == nil)
            Divider()
            Button("Heute") { hk?.today() }.keyboardShortcut("1", modifiers: .command).disabled(hk == nil)
            Button("Aufgaben") { hk?.tasks() }.keyboardShortcut("2", modifiers: .command).disabled(hk == nil)
            Button("Termine") { hk?.events() }.keyboardShortcut("3", modifiers: .command).disabled(hk == nil)
            Button { hk?.graph?() } label: { Text("Graph", tableName: "Graph") }.keyboardShortcut("g", modifiers: [.command, .shift]).disabled(hk?.graph == nil)
            Divider()
            Button("Link zur Seite kopieren") { hk?.copyLink() }.keyboardShortcut("l", modifiers: .command).disabled(!(hk?.hasPage ?? false))
        }
    }
}

#if os(macOS)
/// Menueleiste: Aufgabe oder Notiz ablegen, ohne das Fenster zu wechseln (ueber den App-Schluessel)
struct QuickCaptureView: View {
    enum Kind: CaseIterable {
        case task, note
        var label: LocalizedStringKey { self == .task ? "Aufgabe" : "Notiz" }
    }
    @State private var kind: Kind = .task
    @State private var text = ""
    @State private var withDue = false
    @State private var due = Date()
    @State private var status: String?
    @State private var busy = false
    @FocusState private var focus: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text("Saga").font(.headline)
                Spacer()
                Picker("", selection: $kind) { ForEach(Kind.allCases, id: \.self) { Text($0.label) } }.pickerStyle(.segmented).labelsHidden().fixedSize()
            }
            TextField(kind == .task ? "Neue Aufgabe …" : "Notiz – erste Zeile wird der Titel", text: $text, axis: .vertical)
                .lineLimit(kind == .task ? 1...3 : 3...8).textFieldStyle(.roundedBorder).focused($focus)
                .onSubmit { if kind == .task { save() } }
            if kind == .task {
                HStack {
                    Toggle("Fällig", isOn: $withDue).toggleStyle(.checkbox)
                    if withDue { DatePicker("", selection: $due, displayedComponents: .date).labelsHidden() }
                }
            }
            HStack {
                if let status { Text(status).font(.caption).foregroundStyle(status.hasPrefix("✓") ? .green : .red).lineLimit(2) }
                Spacer()
                Button(kind == .task ? "Aufgabe anlegen" : "In den Eingang") { save() }
                    .keyboardShortcut(.return, modifiers: kind == .task ? [] : .command)
                    .disabled(busy || text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
            if !AppConfig.hasToken { Text("Kein App-Schlüssel – in der App unter Einstellungen eintragen.").font(.caption).foregroundStyle(.secondary) }
        }
        .padding(14).frame(width: 340)
        .onAppear { focus = true; status = nil }
    }

    private func save() {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines); guard !t.isEmpty, !busy else { return }
        busy = true
        Task {
            do {
                if kind == .task { try await API.capture(kind: "task", title: t, text: "", url: nil, due: withDue ? due : nil) }
                else {
                    let parts = t.split(separator: "\n", maxSplits: 1)
                    try await API.capture(kind: "page", title: String(parts.first ?? "").prefix(80).description, text: parts.count > 1 ? String(parts[1]) : "", url: nil)
                }
                status = kind == .task ? String(localized: "✓ Aufgabe angelegt") : String(localized: "✓ Im Eingang abgelegt"); text = ""; withDue = false
                NotificationCenter.default.post(name: .hkCaptured, object: nil)
            } catch { status = error.localizedDescription }
            busy = false
        }
    }
}
#endif
