import Foundation

// "@" und ":" an der Cursor-Position erkennen und ersetzen (reine Text-Logik, ohne SwiftUI - testbar).

enum HKTrigger: Equatable { case at(String), emoji(String) }
/// Laenge des Ausloesers samt "@"/":" (zum Ersetzen)
extension HKTrigger { var length: Int { switch self { case .at(let q): return q.count + 1; case .emoji(let q): return q.count + 1 } } }

private func trx(_ s: String, _ p: String) -> [String]? {
    guard let re = try? NSRegularExpression(pattern: p), let m = re.firstMatch(in: s, range: NSRange(s.startIndex..., in: s)) else { return nil }
    return (0..<m.numberOfRanges).map { i in Range(m.range(at: i), in: s).map { String(s[$0]) } ?? "" }
}

/// Ausloeser am Ende von s ("... @morgen", "... :herz"). \z statt $: $ passt auch vor einem abschliessenden Zeilenumbruch
func hkTrigger(_ s: String) -> HKTrigger? {
    if let m = trx(s, #"(?:^|[\s(])@([^\s@]{0,20}(?: [^\s@]{1,15}){0,2})\z"#) { return .at(m[1]) }
    if let m = trx(s, #"(?:^|[\s(]):([a-zA-ZäöüÄÖÜß0-9_+-]{2,20})\z"#) { return .emoji(m[1]) }
    return nil
}

/// Ausloeser direkt vor dem Cursor (Zeichen-Offset in s). start = Zeichen-Offset des "@"/":".
/// Steht direkt hinter dem Cursor ein Wortzeichen (Cursor mitten im Wort), wird nichts vorgeschlagen.
func hkTrigger(_ s: String, cursor: Int) -> (trigger: HKTrigger, start: Int)? {
    guard cursor >= 0, cursor <= s.count else { return nil }
    if cursor < s.count {
        let next = s[s.index(s.startIndex, offsetBy: cursor)]
        if next.isLetter || next.isNumber || next == "_" { return nil }
    }
    guard let t = hkTrigger(String(s.prefix(cursor))) else { return nil }
    return (t, cursor - t.length)
}

/// Zeichen [start, start+length) durch insert ersetzen, der Rest bleibt unveraendert.
/// Das Eingefuegte uebernimmt die Formatierung des Ausloesers (Schrift fett/kursiv, Markierung, ...), nur den Link
/// bringt insert selbst mit. Dahinter kommt space, ausser dort steht schon ein Leerzeichen oder ein Satzzeichen.
/// Rueckgabe: Zeichen-Offset hinter dem Eingefuegten (samt Leerzeichen) - dorthin gehoert der Cursor.
@discardableResult
func hkReplaceChars(_ rich: inout AttributedString, start: Int, length: Int, with insert: AttributedString, space: AttributedString) -> Int {
    let n = rich.characters.count
    let s0 = max(0, min(start, n)), e0 = max(s0, min(start + length, n))
    let a = rich.characters.index(rich.startIndex, offsetBy: s0), b = rich.characters.index(rich.startIndex, offsetBy: e0)
    var inherit = (a < b ? rich[a..<b].runs.first?.attributes : nil) ?? AttributeContainer()
    inherit.link = nil
    var ins = insert
    ins.mergeAttributes(inherit, mergePolicy: .keepNew)            // Formatierung der Umgebung, Link bleibt (inherit hat keinen)
    let next: Character? = e0 < n ? rich.characters[b] : nil
    let hasSpace = next?.isWhitespace == true
    if !hasSpace, !(next.map { ",.;:!?)]}".contains($0) } ?? false) {
        var sp = space
        sp.mergeAttributes(inherit, mergePolicy: .keepNew)
        ins += sp
    }
    rich.replaceSubrange(a..<b, with: ins)
    return s0 + ins.characters.count + (hasSpace ? 1 : 0)
}
