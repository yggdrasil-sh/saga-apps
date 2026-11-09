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
    /// 2.5 (9.11.): Waerme allein drosselt die Kamera erst ab thermalState .serious - .fair meldet iOS schon beim Laden bzw.
    /// nach wenigen Minuten Videoanruf (das iPhone waere sonst fast immer gedrosselt); .fair mit anhaltender CPU-Grenze greift
    /// ueber die CPU-Bedingung
    static let warmFrom = 2
    /// 2.5 (9.11., echter Anruf: Sender nach 12-25 min bei ~30 kbit/s und 1-4 fps): Kamera ohne h und mit hoechstens 15 fps,
    /// wenn warm (>= .serious) oder der Kodierer anhaltend (> cpuMs) durch die CPU begrenzt ist; zurueck erst nach calmMs am
    /// Stueck ohne beides, bei erneutem Anspringen kurz danach doppelt so lange (hoechstens maxCalmMs). cpu nil = keine Messung.
    /// Gleiche Regeln wie Android CallDiag.Throttle.
    struct Throttle {
        let cpuMs: Double, calmMs: Double, maxCalmMs: Double
        private(set) var on = false
        private(set) var why = ""
        private var cpuSince: Double?
        private var calmSince: Double?
        private var offAt = 0.0
        private(set) var calmNeed: Double
        init(cpuMs: Double = 20_000, calmMs: Double = 60_000, maxCalmMs: Double = 300_000) {
            self.cpuMs = cpuMs; self.calmMs = calmMs; self.maxCalmMs = maxCalmMs; calmNeed = calmMs
        }
        mutating func update(now: Double, thermal: Int, cpu: Bool?) -> Bool {
            if cpu == true { if cpuSince == nil { cpuSince = now } } else if cpu == false { cpuSince = nil }
            let warm = thermal >= CallDiag.warmFrom
            let cpuLong = cpuSince.map { now - $0 >= cpuMs } ?? false
            if !on {
                if warm || cpuLong {
                    on = true
                    why = warm ? "waerme \(thermal)" : "cpu \(Int((now - (cpuSince ?? now)) / 1000)) s"
                    calmNeed = offAt > 0 && now - offAt < 2 * maxCalmMs ? min(maxCalmMs, calmNeed * 2) : calmMs
                    calmSince = nil
                }
                return on
            }
            if warm || cpu == true { calmSince = nil; return true }
            let c = calmSince ?? now
            calmSince = c
            if now - c >= calmNeed { on = false; why = ""; offAt = now; calmSince = nil; cpuSince = nil }
            return on
        }
    }
    /// 2.5 (9.11.): ueberwiegend CPU-begrenzt seit der letzten Messung? cpuS = qualityLimitationDurations.cpu (s, kumulativ,
    /// hoechster Wert der Kamera-Schichten); nil = keine Aussage (erste Messung, Zaehler neu)
    static func cpuLimited(prevS: Double?, prevT: Double?, cpuS: Double?, now: Double, share: Double = 0.5) -> Bool? {
        guard let cpuS, let prevS, let prevT, now > prevT, cpuS >= prevS else { return nil }
        return (cpuS - prevS) * 1000 / (now - prevT) >= share
    }

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
        /// 2.5 Geraete-CPU (Labor 2.11.): out qcpu/qbw = ms durch CPU bzw. Bandbreite begrenzt, encms = mittlere Kodierzeit je
        /// Rahmen, fdrop = Kamera-Rahmen ohne Kodierung; in fdrop = framesDropped, decms = mittlere Dekodierzeit, fzms =
        /// Standbild-Dauer. Zuwaechse seit der letzten Probe; 0 wird weggelassen
        var qcpu = 0, qbw = 0, fdrop = 0, fzms = 0
        var encms = 0.0, decms = 0.0
        var json: [String: Any] {
            var j: [String: Any] = ["t": t, "dir": dir, "src": src, "rid": rid, "fps": fps, "h": h, "kbps": kbps, "lost": lost, "nack": nack, "pli": pli,
                                    "freeze": freeze, "kf": kf, "rtt": rtt, "qlr": qlr, "enc": enc]
            for (k, v) in [("qcpu", qcpu), ("qbw", qbw), ("fdrop", fdrop), ("fzms", fzms)] where v > 0 { j[k] = v }
            for (k, v) in [("encms", encms), ("decms", decms)] where v > 0 && v.isFinite { j[k] = v }
            return j
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
        return String(String.UnicodeScalarView(x.unicodeScalars.prefix(maxText)))   // Server zaehlt Codepunkte, nicht Zeichen
    }
    /// Zeichensatz des Servers (saga/calldiag.py _SRC/_ENC): andere Zeichen weg, Laenge begrenzt
    static func keep(_ s: String, _ extra: String, _ n: Int) -> String {
        String(String(s.unicodeScalars.filter { ($0.isASCII && (CharacterSet.alphanumerics.contains($0))) || extra.unicodeScalars.contains($0) }).prefix(n))
    }
    /// Zahl fuer den Server: endlich, >= 0, ganzzahlig (Int(NaN/inf) wuerde abstuerzen)
    static func int(_ v: Double?) -> Int { guard let v, v.isFinite, v > 0 else { return 0 }; return v >= 1e9 ? 1_000_000_000 : Int(v.rounded()) }
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
        /// Geraete-ID -> p1, p2 ... (je Anruf fest; nie Name oder ID im Inhalt); unbekannt -> "" (der Server nimmt fuer src nur
        /// [A-Za-z0-9_-:.] an - "?" verwarf die ganze Meldung)
        mutating func alias(_ dev: String?) -> String {
            guard let dev, !dev.isEmpty else { return "" }
            if let a = aliases[dev] { return a }
            let a = "p\(aliases.count + 1)"; aliases[dev] = a; return a
        }
        /// Zuwachs eines Zaehlers seit der letzten Probe (erste Probe: 0; Zaehler neu: 0)
        mutating func delta(_ key: String, _ v: Double) -> Int { CallDiag.int(rawDelta(key, v)) }
        mutating func rawDelta(_ key: String, _ v: Double) -> Double {
            defer { prev[key] = v }
            guard v.isFinite, let p = prev[key], v >= p else { return 0 }
            return v - p
        }
        mutating func kbps(_ key: String, bytes: Double, t: Double) -> Int {
            defer { rate[key] = (bytes, t) }
            guard let p = rate[key], t > p.t, bytes >= p.b else { return 0 }
            return CallDiag.int((bytes - p.b) * 8 / (t - p.t))              // Bytes je ms * 8 = kbit/s
        }
        /// hoechstens eine Probe je Quelle und `every` ms (P2P-Statistik laeuft im 1-2-s-Takt)
        mutating func sampleDue(_ key: String, now: Double, every: Double = 3000) -> Bool {
            if let s = sampled[key], now - s < every { return false }
            sampled[key] = now; return true
        }
        /// Probe aus einem getStats-Eintrag (inbound-rtp/outbound-rtp, nur Video); src = Kuerzel, key = eindeutig je Spur;
        /// srcFrames = media-source.frames der Kamera dieses Senders (fdrop beim Senden)
        mutating func sample(type: String, values v: [String: Any], src: String, key: String, now: Double, rtt: Double?, srcFrames: Double? = nil) -> Sample? {
            func n(_ k: String) -> Double? {
                if let x = v[k] as? NSNumber { return x.doubleValue }
                if let x = v[k] as? String { return Double(x) }
                return nil
            }
            guard type == "inbound-rtp" || type == "outbound-rtp" else { return nil }
            let kind = (v["kind"] as? String) ?? (v["mediaType"] as? String) ?? ""
            guard kind == "video" else { return nil }
            let out = type == "outbound-rtp"
            let rid = (v["rid"] as? String) ?? ""
            var s = Sample(t: ms(now), dir: out ? "out" : "in", src: CallDiag.keep(src, "_-:.", 16), rid: out && ["l", "m", "h"].contains(rid) ? rid : "")
            s.fps = CallDiag.int(n("framesPerSecond"))
            s.h = CallDiag.int(n("frameHeight"))
            s.kbps = kbps(key, bytes: n(out ? "bytesSent" : "bytesReceived") ?? 0, t: now)
            s.nack = delta(key + ".nack", n("nackCount") ?? 0)
            s.pli = delta(key + ".pli", n("pliCount") ?? 0)
            s.kf = delta(key + ".kf", n(out ? "keyFramesEncoded" : "keyFramesDecoded") ?? 0)
            s.rtt = CallDiag.int(rtt)
            if out {
                let q = (v["qualityLimitationReason"] as? String) ?? ""
                s.qlr = q.isEmpty ? "" : (qlrs.contains(q) ? q : "other")
                s.enc = CallDiag.keep((v["encoderImplementation"] as? String) ?? "", " _()[].,:+/-", 60)
            } else {
                s.lost = delta(key + ".lost", n("packetsLost") ?? 0)
                s.freeze = delta(key + ".fz", n("freezeCount") ?? 0)
            }
            // 2.5 Geraete-CPU (Vertrag "Anruf-Diagnose", Web diagRep)
            let nfr = rawDelta(key + ".fr", n(out ? "framesEncoded" : "framesDecoded") ?? 0)
            let tt = rawDelta(key + ".tt", (n(out ? "totalEncodeTime" : "totalDecodeTime") ?? 0) * 1000)
            if nfr > 0 { let m = (tt / nfr * 10).rounded() / 10; if m.isFinite && m > 0 { if out { s.encms = m } else { s.decms = m } } }
            if out {
                let qd = Self.durations(v["qualityLimitationDurations"])
                s.qcpu = delta(key + ".qcpu", (qd["cpu"] ?? 0) * 1000)
                s.qbw = delta(key + ".qbw", (qd["bandwidth"] ?? 0) * 1000)
                // Kamera-Rahmen, die nicht kodiert wurden - nur wenn die Schicht gerade kodiert (abgeschaltete Schicht: nichts)
                let dsrc = rawDelta(key + ".src", srcFrames ?? 0)
                if srcFrames != nil, nfr > 0 { s.fdrop = CallDiag.int(dsrc - nfr) }
            } else {
                s.fdrop = delta(key + ".drop", n("framesDropped") ?? 0)
                s.fzms = delta(key + ".fzd", (n("totalFreezesDuration") ?? 0) * 1000)
            }
            return s
        }
        /// qualityLimitationDurations (Sekunden je Grund) aus getStats: Dictionary mit NSNumber bzw. Text
        static func durations(_ any: Any?) -> [String: Double] {
            guard let d = any as? [String: Any] else { return [:] }
            var o: [String: Double] = [:]
            for (k, x) in d {
                if let n = x as? NSNumber { o[k] = n.doubleValue } else if let t = x as? String, let v = Double(t) { o[k] = v }
            }
            return o
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
