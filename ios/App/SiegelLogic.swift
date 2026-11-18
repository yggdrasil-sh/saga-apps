import Foundation

// Saga 2.5 Saga-Siegel als Seitensymbol (Server: docs/APP-API.md "Saga-Siegel als Seitensymbol (2.5)", GET /api/siegel,
// Feature siegel1): reine Logik ohne SwiftUI, mit swiftc pruefbar (Tests/Logic/SiegelTests.swift).
// Ein Siegel ist ein normaler icon-Wert "/siegel/<farbe>/<motiv>.svg". Die Apps zeigen es wie ein eigenes Bild (/files/...)
// und laden es immer mit ?w= -> der Server liefert dann PNG (UIImage/NSImage koennen kein SVG).

enum SiegelLogic {
    /// /api/version features bzw. remote.caps einer fremden Seite
    static let feature = "siegel1"
    static let listPath = "api/siegel"
    static let prefix = "/siegel/"
    /// Farben in der Reihenfolge des Servers (saga/siegel.py FARBEN)
    static let colorIDs = ["weinrot", "smaragd", "elfenbein"]
    /// Breite im Auswahl-Raster (?w=, Server-Stufen 64/128/256)
    static let gridWidth = 128
    /// zuletzt gewaehlte Farbe (je Geraet, wie das Web je Browser)
    static let rememberKey = "hkSiegelColor"

    struct Entry: Equatable, Identifiable {
        var id: String
        var label: String
    }

    struct Listing: Equatable {
        var colors: [Entry]
        var motifs: [Entry]
        func colorLabel(_ id: String) -> String { colors.first { $0.id == id }?.label ?? SiegelLogic.fallbackLabel(id) }
        func motifLabel(_ id: String) -> String { motifs.first { $0.id == id }?.label ?? SiegelLogic.fallbackLabel(id) }
    }

    // MARK: Pfade

    /// "/siegel/<farbe>/<motiv>.svg" (genau so, kleingeschrieben, ohne Anhang) -> (farbe, motiv), sonst nil.
    /// Wie der Server (saga/siegel.py PATH_RE): Farbe aus der festen Liste, Motiv [a-z]{2,20}.
    static func parse(_ s: String?) -> (color: String, motif: String)? {
        guard let s, s.hasPrefix(prefix), s.hasSuffix(".svg") else { return nil }
        let rest = s.dropFirst(prefix.count).dropLast(4)
        let parts = rest.split(separator: "/", omittingEmptySubsequences: false)
        guard parts.count == 2 else { return nil }
        let color = String(parts[0]), motif = String(parts[1])
        guard colorIDs.contains(color), validMotif(motif) else { return nil }
        return (color, motif)
    }

    static func isPath(_ s: String?) -> Bool { parse(s) != nil }

    /// Als Bild anzeigen (lockerer als parse, damit ein spaeter dazugekommenes Motiv/eine Farbe nicht als Pfad-Text erscheint):
    /// "/siegel/<a>/<b>.svg" mit a, b aus [a-z0-9-]{1,32} - keine "..", kein "?"/"#", kein "\\".
    static func isDisplayPath(_ s: String?) -> Bool {
        guard let s, s.hasPrefix(prefix), s.hasSuffix(".svg") else { return false }
        let parts = s.dropFirst(prefix.count).dropLast(4).split(separator: "/", omittingEmptySubsequences: false)
        return parts.count == 2 && parts.allSatisfy { p in
            (1...32).contains(p.unicodeScalars.count) && p.unicodeScalars.allSatisfy { ("a"..."z").contains($0) || ("0"..."9").contains($0) || $0 == "-" }
        }
    }

    static func validMotif(_ m: String) -> Bool {
        let u = m.unicodeScalars
        return (2...20).contains(u.count) && u.allSatisfy { ("a"..."z").contains($0) }
    }

    static func path(color: String, motif: String) -> String { prefix + color + "/" + motif + ".svg" }

    /// Name ohne Liste vom Server (offline): ID mit grossem Anfangsbuchstaben ("rabe" -> "Rabe")
    static func fallbackLabel(_ id: String) -> String { id.prefix(1).uppercased() + id.dropFirst() }

    // MARK: Text statt Bild

    /// Symbol, das sich als Text zeigen laesst (Emoji); Bildpfade (/files/, /siegel/, ...) -> nil. Fuer Stellen, die nur
    /// Text koennen (Chat-Verweise, Mitteilungen) - dort sonst der Pfad als Text.
    static func textSymbol(_ icon: String?) -> String? {
        guard let i = icon?.trimmingCharacters(in: .whitespacesAndNewlines), !i.isEmpty, !i.hasPrefix("/") else { return nil }
        return i
    }

    // MARK: Liste

    private static func str(_ v: Any?) -> String { ((v as? String) ?? "").trimmingCharacters(in: .whitespacesAndNewlines) }

    /// GET /api/siegel lesen; nil = keine lesbare Antwort. Nur bekannte Farben (Reihenfolge des Servers, fehlende hinten mit
    /// Ersatznamen), Motive mit gueltiger ID (Reihenfolge des Servers, doppelte einmal). Leere Namen -> Ersatzname.
    static func parse(listing data: Data) -> Listing? {
        guard let j = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any], let ms = j["motifs"] as? [Any] else { return nil }
        var colors: [Entry] = []
        for case let c as [String: Any] in (j["colors"] as? [Any] ?? []) {
            let id = str(c["id"])
            guard colorIDs.contains(id), !colors.contains(where: { $0.id == id }) else { continue }
            let l = str(c["label"])
            colors.append(Entry(id: id, label: l.isEmpty ? fallbackLabel(id) : l))
        }
        for id in colorIDs where !colors.contains(where: { $0.id == id }) { colors.append(Entry(id: id, label: fallbackLabel(id))) }
        var motifs: [Entry] = []
        for case let m as [String: Any] in ms {
            let id = str(m["id"])
            guard validMotif(id), !motifs.contains(where: { $0.id == id }) else { continue }
            let l = str(m["label"])
            motifs.append(Entry(id: id, label: l.isEmpty ? fallbackLabel(id) : l))
        }
        guard !motifs.isEmpty else { return nil }
        return Listing(colors: colors, motifs: motifs)
    }

    /// Antwort einordnen: true = Liste da, false = aelterer Server (404) bzw. nicht erlaubt (401/403), nil = spaeter wieder
    static func offered(code: Int) -> Bool? {
        switch code {
        case 200..<300: return true
        case 401, 403, 404: return false
        default: return nil
        }
    }

    // MARK: Auswahl

    /// Bereich "Saga-Siegel" anbieten? Eigener Server mit siegel1; fremde Seite zusaetzlich deren Server (remote.caps).
    /// Aeltere Server kuerzen den Pfad als "Emoji" auf 8 Zeichen - deshalb nie ohne siegel1.
    static func offered(own: Set<String>, remote: Bool, remoteCaps: Set<String>) -> Bool {
        own.contains(feature) && (!remote || remoteCaps.contains(feature))
    }

    /// Vorgewaehlte Farbe: die des aktuellen Siegels, sonst die zuletzt gewaehlte, sonst die erste
    static func initialColor(current: String?, remembered: String?) -> String {
        if let c = parse(current)?.color { return c }
        if let r = remembered, colorIDs.contains(r) { return r }
        return colorIDs[0]
    }

    /// Name wie im Web: "Rabe (Weinrot)"
    static func name(color: String, motif: String, in l: Listing?) -> String {
        let m = l?.motifLabel(motif) ?? fallbackLabel(motif), c = l?.colorLabel(color) ?? fallbackLabel(color)
        return "\(m) (\(c))"
    }

    /// Name eines Symbol-Werts fuer Bedienungshilfen ("Rabe (Weinrot)"); nil = kein Siegel
    static func name(of icon: String?, in l: Listing?) -> String? {
        guard let p = parse(icon) else { return nil }
        return name(color: p.color, motif: p.motif, in: l)
    }

    /// Felder fuer PATCH /api/notes/{id}; nil = schon das Symbol (nicht speichern)
    static func fields(color: String, motif: String, current: String?) -> [String: Any]? {
        let p = path(color: color, motif: motif)
        return p == current ? nil : ["icon": p]
    }
}
