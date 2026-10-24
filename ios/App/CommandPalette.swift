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
            .alert("Alle Seiten im Papierkorb endgültig löschen?", isPresented: $confirmEmpty) {
                Button("Endgültig löschen", role: .destructive) { Task { _ = try? await store.api("api/trash/empty", method: "POST", body: [:]); await load() } }
                Button("Abbrechen", role: .cancel) {}
            }
            // Hinweis statt Aktionsblatt: dort fehlte auf iPhone/iPad ein sichtbarer "Abbrechen"-Knopf (N8)
            .alert(Text(verbatim: TrashLogic.purgeQuestion(purge)), isPresented: Binding(get: { purge != nil }, set: { if !$0 { purge = nil } }), presenting: purge) { it in
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

/// Dienste aus FreeIPA (wie die Kacheln auf der Web-Startseite) und eigene Lesezeichen, nach Kategorie; Klick oeffnet
/// im Browser. Eigene Lesezeichen anlegen (+), bearbeiten und loeschen (Kontextmenue) wie im Web und auf Android.
struct ServicesScreen: View {
    @EnvironmentObject var store: Store
    @Environment(\.openURL) private var openURL
    struct S: Decodable, Identifiable {
        var id: String { key ?? url }
        let name: String; let url: String; let host: String; let category: String; let icon: String?
        var own: Bool? = nil; var key: String? = nil
    }
    @State private var list: [S] = []
    @State private var q = ""
    @State private var err = ""
    @State private var loaded = false
    @State private var editing: BookmarkSheet.Target?
    @State private var askDelete: S?
    @State private var note = ""
    var body: some View {
        let labeled = list.map { ($0, BookmarkLogic.displayCategory($0.category)) }
        let shown = labeled.filter { q.isEmpty || hkNorm($0.0.name + " " + $0.0.host + " " + $0.1 + " " + $0.0.category).contains(hkNorm(q)) }
        let cats = Array(Set(shown.map(\.1))).sorted { $0.localizedCaseInsensitiveCompare($1) == .orderedAscending }
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                TextField("Dienst suchen", text: $q).textFieldStyle(.roundedBorder).frame(maxWidth: 320)
                if !err.isEmpty { Text(err).foregroundStyle(.red) }
                if !note.isEmpty { Text(note).font(.callout).foregroundStyle(.secondary) }
                if loaded && err.isEmpty && list.isEmpty {
                    ContentUnavailableView {
                        Label("Keine Dienste", systemImage: "square.grid.3x3")
                    } description: {
                        Text("Dienste kommen aus der Verwaltung des Servers (FreeIPA). Eigene Lesezeichen legst du mit + an.")
                    } actions: {
                        Button("Lesezeichen hinzufügen") { editing = .new(nil) }
                    }
                } else if !loaded && err.isEmpty { ProgressView().frame(maxWidth: .infinity) }
                if list.contains(where: { $0.own == true }) {
                    Text("Eigene Lesezeichen: lange drücken (Mac: Rechtsklick) zum Bearbeiten.").font(.caption).foregroundStyle(.secondary)
                }
                ForEach(cats, id: \.self) { c in
                    Text(c).font(.headline)
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 200), spacing: 10)], spacing: 10) {
                        ForEach(shown.filter { $0.1 == c }.map(\.0)) { s in
                            Button { if let u = URL(string: s.url) { openURL(u) } } label: {
                                HStack(spacing: 10) {
                                    ServiceIcon(icon: s.icon, name: s.name)
                                    VStack(alignment: .leading, spacing: 2) { Text(s.name).fontWeight(.medium); Text(s.host).font(.caption).foregroundStyle(.secondary).lineLimit(1) }
                                    Spacer(minLength: 0)
                                }
                                .padding(12).frame(maxWidth: .infinity, alignment: .leading)
                                .background(.secondary.opacity(0.1), in: RoundedRectangle(cornerRadius: 10))
                                .contentShape(RoundedRectangle(cornerRadius: 10))
                            }
                            .buttonStyle(.plain)
                            .contextMenu {
                                if s.own == true, BookmarkLogic.id(key: s.key) != nil {
                                    Button { editing = .edit(s) } label: { Label("Lesezeichen bearbeiten", systemImage: "pencil") }
                                    Button(role: .destructive) { askDelete = s } label: { Label("Löschen", systemImage: "trash") }
                                }
                            }
                        }
                    }
                }
            }.padding(24)
        }
        .navigationTitle("Dienste")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button { editing = .new(list.first(where: { $0.own == true })?.category) } label: { Label("Lesezeichen hinzufügen", systemImage: "plus") }
            }
        }
        .sheet(item: $editing) { t in
            BookmarkSheet(target: t) { msg in flash(msg); Task { await load(retry: true) } }.environmentObject(store)
        }
        .alert(askDelete.map { String(localized: "„\($0.name)“ löschen?") } ?? "", isPresented: Binding(get: { askDelete != nil }, set: { if !$0 { askDelete = nil } })) {
            Button("Löschen", role: .destructive) {
                guard let s = askDelete else { return }
                askDelete = nil
                Task { await delete(s) }
            }
            Button("Abbrechen", role: .cancel) { askDelete = nil }
        }
        .task {
            await load(retry: true)
            #if DEBUG
            // nur fuer Tests: -hkBookmark new|edit|delete (mit -hkBookmarkURL/-hkBookmarkName/-hkBookmarkSave 1 im Blatt)
            let own = list.first(where: { $0.own == true })
            switch UserDefaults.standard.string(forKey: "hkBookmark") {
            case "new": editing = .new(own?.category)
            case "edit": if let own { editing = .edit(own) }
            case "delete": if let own { askDelete = own }
            case "delete-now": if let own { await delete(own) }
            default: break
            }
            #endif
        }
    }
    /// kurze Bestaetigung ueber der Liste (verschwindet nach 4 s)
    private func flash(_ msg: String) {
        note = msg
        Task { try? await Task.sleep(for: .seconds(4)); if note == msg { note = "" } }
    }
    private func delete(_ s: S) async {
        guard let id = BookmarkLogic.id(key: s.key) else { return }
        do { _ = try await store.api("api/bookmarks/\(id)", method: "DELETE"); flash(String(localized: "Lesezeichen gelöscht")); await load(retry: true) }
        catch { err = error.localizedDescription }
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


// MARK: - Eigene Lesezeichen (Saga 2.4, wie Web/Android)

enum BookmarkLogic {
    /// Schluessel "bm<id>" eigener Lesezeichen -> id
    static func id(key: String?) -> Int? {
        guard let k = key, k.hasPrefix("bm") else { return nil }
        return Int(k.dropFirst(2))
    }
    static func payload(name: String, url: String, category: String, icon: String) -> [String: Any] {
        ["name": name.trimmingCharacters(in: .whitespacesAndNewlines), "url": url.trimmingCharacters(in: .whitespacesAndNewlines),
         "category": category.trimmingCharacters(in: .whitespacesAndNewlines), "icon": icon.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()]
    }
    /// Vorpruefung wie der Server (_bm_input): http(s), Host, Port nur Ziffern; ohne Schema https://
    static func validURL(_ u: String) -> Bool {
        let t = u.trimmingCharacters(in: .whitespacesAndNewlines)
        if t.isEmpty || t.contains(where: \.isWhitespace) { return false }
        let full = t.contains("://") ? t : "https://" + t
        let parts = full.components(separatedBy: "://")
        let scheme = parts[0].lowercased()
        let host = String(parts.dropFirst().joined(separator: "://").split(separator: "/", maxSplits: 1, omittingEmptySubsequences: false).first ?? "")
            .components(separatedBy: "?")[0]
        guard scheme == "http" || scheme == "https", !host.isEmpty, !host.contains("@") else { return false }
        if host.hasPrefix("[") || !host.contains(":") { return true }
        let port = host.components(separatedBy: ":").last ?? ""
        return !port.isEmpty && port.allSatisfy(\.isNumber)
    }
    /// Standard-Kategorien kommen in der Sprache des Kontos vom Server (saga/core.py std_name) - in der App-Sprache zeigen
    private static let services: Set<String> = ["Dienste", "Services", "Servicios", "Сервисы", "บริการ"]
    private static let bookmarks: Set<String> = ["Lesezeichen", "Bookmarks", "Marcadores", "Закладки", "บุ๊กมาร์ก"]
    static func displayCategory(_ c: String) -> String {
        let n = c.trimmingCharacters(in: .whitespaces)
        if services.contains(n) { return String(localized: "Dienste") }
        if bookmarks.contains(n) { return String(localized: "Lesezeichen") }
        return n.isEmpty ? String(localized: "Sonstige") : n
    }
}

struct BookmarkSheet: View {
    enum Target: Identifiable {
        case new(String?)                     // vorgeschlagene Kategorie
        case edit(ServicesScreen.S)
        var id: String { switch self { case .new: "new"; case .edit(let s): s.id } }
    }
    let target: Target
    let done: (String) -> Void
    @EnvironmentObject var store: Store
    @Environment(\.dismiss) private var dismiss
    @State private var url = ""
    @State private var name = ""
    @State private var category = ""
    @State private var icon = ""
    @State private var err = ""
    @State private var busy = false
    private var editID: Int? { if case .edit(let s) = target { return BookmarkLogic.id(key: s.key) } else { return nil } }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Adresse (https://…)", text: $url, prompt: Text(verbatim: "https://…"))
                        #if os(iOS)
                        .keyboardType(.URL).textInputAutocapitalization(.never)
                        #endif
                        .autocorrectionDisabled()
                } header: { Text("Adresse (https://…)") } footer: {
                    if !url.isEmpty && !BookmarkLogic.validURL(url) { Text("Bitte eine Adresse mit http:// oder https:// angeben.").foregroundStyle(.red) }
                }
                Section { TextField("Name", text: $name) } header: { Text("Name") }
                Section { TextField("Kategorie", text: $category) } header: { Text("Kategorie") }
                Section {
                    TextField("Symbol (optional)", text: $icon, prompt: Text(verbatim: "nextcloud"))
                        #if os(iOS)
                        .textInputAutocapitalization(.never)
                        #endif
                        .autocorrectionDisabled()
                } header: { Text("Symbol (optional)") } footer: { Text("Name aus selfh.st/icons, z. B. nextcloud") }
                if !err.isEmpty { Text(err).foregroundStyle(.red) }
            }
            .formStyle(.grouped)
            .navigationTitle(editID == nil ? String(localized: "Neues Lesezeichen") : String(localized: "Lesezeichen bearbeiten"))
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Abbrechen") { dismiss() }.disabled(busy) }
                ToolbarItem(placement: .confirmationAction) {
                    Button(editID == nil ? String(localized: "Hinzufügen") : String(localized: "Speichern")) { Task { await save() } }
                        .disabled(busy || !BookmarkLogic.validURL(url))
                }
            }
        }
        #if os(macOS)
        .frame(minWidth: 420, minHeight: 320)
        #endif
        .onAppear {
            switch target {
            case .new(let c): category = BookmarkLogic.displayCategory(c ?? "Lesezeichen")
            case .edit(let s): url = s.url; name = s.name; category = BookmarkLogic.displayCategory(s.category); icon = s.icon ?? ""
            }
            #if DEBUG
            let d = UserDefaults.standard
            if let v = d.string(forKey: "hkBookmarkURL") { url = v }
            if let v = d.string(forKey: "hkBookmarkName") { name = v }
            if d.string(forKey: "hkBookmarkSave") == "1" { Task { try? await Task.sleep(for: .seconds(2)); await save() } }
            #endif
        }
    }

    private func save() async {
        busy = true; err = ""
        let body = BookmarkLogic.payload(name: String(name.prefix(80)), url: url, category: String(category.prefix(40)), icon: String(icon.prefix(64)))
        do {
            if let id = editID { _ = try await store.api("api/bookmarks/\(id)", method: "PATCH", body: body) }
            else { _ = try await store.api("api/bookmarks", method: "POST", body: body) }
            done(editID == nil ? String(localized: "Lesezeichen hinzugefügt") : String(localized: "Lesezeichen gespeichert"))
            dismiss()
        } catch {
            err = error.localizedDescription; busy = false
        }
    }
}
