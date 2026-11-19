import Foundation

// Bugs-DB 2200: Praesentations-Modifikatoren (.sheet, .popover, .fullScreenCover, .photosPicker) direkt an einem
// Section verteilt SwiftUI in Form/List auf jede Zeile (Lehre 2199: mehrere Blaetter auf einen Tipp, das Blatt schliesst
// sich wieder). Quelltext-Pruefung: "Heute" haengt die Editoren an der Liste, und im ganzen App-Ordner steht keiner
// dieser Modifikatoren an einem Section-Block. Bugs-DB 2197: Feld "Verbindung" folgt dem System-Farbschema.

/// Index der schliessenden Klammer zu s[i] (o/c), Zeichenketten und //-Kommentare uebersprungen; nil ohne Treffer
private func closing(_ s: [Character], _ i: Int, _ o: Character, _ c: Character) -> Int? {
    var d = 0, k = i
    while k < s.count {
        let ch = s[k]
        if ch == "\"" {                                                   // Zeichenkette ueberspringen (mit \")
            k += 1
            while k < s.count && s[k] != "\"" { if s[k] == "\\" { k += 1 }; k += 1 }
        } else if ch == "/" && k + 1 < s.count && s[k + 1] == "/" {
            while k < s.count && s[k] != "\n" { k += 1 }
            continue
        } else if ch == o { d += 1 } else if ch == c { d -= 1; if d == 0 { return k } }
        k += 1
    }
    return nil
}

private func skipSpace(_ s: [Character], _ i: Int) -> Int {
    var k = i
    while k < s.count {
        if s[k].isWhitespace { k += 1; continue }
        if s[k] == "/" && k + 1 < s.count && s[k + 1] == "/" { while k < s.count && s[k] != "\n" { k += 1 }; continue }
        if s[k] == "#" {                                                  // #if/#else/#endif-Zeilen zwischen Modifikatoren
            var e = k; while e < s.count && s[e] != "\n" { e += 1 }
            let w = String(s[k..<e])
            if w.hasPrefix("#if") || w.hasPrefix("#else") || w.hasPrefix("#elseif") || w.hasPrefix("#endif") { k = e; continue }
        }
        break
    }
    return k
}

private func ident(_ s: [Character], _ i: Int) -> (String, Int) {
    var k = i
    while k < s.count && (s[k].isLetter || s[k].isNumber || s[k] == "_") { k += 1 }
    return (String(s[i..<k]), k)
}

/// Modifikatoren, die direkt an einem `Section { … }` (mit `header:`/`footer:`) haengen, als (Zeile, Name)
func sectionModifiers(_ text: String) -> [(line: Int, name: String)] {
    let s = Array(text)
    var out: [(Int, String)] = []
    var i = 0
    while i < s.count {
        // Wortanfang "Section" (nicht .Section, nicht XyzSection)
        guard s[i] == "S", i + 7 <= s.count, String(s[i..<i + 7]) == "Section",
              i == 0 || !(s[i - 1].isLetter || s[i - 1].isNumber || s[i - 1] == "_" || s[i - 1] == "."),
              i + 7 == s.count || !(s[i + 7].isLetter || s[i + 7].isNumber || s[i + 7] == "_") else { i += 1; continue }
        let start = i
        var k = i + 7
        k = skipSpace(s, k)
        if k < s.count && s[k] == "(" { guard let e = closing(s, k, "(", ")") else { break }; k = skipSpace(s, e + 1) }
        guard k < s.count, s[k] == "{", var e = closing(s, k, "{", "}") else { i += 7; continue }
        // header: { … } / footer: { … }
        while true {
            let a = skipSpace(s, e + 1)
            let (w, b) = ident(s, a)
            guard w == "header" || w == "footer" else { break }
            let c = skipSpace(s, b)
            guard c < s.count, s[c] == ":" else { break }
            let d = skipSpace(s, c + 1)
            guard d < s.count, s[d] == "{", let f = closing(s, d, "{", "}") else { break }
            e = f
        }
        // Modifikator-Kette
        var j = e + 1
        while true {
            let a = skipSpace(s, j)
            guard a < s.count, s[a] == "." else { break }
            let (name, b) = ident(s, a + 1)
            guard !name.isEmpty else { break }
            var m = b
            if m < s.count && s[m] == "(" { guard let f = closing(s, m, "(", ")") else { break }; m = f + 1 }
            // nachgestellte Closures: { … } oder label: { … }
            while true {
                let q = skipSpace(s, m)
                if q < s.count && s[q] == "{" { guard let f = closing(s, q, "{", "}") else { break }; m = f + 1; continue }
                let (w, r) = ident(s, q)
                let t = skipSpace(s, r)
                if !w.isEmpty, t < s.count, s[t] == ":" {
                    let u = skipSpace(s, t + 1)
                    if u < s.count && s[u] == "{" { guard let f = closing(s, u, "{", "}") else { break }; m = f + 1; continue }
                }
                break
            }
            out.append((s[0..<a].filter { $0 == "\n" }.count + 1, name))
            j = m
        }
        i = start + 7
    }
    return out
}

/// Modifikatoren, die SwiftUI am Section auf die Zeilen verteilt und die dabei ein eigenes Fenster/Blatt oeffnen
let presentingModifiers: Set<String> = ["sheet", "popover", "fullScreenCover", "photosPicker"]

func presentTests() {
    // Erkenner selbst (synthetische Faelle)
    let bad = """
    List {
        Section {
            Text("a") // }
        } header: { Text("H") }
        .sheet(item: $x) { t in Editor(t) }
        Section("x") { Text("b") }
            #if os(iOS)
            .popover(isPresented: $p) { Text("{") }
            #endif
        Section { Text("c") }.alert("A", isPresented: $a) { Button("OK") {} }
            .task { await load() }
    }
    .sheet(isPresented: $ok) { Text("an der Liste") }
    """
    let found = sectionModifiers(bad)
    check(found.map(\.name) == ["sheet", "popover", "alert", "task"], "Erkenner: Ketten an Section gefunden: \(found.map(\.name))")
    check(found.filter { presentingModifiers.contains($0.name) }.map(\.line) == [5, 8], "Erkenner: Zeilen \(found.map(\.line))")
    check(sectionModifiers("struct ReadingTodaySection: View { var body: some View { Text(\"x\") }.sheet(item: $y) {} }").isEmpty,
          "Erkenner: XyzSection ist kein Section")

    guard let dir = ProcessInfo.processInfo.environment["SAGA_APP_SRC"],
          let root = try? String(contentsOfFile: dir + "/RootView.swift", encoding: .utf8),
          let files = try? FileManager.default.contentsOfDirectory(atPath: dir) else { check(false, "SAGA_APP_SRC fehlt"); return }

    // 2200: TodaySection ohne eigene Blaetter, die Editoren haengen einmal an jeder Liste mit TodaySection
    let today = root.components(separatedBy: "struct TodaySection: View").dropFirst().first?.components(separatedBy: "\n}\n").first ?? ""
    check(!today.isEmpty && !today.contains(".sheet(") && today.contains("@Binding var editingTask") && today.contains("@Binding var editingEvent"),
          "2200: TodaySection ohne eigene .sheet (Bindings statt @State)")
    let uses = root.components(separatedBy: " TodaySection(store:").count - 1        // ohne ReadingTodaySection
    let carriers = root.components(separatedBy: ".modifier(TodayEditors(").count - 1
    check(uses == 2 && carriers == uses, "2200: jede Liste mit TodaySection traegt TodayEditors genau einmal (\(uses)/\(carriers))")
    let editors = root.components(separatedBy: "struct TodayEditors: ViewModifier").dropFirst().first?.components(separatedBy: "\n}\n").first ?? ""
    check(editors.contains("TaskEditor(task:") && editors.contains("EventEditor(event:"), "2200: TodayEditors zeigt Aufgaben- und Termin-Editor")

    // ganzer App-Ordner: kein Praesentations-Modifikator an einem Section
    var hits: [String] = []
    for f in files.sorted() where f.hasSuffix(".swift") {
        guard let t = try? String(contentsOfFile: dir + "/" + f, encoding: .utf8) else { continue }
        for m in sectionModifiers(t) where presentingModifiers.contains(m.name) { hits.append("\(f):\(m.line) .\(m.name)") }
    }
    check(hits.isEmpty, "2200: kein .sheet/.popover/.fullScreenCover/.photosPicker an einem Section: \(hits)")

    // 2197: Feld "Verbindung" im Popover mit System-Schema und passender Schrift (Anruf-Fenster ist immer dunkel/weiss)
    guard let horn = try? String(contentsOfFile: dir + "/HornViews.swift", encoding: .utf8),
          let call = try? String(contentsOfFile: dir + "/CallViews.swift", encoding: .utf8) else { check(false, "HornViews/CallViews fehlt"); return }
    let pop = horn.components(separatedBy: ".popover(isPresented: $horn.ppop)").dropFirst().first?.components(separatedBy: "\n                }\n").first ?? ""
    check(pop.contains("HornPathCard(") && pop.contains(".environment(\\.colorScheme, scheme)")
          && pop.contains(".foregroundStyle(scheme == .dark ? Color.white : Color.black)"), "2197: Popover Verbindung folgt dem System-Schema")
    check(call.contains("HornInfoLine(calls: calls, horn: H, scheme: systemScheme)"), "2197: Anruf reicht das System-Schema an die Kopfzeile")
}
