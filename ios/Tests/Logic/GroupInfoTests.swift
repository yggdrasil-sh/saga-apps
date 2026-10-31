import Foundation

// Saga 2.5 Gruppeninfo + Kontakte: Rechte aus can_*, Aktionen je Mitglied, Reihenfolge/Unterzeile (nie Anmeldename),
// Systemzeile admin, Kontaktliste (gone, Sortierung, Auswahl-Kennung). Fixtures: echte Antworten eines Testservers
// (Tests/Logic/FixturesGI, Wegwerf-Konten, Host als a.example).

private func gi(_ name: String) -> [String: Any] {
    let dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent().appendingPathComponent("FixturesGI")
    let d = (try? Data(contentsOf: dir.appendingPathComponent(name + ".json"))) ?? Data()
    return (try? JSONSerialization.jsonObject(with: d)) as? [String: Any] ?? [:]
}

private struct TM: GroupMemberLike {
    var username: String; var name: String; var me = false; var admin = false
    var ref: String? = nil; var sub: String? = nil; var handle: String? = nil
}
private func members(_ chat: [String: Any]) -> [TM] {
    (chat["members"] as? [Any] ?? []).compactMap { v in
        guard let m = v as? [String: Any], let u = m["username"] as? String else { return nil }
        return TM(username: u, name: m["name"] as? String ?? "?", me: ShareJSON.bool(m["me"]), admin: ShareJSON.bool(m["admin"]),
                  ref: ShareJSON.str(m["ref"]), sub: PersonRef.sub(m["sub"]), handle: ShareJSON.str(m["handle"]))
    }
}

func groupInfoTests() {
    // MARK: Rechte
    let old = GroupInfoLogic.rights(kind: "free", role: "admin", canManage: nil, canAdmin: nil, canRename: nil, readonly: false, feature: false)
    check(old.manage && old.rename && !old.admin && !old.readOnlyMembers, "alter Server: Admin verwaltet, keine Admin-Vergabe")
    let oldM = GroupInfoLogic.rights(kind: "free", role: "member", canManage: nil, canAdmin: nil, canRename: nil, readonly: false, feature: false)
    check(!oldM.manage && oldM.rename && !oldM.admin, "alter Server: Mitglied benennt nur um")
    let ro = GroupInfoLogic.rights(kind: "free", role: "admin", canManage: nil, canAdmin: nil, canRename: nil, readonly: true, feature: false)
    check(!ro.manage && !ro.rename, "schreibgeschuetzt (alt): nichts")
    let mixed = GroupInfoLogic.rights(kind: "free", role: "admin", canManage: true, canAdmin: false, canRename: true, readonly: false, feature: true)
    check(mixed.manage && !mixed.admin && mixed.rename, "can_admin false (Heim zu alt): verwalten ja, Admin nein")
    let noFeat = GroupInfoLogic.rights(kind: "free", role: "member", canManage: true, canAdmin: true, canRename: true, readonly: false, feature: false)
    check(!noFeat.manage && !noFeat.admin, "ohne chatadm1: Felder ignoriert, role zaehlt")
    for k in ["group", "page"] {
        let r = GroupInfoLogic.rights(kind: k, role: "admin", canManage: true, canAdmin: true, canRename: true, readonly: false, feature: true)
        check(r.readOnlyMembers && !r.manage && !r.admin && !r.rename, "\(k): nur lesend")
    }
    let dm = GroupInfoLogic.rights(kind: "dm", role: "member", canManage: false, canAdmin: false, canRename: false, readonly: false, feature: true)
    check(!dm.readOnlyMembers && !dm.manage, "Direkt-Chat: nichts")

    // Fixtures: Admin (Bert) und Mitglied (Cara) derselben Gruppe
    let ca = gi("gi_chat_admin")["chat"] as? [String: Any] ?? [:], cm = gi("gi_chat_member")["chat"] as? [String: Any] ?? [:]
    func rightsOf(_ c: [String: Any]) -> GroupRights {
        GroupInfoLogic.rights(kind: c["kind"] as? String ?? "", role: c["role"] as? String ?? "", canManage: c["can_manage"] as? Bool,
                              canAdmin: c["can_admin"] as? Bool, canRename: c["can_rename"] as? Bool, readonly: false, feature: true)
    }
    let ra = rightsOf(ca), rm = rightsOf(cm)
    check(ra.manage && ra.admin && ra.rename && ShareJSON.bool(ca["me_admin"]), "Server: Admin darf alles")
    check(!rm.manage && !rm.admin && rm.rename && !ShareJSON.bool(cm["me_admin"]), "Server: Mitglied nur umbenennen")
    let ms = members(cm)
    check(ms.filter(\.admin).map(\.name).sorted() == ["Anna Test", "Bert Test"], "members[].admin gelesen")

    // MARK: Aktionen je Mitglied
    check(GroupInfoLogic.actions(me: false, admin: false, kind: "free", rights: ra) == [.safety, .makeAdmin, .remove], "Admin: anderes Mitglied")
    check(GroupInfoLogic.actions(me: false, admin: true, kind: "free", rights: ra) == [.safety, .revokeAdmin, .remove], "Admin: anderer Admin")
    check(GroupInfoLogic.actions(me: true, admin: true, kind: "free", rights: ra) == [.revokeAdmin], "Admin: sich selbst nur entziehen")
    check(GroupInfoLogic.actions(me: true, admin: false, kind: "free", rights: ra).isEmpty, "ich ohne Admin: kein Menue")
    check(GroupInfoLogic.actions(me: false, admin: true, kind: "free", rights: rm) == [.safety], "Mitglied: nur Sicherheitsnummer")
    check(GroupInfoLogic.actions(me: false, admin: false, kind: "free", rights: mixed) == [.safety, .remove], "can_admin false: kein Admin-Eintrag")

    // MARK: Reihenfolge und Unterzeile (nie Anmeldename)
    let sorted = GroupInfoLogic.sorted(ms)
    check(sorted.map(\.name) == ["Cara Test", "Anna Test", "Bert Test"], "ich, dann Admins, dann Name: \(sorted.map(\.name))")
    let logins = ms.map(\.username).filter { !$0.hasPrefix("~") }
    for m in ms where !m.me {
        let s = GroupInfoLogic.subline(m, myHandle: nil, remoteChat: false) ?? ""
        check(s.hasPrefix("~") && !logins.contains(where: { s.contains($0) }), "v1: Unterzeile Handle, kein Anmeldename (\(m.name))")
    }
    let c3 = gi("gi_chat_v3")["chat"] as? [String: Any] ?? [:], my3 = c3["my_handle"] as? String
    let m3 = members(c3)
    check(c3["v"] as? Int == 3 && m3.allSatisfy { $0.handle != nil }, "Stufe 3: Handles")
    for m in m3 where !m.me {
        let s = GroupInfoLogic.subline(m, myHandle: my3, remoteChat: false) ?? ""
        check(s.hasPrefix("~") && !s.contains("@"), "Stufe 3 eigener Server: Handle ohne Host (\(s))")
    }
    let far = TM(username: "~aaaaaaaaaaaaaaaa@b.example", name: "Bert", handle: "~aaaaaaaaaaaaaaaa@b.example")
    check(GroupInfoLogic.subline(far, myHandle: "~bbbbbbbbbbbbbbbb@a.example", remoteChat: true) == "auf b.example · ~aaaaaaaaaaaaaaaa", "fremd: auf host")
    check(GroupInfoLogic.subline(TM(username: "x", name: "X", sub: "Xa"), myHandle: nil, remoteChat: false) == "Xa", "ohne Handle: sub")
    check(GroupInfoLogic.subline(TM(username: "loginname", name: "X"), myHandle: nil, remoteChat: false) == nil, "ohne Handle/sub: nichts (nie username)")
    check(GroupInfoLogic.memberPath(4, "~ab@a.example") == "api/chats/4/members/~ab%40a.example", "Pfad PATCH/DELETE kodiert")

    // MARK: Fehler 409/403 (Server-Meldung zeigen)
    let e409 = gi("gi_patch_409"), e403 = gi("gi_patch_403")
    check(e409["status"] as? Int == 409 && ((e409["body"] as? [String: Any])?["error"] as? String ?? "").contains("letzte Admin"), "409 letzter Admin mit Meldung")
    check(e403["status"] as? Int == 403 && ((e403["body"] as? [String: Any])?["error"] as? String) != nil, "403 mit Meldung")
    let pa = gi("gi_patch_admin")["chat"] as? [String: Any]
    check(members(pa ?? [:]).first { $0.name == "Bert Test" }?.admin == true, "PATCH-Antwort: volles Chat-Objekt mit neuer Liste")

    // MARK: Systemzeile admin (echte Zeilen + Varianten)
    let rows = (gi("gi_messages_v3")["messages"] as? [Any] ?? []).compactMap { $0 as? [String: Any] }.filter { $0["kind"] as? String == "sys" }
    check(rows.count == 2, "zwei Systemzeilen admin")
    let bertH = (m3.first { $0.name == "Bert Test" })?.handle
    if rows.count == 2 {
        let l0 = FedChatLogic.sysLine(rows[0]["body_ct"] as? String, myHandle: my3), l1 = FedChatLogic.sysLine(rows[1]["body_ct"] as? String, myHandle: my3)
        guard case .admin(let a0) = l0 else { check(false, "admin geparst"); return }
        check(a0.on && a0.target?.name == "Bert Test" && a0.by?.name == "Anna Test", "admin geparst")
        check(my3 == bertH, "Fixture aus Sicht von Bert")
        check(FedChatLogic.sysText(l0, myHandle: my3) == "Anna Test hat dich zum Admin gemacht.", "Text: hat dich zum Admin gemacht")
        check(FedChatLogic.sysText(l1, myHandle: my3) == "Du bist kein Admin mehr.", "Text: ich selbst entzogen")
        let third = "~zzzzzzzzzzzzzzzz@a.example"                     // Dritte Person auf demselben Server (ohne Host)
        check(FedChatLogic.sysText(l0, myHandle: third) == "Anna Test hat Bert Test zum Admin gemacht.", "Text: Dritte")
        check(FedChatLogic.sysText(l1, myHandle: third) == "Bert Test ist kein Admin mehr.", "Text: Dritte, selbst")
        check(FedChatLogic.sysText(l0, myHandle: nil) == "Anna Test (a.example) hat Bert Test (a.example) zum Admin gemacht.", "Text: ohne eigenen Handle mit Herkunft")
    }
    func line(_ j: [String: Any]) -> FedChatLogic.SysLine {
        FedChatLogic.sysLine(String(data: try! JSONSerialization.data(withJSONObject: j), encoding: .utf8), myHandle: nil)
    }
    let me = "~mmmmmmmmmmmmmmmm@a.example"
    let anna: [String: Any] = ["name": "Anna", "host": "a.example", "handle": me], bert: [String: Any] = ["name": "Bert", "host": "b.example", "handle": "~qrstuvwxyz234567@b.example"]
    check(FedChatLogic.sysText(line(["type": "admin", "target": bert, "on": true, "by": anna]), myHandle: me) == "Du hast Bert (b.example) zum Admin gemacht.", "ich vergebe (fremd mit Host)")
    check(FedChatLogic.sysText(line(["type": "admin", "target": bert, "on": false, "by": anna]), myHandle: me) == "Du hast Bert (b.example) die Admin-Rolle entzogen.", "ich entziehe")
    check(FedChatLogic.sysText(line(["type": "admin", "target": anna, "on": false, "by": bert]), myHandle: me) == "Bert (b.example) hat dich die Admin-Rolle entzogen.", "mir entzogen")
    check(FedChatLogic.sysText(line(["type": "admin", "target": bert, "on": true, "by": NSNull()]), myHandle: me) == "Bert (b.example) ist jetzt Admin.", "ohne by: ohne Urheber")
    check(FedChatLogic.sysText(line(["type": "admin", "target": bert, "on": false]), myHandle: me) == "Bert (b.example) ist kein Admin mehr.", "ohne by: entzogen")
    check(FedChatLogic.sysText(line(["type": "admin", "target": anna, "on": true, "by": anna]), myHandle: me) == "Du bist jetzt Admin.", "ich selbst Admin")

    // MARK: Kontakte
    let ks = ContactsLogic.parse(gi("gi_contacts"))
    check(ks.map(\.name) == ["Dora Test", "Bert Test", "Cara Test"], "zuletzt zuerst, Gleichstand stabil: \(ks.map(\.name))")
    check(ks.allSatisfy { $0.ref.hasPrefix("~") && !$0.remote }, "lokal: ref ist Such-Handle")
    let dora = ks[0]
    check(dora.gone && !ContactsLogic.selectable(dora) && ContactsLogic.subline(dora) == "Konto nicht mehr aktiv", "gone: ausgegraut, nicht waehlbar")
    check(ContactsLogic.selectable(ks[1]) && ContactsLogic.subline(ks[1]) == ks[1].sub, "aktiv: waehlbar, sub als Unterzeile")
    check(NSDictionary(dictionary: ContactsLogic.dmBody(ks[1])) == ["kind": "dm", "user": ks[1].ref], "Direkt-Chat lokal: user = ref")
    check(NSDictionary(dictionary: ContactsLogic.memberBody(ks[1])) == ["user": ks[1].ref], "Mitglied lokal: user = ref")
    let fk = ChatContact(["ref": "~bslpbslpbslpbslp@b.example", "name": "Bert Gast", "sub": "b.example", "host": "b.example", "remote": true, "ready": true, "gone": false, "last_at": 5.0])!
    check(NSDictionary(dictionary: ContactsLogic.dmBody(fk)) == ["kind": "dm", "handle": fk.ref] && NSDictionary(dictionary: ContactsLogic.memberBody(fk)) == ["handle": fk.ref], "fremd: handle = ref")
    check(ContactsLogic.subline(fk) == "Person auf b.example" && fk.fedPerson.handle == fk.ref && fk.fedPerson.host == "b.example", "fremd: Person auf host")
    var fg = fk; fg.gone = true
    check(ContactsLogic.subline(fg) == "Server b.example nicht verbunden", "fremd gone: Server nicht verbunden")
    var nr = ks[1]; nr.ready = false
    check(ContactsLogic.subline(nr) == (nr.sub ?? "") + " · hat Chats noch nicht eingerichtet", "nicht eingerichtet")
    // Mitglied hinzufuegen: ohne Mitglieder; fremde nur in Gruppen ueber Servergrenzen
    let annaView = members(pa ?? [:])                                     // Kontakte sind aus Annas Sicht
    let inGroup = ContactsLogic.forAdd(ks + [fk], members: annaView, fedChat: false)
    check(inGroup.map(\.name) == ["Dora Test"], "ohne Mitglieder und ohne fremde: \(inGroup.map(\.name))")
    check(ContactsLogic.forAdd([fk], members: annaView, fedChat: true).count == 1, "fremde in Gruppe ueber Servergrenzen")
    check(ContactsLogic.deletePath(fk.ref) == "api/chat/contacts/~bslpbslpbslpbslp@b.example" && ContactsLogic.deletePath("~a b") == "api/chat/contacts/~a%20b", "DELETE-Pfad")
    // nach "Aus Kontakten entfernen": Cara fehlt
    check(!ContactsLogic.parse(gi("gi_contacts_after")).contains { $0.name == "Cara Test" }, "entfernt: nicht mehr in der Liste")
    // Personensuche: Kontakte vorne (contact: true)
    let ppl = (gi("gi_people")["users"] as? [Any] ?? []).compactMap { $0 as? [String: Any] }
    check(!ppl.isEmpty && ppl.allSatisfy { ShareJSON.bool($0["contact"]) && ($0["username"] as? String ?? "").hasPrefix("~") }, "people: Kontakte mit contact:true, Handle")
    check(ContactsLogic.listPath == "api/chat/contacts?limit=30", "Liste ~30")
}
