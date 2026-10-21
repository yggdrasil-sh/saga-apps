import Foundation

// Chats ueber Servergrenzen (Saga 2.3, docs/APP-API-FED.md): eigene Zertifikate v2 hochladen (Handle, Geraete, signierte
// Geraete-Liste), Schluessel anderer Personen ueber den eigenen Server holen und SELBST pruefen (der Server koennte luegen),
// TOFU v2, Nachrichten v2 oeffnen. Nur dieses Geraet kennt ident_sk. Ablaeufe: FedChatFlow.swift, Krypto: ChatCryptoV2.swift.

extension ChatKeys {
    var fedOwn: FedFlow.Own {
        FedFlow.Own(uid: uid, deviceID: deviceID, enc: enc, sign: sign, identSK: identSKData, backup: .init(pk: backupPK, sk: backupSKData))
    }
}

extension ChatModel {
    /// Eigene Zertifikate v2 (still im Hintergrund; nach Einrichtung, neuem/entferntem Geraet, chat.keys.changed own)
    func fedCerts() async {
        guard let store, let k = keys, !fedCertsRunning else { return }
        fedCertsRunning = true; defer { fedCertsRunning = false }
        guard let (d, code) = try? await store.apiResponse("api/chat/v2/me"), code == 200, var v = ShareJSON.obj(d) else { return }   // 2.1/2.2: 404
        guard let h = ShareJSON.str(v["handle"]) else { return }
        myHandle = h
        guard ShareJSON.bool(v["need"]) else { return }
        let own = k.fedOwn
        var devs: [FedFlow.V1Device] = []
        if FedFlow.needsDevices(v, own: own), let (md, mc) = try? await store.apiResponse("api/chat/me?device=\(k.deviceID)"), mc == 200, let m = ChatMe(data: md) {
            devs = m.devices.map { FedFlow.V1Device(id: $0.id, encPub: $0.encPub, signPub: $0.signPub, sig: $0.sig, revoked: $0.revoked) }
        }
        let body = FedFlow.certBody(v, own: own, devices: devs)
        if !body.isEmpty {
            guard let (rd, rc) = try? await store.apiResponse("api/chat/v2/cert", method: "PUT", body: body), rc == 200, let r = ShareJSON.obj(rd) else { return }
            v = r
        }
        if let ds = FedFlow.deviceSetBody(v, own: own) { _ = try? await store.apiResponse("api/chat/v2/cert", method: "PUT", body: ds) }
    }

    /// Schluessel zu Handles holen (hoechstens 20), selbst pruefen, TOFU anwenden (neue Eintraege werden gespeichert)
    func fedKeys(_ handles: [String], fresh: Bool = false, own: String? = nil) async throws -> [String: FedKeyState] {
        let paths = FedFlow.keysPaths(handles, fresh: fresh)
        guard let store, !paths.isEmpty else { return [:] }
        if let own, !own.isEmpty { myHandle = own }                     // aus dem Chat (my_handle) - nie als fremde Person behandeln
        var all: [String: Any] = [:]
        for p in paths {                                                // Gruppen mit mehr als 20 Personen: in Teilen
            let d = try await store.api(p)
            all.merge(ShareJSON.obj(d)?["keys"] as? [String: Any] ?? [:]) { $1 }
        }
        let uid = me.uid
        return FedFlow.evaluate(handles, keys: all, myHandle: myHandle, myIdentPK: keys?.identPK ?? Data(),
                                stored: { FedTrustStore.get(uid, $0) }, save: { FedTrustStore.set(uid, $0, $1) },
                                v1: { ChatH2UStore.v1Trust(uid, handle: $0) })            // 2.5: Vertrauen aus v1 uebernehmen
    }
    /// Eigener Handle (fuer eingehende Anrufe ueber Servergrenzen); nil = Server ohne Foederation 2.3
    func fedMyHandle() async -> String? {
        if let h = myHandle { return h }
        guard let store, let (d, code) = try? await store.apiResponse("api/chat/v2/me"), code == 200,
              let h = ShareJSON.obj(d).flatMap({ ShareJSON.str($0["handle"]) }) else { return nil }
        myHandle = h
        return h
    }
    /// "Verstanden" (verified false) bzw. "Als ueberprueft markieren" (true)
    func fedVerify(_ handle: String, identPub: String, verified: Bool) {
        FedTrustStore.set(me.uid, handle, FedChatLogic.confirm(FedTrustStore.get(me.uid, handle), ident: identPub, verified: verified))
    }
    func safetyV2(myHandle mh: String, handle: String, identPK: Data) -> String? {
        guard let k = keys else { return nil }
        return ChatCrypto.safetyNumberV2(handleA: mh, identA: k.identPK, handleB: handle, identB: identPK)
    }

    /// Name einer Gruppe ueber Servergrenzen (title_msg v2): Absender-Schluessel selbst pruefen (TOFU) - nur ein echter,
    /// nicht abgelehnter Absender gilt (wie im Web). Gemerkt je Nachricht, damit die Liste nicht jedes Mal Schluessel holt.
    func decryptTitlesV2(_ list: inout [ChatSummary]) async {
        var need: [Int: (ref: String, msg: [String: Any])] = [:]
        for c in list where c.kind == "free" {
            guard let f = c.fed, let m = c.titleMsg, let mid = m["id"] as? String else { continue }
            if let t = titleMemo[c.id], t.msg == mid, let i = list.firstIndex(where: { $0.id == c.id }) { list[i].decryptedTitle = t.title; continue }
            need[c.id] = (f.ref, m)
        }
        guard !need.isEmpty else { return }
        let senders = need.values.compactMap { ShareJSON.str($0.msg["sender_handle"]) }
        guard let fk = try? await fedKeys(senders) else { return }
        for (cid, x) in need {
            guard let r = decryptV2(ref: x.ref, raw: x.msg, fk: fk), r.verified, let c = r.content, c.type == "title" else { continue }
            let t = String((c.title ?? "").prefix(100))
            titleMemo[cid] = (x.msg["id"] as? String ?? "", t)
            if let i = list.firstIndex(where: { $0.id == cid }) { list[i].decryptedTitle = t }
        }
    }

    /// Nachricht v2 oeffnen und pruefen (FedFlow.open)
    func decryptV2(ref: String, raw m: [String: Any], fk: [String: FedKeyState]) -> (content: ChatContent?, verified: Bool)? {
        guard let o = own, let r = FedFlow.open(m, ref: ref, own: o, keys: fk) else { return nil }
        return (r.plain.flatMap { ChatContent(json: $0) }, r.verified)
    }
}
