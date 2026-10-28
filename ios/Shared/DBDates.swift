import Foundation

// Saga 2.5 (datetime1, relfilter1): Datumswerte mit Uhrzeit/Zeitraum lesen, anzeigen, schreiben und relative Filter -
// reine Logik (App + Widget), mit swiftc pruefbar (Tests/Logic/DB25Tests.swift). Bildet saga/dbquery.py (_dt_part,
// date_parts, rel_date) bzw. static/pages.js nach: Tagesnummern seit 1970-01-01, "Tag" immer in der Zeitzone tz.

enum DBDate {
    /// Tage seit 1970-01-01 (proleptisch gregorianisch)
    static func days(_ y: Int, _ m: Int, _ d: Int) -> Int {
        let y2 = m <= 2 ? y - 1 : y
        let era = (y2 >= 0 ? y2 : y2 - 399) / 400
        let yoe = y2 - era * 400
        let doy = (153 * (m + (m > 2 ? -3 : 9)) + 2) / 5 + d - 1
        let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097 + doe - 719468
    }
    /// Tagesnummer -> (Jahr, Monat, Tag)
    static func civil(_ n: Int) -> (Int, Int, Int) {
        let z = n + 719468
        let era = (z >= 0 ? z : z - 146096) / 146097
        let doe = z - era * 146097
        let yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        let doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        let mp = (5 * doy + 2) / 153
        let d = doy - (153 * mp + 2) / 5 + 1
        let m = mp < 10 ? mp + 3 : mp - 9
        return (yoe + era * 400 + (m <= 2 ? 1 : 0), m, d)
    }
    static func iso(_ n: Int) -> String { let (y, m, d) = civil(n); return String(format: "%04d-%02d-%02d", y, m, d) }
    /// Wochentag 0 = Montag
    static func weekday(_ n: Int) -> Int { ((n % 7) + 7 + 3) % 7 }

    /// Ein Teil eines Werts: Tag oder Zeitpunkt (Wandzeit mit Offset in Minuten)
    struct Part: Equatable {
        var y: Int, m: Int, d: Int
        var hour: Int? = nil, minute: Int? = nil
        var offset: Int? = nil                 // Minuten oestlich von UTC (nur mit Uhrzeit)
        var hasTime: Bool { hour != nil }
        var day: String { String(format: "%04d-%02d-%02d", y, m, d) }
        /// Zeitpunkt (nur mit Uhrzeit)
        var instant: Date? {
            guard let hour, let minute, let offset else { return nil }
            let mins = DBDate.days(y, m, d) * 1440 + hour * 60 + minute - offset
            return Date(timeIntervalSince1970: Double(mins) * 60)
        }
        /// (Tagesnummer, "YYYY-MM-DD[THH:MM]") lokal in tz
        func local(_ tz: TimeZone) -> (Int, String) {
            guard let t = instant else { return (DBDate.days(y, m, d), day) }
            var cal = Calendar(identifier: .gregorian); cal.timeZone = tz
            let c = cal.dateComponents([.year, .month, .day, .hour, .minute], from: t)
            let n = DBDate.days(c.year!, c.month!, c.day!)
            return (n, String(format: "%04d-%02d-%02dT%02d:%02d", c.year!, c.month!, c.day!, c.hour!, c.minute!))
        }
    }

    private static let partRe = try! NSRegularExpression(pattern: #"^([0-9]{4})-([0-9]{2})-([0-9]{2})(?:T([0-9]{2}):([0-9]{2})(Z|[+-][0-9]{2}:[0-9]{2}))?$"#)

    /// "JJJJ-MM-TT" oder "JJJJ-MM-TTTHH:MM+hh:mm" (streng wie der Server); sonst nil
    static func part(_ s: String) -> Part? {
        let ns = s as NSString
        guard let m = partRe.firstMatch(in: s, range: NSRange(location: 0, length: ns.length)) else { return nil }
        func g(_ i: Int) -> String? { m.range(at: i).location == NSNotFound ? nil : ns.substring(with: m.range(at: i)) }
        guard let y = Int(g(1)!), let mo = Int(g(2)!), let d = Int(g(3)!), (1...9999).contains(y), (1...12).contains(mo), d >= 1,
              d <= daysInMonth(y, mo) else { return nil }
        guard let hh = g(4).flatMap(Int.init), let mi = g(5).flatMap(Int.init), let off = g(6) else { return Part(y: y, m: mo, d: d) }
        guard (1900...9998).contains(y), hh <= 23, mi <= 59 else { return nil }
        var o = 0
        if off != "Z" {
            let oh = Int(off.dropFirst().prefix(2)) ?? 99, om = Int(off.suffix(2)) ?? 99
            guard oh <= 18, om <= 59 else { return nil }
            o = (off.hasPrefix("-") ? -1 : 1) * (oh * 60 + om)
        }
        return Part(y: y, m: mo, d: d, hour: hh, minute: mi, offset: o)
    }
    static func daysInMonth(_ y: Int, _ m: Int) -> Int {
        days(m == 12 ? y + 1 : y, m == 12 ? 1 : m + 1, 1) - days(y, m, 1)
    }

    /// Wert einer Datums-Eigenschaft: Start und (bei Zeitraum) Ende
    struct Value: Equatable {
        var start: Part
        var end: Part?
        var isNew: Bool { start.hasTime || end != nil }
    }
    /// Beliebiger gespeicherter Wert -> Value (alte Werte: erste 10 Zeichen als Tag); nil = kein Datum
    static func parse(_ v: Any?) -> Value? {
        guard let s = v as? String, !s.isEmpty else { return nil }
        let ps = s.split(separator: "/", omittingEmptySubsequences: false).map(String.init)
        if ps.count == 2, let a = part(ps[0]), let b = part(ps[1]) { return Value(start: a, end: b) }
        if ps.count == 1, let a = part(s) { return Value(start: a) }
        if s.count >= 10, let a = part(String(s.prefix(10))) { return Value(start: a) }
        return nil
    }

    /// Neuer Datumswert (mit Uhrzeit oder Zeitraum) -> (starttag, endtag, startschluessel, endschluessel) in tz
    /// wie date_parts im Server; alte Werte (reiner Tag) -> nil
    static func spans(_ v: Any?, _ tz: TimeZone) -> (Int, Int, String, String)? {
        guard let s = v as? String, s.count > 10 else { return nil }
        let ps = s.split(separator: "/", omittingEmptySubsequences: false).map(String.init)
        guard ps.count <= 2, ps.count == 2 || s.contains("T"), let a = part(ps[0]), let b = ps.count == 2 ? part(ps[1]) : a else { return nil }
        let la = a.local(tz), lb = b.local(tz)
        return (la.0, lb.0, la.1, lb.1)
    }

    // MARK: Anzeige

    /// Text eines Datumswerts in der Zeitzone des Betrachters: "27.10.2026, 14:30 – 16:00" (gleicher Tag),
    /// "27.10.2026 – 29.10.2026". Alte Werte: nur der Tag (wie bisher).
    static func display(_ raw: Any?, tz: TimeZone = .current, locale: Locale = .current, now: Date = Date()) -> String? {
        guard let v = parse(raw) else { return (raw as? String).flatMap { $0.isEmpty ? nil : $0 } }
        var cal = Calendar(identifier: .gregorian); cal.timeZone = tz; cal.locale = locale
        func dayDate(_ p: Part) -> Date { cal.date(from: DateComponents(year: p.y, month: p.m, day: p.d, hour: 12))! }
        let thisYear = cal.component(.year, from: now)
        func dayStr(_ d: Date) -> String {
            var st = Date.FormatStyle(locale: locale, calendar: cal, timeZone: tz).day().month(.abbreviated)
            if cal.component(.year, from: d) != thisYear { st = st.year() }
            return d.formatted(st)
        }
        func timeStr(_ d: Date) -> String { d.formatted(Date.FormatStyle(locale: locale, calendar: cal, timeZone: tz).hour().minute()) }
        func one(_ p: Part) -> (date: Date, text: String) {
            if let t = p.instant { return (t, dayStr(t) + ", " + timeStr(t)) }
            let d = dayDate(p); return (d, dayStr(d))
        }
        let a = one(v.start)
        guard let e = v.end, e != v.start else { return a.text }
        if let ta = v.start.instant, let tb = e.instant, cal.isDate(ta, inSameDayAs: tb) { return a.text + " – " + timeStr(tb) }
        return a.text + " – " + one(e).text
    }
    /// Originalzeit, wenn die Zeitzone des Eintrags anders war (Tooltip); nil = gleiche Zone oder kein Zeitpunkt
    static func originalHint(_ raw: Any?, tz: TimeZone = .current) -> String? {
        guard let v = parse(raw), v.start.hasTime, let t = v.start.instant, let off = v.start.offset,
              tz.secondsFromGMT(for: t) != off * 60, let s = raw as? String else { return nil }
        return s.replacingOccurrences(of: "T", with: " ")
    }

    // MARK: Schreiben

    /// Offset "+hh:mm" der Zeitzone zu einem Zeitpunkt
    static func offsetText(_ seconds: Int) -> String {
        let m = abs(seconds) / 60
        return (seconds < 0 ? "-" : "+") + String(format: "%02d:%02d", m / 60, m % 60)
    }
    /// Zeitpunkt -> "JJJJ-MM-TTTHH:MM+hh:mm" (Ortszeit des Geraets mit ihrem Offset)
    static func stamp(_ d: Date, tz: TimeZone = .current) -> String {
        var cal = Calendar(identifier: .gregorian); cal.timeZone = tz
        let c = cal.dateComponents([.year, .month, .day, .hour, .minute], from: d)
        return String(format: "%04d-%02d-%02dT%02d:%02d", c.year!, c.month!, c.day!, c.hour!, c.minute!) + offsetText(tz.secondsFromGMT(for: d))
    }
    /// Tag eines Zeitpunkts in tz
    static func dayString(_ d: Date, tz: TimeZone = .current) -> String {
        var cal = Calendar(identifier: .gregorian); cal.timeZone = tz
        let c = cal.dateComponents([.year, .month, .day], from: d)
        return String(format: "%04d-%02d-%02d", c.year!, c.month!, c.day!)
    }
    /// Neuen Wert bilden. `old` = gespeicherter Wert: hatte er Uhrzeit/Ende und ist der neue ein reiner Tag mit
    /// demselben Starttag, schickt die App "Tag/Tag" - sonst behielte der Server die alte Uhrzeit (APP-API "Datum mit Uhrzeit").
    static func compose(start: Date, startTime: Bool, end: Date?, endTime: Bool, old: Any?, tz: TimeZone = .current) -> String {
        let a = startTime ? stamp(start, tz: tz) : dayString(start, tz: tz)
        guard let end else {
            if !startTime, let o = parse(old), o.isNew, o.start.day == a { return a + "/" + a }
            return a
        }
        let b = endTime ? stamp(end, tz: tz) : dayString(end, tz: tz)
        return a == b ? (startTime ? a : a + "/" + a) : a + "/" + b
    }

    // MARK: Relative Filter (relfilter1)

    static let relOps: Set<String> = ["today", "this_week", "this_month", "yesterday", "tomorrow", "last_week", "next_week",
                                      "last_month", "next_month", "this_year", "overdue"]
    static let relValueOps: Set<String> = ["next_days", "past_days", "on_or_before", "on_or_after"]
    private static let tokenRe = try! NSRegularExpression(pattern: #"^today(?:([+-])([0-9]{1,4}))?$"#)

    /// Unvollstaendiger Wert (zaehlt nicht)?
    static func relIncomplete(_ op: String, _ fv: Any?) -> Bool {
        let s = fv as? String ?? ""
        if op == "next_days" || op == "past_days" {
            guard s.range(of: #"^[0-9]{1,3}$"#, options: .regularExpression) != nil, let n = Int(s) else { return true }
            return !(1...366).contains(n)
        }
        if tokenRe.firstMatch(in: s, range: NSRange(location: 0, length: (s as NSString).length)) != nil { return false }
        return s.range(of: #"^[0-9]{4}-[0-9]{2}-[0-9]{2}$"#, options: .regularExpression) == nil || jsDay(s) == nil
    }

    /// new Date(+s.slice(0,4), +s.slice(5,7) - 1, +s.slice(8,10)) als Tagesnummer (Rueckfall fuer alte Werte)
    static func jsDay(_ s: String) -> Int? {
        let u = Array(s.utf16)
        func sl(_ a: Int, _ b: Int) -> String { a >= u.count ? "" : String(utf16CodeUnits: Array(u[a..<min(b, u.count)]), count: min(b, u.count) - a) }
        guard let y0 = jsNum(sl(0, 4)), let m0 = jsNum(sl(5, 7)), let d0 = jsNum(sl(8, 10)) else { return nil }
        var y = Int(y0), m = Int(m0) - 1
        let d = Int(d0)
        if (0...99).contains(y) { y += 1900 }
        y += Int((Double(m) / 12).rounded(.down)); m = ((m % 12) + 12) % 12
        guard abs(y) <= 400000 else { return nil }
        let n = days(y, m + 1, 1) + d - 1
        return abs(n) <= 100_000_000 ? n : nil
    }
    /// Number(text) fuer kurze Stuecke (leer = 0, nicht lesbar = nil)
    static func jsNum(_ s: String) -> Double? {
        let t = s.trimmingCharacters(in: .whitespacesAndNewlines)
        if t.isEmpty { return 0 }
        let lo = t.lowercased()                                  // Number("0x10") = 16 (auch 0o/0b)
        if lo.count > 2, lo.hasPrefix("0x") || lo.hasPrefix("0o") || lo.hasPrefix("0b"), let n = Int(lo.dropFirst(2), radix: lo.hasPrefix("0x") ? 16 : lo.hasPrefix("0o") ? 8 : 2) { return Double(n) }
        guard t.range(of: #"^[+-]?(\d+\.?\d*([eE][+-]?\d+)?|\.\d+([eE][+-]?\d+)?)$"#, options: .regularExpression) != nil, let d = Double(t), d.isFinite else { return nil }
        return d.rounded(.towardZero)
    }

    /// (Starttag, Endtag) eines Werts in tz; alte Werte ueber die ersten 10 Zeichen
    static func daySpan(_ v: Any?, _ tz: TimeZone) -> (Int, Int)? {
        if let s = spans(v, tz) { return (s.0, s.1) }
        let s: String
        switch v {
        case let x as String: s = x
        case let b as Bool: s = b ? "true" : ""
        case let n as Double: s = n == 0 ? "" : DBDate.jsNumStr(n)
        case let a as [AnyHashable]: s = a.map { "\($0)" }.joined(separator: ",")
        default: s = ""
        }
        return jsDay(s).map { ($0, $0) }
    }
    static func jsNumStr(_ n: Double) -> String {
        if n.rounded() == n && abs(n) < 1e21 { return String(format: "%.0f", n) }
        return "\(n)"
    }

    /// Relativer Datumsfilter wie rel_date im Server; `today` = Tagesnummer "heute" in tz
    static func rel(_ op: String, _ v: Any?, _ fv: Any? = nil, today t0: Int, tz: TimeZone) -> Bool {
        let sp = daySpan(v, tz)
        func touch(_ a: Int, _ b: Int) -> Bool { guard let sp else { return false }; return sp.0 < b && sp.1 >= a }
        if relValueOps.contains(op) {
            let s = fv as? String ?? ""
            if op == "next_days" || op == "past_days" {
                guard s.range(of: #"^[0-9]{1,3}$"#, options: .regularExpression) != nil, let n = Int(s), (1...366).contains(n) else { return true }
                return op == "next_days" ? touch(t0, t0 + n) : touch(t0 - n + 1, t0 + 1)
            }
            let ns = s as NSString
            var ref: Int
            if let m = tokenRe.firstMatch(in: s, range: NSRange(location: 0, length: ns.length)) {
                ref = t0
                if m.range(at: 1).location != NSNotFound { ref += (ns.substring(with: m.range(at: 1)) == "+" ? 1 : -1) * (Int(ns.substring(with: m.range(at: 2))) ?? 0) }
            } else if s.range(of: #"^[0-9]{4}-[0-9]{2}-[0-9]{2}$"#, options: .regularExpression) != nil, let r = jsDay(s) { ref = r }
            else { return true }
            guard let sp else { return false }
            return op == "on_or_before" ? sp.0 <= ref : sp.1 >= ref
        }
        if op == "overdue" { return sp.map { $0.1 < t0 } ?? false }
        let (y, m, _) = civil(t0)
        func monthStart(_ yy: Int, _ mm: Int) -> Int { let k = mm - 1; let y2 = yy + Int((Double(k) / 12).rounded(.down)); return days(y2, ((k % 12) + 12) % 12 + 1, 1) }
        switch op {
        case "this_month", "last_month", "next_month":
            let k = op == "this_month" ? 0 : op == "last_month" ? -1 : 1
            return touch(monthStart(y, m + k), monthStart(y, m + k + 1))
        case "this_year": return touch(days(y, 1, 1), days(y + 1, 1, 1))
        case "this_week", "last_week", "next_week":
            let a = t0 - weekday(t0) + (op == "this_week" ? 0 : op == "last_week" ? -7 : 7)
            return touch(a, a + 7)
        default:
            let a = t0 + (op == "yesterday" ? -1 : op == "tomorrow" ? 1 : 0)
            return touch(a, a + 1)
        }
    }
    /// "heute" als Tagesnummer: `today` (JJJJ-MM-TT) oder jetzt in tz
    static func todayNumber(_ today: String?, tz: TimeZone, now: Date = Date()) -> Int {
        if let today, let p = part(today) { return days(p.y, p.m, p.d) }
        let p = part(dayString(now, tz: tz))!
        return days(p.y, p.m, p.d)
    }
}
