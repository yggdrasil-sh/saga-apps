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
    check(src.contains("HornLogic.cpuHold(cur.cpuAt, now: t)") && src.contains("hold ? [] :"), "2202: Umschalter haelt unter CPU-Grenze")
    check(src.contains("(v[\"qualityLimitationReason\"] as? String) == \"cpu\"") && src.contains("conn.cpuAt = t"), "2202: hornStats setzt cpuAt")
}
