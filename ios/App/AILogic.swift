import Foundation
import CryptoKit

// KI-Assistent (Saga 1.6): reine Logik (mit swiftc pruefbar). Der Server spricht mit dem Modell, die App nie direkt.
// GET /api/ai/status, POST /api/ai/ask | /api/ai/page/{id} (text/event-stream), /api/ai/history, PUT /api/ai/settings.

struct AIStatus: Equatable {
    var enabled = false
    var host = ""
    var quota: Int?
    var used = 0
    var quotaLeft: Int?
    var historyAllowed = false
    var history = false
    var notice = ""
    var targets: [String] = []
    var qMax = 1000
    var blocksMax = 50
    /// 2.1: Name + Profilbild der KI (nil = aelterer Server ohne Persona)
    var persona: AIPersona?
    /// 2.1: Admin hat "KI in Chats erlauben" eingeschaltet
    var chat = false
    init() {}
    init?(data: Data) {
        guard let j = ShareJSON.obj(data) else { return nil }
        enabled = ShareJSON.bool(j["enabled"])
        host = j["host"] as? String ?? ""
        quota = ShareJSON.int(j["quota"]); used = ShareJSON.int(j["used"]) ?? 0; quotaLeft = ShareJSON.int(j["quota_left"])
        historyAllowed = ShareJSON.bool(j["history_allowed"]); history = ShareJSON.bool(j["history"])
        notice = j["notice"] as? String ?? ""
        targets = (j["targets"] as? [Any] ?? []).compactMap { $0 as? String }
        qMax = ShareJSON.int(j["q_max"]) ?? 1000; blocksMax = ShareJSON.int(j["blocks_max"]) ?? 50
        persona = AIPersona(j["persona"])
        chat = ShareJSON.bool(j["chat"])
    }
}

/// Persona der KI (2.1, GET /api/ai/persona, /api/ai/status.persona, Live-Ereignis ai.persona.changed)
struct AIPersona: Equatable {
    /// Anzeigename; ohne eigenen Namen der Standard in der Sprache der Anfrage (Accept-Language = App-Sprache)
    var name: String
    var customName = false
    /// relative Adresse /api/ai/avatar?v=… oder nil = neutrales Symbol
    var avatar: String?
    var avatarEtag: String?
    /// nur /api/ai/persona: Assistent fuer dieses Konto an?
    var enabled: Bool?
    init(name: String, customName: Bool = false, avatar: String? = nil, avatarEtag: String? = nil, enabled: Bool? = nil) {
        self.name = name; self.customName = customName; self.avatar = avatar; self.avatarEtag = avatarEtag; self.enabled = enabled
    }
    init?(_ v: Any?) {
        guard let d = v as? [String: Any], let n = (d["name"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines), !n.isEmpty else { return nil }
        name = String(n.prefix(60)); customName = ShareJSON.bool(d["custom_name"])
        avatar = AIPersona.avatarPath(d["avatar"] as? String)
        avatarEtag = ShareJSON.str(d["avatar_etag"])
        enabled = d["enabled"].map(ShareJSON.bool)
    }
    static let avatarBase = "/api/ai/avatar"
    /// nur die eigene Adresse des Servers (/api/ai/avatar[?…]) - nie fremde Hosts
    static func avatarPath(_ s: String?) -> String? {
        guard let a = s?.trimmingCharacters(in: .whitespaces), !a.contains("://"), !a.hasPrefix("//"),
              a == avatarBase || a.hasPrefix(avatarBase + "?") else { return nil }
        return a
    }
    /// Live-Ereignis (ohne enabled): Name/Bild uebernehmen, "enabled" bleibt wie bisher
    func merged(_ live: AIPersona) -> AIPersona {
        var p = live; p.enabled = live.enabled ?? enabled
        return p
    }
}

/// Server-Beleg einer KI-Antwort im Chat (GET /api/chats/{cid}/messages: "ai")
struct AIReplyMeta: Equatable {
    var ref: String; var sha256: String; var by: String?; var qMsg: String?
    init(ref: String, sha256: String, by: String? = nil, qMsg: String? = nil) { self.ref = ref; self.sha256 = sha256; self.by = by; self.qMsg = qMsg }
    init?(_ v: Any?) {
        guard let d = v as? [String: Any], let r = d["ref"] as? String, !r.isEmpty, let h = d["sha256"] as? String, !h.isEmpty else { return nil }
        self.init(ref: r, sha256: h, by: ShareJSON.str(d["by"]), qMsg: ShareJSON.str(d["q_msg"]))
    }
}

/// Eintrag fuer den mitgeschickten Verlauf (aelteste zuerst)
struct AIChatLine: Equatable { var id: String; var name: String; var text: String }

/// KI im Chat - nur auf Zuruf (2.1): Erwaehnung, Verlauf, Pruefung empfangener Antworten. Reine Logik.
enum AIChatLogic {
    static let historyMax = 12
    static let lineMax = 600
    static let qMax = 1000

    /// "@Name" in der Nachricht? Gross-/Kleinschreibung egal; davor Anfang, Leerraum oder ( [ " '; danach kein
    /// Buchstabe/keine Ziffer/kein Unterstrich (wie der Server)
    static func mentions(_ text: String, name: String) -> Bool {
        let n = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !n.isEmpty else { return false }
        let p = #"(?:^|(?<=[\s(\["']))@"# + NSRegularExpression.escapedPattern(for: n) + #"(?![\p{L}\p{N}_])"#
        guard let re = try? NSRegularExpression(pattern: p, options: [.caseInsensitive]) else { return false }
        return re.firstMatch(in: text, range: NSRange(text.startIndex..., in: text)) != nil
    }

    /// Verlauf vor der Frage: hoechstens 12 Nachrichten, aelteste zuerst, je hoechstens 600 Zeichen, ohne leere
    static func history(_ lines: [AIChatLine], before qid: String?) -> [AIChatLine] {
        var all = lines
        if let qid, let i = all.firstIndex(where: { $0.id == qid }) { all = Array(all[..<i]) }
        let usable = all.filter { !$0.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
        return usable.suffix(historyMax).map { AIChatLine(id: $0.id, name: String($0.name.prefix(80)), text: String($0.text.prefix(lineMax))) }
    }
    /// Sende-Body fuer POST /api/chats/{cid}/ai
    static func askBody(q: String, msgID: String?, history: [AIChatLine]) -> [String: Any] {
        var b: [String: Any] = ["q": String(q.prefix(qMax)), "history": history.map { ["name": $0.name, "text": $0.text] }]
        if let msgID, msgID.count == 32, msgID.allSatisfy(\.isHexDigit) { b["msg_id"] = msgID }
        return b
    }

    /// SHA-256 (hex, klein) ueber die UTF-8-Bytes
    static func sha256Hex(_ s: String) -> String { SHA256.hash(data: Data(s.utf8)).map { String(format: "%02x", $0) }.joined() }
    /// Text wie der Server ihn hasht: ohne Leerraum am Anfang/Ende
    static func normalized(_ s: String) -> String { s.trimmingCharacters(in: .whitespacesAndNewlines) }

    /// Empfangene Nachricht: keine KI-Antwort, bestaetigte KI-Antwort oder "gibt sich als KI aus" (Warnung)
    enum ReplyState: Equatable { case none, ok, bad }
    static func check(bodyRef: String?, text: String?, meta: AIReplyMeta?) -> ReplyState {
        guard let bodyRef else { return .none }
        guard let meta, let text, bodyRef == meta.ref else { return .bad }
        return sha256Hex(normalized(text)) == meta.sha256.lowercased() ? .ok : .bad
    }

    // MARK: @-Vorschlag beim Tippen

    /// "@teil" am Ende der Eingabe (davor Anfang oder Leerraum; bis 30 Zeichen ohne Leerraum/@) -> "teil"
    static func mentionQuery(_ text: String) -> String? {
        guard let at = text.lastIndex(of: "@") else { return nil }
        if at > text.startIndex, !text[text.index(before: at)].isWhitespace { return nil }
        let q = text[text.index(after: at)...]
        guard q.count <= 30, !q.contains(where: { $0.isWhitespace || $0 == "@" }) else { return nil }
        return String(q)
    }
    /// KI als (ersten) Vorschlag zeigen?
    static func suggests(query: String?, name: String) -> Bool {
        guard let q = query, !name.isEmpty else { return false }
        return q.isEmpty || name.lowercased().hasPrefix(q.lowercased())
    }
    /// "@teil" am Ende durch "@Name " ersetzen
    static func complete(_ text: String, name: String) -> String {
        guard let q = mentionQuery(text) else { return text + (text.isEmpty || text.last?.isWhitespace == true ? "" : " ") + "@" + name + " " }
        return String(text.dropLast(q.count + 1)) + "@" + name + " "
    }

    /// Wer darf die KI im Chat erlauben? Freie Gruppe: Chat-Admins; Direkt-Chat: beide; Seiten-Chat: Eigentuemer der
    /// Seite; Gruppen-Chat: Saga-Admins (die App kennt das nicht sicher -> anbieten, der Server entscheidet)
    static func mayToggle(kind: String, role: String, ownsPage: Bool) -> Bool {
        switch kind {
        case "free": return role == "admin"
        case "dm", "group": return true
        case "page": return ownsPage
        default: return false
        }
    }
}

struct AISource: Identifiable, Equatable {
    var n: Int; var pageID: Int?; var title: String; var owner: String?
    var id: Int { n }
    init(n: Int, pageID: Int?, title: String, owner: String? = nil) { self.n = n; self.pageID = pageID; self.title = title; self.owner = owner }
    init?(_ v: Any?) {
        guard let d = v as? [String: Any], let n = ShareJSON.int(d["n"]) else { return nil }
        self.init(n: n, pageID: ShareJSON.int(d["id"]), title: d["title"] as? String ?? "", owner: ShareJSON.str(d["owner"]))
    }
}

/// Block, den "Verbessern"/"Uebersetzen" geliefert hat ({id, type, replaceable})
struct AIBlock: Equatable { var id: String; var type: String; var replaceable: Bool }

/// Ende eines Stroms (done)
struct AIDone: Equatable {
    var quotaLeft: Int?
    var tasks: [String] = []
    var blocks: [AIBlock] = []
    var segments: [String]?
    var truncated = false
    init(_ d: [String: Any]) {
        quotaLeft = ShareJSON.int(d["quota_left"])
        tasks = (d["tasks"] as? [Any] ?? []).compactMap { $0 as? String }
        blocks = (d["blocks"] as? [Any] ?? []).compactMap { v -> AIBlock? in
            guard let b = v as? [String: Any], let id = b["id"] as? String else { return nil }
            return AIBlock(id: id, type: b["type"] as? String ?? "", replaceable: ShareJSON.bool(b["replaceable"]))
        }
        segments = (d["segments"] as? [Any])?.compactMap { $0 as? String }
        truncated = ShareJSON.bool(d["truncated"])
    }
}

enum AILogic {
    /// Antworttext in Stuecke: Klartext und [n]-Verweise (nur, wenn es Quelle n gibt) - nie Markdown/Links aus dem Modell
    enum Piece: Equatable { case text(String), cite(Int, pageID: Int?) }
    static func pieces(_ text: String, sources: [AISource]) -> [Piece] {
        guard let re = try? NSRegularExpression(pattern: #"\[(\d{1,3})\]"#) else { return [.text(text)] }
        var out: [Piece] = [], pos = text.startIndex
        let byN = Dictionary(sources.map { ($0.n, $0) }, uniquingKeysWith: { a, _ in a })
        for m in re.matches(in: text, range: NSRange(text.startIndex..., in: text)) {
            guard let r = Range(m.range, in: text), let nr = Range(m.range(at: 1), in: text), let n = Int(text[nr]), let s = byN[n] else { continue }
            if pos < r.lowerBound { out.append(.text(String(text[pos..<r.lowerBound]))) }
            out.append(.cite(n, pageID: s.pageID)); pos = r.upperBound
        }
        if pos < text.endIndex { out.append(.text(String(text[pos...]))) }
        return out
    }
    /// "Ersetzen": nur Rolle owner/edit, Seite nicht gesperrt, Segmente da und alle Bloecke ersetzbar (gleiche Anzahl)
    static func canReplace(canEdit: Bool, locked: Bool, done: AIDone?) -> Bool {
        guard canEdit, !locked, let d = done, let seg = d.segments, !d.blocks.isEmpty, seg.count == d.blocks.count else { return false }
        return d.blocks.allSatisfy(\.replaceable)
    }
    static func canInsertBelow(canEdit: Bool, locked: Bool, done: AIDone?) -> Bool { canEdit && !locked && done != nil }
    /// Frage gueltig (1 bis q_max Zeichen)?
    static func validQuestion(_ q: String, max: Int) -> Bool {
        let t = q.trimmingCharacters(in: .whitespacesAndNewlines)
        return !t.isEmpty && t.count <= max
    }
    /// "Heute noch {n} von {m} Anfragen" (nil = unbegrenzt)
    static func quota(_ s: AIStatus) -> (left: Int, of: Int)? {
        guard let q = s.quota, q > 0 else { return nil }
        return (max(0, s.quotaLeft ?? (q - s.used)), q)
    }
    /// Nach dem Strom: Kontingent aus "done" uebernehmen
    static func updated(_ s: AIStatus, quotaLeft: Int?) -> AIStatus {
        var x = s
        if let q = quotaLeft { x.quotaLeft = q }
        return x
    }
}
