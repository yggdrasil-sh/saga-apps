import Foundation

// Horn-Fix (Saga 2.5): Wiederaufbau nach Knotenverlust - dieselben Faelle wie HornRecoverTest.kt (Android).
// Immer ueber Knoten, nie direkt (p2p).
func hornRecoverTests() {
    typealias R = HornRecover
    func near(_ a: Double, _ b: Double) -> Bool { abs(a - b) < 1e-9 }
    func secs(_ s: R.Step?) -> Double? { if case .wait(let x)? = s { return x }; return nil }

    // ---- Backoff-Folge 1/2/4/8/15 s (+-20 %)
    check([1, 2, 3, 4, 5, 6, 9].map { R.delay(fails: $0, rnd: 0.5) } == [1, 2, 4, 8, 15, 15, 15], "Backoff 1/2/4/8/15 s, danach 15 s")
    check(near(R.delay(fails: 1, rnd: 0), 0.8) && near(R.delay(fails: 1, rnd: 1), 1.2), "Zufall +-20 % (1 s)")
    check(near(R.delay(fails: 5, rnd: 0), 12) && near(R.delay(fails: 5, rnd: 0.75), 16.5), "Zufall +-20 % (15 s)")
    check(near(R.delay(fails: 0, rnd: 0.5), 1), "fails < 1 wie der erste Fehlversuch")

    var r = R()
    check(!r.active && !r.muted, "Ruhe: nicht aktiv, Beobachtung an")
    check(secs(r.lost(now: 1000, rnd: 0.5)) == 0, "Verlust: sofort neu versuchen")
    check(r.active && r.muted, "Versuch laeuft: Beobachtung stumm")
    check(r.lost(now: 1500, rnd: 0.5) == nil, "Monitor stumm: zweites lost waehrend des Versuchs ignoriert")
    var seq: [Double] = []
    var t = 1000.0
    for _ in 0..<6 { t += 100; seq.append(secs(r.failed(now: t, retryAfter: nil, rnd: 0.5)) ?? -1) }
    check(seq == [1, 2, 4, 8, 15, 15], "Fehlversuche: 1, 2, 4, 8, 15, 15 s")
    check(r.muted, "geplanter Versuch: weiter stumm (kein neues switch lost)")
    check(r.lost(now: t + 50, rnd: 0.5) == nil, "geplanter Retry wird nicht abgebrochen")

    // ---- Adopt, Rueckstellung erst nach Datenempfang, Verlust < 10 s = Fehlversuch
    r = R()
    _ = r.lost(now: 0, rnd: 0.5)
    _ = r.failed(now: 1000, retryAfter: nil, rnd: 0.5)                       // fails 1
    r.adopted(now: 3000)
    check(r.active && !r.muted, "nach Adopt: Beobachtung wieder an, Wiederaufbau noch nicht zurueckgestellt")
    check(!r.tick(now: 4000, rx: true), "rx > 0, aber < 10 s: noch nicht zurueckgestellt")
    check(secs(r.lost(now: 8000, rnd: 0.5)) == 2, "Verlust 5 s nach Adopt = Fehlversuch (Backoff weiter: 2 s)")
    check(r.fails == 2 && r.t0 == 0, "Frist laeuft vom ersten Verlust")
    r.adopted(now: 20_000)
    check(!r.tick(now: 40_000, rx: false), "ohne Daten keine Rueckstellung")
    check(r.tick(now: 40_000, rx: true) && !r.active && r.fails == 0, "Daten >= 10 s nach Adopt: zurueckgestellt")
    check(secs(r.lost(now: 50_000, rnd: 0.5)) == 0 && r.t0 == 50_000, "neuer Verlust danach: frischer Wiederaufbau, sofort")
    var r2 = R()
    _ = r2.lost(now: 0, rnd: 0.5); r2.adopted(now: 1000)
    check(secs(r2.lost(now: 12_000, rnd: 0.5)) == 0 && r2.t0 == 12_000, "Verlust >= 10 s nach Adopt (ohne tick): neu beginnen")

    // ---- 429 mit/ohne retry_after
    r = R()
    _ = r.lost(now: 0, rnd: 0.5)
    check(secs(r.failed(now: 100, retryAfter: 7, rnd: 0.5)) == 7 && r.fails == 0, "429 retry_after 7: 7 s warten, kein Fehlversuch")
    check(secs(r.failed(now: 200, retryAfter: 300, rnd: 0.5)) == 60, "429 retry_after > 60: hoechstens 60 s")
    check(secs(r.failed(now: 300, retryAfter: nil, rnd: 0.5)) == 1, "429 ohne Feld (alter Server): Backoff normal (1 s)")
    check(R.retryAfter(status: 429, body: ["error": "busy", "retry_after": 12]) == 12, "Vertrag: Body retry_after")
    check(R.retryAfter(status: 429, body: ["error": "busy", "retry_after": "5"]) == 5, "retry_after als Text")
    check(R.retryAfter(status: 429, body: ["error": "busy"], header: "9") == 9, "ersatzweise Header Retry-After")
    check(R.retryAfter(status: 429, body: ["error": "busy"]) == nil, "alter Server ohne Feld: nil (normaler Backoff)")
    check(R.retryAfter(status: 429, body: nil) == nil, "429 ohne Body: nil")
    check(R.retryAfter(status: 429, body: ["retry_after": 999]) == 60, "Deckel 60 s")
    check(R.retryAfter(status: 429, body: ["retry_after": 0]) == 1, "0 -> 1 s")
    check(R.retryAfter(status: 503, body: ["retry_after": 5]) == nil, "nur 429")
    check(R.retryAfter(status: 429, body: ["retry_after": true]) == nil, "kein Wahrheitswert")

    // ---- avoid-Regel einziger/mehrere Knoten
    check(R.avoid(["n1"], known: ["n1"]) == [], "einziger Knoten: nicht meiden")
    check(R.avoid(["n1"], known: []) == [], "unbekannte Liste: nicht meiden")
    check(R.avoid(["n1"], known: ["n1", "n2"]) == ["n1"], "mehrere Knoten: verlorenen meiden")
    check(R.avoid(["n1", "n2"], known: ["n1", "n2"]) == [], "alle gemieden waere leer: nichts meiden")
    check(R.avoid(["n2", "n1"], known: ["n1", "n2", "n3"]) == ["n1", "n2"], "mehrere gemieden, einer bleibt (sortiert)")

    // ---- 120-s-Abbruch, kein p2p-Rueckfall
    r = R()
    _ = r.lost(now: 0, rnd: 0.5)
    var steps: [R.Step] = []
    t = 0
    while t < 200_000 {
        let s = r.failed(now: t, retryAfter: nil, rnd: 0.5)
        steps.append(s)
        if s == .giveUp { break }
        if case .wait(let x) = s { t += x * 1000 + 500 }                     // Versuch dauert 0,5 s
    }
    check(steps.last == .giveUp && t >= 120_000 && t < 121_000, "nach 120 s ohne Erfolg aufgeben")
    check(steps.dropLast().allSatisfy { if case .wait = $0 { return true }; return false }, "bis dahin nur Warten + neuer Knoten-Versuch")
    check(!r.muted, "nach Aufgabe nicht mehr stumm")
    r = R()
    _ = r.lost(now: 0, rnd: 0.5)
    check(secs(r.failed(now: 115_000, retryAfter: 30, rnd: 0.5)) == 5, "Wartezeit endet an der Frist")
    check(r.failed(now: 120_000, retryAfter: nil, rnd: 0.5) == .giveUp && r.expired(now: 120_000), "an der Frist: aufgeben")

    // ---- Verdrahtung (Quelltext): kein Rueckfall auf direkt
    guard let dir = ProcessInfo.processInfo.environment["SAGA_APP_SRC"],
          let call = try? String(contentsOfFile: dir + "/HornCall.swift", encoding: .utf8) else { check(false, "SAGA_APP_SRC fehlt"); return }
    check(call.contains("if why == .lost { hornRecover(why, lost: H.cur?.node); return }"), "lost -> Wiederaufbau")
    check(call.contains("!H.switching, !H.rec.muted, let d = H.sw?.conn"), "Monitor stumm waehrend Wiederaufbau")
    check(call.contains("if code == 429 { throw HornBusy("), "429 ist kein noNode")
    check(call.contains("H.rec.adopted(now: now)") && !call.contains("H.retryTask?.cancel(); H.retryTask = nil"), "Adopt bricht den Plan nicht ab")
    if let a = call.range(of: "func hornRecover("), let b = call.range(of: "func hornConnClose(") {
        let body = String(call[a.lowerBound..<b.lowerBound]).lowercased()
        check(!body.contains("mesh") && !body.contains("p2p") && !body.contains("topo"), "Wiederaufbau ohne Rueckfall auf direkt (Mesh/p2p)")
    } else { check(false, "hornRecover fehlt") }
}
