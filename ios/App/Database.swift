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
    @Published var sharedJump = 0                                    // "Zu „Mit mir geteilt“": Seitenleiste dort zeigen (zaehlt hoch)
    /// Benachrichtigung zu einem Kommentar: beim Oeffnen der Seite die Kommentare dieses Blocks zeigen
    var pendingComments: (id: Int, block: String)?
    /// zaehlt hoch, wenn `pendingComments` gesetzt wird - eine schon offene Seite oeffnet dann ihre Kommentare (BUG-059)
    @Published var commentsJump = 0
    func showComments(_ id: Int, block: String) { pendingComments = (id, block); commentsJump += 1 }
    /// Neu angelegter Datenbank-Eintrag: beim Oeffnen den Titel fokussieren (Tastatur auf)
    var focusTitle: Int?
    func toShared() { sharedJump += 1 }
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
    /// Rolle (1.3, aus /rows): Lesen/Kommentieren -> nur lesen ("Nur lesen"-Chip, keine Eingaben)
    @Published var role = "owner"
    var readOnly: Bool { role == "view" || role == "comment" }
    /// Seite mit diesem Datenbank-Block (bzw. die Datenbank-Seite) ist gesperrt: keine neuen Eintraege, keine
    /// Ansichten/Spalten aendern, solange die Sperre gilt (Eintraege selbst bleiben wie bisher)
    @Published var pageLocked = false
    /// "Neu" (Eintrag anlegen) anbieten?
    var canAdd: Bool { !readOnly && !pageLocked }
    /// Datenbank nicht (mehr) erreichbar (404/403) - verknuepfte Datenbank auf einer fremden Seite
    @Published var noAccess = false
    var onLocalViews: (([[String: Any]]) -> Void)?
    /// 2.5 eingebetteter Block: Kopf ein-/ausblenden (Datenbanktitel, Ansichts-Reiter) - steht im Block (hide_title/hide_tabs).
    /// onHead nil = nicht aenderbar (ganze Datenbank-Seite, gesperrte oder fremde Seite)
    @Published var showTitle = true
    @Published var showTabs = true
    var onHead: ((String, Bool) -> Void)?
    /// "Vorlagen verwalten" offen (Blatt an der DatabaseView)
    @Published var managingTemplates = false
    /// Schema-Aenderungen, die noch nicht beim Server sind (entprellt, nacheinander gesendet)
    var pendingSchema: [(inout [String: Any]) -> Void] = []
    var schemaDebounce: Task<Void, Never>?
    let schemaQueue = SerialQueue()
    /// dataTick, den diese Ansicht selbst ausgeloest und schon geladen hat (kein zweites /rows)
    var ownTick: Int?
    init(dbID: Int) { self.dbID = dbID }

    /// 2.5: Personen-Verzeichnis der Antwort (Kennung -> Name/Bild/ich/geloescht) und eigene Kennung
    @Published var people: [String: [String: Any]] = [:]
    var me: String? { people.first { $0.value["me"] as? Bool == true }?.key }
    /// /rows immer mit Zeitzone des Geraets (Formeln mit today()/now(), Datum mit Uhrzeit) - aeltere Server ignorieren ihn
    var rowsPath: String {
        "api/notes/\(dbID)/rows?tz=" + (TimeZone.current.identifier.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? "UTC")
    }
    func load(_ store: Store) async {
        if !loaded, let c = store.cachedData(rowsPath) ?? store.cachedData("api/notes/\(dbID)/rows") { apply(c) }
        do { apply(try await store.get(rowsPath)); noAccess = false }
        catch APIError.http(let c, _) where c == 404 || c == 403 { noAccess = true; rows = []; store.dropCache(rowsPath); store.dropCache("api/notes/\(dbID)/rows") }
        catch {}
        loaded = true
    }
    #if DEBUG
    func previewApply(_ d: Data) { apply(d); loaded = true }   // nur fuer Bilder (DB25Preview)
    #endif
    private func apply(_ d: Data) {
        guard let q = DBQuery.parse(d) else { return }
        rows = q.rows; people = q.people
        guard pendingSchema.isEmpty else { return }               // eigene, noch nicht gesendete Schema-Aenderungen nicht zuruecksetzen
        props = q.props; views = q.views
        if let j = try? JSONSerialization.jsonObject(with: d) as? [String: Any] {
            role = j["role"] as? String ?? "owner"
            schema = j["schema"] as? [String: Any] ?? [:]
            templates = (j["templates"] as? [[String: Any]] ?? []).map { DBTemplate(id: $0["id"] as? Int ?? 0, title: $0["title"] as? String ?? "", icon: $0["icon"] as? String ?? "") }
        }
    }
    var query: DBQuery { DBQuery(props: props, views: views, rows: rows, people: people, me: me) }
    func prop(_ id: String?) -> DBProp? { props.first { $0.id == id } }

    /// Wert einer Eigenschaft setzen (der Server fuehrt die Eigenschaften zusammen)
    func set(_ row: Int, _ pid: String, _ value: Any?, store: Store) async {
        guard !readOnly else { return }                           // nur lesen (1.3): z. B. Karte aufs Board gezogen - nichts senden
        guard !FxLogic.computed(props).contains(pid) else { return }   // 2.5: Formel/Rollup/Berechnet nie zurueckschreiben
        if let i = rows.firstIndex(where: { $0.id == row }) { if let h = hashable(value) { rows[i].props[pid] = h } else { rows[i].props.removeValue(forKey: pid) } }
        do {
            let d = try await store.api("api/notes/\(row)", method: "PATCH", body: ["props": [pid: value ?? NSNull()]])
            applyPatch(row, d)
            store.dataChanged()
        }
        catch { self.error = error.localizedDescription; await load(store) }
    }
    /// 2.5: mehrere Werte in EINEM Aufruf (Zeitleiste: Beginn und Ende zusammen)
    func setMany(_ row: Int, _ values: [String: Any], store: Store) async {
        guard !readOnly else { return }
        let vs = values.filter { !FxLogic.computed(props).contains($0.key) }
        guard !vs.isEmpty else { return }
        if let i = rows.firstIndex(where: { $0.id == row }) { for (k, v) in vs { if let h = hashable(v) { rows[i].props[k] = h } } }
        do {
            let d = try await store.api("api/notes/\(row)", method: "PATCH", body: ["props": vs])
            applyPatch(row, d); store.dataChanged()
        } catch { self.error = error.localizedDescription; await load(store) }
    }
    /// 2.5: Antwort eines Zeilen-PATCH - berechnete Werte (calc, fx_err), gespeicherte Personen/Dateien (values), Personen
    func applyPatch(_ row: Int, _ d: Data) {
        guard let j = (try? JSONSerialization.jsonObject(with: d)) as? [String: Any] else { return }
        people = PeopleLogic.merge(people, j["people"] as? [String: Any])
        if let i = rows.firstIndex(where: { $0.id == row }) { FxLogic.apply(patch: j, to: &rows[i]) }
    }
    func add(_ store: Store, props: [String: Any] = [:]) async -> Int? {
        guard !readOnly else { return nil }
        struct R: Decodable { let id: Int; let updated: Double? }
        var b: [String: Any] = ["parent_id": dbID, "title": ""]
        if !props.isEmpty { b["props"] = props }
        guard let d = try? await store.api("api/notes", method: "POST", body: b), let r = try? JSONDecoder().decode(R.self, from: d) else { return nil }
        // Zeilen dieser Ansicht neu, Eintrag lokal in die Seitenliste (statt die ganze Liste neu zu laden), andere
        // Ansichten derselben Datenbank ueber dataChanged - diese Ansicht nicht noch einmal (hat gerade geladen)
        await load(store)
        await store.insertCreated(.created(id: r.id, parent: dbID, updated: r.updated ?? Date().timeIntervalSince1970))
        ownTick = store.dataTick + 1; store.dataChanged()
        return r.id
    }

    // Ansicht: Filter + Sortierung genau wie im Web - Shared/DBQuery.swift
    func value(_ p: DBProp, _ r: DBRow) -> Any? { query.value(p, r) }
    func rows(for view: [String: Any]?) -> [DBRow] {
        let all = query.rows(for: view), q = search.trimmingCharacters(in: .whitespaces).lowercased()
        let names = PeopleLogic.directory(people)
        guard !q.isEmpty else { return all }
        return all.filter { r in r.title.lowercased().contains(q) || props.contains { p in
            let v = query.value(p, r)
            if let s = v as? String { return (p.option(s)?.name ?? s).lowercased().contains(q) }
            if p.type == "person" { return PeopleLogic.persons(v, names).contains { $0.name.lowercased().contains(q) } }   // Anzeigenamen
            if let a = v as? [AnyHashable] { return a.contains { p.option($0 as? String)?.name.lowercased().contains(q) ?? ($0 as? String)?.lowercased().contains(q) ?? false } }
            if let n = v as? Double { return DBDate.jsNumStr(n).contains(q) }
            return false } }
    }
    func defaults(for view: [String: Any]?) -> [String: Any] { query.defaults(for: view) }
    /// Hoechstzahl Dateien je Zelle (Schema max, Standard 10)
    func maxFiles(_ pid: String) -> Int { (schema["props"] as? [[String: Any]])?.first { $0["id"] as? String == pid }?["max"] as? Int ?? 10 }
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
    /// Seite gesperrt: "Neu", Ansichts-Einstellungen, "+" (Ansicht/Spalte) aus, solange die Sperre gilt
    var pageLocked = false
    var showTitle = true, showTabs = true
    var onHead: ((String, Bool) -> Void)? = nil
    init(dbID: Int, viewID: String?, localViews: [[String: Any]]?, embedded: Bool = false, pageLocked: Bool = false,
         showTitle: Bool = true, showTabs: Bool = true, onHead: ((String, Bool) -> Void)? = nil, onLocalViews: (([[String: Any]]) -> Void)? = nil) {
        self.dbID = dbID; self.viewID = viewID; self.localViews = localViews; self.embedded = embedded; self.pageLocked = pageLocked; self.onLocalViews = onLocalViews
        self.showTitle = showTitle; self.showTabs = showTabs; self.onHead = onHead
        _m = StateObject(wrappedValue: DBModel(dbID: dbID)); _current = State(initialValue: viewID)
    }
    private var views: [[String: Any]] { m.localViews ?? (localViews?.isEmpty == false ? localViews : nil) ?? m.views }
    private var view: [String: Any]? { views.first { $0["id"] as? String == current } ?? views.first }
    private var type: String { view?["type"] as? String ?? "table" }

    var body: some View {
        // R15: Rueckruf bei jedem Neuzeichnen neu setzen - er haengt am Block der aktuellen Fassung der Seite
        let _ = { if embedded { m.onHead = onHead; if m.onLocalViews != nil, let cb = onLocalViews { m.onLocalViews = cb } } }()   // I01: auch eigene Ansichten
        VStack(alignment: .leading, spacing: 8) {
            if embedded && m.showTitle { header }                    // ganze Datenbank-Seite: Name steht schon als Titel darueber
            DBViewBar(m: m, views: views, current: $current, editable: localViews?.isEmpty != false || onLocalViews != nil, onNew: { id in
                Task {
                    let mode = view?["open"] as? String ?? "side"
                    if let id, id > 0 { nav.open(id, mode: mode); return }
                    // "Neu" (nil): Standardvorlage, wenn gesetzt (2.5); "Leerer Eintrag" (0): immer leer
                    if id == nil, let t = m.defaultTemplate, let nid = await m.newFromTemplate(store, t) { nav.open(nid, mode: mode); return }
                    if let nid = await m.add(store, props: defaults()) { nav.focusTitle = nid; nav.open(nid, mode: mode) }   // Titel gleich bearbeiten
                }
            }, onOpenFull: embedded && !m.showTitle ? { nav.push(PageRoute(id: dbID)) } : nil,   // V2
               dbName: store.page(dbID)?.label ?? "")
            if !m.loaded { ProgressView().frame(maxWidth: .infinity) }
            else if m.noAccess { Text(verbatim: "🗃️ " + String(localized: "Seite ohne Zugriff")).foregroundStyle(.secondary) }
            else {
                switch type {
                case "board": BoardView(m: m, view: view)
                case "gallery": GalleryView(m: m, view: view)
                case "calendar":
                    if view?["range"] as? String == "week", let dp = m.prop(view?["date_by"] as? String) ?? m.props.first(where: { $0.type == "date" }) {
                        DBWeekView(m: m, view: view, prop: dp)                // 2.5 tableopts1: Kalender-Woche
                    } else { DBCalendarView(m: m, view: view) }
                case "list": RowList(m: m, view: view)
                case "chart" where store.features.chart: DBChartView(m: m, view: view)      // 2.5 chart1: nativ (Swift Charts)
                case "timeline": DBTimelineView(m: m, view: view)                           // 2.5 timeline1: nur lesen
                default: TableGrid(m: m, view: view)                 // auch iPhone: echte Spalten, waagrecht scrollbar
                }
            }
            if let e = m.error { Text(e).font(.caption).foregroundStyle(.orange) }
        }
        .onChange(of: pageLocked, initial: true) { _, v in if m.pageLocked != v { m.pageLocked = v } }
        .onChange(of: "\(showTitle)\(showTabs)\(onHead != nil)", initial: true) { _, _ in
            if embedded { m.onHead = onHead; if m.showTitle != showTitle { m.showTitle = showTitle }; if m.showTabs != showTabs { m.showTabs = showTabs } }
        }
        .sheet(isPresented: $m.managingTemplates) { TemplateManager(m: m).environmentObject(store).environmentObject(nav) }
        .task {
            if let lv = localViews, !lv.isEmpty, m.localViews == nil, let cb = onLocalViews { m.localViews = lv; m.onLocalViews = cb }
            await m.load(store)
        }
        .onChange(of: store.dataTick) { _, t in if m.ownTick != t { Task { await m.load(store) } } }      // z. B. Eintrag per Schaltflaeche angelegt
    }

    private var header: some View {
        HStack(spacing: 8) {
            Button { if embedded { nav.push(PageRoute(id: dbID)) } } label: {
                HStack(spacing: 6) { PageIcon(icon: store.page(dbID)?.icon, store: store, fallback: "🗃️"); Text(store.page(dbID)?.label ?? String(localized: "Datenbank")).font(.subheadline.weight(.semibold)) }
                    .padding(.horizontal, 10).padding(.vertical, 5).background(.secondary.opacity(0.12), in: Capsule())
            }.buttonStyle(.plain)
            Spacer()
            if embedded {
                Button { nav.push(PageRoute(id: dbID)) } label: { Image(systemName: "arrow.up.left.and.arrow.down.right") }.buttonStyle(.plain).foregroundStyle(.secondary).help("Als ganze Seite öffnen").accessibilityLabel("Als ganze Seite öffnen")
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
        let status = check == nil ? m.props.first { $0.status && !hidden.contains($0.id) } : nil      // 2.5: Abhaken ueber Status-Gruppe
        let chips = m.props.filter { ["select", "multi_select", "date", "number", "calc", "formula", "rollup", "person"].contains($0.type) && !hidden.contains($0.id) && $0.id != status?.id }.prefix(3)
        VStack(spacing: 0) {
            ForEach(rows) { r in
                HStack(spacing: 10) {
                    if let check {
                        let on = (r.props[check.id]?.base as? Bool) ?? false
                        Button { Task { await m.set(r.id, check.id, !on, store: store) } } label: {
                            CheckBoxIcon(on: on).font(.body)
                        }.buttonStyle(.plain).disabled(m.readOnly)
                        .accessibilityLabel(Text(check.name)).accessibilityAddTraits(on ? .isSelected : [])
                    } else if let status {
                        let done = status.statusGroup(r.props[status.id]?.base) == "done"
                        Button { Task { if let v = StatusLogic.toggled(status, current: r.props[status.id]?.base as? String) { await m.set(r.id, status.id, v, store: store) } } } label: {
                            CheckBoxIcon(on: done).font(.body)
                        }.buttonStyle(.plain).disabled(m.readOnly)
                        .accessibilityLabel(Text(status.name)).accessibilityAddTraits(done ? .isSelected : [])
                    } else { PageIcon(icon: r.icon, store: store) }
                    Button { nav.open(r.id, mode: view?["open"] as? String ?? "side", list: rows.map(\.id)) } label: {
                        let shown = chips.filter { hasValue(m.value($0, r)) }
                        let title = HStack(spacing: 8) {
                            if check != nil, let ic = r.icon, !ic.isEmpty { PageIcon(icon: ic, store: store) }
                            Text(r.title.isEmpty ? String(localized: "Unbenannt") : r.title).lineLimit(compact ? 3 : 2).multilineTextAlignment(.leading)
                                .foregroundStyle(r.title.isEmpty ? .secondary : .primary)
                            if r.blocked { BlockedBadge() }
                        }
                        Group {
                            if compact {                            // iPhone: Werte unter dem Namen, umbrechend - nichts wird abgeschnitten
                                VStack(alignment: .leading, spacing: 4) {
                                    title
                                    if !shown.isEmpty { WrapLayout { ForEach(shown, id: \.id) { p in ValueChip(prop: p, value: m.value(p, r), err: r.fxErr[p.id], people: m.people) } } }
                                }.frame(maxWidth: .infinity, alignment: .leading)
                            } else {
                                // Werte zuerst (bis 320, umbrechend), der Name bekommt den Rest. Frueher hatte der Name Vorrang:
                                // ein langer Name liess WrapLayout auf Breite 0 schrumpfen -> Chips als leere Striche.
                                HStack(spacing: 8) {
                                    title.frame(maxWidth: .infinity, alignment: .leading)
                                    WrapLayout(trailing: true, maxWidth: 320) { ForEach(shown, id: \.id) { p in ValueChip(prop: p, value: m.value(p, r), err: r.fxErr[p.id], people: m.people) } }
                                        .layoutPriority(1)
                                }
                            }
                        }.contentShape(Rectangle())
                    }.buttonStyle(.plain)
                }
                .padding(.vertical, 7)
                .padding(.leading, CGFloat(min(r.depth, 6)) * 18)          // 2.5 Unteraufgaben: eingerueckt
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
    /// Grosse Schrift: Spalten wachsen mit (gespeicherte Breiten gelten fuer Standardschrift). Sonst brach der Name
    /// silbenweise um und Status/Datum ueberlappten (F10); die Tabelle scrollt dann waagerecht.
    @ScaledMetric(relativeTo: .body) private var typeScale: CGFloat = 1
    private var scale: CGFloat { min(3, max(1, typeScale)) }
    private var vid: String? { view?["id"] as? String }

    private var cols: [DBProp] {
        let hidden = Set(view?["hidden"] as? [String] ?? []), order = view?["order"] as? [String] ?? []
        let vis = m.props.filter { !hidden.contains($0.id) }
        return order.compactMap { id in vis.first { $0.id == id } } + vis.filter { !order.contains($0.id) }
    }
    private func widths(_ cols: [DBProp]) -> [String: CGFloat] {
        let saved = view?["widths"] as? [String: Any] ?? [:]
        func w(_ k: String, _ d: CGFloat) -> CGFloat { ((saved[k] as? NSNumber).map { CGFloat($0.doubleValue) } ?? d) * scale }
        var out: [String: CGFloat] = ["title": dragW["title"] ?? w("title", 260)]
        for p in cols { out[p.id] = dragW[p.id] ?? w(p.id, 170) }
        if !dragW.isEmpty { return out }                             // beim Ziehen nicht anpassen (sonst springt es)
        guard avail > 0 else { return out }
        let avail = self.avail - (m.locked ? 0 : 40)               // Platz fuer "+" (neue Spalte)
        var sum = out.values.reduce(0, +)
        func shrink(_ keys: [String], _ minW: (String) -> CGFloat) {
            let over = sum - avail, room = keys.reduce(0) { $0 + max(0, (out[$1] ?? 0) - minW($1)) }
            guard over > 0, room > 0 else { return }
            let f = min(1, over / room)
            for k in keys { let d = (max(0, (out[k] ?? 0) - minW(k)) * f).rounded(.down); out[k] = (out[k] ?? 0) - d; sum -= d }
        }
        // Datum: Datumswahl + "x" brauchen ~150 (bei 90 ragte das Datum in die naechste Spalte)
        let dateCols = Set(cols.filter { $0.type == "date" }.map(\.id))
        shrink(cols.map(\.id)) { (dateCols.contains($0) ? 150 : 90) * scale }; shrink(["title"]) { _ in 180 * scale }   // fix27 #17: Titel nicht mitten im Wort trennen (iPhone: "Work-/shop")
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
                            .padding(.leading, CGFloat(min(r.depth, 6)) * 14)      // 2.5 Unteraufgaben
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
                if m.canAdd {
                    Button { Task { if let id = await m.add(store, props: m.defaults(for: view)) { nav.open(id, mode: view?["open"] as? String ?? "side") } } } label: {
                        Label("Neu", systemImage: "plus").font(.subheadline).foregroundStyle(.secondary).padding(8)
                    }.buttonStyle(.plain)
                }
            }
            .frame(minWidth: avail, alignment: .leading)
        }
        .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { avail = $0 }
        // schmale Spalte: Schattenkante zeigt, dass die Tabelle waagrecht weitergeht (links/rechts)
        .onScrollGeometryChange(for: [Bool].self) { g in
            [g.contentOffset.x > 1, g.contentSize.width - g.contentOffset.x - g.containerSize.width > 1]
        } action: { _, v in if v != more { more = v } }
        .overlay(alignment: .leading) { if more[0] { edgeShadow(toLeading: true) } }
        .overlay(alignment: .trailing) { if more[1] { edgeShadow(toLeading: false) } }
    }
    @State private var more = [false, false]
    @Environment(\.colorScheme) private var scheme
    /// hell: Schatten; dunkel: heller Schimmer (ein schwarzer Schatten verschwaende auf schwarzem Grund)
    private func edgeShadow(toLeading: Bool) -> some View {
        let c: Color = scheme == .dark ? .white : .black
        return LinearGradient(colors: [c.opacity(scheme == .dark ? 0.16 : 0.13), c.opacity(0)],
                       startPoint: toLeading ? .leading : .trailing, endPoint: toLeading ? .trailing : .leading)
            .frame(width: 14).allowsHitTesting(false).accessibilityHidden(true)
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
                        let nw = Int((dragW[key] ?? cur) / scale); dragW = [:]
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
        case "text", "number", "date", "select", "multi_select":  // direkt in der Tabelle bearbeiten (Auswahl als Chips, Datum lesbar)
            PropEditor(prop: p, value: v, dateTime: store.features.datetime) { nv in Task { await m.set(r.id, p.id, nv, store: store) } }.disabled(m.readOnly).id("\(r.id)-\(p.id)-\(String(describing: v))")
        case "url":                                               // bearbeiten + Link oeffnen (nur http/https/mailto)
            HStack(spacing: 4) {
                PropEditor(prop: p, value: v) { nv in Task { await m.set(r.id, p.id, nv, store: store) } }.disabled(m.readOnly).id("\(r.id)-\(p.id)-\(String(describing: v))")
                if let s = v as? String, let u = safeLink(s) {
                    Link(destination: u) { Image(systemName: "arrow.up.right.square") }.foregroundStyle(.secondary).accessibilityLabel("Link öffnen")
                }
            }
        case "checkbox":
            let on = (v as? Bool) ?? false
            Button { Task { await m.set(r.id, p.id, !on, store: store) } } label: {
                CheckBoxIcon(on: on)
            }.buttonStyle(.plain).disabled(m.readOnly)
            .accessibilityLabel(Text(p.name)).accessibilityAddTraits(on ? .isSelected : [])
        case "calc":
            if let n = v as? Double {
                HStack(spacing: 6) {
                    Text(numText(n, p.fmt, p.show)).monospacedDigit()
                    if p.fmt == "percent", p.show == "ring" { ProgressRing(value: n / 100) }
                }
            }
        case "formula", "rollup":                                 // 2.5: fertig vom Server, nur lesen
            FxValueView(prop: p, value: v, err: r.fxErr[p.id]).help(String(localized: "Formel im Web bearbeiten"))
        case "person":
            PeopleEditor(prop: p, dbID: m.dbID, value: v, people: m.people, canEdit: !m.readOnly && store.features.people) { nv in Task { await m.set(r.id, p.id, nv, store: store) } }
        case "files":
            FilesEditor(rowID: r.id, value: v, max: m.maxFiles(p.id), canEdit: !m.readOnly && store.features.files) { nv in Task { await m.set(r.id, p.id, nv, store: store) } }
        case "created", "edited":
            if let ts = v as? Double { Text(Date(timeIntervalSince1970: ts).sagaFormatted(date: .abbreviated, time: .omitted)).foregroundStyle(.secondary) }
            else if let s = v as? String, !s.isEmpty { Text(dayText(s)).foregroundStyle(.secondary) }
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
        let owner = m.role == "owner"                                   // Duplizieren/Papierkorb: nur der Eigentuemer (1.3)
        if owner { Button("Duplizieren") { Task { _ = await store.duplicate(row.id); await m.load(store); store.dataChanged() } } }
        Button("Link kopieren") { HlidskjalfApp_copyPageLink(row.id) }
        if owner {
            Divider()
            Button("In den Papierkorb", role: .destructive) { Task { _ = await store.trash(row.id); await m.load(store); store.dataChanged() } }
        }
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
            if let op {
                // schmale Spalte (iPhone): Wert vor dem Namen, nie beides abgeschnitten ("Sum… 1.4…", fix27 #10)
                let val = Text(value(op, p)).foregroundStyle(.primary).fontWeight(.semibold)
                ViewThatFits(in: .horizontal) {
                    HStack(spacing: 4) { Text(Self.names[op] ?? op); val }.lineLimit(1).fixedSize()
                    val.lineLimit(1).fixedSize()
                    val.lineLimit(1).minimumScaleFactor(0.6)
                }
                .accessibilityElement(children: .ignore).accessibilityLabel(Text(verbatim: (Self.names[op] ?? op) + " " + value(op, p)))
            }
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
            Button(action: open) { Image(systemName: "arrow.up.right.square").foregroundStyle(.secondary) }.accessibilityLabel("Öffnen")
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
        .settingsPopover(isPresented: $edit, title: "Spalte") { ColumnEditor(m: m, pid: prop.id) }   // iPhone/iPad: Blatt mit "Fertig"
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
    var err: String? = nil
    var people: [String: [String: Any]] = [:]
    var body: some View {
        switch prop.type {
        case "select" where prop.status: StatusChip(prop: prop, value: value)
        case "select":
            if let o = prop.option(value as? String) { Chip(text: o.name, color: o.color) }
        case "formula", "rollup": FxValueView(prop: prop, value: value, err: err, compact: true)
        case "person": PeopleChips(refs: value, people: people, compact: true)
        case "multi_select":                                // einzelne Chips: in WrapLayout bricht jeder fuer sich um
            let ids = (value as? [AnyHashable])?.compactMap { $0 as? String } ?? []
            ForEach(ids, id: \.self) { id in if let o = prop.option(id) { Chip(text: o.name, color: o.color) } }
        case "date":
            if let s = value as? String, !s.isEmpty { DateValueText(value: s).font(.caption).foregroundStyle(.secondary) }
        case "number", "calc":
            if let n = value as? Double { Text(numText(n, prop.fmt, prop.show)).font(.caption.monospacedDigit()).foregroundStyle(.secondary) }
        default: EmptyView()
        }
    }
}

func dayText(_ s: String) -> String {
    let p = DateFormatter.isoDay()
    guard let d = p.date(from: String(s.prefix(10))) else { return s }
    var style = SagaDate.dateTime().day().month(.abbreviated)                         // Format der App-Sprache, gregorianisch
    if !Calendar.current.isDate(d, equalTo: .now, toGranularity: .year) { style = style.year() }
    return d.formatted(style)
}
/// Zahl einer Eigenschaft (Zahl/Berechnet) als Text - Prozent ganzzahlig, Euro mit 2 Nachkommastellen (fix27 #10)
func numText(_ n: Double, _ fmt: String?, _ show: String? = nil) -> String {
    NumFormat.text(n, fmt: fmt)
}

// MARK: - Board: Spalten nach Auswahl, Karten ziehen aendert den Wert

struct BoardView: View {
    @ObservedObject var m: DBModel
    let view: [String: Any]?
    @EnvironmentObject var store: Store
    @EnvironmentObject var nav: NavModel
    @State private var avail: CGFloat = 0
    var body: some View {
        // 2.5: auch nach Status-Gruppe (group_status) und nach Formel/Rollup/Person (vorkommende Werte, nur lesend)
        let group = m.prop(view?["group_by"] as? String).flatMap { p in p.type == "select" || p.type == "person" || (p.isFx && ["number", "text", "checkbox", "date"].contains(p.rtype ?? "")) ? p : nil }
            ?? m.props.first { $0.type == "select" }
        let rows = m.rows(for: view)
        if let group {
            let byStatus = group.status && view?["group_status"] as? Bool == true
            let all = GroupLogic.groups(m.query, rows: rows, by: group, byStatus: byStatus)
            let editable = GroupLogic.draggable(group)
            // "Ohne …" zuletzt; leer nur bei Auswahl-Spalten zeigen (wie bisher)
            let cols = Array(all.dropFirst()) + (all.first.map { $0.count > 0 || (group.type == "select" && !byStatus) ? [$0] : [] } ?? [])
            let rowIDs = Set(rows.map(\.id)), byID = Dictionary(rows.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
            // Spalten fuellen die Breite, wenn alle passen; sonst 240 pt mit waagrechtem Scrollen und Hinweis am Rand
            let fit = avail > 0 && CGFloat(cols.count) * 240 + CGFloat(cols.count - 1) * 12 <= avail
            let colW = fit ? max(240, (avail - CGFloat(cols.count - 1) * 12) / CGFloat(max(1, cols.count))) : 240
            ScrollView(.horizontal, showsIndicators: !fit) {
                HStack(alignment: .top, spacing: 12) {
                    ForEach(Array(cols.enumerated()), id: \.offset) { _, col in
                        let items = col.ids.compactMap { byID[$0] }
                        let label = byStatus && col.id != nil ? (StatusLogic.symbols[col.id!] ?? "") + " " + col.label : col.label
                        VStack(alignment: .leading, spacing: 8) {
                            HStack { Chip(text: label, color: col.color); Text("\(items.count)").font(.caption).foregroundStyle(.secondary) }
                            ForEach(items) { r in
                                Button { nav.open(r.id, mode: view?["open"] as? String ?? "side", list: m.rows(for: view).map(\.id)) } label: { Card(m: m, row: r, view: view, cover: false) }.buttonStyle(.plain)
                                    .draggable(String(r.id))
                            }
                            if m.canAdd {
                                Button { Task { if let id = await m.add(store, props: newProps(group, col.id, byStatus: byStatus, editable: editable)) { nav.open(id, mode: view?["open"] as? String ?? "side") } } } label: {
                                    Label("Neu", systemImage: "plus").font(.caption)
                                }.buttonStyle(.plain).foregroundStyle(.secondary)
                            }
                        }
                        .padding(10).frame(width: colW, alignment: .topLeading)
                        .background(optColor(col.color).opacity(0.08), in: RoundedRectangle(cornerRadius: 10))
                        .dropDestination(for: String.self) { ids, _ in
                            guard editable else { return false }                                   // Formel/Person: nur lesend
                            let mine = ids.compactMap(Int.init).filter { rowIDs.contains($0) }    // nur Karten dieses Boards (keine Seiten aus der Seitenleiste)
                            for id in mine {
                                let cur = byID[id]?.props[group.id]?.base as? String
                                if byStatus { if case .some(let v) = StatusLogic.drop(group, current: cur, group: col.id) { Task { await m.set(id, group.id, v, store: store) } } }
                                else { Task { await m.set(id, group.id, col.id, store: store) } }
                            }
                            return !mine.isEmpty
                        }
                    }
                }
                .padding(.trailing, fit ? 0 : 24)
            }
            .overlay(alignment: .trailing) {                       // Hinweis: rechts geht es weiter
                if !fit { LinearGradient(colors: [.clear, Color.primary.opacity(0.08)], startPoint: .leading, endPoint: .trailing).frame(width: 24).allowsHitTesting(false) }
            }
            .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { avail = $0 }
        } else { Text("Board braucht eine Auswahl-Eigenschaft").foregroundStyle(.secondary) }
    }
}

extension BoardView {
    /// "+ Neu" in einer Spalte: Wert der Spalte (Status-Gruppe: erste Option); Formel/Person-Gruppen setzen nichts
    func newProps(_ g: DBProp, _ col: String?, byStatus: Bool, editable: Bool) -> [String: Any] {
        guard editable, let col else { return [:] }
        if byStatus { return g.firstOption(group: col).map { [g.id: $0.id] } ?? [:] }
        return [g.id: col]
    }
}

/// "blockiert" (Abhaengigkeit nicht erledigt) - nur Anzeige
struct BlockedBadge: View {
    var body: some View {
        Text(verbatim: "⛔").font(.caption).help(Text("Blockiert – eine Abhängigkeit ist noch nicht erledigt"))
            .accessibilityLabel(Text("Blockiert"))
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
                let ps = m.props.filter { !hidden.contains($0.id) && ["select", "multi_select", "date", "checkbox", "number", "calc", "formula", "rollup", "person"].contains($0.type) }.prefix(4)
                FlowChips(m: m, row: row, props: Array(ps))
            }.padding(cover ? 10 : 8)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .background {                                        // Karte + bedingte Farbe darueber (ein zweites .background laege dahinter)
            ZStack { Rectangle().fill(.background.secondary); if let c = m.color(row, view: view) { c.opacity(0.16) } }
                .clipShape(RoundedRectangle(cornerRadius: 9))
        }
        .overlay(alignment: .top) { if let c = m.color(row, view: view) { c.frame(height: 3) } }    // 2.5: Streifen oben wie im Web
        .overlay(RoundedRectangle(cornerRadius: 9).stroke(m.color(row, view: view)?.opacity(0.6) ?? .secondary.opacity(0.2)))
        .clipShape(RoundedRectangle(cornerRadius: 9))
        .overlay(alignment: .topTrailing) { if row.blocked { BlockedBadge().padding(6) } }
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
    var maxWidth: CGFloat = .infinity                       // hoechstens so breit (umbrechen), sonst nur so breit wie noetig
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
        let ls = lines(min(proposal.width ?? .infinity, maxWidth), subviews)
        let w = ls.map { l in l.reduce(0) { $0 + $1.1.width } + spacing * CGFloat(max(0, l.count - 1)) }.max() ?? 0
        let h = ls.map { l in l.map(\.1.height).max() ?? 0 }.reduce(0, +) + lineSpacing * CGFloat(max(0, ls.count - 1))
        return CGSize(width: min(w, proposal.width ?? w), height: h)
    }
    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var y = bounds.minY
        for l in lines(min(bounds.width, maxWidth), subviews) {
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
        let gs = CardChipLogic.groups(types: props.map(\.type), filled: props.map { hasValue(m.value($0, row)) })
        WrapLayout {
            ForEach(gs, id: \.self) { g in
                let p = props[g[0]]
                if g.count == 2, let a = m.value(p, row) as? String, let b = m.value(props[g[1]], row) as? String {
                    Text(CardChipLogic.range(dayText(a), dayText(b))).font(.caption).foregroundStyle(.secondary)   // Zeitraum (fix27 #17)
                }
                else if p.type == "checkbox", let b = row.props[p.id]?.base as? Bool, b { Image(systemName: "checkmark.square.fill").font(.caption).foregroundStyle(.secondary) }
                else { ValueChip(prop: p, value: m.value(p, row), err: row.fxErr[p.id], people: m.people) }
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
    static let dayFormat: DateFormatter = DateFormatter.isoDay()
    static let monthFormat: DateFormatter = DateFormatter.saga(template: "LLLLyyyy")
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
                Button { month = cal.date(byAdding: .month, value: -1, to: month)! } label: { Image(systemName: "chevron.left") }.accessibilityLabel("Vorheriger Monat")
                Text(mf.string(from: month)).font(.headline).frame(minWidth: 140)
                Button { month = cal.date(byAdding: .month, value: 1, to: month)! } label: { Image(systemName: "chevron.right") }.accessibilityLabel("Nächster Monat")
                Spacer()
                Button("Heute") { month = cal.date(from: cal.dateComponents([.year, .month], from: .now))! }.font(.caption)
            }.buttonStyle(.plain)
            if let dp {
                let rows = m.rows(for: view)
                LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 2), count: 7), spacing: 2) {
                    ForEach(Array(Self.weekdayHeads.enumerated()), id: \.offset) { Text($0.element).font(.caption2).foregroundStyle(.secondary) }
                    ForEach(0..<(lead + days), id: \.self) { i in
                        if i < lead { Color.clear.frame(minHeight: 64) } else {
                            let d = cal.date(byAdding: .day, value: i - lead, to: first)!, ds = f.string(from: d)
                            let items = TimelineLogic.rows(on: DBDate.days(cal.component(.year, from: d), cal.component(.month, from: d), cal.component(.day, from: d)), m.query, rows: rows, prop: dp)   // 2.5: Zeitraum auf jedem Tag, Tag in eigener Zeitzone
                            VStack(alignment: .leading, spacing: 2) {
                                HStack {
                                    Text("\(cal.component(.day, from: d))").font(.caption2.weight(cal.isDateInToday(d) ? .bold : .regular))
                                        .foregroundStyle(cal.isDateInToday(d) ? Color.accentColor : .secondary)
                                    Spacer()
                                    if !["created", "edited"].contains(dp.type) && m.canAdd {
                                        Button { Task { if let id = await m.add(store, props: [dp.id: ds]) { nav.open(id, mode: view?["open"] as? String ?? "side") } } } label: { Image(systemName: "plus").font(.caption2) }.buttonStyle(.plain).foregroundStyle(.tertiary).accessibilityLabel("Neuer Eintrag an diesem Tag")
                                    }
                                }
                                ForEach(items.prefix(3)) { r in
                                    let time = (m.value(dp, r) as? String).flatMap { DBDate.parse($0)?.start.instant }.map { $0.formatted(date: .omitted, time: .shortened) + " " } ?? ""
                                    Button { nav.open(r.id, mode: view?["open"] as? String ?? "side", list: m.rows(for: view).map(\.id)) } label: { Text(verbatim: time + (r.title.isEmpty ? String(localized: "Unbenannt") : r.title)).font(.caption2).lineLimit(1).padding(.horizontal, 3).frame(maxWidth: .infinity, alignment: .leading).background((m.color(r, view: view) ?? Color.accentColor).opacity(0.18), in: RoundedRectangle(cornerRadius: 3)) }.buttonStyle(.plain)
                                }
                                if items.count > 3 { Text("+\(items.count - 3)").font(.caption2).foregroundStyle(.secondary) }
                                Spacer(minLength: 0)
                            }
                            .padding(3).frame(minHeight: 64, alignment: .top).background(.secondary.opacity(0.06), in: RoundedRectangle(cornerRadius: 4))
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
    private var people: [String: [String: Any]] { (doc.page["people"] as? [String: Any] ?? [:]).compactMapValues { $0 as? [String: Any] } }
    private var fxErr: [String: String] { (doc.page["fx_err"] as? [String: Any] ?? [:]).compactMapValues { $0 as? String } }
    private var canEdit: Bool { !doc.access.readOnly }
    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            if doc.page["blocked"] as? Bool == true {                // 2.5: Abhaengigkeit offen
                Label { Text("Blockiert – eine Abhängigkeit ist noch nicht erledigt") } icon: { Text(verbatim: "⛔") }.font(.subheadline).foregroundStyle(.secondary)
            }
            ForEach(defs) { p in
                HStack(alignment: .center, spacing: 8) {
                    Label(p.name, systemImage: p.symbol).font(.subheadline).foregroundStyle(.secondary).frame(width: 150, alignment: .leading).lineLimit(1)
                    switch p.type {
                    case "formula", "rollup": FxValueView(prop: p, value: value(p), err: fxErr[p.id]).font(.subheadline).help(String(localized: "Formel im Web bearbeiten"))
                    case "person": PeopleEditor(prop: p, dbID: doc.page["parent_id"] as? Int ?? 0, value: value(p), people: people, canEdit: canEdit && store.features.people) { v in Task { await save(p, v) } }
                    case "files": FilesEditor(rowID: doc.id, value: value(p), max: (schema["props"] as? [[String: Any]])?.first { $0["id"] as? String == p.id }?["max"] as? Int ?? 10, canEdit: canEdit && store.features.files) { v in Task { await save(p, v) } }
                    default: PropEditor(prop: p, value: value(p), dateTime: store.features.datetime) { v in Task { await save(p, v) } }
                    }
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
        guard !p.isFx && p.type != "calc" else { return }                // berechnete Werte nie zurueckschreiben
        var props = doc.props; props[p.id] = v ?? NSNull(); doc.page["props"] = props
        do {
            let d = try await store.api("api/notes/\(doc.id)", method: "PATCH", body: ["props": [p.id: v ?? NSNull()]])
            // 2.5: berechnete Werte, gespeicherte Personen/Dateien und Personen sofort uebernehmen (kein Neuladen)
            if let j = (try? JSONSerialization.jsonObject(with: d)) as? [String: Any] {
                var ps = doc.props
                for (k, x) in (j["calc"] as? [String: Any] ?? [:]).merging(j["values"] as? [String: Any] ?? [:], uniquingKeysWith: { a, _ in a }) { ps[k] = x is NSNull ? nil : x }
                doc.page["props"] = ps
                if let e = j["fx_err"] { doc.page["fx_err"] = e }
                if let pp = j["people"] as? [String: Any] { doc.page["people"] = (doc.page["people"] as? [String: Any] ?? [:]).merging(pp) { _, b in b } }
            }
            store.dataChanged()
        }
        catch { doc.error = error.localizedDescription }
    }
}

struct PropEditor: View {
    let prop: DBProp
    let value: Any?
    /// 2.5 datetime1: Uhrzeit/Zeitraum bearbeiten (nur wenn der Server es kann)
    var dateTime = false
    let set: (Any?) -> Void
    @State private var text = ""
    @State private var base = ""                                   // Wert beim Uebernehmen: nur echte Eingaben zurueckschreiben
    @State private var date = Date()
    @FocusState private var focused: Bool
    var body: some View {
        switch prop.type {
        case "calc":                                              // berechnet: nur anzeigen
            if let n = value as? Double { Text(numText(n, prop.fmt, prop.show)).monospacedDigit() } else { Text("–").foregroundStyle(.secondary) }
        case "select" where prop.status:                          // 2.5: Status - Auswahl nach Gruppen
            Menu {
                ForEach(StatusLogic.grouped(prop), id: \.group) { g in
                    Section((StatusLogic.symbols[g.group] ?? "") + " " + StatusLogic.groupName(g.group)) {
                        ForEach(g.options, id: \.id) { o in Button(o.name) { set(o.id) } }
                    }
                }
                if value as? String != nil { Divider(); Button("Leeren", role: .destructive) { set(nil) } }
            } label: {
                if prop.option(value as? String) != nil { StatusChip(prop: prop, value: value) } else { leer }
            }.menuStyle(.button).buttonStyle(.plain).menuIndicator(.hidden).fixedSize()
        case "date" where dateTime || DBDate.parse(value)?.isNew == true:
            if dateTime { DateTimeEditor(prop: prop, value: value, set: set) }
            else { DateValueText(value: value).font(.subheadline) }   // aelterer Server: Uhrzeit/Zeitraum nur anzeigen
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
            Button { set(!on) } label: { CheckBoxIcon(on: on) }.buttonStyle(.plain)
                .accessibilityLabel(Text(prop.name)).accessibilityAddTraits(on ? .isSelected : [])
        case "date":
            let s = value as? String ?? ""
            HStack(spacing: 6) {
                if s.isEmpty {
                    Button { set(isoDay(.now)) } label: { leer }.buttonStyle(.plain)
                } else {
                    DatePicker("", selection: Binding(get: { parse(s) ?? .now }, set: { set(isoDay($0)) }), displayedComponents: .date).labelsHidden()
                    Button { set(nil) } label: { Image(systemName: "xmark.circle.fill").foregroundStyle(.tertiary) }.buttonStyle(.plain).accessibilityLabel("Datum entfernen")
                }
            }
        case "created", "edited":
            if let ts = value as? Double { Text(Date(timeIntervalSince1970: ts).sagaFormatted(date: .abbreviated, time: .shortened)).font(.subheadline) }
        case "relation":
            let n = (value as? [Any])?.count ?? 0
            Text(n == 0 ? "Leer" : "\(n) verknüpft").font(.subheadline).foregroundStyle(n == 0 ? .tertiary : .primary)
        case "formula", "rollup", "person", "files":              // 2.5: eigene Ansichten (EditableProps/TableGrid); hier nur lesen
            Text("Im Web bearbeiten").font(.subheadline).foregroundStyle(.tertiary)
        default:   // text, number, url, email, phone
            TextField("Leer", text: $text)
                .textFieldStyle(.plain).font(.subheadline)
                #if os(iOS)
                .keyboardType(prop.type == "number" ? .decimalPad : prop.type == "url" ? .URL : prop.type == "email" ? .emailAddress : prop.type == "phone" ? .phonePad : .default)
                #endif
                .focused($focused)
                .onAppear { text = shownView; base = text }
                .onChange(of: shown) { _, v in    // neuer Wert vom Server: uebernehmen statt alten zurueckschreiben
                    if !focused { text = shownView; base = text } else if text == base { text = v; base = v }
                }
                .onSubmit { commit() }
                .onChange(of: focused) { _, f in
                    // fix27 #10: Zahl ausserhalb der Bearbeitung mit Einheit ("48,50 €"), beim Bearbeiten ohne
                    if f { if text == base { text = shown; base = text } }
                    else { commit(); if prop.type == "number" { text = text.isEmpty ? "" : parsed(text).map { NumFormat.text($0, fmt: prop.fmt) } ?? text; base = text } }
                }
                .onDisappear { commit() }
        }
    }
    private var leer: some View { Text("Leer").font(.subheadline).foregroundStyle(.tertiary) }
    /// Wert als Text (wie im Eingabefeld)
    private var shown: String { value.map { v in (v as? Double).map { prop.type == "number" ? NumFormat.editText($0) : "\($0)" } ?? "\(v)" } ?? "" }
    /// Anzeige ohne Fokus: Zahl mit Format der Eigenschaft (fix27 #10), sonst wie im Eingabefeld
    private var shownView: String {
        if prop.type == "number", let n = value as? Double { return NumFormat.text(n, fmt: prop.fmt) }
        return shown
    }
    private func parsed(_ t: String) -> Double? { (try? Double(t, format: .number)) ?? Double(t) }
    private func commit() {
        guard text != base else { return }                          // nichts eingegeben -> nichts (Veraltetes) zurueckschreiben
        base = text
        let cur = value.map { "\($0)" } ?? ""
        if prop.type == "number" {
            let n = parsed(text)                                                 // Zahl in der Schreibweise der Systemsprache
            if text.isEmpty { if value != nil { set(nil) } } else if let n, n != (value as? Double) { set(n) }
        } else if text != cur { set(text.isEmpty ? nil : text) }
    }
    private func isoDay(_ d: Date) -> String { let f = DateFormatter.isoDay(); return f.string(from: d) }
    private func parse(_ s: String) -> Date? { let f = DateFormatter.isoDay(); return f.date(from: String(s.prefix(10))) }
}

/// Nur Web- und Mail-Links oeffnen (keine App-Schemata wie saga://pair oder file:)
func safeLink(_ s: String) -> URL? {
    guard let u = URL(string: s.trimmingCharacters(in: .whitespaces)), let sc = u.scheme?.lowercased(), ["http", "https", "mailto"].contains(sc) else { return nil }
    return u
}
