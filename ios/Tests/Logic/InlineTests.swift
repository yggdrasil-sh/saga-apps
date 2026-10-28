import Foundation

// Saga 2.5 (P6-8, inline2): Inline-Formen zeichengenau (App/InlineRuns.swift) gegen die gemeinsamen Vektoren
// tests/vectors/editor_inline.json (Kopie in ios/Tests/vectors) und die Texte aus tests/vectors/merge.json; dazu
// Erwaehnungs-HTML, Kommentar-Token und die Grenzen (was die App nicht bearbeitet).
func inlineTests() {
    func load(_ name: String) -> [String: Any] {
        let p = URL(fileURLWithPath: #filePath).deletingLastPathComponent().appendingPathComponent("../vectors/" + name).standardized
        guard let d = try? Data(contentsOf: p), let j = try? JSONSerialization.jsonObject(with: d) as? [String: Any] else { check(false, "Vektoren \(name)"); return [:] }
        return j
    }
    func rt(_ h: String) -> String { InlineRuns.serialize(InlineRuns.parse(h)) }

    // MARK: Vektoren: gespeichertes HTML bleibt beim Hin und Zurueck zeichengenau
    let v = load("editor_inline.json")
    var n = 0
    for case let row as [String] in v["sanitize"] as? [Any] ?? [] {
        let out = row[2]
        guard InlineRuns.editable(out) else { check(false, "nicht bearbeitbar: \(row[0])"); continue }
        check(rt(out) == out, "Rundreise: \(row[0]) -> \(rt(out))")
        n += 1
    }
    check(n >= 20, "alle Sanitize-Vektoren (\(n))")
    for case let row as [String] in (v["publish"] as? [Any] ?? []) + (v["markdown"] as? [Any] ?? []) where !row[1].contains("<script") {
        if InlineRuns.editable(row[1]) { check(rt(row[1]) == row[1], "Rundreise (Eingabe Export): \(row[0])") }
    }
    // Texte aus den Merge-Vektoren (Farben, Erwaehnungen, Mathe, Verweise, Datum, Zeilenumbruch)
    let mv = load("merge.json")
    var texts = Set<String>()
    func collect(_ x: Any?) {
        if let d = x as? [String: Any] {
            for (k, val) in d { if ["text", "title", "caption", "content"].contains(k), let s = val as? String { texts.insert(s) } else { collect(val) } }
        } else if let a = x as? [Any] { a.forEach(collect) }
    }
    collect(mv["docs"])
    var m = 0
    for t in texts.sorted() where InlineRuns.editable(t) { check(rt(t) == t, "Merge-Text bleibt: \(t.prefix(60))"); m += 1 }
    check(m >= 20, "Merge-Texte geprueft (\(m))")

    // MARK: Formen lesen
    let both = InlineRuns.parse(#"<span class="hk-c-red"><mark class="hk-bg-blue">d</mark></span>"#)
    check(both == [InlineRun(text: "d", fmt: InlineFmt(mark: .color("blue"), color: "red"))], "Text- und Hintergrundfarbe")
    let men = InlineRuns.parse(#"x <span class="hk-mention" data-u="~abcdefghijklmnop">Anna</span>"#)
    check(men.last?.fmt.link == .mention("~abcdefghijklmnop") && men.last?.text == "Anna", "Erwaehnung gelesen")
    let math = InlineRuns.parse(#"<code class="hk-math">a&lt;b</code>"#)
    check(math == [InlineRun(text: "a<b", fmt: InlineFmt(code: .math))], "Inline-Mathe (Entities)")
    let tm = InlineRuns.parse(#"<time datetime="2026-10-10T09:00" class="hk-remind">⏰ Sa.</time>"#)
    check(tm.first?.fmt.link == .time("2026-10-10T09:00", remind: true), "Datum/Erinnerung")

    // MARK: Setzen / entfernen (Reihenfolge span aussen, mark innen)
    var runs = InlineRuns.parse("ein <b>fettes</b> Wort")
    runs[1].fmt.color = "green"; runs[1].fmt.mark = .color("yellow")
    check(InlineRuns.serialize(runs) == #"ein <span class="hk-c-green"><b><mark class="hk-bg-yellow">fettes</mark></b></span> Wort"#, "Farbe setzen: span aussen, mark innen")
    runs[1].fmt.color = nil; runs[1].fmt.mark = nil
    check(InlineRuns.serialize(runs) == "ein <b>fettes</b> Wort", "Farben entfernen")
    let ext = [InlineRun(text: "L", fmt: InlineFmt(color: "red", link: .href("https://x.example/?a=1&b=2")))]
    check(InlineRuns.serialize(ext) == #"<span class="hk-c-red"><a href="https://x.example/?a=1&amp;b=2" target="_blank" rel="noopener noreferrer">L</a></span>"#, "Verweis in Farbe, Entities im Attribut")
    let mx = [InlineRun(text: "x^2 & <y>", fmt: InlineFmt(code: .math))]
    check(InlineRuns.serialize(mx) == #"<code class="hk-math">x^2 &amp; &lt;y&gt;</code>"#, "Inline-Mathe schreiben")
    check(InlineRuns.serialize([InlineRun(text: "a\nb", fmt: InlineFmt())]) == "a<br>b", "Zeilenumbruch")

    // MARK: Erwaehnungs-HTML
    check(InlineRuns.mentionHTML(ref: "~abcdefghijklmnop", name: "Anna <B>") == #"<span class="hk-mention" data-u="~abcdefghijklmnop">Anna &lt;B&gt;</span>"#, "Erwaehnung einfuegen")
    check(InlineRuns.mentionHTML(ref: "@bob@b.example", name: "Bob") == #"<span class="hk-mention" data-u="@bob@b.example">Bob</span>"#, "Erwaehnung fremder Server")
    check(InlineRuns.validRef("~abcdefghijklmnop") && InlineRuns.validRef("@bob@b.example") && InlineRuns.validRef("@~abcdefghijklmnop@b.example"), "gueltige Kennungen")
    check(!InlineRuns.validRef("anna") && !InlineRuns.validRef("~abc") && !InlineRuns.validRef("~abc\"><script>"), "Anmeldenamen/Unsinn abgelehnt")

    // MARK: Grenzen: was die App nicht zeichengenau kann -> nicht bearbeiten (im Web)
    check(!InlineRuns.editable("<sup>2</sup>") && !InlineRuns.editable(#"<span class="hk-neu">x</span>"#), "unbekannte Tags/Klassen")
    check(!InlineRuns.editable(#"<mark class="hk-bg-blue"><code>x</code></mark>"#), "Markierung und Code an derselben Stelle")
    check(InlineRuns.editable(#"<b>a</b> <span class="hk-c-red">r</span> <code class="hk-math">x</code>"#), "Vertragsformen bearbeitbar")

    // MARK: Kommentare: Token <@REF>
    let parts = CommentMentions.parts("Schau mal <@~abcdefghijklmnop> und <@@bob@b.example>!")
    check(parts == [.text("Schau mal "), .mention("~abcdefghijklmnop"), .text(" und "), .mention("@bob@b.example"), .text("!")], "Token zerlegt")
    check(CommentMentions.display("Hi <@~abcdefghijklmnop>") { $0 == "~abcdefghijklmnop" ? "Anna" : nil } == "Hi @Anna", "Chip mit aktuellem Namen")
    check(CommentMentions.parts("<@anna> <b>").count == 1, "kein Token fuer Anmeldenamen")
    check(CommentMentions.encode("Danke @Anna Berg und @Anna", picked: [("Anna", "~aaaaaaaaaaaaaaaa"), ("Anna Berg", "~bbbbbbbbbbbbbbbb")])
          == "Danke <@~bbbbbbbbbbbbbbbb> und <@~aaaaaaaaaaaaaaaa>", "Einfuegen: laengster Name zuerst")
}
