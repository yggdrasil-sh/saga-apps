import Foundation

// Reine Logik zu den Funden des Apple-Tests 2.4 (ohne SwiftUI, mit swiftc pruefbar: Tests/Logic/AppleFixTests.swift)

/// Papierkorb: Eintraege lesen und Rueckfrage vor dem endgueltigen Loeschen
enum TrashLogic {
    struct Item: Equatable, Identifiable {
        var id: Int
        var title: String
        var icon: String?
        var parent: String                        // Titel der Elternseite (leer: oberste Ebene oder unbekannt)
        var subpages: Int
        var purgeAt: Double?
    }
    static func item(_ d: [String: Any], parentLabel: (Int) -> String? = { _ in nil }) -> Item {
        let pid = d["parent_id"] as? Int
        return Item(id: d["id"] as? Int ?? 0,
                    title: (d["title"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines),
                    icon: (d["icon"] as? String).flatMap { $0.isEmpty ? nil : $0 },
                    parent: pid.flatMap(parentLabel) ?? "",
                    subpages: max(0, d["subpages"] as? Int ?? 0),
                    purgeAt: (d["purge_at"] as? Double) ?? (d["purge_at"] as? Int).map(Double.init))
    }
    /// Text der Rueckfrage; nennt Titel und Unterseiten, damit klar ist, was verschwindet
    static func purgeQuestion(_ it: Item?) -> String {
        guard let it else { return "" }
        let t = it.title.isEmpty ? String(localized: "Unbenannt") : it.title
        if it.subpages > 0 { return String(localized: "„\(t)“ mit Unterseiten (\(it.subpages)) endgültig löschen?") }
        return String(localized: "„\(t)“ endgültig löschen?")
    }
}

/// Quelltext-Pruefung: mehrere Knoepfe in einer List-/Form-Zeile ohne eigenen Stil. iOS loest dann bei einem Tipp
/// alle Knoepfe der Zeile aus (F1 Papierkorb). Erlaubt, wenn jeder Knopf einen Stil hat oder der Container
/// .rowButtons() / .buttonStyle(.borderless|.plain|.bordered...) traegt.
enum RowButtonLint {
    struct Finding: Equatable { let file: String; let line: Int }

    private static func blockEnd(_ s: [Character], _ start: Int) -> Int {
        var d = 1, i = start
        while d > 0 && i < s.count { if s[i] == "{" { d += 1 } else if s[i] == "}" { d -= 1 }; i += 1 }
        return i
    }
    private static func matches(_ pattern: String, _ text: String) -> [NSRange] {
        guard let re = try? NSRegularExpression(pattern: pattern) else { return [] }
        return re.matches(in: text, range: NSRange(text.startIndex..., in: text)).map(\.range)
    }
    /// Offset in Zeichen (Character) aus einer UTF-16-Position
    private static func charIndex(_ text: String, _ utf16: Int) -> Int {
        text.distance(from: text.startIndex, to: String.Index(utf16Offset: utf16, in: text))
    }

    static func findings(file: String, source text: String) -> [Finding] {
        let s = Array(text)
        // Container: List, Form, Section (jede direkte Zeile darin ist eine Listenzeile)
        var conts: [(Int, Int)] = []
        for r in matches(#"\b(List|Form|Section)\b[^{\n]*\{"#, text) {
            let a = charIndex(text, r.location), b = charIndex(text, r.location + r.length)
            conts.append((a, blockEnd(s, b)))
        }
        guard !conts.isEmpty else { return [] }
        // Alle Stapel; solche mit Stil am Container (.rowButtons() o. ae.) decken ihre Knoepfe ab
        var stacks: [(start: Int, end: Int, styled: Bool)] = []
        for r in matches(#"\b(HStack|VStack|LabeledContent|ViewThatFits)\b[^{\n]*\{"#, text) {
            let a = charIndex(text, r.location), b = charIndex(text, r.location + r.length)
            let e = blockEnd(s, b)
            stacks.append((a, e, containerStyled(String(s[e..<min(s.count, e + 400)]))))
        }
        // Knoepfe mit Position; Menues/Kontextmenues zaehlen nicht (eigene Ebene)
        var skip: [(Int, Int)] = []
        for r in matches(#"\b(Menu|contextMenu)\b[^{\n]*\{"#, text) {
            let b = charIndex(text, r.location + r.length); skip.append((b, blockEnd(s, b)))
        }
        var buttons: [(pos: Int, styled: Bool)] = []
        let all = matches(#"\b(Button|Link)\s*[\({]"#, text).map { charIndex(text, $0.location) }
        for (n, a) in all.enumerated() where !skip.contains(where: { $0.0 <= a && a < $0.1 }) {
            let next = n + 1 < all.count ? all[n + 1] : s.count
            let seg = String(s[a..<next])
            buttons.append((a, seg.contains(".buttonStyle(") || seg.contains(".prominentGold()") || seg.contains(".rowButtons()")))
        }
        var out: [Finding] = []
        for st in stacks where !st.styled && conts.contains(where: { $0.0 < st.start && st.start < $0.1 }) {
            let inside = buttons.filter { b in
                st.start < b.pos && b.pos < st.end && !stacks.contains { $0.styled && $0.start < b.pos && b.pos < $0.end && $0.start > st.start }
            }
            guard inside.count >= 2, inside.contains(where: { !$0.styled }) else { continue }
            let line = s[0..<st.start].filter { $0 == "\n" }.count + 1
            out.append(Finding(file: file, line: line))
        }
        return out
    }
    /// Modifikatoren direkt hinter dem Container (bis zur ersten Zeile, die nicht mit "." beginnt)
    private static func containerStyled(_ tail: String) -> Bool {
        var mods = ""
        for (n, l) in tail.split(separator: "\n", omittingEmptySubsequences: false).enumerated() {
            let t = l.trimmingCharacters(in: .whitespaces)
            if n == 0 || t.hasPrefix(".") || t.hasPrefix("//") { mods += t } else { break }
        }
        return mods.contains(".rowButtons()") || mods.contains(".buttonStyle(")
    }
}

