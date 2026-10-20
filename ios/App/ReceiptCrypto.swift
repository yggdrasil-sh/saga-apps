import Foundation

// Gelesen-Bestaetigung (Saga 2.4, saga-receipt/v1): vom Geraet signiert, je anderem Mitglied fuer dessen Backup-Schluessel
// versiegelt (eine je Person, nicht je Geraet). Der Server sieht nur, DASS es eine gibt.
//   body   = {"id":"<msg_id>","n":<int>,"ts":<ms>}
//   sig    = sign_detached(LP("saga-chat/v1/receipt", chat_id, benutzername, geraet, body))       lokal
//          = sign_detached(LP("saga-chat/v2/receipt", chat_ref, handle, geraet, body))            v2
//   inner  = {"v":1,"dev":geraet,"body":body,"sig":B64(sig)}
//   sealed = B64(crypto_box_seal(inner, backup_pub))
// Testvektoren: Tests/vectors/receipts.json (Server tests/vectors/receipts.json), ChatVectors.

extension ChatCrypto {
    static func receiptMsg(chat: String, reader: String, device: String, body: String, v2: Bool) -> Data {
        lp((v2 ? V2 : V) + "receipt", chat, reader, device, body)
    }
    static func receiptSig(chat: String, reader: String, device: String, body: String, v2: Bool, signSK: Data) -> Data {
        sign(receiptMsg(chat: chat, reader: reader, device: device, body: body, v2: v2), sk: signSK)
    }
    /// Signieren und fuer einen Backup-Schluessel versiegeln -> Base64
    static func sealReceipt(chat: String, reader: String, device: String, signSK: Data, body: String, v2: Bool, toPK: Data) -> String? {
        let sig = receiptSig(chat: chat, reader: reader, device: device, body: body, v2: v2, signSK: signSK)
        guard toPK.count == 32,
              let inner = try? JSONSerialization.data(withJSONObject: ["v": 1, "dev": device, "body": body, "sig": b64(sig)], options: [.sortedKeys]),
              let s = seal(inner, pk: toPK) else { return nil }
        return b64(s)
    }
    struct OpenedReceipt: Equatable { var dev: String; var body: String; var sig: Data }
    /// Mit dem eigenen Backup-Schluessel oeffnen (nil = nicht lesbar oder falsche Form)
    static func openReceipt(_ sealed: String?, pk: Data, sk: Data) -> OpenedReceipt? {
        guard let c = unb64(sealed), let p = sealOpen(c, pk: pk, sk: sk), let d = ShareJSON.obj(p),
              (d["v"] as? NSNumber)?.intValue == 1, let dev = d["dev"] as? String, let body = d["body"] as? String,
              let sig = unb64(d["sig"] as? String, 64) else { return nil }
        return OpenedReceipt(dev: dev, body: body, sig: sig)
    }
    /// Signatur gegen das gepruefte Geraet der lesenden Person (Chat und Benutzername/Handle gebunden)
    static func verifyReceipt(chat: String, reader: String, _ r: OpenedReceipt, signPK: Data, v2: Bool) -> Bool {
        verify(r.sig, receiptMsg(chat: chat, reader: reader, device: r.dev, body: r.body, v2: v2), pk: signPK)
    }
}
