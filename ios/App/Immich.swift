import SwiftUI

/// Bilder aus Immich waehlen (Zuletzt, Alben, Suche nach Bildinhalt). Das gewaehlte Foto uebernimmt der Server
/// als eigene Datei (/api/immich/import) -> onPick(url). Ohne Schluessel: direkt hier verbinden.
struct ImmichPicker: View {
    let onPick: (String) -> Void
    @EnvironmentObject var store: Store
    @Environment(\.dismiss) private var dismiss
    struct Item: Identifiable, Hashable { let id: String; let name: String; let gif: Bool }
    struct Album: Identifiable, Hashable { let id: String; let name: String; let count: Int; let thumb: String? }
    enum Tab: Hashable { case recent, albums }
    @State private var status: (available: Bool, connected: Bool)?
    @State private var tab: Tab = .recent
    @State private var items: [Item] = []
    @State private var albums: [Album] = []
    @State private var album: Album?
    @State private var query = ""
    @State private var next: Int?
    @State private var info = ""
    @State private var busy: String?
    @State private var key = ""
    @State private var url = ""

    var body: some View {
        NavigationStack {
            Group {
                if let s = status, !s.available { msg("Immich ist auf diesem Server nicht eingerichtet.") }
                else if let s = status, !s.connected { connectForm }
                else if status == nil { ProgressView() }
                else { content }
            }
            .navigationTitle(album?.name ?? "Immich")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Abbrechen") { dismiss() } }
                if album != nil { ToolbarItem(placement: .navigation) { Button { album = nil; tab = .albums } label: { Label("Alben", systemImage: "chevron.left") } } }
            }
            .task { await loadStatus() }
        }
        #if os(macOS)
        .frame(minWidth: 520, minHeight: 560)      // nur das Mac-Fenster; am iPhone (≈393 pt) wuerde es ueber den Rand ragen
        #endif
    }

    private func msg(_ t: LocalizedStringKey) -> some View { Text(t).foregroundStyle(.secondary).padding().frame(maxWidth: .infinity, maxHeight: .infinity) }

    private var connectForm: some View {
        Form {
            Section {
                Text("Adresse deines Immich und einen API-Schlüssel eintragen. In Immich unter Kontoeinstellungen → API-Schlüssel einen neuen Schlüssel anlegen (Rechte: album.read, asset.read, asset.view, asset.download, user.read). Saga sieht nur deine eigenen Fotos.")
                    .font(.footnote).foregroundStyle(.secondary)
                TextField("Immich-Adresse (https://…)", text: $url)
                    #if os(iOS)
                    .keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
                    #endif
                SecureField("API-Schlüssel", text: $key)
                Button("Verbinden") { Task { await connect() } }.disabled(key.trimmingCharacters(in: .whitespaces).isEmpty || url.trimmingCharacters(in: .whitespaces).isEmpty)
                if !info.isEmpty { Text(info).font(.footnote).foregroundStyle(.red) }
            }
        }.formStyle(.grouped)
    }

    private var content: some View {
        VStack(spacing: 8) {
            if album == nil {
                Picker("", selection: $tab) { Text("Zuletzt").tag(Tab.recent); Text("Alben").tag(Tab.albums) }
                    .pickerStyle(.segmented).labelsHidden().padding(.horizontal)
                    .onChange(of: tab) { _, t in query = ""; Task { t == .albums ? await loadAlbums() : await loadItems(reset: true) } }
            }
            if tab == .recent || album != nil {
                TextField("Suchen … (z. B. „Strand“, „Katze“)", text: $query).textFieldStyle(.roundedBorder).padding(.horizontal)
                    .onSubmit { Task { await loadItems(reset: true) } }
            }
            if !info.isEmpty { Text(info).font(.footnote).foregroundStyle(.secondary) }
            ScrollView {
                if tab == .albums && album == nil {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 150), spacing: 8)], spacing: 8) {
                        ForEach(albums) { a in
                            Button { album = a; Task { await loadItems(reset: true) } } label: {
                                ZStack(alignment: .bottomLeading) {
                                    ImmichThumb(id: a.thumb).aspectRatio(4 / 3, contentMode: .fit)
                                    VStack(alignment: .leading) { Text(a.name).bold().lineLimit(1); Text("\(a.count) Fotos").font(.caption) }
                                        .foregroundStyle(.white).padding(8).frame(maxWidth: .infinity, alignment: .leading)
                                        .background(LinearGradient(colors: [.clear, .black.opacity(0.7)], startPoint: .top, endPoint: .bottom))
                                }.clipShape(RoundedRectangle(cornerRadius: 8))
                            }.buttonStyle(.plain)
                        }
                    }.padding(.horizontal)
                } else {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 100), spacing: 4)], spacing: 4) {
                        ForEach(items) { it in
                            Button { Task { await choose(it) } } label: {
                                ImmichThumb(id: it.id).aspectRatio(1, contentMode: .fit)
                                    .overlay(alignment: .bottomTrailing) { if it.gif { Text("GIF").font(.caption2.bold()).padding(3).background(.black.opacity(0.6)).foregroundStyle(.white).cornerRadius(3).padding(4) } }
                                    .overlay { if busy == it.id { ProgressView() } }
                                    .clipShape(RoundedRectangle(cornerRadius: 6))
                            }.buttonStyle(.plain).disabled(busy != nil)
                        }
                    }.padding(.horizontal, 6)
                    if next != nil { Button("Mehr laden") { Task { await loadItems(reset: false) } }.padding() }
                }
            }
        }
        .padding(.top, 6)
    }

    // MARK: Server
    private func loadStatus() async {
        guard let d = try? await store.api("api/settings/immich"), let j = try? JSONSerialization.jsonObject(with: d) as? [String: Any] else { info = String(localized: "Nicht erreichbar"); status = (false, false); return }
        status = (j["available"] as? Bool ?? true, j["connected"] as? Bool ?? false)
        if url.isEmpty, let s = j["suggest"] as? String { url = s }
        if status?.connected == true { await loadItems(reset: true) }
    }
    private func connect() async {
        info = String(localized: "Prüfe …")
        do { _ = try await store.api("api/settings/immich", method: "POST", body: ["url": url.trimmingCharacters(in: .whitespaces), "apikey": key.trimmingCharacters(in: .whitespaces)]); key = ""; info = ""; await loadStatus() }
        catch { info = error.localizedDescription }
    }
    private func loadItems(reset: Bool) async {
        if reset { items = []; next = nil }
        var b: [String: Any] = ["page": reset ? 1 : (next ?? 1)]
        if let a = album { b["album"] = a.id }
        let q = query.trimmingCharacters(in: .whitespaces); if !q.isEmpty { b["q"] = q }
        info = items.isEmpty ? String(localized: "Lade …") : ""
        guard let d = try? await store.api("api/immich/assets", method: "POST", body: b), let j = try? JSONSerialization.jsonObject(with: d) as? [String: Any] else { info = String(localized: "Immich: Suche fehlgeschlagen"); return }
        let new = (j["items"] as? [[String: Any]] ?? []).compactMap { x -> Item? in
            guard let id = x["id"] as? String else { return nil }
            return Item(id: id, name: x["name"] as? String ?? "", gif: x["mime"] as? String == "image/gif")
        }
        items += new; next = j["next"] as? Int
        info = items.isEmpty ? String(localized: "Keine Fotos gefunden.") : ""
    }
    private func loadAlbums() async {
        album = nil; info = String(localized: "Lade …")
        guard let d = try? await store.api("api/immich/albums"), let j = try? JSONSerialization.jsonObject(with: d) as? [String: Any] else { info = String(localized: "Alben nicht lesbar"); return }
        albums = (j["albums"] as? [[String: Any]] ?? []).compactMap { a in
            guard let id = a["id"] as? String else { return nil }
            return Album(id: id, name: a["name"] as? String ?? "", count: a["count"] as? Int ?? 0, thumb: a["thumb"] as? String)
        }
        info = albums.isEmpty ? String(localized: "Keine Alben.") : ""
    }
    private func choose(_ it: Item) async {
        busy = it.id; info = String(localized: "Übernehme „\(it.name)“ …")
        struct R: Decodable { let url: String }
        do {
            let d = try await store.api("api/immich/import", method: "POST", body: ["id": it.id])
            let r = try JSONDecoder().decode(R.self, from: d)
            dismiss(); onPick(r.url)
        } catch { busy = nil; info = String(localized: "Nicht übernommen: \(error.localizedDescription)") }
    }
}

/// Vorschaubild eines Immich-Fotos (ueber den Server, mit Sitzung; zwischengespeichert)
struct ImmichThumb: View {
    let id: String?
    @EnvironmentObject var store: Store
    @State private var img: PlatformImage?
    var body: some View {
        Color.secondary.opacity(0.12)
            .overlay {
                if let img {
                    #if os(iOS)
                    Image(uiImage: img).resizable().scaledToFill()
                    #else
                    Image(nsImage: img).resizable().scaledToFill()
                    #endif
                }
            }
            .clipped()
            .task(id: id) {
                guard let id else { return }
                let key = "immich:" + id as NSString
                if let c = ImageStore.cache.object(forKey: key) { img = c; return }
                guard let d = await store.imageData("/api/immich/thumb/" + id) else { return }
                let decoded = await Task.detached(priority: .utility) { ImageStore.decode(d) }.value
                if let decoded { ImageStore.cache.setObject(decoded, forKey: key, cost: d.count * 4); img = decoded }
            }
    }
}
