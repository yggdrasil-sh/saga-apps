import Foundation

// Meeting-Ansicht (MEETING-SPEC): MeetingLogic gegen
// - die GEMEINSAMEN Vektoren ios/Tests/vectors/meeting.json (unveraenderte Kopie von Server tests/meeting_vectors.json,
//   Web tests/meeting_logik.js und Android pruefen dieselben Faelle): meetingTiles + Layout + Buehne/Streifen/Raster,
//   pickLayout, galleryGrid, pages, stripSide, speakerHysteresis, hideControls, selfPipSize - ohne Abweichungen (Bugs-DB 2161)
// - iOS-eigene Zusatzfaelle ios/Tests/vectors/meeting_app.json (ownOthers, selfSharing, Layout-Knopf, eigenes Bild)
// plus Einzelfaelle.

func meetingVectors(_ file: String) -> [String: Any]? {
    let path = URL(fileURLWithPath: #filePath).deletingLastPathComponent().appendingPathComponent("../vectors/" + file).standardized.path
    guard let d = FileManager.default.contents(atPath: path) else { return nil }
    return (try? JSONSerialization.jsonObject(with: d)) as? [String: Any]
}

/// Gemeinsame Vektoren (Format saga-meeting/v1: roster wie GET /api/calls/{id}, media je Geraet, prefs.self = dieses Geraet)
func meetingSharedTests() {
    guard let j = meetingVectors("meeting.json") else { check(false, "meeting.json fehlt"); return }
    check(j["format"] as? String == "saga-meeting/v1", "meeting.json: Format saga-meeting/v1")
    typealias M = Meeting
    func num(_ x: Any?) -> Double { (x as? NSNumber)?.doubleValue ?? 0 }
    func kind(_ k: M.Kind) -> String { k == .camera ? "cam" : k.rawValue }
    func layout(_ m: M.Mode) -> String { m == .duo ? "one" : m.rawValue }
    var n = 0
    for c in j["meetingTiles"] as? [[String: Any]] ?? [] {
        n += 1
        let name = c["name"] as? String ?? "?"
        let media = c["media"] as? [String: [String: Any]] ?? [:]
        let prefs = c["prefs"] as? [String: Any] ?? [:]
        let me = prefs["self"] as? String
        let devs: [M.Device] = (c["roster"] as? [[String: Any]] ?? []).map { r in
            let dev = r["device"] as? String ?? "", m = media[dev] ?? [:]
            return M.Device(device: dev, who: r["who"] as? String ?? dev, name: r["name"] as? String ?? "", devName: r["dev_name"] as? String ?? "",
                            companion: (r["role"] as? String) == "companion", own: r["me"] as? Bool ?? false, here: dev == me,
                            mic: m["mic"] as? Bool == true, cam: m["cam"] as? Bool ?? false, screen: m["screen"] as? Bool ?? false,
                            screenSince: num(m["screenAt"]))
        }
        let all = M.tiles(devs).tiles
        // Kacheln ohne Video aus (prefs.showNoVideo false): wie die Ansicht (CallViews.meetBuilt) in allen Layouts, wer in
        // den letzten 5 s gesprochen hat, bleibt (prefs.spokeAt/now in ms)
        let pnow = num(prefs["now"]), pspoke = prefs["spokeAt"] as? [String: Any] ?? [:]
        let talked = Set(pspoke.filter { pnow - num($0.value) <= M.noVideoTail * 1000 }.map(\.key))
        let tiles = M.visible(all, show: prefs["showNoVideo"] as? Bool != false, talked: talked)
        if let hid = c["hidden"] as? [String] {
            let gone = all.filter { t in !tiles.contains { $0.id == t.id } }.map(\.who)
            check(gone == hid, "\(name): ausgeblendet \(gone)")
        }
        let want = c["expect"] as? [[String: Any]] ?? []
        let got = tiles.map { [$0.id, kind($0.kind), $0.dev, $0.video ? "1" : "0", $0.devLabel].joined(separator: "/") }
        let exp = want.map { w in [w["id"] as? String ?? "", w["kind"] as? String ?? "", w["dev"] as? String ?? "", (w["video"] as? Bool ?? false) ? "1" : "0",
                                   w["devName"] as? String ?? ""].joined(separator: "/") }
        check(got == exp, "gemeinsam \(name): Kacheln \(got)")
        let ns = tiles.filter { $0.kind != .screen }.count, sc = tiles.filter { $0.kind == .screen }.count
        let mode = M.mode(M.Layout(rawValue: c["choice"] as? String ?? "auto") ?? .auto, tiles: ns, screens: sc)
        check(layout(mode) == c["layout"] as? String, "gemeinsam \(name): Layout \(layout(mode))")
        let a = c["arrange"] as? [String: Any] ?? [:]
        let pin = c["pin"] as? String, speaker = c["speaker"] as? String
        var stage: String?, strip: [String] = [], grid: [String] = [], float = false
        if mode == .gallery {
            let now = num(prefs["now"]), spoke = prefs["spokeAt"] as? [String: Any] ?? [:]
            let recent = Set(spoke.filter { now - num($0.value) <= M.Speakers.recentFor * 1000 }.map(\.key))
            grid = M.galleryOrder(tiles, pin: pin, recent: recent).map(\.id)
        } else {
            // meeting.js arrange: eigenes Bild nie ueber dem Bildschirm = iOS mit Streifen rechts
            float = M.floats(mode, people: tiles.filter { $0.kind == .person }.count, rightStrip: true)
            stage = M.stage(mode, tiles: tiles, pin: pin, speaker: speaker)
            strip = M.strip(tiles, stage: stage, selfFloats: float).map(\.id)
        }
        check(stage == a["stage"] as? String && strip == (a["strip"] as? [String] ?? []) && grid == (a["grid"] as? [String] ?? [])
              && float == (a["float"] as? Bool ?? false), "gemeinsam \(name): Buehne \(stage ?? "-") Streifen \(strip) Raster \(grid) schwebt \(float)")
    }
    check(n >= 17, "gemeinsam: alle Kachelfaelle gelesen (\(n))")
    for c in j["pickLayout"] as? [[String: Any]] ?? [] {
        let m = M.mode(M.Layout(rawValue: c["choice"] as? String ?? "") ?? .auto, tiles: c["people"] as? Int ?? 0, screens: c["screens"] as? Int ?? 0,
                       reduceMotion: c["reduced"] as? Bool ?? false)
        check(layout(m) == c["expect"] as? String, "gemeinsam pickLayout \(c["name"] ?? ""): \(layout(m))")
    }
    for c in j["galleryGrid"] as? [[String: Any]] ?? [] {
        let g = M.grid(n: Int(num(c["n"])), width: num(c["w"]), height: num(c["h"]))
        check(g == M.Grid(cols: Int(num(c["cols"])), perPage: Int(num(c["perPage"])), videoMax: Int(num(c["video"]))),
              "gemeinsam Galerie \(c["w"] ?? 0)x\(c["h"] ?? 0) n=\(c["n"] ?? 0): \(g)")
    }
    for c in j["pages"] as? [[String: Any]] ?? [] {
        let k = Int(num(c["n"])), per = Int(num(c["perPage"])), p = Int(num(c["page"]))
        let items = M.page(Array(0..<k), page: p, perPage: per)
        check(M.pages(k, perPage: per) == Int(num(c["pages"])) && items.count == Int(num(c["items"])), "gemeinsam Seiten \(k)/\(per) Seite \(p)")
    }
    for c in j["stripSide"] as? [[String: Any]] ?? [] {
        let p = M.strip(width: num(c["w"]), height: num(c["h"]), presentation: c["layout"] as? String == "presentation")
        check(p.side.rawValue == c["side"] as? String && p.size == num(c["size"]), "gemeinsam Streifen \(c["w"] ?? 0)x\(c["h"] ?? 0) \(c["layout"] ?? ""): \(p)")
    }
    for c in j["speakerHysteresis"] as? [[String: Any]] ?? [] {
        let name = c["name"] as? String ?? "?", step = Int(num(c["step"])), until = Int(num(c["until"]))
        let tones = c["tones"] as? [[String: Any]] ?? []
        var sp = M.Speakers(), at: [Int: String?] = [:], prev: String?, changes = 0
        for t in stride(from: 0, through: until, by: max(1, step)) {
            var lv: [String: Double] = [:]
            for x in tones where Double(t) >= num(x["from"]) && Double(t) < num(x["to"]) {
                let w = x["who"] as? String ?? ""; lv[w] = max(lv[w] ?? 0, num(x["level"]))
            }
            sp.update(lv, me: c["me"] as? String, now: Double(t) / 1000)
            if sp.stage != prev { changes += 1; prev = sp.stage }
            at[t] = sp.stage
        }
        let bad = (c["expect"] as? [[String: Any]] ?? []).filter { e in (at[Int(num(e["t"]))] ?? nil) != e["stage"] as? String }
        check(bad.isEmpty && changes == Int(num(c["changes"])), "gemeinsam Hysterese \(name): Wechsel \(changes), falsch bei \(bad.map { $0["t"] ?? 0 })")
    }
    for (i, c) in (j["hideControls"] as? [[String: Any]] ?? []).enumerated() {
        func b(_ k: String) -> Bool { c[k] as? Bool ?? false }
        let ph = c["phase"] as? String ?? ""
        let h = CallLogic.hideControls(idle: b("idle") ? CallLogic.controlsIdle : 0, phase: ph == "connected" ? "active" : ph, video: b("video"),
                                       voiceOver: b("sr"), menuOpen: b("menu"), notice: b("notice"), pointerOverBar: b("over"))
        check(h == b("expect"), "gemeinsam hideControls #\(i + 1)")
    }
    // selfPipSize (Bugs-DB 2161): Breitenregel nach der Kamera auf allen Plattformen; ar 0 = unbekannt (Lage der Buehne)
    let pip = j["selfPipSize"] as? [[String: Any]] ?? []
    check(pip.count >= 13, "gemeinsam selfPipSize: alle Faelle gelesen (\(pip.count))")
    for c in pip {
        let size = (c["size"] as? String ?? "M").lowercased(), ar = num(c["ar"]), e = c["expect"] as? [NSNumber] ?? []
        let r = CallLogic.selfPipSize(aspect: ar > 0 ? ar : nil, fallback: 0, width: num(c["w"]), height: num(c["h"]),
                                      factor: (M.SelfSize(rawValue: size) ?? .m).factor)
        check(e.count == 2 && r.w == e[0].doubleValue && r.h == e[1].doubleValue, "gemeinsam selfPipSize \(c["w"] ?? 0)x\(c["h"] ?? 0) ar \(ar) \(size): \(r)")
    }
    // speakRing (Bugs-DB 2125): Sprechring nur ab 3 verschiedenen Personen, Begleitgeraete zaehlen nicht
    let ring = j["speakRing"] as? [[String: Any]] ?? []
    check(ring.count >= 12, "gemeinsam speakRing: alle Faelle gelesen (\(ring.count))")
    for c in ring {
        let ros = (c["roster"] as? [[String: Any]] ?? []).map { o in
            M.RingMember(device: o["device"] as? String ?? "", who: o["who"] as? String, companion: (o["role"] as? String) == "companion",
                         me: (o["me"] as? Bool) == true)
        }
        check(M.speakRingOn(ros, self: c["self"] as? String) == (c["expect"] as? Bool), "gemeinsam speakRing: \(c["name"] ?? "?")")
    }
    // selfRing (Bugs-DB 2472): eigener Sprechring - Schwelle, Nachlauf, stumm sofort aus, alter Messwert zaehlt nicht
    let own = j["selfRing"] as? [[String: Any]] ?? []
    check(own.count >= 7, "gemeinsam selfRing: alle Faelle gelesen (\(own.count))")
    for c in own {
        var r = M.SelfRing()
        let steps = c["steps"] as? [[String: Any]] ?? []
        let got = steps.map { s in r.update(now: num(s["t"]), level: num(s["level"]), at: num(s["at"]), mic: (s["mic"] as? Bool) == true) }
        check(!steps.isEmpty && got == steps.map { ($0["expect"] as? Bool) == true }, "gemeinsam selfRing: \(c["name"] ?? "?") \(got)")
    }
    meetingRingFlowTests()
}

/// 2125: Ablauf in der Meeting-Ansicht (Geraete wie meetingDevices): 1:1 aus, + Begleitgeraet aus, dritte kommt an, geht aus
func meetingRingFlowTests() {
    typealias M = Meeting
    let me = M.Device(device: "me-dev", who: "me", name: "Max", own: true, here: true)
    let anna = M.Device(device: "a1", who: "anna", name: "Anna")
    let comp = M.Device(device: "me-2", who: "me", name: "Max", companion: true, own: true)
    let ben = M.Device(device: "b1", who: "ben", name: "Ben")
    let annaComp = M.Device(device: "a2", who: "anna", name: "Anna", companion: true)
    check(!M.speakRingOn([me, anna]), "2125: 1:1 (zwei Personen) - kein Ring")
    check(!M.speakRingOn([me, anna, comp]), "2125: 1:1 + eigenes Begleitgeraet - kein Ring")
    check(!M.speakRingOn([me, anna, annaComp]), "2125: 1:1 + Begleitgeraet der anderen Person - kein Ring")
    check(M.speakRingOn([me, anna, comp, ben]), "2125: dritte Person kommt - Ring")
    check(!M.speakRingOn([me, anna, comp]), "2125: dritte Person geht - kein Ring")
    // dieses Geraet ist selbst das Begleitgeraet: zaehlt trotzdem als die eigene Person
    let hereComp = M.Device(device: "me-2", who: "me", name: "Max", companion: true, own: true, here: true)
    check(M.speakRingOn([hereComp, anna, ben]) && !M.speakRingOn([hereComp, anna]), "2125: auf dem Begleitgeraet: zu dritt Ring, zu zweit keiner")
    meetingRingRosterTests()
}

/// 2459: Mesh - Personen aus der Teilnehmerliste (wie Web ringPeople/Android meetDevs), nicht aus den Verbindungen
func meetingRingRosterTests() {
    typealias M = Meeting
    typealias R = M.RingMember
    let here = R(device: "me-dev", who: "me", me: true)
    let legs3 = [R(device: "me-dev", who: "me", me: true), R(device: "a1", who: "anna"), R(device: "b1", who: "ben")]
    let linkAnna = [R(device: "a1", who: "anna")]
    // Ben ist in der Liste, die Verbindung zu ihm steht noch nicht: Ring schon an (vorher erst mit der Verbindung)
    check(M.speakRingOn(M.ringRoster(legs: legs3, links: linkAnna, here: here), self: "me"), "2459: dritte Person in der Liste, Verbindung noch nicht da - Ring")
    check(!M.speakRingOn(linkAnna + [here], self: "me"), "2459: Gegenprobe alte Zaehlung (nur Verbindungen) - kein Ring")
    check(!M.speakRingOn(M.ringRoster(legs: Array(legs3.prefix(2)), links: linkAnna, here: here), self: "me"), "2459: zu zweit in der Liste - kein Ring")
    // aeltere 1:1 ohne Liste: Gegenueber aus der Verbindung, dieses Geraet zaehlt immer
    let ros = M.ringRoster(legs: [], links: [R(device: "peer", who: "anna")], here: here)
    check(ros.count == 2 && !M.speakRingOn(ros, self: "me"), "2459: ohne Liste (aeltere 1:1) - Verbindung + dieses Geraet, kein Ring")
    // jedes Geraet einmal, die Liste geht vor (Begleitgeraet laut Liste zaehlt nicht, auch wenn die Verbindung es nicht weiss)
    let r2 = M.ringRoster(legs: [here, R(device: "a1", who: "anna"), R(device: "a2", who: "anna", companion: true)],
                          links: [R(device: "a2", who: "anna"), R(device: "a1", who: "anna")], here: here)
    check(r2.count == 3 && r2.filter { $0.device == "a2" }.first?.companion == true && !M.speakRingOn(r2, self: "me"),
          "2459: doppelte Geraete einmal, Liste vor Verbindung (Begleitgeraet zaehlt nicht)")
    // ein Geraet verlaesst die Liste, Verbindung noch offen: zaehlt ueber die Verbindung weiter (wie Web), bis sie weg ist
    check(M.speakRingOn(M.ringRoster(legs: Array(legs3.prefix(2)), links: linkAnna + [R(device: "b1", who: "ben")], here: here), self: "me"),
          "2459: Liste ohne Ben, Verbindung zu Ben noch offen - zaehlt weiter (wie Web ringPeople)")

    // Stumm = sofort kein "spricht" (Nachlauf nur fuer Sprechpausen); Hysterese unberuehrt
    var sp = M.Speakers()
    sp.update(["anna": 0.4, "me": 0.4], me: "me", now: 0); sp.update(["anna": 0.4, "me": 0.4], me: "me", now: 0.5)
    sp.update(["anna": 0, "me": 0], me: "me", now: 0.6)
    check(sp.speaking == ["anna", "me"], "2459: Sprechpause - Nachlauf haelt beide (Speakers unveraendert)")
    let me = M.Device(device: "me-dev", who: "me", name: "Max", own: true, here: true)
    let anna = M.Device(device: "a1", who: "anna", name: "Anna")
    var meMuted = me; meMuted.mic = false
    var annaMuted = anna; annaMuted.mic = false
    check(M.audible(sp.speaking, [me, anna]) == ["anna", "me"], "2459: Mikro an - Nachlauf bleibt sichtbar")
    check(M.audible(sp.speaking, [meMuted, anna]) == ["anna"], "2459: selbst stumm - eigener Ring sofort aus")
    check(M.audible(sp.speaking, [me, annaMuted]) == ["me"], "2459: Anna stumm - ihr Ring sofort aus")
    var annaComp = M.Device(device: "a2", who: "anna", name: "Anna", companion: true); annaComp.mic = true
    check(M.audible(sp.speaking, [me, annaMuted, annaComp]) == ["anna", "me"], "2459: Person mit einem Geraet mit Mikro an - zaehlt (wie Kachel-mic)")
}

func meetingTests() {
    meetingSharedTests()
    guard let j = meetingVectors("meeting_app.json") else { check(false, "meeting_app.json fehlt"); return }
    typealias M = Meeting
    func device(_ o: [String: Any]) -> M.Device {
        M.Device(device: o["device"] as? String ?? "", who: o["who"] as? String ?? "", name: o["name"] as? String ?? "", devName: o["dev_name"] as? String ?? "",
                 companion: (o["role"] as? String) == "companion", own: o["own"] as? Bool ?? false, here: o["here"] as? Bool ?? false,
                 mic: o["mic"] as? Bool ?? true, cam: o["cam"] as? Bool ?? false, screen: o["screen"] as? Bool ?? false,
                 screenSince: (o["screen_since"] as? NSNumber)?.doubleValue ?? 0)
    }
    for c in j["meetingTiles"] as? [[String: Any]] ?? [] {
        let name = c["name"] as? String ?? "?"
        let b = M.tiles((c["devices"] as? [[String: Any]] ?? []).map(device))
        let e = c["expect"] as? [String: Any] ?? [:]
        let want = e["tiles"] as? [[String: Any]] ?? []
        check(b.tiles.map(\.id) == want.map { $0["id"] as? String ?? "" }, "\(name): Kacheln \(b.tiles.map(\.id))")
        for (t, w) in zip(b.tiles, want) {
            check(t.kind.rawValue == w["kind"] as? String && t.dev == w["dev"] as? String && t.video == (w["video"] as? Bool ?? false)
                  && t.me == (w["me"] as? Bool ?? false), "\(name): \(t.id) kind/dev/video/me")
            if let l = w["devLabel"] as? String { check(t.devLabel == l, "\(name): \(t.id) Geraet '\(t.devLabel)'") }
        }
        check(b.people == e["people"] as? Int, "\(name): Personen \(b.people)")
        check(b.ownOthers.map(\.device) == (e["ownOthers"] as? [String] ?? []), "\(name): eigene Begleitgeraete")
        check(b.selfSharing == (e["selfSharing"] as? Bool ?? false), "\(name): eigenes Teilen")
        let n = b.tiles.filter { $0.kind != .screen }.count, s = b.tiles.filter { $0.kind == .screen }.count
        let mode = M.mode(.auto, tiles: n, screens: s)
        check(mode.rawValue == e["mode"] as? String, "\(name): Layout \(mode)")
        check(M.layoutButton(tiles: n, screens: s) == (e["layoutButton"] as? Bool ?? false), "\(name): Layout-Knopf")
        let floats = M.selfFloats(mode, people: b.people)
        check(floats == (e["selfFloats"] as? Bool ?? false), "\(name): eigenes Bild schwebt")
        if let st = e["stage"] as? String { check(M.stage(mode, tiles: b.tiles, pin: nil, speaker: nil) == st, "\(name): Buehne") }
        if let sw = e["strip"] as? [String] {
            let stg = M.stage(mode, tiles: b.tiles, pin: nil, speaker: nil)
            check(M.strip(b.tiles, stage: stg, selfFloats: floats).map(\.id) == sw, "\(name): Streifen")
        }
    }
    for c in j["speakerHysteresis"] as? [[String: Any]] ?? [] {
        let name = c["name"] as? String ?? "?"
        var sp = M.Speakers(), times: [Double] = []
        for t in c["ticks"] as? [[String: Any]] ?? [] {
            let now = (t["t"] as? NSNumber)?.doubleValue ?? 0
            let lv = (t["levels"] as? [String: Any] ?? [:]).mapValues { ($0 as? NSNumber)?.doubleValue ?? 0 }
            if sp.update(lv, me: c["me"] as? String, now: now) { times.append(now) }
        }
        let e = c["expect"] as? [String: Any] ?? [:]
        check(sp.stage == e["stage"] as? String, "\(name): Buehne \(sp.stage ?? "-")")
        let want = (e["switchTimes"] as? [NSNumber] ?? []).map(\.doubleValue)
        check(times.count == want.count && zip(times, want).allSatisfy { abs($0 - $1) < 0.01 }, "\(name): Wechsel \(times)")
    }
    for c in j["galleryGrid"] as? [[String: Any]] ?? [] {
        func v(_ k: String) -> Double { (c[k] as? NSNumber)?.doubleValue ?? 0 }
        let g = M.grid(n: Int(v("n")), width: v("w"), height: v("h"))
        check(g == M.Grid(cols: Int(v("cols")), perPage: Int(v("perPage")), videoMax: Int(v("videoMax"))), "Galerie \(c["w"] ?? 0)x\(c["h"] ?? 0) n=\(c["n"] ?? 0): \(g)")
    }
    for c in j["pickLayout"] as? [[String: Any]] ?? [] {
        let m = M.mode(M.Layout(rawValue: c["pref"] as? String ?? "") ?? .auto, tiles: c["tiles"] as? Int ?? 0, screens: c["screens"] as? Int ?? 0,
                       reduceMotion: c["reduceMotion"] as? Bool ?? false)
        check(m.rawValue == c["mode"] as? String, "Layout \(c["pref"] ?? "") \(c["tiles"] ?? 0)/\(c["screens"] ?? 0): \(m)")
    }
    for c in j["stripPlace"] as? [[String: Any]] ?? [] {
        func v(_ k: String) -> Double { (c[k] as? NSNumber)?.doubleValue ?? 0 }
        let p = M.strip(width: v("w"), height: v("h"), presentation: c["presentation"] as? Bool ?? false)
        check(p.side.rawValue == c["side"] as? String && p.size == v("size"), "Streifen \(c["w"] ?? 0)x\(c["h"] ?? 0): \(p)")
    }

    // Einzelfaelle
    check(M.layout(nil, legacy: "grid") == .gallery && M.layout(nil, legacy: "speaker") == .speaker && M.layout("presentation") == .presentation
          && M.layout("x") == .auto, "Layout aus dem Speicher (saga-horn-view uebernommen)")
    check(M.pages(0, perPage: 6) == 1 && M.pages(6, perPage: 6) == 1 && M.pages(7, perPage: 6) == 2, "Seiten")
    check(M.page(Array(0..<14), page: 2, perPage: 6) == [12, 13] && M.page(Array(0..<14), page: 9, perPage: 6) == [12, 13], "Seite begrenzt")
    func t(_ id: String, video: Bool = false, me: Bool = false, name: String? = nil) -> M.Tile {
        M.Tile(id: id, kind: .person, who: id, dev: id, name: name ?? id.capitalized, me: me, video: video)
    }
    let all = [t("me", me: true), t("dora"), t("ben", video: true), t("cem"), t("anna")]
    check(M.galleryOrder(all, pin: "cem", recent: ["dora"]).map(\.id) == ["cem", "ben", "dora", "anna", "me"], "Galerie: Pin, Video, Sprecher, alphabetisch, eigene zuletzt")
    var ord = M.Order()
    _ = ord.update(all, pin: nil, recent: [])
    check(ord.update(all, pin: nil, recent: ["dora"]).map(\.id) == ["ben", "anna", "cem", "dora", "me"], "Galerie: Sprecherwechsel allein sortiert nicht neu")
    let v = M.videoAllowed([t("a", video: true), t("b", video: true), t("c", video: true)], max: 2, pin: nil, speaker: "c", recent: [])
    check(v == ["c", "a"], "Video-Hoechstzahl: Sprecher zuerst")
    check(M.zoom(9) == 4 && M.zoom(0.2) == 1, "Zoom 1x-4x")
    let p = M.pan((x: 500, y: -500), zoom: 2, size: (w: 400, h: 300))
    check(p.x == 200 && p.y == -150, "Verschieben begrenzt")
    check(M.SelfSize.s.factor == 0.7 && M.SelfSize.l.next == .s, "eigenes Bild S/M/L")
    check(CallLogic.controlsIdle == 4, "Leisten blenden nach 4 s aus")
    check(!CallLogic.hideControls(idle: 10, phase: "active", video: true, voiceOver: false, menuOpen: false, notice: false, pointerOverBar: true),
          "Maus ueber der Leiste: bleibt sichtbar")
    let pin = M.stage(.speaker, tiles: [t("a"), t("b")], pin: "b", speaker: "a")
    check(pin == "b" && M.stage(.speaker, tiles: [t("a"), t("b")], pin: "x", speaker: "a") == "a", "Pin vor Sprecher, alter Pin faellt weg")
    // §4.2 (Bugs-DB 2160): Bildschirm eines anderen eigenen Geraets
    func dv(_ dev: String, _ who: String, own: Bool = false, here: Bool = false, comp: Bool = false, screen: Bool = false, at: Double = 0, missing: Bool = false) -> M.Device {
        M.Device(device: dev, who: who, name: who.capitalized, devName: dev.uppercased(), companion: comp, own: own, here: here, cam: true,
                 screen: screen, screenSince: at, screenMissing: missing)
    }
    let k1 = M.tiles([dv("m", "me", own: true, here: true, screen: true, at: 1), dv("i", "me", own: true, comp: true, screen: true, at: 2), dv("b", "ben")])
    check(k1.tiles.map(\.id) == ["me|i:scr", "ben", "me"] && k1.selfSharing && k1.tiles[0].devLabel == "I" && !k1.tiles[0].me,
          "§4.2: beide eigenen Geraete teilen - nur der Bildschirm des anderen, mit Geraetename")
    let k2 = M.tiles([dv("i", "me", own: true, here: true, comp: true), dv("m", "me", own: true, screen: true, at: 1, missing: true), dv("b", "ben")])
    check(k2.tiles.first?.id == "me|m:scr" && k2.tiles.first?.video == false, "§4.2: ueber den Knoten noch nicht lieferbar - Kachel ohne Bild")
    let k3 = M.tiles([dv("m", "me", own: true, here: true), dv("a", "anna", screen: true, at: 5), dv("i", "me", own: true, comp: true, screen: true, at: 9), dv("b", "ben")])
    check(k3.tiles.map(\.id).prefix(2) == ["anna|a:scr", "me|i:scr"] && M.newestScreen(k3.tiles)?.id == "me|i:scr"
          && M.stage(.presentation, tiles: k3.tiles, pin: nil, speaker: nil) == "me|i:scr"
          && M.strip(k3.tiles, stage: "me|i:scr", selfFloats: false).map(\.id) == ["anna|a:scr", "anna", "ben", "me"],
          "§4.2: eigener Bildschirm neben fremdem - neueste Freigabe auf der Buehne, aelteste vorn im Streifen")
    check(M.floats(.presentation, people: 2, rightStrip: true) == false && M.floats(.presentation, people: 2, rightStrip: false)
          && M.floats(.duo, people: 2, rightStrip: true), "eigenes Bild: Praesentation mit Streifen rechts im Streifen, sonst schwebend")
}
