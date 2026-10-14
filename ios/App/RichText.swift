import SwiftUI

// MARK: - Gespeichertes Inline-HTML <-> formatierter Text (zum direkten Bearbeiten, ohne Zeichen)

let hkMarkColor = Color.yellow.opacity(0.35)
let hkCodeColor = Color.gray.opacity(0.22)

/// HTML -> AttributedString fuer den Editor. Fett/kursiv/Code ueber die Schrift (so bearbeitet sie der Editor),
/// unterstrichen/durchgestrichen/markiert/Link ueber eigene Attribute.
func richText(_ stored: String, base: Font) -> AttributedString {
    // Verweis auf eine Seite ohne Zugriff (1.3): auch beim Bearbeiten mit 🔒, gedaempft und nicht als Link;
    // intern hkna://<id>, beim Speichern wieder genau <a href="/notes#id">Text</a> (richToHTML)
    let html = ShareLogic.markMasked(stored, keepRef: true)
    var out = AttributedString()
    var bold = 0, italic = 0, under = 0, strike = 0, mark = 0, code = 0, na = 0
    var links: [URL?] = []
    let re = hkTagRegex
    let ns = html as NSString
    var pos = 0
    func emit(_ raw: String) {
        guard !raw.isEmpty else { return }
        var s = AttributedString(decodeEntities(raw))
        var f = code > 0 ? base.monospaced() : base
        if bold > 0 { f = f.bold() }
        if italic > 0 { f = f.italic() }
        s.font = f
        if under > 0 { s.underlineStyle = .single }
        if strike > 0 { s.strikethroughStyle = .single }
        if code > 0 { s.backgroundColor = hkCodeColor } else if mark > 0 { s.backgroundColor = hkMarkColor }
        if let l = links.last, let u = l { s.link = u }
        if na > 0 { s.foregroundColor = .secondary }
        out += s
    }
    for m in re.matches(in: html, range: NSRange(location: 0, length: ns.length)) {
        emit(ns.substring(with: NSRange(location: pos, length: m.range.location - pos)))
        pos = m.range.location + m.range.length
        let closing = ns.substring(with: m.range(at: 1)) == "/", tag = ns.substring(with: m.range(at: 2)).lowercased()
        let d = closing ? -1 : 1
        switch tag {
        case "b", "strong": bold += d
        case "i", "em": italic += d
        case "u": under += d
        case "s", "strike", "del": strike += d
        case "mark": mark += d
        case "code": code += d
        case "br": if !closing { var nl = AttributedString("\n"); nl.font = base; out += nl }
        case "na":
            na += d
            if closing { _ = links.popLast() } else {
                let attrs = ns.substring(with: m.range(at: 3))
                let id = attrs.range(of: #"/notes#[0-9]+"#, options: .regularExpression).map { String(attrs[$0].dropFirst(7)) } ?? ""
                links.append(URL(string: "hkna://" + id))
            }
        case "time":                       // @-Datum aus dem Web: als eigener Link (hkdate://), beim Speichern wieder <time>
            if closing { _ = links.popLast() } else {
                let attrs = ns.substring(with: m.range(at: 3))
                let dt = attrs.range(of: #"datetime="([0-9T:\-]{10,16})""#, options: .regularExpression).map { String(attrs[$0].dropFirst(10).dropLast()) } ?? ""
                links.append(dt.isEmpty ? nil : URL(string: "hkdate://d/\(dt)" + (attrs.contains("hk-remind") ? "?r=1" : "")))
            }
        case "a":
            if closing { _ = links.popLast() } else {
                let attrs = ns.substring(with: m.range(at: 3))
                let href = attrs.range(of: #"href="([^"]*)""#, options: .regularExpression).map { String(attrs[$0].dropFirst(6).dropLast()) } ?? ""
                links.append(linkURL(decodeEntities(href)))
            }
        default: break
        }
    }
    emit(ns.substring(from: pos))
    return out
}

private func esc(_ s: String) -> String { s.replacingOccurrences(of: "&", with: "&amp;").replacingOccurrences(of: "<", with: "&lt;").replacingOccurrences(of: ">", with: "&gt;") }

/// AttributedString -> Inline-HTML. `baseBold`: Ueberschriften sind schon fett -> kein <b> dafuer.
func richToHTML(_ a: AttributedString, ctx: Font.Context, baseBold: Bool) -> String {
    var html = ""
    for run in a.runs {
        var text = String(a[run.range].characters)
        guard !text.isEmpty else { continue }
        if let u = run.link, u.scheme == "hkna" { text = ShareLogic.unmasked(text) }    // Schloss ist nur Darstellung
        let r = run.font?.resolve(in: ctx)
        let isBold = (r?.isBold ?? false) && !baseBold, isItalic = r?.isItalic ?? false
        let isCode = run.backgroundColor == hkCodeColor
        let isMark = run.backgroundColor == hkMarkColor
        var open = "", close = ""
        func wrap(_ tag: String) { open += "<\(tag)>"; close = "</\(tag)>" + close }
        if let u = run.link, u.scheme == "hkdate" {
            let dt = String(u.path.dropFirst()), remind = u.query == "r=1"
            open += "<time datetime=\"\(esc(dt))\" class=\"\(remind ? "hk-remind" : "hk-date")\">"; close = "</time>" + close
        } else if let u = run.link {
            let href = u.scheme == "hkpage" || u.scheme == "hkna" ? "/notes#" + (u.host ?? "") : u.absoluteString
            open += "<a href=\"\(esc(href).replacingOccurrences(of: "\"", with: "&quot;"))\">"; close = "</a>" + close
        }
        if isBold { wrap("b") }
        if isItalic { wrap("i") }
        if run.underlineStyle != nil && run.link == nil { wrap("u") }   // (hkdate-Links: kein <u>)
        if run.strikethroughStyle != nil { wrap("s") }
        if isMark { wrap("mark") }
        if isCode { wrap("code") }
        html += open + esc(text).replacingOccurrences(of: "\n", with: "<br>") + close
    }
    // aneinanderstossende gleiche Tags zusammenfassen (</b><b> -> nichts)
    for t in ["b", "i", "u", "s", "mark", "code"] { html = html.replacingOccurrences(of: "</\(t)><\(t)>", with: "") }
    return html
}
