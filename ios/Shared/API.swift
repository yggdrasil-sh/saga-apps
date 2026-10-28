import Foundation

struct AppView: Codable, Hashable { var id: String?; var name: String?; var type: String? }
struct AppPage: Codable, Hashable {
    var id: Int; var title: String; var icon: String; var kind: String; var parent_id: Int?; var views: [AppView]?
    var cover: Bool?; var cover_pos: Double?
    var isDatabase: Bool { kind == "database" }
    /// Symbol nur, wenn es ein Emoji ist (eigene Bilder koennen Widgets nicht laden)
    var emoji: String? { icon.isEmpty || icon.count > 4 || icon.hasPrefix("/") ? nil : icon }
}

enum APIError: LocalizedError {
    case noToken, http(Int, String), other(String)
    var errorDescription: String? {
        switch self {
        case .noToken: return String(localized: "Kein App-Schlüssel – in der App unter Einstellungen eintragen.")
        case .http(401, let m): return m.isEmpty ? String(localized: "Schlüssel ungültig oder widerrufen.") : m
        case .http(let c, let m): return m.isEmpty ? String(localized: "Server-Fehler \(c)") : m
        case .other(let m): return m
        }
    }
}

/// Zugriff auf die eng begrenzten App-Endpunkte (/api/app/*) mit dem App-Schluessel.
enum API {
    private static func request(_ path: String, method: String = "GET", body: [String: Any]? = nil) async throws -> Data {
        // Schluessel nur an den Server, der ihn ausgestellt hat (AppConfig.token prueft das), und nur verschluesselt
        guard AppConfig.hasServer, AppConfig.isAllowedServer(AppConfig.server), AppConfig.hasToken else { throw APIError.noToken }
        // NICHT appendingPathComponent: das kodiert "?" als %3F (Abfrage-Parameter wie ?w= wuerden zum Pfad)
        guard let url = URL(string: AppConfig.server.absoluteString.trimmingCharacters(in: CharacterSet(charactersIn: "/")) + "/" + path) else { throw APIError.noToken }
        var req = URLRequest(url: url, timeoutInterval: 20)
        req.httpMethod = method
        req.setValue("Bearer \(AppConfig.token)", forHTTPHeaderField: "Authorization")
        req.setValue(AppConfig.appLanguage, forHTTPHeaderField: "Accept-Language")       // Fehlertexte in der App-Sprache
        if let body {
            req.setValue("application/json", forHTTPHeaderField: "Content-Type")
            req.httpBody = try JSONSerialization.data(withJSONObject: body)
        }
        let (data, resp) = try await URLSession.shared.data(for: req)
        let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(code) else {
            let msg = (try? JSONSerialization.jsonObject(with: data) as? [String: Any])?["error"] as? String ?? ""
            throw APIError.http(code, msg)
        }
        return data
    }

    static func today() async throws -> TodayData {
        try JSONDecoder().decode(TodayData.self, from: try await request("api/app/today?sources=1"))   // aeltere Server ignorieren sources
    }

    // --- Widgets ---
    static func pages() async throws -> [AppPage] {
        try JSONDecoder().decode([AppPage].self, from: try await request("api/app/pages"))
    }
    static func rows(_ db: Int) async throws -> Data { try await request("api/app/rows/\(db)") }
    /// Titelbild ("cover", sonst erstes Bild im Inhalt) bzw. Symbol-Bild ("icon") einer eigenen Seite - nil, wenn keins
    static func pageImage(_ id: Int, _ which: String) async -> Data? { try? await request("api/app/page-image/\(id)/\(which)?w=\(which == "icon" ? 160 : 640)") }
    static func taskDone(_ id: Int, done: Bool) async throws {
        let f = DateFormatter.isoDay()
        _ = try await request("api/app/tasks/\(id)/done", method: "POST", body: ["done": done, "today": f.string(from: .now)])
    }
    /// 2.5: Zeile einer Aufgabenquelle abhaken (Antwort mit prev fuer Rueckgaengig)
    @discardableResult
    static func rowDone(_ row: Int, done: Bool, value: TaskValue? = nil) async throws -> TaskValue? {
        let d = try await request("api/app/tasks/src/\(row)/done", method: "POST", body: TaskSourceLogic.doneBody(done: done, value: value))
        return ((try? JSONSerialization.jsonObject(with: d)) as? [String: Any])?["prev"].map(TaskValue.init)
    }
    static func check(row: Int, pid: String, value: Bool) async throws {
        _ = try await request("api/app/rows/\(row)/check", method: "POST", body: ["pid": pid, "value": value])
    }

    /// Teilen-Blatt (2.5): Namen der eigenen Lesezeichen-Kategorien und ein Lesezeichen anlegen (nur diese zwei Rechte).
    struct BookmarkCategories: Decodable { var categories: [String]; var `default`: String? }
    static func bookmarkCategories() async throws -> BookmarkCategories {
        try JSONDecoder().decode(BookmarkCategories.self, from: try await request("api/app/bookmarks/categories"))
    }
    static func addBookmark(_ body: [String: Any]) async throws {
        _ = try await request("api/app/bookmarks", method: "POST", body: body)
    }

    /// Link/Text ablegen: kind "page" (Seite im Eingang) oder "task" (Aufgabe).
    static func capture(kind: String, title: String, text: String, url: String?, due: Date? = nil) async throws {
        var b: [String: Any] = ["kind": kind, "title": title, "text": text]
        if let url, !url.isEmpty { b["url"] = url }
        if let due { let f = DateFormatter.isoDay(); b["due"] = f.string(from: due) }
        _ = try await request("api/app/capture", method: "POST", body: b)
    }
}
