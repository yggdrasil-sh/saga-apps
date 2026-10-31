import Foundation

// Saga 2.5 App-Runde "Chat-Standard": Feature-Gating, Rechte, Seiten-Chat-Mitglieder, Gruppenbild/Beschreibung,
// Pins/Grabsteine, Sperren/Melden, Stummschalten, Erwaehnungen, Formatierung (Vektoren aus static/chat-fmt.js inkl.
// Injection-Faellen), Linkvorschau, Entwuerfe. Fixtures: echte Antworten eines Testservers (Wegwerf-Konten, Host a.example).

private func cs(_ name: String) -> Any? {
    let dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent().appendingPathComponent("FixturesCS")
    guard let d = try? Data(contentsOf: dir.appendingPathComponent(name + ".json")) else { check(false, "Fixture \(name) fehlt"); return nil }
    return try? JSONSerialization.jsonObject(with: d)
}
private func csObj(_ name: String) -> [String: Any] { cs(name) as? [String: Any] ?? [:] }
private func csChat(_ name: String) -> [String: Any] { csObj(name)["chat"] as? [String: Any] ?? [:] }

private struct TM2: GroupMemberLike {
    var username: String; var name: String; var me = false; var admin = false
    var ref: String? = nil; var sub: String? = nil; var handle: String? = nil
}
private func mems(_ c: [String: Any]) -> [TM2] {
    (c["members"] as? [Any] ?? []).compactMap { v in
        guard let m = v as? [String: Any], let u = m["username"] as? String else { return nil }
        return TM2(username: u, name: m["name"] as? String ?? "?", me: ShareJSON.bool(m["me"]), admin: ShareJSON.bool(m["admin"]),
                   ref: ShareJSON.str(m["ref"]), sub: ShareJSON.str(m["sub"]), handle: ShareJSON.str(m["handle"]))
    }
}

/// Baum als JSON-artiger Wert (wie fmtvec.js: Text = String, Element = [tag, kinder])
private func fmtJSON(_ ns: [FmtNode]) -> [Any] {
    ns.map { n -> Any in
        switch n {
        case .text(let s): return s
        case .el(let t, let k): return [t, fmtJSON(k)]
        }
    }
}
private func same(_ a: Any, _ b: Any) -> Bool {
    if let x = a as? String, let y = b as? String { return x == y }
    if let x = a as? [Any], let y = b as? [Any] { return x.count == y.count && zip(x, y).allSatisfy { same($0, $1) } }
    return false
}

func chatStdTests() {
    // MARK: Feature-Gating
    let ver = csObj("cs_version")
    let fAll = ChatStdFeatures(list: (ver["features"] as? [Any] ?? []).compactMap { $0 as? String })
    check(fAll.pageMembers && fAll.groupInfo2 && fAll.modDel && fAll.msgPin && fAll.block && fAll.report && fAll.mute2 && fAll.chatPin
          && fAll.markUnread && fAll.hide && fAll.mention && fAll.fmt && fAll.unfurl && fAll.media, "Server: alle Chat-Standard-Features")
    let f0 = ChatStdFeatures()
    check(!f0.groupInfo2 && !f0.mute2 && !f0.fmt && !f0.unfurl, "alter Server: nichts")
    var noUnf = fAll; noUnf.unfurl = false
    check(fAll != noUnf, "unfurl1 einzeln schaltbar (Admin-Schalter)")

    // MARK: Felder des Chat-Objekts (Server-Antworten)
    let ia = ChatStdInfo(csChat("cs_chat_admin")), im = ChatStdInfo(csChat("cs_chat_member"))
    check(ia.permInfo == "admins" && ia.permSend == "admins" && ia.canSend == true && ia.canPerms && ia.canPin && ia.canModerate && ia.canInfo2,
          "Admin: Rechte gelesen")
    check(im.canSend == false && im.canEditInfo == false && !im.canPerms && !im.canPin && !im.canModerate && !im.canInfo2, "Mitglied: eingeschraenkt")
    check(ia.pins.count == 2 && ia.pins == im.pins, "pins: zwei (dritte war die geloeschte) \(ia.pins.count)")
    check(ia.descMsgID != nil && ia.avatarMsgID != nil, "desc_msg/avatar_msg vorhanden")
    check(ia.meRef?.hasPrefix("~") == true && !(ia.meRef ?? "").contains("cx-"), "me_ref ist Kennung, kein Anmeldename")
    let old = ChatStdInfo([:])
    check(old.canSend == nil && old.mutedUntil == nil && old.pins.isEmpty && old.notify == "all", "aeltere Antwort: Felder fehlen")
    let bad = ChatStdInfo(["notify": "laut", "perm_send": "x", "pins": ["<script>", "0123456789abcdef"]])
    check(bad.notify == "all" && bad.permSend == "all" && bad.pins == ["0123456789abcdef"], "ungueltige Werte verworfen")

    // MARK: Rechte-Ableitung
    check(ChatStd.composerBlock(info: im, f: fAll) == String(localized: "In dieser Gruppe dürfen nur Admins schreiben."), "can_send false: Hinweis")
    check(ChatStd.composerBlock(info: im, f: f0) == nil, "ohne Feature: kein Hinweis")
    check(ChatStd.composerBlock(info: ia, f: fAll) == nil, "Admin schreibt")
    let dmB = ChatStdInfo(csChat("cs_chat_dm_blocked"))
    check(dmB.blocked && dmB.canSend == false && ChatStd.composerBlock(info: dmB, f: fAll)?.contains("gesperrt") == true, "DM gesperrt: Hinweis Entsperren")
    check(ChatStd.canEditInfo2(kind: "free", info: ia, readonly: false, f: fAll) && !ChatStd.canEditInfo2(kind: "free", info: im, readonly: false, f: fAll),
          "Bild/Beschreibung nur bei can_info2")
    check(!ChatStd.canEditInfo2(kind: "free", info: ia, readonly: true, f: fAll) && !ChatStd.canEditInfo2(kind: "free", info: ia, readonly: false, f: f0),
          "schreibgeschuetzt/ohne Feature: nein")
    check(ChatStd.showPerms(kind: "free", info: im, f: fAll) && !ChatStd.showPerms(kind: "free", info: ChatStdInfo(["can_perms": false]), f: fAll),
          "Gruppenrechte: Mitglied sieht eingeschraenkte, sonst nur Admins")
    check(!ChatStd.canRename(base: true, info: im, f: fAll) && ChatStd.canRename(base: true, info: im, f: f0) && ChatStd.canRename(base: true, info: ia, f: fAll),
          "can_rename folgt can_edit_info")

    // MARK: Seiten-Chat-Mitglieder (6a)
    let po = csChat("cs_page_owner"), pm = csChat("cs_page_member")
    let pmems = (po["members"] as? [[String: Any]] ?? [])
    let owner = pmems.first { ShareJSON.bool($0["me"]) } ?? [:], other = pmems.first { !ShareJSON.bool($0["me"]) } ?? [:]
    let pr = GroupInfoLogic.rights(kind: "page", role: "member", canManage: po["can_manage"] as? Bool, canAdmin: false, canRename: false, readonly: false, feature: true)
    check(ChatStd.canAdd(kind: "page", rights: pr, canManage: po["can_manage"] as? Bool, f: fAll), "Seiten-Chat: Eigentuemer fuegt hinzu")
    check(!ChatStd.canAdd(kind: "page", rights: pr, canManage: pm["can_manage"] as? Bool, f: fAll), "Seiten-Chat: Mitglied nicht")
    check(!ChatStd.canAdd(kind: "page", rights: pr, canManage: true, f: f0), "ohne pagechatmembers1: nicht")
    check(!ChatStd.canAdd(kind: "group", rights: pr, canManage: true, f: fAll), "Gruppen-Chat: nie")
    check(ChatStd.pageRemove(me: false, member: PageMemberInfo(other), canManage: true, f: fAll) == .remove, "direkte Freigabe: entfernen")
    check(ChatStd.pageRemove(me: true, member: PageMemberInfo(owner), canManage: true, f: fAll) == .none, "ich: kein Eintrag")
    check(ChatStd.pageRemove(me: false, member: PageMemberInfo(via: "owner", removable: false), canManage: true, f: fAll) == .disabled(String(localized: "Ihr gehört die Seite.")), "Eigentuemer: ausgegraut")
    if case .disabled(let t) = ChatStd.pageRemove(me: false, member: PageMemberInfo(via: "group", removable: false), canManage: true, f: fAll) {
        check(t.contains("Gruppe"), "ueber Gruppe: Erklaerung")
    } else { check(false, "ueber Gruppe: ausgegraut") }
    check(ChatStd.pageRemove(me: false, member: PageMemberInfo(pm["members"].flatMap { ($0 as? [[String: Any]])?.first } ?? [:]), canManage: false, f: fAll) == .none,
          "Mitglied (ohne via/removable): kein Eintrag")
    check(((pm["members"] as? [[String: Any]]) ?? []).allSatisfy { $0["via"] == nil && $0["removable"] == nil }, "Server verraet via nur dem Eigentuemer")
    check(ChatStd.membersHint(kind: "page", canManage: true, f: fAll) == String(localized: "Personen hinzufügen teilt diese Seite mit ihnen. Entfernen entzieht die Freigabe."), "Hinweis page")
    check(ChatStd.membersHint(kind: "group", canManage: nil, f: fAll) == String(localized: "Mitglieder ergeben sich aus der Benutzergruppe – dort verwalten."), "Hinweis group")
    let pb = ChatStd.pageAddBody(["user": "~x"], role: .comment)
    check(pb["role"] as? String == "comment" && pb["user"] as? String == "~x", "Seiten-Chat hinzufuegen mit Rolle")
    check(ChatStd.pageAddBody([:], role: .owner)["role"] as? String == "view", "Rolle owner nicht vergebbar")
    check((csChat("cs_page_add")["member_count"] as? Int) == 3, "Server: Hinzufuegen im Seiten-Chat (role comment)")

    // MARK: Bild, Beschreibung (Steuer-Nachrichten)
    let long = String(repeating: "ä", count: 600)
    check((ChatStd.descBody(long)["desc"] as? String)?.count == 500 && ChatStd.descBody("x")["type"] as? String == "desc", "Beschreibung gekuerzt")
    let av = ChatFileRef(id: "f1", key: "k", name: "gruppe.jpg", mime: "image/jpeg", size: 9, w: 256, h: 256)
    let ab = ChatStd.avatarBody(av)
    check((ab["file"] as? [String: Any])?["id"] as? String == "f1" && (ab["files"] as? [Any])?.count == 1, "Gruppenbild-Inhalt")
    check(ChatStd.avatarBody(nil)["file"] is NSNull, "Bild entfernen: file null")
    let ctrlD = try? JSONSerialization.data(withJSONObject: ["v": 1, "type": "desc", "desc": "Hallo"])
    check(ChatStd.control(ctrlD) == .desc("Hallo"), "desc lesen")
    let ctrlA = try? JSONSerialization.data(withJSONObject: ["v": 1, "type": "avatar", "file": NSNull()])
    check(ChatStd.control(ctrlA) == .avatar(nil), "avatar null lesen")
    check(ChatStd.control(Data("{\"type\":\"title\"}".utf8)) == nil, "andere Steuer-Nachricht ignoriert")

    // MARK: Systemzeile perm (Stufe 3, echte Antwort)
    let sysPerm = (csObj("cs_messages_v3_perm")["messages"] as? [[String: Any]] ?? []).first { $0["kind"] as? String == "sys" }
    let line = FedChatLogic.sysLine(sysPerm?["body_ct"] as? String, myHandle: "~zzzzzzzzzzzzzzzz@a.example")
    if case .perm(let pi, let ps, let by) = line {
        check(ps == "admins" && pi == nil && by?.name == "Emil Test", "perm-Zeile gelesen")
        check(FedChatLogic.sysText(line, myHandle: "~zzzzzzzzzzzzzzzz@a.example") == "Emil Test hat die Gruppenrechte geändert. Nur Admins dürfen jetzt schreiben.", "perm-Text")
        check(FedChatLogic.sysText(line, myHandle: by?.handle).hasPrefix("Du hat die Gruppenrechte"), "ich: \"Du\" (wie Web)")
    } else { check(false, "perm-Zeile erkannt") }
    check(ChatStd.permText(permInfo: "all", permSend: nil, by: nil, myHandle: nil) == "Ein Admin hat die Gruppenrechte geändert. Alle dürfen jetzt Name, Bild und Beschreibung ändern.", "perm ohne by")
    let admLine = FedChatLogic.sysLine(#"{"type":"admin","target":{"name":"B","handle":"~bbbbbbbbbbbbbbbb@a.example"},"on":true,"by":{"name":"A","handle":"~aaaaaaaaaaaaaaaa@a.example"}}"#, myHandle: nil)
    check(FedChatLogic.sysText(admLine, myHandle: "~cccccccccccccccc@a.example") == "A hat B zum Admin gemacht.", "admin-Zeile unveraendert")

    // MARK: Grabsteine, Pins
    let tm = csObj("cs_messages_tombs")["messages"] as? [[String: Any]] ?? []
    let tombs = tm.filter(ChatStd.isTomb)
    check(tombs.count == 1 && ShareJSON.bool(tombs[0]["by_admin"]) && (tombs[0]["body_ct"] as? String ?? "").isEmpty && ShareJSON.str(tombs[0]["key"]) == nil, "Grabstein ohne Inhalt/Schluessel")
    check(ChatStd.tombText(sender: "Cara") == "Nachricht von Cara – von einem Admin gelöscht" && ChatStd.tombText(sender: nil) == "Von einem Admin gelöscht", "Grabstein-Text")
    check(ShareJSON.bool(csObj("cs_moddel")["by_admin"]), "DELETE fremde: by_admin")
    check(ChatStd.canModDelete(mine: false, info: ia, f: fAll) && !ChatStd.canModDelete(mine: true, info: ia, f: fAll) && !ChatStd.canModDelete(mine: false, info: im, f: fAll),
          "Loeschen (Admin) nur fremde, nur can_moderate")
    check(ChatStd.pinAction(id: ia.pins[0], info: ia, f: fAll, delivered: true) == false && ChatStd.pinAction(id: "ffffffffffffffff", info: ia, f: fAll, delivered: true) == true,
          "Anheften/Loesen")
    check(ChatStd.pinAction(id: "x", info: im, f: fAll, delivered: true) == nil && ChatStd.pinAction(id: "x", info: ia, f: fAll, delivered: false) == nil, "ohne can_pin/wartend: nichts")
    check((csObj("cs_pin_409")["status"] as? Int) == 409 && (csObj("cs_pin_409")["error"] as? String)?.contains("3") == true, "4. Pin: 409 mit Meldung")
    check(ChatStd.pinShown(["a", "b", "c"], index: 0)?.id == "c" && ChatStd.pinShown(["a", "b", "c"], index: 2)?.id == "a" && ChatStd.pinShown([], index: 0) == nil,
          "Leiste: neueste zuerst")

    // MARK: Sperren, Melden
    check(csObj("cs_block")["target"] as? String == csObj("cs_blocks")["blocks"].flatMap { ($0 as? [[String: Any]])?.first?["target"] as? String }, "Sperre: stabiler Handle")
    let bl = ChatStd.blocks(csObj("cs_blocks"))
    check(bl.count == 1 && bl[0].local && bl[0].name == "Bert Test" && !bl[0].target.contains("cx-"), "Sperrliste ohne Anmeldenamen")
    check(ChatStd.unblockPath("~abc@a.example") == "api/chat/blocks?target=~abc%40a.example", "Entsperren: URL-kodiert")
    let gb = csChat("cs_chat_group_blocked")
    let bm = (gb["members"] as? [[String: Any]] ?? []).first { ShareJSON.bool($0["blocked"]) }
    check(bm?["name"] as? String == "Bert Test", "members[].blocked in Gruppe")
    check(ChatStd.collapsed(kind: "free", senderBlocked: true, mine: false, f: fAll) && !ChatStd.collapsed(kind: "dm", senderBlocked: true, mine: false, f: fAll)
          && !ChatStd.collapsed(kind: "free", senderBlocked: true, mine: false, f: f0), "einklappen nur in Gruppen")
    check(ChatStd.blockTarget(senderHandle: nil, member: (handle: nil, ref: "~abcdefghijklmnop")) == "~abcdefghijklmnop"
          && ChatStd.blockTarget(senderHandle: nil, member: (handle: nil, ref: "anmeldename")) == nil, "Sperr-Ziel nie Anmeldename")
    let rb = ChatStd.reportBody(chat: 4, msgs: (0..<30).map { String($0) }, reason: .abuse, text: "  x  ")
    check((rb["msgs"] as? [String])?.count == 20 && rb["reason"] as? String == "abuse" && rb["text"] as? String == "x", "Meldung: hoechstens 20, Text getrimmt")
    check(ChatStd.reportText([("Bert", "Werbung"), ("", "zwei")]) == "Bert: Werbung\nzwei", "vorgefuellter Text")
    check(ChatStd.ReportReason.allCases.map(\.rawValue) == ["spam", "abuse", "illegal", "other"], "Gruende wie Server")
    check(csObj("cs_report")["ok"] as? Bool == true, "Server nimmt Meldung an")

    // MARK: Stummschalten (Anzeige)
    let now = Date(timeIntervalSince1970: 1_800_000_000)
    check(ChatStd.mutedUntil(.hour, now: now) == 1_800_003_600 && ChatStd.mutedUntil(.forever, now: now) == -1 && ChatStd.mutedUntil(.off, now: now) == 0
          && ChatStd.mutedUntil(.week, now: now) == 1_800_604_800, "Dauern")
    check(ChatStd.isMuted(mutedUntil: -1, muted: false, now: now) && !ChatStd.isMuted(mutedUntil: 1_799_999_999, muted: true, now: now)
          && ChatStd.isMuted(mutedUntil: nil, muted: true, now: now), "jetzt stumm (abgelaufen = aus, alt: muted)")
    let fmtD: (Date) -> String = { _ in "Mo 14:00" }
    check(ChatStd.muteLabel(ChatStd.muteState(mutedUntil: 1_800_003_600, muted: true, notify: "all", now: now), format: fmtD) == "Stumm bis Mo 14:00", "stumm bis …")
    check(ChatStd.muteLabel(ChatStd.muteState(mutedUntil: -1, muted: true, notify: "all", now: now), format: fmtD) == "Stummgeschaltet", "immer")
    check(ChatStd.muteLabel(ChatStd.muteState(mutedUntil: 0, muted: false, notify: "mentions", now: now), format: fmtD) == "Nur Erwähnungen", "nur Erwaehnungen")
    check(ChatStd.muteLabel(ChatStd.muteState(mutedUntil: 0, muted: false, notify: "none", now: now), format: fmtD) == "Mitteilungen aus", "keine")
    let mp = ChatStdInfo(csChat("cs_patch_mute_pin"))
    check(mp.notify == "mentions" && mp.pinned && mp.pinnedAt > 0 && (mp.mutedUntil ?? 0) > 1_700_000_000, "Server: PATCH stumm/notify/pinned zusammen")
    check((csObj("cs_hide_400")["status"] as? Int) == 400, "Direkt-Chat ausblenden: 400")
    let ph = ChatStdInfo(csChat("cs_page_hidden"))
    check(ph.hidden && ph.canHide, "Seiten-Chat ausgeblendet")
    let lst = csObj("cs_list"), lh = csObj("cs_list_hidden")
    check(ShareJSON.int(lst["hidden_count"]) == 1 && (lst["chats"] as? [[String: Any]] ?? []).allSatisfy { !ShareJSON.bool($0["hidden"]) }, "Liste ohne ausgeblendete, hidden_count")
    check((lh["chats"] as? [[String: Any]] ?? []).count == 1, "?hidden=1: nur ausgeblendete")
    check(ShareJSON.bool((lst["chats"] as? [[String: Any]])?.first?["pinned"]), "Server: angeheftete oben")
    let sorted = ChatStd.sortPinned([(1, false, 0.0), (2, true, 5.0), (3, true, 2.0), (4, false, 0.0)], pinned: { $0.1 }, at: { $0.2 }).map(\.0)
    check(sorted == [3, 2, 1, 4], "angeheftete oben nach pinned_at")
    let um = ChatStdInfo(csChat("cs_patch_unread"))
    check(um.unreadMark && ShareJSON.int(csChat("cs_patch_unread")["unread"]) == 1, "ungelesen markiert: unread 1")
    check(ChatStd.offerMarkUnread(unread: 0, mark: false, f: fAll) && !ChatStd.offerMarkUnread(unread: 2, mark: false, f: fAll) && !ChatStd.offerMarkUnread(unread: 0, mark: false, f: f0),
          "Als ungelesen markieren anbieten")

    // MARK: Erwaehnungen
    let gm = mems(csChat("cs_chat_member"))
    let cand = ChatStd.mentionCandidates(gm, query: "an")
    check(cand.count == 1 && cand[0].member.name == "Anna Test" && cand[0].ref.hasPrefix("~"), "@an -> Anna (Kennung)")
    check(ChatStd.mentionCandidates(gm, query: "").allSatisfy { !$0.member.me && !$0.ref.contains("cx-") }, "nie ich, nie Anmeldename")
    let ref = cand[0].ref
    let ap = ChatStd.applyMentions("Hallo @Anna Test und @Anna Test!", people: ["@Anna Test": ref, "@Anna": "~zzzzzzzzzzzzzzzz"])
    check(ap.text == "Hallo <@\(ref)> und <@\(ref)>!" && ap.refs == [ref], "Token + mentions (laengstes Label zuerst)")
    check(ChatStd.mentionLabel("Anna   Test") == "@Anna Test" && ChatStd.completeMention("Hi @an", label: "@Anna Test") == "Hi @Anna Test ", "Label + Vervollstaendigen")
    check(ChatStd.mentionName(ref, members: gm, meRef: nil, myHandle: nil, myName: "Ich") == "Anna Test", "Kennung -> Name")
    check(ChatStd.mentionName("~qqqqqqqqqqqqqqqq", members: gm, meRef: nil, myHandle: nil, myName: "Ich") == nil, "unbekannt -> nil")
    let me = ChatStdInfo(csChat("cs_chat_member")).meRef
    check(ChatStd.mentionName(me ?? "", members: gm, meRef: me, myHandle: nil, myName: "Ich") == "Ich", "ich -> eigener Name")
    check(ChatStd.mentionsMe("x <@\(me ?? "")> y", meRef: me, myHandle: nil) && !ChatStd.mentionsMe("<@\(ref)>", meRef: me, myHandle: nil), "Hervorhebung nur bei mir")
    check(ChatStd.plainMentions("Hi <@\(ref)> <@~qqqqqqqqqqqqqqqq>", name: { ChatStd.mentionName($0, members: gm, meRef: nil, myHandle: nil, myName: "Ich") })
          == "Hi @Anna Test @Unbekannt", "Vorschau: @Name / @Unbekannt")
    let ms = csObj("cs_mention_send")
    check(ms["status"] as? Int == 200 && ((ms["sent_mentions"] as? [String])?.first ?? "").hasPrefix("~"), "Server nimmt mentions an")
    let manyPeople = Dictionary(uniqueKeysWithValues: (0..<60).map { ("@P\($0)x", "~" + String(format: "%016d", $0)) })
    check(ChatStd.applyMentions(manyPeople.keys.joined(separator: " "), people: manyPeople).refs.count == 50, "hoechstens 50")
    check(ChatFmt.leafPieces("a <@~abcdefghijklmnop@b.example> b") == [.text("a "), .mention("~abcdefghijklmnop@b.example"), .text(" b")], "Token erkannt")
    check(ChatFmt.leafPieces("<@anna>") == [.text("<@anna>")], "kein Token aus Anmeldename")

    // MARK: Formatierung - Vektoren aus static/chat-fmt.js (identisch)
    let vecs = cs("fmt_vectors") as? [[String: Any]] ?? []
    check(vecs.count >= 60, "Vektoren geladen (\(vecs.count))")
    var fmtBad = 0, stripBad = 0
    for v in vecs {
        let input = v["in"] as? String ?? ""
        if !same(fmtJSON(ChatFmt.render(input)), v["tree"] ?? []) { fmtBad += 1; if fmtBad <= 5 { print("  fmt: \(input.debugDescription) -> \(ChatFmt.render(input))") } }
        if ChatFmt.strip(input) != v["strip"] as? String { stripBad += 1; if stripBad <= 5 { print("  strip: \(input.debugDescription) -> \(ChatFmt.strip(input).debugDescription)") } }
    }
    check(fmtBad == 0, "render wie chat-fmt.js (\(fmtBad) abweichend)")
    check(stripBad == 0, "strip wie chat-fmt.js (\(stripBad) abweichend)")
    // Injection: nur feste Elemente, HTML bleibt Text, keine Links aus Markdown
    func tags(_ ns: [FmtNode]) -> Set<String> { Set(ns.flatMap { n -> [String] in if case .el(let t, let k) = n { return [t] + tags(k) }; return [] }) }
    let xss = ChatFmt.render("<img src=x onerror=\"alert(1)\"> *<script>alert(2)</script>* [x](javascript:alert(3)) a_b_c 2*3*4")
    check(tags(xss) == ["strong"], "Injection: nur strong, kein HTML-Element")
    check(ChatFmt.leafPieces("[x](javascript:alert(3)) data:text/html,x").allSatisfy { if case .url = $0 { return false }; return true }, "keine javascript:/data:-Links")
    check(ChatFmt.leafPieces("siehe https://a.example/x_y.") == [.text("siehe "), .url("https://a.example/x_y"), .text(".")], "Adresse ohne Satzzeichen")
    let allowed: Set<String> = ["strong", "em", "s", "code", "ul", "li", "blockquote", "pre"]
    check(vecs.allSatisfy { tags(ChatFmt.render($0["in"] as? String ?? "")).isSubset(of: allowed) }, "nur erlaubte Elemente")
    let bl1 = ChatFmt.blocks(ChatFmt.render("a\n- b\n- c\n> q\n```\nx\n```"))
    check(bl1 == [.para([.text("a")]), .list([[.text("b")], [.text("c")]]), .quote([.para([.text("q")])]), .pre("x")], "Bloecke fuer die Darstellung")

    // MARK: Linkvorschau
    let un = ChatStd.Unfurl(csObj("cs_unfurl"))
    check(un?.title == "Ein Artikel zum Test" && un?.site == "Testblatt" && un?.image == nil, "Server-Vorschau gelesen")
    check(ChatStd.Unfurl([:]) == nil && ChatStd.Unfurl(["url": "https://a.example", "title": ""]) == nil && ChatStd.Unfurl(["url": "javascript:x", "title": "t"]) == nil,
          "leer/ohne Titel/kein http -> keine Karte")
    check((csObj("cs_unfurl_400")["status"] as? Int) == 400, "ftp -> 400")
    check(ChatStd.firstURL("schau https://a.example/x, und http://b.example") == "https://a.example/x" && ChatStd.firstURL("kein link") == nil, "erste Adresse")
    check(ChatStd.unfurlPath("https://a.example/a b?x=1&y") == "api/chat/unfurl?url=https%3A%2F%2Fa.example%2Fa%20b%3Fx%3D1%26y", "Abfrage kodiert")
    let img = ChatThumbRef(id: "i1", key: "k", w: 40, h: 20, size: 99)
    let pv = ChatStd.Preview(un!, img: img)
    check(ChatStd.Preview(pv.dict) == pv, "Vorschau im Inhalt: hin und zurueck")
    check(ChatStd.Preview(["url": "javascript:alert(1)", "title": "x"]) == nil, "Empfaenger: nur http/https")
    check(ChatStd.fileIDs([ChatFileRef(id: "a", key: "k", name: "", mime: "image/png", size: 1, thumb: ChatThumbRef(id: "t", key: "k", w: 1, h: 1, size: 1))], preview: pv) == ["a", "t", "i1"],
          "Bild der Vorschau in files")
    var cc = ChatContent(text: "x"); cc.preview = pv
    check(ChatContent(json: cc.json())?.preview == pv, "ChatContent traegt preview")

    // MARK: Medienuebersicht
    let hf = csObj("cs_messages_hasfile")["messages"] as? [[String: Any]] ?? []
    check(hf.count == 1 && hf.allSatisfy { !ChatStd.isTomb($0) }, "has=file: nur Nachricht mit Anhang, kein Grabstein")
    check((csObj("cs_haslink_400")["status"] as? Int) == 400, "has=link -> 400")
    check(ChatStd.mediaPath(5, device: "device=d", before: 12) == "api/chats/5/messages?device=d&has=file&limit=100&before=12", "Pfad mit before")
    var voice = ChatFileRef(id: "v", key: "k", name: "", mime: "audio/ogg", size: 1); voice.voice = true
    check(ChatStd.mediaKind(ChatFileRef(id: "a", key: "k", name: "", mime: "image/jpeg", size: 1)) == .media && ChatStd.mediaKind(voice) == .skip
          && ChatStd.mediaKind(ChatFileRef(id: "b", key: "k", name: "", mime: "application/pdf", size: 1)) == .file, "Raster/Liste/Sprachnachricht")

    // MARK: Entwuerfe
    check(ChatStd.draftKey(account: "u1", chatID: 7) == "saga-chat-draft:u1:7", "Schluessel je Konto und Chat")
    check(ChatStd.draft("   ", people: [:], now: now) == nil, "leer -> kein Entwurf")
    let dr = ChatStd.draft("Hi @Anna Test", people: ["@Anna Test": ref, "@Bert": "~b"], now: now)
    check(dr?.p == ["@Anna Test": ref] && dr?.t == "Hi @Anna Test", "Entwurf behaelt nur benutzte Erwaehnungen")
    check(ChatStd.draft(String(repeating: "x", count: 30_000), people: [:], now: now)?.t.count == 20_000, "Entwurf gekuerzt")
    check(ChatStd.foreignDraftKeys(["saga-chat-draft:u1:7", "saga-chat-draft:u2:7", "anderes"], account: "u1") == ["saga-chat-draft:u2:7"], "fremde Konten verworfen")
    check(ChatStd.foreignDraftKeys(["saga-chat-draft:u1:7", "x"], account: nil) == ["saga-chat-draft:u1:7"], "Abmelden: alle Entwuerfe")
}
