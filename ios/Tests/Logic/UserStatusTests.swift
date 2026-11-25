import Foundation

// 2.6 (Bugs-DB 2029-6): eigener Status, Erreichbarkeit, Status anderer, Anklopfen/stille Anrufe (App/UserStatusLogic.swift)
func userStatusTests() {
    typealias L = UserStatusLogic
    func j(_ s: String) -> Any? { try? JSONSerialization.jsonObject(with: Data(s.utf8)) }

    // Ansicht
    let full = #"{"state":"dnd","until":1792450200,"text":"Im Kundentermin","source":"manual","since":1,"updated":2,"ver":7,"effective":"dnd","why":"manual","quiet":false,"in_call":false,"prefs":{"quiet_dnd":false,"quiet_digest":true,"in_call":"busy","show_call":false,"busy":"silent","repeat":false,"repeat_s":300,"mention_break":true},"exceptions":[{"type":"user","ref":"~abcdefghijklmnop","name":"Bruno Beispiel"},{"type":"group","name":"Familie"},{"type":"contact","ref":"x"}],"limits":{"text":80,"exceptions":100,"repeat_s":[30,3600],"until_max":1,"dur_min":60},"now":1792440000}"#
    guard let v = L.view(j(full)) else { check(false, "Ansicht lesen"); return }
    check(v.state == "dnd" && v.until == 1792450200 && v.text == "Im Kundentermin" && v.ver == 7 && v.effective == "dnd" && v.why == "manual", "Ansicht: Zustand, Ende, Text, ver")
    check(v.prefs == L.Prefs(quietDnd: false, quietDigest: true, inCall: "busy", showCall: false, busy: "silent", repeatOn: false, repeatS: 300, mentionBreak: true), "Einstellungen gelesen")
    check(v.exceptions.map(\.id) == ["user:~abcdefghijklmnop", "group:Familie"] && v.exceptions[0].shown == "Bruno Beispiel", "Ausnahmen: reservierte Arten verworfen")
    check(v.limits.repeatMin == 30 && v.limits.repeatMax == 3600 && v.limits.text == 80, "Grenzen")
    check(L.view(j(#"{"state":"weg","ver":1}"#)) == nil && L.view(j(#"{"state":"busy"}"#)) == nil && L.view(j(#"{"state":"busy","ver":true}"#)) == nil, "ohne gueltigen Zustand/ver: nichts")
    let def = L.view(j(#"{"state":"available","ver":0}"#))
    check(def?.prefs == L.Prefs() && def?.effective == "available", "Standard-Einstellungen")
    check(L.newer(v, than: nil) && L.newer(v, than: v) && !L.newer(def!, than: v), "aeltere Antwort ueberholt nicht")

    // PUT-Antworten: 409 still uebernehmen
    check(L.putResult(status: 200, data: Data(full.utf8)) == .ok(v), "PUT ok")
    check(L.putResult(status: 409, data: Data(full.utf8)) == .conflict(v), "409 mit Ansicht -> uebernehmen")
    check(L.putResult(status: 400, data: Data(#"{"error":"Text zu lang"}"#.utf8)) == .failed("Text zu lang") && L.putResult(status: 500, data: Data()) == .failed(nil), "Fehler mit Text")

    // Setzen: Dauer, Text, ver
    var cal = Calendar(identifier: .gregorian); cal.timeZone = TimeZone(identifier: "Europe/Berlin")!
    let noon = cal.date(from: DateComponents(year: 2026, month: 11, day: 25, hour: 12))!
    let night = cal.date(from: DateComponents(year: 2026, month: 11, day: 25, hour: 3))!
    let m1 = L.morning(now: noon, calendar: cal), m2 = L.morning(now: night, calendar: cal)
    check(!m1.today && m1.ts == cal.date(from: DateComponents(year: 2026, month: 11, day: 26, hour: 7))!.timeIntervalSince1970, "Bis morgen 7:00")
    check(m2.today && m2.ts == cal.date(from: DateComponents(year: 2026, month: 11, day: 25, hour: 7))!.timeIntervalSince1970, "vor 5 Uhr: heute 7:00")
    let b1 = L.setBody(state: "busy", duration: .hour, text: "  Im Termin \n", ver: 7, now: noon, calendar: cal)
    check(b1["state"] as? String == "busy" && b1["source"] as? String == "manual" && b1["dur"] as? Int == 3600 && b1["text"] as? String == "Im Termin" && b1["ver"] as? Int == 7 && b1["until"] == nil, "1 Stunde mit Text")
    let b2 = L.setBody(state: "dnd", duration: .off, text: "", ver: nil, now: noon, calendar: cal)
    check(b2["dur"] == nil && b2["until"] == nil && b2["text"] is NSNull && b2["ver"] == nil, "bis ich es ausschalte, Text leer = null")
    let b3 = L.setBody(state: "available", duration: .hour, text: "x", ver: 1, now: noon, calendar: cal)
    check(b3["dur"] == nil && b3["until"] == nil, "Verfuegbar: keine Dauer")
    check(L.setBody(state: "dnd", duration: .morning, text: "", ver: 1, now: noon, calendar: cal)["until"] as? Double == m1.ts, "bis morgen 7:00 als until")
    check(L.clampText(String(repeating: "a", count: 90), max: 80).count == 80 && L.clampText("a\u{7}b", max: 80) == "a b", "Text <= 80, Steuerzeichen")
    let now = 1792440000.0
    check(L.durations(v, now: now) == [.keep(1792450200), .hour, .morning, .off] && L.defaultDuration(v, now: now) == .keep(1792450200), "Dauer wie bisher")
    check(L.durations(v, now: 1792450190) == [.hour, .morning, .off] && L.defaultDuration(def!, now: now) == .off, "Ende gleich erreicht: nicht anbieten")

    // Fokus (RT-29): nur Zustand und Quelle
    let f = L.focusBody(state: "dnd")
    check(Set(f.keys) == ["state", "source"] && f["source"] as? String == "focus" && f["state"] as? String == "dnd", "Fokus-PUT nur {state, source}")
    check(L.focusBody(state: "Arbeit")["state"] as? String == "available", "unbekannter Zustand -> available")
    if let d = try? JSONSerialization.data(withJSONObject: L.focusBody(state: "busy")), let s = String(data: d, encoding: .utf8) {
        check(!s.contains("text") && !s.contains("name") && !s.contains("focus_name"), "Nutzlast ohne Text/Name: \(s)")
    }

    // Anzeige eigener Status
    check(L.shown(v) == .init(state: "dnd", hint: .until(1792450200), focus: false), "Nicht stoeren bis …")
    var q = v; q.state = "available"; q.effective = "dnd"; q.why = "quiet"; q.until = nil
    check(L.shown(q).hint == .quiet(to: nil) && L.shown(q).state == "dnd", "Ruhezeit zaehlt als Nicht stoeren")
    var c = v; c.state = "busy"; c.effective = "in_call"; c.why = "call"
    check(L.shown(c) == .init(state: "in_call", hint: .under("busy"), focus: false), "Im Anruf, darunter Beschaeftigt")
    var fo = v; fo.source = "focus"; fo.until = nil
    check(L.shown(fo).focus && L.shown(fo).hint == .none, "vom Geraet (Fokus)")
    check(L.quietTo(j(#"{"settings":{"quiet":{"on":true,"from":"22:00","to":"07:00"}}}"#)) == "07:00" && L.quietTo(j(#"{"settings":{"quiet":{"on":false,"to":"07:00"}}}"#)) == nil, "Ende der Ruhezeit")
    check(L.nextCheck(v, now: 1792450100) == 102.5 && L.nextCheck(nil, now: 0) == 300 && L.nextCheck(v, now: 1792450300) == 2, "Abfrage-Takt")

    // Status anderer
    let pres = j(#"{"bert":{"s":"dnd","until":1792450200.0,"text":"Im Kundentermin"},"cora":{"s":"in_call"},"@~abcdefghijklmnop@b.example":{"s":"busy"},"x":{"s":"weg"},"y":5}"#)
    let ps = L.peers(pres)
    check(ps.count == 3 && ps["bert"] == .init(s: "dnd", until: 1792450200, text: "Im Kundentermin") && ps["cora"]?.s == "in_call", "Presence status")
    var map = ps
    check(L.applyLive(["username": "cora", "status": NSNull()], to: &map) && map["cora"] == nil, "chat.status null -> entfernen")
    check(L.applyLive(["username": "dora", "status": ["s": "busy"]], to: &map) && map["dora"]?.s == "busy", "chat.status neu")
    check(!L.applyLive(["username": "dora", "status": ["s": "busy"]], to: &map), "unveraendert")
    var at: [String: Double] = ["dora": 100, "old": 10]
    let merged = L.merge(answer: ["bert": ps["bert"]!], old: map, liveAt: &at, sent: 50)
    check(merged["dora"]?.s == "busy" && merged["bert"] != nil && at["old"] == nil && at["dora"] == 100, "Live nach dem Senden bleibt, aeltere verworfen")
    check(L.visible(.init(s: "available")) == nil && L.visible(ps["bert"]) != nil, "Verfuegbar zeigt nichts")
    check(L.callAsk(ps["bert"]) == .confirmDnd && L.callAsk(ps["cora"]) == .knockHint && L.callAsk(.init(s: "busy")) == .none && L.callAsk(nil) == .none, "Rueckfrage/Hinweis vor dem Anruf")

    // Sammelmeldung: nur Zahlen
    check(L.digest(j(#"{"messages":4,"calls":1,"other":0,"since":1,"until":2}"#)) == .init(messages: 4, calls: 1, other: 0), "status.digest")
    check(L.digest(j(#"{"messages":0,"calls":0}"#)) == nil && L.digest(nil) == nil && L.digest(j(#"{"messages":-3,"other":true}"#)) == nil, "leer/kaputt: nichts")

    // Erreichbarkeit
    check(L.repeatValid(30, v.limits) && L.repeatValid(3600, v.limits) && !L.repeatValid(29, v.limits) && !L.repeatValid(3601, v.limits), "repeat_s in Grenzen")
    let eb = L.exceptionsBody(v.exceptions)
    check(eb == [["type": "user", "ref": "~abcdefghijklmnop"], ["type": "group", "name": "Familie"]], "Ausnahmen-Body ohne Namen")
    check(L.isHandle("~abcdefghijklmnop") && L.isHandle("~abcdefghijklmnop@b.example") && !L.isHandle("bruno") && !L.isHandle("~ABC"), "Handle")
    check(L.exception(type: "remote", target: "anna@b.example", name: "Anna") == nil && L.exception(type: "remote", target: "~abcdefghijklmnop@b.example", name: "Anna")?.type == "user", "anderer Server nur mit Handle")
    check(L.exception(type: "group", target: "Familie", name: "Familie") == .init(type: "group", ref: "Familie", name: "Familie"), "Gruppe")
    let one = L.Exception(type: "user", ref: "~abcdefghijklmnop", name: nil)
    check(L.adding(one, to: v.exceptions, max: 100) == nil && L.adding(.init(type: "user", ref: "~bbbbbbbbbbbbbbbb", name: nil), to: v.exceptions, max: 2) == nil
          && L.adding(.init(type: "user", ref: "~bbbbbbbbbbbbbbbb", name: nil), to: v.exceptions, max: 100)?.count == 3, "doppelt / voll")

    // Anrufe: Anklopfen, stille Anrufe
    typealias H = CallHoldLogic
    check(H.liveWanted(foreground: true, phase: "idle") && H.liveWanted(foreground: true, phase: "active"), "Live: Vordergrund immer")
    check(H.liveWanted(foreground: false, phase: "active") && H.liveWanted(foreground: false, phase: "connecting") && H.liveWanted(foreground: false, phase: "ringingOut"),
          "Live im Hintergrund waehrend des Gespraechs (CallKit-Annehmen auf dem Sperrbildschirm)")
    check(!H.liveWanted(foreground: false, phase: "idle") && !H.liveWanted(foreground: false, phase: "ended") && !H.liveWanted(foreground: false, phase: "incoming"),
          "Live im Hintergrund ohne Gespraech zu (auch beim blossen Klingeln)")
    check(H.reportsWaiting(serverFeatures: ["reneg", "waiting1"]) && !H.reportsWaiting(serverFeatures: ["reneg", "multi"]), "waiting1 nur mit Server")
    check(H.ring(sameCallActive: true, busy: true, waiting: true, quiet: false, freed: false) == .ignore, "derselbe Anruf")
    check(H.ring(sameCallActive: false, busy: true, waiting: true, quiet: false, freed: false) == .knock(here: true), "im Gespraech: Anklopf-Leiste")
    check(H.ring(sameCallActive: false, busy: false, waiting: true, quiet: false, freed: false) == .knock(here: false), "Gespraech woanders: nicht klingeln")
    check(H.ring(sameCallActive: false, busy: false, waiting: true, quiet: false, freed: true) == .ring, "eben aufgelegt: klingelt normal")
    check(H.ring(sameCallActive: false, busy: true, waiting: false, quiet: false, freed: false) == .busy, "ohne waiting: besetzt wie bisher")
    check(H.ring(sameCallActive: false, busy: false, waiting: false, quiet: true, freed: false) == .quiet && H.ring(sameCallActive: false, busy: true, waiting: false, quiet: true, freed: false) == .quiet, "quiet: still")
    check(H.ring(sameCallActive: false, busy: false, waiting: false, quiet: false, freed: false) == .ring, "normal klingeln")
    check(H.invite(dm: true, waiting: true, quiet: false) == .knock && H.invite(dm: true, waiting: false, quiet: true) == .quiet
          && H.invite(dm: false, waiting: false, quiet: true) == .silent && H.invite(dm: false, waiting: false, quiet: false) == .normal, "call.invite")
    check(H.pollGone(state: "ended") && H.pollGone(state: "active") && H.pollGone(state: "declined") && !H.pollGone(state: "ringing") && !H.pollGone(state: nil), "Leiste: Ende per Abfrage")
    check(H.lifetime(expiresIn: 2, ringS: 45) == 8 && H.lifetime(expiresIn: nil, ringS: 30) == 33 && H.lifetime(expiresIn: nil, ringS: nil) == 48, "Zeitgrenze")
    check(H.left(expiresIn: 40, ringS: nil, shownAt: 100, now: 110) == 30, "Restzeit")
    check(H.free(mode: .wait, left: 30) == .ring(30) && H.free(mode: .quiet, left: 30) == .quiet(30) && H.free(mode: .wait, left: 1) == .drop, "nach dem Gespraech")
    let rows: [[String: Any]] = [["id": "a", "state": "active", "kind": "dm", "role": "callee"], ["id": "b", "state": "ringing", "kind": "dm", "role": "callee"]]
    check(H.busyElsewhere(rows, except: "b", current: nil) && !H.busyElsewhere(rows, except: "b", current: "a") && !H.busyElsewhere([rows[1]], except: "x", current: nil), "eigenes Gespraech woanders")
    check(!H.busyElsewhere([["id": "g", "state": "active", "kind": "group", "mine": []]], except: "x", current: nil)
          && H.busyElsewhere([["id": "g", "state": "active", "kind": "group", "mine": ["d1"]]], except: "x", current: nil), "Gruppe nur mit eigenem Geraet")
    check(H.dial(code: 409, body: ["busy": true, "again": true, "error": "Bruno telefoniert noch – du hast schon angeklopft."]) == .again("Bruno telefoniert noch – du hast schon angeklopft."), "409 again mit Text")
    check(H.dial(code: 409, body: ["busy": true]) == .busy && H.dial(code: 409, body: ["devices": []]) == .other && H.dial(code: 200, body: nil) == .ok, "409 busy / anderes")
    check(H.callerWaiting(["waiting": true]) && !H.callerWaiting(["waiting": false]) && !H.callerWaiting(nil), "Anrufer: klopft an")
    check(H.endConfirmed(200) && H.endConfirmed(404) && H.endConfirmed(409) && !H.endConfirmed(500) && !H.endConfirmed(nil), "Ende bestaetigt")
    check(H.entryNote(status: "missed", hold: "dnd", mine: false, group: false) == .dnd, "Verpasster Anruf (Nicht stoeren)")
    check(H.entryNote(status: "missed", hold: "dnd", mine: true, group: false) == .plain && H.entryNote(status: "missed", hold: nil, mine: false, group: false) == .plain, "Anrufer bzw. ohne hold: wie bisher")
    check(H.entryNote(status: "busy", hold: "again", mine: false, group: false) == .inCall && H.entryNote(status: "missed", hold: "knock", mine: false, group: false) == .inCall, "angeklopft")
    check(H.entryNote(status: "missed", hold: "dnd", mine: false, group: true) == .plain, "Gruppen ohne hold")
    let e = CallLogic.Entry(json: Data(#"{"type":"call","call":"x","media":"video","status":"missed","from":"bruno","to":"alex","hold":"dnd"}"#.utf8))
    check(e?.hold == "dnd" && CallLogic.Entry(json: Data(#"{"type":"call","call":"x","status":"missed","hold":5}"#.utf8))?.hold == nil, "Eintrag: hold gelesen")

    // Online-Abo: mit userstatus1 auch bei eigenem Online-Status aus
    var caps = ReceiptLogic.Caps(); caps.online = true; caps.privacy.online = false
    let dm = ReceiptLogic.PeerRef(user: "bert", flags: ReceiptLogic.ChatFlags())
    var grp = ReceiptLogic.ChatFlags(); grp.kind = "group"
    check(ReceiptLogic.presenceWant(open: dm, list: nil, caps: caps).isEmpty, "ohne Status: wie bisher kein Abo")
    check(ReceiptLogic.presenceWant(open: dm, list: [.init(user: "x", flags: grp)], caps: caps, status: true) == ["bert"], "Status: Abo auch mit Online-Status aus, nur Direkt-Chats")
    var req = ReceiptLogic.ChatFlags(); req.request = true
    check(ReceiptLogic.presenceWant(open: .init(user: "r", flags: req), list: nil, caps: caps, status: true).isEmpty, "keine Anfragen")
}
