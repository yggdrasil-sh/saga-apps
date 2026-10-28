import Foundation
import CoreGraphics

// Saga 2.5 "Notion-Gefuehl" (Quick Wins Mac/iPad): Masse der Schreibflaeche. Reine Logik (mit Tests) - die Ansicht
// (EditText, PageTitleField, BlocksView) liest daraus Schriftgroessen, Zeilen- und Blockabstaende.
// iPhone (und alles ausserhalb der Seite) bleibt bei `standard`: Textstile des Systems, Abstaende wie bisher.

enum WritingLogic {
    /// Rolle eines Textfelds auf der Seite
    enum Role: Equatable { case body, bodyBold, title, h1, h2, h3, h4 }

    /// Masse einer Schreibflaeche. `sizes == nil`: Textstile des Systems - nur Abstaende aendern sich.
    struct Metrics: Equatable {
        var sizes: [Role: CGFloat]?
        var lineSpacing: CGFloat
        var blockSpacing: CGFloat
        /// Abstand ueber Ueberschriften (Ebene 1, 2, 3, 4+)
        var headerTop: [CGFloat]

        func size(_ r: Role) -> CGFloat? { sizes?[r] }
        func headerPadding(level: Int) -> CGFloat { headerTop[min(max(level, 1), headerTop.count) - 1] }
    }

    /// bisher (iPhone, Vorschauen): Textstile des Systems, 10 pt zwischen Bloecken, 6 pt ueber Ueberschriften
    static let standard = Metrics(sizes: nil, lineSpacing: 0, blockSpacing: 10, headerTop: [6, 6, 6, 6])

    /// Mac: feste Schreibgroesse wie Notion (16 pt Text, 35 pt Titel; H1/H2/H3 26/21/18), Zeilenabstand +3 pt,
    /// Luft ueber Ueberschriften. Die Groessen gelten fuer die Standardstufe und folgen Dynamic Type bzw. "kleiner Text".
    static let mac = Metrics(
        sizes: [.body: 16, .bodyBold: 16, .title: 35, .h1: 26, .h2: 21, .h3: 18, .h4: 16],
        lineSpacing: 3, blockSpacing: 10, headerTop: [18, 14, 10, 8])

    /// iPad (normale Breite): Textstile des Systems (17 pt Text, 34 pt Titel sind schon passend) - nur mehr Luft
    static let pad = Metrics(sizes: nil, lineSpacing: 2, blockSpacing: 12, headerTop: [16, 12, 8, 6])

    /// Masse fuer die Seite: Mac immer, iPad nur in normaler Breite (geteilter Bildschirm schmal = wie iPhone)
    static func metrics(mac isMac: Bool, regularWidth: Bool) -> Metrics {
        isMac ? mac : regularWidth ? pad : standard
    }

    /// Stufen von Dynamic Type (Reihenfolge wie `DynamicTypeSize.allCases`): Groesse des Textstils "body" auf iOS.
    /// Index 3 (large, 17 pt) ist die Standardstufe.
    static let bodySteps: [CGFloat] = [14, 15, 16, 17, 19, 21, 23, 28, 33, 40, 47, 53]
    static let defaultStep = 3

    /// Faktor fuer eine Dynamic-Type-Stufe (Standard = 1; unbekannt = Standard)
    static func scale(step: Int) -> CGFloat {
        guard bodySteps.indices.contains(step) else { return 1 }
        return bodySteps[step] / bodySteps[defaultStep]
    }

    /// Schriftgroesse einer Rolle bei einer Stufe (gerundet auf halbe Punkte; nil = Textstil des Systems)
    static func pointSize(_ r: Role, step: Int, in m: Metrics) -> CGFloat? {
        guard let base = m.size(r) else { return nil }
        return (base * scale(step: step) * 2).rounded() / 2
    }

    /// Mac: senkrechte Mitte der Griffe (+ ⋮⋮) ab Oberkante des Blocks - auf der ersten Textzeile (wie Notion).
    /// Ueberschriften: Abstand darueber + halbe Zeilenhoehe; sonst halbe Zeilenhoehe (mindestens 11 = halbe Griffhoehe).
    static func handleCenter(type: String, level: Int, step: Int, in m: Metrics) -> CGFloat {
        let header = type == "header"
        let role: Role = header ? headerRole(level: max(level, 1)) : .body
        let size = pointSize(role, step: step, in: m) ?? 13
        let line = size * 1.2 + m.lineSpacing
        let top = header ? m.headerPadding(level: level) : 0
        return max(11, (top + line / 2).rounded())
    }

    /// Ueberschriften und Titel sind fett
    static func isBold(_ r: Role) -> Bool { r != .body }

    /// Rolle einer Ueberschrift nach Ebene (Block "header" bzw. Toggle-Ueberschrift; 0 = fetter Text)
    static func headerRole(level: Int) -> Role {
        switch level { case ...0: return .bodyBold; case 1: return .h1; case 2: return .h2; case 3: return .h3; default: return .h4 }
    }
}
