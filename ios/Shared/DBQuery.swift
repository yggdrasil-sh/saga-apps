import Foundation

// Datenbank-Logik ohne Oberflaeche - gemeinsam fuer App und Widget, damit beide GENAU wie das Web filtern/sortieren.

struct DBOption: Hashable {
    let id: String; let name: String; let color: String?
    /// 2.5 Status (status1): Gruppe todo/doing/done (nur bei Status-Eigenschaften gesetzt)
    var group: String? = nil
}
struct DBProp: Identifiable, Hashable {
    let id: String; let name: String; let type: String; let options: [DBOption]; let fmt: String?
    var a: String? = nil, b: String? = nil, op: String? = nil, show: String? = nil      // "Berechnet": A op B
    /// 2.5: Formel/Rollup (Ergebnistyp setzt der Server), Status, Datum mit Uhrzeit/Zeitraum, Personen, Unteraufgaben
    var rtype: String? = nil, status = false, time = false, range = false, multi = true, role: String? = nil
    init(_ d: [String: Any]) {
        id = d["id"] as? String ?? ""; name = d["name"] as? String ?? ""; type = d["type"] as? String ?? "text"; fmt = d["fmt"] as? String
        a = d["a"] as? String; b = d["b"] as? String; op = d["op"] as? String; show = d["show"] as? String
        rtype = d["rtype"] as? String; status = type == "select" && d["status"] as? Bool == true
        time = d["time"] as? Bool == true; range = d["range"] as? Bool == true; multi = d["multi"] as? Bool != false
        role = d["role"] as? String
        let st = status
        options = (d["options"] as? [[String: Any]] ?? []).map { o in
            let g = o["group"] as? String
            return DBOption(id: o["id"] as? String ?? "", name: o["name"] as? String ?? "", color: o["color"] as? String,
                            group: st ? (DBQuery.statusGroups.contains(g ?? "") ? g : "todo") : nil)
        }
    }
    /// Formel oder Rollup: Wert fertig vom Server, nie bearbeiten
    var isFx: Bool { type == "formula" || type == "rollup" }
    /// Typ, nach dem Filter/Sortierung/Anzeige gehen (Formel/Rollup: Ergebnistyp)
    var valueType: String { isFx ? (rtype ?? "text") : type }
    /// Status-Gruppe einer Option (nil: keine Status-Eigenschaft oder unbekannte Option)
    func statusGroup(_ v: Any?) -> String? {
        guard status, let s = v as? String else { return nil }
        return option(s)?.group
    }
    /// Erste Option einer Status-Gruppe (Abhaken, Board nach Gruppen)
    func firstOption(group: String) -> DBOption? { options.first { $0.group == group } }
    func option(_ id: String?) -> DBOption? { options.first { $0.id == id } }
    var symbol: String {
        switch type {
        case "select": return "chevron.down.circle"; case "multi_select": return "list.bullet"; case "checkbox": return "checkmark.square"
        case "date": return "calendar"; case "number": return "number"; case "url": return "link"; case "email": return "at"
        case "phone": return "phone"; case "relation": return "arrow.up.right"; case "created", "edited": return "clock"
        case "calc", "formula": return "function"
        case "rollup": return "sum"
        case "person": return "person"
        case "files": return "paperclip"
        default: return "text.alignleft"
        }
    }
}
struct DBRow: Identifiable, Hashable {
    let id: Int; var title: String; var icon: String?; var cover: String?; var props: [String: AnyHashable]; var created: Double; var updated: Double
    /// 2.5: Laufzeitfehler je Formel-Zelle (Code), "blockiert" (Abhaengigkeit offen), Einrueckung (Unteraufgaben)
    var fxErr: [String: String] = [:]
    var blocked = false
    var depth = 0
    var subOf: Int? = nil
}

func dbHashable(_ v: Any?) -> AnyHashable? {
    switch v {
    case let s as String: return s
    case let b as Bool: return b
    case let n as NSNumber: return n.doubleValue
    case let a as [Any]: return a.compactMap { dbHashable($0) } as [AnyHashable]
    default: return nil
    }
}

struct DBQuery {
    var props: [DBProp] = []
    var views: [[String: Any]] = []
    var rows: [DBRow] = []
    /// 2.5: Personen-Verzeichnis der Antwort ({kennung: {name, avatar, me, remote, gone}}) und eigene Kennung (Filter "ich")
    var people: [String: [String: Any]] = [:]
    var me: String? = nil
    /// Zeitzone und "heute" fuer relative Datumsfilter (wie tz/today an /rows); nil = jetzt in tz
    var tz: TimeZone = .current
    var today: String? = nil
    static let statusGroups = ["todo", "doing", "done"]

    init(props: [DBProp] = [], views: [[String: Any]] = [], rows: [DBRow] = [], people: [String: [String: Any]] = [:], me: String? = nil) {
        self.props = props; self.views = views; self.rows = rows; self.people = people; self.me = me
    }

    /// Antwort von /api/notes/{id}/rows bzw. /api/app/rows/{id}
    static func parse(_ d: Data) -> DBQuery? {
        guard let j = try? JSONSerialization.jsonObject(with: d) as? [String: Any] else { return nil }
        let schema = j["schema"] as? [String: Any] ?? [:]
        var q = DBQuery()
        q.props = (schema["props"] as? [[String: Any]] ?? []).map(DBProp.init)
        q.views = schema["views"] as? [[String: Any]] ?? []
        q.rows = (j["rows"] as? [[String: Any]] ?? []).map { r in
            DBRow(id: r["id"] as? Int ?? 0, title: r["title"] as? String ?? "", icon: r["icon"] as? String,
                  cover: ((r["preview"] as? String) ?? (r["cover"] as? String)).flatMap { $0.isEmpty ? nil : $0 },   // Titelbild oder erstes Bild der Seite
                  props: (r["props"] as? [String: Any] ?? [:]).compactMapValues { dbHashable($0) },
                  created: r["created"] as? Double ?? 0, updated: r["updated"] as? Double ?? 0,
                  fxErr: (r["fx_err"] as? [String: Any] ?? [:]).compactMapValues { $0 as? String },
                  blocked: r["blocked"] as? Bool == true, depth: r["depth"] as? Int ?? 0, subOf: r["sub_of"] as? Int)
        }
        q.people = (j["people"] as? [String: Any] ?? [:]).compactMapValues { $0 as? [String: Any] }
        q.me = q.people.first { $0.value["me"] as? Bool == true }?.key
        return q
    }

    func prop(_ id: String?) -> DBProp? { props.first { $0.id == id } }
    func view(_ id: String?) -> [String: Any]? { views.first { ($0["id"] as? String) == id } ?? views.first }

    // Ansicht: Filter + Sortierung genau wie im Web (matchFilter / sortedRows)
    /// "Berechnet" (wie im Web/Server): aus zwei Zahlen-Eigenschaften, nie gespeichert
    static func calc(_ p: DBProp, _ props: [String: Any]) -> Double? {
        func num(_ k: String?) -> Double? { guard let k, let v = props[k] else { return nil }; if let d = v as? Double { return d }; if let n = v as? NSNumber { return n.doubleValue }; return Double("\(v)") }
        var x = num(p.a), y = num(p.b); let op = p.op ?? "div"
        if op == "add" || op == "sub" { if x == nil && y == nil { return nil }; x = x ?? 0; y = y ?? 0 }
        guard let a = x, let b = y else { return nil }
        let v: Double
        switch op {
        case "div", "pct": guard b != 0 else { return nil }; v = a / b * (op == "pct" ? 100 : 1)
        case "mul": v = a * b
        case "add": v = a + b
        default: v = a - b
        }
        return (v * 10000).rounded() / 10000
    }
    func value(_ p: DBProp, _ r: DBRow) -> Any? {
        if p.type == "calc" { return Self.calc(p, r.props.mapValues { $0.base }) }
        if p.type == "created" { return isoDay(r.created) }
        if p.type == "edited" { return isoDay(r.updated) }
        return r.props[p.id]?.base
    }
    private func isoDay(_ ts: Double) -> String { let f = DateFormatter.isoDay(); f.timeZone = tz; return f.string(from: Date(timeIntervalSince1970: ts)) }
    /// Anzeigename einer Personen-Kennung ("" = unbekannt)
    func personName(_ ref: String) -> String { people[ref]?["name"] as? String ?? "" }

    func rows(for view: [String: Any]?) -> [DBRow] {
        let filters = view?["filters"] as? [[String: Any]] ?? []
        var out = rows.filter { r in filters.allSatisfy { match(r, $0) } }
        let keys = (view?["sorts"] as? [[String: Any]] ?? []).compactMap { s -> (DBProp?, Int)? in
            let pid = s["pid"] as? String; let p = pid == "title" ? nil : prop(pid)
            if pid != "title" && p == nil { return nil }
            return (p, s["dir"] as? String == "desc" ? -1 : 1)
        }
        if !keys.isEmpty {
            out.sort { a, b in
                for (p, d) in keys {
                    let x = sortKey(p, a), y = sortKey(p, b)
                    if x == y { continue }; if x == nil { return false }; if y == nil { return true }
                    return (x! < y!) == (d == 1)
                }
                return false
            }
        }
        if let sub = view?["sub"] as? [String: Any], view?["group"] == nil, let pid = sub["parent"] as? String,
           let mode = sub["mode"] as? String, mode == "nested" || mode == "parents_only" {
            return nest(out, parent: pid, mode: mode)
        }
        return out
    }

    /// Unteraufgaben (2.5, view.sub) wie nest() im Server: Kinder direkt hinter ihren Eltern (depth, subOf);
    /// passt ein Kind zum Filter, bleibt die Eltern-Kette sichtbar. parents_only: nur Zeilen ohne Elternteil.
    func nest(_ shown: [DBRow], parent pid: String, mode: String) -> [DBRow] {
        let byID = Dictionary(rows.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
        func parentOf(_ r: DBRow) -> Int? {
            guard let a = r.props[pid] as? [AnyHashable], let f = a.first, let id = Self.relID(f), id != r.id, byID[id] != nil else { return nil }
            return id
        }
        if mode == "parents_only" { return shown.filter { parentOf($0) == nil }.map { var r = $0; r.depth = 0; r.subOf = nil; return r } }
        var keep: [Int: DBRow] = [:], keepOrder: [Int] = []
        func add(_ r: DBRow) { if keep[r.id] == nil { keep[r.id] = r; keepOrder.append(r.id) } }
        shown.forEach(add)
        for r in shown {
            var cur = parentOf(r), n = 0
            while let c = cur, n < 10 { if let pr = byID[c] { add(pr); cur = parentOf(pr) } else { cur = nil }; n += 1 }
        }
        var rank: [Int: Int] = [:]
        for (i, r) in shown.enumerated() { rank[r.id] = i }
        for (k, r) in rows.enumerated() where rank[r.id] == nil { rank[r.id] = shown.count + k }
        var kids: [Int: [Int]] = [:], roots: [Int] = []
        for id in keepOrder {
            let r = keep[id]!, par = parentOf(r)
            var seen: Set<Int> = [id], cur = par
            while let c = cur, !seen.contains(c), seen.count <= 10 { seen.insert(c); cur = keep[c] != nil ? parentOf(keep[c]!) : nil }
            if let par, keep[par] != nil, cur == nil { kids[par, default: []].append(id) } else { roots.append(id) }
        }
        var out: [DBRow] = []
        func walk(_ id: Int, _ depth: Int, _ par: Int?) {
            var r = keep[id]!; r.depth = depth; r.subOf = par; out.append(r)
            for k in (kids[id] ?? []).sorted(by: { rank[$0, default: 0] < rank[$1, default: 0] }) { walk(k, depth + 1, id) }
        }
        for id in roots.sorted(by: { rank[$0, default: 0] < rank[$1, default: 0] }) { walk(id, 0, nil) }
        return out
    }

    /// Bedingte Farbe der Ansicht: erste passende Regel (wie im Web) - Farbname oder nil
    func colorName(_ r: DBRow, view: [String: Any]?) -> String? {
        for c in view?["colors"] as? [[String: Any]] ?? [] where match(r, c) { return c["color"] as? String }
        return nil
    }

    /// Neuer Eintrag passt zu den Filtern der Ansicht ("Status ist Offen" -> Status = Offen), wie Notion
    func defaults(for view: [String: Any]?) -> [String: Any] {
        var d: [String: Any] = [:]
        for f in view?["filters"] as? [[String: Any]] ?? [] where (f["op"] as? String) == "is" || (f["op"] as? String) == "eq" || (f["op"] as? String) == "checked" {
            guard let pid = f["pid"] as? String, pid != "title", let p = prop(pid) else { continue }
            if f["op"] as? String == "checked" { d[pid] = true } else if let v = f["value"] as? String, !v.isEmpty { d[pid] = p.type == "multi_select" ? [v] : v }
        }
        return d
    }

    private func sortKey(_ p: DBProp?, _ r: DBRow) -> String? {
        guard let p else { return r.title.lowercased() }
        let v = value(p, r)
        switch p.type {
        case "select": return String(format: "%04d", (v as? String).flatMap { s in p.options.firstIndex { $0.id == s } } ?? 9999)
        case "person":                                  // 2.5: nach dem Namen der ersten Person, leer zuletzt
            guard let a = v as? [AnyHashable], let f = a.first as? String else { return nil }
            return personName(f).lowercased()
        case "multi_select", "relation", "files": return String(format: "%06d", (v as? [AnyHashable])?.count ?? 0)
        case "checkbox": return (v as? Bool) == true ? "1" : "0"
        default: break
        }
        if p.isFx && p.rtype == "list" { return String(format: "%06d", (v as? [AnyHashable])?.count ?? 0) }
        if p.type == "date", let sp = DBDate.spans(v, tz) { return sp.2 }        // Datum mit Uhrzeit/Zeitraum: Start lokal in tz
        switch v {
        case let s as String: return s.isEmpty ? nil : s.lowercased()
        case let n as Double: return String(format: "%020.4f", n + 1e12)          // auch "Berechnet"/Formel
        case let b as Bool: return b ? "1" : "0"
        default: return nil
        }
    }
    /// Relative Datumsfilter (heute, diese Woche, ..., seit 2.5 relfilter1) in der Zeitzone der Abfrage
    func relDate(_ op: String, _ v: Any?, _ fv: Any? = nil) -> Bool {
        DBDate.rel(op, v, fv, today: DBDate.todayNumber(today, tz: tz), tz: tz)
    }
    /// Frueheres Verhalten (Zeitzone des Geraets, heute)
    static func relDate(_ op: String, _ v: Any?) -> Bool {
        DBQuery().relDate(op, v)
    }
    /// Eintrags-ID einer Relation (Zahl, Zahl als Text) -> Int; sonst nil
    static func relID(_ x: Any) -> Int? {
        if let d = x as? Double { return d.isFinite && d.rounded() == d && abs(d) < 1e15 ? Int(d) : nil }
        if let i = x as? Int { return i }
        if let n = x as? NSNumber { return relID(n.doubleValue) }
        if let s = x as? String { return Double(s.trimmingCharacters(in: .whitespaces)).flatMap { relID($0) } }
        return nil
    }
    private func match(_ r: DBRow, _ f: [String: Any]) -> Bool {
        let pid = f["pid"] as? String, op = f["op"] as? String ?? ""
        let p: DBProp? = pid == "title" ? DBProp(["id": "title", "type": "text"]) : prop(pid)
        guard let p else { return true }
        let v: Any? = pid == "title" ? r.title : value(p, r)
        let empty: Bool = { if v == nil { return true }; if let s = v as? String { return s.isEmpty }; if let b = v as? Bool { return !b }; if let a = v as? [Any] { return a.isEmpty }; return false }()
        switch op {
        case "is_empty": return empty
        case "not_empty": return !empty
        case "checked": return (v as? Bool) ?? false
        case "unchecked": return !((v as? Bool) ?? false)
        case "me": return (v as? [AnyHashable])?.contains { ($0 as? String) == me && me != nil } ?? false   // 2.5: mir zugewiesen
        default: break
        }
        if DBDate.relOps.contains(op) { return !empty && relDate(op, v) }
        guard let fvRaw = f["value"], !(fvRaw is NSNull), !"\(fvRaw)".isEmpty else { return true }
        let fv = "\(fvRaw)"
        if DBDate.relValueOps.contains(op) {                        // unvollstaendiger Wert zaehlt nicht (auch leere Zeilen)
            if DBDate.relIncomplete(op, fvRaw) { return true }
            return !empty && relDate(op, v, fvRaw)
        }
        if op == "status_group" {                                    // 2.5: Gruppe der Option; "open" = nicht erledigt
            guard Self.statusGroups.contains(fv) || fv == "open" else { return true }
            if empty { return false }
            guard let g = p.statusGroup(v) else { return false }
            return fv == "open" ? g != "done" : g == fv
        }
        if p.type == "date", ["eq", "before", "after"].contains(op), let sp = DBDate.spans(v, tz) {   // Datum mit Uhrzeit: Tag in tz
            guard let fs = fvRaw as? String else { return false }
            let a = DBDate.iso(sp.0), b = DBDate.iso(sp.1)
            return op == "eq" ? (a <= fs && fs <= b) : op == "before" ? a < fs : b > fs
        }
        // Relation (wie Web/Server seit fix-relfilter): Eintrags-IDs als Zahlen vergleichen (Werte kommen als Double: "5.0" != "5"),
        // ohne Verknuepfung = leere Liste -> "enthaelt" falsch, "enthaelt nicht" wahr
        if p.type == "relation", op == "contains" || op == "not_contains" {
            let ids = (v as? [AnyHashable])?.compactMap { Self.relID($0) } ?? []
            let hit = Self.relID(fvRaw).map { ids.contains($0) } ?? false
            return op == "contains" ? hit : !hit
        }
        let arr = (v as? [AnyHashable])?.map { "\($0)" }
        let s = (v as? String) ?? (v.map { "\($0)" } ?? "")
        let num = (v as? Double), fnum = Double(fv)
        switch op {
        case "contains": return arr.map { $0.contains(fv) } ?? s.lowercased().contains(fv.lowercased())
        case "not_contains": return arr.map { !$0.contains(fv) } ?? !s.lowercased().contains(fv.lowercased())
        case "is": return s == fv
        case "is_not": return s != fv
        case "eq": return p.type == "number" || p.type == "calc" || (p.isFx && p.rtype == "number") ? (num != nil && fnum != nil && num == fnum) : s == fv
        case "gt": return num != nil && fnum != nil && num! > fnum!
        case "lt": return num != nil && fnum != nil && num! < fnum!
        case "before": return !s.isEmpty && s < fv
        case "after": return !s.isEmpty && s > fv
        default: return true
        }
    }
}
