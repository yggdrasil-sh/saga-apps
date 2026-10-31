import Foundation

// Saga 2.5 (chatfmt1): Markdown-light fuer Chat-Nachrichten NUR fuer die Darstellung - Nachbildung von
// static/chat-fmt.js (gleiche Regeln, gleiche Testvektoren in Tests/Logic/FixturesCS/fmt_vectors.json).
// *fett*, _kursiv_, ~durch~, `code`, ```block```, Zeilen "- " = Liste, "> " = Zitat. Ergebnis ist ein Baum aus
// Text-Knoten und festen Elementen (strong/em/s/code/ul/li/blockquote/pre) - nie Markup aus dem Text, keine Links aus
// Markdown. Adressen, Seitenverweise und Erwaehnungen erkennt erst die Darstellung in den Textstuecken (leafPieces).

indirect enum FmtNode: Equatable {
    case text(String)
    case el(String, [FmtNode])
}

enum ChatFmt {
    /// Marke nur am Wortrand (wie INL in chat-fmt.js)
    static let inlinePattern = #"(^|[\s(\[{"'])([*_~])([^\s*_~\n](?:[^\n]*?[^\s\n])?)\2(?=$|[\s)\]}"'.,;:!?])"#
    private static let inl = try! NSRegularExpression(pattern: inlinePattern)
    private static let code = try! NSRegularExpression(pattern: "`([^`\\n]+)`")
    private static let block = try! NSRegularExpression(pattern: "```(?:[a-zA-Z0-9_+-]{0,20}\\n)?([\\s\\S]*?)```")
    private static let markTag = ["*": "strong", "_": "em", "~": "s"]

    /// Arbeitsbaum wie das DOM in chat-fmt.js: Textknoten bleiben einzeln (lastChild-Regel beim Zitat)
    private final class Box {
        let tag: String
        var kids: [Kid] = []
        init(_ tag: String) { self.tag = tag }
        enum Kid { case text(String), box(Box) }
        func add(_ s: String) { kids.append(.text(s)) }
        func add(_ b: Box) { kids.append(.box(b)) }
        var node: FmtNode { .el(tag, Box.merge(kids)) }
        static func merge(_ ks: [Kid]) -> [FmtNode] {
            var out: [FmtNode] = []
            for k in ks {
                switch k {
                case .text(let s):
                    if case .text(let prev)? = out.last { out[out.count - 1] = .text(prev + s) } else { out.append(.text(s)) }
                case .box(let b): out.append(b.node)
                }
            }
            return out.filter { $0 != .text("") }
        }
    }

    private static func sub(_ s: NSString, _ r: NSRange) -> String { s.substring(with: r) }

    private static func inline(_ box: Box, _ str: String, depth: Int) {
        if str.isEmpty { return }
        let s = str as NSString, all = NSRange(location: 0, length: s.length)
        if let mc = code.firstMatch(in: str, range: all) {
            inline(box, sub(s, NSRange(location: 0, length: mc.range.location)), depth: depth)
            let c = Box("code"); c.add(sub(s, mc.range(at: 1))); box.add(c)
            let end = mc.range.location + mc.range.length
            inline(box, s.substring(from: end), depth: depth)
            return
        }
        guard depth < 4, let m = inl.firstMatch(in: str, range: all) else { box.add(str); return }
        let start = m.range.location + m.range(at: 1).length
        if start > 0 { box.add(s.substring(to: start)) }
        let w = Box(markTag[sub(s, m.range(at: 2))] ?? "strong")
        inline(w, sub(s, m.range(at: 3)), depth: depth + 1)
        box.add(w)
        inline(box, s.substring(from: m.range.location + m.range.length), depth: depth)
    }

    private static func lines(_ box: Box, _ text: String) {
        let ls = text.components(separatedBy: "\n")
        var i = 0, first = true
        func nl() { if !first { box.add("\n") }; first = false }
        while i < ls.count {
            let l = ls[i]
            if l.hasPrefix(">") {
                let q = Box("blockquote"); var qs: [String] = []
                while i < ls.count, ls[i].hasPrefix(">") {
                    var x = String(ls[i].dropFirst()); if x.hasPrefix(" ") { x.removeFirst() }
                    qs.append(x); i += 1
                }
                lines(q, qs.joined(separator: "\n"))
                if !first, case .text(let t)? = box.kids.last, t.hasSuffix("\n") { box.kids[box.kids.count - 1] = .text(String(t.dropLast())) }
                box.add(q); first = true; continue
            }
            if isListLine(l) {
                let ul = Box("ul")
                while i < ls.count, isListLine(ls[i]) { let li = Box("li"); inline(li, String(ls[i].dropFirst(2)), depth: 0); ul.add(li); i += 1 }
                box.add(ul); first = true; continue
            }
            nl(); inline(box, l, depth: 0); i += 1
        }
    }
    private static func isListLine(_ l: String) -> Bool { l.hasPrefix("- ") || l.hasPrefix("• ") }

    /// Text -> Baum (Kinder der Nachricht)
    static func render(_ text: String) -> [FmtNode] {
        let root = Box("root"), s = text as NSString
        var last = 0, from = 0
        while from <= s.length, let m = block.firstMatch(in: text, range: NSRange(location: from, length: s.length - from)) {
            var before = s.substring(with: NSRange(location: last, length: m.range.location - last))
            if before.hasSuffix("\n") { before.removeLast() }
            if !before.isEmpty { lines(root, before) }
            var c = sub(s, m.range(at: 1)); if c.hasSuffix("\n") { c.removeLast() }
            let pre = Box("pre"), cd = Box("code"); cd.add(c); pre.add(cd); root.add(pre)
            from = m.range.location + m.range.length
            last = from
            if last < s.length, s.character(at: last) == 10 { last += 1 }
        }
        if last < s.length { lines(root, s.substring(from: last)) }
        guard case .el(_, let kids) = root.node else { return [] }
        return kids
    }

    /// Fuer Vorschauen (Liste, Zitat, angeheftete Nachricht): Marken entfernen (wie strip in chat-fmt.js)
    static func strip(_ text: String) -> String {
        func rep(_ s: String, _ p: String, _ t: String, _ o: NSRegularExpression.Options = []) -> String {
            guard let re = try? NSRegularExpression(pattern: p, options: o) else { return s }
            return re.stringByReplacingMatches(in: s, range: NSRange(location: 0, length: (s as NSString).length), withTemplate: t)
        }
        var s = rep(text, "```(?:[a-zA-Z0-9_+-]{0,20}\\n)?", "")
        s = rep(s, "`([^`\\n]+)`", "$1")
        s = rep(s, inlinePattern, "$1$3")
        return rep(s, "^(> ?|[-•] )", "", .anchorsMatchLines)
    }

    // MARK: Textstuecke (Links nur http/https, Seitenverweise, Erwaehnungen) - wie TOKEN in static/chat.js

    enum Leaf: Equatable { case text(String), note(Int), url(String), mention(String) }
    static let tokenPattern = #"\[\[note:([1-9][0-9]{0,17})\]\]|(https?://[^\s<>"'`]+[^\s<>"'`.,;:!?)\]])|<@(~[a-z2-7]{16}(?:@[a-z0-9.:\-]{1,260})?)>"#
    private static let token = try! NSRegularExpression(pattern: tokenPattern)
    static func leafPieces(_ text: String) -> [Leaf] {
        let s = text as NSString
        var out: [Leaf] = [], last = 0
        for m in token.matches(in: text, range: NSRange(location: 0, length: s.length)) {
            if m.range.location > last { out.append(.text(s.substring(with: NSRange(location: last, length: m.range.location - last)))) }
            if m.range(at: 3).location != NSNotFound { out.append(.mention(sub(s, m.range(at: 3)))) }
            else if m.range(at: 1).location != NSNotFound, let id = Int(sub(s, m.range(at: 1))) { out.append(.note(id)) }
            else { out.append(.url(sub(s, m.range(at: 2)))) }
            last = m.range.location + m.range.length
        }
        if last < s.length { out.append(.text(s.substring(from: last))) }
        return out
    }

    /// Zeile der Darstellung: Absatz (Inline-Knoten), Liste, Zitat (verschachtelt), Codeblock
    enum Block: Equatable {
        case para([FmtNode])
        case list([[FmtNode]])
        case quote([Block])
        case pre(String)
    }
    /// Baum in Bloecke fuer die Darstellung (SwiftUI): Inline-Folgen werden zu Absaetzen, Zeilenumbrueche bleiben im Absatz
    static func blocks(_ nodes: [FmtNode]) -> [Block] {
        var out: [Block] = [], run: [FmtNode] = []
        func flush() {
            // fuehrende/abschliessende Zeilenumbrueche zwischen Bloecken weglassen (stehen im DOM als eigener Text)
            if !run.isEmpty, !(run.count == 1 && run[0] == .text("\n")) { out.append(.para(run)) }
            run = []
        }
        for n in nodes {
            switch n {
            case .el("ul", let lis): flush(); out.append(.list(lis.map { if case .el(_, let k) = $0 { return k }; return [$0] }))
            case .el("blockquote", let k): flush(); out.append(.quote(blocks(k)))
            case .el("pre", let k):
                flush()
                if case .el("code", let c)? = k.first, case .text(let t)? = c.first { out.append(.pre(t)) } else { out.append(.pre("")) }
            default: run.append(n)
            }
        }
        flush()
        return out
    }
}
