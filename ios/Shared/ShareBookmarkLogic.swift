import Foundation

/// Teilen-Blatt (iOS/macOS): Auswahl Seite/Aufgabe/Lesezeichen und Vorbelegung des Lesezeichens - reine Logik (Tests: Tests/Logic).
enum ShareBookmarkLogic {
    static let kinds = ["page", "task", "bookmark"]
    static let lastKindKey = "shareLastKind", lastCategoryKey = "shareLastCategory"
    static let nameMax = 80, categoryMax = 40

    /// Vorauswahl: die zuletzt benutzte Art (Lesezeichen nur, wenn ein Link dabei ist), sonst wie bisher "page".
    static func initialKind(last: String?, hasURL: Bool) -> String {
        guard let last, kinds.contains(last) else { return "page" }
        return last == "bookmark" && !hasURL ? "page" : last
    }

    /// Name aus dem Seitentitel, sonst der Host der Adresse (wie der Server bei leerem Namen); hoechstens 80 Zeichen.
    static func defaultName(title: String, url: String?) -> String {
        let t = title.split(whereSeparator: \.isNewline).first.map(String.init)?.trimmingCharacters(in: .whitespaces) ?? ""
        if !t.isEmpty { return String(t.prefix(nameMax)) }
        guard let url, let host = URLComponents(string: url)?.host else { return "" }
        return String((host.hasPrefix("www.") ? String(host.dropFirst(4)) : host).prefix(nameMax))
    }

    /// Auswahlliste der Kategorien: die des Servers (ohne Leere/Doppelte, Reihenfolge bleibt), Standard immer dabei.
    static func categories(server: [String], standard: String) -> [String] {
        var seen = Set<String>(), out: [String] = []
        for c in server + [standard] {
            let n = c.trimmingCharacters(in: .whitespacesAndNewlines)
            if !n.isEmpty, seen.insert(n).inserted { out.append(n) }
        }
        return out
    }

    /// Vorbelegte Kategorie: die zuletzt benutzte, wenn es sie noch gibt, sonst der Standard.
    static func initialCategory(last: String?, available: [String], standard: String) -> String {
        if let last, available.contains(last) { return last }
        return standard
    }

    /// Body fuer POST /api/app/bookmarks. Symbol bleibt leer: die Apps raten keins (Web/Apps: optional von Hand).
    static func payload(name: String, url: String, category: String) -> [String: Any] {
        ["name": String(name.trimmingCharacters(in: .whitespacesAndNewlines).prefix(nameMax)),
         "url": url.trimmingCharacters(in: .whitespacesAndNewlines),
         "category": String(category.trimmingCharacters(in: .whitespacesAndNewlines).prefix(categoryMax)),
         "icon": ""]
    }
}
