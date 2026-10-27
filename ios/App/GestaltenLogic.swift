import Foundation

// Saga 2.5 "Seite gestalten" (SPEC-gestalten): reine Logik ohne SwiftUI, mit swiftc pruefbar (Tests/Logic/GestaltenTests.swift).
// Alles ist optional: fehlt ein Feld, bleibt es beim bisherigen Aussehen. Werte werden beim Lesen begrenzt
// (fremde/alte Server), geschrieben wird nur, was die App selbst aendert - alles andere bleibt unveraendert (Regel 0).

enum GestaltenLogic {
    /// Farben der Seite (gleiche Namen wie die Auswahl-Farben); nie freie Werte
    static let palette = ["gray", "brown", "orange", "yellow", "green", "blue", "purple", "pink", "red"]
    /// Bloecke, die eine Farbe (tunes.hkstyle) tragen duerfen
    static let styledTypes: Set<String> = ["paragraph", "header", "list", "quote", "toggle", "callout", "checklist"]

    // MARK: Regel 0 - Seite laden/speichern, ohne Unbekanntes zu verlieren

    /// Inhalt (format "blocks") -> veraenderbares Dokument; die Bloecke liegen als NSMutableArray unter "blocks".
    /// Unbekannte Schluessel (im Dokument, in block.data, block.tunes) bleiben einfach im Baum stehen.
    static func parseDoc(_ body: String) -> NSMutableDictionary? {
        guard let bd = body.data(using: .utf8),
              let m = try? JSONSerialization.jsonObject(with: bd, options: .mutableContainers) as? NSMutableDictionary,
              m["blocks"] is NSMutableArray else { return nil }
        return m
    }

    /// Dokument so, wie es gespeichert wird (Bloecke aus dem Editor, Zeitstempel neu)
    static func serialize(_ doc: NSMutableDictionary, blocks: NSMutableArray, timeMs: Int) -> String? {
        doc["blocks"] = blocks; doc["time"] = timeMs
        guard let data = try? JSONSerialization.data(withJSONObject: doc) else { return nil }
        return String(data: data, encoding: .utf8)
    }

    /// Umwandeln in einen anderen Typ: Block-Tunes (Farbe u. a.) mitnehmen
    static func carryTunes(from old: NSDictionary, to new: NSMutableDictionary) {
        if let t = old["tunes"] as? NSDictionary, t.count > 0 { new["tunes"] = NSMutableDictionary(dictionary: t) }
    }

    // MARK: G1 Spaltengewichte

    /// Gewichte der Spalten (Summe 1, je mindestens 0,15) - nil, wenn nicht ALLE Spalten ein gueltiges `w` haben
    static func colWeights(_ cols: NSArray?) -> [Double]? {
        guard let cols, cols.count >= 2, cols.count <= 4 else { return nil }
        var ws: [Double] = []
        for c in cols {
            guard let w = num((c as? NSDictionary)?["w"]), w.isFinite, w > 0 else { return nil }
            ws.append(w)
        }
        let sum = ws.reduce(0, +)
        guard sum > 0 else { return nil }
        var n = ws.map { $0 / sum }
        // Mindestbreite 0,15: zu schmale anheben, den Rest anteilig auf die anderen verteilen
        let minW = 0.15
        for _ in 0..<4 {
            let low = n.indices.filter { n[$0] < minW }
            if low.isEmpty { break }
            let restSum = n.indices.filter { !low.contains($0) }.map { n[$0] }.reduce(0, +)
            let free = 1 - Double(low.count) * minW
            guard restSum > 0, free > 0 else { return nil }
            n = n.indices.map { low.contains($0) ? minW : n[$0] / restSum * free }
        }
        // alle gleich -> wie bisher (kein eigenes Layout noetig)
        if let f = n.first, n.allSatisfy({ abs($0 - f) < 0.005 }) { return nil }
        return n
    }

    // MARK: G2 Breite und Ausrichtung (Bild, Player)

    enum Align: String { case left, center, right }

    /// Anteil der Inhaltsbreite (0,1 ... 1) oder nil = wie bisher. `stretched` (volle Breite) gewinnt;
    /// Telefon (`compact`): mindestens 50 %.
    static func mediaWidth(_ data: NSDictionary?, compact: Bool) -> Double? {
        guard let data, data["stretched"] as? Bool != true, let w = num(data["width"]), w.isFinite else { return nil }
        let p = min(100, max(10, w.rounded()))
        if p >= 100 { return nil }
        return compact ? max(0.5, p / 100) : p / 100
    }

    static func align(_ data: NSDictionary?) -> Align { Align(rawValue: data?["align"] as? String ?? "") ?? .center }

    /// Einbetten mit Player (YouTube, wie ytId im Web) - nur dort gelten Breite/Ausrichtung; Link-Karten bleiben
    static func isPlayer(_ url: String) -> Bool {
        url.range(of: #"(?:youtube\.com/(?:watch\?(?:.*&)?v=|embed/|shorts/|live/)|youtu\.be/)[\w-]{11}"#, options: .regularExpression) != nil
    }

    // MARK: G3 Block-Farbe

    struct Style: Equatable { var color: String; var bg: Bool }

    /// tunes.hkstyle eines Blocks - nur Palette und nur fuer erlaubte Typen
    static func style(_ block: NSDictionary) -> Style? {
        guard let type = block["type"] as? String, styledTypes.contains(type),
              let s = (block["tunes"] as? NSDictionary)?["hkstyle"] as? NSDictionary,
              let c = s["c"] as? String, palette.contains(c) else { return nil }
        return Style(color: c, bg: s["bg"] as? Bool ?? false)
    }

    /// Farbe setzen (nil = Standard: hkstyle entfernen); andere Tunes bleiben unberuehrt
    static func setStyle(_ block: NSMutableDictionary, _ style: Style?) {
        let tunes = (block["tunes"] as? NSDictionary).map { NSMutableDictionary(dictionary: $0) } ?? NSMutableDictionary()
        if let style, palette.contains(style.color) {
            tunes["hkstyle"] = NSMutableDictionary(dictionary: style.bg ? ["c": style.color, "bg": true] : ["c": style.color])
        } else {
            tunes.removeObject(forKey: "hkstyle")
        }
        if tunes.count > 0 { block["tunes"] = tunes } else { block.removeObject(forKey: "tunes") }
    }

    // MARK: G9 Schrift und kleiner Text (je Seite)

    enum PageFont: String, CaseIterable { case standard = "default", serif, mono }

    static func pageFont(_ page: [String: Any]) -> PageFont {
        switch page["font"] as? String { case "serif": return .serif; case "mono": return .mono; default: return .standard }
    }
    static func pageSmall(_ page: [String: Any]) -> Bool { (page["small"] as? Bool) ?? ((page["small"] as? Int).map { $0 != 0 } ?? false) }
    /// Server kennt Schrift/kleinen Text (GET liefert `small`) - sonst kein Umschalter (aeltere Server)
    static func supportsPageFont(_ page: [String: Any]) -> Bool { page["small"] != nil }
    /// PATCH-Wert fuer `font`
    static func patchValue(_ f: PageFont) -> String { f.rawValue }
    /// Kleiner Text: Stufe der Schriftgroesse zwei kleiner (Grundschrift 17 -> 15 pt, etwa 0,875)
    static func smallerStep(_ index: Int) -> Int { max(0, index - 2) }

    // MARK: G10 Toggle-Ueberschriften

    static func toggleLevel(_ data: NSDictionary?) -> Int { min(3, max(0, Int(num(data?["level"]) ?? 0))) }

    // MARK: R Rueckfall-Karte

    /// Typen, die die App darstellt (alles andere: Rueckfall-Karte)
    static let knownTypes: Set<String> = ["paragraph", "header", "list", "quote", "code", "delimiter", "table", "image", "attaches",
                                          "callout", "toggle", "columns", "database", "tasks", "calendar", "buttons", "embed", "nextcloud", "widget"]
    /// Ohne sichtbaren Inhalt auch im Web (Hilfsblock) - keine Karte
    static let silentTypes: Set<String> = ["verweis"]
    static func needsFallback(_ type: String) -> Bool { !knownTypes.contains(type) && !silentTypes.contains(type) }

    /// Pfad fuer den Web-Editor in der App (mit Block-Anker, wie der Link zum Block)
    static func webPath(note: Int, block: String?) -> String {
        var p = "/notes?embed=1#\(note)"
        if let block, !block.isEmpty, let b = block.addingPercentEncoding(withAllowedCharacters: anchorChars) { p += ":" + b }
        return p
    }

    private static let anchorChars = CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "-_"))

    private static func num(_ v: Any?) -> Double? {
        // true/false sind keine Zahlen (JSONSerialization liefert beides als NSNumber)
        if let n = v as? NSNumber { return CFGetTypeID(n) == CFBooleanGetTypeID() ? nil : n.doubleValue }
        return nil
    }
}
