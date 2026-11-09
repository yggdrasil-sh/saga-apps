import Foundation

// 2.5 (9.11.) Geraete im Anruf: Audio-Weg (Anzeige/Auswahl) und Raum-Signal-Buendel nach einer Uebergabe
// (App/CallLogic.swift CallRoute, App/CallRoomLogic.swift signalReply/resendable/resendTargets/Pull)
func geraeteTests() {
    // Audio-Weg aus den Port-Typen der Route
    check(CallRoute.of(outputs: ["Receiver"]) == .receiver && CallRoute.of(outputs: ["Speaker"]) == .speaker, "Route: Hoermuschel/Lautsprecher")
    check(CallRoute.of(outputs: ["BluetoothHFP"]) == .bluetooth && CallRoute.of(outputs: ["BluetoothLE"]) == .bluetooth, "Route: Bluetooth (HFP/LE)")
    check(CallRoute.of(outputs: ["Headphones"]) == .wired && CallRoute.of(outputs: ["CarAudio"]) == .wired, "Route: Kabel/Auto")
    check(CallRoute.of(outputs: []) == .other && CallRoute.of(outputs: ["AirPlay"]) == .other, "Route: leer/unbekannt")
    check(!CallRoute.hasExternal(inputs: ["MicrophoneBuiltIn"], outputs: ["Receiver"]), "nur eingebaut: Umschalter")
    check(CallRoute.hasExternal(inputs: ["MicrophoneBuiltIn", "BluetoothHFP"], outputs: ["Speaker"]), "AirPods verbunden (nicht aktiv): Auswahl")
    check(CallRoute.hasExternal(inputs: ["MicrophoneWired"], outputs: ["Headphones"]), "Kabel-Headset: Auswahl")

    // Antwort auf ein Buendel
    check(CallRoom.signalReply(code: 200, body: ["ok": true, "n": 2]) == .ok(dropped: []), "200 ohne dropped")
    check(CallRoom.signalReply(code: 200, body: ["ok": true, "n": 1, "dropped": ["aa", 5, ""]]) == .ok(dropped: ["aa"]), "200 mit dropped (nur Texte)")
    check(CallRoom.signalReply(code: 400, body: ["error": "ungueltige Angabe"]) == .rejected, "400 = abgelehnt (aelterer Server)")
    check(CallRoom.signalReply(code: 409, body: nil) == .gone && CallRoom.signalReply(code: nil, body: nil) == .other && CallRoom.signalReply(code: 500, body: nil) == .other, "409/Netz/500")

    // erneut senden: nur fkey/state, nur an Geraete der aktuellen Liste, nie an sich selbst
    let A = String(repeating: "a", count: 32), B = String(repeating: "b", count: 32), C = String(repeating: "c", count: 32)
    let D = String(repeating: "d", count: 32), M = String(repeating: "e", count: 32)
    let b = [A: #"{"t":"fkey","n":1}"#, B: #"{"t":"state","mic":true}"#, C: #"{"t":"offer"}"#, D: "kein json"]
    let r = CallRoom.resendable(b)
    check(Set(r.keys) == [A, B], "nur fkey/state erneut")
    func leg(_ d: String) -> CallRoom.Leg { CallRoom.legs([["device": d, "who": "x", "role": "member", "state": "active"]])[0] }
    let t = CallRoom.resendTargets(r.merging([M: #"{"t":"state"}"#]) { a, _ in a }, legs: [leg(B), leg(M)], here: M)
    check(Array(t.keys) == [B], "gegangenes Geraet faellt weg, nie an sich selbst")
    var p = CallRoom.Pull()
    check(p.due(now: 100) && !p.due(now: 101.5) && p.due(now: 102), "Liste hoechstens alle 2 s")

    // 2.5 (9.11.) Kamera frueher drosseln (wie Android CallThrottleTest)
    var w = CallDiag.Throttle()
    check(!w.update(now: 0, thermal: 0, cpu: nil) && w.update(now: 3000, thermal: 1, cpu: nil) && w.why.hasPrefix("waerme"), "Drossel: .fair schaltet sofort")
    check(w.update(now: 30_000, thermal: 0, cpu: false) && w.update(now: 89_000, thermal: 0, cpu: false) && !w.update(now: 90_000, thermal: 0, cpu: false), "Drossel: 60 s ruhig -> hoch")
    check(w.update(now: 99_000, thermal: 1, cpu: nil) && w.calmNeed == 120_000, "Drossel: erneut kurz danach -> doppelte Wartezeit")
    var c = CallDiag.Throttle()
    var early = false
    for s in stride(from: 0, through: 18, by: 3) { early = early || c.update(now: Double(s) * 1000, thermal: 0, cpu: true) }
    _ = c.update(now: 19_000, thermal: 0, cpu: false)
    for s in stride(from: 20, through: 38, by: 3) { early = early || c.update(now: Double(s) * 1000, thermal: 0, cpu: true) }
    check(!early && c.update(now: 41_000, thermal: 0, cpu: true) && c.why.hasPrefix("cpu"), "Drossel: CPU erst nach 20 s am Stueck")
    check(CallDiag.cpuLimited(prevS: nil, prevT: nil, cpuS: 1, now: 1000) == nil && CallDiag.cpuLimited(prevS: 5, prevT: 0, cpuS: 1, now: 3000) == nil, "CPU-Anteil: erste Messung/Zaehler neu")
    check(CallDiag.cpuLimited(prevS: 1, prevT: 0, cpuS: 3, now: 3000) == true && CallDiag.cpuLimited(prevS: 1, prevT: 0, cpuS: 1.5, now: 3000) == false, "CPU-Anteil: 2 von 3 s bzw. 0,5 von 3 s")
}
