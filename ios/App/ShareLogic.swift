import Foundation

// Teilen und Kommentare (Saga 1.3): reine Logik ohne SwiftUI (Rollen -> erlaubte Aktionen, Zustand vom Server ->
// Anzeige, Konflikt/Zugriff verloren, Maskierung, Kommentar-Baum). Texte stehen in den Ansichten (Sharing.swift,
// Comments.swift) - so laesst sich alles mit swiftc pruefen.
// Server: GET /api/notes/{id} (role, owner, comment_counts ...), /api/shared, /api/share/info, /api/notes/{id}/shares,
// /api/share/targets, /api/notes/{id}/comments, /api/comments/{id}[/resolve|/seen].

/// Werte aus JSONSerialization (NSNumber, NSNull) robust lesen
enum ShareJSON {
    static func str(_ v: Any?) -> String? { (v as? String).flatMap { $0.isEmpty ? nil : $0 } }
    static func bool(_ v: Any?) -> Bool { (v as? Bool) ?? ((v as? NSNumber)?.boolValue ?? false) }
    static func int(_ v: Any?) -> Int? { v is NSNull ? nil : (v as? Int) ?? (v as? NSNumber)?.intValue }
    static func num(_ v: Any?) -> Double? { v is NSNull ? nil : (v as? Double) ?? (v as? NSNumber)?.doubleValue }
    static func obj(_ d: Data) -> [String: Any]? { (try? JSONSerialization.jsonObject(with: d)) as? [String: Any] }
}

/// Rolle auf einer Seite: owner > edit > comment > view
enum ShareRole: String, CaseIterable, Equatable {
    case view, comment, edit, owner
    var rank: Int { switch self { case .view: return 1; case .comment: return 2; case .edit: return 3; case .owner: return 4 } }
    var canEdit: Bool { rank >= ShareRole.edit.rank }
    var canComment: Bool { rank >= ShareRole.comment.rank }
    /// Rollen, die man vergeben kann (Teilen-Dialog)
    static let grantable: [ShareRole] = [.view, .comment, .edit]
    static func parse(_ v: Any?) -> ShareRole? { (v as? String).flatMap(ShareRole.init(rawValue:)) }
}

/// Eigentuemer, Verfasser, "zuletzt geaendert von" ({name, username, me})
struct SharePerson: Equatable {
    var name: String
    var username: String?
    var me: Bool
    /// Profilbild (2.1): relative Adresse /api/avatar/... oder nil
    var avatar: String? = nil
    init(name: String, username: String? = nil, me: Bool = false, avatar: String? = nil) { self.name = name; self.username = username; self.me = me; self.avatar = avatar }
    init?(_ v: Any?) {
        guard let d = v as? [String: Any] else { return nil }
        self.init(name: ShareJSON.str(d["name"]) ?? ShareJSON.str(d["username"]) ?? "", username: ShareJSON.str(d["username"]), me: ShareJSON.bool(d["me"]),
                  avatar: ShareJSON.str(d["avatar"]))
    }
}

/// Aktionen im Seitenmenue - was welche Rolle darf
enum PageAction: CaseIterable, Equatable {
    // nur der Eigentuemer
    case delete, move, archive, publish, share, versions, template, favorite, lock, duplicate, editIcon
    // ab "Bearbeiten"
    case wide, addSubpage, editContent
    // ab "Kommentieren"
    case writeComment
    // alle
    case comments, export, copyLink, reload
}

/// Faehigkeiten des Servers einer fremden Seite (remote.caps, Saga 2.3, docs/APP-API-FED.md Abschnitt 8). Ohne Angabe
/// (Server 2.1/2.2) bleibt es bei Lesen, Kommentieren und Titel/Inhalt bearbeiten.
struct FedCaps: Equatable {
    static let db = "db", subpages = "subpages", upload = "upload", versions = "versions", export = "export"
    static let presence = "presence", live = "live", chatgroups = "chatgroups"
    var set: Set<String> = []
    init() {}
    /// remote.caps (Liste von Texten) bzw. RemoteRef.caps
    init(_ v: Any?) { set = Set(Self.list(v) ?? []) }
    static func list(_ v: Any?) -> [String]? { (v as? [Any]).map { $0.compactMap { $0 as? String } } }
    func has(_ c: String) -> Bool { set.contains(c) }
    /// Datenbanken (Schema, Zeilen) und weitere Felder (props schema icon cover tags wide) bearbeiten
    var db: Bool { has(Self.db) }
    var subpages: Bool { has(Self.subpages) }
    var upload: Bool { has(Self.upload) }
    var versions: Bool { has(Self.versions) }
    var export: Bool { has(Self.export) }
    /// Anwesenheit (und damit das Live-Abo fuer note.changed/presence/comments.changed)
    var presence: Bool { has(Self.presence) || has(Self.live) }
    var chatgroups: Bool { has(Self.chatgroups) }
}

/// Zugriff auf die offene Seite - aus GET /api/notes/{id}. Aeltere Server (ohne "role") = eigene Seite.
struct PageAccess: Equatable {
    var role: ShareRole = .owner
    var owner: SharePerson?
    var shared = false
    var commentCounts: [String: Int] = [:]
    var commentsOpen = 0
    var commentsUnread = 0
    var commentsResolved = 0
    var updatedBy: SharePerson?
    var createdBy: SharePerson?
    /// Server kennt Kommentare (1.3)
    var commentsSupported = false
    /// Seite einer anderen Saga-Instanz (Foederation 2.0): ohne Faehigkeiten nur Titel/Inhalt aenderbar
    var remoteHost: String?
    /// 2.3: was der Server der fremden Seite kann (remote.caps) - Datenbanken, Unterseiten, Hochladen, Versionen, Export, Anwesenheit
    var remoteCaps = FedCaps()
    /// Eigener Server ab 2.3: Versionen und Verschieben (im geteilten Bereich) auch mit Rolle Bearbeiten
    var server23 = false

    init() {}
    init(page p: [String: Any], serverVersion: String? = nil) {
        remoteHost = (p["remote"] as? [String: Any])?["host"] as? String ?? ((ShareJSON.int(p["id"]) ?? 0) < 0 ? "" : nil)
        remoteCaps = FedCaps((p["remote"] as? [String: Any])?["caps"])
        server23 = serverVersion.map { !ShareLogic.versionLess($0, "2.3.0") } ?? false
        role = ShareRole.parse(p["role"]) ?? .owner
        owner = SharePerson(p["owner"])
        shared = ShareJSON.bool(p["shared"])
        if let c = p["comment_counts"] as? [String: Any] {
            commentsSupported = true
            for (k, v) in c { if let n = ShareJSON.int(v), n > 0 { commentCounts[k] = n } }
        }
        commentsOpen = ShareJSON.int(p["comments_open"]) ?? commentCounts.values.reduce(0, +)
        commentsUnread = ShareJSON.int(p["comments_unread"]) ?? 0
        commentsResolved = ShareJSON.int(p["comments_resolved"]) ?? 0
        updatedBy = SharePerson(p["updated_by"])
        createdBy = SharePerson(p["created_by"])
    }

    var foreign: Bool { role != .owner }
    /// Lesen/Kommentieren: Editor schreibgeschuetzt (kein Platzhalter, keine Griffe)
    var readOnly: Bool { !role.canEdit }
    var canComment: Bool { role.canComment }
    /// Kopfzeile fremder Seiten: "Geteilt von {name}" (nil auf eigenen Seiten)
    var sharedBy: String? { foreign ? (owner?.name ?? "") : nil }

    func allows(_ a: PageAction) -> Bool {
        // Seiten anderer Saga-Instanzen: nur, was deren Server kann (remote.caps, 2.3); die Rolle prueft er selbst
        if remote {
            switch a {
            case .addSubpage, .move: return remoteCaps.subpages && role.canEdit
            case .export: return remoteCaps.export
            case .versions: return remoteCaps.versions && role.canEdit
            case .wide: return remoteCaps.db && role.canEdit
            case .editIcon: return false                                // Symbol/Titelbild: Kopf der Seite (headerEditable)
            default: break
            }
        }
        // 2.3: Versionen ab Rolle Bearbeiten; Verschieben innerhalb des geteilten Bereichs (gleiche Freigaben prueft der Server)
        if (a == .versions || a == .move) && server23 && role.canEdit { return true }
        return ShareLogic.allows(role, a)
    }
    var remote: Bool { remoteHost != nil }
    /// Symbol, Titelbild und Schlagwoerter: fremde Seite nur, wenn deren Server mehr als Titel/Inhalt annimmt (Faehigkeit db)
    var remoteExtras: Bool { !remote || remoteCaps.db }
    /// Bilder/Dateien hochladen: fremde Seite nur mit Faehigkeit upload (Datei liegt beim Server der Seite)
    var canUpload: Bool { !remote || remoteCaps.upload }
    /// Anwesenheit/Live: fremde Seite nur mit Faehigkeit presence bzw. live
    var presence: Bool { !remote || remoteCaps.presence }

    /// "Angelegt von {name}" - nur, wenn es jemand anderes war
    var createdByOther: String? { createdBy.flatMap { $0.me || $0.name.isEmpty ? nil : $0.name } }
    /// "Zuletzt geaendert von {name} · {wann}" - nur, wenn es jemand anderes war
    var updatedByOther: String? { updatedBy.flatMap { $0.me || $0.name.isEmpty ? nil : $0.name } }
}

/// Seite in "Mit mir geteilt" (GET /api/shared): Eintrag mit Rolle, Eigentuemer, oberste geteilte Seite (root)
struct SharedInfo: Equatable {
    var enabled = true
    var received = 0
    init(enabled: Bool = true, received: Int = 0) { self.enabled = enabled; self.received = received }
    init?(data: Data) {
        guard let j = ShareJSON.obj(data) else { return nil }
        enabled = j["enabled"].map(ShareJSON.bool) ?? true; received = ShareJSON.int(j["received"]) ?? 0
    }
    /// Seitenleiste: Hinweis, wenn Teilen abgeschaltet ist, man aber Freigaben hat (sonst waere der Bereich still leer)
    func showOffHint(sharedCount: Int) -> Bool { !enabled && received > 0 && sharedCount == 0 }
}

// MARK: - Freigaben (Teilen-Dialog)

/// Weiterer Weg, auf dem eine Person die Seite sieht (Gruppe hier/darueber, eigene Freigabe darueber)
struct ShareVia: Equatable {
    var type: String
    var name: String
    var role: ShareRole
    var noteID: Int?
    var title: String?
    init(type: String, name: String, role: ShareRole, noteID: Int? = nil, title: String? = nil) {
        self.type = type; self.name = name; self.role = role; self.noteID = noteID; self.title = title
    }
    init?(_ v: Any?) {
        guard let d = v as? [String: Any], let r = ShareRole.parse(d["role"]) else { return nil }
        self.init(type: ShareJSON.str(d["type"]) ?? "user", name: ShareJSON.str(d["name"]) ?? "", role: r,
                  noteID: ShareJSON.int(d["note_id"]), title: d["title"] is NSNull ? nil : d["title"] as? String)
    }
    var isGroup: Bool { type == "group" }
}

struct ShareEntry: Identifiable, Equatable {
    var type: String
    var target: String
    var name: String
    var role: ShareRole
    var effective: ShareRole
    var via: [ShareVia]
    var members: Int?
    var avatar: String? = nil
    var id: String { type + ":" + target }
    var isGroup: Bool { type == "group" || type == "remote_group" }
    /// Person/Gruppe auf einer anderen Saga-Instanz (Foederation 2.0)
    var isRemote: Bool { type == "remote" || type == "remote_group" }

    init(type: String, target: String, name: String, role: ShareRole, effective: ShareRole? = nil, via: [ShareVia] = [], members: Int? = nil, avatar: String? = nil) {
        self.type = type; self.target = target; self.name = name; self.role = role; self.effective = effective ?? role; self.via = via; self.members = members
        self.avatar = avatar
    }
    init?(_ v: Any?) {
        guard let d = v as? [String: Any], let r = ShareRole.parse(d["role"]), let tg = ShareJSON.str(d["target"]) else { return nil }
        self.init(type: ShareJSON.str(d["type"]) ?? "user", target: tg, name: ShareJSON.str(d["name"]) ?? tg, role: r,
                  effective: ShareRole.parse(d["effective"]), via: (d["via"] as? [Any] ?? []).compactMap(ShareVia.init),
                  members: ShareJSON.int(d["members"]), avatar: ShareJSON.str(d["avatar"]))
    }
    /// Ueber eine Gruppe/Elternseite mehr Rechte als hier eingestellt -> Hinweis "(ueber X: Rolle)"
    var stronger: ShareVia? {
        guard !isGroup, let t = via.first, t.role.rank > role.rank else { return nil }
        return t
    }
}

struct InheritedShare: Identifiable, Equatable {
    var noteID: Int
    var title: String
    var type: String
    var target: String
    var name: String
    var role: ShareRole
    var id: String { "\(noteID):\(type):\(target)" }
    var isGroup: Bool { type == "group" }
    init?(_ v: Any?) {
        guard let d = v as? [String: Any], let r = ShareRole.parse(d["role"]), let n = ShareJSON.int(d["note_id"]) else { return nil }
        noteID = n; title = d["title"] as? String ?? ""; type = ShareJSON.str(d["type"]) ?? "user"
        target = ShareJSON.str(d["target"]) ?? ""; name = ShareJSON.str(d["name"]) ?? target; role = r
    }
}

/// Antwort von GET/PUT/DELETE /api/notes/{id}/shares
struct ShareState: Equatable {
    var enabled = true
    var scope = false
    var max = 200
    var shareable = true
    var shares: [ShareEntry] = []
    var inherited: [InheritedShare] = []
    var groups: [String] = []
    /// Teilen mit anderen Saga-Instanzen moeglich (Eingabe name@server anbieten)
    var federation = false

    init() {}
    init(json j: [String: Any]) {
        federation = ShareJSON.bool(j["federation"])
        enabled = j["enabled"].map(ShareJSON.bool) ?? true
        scope = ShareJSON.bool(j["scope"])
        max = ShareJSON.int(j["max"]) ?? 200
        shareable = j["shareable"].map(ShareJSON.bool) ?? true
        shares = (j["shares"] as? [Any] ?? []).compactMap(ShareEntry.init)
        inherited = (j["inherited"] as? [Any] ?? []).compactMap(InheritedShare.init)
        groups = (j["groups"] as? [Any] ?? []).compactMap { $0 as? String }
    }
    init?(data: Data) {
        guard let j = ShareJSON.obj(data) else { return nil }
        self.init(json: j)
    }
    /// Bestehende Freigaben: "type:target" -> Rolle (Treffer zeigen "hat Zugriff")
    var taken: [String: ShareRole] { Dictionary(shares.map { ($0.id, $0.role) }, uniquingKeysWith: { a, _ in a }) }
    /// Suche/Rolle bedienbar?
    var canAdd: Bool { enabled && shareable }
}

/// Treffer der Personensuche (GET /api/share/targets?q=)
struct ShareTarget: Identifiable, Equatable {
    var type: String
    var target: String
    var name: String
    /// Benutzername (Personen) - Gruppen zeigen "Gruppe"
    var username: String?
    /// Server bei Personen/Gruppen anderer Saga-Instanzen
    var host: String? = nil
    var avatar: String? = nil
    /// 2.3 (Adressbuch): fertig zum Anzeigen, "Anna Berg · b.example" (bei gleichen Namen mit Zusatz)
    var label: String? = nil
    var id: String { type + ":" + target }
    var isGroup: Bool { type == "group" || type == "remote_group" }
    var isRemote: Bool { type == "remote" || type == "remote_group" }
    /// Zweite Zeile eines Treffers anderer Saga-Server: Name · host (nie nur der Name), sonst wie bisher
    var remoteLine: String? {
        guard isRemote else { return nil }
        if let l = label, !l.isEmpty { return l }
        return host.map { name + " · " + $0 }
    }
}

/// Was das Entfernen einer Freigabe bewirkt (Text der Rueckfrage)
enum RemoveEffect: Equatable {
    /// "{name} behaelt ueber {via} die Rolle {role}."
    case keeps(name: String, via: ShareVia)
    /// "Die Gruppe {name} verliert sofort den Zugriff ..."
    case groupLoses(name: String)
    /// "{name} verliert sofort den Zugriff ..."
    case userLoses(name: String)
}

// MARK: - Kommentare

struct CommentItem: Identifiable, Equatable {
    var id: Int
    var threadID: Int?
    var blockID: String
    var author: SharePerson
    var body: String
    var created: Double
    var edited: Double?
    var mine: Bool
    var deleted: Bool
    init(id: Int, threadID: Int? = nil, blockID: String = "", author: SharePerson, body: String, created: Double = 0, edited: Double? = nil, mine: Bool = false, deleted: Bool = false) {
        self.id = id; self.threadID = threadID; self.blockID = blockID; self.author = author; self.body = body
        self.created = created; self.edited = edited; self.mine = mine; self.deleted = deleted
    }
    init?(_ v: Any?) {
        guard let d = v as? [String: Any], let i = ShareJSON.int(d["id"]) else { return nil }
        self.init(id: i, threadID: ShareJSON.int(d["thread_id"]), blockID: d["block_id"] as? String ?? "",
                  author: SharePerson(d["author"]) ?? SharePerson(name: ""), body: d["body"] as? String ?? "",
                  created: ShareJSON.num(d["created"]) ?? 0, edited: ShareJSON.num(d["edited"]),
                  mine: ShareJSON.bool(d["mine"]), deleted: ShareJSON.bool(d["deleted"]))
    }
}

struct CommentThread: Identifiable, Equatable {
    var id: Int
    var blockID: String
    var blockExists = true
    var resolvedAt: Double?
    var resolvedBy: SharePerson?
    var unread = false
    var comments: [CommentItem] = []

    init(id: Int, blockID: String = "", blockExists: Bool = true, resolvedAt: Double? = nil, resolvedBy: SharePerson? = nil, unread: Bool = false, comments: [CommentItem] = []) {
        self.id = id; self.blockID = blockID; self.blockExists = blockExists; self.resolvedAt = resolvedAt
        self.resolvedBy = resolvedBy; self.unread = unread; self.comments = comments
    }
    init?(_ v: Any?) {
        guard let d = v as? [String: Any], let i = ShareJSON.int(d["id"]) else { return nil }
        self.init(id: i, blockID: d["block_id"] as? String ?? "", blockExists: d["block_exists"].map(ShareJSON.bool) ?? true,
                  resolvedAt: ShareJSON.num(d["resolved_at"]), resolvedBy: SharePerson(d["resolved_by"]), unread: ShareJSON.bool(d["unread"]),
                  comments: (d["comments"] as? [Any] ?? []).compactMap(CommentItem.init))
    }
    var resolved: Bool { resolvedAt != nil }
    /// Kommentar zur ganzen Seite (kein Block)
    var wholePage: Bool { blockID.isEmpty }
    /// Index des Kommentars mit dem Ungelesen-Punkt: der letzte von jemand anderem (nur, solange ungelesen)
    var unreadIndex: Int? {
        guard unread else { return nil }
        return comments.lastIndex { !$0.mine }
    }
    /// Unter dem ersten Kommentar haben andere geantwortet (dann bleiben Antworten beim Loeschen stehen)
    var othersReplied: Bool {
        guard let first = comments.first else { return false }
        return comments.dropFirst().contains { $0.author.username == nil || $0.author.username != first.author.username }
    }
}

/// Antwort von GET /api/notes/{id}/comments
struct CommentsState: Equatable {
    var role: ShareRole = .owner
    var canComment = true
    var isOwner = true
    var threads: [CommentThread] = []
    var resolvedCount = 0
    var max = ShareLogic.commentMax

    init() {}
    init?(data: Data) {
        guard let j = ShareJSON.obj(data) else { return nil }
        role = ShareRole.parse(j["role"]) ?? .owner
        canComment = j["can_comment"].map(ShareJSON.bool) ?? role.canComment
        isOwner = j["is_owner"].map(ShareJSON.bool) ?? (role == .owner)
        threads = (j["threads"] as? [Any] ?? []).compactMap(CommentThread.init)
        resolvedCount = ShareJSON.int(j["resolved_count"]) ?? 0
        max = ShareJSON.int(j["max"]) ?? ShareLogic.commentMax
    }
    var open: [CommentThread] { threads.filter { !$0.resolved } }
}

/// Aktionen im ⋯-Menue eines Kommentars
enum CommentAction: Equatable { case edit, delete, deleteThread }

/// Was "Loeschen" wegnimmt (Text der Rueckfrage)
enum CommentDeleteKind: Equatable {
    /// "Unterhaltung loeschen?" / "Der erste Kommentar und alle Antworten werden geloescht."
    case wholeThread
    /// "Der Kommentar wird geloescht, die Antworten bleiben stehen."
    case keepReplies
    /// "Der Kommentar wird geloescht."
    case single
}

/// Leerer Zustand der Kommentarliste - Text wie im Web
enum CommentsEmpty: Equatable {
    case blockNone            // Keine Kommentare zu diesem Block. (nur lesen)
    case blockNoOpen          // Keine offenen Kommentare zu diesem Block.
    case blockNoneYet         // Noch keine Kommentare zu diesem Block.
    case noOpen(Int)          // Keine offenen Kommentare · n erledigt
    case noneYet              // Noch keine Kommentare.
    case tapToComment         // Noch keine Kommentare. Tippe auf einen Absatz, um ihn zu kommentieren.
    case noneYetHint          // Noch keine Kommentare. Kommentiere einen Absatz ... oder schreibe unten ...
}

/// Ergebnis eines fehlgeschlagenen Speicherns
enum SaveFailure: Equatable {
    /// 409: jemand hat inzwischen gespeichert (by: Name, wenn es jemand anderes war)
    case conflict(by: String?)
    /// 404 auf einer fremden Seite: Freigabe entzogen / Teilen abgeschaltet
    case lostAccess
    /// 403 auf einer fremden Seite: Rolle auf Lesen/Kommentieren gefallen
    case readOnlyNow
    case other(Int)
}

enum ShareLogic {
    static let commentMax = 2000
    /// Personensuche ab wie vielen Zeichen: Server ab 2.3 ab 1 (bei 1-2 Zeichen liefert er nur genaue Treffer, z. B. "hd"),
    /// aeltere Server (und Version noch unbekannt) wie bisher ab 3 mit Hinweis
    static func minQuery(server: String?) -> Int { server.map { !versionLess($0, "2.3.0") } == true ? 1 : 3 }
    /// "1.2.0" < "1.10.0" (SemVer, Zusaetze wie -beta werden ignoriert) - wie Store.versionLess
    static func versionLess(_ a: String, _ b: String) -> Bool {
        func p(_ s: String) -> [Int] {
            let core = s.trimmingCharacters(in: CharacterSet(charactersIn: "vV ")).split(separator: "-").first.map(String.init) ?? ""
            let n = core.split(separator: ".").prefix(3).map { Int($0) ?? 0 }
            return n + Array(repeating: 0, count: max(0, 3 - n.count))
        }
        return p(a).lexicographicallyPrecedes(p(b))
    }
    /// Bloecke, die auf fremden Seiten nur einen neutralen Platzhalter zeigen (Daten gehoeren dem Konto, das schaut)
    static let ownerOnlyBlocks: Set<String> = ["tasks", "calendar", "immich"]
    /// Text eines Verweises auf eine Seite ohne Zugriff (der Server setzt ihn in der Sprache der Anfrage ein)
    static let noAccessTexts: Set<String> = ["Seite ohne Zugriff", "Page without access", "Página sin acceso"]

    static func allows(_ r: ShareRole, _ a: PageAction) -> Bool {
        switch a {
        case .delete, .move, .archive, .publish, .share, .versions, .template, .favorite, .lock, .duplicate, .editIcon: return r == .owner
        case .wide, .addSubpage, .editContent: return r.canEdit
        case .writeComment: return r.canComment
        case .comments, .export, .copyLink, .reload: return true
        }
    }

    static func isOwnerOnlyBlock(_ type: String, foreign: Bool) -> Bool { foreign && ownerOnlyBlocks.contains(type) }

    /// Verweise auf Seiten ohne Zugriff im Inline-HTML -> <na>🔒 Text</na> (ohne Link, gedaempft angezeigt).
    /// `keepRef` (Editor): <na href="/notes#12">🔒 Text</na> - die Seiten-ID bleibt erhalten, damit beim Speichern
    /// wieder genau der gespeicherte Verweis (href + Text) entsteht (siehe `noAccessPrefix`/`unmasked`).
    static func markMasked(_ html: String, keepRef: Bool = false) -> String {
        guard html.contains("/notes#") else { return html }
        var out = html
        for t in noAccessTexts {
            let esc = NSRegularExpression.escapedPattern(for: t)
            guard let re = try? NSRegularExpression(pattern: #"<a\s[^>]*href="/notes#(-?[0-9]+)"[^>]*>"# + esc + "</a>") else { continue }
            let open = keepRef ? #"<na href="/notes#$1">"# : "<na>"
            out = re.stringByReplacingMatches(in: out, range: NSRange(out.startIndex..., in: out), withTemplate: open + noAccessPrefix + NSRegularExpression.escapedTemplate(for: t) + "</na>")
        }
        return out
    }
    /// Vorangestelltes Schloss vor "Seite ohne Zugriff" (nur Darstellung)
    static let noAccessPrefix = "🔒 "
    /// Text eines maskierten Verweises zurueck in den gespeicherten Wortlaut (ohne Schloss)
    static func unmasked(_ shown: String) -> String {
        shown.hasPrefix(noAccessPrefix) ? String(shown.dropFirst(noAccessPrefix.count)) : shown
    }

    // MARK: Teilen

    static func searchReady(_ q: String, server: String?) -> Bool { q.trimmingCharacters(in: .whitespacesAndNewlines).count >= minQuery(server: server) }
    /// Hinweis "Mindestens 3 Zeichen." nur bei 1-2 eingegebenen Zeichen auf Servern vor 2.3 (leer: kein Hinweis)
    static func minHint(_ q: String, server: String?) -> Bool {
        (1..<max(1, minQuery(server: server))).contains(q.trimmingCharacters(in: .whitespacesAndNewlines).count)
    }

    static func parseTargets(_ d: Data) -> [ShareTarget] {
        guard let j = ShareJSON.obj(d) else { return [] }
        let users = (j["users"] as? [[String: Any]] ?? []).compactMap { u -> ShareTarget? in
            guard let n = ShareJSON.str(u["username"]) else { return nil }
            return ShareTarget(type: "user", target: n, name: ShareJSON.str(u["name"]) ?? n, username: n, avatar: ShareJSON.str(u["avatar"]))
        }
        let groups = (j["groups"] as? [[String: Any]] ?? []).compactMap { g -> ShareTarget? in
            guard let n = ShareJSON.str(g["name"]) else { return nil }
            return ShareTarget(type: "group", target: n, name: n, username: nil)
        }
        // Foederation (2.0): nur genaue Adressen verbundener Server (name@server)
        let remote = (j["remote"] as? [[String: Any]] ?? []).compactMap { r -> ShareTarget? in
            guard let t = ShareJSON.str(r["type"]), t == "remote" || t == "remote_group", let tg = ShareJSON.str(r["target"]) else { return nil }
            return ShareTarget(type: t, target: tg, name: ShareJSON.str(r["name"]) ?? tg, username: nil, host: ShareJSON.str(r["host"]), avatar: ShareJSON.str(r["avatar"]),
                               label: ShareJSON.str(r["label"]))
        }
        return users + groups + remote
    }
    /// Grund, warum die Suche nach name@server nichts fand (GET /api/share/targets, Feld remote_hint ab Server 2.2);
    /// aeltere Server ohne Feld -> nil (dann wie bisher "Niemand gefunden.")
    static func remoteHint(_ d: Data) -> String? {
        guard let h = ShareJSON.obj(d).flatMap({ ShareJSON.str($0["remote_hint"]) })?.trimmingCharacters(in: .whitespacesAndNewlines), !h.isEmpty else { return nil }
        return h
    }

    /// Rueckfrage beim Entfernen: nennt den verbleibenden Zugriff (ueber Gruppe oder Elternseite)
    static func removeEffect(_ e: ShareEntry) -> RemoveEffect {
        if !e.isGroup, let top = e.via.first { return .keeps(name: e.name, via: top) }
        return e.isGroup ? .groupLoses(name: e.name) : .userLoses(name: e.name)
    }

    /// Gruppen-Chips bei leerem Suchfeld
    static func showGroupChips(_ s: ShareState, query: String) -> Bool {
        !s.groups.isEmpty && query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && s.canAdd
    }

    // MARK: Kommentare

    /// Sichtbare Unterhaltungen: alle bzw. die eines Blocks; erledigte nur mit "Erledigte zeigen"
    static func visible(_ threads: [CommentThread], block: String?, showResolved: Bool) -> [CommentThread] {
        threads.filter { (showResolved || !$0.resolved) && (block == nil || $0.blockID == block) }
    }

    /// Zahl am Block: offene Unterhaltungen je Block (+ ungelesen?)
    static func blockCounts(_ threads: [CommentThread]) -> (counts: [String: Int], unread: Set<String>) {
        var c: [String: Int] = [:], u = Set<String>()
        for t in threads where !t.resolved && !t.blockID.isEmpty {
            c[t.blockID, default: 0] += 1
            if t.unread { u.insert(t.blockID) }
        }
        return (c, u)
    }

    /// Unterhaltungen, die beim Anzeigen als gelesen gemeldet werden (POST /api/comments/{id}/seen)
    static func toMarkSeen(_ shown: [CommentThread]) -> [Int] { shown.filter { $0.unread && !$0.resolved }.map(\.id) }

    /// ⋯-Menue: eigene bearbeiten/loeschen; Eigentuemer der Seite darf fremde loeschen und ganze Unterhaltungen
    static func actions(_ c: CommentItem, in t: CommentThread, first: Bool, role: ShareRole) -> [CommentAction] {
        let mod = role == .owner, mine = c.mine && role.canComment && !c.deleted
        let others = first && t.othersReplied
        var out: [CommentAction] = []
        if mine { out.append(.edit) }
        if !c.deleted && (mine || mod) { out.append(.delete) }
        if first && mod && (others || c.deleted) { out.append(.deleteThread) }
        return out
    }

    static func deleteKind(first: Bool, thread: CommentThread) -> CommentDeleteKind {
        let others = first && thread.othersReplied
        return first && !others ? .wholeThread : (others ? .keepReplies : .single)
    }

    static func empty(block: String?, canComment: Bool, role: ShareRole, resolvedCount: Int, showResolved: Bool, touch: Bool) -> CommentsEmpty {
        if block != nil {
            if !canComment { return .blockNone }
            return resolvedCount > 0 && !showResolved ? .blockNoOpen : .blockNoneYet
        }
        if resolvedCount > 0 && !showResolved { return .noOpen(resolvedCount) }
        if !canComment { return .noneYet }
        if touch && role == .comment { return .tapToComment }
        return .noneYetHint
    }

    /// Zaehler "n von 2000" erst kurz vor der Grenze
    static func showCounter(_ len: Int, max: Int = commentMax) -> Bool { len > max - 200 }
    /// Kommentar sendbar?
    static func canSend(_ text: String, max: Int = commentMax) -> Bool {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        return !t.isEmpty && t.count <= max
    }

    /// Links in Kommentaren (nur http/https) - Bereiche im Text; Satzzeichen am Ende gehoeren nicht dazu
    static func links(_ s: String) -> [(range: Range<String.Index>, url: URL)] {
        guard let re = try? NSRegularExpression(pattern: #"\bhttps?://[^\s<>"']+"#) else { return [] }
        var out: [(Range<String.Index>, URL)] = []
        for m in re.matches(in: s, range: NSRange(s.startIndex..., in: s)) {
            guard let r = Range(m.range, in: s) else { continue }
            var u = String(s[r])
            while let l = u.last, ").,;:!?]".contains(l) { u.removeLast() }
            guard let url = URL(string: u) else { continue }
            out.append((r.lowerBound..<s.index(r.lowerBound, offsetBy: u.count), url))
        }
        return out
    }

    // MARK: Speichern

    /// Fehler beim Speichern einordnen. `foreign`: Seite gehoert jemand anderem.
    static func saveFailure(code: Int, data: Data?, foreign: Bool) -> SaveFailure {
        if code == 409 {
            let by = data.flatMap(ShareJSON.obj).flatMap { ShareJSON.str($0["by"]) }
            return .conflict(by: by)
        }
        if foreign && code == 404 { return .lostAccess }
        if foreign && code == 403 { return .readOnlyNow }
        return .other(code)
    }

    /// Text der Seite zum Kopieren (Entwurf nach Konflikt, Zugriff verloren): Titel + Bloecke, so wie sie im Editor stehen.
    /// `strip` macht aus Inline-HTML Klartext.
    static func plainText(title: String, blocks: NSArray, strip: (String) -> String) -> String {
        var out: [String] = []
        func items(_ arr: NSArray?, _ depth: Int) {
            for case let it as NSDictionary in (arr ?? []) {
                let t = strip(it["content"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
                if !t.isEmpty { out.append(String(repeating: "  ", count: depth) + t) }
                items(it["items"] as? NSArray, depth + 1)
            }
        }
        func walk(_ arr: NSArray) {
            for case let b as NSDictionary in arr {
                let d = b["data"] as? NSDictionary ?? [:]
                switch b["type"] as? String ?? "" {
                case "list": items(d["items"] as? NSArray, 0)
                case "code": if let c = d["code"] as? String, !c.isEmpty { out.append(c) }
                case "toggle":
                    let t = strip(d["title"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
                    if !t.isEmpty { out.append(t) }
                    walk(d["blocks"] as? NSArray ?? [])
                case "columns": for case let c as NSDictionary in (d["cols"] as? NSArray ?? []) { walk(c["blocks"] as? NSArray ?? []) }
                case "table":
                    for case let row as NSArray in (d["content"] as? NSArray ?? []) {
                        let cells = row.compactMap { ($0 as? String).map(strip) }.filter { !$0.isEmpty }
                        if !cells.isEmpty { out.append(cells.joined(separator: " | ")) }
                    }
                default:
                    let t = strip(d["text"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
                    if !t.isEmpty { out.append(t) }
                }
            }
        }
        walk(blocks)
        let tt = title.trimmingCharacters(in: .whitespacesAndNewlines)
        return ((tt.isEmpty ? "" : tt + "\n\n") + out.joined(separator: "\n\n")).trimmingCharacters(in: .whitespacesAndNewlines)
    }
}

/// 2.4 (Testrunde W4): Server zeigen Anmeldenamen anderer Konten nicht mehr – Personensuche, Freigaben, Kommentare usw.
/// liefern einen pseudonymen Handle ('~' + 16 Zeichen) als Kennung, `name` ist Anzeigename, oeffentlicher Name oder
/// derselbe Handle. Chat-Mitglieder behalten `username` (Chats v1 binden Schluessel daran), dazu `ref` (Handle wie in der
/// Personensuche). Die Apps zeigen den Anmeldenamen anderer nie; Kennungen bleiben undurchsichtig.
enum PersonRef {
    /// Name zum Zeigen: name, sonst ref (Handle), zuletzt die Kennung selbst (aeltere Server liefern dort den Namen)
    static func display(name: String?, ref: String?, id: String) -> String {
        if let n = name?.trimmingCharacters(in: .whitespaces), !n.isEmpty { return n }
        if let r = ref, !r.isEmpty { return r }
        return id
    }
    /// Treffer der Personensuche (Feld username = Handle oder bei aelteren Servern der Benutzername) schon Mitglied?
    static func isMember(_ hit: String, members: [(username: String, ref: String?)]) -> Bool {
        members.contains { $0.username == hit || ($0.ref ?? "") == hit }
    }
}
