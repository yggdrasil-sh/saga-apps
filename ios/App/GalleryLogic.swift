import Foundation

// Saga 2.5 Vorlagengalerie, einfache Auswahl in den Apps (SPEC-galerie, Abschnitt 5): reine Logik ohne SwiftUI,
// mit swiftc pruefbar (Tests/Logic/GalleryTests.swift). Liste lesen und gruppieren, Anfrage fuer "Verwenden",
// Antwort lesen und Fehler einordnen. Zusaetzliche Felder des Servers werden ignoriert.

enum GalleryLogic {
    /// Kategorien in Anzeige-Reihenfolge; unbekannte/fehlende landen unter .other (zuletzt)
    enum Category: String, CaseIterable { case personal, work, team, other }

    struct Content: Equatable {
        var kind: String
        var title: String
        var icon: String
    }

    struct Item: Equatable, Identifiable {
        var id: String
        var title: String
        var desc: String
        var icon: String
        var category: Category
        var order: Int
        var needs: [String]
        var contents: [Content]
    }

    struct Group: Equatable, Identifiable {
        var category: Category
        var items: [Item]
        var id: String { category.rawValue }
    }

    /// Antwort von POST /api/gallery/{id}/use
    struct Created: Equatable {
        var root: Int
        var created: [Int]
    }

    /// Einordnung einer Fehlerantwort (die App waehlt daraus den Text)
    enum Failure: Equatable {
        case unsupported            // 404: aelterer Server ohne Galerie (Eintrag ausblenden)
        case rateLimited            // 429
        case forbidden              // 403 ohne Text
        case invalid                // 400 ohne Text
        case message(String)        // Text des Servers ({"error": "..."}), schon in der App-Sprache
        case failed(Int)            // sonst (z. B. 5xx)
        case badResponse            // 2xx, aber nicht lesbar
    }

    // MARK: Pfade und Anfrage

    /// Vorlagen-IDs wie im Server-Repo (Dateinamen): nur so werden sie in einen Pfad gesetzt
    static func validID(_ s: String) -> Bool {
        guard !s.isEmpty, s.count <= 64 else { return false }
        return s.unicodeScalars.allSatisfy { ("a"..."z").contains($0) || ("A"..."Z").contains($0) || ("0"..."9").contains($0) || $0 == "-" || $0 == "_" }
    }

    /// Nur die App-Sprachen; sonst Englisch (wie AppConfig.acceptLanguage)
    static let langs: Set<String> = ["de", "en", "es", "th", "ru"]

    static func listPath(lang: String) -> String {
        "api/gallery?lang=" + (langs.contains(lang) ? lang : "en")
    }

    static func usePath(_ id: String) -> String? {
        validID(id) ? "api/gallery/\(id)/use" : nil
    }

    /// Body fuer "Verwenden": parent_id immer gesetzt (null = oberste Ebene), examples als echtes Bool
    static func useBody(parent: Int?, examples: Bool) -> [String: Any] {
        ["parent_id": parent.map { $0 as Any } ?? NSNull(), "examples": examples]
    }

    // MARK: Antworten lesen

    private static func int(_ v: Any?) -> Int? {
        if let n = v as? NSNumber, CFGetTypeID(n) != CFBooleanGetTypeID() {
            let d = n.doubleValue
            return d == d.rounded() && abs(d) < 9e15 ? Int(d) : nil
        }
        if let s = v as? String, let i = Int(s) { return i }
        return nil
    }

    private static func text(_ v: Any?, max: Int) -> String {
        String(((v as? String) ?? "").trimmingCharacters(in: .whitespacesAndNewlines).prefix(max))
    }

    static func item(_ d: [String: Any]) -> Item? {
        guard let id = d["id"] as? String, validID(id) else { return nil }
        let title = text(d["title"], max: 120)
        guard !title.isEmpty else { return nil }
        let contents: [Content] = ((d["contents"] as? [Any]) ?? []).compactMap {
            guard let c = $0 as? [String: Any] else { return nil }
            let t = text(c["title"], max: 120)
            return t.isEmpty ? nil : Content(kind: text(c["kind"], max: 20), title: t, icon: text(c["icon"], max: 16))
        }
        return Item(id: id, title: title, desc: text(d["desc"], max: 400), icon: text(d["icon"], max: 16),
                    category: Category(rawValue: (d["category"] as? String) ?? "") ?? .other,
                    order: int(d["order"]) ?? 1000,
                    needs: ((d["needs"] as? [Any]) ?? []).compactMap { $0 as? String },
                    contents: Array(contents.prefix(30)))
    }

    /// GET /api/gallery -> Eintraege (ungueltige uebersprungen, doppelte IDs nur einmal); nil = keine Liste
    static func parseList(_ data: Data) -> [Item]? {
        guard let j = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let arr = j["items"] as? [Any] else { return nil }
        var seen = Set<String>()
        return arr.compactMap { ($0 as? [String: Any]).flatMap(item) }.filter { seen.insert($0.id).inserted }
    }

    /// Nach Kategorie (personal, work, team, sonstige), darin nach order, dann Titel; leere Gruppen fallen weg
    static func groups(_ items: [Item]) -> [Group] {
        Category.allCases.compactMap { c in
            let xs = items.filter { $0.category == c }.sorted {
                $0.order != $1.order ? $0.order < $1.order : $0.title.localizedStandardCompare($1.title) == .orderedAscending
            }
            return xs.isEmpty ? nil : Group(category: c, items: xs)
        }
    }

    /// Antwort von "Verwenden"; nil = nicht lesbar
    static func parseCreated(_ data: Data) -> Created? {
        guard let j = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any], let root = int(j["root"]) else { return nil }
        let created = ((j["created"] as? [Any]) ?? []).compactMap { int($0) }
        return Created(root: root, created: created)
    }

    /// Fehlerantwort einordnen. 404 heisst bei der Liste "keine Galerie"; beim Anlegen nur ohne eigenen Text
    /// (mit Text, z. B. Elternseite nicht gefunden, wird er gezeigt).
    static func failure(code: Int, data: Data, list: Bool = false) -> Failure {
        let msg = ((try? JSONSerialization.jsonObject(with: data)) as? [String: Any])?["error"] as? String
        let m = (msg ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        switch code {
        case 404: return list || m.isEmpty ? .unsupported : .message(String(m.prefix(300)))
        case 429: return .rateLimited
        case 200..<300: return .badResponse
        default: break
        }
        if !m.isEmpty && code < 500 { return .message(String(m.prefix(300))) }
        switch code {
        case 403: return .forbidden
        case 400: return .invalid
        default: return .failed(code)
        }
    }

    /// Liste vom Server: Galerie verfuegbar? (true/false; nil = unklar, z. B. offline oder 5xx - Stand behalten)
    static func offered(code: Int) -> Bool? {
        switch code {
        case 200..<300: return true
        case 403, 404: return false       // aelterer Server bzw. vom Admin abgeschaltet
        default: return nil
        }
    }
}
