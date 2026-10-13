import SwiftUI
import UniformTypeIdentifiers
#if os(iOS)
import UIKit
#endif

/// Navigationsstapel der rechten Spalte (iPhone: der ganze Stapel) - Seiten oeffnen sich nativ.
struct Peek: Identifiable, Equatable { let id: Int; let center: Bool }

@MainActor
final class NavModel: ObservableObject {
    @Published var path: [PageRoute] = []
    @Published var peek: Peek?                                       // Mac/iPad: Seite rechts hereingleitend oder mittig
    @Published var wantQuickAdd = false                              // Widget "+": Aufgaben-Eingabe gleich fokussieren
    @Published var peekList: [Int] = []                              // Reihenfolge der Ansicht -> im Peek blaettern (Ctrl+Shift+J/K)
    @Published var forward: [PageRoute] = []                         // fuer "Vor" (Cmd+])
    func push(_ r: PageRoute) { path.append(r); forward = [] }
    func back() { if let r = path.popLast() { forward.append(r) } }
    func goForward() { if let r = forward.popLast() { path.append(r) } }
    func peekStep(_ d: Int) {
        guard let p = peek, let i = peekList.firstIndex(of: p.id), peekList.indices.contains(i + d) else { return }
        peek = Peek(id: peekList[i + d], center: p.center)
    }
    /// "Oeffnen als" wie im Web: side/left = rechts, center = mittig, page/"" = ganze Seite (iPhone: immer ganze Seite)
    func open(_ id: Int, mode: String?, list: [Int] = []) {
        peekList = list
        var canPeek = true
        #if os(iOS)
        canPeek = UIDevice.current.userInterfaceIdiom == .pad
        #endif
        // Achtung Swift: "case a, b where x" prueft x NUR fuer b -> deshalb ausdruecklich (iPhone: nie Peek)
        let m = mode ?? ""
        if canPeek && (m == "side" || m == "left") { peek = Peek(id: id, center: false) }
        else if canPeek && m == "center" { peek = Peek(id: id, center: true) }
        else { peek = nil; push(PageRoute(id: id)) }
    }
}

// MARK: - Datenmodell

// DBOption / DBProp / DBRow + Filter/Sortierung: Shared/DBQuery.swift (gemeinsam mit dem Widget)
private func hashable(_ v: Any?) -> AnyHashable? { dbHashable(v) }

@MainActor
final class DBModel: ObservableObject {
    let dbID: Int
    @Published var props: [DBProp] = []
    @Published var views: [[String: Any]] = []
    @Published var rows: [DBRow] = []
    @Published var loaded = false
    @Published var error: String?
    @Published var schema: [String: Any] = [:]                 // vollstaendig (zum Bearbeiten und Zurueckschreiben)
    @Published var templates: [DBTemplate] = []                // Vorlagen dieser Datenbank ("Neu ▾")
    @Published var search = ""                                 // Suche in der Ansicht
    @Published var localViews: [[String: Any]]?                 // verknuepfter Block: eigene Ansichten (in der Seite gespeichert)
    var onLocalViews: (([[String: Any]]) -> Void)?
    init(dbID: Int) { self.dbID = dbID }

    func load(_ store: Store) async {
        if !loaded, let c = store.cachedData("api/notes/\(dbID)/rows") { apply(c) }
        if let d = try? await store.get("api/notes/\(dbID)/rows") { apply(d) }
        loaded = true
    }
    private func apply(_ d: Data) {
        guard let q = DBQuery.parse(d) else { return }
        props = q.props; views = q.views; rows = q.rows
        if let j = try? JSONSerialization.jsonObject(with: d) as? [String: Any] {
            schema = j["schema"] as? [String: Any] ?? [:]
            templates = (j["templates"] as? [[String: Any]] ?? []).map { DBTemplate(id: $0["id"] as? Int ?? 0, title: $0["title"] as? String ?? "", icon: $0["icon"] as? String ?? "") }
        }
    }
    private var query: DBQuery { DBQuery(props: props, views: views, rows: rows) }
    func prop(_ id: String?) -> DBProp? { props.first { $0.id == id } }

    /// Wert einer Eigenschaft setzen (der Server fuehrt die Eigenschaften zusammen)
    func set(_ row: Int, _ pid: String, _ value: Any?, store: Store) async {
        if let i = rows.firstIndex(where: { $0.id == row }) { if let h = hashable(value) { rows[i].props[pid] = h } else { rows[i].props.removeValue(forKey: pid) } }
        do { _ = try await store.api("api/notes/\(row)", method: "PATCH", body: ["props": [pid: value ?? NSNull()]]); store.dataChanged() }
        catch { self.error = error.localizedDescription; await load(store) }
    }
    func add(_ store: Store, props: [String: Any] = [:]) async -> Int? {
        struct R: Decodable { let id: Int }
        var b: [String: Any] = ["parent_id": dbID, "title": ""]
        if !props.isEmpty { b["props"] = props }
        guard let d = try? await store.api("api/notes", method: "POST", body: b), let r = try? JSONDecoder().decode(R.self, from: d) else { return nil }
        await load(store); await store.refresh(); store.dataChanged()
        return r.id
    }

    // Ansicht: Filter + Sortierung genau wie im Web - Shared/DBQuery.swift
    func value(_ p: DBProp, _ r: DBRow) -> Any? { query.value(p, r) }
    func rows(for view: [String: Any]?) -> [DBRow] {
        let all = query.rows(for: view), q = search.trimmingCharacters(in: .whitespaces).lowercased()
        guard !q.isEmpty else { return all }
        return all.filter { r in r.title.lowercased().contains(q) || props.contains { p in
            let v = query.value(p, r)
            if let s = v as? String { return (p.option(s)?.name ?? s).lowercased().contains(q) }
            if let a = v as? [AnyHashable] { return a.contains { p.option($0 as? String)?.name.lowercased().contains(q) ?? false } }
            return false } }
    }
    func defaults(for view: [String: Any]?) -> [String: Any] { query.defaults(for: view) }
    func color(_ r: DBRow, view: [String: Any]?) -> Color? { query.colorName(r, view: view).map(optColor) }
}

// MARK: - Datenbank (Block auf einer Seite oder ganze Seite)

struct DatabaseView: View {
    let dbID: Int
    let viewID: String?
    let localViews: [[String: Any]]?
    var embedded = false
    @EnvironmentObject var store: Store
    @EnvironmentObject var nav: NavModel
    @StateObject private var m: DBModel
    @State private var current: String?
    #if os(iOS)
    @Environment(\.horizontalSizeClass) private var hsc
    private var compact: Bool { hsc == .compact }
    #else
    private let compact = false
    #endif
    var onLocalViews: (([[String: Any]]) -> Void)? = nil
    init(dbID: Int, viewID: String?, localViews: [[String: Any]]?, embedded: Bool = false, onLocalViews: (([[String: Any]]) -> Void)? = nil) {
        self.dbID = dbID; self.viewID = viewID; self.localViews = localViews; self.embedded = embedded; self.onLocalViews = onLocalViews
        _m = StateObject(wrappedValue: DBModel(dbID: dbID)); _current = State(initialValue: viewID)
    }
    private var views: [[String: Any]] { m.localViews ?? (localViews?.isEmpty == false ? localViews : nil) ?? m.views }
    private var view: [String: Any]? { views.first { $0["id"] as? String == current } ?? views.first }
    private var type: String { view?["type"] as? String ?? "table" }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            header
            DBViewBar(m: m, views: views, current: $current, editable: localViews?.isEmpty != false || onLocalViews != nil) { id in
                Task {
                    if let id { nav.open(id, mode: view?["open"] as? String ?? "side"); return }
                    if let nid = await m.add(store, props: defaults()) { nav.open(nid, mode: view?["open"] as? String ?? "side") }
                }
            }
            if !m.loaded { ProgressView().frame(maxWidth: .infinity) }
            else {
                switch type {
                case "board": BoardView(m: m, view: view)
                case "gallery": GalleryView(m: m, view: view)
                case "calendar": DBCalendarView(m: m, view: view)
                case "list": RowList(m: m, view: view)
                default: if compact { RowList(m: m, view: view) } else { TableGrid(m: m, view: view) }   // iPhone: Liste ohne Abschneiden
                }
            }
            if let e = m.error { Text(e).font(.caption).foregroundStyle(.orange) }
        }
        .task {
            if let lv = localViews, !lv.isEmpty, m.localViews == nil, let cb = onLocalViews { m.localViews = lv; m.onLocalViews = cb }
            await m.load(store)
        }
        .onChange(of: store.dataTick) { _, _ in Task { await m.load(store) } }      // z. B. Eintrag per Schaltflaeche angelegt
    }

    private var header: some View {
        HStack(spacing: 8) {
            Button { if embedded { nav.push(PageRoute(id: dbID)) } } label: {
                HStack(spacing: 6) { PageIcon(icon: store.page(dbID)?.icon, store: store, fallback: "🗃️"); Text(store.page(dbID)?.label ?? String(localized: "Datenbank")).font(.subheadline.weight(.semibold)) }
                    .padding(.horizontal, 10).padding(.vertical, 5).background(.secondary.opacity(0.12), in: Capsule())
            }.buttonStyle(.plain)
            Spacer()
            if embedded {
                Button { nav.push(PageRoute(id: dbID)) } label: { Image(systemName: "arrow.up.left.and.arrow.down.right") }.buttonStyle(.plain).foregroundStyle(.secondary).help("Als ganze Seite öffnen")
            }
        }
    }
    /// Neuer Eintrag passt zu den Filtern der Ansicht ("Status ist Offen" -> Status = Offen), wie Notion
    private func defaults() -> [String: Any] { m.defaults(for: view) }
}

// MARK: - Liste/Tabelle: Haekchen vorne, Titel, rechts farbige Chips

struct RowList: View {
    @ObservedObject var m: DBModel
    let view: [String: Any]?
    @EnvironmentObject var store: Store
    @EnvironmentObject var nav: NavModel
    #if os(iOS)
    @Environment(\.horizontalSizeClass) private var hsc
    private var compact: Bool { hsc == .compact }
    #else
    private let compact = false
    #endif
    private var hidden: Set<String> { Set(view?["hidden"] as? [String] ?? []) }
    var body: some View {
        let rows = m.rows(for: view)
        let check = m.props.first { $0.type == "checkbox" && !hidden.contains($0.id) }
        let chips = m.props.filter { ["select", "multi_select", "date", "number", "calc"].contains($0.type) && !hidden.contains($0.id) }.prefix(3)
        VStack(spacing: 0) {
            ForEach(rows) { r in
                HStack(spacing: 10) {
                    if let check {
                        let on = (r.props[check.id]?.base as? Bool) ?? false
                        Button { Task { await m.set(r.id, check.id, !on, store: store) } } label: {
                            Image(systemName: on ? "checkmark.square.fill" : "square").foregroundStyle(on ? Color.accentColor : .secondary).font(.body)
                        }.buttonStyle(.plain)
                    } else { PageIcon(icon: r.icon, store: store) }
                    Button { nav.open(r.id, mode: view?["open"] as? String ?? "side", list: rows.map(\.id)) } label: {
                        let shown = chips.filter { hasValue(m.value($0, r)) }
                        let title = HStack(spacing: 8) {
                            if check != nil, let ic = r.icon, !ic.isEmpty { PageIcon(icon: ic, store: store) }
                            Text(r.title.isEmpty ? String(localized: "Unbenannt") : r.title).lineLimit(compact ? 3 : 2).multilineTextAlignment(.leading)
                                .foregroundStyle(r.title.isEmpty ? .secondary : .primary)
                        }
                        Group {
                            if compact {                            // iPhone: Werte unter dem Namen, umbrechend - nichts wird abgeschnitten
                                VStack(alignment: .leading, spacing: 4) {
                                    title
                                    if !shown.isEmpty { WrapLayout { ForEach(shown, id: \.id) { p in ValueChip(prop: p, value: m.value(p, r)) } } }
                                }.frame(maxWidth: .infinity, alignment: .leading)
                            } else {
                                HStack(spacing: 8) {
                                    title.layoutPriority(1)
                                    Spacer(minLength: 8)
                                    WrapLayout(trailing: true) { ForEach(shown, id: \.id) { p in ValueChip(prop: p, value: m.value(p, r)) } }
                                        .frame(maxWidth: 320, alignment: .trailing)
                                }
                            }
                        }.contentShape(Rectangle())
                    }.buttonStyle(.plain)
                }
                .padding(.vertical, 7)
                .contextMenu { RowMenu(m: m, row: r) { nav.open(r.id, mode: view?["open"] as? String ?? "side", list: rows.map(\.id)) } }
                .padding(.leading, m.color(r, view: view) != nil ? 8 : 0)
                .background {                                   // bedingte Farbe wie im Web: Hauch + Balken links
                    if let c = m.color(r, view: view) {
                        c.opacity(0.14).overlay(alignment: .leading) { c.frame(width: 3) }
                    }
                }
                Divider().opacity(0.5)
            }
            if rows.isEmpty { Text("Keine Einträge").foregroundStyle(.secondary).padding(.vertical, 8).frame(maxWidth: .infinity, alignment: .leading) }
        }
    }
}

// MARK: - Tabelle (Mac/iPad): Spalten wie im Web

/// Spalten in Reihenfolge der Ansicht, Breiten aus der Ansicht; passt sich dem Platz an wie im Web:
/// zuerst werden die anderen Spalten schmaler (bis 90), dann der Name (bis 150); uebriger Platz geht an den Namen.
struct TableGrid: View {
    @ObservedObject var m: DBModel
    let view: [String: Any]?
    @EnvironmentObject var store: Store
    @EnvironmentObject var nav: NavModel
    @State private var avail: CGFloat = 0
    @State private var dragW: [String: CGFloat] = [:]               // Breite waehrend des Ziehens
    private var vid: String? { view?["id"] as? String }

    private var cols: [DBProp] {
        let hidden = Set(view?["hidden"] as? [String] ?? []), order = view?["order"] as? [String] ?? []
        let vis = m.props.filter { !hidden.contains($0.id) }
        return order.compactMap { id in vis.first { $0.id == id } } + vis.filter { !order.contains($0.id) }
    }
    private func widths(_ cols: [DBProp]) -> [String: CGFloat] {
        let saved = view?["widths"] as? [String: Any] ?? [:]
        func w(_ k: String, _ d: CGFloat) -> CGFloat { (saved[k] as? NSNumber).map { CGFloat($0.doubleValue) } ?? d }
        var out: [String: CGFloat] = ["title": dragW["title"] ?? w("title", 260)]
        for p in cols { out[p.id] = dragW[p.id] ?? w(p.id, 170) }
        if !dragW.isEmpty { return out }                             // beim Ziehen nicht anpassen (sonst springt es)
        guard avail > 0 else { return out }
        let avail = self.avail - (m.locked ? 0 : 40)               // Platz fuer "+" (neue Spalte)
        var sum = out.values.reduce(0, +)
        func shrink(_ keys: [String], _ minW: CGFloat) {
            let over = sum - avail, room = keys.reduce(0) { $0 + max(0, (out[$1] ?? 0) - minW) }
            guard over > 0, room > 0 else { return }
            let f = min(1, over / room)
            for k in keys { let d = (max(0, (out[k] ?? 0) - minW) * f).rounded(.down); out[k] = (out[k] ?? 0) - d; sum -= d }
        }
        shrink(cols.map(\.id), 90); shrink(["title"], 150)
        if sum < avail { out["title"] = (out["title"] ?? 0) + avail - sum }
        return out
    }

    var body: some View {
        let cols = self.cols, w = widths(cols), rows = m.rows(for: view)
        ScrollView(.horizontal, showsIndicators: true) {
            VStack(alignment: .leading, spacing: 0) {
                HStack(spacing: 0) {
                    head(Text(verbatim: "Aa"), String(localized: "Name"), w["title"] ?? 260).overlay(alignment: .trailing) { resizer("title", w["title"] ?? 260) }
                    ForEach(cols) { p in
                        ColumnHead(m: m, prop: p, width: w[p.id] ?? 170)
                            .overlay(alignment: .leading) { Rectangle().fill(.separator).frame(width: 1) }
                            .overlay(alignment: .trailing) { resizer(p.id, w[p.id] ?? 170) }
                    }
                    if !m.locked { AddColumnMenu(m: m).padding(.horizontal, 10) }
                }
                Divider()
                ForEach(rows) { r in
                    HStack(spacing: 0) {
                        TitleCell(m: m, row: r) { nav.open(r.id, mode: view?["open"] as? String ?? "side", list: rows.map(\.id)) }
                            .padding(.horizontal, 8).padding(.vertical, 5)
                            .frame(width: w["title"] ?? 260, alignment: .leading).frame(maxHeight: .infinity)
                        ForEach(cols) { p in
                            ZStack(alignment: .leading) { Color.clear; cell(p, r) }      // leere Zelle behaelt Breite + Trennlinie
                                .padding(.horizontal, 8).padding(.vertical, 7)
                                .frame(width: w[p.id] ?? 170, alignment: .leading).frame(maxHeight: .infinity)
                                .overlay(alignment: .leading) { Rectangle().fill(.separator).frame(width: 1) }
                        }
                    }
                    .fixedSize(horizontal: false, vertical: true)          // alle Zellen einer Zeile gleich hoch
                    .background { if let c = m.color(r, view: view) { c.opacity(0.14) } }
                    .contextMenu { RowMenu(m: m, row: r) { nav.open(r.id, mode: view?["open"] as? String ?? "side", list: rows.map(\.id)) } }
                    Divider()
                }
                CalcRow(m: m, view: view, cols: cols, widths: w, rows: rows)
                Button { Task { if let id = await m.add(store, props: m.defaults(for: view)) { nav.open(id, mode: view?["open"] as? String ?? "side") } } } label: {
                    Label("Neu", systemImage: "plus").font(.subheadline).foregroundStyle(.secondary).padding(8)
                }.buttonStyle(.plain)
            }
            .frame(minWidth: avail, alignment: .leading)
        }
        .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { avail = $0 }
    }

    /// Rand rechts am Spaltenkopf: ziehen aendert die Breite (pro Ansicht gespeichert, wie im Web)
    @ViewBuilder private func resizer(_ key: String, _ cur: CGFloat) -> some View {
        if !m.locked, let vid {
            Rectangle().fill(Color.clear).frame(width: 6).contentShape(Rectangle())
                #if os(macOS)
                .onHover { inside in if inside { NSCursor.resizeLeftRight.push() } else { NSCursor.pop() } }
                #endif
                .gesture(DragGesture(minimumDistance: 1, coordinateSpace: .global)
                    .onChanged { g in
                        let start = dragW[key + "#0"] ?? cur
                        if dragW[key + "#0"] == nil { dragW[key + "#0"] = cur }
                        dragW[key] = max(60, min(800, start + g.translation.width))
                    }
                    .onEnded { _ in
                        let nw = Int(dragW[key] ?? cur); dragW = [:]
                        m.editView(store, vid) { v in var ws = v["widths"] as? [String: Any] ?? [:]; ws[key] = nw; v["widths"] = ws }
                    })
        }
    }

    private func head(_ icon: Text, _ name: String, _ width: CGFloat) -> some View {
        HStack(spacing: 5) { icon.foregroundStyle(.tertiary); Text(name).lineLimit(1) }
            .font(.subheadline).foregroundStyle(.secondary)
            .padding(.horizontal, 8).padding(.vertical, 7).frame(width: width, alignment: .leading)
    }

    @ViewBuilder private func cell(_ p: DBProp, _ r: DBRow) -> some View {
        let v = m.value(p, r)
        switch p.type {
        case "text", "url", "date", "select", "multi_select":     // direkt in der Tabelle bearbeiten
            PropEditor(prop: p, value: v) { nv in Task { await m.set(r.id, p.id, nv, store: store) } }.id("\(r.id)-\(p.id)-\(String(describing: v))")
        case "number":
            PropEditor(prop: p, value: v) { nv in Task { await m.set(r.id, p.id, nv, store: store) } }.id("\(r.id)-\(p.id)-\(String(describing: v))")
        case "checkbox":
            let on = (v as? Bool) ?? false
            Button { Task { await m.set(r.id, p.id, !on, store: store) } } label: {
                Image(systemName: on ? "checkmark.square.fill" : "square").foregroundStyle(on ? Color.accentColor : .secondary)
            }.buttonStyle(.plain)
        case "select", "multi_select":
            WrapLayout { ValueChip(prop: p, value: v) }
        case "number", "calc":
            if let n = v as? Double {
                HStack(spacing: 6) {
                    Text(numText(n, p.fmt, p.show)).monospacedDigit()
                    if p.fmt == "percent", p.show == "ring" { ProgressRing(value: n / 100) }
                }
            }
        case "date", "created", "edited":
            if let s = v as? String, !s.isEmpty { Text(dayText(s)).foregroundStyle(.secondary) }
        case "url":
            if let s = v as? String, let u = URL(string: s), !s.isEmpty { Link(destination: u) { Text(s).lineLimit(1).truncationMode(.middle) } }
        case "relation":
            let ids = (v as? [AnyHashable])?.compactMap { ($0 as? Double).map(Int.init) ?? ($0 as? Int) } ?? []
            WrapLayout { ForEach(ids, id: \.self) { id in Chip(text: store.page(id)?.label ?? "↗", color: nil) } }
        default:
            if let s = v as? String, !s.isEmpty { Text(s).lineLimit(3) }
        }
    }
}

/// Rechtsklick auf eine Zeile (Tabelle/Liste)
struct RowMenu: View {
    @ObservedObject var m: DBModel
    let row: DBRow
    let open: () -> Void
    @EnvironmentObject var store: Store
    var body: some View {
        Button("Öffnen", action: open)
        Button("Duplizieren") { Task { _ = await store.duplicate(row.id); await m.load(store); store.dataChanged() } }
        Button("Link kopieren") { HlidskjalfApp_copyPageLink(row.id) }
        Divider()
        Button("In den Papierkorb", role: .destructive) { Task { _ = await store.trash(row.id); await m.load(store); store.dataChanged() } }
    }
}

/// Fusszeile: Berechnung je Spalte (Anzahl, Summe, Durchschnitt ...), pro Ansicht gespeichert
struct CalcRow: View {
    @ObservedObject var m: DBModel
    let view: [String: Any]?
    let cols: [DBProp]
    let widths: [String: CGFloat]
    let rows: [DBRow]
    @EnvironmentObject var store: Store
    private var calcs: [String: String] { view?["calcs"] as? [String: String] ?? [:] }
    static let names: [String: String] = ["count": String(localized: "Anzahl"), "filled": String(localized: "Ausgefüllt"), "empty": String(localized: "Leer"), "pct_filled": String(localized: "Prozent ausgefüllt"), "unique": String(localized: "Verschiedene"),
        "sum": String(localized: "Summe"), "avg": String(localized: "Durchschnitt"), "median": String(localized: "Median"), "min": String(localized: "Minimum"), "max": String(localized: "Maximum"), "range": String(localized: "Spanne"),
        "checked": String(localized: "Angehakt"), "unchecked": String(localized: "Nicht angehakt"), "pct_checked": String(localized: "Prozent angehakt"), "earliest": String(localized: "Frühestes"), "latest": String(localized: "Spätestes")]
    static func ops(_ p: DBProp?) -> [String] {
        let t = p.map { $0.type == "calc" ? "number" : $0.type } ?? "text"
        if t == "checkbox" { return ["count", "checked", "unchecked", "pct_checked"] }
        var o = ["count", "filled", "empty", "pct_filled", "unique"]
        if t == "number" { o += ["sum", "avg", "median", "min", "max", "range"] }
        if ["date", "created", "edited"].contains(t) { o += ["earliest", "latest"] }
        return o
    }
    var body: some View {
        HStack(spacing: 0) {
            cell("title", nil).frame(width: widths["title"] ?? 260, alignment: .trailing)
            ForEach(cols) { p in cell(p.id, p).frame(width: widths[p.id] ?? 170, alignment: .trailing) }
        }
        .font(.caption).foregroundStyle(.secondary)
    }
    @ViewBuilder private func cell(_ key: String, _ p: DBProp?) -> some View {
        let op = calcs[key]
        Menu {
            Button("Keine") { set(key, nil) }
            ForEach(Self.ops(p), id: \.self) { o in Button(Self.names[o] ?? o) { set(key, o) } }
        } label: {
            if let op { (Text((Self.names[op] ?? op) + " ") + Text(value(op, p)).foregroundStyle(.primary).fontWeight(.semibold)).lineLimit(1) }
            else { Text("Berechnen").opacity(0.35) }
        }
        .menuStyle(.button).buttonStyle(.plain).menuIndicator(.hidden).padding(.horizontal, 8).padding(.vertical, 5)
    }
    private func set(_ key: String, _ op: String?) {
        guard let vid = view?["id"] as? String else { return }
        m.editView(store, vid) { v in var c = v["calcs"] as? [String: Any] ?? [:]; c[key] = op; v["calcs"] = c.isEmpty ? nil : c }
    }
    private func value(_ op: String, _ p: DBProp?) -> String {
        let vals: [Any?] = rows.map { r in p.map { m.value($0, r) } ?? r.title }
        func empty(_ v: Any?) -> Bool { !hasValue(v) }
        let n = vals.count, filled = vals.filter { !empty($0) }
        let nums: [Double] = vals.compactMap { v in (v as? Double) ?? (v as? String).flatMap { Double($0) } }
        func fmt(_ x: Double) -> String { let r = (x * 100).rounded() / 100; return p.map { ["number", "calc"].contains($0.type) ? numText(r, $0.fmt) : r.formatted() } ?? r.formatted() }
        func pct(_ a: Int) -> String { n > 0 ? (Double(a) / Double(n)).formatted(.percent.precision(.fractionLength(0))) : "–" }
        let days = filled.compactMap { $0 as? String }.filter { $0.count >= 10 }.map { String($0.prefix(10)) }.sorted()
        switch op {
        case "count": return "\(n)"
        case "filled": return "\(filled.count)"
        case "empty": return "\(n - filled.count)"
        case "pct_filled": return pct(filled.count)
        case "unique": var u = Set<String>(); for v in filled { if let a = v as? [Any] { a.forEach { u.insert("\($0)") } } else { u.insert("\(v!)") } }; return "\(u.count)"
        case "sum": return nums.isEmpty ? "–" : fmt(nums.reduce(0, +))
        case "avg": return nums.isEmpty ? "–" : fmt(nums.reduce(0, +) / Double(nums.count))
        case "median": if nums.isEmpty { return "–" }; let s = nums.sorted(), k = s.count / 2; return fmt(s.count % 2 == 1 ? s[k] : (s[k - 1] + s[k]) / 2)
        case "min": return nums.min().map(fmt) ?? "–"
        case "max": return nums.max().map(fmt) ?? "–"
        case "range": return nums.isEmpty ? "–" : fmt(nums.max()! - nums.min()!)
        case "checked": return "\(vals.filter { $0 as? Bool == true }.count)"
        case "unchecked": return "\(vals.filter { $0 as? Bool != true }.count)"
        case "pct_checked": return pct(vals.filter { $0 as? Bool == true }.count)
        case "earliest": return days.first.map(dayText) ?? "–"
        case "latest": return days.last.map(dayText) ?? "–"
        default: return ""
        }
    }
}

/// Name einer Zeile: direkt umbenennen, Pfeil oeffnet den Eintrag
struct TitleCell: View {
    @ObservedObject var m: DBModel
    let row: DBRow
    let open: () -> Void
    @EnvironmentObject var store: Store
    @State private var text = ""
    @State private var hover = false
    @FocusState private var focused: Bool
    var body: some View {
        HStack(spacing: 6) {
            if let ic = row.icon, !ic.isEmpty { PageIcon(icon: ic, store: store) }
            TextField(String(localized: "Unbenannt"), text: $text, axis: .vertical).lineLimit(1...3).textFieldStyle(.plain).fontWeight(.medium)
                .focused($focused).onSubmit { commit(); focused = false }
                .onChange(of: focused) { _, f in if !f { commit() } }
            Button(action: open) { Image(systemName: "arrow.up.right.square").foregroundStyle(.secondary) }
                .buttonStyle(.plain).opacity(hover ? 1 : 0.25).help("Öffnen")
        }
        .contentShape(Rectangle())
        .onHover { hover = $0 }
        .onAppear { text = row.title }
        .onChange(of: row.title) { _, t in if !focused { text = t } }
    }
    private func commit() {
        let t = text.replacingOccurrences(of: "\n", with: " ").trimmingCharacters(in: .whitespaces)
        guard t != row.title else { return }
        Task { await m.rename(store, row: row.id, t) }
    }
}

/// Spaltenkopf: Klick oeffnet die Spalten-Einstellungen (Name, Typ, Optionen, Format, loeschen)
struct ColumnHead: View {
    @ObservedObject var m: DBModel
    let prop: DBProp
    let width: CGFloat
    @State private var edit = false
    var body: some View {
        Button { if !m.locked { edit = true } } label: {
            HStack(spacing: 5) { Image(systemName: prop.symbol).foregroundStyle(.tertiary); Text(prop.name).lineLimit(1) }
                .font(.subheadline).foregroundStyle(.secondary)
                .padding(.horizontal, 8).padding(.vertical, 7).frame(width: width, alignment: .leading).contentShape(Rectangle())
        }
        .buttonStyle(.plain).help(prop.name)
        .popover(isPresented: $edit, arrowEdge: .bottom) { ColumnEditor(m: m, pid: prop.id) }
    }
}

/// Fortschrittsring (wie im Web bei "Berechnet" mit Anzeige Ring)
struct ProgressRing: View {
    let value: Double
    var body: some View {
        ZStack {
            Circle().stroke(.secondary.opacity(0.3), lineWidth: 2)
            Circle().trim(from: 0, to: max(0, min(1, value))).stroke(.green, style: StrokeStyle(lineWidth: 2, lineCap: .round)).rotationEffect(.degrees(-90))
        }.frame(width: 14, height: 14)
    }
}

/// Kompakte Anzeige eines Werts (Chip/Datum/Zahl) - leer -> nichts
struct ValueChip: View {
    let prop: DBProp
    let value: Any?
    var body: some View {
        switch prop.type {
        case "select":
            if let o = prop.option(value as? String) { Chip(text: o.name, color: o.color) }
        case "multi_select":                                // einzelne Chips: in WrapLayout bricht jeder fuer sich um
            let ids = (value as? [AnyHashable])?.compactMap { $0 as? String } ?? []
            ForEach(ids, id: \.self) { id in if let o = prop.option(id) { Chip(text: o.name, color: o.color) } }
        case "date":
            if let s = value as? String, !s.isEmpty { Text(dayText(s)).font(.caption).foregroundStyle(.secondary) }
        case "number", "calc":
            if let n = value as? Double { Text(numText(n, prop.fmt, prop.show)).font(.caption.monospacedDigit()).foregroundStyle(.secondary) }
        default: EmptyView()
        }
    }
}

func dayText(_ s: String) -> String {
    let p = DateFormatter(); p.dateFormat = "yyyy-MM-dd"
    guard let d = p.date(from: String(s.prefix(10))) else { return s }
    var style = Date.FormatStyle.dateTime.day().month(.abbreviated)                         // Format der Systemsprache
    if !Calendar.current.isDate(d, equalTo: .now, toGranularity: .year) { style = style.year() }
    return d.formatted(style)
}
func numText(_ n: Double, _ fmt: String?, _ show: String? = nil) -> String {
    switch fmt {
    case "percent" where show == "ring" || show == "bar": return (n / 100).formatted(.percent.precision(.fractionLength(0)))   // Fortschritt: ganze Prozent
    case "percent": return (n / 100).formatted(.percent.precision(.fractionLength(0...2))); case "euro": return n.formatted(.currency(code: "EUR")); default: return n.formatted() }
}

// MARK: - Board: Spalten nach Auswahl, Karten ziehen aendert den Wert

struct BoardView: View {
    @ObservedObject var m: DBModel
    let view: [String: Any]?
    @EnvironmentObject var store: Store
    @EnvironmentObject var nav: NavModel
    var body: some View {
        let group = m.prop(view?["group_by"] as? String) ?? m.props.first { $0.type == "select" }
        let rows = m.rows(for: view)
        if let group {
            let cols: [(String?, String, String?)] = group.options.map { ($0.id, $0.name, $0.color) } + [(nil, String(localized: "Ohne \(group.name)"), nil)]
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(alignment: .top, spacing: 12) {
                    ForEach(cols, id: \.1) { col in
                        let items = rows.filter { (($0.props[group.id]?.base as? String).flatMap { $0.isEmpty ? nil : $0 }) == col.0 }
                        VStack(alignment: .leading, spacing: 8) {
                            HStack { Chip(text: col.1, color: col.2); Text("\(items.count)").font(.caption).foregroundStyle(.secondary) }
                            ForEach(items) { r in
                                Button { nav.open(r.id, mode: view?["open"] as? String ?? "side", list: m.rows(for: view).map(\.id)) } label: { Card(m: m, row: r, view: view, cover: false) }.buttonStyle(.plain)
                                    .draggable(String(r.id))
                            }
                            Button { Task { if let id = await m.add(store, props: col.0.map { [group.id: $0] } ?? [:]) { nav.open(id, mode: view?["open"] as? String ?? "side") } } } label: {
                                Label("Neu", systemImage: "plus").font(.caption)
                            }.buttonStyle(.plain).foregroundStyle(.secondary)
                        }
                        .padding(10).frame(width: 240, alignment: .topLeading)
                        .background(optColor(col.2).opacity(0.08), in: RoundedRectangle(cornerRadius: 10))
                        .dropDestination(for: String.self) { ids, _ in
                            for s in ids { if let id = Int(s) { Task { await m.set(id, group.id, col.0, store: store) } } }
                            return true
                        }
                    }
                }
            }
        } else { Text("Board braucht eine Auswahl-Eigenschaft").foregroundStyle(.secondary) }
    }
}

struct Card: View {
    @ObservedObject var m: DBModel
    let row: DBRow
    let view: [String: Any]?
    var cover = true
    @EnvironmentObject var store: Store
    var body: some View {
        let hidden = Set(view?["hidden"] as? [String] ?? [])
        VStack(alignment: .leading, spacing: 6) {
            if cover, let c = row.cover { RemoteImage(path: c, fill: true, w: 640).frame(height: 110).clipped() }
            VStack(alignment: .leading, spacing: 6) {
                HStack(spacing: 6) { if let ic = row.icon, !ic.isEmpty { PageIcon(icon: ic, store: store) }; Text(row.title.isEmpty ? String(localized: "Unbenannt") : row.title).font(.subheadline.weight(.medium)).lineLimit(2) }
                let ps = m.props.filter { !hidden.contains($0.id) && ["select", "multi_select", "date", "checkbox", "number", "calc"].contains($0.type) }.prefix(4)
                FlowChips(m: m, row: row, props: Array(ps))
            }.padding(cover ? 10 : 8)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .background {                                        // Karte + bedingte Farbe darueber (ein zweites .background laege dahinter)
            ZStack { Rectangle().fill(.background.secondary); if let c = m.color(row, view: view) { c.opacity(0.16) } }
                .clipShape(RoundedRectangle(cornerRadius: 9))
        }
        .overlay(RoundedRectangle(cornerRadius: 9).stroke(m.color(row, view: view)?.opacity(0.6) ?? .secondary.opacity(0.2)))
        .clipShape(RoundedRectangle(cornerRadius: 9))
    }
}

/// Leerer Wert? (dann keinen Platz fuer einen Chip reservieren)
func hasValue(_ v: Any?) -> Bool {
    guard let v else { return false }
    if let s = v as? String { return !s.isEmpty }
    if let a = v as? [Any] { return !a.isEmpty }
    if let b = v as? Bool { return b }
    return true
}

/// Fliesslayout: Kinder nebeneinander, bei Platzmangel in die naechste Zeile (Chips, Marken)
struct WrapLayout: Layout {
    var spacing: CGFloat = 4, lineSpacing: CGFloat = 4
    var trailing = false
    private func lines(_ maxW: CGFloat, _ subviews: Subviews) -> [[(Int, CGSize)]] {
        var out: [[(Int, CGSize)]] = [[]], x: CGFloat = 0
        for (i, s) in subviews.enumerated() {
            var sz = s.sizeThatFits(.unspecified); sz.width = min(sz.width, maxW)
            if sz.width == 0 && sz.height == 0 { continue }
            if x > 0 && x + sz.width > maxW { out.append([]); x = 0 }
            out[out.count - 1].append((i, sz)); x += sz.width + spacing
        }
        return out.filter { !$0.isEmpty }
    }
    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let ls = lines(proposal.width ?? .infinity, subviews)
        let w = ls.map { l in l.reduce(0) { $0 + $1.1.width } + spacing * CGFloat(max(0, l.count - 1)) }.max() ?? 0
        let h = ls.map { l in l.map(\.1.height).max() ?? 0 }.reduce(0, +) + lineSpacing * CGFloat(max(0, ls.count - 1))
        return CGSize(width: min(w, proposal.width ?? w), height: h)
    }
    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var y = bounds.minY
        for l in lines(bounds.width, subviews) {
            let lw = l.reduce(0) { $0 + $1.1.width } + spacing * CGFloat(max(0, l.count - 1)), lh = l.map(\.1.height).max() ?? 0
            var x = trailing ? bounds.maxX - lw : bounds.minX
            for (i, sz) in l { subviews[i].place(at: CGPoint(x: x, y: y + (lh - sz.height) / 2), proposal: ProposedViewSize(sz)); x += sz.width + spacing }
            y += lh + lineSpacing
        }
    }
}

struct FlowChips: View {
    @ObservedObject var m: DBModel
    let row: DBRow
    let props: [DBProp]
    var body: some View {
        WrapLayout {
            ForEach(props, id: \.id) { p in
                if p.type == "checkbox", let b = row.props[p.id]?.base as? Bool, b { Image(systemName: "checkmark.square.fill").font(.caption).foregroundStyle(.secondary) }
                else { ValueChip(prop: p, value: m.value(p, row)) }
            }
        }
    }
}

// MARK: - Galerie

struct GalleryView: View {
    @ObservedObject var m: DBModel
    let view: [String: Any]?
    @EnvironmentObject var nav: NavModel
    var body: some View {
        let big = (view?["card"] as? String) == "l", small = (view?["card"] as? String) == "s"
        LazyVGrid(columns: [GridItem(.adaptive(minimum: small ? 130 : big ? 240 : 170), spacing: 12)], spacing: 12) {
            ForEach(m.rows(for: view)) { r in Button { nav.open(r.id, mode: view?["open"] as? String ?? "side", list: m.rows(for: view).map(\.id)) } label: { Card(m: m, row: r, view: view) }.buttonStyle(.plain) }
        }
    }
}

// MARK: - Kalender (Monat) nach Datums-Eigenschaft

struct DBCalendarView: View {
    @ObservedObject var m: DBModel
    let view: [String: Any]?
    @EnvironmentObject var store: Store
    @EnvironmentObject var nav: NavModel
    @State private var month = Calendar.current.date(from: Calendar.current.dateComponents([.year, .month], from: .now))!
    static let dayFormat: DateFormatter = { let f = DateFormatter(); f.dateFormat = "yyyy-MM-dd"; return f }()
    static let monthFormat: DateFormatter = { let f = DateFormatter(); f.setLocalizedDateFormatFromTemplate("LLLLyyyy"); return f }()
    /// Wochentage ab Montag in der Systemsprache (Mo., Di. ... / Mon, Tue ... / lun, mar ...)
    static let weekdayHeads: [String] = { let s = Calendar.current.shortStandaloneWeekdaySymbols; return Array(s[1...] + s[..<1]) }()
    var body: some View {
        let dp = m.prop(view?["date_by"] as? String) ?? m.props.first { $0.type == "date" }
        let cal = Calendar(identifier: .iso8601)
        let first = month, lead = (cal.component(.weekday, from: first) + 5) % 7
        let days = cal.range(of: .day, in: .month, for: first)!.count
        let f = Self.dayFormat, mf = Self.monthFormat
        VStack(spacing: 6) {
            HStack {
                Button { month = cal.date(byAdding: .month, value: -1, to: month)! } label: { Image(systemName: "chevron.left") }
                Text(mf.string(from: month)).font(.headline).frame(minWidth: 140)
                Button { month = cal.date(byAdding: .month, value: 1, to: month)! } label: { Image(systemName: "chevron.right") }
                Spacer()
                Button("Heute") { month = cal.date(from: cal.dateComponents([.year, .month], from: .now))! }.font(.caption)
            }.buttonStyle(.plain)
            if let dp {
                let rows = m.rows(for: view)
                LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 2), count: 7), spacing: 2) {
                    ForEach(Array(Self.weekdayHeads.enumerated()), id: \.offset) { Text($0.element).font(.caption2).foregroundStyle(.secondary) }
                    ForEach(0..<(lead + days), id: \.self) { i in
                        if i < lead { Color.clear.frame(height: 64) } else {
                            let d = cal.date(byAdding: .day, value: i - lead, to: first)!, ds = f.string(from: d)
                            let items = rows.filter { ((m.value(dp, $0) as? String) ?? "").hasPrefix(ds) }
                            VStack(alignment: .leading, spacing: 2) {
                                HStack {
                                    Text("\(cal.component(.day, from: d))").font(.caption2.weight(cal.isDateInToday(d) ? .bold : .regular))
                                        .foregroundStyle(cal.isDateInToday(d) ? Color.accentColor : .secondary)
                                    Spacer()
                                    if !["created", "edited"].contains(dp.type) {
                                        Button { Task { if let id = await m.add(store, props: [dp.id: ds]) { nav.open(id, mode: view?["open"] as? String ?? "side") } } } label: { Image(systemName: "plus").font(.caption2) }.buttonStyle(.plain).foregroundStyle(.tertiary)
                                    }
                                }
                                ForEach(items.prefix(3)) { r in
                                    Button { nav.open(r.id, mode: view?["open"] as? String ?? "side", list: m.rows(for: view).map(\.id)) } label: { Text(r.title.isEmpty ? String(localized: "Unbenannt") : r.title).font(.caption2).lineLimit(1).padding(.horizontal, 3).frame(maxWidth: .infinity, alignment: .leading).background(Color.accentColor.opacity(0.18), in: RoundedRectangle(cornerRadius: 3)) }.buttonStyle(.plain)
                                }
                                if items.count > 3 { Text("+\(items.count - 3)").font(.caption2).foregroundStyle(.secondary) }
                                Spacer(minLength: 0)
                            }
                            .padding(3).frame(height: 64, alignment: .top).background(.secondary.opacity(0.06), in: RoundedRectangle(cornerRadius: 4))
                        }
                    }
                }
            } else { Text("Kalender braucht eine Datums-Eigenschaft").foregroundStyle(.secondary) }
        }
    }
}

// MARK: - Eigenschaften eines Eintrags: Symbol je Typ, "Leer", direkt aendern

struct EditableProps: View {
    @ObservedObject var doc: PageDoc
    let schema: [String: Any]
    @EnvironmentObject var store: Store
    private var defs: [DBProp] { (schema["props"] as? [[String: Any]] ?? []).map(DBProp.init) }
    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            ForEach(defs) { p in
                HStack(alignment: .center, spacing: 8) {
                    Label(p.name, systemImage: p.symbol).font(.subheadline).foregroundStyle(.secondary).frame(width: 150, alignment: .leading).lineLimit(1)
                    PropEditor(prop: p, value: value(p)) { v in Task { await save(p, v) } }
                    Spacer(minLength: 0)
                }
                .frame(minHeight: 32)
            }
        }
    }
    private func value(_ p: DBProp) -> Any? {
        if p.type == "created" { return doc.page["created"] }
        if p.type == "edited" { return doc.page["updated"] }
        if p.type == "calc" { return DBQuery.calc(p, doc.props) }
        return doc.props[p.id]
    }
    private func save(_ p: DBProp, _ v: Any?) async {
        var props = doc.props; props[p.id] = v ?? NSNull(); doc.page["props"] = props
        do { _ = try await store.api("api/notes/\(doc.id)", method: "PATCH", body: ["props": [p.id: v ?? NSNull()]]); store.dataChanged() }
        catch { doc.error = error.localizedDescription }
    }
}

struct PropEditor: View {
    let prop: DBProp
    let value: Any?
    let set: (Any?) -> Void
    @State private var text = ""
    @State private var date = Date()
    var body: some View {
        switch prop.type {
        case "calc":                                              // berechnet: nur anzeigen
            if let n = value as? Double { Text(numText(n, prop.fmt, prop.show)).monospacedDigit() } else { Text("–").foregroundStyle(.secondary) }
        case "select":
            Menu {
                ForEach(prop.options, id: \.id) { o in Button(o.name) { set(o.id) } }
                if value as? String != nil { Divider(); Button("Leeren", role: .destructive) { set(nil) } }
            } label: {
                if let o = prop.option(value as? String) { Chip(text: o.name, color: o.color) } else { leer }
            }.menuStyle(.button).buttonStyle(.plain).menuIndicator(.hidden).fixedSize()
        case "multi_select":
            let ids = (value as? [Any])?.compactMap { $0 as? String } ?? []
            Menu {
                ForEach(prop.options, id: \.id) { o in
                    Button { set(ids.contains(o.id) ? ids.filter { $0 != o.id } : ids + [o.id]) } label: { Label(o.name, systemImage: ids.contains(o.id) ? "checkmark" : "") }
                }
            } label: {
                if ids.isEmpty { leer } else { WrapLayout(spacing: 3) { ForEach(ids, id: \.self) { id in if let o = prop.option(id) { Chip(text: o.name, color: o.color) } } } }
            }.menuStyle(.button).buttonStyle(.plain).menuIndicator(.hidden).fixedSize(horizontal: false, vertical: true)   // Chips duerfen umbrechen
        case "checkbox":
            let on = value as? Bool ?? false
            Button { set(!on) } label: { Image(systemName: on ? "checkmark.square.fill" : "square").foregroundStyle(on ? Color.accentColor : .secondary) }.buttonStyle(.plain)
        case "date":
            let s = value as? String ?? ""
            HStack(spacing: 6) {
                if s.isEmpty {
                    Button { set(isoDay(.now)) } label: { leer }.buttonStyle(.plain)
                } else {
                    DatePicker("", selection: Binding(get: { parse(s) ?? .now }, set: { set(isoDay($0)) }), displayedComponents: .date).labelsHidden()
                    Button { set(nil) } label: { Image(systemName: "xmark.circle.fill").foregroundStyle(.tertiary) }.buttonStyle(.plain)
                }
            }
        case "created", "edited":
            if let ts = value as? Double { Text(Date(timeIntervalSince1970: ts).formatted(date: .abbreviated, time: .shortened)).font(.subheadline) }
        case "relation":
            let n = (value as? [Any])?.count ?? 0
            Text(n == 0 ? "Leer" : "\(n) verknüpft").font(.subheadline).foregroundStyle(n == 0 ? .tertiary : .primary)
        default:   // text, number, url, email, phone
            TextField("Leer", text: $text)
                .textFieldStyle(.plain).font(.subheadline)
                #if os(iOS)
                .keyboardType(prop.type == "number" ? .decimalPad : prop.type == "url" ? .URL : prop.type == "email" ? .emailAddress : prop.type == "phone" ? .phonePad : .default)
                #endif
                .onAppear { text = value.map { v in (v as? Double).map { prop.type == "number" ? $0.formatted() : "\($0)" } ?? "\(v)" } ?? "" }
                .onSubmit { commit() }
                .onChange(of: text) { _, _ in }
                .onDisappear { commit() }
        }
    }
    private var leer: some View { Text("Leer").font(.subheadline).foregroundStyle(.tertiary) }
    private func commit() {
        let cur = value.map { "\($0)" } ?? ""
        if prop.type == "number" {
            let n = (try? Double(text, format: .number)) ?? Double(text)          // Zahl in der Schreibweise der Systemsprache
            if text.isEmpty { if value != nil { set(nil) } } else if let n, n != (value as? Double) { set(n) }
        } else if text != cur { set(text.isEmpty ? nil : text) }
    }
    private func isoDay(_ d: Date) -> String { let f = DateFormatter(); f.dateFormat = "yyyy-MM-dd"; return f.string(from: d) }
    private func parse(_ s: String) -> Date? { let f = DateFormatter(); f.dateFormat = "yyyy-MM-dd"; return f.date(from: String(s.prefix(10))) }
}
