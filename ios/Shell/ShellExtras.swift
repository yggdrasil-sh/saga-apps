import AppKit
import CoreSpotlight
import Sparkle
import SwiftUI
import UniformTypeIdentifiers

// Etappe 2 der Mac-Huelle: Spotlight, Schnellerfassung in der Menueleiste, Selbst-Update (Sparkle).

// MARK: - Spotlight: Seiten in der Systemsuche (Klick -> saga://page/<id> bzw. /notes#<id>)

/// Seitenliste ueber den App-Schluessel (GET /api/app/pages, wie die Widgets) - unabhaengig vom Web-Inhalt.
/// Abgleich im Hintergrund, hoechstens alle 10 Minuten (ausser nach Anmeldung), ganz ersetzt (Liste ist klein).
@MainActor
final class ShellSpotlight {
    static let shared = ShellSpotlight()
    private nonisolated static let domain = "pages"
    private var last = Date.distantPast
    private var running = false
    private var epoch = 0

    func refresh(force: Bool) {
        guard AppConfig.hasToken, !running, force || Date().timeIntervalSince(last) > 600 else { return }
        running = true; last = Date()
        let ep = epoch
        Task {
            defer { running = false }
            guard let pages = try? await API.pages() else { return }
            let list = pages.map { ShellLogic.IndexPage(id: $0.id, title: $0.title, icon: $0.icon, kind: $0.kind, parent: $0.parent_id) }
            let entries = ShellLogic.indexEntries(list, untitled: String(localized: "Unbenannt"), page: String(localized: "Seite"),
                                                  database: String(localized: "Datenbank"))
            guard ep == epoch else { return }                                // inzwischen abgemeldet
            await Self.replace(entries) { await MainActor.run { ep == self.epoch } }
            if ep != epoch { clear() }                                       // waehrend des Eintragens abgemeldet: sicher leeren
        }
    }

    func clear() {
        epoch += 1; last = .distantPast
        Task.detached(priority: .utility) {
            try? await CSSearchableIndex.default().deleteSearchableItems(withDomainIdentifiers: [Self.domain])
        }
    }

    private nonisolated static func replace(_ entries: [ShellLogic.IndexEntry], valid: @Sendable () async -> Bool) async {
        let idx = CSSearchableIndex.default()
        try? await idx.deleteSearchableItems(withDomainIdentifiers: [domain])
        var i = 0
        while i < entries.count {
            guard await valid() else { return }                              // je Stapel: noch dasselbe Konto?
            let items = entries[i..<min(i + 500, entries.count)].map { e -> CSSearchableItem in
                let a = CSSearchableItemAttributeSet(contentType: .text)
                a.title = e.title; a.contentDescription = e.desc
                if let d = e.display { a.displayName = d }
                return CSSearchableItem(uniqueIdentifier: e.id, domainIdentifier: domain, attributeSet: a)
            }
            try? await idx.indexSearchableItems(items)
            i += 500
        }
    }
}

// MARK: - Schnellerfassung in der Menueleiste (wie QuickCaptureView der nativen App, ueber den App-Schluessel)

struct ShellCaptureView: View {
    enum Kind: CaseIterable { case task, note }
    @State private var kind: Kind = .task
    @State private var text = ""
    @State private var withDue = false
    @State private var due = Date()
    @State private var status: String?
    @State private var ok = false
    @State private var busy = false
    @FocusState private var focus: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text("Saga").font(.headline)
                Spacer()
                Picker("", selection: $kind) {
                    Text(String(localized: "Aufgabe")).tag(Kind.task)
                    Text(String(localized: "Notiz")).tag(Kind.note)
                }
                .pickerStyle(.segmented).labelsHidden().fixedSize()
            }
            TextField(kind == .task ? String(localized: "Neue Aufgabe …") : String(localized: "Notiz – erste Zeile wird der Titel"),
                      text: $text, axis: .vertical)
                .lineLimit(kind == .task ? 1...3 : 3...8).textFieldStyle(.roundedBorder).focused($focus)
                .onSubmit { if kind == .task { save() } }
            if kind == .task {
                HStack {
                    Toggle(String(localized: "Fällig"), isOn: $withDue).toggleStyle(.checkbox)
                    if withDue { DatePicker("", selection: $due, displayedComponents: .date).labelsHidden() }
                }
            }
            HStack {
                if let status { Text(status).font(.caption).foregroundStyle(ok ? .green : .red).lineLimit(2) }
                Spacer()
                Button(kind == .task ? String(localized: "Aufgabe anlegen") : String(localized: "In den Eingang")) { save() }
                    .keyboardShortcut(.return, modifiers: kind == .task ? [] : .command)
                    .disabled(busy || !AppConfig.hasToken || text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
            if !AppConfig.hasToken {
                Text(String(localized: "Erst in Saga anmelden.")).font(.caption).foregroundStyle(.secondary)
            }
            Divider()
            Button(String(localized: "Saga öffnen")) {
                NSApp.activate()
                for w in NSApp.windows where w.canBecomeMain { w.makeKeyAndOrderFront(nil) }
            }
            .buttonStyle(.link)
        }
        .padding(14).frame(width: 340)
        .onAppear { focus = true; status = nil }
    }

    private func save() {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty, !busy else { return }
        busy = true
        Task {
            do {
                if kind == .task { try await API.capture(kind: "task", title: t, text: "", url: nil, due: withDue ? due : nil) }
                else {
                    let parts = t.split(separator: "\n", maxSplits: 1)
                    try await API.capture(kind: "page", title: String(String(parts.first ?? "").prefix(80)),
                                          text: parts.count > 1 ? String(parts[1]) : "", url: nil)
                }
                ok = true
                status = kind == .task ? String(localized: "✓ Aufgabe angelegt") : String(localized: "✓ Im Eingang abgelegt")
                text = ""; withDue = false
                ShellModel.shared.reloadWidgets(force: false)
            } catch { ok = false; status = error.localizedDescription }
            busy = false
        }
    }
}

// MARK: - Selbst-Update ueber Sparkle (wie App/AppUpdater.swift; aktiv nur, wenn das Bauskript Appcast und Schluessel
// in die Info.plist eingetragen hat - UpdateLogic.isEnabled; Kanal wie die DMG: appcast.xml bzw. appcast-rc.xml)

@MainActor
final class ShellUpdater: NSObject, ObservableObject, SPUUpdaterDelegate {
    static let shared = ShellUpdater()
    let enabled = UpdateLogic.isEnabled(Bundle.main.infoDictionary ?? [:])
    @Published private(set) var canCheck = false
    @Published private(set) var autoCheck = false
    private var controller: SPUStandardUpdaterController?

    func start() {
        guard enabled, controller == nil else { return }
        let c = SPUStandardUpdaterController(startingUpdater: true, updaterDelegate: self, userDriverDelegate: nil)
        controller = c
        c.updater.publisher(for: \.canCheckForUpdates).receive(on: RunLoop.main).assign(to: &$canCheck)
        c.updater.publisher(for: \.automaticallyChecksForUpdates).receive(on: RunLoop.main).assign(to: &$autoCheck)
    }
    func check() { controller?.checkForUpdates(nil) }
    /// App-Einstellungen: „Automatisch nach Updates suchen“ (Sparkle merkt sich die Wahl selbst, Vorgabe aus SUEnableAutomaticChecks)
    func setAutoCheck(_ on: Bool) { controller?.updater.automaticallyChecksForUpdates = on }
}

struct ShellUpdateButton: View {
    @ObservedObject var updater = ShellUpdater.shared
    var body: some View {
        if updater.enabled {
            Button(String(localized: "Nach Updates suchen …")) { updater.check() }.disabled(!updater.canCheck)
        }
    }
}
