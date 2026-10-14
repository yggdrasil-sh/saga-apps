import SwiftUI
import WebKit
import AuthenticationServices
import WidgetKit
import CoreSpotlight

/// Nativer App-Rahmen wie bei Notion: Seitenliste/Suche/Heute nativ, die Seite selbst als Editor (Web, eingebettet).
struct RootView: View {
    @ObservedObject var store: Store
    @State private var selection: Int?
    @State private var query = ""
    @State private var results: [Page] = []
    @State private var showSettings = false
    @State private var showPalette = false
    @State private var showTrash = false
    #if os(macOS)
    @Environment(\.openSettings) private var openSettings
    #endif
    @StateObject private var nav = NavModel()
    @Environment(\.scenePhase) private var phase
    @AppStorage("hk.peekWidth") private var peekWidth: Double = 520
    @FocusState private var searchFocused: Bool
    @State private var newAsk: URL?                                  // fremder Link "neuen Eintrag anlegen": erst nach Rueckfrage

    var body: some View {
        NavigationSplitView {
            Sidebar(store: store, selection: $selection, query: query, results: results)
                .navigationSplitViewColumnWidth(min: 220, ideal: 260, max: 380)      // wie Notion, nicht abgeschnitten
                .navigationTitle("Saga")
                .searchable(text: $query, prompt: "Seiten durchsuchen")
                .searchFocused($searchFocused)
                .task(id: query) {
                    try? await Task.sleep(for: .milliseconds(250))          // erst nach kurzer Tipp-Pause suchen
                    results = await store.search(query)
                }
                .refreshable { await store.refresh() }
                .toolbar {
                    ToolbarItem(placement: .primaryAction) {
                        Button { Task { if let id = await store.newPage() { selection = id } } } label: { Label("Neue Seite", systemImage: "square.and.pencil") }
                    }
                    #if os(iOS)
                    ToolbarItem(placement: .topBarLeading) {
                        Button { showSettings = true } label: { Label("Einstellungen", systemImage: "gearshape") }
                    }
                    #endif
                }
        } detail: {
            ZStack(alignment: .trailing) {                       // Peek liegt UEBER der Seite (wie im Web) - die Seite bleibt, wie sie ist
                NavigationStack(path: $nav.path) {
                    Group {
                        if selection == -1 { TasksScreen() } else if selection == -2 { EventsScreen() } else if selection == -3 { ServicesScreen() }
                    else if let id = selection { NativePageScreen(id: id).id(id) } else { TodayScreen(store: store) }
                    }
                    .navigationDestination(for: PageRoute.self) { r in
                        if r.web { PageScreen(id: r.id, store: store) } else { NativePageScreen(id: r.id).id(r.id) }
                    }
                }
                if let p = nav.peek, !p.center {                 // Peek rechts: feste Breite (kein Inspector -> kein Layout-Kreislauf)
                    HStack(spacing: 0) {
                        PeekResizer(width: $peekWidth)
                        PeekView(id: p.id).environmentObject(store).environmentObject(nav)
                            .frame(width: CGFloat(peekWidth))
                            .background(.background)                 // deckend: die Seite darunter scheint nicht durch
                    }
                    .compositingGroup()
                    .shadow(color: .black.opacity(0.35), radius: 18, x: -6)
                    .transition(.move(edge: .trailing))
                }
            }
            .animation(.snappy(duration: 0.25), value: nav.peek)
        }
        .onChange(of: selection) { _, _ in nav.path = []; nav.peek = nil }
        .onChange(of: nav.peek) { old, new in if old != nil && new == nil { store.dataChanged() } }   // Peek zu -> Ansichten auffrischen
        // Peek mittig als Fenster (rechts: eigene Seitenleiste im detail-Bereich)
        .sheet(item: Binding(get: { nav.peek.flatMap { $0.center ? $0 : nil } }, set: { if $0 == nil { nav.peek = nil } })) { p in
            PeekView(id: p.id).environmentObject(store).environmentObject(nav)
                #if os(macOS)
                .frame(minWidth: 760, idealWidth: 900, minHeight: 600, idealHeight: 760)
                #endif
        }
        .overlay(alignment: .bottom) {
            if let u = store.undo {
                HStack(spacing: 14) {
                    Text("„\(u.title)“ in den Papierkorb verschoben").lineLimit(1)
                    Button("Rückgängig") { Task { await store.restore(u.id) } }.bold()
                }
                .font(.subheadline).padding(.horizontal, 16).padding(.vertical, 10)
                .background(.regularMaterial, in: Capsule()).padding(.bottom, 24)
                .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .animation(.snappy, value: store.undo?.id)
        .environmentObject(store)
        .environmentObject(nav)
        .environment(\.openURL, OpenURLAction { url in          // Seitenverweise im Text -> nativ oeffnen
            if url.scheme == "hkpage", let id = Int(url.host ?? "") { nav.push(PageRoute(id: id)); return .handled }
            if url.scheme == "hkdate" { return .handled }                  // @-Datum im Text: nichts oeffnen
            return .systemAction
        })
        .focusedSceneValue(\.hk, HKCommands(                            // Menue-Befehle (Mac/iPad-Tastatur)
            back: { nav.peek = nil; nav.back() }, forward: { nav.goForward() },
            newPage: { Task { if let id = await store.newPage() { selection = id } } },
            search: { showPalette = true },
            copyLink: { if let id = currentPageID { copyPageLink(id) } }, hasPage: currentPageID != nil,
            today: { selection = nil }, tasks: { selection = -1 }, events: { selection = -2 }))
        .onReceive(NotificationCenter.default.publisher(for: .hkCaptured)) { _ in Task { await store.refresh() } }   // Menueleiste
        .onOpenURL { url in openLink(url) }                              // Links aus den Widgets / Kurzbefehlen
        .onContinueUserActivity(CSSearchableItemActionType) { a in        // Spotlight-Treffer
            if let id = SpotlightIndex.pageID(a) { nav.peek = nil; nav.path = []; selection = id }
        }
        .onChange(of: phase) { _, p in
            if p == .active { Task { await store.refresh() } }                     // im Web angelegte Seiten
            if p == .background { WidgetCenter.shared.reloadAllTimelines() }      // Widgets zeigen sofort den neuen Stand
        }
        .task {                                                          // alle 60 s, solange die App vorne ist (ein Takt fuer alle Fenster)
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(60))
                if phase == .active { await store.periodicRefresh() }
            }
        }
        .task {
            if selection == nil, let home = store.homePage { selection = home }        // Startseite sofort (aus dem Zwischenspeicher)
            await store.refresh()
            if let home = store.homePage, !store.pages.isEmpty, store.page(home) == nil { store.setHome(nil); if selection == home { selection = nil } }   // geloescht -> vergessen
            #if DEBUG
            if let v = UserDefaults.standard.string(forKey: "hkOpen"), let id = Int(v) { selection = id }   // nur fuer Tests (Startparameter -hkOpen <id>)
            if UserDefaults.standard.string(forKey: "hkSettings") != nil { showSettings = true }            // nur fuer Tests: Einstellungen oeffnen
            if let v = UserDefaults.standard.string(forKey: "hkPeek"), let id = Int(v) {                 // nur fuer Tests: Peek oeffnen
                try? await Task.sleep(for: .seconds(2)); nav.open(id, mode: UserDefaults.standard.string(forKey: "hkPeekMode") ?? "side")
            }
            #endif
        }
        .sheet(isPresented: $showPalette) { CommandPalette(actions: paletteActions) { id in nav.peek = nil; nav.path = []; selection = id }.environmentObject(store) }
        .safeAreaInset(edge: .top, spacing: 0) {                         // Server ist neuer, als diese App kann
            if store.appTooOld, let m = store.minApp {
                Label("Diese App ist zu alt für den Server (ab \(m)) – bitte aktualisieren.", systemImage: "arrow.down.app")
                    .font(.footnote).padding(.vertical, 6).frame(maxWidth: .infinity).background(.orange.opacity(0.25))
            }
        }
        .sheet(isPresented: $showTrash) { TrashSheet().environmentObject(store) }
        // Kopplungslink: Rueckfrage hier - oder im Anmeldebildschirm, solange der vorne liegt
        // (nur hier zuruecksetzen, solange die Rueckfrage hier liegt: sonst loescht das Ausblenden beim Wechsel zum
        // Anmeldebildschirm den Link - Kaltstart per QR-Code im abgemeldeten Zustand zeigte dann gar nichts)
        .modifier(PairConfirm(ask: Binding(get: { store.needsLogin ? nil : store.pendingPair }, set: { if !store.needsLogin { store.pendingPair = $0 } }), store: store))
        .confirmationDialog("Neuen Eintrag anlegen?", isPresented: Binding(get: { newAsk != nil }, set: { if !$0 { newAsk = nil } }), titleVisibility: .visible) {
            Button("Anlegen") { if let u = newAsk { newAsk = nil; createFromLink(u) } }
            Button("Abbrechen", role: .cancel) { newAsk = nil }
        } message: { Text("Ein Link möchte in Saga einen neuen Eintrag anlegen.") }
        .sheet(isPresented: $showSettings) { NavigationStack { SettingsView(onLogout: { Task { await store.logout() } }) }.environmentObject(store) }
        #if os(iOS)
        .fullScreenCover(isPresented: $store.needsLogin) { LoginScreen(store: store) { Task { await store.refresh() } } }
        #else
        .sheet(isPresented: $store.needsLogin) { LoginScreen(store: store) { Task { await store.refresh() } }.frame(minWidth: 420, minHeight: 420) }
        #endif
    }
}

/// Link zur Seite (Web-Adresse) in die Zwischenablage
func HlidskjalfApp_copyPageLink(_ id: Int) {
    let u = AppConfig.server.absoluteString + "/notes#\(id)"
    #if os(macOS)
    NSPasteboard.general.clearContents(); NSPasteboard.general.setString(u, forType: .string)
    #else
    UIPasteboard.general.string = u
    #endif
}

extension RootView {
    /// Befehle der Palette (⌘K, mit ">" nur Befehle) - wie im Web
    var paletteActions: [CommandPalette.Action] {
        var a: [CommandPalette.Action] = [
            .init(id: "new", icon: "doc.badge.plus", title: String(localized: "Neue Seite")) { Task { if let id = await store.newPage() { selection = id } } },
            .init(id: "today", icon: "sun.max", title: String(localized: "Heute")) { selection = nil },
            .init(id: "tasks", icon: "checklist", title: String(localized: "Aufgaben")) { selection = -1 },
            .init(id: "events", icon: "calendar", title: String(localized: "Termine")) { selection = -2 },
            .init(id: "trash", icon: "trash", title: String(localized: "Papierkorb")) { showTrash = true },
            .init(id: "services", icon: "square.grid.3x3", title: String(localized: "Dienste")) { selection = -3 },
            .init(id: "refresh", icon: "arrow.clockwise", title: String(localized: "Aktualisieren")) { Task { await store.refresh() } },
            .init(id: "back", icon: "chevron.left", title: String(localized: "Zurück")) { nav.peek = nil; nav.back() },
            .init(id: "fwd", icon: "chevron.right", title: String(localized: "Vor")) { nav.goForward() },
            .init(id: "search", icon: "sidebar.left", title: String(localized: "In der Seitenleiste suchen")) { searchFocused = true },
        ]
        if let id = currentPageID { a.append(.init(id: "link", icon: "link", title: String(localized: "Link zur Seite kopieren")) { copyPageLink(id) }) }
        #if os(macOS)
        a.append(.init(id: "settings", icon: "gearshape", title: String(localized: "Einstellungen")) { openSettings() })
        #else
        a.append(.init(id: "settings", icon: "gearshape", title: String(localized: "Einstellungen")) { showSettings = true })
        #endif
        return a
    }

    /// Seite, die gerade vorne ist: Peek, sonst oberste im Stapel, sonst die gewaehlte
    var currentPageID: Int? { nav.peek?.id ?? nav.path.last?.id ?? (selection.flatMap { $0 > 0 ? $0 : nil }) }
    func copyPageLink(_ id: Int) { HlidskjalfApp_copyPageLink(id) }
    /// hlidskjalf:// bzw. saga:// - page/12 · today · tasks · events · new-task · new?db=7&view=tab · new?parent=12 · pair?u=…&c=…
    func openLink(_ url: URL) {
        guard url.scheme == "hlidskjalf" || url.scheme == "saga" else { return }
        let arg = url.pathComponents.first { $0 != "/" }
        nav.peek = nil; nav.path = []
        switch url.host {
        case "pair":                                                       // QR-Code mit der Kamera-App gescannt: erst nachfragen
            if let (srv, code) = Store.parsePairLink(url.absoluteString) { store.pendingPair = PairRequest(server: srv, code: code) }
            else { store.error = String(localized: "Das ist kein Saga-QR-Code.") }
        case "page": if let id = Int(arg ?? "") { selection = id }
        case "tasks": selection = -1
        case "events": selection = -2
        case "new-task": selection = -1; nav.wantQuickAdd = true
        case "new":                                                        // eigene Widgets duerfen ohne Rueckfrage anlegen
            let k = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems?.first { $0.name == "k" }?.value
            if k == AppConfig.linkSecret { createFromLink(url) } else { newAsk = url }
        default: selection = nil                                          // "today" (und alte Links)
        }
    }

    /// new?db=7&view=tab (Eintrag passend zu den Filtern der Ansicht) · new?parent=12 (Unterseite)
    func createFromLink(_ url: URL) {
        let q = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []
        func qi(_ n: String) -> String? { q.first { $0.name == n }?.value }
        Task {
            if let db = qi("db").flatMap(Int.init) {
                let m = DBModel(dbID: db); await m.load(store)
                let view = m.views.first { ($0["id"] as? String) == qi("view") } ?? m.views.first
                if let id = await m.add(store, props: m.defaults(for: view)) { selection = id }
            } else if let p = qi("parent").flatMap(Int.init), let id = await store.newPage(parent: p) {
                selection = id
            }
        }
    }
}

/// Kopplungslink (QR-Code, Link in Mail/Webseite): nie still koppeln - Zielserver zeigen, Wechsel deutlich machen
struct PairRequest: Identifiable, Equatable {
    let server: URL; let code: String
    var id: String { code }
    var host: String { AppConfig.displayHost(server) }
}

struct PairConfirm: ViewModifier {
    @Binding var ask: PairRequest?
    @ObservedObject var store: Store
    @State private var error: String?
    @State private var shown: PairRequest?
    func body(content: Content) -> some View {
        content
            // Kurz warten, bevor die Rueckfrage erscheint: beim Start per Link entscheidet sich erst noch, ob der
            // Anmeldebildschirm nach vorne kommt - eine Rueckfrage mitten in diesem Wechsel ging verloren.
            .task(id: ask) {
                shown = nil
                guard let a = ask else { return }
                try? await Task.sleep(for: .milliseconds(500))
                if !Task.isCancelled { shown = a }
            }
            .alert("Mit diesem Server koppeln?", isPresented: Binding(get: { shown != nil }, set: { if !$0 { shown = nil; ask = nil } }), presenting: shown) { r in
                // Wer angemeldet ist, wird abgemeldet: "Koppeln" dann destruktiv (rot), sonst als Hauptaktion hervorgehoben
                Button("Koppeln", role: store.pairNotice(for: r.server) == PairNotice.none ? ButtonRole.confirm : ButtonRole.destructive) {
                    ask = nil; Task { do { try await store.pair(server: r.server, code: r.code) } catch { self.error = error.localizedDescription } }
                }
                .keyboardShortcut(.defaultAction)
                Button("Abbrechen", role: .cancel) { ask = nil }
            } message: { r in
                Text(verbatim: Self.message(r, store.pairNotice(for: r.server)))
            }
            .alert("Kopplung fehlgeschlagen", isPresented: Binding(get: { error != nil }, set: { if !$0 { error = nil } })) {
                Button("OK", role: .cancel) { error = nil }
            } message: { Text(error ?? "") }
    }
    /// Texte wie im Glossar ("Einheitliche Dialoge") und auf Android; die Abmelde-Warnung mit Warnzeichen
    /// (Alert-Texte lassen sich nicht einfaerben - rot ist dafuer der "Koppeln"-Knopf)
    static func message(_ r: PairRequest, _ notice: PairNotice) -> String {
        var parts = [String(localized: "Saga meldet sich bei \(r.host) an.")]
        switch notice {
        case .none: break
        case .otherServer(let h): parts.append("⚠️ " + String(localized: "Du bist gerade bei \(h) angemeldet und wirst dort abgemeldet."))
        case .sameServer(let name?): parts.append("⚠️ " + String(localized: "Du wirst als „\(name)“ abgemeldet und mit dem Konto aus dem QR-Code angemeldet."))
        case .sameServer(nil): parts.append("⚠️ " + String(localized: "Du wirst abgemeldet und mit dem Konto aus dem QR-Code angemeldet."))
        }
        parts.append(String(localized: "Kopple nur, wenn du diesen QR-Code oder Link gerade selbst in Saga erzeugt hast (Einstellungen → Apps & Geräte)."))
        return parts.joined(separator: "\n\n")
    }
}

struct TreeNode: Identifiable, Hashable {
    let page: Page; var children: [TreeNode]?
    var id: Int { page.id }
}

struct Sidebar: View {
    @ObservedObject var store: Store
    @Binding var selection: Int?
    let query: String
    let results: [Page]
    @State private var moveID: MoveTarget?
    @State private var showTrash = false

    private func sharedTree(_ parent: Int?) -> [TreeNode] {
        store.sharedChildren(of: parent).map { p in
            let kids = sharedTree(p.id)
            return TreeNode(page: p, children: kids.isEmpty ? nil : kids)
        }
    }
    private func tree(_ parent: Int?) -> [TreeNode] {
        store.children(of: parent).map { p in
            let kids = p.isDatabase ? [] : tree(p.id)
            return TreeNode(page: p, children: kids.isEmpty ? nil : kids)
        }
    }

    var body: some View {
        List(selection: $selection) {
            if !query.trimmingCharacters(in: .whitespaces).isEmpty {
                Section("Treffer") {
                    if results.isEmpty { Text("Keine Treffer").foregroundStyle(.secondary) }
                    ForEach(results) { p in
                        VStack(alignment: .leading, spacing: 2) {
                            if p.role != nil && p.role != "owner" {                    // fremder Treffer (1.3): ohne Seiten-Aktionen
                                PageRow(page: p, store: store)
                                if let o = p.owner?.name { Text("Geteilt von \(o)").font(.caption).foregroundStyle(.secondary) }
                            } else {
                                PageRow(page: p, store: store).pageActions(p, store: store, selection: $selection, moveID: $moveID)
                            }
                            if let s = p.snippet, !s.isEmpty { Text(s).font(.caption).foregroundStyle(.secondary).lineLimit(2) }
                        }.tag(p.id)
                    }
                }
            } else {
                Section {
                    Label("Aufgaben", systemImage: "checklist").badge(store.tasks.count).tag(-1)
                    Label("Termine", systemImage: "calendar").tag(-2)
                    Label("Dienste", systemImage: "square.grid.3x3").tag(-3)
                }
                TodaySection(store: store)
                if !store.favorites.isEmpty {
                    Section("Favoriten") { ForEach(store.favorites) { PageRow(page: $0, store: store).pageActions($0, store: store, selection: $selection, moveID: $moveID).tag($0.id) } }
                }
                let recent = store.recent.compactMap { store.page($0) }.prefix(5)
                if !recent.isEmpty {
                    Section("Zuletzt") {
                        ForEach(Array(recent)) { p in
                            if p.role != nil && p.role != "owner" { PageRow(page: p, store: store).tag(p.id) }   // fremde Seite (1.3): ohne Eigentuemer-Aktionen
                            else { PageRow(page: p, store: store).pageActions(p, store: store, selection: $selection, moveID: $moveID).tag(p.id) }
                        }
                    }
                }
                Section {
                    OutlineGroup(tree(nil), children: \.children) { n in
                        PageRow(page: n.page, store: store).pageActions(n.page, store: store, selection: $selection, moveID: $moveID).tag(n.page.id)
                            .draggable(String(n.page.id))                                    // ziehen: auf eine Seite = darunter einsortieren
                            .dropDestination(for: String.self) { ids, _ in dropPages(ids, into: n.page.id) }
                    }
                } header: {
                    Text("Seiten").frame(maxWidth: .infinity, alignment: .leading).contentShape(Rectangle())
                        .dropDestination(for: String.self) { ids, _ in dropPages(ids, into: nil) }        // auf "Seiten" = oberste Ebene
                }
                if !store.shared.isEmpty || store.shareOffHint {             // Mit mir geteilt (1.3)
                    Section {
                        if store.shareOffHint {
                            Text("Teilen ist auf diesem Server zurzeit abgeschaltet – mit dir geteilte Seiten sind nicht erreichbar.").font(.caption).foregroundStyle(.secondary)
                        }
                        OutlineGroup(sharedTree(nil), children: \.children) { n in
                            PageRow(page: n.page, store: store, ownerLine: n.page.root == true).tag(n.page.id)
                        }
                    } header: {
                        HStack(spacing: 6) {
                            Text("Mit mir geteilt")
                            let n = store.shared.filter { $0.unread == true }.count
                            if n > 0 {
                                Text(verbatim: "\(n)").font(.caption2.bold()).foregroundStyle(Color("OnAccent"))
                                    .padding(.horizontal, 6).padding(.vertical, 1).background(Color.accentColor, in: Capsule())
                                    .accessibilityLabel(n == 1 ? Text("1 Seite mit neuen Kommentaren") : Text("\(n) Seiten mit neuen Kommentaren"))
                            }
                        }
                    }
                }
                if !store.archivedPages.isEmpty {
                    Section {
                        DisclosureGroup("📦 Archiv (\(store.archivedPages.count))") {
                            ForEach(store.archivedPages) { p in
                                PageRow(page: p, store: store).tag(p.id)
                                    .contextMenu { Button { Task { await store.setArchived(p.id, false) } } label: { Label("Zurückholen", systemImage: "arrow.uturn.backward") } }
                            }
                        }
                    }
                }
                Section {
                    Button { showTrash = true } label: { Label("Papierkorb", systemImage: "trash") }.buttonStyle(.plain).foregroundStyle(.secondary)
                }
            }
            if let e = store.error { Text(e).font(.caption).foregroundStyle(.red) }
        }
        #if os(macOS)
        .listStyle(.sidebar)
        #else
        .listStyle(.insetGrouped)
        #endif
        .overlay { if store.loading && store.pages.isEmpty { ProgressView() } }
        .sheet(isPresented: $showTrash) { TrashSheet().environmentObject(store) }
        .sheet(item: $moveID) { m in MoveSheet(id: m.id) }
    }
}

extension Sidebar {
    /// Seiten im Baum verschieben (wie Ziehen im Web); der Server lehnt Unsinn ab (in sich selbst, in Datenbanken)
    @discardableResult
    func dropPages(_ ids: [String], into parent: Int?) -> Bool {
        let list = ids.compactMap(Int.init).filter { $0 != parent }
        guard !list.isEmpty else { return false }
        Task { for id in list { do { try await store.move(id, to: parent) } catch { store.error = error.localizedDescription } } }
        return true
    }
}

struct PageRow: View {
    let page: Page
    @ObservedObject var store: Store
    /// "Mit mir geteilt", oberste Seite: Eigentuemer klein darunter
    var ownerLine = false
    var body: some View {
        Label {
            HStack(spacing: 6) {
                VStack(alignment: .leading, spacing: 0) {
                    Text(page.label).lineLimit(1)
                    if ownerLine, let o = page.owner?.name { Text(verbatim: o).font(.caption).foregroundStyle(.secondary).lineLimit(1) }
                }
                Spacer(minLength: 0)
                if page.unread == true {                                    // neue Kommentare von anderen
                    Circle().fill(Color.accentColor).frame(width: 7, height: 7).accessibilityLabel(Text("Neue Kommentare"))
                }
                if page.shared == true && page.role == nil {                // eigene Seite mit Freigaben
                    Image(systemName: "person.2.fill").font(.caption2).foregroundStyle(.secondary).accessibilityLabel(Text("Geteilt"))
                }
            }
        } icon: { PageIcon(icon: page.icon, store: store, fallback: page.isDatabase ? "🗃️" : "📄") }
    }
}

/// Emoji oder eigenes Bild (/files/..., auch GIF - hier das erste Bild) als Seiten-Icon
struct PageIcon: View {
    let icon: String?
    @ObservedObject var store: Store
    var fallback = "📄"
    @State private var data: Data?
    var body: some View {
        Group {
            if let icon, icon.hasPrefix("/files/") {
                if let data, let img = platformImage(data) { img.resizable().scaledToFill() } else { Color.secondary.opacity(0.2) }
            } else {
                Text(icon?.isEmpty == false ? icon! : fallback)
            }
        }
        .frame(width: 22, height: 22).clipShape(RoundedRectangle(cornerRadius: 4))
        .task(id: icon) { if let icon, icon.hasPrefix("/files/") { data = await store.imageData(RemoteImage.sized(icon, 160)) } }
    }
    private func platformImage(_ d: Data) -> Image? {
        #if os(iOS)
        return UIImage(data: d).map { Image(uiImage: $0) }
        #else
        return NSImage(data: d).map { Image(nsImage: $0) }
        #endif
    }
}

struct TodaySection: View {
    @ObservedObject var store: Store
    @State private var editingTask: OpenTask?
    @State private var editingEvent: CalEvent?
    var body: some View {
        Section("Heute") {
            if store.events.isEmpty && store.tasks.isEmpty { Text("Nichts geplant 🎉").foregroundStyle(.secondary) }
            ForEach(store.events.prefix(4)) { e in Button { editingEvent = e } label: { EventRow(e: e) }.buttonStyle(.plain) }
            ForEach(todayTasks) { t in TaskRow(task: t) { editingTask = t } }
        }
        .sheet(item: $editingTask) { t in TaskEditor(task: t).environmentObject(store) }
        .sheet(item: $editingEvent) { e in EventEditor(event: e).environmentObject(store) }
    }
    /// Heute/ueberfaellig + ohne Datum (hoechstens 5)
    private var todayTasks: [OpenTask] {
        let today = isoToday()
        return Array(store.tasks.filter { t -> Bool in let d = t.due ?? ""; return d.isEmpty || d <= today }.prefix(5))
    }
    private func isoToday() -> String { let f = DateFormatter(); f.dateFormat = "yyyy-MM-dd"; return f.string(from: .now) }
}

/// Eine Seite: Editor als Web-Ansicht im schlanken Einbettungsmodus (ohne Web-Kopfzeile/-Seitenleiste)
struct PageScreen: View {
    let id: Int
    @ObservedObject var store: Store
    @StateObject private var model: WebModel
    init(id: Int, store: Store) {
        self.id = id; self.store = store
        _model = StateObject(wrappedValue: WebModel(path: "/notes?embed=1#\(id)", padTop: false))
    }
    var body: some View {
        WebView(model: model)
            .navigationTitle(store.page(id)?.label ?? "")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            .ignoresSafeArea(.container, edges: .bottom)
            #endif
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    Menu {
                        Button { model.reload() } label: { Label("Neu laden", systemImage: "arrow.clockwise") }
                        if let u = URL(string: AppConfig.server.absoluteString + "/notes#\(id)") {
                            ShareLink(item: u) { Label("Link teilen", systemImage: "square.and.arrow.up") }
                            Link(destination: u) { Label("Im Browser öffnen", systemImage: "safari") }
                        }
                    } label: { Image(systemName: "ellipsis.circle") }.accessibilityLabel("Seitenmenü")
                }
            }
            .onAppear { store.visited(id) }
            .onDisappear { Task { await store.refresh() } }     // Titel/Icon-Aenderungen in die Liste uebernehmen
    }
}

struct TodayScreen: View {
    @ObservedObject var store: Store
    var body: some View {
        List { TodaySection(store: store) }
            .navigationTitle("Heute")
    }
}

/// Anmeldung ueber das System-Anmeldefenster (Passkey, Face ID/Touch ID, Passwort + TOTP)
struct LoginScreen: View {
    @ObservedObject var store: Store
    var onDone: () -> Void
    @Environment(\.webAuthenticationSession) private var webAuth
    @State private var busy = false
    @State private var error = ""
    @State private var server = AppConfig.serverString
    @State private var showScanner = false
    @State private var pairLink = ""
    @State private var askSwitch: URL?                     // anderer Server vorgemerkt, bisherige Anmeldung vorhanden
    var body: some View {
        VStack(spacing: 18) {
            Spacer()
            Image("AppLogo").resizable().scaledToFit().frame(width: 88, height: 88)
                .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous)).accessibilityHidden(true)
            Text("Saga").font(.largeTitle.bold())
            Text("Anmelden mit Passkey, Face ID oder Passwort").foregroundStyle(.secondary).multilineTextAlignment(.center)
            VStack(alignment: .leading, spacing: 4) {
                Text("Server").font(.caption).foregroundStyle(.secondary)
                TextField("https://saga.example.org", text: $server)
                    #if os(iOS)
                    .keyboardType(.URL).textInputAutocapitalization(.never)
                    #endif
                    .autocorrectionDisabled().textFieldStyle(.roundedBorder)
                    .onSubmit { Task { await login() } }
            }.frame(maxWidth: 320)
            Button { Task { await login() } } label: { Text(busy ? "Anmelden …" : "Anmelden").frame(maxWidth: 260) }
                .prominentGold().controlSize(.large).disabled(busy)
            #if os(iOS)
            Button { showScanner = true } label: { Label("Mit QR-Code anmelden", systemImage: "qrcode.viewfinder").frame(maxWidth: 260) }
                .buttonStyle(.bordered).controlSize(.large).disabled(busy)
            Text("Den QR-Code zeigt Saga im Browser unter 👤 → Einstellungen → „Apps & Geräte“.").font(.footnote).foregroundStyle(.secondary).multilineTextAlignment(.center)
            #else
            HStack {
                TextField("Kopplungslink aus dem QR-Code (…/app/pair?c=…)", text: $pairLink).textFieldStyle(.roundedBorder)
                Button("Koppeln") { Task { await pair(pairLink) } }.disabled(pairLink.isEmpty || busy)
            }.frame(maxWidth: 420)
            #endif
            if !error.isEmpty { Text(error).font(.footnote).foregroundStyle(.red).multilineTextAlignment(.center) }
            Spacer()
        }
        .padding(30)
        .interactiveDismissDisabled()
        .modifier(PairConfirm(ask: $store.pendingPair, store: store))
        .confirmationDialog("Server wechseln?", isPresented: Binding(get: { askSwitch != nil }, set: { if !$0 { askSwitch = nil } }), titleVisibility: .visible) {
            // abgemeldet wird erst, wenn die Anmeldung beim neuen Server geklappt hat
            Button("Abmelden und wechseln", role: .destructive) { if let u = askSwitch { askSwitch = nil; Task { await signIn(u) } } }
            Button("Abbrechen", role: .cancel) { askSwitch = nil; server = AppConfig.serverString }
        } message: {
            Text("Du wirst vom bisherigen Server abgemeldet. Anmeldung, Schlüssel und Zwischenspeicher auf diesem Gerät werden gelöscht.")
        }
        .onChange(of: store.needsLogin) { _, v in if !v { server = AppConfig.serverString } }
        #if os(iOS)
        .sheet(isPresented: $showScanner) {
            QRScannerSheet { text in showScanner = false; Task { await pair(text) } }
        }
        #endif
    }
    /// Server aus dem Feld nur vormerken - uebernommen wird er erst nach erfolgreicher Anmeldung (Store.exchange).
    /// Bisher bei einem anderen Server angemeldet (Schluessel vorhanden) -> vorher nachfragen.
    private func login() async {
        error = ""
        switch LoginStep.plan(server, currentOrigin: AppConfig.serverOrigin, hasKeys: DeviceKey.stored() != nil || AppConfig.hasToken) {
        case .invalid: error = String(localized: "Bitte eine https-Adresse eingeben, z. B. https://saga.example.org")
        case .confirmSwitch(let u): server = u.absoluteString; askSwitch = u
        case .signIn(let u): server = u.absoluteString; await signIn(u)
        }
    }
    /// Gescannter/eingefuegter Kopplungslink: Ziel zeigen und bestaetigen lassen (wie beim Link von aussen)
    private func pair(_ text: String) async {
        error = ""
        guard let (srv, code) = Store.parsePairLink(text) else { error = String(localized: "Das ist kein Saga-QR-Code."); return }
        store.pendingPair = PairRequest(server: srv, code: code)
    }
    /// Browser-Anmeldung gegen den vorgemerkten Server; Server + PKCE-Geheimnis gehoeren zu genau diesem Versuch
    private func signIn(_ target: URL) async {
        busy = true; error = ""; defer { busy = false }
        let (verifier, challenge) = Store.pkce()
        guard let url = URL(string: target.absoluteString + "/app/login?challenge=" + challenge) else { return }
        do {
            let cb = try await webAuth.authenticate(using: url, callbackURLScheme: "hlidskjalf", preferredBrowserSession: .shared)
            let items = URLComponents(url: cb, resolvingAgainstBaseURL: false)?.queryItems ?? []
            if items.first(where: { $0.name == "error" })?.value == "cancelled" { return }   // im Browser abgebrochen
            guard let code = items.first(where: { $0.name == "code" })?.value else {
                error = String(localized: "Keine Antwort vom Server"); return
            }
            try await store.exchange(server: target, code: code, verifier: verifier)   // erst hier wird der Server gewechselt
            onDone()
        } catch let e as ASWebAuthenticationSessionError where e.code == .canceledLogin {
            // abgebrochen - nichts tun
        } catch { self.error = error.localizedDescription }
    }
}


struct MoveTarget: Identifiable {
    let id: Int
    var key = ""
    init(id: Int) { self.id = id }
    init(idString: String) { self.id = idString.hashValue; self.key = idString }
}

extension View {
    /// Aktionen fuer eine Seite in der Liste: Rechtsklick / langes Druecken, auf dem iPhone auch Wischen
    func pageActions(_ p: Page, store: Store, selection: Binding<Int?>, moveID: Binding<MoveTarget?>) -> some View {
        self
            .contextMenu {
                Button { Task { try? await store.patch(p.id, ["pinned": !(p.pinned ?? false)]) } } label: {
                    Label(p.pinned == true ? "Aus Favoriten entfernen" : "Zu Favoriten", systemImage: p.pinned == true ? "star.slash" : "star")
                }
                if !p.isDatabase {
                    Button { Task { if let n = await store.newPage(parent: p.id) { selection.wrappedValue = n } } } label: { Label("Neue Unterseite", systemImage: "doc.badge.plus") }
                }
                Button { moveID.wrappedValue = MoveTarget(id: p.id) } label: { Label("Verschieben nach …", systemImage: "arrow.turn.up.right") }
                Button { Task { if let n = await store.duplicate(p.id) { selection.wrappedValue = n } } } label: { Label("Duplizieren", systemImage: "plus.square.on.square") }
                Button { Task { if selection.wrappedValue == p.id { selection.wrappedValue = nil }; await store.setArchived(p.id, true) } } label: { Label("Archivieren", systemImage: "archivebox") }
                Divider()
                Button(role: .destructive) { Task { if selection.wrappedValue == p.id { selection.wrappedValue = nil }; _ = await store.trash(p.id) } } label: { Label("In den Papierkorb", systemImage: "trash") }
            }
            #if os(iOS)
            .swipeActions(edge: .trailing) {
                Button(role: .destructive) { Task { if selection.wrappedValue == p.id { selection.wrappedValue = nil }; _ = await store.trash(p.id) } } label: { Label("Papierkorb", systemImage: "trash") }
            }
            .swipeActions(edge: .leading) {
                Button { Task { try? await store.patch(p.id, ["pinned": !(p.pinned ?? false)]) } } label: { Label("Favorit", systemImage: p.pinned == true ? "star.slash" : "star.fill") }.tint(.yellow)
            }
            #endif
    }
}


/// Seite als Peek (rechts oder mittig): eigene Kopfzeile im Inhalt, KEINE eigene Fensterleiste/Navigation
struct PeekView: View {
    let id: Int
    var body: some View {
        NativePageScreen(id: id, inPeek: true).id(id)
            .frame(maxHeight: .infinity)
    }
}


/// Trennlinie zwischen Seite und Peek - ziehen aendert die Breite (360-900 pt, wird gemerkt)
struct PeekResizer: View {
    @Binding var width: Double
    @State private var start: Double?
    var body: some View {
        Rectangle().fill(.separator).frame(width: 1)
            .overlay {
                Color.clear.frame(width: 9).contentShape(Rectangle())
                    #if os(macOS)
                    .onHover { inside in if inside { NSCursor.resizeLeftRight.push() } else { NSCursor.pop() } }
                    #endif
                    .gesture(DragGesture(minimumDistance: 1)
                        .onChanged { g in let b = start ?? width; if start == nil { start = width }; width = min(900, max(360, b - Double(g.translation.width))) }
                        .onEnded { _ in start = nil })
            }
    }
}
