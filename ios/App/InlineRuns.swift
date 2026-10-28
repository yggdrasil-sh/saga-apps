import Foundation

// Saga 2.5 (P6-8, inline2): Inline-HTML <-> Abschnitte mit Formatierung - zeichengenau fuer alle Formen der
// Positivliste des Servers (b/i/u/s, mark [Gelb, cdx-marker, hk-bg-*], code [inline-code, hk-math], a, time,
// span [hk-c-*, hk-mention], br). Reine Logik ohne SwiftUI; RichText.swift bildet die Abschnitte auf den Editor ab.
// Reihenfolge beim Schreiben fest (Vertrag): Textfarbe (span) aussen, dann Verweis/Datum/Erwaehnung, b, i, u, s,
// mark, code innen. Tests: Tests/Logic/InlineTests.swift gegen tests/vectors/editor_inline.json (Server-Repo).

struct InlineFmt: Hashable {
    enum Mark: Hashable { case plain, cdx, color(String) }
    enum Code: Hashable { case plain, inline, math }
    enum Link: Hashable {
        case href(String)                    // http(s)/mailto oder /notes#12 (wie gespeichert, ohne Entities)
        case masked(String)                  // Seite ohne Zugriff (ShareLogic.markMasked): /notes#<id>
        case time(String, cls: String)       // @-Datum <time datetime="…" class="hk-date|hk-remind"> (cls "" = ohne Klasse)
        case mention(String)                 // span.hk-mention data-u
    }
    var bold = false, italic = false, underline = false, strike = false
    var mark: Mark?
    var code: Code?
    var color: String?
    var link: Link?
}

struct InlineRun: Hashable {
    var text: String
    var fmt: InlineFmt
}

enum InlineRuns {
    static let palette = ["gray", "brown", "orange", "yellow", "green", "blue", "purple", "pink", "red"]
    private static let tagRe = try! NSRegularExpression(pattern: "<(/?)([a-zA-Z]+)([^>]*)>")

    private static func attr(_ a: String, _ name: String) -> String? {
        guard let r = a.range(of: name + #"="([^"]*)""#, options: .regularExpression) else { return nil }
        return String(a[r].dropFirst(name.count + 2).dropLast())
    }
    private static func classes(_ a: String) -> [String] { (attr(a, "class") ?? "").split(separator: " ").map(String.init) }
    static func entities(_ s: String) -> String {
        guard s.contains("&") else { return s }
        var r = s
        for (k, v) in [("&nbsp;", " "), ("&lt;", "<"), ("&gt;", ">"), ("&quot;", "\""), ("&#39;", "'"), ("&#x27;", "'"), ("&amp;", "&")] { r = r.replacingOccurrences(of: k, with: v) }
        return r
    }
    static func esc(_ s: String) -> String { s.replacingOccurrences(of: "&", with: "&amp;").replacingOccurrences(of: "<", with: "&lt;").replacingOccurrences(of: ">", with: "&gt;") }
    private static func escAttr(_ s: String) -> String { esc(s).replacingOccurrences(of: "\"", with: "&quot;") }

    // MARK: Lesen

    /// Gespeichertes (bereinigtes) Inline-HTML -> Abschnitte. `na` (maskierter Verweis, ShareLogic.markMasked) wird
    /// als .masked gelesen. Zeilenumbruch <br> -> "\n".
    static func parse(_ html: String) -> [InlineRun] {
        var out: [InlineRun] = []
        var b = 0, i = 0, u = 0, s = 0
        var marks: [InlineFmt.Mark?] = [], codes: [InlineFmt.Code] = [], spans: [(color: String?, mention: String?)] = []
        var links: [InlineFmt.Link?] = []
        func fmt() -> InlineFmt {
            var f = InlineFmt()
            f.bold = b > 0; f.italic = i > 0; f.underline = u > 0; f.strike = s > 0
            f.mark = marks.last ?? nil
            f.code = codes.last
            f.color = spans.last(where: { $0.color != nil })?.color
            if let m = spans.last(where: { $0.mention != nil })?.mention { f.link = .mention(m) }
            else if let l = links.last { f.link = l }
            return f
        }
        func emit(_ raw: String) {
            guard !raw.isEmpty else { return }
            let t = entities(raw), f = fmt()
            if let last = out.last, last.fmt == f { out[out.count - 1].text += t } else { out.append(InlineRun(text: t, fmt: f)) }
        }
        let ns = html as NSString
        var pos = 0
        for m in tagRe.matches(in: html, range: NSRange(location: 0, length: ns.length)) {
            emit(ns.substring(with: NSRange(location: pos, length: m.range.location - pos)))
            pos = m.range.location + m.range.length
            let closing = ns.substring(with: m.range(at: 1)) == "/", tag = ns.substring(with: m.range(at: 2)).lowercased()
            let a = ns.substring(with: m.range(at: 3))
            let d = closing ? -1 : 1
            switch tag {
            case "b", "strong": b += d
            case "i", "em": i += d
            case "u": u += d
            case "s", "strike", "del": s += d
            case "mark":
                if closing { _ = marks.popLast() } else {
                    let c = classes(a)
                    if let bg = c.first(where: { $0.hasPrefix("hk-bg-") && palette.contains(String($0.dropFirst(6))) }) { marks.append(.color(String(bg.dropFirst(6)))) }
                    else { marks.append(c.contains("cdx-marker") ? .cdx : .plain) }
                }
            case "code":
                if closing { _ = codes.popLast() } else {
                    let c = classes(a)
                    codes.append(c.contains("hk-math") ? .math : c.contains("inline-code") ? .inline : .plain)
                }
            case "span":
                if closing { _ = spans.popLast() } else {
                    let c = classes(a)
                    if c.contains("hk-mention"), let ref = attr(a, "data-u") { spans.append((nil, entities(ref))) }
                    else { spans.append((c.first { $0.hasPrefix("hk-c-") && palette.contains(String($0.dropFirst(5))) }.map { String($0.dropFirst(5)) }, nil)) }
                }
            case "a", "na":
                if closing { _ = links.popLast() } else {
                    let href = entities(attr(a, "href") ?? "")
                    links.append(href.isEmpty ? nil : tag == "na" ? .masked(href) : .href(href))
                }
            case "time":
                if closing { _ = links.popLast() } else {
                    let dt = attr(a, "datetime") ?? ""
                    let c = classes(a)
                    links.append(dt.isEmpty ? nil : .time(dt, cls: c.contains("hk-remind") ? "hk-remind" : c.contains("hk-date") ? "hk-date" : ""))
                }
            case "br": if !closing { emit("\n") }
            default: break
            }
        }
        emit(ns.substring(from: pos))
        return out
    }

    /// Kann der App-Editor diesen Text zeichengenau bearbeiten? Nein bei unbekannten Tags/Klassen (neuere Formen) und
    /// bei Markierung und Code an derselben Stelle (der Editor kennt je Zeichen nur einen Hintergrund).
    static func editable(_ html: String) -> Bool {
        guard html.contains("<") else { return true }
        let ns = html as NSString
        let known: Set<String> = ["b", "strong", "i", "em", "u", "s", "strike", "del", "mark", "code", "a", "br", "time", "span"]
        for m in tagRe.matches(in: html, range: NSRange(location: 0, length: ns.length)) where ns.substring(with: m.range(at: 1)) != "/" {
            let tag = ns.substring(with: m.range(at: 2)).lowercased(), a = ns.substring(with: m.range(at: 3))
            guard known.contains(tag) else { return false }
            let c = classes(a)
            switch tag {
            case "span":
                let ok = (c == ["hk-mention"] && attr(a, "data-u") != nil) || (c.count == 1 && c[0].hasPrefix("hk-c-") && palette.contains(String(c[0].dropFirst(5))))
                if !ok { return false }
            case "mark":
                if !(c.isEmpty || c == ["cdx-marker"] || (c.count == 1 && c[0].hasPrefix("hk-bg-") && palette.contains(String(c[0].dropFirst(6))))) { return false }
            case "code":
                if !(c.isEmpty || c == ["inline-code"] || c == ["hk-math"]) { return false }
            default: break
            }
        }
        return !parse(html).contains { $0.fmt.mark != nil && $0.fmt.code != nil }
    }

    // MARK: Schreiben

    /// Abschnitte -> Inline-HTML in fester Reihenfolge; gleiche aeussere Elemente werden ueber Abschnitte hinweg
    /// zusammengefasst (<span class="hk-c-green">a <b>b</b> c</span>). In Inline-Mathe nur Text.
    static func serialize(_ runs: [InlineRun]) -> String {
        var html = ""
        var stack: [(key: String, close: String)] = []
        for r in runs where !r.text.isEmpty {
            let want = tags(r.fmt)
            var keep = 0
            while keep < stack.count && keep < want.count && stack[keep].key == want[keep].key { keep += 1 }
            while stack.count > keep { html += stack.removeLast().close }
            for t in want[keep...] { html += t.open; stack.append((t.key, t.close)) }
            html += esc(r.text).replacingOccurrences(of: "\n", with: "<br>")
        }
        while let t = stack.popLast() { html += t.close }
        return html
    }

    private static func tags(_ f: InlineFmt) -> [(key: String, open: String, close: String)] {
        var t: [(String, String, String)] = []
        if let c = f.color, palette.contains(c) { t.append(("c:" + c, "<span class=\"hk-c-\(c)\">", "</span>")) }
        switch f.link {
        case .href(let h)?, .masked(let h)?:
            let ext = h.hasPrefix("http://") || h.hasPrefix("https://") || h.hasPrefix("mailto:")
            t.append(("a:" + h, "<a href=\"\(escAttr(h))\"" + (ext ? " target=\"_blank\" rel=\"noopener noreferrer\"" : "") + ">", "</a>"))
        case .time(let dt, let cls)?:
            t.append(("t:" + dt + cls, "<time datetime=\"\(escAttr(dt))\"" + (cls.isEmpty ? "" : " class=\"\(cls)\"") + ">", "</time>"))
        case .mention(let ref)?:
            t.append(("m:" + ref, "<span class=\"hk-mention\" data-u=\"\(escAttr(ref))\">", "</span>"))
        case nil: break
        }
        if f.bold { t.append(("b", "<b>", "</b>")) }
        if f.italic { t.append(("i", "<i>", "</i>")) }
        if f.underline { t.append(("u", "<u>", "</u>")) }
        if f.strike { t.append(("s", "<s>", "</s>")) }
        switch f.mark {
        case .plain?: t.append(("mk", "<mark>", "</mark>"))
        case .cdx?: t.append(("mk:cdx", "<mark class=\"cdx-marker\">", "</mark>"))
        case .color(let c)?: t.append(("mk:" + c, "<mark class=\"hk-bg-\(c)\">", "</mark>"))
        case nil: break
        }
        switch f.code {
        case .plain?: t.append(("cd", "<code>", "</code>"))
        case .inline?: t.append(("cd:i", "<code class=\"inline-code\">", "</code>"))
        case .math?: t.append(("cd:m", "<code class=\"hk-math\">", "</code>"))
        case nil: break
        }
        return t.map { (key: $0.0, open: $0.1, close: $0.2) }
    }

    // MARK: Erwaehnung (P6-5)

    /// HTML einer Erwaehnung: data-u = ref (Handle/Prinzipal), Text = Name (nur Rueckfall)
    static func mentionHTML(ref: String, name: String) -> String {
        serialize([InlineRun(text: name, fmt: InlineFmt(link: .mention(ref)))])
    }
    /// Gueltige Kennung (wie der Server): Handle ~ + 16 Zeichen a-z2-7 oder Prinzipal @name@host / @~handle@host
    static func validRef(_ r: String) -> Bool {
        r.range(of: #"^(~[a-z2-7]{16}|@(?:[a-z0-9][a-z0-9_-]{1,31}|~[a-z2-7]{16})@[a-z0-9.:-]{1,260})$"#, options: .regularExpression) != nil
    }
}

// MARK: - Kommentare: Erwaehnungs-Token <@REF>

enum CommentMentions {
    private static let re = try! NSRegularExpression(pattern: #"<@(~[a-z2-7]{16}|@(?:[a-z0-9][a-z0-9_-]{1,31}|~[a-z2-7]{16})@[a-z0-9.:-]{1,260})>"#)
    /// Text in Stuecke zerlegen: Klartext bzw. Erwaehnung (ref)
    enum Part: Equatable { case text(String), mention(String) }
    static func parts(_ body: String) -> [Part] {
        let ns = body as NSString
        var out: [Part] = [], pos = 0
        for m in re.matches(in: body, range: NSRange(location: 0, length: ns.length)) {
            if m.range.location > pos { out.append(.text(ns.substring(with: NSRange(location: pos, length: m.range.location - pos)))) }
            out.append(.mention(ns.substring(with: m.range(at: 1))))
            pos = m.range.location + m.range.length
        }
        if pos < ns.length { out.append(.text(ns.substring(from: pos))) }
        return out
    }
    static func token(_ ref: String) -> String { "<@\(ref)>" }
    /// Anzeige "@Name" (aktueller Name aus people; unbekannt: Kennung ohne Host-Teil nicht raten -> "@?")
    static func display(_ body: String, name: (String) -> String?) -> String {
        parts(body).map { p in
            switch p { case .text(let t): return t; case .mention(let r): return "@" + (name(r) ?? String(localized: "Unbekannte Person")) }
        }.joined()
    }
    /// Eingabe mit gewaehlten Personen ("@Anna" -> <@~…>) in den gespeicherten Text: nur genau die eingefuegten Namen
    static func encode(_ text: String, picked: [(name: String, ref: String)]) -> String {
        var s = text
        for p in picked.sorted(by: { $0.name.count > $1.name.count }) {
            s = s.replacingOccurrences(of: "@" + p.name, with: token(p.ref))
        }
        return s
    }
}
