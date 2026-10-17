import Foundation

// Anrufe: Ton-Diagnose im Anruffenster (getStats -> "Ton: empfangen / kein Ton", Mikrofon)
func callAudioTests() {
    typealias L = CallLogic
    // getStats-Eintraege lesen (Werte als NSNumber wie RTCStatistics), Video und fremde Typen ignorieren
    let st: [(type: String, values: [String: Any])] = [
        ("inbound-rtp", ["kind": "audio", "bytesReceived": NSNumber(value: 5000), "totalSamplesReceived": NSNumber(value: 96000),
                         "totalAudioEnergy": NSNumber(value: 0.25), "audioLevel": NSNumber(value: 0.12)]),
        ("inbound-rtp", ["kind": "video", "bytesReceived": NSNumber(value: 900_000)]),
        ("outbound-rtp", ["kind": "audio", "bytesSent": NSNumber(value: 4200)]),
        ("media-source", ["kind": "audio", "audioLevel": NSNumber(value: 0.05), "totalAudioEnergy": NSNumber(value: 0.01)]),
        ("media-source", ["kind": "video", "width": NSNumber(value: 640)]),
        ("transport", ["bytesReceived": NSNumber(value: 1)]),
    ]
    let s = L.AudioSample.from(st)
    check(s.bytesIn == 5000 && s.samplesIn == 96000 && s.bytesOut == 4200 && abs(s.levelIn - 0.12) < 1e-9 && s.hasMic && abs(s.levelMic - 0.05) < 1e-9,
          "Ton-Statistik: nur Audio gezaehlt")
    check(L.AudioSample.from([("inbound-rtp", ["kind": "video", "bytesReceived": NSNumber(value: 1)])]) == L.AudioSample(), "nur Video: leer")

    func sample(_ b: Int, _ n: Int, _ e: Double, lvl: Double = 0, mic: Double = 0, me: Double = 0, hasMic: Bool = true) -> L.AudioSample {
        var x = L.AudioSample(); x.bytesIn = b; x.samplesIn = n; x.energyIn = e; x.levelIn = lvl; x.levelMic = mic; x.energyMic = me; x.hasMic = hasMic; return x
    }
    // 1. Ton kommt an (Energie waechst)
    var m = L.AudioMeter()
    m.update(sample(0, 0, 0), micOn: true)
    check(m.input == .unknown, "erste Messung: noch kein Urteil")
    m.update(sample(4000, 96000, 0.2, lvl: 0.1, mic: 0.05, me: 0.01), micOn: true)
    check(m.input == .receiving && m.mic == .ok, "Ton empfangen, Mikro ok")
    // Sprechpause: 3 Takte bleibt "empfangen", danach "still" (Pakete + Wiedergabe laufen)
    var e = 0.2, b = 4000, n = 96000
    for i in 1...4 { b += 4000; n += 96000; m.update(sample(b, n, e), micOn: true); if i <= 3 { check(m.input == .receiving, "Pause \(i): haelt") } }
    check(m.input == .silent, "lange Stille: still")
    e += 0.5; b += 4000; n += 96000; m.update(sample(b, n, e), micOn: true)
    check(m.input == .receiving, "wieder Ton")

    // 2. Pakete kommen, aber keine Wiedergabe (iOS: Audio-Session nicht aktiv -> totalSamplesReceived bleibt 0)
    var p = L.AudioMeter()
    for i in 0..<4 { p.update(sample(i * 4000, 0, 0), micOn: true) }
    check(p.input == .noPlayout, "Pakete ohne Wiedergabe erkannt")
    // 3. Gar nichts kommt an
    var q = L.AudioMeter()
    for _ in 0..<4 { q.update(sample(100, 0, 0), micOn: true) }
    check(q.input == .nothing, "keine Pakete erkannt")
    // 4. Mikrofon: still erst nach 4 Takten melden, stumm geschaltet = kein Urteil, ohne Mikro-Track = none
    var r = L.AudioMeter()
    for i in 0..<3 { r.update(sample(i, i, 0), micOn: true) }
    check(r.mic == .unknown, "Mikro: anfangs kein Urteil")
    for i in 3..<6 { r.update(sample(i, i, 0), micOn: true) }
    check(r.mic == .silent, "Mikro still erkannt")
    r.update(sample(7, 7, 0), micOn: false)
    check(r.mic == .unknown, "stumm geschaltet: kein Urteil")
    var t = L.AudioMeter()
    t.update(sample(0, 0, 0, hasMic: false), micOn: true); t.update(sample(1, 1, 0, hasMic: false), micOn: true)
    check(t.mic == .none, "ohne Mikro-Track")
}
