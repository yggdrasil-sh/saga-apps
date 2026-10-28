import SwiftUI

// Datenbanken bearbeiten (wie im Web): Spalten, Ansichten, Filter, Sortierung, Vorlagen.
// Das Schema wird vollstaendig zurueckgeschrieben; der Server prueft und bereinigt es (_clean_schema).

struct DBTemplate: Identifiable, Hashable { let id: Int; let title: String; let icon: String }

let dbTypes: [(String, String)] = [("text", String(localized: "Text")), ("number", String(localized: "Zahl")), ("select", String(localized: "Auswahl")), ("multi_select", String(localized: "Mehrfachauswahl")),
                                   ("date", String(localized: "Datum")), ("checkbox", String(localized: "Häkchen")), ("url", String(localized: "URL")), ("created", String(localized: "Erstellt")),
                                   ("edited", String(localized: "Bearbeitet")), ("calc", String(localized: "Berechnet")), ("relation", String(localized: "Relation"))]
let dbColors = ["gray", "brown", "orange", "yellow", "green", "blue", "purple", "pink", "red"]
let dbColorNames = ["gray": String(localized: "Grau"), "brown": String(localized: "Braun"), "orange": String(localized: "Orange"), "yellow": String(localized: "Gelb"), "green": String(localized: "Grün"), "blue": String(localized: "Blau"),
                    "purple": String(localized: "Lila"), "pink": String(localized: "Rosa"), "red": String(localized: "Rot")]
let dbCalcOps: [(String, String)] = [("div", "A ÷ B"), ("pct", String(localized: "A in Prozent von B")), ("mul", "A × B"), ("add", "A + B"), ("sub", "A − B")]
let viewTypes: [(String, String, String)] = [("table", String(localized: "Tabelle"), "tablecells"), ("board", String(localized: "Board"), "rectangle.split.3x1"),
                                             ("list", String(localized: "Liste"), "list.bullet"), ("gallery", String(localized: "Galerie"), "square.grid.2x2"),
                                             ("calendar", String(localized: "Kalender"), "calendar")]
func viewSymbol(_ t: String?) -> String {
    switch t {                                                         // 2.5: nur im Web einzurichten, Apps zeigen an
    case "chart": return "chart.bar.xaxis"
    case "timeline": return "chart.bar.doc.horizontal"
    default: return viewTypes.first { $0.0 == t }?.2 ?? "tablecells"
    }
}
func newPID() -> String { String((0..<8).map { _ in "abcdefghijklmnopqrstuvwxyz0123456789".randomElement()! }) }

/// Bedingungen je Typ (wie OPS im Web); `relative`: Server kann relfilter1, `status`: Status-Eigenschaft (status1)
func filterOps(_ type: String, relative: Bool = false, status: Bool = false) -> [(String, String)] {
    let empty = [("is_empty", String(localized: "ist leer")), ("not_empty", String(localized: "ist nicht leer"))]
    var rel = [("today", String(localized: "ist heute")), ("this_week", String(localized: "ist diese Woche")), ("this_month", String(localized: "ist diesen Monat"))]
    if relative {
        rel += [("yesterday", String(localized: "ist gestern")), ("tomorrow", String(localized: "ist morgen")), ("last_week", String(localized: "ist letzte Woche")),
                ("next_week", String(localized: "ist nächste Woche")), ("last_month", String(localized: "ist letzten Monat")), ("next_month", String(localized: "ist nächsten Monat")),
                ("this_year", String(localized: "ist dieses Jahr")), ("overdue", String(localized: "ist überfällig")), ("next_days", String(localized: "in den nächsten … Tagen")),
                ("past_days", String(localized: "in den letzten … Tagen")), ("on_or_before", String(localized: "am oder vor")), ("on_or_after", String(localized: "am oder nach"))]
    }
    if status && type == "select" {
        return [("status_group", String(localized: "Status-Gruppe")), ("is", String(localized: "ist")), ("is_not", String(localized: "ist nicht"))] + empty
    }
    switch type {
    case "person": return [("me", String(localized: "ist mir zugewiesen")), ("contains", String(localized: "enthält")), ("not_contains", String(localized: "enthält nicht"))] + empty
    case "files": return empty
    case "number", "calc": return [("eq", "="), ("gt", ">"), ("lt", "<")] + empty
    case "select": return [("is", String(localized: "ist")), ("is_not", String(localized: "ist nicht"))] + empty
    case "multi_select": return [("contains", String(localized: "enthält")), ("not_contains", String(localized: "enthält nicht"))] + empty
    case "relation": return empty
    case "date": return [("eq", String(localized: "am")), ("before", String(localized: "vor")), ("after", String(localized: "nach"))] + rel + empty
    case "created", "edited": return [("after", String(localized: "nach")), ("before", String(localized: "vor")), ("eq", String(localized: "am"))] + rel
    case "checkbox": return [("checked", String(localized: "angehakt")), ("unchecked", String(localized: "nicht angehakt"))]
    default: return [("contains", String(localized: "enthält")), ("not_contains", String(localized: "enthält nicht"))] + empty
    }
}
let filterNoValue: Set<String> = ["is_empty", "not_empty", "checked", "unchecked", "me"].union(DBDate.relOps)

extension DBModel {
    /// Schema gesperrt - oder nur lesen (Rolle Lesen/Kommentieren): keine Spalten/Ansichten aendern
    var locked: Bool { schema["locked"] as? Bool == true || readOnly || pageLocked }
    var schemaProps: [[String: Any]] { schema["props"] as? [[String: Any]] ?? [] }
    var schemaViews: [[String: Any]] { schema["views"] as? [[String: Any]] ?? [] }

    /// Schema aendern: sofort anzeigen, dann entprellt speichern (z. B. Umbenennen: ein PATCH statt einer pro Taste).
    /// Der Server kennt fuer das Schema keine Revision - deshalb vor dem Senden den aktuellen Server-Stand holen und
    /// die eigenen Aenderungen darauf anwenden (so gehen parallele Aenderungen aus dem Web, z. B. neue Spalten, nicht verloren).
    func editSchema(_ store: Store, _ change: @escaping (inout [String: Any]) -> Void) {
        var sc = schema
        if sc["props"] == nil { sc["props"] = [] as [[String: Any]] }
        change(&sc)
        schema = sc
        props = schemaProps.map(DBProp.init); views = schemaViews
        pendingSchema.append(change)
        schemaDebounce?.cancel()
        schemaDebounce = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(600))
            guard !Task.isCancelled, let self else { return }
            Task { await self.schemaQueue.run { await self.flushSchema(store) } }   // eigene Task: wird durch neue Eingaben nicht abgebrochen
        }
    }
    /// Offene Schema-Aenderungen senden (auf den frischen Server-Stand angewandt)
    func flushSchema(_ store: Store) async {
        guard !pendingSchema.isEmpty else { return }
        let changes = pendingSchema
        var base = schema
        if let d = try? await store.api("api/notes/\(dbID)/rows"), let j = try? JSONSerialization.jsonObject(with: d) as? [String: Any],
           let s = j["schema"] as? [String: Any] { base = s }
        if base["props"] == nil { base["props"] = [] as [[String: Any]] }
        for c in changes { c(&base) }
        do {
            _ = try await store.api("api/notes/\(dbID)", method: "PATCH", body: ["schema": base])
            pendingSchema.removeFirst(min(changes.count, pendingSchema.count))
            store.dataChanged()
        } catch {
            pendingSchema.removeFirst(min(changes.count, pendingSchema.count))   // abgelehnt (z. B. gesperrt): Server-Stand zeigen
            self.error = error.localizedDescription
        }
        if pendingSchema.isEmpty { await load(store) }
    }
    func editProp(_ store: Store, _ id: String, _ change: @escaping (inout [String: Any]) -> Void) {
        editSchema(store) { sc in
            var ps = sc["props"] as? [[String: Any]] ?? []
            if let i = ps.firstIndex(where: { $0["id"] as? String == id }) { change(&ps[i]) }
            sc["props"] = ps
        }
    }
    /// Ansichten der Datenbank - oder die eigenen Ansichten eines verknuepften Datenbank-Blocks (stehen in der Seite)
    var editViews: [[String: Any]] { localViews ?? schemaViews }
    func mutateViews(_ store: Store, _ change: @escaping (inout [[String: Any]]) -> Void) {
        if var lv = localViews {
            change(&lv); lv = Array(lv.prefix(10)); localViews = lv; onLocalViews?(lv); return
        }
        editSchema(store) { sc in
            var vs = sc["views"] as? [[String: Any]] ?? []
            if vs.isEmpty { vs = [["id": "tabelle", "name": String(localized: "Tabelle"), "type": "table"]] }
            change(&vs); sc["views"] = Array(vs.prefix(10))
        }
    }
    func editView(_ store: Store, _ id: String, _ change: @escaping (inout [String: Any]) -> Void) {
        mutateViews(store) { vs in if let i = vs.firstIndex(where: { $0["id"] as? String == id }) { change(&vs[i]) } }
    }
    func addProp(_ store: Store, type: String, target: Int? = nil, targetName: String? = nil) {
        var p: [String: Any] = ["id": newPID(), "name": dbTypes.first { $0.0 == type }?.1 ?? String(localized: "Eigenschaft"), "type": type]
        if type == "select" || type == "multi_select" { p["options"] = [] as [[String: Any]] }
        if type == "calc" {
            let ns = props.filter { $0.type == "number" }
            p["name"] = String(localized: "Fortschritt"); p["op"] = "pct"; p["fmt"] = "percent"; p["show"] = "ring"
            if ns.count > 0 { p["a"] = ns[0].id }; if ns.count > 1 { p["b"] = ns[1].id }
        }
        if type == "relation", let target { p["target"] = target; p["name"] = targetName ?? String(localized: "Relation"); p["two_way"] = target != dbID }
        editSchema(store) { sc in sc["props"] = (sc["props"] as? [[String: Any]] ?? []) + [p] }
    }
    func deleteProp(_ store: Store, _ id: String) {
        editSchema(store) { sc in sc["props"] = (sc["props"] as? [[String: Any]] ?? []).filter { $0["id"] as? String != id } }
    }
    @discardableResult
    func addView(_ store: Store, type: String) -> String {
        let id = "v" + String(newPID().prefix(7))
        mutateViews(store) { vs in vs.append(["id": id, "name": viewTypes.first { $0.0 == type }?.1 ?? String(localized: "Ansicht"), "type": type]) }
        return id
    }
    func deleteView(_ store: Store, _ id: String) {
        mutateViews(store) { vs in vs.removeAll { $0["id"] as? String == id } }
    }
    func duplicateView(_ store: Store, _ id: String) -> String? {
        guard var v = editViews.first(where: { $0["id"] as? String == id }), editViews.count < 10 else { return nil }
        let nid = "v" + String(newPID().prefix(7)); v["id"] = nid; v["name"] = String(localized: "\(v["name"] as? String ?? String(localized: "Ansicht")) (Kopie)")
        let copy = v
        mutateViews(store) { vs in vs.append(copy) }
        return nid
    }
    func newFromTemplate(_ store: Store, _ t: DBTemplate) async -> Int? {
        do {
            let d = try await store.api("api/notes/\(t.id)/duplicate", method: "POST", body: ["parent_id": dbID])
            guard let id = store.noteDuplicated(d) else { return nil }     // fix27 E4: "frisch" merken (Platzhalter, Verwerfen)
            await load(store); await store.refresh(); store.dataChanged()
            return id
        } catch { self.error = error.localizedDescription; return nil }    // z. B. Bearbeitende an alter Server-Fassung
    }
    // --- 2.5 Vorlagen verwalten -------------------------------------------------------------------------------------
    /// Standardvorlage fuer "Neu" (Schema default_tpl) - nur, wenn es die Vorlage noch gibt
    var defaultTemplate: DBTemplate? {
        guard let id = (schema["default_tpl"] as? NSNumber)?.intValue else { return nil }
        return templates.first { $0.id == id }
    }
    func setDefaultTemplate(_ store: Store, _ id: Int?) {
        editSchema(store) { sc in sc["default_tpl"] = id.map { $0 as Any } ?? NSNull() }
    }
    private func templateCall(_ store: Store, _ path: String, _ method: String, _ body: [String: Any]?) async -> Data? {
        do {
            let d = try await store.api(path, method: method, body: body)
            await load(store); await store.refresh(); store.dataChanged()
            return d
        } catch { self.error = error.localizedDescription; return nil }
    }
    func renameTemplate(_ store: Store, _ t: DBTemplate, _ title: String) async {
        let n = title.trimmingCharacters(in: .whitespacesAndNewlines)
        guard n != t.title else { return }
        _ = await templateCall(store, "api/notes/\(t.id)", "PATCH", ["title": n])
    }
    func duplicateTemplate(_ store: Store, _ t: DBTemplate) async {
        _ = await templateCall(store, "api/notes/\(t.id)/duplicate", "POST",
                               ["parent_id": dbID, "template": true, "title": String(localized: "\(t.title.isEmpty ? String(localized: "Vorlage") : t.title) (Kopie)")])
    }
    func untemplate(_ store: Store, _ t: DBTemplate) async {
        if defaultTemplate?.id == t.id { setDefaultTemplate(store, nil) }
        _ = await templateCall(store, "api/notes/\(t.id)", "PATCH", ["template": false])
    }
    func deleteTemplate(_ store: Store, _ t: DBTemplate) async {
        if defaultTemplate?.id == t.id { setDefaultTemplate(store, nil) }
        _ = await templateCall(store, "api/notes/\(t.id)", "DELETE", nil)
    }
    /// Neue, leere Vorlage -> ID (zum Oeffnen und Bearbeiten)
    func newTemplate(_ store: Store) async -> Int? {
        struct R: Decodable { let id: Int }
        guard let d = try? await store.api("api/notes", method: "POST", body: ["parent_id": dbID, "title": String(localized: "Neue Vorlage")]),
              let r = try? JSONDecoder().decode(R.self, from: d) else { return nil }
        _ = await templateCall(store, "api/notes/\(r.id)", "PATCH", ["template": true])
        return r.id
    }
    func rename(_ store: Store, row: Int, _ title: String) async {
        guard !readOnly else { return }                           // nur lesen (1.3)
        if let i = rows.firstIndex(where: { $0.id == row }) { rows[i].title = title }
        // Titel lokal in die Seitenliste (wie der Server ihn speichert: ohne Leerraum am Rand) statt alles neu zu laden
        do { _ = try await store.api("api/notes/\(row)", method: "PATCH", body: ["title": title]); store.updateTitle(row, title.trimmingCharacters(in: .whitespacesAndNewlines)) }
        catch { self.error = error.localizedDescription }
    }
}

// MARK: - Spalte bearbeiten (Klick auf den Spaltenkopf)

struct ColumnEditor: View {
    @ObservedObject var m: DBModel
    let pid: String
    @EnvironmentObject var store: Store
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var newOpt = ""
    private var p: [String: Any] { m.schemaProps.first { $0["id"] as? String == pid } ?? [:] }
    private var type: String { p["type"] as? String ?? "text" }
    private var options: [[String: Any]] { p["options"] as? [[String: Any]] ?? [] }

    /// 2.5: Formel/Rollup/Person/Dateien (und andere unbekannte Typen) richtet nur das Web ein - hier nicht aenderbar
    private var webOnly: Bool { !dbTypes.contains { $0.0 == type } }
    var body: some View {
        if webOnly {
            Form {
                LabeledContent("Name", value: name)
                Label(type == "formula" || type == "rollup" ? String(localized: "Formel im Web bearbeiten") : String(localized: "Diese Spalte lässt sich nur im Web einrichten."), systemImage: "safari")
                    .foregroundStyle(.secondary)
            }
            .formStyle(.grouped).macFrame(minWidth: 320, minHeight: 160)
            .onAppear { name = p["name"] as? String ?? "" }
        } else { editor }
    }
    private var editor: some View {
        Form {
            TextField("Name", text: $name).onSubmit { saveName() }
            Picker("Typ", selection: Binding(get: { type }, set: { t in
                m.editProp(store, pid) { d in
                    d["type"] = t
                    if (t == "select" || t == "multi_select") && d["options"] == nil { d["options"] = [] as [[String: Any]] }
                    if t == "calc" { d["op"] = d["op"] ?? "pct"; d["fmt"] = "percent"; d["show"] = "ring" }
                }
            })) { ForEach(dbTypes.filter { $0.0 != "relation" || type == "relation" }, id: \.0) { Text($0.1).tag($0.0) } }
            if type == "select" || type == "multi_select" {
                Section("Optionen") {
                    ForEach(Array(options.enumerated()), id: \.offset) { i, o in
                        HStack {
                            Chip(text: o["name"] as? String ?? "", color: o["color"] as? String)
                            Spacer()
                            Menu {
                                ForEach(dbColors, id: \.self) { c in Button(dbColorNames[c] ?? c) { setOpt(i) { $0["color"] = c } } }
                                Divider()
                                Button("Option löschen", role: .destructive) { m.editProp(store, pid) { d in var os = d["options"] as? [[String: Any]] ?? []; if i < os.count { os.remove(at: i) }; d["options"] = os } }
                            } label: { Image(systemName: "ellipsis.circle") }.menuIndicator(.hidden).fixedSize().accessibilityLabel("Optionen")
                        }
                    }
                    HStack {
                        TextField("Neue Option", text: $newOpt).onSubmit { addOpt() }
                        Button("Hinzufügen") { addOpt() }.disabled(newOpt.trimmingCharacters(in: .whitespaces).isEmpty)
                    }
                }
            }
            if type == "number" || type == "calc" {
                Picker("Format", selection: Binding(get: { p["fmt"] as? String ?? "" }, set: { f in m.editProp(store, pid) { d in d["fmt"] = f.isEmpty ? nil : f } })) {
                    Text("Zahl").tag(""); Text("Prozent").tag("percent"); Text("Euro").tag("euro")
                }
                Picker("Anzeigen als", selection: Binding(get: { p["show"] as? String ?? "" }, set: { f in m.editProp(store, pid) { d in d["show"] = f.isEmpty ? nil : f } })) {
                    Text("Zahl").tag(""); Text("Ring").tag("ring"); Text("Balken").tag("bar")
                }
            }
            if type == "calc" {
                let nums = m.props.filter { $0.type == "number" }
                Picker("Rechnung", selection: Binding(get: { p["op"] as? String ?? "div" }, set: { o in m.editProp(store, pid) { d in d["op"] = o } })) {
                    ForEach(dbCalcOps, id: \.0) { Text($0.1).tag($0.0) }
                }
                Picker("Zahl A", selection: Binding(get: { p["a"] as? String ?? "" }, set: { o in m.editProp(store, pid) { d in d["a"] = o.isEmpty ? nil : o } })) {
                    Text("–").tag(""); ForEach(nums) { Text($0.name).tag($0.id) }
                }
                Picker("Zahl B", selection: Binding(get: { p["b"] as? String ?? "" }, set: { o in m.editProp(store, pid) { d in d["b"] = o.isEmpty ? nil : o } })) {
                    Text("–").tag(""); ForEach(nums) { Text($0.name).tag($0.id) }
                }
            }
            Section {
                Button("Spalte löschen", role: .destructive) { m.deleteProp(store, pid); dismiss() }
            }
        }
        .formStyle(.grouped)
        .macFrame(minWidth: 320, minHeight: 260)
        .onAppear { name = p["name"] as? String ?? "" }
        .onDisappear { saveName() }
    }
    private func saveName() {
        let n = name.trimmingCharacters(in: .whitespaces)
        guard !n.isEmpty, n != p["name"] as? String else { return }
        m.editProp(store, pid) { d in d["name"] = n }
    }
    private func addOpt() {
        let n = newOpt.trimmingCharacters(in: .whitespaces); guard !n.isEmpty else { return }
        let c = dbColors[options.count % dbColors.count]
        m.editProp(store, pid) { d in d["options"] = (d["options"] as? [[String: Any]] ?? []) + [["id": newPID(), "name": n, "color": c]] }
        newOpt = ""
    }
    private func setOpt(_ i: Int, _ f: @escaping (inout [String: Any]) -> Void) {
        m.editProp(store, pid) { d in var os = d["options"] as? [[String: Any]] ?? []; if i < os.count { f(&os[i]) }; d["options"] = os }
    }
}

/// "+" am Ende der Kopfzeile: neue Spalte
struct AddColumnMenu: View {
    @ObservedObject var m: DBModel
    @EnvironmentObject var store: Store
    var body: some View {
        Menu {
            ForEach(dbTypes.filter { $0.0 != "relation" }, id: \.0) { t in Button(t.1) { m.addProp(store, type: t.0) } }
            Menu("Relation") {
                ForEach(store.relationTargets(for: m.dbID)) { d in Button(d.label) { m.addProp(store, type: "relation", target: d.id, targetName: d.label) } }
            }
        } label: { Image(systemName: "plus").foregroundStyle(.secondary) }
            .menuIndicator(.hidden).fixedSize().help("Spalte hinzufügen").accessibilityLabel("Spalte hinzufügen")
    }
}

// MARK: - Ansichten: Reiter, Suche, Einstellungen, "Neu ▾"

struct DBViewBar: View {
    @ObservedObject var m: DBModel
    let views: [[String: Any]]
    @Binding var current: String?
    let editable: Bool
    let onNew: (Int?) -> Void                            // neue Zeile (nil) bzw. aus Vorlage angelegt (id)
    /// V2: eingebetteter Block ohne Titel - "Als ganze Seite öffnen" neben dem Ansicht-Knopf (sonst kein Weg zur DB-Seite)
    var onOpenFull: (() -> Void)? = nil
    var dbName: String = ""
    @EnvironmentObject var store: Store
    @State private var settings = false
    @State private var renaming: String?
    @State private var newName = ""
    @State private var searching = false
    @FocusState private var searchFocus: Bool
    #if os(iOS)
    @Environment(\.horizontalSizeClass) private var hsc
    private var compact: Bool { hsc == .compact }
    #else
    private let compact = false
    #endif
    private var curID: String { current ?? (views.first?["id"] as? String ?? "") }
    private var view: [String: Any]? { views.first { $0["id"] as? String == curID } ?? views.first }
    private var canEdit: Bool { editable && !m.locked }

    var body: some View {
        Group {
            if compact {
                // iPhone: Ansicht als Menue (nichts wird abgeschnitten), Suche hinter der Lupe
                VStack(alignment: .leading, spacing: 6) {
                    // grosse Schrift: Ansichtsname in eigener Zeile statt nur "…" (F10)
                    ViewThatFits(in: .horizontal) {
                        HStack(spacing: 10) {
                            if m.showTabs { viewMenu } else { Spacer(minLength: 0) }
                            searchButton
                            settingsButton
                            newButton
                        }
                        VStack(alignment: .leading, spacing: 6) {
                            if m.showTabs { viewMenu }
                            HStack(spacing: 10) { searchButton; settingsButton; Spacer(minLength: 0); newButton }
                        }
                    }
                    if searching || !m.search.isEmpty { searchField.frame(maxWidth: .infinity) }
                }
            } else {
                // Reiter nehmen nur den Rest (keine layoutPriority: die waagrechte ScrollView ist gierig und quetschte
                // Suchfeld und "Neu" auf ihre Mindestbreite -> leere schwarze/goldene Pillen, Mac und iPad).
                // Rechts: feste Breiten; Suchfeld nur, wenn Platz ist, sonst Lupe.
                HStack(spacing: 6) {
                    if m.showTabs { tabs.frame(maxWidth: .infinity, alignment: .leading) } else { Spacer(minLength: 0) }   // 2.5: Reiter ausgeblendet
                    ViewThatFits(in: .horizontal) {
                        searchField.frame(width: 160)
                        HStack(spacing: 6) {
                            if searching || !m.search.isEmpty { searchField.frame(width: 120) }
                            searchButton
                        }
                    }
                    settingsButton
                    newButton
                }
            }
        }
        .alert("Ansicht umbenennen", isPresented: Binding(get: { renaming != nil }, set: { if !$0 { renaming = nil } })) {
            TextField("Name", text: $newName)
            Button("Sichern") { if let id = renaming { let n = newName; m.editView(store, id) { $0["name"] = n } }; renaming = nil }
            Button("Abbrechen", role: .cancel) { renaming = nil }
        }
    }

    private var tabs: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 2) {
                ForEach(views.indices, id: \.self) { i in
                    let v = views[i], id = v["id"] as? String ?? ""
                    Button { current = id } label: {
                        Label(v["name"] as? String ?? String(localized: "Ansicht"), systemImage: viewSymbol(v["type"] as? String))
                            .font(.subheadline.weight(id == curID ? .semibold : .regular))
                            .padding(.horizontal, 8).padding(.vertical, 4)
                            .background(id == curID ? Color.secondary.opacity(0.16) : .clear, in: Capsule())
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(id == curID ? .isSelected : [])
                    .contextMenu { if canEdit { viewActions(v, id) } }
                }
                if canEdit { addViewMenu }
            }
        }
    }

    /// iPhone: aktuelle Ansicht als Knopf mit Menue (wechseln, anlegen, umbenennen ...)
    private var viewMenu: some View {
        Menu {
            ForEach(views.indices, id: \.self) { i in
                let v = views[i], id = v["id"] as? String ?? ""
                Button { current = id } label: {
                    Label(v["name"] as? String ?? String(localized: "Ansicht"), systemImage: id == curID ? "checkmark" : viewSymbol(v["type"] as? String))
                }
            }
            if canEdit {
                Divider()
                Menu { ForEach(viewTypes, id: \.0) { t in Button { current = m.addView(store, type: t.0) } label: { Label(t.1, systemImage: t.2) } } }
                    label: { Label("Ansicht hinzufügen", systemImage: "plus") }
                if let v = view, let id = v["id"] as? String { Section { viewActions(v, id) } }
            }
        } label: {
            HStack(spacing: 4) {
                Label(view?["name"] as? String ?? String(localized: "Ansicht"), systemImage: viewSymbol(view?["type"] as? String))
                    .font(.subheadline.weight(.semibold)).lineLimit(2)
                Image(systemName: "chevron.down").font(.caption2.weight(.semibold)).foregroundStyle(.secondary)
            }
            .padding(.horizontal, 10).padding(.vertical, 5)
            .background(Color.secondary.opacity(0.14), in: Capsule())
        }
        .menuStyle(.button).buttonStyle(.plain).menuIndicator(.hidden)
        .frame(maxWidth: .infinity, alignment: .leading)       // lange Ansichtsnamen werden gekuerzt und verdraengen "Neu" nicht
        .accessibilityLabel(String(localized: "Ansicht: \(view?["name"] as? String ?? String(localized: "Ansicht"))"))
    }

    @ViewBuilder private func viewActions(_ v: [String: Any], _ id: String) -> some View {
        Button("Umbenennen") { newName = v["name"] as? String ?? ""; renaming = id }
        Button("Duplizieren") { if let n = m.duplicateView(store, id) { current = n } }
        Menu("Layout") { ForEach(viewTypes, id: \.0) { t in Button { m.editView(store, id) { $0["type"] = t.0 } } label: { Label(t.1, systemImage: t.2) } } }
        if views.count > 1 { Divider(); Button("Ansicht löschen", role: .destructive) { m.deleteView(store, id); current = nil } }
    }

    private var addViewMenu: some View {
        Menu {
            ForEach(viewTypes, id: \.0) { t in Button { current = m.addView(store, type: t.0) } label: { Label(t.1, systemImage: t.2) } }
        } label: { Image(systemName: "plus").foregroundStyle(.secondary) }
            .menuIndicator(.hidden).fixedSize().help("Ansicht hinzufügen").accessibilityLabel("Ansicht hinzufügen")
    }

    private var searchField: some View {
        TextField("Suchen", text: $m.search).textFieldStyle(.roundedBorder).focused($searchFocus)
            .onSubmit { if m.search.isEmpty { searching = false } }
    }
    private var searchButton: some View {
        Button { searching.toggle(); if searching { searchFocus = true } else { m.search = "" } } label: {
            Image(systemName: searching || !m.search.isEmpty ? "xmark.circle" : "magnifyingglass")
        }
        .buttonStyle(.plain).foregroundStyle(.secondary)
        .accessibilityLabel(searching ? "Suche schließen" : "Suchen")
    }

    @ViewBuilder private var settingsButton: some View {
        if canEdit {
            // aktiver Filter sichtbar (N6): Symbol in Akzentfarbe mit Zahl, sonst "fehlten" Eintraege einfach
            let nf = DBFilterBadge.count(view)
            Button { settings = true } label: {
                Image(systemName: "slider.horizontal.3")
                    .foregroundStyle(nf > 0 ? AnyShapeStyle(Color.accentColor) : AnyShapeStyle(HierarchicalShapeStyle.secondary))
                    .overlay(alignment: .topTrailing) {
                        if nf > 0 {
                            Text(verbatim: "\(nf)").font(.caption2.bold()).foregroundStyle(Color("OnAccent"))
                                .padding(.horizontal, 4).background(Color.accentColor, in: Capsule()).offset(x: 9, y: -8)
                        }
                    }
            }.buttonStyle(.plain).fixedSize()
                .help("Ansicht: Filter, Sortierung, Eigenschaften")
                .accessibilityLabel("Ansicht: Filter, Sortierung, Eigenschaften")
                .accessibilityValue(nf > 0 ? Text("\(nf) Filter aktiv") : Text(verbatim: ""))
                .settingsPopover(isPresented: $settings, title: "Ansicht") {
                    if let id = view?["id"] as? String { ViewSettings(m: m, viewID: id) }
                }
        }
        if let onOpenFull {
            Button(action: onOpenFull) { Image(systemName: "arrow.up.left.and.arrow.down.right") }
                .buttonStyle(.plain).foregroundStyle(.secondary).fixedSize()
                .help("Als ganze Seite öffnen")
                .accessibilityLabel(dbName.isEmpty ? Text("Als ganze Seite öffnen") : Text("„\(dbName)“ als ganze Seite öffnen"))
        }
    }

    @ViewBuilder private var newButton: some View {
        if m.pageLocked && !m.readOnly {
            EmptyView()                                                                     // Seite gesperrt: kein "Neu"
        } else if m.readOnly {
            Label("Nur lesen", systemImage: "eye").font(.caption).foregroundStyle(.secondary)
                .padding(.horizontal, 8).padding(.vertical, 3).background(Color.secondary.opacity(0.12), in: Capsule()).fixedSize()
        } else if m.templates.isEmpty {
            Button("Neu") { onNew(nil) }.prominentGold().controlSize(.small).fixedSize()      // nie zur leeren Pille quetschen
        } else {
            Menu {
                Button("Leerer Eintrag") { onNew(0) }
                Section("Vorlagen") {
                    ForEach(m.templates) { t in
                        let name = (t.icon.count <= 4 && !t.icon.isEmpty ? t.icon + " " : "") + (t.title.isEmpty ? String(localized: "Vorlage") : t.title)
                        Button(m.defaultTemplate?.id == t.id ? String(localized: "\(name) (Standard)") : name) {
                            Task { if let id = await m.newFromTemplate(store, t) { onNew(id) } } }
                    }
                }
                if m.role == "owner" { Divider(); Button("Vorlagen verwalten …") { m.managingTemplates = true } }
            } label: {
                // I02: iOS zeigt bei Menu mit primaryAction keinen Pfeil - selbst setzen (langer Druck: Vorlagen)
                HStack(spacing: 3) { Text("Neu"); Image(systemName: "chevron.down").font(.caption2.weight(.semibold)).accessibilityHidden(true) }
            } primaryAction: { onNew(nil) }
            .menuStyle(.button).prominentGold().controlSize(.small).fixedSize()
        }
    }
}

/// Einstellungen einer Ansicht (wie "⚙ Ansicht" + Filter + Sortierung im Web)
struct ViewSettings: View {
    @ObservedObject var m: DBModel
    let viewID: String
    @EnvironmentObject var store: Store
    private var v: [String: Any] { m.editViews.first { $0["id"] as? String == viewID } ?? ["id": viewID, "type": "table"] }
    private var type: String { v["type"] as? String ?? "table" }
    private var hidden: [String] { v["hidden"] as? [String] ?? [] }
    private var sorts: [[String: Any]] { v["sorts"] as? [[String: Any]] ?? [] }
    private var filters: [[String: Any]] { v["filters"] as? [[String: Any]] ?? [] }
    private var selects: [DBProp] { m.props.filter { $0.type == "select" } }
    private func set(_ f: @escaping (inout [String: Any]) -> Void) { m.editView(store, viewID, f) }

    @Environment(\.dismiss) private var dismiss
    var body: some View {
        Form {
            if let head = m.onHead {                                 // 2.5: eingebetteter Block - Kopf ein-/ausblenden
                Section("Dieser Block") {
                    Toggle("Datenbanktitel anzeigen", isOn: Binding(get: { m.showTitle }, set: { m.showTitle = $0; head("title", $0) }))
                    Toggle("Ansichts-Reiter anzeigen", isOn: Binding(get: { m.showTabs }, set: { m.showTabs = $0; head("tabs", $0) }))
                }
            }
            if m.role == "owner" {
                Section {
                    Button { dismiss(); Task { try? await Task.sleep(for: .milliseconds(250)); m.managingTemplates = true } } label: {
                        Label(m.templates.isEmpty ? String(localized: "Vorlagen verwalten …") : String(localized: "Vorlagen verwalten … (\(m.templates.count))"), systemImage: "doc.on.doc")
                    }
                }
            }
            Section("Ansicht") {
                TextField("Name", text: Binding(get: { v["name"] as? String ?? "" }, set: { n in set { $0["name"] = n } }))
                if viewTypes.contains(where: { $0.0 == type }) {
                    Picker("Layout", selection: Binding(get: { type }, set: { t in set { $0["type"] = t } })) {
                        ForEach(viewTypes, id: \.0) { Label($0.1, systemImage: $0.2).tag($0.0) }
                    }
                } else {                                                    // 2.5: Diagramm/Zeitleiste - einrichten im Web
                    LabeledContent("Layout") { Label(type == "chart" ? String(localized: "Diagramm") : type == "timeline" ? String(localized: "Zeitleiste") : type, systemImage: viewSymbol(type)) }
                }
                if type == "board" {
                    Picker("Gruppieren nach", selection: Binding(get: { v["group_by"] as? String ?? (selects.first?.id ?? "") }, set: { g in set { $0["group_by"] = g } })) {
                        ForEach(selects) { Text($0.name).tag($0.id) }
                    }
                }
                if type == "table" || type == "list" {
                    Picker("Gruppieren", selection: Binding(get: { v["group"] as? String ?? "" }, set: { g in set { $0["group"] = g.isEmpty ? nil : g } })) {
                        Text("nicht gruppieren").tag(""); ForEach(selects) { Text($0.name).tag($0.id) }
                    }
                }
                if type == "calendar" {
                    let dates = m.props.filter { ["date", "created", "edited"].contains($0.type) }
                    Picker("Datum", selection: Binding(get: { v["date_by"] as? String ?? (dates.first?.id ?? "") }, set: { g in set { $0["date_by"] = g } })) {
                        ForEach(dates) { Text($0.name).tag($0.id) }
                    }
                }
                if type == "gallery" {
                    Picker("Kartengröße", selection: Binding(get: { v["card"] as? String ?? "" }, set: { c in set { $0["card"] = c.isEmpty ? nil : c } })) {
                        Text("Mittel").tag(""); Text("Klein").tag("s"); Text("Groß").tag("l")
                    }
                }
                Picker("Einträge öffnen", selection: Binding(get: { v["open"] as? String ?? "" }, set: { o in set { $0["open"] = o.isEmpty ? nil : o } })) {
                    Text("Seitenansicht").tag(""); Text("Mitte").tag("center"); Text("Ganze Seite").tag("page")
                }
            }
            Section("Eigenschaften") {
                ForEach(m.props) { p in
                    Toggle(isOn: Binding(get: { !hidden.contains(p.id) }, set: { on in
                        set { d in var h = d["hidden"] as? [String] ?? []; if on { h.removeAll { $0 == p.id } } else if !h.contains(p.id) { h.append(p.id) }; d["hidden"] = h.isEmpty ? nil : h }
                    })) { Label(p.name, systemImage: p.symbol) }
                }
            }
            Section("Sortierung") {
                ForEach(Array(sorts.enumerated()), id: \.offset) { i, s in
                    HStack {
                        Picker("", selection: Binding(get: { s["pid"] as? String ?? "title" }, set: { pid in set { d in var ss = d["sorts"] as? [[String: Any]] ?? []; if i < ss.count { ss[i]["pid"] = pid }; d["sorts"] = ss } })) {
                            Text("Name").tag("title"); ForEach(m.props) { Text($0.name).tag($0.id) }
                        }.labelsHidden()
                        Picker("", selection: Binding(get: { s["dir"] as? String ?? "asc" }, set: { dir in set { d in var ss = d["sorts"] as? [[String: Any]] ?? []; if i < ss.count { ss[i]["dir"] = dir }; d["sorts"] = ss } })) {
                            Text("Aufsteigend").tag("asc"); Text("Absteigend").tag("desc")
                        }.labelsHidden().fixedSize()
                        Button { set { d in var ss = d["sorts"] as? [[String: Any]] ?? []; if i < ss.count { ss.remove(at: i) }; d["sorts"] = ss.isEmpty ? nil : ss } } label: { Image(systemName: "xmark.circle.fill").foregroundStyle(.tertiary) }.buttonStyle(.plain).accessibilityLabel("Sortierung entfernen")
                    }
                }
                if sorts.count < 3 {
                    Button("Sortierung hinzufügen") { set { d in d["sorts"] = (d["sorts"] as? [[String: Any]] ?? []) + [["pid": "title", "dir": "asc"]] } }
                }
            }
            Section("Filter (alle müssen zutreffen)") {
                ForEach(Array(filters.enumerated()), id: \.offset) { i, f in FilterRow(m: m, viewID: viewID, index: i, f: f) }
                if filters.count < 10, let first = m.props.first {
                    Button("Filter hinzufügen") { set { d in d["filters"] = (d["filters"] as? [[String: Any]] ?? []) + [["pid": first.id, "op": filterOps(first.type)[0].0, "value": ""]] } }
                }
            }
        }
        .formStyle(.grouped)
        .macFrame(minWidth: 380, minHeight: 420)
    }
}

struct FilterRow: View {
    @ObservedObject var m: DBModel
    let viewID: String
    let index: Int
    let f: [String: Any]
    @EnvironmentObject var store: Store
    @State private var text = ""
    private var pid: String { f["pid"] as? String ?? "title" }
    private var prop: DBProp? { pid == "title" ? DBProp(["id": "title", "name": String(localized: "Name"), "type": "text"]) : m.prop(pid) }
    private var op: String { f["op"] as? String ?? "" }
    private func set(_ k: String, _ val: Any?) {
        m.editView(store, viewID) { d in var fs = d["filters"] as? [[String: Any]] ?? []; if index < fs.count { fs[index][k] = val }; d["filters"] = fs }
    }
    var body: some View {
        #if os(iOS)
        // Schmal (iPhone): Eigenschaft/Bedingung oben, Wert darunter - in einer Zeile war kein Platz
        VStack(alignment: .leading, spacing: 8) {
            HStack { propPicker; opPicker; Spacer(minLength: 0); removeButton }
            valueField
        }
        #else
        HStack { propPicker; opPicker; valueField; Spacer(minLength: 0); removeButton }
        #endif
    }
    private var propPicker: some View {
            Picker("", selection: Binding(get: { pid }, set: { p in
                let t = p == "title" ? "text" : (m.prop(p)?.type ?? "text")
                m.editView(store, viewID) { d in var fs = d["filters"] as? [[String: Any]] ?? []; if index < fs.count { fs[index] = ["pid": p, "op": filterOps(t)[0].0, "value": ""] }; d["filters"] = fs }
            })) { Text("Name").tag("title"); ForEach(m.props) { Text($0.name).tag($0.id) } }.labelsHidden()
                .accessibilityLabel(Text("Eigenschaft"))
    }
    private var opPicker: some View {
            Picker("", selection: Binding(get: { op }, set: { set("op", $0) })) {
                ForEach(ops, id: \.0) { Text($0.1).tag($0.0) }
            }.labelsHidden().fixedSize().accessibilityLabel(Text("Bedingung"))
    }
    /// Bedingungen fuer diese Eigenschaft (Formel/Rollup nach Ergebnistyp; ein unbekannter gespeicherter op bleibt waehlbar)
    private var ops: [(String, String)] {
        let t = prop.map { $0.isFx ? ($0.rtype == "list" ? "multi_select" : $0.rtype == "checkbox" ? "checkbox" : $0.rtype == "date" ? "date" : $0.rtype == "number" ? "number" : "text") : $0.type } ?? "text"
        var o = filterOps(t, relative: store.features.has("relfilter1"), status: prop?.status == true && store.features.status)
        if !op.isEmpty && !o.contains(where: { $0.0 == op }) { o.append((op, op)) }
        return o
    }
    @ViewBuilder private var valueField: some View {
            if op == "status_group" {
                Picker("", selection: Binding(get: { f["value"] as? String ?? "" }, set: { set("value", $0) })) {
                    Text("– wählen –").tag(""); Text("Offen (nicht erledigt)").tag("open")
                    ForEach(DBQuery.statusGroups, id: \.self) { g in Text(verbatim: (StatusLogic.symbols[g] ?? "") + " " + StatusLogic.groupName(g)).tag(g) }
                }.labelsHidden()
            } else if op == "next_days" || op == "past_days" {
                TextField("Tage", text: $text).onSubmit { set("value", text) }
                    .onAppear { text = f["value"].map { "\($0)" } ?? "" }.onDisappear { if text != (f["value"].map { "\($0)" } ?? "") { set("value", text) } }
                    .textFieldStyle(.roundedBorder).frame(maxWidth: 80)
                    #if os(iOS)
                    .keyboardType(.numberPad)
                    #endif
            } else if (op == "contains" || op == "not_contains"), prop?.type == "person" {
                Picker("", selection: Binding(get: { f["value"] as? String ?? "" }, set: { set("value", $0) })) {
                    Text("– wählen –").tag("")
                    ForEach(PeopleLogic.directory(m.people).values.sorted { $0.name < $1.name }) { p in Text(verbatim: PeopleLogic.label(p)).tag(p.ref) }
                }.labelsHidden()
            } else if !filterNoValue.contains(op), let p = prop {
                switch p.type {
                case "select", "multi_select":
                    Picker("", selection: Binding(get: { f["value"] as? String ?? "" }, set: { set("value", $0) })) {
                        Text("– wählen –").tag(""); ForEach(p.options, id: \.id) { Text($0.name).tag($0.id) }
                    }.labelsHidden()
                case "date", "created", "edited":
                    DatePicker("", selection: Binding(get: { dayDate(f["value"] as? String) ?? .now }, set: { set("value", dayString($0)) }), displayedComponents: .date).labelsHidden()
                default:
                    TextField("Wert", text: $text).onSubmit { set("value", text) }
                        .onAppear { text = f["value"].map { "\($0)" } ?? "" }.onDisappear { if text != (f["value"].map { "\($0)" } ?? "") { set("value", text) } }
                        .textFieldStyle(.roundedBorder)
                }
            }
    }
    private var removeButton: some View {
            Button {
                m.editView(store, viewID) { d in var fs = d["filters"] as? [[String: Any]] ?? []; if index < fs.count { fs.remove(at: index) }; d["filters"] = fs.isEmpty ? nil : fs }
            } label: { Image(systemName: "xmark.circle.fill").foregroundStyle(.tertiary) }.buttonStyle(.borderless).accessibilityLabel("Filter entfernen")
    }
}

func dayString(_ d: Date) -> String { let f = DateFormatter.isoDay(); return f.string(from: d) }
func dayDate(_ s: String?) -> Date? { guard let s else { return nil }; let f = DateFormatter.isoDay(); return f.date(from: String(s.prefix(10))) }

extension Store {
    /// Ziel-Datenbanken einer Relation: eigene Datenbank -> eigene; Datenbank eines anderen Servers (2.3) -> nur geteilte
    /// Datenbanken desselben Servers (andere Ziele verwirft dessen Server)
    func relationTargets(for dbID: Int) -> [Page] {
        guard FedLogic.isRemote(dbID) else { return pages.filter(\.isDatabase) }
        let peer = page(dbID)?.remote?.peer
        return fedShared.filter { $0.isDatabase && $0.remote?.peer == peer }
    }
}


// MARK: - Vorlagen verwalten (2.5)

/// Vorlagen einer Datenbank: umbenennen (direkt im Feld), bearbeiten, duplizieren, Standard fuer "Neu", "Keine Vorlage
/// mehr", Papierkorb - wie im Web (Datenbank -> Neu ▾ -> Vorlagen verwalten). Nur der Eigentuemer.
struct TemplateManager: View {
    @ObservedObject var m: DBModel
    @EnvironmentObject var store: Store
    @EnvironmentObject var nav: NavModel
    @Environment(\.dismiss) private var dismiss
    @State private var names: [Int: String] = [:]
    @State private var trash: DBTemplate?
    @State private var busy = false

    private func saveNames() async {
        for t in m.templates { if let n = names[t.id], n != t.title { await m.renameTemplate(store, t, n) } }
        names = [:]
    }
    private func run(_ f: @escaping () async -> Void) { Task { busy = true; await saveNames(); await f(); busy = false } }
    #if os(iOS)
    @Environment(\.horizontalSizeClass) private var hsc
    private var compact: Bool { hsc == .compact }
    #else
    private let compact = false
    #endif

    @ViewBuilder private func nameField(_ t: DBTemplate) -> some View {
        PageIcon(icon: t.icon.isEmpty ? nil : t.icon, store: store, fallback: "📐")
        TextField("Name der Vorlage", text: Binding(get: { names[t.id] ?? t.title }, set: { names[t.id] = $0 }), prompt: Text("Unbenannt"))
            .textFieldStyle(.plain)
            .onSubmit { run {} }
    }
    @ViewBuilder private func badge(_ t: DBTemplate) -> some View {
        if m.defaultTemplate?.id == t.id {
            Text("Standard").font(.caption2.weight(.semibold)).foregroundStyle(Color.accentColor)
                .padding(.horizontal, 6).padding(.vertical, 2).overlay(Capsule().stroke(Color.accentColor.opacity(0.5)))
        }
    }
    @ViewBuilder private func actions(_ t: DBTemplate) -> some View {
        Button("Bearbeiten") { run { dismiss(); nav.push(PageRoute(id: t.id)) } }
            .buttonStyle(.bordered).controlSize(.small)
        Menu {
            Button { run { await m.duplicateTemplate(store, t) } } label: { Label("Duplizieren", systemImage: "plus.square.on.square") }
            if m.defaultTemplate?.id == t.id {
                Button { run { m.setDefaultTemplate(store, nil) } } label: { Label("Nicht mehr Standard", systemImage: "star.slash") }
            } else {
                Button { run { m.setDefaultTemplate(store, t.id) } } label: { Label("Als Standard für „Neu“", systemImage: "star") }
            }
            Button { run { await m.untemplate(store, t) } } label: { Label("Keine Vorlage mehr", systemImage: "doc") }
            Divider()
            Button(role: .destructive) { trash = t } label: { Label("In den Papierkorb", systemImage: "trash") }
        } label: { Image(systemName: "ellipsis.circle") }
            .menuStyle(.button).buttonStyle(.plain).menuIndicator(.hidden).fixedSize()
            .accessibilityLabel("Weitere Aktionen")
    }

    var body: some View {
        NavigationStack {
            List {
                if m.templates.isEmpty {
                    Text("Diese Datenbank hat noch keine Vorlagen.").foregroundStyle(.secondary)
                }
                ForEach(m.templates) { t in
                    Group {
                        if compact {                                    // I08: iPhone - Name in voller Breite, Knoepfe darunter
                            VStack(alignment: .leading, spacing: 6) {
                                HStack(spacing: 8) { nameField(t) }
                                HStack(spacing: 8) { badge(t); Spacer(minLength: 0); actions(t) }
                            }
                        } else {
                            HStack(spacing: 8) { nameField(t); badge(t); actions(t) }
                        }
                    }
                    .padding(.vertical, 2)
                }
                if let e = m.error { Text(e).font(.caption).foregroundStyle(.orange) }
            }
            .disabled(busy)
            .navigationTitle("Vorlagen dieser Datenbank")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Fertig") { run { dismiss() } } }
                ToolbarItem(placement: .primaryAction) {
                    Button { run { if let id = await m.newTemplate(store) { dismiss(); nav.focusTitle = id; nav.push(PageRoute(id: id)) } } } label: {
                        Label("Neue Vorlage", systemImage: "plus")
                    }
                }
            }
            .alert(Text("Vorlage „\(trash?.title ?? "")“ in den Papierkorb?"), isPresented: Binding(get: { trash != nil }, set: { if !$0 { trash = nil } })) {
                Button("In den Papierkorb", role: .destructive) { if let t = trash { run { await m.deleteTemplate(store, t) } }; trash = nil }
                Button("Abbrechen", role: .cancel) { trash = nil }
            } message: { Text("Du kannst sie dort jederzeit wiederherstellen.") }
        }
        #if os(macOS)
        .frame(minWidth: 520, minHeight: 340)
        #endif
        .onDisappear { if !names.isEmpty { let n = names; Task { for t in m.templates { if let x = n[t.id], x != t.title { await m.renameTemplate(store, t, x) } } } } }   // R11: auch beim Wegwischen
    }
}
