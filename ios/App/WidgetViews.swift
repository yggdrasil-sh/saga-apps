import SwiftUI

// Saga 2.5 Widgets in Seiten, Etappe 1: native Anzeige (Uhr, Countdown, Fortschritt, Kennzahl, Zitat).
// Regeln/Format in WidgetLogic.swift (mit Tests). Bearbeiten nur im Web; der Block bleibt beim Speichern unveraendert.

/// Werte der Seiten-Widgets (GET /api/notes/{nid}/widgets) - je Seite eine Instanz
@MainActor
final class PageWidgetValues: ObservableObject {
    @Published private(set) var load: WidgetLogic.Load = .idle
    /// einmal je Oeffnen gewuerfelt (Zitat mit pick "open", lokaler Rueckfall)
    let seed = UInt64.random(in: 0...UInt64.max)
    private var busy = false
    init() {}
    #if DEBUG
    init(preview: WidgetLogic.Load) { load = preview }      // nur fuer Bilder (DB25Preview)
    #endif

    /// Laden, wenn die Seite Widgets mit Server-Wert hat; still (Fehler zeigt die Karte)
    func refresh(note: Int, blocks: NSArray, store: Store) async {
        guard !busy, WidgetLogic.pageNeedsServer(blocks) else { return }
        busy = true; defer { busy = false }
        let tz = TimeZone.autoupdatingCurrent.identifier.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? ""
        let next: WidgetLogic.Load
        do {
            let (d, code) = try await store.apiResponse("api/notes/\(note)/widgets?tz=\(tz)")
            next = WidgetLogic.load(code: code, data: d)
        } catch is CancellationError {
            return
        } catch {
            next = .failed
        }
        let merged = WidgetLogic.merge(old: load, new: next)
        if merged != load { load = merged }
    }
}

/// Block "widget": Karte je Art; unbekannte Art -> Hinweis "neuere Version noetig"
struct WidgetBlockView: View {
    let data: NSDictionary
    let blockID: String
    @EnvironmentObject private var values: PageWidgetValues
    @EnvironmentObject private var store: Store

    var body: some View {
        let b = WidgetLogic.block(data)
        // 2.5: neue Arten nur mit passendem Server (features), sonst wie bisher "neuere Version noetig"
        let known = b.known && (Widget2Logic.featureFor(b.kind).map { store.features.has($0) } ?? true)
        let v = WidgetLogic.value(values.load, blockID: blockID)
        let notice = WidgetLogic.notice(block: b, load: values.load, value: v)
        if !known {
            HStack(spacing: 8) {
                Image(systemName: "puzzlepiece.extension").foregroundStyle(.secondary)
                Text("Widget – neuere Version nötig").font(.subheadline).foregroundStyle(.secondary)
            }
            .padding(10).frame(maxWidth: .infinity, alignment: .leading)
            .overlay(RoundedRectangle(cornerRadius: 10).strokeBorder(.secondary.opacity(0.35), style: StrokeStyle(lineWidth: 1, dash: [4, 3])))
            .accessibilityElement(children: .combine)
        } else {
            WidgetCard(block: b) {
                if let notice { WidgetNoticeView(notice: notice) } else { WidgetContent(block: b, value: v, blockID: blockID) }
            }
        }
    }
}

/// Rahmen: Titel, Akzentfarbe, Groesse, Stil card/plain
private struct WidgetCard<Content: View>: View {
    let block: WidgetLogic.Block
    @ViewBuilder let content: Content
    var body: some View {
        let accent = block.accent.map { optColor($0) }
        VStack(alignment: .leading, spacing: 6) {
            if !block.title.isEmpty {
                Text(verbatim: block.title).font(.subheadline.weight(.semibold)).foregroundStyle(accent ?? .secondary).lineLimit(2)
            }
            content
        }
        .frame(maxWidth: .infinity, minHeight: minHeight, alignment: .leading)
        .padding(block.style == .card ? 14 : 4)
        .background {
            if block.style == .card {
                RoundedRectangle(cornerRadius: 12).fill((accent ?? .secondary).opacity(accent == nil ? 0.08 : 0.12))
            }
        }
        .overlay {
            if block.style == .card { RoundedRectangle(cornerRadius: 12).strokeBorder((accent ?? .secondary).opacity(0.3), lineWidth: 1) }
        }
        .tint(accent ?? .accentColor)
        .environment(\.widgetSize, block.size)
        .accessibilityElement(children: .combine)
    }
    private var minHeight: CGFloat { block.size == .s ? 0 : block.size == .m ? 60 : 110 }
}

private struct WidgetSizeKey: EnvironmentKey { static let defaultValue: WidgetLogic.Size = .m }
extension EnvironmentValues { var widgetSize: WidgetLogic.Size { get { self[WidgetSizeKey.self] } set { self[WidgetSizeKey.self] = newValue } } }

/// Schrift des Hauptwerts je Groesse
private func valueFont(_ s: WidgetLogic.Size) -> Font {
    switch s { case .s: return .title3.weight(.semibold); case .m: return .largeTitle.weight(.semibold); case .l: return .system(size: 54, weight: .semibold) }
}

private struct WidgetNoticeView: View {
    let notice: WidgetLogic.Notice
    var body: some View {
        Label { Text(text).font(.footnote) } icon: { Image(systemName: icon) }
            .foregroundStyle(.secondary)
    }
    private var icon: String {
        switch notice {
        case .noAccess: return "lock"
        case .gone: return "trash"
        case .failed: return "wifi.slash"
        default: return "info.circle"
        }
    }
    private var text: String {
        switch notice {
        case .newerVersion: return String(localized: "Widget – neuere Version nötig")
        case .noAccess: return String(localized: "Kein Zugriff auf die Datenquelle")
        case .gone: return String(localized: "Datenquelle gelöscht")
        case .badCfg: return String(localized: "Widget ist nicht vollständig eingerichtet – im Web bearbeiten")
        case .tooBig: return String(localized: "Zu viele Einträge für dieses Widget")
        case .olderServer: return String(localized: "Der Server zeigt noch keine Widget-Werte an")
        case .originOnly: return String(localized: "Widget nur auf dem Ursprungsserver")
        case .failed: return String(localized: "Wert konnte nicht geladen werden")
        case .unknownError: return String(localized: "Wert nicht verfügbar")
        }
    }
}

private struct WidgetContent: View {
    let block: WidgetLogic.Block
    let value: WidgetLogic.Value?
    let blockID: String
    @EnvironmentObject private var values: PageWidgetValues
    @Environment(\.widgetSize) private var size
    @Environment(\.locale) private var envLocale

    private var locale: Locale { SagaDate.locale(envLocale) }

    var body: some View {
        switch block.kind {
        case "clock": ClockWidget(cfg: block.cfg, locale: locale)
        case "countdown": CountdownWidget(cfg: block.cfg)
        case "progress": progress
        case "metric": metric
        case "weather": WeatherWidget(value: value)
        case "habit": HabitWidget(value: value, blockID: blockID)
        case "upcoming": UpcomingWidget(value: value)
        case "chart": if let d = value?.dict { ChartBody(data: ChartData.parse(d), height: size == .s ? 140 : size == .m ? 200 : 280) } else { Text(verbatim: "–").foregroundStyle(.secondary) }
        default: quote
        }
    }

    @ViewBuilder private var progress: some View {
        if let p = WidgetLogic.progress(block, value: value) {
            VStack(alignment: .leading, spacing: 6) {
                Text(verbatim: WidgetLogic.format(Double(p.percent), fmt: "pct", locale: locale)).font(valueFont(size)).monospacedDigit()
                ProgressView(value: p.fraction).progressViewStyle(.linear)
                let vf = WidgetLogic.progressFmt(block, value: value)
                Text("\(WidgetLogic.format(p.value, fmt: vf, locale: locale)) von \(WidgetLogic.format(p.max, fmt: vf, locale: locale))")
                    .font(.caption).foregroundStyle(.secondary).monospacedDigit()
            }
        } else {
            Text(verbatim: "–").font(valueFont(size)).foregroundStyle(.secondary)
        }
    }

    @ViewBuilder private var metric: some View {
        let fmt = WidgetLogic.metricFmt(block, value: value)
        VStack(alignment: .leading, spacing: 4) {
            Text(verbatim: WidgetLogic.format(value?.value, fmt: fmt, locale: locale)).font(valueFont(size)).monospacedDigit()
                .lineLimit(1).minimumScaleFactor(0.5)
            if let g = WidgetLogic.num(block.cfg["goal"]) {
                let reached = WidgetLogic.goalReached(block, value: value) == true
                Label { Text("Ziel: \(WidgetLogic.format(g, fmt: fmt, locale: locale))") } icon: { Image(systemName: reached ? "checkmark.circle.fill" : "target") }
                    .font(.caption).foregroundStyle(reached ? Color.green : Color.secondary)
            }
            if let d = value?.asOf { asOf(d) }
        }
    }

    @ViewBuilder private var quote: some View {
        TimelineView(.everyMinute) { ctx in
            if let t = WidgetLogic.quoteText(block, value: value, local: WidgetLogic.quoteLocal(values.load), blockID: blockID, now: ctx.date, seed: values.seed) {
                HStack(alignment: .top, spacing: 8) {
                    Rectangle().fill(.tint).frame(width: 3)
                    Text(verbatim: t).font(size == .s ? .callout : size == .m ? .title3 : .title2).italic()
                        .fixedSize(horizontal: false, vertical: true)
                }
            } else {
                Text(verbatim: "–").foregroundStyle(.secondary)
            }
        }
    }

    private func asOf(_ d: Date) -> some View {
        Text("Stand: \(d.formatted(SagaDate.style(date: .abbreviated, time: .shortened)))").font(.caption2).foregroundStyle(.tertiary)
    }
}

/// Uhr: digital oder Zeiger, tickt lokal (Sekunde nur, wenn angezeigt)
private struct ClockWidget: View {
    let cfg: [String: Any]
    let locale: Locale
    @Environment(\.widgetSize) private var size
    var body: some View {
        let sec = WidgetLogic.bool(cfg, "sec", false)
        let analog = WidgetLogic.bool(cfg, "analog", false)
        TimelineView(.periodic(from: .now, by: sec ? 1 : 15)) { ctx in
            HStack(spacing: 14) {
                if analog {
                    AnalogClock(hands: WidgetLogic.hands(ctx.date, cfg: cfg), seconds: sec)
                        .frame(width: dial, height: dial)
                }
                VStack(alignment: .leading, spacing: 2) {
                    Text(verbatim: WidgetLogic.clockTime(ctx.date, cfg: cfg, locale: locale))
                        .font(analog ? .title3.weight(.semibold) : valueFont(size)).monospacedDigit()
                    Text(verbatim: WidgetLogic.clockDate(ctx.date, cfg: cfg, locale: locale)).font(.caption).foregroundStyle(.secondary)
                    let zone = WidgetLogic.zoneLabel(cfg)
                    if !zone.isEmpty { Text(verbatim: zone).font(.caption2).foregroundStyle(.tertiary) }
                }
            }
        }
    }
    private var dial: CGFloat { size == .s ? 48 : size == .m ? 76 : 120 }
}

private struct AnalogClock: View {
    let hands: WidgetLogic.Hands
    let seconds: Bool
    var body: some View {
        GeometryReader { g in
            let r = min(g.size.width, g.size.height) / 2
            ZStack {
                Circle().strokeBorder(.secondary.opacity(0.5), lineWidth: max(1, r * 0.05))
                ForEach(0..<12, id: \.self) { i in
                    Capsule().fill(.secondary).frame(width: max(1, r * 0.04), height: r * 0.12)
                        .offset(y: -r * 0.82).rotationEffect(.degrees(Double(i) * 30))
                }
                hand(r * 0.5, width: r * 0.08, angle: hands.hour, color: .primary)
                hand(r * 0.75, width: r * 0.05, angle: hands.minute, color: .primary)
                if seconds { hand(r * 0.82, width: max(1, r * 0.02), angle: hands.second, color: .accentColor) }
                Circle().fill(.tint).frame(width: r * 0.1, height: r * 0.1)
            }
            .frame(width: g.size.width, height: g.size.height)
        }
        .accessibilityHidden(true)
    }
    private func hand(_ len: CGFloat, width: CGFloat, angle: Double, color: Color) -> some View {
        Capsule().fill(color).frame(width: width, height: len)
            .offset(y: -len / 2).rotationEffect(.degrees(angle))
    }
}

/// Countdown: minuetlich neu berechnet (Zeitzone des Betrachters)
private struct CountdownWidget: View {
    let cfg: [String: Any]
    @Environment(\.widgetSize) private var size
    private var dayFont: Font { size == .s ? .title3.weight(.semibold) : size == .m ? .title.weight(.semibold) : .largeTitle.weight(.semibold) }
    var body: some View {
        TimelineView(.everyMinute) { ctx in
            let c = WidgetLogic.countdown(cfg, now: ctx.date)
            VStack(alignment: .leading, spacing: 2) {
                switch c {
                case .days(let n, let since):
                    Text(since ? "seit \(n) Tagen" : "noch \(n) Tage").font(dayFont).monospacedDigit()
                case .hoursMinutes(let h, let m, let since):
                    Text(verbatim: String(format: "%d:%02d", h, m)).font(valueFont(size)).monospacedDigit()
                    Text(since ? "Std.:Min. seit dem Ziel" : "Std.:Min. bis zum Ziel").font(.caption).foregroundStyle(.secondary)
                case .today:
                    Text("Heute").font(valueFont(size))
                case .reached:
                    Label("Ziel erreicht", systemImage: "flag.checkered").font(.title3.weight(.semibold))
                case .notYet:
                    Text("Noch nicht erreicht").font(.title3).foregroundStyle(.secondary)
                case .invalid:
                    WidgetNoticeView(notice: .badCfg)
                }
                if c != .invalid, let t = WidgetLogic.target(cfg, calendar: SagaDate.calendar) {
                    let hasTime = !((cfg["time"] as? String) ?? "").isEmpty
                    Text(verbatim: t.formatted(SagaDate.style(date: .long, time: hasTime ? .shortened : .omitted))).font(.caption2).foregroundStyle(.tertiary)
                }
            }
        }
    }
}

// MARK: - Widgets Etappe 2 (weather1, habit1, upcoming1)

private struct WeatherWidget: View {
    let value: WidgetLogic.Value?
    @Environment(\.widgetSize) private var size
    var body: some View {
        if let n = Widget2Logic.weatherNotice(value?.error) {
            Label(n, systemImage: "cloud.sun").font(.footnote).foregroundStyle(.secondary)
        } else if let w = Widget2Logic.weather(value?.dict) {
            let unit = w.units == "imperial" ? "°F" : "°C"
            VStack(alignment: .leading, spacing: 8) {
                HStack(alignment: .center, spacing: 12) {
                    Image(systemName: Widget2Logic.weatherSymbol(w.code)).symbolRenderingMode(.multicolor).font(.system(size: size == .s ? 26 : 38))
                    VStack(alignment: .leading, spacing: 0) {
                        Text(verbatim: w.now.map { String(format: "%.0f", $0) + unit } ?? "–").font(valueFont(size)).monospacedDigit()
                        Text(verbatim: w.place).font(.caption).foregroundStyle(.secondary)
                    }
                }
                if size != .s && w.days.count > 1 {
                    HStack(spacing: 10) {
                        ForEach(Array(w.days.dropFirst().prefix(size == .m ? 5 : 7).enumerated()), id: \.offset) { _, d in
                            VStack(spacing: 2) {
                                Text(verbatim: weekday(d.d)).font(.caption2).foregroundStyle(.secondary)
                                Image(systemName: Widget2Logic.weatherSymbol(d.code)).symbolRenderingMode(.multicolor)
                                Text(verbatim: (d.max.map { String(format: "%.0f°", $0) } ?? "–") + " " + (d.min.map { String(format: "%.0f°", $0) } ?? "")).font(.caption2).monospacedDigit()
                            }
                        }
                    }
                }
                HStack(spacing: 6) {
                    if w.stale { Text("Letzter Stand").font(.caption2).foregroundStyle(.orange) }
                    Text(verbatim: "Wetterdaten: Open-Meteo.com").font(.caption2).foregroundStyle(.tertiary)   // Pflichtangabe (CC BY 4.0)
                }
            }
        } else { Text(verbatim: "–").foregroundStyle(.secondary) }
    }
    private func weekday(_ s: String) -> String {
        guard let p = DBDate.part(s) else { return "" }
        let d = SagaDate.calendar.date(from: DateComponents(year: p.y, month: p.m, day: p.d, hour: 12)) ?? .now
        return d.formatted(SagaDate.dateTime().weekday(.abbreviated))
    }
}

private struct HabitWidget: View {
    let value: WidgetLogic.Value?
    let blockID: String
    @EnvironmentObject private var values: PageWidgetValues
    @EnvironmentObject private var store: Store
    @EnvironmentObject private var ed: PageEditor
    @State private var local: Widget2Logic.Habit?
    @State private var busy = false
    var body: some View {
        if let h = local ?? Widget2Logic.habit(value?.dict) {
            VStack(alignment: .leading, spacing: 6) {
                ForEach(h.habits, id: \.pid) { it in
                    HStack(spacing: 8) {
                        Text(verbatim: it.name).font(.subheadline).lineLimit(1).frame(minWidth: 60, maxWidth: 140, alignment: .leading)
                        HStack(spacing: 3) {
                            ForEach(Array(h.days.enumerated()), id: \.offset) { i, day in
                                let on = it.done[safe: i] ?? false
                                Button { toggle(it.pid, day, !on) } label: {
                                    RoundedRectangle(cornerRadius: 3).fill(on ? Color.accentColor : Color.secondary.opacity(0.15))
                                        .frame(width: h.days.count > 14 ? 8 : 16, height: 16)
                                        .overlay { if day == h.today { RoundedRectangle(cornerRadius: 3).stroke(Color.primary.opacity(0.5), lineWidth: 1) } }
                                }
                                .buttonStyle(.plain).disabled(!h.canCheck || busy)
                                .accessibilityLabel(Text(verbatim: it.name + " " + day)).accessibilityAddTraits(on ? .isSelected : [])
                            }
                        }
                        Spacer(minLength: 4)
                        Text("\(it.streak) 🔥").font(.caption).monospacedDigit().foregroundStyle(.secondary).help(Text("Tage in Folge"))
                    }
                }
                if let g = h.goal { Text("Ziel: \(g) Tage je Woche").font(.caption2).foregroundStyle(.tertiary) }
            }
            .onChange(of: value?.data) { _, _ in local = nil }
        } else { Text(verbatim: "–").foregroundStyle(.secondary) }
    }
    /// POST /api/notes/{seite}/widgets/{bid}/habit -> neuer Stand
    private func toggle(_ pid: String, _ day: String, _ on: Bool) {
        busy = true
        Task {
            defer { busy = false }
            let tz = TimeZone.current.identifier.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? "UTC"
            if let d = try? await store.api("api/notes/\(ed.doc.id)/widgets/\(blockID)/habit?tz=\(tz)", method: "POST", body: Widget2Logic.habitBody(pid: pid, day: day, on: on)),
               let j = (try? JSONSerialization.jsonObject(with: d)) as? [String: Any], let w = j["widget"] as? [String: Any] {
                local = Widget2Logic.habit(w["data"] as? [String: Any])
            }
        }
    }
}

private struct UpcomingWidget: View {
    let value: WidgetLogic.Value?
    @EnvironmentObject private var nav: NavModel
    var body: some View {
        if let u = Widget2Logic.upcoming(value?.dict) {
            VStack(alignment: .leading, spacing: 6) {
                if u.items.isEmpty { Text("Nichts Anstehendes").font(.subheadline).foregroundStyle(.secondary) }
                ForEach(Array(Widget2Logic.sections(u, today: u.from).enumerated()), id: \.offset) { _, sec in
                    Text(title(sec.0)).font(.caption.weight(.semibold)).foregroundStyle(sec.0 == .overdue ? .red : .secondary)
                    ForEach(Array(sec.1.enumerated()), id: \.offset) { _, it in
                        Button { if let n = it.note { nav.open(n, mode: "side") } } label: {
                            HStack(spacing: 6) {
                                Circle().fill(it.color.flatMap { Color(hex: $0) } ?? (it.k == "task" ? .orange : .accentColor)).frame(width: 7, height: 7)
                                Image(systemName: it.k == "event" ? "calendar" : it.k == "task" ? "checkmark.circle" : "doc").font(.caption).foregroundStyle(.secondary)
                                Text(verbatim: it.title).lineLimit(1)
                                Spacer(minLength: 4)
                                if !it.allday, let t = DBDate.parse(it.start)?.start.instant { Text(t.formatted(date: .omitted, time: .shortened)).font(.caption).foregroundStyle(.secondary) }
                            }.font(.subheadline).contentShape(Rectangle())
                        }.buttonStyle(.plain).disabled(it.note == nil)
                    }
                }
                if u.more > 0 { Text("+\(u.more) weitere").font(.caption).foregroundStyle(.secondary) }
                if u.errors > 0 { Text("Einige Quellen nicht erreichbar").font(.caption2).foregroundStyle(.orange) }
            }
        } else { Text(verbatim: "–").foregroundStyle(.secondary) }
    }
    private func title(_ s: Widget2Logic.Section) -> String {
        switch s {
        case .overdue: return String(localized: "Überfällig")
        case .today: return String(localized: "Heute")
        case .tomorrow: return String(localized: "Morgen")
        case .day(let d): return DBDate.display(d) ?? d
        }
    }
}
