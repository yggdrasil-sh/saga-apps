import Foundation

// 2.5 Anruf-Uebergabe hin und her (handover2, Vertrag APP-API "Geraete-Uebergabe hin und her"): gen, bedingtes /leave
// (by/gen, stale), handover_busy, Wiedereintritt, takeover bei vollem Anruf, promote mit Ruecknahme, call.mod why handover;
// dazu Diagnose-Felder Geraete-CPU und Wetter-Ort beim Anlegen aus der Galerie (App/CallRoomLogic.swift, CallDiagLogic.swift,
// GalleryLogic.swift)
func handoverTests() {
    let me = "anna", a = String(repeating: "a", count: 32), b = String(repeating: "b", count: 32), p = String(repeating: "c", count: 32)
    func leg(_ dev: String, who: String = "anna", role: String = "member", gen: Any? = nil) -> [String: Any] {
        var d: [String: Any] = ["device": dev, "who": who, "role": role, "state": "active", "dev_name": dev.prefix(1) == "a" ? "iPhone" : "Mac"]
        if let gen { d["gen"] = gen }
        return d
    }

    // gen aus der Liste: nur positive ganze Zahlen
    let L = CallRoom.legs([leg(a, gen: NSNumber(value: 1793634001234)), leg(b, role: "handover", gen: true), leg(p, who: "bert", gen: "5")])
    check(L[0].gen == 1793634001234 && L[1].gen == nil && L[2].gen == nil, "gen: Zahl uebernehmen, Bool/Text verwerfen")
    check(CallRoom.gen(NSNumber(value: -1)) == nil && CallRoom.gen(NSNumber(value: 1.5)) == nil && CallRoom.gen(NSNumber(value: 1e16)) == nil, "gen: negativ/Bruch/zu gross verworfen")

    // bedingtes /leave
    let fb = CallRoom.leaveBody(device: a, reason: "handover", by: b, legs: L, v2: true)
    check(fb["device"] as? String == a && fb["reason"] as? String == "handover" && fb["by"] as? String == b && fb["gen"] as? Int == 1793634001234,
          "Rueckfall des neuen Geraets: by = eigenes, gen = die des alten")
    let own = CallRoom.leaveBody(device: a, reason: "handover", by: nil, legs: L, v2: true)
    check(own["by"] == nil && own["gen"] as? Int == 1793634001234, "altes Geraet: eigene gen, kein by")
    let self_ = CallRoom.leaveBody(device: a, reason: nil, by: a, legs: L, v2: true)
    check(self_["by"] == nil && self_["reason"] == nil, "by = eigenes Geraet wird nicht gesendet")
    let old = CallRoom.leaveBody(device: a, reason: "handover", by: b, legs: L, v2: false)
    check(old["by"] == nil && old["gen"] == nil && old.count == 2, "ohne handover2: wie bisher unbedingt")
    let nogen = CallRoom.leaveBody(device: b, reason: "handover", by: a, legs: L, v2: true)
    check(nogen["gen"] == nil && nogen["by"] as? String == a, "ohne gen in der Liste: nur by")
    check(CallRoom.leaveResult(code: 200, body: ["ok": true, "stale": true]) == .stale, "stale: nichts passiert")
    check(CallRoom.leaveResult(code: 200, body: ["ok": true, "already": true]) == .already && CallRoom.leaveResult(code: 200, body: ["ok": true]) == .left, "already/left")
    check(CallRoom.leaveResult(code: nil, body: nil) == .failed && CallRoom.leaveResult(code: 400, body: nil) == .failed, "Fehler")
    check(CallRoom.fallbackDelay(horn: true, handoverS: 15) == 3 && CallRoom.fallbackDelay(horn: false, handoverS: 15) == 7.5, "Rueckfall: Knoten 3 s, Mesh handover_s/2")

    // join: handover_busy nur Hinweis, Wiedereintritt = 200 mit neuer gen
    check(CallRoom.joinOutcome(code: 200, body: ["ok": true, "gen": NSNumber(value: 1793634016726)]) == .ok(gen: 1793634016726), "Wiedereintritt: neue gen")
    check(CallRoom.joinOutcome(code: 409, body: ["join_block": "handover_busy", "error": "warten", "legs": []]) == .busy("warten"), "handover_busy: Hinweis (auch mit legs kein Neuversuch)")
    check(CallRoom.joinOutcome(code: 409, body: ["legs": []]) == .reoffer, "Liste veraltet: neu anbieten")
    check(CallRoom.joinOutcome(code: 409, body: ["join_block": "already", "error": "schon"]) == .blocked(.other, "schon"), "already bleibt Fehler")
    check(CallRoom.joinOutcome(code: 409, body: ["join_block": "full"]) == .blocked(.full, nil), "voll")
    check(CallRoom.joinOutcome(code: 429, body: nil) == .failed(429, nil), "429")
    check(CallRoom.block("handover_busy") == .handoverBusy, "Block handover_busy")

    // takeover bei vollem Anruf (E6)
    check(CallRoom.joinAllowed(role: "handover", joinable: false, takeover: true), "voll + takeover: uebernehmen geht")
    check(!CallRoom.joinAllowed(role: "companion", joinable: false, takeover: true), "voll: Hinzufuegen gesperrt")
    check(!CallRoom.joinAllowed(role: "handover", joinable: false, takeover: nil), "aelterer Server: wie bisher gesperrt")
    check(CallRoom.joinAllowed(role: "member", joinable: nil, takeover: nil), "ohne joinable: erlaubt")
    var run: [String: Any] = ["id": String(repeating: "1", count: 32), "kind": "dm", "state": "active", "mine": [a], "legs": [leg(a), leg(p, who: "bert")],
                              "joinable": false, "join_block": "full", "takeover": true]
    let x = CallRoom.running(run)!
    let bar = CallRoom.bar(x, here: b, current: nil, ringing: false)
    check(x.takeover && CallRoom.barActions(x, bar: bar) == CallRoom.BarActions(add: false, takeover: true, join: false), "Leiste voll: nur Hierher uebernehmen")
    run["takeover"] = false
    check(CallRoom.barActions(CallRoom.running(run)!, bar: bar) == CallRoom.BarActions(), "voll ohne takeover: keine Knoepfe")
    run["joinable"] = true; run["join_block"] = NSNull()
    check(CallRoom.barActions(CallRoom.running(run)!, bar: bar) == CallRoom.BarActions(add: true, takeover: true), "frei: Hinzufuegen und Uebernehmen")
    check(CallRoom.barActions(x, bar: .group(devices: 3)) == CallRoom.BarActions(), "Gruppe voll: kein Beitreten")

    // R09 + 2.5: ein Geraet, das vorher handover war, fuehrt -> hier beenden; Begleitgeraet bleibt Begleitgeraet -> nicht
    let o1 = CallRoom.legs([leg(a), leg(b, role: "handover"), leg(p, who: "bert")])
    let n1 = CallRoom.legs([leg(b), leg(p, who: "bert")])
    check(CallRoom.takenElsewhere(old: o1, new: n1, here: a, me: me)?.device == b, "Rueckfall hat dieses Geraet abgemeldet: uebernommen")
    let o2 = CallRoom.legs([leg(a), leg(b, role: "companion"), leg(p, who: "bert")])
    let n2 = CallRoom.legs([leg(b, role: "companion"), leg(p, who: "bert")])
    check(CallRoom.takenElsewhere(old: o2, new: n2, here: a, me: me) == nil, "Begleitgeraet bleibt Begleitgeraet: Aussetzer, weiter")
    let n3 = CallRoom.legs([leg(b), leg(p, who: "bert")])
    check(CallRoom.takenElsewhere(old: o2, new: n3, here: a, me: me)?.device == b, "Begleitgeraet wurde Hauptgeraet (promote)")
    let o4 = CallRoom.legs([leg(a), leg(b), leg(p, who: "bert")])
    check(CallRoom.takenElsewhere(old: o4, new: n3, here: a, me: me) == nil, "schon vorher Mitglied: nicht beenden")
    check(CallRoom.takenElsewhere(old: nil, new: n3, here: a, me: me) == nil && CallRoom.takenElsewhere(old: o1, new: o1, here: a, me: me) == nil, "erste Liste bzw. noch dabei")
    check(CallRoom.takenElsewhere(old: o1, new: CallRoom.legs([leg(p, who: "bert"), leg(b, who: "bert")]), here: a, me: me) == nil, "fremdes Geraet zaehlt nicht")

    // Mehrfaches Hin und Her (Wiedereintritt): jede Runde neue gen, alte Rueckfaelle gelten nicht mehr
    var gens: [String: Int] = [a: 1000, b: 0]
    var legsNow = CallRoom.legs([leg(a, gen: NSNumber(value: 1000)), leg(p, who: "bert", gen: NSNumber(value: 500))])
    var pendingLeaves: [[String: Any]] = []
    for round in 1...6 {
        let to = round % 2 == 1 ? b : a, from = round % 2 == 1 ? a : b
        gens[to] = 1000 + round * 10                                       // Server: neue Generation je Beitritt
        // Rueckfall des neuen Geraets und Verlassen des alten - mit der gen von JETZT
        pendingLeaves.append(CallRoom.leaveBody(device: from, reason: "handover", by: to, legs: legsNow, v2: true))
        legsNow = CallRoom.legs([leg(to, gen: NSNumber(value: gens[to]!)), leg(p, who: "bert", gen: NSNumber(value: 500))])
    }
    // Server-Sicht nachgespielt: ein /leave gilt nur, wenn gen noch die aktuelle ist (leave_stale)
    let applied = pendingLeaves.filter { l in (l["gen"] as? Int) == gens[l["device"] as! String] }
    check(pendingLeaves.count == 6 && pendingLeaves.allSatisfy { $0["gen"] != nil && $0["by"] != nil }, "jede Runde: by und gen")
    check(applied.isEmpty, "6x hin und her: kein spaeter Rueckfall wirft das zurueckgekehrte Geraet hinaus (\(applied.count))")

    // Uebernahme mit promote (Begleitgeraet ueber Knoten) und Ruecknahme
    var h = CallRoom.Handover()
    h.begin(rep: a, now: 0, timeout: 15, promote: true)
    check(h.active && h.promo && h.check(others: [true], now: 1) == .wait, "promote laeuft: noch kein state.ready, obwohl verbunden")
    h.promotedNow(already: false)
    check(h.promoted && h.check(others: [true], now: 2) == .wait, "befoerdert, Ton-Bein noch nicht neu")
    h.promoReady()
    check(h.check(others: [true], now: 3) == .takeOver(a) && h.undo == nil, "Ton-Bein steht: uebernehmen, nichts zuruecknehmen")
    var f = CallRoom.Handover()
    f.begin(rep: a, now: 0, timeout: 15, promote: true); f.promotedNow(already: false)
    check(f.check(others: [true], now: 16) == .fail && f.undo == a, "Frist abgelaufen nach promote: zuruecknehmen an das alte Geraet")
    var c = CallRoom.Handover()
    c.begin(rep: a, now: 0, timeout: 15, promote: true); c.promotedNow(already: false)
    check(c.cancel() == a && !c.active && c.undo == nil, "Fehler beim Ton-Bein: abbrechen -> Ruecknahme")
    var al = CallRoom.Handover()
    al.begin(rep: a, now: 0, timeout: 15, promote: true); al.promotedNow(already: true)
    check(al.cancel() == nil, "war schon Hauptgeraet: nichts zuruecknehmen")
    var st = CallRoom.Handover()
    st.begin(rep: a, now: 0, promote: true); let n0 = st.attempt
    st.begin(rep: b, now: 1, promote: true)
    check(st.attempt == n0 + 1 && !st.promoted, "neuer Versuch: spaete promote-Antwort des alten erkennbar")
    var plain = CallRoom.Handover()
    plain.begin(rep: a, now: 0, timeout: 15)
    check(!plain.promo && plain.check(others: [true], now: 1) == .takeOver(a), "Mesh: ohne promote wie bisher")
    check(CallRoom.needsPromote(horn: true, v2: true) && !CallRoom.needsPromote(horn: false, v2: true) && !CallRoom.needsPromote(horn: true, v2: false), "promote nur ueber Knoten mit handover2")
    check(CallRoom.promoteOutcome(code: 200, body: ["ok": true, "call": [:]]) == .ok(already: false), "promote ok")
    check(CallRoom.promoteOutcome(code: 200, body: ["ok": true, "already": true]) == .ok(already: true), "promote already")
    check(CallRoom.promoteOutcome(code: 409, body: ["join_block": "old"]) == .old, "foederiert ohne promote: wie bisher")
    check(CallRoom.promoteOutcome(code: 409, body: ["error": "x"]) == .failed("x") && CallRoom.promoteOutcome(code: nil, body: nil) == .failed(nil), "promote Fehler")
    let pb = CallRoom.promoteBody(device: b, devClass: "mobile")
    check(pb["device"] as? String == b && pb["dev_class"] as? String == "mobile" && CallRoom.promoteBody(device: a, devClass: nil).count == 1, "promote-Body")

    // call.mod why handover
    check(CallRoom.mod(["action": "mute", "device": b, "by": "", "why": "handover"], here: b) == .handoverMute, "mute why handover: Begleitgeraet, kein Hinweis 'stummgeschaltet'")
    check(CallRoom.mod(["action": "mute", "device": b, "by": "Anna"], here: b) == .mute(by: "Anna"), "Gastgeber schaltet stumm")
    check(CallRoom.mod(["action": "mute", "device": a, "why": "handover"], here: b) == .none, "anderes Geraet gemeint")

    // Diagnose: Geraete-CPU aus WebRTC-Stats (Zuwaechse, 0 weglassen)
    var d = CallDiag.Buffer(call: "c1", plat: "ios", app: "2.5.0", t0: 0)
    let o0: [String: Any] = ["kind": "video", "rid": "m", "framesEncoded": NSNumber(value: 100), "totalEncodeTime": NSNumber(value: 0.5),
                             "qualityLimitationDurations": ["cpu": NSNumber(value: 1.0), "bandwidth": NSNumber(value: 0), "none": NSNumber(value: 9)]]
    let s0 = d.sample(type: "outbound-rtp", values: o0, src: "me", key: "o", now: 1000, rtt: nil, srcFrames: 110)
    check(s0?.qcpu == 0 && s0?.encms == 0 && s0?.fdrop == 0, "erste Probe: keine Zuwaechse")
    let o1: [String: Any] = ["kind": "video", "rid": "m", "framesEncoded": NSNumber(value: 190), "totalEncodeTime": NSNumber(value: 0.95),
                             "qualityLimitationDurations": ["cpu": NSNumber(value: 2.5), "bandwidth": NSNumber(value: 0.25)]]
    let s1 = d.sample(type: "outbound-rtp", values: o1, src: "me", key: "o", now: 4000, rtt: nil, srcFrames: 210)
    check(s1?.qcpu == 1500 && s1?.qbw == 250 && s1?.encms == 5 && s1?.fdrop == 10, "out: qcpu/qbw ms, encms je Rahmen, fdrop = Kamera - kodiert: \(String(describing: s1))")
    let j1 = s1?.json ?? [:]
    check(j1["qcpu"] as? Int == 1500 && j1["encms"] as? Double == 5 && j1["decms"] == nil && j1["fzms"] == nil, "JSON: nur Werte > 0")
    let o2: [String: Any] = ["kind": "video", "rid": "l", "framesEncoded": NSNumber(value: 190), "totalEncodeTime": NSNumber(value: 0.95)]
    let s2 = d.sample(type: "outbound-rtp", values: o2, src: "me", key: "o", now: 7000, rtt: nil, srcFrames: 300)
    check(s2?.fdrop == 0 && s2?.encms == 0, "Schicht kodiert gerade nicht: kein fdrop")
    _ = d.sample(type: "inbound-rtp", values: ["kind": "video", "framesDecoded": NSNumber(value: 10), "totalDecodeTime": NSNumber(value: 0.02),
                                               "framesDropped": NSNumber(value: 1), "totalFreezesDuration": NSNumber(value: 0.1)], src: "p1", key: "i", now: 1000, rtt: nil)
    let si = d.sample(type: "inbound-rtp", values: ["kind": "video", "framesDecoded": NSNumber(value: 110), "totalDecodeTime": NSNumber(value: 0.27),
                                                    "framesDropped": NSNumber(value: 4), "totalFreezesDuration": NSNumber(value: 0.9)], src: "p1", key: "i", now: 4000, rtt: nil)
    check(si?.decms == 2.5 && si?.fdrop == 3 && si?.fzms == 800 && si?.qcpu == 0, "in: decms, fdrop = framesDropped, fzms: \(String(describing: si))")
    let sz = d.sample(type: "inbound-rtp", values: ["kind": "video", "framesDecoded": NSNumber(value: Double.nan)], src: "p1", key: "z", now: 1000, rtt: nil)
    check(sz?.json["decms"] == nil, "ungueltige Werte: weggelassen")

    // Galerie: Wetter-Ort beim Anlegen
    check(GalleryLogic.previewPath("persoenlich-start", lang: "de") == "api/gallery/persoenlich-start?lang=de" && GalleryLogic.previewPath("../x", lang: "de") == nil, "Vorschau-Pfad")
    check(GalleryLogic.askWeather(Data(#"{"id":"x","ask_weather":true}"#.utf8)) && !GalleryLogic.askWeather(Data(#"{"id":"x"}"#.utf8))
          && !GalleryLogic.askWeather(Data(#"{"ask_weather":"yes"}"#.utf8)), "ask_weather nur bei true")
    check(GalleryLogic.geocodePath(" H") == nil && GalleryLogic.geocodePath(String(repeating: "x", count: 81)) == nil, "Suche: 2-80 Zeichen")
    check(GalleryLogic.geocodePath("São Paulo&x") == "api/widgets/geocode?q=S%C3%A3o%20Paulo%26x", "Suche: kodiert")
    let pl = GalleryLogic.places(Data(#"{"results":[{"name":"Hamburg","admin":"Hamburg","country":"Deutschland","lat":53.55,"lon":9.99},{"name":"Bad","lat":"x","lon":1},{"name":"","lat":1,"lon":1},{"name":"Lyon","admin":"Auvergne","country":"","lat":45.76,"lon":4.84}],"error":null}"#.utf8))
    check(pl?.count == 2 && pl?[0].label == "Hamburg (Deutschland)" && pl?[1].label == "Lyon, Auvergne", "Treffer lesen, Anzeige: \(pl?.map(\.label) ?? [])")
    check(GalleryLogic.places(Data("x".utf8)) == nil && GalleryLogic.places(Data(#"{"results":[]}"#.utf8)) == [], "leer bzw. kaputt")
    check(GalleryLogic.geoFailure(code: 429) == .rateLimited && GalleryLogic.geoFailure(code: 409) == .unavailable && GalleryLogic.geoFailure(code: 200) == .none, "Ortssuche: Fehler")
    let ub = GalleryLogic.useBody(parent: nil, examples: true, place: pl?[0])
    let wp = ub["weather_place"] as? [String: Any]
    check(wp?["place"] as? String == "Hamburg" && wp?["lat"] as? Double == 53.55 && wp?["lon"] as? Double == 9.99 && ub["parent_id"] is NSNull, "weather_place im Body")
    check(GalleryLogic.useBody(parent: 5, examples: false)["weather_place"] == nil, "Ueberspringen: ohne weather_place")
}
