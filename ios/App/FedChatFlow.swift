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

    /// Abfrage fuer GET /api/chat/v2/keys (hoechstens 20, sortiert, ohne Doppelte)
    static func keysPath(_ handles: [String], fresh: Bool) -> String? {
        let hs = Array(Set(handles)).sorted().prefix(20)
        guard !hs.isEmpty else { return nil }
        var allowed = CharacterSet.alphanumerics; allowed.insert(charactersIn: "~.-_:")
        return "api/chat/v2/keys?handles=" + hs.map { $0.addingPercentEncoding(withAllowedCharacters: allowed) ?? $0 }.joined(separator: ",") + (fresh ? "&fresh=1" : "")
    }
    /// Antwort selbst pruefen + TOFU: eigene Schluessel muessen zu diesem Geraet passen; neue Vertrauensstaende ueber `save`
    static func evaluate(_ handles: [String], keys all: [String: Any], myHandle: String?, myIdentPK: Data,
                         stored: (String) -> FedTrust?, save: (String, FedTrust) -> Void) -> [String: FedKeyState] {
        var out: [String: FedKeyState] = [:]
        for h in Set(handles) {
            guard let e = all[h] as? [String: Any] else { out[h] = FedKeyState(keys: nil, verdict: .missing); continue }
            guard let ck = ChatCrypto.checkKeysV2(handle: h, entry: e) else { out[h] = FedKeyState(keys: nil, verdict: .reject); continue }
            let stale = ShareJSON.bool(e["stale"])
            if h == myHandle {
                out[h] = FedKeyState(keys: ck, verdict: ck.identPK == myIdentPK ? .trust : .reject, stale: stale); continue
            }
            let st = stored(h)
            let t = FedChatLogic.tofu(st, ident: ck.identPub, ver: ck.ver)
            if let n = t.store { save(h, n) }
            out[h] = FedKeyState(keys: ck, verdict: t.result, stale: stale, stored: t.store ?? st)
        }
        return out
    }

    // MARK: Nachrichten

    /// Nachricht v2 fuer POST /api/chats/{id}/messages: verschluesselt, signiert, K je aktivem Geraet + backup:<handle> versiegelt
    static func sendBody(info: FedChatInfo, own: Own, keys: [String: FedKeyState], plain: Data, replyTo: String?,
                         ai: (hash: String, reply: String)?, fileIDs: [String]) -> (body: [String: Any], id: String)? {
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
        return (body, mid)
    }
    /// Nachricht v2 oeffnen; echt = Absender-Geraet aus den selbst geprueften Schluesseln von sender_handle + Signatur v2
    static func open(_ m: [String: Any], ref: String, own: Own, keys fk: [String: FedKeyState]) -> (plain: Data?, verified: Bool)? {
        guard let mid = m["id"] as? String, let body = m["body_ct"] as? String, let key = m["key"] as? String else { return nil }
        let sender = m["sender_handle"] as? String ?? "", dev = m["sender_device"] as? String ?? ""
        let ts = (m["ts"] as? NSNumber)?.int64Value ?? 0
        let reply = ShareJSON.str(m["reply_to"]), ai = ShareJSON.str(m["ai"])
        let pair = (m["key_for"] as? String) == "backup" ? own.backup : own.enc
        let pt = ChatCrypto.decryptMessageV2(ref: ref, msgID: mid, sender: sender, device: dev, ts: ts, replyTo: reply, ai: ai,
                                             bodyCT: body, sealedKey: key, pk: pair.pk, sk: pair.sk)
        var ok = false
        if let f = fk[sender], f.verdict != .reject, let d = f.keys?.devices.first(where: { $0.id == dev }) {
            ok = ChatCrypto.verifyMessageV2(ref: ref, msgID: mid, sender: sender, device: dev, ts: ts, replyTo: reply, ai: ai,
                                            bodyCT: body, sig: m["sig"] as? String ?? "", signPK: d.sign)
        }
        return (pt, ok)
    }
}
