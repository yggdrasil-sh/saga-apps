import SwiftUI
import WidgetKit
import AppIntents
import ImageIO

// Widget "Seite" wie bei Notion: Titelbild oben, Symbol auf der Kante, Titel darunter - Tippen oeffnet die Seite.

struct PageCardEntity: AppEntity {
    static var typeDisplayRepresentation: TypeDisplayRepresentation = "Seite"
    static var defaultQuery = PageCardQuery()
    let id: Int
    let name: String
    let icon: String?
    var displayRepresentation: DisplayRepresentation { DisplayRepresentation(title: "\(icon.map { $0 + " " } ?? "")\(name)") }
}

struct PageCardQuery: EntityQuery, EntityStringQuery {
    private static func load(rows: Bool) async throws -> [PageCardEntity] {
        let pages = try await API.pages()
        let dbIDs = Set(pages.filter(\.isDatabase).map(\.id))
        return pages.filter { rows || $0.parent_id.map { !dbIDs.contains($0) } ?? true }
            .map { PageCardEntity(id: $0.id, name: $0.title.isEmpty ? String(localized: "Unbenannt") : $0.title, icon: $0.emoji ?? ($0.isDatabase ? "🗂" : "📄")) }
    }
    func entities(for identifiers: [Int]) async throws -> [PageCardEntity] {
        let all = try await Self.load(rows: true); return identifiers.compactMap { id in all.first { $0.id == id } }
    }
    func entities(matching string: String) async throws -> [PageCardEntity] {      // Suche findet auch Datenbank-Eintraege
        try await Self.load(rows: true).filter { $0.name.localizedCaseInsensitiveContains(string) }
    }
    func suggestedEntities() async throws -> [PageCardEntity] { try await Self.load(rows: false) }
}

struct PageCardIntent: WidgetConfigurationIntent {
    static var title: LocalizedStringResource = "Seite"
    static var description = IntentDescription("Eine Seite mit Titelbild und Symbol – Tippen öffnet sie.")
    @Parameter(title: "Seite") var page: PageCardEntity?
    init() {}
}

struct PageCardEntry: TimelineEntry {
    var date: Date
    var id: Int? = nil
    var title = String(localized: "Seite wählen")
    var emoji: String? = nil
    var cover: CGImage? = nil
    var coverPos: Double = 50
    var iconImage: CGImage? = nil
    var hint: String? = nil
    /// 2185: "Stand: 14:05", wenn das Gezeigte nicht frisch ist
    var stamp: String? = nil
}

/// Letzter guter Stand der Seiten-Karte (Bilder als Dateien in der App-Gruppe, siehe PageCardStore)
/// 2188: coverV/iconRef = Versionsschluessel der gemerkten Bilder (aeltere Staende: nil -> einmal neu laden)
struct PageCardSnap: Codable { var title: String; var emoji: String?; var coverPos: Double; var cover: Bool; var icon: Bool
    var coverV: String? = nil; var iconRef: String? = nil }

/// Bilder des letzten guten Stands (Titelbild/Symbol, wie vom Server geliefert) - Library/Caches der App-Gruppe
enum PageCardStore {
    private static var dir: URL? { WidgetFeed.cardsDir(group: AppConfig.appGroup) }
    static func read(_ key: String?, _ which: String) -> Data? {
        guard let key, let d = dir else { return nil }
        return try? Data(contentsOf: d.appendingPathComponent(WidgetFeed.imageFile(key: key, which: which)))
    }
    static func write(_ data: Data?, _ key: String?, _ which: String) {
        guard let key, let d = dir else { return }
        let f = d.appendingPathComponent(WidgetFeed.imageFile(key: key, which: which))
        guard let data else { try? FileManager.default.removeItem(at: f); return }
        try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        try? data.write(to: f, options: .atomic)
    }
}

/// Bild auf Widget-Groesse verkleinern (Widgets haben wenig Speicher; GIF -> erstes Bild)
func thumb(_ d: Data?, max: Int) -> CGImage? {
    guard let d, let src = CGImageSourceCreateWithData(d as CFData, nil) else { return nil }
    let o: [CFString: Any] = [kCGImageSourceCreateThumbnailFromImageAlways: true, kCGImageSourceThumbnailMaxPixelSize: max,
                              kCGImageSourceCreateThumbnailWithTransform: true, kCGImageSourceShouldCacheImmediately: true]
    return CGImageSourceCreateThumbnailAtIndex(src, 0, o as CFDictionary)
}

struct PageCardProvider: AppIntentTimelineProvider {
    func placeholder(in context: Context) -> PageCardEntry { PageCardEntry(date: .now, title: String(localized: "Haupt Dashboard"), emoji: "🏠") }
    func snapshot(for configuration: PageCardIntent, in context: Context) async -> PageCardEntry {
        configuration.page == nil ? placeholder(in: context) : await load(configuration.page)
    }
    func timeline(for configuration: PageCardIntent, in context: Context) async -> Timeline<PageCardEntry> {
        // 2185: 15 min statt 2 h (die App stoesst zusaetzlich an); spaeterer Eintrag mit "Stand", falls nicht neu geladen wird
        let now = Date()
        let (e, ok, snapAt) = await fetch(configuration.page)
        let entries = WidgetFeed.entryPlan(ok: ok, hasCached: snapAt != nil, fetchedAt: snapAt, now: now).map { p -> PageCardEntry in
            var x = e; x.date = p.date
            if p.stamp, let at = ok ? now : snapAt { x.stamp = WidgetFeed.stamp(at, now: p.date) }
            return x
        }
        return Timeline(entries: entries, policy: .after(WidgetFeed.nextReload(ok: ok, now: now)))
    }
    private func load(_ sel: PageCardEntity?) async -> PageCardEntry {
        let (e, ok, snapAt) = await fetch(sel)
        var x = e
        if !ok, let at = snapAt { x.stamp = WidgetFeed.stamp(at, now: .now) }
        return x
    }
    private static func snapKey(_ id: Int) -> String? {
        WidgetFeed.snapshotKey(widget: "card", target: String(id), server: AppConfig.serverOrigin ?? "", token: AppConfig.token)
    }
    /// -> (Eintrag, frisch geladen?, Zeitpunkt des gemerkten Stands bei Fehler)
    private func fetch(_ sel: PageCardEntity?) async -> (PageCardEntry, Bool, Date?) {
        var e = PageCardEntry(date: .now)
        guard let sel else { e.hint = String(localized: "Gedrückt halten → Widget bearbeiten"); return (e, true, nil) }
        e.id = sel.id; e.title = sel.name; e.emoji = sel.icon
        let key = Self.snapKey(sel.id)
        WidgetPushLogic.record(kind: "pagecard", target: String(sel.id), server: AppConfig.serverOrigin ?? "", token: AppConfig.token,
                               to: AppConfig.store)                              // 2188: Meldung an den Server (Widget-Push)
        let snap = WidgetSnapshot<PageCardSnap>.load(key, from: AppConfig.store)
        // 2188: nur diese Seite holen (GET /api/app/page/{id}; aeltere Server: aus der Liste)
        guard let res = try? await API.page(sel.id) else {
            // offline/Fehler: letzter guter Stand mit Bildern und "Stand: ...", sonst Namen aus der Auswahl
            guard let s = snap else { return (e, false, nil) }
            e.title = s.value.title; e.emoji = s.value.emoji; e.coverPos = s.value.coverPos
            e.cover = s.value.cover ? thumb(PageCardStore.read(key, "cover"), max: 900) : nil
            e.iconImage = s.value.icon ? thumb(PageCardStore.read(key, "icon"), max: 120) : nil
            return (e, false, s.at)
        }
        guard case .page(let p) = res else { e.hint = String(localized: "Seite gelöscht"); return (e, true, nil) }
        e.title = p.title.isEmpty ? String(localized: "Unbenannt") : p.title
        e.emoji = p.emoji ?? (p.imageIcon ? nil : (p.isDatabase ? "🗂" : "📄"))
        e.coverPos = p.cover_pos ?? 50
        // 2188: Bilder nach Versionsschluessel - gleicher Schluessel und Bild gemerkt: nicht neu laden; sonst unter neuer
        // Adresse. Aeltere Server (ohne cover_v): wie 2185 immer frisch. Netzfehler -> gemerktes Bild statt keins
        let cachedCover = PageCardStore.read(key, "cover"), cachedIcon = PageCardStore.read(key, "icon")
        let coverPlan = WidgetFeed.imagePlan(version: p.cover_v, cached: snap?.value.coverV, hasData: cachedCover != nil)
        let iconPlan: WidgetFeed.ImagePlan = p.imageIcon
            ? WidgetFeed.imagePlan(version: p.cover_v == nil ? nil : p.icon, cached: snap?.value.iconRef, hasData: cachedIcon != nil) : .none
        func load(_ plan: WidgetFeed.ImagePlan, _ which: String, _ cached: Data?) async -> WidgetImage {
            switch plan {
            case .none: return .none
            case .reuse: return cached.map { .image($0) } ?? .none
            case .fetch(let v): return await API.pageImageResult(p.id, which, version: v)   // ohne Titelbild: erstes Bild im Inhalt
            }
        }
        async let cov = load(coverPlan, "cover", cachedCover)
        async let ico = load(iconPlan, "icon", cachedIcon)                                  // 2.5: Siegel als PNG
        let (rc, ri) = (await cov, await ico)
        let cd = rc.resolved(cached: cachedCover), icd = ri.resolved(cached: cachedIcon)
        e.cover = thumb(cd, max: 900)
        e.iconImage = thumb(icd, max: 120)
        if rc != .failed && coverPlan != .reuse { PageCardStore.write(cd, key, "cover") }
        if ri != .failed && iconPlan != .reuse { PageCardStore.write(icd, key, "icon") }
        // Bild nicht abrufbar: gemerktes Bild mit dem Stand, aus dem es stammt
        let ok = rc != .failed && ri != .failed
        let prev = ok ? nil : snap?.at
        WidgetSnapshot.save(PageCardSnap(title: e.title, emoji: e.emoji, coverPos: e.coverPos, cover: cd != nil, icon: icd != nil,
                                         coverV: rc == .failed ? snap?.value.coverV : p.cover_v,
                                         iconRef: ri == .failed ? snap?.value.iconRef : (p.imageIcon ? p.icon : nil)),
                            at: prev ?? .now, key: key, to: AppConfig.store)
        return (e, ok, prev)
    }
}

struct PageCardView: View {
    @Environment(\.widgetFamily) private var family
    let entry: PageCardEntry
    private var small: Bool { family == .systemSmall }
    private var iconSize: CGFloat { small ? 38 : 40 }

    var body: some View {
        GeometryReader { g in
            let coverH = (g.size.height * (small ? 0.48 : 0.52)).rounded()
            ZStack(alignment: .topLeading) {
                coverView(width: g.size.width, height: coverH)
                // wie Notion: Symbol auf der Kante, Titel direkt darunter (oben buendig, Rest bleibt frei)
                VStack(alignment: .leading, spacing: 4) {
                    icon
                    Text(entry.title).font(.system(size: small ? 17 : 19, weight: .bold))
                        .foregroundStyle(.white).lineLimit(2).minimumScaleFactor(0.8)
                        .fixedSize(horizontal: false, vertical: true)
                    if let h = entry.hint { Text(h).font(.caption2).foregroundStyle(.secondary).lineLimit(2) }
                    if let st = entry.stamp {                  // 2185: nicht frisch -> sichtbar mit Zeitpunkt
                        Label(st, systemImage: "clock.arrow.circlepath").font(.caption2).foregroundStyle(.secondary).lineLimit(1)
                    }
                }
                .padding(.horizontal, 14)
                .padding(.top, coverH - (hasIcon ? iconSize / 2 : -10))
                .frame(width: g.size.width, alignment: .leading)
            }
            .frame(width: g.size.width, height: g.size.height, alignment: .topLeading)
        }
        .containerBackground(for: .widget) { Color(red: 0.17, green: 0.17, blue: 0.18) }
        .environment(\.colorScheme, .dark)
        .widgetURL(entry.id.map(WLink.page) ?? WLink.today)
    }
    private var hasIcon: Bool { entry.iconImage != nil || entry.emoji != nil }

    /// Titelbild wie im Web: fuellen, senkrechte Position aus cover_pos (0 = oben, 100 = unten)
    @ViewBuilder private func coverView(width W: CGFloat, height H: CGFloat) -> some View {
        if let c = entry.cover {
            let w = CGFloat(c.width), h = CGFloat(c.height), s = max(W / w, H / h)
            let dw = w * s, dh = h * s
            Image(decorative: c, scale: 1).resizable()
                .frame(width: dw, height: dh)
                .offset(x: -(dw - W) / 2, y: -(dh - H) * CGFloat(entry.coverPos / 100))
                .frame(width: W, height: H, alignment: .topLeading).clipped()
        } else {
            LinearGradient(colors: [Color(white: 0.30), Color(white: 0.22)], startPoint: .topLeading, endPoint: .bottomTrailing)
                .frame(width: W, height: H)
        }
    }

    @ViewBuilder private var icon: some View {
        if let i = entry.iconImage {
            Image(decorative: i, scale: 1).resizable().scaledToFill()
                .frame(width: iconSize, height: iconSize).clipShape(RoundedRectangle(cornerRadius: 6))
                .shadow(color: .black.opacity(0.35), radius: 2, y: 1)
        } else if let e = entry.emoji {
            Text(e).font(.system(size: iconSize * 0.82)).frame(width: iconSize, height: iconSize, alignment: .leading)
                .shadow(color: .black.opacity(0.35), radius: 2, y: 1)
        }
    }
}

struct PageCardWidget: Widget {
    var body: some WidgetConfiguration {
        AppIntentConfiguration(kind: "pagecard", intent: PageCardIntent.self, provider: PageCardProvider()) { PageCardView(entry: $0) }
            .configurationDisplayName("Seite")
            .description("Eine Seite mit Titelbild und Symbol – wie bei Notion. Tippen öffnet sie.")
            .supportedFamilies([.systemSmall, .systemMedium])
            .contentMarginsDisabled()                    // Titelbild bis an den Rand
    }
}
