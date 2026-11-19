import SwiftUI
import WidgetKit
import AppIntents

/// Eine Zeile im Widget: Datenbank-Eintrag oder Unterseite (Codable: letzter guter Stand, Bugs-DB 2185)
struct PItem: Hashable, Codable {
    struct Check: Hashable, Codable { var pid: String; var on: Bool }
    let id: Int; let title: String; let emoji: String
    var chip: String? = nil; var chipColor: String? = nil
    var check: Check? = nil
    static func == (a: PItem, b: PItem) -> Bool { a.id == b.id }
    func hash(into h: inout Hasher) { h.combine(id) }
}

struct PageEntry: TimelineEntry {
    var date: Date
    var name = String(localized: "Seite oder Datenbank"); var emoji = "📄"
    var targetID: Int? = nil; var dbID: Int? = nil; var viewID: String? = nil
    var items: [PItem] = []
    /// alle passenden Zeilen (Server: matched) - fuer "+ N weitere"
    var total: Int? = nil
    var message: String? = nil
    /// 2185: "Stand: 14:05", wenn das Gezeigte nicht frisch ist (Abruf fehlgeschlagen bzw. lange nicht neu geladen)
    var stamp: String? = nil
}

/// Letzter guter Inhalt eines Seiten-/Datenbank-Widgets
struct PageSnap: Codable { var name: String; var emoji: String; var items: [PItem]; var total: Int?; var message: String? }

struct PageProvider: AppIntentTimelineProvider {
    func placeholder(in context: Context) -> PageEntry {
        PageEntry(date: .now, name: String(localized: "ToDos"), emoji: "✅", items: [PItem(id: 1, title: String(localized: "Eintrag"), emoji: "📄", chip: String(localized: "Offen"), chipColor: "blue")])
    }
    func snapshot(for configuration: PageWidgetIntent, in context: Context) async -> PageEntry {
        configuration.target == nil ? placeholder(in: context) : await load(configuration.target)
    }
    func timeline(for configuration: PageWidgetIntent, in context: Context) async -> Timeline<PageEntry> {
        let now = Date()
        let (e, ok, snapAt) = await fetch(configuration.target)
        // 2185: 15 min; dazu ein spaeterer Eintrag mit "Stand", falls WidgetKit nicht rechtzeitig neu laedt
        let entries = WidgetFeed.entryPlan(ok: ok, hasCached: snapAt != nil, fetchedAt: snapAt, now: now).map { p -> PageEntry in
            var x = e; x.date = p.date
            if p.stamp, let at = ok ? now : snapAt { x.stamp = WidgetFeed.stamp(at, now: p.date) }
            return x
        }
        return Timeline(entries: entries, policy: .after(WidgetFeed.nextReload(ok: ok, now: now)))
    }

    private func load(_ t: TargetEntity?) async -> PageEntry {
        let (e, ok, snapAt) = await fetch(t)
        var x = e
        if !ok, let at = snapAt { x.stamp = WidgetFeed.stamp(at, now: .now) }
        return x
    }

    private static func snapKey(_ t: TargetEntity) -> String? {
        WidgetFeed.snapshotKey(widget: "page", target: t.id, server: AppConfig.serverOrigin ?? "", token: AppConfig.token)
    }

    /// -> (Eintrag, frisch geladen?, Zeitpunkt des gezeigten gemerkten Stands bei Fehler)
    private func fetch(_ t: TargetEntity?) async -> (PageEntry, Bool, Date?) {
        var e = PageEntry(date: .now)
        guard let t, let id = t.pageID else { e.message = String(localized: "Widget gedrückt halten → „Widget bearbeiten“ → Seite oder Datenbank wählen"); return (e, true, nil) }
        e.name = t.name; e.emoji = t.icon ?? "📄"; e.targetID = id
        if t.isDatabase { e.dbID = id; e.viewID = t.viewID }
        do {
            if t.isDatabase {
                // 2185: Ansicht/Filter/Sortierung rechnet der Server, nur die ersten Zeilen kommen (aeltere Server: alle -> lokal)
                let raw = try await API.rows(id, view: t.viewID)
                guard let q = DBQuery.parse(raw) else { throw APIError.other(String(localized: "Datenbank nicht lesbar")) }
                let matched = WidgetFeed.serverMatched(raw)
                let rows = matched != nil ? q.rows : q.rows(for: q.view(t.viewID))
                // Chip: "Status" (sonst erste Auswahl); Haekchen: erstes Haekchen-Feld - wie in der Liste der App
                let chipP = q.props.first { $0.type == "select" && $0.name.lowercased() == "status" } ?? q.props.first { $0.type == "select" }
                let checkP = q.props.first { $0.type == "checkbox" }
                e.items = rows.prefix(WidgetFeed.rowLimit).map { r in
                    var it = PItem(id: r.id, title: r.title.isEmpty ? String(localized: "Unbenannt") : r.title, emoji: emoji(r.icon) ?? "📄")
                    if let p = chipP, let o = p.option(q.value(p, r) as? String) { it.chip = o.name; it.chipColor = o.color }
                    if let p = checkP { it.check = PItem.Check(pid: p.id, on: (q.value(p, r) as? Bool) ?? false) }
                    return it
                }
                e.total = WidgetFeed.total(matched: matched, shown: rows.count)
                if e.items.isEmpty { e.message = String(localized: "Keine Einträge") }
            } else {
                let pages = try await API.pages()
                e.items = pages.filter { $0.parent_id == id }.map { p in
                    PItem(id: p.id, title: p.title.isEmpty ? String(localized: "Unbenannt") : p.title, emoji: p.emoji ?? (p.isDatabase ? "🗂" : "📄"))
                }
                if e.items.isEmpty { e.message = String(localized: "Keine Unterseiten") }
            }
            WidgetSnapshot.save(PageSnap(name: e.name, emoji: e.emoji, items: e.items, total: e.total, message: e.message),
                                at: .now, key: Self.snapKey(t), to: AppConfig.store)
            return (e, true, nil)
        } catch {
            // 2185: nicht stumm alt und nicht leer - letzter guter Stand mit "Stand: ..." (sonst die Fehlermeldung)
            if let s = WidgetSnapshot<PageSnap>.load(Self.snapKey(t), from: AppConfig.store) {
                e.items = s.value.items; e.total = s.value.total; e.message = s.value.message
                return (e, false, s.at)
            }
            e.message = error.localizedDescription
            return (e, false, nil)
        }
    }
    private func emoji(_ s: String?) -> String? { guard let s, !s.isEmpty, s.count <= 4, !s.hasPrefix("/") else { return nil }; return s }
}

struct PageWidgetView: View {
    @Environment(\.widgetFamily) private var family
    let entry: PageEntry
    private var limit: Int { family == .systemLarge ? 10 : family == .systemMedium ? 4 : 3 }
    private var newURL: URL? {
        if let db = entry.dbID { return WLink.url("new?db=\(db)" + (entry.viewID.map { "&view=\($0)" } ?? "") + "&k=\(AppConfig.linkSecret)") }
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
            let rest = (entry.total ?? entry.items.count) - min(limit, entry.items.count)
            if rest > 0 {
                Text("+ \(rest) weitere").font(.caption2).foregroundStyle(.secondary)
            }
            Spacer(minLength: 0)
            if let st = entry.stamp {                                   // 2185: nicht frisch -> sichtbar mit Zeitpunkt
                Label(st, systemImage: "clock.arrow.circlepath").font(.caption2).foregroundStyle(.secondary).lineLimit(1)
            }
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
