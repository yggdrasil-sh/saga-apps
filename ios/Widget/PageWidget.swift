import SwiftUI
import WidgetKit
import AppIntents

/// Eine Zeile im Widget: Datenbank-Eintrag oder Unterseite
struct PItem: Hashable {
    let id: Int; let title: String; let emoji: String
    var chip: String? = nil; var chipColor: String? = nil
    var check: (pid: String, on: Bool)? = nil
    static func == (a: PItem, b: PItem) -> Bool { a.id == b.id }
    func hash(into h: inout Hasher) { h.combine(id) }
}

struct PageEntry: TimelineEntry {
    let date: Date
    var name = String(localized: "Seite oder Datenbank"); var emoji = "📄"
    var targetID: Int? = nil; var dbID: Int? = nil; var viewID: String? = nil
    var items: [PItem] = []
    var message: String? = nil
}

struct PageProvider: AppIntentTimelineProvider {
    func placeholder(in context: Context) -> PageEntry {
        PageEntry(date: .now, name: String(localized: "ToDos"), emoji: "✅", items: [PItem(id: 1, title: String(localized: "Eintrag"), emoji: "📄", chip: String(localized: "Offen"), chipColor: "blue")])
    }
    func snapshot(for configuration: PageWidgetIntent, in context: Context) async -> PageEntry {
        configuration.target == nil ? placeholder(in: context) : await load(configuration.target)
    }
    func timeline(for configuration: PageWidgetIntent, in context: Context) async -> Timeline<PageEntry> {
        Timeline(entries: [await load(configuration.target)], policy: .after(.now.addingTimeInterval(30 * 60)))
    }

    private func load(_ t: TargetEntity?) async -> PageEntry {
        var e = PageEntry(date: .now)
        guard let t, let id = t.pageID else { e.message = String(localized: "Widget gedrückt halten → „Widget bearbeiten“ → Seite oder Datenbank wählen"); return e }
        e.name = t.name; e.emoji = t.icon ?? "📄"; e.targetID = id
        do {
            if t.isDatabase {
                e.dbID = id; e.viewID = t.viewID
                guard let q = DBQuery.parse(try await API.rows(id)) else { e.message = String(localized: "Datenbank nicht lesbar"); return e }
                let view = q.view(t.viewID)
                // Chip: "Status" (sonst erste Auswahl); Haekchen: erstes Haekchen-Feld - wie in der Liste der App
                let chipP = q.props.first { $0.type == "select" && $0.name.lowercased() == "status" } ?? q.props.first { $0.type == "select" }
                let checkP = q.props.first { $0.type == "checkbox" }
                e.items = q.rows(for: view).map { r in
                    var it = PItem(id: r.id, title: r.title.isEmpty ? String(localized: "Unbenannt") : r.title, emoji: emoji(r.icon) ?? "📄")
                    if let p = chipP, let o = p.option(q.value(p, r) as? String) { it.chip = o.name; it.chipColor = o.color }
                    if let p = checkP { it.check = (p.id, (q.value(p, r) as? Bool) ?? false) }
                    return it
                }
                if e.items.isEmpty { e.message = String(localized: "Keine Einträge") }
            } else {
                let pages = try await API.pages()
                e.items = pages.filter { $0.parent_id == id }.map { p in
                    PItem(id: p.id, title: p.title.isEmpty ? String(localized: "Unbenannt") : p.title, emoji: p.emoji ?? (p.isDatabase ? "🗂" : "📄"))
                }
                if e.items.isEmpty { e.message = String(localized: "Keine Unterseiten") }
            }
        } catch { e.message = error.localizedDescription }
        return e
    }
    private func emoji(_ s: String?) -> String? { guard let s, !s.isEmpty, s.count <= 4, !s.hasPrefix("/") else { return nil }; return s }
}

struct PageWidgetView: View {
    @Environment(\.widgetFamily) private var family
    let entry: PageEntry
    private var limit: Int { family == .systemLarge ? 10 : family == .systemMedium ? 4 : 3 }
    private var newURL: URL? {
        if let db = entry.dbID { return WLink.url("new?db=\(db)" + (entry.viewID.map { "&view=\($0)" } ?? "")) }
        return entry.targetID.map { WLink.url("new?parent=\($0)") }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: family == .systemSmall ? 5 : 6) {
            HStack(spacing: 6) {
                Link(destination: entry.targetID.map(WLink.page) ?? WLink.today) {
                    HStack(spacing: 5) {
                        Text(entry.emoji).font(.subheadline)
                        Text(entry.name).font(.headline).lineLimit(1)
                    }
                }
                Spacer(minLength: 4)
                if family != .systemSmall, let u = newURL {
                    Link(destination: u) {
                        Image(systemName: "plus").font(.caption.weight(.bold)).frame(width: 22, height: 22).background(.white.opacity(0.12), in: Circle())
                    }
                }
            }
            if let m = entry.message, entry.items.isEmpty {
                Text(m).font(.caption).foregroundStyle(.secondary)
            }
            ForEach(entry.items.prefix(limit), id: \.self) { it in Row(item: it, small: family == .systemSmall) }
            if entry.items.count > limit {
                Text("+ \(entry.items.count - limit) weitere").font(.caption2).foregroundStyle(.secondary)
            }
            Spacer(minLength: 0)
        }
        .containerBackground(for: .widget) { Color(red: 0.07, green: 0.09, blue: 0.13) }
        .environment(\.colorScheme, .dark)
        .widgetURL(entry.targetID.map(WLink.page) ?? WLink.today)
    }

    struct Row: View {
        let item: PItem; let small: Bool
        var body: some View {
            HStack(spacing: 6) {
                if let c = item.check {
                    Button(intent: CheckRowIntent(rowID: item.id, pid: c.pid, value: !c.on)) {      // direkt im Widget setzen
                        Image(systemName: c.on ? "checkmark.square.fill" : "square").font(.caption)
                            .foregroundStyle(c.on ? Color.accentColor : .secondary).frame(width: 18, height: 18).contentShape(Rectangle())
                    }.buttonStyle(.plain)
                }
                Link(destination: WLink.page(item.id)) {
                    HStack(spacing: 5) {
                        if item.check == nil { Text(item.emoji).font(.caption) }
                        Text(item.title).font(.caption).lineLimit(1)
                            .strikethrough(item.check?.on == true, color: .secondary).foregroundStyle(item.check?.on == true ? .secondary : .primary)
                        Spacer(minLength: 2)
                        if let chip = item.chip, !small {
                            Text(chip).font(.caption2.weight(.medium)).lineLimit(1)
                                .padding(.horizontal, 6).padding(.vertical, 1.5)
                                .background(optColor(item.chipColor).opacity(0.28), in: RoundedRectangle(cornerRadius: 4))
                        }
                    }
                }
            }
        }
    }
}

struct PageWidget: Widget {
    var body: some WidgetConfiguration {
        AppIntentConfiguration(kind: "page", intent: PageWidgetIntent.self, provider: PageProvider()) { PageWidgetView(entry: $0) }
            .configurationDisplayName("Seite oder Datenbank")
            .description("Eine Datenbank-Ansicht (mit Filter und Sortierung) oder die Unterseiten einer Seite.")
            .supportedFamilies([.systemSmall, .systemMedium, .systemLarge])
    }
}
