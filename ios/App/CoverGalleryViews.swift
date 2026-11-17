import SwiftUI

// Saga 2.5 Titelbild-Galerie in der App (wie im Web: Titelbild -> "Galerie …" / "Zufällig" / "Über dieses Bild").
// Liste: GET /api/covers (einmal je Server, beim Oeffnen frisch), Setzen: PATCH cover + empfohlene cover_pos.
// Aelterer Server (404): Eintraege ausgeblendet. Logik in CoverGalleryLogic.swift.

extension Store {
    /// Bietet dieser Server die Titelbild-Galerie an (Liste gelesen, fuer den aktuellen Server)?
    var coverGalleryOffered: Bool { coverGallery != nil && coverGalleryOrigin != nil && coverGalleryOrigin == AppConfig.serverOrigin }

    /// Liste holen. Aelterer Server (404/403): Galerie weg; offline/5xx/unlesbar: bisheriger Stand bleibt.
    @discardableResult
    func loadCoverGallery() async -> CoverGalleryLogic.Listing? {
        guard let o = AppConfig.serverOrigin,
              let (d, code) = try? await apiResponse(CoverGalleryLogic.listPath), AppConfig.serverOrigin == o else {
            return coverGalleryOffered ? coverGallery : nil
        }
        switch CoverGalleryLogic.offered(code: code) {
        case true?:
            if let l = CoverGalleryLogic.parse(d) {
                if coverGallery != l { coverGallery = l }
                coverGalleryOrigin = o
            }
        case false?:
            coverGallery = nil; coverGalleryOrigin = o
        case nil:
            break
        }
        return coverGalleryOffered ? coverGallery : nil
    }
}

extension PageDoc {
    /// "Zufällig" (wie im Web): ein anderes sichtbares Galerie-Bild samt empfohlener Position
    func randomCover(_ store: Store) async {
        var l = store.coverGalleryOffered ? store.coverGallery : nil
        if l == nil { l = await store.loadCoverGallery() }
        guard let l else { return }
        guard let c = CoverGalleryLogic.random(l, remote: FedLogic.isRemote(id), current: cover),
              let f = CoverGalleryLogic.fields(c, current: cover) else {
            error = String(localized: "Keine Bilder in der Galerie."); return
        }
        await setHeader(f, store)
    }
}

/// Eintraege "Galerie …" und "Zufällig" fuer die Titelbild-Menues (nur, wenn der Server die Galerie anbietet)
struct CoverGalleryMenuItems: View {
    @ObservedObject var doc: PageDoc
    @EnvironmentObject var store: Store
    let open: () -> Void
    var body: some View {
        if store.coverGalleryOffered {
            Button(action: open) { Label("Galerie …", systemImage: "square.grid.2x2") }
            Button { Task { await doc.randomCover(store) } } label: { Label("Zufällig", systemImage: "shuffle") }
        }
    }
}

// MARK: - Auswahl nach Gruppen

struct CoverGallerySheet: View {
    @ObservedObject var doc: PageDoc
    @EnvironmentObject var store: Store
    @Environment(\.dismiss) private var dismiss
    @Environment(\.dynamicTypeSize) private var typeSize
    @State private var loading = false
    @State private var failed = false
    /// nur Bilder (Debug-Vorschau): nichts laden, nichts speichern
    var preview = false
    var onPick: ((CoverGalleryLogic.Cover) -> Void)? = nil

    private var listing: CoverGalleryLogic.Listing? { store.coverGalleryOffered || preview ? store.coverGallery : nil }
    private var remote: Bool { FedLogic.isRemote(doc.id) }

    var body: some View {
        NavigationStack {
            Group {
                if let l = listing {
                    let groups = CoverGalleryLogic.grouped(l, remote: remote, other: String(localized: "Weitere"))
                    if groups.isEmpty { message(Text("Keine Bilder in der Galerie.")) } else { grid(groups, l) }
                } else if failed && !loading {
                    VStack(spacing: 12) {
                        Text("Server nicht erreichbar").foregroundStyle(.secondary)
                        Button { Task { await load() } } label: { Label("Erneut versuchen", systemImage: "arrow.clockwise") }.buttonStyle(.bordered)
                    }.frame(maxWidth: .infinity, maxHeight: .infinity)
                } else {
                    ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
                }
            }
            .navigationTitle("Titelbild-Galerie")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Abbrechen") { dismiss() } }
                ToolbarItem(placement: .primaryAction) {
                    Button { random() } label: { Label("Zufällig", systemImage: "shuffle") }
                        .disabled(listing.map { CoverGalleryLogic.choosable($0, remote: remote).isEmpty } ?? true)
                }
            }
        }
        .macFrame(minWidth: 560, idealWidth: 720, minHeight: 560, idealHeight: 680)
        .task { if !preview { await load() } }
    }

    private func message(_ t: Text) -> some View {
        t.foregroundStyle(.secondary).multilineTextAlignment(.center).padding().frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private func grid(_ groups: [CoverGalleryLogic.Group], _ l: CoverGalleryLogic.Listing) -> some View {
        // Bedienungshilfen-Schriftgroessen: breitere Kacheln, damit Titel und Nachweis lesbar bleiben
        let minW: CGFloat = typeSize.isAccessibilitySize ? 280 : 150
        return ScrollView {
            LazyVStack(alignment: .leading, spacing: 18) {
                ForEach(groups) { g in
                    VStack(alignment: .leading, spacing: 8) {
                        Text(verbatim: g.label).font(.headline).accessibilityAddTraits(.isHeader)
                        LazyVGrid(columns: [GridItem(.adaptive(minimum: minW), spacing: 10, alignment: .top)], alignment: .leading, spacing: 12) {
                            ForEach(g.covers) { c in tile(c) }
                        }
                    }
                }
                if !l.source.isEmpty {
                    Text("Mitgelieferte Kunstwerke: gemeinfrei, \(l.source). Die Saga-Bilder sind KI-generiert.")
                        .font(.footnote).foregroundStyle(.secondary).padding(.top, 4)
                }
            }
            .padding()
        }
    }

    private func tile(_ c: CoverGalleryLogic.Cover) -> some View {
        let on = doc.cover == c.url
        return Button { choose(c) } label: {
            VStack(alignment: .leading, spacing: 0) {
                Color.clear.aspectRatio(7.0 / 4.0, contentMode: .fit)              // wie im Web (168 x 96)
                    .overlay { RemoteImage(path: c.thumb, fill: true, vAlign: c.pos) }
                    .clipped()
                VStack(alignment: .leading, spacing: 1) {
                    Text(verbatim: c.title).font(.subheadline).lineLimit(typeSize.isAccessibilitySize ? 3 : 1)
                    if !c.credit.isEmpty {
                        Text(verbatim: c.credit).font(.caption).foregroundStyle(.secondary).lineLimit(typeSize.isAccessibilitySize ? 2 : 1)
                    }
                }
                .padding(.horizontal, 8).padding(.vertical, 6)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .background(.background.secondary)
            .clipShape(RoundedRectangle(cornerRadius: 10))
            .overlay(RoundedRectangle(cornerRadius: 10).strokeBorder(on ? Color.accentColor : Color.secondary.opacity(0.25), lineWidth: on ? 3 : 1))
            .overlay(alignment: .topTrailing) {
                if on {
                    Image(systemName: "checkmark.circle.fill").font(.title3).symbolRenderingMode(.palette)
                        .foregroundStyle(.white, Color.accentColor).padding(6).accessibilityHidden(true)
                }
            }
            .contentShape(RoundedRectangle(cornerRadius: 10))
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(verbatim: c.label))
        .accessibilityAddTraits(on ? [.isButton, .isSelected] : .isButton)
        #if os(macOS)
        .help(Text(verbatim: c.label))
        #endif
    }

    private func load() async {
        loading = true; defer { loading = false }
        let l = await store.loadCoverGallery()
        failed = l == nil && !store.coverGalleryOffered
    }

    private func choose(_ c: CoverGalleryLogic.Cover) {
        dismiss()
        if let onPick { onPick(c); return }
        guard let f = CoverGalleryLogic.fields(c, current: doc.cover) else { return }      // schon das Titelbild: nichts speichern
        Task { await doc.setHeader(f, store) }
    }

    private func random() {
        guard let l = listing, let c = CoverGalleryLogic.random(l, remote: remote, current: doc.cover) else { return }
        choose(c)
    }
}

// MARK: - Ueber dieses Bild

struct CoverAboutSheet: View {
    let about: CoverGalleryLogic.About
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 10) {
                    switch about {
                    case .cover(let c):
                        Text(verbatim: c.title).font(.title3.weight(.semibold)).accessibilityAddTraits(.isHeader)
                        if !c.credit.isEmpty { Text(verbatim: c.credit) }
                        if !c.source.isEmpty { Text(verbatim: c.source).font(.footnote).foregroundStyle(.secondary) }
                        if let u = URL(string: c.sourceURL), !c.sourceURL.isEmpty {
                            Link(destination: u) { Text("Zur Quelle ↗") }.padding(.top, 4)
                        }
                    case .gone, .none:
                        Text("Ein Bild aus der Titelbild-Galerie (nicht mehr zur Auswahl).").foregroundStyle(.secondary)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding()
            }
            .navigationTitle("Über dieses Bild")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Fertig") { dismiss() } } }
        }
        .presentationDetents([.medium, .large])
        .macFrame(minWidth: 380, minHeight: 240)
    }
}

#if DEBUG && os(iOS)
// MARK: - Nur fuer Bilder (ohne Server): -hkCoverPreview vorher|menue|galerie|ueber
// Testdaten wie GET /api/covers (Auszug der mitgelieferten Bilder aus covers.json des Servers, Deutsch). Vorschaubilder: aus dem Ordner
// -hkCoverPreviewDir (<id>.jpg, z. B. aus static/covers verkleinert), sonst Farbverlaeufe.
enum CoverPreview {
    static var mode: String? { UserDefaults.standard.string(forKey: "hkCoverPreview") }
    static let json = #"""
{"covers": [
 {"id": "balke-nordkap", "url": "/covers/balke-nordkap.jpg", "thumb": "/covers/balke-nordkap.jpg?w=384", "title": "Das Nordkap im Mondlicht", "artist": "Peder Balke", "date": "1848", "source_url": "https://www.metmuseum.org/art/collection/search/441379", "source": "The Metropolitan Museum of Art, Open Access (CC0 1.0)", "group": "nordisch", "pos": 55, "builtin": true},
 {"id": "dahl-wasserfall", "url": "/covers/dahl-wasserfall.jpg", "thumb": "/covers/dahl-wasserfall.jpg?w=384", "title": "Zwei Männer vor einem Wasserfall bei Sonnenuntergang", "artist": "Johan Christian Dahl", "date": "1823", "source_url": "https://www.metmuseum.org/art/collection/search/439344", "source": "The Metropolitan Museum of Art, Open Access (CC0 1.0)", "group": "nordisch", "pos": 45, "builtin": true},
 {"id": "dahl-kopenhagen", "url": "/covers/dahl-kopenhagen.jpg", "thumb": "/covers/dahl-kopenhagen.jpg?w=384", "title": "Kopenhagener Hafen im Mondlicht", "artist": "Johan Christian Dahl", "date": "1846", "source_url": "https://www.metmuseum.org/art/collection/search/439343", "source": "The Metropolitan Museum of Art, Open Access (CC0 1.0)", "group": "nordisch", "pos": 40, "builtin": true},
 {"id": "friedrich-mond", "url": "/covers/friedrich-mond.jpg", "thumb": "/covers/friedrich-mond.jpg?w=384", "title": "Zwei Männer betrachten den Mond", "artist": "Caspar David Friedrich", "date": "ca. 1825–30", "source_url": "https://www.metmuseum.org/art/collection/search/438417", "source": "The Metropolitan Museum of Art, Open Access (CC0 1.0)", "group": "romantik", "pos": 40, "builtin": true},
 {"id": "turner-walfaenger", "url": "/covers/turner-walfaenger.jpg", "thumb": "/covers/turner-walfaenger.jpg?w=384", "title": "Walfänger", "artist": "J. M. W. Turner", "date": "ca. 1845", "source_url": "https://www.metmuseum.org/art/collection/search/437854", "source": "The Metropolitan Museum of Art, Open Access (CC0 1.0)", "group": "romantik", "pos": 50, "builtin": true},
 {"id": "carus-milkel", "url": "/covers/carus-milkel.jpg", "thumb": "/covers/carus-milkel.jpg?w=384", "title": "Schloss Milkel im Mondlicht", "artist": "Carl Gustav Carus", "date": "ca. 1833–35", "source_url": "https://www.metmuseum.org/art/collection/search/788905", "source": "The Metropolitan Museum of Art, Open Access (CC0 1.0)", "group": "romantik", "pos": 40, "builtin": true},
 {"id": "hokusai-welle", "url": "/covers/hokusai-welle.jpg", "thumb": "/covers/hokusai-welle.jpg?w=384", "title": "Die große Welle vor Kanagawa", "artist": "Katsushika Hokusai", "date": "ca. 1830–32", "source_url": "https://www.metmuseum.org/art/collection/search/45434", "source": "The Metropolitan Museum of Art, Open Access (CC0 1.0)", "group": "holzschnitt", "pos": 45, "builtin": true},
 {"id": "hokusai-roter-fuji", "url": "/covers/hokusai-roter-fuji.jpg", "thumb": "/covers/hokusai-roter-fuji.jpg?w=384", "title": "Roter Fuji", "artist": "Katsushika Hokusai", "date": "ca. 1830–32", "source_url": "https://www.metmuseum.org/art/collection/search/36490", "source": "The Metropolitan Museum of Art, Open Access (CC0 1.0)", "group": "holzschnitt", "pos": 50, "builtin": true},
 {"id": "hokusai-gewitter", "url": "/covers/hokusai-gewitter.jpg", "thumb": "/covers/hokusai-gewitter.jpg?w=384", "title": "Gewitter unterhalb des Fuji", "artist": "Katsushika Hokusai", "date": "ca. 1830–32", "source_url": "https://www.metmuseum.org/art/collection/search/36492", "source": "The Metropolitan Museum of Art, Open Access (CC0 1.0)", "group": "holzschnitt", "pos": 35, "builtin": true},
 {"id": "bierstadt-landers-peak", "url": "/covers/bierstadt-landers-peak.jpg", "thumb": "/covers/bierstadt-landers-peak.jpg?w=384", "title": "Die Rocky Mountains, Lander’s Peak", "artist": "Albert Bierstadt", "date": "1863", "source_url": "https://www.metmuseum.org/art/collection/search/10154", "source": "The Metropolitan Museum of Art, Open Access (CC0 1.0)", "group": "landschaft", "pos": 40, "builtin": true},
 {"id": "bierstadt-lake-louise", "url": "/covers/bierstadt-lake-louise.jpg", "thumb": "/covers/bierstadt-lake-louise.jpg?w=384", "title": "Kanadische Rockies (Lake Louise)", "artist": "Albert Bierstadt", "date": "ca. 1889", "source_url": "https://www.metmuseum.org/art/collection/search/10149", "source": "The Metropolitan Museum of Art, Open Access (CC0 1.0)", "group": "landschaft", "pos": 45, "builtin": true},
 {"id": "cole-oxbow", "url": "/covers/cole-oxbow.jpg", "thumb": "/covers/cole-oxbow.jpg?w=384", "title": "The Oxbow", "artist": "Thomas Cole", "date": "1836", "source_url": "https://www.metmuseum.org/art/collection/search/10497", "source": "The Metropolitan Museum of Art, Open Access (CC0 1.0)", "group": "landschaft", "pos": 40, "builtin": true},
 {"id": "saga-am-kaminfeuer", "url": "/covers/saga-am-kaminfeuer.jpg", "thumb": "/covers/saga-am-kaminfeuer.jpg?w=384", "title": "Am Kaminfeuer", "artist": "", "date": "", "source_url": "", "source": "KI-generiert für Saga", "group": "saga-stube", "pos": 20, "builtin": true},
 {"id": "saga-warme-decke-heisser-tee", "url": "/covers/saga-warme-decke-heisser-tee.jpg", "thumb": "/covers/saga-warme-decke-heisser-tee.jpg?w=384", "title": "Warme Decke, heißer Tee", "artist": "", "date": "", "source_url": "", "source": "KI-generiert für Saga", "group": "saga-stube", "pos": 20, "builtin": true},
 {"id": "saga-schreiben-unter-dem-nordlicht", "url": "/covers/saga-schreiben-unter-dem-nordlicht.jpg", "thumb": "/covers/saga-schreiben-unter-dem-nordlicht.jpg?w=384", "title": "Schreiben unter dem Nordlicht", "artist": "", "date": "", "source_url": "", "source": "KI-generiert für Saga", "group": "saga-schreibstube", "pos": 20, "builtin": true},
 {"id": "saga-lesen-am-kamin", "url": "/covers/saga-lesen-am-kamin.jpg", "thumb": "/covers/saga-lesen-am-kamin.jpg?w=384", "title": "Lesen am Kamin", "artist": "", "date": "", "source_url": "", "source": "KI-generiert für Saga", "group": "saga-schreibstube", "pos": 20, "builtin": true},
 {"id": "saga-im-gewaechshaus", "url": "/covers/saga-im-gewaechshaus.jpg", "thumb": "/covers/saga-im-gewaechshaus.jpg?w=384", "title": "Im Gewächshaus", "artist": "", "date": "", "source_url": "", "source": "KI-generiert für Saga", "group": "saga-garten", "pos": 20, "builtin": true},
 {"id": "saga-pflanzen-setzen", "url": "/covers/saga-pflanzen-setzen.jpg", "thumb": "/covers/saga-pflanzen-setzen.jpg?w=384", "title": "Pflanzen setzen", "artist": "", "date": "", "source_url": "", "source": "KI-generiert für Saga", "group": "saga-garten", "pos": 20, "builtin": true},
 {"id": "saga-sonnenuntergang-am-see", "url": "/covers/saga-sonnenuntergang-am-see.jpg", "thumb": "/covers/saga-sonnenuntergang-am-see.jpg?w=384", "title": "Sonnenuntergang am See", "artist": "", "date": "", "source_url": "", "source": "KI-generiert für Saga", "group": "saga-natur", "pos": 20, "builtin": true},
 {"id": "saga-am-hafen", "url": "/covers/saga-am-hafen.jpg", "thumb": "/covers/saga-am-hafen.jpg?w=384", "title": "Am Hafen", "artist": "", "date": "", "source_url": "", "source": "KI-generiert für Saga", "group": "saga-natur", "pos": 30, "builtin": true},
 {"id": "saga-mondnacht-am-weltenbaum", "url": "/covers/saga-mondnacht-am-weltenbaum.jpg", "thumb": "/covers/saga-mondnacht-am-weltenbaum.jpg?w=384", "title": "Mondnacht am Weltenbaum", "artist": "", "date": "", "source_url": "", "source": "KI-generiert für Saga", "group": "saga-winter", "pos": 20, "builtin": true},
 {"id": "saga-nordlicht-ueber-der-kueste", "url": "/covers/saga-nordlicht-ueber-der-kueste.jpg", "thumb": "/covers/saga-nordlicht-ueber-der-kueste.jpg?w=384", "title": "Nordlicht über der Küste", "artist": "", "date": "", "source_url": "", "source": "KI-generiert für Saga", "group": "saga-winter", "pos": 20, "builtin": true},
 {"id": "saga-im-schneesturm", "url": "/covers/saga-im-schneesturm.jpg", "thumb": "/covers/saga-im-schneesturm.jpg?w=384", "title": "Im Schneesturm", "artist": "", "date": "", "source_url": "", "source": "KI-generiert für Saga", "group": "saga-kriegerin", "pos": 20, "builtin": true},
 {"id": "saga-gewitter-ueber-dem-fjord", "url": "/covers/saga-gewitter-ueber-dem-fjord.jpg", "thumb": "/covers/saga-gewitter-ueber-dem-fjord.jpg?w=384", "title": "Gewitter über dem Fjord", "artist": "", "date": "", "source_url": "", "source": "KI-generiert für Saga", "group": "saga-kriegerin", "pos": 20, "builtin": true},
 {"id": "saga-herrin-des-gewitters", "url": "/covers/saga-herrin-des-gewitters.jpg", "thumb": "/covers/saga-herrin-des-gewitters.jpg?w=384", "title": "Herrin des Gewitters", "artist": "", "date": "", "source_url": "", "source": "KI-generiert für Saga", "group": "saga-magie", "pos": 20, "builtin": true},
 {"id": "saga-nordlichtzauber", "url": "/covers/saga-nordlichtzauber.jpg", "thumb": "/covers/saga-nordlichtzauber.jpg?w=384", "title": "Nordlichtzauber", "artist": "", "date": "", "source_url": "", "source": "KI-generiert für Saga", "group": "saga-magie", "pos": 20, "builtin": true},
 {"id": "saga-der-hund-mit-dem-wikingerhelm", "url": "/covers/saga-der-hund-mit-dem-wikingerhelm.jpg", "thumb": "/covers/saga-der-hund-mit-dem-wikingerhelm.jpg?w=384", "title": "Der Hund mit dem Wikingerhelm", "artist": "", "date": "", "source_url": "", "source": "KI-generiert für Saga", "group": "saga-gefaehrten", "pos": 30, "builtin": true},
 {"id": "saga-lesen-mit-dem-wolf", "url": "/covers/saga-lesen-mit-dem-wolf.jpg", "thumb": "/covers/saga-lesen-mit-dem-wolf.jpg?w=384", "title": "Lesen mit dem Wolf", "artist": "", "date": "", "source_url": "", "source": "KI-generiert für Saga", "group": "saga-gefaehrten", "pos": 30, "builtin": true}],
 "groups": [{"id": "nordisch", "label": "Nordisch"}, {"id": "romantik", "label": "Romantik"}, {"id": "holzschnitt", "label": "Holzschnitt"}, {"id": "landschaft", "label": "Landschaft"}, {"id": "saga-stube", "label": "Saga · Stube"}, {"id": "saga-schreibstube", "label": "Saga · Schreibstube"}, {"id": "saga-garten", "label": "Saga · Garten & Küche"}, {"id": "saga-natur", "label": "Saga · Natur"}, {"id": "saga-winter", "label": "Saga · Winter & Nordlicht"}, {"id": "saga-kriegerin", "label": "Saga · Kriegerin"}, {"id": "saga-magie", "label": "Saga · Magie"}, {"id": "saga-gefaehrten", "label": "Saga · Gefährten"}],
 "license": "CC0-1.0", "source": "The Metropolitan Museum of Art, Open Access (CC0 1.0)", "random_cover": true}
"""#

    @MainActor static func prime(_ l: CoverGalleryLogic.Listing) {
        let dir = UserDefaults.standard.string(forKey: "hkCoverPreviewDir")
        let colors: [(UIColor, UIColor)] = [(.systemIndigo, .systemTeal), (.systemBrown, .systemOrange), (.systemGreen, .systemMint),
                                             (.systemPurple, .systemPink), (.systemBlue, .systemCyan)]
        for (i, c) in l.covers.enumerated() {
            var img: UIImage?
            if let dir, let d = FileManager.default.contents(atPath: dir + "/" + c.id + ".jpg") { img = UIImage(data: d) }
            if img == nil {
                let (a, b) = colors[i % colors.count]
                img = UIGraphicsImageRenderer(size: CGSize(width: 384, height: 220)).image { ctx in
                    let g = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(), colors: [a.cgColor, b.cgColor] as CFArray, locations: [0, 1])!
                    ctx.cgContext.drawLinearGradient(g, start: .zero, end: CGPoint(x: 384, y: 220), options: [])
                }
            }
            if let img {
                ImageStore.cache.setObject(img, forKey: c.thumb as NSString)
                ImageStore.cache.setObject(img, forKey: c.url as NSString)
            }
        }
    }
}

/// Seitenkopf mit Titelbild und dem Titelbild-Menue als Liste (Menues lassen sich nicht ohne Antippen abbilden)
struct CoverPreviewView: View {
    @ObservedObject var store: Store
    @StateObject private var doc = PageDoc(id: 1)
    @State private var gallery = false
    @State private var about = false
    @State private var ready = false
    private var before: Bool { CoverPreview.mode == "vorher" }
    var body: some View {
        NavigationStack {
            List {
                Section {
                    Color.clear.frame(height: 150)
                        .overlay { if let c = doc.cover { RemoteImage(path: c, fill: true, vAlign: HeaderLogic.coverPos(doc.page["cover_pos"])) } }
                        .clipped().listRowInsets(EdgeInsets())
                    Text(verbatim: "Reisetagebuch Nord").font(.title2.bold())
                }
                Section {
                    if !before {
                        Button { gallery = true } label: { Label("Galerie …", systemImage: "square.grid.2x2") }
                        Button {} label: { Label("Zufällig", systemImage: "shuffle") }
                    }
                    Button {} label: { Label("Fotos …", systemImage: "photo") }
                    Button {} label: { Label("Aus Immich …", systemImage: "photo.on.rectangle.angled") }
                    if !before && CoverGalleryLogic.about(store.coverGallery, current: doc.cover) != .none {
                        Button { about = true } label: { Label("Über dieses Bild", systemImage: "info.circle") }
                    }
                } header: { Text(verbatim: "Titelbild") }
                Section { Button("Neu positionieren") {}; Button("Entfernen") {} }
            }
            .navigationTitle(Text(verbatim: before ? "Vorher" : "Nachher"))
            .navigationBarTitleDisplayMode(.inline)
        }
        .environmentObject(store)
        .sheet(isPresented: $gallery) {
            CoverGallerySheet(doc: doc, preview: true) { c in
                doc.page["cover"] = c.url; doc.page["cover_pos"] = c.pos; doc.objectWillChange.send()
            }.environmentObject(store)
        }
        .sheet(isPresented: $about) { CoverAboutSheet(about: CoverGalleryLogic.about(store.coverGallery, current: doc.cover)) }
        .task {
            guard !ready, let l = CoverGalleryLogic.parse(Data(CoverPreview.json.utf8)) else { return }
            ready = true
            CoverPreview.prime(l)
            store.coverGallery = l
            doc.page["cover"] = "/covers/hokusai-welle.jpg"; doc.page["cover_pos"] = 45.0; doc.objectWillChange.send()
            if CoverPreview.mode == "galerie" { gallery = true }
            if CoverPreview.mode == "ueber" { about = true }
        }
    }
}
#endif
