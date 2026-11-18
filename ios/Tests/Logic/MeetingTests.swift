import Foundation

// Meeting-Ansicht (MEETING-SPEC): MeetingLogic gegen
// - die GEMEINSAMEN Vektoren ios/Tests/vectors/meeting.json (unveraenderte Kopie von Server tests/meeting_vectors.json,
//   Web tests/meeting_logik.js und Android pruefen dieselben Faelle): meetingTiles + Layout + Buehne/Streifen/Raster,
//   pickLayout, galleryGrid, pages, stripSide, speakerHysteresis, hideControls (selfPipSize: bekannte iOS-Abweichung)
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
        var tiles = M.tiles(devs).tiles
        // Kacheln ohne Video aus (prefs.showNoVideo false): iOS blendet in der Ansicht aus (MeetingViews.tiles, nur Galerie,
        // Sprechring statt 5 s Nachlauf) - hier pruefen, dass die Ausgeblendeten Personenkacheln ohne Video sind
        if let hid = c["hidden"] as? [String] {
            check(hid.allSatisfy { w in tiles.contains { $0.who == w && $0.kind == .person && !$0.video && !$0.me } }, "\(name): ausgeblendet = Personen ohne Video")
            tiles = tiles.filter { !hid.contains($0.who) }
        }
        let want = c["expect"] as? [[String: Any]] ?? []
        let got = tiles.map { [$0.id, kind($0.kind), $0.dev, $0.video ? "1" : "0", $0.devLabel].joined(separator: "/") }
        let exp = want.map { w in [w["id"] as? String ?? "", w["kind"] as? String ?? "", w["dev"] as? String ?? "", (w["video"] as? Bool ?? false) ? "1" : "0",
                                   w["devName"] as? String ?? ""].joined(separator: "/") }
        check(got == exp, "gemeinsam \(name): Kacheln \(got)")
        let ns = tiles.filter { $0.kind != .screen }.count, sc = tiles.filter { $0.kind == .screen }.count
        let mode = M.mode(.auto, tiles: ns, screens: sc)
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
            float = M.floats(mode, people: M.tiles(devs).people, rightStrip: true)
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
    // selfPipSize: iOS richtet das eigene Bild nach dem Seitenverhaeltnis der Kamera aus (CallLogic.selfPipSize, Feldfall
    // Drehen) statt nach der Buehne - bekannte Abweichung, nur gezaehlt (Bericht teilen-ios)
    let pip = j["selfPipSize"] as? [[String: Any]] ?? []
    check(!pip.isEmpty, "gemeinsam selfPipSize vorhanden (\(pip.count), bekannte iOS-Abweichung, nicht geprueft)")
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
