import Foundation

// 2.5 Anruf-Diagnose (Analyse 1.11.): Empfangsqualitaet ueber 30 s, Anzeige mit Entprellung, Waerme-Hysterese,
// Diagnose-Puffer fuer POST /api/calls/diag (App/CallDiagLogic.swift)
func callDiagTests() {
    // Gauge: ein Einfrieren alle 3 s war frueher 20/min (sofort rot) - ueber 30 s sind es 2/min
    var g = CallDiag.Gauge()
    var r = g.push(t: 0, lost: 0, recv: 0, fz: 0)
    check(r.loss == 0 && r.freezes == 0, "Gauge: Start")
    r = g.push(t: 3000, lost: 0, recv: 300, fz: 1)
    check(r.freezes == 2, "Gauge: ein Freeze in 3 s = 2/min (30-s-Basis) statt 20/min: \(r.freezes)")
    check(HornLogic.quality(rtt: 40, loss: r.loss, freezes: r.freezes) == .mid, "ein Freeze: gelb statt rot")
    for i in 2...10 { r = g.push(t: Double(i) * 3000, lost: 0, recv: 300 * i, fz: 1) }
    check(r.freezes == 2, "Gauge: Freeze bleibt 30 s im Fenster")
    r = g.push(t: 36_000, lost: 0, recv: 3600, fz: 1)
    check(r.freezes == 0, "Gauge: nach 30 s faellt der Freeze heraus: \(r.freezes)")
    // Verlust ueber das Fenster
    var g2 = CallDiag.Gauge()
    _ = g2.push(t: 0, lost: 0, recv: 0, fz: 0)
    r = g2.push(t: 3000, lost: 10, recv: 90, fz: 0)
    check(abs(r.loss - 0.1) < 1e-9, "Gauge: Verlust 10 %")
    r = g2.push(t: 6000, lost: 10, recv: 990, fz: 0)
    check(abs(r.loss - 0.01) < 1e-9, "Gauge: Verlust ueber das Fenster gemittelt: \(r.loss)")
    r = g2.push(t: 9000, lost: 0, recv: 10, fz: 0)                     // Zaehler neu (neues Bein)
    check(r.loss == 0 && r.freezes == 0 && g2.pts.count == 1, "Gauge: Zaehler zurueckgesetzt -> neu anfangen")
    // viele Einfrierer bleiben rot
    var g3 = CallDiag.Gauge()
    _ = g3.push(t: 0, lost: 0, recv: 0, fz: 0)
    r = g3.push(t: 3000, lost: 0, recv: 100, fz: 2)
    check(r.freezes == 4 && HornLogic.quality(rtt: 40, freezes: r.freezes) == .bad, "Gauge: 2 Freezes = 4/min = rot")

    // Debounce: rot erst nach zwei schlechten, gruen erst nach zwei guten
    var d = CallDiag.Debounce(HornLogic.Quality.good)
    check(d.push(.bad) == .good, "eine schlechte Messung: noch gruen")
    check(d.push(.good) == .good, "zurueck gut: gruen")
    check(d.push(.bad) == .good && d.push(.bad) == .bad, "zwei schlechte in Folge: rot")
    check(d.push(.good) == .bad, "eine gute: noch rot")
    check(d.push(.mid) == .bad, "gut, dann mittel: noch rot (keine zwei gleichen)")
    check(d.push(.mid) == .mid, "zwei mittlere: gelb")
    check(d.push(.good) == .mid && d.push(.good) == .good, "zwei gute: gruen")

    // Hold: Waerme-Hysterese 60 s
    var h = CallDiag.Hold()
    check(!h.update(now: 0, raw: false), "kalt")
    check(h.update(now: 1000, raw: true), "heiss")
    check(h.update(now: 6000, raw: false), "kurz kuehler: bleibt heiss")
    check(h.update(now: 11_000, raw: true), "wieder heiss")
    check(h.update(now: 60_000, raw: false) && h.update(now: 115_000, raw: false), "unter 60 s kuehl: heiss")
    check(!h.update(now: 120_000, raw: false), "60 s am Stueck kuehl: zurueck")
    var h2 = CallDiag.Hold()
    _ = h2.update(now: 0, raw: true)
    check(h2.update(now: 1000, raw: false) && h2.update(now: 59_000, raw: false), "mindestens 60 s heiss")
    check(!h2.update(now: 61_000, raw: false), "nach 60 s heiss und 60 s kuehl: aus")

    // Puffer
    var b = CallDiag.Buffer(call: "c1", plat: "ios", app: "2.5.0", t0: 1000)
    b.path = "horn"; b.transport = CallDiag.transport(horn: "turn"); b.node = "horn-1.example.org"
    check(b.alias("devA") == "p1" && b.alias("devB") == "p2" && b.alias("devA") == "p1" && b.alias(nil) == "?", "Kuerzel je Geraet")
    check(!b.due(now: 20_000) && b.due(now: 31_000), "alle 30 s ab Anrufbeginn")
    check(b.take(now: 2000) == nil, "leer: nichts senden")
    let out: [String: Any] = ["kind": "video", "rid": "h", "framesPerSecond": NSNumber(value: 29.6), "frameHeight": NSNumber(value: 720),
                              "bytesSent": NSNumber(value: 0), "nackCount": NSNumber(value: 1), "pliCount": NSNumber(value: 2),
                              "keyFramesEncoded": NSNumber(value: 1), "qualityLimitationReason": "cpu", "encoderImplementation": "VideoToolbox"]
    check(b.sample(type: "outbound-rtp", values: ["kind": "audio"], src: "me", key: "a", now: 2000, rtt: nil) == nil, "Ton: keine Probe")
    let s1 = b.sample(type: "outbound-rtp", values: out, src: "me", key: "o.h", now: 2000, rtt: 41.6)
    check(s1?.dir == "out" && s1?.rid == "h" && s1?.fps == 30 && s1?.h == 720 && s1?.qlr == "cpu" && s1?.enc == "VideoToolbox" && s1?.rtt == 42
          && s1?.kbps == 0 && s1?.pli == 0 && s1?.t == 1000, "Probe aus outbound-rtp (erste: Zuwaechse 0)")
    var out2 = out; out2["bytesSent"] = NSNumber(value: 375_000); out2["pliCount"] = NSNumber(value: 5); out2["qualityLimitationReason"] = "weird"
    let s2 = b.sample(type: "outbound-rtp", values: out2, src: "me", key: "o.h", now: 5000, rtt: nil)
    check(s2?.kbps == 1000 && s2?.pli == 3 && s2?.qlr == "other", "kbit/s und Zuwaechse: \(String(describing: s2))")
    let inb: [String: Any] = ["kind": "video", "framesPerSecond": NSNumber(value: 15), "frameHeight": NSNumber(value: 360),
                              "bytesReceived": NSNumber(value: 10), "packetsLost": NSNumber(value: 4), "freezeCount": NSNumber(value: 1)]
    _ = b.sample(type: "inbound-rtp", values: inb, src: "p1", key: "i1", now: 2000, rtt: nil)
    var inb2 = inb; inb2["packetsLost"] = NSNumber(value: 9); inb2["freezeCount"] = NSNumber(value: 3)
    let s3 = b.sample(type: "inbound-rtp", values: inb2, src: "p1", key: "i1", now: 5000, rtt: nil)
    check(s3?.dir == "in" && s3?.rid == "" && s3?.lost == 5 && s3?.freeze == 2 && s3?.qlr == "" && s3?.enc == "", "Probe aus inbound-rtp")
    check(b.sampleDue("i1", now: 0) && !b.sampleDue("i1", now: 2000) && b.sampleDue("i1", now: 3000), "Probe je Quelle hoechstens alle 3 s")
    b.add(s1!); b.add(s2!); b.add(s3!)
    b.event("layers", "schichten [m] fps 15 waerme 4", now: 3000)
    b.event("bogus", "x", now: 3000)
    b.event("path", "udp -> tcp 10.0.0.1 fe80::1:2:3 " + String(repeating: "x", count: 200), now: 4000)
    check(b.events.count == 2, "unbekannte Ereignisart verworfen")
    check(!b.events[1].d.contains("10.0.0.1") && !b.events[1].d.contains("fe80") && b.events[1].d.count == 120, "keine IP, <= 120 Zeichen: \(b.events[1].d)")
    let body = b.take(now: 6000)
    check(body?["v"] as? Int == 1 && body?["call"] as? String == "c1" && body?["plat"] as? String == "ios" && body?["path"] as? String == "horn"
          && body?["transport"] as? String == "tls" && body?["node"] as? String == "horn-1.example.org", "Kopf")
    check((body?["samples"] as? [Any])?.count == 3 && (body?["events"] as? [Any])?.count == 2 && b.isEmpty, "Inhalt, Puffer leer")
    let keys = Set(((body?["samples"] as? [[String: Any]])?.first ?? [:]).keys)
    check(keys == ["t", "dir", "src", "rid", "fps", "h", "kbps", "lost", "nack", "pli", "freeze", "kf", "rtt", "qlr", "enc"], "Felder je Probe: \(keys.sorted())")
    b.event("quality", "good -> mid", now: 7000)
    check(b.wait(now: 10_000) == 11_000 && b.take(now: 10_000) == nil && b.take(now: 21_000) != nil, "hoechstens ein POST je 15 s")
    // Kuerzen: <= 200 Proben, <= 100 Ereignisse, <= 32 KB
    var big = CallDiag.Buffer(call: "c2", plat: "mac", app: "2.5.0", t0: 0)
    for i in 0..<500 {
        var s = CallDiag.Sample(t: i, dir: "in", src: "p1", rid: "")
        s.enc = String(repeating: "e", count: 60)
        big.add(s)
        big.event("switch", String(repeating: "s", count: 120), now: Double(i))
    }
    let bb = big.take(now: 40_000)!
    let n = (bb["samples"] as! [[String: Any]]).count, ne = (bb["events"] as! [Any]).count
    let sz = (try? JSONSerialization.data(withJSONObject: bb))?.count ?? 0
    check(n <= 200 && ne <= 100 && sz <= 32 * 1024 && sz > 20_000, "gekuerzt: \(n) Proben, \(ne) Ereignisse, \(sz) Bytes")
    check((bb["samples"] as! [[String: Any]]).last?["t"] as? Int == 499, "neueste Proben bleiben")
    check(CallDiag.transport(horn: "udp") == "udp" && CallDiag.transport(horn: "tcp") == "tcp" && CallDiag.transport(horn: "") == "?", "Weg Knoten")
    check(CallDiag.transport(p2p: "direct") == "udp" && CallDiag.transport(p2p: "relay-tcp") == "tcp" && CallDiag.transport(p2p: nil) == "?", "Weg P2P")
}
