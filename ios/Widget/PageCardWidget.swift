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
            .map { PageCardEntity(id: $0.id, name: $0.title.isEmpty ? "Unbenannt" : $0.title, icon: $0.emoji ?? ($0.isDatabase ? "🗂" : "📄")) }
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
    let date: Date
    var id: Int? = nil
    var title = "Seite wählen"
    var emoji: String? = nil
    var cover: CGImage? = nil
    var coverPos: Double = 50
    var iconImage: CGImage? = nil
    var hint: String? = nil
}

/// Bild auf Widget-Groesse verkleinern (Widgets haben wenig Speicher; GIF -> erstes Bild)
func thumb(_ d: Data?, max: Int) -> CGImage? {
    guard let d, let src = CGImageSourceCreateWithData(d as CFData, nil) else { return nil }
    let o: [CFString: Any] = [kCGImageSourceCreateThumbnailFromImageAlways: true, kCGImageSourceThumbnailMaxPixelSize: max,
                              kCGImageSourceCreateThumbnailWithTransform: true, kCGImageSourceShouldCacheImmediately: true]
    return CGImageSourceCreateThumbnailAtIndex(src, 0, o as CFDictionary)
}

struct PageCardProvider: AppIntentTimelineProvider {
    func placeholder(in context: Context) -> PageCardEntry { PageCardEntry(date: .now, title: "Haupt Dashboard", emoji: "🏠") }
    func snapshot(for configuration: PageCardIntent, in context: Context) async -> PageCardEntry {
        configuration.page == nil ? placeholder(in: context) : await load(configuration.page)
    }
    func timeline(for configuration: PageCardIntent, in context: Context) async -> Timeline<PageCardEntry> {
        Timeline(entries: [await load(configuration.page)], policy: .after(.now.addingTimeInterval(2 * 3600)))   // die App stoesst zusaetzlich an
    }
    private func load(_ sel: PageCardEntity?) async -> PageCardEntry {
        var e = PageCardEntry(date: .now)
        guard let sel else { e.hint = "Gedrückt halten → Widget bearbeiten"; return e }
        e.id = sel.id; e.title = sel.name; e.emoji = sel.icon
        guard let pages = try? await API.pages() else { return e }                // offline: Namen aus der Auswahl zeigen
        guard let p = pages.first(where: { $0.id == sel.id }) else { e.hint = "Seite gelöscht"; return e }
        e.title = p.title.isEmpty ? "Unbenannt" : p.title
        e.emoji = p.emoji ?? (p.icon.hasPrefix("/files/") ? nil : (p.isDatabase ? "🗂" : "📄"))
        e.coverPos = p.cover_pos ?? 50
        async let cov = API.pageImage(p.id, "cover")                                      // ohne Titelbild: erstes Bild im Inhalt
        async let ico = p.icon.hasPrefix("/files/") ? API.pageImage(p.id, "icon") : nil
        e.cover = thumb(await cov, max: 900)
        e.iconImage = thumb(await ico, max: 120)
        return e
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
