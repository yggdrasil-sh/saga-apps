import Foundation

// Gelesen-Bestaetigungen (2.4): tests/vectors/receipts.json (Kopie: ios/Tests/vectors/receipts.json) gegen
// App/ReceiptCrypto.swift und App/ReceiptLogic.swift - dieselben Punkte wie tools/receipt_vectors.py bzw. .mjs.
func receiptVectors(_ path: String) -> [(String, Bool)] {
    let C = ChatCrypto.self
    var res: [(String, Bool)] = []
    func ok(_ n: String, _ c: Bool) { res.append(("receipt " + n, c)) }
    guard let raw = FileManager.default.contents(atPath: path), let v = (try? JSONSerialization.jsonObject(with: raw)) as? [String: Any] else {
        return [("receipt Vektoren lesbar (\(path))", false)]
    }
    func o(_ d: [String: Any], _ k: String) -> [String: Any] { d[k] as? [String: Any] ?? [:] }
    func s(_ d: [String: Any], _ k: String) -> String { d[k] as? String ?? "" }
    let dev = o(v, "device"), viewer = o(v, "viewer")
    ok("format", s(v, "format") == "saga-receipt/v1")
    let sign = C.signKeypair(seed: C.unhex(s(dev, "sign_seed_hex")))
    let backup = C.boxKeypair(seed: C.unhex(s(viewer, "backup_seed_hex")))
    ok("device sign_pk", C.b64(sign.pk) == s(dev, "sign_pk"))
    ok("viewer backup_pk", C.b64(backup.pk) == s(viewer, "backup_pk"))
    let devID = s(dev, "id")
    let cases = v["cases"] as? [[String: Any]] ?? []
    ok("cases vorhanden", cases.count >= 2)
    for c in cases {
        let name = s(c, "name"), v2 = c["v2"] as? Bool ?? false, chat = s(c, "chat"), reader = s(c, "reader"), body = s(c, "body")
        // body genau wie die App ihn baut
        if let seen = ReceiptLogic.parseBody(body) {
            ok("\(name) body", ReceiptLogic.body(id: seen.id, n: seen.n, ts: Int64(seen.ts)) == body)
        } else { ok("\(name) body lesbar", false) }
        let msg = C.receiptMsg(chat: chat, reader: reader, device: devID, body: body, v2: v2)
        ok("\(name) msg", C.hex(msg) == s(c, "msg_hex"))
        let sig = C.receiptSig(chat: chat, reader: reader, device: devID, body: body, v2: v2, signSK: sign.sk)
        ok("\(name) sig", C.b64(sig) == s(c, "sig"))
        // Versiegelte Bestaetigung oeffnen und pruefen
        let r = C.openReceipt(s(c, "sealed"), pk: backup.pk, sk: backup.sk)
        ok("\(name) open", r?.dev == devID && r?.body == body && r.map { C.b64($0.sig) } == s(c, "sig"))
        ok("\(name) verify", r.map { C.verifyReceipt(chat: chat, reader: reader, $0, signPK: sign.pk, v2: v2) } ?? false)
        let other = C.boxKeypair()
        ok("\(name) fremder Schluessel oeffnet nicht", C.openReceipt(s(c, "sealed"), pk: other.pk, sk: other.sk) == nil)
        // eigene Rundreise
        let mine = C.sealReceipt(chat: chat, reader: reader, device: devID, signSK: sign.sk, body: body, v2: v2, toPK: backup.pk)
        let back = C.openReceipt(mine, pk: backup.pk, sk: backup.sk)
        ok("\(name) seal roundtrip", back?.body == body && back?.dev == devID
           && back.map { C.verifyReceipt(chat: chat, reader: reader, $0, signPK: sign.pk, v2: v2) } ?? false)
    }
    for (i, c) in (v["reject"] as? [[String: Any]] ?? []).enumerated() {
        let r = ChatCrypto.OpenedReceipt(dev: devID, body: s(c, "body"), sig: C.unb64(s(c, "sig"), 64) ?? Data())
        ok("reject \(i + 1) (\(s(c, "why")))", !C.verifyReceipt(chat: s(c, "chat"), reader: s(c, "reader"), r, signPK: sign.pk, v2: c["v2"] as? Bool ?? false))
    }
    return res
}
