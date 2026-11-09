import Foundation

// Saga 2.5 Widgets in Seiten, Etappe 1 (SPEC-widgets, Abschnitt 4): reine Logik ohne SwiftUI, mit swiftc pruefbar
// (Tests/Logic/WidgetTests.swift). Die App zeigt Widgets nur an - Bearbeiten im Web. Der Block bleibt beim Speichern
// unveraendert (Regel 0): hier wird nur gelesen, nie in block.data geschrieben.

enum WidgetLogic {
    /// Arten der Etappe 1; andere Werte -> Karte "neuere Version noetig"
    static let kinds: Set<String> = ["clock", "countdown", "progress", "metric", "quote", "weather", "habit", "upcoming", "chart", "reading"]

    enum Size: String { case s, m, l }
    enum Style: String { case card, plain }

    /// Block-Daten (block.data) eines Widgets - begrenzt gelesen, Unbekanntes ignoriert
    struct Block: Equatable {
        var kind: String
        var size: Size = .m
        var style: Style = .card
        var accent: String?
        var title: String = ""
        var cfg: [String: Any] = [:]
        var known: Bool { WidgetLogic.kinds.contains(kind) }

        static func == (a: Block, b: Block) -> Bool {
            a.kind == b.kind && a.size == b.size && a.style == b.style && a.accent == b.accent && a.title == b.title
                && NSDictionary(dictionary: a.cfg).isEqual(to: b.cfg)
        }
    }

    static func block(_ data: NSDictionary?) -> Block {
        let d = data ?? [:]
        var b = Block(kind: (d["kind"] as? String) ?? "")
        b.size = Size(rawValue: d["size"] as? String ?? "") ?? .m
        b.style = Style(rawValue: d["style"] as? String ?? "") ?? .card
        if let a = d["accent"] as? String, GestaltenLogic.palette.contains(a) { b.accent = a }
        b.title = String((d["title"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines).prefix(80))
        b.cfg = (d["cfg"] as? [String: Any]) ?? [:]
        return b
    }

    /// Holt die App einen Wert vom Server? metric, progress/db und jedes quote (der Server waehlt auch feste Zitate
    /// nach `pick` - gleiche Auswahl wie im Web); Uhr, Countdown und fester Fortschritt rechnen nur lokal
    static func needsServer(_ b: Block) -> Bool {
        switch b.kind {
        case "metric", "quote", "weather", "habit", "upcoming", "chart", "reading": return true
        case "progress": return (b.cfg["src"] as? String) == "db"
        default: return false
        }
    }

    /// Kann die App das Widget ohne Server-Wert zeigen? (festes Zitat: lokale Auswahl als Rueckfall)
    static func hasLocalFallback(_ b: Block) -> Bool {
        b.kind == "quote" && (b.cfg["src"] as? String) != "db" && !items(b.cfg).isEmpty
    }

    /// Enthaelt die Seite (auch in Spalten und Toggles) ein Widget, dessen Wert vom Server kommt?
    static func pageNeedsServer(_ blocks: NSArray) -> Bool {
        var found = false
        walk(blocks) { b in
            if b["type"] as? String == "widget" {
                if needsServer(block(b["data"] as? NSDictionary)) { found = true }
            }
        }
        return found
    }

    /// Alle Bloecke einer Seite, auch in Spalten (data.cols[].blocks) und Toggles (data.blocks)
    static func walk(_ blocks: NSArray, _ f: (NSDictionary) -> Void) {
        for case let b as NSDictionary in blocks {
            f(b)
            let d = b["data"] as? NSDictionary
            for case let c as NSDictionary in (d?["cols"] as? NSArray ?? []) { if let inner = c["blocks"] as? NSArray { walk(inner, f) } }
            if let inner = d?["blocks"] as? NSArray { walk(inner, f) }
        }
    }

    // MARK: Antwort GET /api/notes/{nid}/widgets

    /// Wert eines Widgets vom Server (W)
    struct Value: Equatable {
        var kind: String = ""
        var value: Double?
        var max: Double?
        var text: String?
        var fmt: String?
        var asOf: Date?
        var error: String?
        /// 2.5 Etappe 2 (weather/habit/upcoming/chart): strukturierte Daten je Art (roh, JSON)
        var data: Data?
        var dict: [String: Any]? { data.flatMap { (try? JSONSerialization.jsonObject(with: $0)) as? [String: Any] } }
    }

    struct Response: Equatable {
        var widgets: [String: Value] = [:]
        var unsupported = false
    }

    static func parse(_ data: Data) -> Response? {
        guard let o = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return nil }
        var r = Response()
        r.unsupported = (o["unsupported"] as? Bool) ?? false || (o["unsupported2"] as? Bool) ?? false
        for (id, raw) in (o["widgets"] as? [String: Any]) ?? [:] {
            guard let w = raw as? [String: Any] else { continue }
            var v = Value()
            v.kind = w["kind"] as? String ?? ""
            v.value = num(w["value"]); v.max = num(w["max"])
            v.text = w["text"] as? String
            v.fmt = w["fmt"] as? String
            v.asOf = (w["as_of"] as? String).flatMap(isoDate)
            v.error = w["error"] as? String
            if let d = w["data"] as? [String: Any] { v.data = try? JSONSerialization.data(withJSONObject: d, options: .sortedKeys) }
            r.widgets[id] = v
        }
        return r
    }

    private static func isoDate(_ s: String) -> Date? {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let d = f.date(from: s) { return d }
        f.formatOptions = [.withInternetDateTime]
        return f.date(from: s)
    }

    // MARK: Zustand und Hinweise

    /// Stand der Werte einer Seite
    enum Load: Equatable {
        case idle                 // noch nicht geladen (bzw. nicht noetig)
        case loaded(Response)
        case missing              // Endpunkt fehlt (aelterer Server, 404)
        case failed               // offline/Fehler - zuletzt geladene Werte bleiben, falls vorhanden
    }

    /// Hinweis statt eines Werts (freundlicher Text in WidgetViews)
    enum Notice: Equatable {
        case newerVersion         // unbekannte Art
        case noAccess, gone, badCfg, tooBig
        case olderServer          // 404: Server ohne Widgets
        case originOnly           // Foederation: Gegenstelle ohne Faehigkeit widgets1
        case failed               // nicht geladen (offline)
        case unknownError         // anderer Fehlercode vom Server
    }

    static func notice(error: String?) -> Notice? {
        switch error {
        case nil, "": return nil
        case "disabled", "unavailable": return nil              // Wetter: eigener Hinweis in der Karte
        case "no_access": return .noAccess
        case "gone": return .gone
        case "bad_cfg": return .badCfg
        case "too_big": return .tooBig
        default: return .unknownError
        }
    }

    /// Was ein Widget zeigt: Hinweis (oder nil = Inhalt). `value` = Server-Wert des Blocks, falls geladen.
    static func notice(block b: Block, load: Load, value: Value?) -> Notice? {
        guard b.known else { return .newerVersion }
        if let e = notice(error: value?.error) { return e }
        guard needsServer(b), !hasLocalFallback(b) else { return nil }   // lokal berechenbar
        switch load {
        case .missing: return .olderServer
        case .loaded(let r):
            if r.unsupported { return .originOnly }
            return nil                                       // Wert fehlt noch (Seite eben geaendert) -> leer statt Fehler
        case .failed: return value == nil ? .failed : nil
        case .idle: return nil
        }
    }

    /// Server-Wert eines Blocks aus dem Ladezustand
    static func value(_ load: Load, blockID: String) -> Value? {
        if case .loaded(let r) = load { return r.widgets[blockID] }
        return nil
    }

    /// Neuer Stand nach einem Abruf: ein Fehler (offline) verdraengt zuletzt geladene Werte nicht
    static func merge(old: Load, new: Load) -> Load {
        if new == .failed, case .loaded = old { return old }
        return new
    }

    /// Ergebnis einer Anfrage GET .../widgets: 200 -> Werte, 404 -> aelterer Server, sonst Fehler
    static func load(code: Int, data: Data) -> Load {
        if code == 404 || code == 405 { return .missing }
        guard code == 200, let r = parse(data) else { return .failed }
        return .loaded(r)
    }

    // MARK: Uhr

    /// Zeitzone der Uhr: IANA-Name aus cfg.tz, sonst (leer/unbekannt) die des Betrachters
    static func clockZone(_ cfg: [String: Any], viewer: TimeZone = .autoupdatingCurrent) -> TimeZone {
        if let n = cfg["tz"] as? String, !n.isEmpty, let z = TimeZone(identifier: n) { return z }
        return viewer
    }

    /// Ortsname zur Zeitzone ("Europe/Berlin" -> "Berlin", "America/Argentina/Buenos_Aires" -> "Buenos Aires"); leer = Betrachter
    static func zoneLabel(_ cfg: [String: Any]) -> String {
        guard let n = cfg["tz"] as? String, !n.isEmpty, TimeZone(identifier: n) != nil else { return "" }
        return (n.split(separator: "/").last.map(String.init) ?? n).replacingOccurrences(of: "_", with: " ")
    }

    static func bool(_ cfg: [String: Any], _ k: String, _ def: Bool) -> Bool {
        if let b = cfg[k] as? Bool { return b }
        return def
    }

    /// Uhrzeit (digital) in der Sprache der App, gregorianisch
    static func clockTime(_ date: Date, cfg: [String: Any], locale: Locale, viewer: TimeZone = .autoupdatingCurrent) -> String {
        let f = DateFormatter()
        f.locale = locale; f.calendar = Calendar(identifier: .gregorian); f.timeZone = clockZone(cfg, viewer: viewer)
        let h24 = bool(cfg, "h24", true), sec = bool(cfg, "sec", false)
        // feste Muster: die Vorlage "jj" wuerde die Spracheinstellung ueber h24 stellen
        if h24 { f.dateFormat = sec ? "HH:mm:ss" : "HH:mm" }
        else { f.setLocalizedDateFormatFromTemplate(sec ? "hmmssa" : "hmma") }
        return f.string(from: date)
    }

    /// Datum unter der Uhr ("Dienstag, 27. Oktober")
    static func clockDate(_ date: Date, cfg: [String: Any], locale: Locale, viewer: TimeZone = .autoupdatingCurrent) -> String {
        let f = DateFormatter()
        f.locale = locale; f.calendar = Calendar(identifier: .gregorian); f.timeZone = clockZone(cfg, viewer: viewer)
        f.setLocalizedDateFormatFromTemplate("EEEEdMMMM")
        return f.string(from: date)
    }

    /// Kurzdatum unter der Zeigeruhr ("Mo., 9. Nov.")
    static func clockShortDate(_ date: Date, cfg: [String: Any], locale: Locale, viewer: TimeZone = .autoupdatingCurrent) -> String {
        let f = DateFormatter()
        f.locale = locale; f.calendar = Calendar(identifier: .gregorian); f.timeZone = clockZone(cfg, viewer: viewer)
        f.setLocalizedDateFormatFromTemplate("EEEdMMM")
        return f.string(from: date)
    }

    /// Zeigerwinkel (Grad, 0 = 12 Uhr, im Uhrzeigersinn) fuer die Zeigeruhr
    struct Hands: Equatable { var hour: Double; var minute: Double; var second: Double }

    static func hands(_ date: Date, cfg: [String: Any], viewer: TimeZone = .autoupdatingCurrent) -> Hands {
        var cal = Calendar(identifier: .gregorian); cal.timeZone = clockZone(cfg, viewer: viewer)
        let c = cal.dateComponents([.hour, .minute, .second], from: date)
        let h = Double((c.hour ?? 0) % 12), m = Double(c.minute ?? 0), s = Double(c.second ?? 0)
        return Hands(hour: (h + m / 60) * 30, minute: (m + s / 60) * 6, second: s * 6)
    }

    // MARK: Countdown

    enum Countdown: Equatable {
        case days(Int, since: Bool)               // "noch 12 Tage" / "seit 340 Tagen"
        case hoursMinutes(Int, Int, since: Bool)  // unter einem Tag (unit auto)
        case today                                // Zieltag ist heute (unit days)
        case reached                              // "bis": Ziel ueberschritten
        case notYet                               // "seit": Ziel liegt in der Zukunft
        case invalid                              // Datum fehlt/kaputt
    }

    /// Zielzeitpunkt in der Zeitzone des Betrachters ("YYYY-MM-DD", "HH:MM" oder "" = Tagesbeginn)
    static func target(_ cfg: [String: Any], calendar cal: Calendar) -> Date? {
        guard let ds = cfg["date"] as? String else { return nil }
        let p = ds.split(separator: "-").compactMap { Int($0) }
        guard p.count == 3, ds.count == 10, (1...12).contains(p[1]), (1...31).contains(p[2]) else { return nil }
        var c = DateComponents(year: p[0], month: p[1], day: p[2], hour: 0, minute: 0)
        if let ts = cfg["time"] as? String, !ts.isEmpty {
            let t = ts.split(separator: ":").compactMap { Int($0) }
            guard t.count == 2, (0...23).contains(t[0]), (0...59).contains(t[1]) else { return nil }
            c.hour = t[0]; c.minute = t[1]
        }
        guard let d = cal.date(from: c), cal.component(.day, from: d) == p[2] else { return nil }   // 31.02. -> ungueltig
        return d
    }

    /// Countdown zum Zeitpunkt `now`. unit "days": Kalendertage (Ziel heute = today); "auto": unter einem Tag Std./Min.
    static func countdown(_ cfg: [String: Any], now: Date, calendar: Calendar = SagaDate.calendar) -> Countdown {
        var cal = calendar
        if cal.identifier != .gregorian { cal = SagaDate.calendar(base: calendar) }
        guard let t = target(cfg, calendar: cal) else { return .invalid }
        let since = (cfg["dir"] as? String) == "since"
        let auto = (cfg["unit"] as? String) == "auto"
        let secs = since ? now.timeIntervalSince(t) : t.timeIntervalSince(now)
        if auto {
            if secs <= 0 { return since ? .notYet : .reached }
            if secs < 86_400 {
                let mins = Int((secs / 60).rounded(.up))       // "noch 1 Min." bis zur letzten Minute
                let total = since ? Int(secs / 60) : mins
                return .hoursMinutes(total / 60, total % 60, since: since)
            }
        }
        let a = cal.startOfDay(for: now), b = cal.startOfDay(for: t)
        let days = cal.dateComponents([.day], from: since ? b : a, to: since ? a : b).day ?? 0
        if days == 0 { return auto ? .days(0, since: since) : .today }
        if days < 0 { return since ? .notYet : .reached }
        return .days(days, since: since)
    }

    // MARK: Fortschritt

    struct Progress: Equatable {
        var value: Double
        var max: Double
        /// Anteil 0 ... 1 (fuer den Balken)
        var fraction: Double { max > 0 && value.isFinite ? Swift.min(1, Swift.max(0, value / max)) : 0 }
        /// Prozent fuer den Text (ganzzahlig, ueber 100 % moeglich)
        var percent: Int {
            let p = (value / max * 100).rounded()
            // Int(_:) stuerzt bei unendlich/riesig ab (z. B. value 1e11, max 1e-9 aus unbereinigten Daten)
            return max > 0 && !p.isNaN ? Int(Swift.min(1e9, Swift.max(-1e9, p))) : 0
        }
    }

    /// Fortschritt: fest aus cfg (value/max), aus DB vom Server (value/max); nil = (noch) kein Wert
    static func progress(_ b: Block, value v: Value?) -> Progress? {
        if (b.cfg["src"] as? String) == "db" {
            guard let val = v?.value, let mx = v?.max, mx > 0 else { return nil }
            return Progress(value: val, max: mx)
        }
        guard let val = num(b.cfg["value"]), let mx = num(b.cfg["max"]), mx > 0 else { return nil }
        return Progress(value: val, max: mx)
    }

    // MARK: Kennzahl

    /// Zahl formatieren: num (bis 2 Nachkommastellen), pct (ganzzahlig, z. B. 42,5 -> "43 %"), eur (Waehrung, 2 Nachkommastellen)
    static func format(_ value: Double?, fmt: String?, locale: Locale) -> String {
        NumFormat.text(value, fmt: fmt, locale: locale)                 // fix27 #10: ein Format fuer Widgets und Tabellen
    }

    /// Format der Werte eines Fortschritts ("1.430,50 € von 1.200,00 €"): Server-Angabe, sonst Block-Einstellung, sonst Zahl
    static func progressFmt(_ b: Block, value v: Value?) -> String {
        let f = NumFormat.kind(v?.fmt ?? (b.cfg["fmt"] as? String))
        return f == "pct" ? "num" : f                                   // Prozent steht schon gross darueber
    }

    /// Format einer Kennzahl: Server-Angabe, sonst Block-Einstellung
    static func metricFmt(_ b: Block, value v: Value?) -> String {
        let f = v?.fmt ?? (b.cfg["fmt"] as? String)
        return ["num", "pct", "eur"].contains(f ?? "") ? f! : "num"
    }

    /// Ziel einer Kennzahl erreicht? (nil = kein Ziel/kein Wert)
    static func goalReached(_ b: Block, value v: Value?) -> Bool? {
        guard let g = num(b.cfg["goal"]), let x = v?.value else { return nil }
        return x >= g
    }

    // MARK: Zitat

    /// Feste Texte (cfg.items), leere entfernt, hoechstens 20
    static func items(_ cfg: [String: Any]) -> [String] {
        ((cfg["items"] as? [Any]) ?? []).compactMap { $0 as? String }
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }.filter { !$0.isEmpty }.prefix(20).map { String($0.prefix(300)) }
    }

    /// Index eines festen Zitats: "daily" deterministisch aus (Tag des Betrachters, Block-ID), "open" aus `seed`
    /// (einmal je Oeffnen der Seite gewuerfelt)
    static func quoteIndex(count: Int, pick: String?, blockID: String, now: Date, calendar: Calendar = SagaDate.calendar, seed: UInt64) -> Int {
        guard count > 0 else { return 0 }
        if pick == "open" { return Int(seed % UInt64(count)) }
        let c = calendar.dateComponents([.year, .month, .day], from: now)
        let key = String(format: "%04d-%02d-%02d", c.year ?? 0, c.month ?? 0, c.day ?? 0) + "|" + blockID
        return Int(fnv1a(key) % UInt64(count))
    }

    /// Text eines Zitats: Server-Text, sonst (nur wenn `local`, d. h. der Server keinen liefert oder nicht erreichbar ist)
    /// fest nach pick; nil = nichts anzuzeigen
    static func quoteText(_ b: Block, value v: Value?, local: Bool, blockID: String, now: Date, calendar: Calendar = SagaDate.calendar, seed: UInt64) -> String? {
        if let t = v?.text?.trimmingCharacters(in: .whitespacesAndNewlines), !t.isEmpty { return t }
        guard local, (b.cfg["src"] as? String) != "db" else { return nil }
        let its = items(b.cfg)
        guard !its.isEmpty else { return nil }
        return its[quoteIndex(count: its.count, pick: b.cfg["pick"] as? String, blockID: blockID, now: now, calendar: calendar, seed: seed)]
    }

    /// Lokale Auswahl erlaubt? Erst, wenn klar ist, dass der Server nichts liefert (nicht waehrend des ersten Ladens)
    static func quoteLocal(_ load: Load) -> Bool {
        switch load {
        case .idle: return false
        case .loaded, .missing, .failed: return true
        }
    }

    /// FNV-1a 64 bit (stabil ueber Starts, anders als Hasher)
    static func fnv1a(_ s: String) -> UInt64 {
        var h: UInt64 = 0xcbf29ce484222325
        for b in s.utf8 { h ^= UInt64(b); h = h &* 0x100000001b3 }
        return h
    }

    // MARK: Widget-Design-Runde (9.11.): Regeln wie im Web (static/pages.js wgVal, Zitat-Quelle, Gewohnheiten, Diagramm)

    /// Innenabstand (oben/unten, seitlich) und Mindesthoehe je Groesse (Regel 4)
    static func padding(_ s: Size) -> (v: Double, h: Double) { s == .s ? (10, 12) : s == .l ? (18, 20) : (14, 16) }
    static func minHeight(_ s: Size) -> Double { s == .s ? 72 : s == .l ? 144 : 104 }
    /// Diagrammhoehe je Groesse (Regel 14) - wie CH_H im Web
    static func chartHeight(_ s: Size) -> Double { s == .s ? 140 : s == .l ? 300 : 220 }
    /// Zeilen eines Zitats je Groesse (Regel 11)
    static func quoteLines(_ s: Size) -> Int { s == .s ? 4 : s == .l ? 8 : 6 }

    /// Wert in Zahl und kleine Einheit zerlegen (Regel 5): "2.250,00 €" -> ("2.250,00", "€"), "42 Tage" -> ("42", "Tage"),
    /// "$12" -> Einheit davor. Wie wgVal im Web: Einheit hoechstens 4 Zeichen ohne Ziffern.
    struct ValueParts: Equatable { var prefix: String?; var number: String; var unit: String? }
    static func valueParts(_ s: String) -> ValueParts {
        let ns = s as NSString, all = NSRange(location: 0, length: ns.length)
        if let r = try? NSRegularExpression(pattern: #"^(.*?[\d)])\s*([^\d\s.,\-−+()]{1,4})$"#),
           let m = r.firstMatch(in: s, range: all) {
            return ValueParts(prefix: nil, number: ns.substring(with: m.range(at: 1)), unit: ns.substring(with: m.range(at: 2)))
        }
        if let r = try? NSRegularExpression(pattern: #"^([^\d\-−+\s]{1,4})\s*(.*\d.*)$"#),
           let m = r.firstMatch(in: s, range: all) {
            return ValueParts(prefix: ns.substring(with: m.range(at: 1)), number: ns.substring(with: m.range(at: 2)), unit: nil)
        }
        return ValueParts(prefix: nil, number: s, unit: nil)
    }

    /// Quelle eines Zitats (Regel 11): "Text. – Name" bzw. "Text\n— Name" -> eigene Zeile. Nur nach Satzende/Zeilenumbruch,
    /// Name mit Grossbuchstaben, hoechstens 6 Woerter, ohne Satzzeichen am Ende (wie im Web)
    static func quoteParts(_ text: String) -> (text: String, source: String?) {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines), ns = t as NSString
        guard let r = try? NSRegularExpression(pattern: #"^([\s\S]{8,}?(?:[.!?…"“”»«’']|\n))\s*(?:—|–|--)\s*(\p{Lu}[^—–\n]{1,59})$"#),
              let m = r.firstMatch(in: t, range: NSRange(location: 0, length: ns.length)) else { return (t, nil) }
        let src = ns.substring(with: m.range(at: 2)).trimmingCharacters(in: .whitespaces)
        guard src.split(whereSeparator: { $0 == " " || $0 == "\t" }).count <= 6, let last = src.last, !".!?…:".contains(last) else { return (t, nil) }
        return (ns.substring(with: m.range(at: 1)).trimmingCharacters(in: .whitespacesAndNewlines), src)
    }

    /// Gewohnheiten je Kartenbreite (Regel 12): 7 Tage Kreise; 14/30 Tage Heatmap (Quadrate), schmal (< 420) nur die
    /// letzten 14, sehr schmal (< 240) bzw. Groesse S nur die letzten 7 (unter 240 wieder als Kreise, ohne Kopf).
    /// hint: "letzte n Tage", wenn weniger Tage gezeigt werden als die Quote abdeckt. oneLine: Name | Tage | Quote ab 420.
    struct HabitLayout: Equatable { var shown: Int; var heat: Bool; var header: Bool; var oneLine: Bool; var hint: Int? }
    static func habitLayout(days n: Int, width w: Double, size: Size) -> HabitLayout {
        let oneLine = w >= 420
        guard n > 7 else { return HabitLayout(shown: n, heat: false, header: true, oneLine: oneLine, hint: nil) }
        var shown = n, heat = true, header = true
        if w < 420 { shown = min(n, 14) }
        if size == .s { shown = min(n, 7) }
        if w < 240 { shown = min(n, 7); heat = false; header = false }
        return HabitLayout(shown: shown, heat: heat, header: header, oneLine: oneLine, hint: shown < n ? shown : nil)
    }

    /// Zellmasse der Gewohnheiten wie im Web (grid minmax(0, --hbc)): Zelle = min(Maximum, Platz / Anzahl), immer
    /// quadratisch mit fester Seitenlaenge - wird nie hoeher als breit, auch bei sehr breiter Karte (iPad).
    /// Breit (oneLine): Name 80...144 | Tage | Quote 104, Abstaende 2 x 10; der Name gibt Platz ab, wenn die Tage
    /// sonst schrumpfen muessten. Tage-Zeile hat 2 pt Rand je Seite fuer den Ring um heute.
    struct HabitGeom: Equatable { var name: Double; var cell: Double; var gap: Double }
    static let habitStatsWidth = 104.0, habitRowGap = 10.0, habitRingPad = 2.0
    static func habitCellMax(heat: Bool, size: Size) -> Double {
        heat ? (size == .l ? 17 : 13) : (size == .s ? 19 : size == .l ? 30 : 22)
    }
    static func habitGeom(width w: Double, layout L: HabitLayout, size: Size) -> HabitGeom {
        let n = Double(max(1, L.shown)), gap = L.heat ? 2.0 : 3.0, mx = habitCellMax(heat: L.heat, size: size)
        let need = n * mx + (n - 1) * gap + 2 * habitRingPad
        var name = 0.0, avail = max(0, w)
        if L.oneLine {
            let rest = avail - 2 * habitRowGap - habitStatsWidth
            name = min(144, max(80, rest - need))
            avail = max(0, rest - name)
        }
        let fit = (avail - 2 * habitRingPad - (n - 1) * gap) / n
        let cell = max(4, min(mx, (fit * 2).rounded(.down) / 2))
        return HabitGeom(name: name, cell: cell, gap: gap)
    }

    /// Wetter: schmal (bis 330 wie die Container-Regel im Web) Tage als Liste statt Kacheln (Regel 15)
    static func weatherList(width: Double) -> Bool { width <= 330 }

    // MARK: Hilfen

    static func num(_ v: Any?) -> Double? {
        // true/false sind keine Zahlen (JSONSerialization liefert beides als NSNumber)
        if let n = v as? NSNumber { return CFGetTypeID(n) == CFBooleanGetTypeID() ? nil : n.doubleValue }
        return nil
    }
}
