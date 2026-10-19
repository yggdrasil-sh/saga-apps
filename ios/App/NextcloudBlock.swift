import SwiftUI

// Saga 2.3, Apps Teil 3: Block "nextcloud" in Seiten (wie static/pages.js NextcloudTool). Karte mit Symbol, Name, Groesse,
// Vorschau fuer Bilder ueber den Saga-Proxy (/api/notes/{id}/nc/{fileid}?a=&w=), Ordner mit ersten Eintraegen,
// "In Nextcloud oeffnen", "Herunterladen". Der Block wird NIE veraendert (Daten bleiben beim Speichern wie geladen);
// Freigaben (veroeffentlichte Seite, verbundene Server) aendert nur die einfuegende Person im Web.
// Einfuegen: "/"-Menue "Nextcloud-Datei" -> Auswahl aus der eigenen verknuepften Nextcloud (Zuletzt, Ordner, Suche).

/// Status der Nextcloud-Bloecke je Seite (eine Anfrage fuer alle Karten, 60 s gueltig wie im Web)
@MainActor
final class NCStatusCache {
    static let shared = NCStatusCache()
    private var entries: [Int: (t: Date, task: Task<NCStatus, Never>)] = [:]
    func status(_ store: Store, note: Int, fresh: Bool = false) async -> NCStatus {
        if !fresh, let e = entries[note], Date().timeIntervalSince(e.t) < 60 { return await e.task.value }
        let api = store.cloudAPI
        let t = Task { await CloudFlow.noteStatus(api, note: note) }
        entries[note] = (Date(), t)
        return await t.value
    }
    func forget(_ note: Int) { entries[note] = nil }
}

struct NextcloudCard: View {
    let data: NSMutableDictionary
    let note: Int
    let remotePage: Bool
    @EnvironmentObject var store: Store
    @Environment(\.openURL) private var openURL
    @State private var item: NCStatusItem?
    @State private var checked = false
    @State private var err: String?
    @State private var loading = false
    private var block: NCBlock? { NCBlock(data as? [String: Any] ?? [:]) }
    var body: some View {
        if let b = block {
            VStack(alignment: .leading, spacing: 8) {
                header(b)
                media(b)
                if let n = note(b) { Text(verbatim: n).font(.footnote).foregroundStyle(.secondary) }
                if let err { Text(verbatim: err).font(.footnote).foregroundStyle(.red) }
            }
            .padding(10)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(.secondary.opacity(0.1), in: RoundedRectangle(cornerRadius: 10))
            .opacity(checked && item?.ok != true ? 0.75 : 1)
            .task(id: b.key) { await load(b) }
        }
    }
    private func header(_ b: NCBlock) -> some View {
        HStack(alignment: .center, spacing: 10) {
            Image(systemName: CloudLogic.symbol(mime: item?.mime ?? b.mime, kind: b.kind, dir: item?.dir ?? b.isFolder))
                .font(.title2).foregroundStyle(.secondary).frame(width: 30).accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(verbatim: item?.name ?? b.displayName).font(.body.weight(.medium)).lineLimit(2)
                Text(verbatim: meta(b)).font(.caption).foregroundStyle(.secondary)
            }
            Spacer(minLength: 4)
            actions(b)
        }
    }
    @ViewBuilder private func actions(_ b: NCBlock) -> some View {
        if !b.isFolder && item?.ok != false {
            Button { Task { await download(b) } } label: {
                if loading { ProgressView().controlSize(.small) } else { Image(systemName: "arrow.down.circle") }
            }
            .buttonStyle(.borderless).disabled(loading).help(Text("Herunterladen")).accessibilityLabel(Text("Herunterladen"))
        }
        if let u = b.openURL(remotePage: remotePage) {
            Button { openURL(u) } label: { Image(systemName: "arrow.up.forward.app") }
                .buttonStyle(.borderless).help(Text("In Nextcloud öffnen")).accessibilityLabel(Text("In Nextcloud öffnen"))
        }
    }
    @ViewBuilder private func media(_ b: NCBlock) -> some View {
        if let it = item, it.ok {
            if it.dir || b.isFolder {
                VStack(alignment: .leading, spacing: 3) {
                    if it.entries.isEmpty { Text("Leerer Ordner").font(.footnote).foregroundStyle(.secondary) }
                    ForEach(Array(it.entries.prefix(12).enumerated()), id: \.offset) { _, e in
                        Label(e.name, systemImage: e.dir ? "folder" : "doc").font(.footnote).lineLimit(1)
                    }
                }
                .padding(.leading, 40)
            } else if b.isImage || it.preview {
                let w = b.isImage ? 1280 : 320
                RemoteImage(path: b.proxyPath(note: note, w: w, v: it.mtime ?? b.mtime))
                    .frame(maxWidth: b.isImage ? .infinity : 200, maxHeight: b.isImage ? 700 : 160, alignment: .leading)
                    .clipShape(RoundedRectangle(cornerRadius: 8))
                    .accessibilityLabel(Text(verbatim: it.name ?? b.displayName))
            }
        }
    }
    private func meta(_ b: NCBlock) -> String {
        var parts: [String] = []
        if item?.dir ?? b.isFolder { parts.append(String(localized: "Ordner")) } else { parts.append(CloudLogic.size(item?.size ?? b.size)) }
        if let m = item?.mtime { parts.append(Date(timeIntervalSince1970: m).formatted(date: .abbreviated, time: .omitted)) }
        parts.append("Nextcloud")
        return parts.filter { !$0.isEmpty }.joined(separator: " · ")
    }
    private func note(_ b: NCBlock) -> String? {
        guard checked, item?.ok != true else { return nil }
        if remotePage { return String(localized: "Nur auf dem Server der Seite sichtbar (Freigabe für verbundene Server ist aus).") }
        if !b.signed && item == nil { return String(localized: "Wird nach dem Speichern angezeigt.") }
        return String(localized: "Nicht verfügbar – Verknüpfung getrennt, kein Zugriff mehr oder Datei gelöscht.")
    }
    private func load(_ b: NCBlock) async {
        // eben eingefuegt (noch ohne Signatur): der Server kennt den Block erst nach dem Speichern - kurz nachfragen
        let tries = (!b.signed && !remotePage) ? 8 : 1
        for i in 0..<tries {
            let st = await NCStatusCache.shared.status(store, note: note, fresh: i > 0)
            if let it = st.item(for: b, remotePage: remotePage) { item = it; checked = true; if it.ok { return } }
            checked = true
            if i + 1 < tries { try? await Task.sleep(for: .seconds(1.5)) }
            if Task.isCancelled { return }
        }
    }
    private func download(_ b: NCBlock) async {
        loading = true; defer { loading = false }
        err = await downloadToUser(store, path: b.proxyPath(note: note, download: true), name: item?.name ?? b.displayName)
    }
}

// MARK: - Auswahl aus der eigenen Nextcloud

enum NCPickResult {
    case link([String: Any])
    case copy([String: Any])
}

struct NextcloudPicker: View {
    /// Verweis erlaubt (nicht auf Seiten anderer Saga-Server - dort nur als Kopie)
    let linkAllowed: Bool
    /// Seite einer anderen Person (Kopie gehoert dann dem Eigentuemer, wie Uploads)
    let note: Int?
    let done: (NCPickResult?) -> Void
    @EnvironmentObject var store: Store
    @State private var settings: NCSettings?
    @State private var mode = 0                         // 0 Zuletzt, 1 Ordner, 2 Suche
    @State private var path = "/"
    @State private var query = ""
    @State private var items: [NCItem] = []
    @State private var here: NCItem?                    // aktueller Ordner (fuer "Diesen Ordner einfuegen")
    @State private var sel: NCItem?
    @State private var info: String?
    @State private var busy = false
    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                if let s = settings, s.enabled, s.connected { browser } else { status }
            }
            .navigationTitle("Datei aus Nextcloud")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Abbrechen") { done(nil) } } }
        }
        .macFrame(minWidth: 520, idealWidth: 600, minHeight: 520, idealHeight: 640)
        .task { await start() }
    }
    @ViewBuilder private var status: some View {
        if let s = settings {
            ContentUnavailableView {
                Label("Nextcloud", systemImage: "cloud")
            } description: {
                Text(s.enabled ? String(localized: "Noch nicht mit Nextcloud verbunden. Verknüpfe sie unter Einstellungen → Nextcloud.")
                               : String(localized: "Nextcloud-Dateien sind auf diesem Server abgeschaltet."))
            }
        } else if let info { Text(verbatim: info).foregroundStyle(.secondary).padding() }
        else { ProgressView().padding() }
    }
    private var browser: some View {
        VStack(spacing: 0) {
            Picker("", selection: $mode) {
                Text("Zuletzt").tag(0)
                Text("Ordner").tag(1)
                if mode == 2 { Text("Suche").tag(2) }
            }
            .pickerStyle(.segmented).labelsHidden().padding(.horizontal).padding(.top, 8)
            TextField("Dateiname suchen …", text: $query).textFieldStyle(.roundedBorder).padding(8)
                .onSubmit { mode = query.trimmingCharacters(in: .whitespaces).isEmpty ? 0 : 2; Task { await load() } }
            if mode == 1 { crumbs }
            list
            if let s = sel { footer(s) }
        }
        .onChange(of: mode) { _, m in if m != 2 { query = "" }; Task { await load() } }
    }
    private var crumbs: some View {
        HStack(spacing: 4) {
            Button { path = "/"; Task { await load() } } label: { Image(systemName: "cloud") }.buttonStyle(.borderless).accessibilityLabel(Text("Oberste Ebene"))
            Text(verbatim: path == "/" ? "/" : path).font(.footnote).lineLimit(1).truncationMode(.head)
            Spacer()
            if path != "/" {
                Button { path = parent(path); Task { await load() } } label: { Image(systemName: "chevron.up") }.buttonStyle(.borderless).accessibilityLabel(Text("Übergeordneter Ordner"))
                if let h = here { Button("Diesen Ordner einfügen") { sel = h } .buttonStyle(.bordered).controlSize(.small) }
            }
        }
        .padding(.horizontal)
    }
    private var list: some View {
        List {
            if let info { Text(verbatim: info).foregroundStyle(.secondary) }
            ForEach(items) { it in
                Button { tap(it) } label: { NCItemRow(item: it, showPath: mode != 1, selected: sel?.id == it.id) }.buttonStyle(.plain)
            }
        }
        .overlay { if busy && items.isEmpty { ProgressView() } }
    }
    private func footer(_ it: NCItem) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Label(it.name, systemImage: CloudLogic.symbol(mime: it.mime, kind: it.kind, dir: it.dir)).font(.headline).lineLimit(1)
            Text(linkAllowed ? String(localized: "Alle, die diese Seite sehen, sehen auch diese Datei. Vorschau und Download laufen über dein Nextcloud-Konto, solange es verknüpft ist.")
                             : String(localized: "Auf Seiten anderer Saga-Server lassen sich Nextcloud-Dateien nur als Kopie einfügen."))
                .font(.footnote).foregroundStyle(.secondary)
            HStack {
                if linkAllowed {
                    Button("Als Verweis einfügen") { done(.link(CloudLogic.newBlockData(it, me: settings?.me))) }.buttonStyle(.borderedProminent)
                }
                if !it.dir {
                    Button("Als Kopie übernehmen") { Task { await copy(it) } }.buttonStyle(.bordered).disabled(busy)
                        .help(Text("Die Datei wird nach Saga kopiert und folgt dann den Saga-Rechten (auch offline, im Backup und Export)."))
                }
            }
        }
        .padding().frame(maxWidth: .infinity, alignment: .leading).background(.bar)
    }
    private func tap(_ it: NCItem) {
        if it.dir && mode == 1 { path = it.path; Task { await load() }; return }
        sel = it
    }
    private func parent(_ p: String) -> String {
        guard let r = p.range(of: "/", options: .backwards), r.lowerBound > p.startIndex else { return "/" }
        return String(p[..<r.lowerBound])
    }
    private func start() async {
        do {
            settings = try await CloudFlow.ncSettings(store.cloudAPI)
            if settings == nil { info = String(localized: "Dieser Server kennt noch keine Nextcloud-Dateien (ab Saga 2.3).") }
            if settings?.connected == true { await load() }
        } catch { info = error.localizedDescription }
    }
    private func load() async {
        busy = true; sel = nil; info = String(localized: "Lade …"); here = nil
        defer { busy = false }
        do {
            switch mode {
            case 1:
                items = try await CloudFlow.ncList(store.cloudAPI, path: path)
                if path != "/" {                                      // Datei-ID des Ordners (aus der Liste des Elternordners)
                    here = try? await CloudFlow.ncList(store.cloudAPI, path: parent(path)).first { $0.path == path }
                }
            case 2: items = try await CloudFlow.ncSearch(store.cloudAPI, query: query)
            default: items = try await CloudFlow.ncSearch(store.cloudAPI, query: "")
            }
            info = items.isEmpty ? String(localized: "Nichts gefunden.") : nil
        } catch { items = []; info = error.localizedDescription }
    }
    private func copy(_ it: NCItem) async {
        busy = true; info = String(localized: "Übernehme „\(it.name)“ …")
        defer { busy = false }
        do { done(.copy(try await CloudFlow.ncImport(store.cloudAPI, fileid: it.fileid, note: note))) }
        catch { info = String(localized: "Nicht übernommen: \(error.localizedDescription)") }
    }
}

struct NCItemRow: View {
    let item: NCItem
    let showPath: Bool
    let selected: Bool
    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: CloudLogic.symbol(mime: item.mime, kind: item.kind, dir: item.dir)).frame(width: 24).foregroundStyle(.secondary)
            if item.preview && !item.dir {
                RemoteImage(path: "api/nextcloud/thumb/\(CloudLogic.q(item.fileid))?w=160&v=\(Int(item.mtime ?? 0))", fill: true)
                    .frame(width: 36, height: 36).clipShape(RoundedRectangle(cornerRadius: 4)).accessibilityHidden(true)
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(verbatim: item.name).lineLimit(1)
                Text(verbatim: detail).font(.caption).foregroundStyle(.secondary).lineLimit(1)
            }
            Spacer()
            if selected { Image(systemName: "checkmark").foregroundStyle(Color.accentColor) }
            else if item.dir && !showPath { Image(systemName: "chevron.right").foregroundStyle(.tertiary) }
        }
        .contentShape(Rectangle())
    }
    private var detail: String {
        var p: [String] = [item.dir ? String(localized: "Ordner") : CloudLogic.size(item.size)]
        if let m = item.mtime, !item.dir { p.append(Date(timeIntervalSince1970: m).formatted(date: .abbreviated, time: .shortened)) }
        if showPath { p.append(item.folder) }
        return p.filter { !$0.isEmpty }.joined(separator: " · ")
    }
}

extension PageEditor {
    /// Auswahl uebernehmen: Verweis -> Block "nextcloud" (der Server setzt author/sig beim Speichern),
    /// Kopie -> Bild- bzw. Datei-Block
    func applyNextcloud(_ r: NCPickResult?, into id: String) {
        defer { ncFor = nil }
        guard let r, let (arr, i) = locate(id) else { return }
        let block: [String: Any]?
        switch r {
        case .link(let d): block = ["id": id, "type": "nextcloud", "data": d]
        case .copy(let f): block = CloudLogic.copyBlock(file: f, id: id)
        }
        guard let block, let d = try? JSONSerialization.data(withJSONObject: block),
              let m = try? JSONSerialization.jsonObject(with: d, options: .mutableContainers) as? NSMutableDictionary else { return }
        arr.replaceObject(at: i, with: m)
        NCStatusCache.shared.forget(doc.id)
        focus = nil; changed()
    }
}
