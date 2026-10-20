import Foundation

// Prueft App/ChatCrypto.swift gegen tests/chat_vectors.json (wie tools/chat_vectors.py check) und App/CallCrypto.swift +
// App/CallLogic.swift gegen tests/call_vectors.json (wie tools/call_vectors.py check; Datei neben chat_vectors.json oder 2. Argument).
let C = ChatCrypto.self
let path = CommandLine.arguments.dropFirst().first ?? (NSHomeDirectory() + "/src/saga/tests/chat_vectors.json")
guard let raw = FileManager.default.contents(atPath: path), let v = (try? JSONSerialization.jsonObject(with: raw)) as? [String: Any] else {
    print("Vektoren nicht lesbar: \(path)"); exit(2)
}
func o(_ k: String) -> [String: Any] { v[k] as? [String: Any] ?? [:] }
func s(_ d: [String: Any], _ k: String) -> String { d[k] as? String ?? "" }
func h(_ d: [String: Any], _ k: String) -> Data { C.unhex(s(d, k)) ?? Data() }
func b(_ d: [String: Any], _ k: String) -> Data { C.unb64(s(d, k)) ?? Data() }
var res: [(String, Bool)] = []
func ok(_ n: String, _ c: Bool) { res.append((n, c)) }
func jsonEq(_ a: Data?, _ b: String) -> Bool {
    guard let a, let x = try? JSONSerialization.jsonObject(with: a), let y = try? JSONSerialization.jsonObject(with: Data(b.utf8)) else { return false }
    return (x as? NSDictionary) == (y as? NSDictionary)
}

let lpv = o("lp"), b32 = o("base32"), i = o("identity"), d = o("device"), r = o("recovery"), m = o("message"), f = o("file"), lk = o("link"), sf = o("safety")
let uid = s(i, "uid")
ok("lp", C.hex(C.lp((lpv["fields"] as? [String] ?? []) as [Any])) == s(lpv, "hex"))
ok("base32", C.base32(h(b32, "bytes_hex")) == s(b32, "text"))
let ident = C.signKeypair(seed: h(i, "ident_seed_hex")), backup = C.boxKeypair(seed: h(i, "backup_seed_hex"))
ok("identity keys", C.b64(ident.pk) == s(i, "ident_pk") && C.b64(ident.sk) == s(i, "ident_sk") && C.b64(backup.pk) == s(i, "backup_pk")
   && C.b64(backup.sk) == s(i, "backup_sk") && C.scalarmultBase(backup.sk) == backup.pk)
ok("backup_sig", C.b64(C.sign(h(i, "backup_msg_hex"), sk: ident.sk)) == s(i, "backup_sig")
   && C.hex(C.backupMsg(uid: uid, backupPK: backup.pk)) == s(i, "backup_msg_hex")
   && C.verify(b(i, "backup_sig"), C.backupMsg(uid: uid, backupPK: backup.pk), pk: ident.pk))
let enc = C.boxKeypair(seed: h(d, "enc_seed_hex")), sgn = C.signKeypair(seed: h(d, "sign_seed_hex"))
ok("device keys", C.b64(enc.pk) == s(d, "enc_pk") && C.b64(sgn.pk) == s(d, "sign_pk") && C.b64(sgn.sk) == s(d, "sign_sk"))
let dmsg = C.deviceMsg(uid: uid, deviceID: s(d, "id"), encPK: enc.pk, signPK: sgn.pk)
ok("device cert", C.hex(dmsg) == s(d, "msg_hex") && C.b64(C.sign(dmsg, sk: ident.sk)) == s(d, "sig") && C.verify(b(d, "sig"), dmsg, pk: ident.pk))
ok("recovery normalize", C.normRecovery(s(r, "key")) == s(r, "normalized") && C.normRecovery(s(r, "input_variant")) == s(r, "normalized"))
let ops = (r["ops"] as? NSNumber)?.intValue ?? 0, mem = (r["mem"] as? NSNumber)?.intValue ?? 0
ok("recovery kdf (argon2id13)", C.recoveryKDF(s(r, "key"), salt: b(r, "salt"), ops: ops, mem: mem).map(C.hex) == s(r, "kdf_hex"))
ok("bundle seal", C.sealBundle(uid: uid, recoveryKey: s(r, "key"), plain: s(r, "plain"), salt: b(r, "salt"), nonce: b(r, "nonce"), ops: ops, mem: mem) == s(r, "bundle")
   && C.bundlePlain(identSK: ident.sk, backupSK: backup.sk) == s(r, "plain") && C.hex(C.lp(C.V + "bundle", uid)) == s(r, "ad_hex"))
let ob = C.openBundle(uid: uid, recoveryKey: s(r, "input_variant"), bundle: s(r, "bundle"))
ok("bundle open", ob?.identPK == ident.pk && ob?.backupPK == backup.pk && ob?.identSK == ident.sk
   && C.openBundle(uid: uid, recoveryKey: "AAAA-AAAA-AAAA-AAAA-AAAA-AAAA-AAAA-AAAA", bundle: s(r, "bundle")) == nil)
let ts = (m["ts"] as? NSNumber)?.int64Value ?? 0, reply = m["reply_to"] as? String
let chat = s(m, "chat_id"), mid = s(m, "id"), sender = s(m, "sender"), dev = s(m, "device")
let em = C.encryptMessage(chatID: chat, msgID: mid, sender: sender, device: dev, signSK: sgn.sk, ts: ts, replyTo: reply,
                          plain: Data(s(m, "body_json").utf8), key: b(m, "key"), nonce: b(m, "nonce"))
ok("message ad", C.hex(C.msgAD(chatID: chat, msgID: mid, sender: sender, device: dev, ts: ts, replyTo: reply)) == s(m, "ad_hex"))
ok("message encrypt", em?.bodyCT == s(m, "body_ct"))
ok("message sig", em?.sig == s(m, "sig")
   && C.hex(C.sigMsg(chatID: chat, msgID: mid, sender: sender, device: dev, ts: ts, replyTo: reply, bodyCT: s(m, "body_ct"))) == s(m, "sig_msg_hex")
   && C.verifyMessage(chatID: chat, msgID: mid, sender: sender, device: dev, ts: ts, replyTo: reply, bodyCT: s(m, "body_ct"), sig: s(m, "sig"), signPK: sgn.pk))
ok("message open (device)", jsonEq(C.decryptMessage(chatID: chat, msgID: mid, sender: sender, device: dev, ts: ts, replyTo: reply,
                                                    bodyCT: s(m, "body_ct"), sealedKey: s(m, "sealed_device"), pk: enc.pk, sk: enc.sk), s(m, "body_json")))
ok("message open (backup)", jsonEq(C.decryptMessage(chatID: chat, msgID: mid, sender: sender, device: dev, ts: ts, replyTo: reply,
                                                    bodyCT: s(m, "body_ct"), sealedKey: s(m, "sealed_backup"), pk: backup.pk, sk: backup.sk), s(m, "body_json")))
let fb = C.encryptFile(chatID: s(f, "chat_id"), data: b(f, "plain_b64"), key: b(f, "key"), nonce: b(f, "nonce"))
ok("file", fb.map { C.b64($0.blob) } == s(f, "blob") && C.decryptFile(chatID: s(f, "chat_id"), blob: b(f, "blob"), key: b(f, "key")) == b(f, "plain_b64"))
ok("link code", C.linkCode(uid: s(lk, "uid"), deviceID: s(lk, "device_id"), encPK: b(lk, "enc_pk"), signPK: b(lk, "sign_pk")) == s(lk, "code"))
ok("link sealed bundle", C.sealOpen(b(lk, "sealed_bundle"), pk: enc.pk, sk: enc.sk).flatMap { String(data: $0, encoding: .utf8) } == s(r, "plain"))
ok("safety number", C.safetyNumber(uidA: s(sf, "uid_a"), identA: b(sf, "ident_a"), uidB: s(sf, "uid_b"), identB: b(sf, "ident_b")) == s(sf, "number")
   && C.safetyNumber(uidA: s(sf, "uid_b"), identA: b(sf, "ident_b"), uidB: s(sf, "uid_a"), identB: b(sf, "ident_a")) == s(sf, "number"))
// Grenzen beim Oeffnen des Buendels: ops <= 10, mem <= 256 MiB, nur v=1/argon2id13 (sonst "Unbekanntes Schluesselformat")
func variant(_ f: (inout [String: Any]) -> Void) -> String {
    var j = (try? JSONSerialization.jsonObject(with: Data(s(r, "bundle").utf8))) as? [String: Any] ?? [:]
    f(&j); return String(data: try! JSONSerialization.data(withJSONObject: j), encoding: .utf8)!
}
let tooManyOps = variant { $0["ops"] = 11 }, tooMuchMem = variant { $0["mem"] = 256 * 1024 * 1024 + 1024 }
let atLimit = variant { $0["ops"] = 10; $0["mem"] = 256 * 1024 * 1024 }, wrongKdf = variant { $0["kdf"] = "scrypt" }, wrongV = variant { $0["v"] = 2 }
ok("bundle limits", C.bundleFormatOK(s(r, "bundle")) && C.bundleFormatOK(atLimit)
   && !C.bundleFormatOK(tooManyOps) && !C.bundleFormatOK(tooMuchMem) && !C.bundleFormatOK(wrongKdf) && !C.bundleFormatOK(wrongV)
   && !C.bundleFormatOK(variant { $0["ops"] = 0 }) && !C.bundleFormatOK("kein json")
   && C.openBundle(uid: uid, recoveryKey: s(r, "key"), bundle: tooManyOps) == nil
   && C.openBundle(uid: uid, recoveryKey: s(r, "key"), bundle: tooMuchMem) == nil)
// eigene Rundreise: versiegeln/oeffnen mit frischen Schluesseln
let kp = C.boxKeypair(), k = C.aeadKey()
ok("seal roundtrip", C.seal(k, pk: kp.pk).flatMap { C.sealOpen($0, pk: kp.pk, sk: kp.sk) } == k && C.seal(k, pk: kp.pk)?.count == 80)
ok("recovery key format", { let x = C.newRecoveryKey(); return x.count == 39 && C.normRecovery(x) != nil && x.split(separator: "-").count == 8 }())

// Chat-Bilder mit Vorschaubild (2.1-c, Abschnitt "image"; aeltere Vektordateien ohne ihn: uebersprungen)
let im = o("image")
if !im.isEmpty {
    let ic = s(im, "chat_id"), its = (im["ts"] as? NSNumber)?.int64Value ?? 0
    let iem = C.encryptMessage(chatID: ic, msgID: s(im, "id"), sender: s(im, "sender"), device: s(im, "device"), signSK: sgn.sk, ts: its, replyTo: nil,
                               plain: Data(s(im, "body_json").utf8), key: b(im, "key"), nonce: b(im, "nonce"))
    ok("image message encrypt", iem?.bodyCT == s(im, "body_ct"))
    ok("image message sig", iem?.sig == s(im, "sig")
       && C.verifyMessage(chatID: ic, msgID: s(im, "id"), sender: s(im, "sender"), device: s(im, "device"), ts: its, replyTo: nil, bodyCT: s(im, "body_ct"), sig: s(im, "sig"), signPK: sgn.pk))
    let content = ChatContent(json: Data(s(im, "body_json").utf8))
    let f0 = content?.files.first, th = f0?.thumb
    ok("image files[0] w/h/th", f0 != nil && (f0?.w ?? 0) > 0 && (f0?.h ?? 0) > 0 && th != nil && max(th?.w ?? 999, th?.h ?? 999) <= 320 && th?.mime == "image/jpeg")
    ok("image send_files", ChatMediaLogic.sendIDs(content?.files ?? [], maxIDs: 20).ids == (im["send_files"] as? [String] ?? ["?"]))
    ok("image files[] roundtrip", content.flatMap { ChatContent(json: $0.json()) } == content)
    for (n, k) in [("orig", f0?.key), ("thumb", th?.key)] {
        let x = im[n] as? [String: Any] ?? [:]
        let eb = C.encryptFile(chatID: ic, data: b(x, "plain_b64"), key: b(x, "key"), nonce: b(x, "nonce"))
        ok("image \(n) blob", eb.map { C.b64($0.blob) } == s(x, "blob") && C.decryptFile(chatID: ic, blob: b(x, "blob"), key: b(x, "key")) == b(x, "plain_b64") && s(x, "key") == k)
    }
    let tp = b(im["thumb"] as? [String: Any] ?? [:], "plain_b64")
    ok("image thumb jpeg", tp.prefix(3) == Data([0xFF, 0xD8, 0xFF]) && tp.count == th?.size)
} else { print("(chat_vectors.json ohne Abschnitt image - uebersprungen)") }

// Anrufe (saga-call/v1): tests/call_vectors.json - dieselben Punkte wie tools/call_vectors.py check
let cpath = CommandLine.arguments.dropFirst(2).first ?? ((path as NSString).deletingLastPathComponent + "/call_vectors.json")
if let craw = FileManager.default.contents(atPath: cpath), let cv = (try? JSONSerialization.jsonObject(with: craw)) as? [String: Any] {
    func co(_ k: String) -> [String: Any] { cv[k] as? [String: Any] ?? [:] }
    let ca = co("caller"), ce = co("callee"), cid = cv["call_id"] as? String ?? "", off = co("offer"), ice = co("ice"), forged = co("forged")
    let aSign = C.signKeypair(seed: h(ca, "sign_seed_hex")), bEnc = C.boxKeypair(seed: h(ce, "enc_seed_hex"))
    ok("call device keys", C.b64(aSign.pk) == s(ca, "sign_pk") && C.b64(bEnc.pk) == s(ce, "enc_pk")
       && C.b64(C.boxKeypair(seed: h(ca, "enc_seed_hex")).pk) == s(ca, "enc_pk") && C.b64(C.signKeypair(seed: h(ce, "sign_seed_hex")).pk) == s(ce, "sign_pk"))
    let msg = C.signalMsg(callID: cid, fromUID: s(ca, "uid"), fromDevice: s(ca, "device"), toUID: s(ce, "uid"), toDevice: s(ce, "device"), body: s(off, "body"))
    ok("call signal_msg", C.hex(msg) == s(off, "signal_msg_hex"))
    ok("call sig (Ed25519, deterministisch)", C.b64(C.sign(msg, sk: aSign.sk)) == s(off, "sig"))
    ok("call inner", C.signalInner(body: s(off, "body"), sig: b(off, "sig")) == s(off, "inner"))
    let offerSDP: String = { if case .offer(let sdp, _, _)? = CallLogic.parse(s(off, "body")) { return sdp }; return "" }()
    let offerFP: [String] = { if case .offer(_, _, let fp)? = CallLogic.parse(s(off, "body")) { return fp }; return [] }()
    ok("call sdp fingerprints", CallLogic.sdpFingerprints(offerSDP) == (off["fingerprints"] as? [String] ?? ["x"]) && offerFP == CallLogic.sdpFingerprints(offerSDP)
       && CallLogic.fingerprintsMatch(sdp: offerSDP, fp: offerFP) && !CallLogic.fingerprintsMatch(sdp: offerSDP, fp: []))
    func verifyFrom(_ o: (body: String, sig: Data)?, call: String = cid) -> Bool {
        guard let o else { return false }
        return C.verifySignal(callID: call, fromUID: s(ca, "uid"), fromDevice: s(ca, "device"), toUID: s(ce, "uid"), toDevice: s(ce, "device"),
                              body: o.body, sig: o.sig, signPK: aSign.pk)
    }
    let oo = C.openSignal(s(off, "sealed"), pk: bEnc.pk, sk: bEnc.sk)
    ok("call open offer", oo?.body == s(off, "body") && oo.map { C.b64($0.sig) } == s(off, "sig"))
    ok("call verify offer", verifyFrom(oo))
    ok("call verify fails for other call", !verifyFrom(oo, call: String(repeating: "0", count: 32)))
    let io = C.openSignal(s(ice, "sealed"), pk: bEnc.pk, sk: bEnc.sk)
    ok("call open+verify ice", io?.body == s(ice, "body") && io.map { C.b64($0.sig) } == s(ice, "sig") && verifyFrom(io))
    if case .ice(let cands)? = io.flatMap({ CallLogic.parse($0.body) }) { ok("call ice parse", cands.count == 1 && cands[0].sdpMid == "0") } else { ok("call ice parse", false) }
    let fo = C.openSignal(s(forged, "sealed"), pk: bEnc.pk, sk: bEnc.sk)
    ok("call forged (other target device) rejected", fo != nil && !verifyFrom(fo))
    // eigene Rundreise: versiegeln -> oeffnen -> pruefen; falsches Geraet kann nicht oeffnen
    let sealed = C.sealSignal(callID: cid, fromUID: s(ca, "uid"), fromDevice: s(ca, "device"), signSK: aSign.sk, toUID: s(ce, "uid"),
                              toDevice: s(ce, "device"), toPK: bEnc.pk, body: s(off, "body")) ?? ""
    let other = C.boxKeypair()
    ok("call seal roundtrip", verifyFrom(C.openSignal(sealed, pk: bEnc.pk, sk: bEnc.sk)) && C.openSignal(sealed, pk: other.pk, sk: other.sk) == nil)
} else { ok("call vectors lesbar (\(cpath))", false) }

// saga-chat/v2 (2.3): 3. Argument oder die Kopie im Repo (ios/Tests/vectors/chat_v2.json)
let v2path = CommandLine.arguments.dropFirst(3).first
    ?? URL(fileURLWithPath: #filePath).deletingLastPathComponent().appendingPathComponent("../../../vectors/chat_v2.json").standardized.path
res += v2Vectors(v2path)

// Gelesen-Bestaetigungen (2.4): 4. Argument oder die Kopie im Repo (ios/Tests/vectors/receipts.json)
let rcpath = CommandLine.arguments.dropFirst(4).first
    ?? URL(fileURLWithPath: #filePath).deletingLastPathComponent().appendingPathComponent("../../../vectors/receipts.json").standardized.path
res += receiptVectors(rcpath)

for (n, g) in res { print(g ? "OK  " : "FAIL", n) }
let good = res.filter { $0.1 }.count
print("VEKTOREN \(good)/\(res.count)", good == res.count ? "- ALLES GRUEN" : "- FEHLER")
exit(good == res.count ? 0 : 1)
