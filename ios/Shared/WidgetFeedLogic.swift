import Foundation

// Homescreen-Widgets (Bugs-DB 2185): wann neu laden, wann "Stand: ..." zeigen, welche Abfrage, letzter guter Stand.
// Reine Foundation-Logik (Widget-Erweiterung und App) - geprueft in Tests/Logic (WidgetFeedTests.swift).
//
// Ursache 2185: Das Widget "Seite oder Datenbank" holte ALLE Zeilen einer Datenbank und filterte selbst. Bei grossen
// Datenbanken (Bugs-DB) ist das fuer den Speicher der Widget-Erweiterung zu viel - bricht das Laden ab, behaelt WidgetKit
// den alten Eintrag stumm (auch die Seiten-Karte im selben Prozess). Titelbilder kamen zudem bis zu 1 h aus dem
// URL-Zwischenspeicher (Cache-Control private, max-age=3600, gleiche Adresse fuer neues Bild).
enum WidgetFeed {
    /// Naechster Abruf: 15 min nach Erfolg (WidgetKit erlaubt grob 40-70 Abrufe am Tag), 10 min nach einem Fehler
    static let okInterval: TimeInterval = 15 * 60
    static let failInterval: TimeInterval = 10 * 60
    /// Spaetestens dann zeigt das Widget "Stand: ..." - auch wenn WidgetKit nicht neu laden konnte (Budget, Abbruch)
    static let staleAfter: TimeInterval = 45 * 60
    /// Zeilen je Datenbank-Widget (gross zeigt 10; "+ N weitere" kommt aus matched)
    static let rowLimit = 10

    static func nextReload(ok: Bool, now: Date) -> Date { now.addingTimeInterval(ok ? okInterval : failInterval) }

    /// Eintraege einer Timeline: (Zeitpunkt, Stand zeigen?).
    /// Erfolg: jetzt ohne Stand, dazu ein spaeterer Eintrag mit Stand (falls nicht rechtzeitig neu geladen wird).
    /// Fehler mit letztem guten Stand: sofort mit Stand. Fehler ohne: nur der Fehler (kein Stand).
    static func entryPlan(ok: Bool, hasCached: Bool, fetchedAt: Date?, now: Date) -> [(date: Date, stamp: Bool)] {
        if ok { return [(now, false), (now.addingTimeInterval(staleAfter), true)] }
        return [(now, hasCached && fetchedAt != nil)]
    }

    /// "14:05" heute, sonst mit Tag ("18. Nov., 14:05") - fuer "Stand: %@"
    static func stampTime(_ at: Date, now: Date, calendar: Calendar = SagaDate.calendar, locale: Locale = SagaDate.locale()) -> String {
        let f = DateFormatter()
        f.calendar = calendar; f.locale = locale; f.timeZone = calendar.timeZone
        f.setLocalizedDateFormatFromTemplate(calendar.isDate(at, inSameDayAs: now) ? "jmm" : "dMMMjmm")
        return f.string(from: at)
    }
    static func stamp(_ at: Date, now: Date) -> String { String(localized: "Stand: \(stampTime(at, now: now))") }

    /// Abfrage fuer das Datenbank-Widget: der Server rechnet Ansicht, Filter, Sortierung und Unteraufgaben (wie im Web)
    /// und liefert nur die ersten `limit` Zeilen plus matched. view "" = erste Ansicht (wie lokal DBQuery.view(nil)),
    /// group "" = ohne Gruppierung (Reihenfolge wie bisher in der Liste); "heute" rechnet der Server in tz.
    static func rowsPath(db: Int, view: String?, limit: Int = rowLimit, tz: String) -> String {
        var c = URLComponents()
        c.path = "api/app/rows/\(db)"
        c.queryItems = [URLQueryItem(name: "view", value: view ?? ""), URLQueryItem(name: "group", value: ""),
                        URLQueryItem(name: "limit", value: String(limit)), URLQueryItem(name: "tz", value: tz)]
        // "+" in Zeitzonen (Etc/GMT+1) waere sonst ein Leerzeichen
        return (c.string ?? c.path).replacingOccurrences(of: "+", with: "%2B")
    }

    /// Hat der Server die Abfrage gerechnet? Dann steht matched in der Antwort (aeltere Server: alle Zeilen, ohne matched)
    static func serverMatched(_ data: Data) -> Int? {
        guard let j = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any], let m = j["matched"] as? NSNumber,
              CFGetTypeID(m) != CFBooleanGetTypeID() else { return nil }
        let v = m.intValue
        return v >= 0 ? v : nil
    }

    /// Wie viele Zeilen gibt es insgesamt (fuer "+ N weitere")? Server-Zahl, sonst die lokal gefilterten
    static func total(matched: Int?, shown: Int) -> Int { max(matched ?? shown, shown) }

    // MARK: Letzter guter Stand (App-Gruppe)

    /// Schluessel je Konto (Server + Schluessel, nur als Pruefsumme) und Widget - nach Kontowechsel kein fremder Stand.
    /// nil ohne Schluessel: dann nichts merken.
    static func snapshotKey(widget: String, target: String, server: String, token: String) -> String? {
        guard !token.isEmpty, !server.isEmpty else { return nil }
        return "w.snap.\(widget).\(fnv(server + "|" + token)).\(target)"
    }
    /// Dateiname fuer Bilder (Titelbild/Symbol) des letzten guten Stands
    static func imageFile(key: String, which: String) -> String {
        "\(fnv(key))-\(which == "icon" ? "icon" : "cover").img"
    }
    /// Staende verwerfen, wenn der App-Schluessel geleert (Abmelden) oder durch einen anderen ersetzt wird (Kontowechsel,
    /// neuer Schluessel): die Schluessel der Staende haengen am alten App-Schluessel und waeren sonst verwaist liegen geblieben
    static func dropSnapshots(oldToken: String, newToken: String) -> Bool { newToken.isEmpty || newToken != oldToken }
    /// Abmelden: gemerkte Widget-Staende (auch Titelbilder) verwerfen
    static func clearSnapshots(_ d: UserDefaults, cardsDir: URL?) {
        for k in d.dictionaryRepresentation().keys where k.hasPrefix("w.snap.") { d.removeObject(forKey: k) }
        if let cardsDir { try? FileManager.default.removeItem(at: cardsDir) }
    }
    /// Ordner der gemerkten Bilder der Seiten-Karte (App-Gruppe)
    static func cardsDir(group: String) -> URL? {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: group)?
            .appendingPathComponent("Library/Caches/widget-cards", isDirectory: true)
    }
    /// FNV-1a 64 bit als Hex - stabil ueber Starts (anders als Hasher), kein Geheimnis
    static func fnv(_ s: String) -> String {
        var h: UInt64 = 0xcbf29ce484222325
        for b in s.utf8 { h ^= UInt64(b); h = h &* 0x100000001b3 }
        return String(h, radix: 16)
    }
}

/// Letzter guter Stand eines Widgets (JSON in den Einstellungen der App-Gruppe)
struct WidgetSnapshot<T: Codable>: Codable {
    var at: Date
    var value: T

    static func load(_ key: String?, from d: UserDefaults) -> WidgetSnapshot<T>? {
        guard let key, let raw = d.data(forKey: key) else { return nil }
        return try? JSONDecoder().decode(WidgetSnapshot<T>.self, from: raw)
    }
    static func save(_ v: T, at: Date, key: String?, to d: UserDefaults) {
        guard let key, let raw = try? JSONEncoder().encode(WidgetSnapshot(at: at, value: v)) else { return }
        d.set(raw, forKey: key)
    }
}

/// Bild-Abruf fuer die Seiten-Karte: Bild, keins (404) oder Fehler (dann letzten Stand behalten)
enum WidgetImage: Equatable {
    case image(Data), none, failed
    static func from(status: Int?, data: Data?) -> WidgetImage {
        guard let status else { return .failed }
        if (200..<300).contains(status), let data, !data.isEmpty { return .image(data) }
        return status == 404 ? .none : .failed
    }
    /// Was zeigen? Neues Bild; ohne Bild nichts; bei Fehler das gemerkte
    func resolved(cached: Data?) -> Data? {
        switch self {
        case .image(let d): return d
        case .none: return nil
        case .failed: return cached
        }
    }
}
