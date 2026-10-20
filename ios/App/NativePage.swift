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

/// Inline-Tags (einmal angelegt statt bei jedem Aufruf)
let hkTagRegex = try! NSRegularExpression(pattern: "<(/?)([a-zA-Z]+)([^>]*)>")

/// Wandelt das gespeicherte, bereinigte Inline-HTML (b/i/u/s/mark/code/a/br) in formatierten Text um.
/// Verweise auf Seiten (/notes#123) werden zu hkpage://123 (oeffnet nativ), http(s) bleibt extern.
func inlineText(_ raw: String) -> AttributedString {
    let html = ShareLogic.markMasked(PageLinkTitles.apply(raw))   // Seitenverweis: aktueller Titel; ohne Zugriff: 🔒 ohne Link (1.3)
    var out = AttributedString()
    var bold = 0, italic = 0, under = 0, strike = 0, mark = 0, code = 0, na = 0
    var links: [URL?] = []
    let re = hkTagRegex
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
        if na > 0 { s.foregroundColor = .secondary }
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
        case "na": na += d
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
    // auch Seiten anderer Saga-Instanzen (Foederation: negative IDs, z. B. hkpage://-2049)
    if let id = FedLogic.noteID(href: href) { return URL(string: "hkpage://\(id)") }
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
        guard let w, FedLogic.isImagePath(path), !path.contains("?") else { return path }
        return "\(path)?w=\(w)"
    }
    let path: String
    @EnvironmentObject var store: Store
    var fill = false
    var vAlign: Double = 50                               // Titelbild-Position 0 (oben) ... 100 (unten)
    var w: Int? = nil                                     // verkleinert laden (?w=, Server rechnet einmal und merkt es sich)
    var spinner = false                                   // waehrend des Ladens Fortschritt zeigen (Titelbild)
    @State private var img: PlatformImage?
    @State private var loadedKey: String?
    private var key: String { Self.sized(path, w) }
    var body: some View {
        let key = key
        // Zwischenspeicher schon beim ersten Zeichnen nutzen (z. B. eben gewaehltes Bild): kein Aufblitzen des Platzhalters.
        // Waehrend ein neues Bild laedt, bleibt das bisherige stehen (kein leerer Kasten beim Wechseln).
        let cached = loadedKey == key ? nil : ImageStore.cache.object(forKey: key as NSString)
        let shown = cached ?? img
        Group {
            if let shown {
                let ratio = shown.size.height > 0 ? shown.size.width / shown.size.height : 1
                if fill {
                    CoverFill(image: shown, ratio: ratio, vAlign: vAlign)
                } else {
                    AnimatedImage(image: shown, fill: false).aspectRatio(ratio, contentMode: .fit)
                }
            } else { Rectangle().fill(.secondary.opacity(0.12)) }
        }
        .overlay {
            if spinner && cached == nil && loadedKey != key {
                if failedKey == key {                         // endgueltig nicht geladen: nicht still leer lassen
                    Button { failedKey = nil; attempt += 1 } label: { Label("Erneut versuchen", systemImage: "arrow.clockwise") }
                        .buttonStyle(.bordered).padding(10).background(.regularMaterial, in: Capsule())
                } else {
                    ProgressView().padding(10).background(.regularMaterial, in: Circle())
                }
            }
        }
        .task(id: "\(key)#\(attempt)") { await load(key) }
    }
    @State private var failedKey: String?
    @State private var attempt = 0
    private func load(_ key: String) async {
        if let c = ImageStore.cache.object(forKey: key as NSString) { img = c; loadedKey = key; return }
        defer { if loadedKey != key && !Task.isCancelled { failedKey = key } }
        for wait in HeaderLogic.retryDelays {
            if wait > 0 { try? await Task.sleep(for: .seconds(wait)) }
            if Task.isCancelled { return }
            guard let d = await store.imageData(key) else { continue }
            let decoded = await Task.detached(priority: .userInitiated) { ImageStore.decode(d) }.value      // im Hintergrund entpacken
            // kein Bild (z. B. Fehlerseite eines Proxys gespeichert): verwerfen und frisch holen - sonst bliebe es fuer immer leer
            guard let decoded else { store.forgetImage(key); continue }
            ImageStore.cache.setObject(decoded, forKey: key as NSString, cost: d.count * 4)
            img = decoded; loadedKey = key
            return
        }
    }
}

/// Wie CSS object-fit: cover -> Flaeche ganz bedecken, Seitenverhaeltnis behalten, Rest abschneiden
struct CoverFill: View {
    let image: PlatformImage
    let ratio: CGFloat
    var vAlign: Double = 50
    var body: some View {
        GeometryReader { g in
            let W = g.size.width, H = max(g.size.height, 1)
            let s = W / H > ratio ? CGSize(width: W, height: W / ratio) : CGSize(width: H * ratio, height: H)
            let y = s.height / 2 - (s.height - H) * CGFloat(min(100, max(0, vAlign)) / 100)   // stufenlos wie im Web
            AnimatedImage(image: image, fill: true).frame(width: s.width, height: s.height).position(x: W / 2, y: y)
        }
        .clipped()
    }
}

extension ImageStore {
    /// Eben gewaehltes eigenes Bild unter der Server-Adresse vormerken: es erscheint sofort, ohne erneuten Abruf
    static func prime(_ path: String, w: Int?, _ img: PlatformImage) {
        cache.setObject(img, forKey: RemoteImage.sized(path, w) as NSString, cost: Int(img.size.width * img.size.height * 4))
    }
}

// MARK: - Seite laden/speichern

@MainActor
final class PageDoc: ObservableObject {
    let id: Int
    @Published var page: [String: Any] = [:]
    /// Eben gewaehltes Titelbild (lokal), bis Hochladen + Speichern durch sind: sofort sichtbar statt leerem Kasten
    @Published var coverPreview: PlatformImage?
    /// Titelbild wird hochgeladen/gespeichert (Fortschritt im Titelbild)
    @Published var coverBusy = false
    /// Gesperrte Seite: Symbol/Titelbild angetippt -> kurzer Hinweis (zaehlt hoch)
    @Published var lockHint = 0
    @Published var parentSchema: [String: Any]?
    @Published var error: String?
    @Published var loaded = false
    /// Echte Daten (Server oder Zwischenspeicher) angekommen. Vorher darf NIE gespeichert werden -
    /// sonst ueberschreibt die leere Ansicht ("Unbenannt") nach einem Ladefehler die Seite auf dem Server.
    @Published var fetched = false
    /// Server nicht erreichbar: angezeigt wird der zuletzt geladene Stand
    @Published var offline: String?
    /// Server hat inzwischen eine andere Fassung (409) -> Nutzer entscheidet; bis dahin wird nicht gespeichert
    @Published var conflict = false
    /// Rueckfrage zum Konflikt anzeigen (getrennt von `conflict`, damit das Schliessen des Dialogs sauber ist)
    @Published var askConflict = false
    /// Lokaler Entwurf (ungespeicherte Aenderungen oder Konfliktkopie) zum Wiederherstellen
    @Published var draft: PageDraft?
    // Teilen (1.3)
    /// Rolle, Eigentuemer, Kommentar-Zaehler (aus GET /api/notes/{id}; aeltere Server: eigene Seite)
    @Published var access = PageAccess()
    /// Laden mit 404/403: "Kein Zugriff auf diese Seite"
    @Published var noAccess = false
    /// Konflikt mit einer anderen Person: deren Name ("{name} hat diese Seite gerade geaendert")
    @Published var conflictBy: String?
    /// Text zum Kopieren (Entwurf nach "Neue Version laden", Zugriff verloren)
    @Published var copyDialog: CopyTextDialog?
    /// Zugriff beim Speichern verloren: bis zum Neuladen nichts mehr senden
    private(set) var lostAccess = false
    /// "Meine Version behalten": naechstes Speichern mit overwrite (der Server sichert die andere Fassung als Version)
    private var overwriteNext = false
    /// Sitzung/Konto, mit dem die Seite geladen wurde: nach Abmelden/Kontowechsel nichts mehr senden (Pruefer 1.3)
    private var loadedGen = 0
    private var loadedOrigin: String?
    private var loadedAcct: String?
    private(set) var root = NSMutableArray()
    /// Nextcloud-Bloecke (Autor|Nextcloud|Datei) im zuletzt geladenen/gespeicherten Stand: nur NEUE Bloecke kann der
    /// Server ablehnen (fremde Datei kopiert, nicht verbunden) - dann Meldung statt "kein Zugriff"/Konflikt (2.3)
    private var ncSaved = Set<String>()
    private var doc: NSMutableDictionary = [:]
    private var rev = 0
    private var tracker = EditTracker()
    private let queue = SerialQueue()
    private var retry: Task<Void, Never>?
    private var retryCount = 0
    /// Entwuerfe des angemeldeten Kontos auf diesem Server (jedes Mal frisch: Konto kann nach dem Oeffnen erkannt werden)
    private var drafts: DraftStore? { DraftOwner.app.store(origin: AppConfig.serverOrigin) }
    init(id: Int) { self.id = id }

    var titleRaw: String { page["title"] as? String ?? "" }
    var title: String { titleRaw.isEmpty ? String(localized: "Unbenannt") : titleRaw }
    var icon: String? { page["icon"] as? String }
    var cover: String? { (page["cover"] as? String).flatMap { $0.isEmpty ? nil : $0 } }
    /// Titelbild sichtbar (gespeichert oder eben gewaehlt und noch beim Hochladen)
    var hasCover: Bool { cover != nil || coverPreview != nil }
    var props: [String: Any] { page["props"] as? [String: Any] ?? [:] }
    var isDatabase: Bool { page["kind"] as? String == "database" }
    /// Uploads in eine fremde Seite (Rolle Bearbeiten): Datei gehoert dem Eigentuemer (X-Saga-Note)
    var uploadNote: Int? { access.foreign ? id : nil }
    /// Hochladen nicht moeglich: Server der fremden Seite kann es (noch) nicht (2.3: Faehigkeit upload) -> Meldung statt Versuch
    var uploadBlocked: String? {
        access.canUpload ? nil : String(localized: "Der Server dieser Seite nimmt noch keine Bilder oder Dateien an (ältere Saga-Version).")
    }
    /// Seite als Klartext (Titel + Bloecke, wie sie gerade im Editor stehen)
    func plainTextNow() -> String { ShareLogic.plainText(title: titleRaw, blocks: root, strip: plain) }

    /// ungespeicherte Eingaben -> nicht durch Neuladen ueberschreiben
    var dirty: Bool { tracker.dirty }
    /// Jede Eingabe/Strukturaenderung zaehlt (gespeichert ist nur, was wirklich gesendet wurde)
    func markEdited() { tracker.edit(); lastInput = Date() }
    /// Live (1.4): letzte Eingabe ("bearbeitet gerade"), Revision fuer fremde Aenderungen
    private(set) var lastInput: Date?
    var currentRev: Int { rev }
    /// Foederation (2.0): Problem mit der anderen Saga-Instanz (nicht erreichbar, oeffentlicher Name fehlt)
    @Published var fedProblem: FedLogic.Problem?
    /// Live (1.4): jemand anderes hat die Seite geaendert, waehrend hier ungespeicherte Eingaben liegen
    @Published var changedBy: String?
    /// Statuszeile nach stillem Neuladen: "Aktualisiert – geaendert von {name}"
    @Published var updatedBy: String?
    /// Laeuft noch eine Speicherung oder ist etwas offen? (Beenden abwarten)
    var hasUnsaved: Bool { fetched && (dirty || queue.pending > 0) }

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

    /// `force`: auch ueber eigene, ungespeicherte Eingaben hinweg (nur nach bewusster Entscheidung)
    func load(_ store: Store, force: Bool = false) async {
        if !loaded, let c = store.cachedData("api/notes/\(id)") { await apply(c, store) }     // sofort anzeigen
        do {
            let d = try await store.get("api/notes/\(id)")
            offline = nil; fedProblem = nil
            if dirty && loaded && !force { return }                // eigene Eingaben schuetzen - aber nur, wenn schon etwas angezeigt wird
            if force { tracker.discard() }
            await apply(d, store)
            checkDraft()
        } catch {
            // Seite einer anderen Saga-Instanz: Server nicht erreichbar (502) / oeffentlicher Name fehlt (403) -> Hinweis statt "Kein Zugriff"
            if case APIError.http(let c, let msg) = error, FedLogic.isRemote(id) {
                let p = FedLogic.problem(code: c, message: msg)
                if p == .unreachable || p == .needsPublicName {
                    fedProblem = p
                    if !fetched { self.error = msg.isEmpty ? error.localizedDescription : msg; loaded = true }
                    else { offline = msg }
                    return
                }
            }
            if case APIError.http(let c, _) = error, c == 404 || c == 403, !dirty || !fetched {
                // Seite geloescht oder nicht (mehr) geteilt: nichts Bearbeitbares zeigen, nie speichern;
                // zwischengespeicherten Inhalt verwerfen (sonst offline weiter lesbar)
                store.dropCache("api/notes/\(id)")
                noAccess = true; fetched = false; loaded = true; self.error = nil; offline = nil; return
            }
            if !fetched { self.error = error.localizedDescription; loaded = true }
            else {
                offline = String(localized: "Offline – Änderungen werden gespeichert, sobald der Server erreichbar ist.")
                if !dirty { checkDraft() }                          // auch offline: nicht Gespeichertes vom letzten Mal anbieten
            }
        }
    }

    private func setBody(_ body: String, format: String?) {
        if format == "blocks", let bd = body.data(using: .utf8),
           let m = try? JSONSerialization.jsonObject(with: bd, options: .mutableContainers) as? NSMutableDictionary,
           let arr = m["blocks"] as? NSMutableArray {
            doc = m; root = arr
        } else {                                     // alte Textnotiz -> Absaetze
            root = NSMutableArray(array: body.split(separator: "\n", omittingEmptySubsequences: true).map { PageEditor.make("paragraph", text: mdToHTML(String($0))) })
            doc = ["blocks": root]
        }
        ensureIDs(root)
        if root.count == 0 && !isDatabase { root.add(PageEditor.make("paragraph")) }
    }

    private func apply(_ d: Data, _ store: Store) async {
        guard let p = try? JSONSerialization.jsonObject(with: d) as? [String: Any] else { return }
        page = p; rev = p["rev"] as? Int ?? 0
        let a = PageAccess(page: p, serverVersion: store.serverVersion); if a != access { access = a }
        noAccess = false; lostAccess = false
        loadedGen = store.sessionGen; loadedOrigin = AppConfig.serverOrigin
        loadedAcct = loadedOrigin.flatMap { DraftOwner.app.account(origin: $0) }
        setBody(p["body"] as? String ?? "", format: p["format"] as? String)
        ncSaved = CloudLogic.ncKeys(root)                                  // Nextcloud-Bloecke im gespeicherten Stand (2.3)
        // Eigenschaften nur, wenn die uebergeordnete Seite eine Datenbank ist (spart eine Anfrage); fremde Seite (2.3):
        // Elternseite noch unbekannt (z. B. eben angelegte Zeile) -> nachsehen, ob sie eine Datenbank ist
        if let parent = p["parent_id"] as? Int,
           store.page(parent)?.isDatabase == true || (FedLogic.isRemote(parent) && store.page(parent) == nil && access.remoteCaps.db) {
            let pd = store.cachedData("api/notes/\(parent)")
            let fresh = try? await store.get("api/notes/\(parent)")
            if let raw = fresh ?? pd, let pp = try? JSONSerialization.jsonObject(with: raw) as? [String: Any], pp["kind"] as? String == "database" {
                parentSchema = pp["schema"] as? [String: Any]
            } else { parentSchema = nil }
        } else { parentSchema = nil }
        error = nil; loaded = true; fetched = true
    }

    /// Inhalt so, wie er gespeichert wird
    private func serialized() -> String? {
        doc["blocks"] = root; doc["time"] = Int(Date().timeIntervalSince1970 * 1000)
        guard let data = try? JSONSerialization.data(withJSONObject: doc) else { return nil }
        return String(data: data, encoding: .utf8)
    }

    // MARK: Entwuerfe

    private func writeDraft(conflict: Bool) {
        guard !isDatabase, let body = serialized() else { return }
        drafts?.save(id, PageDraft(title: titleRaw, body: body, rev: rev, saved: Date(), conflict: conflict))
    }
    /// Bei jeder Eingabe sofort lokal sichern (nur Datei, Senden bleibt entprellt): ein Absturz oder Beenden durch
    /// das System kurz nach dem Tippen verliert sonst den Text (BUG-100). Nur fuer geladene, beschreibbare Seiten
    /// der aktuellen Anmeldung.
    func noteInput(_ store: Store) {
        guard fetched, !lostAccess, !access.readOnly, !isDatabase, !staleSession(store) else { return }
        writeDraft(conflict: conflict)
    }
    /// Nach dem Laden: liegt ein Entwurf mit anderem Inhalt vor -> anbieten; sonst aufraeumen
    private func checkDraft() {
        guard let dr = drafts?.load(id) else { draft = nil; return }
        if dr.title == titleRaw, let cur = serialized(), sameBlocks(dr.body, cur) { drafts?.remove(id); draft = nil; return }
        if !dirty && !access.readOnly { draft = dr }                     // nur lesen: kein "Wiederherstellen" anbieten
    }
    /// Entwurf uebernehmen: wird dann mit der aktuellen Server-Revision gespeichert (ueberschreibt)
    func restoreDraft(_ store: Store) async {
        guard let dr = draft else { return }
        page["title"] = dr.title
        setBody(dr.body, format: "blocks")
        draft = nil; conflict = false
        tracker.edit(); objectWillChange.send()
        await save(store)
    }
    func discardDraft() { drafts?.remove(id); draft = nil }

    // MARK: Speichern

    /// Titel + Inhalt speichern - nacheinander, mit Revision (Konflikterkennung wie im Web-Editor)
    func save(_ store: Store) async {
        await queue.run { [weak self] in await self?.saveOnce(store) }
    }

    private func saveOnce(_ store: Store) async {
        guard fetched, dirty, !lostAccess else { return }               // nur echte, geaenderte Seiten; nach Zugriffsverlust nie
        if access.readOnly { tracker.discard(); return }                // Lesen/Kommentieren: nie senden (1.3)
        if staleSession(store) { retry?.cancel(); retry = nil; return } // abgemeldet/anderes Konto: nichts ins neue Konto senden
        if conflict { writeDraft(conflict: true); return }              // Konflikt offen: nicht senden, neueste Eingaben aber lokal sichern
        let sent = tracker.snapshot
        guard let body = serialized() else { return }
        let title = titleRaw
        if !isDatabase { drafts?.save(id, PageDraft(title: title, body: body, rev: rev, saved: Date(), conflict: false)) }   // vor dem Senden lokal sichern
        var b: [String: Any] = ["title": title]
        // "Meine Version behalten": ohne Revision, mit overwrite - der Server sichert die andere Fassung als Version
        if overwriteNext { b["overwrite"] = true } else { b["rev"] = rev }
        if !isDatabase { b["body"] = body; b["format"] = "blocks" }
        do {
            let (r, code) = try await store.apiResponse("api/notes/\(id)", method: "PATCH", body: b)
            guard (200..<300).contains(code) else {
                overwriteNext = false
                failed(code: code, data: r, store: store)
                return
            }
            overwriteNext = false
            if let j = try? JSONSerialization.jsonObject(with: r) as? [String: Any], let nr = j["rev"] as? Int { rev = nr }
            tracker.confirm(sent)
            if let sentDoc = body.data(using: .utf8).flatMap({ try? JSONSerialization.jsonObject(with: $0) as? [String: Any] }) {
                ncSaved = CloudLogic.ncKeys(sentDoc["blocks"])                 // gesendeter Stand ist jetzt der gespeicherte
            }
            error = nil; offline = nil; retryCount = 0; retry?.cancel(); retry = nil
            if !dirty { drafts?.remove(id) }
            store.updateTitle(id, title)
            if access.updatedBy != nil && access.updatedBy?.me != true { access.updatedBy = SharePerson(name: "", me: true) }   // "zuletzt geaendert von" bin jetzt ich
            if parentSchema != nil { store.dataChanged() }          // Eintrag einer Datenbank: Titel in den Ansichten aktualisieren
        } catch {
            self.error = String(localized: "Nicht gespeichert – lokal gesichert, neuer Versuch folgt (\(error.localizedDescription)).")
            scheduleRetry(store)
        }
    }

    /// Speichern abgelehnt: Konflikt (auch mit anderer Person), Zugriff verloren, dauerhafter oder voruebergehender Fehler
    private func failed(code: Int, data: Data, store: Store) {
        // Nextcloud-Block abgelehnt (2.3): weder Konflikt noch Zugriffsverlust - Meldung, Entwurf behalten, nicht wiederholen
        if let msg = CloudLogic.saveRejection(code: code, data: data, saved: ncSaved, now: CloudLogic.ncKeys(root)) {
            retry?.cancel(); retry = nil; retryCount = 0
            writeDraft(conflict: false)
            self.error = msg
            return
        }
        switch ShareLogic.saveFailure(code: code, data: data, foreign: access.foreign) {
        case .conflict(let by):
            writeDraft(conflict: true)                                 // eigene Fassung bleibt in jedem Fall erhalten
            conflictBy = by; conflict = true; askConflict = true
        case .lostAccess, .readOnlyNow:
            // Freigabe entzogen (404) bzw. nur noch Lesen (403): letzten Text zum Kopieren zeigen, danach nicht mehr speichern
            let ro = code == 403
            lostAccess = true; retry?.cancel(); retry = nil; retryCount = 0
            let text = plainTextNow()
            tracker.discard(); drafts?.remove(id)
            copyDialog = CopyTextDialog(kind: ro ? .readOnlyNow : .lostAccess, text: text)
            // Seite zeigt sofort den neuen Zustand (Etikett, schreibgeschuetzt bzw. "Kein Zugriff") - nicht erst nach dem Dialog
            Task { await load(store, force: true); await store.refresh(quiet: true) }
        case .other(let c) where isPermanentSaveError(c):
            // dauerhafter Fehler: nicht automatisch wiederholen, klar anzeigen; Entwurf (vor dem Senden geschrieben) bleibt
            retry?.cancel(); retry = nil; retryCount = 0
            writeDraft(conflict: false)                                // neuester Stand (auch spaetere Eingaben) lokal
            self.error = PageDoc.permanentErrorText(c)
        case .other(let c):
            let msg = APIError.http(c, ShareJSON.obj(data).flatMap { ShareJSON.str($0["error"]) } ?? "").localizedDescription
            self.error = String(localized: "Nicht gespeichert – lokal gesichert, neuer Versuch folgt (\(msg)).")
            scheduleRetry(store)
        }
    }

    static func permanentErrorText(_ code: Int) -> String {
        switch code {
        case 403: return String(localized: "Nicht gespeichert: Du darfst diese Seite nicht bearbeiten. Deine Änderungen bleiben auf diesem Gerät als Entwurf erhalten.")
        case 404: return String(localized: "Nicht gespeichert: Diese Seite gibt es auf dem Server nicht mehr. Deine Änderungen bleiben auf diesem Gerät als Entwurf erhalten.")
        case 413: return String(localized: "Nicht gespeichert: Die Seite ist zu groß für den Server. Deine Änderungen bleiben auf diesem Gerät als Entwurf erhalten.")
        case 423: return String(localized: "Nicht gespeichert: Die Seite ist gesperrt. Deine Änderungen bleiben auf diesem Gerät als Entwurf erhalten.")
        default: return String(localized: "Nicht gespeichert: Der Server hat die Änderung abgelehnt (Fehler \(code)). Deine Änderungen bleiben auf diesem Gerät als Entwurf erhalten.")
        }
    }

    private func scheduleRetry(_ store: Store) {
        retry?.cancel()
        let wait = saveRetryDelay(retryCount); retryCount += 1
        retry = Task { [weak self] in
            try? await Task.sleep(for: .seconds(wait))
            guard !Task.isCancelled else { return }
            await self?.save(store)
        }
    }

    /// Konflikt: eigene Fassung behalten -> mit overwrite speichern (die andere Fassung sichert der Server als Version)
    func keepMine(_ store: Store) async {
        overwriteNext = true; conflict = false; conflictBy = nil
        if !dirty { tracker.edit() }
        await save(store)
    }
    /// Konflikt: Server-Fassung laden - die eigene bleibt als lokale Kopie (Entwurf) und kann wiederhergestellt werden
    func takeServer(_ store: Store) async {
        guard drafts != nil || isDatabase else {                       // Konto noch unbekannt: keine lokale Kopie moeglich -> nichts verwerfen
            error = String(localized: "Server nicht erreichbar – bitte später erneut versuchen."); askConflict = false; return
        }
        writeDraft(conflict: true)
        conflict = false; conflictBy = nil
        await load(store, force: true)
    }
    /// Konflikt mit einer anderen Person: "Neue Version laden" - der eigene, nicht gespeicherte Text kommt danach in den Kopier-Dialog
    func loadNewVersion(_ store: Store) async {
        let text = plainTextNow()
        conflict = false; conflictBy = nil
        await load(store, force: true)
        guard fetched else { return }
        drafts?.remove(id); draft = nil                               // statt Entwurfs-Hinweis: Text zum Kopieren
        if !text.isEmpty { copyDialog = CopyTextDialog(kind: .draft, text: text) }
    }
    /// Seite wurde mit einer frueheren Anmeldung bzw. einem anderen Konto geladen (Abmelden, Kontowechsel, Kopplung):
    /// dann nichts mehr senden - sonst landeten Eingaben des alten Kontos unter dem neuen (mit Teilen evtl. erlaubt).
    func staleSession(_ store: Store) -> Bool {
        if store.sessionGen != loadedGen { return true }
        let now = AppConfig.serverOrigin.flatMap { DraftOwner.app.account(origin: $0) }
        return AppConfig.serverOrigin != loadedOrigin || (loadedAcct != nil && now != nil && loadedAcct != now)
    }
    /// Neu angemeldet (anderes Konto/neue Sitzung), Seite noch offen: Eingaben als Entwurf des ALTEN Kontos sichern,
    /// dann alles vergessen und fuer das neue Konto frisch laden (Rolle, Inhalt; 404 -> "Kein Zugriff").
    func resetForNewSession(_ store: Store) async {
        retry?.cancel(); retry = nil; retryCount = 0
        if dirty, !isDatabase, let o = loadedOrigin, let a = loadedAcct, let body = serialized() {
            DraftStore(origin: o, account: a).save(id, PageDraft(title: titleRaw, body: body, rev: rev, saved: Date(), conflict: conflict))
        }
        // Konto der Seite unbekannt (kein Entwurf moeglich): ungesicherten Text nicht still verwerfen -> Kopier-Dialog
        let orphan = dirty && !isDatabase && loadedAcct == nil ? plainTextNow() : ""
        tracker.discard(); conflict = false; conflictBy = nil; askConflict = false; overwriteNext = false
        draft = nil; copyDialog = nil; lostAccess = false
        fetched = false; loaded = false
        await load(store)
        if !orphan.isEmpty { copyDialog = CopyTextDialog(kind: .draft, text: orphan) }
    }
    /// Kopier-Dialog nach Zugriffsverlust geschlossen: neu laden (403: jetzt schreibgeschuetzt; 404: "Kein Zugriff")
    func afterLostAccess(_ store: Store) async {
        guard lostAccess else { return }
        await load(store, force: true)
        await store.refresh(quiet: true)
    }
}

extension DraftOwner {
    /// Entwurfs-Zuordnung der App (Einstellungen dieser App, nicht der App-Gruppe)
    static var app: DraftOwner { DraftOwner(defaults: .standard) }
}

/// Seitentitel: einzeilig gespeichert - Return springt in den ersten Block (BUG-062). Nicht geladene Seite: Titel aus
/// der Seitenliste statt "Unbenannt", nicht bearbeitbar (BUG-061).
struct PageTitleField: View {
    @ObservedObject var doc: PageDoc
    let ed: PageEditor
    var fallback: String?
    @EnvironmentObject private var nav: NavModel
    @FocusState private var focused: Bool
    var body: some View {
        if !doc.fetched, doc.titleRaw.isEmpty, let t = fallback, !t.isEmpty {
            Text(t).font(.largeTitle.bold()).foregroundStyle(.secondary).frame(maxWidth: .infinity, alignment: .leading)
        } else {
            TextField("Unbenannt", text: Binding(get: { doc.titleRaw }, set: { v in input(v) }), axis: .vertical)
                .font(.largeTitle.bold()).textFieldStyle(.plain).disabled(ed.locked || !doc.fetched)
                .focused($focused)
                .onSubmit { ed.enterFromTitle() }                     // Mac: Return ohne Umbruch
                .onChange(of: doc.fetched, initial: true) { _, f in    // neuer Datenbank-Eintrag: Titel gleich bearbeiten
                    guard f, nav.focusTitle == doc.id, !ed.locked else { return }
                    nav.focusTitle = nil
                    Task { try? await Task.sleep(for: .milliseconds(400)); focused = true }
                }
        }
    }
    private func input(_ v: String) {
        let r = TitleInput.apply(old: doc.titleRaw, new: v)
        if r.title != doc.titleRaw { doc.page["title"] = r.title; ed.textChanged() }   // nur echte Aenderung
        else if v != r.title { doc.objectWillChange.send() }          // Umbruch verworfen: Feld neu zeichnen
        if r.enter { ed.enterFromTitle() }
    }
}

/// Offene Editoren (Speichern vor dem Beenden abwarten)
@MainActor
enum SaveRegistry {
    private static let editors = NSHashTable<PageEditor>.weakObjects()
    static func add(_ e: PageEditor) { editors.add(e) }
    static var hasUnsaved: Bool { editors.allObjects.contains { $0.doc.hasUnsaved } }
    /// Seiten mit offenem Editor (die senden ihre Entwuerfe selbst)
    static var openIDs: Set<Int> { Set(editors.allObjects.map(\.doc.id)) }
    static func flushAll() async { for e in editors.allObjects { await e.flush() } }
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
    @State private var publishing = false                // Dialog "Veroeffentlichen"
    @State private var sharing = false                   // Dialog "Teilen" (nur Eigentuemer)
    @State private var commentsTarget: CommentsTarget?   // Kommentare (alle bzw. eines Blocks)
    @State private var scrolled = false                  // grosser Titel weggescrollt -> kleiner Titel + Leiste
    @State private var topInset: CGFloat = 0
    @State private var presence: [PresenceUser] = []     // Live (1.4): andere auf dieser Seite
    @State private var presenceOff = false               // 404/aelterer Server: nicht mehr senden
    @State private var assistant: AssistantLaunch?       // KI-Assistent (1.6)
    /// Editor-Tastaturleiste nur, solange ein Seitenblock bearbeitet wird und kein Blatt mit eigenem Textfeld offen ist
    private var editorKeyboard: Bool {
        ed.focus != nil && commentsTarget == nil && assistant == nil && !sharing && !publishing && !moving && !showVersions
    }
    #if os(iOS)
    @ScaledMetric(relativeTo: .body) private var editorBarHeight: CGFloat = 56   // Hoehe der Editor-Leiste (waechst mit der Schrift)
    #endif
    @Environment(\.scenePhase) private var phase
    init(id: Int, inPeek: Bool = false) {
        self.id = id; self.inPeek = inPeek
        let d = PageDoc(id: id)
        _doc = StateObject(wrappedValue: d); _ed = StateObject(wrappedValue: PageEditor(doc: d))
    }

    @ViewBuilder private var favButton: some View {
        if doc.access.allows(.favorite) { favButtonOwn }
    }
    /// Kommentare in der Kopfzeile: nur bei offenen Unterhaltungen (dezent)
    @ViewBuilder private var commentsButton: some View {
        if doc.access.commentsOpen > 0 {
            CommentsHeadButton(open: doc.access.commentsOpen, unread: doc.access.commentsUnread) { commentsTarget = CommentsTarget(block: nil) }
        } else if doc.access.remote && doc.access.commentsSupported && doc.access.canComment && doc.fetched {
            // fremde Seite (Foederation): der Hinweis verspricht "kommentieren" - also den Weg dorthin sichtbar anbieten
            Button { commentsTarget = CommentsTarget(block: nil) } label: { Image(systemName: "bubble.left") }
                .help(Text("Kommentieren")).accessibilityLabel(Text("Kommentieren"))
        }
    }
    private var favButtonOwn: some View {
        let fav = doc.page["pinned"] as? Bool ?? false
        return Button { Task { try? await store.patch(id, ["pinned": !fav]); doc.page["pinned"] = !fav } } label: {
            Image(systemName: fav ? "star.fill" : "star").foregroundStyle(fav ? .yellow : .primary)
        }.help(fav ? "Aus Favoriten entfernen" : "Zu Favoriten")
        .accessibilityLabel(fav ? "Aus Favoriten entfernen" : "Zu Favoriten")
    }
    @ViewBuilder private var pageMenuItems: some View {
                    let a = doc.access
                    if let by = a.sharedBy { Text("Geteilt von \(by) · \(RoleText.you(a.role))") }       // fremde Seite: Etikett, kein Knopf
                    Text(editedText(doc.page["updated"] as? Double))
                    #if os(iOS)
                    if !store.path(id).isEmpty {
                        Menu { ForEach(store.path(id)) { p in Button { nav.push(PageRoute(id: p.id)) } label: { Label(p.label, systemImage: "arrow.up.forward.square") } } }
                            label: { Label("Übergeordnete Seiten", systemImage: "arrow.up.forward") }
                    }
                    #endif
                    if a.allows(.wide) {
                        Toggle(isOn: Binding(get: { doc.page["wide"] as? Bool ?? false },
                                             set: { v in Task { try? await store.patch(id, ["wide": v]); doc.page["wide"] = v } })) { Label("Volle Breite", systemImage: "arrow.left.and.right") }
                    }
                    Button { store.setHome(store.homePage == id ? nil : id) } label: {
                        Label(store.homePage == id ? "Nicht mehr Startseite" : "Als Startseite festlegen", systemImage: store.homePage == id ? "house.slash" : "house")
                    }
                    if a.foreign && a.allows(.addSubpage) && !doc.isDatabase {      // fremde Instanz (2.3): nur mit Faehigkeit subpages
                        Button { Task { await ed.flush(); if let n = await store.newPage(parent: id) { nav.push(PageRoute(id: n)) } } } label: { Label("Unterseite hinzufügen", systemImage: "doc.badge.plus") }
                    }
                    if store.aiEnabled && doc.fetched {                // KI-Assistent (1.6): nur, wenn der Server ihn fuer mich anbietet
                        Menu {
                            if !a.remote && !doc.isDatabase {
                                Button { launchAssistant(tab: 1, action: "summarize") } label: { Label("Zusammenfassen", systemImage: "text.alignleft") }
                                Button { launchAssistant(tab: 1, action: "tasks") } label: { Label("Aufgaben vorschlagen", systemImage: "checklist") }
                                Button { launchAssistant(tab: 1, action: nil) } label: { Label("Markierten Text verbessern", systemImage: "wand.and.stars") }
                            }
                            Button { launchAssistant(tab: 0, action: nil) } label: { Label("Frag deine Seiten", systemImage: "text.magnifyingglass") }
                        } label: { Label(store.aiMenuLabel, systemImage: "sparkles") }
                    }
                    if store.chat.state == .ready && (!a.remote || a.remoteCaps.chatgroups) && doc.fetched {   // Chat (2.0): Seiten-Chat fuer alle mit Zugriff; fremde Seite ab 2.3
                        Button { Task { await openPageChat() } } label: { Label("Chat zu dieser Seite", systemImage: "bubble.left.and.bubble.right") }
                    }
                    if a.commentsSupported {                          // Server 1.3: Kommentare (mit "n erledigt") und Teilen
                        Button { commentsTarget = CommentsTarget(block: nil) } label: {
                            Label("Kommentare", systemImage: "bubble.left")
                            if a.commentsResolved > 0 { Text("\(a.commentsResolved) erledigt") }
                        }
                        if a.allows(.share) && doc.fetched {
                            Button { Task { await ed.flush(); sharing = true } } label: { Label("Teilen …", systemImage: a.shared ? "person.2.fill" : "person.2") }
                        }
                    }
                    Divider()
                    if a.allows(.move) { Button { moving = true } label: { Label("Verschieben nach …", systemImage: "arrow.turn.up.right") } }
                    if a.allows(.versions) { Button { Task { await ed.flush(); showVersions = true } } label: { Label("Versionsverlauf …", systemImage: "clock.arrow.circlepath") } }
                    #if os(macOS)
                    if a.allows(.export) { Menu {
                        Button("Als Markdown (.md)") { Task { await ed.flush(); if let e = await exportPage(id, title: doc.title, withSub: false, store: store) { doc.error = e } } }
                        if !store.children(of: id).isEmpty || !store.sharedChildren(of: id).isEmpty {
                            Button("Mit Unterseiten (.zip)") { Task { await ed.flush(); if let e = await exportPage(id, title: doc.title, withSub: true, store: store) { doc.error = e } } }
                        }
                    } label: { Label("Exportieren", systemImage: "square.and.arrow.down") } }
                    #endif
                    if a.allows(.template) {
                        Toggle(isOn: Binding(get: { doc.page["template"] as? Bool ?? false },
                                             set: { v in Task { try? await store.patch(id, ["template": v]); doc.page["template"] = v; await store.refresh() } })) { Label("Als Vorlage", systemImage: "doc.on.doc") }
                    }
                    if a.allows(.duplicate) { Button { Task { if let n = await store.duplicate(id) { nav.push(PageRoute(id: n)) } } } label: { Label("Duplizieren", systemImage: "plus.square.on.square") } }
                    // Veroeffentlichen: nur, wenn der Server es kann (liefert "published"), nicht fuer Vorlagen
                    if a.allows(.publish) && doc.fetched && PublicBadge.supported(doc.page) && !(doc.page["template"] as? Bool ?? false) {
                        Button { Task { await ed.flush(); publishing = true } } label: { Label("Veröffentlichen …", systemImage: "globe") }
                    }
                    if let u = URL(string: AppConfig.server.absoluteString + "/notes#\(id)") {
                        ShareLink(item: u) { Label("Link teilen", systemImage: "square.and.arrow.up") }
                    }
                    Button { nav.peek = nil; nav.push(PageRoute(id: id, web: true)) } label: { Label("Im Web-Editor öffnen", systemImage: "safari") }
                    Button { Task { await ed.flush(); await doc.load(store) } } label: { Label("Neu laden", systemImage: "arrow.clockwise") }
                    if a.foreign {
                        Divider()
                        if a.allows(.versions) || a.allows(.move) { Text("Löschen und Freigaben verwaltet der Eigentümer.") }   // 2.3: Bearbeiten darf mehr
                        else { Text("Verschieben, Löschen, Versionen und Freigaben verwaltet der Eigentümer.") }
                    } else {
                        Divider()
                        Button { Task { await setLocked(!pageLocked) } } label: {
                            Label(pageLocked ? "Seite entsperren" : "Seite sperren", systemImage: pageLocked ? "lock.open" : "lock")
                        }
                        let isArch = (doc.page["archived"] as? Bool) == true
                        Button { Task { await ed.flush(); await store.setArchived(id, !isArch); doc.page["archived"] = !isArch } } label: {
                            Label(isArch ? "Aus dem Archiv holen" : "Archivieren", systemImage: isArch ? "tray.and.arrow.up" : "archivebox")
                        }
                        Button(role: .destructive) { Task { await ed.flush(); if await store.trash(id) { if inPeek { nav.peek = nil } else { dismiss() } } } } label: { Label("In den Papierkorb", systemImage: "trash") }   // im Peek: nur den Peek schliessen
                    }
    }
    /// "✦ Assistent" in der Kopfzeile (nur, wenn der Server ihn fuer mich anbietet)
    @ViewBuilder private var assistantButton: some View {
        if store.aiEnabled && doc.fetched {
            Button { launchAssistant(tab: doc.access.remote || doc.isDatabase ? 0 : 1, action: nil) } label: {
                if store.aiPersona != nil { AIAvatarView(persona: store.aiPersona, size: 22) } else { Image(systemName: "sparkles") }   // 2.1: Bild der KI
            }
                .help(Text(verbatim: store.aiMenuLabel)).accessibilityLabel(Text(verbatim: store.aiMenuLabel))
        }
    }
    private func launchAssistant(tab: Int, action: String?) {
        let sel = ed.focusedBlock.map { [$0] } ?? []                     // markiert = Block mit dem Cursor (vor dem Blatt merken)
        Task { await ed.flush() }
        assistant = AssistantLaunch(tab: tab, action: action, selected: sel)
    }
    /// Seiten-Chat oeffnen (idempotent: gibt es ihn schon, kommt er zurueck)
    private func openPageChat() async {
        guard let d = try? await store.api("api/chats", method: "POST", body: ["kind": "page", "note_id": id, "device": store.chat.deviceID ?? ""]),
              let cid = ShareJSON.int((ShareJSON.obj(d)?["chat"] as? [String: Any])?["id"]) else { return }
        NotificationCenter.default.post(name: .hkOpenTarget, object: NotifTarget.chat(id: cid))
    }
    private var assistantActions: AssistantPageActions {
        AssistantPageActions(canEdit: !doc.access.readOnly, locked: pageLocked, remote: doc.access.remote, selected: assistant?.selected ?? [],
                             flush: { await ed.flush() },
                             replace: { ids, segs in
                                 for (bid, t) in zip(ids, segs) {
                                     guard let (arr, i) = ed.locate(bid), let b = arr[i] as? NSMutableDictionary, let d = b["data"] as? NSMutableDictionary else { continue }
                                     if d["text"] != nil { d["text"] = htmlText(t) } else if d["title"] != nil { d["title"] = htmlText(t) }
                                 }
                                 ed.changed(); Task { await ed.flush() }
                             },
                             insertBelow: { t in
                                 let after = assistant?.selected.last ?? (doc.root.lastObject as? NSMutableDictionary)?.blockID
                                 ed.insert(PageEditor.make("paragraph", text: htmlText(t)), after: after); ed.focus = nil
                                 Task { await ed.flush() }
                             },
                             insertChecklist: { tasks in
                                 let b = PageEditor.make("checklist")
                                 (b["data"] as? NSMutableDictionary)?["items"] = NSMutableArray(array: tasks.map { NSMutableDictionary(dictionary: ["content": htmlText($0), "meta": NSMutableDictionary(dictionary: ["checked": false]), "items": NSMutableArray()]) })
                                 ed.insert(b, after: (doc.root.lastObject as? NSMutableDictionary)?.blockID); ed.focus = nil
                                 Task { await ed.flush() }
                             })
    }

    // MARK: Live (1.4): Anwesenheit, fremde Aenderungen

    /// alle ~20 s, solange die Seite offen und die App vorne ist; Seiten anderer Saga-Instanzen nur, wenn deren Server
    /// Anwesenheit kann (2.3: meldet zugleich das Live-Abo beim Server der Seite an, 90 s)
    private func presenceLoop() async {
        for _ in 0..<40 where !doc.fetched { try? await Task.sleep(for: .milliseconds(250)) }   // gleich nach dem Laden melden (sonst kein note.changed)
        guard !FedLogic.isRemote(id) || doc.access.presence else { return }
        while !Task.isCancelled && !presenceOff {
            if phase == .active && doc.fetched && !doc.noAccess { await sendPresence(leave: false) }
            try? await Task.sleep(for: .seconds(20))
        }
    }
    private func sendPresence(leave: Bool) async {
        guard !FedLogic.isRemote(id) || doc.access.presence, !presenceOff else { return }
        let body: [String: Any] = leave ? ["note_id": id, "leave": true]
            : ["note_id": id, "editing": LiveLogic.editing(lastInput: doc.lastInput, canEdit: !doc.access.readOnly && !pageLocked)]
        guard let (d, code) = try? await store.apiResponse("api/presence", method: "POST", body: body) else { return }
        if code == 404 || code == 405 { presenceOff = code == 405; return }
        guard !leave, code == 200, let j = ShareJSON.obj(d), !ShareJSON.bool(j["skipped"]) else { return }
        let others = PresenceUser.others(j["users"] as? [Any])
        if others != presence { presence = others }
        pageChanged(rev: ShareJSON.int(j["rev"]), by: SharePerson(j["by"]), gone: false)
    }
    /// note.changed / presence-rev: still neu laden oder Hinweis "{name} hat diese Seite geaendert"
    private func pageChanged(rev: Int?, by: SharePerson?, gone: Bool) {
        guard doc.fetched else { return }
        let who = by.flatMap { $0.me ? nil : ($0.name.isEmpty ? nil : $0.name) }
        switch LiveLogic.pageChange(eventRev: rev, ownRev: doc.currentRev, dirty: doc.dirty || ed.focus != nil && doc.dirty, gone: gone) {
        case .ignore: break
        case .reloadSilently: Task { await doc.load(store); doc.updatedBy = who }
        case .ask: doc.changedBy = who ?? String(localized: "Jemand")
        case .gone: Task { doc.loaded = false; await doc.load(store) }
        }
    }
    private func liveEvent(_ e: LiveEvent) {
        switch e.type {
        case "note.changed" where ShareJSON.int(e.data["id"]) == id:
            pageChanged(rev: ShareJSON.int(e.data["rev"]), by: SharePerson(e.data["by"]), gone: ShareJSON.bool(e.data["gone"]))
        case "presence" where ShareJSON.int(e.data["note_id"]) == id:
            let o = PresenceUser.others(e.data["users"] as? [Any]); if o != presence { presence = o }
        case "comments.changed" where ShareJSON.int(e.data["note_id"]) == id:
            Task {
                guard let d = try? await store.api("api/notes/\(id)/comments"), let s = CommentsState(data: d) else { return }
                let (counts, unread) = ShareLogic.blockCounts(s.threads)
                var a = doc.access
                a.commentCounts = counts; a.commentsOpen = s.open.count; a.commentsUnread = s.open.filter(\.unread).count; a.commentsResolved = s.resolvedCount
                if a != doc.access { doc.access = a }
                if ed.commentUnread != unread { ed.commentUnread = unread }
            }
        default: break
        }
    }

    /// Seite vom Eigentuemer gesperrt (unabhaengig von der Rolle)
    private var pageLocked: Bool { doc.page["locked"] as? Bool ?? false }
    /// Symbol/Titelbild nicht aenderbar: Rolle, Sperre, fremde Instanz - und solange die Seite nie geladen wurde
    /// (sonst ueberschriebe ein PATCH ein unbekanntes vorhandenes Symbol/Titelbild, BUG-061)
    private var headerReadOnly: Bool { doc.access.readOnly || pageLocked || !doc.access.remoteExtras || !doc.fetched }
    /// Tippen auf Symbol/Titelbild der gesperrten (eigenen bzw. bearbeitbaren) Seite: Hinweis statt Nichtstun
    private var headerLocked: Bool {
        HeaderLogic.tap(readOnlyRole: doc.access.readOnly, remote: !doc.access.remoteExtras, fetched: doc.fetched, locked: pageLocked) == .lockedHint
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
                .accessibilityLabel("Schließen")
            Button { nav.peek = nil; nav.push(PageRoute(id: id)) } label: { Image(systemName: "arrow.up.left.and.arrow.down.right") }.help("Als ganze Seite öffnen")
                .accessibilityLabel("Als ganze Seite öffnen")
            if nav.peekList.count > 1 {                                  // wie Notion: im Peek durch die Ansicht blaettern
                let i = nav.peekList.firstIndex(of: id)
                Button { Task { await ed.flush(); nav.peekStep(-1) } } label: { Image(systemName: "chevron.up") }
                    .help("Vorheriger Eintrag (⌃⇧K)").accessibilityLabel("Vorheriger Eintrag").keyboardShortcut("k", modifiers: [.control, .shift]).disabled(i == nil || i == 0)
                Button { Task { await ed.flush(); nav.peekStep(1) } } label: { Image(systemName: "chevron.down") }
                    .help("Nächster Eintrag (⌃⇧J)").accessibilityLabel("Nächster Eintrag").keyboardShortcut("j", modifiers: [.control, .shift]).disabled(i == nil || i == nav.peekList.count - 1)
            }
            if !doc.noAccess { Text(doc.title).font(.headline).lineLimit(1).foregroundStyle(.secondary) }
            Spacer()
            if !doc.noAccess {
                assistantButton
                commentsButton
                favButton
                Menu { pageMenuItems } label: { Image(systemName: "ellipsis.circle") }.menuIndicator(.hidden).fixedSize().accessibilityLabel("Seitenmenü")
            }
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
    // Seite in drei Teilen (Inhalt, Blaetter, Leiste/Ablauf): eine einzige lange Modifier-Kette braucht im CI-Xcode
    // sonst viele Sekunden Typpruefung
    private var scrollPart: some View {
        ScrollViewReader { proxy in
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                if !doc.noAccess {                                   // Kein Zugriff: weder Titelbild noch Symbol der Seite
                    #if os(iOS)
                    CoverView(doc: doc, extendTop: topInset, readOnly: headerReadOnly, locked: headerLocked)          // Titelbild bis ganz nach oben (wie Notion)
                    #else
                    CoverView(doc: doc, readOnly: headerReadOnly, locked: headerLocked)
                    #endif
                }
                VStack(alignment: .leading, spacing: 10) {
                    if doc.noAccess {
                        NoAccessView(retry: { Task { doc.loaded = false; await doc.load(store); await store.refresh(quiet: true) } },
                                     toShared: { nav.toShared() })
                    } else {
                    IconRow(doc: doc, readOnly: headerReadOnly, locked: headerLocked)
                    PageTitleField(doc: doc, ed: ed, fallback: store.page(id)?.title)
                    if let by = doc.access.sharedBy { SharedByLabel(owner: by, role: doc.access.role) }      // fremde Seite (1.3)
                    PageMetaLine(access: doc.access, updated: doc.page["updated"] as? Double)
                    if let host = doc.access.remoteHost { RemotePageBanner(host: host, canComment: doc.access.commentsSupported && doc.access.canComment, canEdit: !doc.access.readOnly, full: doc.access.remoteCaps.db) }                    // Foederation (2.0)
                    if !presence.isEmpty { PresenceBar(users: presence) }                                 // Live (1.4)
                    if let by = doc.changedBy { ChangedBanner(name: by) { doc.changedBy = nil; ed.focus = nil; Task { await doc.loadNewVersion(store) } } }
                    if let by = doc.updatedBy { Label("Aktualisiert – geändert von \(by)", systemImage: "arrow.triangle.2.circlepath").font(.caption).foregroundStyle(.secondary) }
                    if pageLocked {
                        if doc.access.allows(.lock) {
                            Button { Task { await setLocked(false) } } label: { Label("Gesperrt – tippen zum Entsperren", systemImage: "lock.fill").font(.caption) }
                                .buttonStyle(.bordered).controlSize(.small).tint(.secondary)
                        } else {
                            Label("Der Eigentümer hat die Seite gesperrt", systemImage: "lock.fill").font(.caption).foregroundStyle(.secondary)
                        }
                    }
                    if let pb = PublicBadge.from(doc.page) { PublicBadgeButton(badge: pb) { publishing = true } }
                    if let o = doc.offline {
                        Label(o, systemImage: "wifi.slash").font(.footnote).foregroundStyle(.orange)
                    }
                    if doc.conflict && !doc.askConflict && !doc.access.readOnly {   // Rueckfrage weggeklickt: erneut entscheiden (nur lesen: nichts zu entscheiden)
                        Button { doc.askConflict = true } label: { Label("Seite wurde woanders geändert", systemImage: "exclamationmark.triangle") }
                            .font(.footnote).buttonStyle(.bordered).tint(.orange)
                    }
                    if let dr = doc.draft, !doc.access.readOnly { DraftBanner(draft: dr, doc: doc, ed: ed) }   // nur lesen: nichts wiederherzustellen
                    if doc.fetched && !doc.isDatabase && doc.access.remoteExtras { TagsRow(doc: doc) }   // fremde Instanz: nur mit Faehigkeit db (2.3)
                    if let schema = doc.parentSchema { EditableProps(doc: doc, schema: schema).padding(.bottom, 8).disabled(doc.access.readOnly) }
                    if doc.isDatabase {
                        DatabaseView(dbID: id, viewID: nil, localViews: nil, pageLocked: pageLocked)
                    } else if doc.fetched {
                        BlocksView(arr: doc.root)
                        // wie Notion: Klick in den freien Bereich unter dem letzten Block -> weiterschreiben
                        Color.clear.frame(height: 160).contentShape(Rectangle()).onTapGesture { if !ed.locked { ed.continueWriting() } }
                        BacklinksView(id: id)
                    }
                    if let e = doc.error { Text(e).font(.footnote).foregroundStyle(.orange) }
                    if doc.fedProblem == .needsPublicName {                       // Foederation: oeffentlicher Name fehlt
                        Button { NotificationCenter.default.post(name: .hkOpenSettings, object: "konto") } label: { Label("Einstellungen", systemImage: "gearshape") }
                            .buttonStyle(.bordered)
                    }
                    if doc.loaded && !doc.fetched {                              // Laden fehlgeschlagen: nichts Bearbeitbares zeigen
                        Button { Task { doc.loaded = false; await doc.load(store) } } label: { Label("Erneut versuchen", systemImage: "arrow.clockwise") }
                            .prominentGold().padding(.top, 4)
                    }
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
        #else
        // Schreibzeile ueber die Tastatur holen: das Textfeld scrollt nicht selbst (scrollDisabled in der Seite)
        .onChange(of: ed.focus) { _, f in revealFocus(proxy, f, delay: 0.35) }
        .onChange(of: ed.slashDock) { _, d in if d != nil { revealFocus(proxy, ed.focus, delay: 0.05) } }
        #endif
        }
        .scrollDismissesKeyboard(.interactively)
        #if os(iOS)
        .scrollEdgeEffectHidden(!scrolled && doc.hasCover, for: .top)    // iOS 26: Titelbild unter der Leiste nicht abdunkeln
        .background {                                                          // Hoehe von Statusleiste + Navigation am aeusseren Rahmen messen
            GeometryReader { g in Color.clear.onAppear { topInset = g.safeAreaInsets.top }.onChange(of: g.safeAreaInsets.top) { _, v in topInset = v } }
        }
        .onScrollGeometryChange(for: Bool.self) { g in g.contentOffset.y + g.contentInsets.top > (doc.hasCover ? 250 : 70) } action: { _, v in
            withAnimation(.easeInOut(duration: 0.2)) { scrolled = v }
        }
        .toolbarBackground(scrolled ? .visible : .hidden, for: .navigationBar)
        // "/"-Menue ueber der Tastatur andocken (inline lag es unter der Tastaturleiste); die Seite wird entsprechend kuerzer
        .safeAreaInset(edge: .bottom, spacing: 0) {
            if editorKeyboard, let d = ed.slashDock, ed.focus == d.key {
                SlashMenu(query: d.query, nextcloud: store.serverVersion.map { !ShareLogic.versionLess($0, "2.3.0") } ?? false, docked: true) { type, level in
                    ed.slashApply(String(d.key.split(separator: "/").first ?? ""), type: type, level: level)
                }
                .padding(.horizontal, 12).padding(.top, 6).padding(.bottom, 10)
                .frame(maxWidth: .infinity)
                .fixedSize(horizontal: false, vertical: true)           // feste Hoehe aus der Trefferzahl (F3)
                .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        // die schwebende Editor-Leiste ueber der Tastatur zaehlt nicht zur Tastatur: Platz dafuer lassen, sonst liegt die
        // Schreibzeile unten auf der Seite dahinter (BUG-058)
        .safeAreaPadding(.bottom, editorKeyboard ? editorBarHeight : 0)
        #endif
    }
    #if os(iOS)
    /// Fokussierten Block sichtbar machen (kleinster Weg), nachdem die Tastatur da ist
    private func revealFocus(_ proxy: ScrollViewProxy, _ key: String?, delay: Double) {
        guard let key else { return }
        Task { @MainActor in
            try? await Task.sleep(for: .seconds(delay))
            guard ed.focus == key else { return }
            withAnimation(.easeOut(duration: 0.2)) { proxy.scrollTo(key, anchor: nil) }
        }
    }
    #endif
    private var sheetsPart: some View {
        scrollPart
        .photosPicker(isPresented: $ed.pickImage, selection: $ed.picked, maxSelectionCount: 5, matching: .images)
        .sheet(isPresented: $ed.pickImmich) { ImmichPicker(note: doc.uploadNote) { url in ed.insertImageURL(url) }.environmentObject(store) }
        .sheet(item: Binding(get: { ed.linkDBFor.map(MoveTarget.init(idString:)) }, set: { if $0 == nil { ed.linkDBFor = nil } })) { _ in
            NavigationStack {
                List(store.pages.filter { $0.isDatabase }) { p in
                    Button { if let b = ed.linkDBFor { ed.linkDatabase(p.id, into: b) } } label: {
                        HStack { PageIcon(icon: p.icon, store: store, fallback: "🗃️"); VStack(alignment: .leading) { Text(p.label); Text(store.path(p.id).map(\.label).joined(separator: " / ")).font(.caption).foregroundStyle(.secondary) } }
                    }.buttonStyle(.plain)
                }
                .navigationTitle("Datenbank verknüpfen")
                .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Abbrechen") { ed.linkDBFor = nil } } }
            }.macFrame(minWidth: 360, minHeight: 420)
        }
        .onChange(of: ed.openPage) { _, v in if let v { ed.openPage = nil; nav.push(PageRoute(id: v)) } }
        .modifier(PageEditorSheets(ed: ed, doc: doc))
        .sheet(isPresented: $showVersions) { VersionsSheet(id: id, restored: { Task { await doc.load(store) } }, canRestore: doc.access.role == .owner || !pageLocked).environmentObject(store) }
        .onChange(of: ed.picked) { _, items in
            Task { for it in items { if let d = try? await it.loadTransferable(type: Data.self) { await ed.insertImage(d) } }; ed.picked = [] }
        }
        // Cmd-V / Einsetzen mit Bild in der Zwischenablage -> Bildblock (wie "Bild einfuegen"); Text wird normal eingesetzt
        .imagePasteTarget(enabled: { [weak e = ed, weak d = doc] in e.map { !$0.locked } == true && d?.fetched == true && d?.isDatabase == false },
                          acceptBlocks: { [weak e = ed] p in e?.pasteBlocks(p) ?? false }) { [weak e = ed] imgs in
            Task { for i in imgs { await e?.insertImage(i.data) } }
        }
        .environmentObject(ed)
        .overlay { if !doc.loaded { ProgressView() } }
        #if os(iOS)
        .navigationTitle(scrolled && !doc.noAccess ? doc.title : "")
        .navigationBarTitleDisplayMode(.inline)
        #else
        .modifier(MacTitle(on: !inPeek, title: doc.noAccess ? String(localized: "Kein Zugriff") : doc.title,
                           subtitle: doc.noAccess ? "" : store.path(id).map(\.label).joined(separator: " / ")))
        #endif
        .sheet(isPresented: $moving) { MoveSheet(id: id) }
        .sheet(isPresented: $publishing) {
            PublishSheet(id: id, onChange: { s in for (k, v) in PublishLogic.pageFields(s) { doc.page[k] = v } },
                         openPage: { pid in nav.peek = nil; nav.push(PageRoute(id: pid)) })
                .environmentObject(store)
        }
    }
    private var pageBody: some View {
        sheetsPart
        .toolbar {
            if !inPeek && !doc.noAccess {                    // im Peek: eigene Kopfzeile statt Fensterleiste (sonst Layout-Kreislauf am Mac); Kein Zugriff: nichts
                ToolbarItem(placement: .primaryAction) { assistantButton }
                ToolbarItem(placement: .primaryAction) { commentsButton }
                ToolbarItem(placement: .primaryAction) { favButton }
                ToolbarItem(placement: .primaryAction) { Menu { pageMenuItems } label: { Image(systemName: "ellipsis.circle") }.accessibilityLabel("Seitenmenü") }
            }
            #if os(iOS)
            if editorKeyboard {                            // nur fuer Seitenbloecke - nicht im Kommentar-/Assistent-/Teilen-Blatt (deckte dort "Kommentieren" ab)
            ToolbarItemGroup(placement: .keyboard) {
                EditorKeyboardItems(ed: ed)                 // Leiste liegt ausserhalb von .environmentObject(ed): ausdruecklich uebergeben
                Spacer()
                Button("Fertig") { ed.focus = nil }.bold()
            }
            }
            #endif
        }
        .task {
            ed.store = store; await doc.load(store); store.visited(id)
            if let p = nav.pendingComments, p.id == id, doc.fetched { nav.pendingComments = nil; commentsTarget = CommentsTarget(block: p.block) }
            #if DEBUG
            if UserDefaults.standard.bool(forKey: "hkAssistantPage"), doc.fetched { try? await Task.sleep(for: .seconds(1)); launchAssistant(tab: 1, action: UserDefaults.standard.string(forKey: "hkAssistantAction")) }   // nur fuer Tests
            #endif
            #if DEBUG
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
            if UserDefaults.standard.bool(forKey: "hkFocusLast"), doc.fetched, !inPeek,     // nur fuer Tests: letzten Absatz bearbeiten (Einsetzen)
               let b = doc.root.compactMap({ $0 as? NSMutableDictionary }).last(where: { $0["type"] as? String == "paragraph" }) {
                try? await Task.sleep(for: .seconds(1)); ed.focus = b.blockID
            }
            if UserDefaults.standard.bool(forKey: "hkBlockCopyTest"), !inPeek { await BlockClipboard.debugTest(ed) }   // nur fuer Tests: Bildblock kopieren
            if UserDefaults.standard.bool(forKey: "hkPublish"), doc.fetched {             // nur fuer Tests: Dialog "Veroeffentlichen" oeffnen
                try? await Task.sleep(for: .seconds(1)); publishing = true
            }
            if UserDefaults.standard.bool(forKey: "hkShare"), doc.fetched, doc.access.allows(.share) {   // nur fuer Tests: Dialog "Teilen"
                try? await Task.sleep(for: .seconds(1)); sharing = true
            }
            if let c = UserDefaults.standard.string(forKey: "hkComments"), doc.fetched {   // nur fuer Tests: Kommentare ("all" oder Block-ID)
                try? await Task.sleep(for: .seconds(1)); commentsTarget = CommentsTarget(block: c == "all" ? nil : c)
            }
            let edAfter = UserDefaults.standard.integer(forKey: "hkEditAfter")               // nur fuer Tests: nach n s Text anhaengen + speichern (Konflikt)
            if edAfter > 0, doc.fetched {
                try? await Task.sleep(for: .seconds(edAfter))
                // Block erst jetzt suchen: die Seite kann inzwischen neu geladen sein (neuer Baum)
                if let b = doc.root.compactMap({ $0 as? NSMutableDictionary }).last(where: { $0["type"] as? String == "paragraph" }),
                   let d = b["data"] as? NSMutableDictionary {
                    d["text"] = (d["text"] as? String ?? "") + " " + (UserDefaults.standard.string(forKey: "hkEditText") ?? "+")
                    ed.changed(); await ed.flush()
                }
            }
            if let t = UserDefaults.standard.string(forKey: "hkFocusText"),                // nur fuer Tests: Absatz mit diesem Text bearbeiten
               let b = doc.root.compactMap({ $0 as? NSMutableDictionary }).first(where: { (($0["data"] as? NSDictionary)?["text"] as? String ?? "").contains(t) }) {
                try? await Task.sleep(for: .seconds(1)); ed.focus = b.blockID
            }
            if UserDefaults.standard.bool(forKey: "hkToShared"), doc.noAccess {            // nur fuer Tests: "Zu „Mit mir geteilt“"
                try? await Task.sleep(for: .seconds(3)); nav.toShared()
            }
            if UserDefaults.standard.bool(forKey: "hkFocusFirst"),                       // nur fuer Tests (Startparameter -hkFocusFirst YES)
               let b = doc.root.compactMap({ $0 as? NSMutableDictionary }).first(where: { ["paragraph", "header"].contains($0["type"] as? String ?? "") }) {
                ed.focus = b.blockID
            }
            #endif
        }
        .refreshable { await ed.flush(); await doc.load(store); store.refreshEvents() }
        .task(id: "presence-\(id)") { await presenceLoop() }
        .onReceive(store.live.events) { liveEvent($0) }
        .onChange(of: doc.updatedBy) { _, v in if v != nil { Task { try? await Task.sleep(for: .seconds(6)); doc.updatedBy = nil } } }
        .sheet(item: $assistant) { l in
            AssistantSheet(pageID: id, actions: assistantActions, initialTab: l.tab, initialAction: l.action,
                           openPage: { pid in nav.peek = nil; nav.push(PageRoute(id: pid)) })
                .environmentObject(store)
        }
        .modifier(SaveGuard(doc: doc, ed: ed))
        // gesperrt (Eigentuemer) oder Rolle Lesen/Kommentieren: schreibgeschuetzt, ohne Platzhalter und Griffe
        .onChange(of: pageLocked || doc.access.readOnly, initial: true) { _, v in ed.locked = v; if v { ed.focus = nil } }
        .onChange(of: doc.access, initial: true) { _, a in ed.applyAccess(a) }
        .onChange(of: ed.commentBlock) { _, b in if let b { ed.commentBlock = nil; Task { await ed.flush(); commentsTarget = CommentsTarget(block: b) } } }
        .modifier(CommentsPresenter(target: $commentsTarget, inPeek: inPeek, doc: doc, ed: ed, store: store))
        .modifier(PendingCommentsOpener(id: id, nav: nav, doc: doc, target: $commentsTarget))
        .sheet(isPresented: $sharing) {
            ShareSheet(id: id, ownerName: doc.access.owner?.name, onShared: { on in
                doc.access.shared = on; doc.page["shared"] = on; Task { await store.refresh(quiet: true) }
            }, openPage: { pid in nav.peek = nil; nav.push(PageRoute(id: pid)) })
            .environmentObject(store)
        }
        .onAppear { if doc.loaded && ed.focus == nil { Task { await ed.flush(); await doc.load(store) } } }   // zurueck aus dem Web-Editor
        .onDisappear { Task { await ed.flush(); await sendPresence(leave: true) } }
        .onChange(of: store.needsLogin) { _, v in                          // wieder angemeldet -> neu laden
            if !v && doc.fetched && doc.staleSession(store) { ed.focus = nil; Task { await doc.resetForNewSession(store) } }   // anderes Konto/neue Sitzung
            else if !v && !doc.fetched { Task { await doc.load(store) } }
        }
        .onChange(of: store.accountSwitches) { _, _ in                   // anderes Konto ohne Anmeldefenster dazwischen
            if doc.fetched && doc.staleSession(store) { ed.focus = nil; Task { await doc.resetForNewSession(store) } }
        }
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
        if ShareLogic.isOwnerOnlyBlock(type, foreign: ed.foreign) {
            OwnerOnlyBlock(type: type)                          // fremde Seite: Daten gehoeren dem, der schaut - neutraler Platzhalter
        } else {
            content
        }
    }

    @ViewBuilder private var content: some View {
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
                VStack(alignment: .leading, spacing: 4) {
                    EditText(key: id, html: s("text"), placeholder: String(localized: "Zitat"), setHTML: set("text"), onReturn: split("text")).id(id).italic()
                    if !s("caption").isEmpty {                               // Quelle (wie im Web), bleibt beim Bearbeiten erhalten
                        Text(verbatim: "— " + plain(s("caption"))).font(.footnote).foregroundStyle(.secondary)
                    }
                }
            }
        case "code":
            EditText(key: id, html: s("code"), font: .system(.footnote, design: .monospaced), placeholder: String(localized: "Code"), splitOnReturn: false, raw: true, setHTML: set("code")).id(id)
                .padding(10).padding(.top, s("language").isEmpty ? 0 : 8).background(.secondary.opacity(0.12), in: RoundedRectangle(cornerRadius: 8))
                .overlay(alignment: .topTrailing) {                      // Sprache aus dem Web (Bash, YAML ...) - bleibt beim Bearbeiten erhalten
                    if !s("language").isEmpty { Text(s("language")).font(.caption2.monospaced()).foregroundStyle(.secondary).padding(.horizontal, 8).padding(.top, 4) }
                }
        case "delimiter":                                                // waagerechte Linie (Divider stand in der Block-Zeile senkrecht)
            Rectangle().fill(Color.secondary.opacity(0.45)).frame(height: 1).frame(maxWidth: .infinity)
                .padding(.vertical, 12).accessibilityLabel(Text("Trenner"))
        case "table":
            EditableTable(data: data, blockID: id).id(id)
        case "image":
            if let url = (data["file"] as? NSDictionary)?["url"] as? String {
                VStack(alignment: .leading, spacing: 4) {
                    RemoteImage(path: url, w: 2000).frame(maxWidth: .infinity, maxHeight: 900).clipShape(RoundedRectangle(cornerRadius: 8))   // ganzes Bild wie im Web
                        .overlay { RoundedRectangle(cornerRadius: 8).strokeBorder(Color.accentColor, lineWidth: 3).opacity(ed.selected == id ? 1 : 0) }
                        .background(BlockClipResponder(id: id, active: ed.selected == id, ed: ed))   // Cmd-C/X, Entf, Menue (2.1-c)
                        .contentShape(Rectangle())
                        .onTapGesture { ed.select(ed.selected == id ? nil : id) }
                        .accessibilityAddTraits(ed.selected == id ? [.isImage, .isSelected] : .isImage)
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
                if let fu { Link(destination: fu) { Image(systemName: "arrow.down.circle") }.help("Öffnen / herunterladen").accessibilityLabel("Öffnen / herunterladen") }
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
                DatabaseView(dbID: db, viewID: data["view"] as? String, localViews: data["views"] as? [[String: Any]], embedded: true, pageLocked: ed.locked) { vs in
                    data["views"] = vs; ed.changed()                // eigene Ansichten des Blocks in der Seite speichern
                }
            }
        case "tasks":
            TaskListView(compact: true).padding(12).background(.secondary.opacity(0.08), in: RoundedRectangle(cornerRadius: 10))
        case "calendar":
            EventList(days: 14, compact: true).padding(12).background(.secondary.opacity(0.08), in: RoundedRectangle(cornerRadius: 10))
        case "buttons":
            HStack(spacing: 6) {
                ButtonsBlock(buttons: data["buttons"] as? [[String: Any]] ?? [], readOnlyRole: ed.doc.access.readOnly)
                if !ed.locked { Button { ed.configButtons = id } label: { Image(systemName: "gearshape").foregroundStyle(.tertiary) }.buttonStyle(.plain).help("Schaltflächen einrichten").accessibilityLabel("Schaltflächen einrichten") }
            }
        case "embed":
            EmbedCard(url: decodeEntities(s("url")), title: decodeEntities(s("title")))
        case "nextcloud":                                            // 2.3: Datei/Ordner aus Nextcloud (Block bleibt unveraendert)
            NextcloudCard(data: data, note: ed.doc.id, remotePage: FedLogic.isRemote(ed.doc.id))
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
                            } label: { CheckBoxIcon(on: checked) }
                            .buttonStyle(.plain)
                            .disabled(ed.locked)                               // nur Lesen/Kommentieren oder gesperrt: nicht abhaken, sichtbar gedimmt
                            .accessibilityLabel("Abhaken").accessibilityAddTraits(checked ? .isSelected : [])
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
            // Erst den neuen Absatz fokussieren, den leeren Punkt erst danach entfernen: verschwand das fokussierte Feld
            // sofort, ging die Tastatur zu und weiter Getipptes verloren (N2)
            let editor = ed, list = items, item = items[i], lone = path.isEmpty && items.count == 1, bid = blockID
            let p = PageEditor.make("paragraph")
            editor.insert(p, after: bid)
            editor.whenFocused(p.blockID) {
                if lone {
                    if let (arr, j) = editor.locate(bid) { arr.removeObject(at: j) }   // einziger Punkt: ganze Liste weg (wie bisher)
                } else {
                    let k = list.indexOfObjectIdentical(to: item)
                    if k != NSNotFound { list.removeObject(at: k) }
                }
                editor.changed()
            }
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
    /// Nur Lesen/Kommentieren: Schaltflaechen nicht ausloesbar. Gesperrte Seite: bedienbar (wie im Web)
    var readOnlyRole = false
    @EnvironmentObject var store: Store
    @EnvironmentObject var nav: NavModel
    @State private var gate = ButtonLogic.Gate()
    @State private var problem: String?
    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(Array(buttons.enumerated()), id: \.offset) { i, b in
                        let key = String(i), busy = gate.busy(key)
                        Button { start(key, b) } label: {
                            HStack(spacing: 6) {
                                if busy { ProgressView().controlSize(.small) }          // sofort sichtbar: Aktion laeuft
                                else if let ic = b["icon"] as? String, !ic.isEmpty { Text(ic) }
                                else { Image(systemName: "cursorarrow.click").foregroundStyle(.secondary) }
                                Text(b["label"] as? String ?? "").foregroundStyle(.primary)
                            }
                            .font(.subheadline.weight(.medium)).padding(.horizontal, 10).padding(.vertical, 6)
                            .background(.secondary.opacity(0.12), in: RoundedRectangle(cornerRadius: 7))
                            .opacity(busy ? 0.7 : 1)
                        }
                        .buttonStyle(.plain)
                        .disabled(busy || !ButtonLogic.enabled(readOnlyRole: readOnlyRole))
                    }
                }
            }
            if let problem { Text(problem).font(.caption).foregroundStyle(.orange) }
        }
    }
    /// Doppeltes Tippen waehrend der Anfrage legt nichts doppelt an (Knopf gesperrt, Tor je Knopf)
    private func start(_ key: String, _ b: [String: Any]) {
        guard gate.begin(key) else { return }
        problem = nil
        Task {
            await run(b)
            try? await Task.sleep(for: .milliseconds(800))      // waehrend die neue Seite hereinfaehrt: kein zweites Mal
            gate.end(key)
        }
    }
    private func run(_ b: [String: Any]) async {
        let target = b["target"] as? Int
        switch b["action"] as? String {
        case "page": if let t = target { nav.open(t, mode: b["open"] as? String ?? "") }
        case "db":
            guard let t = target else { return }
            let title = ButtonLogic.title(b["title"] as? String ?? "", today: Date.now.sagaFormatted(date: .numeric, time: .omitted))
            struct R: Decodable { let id: Int; let updated: Double? }
            let tpl = b["tpl"] as? Int
            var body: [String: Any] = ["parent_id": t]; if !title.isEmpty || tpl == nil { body["title"] = title }
            do {
                let d = try await store.api(tpl.map { "api/notes/\($0)/duplicate" } ?? "api/notes", method: "POST", body: body)   // Vorlage: alles uebernehmen
                let r = try JSONDecoder().decode(R.self, from: d)
                // Gleich oeffnen (wie im Web) - die Seitenliste laedt still dahinter. Vorher wartete die App auf das
                // komplette Neuladen (Seiten, Freigaben, Heute, Konto ...): bei langsamer Verbindung sekundenlang
                // keine Reaktion, und weiteres Tippen legte weitere Eintraege an.
                store.dataChanged()
                if title.isEmpty { nav.focusTitle = r.id }
                nav.open(r.id, mode: b["open"] as? String ?? "")
                if tpl == nil {                                     // neuer leerer Eintrag: lokal in die Seitenliste statt alles neu laden
                    let p = Page.created(id: r.id, parent: t, title: title.trimmingCharacters(in: .whitespacesAndNewlines), updated: r.updated ?? Date().timeIntervalSince1970)
                    Task { await store.insertCreated(p) }
                } else {                                            // Vorlage (Inhalt/Symbol/Unterseiten kopiert): wie bisher neu laden
                    Task { await store.refresh(quiet: true); store.dataChanged() }
                }
            } catch {
                problem = String(localized: "Eintrag nicht angelegt: \(error.localizedDescription)")
            }
        case "task":
            do {
                _ = try await store.api("api/tasks", method: "POST", body: ["text": (b["title"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? String(localized: "Neue Aufgabe")])
                await store.loadToday()
            } catch { problem = error.localizedDescription }
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
        let n = ed.commentCounts[id] ?? 0
        HStack(alignment: .top, spacing: 4) {
            inner
            if n > 0 { CommentBubble(count: n, unread: ed.commentUnread.contains(id)) { ed.commentBlock = id } }   // nur offene Unterhaltungen
        }
        .id(id)                                                        // Ziel fuer das Inhaltsverzeichnis
    }
    @ViewBuilder private var inner: some View {
        #if os(macOS)
        // Griffe zeichnet die SEITE (BlockHandles) - hier nur melden: Maus drueber + Position
        BlockView(block: block)
            .contentShape(Rectangle())
            .onHover { inside in ed.hover.hover(id, depth: depth, inside: inside) }
            // transform: eigene Position ERGAENZEN (anchorPreference wuerde die der Bloecke darin ueberschreiben,
            // dann kennt die Seite Bloecke in Spalten nicht und zeichnet dort keine Griffe)
            .transformAnchorPreference(key: BlockFramesKey.self, value: .bounds) { frames, a in frames[id] = a }
            .contextMenu { if ed.locked { BlockMenuItems(id: id) } }   // schreibgeschuetzt (keine Griffe): Kommentieren, Link
        #else
        BlockView(block: block)
            .contentShape(Rectangle())
            .contextMenu { BlockMenuItems(id: id) }
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
                    Button { ed.insert(PageEditor.make("paragraph"), after: id) } label: { Image(systemName: "plus").frame(width: 22, height: 22).contentShape(Rectangle()) }.accessibilityLabel("Block einfügen")
                        .buttonStyle(.plain).help("Klicken: Block darunter einfügen")
                    BlockMenu(id: id) { Image(systemName: "line.3.horizontal").frame(width: 20, height: 22).contentShape(Rectangle()) }.help("Block-Menü").accessibilityLabel("Block-Menü")
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
    private var isImage: Bool {
        guard let (arr, i) = ed.locate(id), let b = arr[i] as? NSDictionary else { return false }
        return (b["type"] as? String) == "image" && ((b["data"] as? NSDictionary)?["file"] as? NSDictionary)?["url"] is String
    }
    var body: some View {
        if ed.canComment {
            Button { ed.commentBlock = id } label: { Label("Kommentieren", systemImage: "bubble.left") }
        }
        if isImage {                                                    // Bildblock (2.1-c): Bild + Block in die Zwischenablage
            Button { ed.copyBlock(id, cut: false) } label: { Label("Kopieren", systemImage: "doc.on.doc") }
            if !ed.locked { Button { ed.copyBlock(id, cut: true) } label: { Label("Ausschneiden", systemImage: "scissors") } }
        }
        if ed.locked {                                                  // schreibgeschuetzt: nur Link (und Kommentieren)
            Button { copyText(AppConfig.server.absoluteString.trimmingCharacters(in: CharacterSet(charactersIn: "/")) + "/notes#\(ed.doc.id):\(id)") } label: { Label("Link zum Block kopieren", systemImage: "link") }
        } else {
            editItems
        }
    }
    /// Umwandeln nur fuer Textbloecke (Bild -> Text loeschte das Bild); verlustbehaftet nur nach Rueckfrage
    private var convertMode: BlockConvert.Mode {
        guard let (arr, i) = ed.locate(id), let b = arr[i] as? NSDictionary else { return .no }
        return BlockConvert.mode(type: b["type"] as? String ?? "", data: b["data"] as? [String: Any] ?? [:])
    }
    @ViewBuilder private var editItems: some View {
        let mode = convertMode
        if mode != .no {
        Menu {
            ForEach(slashItems.filter { !["image", "immich_image", "page", "db_new", "db_link", "tasks", "calendar", "table", "columns", "buttons", "embed", "file", "nextcloud", "delimiter"].contains($0.1) }, id: \.0) { it in
                Button {
                    let level = it.2 == 0 ? 2 : it.2
                    if mode == .confirm { ed.askConvert = PageEditor.ConvertAsk(id: id, type: it.1, level: level) } else { ed.convert(id, to: it.1, level: level) }
                } label: { Label(it.0, systemImage: it.3) }
            }
        } label: { Label("Umwandeln in", systemImage: "arrow.triangle.2.circlepath") }
        }
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
            .help("Neuer Block am Ende dieser Spalte").accessibilityLabel("Neuer Block am Ende dieser Spalte")
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


/// Lokaler Entwurf gefunden (ungespeicherte Aenderungen oder Konfliktkopie): wiederherstellen oder verwerfen
struct DraftBanner: View {
    let draft: PageDraft
    @ObservedObject var doc: PageDoc
    @ObservedObject var ed: PageEditor
    @EnvironmentObject var store: Store
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Label("Nicht gespeicherte Änderungen vom \(draft.saved.sagaFormatted(date: .abbreviated, time: .shortened))", systemImage: "doc.badge.clock")
                .font(.subheadline.weight(.semibold))
            Text("Wiederherstellen ersetzt den angezeigten Stand und speichert deine Fassung.").font(.footnote).foregroundStyle(.secondary)
            HStack {
                Button("Wiederherstellen") { ed.focus = nil; Task { await doc.restoreDraft(store); ed.changed() } }.prominentGold()
                Button("Verwerfen", role: .destructive) { doc.discardDraft() }.buttonStyle(.bordered)
            }.controlSize(.small)
        }
        .padding(12).frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.orange.opacity(0.12), in: RoundedRectangle(cornerRadius: 10))
    }
}

/// Konflikt-Rueckfrage (409), Zugriff verloren und Speichern beim Wechsel in den Hintergrund
struct SaveGuard: ViewModifier {
    @ObservedObject var doc: PageDoc
    let ed: PageEditor
    @EnvironmentObject var store: Store
    @Environment(\.scenePhase) private var phase
    @State private var askOverwrite: String?
    func body(content: Content) -> some View {
        content
            .alert(doc.conflictBy.map { String(localized: "\($0) hat diese Seite gerade geändert") } ?? String(localized: "Seite wurde woanders geändert"),
                   isPresented: $doc.askConflict) {
                if let by = doc.conflictBy {
                    // andere Person (1.3): Hauptknopf "Neue Version laden" (eigener Text danach zum Kopieren); ueberschreiben nur mit Rueckfrage
                    #if os(macOS)
                    Button("Neue Version laden") { ed.focus = nil; Task { await doc.loadNewVersion(store) } }.keyboardShortcut(.defaultAction)
                    #else
                    Button("Neue Version laden") { ed.focus = nil; Task { await doc.loadNewVersion(store) } }   // iOS: kein Standardknopf (dunkel weiss auf Gold 2,2:1)
                    #endif
                    Button("Meine Version behalten") { askOverwrite = by }
                } else {
                    Button("Meine Fassung behalten") { Task { await doc.keepMine(store) } }
                    Button("Server-Fassung laden (meine als Kopie sichern)") { ed.focus = nil; Task { await doc.takeServer(store) } }
                }
            } message: {
                if doc.conflictBy != nil { Text("Deine letzten Änderungen sind noch nicht gespeichert.") }
                else { Text("Diese Seite wurde inzwischen auf einem anderen Gerät geändert. Deine Änderungen gehen nicht verloren.") }
            }
            .alert(askOverwrite.map { String(localized: "Änderungen von \($0) überschreiben?") } ?? "",
                   isPresented: Binding(get: { askOverwrite != nil }, set: { if !$0 { askOverwrite = nil } })) {
                Button("Überschreiben", role: .destructive) { askOverwrite = nil; Task { await doc.keepMine(store) } }
                Button("Abbrechen", role: .cancel) { askOverwrite = nil; doc.askConflict = true }
            } message: {
                Text(verbatim: askOverwrite.map { String(localized: "Die Fassung von \($0) bleibt in den Versionen erhalten.") } ?? "")
            }
            .sheet(item: $doc.copyDialog, onDismiss: { Task { await doc.afterLostAccess(store) } }) { d in CopyTextSheet(dialog: d) }
            .onChange(of: phase) { _, p in if p != .active { flushInBackground() } }   // App geht in den Hintergrund: Eingaben sichern
    }
    /// Speichern, auch wenn die App gerade in den Hintergrund geht (iOS gibt dafuer etwas Zeit)
    private func flushInBackground() {
        #if os(iOS)
        let bg = UIApplication.shared.beginBackgroundTask(withName: "save-page")
        Task { await ed.flush(); if bg != .invalid { UIApplication.shared.endBackgroundTask(bg) } }
        #else
        Task { await ed.flush() }
        #endif
    }
}


/// Benachrichtigung/Mitteilung zu einem Kommentar, waehrend die Seite schon offen ist: Kommentare dieses Blocks zeigen
/// (der .task der Seite laeuft dann nicht erneut, BUG-059). Kurz warten, bis das Glocken-Blatt geschlossen ist.
struct PendingCommentsOpener: ViewModifier {
    let id: Int
    @ObservedObject var nav: NavModel
    @ObservedObject var doc: PageDoc
    @Binding var target: CommentsTarget?
    func body(content: Content) -> some View {
        content.onChange(of: nav.commentsJump) { _, _ in
            guard let p = nav.pendingComments, p.id == id, doc.fetched else { return }
            nav.pendingComments = nil
            Task { try? await Task.sleep(for: .milliseconds(500)); target = CommentsTarget(block: p.block) }
        }
    }
}

/// Kommentare zeigen: iPhone als Blatt von unten (halbe/ganze Hoehe), iPad und Mac als Blatt.
/// (Kein Inspector: der nahm auf dem iPhone der Seite die Knoepfe der Navigationsleiste.)
struct CommentsPresenter: ViewModifier {
    @Binding var target: CommentsTarget?
    let inPeek: Bool
    @ObservedObject var doc: PageDoc
    let ed: PageEditor
    let store: Store
    #if os(iOS)
    @Environment(\.dynamicTypeSize) private var typeSize
    /// iPad und grosse Schrift: gleich ganze Hoehe - halbhoch zeigte dort kaum einen Kommentar (BUG-064)
    private var detents: Set<PresentationDetent> {
        UIDevice.current.userInterfaceIdiom == .pad || typeSize.isAccessibilitySize ? [.large] : [.medium, .large]
    }
    #endif
    func body(content: Content) -> some View {
        #if os(iOS)
        content.sheet(item: $target) { _ in
            panel.presentationDetents(detents).presentationContentInteraction(.scrolls)
                .presentationBackground(.background)              // deckend (iPad: links schien die Seitenleiste durch)
        }
        #else
        content.sheet(item: $target) { _ in panel.frame(minWidth: 420, idealWidth: 460, minHeight: 520, idealHeight: 640) }
        #endif
    }
    private var panel: some View {
        CommentsPanel(noteID: doc.id, block: Binding(get: { target?.block }, set: { b in if target != nil { target = CommentsTarget(block: b) } }),
                      role: doc.access.role, snippet: { blockSnippet($0) },
                      onUpdate: { s in apply(s) }, close: { target = nil })
            .environmentObject(store)
    }
    /// Zahlen an Block und Kopfzeile nachfuehren
    private func apply(_ s: CommentsState) {
        let (counts, unread) = ShareLogic.blockCounts(s.threads)
        var a = doc.access
        a.commentCounts = counts; a.commentsOpen = s.open.count; a.commentsUnread = s.open.filter(\.unread).count; a.commentsResolved = s.resolvedCount
        if a != doc.access { doc.access = a }
        if ed.commentUnread != unread { ed.commentUnread = unread }
    }
    /// Kurzer Text des Blocks (nil: Block gibt es nicht mehr)
    private func blockSnippet(_ id: String) -> String? {
        guard let (arr, i) = ed.locate(id), let b = arr[i] as? NSDictionary else { return nil }
        var s = ShareLogic.plainText(title: "", blocks: [b], strip: plain).replacingOccurrences(of: "\n", with: " ")
        if s.isEmpty { s = (b["type"] as? String) == "image" ? String(localized: "Bild") : String(localized: "Block") }
        return s.count > 70 ? String(s.prefix(69)) + "…" : s
    }
}
