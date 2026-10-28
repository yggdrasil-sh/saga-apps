import SwiftUI

// MARK: - Gespeichertes Inline-HTML <-> formatierter Text (zum direkten Bearbeiten, ohne Zeichen)

let hkMarkColor = Color.yellow.opacity(0.35)
let hkCodeColor = Color.gray.opacity(0.22)

/// 2.5 (inline2): Darstellung der Inline-Formen im Editor. Die Form steckt in sichtbaren Attributen (Schrift, Farben,
/// Link-Schema) und wird beim Speichern daraus zurueckgelesen - Farben werden ueber ihre aufgeloesten Werte erkannt
/// (so ueberstehen sie auch den Weg durch das Textfeld). Varianten mit gleichem Aussehen (Marker Gelb mit/ohne
/// cdx-marker, Inline-Code mit/ohne Klasse) unterscheiden sich nur unmerklich in der Deckkraft.
enum InlineStyle {
    static let mark = hkMarkColor
    static let cdx = Color.yellow.opacity(0.34)
    static let code = hkCodeColor
    static let inlineCode = Color.gray.opacity(0.21)
    static let math = Color.purple.opacity(0.14)
    static let text: [(String, Color)] = InlineRuns.palette.map { ($0, HKPalette.textColor($0)) }
    static let bg: [(String, Color)] = InlineRuns.palette.map { ($0, HKPalette.bgColor($0)) }
    private static func rgba(_ c: Color) -> [Float] {
        let r = c.resolve(in: EnvironmentValues())
        return [r.red, r.green, r.blue, r.opacity]
    }
    private static func match(_ c: Color?, _ list: [(String, Color)]) -> String? {
        guard let c else { return nil }
        let v = rgba(c)
        return list.first { k in zip(rgba(k.1), v).allSatisfy { abs($0 - $1) < 0.004 } }?.0
    }
    /// Hintergrund -> Markierung/Code
    static func background(_ c: Color?) -> (InlineFmt.Mark?, InlineFmt.Code?) {
        guard let c else { return (nil, nil) }
        if c == mark { return (.plain, nil) }
        if c == code { return (nil, .plain) }
        switch match(c, [("mark", mark), ("cdx", cdx), ("code", code), ("icode", inlineCode), ("math", math)]) {
        case "mark": return (.plain, nil)
        case "cdx": return (.cdx, nil)
        case "code": return (nil, .plain)
        case "icode": return (nil, .inline)
        case "math": return (nil, .math)
        default: return (match(c, bg).map { .color($0) }, nil)
        }
    }
    static func color(_ c: Color?) -> String? { match(c, text) }
    static func bgColor(_ m: InlineFmt.Mark?, _ cd: InlineFmt.Code?) -> Color? {
        switch cd {
        case .plain?: return code
        case .inline?: return inlineCode
        case .math?: return math
        case nil: break
        }
        switch m {
        case .plain?: return mark
        case .cdx?: return cdx
        case .color(let n)?: return HKPalette.bgColor(n)
        case nil: return nil
        }
    }
    static func mentionURL(_ ref: String) -> URL? { URL(string: "hkmention:" + (ref.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? ref)) }
    static func mentionRef(_ u: URL) -> String? {
        guard u.scheme == "hkmention" else { return nil }
        return String(u.absoluteString.dropFirst("hkmention:".count)).removingPercentEncoding
    }
}

/// Personen-Namen fuer Erwaehnungen (people aus GET /api/notes/{id}) - aktueller Name statt gespeichertem Text
enum MentionNames {
    nonisolated(unsafe) static var people: [String: [String: Any]] = [:]
    static func name(_ ref: String) -> String? { (people[ref]?["name"] as? String).flatMap { $0.isEmpty ? nil : $0 } }
    static func gone(_ ref: String) -> Bool { people[ref]?["gone"] as? Bool == true }
    static func me(_ ref: String) -> Bool { people[ref]?["me"] as? Bool == true }
    static func merge(_ pp: [String: Any]) { for (k, v) in pp { if let d = v as? [String: Any] { people[k] = d } } }
}

/// HTML -> AttributedString fuer den Editor. Fett/kursiv/Code ueber die Schrift (so bearbeitet sie der Editor),
/// unterstrichen/durchgestrichen/markiert/Farben/Link ueber eigene Attribute (2.5: alle Formen des Inline-Vertrags).
func richText(_ stored: String, base: Font) -> AttributedString {
    // Verweis auf eine Seite ohne Zugriff (1.3): auch beim Bearbeiten mit 🔒, gedaempft und nicht als Link;
    // intern hkna://<id>, beim Speichern wieder genau <a href="/notes#id">Text</a> (richToHTML)
    let html = ShareLogic.markMasked(PageLinkTitles.apply(stored), keepRef: true)   // Seitenverweise mit aktuellem Titel
    var out = AttributedString()
    for r in InlineRuns.parse(html) {
        var s = AttributedString(r.text)
        let f = r.fmt
        var font = f.code != nil ? base.monospaced() : base
        if f.bold { font = font.bold() }
        if f.italic { font = font.italic() }
        s.font = font
        if f.underline { s.underlineStyle = .single }
        if f.strike { s.strikethroughStyle = .single }
        if let bg = InlineStyle.bgColor(f.mark, f.code) { s.backgroundColor = bg }
        if let c = f.color { s.foregroundColor = HKPalette.textColor(c) }
        switch f.link {
        case .href(let h)?: s.link = linkURL(h)
        case .masked(let h)?:
            s.link = URL(string: "hkna://" + (h.range(of: #"-?[0-9]+$"#, options: .regularExpression).map { String(h[$0]) } ?? ""))
            s.foregroundColor = .secondary
        case .time(let dt, let remind)?: s.link = URL(string: "hkdate://d/\(dt)" + (remind ? "?r=1" : ""))
        case .mention(let ref)?: s.link = InlineStyle.mentionURL(ref)
        case nil: break
        }
        out += s
    }
    return out
}

/// AttributedString -> Inline-HTML. `baseBold`: Ueberschriften sind schon fett -> kein <b> dafuer.
func richToHTML(_ a: AttributedString, ctx: Font.Context, baseBold: Bool) -> String {
    var runs: [InlineRun] = []
    for run in a.runs {
        var text = String(a[run.range].characters)
        guard !text.isEmpty else { continue }
        var f = InlineFmt()
        let r = run.font?.resolve(in: ctx)
        f.bold = (r?.isBold ?? false) && !baseBold
        f.italic = r?.isItalic ?? false
        if run.strikethroughStyle != nil { f.strike = true }
        (f.mark, f.code) = InlineStyle.background(run.backgroundColor)
        if let u = run.link {
            switch u.scheme {
            case "hkdate": f.link = .time(String(u.path.dropFirst()), remind: u.query == "r=1")
            case "hkna": f.link = .href("/notes#" + (u.host ?? "")); text = ShareLogic.unmasked(text)   // Schloss ist nur Darstellung
            case "hkpage": f.link = .href("/notes#" + (u.host ?? ""))
            case "hkmention": f.link = InlineStyle.mentionRef(u).map { .mention($0) }
            default: f.link = .href(u.absoluteString)
            }
        } else {
            if run.underlineStyle != nil { f.underline = true }       // (Links: Unterstrich ist nur Darstellung)
        }
        if f.link.map({ if case .masked = $0 { return false }; return true }) ?? true { f.color = InlineStyle.color(run.foregroundColor) }
        if case .href(let h)? = f.link, h.hasPrefix("/notes#"), run.foregroundColor == .secondary { f.color = nil }
        if f.code == .math { f.bold = false; f.italic = false; f.underline = false; f.strike = false; f.color = nil; f.link = nil }   // Mathe: nur Text
        runs.append(InlineRun(text: text, fmt: f))
    }
    return InlineRuns.serialize(runs)
}

/// Gleicher Inhalt (Text und Formen)? Unterschiede nur in der Schreibweise (Reihenfolge der Tags, Attribute) zaehlen nicht.
func sameInline(_ a: String, _ b: String) -> Bool { a == b || InlineRuns.parse(a) == InlineRuns.parse(b) }
