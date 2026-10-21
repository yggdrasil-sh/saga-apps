import XCTest
import Foundation

// Chat-Grundlagen (ChatX3Logic.swift) und Vertrauens-Uebernahme (FedChatFlow.evaluate mit v1-Stand): Reaktionen,
// Bearbeiten, Sprachnachrichten (Wellenform wie im Browser, Zeitgrenzen, Gesten), Systemzeile, Stufe 3 im Chat-Objekt.

final class ChatX3LogicTests: XCTestCase {
    func testWaveformMatchesBrowser() {
        // Erwartung aus static/chat-voice.js (waveform) mit denselben Pegeln (node, Float32-gerundet)
        let lv: [Float] = (0..<150).map { Float(abs(sin(Double($0) * 0.37)) * Double($0 % 7) / 7) }
        let want: [UInt8] = [63, 171, 229, 44, 131, 255, 85, 88, 242, 102, 131, 187, 102, 170, 170, 86, 180, 207, 47, 162, 240, 61, 111, 255, 92, 105,
                             230, 104, 146, 159, 99, 184, 165, 77, 208, 219, 19, 199, 249, 73, 156, 252, 98, 118, 214, 104, 159, 122, 95, 195, 186, 66,
                             208, 227, 38, 190, 254, 83, 134, 245, 101, 129, 194, 147]
        XCTAssertEqual(VoiceLogic.waveform(lv), want)
        XCTAssertEqual(VoiceLogic.waveform([0.5, 0.25]), [UInt8](repeating: 255, count: 32) + [UInt8](repeating: 180, count: 32))
        XCTAssertEqual(VoiceLogic.waveform([]), [UInt8](repeating: 0, count: 64))
        XCTAssertEqual(VoiceLogic.waveform([0, 0, 0]), [UInt8](repeating: 0, count: 64))
    }

    func testVoiceHelpers() {
        XCTAssertEqual(VoiceLogic.bars(Data([1, 2, 3]).base64EncodedString()).prefix(4), [1, 2, 3, 0])
        XCTAssertEqual(VoiceLogic.bars(nil).count, 64)
        XCTAssertEqual(VoiceLogic.played(position: 0, duration: 10), 0)
        XCTAssertEqual(VoiceLogic.played(position: 5, duration: 10), 32)
        XCTAssertEqual(VoiceLogic.played(position: 12, duration: 10), 64)
        XCTAssertEqual(VoiceLogic.played(position: 1, duration: 0), 0)
        XCTAssertEqual(VoiceLogic.seek(fraction: 0.5, duration: 10), 5)
        XCTAssertEqual(VoiceLogic.seek(fraction: 1.5, duration: 10), 9.95, accuracy: 1e-9)
        XCTAssertEqual(VoiceLogic.fmt(0), "0:00"); XCTAssertEqual(VoiceLogic.fmt(12.4), "0:12"); XCTAssertEqual(VoiceLogic.fmt(899.6), "15:00")
        XCTAssertEqual(VoiceLogic.nextSpeed(1), 1.5); XCTAssertEqual(VoiceLogic.nextSpeed(1.5), 2); XCTAssertEqual(VoiceLogic.nextSpeed(2), 1)
        XCTAssertEqual(VoiceLogic.speedLabel(1.5), "1,5×"); XCTAssertEqual(VoiceLogic.speedLabel(2), "2×")
        let d = VoiceLogic.fileDict(id: "f", key: "k", size: 1000, v2: true, cs: 262_144, dur: 12.44, wf: [1, 2])
        XCTAssertEqual(d["voice"] as? Bool, true); XCTAssertEqual(d["m"] as? String, "audio/ogg; codecs=opus"); XCTAssertEqual(d["x"] as? Int, 2)
        XCTAssertEqual(d["dur"] as? Double, 12.4)
        let ref = ChatFileRef(d)
        XCTAssertEqual(ref?.voice, true); XCTAssertEqual(ref?.wf, "AQI="); XCTAssertEqual(ref?.dur, 12.4); XCTAssertEqual(ref?.isV2, true)
        XCTAssertEqual(ref?.dict["voice"] as? Bool, true)
        XCTAssertEqual(ChatFileRef(["id": "x", "k": "k", "m": "image/png", "voice": true])?.voice, false, "voice nur fuer Audio")
    }

    func testRecordingLimits() {
        XCTAssertEqual(VoiceLogic.tick(secs: 10, maxS: 900, warned: false), .run)
        XCTAssertEqual(VoiceLogic.tick(secs: 840, maxS: 900, warned: false), .warn, "Hinweis bei 14:00")
        XCTAssertEqual(VoiceLogic.tick(secs: 841, maxS: 900, warned: true), .run)
        XCTAssertEqual(VoiceLogic.tick(secs: 900, maxS: 900, warned: true), .stop, "Stopp bei 15:00")
        XCTAssertFalse(VoiceLogic.keep(duration: 0.99), "unter 1 s verwerfen")
        XCTAssertTrue(VoiceLogic.keep(duration: 1))
    }

    func testGestures() {
        XCTAssertEqual(VoiceLogic.gesture(dx: 0, dy: 0), .hold)
        XCTAssertEqual(VoiceLogic.gesture(dx: -20, dy: -80), .lock, "nach oben sperrt")
        XCTAssertEqual(VoiceLogic.gesture(dx: -130, dy: -20), .cancel, "nach links verwirft")
        XCTAssertEqual(VoiceLogic.gesture(dx: -60, dy: -50), .hold)
        XCTAssertEqual(VoiceLogic.gesture(dx: 120, dy: 0), .hold, "nach rechts: nichts")
    }

    func testReactionsLogic() {
        let r = { (by: String, ts: Int64) in ChatX3.Reaction(by: by, dev: "d", ts: ts, ct: "c\(ts)", sig: "s") }
        XCTAssertEqual(ChatX3.newest([r("a", 2), r("a", 5), r("b", 1), r("a", 3)]).map(\.ts), [1, 5])
        XCTAssertEqual(ChatX3.merge([r("a", 5)], [r("a", 4), r("b", 9)]).map(\.ts), [5, 9], "aelteres ersetzt nichts")
        let o = [ChatX3.Opened(by: "~me", e: "👍", ts: 3), .init(by: "~b", e: "❤️", ts: 1), .init(by: "~c", e: "👍", ts: 2), .init(by: "~d", e: "", ts: 4)]
        let chips = ChatX3.chips(o, me: "~me")
        XCTAssertEqual(chips.map(\.e), ["❤️", "👍"], "Reihenfolge der ersten Reaktion")
        XCTAssertEqual(chips.map(\.n), [1, 2]); XCTAssertEqual(chips.map(\.mine), [false, true])
        XCTAssertEqual(chips[1].who, ["~c", "~me"])
        XCTAssertEqual(ChatX3.mine(o, me: "~me"), "👍"); XCTAssertNil(ChatX3.mine(o, me: "~d"))
        XCTAssertEqual(ChatX3.toggle(current: "👍", picked: "👍"), "", "gleiches nochmal nimmt zurueck")
        XCTAssertEqual(ChatX3.toggle(current: "👍", picked: "😂"), "😂")
        let l = ChatX3.applyLocal(o, me: "~me", e: "😂", ts: 9)
        XCTAssertEqual(ChatX3.mine(l, me: "~me"), "😂"); XCTAssertEqual(l.filter { $0.by == "~me" }.count, 1)
        XCTAssertNil(ChatX3.mine(ChatX3.applyLocal(o, me: "~me", e: "", ts: 9), me: "~me"))
    }

    func testEmojiAndBody() {
        XCTAssertTrue(ChatX3.emojiOK("👍")); XCTAssertTrue(ChatX3.emojiOK("👩🏽‍💻")); XCTAssertTrue(ChatX3.emojiOK("❤️"))
        XCTAssertFalse(ChatX3.emojiOK("👍👍")); XCTAssertFalse(ChatX3.emojiOK("")); XCTAssertFalse(ChatX3.emojiOK("\u{7}"))
        XCTAssertEqual(ChatX3.pickedEmoji("😮"), "😮"); XCTAssertNil(ChatX3.pickedEmoji("a")); XCTAssertNil(ChatX3.pickedEmoji(" "))
        XCTAssertEqual(ChatX3.reactBody(Data(#"{"e":"👍","ts":5}"#.utf8), ts: 5), "👍")
        XCTAssertEqual(ChatX3.reactBody(Data(#"{"e":"","ts":5}"#.utf8), ts: 5), "", "zurueckgenommen")
        XCTAssertNil(ChatX3.reactBody(Data(#"{"e":"👍","ts":6}"#.utf8), ts: 5), "andere Zeit")
        XCTAssertNil(ChatX3.reactBody(Data(#"{"e":"👍","ts":5,"x":1}"#.utf8), ts: 5), "Zusatzfelder")
        XCTAssertNil(ChatX3.reactBody(Data(#"{"e":"👍","ts":5.5}"#.utf8), ts: 5))
        XCTAssertNil(ChatX3.reactBody(Data(("{\"e\":\"" + String(repeating: "x", count: 33) + "\",\"ts\":5}").utf8), ts: 5), "zu lang")
        XCTAssertEqual(ChatX3.reactBodyJSON(emoji: "a\"b", ts: 1), #"{"e":"a\"b","ts":1}"#)
        XCTAssertEqual(ChatX3.quick.count, 6)
    }

    func testEditRules() {
        let L = ChatX3.Limits()
        let now = 1_000_000.0
        func ok(mine: Bool = true, text: Bool = true, ai: Bool = false, voice: Bool = false, pending: Bool = false, age: Double = 60, n: Int = 0) -> Bool {
            ChatX3.canEdit(mine: mine, hasText: text, ai: ai, voice: voice, pending: pending, created: now - age, editN: n, now: now, limits: L)
        }
        XCTAssertTrue(ok())
        XCTAssertFalse(ok(mine: false)); XCTAssertFalse(ok(text: false)); XCTAssertFalse(ok(ai: true)); XCTAssertFalse(ok(voice: true))
        XCTAssertFalse(ok(pending: true))
        XCTAssertTrue(ok(age: 86_399)); XCTAssertFalse(ok(age: 86_400), "24 h")
        XCTAssertTrue(ok(n: 19)); XCTAssertFalse(ok(n: 20), "hoechstens 20-mal")
        let body: [String: Any] = ["edit": "m", "t": "neu"]
        XCTAssertNil(ChatX3.editCheck(origID: "m", origSender: "~a", origAI: false, origCreated: 0, editOf: "m", editSender: "~a", editCreated: 10, body: body))
        XCTAssertEqual(ChatX3.editCheck(origID: "m", origSender: "~a", origAI: false, origCreated: 0, editOf: "m", editSender: "~b", editCreated: 10, body: body), .otherSender)
        XCTAssertEqual(ChatX3.editCheck(origID: "m", origSender: "~a", origAI: true, origCreated: 0, editOf: "m", editSender: "~a", editCreated: 10, body: body), .aiOriginal)
        XCTAssertEqual(ChatX3.editCheck(origID: "m", origSender: "~a", origAI: false, origCreated: 0, editOf: "m", editSender: "~a", editCreated: 86_401, body: body), .window)
        XCTAssertEqual(ChatX3.editCheck(origID: "x", origSender: "~a", origAI: false, origCreated: 0, editOf: "m", editSender: "~a", editCreated: 1, body: body), .editOfMismatch)
        XCTAssertEqual(ChatX3.editCheck(origID: "m", origSender: "~a", origAI: false, origCreated: 0, editOf: "m", editSender: "~a", editCreated: 1, body: nil), .editOfMismatch)
        XCTAssertEqual(String(decoding: ChatX3.editBody(msgID: "m1", text: "Zeile\n\"2\""), as: UTF8.self), #"{"edit":"m1","t":"Zeile\n\"2\""}"#)
    }

    func testLevel3ChatObject() {
        let c: [String: Any] = ["v": 3, "ref": "0123456789abcdef0123456789abcdef@a.example", "fed": false, "home_self": true, "my_handle": "~aaaaaaaaaaaaaaaa@a.example",
                                "x3_ok": true, "v3": ["seq": 41, "at": 1.0, "forced": false],
                                "members": [["username": "~bbbbbbbbbbbbbbbb@a.example", "handle": "~bbbbbbbbbbbbbbbb@a.example", "name": "Bea", "host": "a.example"],
                                            ["username": "ich", "handle": "~aaaaaaaaaaaaaaaa@a.example", "name": "Ich", "me": true, "host": "a.example"]]]
        let f = FedChatInfo(c)
        XCTAssertNotNil(f)
        XCTAssertEqual(f?.level, 3); XCTAssertEqual(f?.remote, false); XCTAssertEqual(f?.x3OK, true); XCTAssertEqual(f?.v3Seq, 41)
        XCTAssertEqual(f?.members.first?.handle, "~bbbbbbbbbbbbbbbb@a.example")
        var v2 = c; v2["v"] = 2
        XCTAssertEqual(FedChatInfo(v2)?.remote, true)
        var v1 = c; v1["v"] = 1
        XCTAssertNil(FedChatInfo(v1), "v1 bleibt lokal im alten Format")
        // Altbestand: ohne sender_handle mit v1 pruefen
        XCTAssertTrue(FedChatLogic.isLegacyV1(["seq": 40, "kind": "msg"]))
        XCTAssertFalse(FedChatLogic.isLegacyV1(["seq": 42, "kind": "msg", "sender_handle": "~bbbbbbbbbbbbbbbb@a.example"]))
        XCTAssertFalse(FedChatLogic.isLegacyV1(["seq": 40, "kind": "sys"]))
    }

    func testCryptoSysLine() {
        let a = FedChatLogic.sysLine(#"{"type":"crypto","level":3}"#, myHandle: nil)
        XCTAssertEqual(a, .crypto(verify: false))
        XCTAssertEqual(FedChatLogic.sysLine(#"{"type":"crypto","level":3,"verify":true}"#, myHandle: nil), .crypto(verify: true))
        XCTAssertFalse(FedChatLogic.sysText(a, myHandle: nil).isEmpty)
    }

    /// Erster Start einer 2.5-App: aus trust:<benutzer> wird trust2:<handle>, aber nur bei gleichem Identitaetsschluessel
    func testTrustMigrationInEvaluate() throws {
        ChatCrypto.ready()
        let h = "~bbbbbbbbbbbbbbbb@a.example"
        let ident = ChatCrypto.signKeypair(), backup = ChatCrypto.boxKeypair()
        let dev = ChatCrypto.signKeypair(), enc = ChatCrypto.boxKeypair(), did = ChatCrypto.hexID()
        let entry: [String: Any] = [
            "handle": h, "ident_pub": ChatCrypto.b64(ident.pk), "backup_pub": ChatCrypto.b64(backup.pk),
            "sig": ChatCrypto.b64(ChatCrypto.sign(ChatCrypto.handleMsg(handle: h, identPK: ident.pk, backupPK: backup.pk), sk: ident.sk)),
            "devices": [["id": did, "enc_pub": ChatCrypto.b64(enc.pk), "sign_pub": ChatCrypto.b64(dev.pk),
                         "fed_sig": ChatCrypto.b64(ChatCrypto.sign(ChatCrypto.deviceMsgV2(handle: h, deviceID: did, encPK: enc.pk, signPK: dev.pk), sk: ident.sk))]],
        ]
        func run(stored: FedTrust?, v1: FedChatLogic.V1Trust?) -> (FedKeyState, FedTrust?) {
            var saved: FedTrust?
            let r = FedFlow.evaluate([h], keys: [h: entry], myHandle: "~aaaaaaaaaaaaaaaa@a.example", myIdentPK: Data(),
                                     stored: { _ in stored }, save: { saved = $1 }, v1: { _ in v1 })
            return (r[h]!, saved)
        }
        let ip = ChatCrypto.b64(ident.pk)
        // gleicher Schluessel, ueberprueft: Vertrauen geht mit, Hinweis "Neue Darstellung"
        var (st, saved) = run(stored: nil, v1: .init(ident: ip, verified: true, changed: false))
        XCTAssertEqual(st.verdict, .trust); XCTAssertEqual(saved?.verified, true); XCTAssertEqual(saved?.migrated, true); XCTAssertTrue(st.verified)
        // anderer Schluessel, ueberprueft: Blockade (Server schiebt etwas unter)
        (st, saved) = run(stored: nil, v1: .init(ident: "AAAA", verified: true, changed: false))
        XCTAssertEqual(st.verdict, .block); XCTAssertNil(saved)
        // anderer Schluessel, nicht ueberprueft: Warnung
        (st, saved) = run(stored: nil, v1: .init(ident: "AAAA", verified: false, changed: false))
        XCTAssertEqual(st.verdict, .warn); XCTAssertEqual(saved?.changed, true); XCTAssertEqual(saved?.old, "AAAA")
        // schon trust2 vorhanden: v1 zaehlt nicht mehr
        (st, saved) = run(stored: FedTrust(ident: ip, verified: false), v1: .init(ident: "AAAA", verified: true, changed: false))
        XCTAssertEqual(st.verdict, .trust); XCTAssertNil(saved)
        // ohne v1: wie bisher (erstes Sehen)
        (st, saved) = run(stored: nil, v1: nil)
        XCTAssertEqual(st.verdict, .trust); XCTAssertNil(saved?.migrated)
        // "Verstanden"/"ueberprueft" behaelt den Hinweis
        XCTAssertEqual(FedChatLogic.confirm(FedTrust(ident: ip, verified: false, migrated: true), ident: ip, verified: true).migrated, true)
        XCTAssertNil(FedChatLogic.confirm(FedTrust(ident: "X", verified: false, migrated: true), ident: ip, verified: true).migrated)
        // Codable: alte Eintraege ohne migrated bleiben lesbar
        let old = try JSONDecoder().decode(FedTrust.self, from: Data(#"{"ident":"x","verified":true,"changed":false,"dver":2}"#.utf8))
        XCTAssertNil(old.migrated); XCTAssertEqual(old.dver, 2)
    }
}
