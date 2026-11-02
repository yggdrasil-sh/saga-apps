import Foundation

// 2.5 Fehlerberichte und "Diagnose senden" (Server-Feature clientlog1, Vertrag docs/APP-API.md "Fehlerberichte und
// Diagnose"). Reine Logik ohne UI und ohne Netz (Tests/Logic/ClientLogTests.swift):
// - scrub: Fehlertexte ohne Inhalte (wie saga/clientlog.py bzw. static/clientlog.js) - URLs, E-Mail, Schluessel, Zitate,
//   Pfade, Handles, UUIDs, Hex, Zufallsfolgen, Zahlen -> Platzhalter; Woerter, die nicht nach Programm aussehen -> <w>
// - endpoint: Endpunkt-Muster (/api/notes/:id/comments) - nur bekannte feste Pfadwoerter bleiben stehen
// - Reporter: Eintraege sammeln, gleiche zaehlen (n), hoechstens 20 je Bericht, 1 Bericht je 30 s, 429/404 beachten
// - Ring: die letzten 200 Ereignisse fuer "Diagnose senden" (nur im Speicher)
// - Absturz-Notiz: Format der lokalen Datei und Umwandlung der Stackzeilen (nur Modul + Symbol + Offset, keine Pfade)
// - JSON-Form von Kopf, Bericht und Diagnose inkl. Groessengrenzen
enum ClientLogLogic {
    static let feature = "clientlog1"
    static let logPath = "api/client/log", diagPath = "api/client/diag"
    static let rateMs = 30_000.0, itemsMax = 20, ringMax = 200, reportsMax = 40
    static let logBytes = 15_000, diagBytes = 60_000, noteMax = 1000, msgMax = 300, stackMax = 8000, stackLines = 30
    static let tMax = 7.0 * 24 * 3600 * 1000

    // MARK: Bereinigung

    private static func rx(_ p: String, _ o: NSRegularExpression.Options = []) -> NSRegularExpression { try! NSRegularExpression(pattern: p, options: o) }
    private static let reCtrl = rx("[\\x{0}-\\x{1f}\\x{7f}-\\x{9f}\\x{200b}-\\x{200f}\\x{202a}-\\x{202e}\\x{2066}-\\x{2069}\\x{feff}]")
    private static let reURL = rx("\\b[a-z][a-z0-9+.\\-]{1,15}://[^\\s\"'<>()]*|\\bwww\\.[^\\s\"'<>()]+|\\b(?:blob|data|javascript|mailto|tel):[^\\s\"'<>()]*", .caseInsensitive)
    private static let reEmail = rx("[\\w.+\\-]+@[\\w\\-]+(?:\\.[\\w\\-]+)+")
    private static let reSecret = rx("\\b(?:hkd?|sga|hk_test)_[A-Za-z0-9_\\-]{6,}|\\bBearer\\s+\\S+", .caseInsensitive)
    private static let reQuote = rx("'([^'\\n]{0,200})'|\"([^\"\\n]{0,200})\"|`([^`\\n]{0,200})`|\u{201e}([^\u{201c}\u{201d}\\n]{0,200})[\u{201c}\u{201d}]|\u{201c}([^\u{201d}\\n]{0,200})\u{201d}|\u{00ab}([^\u{00bb}\\n]{0,200})\u{00bb}|\u{2018}([^\u{2019}\\n]{0,200})\u{2019}")
    private static let reIdent = rx("^[A-Za-z_$][\\w$.]{0,39}$")
    private static let rePath = rx("(?<![\\w.:<])/[^\\s\"'<>()]+")
    private static let reHandle = rx("[~@][\\w.\\-]{2,}")
    private static let reUUID = rx("\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b", .caseInsensitive)
    private static let reHex = rx("\\b(?:0x)?[0-9a-f]{8,}\\b", .caseInsensitive)
    private static let reToken = rx("(?<![\\w~])(?=[A-Za-z_\\-+=]*\\d)[A-Za-z0-9_\\-+=]{20,}")
    private static let reNum = rx("(?<![\\w$<])\\d+(?:[.,]\\d+)*|(?<=[A-Za-z_$])\\d{3,}")
    // Platzhalter und Eigenschaften in Anfuehrungszeichen ('title') bleiben, der Rest wird Wort fuer Wort geprueft
    private static let reKeep = rx("<[a-z]+>|'[a-z_$][\\w$.]{0,39}'")
    private static let reWord = rx("[^\\W\\d_][\\w$]*(?:\\.[\\w$]+)*")
    private static let reProgram = rx("[.$_\\d]|[a-z][A-Z]|(?:Error|Exception)$")
    private static let reAllow = rx("[^\\w\\s.,:;()\\[\\]{}<>#'\"=+\\-*/!?&|%$~^\u{00ab}\u{00bb}\u{201e}\u{201c}\u{201d}\u{2013}\u{2014}\u{2026}]")
    private static let reSpaces = rx("\\s{2,}")

    /// Woerter englischer Fehlermeldungen (wie _ERR_WORDS in saga/clientlog.py)
    static let errWords: Set<String> = Set("""
    a an the of to in on at by for from with without into is are was were be been not no null undefined nan true false
    cannot can could read write set get call called properties property reading setting object function functions method
    methods value values type types error errors exception failed fail failure load loading loaded fetch fetching network
    request response resource attempting when while operation aborted abort timeout timed out quota exceeded has been
    storage permission denied access allowed blocked origin cross script syntax unexpected token end input json parse
    parsing invalid argument arguments range maximum stack size reference defined assignment constant variable
    iterable iterator promise rejected resolved unhandled rejection uncaught internal unknown missing required expected
    element elements node nodes child children parent document window insert remove removed append contains selector
    execute executing constructor instance instanceof new length index offset count number string boolean array
    map key keys message name code status http https ok closed close open opened connection websocket eventsource socket
    media device devices stream streams track tracks play paused user gesture interaction not supported support disabled
    enabled decode encode encoding decoding image audio video codec context lost webgl canvas worker service navigator
    clipboard observer loop completed undelivered notifications limit is has have had does do did this that it its which
    only already still yet must should please try again later offline online state crypto decrypt encrypt signature verify
    verification module import export dynamically imported chunk database transaction version upgrade int double float
    bool optional nil none self class struct enum protocol thread main queue signal fatal crash exit killed memory oom anr
    warning critical
    """.split(whereSeparator: { $0 == " " || $0 == "\n" }).map(String.init))

    private static func sub(_ re: NSRegularExpression, _ s: String, _ f: (NSTextCheckingResult, NSString) -> String) -> String {
        let ns = s as NSString
        var out = "", pos = 0
        for m in re.matches(in: s, range: NSRange(location: 0, length: ns.length)) {
            out += ns.substring(with: NSRange(location: pos, length: m.range.location - pos))
            out += f(m, ns)
            pos = m.range.location + m.range.length
        }
        return out + ns.substring(from: pos)
    }
    private static func sub(_ re: NSRegularExpression, _ s: String, _ with: String) -> String { sub(re, s) { _, _ in with } }

    private static func word(_ w: String) -> String {
        if w.count == 1 || reProgram.firstMatch(in: w, range: NSRange(location: 0, length: (w as NSString).length)) != nil
            || errWords.contains(w.lowercased()) { return w }
        return "<w>"
    }

    /// Fehlertext ohne Inhalte (hoechstens `max` Zeichen). Der Server bereinigt noch einmal.
    static func scrub(_ input: String, max: Int = msgMax) -> String {
        var s = String(input.prefix(4000))
        s = sub(reCtrl, s, " ")
        s = sub(reURL, s, "<url>")
        s = sub(reEmail, s, "<email>")
        s = sub(reSecret, s, "<secret>")
        s = sub(reQuote, s) { m, ns in
            let whole = ns.substring(with: m.range)
            var inner = ""
            for i in 1..<m.numberOfRanges where m.range(at: i).location != NSNotFound { inner = ns.substring(with: m.range(at: i)); break }
            let ascii = "'\"`".contains(whole.first ?? " ")
            if !inner.isEmpty, reIdent.firstMatch(in: inner, range: NSRange(location: 0, length: (inner as NSString).length)) != nil {
                return ascii ? whole : "'" + inner + "'"
            }
            return ascii ? String(whole.first!) + "<str>" + String(whole.first!) : "\"<str>\""
        }
        s = sub(rePath, s, "<path>")
        s = sub(reHandle, s, "<id>")
        s = sub(reUUID, s, "<uuid>")
        s = sub(reHex, s, "<hex>")
        s = sub(reToken, s, "<tok>")
        s = sub(reNum, s, "<n>")
        // Woerter ausserhalb von Platzhaltern pruefen
        let ns = s as NSString
        var out = "", pos = 0
        func words(_ part: String) -> String { sub(reWord, part) { m, p in word(p.substring(with: m.range)) } }
        for m in reKeep.matches(in: s, range: NSRange(location: 0, length: ns.length)) {
            out += words(ns.substring(with: NSRange(location: pos, length: m.range.location - pos)))
            out += ns.substring(with: m.range)
            pos = m.range.location + m.range.length
        }
        s = out + words(ns.substring(from: pos))
        s = sub(reAllow, s, "?")
        s = sub(reSpaces, s, " ").trimmingCharacters(in: .whitespacesAndNewlines)
        return String(s.prefix(max))
    }

    /// Freitext der Person (nur Diagnose): Steuerzeichen weg, Schluessel/Adressen/E-Mail maskiert, gekuerzt
    static func note(_ input: String) -> String {
        var s = input.replacingOccurrences(of: "\r\n", with: "\n")
        s = sub(rx("[\\x{0}-\\x{9}\\x{b}-\\x{1f}\\x{7f}-\\x{9f}\\x{200b}-\\x{200f}\\x{202a}-\\x{202e}\\x{2066}-\\x{2069}\\x{feff}]"), s, " ")
        s = sub(reSecret, s, "<secret>")
        s = sub(reURL, s, "<url>")
        s = sub(reEmail, s, "<email>")
        s = sub(rx("(?<![\\w-])[A-Za-z0-9_\\-+/=]{32,}"), s, "<tok>")
        return String(s.trimmingCharacters(in: .whitespacesAndNewlines).prefix(noteMax))
    }

    // MARK: Endpunkt-Muster

    /// Feste Pfadwoerter der Saga-Endpunkte, die die App benutzt - alles andere wird :x (Zahlen :id)
    static let routeWords: Set<String> = Set("""
    api app fed account api-tokens avatar backup connect flow cancel key confirm generate remote restore run settings target
    test public-name security ai ask history page dbs persona status bookmarks categories capture page-image pages rows check
    tasks src done today calendar events delete calls active caps config diag alive answer decline end horn join leave
    moderate signal chat blocks bundle contacts devices files token identity keys link me notes people presence privacy
    report unfurl uploads v2 cert chats unread hide members messages reaction pins reactions read receipts recipients
    request typing comments resolve seen shared gallery use graph immich albums assets import thumb nextcloud list resolve
    search notes.cursor archive backlinks move chart discard duplicate export nc versions trash share shares widgets
    subscriptions push notifications version resume pair client log habit handles trash restore templates targets
    """.split(whereSeparator: { $0 == " " || $0 == "\n" }).map(String.init))

    /// "/api/notes/123/comments?x=1" bzw. "https://host/saga/api/notes/123" -> "/api/notes/:id/comments"; nil, wenn kein
    /// Saga-API-Pfad (api/, app/, fed/) darin steht
    static func endpoint(_ raw: String) -> String? {
        var p = raw
        if let r = p.range(of: "://") { p = String(p[r.upperBound...]); p = p.firstIndex(of: "/").map { String(p[$0...]) } ?? "/" }
        p = String(p.split(separator: "?", maxSplits: 1, omittingEmptySubsequences: false).first ?? "")
        p = String(p.split(separator: "#", maxSplits: 1, omittingEmptySubsequences: false).first ?? "")
        var segs = p.split(separator: "/", omittingEmptySubsequences: false).map(String.init).filter { !$0.isEmpty }
        guard let i = segs.firstIndex(where: { $0 == "api" || $0 == "app" || $0 == "fed" }) else { return nil }
        segs = Array(segs[i...].prefix(11))
        let out = segs.map { s -> String in
            if s.range(of: "^-?\\d+$", options: .regularExpression) != nil { return ":id" }
            if routeWords.contains(s) { return s }
            return ":x"
        }
        return String(("/" + out.joined(separator: "/")).prefix(100))
    }
    /// Muster fuer Fehlertexte: "/api/notes/:id" -> "api.notes.ID" (bleibt bei der Server-Bereinigung erhalten)
    static func dotted(_ pattern: String) -> String {
        pattern.split(separator: "/").map { $0 == ":id" ? "ID" : $0 == ":x" ? "X" : String($0) }.joined(separator: ".")
            .replacingOccurrences(of: "-", with: "_")
    }
    static let methods: Set<String> = ["GET", "POST", "PUT", "PATCH", "DELETE", "HEAD"]

    /// Fehlertext fuer eine 5xx-Antwort - ohne Server-Text (kann Inhalte enthalten)
    static func httpMessage(code: Int, method: String, pattern: String) -> String {
        "HTTPError.status\(code / 100)xx: http.\(methods.contains(method) ? method : "GET") \(dotted(pattern))"
    }
    /// Fehlertext fuer einen Dekodierfehler: nur die Art (keyNotFound ...), keine Schluesselnamen oder Werte
    static func decodeMessage(_ e: Error, method: String = "GET", pattern: String) -> String {
        let kind: String
        switch e as? DecodingError {
        case .typeMismatch?: kind = "typeMismatch"
        case .valueNotFound?: kind = "valueNotFound"
        case .keyNotFound?: kind = "keyNotFound"
        case .dataCorrupted?: kind = "dataCorrupted"
        default: kind = (e as NSError).domain == NSCocoaErrorDomain ? "jsonInvalid" : "other"
        }
        return "DecodingError.\(kind): http.\(methods.contains(method) ? method : "GET") \(dotted(pattern))"
    }
    /// Speichern abgelehnt (nicht 409): Statusklasse
    static func saveMessage(code: Int) -> String { "SaveError.status\(code / 100)xx" }
    /// Anruf kam nicht zustande: Phase und Grund (beides feste Bezeichner)
    static func callMessage(phase: String, reason: String) -> String {
        let ok = { (s: String) in s.range(of: "^[a-zA-Z]{1,20}$", options: .regularExpression) != nil ? s : "other" }
        return "CallError.setup: phase.\(ok(phase)) reason.\(ok(reason))"
    }
    /// Bildschirm-Bezeichnung nach Vertrag ([a-z][a-z0-9-]{0,23}), sonst "other"
    static func screen(_ s: String) -> String { s.range(of: "^[a-z][a-z0-9\\-]{0,23}$", options: .regularExpression) != nil ? s : "other" }

    // MARK: Kopf

    static func clean(_ s: String, allowed: String, max: Int) -> String {
        String(s.unicodeScalars.filter { allowed.unicodeScalars.contains($0) || CharacterSet.alphanumerics.contains($0) && $0.isASCII }.map(Character.init).prefix(max))
    }
    /// Gemeinsamer Kopf; `os` grob ("iOS 26"), `sid` zufaellig je App-Start
    static func head(app: String, ver: String, os: String?, dev: String?, sid: String) -> [String: Any] {
        var h: [String: Any] = ["v": 1, "app": ["web", "ios", "mac", "android", "linux"].contains(app) ? app : "ios",
                                "ver": clean(ver, allowed: "._+-", max: 40), "sid": sid]
        if let os { let o = clean(os, allowed: "._ ()+;/-", max: 60); if !o.isEmpty { h["os"] = o } }
        if let dev, ["phone", "tablet", "desktop"].contains(dev) { h["dev"] = dev }
        return h
    }
    /// System + Hauptversion ("iOS 26", "macOS 26")
    static func osLabel(name: String, major: Int) -> String { "\(name) \(major)" }
    /// Zufallskennung (16 Zeichen), nie aus Hardware-IDs
    static func newSID<G: RandomNumberGenerator>(using g: inout G) -> String {
        let a = Array("abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789")
        return String((0..<16).map { _ in a[Int(g.next() % UInt64(a.count))] })
    }
    static func newSID() -> String { var g = SystemRandomNumberGenerator(); return newSID(using: &g) }

    // MARK: Eintraege und Bericht

    struct Item: Equatable {
        var k: String                     // error | unhandled | crash | warn
        var msg: String
        var stack: String?
        var src: String?
        var line: Int?
        var screen: String?
        var n = 1
        var t: Double
        var key: String { [k, msg, src ?? "", String(line ?? 0)].joined(separator: "|") }
        var json: [String: Any] {
            var d: [String: Any] = ["k": k, "msg": msg, "t": Int(max(0, min(t, ClientLogLogic.tMax)))]
            if let stack, !stack.isEmpty { d["stack"] = String(stack.prefix(ClientLogLogic.stackMax)) }
            if let src, !src.isEmpty { d["src"] = src }
            if let line, line >= 0 { d["line"] = line }
            if let screen { d["screen"] = ClientLogLogic.screen(screen) }
            if n > 1 { d["n"] = n }
            return d
        }
        /// bereinigt anlegen (msg, src ohne Pfad)
        static func make(_ k: String, _ msg: String, stack: String? = nil, src: String? = nil, line: Int? = nil, screen: String? = nil, t: Double) -> Item {
            let m = ClientLogLogic.scrub(msg)
            let s = src.map { ($0 as NSString).lastPathComponent }.flatMap { $0.range(of: "^[\\w.\\-]{1,60}$", options: .regularExpression) != nil ? $0 : nil }
            return Item(k: ["error", "unhandled", "crash", "anr", "warn"].contains(k) ? k : "error", msg: m.isEmpty ? "Error" : m,
                        stack: stack, src: s, line: line, screen: screen, t: t)
        }
    }

    /// Sammeln, zaehlen, drosseln. `on`: Server meldet clientlog1 (sonst wird nichts gepuffert)
    struct Reporter {
        private(set) var pending: [Item] = []
        private(set) var lastSent = -Double.infinity
        private(set) var blockedUntil = -Double.infinity
        private(set) var sent = 0
        /// 404 (Schalter aus) bzw. zu viele Berichte: bis zum naechsten Start nichts mehr
        private(set) var off = false
        var isEmpty: Bool { pending.isEmpty }

        /// Eintrag aufnehmen; gleicher Fehler -> n + 1. false: verworfen (aus, voll)
        @discardableResult
        mutating func add(_ it: Item) -> Bool {
            guard !off else { return false }
            if let i = pending.firstIndex(where: { $0.key == it.key }) { pending[i].n += it.n; return true }
            guard pending.count < ClientLogLogic.itemsMax else { return false }
            pending.append(it); return true
        }
        /// Wartezeit bis zum naechsten erlaubten Bericht (ms, 0 = jetzt)
        func wait(now: Double) -> Double { max(0, lastSent + ClientLogLogic.rateMs - now, blockedUntil - now) }
        func due(now: Double) -> Bool { !off && !pending.isEmpty && wait(now: now) == 0 }
        /// Eintraege fuer einen Bericht entnehmen (nil: nicht faellig)
        mutating func take(now: Double) -> [Item]? {
            guard due(now: now) else { return nil }
            if sent >= ClientLogLogic.reportsMax { off = true; pending = []; return nil }
            let items = pending; pending = []
            lastSent = now; sent += 1
            return items
        }
        /// Antwort des Servers: 404 aus, 429 frühestens nach retry_s, 401 Eintraege zurueck (nach der Anmeldung senden)
        mutating func response(code: Int, retryS: Double?, items: [Item], now: Double) {
            switch code {
            case 404: off = true; pending = []
            case 429: blockedUntil = now + max(60, retryS ?? 60) * 1000
            case 401, 0: for it in items.reversed() where !pending.contains(where: { $0.key == it.key }) { if pending.count < ClientLogLogic.itemsMax { pending.insert(it, at: 0) } }
                lastSent = code == 0 ? now : -Double.infinity
            default: break
            }
        }
        mutating func disable() { off = true; pending = [] }
    }

    /// Bericht-JSON; zu gross -> hintere Eintraege weglassen
    static func logBody(head: [String: Any], items: [Item]) -> Data? {
        var list = items.prefix(itemsMax).map(\.json)
        while !list.isEmpty {
            var b = head; b["items"] = list
            if let d = try? JSONSerialization.data(withJSONObject: b, options: [.sortedKeys]), d.count <= logBytes { return d }
            if list.count == 1 {                                         // ein riesiger Eintrag: Stack kuerzen bzw. weg
                var one = list[0]; one.removeValue(forKey: "stack"); list = [one]
                var b2 = head; b2["items"] = list
                return try? JSONSerialization.data(withJSONObject: b2, options: [.sortedKeys])
            }
            list.removeLast()
        }
        return nil
    }

    // MARK: Ringpuffer

    struct Ring {
        private(set) var events: [[String: Any]] = []
        var cap = ClientLogLogic.ringMax
        mutating func push(_ k: String, t: Double, _ fields: [String: Any] = [:]) {
            var e = fields.filter { !($0.value is String) || !($0.value as! String).isEmpty }
            e["k"] = k; e["t"] = Int(max(0, min(t, ClientLogLogic.tMax)))
            events.append(e)
            if events.count > cap { events.removeFirst(events.count - cap) }
        }
        mutating func net(method: String, pattern: String, status: Int, ms: Double?, t: Double) {
            var f: [String: Any] = ["m": ClientLogLogic.methods.contains(method) ? method : "GET", "p": pattern, "s": max(0, status)]
            if let ms { f["ms"] = Int(max(0, ms)) }
            push("net", t: t, f)
        }
        mutating func err(_ it: Item) {
            var f: [String: Any] = ["kind": it.k, "msg": it.msg]
            if let s = it.src { f["src"] = s }
            if let l = it.line { f["line"] = l }
            if let s = it.screen { f["screen"] = ClientLogLogic.screen(s) }
            push("err", t: it.t, f)
        }
        var count: Int { events.count }
    }
    static let connChannels: Set<String> = ["sse", "poll", "ws", "collab", "push", "call"]
    static let connStates: Set<String> = ["open", "closed", "error", "poll", "retry"]
    static let saveStates: Set<String> = ["ok", "conflict", "offline", "error", "merged"]
    static let lifeStates: Set<String> = ["start", "hidden", "visible", "online", "offline", "resume"]

    /// Diagnose-JSON; zu gross -> aelteste Ereignisse zuerst weglassen
    static func diagBody(head: [String: Any], ring: [[String: Any]], note: String) -> Data? {
        var ev = Array(ring.suffix(ringMax))
        let n = Self.note(note)
        while true {
            var b = head; b["ev"] = ev
            if !n.isEmpty { b["note"] = n }
            guard let d = try? JSONSerialization.data(withJSONObject: b, options: [.sortedKeys]) else { return nil }
            if d.count <= diagBytes || ev.isEmpty { return d }
            ev.removeFirst(max(1, ev.count / 10))
        }
    }
    /// Antwort von /api/client/diag: Kennung D-XXXXX (nur gueltige Zeichen)
    static func diagID(_ data: Data) -> String? {
        guard let j = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any], let id = j["id"] as? String,
              id.range(of: "^D-[23456789ABCDEFGHJKMNPQRSTVWXYZ]{5}$", options: .regularExpression) != nil else { return nil }
        return id
    }

    // MARK: Absturz-Notiz

    /// Erste Zeile der lokalen Notiz: "SIG <NAME>" (Signal-Handler) bzw. "EXC <Name>: <Grund>" (NSException);
    /// danach Stackzeilen im Format von backtrace_symbols ("3   Saga   0x0000000104f2a1c8 symbol + 120")
    static func signalLine(_ name: String) -> String { "SIG \(name)\n" }
    static let signals: [(Int32, String)] = [(6, "SIGABRT"), (11, "SIGSEGV"), (10, "SIGBUS"), (4, "SIGILL"), (5, "SIGTRAP"), (8, "SIGFPE")]

    /// Eine Stackzeile auf Modul + Symbol + Offset kuerzen; Pfade -> Dateiname, Adressen weg. `demangle`: Swift-Namen
    /// lesbar machen (in der App ueber swift_demangle; Tests: Ersatz)
    static func stackLine(_ raw: String, demangle: (String) -> String? = { _ in nil }) -> String? {
        let parts = raw.split(whereSeparator: { $0 == " " || $0 == "\t" }).map(String.init)
        guard !parts.isEmpty else { return nil }
        var i = 0
        if Int(parts[0]) != nil { i = 1 }                                       // laufende Nummer
        guard i < parts.count else { return nil }
        var module = (parts[i] as NSString).lastPathComponent
        i += 1
        if i < parts.count, parts[i].hasPrefix("0x") { i += 1 }                 // Adresse
        var rest = parts[i...].joined(separator: " ")
        var off = ""
        if let r = rest.range(of: " \\+ \\d+$", options: .regularExpression) { off = String(rest[r]); rest = String(rest[..<r.lowerBound]) }
        if rest.hasPrefix("0x") || rest.isEmpty { rest = "" }
        var sym = rest
        if sym.hasPrefix("$s") || sym.hasPrefix("_$s") || sym.hasPrefix("$S"), let d = demangle(sym) { sym = shortSymbol(d) }
        if sym.contains("/") { sym = (sym as NSString).lastPathComponent }
        module = clean(module, allowed: "._-", max: 60)
        sym = String(sym.unicodeScalars.filter { $0.isASCII && $0.value >= 32 && $0 != "\"" && $0 != "'" }.map(Character.init).prefix(160))
        let line = [module, sym].filter { !$0.isEmpty }.joined(separator: " ") + off
        return line.isEmpty ? nil : line
    }
    /// "Saga.PageDoc.save(Saga.Store) async -> ()" -> "Saga.PageDoc.save"; Closures: "closure in Saga.X.y"
    static func shortSymbol(_ d: String) -> String {
        var core = d
        if let r = d.range(of: " in ", options: .backwards) { core = String(d[r.upperBound...]) }
        if let p = core.firstIndex(of: "(") { core = String(core[..<p]) }
        core = core.trimmingCharacters(in: .whitespaces)
        if let sp = core.lastIndex(of: " ") { core = String(core[core.index(after: sp)...]) }   // "static", "merged" ...
        return (d.hasPrefix("closure") || d.contains(" closure #") ? "closure in " : "") + core
    }

    /// Notiz -> Eintrag `crash` (nil: leer/unlesbar)
    static func crashItem(_ text: String, t: Double, demangle: (String) -> String? = { _ in nil }) -> Item? {
        var lines = text.replacingOccurrences(of: "\r", with: "").split(separator: "\n").map(String.init)
        guard let first = lines.first else { return nil }
        lines.removeFirst()
        let msg: String
        if first.hasPrefix("SIG ") {
            let n = String(first.dropFirst(4))
            msg = "Signal." + (signals.contains(where: { $0.1 == n }) ? n : "other")
        } else if first.hasPrefix("EXC ") {
            msg = scrub(String(first.dropFirst(4)))
        } else { return nil }
        let st = lines.prefix(stackLines).compactMap { stackLine($0, demangle: demangle) }.joined(separator: "\n")
        return Item(k: "crash", msg: msg.isEmpty ? "Error" : msg, stack: st.isEmpty ? nil : String(st.prefix(stackMax)), t: t)
    }
}

/// Grober Ort fuer Fehler und Ansichtswechsel (nie Titel oder ID): Seitenleisten-Auswahl -> Bezeichnung
enum ClientLogScreen {
    static func of(selection: Int?) -> String {
        switch selection {
        case nil: return "dashboard"
        case -1?: return "tasks"
        case -2?: return "calendar"
        case -3?: return "services"
        case -4?: return "chat"
        case -5?: return "graph"
        default: return "pages"
        }
    }
    /// Einstellungen/Anruf liegen ueber der Ansicht: oben angezeigter Ort, darunter der zuletzt gewaehlte
    struct Stack: Equatable {
        var base = "dashboard"
        var overlays: [String] = []
        var current: String { overlays.last ?? base }
        mutating func show(_ s: String, _ on: Bool) {
            overlays.removeAll { $0 == s }
            if on { overlays.append(s) }
        }
    }
}

