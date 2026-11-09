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
            if let data {
                let spec = view?["chart"] as? [String: Any], agg = spec?["agg"] as? String
                ChartBody(data: data, context: data.context(agg: agg, yName: propName(spec?["y"] as? String), xName: propName(spec?["x"] as? String)),
                          cap: ChartData.aggLabel(agg))
            }
            else if let err { Label(err == "bad_cfg" ? String(localized: "Diagramm ist nicht vollständig eingerichtet – im Web bearbeiten") : String(localized: "Diagramm konnte nicht geladen werden"), systemImage: "chart.bar.xaxis").foregroundStyle(.secondary) }
            else if !loaded { ProgressView().frame(maxWidth: .infinity, minHeight: 120) }
        }
        .task(id: "\(view?["id"] as? String ?? "")-\(store.dataTick)") { await load() }
    }
    /// Name einer Eigenschaft fuer die Kontextzeile ("Summe Betrag nach Kategorie")
    private func propName(_ id: String?) -> String? {
        guard let id else { return nil }
        return id == "title" ? String(localized: "Name") : m.prop(id)?.name
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

/// Diagramm zeichnen (auch fuer das Widget): Balken, waagrechte Balken, Linie, Flaeche, Kreis, Ring, Zahl; Werte als Tabelle.
/// Widget-Design-Runde (9.11.): Kontextzeile "Summe · 8 Einträge", Palette ohne Optionsfarben (blau, orange, gruen …),
/// Ring mit Gesamtwert in der Mitte und Legende mit Werten, Hoehe nach Groesse, Achse 11 pt kompakt, schmal 3 Ticks,
/// gestapelte Flaeche stapelt, Ziellinie gestrichelt gedaempft mit "Ziel". Regeln in ChartData (DB25Logic.swift).
struct ChartBody: View {
    let data: ChartData
    /// Widget: Hoehe je Groesse (140/220/300); nil = Datenbank-Ansicht (nach Breite 160 … 300)
    var height: CGFloat? = nil
    /// Kontextzeile ueber dem Diagramm
    var context: String? = nil
    /// Beschriftung unter dem Gesamtwert im Ring ("Summe", "Anzahl"); nil = "Gesamt"
    var cap: String? = nil
    /// bewusst gewaehlte Widget-Farbe: einfarbige Reihe in dieser Farbe
    var accent: Color? = nil
    @State private var showTable = false
    @State private var width: CGFloat = 0
    @Environment(\.locale) private var envLocale
    @Environment(\.widgetSize) private var size

    private var locale: Locale { SagaDate.locale(envLocale) }
    private var W: CGFloat { width > 0 ? width : 320 }
    private var L: ChartData.Layout { ChartData.layout(width: Double(W), height: height.map(Double.init)) }
    private var hasTable: Bool { data.type != "number" && !data.cats.isEmpty && !data.series.isEmpty }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if (context ?? "").isEmpty == false || (L.narrow && hasTable) {
                HStack(spacing: 6) {
                    if let c = context, !c.isEmpty { Text(verbatim: c).font(.footnote).foregroundStyle(.secondary).lineLimit(1).truncationMode(.tail) }
                    Spacer(minLength: 4)
                    if L.narrow && hasTable {
                        Button { showTable.toggle() } label: {
                            Image(systemName: "tablecells").font(.system(size: 12)).frame(width: 24, height: 24)
                                .background(showTable ? Color.primary.opacity(0.1) : .clear, in: RoundedRectangle(cornerRadius: 6))
                        }
                        .buttonStyle(.plain).foregroundStyle(.secondary)
                        .accessibilityLabel(Text("Werte als Tabelle")).help(Text("Werte als Tabelle"))
                    }
                }
            }
            switch data.type {
            case "number":
                WidgetValueText(text: NumFormat.text(data.total, fmt: data.fmt, locale: locale), size: height == nil ? 40 : widgetValueSize(size, width: W))
                if let g = data.goal { Text("Ziel: \(NumFormat.text(g, fmt: data.fmt, locale: locale))").font(.footnote).foregroundStyle(.secondary) }
            default:
                if !hasTable { Text("Keine Einträge.").foregroundStyle(.secondary) }
                else if data.round { ring } else { cartesian }
            }
            if data.other > 0 { Text("„Andere“ fasst \(data.other) weitere zusammen.").font(.caption).foregroundStyle(.secondary) }
            if data.truncated { Text("Gekürzt – nur die ersten Werte werden gezeigt.").font(.caption).foregroundStyle(.secondary) }
            if hasTable {
                if L.narrow { if showTable { table } }
                else { DisclosureGroup("Werte als Tabelle", isExpanded: $showTable) { table }.font(.footnote).foregroundStyle(.secondary) }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { if abs($0 - width) > 1 { width = $0 } }
    }

    // MARK: Farben

    private func color(_ p: ChartData.Paint) -> Color {
        switch p {
        case .named(let n): return HKPalette.chart(n)
        case .one: return accent ?? HKPalette.chart("blue")
        case .other: return Color.secondary.opacity(0.45)
        }
    }

    // MARK: Kreis und Ring

    @ViewBuilder private var ring: some View {
        let L = self.L, d = CGFloat(L.diameter)
        let vals = data.cats.indices.map { i in max(0, (data.series.first?.values[safe: i] ?? nil) ?? 0) }
        let paints = data.catColors
        let items = data.cats.indices.map { i in (paints[safe: i] ?? .one, data.label(i), NumFormat.text(vals[i], fmt: data.fmt, locale: locale)) }
        let pie = Chart(Array(data.cats.indices.filter { vals[$0] > 0 }), id: \.self) { i in
            SectorMark(angle: .value("Wert", vals[i]), innerRadius: .ratio(data.type == "donut" ? 0.62 : 0), angularInset: 1)
                .foregroundStyle(color(paints[safe: i] ?? .one))
                .accessibilityLabel(Text(verbatim: data.label(i)))                      // VoiceOver: Name statt Index (Nacharbeit 1)
                .accessibilityValue(Text(verbatim: NumFormat.text(vals[i], fmt: data.fmt, locale: locale)))
        }
        .frame(width: d, height: d)
        .overlay { if data.type == "donut" { center(d).accessibilityHidden(true) } }
        if L.beside {
            HStack(alignment: .center, spacing: 20) {
                pie
                legend(items, max: L.legendMax).frame(maxWidth: 320)
            }
        } else {
            VStack(alignment: .leading, spacing: 10) {
                pie.frame(maxWidth: .infinity)
                legend(items, max: L.legendMax)
            }
        }
    }
    /// Gesamtwert gross in der Mitte, darunter klein "Summe"/"Anzahl"
    private func center(_ d: CGFloat) -> some View {
        let ri = d / 2 * 0.62, tv = NumFormat.text(data.total, fmt: data.fmt, locale: locale)
        let fs = max(12, min(26, ri * 0.42, ri * 1.7 / CGFloat(max(3, tv.count)) * 1.6))
        return VStack(spacing: 0) {
            Text(verbatim: tv).font(.system(size: fs, weight: .semibold)).monospacedDigit().lineLimit(1).minimumScaleFactor(0.5)
            if ri > 34 { Text(verbatim: cap ?? String(localized: "Gesamt")).font(.system(size: 11)).foregroundStyle(.secondary).lineLimit(1) }
        }
        .frame(width: ri * 1.6)
    }
    /// Legende als Liste: Farbfeld, Name, Wert rechts; "+ n weitere"
    private func legend(_ items: [(ChartData.Paint, String, String)], max m: Int) -> some View {
        let lg = ChartData.legend(items, max: m)
        return VStack(alignment: .leading, spacing: 5) {
            ForEach(Array(lg.shown.enumerated()), id: \.offset) { _, it in
                HStack(spacing: 7) {
                    RoundedRectangle(cornerRadius: 3).fill(color(it.0)).frame(width: 10, height: 10)
                    Text(verbatim: it.1).foregroundStyle(.secondary).lineLimit(1).truncationMode(.tail)
                    Spacer(minLength: 8)
                    Text(verbatim: it.2).monospacedDigit().lineLimit(1).fixedSize()
                }
                .accessibilityElement(children: .combine)
            }
            if lg.more > 0 { Text("+ \(lg.more) weitere").italic().foregroundStyle(.secondary) }
        }
        .font(.footnote)
        .accessibilityHidden(true)                              // VoiceOver liest die Segmente (alle, mit Wert) - nicht doppelt
    }

    // MARK: Balken, Linie, Flaeche

    /// Punkt je Reihe und Kategorie; lo/hi = Unter-/Oberkante (gestapelt: aufsummiert je Kategorie)
    private struct Pt: Identifiable { let id: Int; let i: Int; let s: Int; let v: Double; let lo: Double; let hi: Double }
    private var stacked: Bool { data.stack && data.multi }
    private var points: [Pt] {
        var out: [Pt] = []
        var pos = [Double](repeating: 0, count: data.cats.count), neg = pos
        for (j, s) in data.series.enumerated() {
            var acc = 0.0
            for i in data.cats.indices {
                guard var v = s.values[safe: i] ?? nil else { continue }
                if data.cumulative { acc += v; v = acc }
                var lo = 0.0, hi = v
                if stacked {
                    if v >= 0 { lo = pos[i]; pos[i] += v; hi = pos[i] } else { lo = neg[i]; neg[i] += v; hi = neg[i] }
                }
                out.append(Pt(id: j * 10000 + i, i: i, s: j, v: v, lo: lo, hi: hi))
            }
        }
        return out
    }
    @ViewBuilder private var cartesian: some View {
        let L = self.L, pts = points, horiz = data.type == "hbar"
        let all = pts.flatMap { [$0.lo, $0.hi] } + (L.tiny ? [] : data.goal.map { [$0] } ?? [])
        let ticks = ChartData.ticks(min(0, all.min() ?? 0), max(0, all.max() ?? 0), n: L.ticks, integer: data.integerValues)
        let keys = data.cats.indices.map { String($0) }, labels = data.cats.indices.map { data.label($0) }
        let sKeys = data.series.indices.map { String($0) }
        let sCols = data.multi ? data.seriesColors.map { color($0) } : [color(.one)]
        let dots = data.cats.count <= 24 && !L.tiny
        let h = L.tiny ? min(CGFloat(L.height), 72) : (horiz ? max(CGFloat(L.height), CGFloat(labels.count) * 26 + 40) : CGFloat(L.height))
        if L.tiny { WidgetValueText(text: NumFormat.text(data.total, fmt: data.fmt, locale: locale), size: 20) }
        Chart {
            ForEach(pts) { p in
                let k = String(p.i), ser = data.multi ? String(p.s) : "0"
                switch data.type {
                case "hbar":
                    if stacked {
                        BarMark(xStart: .value("Wert", p.lo), xEnd: .value("Wert", p.hi), y: .value("Kategorie", k)).foregroundStyle(barColor(p)).accessibilityLabel(axLabel(p)).accessibilityValue(axValue(p))
                    } else {
                        BarMark(x: .value("Wert", p.v), y: .value("Kategorie", k)).foregroundStyle(barColor(p)).position(by: .value("Reihe", ser)).accessibilityLabel(axLabel(p)).accessibilityValue(axValue(p))
                    }
                case "line":
                    LineMark(x: .value("Kategorie", k), y: .value("Wert", p.hi)).foregroundStyle(by: .value("Reihe", ser))
                        .lineStyle(StrokeStyle(lineWidth: 2, lineCap: .round, lineJoin: .round))
                        .accessibilityLabel(axLabel(p)).accessibilityValue(axValue(p))
                    if dots { PointMark(x: .value("Kategorie", k), y: .value("Wert", p.hi)).foregroundStyle(by: .value("Reihe", ser)).symbolSize(28).accessibilityHidden(true) }
                case "area":
                    AreaMark(x: .value("Kategorie", k), yStart: .value("Wert", p.lo), yEnd: .value("Wert", p.hi))
                        .foregroundStyle(by: .value("Reihe", ser)).opacity(0.2)
                        .accessibilityLabel(axLabel(p)).accessibilityValue(axValue(p))
                    LineMark(x: .value("Kategorie", k), y: .value("Wert", p.hi)).foregroundStyle(by: .value("Reihe", ser))
                        .lineStyle(StrokeStyle(lineWidth: 2, lineCap: .round, lineJoin: .round)).accessibilityHidden(true)
                    if dots { PointMark(x: .value("Kategorie", k), y: .value("Wert", p.hi)).foregroundStyle(by: .value("Reihe", ser)).symbolSize(28).accessibilityHidden(true) }
                default:
                    if stacked {
                        BarMark(x: .value("Kategorie", k), yStart: .value("Wert", p.lo), yEnd: .value("Wert", p.hi)).foregroundStyle(barColor(p)).accessibilityLabel(axLabel(p)).accessibilityValue(axValue(p))
                    } else {
                        BarMark(x: .value("Kategorie", k), y: .value("Wert", p.v)).foregroundStyle(barColor(p)).position(by: .value("Reihe", ser)).accessibilityLabel(axLabel(p)).accessibilityValue(axValue(p))
                    }
                }
            }
            if let g = data.goal, !L.tiny {
                let gl = String(localized: "Ziel##Chart") + " " + ChartData.axisText(g, fmt: data.fmt, locale: locale)   // "Ziel" allein ist im Katalog ein Speicherziel
                if horiz {
                    RuleMark(x: .value("Ziel", g)).foregroundStyle(Color.secondary).lineStyle(StrokeStyle(lineWidth: 1.25, dash: [4, 4]))
                        .annotation(position: .top, alignment: .center) { Text(verbatim: gl).font(.system(size: 11)).foregroundStyle(.secondary) }
                } else {
                    RuleMark(y: .value("Ziel", g)).foregroundStyle(Color.secondary).lineStyle(StrokeStyle(lineWidth: 1.25, dash: [4, 4]))
                        .annotation(position: .top, alignment: .trailing) { Text(verbatim: gl).font(.system(size: 11)).foregroundStyle(.secondary) }
                }
            }
        }
        .chartForegroundStyleScale(domain: data.multi ? sKeys : ["0"], range: sCols)
        .chartLegend(.hidden)
        .modifier(AxisTicks(ticks: ticks, horizontal: horiz, fmt: data.fmt, keys: keys, labels: labels, hidden: L.tiny, locale: locale))
        .frame(height: h)
        if data.multi {
            let items = data.series.indices.map { j in (data.seriesColor(j), data.series[j].label.isEmpty ? String(localized: "Wert") : data.series[j].label) }
            let lg = ChartData.legend(items, max: L.narrow ? 4 : 10)
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 84), spacing: 12, alignment: .leading)], alignment: .leading, spacing: 4) {
                ForEach(Array(lg.shown.enumerated()), id: \.offset) { _, it in
                    HStack(spacing: 6) {
                        RoundedRectangle(cornerRadius: 3).fill(color(it.0)).frame(width: 10, height: 10)
                        Text(verbatim: it.1).foregroundStyle(.secondary).lineLimit(1).truncationMode(.tail)
                    }
                }
                if lg.more > 0 { Text("+ \(lg.more) weitere").italic().foregroundStyle(.secondary) }
            }
            .font(.footnote)
        }
    }
    /// VoiceOver je Punkt: Kategorie (bei mehreren Reihen mit Reihenname) und formatierter Wert - die Achse nutzt
    /// Index-Schluessel ("0", "1" ...), ohne eigene Beschriftung laese VoiceOver Nummern (Nacharbeit 1)
    private func axLabel(_ p: Pt) -> Text {
        let s = data.multi ? data.series[safe: p.s]?.label ?? "" : ""
        return Text(verbatim: data.label(p.i) + (s.isEmpty ? "" : ", " + s))
    }
    private func axValue(_ p: Pt) -> Text { Text(verbatim: NumFormat.text(p.v, fmt: data.fmt, locale: locale)) }
    /// Balkenfarbe: mehrere Reihen je Reihe, sonst je Kategorie (einfarbig, ausser >= 2 echte Optionsfarben)
    private func barColor(_ p: Pt) -> Color { data.multi ? color(data.seriesColor(p.s)) : color(data.catColor(p.i)) }

    private var table: some View {
        Grid(alignment: .leading, horizontalSpacing: 12, verticalSpacing: 3) {
            GridRow {
                Text(verbatim: "")
                ForEach(Array(data.series.enumerated()), id: \.offset) { Text(verbatim: data.series.count > 1 && !$0.element.label.isEmpty ? $0.element.label : String(localized: "Wert")).bold() }
            }
            ForEach(data.cats.indices, id: \.self) { i in
                GridRow {
                    Text(verbatim: data.label(i))
                    ForEach(Array(data.series.enumerated()), id: \.offset) { s in
                        Text(verbatim: (s.element.values[safe: i] ?? nil).map { NumFormat.text($0, fmt: data.fmt, locale: locale) } ?? "–").monospacedDigit()
                    }
                }
            }
        }
        .font(.footnote).foregroundStyle(.primary)
        .padding(.top, 4)
    }
}

/// Achsen wie im Web: Werte 1/2/2,5/5 x 10^k kompakt, Beschriftung 11 pt; Kategorien gekuerzt, ueberlappende ausgelassen
private struct AxisTicks: ViewModifier {
    let ticks: [Double]
    let horizontal: Bool
    let fmt: String
    let keys: [String]
    let labels: [String]
    let hidden: Bool
    let locale: Locale
    private func valueMarks() -> some AxisContent {
        AxisMarks(values: ticks) { v in
            AxisGridLine()
            AxisValueLabel { Text(verbatim: ChartData.axisText(v.as(Double.self) ?? 0, fmt: fmt, locale: locale)).font(.system(size: 11)) }
        }
    }
    private func catMarks() -> some AxisContent {
        AxisMarks(values: keys) { v in
            AxisValueLabel(collisionResolution: .greedy) {
                Text(verbatim: v.as(String.self).flatMap(Int.init).flatMap { labels[safe: $0] } ?? "").font(.system(size: 11)).lineLimit(1).truncationMode(.tail)
            }
        }
    }
    func body(content: Content) -> some View {
        let dom = (ticks.first ?? 0)...(ticks.last ?? 1)
        if hidden {
            if horizontal { content.chartXScale(domain: dom).chartYScale(domain: keys).chartXAxis(.hidden).chartYAxis(.hidden) }
            else { content.chartYScale(domain: dom).chartXScale(domain: keys).chartXAxis(.hidden).chartYAxis(.hidden) }
        } else if horizontal {
            content
                .chartXScale(domain: dom).chartYScale(domain: keys)
                .chartXAxis { valueMarks() }
                .chartYAxis { catMarks() }
        } else {
            content
                .chartYScale(domain: dom).chartXScale(domain: keys)
                .chartYAxis { valueMarks() }
                .chartXAxis { catMarks() }
        }
    }
}

// MARK: - Zeitleiste (timeline1, nur lesen)

struct DBTimelineView: View {
    @ObservedObject var m: DBModel
    let view: [String: Any]?
    @EnvironmentObject var nav: NavModel
    @EnvironmentObject var store: Store
    /// 2.5: Balken ziehen (Tablet/Mac): verschieben bzw. Ende ziehen -> Props-PATCH der Datumswerte
    @State private var drag: (id: Int, days: Int, move: Bool)?
    private var canDrag: Bool { !phone && !m.readOnly && !m.pageLocked && m.prop(startPid)?.type == "date" }
    private var startPid: String? { (view?["date_by"] as? String) ?? m.props.first { $0.type == "date" }?.id }
    private func dragGesture(_ b: TimelineLogic.Bar, move: Bool) -> some Gesture {
        DragGesture(minimumDistance: 6)
            .onChanged { g in drag = (b.id, TimelineDrag.days(dx: g.translation.width, dayWidth: Double(dayW)), move) }
            .onEnded { g in
                let n = TimelineDrag.days(dx: g.translation.width, dayWidth: Double(dayW))
                drag = nil
                guard let sp = startPid, let r = m.rows.first(where: { $0.id == b.id }) else { return }
                let ep = (view?["end_by"] as? String).flatMap { m.prop($0)?.type == "date" ? $0 : nil }
                guard let sv = m.query.value(m.prop(sp)!, r) as? String,
                      let p = TimelineDrag.props(start: sp, startValue: sv, end: ep, endValue: ep.flatMap { m.query.value(m.prop($0)!, r) as? String }, days: n, moveStart: move) else { return }
                Task { await m.setMany(b.id, p, store: store) }
            }
    }
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
                                    let dragging = drag?.id == b.id
                                    let ds = dragging && drag?.move == true ? CGFloat(drag?.days ?? 0) * dayW : 0
                                    let de = dragging ? CGFloat(drag?.days ?? 0) * dayW : 0
                                    Button { open(b.id, rows) } label: {
                                        Text(b.title.isEmpty ? String(localized: "Unbenannt") : b.title).font(.caption).lineLimit(1).fixedSize()
                                            .padding(.horizontal, 6).frame(minWidth: max(dayW, CGFloat(b.end - b.start + 1) * dayW + (dragging && drag?.move == false ? de : 0)), minHeight: 24, alignment: .leading)
                                            .background(barColor(b.id, rows).opacity(dragging ? 0.45 : 0.3), in: RoundedRectangle(cornerRadius: 5))
                                            .overlay(RoundedRectangle(cornerRadius: 5).stroke(barColor(b.id, rows).opacity(0.6)))
                                            .overlay(alignment: .trailing) {          // Ende ziehen
                                                if canDrag {
                                                    Rectangle().fill(Color.clear).frame(width: 10).contentShape(Rectangle())
                                                        .gesture(dragGesture(b, move: false))
                                                        #if os(macOS)
                                                        .onHover { $0 ? NSCursor.resizeLeftRight.push() : NSCursor.pop() }
                                                        #endif
                                                }
                                            }
                                    }
                                    .buttonStyle(.plain).offset(x: CGFloat(b.start - lo) * dayW + ds)
                                    .simultaneousGesture(canDrag ? dragGesture(b, move: true) : nil)
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
    @EnvironmentObject var ed: PageEditor
    var body: some View {
        let src = data["src"] as? [String: Any]
        let kind = SyncedLogic.kind(data)
        let inner = data["blocks"] as? NSMutableArray
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 6) {
                Image(systemName: kind == .noAccess ? "lock" : "arrow.triangle.2.circlepath").foregroundStyle(.red.opacity(0.8))
                Text(head(kind)).font(.caption).foregroundStyle(.secondary)
                Spacer()
                if let note = (src?["note"] as? Int) ?? (src?["note"] as? NSNumber)?.intValue {
                    Button { nav.open(note, mode: "page") } label: { Label("Zum Original", systemImage: "arrow.up.right") }.font(.caption).buttonStyle(.plain).foregroundStyle(Color.accentColor)
                }
            }
            if let inner, SyncedLogic.editable(data), !ed.locked {
                // 2.5 sync1: Inhalt hier bearbeiten - Original direkt, Kopie schreibt der Server ins Original durch
                BlocksView(arr: inner)
            } else if let inner, inner.count > 0 {
                ForEach(Array((inner as? [[String: Any]] ?? []).enumerated()), id: \.offset) { _, b in
                    let bd = b["data"] as? [String: Any] ?? [:]
                    let t = (bd["text"] as? String) ?? (bd["title"] as? String) ?? ""
                    if !t.isEmpty { Text(inlineText(t)).font(b["type"] as? String == "header" ? .headline : .body) }
                    else if let items = bd["items"] as? [Any] { ForEach(Array(items.enumerated()), id: \.offset) { Text(verbatim: "• " + plain(($0.element as? [String: Any])?["content"] as? String ?? ($0.element as? String) ?? "")) } }
                }
            } else if kind != .noAccess {
                Text("Inhalt nur auf dem Original").font(.subheadline).foregroundStyle(.tertiary)
            }
        }
        .padding(10).frame(maxWidth: .infinity, alignment: .leading)
        .overlay(RoundedRectangle(cornerRadius: 8).strokeBorder(Color.red.opacity(0.45), lineWidth: 1.5))
    }
    private func head(_ k: SyncedLogic.Kind) -> String {
        switch k {
        case .original(let n): return n > 0 ? String(localized: "Original · auf \(n) weiteren Seiten") : String(localized: "Synchronisierter Block")
        case .copy(let e): return e ? String(localized: "Synchronisiert") : String(localized: "Synchronisiert · nur lesen")
        case .gone: return String(localized: "Original gelöscht")
        case .noAccess: return String(localized: "Synchronisierter Block – kein Zugriff")
        case .missing: return String(localized: "Synchronisierter Block nicht gefunden")
        case .unknown: return String(localized: "Synchronisierter Block")
        }
    }
}
