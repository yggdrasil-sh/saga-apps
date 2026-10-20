import Foundation

// F4/N1/N2 (Testrunde 2.4): Eingabefluss des Editors - kein Tastendruck geht verloren

/// Nachbildung des Seiten-Editors fuer Tippfolgen: Bloecke, Ziel-Fokus (ed.focus), Feld mit der Tastatur
/// (wandert mit Verzoegerung), mehrere Tastendruecke in einem onChange, Weiterreichen, Aktionen nach dem Fokus.
/// Die Schritte entsprechen EditText.handle / takeCarry / doReturn und ListBlock.returnIn.
final class SimEditor {
    struct Block: Equatable { var id: Int; var list: Bool; var text: String }
    struct Field { var ui: String; var caret: Int; var shadow: String; var handover: EditorFlow.Handover? }
    var blocks: [Block]
    var focus: Int?
    var responder: Int?
    var fields: [Int: Field] = [:]
    var carry: [Int: String] = [:]
    var caretStart = Set<Int>()
    var deferred: [(Int, () -> Void)] = []
    var pending: (id: Int, after: Int)?
    var lost = 0
    private var nextID = 100
    private var unflushed = 0
    let lag: Int, coalesce: Int, removeImmediately: Bool

    init(_ blocks: [Block], focus id: Int, lag: Int, coalesce: Int, removeImmediately: Bool = false) {
        self.blocks = blocks; self.lag = lag; self.coalesce = coalesce; self.removeImmediately = removeImmediately
        self.focus = id; appear(id); responder = id
    }
    func idx(_ id: Int) -> Int? { blocks.firstIndex { $0.id == id } }

    // Feld erscheint (onAppear): Text laden, Cursor, Weitergereichtes uebernehmen
    func appear(_ id: Int) {
        guard let i = idx(id) else { return }
        let t = blocks[i].text
        fields[id] = Field(ui: t, caret: caretStart.remove(id) != nil ? 0 : t.count, shadow: t, handover: nil)
        takeCarry(id)
    }
    func setFocus(_ id: Int) {
        focus = id
        if fields[id] == nil { appear(id) }
        if lag == 0 { acquire(id) } else { pending = (id, lag) }
    }
    func acquire(_ id: Int) {
        if let r = responder, r != id { fields[r]?.handover = nil; fields[r] = nil }   // altes Feld zeigt wieder statischen Text
        responder = id
        takeCarry(id)
        let due = deferred.filter { $0.0 == id }; deferred.removeAll { $0.0 == id }
        due.forEach { $0.1() }
    }
    func forward(_ s: String, to t: Int) {
        guard !s.isEmpty else { return }
        carry[t, default: ""] += s
        if fields[t] != nil { takeCarry(t) }
    }
    // ListBlock.returnIn / BlockView.split
    func onReturn(_ id: Int, before: String, after: String) {
        guard let i = idx(id) else { return }
        blocks[i].text = before
        nextID += 1
        if blocks[i].list && before.isEmpty && after.isEmpty {
            var j = i; while j + 1 < blocks.count && blocks[j + 1].list { j += 1 }
            let p = Block(id: nextID, list: false, text: "")
            blocks.insert(p, at: j + 1)
            let remove = { [unowned self] in if let k = self.idx(id) { self.blocks.remove(at: k); if self.responder == id { self.responder = nil } } }
            if removeImmediately { remove(); fields[id] = nil } else { deferred.append((p.id, remove)) }
            setFocus(p.id)
        } else {
            blocks.insert(Block(id: nextID, list: blocks[i].list, text: after), at: i + 1)
            setFocus(nextID)
        }
    }
    func doReturn(_ id: Int, before: String, after: String, rest: String) {
        onReturn(id, before: before, after: after)
        // In der App erscheint das neue Feld erst nach diesem Schritt (naechstes Zeichnen) und liest caretStart;
        // hier ist es schon da -> Cursor direkt an den Anfang
        if let t = focus, t != id { if fields[t] != nil { fields[t]!.caret = 0 } else { caretStart.insert(t) }; forward(rest, to: t) }
    }
    func takeCarry(_ id: Int) {
        guard focus == id, var f = fields[id], let c = carry.removeValue(forKey: id), !c.isEmpty else { return }
        let a = EditorFlow.apply(carry: c, caret: f.caret, canSplit: true)
        let r = EditorFlow.result(f.ui, caret: f.caret, a)
        f.ui = r.text; f.caret = r.caret; f.shadow = r.text
        if a.split {
            let before = String(r.text.prefix(r.caret)), after = String(r.text.dropFirst(r.caret))
            f.ui = before; f.shadow = before; fields[id] = f
            doReturn(id, before: before, after: after, rest: a.rest)
        } else {
            fields[id] = f
            if let i = idx(id) { blocks[i].text = r.text }
        }
    }
    // EditText.handle (onChange)
    func change(_ id: Int) {
        guard var f = fields[id] else { return }
        let n = f.ui
        if focus != id, focus != nil {
            var h = f.handover ?? EditorFlow.Handover(base: f.shadow)
            let out = EditorFlow.handover(&h, new: n)
            f.handover = h; fields[id] = f
            if let t = focus { forward(out, to: t) }
            return
        }
        switch EditorFlow.targetStep(shadow: f.shadow, new: n, canSplit: true, slashPick: { _ in false }) {
        case .none, .slashPick: break
        case .text:
            f.shadow = n; fields[id] = f
            if let i = idx(id) { blocks[i].text = n }
        case .split(let at, let from, let rest):
            f.shadow = n; f.handover = EditorFlow.Handover(base: n); fields[id] = f
            doReturn(id, before: String(n.prefix(at)), after: String(n.dropFirst(from)), rest: rest)
        }
    }
    func key(_ ch: Character) {
        guard let r = responder, var f = fields[r] else { lost += 1; return }
        var u = Array(f.ui)
        if ch == EditorFlow.backspace { if f.caret > 0 { u.remove(at: f.caret - 1); f.caret -= 1 } }
        else { u.insert(ch, at: f.caret); f.caret += 1 }
        f.ui = String(u); fields[r] = f
        unflushed += 1
        if unflushed >= coalesce { flush() }
        if let p = pending {
            if p.after <= 1 {
                flush()
                // hat das Verbuchen schon ein neueres Ziel gesetzt, bekommt das alte die Tastatur nie (wie in der App)
                if let q = pending, q.id == p.id { pending = nil; acquire(p.id) }
            } else { pending = (p.id, p.after - 1) }
        }
    }
    func flush() { unflushed = 0; if let r = responder { change(r) } }
    func type(_ s: String) { for ch in s { key(ch) }; flush(); if let p = pending { pending = nil; acquire(p.id) } }
    var texts: [String] { blocks.map { ($0.list ? "☐ " : "") + $0.text } }
}

func editorFlowTests() {
    // Bausteine
    check(EditorFlow.delta("Mittel", "Mittel:\n") == .init(prefix: 6, removed: 0, inserted: ":\n", suffix: 0), "Aenderung am Ende")
    check(EditorFlow.delta("abcdef", "abcXdef") == .init(prefix: 3, removed: 0, inserted: "X", suffix: 3), "Aenderung in der Mitte")
    check(EditorFlow.delta("abc", "ab") == .init(prefix: 2, removed: 1, inserted: "", suffix: 0), "Loeschen")
    // Ursache 1: mehrere Tasten in einem onChange - getrennt wird AM Zeilenumbruch, nicht am Anfang der Aenderung
    check(EditorFlow.targetStep(shadow: "Mittel", new: "Mittel:\n", canSplit: true, slashPick: { _ in false }) == .split(at: 7, afterFrom: 8, rest: ""), "\":⏎\" zusammen: Doppelpunkt bleibt")
    check(EditorFlow.targetStep(shadow: "Punkt", new: "Punkt C\n\n", canSplit: true, slashPick: { _ in false }) == .split(at: 7, afterFrom: 9, rest: "\n"), "zweites Return geht weiter")
    check(EditorFlow.targetStep(shadow: "abcdef", new: "abc\nXdef", canSplit: true, slashPick: { _ in false }) == .split(at: 3, afterFrom: 5, rest: "X"), "Return mitten im Text: Getipptes vor den Rest")
    check(EditorFlow.targetStep(shadow: "ab", new: "ab\n", canSplit: false, slashPick: { _ in false }) == .text, "ohne Trennen: Umbruch bleibt im Text")
    check(EditorFlow.targetStep(shadow: "ab", new: "ab", canSplit: true, slashPick: { _ in false }) == .none, "eigenes Echo: nichts")
    let cmd: (String) -> Bool = { SlashFilter.query($0).map { ["seite", "sei", "checkliste"].contains($0) } == true }
    check(EditorFlow.targetStep(shadow: "/seite", new: "/seite\n", canSplit: true, slashPick: cmd) == .slashPick(line: "/seite", rest: ""), "Return im \"/\"-Menue waehlt")
    check(EditorFlow.targetStep(shadow: "/sei", new: "/sei\nX", canSplit: true, slashPick: cmd) == .slashPick(line: "/sei", rest: "X"), "Weitergetipptes nach der Wahl")
    // Tipptest int2: "e⏎" schnell in EINER Aenderung - die Zeile vor dem Return zaehlt, nicht der alte Schatten
    check(EditorFlow.targetStep(shadow: "/Seit", new: "/Seite\n", canSplit: true, slashPick: { $0 == "/Seite" }) == .slashPick(line: "/Seite", rest: ""), "\"e⏎\" zusammen waehlt")
    check(EditorFlow.targetStep(shadow: "", new: "/Checkliste\neins", canSplit: true, slashPick: { $0 == "/Checkliste" }) == .slashPick(line: "/Checkliste", rest: "eins"), "ganzer Befehl in einem onChange")
    check(EditorFlow.targetStep(shadow: "abc", new: "abc\n/Seite", canSplit: true, slashPick: cmd) == .split(at: 3, afterFrom: 10, rest: "/Seite"), "Text vor dem Return ist kein Befehl: trennen")
    check(EditorFlow.targetStep(shadow: "/seite x", new: "/seite x\n", canSplit: true, slashPick: cmd) == .split(at: 8, afterFrom: 9, rest: ""), "mit Leerzeichen kein Befehl")
    // weitergereichtes "/Seite⏎" (Feld bekam den Fokus erst danach): waehlen statt trennen
    let apS = EditorFlow.apply(carry: "/Seite\nX", caret: 0, canSplit: true)
    check(EditorFlow.carrySlashLine(text: "", caret: 0, apS, slashPick: { $0 == "/Seite" }) == "/Seite" && apS.rest == "X", "Weitergereicht: Befehl erkannt, Rest bleibt")
    check(EditorFlow.carrySlashLine(text: "ab", caret: 2, EditorFlow.apply(carry: "/Seite\n", caret: 2, canSplit: true), slashPick: { _ in true }) == nil, "Text davor: kein Befehl")
    check(EditorFlow.carrySlashLine(text: "", caret: 0, EditorFlow.apply(carry: "/Seite", caret: 0, canSplit: true), slashPick: { _ in true }) == nil, "ohne Return: nichts waehlen")
    // Ursache 2: Handover rechnet gegen den festen Stand - egal, ob Tasten einzeln oder zusammen ankommen
    var h = EditorFlow.Handover(base: "Punkt A")
    check(EditorFlow.handover(&h, new: "Punkt AP") == "P", "erstes Zeichen")
    check(EditorFlow.handover(&h, new: "Punkt APu") == "u", "nur Neues")
    check(EditorFlow.handover(&h, new: "Punkt APu") == "", "Wiederholung: nichts doppelt")
    check(EditorFlow.handover(&h, new: "Punkt APunkt\n") == "nkt\n", "mehrere auf einmal, mit Return")
    check(EditorFlow.handover(&h, new: "Punkt APunkt") == "\u{8}", "Rueckschritt")
    var h2 = EditorFlow.Handover(base: "ab")
    check(EditorFlow.handover(&h2, new: "a") == "\u{8}", "Rueckschritt in den alten Text")
    check(EditorFlow.handover(&h2, new: "aX") == "X", "danach weiter")
    // Einspielen
    let ap = EditorFlow.apply(carry: "unkt B\nPunkt C", caret: 1, canSplit: true)
    check(ap == .init(removedBefore: 0, inserted: "unkt B", split: true, rest: "Punkt C"), "bis zum Return, Rest weiter")
    let rp = EditorFlow.result("P", caret: 1, ap)
    check(rp.text == "Punkt B" && rp.caret == 7, "Text nach dem Einspielen")
    let bs = EditorFlow.apply(carry: "xy\u{8}\u{8}\u{8}z", caret: 2, canSplit: true)
    check(EditorFlow.result("ab", caret: 2, bs).text == "az", "Rueckschritte im Weitergereichten und davor")
    check(SlashFilter.query("/Seite") == "seite" && SlashFilter.query("/a b") == nil && SlashFilter.query("x") == nil, "\"/\"-Suchtext")

    // Ganze Tippfolgen wie im Testbericht (041/042/043): jede Verzoegerung des Fokuswechsels, Tasten einzeln oder gebuendelt
    func run(_ start: [SimEditor.Block], focus: Int, _ input: String, lag: Int, coalesce: Int, removeImmediately: Bool = false) -> SimEditor {
        let s = SimEditor(start, focus: focus, lag: lag, coalesce: coalesce, removeImmediately: removeImmediately)
        s.type(input); return s
    }
    var all = true
    for lag in 0...6 {
        for co in 1...4 {
            let a = run([.init(id: 1, list: true, text: "")], focus: 1, "Punkt A\nPunkt B\nPunkt C\n\nNach der Liste", lag: lag, coalesce: co)
            let ok1 = a.texts == ["☐ Punkt A", "☐ Punkt B", "☐ Punkt C", "Nach der Liste"] && a.lost == 0
            let b = run([.init(id: 1, list: false, text: "Schnell")], focus: 1, ":\nMittel:\nEnde", lag: lag, coalesce: co)
            let ok2 = b.texts == ["Schnell:", "Mittel:", "Ende"] && b.lost == 0
            let c = run([.init(id: 1, list: true, text: "Kirsche drei")], focus: 1, "\n\nWeiter nach der Liste", lag: lag, coalesce: co)
            let ok3 = c.texts == ["☐ Kirsche drei", "Weiter nach der Liste"] && c.lost == 0
            let d = run([.init(id: 1, list: true, text: "")], focus: 1, "Px\u{8}unkt A\nPunkt Bx\u{8}\n", lag: lag, coalesce: co)
            let ok4 = d.texts == ["☐ Punkt A", "☐ Punkt B", "☐ "] && d.lost == 0
            if !(ok1 && ok2 && ok3 && ok4) {
                all = false
                print("  Verzoegerung \(lag), je \(co): \(a.texts) | \(b.texts) | \(c.texts) | \(d.texts)")
            }
        }
    }
    check(all, "schnelle Tippfolgen (Liste, Absatz, Liste verlassen, Rueckschritt) ohne Verlust - Verzoegerung 0-6, Buendel 1-4")
    // Return mitten im Absatz: Getipptes landet VOR dem abgetrennten Rest
    let m = SimEditor([.init(id: 1, list: false, text: "abcdef")], focus: 1, lag: 3, coalesce: 2)
    m.fields[1]!.caret = 3
    m.type("\nXY")
    check(m.texts == ["abc", "XYdef"], "Return in der Mitte: \(m.texts)")
    // Liste verlassen, nachdem der leere Punkt schon die Tastatur hatte: leerer Punkt verschwindet erst nach dem Fokus
    let seq = "\nab\u{8}\u{8}\nWeiter"
    let neu = run([.init(id: 1, list: true, text: "Kirsche drei")], focus: 1, seq, lag: 2, coalesce: 1)
    check(neu.texts == ["☐ Kirsche drei", "Weiter"] && neu.lost == 0, "Liste verlassen ohne Verlust: \(neu.texts) \(neu.lost)")
    // Gegenprobe: fokussiertes Feld sofort entfernen (alter Ablauf) verliert Tasten
    let old = run([.init(id: 1, list: true, text: "Kirsche drei")], focus: 1, seq, lag: 2, coalesce: 1, removeImmediately: true)
    check(old.lost > 0, "Gegenprobe: sofortiges Entfernen verliert Tasten (\(old.lost))")

    // Kleinkram der Runde
    check(DBFilterBadge.count(["filters": [["pid": "a", "op": "is", "value": "x"], ["pid": "b", "op": "contains", "value": ""], ["pid": "c", "op": "is_empty"]]]) == 2, "aktive Filter zaehlen")
    check(DBFilterBadge.count(nil) == 0 && DBFilterBadge.count(["filters": [] as [Any]]) == 0, "ohne Filter")
    check(ShareSearchHint.tooShort("jo") && !ShareSearchHint.tooShort("jon") && ShareSearchHint.tooShort(" j "), "Personensuche: 1-2 Zeichen")
    check(ChatLayout.listWidth(total: 700) == 320 && ChatLayout.listWidth(total: 1000) == 360 && ChatLayout.listWidth(total: 2000) == 420, "Breite der Chatliste")
}
