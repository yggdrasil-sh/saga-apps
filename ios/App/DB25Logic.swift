import Foundation

// Saga 2.5 App-Runde Etappe 1: reine Logik ohne SwiftUI (mit swiftc pruefbar, Tests/Logic/DB25Tests.swift).
// Formeln/Rollups, Personen, Dateien, Status, Tabellen-Block, Inline-Formen, Spalten, Diagramme, Widgets Etappe 2,
// Zeitleiste. Grundregel: Unbekanntes bleibt roh erhalten; berechnete Werte werden nie zurueckgeschrieben.

/// Faehigkeiten des Servers (/api/version "features"); aeltere Server: leer -> alles wie bisher
struct ServerFeatures: Equatable {
    var set: Set<String> = []
    init(_ list: [String] = []) { set = Set(list) }
    static func parse(_ j: [String: Any]) -> ServerFeatures { ServerFeatures((j["features"] as? [Any] ?? []).compactMap { $0 as? String }) }
    func has(_ f: String) -> Bool { set.contains(f) }
    var datetime: Bool { has("datetime1") }
    var status: Bool { has("status1") }
    var people: Bool { has("person1") }
    var files: Bool { has("file1") }
    var chart: Bool { has("chart1") }
    var formulas: Bool { has("formula1") || has("rollup1") }
}

// MARK: - Formeln und Rollups

enum FxCell: Equatable {
    case empty
    case number(Double)
    case text(String)
    case check(Bool)
    case date(String)
    case list([String])
    case error(String)
}

enum FxLogic {
    /// Wert einer Formel-/Rollup-Zelle nach rtype; Laufzeitfehler (fx_err) gewinnt
    static func cell(_ p: DBProp, value: Any?, err: String?) -> FxCell {
        if let err { return .error(err) }
        guard let v = value, !(v is NSNull) else { return .empty }
        switch p.rtype ?? "text" {
        case "number":
            if let d = v as? Double { return .number(d) }
            if let n = v as? NSNumber { return .number(n.doubleValue) }
            return Double("\(v)").map { .number($0) } ?? .text("\(v)")
        case "checkbox": return .check((v as? Bool) ?? false)
        case "date": return (v as? String).map { $0.isEmpty ? .empty : .date($0) } ?? .empty
        case "list":
            let a = (v as? [Any] ?? []).map { "\($0)" }
            return a.isEmpty ? .empty : .list(a)
        default:
            let s = (v as? String) ?? "\(v)"
            return s.isEmpty ? .empty : .text(s)
        }
    }
    /// Erklaerung zu einem Fehlercode (Tooltip/Untertitel)
    static func errorHint(_ code: String) -> String {
        switch code {
        case "num": return String(localized: "Division durch 0")
        case "ref": return String(localized: "Verwendete Eigenschaft gelöscht")
        case "time": return String(localized: "Zeitlimit erreicht")
        case "cycle": return String(localized: "Formel bezieht sich auf sich selbst")
        case "syntax": return String(localized: "Formel fehlerhaft")
        default: return String(localized: "Formel kann nicht berechnet werden")
        }
    }
    /// Antwort von PATCH /api/notes/{zeile}: berechnete Werte (calc), Fehler (fx_err), gespeicherte Personen/Dateien
    /// (values) in die Zeile uebernehmen - nie zuruecksenden
    static func apply(patch j: [String: Any], to row: inout DBRow) {
        for (k, v) in j["calc"] as? [String: Any] ?? [:] {
            if v is NSNull { row.props.removeValue(forKey: k) } else if let h = dbHashable(v) { row.props[k] = h }
        }
        if let e = j["fx_err"] as? [String: Any] { row.fxErr = e.compactMapValues { $0 as? String } }
        for (k, v) in j["values"] as? [String: Any] ?? [:] {
            if v is NSNull { row.props.removeValue(forKey: k) } else if let h = dbHashable(v) { row.props[k] = h }
        }
    }
    /// Eigenschaften, die die App nie im PATCH mitschickt (Server verwirft sie ohnehin)
    static func computed(_ props: [DBProp]) -> Set<String> { Set(props.filter { $0.isFx || $0.type == "calc" }.map(\.id)) }
}

// MARK: - Personen

struct DBPerson: Equatable, Identifiable {
    var ref: String
    var name: String
    var avatar: String?
    var me = false
    var remote = false
    var gone = false
    var id: String { ref }
}

enum PeopleLogic {
    /// people-Woerterbuch einer Antwort -> Personen (Name nie aus der Kennung abgeleitet)
    static func directory(_ raw: [String: [String: Any]]) -> [String: DBPerson] {
        var out: [String: DBPerson] = [:]
        for (ref, d) in raw {
            out[ref] = DBPerson(ref: ref, name: (d["name"] as? String) ?? "", avatar: (d["avatar"] as? String).flatMap { $0.isEmpty ? nil : $0 },
                                me: d["me"] as? Bool == true, remote: d["remote"] as? Bool == true, gone: d["gone"] as? Bool == true)
        }
        return out
    }
    /// Kennungen einer Zelle -> Personen (unbekannte Kennung = "Gelöschte Person")
    static func persons(_ v: Any?, _ dir: [String: DBPerson]) -> [DBPerson] {
        ((v as? [Any]) ?? []).compactMap { $0 as? String }.map { r in
            dir[r] ?? DBPerson(ref: r, name: "", gone: true)
        }
    }
    /// Angezeigter Name (nie Anmeldename, nie Kennung)
    static func label(_ p: DBPerson) -> String {
        if p.gone { return String(localized: "Gelöschte Person") }
        return p.name.isEmpty ? String(localized: "Unbekannte Person") : p.name
    }
    /// Auswahlliste GET /api/notes/{db}/people?q=
    static func picker(_ d: Data) -> [DBPerson] {
        guard let j = (try? JSONSerialization.jsonObject(with: d)) as? [String: Any] else { return [] }
        return (j["people"] as? [[String: Any]] ?? []).compactMap { x in
            guard let ref = x["ref"] as? String, !ref.isEmpty else { return nil }
            return DBPerson(ref: ref, name: (x["name"] as? String) ?? "", avatar: (x["avatar"] as? String).flatMap { $0.isEmpty ? nil : $0 },
                            me: x["me"] as? Bool == true, remote: x["remote"] as? Bool == true)
        }
    }
    /// Person an-/abwaehlen; multi:false -> hoechstens eine; hoechstens 20
    static func toggle(_ cur: [String], _ ref: String, multi: Bool) -> [String] {
        if cur.contains(ref) { return cur.filter { $0 != ref } }
        return multi ? Array((cur + [ref]).prefix(20)) : [ref]
    }
    /// Verzeichnisse zusammenfuehren (Antwort eines PATCH ergaenzt die Liste)
    static func merge(_ a: [String: [String: Any]], _ b: [String: Any]?) -> [String: [String: Any]] {
        var out = a
        for (k, v) in b ?? [:] { if let d = v as? [String: Any] { out[k] = d } }
        return out
    }
}

// MARK: - Dateien

struct DBFile: Equatable, Identifiable {
    var url: String
    var name: String
    var size: Int?
    var mime: String?
    var image = false
    var id: String { url }
}

enum FilesLogic {
    static func files(_ v: Any?) -> [DBFile] {
        ((v as? [Any]) ?? []).compactMap { x in
            let d: [String: Any]? = (x as? [String: Any]) ?? (x as? [String: AnyHashable]).map { $0.mapValues { $0.base } }
            guard let d, let u = d["url"] as? String, !u.isEmpty else { return nil }
            return DBFile(url: u, name: (d["name"] as? String) ?? "", size: (d["size"] as? Int) ?? (d["size"] as? Double).map { Int($0) },
                          mime: d["mime"] as? String, image: d["image"] as? Bool == true)
        }
    }
    /// Ganze Liste fuer PATCH (gespeichert werden nur url und name)
    static func stored(_ fs: [DBFile]) -> [[String: Any]] { fs.map { ["url": $0.url, "name": $0.name] } }
    /// Neue Datei anhaengen (doppelte Adresse nicht); nil = Grenze `max` erreicht
    static func adding(_ fs: [DBFile], url: String, name: String, max: Int) -> [DBFile]? {
        if fs.contains(where: { $0.url == url }) { return fs }
        guard fs.count < max else { return nil }
        return fs + [DBFile(url: url, name: name)]
    }
    static func symbol(_ f: DBFile) -> String {
        if f.image { return "photo" }
        let m = f.mime ?? "", n = f.name.lowercased()
        if m.hasPrefix("video/") { return "film" }
        if m.hasPrefix("audio/") { return "waveform" }
        if m == "application/pdf" || n.hasSuffix(".pdf") { return "doc.richtext" }
        return "doc"
    }
}

// MARK: - Status

enum StatusLogic {
    static let symbols = ["todo": "○", "doing": "◐", "done": "●"]
    static func groupName(_ g: String) -> String {
        switch g {
        case "doing": return String(localized: "In Arbeit")
        case "done": return String(localized: "Erledigt")
        default: return String(localized: "Zu erledigen")
        }
    }
    /// Optionen nach Gruppen geordnet (Auswahl beim Bearbeiten)
    static func grouped(_ p: DBProp) -> [(group: String, options: [DBOption])] {
        DBQuery.statusGroups.map { g in (g, p.options.filter { $0.group == g }) }.filter { !$0.options.isEmpty }
    }
    /// Abhaken: erledigt -> erste Option der Gruppe "todo", sonst erste Option von "done"
    static func toggled(_ p: DBProp, current: String?) -> String? {
        let done = p.statusGroup(current) == "done"
        return p.firstOption(group: done ? "todo" : "done")?.id
    }
    /// Karte in eine Gruppen-Spalte gezogen: erste Option der Gruppe; schon in der Gruppe -> nil (nichts aendern)
    static func drop(_ p: DBProp, current: String?, group: String?) -> String?? {
        guard let group else { return .some(nil) }                       // "Ohne Status": leeren
        if p.statusGroup(current) == group { return nil }
        guard let o = p.firstOption(group: group) else { return nil }
        return .some(o.id)
    }
}

// MARK: - Gruppen aus /rows (Board, Gruppierung nach Formel/Person: nur lesend)

struct DBGroup: Equatable {
    var id: String?                 // nil = "Ohne …"
    var label: String
    var color: String?
    var count: Int
    var ids: [Int] = []
}

enum GroupLogic {
    /// Gruppen lokal bilden wie der Server (Auswahl je Option, Status nach Gruppe, Formel/Rollup nach Wert)
    static func groups(_ q: DBQuery, rows: [DBRow], by g: DBProp, byStatus: Bool) -> [DBGroup] {
        if byStatus && g.status {
            var out = [DBGroup(id: nil, label: String(localized: "Ohne \(g.name)"), color: nil, count: 0)]
            for grp in DBQuery.statusGroups {
                out.append(DBGroup(id: grp, label: StatusLogic.groupName(grp), color: grp == "done" ? "green" : grp == "doing" ? "blue" : "gray", count: 0))
            }
            for r in rows {
                let k = g.statusGroup(r.props[g.id]?.base)
                if let i = out.firstIndex(where: { $0.id == k }) { out[i].ids.append(r.id); out[i].count += 1 }
            }
            return out
        }
        if g.type == "select" {
            var out = [DBGroup(id: nil, label: String(localized: "Ohne \(g.name)"), color: nil, count: 0)] + g.options.map { DBGroup(id: $0.id, label: $0.name, color: $0.color, count: 0) }
            for r in rows {
                let v = r.props[g.id]?.base as? String
                let i = out.firstIndex { $0.id != nil && $0.id == v } ?? 0
                out[i].ids.append(r.id); out[i].count += 1
            }
            return out
        }
        // Formel/Rollup/Person: vorkommende Werte, Reihenfolge wie im Server (Zahl aufsteigend, Wochentage, Text)
        var empty = DBGroup(id: nil, label: String(localized: "Ohne \(g.name)"), color: nil, count: 0)
        var buckets: [(key: String, value: Any, ids: [Int])] = []
        for r in rows {
            let v = q.value(g, r)
            let key: String?
            if g.type == "person" { key = (v as? [AnyHashable])?.compactMap { $0 as? String }.joined(separator: ",").nilIfEmpty }
            else if let b = v as? Bool { key = b ? "true" : "false" }
            else if let n = v as? Double { key = DBDate.jsNumStr(n) }
            else if let s = v as? String, !s.isEmpty { key = s }
            else { key = nil }
            guard let key else { empty.ids.append(r.id); empty.count += 1; continue }
            if let i = buckets.firstIndex(where: { $0.key == key }) { buckets[i].ids.append(r.id) } else { buckets.append((key, v as Any, [r.id])) }
        }
        let allWeek = !buckets.isEmpty && buckets.allSatisfy { weekdayIndex[$0.key] != nil }
        buckets.sort { a, b in
            if g.type == "person" {
                let na = a.key.split(separator: ",").map { q.personName(String($0)) }.joined(separator: ", ").lowercased()
                let nb = b.key.split(separator: ",").map { q.personName(String($0)) }.joined(separator: ", ").lowercased()
                return na != nb ? Array(na.utf16).lexicographicallyPrecedes(Array(nb.utf16)) : a.key < b.key
            }
            if let x = a.value as? Double, let y = b.value as? Double { return x < y }
            if let x = a.value as? Bool, let y = b.value as? Bool { return !x && y }
            if allWeek { return weekdayIndex[a.key]! < weekdayIndex[b.key]! }
            return Array(a.key.utf16).lexicographicallyPrecedes(Array(b.key.utf16))
        }
        return [empty] + buckets.map { b in
            let label: String
            if g.type == "person" { label = b.key.split(separator: ",").map { q.personName(String($0)).nilIfEmpty ?? String(localized: "Gelöschte Person") }.joined(separator: ", ") }
            else if b.key == "true" || b.key == "false" { label = b.key == "true" ? String(localized: "Ja") : String(localized: "Nein") }
            else if let n = b.value as? Double { label = NumFormat.text(n, fmt: g.fmt) }
            else { label = b.key }
            return DBGroup(id: b.key, label: label, color: nil, count: b.ids.count, ids: b.ids)
        }
    }
    /// Wochentagsnamen (formatDate dddd/ddd) in den App-Sprachen -> 0 = Montag
    static let weekdayIndex: [String: Int] = {
        let names: [[String]] = [
            ["Montag", "Dienstag", "Mittwoch", "Donnerstag", "Freitag", "Samstag", "Sonntag"], ["Mo", "Di", "Mi", "Do", "Fr", "Sa", "So"],
            ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"], ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"],
            ["lunes", "martes", "miércoles", "jueves", "viernes", "sábado", "domingo"], ["lun", "mar", "mié", "jue", "vie", "sáb", "dom"],
            ["понедельник", "вторник", "среда", "четверг", "пятница", "суббота", "воскресенье"], ["пн", "вт", "ср", "чт", "пт", "сб", "вс"],
            ["วันจันทร์", "วันอังคาร", "วันพุธ", "วันพฤหัสบดี", "วันศุกร์", "วันเสาร์", "วันอาทิตย์"], ["จ.", "อ.", "พ.", "พฤ.", "ศ.", "ส.", "อา."]]
        var d: [String: Int] = [:]
        for l in names { for (i, n) in l.enumerated() where d[n] == nil { d[n] = i } }
        return d
    }()
    /// Darf eine Karte zwischen Spalten gezogen werden? Nur Auswahl (auch Status); Formel/Person nur lesend
    static func draggable(_ g: DBProp) -> Bool { g.type == "select" }
}

extension String { var nilIfEmpty: String? { isEmpty ? nil : self } }

// MARK: - Tabellen-Block (tableopts1)

enum TableOpts {
    /// Breiten in Prozent: genau so viele wie Spalten, sonst gleich breit
    static func widths(_ data: [String: Any], cols: Int) -> [Double] {
        guard cols > 0 else { return [] }
        let w = (data["colWidths"] as? [Any] ?? []).compactMap { ($0 as? Double) ?? ($0 as? Int).map(Double.init) ?? ($0 as? NSNumber)?.doubleValue }
        if w.count == cols, w.allSatisfy({ $0 > 0 }) { let s = w.reduce(0, +); return w.map { $0 / s } }
        return Array(repeating: 1 / Double(cols), count: cols)
    }
    /// Hintergrund einer Zelle: Zelle > Zeile > Spalte (Zeile 0 = Kopfzeile); nur Palette
    static func color(_ data: [String: Any], row: Int, col: Int) -> String? {
        let c = data["colors"] as? [String: Any] ?? [:]
        let pal = Set(["gray", "brown", "orange", "yellow", "green", "blue", "purple", "pink", "red"])
        func pick(_ k: String, _ key: String) -> String? { ((c[k] as? [String: Any])?[key] as? String).flatMap { pal.contains($0) ? $0 : nil } }
        return pick("x", "\(row),\(col)") ?? pick("r", "\(row)") ?? pick("c", "\(col)")
    }
    static func headCol(_ data: [String: Any]) -> Bool { data["headCol"] as? Bool == true }
}

// MARK: - Inline-Formen (P6-2) und Spalten

enum InlineLogic {
    /// Enthaelt das HTML Inline-Formen, die der App-Editor nicht zeichengenau erhalten kann?
    /// (Textfarbe, Hintergrundfarbe mit Klasse, Erwaehnung, Inline-Mathe, unbekannte Tags)
    static func hasProtectedForms(_ html: String) -> Bool {
        guard html.contains("<") else { return false }
        let re = try! NSRegularExpression(pattern: "<([a-zA-Z]+)([^>]*)>")
        let ns = html as NSString
        let known: Set<String> = ["b", "strong", "i", "em", "u", "s", "strike", "del", "code", "a", "br", "time", "mark"]
        for m in re.matches(in: html, range: NSRange(location: 0, length: ns.length)) {
            let tag = ns.substring(with: m.range(at: 1)).lowercased(), attrs = ns.substring(with: m.range(at: 2))
            if !known.contains(tag) { return true }                       // span (Farbe, Erwaehnung) und alles Unbekannte
            if tag == "code" && attrs.contains("hk-math") { return true }
            if tag == "mark" && attrs.contains("hk-bg-") { return true }
        }
        return false
    }
    /// Farbe aus einer Klasse "hk-c-red" / "hk-bg-blue"
    static func colorClass(_ attrs: String, prefix: String) -> String? {
        guard let r = attrs.range(of: prefix + #"([a-z]+)"#, options: .regularExpression) else { return nil }
        let c = String(attrs[r].dropFirst(prefix.count))
        return GestaltenLogic.palette.contains(c) ? c : nil
    }
    /// Kennung einer Erwaehnung (data-u)
    static func mentionRef(_ attrs: String) -> String? {
        guard attrs.contains("hk-mention"), let r = attrs.range(of: #"data-u="([^"]*)""#, options: .regularExpression) else { return nil }
        return String(attrs[r].dropFirst(8).dropLast())
    }
    /// Bearbeitet der App-Editor die Inline-Formen zeichengenau? (Etappe 2, P6-8: dann inline2 in SagaCaps)
    static let editable = true
}

enum ColumnsLogic {
    /// Hoechstzahl Spalten (cols6); aeltere Staende kappten auf 4 und verloren Inhalt
    static let maxColumns = 6
    /// Mindestbreite je Spalte; passen nicht alle nebeneinander, brechen sie in Zeilen um
    static func perRow(count: Int, width: Double, minWidth: Double = 180, spacing: Double = 16) -> Int {
        guard count > 0 else { return 1 }
        var n = count
        while n > 1 && Double(n) * minWidth + Double(n - 1) * spacing > width { n -= 1 }
        return n
    }

    /// Widget-Design-Runde (9.11., Regel 18): Spalten je Reihe nach der Breite des Blocks wie die Container-Regeln im
    /// Web (je Spalte mind. ~200): 4 -> 2x2 (< 880), 3 -> 2 + 1 (< 660), 5-6 -> Dreier- (< 1100, 6: < 1330) bzw.
    /// Zweierreihen (< 660), unter 440 untereinander. slots: Plaetze der Reihe (die dritte von drei Spalten nimmt die
    /// volle Breite, sonst bleibt ein Platz frei wie im Raster).
    struct Row: Equatable { var items: [Int]; var slots: Int }
    static func perRowWeb(count n: Int, width w: Double) -> Int {
        guard n > 1 else { return max(1, n) }
        if w < 440 { return 1 }
        if (n == 3 || n >= 5) && w < 660 { return 2 }
        if n >= 5 && w < 1100 { return 3 }
        if n == 6 && w < 1330 { return 3 }
        if n == 4 && w < 880 { return 2 }
        return n
    }
    static func rows(count n: Int, width w: Double) -> [Row] {
        let per = perRowWeb(count: n, width: w)
        var out: [Row] = [], i = 0
        while i < n {
            let items = Array(i..<min(n, i + per))
            out.append(Row(items: items, slots: n == 3 && per == 2 && items.count == 1 ? 1 : per))
            i += per
        }
        return out
    }
    /// Karten einer Reihe gleich hoch - nur, wenn jede Spalte genau ein Widget enthaelt
    static func equalHeight(_ cols: [NSArray]) -> Bool {
        cols.count > 1 && cols.allSatisfy { c in c.count == 1 && ((c.firstObject as? NSDictionary)?["type"] as? String) == "widget" }
    }
}

// MARK: - Vorschaubild Mathe/Mermaid (render1)

struct RenderPreview: Equatable {
    var png: String
    var w: Double?
    var hgt: Double?
    var stale: Bool
    static func parse(_ data: [String: Any]) -> RenderPreview? {
        guard let r = data["render"] as? [String: Any], let p = (r["png"] as? String) ?? (r["svg"] as? String), p.hasPrefix("/") else { return nil }
        func num(_ k: String) -> Double? { (r[k] as? Double) ?? (r[k] as? Int).map(Double.init) ?? (r[k] as? NSNumber)?.doubleValue }
        return RenderPreview(png: p, w: num("w"), hgt: num("hgt"), stale: r["stale"] as? Bool == true)
    }
    static func isMermaid(_ data: [String: Any]) -> Bool { (data["language"] as? String)?.lowercased() == "mermaid" }
}

// MARK: - Diagramme (chart1)

struct ChartData: Equatable {
    struct Cat: Equatable { var key: String?; var label: String; var color: String?; var start: String? }
    struct Series: Equatable { var key: String; var label: String; var color: String?; var values: [Double?]; var nullKey = false }
    var type: String
    var xKind: String
    var cats: [Cat]
    var series: [Series]
    var total: Double?
    var fmt: String
    var goal: Double?
    var stack: Bool
    var cumulative: Bool
    var truncated: Bool
    var other: Int
    /// Anzahl der Eintraege (Kontextzeile "Summe · 8 Einträge"); aeltere Server ohne Feld -> nil
    var rows: Int? = nil

    static func parse(_ c: [String: Any]) -> ChartData {
        func num(_ v: Any?) -> Double? { v is NSNull ? nil : (v as? Double) ?? (v as? Int).map(Double.init) ?? (v as? NSNumber)?.doubleValue }
        let cats = (c["cats"] as? [[String: Any]] ?? []).map { x -> Cat in
            let k: String? = (x["key"] as? String) ?? (x["key"] as? NSNumber).map { "\($0)" }
            return Cat(key: k, label: (x["label"] as? String) ?? "", color: x["color"] as? String, start: x["start"] as? String)
        }
        let series = (c["series"] as? [[String: Any]] ?? []).map { s in
            Series(key: (s["key"] as? String) ?? "", label: (s["label"] as? String) ?? "", color: s["color"] as? String,
                   values: (s["values"] as? [Any] ?? []).map { num($0) }, nullKey: s["key"] is NSNull)
        }
        return ChartData(type: (c["type"] as? String) ?? "bar", xKind: (c["x_kind"] as? String) ?? "cat", cats: cats, series: series,
                         total: num(c["total"]), fmt: (c["fmt"] as? String) ?? "num", goal: num(c["goal"]), stack: c["stack"] as? Bool == true,
                         cumulative: c["cumulative"] as? Bool == true, truncated: c["truncated"] as? Bool == true, other: (c["other"] as? Int) ?? 0,
                         rows: (c["rows"] as? NSNumber)?.intValue)
    }
    /// Antwort GET /api/notes/{db}/chart -> Ergebnis oder Fehlercode
    static func response(_ d: Data) -> (ChartData?, String?) {
        guard let j = (try? JSONSerialization.jsonObject(with: d)) as? [String: Any] else { return (nil, "bad") }
        if let e = j["error"] as? String { return (nil, e) }
        guard let c = j["chart"] as? [String: Any] else { return (nil, "bad_cfg") }
        return (parse(c), nil)
    }
    /// Palette ohne Optionsfarben in dieser Reihenfolge (Widget-Design-Runde 9.11., Regel 14; Web .ch-s0..7)
    static let fallback = ["blue", "orange", "green", "purple", "pink", "yellow", "red", "brown"]
    /// Farbe eines Diagramm-Elements: Palettenfarbe, "eine Farbe" (eine Reihe: blau bzw. gewaehlte Widget-Farbe) oder
    /// neutral ("Andere"/"Leer")
    enum Paint: Equatable { case named(String), one, other }
    /// Wie chartColors im Web: "gray"/leer zaehlt als keine Farbe. Optionsfarben nur, wenn >= 2 verschiedene echte
    /// vorkommen, sonst Palette der Reihe nach (bzw. `one`: alles einfarbig). Neutrale Eintraege zaehlen nicht mit.
    static func colors(_ list: [(neutral: Bool, color: String?)], one: Bool) -> [Paint] {
        let real = Set(list.filter { !$0.neutral }.compactMap { $0.color }.filter { $0 != "gray" && !$0.isEmpty })
        let useOpt = real.count >= 2
        var k = 0
        return list.map { x in
            if x.neutral { return .other }
            if useOpt { return .named(x.color.map { $0.isEmpty ? "gray" : $0 } ?? "gray") }
            if one { return .one }
            defer { k += 1 }
            return .named(fallback[k % fallback.count])
        }
    }
    var round: Bool { type == "pie" || type == "donut" }
    /// mehrere Reihen (nicht bei Kreis/Ring: die zeigen nur die erste)
    var multi: Bool { series.count > 1 && !round }
    /// Farben je Kategorie (Kreis/Ring, Balken mit einer Reihe) bzw. je Reihe
    var catColors: [Paint] { Self.colors(cats.map { ($0.key == nil || $0.key == "__other__", $0.color) }, one: !round && !multi) }
    var seriesColors: [Paint] { Self.colors(series.map { ($0.nullKey || $0.key == "__other__", $0.color) }, one: false) }
    func catColor(_ i: Int) -> Paint { let c = catColors; return c.indices.contains(i) ? c[i] : .one }
    func seriesColor(_ j: Int) -> Paint { let c = seriesColors; return c.indices.contains(j) ? c[j] : .one }

    /// Rechenart als Text (Kontextzeile, Ring-Mitte): "Anzahl", "Summe" …
    static func aggLabel(_ agg: String?) -> String {
        switch agg ?? "count" {
        case "sum": return String(localized: "Summe")
        case "avg": return String(localized: "Durchschnitt")
        case "min": return String(localized: "Min")
        case "max": return String(localized: "Max")
        case "pct_checked": return String(localized: "\("%") angehakt")     // "%" als Argument: ein Prozentzeichen im Schluessel waere ein Formatzeichen
        default: return String(localized: "Anzahl")
        }
    }
    /// Kontextzeile ueber dem Diagramm (wie chartCtx im Web): "Summe · 8 Einträge", mit Namen der Eigenschaften
    /// "Summe Betrag nach Kategorie · 8 Einträge" (Namen nur, wenn das Schema bekannt ist)
    func context(agg: String?, yName: String? = nil, xName: String? = nil) -> String {
        var head = Self.aggLabel(agg)
        if (agg ?? "count") != "count", let y = yName, !y.isEmpty { head += " " + y }
        if let x = xName, !x.isEmpty, type != "number" { head = String(localized: "\(head) nach \(x)") }
        guard let n = rows else { return head }
        return head + " · " + String(localized: "\(n) Einträge")
    }

    /// Masse eines Diagramms je Breite (wie paint im Web): Hoehe (Widget: S/M/L 140/220/300, nie hoeher als 0,8 x Breite),
    /// Ring/Kreis neben der Legende ab 300 Breite, Durchmesser, Zahl der Legenden-Eintraege, y-Ticks (< 260: 3)
    struct Layout: Equatable { var height: Double; var beside: Bool; var diameter: Double; var legendMax: Int; var ticks: Int; var tiny: Bool; var narrow: Bool }
    static func layout(width w: Double, height h0: Double?) -> Layout {
        let h = h0.map { min($0, max(120, w * 0.8)) } ?? max(160, min(w * 0.55, 300))
        let beside = w >= 300
        let d = beside ? min(h, w * 0.45) : min(h, w, 220)
        let tiny = w < 180
        return Layout(height: h.rounded(), beside: beside, diameter: d.rounded(), legendMax: beside ? max(3, Int(d / 24)) : (tiny ? 4 : 6),
                      ticks: w < 260 ? 3 : (h < 180 ? 4 : 5), tiny: tiny, narrow: w < 360)
    }
    /// Legende kuerzen: hoechstens `max` Zeilen, sonst max-1 Eintraege + "+ n weitere"
    static func legend<T>(_ items: [T], max m: Int) -> (shown: [T], more: Int) {
        guard items.count > m, m > 1 else { return (items, 0) }
        return (Array(items.prefix(m - 1)), items.count - m + 1)
    }
    /// Achsenbeschriftung kompakt ("1,5 Tsd.", "12 Mio. €"); Prozent wie ueberall
    static func axisText(_ v: Double, fmt: String, locale: Locale = SagaDate.locale()) -> String {
        if fmt == "pct" { return NumFormat.text(v, fmt: "pct", locale: locale) }
        if fmt == "eur" { return v.formatted(.currency(code: "EUR").notation(.compactName).precision(.fractionLength(0...1)).locale(locale)) }
        return v.formatted(.number.notation(.compactName).precision(.fractionLength(0...1)).locale(locale))
    }
    /// Beschriftung einer Kategorie: leer -> "Leer", __other__ -> "Andere" (der Server liefert schon Texte)
    func label(_ i: Int) -> String {
        guard cats.indices.contains(i) else { return "" }
        let c = cats[i]
        if !c.label.isEmpty { return c.label }
        return c.key == nil ? String(localized: "Leer") : c.key == "__other__" ? String(localized: "Andere") : (c.key ?? "")
    }
    var single: Bool { series.count <= 1 }
    /// Groesster/kleinster Wert (gestapelt: Summe je Kategorie) fuer die Achse
    var range: (Double, Double) {
        var lo = 0.0, hi = 0.0
        for i in cats.indices {
            if stack && !single {
                let s = series.compactMap { $0.values.indices.contains(i) ? $0.values[i] : nil }
                hi = max(hi, s.filter { $0 > 0 }.reduce(0, +)); lo = min(lo, s.filter { $0 < 0 }.reduce(0, +))
            } else {
                for s in series { if s.values.indices.contains(i), let v = s.values[i] { hi = max(hi, v); lo = min(lo, v) } }
            }
        }
        if let g = goal { hi = max(hi, g); lo = min(lo, g) }
        return (lo, hi)
    }

    /// Alle Werte (und Ziel) ganzzahlig, kein Prozent -> Achse nur mit ganzen Werten (Anzahl: kein "0,2 / 0,4")
    var integerValues: Bool {
        fmt != "pct" && (series.flatMap { $0.values.compactMap { $0 } } + (goal.map { [$0] } ?? [])).allSatisfy { $0.isFinite && $0 == $0.rounded(.down) }
    }

    /// "Schoene" Achsenwerte (1/2/2,5/5 x 10^k, ca. 5 Schritte) wie saga/chartsvg.py nice_ticks (tests/chart_ticks.json);
    /// der Aufrufer nimmt 0 in den Bereich auf (range). integer: nur ganze Werte (Schritte 1/2/5 x 10^k, mindestens 1)
    static func ticks(_ lo0: Double, _ hi0: Double, n: Int = 5, integer: Bool = false) -> [Double] {
        var lo = lo0, hi = hi0
        if lo == hi {
            hi = lo >= 0 ? lo + 1 : lo
            lo = min(0, lo)
            if lo == hi { hi = lo + 1 }
        }
        let raw = (hi - lo) / Double(max(1, n))
        let mag = pow(10, log10(raw).rounded(.down))
        var step = [1.0, 2, 2.5, 5, 10].map { $0 * mag }.first { $0 >= raw - 1e-12 } ?? 10 * mag
        if integer { step = max(1, [1.0, 2, 5, 10].map { $0 * mag }.first { $0 >= raw - 1e-12 } ?? 10 * mag) }
        let a = (lo / step + 1e-9).rounded(.down) * step, b = (hi / step - 1e-9).rounded(.up) * step
        var out: [Double] = [], x = a
        while x <= b + step / 2 && out.count < 50 { out.append((x * 1e10).rounded() / 1e10); x += step }
        return out
    }
}

// MARK: - Widgets Etappe 2 (weather1, habit1, upcoming1, chart1)

enum Widget2Logic {
    static let kinds: Set<String> = ["weather", "habit", "upcoming", "chart", "reading"]
    /// Faehigkeit je Art: weather1 … chart1; "reading" (Lesen, 2.5) gehoert zu bookorbit1
    static func featureFor(_ kind: String) -> String? { kind == "reading" ? "bookorbit1" : kinds.contains(kind) ? kind + "1" : nil }

    /// WMO-Code -> SF Symbol (groesster Eintrag <= code)
    static func weatherSymbol(_ code: Int) -> String {
        let t: [(Int, String)] = [(0, "sun.max"), (1, "sun.min"), (2, "cloud.sun"), (3, "cloud"), (45, "cloud.fog"), (51, "cloud.drizzle"),
                                  (61, "cloud.rain"), (66, "cloud.sleet"), (71, "cloud.snow"), (77, "cloud.snow"), (80, "cloud.heavyrain"),
                                  (85, "cloud.snow"), (95, "cloud.bolt.rain")]
        return t.last { $0.0 <= code }?.1 ?? "sun.max"
    }
    struct Weather: Equatable {
        struct Day: Equatable { var d: String; var min: Double?; var max: Double?; var code: Int; var rain: Int? }
        var now: Double?; var code: Int; var wind: Double?; var days: [Day]; var units: String; var stale: Bool; var place: String
    }
    static func weather(_ d: [String: Any]?) -> Weather? {
        guard let d else { return nil }
        func num(_ v: Any?) -> Double? { (v as? Double) ?? (v as? Int).map(Double.init) ?? (v as? NSNumber)?.doubleValue }
        let n = d["now"] as? [String: Any] ?? [:]
        let days = (d["days"] as? [[String: Any]] ?? []).map { x in
            Weather.Day(d: (x["d"] as? String) ?? "", min: num(x["min"]), max: num(x["max"]), code: (x["code"] as? Int) ?? 0, rain: x["rain"] as? Int)
        }
        return Weather(now: num(n["t"]), code: (n["code"] as? Int) ?? 0, wind: num(n["wind"]), days: days,
                       units: (d["units"] as? String) ?? "metric", stale: d["stale"] as? Bool == true, place: (d["place"] as? String) ?? "")
    }
    /// Wetterlage als Text (WMO-Code, groesster Eintrag <= code) wie im Web
    static func weatherText(_ code: Int) -> String {
        switch code {
        case 95...: return String(localized: "Gewitter")
        case 85...: return String(localized: "Schneeschauer")
        case 80...: return String(localized: "Regenschauer")
        case 77...: return String(localized: "Schneegriesel")
        case 71...: return String(localized: "Schnee")
        case 66...: return String(localized: "Gefrierender Regen")
        case 61...: return String(localized: "Regen")
        case 51...: return String(localized: "Nieselregen")
        case 45...: return String(localized: "Nebel")
        case 3...: return String(localized: "Bedeckt")
        case 2: return String(localized: "Teils bewölkt")
        case 1: return String(localized: "Überwiegend klar")
        default: return String(localized: "Klar")
        }
    }
    static func weatherNotice(_ error: String?) -> String? {
        switch error {
        case "disabled": return String(localized: "Wetter ist auf diesem Server ausgeschaltet")
        case "unavailable": return String(localized: "Wetterdaten gerade nicht erreichbar")
        default: return nil
        }
    }

    struct Habit: Equatable {
        struct Item: Equatable { var pid: String; var name: String; var done: [Bool]; var streak: Int; var pct: Int; var week: Int }
        var days: [String]; var today: String; var canCheck: Bool; var goal: Int?; var habits: [Item]
    }
    static func habit(_ d: [String: Any]?) -> Habit? {
        guard let d else { return nil }
        let hs = (d["habits"] as? [[String: Any]] ?? []).map { h in
            Habit.Item(pid: (h["pid"] as? String) ?? "", name: (h["name"] as? String) ?? "", done: (h["done"] as? [Any] ?? []).map { $0 as? Bool == true },
                       streak: (h["streak"] as? Int) ?? 0, pct: (h["pct"] as? Int) ?? 0, week: (h["week"] as? Int) ?? 0)
        }
        return Habit(days: (d["days"] as? [String]) ?? [], today: (d["today"] as? String) ?? "", canCheck: d["can_check"] as? Bool == true,
                     goal: d["goal"] as? Int, habits: hs)
    }
    /// Body fuer POST …/widgets/{bid}/habit
    static func habitBody(pid: String, day: String, on: Bool) -> [String: Any] { ["pid": pid, "day": day, "on": on] }

    struct Upcoming: Equatable {
        struct Item: Equatable { var k: String; var title: String; var start: String; var end: String?; var allday: Bool; var color: String?; var cal: String?; var note: Int?; var task: Int?; var overdue: Bool }
        var from: String; var days: Int; var more: Int; var errors: Int; var items: [Item]
    }
    static func upcoming(_ d: [String: Any]?) -> Upcoming? {
        guard let d else { return nil }
        let items = (d["items"] as? [[String: Any]] ?? []).map { x in
            Upcoming.Item(k: (x["k"] as? String) ?? "", title: (x["title"] as? String) ?? "", start: (x["start"] as? String) ?? "", end: x["end"] as? String,
                          allday: x["allday"] as? Bool != false, color: x["color"] as? String, cal: x["cal"] as? String,
                          note: x["note"] as? Int, task: x["task"] as? Int, overdue: x["overdue"] as? Bool == true)
        }
        return Upcoming(from: (d["from"] as? String) ?? "", days: (d["days"] as? Int) ?? 7, more: (d["more"] as? Int) ?? 0, errors: (d["errors"] as? Int) ?? 0, items: items)
    }
    /// Groesse S: hoechstens 4 Eintraege (ohne Tagekoepfe), der Rest zaehlt zu "und n weitere"
    static func upcomingShown(_ u: Upcoming, small: Bool) -> (Upcoming, more: Int) {
        guard small, u.items.count > 4 else { return (u, u.more) }
        var v = u; v.items = Array(u.items.prefix(4))
        return (v, u.more + u.items.count - 4)
    }
    /// Abschnitte: "Überfällig", "Heute", "Morgen", Datum (Reihenfolge wie geliefert)
    enum Section: Equatable, Hashable { case overdue, today, tomorrow, day(String) }
    static func sections(_ u: Upcoming, today: String, tz: TimeZone = .current) -> [(Section, [Upcoming.Item])] {
        var out: [(Section, [Upcoming.Item])] = []
        let t = DBDate.part(today).map { DBDate.days($0.y, $0.m, $0.d) }
        for it in u.items {
            let sec: Section
            if it.overdue { sec = .overdue } else {
                let day = DBDate.parse(it.start).map { $0.start.local(tz).0 }
                if let day, let t, day == t { sec = .today } else if let day, let t, day == t + 1 { sec = .tomorrow }
                else { sec = .day(day.map(DBDate.iso) ?? String(it.start.prefix(10))) }
            }
            if let i = out.firstIndex(where: { $0.0 == sec }) { out[i].1.append(it) } else { out.append((sec, [it])) }
        }
        return out
    }
}

// MARK: - Zeitleiste (timeline1, nur lesen) und Kalender-Woche

enum TimelineLogic {
    struct Bar: Equatable { var id: Int; var title: String; var start: Int; var end: Int }
    /// Balken je Zeile: Start aus date_by (Zeitraum: Start/Ende), Ende aus end_by (oder gleich Start); ohne Datum -> nicht
    static func bars(_ q: DBQuery, rows: [DBRow], view: [String: Any]?, tz: TimeZone = .current) -> (bars: [Bar], undated: [Int]) {
        let sp = q.prop(view?["date_by"] as? String) ?? q.props.first { $0.type == "date" }
        let ep = q.prop(view?["end_by"] as? String)
        var bars: [Bar] = [], none: [Int] = []
        for r in rows {
            guard let sp, let s = shownSpan(q.value(sp, r), tz) else { none.append(r.id); continue }
            var e = s.1
            if let ep, let x = shownSpan(q.value(ep, r), tz) { e = max(s.0, x.1) }
            bars.append(Bar(id: r.id, title: r.title, start: s.0, end: e))
        }
        return (bars.sorted { $0.start != $1.start ? $0.start < $1.start : $0.id < $1.id }, none)
    }
    /// Telefon: Liste nach Starttag gruppiert
    static func byStart(_ bars: [Bar]) -> [(day: Int, bars: [Bar])] {
        var out: [(Int, [Bar])] = []
        for b in bars { if let i = out.firstIndex(where: { $0.0 == b.start }) { out[i].1.append(b) } else { out.append((b.start, [b])) } }
        return out.map { (day: $0.0, bars: $0.1) }
    }
    /// Sichtbarer Bereich (Tage) mit etwas Rand
    static func span(_ bars: [Bar], today: Int) -> (Int, Int) {
        let lo = min(bars.map(\.start).min() ?? today, today) - 2, hi = max(bars.map(\.end).max() ?? today, today) + 3
        return (lo, max(hi, lo + 14))
    }
    /// Tage einer Woche (Mo–So) um einen Tag
    static func week(of day: Int) -> [Int] { let a = day - DBDate.weekday(day); return (0..<7).map { a + $0 } }
    /// Zeilen an einem Tag (Zeitraum: auf jedem Tag, hoechstens 62)
    static func rows(on day: Int, _ q: DBQuery, rows: [DBRow], prop: DBProp, tz: TimeZone = .current) -> [DBRow] {
        rows.filter { r in
            guard let s = shownSpan(q.value(prop, r), tz) else { return false }
            return s.0 <= day && day <= min(s.1, s.0 + 61)
        }
    }
    /// Tage eines Datumswerts zum Anzeigen (leer/kein Datum -> nil; Erstellt/Bearbeitet als Zeitstempel)
    static func shownSpan(_ v: Any?, _ tz: TimeZone) -> (Int, Int)? {
        if let t = v as? Double {
            let d = DBDate.dayString(Date(timeIntervalSince1970: t), tz: tz)
            return DBDate.part(d).map { let n = DBDate.days($0.y, $0.m, $0.d); return (n, n) }
        }
        guard let s = v as? String, !s.isEmpty, let x = DBDate.parse(s) else { return nil }
        if let sp = DBDate.spans(s, tz) { return (sp.0, max(sp.0, sp.1)) }
        let a = DBDate.days(x.start.y, x.start.m, x.start.d)
        return (a, a)
    }
}

// MARK: - 2.5 (timeline1): Zeitleiste ziehen (Tablet/Mac) - neue Datumswerte fuer den Props-PATCH

enum TimelineDrag {
    /// Ein Datumswert um n Tage verschieben; Uhrzeit und Zeitzonen-Angabe bleiben ("2026-10-28T09:30+01:00")
    static func shift(_ v: String, by n: Int) -> String? {
        guard v.count >= 10, let p = DBDate.part(String(v.prefix(10))) else { return nil }
        return DBDate.iso(DBDate.days(p.y, p.m, p.d) + n) + v.dropFirst(10)
    }
    /// Neue Werte (pid -> Wert) fuer Verschieben (`move`, beide Enden) bzw. Ende ziehen (nur Ende), beide in EINEM Aufruf.
    /// Zeitraum im Beginn: "start/ende" gemeinsam. Ende vor Beginn wird nie geschrieben.
    static func props(start sp: String, startValue: String, end ep: String?, endValue: String?, days n: Int, moveStart: Bool) -> [String: Any]? {
        guard n != 0 else { return nil }
        let parts = startValue.split(separator: "/", maxSplits: 1).map(String.init)
        var out: [String: Any] = [:]
        if let ep {                                                   // eigenes Ende (end_by)
            let base = endValue.flatMap { $0.isEmpty ? nil : $0 } ?? parts.last ?? startValue
            guard let ne = shift(base.split(separator: "/").last.map(String.init) ?? base, by: n) else { return nil }
            if moveStart {
                guard let ns = parts.count == 2 ? zip2(parts, n) : shift(startValue, by: n) else { return nil }
                out[sp] = ns
            } else if String(ne.prefix(10)) < String(parts[0].prefix(10)) { return nil }
            out[ep] = ne
            return out
        }
        if moveStart {
            guard let ns = parts.count == 2 ? zip2(parts, n) : shift(startValue, by: n) else { return nil }
            out[sp] = ns
        } else {                                                      // Ende im Zeitraum des Beginns
            let endBase = parts.count == 2 ? parts[1] : parts[0]
            guard let ne = shift(endBase, by: n), String(ne.prefix(10)) >= String(parts[0].prefix(10)) else { return nil }
            out[sp] = String(ne.prefix(10)) == String(parts[0].prefix(10)) && !ne.contains("T") ? parts[0] : parts[0] + "/" + ne
        }
        return out
    }
    private static func zip2(_ p: [String], _ n: Int) -> String? {
        guard let a = shift(p[0], by: n), let b = shift(p[1], by: n) else { return nil }
        return a + "/" + b
    }
    /// Gezogene Strecke -> ganze Tage
    static func days(dx: Double, dayWidth: Double) -> Int { dayWidth > 0 ? Int((dx / dayWidth).rounded()) : 0 }
}
