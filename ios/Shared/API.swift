import Foundation

struct TodayEvent: Codable, Hashable {
    var summary: String; var start: String; var end: String; var allday: Bool
    var location: String?; var color: String?; var calendar: String?
}
struct TodayTask: Codable, Hashable { var id: Int; var text: String; var due: String?; var prio: Int?; var overdue: Bool? }
struct TodayData: Codable { var date: String; var events: [TodayEvent]; var tasks: [TodayTask]; var errors: [String?]? }
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
        guard AppConfig.hasToken, AppConfig.hasServer else { throw APIError.noToken }
        // NICHT appendingPathComponent: das kodiert "?" als %3F (Abfrage-Parameter wie ?w= wuerden zum Pfad)
        guard let url = URL(string: AppConfig.server.absoluteString.trimmingCharacters(in: CharacterSet(charactersIn: "/")) + "/" + path) else { throw APIError.noToken }
        var req = URLRequest(url: url, timeoutInterval: 20)
        req.httpMethod = method
        req.setValue("Bearer \(AppConfig.token)", forHTTPHeaderField: "Authorization")
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
        try JSONDecoder().decode(TodayData.self, from: try await request("api/app/today"))
    }

    // --- Widgets ---
    static func pages() async throws -> [AppPage] {
        try JSONDecoder().decode([AppPage].self, from: try await request("api/app/pages"))
    }
    static func rows(_ db: Int) async throws -> Data { try await request("api/app/rows/\(db)") }
    /// Titelbild ("cover", sonst erstes Bild im Inhalt) bzw. Symbol-Bild ("icon") einer eigenen Seite - nil, wenn keins
    static func pageImage(_ id: Int, _ which: String) async -> Data? { try? await request("api/app/page-image/\(id)/\(which)?w=\(which == "icon" ? 160 : 640)") }
    static func taskDone(_ id: Int, done: Bool) async throws {
        let f = DateFormatter(); f.dateFormat = "yyyy-MM-dd"
        _ = try await request("api/app/tasks/\(id)/done", method: "POST", body: ["done": done, "today": f.string(from: .now)])
    }
    static func check(row: Int, pid: String, value: Bool) async throws {
        _ = try await request("api/app/rows/\(row)/check", method: "POST", body: ["pid": pid, "value": value])
    }

    /// Link/Text ablegen: kind "page" (Seite im Eingang) oder "task" (Aufgabe).
    static func capture(kind: String, title: String, text: String, url: String?, due: Date? = nil) async throws {
        var b: [String: Any] = ["kind": kind, "title": title, "text": text]
        if let url, !url.isEmpty { b["url"] = url }
        if let due { let f = DateFormatter(); f.dateFormat = "yyyy-MM-dd"; b["due"] = f.string(from: due) }
        _ = try await request("api/app/capture", method: "POST", body: b)
    }
}
