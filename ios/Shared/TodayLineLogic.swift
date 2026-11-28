import Foundation

// 2.6 Tageszeile „Kurz gesagt“ auf „Heute“ (Bugs-DB 2271 K-D, Server docs/APP-API-KI.md §13). Reine Logik ohne SwiftUI:
// Decodieren, Anzeige-Regeln, Ziele, Pfade, Widget. Testvektoren: Tests/vectors/today_vectors.json (Kopie von
// tests/today_vectors.json aus dem Server-Repo, Format saga-today/v1), Tests/Logic/TodayLineTests.swift.
// Grundsaetze: Texte kommen fertig vom Server (Sprache der Anfrage) - hier werden keine Saetze gebaut; Zustaende nur an
// Feldern erkennen, nie an Texten; `sentence` nur fuer Siri (K-G, SagaIntentLogic.todaySpeech), nie gezeigt; 4xx/5xx -> nichts.
// Gemeinsam mit dem Widget (TodayData.line): deshalb in Shared und tolerant (ein kaputtes Feld bricht das Widget nicht).

/// Ziel eines Teils bzw. von „Wartet auf dich“ - Tippen oeffnet nur (hakt nie ab)
struct TodayLineTarget: Codable, Hashable {
    var type: String
    var id: Int?
    var filter: String?
    var date: String?
    var group: String?
    init(type: String, id: Int? = nil, filter: String? = nil, date: String? = nil, group: String? = nil) {
        self.type = type; self.id = id; self.filter = filter; self.date = date; self.group = group
    }
    init(from d: Decoder) throws {
        let c = try d.container(keyedBy: CodingKeys.self)
        type = try c.decode(String.self, forKey: .type)
        id = (try? c.decodeIfPresent(Int.self, forKey: .id)) ?? nil
        filter = (try? c.decodeIfPresent(String.self, forKey: .filter)) ?? nil
        date = (try? c.decodeIfPresent(String.self, forKey: .date)) ?? nil
        group = (try? c.decodeIfPresent(String.self, forKey: .group)) ?? nil
    }
}

struct TodayLinePart: Codable, Hashable {
    var kind: String
    var text: String
    var n: Int?
    var target: TodayLineTarget?
    init(from d: Decoder) throws {
        let c = try d.container(keyedBy: CodingKeys.self)
        kind = (try? c.decode(String.self, forKey: .kind)) ?? ""
        text = try c.decode(String.self, forKey: .text)
        n = (try? c.decodeIfPresent(Int.self, forKey: .n)) ?? nil
        target = (try? c.decodeIfPresent(TodayLineTarget.self, forKey: .target)) ?? nil
    }
}

struct TodayLineText: Codable, Hashable {
    var text: String
    var a11y: String?
    var parts: [TodayLinePart]
    init(from d: Decoder) throws {
        let c = try d.container(keyedBy: CodingKeys.self)
        text = (try? c.decode(String.self, forKey: .text)) ?? ""
        a11y = (try? c.decodeIfPresent(String.self, forKey: .a11y)) ?? nil
        // einzelne kaputte Teile auslassen statt die ganze Zeile zu verwerfen
        let raw = (try? c.decode([Lossy<TodayLinePart>].self, forKey: .parts)) ?? []
        parts = raw.compactMap(\.value).filter { !$0.text.isEmpty }
    }
}

struct TodayWaiting: Codable, Hashable {
    var n: Int?
    var text: String
    var target: TodayLineTarget?
}

struct TodayCounts: Codable, Hashable {
    /// nil = Kalender noch unbekannt (pending)
    var events: Int?
    var over: Int?
    var due: Int?
    var waiting: Int?
}

struct TodayQuiet: Codable, Hashable {
    var until: String?
    var text: String
    var undo: String
}

struct TodayAsk: Codable, Hashable {
    var text: String
    var yes: String
    var no: String
}

/// Antwort von GET/PUT /api/ai/today, POST|DELETE /api/ai/today/quiet bzw. `line` aus /api/app/today?line=1 (Widget,
/// dort ohne ask/info/sentence). `sentence` liest nur Siri vor ("Was steht heute an", K-G) - nie in der Anzeige.
struct TodayLineState: Codable, Hashable {
    var enabled: Bool
    var on: Bool?
    var day: String?
    var tz: String?
    var v: String?
    var label: String?
    var line: TodayLineText?
    var waiting: TodayWaiting?
    var counts: TodayCounts?
    var quiet: TodayQuiet?
    var ask: TodayAsk?
    var pending: [String]?
    var info: String?
    var sentence: String?

    init(from d: Decoder) throws {
        // Feld fuer Feld: ein unerwarteter Wert macht nur dieses Feld leer (Widget-Antwort bleibt lesbar)
        guard let c = try? d.container(keyedBy: CodingKeys.self) else { enabled = false; return }
        func opt<T: Decodable>(_ t: T.Type, _ k: CodingKeys) -> T? { (try? c.decodeIfPresent(t, forKey: k)) ?? nil }
        enabled = opt(Bool.self, .enabled) ?? false
        on = opt(Bool.self, .on); day = opt(String.self, .day); tz = opt(String.self, .tz); v = opt(String.self, .v)
        label = opt(String.self, .label); line = opt(TodayLineText.self, .line); waiting = opt(TodayWaiting.self, .waiting)
        counts = opt(TodayCounts.self, .counts); quiet = opt(TodayQuiet.self, .quiet); ask = opt(TodayAsk.self, .ask)
        pending = opt([String].self, .pending); info = opt(String.self, .info)
        sentence = opt(String.self, .sentence)
    }
}

/// Element einer Liste, das beim Decodieren fehlschlagen darf
private struct Lossy<T: Decodable>: Decodable {
    let value: T?
    init(from d: Decoder) throws { value = try? T(from: d) }
}

/// Was an der Stelle der Tageszeile zu sehen ist (Vektor-Feld `show`)
struct TodayLineShow: Equatable {
    struct Kurz: Equatable { var label: String; var parts: [TodayLinePart]; var a11y: String }
    var kurz: Kurz?
    var pill: TodayQuiet?
    var ask: TodayAsk?
    var waiting: TodayWaiting?
    var isEmpty: Bool { kurz == nil && pill == nil && ask == nil && waiting == nil }
}

/// Ergebnis eines Abrufs: neuer Stand, Stand behalten (304) oder nichts zeigen (alter Server, Fehler, ohne `ai`)
enum TodayLineFetch: Equatable {
    case state(TodayLineState)
    case keep
    case nothing
}

/// Wohin ein Tipp fuehrt
enum TodayLineNav: Equatable {
    case task(Int)
    case row(Int)
    case tasks(String)
    case events(String?)
    case jobs(String)
}

enum TodayWidgetSize { case small, medium, large, lock }

enum TodayLineLogic {
    /// Server-Merkmal (/api/version features): vorher wird /api/ai/today nicht gefragt
    static let feature = "aitoday1"
    /// Live `ai.today.changed {v}`: so lange entprellen
    static let debounceMs = 400

    static func shouldFetch(features: Set<String>) -> Bool { features.contains(feature) }

    /// IANA-Zone fuer `?tz=` ("Etc/GMT+1": "+" waere sonst ein Leerzeichen)
    static func tzQuery(_ tz: String) -> String {
        var ok = CharacterSet.alphanumerics; ok.insert(charactersIn: "/_-.")
        return tz.addingPercentEncoding(withAllowedCharacters: ok) ?? ""
    }
    static func path(tz: String) -> String { "api/ai/today?tz=" + tzQuery(tz) }
    static func quietPath(tz: String) -> String { "api/ai/today/quiet?tz=" + tzQuery(tz) }
    /// Widget (App-Schluessel hk_): aeltere Server ignorieren line/tz
    static func widgetPath(tz: String) -> String { "api/app/today?sources=1&line=1&tz=" + tzQuery(tz) }

    static func etag(_ v: String?) -> String? {
        guard let v, !v.isEmpty else { return nil }
        return "\"" + v + "\""
    }

    static func decode(_ data: Data?) -> TodayLineState? {
        guard let data, let s = try? JSONDecoder().decode(TodayLineState.self, from: data) else { return nil }
        return s
    }

    static func result(status: Int, data: Data?) -> TodayLineFetch {
        if status == 304 { return .keep }
        guard (200..<300).contains(status), let s = decode(data), s.enabled else { return .nothing }
        return .state(s)
    }

    /// Anzeige-Regeln (Vektoren `display`): Frage vor Pille vor Zeile; `waiting` eigene Zeile, auch bei aus/Ruhe
    static func show(_ s: TodayLineState?) -> TodayLineShow {
        guard let s, s.enabled else { return TodayLineShow() }
        var r = TodayLineShow()
        if let w = s.waiting, !w.text.isEmpty { r.waiting = w }
        if let a = s.ask, !a.text.isEmpty { r.ask = a; return r }
        if let q = s.quiet, !q.text.isEmpty { r.pill = q; return r }
        if s.on != false, let l = s.line, !l.parts.isEmpty {
            let a11y = (l.a11y?.isEmpty == false ? l.a11y : nil) ?? l.text
            r.kurz = .init(label: s.label ?? "", parts: l.parts, a11y: a11y)
        }
        return r
    }

    /// Ziel -> Navigation; unbekannter Typ (bzw. fehlende Kennung) -> nil = nicht antippbar
    static func nav(_ t: TodayLineTarget?) -> TodayLineNav? {
        guard let t else { return nil }
        switch t.type {
        case "task": return t.id.map { .task($0) }
        case "row": return t.id.map { .row($0) }
        case "tasks": return .tasks(t.filter ?? "today")
        case "events": return .events(t.date)
        case "jobs": return .jobs(t.group ?? "wartet_auf_dich")
        default: return nil
        }
    }

    /// Live: neu laden, wenn der Stand abweicht (ohne `v` sicherheitshalber auch)
    static func liveNeedsReload(eventV: String?, own: String?) -> Bool {
        guard let e = eventV, !e.isEmpty else { return true }
        return e != own
    }

    /// Frage „Tageszeile ausblenden?“: Ja = aus und beantwortet, Nein = nur beantwortet
    static func askBody(yes: Bool) -> [String: Bool] { yes ? ["on": false, "asked": true] : ["asked": true] }

    // MARK: Widget

    /// Mittel/Gross: fertiger Text der Zeile (privacySensitive); Klein und Sperrbildschirm nie (dort nur Zahlen wie bisher).
    /// Bei Ruhe/aus/ohne `ai`/aelterem Server: nil (keine Pille im Widget).
    static func widgetText(_ s: TodayLineState?, size: TodayWidgetSize) -> String? {
        guard size == .medium || size == .large, let s, s.enabled, s.on != false, s.quiet == nil, let l = s.line else { return nil }
        let t = l.text.isEmpty ? l.parts.map(\.text).joined(separator: " · ") : l.text
        return t.isEmpty ? nil : t
    }
    /// 2.6 K-G (2275): Zaehler „Wartet auf dich“ fuer Klein und Sperrbildschirm - nur die Zahl `counts.waiting` (nie
    /// Texte, RT-31), Tippen -> Aufträge. Auch bei Ruhe/aus (wie `waiting`); 0, ohne `ai` oder aelterer Server: nil
    static func widgetCount(_ s: TodayLineState?, size: TodayWidgetSize) -> Int? {
        guard size == .small || size == .lock, let s, s.enabled, let n = s.counts?.waiting ?? s.waiting?.n, n > 0 else { return nil }
        return n
    }
    /// „Wartet auf dich“ im Widget: nur Gross (Mittel hat keinen Platz dafuer)
    static func widgetWaiting(_ s: TodayLineState?, size: TodayWidgetSize) -> String? {
        guard size == .large, let s, s.enabled, let w = s.waiting, !w.text.isEmpty else { return nil }
        return w.text
    }
    /// Zeilen, die die Tageszeile im Widget belegt (Mittel 1, Gross 2 bzw. 3 mit „Wartet auf dich“)
    static func widgetRows(_ s: TodayLineState?, size: TodayWidgetSize) -> Int {
        guard widgetText(s, size: size) != nil else { return widgetWaiting(s, size: size) == nil ? 0 : 1 }
        return (size == .large ? 2 : 1) + (widgetWaiting(s, size: size) == nil ? 0 : 1)
    }
}
