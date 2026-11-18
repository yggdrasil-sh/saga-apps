import SwiftUI

// 2.5 (Bugs-DB 2156): Bereich "Saga-Siegel" in der Symbol-Auswahl (IconPicker). Farbwahl (3, gemerkt je Geraet; ist das
// jetzige Symbol ein Siegel, dessen Farbe), Raster der Motive aus GET /api/siegel (Namen in der App-Sprache). Nur, wenn der
// eigene Server siegel1 meldet und bei fremden Seiten auch deren Server (remote.caps) - aeltere Server kuerzen den Pfad.
// Bilder: /siegel/<farbe>/<motiv>.svg?w=128 -> PNG. VoiceOver: "Siegel Rabe (Weinrot)", gewaehltes mit "ausgewaehlt".

/// Liste je Server und Sprache (einmal laden, auch fuer den Namen im Seitenkopf)
@MainActor
enum SiegelCache {
    static var lists: [String: SiegelLogic.Listing] = [:]
    static var key: String { (AppConfig.serverOrigin ?? "") + "|" + AppConfig.appLanguage }
    static var current: SiegelLogic.Listing? { lists[key] }

    /// nil = nicht geladen (offline, aelterer Server, unlesbar)
    static func load(_ store: Store) async -> SiegelLogic.Listing? {
        let k = key
        if let l = lists[k] { return l }
        #if DEBUG && os(iOS)
        if let l = SiegelPreview.listing { lists[k] = l; return l }
        #endif
        guard let (d, code) = try? await store.apiResponse(SiegelLogic.listPath), SiegelLogic.offered(code: code) == true,
              let l = SiegelLogic.parse(listing: d) else { return nil }
        lists[k] = l
        return l
    }
}

struct SiegelSection: View {
    /// jetziges Symbol der Seite
    let current: String?
    let pick: (String) -> Void
    @EnvironmentObject var store: Store
    @AppStorage(SiegelLogic.rememberKey) private var remembered = ""
    @State private var color = ""
    @State private var listing: SiegelLogic.Listing?
    @State private var failed = false
    @ScaledMetric(relativeTo: .body) private var cell: CGFloat = 52

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Saga-Siegel").font(.headline).accessibilityAddTraits(.isHeader)
            if let l = listing {
                Picker(selection: $color) {
                    ForEach(l.colors) { c in Text(verbatim: c.label).tag(c.id) }
                } label: { Text("Farbe der Siegel") }
                    .pickerStyle(.segmented)
                    .onChange(of: color) { _, c in if !c.isEmpty { remembered = c } }
                LazyVGrid(columns: [GridItem(.adaptive(minimum: max(44, cell), maximum: max(72, cell * 1.4)), spacing: 8)], spacing: 8) {
                    ForEach(l.motifs) { m in tile(m, l) }
                }
                .accessibilityElement(children: .contain)
                .accessibilityLabel(Text("Saga-Siegel"))
            } else if failed {
                HStack(spacing: 10) {
                    Text("Saga-Siegel nicht geladen.").font(.subheadline).foregroundStyle(.secondary)
                    Button { Task { await load() } } label: { Label("Erneut versuchen", systemImage: "arrow.clockwise") }.buttonStyle(.bordered)
                }
            } else {
                ProgressView().frame(maxWidth: .infinity, minHeight: 60)
            }
        }
        .task { if color.isEmpty { color = SiegelLogic.initialColor(current: current, remembered: remembered) }; await load() }
    }

    private func tile(_ m: SiegelLogic.Entry, _ l: SiegelLogic.Listing) -> some View {
        let p = SiegelLogic.path(color: color, motif: m.id)
        let name = SiegelLogic.name(color: color, motif: m.id, in: l)
        let on = p == current
        return Button {
            remembered = color
            pick(p)
        } label: {
            Color.clear.aspectRatio(1, contentMode: .fit)
                .overlay { RemoteImage(path: p, fill: true, w: SiegelLogic.gridWidth) }
                .clipShape(Circle())
                .padding(3)
                .overlay { if on { Circle().strokeBorder(Color.accentColor, lineWidth: 3) } }
                .frame(minWidth: 44, minHeight: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text("Siegel \(name)"))
        .accessibilityAddTraits(on ? .isSelected : [])
        .help(Text(verbatim: name))
    }

    private func load() async {
        failed = false
        if let l = await SiegelCache.load(store) {
            listing = l
            if !l.colors.contains(where: { $0.id == color }) { color = l.colors.first?.id ?? SiegelLogic.colorIDs[0] }
        } else { failed = true }
    }
}

#if DEBUG && os(iOS)
// MARK: - Nur fuer Bilder (ohne Server): -hkSiegelPreview vorher|nachher|auswahl
// Siegel-PNGs aus dem Ordner -hkSiegelPreviewDir (<farbe>/<motiv>.png wie static/siegel des Servers), Liste wie GET /api/siegel
// (Deutsch). "vorher" zeigt nach, wie die App bis 2.5 ein Siegel anzeigte (Pfad als Text).
enum SiegelPreview {
    static var mode: String? { UserDefaults.standard.string(forKey: "hkSiegelPreview") }
    static var dir: String? { UserDefaults.standard.string(forKey: "hkSiegelPreviewDir") }
    static let ids = ["yggdrasil", "rabe", "drachenboot", "horn", "rune", "buch", "feder", "schriftrolle", "kerze", "laterne", "mond", "sonne",
                      "nordlicht", "berg", "wald", "welle", "kompass", "karte", "schluessel", "schild", "hammer", "axt", "krone", "kristall",
                      "trank", "feuer", "stern", "herz", "brief", "sanduhr", "zahnrad", "truhe", "server", "terminal", "katze", "teetasse"]
    static let names = ["Yggdrasil", "Rabe", "Drachenboot", "Horn", "Rune", "Buch", "Feder", "Schriftrolle", "Kerze", "Laterne", "Mond", "Sonne",
                        "Nordlicht", "Berg", "Wald", "Welle", "Kompass", "Karte", "Schlüssel", "Schild", "Hammer", "Axt", "Krone", "Kristall",
                        "Trank", "Feuer", "Stern", "Herz", "Brief", "Sanduhr", "Zahnrad", "Truhe", "Server", "Terminal", "Katze", "Teetasse"]
    static var listing: SiegelLogic.Listing? {
        guard mode != nil else { return nil }
        return SiegelLogic.Listing(colors: [.init(id: "weinrot", label: "Weinrot"), .init(id: "smaragd", label: "Smaragd"), .init(id: "elfenbein", label: "Elfenbein")],
                                   motifs: zip(ids, names).map { .init(id: $0.0, label: $0.1) })
    }
    /// Store.imageData: Siegel aus dem Ordner statt vom Server
    static func data(_ path: String) -> Data? {
        guard mode != nil, let dir, let p = SiegelLogic.parse(String(path.split(separator: "?").first ?? "")) else { return nil }
        return FileManager.default.contents(atPath: "\(dir)/\(p.color)/\(p.motif).png")
    }
}

/// Seitenkopf mit Siegel und eine Seitenliste (wie die Seitenleiste) - "auswahl" oeffnet die Symbol-Auswahl
struct SiegelPreviewView: View {
    @ObservedObject var store: Store
    @StateObject private var doc = PageDoc(id: 1)
    @State private var picker = false
    @State private var ready = false
    private var before: Bool { SiegelPreview.mode == "vorher" }
    private let rows: [(String, String)] = [("/siegel/weinrot/rabe.svg", "Reisetagebuch Nord"), ("/siegel/smaragd/yggdrasil.svg", "Familienstammbaum"),
                                            ("/siegel/elfenbein/buch.svg", "Lesezirkel"), ("📚", "Bibliothek"), ("/siegel/weinrot/teetasse.svg", "Rezepte"),
                                            ("/siegel/smaragd/server.svg", "Homelab")]
    var body: some View {
        NavigationStack {
            List {
                Section {
                    VStack(alignment: .leading, spacing: 8) {
                        if before { Text(verbatim: doc.icon ?? "").font(.system(size: 64)).lineLimit(2).minimumScaleFactor(0.3) }
                        else { IconRow(doc: doc) }
                        Text(verbatim: "Reisetagebuch Nord").font(.largeTitle.bold())
                    }
                    .listRowInsets(EdgeInsets(top: 12, leading: 16, bottom: 12, trailing: 16))
                }
                Section {
                    ForEach(rows, id: \.1) { r in
                        Label {
                            Text(verbatim: r.1)
                        } icon: {
                            if before && r.0.hasPrefix("/") { Text(verbatim: r.0).font(.caption2).lineLimit(1) }
                            else { PageIcon(icon: r.0, store: store) }
                        }
                    }
                } header: { Text(verbatim: "Seiten") }
            }
            .navigationTitle(Text(verbatim: before ? "Vorher" : "Nachher"))
            .navigationBarTitleDisplayMode(.inline)
        }
        .environmentObject(store)
        .sheet(isPresented: $picker) { IconPicker(doc: doc, preview: true).environmentObject(store) }
        .task {
            guard !ready else { return }
            ready = true
            store.features = ServerFeatures(["siegel1"])
            doc.page["icon"] = "/siegel/weinrot/rabe.svg"; doc.objectWillChange.send()
            if SiegelPreview.mode == "auswahl" { picker = true }
        }
    }
}
#endif
