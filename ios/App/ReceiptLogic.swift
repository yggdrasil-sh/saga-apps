import Foundation

// Gelesen-Bestaetigungen und Online-Status (Saga 2.4, docs/APP-API.md "Gelesen-Bestaetigungen und Online-Status").
// Reine Logik ohne SwiftUI/libsodium (mit ./Tests/Logic/run.sh geprueft): Faehigkeiten des Servers, Einstellungen
// (gegenseitig), wann eine Bestaetigung faellig ist, Haken an eigenen Nachrichten, "Gelesen von", Online-Abo.
// Krypto: ReceiptCrypto.swift, Ablauf: ChatReceipts.swift.

enum ReceiptLogic {
    // MARK: Faehigkeiten und Einstellungen

    /// Eigene Einstellungen (Standard an). Gegenseitig: wer etwas abschaltet, sendet es nicht und sieht es auch nicht.
    struct Privacy: Equatable { var receipts = true; var online = true }
    struct Limits: Equatable { var receiptGap = 2.0; var presenceTTL = 90.0; var presenceUsers = 50; var lastRound = 300.0 }
    /// Was der Server kann (aus GET /api/chat/me) - aeltere Server: beides false -> ausblenden
    struct Caps: Equatable {
        var receipts = false
        var online = false
        var privacy = Privacy()
        var limits = Limits()
        /// Bestaetigungen senden und anzeigen
        var receiptsOn: Bool { receipts && privacy.receipts }
        /// Online-Status abonnieren und anzeigen
        var onlineOn: Bool { online && privacy.online }
        /// Abschnitt "Datenschutz" zeigen
        var any: Bool { receipts || online }
    }
    static func caps(_ me: [String: Any]?) -> Caps {
        guard let me else { return Caps() }
        var c = Caps()
        let f = (me["features"] as? [Any] ?? []).compactMap { $0 as? String }
        c.receipts = f.contains("receipts"); c.online = f.contains("online")
        if let p = me["privacy"] { c.privacy = privacy(p) }
        if let l = me["status_limits"] as? [String: Any] {
            c.limits.receiptGap = ShareJSON.num(l["receipt_gap"]) ?? c.limits.receiptGap
            c.limits.presenceTTL = ShareJSON.num(l["presence_ttl"]) ?? c.limits.presenceTTL
            c.limits.presenceUsers = ShareJSON.int(l["presence_users"]) ?? c.limits.presenceUsers
            c.limits.lastRound = ShareJSON.num(l["last_round"]) ?? c.limits.lastRound
        }
        return c
    }
    /// {receipts, online} aus GET/PUT /api/chat/privacy bzw. chat.privacy; fehlende Felder = an
    static func privacy(_ v: Any?) -> Privacy {
        guard let d = v as? [String: Any] else { return Privacy() }
        return Privacy(receipts: d["receipts"].map(ShareJSON.bool) ?? true, online: d["online"].map(ShareJSON.bool) ?? true)
    }
    /// Body fuer PUT /api/chat/privacy (nur das geaenderte Feld)
    static func privacyBody(receipts: Bool? = nil, online: Bool? = nil) -> [String: Any] {
        var b: [String: Any] = [:]
        if let receipts { b["receipts"] = receipts }
        if let online { b["online"] = online }
        return b
    }

    /// Chat-Eigenschaften, die fuer Gelesen/Online zaehlen
    struct ChatFlags: Equatable {
        var kind = "dm"
        var fed = false
        var request = false
        var readonly = false
        /// v2: der andere Server kann es (receipts_ok/online_ok; fehlt = true)
        var receiptsOK = true
        var onlineOK = true
    }
    static func flags(_ d: [String: Any]) -> (receiptsOK: Bool, onlineOK: Bool) {
        (d["receipts_ok"].map(ShareJSON.bool) ?? true, d["online_ok"].map(ShareJSON.bool) ?? true)
    }
    /// Bestaetigungen in diesem Chat senden/anzeigen?
    static func receiptsActive(_ caps: Caps, _ c: ChatFlags) -> Bool {
        caps.receiptsOn && !c.request && !c.readonly && c.receiptsOK
    }
    /// Online-Status dieses Chats (nur Direkt-Chats, keine Anfragen)
    static func onlineActive(_ caps: Caps, _ c: ChatFlags) -> Bool {
        caps.onlineOn && c.kind == "dm" && !c.request && c.onlineOK
    }

    // MARK: Format

    /// body = {"id":"<msg_id>","n":<int>,"ts":<ms>} - genau dieser Text wird signiert
    static func body(id: String, n: Int, ts: Int64) -> String {
        let q = (try? JSONSerialization.data(withJSONObject: [id], options: [.withoutEscapingSlashes])).flatMap { String(data: $0, encoding: .utf8) } ?? "[\"\"]"
        return "{\"id\":" + q.dropFirst().dropLast() + ",\"n\":\(n),\"ts\":\(ts)}"
    }
    /// Bestaetigung einer Person: gelesen bis Position n (Nachricht id) um ts (ms)
    struct Seen: Equatable { var n: Int; var id: String; var ts: Double }
    static func parseBody(_ s: String) -> Seen? {
        guard let d = ShareJSON.obj(Data(s.utf8)), let n = d["n"] as? NSNumber, CFNumberIsFloatType(n) == false, n.intValue > 0,
              let id = d["id"] as? String else { return nil }
        return Seen(n: n.intValue, id: id, ts: ShareJSON.num(d["ts"]) ?? 0)
    }
    /// Je Person nur das groesste n merken -> neuer Stand oder nil (nichts geaendert)
    static func merge(_ old: Seen?, _ new: Seen) -> Seen? {
        if let old, old.n > new.n || old == new { return nil }
        return new
    }
    /// Empfaenger-Schluessel fuer /read: lokal "backup:<benutzername>", v2 "backup:<handle>"
    static func keyID(_ who: String) -> String { "backup:" + who }

    // MARK: Senden

    /// Nachricht in der Reihenfolge, die alle sehen (lokal seq, v2 fseq; 0 = noch ohne Position)
    struct Pos: Equatable { var id: String; var n: Int; var mine: Bool }
    /// Faellige Bestaetigung: neueste Nachricht mit Position, die nicht von mir ist und schon sichtbar war (n <= seenUpTo),
    /// mit n groesser als die zuletzt bestaetigte
    static func want(_ msgs: [Pos], sent: Int, seenUpTo: Int) -> Pos? {
        for m in msgs.reversed() where m.n > 0 && !m.mine && m.n <= seenUpTo {
            return m.n > sent ? m : nil
        }
        return nil
    }
    /// Hoechstens eine je Chat und `gap` Sekunden: Wartezeit bis zur naechsten (0 = jetzt)
    static func wait(lastAt: Double?, now: Double, gap: Double) -> Double {
        guard let lastAt else { return 0 }
        return max(0, gap - (now - lastAt))
    }
    /// Antwort von /read: was tun?
    enum ReadResult: Equatable { case sent, off, retryLater, drop }
    static func readResult(_ v: Any?) -> ReadResult {
        switch v as? String {
        case "ok", "none": return .sent                 // "none": niemand zu benachrichtigen - nicht wiederholen
        case "off": return .off
        case "rate": return .retryLater
        default: return .drop
        }
    }

    // MARK: Haken

    /// ⏳ wird zugestellt, ✓ zugestellt, ✓✓ gelesen (Gruppen: Zaehler, bis alle gelesen haben)
    enum Tick: Equatable { case none, pending, delivered, read(count: Int, all: Bool) }
    /// `others`: Schluessel der anderen Mitglieder (wie in `seen`); `shown`: Server kann es (sonst kein Haken wie bisher)
    static func tick(mine: Bool, pending: Bool, failed: Bool, n: Int, others: [String], seen: [String: Seen], active: Bool, shown: Bool) -> Tick {
        guard mine, !failed else { return .none }
        if pending { return .pending }
        guard shown else { return .none }
        guard active, n > 0 else { return .delivered }
        let c = others.filter { (seen[$0]?.n ?? 0) >= n }.count
        return c == 0 ? .delivered : .read(count: c, all: c >= others.count)
    }
    /// "Gelesen von": Name + Zeit, neueste zuerst
    struct Reader: Equatable { var key: String; var name: String; var ts: Double }
    static func readers(n: Int, members: [(key: String, name: String)], seen: [String: Seen]) -> [Reader] {
        guard n > 0 else { return [] }
        return members.compactMap { m in seen[m.key].flatMap { $0.n >= n ? Reader(key: m.key, name: m.name, ts: $0.ts) : nil } }
            .sorted { $0.ts != $1.ts ? $0.ts > $1.ts : $0.name < $1.name }
    }

    // MARK: Online-Status

    struct Presence: Equatable { var online: Bool; var last: Double? }
    /// Antwort von POST /api/chat/presence
    static func presence(_ v: Any?) -> (enabled: Bool, users: [String: Presence]) {
        guard let d = v as? [String: Any] else { return (true, [:]) }
        var out: [String: Presence] = [:]
        for (u, x) in d["users"] as? [String: Any] ?? [:] {
            guard let p = x as? [String: Any] else { continue }
            let on = ShareJSON.bool(p["online"]), last = ShareJSON.num(p["last"])
            if on || last != nil { out[u] = Presence(online: on, last: last) }
        }
        return (d["enabled"].map(ShareJSON.bool) ?? true, out)
    }
    /// Live chat.presence: online false + last null = nicht mehr verfuegbar -> entfernen
    static func applyLive(_ d: [String: Any], to map: inout [String: Presence]) -> Bool {
        guard let u = d["username"] as? String else { return false }
        let on = ShareJSON.bool(d["online"]), last = ShareJSON.num(d["last"])
        let new: Presence? = on || last != nil ? Presence(online: on, last: last) : nil
        if map[u] == new { return false }
        map[u] = new
        return true
    }
    /// Anzeige: online bzw. "zuletzt online" (nie in der Zukunft; zu lange her bzw. unbekannt: nichts)
    enum Shown: Equatable { case online, last(Date), none }
    static func shown(_ p: Presence?, now: Date) -> Shown {
        guard let p else { return .none }
        if p.online { return .online }
        guard let l = p.last, l > 0 else { return .none }
        return .last(Date(timeIntervalSince1970: min(l, now.timeIntervalSince1970)))
    }

    /// Wen abonnieren: offener Direkt-Chat + Direkt-Chats der sichtbaren Liste (erste 30 nicht archivierte), ohne Doppelte
    struct PeerRef: Equatable { var user: String; var flags: ChatFlags; var archived = false }
    static func presenceWant(open: PeerRef?, list: [PeerRef]?, caps: Caps) -> [String] {
        guard caps.onlineOn else { return [] }
        var out: [String] = []
        func add(_ r: PeerRef) { if onlineActive(caps, r.flags), !r.user.isEmpty, !out.contains(r.user) { out.append(r.user) } }
        if let open { add(open) }
        for r in (list ?? []).filter({ !$0.archived }).prefix(30) { add(r) }
        return Array(out.prefix(caps.limits.presenceUsers))
    }

    /// Abo-Lebenszyklus: nur solange sichtbar und App aktiv; alle 60 s erneuern; Personen anderer Server, fuer die noch
    /// nichts kam, eine Minute lang alle 10 s nachfragen; beim Verlassen bzw. im Hintergrund abmelden (leave)
    struct PresenceSub: Equatable {
        enum Action: Equatable { case none, subscribe([String]), leave([String]) }
        private(set) var users: [String] = []
        private(set) var lastAt: Double?
        private(set) var retryAt: Double?
        private(set) var misses = 0
        static let renew = 60.0, retry = 10.0, maxMisses = 6
        init() {}
        /// Was jetzt tun? (Takt, Sichtbarkeit geaendert, Vordergrund)
        mutating func plan(want: [String], active: Bool, now: Double) -> Action {
            if !active || want.isEmpty {
                let had = users
                users = []; lastAt = nil; retryAt = nil; misses = 0
                return had.isEmpty ? .none : .leave(had)
            }
            let due = lastAt.map { now - $0 >= Self.renew } ?? true
            let retry = retryAt.map { now >= $0 } ?? false
            guard Set(want) != Set(users) || due || retry else { return .none }
            users = want; lastAt = now; retryAt = nil
            return .subscribe(want)
        }
        /// Antwort verarbeitet: fehlen Personen anderer Server ('@' vorne), bald erneut fragen
        mutating func answered(_ got: [String: Presence], now: Double) {
            let miss = users.contains { $0.hasPrefix("@") && got[$0] == nil }
            misses = miss ? misses + 1 : 0
            retryAt = miss && misses <= Self.maxMisses ? now + Self.retry : nil
        }
        /// Anfrage gescheitert: im naechsten Takt erneut
        mutating func failed() { lastAt = nil }
    }
}
