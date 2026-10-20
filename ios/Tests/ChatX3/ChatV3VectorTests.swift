import XCTest
import Foundation

// Chat-Stufe 3 (2.5): Testvektoren tests/vectors/chat_v3.json (Server-Repo, Kopie in ios/Tests/vectors) gegen die App-Krypto
// (ChatCryptoV3.swift, ChatCryptoV2.swift) und die Logik (ChatX3Logic.swift, FedChatLogic.tofuMigrate) - wie
// tools/chat_vectors_v3.mjs fuer das Web. Ohne Netz, ohne Geraete.

func loadVector(_ name: String, ext: String = "json") throws -> Data {
    let b = Bundle(for: ChatV3VectorTests.self)
    guard let u = b.url(forResource: name, withExtension: ext) else { throw XCTSkip("Testdatei \(name).\(ext) fehlt im Bundle") }
    return try Data(contentsOf: u)
}

final class ChatV3VectorTests: XCTestCase {
    var v: [String: Any] = [:]
    var signSK: [String: Data] = [:]                 // Geraete-ID -> Ed25519-Schluessel
    var signPK: [String: Data] = [:]
    var devName: [String: String] = [:]              // "a"/"b" -> Geraete-ID

    override func setUpWithError() throws {
        v = try XCTUnwrap(JSONSerialization.jsonObject(with: loadVector("chat_v3")) as? [String: Any])
        for (k, x) in v["devices"] as? [String: [String: Any]] ?? [:] {
            let id = x["id"] as! String, seed = ChatCrypto.unhex(x["sign_seed_hex"] as! String)!
            let kp = ChatCrypto.signKeypair(seed: seed)
            signSK[id] = kp.sk; signPK[id] = kp.pk; devName[k] = id
            XCTAssertEqual(ChatCrypto.b64(kp.pk), x["sign_pk"] as? String, "Geraeteschluessel \(k)")
        }
    }
    private func reaction(_ d: [String: Any]) -> ChatX3.Reaction { ChatX3.Reaction(d)! }
    private var messages: [String: [String: Any]] {
        var m = v["messages"] as? [String: [String: Any]] ?? [:]
        m["e1"] = (v["edit"] as? [String: Any])?["message"] as? [String: Any]
        for (k, x) in v["edits_bad"] as? [String: [String: Any]] ?? [:] { m[k] = x }
        return m
    }

    func testFormatAndIdentities() {
        XCTAssertEqual(v["format"] as? String, "saga-chat/v3")
        for (k, i) in v["identities"] as? [String: [String: Any]] ?? [:] {
            let kp = ChatCrypto.signKeypair(seed: ChatCrypto.unhex(i["ident_seed_hex"] as! String)!)
            XCTAssertEqual(ChatCrypto.b64(kp.pk), i["ident_pk"] as? String, "Identitaet \(k)")
        }
    }

    /// Nachrichten (auch Bearbeitungen) sind saga-chat/v2
    func testMessagesV2() {
        for (name, m) in messages {
            let dev = m["sender_device"] as! String
            let s = ChatCrypto.encryptMessageV2(ref: m["chat_ref"] as! String, msgID: m["id"] as! String, sender: m["sender_handle"] as! String,
                                                device: dev, signSK: signSK[dev]!, ts: (m["ts"] as! NSNumber).int64Value,
                                                replyTo: m["reply_to"] as? String, ai: m["ai"] as? String, plain: Data((m["body_json"] as! String).utf8),
                                                key: ChatCrypto.unb64(m["key"] as? String), nonce: ChatCrypto.unb64(m["nonce"] as? String))
            XCTAssertEqual(s?.bodyCT, m["body_ct"] as? String, "\(name): body_ct")
            XCTAssertEqual(s?.sig, m["sig"] as? String, "\(name): sig")
        }
    }

    func testReactions() throws {
        let rs = try XCTUnwrap(v["reactions"] as? [String: [String: Any]])
        XCTAssertGreaterThanOrEqual(rs.count, 3)
        for (name, r) in rs {
            let K = ChatCrypto.unb64(r["msg_key"] as? String, 32)!
            let ref = r["chat_ref"] as! String, mid = r["msg_id"] as! String, by = r["by"] as! String, dev = r["dev"] as! String
            let ts = (r["ts"] as! NSNumber).int64Value, emoji = r["emoji"] as! String
            XCTAssertEqual(ChatCrypto.hex(ChatCrypto.reactKey(K, ref: ref, msgID: mid)), r["react_key_hex"] as? String, "\(name): K_r")
            XCTAssertEqual(ChatCrypto.hex(ChatCrypto.reactAD(ref: ref, msgID: mid, handle: by, device: dev)), r["ad_hex"] as? String, "\(name): AD")
            XCTAssertEqual(ChatX3.reactBodyJSON(emoji: emoji, ts: ts), r["body_json"] as? String, "\(name): Inhalt kompakt")
            let e = ChatCrypto.encryptReaction(K: K, ref: ref, msgID: mid, handle: by, device: dev, signSK: signSK[dev]!, emoji: emoji, ts: ts,
                                               nonce: ChatCrypto.unb64(r["nonce"] as? String))
            XCTAssertEqual(e?.ct, r["ct"] as? String, "\(name): ct")
            XCTAssertEqual(e?.sig, r["sig"] as? String, "\(name): sig")
            let x = reaction(r)
            XCTAssertEqual(ChatCrypto.hex(ChatCrypto.reactSigMsg(ref: ref, msgID: mid, handle: by, device: dev, ts: ts, ct: x.ct)), r["sig_msg_hex"] as? String)
            XCTAssertTrue(ChatCrypto.verifyReaction(x, ref: ref, msgID: mid, signPK: signPK[dev]), "\(name): Signatur")
            XCTAssertEqual(ChatCrypto.decryptReaction(x, K: K, ref: ref, msgID: mid), emoji, "\(name): oeffnen")
            XCTAssertEqual(ChatCrypto.openReaction(x, K: K, ref: ref, msgID: mid, signPK: signPK[dev]), emoji)
        }
        // Hintergrund-Pruefung wie in der App (je Person die neueste, falsche Schluessel fallen weg)
        var pks: [String: Data] = [:]
        for (_, r) in rs { pks[(r["by"] as! String) + "|" + (r["dev"] as! String)] = signPK[r["dev"] as! String] }
        for (name, r) in rs {
            let job = ChatX3Open.Job(mid: r["msg_id"] as! String, K: ChatCrypto.unb64(r["msg_key"] as? String, 32)!, items: [reaction(r)])
            let out = ChatX3Open.run([job], pks: pks, ref: r["chat_ref"] as! String)[job.mid] ?? []
            XCTAssertEqual(out.first?.e, r["emoji"] as? String, "\(name): ChatX3Open")
        }
    }

    func testEdit() throws {
        let ed = try XCTUnwrap(v["edit"] as? [String: Any]), em = ed["message"] as! [String: Any]
        let om = (v["messages"] as! [String: [String: Any]])[ed["original"] as! String]!
        let pt = try XCTUnwrap(ChatCrypto.decryptMessageV2(ref: em["chat_ref"] as! String, msgID: em["id"] as! String, sender: em["sender_handle"] as! String,
                                                           device: em["sender_device"] as! String, ts: (em["ts"] as! NSNumber).int64Value, replyTo: nil, ai: nil,
                                                           bodyCT: em["body_ct"] as! String, key: ChatCrypto.unb64(em["key"] as? String, 32)!))
        let body = try XCTUnwrap(JSONSerialization.jsonObject(with: pt) as? [String: Any])
        XCTAssertEqual(body["t"] as? String, ed["text"] as? String)
        XCTAssertEqual(em["kind"] as? String, "edit")
        XCTAssertEqual(ChatX3.editBody(msgID: om["id"] as! String, text: ed["text"] as! String), Data((em["body_json"] as! String).utf8), "Inhalt kompakt")
        XCTAssertNil(check(om, em, body))
    }
    private func check(_ o: [String: Any], _ e: [String: Any], _ body: [String: Any]?) -> ChatX3.EditReject? {
        ChatX3.editCheck(origID: o["id"] as! String, origSender: o["sender_handle"] as? String, origAI: (o["ai"] as? String) != nil,
                         origCreated: (o["created"] as? NSNumber)?.doubleValue ?? 0, editOf: e["edit_of"] as? String,
                         editSender: e["sender_handle"] as? String, editCreated: (e["created"] as? NSNumber)?.doubleValue ?? 0, body: body)
    }

    func testReject() throws {
        let rs = v["reactions"] as! [String: [String: Any]], msgs = messages
        var n = 0
        for r in try XCTUnwrap(v["reject"] as? [[String: Any]]) {
            let c = r["case"] as! String
            switch r["what"] as! String {
            case "reaction":
                var x = rs[r["reaction"] as! String]!
                for k in ["by", "dev", "ts"] where r[k] != nil { x[k] = r[k] }
                let rx = reaction(x)
                let pk = (r["sign_pk"] as? String).map { signPK[devName[$0]!]! } ?? signPK[rx.dev]
                XCTAssertFalse(ChatCrypto.verifyReaction(rx, ref: (r["chat_ref"] as? String) ?? (x["chat_ref"] as! String),
                                                         msgID: (r["msg_id"] as? String) ?? (x["msg_id"] as! String), signPK: pk), c)
            case "reaction_open", "reaction_body":
                let m = (v["messages"] as! [String: [String: Any]])[r["msg"] as! String]!
                let x = reaction(r["reaction"] as! [String: Any])
                let K = ChatCrypto.unb64(m["key"] as? String, 32)!
                XCTAssertNil(ChatCrypto.openReaction(x, K: K, ref: m["chat_ref"] as! String, msgID: m["id"] as! String, signPK: signPK[x.dev]), c)
            case "edit":
                let e = msgs[r["edit"] as! String]!, o = (v["messages"] as! [String: [String: Any]])[r["original"] as! String]!
                let pt = ChatCrypto.decryptMessageV2(ref: e["chat_ref"] as! String, msgID: e["id"] as! String, sender: e["sender_handle"] as! String,
                                                     device: e["sender_device"] as! String, ts: (e["ts"] as! NSNumber).int64Value, replyTo: nil,
                                                     ai: e["ai"] as? String, bodyCT: e["body_ct"] as! String, key: ChatCrypto.unb64(e["key"] as? String, 32)!)
                let body = pt.flatMap { (try? JSONSerialization.jsonObject(with: $0)) as? [String: Any] }
                XCTAssertEqual(check(o, e, body)?.rawValue, r["reason"] as? String, c)
            default:                                                       // edit_sig: andere chat_ref
                let e = msgs[r["edit"] as! String]!
                XCTAssertFalse(ChatCrypto.verifyMessageV2(ref: r["chat_ref"] as! String, msgID: e["id"] as! String, sender: e["sender_handle"] as! String,
                                                          device: e["sender_device"] as! String, ts: (e["ts"] as! NSNumber).int64Value, replyTo: nil,
                                                          ai: e["ai"] as? String, bodyCT: e["body_ct"] as! String, sig: e["sig"] as! String,
                                                          signPK: signPK[e["sender_device"] as! String]), c)
            }
            n += 1
        }
        XCTAssertGreaterThanOrEqual(n, 14)
    }

    func testTofuMigrate() throws {
        let rules = try XCTUnwrap((v["tofu_migrate"] as? [String: Any])?["rules"] as? [[String: Any]])
        XCTAssertEqual(rules.count, 6)
        for r in rules {
            let v1 = (r["v1"] as? [String: Any]).map {
                FedChatLogic.V1Trust(ident: $0["ident"] as! String, verified: $0["verified"] as? Bool ?? false, changed: $0["changed"] as? Bool ?? false,
                                     old: $0["old"] as? String)
            }
            let t = FedChatLogic.tofuMigrate(v1, ident: r["seen"] as! String, ver: (r["ver"] as! NSNumber).intValue)
            XCTAssertEqual(t.result.rawValue, r["result"] as? String, r["step"] as! String)
            if let s = r["store"] as? [String: Any] {
                XCTAssertEqual(t.store, FedTrust(ident: s["ident"] as! String, verified: s["verified"] as! Bool, changed: s["changed"] as? Bool ?? false,
                                                 old: s["old"] as? String, dver: (s["dver"] as! NSNumber).intValue), r["step"] as! String)
            } else { XCTAssertNil(t.store, r["step"] as! String) }
        }
    }

    func testLimits() throws {
        let l = try XCTUnwrap(v["limits"] as? [String: Any])
        let c = ChatX3.caps(["features": ["react", "edit", "voice", "chat3"],
                             "x3_limits": ["edit_window": l["edit_window_s"]!, "edit_max": l["edit_max"]!, "emoji_max": l["emoji_max_bytes"]!]])
        XCTAssertTrue(c.react && c.edit && c.voice && c.chat3)
        XCTAssertEqual(c.limits.editWindow, 86_400); XCTAssertEqual(c.limits.editMax, 20); XCTAssertEqual(c.limits.emojiMax, 32)
        XCTAssertFalse(ChatX3.caps(nil).react)
    }
}
