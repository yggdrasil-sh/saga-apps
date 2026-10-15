import Foundation

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
