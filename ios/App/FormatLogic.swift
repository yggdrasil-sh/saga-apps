import Foundation

// Zahlenformate einheitlich fuer Tabellen, Karten, Fusszeilen und Widgets (fix27 #10, wie Web/Android):
// - Prozent immer ganzzahlig ("41 %", auch 41,0377 -> 41 %), ueber 100 % echt ("119 %")
// - Waehrung (euro/eur) immer mit 2 Nachkommastellen in der Sprache der Person ("48,50 €")
// - sonst Zahl mit hoechstens 2 Nachkommastellen
enum NumFormat {
    /// Format-Kennung normalisieren: Datenbank ("percent"/"euro") und Widgets ("pct"/"eur") -> "pct" | "eur" | "num"
    static func kind(_ fmt: String?) -> String {
        switch fmt {
        case "percent", "pct": return "pct"
        case "euro", "eur": return "eur"
        default: return "num"
        }
    }

    /// Wert in seiner Einheit formatieren (Prozent: 42 = 42 %)
    static func text(_ n: Double?, fmt: String?, locale: Locale = .autoupdatingCurrent) -> String {
        guard let n, n.isFinite else { return "–" }
        let f = NumberFormatter()
        f.locale = locale
        switch kind(fmt) {
        case "pct":
            f.numberStyle = .percent; f.maximumFractionDigits = 0; f.minimumFractionDigits = 0
            return f.string(from: NSNumber(value: n.rounded() / 100)) ?? "–"
        case "eur":
            f.numberStyle = .currency; f.currencyCode = "EUR"; f.minimumFractionDigits = 2; f.maximumFractionDigits = 2
            return f.string(from: NSNumber(value: n)) ?? "–"
        default:
            f.numberStyle = .decimal; f.maximumFractionDigits = 2; f.minimumFractionDigits = 0
            return f.string(from: NSNumber(value: (n * 100).rounded() / 100)) ?? "–"
        }
    }

    /// Zahl im Eingabefeld (ohne Einheit, ohne Tausenderpunkte): beim Bearbeiten statt des formatierten Werts
    static func editText(_ n: Double, locale: Locale = .autoupdatingCurrent) -> String {
        let f = NumberFormatter()
        f.locale = locale; f.numberStyle = .decimal; f.usesGroupingSeparator = false; f.maximumFractionDigits = 10
        return f.string(from: NSNumber(value: n)) ?? "\(n)"
    }
}

/// Karten (Galerie/Board): zwei direkt aufeinanderfolgende, gefuellte Datums-Eigenschaften als Zeitraum
/// "18. Juli – 25. Juli" statt zweier Daten ohne Trenner (fix27 #17, z. B. Hin- und Rueckreise)
enum CardChipLogic {
    /// Gruppen von Indizes in Reihenfolge: [i] einzeln, [i, j] Datumsbereich
    static func groups(types: [String], filled: [Bool]) -> [[Int]] {
        var out: [[Int]] = [], i = 0
        while i < types.count {
            if types[i] == "date", filled[i], i + 1 < types.count, types[i + 1] == "date", filled[i + 1] {
                out.append([i, i + 1]); i += 2
            } else { out.append([i]); i += 1 }
        }
        return out
    }
    static func range(_ a: String, _ b: String) -> String { a == b ? a : a + " – " + b }
}
