import Foundation
import Clibsodium

// Chats ueber Servergrenzen (Saga 2.3): Krypto-Format saga-chat/v2 und Anruf-Signale saga-call/v2 - genau wie
// static/chat-crypto.js (handleSig, deviceSigV2, deviceSetSig, checkKeysV2, encryptMessageV2, safetyNumberV2, sealSignalV2 …)
// und saga/chat_v2.py. Gebunden an den foederierten Handle ('~x@host') und chat_ref ('<32 hex>@<heim>') statt an den lokalen
// Benutzernamen und die lokale chat_id. Identitaets-, Backup- und Geraeteschluessel sind dieselben wie in v1.
// Geprueft gegen tests/vectors/chat_v2.json (ios/Tests/ChatVectors, Kopie in ios/Tests/vectors). Lokale Chats bleiben v1.

extension ChatCrypto {
    static let V2 = "saga-chat/v2/"
    static let VC2 = "saga-call/v2/"

    // MARK: Zertifikate

    /// Handle-Zertifikat: sig = sign(LP(V2+"handle", handle, ident_pk, backup_pk), ident_sk)
    static func handleMsg(handle: String, identPK: Data, backupPK: Data) -> Data { lp(V2 + "handle", handle, identPK, backupPK) }
    /// Geraete-Zertifikat v2: fed_sig = sign(LP(V2+"device", handle, device_id, enc_pk, sign_pk), ident_sk)
    static func deviceMsgV2(handle: String, deviceID: String, encPK: Data, signPK: Data) -> Data { lp(V2 + "device", handle, deviceID, encPK, signPK) }
    /// Signierte Geraete-Liste: LP(V2+"devices", handle, ver, ids sortiert mit "," verbunden)
    static func deviceSetMsg(handle: String, ver: Int, ids: [String]) -> Data {
        lp(V2 + "devices", handle, String(ver), ids.sorted { Array($0.utf8).lexicographicallyPrecedes(Array($1.utf8)) }.joined(separator: ","))
    }
    static func verifyDeviceSet(handle: String, identPK: Data, ver: Int, ids: [String], sig: String?) -> Bool {
        ver >= 1 && verify(unb64(sig, 64), deviceSetMsg(handle: handle, ver: ver, ids: ids), pk: identPK)
    }

    /// Geprueftes Geraet einer Person (v2)
    struct DeviceV2: Equatable { var id: String; var enc: Data; var sign: Data; var revoked: Bool }
    /// Selbst gepruefter Eintrag aus GET /api/chat/v2/keys
    struct KeysV2: Equatable {
        var handle: String; var identPK: Data; var backupPK: Data
        /// Version der gueltigen signierten Geraete-Liste (0 = ohne, Altbestand)
        var ver: Int; var signed: Bool
        var devices: [DeviceV2]
        var identPub: String { b64(identPK) }
        var active: [DeviceV2] { devices.filter { !$0.revoked } }
    }
    /// Eintrag pruefen (der eigene Server koennte luegen): Handle-Format ('~' + 16 Zeichen a-z2-7 + '@host') und Handle-
    /// Zertifikat gegen ident_pub mit genau dem angefragten Handle (sonst nil), Geraete-IDs eindeutig (doppelte IDs = nil:
    /// sonst waere unklar, welcher Schluessel gilt), jedes Geraet gegen fed_sig (ungueltige fallen weg); aktiv nur, wer in
    /// der gueltigen signierten Geraete-Liste steht und nicht widerrufen ist (ohne gueltige Liste: revoked vom Server).
    static func checkKeysV2(handle: String, entry e: [String: Any]?) -> KeysV2? {
        guard let e, FedChatLogic.isHandle(handle), e["handle"] as? String == handle,
              FedChatLogic.uniqueDeviceIDs(e["devices"]),
              let ip = unb64(e["ident_pub"] as? String, 32), let bp = unb64(e["backup_pub"] as? String, 32),
              verify(unb64(e["sig"] as? String, 64), handleMsg(handle: handle, identPK: ip, backupPK: bp), pk: ip) else { return nil }
        var set: (ver: Int, ids: [String])?
        if let ds = e["dset"] as? [String: Any], let ver = (ds["ver"] as? NSNumber)?.intValue, let ids = ds["ids"] as? [String],
           verifyDeviceSet(handle: handle, identPK: ip, ver: ver, ids: ids, sig: ds["sig"] as? String) { set = (ver, ids) }
        let devs = (e["devices"] as? [Any] ?? []).compactMap { v -> DeviceV2? in
            guard let d = v as? [String: Any], let id = d["id"] as? String, let enc = unb64(d["enc_pub"] as? String, 32),
                  let sg = unb64(d["sign_pub"] as? String, 32),
                  verify(unb64(d["fed_sig"] as? String, 64), deviceMsgV2(handle: handle, deviceID: id, encPK: enc, signPK: sg), pk: ip) else { return nil }
            let revoked = ((d["revoked"] as? Bool) ?? false) || (set.map { !$0.ids.contains(id) } ?? false)
            return DeviceV2(id: id, enc: enc, sign: sg, revoked: revoked)
        }
        return KeysV2(handle: handle, identPK: ip, backupPK: bp, ver: set?.ver ?? 0, signed: set != nil, devices: devs)
    }
    /// Empfaenger: je Person backup:<handle>, je aktivem Geraet dev:<id>@<host aus dem Handle>
    static func recipientsV2(_ entries: [KeysV2]) -> [(id: String, pk: Data)] {
        var out: [(String, Data)] = []
        for e in entries {
            let host = FedChatLogic.host(of: e.handle)
            out.append(("backup:" + e.handle, e.backupPK))
            for d in e.active { out.append(("dev:" + d.id + "@" + host, d.enc)) }
        }
        return out
    }

    // MARK: Nachrichten

    static func msgADV2(ref: String, msgID: String, sender: String, device: String, ts: Int64, replyTo: String?, ai: String?) -> Data {
        lp(V2 + "msg", ref, msgID, sender, device, String(ts), replyTo ?? "", ai ?? "")
    }
    static func sigMsgV2(ref: String, msgID: String, sender: String, device: String, ts: Int64, replyTo: String?, ai: String?, bodyCT: String) -> Data {
        lp(V2 + "sig", ref, msgID, sender, device, String(ts), replyTo ?? "", ai ?? "", gh(unb64(bodyCT) ?? Data()))
    }
    /// Inhalt verschluesseln + signieren (K und nonce nur fuer Testvektoren vorgeben); `ai` = Hex-SHA-256 einer KI-Antwort
    static func encryptMessageV2(ref: String, msgID: String, sender: String, device: String, signSK: Data, ts: Int64, replyTo: String?,
                                 ai: String?, plain: Data, key: Data? = nil, nonce: Data? = nil) -> Sealed? {
        let k = key ?? aeadKey(), n = nonce ?? random(24)
        guard let ct = aeadEncrypt(plain, ad: msgADV2(ref: ref, msgID: msgID, sender: sender, device: device, ts: ts, replyTo: replyTo, ai: ai), nonce: n, key: k)
        else { return nil }
        let body = b64(n + ct)
        let sig = sign(sigMsgV2(ref: ref, msgID: msgID, sender: sender, device: device, ts: ts, replyTo: replyTo, ai: ai, bodyCT: body), sk: signSK)
        return Sealed(bodyCT: body, sig: b64(sig), key: k)
    }
    /// Nachricht v2 oeffnen: versiegelten Schluessel mit (enc|backup)-Schluessel, dann Inhalt
    static func decryptMessageV2(ref: String, msgID: String, sender: String, device: String, ts: Int64, replyTo: String?, ai: String?,
                                 bodyCT: String, sealedKey: String, pk: Data, sk: Data) -> Data? {
        guard let s = unb64(sealedKey, 80), let k = sealOpen(s, pk: pk, sk: sk) else { return nil }
        return decryptMessageV2(ref: ref, msgID: msgID, sender: sender, device: device, ts: ts, replyTo: replyTo, ai: ai, bodyCT: bodyCT, key: k)
    }
    static func decryptMessageV2(ref: String, msgID: String, sender: String, device: String, ts: Int64, replyTo: String?, ai: String?,
                                 bodyCT: String, key: Data) -> Data? {
        guard let raw = unb64(bodyCT), raw.count > 24 else { return nil }
        return aeadDecrypt(raw.dropFirst(24), ad: msgADV2(ref: ref, msgID: msgID, sender: sender, device: device, ts: ts, replyTo: replyTo, ai: ai),
                           nonce: raw.prefix(24), key: key)
    }
    static func verifyMessageV2(ref: String, msgID: String, sender: String, device: String, ts: Int64, replyTo: String?, ai: String?,
                                bodyCT: String, sig: String, signPK: Data?) -> Bool {
        verify(unb64(sig, 64), sigMsgV2(ref: ref, msgID: msgID, sender: sender, device: device, ts: ts, replyTo: replyTo, ai: ai, bodyCT: bodyCT), pk: signPK)
    }

    // MARK: Anhaenge, Sicherheitsnummer

    /// Anhang v2: nonce24 || AEAD(datei, AD = LP("saga-chat/v2/file", chat_ref))
    static func encryptFileV2(ref: String, data: Data, key: Data? = nil, nonce: Data? = nil) -> (blob: Data, key: Data)? {
        let k = key ?? aeadKey(), n = nonce ?? random(24)
        guard let ct = aeadEncrypt(data, ad: lp(V2 + "file", ref), nonce: n, key: k) else { return nil }
        return (n + ct, k)
    }
    static func decryptFileV2(ref: String, blob: Data, key: Data) -> Data? {
        guard blob.count > 24 else { return nil }
        return aeadDecrypt(blob.dropFirst(24), ad: lp(V2 + "file", ref), nonce: blob.prefix(24), key: key)
    }
    /// Kennung eines Chats fuer Anhaenge: v1 die lokale chat_id (Zahl), v2 die chat_ref ("<32 hex>@host")
    static func isRef(_ cid: String) -> Bool { cid.contains("@") }
    /// Anhang ver-/entschluesseln je nach Format (v1: chat_id, v2: chat_ref)
    static func encryptChatFile(_ cid: String, data: Data, key: Data? = nil) -> (blob: Data, key: Data)? {
        isRef(cid) ? encryptFileV2(ref: cid, data: data, key: key) : encryptFile(chatID: cid, data: data, key: key)
    }
    static func decryptChatFile(_ cid: String, blob: Data, key: Data) -> Data? {
        isRef(cid) ? decryptFileV2(ref: cid, blob: blob, key: key) : decryptFile(chatID: cid, blob: blob, key: key)
    }
    /// Sicherheitsnummer v2 (60 Ziffern) aus beiden (Handle, ident_pk), unabhaengig von der Reihenfolge
    static func safetyNumberV2(handleA: String, identA: Data, handleB: String, identB: Data) -> String {
        var a = (handleA, identA), b = (handleB, identB)
        if Array(b.0.utf8).lexicographicallyPrecedes(Array(a.0.utf8)) { swap(&a, &b) }
        return digits(gh(lp(V2 + "safety", a.0, a.1, b.0, b.1), 64), blocks: 12)
    }

    // MARK: Anrufe saga-call/v2

    static func signalMsgV2(callID: String, fromHandle: String, fromDevice: String, toHandle: String, toDevice: String, body: String) -> Data {
        lp(VC2 + "signal", callID, fromHandle, fromDevice, toHandle, toDevice, body)
    }
    static func signalInnerV2(body: String, sig: Data) -> String {
        "{\"v\":2,\"body\":" + CallLogic.jsonString(body) + ",\"sig\":\"" + b64(sig) + "\"}"
    }
    static func sealSignalV2(callID: String, fromHandle: String, fromDevice: String, signSK: Data, toHandle: String, toDevice: String,
                             toPK: Data, body: String) -> String? {
        let sig = sign(signalMsgV2(callID: callID, fromHandle: fromHandle, fromDevice: fromDevice, toHandle: toHandle, toDevice: toDevice, body: body), sk: signSK)
        return seal(Data(signalInnerV2(body: body, sig: sig).utf8), pk: toPK).map(b64)
    }
    static func openSignalV2(_ sealed: String, pk: Data, sk: Data) -> (body: String, sig: Data)? {
        guard let raw = unb64(sealed), let pt = sealOpen(raw, pk: pk, sk: sk),
              let j = (try? JSONSerialization.jsonObject(with: pt)) as? [String: Any],
              (j["v"] as? NSNumber)?.intValue == 2, let body = j["body"] as? String, let sig = unb64(j["sig"] as? String, 64) else { return nil }
        return (body, sig)
    }
    static func verifySignalV2(callID: String, fromHandle: String, fromDevice: String, toHandle: String, toDevice: String, body: String,
                               sig: Data, signPK: Data?) -> Bool {
        verify(sig, signalMsgV2(callID: callID, fromHandle: fromHandle, fromDevice: fromDevice, toHandle: toHandle, toDevice: toDevice, body: body), pk: signPK)
    }
}
