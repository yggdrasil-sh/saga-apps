import Foundation
import ImageIO
import UniformTypeIdentifiers

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

/// Bild fuer Titelbild/Symbol vor dem Hochladen. Der Server nimmt hoechstens ATTACH_MAX_MB (Standard 10 MB) und nur
/// PNG/JPEG/GIF/WebP; ein aktuelles iPhone-Foto (HEIC, 24/48 MP) wurde bisher in voller Groesse als JPEG geschickt -
/// gross, langsam, evtl. abgelehnt, und die Vorschau musste der Server erst aus dem Riesenbild rechnen.
enum HeaderImage {
    static let coverMaxPixel = 3000                 // Titelbild: Web und Apps laden hoechstens ?w=2000
    static let iconMaxPixel = 640                   // Symbol: angezeigt mit hoechstens 78 pt
    static let maxBytes = 9 * 1024 * 1024           // mit Reserve unter dem Server-Standard (10 MB)

    /// Unveraendert, wenn schon passend (Format, Groesse, Ausrichtung) und GIF immer (Animation); sonst auf maxPixel
    /// verkleinert, nach EXIF gedreht, als JPEG (mit Transparenz als PNG). nil = kein lesbares Bild.
    static func prepare(_ data: Data, maxPixel: Int) -> Data? {
        guard let src = CGImageSourceCreateWithData(data as CFData, nil), let type = CGImageSourceGetType(src) as String? else { return nil }
        let props = CGImageSourceCopyPropertiesAtIndex(src, 0, nil) as? [CFString: Any] ?? [:]
        let w = props[kCGImagePropertyPixelWidth] as? Int ?? 0, h = props[kCGImagePropertyPixelHeight] as? Int ?? 0
        let orient = props[kCGImagePropertyOrientation] as? Int ?? 1
        if type == UTType.gif.identifier && data.count <= maxBytes { return data }
        let web = [UTType.png, .jpeg, .webP].map(\.identifier)
        if web.contains(type) && max(w, h) <= maxPixel && orient == 1 && data.count <= maxBytes { return data }
        let opts: [CFString: Any] = [kCGImageSourceCreateThumbnailFromImageAlways: true, kCGImageSourceThumbnailMaxPixelSize: maxPixel,
                                     kCGImageSourceCreateThumbnailWithTransform: true]
        guard let cg = CGImageSourceCreateThumbnailAtIndex(src, 0, opts as CFDictionary) else { return nil }
        // Transparenz nur bei Grafikformaten behalten (Fotos/HEIC melden manchmal einen Alphakanal ohne Inhalt)
        let alpha = (props[kCGImagePropertyHasAlpha] as? Bool ?? false) && [UTType.png, .webP, .gif].map(\.identifier).contains(type)
        let out = NSMutableData()
        guard let dst = CGImageDestinationCreateWithData(out, (alpha ? UTType.png : UTType.jpeg).identifier as CFString, 1, nil) else { return nil }
        CGImageDestinationAddImage(dst, cg, alpha ? nil : [kCGImageDestinationLossyCompressionQuality: 0.85] as CFDictionary)
        guard CGImageDestinationFinalize(dst) else { return nil }
        return out as Data
    }
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
