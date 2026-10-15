import Foundation

// Live-Kanal (Saga 1.4), Benachrichtigungen, Anwesenheit, KI-Strom: reine Logik ohne UI (mit swiftc pruefbar).
// Server: GET /api/events (text/event-stream), POST /api/presence, /api/notifications[...]; KI: POST /api/ai/... (SSE).

/// Ein Ereignis aus einem text/event-stream (id/event/data, Leerzeile = Ende des Ereignisses)
struct SSEEvent: Equatable {
    var id: String?
    var event: String
    var data: String
    var json: [String: Any] { (try? JSONSerialization.jsonObject(with: Data(data.utf8))) as? [String: Any] ?? [:] }
}

/// Zeilenweiser Parser: Kommentarzeilen (": ping …", Fuellblock) zaehlen nur als Lebenszeichen
struct SSEParser {
    private var event = ""
    private var data: [String] = []
    private var id: String?
    private var buffer = Data()
    init() {}

    /// Eine Zeile (ohne Zeilenende) -> fertiges Ereignis bei Leerzeile
    mutating func feed(line raw: String) -> SSEEvent? {
        let line = raw.hasSuffix("\r") ? String(raw.dropLast()) : raw
        if line.isEmpty {
            defer { event = ""; data = []; id = nil }
            guard !data.isEmpty || !event.isEmpty else { return nil }
            return SSEEvent(id: id, event: event.isEmpty ? "message" : event, data: data.joined(separator: "\n"))
        }
        if line.hasPrefix(":") { return nil }
        let (k, v): (Substring, Substring)
        if let c = line.firstIndex(of: ":") {
            k = line[..<c]; var rest = line[line.index(after: c)...]
            if rest.hasPrefix(" ") { rest = rest.dropFirst() }
            v = rest
        } else { k = Substring(line); v = "" }
        switch k {
        case "event": event = String(v)
        case "data": data.append(String(v))
        case "id": id = String(v)
        default: break
        }
        return nil
    }

    /// Rohdaten (beliebig zerstueckelt) -> fertige Ereignisse; UTF-8 erst an Zeilengrenzen dekodieren
    mutating func feed(bytes: Data) -> [SSEEvent] {
        buffer.append(bytes)
        var out: [SSEEvent] = []
        while let nl = buffer.firstIndex(of: 0x0A) {
            let lineData = buffer[buffer.startIndex..<nl]
            buffer.removeSubrange(buffer.startIndex...nl)
            if let e = feed(line: String(decoding: lineData, as: UTF8.self)) { out.append(e) }
        }
        return out
    }
}

/// Was nach "bye" zu tun ist
enum LiveReconnect: Equatable {
    case now, after(Double), resumeSession, stop
    static func after(bye reason: String) -> LiveReconnect {
        switch reason {
        case "refresh": return .now
        case "limit": return .after(300)              // andere Verbindung hat uebernommen: nicht sofort neu
        case "auth": return .resumeSession
        case "account": return .stop
        default: return .after(5)                     // shutdown, slow, closed
        }
    }
    /// Wartezeit nach Verbindungsfehlern: 2, 4, 8 … hoechstens 60 s
    static func backoff(_ n: Int) -> Double { min(60, pow(2, Double(min(n, 6))) ) }
}

/// Person auf derselben Seite (POST /api/presence, SSE presence)
struct PresenceUser: Equatable, Identifiable {
    var username: String; var name: String; var editing: Bool; var me: Bool
    var id: String { username }
    init(username: String, name: String, editing: Bool, me: Bool) { self.username = username; self.name = name; self.editing = editing; self.me = me }
    init?(_ v: Any?) {
        guard let d = v as? [String: Any], let u = d["username"] as? String else { return nil }
        self.init(username: u, name: (d["name"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? u,
                  editing: (d["editing"] as? Bool) ?? false, me: (d["me"] as? Bool) ?? false)
    }
    static func others(_ list: [Any]?) -> [PresenceUser] { (list ?? []).compactMap(PresenceUser.init).filter { !$0.me } }
}

enum LiveLogic {
    /// Presence: "bearbeitet gerade" nur in den ersten 30 s nach einer Eingabe und nur mit Bearbeiten-Recht
    static func editing(lastInput: Date?, now: Date = Date(), canEdit: Bool) -> Bool {
        guard canEdit, let t = lastInput else { return false }
        return now.timeIntervalSince(t) < 30
    }
    /// note.changed / presence-rev: was tun mit der offenen Seite?
    enum PageChange: Equatable { case ignore, reloadSilently, ask, gone }
    static func pageChange(eventRev: Int?, ownRev: Int, dirty: Bool, gone: Bool) -> PageChange {
        if gone { return .gone }
        guard let r = eventRev, r > ownRev else { return .ignore }
        return dirty ? .ask : .reloadSilently
    }
    /// Anwesenheits-Satz fuer die Kopfzeile (nil = niemand)
    enum PresenceLine: Equatable { case one(name: String, editing: Bool), many(n: Int, editing: Int) }
    static func presenceLine(_ others: [PresenceUser]) -> PresenceLine? {
        guard let f = others.first else { return nil }
        if others.count == 1 { return .one(name: f.name, editing: f.editing) }
        return .many(n: others.count, editing: others.filter(\.editing).count)
    }
    /// Anfangsbuchstaben fuer Avatare
    static func initials(_ name: String) -> String {
        let parts = name.split(whereSeparator: { $0 == " " || $0 == "-" }).prefix(2)
        let s = parts.compactMap { $0.first }.map { String($0) }.joined().uppercased()
        return s.isEmpty ? "?" : s
    }
}

// MARK: - Benachrichtigungen (Glocke)

struct NotifItem: Identifiable, Equatable {
    var id: Int
    var kind: String
    var created: Double
    var read: Bool
    var actorName: String?
    var count: Int
    var access: Bool
    var noteID: Int?
    var noteTitle: String?
    var noteIcon: String?
    var host: String?
    var title: String
    var text: String
    var url: String?
    var role: String?
    var excerpt: String?
    var threadID: Int?
    var blockID: String?
    var chatID: Int?

    init?(_ v: Any?) {
        guard let d = v as? [String: Any], let i = ShareJSON.int(d["id"]) else { return nil }
        id = i; kind = d["kind"] as? String ?? ""; created = ShareJSON.num(d["created"]) ?? 0; read = ShareJSON.bool(d["read"])
        actorName = ShareJSON.str((d["actor"] as? [String: Any])?["name"])
        count = ShareJSON.int(d["count"]) ?? 1
        access = d["access"].map(ShareJSON.bool) ?? true
        let n = d["note"] as? [String: Any]
        noteID = ShareJSON.int(n?["id"]); noteTitle = n?["title"] as? String; noteIcon = ShareJSON.str(n?["icon"]); host = ShareJSON.str(n?["host"])
        title = d["title"] as? String ?? ""; text = d["text"] as? String ?? ""; url = ShareJSON.str(d["url"])
        role = ShareJSON.str(d["role"]); excerpt = ShareJSON.str(d["excerpt"])
        threadID = ShareJSON.int(d["thread_id"]); blockID = ShareJSON.str(d["block_id"])
        chatID = ShareJSON.int((d["chat"] as? [String: Any])?["id"]) ?? NotifLogic.chatID(fromURL: url)
    }
    /// antippbar: Seite mit Zugriff oder Chat
    var openable: Bool { access && (noteID != nil || chatID != nil) }
}

/// Ziel beim Antippen einer Benachrichtigung bzw. Mitteilung (url relativ zu BASE_URL)
enum NotifTarget: Equatable {
    case page(id: Int, block: String?)
    case chat(id: Int)
    case none
}

enum NotifLogic {
    /// "/chats#c12" oder "/chats#12" -> 12
    static func chatID(fromURL u: String?) -> Int? {
        guard let u, let r = u.range(of: "/chats#") else { return nil }
        var s = u[r.upperBound...]
        if s.hasPrefix("c") { s = s.dropFirst() }
        return Int(s.prefix { $0.isNumber })
    }
    /// "/notes#12", "/notes#-2049:block", "/chats#c3", "/" -> Ziel
    static func target(url: String?) -> NotifTarget {
        guard let u = url else { return .none }
        if let c = chatID(fromURL: u) { return .chat(id: c) }
        guard let r = u.range(of: "/notes#") else { return .none }
        let frag = u[r.upperBound...]
        let parts = frag.split(separator: ":", maxSplits: 1)
        guard let first = parts.first, let id = Int(first) else { return .none }
        return .page(id: id, block: parts.count > 1 ? String(parts[1]) : nil)
    }
    static func target(_ n: NotifItem) -> NotifTarget {
        if let c = n.chatID { return .chat(id: c) }
        if !n.access { return .none }
        if let id = n.noteID { return .page(id: id, block: n.blockID) }
        return target(url: n.url)
    }
    /// SSE "notification": neue (ggf. gebuendelte) Benachrichtigung einsortieren bzw. "anderswo gelesen" markieren
    static func apply(_ data: [String: Any], to items: [NotifItem]) -> (items: [NotifItem], unread: Int?) {
        var list = items
        let unread = ShareJSON.int(data["unread"])
        if let it = NotifItem(data["item"]) {
            let rep = ShareJSON.int(data["replaces"])
            list.removeAll { $0.id == it.id || (rep != nil && $0.id == rep) }
            list.insert(it, at: 0)
        } else if let r = data["read"] {
            if (r as? String) == "all" { for i in list.indices { list[i].read = true } }
            else if let ids = r as? [Any] {
                let set = Set(ids.compactMap(ShareJSON.int))
                for i in list.indices where set.contains(list[i].id) { list[i].read = true }
            }
        }
        return (list, unread)
    }
}
