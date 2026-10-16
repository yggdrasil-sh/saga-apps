import Foundation

// 2.1-b: Anmeldung & Sicherheit (Status), Profilbilder (Adresse, Stufe, Zuschnitt), Anrufe (Signale, Fingerabdruecke,
// Gruende, Kennungen, Chat-Eintraege, VoIP-Push)
func v21bTests() {
    // --- Anmeldung & Sicherheit
    let sec = SecurityStatus(data: Data(#"{"supported":true,"enabled":true,"required":true,"passkeys":[{"id":1},{"id":2}],"totp":true,"recovery_left":2}"#.utf8))
    check(sec?.passkeys == 2 && sec?.totp == true && sec?.fewRecoveryCodes == true && sec?.mustSetUp == false, "Sicherheit: Status gelesen")
    check(SecurityStatus(data: Data(#"{"enabled":false,"required":true,"passkeys":[],"recovery_left":0}"#.utf8))?.mustSetUp == true, "Admin verlangt Faktor")
    check(SecurityStatus(data: Data(#"{"enabled":false,"recovery_left":0}"#.utf8))?.fewRecoveryCodes == false, "ohne Faktor keine Code-Warnung")
    check(SecurityStatus(data: Data(#"{"enabled":true,"recovery_left":4}"#.utf8))?.fewRecoveryCodes == false, "4 Codes: keine Warnung")
    check(SecurityStatus(data: Data(#"{"supported":false}"#.utf8))?.supported == false, "Server ohne 2FA")

    // --- Profilbilder
    typealias A = AvatarLogic
    check(A.step(points: 40, scale: 3) == 128 && A.step(points: 20, scale: 2) == 64 && A.step(points: 72, scale: 3) == 256 && A.step(points: 200, scale: 3) == 256, "Avatar-Stufen")
    check(A.url(server: "https://s.example", avatar: "/api/avatar/abc?v=3", px: 128)?.absoluteString == "https://s.example/api/avatar/abc?v=3&s=128", "Avatar-Adresse mit v")
    check(A.url(server: "https://s.example/", avatar: "/api/avatar/abc", px: 64)?.absoluteString == "https://s.example/api/avatar/abc?s=64", "Avatar-Adresse ohne v")
    check(A.url(server: "https://s.example", avatar: "https://gravatar.com/avatar/x", px: 64) == nil, "fremder Host nie")
    check(A.url(server: "https://s.example", avatar: "//evil.example/api/avatar/x", px: 64) == nil, "protokoll-relative Adresse nie")
    check(A.url(server: "https://s.example", avatar: nil, px: 64) == nil && A.url(server: "https://s.example", avatar: "/files/x.png", px: 64) == nil, "nur /api/avatar/")
    check(A.source("upload") == .upload && A.source("gravatar") == .gravatar && A.source(nil) == .none, "Bildquelle")
    // Zuschnitt: Querformat 2000x1000 im Quadrat 300 -> ohne Zoom mittig 1000x1000
    let c0 = A.cropRect(image: 2000, 1000, side: 300, zoom: 1, offset: (0, 0))
    check(abs(c0.x - 500) < 0.01 && abs(c0.y) < 0.01 && abs(c0.size - 1000) < 0.01, "Zuschnitt mittig")
    // ganz nach rechts geschoben (Bild nach rechts = linker Rand sichtbar)
    let c1 = A.cropRect(image: 2000, 1000, side: 300, zoom: 1, offset: (10_000, 0))
    check(abs(c1.x) < 0.01 && abs(c1.size - 1000) < 0.01, "Zuschnitt linker Rand (Verschiebung begrenzt)")
    let c2 = A.cropRect(image: 1000, 1000, side: 300, zoom: 2, offset: (0, 0))
    check(abs(c2.size - 500) < 0.01 && abs(c2.x - 250) < 0.01, "Zoom 2: halbe Kante, mittig")
    let cl = A.clamp(offset: (500, -500), image: 1000, 1000, side: 300, zoom: 2)
    check(abs(cl.x - 150) < 0.01 && abs(cl.y + 150) < 0.01, "Verschiebung begrenzt")
    check(A.outputSide(300) == 512 && A.outputSide(800) == 800 && A.outputSide(4000) == 1024, "Ausgabegroesse 512-1024")

    // --- Anrufe
    typealias L = CallLogic
    let sdp = "v=0\r\na=fingerprint:sha-256 ab:cd:EF\r\nm=audio 9 UDP\r\na=fingerprint:SHA-256 AB:CD:EF \r\na=fingerprint:sha-1 01:02\r\na=fingerprint:sha-256 zz:yy\r\n"
    check(L.sdpFingerprints(sdp) == ["sha-1 01:02", "sha-256 AB:CD:EF"], "Fingerabdruecke: klein/gross, ohne Doppelte, sortiert, nur Hex")
    check(L.sdpFingerprints("v=0\na=fingerprint:sha-256 AB:CD\n") == ["sha-256 AB:CD"], "Fingerabdruck mit LF")
    check(L.fingerprintsMatch(sdp: sdp, fp: ["sha-256 AB:CD:EF", "sha-1 01:02"]) && !L.fingerprintsMatch(sdp: sdp, fp: ["sha-256 AB:CD:EF"]), "fp muss genau passen")
    check(!L.fingerprintsMatch(sdp: "v=0\r\n", fp: []), "ohne Fingerabdruck kein Anruf")
    check(L.certFingerprint(algorithm: "SHA-256", fingerprint: "ab:cd") == "sha-256 AB:CD", "Zertifikat-Vergleichsform")
    check(L.jsonString("a\"b\\c\r\n\t\u{01}ä") == "\"a\\\"b\\\\c\\r\\n\\t\\u0001ä\"", "JSON-Text wie JSON.stringify")
    if case .offer(let s, let m, let fp)? = L.parse(L.offerBody(sdp: sdp, media: "video")) {
        check(s == sdp && m == "video" && fp == L.sdpFingerprints(sdp), "Angebot: Rundreise")
    } else { check(false, "Angebot lesbar") }
    if case .answer(let s, let fp)? = L.parse(L.answerBody(sdp: sdp)) { check(s == sdp && fp.count == 2, "Antwort: Rundreise") } else { check(false, "Antwort lesbar") }
    let cands = (0..<50).map { L.Candidate(candidate: "candidate:\($0) 1 udp 1 10.0.0.1 9 typ host", sdpMid: "0", sdpMLineIndex: 0) }
    if case .ice(let c)? = L.parse(L.iceBody(cands)) { check(c.count == 40 && c[3] == cands[3], "ICE: hoechstens 40 je Signal") } else { check(false, "ICE lesbar") }
    check(L.parse(#"{"t":"ice","c":[{"candidate":""},{"sdpMid":"0"}]}"#) == .ice([]), "leere Kandidaten ignoriert")
    check(L.parse(L.stateBody(mic: false, cam: true, screen: false)) == .state(mic: false, cam: true, screen: false), "Zustand")
    check(L.parse(#"{"t":"state"}"#) == .state(mic: true, cam: false, screen: false), "Zustand: Vorgaben wie im Web")
    check(L.parse("kein json") == nil && L.parse(#"{"t":"neu"}"#) == .other("neu"), "unbekannte Signale")
    check(L.endNote(reason: "declined", role: "caller", connectedFor: nil) == .declined && L.endNote(reason: "busy", role: "caller", connectedFor: nil) == .busy, "Ende: abgelehnt/besetzt")
    check(L.endNote(reason: "timeout", role: "caller", connectedFor: nil) == .missed && L.endNote(reason: "cancelled", role: "callee", connectedFor: nil) == .cancelled, "Ende: keine Antwort/abgebrochen")
    check(L.endNote(reason: "timeout", role: "callee", connectedFor: nil) == .unanswered, "Ende: angerufen, nicht angenommen")
    check(L.endNote(reason: "hangup", role: "callee", connectedFor: 75) == .ended(dur: 75) && L.endNote(reason: "gone", role: "caller", connectedFor: 3) == .gone, "Ende: aufgelegt/Geraet entfernt")
    check(L.duration(65) == "1:05" && L.duration(3725) == "1:02:05" && L.duration(-3) == "0:00", "Dauer")
    let id = "c0ffee00c0ffee00c0ffee00c0ffee00"
    check(L.uuid(id).map(L.callID) == id && L.uuid("xyz") == nil && L.uuid(String(repeating: "g", count: 32)) == nil, "Kennung <-> UUID")
    check(L.isCallID(id) && !L.isCallID(id.uppercased()) && !L.isCallID(nil), "Kennung pruefen")
    let e = L.Entry(json: Data(#"{"v":1,"type":"call","call":"c0","media":"video","status":"ended","dur":125,"from":"anna","to":"bert"}"#.utf8))
    check(e?.kind() == .ended("2:05") && e?.outgoing(me: "anna") == true && e?.outgoing(me: "bert") == false && e?.media == "video", "Chat-Eintrag Anruf")
    check(L.Entry(json: Data(#"{"type":"call","status":"missed","from":"a","to":"b"}"#.utf8))?.kind() == .missed, "Chat-Eintrag verpasst")
    check(L.Entry(json: Data(#"{"type":"text"}"#.utf8)) == nil, "kein Anruf-Eintrag")
    let p = L.push(["kind": "call", "call": id, "chat": 7, "media": "video", "caller": "Anna"])
    check(p == L.Push(call: id, chat: 7, media: "video", caller: "Anna"), "VoIP-Push gelesen")
    check(L.push(["kind": "call", "call": id, "chat": "8", "caller": ""])?.caller == nil && L.push(["kind": "call", "call": id, "chat": "8"])?.chat == 8, "VoIP-Push ohne Namen")
    check(L.push(["kind": "chat.message", "call": id]) == nil && L.push(["kind": "call", "call": "x"]) == nil, "kein Anruf-Push")
    check(L.callFromURL("/chats#call-" + id) == id && L.callFromURL("/chats#c12") == nil, "Anruf aus Mitteilungs-Adresse")
    var seen = Set<Int>()
    check(L.fresh(3, seen: &seen) && !L.fresh(3, seen: &seen) && L.fresh(4, seen: &seen), "Signale nach seq entdoppelt")
}

func v21bReauthTests() {
    check(ReauthLogic.body("123 456", methods: ["password", "totp"]) == ["code": "123456"], "Reauth: Code")
    check(ReauthLogic.body("123456", methods: ["password"]) == ["password": "123456"], "Reauth: ohne TOTP immer Passwort")
    check(ReauthLogic.body("geheim1", methods: ["password", "totp"]) == ["password": "geheim1"], "Reauth: Passwort")
    check(ReauthLogic.nativeInput(methods: ["passkey"]) == false && ReauthLogic.nativeInput(methods: ["password", "passkey"]) && ReauthLogic.nativeInput(methods: []), "Reauth: nur Passkey -> Browser")
}

func v21bPushTests() {
    // VoIP-Abo (Anrufe): eigenes Abo "apns-voip", in der Geraeteliste als "Saga-App: Anrufe", Doppelte je Art
    check(PushLogic.subscription(token: "ab", env: "sandbox", name: "iPhone", type: "apns-voip")["type"] as? String == "apns-voip", "VoIP-Abo: Art")
    check(PushLogic.service(type: "apns-voip", whereText: "Apple VoIP (sandbox)") == .sagaCalls(test: true), "VoIP-Abo: Bezeichnung")
    let devs = [PushDevice(["id": 1, "type": "apns", "this_device": true])!, PushDevice(["id": 2, "type": "apns-voip", "this_device": true])!,
                PushDevice(["id": 3, "type": "apns-voip", "this_device": true])!]
    check(PushLogic.duplicates(devs, keep: 3, type: "apns-voip") == [2] && PushLogic.duplicates(devs, keep: 1) == [], "VoIP-Abo: Doppelte nur derselben Art")
    var st = PushState(); st.devices = devs
    check(st.thisDevice?.id == 1 && st.thisVoip?.id == 2, "dieses Geraet: normales und VoIP-Abo getrennt")
}
