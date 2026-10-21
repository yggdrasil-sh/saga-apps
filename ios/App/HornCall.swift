import SwiftUI
import WebRTC

// Gjallarhorn (Saga 2.5, P11 Stufe 2): Gruppenanruf ueber Knoten in den Apple-Apps. Ablauf wie der Web-Client
// (static/calls.js hornGo/hornStart/hornTry/hornLeg/hornMon, Regeln in App/HornLogic.swift):
// - Meldet der Server topo=horn: Kandidaten (je eigene Medien-Token, 60 s) parallel messen (TLS-Aufbau + Pings, UDP-Probe;
//   hoechstens 800 ms), nach Punktzahl der Reihe nach versuchen. Weg: UDP (eine Leitung, BUNDLE), wenn der Knoten udp_ok
//   meldet und die UDP-Probe antwortet; sonst ICE-TCP (Ton- und Bild-Leitung getrennt, 4 s), sonst TURN-TLS ueber den Knoten
// - Token juenger als 30 s behalten, sonst frisch holen (POST /api/calls/{id}/horn); 401 am Knoten: einmal frisch
// - Ereigniskanal je Bein (hello/peers/layout/state/closed/pong): Zuordnung CSRC -> Geraet, Belegung der Plaetze, Leeren
// - Frame-E2E: FrameCryptor mit Saga-Ratsche (Plaetze vorab), eigener Schluessel als fkey ueber den versiegelten
//   Signalweg (nie ueber den Knoten); fkey-ack, Nachforderung (need) aus FrameCryptor-Zustand und getStats
// - Live-Umschalten make-before-break mit Hysterese; kein Knoten erreichbar: neuer Versuch (2 ... 15 s), nach 2 min aufgeben
// - Kacheln (iPhone Last-N 4, iPad/Mac 9), Sprecheransicht, Bildschirm ueber den Knoten, Weg UDP/TCP/TLS mit
//   Qualitaetspunkt, Runensiegel, Teilnehmerliste mit Stummschalten/Entfernen (Gastgeber)
// Mesh 2.4 bleibt fuer Anrufe mit topo=mesh (kein Knoten bereit, Gruppe in der Mesh-Grenze, 1:1).

struct HornTile: Identifiable, Equatable {
    var id: String
    var name: String
    var devName: String
    var avatar: String?
    var video: RTCVideoTrack?
    var moving: Bool
    var big: Bool
    var mic: Bool
    var cam: Bool
    var speaking: Bool
    var screen: Bool
    var wait: Bool
    static func == (a: HornTile, b: HornTile) -> Bool {
        a.id == b.id && a.name == b.name && a.devName == b.devName && a.avatar == b.avatar && a.video === b.video && a.moving == b.moving
            && a.big == b.big && a.mic == b.mic && a.cam == b.cam && a.speaking == b.speaking && a.screen == b.screen && a.wait == b.wait
    }
}
struct HornPerson: Identifiable, Equatable {
    var id: String
    var name: String
    var avatar: String?
    var sub: String
    var mine: Bool
    var ownPerson: Bool
    var mic: Bool
    var speaking: Bool
    var unverified: Bool
}
struct HornShare: Equatable {
    var dev: String
    var name: String
    var track: RTCVideoTrack?
    static func == (a: HornShare, b: HornShare) -> Bool { a.dev == b.dev && a.name == b.name && a.track === b.track }
}

/// Knoten-Verbindung (ein Knoten, ein Weg; zwei Beine auf TCP/TLS, eines auf UDP)
@MainActor
final class HornConn {
    let key: String
    var cand: HornLogic.Candidate
    let node: String
    let url: String
    let path: String
    var legs: [String: HornLeg] = [:]
    var peers: [String: UInt32] = [:]
    var csrc: [UInt32: String] = [:]
    let created: Double
    var up = 0.0, rx = 0.0, retired = 0.0
    var dead = false
    var closed: String?
    var state = "ok"
    var rtts: [Double] = []
    var pingN = 0, pingAt = 0.0
    var pings: [Int: Double] = [:]
    var q5: (lost: Int, recv: Int, fz: Int, t: Double)?
    var rtt: Double?
    var loss = 0.0
    var freezes = 0.0
    var quality = HornLogic.Quality.good
    var onUp: ((Bool) -> Void)?
    init(key: String, cand: HornLogic.Candidate, path: String, now: Double) {
        self.key = key; self.cand = cand; node = cand.node; url = cand.url; self.path = path; created = now
    }
    var aLeg: HornLeg? { legs["audio"] ?? legs["both"] }
    var vLeg: HornLeg? { legs["video"] ?? legs["both"] }
    var all: [HornLeg] { ["audio", "video", "both"].compactMap { legs[$0] } }
    func worst() -> String {
        var w = "connected"
        for L in all {
            switch L.state {
            case .failed: w = "failed"
            case .closed: if w != "failed" { w = "closed" }
            case .connected: break
            default: if w == "connected" { w = "disconnected" }
            }
        }
        return w
    }
}

/// Zustand des Knoten-Anrufs; @Published nur fuer die Anzeige
@MainActor
final class HornSession: ObservableObject {
    @Published var tiles: [HornTile] = []
    @Published var more = 0
    @Published var view = UserDefaults.standard.string(forKey: "saga-horn-view") == "speaker" ? "speaker" : "grid" {
        didSet { UserDefaults.standard.set(view, forKey: "saga-horn-view") }
    }
    @Published var pin: String?
    @Published var share: HornShare?
    @Published var path = ""
    @Published var node = ""
    @Published var quality = HornLogic.Quality.good
    @Published var rtt: Double?
    @Published var loss = 0.0
    @Published var moving = false
    @Published var runes: String?
    @Published var runeNames: [String] = []
    @Published var unverified: [String] = []
    @Published var plist = false
    @Published var rpop = false
    @Published var people: [HornPerson] = []
    @Published var host = false
    @Published var note = ""
    @Published var rmSure: String?
    /// Bild fuer Bild-im-Bild/Schwebefenster: Bildschirm bzw. Sprecher
    @Published var focusTrack: RTCVideoTrack?
    @Published var focusName = ""

    var blk: [HornLogic.Candidate] = []
    var cur: HornConn?
    var next: HornConn?
    var olds: [HornConn] = []
    var seq = 0
    var bad: [String: Double] = [:]
    var leaving: (node: String, until: Double)?
    var meas: [String: HornLogic.Measure] = [:]
    var sw: HornLogic.Switcher?
    var switching = false
    var retryTask: Task<Void, Never>?
    var retry0 = 0.0, retryN = 0
    var own = HornLogic.OwnKey()
    var ownAt = 0.0
    var plan = HornLogic.KeyPlan()
    var recv: [String: HornLogic.PeerKeys] = [:]
    var need = HornLogic.NeedWatch()
    var acks: [String: String] = [:]
    var ackTask: Task<Void, Never>?
    var rekeyTask: Task<Void, Never>?
    var commitTask: Task<Void, Never>?
    var stTask: Task<Void, Never>?
    var st: [String: HornLogic.PeerState] = [:]
    /// P9/P10: Kennungen anderer Geraete an ihren Knoten aus state.hp (Kaskade, Knoten anderer Server)
    var hpx: [String: [String]] = [:]
    var links: [String: CallLink] = [:]
    var q: [[String: Any]] = []
    let provider = HornCryptor.keyProvider()
    var monTask: Task<Void, Never>?
    var candT = 0.0, statT = 0.0, udpProbeAt = 0.0, rosterT = 0.0
    var levels: [String: Double] = [:]
    var speaking = Set<String>()
    var speakAt: [String: Double] = [:]
    var spk: String?
    var selfLevel = 0.0
    var runesKey = ""
    var acked: [String: Int] = [:]
    /// nur Bilder (HornPreview): eigenes Geraet ohne Chat-Schluessel
    var meDevice: String?
    var log: [String] = []
    var started = false
    init(_ blk: Any?) { self.blk = HornLogic.candidates(blk) }
    var conns: [HornConn] { ([cur] + olds + [next]).compactMap { $0 }.filter { !$0.dead } }
    func avoid(_ extra: [String] = [], now: Double) -> [String] {
        var a = bad.filter { $0.value > now }.map(\.key)
        if let l = leaving, l.until > now, !a.contains(l.node) { a.append(l.node) }
        for n in extra where !a.contains(n) { a.append(n) }
        return a.sorted()
    }
    func note(_ s: String) { if note != s { note = s } }
    func trace(_ s: String) {
        log.append(s); if log.count > 40 { log.removeFirst() }
        CallEnv.log("horn " + s)
    }
}

/// einmal ausloesen (Frist oder Ergebnis, was zuerst kommt)
@MainActor final class HornOnce {
    private var done = false
    func take() -> Bool { if done { return false }; done = true; return true }
}

enum HornFail: Error, LocalizedError {
    case noNode, ended, cancelled, timeout, failed
    var errorDescription: String? {
        switch self {
        case .noNode: return String(localized: "Gerade ist kein Knoten für große Gruppenanrufe erreichbar – bitte gleich noch einmal.")
        case .ended: return String(localized: "Der Anruf ist schon beendet.")
        case .timeout: return "timeout"
        case .failed: return "failed"
        case .cancelled: return "–"
        }
    }
}

extension CallManager {
    static func hornNow() -> Double { Date().timeIntervalSince1970 * 1000 }
    /// iPhone = Handy (Last-N 4), iPad/Mac = Desktop (9)
    var hornMobile: Bool {
        #if os(iOS)
        return UIDevice.current.userInterfaceIdiom == .phone
        #else
        return false
        #endif
    }
    var hornDevClass: String { hornMobile ? "mobile" : "desktop" }
    /// Knoten-Anruf laeuft noch (dieser Anruf, dieselbe Sitzung)
    func hornLive(_ H: HornSession?) -> Bool { H != nil && horn === H && !ending && id != nil }

    // MARK: Beitreten

    /// POST /join ohne Angebote (topo=horn), danach Knoten waehlen und verbinden
    func hornJoin(role r: String, rep: String?) async throws {
        guard let store, let id, let dev = myDevice else { return }
        guard myCaps.contains("horn") else { throw APIError.other(String(localized: "Dafür braucht dieses Gerät Saga 2.5.")) }
        var body: [String: Any] = ["device": dev, "role": r, "caps": myCaps, "dev_class": hornDevClass]
        if r == "handover", let rep { body["replaces"] = rep }
        let asked = Date().timeIntervalSince1970
        let (d, code) = try await store.apiResponse("api/calls/\(id)/join", method: "POST", body: body)
        let j = ShareJSON.obj(d)
        guard (200..<300).contains(code) else {
            let blk = j?["join_block"] as? String
            throw APIError.other(ShareJSON.str(j?["error"]) ?? hornBlockText(blk) ?? blockText(CallRoom.block(blk), limit: limit))
        }
        guard !ending, self.id == id else {
            _ = try? await store.apiResponse("api/calls/\(id)/leave", method: "POST", body: ["device": dev])
            return
        }
        ready = true; inRoom = true
        let c = j?["call"] as? [String: Any]
        if let c { adoptCall(c) }
        hornGo(c?["horn"])
        if let c { rosterIn(CallRoom.legs(c["legs"]), rv: asked, extra: c) }
        status = String(localized: "Verbinde …")
        if phase != .active { phase = .connecting }
        aliveOn()
        if r == "handover", let rep {
            ho.begin(rep: rep, now: Date().timeIntervalSince1970, timeout: handoverSeconds)
            handingOver = true
        }
        let pending = early; early = []
        for s in pending { await handleRaw(s) }
    }
    /// Vor dem Start eines Gruppenanrufs: kein Knoten bereit und die Gruppe ist groesser als die Mesh-Grenze -> Hinweis
    func hornMeshHint(members n: Int, video: Bool) -> Bool {
        guard serverFeatures.contains("horn"), (cfg["horn_ready"] as? Bool) == false, let lim = cfg["limits"] as? [String: Any],
              let l = ShareJSON.int(video ? lim["endpoints_video"] : lim["endpoints_audio"]), l > 0 else { return false }
        return n > l
    }
    func hornBlockText(_ b: String?) -> String? {
        switch b {
        case "node": return String(localized: "Gerade ist kein Knoten für große Gruppenanrufe erreichbar – bitte gleich noch einmal.")
        case "removed": return String(localized: "Du wurdest aus dem Anruf entfernt.")
        case "old": return String(localized: "Dafür braucht dieses Gerät Saga 2.5.")
        default: return nil
        }
    }

    /// Start im Knoten-Anruf: eigener Schluessel, Schluessel an alle, Kandidaten messen und verbinden
    func hornGo(_ blk: Any?) {
        let H: HornSession
        if let h = horn { H = h; let b = HornLogic.candidates(blk); if !b.isEmpty { h.blk = b } } else { H = HornSession(blk); horn = H }
        guard !H.started else { return }
        H.started = true
        verified = true                                                     // Frame-E2E: kein Rahmen geht unverschluesselt raus
        H.ownAt = Self.hornNow()
        hornSetOwn(H, H.own.active)
        status = String(localized: "Verbinde …")
        hornKeys()
        let q0 = H.q; H.q = []
        Task { for s in q0 { await hornSig(s) } }
        Task {
            do { _ = try await hornStart(H.blk, why: .join) }
            catch { hornStartFailed(H, error, why: .join) }
        }
    }
    private func hornStartFailed(_ H: HornSession, _ e: Error, why: HornLogic.Why) {
        guard hornLive(H) else { return }
        if case HornFail.cancelled? = e as? HornFail { return }
        if case HornFail.ended? = e as? HornFail { remoteEnded(reason: nil); return }
        hornRetry(why)
    }

    // MARK: Kandidaten

    func hornCands(node: String?, avoid: [String]) async throws -> [HornLogic.Candidate] {
        guard let store, let id, let dev = myDevice else { throw HornFail.cancelled }
        var b: [String: Any] = ["device": dev, "dev_class": hornDevClass, "avoid": avoid]
        if let node { b["node"] = node }
        let (d, code) = try await store.apiResponse("api/calls/\(id)/horn", method: "POST", body: b)
        if code == 404 { throw HornFail.ended }
        guard (200..<300).contains(code), let j = ShareJSON.obj(d) else { throw HornFail.noNode }
        return HornLogic.candidates(j["horn"])
    }
    func hornFresh(_ c: HornLogic.Candidate, avoid: [String]) async throws -> HornLogic.Candidate {
        guard let x = try await hornCands(node: c.node, avoid: avoid).first(where: { $0.node == c.node }) else { throw HornFail.noNode }
        return x
    }
    func hornMeasure(_ cands: [HornLogic.Candidate]) async -> [String: HornLogic.Measure] {
        let H = horn
        let out = await withTaskGroup(of: (String, HornLogic.Measure).self) { g -> [String: HornLogic.Measure] in
            for c in cands { g.addTask { (c.node, await HornNet.probe(c)) } }
            var m: [String: HornLogic.Measure] = [:]
            for await (n, r) in g { m[n] = r }
            return m
        }
        for (n, r) in out { H?.meas[n] = r }
        return out
    }

    /// Kandidaten messen, ordnen, der Reihe nach versuchen
    func hornStart(_ list0: [HornLogic.Candidate], why: HornLogic.Why) async throws -> HornConn {
        guard let H = horn else { throw HornFail.cancelled }
        let now = Self.hornNow()
        var cands = list0.filter { (H.bad[$0.node] ?? 0) <= now || list0.count == 1 }
        guard !cands.isEmpty else { throw HornFail.noNode }
        let one = cands.count == 1
        if one && !cands[0].udp { Task { _ = await hornMeasure(cands) } }  // einziger ohne UDP: gleich verbinden
        else {
            let m = await hornMeasure(cands)
            guard hornLive(H) else { throw HornFail.cancelled }
            if !one { cands = HornLogic.rank(cands, m).map(\.cand) }
        }
        return try await hornTry(cands, why: why, i: 0)
    }
    private func hornTry(_ list: [HornLogic.Candidate], why: HornLogic.Why, i: Int) async throws -> HornConn {
        guard let H = horn, i < list.count else { throw HornFail.noNode }
        let c = list[i]
        let first = H.blk.first?.node
        do {
            var c2 = c
            if !(why == .join && i == 0 && c.node == first && !c.used && c.young(now: Self.hornNow())) {
                c2 = try await hornFresh(c, avoid: H.avoid(now: Self.hornNow()))
            }
            if let k = H.blk.firstIndex(where: { $0.node == c.node }) { H.blk[k].used = true }
            let conn = try await hornOpen(c2)
            hornAdopt(conn, why: why)
            return conn
        } catch {
            guard hornLive(H) else { throw HornFail.cancelled }
            if let f = error as? HornFail, f == .cancelled || f == .ended { throw f }
            if (error as? HornNet.HTTPError)?.status == 404 { throw HornFail.ended }
            H.trace("err \(c.node) \(error.localizedDescription)")
            H.bad[c.node] = Self.hornNow() + HornLogic.badMs
            if i + 1 < list.count { H.note(String(localized: "Knoten \(c.node) ist nicht erreichbar – versuche \(list[i + 1].node) …")); status = H.note }
            return try await hornTry(list, why: why, i: i + 1)
        }
    }
    /// Weg: UDP (udp_ok + Probe), sonst ICE-TCP, sonst TURN-TLS mit frischen Token
    private func hornOpen(_ cand: HornLogic.Candidate) async throws -> HornConn {
        guard let H = horn else { throw HornFail.cancelled }
        let m = H.meas[cand.node]
        let udp = cand.udp && cand.tokens["both"] != nil && m != nil && (m?.udp != nil || m?.udpMaybe == true)
        do {
            if udp {
                do { return try await hornConnect(cand, path: "udp", wait: m?.udp != nil ? HornLogic.waitUDP : HornLogic.waitUDPMaybe) }
                catch HornFail.cancelled { throw HornFail.cancelled }
                catch { return try await hornConnect(cand, path: "tcp") }
            }
            return try await hornConnect(cand, path: "tcp")
        } catch {
            if case HornFail.cancelled? = error as? HornFail { throw error }
            if let s = (error as? HornNet.HTTPError)?.status, s == 404 || s == 429 { throw error }   // beendet bzw. voll/Leeren
            let c2 = try await hornFresh(cand, avoid: H.avoid(now: Self.hornNow()))
            return try await hornConnect(c2, path: "turn")
        }
    }
    /// Beine aufbauen und auf "verbunden" warten
    private func hornConnect(_ cand: HornLogic.Candidate, path: String, wait: Double? = nil) async throws -> HornConn {
        guard let H = horn else { throw HornFail.cancelled }
        H.seq += 1
        let conn = HornConn(key: "c\(H.seq)", cand: cand, path: path, now: Self.hornNow())
        H.next = conn
        H.trace("connect \(cand.node) \(path)")
        do {
            var turn: HornLeg.Turn?
            if path == "turn", let tok = cand.tokens["audio"], let hp = cand.hostPort {
                let t = try await HornNet.turn(cand.url, token: tok)
                turn = HornLeg.Turn(url: "turns:\(hp.host):\(hp.port)?transport=tcp", user: t.user, pass: t.pass)
            }
            let roles = path == "udp" ? ["both"] : ["audio", "video"]
            try await withThrowingTaskGroup(of: Void.self) { g in
                for r in roles { g.addTask { @MainActor in try await self.hornLeg(conn, role: r, turn: turn) } }
                try await g.waitForAll()
            }
            guard hornLive(H), !conn.dead else { throw HornFail.cancelled }
            let ms = wait ?? (path == "udp" ? HornLogic.waitUDP : path == "tcp" ? HornLogic.waitTCP : HornLogic.waitTURN)
            let ok: Bool = await withCheckedContinuation { k in
                let once = HornOnce()
                let t = Task { @MainActor in
                    try? await Task.sleep(for: .milliseconds(Int(ms)))
                    if once.take() { conn.onUp = nil; k.resume(returning: false) }
                }
                conn.onUp = { good in guard once.take() else { return }; t.cancel(); conn.onUp = nil; k.resume(returning: good) }
                self.hornConnState(conn)
            }
            guard ok else { throw HornFail.timeout }
            guard hornLive(H), !conn.dead else { throw HornFail.cancelled }
            return conn
        } catch {
            hornConnClose(conn)
            if H.next === conn { H.next = nil }
            throw hornLive(H) ? error : HornFail.cancelled
        }
    }
    private func hornLeg(_ conn: HornConn, role: String, turn: HornLeg.Turn?) async throws {
        guard let H = horn, let me = myDevice else { throw HornFail.cancelled }
        let L = HornLeg(role: role, path: conn.path, me: me, tiles: HornLogic.tileSlots(mobile: hornMobile), factory: CallRTC.factory, provider: H.provider)
        conn.legs[role] = L
        if scrSid.isEmpty { scrSid = "k" + ChatCrypto.hexID().prefix(8) }
        let sid = "s" + (id ?? "0").prefix(8)
        guard L.build(turn: turn, audio: audioTrack, camera: H.cur == nil || H.cur === conn ? camera?.track : nil,
                      screen: sharing ? scrTrack : nil, keyIndex: H.own.active.kid, sid: sid) else { throw HornFail.failed }
        L.onState = { [weak self, weak conn] _ in guard let self, let conn else { return }; self.hornConnState(conn) }
        L.onCryptor = { [weak self] dev, ok in
            guard let self, let H = self.horn else { return }
            if ok { H.need.good(dev) } else { H.need.bad(dev, now: Self.hornNow()) }
        }
        let offer = try await L.offer()
        let mobile = hornMobile
        func post(_ c: HornLogic.Candidate) async throws -> HornLogic.LegAnswer {
            guard let tok = c.tokens[role] else { throw HornNet.HTTPError(status: 401, message: "token") }
            return try await HornNet.leg(c.url, L.body(token: tok, offer: offer, mobile: mobile))
        }
        var res: HornLogic.LegAnswer
        do { res = try await post(conn.cand) }
        catch let e as HornNet.HTTPError where e.status == 401 && hornLive(H) {   // Token abgelaufen/benutzt: einmal frisch
            let c2 = try await hornFresh(conn.cand, avoid: H.avoid(now: Self.hornNow()))
            conn.cand = c2
            res = try await post(c2)
        }
        guard hornLive(H), conn.legs[role] === L, !conn.dead else { L.close(); throw HornFail.cancelled }
        L.id = res.id; L.slots = res.slots; conn.state = res.state
        hornPeersIn(conn, res.peers)
        try await L.answer(res.answer)
        hornEventsOpen(conn, L, again: false)
        hornMap(); hornDraw()
    }

    // MARK: Ereigniskanal

    private func hornEventsOpen(_ conn: HornConn, _ L: HornLeg, again: Bool) {
        guard let lid = L.id, !conn.dead, let ev = HornEvents(base: conn.url, leg: lid) else { return }
        L.events = ev
        ev.onEvent = { [weak self, weak conn, weak L] e in guard let self, let conn, let L else { return }; self.hornEvent(conn, L, e) }
        ev.onClose = { [weak self, weak conn, weak L] opened in
            guard let self, let conn, let L, let H = self.horn, self.hornLive(H), !conn.dead, conn.closed == nil else { return }
            L.events = nil
            if again && !opened {                                           // Knoten nimmt nichts mehr an: nicht auf ICE warten
                if H.cur === conn { H.trace("events weg"); self.hornSwitch(.lost) }
                return
            }
            Task { @MainActor [weak self, weak conn, weak L] in            // Netzwechsel: neu verbinden
                try? await Task.sleep(for: .milliseconds(300))
                guard let self, let conn, let L, !conn.dead, L.events == nil else { return }
                self.hornEventsOpen(conn, L, again: true)
            }
        }
        ev.start()
    }
    private func hornEvent(_ conn: HornConn, _ L: HornLeg, _ e: HornLogic.Event) {
        guard let H = horn, hornLive(H), !conn.dead else { return }
        switch e {
        case .hello(_, let peers, let slots, let est, let state):
            hornPeersIn(conn, peers)
            L.slots = slots; if est > 0 { L.est = est }
            hornMap(); hornDraw()
            hornNodeState(conn, state)
        case .peers(let p): hornPeersIn(conn, p)
        case .layout(let slots, let est):
            L.slots = slots; if est > 0 { L.est = est }
            hornMap(); hornDraw()
        case .state(let s): hornNodeState(conn, s)
        case .closed(let why):
            H.trace("closed \(conn.node) \(why)")
            conn.closed = why
            if H.cur === conn { hornGone(conn) }
        case .pong(let n):
            if let t = conn.pings.removeValue(forKey: n) {
                conn.rtts.append((Self.hornNow() - t).rounded())
                if conn.rtts.count > 10 { conn.rtts.removeFirst() }
            }
        }
    }
    private func hornNodeState(_ conn: HornConn, _ s: String) {
        conn.state = s
        if s == "draining", horn?.cur === conn { hornSwitch(.draining) }
    }
    /// Bein vom Knoten beendet: entfernt (Gastgeber) -> der Server bestaetigt es; sonst wie Verbindung weg
    private func hornGone(_ conn: HornConn) {
        guard conn.closed == "entfernt", let store, let id, let dev = myDevice else { hornSwitch(.lost); return }
        Task {
            guard let (d, code) = try? await store.apiResponse("api/calls/\(id)?device=\(dev)"), code == 200,
                  let c = ShareJSON.obj(d)?["call"] as? [String: Any] else { hornSwitch(.lost); return }
            if (c["state"] as? String) == "ended" { remoteEnded(reason: c["reason"] as? String); return }
            let mine = (c["mine"] as? [Any] ?? []).compactMap { $0 as? String }
            if !mine.contains(dev) { hornRemoved(by: nil) } else { hornSwitch(.lost) }
        }
    }
    private func hornPeersIn(_ conn: HornConn, _ peers: [String: UInt32]) {
        guard let H = horn else { return }
        conn.peers = peers
        hornMap()
        let mine = hornMyRef(conn)
        if HornLogic.unknownRefs(peers: peers, mine: mine, devOf: { self.hornDevOf(conn, $0) }), Self.hornNow() - H.rosterT > 2000 {
            H.rosterT = Self.hornNow()
            Task { await hornRosterFetch() }
        }
    }
    func hornRosterFetch() async {
        guard let store, let id, let dev = myDevice else { return }
        let asked = Date().timeIntervalSince1970
        if let (d, code) = try? await store.apiResponse("api/calls/\(id)?device=\(dev)"), code == 200, let c = ShareJSON.obj(d)?["call"] as? [String: Any] {
            rosterIn(CallRoom.legs(c["legs"]), rv: asked, extra: c)
        }
    }
    func hornRef(_ x: CallRoom.Leg, _ conn: HornConn) -> String? { HornLogic.ref(hp: x.hp, hpn: x.hpn, node: conn.node) }
    func hornMyRef(_ conn: HornConn) -> String {
        if let x = roster.leg(myDevice), let r = hornRef(x, conn) { return r }
        return conn.cand.peer
    }
    /// Kennung am Knoten -> Geraet: Teilnehmerliste (hp bzw. hpn dieses Knotens), dann hpn anderer Knoten dieses Servers
    /// (Kaskade: Teilnehmende behalten die Kennung ihres Knotens), dann state.hp (Knoten anderer Server)
    func hornDevOf(_ conn: HornConn, _ ref: String) -> String? {
        if ref.isEmpty { return nil }
        if let d = roster.legs.first(where: { hornRef($0, conn) == ref })?.device { return d }
        if let d = roster.legs.first(where: { $0.hpn.values.contains(ref) })?.device { return d }
        if let H = horn, let d = H.hpx.first(where: { $0.value.contains(ref) && roster.leg($0.key) != nil })?.key { return d }
        return nil
    }
    /// Eigene Kennungen an allen Knoten, mit denen das Geraet gerade verbunden ist (state.hp, hoechstens 4)
    func hornMyRefs() -> [String] {
        guard let H = horn else { return [] }
        return HornLogic.myRefs(H.conns.map { hornMyRef($0) })
    }
    /// CSRC -> Geraet je Verbindung; Empfangsplaetze ihren Besitzern zuordnen (FrameCryptor je Absender)
    func hornMap() {
        guard let H = horn else { return }
        let me = myDevice ?? ""
        for conn in H.conns {
            let slots = conn.all.flatMap(\.slots)
            conn.csrc = HornLogic.csrcMap(peers: conn.peers, slots: slots, me: me) { self.hornDevOf(conn, $0) }
            for L in conn.all {
                let bySlot = Dictionary(L.slots.map { ($0.i, $0) }, uniquingKeysWith: { a, _ in a })
                L.bind { i in HornLogic.owner(bySlot[i], conn.csrc) }
            }
        }
    }
    func hornConnState(_ conn: HornConn) {
        guard let H = horn, hornLive(H) else { return }
        let ls = conn.all
        let up = !ls.isEmpty && ls.allSatisfy { $0.state == .connected }
        let bad = ls.contains { $0.state == .failed || $0.state == .closed }
        if up && conn.up == 0 { conn.up = Self.hornNow() }
        if up || bad { conn.onUp?(up) }
    }

    // MARK: Uebernehmen, Wechseln

    /// Neue Verbindung uebernehmen (make-before-break): die bisherige laeuft weiter, bis die neue Rahmen liefert
    func hornAdopt(_ conn: HornConn, why: HornLogic.Why) {
        guard let H = horn else { return }
        let prev = H.cur, now = Self.hornNow()
        if H.next === conn { H.next = nil }
        H.cur = conn
        if let p = prev, p !== conn {
            p.retired = now; H.olds.append(p)
            for L in p.all { L.cam?.sender.track = nil; L.scr?.sender.track = nil }   // Bild nur noch ueber die neue
            for L in conn.all { L.cam?.sender.track = camera?.track; L.scr?.sender.track = sharing ? scrTrack : nil }
        }
        if H.sw == nil { H.sw = HornLogic.Switcher(node: conn.node, path: conn.path, now: now) }
        else { H.sw?.moved(now, node: conn.node, path: conn.path, why: why) }
        if prev != nil { H.trace("adopt \(prev!.node)->\(conn.node) \(conn.path) \(why.rawValue)") }
        H.retryTask?.cancel(); H.retryTask = nil; H.retry0 = 0; H.retryN = 0
        H.note(""); H.node = conn.node; H.path = conn.path; H.moving = !H.olds.isEmpty
        if !connected {
            connected = true; connectedAt = Date(); phase = .active; status = ""
            CallTones.shared.stop()
            CallSystem.shared.connected(self)
            screenOn()
        } else { status = "" }
        if phase != .active { phase = .active }
        hornMap(); hornDraw(); hornAudioRoute()
        hornStateOut()                                                      // P9/P10: neue Kennung (state.hp) an alle
        Task { await hornRunes() }
        if H.monTask == nil {
            H.monTask = Task { [weak self, weak H] in
                while !Task.isCancelled {
                    try? await Task.sleep(for: .milliseconds(Int(HornLogic.monMs)))
                    guard let self, let H, self.hornLive(H) else { return }
                    self.hornMon()
                }
            }
        }
        if ho.active { hoCheck() }
        pipRefresh()
    }
    /// why: lost | draining | better (to) | udp-loss / udp (path, selber Knoten) | moved (Saga: call.horn_move)
    func hornSwitch(_ why: HornLogic.Why, to: String? = nil, path: String? = nil) {
        guard let H = horn, hornLive(H), !H.switching else { return }
        H.trace("switch \(why.rawValue)")
        let cur = H.cur, hard = why == .lost || why == .draining || why == .moved, now = Self.hornNow()
        H.switching = true
        if why == .lost, let c = cur { H.bad[c.node] = now + HornLogic.badMs }   // Leeren ist kein Fehler
        if hard, let c = cur { H.leaving = (c.node, now + HornLogic.badMs) }
        if why == .lost { H.note(String(localized: "Verbindung zum Knoten \(cur?.node ?? "?") unterbrochen – wechsle …")) }
        else if why == .draining || why == .moved { H.note(String(localized: "Knoten \(cur?.node ?? "?") wird gewartet – wechsle ohne Unterbrechung …")) }
        Task {
            do {
                if let p = path, let c = cur {
                    let c2 = try await hornFresh(c.cand, avoid: [])
                    let conn = try await hornConnect(c2, path: p)
                    hornAdopt(conn, why: why)
                } else {
                    var list = try await hornCands(node: nil, avoid: H.avoid(now: Self.hornNow()))
                    if let to { list = list.filter { $0.node == to } }
                    else if hard, let c = cur {
                        let other = list.filter { $0.node != c.node }
                        if !other.isEmpty { list = other }                 // nur dieser eine Knoten: dorthin neu verbinden
                    }
                    if list.isEmpty, hard, let c = cur { list = [c.cand] }
                    _ = try await hornStart(list, why: why)
                }
                H.switching = false
            } catch {
                H.switching = false
                guard hornLive(H) else { return }
                if case HornFail.ended? = error as? HornFail { remoteEnded(reason: nil); return }
                if hard { hornRetry(why) }                                 // sonst bleibt die bisherige Verbindung
            }
        }
    }
    /// Kein Knoten erreichbar: Meldung, neuer Versuch (2 ... 15 s), nach 2 min aufgeben
    func hornRetry(_ why: HornLogic.Why) {
        guard let H = horn, hornLive(H) else { return }
        let now = Self.hornNow()
        if H.retry0 == 0 { H.retry0 = now }
        if now - H.retry0 > HornLogic.retryMax { fail(String(localized: "Verbindung zum Knoten verloren.")); return }
        H.retryN += 1
        let s = min(15, 2 * H.retryN)
        H.note(String(localized: "Kein Knoten erreichbar – neuer Versuch in \(s) s …"))
        if !connected { status = H.note }
        H.retryTask?.cancel()
        H.retryTask = Task { [weak self, weak H] in
            try? await Task.sleep(for: .seconds(s))
            guard let self, let H, self.hornLive(H), !Task.isCancelled else { return }
            H.bad = [:]
            do {
                let list = try await self.hornCands(node: nil, avoid: [])
                _ = try await self.hornStart(list, why: H.cur != nil ? .lost : why == .join ? .join : .retry)
            } catch {
                guard self.hornLive(H) else { return }
                if case HornFail.ended? = error as? HornFail { self.remoteEnded(reason: nil); return }
                self.hornRetry(why)
            }
        }
    }
    func hornConnClose(_ conn: HornConn) {
        guard !conn.dead else { return }
        conn.dead = true
        conn.onUp?(false)
        for L in conn.all {
            if let lid = L.id { HornNet.deleteLeg(conn.url, id: lid) }
            L.close()
        }
        if let H = horn {
            H.olds.removeAll { $0 === conn }
            if H.cur === conn { H.cur = nil }
            if H.next === conn { H.next = nil }
            H.moving = !H.olds.isEmpty
            if hornLive(H) { hornStateOut() }                                  // state.hp ohne die geschlossene Verbindung
        }
    }

    // MARK: Laufende Beobachtung (alle 0,5 s)

    func hornMon() {
        guard let H = horn, hornLive(H) else { return }
        let now = Self.hornNow(), cur = H.cur
        for c in H.conns { for L in c.all { L.poll(now: now) } }
        if let cur, !H.switching, let d = H.sw?.conn(now, cur.worst()) {
            H.trace("ice \(cur.worst())"); hornSwitch(d.why); return
        }
        for c in H.conns where c.rx == 0 && c.up > 0 {                     // neues Bein liefert Rahmen?
            if c.all.contains(where: { $0.anyLive(now: now) }) || now - c.up > 1500 { c.rx = now }
        }
        // Alte Verbindungen schliessen: 2 s nachdem die neue liefert, wenn dort niemand mehr nur ueber die alte zu hoeren
        // ist und man selbst nicht gerade spricht, waehrend am alten Knoten noch andere zuhoeren; hoechstens 15 s
        if let cur, cur.rx > 0 {
            if !H.olds.isEmpty { Task { await hornSelfLevel(cur) } }
            let onCur = hornLiveOwners(cur, now: now)
            for o in H.olds {
                var uniq = false
                if let L = o.aLeg {
                    let audio = L.slots.filter { $0.cls == "audio" }
                    for (k, i) in L.audioRecv.enumerated() {
                        guard let d = HornLogic.owner(k < audio.count ? audio[k] : nil, o.csrc), !onCur.contains(d), L.live(i, now: now) else { continue }
                        if (H.levels[d] ?? 0) > 0.02 { uniq = true }
                    }
                }
                let mine = hornMyRef(o), listeners = o.peers.keys.filter { $0 != mine }.count
                let needOld = uniq || (listeners > 0 && H.selfLevel > 0.02)
                let dead = o.all.contains { $0.state == .failed || $0.state == .closed }
                if dead || o.closed != nil || HornLogic.closeOld(now: now, up: max(cur.rx, o.retired), waiting: needOld ? 1 : 0) {
                    H.trace("zu \(o.node)"); hornConnClose(o)
                }
            }
        }
        hornLevels(now: now)
        hornAudioRoute()
        hornDraw()
        // Ping ueber den Ereigniskanal (RTT ueber denselben Weg)
        if let cur, let ev = cur.all.first?.events, ev.isOpen, now - cur.pingAt >= HornLogic.pingEvery {
            cur.pingAt = now; cur.pingN += 1; cur.pings[cur.pingN] = now; cur.pings[cur.pingN - 10] = nil
            ev.send(HornLogic.ping(cur.pingN))
        }
        if let cur, now - H.statT >= HornLogic.statsEvery { H.statT = now; Task { await hornStats(cur) } }
        // Auf TCP/TLS zu einem Knoten mit UDP: alle 10 s die UDP-Probe (Rueckkehr nach 30 s ok, hoechstens 1x je 5 min)
        if let cur, cur.path != "udp", cur.cand.udp, cur.cand.tokens["both"] != nil, now - H.udpProbeAt >= HornLogic.udpProbeEvery {
            H.udpProbeAt = now
            Task { let m = await HornNet.probe(cur.cand); if hornLive(H), H.cur === cur { H.meas[cur.node] = m } }
        }
        // Andere Kandidaten (Kaskade, P9): alle 30 s frische Liste, messen, Hysterese entscheidet
        if let cur, !H.switching, now - (H.candT > 0 ? H.candT : cur.created) >= HornLogic.candsEvery {
            H.candT = now
            Task {
                guard let list = try? await hornCands(node: nil, avoid: []) else { return }
                let others = list.filter { $0.node != cur.node }
                guard !others.isEmpty, hornLive(H) else { return }
                let m = await hornMeasure(list)
                let curScore = HornLogic.score(HornLogic.Measure(ok: true, rtts: cur.rtts.isEmpty ? (m[cur.node]?.rtts ?? []) : cur.rtts, udp: cur.path == "udp" ? 1 : nil),
                                               HornLogic.Candidate(node: cur.node, udp: cur.path == "udp", here: true, load: list.first { $0.node == cur.node }?.load ?? 0))
                if let x = H.sw?.observe(Self.hornNow(), cur: curScore, others: others.map { ($0.node, HornLogic.score(m[$0.node], $0)) }) {
                    hornSwitch(.better, to: x.to)
                }
            }
        }
        // Schluessel: Nachforderung, Ablauf alter Plaetze, Wechsel nach 6 h
        for d in H.need.due(now: now) {
            hornAck(to: d, n: H.recv[d]?.n ?? -1, s: H.recv[d]?.s ?? "0000000000000000", ok: false)
        }
        for (d, var k) in H.recv {
            let due = k.due(now: now)
            H.recv[d] = k
            for kid in due { HornCryptor.scrub(H.provider, device: d, kid: kid) }
        }
        if now - H.ownAt >= HornLogic.rekeyAfter { hornRekey(H) }
    }
    /// Absender, die auf der aktuellen Verbindung stabil zu hoeren sind
    private func hornLiveOwners(_ conn: HornConn, now: Double) -> Set<String> {
        guard let L = conn.aLeg else { return [] }
        let audio = L.slots.filter { $0.cls == "audio" }
        var out = Set<String>()
        for (k, i) in L.audioRecv.enumerated() where L.steady(i, now: now) {
            if let d = HornLogic.owner(k < audio.count ? audio[k] : nil, conn.csrc) { out.insert(d) }
        }
        return out
    }
    private func hornSelfLevel(_ conn: HornConn) async {
        guard let H = horn, let pc = conn.aLeg?.pc else { return }
        let rep = await pc.statsAsync()
        var lv = 0.0
        for s in rep.statistics.values where s.type == "media-source" && (s.values["kind"] as? String) == "audio" {
            lv = (s.values["audioLevel"] as? NSNumber)?.doubleValue ?? lv
        }
        H.selfLevel = mic ? lv : 0
    }
    /// Pegel je Absender (Tonplaetze), Sprechring mit 600 ms Nachlauf
    private func hornLevels(now: Double) {
        guard let H = horn else { return }
        var lv: [String: Double] = [:]
        for c in H.conns {
            guard let L = c.aLeg else { continue }
            let audio = L.slots.filter { $0.cls == "audio" }
            for (k, i) in L.audioRecv.enumerated() where L.live(i, now: now) {
                guard let d = HornLogic.owner(k < audio.count ? audio[k] : nil, c.csrc), let l = L.level(i) else { continue }
                lv[d] = max(lv[d] ?? 0, l)
            }
        }
        H.levels = lv
        var sp = Set<String>()
        for (d, l) in lv where l > 0.03 && H.st[d]?.mic != false { H.speakAt[d] = now }
        for (d, t) in H.speakAt where now - t < 600 { sp.insert(d) }
        if sp.isEmpty, lv.isEmpty, let s = H.spk { sp.insert(s) }         // ohne Pegel: Sprecherplatz des Knotens
        if sp != H.speaking { H.speaking = sp }
    }
    /// Qualitaet (RTT, Verlust, Einfrieren), UDP-Regeln, Nachforderung aus getStats
    private func hornStats(_ conn: HornConn) async {
        guard let H = horn else { return }
        var rtt: Double?, lost = 0, recv = 0, fz = 0
        for L in conn.all {
            guard let pc = L.pc else { continue }
            let rep = await pc.statsAsync()
            var tracks: [String: Int] = [:]
            for i in L.recv.indices { if let t = L.track(i)?.trackId { tracks[t] = i } }
            for s in rep.statistics.values {
                let v = s.values
                if s.type == "candidate-pair", (v["nominated"] as? NSNumber)?.boolValue == true, (v["state"] as? String) == "succeeded",
                   let r = (v["currentRoundTripTime"] as? NSNumber)?.doubleValue { rtt = max(rtt ?? 0, r * 1000) }
                guard s.type == "inbound-rtp" else { continue }
                let pk = (v["packetsReceived"] as? NSNumber)?.intValue ?? 0
                lost += (v["packetsLost"] as? NSNumber)?.intValue ?? 0; recv += pk
                guard (v["kind"] as? String) == "video" else { continue }
                fz += (v["freezeCount"] as? NSNumber)?.intValue ?? 0
                // Pakete ohne dekodierte Bilder = unlesbar (Schluessel fehlt) -> Nachforderung
                if let tid = v["trackIdentifier"] as? String, let i = tracks[tid], let d = L.ownerOfCryptor(i) {
                    let now = HornLogic.RxSample(packets: pk, decoded: (v["framesDecoded"] as? NSNumber)?.intValue ?? 0)
                    if let u = HornLogic.unreadable(prev: L.rxStats[tid], now: now) { if u { H.need.bad(d, now: Self.hornNow()) } else { H.need.good(d) } }
                    L.rxStats[tid] = now
                }
            }
        }
        guard hornLive(H), !conn.dead else { return }
        let t = Self.hornNow()
        let p = conn.q5 ?? (lost, recv, fz, t)
        let dl = lost - p.lost, dr = recv - p.recv
        let loss = dl + dr > 0 ? Double(max(0, dl)) / Double(dl + dr) : 0
        let mins = max(1.0 / 60, (t - p.t) / 60_000)
        conn.q5 = (lost, recv, fz, t)
        if rtt == nil && !conn.rtts.isEmpty { rtt = HornLogic.median(conn.rtts) }
        conn.rtt = rtt.map { $0.rounded() }; conn.loss = (loss * 1000).rounded() / 1000; conn.freezes = (Double(fz - p.fz) / mins * 10).rounded() / 10
        conn.quality = HornLogic.quality(rtt: conn.rtt, loss: conn.loss, freezes: conn.freezes)
        if H.cur === conn {
            if H.quality != conn.quality { H.quality = conn.quality }
            if H.rtt != conn.rtt { H.rtt = conn.rtt }
            if H.loss != conn.loss { H.loss = conn.loss }
            if !H.switching {
                let udpOk = conn.path != "udp" && conn.cand.udp && H.meas[conn.node]?.udp != nil
                if let x = H.sw?.udp(t, loss: loss, ok: udpOk), let p = x.path, p != conn.path, p != "udp" || conn.cand.tokens["both"] != nil {
                    hornSwitch(x.why, path: p)
                }
            }
        }
    }

    // MARK: Ton

    /// Je Absender spielt nur EINE Verbindung: die neue, sobald sie ihn stabil liefert, sonst die alte (keine Luecke, kein
    /// Doppel). Eigene Geraete (Begleitgeraet) nie abspielen; Wiedergabe aus (Begleitgeraet) = alles stumm.
    func hornAudioRoute() {
        guard let H = horn else { return }
        let now = Self.hornNow(), cur = H.cur
        let onCur = cur.map { hornLiveOwners($0, now: now) } ?? []
        let ownDevs = Set(roster.legs.filter { $0.who == me }.map(\.device))
        for c in H.conns {
            guard let L = c.aLeg else { continue }
            let audio = L.slots.filter { $0.cls == "audio" }
            for (k, i) in L.audioRecv.enumerated() {
                let ow = HornLogic.owner(k < audio.count ? audio[k] : nil, c.csrc)
                let dup = c !== cur && ow.map { onCur.contains($0) } == true
                let on = sound && !dup && !(ow.map { ownDevs.contains($0) } ?? false)
                if let t = L.track(i), t.isEnabled != on { t.isEnabled = on }
            }
        }
    }
    func hornMic() {
        guard let H = horn else { return }
        for c in H.conns { if let L = c.aLeg, let t = L.tr, t.sender.track !== audioTrack { t.sender.track = audioTrack } }
        hornStateOut()
    }
    func hornCam() {
        guard let H = horn else { return }
        for c in H.conns where c === H.cur || H.cur == nil { c.vLeg?.cam?.sender.track = camera?.track }
        hornStateOut(); hornDraw()
    }
    /// Bildschirm ueber den Knoten: eigener Sender je Bild-Leitung (Platz "screen" bei den anderen)
    func hornScreen() {
        guard let H = horn else { return }
        for c in H.conns where c === H.cur || H.cur == nil { c.vLeg?.scr?.sender.track = sharing ? scrTrack : nil }
        hornStateOut(); hornDraw()
    }

    // MARK: Ansicht

    func hornDraw() {
        guard let H = horn else { return }
        let me = myDevice ?? "", now = Self.hornNow()
        var vid: [String: RTCVideoTrack] = [:], scr: (dev: String, track: RTCVideoTrack?)?, spk: String?
        let conns = H.conns.filter { $0 === H.cur } + H.conns.filter { $0 !== H.cur }
        for c in conns {
            for L in c.all {
                for s in L.slots where s.cls != "audio" && s.csrc != nil && !s.paused {
                    guard var d = HornLogic.owner(s, c.csrc), s.i < L.recv.count else { continue }
                    let tr = L.track(s.i) as? RTCVideoTrack
                    let vd = L.actualCSRC(s.i).flatMap { c.csrc[$0] }
                    if s.cls == "screen" {
                        if scr == nil && (vd == nil || vd == d) && H.st[d]?.screen != false { scr = (d, tr) }
                        continue
                    }
                    if s.cls == "speaker" && (spk == nil || c === H.cur) { spk = d }
                    if let vd { d = vd }
                    if conns.count > 1 && !L.live(s.i, now: now) { continue }
                    if vid[d] == nil, let tr { vid[d] = tr }
                }
            }
        }
        H.spk = spk ?? H.spk
        let devs = roster.legs.map(\.device).filter { $0 != me }
        if let s = H.spk, !devs.contains(s) { H.spk = nil }
        if let p = H.pin, !devs.contains(p) { H.pin = nil }
        let lay = HornLogic.tiles(devs, moving: Set(vid.keys), speaker: H.pin ?? H.spk, view: H.view)
        let t: [HornTile] = lay.list.compactMap { x in
            guard let l = roster.leg(x.dev) else { return nil }
            let s = H.st[x.dev]                                             // unbekannt (noch kein state) = an
            return HornTile(id: x.dev, name: l.name, devName: l.devName, avatar: l.avatar, video: s?.cam != false ? vid[x.dev] : nil, moving: x.moving, big: x.big,
                            mic: s?.mic ?? true, cam: s?.cam ?? true, speaking: H.speaking.contains(x.dev), screen: scr?.dev == x.dev, wait: H.cur == nil)
        }
        if H.tiles != t { H.tiles = t }
        if H.more != lay.more { H.more = lay.more }
        let sh = scr.map { HornShare(dev: $0.dev, name: roster.leg($0.dev)?.name ?? "?", track: $0.track) }
        if H.share != sh { H.share = sh }
        // Bild-im-Bild: Bildschirm, sonst Sprecher, sonst das erste Bild
        let fd = sh?.dev ?? (H.spk.flatMap { vid[$0] != nil ? $0 : nil }) ?? devs.first { vid[$0] != nil }
        let ft = sh?.track ?? fd.flatMap { vid[$0] }
        let fn = fd.flatMap { roster.leg($0)?.name } ?? title
        if H.focusTrack !== ft || H.focusName != fn { H.focusTrack = ft; H.focusName = fn; pipRefresh() }
        hornPeople()
    }
    func hornSetView(_ v: String) {
        guard let H = horn else { return }
        H.view = v == "speaker" ? "speaker" : "grid"
        if H.view == "grid" { H.pin = nil }
        hornDraw()
    }
    func hornPin(_ dev: String) {
        guard let H = horn else { return }
        H.pin = H.pin == dev ? nil : dev
        hornSetView("speaker")
    }
    /// Gastgeber: dieses Konto hat den Gruppenanruf begonnen
    var hornHost: Bool { callHost }
    func hornPeople() {
        guard let H = horn else { return }
        let me = myDevice ?? H.meDevice ?? ""
        let p: [HornPerson] = roster.legs.map { x in
            let mine = x.device == me
            let sub = [x.devName, x.companion ? String(localized: "Begleitgerät") : ""].filter { !$0.isEmpty }.joined(separator: " · ")
            return HornPerson(id: x.device, name: x.name, avatar: x.avatar, sub: sub, mine: mine, ownPerson: x.who == self.me,
                              mic: mine ? self.mic : (H.st[x.device]?.mic ?? true), speaking: H.speaking.contains(x.device), unverified: H.unverified.contains(x.device))
        }
        if H.people != p { H.people = p }
        if H.host != hornHost { H.host = hornHost }
    }

    // MARK: Schluessel und Signale

    private func hornSetOwn(_ H: HornSession, _ g: HornLogic.OwnKey.Gen) {
        guard let me = myDevice else { return }
        H.provider.setKey(g.key, with: Int32(g.kid), forParticipant: me)
    }
    private func hornKeyIndex(_ H: HornSession) {
        for c in H.conns { for L in c.all { L.setKeyIndex(H.own.active.kid) } }
    }
    /// Teilnehmerliste geaendert: Schluesselplan (Neue: geratschter Schluessel; Abgang: neuer Schluessel), Ratsche bei
    /// den Empfaengern vorab, Runensiegel, Kacheln
    func hornRoster(added: [CallRoom.Leg], removed: [CallRoom.Leg]) {
        guard let H = horn else { return }
        let me = myDevice ?? "", now = Self.hornNow()
        if !added.contains(where: { $0.device == me }), added.contains(where: { $0.device != me }) {
            for (d, var k) in H.recv {                                      // jeder Beitritt: alle Sender ratschen
                if let a = k.joined(now: now) { for (kid, key) in a.set { H.provider.setKey(key, with: Int32(kid), forParticipant: d) } }
                H.recv[d] = k
            }
        }
        for x in removed { H.recv[x.device] = nil; H.st[x.device] = nil; H.need.forget(x.device); H.acked[x.device] = nil }
        hornKeys()
        if !H.q.isEmpty, H.started {                                        // Signale, die vor der Liste kamen
            let q = H.q; H.q = []
            Task { for s in q { await hornSig(s) } }
        }
        hornMap(); hornDraw()
        Task { await hornRunes() }
        CallSystem.shared.groupInfo(self, count: roster.legs.count)
    }
    func hornKeys() {
        guard let H = horn, H.started, hornLive(H) else { return }
        let me = myDevice ?? ""
        let devs = Set(roster.legs.map(\.device).filter { $0 != me })
        let ch = H.plan.update(devs)
        if !ch.gone.isEmpty {                                               // Abgaenge binnen 0,5 s: ein Wechsel
            H.rekeyTask?.cancel()
            H.rekeyTask = Task { [weak self, weak H] in
                try? await Task.sleep(for: .milliseconds(500))
                guard let self, let H, self.hornLive(H), !Task.isCancelled else { return }
                self.hornRekey(H)
            }
        }
        guard !ch.add.isEmpty else { return }
        let g = ch.first ? H.own.newest : H.own.ratchet()                  // erste Runde: Startschluessel an alle
        if !ch.first { hornSetOwn(H, g); if H.own.pending == nil { hornKeyIndex(H) } }
        var b: [String: String] = [:]
        for d in ch.add { b[d] = H.own.body(g) }
        Task {
            await hornSend(b)
            hornStateOut(only: ch.add)
        }
    }
    /// Neuer Zufallsschluessel an alle Verbliebenen, Umschalten nach 1,5 s
    private func hornRekey(_ H: HornSession) {
        let g = H.own.rekey(now: Self.hornNow())
        H.ownAt = Self.hornNow()
        hornSetOwn(H, g)
        var b: [String: String] = [:]
        for d in H.plan.known { b[d] = H.own.body(g) }
        Task { await hornSend(b) }
        H.commitTask?.cancel()
        H.commitTask = Task { [weak self, weak H] in
            try? await Task.sleep(for: .milliseconds(Int(HornE2E.switchMs)))
            guard let self, let H, self.hornLive(H), !Task.isCancelled else { return }
            if H.own.commit() != nil { self.hornKeyIndex(H) }
        }
    }
    /// Gegenueber zum Versiegeln (Schluessel je Person, geprueft wie im Mesh)
    private func hornLink(_ dev: String) async -> CallLink? {
        guard let H = horn else { return nil }
        if let L = H.links[dev] { return L }
        guard let x = roster.leg(dev), let L = try? await makeLink(x) else { return nil }
        H.links[dev] = L
        return L
    }
    /// Ein POST /signal an mehrere Geraete (nur an Geraete in der signierten Liste ihrer Person)
    func hornSend(_ bodies: [String: String]) async {
        guard let store, let id, let dev = myDevice, !ending, !bodies.isEmpty else { return }
        var sig: [String: String] = [:]
        for (d, body) in bodies {
            guard let L = await hornLink(d) else { continue }
            if let s = seal(L, d, body) { sig[d] = s }
        }
        guard !sig.isEmpty, !ending else { return }
        if let (_, code) = try? await store.apiResponse("api/calls/\(id)/signal", method: "POST", body: ["device": dev, "signals": sig]), code == 409 {
            remoteEnded(reason: "gone")
        }
    }
    func hornAck(to: String, n: Int, s: String, ok: Bool) {
        guard let H = horn else { return }
        H.acks[to] = HornLogic.ackBody(n: n, s: s, ok: ok)
        guard H.ackTask == nil else { return }
        H.ackTask = Task { [weak self, weak H] in                           // gebuendelt (100 ms)
            try? await Task.sleep(for: .milliseconds(100))
            guard let self, let H else { return }
            let b = H.acks; H.acks = [:]; H.ackTask = nil
            await self.hornSend(b)
        }
    }
    /// Eigener Zustand (Mikrofon, Kamera, Bildschirm) an alle bzw. nur an neue Geraete - gebuendelt
    func hornStateOut(only: [String]? = nil) {
        guard let H = horn, hornLive(H) else { return }
        let body = HornLogic.stateBody(mic: mic && audioTrack != nil && !companion, cam: cam && camera != nil && !camPaused, screen: sharing,
                                       hp: hornMyRefs())
        if let only {
            var b: [String: String] = [:]
            for d in only where H.plan.known.contains(d) { b[d] = body }
            Task { await hornSend(b) }
            return
        }
        H.stTask?.cancel()
        H.stTask = Task { [weak self, weak H] in
            try? await Task.sleep(for: .milliseconds(300))
            guard let self, let H, self.hornLive(H), !Task.isCancelled else { return }
            let body = HornLogic.stateBody(mic: self.mic && self.audioTrack != nil && !self.companion, cam: self.cam && self.camera != nil && !self.camPaused,
                                           screen: self.sharing, hp: self.hornMyRefs())
            var b: [String: String] = [:]
            for d in H.plan.known { b[d] = body }
            await self.hornSend(b)
        }
        hornPeople()
    }
    /// Signal im Knoten-Anruf: fkey, fkey-ack (ok:false = Nachforderung), state; Uebernahme (state.ready + replaces)
    func hornSig(_ s: [String: Any]) async {
        guard let H = horn else { return }
        guard H.started, let from = s["from_device"] as? String else { H.q.append(s); return }
        guard roster.leg(from) != nil else { H.q.append(s); if H.q.count > 200 { H.q.removeFirst() }; return }
        guard let L = await hornLink(from), let body = await openBody(L, s), hornLive(H) else { return }
        let o = try? JSONSerialization.jsonObject(with: Data(body.utf8))
        if let k = HornE2E.parseFKey(o) {
            var pk = H.recv[from] ?? HornLogic.PeerKeys()
            if let a = pk.fkey(k, now: Self.hornNow()) {
                if a.reset { for kid in 0..<HornE2E.ring { HornCryptor.scrub(H.provider, device: from, kid: kid) } }
                for (kid, key) in a.set { H.provider.setKey(key, with: Int32(kid), forParticipant: from) }
                H.need.good(from)
                hornAck(to: from, n: k.n, s: k.s, ok: true)
            }
            H.recv[from] = pk
        } else if let a = HornLogic.parseAck(o) {
            if !a.ok { await hornSend([from: H.own.body(H.own.newest)]) }   // Nachforderung: nur den neuesten
            else if a.n == H.own.pending?.n, H.plan.known.allSatisfy({ $0 == from || H.acked[$0] == a.n }) {
                if H.own.commit() != nil { hornKeyIndex(H) }                 // alle haben bestaetigt: frueher umschalten
            }
            if a.ok { H.acked[from] = a.n }
        } else if let st = HornLogic.parseState(o) {
            H.st[from] = st
            if let hp = HornLogic.parseHp(o), H.hpx[from] != hp { H.hpx[from] = hp; hornMap() }   // P9/P10: Kennungen des Absenders
            let m = CallV3.meta(body)
            if CallRoom.handoverOut(ready: m.ready == true, replaces: m.replaces, here: myDevice ?? "", fromOwn: roster.leg(from)?.who == me) {
                handoverOut(from: from); return
            }
            hornDraw()
        }
    }
    /// Runensiegel aus den Geraeteschluesseln aller aktiven Endpunkte (inkl. des eigenen)
    func hornRunes() async {
        guard let H = horn, let id, let k = store?.chat.keys else { return }
        var pks: [Data] = [], unver: [String] = []
        for x in roster.legs {
            if x.device == k.deviceID { pks.append(k.sign.pk); continue }
            if let L = await hornLink(x.device), let d = L.devs[x.device] { pks.append(d.sign) } else { unver.append(x.device) }
        }
        let key = roster.legs.map(\.device).sorted().joined(separator: ",") + "|\(pks.count)"
        guard hornLive(H), key != H.runesKey else { return }
        H.runesKey = key
        let r = HornE2E.runeSeal(callId: id, signPks: pks)
        H.runes = r.runes; H.runeNames = r.idx.map { HornE2E.runeNames[$0] }; H.unverified = unver
        hornPeople()
    }

    // MARK: Gastgeber

    func hornModerate(_ dev: String, action: String) {
        guard let store, let id, let me = myDevice else { return }
        let name = roster.leg(dev)?.name ?? "?"
        Task {
            let r = try? await store.apiResponse("api/calls/\(id)/moderate", method: "POST", body: ["device": me, "target": dev, "action": action])
            if let (_, code) = r, (200..<300).contains(code) {
                toast = action == "mute" ? String(localized: "\(name) wurde stummgeschaltet.") : String(localized: "\(name) wurde aus dem Anruf entfernt.")
            } else {
                toast = r.flatMap { ShareJSON.obj($0.0).flatMap { ShareJSON.str($0["error"]) } } ?? String(localized: "Das ging gerade nicht.")
            }
        }
    }
    /// Live-Ereignis call.mod (Gastgeber hat dieses Geraet stummgeschaltet bzw. diese Person entfernt)
    func hornModIn(_ d: [String: Any]) {
        guard (d["call_id"] as? String) == id, !ending, let me = myDevice else { return }
        let by = ShareJSON.str(d["by"]) ?? "?"
        if (d["action"] as? String) == "mute", (d["device"] as? String) == me {
            if mic { toggleMic() }
            toast = String(localized: "\(by) hat dich stummgeschaltet. Du kannst das Mikrofon selbst wieder einschalten.")
        } else if (d["action"] as? String) == "remove", let store, let id {
            Task {
                guard let (r, code) = try? await store.apiResponse("api/calls/\(id)?device=\(me)"), code == 200,
                      let c = ShareJSON.obj(r)?["call"] as? [String: Any] else { return }
                if !(c["mine"] as? [Any] ?? []).contains(where: { ($0 as? String) == me }) { hornRemoved(by: by) }
            }
        }
    }
    func hornRemoved(by: String?) {
        finish(nil, message: by.map { String(localized: "\($0) hat dich aus dem Anruf entfernt.") } ?? String(localized: "Du wurdest aus dem Anruf entfernt."))
    }
    /// Live-Ereignis call.horn_move (Knoten wird geleert, aktualisiert oder entfernt): neue Kandidaten, make-before-break
    func hornMoveIn(_ d: [String: Any]) {
        guard (d["call_id"] as? String) == id, let H = horn, let node = d["node"] as? String, H.cur?.node == node else { return }
        hornSwitch(.moved)
    }

    /// Alles abbauen (Auflegen, Ende)
    func hornStop() {
        guard let H = horn else { return }
        H.monTask?.cancel(); H.retryTask?.cancel(); H.ackTask?.cancel(); H.rekeyTask?.cancel(); H.commitTask?.cancel(); H.stTask?.cancel()
        for c in H.conns + H.olds { hornConnClose(c) }
        H.cur = nil; H.next = nil; H.olds = []
        for (_, L) in H.links { L.close() }
        H.links = [:]
        H.tiles = []; H.share = nil; H.focusTrack = nil
    }
}
