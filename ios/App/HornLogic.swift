import Foundation

// Gjallarhorn (Saga 2.5, P11 Stufe 2): Entscheidungen der Apple-Apps fuer Gruppenanrufe ueber Knoten - reine Logik ohne
// WebRTC und ohne UI (mit swiftc/XCTest pruefbar). Gleiche Regeln und Zahlen wie das Web (static/horn.js, getestet in
// tests/horn_logik.js; Konzept v2 §1.2 H6/H7, §2.6; docs/APP-API.md "Anrufe ueber Gjallarhorn-Knoten"):
// - Punktzahl je Kandidat (kleiner = besser): rtt_p50 + 2*jitter + Lastabzug + (UDP fehlt ? 15 : 0) - (here ? 25 : 0)
// - Umschalter mit Hysterese (Verbindung weg/Leeren sofort; "besser" nur >= 25 % UND >= 30 ms, 20 s am Stueck,
//   Verweildauer 60 s, hoechstens 3 Wechsel je 10 min; UDP-Verlust > 20 % fuer 3 s -> TCP; UDP wieder ok 30 s -> zurueck,
//   hoechstens 1x je 5 min), make-before-break (altes Bein nach 2 s bzw. hoechstens 15 s)
// - Kacheln bis 25, Last-N (iPhone 4, iPad/Mac 9 - der Sprecherplatz zaehlt mit), Sprecheransicht, Qualitaetspunkt, Weg
// - Kandidaten, Antwort von POST /api/sfu/leg und Ereigniskanal (hello, peers, layout, state, closed, pong) lesen,
//   Zuordnung CSRC -> Geraet je Knoten-Verbindung
// - Schluesselplan (eigener Schluessel: Start, Ratsche beim Beitritt, neuer Schluessel nach Abgang mit Umschalten nach
//   1,5 s), Empfaenger-Plaetze fuer FrameCryptor (vorab geratscht, Ablauf nach 3 s) und Nachforderung ("need", 2 s / 5 s)
// Zeiten in Millisekunden (Double), wie im Web.

enum HornLogic {
    /// 2.5 Akku: welche Kamera-Schichten wirklich kodiert werden (wie Android HornLogic.camActive). others = andere
    /// Geraete am Knoten. Allein: keine; zu zweit: ohne "l" (Kachel-Wunsch faellt auf "m" zurueck); Energiesparen/heiss:
    /// ohne "h", hoechstens 15 fps. Solange jemand da ist, bleibt mindestens eine Schicht an.
    static func camActive(path: String, others: Int, lowPower: Bool) -> (rids: Set<String>, fps: Int?) {
        let all = path == "udp" ? ["l", "m", "h"] : ["l", "m"]
        guard others > 0 else { return ([], nil) }
        var r = Set(all)
        if others == 1, r.count > 1 { r.remove("l") }
        if lowPower, r.count > 1 { r.remove("h") }
        if r.isEmpty { r = [all.last!] }
        return (r, lowPower ? 15 : nil)
    }
    /// Hoechstrate je Schicht (wie HornLeg.encodings)
    static func camMaxFps(_ rid: String) -> Int { rid == "l" ? 20 : 30 }
    static let probeMs = 800.0, pings = 5, noUdpMs = 15.0, hereMs = 25.0
    static let loadFrom = 0.7, loadSlope = 200.0, full = 0.9, fullMs = 1000.0
    static let betterRel = 0.25, betterMs = 30.0, betterFor = 20_000.0, dwell = 60_000.0, window = 600_000.0, maxSwitch = 3
    static let lostMs = 2000.0, udpLoss = 0.2, udpLossFor = 3000.0, udpBackFor = 30_000.0, udpBackEvery = 300_000.0
    static let overlapMin = 2000.0, overlapMax = 15_000.0
    static let lastNDesktop = 9, lastNMobile = 4, maxTiles = 25
    // Ablauf am Geraet (wie static/calls.js)
    static let waitTCP = 4000.0, waitUDP = 2000.0, waitUDPMaybe = 1500.0, waitTURN = 10_000.0
    static let monMs = 500.0, pingEvery = 2000.0, statsEvery = 3000.0, candsEvery = 30_000.0, udpProbeEvery = 10_000.0
    static let badMs = 60_000.0, retryMax = 120_000.0, freshMs = 30_000.0, fetchMs = 15_000.0
    static let rekeyAfter = 6 * 3_600_000.0

    // MARK: Punktzahl

    static func median(_ a: [Double]) -> Double {
        let s = a.sorted(), n = s.count
        guard n > 0 else { return .nan }
        return n % 2 == 1 ? s[(n - 1) / 2] : (s[n / 2 - 1] + s[n / 2]) / 2
    }
    /// mittlere Abweichung aufeinanderfolgender RTTs (wie RFC 3550 ohne Glaettung)
    static func jitter(_ a: [Double]) -> Double {
        guard a.count >= 2 else { return 0 }
        var s = 0.0
        for i in 1..<a.count { s += abs(a[i] - a[i - 1]) }
        return s / Double(a.count - 1)
    }
    static func loadPenalty(_ load: Double) -> Double {
        if load >= full { return fullMs }
        return load > loadFrom ? (load - loadFrom) * loadSlope : 0
    }

    /// Messung eines Kandidaten: TLS-Aufbau, WSS-Pings, UDP (STUN-Binding beantwortet; nil = nicht)
    struct Measure: Equatable {
        var ok = false
        var rtts: [Double] = []
        var tls: Double?
        var udp: Double?
        var udpMaybe = false
        static func pings(_ r: [Double], udp: Double? = nil) -> Measure { Measure(ok: true, rtts: r, tls: nil, udp: udp) }
    }

    /// Kandidat aus horn.candidates (POST /join, /api/calls, /horn)
    struct Candidate: Equatable {
        var node: String
        var url: String
        var udp = false
        var here = false
        var load = 0.0
        var peer = ""
        var exp = 0.0
        var tokens: [String: String] = [:]
        var used = false
        init(node: String, url: String = "", udp: Bool = false, here: Bool = false, load: Double = 0, peer: String = "", exp: Double = 0, tokens: [String: String] = [:]) {
            self.node = node; self.url = url; self.udp = udp; self.here = here; self.load = load; self.peer = peer; self.exp = exp; self.tokens = tokens
        }
        init?(_ any: Any?) {
            guard let d = any as? [String: Any], let n = d["node"] as? String, !n.isEmpty, let u = d["url"] as? String,
                  u.hasPrefix("https://") || u.hasPrefix("http://") else { return nil }
            node = n; url = u.hasSuffix("/") ? String(u.dropLast()) : u
            udp = d["udp"] as? Bool ?? false; here = d["here"] as? Bool ?? false
            load = (d["load"] as? NSNumber)?.doubleValue ?? 0
            peer = d["peer"] as? String ?? ""
            exp = (d["exp"] as? NSNumber)?.doubleValue ?? 0
            var t: [String: String] = [:]
            for (k, v) in d["tokens"] as? [String: Any] ?? [:] { if let s = v as? String, !s.isEmpty { t[k] = s } }
            tokens = t
        }
        /// Token noch juenger als 30 s vor Ablauf (gelten 60 s)? Sonst vor dem Verbinden frisch holen
        func young(now: Double) -> Bool { exp > 0 && exp * 1000 - now > HornLogic.freshMs }
        /// Host und Port (fuer STUN/TURN), Standard 443
        var hostPort: (host: String, port: Int)? {
            guard let c = URLComponents(string: url), let h = c.host else { return nil }
            return (h, c.port ?? 443)
        }
        var wsURL: String { (url.hasPrefix("https://") ? "wss://" + url.dropFirst(8) : "ws://" + url.dropFirst(7)) }
    }
    /// horn-Block des Anruf-Objekts: Kandidaten (hoechstens 3, beste zuerst)
    static func candidates(_ blk: Any?) -> [Candidate] {
        ((blk as? [String: Any])?["candidates"] as? [Any] ?? []).compactMap { Candidate($0) }.prefix(3).map { $0 }
    }

    /// kleiner = besser; nicht erreichbar = unendlich
    static func score(_ m: Measure?, _ c: Candidate?, devUdp: Bool = true) -> Double {
        guard let m, m.ok, !m.rtts.isEmpty else { return .infinity }
        var s = median(m.rtts) + 2 * jitter(m.rtts) + loadPenalty(c?.load ?? 0)
        let udp = (c?.udp ?? false) && devUdp && m.udp != nil
        if !udp { s += noUdpMs }
        if c?.here == true { s -= hereMs }
        return (s * 10).rounded() / 10
    }
    struct Ranked: Equatable { var cand: Candidate; var score: Double }
    /// nach Punktzahl (unerreichbare zuletzt, bei Gleichstand die Reihenfolge des Servers)
    static func rank(_ cands: [Candidate], _ meas: [String: Measure], devUdp: Bool = true) -> [Ranked] {
        var list: [(i: Int, r: Ranked)] = []
        for (i, c) in cands.enumerated() { list.append((i, Ranked(cand: c, score: score(meas[c.node], c, devUdp: devUdp)))) }
        list.sort { a, b in
            if a.r.score == b.r.score { return a.i < b.i }
            return a.r.score < b.r.score
        }
        return list.map { $0.r }
    }
    static func better(_ cur: Double, _ other: Double) -> Bool {
        guard other.isFinite else { return false }
        guard cur.isFinite else { return true }
        return cur - other >= betterMs && other <= cur * (1 - betterRel)
    }

    // MARK: Umschalter

    enum Why: String, Equatable { case join, lost, draining, better, udpLoss = "udp-loss", udp, retry, moved }
    struct Decision: Equatable {
        var why: Why
        var to: String? = nil
        var path: String? = nil
        var isPath: Bool { path != nil }
    }
    /// Zustand nur hier; der Aufrufer fuehrt den Wechsel aus und meldet ihn mit moved()
    struct Switcher {
        private(set) var node: String?
        private(set) var path: String
        private(set) var since: Double
        private(set) var moves: [Double] = []
        private var cand: String?
        private var candSince = 0.0
        private var lossSince = 0.0
        private var udpOkSince = 0.0
        private var udpBackAt = -Double.infinity
        private var discSince = 0.0
        init(node: String?, path: String, now: Double = 0) { self.node = node; self.path = path; since = now }

        mutating func moved(_ now: Double, node n: String, path p: String, why: Why) {
            if why == .better { moves.append(now) }
            if n != node { since = now }
            node = n; if !p.isEmpty { path = p }
            cand = nil; candSince = 0; lossSince = 0; discSince = 0
            if why == .udp { udpBackAt = now }
            if p == "udp" { udpOkSince = 0 }
        }
        mutating func budget(_ now: Double) -> Int {
            let w = now - HornLogic.window
            moves = moves.filter { $0 > w }
            return HornLogic.maxSwitch - moves.count
        }
        /// state = schlechtester Zustand der Beine (connected | disconnected | failed | closed | new ...)
        mutating func conn(_ now: Double, _ state: String) -> Decision? {
            if state == "failed" || state == "closed" { return Decision(why: .lost) }
            if state == "disconnected" {
                if discSince == 0 { discSince = now }
                if now - discSince > HornLogic.lostMs { return Decision(why: .lost) }
            } else { discSince = 0 }
            return nil
        }
        func draining() -> Decision { Decision(why: .draining) }
        /// laufende Beobachtung: cur = Punktzahl des aktuellen Knotens, others = (node, score)
        mutating func observe(_ now: Double, cur: Double, others: [(node: String, score: Double)]) -> Decision? {
            var best: (node: String, score: Double)?
            for x in others where x.node != node && HornLogic.better(cur, x.score) && (best == nil || x.score < best!.score) { best = x }
            guard let best else { cand = nil; candSince = 0; return nil }
            if cand != best.node { cand = best.node; candSince = now; return nil }
            if now - candSince < HornLogic.betterFor { return nil }
            if now - since < HornLogic.dwell { return nil }
            if budget(now) <= 0 { return nil }
            return Decision(why: .better, to: best.node)
        }
        /// UDP-Weg: Verlust (0...1) aus getStats; ok = UDP-Probe gelingt (Rueckkehr)
        mutating func udp(_ now: Double, loss: Double, ok: Bool = false) -> Decision? {
            if path == "udp" {
                if loss > HornLogic.udpLoss {
                    if lossSince == 0 { lossSince = now }
                    if now - lossSince >= HornLogic.udpLossFor { return Decision(why: .udpLoss, path: "tcp") }
                } else { lossSince = 0 }
                return nil
            }
            guard ok else { udpOkSince = 0; return nil }
            if udpOkSince == 0 { udpOkSince = now }
            if now - udpOkSince >= HornLogic.udpBackFor && now - udpBackAt >= HornLogic.udpBackEvery { return Decision(why: .udp, path: "udp") }
            return nil
        }
    }
    /// Altes Bein schliessen? up = Zeitpunkt, ab dem das neue Bein Rahmen liefert (0 = noch nicht); waiting = andere,
    /// die nur ueber das alte Bein zu hoeren sind
    static func closeOld(now: Double, up: Double, waiting: Int) -> Bool {
        guard up > 0 else { return false }
        let d = now - up
        return d >= overlapMax || (d >= overlapMin && waiting == 0)
    }

    // MARK: Kacheln

    /// Spalten fuer n Kacheln bei Breite w (Punkte). Schmal (Handy): 2 Spalten
    static func cols(_ n: Int, width w: Double) -> Int {
        if w > 0 && w <= 640 { return n <= 1 ? 1 : 2 }
        return n <= 1 ? 1 : n <= 4 ? 2 : n <= 9 ? 3 : n <= 16 ? 4 : 5
    }
    struct Tile: Equatable { var dev: String; var moving: Bool; var big: Bool }
    /// devs = andere Geraete (Reihenfolge der Teilnehmerliste), moving = hat gerade einen Bildplatz, speaker = Geraet des
    /// Sprecherplatzes; Sprecheransicht: Sprecher zuerst und gross. Hoechstens 25, Rest als "+n"
    static func tiles(_ devs: [String], moving: Set<String>, speaker: String?, view: String) -> (list: [Tile], more: Int) {
        var list = devs.prefix(maxTiles).map { Tile(dev: $0, moving: moving.contains($0), big: false) }
        if view == "speaker" && !list.isEmpty {
            let i = list.firstIndex { $0.dev == speaker } ?? 0
            var s = list.remove(at: i); s.big = true; list.insert(s, at: 0)
        }
        return (list, max(0, devs.count - maxTiles))
    }
    /// Raster: Kacheln, die (noch) nicht ganz sichtbar sind - n Kacheln in cols Spalten, Zeilenhoehe h, Abstand sp,
    /// seenBottom = unterer Rand des sichtbaren Bereichs (Blaetterstand + Hoehe). Fuer den Hinweis "+n weitere"
    static func hiddenTiles(_ n: Int, cols: Int, rowHeight h: Double, spacing sp: Double, seenBottom: Double) -> Int {
        guard n > 0, cols > 0, h > 0 else { return 0 }
        let rows = max(0, Int(((seenBottom + sp + 0.5) / (h + sp)).rounded(.down)))   // ganz sichtbare Zeilen
        return max(0, n - rows * cols)
    }
    static func lastN(mobile: Bool) -> Int { mobile ? lastNMobile : lastNDesktop }
    /// Kachelplaetze je Bild-Leitung (der Sprecherplatz zaehlt bei Last-N mit)
    static func tileSlots(mobile: Bool) -> Int { lastN(mobile: mobile) - 1 }

    // MARK: Qualitaet, Weg

    enum Quality: String, Equatable { case good, mid, bad }
    static func quality(rtt: Double?, loss: Double = 0, freezes: Double = 0) -> Quality {
        let r = rtt ?? 0
        if r > 400 || loss > 0.08 || freezes > 3 { return .bad }
        if r > 200 || loss > 0.02 || freezes > 1 { return .mid }
        return .good
    }
    static func pathName(_ p: String) -> String { p == "udp" ? "UDP" : p == "turn" ? "TLS" : p == "tcp" ? "TCP" : "" }
}

// MARK: - Knoten: Bein-Antwort, Ereigniskanal, Zuordnung

extension HornLogic {
    struct Slot: Equatable {
        var i: Int
        var cls: String
        var peer: String?
        var csrc: UInt32?
        var layer: String?
        var paused = false
        init(i: Int, cls: String, peer: String? = nil, csrc: UInt32? = nil, layer: String? = nil, paused: Bool = false) {
            self.i = i; self.cls = cls; self.peer = peer; self.csrc = csrc; self.layer = layer; self.paused = paused
        }
        init?(_ any: Any?) {
            guard let d = any as? [String: Any], let i = (d["i"] as? NSNumber)?.intValue, i >= 0, i < 64, let c = d["class"] as? String else { return nil }
            self.i = i; cls = c
            peer = (d["peer"] as? String).flatMap { $0.isEmpty ? nil : $0 }
            csrc = (d["csrc"] as? NSNumber).flatMap { $0.int64Value > 0 && $0.int64Value <= Int64(UInt32.max) ? UInt32($0.int64Value) : nil }
            layer = d["layer"] as? String
            paused = d["paused"] as? Bool ?? false
        }
    }
    static func slots(_ any: Any?) -> [Slot] { (any as? [Any] ?? []).prefix(64).compactMap { Slot($0) } }
    /// peers: {peer_ref: csrc}
    static func peers(_ any: Any?) -> [String: UInt32] {
        var out: [String: UInt32] = [:]
        for (k, v) in (any as? [String: Any] ?? [:]).prefix(200) {
            guard !k.isEmpty, k.count <= 64, let n = v as? NSNumber, n.int64Value >= 0, n.int64Value <= Int64(UInt32.max) else { continue }
            out[k] = UInt32(n.int64Value)
        }
        return out
    }

    /// POST /api/sfu/leg -> {id, answer, csrc, slots, peers, state}
    struct LegAnswer: Equatable {
        var id: String
        var answer: String
        var csrc: UInt32?
        var slots: [Slot]
        var peers: [String: UInt32]
        var state: String
        init?(_ any: Any?) {
            guard let d = any as? [String: Any], let id = d["id"] as? String, !id.isEmpty, let a = d["answer"] as? String, a.hasPrefix("v=") else { return nil }
            self.id = id; answer = a
            csrc = (d["csrc"] as? NSNumber).map { UInt32(truncatingIfNeeded: $0.int64Value) }
            slots = HornLogic.slots(d["slots"]); peers = HornLogic.peers(d["peers"])
            state = d["state"] as? String ?? "ok"
        }
    }

    /// Ereigniskanal je Bein (GET wss://{knoten}/api/sfu/leg/{id}/events)
    enum Event: Equatable {
        case hello(leg: String, peers: [String: UInt32], slots: [Slot], est: Int, state: String)
        case peers([String: UInt32])
        case layout(slots: [Slot], est: Int)
        case state(String)
        case closed(String)
        case pong(Int)
    }
    static func event(_ text: String) -> Event? {
        guard text.count < 256 * 1024, let d = (try? JSONSerialization.jsonObject(with: Data(text.utf8))) as? [String: Any], let t = d["t"] as? String else { return nil }
        let est = (d["est_kbit"] as? NSNumber)?.intValue ?? 0
        switch t {
        case "hello": return .hello(leg: d["leg"] as? String ?? "", peers: peers(d["peers"]), slots: slots(d["slots"]), est: est, state: d["state"] as? String ?? "ok")
        case "peers": return .peers(peers(d["peers"]))
        case "layout": return .layout(slots: slots(d["slots"]), est: est)
        case "state": return (d["state"] as? String).map { .state($0) }
        case "closed": return .closed((d["why"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? "zu")
        case "pong": return (d["n"] as? NSNumber).map { .pong($0.intValue) }
        default: return nil                                                  // unbekannt: ignorieren (neuere Knoten)
        }
    }
    static func ping(_ n: Int) -> String { "{\"t\":\"ping\",\"n\":\(n)}" }

    /// Peer-Kennung eines Geraets am Knoten: hpn[Knoten], sonst hp
    static func ref(hp: String?, hpn: [String: String], node: String) -> String? { hpn.isEmpty ? hp : hpn[node] }
    /// CSRC -> Geraet je Knoten-Verbindung aus peers (Ereigniskanal, POST /leg) und der Belegung der Plaetze; nur Geraete aus
    /// der Teilnehmerliste, ohne das eigene. devOf: Peer-Kennung -> Geraet
    static func csrcMap(peers: [String: UInt32], slots: [Slot], me: String, devOf: (String) -> String?) -> [UInt32: String] {
        var cp: [UInt32: String] = [:]
        for (ref, c) in peers { cp[c] = ref }
        for s in slots { if let p = s.peer, let c = s.csrc { cp[c] = p } }
        var m: [UInt32: String] = [:]
        for (c, ref) in cp { if let d = devOf(ref), d != me { m[c] = d } }
        return m
    }
    /// Gibt es Kennungen am Knoten, die nicht in der Teilnehmerliste stehen (Liste neu holen)?
    static func unknownRefs(peers: [String: UInt32], mine: String, devOf: (String) -> String?) -> Bool {
        peers.keys.contains { $0 != mine && devOf($0) == nil }
    }
    /// Besitzer eines Platzes: CSRC des Platzes -> Geraet
    static func owner(_ s: Slot?, _ map: [UInt32: String]) -> String? { s?.csrc.flatMap { map[$0] } }
}

// MARK: - Schluessel (docs/HORN-E2E.md §4, §6)

extension HornLogic {
    /// Eigener Frame-Schluessel: aktiv (wird gesendet), wartend (nach Abgang, Umschalten nach 1,5 s)
    struct OwnKey: Equatable {
        struct Gen: Equatable { var n: Int; var key: Data; var kid: Int { n % HornE2E.ring } }
        let s: String
        private(set) var active: Gen
        private(set) var pending: Gen?
        private(set) var since: Double
        init(s: String = OwnKey.session(), key: Data = OwnKey.random(), now: Double = 0) { self.s = s; active = Gen(n: 0, key: key); since = now }
        var newest: Gen { pending ?? active }
        /// Beitritt: neuesten (bzw. wartenden) Schluessel ratschen, n+1; ohne Wechsel sofort aktiv
        mutating func ratchet() -> Gen {
            let g = Gen(n: newest.n + 1, key: HornE2E.ratchet(newest.key))
            if pending != nil { pending = g } else { active = g }
            return g
        }
        /// Abgang/Entfernen (bzw. 6 h in Gebrauch): neuer Zufallsschluessel, n+1, wartet auf das Umschalten
        mutating func rekey(now: Double, key: Data = OwnKey.random()) -> Gen {
            let g = Gen(n: newest.n + 1, key: key)
            pending = g; since = now
            return g
        }
        /// Umschalten (1,5 s nach rekey bzw. wenn alle bestaetigt haben)
        @discardableResult mutating func commit() -> Gen? {
            guard let p = pending else { return nil }
            active = p; pending = nil
            return p
        }
        static func random() -> Data { HornE2E.scrubKey() }
        static func session() -> String { (0..<8).map { _ in String(format: "%02x", UInt8.random(in: 0...255)) }.joined() }
        func body(_ g: Gen) -> String {
            "{\"t\":\"fkey\",\"n\":\(g.n),\"kid\":\(g.kid),\"key\":\"\(g.key.base64EncodedString())\",\"s\":\"\(s)\"}"
        }
    }
    static func ackBody(n: Int, s: String, ok: Bool) -> String { "{\"t\":\"fkey-ack\",\"n\":\(n),\"s\":\"\(s)\",\"ok\":\(ok)}" }
    struct Ack: Equatable { var n: Int; var s: String; var ok: Bool }
    static func parseAck(_ o: Any?) -> Ack? {
        guard let b = o as? [String: Any], b["t"] as? String == "fkey-ack", let n = (b["n"] as? NSNumber)?.intValue, n >= -1,
              let s = b["s"] as? String, s.count == 16, let ok = b["ok"] as? Bool else { return nil }
        return Ack(n: n, s: s, ok: ok)
    }
    struct PeerState: Equatable { var mic = true; var cam = false; var screen = false }
    /// hp (P9/P10): eigene Kennungen an den Knoten, mit denen das Geraet gerade verbunden ist (hoechstens 4, alle
    /// Verbindungen waehrend eines Wechsels) - so ordnen Geraete an anderen Knoten (Kaskade, andere Server) CSRC -> Geraet zu
    /// replaces: "Hierher uebernehmen" fertig - ready + bisheriges eigenes Geraet (nur an dieses; wie state im Mesh)
    static func stateBody(mic: Bool, cam: Bool, screen: Bool, hp: [String] = [], replaces: String? = nil) -> String {
        var s = "{\"t\":\"state\",\"mic\":\(mic),\"cam\":\(cam),\"screen\":\(screen)"
        if let r = replaces, r.count == 32, r.allSatisfy(\.isHexDigit) { s += ",\"ready\":true,\"replaces\":\"\(r)\"" }
        let refs = Array(hp.filter { !$0.isEmpty && $0.count <= 64 }.prefix(4))
        if !refs.isEmpty, let d = try? JSONSerialization.data(withJSONObject: refs), let a = String(data: d, encoding: .utf8) {
            s += ",\"hp\":" + a
        }
        return s + "}"
    }
    static func parseState(_ o: Any?) -> PeerState? {
        guard let b = o as? [String: Any], b["t"] as? String == "state" else { return nil }
        return PeerState(mic: (b["mic"] as? Bool) != false, cam: (b["cam"] as? Bool) == true, screen: (b["screen"] as? Bool) == true)
    }
    /// Kennungen des Absenders aus state.hp (nil = Feld fehlt, aeltere App); nur Texte bis 64 Zeichen, hoechstens 4
    static func parseHp(_ o: Any?) -> [String]? {
        guard let b = o as? [String: Any], b["t"] as? String == "state", let a = b["hp"] as? [Any] else { return nil }
        return Array(a.compactMap { $0 as? String }.filter { !$0.isEmpty && $0.count <= 64 }.prefix(4))
    }
    /// Unique, Reihenfolge bleibt, hoechstens 4
    static func myRefs(_ refs: [String]) -> [String] {
        var out: [String] = []
        for r in refs where !r.isEmpty && !out.contains(r) { out.append(r) }
        return Array(out.prefix(4))
    }

    /// Teilnehmerliste -> wer bekommt meinen Schluessel: neue Geraete (Ratsche bzw. in der ersten Runde der Startschluessel),
    /// nach einem Abgang ein neuer Schluessel an alle Verbliebenen
    struct KeyPlan: Equatable {
        private(set) var known = Set<String>()
        private(set) var started = false
        struct Change: Equatable { var add: [String]; var gone: [String]; var first: Bool }
        mutating func update(_ devices: Set<String>) -> Change {
            let add = devices.subtracting(known).sorted(), gone = known.subtracting(devices).sorted()
            known = devices
            let first = !started && !add.isEmpty
            if !add.isEmpty { started = true }
            return Change(add: add, gone: gone, first: first)
        }
    }

    /// Empfaenger-Seite je Sender fuer FrameCryptor: neueste Generation aus fkey UND Teilnehmerliste (jeder Beitritt = +1,
    /// der Sender ratscht dann), vier Ratschenstufen vorab; Plaetze aelterer Generationen laufen nach 3 s ab
    /// (FrameCryptor kann nicht loeschen: ueberschreiben).
    struct PeerKeys: Equatable {
        private(set) var s: String?
        private(set) var n = -1
        private(set) var key: Data?
        private(set) var scrub: [Int: Double] = [:]                       // kid -> Zeitpunkt
        struct Apply: Equatable { var set: [Int: Data]; var reset: Bool }
        /// fkey des Senders. nil = ignorieren (alte Generation derselben Sitzung)
        mutating func fkey(_ f: HornE2E.FKey, now: Double) -> Apply? {
            var reset = false
            if s != f.s {                                                    // neue Sitzung: alles Alte verwerfen
                reset = s != nil; s = f.s; n = -1; key = nil; scrub = [:]
            } else if f.n < n - 1 { return nil }
            let older = n
            if f.n >= n { n = f.n; key = f.key }
            else {                                                           // n-1: nur den eigenen Platz setzen
                return Apply(set: [f.n % HornE2E.ring: f.key], reset: false)
            }
            if older >= 0 && older < n { plan(older, at: now + HornE2E.switchMs + HornE2E.graceMs) }
            return Apply(set: slots(), reset: reset)
        }
        /// Jemand ist beigetreten: der Sender ratscht (n+1) - Plaetze nachziehen. Abgelaufen ist danach nur, was VOR der
        /// bisher neuesten Generation lag (kamen zwei Beitritte beim Sender in einer Runde an, zaehlt das Geraet einen zu
        /// viel - die wirklich benutzte Generation bleibt so trotzdem gehalten)
        mutating func joined(now: Double) -> Apply? {
            guard let k = key, n >= 0 else { return nil }
            if n >= 1 { plan(n - 1, at: now + HornE2E.graceMs) }
            n += 1; key = HornE2E.ratchet(k)
            return Apply(set: slots(), reset: false)
        }
        /// faellige Plaetze zum Ueberschreiben (nie die gehaltenen newest ... newest+4)
        mutating func due(now: Double) -> [Int] {
            let keep = Set((0...HornE2E.ahead).map { (n + $0) % HornE2E.ring })
            var out: [Int] = []
            for (kid, t) in scrub where t <= now {
                scrub[kid] = nil
                if !keep.contains(kid) { out.append(kid) }
            }
            return out.sorted()
        }
        private mutating func plan(_ gen: Int, at: Double) {
            for g in max(0, gen - 2)...gen { let kid = g % HornE2E.ring; scrub[kid] = min(scrub[kid] ?? .infinity, at) }
        }
        private func slots() -> [Int: Data] {
            guard let key, n >= 0 else { return [:] }
            var out: [Int: Data] = [:]
            for x in HornE2E.cryptorSlots(newest: n, key: key) { out[x.kid] = x.key }
            return out
        }
    }

    /// Nachforderung: liefert ein Sender seit 2 s nur Unlesbares (MissingKey/DecryptionFailed bzw. Pakete ohne dekodierte
    /// Bilder in getStats), fkey-ack ok:false - hoechstens alle 5 s je Sender
    struct NeedWatch: Equatable {
        private var badSince: [String: Double] = [:]
        private var asked: [String: Double] = [:]
        mutating func bad(_ dev: String, now: Double) { if badSince[dev] == nil { badSince[dev] = now } }
        mutating func good(_ dev: String) { badSince[dev] = nil }
        mutating func forget(_ dev: String) { badSince[dev] = nil; asked[dev] = nil }
        mutating func due(now: Double) -> [String] {
            var out: [String] = []
            for (d, t) in badSince where now - t >= 2000 && now - (asked[d] ?? -.infinity) >= 5000 {
                asked[d] = now; out.append(d)
            }
            return out.sorted()
        }
    }
    /// getStats je Empfangsplatz: kamen Pakete, aber keine Bilder (Video) bzw. keine Samples (Ton)? -> unlesbar
    struct RxSample: Equatable { var packets: Int; var decoded: Int }
    static func unreadable(prev: RxSample?, now: RxSample) -> Bool? {
        guard let p = prev, now.packets > p.packets else { return nil }   // nichts angekommen: keine Aussage
        return now.decoded <= p.decoded
    }
}
