import Foundation
import CoreMedia
import CoreVideo

// Anrufe 2.4 (saga-call v3, E0) und Bild-im-Bild: Pfaderkennung, Bitrate-Wahl, Perfect Negotiation (Zusammenstoss),
// Kompatibilitaet mit 2.3-Gegenuebern (ohne "reneg"), want small/large, Sample-Buffer ohne Kopie
func callV3Tests() {
    callV3PathTests()
    callV3BitrateTests()
    callV3NegotiationTests()
    callV3CompatTests()
    callV3WantTests()
    callV3SampleBufferTests()
}

private func callV3PathTests() {
    typealias V = CallV3
    check(V.path(local: ["candidateType": "host", "protocol": "udp"], remote: ["candidateType": "host"]) == .direct, "host/udp: direkt")
    check(V.path(local: ["candidateType": "srflx", "protocol": "udp"], remote: ["candidateType": "prflx"]) == .direct, "srflx/udp: direkt")
    check(V.path(local: ["candidateType": "relay", "protocol": "udp", "relayProtocol": "udp"], remote: ["candidateType": "srflx"]) == .relayUDP, "TURN/UDP")
    check(V.path(local: ["candidateType": "relay", "protocol": "udp", "relayProtocol": "tls"], remote: ["candidateType": "srflx"]) == .relayTCP, "TURN ueber TLS 443")
    check(V.path(local: ["candidateType": "relay", "protocol": "udp", "relayProtocol": "TCP"], remote: nil) == .relayTCP, "TURN/TCP (Grossschrift)")
    check(V.path(local: ["candidateType": "host", "protocol": "tcp"], remote: ["candidateType": "host"]) == .relayTCP, "ICE-TCP: Grenzen wie TCP")
    check(V.path(local: ["candidateType": "srflx", "protocol": "udp"], remote: ["candidateType": "relay"]) == .relayUDP, "Gegenueber relayt: Relay-UDP")
    check(V.path(local: ["candidateType": "relay"], remote: nil) == .relayUDP, "relay ohne relayProtocol: UDP")
    check(V.path(local: nil, remote: ["candidateType": "host"]) == nil, "kein Paar: kein Pfad")
    check(V.Path.direct.rawValue == "direct" && V.Path.relayUDP.rawValue == "relay-udp" && V.Path.relayTCP.rawValue == "relay-tcp", "state.path-Werte wie im Vertrag")
    check(V.worse(.direct, .relayTCP) == .relayTCP && V.worse(.relayUDP, .direct) == .relayUDP && V.worse(nil, .relayUDP) == .relayUDP
          && V.worse(.direct, nil) == .direct && V.worse(nil, nil) == nil, "schlechterer Weg von eigenem und gemeldetem")
}

private func callV3BitrateTests() {
    typealias V = CallV3
    // 1:1, Kamera 720p
    var l = V.videoLimits(path: .direct, kind: .camera, streams: 1, want: .large, sourceHeight: 720)
    check(l == V.Limits(maxBitrate: 1_500_000, scaleDown: 1, maxFramerate: 30), "direkt: 1,5 Mbit/s 720p30")
    l = V.videoLimits(path: .relayUDP, kind: .camera, streams: 1, want: .large, sourceHeight: 720)
    check(l.maxBitrate == 1_200_000 && l.scaleDown == 1 && l.active, "Relay-UDP: 1,2 Mbit/s")
    l = V.videoLimits(path: .relayTCP, kind: .camera, streams: 1, want: .large, sourceHeight: 720)
    check(l.maxBitrate == 800_000 && abs(l.scaleDown - 720.0 / 540) < 0.0001 && !l.maintainResolution, "TCP/TLS: 800 kbit/s, 540p")
    // Mesh (n >= 3)
    l = V.videoLimits(path: .direct, kind: .camera, streams: 2, want: .large, sourceHeight: 720)
    check(l.maxBitrate == 600_000 && abs(l.scaleDown - 720.0 / 540) < 0.0001, "Mesh direkt: 600 kbit/s 540p")
    l = V.videoLimits(path: .relayUDP, kind: .camera, streams: 3, want: .large, sourceHeight: 720)
    check(l.maxBitrate == 500_000, "Mesh Relay-UDP: 500 kbit/s")
    l = V.videoLimits(path: .relayTCP, kind: .camera, streams: 2, want: .large, sourceHeight: 720)
    check(l.maxBitrate == 350_000 && l.scaleDown == 2 && l.maxFramerate == 15, "Mesh TCP: 350 kbit/s 360p15")
    // Bildschirm
    l = V.videoLimits(path: .direct, kind: .screen, streams: 1, want: .large, sourceHeight: 1080)
    check(l.maxBitrate == 2_500_000 && l.scaleDown == 1 && l.maxFramerate == 15 && l.maintainResolution, "Bildschirm direkt: 2,5 Mbit/s 1080p15")
    l = V.videoLimits(path: .relayUDP, kind: .screen, streams: 1, want: .large, sourceHeight: 1080)
    check(l.maxBitrate == 1_800_000, "Bildschirm Relay-UDP: 1,8 Mbit/s")
    l = V.videoLimits(path: .relayTCP, kind: .screen, streams: 1, want: .large, sourceHeight: 1080)
    check(l.maxBitrate == 1_200_000 && l.maxFramerate == 8 && l.maintainResolution, "Bildschirm TCP: 1,2 Mbit/s, 8 fps, Aufloesung halten")
    // Gegenstelle im PiP
    l = V.videoLimits(path: .direct, kind: .camera, streams: 1, want: .small, sourceHeight: 720)
    check(l.maxBitrate == 300_000 && l.scaleDown == 3, "PiP 720p: 300 kbit/s, max(2, min(3, 720/240)) = 3")
    l = V.videoLimits(path: .relayTCP, kind: .camera, streams: 1, want: .small, sourceHeight: 720)
    check(l.maxBitrate == 300_000 && l.scaleDown == 3, "PiP TCP: 300 kbit/s, Drittel")
    check(V.videoLimits(path: .direct, kind: .camera, streams: 1, want: .small, sourceHeight: 360).scaleDown == 2, "PiP 360p-Quelle: mindestens 2")
    check(V.videoLimits(path: .direct, kind: .camera, streams: 1, want: .small, sourceHeight: 600).scaleDown == 2.5, "PiP 600p: 600/240 = 2,5")
    check(V.videoLimits(path: .direct, kind: .camera, streams: 1, want: .small, sourceHeight: 1080).scaleDown == 3, "PiP 1080p: hoechstens 3")
    check(V.videoLimits(path: .direct, kind: .camera, streams: 1, want: .small, sourceHeight: 720).maxBitrate == 300_000, "PiP: min(1,5 M, 300 k)")
    l = V.videoLimits(path: .direct, kind: .camera, streams: 2, want: .small, sourceHeight: 720)
    check(l.maxBitrate == 200_000, "PiP Mesh: 200 kbit/s")
    check(V.videoLimits(path: .relayTCP, kind: .screen, streams: 1, want: .small, sourceHeight: 1080)
          == V.videoLimits(path: .relayTCP, kind: .screen, streams: 1, want: .large, sourceHeight: 1080), "Bildschirm: PiP aendert nichts (Text lesbar)")
    l = V.videoLimits(path: .direct, kind: .camera, streams: 1, want: .off, sourceHeight: 720)
    check(!l.active, "want off: Sender pausiert")
    // kleine Quelle (Testbild 360p): nie hochskalieren
    check(V.videoLimits(path: .relayTCP, kind: .camera, streams: 1, want: .large, sourceHeight: 360).scaleDown == 1, "360p-Quelle: kein Skalieren unter 1")
    check(V.videoLimits(path: .direct, kind: .camera, streams: 1, want: .large, sourceHeight: 1080).scaleDown == 1.5, "1080p-Quelle -> 720p")
    check(V.audioBitrate(path: .direct) == 32_000 && V.audioBitrate(path: .relayUDP) == 32_000 && V.audioBitrate(path: .relayTCP) == 24_000, "Opus 32/32/24 kbit/s")
}

/// Zwei Geraete mit Perfect Negotiation; Signalisierungszustand vereinfacht (stable / have-local-offer)
private struct NegPeer {
    var neg: CallV3.Negotiator
    var haveLocalOffer = false
    var remoteOffers = 0
    init(polite: Bool) { neg = CallV3.Negotiator(polite: polite) }
}

private func callV3NegotiationTests() {
    typealias V = CallV3
    check(V.polite(me: "dev-b", peer: "dev-a") && !V.polite(me: "dev-a", peer: "dev-b"), "hoeflich = groessere device_id")
    let devA = "3f00", devB = "a100"
    var a = NegPeer(polite: V.polite(me: devA, peer: devB)), b = NegPeer(polite: V.polite(me: devB, peer: devA))
    check(!a.neg.polite && b.neg.polite, "genau eine Seite ist hoeflich")

    // Aufbau: A ruft an (n=1), B antwortet ohne Neuverhandlung
    let n1 = a.neg.initialOffer(); a.haveLocalOffer = true
    b.neg.initialRemote(n1)
    check(a.neg.remoteAnswer(n: n1, haveLocalOffer: a.haveLocalOffer) == .apply, "erste Antwort angenommen"); a.haveLocalOffer = false
    check(a.neg.remoteAnswer(n: n1, haveLocalOffer: false) == .ignore, "doppelte Antwort verworfen")

    // Zusammenstoss: beide starten gleichzeitig ein Angebot (z. B. ICE-Restart nach Netzwechsel)
    let na = a.neg.beginOffer(), nb = b.neg.beginOffer()
    a.neg.offerSent(na); a.haveLocalOffer = true
    b.neg.offerSent(nb); b.haveLocalOffer = true
    // A (unhoeflich) bekommt B's Angebot -> verwerfen
    check(a.neg.remoteOffer(n: nb, stable: !a.haveLocalOffer) == .ignore, "Zusammenstoss: unhoeflich verwirft")
    check(a.neg.ignoredOffer, "verworfen vermerkt")
    // B (hoeflich) bekommt A's Angebot -> eigenes zuruecknehmen, fremdes annehmen
    check(b.neg.remoteOffer(n: na, stable: !b.haveLocalOffer) == .accept(rollback: true), "Zusammenstoss: hoeflich nimmt zurueck")
    b.haveLocalOffer = false                                            // Rollback + Antwort -> stable
    check(b.neg.pendingN == nil && !b.neg.makingOffer, "hoeflich: eigenes Angebot verworfen")
    // A bekommt B's Antwort auf SEIN Angebot
    check(a.neg.remoteAnswer(n: na, haveLocalOffer: a.haveLocalOffer) == .apply, "Antwort auf das gewinnende Angebot"); a.haveLocalOffer = false
    // spaete Antwort auf das zurueckgenommene Angebot von B (kommt nie, aber falls doch): verworfen
    check(b.neg.remoteAnswer(n: nb, haveLocalOffer: b.haveLocalOffer) == .ignore, "Antwort auf zurueckgenommenes Angebot verworfen")
    check(!a.haveLocalOffer && !b.haveLocalOffer, "beide wieder stable")
    // dasselbe Angebot nochmal (Live + Nachholen): alt
    check(b.neg.remoteOffer(n: na, stable: true) == .stale, "doppeltes Angebot: alt")
    // Antwort mit falscher Nummer
    let na2 = a.neg.beginOffer(); a.neg.offerSent(na2); a.haveLocalOffer = true
    check(a.neg.remoteAnswer(n: na2 + 5, haveLocalOffer: true) == .ignore, "Antwort mit falschem n verworfen")
    check(a.neg.remoteAnswer(n: na2, haveLocalOffer: true) == .apply, "richtige Antwort danach")
    // ohne Zusammenstoss: annehmen ohne Rollback
    check(b.neg.remoteOffer(n: na2, stable: true) == .accept(rollback: false), "normales Angebot: annehmen")
    // hoeflich, aber gerade selbst beim Anbieten (makingOffer, noch stable)
    var p = V.Negotiator(polite: true); _ = p.beginOffer()
    check(p.remoteOffer(n: 1, stable: true) == .accept(rollback: true) && !p.makingOffer, "hoeflich beim Anbieten: zuruecknehmen")
    var q = V.Negotiator(polite: false); _ = q.beginOffer()
    check(q.remoteOffer(n: 1, stable: true) == .ignore && q.makingOffer, "unhoeflich beim Anbieten: verwerfen, weiter anbieten")
    q.offerFailed(); check(!q.makingOffer && q.pendingN == nil, "Angebot fehlgeschlagen: zurueckgesetzt")
    // Simulation: 50 Runden zufaelliger Zusammenstoesse enden immer in genau einem angenommenen Angebot
    var ok = true
    for r in 0..<50 {
        var x = NegPeer(polite: r % 2 == 0), y = NegPeer(polite: r % 2 != 0)
        let nx = x.neg.beginOffer(), ny = y.neg.beginOffer()
        x.neg.offerSent(nx); x.haveLocalOffer = true; y.neg.offerSent(ny); y.haveLocalOffer = true
        let ax = x.neg.remoteOffer(n: ny, stable: false), ay = y.neg.remoteOffer(n: nx, stable: false)
        let accepted = [ax, ay].filter { if case .accept = $0 { return true }; return false }.count
        let ignored = [ax, ay].filter { $0 == .ignore }.count
        if accepted != 1 || ignored != 1 { ok = false }
    }
    check(ok, "Zusammenstoss: immer genau ein Angebot gewinnt")
}

private func callV3CompatTests() {
    typealias V = CallV3
    typealias L = CallLogic
    let sdp = "v=0\r\na=fingerprint:sha-256 AB:CD\r\n"
    // 2.3-Bodys: keine neuen Felder -> keine Neuverhandlung
    let old = V.meta(L.offerBody(sdp: sdp, media: "video"))
    check(old == V.Meta(), "2.3-Angebot: keine v3-Felder")
    let mine = V.myCaps(serverFeatures: ["reneg", "pip_hint"])
    check(mine == ["reneg", "want", "bye"], "eigene caps mit v3-Server: reneg, want, bye")
    check(V.myCaps(serverFeatures: []) == ["want", "bye"], "Server ohne reneg: kein reneg melden")
    check(!V.canReneg(mine: mine, peer: old.caps) && !V.canReneg(mine: mine, peer: []) && !V.canReneg(mine: mine, peer: ["want", "bye"]), "Gegenueber ohne reneg: keine Neuverhandlung")
    check(V.canReneg(mine: mine, peer: ["reneg", "want", "bye"]), "beide reneg: Neuverhandlung")
    check(!V.canReneg(mine: ["want", "bye"], peer: ["reneg"]), "eigener Server ohne reneg: keine Neuverhandlung")
    check(V.canBye(["bye"]) && !V.canBye(nil) && !V.canBye(["reneg"]), "bye nur an Gegenstellen mit bye")
    check(V.pairPC("65fb", "2f67") == "2f67:65fb" && V.pairPC("2f67", "65fb") == "2f67:65fb", "pc = min:max der device_ids")
    // ICE: ohne reneg genau wie 2.3 (unterbrochen: abwarten, fehlgeschlagen: Ende)
    check(V.iceAction(state: "disconnected", everConnected: true, canReneg: false, attempts: 0) == .none, "2.3: unterbrochen -> abwarten")
    check(V.iceAction(state: "failed", everConnected: true, canReneg: false, attempts: 0) == .fail, "2.3: fehlgeschlagen -> Ende")
    check(V.iceAction(state: "failed", everConnected: false, canReneg: true, attempts: 0) == .fail, "vor der Verbindung: wie bisher")
    check(V.iceAction(state: "disconnected", everConnected: true, canReneg: true, attempts: 0) == .wait, "v3: unterbrochen -> kurz warten, dann Restart")
    check(V.iceAction(state: "failed", everConnected: true, canReneg: true, attempts: 0) == .restart, "v3: fehlgeschlagen -> ICE-Restart")
    check(V.iceAction(state: "failed", everConnected: true, canReneg: true, attempts: V.maxRestarts) == .fail, "v3: nach 3 Versuchen Ende")
    check(V.iceAction(state: "connected", everConnected: true, canReneg: true, attempts: 0) == .none, "verbunden: nichts")
    // v3-Bodys bleiben fuer 2.3-Parser lesbar
    let off = V.offerBody(sdp: sdp, media: "video", pc: "p1", n: 3, caps: mine)
    if case .offer(let s, let m, let fp)? = L.parse(off) { check(s == sdp && m == "video" && fp == ["sha-256 AB:CD"], "v3-Angebot fuer 2.3 lesbar") } else { check(false, "v3-Angebot lesbar") }
    check(V.meta(off) == V.Meta(pc: "p1", caps: mine, n: 3), "v3-Angebot: pc, caps, n (ohne restart)")
    let rs = V.offerBody(sdp: sdp, media: "video", pc: "p1", n: 4, caps: mine, restart: true)
    check(V.meta(rs).restart == true && V.meta(rs).n == 4, "ICE-Restart-Angebot: restart als Hinweis")
    let ans = V.answerBody(sdp: sdp, media: "video", pc: "p1", n: 3, caps: mine)
    if case .answer(let s, _)? = L.parse(ans) { check(s == sdp, "v3-Antwort lesbar") } else { check(false, "v3-Antwort lesbar") }
    check(V.meta(ans).n == 3 && V.meta(ans).caps == mine && ans.contains(#""media":"video""#), "v3-Antwort: n, caps, media")
    let st = V.stateBody(pc: "p1", mic: false, cam: true, screen: false, pip: true, want: .small, path: .relayTCP)
    check(L.parse(st) == .state(mic: false, cam: true, screen: false), "v3-Zustand fuer 2.3 lesbar")
    check(V.meta(st) == V.Meta(pc: "p1", pip: true, want: .small, path: .relayTCP), "state: pc, pip, want, path")
    check(!V.stateBody(pc: "p1", mic: true, cam: true, screen: false, pip: false, want: .large, path: nil).contains("path"), "Weg unbekannt: kein path")
    let ice = V.iceBody([L.Candidate(candidate: "candidate:1", sdpMid: "0", sdpMLineIndex: 0, usernameFragment: "oNrl")], pc: "p1")
    if case .ice(let c)? = L.parse(ice) { check(c.count == 1 && c[0].usernameFragment == "oNrl", "v3-ICE mit usernameFragment") } else { check(false, "v3-ICE lesbar") }
    check(V.meta(ice).pc == "p1", "ICE mit pc")
    if case .ice(let c)? = L.parse(L.iceBody([L.Candidate(candidate: "candidate:1", sdpMid: "0", sdpMLineIndex: 0)])) { check(c[0].usernameFragment == nil, "2.3-ICE: ohne ufrag") }
    // Kandidaten je Generation (ICE-Restart)
    let cand = L.Candidate(candidate: "candidate:1 1 udp 2122260223 192.0.2.10 54321 typ host generation 0 ufrag Zx9q network-id 1", sdpMid: "0", sdpMLineIndex: 0)
    check(V.candidateUfrag(cand) == "Zx9q", "ufrag aus der candidate-Zeile")
    check(V.candidateUfrag(L.Candidate(candidate: "candidate:1 1 udp 1 h 1 typ host", sdpMid: nil, sdpMLineIndex: 0, usernameFragment: "abc")) == "abc", "ufrag aus usernameFragment")
    check(V.sdpUfrags("v=0\r\na=ice-ufrag:oNrl\r\na=ice-pwd:x\r\na=ice-ufrag:oNrl\r\n") == ["oNrl"], "a=ice-ufrag aus der SDP")
    check(V.iceUse(cand, current: ["Zx9q"], old: ["oNrl"]) == .apply, "passende Generation: setzen")
    check(V.iceUse(cand, current: ["oNrl"], old: []) == .hold, "neue Generation vor der Antwort: zurueckhalten")
    check(V.iceUse(cand, current: ["Ab12"], old: ["Zx9q"]) == .drop, "alte Generation: verwerfen")
    check(V.iceUse(L.Candidate(candidate: "candidate:1 1 udp 1 h 1 typ host", sdpMid: nil, sdpMLineIndex: 0), current: ["oNrl"], old: []) == .apply, "ohne ufrag (2.3): setzen")
    check(V.iceUse(cand, current: [], old: []) == .apply, "SDP ohne ufrag: setzen")
    check(V.restartDelay == 2.5 && V.restartTimeout == 20 && V.maxRestarts == 3, "ICE-Restart: 2,5 s, 20 s, 3 Versuche")
    // unbekannte t-Typen: ignorieren; bye bekannt
    check(L.parse(V.byeBody(pc: "p1")) == .bye && V.byeBody(pc: "p1").contains(#""reason":"hangup""#), "bye mit reason hangup")
    check(L.parse(#"{"t":"fkey","k":"x"}"#) == .other("fkey"), "unbekannter Typ (2.5 fkey): ignorieren")
    // pc: ohne Kennung (2.3) gehoert alles zu uns, sonst nur die eigene
    check(V.samePC(nil, "p1") && V.samePC("p1", "p1") && V.samePC("p1", nil) && !V.samePC("p2", "p1"), "pc-Zuordnung")
    // kaputte/fremde Felder
    let bad = V.meta(#"{"t":"offer","pc":"","caps":"reneg","n":true,"want":{"video":"riesig"},"path":"relayTCP","restart":"ja"}"#)
    check(bad == V.Meta(), "ungueltige v3-Felder: ignoriert")
    // Netzwechsel
    check(!V.netChangeNeedsRestart(old: nil, new: .init(satisfied: true, primary: "wifi")), "erste Netzmeldung: nichts")
    check(V.netChangeNeedsRestart(old: .init(satisfied: true, primary: "wifi"), new: .init(satisfied: true, primary: "cellular")), "WLAN -> LTE: Restart")
    check(V.netChangeNeedsRestart(old: .init(satisfied: false, primary: nil), new: .init(satisfied: true, primary: "wifi")), "Netz wieder da: Restart")
    check(!V.netChangeNeedsRestart(old: .init(satisfied: true, primary: "wifi"), new: .init(satisfied: true, primary: "wifi")), "gleiches Netz: nichts")
    check(!V.netChangeNeedsRestart(old: .init(satisfied: true, primary: "wifi"), new: .init(satisfied: false, primary: nil)), "Netz weg: abwarten")
}

private func callV3WantTests() {
    typealias V = CallV3
    typealias P = PiPLogic
    check(P.want(pip: true) == .small && P.want(pip: false) == .large, "PiP -> small, sonst large")
    var w = P.WantSender()
    check(!w.update(.large), "Start large: nichts senden")
    check(w.update(.small), "in PiP: small senden")
    check(!w.update(.small), "nochmal small: nichts")
    check(w.update(.large), "zurueck: large senden")
    check(V.meta(V.stateBody(pc: "p", mic: true, cam: true, screen: false, pip: true, want: .small, path: nil)) == V.Meta(pc: "p", pip: true, want: .small), "state traegt pip/want")
    check(V.meta(V.stateBody(pc: "p", mic: true, cam: true, screen: false, pip: false, want: .large, path: .direct)).want == .large, "state want large")
    check(V.meta(#"{"t":"state","mic":true}"#).want == nil, "2.3-Zustand: kein want (= large)")
    // iPhone/iPad: PiP beim Verkleinern bzw. im Hintergrund, nur mit Fernbild und laufendem Anruf
    check(P.iosWantsPiP(phase: "active", hasRemoteVideo: true, minimized: true, background: false), "verkleinert: PiP")
    check(P.iosWantsPiP(phase: "active", hasRemoteVideo: true, minimized: false, background: true), "Hintergrund: PiP")
    check(!P.iosWantsPiP(phase: "active", hasRemoteVideo: true, minimized: false, background: false), "Vollbild: kein PiP")
    check(!P.iosWantsPiP(phase: "active", hasRemoteVideo: false, minimized: true, background: true), "ohne Fernbild: kein PiP")
    check(!P.iosWantsPiP(phase: "ringingOut", hasRemoteVideo: true, minimized: true, background: false), "klingelt: kein PiP")
    check(!P.iosWantsPiP(phase: "ended", hasRemoteVideo: true, minimized: true, background: true), "beendet: kein PiP")
    // Mac: Schwebefenster nur bei verdecktem/verkleinertem Anruffenster und Video
    check(P.macPanelShows(phase: "active", video: true, callWindowVisible: false), "Mac verdeckt: Schwebefenster")
    check(!P.macPanelShows(phase: "active", video: true, callWindowVisible: true), "Mac sichtbar: keins")
    check(!P.macPanelShows(phase: "active", video: false, callWindowVisible: false), "nur Ton: keins (Leiste reicht)")
    check(!P.macPanelShows(phase: "incoming", video: true, callWindowVisible: false), "klingelt: keins")
    check(!P.macPanelShows(phase: "preparing", video: true, callWindowVisible: false), "Vorbereitung: keins")
    check(!P.macPanelShows(phase: "idle", video: true, callWindowVisible: false), "idle: keins")
}

/// Performance (Konzept §3.5): CVPixelBuffer-Bilder werden nicht kopiert, die Formatbeschreibung wiederverwendet
private func callV3SampleBufferTests() {
    func makePB(_ w: Int, _ h: Int) -> CVPixelBuffer? {
        var pb: CVPixelBuffer?
        let attrs: [String: Any] = [kCVPixelBufferIOSurfacePropertiesKey as String: [String: Any]()]
        CVPixelBufferCreate(kCFAllocatorDefault, w, h, kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange, attrs as CFDictionary, &pb)
        return pb
    }
    let m = PiPSampleMaker()
    guard let pb = makePB(1280, 720) else { check(false, "CVPixelBuffer anlegen"); return }
    var same = true, immediate = true
    let t0 = DispatchTime.now().uptimeNanoseconds
    for i in 0..<300 {
        guard let sb = m.wrap(pb, ptsNs: Int64(i) * 33_000_000) else { same = false; break }
        if CMSampleBufferGetImageBuffer(sb) !== pb { same = false }        // derselbe Puffer, keine Kopie
        if i == 0, let a = CMSampleBufferGetSampleAttachmentsArray(sb, createIfNecessary: false) as? [[CFString: Any]] {
            immediate = (a.first?[kCMSampleAttachmentKey_DisplayImmediately] as? Bool) == true
        }
    }
    let perFrameUs = Double(DispatchTime.now().uptimeNanoseconds - t0) / 300 / 1000
    check(same, "Sample-Buffer traegt denselben CVPixelBuffer (keine Kopie pro Bild)")
    check(m.formatsMade == 1, "Formatbeschreibung einmal angelegt, danach wiederverwendet (\(m.formatsMade))")
    check(m.conversions == 0, "Hardware-Bilder: keine Umrechnung")
    check(immediate, "sofort anzeigen (live)")
    check(perFrameUs < 500, "Einpacken je Bild < 0,5 ms (gemessen \(String(format: "%.1f", perFrameUs)) µs)")
    print("PiP-Sample-Buffer: \(String(format: "%.1f", perFrameUs)) µs je Bild (1280x720, ohne Kopie)")
    // neue Groesse (Gegenueber dreht/wechselt Kamera): neue Formatbeschreibung
    if let pb2 = makePB(720, 1280) { _ = m.wrap(pb2, ptsNs: 1); check(m.formatsMade == 2, "neue Groesse: neue Formatbeschreibung") }
    // Rueckfall I420 -> NV12 (Software-Decoder): kopiert, Inhalt stimmt
    let w = 4, h = 2
    let y: [UInt8] = [1, 2, 3, 4, 5, 6, 7, 8], u: [UInt8] = [10, 11], v: [UInt8] = [20, 21]
    let out = y.withUnsafeBufferPointer { yp in u.withUnsafeBufferPointer { up in v.withUnsafeBufferPointer { vp in
        m.nv12(width: w, height: h, y: yp.baseAddress!, strideY: 4, u: up.baseAddress!, strideU: 2, v: vp.baseAddress!, strideV: 2)
    } } }
    if let out {
        CVPixelBufferLockBaseAddress(out, .readOnly)
        let py = CVPixelBufferGetBaseAddressOfPlane(out, 0)!.assumingMemoryBound(to: UInt8.self), sy = CVPixelBufferGetBytesPerRowOfPlane(out, 0)
        let puv = CVPixelBufferGetBaseAddressOfPlane(out, 1)!.assumingMemoryBound(to: UInt8.self)
        check(py[0] == 1 && py[3] == 4 && py[sy] == 5 && py[sy + 3] == 8, "NV12: Y-Ebene")
        check(puv[0] == 10 && puv[1] == 20 && puv[2] == 11 && puv[3] == 21, "NV12: UV verschraenkt")
        CVPixelBufferUnlockBaseAddress(out, .readOnly)
        check(CVPixelBufferGetPixelFormatType(out) == kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange, "NV12-Format")
    } else { check(false, "I420 -> NV12") }
    check(m.conversions == 1, "Umrechnung gezaehlt (nur Rueckfall)")
}
