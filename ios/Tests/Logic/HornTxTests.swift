import Foundation

// A1 "Video stockt ueber Gjallarhorn" (iOS/macOS als Sender): Senderate auf TCP/TLS - dieselben Faelle wie
// tests/horn_logik.js (Server) und HornTxTest.kt (Android), inkl. Grundwert-Fenster (2 min), dazu die Verdrahtung.
func hornTxTests() {
    typealias T = HornTx
    func eq(_ a: Double?, _ b: Double) -> Bool { a.map { abs($0 - b) < 1e-9 } ?? false }

    // ---- plan: TCP/TLS
    var p = T.plan(path: "tcp", level: 0, others: 1, h: 720, sh: 1080)
    check(p.cam["l"]!.active && p.cam["m"]!.active, "TCP zu zweit: l bleibt an (Ausweichschicht)")
    check(p.cam["l"]!.maxBps == 150_000 && p.cam["m"]!.maxBps == 600_000 && eq(p.cam["m"]!.scaleDown, 1), "TCP: l 150, m 600 bis 720p")
    check(p.screen == T.Screen(maxBps: 900_000, maxFps: 8, scaleDown: 1), "TCP: Bildschirm 900 kbit/s 8 fps")
    p = T.plan(path: "turn", level: 0, others: 1, h: 1080, sh: 1440)
    check(p.cam["m"]!.maxBps == 450_000 && eq(p.cam["m"]!.scaleDown, 2), "TLS: m 450 kbit/s bis 540p (1080/540 = 2)")
    check(p.cam["l"]!.maxBps == 120_000, "TLS: l 120")
    check(p.screen == T.Screen(maxBps: 600_000, maxFps: 5, scaleDown: 2), "TLS: Bildschirm 600 kbit/s 5 fps bis 720p")
    p = T.plan(path: "turn", level: 0, others: 1, h: 720)
    check(eq(p.cam["m"]!.scaleDown, 1.33), "TLS, Kamera 720p: m auf 540p (1,33)")
    p = T.plan(path: "tcp", level: 0, others: 0)
    check(!p.cam["l"]!.active && !p.cam["m"]!.active, "allein: keine Kamera-Schicht (auch TCP)")
    p = T.plan(path: "turn", level: 0, others: 1, share: true)
    check(p.cam["l"]!.active && !p.cam["m"]!.active, "Bildschirm teilen auf TLS: Kamera nur l")
    p = T.plan(path: "tcp", level: 2, others: 4)
    check(!p.cam["m"]!.active && p.cam["l"]!.active, "Stufe 2: m aus (nur l)")
    check(p.screen == T.Screen(maxBps: 360_000, maxFps: 3, scaleDown: 1), "Bildschirm 0,4 x 900 = 360 kbit/s bei 3 fps")
    p = T.plan(path: "turn", level: 3, others: 1, sh: 720)
    check(p.cam["l"]!.maxBps == 84_000, "Stufe 3 (TLS): l 84 kbit/s")
    check(p.screen == T.Screen(maxBps: 150_000, maxFps: 2, scaleDown: 1.5), "Stufe 3: Bildschirm 150 kbit/s, 2 fps, 1,5-fach kleiner")
    p = T.plan(path: "tcp", level: 1, others: 1)
    check(p.cam["m"]!.active && p.cam["m"]!.maxBps == 360_000 && p.screen?.maxFps == 5, "Stufe 1: m 360 kbit/s, Bildschirm 5 fps")
    p = T.plan(path: "turn", level: 3, others: 1)
    check(p.cam["m"]!.maxBps == 113_000, "450 x 0,25 = 112,5 -> 113 (wie Math.round im Web)")
    check(T.plan(path: "tcp", level: 9, others: 1) == T.plan(path: "tcp", level: 3, others: 1), "Stufe ueber 3 wird begrenzt")
    check(T.plan(path: "tcp", level: -1, others: 1) == T.plan(path: "tcp", level: 0, others: 1), "Stufe unter 0 wird begrenzt")
    check(T.plan(path: "tcp", level: 0, others: 3, rids: ["l", "m", "h"]).cam["h"]?.active == false, "h gibt es auf TCP nicht")
    // Hochkant (iPhone): die kurze Seite zaehlt - 720x1280 ist "720p", nicht 1280
    check(eq(T.plan(path: "tcp", level: 0, others: 1, h: min(720, 1280)).cam["m"]!.scaleDown, 1), "Hochkant 720x1280: m nicht verkleinert")

    // ---- plan: UDP unveraendert (P03)
    p = T.plan(path: "udp", level: 0, others: 1)
    check(!p.cam["l"]!.active && p.cam["m"]!.active && p.cam["h"]!.active, "UDP zu zweit (P03): ohne l, m und h an")
    check(p.cam["m"]!.maxBps == nil && p.cam["m"]!.scaleDown == nil && p.screen == nil, "UDP: keine Deckel (GCC), kein Bildschirm-Plan")
    check(T.plan(path: "udp", level: 0, others: 3).cam.values.allSatisfy(\.active), "UDP in der Gruppe: alle Schichten an")
    p = T.plan(path: "udp", level: 0, others: 3, lowPower: true)
    check(!p.cam["h"]!.active && p.maxFps == 15, "UDP Energiesparen: ohne h, 15 fps")
    check(!T.plan(path: "udp", level: 0, others: 0).cam.values.contains(where: \.active), "UDP allein: nichts")
    check(T.plan(path: "udp", level: 3, others: 1) == T.plan(path: "udp", level: 0, others: 1), "UDP: Stufe ohne Wirkung")

    // ---- gemeinsame Bildrate, Energiesparen
    check(T.plan(path: "tcp", level: 0, others: 1, lowPower: true).maxFps == 15, "TCP Energiesparen: 15 fps gemeinsam")
    check(T.plan(path: "tcp", level: 0, others: 1).maxFps == nil, "TCP ohne Energiesparen: keine Grenze")
    check(T.plan(path: "tcp", level: 0, others: 0, lowPower: true).maxFps == nil, "allein: keine Grenze noetig")
    p = T.plan(path: "tcp", level: 0, others: 1, lowPower: true)
    check(p.cam["l"]!.active && p.cam["m"]!.active, "Energiesparen auf TCP: l bleibt (es gibt kein h)")

    // ---- beim Anlegen
    check(HornCamPlan.layers(path: "tcp").map(\.kbit) == [150, 600], "Anlegen TCP l 150 / m 600")
    check(HornCamPlan.layers(path: "turn").map(\.kbit) == [120, 450], "Anlegen TLS l 120 / m 450")
    check(HornCamPlan.layers(path: "udp").map(\.kbit) == [150, 500, 1500], "Anlegen UDP unveraendert")
    check(HornCamPlan.screenRate(path: "tcp") == (900, 8) && HornCamPlan.screenRate(path: "turn") == (600, 5)
          && HornCamPlan.screenRate(path: "udp") == (1800, 15), "Bildschirm beim Anlegen je Weg")

    // ---- Gov
    struct Feed {
        var g: T.Gov, t = 0.0
        init(_ path: String) { g = T.Gov(path: path) }
        @discardableResult mutating func feed(_ rtt: Double?, _ n: Int, step: Double = 3000) -> T.Step? {
            var last: T.Step?
            for _ in 0..<n { t += step; if let s = g.add(now: t, rtt: rtt) { last = s } }
            return last
        }
    }
    var f = Feed("turn")
    f.feed(160, 5)
    check(f.g.level == 0 && f.g.base == 160, "ruhig bei 160 ms: Stufe 0, Grundwert 160")
    check(f.feed(330, 1) == nil, "eine Messung 330 ms reicht nicht")
    let ch = f.feed(330, 1)
    check(ch == T.Step(from: 0, to: 1, rtt: 330, base: 160), "zweite Messung in Folge: Stufe 1")
    check(ch?.line("turn") == "L0>L1 rtt=330 base=160 turn", "Diagnose-Zeile")
    f.feed(330, 1); check(f.g.level == 1, "< 6 s seit dem Schritt: bleibt 1")
    f.feed(330, 1); check(f.g.level == 2, "nach 6 s weiter Stau: Stufe 2")
    f.feed(330, 10); check(f.g.level == 3, "hoechstens Stufe 3")

    f = Feed("tcp"); f.feed(160, 2); f.feed(260, 6)
    check(f.g.level == 0, "100 ms ueber dem Grundwert (< 120): kein Stau")
    f = Feed("tcp"); f.feed(20, 2); f.feed(150, 2)
    check(f.g.level == 1, "Grundwert 20, RTT 150: Stau (130 ms und > 50 %)")
    f = Feed("turn"); f.feed(200, 2)
    check(f.feed(5400, 1)?.to == 1, "RTT 5,4 s: sofort")

    f = Feed("tcp"); f.feed(100, 2); f.feed(400, 2)
    check(f.g.level == 1, "Erholung: Start Stufe 1")
    f.feed(100, 4); check(f.g.level == 1, "12 s frei: noch 1")
    f.feed(100, 2); check(f.g.level == 0, "15 s frei: zurueck auf 0")
    f.feed(400, 2); check(f.g.level == 1, "Stau kurz nach dem Schritt nach oben: wieder 1")
    check(f.g.upWait == 30_000, "Wartezeit verdoppelt")
    f.feed(100, 6); check(f.g.level == 1, "18 s frei: noch 1 (Wartezeit 30 s)")
    f.feed(100, 5); check(f.g.level == 0, "nach 30 s frei: 0")
    var g = Feed("tcp"); g.feed(100, 2); g.feed(400, 2); g.feed(100, 4); g.feed(190, 1); g.feed(100, 4)
    check(g.g.level == 1, "Zwischenwert setzt die Erholungszeit zurueck")

    // Grundwert-Fenster (2 min, Eimer 10 s): Routenwechsel und Zwischenband haengen nicht fest
    f = Feed("tcp"); f.feed(20, 4); f.feed(180, 3)
    check(f.g.level >= 1, "Routenwechsel 20 -> 180 ms sieht zuerst wie Stau aus")
    f.feed(180, 100)
    check(f.g.base == 180 && f.g.level == 0, "Grundwert folgt der neuen Route, Stufe zurueck auf 0")
    g = Feed("tcp"); g.feed(100, 2); g.feed(400, 2)
    check(g.g.level == 1, "Zwischenband: Start Stufe 1")
    g.feed(190, 30); check(g.g.level == 1, "innerhalb des Fensters: Stufe bleibt")
    g.feed(190, 30); check(g.g.level == 0 && g.g.base == 190, "Zwischenband haengt nicht fuer immer")
    var h = Feed("turn"); h.feed(150, 2); h.feed(200, 1); h.feed(150, 1); h.feed(170, 20)
    check(h.g.base == 150, "kleinster Wert im Fenster zaehlt")

    f = Feed("udp"); f.feed(100, 2); f.feed(5000, 4)
    check(f.g.level == 0, "UDP: Regler aus (GCC regelt)")
    var z = T.Gov(path: "tcp")
    check(z.add(now: 1000, rtt: nil) == nil && z.add(now: 2000, rtt: 0) == nil && z.add(now: 3000, rtt: .nan) == nil
          && z.add(now: 4000, rtt: .infinity) == nil && z.level == 0, "fehlende/ungueltige RTT: nichts")

    // ---- Verdrahtung (Quelltext)
    guard let dir = ProcessInfo.processInfo.environment["SAGA_APP_SRC"],
          let call = try? String(contentsOfFile: dir + "/HornCall.swift", encoding: .utf8),
          let media = try? String(contentsOfFile: dir + "/HornMedia.swift", encoding: .utf8),
          let calls = try? String(contentsOfFile: dir + "/Calls.swift", encoding: .utf8) else { check(false, "SAGA_APP_SRC fehlt"); return }
    check(call.contains("HornTx.plan(path: conn.path, level: lv, others: others, share: sharing"), "hornTune nutzt HornTx.plan")
    check(call.contains("HornTx.Gov(path: path)"), "Regler je Verbindung (TCP/TLS)")
    check(call.contains("diagEvent(\"tx\", d)"), "Diagnose-Ereignis tx")
    check(call.contains("if L.role != \"audio\" { vrtt"), "RTT der Bild-Leitung")
    check(!call.contains("HornLogic.camActive("), "hornTune nutzt camActive nicht mehr direkt")
    check(media.contains("RTCDegradationPreference.balanced") && media.contains("RTCDegradationPreference.maintainResolution"),
          "Kamera BALANCED, Bildschirm MAINTAIN_RESOLUTION")
    check(media.contains("HornCamPlan.screenRate(path: path)"), "Bildschirm beim Anlegen nach Weg")
    check(calls.contains("hornRetune(); hornDraw()"), "Kamerawechsel: neu abstimmen")
}
