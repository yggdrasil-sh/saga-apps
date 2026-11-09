import SwiftUI

// Saga 2.5 Widgets in Seiten: native Anzeige (Uhr, Countdown, Fortschritt, Kennzahl, Zitat, Wetter, Gewohnheiten,
// Anstehendes, Diagramm, Lesen). Regeln/Format in WidgetLogic.swift (mit Tests). Bearbeiten nur im Web; der Block bleibt
// beim Speichern unveraendert.
// Widget-Design-Runde (9.11.): gleiches Geruest wie im Web (static/pages.js, style.css .wg*) - Karte immer in der
// Kartenfarbe mit Rand (nie in der Akzentfarbe gefuellt), Kopf mit Symbol der Art + Titel, grosser Wert in Textfarbe mit
// kleiner Einheit, gedaempfte Kontextzeile; Akzent nur fuer Balken, Ringe, Punkte, Sonne und den heutigen Tag.

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

// MARK: - Farben und Umgebung

/// Flaechen wie im Web (tokens.css): Karte, Rand, Feldrand (offene Kreise, mind. 3:1), Spur der Balken, Fehler
enum WGColor {
    static let card = Color(uiColor: UIColor { $0.userInterfaceStyle == .dark ? .secondarySystemBackground : .systemBackground })
    static let border = Color.adaptive(0xe7e2d7, 0x38383a)
    static let field = Color.adaptive(0x8f8670, 0x77777d)
    static let track = Color.primary.opacity(0.10)
    static let down = Color.adaptive(0xcf222e, 0xf85149)
    static let ok = Color.adaptive(0x1f7a33, 0x3fb950)
}

private struct WidgetSizeKey: EnvironmentKey { static let defaultValue: WidgetLogic.Size = .m }
private struct WidgetWidthKey: EnvironmentKey { static let defaultValue: CGFloat = 300 }
private struct WidgetAccentKey: EnvironmentKey { static let defaultValue: Color = .accentColor }
private struct WidgetAccentChosenKey: EnvironmentKey { static let defaultValue = false }
private struct WidgetFillKey: EnvironmentKey { static let defaultValue = false }
extension EnvironmentValues {
    var widgetSize: WidgetLogic.Size { get { self[WidgetSizeKey.self] } set { self[WidgetSizeKey.self] = newValue } }
    /// Innenbreite der Karte (fuer Umbrueche wie die Container-Regeln im Web)
    var widgetWidth: CGFloat { get { self[WidgetWidthKey.self] } set { self[WidgetWidthKey.self] = newValue } }
    /// Akzent des Widgets: gewaehlte Farbe oder Saga-Gold
    var widgetAccent: Color { get { self[WidgetAccentKey.self] } set { self[WidgetAccentKey.self] = newValue } }
    /// Farbe bewusst gewaehlt -> auch der Wert in dieser Farbe
    var widgetAccentChosen: Bool { get { self[WidgetAccentChosenKey.self] } set { self[WidgetAccentChosenKey.self] = newValue } }
    /// Karte fuellt die Hoehe der Reihe (Spalten mit je genau einem Widget)
    var widgetFill: Bool { get { self[WidgetFillKey.self] } set { self[WidgetFillKey.self] = newValue } }
}

/// Symbol und Name je Art (Kopf, Leerzustand)
enum WidgetKind {
    static func icon(_ k: String) -> String {
        switch k {
        case "clock": return "clock"
        case "countdown": return "hourglass"
        case "progress": return "capsule.lefthalf.filled"
        case "quote": return "quote.opening"
        case "weather": return "cloud.sun"
        case "habit": return "calendar.badge.checkmark"
        case "upcoming": return "list.bullet.indent"
        case "reading": return "book"
        default: return "chart.bar.xaxis"
        }
    }
    static func name(_ k: String) -> String {
        switch k {
        case "clock": return String(localized: "Uhr")
        case "countdown": return String(localized: "Countdown")
        case "progress": return String(localized: "Fortschritt")
        case "metric": return String(localized: "Kennzahl")
        case "quote": return String(localized: "Zitat")
        case "weather": return String(localized: "Wetter")
        case "habit": return String(localized: "Gewohnheiten")
        case "upcoming": return String(localized: "Anstehendes")
        case "chart": return String(localized: "Diagramm##Chart")
        case "reading": return String(localized: "Lesen##BookOrbit")
        default: return String(localized: "Widget")
        }
    }
    /// Hilfe im Leerzustand ("Datenbank und Gruppierung wählen")
    static func hint(_ k: String) -> String? {
        switch k {
        case "countdown": return String(localized: "Datum wählen")
        case "progress": return String(localized: "Quelle und Ziel wählen")
        case "metric": return String(localized: "Datenbank und Rechenart wählen")
        case "quote": return String(localized: "Zitate eintragen oder Datenbank wählen")
        case "weather": return String(localized: "Ort suchen")
        case "habit": return String(localized: "Datenbank und Häkchen wählen")
        case "chart": return String(localized: "Datenbank und Gruppierung wählen")
        case "upcoming": return String(localized: "Quellen wählen")
        case "reading": return String(localized: "BookOrbit verbinden")
        default: return nil
        }
    }
    /// Rechenart einer Kennzahl (Kontextzeile), auch Saga-Aufgaben
    static func agg(_ a: String?) -> String {
        switch a {
        case "open": return String(localized: "Offene Aufgaben")
        case "due_today": return String(localized: "Heute fällig")
        case "overdue": return String(localized: "Überfällig")
        case "done_week": return String(localized: "Diese Woche erledigt")
        default: return ChartData.aggLabel(a)
        }
    }
}

// MARK: - Block und Karte

/// Block "widget": Karte je Art; unbekannte Art -> Hinweis "neuere Version noetig"
struct WidgetBlockView: View {
    let data: NSDictionary
    let blockID: String
    /// Seite bearbeitbar? Dann zeigt der Leerzustand "… einrichten" mit Hilfe und gestricheltem Rand (wie im Web)
    var editable = false
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
            // nicht eingerichtet: Server meldet bad_cfg, Countdown ohne gueltiges Datum
            let unset = notice == .badCfg || (b.kind == "countdown" && WidgetLogic.countdown(b.cfg, now: .now) == .invalid)
            WidgetCard(block: b, unset: unset, editable: editable) {
                if unset { WidgetEmptyView(kind: b.kind, editable: editable) }
                else if let notice { WidgetNoticeView(notice: notice) }
                else { WidgetContent(block: b, value: v, blockID: blockID) }
            }
        }
    }
}

/// Rahmen: Kopf (Symbol + Titel), Groesse (Abstand, Mindesthoehe), Stil card/plain, Akzent
private struct WidgetCard<Content: View>: View {
    let block: WidgetLogic.Block
    let unset: Bool
    let editable: Bool
    @ViewBuilder let content: Content
    @Environment(\.widgetFill) private var fill
    @State private var width: CGFloat = 0

    var body: some View {
        let pad = WidgetLogic.padding(block.size)
        let card = block.style == .card
        let accent = block.accent.map { HKPalette.textColor($0) } ?? .accentColor
        let dashed = unset && editable
        VStack(alignment: .leading, spacing: block.size == .s ? 6 : block.size == .l ? 10 : 8) {
            if !unset || !block.title.isEmpty { header }
            content
        }
        .frame(maxWidth: .infinity, minHeight: WidgetLogic.minHeight(block.size), maxHeight: fill ? .infinity : nil, alignment: .topLeading)
        .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { if abs($0 - width) > 1 { width = $0 } }
        .padding(.vertical, pad.v).padding(.horizontal, card ? pad.h : 0)
        .background { if card { RoundedRectangle(cornerRadius: 10).fill(WGColor.card) } }
        .overlay {
            if card {
                RoundedRectangle(cornerRadius: 10)
                    .strokeBorder(dashed ? WGColor.field : WGColor.border, style: StrokeStyle(lineWidth: 1, dash: dashed ? [4, 3] : []))
            }
        }
        .tint(accent)
        .environment(\.widgetSize, block.size)
        .environment(\.widgetWidth, width > 0 ? width : 300)
        .environment(\.widgetAccent, accent)
        .environment(\.widgetAccentChosen, block.accent != nil)
        .accessibilityElement(children: .contain)
    }

    /// Kopf: Symbol der Art (14 pt, gedaempft) + Titel einzeilig (L: zweizeilig); leerer Titel -> Name der Art
    private var header: some View {
        HStack(spacing: 6) {
            Image(systemName: WidgetKind.icon(block.kind)).font(.system(size: 12, weight: .medium)).frame(width: 14, height: 14)
                .accessibilityHidden(true)
            Text(verbatim: block.title.isEmpty ? WidgetKind.name(block.kind) : block.title)
                .font(.footnote.weight(.medium)).lineLimit(block.size == .l ? 2 : 1).truncationMode(.tail)
        }
        .foregroundStyle(.secondary)
        .accessibilityAddTraits(.isHeader)
    }
}

/// Nicht eingerichtet: ruhiger Leerzustand (Symbol 20 pt, "Diagramm einrichten", Hilfe) - kein Warnzeichen
private struct WidgetEmptyView: View {
    let kind: String
    let editable: Bool
    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            Image(systemName: WidgetKind.icon(kind)).font(.system(size: 18)).frame(width: 20, height: 20).foregroundStyle(.secondary)
                .padding(.bottom, 3).accessibilityHidden(true)
            if editable {
                Text("\(WidgetKind.name(kind)) einrichten").font(.subheadline.weight(.medium))
                if let h = WidgetKind.hint(kind) { Text(verbatim: h).font(.footnote).foregroundStyle(.secondary) }
            } else {
                Text("Noch nicht eingerichtet").font(.subheadline.weight(.medium))
            }
        }
        .accessibilityElement(children: .combine)
    }
}

/// Hinweis statt Wert: echte Fehler mit Warnzeichen (rot), sonst ruhig gedaempft
private struct WidgetNoticeView: View {
    let notice: WidgetLogic.Notice
    var body: some View {
        let err = notice == .noAccess || notice == .gone || notice == .tooBig || notice == .unknownError
        Text(verbatim: (err ? "⚠ " : "") + text).font(.subheadline).foregroundStyle(err ? WGColor.down : .secondary)
    }
    private var text: String {
        switch notice {
        case .newerVersion: return String(localized: "Widget – neuere Version nötig")
        case .noAccess: return String(localized: "Kein Zugriff auf die Datenquelle")
        case .gone: return String(localized: "Die Datenquelle gibt es nicht mehr")
        case .badCfg: return String(localized: "Widget noch nicht fertig eingerichtet")
        case .tooBig: return String(localized: "Zu viele Einträge für ein Widget")
        case .olderServer: return String(localized: "Der Server zeigt noch keine Widget-Werte an")
        case .originOnly: return String(localized: "Widget nur auf dem Ursprungsserver")
        case .failed: return String(localized: "Wert konnte nicht geladen werden")
        case .unknownError: return String(localized: "Wert nicht verfügbar")
        }
    }
}

// MARK: - Bausteine: Wert, Balken, Kontext

/// Schriftgroesse des Hauptwerts: je Groesse hoechstens 19/28/40 pt, schmal kleiner (wie clamp(1.1rem, 13cqi, max))
func widgetValueSize(_ s: WidgetLogic.Size, width: CGFloat) -> CGFloat {
    let vmax: CGFloat = s == .s ? 19 : s == .l ? 40 : 28
    return min(vmax, max(17.6, width * 0.13))
}

/// Grosser Wert in Textfarbe (gewaehlte Farbe nur, wenn bewusst gesetzt), Einheit klein und gedaempft, einzeilig
struct WidgetValueText: View {
    let text: String
    var size: CGFloat = 28
    @Environment(\.widgetAccent) private var accent
    @Environment(\.widgetAccentChosen) private var chosen
    var body: some View {
        let p = WidgetLogic.valueParts(text)
        let small = Font.system(size: size * 0.55, weight: .medium)
        var t = Text(verbatim: "")
        if let pre = p.prefix { t = t + Text(verbatim: pre + "\u{00A0}").font(small).foregroundStyle(.secondary) }
        t = t + Text(verbatim: p.number)
        if let u = p.unit { t = t + Text(verbatim: "\u{00A0}" + u).font(small).foregroundStyle(.secondary) }
        return t.font(.system(size: size, weight: .semibold)).monospacedDigit()
            .foregroundStyle(chosen ? accent : .primary)
            .lineLimit(1).minimumScaleFactor(0.4)
            .accessibilityLabel(Text(verbatim: text))
    }
}

/// Balken (Fortschritt, Kennzahl mit Ziel, Lesen): 6 pt, Pille, Spur = Textfarbe 10 %, hoechstens voll
struct WidgetBar: View {
    let fraction: Double
    var body: some View {
        GeometryReader { g in
            ZStack(alignment: .leading) {
                Capsule().fill(WGColor.track)
                Capsule().fill(.tint).frame(width: g.size.width * min(1, max(0, fraction.isFinite ? fraction : 0)))
            }
        }
        .frame(height: 6)
        .accessibilityElement().accessibilityValue(Text(verbatim: "\(Int((min(1, max(0, fraction.isFinite ? fraction : 0)) * 100).rounded())) %"))
    }
}

/// Kontextzeile unter dem Wert (gedaempft, klein)
private struct WidgetSub: View {
    let text: String
    var body: some View { Text(verbatim: text).font(.footnote).foregroundStyle(.secondary).lineLimit(1).truncationMode(.tail) }
}

private func dayDate(_ s: String) -> Date? {
    guard let p = DBDate.part(String(s.prefix(10))) else { return nil }
    return SagaDate.calendar.date(from: DateComponents(year: p.y, month: p.m, day: p.d, hour: 12))
}
private func fmtDate(_ d: Date, _ template: String, locale: Locale) -> String {
    let f = DateFormatter()
    f.locale = locale; f.calendar = SagaDate.calendar; f.setLocalizedDateFormatFromTemplate(template)
    return f.string(from: d)
}

// MARK: - Inhalt je Art

private struct WidgetContent: View {
    let block: WidgetLogic.Block
    let value: WidgetLogic.Value?
    let blockID: String
    @EnvironmentObject private var values: PageWidgetValues
    @Environment(\.widgetSize) private var size
    @Environment(\.widgetWidth) private var width
    @Environment(\.widgetAccentChosen) private var chosen
    @Environment(\.widgetAccent) private var accent
    @Environment(\.locale) private var envLocale

    private var locale: Locale { SagaDate.locale(envLocale) }
    private var vsize: CGFloat { widgetValueSize(size, width: width) }

    var body: some View {
        switch block.kind {
        case "clock": ClockWidget(cfg: block.cfg, locale: locale)
        case "countdown": CountdownWidget(cfg: block.cfg, locale: locale)
        case "progress": progress
        case "metric": metric
        case "weather": WeatherWidget(value: value, locale: locale)
        case "habit": HabitWidget(value: value, blockID: blockID, locale: locale)
        case "upcoming": UpcomingWidget(value: value, locale: locale)
        case "reading": ReadingWidget(value: value)              // 2.5 bookorbit1 (BookOrbitViews.swift)
        case "chart": chart
        default: quote
        }
    }

    @ViewBuilder private var chart: some View {
        if let d = value?.dict {
            let c = ChartData.parse(d)
            ChartBody(data: c, height: CGFloat(WidgetLogic.chartHeight(size)), context: c.context(agg: block.cfg["agg"] as? String),
                      cap: ChartData.aggLabel(block.cfg["agg"] as? String), accent: chosen ? accent : nil)
        } else { Text(verbatim: "…").foregroundStyle(.secondary) }
    }

    /// Fortschritt: "68 %" gross, Balken, "68 von 100" (+ Marker ueber 100 %), Quelle unten
    @ViewBuilder private var progress: some View {
        if let p = WidgetLogic.progress(block, value: value) {
            let raw = max(0, p.value / p.max * 100)
            VStack(alignment: .leading, spacing: 6) {
                WidgetValueText(text: WidgetLogic.format(Double(p.percent), fmt: "pct", locale: locale), size: vsize)
                WidgetBar(fraction: p.fraction)
                let vf = WidgetLogic.progressFmt(block, value: value)
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Text("\(WidgetLogic.format(p.value, fmt: vf, locale: locale)) von \(WidgetLogic.format(p.max, fmt: vf, locale: locale))")
                        .font(.footnote).foregroundStyle(.secondary).monospacedDigit().lineLimit(1)
                    Spacer(minLength: 4)
                    chip(raw)
                }
                if (block.cfg["src"] as? String) == "tasks" { Text("Saga-Aufgaben").font(.caption).foregroundStyle(.secondary) }
            }
        } else {
            Text(verbatim: "…").font(.system(size: vsize, weight: .semibold)).foregroundStyle(.secondary)
        }
    }

    /// Ueber 100 %: "+50 %" in Akzent; genau erreicht: "✓ Ziel erreicht"
    @ViewBuilder private func chip(_ raw: Double) -> some View {
        if raw > 100.05 {
            Text(verbatim: "+" + WidgetLogic.format(raw - 100, fmt: "pct", locale: locale)).font(.caption.weight(.semibold)).monospacedDigit().foregroundStyle(.tint)
        } else if raw >= 99.95 {
            (Text(verbatim: "✓ ") + Text("Ziel erreicht")).font(.caption.weight(.semibold)).foregroundStyle(WGColor.ok)
        }
    }

    /// Kennzahl: Wert gross, Kontext "Rechenart" (entfaellt, wenn gleich dem Titel), mit Ziel: Balken + "68 % vom Ziel 100"
    @ViewBuilder private var metric: some View {
        let fmt = WidgetLogic.metricFmt(block, value: value)
        VStack(alignment: .leading, spacing: 6) {
            if let v = value {
                WidgetValueText(text: WidgetLogic.format(v.value, fmt: fmt, locale: locale), size: vsize)
                let ctx = WidgetKind.agg((block.cfg["agg"] as? String) ?? ((block.cfg["src"] as? String) == "tasks" ? "open" : "count"))
                if ctx.compare(block.title.trimmingCharacters(in: .whitespaces), options: [.caseInsensitive]) != .orderedSame { WidgetSub(text: ctx) }
                if let g = v.max ?? WidgetLogic.num(block.cfg["goal"]) {
                    let pm = g != 0 ? max(0, (v.value ?? 0) / g * 100) : 0
                    WidgetBar(fraction: pm / 100)
                    HStack(alignment: .firstTextBaseline, spacing: 8) {
                        Text("\(WidgetLogic.format(pm, fmt: "pct", locale: locale)) vom Ziel \(WidgetLogic.format(g, fmt: fmt, locale: locale))")
                            .font(.footnote).foregroundStyle(.secondary).monospacedDigit().lineLimit(1)
                        Spacer(minLength: 4)
                        if pm > 100.05 { chip(pm) }
                    }
                }
            } else {
                Text(verbatim: "…").font(.system(size: vsize, weight: .semibold)).foregroundStyle(.secondary)
            }
        }
    }

    /// Zitat: Serifenschrift, Zitatzeichen in Akzent davor, Quelle als eigene gedaempfte Zeile, Kuerzung 4/6/8 Zeilen
    @ViewBuilder private var quote: some View {
        TimelineView(.everyMinute) { ctx in
            if let t = WidgetLogic.quoteText(block, value: value, local: WidgetLogic.quoteLocal(values.load), blockID: blockID, now: ctx.date, seed: values.seed) {
                let q = WidgetLogic.quoteParts(t)
                let fs: CGFloat = size == .s ? 15 : size == .l ? 21 : 17
                HStack(alignment: .firstTextBaseline, spacing: 6) {
                    Text(verbatim: "“").font(.system(size: fs * 2, design: .serif)).foregroundStyle(.tint).opacity(0.85)
                        .frame(height: fs).accessibilityHidden(true)
                    VStack(alignment: .leading, spacing: 5) {
                        Text(verbatim: q.text).font(.system(size: fs, design: .serif)).lineSpacing(fs * 0.3)
                            .lineLimit(WidgetLogic.quoteLines(size)).truncationMode(.tail)
                        if let s = q.source { Text(verbatim: "— " + s).font(.footnote).foregroundStyle(.secondary) }
                    }
                }
            } else {
                Text(verbatim: "…").foregroundStyle(.secondary)
            }
        }
    }
}

/// Uhr: digital (Zeit gross, Datum + Ort als Kontext) oder Zeiger (12 Marken, Digitalzeit klein darunter)
private struct ClockWidget: View {
    let cfg: [String: Any]
    let locale: Locale
    @Environment(\.widgetSize) private var size
    @Environment(\.widgetWidth) private var width
    var body: some View {
        let sec = WidgetLogic.bool(cfg, "sec", false)
        let analog = WidgetLogic.bool(cfg, "analog", false)
        let zone = WidgetLogic.zoneLabel(cfg)
        TimelineView(.periodic(from: .now, by: sec ? 1 : 15)) { ctx in
            if analog {
                VStack(spacing: 6) {
                    AnalogClock(hands: WidgetLogic.hands(ctx.date, cfg: cfg), seconds: sec).frame(width: dial, height: dial)
                    WidgetSub(text: ([WidgetLogic.clockTime(ctx.date, cfg: cfg.merging(["sec": false]) { _, b in b }, locale: locale),
                                      WidgetLogic.clockShortDate(ctx.date, cfg: cfg, locale: locale)] + (zone.isEmpty ? [] : [zone])).joined(separator: " · "))
                        .monospacedDigit()
                }
                .frame(maxWidth: .infinity)
            } else {
                VStack(alignment: .leading, spacing: 4) {
                    Text(verbatim: WidgetLogic.clockTime(ctx.date, cfg: cfg, locale: locale))
                        .font(.system(size: widgetValueSize(size, width: width), weight: .semibold)).monospacedDigit().lineLimit(1).minimumScaleFactor(0.4)
                    WidgetSub(text: WidgetLogic.clockDate(ctx.date, cfg: cfg, locale: locale) + (zone.isEmpty ? "" : " · " + zone))
                }
            }
        }
    }
    /// Zifferblatt 60/92/128 pt, hoechstens 62 % der Breite
    private var dial: CGFloat { min(size == .s ? 60 : size == .m ? 92 : 128, width * 0.62) }
}

private struct AnalogClock: View {
    let hands: WidgetLogic.Hands
    let seconds: Bool
    @Environment(\.widgetAccent) private var accent
    var body: some View {
        GeometryReader { g in
            let r = min(g.size.width, g.size.height) / 2, u = r / 50          // Masse wie das SVG im Web (viewBox 100)
            ZStack {
                Circle().fill(Color.primary.opacity(0.03)).padding(3 * u)
                Circle().strokeBorder(WGColor.field, lineWidth: 1.5 * u).padding(3 * u)
                ForEach(0..<12, id: \.self) { i in
                    let q = i % 3 == 0
                    Capsule().fill(q ? Color.primary : Color.secondary).frame(width: (q ? 2.5 : 1.5) * u, height: (q ? 7.5 : 4.5) * u)
                        .offset(y: -r + (q ? 11.25 : 9.75) * u).rotationEffect(.degrees(Double(i) * 30))
                }
                hand(23 * u, width: 4.5 * u, angle: hands.hour, color: .primary)
                hand(35 * u, width: 3 * u, angle: hands.minute, color: .primary)
                if seconds { hand(38 * u, width: max(1, 1.2 * u), angle: hands.second, color: accent) }
                Circle().fill(Color.primary).frame(width: 6 * u, height: 6 * u)
                if seconds { Circle().fill(accent).frame(width: 3.2 * u, height: 3.2 * u) }
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

/// Countdown: Zahl gross, "Tage" klein, Kontext "bis Mo., 21. Dez. 2026"
private struct CountdownWidget: View {
    let cfg: [String: Any]
    let locale: Locale
    @Environment(\.widgetSize) private var size
    @Environment(\.widgetWidth) private var width
    var body: some View {
        TimelineView(.everyMinute) { ctx in
            let c = WidgetLogic.countdown(cfg, now: ctx.date)
            let vs = widgetValueSize(size, width: width)
            VStack(alignment: .leading, spacing: 4) {
                switch c {
                case .days(let n, _):
                    WidgetValueText(text: String(localized: "\(n) Tage"), size: vs)
                case .hoursMinutes(let h, let m, _):
                    let small = Font.system(size: vs * 0.55, weight: .medium)
                    (Text(verbatim: "\(h)") + Text(verbatim: "\u{00A0}") + Text("Std.").font(small).foregroundStyle(.secondary)
                     + Text(verbatim: " \(m)") + Text(verbatim: "\u{00A0}") + Text("Min.").font(small).foregroundStyle(.secondary))
                        .font(.system(size: vs, weight: .semibold)).monospacedDigit().lineLimit(1).minimumScaleFactor(0.4)
                case .today:
                    Text("Heute").font(.system(size: vs, weight: .semibold)).lineLimit(1).minimumScaleFactor(0.5)
                case .reached:
                    Text("Ziel erreicht").font(.system(size: vs * 0.75, weight: .semibold)).lineLimit(1).minimumScaleFactor(0.5)
                case .notYet:
                    Text("Noch nicht erreicht").font(.system(size: vs * 0.75)).foregroundStyle(.secondary).lineLimit(1).minimumScaleFactor(0.5)
                case .invalid:
                    EmptyView()
                }
                if c != .invalid, let t = WidgetLogic.target(cfg, calendar: SagaDate.calendar) {
                    let time = (cfg["time"] as? String) ?? ""
                    let ds = fmtDate(t, "EEEdMMMyyyy", locale: locale) + (time.isEmpty ? "" : " · " + time)
                    WidgetSub(text: (cfg["dir"] as? String) == "since" ? String(localized: "seit \(ds)") : String(localized: "bis \(ds)"))
                }
            }
        }
    }
}

// MARK: - Etappe 2 (weather1, habit1, upcoming1)

private struct WeatherWidget: View {
    let value: WidgetLogic.Value?
    let locale: Locale
    @Environment(\.widgetSize) private var size
    @Environment(\.widgetWidth) private var width
    var body: some View {
        if let n = Widget2Logic.weatherNotice(value?.error) {
            Text(verbatim: "⚠ " + n).font(.subheadline).foregroundStyle(WGColor.down)
        } else if let w = Widget2Logic.weather(value?.dict) {
            let unit = w.units == "imperial" ? "°F" : "°C"
            let days = Array(w.days.dropFirst())
            VStack(alignment: .leading, spacing: 8) {
                HStack(alignment: .center, spacing: 8) {
                    symbol(w.code).font(.system(size: 30)).frame(width: 36)
                    WidgetValueText(text: (w.now.map { NumFormat.text($0.rounded(), fmt: "num", locale: locale) } ?? "–") + "\u{00A0}" + unit,
                                    size: widgetValueSize(size, width: width))
                }
                WidgetSub(text: meta(w))
                if !days.isEmpty {
                    if WidgetLogic.weatherList(width: Double(width)) { list(days) } else { tiles(days) }
                }
                Spacer(minLength: 0)
                Text(verbatim: String(localized: "Wetterdaten: Open-Meteo.com") + (w.stale ? " · " + String(localized: "Stand von vorhin") : ""))
                    .font(.caption2).foregroundStyle(.secondary)   // Pflichtangabe (CC BY 4.0)
            }
        } else { Text(verbatim: "…").foregroundStyle(.secondary) }
    }
    /// Ort · Wetterlage · Wind
    private func meta(_ w: Widget2Logic.Weather) -> String {
        var parts: [String] = []
        if !w.place.isEmpty { parts.append(w.place) }
        parts.append(Widget2Logic.weatherText(w.code))
        if let wind = w.wind {
            let u = w.units == "imperial" ? "mph" : "km/h"
            parts.append(String(localized: "Wind \(NumFormat.text(wind.rounded(), fmt: "num", locale: locale) + "\u{00A0}" + u)"))
        }
        return parts.joined(separator: " · ")
    }
    /// Sonne in Akzent, Wolken/Regen in Textfarbe (wie die Linien-Symbole im Web)
    private func symbol(_ code: Int) -> some View {
        let s = Widget2Logic.weatherSymbol(code)
        return Image(systemName: s).symbolRenderingMode(.palette).foregroundStyle(s.hasPrefix("sun") ? AnyShapeStyle(.tint) : AnyShapeStyle(.primary), AnyShapeStyle(.tint))
            .accessibilityLabel(Text(verbatim: Widget2Logic.weatherText(code)))
    }
    private func temps(_ d: Widget2Logic.Weather.Day) -> Text {
        Text(verbatim: (d.max.map { "\(Int($0.rounded()))°" } ?? "–") + " ")
            + Text(verbatim: d.min.map { "\(Int($0.rounded()))°" } ?? "–").foregroundStyle(.secondary)
    }
    private func rain(_ d: Widget2Logic.Weather.Day) -> String { (d.rain ?? 0) > 0 ? "💧\(d.rain!)\u{00A0}%" : "" }
    private func weekday(_ s: String) -> String { dayDate(s).map { fmtDate($0, "EEE", locale: locale) } ?? "" }
    /// breit: Kacheln nebeneinander
    private func tiles(_ days: [Widget2Logic.Weather.Day]) -> some View {
        HStack(spacing: 4) {
            ForEach(Array(days.enumerated()), id: \.offset) { _, d in
                VStack(spacing: 2) {
                    Text(verbatim: weekday(d.d)).foregroundStyle(.secondary)
                    symbol(d.code).font(.system(size: 20)).frame(height: 26)
                    temps(d).monospacedDigit().lineLimit(1).minimumScaleFactor(0.7)
                    Text(verbatim: rain(d)).font(.caption2).foregroundStyle(.secondary).lineLimit(1)
                }
                .font(.footnote).frame(maxWidth: .infinity).padding(.vertical, 5)
                .background(Color.primary.opacity(0.04), in: RoundedRectangle(cornerRadius: 6))
            }
        }
    }
    /// schmal: Liste Tag | Symbol | Regen | max/min
    private func list(_ days: [Widget2Logic.Weather.Day]) -> some View {
        VStack(spacing: 0) {
            ForEach(Array(days.enumerated()), id: \.offset) { i, d in
                if i > 0 { Divider() }
                HStack(spacing: 6) {
                    Text(verbatim: weekday(d.d)).foregroundStyle(.secondary).frame(width: 34, alignment: .leading)
                    symbol(d.code).frame(width: 22)
                    if width > 180 { Text(verbatim: rain(d)).font(.caption2).foregroundStyle(.secondary).lineLimit(1) }
                    Spacer(minLength: 4)
                    temps(d).monospacedDigit().lineLimit(1)
                }
                .font(.footnote).padding(.vertical, 4)
            }
        }
    }
}

private struct HabitWidget: View {
    let value: WidgetLogic.Value?
    let blockID: String
    let locale: Locale
    @EnvironmentObject private var store: Store
    @EnvironmentObject private var ed: PageEditor
    @Environment(\.widgetSize) private var size
    @Environment(\.widgetWidth) private var width
    @State private var local: Widget2Logic.Habit?
    @State private var busy = false
    var body: some View {
        if let h = local ?? Widget2Logic.habit(value?.dict) {
            let L = WidgetLogic.habitLayout(days: h.days.count, width: Double(width), size: size)
            let first = max(0, h.days.count - L.shown)
            let idx = Array(first..<h.days.count)
            VStack(alignment: .leading, spacing: size == .s ? 6 : size == .l ? 12 : 9) {
                if L.header && !h.habits.isEmpty { row(name: nil, stats: nil, L: L) { cells(idx, L: L) { i in headCell(h, i, L: L) } } }
                ForEach(h.habits, id: \.pid) { it in
                    row(name: it.name, stats: stats(it), L: L) {
                        cells(idx, L: L) { i in cell(h, it, i, L: L) }
                    }
                }
                if let n = L.hint { Text("letzte \(n) Tage").font(.caption).foregroundStyle(.secondary) }
                if !h.canCheck { Text("Nur lesen – zum Abhaken braucht es „Bearbeiten“ an der Datenbank.").font(.caption).foregroundStyle(.secondary) }
            }
            .onChange(of: value?.data) { _, _ in local = nil }
        } else { Text(verbatim: "…").foregroundStyle(.secondary) }
    }

    /// Feste Zellmasse aus Kartenbreite und Anzahl (Logik in WidgetLogic.habitGeom, getestet)
    private func geom(_ L: WidgetLogic.HabitLayout) -> WidgetLogic.HabitGeom {
        WidgetLogic.habitGeom(width: Double(width), layout: L, size: size)
    }

    /// Breit: Name | Tage | Quote in einer Zeile; sonst Name + Quote oben, Tage darunter ueber die volle Breite
    @ViewBuilder private func row<C: View>(name: String?, stats: AnyView?, L: WidgetLogic.HabitLayout, @ViewBuilder cells: () -> C) -> some View {
        if L.oneLine {
            HStack(spacing: WidgetLogic.habitRowGap) {
                Text(verbatim: name ?? "").font(.subheadline).lineLimit(1).truncationMode(.tail)
                    .frame(width: geom(L).name, alignment: .leading)
                cells()
                Group { if let stats { stats } else { Color.clear } }.frame(width: WidgetLogic.habitStatsWidth, alignment: .trailing)
            }
        } else {
            VStack(alignment: .leading, spacing: 4) {
                if let name {
                    HStack(alignment: .firstTextBaseline, spacing: 8) {
                        Text(verbatim: name).font(.subheadline).lineLimit(1).truncationMode(.tail)
                        Spacer(minLength: 4)
                        stats
                    }
                }
                cells()
            }
        }
    }

    /// Tage: Kreise ueber die volle Breite verteilt (heute rechts), Heatmap linksbuendig dicht
    @ViewBuilder private func cells<C: View>(_ idx: [Int], L: WidgetLogic.HabitLayout, @ViewBuilder _ one: @escaping (Int) -> C) -> some View {
        // Feste quadratische Zellen (frame width = height). Vorher frame(maxWidth:).aspectRatio(1): die Hoehe folgte
        // der vom HStack angebotenen Restbreite und wuchs nach rechts (iPad, breite Karte).
        let g = geom(L), s = CGFloat(g.cell)
        Group {
            if L.heat {
                HStack(spacing: CGFloat(g.gap)) {
                    ForEach(idx, id: \.self) { i in one(i).frame(width: s, height: s) }
                    Spacer(minLength: 0)
                }
            } else {
                HStack(spacing: 0) {
                    ForEach(Array(idx.enumerated()), id: \.element) { k, i in
                        if k > 0 { Spacer(minLength: CGFloat(g.gap)) }
                        one(i).frame(width: s, height: s)
                    }
                }
            }
        }
        .padding(CGFloat(WidgetLogic.habitRingPad))        // Platz fuer den Ring um heute
    }

    /// Kopf: Wochentag (7 Tage) bzw. Tag des Monats alle 5 Tage und heute (Heatmap)
    private func headCell(_ h: Widget2Logic.Habit, _ i: Int, L: WidgetLogic.HabitLayout) -> some View {
        let day = h.days[i]
        var t = ""
        if let d = dayDate(day) {
            if L.heat {
                let n = SagaDate.calendar.component(.day, from: d)
                t = n % 5 == 0 || day == h.today ? "\(n)" : ""
            } else { t = String(fmtDate(d, "EEE", locale: locale).prefix(2)) }
        }
        return Text(verbatim: t).font(.caption2).foregroundStyle(.secondary).lineLimit(1).fixedSize()
            .frame(maxWidth: .infinity).accessibilityHidden(true)
    }

    /// "43 %" + Flamme + Serie
    private func stats(_ it: Widget2Logic.Habit.Item) -> AnyView {
        AnyView(HStack(alignment: .firstTextBaseline, spacing: 5) {
            Text(verbatim: WidgetLogic.format(Double(it.pct), fmt: "pct", locale: locale)).font(.footnote.weight(.semibold))
            HStack(spacing: 1) {
                Image(systemName: "flame.fill").font(.system(size: 10)).foregroundStyle(HKPalette.textColor("orange"))
                Text(verbatim: "\(it.streak)")
            }.font(.caption).foregroundStyle(.secondary)
        }
        .monospacedDigit().fixedSize()
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text("Erfolgsquote \(WidgetLogic.format(Double(it.pct), fmt: "pct", locale: locale)), \(it.streak) Tage in Folge")))
    }

    private func cell(_ h: Widget2Logic.Habit, _ it: Widget2Logic.Habit.Item, _ i: Int, L: WidgetLogic.HabitLayout) -> some View {
        let day = h.days[i], on = it.done[safe: i] ?? false, today = day == h.today
        return Button { toggle(it.pid, day, !on) } label: {
            Group {
                if L.heat {
                    RoundedRectangle(cornerRadius: 3).fill(on ? AnyShapeStyle(.tint) : AnyShapeStyle(WGColor.track))
                } else {
                    Circle().fill(on ? AnyShapeStyle(.tint) : AnyShapeStyle(Color.clear))
                        .overlay { Circle().strokeBorder(on ? AnyShapeStyle(.tint) : AnyShapeStyle(WGColor.field), lineWidth: 1.5) }
                }
            }
            .overlay {
                if today {
                    (L.heat ? AnyShape(RoundedRectangle(cornerRadius: 4)) : AnyShape(Circle()))
                        .stroke(.tint, lineWidth: 2).padding(L.heat ? -2 : -3)
                }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain).disabled(!h.canCheck || busy)
        .accessibilityLabel(Text(verbatim: it.name + ", " + (dayDate(day).map { fmtDate($0, "dMMM", locale: locale) } ?? day)))
        .accessibilityValue(on ? Text("erledigt") : Text("offen"))
        .accessibilityAddTraits(on ? .isSelected : [])
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

/// Anstehendes: Tagekoepfe ("HEUTE", "MI., 11. NOV."), einheitliche 16-pt-Symbolspalte (Aufgabe = Kreis, Termin = Punkt),
/// Titel einzeilig, Zeit rechts; ueberfaellig nur das Datum rot. Groesse S: 4 Eintraege ohne Tagekoepfe.
private struct UpcomingWidget: View {
    let value: WidgetLogic.Value?
    let locale: Locale
    @EnvironmentObject private var nav: NavModel
    @Environment(\.widgetSize) private var size
    @Environment(\.widgetWidth) private var width
    var body: some View {
        if let u0 = Widget2Logic.upcoming(value?.dict) {
            let small = size == .s
            let (u, more) = Widget2Logic.upcomingShown(u0, small: small)
            VStack(alignment: .leading, spacing: 0) {
                if u.items.isEmpty { Text("Nichts in den nächsten \(u.days) Tagen").font(.subheadline).foregroundStyle(.secondary) }
                ForEach(Array(Widget2Logic.sections(u, today: u.from).enumerated()), id: \.offset) { k, sec in
                    if !small {
                        Text(verbatim: title(sec.0).uppercased(with: locale)).font(.caption2.weight(.semibold)).tracking(0.4)
                            .foregroundStyle(sec.0 == .overdue ? WGColor.down : .secondary)
                            .padding(.top, k == 0 ? 0 : 7).padding(.bottom, 2)
                            .accessibilityAddTraits(.isHeader)
                    }
                    ForEach(Array(sec.1.enumerated()), id: \.offset) { _, it in item(it, sec.0, small: small) }
                }
                if more > 0 { Text("und \(more) weitere").font(.caption).foregroundStyle(.secondary).padding(.top, 4) }
                if u.errors > 0 { Text("\(u.errors) Quellen nicht erreichbar").font(.caption).foregroundStyle(WGColor.down).padding(.top, 2) }
            }
        } else { Text(verbatim: "…").foregroundStyle(.secondary) }
    }
    private func item(_ it: Widget2Logic.Upcoming.Item, _ sec: Widget2Logic.Section, small: Bool) -> some View {
        let tm = timeText(it, sec, small: small)
        return Button { if let n = it.note { nav.open(n, mode: "side") } } label: {
            HStack(alignment: size == .l ? .firstTextBaseline : .center, spacing: 8) {
                Group {
                    if it.k == "task" { Circle().strokeBorder(WGColor.field, lineWidth: 1.5).frame(width: 12, height: 12) }
                    else { Circle().fill(it.color.flatMap { Color(hex: $0) }.map { AnyShapeStyle($0) } ?? AnyShapeStyle(.tint)).frame(width: 8, height: 8) }
                }
                .frame(width: 16, height: 16).accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 0) {
                    Text(verbatim: it.title.isEmpty ? String(localized: "Unbenannt") : it.title).lineLimit(size == .l ? 2 : 1).truncationMode(.tail)
                    if size == .l, let c = it.cal, !c.isEmpty { Text(verbatim: c).font(.caption2).foregroundStyle(.secondary) }
                }
                Spacer(minLength: 4)
                if !tm.isEmpty && (it.overdue || width >= 200) {
                    Text(verbatim: tm).font(.caption.weight(it.overdue ? .semibold : .regular)).monospacedDigit()
                        .foregroundStyle(it.overdue ? WGColor.down : .secondary).lineLimit(1).fixedSize()
                }
            }
            .font(.subheadline).frame(minHeight: 28).contentShape(Rectangle())
        }
        .buttonStyle(.plain).disabled(it.note == nil)
        .accessibilityLabel(Text(verbatim: (it.k == "task" ? String(localized: "Aufgabe") : String(localized: "Termin")) + ": " + it.title + (tm.isEmpty ? "" : ", " + tm)))
    }
    /// Zeit rechts: Uhrzeit, "ganztägig" (Termin), ueberfaellig das Datum; Groesse S mit Tag davor
    private func timeText(_ it: Widget2Logic.Upcoming.Item, _ sec: Widget2Logic.Section, small: Bool) -> String {
        if it.overdue { return dayDate(it.start).map { fmtDate($0, "dMMM", locale: locale) } ?? "" }
        var tm = it.allday ? (it.k == "event" ? String(localized: "ganztägig") : "") : (DBDate.parse(it.start)?.start.instant.map { $0.formatted(date: .omitted, time: .shortened) } ?? "")
        if small {
            let day = title(sec).components(separatedBy: ",").first ?? ""
            tm = day + (!it.allday && !tm.isEmpty ? " " + tm : "")
        }
        return tm
    }
    private func title(_ s: Widget2Logic.Section) -> String {
        switch s {
        case .overdue: return String(localized: "Überfällig")
        case .today: return String(localized: "Heute")
        case .tomorrow: return String(localized: "Morgen")
        case .day(let d): return dayDate(d).map { fmtDate($0, "EEE", locale: locale) + ", " + fmtDate($0, "dMMM", locale: locale) } ?? d
        }
    }
}
