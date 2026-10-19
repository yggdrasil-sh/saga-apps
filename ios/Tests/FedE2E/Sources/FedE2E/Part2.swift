import Foundation

// E2E Teil 2 (Saga 2.3, E4-E6) mit FED_PART=2 - drei verbundene Server: A (diese App-Logik) ist mit B und C verbunden,
// B und C nicht miteinander. Gegenseiten: bert.py auf B (fi-bert) und auf C (fi-dora, FED_PEER on c …).
// Geprueft: fremde Seite mit remote.caps (PageAccess), Datenbank (Schema, Zeilen lesen/anlegen/aendern), Unterseite anlegen und
// verschieben, Bild hochladen (X-Saga-Note), Versionen ansehen/wiederherstellen, Export, Anwesenheit; Seiten-Chat der fremden
// Seite; freie Gruppe ueber drei Server (handles, Name als v2-Nachricht kind 'title', Einladung als Anfrage mit Herkunft,
// Systemzeilen members, Mitglied per Handle hinzufuegen/entfernen, Nachrichten in alle Richtungen inkl. Schluessel dritter
// Server); Umstellung eines lokalen Seiten-Chats auf v2 (alte Nachricht bleibt v1 lesbar, Systemzeile fed).
// Umgebung zusaetzlich: FED_A_PUB (oeffentlicher Name von A), FED_C_NAME (Anzeigename auf C), FED_OTHER_UID (zweites Konto auf A).

func part2() async {
    let hostA = URL(string: base).map { ($0.host ?? "") + ($0.port.map { ":\($0)" } ?? "") } ?? ""
    let aPub = env["FED_A_PUB"] ?? "anna", cName = env["FED_C_NAME"] ?? "Dora Dritt", other = env["FED_OTHER_UID"] ?? "fi-otto"
    var trust: [String: FedTrust] = [:]
    func keys2(_ hs: [String], fresh: Bool = false) async -> [String: FedKeyState] {
        var all: [String: Any] = [:]
        for p in FedFlow.keysPaths(hs, fresh: fresh) { all.merge(obj(await api("GET", p).1)["keys"] as? [String: Any] ?? [:]) { $1 } }
        return FedFlow.evaluate(hs, keys: all, myHandle: myHandle, myIdentPK: own.identPK, stored: { trust[$0] }, save: { trust[$0] = $1 })
    }
    func chatInfo(_ id: Int) async -> (raw: [String: Any], fed: FedChatInfo?) {
        let c = obj(await api("GET", "api/chats/\(id)?device=\(did)").1)["chat"] as? [String: Any] ?? [:]
        return (c, FedChatInfo(c))
    }
    func send2(_ info: FedChatInfo, _ cid: Int, _ content: ChatContent, kind: String = "msg") async -> (Int, [String: Any]) {
        let fk = await keys2(info.handles)
        guard let (body, _) = FedFlow.sendBody(info: info, own: own, keys: fk, plain: content.json(), replyTo: nil, ai: nil, fileIDs: [], kind: kind)
        else { return (0, [:]) }
        let (s, d) = await api("POST", "api/chats/\(cid)/messages", body)
        return (s, obj(d))
    }
    func texts(_ cid: Int, ref: String) async -> [(text: String, verified: Bool, sender: String)] {
        let ms = await messages(cid).filter { ($0["kind"] as? String) == "msg" }
        let fk = await keys2(Array(Set(ms.compactMap { $0["sender_handle"] as? String })))
        return ms.compactMap { m in
            guard let r = FedFlow.open(m, ref: ref, own: own, keys: fk) else { return nil }
            return (r.plain.flatMap { ChatContent(json: $0) }?.text ?? "?", r.verified, m["sender_handle"] as? String ?? "")
        }
    }
    func waitFor(_ secs: Int = 20, _ f: () async -> Bool) async -> Bool {
        for _ in 0..<secs { if await f() { return true }; try? await Task.sleep(for: .seconds(1)) }
        return false
    }

    // MARK: Seite eines anderen Servers (E4/E6)
    let mk = peer("mkshare", "@\(aPub)@\(hostA)", "edit")
    let ids = (mk.split(whereSeparator: \.isNewline).first { $0.hasPrefix("IDS") } ?? "").split(separator: " ").dropFirst().compactMap { Int($0) }
    ok("B: Seite, Kapitel, Datenbank mit 2 Zeilen an A geteilt (\(ids))", ids.count == 5 && mk.contains("share 200"), mk)
    guard ids.count == 5 else { return }
    let (P, Cp, D) = (ids[0], ids[1], ids[2])
    var item: [String: Any] = [:]
    _ = await waitFor(25) {
        let items = obj(await api("GET", "api/fed/shared").1)["items"] as? [[String: Any]] ?? []
        item = items.first { ($0["title"] as? String) == "Fremde Seite" } ?? [:]
        return !item.isEmpty && ((item["remote"] as? [String: Any])?["caps"] as? [Any])?.isEmpty == false
    }
    let peerID = ShareJSON.int((item["remote"] as? [String: Any])?["peer"]) ?? 0
    func rid(_ x: Int) -> Int { -(x * 1024 + peerID) }
    let (rP, rC, rD) = (rid(P), rid(Cp), rid(D))
    let caps = FedCaps((item["remote"] as? [String: Any])?["caps"])
    ok("A: Mit mir geteilt mit remote.caps (\(caps.set.sorted()))", ShareJSON.int(item["id"]) == rP && caps.db && caps.subpages && caps.upload
       && caps.versions && caps.export && caps.presence && caps.chatgroups, "\(item)")
    let (ps, pd) = await api("GET", "api/notes/\(rP)")
    let pa = PageAccess(page: obj(pd), serverVersion: "2.3.0")
    ok("A: fremde Seite (\(ps)): Bearbeiten mit allem, was B kann", ps == 200 && pa.remote && pa.allows(.addSubpage) && pa.allows(.versions) && pa.allows(.export)
       && pa.allows(.wide) && pa.allows(.move) && pa.canUpload && pa.presence && pa.remoteExtras && !pa.allows(.share) && !pa.allows(.delete))

    // Datenbank
    let dbj = obj(await api("GET", "api/notes/\(rD)").1)
    let props = ((dbj["schema"] as? [String: Any])?["props"] as? [Any]) ?? []
    ok("A: fremde Datenbank mit Schema (\(props.count) Spalten)", (dbj["kind"] as? String) == "database" && props.count == 2)
    let rows = obj(await api("GET", "api/notes/\(rD)/rows").1)["rows"] as? [[String: Any]] ?? []
    ok("A: Zeilen der fremden Datenbank (\(rows.count), negative IDs)", rows.count == 2 && rows.allSatisfy { (ShareJSON.int($0["id"]) ?? 0) < 0 })
    let (ns, nd) = await api("POST", "api/notes", ["parent_id": rD, "title": "Zeile von A", "props": ["status": "fertig", "zahl": 7]])
    let newRow = ShareJSON.int(obj(nd)["id"]) ?? 0
    ok("A: Zeile in fremder Datenbank angelegt (\(ns), \(newRow))", ns == 200 && newRow < 0, String(data: nd, encoding: .utf8) ?? "")
    let (us, _) = await api("PATCH", "api/notes/\(newRow)", ["props": ["zahl": 9]])
    let br = peer("rows", String(D))
    ok("B: neue Zeile mit Werten von A (\(us))", us == 200 && br.contains("Zeile von A") && br.contains("\"fertig\"") && br.contains("9"), br)
    var sch = dbj["schema"] as? [String: Any] ?? [:]
    sch["props"] = props + [["id": "notiz", "name": "Notiz", "type": "text"]]
    let (ss, _) = await api("PATCH", "api/notes/\(rD)", ["schema": sch])
    let sch2 = ((obj(await api("GET", "api/notes/\(rD)").1)["schema"] as? [String: Any])?["props"] as? [Any]) ?? []
    ok("A: Spalte in fremder Datenbank ergaenzt (\(ss), \(sch2.count))", ss == 200 && sch2.count == 3)

    // Unterseite anlegen und verschieben
    let (cs2, cd2) = await api("POST", "api/notes", ["parent_id": rP, "title": "Unterseite von A"])
    let sub = ShareJSON.int(obj(cd2)["id"]) ?? 0
    ok("A: Unterseite der fremden Seite (\(cs2), \(sub))", cs2 == 200 && sub < 0 && peer("kids", String(P)).contains("Unterseite von A"))
    let (mv, md) = await api("POST", "api/notes/\(sub)/move", ["parent_id": rC, "index": 0])
    ok("A: Unterseite verschoben (\(mv))", mv == 200 && peer("kids", String(Cp)).contains("Unterseite von A"), String(data: md, encoding: .utf8) ?? "")
    let (wp, _) = await api("PATCH", "api/notes/\(rP)", ["wide": true, "tags": ["foederation"]])
    ok("A: weitere Felder (wide, tags) auf fremder Seite (\(wp))", wp == 200 && peer("note", String(P)).contains("foederation"))

    // Bild hochladen (X-Saga-Note), im Inhalt speichern
    var req = URLRequest(url: URL(string: base + "/api/files")!, timeoutInterval: 60)
    req.httpMethod = "POST"
    req.setValue(cookie, forHTTPHeaderField: "Cookie"); req.setValue("1", forHTTPHeaderField: "X-Saga"); req.setValue(String(rP), forHTTPHeaderField: "X-Saga-Note")
    let bd = "fios2", png = Data(base64Encoded: "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAIAAAD91JpzAAAAFklEQVR4nGP4z8DAwMDAxMDAwMDAAAANAQEBGm/ZbAAAAABJRU5ErkJggg==") ?? Data()
    var mp = Data("--\(bd)\r\nContent-Disposition: form-data; name=\"image\"; filename=\"rot.png\"\r\nContent-Type: image/png\r\n\r\n".utf8)
    mp.append(png); mp.append(Data("\r\n--\(bd)--\r\n".utf8))
    req.setValue("multipart/form-data; boundary=\(bd)", forHTTPHeaderField: "Content-Type")
    let up = try? await URLSession.shared.upload(for: req, from: mp)
    let upURL = ((up.flatMap { ShareJSON.obj($0.0) })?["file"] as? [String: Any])?["url"] as? String ?? ""
    ok("A: Bild in fremde Seite hochgeladen (\(upURL))", upURL.hasPrefix("/api/fed/files/\(rP)/"), up.map { String(data: $0.0, encoding: .utf8) ?? "" } ?? "")
    let (gs, gd) = await api("GET", String(upURL.dropFirst()))
    ok("A: Bild abrufbar (\(gs), \(gd.count) Bytes)", gs == 200 && gd.count > 20)
    let body = "{\"blocks\":[{\"id\":\"b1\",\"type\":\"paragraph\",\"data\":{\"text\":\"Start von Bert\"}},{\"id\":\"i1\",\"type\":\"image\",\"data\":{\"file\":{\"url\":\"\(upURL)\"},\"caption\":\"rot\"}}]}"
    let rev = ShareJSON.int(obj(await api("GET", "api/notes/\(rP)").1)["rev"]) ?? 0
    let (bs, _) = await api("PATCH", "api/notes/\(rP)", ["title": "Fremde Seite", "body": body, "format": "blocks", "rev": rev])
    ok("A: Seite mit Bild gespeichert, bei B als eigene Datei (\(bs))", bs == 200 && peer("note", String(P)).contains("/files/"))

    // Versionen (Rolle Bearbeiten), Export
    let vl = (try? JSONSerialization.jsonObject(with: await api("GET", "api/notes/\(rC)/versions").1)) as? [[String: Any]] ?? []
    var restoreID: Int?
    for v in vl {
        guard let vid = ShareJSON.int(v["id"]) else { continue }
        if (obj(await api("GET", "api/notes/\(rC)/versions/\(vid)").1)["text"] as? String ?? "").contains("Fassung eins") { restoreID = vid; break }
    }
    ok("A: Versionen der fremden Seite (\(vl.count)), Fassung lesbar", !vl.isEmpty && restoreID != nil)
    if let vid = restoreID {
        let (rs, _) = await api("POST", "api/notes/\(rC)/versions/\(vid)/restore", [String: Any]())
        ok("A: Fassung wiederhergestellt (\(rs))", rs == 200 && peer("note", String(Cp)).contains("Fassung eins"))
    }
    let (es, ed) = await api("GET", "api/notes/\(rP)/export")
    let (zs, zd) = await api("GET", "api/notes/\(rP)/export?sub=1")
    ok("A: Export Markdown (\(es)) und ZIP (\(zs))", es == 200 && (String(data: ed, encoding: .utf8) ?? "").contains("Start von Bert")
       && zs == 200 && zd.prefix(2) == Data("PK".utf8))

    // Anwesenheit (+ Live-Abo)
    let (prs, prd) = await api("POST", "api/presence", ["note_id": rP, "editing": true])
    let pres = peer("presence", String(P))
    ok("A: Anwesenheit auf fremder Seite (\(prs)), B sieht Anna mit Herkunft", prs == 200 && (obj(prd)["live"] as? Bool) != false
       && pres.contains("\"remote\": true") && pres.contains(hostA), pres)

    // Seiten-Chat der fremden Seite (Heim B)
    let (pcs, pcd) = await api("POST", "api/chats", ["kind": "page", "note_id": rP, "device": did])
    let pcID = ShareJSON.int((obj(pcd)["chat"] as? [String: Any])?["id"]) ?? 0
    let (_, pcf) = await chatInfo(pcID)
    ok("A: Seiten-Chat der fremden Seite (\(pcs), v2, Heim B)", pcs == 200 && pcf != nil && pcf?.homeSelf == false, String(data: pcd, encoding: .utf8) ?? "")
    if let f = pcf {
        peer("pagechat", String(P))
        let (s, r) = await send2(f, pcID, ChatContent(text: "Zur Seite von A"))
        try? await Task.sleep(for: .seconds(2))
        let rd = peer("read", f.ref)
        ok("A -> Seiten-Chat (\(s)): B liest und prueft", s == 200 && rd.contains("Zur Seite von A") && rd.contains("VERIFIED") && !rd.contains("UNVERIFIED"), "\(r)")
    }

    // MARK: Freie Gruppe ueber drei Server (E5)
    let cSetup = peer("on", "c", "setup")
    ok("C: Gegenseite eingerichtet", cSetup.contains("dset_ok True"), cSetup)
    func person(_ name: String) async -> FedPerson? {
        var hit: FedPerson?
        _ = await waitFor(30) {
            hit = FedChatLogic.people(obj(await api("GET", "api/chat/people?q=" + q(name)).1)["remote"]).first { $0.name == name }
            return hit != nil
        }
        return hit
    }
    let pb = await person(bName), pc = await person(cName)
    ok("A: Bert (B) und Dora (C) im Adressbuch", pb?.handle != nil && pc?.handle != nil)
    let (gs2, gd2) = await api("POST", "api/chats", ["kind": "free", "members": [other], "handles": [pb?.handle ?? "", pc?.handle ?? ""], "device": did])
    let gid = ShareJSON.int((obj(gd2)["chat"] as? [String: Any])?["id"]) ?? 0
    var (graw, gfed) = await chatInfo(gid)
    let hosts = Set(gfed?.members.map(\.host) ?? [])
    ok("A: Gruppe ueber drei Server (\(gs2), v2, Heim A, \(hosts.count) Server)", gs2 == 200 && gfed?.homeSelf == true && (graw["kind"] as? String) == "free"
       && (graw["role"] as? String) == "admin" && hosts.count == 3 && gfed?.members.count == 4, String(data: gd2, encoding: .utf8) ?? "")
    guard let g = gfed else { return }
    let (ts, tr) = await send2(g, gid, ChatContent(type: "title", title: "Drei Server"), kind: "title")
    ok("A: Gruppenname als v2-Nachricht kind 'title' (\(ts))", ts == 200, "\(tr)")
    (graw, gfed) = await chatInfo(gid)
    let tm = graw["title_msg"] as? [String: Any] ?? [:]
    let tk = await keys2([myHandle])
    let topen = FedFlow.open(tm, ref: g.ref, own: own, keys: tk)
    ok("A: title_msg v2 mit sender_handle, geprueft", (tm["sender_handle"] as? String) == myHandle && topen?.verified == true
       && topen?.plain.flatMap { ChatContent(json: $0) }?.title == "Drei Server")
    var binfo = "", cinfo = ""
    _ = await waitFor(20) { binfo = peer("chatinfo", g.ref); cinfo = peer("on", "c", "chatinfo", g.ref); return binfo.contains("TITLE") && cinfo.contains("TITLE") }
    ok("B: Einladung als Anfrage (eingeladen von A, Herkunft), Name geprueft", binfo.contains("CHAT free request") && binfo.contains(hostA)
       && binfo.contains("TITLE VERIFIED title Drei Server"), binfo)
    ok("C: Einladung + Name (Schluessel von A)", cinfo.contains("CHAT free request") && cinfo.contains("TITLE VERIFIED title Drei Server"), cinfo)
    peer("answer", g.ref, "accept"); peer("on", "c", "answer", g.ref, "accept")
    let (ms1, _) = await send2(g, gid, ChatContent(text: "Hallo zusammen"))
    try? await Task.sleep(for: .seconds(2))
    let rb = peer("read", g.ref), rc = peer("on", "c", "read", g.ref)
    ok("A -> Gruppe (\(ms1)): B und C lesen und pruefen", ms1 == 200 && rb.contains("Hallo zusammen") && rc.contains("Hallo zusammen")
       && !rb.contains("UNVERIFIED") && !rc.contains("UNVERIFIED"), rb + rc)
    peer("send", g.ref, "Gruesse von B")
    peer("on", "c", "send", g.ref, "Und von C")
    var got: [(text: String, verified: Bool, sender: String)] = []
    _ = await waitFor(20) { got = await texts(gid, ref: g.ref); return got.count >= 3 }
    ok("A: Nachrichten von B und C angekommen und geprueft (\(got.map(\.text)))", got.contains { $0.text == "Gruesse von B" && $0.verified }
       && got.contains { $0.text == "Und von C" && $0.verified })
    let rc2 = peer("on", "c", "read", g.ref)
    ok("C: Nachricht von B (anderer Server, Schluessel ueber das Heim) geprueft", rc2.contains("Gruesse von B") && !rc2.contains("UNVERIFIED"), rc2)
    let sys = await messages(gid).filter { ($0["kind"] as? String) == "sys" }.map { FedChatLogic.sysLine($0["body_ct"] as? String, myHandle: myHandle) }
    let sysT = sys.map { FedChatLogic.sysText($0, myHandle: myHandle) }
    ok("A: Systemzeile 'hinzugefuegt' mit Herkunft (\(sysT.first ?? "-"))", sys.contains { if case .members(let m) = $0 { return m.added.count >= 2 }; return false }
       && sysT.contains { $0.hasPrefix("Du hast") && $0.contains("(\(bHostOf(pb)))") && $0.contains("(\(bHostOf(pc)))") })
    // Mitglied per Handle hinzufuegen und wieder entfernen
    let hd = await person(env["FED_B_SHORT"] ?? "hd")
    let (as2, ad2) = await api("POST", "api/chats/\(gid)/members", ["handle": hd?.handle ?? ""])
    (graw, gfed) = await chatInfo(gid)
    let hdm = gfed?.members.first { $0.handle == hd?.handle }
    ok("A: Person per Handle hinzugefuegt (\(as2), \(gfed?.members.count ?? 0) Mitglieder)", as2 == 200 && hdm != nil, String(data: ad2, encoding: .utf8) ?? "")
    var allowed = CharacterSet.alphanumerics; allowed.insert(charactersIn: "~.-_")
    let (rs2, rd2) = await api("DELETE", "api/chats/\(gid)/members/" + ((hdm?.username ?? "").addingPercentEncoding(withAllowedCharacters: allowed) ?? ""))
    (graw, gfed) = await chatInfo(gid)
    let sys2 = await messages(gid).filter { ($0["kind"] as? String) == "sys" }.map { FedChatLogic.sysText(FedChatLogic.sysLine($0["body_ct"] as? String, myHandle: myHandle), myHandle: myHandle) }
    ok("A: wieder entfernt (\(rs2)), Systemzeile (\(sys2.last ?? "-"))", rs2 == 200 && gfed?.members.contains { $0.handle == hd?.handle } == false
       && sys2.contains { $0.hasPrefix("Du hast") && $0.contains("entfernt") }, String(data: rd2, encoding: .utf8) ?? "")

    // MARK: Umstellung eines lokalen Seiten-Chats auf v2 (alte Nachrichten v1)
    let lp = ShareJSON.int(obj(await api("POST", "api/notes", ["title": "Erst lokal"]).1)["id"]) ?? 0
    _ = await api("PUT", "api/notes/\(lp)/shares", ["type": "user", "target": other, "role": "edit"])
    let (lcs, lcd) = await api("POST", "api/chats", ["kind": "page", "note_id": lp, "device": did], v2: false)
    let lcid = ShareJSON.int((obj(lcd)["chat"] as? [String: Any])?["id"]) ?? 0
    ok("A: lokaler Seiten-Chat v1 (\(lcs))", lcs == 200 && (obj(lcd)["chat"] as? [String: Any])?["v"] == nil)
    let mid = C.hexID(), ts1 = Int64(Date().timeIntervalSince1970 * 1000)
    if let sealed = C.encryptMessage(chatID: String(lcid), msgID: mid, sender: uid, device: did, signSK: own.sign.sk, ts: ts1, replyTo: nil,
                                     plain: ChatContent(text: "alt (v1)").json()) {
        var k: [String: String] = [:]
        if let a = C.seal(sealed.key, pk: own.enc.pk) { k[did] = C.b64(a) }
        if let b = C.seal(sealed.key, pk: own.backup.pk) { k["backup:" + uid] = C.b64(b) }
        let (v1s, v1d) = await api("POST", "api/chats/\(lcid)/messages", ["id": mid, "ts": ts1, "sender_device": did, "body_ct": sealed.bodyCT, "sig": sealed.sig,
                                                                         "keys": k, "files": [String](), "kind": "msg"], v2: false)
        ok("A: v1-Nachricht im lokalen Seiten-Chat (\(v1s))", v1s == 200, String(data: v1d, encoding: .utf8) ?? "")
    }
    _ = await api("PUT", "api/notes/\(lp)/shares", ["type": "remote", "target": "@\(bPub)@\(bHostOf(pb))", "role": "view"])
    // erst, wenn die Freigabe bei B angekommen ist (sonst merkt sich A "kein Handle" fuer RESOLVE_TTL)
    _ = await waitFor(15) { peer("shared").contains("Erst lokal") }
    var conv: FedChatInfo?
    _ = await waitFor(25) {
        _ = await api("POST", "api/chats", ["kind": "page", "note_id": lp, "device": did])
        conv = await chatInfo(lcid).fed
        return conv?.members.contains { $0.remote } == true
    }
    ok("A: Seiten-Chat auf v2 umgestellt (gleiche ID, Bert Mitglied)", conv != nil)
    let mj = obj(await api("GET", "api/chats/\(lcid)/messages?calls=1&device=\(did)&limit=50").1)
    let lm = (mj["messages"] as? [[String: Any]] ?? []).filter { ($0["kind"] as? String) == "msg" }
    let legacy = lm.first { FedChatLogic.isLegacyV1($0) }
    let lo = legacy.flatMap { FedFlow.openV1($0, chatID: String(lcid), own: own, devices: mj["devices"] as? [String: Any], idents: mj["idents"] as? [String: Any]) }
    ok("A: alte Nachricht ohne sender_handle mit v1 gelesen und geprueft", lo?.plain.flatMap { ChatContent(json: $0) }?.text == "alt (v1)" && lo?.verified == true,
       "\(legacy ?? [:])")
    let lsys = (mj["messages"] as? [[String: Any]] ?? []).filter { ($0["kind"] as? String) == "sys" }.map { FedChatLogic.sysLine($0["body_ct"] as? String, myHandle: myHandle) }
    ok("A: Systemzeile 'ab jetzt ueber Servergrenzen'", lsys.contains { if case .fed = $0 { return true }; return false })
    if let cv = conv {
        let (ns2, _) = await send2(cv, lcid, ChatContent(text: "neu (v2)"))
        let after = await texts(lcid, ref: cv.ref)
        ok("A: neue Nachricht im umgestellten Chat v2 (\(ns2))", ns2 == 200 && after.contains { $0.text == "neu (v2)" && $0.verified })
    }
}

/// Server einer Person aus der Adressbuch-Suche
func bHostOf(_ p: FedPerson?) -> String { p?.host ?? "?" }
