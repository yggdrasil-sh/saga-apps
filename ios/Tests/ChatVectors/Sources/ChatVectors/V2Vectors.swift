import Foundation

// saga-chat/v2 (2.3): tests/vectors/chat_v2.json (Kopie: ios/Tests/vectors/chat_v2.json) gegen App/ChatCryptoV2.swift und
// App/FedChatLogic.swift - dieselben Punkte wie tools/chat_vectors_v2.py check bzw. tools/chat_vectors_v2.mjs.
func v2Vectors(_ path: String) -> [(String, Bool)] {
    let C = ChatCrypto.self
    var res: [(String, Bool)] = []
    func ok(_ n: String, _ c: Bool) { res.append(("v2 " + n, c)) }
    guard let raw = FileManager.default.contents(atPath: path), let v = (try? JSONSerialization.jsonObject(with: raw)) as? [String: Any] else {
        return [("v2 Vektoren lesbar (\(path))", false)]
    }
    func o(_ d: [String: Any], _ k: String) -> [String: Any] { d[k] as? [String: Any] ?? [:] }
    func s(_ d: [String: Any], _ k: String) -> String { d[k] as? String ?? "" }
    func h(_ d: [String: Any], _ k: String) -> Data { C.unhex(s(d, k)) ?? Data() }
    func b(_ d: [String: Any], _ k: String) -> Data { C.unb64(s(d, k)) ?? Data() }
    func n(_ d: [String: Any], _ k: String) -> Int64 { (d[k] as? NSNumber)?.int64Value ?? 0 }
    func opt(_ d: [String: Any], _ k: String) -> String? { d[k] as? String }
    func jsonEq(_ a: Data?, _ b: String) -> Bool {
        guard let a, let x = try? JSONSerialization.jsonObject(with: a), let y = try? JSONSerialization.jsonObject(with: Data(b.utf8)) else { return false }
        return (x as? NSDictionary) == (y as? NSDictionary)
    }
    let lpv = o(v, "lp"), i = o(v, "identity"), hc = o(v, "handle_cert"), d = o(v, "device"), ds = o(v, "device_set")
    let rc = o(v, "recipient"), ms = o(v, "messages"), f = o(v, "file"), sf = o(v, "safety")
    ok("lp", C.hex(C.lp((lpv["fields"] as? [String] ?? []) as [Any])) == s(lpv, "hex"))
    let handle = s(i, "handle")
    let ident = C.signKeypair(seed: h(i, "ident_seed_hex")), backup = C.boxKeypair(seed: h(i, "backup_seed_hex"))
    ok("identity keys", C.b64(ident.pk) == s(i, "ident_pk") && C.b64(ident.sk) == s(i, "ident_sk") && C.b64(backup.pk) == s(i, "backup_pk")
       && C.b64(backup.sk) == s(i, "backup_sk"))
    let hm = C.handleMsg(handle: s(hc, "handle"), identPK: ident.pk, backupPK: backup.pk)
    ok("handle cert", C.hex(hm) == s(hc, "msg_hex") && C.b64(C.sign(hm, sk: ident.sk)) == s(hc, "sig") && C.verify(b(hc, "sig"), hm, pk: ident.pk))
    let enc = C.boxKeypair(seed: h(d, "enc_seed_hex")), sgn = C.signKeypair(seed: h(d, "sign_seed_hex"))
    ok("device keys", C.b64(enc.pk) == s(d, "enc_pk") && C.b64(enc.sk) == s(d, "enc_sk") && C.b64(sgn.pk) == s(d, "sign_pk") && C.b64(sgn.sk) == s(d, "sign_sk"))
    let dm = C.deviceMsgV2(handle: handle, deviceID: s(d, "id"), encPK: enc.pk, signPK: sgn.pk)
    ok("device cert v2", C.hex(dm) == s(d, "msg_hex") && C.b64(C.sign(dm, sk: ident.sk)) == s(d, "fed_sig") && C.verify(b(d, "fed_sig"), dm, pk: ident.pk))
    let dsIDs = ds["ids"] as? [String] ?? [], dsVer = Int(n(ds, "ver"))
    let dsm = C.deviceSetMsg(handle: s(ds, "handle"), ver: dsVer, ids: dsIDs.reversed())
    ok("device set", C.hex(dsm) == s(ds, "msg_hex") && C.b64(C.sign(dsm, sk: ident.sk)) == s(ds, "sig")
       && C.verifyDeviceSet(handle: s(ds, "handle"), identPK: ident.pk, ver: dsVer, ids: dsIDs, sig: s(ds, "sig")))
    // Eintrag wie aus /api/chat/v2/keys: selbst pruefen (Handle-Zertifikat, Geraete, signierte Liste)
    let entry: [String: Any] = ["handle": handle, "ident_pub": s(i, "ident_pk"), "backup_pub": s(i, "backup_pk"), "sig": s(hc, "sig"),
                                "dset": ["ver": dsVer, "ids": dsIDs, "sig": s(ds, "sig")],
                                "devices": [["id": s(d, "id"), "enc_pub": s(d, "enc_pk"), "sign_pub": s(d, "sign_pk"), "fed_sig": s(d, "fed_sig"), "revoked": false],
                                            ["id": "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee", "enc_pub": s(d, "enc_pk"), "sign_pub": s(d, "sign_pk"), "fed_sig": s(d, "fed_sig")]]]
    let ck = C.checkKeysV2(handle: handle, entry: entry)
    ok("checkKeysV2", ck?.ver == dsVer && ck?.signed == true && ck?.devices.map(\.id) == [s(d, "id")] && ck?.active.count == 1
       && C.checkKeysV2(handle: s(rc["identity"] as? [String: Any] ?? [:], "handle"), entry: entry) == nil)
    var notInSet = entry; notInSet["dset"] = ["ver": dsVer, "ids": ["ffffffffffffffffffffffffffffffff"], "sig": s(ds, "sig")]
    ok("checkKeysV2 ungueltige Liste -> revoked vom Server", C.checkKeysV2(handle: handle, entry: notInSet).map { $0.signed == false && $0.active.count == 1 } == true)
    var badSig = entry; badSig["sig"] = s(d, "fed_sig")
    ok("checkKeysV2 falsches Handle-Zertifikat", C.checkKeysV2(handle: handle, entry: badSig) == nil)
    var revokedBySet = entry
    let only = ["ffffffffffffffffffffffffffffffff"]
    revokedBySet["dset"] = ["ver": dsVer + 1, "ids": only, "sig": C.b64(C.sign(C.deviceSetMsg(handle: handle, ver: dsVer + 1, ids: only), sk: ident.sk))]
    ok("checkKeysV2 Geraet nicht in der signierten Liste -> widerrufen",
       C.checkKeysV2(handle: handle, entry: revokedBySet).map { $0.signed && $0.ver == dsVer + 1 && $0.active.isEmpty && $0.devices.count == 1 } == true)

    // Empfaenger (dev:/backup:) - versiegelte Schluessel aus den Vektoren oeffnen
    let ri = o(rc, "identity"), rd = o(rc, "device"), rh = s(ri, "handle")
    let rIdent = C.signKeypair(seed: h(ri, "ident_seed_hex")), rBackup = C.boxKeypair(seed: h(ri, "backup_seed_hex"))
    let rEnc = C.boxKeypair(seed: h(rd, "enc_seed_hex"))
    ok("recipient keys", C.b64(rIdent.pk) == s(ri, "ident_pk") && C.b64(rBackup.pk) == s(ri, "backup_pk") && C.b64(rEnc.pk) == s(rd, "enc_pk"))
    let rk = C.KeysV2(handle: rh, identPK: rIdent.pk, backupPK: rBackup.pk, ver: 1, signed: true,
                      devices: [.init(id: s(rd, "id"), enc: rEnc.pk, sign: Data(count: 32), revoked: false), .init(id: "x", enc: rEnc.pk, sign: Data(), revoked: true)])
    let rids = C.recipientsV2([rk]).map(\.id)
    ok("recipient ids", rids.sorted() == (rc["ids"] as? [String] ?? []).sorted() && rids.count == 2)
    let sealed = o(rc, "sealed"), m1 = o(ms, s(rc, "message"))
    let kDev = C.sealOpen(C.unb64(sealed["dev:\(s(rd, "id"))@\(FedChatLogic.host(of: rh))"] as? String) ?? Data(), pk: rEnc.pk, sk: rEnc.sk)
    let kBak = C.sealOpen(C.unb64(sealed["backup:\(rh)"] as? String) ?? Data(), pk: rBackup.pk, sk: rBackup.sk)
    ok("recipient open dev+backup", kDev == b(m1, "key") && kBak == b(m1, "key"))

    // Nachrichten m1 (reply_to) und m2 (ai)
    for (name, mm) in ms.sorted(by: { $0.key < $1.key }) {
        guard let m = mm as? [String: Any] else { ok("message \(name)", false); continue }
        let ref = s(m, "chat_ref"), mid = s(m, "id"), snd = s(m, "sender_handle"), dev = s(m, "sender_device"), ts = n(m, "ts")
        let rp = opt(m, "reply_to"), ai = opt(m, "ai")
        ok("\(name) ad", C.hex(C.msgADV2(ref: ref, msgID: mid, sender: snd, device: dev, ts: ts, replyTo: rp, ai: ai)) == s(m, "ad_hex"))
        let em = C.encryptMessageV2(ref: ref, msgID: mid, sender: snd, device: dev, signSK: sgn.sk, ts: ts, replyTo: rp, ai: ai,
                                    plain: Data(s(m, "body_json").utf8), key: b(m, "key"), nonce: b(m, "nonce"))
        ok("\(name) encrypt", em?.bodyCT == s(m, "body_ct"))
        ok("\(name) sig", em?.sig == s(m, "sig")
           && C.hex(C.sigMsgV2(ref: ref, msgID: mid, sender: snd, device: dev, ts: ts, replyTo: rp, ai: ai, bodyCT: s(m, "body_ct"))) == s(m, "sig_msg_hex")
           && C.verifyMessageV2(ref: ref, msgID: mid, sender: snd, device: dev, ts: ts, replyTo: rp, ai: ai, bodyCT: s(m, "body_ct"), sig: s(m, "sig"), signPK: sgn.pk))
        ok("\(name) decrypt", jsonEq(C.decryptMessageV2(ref: ref, msgID: mid, sender: snd, device: dev, ts: ts, replyTo: rp, ai: ai, bodyCT: s(m, "body_ct"), key: b(m, "key")),
                                    s(m, "body_json")))
        if name == s(rc, "message") {
            ok("\(name) open (sealed dev)", jsonEq(C.decryptMessageV2(ref: ref, msgID: mid, sender: snd, device: dev, ts: ts, replyTo: rp, ai: ai, bodyCT: s(m, "body_ct"),
                                                                    sealedKey: sealed["dev:\(s(rd, "id"))@\(FedChatLogic.host(of: rh))"] as? String ?? "", pk: rEnc.pk, sk: rEnc.sk), s(m, "body_json")))
        }
        if let ai {   // KI-Hash = SHA-256 des (normalisierten) Textes
            let txt = (try? JSONSerialization.jsonObject(with: Data(s(m, "body_json").utf8)) as? [String: Any])?["text"] as? String ?? ""
            ok("\(name) ai hash", AIChatLogic.sha256Hex(AIChatLogic.normalized(txt)) == ai)
        }
    }
    ok("file", C.encryptFileV2(ref: s(f, "chat_ref"), data: b(f, "plain_b64"), key: b(f, "key"), nonce: b(f, "nonce")).map { C.b64($0.blob) } == s(f, "blob")
       && C.hex(C.lp(C.V2 + "file", s(f, "chat_ref"))) == s(f, "ad_hex")
       && C.decryptFileV2(ref: s(f, "chat_ref"), blob: b(f, "blob"), key: b(f, "key")) == b(f, "plain_b64")
       && C.decryptChatFile(s(f, "chat_ref"), blob: b(f, "blob"), key: b(f, "key")) == b(f, "plain_b64")
       && C.decryptFile(chatID: s(f, "chat_ref"), blob: b(f, "blob"), key: b(f, "key")) == nil)
    ok("safety v2", C.safetyNumberV2(handleA: s(sf, "handle_a"), identA: b(sf, "ident_a"), handleB: s(sf, "handle_b"), identB: b(sf, "ident_b")) == s(sf, "number")
       && C.safetyNumberV2(handleA: s(sf, "handle_b"), identA: b(sf, "ident_b"), handleB: s(sf, "handle_a"), identB: b(sf, "ident_a")) == s(sf, "number"))

    // Faelle, die abgelehnt werden muessen
    for case let r as [String: Any] in (v["reject"] as? [Any] ?? []) {
        let cs = s(r, "case")
        switch s(r, "what") {
        case "handle_cert":
            ok("reject \(cs)", !C.verify(b(r, "sig"), C.handleMsg(handle: s(r, "handle"), identPK: ident.pk, backupPK: backup.pk), pk: ident.pk))
        case "device_cert":
            ok("reject \(cs)", !C.verify(b(r, "fed_sig"), C.deviceMsgV2(handle: s(r, "handle"), deviceID: s(d, "id"), encPK: enc.pk, signPK: sgn.pk), pk: ident.pk))
        case "message":
            let m = o(ms, s(r, "message"))
            let ref = r["chat_ref"] as? String ?? s(m, "chat_ref"), snd = r["sender_handle"] as? String ?? s(m, "sender_handle")
            let ts = r["ts"] != nil ? n(r, "ts") : n(m, "ts")
            let ai: String? = r.keys.contains("ai") ? opt(r, "ai") : opt(m, "ai")
            let sigOK = C.verifyMessageV2(ref: ref, msgID: s(m, "id"), sender: snd, device: s(m, "sender_device"), ts: ts, replyTo: opt(m, "reply_to"), ai: ai,
                                          bodyCT: s(m, "body_ct"), sig: s(m, "sig"), signPK: sgn.pk)
            let opens = C.decryptMessageV2(ref: ref, msgID: s(m, "id"), sender: snd, device: s(m, "sender_device"), ts: ts, replyTo: opt(m, "reply_to"), ai: ai,
                                           bodyCT: s(m, "body_ct"), key: b(m, "key")) != nil
            ok("reject \(cs)", !sigOK && !opens)
        case "device_set":
            ok("reject \(cs)", !C.verifyDeviceSet(handle: s(r, "handle"), identPK: ident.pk, ver: Int(n(r, "ver")), ids: r["ids"] as? [String] ?? [], sig: s(r, "sig")))
        default: ok("reject \(cs) (unbekannt)", false)
        }
    }

    // TOFU (Regeln maschinenlesbar)
    let tf = o(v, "tofu")
    func trust(_ x: Any?) -> FedTrust? {
        guard let t = x as? [String: Any], let id = t["ident"] as? String else { return nil }
        return FedTrust(ident: id, verified: t["verified"] as? Bool ?? false, changed: t["changed"] as? Bool ?? false, old: t["old"] as? String,
                        dver: (t["dver"] as? NSNumber)?.intValue ?? 0)
    }
    for case let r as [String: Any] in (tf["rules"] as? [Any] ?? []) {
        let step = s(r, "step"), seen = s(r, "seen")
        if seen.hasPrefix("invalid") {   // ungueltiges Zertifikat: gar nicht erst bis zu TOFU (checkKeysV2 -> nil)
            ok("tofu \(step)", C.checkKeysV2(handle: handle, entry: badSig) == nil && s(r, "result") == "reject"); continue
        }
        let t = FedChatLogic.tofu(trust(r["stored"]), ident: seen, ver: Int(n(r, "ver")))
        let want = trust(r["store"])
        ok("tofu \(step)", t.result.rawValue == s(r, "result") && (r["store"] == nil || t.store == want))
    }

    // saga-call/v2: versiegeln -> oeffnen -> pruefen (Ziel gebunden), falsches Geraet/Handle scheitert
    let other = C.boxKeypair(), cid = "0123456789abcdef0123456789abcdef"
    let sealedSig = C.sealSignalV2(callID: cid, fromHandle: handle, fromDevice: s(d, "id"), signSK: sgn.sk, toHandle: rh, toDevice: s(rd, "id"),
                                   toPK: rEnc.pk, body: "{\"t\":\"hangup\"}") ?? ""
    let os = C.openSignalV2(sealedSig, pk: rEnc.pk, sk: rEnc.sk)
    ok("call v2 roundtrip", os?.body == "{\"t\":\"hangup\"}"
       && C.verifySignalV2(callID: cid, fromHandle: handle, fromDevice: s(d, "id"), toHandle: rh, toDevice: s(rd, "id"), body: os?.body ?? "", sig: os?.sig ?? Data(), signPK: sgn.pk)
       && !C.verifySignalV2(callID: cid, fromHandle: handle, fromDevice: s(d, "id"), toHandle: handle, toDevice: s(rd, "id"), body: os?.body ?? "", sig: os?.sig ?? Data(), signPK: sgn.pk)
       && C.openSignalV2(sealedSig, pk: other.pk, sk: other.sk) == nil
       && C.openSignal(sealedSig, pk: rEnc.pk, sk: rEnc.sk) == nil)
    ok("call v2 msg", C.signalMsgV2(callID: "c", fromHandle: "a", fromDevice: "b", toHandle: "d", toDevice: "e", body: "f")
       == C.lp("saga-call/v2/signal", "c", "a", "b", "d", "e", "f"))
    return res
}
