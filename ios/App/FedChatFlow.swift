import Foundation

// Chats ueber Servergrenzen (Saga 2.3): die Ablaeufe der App ohne Netz und UI - eigene Zertifikate v2, Schluessel anderer
// Personen pruefen + TOFU, Nachricht v2 bauen (Empfaenger dev:/backup:), Nachricht v2 oeffnen und pruefen, Anruf-Signale.
// Genutzt von ChatFed.swift/ChatConversation.swift (App) und vom E2E-Test ios/Tests/FedE2E (gegen zwei verbundene Server).

/// Selbst gepruefte Schluessel einer Person (v2) + TOFU-Ergebnis
struct FedKeyState: Equatable {
    var keys: ChatCrypto.KeysV2?
    var verdict: FedChatLogic.Verdict
    var stale = false
    var stored: FedTrust?
    var verified: Bool { FedChatLogic.isVerified(stored, ident: keys?.identPub, verdict: verdict) }
}

enum FedFlow {
    /// Eigene Schluessel dieses Geraets (aus ChatKeys)
    struct Own {
        var uid: String; var deviceID: String
        var enc: ChatCrypto.KeyPair; var sign: ChatCrypto.KeyPair
        var identSK: Data; var backup: ChatCrypto.KeyPair
        var identPK: Data { identSK.suffix(32) }
    }
    /// Geraet aus GET /api/chat/me (v1-Zertifikat)
    struct V1Device { var id: String; var encPub: String; var signPub: String; var sig: String; var revoked: Bool }

    // MARK: Eigene Zertifikate (PUT /api/chat/v2/cert)

    static func missing(_ v: [String: Any]) -> Set<String> { Set((v["missing"] as? [Any] ?? []).compactMap { $0 as? String }) }
    /// Brauchen wir die Geraete-Liste v1 (andere eigene Geraete ohne Zertifikat v2)?
    static func needsDevices(_ v: [String: Any], own: Own) -> Bool { !missing(v).subtracting([own.deviceID]).isEmpty }
    /// Zertifikate fuer den Handle und fehlende Geraete; andere eigene Geraete nur mit gueltigem v1-Zertifikat. Leer = nichts zu tun
    static func certBody(_ v: [String: Any], own: Own, devices: [V1Device]) -> [String: Any] {
        guard let h = ShareJSON.str(v["handle"]) else { return [:] }
        let miss = missing(v)
        var body: [String: Any] = [:]
        if !ShareJSON.bool(v["cert_ok"]) {
            body["handle_sig"] = ChatCrypto.b64(ChatCrypto.sign(ChatCrypto.handleMsg(handle: h, identPK: own.identPK, backupPK: own.backup.pk), sk: own.identSK))
        }
        var devs: [String: String] = [:]
        if miss.contains(own.deviceID) {
            devs[own.deviceID] = ChatCrypto.b64(ChatCrypto.sign(ChatCrypto.deviceMsgV2(handle: h, deviceID: own.deviceID, encPK: own.enc.pk, signPK: own.sign.pk), sk: own.identSK))
        }
        for dv in devices where !dv.revoked && dv.id != own.deviceID && miss.contains(dv.id) {
            guard let e = ChatCrypto.unb64(dv.encPub, 32), let s = ChatCrypto.unb64(dv.signPub, 32),
                  ChatCrypto.verify(ChatCrypto.unb64(dv.sig), ChatCrypto.deviceMsg(uid: own.uid, deviceID: dv.id, encPK: e, signPK: s), pk: own.identPK) else { continue }
            devs[dv.id] = ChatCrypto.b64(ChatCrypto.sign(ChatCrypto.deviceMsgV2(handle: h, deviceID: dv.id, encPK: e, signPK: s), sk: own.identSK))
        }
        if !devs.isEmpty { body["devices"] = devs }
        return body
    }
    /// Signierte Liste der aktiven Geraete (nach dem Zertifikat), nil = passt schon
    static func deviceSetBody(_ v: [String: Any], own: Own) -> [String: Any]? {
        let want = (v["device_set_want"] as? [Any] ?? []).compactMap { $0 as? String }.sorted()
        guard let h = ShareJSON.str(v["handle"]), ShareJSON.bool(v["cert_ok"]), !ShareJSON.bool(v["device_set_ok"]), !want.isEmpty else { return nil }
        let ver = (ShareJSON.int((v["device_set"] as? [String: Any])?["ver"]) ?? 0) + 1
        let sig = ChatCrypto.sign(ChatCrypto.deviceSetMsg(handle: h, ver: ver, ids: want), sk: own.identSK)
        return ["device_set": ["ver": ver, "ids": want, "sig": ChatCrypto.b64(sig)]]
    }

    // MARK: Schluessel anderer Personen

    /// Abfrage(n) fuer GET /api/chat/v2/keys (FedChatLogic, hoechstens 20 Handles je Abfrage)
    static func keysPath(_ handles: [String], fresh: Bool) -> String? { FedChatLogic.keysPath(handles, fresh: fresh) }
    static func keysPaths(_ handles: [String], fresh: Bool) -> [String] { FedChatLogic.keysPaths(handles, fresh: fresh) }
    /// Antwort selbst pruefen + TOFU: eigene Schluessel muessen zu diesem Geraet passen; neue Vertrauensstaende ueber `save`
    /// `v1`: 2.5 - noch kein Eintrag trust2:<handle>, aber ein v1-Stand (trust:<benutzer>, Zuordnung aus members[].handle
    /// der Chats v1): Vertrauen nur bei gleichem Identitaetsschluessel uebernehmen (FedChatLogic.tofuMigrate)
    static func evaluate(_ handles: [String], keys all: [String: Any], myHandle: String?, myIdentPK: Data,
                         stored: (String) -> FedTrust?, save: (String, FedTrust) -> Void,
                         v1: (String) -> FedChatLogic.V1Trust? = { _ in nil }) -> [String: FedKeyState] {
        var out: [String: FedKeyState] = [:]
        for h in Set(handles) {
            guard let e = all[h] as? [String: Any] else { out[h] = FedKeyState(keys: nil, verdict: .missing); continue }
            guard let ck = ChatCrypto.checkKeysV2(handle: h, entry: e) else { out[h] = FedKeyState(keys: nil, verdict: .reject); continue }
            let stale = ShareJSON.bool(e["stale"])
            if h == myHandle {
                out[h] = FedKeyState(keys: ck, verdict: ck.identPK == myIdentPK ? .trust : .reject, stale: stale); continue
            }
            let st = stored(h)
            var t = FedChatLogic.tofu(st, ident: ck.identPub, ver: ck.ver)
            if st == nil, let old = v1(h) {
                t = FedChatLogic.tofuMigrate(old, ident: ck.identPub, ver: ck.ver)
                t.store?.migrated = true
            }
            if let n = t.store { save(h, n) }
            out[h] = FedKeyState(keys: ck, verdict: t.result, stale: stale, stored: t.store ?? st)
        }
        return out
    }

    // MARK: Nachrichten

    /// Nachricht v2 fuer POST /api/chats/{id}/messages: verschluesselt, signiert, K je aktivem Geraet + backup:<handle> versiegelt
    /// `kind`: "msg" bzw. "title" (Name einer freien Gruppe, E5 - wie in v1 eine verschluesselte Nachricht)
    static func sendBody(info: FedChatInfo, own: Own, keys: [String: FedKeyState], plain: Data, replyTo: String?,
                         ai: (hash: String, reply: String)?, fileIDs: [String], kind: String = "msg",
                         extra: [String: Any] = [:]) -> (body: [String: Any], id: String)? {
        let ents = info.handles.compactMap { keys[$0]?.keys }
        let mid = ChatCrypto.hexID(), ts = Int64(Date().timeIntervalSince1970 * 1000)
        guard let sealed = ChatCrypto.encryptMessageV2(ref: info.ref, msgID: mid, sender: info.myHandle, device: own.deviceID, signSK: own.sign.sk, ts: ts,
                                                       replyTo: replyTo, ai: ai?.hash, plain: plain) else { return nil }
        var sk: [String: String] = [:]
        for r in ChatCrypto.recipientsV2(ents) {
            guard let s = ChatCrypto.seal(sealed.key, pk: r.pk) else { return nil }
            sk[r.id] = ChatCrypto.b64(s)
        }
        var body: [String: Any] = ["v": 2, "id": mid, "ts": ts, "sender_device": own.deviceID, "body_ct": sealed.bodyCT, "sig": sealed.sig,
                                   "keys": sk, "files": fileIDs]
        if let replyTo { body["reply_to"] = replyTo }
        if let ai { body["ai"] = ai.hash; body["ai_reply"] = ai.reply }
        if kind == "title" || kind == "edit" { body["kind"] = kind }       // 2.5: Bearbeitung (mit edit_of in `extra`)
        for (k, v) in extra { body[k] = v }
        return (body, mid)
    }
    /// Nachricht v1 oeffnen + Echtheit pruefen (Geraet gehoert dem Absender, Zertifikat gegen dessen Identitaet, Signatur);
    /// devices/idents aus der Antwort (title_msg: device + ident_pub direkt). Auch fuer alte Nachrichten umgestellter Chats.
    static func openV1(_ m: [String: Any], chatID: String, own: Own, devices: [String: Any]?, idents: [String: Any]?) -> (plain: Data?, verified: Bool)? {
        guard let mid = m["id"] as? String, let body = m["body_ct"] as? String, let key = m["key"] as? String else { return nil }
        let sender = ((m["sender"] as? [String: Any])?["username"] as? String) ?? ""
        let dev = m["sender_device"] as? String ?? ""
        let ts = (m["ts"] as? NSNumber)?.int64Value ?? 0
        let reply = ShareJSON.str(m["reply_to"])
        let pair = (m["key_for"] as? String) == "backup" ? own.backup : own.enc
        let pt = ChatCrypto.decryptMessage(chatID: chatID, msgID: mid, sender: sender, device: dev, ts: ts, replyTo: reply,
                                           bodyCT: body, sealedKey: key, pk: pair.pk, sk: pair.sk)
        let d = (devices?[dev] as? [String: Any]) ?? (m["device"] as? [String: Any])
        let ip = (idents?[sender] as? String) ?? (m["ident_pub"] as? String)
        var ok = false
        if let d, (d["uid"] as? String ?? sender) == sender, let ipk = ChatCrypto.unb64(ip, 32),
           let enc = ChatCrypto.unb64(d["enc_pub"] as? String, 32), let sg = ChatCrypto.unb64(d["sign_pub"] as? String, 32),
           ChatCrypto.verify(ChatCrypto.unb64(d["sig"] as? String), ChatCrypto.deviceMsg(uid: sender, deviceID: dev, encPK: enc, signPK: sg), pk: ipk) {
            ok = ChatCrypto.verifyMessage(chatID: chatID, msgID: mid, sender: sender, device: dev, ts: ts, replyTo: reply, bodyCT: body,
                                          sig: m["sig"] as? String ?? "", signPK: sg)
        }
        return (pt, ok)
    }
    /// Nachricht eines auf v2 umgestellten Seiten-Chats aus der Zeit davor (FedChatLogic.isLegacyV1)
    static func isLegacyV1(_ m: [String: Any]) -> Bool { FedChatLogic.isLegacyV1(m) }
    /// Nachrichten-Schluessel K (fuer Reaktionen, 2.5): versiegelten Schluessel mit (enc|backup)-Schluessel oeffnen
    static func messageKey(_ m: [String: Any], own: Own) -> Data? {
        guard let key = m["key"] as? String, let s = ChatCrypto.unb64(key, 80) else { return nil }
        let pair = (m["key_for"] as? String) == "backup" ? own.backup : own.enc
        return ChatCrypto.sealOpen(s, pk: pair.pk, sk: pair.sk)
    }
    /// Nachricht v2 oeffnen; echt = Absender-Geraet aus den selbst geprueften Schluesseln von sender_handle + Signatur v2
    static func open(_ m: [String: Any], ref: String, own: Own, keys fk: [String: FedKeyState]) -> (plain: Data?, verified: Bool)? {
        open(m, ref: ref, own: own, keys: fk, key: nil).map { ($0.plain, $0.verified) }
    }
    /// wie open, mit schon geoeffnetem K (`key`, sonst aus der Nachricht) - liefert K mit (Reaktionen, 2.5)
    static func open(_ m: [String: Any], ref: String, own: Own, keys fk: [String: FedKeyState], key K: Data?) -> (plain: Data?, verified: Bool, key: Data?)? {
        guard let mid = m["id"] as? String, let body = m["body_ct"] as? String, m["key"] is String else { return nil }
        let sender = m["sender_handle"] as? String ?? "", dev = m["sender_device"] as? String ?? ""
        let ts = (m["ts"] as? NSNumber)?.int64Value ?? 0
        let reply = ShareJSON.str(m["reply_to"]), ai = ShareJSON.str(m["ai"])
        let k = K ?? messageKey(m, own: own)
        let pt = k.flatMap { ChatCrypto.decryptMessageV2(ref: ref, msgID: mid, sender: sender, device: dev, ts: ts, replyTo: reply, ai: ai, bodyCT: body, key: $0) }
        var ok = false
        if let f = fk[sender], f.verdict != .reject, let d = f.keys?.devices.first(where: { $0.id == dev }) {
            ok = ChatCrypto.verifyMessageV2(ref: ref, msgID: mid, sender: sender, device: dev, ts: ts, replyTo: reply, ai: ai,
                                            bodyCT: body, sig: m["sig"] as? String ?? "", signPK: d.sign)
        }
        return (pt, ok, k)
    }
}
