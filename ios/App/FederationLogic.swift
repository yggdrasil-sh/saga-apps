import Foundation

// Foederation (Saga 2.0): Seiten anderer Saga-Instanzen. Die App spricht nur mit dem eigenen Server; fremde Seiten haben
// NEGATIVE IDs (-(id_dort * 1024 + server_nr)) und laufen ueber die bekannten Endpunkte. Reine Logik (mit swiftc pruefbar).

/// Herkunft einer fremden Seite ({peer, host, name})
struct RemoteRef: Decodable, Hashable {
    var peer: Int?
    var host: String
    var name: String?
    init(peer: Int? = nil, host: String, name: String? = nil) { self.peer = peer; self.host = host; self.name = name }
    init?(_ v: Any?) {
        guard let d = v as? [String: Any], let h = d["host"] as? String, !h.isEmpty else { return nil }
        self.init(peer: ShareJSON.int(d["peer"]), host: h, name: ShareJSON.str(d["name"]))
    }
}

enum FedLogic {
    /// Seite einer anderen Saga-Instanz?
    static func isRemote(_ id: Int) -> Bool { id < 0 }
    /// Seiten-ID (auch fremd) - die Seitenleiste nutzt -1 bis -99 fuer Bereiche (Aufgaben, Termine, Dienste, Chats …)
    static func isPageID(_ id: Int) -> Bool { id > 0 || id < -1000 }
    /// Sitzungsbereiche der Seitenleiste (keine Seiten)
    static let tasksTag = -1, eventsTag = -2, servicesTag = -3, chatsTag = -4

    /// GET /api/fed/shared -> Eintraege + nicht erreichbare Server (ohne Foederation: leer)
    static func parseShared<T: Decodable>(_ d: Data, as: T.Type) -> (items: [T], unreachable: [String]) {
        guard let j = ShareJSON.obj(d) else { return ([], []) }
        let items = (j["items"] as? [Any] ?? []).compactMap { v -> T? in
            guard let o = v as? [String: Any], let data = try? JSONSerialization.data(withJSONObject: o) else { return nil }
            return try? JSONDecoder().decode(T.self, from: data)
        }
        let un = (j["unreachable"] as? [Any] ?? []).compactMap { $0 as? String }
        return (items, un)
    }

    /// Fehler beim Laden/Speichern fremder Seiten einordnen
    enum Problem: Equatable { case unreachable, needsPublicName, notAvailable, other }
    static func problem(code: Int, message: String) -> Problem {
        if code == 502 { return .unreachable }
        if code == 403 && (message.contains("öffentlichen Namen") || message.contains("public name") || message.contains("nombre público")) { return .needsPublicName }
        if code == 404 && (message.contains("anderer Saga-Instanzen") || message.contains("other Saga instances") || message.contains("otras instancias")) { return .notAvailable }
        return .other
    }

    /// Verweise im Inhalt: /notes#123 und /notes#-2049 (fremde Seite) -> Seiten-ID
    static func noteID(href: String) -> Int? {
        guard let r = href.range(of: #"^/notes#(-?\d+)$"#, options: .regularExpression) else { return nil }
        return Int(href[r].split(separator: "#").last ?? "")
    }

    /// Bild fremder Seiten: /api/fed/files/{id}/{fid} (mit Sitzung, ?w= fuer Vorschaubilder wie bei /files/)
    static func isImagePath(_ p: String) -> Bool { p.hasPrefix("/files/") || p.hasPrefix("/api/fed/files/") }

    /// Teilen an name@server: Eingabe sieht wie eine Adresse aus ("bert@b.example" oder "@bert@b.example")
    static func looksLikeRemote(_ q: String) -> Bool {
        let t = q.trimmingCharacters(in: .whitespaces)
        let s = t.hasPrefix("@") ? String(t.dropFirst()) : t
        let p = s.split(separator: "@")
        return p.count == 2 && !p[0].isEmpty && p[1].contains(".")
    }
}
