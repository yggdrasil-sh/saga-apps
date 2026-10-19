import Foundation

// E2E Chats ueber Servergrenzen (Saga 2.3) mit den Ablaeufen der App (FedFlow, ChatCryptoV2, FedChatLogic):
// Konto A (diese App-Logik) auf Server A, Gegenseite B als Referenz-App (tests/chatlib.py) auf Server B, beide Server verbunden.
// Umgebung:
//   FED_A          Adresse von Server A (z. B. http://host:port)
//   FED_A_COOKIE   Sitzungs-Cookie des Testkontos auf A ("saga=…"), FED_A_UID dessen Benutzername
//   FED_PEER       Programm fuer die Gegenseite: <prog> [as <uid>] <befehl> [args] fuehrt bert.py auf B aus,
//                  <prog> ctl stop|start haelt Server B an bzw. startet ihn wieder
//   FED_B_PUB      oeffentlicher Name der Gegenseite (z. B. bert), FED_B_NAME Anzeigename
//   FED_B_SHORT    Anzeigename (1-2 Zeichen) eines zweiten Kontos auf B, FED_B_SHORT_UID dessen Benutzername (Anfrage an A)
// Aufruf: swift run -c release FedE2E   (alle Pruefungen; Ende mit "E2E n/m")
let env = ProcessInfo.processInfo.environment
func need(_ k: String) -> String { guard let v = env[k], !v.isEmpty else { print("Umgebung fehlt: \(k)"); exit(2) }; return v }
let base = need("FED_A").trimmingCharacters(in: CharacterSet(charactersIn: "/")), cookie = need("FED_A_COOKIE"), uid = need("FED_A_UID")
let peerProg = need("FED_PEER"), bPub = need("FED_B_PUB"), bName = need("FED_B_NAME")
let bShort = env["FED_B_SHORT"] ?? "", bShortUID = env["FED_B_SHORT_UID"] ?? ""
let C = ChatCrypto.self

var results: [(String, Bool)] = []
func ok(_ n: String, _ c: Bool, _ info: @autoclosure () -> String = "") {
    results.append((n, c)); print(c ? "OK  " : "FAIL", n, c ? "" : "– " + info())
}
func obj(_ d: Data) -> [String: Any] { ShareJSON.obj(d) ?? [:] }

/// Anfrage an Server A wie die App (Sitzung, X-Saga, X-Saga-Chat: 2)
func api(_ m: String, _ p: String, _ body: Any? = nil, raw: Data? = nil, v2: Bool = true) async -> (Int, Data) {
    var r = URLRequest(url: URL(string: base + "/" + p)!, timeoutInterval: 60)
    r.httpMethod = m
    r.setValue(cookie, forHTTPHeaderField: "Cookie"); r.setValue("1", forHTTPHeaderField: "X-Saga"); r.setValue("de", forHTTPHeaderField: "Accept-Language")
    if v2 { r.setValue("2", forHTTPHeaderField: "X-Saga-Chat") }
    if let body { r.httpBody = try? JSONSerialization.data(withJSONObject: body); r.setValue("application/json", forHTTPHeaderField: "Content-Type") }
    if let raw { r.httpBody = raw; r.setValue("application/octet-stream", forHTTPHeaderField: "Content-Type") }
    guard let (d, resp) = try? await URLSession.shared.data(for: r) else { return (0, Data()) }
    return ((resp as? HTTPURLResponse)?.statusCode ?? 0, d)
}
/// Gegenseite (bert.py auf Server B) -> Ausgabe
@discardableResult func peer(_ args: String...) -> String {
    let p = Process(); p.executableURL = URL(fileURLWithPath: peerProg); p.arguments = args
    let out = Pipe(); p.standardOutput = out; p.standardError = out
    try? p.run(); let d = out.fileHandleForReading.readDataToEndOfFile(); p.waitUntilExit()
    let s = String(data: d, encoding: .utf8) ?? ""
    print("   [B \(args.prefix(2).joined(separator: " "))] " + s.split(separator: "\n").suffix(6).joined(separator: "\n   "))
    return s
}
func q(_ s: String) -> String { s.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? s }

// MARK: 1. Einrichtung wie ChatModel.setup + fedCerts

let ident = C.signKeypair(), backup = C.boxKeypair(), encK = C.boxKeypair(), signK = C.signKeypair(), did = C.hexID()
let own = FedFlow.Own(uid: uid, deviceID: did, enc: encK, sign: signK, identSK: ident.sk, backup: backup)
do {
    let bundle = C.sealBundle(uid: uid, recoveryKey: C.newRecoveryKey(), plain: C.bundlePlain(identSK: ident.sk, backupSK: backup.sk)) ?? ""
    let cert = C.sign(C.deviceMsg(uid: uid, deviceID: did, encPK: encK.pk, signPK: signK.pk), sk: ident.sk)
    let body: [String: Any] = ["ident_pub": C.b64(ident.pk), "backup_pub": C.b64(backup.pk),
                               "backup_sig": C.b64(C.sign(C.backupMsg(uid: uid, backupPK: backup.pk), sk: ident.sk)), "bundle": bundle,
                               "device": ["id": did, "name": "FedE2E", "enc_pub": C.b64(encK.pk), "sign_pub": C.b64(signK.pk), "sig": C.b64(cert)]]
    let (s, d) = await api("PUT", "api/chat/identity", body)
    ok("A: Chats eingerichtet (\(s))", s == 200, String(data: d, encoding: .utf8) ?? "")
}
nonisolated(unsafe) var myHandle = ""
do {
    var v = obj(await api("GET", "api/chat/v2/me").1)
    myHandle = ShareJSON.str(v["handle"]) ?? ""
    ok("A: Handle pseudonym (\(myHandle))", FedChatLogic.isHandle(myHandle) && !myHandle.contains(uid))
    let body = FedFlow.certBody(v, own: own, devices: [])
    let (s1, d1) = await api("PUT", "api/chat/v2/cert", body)
    v = obj(d1)
    if let ds = FedFlow.deviceSetBody(v, own: own) { v = obj(await api("PUT", "api/chat/v2/cert", ds).1) }
    let me2 = obj(await api("GET", "api/chat/v2/me").1)
    ok("A: Zertifikate v2 + signierte Geraete-Liste (\(s1))", s1 == 200 && !ShareJSON.bool(me2["need"]) && ShareJSON.bool(me2["device_set_ok"]),
       "\(me2)")
    ok("A: nichts mehr zu tun", FedFlow.certBody(me2, own: own, devices: []).isEmpty && FedFlow.deviceSetBody(me2, own: own) == nil)
}
let bSetup = peer("setup")
ok("B: Gegenseite eingerichtet", bSetup.contains("dset_ok True"), bSetup)

// MARK: 2. Personensuche (Adressbuch): ab 1 Zeichen, "Name · host", name@host

var bert: FedPerson?
for _ in 0..<15 {                                                    // Adressbuch-Abgleich nach dem Verbinden abwarten
    let r = FedChatLogic.people(obj(await api("GET", "api/chat/people?q=" + q(bName)).1)["remote"])
    if let p = r.first(where: { $0.name == bName }) { bert = p; break }
    try? await Task.sleep(for: .seconds(2))
}
let bHost = bert?.host ?? "?"
ok("Suche nach Anzeigename findet B mit Herkunft (\(bert?.label ?? "-"))", bert?.handle != nil && bert?.label == "\(bName) · \(bHost)" && bert?.pub == "\(bPub)@\(bHost)")
do {
    let one = obj(await api("GET", "api/chat/people?q=" + q(String(bName.prefix(1)))).1)
    ok("1 Zeichen: Anfrage geht, nur genaue Treffer", FedChatLogic.people(one["remote"]).allSatisfy { $0.name.count == 1 })
    let addr = FedChatLogic.people(obj(await api("GET", "api/chat/people?q=" + q("\(bPub)@\(bHost)")).1)["remote"])
    ok("name@host findet genau B", addr.count == 1 && addr.first?.handle == bert?.handle)
    if !bShort.isEmpty {
        let sh = FedChatLogic.people(obj(await api("GET", "api/fed/people?q=" + q(bShort)).1)["people"])
        ok("kurzer Name (\(bShort), 2 Zeichen) genau gefunden", sh.contains { $0.name == bShort && $0.host == bHost })
    }
    let st = ShareLogic.parseTargets(await api("GET", "api/share/targets?q=" + q(bName)).1).filter(\.isRemote)
    ok("Teilen-Suche: Treffer mit label (\(st.first?.remoteLine ?? "-"))", st.first?.remoteLine == "\(bName) · \(bHost)" && st.first?.target.hasPrefix("@") == true)
}

// MARK: 3. Direkt-Chat beginnen (Heim = A), Nachricht v2 senden

var tofu: [String: FedTrust] = [:]
func keys(_ hs: [String], fresh: Bool = false) async -> [String: FedKeyState] {
    guard let p = FedFlow.keysPath(hs, fresh: fresh) else { return [:] }
    return FedFlow.evaluate(hs, keys: obj(await api("GET", p).1)["keys"] as? [String: Any] ?? [:], myHandle: myHandle, myIdentPK: own.identPK,
                            stored: { tofu[$0] }, save: { tofu[$0] = $1 })
}
func chat(_ id: Int) async -> (ChatSummaryLite, FedChatInfo?) {
    let c = obj(await api("GET", "api/chats/\(id)?device=\(did)").1)["chat"] as? [String: Any] ?? [:]
    return (ChatSummaryLite(c), FedChatInfo(c))
}
struct ChatSummaryLite { var id: Int; var name: String; init(_ d: [String: Any]) { id = ShareJSON.int(d["id"]) ?? 0; name = ShareJSON.str(d["name"]) ?? "" } }
func send(_ info: FedChatInfo, _ cid: Int, text: String, files: [ChatFileRef] = [], replyTo: String? = nil) async -> (Int, [String: Any], String) {
    let fk = await keys(info.handles)
    guard FedChatLogic.sendCheck(members: info.members, verdicts: fk.mapValues(\.verdict), myHandle: info.myHandle) == .ok,
          let (body, mid) = FedFlow.sendBody(info: info, own: own, keys: fk, plain: ChatContent(text: text, files: files).json(), replyTo: replyTo, ai: nil,
                                             fileIDs: ChatMediaLogic.sendIDs(files, maxIDs: 20).ids) else { return (0, [:], "") }
    let (s, d) = await api("POST", "api/chats/\(cid)/messages", body)
    return (s, obj(d), mid)
}
func messages(_ cid: Int) async -> [[String: Any]] { obj(await api("GET", "api/chats/\(cid)/messages?calls=1&device=\(did)&limit=200").1)["messages"] as? [[String: Any]] ?? [] }

let (cs, cd) = await api("POST", "api/chats", ["kind": "dm", "handle": bert?.handle ?? "", "device": did])
let chatID = ShareJSON.int((obj(cd)["chat"] as? [String: Any])?["id"]) ?? 0
var (_, fed) = await chat(chatID)
ok("Direkt-Chat v2 begonnen (\(cs)): Heim A, chat_ref, Gegenueber mit Herkunft",
   cs == 200 && fed?.homeSelf == true && fed?.myHandle == myHandle && fed?.peer?.host == bHost && fed?.peer?.label == "\(bName) · \(bHost)"
   && FedChatLogic.host(of: fed?.ref ?? "") != bHost && fed?.request == false, String(data: cd, encoding: .utf8) ?? "")
let fk0 = await keys(fed?.handles ?? [])
ok("Schluessel selbst geprueft + TOFU (beide trust, signierte Geraete-Liste)",
   fk0[myHandle]?.verdict == .trust && fk0[bert?.handle ?? ""]?.verdict == .trust && fk0[bert?.handle ?? ""]?.keys?.signed == true && tofu.count == 1)
let t1 = "Hallo \(bName) – von der Apple-App über Servergrenzen! äöü 👋"
let (s1, r1, m1) = await send(fed!, chatID, text: t1)
ok("A sendet v2 (\(s1), fseq \(r1["fseq"] ?? "-"))", s1 == 200 && ShareJSON.bool(r1["ok"]) && ShareJSON.int(r1["fseq"]) != nil, "\(r1)")
let ref = fed?.ref ?? ""
var bl = peer("chats")
ok("B sieht Nachrichtenanfrage", bl.contains(ref) && bl.contains("request"))
var br = peer("read", ref)
ok("B entschluesselt + prueft Signatur (Referenz-App)", br.contains("VERIFIED") && !br.contains("UNVERIFIED") && br.contains("Servergrenzen"))
peer("answer", ref, "accept")
peer("send", ref, "Hallo zurück aus B ✓", m1)
peer("sendfile", ref, "Mit Anhang")

// MARK: 4. Empfangen, pruefen, Anhaenge (AD = chat_ref), Antwort, Sicherheitsnummer

try? await Task.sleep(for: .seconds(2))
let fk1 = await keys(fed?.handles ?? [])
var fromB: [(id: String, content: ChatContent?, verified: Bool, reply: String?)] = []
for m in await messages(chatID) where (m["kind"] as? String) == "msg" && (m["sender_handle"] as? String) != myHandle {
    let r = FedFlow.open(m, ref: ref, own: own, keys: fk1)
    fromB.append((m["id"] as? String ?? "", r?.plain.flatMap { ChatContent(json: $0) }, r?.verified ?? false, ShareJSON.str(m["reply_to"])))
}
ok("A empfaengt + prueft Nachrichten von B (\(fromB.count))", fromB.count == 2 && fromB.allSatisfy(\.verified)
   && fromB.contains { $0.content?.text == "Hallo zurück aus B ✓" && $0.reply == m1 }, "\(fromB.map { ($0.content?.text ?? "nil", $0.verified) })")
if let f = fromB.compactMap({ $0.content?.files.first }).first {
    let (fs, blob) = await api("GET", "api/chat/files/\(f.id)")
    let pt = C.decryptChatFile(ref, blob: blob, key: C.unb64(f.key, 32) ?? Data())
    ok("A laedt Anhang von B (\(fs)) und entschluesselt (AD chat_ref)", fs == 200 && pt.map { String(decoding: $0, as: UTF8.self).hasPrefix("Anhang von Bert") } == true)
} else { ok("A: Anhang von B vorhanden", false) }
do {
    let data = Data(("Anhang aus der Apple-App über Servergrenzen äöü " + Date().description).utf8)
    let e = C.encryptChatFile(ref, data: data)!
    let (us, ud) = await api("POST", "api/chats/\(chatID)/files", raw: e.blob)
    let fid = ShareJSON.str(obj(ud)["id"]) ?? ""
    let (s, r, _) = await send(fed!, chatID, text: "Datei von A", files: [ChatFileRef(id: fid, key: C.b64(e.key), name: "apple.txt", mime: "text/plain", size: data.count)],
                               replyTo: fromB.first?.id)
    ok("A sendet Anhang + Antwort (Upload \(us), Senden \(s))", us == 200 && s == 200, "\(r)")
    try? await Task.sleep(for: .seconds(2))
    let gf = peer("getfiles", ref)
    ok("B laedt Anhang von A und entschluesselt", gf.contains("FILE OK apple.txt"))
    br = peer("read", ref)
    ok("B prueft alle Nachrichten von A (auch mit reply_to)", br.components(separatedBy: "VERIFIED").count - 1 >= 4 && !br.contains("UNVERIFIED"))
}
do {
    let sb = peer("safety", ref).components(separatedBy: "SAFETY ").last?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
    let mine = C.safetyNumberV2(handleA: myHandle, identA: own.identPK, handleB: bert?.handle ?? "", identB: fk1[bert?.handle ?? ""]?.keys?.identPK ?? Data())
    ok("Sicherheitsnummer v2 auf beiden Seiten gleich", !sb.isEmpty && sb == mine, "\(sb) / \(mine)")
}

// MARK: 5. Anrufe saga-call/v2 (ohne Medien: Angebot mit Test-SDP)

do {
    let bk = fk1[bert?.handle ?? ""]?.keys
    let cid = C.hexID(), sdp = "v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\na=fingerprint:sha-256 AB:CD:EF\r\n"
    let body = CallLogic.offerBody(sdp: sdp, media: "audio")
    var sig: [String: String] = [:]
    for d in bk?.active ?? [] {
        sig[d.id] = C.sealSignalV2(callID: cid, fromHandle: myHandle, fromDevice: did, signSK: signK.sk, toHandle: bert?.handle ?? "", toDevice: d.id, toPK: d.enc, body: body)
    }
    let (s, d) = await api("POST", "api/calls", ["id": cid, "chat_id": chatID, "media": "audio", "device": did, "signals": sig])
    ok("A ruft B an (\(s))", s == 200, String(data: d, encoding: .utf8) ?? "")
    try? await Task.sleep(for: .seconds(1))
    let pc = peer("calls")
    ok("B: Anruf v2 klingelt, Signal geprueft", pc.contains("call 2 ringing callee \(myHandle)") && pc.contains("SIGNAL VERIFIED offer"))
    _ = await api("POST", "api/calls/\(cid)/end", ["reason": "hangup", "connected": false])
    try? await Task.sleep(for: .seconds(1))
    peer("ring", ref)
    try? await Task.sleep(for: .seconds(1))
    let act = obj(await api("GET", "api/calls/active").1)["calls"] as? [[String: Any]] ?? []
    if let inc = act.first, let iid = inc["id"] as? String {
        let st = obj(await api("GET", "api/calls/\(iid)?device=\(did)").1)
        let call = st["call"] as? [String: Any] ?? [:], ph = ShareJSON.str((call["peer"] as? [String: Any])?["handle"]) ?? ""
        let pk = await keys([ph])[ph]?.keys
        var good = 0
        for case let sg as [String: Any] in (st["signals"] as? [Any] ?? []) {
            guard let from = sg["from_device"] as? String, let o = C.openSignalV2(sg["sealed"] as? String ?? "", pk: encK.pk, sk: encK.sk) else { continue }
            if C.verifySignalV2(callID: iid, fromHandle: ph, fromDevice: from, toHandle: myHandle, toDevice: did, body: o.body, sig: o.sig,
                                signPK: pk?.devices.first { $0.id == from }?.sign), case .offer(_, _, _)? = CallLogic.parse(o.body) { good += 1 }
        }
        ok("A: Anruf von B (v2, \(ShareJSON.str(call["state"]) ?? "?")) - Angebot geprueft", ShareJSON.int(call["v"]) == 2 && ph == bert?.handle && good >= 1, "\(st)")
        let (ds, _) = await api("POST", "api/calls/\(iid)/decline", ["reason": "declined"])
        ok("A lehnt ab (\(ds))", ds == 200)
    } else { ok("A: Anruf von B klingelt", false, "\(act)") }
}

// MARK: 6. Nachrichtenanfrage an A (Heim B): still, kein Klingeln, Annehmen, Heim offline -> wird zugestellt, Sperren

if !bShortUID.isEmpty {
    peer("as", bShortUID, "setup")
    let st = peer("as", bShortUID, "start", myHandle)
    let ref2 = st.split(whereSeparator: \.isNewline).last.map { String($0.split(separator: " ").last ?? "") } ?? ""
    peer("as", bShortUID, "send", ref2, "Hallo von \(bShort) – darf ich schreiben?")
    try? await Task.sleep(for: .seconds(2))
    let lst = obj(await api("GET", "api/chats?device=\(did)").1)
    let rc = (lst["chats"] as? [[String: Any]] ?? []).first { ShareJSON.str($0["ref"]) == ref2 }
    let rinfo = FedChatInfo(rc)
    let rid = ShareJSON.int(rc?["id"]) ?? 0
    ok("A: Anfrage von \(bShort) (requests \(lst["requests"] ?? "-"), Heim B)", rinfo?.request == true && rinfo?.homeSelf == false && !(rinfo?.callable ?? true)
       && (ShareJSON.int(lst["requests"]) ?? 0) >= 1 && rinfo?.peer?.label.hasSuffix(bHost) == true, "\(rc ?? [:])")
    let fk2 = await keys(rinfo?.handles ?? [])
    let rm = await messages(rid).filter { ($0["kind"] as? String) == "msg" }
    let opened = rm.compactMap { FedFlow.open($0, ref: ref2, own: own, keys: fk2) }
    ok("A liest die Anfrage (ohne Annehmen), Signatur geprueft", opened.count == 1 && opened[0].verified
       && opened[0].plain.flatMap { ChatContent(json: $0) }?.text.hasPrefix("Hallo von") == true)
    let ring = peer("as", bShortUID, "ring", ref2)
    try? await Task.sleep(for: .seconds(1))
    let act = obj(await api("GET", "api/calls/active").1)["calls"] as? [[String: Any]] ?? []
    ok("Anfrage klingelt nicht", act.isEmpty && !ring.contains("(200,"), ring)
    let (as1, _) = await api("POST", "api/chats/\(rid)/request", ["action": "accept", "scope": "person"])
    let (_, acc) = await chat(rid)
    ok("A nimmt an (\(as1))", as1 == 200 && acc?.request == false && acc?.callable == true)
    peer("ctl", "stop")
    let (ps, pr, pmid) = await send(acc!, rid, text: "Während B offline ist")
    let pend = await messages(rid).first { ($0["id"] as? String) == pmid }
    ok("Heim offline: Nachricht wartet (\(ps), pending)", ps == 200 && ShareJSON.bool(pr["pending"]) && FedChatLogic.delivery(pend?["fstate"]) == .pending, "\(pr)")
    peer("ctl", "start")
    var del = FedChatLogic.Delivery.pending
    for _ in 0..<45 {
        try? await Task.sleep(for: .seconds(4))
        del = FedChatLogic.delivery(await messages(rid).first { ($0["id"] as? String) == pmid }?["fstate"])
        if del != .pending { break }
    }
    ok("Heim wieder da: zugestellt (\(del))", del == .delivered)
    let (bs, _) = await api("POST", "api/chats/\(rid)/request", ["action": "block", "scope": "person"])
    let blocks = obj(await api("GET", "api/chat/blocks").1)["blocks"] as? [[String: Any]] ?? []
    let tgt = ShareJSON.str(blocks.first?["target"]) ?? ""
    ok("A sperrt \(bShort) (\(bs)): in der Sperrliste mit Herkunft", bs == 200 && blocks.count == 1 && (blocks.first?["kind"] as? String) == "person"
       && (ShareJSON.str(blocks.first?["label"]) ?? "").hasSuffix(bHost), "\(blocks)")
    let (us, _) = await api("DELETE", "api/chat/blocks?target=" + q(tgt))
    let after = obj(await api("GET", "api/chat/blocks").1)["blocks"] as? [Any] ?? [1]
    ok("A entsperrt (\(us))", us == 200 && after.isEmpty)
}

// MARK: 7. Loeschen fuer alle, aeltere App ohne v2

do {
    let (ds, _) = await api("DELETE", "api/chats/\(chatID)/messages/\(m1)")
    try? await Task.sleep(for: .seconds(2))
    let rd = peer("read", ref)
    ok("A loescht eigene Nachricht fuer alle (\(ds))", ds == 200 && !rd.contains("von der Apple-App"))
    let old = obj(await api("GET", "api/chats/\(chatID)/messages?calls=1&device=\(did)", v2: false).1)
    ok("Ohne X-Saga-Chat: nur Hinweis (aeltere Apps)", (old["messages"] as? [Any])?.isEmpty == true && old["fed_hint"] != nil)
}

let good = results.filter(\.1).count
print("E2E \(good)/\(results.count)", good == results.count ? "- ALLES GRUEN" : "- FEHLER")
exit(good == results.count ? 0 : 1)
