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
        guard let store, let path = FedFlow.keysPath(handles, fresh: fresh) else { return [:] }
        if let own, !own.isEmpty { myHandle = own }                     // aus dem Chat (my_handle) - nie als fremde Person behandeln
        let d = try await store.api(path)
        let uid = me.uid
        return FedFlow.evaluate(handles, keys: ShareJSON.obj(d)?["keys"] as? [String: Any] ?? [:], myHandle: myHandle, myIdentPK: keys?.identPK ?? Data(),
                                stored: { FedTrustStore.get(uid, $0) }, save: { FedTrustStore.set(uid, $0, $1) })
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

    /// Nachricht v2 oeffnen und pruefen (FedFlow.open)
    func decryptV2(ref: String, raw m: [String: Any], fk: [String: FedKeyState]) -> (content: ChatContent?, verified: Bool)? {
        guard let k = keys, let r = FedFlow.open(m, ref: ref, own: k.fedOwn, keys: fk) else { return nil }
        return (r.plain.flatMap { ChatContent(json: $0) }, r.verified)
    }
}
