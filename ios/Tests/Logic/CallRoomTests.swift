import Foundation

// Anrufe 2.4 E4/E5 (Vertrag "Anrufe: Mehrgeraete und Gruppen"): Teilnehmerliste mit Stand (rv), Mesh-Paare, Grenzen,
// Begleitgeraet, Uebernahme-Zustandsmaschine, Mesh-Bitrate, Leiste "Anruf laeuft", Gruppen-Knopf, Sprecher, Geister-Geraet
func callRoomTests() {
    roomLegTests()
    roomRosterTests()
    roomMeshTests()
    roomLimitTests()
    roomCompanionTests()
    roomHandoverTests()
    roomBitrateTests()
    roomViewTests()
    roomBarTests()
    roomGroupTests()
    roomBodyTests()
}

private func leg(_ who: String, _ dev: String, role: String = "member", name: String? = nil, devName: String = "", caps: [String] = ["reneg", "want", "bye", "screen2", "multi"]) -> [String: Any] {
    ["who": who, "name": name ?? who.capitalized, "device": dev, "dev_name": devName, "role": role, "state": "active", "caps": caps, "joined": 1792458574.1, "me": false]
}

private func roomLegTests() {
    let l = CallRoom.legs([leg("anna", "0e32", devName: "iPhone"), leg("bert", "5cbb", role: "companion", devName: "MacBook"),
                           ["who": "x", "device": "a b"], ["device": "ffff"], leg("anna", "0e32"), "kaputt"])
    check(l.count == 2, "ungueltige und doppelte Endpunkte fallen weg")
    check(l.first?.devName == "iPhone" && l.first?.multi == true, "Geraetename, multi")
    check(l.last?.companion == true && l.last?.role == "companion", "Begleitgeraet erkannt")
    let f = CallRoom.legs([["who": "~cara@b.example", "name": "Cara", "device": "c0de", "handle": "cara@b.example", "host": "b.example", "role": "handover", "replaces": "beef"]])
    check(f.first?.handle == "cara@b.example" && f.first?.host == "b.example" && f.first?.replaces == "beef", "foederiert: handle, host, replaces")
    check(f.first?.multi == false, "ohne caps: kein multi (braucht Saga 2.4)")
    check(CallRoom.validDevice("5cbb_a-1") && !CallRoom.validDevice("") && !CallRoom.validDevice(String(repeating: "a", count: 65)), "Geraete-ID")
}

private func roomRosterTests() {
    var r = CallRoom.Roster()
    let a = CallRoom.legs([leg("anna", "0e32"), leg("bert", "5cbb")])
    let b = CallRoom.legs([leg("anna", "0e32"), leg("bert", "5cbb"), leg("bert", "a130", role: "companion")])
    check(r.apply(a, rv: 10), "erste Liste gilt")
    check(r.apply(b, rv: 12) && r.legs.count == 3, "neuere Liste gilt")
    check(!r.apply(a, rv: 11) && r.legs.count == 3, "aeltere Liste ueberschreibt keine neuere (rv)")
    check(r.apply(a, rv: 12) && r.legs.count == 2, "gleicher Stand: uebernehmen")
    check(r.leg("0e32")?.who == "anna" && r.leg("nix") == nil && r.leg(nil) == nil, "Endpunkt nach Geraet")
    r.reset()
    check(!r.known && r.apply(a, rv: 1), "nach reset wieder offen")
    let d = CallRoom.diff(old: a, new: b, here: "0e32")
    check(d.added.map(\.device) == ["a130"] && d.removed.isEmpty, "hinzugekommen")
    let d2 = CallRoom.diff(old: b, new: a, here: "0e32")
    check(d2.removed.map(\.device) == ["a130"] && d2.added.isEmpty, "verlassen")
    check(CallRoom.diff(old: nil, new: b, here: "0e32").added.isEmpty, "erste Liste: keine Hinweise")
    check(CallRoom.diff(old: [], new: a, here: "0e32").added.map(\.device) == ["5cbb"], "eigenes Geraet nie als hinzugekommen")
}

private func roomMeshTests() {
    check(CallRoom.pairs(["b", "a"]) == ["a:b"], "zwei Geraete: eine Verbindung")
    check(CallRoom.pairs(["c", "a", "b"]) == ["a:b", "a:c", "b:c"], "drei Geraete: drei Verbindungen")
    check(CallRoom.pairs(["a", "b", "c", "d"]).count == 6, "vier Geraete: sechs Verbindungen (n(n-1)/2)")
    check(CallRoom.pairs(["a", "a", "b"]) == ["a:b"], "doppelte Geraete zaehlen einmal")
    check(CallRoom.pairs([]).isEmpty && CallRoom.pairs(["a"]).isEmpty, "allein: keine Verbindung")
    let legs = CallRoom.legs([leg("anna", "0e32"), leg("bert", "5cbb"), leg("bert", "a130")])
    check(CallRoom.offerTargets(legs, here: "a130") == ["0e32", "5cbb"], "wer hinzukommt, bietet allen anderen an (auch eigenen Geraeten)")
    for p in CallRoom.pairs(["0e32", "5cbb", "a130"]) {
        let s = p.split(separator: ":").map(String.init)
        check(s.count == 2 && s[0] < s[1] && CallV3.pairPC(s[1], s[0]) == p, "pc = sortiertes Paar \(p)")
    }
    check(CallV3.polite(me: "a130", peer: "0e32") && !CallV3.polite(me: "0e32", peer: "a130"), "je Verbindung hoeflich = groessere device_id")
}

private func roomLimitTests() {
    check(CallRoom.limit(media: "video", server: nil) == 4 && CallRoom.limit(media: "audio", server: nil) == 6, "Mesh: 4 Video, 6 nur Ton")
    check(CallRoom.limit(media: "video", server: 3) == 3, "Admin senkt: gilt")
    check(CallRoom.limit(media: "video", server: 8) == 4 && CallRoom.limit(media: "audio", server: 12) == 6, "Admin kann nur senken")
    check(CallRoom.limit(media: "video", server: 1) == 4 && CallRoom.limit(media: "audio", server: 0) == 6, "unsinnige Werte: Standard")
    check(!CallRoom.full(count: 3, limit: 4) && CallRoom.full(count: 4, limit: 4), "voll ab der Grenze (eigene Zusatzgeraete zaehlen mit)")
    check(CallRoom.block("old") == .old && CallRoom.block("full") == .full && CallRoom.block("neu") == .other && CallRoom.block(nil) == nil && CallRoom.block("") == nil, "join_block")
}

private func roomCompanionTests() {
    let c = CallRoom.start(role: "companion", video: true)
    check(!c.mic && !c.sound && !c.cam && !c.askMic, "Begleitgeraet: Mikrofon aus, stumm, Kamera aus, nicht nach dem Mikrofon fragen")
    let h = CallRoom.start(role: "handover", video: true)
    check(!h.mic && !h.sound && h.cam && h.askMic, "Uebernahme: erst still, Mikrofon wird gebraucht")
    let m = CallRoom.start(role: "member", video: false)
    check(m.mic && m.sound && !m.cam && m.askMic, "Mitglied: wie ein Anruf")
    let legs = CallRoom.legs([leg("anna", "0e32"), leg("bert", "a130", role: "companion"), leg("bert", "5cbb"), leg("bert", "9999")])
    check(CallRoom.mainDevice(legs, me: "bert", here: "9999")?.device == "5cbb", "Hauptgeraet: eigenes Geraet ohne Begleitrolle")
    check(CallRoom.mainDevice(CallRoom.legs([leg("anna", "0e32"), leg("bert", "a130", role: "companion")]), me: "bert", here: "9999")?.device == "a130", "sonst ein Begleitgeraet")
    check(CallRoom.mainDevice(legs, me: "carl", here: "x") == nil, "kein eigenes Geraet")
    check(CallRoom.hangupEndsAll(kind: "dm", companion: false), "1:1 am Hauptgeraet aufgelegt: Ende fuer alle")
    check(!CallRoom.hangupEndsAll(kind: "dm", companion: true) && !CallRoom.hangupEndsAll(kind: "group", companion: false), "Begleitgeraet/Gruppe: nur dieses Geraet geht")
    check(CallRoom.handoverOut(ready: true, replaces: "5cbb", here: "5cbb", fromOwn: true), "altes Geraet gibt ab")
    check(!CallRoom.handoverOut(ready: true, replaces: "5cbb", here: "5cbb", fromOwn: false), "nur von einem EIGENEN Geraet")
    check(!CallRoom.handoverOut(ready: true, replaces: "0e32", here: "5cbb", fromOwn: true) && !CallRoom.handoverOut(ready: false, replaces: "5cbb", here: "5cbb", fromOwn: true), "nur ready + replaces == dieses Geraet")
    let gone = CallRoom.legs([leg("anna", "0e32"), leg("bert", "5cbb")])
    check(CallRoom.admit(device: "5cbb", legs: gone, signed: ["5cbb"]) == .accept, "Geraet in Liste und signierter Geraeteliste: annehmen")
    check(CallRoom.admit(device: "5cbb", legs: gone, signed: ["0e32"]) == .reject, "Geister-Geraet (nicht signiert): ablehnen")
    check(CallRoom.admit(device: "dead", legs: gone, signed: ["dead"]) == .unknownLeg, "nicht in der Liste: erst Liste holen")
}

private func roomHandoverTests() {
    var h = CallRoom.Handover()
    check(h.check(others: [true], now: 0) == .none && !h.active, "ohne Beginn: nichts")
    check(h.begin(rep: "5cbb", now: 100, timeout: 15) && h.active && h.rep == "5cbb", "Beginn mit altem Geraet")
    check(h.check(others: [], now: 101) == .wait, "noch keine Verbindung: warten")
    check(h.check(others: [true, false], now: 105) == .wait, "nicht alle verbunden: warten")
    check(h.check(others: [true, true], now: 106) == .takeOver("5cbb") && h.phase == .done, "alle Verbindungen zu anderen Personen stehen: uebernehmen")
    check(h.check(others: [true, true], now: 107) == .none, "nur einmal")
    var t = CallRoom.Handover()
    t.begin(rep: "5cbb", now: 100, timeout: 15)
    check(t.check(others: [false], now: 114.9) == .wait, "innerhalb von handover_s")
    check(t.check(others: [false], now: 115) == .fail && t.phase == .failed, "nach handover_s: aufgeben (altes Geraet bleibt)")
    check(t.check(others: [true], now: 116) == .none, "nach dem Aufgeben nichts mehr")
    var n = CallRoom.Handover()
    check(!n.begin(rep: nil, now: 0) && n.phase == .done, "kein altes Geraet: sofort selbst Hauptgeraet")
    var c = CallRoom.Handover()
    c.begin(rep: "x", now: 0); c.cancel()
    check(!c.active && c.check(others: [true], now: 1) == .none, "abgebrochen")
    check(CallRoom.handoverSeconds == 15, "handover_s Standard 15 s")
}

private func roomBitrateTests() {
    check(CallRoom.streams(links: 1) == 1 && CallRoom.streams(links: 0) == 1, "1:1: eine Gegenstelle")
    let d1 = CallV3.videoLimits(path: .direct, kind: .camera, streams: CallRoom.streams(links: 1), want: .large, sourceHeight: 720)
    check(d1.maxBitrate == 1_500_000 && d1.scaleDown == 1, "1:1 direkt: 1,5 Mbit/s, 720p")
    let d = CallV3.videoLimits(path: .direct, kind: .camera, streams: CallRoom.streams(links: 2), want: .large, sourceHeight: 720)
    check(d.maxBitrate == 600_000 && abs(d.scaleDown - 720.0 / 540) < 0.001, "Mesh (3 Endpunkte) direkt: 600 kbit/s, <= 540p")
    let u = CallV3.videoLimits(path: .relayUDP, kind: .camera, streams: 3, want: .large, sourceHeight: 720)
    check(u.maxBitrate == 500_000 && abs(u.scaleDown - 720.0 / 540) < 0.001, "Mesh Relais UDP: 500 kbit/s, <= 540p")
    let t = CallV3.videoLimits(path: .relayTCP, kind: .camera, streams: 3, want: .large, sourceHeight: 720)
    check(t.maxBitrate == 350_000 && t.scaleDown == 2 && t.maxFramerate == 15, "Mesh Relais TCP: 350 kbit/s, 360p15")
    let s = CallV3.videoLimits(path: .direct, kind: .camera, streams: 3, want: .small, sourceHeight: 720)
    check(s.maxBitrate == 200_000, "Mesh want small: 200 kbit/s")
    let sc = CallV3.videoLimits(path: .direct, kind: .screen, streams: 3, want: .large, sourceHeight: nil)
    check(sc.maxBitrate == 2_500_000 && sc.maintainResolution, "Bildschirm im Mesh unveraendert (je Strom)")
    check(CallV3.audioBitrate(path: .relayTCP) == 24_000 && CallV3.audioBitrate(path: .direct) == 32_000, "Ton je Strom")
}

private func roomViewTests() {
    typealias V = CallRoom.LinkView
    check(CallRoom.quiet(V(device: "a", own: true, companion: false, gotState: true, mic: true, cam: false, screen: false)), "eigenes Geraet ohne Bild: still")
    check(!CallRoom.quiet(V(device: "a", own: true, companion: true, gotState: true, mic: false, cam: false, screen: true)), "eigenes Begleitgeraet teilt den Bildschirm: Kachel")
    check(CallRoom.quiet(V(device: "a", own: false, companion: true, gotState: true, mic: false, cam: false, screen: false)), "fremdes Begleitgeraet stumm ohne Bild: still")
    check(!CallRoom.quiet(V(device: "a", own: false, companion: true, gotState: true, mic: true, cam: false, screen: false)), "Begleitgeraet mit Mikrofon: Kachel")
    check(CallRoom.quiet(V(device: "a", own: false, companion: true, gotState: false, mic: true, cam: false, screen: false)), "Begleitgeraet vor dem ersten Zustand: still")
    check(!CallRoom.quiet(V(device: "a", own: false, companion: false, gotState: false, mic: true, cam: false, screen: false)), "andere Person: immer Kachel")
    check(!CallRoom.meshView(kind: "dm", shown: 1), "1:1 mit stillem Begleitgeraet bleibt Einzelansicht")
    check(CallRoom.meshView(kind: "dm", shown: 2) && CallRoom.meshView(kind: "group", shown: 1), "Kachelansicht: Gruppe bzw. zwei sichtbare Verbindungen")
    check(CallRoom.columns(1, wide: false) == 1 && CallRoom.columns(3, wide: false) == 2 && CallRoom.columns(4, wide: true) == 2, "2x2")
    check(CallRoom.columns(5, wide: false) == 2 && CallRoom.columns(5, wide: true) == 3, "iPad/Mac: groessere Flaeche, drei Spalten")
    var sp = CallRoom.Speaker()
    check(sp.update([("a", 0.2, true), ("b", 0.5, true)], now: 0) && sp.device == "b", "lautester spricht")
    check(!sp.update([("a", 0.01, true), ("b", 0.02, true)], now: 1) && sp.device == "b", "kurze Pause: haelt nach")
    check(sp.update([("a", 0.0, true)], now: 2) && sp.device == nil, "nach 1,5 s Stille: niemand")
    check(!sp.update([("a", 0.9, false)], now: 3) && sp.device == nil, "stumm geschaltet zaehlt nicht")
    _ = sp.update([("a", 0.4, true)], now: 4); sp.drop("a")
    check(sp.device == nil, "verlassen: nicht mehr Sprecher")
}

private func roomBarTests() {
    func run(_ kind: String, state: String, mine: [String], legs: [[String: Any]], joinable: Bool = true, block: String? = nil, invite: String? = nil) -> CallRoom.Running {
        var d: [String: Any] = ["id": String(repeating: "a", count: 32), "chat_id": 12, "kind": kind, "media": "video", "state": state, "mine": mine,
                                "legs": legs, "joinable": joinable, "limit": 4, "created": 100.0, "ring_s": 45, "peer": ["name": "Anna"], "role": "callee"]
        if let block { d["join_block"] = block }
        if let invite { d["invite"] = invite }
        return CallRoom.running(d)!
    }
    let dm = run("dm", state: "active", mine: ["5cbb"], legs: [leg("anna", "0e32"), leg("bert", "5cbb", devName: "iPhone")])
    check(CallRoom.bar(dm, here: "a130", current: nil, ringing: false) == .elsewhere(device: "iPhone", peer: "Anna"), "Anruf laeuft auf iPhone (anderes eigenes Geraet)")
    check(CallRoom.bar(dm, here: "5cbb", current: nil, ringing: false) == .none, "dieses Geraet ist dabei: keine Leiste")
    check(CallRoom.bar(dm, here: "a130", current: dm.id, ringing: false) == .none, "eigener laufender Anruf: keine Leiste")
    let ring = run("dm", state: "ringing", mine: ["5cbb"], legs: [leg("bert", "5cbb")])
    check(CallRoom.bar(ring, here: "a130", current: nil, ringing: false) == .none, "1:1 klingelt noch: keine Leiste")
    let g = run("group", state: "active", mine: [], legs: [leg("anna", "0e32"), leg("carl", "c0de")])
    check(CallRoom.bar(g, here: "a130", current: nil, ringing: false) == .group(devices: 2), "Gruppenanruf laeuft · 2 Geraete")
    check(CallRoom.bar(g, here: "a130", current: nil, ringing: true) == .none, "klingelt hier gerade: keine Leiste")
    let full = run("group", state: "active", mine: [], legs: [leg("anna", "0e32")], joinable: false, block: "full")
    check(full.block == .full && !full.joinable && full.limit == 4, "voll: Hinweis statt Beitreten")
    let ended = run("group", state: "ended", mine: [], legs: [])
    check(CallRoom.bar(ended, here: "x", current: nil, ringing: false) == .none, "beendet: keine Leiste")
    check(CallRoom.running(["id": "kurz"]) == nil, "ungueltige Kennung")
}

private func roomGroupTests() {
    let caps = CallV3.myCaps(serverFeatures: ["reneg", "pip_hint", "screen2", "multi", "group"])
    check(caps == ["reneg", "want", "bye", "screen2", "multi"], "2.4-Server: multi melden")
    check(!CallV3.myCaps(serverFeatures: ["multi"]).contains("multi"), "multi nur mit reneg")
    check(!CallV3.myCaps(serverFeatures: ["reneg", "screen2"]).contains("multi"), "E3-Server: kein multi")
    // 2.5 Gjallarhorn: horn nur mit reneg + multi + horn am Server
    check(CallV3.myCaps(serverFeatures: ["reneg", "screen2", "multi", "horn"]).last == "horn", "2.5-Server: horn")
    check(!CallV3.myCaps(serverFeatures: ["reneg", "horn"]).contains("horn"), "horn nur mit multi")
    check(!CallV3.myCaps(serverFeatures: ["reneg", "multi", "horn"], horn: false).contains("horn"), "horn abschaltbar")
    check(!CallV3.myCaps(serverFeatures: ["reneg", "multi"]).contains("horn"), "Server ohne horn")
    let hl = CallRoom.legs([["device": "dA", "who": "a", "hp": "p1", "hpn": ["horn-0.example.org": "p1", "horn-1.example.org": "q7", "x": 3], "avatar": "av"],
                            ["device": "dB", "who": "b", "hp": ""]])
    check(hl[0].hp == "p1" && hl[0].hpn == ["horn-0.example.org": "p1", "horn-1.example.org": "q7"] && hl[0].avatar == "av", "legs: hp, hpn, avatar")
    check(hl[1].hp == nil && hl[1].hpn.isEmpty && hl[1].avatar == nil, "legs ohne Knoten-Kennung")
    check(CallRoom.limit(media: "video", server: 12, horn: true) == 12 && CallRoom.limit(media: "video", server: 12, horn: false) == 4, "Grenze: Knoten vs. Mesh")
    check(CallRoom.limit(media: "audio", server: nil, horn: true) == 50, "Grenze Knoten ohne Angabe")
    check(CallRoom.groupButton(kind: "group", group: true, myCaps: caps, enabled: true), "Gruppenanruf-Knopf")
    check(CallRoom.groupButton(kind: "free", group: true, myCaps: caps, enabled: true) && CallRoom.groupButton(kind: "page", group: true, myCaps: caps, enabled: true), "freie und Seiten-Chats")
    check(!CallRoom.groupButton(kind: "dm", group: true, myCaps: caps, enabled: true), "Direkt-Chat: 1:1")
    check(!CallRoom.groupButton(kind: "group", group: false, myCaps: caps, enabled: true), "Server (bzw. ein beteiligter Server ohne calls3) meldet nein")
    check(!CallRoom.groupButton(kind: "group", group: nil, myCaps: caps, enabled: true), "alter Server ohne chat.call: kein Knopf")
    check(!CallRoom.groupButton(kind: "group", group: true, myCaps: ["reneg", "want", "bye"], enabled: true), "Geraet ohne multi: kein Knopf")
    check(!CallRoom.groupButton(kind: "group", group: true, myCaps: caps, enabled: false), "Anrufe aus: kein Knopf")
    check(CallRoom.ringGroup(invite: "ringing", mine: [], silent: false, busy: false, age: 3, ringS: 45), "Einladung klingelt")
    check(!CallRoom.ringGroup(invite: "ringing", mine: [], silent: true, busy: false, age: 3, ringS: 45), "grosse Gruppe: still, nur die Leiste")
    check(!CallRoom.ringGroup(invite: "ringing", mine: ["5cbb"], silent: false, busy: false, age: 3, ringS: 45), "eigenes Geraet schon dabei")
    check(!CallRoom.ringGroup(invite: "declined", mine: [], silent: false, busy: false, age: 3, ringS: 45), "abgelehnt")
    check(!CallRoom.ringGroup(invite: "ringing", mine: [], silent: false, busy: true, age: 3, ringS: 45), "im Gespraech: nur die Leiste")
    check(!CallRoom.ringGroup(invite: "ringing", mine: [], silent: false, busy: false, age: 46, ringS: 45), "Klingeldauer vorbei")
    let legs = CallRoom.legs([leg("anna", "0e32"), leg("bert", "5cbb")])
    check(CallRoom.stopRinging(legs, me: "bert") && !CallRoom.stopRinging(legs, me: "carl"), "Begleitgeraet klingelt nicht weiter, sobald ein eigenes Geraet beigetreten ist")
    check(CallRoom.capsKey(device: "d", caps: ["a", "b"]) == "d|a,b", "Faehigkeiten einmal je Geraet melden")
    let p = CallLogic.push(["kind": "call.group", "call": String(repeating: "b", count: 32), "chat": 5, "media": "video", "caller": "Anna", "group": true])
    check(p?.group == true && p?.chat == 5 && p?.media == "video", "Push call.group: wie ein Anruf (CallKit)")
    check(CallLogic.push(["kind": "call", "call": String(repeating: "b", count: 32)])?.group == false, "Push call: kein Gruppenanruf")
    let e = CallLogic.Entry(json: Data(#"{"type":"call","call":"x","media":"audio","status":"ended","dur":65,"group":true,"n":3}"#.utf8))
    check(e?.group == true && e?.n == 3, "Chat-Eintrag Gruppenanruf mit Zahl der Personen")
}

private func roomBodyTests() {
    let st = CallV3.stateBody(pc: "0e32:a130", mic: true, cam: false, screen: false, pip: false, want: .large, path: nil, replaces: "0e32")
    let m = CallV3.meta(st)
    check(m.ready == true && m.replaces == "0e32" && m.pc == "0e32:a130", "state.ready + replaces (Uebernahme fertig)")
    check(CallV3.meta(CallV3.stateBody(pc: "a:b", mic: true, cam: false, screen: false, pip: false, want: .large, path: nil)).ready == nil, "normaler Zustand ohne ready")
    let bye = CallV3.meta(CallV3.byeBody(pc: "0e32:5cbb", reason: "handover"))
    check(bye.reason == "handover" && bye.pc == "0e32:5cbb", "bye.reason handover")
    check(CallV3.meta(#"{"t":"bye","reason":"weg"}"#).reason == nil, "unbekannter Grund faellt weg")
    let o = CallV3.offerBody(sdp: "v=0", media: "video", pc: "5cbb:a130", n: 1, caps: ["reneg", "want", "bye", "screen2", "multi"])
    check(CallV3.meta(o).caps?.contains("multi") == true && CallV3.meta(o).n == 1, "Angebot beim Beitreten: n 1, caps mit multi")
}
