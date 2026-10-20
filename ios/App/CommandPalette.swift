import SwiftUI

/// Befehlspalette (⌘K) wie im Web: Seiten nach Name und Inhalt suchen, mit ">" nur Aktionen.
struct CommandPalette: View {
    struct Action: Identifiable { let id: String; let icon: String; let title: String; let run: () -> Void }
    let actions: [Action]
    let open: (Int) -> Void
    @EnvironmentObject var store: Store
    @Environment(\.dismiss) private var dismiss
    @State private var q = ""
    @State private var found: [Page] = []
    @State private var sel = 0
    @FocusState private var focus: Bool

    private enum Hit: Identifiable {
        case page(Page), action(Action)
        var id: String { switch self { case .page(let p): return "p\(p.id)"; case .action(let a): return "a" + a.id } }
    }
    private var hits: [Hit] {
        let t = q.trimmingCharacters(in: .whitespaces)
        if t.hasPrefix(">") {
            let a = hkNorm(String(t.dropFirst()))
            return actions.filter { a.isEmpty || hkNorm($0.title).contains(a) }.map(Hit.action)
        }
        let n = hkNorm(t)
        let acts = n.isEmpty ? [] : actions.filter { hkNorm($0.title).contains(n) }.prefix(3).map(Hit.action)
        var pages: [Page]
        if n.isEmpty { pages = store.recent.compactMap { store.page($0) }.prefix(8).map { $0 } }
        else {
            let byName = store.pages.filter { hkNorm($0.label).contains(n) }
            pages = byName + found.filter { f in !byName.contains { $0.id == f.id } }
        }
        return Array(pages.prefix(12)).map(Hit.page) + acts
    }

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Image(systemName: "magnifyingglass").foregroundStyle(.secondary)
                TextField("Seite suchen … (> für Befehle)", text: $q).textFieldStyle(.plain).font(.title3).focused($focus)
                    .onKeyPress(keys: [.upArrow, .downArrow, .return, .escape]) { kp in
                        let h = hits
                        switch kp.key {
                        case .upArrow: if !h.isEmpty { sel = (sel + h.count - 1) % h.count }
                        case .downArrow: if !h.isEmpty { sel = (sel + 1) % h.count }
                        case .escape: dismiss()
                        default: if sel < h.count { pick(h[sel]) }
                        }
                        return .handled
                    }
            }
            .padding(14)
            Divider()
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    if q.trimmingCharacters(in: .whitespaces).isEmpty { Text("Zuletzt").font(.caption).foregroundStyle(.secondary).padding(.horizontal, 14).padding(.top, 8) }
                    ForEach(Array(hits.enumerated()), id: \.element.id) { i, h in
                        Button { pick(h) } label: { row(h).background(i == sel ? Color.accentColor.opacity(0.18) : .clear, in: RoundedRectangle(cornerRadius: 6)) }
                            .buttonStyle(.plain)
                    }
                    if hits.isEmpty { Text("Nichts gefunden").foregroundStyle(.secondary).padding(14) }
                }.padding(6)
            }
            .frame(maxHeight: 380)
        }
        .macWidth(560)
        .onAppear { focus = true }
        .onChange(of: q) { _, v in
            sel = 0
            let t = v.trimmingCharacters(in: .whitespaces)
            guard t.count >= 2, !t.hasPrefix(">") else { found = []; return }
            Task { let r = await store.search(t); if q == v { found = r } }      // Volltext ueber den Server
        }
    }

    @ViewBuilder private func row(_ h: Hit) -> some View {
        HStack(spacing: 10) {
            switch h {
            case .page(let p):
                PageIcon(icon: p.icon, store: store, fallback: p.isDatabase ? "🗃️" : "📄")
                VStack(alignment: .leading, spacing: 1) {
                    Text(p.label).lineLimit(1)
                    let path = store.path(p.id).map(\.label).joined(separator: " / ")
                    if !path.isEmpty { Text(path).font(.caption).foregroundStyle(.secondary).lineLimit(1) }
                }
            case .action(let a):
                Image(systemName: a.icon).frame(width: 22).foregroundStyle(.secondary)
                Text(a.title)
                Spacer()
                Text("Befehl").font(.caption).foregroundStyle(.tertiary)
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 10).padding(.vertical, 7).contentShape(Rectangle())
    }
    private func pick(_ h: Hit) {
        dismiss()
        switch h {
        case .page(let p): open(p.id)
        case .action(let a): DispatchQueue.main.async { a.run() }
        }
    }
}

/// Papierkorb wie im Web: zurueckholen, endgueltig loeschen, leeren (nach 30 Tagen loescht der Server selbst)
struct TrashSheet: View {
    @EnvironmentObject var store: Store
    @Environment(\.dismiss) private var dismiss
    @State private var items: [[String: Any]] = []
    @State private var confirmEmpty = false
    @State private var purge: TrashLogic.Item?                       // Rueckfrage vor "Endgueltig loeschen"
    @State private var busy = Set<Int>()                              // je Eintrag nur eine Aktion gleichzeitig
    var body: some View {
        NavigationStack {
            List {
                if items.isEmpty { Text("Der Papierkorb ist leer.").foregroundStyle(.secondary) }
                ForEach(items.indices, id: \.self) { i in
                    let it = TrashLogic.item(items[i]) { store.page($0)?.label }
                    // Zwei Zeilen statt einer gequetschten (Titel/Datum brachen um): oben Text, unten die Knoepfe
                    VStack(alignment: .leading, spacing: 8) {
                        HStack(alignment: .top) {
                            PageIcon(icon: it.icon, store: store)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(it.title.isEmpty ? String(localized: "Unbenannt") : it.title).lineLimit(2)
                                if !it.parent.isEmpty { Text("in „\(it.parent)“").font(.caption).foregroundStyle(.secondary).lineLimit(1) }
                                Text((it.subpages > 0 ? String(localized: "mit \(it.subpages) Unterseiten") + " · " : "") + String(localized: "wird gelöscht am \(day(it.purgeAt))"))
                                    .font(.caption).foregroundStyle(.secondary)
                            }
                        }
                        HStack {
                            Button("Zurückholen") { act(it.id) { _ = try? await store.api("api/trash/\(it.id)/restore", method: "POST", body: [:]); await store.refresh() } }
                                .buttonStyle(.bordered)
                            Spacer()
                            Button("Endgültig löschen", role: .destructive) { purge = it }
                        }
                        .controlSize(.small).disabled(busy.contains(it.id))
                        .rowButtons()                                 // sonst loest ein Tipp beide Knoepfe aus (Datenverlust)
                    }
                }
            }
            .navigationTitle("Papierkorb")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Schließen") { dismiss() } }
                ToolbarItem(placement: .destructiveAction) { Button("Papierkorb leeren", role: .destructive) { confirmEmpty = true }.disabled(items.isEmpty) }
            }
            .confirmationDialog("Alle Seiten im Papierkorb endgültig löschen?", isPresented: $confirmEmpty) {
                Button("Endgültig löschen", role: .destructive) { Task { _ = try? await store.api("api/trash/empty", method: "POST", body: [:]); await load() } }
            }
            .confirmationDialog(Text(verbatim: TrashLogic.purgeQuestion(purge)), isPresented: Binding(get: { purge != nil }, set: { if !$0 { purge = nil } }), titleVisibility: .visible, presenting: purge) { it in
                Button("Endgültig löschen", role: .destructive) { act(it.id) { _ = try? await store.api("api/trash/\(it.id)", method: "DELETE") } }
                Button("Abbrechen", role: .cancel) {}
            } message: { _ in Text("Das lässt sich nicht rückgängig machen.") }
        }
        .macFrame(minWidth: 520, minHeight: 420)
        .task { await load() }
    }
    /// Aktion je Eintrag; waehrend sie laeuft, sind die Knoepfe dieser Zeile gesperrt (kein Doppel-Tipp)
    private func act(_ id: Int, _ op: @escaping () async -> Void) {
        guard !busy.contains(id) else { return }
        busy.insert(id)
        Task { await op(); await load(); busy.remove(id) }
    }
    private func load() async {
        items = (try? await store.api("api/trash")).flatMap { try? JSONSerialization.jsonObject(with: $0) as? [[String: Any]] } ?? []
    }
    private func day(_ v: Double?) -> String {
        guard let t = v else { return "–" }
        return Date(timeIntervalSince1970: t).sagaFormatted(date: .abbreviated, time: .omitted)
    }
}

/// Dienste aus FreeIPA (wie die Kacheln auf der Web-Startseite), nach Kategorie; Klick oeffnet im Browser
struct ServicesScreen: View {
    @EnvironmentObject var store: Store
    @Environment(\.openURL) private var openURL
    struct S: Decodable, Identifiable { var id: String { url }; let name: String; let url: String; let host: String; let category: String; let icon: String? }
    @State private var list: [S] = []
    @State private var q = ""
    @State private var err = ""
    @State private var loaded = false
    var body: some View {
        let shown = list.filter { q.isEmpty || hkNorm($0.name + " " + $0.host + " " + $0.category).contains(hkNorm(q)) }
        let cats = Array(Set(shown.map(\.category))).sorted()
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                TextField("Dienst suchen", text: $q).textFieldStyle(.roundedBorder).frame(maxWidth: 320)
                if !err.isEmpty { Text(err).foregroundStyle(.red) }
                if loaded && err.isEmpty && list.isEmpty {
                    ContentUnavailableView("Keine Dienste", systemImage: "square.grid.3x3", description: Text("Dienste kommen aus der Verwaltung des Servers (FreeIPA)."))
                } else if !loaded && err.isEmpty { ProgressView().frame(maxWidth: .infinity) }
                ForEach(cats, id: \.self) { c in
                    Text(c).font(.headline)
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 200), spacing: 10)], spacing: 10) {
                        ForEach(shown.filter { $0.category == c }) { s in
                            Button { if let u = URL(string: s.url) { openURL(u) } } label: {
                                HStack(spacing: 10) {
                                    ServiceIcon(icon: s.icon, name: s.name)
                                    VStack(alignment: .leading, spacing: 2) { Text(s.name).fontWeight(.medium); Text(s.host).font(.caption).foregroundStyle(.secondary).lineLimit(1) }
                                    Spacer(minLength: 0)
                                }
                                .padding(12).frame(maxWidth: .infinity, alignment: .leading)
                                .background(.secondary.opacity(0.1), in: RoundedRectangle(cornerRadius: 10))
                            }.buttonStyle(.plain)
                        }
                    }
                }
            }.padding(24)
        }
        .navigationTitle("Dienste")
        .task { await load(retry: true) }
    }
    /// Abgebrochen (Ansicht neu aufgebaut) ist kein Fehler: einmal ausserhalb der abgebrochenen Aufgabe neu laden
    private func load(retry: Bool) async {
        do { list = try JSONDecoder().decode([S].self, from: try await store.api("api/services")); err = ""; loaded = true }
        catch {
            if !ServiceErrors.isCancel(error) { err = error.localizedDescription; loaded = true }
            else if retry { Task { await load(retry: false) } }
        }
    }
}
