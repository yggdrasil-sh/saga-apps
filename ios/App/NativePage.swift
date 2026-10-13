import SwiftUI
import ImageIO
import PhotosUI
#if os(iOS)
import UIKit
#else
import AppKit
#endif

// MARK: - Navigation

#if os(macOS)
/// Mac: Platz fuer die Block-Griffe (+ ≡) links neben Bloecken - am Seitenrand und zwischen Spalten (wie Notion)
let pageGutter: CGFloat = 60, columnGap: CGFloat = 56
#else
let pageGutter: CGFloat = 18, columnGap: CGFloat = 22
#endif

/// Ziel im Navigationsstapel: Seite nativ oder (zum Bearbeiten) im Web-Editor.
struct PageRoute: Hashable { let id: Int; var web = false }

// MARK: - Inline-HTML -> AttributedString

/// Wandelt das gespeicherte, bereinigte Inline-HTML (b/i/u/s/mark/code/a/br) in formatierten Text um.
/// Verweise auf Seiten (/notes#123) werden zu hkpage://123 (oeffnet nativ), http(s) bleibt extern.
func inlineText(_ html: String) -> AttributedString {
    var out = AttributedString()
    var bold = 0, italic = 0, under = 0, strike = 0, mark = 0, code = 0
    var links: [URL?] = []
    let re = try! NSRegularExpression(pattern: "<(/?)([a-zA-Z]+)([^>]*)>")
    let ns = html as NSString
    var pos = 0
    func emit(_ raw: String) {
        guard !raw.isEmpty else { return }
        var s = AttributedString(decodeEntities(raw))
        var intent: InlinePresentationIntent = []
        if bold > 0 { intent.insert(.stronglyEmphasized) }
        if italic > 0 { intent.insert(.emphasized) }
        if strike > 0 { intent.insert(.strikethrough) }
        if code > 0 { intent.insert(.code) }
        if !intent.isEmpty { s.inlinePresentationIntent = intent }
        if under > 0 { s.underlineStyle = .single }
        if mark > 0 { s.backgroundColor = Color.yellow.opacity(0.35) }
        if let l = links.last, let u = l { s.link = u; s.foregroundColor = .accentColor }
        out += s
    }
    for m in re.matches(in: html, range: NSRange(location: 0, length: ns.length)) {
        emit(ns.substring(with: NSRange(location: pos, length: m.range.location - pos)))
        pos = m.range.location + m.range.length
        let closing = ns.substring(with: m.range(at: 1)) == "/", tag = ns.substring(with: m.range(at: 2)).lowercased()
        let d = closing ? -1 : 1
        switch tag {
        case "b", "strong": bold += d
        case "i", "em": italic += d
        case "u": under += d
        case "s", "strike", "del": strike += d
        case "mark": mark += d
        case "code": code += d
        case "br": if !closing { out += AttributedString("\n") }
        case "a":
            if closing { _ = links.popLast() } else {
                let attrs = ns.substring(with: m.range(at: 3))
                let href = attrs.range(of: #"href="([^"]*)""#, options: .regularExpression).map { String(attrs[$0].dropFirst(6).dropLast()) } ?? ""
                links.append(linkURL(decodeEntities(href)))
            }
        default: break
        }
    }
    emit(ns.substring(from: pos))
    return out
}

func linkURL(_ href: String) -> URL? {
    if let r = href.range(of: #"^/notes#(\d+)$"#, options: .regularExpression) { return URL(string: "hkpage://" + href[r].split(separator: "#").last!) }
    if href.hasPrefix("http://") || href.hasPrefix("https://") || href.hasPrefix("mailto:") { return URL(string: href) }
    return nil
}

func decodeEntities(_ s: String) -> String {
    guard s.contains("&") else { return s }
    var r = s
    for (k, v) in [("&nbsp;", " "), ("&lt;", "<"), ("&gt;", ">"), ("&quot;", "\""), ("&#39;", "'"), ("&#x27;", "'"), ("&amp;", "&")] { r = r.replacingOccurrences(of: k, with: v) }
    return r
}

func plain(_ html: String) -> String { String(inlineText(html).characters) }

// MARK: - Bilder (inkl. animierter GIFs)

/// Bild verkleinert dekodieren: ein 8K-Foto braucht entpackt ~130 MB - fuer die Anzeige reichen ~1600 px.
func downsampled(_ src: CGImageSource, _ i: Int, maxPixel: Int = 1600) -> CGImage? {
    let opts: [CFString: Any] = [kCGImageSourceCreateThumbnailFromImageAlways: true, kCGImageSourceThumbnailMaxPixelSize: maxPixel,
                                 kCGImageSourceCreateThumbnailWithTransform: true, kCGImageSourceShouldCacheImmediately: true]
    return CGImageSourceCreateThumbnailAtIndex(src, i, opts as CFDictionary)
}

#if os(iOS)
typealias PlatformImage = UIImage
#else
typealias PlatformImage = NSImage
#endif

/// Entpackte Bilder (inkl. GIF-Einzelbilder) zwischenspeichern - nicht bei jedem Erscheinen neu auf dem Hauptthread
enum ImageStore {
    nonisolated(unsafe) static let cache: NSCache<NSString, PlatformImage> = { let c = NSCache<NSString, PlatformImage>(); c.totalCostLimit = 300 * 1024 * 1024; return c }()
    /// im Hintergrund aufrufen
    nonisolated static func decode(_ d: Data) -> PlatformImage? {
        guard let src = CGImageSourceCreateWithData(d as CFData, nil) else { return nil }
        let n = CGImageSourceGetCount(src)
        #if os(iOS)
        if n <= 1 { return downsampled(src, 0).map { UIImage(cgImage: $0) } ?? UIImage(data: d) }
        var frames: [UIImage] = [], total = 0.0
        for i in 0..<min(n, 300) {
            guard let cg = downsampled(src, i, maxPixel: 800) ?? CGImageSourceCreateImageAtIndex(src, i, nil) else { continue }
            let props = CGImageSourceCopyPropertiesAtIndex(src, i, nil) as? [CFString: Any]
            let gif = props?[kCGImagePropertyGIFDictionary] as? [CFString: Any]
            let delay = (gif?[kCGImagePropertyGIFUnclampedDelayTime] as? Double) ?? (gif?[kCGImagePropertyGIFDelayTime] as? Double) ?? 0.1
            total += max(delay, 0.02); frames.append(UIImage(cgImage: cg))
        }
        return UIImage.animatedImage(with: frames, duration: total)
        #else
        if n <= 1, let cg = downsampled(src, 0) { return NSImage(cgImage: cg, size: NSSize(width: cg.width, height: cg.height)) }
        return NSImage(data: d)                           // GIF: NSImageView spielt es selbst ab
        #endif
    }
}

#if os(iOS)
/// Meldet KEINE eigene Groesse - die Groesse bestimmt allein SwiftUI (sonst schaukeln sich beide hoch)
final class FlexibleImageView: UIImageView {
    override var intrinsicContentSize: CGSize { CGSize(width: UIView.noIntrinsicMetric, height: UIView.noIntrinsicMetric) }
}

struct AnimatedImage: UIViewRepresentable {
    let image: UIImage
    var fill = false
    func makeUIView(context: Context) -> UIImageView {
        let v = FlexibleImageView(); v.contentMode = fill ? .scaleAspectFill : .scaleAspectFit; v.clipsToBounds = true
        v.image = image; return v
    }
    func updateUIView(_ v: UIImageView, context: Context) { if v.image !== image { v.image = image } }
    func sizeThatFits(_ proposal: ProposedViewSize, uiView: UIImageView, context: Context) -> CGSize? { proposal.replacingUnspecifiedDimensions(by: CGSize(width: 100, height: 100)) }
}
#else
/// Meldet KEINE eigene Groesse: sonst kaempfen Bildgroesse und SwiftUI-Rahmen gegeneinander -> Layout-Endlosschleife
final class FlexibleImageView: NSImageView {
    override var intrinsicContentSize: NSSize { NSSize(width: NSView.noIntrinsicMetric, height: NSView.noIntrinsicMetric) }
}

struct AnimatedImage: NSViewRepresentable {
    let image: NSImage
    var fill = false
    func makeNSView(context: Context) -> NSImageView {
        let v = FlexibleImageView(); v.animates = true; v.imageScaling = fill ? .scaleAxesIndependently : .scaleProportionallyUpOrDown
        v.image = image; return v
    }
    func updateNSView(_ v: NSImageView, context: Context) { if v.image !== image { v.image = image } }
    func sizeThatFits(_ proposal: ProposedViewSize, nsView: NSImageView, context: Context) -> CGSize? { proposal.replacingUnspecifiedDimensions(by: CGSize(width: 100, height: 100)) }
}
#endif

/// Bild vom Server (/files/...) mit der Sitzung laden und (animiert) anzeigen.
struct RemoteImage: View {
    static func sized(_ path: String, _ w: Int?) -> String {
        guard let w, path.hasPrefix("/files/"), !path.contains("?") else { return path }
        return "\(path)?w=\(w)"
    }
    let path: String
    @EnvironmentObject var store: Store
    var fill = false
    var vAlign: Double = 50                               // Titelbild-Position 0 (oben) ... 100 (unten)
    var w: Int? = nil                                     // verkleinert laden (?w=, Server rechnet einmal und merkt es sich)
    @State private var img: PlatformImage?
    @State private var ratio: CGFloat?
    var body: some View {
        Group {
            if let img, let ratio {
                if fill {
                    // wie CSS object-fit: cover -> Flaeche ganz bedecken, Seitenverhaeltnis behalten, Rest abschneiden
                    GeometryReader { g in
                        let W = g.size.width, H = max(g.size.height, 1)
                        let s = W / H > ratio ? CGSize(width: W, height: W / ratio) : CGSize(width: H * ratio, height: H)
                        let y = s.height / 2 - (s.height - H) * CGFloat(min(100, max(0, vAlign)) / 100)   // stufenlos wie im Web
                        AnimatedImage(image: img, fill: true).frame(width: s.width, height: s.height).position(x: W / 2, y: y)
                    }
                    .clipped()
                } else {
                    AnimatedImage(image: img, fill: false).aspectRatio(ratio, contentMode: .fit)
                }
            } else { Rectangle().fill(.secondary.opacity(0.12)) }
        }
        .task(id: path) {
            let path = Self.sized(path, w)
            if let c = ImageStore.cache.object(forKey: path as NSString) { ratio = c.size.height > 0 ? c.size.width / c.size.height : 1; img = c; return }
            guard let d = await store.imageData(path) else { return }
            let decoded = await Task.detached(priority: .userInitiated) { ImageStore.decode(d) }.value      // im Hintergrund entpacken
            guard let decoded else { return }
            ImageStore.cache.setObject(decoded, forKey: path as NSString, cost: d.count * 4)
            ratio = decoded.size.height > 0 ? decoded.size.width / decoded.size.height : 1
            img = decoded
        }
    }
}

// MARK: - Seite laden/speichern

@MainActor
final class PageDoc: ObservableObject {
    let id: Int
    @Published var page: [String: Any] = [:]
    @Published var parentSchema: [String: Any]?
    @Published var error: String?
    @Published var loaded = false
    /// Echte Daten (Server oder Zwischenspeicher) angekommen. Vorher darf NIE gespeichert werden -
    /// sonst ueberschreibt die leere Ansicht ("Unbenannt") nach einem Ladefehler die Seite auf dem Server.
    @Published var fetched = false
    private(set) var root = NSMutableArray()
    private var doc: NSMutableDictionary = [:]
    private var rev = 0
    private var saving = false
    init(id: Int) { self.id = id }

    var titleRaw: String { page["title"] as? String ?? "" }
    var title: String { titleRaw.isEmpty ? String(localized: "Unbenannt") : titleRaw }
    var icon: String? { page["icon"] as? String }
    var cover: String? { (page["cover"] as? String).flatMap { $0.isEmpty ? nil : $0 } }
    var props: [String: Any] { page["props"] as? [String: Any] ?? [:] }
    var isDatabase: Bool { page["kind"] as? String == "database" }

    /// Jeder Block braucht eine feste ID (Identitaet beim Einfuegen/Verschieben)
    private func ensureIDs(_ arr: NSMutableArray) {
        for raw in arr {
            guard let b = raw as? NSMutableDictionary else { continue }
            if (b["id"] as? String ?? "").isEmpty { b["id"] = newBlockID() }
            let d = b["data"] as? NSMutableDictionary
            if let inner = d?["blocks"] as? NSMutableArray { ensureIDs(inner) }
            if let cols = d?["cols"] as? NSArray { for c in cols { if let inner = (c as? NSDictionary)?["blocks"] as? NSMutableArray { ensureIDs(inner) } } }
        }
    }

    var dirty = false                                            // ungespeicherte Eingaben -> nicht ueberschreiben

    func load(_ store: Store) async {
        if !loaded, let c = store.cachedData("api/notes/\(id)") { await apply(c, store) }     // sofort anzeigen
        do {
            let d = try await store.get("api/notes/\(id)")
            if dirty && loaded { return }                     // eigene Eingaben schuetzen - aber nur, wenn schon etwas angezeigt wird
            await apply(d, store)
        } catch { if !fetched { self.error = error.localizedDescription; loaded = true } }
    }

    private func apply(_ d: Data, _ store: Store) async {
        guard let p = try? JSONSerialization.jsonObject(with: d) as? [String: Any] else { return }
        page = p; rev = p["rev"] as? Int ?? 0
        let body = p["body"] as? String ?? ""
        if p["format"] as? String == "blocks", let bd = body.data(using: .utf8),
           let m = try? JSONSerialization.jsonObject(with: bd, options: .mutableContainers) as? NSMutableDictionary,
           let arr = m["blocks"] as? NSMutableArray {
            doc = m; root = arr
        } else {                                     // alte Textnotiz -> Absaetze
            root = NSMutableArray(array: body.split(separator: "\n", omittingEmptySubsequences: true).map { PageEditor.make("paragraph", text: mdToHTML(String($0))) })
            doc = ["blocks": root]
        }
        ensureIDs(root)
        if root.count == 0 && !(p["kind"] as? String == "database") { root.add(PageEditor.make("paragraph")) }
        // Eigenschaften nur, wenn die uebergeordnete Seite eine Datenbank ist (spart eine Anfrage)
        if let parent = p["parent_id"] as? Int, store.page(parent)?.isDatabase == true {
            let pd = store.cachedData("api/notes/\(parent)")
            let fresh = try? await store.get("api/notes/\(parent)")
            if let raw = fresh ?? pd, let pp = try? JSONSerialization.jsonObject(with: raw) as? [String: Any] { parentSchema = pp["schema"] as? [String: Any] }
        } else { parentSchema = nil }
        error = nil; loaded = true; fetched = true
    }

    /// Titel + Inhalt speichern - mit Revision (Konflikterkennung wie im Web-Editor)
    func save(_ store: Store) async {
        guard fetched, dirty else { return }                    // nur echte, geaenderte Seiten
        doc["blocks"] = root; doc["time"] = Int(Date().timeIntervalSince1970 * 1000)
        guard let data = try? JSONSerialization.data(withJSONObject: doc), let body = String(data: data, encoding: .utf8) else { return }
        var b: [String: Any] = ["title": titleRaw, "rev": rev]
        if !isDatabase { b["body"] = body; b["format"] = "blocks" }
        do {
            let r = try await store.api("api/notes/\(id)", method: "PATCH", body: b)
            if let j = try? JSONSerialization.jsonObject(with: r) as? [String: Any], let nr = j["rev"] as? Int { rev = nr }
            error = nil; dirty = false; store.updateTitle(id, titleRaw)
            if parentSchema != nil { store.dataChanged() }          // Eintrag einer Datenbank: Titel in den Ansichten aktualisieren
        } catch APIError.http(409, _) {
            error = String(localized: "Die Seite wurde inzwischen woanders geändert – neu geladen."); await load(store)
        } catch { self.error = String(localized: "Nicht gespeichert: \(error.localizedDescription)") }
    }
}

// MARK: - Seite

struct NativePageScreen: View {
    let id: Int
    var inPeek = false
    @EnvironmentObject var store: Store
    @StateObject private var doc: PageDoc
    @StateObject private var ed: PageEditor
    @EnvironmentObject var nav: NavModel
    @Environment(\.dismiss) private var dismiss
    @State private var moving = false
    @State private var scrolled = false                  // grosser Titel weggescrollt -> kleiner Titel + Leiste
    @State private var topInset: CGFloat = 0
    init(id: Int, inPeek: Bool = false) {
        self.id = id; self.inPeek = inPeek
        let d = PageDoc(id: id)
        _doc = StateObject(wrappedValue: d); _ed = StateObject(wrappedValue: PageEditor(doc: d))
    }

    private var favButton: some View {
        let fav = doc.page["pinned"] as? Bool ?? false
        return Button { Task { try? await store.patch(id, ["pinned": !fav]); doc.page["pinned"] = !fav } } label: {
            Image(systemName: fav ? "star.fill" : "star").foregroundStyle(fav ? .yellow : .primary)
        }.help(fav ? "Aus Favoriten entfernen" : "Zu Favoriten")
    }
    @ViewBuilder private var pageMenuItems: some View {
                    Text(editedText(doc.page["updated"] as? Double))
                    #if os(iOS)
                    if !store.path(id).isEmpty {
                        Menu { ForEach(store.path(id)) { p in Button { nav.push(PageRoute(id: p.id)) } label: { Label(p.label, systemImage: "arrow.up.forward.square") } } }
                            label: { Label("Übergeordnete Seiten", systemImage: "arrow.up.forward") }
                    }
                    #endif
                    Toggle(isOn: Binding(get: { doc.page["wide"] as? Bool ?? false },
                                         set: { v in Task { try? await store.patch(id, ["wide": v]); doc.page["wide"] = v } })) { Label("Volle Breite", systemImage: "arrow.left.and.right") }
                    Button { store.setHome(store.homePage == id ? nil : id) } label: {
                        Label(store.homePage == id ? "Nicht mehr Startseite" : "Als Startseite festlegen", systemImage: store.homePage == id ? "house.slash" : "house")
                    }
                    Divider()
                    Button { moving = true } label: { Label("Verschieben nach …", systemImage: "arrow.turn.up.right") }
                    Button { Task { await ed.flush(); showVersions = true } } label: { Label("Versionsverlauf …", systemImage: "clock.arrow.circlepath") }
                    #if os(macOS)
                    Menu {
                        Button("Als Markdown (.md)") { Task { await ed.flush(); if let e = await exportPage(id, title: doc.title, withSub: false, store: store) { doc.error = e } } }
                        if !store.children(of: id).isEmpty {
                            Button("Mit Unterseiten (.zip)") { Task { await ed.flush(); if let e = await exportPage(id, title: doc.title, withSub: true, store: store) { doc.error = e } } }
                        }
                    } label: { Label("Exportieren", systemImage: "square.and.arrow.down") }
                    #endif
                    Toggle(isOn: Binding(get: { doc.page["template"] as? Bool ?? false },
                                         set: { v in Task { try? await store.patch(id, ["template": v]); doc.page["template"] = v; await store.refresh() } })) { Label("Als Vorlage", systemImage: "doc.on.doc") }
                    Button { Task { if let n = await store.duplicate(id) { nav.push(PageRoute(id: n)) } } } label: { Label("Duplizieren", systemImage: "plus.square.on.square") }
                    if let u = URL(string: AppConfig.server.absoluteString + "/notes#\(id)") {
                        ShareLink(item: u) { Label("Link teilen", systemImage: "square.and.arrow.up") }
                    }
                    Button { nav.peek = nil; nav.push(PageRoute(id: id, web: true)) } label: { Label("Im Web-Editor öffnen", systemImage: "globe") }
                    Button { Task { await ed.flush(); await doc.load(store) } } label: { Label("Neu laden", systemImage: "arrow.clockwise") }
                    Divider()
                    Button { Task { await setLocked(!ed.locked) } } label: {
                        Label(ed.locked ? "Seite entsperren" : "Seite sperren", systemImage: ed.locked ? "lock.open" : "lock")
                    }
                    let isArch = (doc.page["archived"] as? Bool) == true
                    Button { Task { await ed.flush(); await store.setArchived(id, !isArch); doc.page["archived"] = !isArch } } label: {
                        Label(isArch ? "Aus dem Archiv holen" : "Archivieren", systemImage: isArch ? "tray.and.arrow.up" : "archivebox")
                    }
                    Button(role: .destructive) { Task { await ed.flush(); if await store.trash(id) { if inPeek { nav.peek = nil } else { dismiss() } } } } label: { Label("In den Papierkorb", systemImage: "trash") }   // im Peek: nur den Peek schliessen
    }
    /// Seite sperren/entsperren (wie im Web): erst speichern, dann sperren
    private func setLocked(_ on: Bool) async {
        if on { await ed.flush(); ed.focus = nil }
        try? await store.patch(id, ["locked": on]); doc.page["locked"] = on; ed.locked = on
    }
    /// Peek: Kopfzeile IM Inhalt (✕, ganze Seite, ★, ⋯) - keine eigene Fensterleiste
    private var peekHeader: some View {
        HStack(spacing: 14) {
            Button { nav.peek = nil } label: { Image(systemName: "xmark") }.help("Schließen (Esc)").keyboardShortcut(.cancelAction)
            Button { nav.peek = nil; nav.push(PageRoute(id: id)) } label: { Image(systemName: "arrow.up.left.and.arrow.down.right") }.help("Als ganze Seite öffnen")
            if nav.peekList.count > 1 {                                  // wie Notion: im Peek durch die Ansicht blaettern
                let i = nav.peekList.firstIndex(of: id)
                Button { Task { await ed.flush(); nav.peekStep(-1) } } label: { Image(systemName: "chevron.up") }
                    .help("Vorheriger Eintrag (⌃⇧K)").keyboardShortcut("k", modifiers: [.control, .shift]).disabled(i == nil || i == 0)
                Button { Task { await ed.flush(); nav.peekStep(1) } } label: { Image(systemName: "chevron.down") }
                    .help("Nächster Eintrag (⌃⇧J)").keyboardShortcut("j", modifiers: [.control, .shift]).disabled(i == nil || i == nav.peekList.count - 1)
            }
            Text(doc.title).font(.headline).lineLimit(1).foregroundStyle(.secondary)
            Spacer()
            favButton
            Menu { pageMenuItems } label: { Image(systemName: "ellipsis.circle") }.menuIndicator(.hidden).fixedSize()
        }
        .buttonStyle(.plain).font(.title3)
        .padding(.horizontal, 16).padding(.vertical, 10)
        .background(.bar)
    }

    var body: some View {
        VStack(spacing: 0) {
            if inPeek { peekHeader; Divider() }
            pageBody
        }
    }

    @State private var pageW: CGFloat = 0
    @State private var showVersions = false
    private var pageBody: some View {
        ScrollViewReader { proxy in
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                #if os(iOS)
                CoverView(doc: doc, extendTop: topInset)          // Titelbild bis ganz nach oben (wie Notion)
                #else
                CoverView(doc: doc)
                #endif
                VStack(alignment: .leading, spacing: 10) {
                    IconRow(doc: doc)
                    TextField("Unbenannt", text: Binding(get: { doc.titleRaw }, set: { v in if v != doc.titleRaw { doc.page["title"] = v; ed.textChanged() } }), axis: .vertical)   // nur echte Aenderung
                        .font(.largeTitle.bold()).textFieldStyle(.plain).disabled(ed.locked || !doc.fetched)
                    if ed.locked {
                        Button { Task { await setLocked(false) } } label: { Label("Gesperrt – tippen zum Entsperren", systemImage: "lock.fill").font(.caption) }
                            .buttonStyle(.bordered).controlSize(.small).tint(.secondary)
                    }
                    if doc.fetched && !doc.isDatabase { TagsRow(doc: doc) }
                    if let schema = doc.parentSchema { EditableProps(doc: doc, schema: schema).padding(.bottom, 8) }
                    if doc.isDatabase {
                        DatabaseView(dbID: id, viewID: nil, localViews: nil)
                    } else if doc.fetched {
                        BlocksView(arr: doc.root)
                        // wie Notion: Klick in den freien Bereich unter dem letzten Block -> weiterschreiben
                        Color.clear.frame(height: 160).contentShape(Rectangle()).onTapGesture { if !ed.locked { ed.continueWriting() } }
                        BacklinksView(id: id)
                    }
                    if let e = doc.error { Text(e).font(.footnote).foregroundStyle(.orange) }
                    if doc.loaded && !doc.fetched {                              // Laden fehlgeschlagen: nichts Bearbeitbares zeigen
                        Button { Task { doc.loaded = false; await doc.load(store) } } label: { Label("Erneut versuchen", systemImage: "arrow.clockwise") }
                            .buttonStyle(.borderedProminent).padding(.top, 4)
                    }
                }
                .padding(.horizontal, pageGutter).padding(.vertical, 12)
                #if os(macOS)
                .overlayPreferenceValue(BlockFramesKey.self) { frames in if !ed.locked { BlockHandles(frames: frames, hover: ed.hover) } }   // Griffe auf Seitenebene
                #endif
                .frame(maxWidth: (doc.page["wide"] as? Bool ?? false) ? .infinity : 760 + 2 * pageGutter)      // lesbare Spalte (ausser "Volle Breite")
                .frame(maxWidth: .infinity)
            }
        }
        #if os(macOS)
        .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { pageW = $0 }
        .overlay(alignment: .topTrailing) {                   // Inhaltsverzeichnis rechts, wenn Platz ist (wie im Web)
            let toc = doc.loaded && !inPeek && pageW > 1180 ? tocItems(doc.root) : []
            if toc.count >= 3 { PageTOC(items: toc) { bid in withAnimation { proxy.scrollTo(bid, anchor: .top) } }.padding(.top, 70).padding(.trailing, 16) }
        }
        #endif
        }
        .scrollDismissesKeyboard(.interactively)
        #if os(iOS)
        .scrollEdgeEffectHidden(!scrolled && doc.cover != nil, for: .top)    // iOS 26: Titelbild unter der Leiste nicht abdunkeln
        .background {                                                          // Hoehe von Statusleiste + Navigation am aeusseren Rahmen messen
            GeometryReader { g in Color.clear.onAppear { topInset = g.safeAreaInsets.top }.onChange(of: g.safeAreaInsets.top) { _, v in topInset = v } }
        }
        .onScrollGeometryChange(for: Bool.self) { g in g.contentOffset.y + g.contentInsets.top > (doc.cover != nil ? 250 : 70) } action: { _, v in
            withAnimation(.easeInOut(duration: 0.2)) { scrolled = v }
        }
        .toolbarBackground(scrolled ? .visible : .hidden, for: .navigationBar)
        #endif
        .photosPicker(isPresented: $ed.pickImage, selection: $ed.picked, maxSelectionCount: 5, matching: .images)
        .sheet(isPresented: $ed.pickImmich) { ImmichPicker { url in ed.insertImageURL(url) }.environmentObject(store) }
        .sheet(item: Binding(get: { ed.linkDBFor.map(MoveTarget.init(idString:)) }, set: { if $0 == nil { ed.linkDBFor = nil } })) { _ in
            NavigationStack {
                List(store.pages.filter { $0.isDatabase }) { p in
                    Button { if let b = ed.linkDBFor { ed.linkDatabase(p.id, into: b) } } label: {
                        HStack { PageIcon(icon: p.icon, store: store, fallback: "🗃️"); VStack(alignment: .leading) { Text(p.label); Text(store.path(p.id).map(\.label).joined(separator: " / ")).font(.caption).foregroundStyle(.secondary) } }
                    }.buttonStyle(.plain)
                }
                .navigationTitle("Datenbank verknüpfen")
                .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Abbrechen") { ed.linkDBFor = nil } } }
            }.frame(minWidth: 360, minHeight: 420)
        }
        .onChange(of: ed.openPage) { _, v in if let v { ed.openPage = nil; nav.push(PageRoute(id: v)) } }
        .modifier(PageEditorSheets(ed: ed, doc: doc))
        .sheet(isPresented: $showVersions) { VersionsSheet(id: id) { Task { await doc.load(store) } }.environmentObject(store) }
        .onChange(of: ed.picked) { _, items in
            Task { for it in items { if let d = try? await it.loadTransferable(type: Data.self) { await ed.insertImage(d) } }; ed.picked = [] }
        }
        #if os(macOS)
        .onPasteCommand(of: [.image]) { providers in ed.insertImages(from: providers) }     // Mac: Cmd+V mit Bild
        #endif
        .environmentObject(ed)
        .overlay { if !doc.loaded { ProgressView() } }
        #if os(iOS)
        .navigationTitle(scrolled ? doc.title : "")
        .navigationBarTitleDisplayMode(.inline)
        #else
        .modifier(MacTitle(on: !inPeek, title: doc.title, subtitle: store.path(id).map(\.label).joined(separator: " / ")))
        #endif
        .sheet(isPresented: $moving) { MoveSheet(id: id) }
        .toolbar {
            if !inPeek {                                     // im Peek: eigene Kopfzeile statt Fensterleiste (sonst Layout-Kreislauf am Mac)
                ToolbarItem(placement: .primaryAction) { favButton }
                ToolbarItem(placement: .primaryAction) { Menu { pageMenuItems } label: { Image(systemName: "ellipsis.circle") } }
            }
            #if os(iOS)
            ToolbarItemGroup(placement: .keyboard) {
                BlockTypeMenu(title: "Neu", icon: "plus") { t, l in ed.insert(PageEditor.make(t, level: l == 0 ? 2 : l), after: ed.focusedBlock) }
                BlockTypeMenu(title: "Typ", icon: "textformat") { t, l in if let b = ed.focusedBlock { ed.convert(b, to: t, level: l == 0 ? 2 : l) } }
                ImageInsertButtons()
                Button { ed.format("bold") } label: { Image(systemName: "bold") }
                Button { ed.format("italic") } label: { Image(systemName: "italic") }
                Button { ed.format("underline") } label: { Image(systemName: "underline") }
                Button { ed.format("strike") } label: { Image(systemName: "strikethrough") }
                Button { ed.format("mark") } label: { Image(systemName: "highlighter") }
                Button { if let b = ed.focusedBlock { ed.move(b, by: -1) } } label: { Image(systemName: "arrow.up") }
                Button { if let b = ed.focusedBlock { ed.move(b, by: 1) } } label: { Image(systemName: "arrow.down") }
                Button { if let b = ed.focusedBlock { ed.delete(b) } } label: { Image(systemName: "trash") }
                Spacer()
                Button("Fertig") { ed.focus = nil }.bold()
            }
            #endif
        }
        .task {
            ed.store = store; await doc.load(store); store.visited(id)
            if let ty = UserDefaults.standard.string(forKey: "hkHoverType") {              // nur fuer Tests: Griffe erzwingen
                func find(_ a: NSMutableArray, inColumn: Bool) -> String? {
                    for case let b as NSMutableDictionary in a {
                        let d = b["data"] as? NSDictionary
                        for case let c as NSDictionary in (d?["cols"] as? NSArray ?? []) { if let inner = c["blocks"] as? NSMutableArray, let r = find(inner, inColumn: true) { return r } }
                        if inColumn, b["type"] as? String == ty { return b["id"] as? String }
                    }
                    return nil
                }
                if let hid = find(doc.root, inColumn: false) { try? await Task.sleep(for: .seconds(1)); ed.hover.hovered = hid; ed.hover.overHandle = true }
            }
            if UserDefaults.standard.bool(forKey: "hkFocusFirst"),                       // nur fuer Tests (Startparameter -hkFocusFirst YES)
               let b = doc.root.compactMap({ $0 as? NSMutableDictionary }).first(where: { ["paragraph", "header"].contains($0["type"] as? String ?? "") }) {
                ed.focus = b.blockID
            }
        }
        .refreshable { await ed.flush(); await doc.load(store); store.refreshEvents() }
        .onChange(of: (doc.page["locked"] as? Bool) ?? false, initial: true) { _, v in ed.locked = v; if v { ed.focus = nil } }
        .onAppear { if doc.loaded && ed.focus == nil { Task { await ed.flush(); await doc.load(store) } } }   // zurueck aus dem Web-Editor
        .onDisappear { Task { await ed.flush() } }
        .onChange(of: store.needsLogin) { _, v in if !v && !doc.fetched { Task { await doc.load(store) } } }   // wieder angemeldet -> neu laden
    }
}

// MARK: - Bloecke (bearbeitbar)

struct BlocksView: View {
    let arr: NSMutableArray
    @EnvironmentObject var ed: PageEditor
    @Environment(\.blockDepth) private var depth
    var body: some View {
        let blocks = arr.compactMap { $0 as? NSMutableDictionary }
        VStack(alignment: .leading, spacing: 10) {
            ForEach(blocks, id: \.blockID) { b in BlockRow(block: b) }
        }
        .environment(\.blockDepth, depth + 1)
    }
}

extension NSMutableDictionary { var blockID: String { self["id"] as? String ?? "\(ObjectIdentifier(self).hashValue)" } }

struct BlockView: View {
    let block: NSMutableDictionary
    @EnvironmentObject var store: Store
    @EnvironmentObject var ed: PageEditor
    @Environment(\.horizontalSizeClass) private var hsc
    @State private var colHover: Int?
    private var type: String { block["type"] as? String ?? "" }
    private var id: String { block.blockID }
    private var data: NSMutableDictionary {
        if let d = block["data"] as? NSMutableDictionary { return d }
        let d = NSMutableDictionary(); block["data"] = d; return d
    }
    private func s(_ k: String) -> String { data[k] as? String ?? "" }
    private func set(_ k: String) -> (String) -> Void { { v in self.data[k] = v; self.ed.textChanged() } }
    /// Return in einem Textblock: neuer Absatz darunter mit dem Rest
    private func split(_ k: String) -> (String, String) -> Void {
        { before, after in self.data[k] = before; self.ed.insert(PageEditor.make("paragraph", text: after), after: self.id) }
    }

    var body: some View {
        switch type {
        case "paragraph":
            EditText(key: id, html: s("text"), placeholder: ed.focus == id ? String(localized: "Tippe etwas oder „/“ für Blöcke …") : "", slash: true, setHTML: set("text"), onReturn: split("text")).id(id)
        case "header":
            let lvl = data["level"] as? Int ?? 2
            EditText(key: id, html: s("text"), font: lvl <= 1 ? .title.bold() : lvl == 2 ? .title2.bold() : lvl == 3 ? .title3.bold() : .headline,
                     placeholder: String(localized: "Überschrift"), boldBase: true, setHTML: set("text"), onReturn: split("text")).id(id).padding(.top, 6)
        case "list":
            ListBlock(blockID: id, items: data["items"] as? NSMutableArray ?? NSMutableArray(), style: s("style"), path: [])
        case "quote":
            HStack(alignment: .top, spacing: 10) {
                Rectangle().fill(.secondary).frame(width: 3)
                EditText(key: id, html: s("text"), placeholder: String(localized: "Zitat"), setHTML: set("text"), onReturn: split("text")).id(id).italic()
            }
        case "code":
            EditText(key: id, html: s("code"), font: .system(.footnote, design: .monospaced), placeholder: String(localized: "Code"), splitOnReturn: false, raw: true, setHTML: set("code")).id(id)
                .padding(10).padding(.top, s("language").isEmpty ? 0 : 8).background(.secondary.opacity(0.12), in: RoundedRectangle(cornerRadius: 8))
                .overlay(alignment: .topTrailing) {                      // Sprache aus dem Web (Bash, YAML ...) - bleibt beim Bearbeiten erhalten
                    if !s("language").isEmpty { Text(s("language")).font(.caption2.monospaced()).foregroundStyle(.secondary).padding(.horizontal, 8).padding(.top, 4) }
                }
        case "delimiter":
            Divider().padding(.vertical, 6)
        case "table":
            EditableTable(data: data).id(id)
        case "image":
            if let url = (data["file"] as? NSDictionary)?["url"] as? String {
                VStack(alignment: .leading, spacing: 4) {
                    RemoteImage(path: url, w: 2000).frame(maxWidth: .infinity, maxHeight: 900).clipShape(RoundedRectangle(cornerRadius: 8))   // ganzes Bild wie im Web
                    if !s("caption").isEmpty { Text(plain(s("caption"))).font(.caption).foregroundStyle(.secondary) }
                }
            }
        case "attaches":
            let f = data["file"] as? NSDictionary
            let fu = (f?["url"] as? String).flatMap { URL(string: AppConfig.server.absoluteString.trimmingCharacters(in: CharacterSet(charactersIn: "/")) + $0) }
            HStack {
                Label((f?["name"] as? String) ?? s("title"), systemImage: "paperclip")
                Spacer()
                if let sz = f?["size"] as? Int { Text(ByteCountFormatter.string(fromByteCount: Int64(sz), countStyle: .file)).font(.caption).foregroundStyle(.secondary) }
                if let fu { Link(destination: fu) { Image(systemName: "arrow.down.circle") }.help("Öffnen / herunterladen") }
            }
            .padding(10).frame(maxWidth: .infinity, alignment: .leading).background(.secondary.opacity(0.1), in: RoundedRectangle(cornerRadius: 8))
        case "callout":
            HStack(alignment: .top, spacing: 10) {
                Text(s("emoji").isEmpty ? "💡" : s("emoji"))
                EditText(key: id, html: s("text"), placeholder: String(localized: "Hinweis"), setHTML: set("text"), onReturn: split("text")).id(id)
            }
            .padding(12).background(.secondary.opacity(0.12), in: RoundedRectangle(cornerRadius: 10))
        case "toggle":
            ToggleBlock(id: id, data: data)
        case "columns":
            let cols = (data["cols"] as? NSArray)?.compactMap { ($0 as? NSDictionary)?["blocks"] as? NSMutableArray } ?? []
            if hsc == .compact {
                VStack(alignment: .leading, spacing: 14) {   // iPhone: Spalten untereinander
                    ForEach(Array(cols.enumerated()), id: \.offset) { _, c in
                        VStack(alignment: .leading, spacing: 4) { BlocksView(arr: c); ColumnAddButton(visible: true) { ed.continueInColumn(c) } }
                    }
                }
            } else {
                HStack(alignment: .top, spacing: columnGap) {
                    ForEach(Array(cols.enumerated()), id: \.offset) { i, c in
                        VStack(alignment: .leading, spacing: 4) {
                            BlocksView(arr: c)
                            ColumnAddButton(visible: colHover == i) { ed.continueInColumn(c) }   // in DIESER Spalte weiterschreiben
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .contentShape(Rectangle())
                        .onHover { colHover = $0 ? i : (colHover == i ? nil : colHover) }
                    }
                }
            }
        case "database":
            if let db = data["db"] as? Int {
                DatabaseView(dbID: db, viewID: data["view"] as? String, localViews: data["views"] as? [[String: Any]], embedded: true) { vs in
                    data["views"] = vs; ed.changed()                // eigene Ansichten des Blocks in der Seite speichern
                }
            }
        case "tasks":
            TaskListView(compact: true).padding(12).background(.secondary.opacity(0.08), in: RoundedRectangle(cornerRadius: 10))
        case "calendar":
            EventList(days: 14, compact: true).padding(12).background(.secondary.opacity(0.08), in: RoundedRectangle(cornerRadius: 10))
        case "buttons":
            HStack(spacing: 6) {
                ButtonsBlock(buttons: data["buttons"] as? [[String: Any]] ?? [])
                if !ed.locked { Button { ed.configButtons = id } label: { Image(systemName: "gearshape").foregroundStyle(.tertiary) }.buttonStyle(.plain).help("Schaltflächen einrichten") }
            }
        case "embed":
            EmbedCard(url: decodeEntities(s("url")), title: decodeEntities(s("title")))
        default:
            EmptyView()
        }
    }
}

struct ListBlock: View {
    let blockID: String
    let items: NSMutableArray
    let style: String
    let path: [Int]
    @EnvironmentObject var ed: PageEditor
    private func key(_ i: Int) -> String { ([blockID] + (path + [i]).map(String.init)).joined(separator: "/") }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            ForEach(Array(items.enumerated()), id: \.offset) { i, raw in
                if let it = raw as? NSMutableDictionary {
                    let meta = it["meta"] as? NSMutableDictionary
                    let checked = meta?["checked"] as? Bool ?? false
                    HStack(alignment: .firstTextBaseline, spacing: 8) {
                        if style == "checklist" {
                            Button {
                                let m = meta ?? NSMutableDictionary(); m["checked"] = !checked; it["meta"] = m; ed.changed()
                            } label: { Image(systemName: checked ? "checkmark.square.fill" : "square").foregroundStyle(checked ? Color.accentColor : .secondary) }
                            .buttonStyle(.plain)
                        } else {
                            Text(style == "ordered" ? "\(i + 1)." : "•").foregroundStyle(.secondary).frame(minWidth: 16, alignment: .trailing)
                        }
                        VStack(alignment: .leading, spacing: 6) {
                            EditText(key: key(i), html: it["content"] as? String ?? "", placeholder: String(localized: "Listenpunkt"), muted: checked,
                                     setHTML: { v in it["content"] = v; ed.textChanged() },
                                     onReturn: { before, after in returnIn(i, before: before, after: after) }).id(key(i))
                            if let sub = it["items"] as? NSMutableArray, sub.count > 0 { ListBlock(blockID: blockID, items: sub, style: style, path: path + [i]) }
                        }
                    }
                }
            }
        }
        .padding(.leading, path.isEmpty ? 0 : 6)
    }

    /// Return: neuer Punkt darunter; leerer Punkt + Return beendet die Liste (neuer Absatz nach der Liste)
    private func returnIn(_ i: Int, before: String, after: String) {
        if before.isEmpty && after.isEmpty {
            items.removeObject(at: i)
            if path.isEmpty && items.count == 0 { ed.convert(blockID, to: "paragraph") }
            else { ed.insert(PageEditor.make("paragraph"), after: blockID) }
            return
        }
        let ni = NSMutableDictionary(dictionary: ["content": after, "meta": NSMutableDictionary(dictionary: style == "checklist" ? ["checked": false] : [:]), "items": NSMutableArray()])
        items.insert(ni, at: i + 1)
        ed.focus = key(i + 1); ed.changed()
    }
}

struct ToggleBlock: View {
    let id: String
    let data: NSMutableDictionary
    @EnvironmentObject var ed: PageEditor
    @State private var open: Bool
    init(id: String, data: NSMutableDictionary) { self.id = id; self.data = data; _open = State(initialValue: data["open"] as? Bool ?? false) }
    private var inner: NSMutableArray {
        if let a = data["blocks"] as? NSMutableArray { return a }
        let a = NSMutableArray(); data["blocks"] = a; return a
    }
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .firstTextBaseline, spacing: 6) {
                Button { withAnimation(.snappy) { open.toggle() } } label: {
                    Image(systemName: "chevron.right").font(.caption.bold()).rotationEffect(.degrees(open ? 90 : 0)).foregroundStyle(.secondary).frame(width: 18, height: 22)
                }.buttonStyle(.plain)
                EditText(key: id, html: data["title"] as? String ?? "", font: .body.bold(), placeholder: String(localized: "Toggle"), boldBase: true,
                         setHTML: { v in data["title"] = v; ed.textChanged() },
                         onReturn: { before, after in
                             data["title"] = before; open = true
                             if inner.count == 0 { inner.add(PageEditor.make("paragraph", text: after)) }
                             let first = (inner[0] as? NSMutableDictionary)?.blockID
                             if let first { ed.focus = first }; ed.changed()
                         }).id(id)
            }
            if open {
                if inner.count == 0 {
                    Button("Leerer Toggle – tippen zum Schreiben") { ed.insertInto(inner) }.font(.subheadline).foregroundStyle(.secondary).padding(.leading, 24)
                } else {
                    BlocksView(arr: inner).padding(.leading, 24)
                }
            }
        }
    }
}

extension PageEditor {
    func insertInto(_ arr: NSMutableArray) { let b = Self.make("paragraph"); arr.add(b); focus = b.blockID; changed() }
}

struct TableBlock: View {
    let rows: [[String]]
    let headings: Bool
    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            Grid(alignment: .leading, horizontalSpacing: 0, verticalSpacing: 0) {
                ForEach(Array(rows.enumerated()), id: \.offset) { r, row in
                    GridRow {
                        ForEach(Array(row.enumerated()), id: \.offset) { _, cell in
                            Text(inlineText(cell)).font(r == 0 && headings ? .subheadline.bold() : .subheadline)
                                .padding(8).frame(minWidth: 90, alignment: .leading).border(.secondary.opacity(0.25), width: 0.5)
                        }
                    }
                }
            }
        }
    }
}

struct ButtonsBlock: View {
    let buttons: [[String: Any]]
    @EnvironmentObject var store: Store
    @EnvironmentObject var nav: NavModel
    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(Array(buttons.enumerated()), id: \.offset) { _, b in
                    Button { Task { await run(b) } } label: {
                        HStack(spacing: 6) {
                            if let ic = b["icon"] as? String, !ic.isEmpty { Text(ic) } else { Image(systemName: "cursorarrow.click").foregroundStyle(.secondary) }
                            Text(b["label"] as? String ?? "").foregroundStyle(.primary)
                        }
                        .font(.subheadline.weight(.medium)).padding(.horizontal, 10).padding(.vertical, 6)
                        .background(.secondary.opacity(0.12), in: RoundedRectangle(cornerRadius: 7))
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }
    private func run(_ b: [String: Any]) async {
        let target = b["target"] as? Int
        switch b["action"] as? String {
        case "page": if let t = target { nav.open(t, mode: b["open"] as? String ?? "") }
        case "db":
            guard let t = target else { return }
            let title = (b["title"] as? String ?? "").replacingOccurrences(of: "{datum}", with: Date.now.formatted(date: .numeric, time: .omitted))
            struct R: Decodable { let id: Int }
            let tpl = b["tpl"] as? Int
            var body: [String: Any] = ["parent_id": t]; if !title.isEmpty || tpl == nil { body["title"] = title }
            if let d = try? await store.api(tpl.map { "api/notes/\($0)/duplicate" } ?? "api/notes", method: "POST", body: body),   // Vorlage: alles uebernehmen
               let r = try? JSONDecoder().decode(R.self, from: d) { await store.refresh(); store.dataChanged(); nav.open(r.id, mode: b["open"] as? String ?? "") }
        case "task":
            _ = try? await store.api("api/tasks", method: "POST", body: ["text": (b["title"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? String(localized: "Neue Aufgabe")])
            await store.loadToday()
        default: break
        }
    }
}

struct Chip: View {
    let text: String
    let color: String?
    var body: some View {
        Text(text).font(.caption.weight(.medium)).padding(.horizontal, 7).padding(.vertical, 2)
            .background(optColor(color).opacity(0.28), in: RoundedRectangle(cornerRadius: 5))
    }
}

// optColor: Shared/ColorHex.swift (gemeinsam mit dem Widget)


/// Ein Block mit Griffen wie Notion: Mac -> beim Ueberfahren "+" und "⋮⋮" links; iPhone/iPad -> langes Druecken
struct BlockRow: View {
    let block: NSMutableDictionary
    @EnvironmentObject var ed: PageEditor
    @Environment(\.blockDepth) private var depth
    private var id: String { block.blockID }
    var body: some View {
        #if os(macOS)
        // Griffe zeichnet die SEITE (BlockHandles) - hier nur melden: Maus drueber + Position
        BlockView(block: block)
            .contentShape(Rectangle())
            .onHover { inside in ed.hover.hover(id, depth: depth, inside: inside) }
            // transform: eigene Position ERGAENZEN (anchorPreference wuerde die der Bloecke darin ueberschreiben,
            // dann kennt die Seite Bloecke in Spalten nicht und zeichnet dort keine Griffe)
            .transformAnchorPreference(key: BlockFramesKey.self, value: .bounds) { frames, a in frames[id] = a }
            .id(id)                                                    // Ziel fuer das Inhaltsverzeichnis
        #else
        BlockView(block: block)
            .contentShape(Rectangle())
            .contextMenu { BlockMenuItems(id: id) }
            .id(id)
        #endif
    }
}

/// Position aller Bloecke (fuer die Griffe auf Seitenebene)
struct BlockFramesKey: PreferenceKey {
    static let defaultValue: [String: Anchor<CGRect>] = [:]
    static func reduce(value: inout [String: Anchor<CGRect>], nextValue: () -> [String: Anchor<CGRect>]) { value.merge(nextValue()) { $1 } }
}
private struct BlockDepthKey: EnvironmentKey { static let defaultValue = 0 }
extension EnvironmentValues { var blockDepth: Int { get { self[BlockDepthKey.self] } set { self[BlockDepthKey.self] = newValue } } }

/// Mac: EINE Griffleiste (+ ≡) fuer die ganze Seite, links neben dem Block unter der Maus.
/// Liegt auf Seitenebene -> auch im Rand neben Spalten erreichbar (vorher: ausserhalb der Spalte nicht).
struct BlockHandles: View {
    let frames: [String: Anchor<CGRect>]
    @ObservedObject var hover: HoverModel
    @EnvironmentObject var ed: PageEditor
    var body: some View {
        GeometryReader { g in
            if let id = hover.hovered, let a = frames[id] {
                let r = g[a]
                HStack(spacing: 0) {
                    Button { ed.insert(PageEditor.make("paragraph"), after: id) } label: { Image(systemName: "plus").frame(width: 22, height: 22).contentShape(Rectangle()) }
                        .buttonStyle(.plain).help("Klicken: Block darunter einfügen")
                    BlockMenu(id: id) { Image(systemName: "line.3.horizontal").frame(width: 20, height: 22).contentShape(Rectangle()) }.help("Block-Menü")
                }
                .foregroundStyle(.secondary)
                .padding(.trailing, 8).padding(.vertical, 4)                  // grosszuegige Trefferflaeche bis zum Text
                .contentShape(Rectangle())
                .onHover { hover.overHandle = $0; if !$0 { hover.scheduleClear() } }
                .position(x: r.minX - 26, y: r.minY + 13)
            }
        }
    }
}

struct BlockMenuItems: View {
    let id: String
    @EnvironmentObject var ed: PageEditor
    var body: some View {
        Menu {
            ForEach(slashItems.filter { !["image", "immich_image", "page", "db_new", "db_link", "tasks", "calendar", "table", "columns", "buttons", "embed", "file"].contains($0.1) }, id: \.0) { it in Button { ed.convert(id, to: it.1, level: it.2 == 0 ? 2 : it.2) } label: { Label(it.0, systemImage: it.3) } }
        } label: { Label("Umwandeln in", systemImage: "arrow.triangle.2.circlepath") }
        Button { copyText(AppConfig.server.absoluteString.trimmingCharacters(in: CharacterSet(charactersIn: "/")) + "/notes#\(ed.doc.id):\(id)") } label: { Label("Link zum Block kopieren", systemImage: "link") }
        // Spalten wie Notion: hinein, heraus, zusammenfuehren
        if let c = ed.columnOf(id) {
            Menu {
                ForEach(0..<ed.colCount(c.cols), id: \.self) { ci in
                    if ci != c.col { Button("Spalte \(ci + 1)") { ed.moveIntoColumn(id, cols: c.cols, col: ci) } }
                }
            } label: { Label("In andere Spalte", systemImage: "rectangle.split.3x1") }
            Button { ed.moveOutOfColumns(id) } label: { Label("Aus den Spalten herausnehmen", systemImage: "rectangle.portrait.and.arrow.right") }
        } else if let above = ed.columnsAbove(id) {
            if ed.isColumns(id) {                       // Spalten-Block unter Spalten-Block: zusammenfuehren, NIE hineinschieben
                Button { ed.mergeColumnsInto(above: above, id) } label: { Label("Mit Spalten darüber zusammenführen", systemImage: "rectangle.compress.vertical") }
            } else {
                Menu {
                    ForEach(0..<ed.colCount(above), id: \.self) { ci in Button("Spalte \(ci + 1)") { ed.moveIntoColumn(id, cols: above, col: ci) } }
                } label: { Label("In Spalte darüber verschieben", systemImage: "arrow.up.square") }
            }
        }
        if ed.columnsBelow(id) != nil, (ed.locate(id).map { ($0.0[$0.1] as? NSDictionary)?["type"] as? String } ?? nil) == "columns" {
            Button { ed.mergeColumns(id) } label: { Label("Mit Spalten darunter zusammenführen", systemImage: "rectangle.compress.vertical") }
        }
        Button { ed.duplicate(id) } label: { Label("Duplizieren", systemImage: "plus.square.on.square") }
        Button { ed.move(id, by: -1) } label: { Label("Nach oben", systemImage: "arrow.up") }
        Button { ed.move(id, by: 1) } label: { Label("Nach unten", systemImage: "arrow.down") }
        Divider()
        Button(role: .destructive) { ed.delete(id) } label: { Label("Löschen", systemImage: "trash") }
    }
}

struct BlockMenu<L: View>: View {
    let id: String
    @ViewBuilder var label: () -> L
    var body: some View {
        Menu { BlockMenuItems(id: id) } label: { label() }
            .menuStyle(.button).buttonStyle(.plain).menuIndicator(.hidden).fixedSize()
    }
}


/// "+ Block" unter einer Spalte: neuer Absatz am Ende GENAU dieser Spalte (Mac beim Ueberfahren der Spalte)
struct ColumnAddButton: View {
    let visible: Bool
    let action: () -> Void
    var body: some View {
        Button(action: action) { Label("Block", systemImage: "plus").font(.subheadline).padding(.horizontal, 6).padding(.vertical, 3) }
            .buttonStyle(.plain).foregroundStyle(.secondary)
            .opacity(visible ? 1 : 0)
            .help("Neuer Block am Ende dieser Spalte")
    }
}


/// Fenstertitel nur fuer die Hauptseite (der Peek setzt keinen - zwei Titel/Leisten schaukeln sich am Mac auf)
struct MacTitle: ViewModifier {
    let on: Bool
    let title: String
    let subtitle: String
    func body(content: Content) -> some View {
        #if os(macOS)
        if on { content.navigationTitle(title).navigationSubtitle(subtitle) } else { content }
        #else
        content
        #endif
    }
}

/// Einbetten-Block aus dem Web: YouTube oder Link als Karte - Tippen oeffnet im Browser bzw. in der YouTube-App
struct EmbedCard: View {
    let url: String
    let title: String
    @Environment(\.openURL) private var openURL
    private var u: URL? { url.hasPrefix("http://") || url.hasPrefix("https://") ? URL(string: url) : nil }
    private var isYouTube: Bool { url.contains("youtu.be/") || url.contains("youtube.com/") }
    var body: some View {
        if let u {
            Button { openURL(u) } label: {
                HStack(spacing: 12) {
                    Image(systemName: isYouTube ? "play.rectangle.fill" : url.contains("goodnotes") ? "book.closed" : "link")
                        .font(.title2).foregroundStyle(isYouTube ? .red : .secondary).frame(width: 30)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(title.isEmpty ? (isYouTube ? String(localized: "YouTube-Video") : (u.host ?? url).replacingOccurrences(of: "www.", with: "") + (u.path.count > 1 ? u.path : "")) : title)
                            .font(.subheadline.weight(.semibold)).lineLimit(1)
                        Text((u.host ?? "").replacingOccurrences(of: "www.", with: "")).font(.caption).foregroundStyle(.secondary)
                    }
                    Spacer()
                    Image(systemName: "arrow.up.right").foregroundStyle(.secondary)
                }
                .padding(12).frame(maxWidth: .infinity, alignment: .leading)
                .background(.secondary.opacity(0.1), in: RoundedRectangle(cornerRadius: 10))
            }.buttonStyle(.plain)
        }
    }
}
