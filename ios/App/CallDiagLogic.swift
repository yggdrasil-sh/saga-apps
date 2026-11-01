import Foundation

// 2.5 Anruf-Diagnose (Analyse 1.11.: Videoanruf ueber den Knoten ruckelte, Qualitaetsanzeige sprang rot/gruen).
// Reine Logik ohne WebRTC und ohne UI (Tests/Logic/CallDiagTests.swift):
// - Gauge: Empfangsqualitaet ueber ein gleitendes 30-s-Fenster statt der letzten 3 s (ein Einfrieren in 3 s waren
//   20 je Minute = sofort rot). Einfrierer je Minute werden auf mindestens 30 s bezogen.
// - Debounce: die Anzeige wechselt erst nach zwei gleichen Messungen in Folge (rot erst nach zwei schlechten, zurueck
//   nach zwei guten). Schwellen bleiben in HornLogic.quality.
// - Hold: Waerme-Hysterese - einmal heiss mindestens 60 s heiss, zurueck erst nach 60 s am Stueck unter der Schwelle.
// - Buffer: Proben und Ereignisse fuer POST /api/calls/diag (nur mit Server-Feature calldiag1): alle 30 s und beim
//   Auflegen, hoechstens ein POST je 15 s, <= 32 KB, <= 200 Proben, <= 100 Ereignisse. Quellen nur als Kuerzel (p1, p2 ...),
//   nie Name oder Adresse.
enum CallDiag {
    static let feature = "calldiag1"
    static let path = "api/calls/diag"
    static let window = 30_000.0, every = 30_000.0, minGap = 15_000.0, holdMs = 60_000.0
    static let maxBytes = 32 * 1024, maxSamples = 200, maxEvents = 100, maxText = 120

    // MARK: Empfangsqualitaet

    /// Zaehlerstaende (kumulativ, aus getStats) mit Zeit -> Verlust und Einfrierer je Minute ueber die letzten 30 s
    struct Gauge {
        struct Pt: Equatable { var t: Double; var lost: Int; var recv: Int; var fz: Int }
        private(set) var pts: [Pt] = []
        var window = CallDiag.window
        init(window: Double = CallDiag.window) { self.window = window }
        mutating func push(t: Double, lost: Int, recv: Int, fz: Int) -> (loss: Double, freezes: Double) {
            if let l = pts.last, lost < l.lost || recv < l.recv || fz < l.fz || t < l.t { pts = [] }   // Zaehler neu (anderes Bein)
            pts.append(Pt(t: t, lost: lost, recv: recv, fz: fz))
            while pts.count > 2, pts[1].t <= t - window { pts.removeFirst() }   // Grundlage: letzter Stand am Fensteranfang
            let b = pts[0]
            let dl = lost - b.lost, dr = recv - b.recv
            let loss = dl + dr > 0 ? Double(max(0, dl)) / Double(dl + dr) : 0
            let mins = max(window, t - b.t) / 60_000
            return (loss, Double(max(0, fz - b.fz)) / mins)
        }
    }

    /// Anzeige erst nach `need` gleichen Messungen in Folge wechseln
    struct Debounce<T: Equatable> {
        private(set) var shown: T
        private var pend: T?
        private var n = 0
        let need: Int
        init(_ start: T, need: Int = 2) { shown = start; self.need = need }
        mutating func push(_ v: T) -> T {
            if v == shown { pend = nil; n = 0; return shown }
            if let p = pend, p == v { n += 1 } else { pend = v; n = 1 }
            if n >= need { shown = v; pend = nil; n = 0 }
            return shown
        }
    }

    /// Waerme-Hysterese: heiss -> mindestens `ms` heiss; zurueck erst nach `ms` am Stueck unter der Schwelle
    struct Hold {
        let ms: Double
        private(set) var on = false
        private var since = 0.0
        private var coolSince: Double?
        init(ms: Double = CallDiag.holdMs) { self.ms = ms }
        mutating func update(now: Double, raw: Bool) -> Bool {
            if raw {
                if !on { on = true; since = now }
                coolSince = nil
                return true
            }
            guard on else { return false }
            let c = coolSince ?? now
            coolSince = c
            if now - since >= ms && now - c >= ms { on = false; coolSince = nil }
            return on
        }
    }

    // MARK: Proben und Ereignisse

    struct Sample: Equatable {
        var t: Int, dir: String, src: String, rid: String
        var fps = 0, h = 0, kbps = 0, lost = 0, nack = 0, pli = 0, freeze = 0, kf = 0, rtt = 0
        var qlr = "", enc = ""
        var json: [String: Any] {
            ["t": t, "dir": dir, "src": src, "rid": rid, "fps": fps, "h": h, "kbps": kbps, "lost": lost, "nack": nack, "pli": pli,
             "freeze": freeze, "kf": kf, "rtt": rtt, "qlr": qlr, "enc": enc]
        }
    }
    struct Event: Equatable {
        var t: Int, k: String, d: String
        var json: [String: Any] { ["t": t, "k": k, "d": d] }
    }

    static let kinds: Set<String> = ["layers", "thermal", "switch", "quality", "path"]
    static let qlrs: Set<String> = ["none", "cpu", "bandwidth", "other"]

    /// Kurztext: hoechstens 120 Zeichen, IPv4-/IPv6-artige Adressen entfernt, eine Zeile
    static func clean(_ s: String) -> String {
        var x = s.replacingOccurrences(of: "\n", with: " ")
        for p in [#"\b\d{1,3}(\.\d{1,3}){3}\b"#, #"\b[0-9a-fA-F]{1,4}(:[0-9a-fA-F]{0,4}){3,7}\b"#] {
            x = x.replacingOccurrences(of: p, with: "*", options: .regularExpression)
        }
        return String(x.prefix(maxText))
    }
    static func transport(horn path: String) -> String { path == "udp" ? "udp" : path == "tcp" ? "tcp" : path == "turn" ? "tls" : "?" }
    /// P2P: Weg aus CallV3.Path (direct, relay-udp, relay-tcp)
    static func transport(p2p wire: String?) -> String {
        switch wire { case "direct", "relay-udp": return "udp"; case "relay-tcp": return "tcp"; default: return "?" }
    }

    struct Buffer {
        let call: String, plat: String, app: String
        let t0: Double
        var path = "p2p", transport = "?", node = ""
        private(set) var samples: [Sample] = []
        private(set) var events: [Event] = []
        private(set) var lastPost: Double?
        private var aliases: [String: String] = [:]
        private var prev: [String: Double] = [:]
        private var rate: [String: (b: Double, t: Double)] = [:]
        private var sampled: [String: Double] = [:]
        init(call: String, plat: String, app: String, t0: Double) { self.call = call; self.plat = plat; self.app = app; self.t0 = t0 }

        func ms(_ now: Double) -> Int { max(0, Int((now - t0).rounded())) }
        /// Geraete-ID -> p1, p2 ... (je Anruf fest; nie Name oder ID im Inhalt)
        mutating func alias(_ dev: String?) -> String {
            guard let dev, !dev.isEmpty else { return "?" }
            if let a = aliases[dev] { return a }
            let a = "p\(aliases.count + 1)"; aliases[dev] = a; return a
        }
        /// Zuwachs eines Zaehlers seit der letzten Probe (erste Probe: 0; Zaehler neu: 0)
        mutating func delta(_ key: String, _ v: Double) -> Int {
            defer { prev[key] = v }
            guard let p = prev[key], v >= p else { return 0 }
            return Int((v - p).rounded())
        }
        mutating func kbps(_ key: String, bytes: Double, t: Double) -> Int {
            defer { rate[key] = (bytes, t) }
            guard let p = rate[key], t > p.t, bytes >= p.b else { return 0 }
            return Int(((bytes - p.b) * 8 / (t - p.t)).rounded())          // Bytes je ms * 8 = kbit/s
        }
        /// hoechstens eine Probe je Quelle und `every` ms (P2P-Statistik laeuft im 1-2-s-Takt)
        mutating func sampleDue(_ key: String, now: Double, every: Double = 3000) -> Bool {
            if let s = sampled[key], now - s < every { return false }
            sampled[key] = now; return true
        }
        /// Probe aus einem getStats-Eintrag (inbound-rtp/outbound-rtp, nur Video); src = Kuerzel, key = eindeutig je Spur
        mutating func sample(type: String, values v: [String: Any], src: String, key: String, now: Double, rtt: Double?) -> Sample? {
            func n(_ k: String) -> Double? {
                if let x = v[k] as? NSNumber { return x.doubleValue }
                if let x = v[k] as? String { return Double(x) }
                return nil
            }
            guard type == "inbound-rtp" || type == "outbound-rtp" else { return nil }
            let kind = (v["kind"] as? String) ?? (v["mediaType"] as? String) ?? ""
            guard kind == "video" else { return nil }
            let out = type == "outbound-rtp"
            var s = Sample(t: ms(now), dir: out ? "out" : "in", src: src, rid: out ? String(((v["rid"] as? String) ?? "").prefix(2)) : "")
            s.fps = Int((n("framesPerSecond") ?? 0).rounded())
            s.h = Int(n("frameHeight") ?? 0)
            s.kbps = kbps(key, bytes: n(out ? "bytesSent" : "bytesReceived") ?? 0, t: now)
            s.nack = delta(key + ".nack", n("nackCount") ?? 0)
            s.pli = delta(key + ".pli", n("pliCount") ?? 0)
            s.kf = delta(key + ".kf", n(out ? "keyFramesEncoded" : "keyFramesDecoded") ?? 0)
            s.rtt = Int((rtt ?? 0).rounded())
            if out {
                let q = (v["qualityLimitationReason"] as? String) ?? ""
                s.qlr = q.isEmpty ? "" : (qlrs.contains(q) ? q : "other")
                s.enc = String(((v["encoderImplementation"] as? String) ?? "").prefix(60))
            } else {
                s.lost = delta(key + ".lost", n("packetsLost") ?? 0)
                s.freeze = delta(key + ".fz", n("freezeCount") ?? 0)
            }
            return s
        }
        mutating func add(_ s: Sample) {
            samples.append(s)
            if samples.count > maxSamples * 2 { samples.removeFirst(samples.count - maxSamples) }
        }
        mutating func event(_ k: String, _ d: String, now: Double) {
            guard kinds.contains(k) else { return }
            events.append(Event(t: ms(now), k: k, d: clean(d)))
            if events.count > maxEvents * 2 { events.removeFirst(events.count - maxEvents) }
        }
        /// regelmaessiger POST faellig? (alle 30 s, ab Anrufbeginn)
        func due(now: Double) -> Bool { now - (lastPost ?? t0) >= every }
        /// Wartezeit bis ein POST erlaubt ist (hoechstens einer je 15 s)
        func wait(now: Double) -> Double { lastPost.map { max(0, minGap - (now - $0)) } ?? 0 }
        var isEmpty: Bool { samples.isEmpty && events.isEmpty }

        /// Inhalt fuer den POST (<= 32 KB; neueste Proben/Ereignisse behalten) und Puffer leeren; nil = nichts zu senden
        mutating func take(now: Double) -> [String: Any]? {
            guard !isEmpty, wait(now: now) == 0 else { return nil }
            var s = Array(samples.suffix(maxSamples)), e = Array(events.suffix(maxEvents))
            func body() -> [String: Any] {
                ["v": 1, "call": call, "plat": plat, "app": app, "path": path, "transport": transport, "node": node,
                 "samples": s.map(\.json), "events": e.map(\.json)]
            }
            func size() -> Int { (try? JSONSerialization.data(withJSONObject: body())).map(\.count) ?? Int.max }
            while size() > maxBytes, !(s.isEmpty && e.isEmpty) {
                if !s.isEmpty { s.removeFirst(max(1, s.count / 10)) } else { e.removeFirst(max(1, e.count / 10)) }
            }
            samples = []; events = []; lastPost = now
            return body()
        }
    }
}
