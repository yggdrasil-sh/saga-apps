import Foundation

// Meeting-Ansicht (MEETING-SPEC): MeetingLogic gegen die gemeinsamen Vektoren ios/Tests/vectors/meeting.json
// (meetingTiles S2/S3/S9, speakerHysteresis S5, galleryGrid, pickLayout, stripPlace) plus Einzelfaelle.

func meetingTests() {
    let path = URL(fileURLWithPath: #filePath).deletingLastPathComponent().appendingPathComponent("../vectors/meeting.json").standardized.path
    guard let d = FileManager.default.contents(atPath: path), let j = (try? JSONSerialization.jsonObject(with: d)) as? [String: Any] else {
        check(false, "meeting.json fehlt"); return
    }
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
}
