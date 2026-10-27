import Foundation

// Datenbank-Logik ohne Oberflaeche - gemeinsam fuer App und Widget, damit beide GENAU wie das Web filtern/sortieren.

struct DBOption: Hashable { let id: String; let name: String; let color: String? }
struct DBProp: Identifiable, Hashable {
    let id: String; let name: String; let type: String; let options: [DBOption]; let fmt: String?
    var a: String? = nil, b: String? = nil, op: String? = nil, show: String? = nil      // "Berechnet": A op B
    init(_ d: [String: Any]) {
        id = d["id"] as? String ?? ""; name = d["name"] as? String ?? ""; type = d["type"] as? String ?? "text"; fmt = d["fmt"] as? String
        a = d["a"] as? String; b = d["b"] as? String; op = d["op"] as? String; show = d["show"] as? String
        options = (d["options"] as? [[String: Any]] ?? []).map { DBOption(id: $0["id"] as? String ?? "", name: $0["name"] as? String ?? "", color: $0["color"] as? String) }
    }
    func option(_ id: String?) -> DBOption? { options.first { $0.id == id } }
    var symbol: String {
        switch type {
        case "select": return "chevron.down.circle"; case "multi_select": return "list.bullet"; case "checkbox": return "checkmark.square"
        case "date": return "calendar"; case "number": return "number"; case "url": return "link"; case "email": return "at"
        case "phone": return "phone"; case "relation": return "arrow.up.right"; case "created", "edited": return "clock"
        case "calc": return "function"
        default: return "text.alignleft"
        }
    }
}
struct DBRow: Identifiable, Hashable {
    let id: Int; var title: String; var icon: String?; var cover: String?; var props: [String: AnyHashable]; var created: Double; var updated: Double
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
                  created: r["created"] as? Double ?? 0, updated: r["updated"] as? Double ?? 0)
        }
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
    private func isoDay(_ ts: Double) -> String { let f = DateFormatter.isoDay(); return f.string(from: Date(timeIntervalSince1970: ts)) }

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
        switch v {
        case let s as String where p.type == "select": return p.option(s).map { String(format: "%04d", p.options.firstIndex(of: $0) ?? 0) }
        case let s as String: return s.isEmpty ? nil : s.lowercased()
        case let n as Double: return String(format: "%020.4f", n + 1e12)          // auch "Berechnet"
        case let b as Bool: return b ? "1" : "0"
        default: return nil
        }
    }
    /// Relative Datumsfilter wie in Notion: heute / diese Woche (Mo–So) / diesen Monat
    static func relDate(_ op: String, _ v: Any?) -> Bool {
        var day: Date?
        if let s = v as? String, s.count >= 10 {
            let f = DateFormatter.isoDay(); f.timeZone = .current
            day = f.date(from: String(s.prefix(10)))
        } else if let t = v as? Double { day = Date(timeIntervalSince1970: t) }
        else if let t = v as? Int { day = Date(timeIntervalSince1970: Double(t)) }
        guard let day else { return false }
        var cal = Calendar(identifier: .gregorian); cal.firstWeekday = 2
        let unit: Calendar.Component = op == "this_month" ? .month : op == "this_week" ? .weekOfYear : .day
        return cal.isDate(day, equalTo: Date(), toGranularity: unit)
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
        case "today", "this_week", "this_month": return !empty && Self.relDate(op, v)
        default: break
        }
        guard let fvRaw = f["value"], !(fvRaw is NSNull), !"\(fvRaw)".isEmpty else { return true }
        let fv = "\(fvRaw)"
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
        case "eq": return p.type == "number" || p.type == "calc" ? (num != nil && fnum != nil && num == fnum) : s == fv
        case "gt": return num != nil && fnum != nil && num! > fnum!
        case "lt": return num != nil && fnum != nil && num! < fnum!
        case "before": return !s.isEmpty && s < fv
        case "after": return !s.isEmpty && s > fv
        default: return true
        }
    }
}
