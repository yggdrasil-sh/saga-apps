import Foundation

/// Seitenkopf (Titelbild, Symbol): reine Logik, ohne SwiftUI pruefbar (Tests/Logic/HeaderTests.swift)
enum HeaderLogic {
    /// Felder sofort in die Seite uebernehmen (NSNull = entfernen). Rueckgabe: die alten Werte - mit denen
    /// stellt ein zweiter Aufruf den vorherigen Stand wieder her (Speichern fehlgeschlagen)
    @discardableResult
    static func apply(_ page: inout [String: Any], _ fields: [String: Any]) -> [String: Any] {
        var undo: [String: Any] = [:]
        for (k, v) in fields {
            undo[k] = page[k] ?? NSNull()
            if v is NSNull { page[k] = nil } else { page[k] = v }
        }
        return undo
    }

    /// Position des Titelbilds 0 (oben) ... 100 (unten); der Server liefert eine Zahl, frueher lokal auch Int
    static func coverPos(_ v: Any?) -> Double {
        let d: Double?
        switch v {
        case let x as Double: d = x
        case let x as Int: d = Double(x)
        case let x as NSNumber: d = x.doubleValue
        case let x as String: d = Double(x)
        default: d = nil
        }
        guard let d, d.isFinite else { return 50 }
        return min(100, max(0, d))
    }

    /// Was Tippen/Klicken auf Symbol oder Titelbild bewirkt
    enum Tap: Equatable {
        case edit                      // Auswahl oeffnen
        case lockedHint                // Seite gesperrt: kurzer Hinweis statt stillem Nichtstun
        case none                      // nur Lesen/Kommentieren, fremde Instanz, nicht geladen: nichts (wie im Web)
    }
    static func tap(readOnlyRole: Bool, remote: Bool, fetched: Bool, locked: Bool) -> Tap {
        if readOnlyRole || remote || !fetched { return .none }
        return locked ? .lockedHint : .edit
    }

    /// Bild vom Server nachladen: Wartezeiten vor dem 1., 2., ... Versuch (die Vorschau ?w= erzeugt der Server beim
    /// ersten Abruf; ein Zeitlimit oder Netzaussetzer darf das Bild nicht bis zum Neuladen der Seite verschwinden lassen)
    static let retryDelays: [Double] = [0, 1, 3, 8]
}

/// Schaltflaechen-Bloecke ("+ Neuer Eintrag" usw.)
enum ButtonLogic {
    /// Titel-Vorlage wie im Web: {datum} / {date} / {fecha} -> heutiges Datum
    static func title(_ template: String, today: String) -> String {
        var t = template
        for k in ["{datum}", "{date}", "{fecha}"] { t = t.replacingOccurrences(of: k, with: today) }
        return t
    }
    /// Laufende Aktionen (je Knopf): ein zweites Tippen, waehrend die erste Anfrage laeuft, wird ignoriert -
    /// sonst legt mehrfaches Tippen bei langsamer Verbindung mehrere Eintraege an
    struct Gate: Equatable {
        private(set) var running: Set<String> = []
        mutating func begin(_ key: String) -> Bool { running.insert(key).inserted }
        mutating func end(_ key: String) { running.remove(key) }
        func busy(_ key: String) -> Bool { running.contains(key) }
    }
    /// Schaltflaechen bleiben auf gesperrten Seiten bedienbar (wie im Web); nur Lesen/Kommentieren: nicht
    static func enabled(readOnlyRole: Bool) -> Bool { !readOnlyRole }
}
