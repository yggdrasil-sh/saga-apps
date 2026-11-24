import Foundation

// Bugs-DB 2202 (Flattern unter Last): der aktuelle Knoten zaehlt mit derselben frischen Probe wie die anderen
func hornFlatterTests() {
    let fresh = HornLogic.Measure(ok: true, rtts: [20, 21, 20, 22, 20], tls: nil, udp: nil)
    let own: [Double] = [20, 90, 25, 140, 30, 95, 20, 130, 22, 100]
    check(HornLogic.curRtts(fresh, own: own) == fresh.rtts, "2202: frische Probe fuer den aktuellen Knoten")
    check(HornLogic.curRtts(nil, own: own) == own, "2202: ohne Probe die eigenen Pings")
    check(HornLogic.curRtts(HornLogic.Measure(ok: false, rtts: [], tls: nil, udp: nil), own: own) == own, "2202: fehlgeschlagene Probe -> eigene Pings")
    let src = (try? String(contentsOfFile: URL(fileURLWithPath: #filePath).deletingLastPathComponent().appendingPathComponent("../../App/HornCall.swift").path, encoding: .utf8)) ?? ""
    check(src.contains("HornLogic.curRtts(m[cur.node], own: cur.rtts)"), "2202: Umschalter nutzt curRtts")
    // cpuHold (wie static/horn.js): 30 s nach der letzten CPU-Grenze kein "besser"-Wechsel
    check(!HornLogic.cpuHold(0, now: 1_000_000), "2202: nie gemeldet -> kein Halt")
    check(HornLogic.cpuHold(1_000_000, now: 1_000_000), "2202: gerade gemeldet -> Halt")
    check(HornLogic.cpuHold(1_000_000, now: 1_029_999), "2202: knapp unter 30 s -> Halt")
    check(!HornLogic.cpuHold(1_000_000, now: 1_030_000), "2202: nach 30 s -> frei")
    check(src.contains("HornLogic.cpuHold(cur.cpuAt, now: t)") && src.contains("!hold || HornLogic.back(curPath: cur.path"), "2202: Umschalter haelt unter CPU-Grenze, Rueckweg bleibt")
    // back (wie static/horn.js): nur vom Knoten ohne UDP zu einem mit UDP-Antwort
    let tun = HornLogic.Candidate(node: "t", udp: false), u1 = HornLogic.Candidate(node: "h", udp: true)
    let okU = HornLogic.Measure(ok: true, rtts: [20], tls: nil, udp: 15), okNoU = HornLogic.Measure(ok: true, rtts: [20], tls: nil, udp: nil)
    check(HornLogic.back(curPath: "ws", curCand: tun, u1, okU), "2202: Tunnel -> UDP-Knoten = Rueckweg")
    check(!HornLogic.back(curPath: "udp", curCand: u1, u1, okU), "2202: schon auf UDP -> kein Rueckweg")
    check(!HornLogic.back(curPath: "tcp", curCand: u1, u1, okU), "2202: aktueller Knoten hat UDP -> kein Rueckweg (UDP-Regel)")
    check(!HornLogic.back(curPath: "ws", curCand: tun, u1, okNoU), "2202: ohne UDP-Antwort kein Rueckweg")
    check(!HornLogic.back(curPath: "ws", curCand: tun, tun, okU), "2202: Ziel ohne UDP kein Rueckweg")
    // Rücksetzung am Swift-Switcher mit leerer Liste (Pruefer-Auflage)
    var sw = HornLogic.Switcher(node: "a", path: "udp", now: 0)
    _ = sw.observe(100_000, cur: 100, others: [("b", 10)])
    check(sw.observe(130_000, cur: 100, others: []) == nil, "2202: leere Liste -> kein Wechsel")
    check(sw.observe(140_000, cur: 100, others: [("b", 10)]) == nil, "2202: Anwaerter neu ab 140 s")
    check(sw.observe(150_000, cur: 100, others: [("b", 10)]) == nil, "2202: 10 s < betterFor")
    check(sw.observe(160_000, cur: 100, others: [("b", 10)]) != nil, "2202: nach betterFor Wechsel")
    check(src.contains("(v[\"qualityLimitationReason\"] as? String) == \"cpu\"") && src.contains("conn.cpuAt = t"), "2202: hornStats setzt cpuAt")
}
