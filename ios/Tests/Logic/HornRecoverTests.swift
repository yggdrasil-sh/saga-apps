import Foundation

// Horn-Fix (Saga 2.5): Wiederaufbau nach Knotenverlust - dieselben Faelle wie HornRecoverTest.kt (Android).
// 30 s ueber Knoten, danach direkt (wenn moeglich); ohne Direkt-Weg Abbruch nach 120 s.
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
    var r3 = R()
    _ = r3.lost(now: 0, rnd: 0.5); r3.adopted(now: 1000)
    check(secs(r3.lost(now: 15_000, rnd: 0.5, rx: false)) == 1 && r3.t0 == 0 && r3.fails == 1, "> 10 s, aber ohne Daten: Fehlversuch derselben Folge")

    // ---- 429 mit/ohne retry_after
    r = R()
    _ = r.lost(now: 0, rnd: 0.5)
    check(secs(r.failed(now: 100, retryAfter: 7, rnd: 0.5)) == 7, "429 retry_after 7: max(7, Backoff 1) = 7 s")
    check(secs(r.failed(now: 200, retryAfter: 300, rnd: 0.5)) == 60, "429 retry_after > 60: hoechstens 60 s")
    check(secs(r.failed(now: 300, retryAfter: nil, rnd: 0.5)) == 4, "429 ohne Feld (alter Server): Backoff normal weiter (4 s)")
    check(secs(r.failed(now: 400, retryAfter: 2, rnd: 0.5)) == 8, "429 kuerzer als Backoff: Backoff (8 s)")
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
    check(R.avoid(["n1"], known: ["n1", "n2"], lost: "n1") == [], "verlorenen Knoten nie in avoid (letzter Versuch)")
    check(R.avoid(["n1", "n2"], known: ["n1", "n2", "n3"], lost: "n1") == ["n2"], "andere gescheiterte weiter meiden")

    // ---- 30-s-Fenster, dann direkt (Horn faellt aus)
    r = R()
    _ = r.lost(now: 0, rnd: 0.5, canDirect: true)
    var st: [R.Step] = []
    t = 0
    while t < 200_000 {
        let s = r.failed(now: t, retryAfter: nil, rnd: 0.5, canDirect: true)
        st.append(s)
        if case .wait(let x) = s { t += x * 1000 + 500 } else { break }
    }
    check(st.last == .direct && t >= 30_000 && t < 31_000, "nach 30 s ohne Knoten: direkt")
    check(st.dropLast().allSatisfy { if case .wait = $0 { return true }; return false }, "bis 30 s nur Knoten-Versuche")
    check(r.muted, "Direkt-Anfrage laeuft: weiter stumm")
    check(r.direct(.switched(offerTo: ["b"]), now: t, rnd: 0.5) == nil && !r.active && !r.muted, "200: umgeschaltet, Wiederaufbau beendet")
    // node_ready: weiter ueber Knoten, danach erneut fragen; limit/fed: nie mehr fragen, Ende nach 120 s
    r = R()
    _ = r.lost(now: 0, rnd: 0.5, canDirect: true)
    check(r.failed(now: 31_000, retryAfter: nil, rnd: 0.5, canDirect: true) == .direct, "31 s: direkt fragen")
    check(secs(r.direct(.nodeReady, now: 31_500, rnd: 0.5)) == 1, "node_ready: erst wieder ueber den Knoten (Backoff)")
    check(r.failed(now: 34_000, retryAfter: nil, rnd: 0.5, canDirect: true) == .direct, "Knoten wieder nicht: erneut direkt fragen")
    check(secs(r.direct(.busy(20), now: 34_500, rnd: 0.5)) == 20, "429 auf /direct: retry_after abwarten")
    check(r.failed(now: 55_000, retryAfter: nil, rnd: 0.5, canDirect: true) == .direct, "danach wieder fragen")
    check(secs(r.direct(.noPath("limit"), now: 55_500, rnd: 0.5)) != nil && r.noDirect, "limit: kein Direkt-Weg mehr")
    check(secs(r.failed(now: 70_000, retryAfter: nil, rnd: 0.5, canDirect: true)) != nil, "nach limit: weiter ueber Knoten statt direkt")
    check(r.failed(now: 120_000, retryAfter: nil, rnd: 0.5, canDirect: true) == .giveUp, "ohne Direkt-Weg: nach 120 s Ende")
    r = R()
    _ = r.lost(now: 0, rnd: 0.5, canDirect: true)
    _ = r.failed(now: 40_000, retryAfter: nil, rnd: 0.5, canDirect: true)
    check(r.direct(.ended, now: 40_500, rnd: 0.5) == nil && !r.active, "404: Anruf vorbei")
    r = R()
    _ = r.lost(now: 0, rnd: 0.5, canDirect: true)
    _ = r.failed(now: 31_000, retryAfter: nil, rnd: 0.5, canDirect: true)
    _ = r.direct(.noPath("limit"), now: 31_500, rnd: 0.5)
    r.adopted(now: 40_000)
    check(r.noDirect && r.tick(now: 50_000, rx: true) && !r.noDirect, "noDirect bis zum erfolgreichen Wiederaufbau (wie Android)")
    r = R()
    _ = r.lost(now: 0, rnd: 0.5, canDirect: true)
    check(secs(r.failed(now: 10_000, retryAfter: 60, rnd: 0.5, canDirect: true)) == 20, "429 im Fenster: Wartezeit endet am Fensterende")
    check(r.failed(now: 30_000, retryAfter: nil, rnd: 0.5, canDirect: true) == .direct, "am Fensterende: direkt")
    r = R()
    _ = r.lost(now: 0, rnd: 0.5, canDirect: true)
    _ = r.failed(now: 1000, retryAfter: nil, rnd: 0.5, canDirect: true)
    r.adopted(now: 5000)
    check(r.tick(now: 15_000, rx: true), "Knoten wieder da (< 30 s): kein Umschalten")
    r = R()
    _ = r.lost(now: 0, rnd: 0.5)
    check(secs(r.failed(now: 40_000, retryAfter: nil, rnd: 0.5, canDirect: false)) != nil, "ohne Direkt-Weg: nach 30 s weiter ueber Knoten")
    check(r.failed(now: 45_000, retryAfter: nil, rnd: 0.5, canDirect: true) == .direct, "Direkt-Weg wird moeglich: umschalten")

    // ---- 120-s-Abbruch nur ohne Direkt-Weg
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
    check(steps.dropLast().allSatisfy { if case .wait = $0 { return true }; return false }, "ohne Direkt-Weg: bis dahin nur Knoten-Versuche")
    check(!r.muted, "nach Aufgabe nicht mehr stumm")
    r = R()
    _ = r.lost(now: 0, rnd: 0.5)
    check(secs(r.failed(now: 115_000, retryAfter: 30, rnd: 0.5)) == 5, "Wartezeit endet an der Frist")
    check(r.failed(now: 120_000, retryAfter: nil, rnd: 0.5) == .giveUp && r.expired(now: 120_000), "an der Frist: aufgeben")

    // ---- Antwort von /direct (Vertrag horn_direct1)
    check(R.directAnswer(status: 200, body: ["ok": true, "call": ["topo": "mesh"], "offer_to": ["b", "c"]]) == .switched(offerTo: ["b", "c"]), "200 -> umgeschaltet")
    check(R.directAnswer(status: 200, body: ["ok": true, "call": ["topo": "horn"]]) == .error, "200 ohne mesh: Fehler")
    check(R.directAnswer(status: 409, body: ["horn_block": "node_ready", "horn": [:]]) == .nodeReady, "409 node_ready")
    check(R.directAnswer(status: 409, body: ["horn_block": "limit"]) == .noPath("limit"), "409 limit")
    check(R.directAnswer(status: 409, body: ["horn_block": "fed"]) == .noPath("fed"), "409 fed")
    check(R.directAnswer(status: 404, body: ["ended": true]) == .ended, "404 beendet")
    check(R.directAnswer(status: 429, body: ["retry_after": 9]) == .busy(9), "429 retry_after")
    check(R.directAnswer(status: 500, body: nil) == .error && R.directAnswer(status: nil, body: nil) == .error, "500/Netz: Fehler")
    let me = String(repeating: "5", count: 32), lo = String(repeating: "1", count: 32), hi = String(repeating: "a", count: 32)
    check(R.offerTargets([hi, lo, hi], me: me) == [hi], "Mesh: nur an groessere IDs anbieten (kein Glare)")
    check(R.unreached(["n1": 100_000, "n2": 50_000], now: 60_000, badMs: 60_000) == ["n1"], "avoid fuer /direct: in den letzten 30 s nicht erreicht")
    check(R.startNote("node") == .nodeDown && R.startNote("old") == .peerOld && R.startNote("peer") == .peerOld, "Start-Hinweise")
    check(R.startNote("off") == nil && R.startNote("caps") == nil && R.startNote("fed") == nil && R.startNote(nil) == nil, "off/caps/fed: kein Hinweis")

    // ---- Vertrag 5: foederierter Direkt-Chat mit horn_dm_fed
    let caps = CallV3.myCaps(serverFeatures: ["reneg", "multi", "horn"])
    check(CallRoom.dmViaHorn(hornDm: false, hornDmFed: true, fed: true, myCaps: caps), "foederiert + horn_dm_fed: ueber Knoten")
    check(!CallRoom.dmViaHorn(hornDm: false, hornDmFed: true, fed: false, myCaps: caps), "lokal nur horn_dm_fed: direkt")
    check(CallRoom.dmViaHorn(hornDm: true, hornDmFed: false, fed: false, myCaps: caps), "horn_dm wie bisher")
    check(!CallRoom.dmViaHorn(hornDm: false, hornDmFed: true, fed: true, myCaps: ["horn"]), "ohne multi: direkt")

    // ---- Verdrahtung (Quelltext)
    guard let dir = ProcessInfo.processInfo.environment["SAGA_APP_SRC"],
          let call = try? String(contentsOfFile: dir + "/HornCall.swift", encoding: .utf8) else { check(false, "SAGA_APP_SRC fehlt"); return }
    check(call.contains("if why == .lost { hornRecover(why, lost: H.cur?.node, rx: (H.cur?.rx ?? 0) > 0); return }"), "lost -> Wiederaufbau")
    check(call.contains("!H.switching, !H.rec.muted, let d = H.sw?.conn"), "Monitor stumm waehrend Wiederaufbau")
    check(call.contains("if code == 429 { throw HornBusy("), "429 ist kein noNode")
    check(call.contains("H.rec.adopted(now: now)") && !call.contains("H.retryTask?.cancel(); H.retryTask = nil"), "Adopt bricht den Plan nicht ab")
    check(call.contains("var hornCanDirect: Bool { serverFeatures.contains(\"horn_direct1\") }"), "direkt nur mit horn_direct1")
    check(call.contains("let a = await self.hornDirectAsk(H)"), "nach 30 s: Direkt-Anfrage")
    guard let dir2 = try? String(contentsOfFile: dir + "/HornDirect.swift", encoding: .utf8),
          let calls = try? String(contentsOfFile: dir + "/Calls.swift", encoding: .utf8) else { check(false, "HornDirect.swift fehlt"); return }
    check(dir2.contains("api/calls/\\(id)/direct") && dir2.contains("HornRecover.offerTargets(others, me: dev)"), "POST /direct, Angebote an groessere IDs")
    check(calls.contains("case \"call.topo\": hornTopoIn(d)") && calls.contains("hornStartNote(ShareJSON.str(j?[\"horn_block\"]))"), "call.topo und Start-Hinweis verdrahtet")
}
