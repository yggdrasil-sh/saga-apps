import Foundation

// Bildschirm teilen 2.4 (E3, Vertrag "Bildschirm v2"): Betriebsart A/B, tracks/msid, Zuordnung ueber mid/screen_mid,
// Wiederverwendung der Bildschirm-m-Zeile, Bitrate-Werte, Aufnahmegroesse, Uebergabe-Kopf der iOS-Erweiterung
func screenShareTests() {
    screenModeTests()
    screenBodyTests()
    screenClassifyTests()
    screenReuseTests()
    screenBitrateTests()
    screenCaptureTests()
    screenFrameTests()
    screenSocketTests()
    screenMacAccessTests()
}

private func screenModeTests() {
    typealias V = CallV3; typealias S = ScreenShare
    let all = ["reneg", "pip_hint", "screen2"]
    check(V.myCaps(serverFeatures: all) == ["reneg", "want", "bye", "screen2"], "Server mit reneg+screen2: screen2 melden")
    check(V.myCaps(serverFeatures: ["screen2"]) == ["want", "bye"], "screen2 ohne reneg: nicht melden")
    check(V.myCaps(serverFeatures: ["reneg", "pip_hint"]) == ["reneg", "want", "bye"], "E0-Server: kein screen2")
    check(V.myCaps(serverFeatures: all, screen2: false) == ["reneg", "want", "bye"], "Client ohne B: kein screen2")
    let mine = V.myCaps(serverFeatures: all)
    check(S.mode(mine: mine, peer: ["reneg", "want", "bye", "screen2"], established: true) == .track, "beide reneg+screen2, verbunden: B")
    check(S.mode(mine: mine, peer: ["reneg", "want", "bye", "screen2"], established: false) == .replace, "noch nicht ausgehandelt: A")
    check(S.mode(mine: mine, peer: ["reneg", "want", "bye"], established: true) == .replace, "E0-Gegenueber ohne screen2: A")
    check(S.mode(mine: mine, peer: ["screen2"], established: true) == .replace, "Gegenueber screen2 ohne reneg: A")
    check(S.mode(mine: mine, peer: nil, established: true) == .replace, "2.3-Gegenueber (keine caps): A")
    check(S.mode(mine: mine, peer: [], established: true) == .replace, "2.1 (caps fehlen = []): A")
    check(S.mode(mine: ["reneg", "want", "bye"], peer: ["reneg", "screen2"], established: true) == .replace, "eigener Server ohne screen2: A")
}

private func screenBodyTests() {
    typealias V = CallV3; typealias S = ScreenShare
    // Rollen je eigener m-Zeile; die Bildschirm-Zeile bleibt "screen", auch nach dem Ende (dann ohne msid)
    let r = S.roles([.init(mid: "0", role: "mic", stream: "s1"), .init(mid: "1", role: "cam", stream: "s1"), .init(mid: "2", role: "screen", stream: "k9")])
    check(r.tracks == ["0": "mic", "1": "cam", "2": "screen"] && r.msid == ["0": "s1", "1": "s1", "2": "k9"], "tracks/msid wie im Beispiel")
    let off = S.roles([.init(mid: "0", role: "mic", stream: "s1"), .init(mid: "1", role: "cam", stream: "s1"), .init(mid: "2", role: "screen", stream: nil)])
    check(off.tracks["2"] == "screen" && off.msid["2"] == nil, "nach dem Ende: Rolle bleibt, kein msid")
    check(S.roles([.init(mid: nil, role: "mic", stream: "s")]).tracks.isEmpty, "mid noch unbekannt: nicht melden")
    check(S.roles([.init(mid: "a b", role: "mic", stream: "s"), .init(mid: String(repeating: "x", count: 17), role: "cam", stream: "s")]).tracks.isEmpty, "ungueltige mid faellt weg")

    let sdp = "v=0\r\na=fingerprint:sha-256 AA:BB\r\n"
    let caps = V.myCaps(serverFeatures: ["reneg", "screen2"])
    let o = V.meta(V.offerBody(sdp: sdp, media: "video", pc: "p", n: 3, caps: caps, roles: r))
    let okT: Bool = o.tracks == r.tracks; let okM: Bool = o.msid == r.msid
    check(okT && okM && o.n == 3, "Angebot traegt tracks/msid")
    check(V.meta(V.offerBody(sdp: sdp, media: "video", pc: "p", n: 3, caps: ["reneg"])).tracks == nil, "ohne screen2: keine tracks")
    let a = V.meta(V.answerBody(sdp: sdp, media: "video", pc: "p", n: 3, caps: caps, roles: off))
    check(a.tracks?["2"] == "screen", "Antwort traegt tracks")

    // state B: screen_mid = Bildschirm-Zeile, screen_audio false (kein Systemton)
    let st = V.stateBody(pc: "p", mic: true, cam: true, screen: true, pip: false, want: .large, path: .direct, screenMid: "2", screenAudio: false, roles: r)
    let m = V.meta(st)
    check(m.screenMid == "2" && m.screenAudio == false && m.tracks == r.tracks, "state B: screen_mid, screen_audio, tracks")
    if case .state(let mi, let c, let sc)? = CallLogic.parse(st) { check(mi && c && sc, "2.3-Leser sieht mic/cam/screen") } else { check(false, "state lesbar") }
    // state Ende: kein screen_mid
    let end = V.meta(V.stateBody(pc: "p", mic: true, cam: true, screen: false, pip: false, want: .large, path: nil, screenMid: "2", roles: off))
    check(end.screenMid == nil && end.screenAudio == nil && end.tracks?["2"] == "screen", "Ende: ohne screen_mid, Rolle bleibt")
    // state A: screen_mid = Kamera-Zeile
    check(S.screenMid(sharing: true, mode: .replace, screenLine: "2", camLine: "1") == "1", "A: screen_mid = Kamera-mid")
    check(S.screenMid(sharing: true, mode: .track, screenLine: "2", camLine: "1") == "2", "B: screen_mid = Bildschirm-mid")
    check(S.screenMid(sharing: false, mode: .track, screenLine: "2", camLine: "1") == nil, "nicht teilen: kein screen_mid")
    // Eingang robust: Unbekanntes ignorieren, hoechstens 8
    var big: [String: Any] = ["x y": "cam", "3": "audio", "4": 5]
    for i in 0..<12 { big["m\(i)"] = "cam" }
    let body = CallLogic.json(["t": "state", "mic": true, "cam": true, "screen": true, "screen_mid": "bad mid!", "tracks": big])
    let bm = V.meta(body)
    check(bm.tracks?.count == 8 && bm.tracks?["3"] == nil && bm.tracks?["x y"] == nil, "tracks: max. 8, unbekannte Rolle/ungueltige mid weg")
    check(bm.screenMid == nil, "ungueltige screen_mid: ignoriert")
    check(V.meta(CallLogic.json(["t": "state", "tracks": "kaputt"])).tracks == nil, "tracks kein Objekt: ignoriert")
    check(V.validMid("0") && V.validMid("audio_1-a") && !V.validMid("") && !V.validMid("ä") && !V.validMid("a/b"), "mid-Muster [A-Za-z0-9_-]{1,16}")
}

private func screenClassifyTests() {
    typealias S = ScreenShare
    let roles = ["0": "mic", "1": "cam", "2": "screen"]
    // B: Bildschirm auf mid 2, Kamera auf mid 1
    var v = S.classify(videoMids: ["1", "2"], receiving: ["1", "2"], peerTracks: roles, screen: true, screenMid: "2")
    check(v == .init(cam: "1", screen: "2"), "B: Bildschirm per screen_mid, Kamera per Rolle cam")
    check(S.layout(v, camOn: true, swap: false) == ("2", "1"), "Bildschirm gross, Kamera klein")
    check(S.layout(v, camOn: true, swap: true) == ("1", "2"), "Tauschen: Kamera gross")
    check(S.layout(v, camOn: false, swap: true) == ("2", nil), "Kamera aus: nur Bildschirm, Tauschen wirkungslos")
    // kurz nach dem Angebot: Bildschirm-Zeile sendet noch nicht -> Kamera gross
    v = S.classify(videoMids: ["1", "2"], receiving: ["1"], peerTracks: roles, screen: true, screenMid: "2")
    check(v == .init(cam: "1", screen: nil) && S.layout(v, camOn: true, swap: false) == ("1", nil), "screen-mid noch ohne Bilder: nicht gross")
    // A mit screen_mid (2.4 ohne screen2 beim Gegenueber): Bildschirm auf der Kamera-Zeile
    v = S.classify(videoMids: ["1"], receiving: ["1"], peerTracks: ["0": "mic", "1": "cam"], screen: true, screenMid: "1")
    check(v == .init(cam: nil, screen: "1"), "A: screen_mid = Kamera-Zeile -> Bildschirm, keine Kamera")
    // 2.1/2.3/E0: screen ohne screen_mid -> die einzige Videospur ist der Bildschirm
    v = S.classify(videoMids: ["1"], receiving: ["1"], peerTracks: [:], screen: true, screenMid: nil)
    check(v == .init(cam: nil, screen: "1"), "2.3-Gegenueber: einzige Videospur = Bildschirm")
    // ohne tracks: erste Video-m-Zeile ist die Kamera
    v = S.classify(videoMids: ["v0", "v1"], receiving: ["v0", "v1"], peerTracks: [:], screen: false, screenMid: nil)
    check(v == .init(cam: "v0", screen: nil), "ohne tracks: erste Video-Zeile = Kamera")
    // Gegenueber hat die Bildschirm-Zeile zuerst angelegt (Reihenfolge egal - die Rolle zaehlt)
    v = S.classify(videoMids: ["1", "2"], receiving: ["1", "2"], peerTracks: ["1": "cam", "2": "screen"], screen: false, screenMid: nil)
    check(v == .init(cam: "1", screen: nil), "nicht teilen: nur Kamera")
    // mid ist auf beiden Seiten dieselbe; Zuordnung unabhaengig von msid
    v = S.classify(videoMids: ["2", "1"], receiving: ["1", "2"], peerTracks: roles, screen: true, screenMid: "2")
    check(v == .init(cam: "1", screen: "2"), "Rolle cam statt Reihenfolge")
    check(S.swapAfter(state: false, isScreen: true, swap: true) == false, "neues Teilen: Bildschirm wieder gross")
    check(S.swapAfter(state: true, isScreen: true, swap: true) == true, "laufendes Teilen: Tausch bleibt")
}

private func screenReuseTests() {
    typealias S = ScreenShare
    let cam = S.VideoLine(mid: "1", isCam: true)
    check(S.reuseMid(own: nil, lines: [cam], peerTracks: ["1": "cam"]) == nil, "erstes Teilen: neue m-Zeile")
    check(S.reuseMid(own: "2", lines: [cam, .init(mid: "2", isCam: false)], peerTracks: [:]) == "2", "eigene Bildschirm-Zeile wiederverwenden")
    check(S.reuseMid(own: nil, lines: [cam, .init(mid: "2", isCam: false)], peerTracks: ["1": "cam", "2": "screen"]) == "2",
          "vom Gegenueber angelegte Bildschirm-Zeile wiederverwenden")
    check(S.reuseMid(own: nil, lines: [cam, .init(mid: "2", isCam: false)], peerTracks: ["2": "cam"]) == nil, "fremde Zeile ohne Rolle screen: nicht")
    check(S.reuseMid(own: "1", lines: [cam], peerTracks: [:]) == nil, "nie die Kamera-Zeile")
    check(S.reuseMid(own: nil, lines: [cam, .init(mid: nil, isCam: false)], peerTracks: ["2": "screen"]) == nil, "ohne mid: nicht")
}

private func screenBitrateTests() {
    typealias V = CallV3
    // Bildschirm-Sender je Weg: 2,5/1,8/1,2 Mbit/s, 15/15/8 fps, nie skaliert, immer maintain-resolution, want aendert nichts
    for (p, br, f) in [(V.Path.direct, 2_500_000, 15), (.relayUDP, 1_800_000, 15), (.relayTCP, 1_200_000, 8)] {
        for w in [V.Want.large, .small, .off] {
            let l = V.videoLimits(path: p, kind: .screen, streams: 1, want: w, sourceHeight: 1080)
            check(l == V.Limits(maxBitrate: br, scaleDown: 1, maxFramerate: f, maintainResolution: true, active: true), "Bildschirm \(p.rawValue) want=\(w.rawValue)")
        }
    }
    // Kamera daneben (B): <= 300 kbit/s, <= 360p, ueber Relais TCP aus
    var c = V.videoLimits(path: .direct, kind: .camera, streams: 1, want: .large, sourceHeight: 720, sharing: true)
    check(c.maxBitrate == 300_000 && abs(c.scaleDown - 2) < 0.0001 && c.active, "B direkt: Kamera 300 kbit/s, 360p")
    c = V.videoLimits(path: .relayUDP, kind: .camera, streams: 1, want: .large, sourceHeight: 360, sharing: true)
    check(c.maxBitrate == 300_000 && c.scaleDown == 1 && c.active, "B Relais UDP: Testbild 360p unskaliert")
    c = V.videoLimits(path: .relayTCP, kind: .camera, streams: 1, want: .large, sourceHeight: 720, sharing: true)
    check(!c.active && c.maxBitrate == 300_000, "B Relais TCP: Kamera aus")
    c = V.videoLimits(path: .direct, kind: .camera, streams: 1, want: .small, sourceHeight: 720, sharing: true)
    check(c.maxBitrate == 300_000 && abs(c.scaleDown - 3) < 0.0001, "B + PiP beim Gegenueber: kleiner bleibt")
    c = V.videoLimits(path: .direct, kind: .camera, streams: 1, want: .large, sourceHeight: nil, sharing: true)
    check(abs(c.scaleDown - 480.0 / 360) < 0.0001, "B unbekannte Hoehe: 480 angenommen")
    // Summe ueber TCP je Richtung <= 1,6 Mbit/s (Bildschirm + Kamera aus + Ton)
    let sum = V.videoLimits(path: .relayTCP, kind: .screen, streams: 1, want: .large, sourceHeight: nil).maxBitrate + V.audioBitrate(path: .relayTCP)
    check(sum <= 1_600_000, "TCP: Summe <= 1,6 Mbit/s")
    // nach dem Ende: normale Kamera-Regeln
    check(V.videoLimits(path: .direct, kind: .camera, streams: 1, want: .large, sourceHeight: 720) == V.Limits(maxBitrate: 1_500_000, scaleDown: 1, maxFramerate: 30),
          "ohne Teilen: Kamera wie vorher")
    // A: Kamera-Sender bekommt die Bildschirm-Regeln (Aufrufer waehlt kind .screen)
    check(V.videoLimits(path: .relayTCP, kind: .screen, streams: 1, want: .small, sourceHeight: 720).maxFramerate == 8, "A ueber TCP: 8 fps")
}

private func screenCaptureTests() {
    typealias S = ScreenShare
    check(S.captureSize(width: 1512, height: 982, scale: 2) == (1920, 1246), "Retina 3024x1964 -> lange Kante 1920, gerade")
    check(S.captureSize(width: 1280, height: 720, scale: 1) == (1280, 720), "kleiner als 1080p: unveraendert")
    check(S.captureSize(width: 800, height: 1201, scale: 2) == (1278, 1920), "Hochformat: lange Kante 1920")
    let odd = S.captureSize(width: 333, height: 201, scale: 1)
    check(odd.width % 2 == 0 && odd.height % 2 == 0, "NV12: gerade Werte")
    check(S.captureFps(path: .direct) == 15 && S.captureFps(path: .relayUDP) == 15 && S.captureFps(path: .relayTCP) == 8 && S.captureFps(path: nil) == 15,
          "Aufnahme 15 fps, ueber TCP 8")
    let d = UserDefaults(suiteName: "screen-hint-test")!
    d.removePersistentDomain(forName: "screen-hint-test")
    check(S.showHint(d), "erstes Teilen: Hinweis")
    d.set(true, forKey: S.hintKey)
    check(!S.showHint(d), "Nicht mehr anzeigen: kein Hinweis")
    d.removePersistentDomain(forName: "screen-hint-test")
}

private func screenFrameTests() {
    typealias F = ScreenFrame
    let h = F.Header(width: 1920, height: 886, rotation: 90, ptsNs: 123_456_789_012)
    let b = F.encode(h)
    check(b.count == F.headerSize && F.decode(b) == h, "Kopf hin und zurueck")
    var bad = b; bad[0] = 0
    check(F.decode(bad) == nil, "falsche Kennung: trennen")
    check(F.decode(F.encode(.init(width: 1921, height: 886, rotation: 0, ptsNs: 0))) == nil, "ungerade Breite: trennen")
    check(F.decode(F.encode(.init(width: 8192, height: 886, rotation: 0, ptsNs: 0))) == nil, "zu gross: trennen")
    check(F.decode(F.encode(.init(width: 640, height: 360, rotation: 45, ptsNs: 0))) == nil, "unbekannte Drehung: trennen")
    var len = F.encode(.init(width: 640, height: 360, rotation: 0, ptsNs: 0)); len[24] ^= 1
    check(F.decode(len) == nil, "Laenge passt nicht: trennen")
    check(F.payloadSize(width: 1920, height: 1080) == 3_110_400, "NV12 1080p = 3,1 MB je Bild")
    check(F.scaled(width: 1290, height: 2796, maxLong: 1920) == (884, 1920), "iPhone Pro Max -> 1920 lange Kante")
    check(F.scaled(width: 2048, height: 2732, maxLong: 1280) == (958, 1280), "iPad heiss -> 1280")
    check(F.scaled(width: 750, height: 1334, maxLong: 1920) == (750, 1334), "kleiner: nie vergroessern")
    check(F.due(lastNs: nil, nowNs: 5, fps: 10), "erstes Bild sofort")
    check(!F.due(lastNs: 1_000_000_000, nowNs: 1_050_000_000, fps: 10), "nach 50 ms: verwerfen (10 fps)")
    check(F.due(lastNs: 1_000_000_000, nowNs: 1_095_000_000, fps: 10), "nach 95 ms: senden (Toleranz)")
    check(!F.due(lastNs: 1_000_000_000, nowNs: 1_150_000_000, fps: 5), "heiss 5 fps: nach 150 ms verwerfen")
    check(F.rotation(orientation: 1) == 0 && F.rotation(orientation: 3) == 180 && F.rotation(orientation: 8) == 90 && F.rotation(orientation: 6) == 270,
          "ReplayKit-Ausrichtung -> Drehung")
    let long = URL(fileURLWithPath: "/" + String(repeating: "a", count: 100))
    check(F.socketPath(container: long) == nil, "Socket-Pfad zu lang: nicht verwenden")
    check(F.socketPath(container: URL(fileURLWithPath: "/private/var/mobile/Containers/Shared/AppGroup/0E4C2B7F-2D1A-4C8B-9B1E-3C2A1F0D9E8B")) != nil,
          "Pfad im App-Gruppen-Container passt")
    // Speicher der Erweiterung: hoechstens zwei NV12-Puffer 1080p unterwegs
    check(2 * F.payloadSize(width: 1920, height: 1080) < 8_000_000, "Puffer der Erweiterung < 8 MB")
    // Fehler 26.10. (Knoten): bei stehendem Bildschirm das letzte Bild wiederholen, damit ein angefordertes
    // Schluesselbild kodiert wird (libwebrtc kodiert es erst mit dem naechsten Eingangsbild)
    let s1: UInt64 = 10_000_000_000
    check(!F.repeatDue(lastNs: 0, nowNs: s1, ms: F.repeatMs), "noch kein Bild: nichts wiederholen")
    check(!F.repeatDue(lastNs: s1, nowNs: s1 + 100_000_000, ms: F.repeatMs), "Bild vor 100 ms: nicht wiederholen")
    check(!F.repeatDue(lastNs: s1, nowNs: s1 + 499_000_000, ms: F.repeatMs), "knapp unter 500 ms: nicht wiederholen")
    check(F.repeatDue(lastNs: s1, nowNs: s1 + 500_000_000, ms: F.repeatMs), "500 ms ohne Bild: wiederholen")
    check(!F.repeatDue(lastNs: s1, nowNs: s1 - 1, ms: F.repeatMs), "Uhr rueckwaerts: nicht wiederholen")
    check(F.repeatMs <= 1000 && F.repeatMs >= 1000 / F.fps, "Wiederholung hoechstens 1 s, nie schneller als die Bildrate")
    // Wiederholen bremst nichts aus: ein Bild alle 500 ms ist weit unter der Rate der Erweiterung
    check(1000 / F.repeatMs < F.hotFps, "Wiederholung (2 fps) unter der Bildrate auch heiss (5 fps)")
    check(F.nextTimestamp(now: 5_000_000, last: 0) == 5_000_000, "Zeitstempel = jetzt")
    check(F.nextTimestamp(now: 5_000_000, last: 5_000_000) == 6_000_000, "gleicher Zeitpunkt: 1 ms spaeter")
    check(F.nextTimestamp(now: 4_000_000, last: 5_000_000) == 6_000_000, "nie rueckwaerts (zwei Threads)")
}

// 2.5-Fix: angenommener Socket muss blockierend lesen (Darwin erbt O_NONBLOCK vom lauschenden Socket)
private func screenSocketTests() {
    let path = "/tmp/sgf-\(getpid()).sock"
    defer { unlink(path) }
    check(ScreenSocket.address(String(repeating: "x", count: 104)) == nil, "Socket-Pfad zu lang: nil statt abschneiden")
    check(ScreenSocket.address(String(repeating: "x", count: 103)) != nil, "Socket-Pfad 103 Byte passt")
    guard let l = ScreenSocket.listen(path: path) else { check(false, "listen"); return }
    defer { close(l) }
    check(!ScreenSocket.isBlocking(l), "lauschender Socket nicht blockierend (DispatchSource)")
    // Ursache: rohes accept() erbt O_NONBLOCK -> read() sofort EAGAIN
    if let c0 = ScreenSocket.connect(path: path) {
        var r = Darwin.accept(l, nil, nil)
        for _ in 0..<50 where r < 0 { usleep(10_000); r = Darwin.accept(l, nil, nil) }
        check(r >= 0 && !ScreenSocket.isBlocking(r), "Darwin: rohes accept erbt O_NONBLOCK (Ursache)")
        var b = [UInt8](repeating: 0, count: 4)
        let n = r >= 0 ? b.withUnsafeMutableBytes { Darwin.read(r, $0.baseAddress!, 4) } : 0
        check(n < 0 && errno == EAGAIN, "rohes accept: read ohne Daten -> EAGAIN (alte App trennte hier)")
        if r >= 0 { close(r) }; close(c0)
    } else { check(false, "connect") }
    // Fix: ScreenSocket.accept liefert blockierend; Kopf kommt verzoegert und wird vollstaendig gelesen
    guard let c = ScreenSocket.connect(path: path) else { check(false, "connect 2"); return }
    var a: Int32? = ScreenSocket.accept(l)
    for _ in 0..<50 where a == nil { usleep(10_000); a = ScreenSocket.accept(l) }
    guard let a else { check(false, "accept"); close(c); return }
    check(ScreenSocket.isBlocking(a), "ScreenSocket.accept: blockierend")
    let head = ScreenFrame.encode(.init(width: 1280, height: 720, rotation: 90, ptsNs: 42))
    let t = Thread {
        usleep(150_000)                                                     // Erweiterung schickt das erste Bild spaeter
        _ = head.withUnsafeBytes { ScreenSocket.writeAll(c, $0.baseAddress!, 16) }
        usleep(50_000)
        _ = head.withUnsafeBytes { ScreenSocket.writeAll(c, $0.baseAddress! + 16, 16) }
    }
    t.start()
    var got = [UInt8](repeating: 0, count: ScreenFrame.headerSize)
    let ok = got.withUnsafeMutableBytes { ScreenSocket.readAll(a, $0.baseAddress!, $0.count) }
    check(ok && ScreenFrame.decode(got) == .init(width: 1280, height: 720, rotation: 90, ptsNs: 42), "verzoegerter Kopf in zwei Teilen vollstaendig gelesen")
    close(c)
    var one = [UInt8](repeating: 0, count: 1)
    check(!one.withUnsafeMutableBytes { ScreenSocket.readAll(a, $0.baseAddress!, 1) }, "Erweiterung trennt: readAll false")
    close(a)
}

// 2.5-Fix Mac: eigener Filter bzw. setConfiguration(for:) nur mit dauerhafter Freigabe
private func screenMacAccessTests() {
    typealias S = ScreenShare
    check(!S.excludeSelf(display: true, hasAccess: false), "ohne Freigabe: Filter der Auswahl (keine Ablehnung -3801, kein Neustart)")
    check(S.excludeSelf(display: true, hasAccess: true), "mit Freigabe: ganzer Bildschirm ohne Saga")
    check(!S.excludeSelf(display: false, hasAccess: true), "Fenster/App: wie gewaehlt")
    check(!S.pickerConfigPerStream(hasAccess: false), "ohne Freigabe kein setConfiguration(for:)")
    check(S.pickerConfigPerStream(hasAccess: true), "mit Freigabe setConfiguration(for:)")
}
