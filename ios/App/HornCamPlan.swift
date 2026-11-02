import Foundation

// Gjallarhorn (2.5): Simulcast-Schichten der Kamera am Knoten - reine Logik (Logic-Runner, HornE2ETests).
// Fehler 26.10. (1:1 ueber Knoten ohne Bild in beide Richtungen): libwebrtc (webrtc-sdk M150, VP8/libvpx) kodiert ein
// 720p-Bild GAR NICHT, sobald die Schichten verschiedene maxFramerate oder numTemporalLayers haben (l 20 fps/T2,
// m 30 fps/T3) - beim Anlegen gesetzt kommt kein Rahmen, spaeter gesetzt bleibt der Kodierer nach wenigen Sekunden
// stehen. Mit 320x240 lief nur noch "l", und die schaltet hornTune zu zweit ab. Daher: alle Schichten mit denselben
// Werten (T3 fuer die Bildrate der Kacheln am Knoten), Bildrate nur gemeinsam begrenzen (Energiesparen 15 fps).
// Geprueft im Simulator gegen einen lokalen Knoten (HornNodeTests.testOneToOneVideo*).
enum HornCamPlan {
    struct Layer: Equatable {
        var rid: String
        var scale: Double
        var kbit: Int
    }
    /// zeitliche Schichten je Kamera-Schicht (gleich fuer alle, sonst kodiert libwebrtc nichts)
    static let temporalLayers = 3
    /// Simulcast (NODE.md): TCP-Wege l = 1/4, m = voll; UDP zusaetzlich h (m dann halbe Groesse).
    /// A1: auf TCP/TLS gleich mit den Deckeln aus HornTx.caps (tcp l 150 / m 600, TLS l 120 / m 450); Hoehe von m: HornTx.plan
    static func layers(path: String) -> [Layer] {
        if path == "udp" { return [Layer(rid: "l", scale: 4, kbit: 150), Layer(rid: "m", scale: 2, kbit: 500), Layer(rid: "h", scale: 1, kbit: 1500)] }
        let c = HornTx.caps[path] ?? HornTx.caps["tcp"]!
        return [Layer(rid: "l", scale: 4, kbit: c.l), Layer(rid: "m", scale: 1, kbit: c.m)]
    }
    /// Bildschirm beim Anlegen (kbit/s, fps): UDP wie 2.4 relay-udp, TCP/TLS nach HornTx.caps (A1; vorher 1200 kbit/s 8 fps)
    static func screenRate(path: String) -> (kbit: Int, fps: Int) { HornTx.caps[path].map { ($0.screen, $0.screenFps) } ?? (1800, 15) }
    /// Gemeinsame Hoechstrate aller Schichten: nil = keine Grenze (Kamera liefert 30 fps), Energiesparen 15
    static func maxFps(_ fps: Int?) -> Int? { fps.map { max(1, min(30, $0)) } }
    /// Gleiche Werte in allen Schichten? (Bildrate und zeitliche Schichten)
    static func uniform(fps: [Int?], temporal: [Int?]) -> Bool { Set(fps.map { $0 ?? 0 }).count <= 1 && Set(temporal.map { $0 ?? 0 }).count <= 1 }
}

// A1 "Video stockt ueber Gjallarhorn" (Labor 2.11.): Senderate auf TCP/TLS - wie static/horn.js K.TX / TxGov / txPlan
// und Android HornLogic (TX, TxGov mit 2-min-Grundwert-Fenster, txPlan). Ueber TCP/TLS sieht WebRTC keinen Verlust:
// Neuuebertragungen kommen als Schuebe, ein zu grosser Sendestrom staut sich in der TCP-Sendewarteschlange (RTT steigt,
// Bild friert bei lost=0). Daher harte Deckel je Weg und eine Stufe 0..3 aus der RTT der Bild-Leitung. UDP bleibt bei
// GCC (keine Deckel, Regeln aus HornLogic.camActive, Bughunt P03).
enum HornTx {
    /// Deckel je Weg (kbit/s); mH/screenH = hoechste Bildhoehe (kurze Seite) von m bzw. Bildschirm
    struct Cap: Equatable { var l: Int; var m: Int; var mH: Int; var screen: Int; var screenFps: Int; var screenH: Int }
    static let caps: [String: Cap] = [
        "tcp": Cap(l: 150, m: 600, mH: 720, screen: 900, screenFps: 8, screenH: 1080),
        "turn": Cap(l: 120, m: 450, mH: 540, screen: 600, screenFps: 5, screenH: 720),
    ]
    /// Faktor je Stufe auf m und Bildschirm
    static let factor: [Double] = [1, 0.6, 0.4, 0.25]
    /// Bildschirm hoechstens so viele Bilder/s je Stufe (0 = Wert des Wegs)
    static let stepFps: [Int] = [0, 5, 3, 2]
    static let rttAbs = 120.0          // Stau: RTT mindestens 120 ms ...
    static let rttRel = 0.5            // ... und 50 % ueber dem Grundwert
    static let rttHuge = 1000.0        // ab 1 s ueber dem Grundwert sofort
    static let rttOk = 60.0            // frei: hoechstens 60 ms ueber dem Grundwert
    static let badN = 2                // Stau in 2 Messungen in Folge (Messung alle ~3 s)
    static let hold = 6000.0           // zwischen zwei Stufen nach unten mindestens 6 s
    static let up = 15_000.0           // frei fuer 15 s -> eine Stufe zurueck ...
    static let upMax = 120_000.0       // ... nach erneutem Stau kurz danach doppelt so lange, hoechstens 2 min
    static let retry = 30_000.0        // Stau innerhalb von 30 s nach einem Schritt nach oben = Fehlversuch
    /// Grundwert = kleinste RTT im gleitenden Fenster (2 min, Eimer zu 10 s) - folgt einem dauerhaften Routenwechsel
    static let baseWin = 120_000.0
    static let baseBucket = 10_000.0
    private static let never = -1e15

    /// Stufenwechsel (Diagnose-Ereignis `tx`: `L0>L1 rtt=… base=… tcp`)
    struct Step: Equatable {
        var from: Int, to: Int, rtt: Int, base: Int
        func line(_ path: String) -> String { "L\(from)>L\(to) rtt=\(rtt) base=\(base) \(path)" }
    }

    /// Sendestufe aus der RTT der Bild-Leitung mit Hysterese (gleiche Schwellen wie Web/Android). Grundwert wie Android
    /// (Pruefung A1): kleinste RTT der letzten 2 min statt seit Beginn - sonst bliebe nach einem dauerhaften Routenwechsel
    /// bzw. im Band 60..120 ms ueber dem alten Grundwert die Stufe fuer immer haengen.
    struct Gov {
        let path: String
        private(set) var level = 0
        private(set) var base = Double.infinity
        private(set) var upWait = HornTx.up
        private var bad = 0
        private var okSince: Double?
        private var downAt = HornTx.never
        private var upAt = HornTx.never
        /// (Eimer-Beginn, kleinste RTT im Eimer), aelteste zuerst
        private var mins: [(t: Double, v: Double)] = []
        init(path: String) { self.path = path }

        private mutating func baseAt(_ now: Double, _ rtt: Double) -> Double {
            let b = (now / HornTx.baseBucket).rounded(.down) * HornTx.baseBucket
            if let last = mins.last, last.t == b { mins[mins.count - 1].v = min(last.v, rtt) } else { mins.append((b, rtt)) }
            while mins.count > 1, mins[0].t + HornTx.baseBucket <= now - HornTx.baseWin { mins.removeFirst() }
            return mins.map(\.v).min() ?? rtt
        }
        /// Eine Messung (rtt in ms, nil = keine) -> Wechsel der Stufe oder nil
        mutating func add(now: Double, rtt: Double?) -> Step? {
            guard path != "udp", let rtt, rtt.isFinite, rtt > 0 else { return nil }
            base = baseAt(now, rtt)
            let q = rtt - base, from = level
            if q >= HornTx.rttHuge || (q >= HornTx.rttAbs && q >= base * HornTx.rttRel) {
                bad += 1
                okSince = nil
                if level < 3, now - downAt >= HornTx.hold, bad >= HornTx.badN || q >= HornTx.rttHuge {
                    if now - upAt < HornTx.retry { upWait = min(HornTx.upMax, upWait * 2) }
                    level += 1; downAt = now; bad = 0
                }
            } else if q <= HornTx.rttOk {
                bad = 0
                let since = okSince ?? now
                okSince = since
                if now - upAt >= 4 * HornTx.upMax, now - downAt >= 4 * HornTx.upMax { upWait = HornTx.up }   // lange ruhig
                if level > 0, now - since >= upWait, now - downAt >= upWait { level -= 1; upAt = now; okSince = now }
            } else {
                bad = 0
                okSince = nil
            }
            return level != from ? Step(from: from, to: level, rtt: Int(rtt.rounded()), base: Int(base.rounded())) : nil
        }
    }

    /// Sollwerte einer Kamera-Schicht (nil = nicht anfassen); Raten in bit/s
    struct Enc: Equatable { var active: Bool; var maxBps: Int? = nil; var scaleDown: Double? = nil }
    struct Screen: Equatable { var maxBps: Int; var maxFps: Int; var scaleDown: Double }
    /// maxFps = gemeinsame Bildrate ALLER Kamera-Schichten (nie je Schicht, Fehler 26.10.), nil = ohne Grenze
    struct Plan: Equatable { var cam: [String: Enc]; var screen: Screen?; var maxFps: Int? }

    private static func r2(_ x: Double) -> Double { (x * 100).rounded() / 100 }

    /// Sende-Parameter der Bild-Leitung eines Knotenbeins. others = andere Geraete am Knoten, share = ich teile den
    /// Bildschirm, h/sh = kurze Seite von Kamera bzw. Bildschirm (0 = unbekannt; Hochkant zaehlt die Breite).
    /// - UDP: wie bisher (HornLogic.camActive, P03): allein nichts, zu zweit ohne l, Energiesparen ohne h; keine Deckel.
    /// - TCP/TLS: l bleibt auch zu zweit an (Ausweichschicht fuer den Knoten), m gedeckelt und ab Stufe 2 bzw. beim
    ///   Teilen aus, Bildschirm gedeckelt.
    static func plan(path: String, level: Int, others: Int, share: Bool = false, h: Int = 0, sh: Int = 0,
                     rids: [String]? = nil, lowPower: Bool = false) -> Plan {
        let rids = rids ?? HornCamPlan.layers(path: path).map(\.rid)
        let lv = max(0, min(3, level))
        guard let c = caps[path] else {
            let (on, fps) = HornLogic.camActive(path: path, others: others, lowPower: lowPower)
            return Plan(cam: Dictionary(uniqueKeysWithValues: rids.map { ($0, Enc(active: on.contains($0))) }), screen: nil, maxFps: fps)
        }
        let f = factor[lv]
        var cam: [String: Enc] = [:]
        for r in rids {
            switch r {
            case "l": cam[r] = Enc(active: others > 0, maxBps: Int((Double(c.l) * (lv >= 3 ? 0.7 : 1)).rounded()) * 1000)
            case "m": cam[r] = Enc(active: others > 0 && lv < 2 && !share, maxBps: Int((Double(c.m) * f).rounded()) * 1000,
                                   scaleDown: h > c.mH ? r2(Double(h) / Double(c.mH)) : 1)
            default: cam[r] = Enc(active: false)                                  // h gibt es auf TCP/TLS nicht
            }
        }
        let fps = stepFps[lv] != 0 ? min(c.screenFps, stepFps[lv]) : c.screenFps
        var sc = sh > c.screenH ? Double(sh) / Double(c.screenH) : 1
        if lv >= 3 { sc *= 1.5 }
        return Plan(cam: cam, screen: Screen(maxBps: Int((Double(c.screen) * f).rounded()) * 1000, maxFps: fps, scaleDown: r2(max(1, sc))),
                    maxFps: lowPower && others > 0 ? 15 : nil)
    }
}
