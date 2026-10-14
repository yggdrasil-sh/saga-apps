import SwiftUI
import WidgetKit
import AppIntents

struct TodayEntry: TimelineEntry { let date: Date; let data: TodayData?; let error: String? }

struct TodayProvider: TimelineProvider {
    func placeholder(in context: Context) -> TodayEntry {
        TodayEntry(date: .now, data: TodayData(date: "", events: [TodayEvent(summary: String(localized: "Termin"), start: "", end: "", allday: false)],
                                               tasks: [TodayTask(id: 1, text: String(localized: "Aufgabe"))], errors: nil), error: nil)
    }
    func getSnapshot(in context: Context, completion: @escaping (TodayEntry) -> Void) { load(completion) }
    func getTimeline(in context: Context, completion: @escaping (Timeline<TodayEntry>) -> Void) {
        load { entry in completion(Timeline(entries: [entry], policy: .after(.now.addingTimeInterval(30 * 60)))) }   // alle 30 Min.
    }
    private func load(_ done: @escaping (TodayEntry) -> Void) {
        Task {
            do {
                var d = try await API.today()
                let hidden = WidgetState.tasks()                         // gerade abgehakt: nicht mehr zeigen
                d.tasks.removeAll { hidden[String($0.id)] != nil }
                done(TodayEntry(date: .now, data: d, error: nil))
            } catch { done(TodayEntry(date: .now, data: nil, error: error.localizedDescription)) }
        }
    }
}

enum WLink {
    static func url(_ s: String) -> URL { URL(string: "hlidskjalf://" + s)! }
    static let today = url("today"), tasks = url("tasks"), events = url("events"), newTask = url("new-task")
    static func page(_ id: Int) -> URL { url("page/\(id)") }
}

private let isoF = ISO8601DateFormatter()
private let dayF: DateFormatter = { let f = DateFormatter(); f.dateFormat = "yyyy-MM-dd"; f.timeZone = .current; return f }()
private let timeF: DateFormatter = { let f = DateFormatter(); f.setLocalizedDateFormatFromTemplate("jmm"); return f }()   // 15:30 bzw. 3:30 PM
/// 12-Stunden-Uhr (en-US): Zeitspalte im kleinen Widget etwas breiter
private let uses12h = (DateFormatter.dateFormat(fromTemplate: "j", options: 0, locale: .current) ?? "").contains("a")
/// Kennungen fuer "heute"/"morgen" (Anzeige und Vergleich)
private let wToday = String(localized: "heute"), wTomorrow = String(localized: "morgen")

/// Termine nur von HEUTE (lokal), ganztaegige zuerst.
private func todays(_ evs: [TodayEvent]) -> [(time: String, ev: TodayEvent)] {
    let cal = Calendar.current, today = cal.startOfDay(for: .now)
    return evs.compactMap { e in
        if e.allday, let s = dayF.date(from: e.start), let en = dayF.date(from: e.end), s <= today, today < en { return (String(localized: "ganztägig"), e) }
        if !e.allday, let s = isoF.date(from: e.start), cal.isDate(s, inSameDayAs: .now) { return (timeF.string(from: s), e) }
        return nil
    }
}

/// Naechster Termin, der noch nicht vorbei ist (heute oder morgen) - fuer den Sperrbildschirm
private func nextEvent(_ evs: [TodayEvent], now: Date) -> (when: String, ev: TodayEvent)? {
    let cal = Calendar.current
    let cands: [(Date, String, TodayEvent)] = evs.compactMap { e in
        if e.allday {
            guard let s = dayF.date(from: e.start), let en = dayF.date(from: e.end), en > now else { return nil }
            return (s, cal.isDateInToday(max(s, cal.startOfDay(for: now))) ? wToday : wTomorrow, e)
        }
        guard let s = isoF.date(from: e.start) else { return nil }
        let en = isoF.date(from: e.end) ?? s
        guard en > now else { return nil }
        return (s, cal.isDateInToday(s) ? timeF.string(from: s) : String(localized: "morgen \(timeF.string(from: s))"), e)
    }
    // zeitgebundene zuerst (ganztaegige laufen nebenher)
    let timed = cands.filter { !$0.2.allday }.sorted { $0.0 < $1.0 }
    if let t = timed.first { return (t.1, t.2) }
    return cands.sorted { $0.0 < $1.0 }.first.map { ($0.1, $0.2) }
}

// MARK: - Startbildschirm: Heute (Termine + Aufgaben, abhaken direkt im Widget)

struct TodayWidgetView: View {
    @Environment(\.widgetFamily) private var family
    let entry: TodayEntry
    private var limit: Int { family == .systemLarge ? 9 : family == .systemMedium ? 4 : 3 }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 6) {
                Link(destination: WLink.today) { Text("Heute").font(.headline) }
                Text(entry.date, format: .dateTime.weekday(.abbreviated).day().month(.abbreviated)).font(.caption).foregroundStyle(.secondary)
                Spacer()
                if family != .systemSmall {
                    Link(destination: WLink.newTask) {
                        Image(systemName: "plus").font(.caption.weight(.bold)).frame(width: 22, height: 22).background(.white.opacity(0.12), in: Circle())
                    }
                }
            }
            if let err = entry.error {
                Text(err).font(.caption).foregroundStyle(.secondary)
            } else if let d = entry.data {
                let evs = todays(d.events)
                if evs.isEmpty && d.tasks.isEmpty { Text("Nichts geplant 🎉").font(.caption).foregroundStyle(.secondary) }
                ForEach(Array(evs.prefix(limit).enumerated()), id: \.offset) { _, x in
                    Link(destination: WLink.events) {
                        HStack(spacing: 6) {
                            RoundedRectangle(cornerRadius: 1.5).fill(Color(hex: x.ev.color) ?? .blue).frame(width: 3)
                            Text(x.time).font(.caption2).foregroundStyle(.secondary).frame(width: family == .systemSmall ? (uses12h ? 46 : 34) : 56, alignment: .leading)
                            Text(x.ev.summary).font(.caption).lineLimit(1).privacySensitive()
                            Spacer(minLength: 0)
                        }.frame(height: 15)
                    }
                }
                if family != .systemSmall || evs.isEmpty {
                    let n = max(0, limit - min(evs.count, limit) + (family == .systemLarge ? 2 : 0))
                    ForEach(d.tasks.prefix(n), id: \.id) { t in TaskLine(task: t) }
                    if d.tasks.count > n && family != .systemSmall {
                        Link(destination: WLink.tasks) { Text("+ \(d.tasks.count - n) weitere").font(.caption2).foregroundStyle(.secondary) }
                    }
                }
            }
            Spacer(minLength: 0)
        }
        .containerBackground(for: .widget) { Color(red: 0.07, green: 0.09, blue: 0.13) }
        .environment(\.colorScheme, .dark)
        .widgetURL(WLink.today)
    }
}

struct TaskLine: View {
    let task: TodayTask
    var body: some View {
        HStack(spacing: 6) {
            Button(intent: ToggleTaskIntent(taskID: task.id, done: true)) {        // abhaken, ohne die App zu oeffnen
                Image(systemName: "circle").font(.caption).foregroundStyle(task.overdue == true ? .red : .secondary)
                    .frame(width: 18, height: 18).contentShape(Rectangle())
            }.buttonStyle(.plain).accessibilityLabel("Erledigt")
            Link(destination: WLink.tasks) {
                HStack(spacing: 4) {
                    Text(task.text).font(.caption).lineLimit(1).privacySensitive()
                    if task.prio == 1 { Text("!").font(.caption2.bold()).foregroundStyle(.orange) }
                    Spacer(minLength: 0)
                }
            }
        }
    }
}

struct TodayWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "today", provider: TodayProvider()) { TodayWidgetView(entry: $0) }
            .configurationDisplayName("Heute")
            .description("Termine und offene Aufgaben – direkt abhaken")
            .supportedFamilies([.systemSmall, .systemMedium, .systemLarge])
    }
}

// MARK: - Sperrbildschirm: naechster Termin / offene Aufgaben

#if os(iOS)
struct LockWidgetView: View {
    @Environment(\.widgetFamily) private var family
    let entry: TodayEntry
    private var tasks: [TodayTask] { entry.data?.tasks ?? [] }
    private var overdue: Int { tasks.filter { $0.overdue == true }.count }
    private var next: (when: String, ev: TodayEvent)? { nextEvent(entry.data?.events ?? [], now: entry.date) }

    var body: some View {
        Group {
            switch family {
            case .accessoryCircular:
                ZStack {
                    AccessoryWidgetBackground()
                    VStack(spacing: 0) {
                        Image(systemName: overdue > 0 ? "exclamationmark.circle" : "checklist").font(.caption)
                        Text("\(tasks.count)").font(.title3.weight(.semibold)).minimumScaleFactor(0.6)
                    }
                }
                .widgetURL(WLink.tasks)
            case .accessoryInline:
                if let n = next { Text(verbatim: "📅 " + (n.when == wToday ? "" : n.when + " ") + n.ev.summary).privacySensitive() }
                else { Text("✓ \(tasks.count) Aufgaben offen") }
            default:            // Rechteck
                VStack(alignment: .leading, spacing: 1) {
                    if let n = next {
                        Text(n.ev.summary).font(.headline).lineLimit(1).widgetAccentable().privacySensitive()   // gesperrt: Termintitel ausblenden
                        Text([n.when == wToday ? String(localized: "ganztägig") : n.when, n.ev.location].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " · "))
                            .font(.caption).lineLimit(1).privacySensitive()
                    } else {
                        Text("Keine Termine").font(.headline).widgetAccentable()
                    }
                    Text(tasks.isEmpty ? String(localized: "Alles erledigt") : String(localized: "\(tasks.count) Aufgaben") + (overdue > 0 ? " · " + String(localized: "\(overdue) überfällig") : ""))
                        .font(.caption).foregroundStyle(.secondary).lineLimit(1)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .widgetURL(next != nil ? WLink.events : WLink.tasks)
            }
        }
        .containerBackground(for: .widget) { Color.clear }
    }
}

struct LockWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "lock", provider: TodayProvider()) { LockWidgetView(entry: $0) }
            .configurationDisplayName("Saga")
            .description("Nächster Termin und offene Aufgaben")
            .supportedFamilies([.accessoryRectangular, .accessoryCircular, .accessoryInline])
    }
}
#endif

@main
struct HlidskjalfWidgets: WidgetBundle {
    var body: some Widget {
        PageCardWidget()
        TodayWidget()
        PageWidget()
        #if os(iOS)
        LockWidget()
        #endif
    }
}
