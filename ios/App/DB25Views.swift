import SwiftUI
import UniformTypeIdentifiers
import Charts

// Saga 2.5 App-Runde Etappe 1: Anzeige von Formeln/Rollups, Personen, Dateien, Status, Datum mit Uhrzeit,
// Diagramm-Ansicht (Swift Charts), Zeitleiste (nur lesen), Kalender-Woche. Regeln in DB25Logic.swift / DBDates.swift.

// MARK: - Palette wie Web-CSS (--tc-* Text, --bc-* Hintergrund), hell/dunkel

enum HKPalette {
    private static let text: [String: (UInt32, UInt32)] = [
        "gray": (0x686763, 0xb0afab), "brown": (0x8a5a44, 0xc9a08b), "orange": (0xa65300, 0xf0a35e), "yellow": (0x7f6200, 0xe6c54f),
        "green": (0x0b6e60, 0x4fc4a9), "blue": (0x0b6893, 0x62b4e2), "purple": (0x6940a5, 0xb893e6), "pink": (0xa8186e, 0xe682bb), "red": (0xc22a2a, 0xf28585)]
    private static let bg: [String: UInt32] = ["gray": 0x9b9a97, "brown": 0x9f6b53, "orange": 0xd9730d, "yellow": 0xdfab01, "green": 0x0f7b6c,
                                               "blue": 0x0b6e99, "purple": 0x6940a5, "pink": 0xad1a72, "red": 0xe03e3e]
    static func textColor(_ name: String?) -> Color {
        guard let name, let c = text[name] else { return .primary }
        return .adaptive(c.0, c.1)
    }
    /// Hintergrund: Grundfarbe mit 20 % (hell) bzw. 30 % (dunkel) wie color-mix im Web
    static func bgColor(_ name: String?) -> Color {
        guard let name, let c = bg[name] else { return .clear }
        let base = Color.adaptive(c, c)
        return base.opacity(0.25)
    }
    /// Farbe fuer Diagramme/Balken (Textfarbe der Palette)
    static func chart(_ name: String) -> Color { textColor(name) }
}

// MARK: - Zellen-Anzeige (Tabelle, Liste, Karten)

/// Formel-/Rollup-Zelle (nur lesen); Fehler als "#Fehler" mit Erklaerung
struct FxValueView: View {
    let prop: DBProp
    let value: Any?
    let err: String?
    var compact = false
    var body: some View {
        switch FxLogic.cell(prop, value: value, err: err) {
        case .empty: if !compact { Text(verbatim: "–").foregroundStyle(.tertiary) }
        case .number(let n):
            HStack(spacing: 6) {
                Text(numText(n, prop.fmt, prop.show)).monospacedDigit().font(compact ? .caption : .body).foregroundStyle(compact ? .secondary : .primary)
                if prop.show == "ring" && !compact { ProgressRing(value: n / 100) }
            }
        case .text(let s): Text(verbatim: s).lineLimit(compact ? 1 : 3).font(compact ? .caption : .body).foregroundStyle(compact ? .secondary : .primary)
        case .check(let b): Image(systemName: b ? "checkmark.square.fill" : "square").foregroundStyle(b ? Color.accentColor : .secondary)
        case .date(let d): Text(verbatim: DBDate.display(d) ?? d).font(compact ? .caption : .body).foregroundStyle(.secondary)
        case .list(let a):
            if compact { Text(verbatim: a.joined(separator: ", ")).font(.caption).foregroundStyle(.secondary).lineLimit(1) }
            else { WrapLayout { ForEach(Array(a.enumerated()), id: \.offset) { Chip(text: $0.element, color: nil) } } }
        case .error(let code):
            Text(verbatim: "#Fehler").font(compact ? .caption.monospaced() : .body.monospaced()).foregroundStyle(.red)
                .help(FxLogic.errorHint(code)).accessibilityLabel(Text(verbatim: "#Fehler, " + FxLogic.errorHint(code)))
        }
    }
}

/// Personen als Chips (Avatar + Name, "ich" hervorgehoben, geloescht ausgegraut) - nie Anmeldenamen
struct PeopleChips: View {
    let refs: Any?
    let people: [String: [String: Any]]
    var compact = false
    var body: some View {
        let ps = PeopleLogic.persons(refs, PeopleLogic.directory(people))
        WrapLayout(spacing: 4) {
            ForEach(ps) { p in
                HStack(spacing: 4) {
                    AvatarView(name: p.gone ? "?" : p.name, avatar: p.avatar, size: compact ? 16 : 20)
                    Text(verbatim: PeopleLogic.label(p)).font(.caption.weight(p.me ? .semibold : .regular)).lineLimit(1)
                }
                .padding(.trailing, 6).padding(2)
                .background((p.me ? Color.accentColor : .secondary).opacity(p.gone ? 0.06 : 0.14), in: Capsule())
                .opacity(p.gone ? 0.55 : 1)
            }
        }
    }
}

/// Status-Chip: Gruppensymbol vor dem Namen (○ ◐ ●)
struct StatusChip: View {
    let prop: DBProp
    let value: Any?
    var body: some View {
        if let id = value as? String, let o = prop.option(id) {
            Chip(text: (StatusLogic.symbols[o.group ?? "todo"] ?? "○") + " " + o.name, color: o.color)
        }
    }
}

/// Dateien einer Zelle: Symbol/Vorschaubild, Tippen oeffnet
struct FilesCell: View {
    let files: [DBFile]
    var compact = false
    @EnvironmentObject var store: Store
    var body: some View {
        WrapLayout(spacing: 4) {
            ForEach(files) { f in
                if let u = store.apiURL(String(f.url.dropFirst())) {
                    Link(destination: u) {
                        HStack(spacing: 4) {
                            if f.image && !compact { RemoteImage(path: f.url, fill: true, w: 80).frame(width: 20, height: 20).clipShape(RoundedRectangle(cornerRadius: 3)) }
                            else { Image(systemName: FilesLogic.symbol(f)) }
                            Text(verbatim: f.name.isEmpty ? "Datei" : f.name).lineLimit(1)
                        }
                        .font(.caption).padding(.horizontal, 6).padding(.vertical, 2)
                        .background(.secondary.opacity(0.12), in: RoundedRectangle(cornerRadius: 5))
                    }
                    .buttonStyle(.plain).help(f.name)
                }
            }
        }
    }
}

/// Datum (auch mit Uhrzeit/Zeitraum) in der Zeitzone des Betrachters; Originalzeit im Tooltip
struct DateValueText: View {
    let value: Any?
    var body: some View {
        if let s = value as? String, !s.isEmpty {
            Text(verbatim: DBDate.display(s) ?? s).help(DBDate.originalHint(s) ?? "")
        }
    }
}

// MARK: - Bearbeiten: Personen, Dateien, Datum mit Uhrzeit

/// Personen waehlen (GET /api/notes/{db}/people?q=); Ergebnis: ganze Liste der Kennungen
struct PeoplePicker: View {
    let dbID: Int
    let multi: Bool
    let current: [String]
    let known: [String: [String: Any]]
    let done: ([String]?) -> Void
    @EnvironmentObject var store: Store
    @State private var q = ""
    @State private var found: [DBPerson] = []
    @State private var sel: [String] = []
    @State private var extra: [String: [String: Any]] = [:]
    var body: some View {
        NavigationStack {
            List {
                if !sel.isEmpty {
                    Section("Ausgewählt") {
                        ForEach(PeopleLogic.persons(sel, PeopleLogic.directory(known.merging(extra) { a, _ in a }))) { p in row(p) }
                    }
                }
                Section {
                    ForEach(found.filter { !sel.contains($0.ref) }) { p in row(p) }
                    if found.isEmpty { Text("Keine Personen gefunden").foregroundStyle(.secondary) }
                }
            }
            .searchable(text: $q, prompt: Text("Name suchen"))
            .navigationTitle("Personen")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Abbrechen") { done(nil) } }
                ToolbarItem(placement: .confirmationAction) { Button("Fertig") { done(sel) } }
            }
            .task(id: q) {
                try? await Task.sleep(for: .milliseconds(q.isEmpty ? 0 : 250))
                let qq = q.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? ""
                if let d = try? await store.api("api/notes/\(dbID)/people?q=\(qq)") {
                    found = PeopleLogic.picker(d)
                    for p in found { extra[p.ref] = ["name": p.name, "avatar": p.avatar as Any, "me": p.me] }
                }
            }
        }
        .macFrame(minWidth: 360, minHeight: 420)
        .onAppear { sel = current }
    }
    private func row(_ p: DBPerson) -> some View {
        Button { sel = PeopleLogic.toggle(sel, p.ref, multi: multi) } label: {
            HStack {
                AvatarView(name: p.name, avatar: p.avatar, size: 26)
                Text(verbatim: PeopleLogic.label(p)); if p.me { Text("(ich)").foregroundStyle(.secondary) }
                Spacer()
                if sel.contains(p.ref) { Image(systemName: "checkmark").foregroundStyle(Color.accentColor) }
            }.contentShape(Rectangle())
        }.buttonStyle(.plain)
    }
}

/// Personen-Zelle: Chips, Tippen oeffnet die Auswahl (nur mit person1 und Bearbeiten)
struct PeopleEditor: View {
    let prop: DBProp
    let dbID: Int
    let value: Any?
    let people: [String: [String: Any]]
    let canEdit: Bool
    let set: ([String]) -> Void
    @State private var open = false
    var body: some View {
        let cur = ((value as? [Any]) ?? []).compactMap { $0 as? String }
        Button { if canEdit { open = true } } label: {
            if cur.isEmpty { Text("Leer").font(.subheadline).foregroundStyle(.tertiary) } else { PeopleChips(refs: cur, people: people) }
        }
        .buttonStyle(.plain).disabled(!canEdit)
        .sheet(isPresented: $open) {
            PeoplePicker(dbID: dbID, multi: prop.multi, current: cur, known: people) { r in open = false; if let r, r != cur { set(r) } }
        }
    }
}

/// Datei-Zelle mit Hinzufuegen/Entfernen (POST /api/files?note= + PATCH der ganzen Liste)
struct FilesEditor: View {
    let rowID: Int
    let value: Any?
    let max: Int
    let canEdit: Bool
    let set: ([[String: Any]]) -> Void
    @EnvironmentObject var store: Store
    @State private var pick = false
    @State private var busy = false
    @State private var err: String?
    var body: some View {
        let fs = FilesLogic.files(value)
        HStack(spacing: 6) {
            if fs.isEmpty && !canEdit { Text("Leer").font(.subheadline).foregroundStyle(.tertiary) }
            FilesCell(files: fs)
            if canEdit {
                if busy { ProgressView().controlSize(.small) }
                Menu {
                    Button { pick = true } label: { Label("Datei hinzufügen", systemImage: "plus") }.disabled(fs.count >= max)
                    if !fs.isEmpty {
                        Divider()
                        ForEach(fs) { f in Button(role: .destructive) { set(FilesLogic.stored(fs.filter { $0.url != f.url })) } label: { Label("„\(f.name)“ entfernen", systemImage: "trash") } }
                    }
                } label: { Image(systemName: "paperclip").foregroundStyle(.secondary) }
                .menuStyle(.button).buttonStyle(.plain).menuIndicator(.hidden).fixedSize().accessibilityLabel("Dateien")
            }
            if let err { Text(err).font(.caption).foregroundStyle(.orange) }
        }
        .fileImporter(isPresented: $pick, allowedContentTypes: [.item]) { r in
            guard case .success(let url) = r else { return }
            Task {
                busy = true; defer { busy = false }
                let ok = url.startAccessingSecurityScopedResource(); defer { if ok { url.stopAccessingSecurityScopedResource() } }
                do {
                    let data = try Data(contentsOf: url)
                    let f = try await store.uploadFile(data, name: url.lastPathComponent, note: rowID)
                    guard let u = f["url"] as? String else { return }
                    if let next = FilesLogic.adding(fs, url: u, name: (f["name"] as? String) ?? url.lastPathComponent, max: max) { set(FilesLogic.stored(next)); err = nil }
                    else { err = String(localized: "Höchstens \(max) Dateien") }
                } catch { err = error.localizedDescription }
            }
        }
    }
}

/// Datum mit Uhrzeit/Zeitraum bearbeiten (nur mit datetime1); ohne: wie bisher nur der Tag
struct DateTimeEditor: View {
    let prop: DBProp
    let value: Any?
    let set: (Any?) -> Void
    @State private var open = false
    var body: some View {
        let s = value as? String ?? ""
        HStack(spacing: 6) {
            Button { open = true } label: {
                if s.isEmpty { Text("Leer").font(.subheadline).foregroundStyle(.tertiary) } else { DateValueText(value: s).font(.subheadline) }
            }.buttonStyle(.plain)
            if !s.isEmpty { Button { set(nil) } label: { Image(systemName: "xmark.circle.fill").foregroundStyle(.tertiary) }.buttonStyle(.plain).accessibilityLabel("Datum entfernen") }
        }
        .settingsPopover(isPresented: $open, title: "Datum") { DateTimeSheet(prop: prop, value: s) { v in open = false; if let v, v != s { set(v) } } }
    }
}

struct DateTimeSheet: View {
    let prop: DBProp
    let value: String
    let done: (String?) -> Void
    @State private var start = Date()
    @State private var end = Date()
    @State private var withTime = false
    @State private var withEnd = false
    var body: some View {
        Form {
            DatePicker("Beginn", selection: $start, displayedComponents: withTime ? [.date, .hourAndMinute] : [.date])
            Toggle("Uhrzeit", isOn: $withTime)
            Toggle("Enddatum", isOn: $withEnd)
            if withEnd { DatePicker("Ende", selection: $end, in: start..., displayedComponents: withTime ? [.date, .hourAndMinute] : [.date]) }
            Section {
                Button("Übernehmen") {
                    done(DBDate.compose(start: start, startTime: withTime, end: withEnd ? max(end, start) : nil, endTime: withTime, old: value))
                }.keyboardShortcut(.defaultAction)
                Button("Abbrechen", role: .cancel) { done(nil) }
            }
        }
        .formStyle(.grouped)
        .macFrame(minWidth: 320, minHeight: 260)
        .onAppear {
            let v = DBDate.parse(value)
            withTime = v?.start.hasTime ?? prop.time
            withEnd = v?.end != nil || (v == nil && prop.range)
            var cal = Calendar(identifier: .gregorian); cal.timeZone = .current
            func date(_ p: DBDate.Part) -> Date { p.instant ?? cal.date(from: DateComponents(year: p.y, month: p.m, day: p.d, hour: 9))! }
            start = v.map { date($0.start) } ?? .now
            end = v?.end.map(date) ?? start.addingTimeInterval(3600)
        }
    }
}

// MARK: - Diagramm-Ansicht (chart1) mit Swift Charts

struct DBChartView: View {
    @ObservedObject var m: DBModel
    let view: [String: Any]?
    @EnvironmentObject var store: Store
    @State private var data: ChartData?
    @State private var err: String?
    @State private var loaded = false
    var body: some View {
        Group {
            if let data { ChartBody(data: data, height: 260) }
            else if let err { Label(err == "bad_cfg" ? String(localized: "Diagramm ist nicht vollständig eingerichtet – im Web bearbeiten") : String(localized: "Diagramm konnte nicht geladen werden"), systemImage: "chart.bar.xaxis").foregroundStyle(.secondary) }
            else if !loaded { ProgressView().frame(maxWidth: .infinity, minHeight: 120) }
        }
        .task(id: "\(view?["id"] as? String ?? "")-\(store.dataTick)") { await load() }
    }
    private func load() async {
        let vid = (view?["id"] as? String ?? "").addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? ""
        let tz = TimeZone.current.identifier.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? "UTC"
        var path = "api/notes/\(m.dbID)/chart?view=\(vid)&tz=\(tz)"
        if m.localViews != nil, let spec = view?["chart"], let j = try? JSONSerialization.data(withJSONObject: spec),   // eingebettete Ansicht
           let s = String(data: j, encoding: .utf8)?.addingPercentEncoding(withAllowedCharacters: .alphanumerics) {
            path += "&chart=\(s)"
            if let fs = view?["filters"], let fj = try? JSONSerialization.data(withJSONObject: fs), let f = String(data: fj, encoding: .utf8)?.addingPercentEncoding(withAllowedCharacters: .alphanumerics) { path += "&filters=\(f)" }
        }
        do {
            let (d, code) = try await store.apiResponse(path)
            if code == 404 { err = "bad"; data = nil } else { let r = ChartData.response(d); data = r.0; err = r.1 }
        } catch { if data == nil { err = "bad" } }
        loaded = true
    }
}

/// Diagramm zeichnen (auch fuer das Widget): Balken, waagrechte Balken, Linie, Flaeche, Kreis, Ring, Zahl; Werte als Tabelle
struct ChartBody: View {
    let data: ChartData
    var height: CGFloat = 220
    @State private var showTable = false
    private struct Pt: Identifiable { let id: Int; let i: Int; let cat: String; let series: String; let s: Int; let v: Double }
    private var points: [Pt] {
        var out: [Pt] = []
        for (j, s) in data.series.enumerated() {
            var acc = 0.0
            for i in data.cats.indices {
                let raw = s.values.indices.contains(i) ? s.values[i] : nil
                guard var v = raw else { continue }
                if data.cumulative { acc += v; v = acc }
                out.append(Pt(id: j * 10000 + i, i: i, cat: data.label(i), series: s.label.isEmpty ? " " : s.label, s: j, v: v))
            }
        }
        return out
    }
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            switch data.type {
            case "number":
                Text(verbatim: NumFormat.text(data.total, fmt: data.fmt)).font(.system(size: 40, weight: .semibold)).monospacedDigit()
                if let g = data.goal { Text("Ziel: \(NumFormat.text(g, fmt: data.fmt))").font(.caption).foregroundStyle(.secondary) }
            case "pie", "donut": pie
            default: cartesian
            }
            if data.truncated || data.other > 0 { Text("Weitere Werte als „Andere“ zusammengefasst").font(.caption2).foregroundStyle(.tertiary) }
            if data.type != "number" && !data.cats.isEmpty {
                DisclosureGroup("Werte als Tabelle", isExpanded: $showTable) { table }.font(.caption)
            }
        }
    }
    @ViewBuilder private var cartesian: some View {
        let pts = points, labels = data.cats.indices.map { data.label($0) }
        let lo = data.range.0, hi = data.cumulative ? max(data.range.1, pts.map(\.v).max() ?? 0) : data.range.1
        let ticks = ChartData.ticks(min(lo, 0), max(hi, 0))
        Chart {
            ForEach(pts) { p in
                let x = PlottableValue.value("Kategorie", p.cat), y = PlottableValue.value("Wert", p.v)
                switch data.type {
                case "hbar":
                    BarMark(x: .value("Wert", p.v), y: .value("Kategorie", p.cat)).foregroundStyle(color(p))
                        .position(by: .value("Reihe", data.stack || data.single ? "" : p.series))
                case "line":
                    LineMark(x: x, y: y).foregroundStyle(by: .value("Reihe", p.series)).symbol(by: .value("Reihe", p.series)).interpolationMethod(.monotone)
                case "area":
                    AreaMark(x: x, y: y, stacking: data.stack ? .standard : .unstacked).foregroundStyle(by: .value("Reihe", p.series)).opacity(0.5)
                    LineMark(x: x, y: y).foregroundStyle(by: .value("Reihe", p.series))
                default:
                    BarMark(x: x, y: y).foregroundStyle(color(p))
                        .position(by: .value("Reihe", data.stack || data.single ? "" : p.series))
                }
            }
            if let g = data.goal {
                if data.type == "hbar" { RuleMark(x: .value("Ziel", g)).foregroundStyle(.red).lineStyle(StrokeStyle(lineWidth: 1.5, dash: [4, 3])) }
                else { RuleMark(y: .value("Ziel", g)).foregroundStyle(.red).lineStyle(StrokeStyle(lineWidth: 1.5, dash: [4, 3])) }
            }
        }
        .chartForegroundStyleScale(domain: data.series.map { $0.label.isEmpty ? " " : $0.label }, range: data.series.indices.map { HKPalette.chart(data.seriesColor($0)) })
        .chartLegend(data.single ? .hidden : .visible)
        .modifier(AxisTicks(ticks: ticks, horizontal: data.type == "hbar", fmt: data.fmt, labels: labels))
        .frame(height: data.type == "hbar" ? max(height, CGFloat(labels.count) * 26 + 40) : height)
    }
    private func color(_ p: Pt) -> Color {
        data.single ? HKPalette.chart(data.catColor(p.i)) : HKPalette.chart(data.seriesColor(p.s))
    }
    @ViewBuilder private var pie: some View {
        let vals = data.cats.indices.compactMap { i -> (Int, Double)? in
            guard let v = data.series.first?.values[safe: i] ?? nil, v > 0 else { return nil }; return (i, v)
        }
        HStack(alignment: .center, spacing: 16) {
            Chart(vals, id: \.0) { p in
                SectorMark(angle: .value("Wert", p.1), innerRadius: .ratio(data.type == "donut" ? 0.58 : 0), angularInset: 1)
                    .foregroundStyle(HKPalette.chart(data.catColor(p.0)))
            }
            .frame(width: min(height, 200), height: min(height, 200))
            .overlay { if data.type == "donut" { Text(verbatim: NumFormat.text(data.total, fmt: data.fmt)).font(.headline).monospacedDigit() } }
            VStack(alignment: .leading, spacing: 4) {
                ForEach(vals, id: \.0) { p in
                    HStack(spacing: 6) {
                        RoundedRectangle(cornerRadius: 2).fill(HKPalette.chart(data.catColor(p.0))).frame(width: 10, height: 10)
                        Text(verbatim: data.label(p.0)).lineLimit(1)
                        Spacer(minLength: 4)
                        Text(verbatim: NumFormat.text(p.1, fmt: data.fmt)).monospacedDigit().foregroundStyle(.secondary)
                    }.font(.caption)
                }
            }
        }
    }
    private var table: some View {
        Grid(alignment: .leading, horizontalSpacing: 12, verticalSpacing: 3) {
            GridRow {
                Text(verbatim: "")
                ForEach(Array(data.series.enumerated()), id: \.offset) { Text(verbatim: $0.element.label.isEmpty ? String(localized: "Wert") : $0.element.label).bold() }
            }
            ForEach(data.cats.indices, id: \.self) { i in
                GridRow {
                    Text(verbatim: data.label(i))
                    ForEach(Array(data.series.enumerated()), id: \.offset) { s in
                        Text(verbatim: (s.element.values[safe: i] ?? nil).map { NumFormat.text($0, fmt: data.fmt) } ?? "–").monospacedDigit()
                    }
                }
            }
        }
        .padding(.top, 4)
    }
}

/// Achsenwerte wie im Web (1/2/2,5/5 x 10^k)
private struct AxisTicks: ViewModifier {
    let ticks: [Double]
    let horizontal: Bool
    let fmt: String
    let labels: [String]
    func body(content: Content) -> some View {
        let dom = (ticks.first ?? 0)...(ticks.last ?? 1)
        if horizontal {
            content
                .chartXScale(domain: dom).chartYScale(domain: labels)
                .chartXAxis { AxisMarks(values: ticks) { v in AxisGridLine(); AxisValueLabel { Text(verbatim: NumFormat.text(v.as(Double.self), fmt: fmt)) } } }
        } else {
            content
                .chartYScale(domain: dom).chartXScale(domain: labels)
                .chartYAxis { AxisMarks(values: ticks) { v in AxisGridLine(); AxisValueLabel { Text(verbatim: NumFormat.text(v.as(Double.self), fmt: fmt)) } } }
        }
    }
}

// MARK: - Zeitleiste (timeline1, nur lesen)

struct DBTimelineView: View {
    @ObservedObject var m: DBModel
    let view: [String: Any]?
    @EnvironmentObject var nav: NavModel
    #if os(iOS)
    @Environment(\.horizontalSizeClass) private var hsc
    private var phone: Bool { hsc == .compact }
    #else
    private let phone = false
    #endif
    private var dayW: CGFloat {
        switch view?["scale"] as? String { case "day": return 44; case "month": return 6; case "quarter": return 3; default: return 16 }
    }
    var body: some View {
        let rows = m.rows(for: view)
        let tl = TimelineLogic.bars(m.query, rows: rows, view: view)
        let today = DBDate.todayNumber(nil, tz: .current)
        VStack(alignment: .leading, spacing: 8) {
            if phone {
                ForEach(TimelineLogic.byStart(tl.bars), id: \.day) { g in
                    Text(verbatim: DBDate.display(DBDate.iso(g.day)) ?? DBDate.iso(g.day)).font(.caption.weight(.semibold)).foregroundStyle(g.day == today ? Color.accentColor : .secondary)
                    ForEach(g.bars, id: \.id) { b in
                        Button { open(b.id, rows) } label: {
                            HStack {
                                Text(b.title.isEmpty ? String(localized: "Unbenannt") : b.title).lineLimit(2)
                                Spacer()
                                if b.end > b.start { Text("\(b.end - b.start + 1) Tage").font(.caption).foregroundStyle(.secondary) }
                            }.padding(.vertical, 4).contentShape(Rectangle())
                        }.buttonStyle(.plain)
                    }
                }
            } else {
                let (lo, hi) = TimelineLogic.span(tl.bars, today: today)
                ScrollView(.horizontal) {
                    VStack(alignment: .leading, spacing: 4) {
                        ZStack(alignment: .topLeading) {                            // Kopf: Monatsanfaenge
                            Color.clear.frame(width: CGFloat(hi - lo + 1) * dayW, height: 18)
                            ForEach(Array(stride(from: lo, through: hi, by: 1)).filter { DBDate.civil($0).2 == 1 || $0 == lo }, id: \.self) { d in
                                Text(verbatim: DBDate.display(DBDate.iso(d)) ?? "").font(.caption2).foregroundStyle(.secondary).fixedSize()
                                    .offset(x: CGFloat(d - lo) * dayW)
                            }
                        }
                        ZStack(alignment: .topLeading) {
                            Rectangle().fill(Color.accentColor.opacity(0.5)).frame(width: 1.5, height: CGFloat(max(tl.bars.count, 1)) * 30)
                                .offset(x: CGFloat(today - lo) * dayW + dayW / 2)
                            VStack(alignment: .leading, spacing: 6) {
                                ForEach(tl.bars, id: \.id) { b in
                                    Button { open(b.id, rows) } label: {
                                        Text(b.title.isEmpty ? String(localized: "Unbenannt") : b.title).font(.caption).lineLimit(1).fixedSize()
                                            .padding(.horizontal, 6).frame(minWidth: CGFloat(b.end - b.start + 1) * dayW, minHeight: 24, alignment: .leading)
                                            .background(barColor(b.id, rows).opacity(0.3), in: RoundedRectangle(cornerRadius: 5))
                                            .overlay(RoundedRectangle(cornerRadius: 5).stroke(barColor(b.id, rows).opacity(0.6)))
                                    }
                                    .buttonStyle(.plain).offset(x: CGFloat(b.start - lo) * dayW)
                                }
                            }
                        }
                    }
                    .padding(.bottom, 6)
                }
                .defaultScrollAnchor(UnitPoint(x: Double(today - lo) / Double(max(1, hi - lo)), y: 0))
            }
            if !tl.undated.isEmpty {
                DisclosureGroup("Ohne Termin (\(tl.undated.count))") {
                    ForEach(rows.filter { tl.undated.contains($0.id) }) { r in
                        Button { open(r.id, rows) } label: { Text(r.title.isEmpty ? String(localized: "Unbenannt") : r.title).frame(maxWidth: .infinity, alignment: .leading) }.buttonStyle(.plain)
                    }
                }.font(.subheadline)
            }
            Text("Zeitleiste – Ziehen und Verlängern im Web").font(.caption2).foregroundStyle(.tertiary)
        }
    }
    private func barColor(_ id: Int, _ rows: [DBRow]) -> Color {
        rows.first { $0.id == id }.flatMap { m.color($0, view: view) } ?? .accentColor
    }
    private func open(_ id: Int, _ rows: [DBRow]) { nav.open(id, mode: view?["open"] as? String ?? "side", list: rows.map(\.id)) }
}

// MARK: - Kalender-Woche (range: week)

struct DBWeekView: View {
    @ObservedObject var m: DBModel
    let view: [String: Any]?
    let prop: DBProp
    @EnvironmentObject var nav: NavModel
    @State private var anchor = DBDate.todayNumber(nil, tz: .current)
    #if os(iOS)
    @Environment(\.horizontalSizeClass) private var hsc
    private var phone: Bool { hsc == .compact }
    #else
    private let phone = false
    #endif
    var body: some View {
        let rows = m.rows(for: view), days = TimelineLogic.week(of: anchor), today = DBDate.todayNumber(nil, tz: .current)
        VStack(spacing: 6) {
            HStack {
                Button { anchor -= 7 } label: { Image(systemName: "chevron.left") }.accessibilityLabel("Vorherige Woche")
                Text(verbatim: (DBDate.display(DBDate.iso(days[0])) ?? "") + " – " + (DBDate.display(DBDate.iso(days[6])) ?? "")).font(.headline)
                Button { anchor += 7 } label: { Image(systemName: "chevron.right") }.accessibilityLabel("Nächste Woche")
                Spacer()
                Button("Heute") { anchor = today }.font(.caption)
            }.buttonStyle(.plain)
            let layout = phone ? AnyLayout(VStackLayout(alignment: .leading, spacing: 8)) : AnyLayout(HStackLayout(alignment: .top, spacing: 4))
            layout {
                ForEach(Array(days.enumerated()), id: \.offset) { k, d in
                    let items = TimelineLogic.rows(on: d, m.query, rows: rows, prop: prop)
                    VStack(alignment: .leading, spacing: 3) {
                        Text(verbatim: DBCalendarView.weekdayHeads[k] + " " + String(DBDate.civil(d).2)).font(.caption.weight(d == today ? .bold : .regular))
                            .foregroundStyle(d == today ? Color.accentColor : .secondary)
                        ForEach(items) { r in
                            Button { nav.open(r.id, mode: view?["open"] as? String ?? "side", list: rows.map(\.id)) } label: {
                                let t = (m.value(prop, r) as? String).flatMap { DBDate.parse($0) }
                                let time = t?.start.instant.map { $0.formatted(date: .omitted, time: .shortened) + " " } ?? ""
                                Text(verbatim: time + (r.title.isEmpty ? String(localized: "Unbenannt") : r.title)).font(.caption).lineLimit(2)
                                    .padding(.horizontal, 4).padding(.vertical, 2).frame(maxWidth: .infinity, alignment: .leading)
                                    .background((m.color(r, view: view) ?? .accentColor).opacity(0.18), in: RoundedRectangle(cornerRadius: 4))
                            }.buttonStyle(.plain)
                        }
                        if items.isEmpty && phone { Text("–").font(.caption).foregroundStyle(.tertiary) }
                    }
                    .padding(4).frame(maxWidth: .infinity, minHeight: phone ? 0 : 120, alignment: .topLeading)
                    .background(.secondary.opacity(0.06), in: RoundedRectangle(cornerRadius: 6))
                }
            }
        }
    }
}

// MARK: - Mathe / Mermaid mit Vorschaubild (render1), synchronisierter Block

/// Vorschaubild vom Web (data.render.png), darunter "veraltet" bei stale; Quelltext bearbeitbar (render bleibt unveraendert
/// im Block - der Server markiert es dann als veraltet, das Web zeichnet neu)
struct RenderedSourceBlock: View {
    let data: NSMutableDictionary
    let key: String                       // "tex" (Mathe) bzw. "code" (Mermaid)
    let blockID: String
    @EnvironmentObject var ed: PageEditor
    @EnvironmentObject private var nav: NavModel
    @State private var showSource = false
    var body: some View {
        let d = data as? [String: Any] ?? [:]
        let r = RenderPreview.parse(d)
        VStack(alignment: .leading, spacing: 6) {
            if let r {
                RemoteImage(path: r.png)
                    .frame(maxWidth: r.w.map { CGFloat($0) } ?? .infinity, maxHeight: r.hgt.map { CGFloat($0) })
                    .aspectRatio(r.w.flatMap { w in r.hgt.map { CGFloat(w / max($0, 1)) } }, contentMode: .fit)
                    .padding(6).background(Color.white, in: RoundedRectangle(cornerRadius: 6))      // Bild hat weissen Grund
                    .frame(maxWidth: .infinity, alignment: key == "tex" ? .center : .leading)
                    .accessibilityLabel(Text(verbatim: data[key] as? String ?? ""))
                if r.stale {
                    HStack(spacing: 6) {
                        Label("Vorschau veraltet", systemImage: "exclamationmark.triangle").font(.caption).foregroundStyle(.orange)
                        Button("Im Web öffnen") { nav.peek = nil; nav.push(PageRoute(id: ed.doc.id, web: true, block: blockID)) }.buttonStyle(.bordered).controlSize(.mini)
                    }
                }
            }
            if r == nil || showSource || ed.focus == blockID {
                EditText(key: blockID, html: data[key] as? String ?? "", font: .system(.footnote, design: .monospaced),
                         placeholder: key == "tex" ? "TeX" : String(localized: "Code"), splitOnReturn: false, raw: true,
                         setHTML: { v in data[key] = v; ed.textChanged() }).id(blockID)
                    .padding(10).background(.secondary.opacity(0.12), in: RoundedRectangle(cornerRadius: 8))
            }
            if r != nil && !ed.locked {
                Button(showSource ? "Quelltext ausblenden" : "Quelltext bearbeiten") { showSource.toggle() }.font(.caption).buttonStyle(.plain).foregroundStyle(.secondary)
            }
        }
    }
}

/// Synchronisierter Block: Original zeigt den Inhalt, eine Kopie den vom Server eingesetzten Inhalt (data.src) mit Sprung
/// zum Original. Nur Anzeige - Bearbeiten im Web (Etappe 2); der Block bleibt beim Speichern unveraendert.
struct SyncedBlockView: View {
    let data: NSMutableDictionary
    let blockID: String
    @EnvironmentObject private var nav: NavModel
    @EnvironmentObject var store: Store
    var body: some View {
        let src = data["src"] as? [String: Any]
        let inner = (data["blocks"] as? [[String: Any]]) ?? []
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 6) {
                Image(systemName: "arrow.triangle.2.circlepath").foregroundStyle(.red.opacity(0.8))
                Text(src == nil ? "Synchronisierter Block" : "Synchronisierte Kopie").font(.caption).foregroundStyle(.secondary)
                Spacer()
                if let note = (src?["note"] as? Int) ?? (src?["note"] as? NSNumber)?.intValue {
                    Button { nav.open(note, mode: "page") } label: { Label("Zum Original", systemImage: "arrow.up.right") }.font(.caption).buttonStyle(.plain).foregroundStyle(Color.accentColor)
                }
            }
            if inner.isEmpty { Text("Inhalt nur auf dem Original").font(.subheadline).foregroundStyle(.tertiary) }
            ForEach(Array(inner.enumerated()), id: \.offset) { _, b in
                let bd = b["data"] as? [String: Any] ?? [:]
                let t = (bd["text"] as? String) ?? (bd["title"] as? String) ?? ""
                if !t.isEmpty { Text(inlineText(t)).font(b["type"] as? String == "header" ? .headline : .body) }
                else if let items = bd["items"] as? [Any] { ForEach(Array(items.enumerated()), id: \.offset) { Text(verbatim: "• " + plain(($0.element as? [String: Any])?["content"] as? String ?? ($0.element as? String) ?? "")) } }
            }
        }
        .padding(10).frame(maxWidth: .infinity, alignment: .leading)
        .overlay(RoundedRectangle(cornerRadius: 8).strokeBorder(Color.red.opacity(0.45), lineWidth: 1.5))
    }
}
