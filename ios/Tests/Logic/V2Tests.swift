import Foundation

// Saga 2.0 (Apps-Welle): Logik-Tests fuer LiveLogic, FederationLogic, PushLogic, AILogic, ChatLogic
func v2Tests() {
    // --- SSE-Parser ---
    var p = SSEParser()
    check(p.feed(line: ": ping 123") == nil, "SSE Kommentar ignoriert")
    check(p.feed(line: "id: b1-7") == nil && p.feed(line: "event: notification") == nil && p.feed(line: "data: {\"unread\":3}") == nil, "SSE Felder")
    let e1 = p.feed(line: "")
    check(e1 == SSEEvent(id: "b1-7", event: "notification", data: "{\"unread\":3}"), "SSE Ereignis bei Leerzeile")
    check(ShareJSON.int(e1?.json["unread"]) == 3, "SSE JSON")
    check(p.feed(line: "") == nil, "SSE doppelte Leerzeile")
    var p2 = SSEParser()
    var evs = p2.feed(bytes: Data("event: delta\r\ndata: {\"t\":\"Hal".utf8))
    check(evs.isEmpty, "SSE zerstueckelt: noch nichts")
    evs += p2.feed(bytes: Data("lo ü\"}\n\nevent: done\ndata: {}\n".utf8))
    evs += p2.feed(bytes: Data("\n".utf8))
    check(evs.count == 2 && evs[0].json["t"] as? String == "Hallo ü" && evs[1].event == "done", "SSE Bytes ueber Grenzen + CRLF + UTF-8")
    var p3 = SSEParser()
    _ = p3.feed(line: "data: a"); _ = p3.feed(line: "data: b")
    check(p3.feed(line: "")?.data == "a\nb" , "SSE mehrzeilige data")
    // 2-KB-Fuellblock (Kommentar) vor dem KI-Strom
    var p4 = SSEParser()
    let filler = ":" + String(repeating: " ", count: 2048) + "\n\n"
    check(p4.feed(bytes: Data(filler.utf8)).isEmpty, "SSE Fuellblock")

    // --- bye / Wiederverbinden ---
    check(LiveReconnect.after(bye: "refresh") == .now && LiveReconnect.after(bye: "limit") == .after(300)
          && LiveReconnect.after(bye: "auth") == .resumeSession && LiveReconnect.after(bye: "account") == .stop
          && LiveReconnect.after(bye: "shutdown") == .after(5) && LiveReconnect.after(bye: "slow") == .after(5), "bye-Gruende")
    check(LiveReconnect.backoff(1) == 2 && LiveReconnect.backoff(3) == 8 && LiveReconnect.backoff(20) == 60, "Wartezeiten")

    // --- Anwesenheit, fremde Aenderungen ---
    let now = Date()
    check(LiveLogic.editing(lastInput: now.addingTimeInterval(-10), now: now, canEdit: true), "bearbeitet: 10 s nach Eingabe")
    check(!LiveLogic.editing(lastInput: now.addingTimeInterval(-31), now: now, canEdit: true), "nicht mehr nach 30 s")
    check(!LiveLogic.editing(lastInput: now, now: now, canEdit: false) && !LiveLogic.editing(lastInput: nil, canEdit: true), "nur mit Bearbeiten-Recht")
    check(LiveLogic.pageChange(eventRev: 5, ownRev: 5, dirty: false, gone: false) == .ignore, "eigene Revision ignorieren")
    check(LiveLogic.pageChange(eventRev: 6, ownRev: 5, dirty: false, gone: false) == .reloadSilently, "fremd, nichts offen: still neu laden")
    check(LiveLogic.pageChange(eventRev: 6, ownRev: 5, dirty: true, gone: false) == .ask, "fremd, eigene Eingaben: Hinweis")
    check(LiveLogic.pageChange(eventRev: nil, ownRev: 5, dirty: true, gone: true) == .gone, "geloescht")
    let users = PresenceUser.others([["username": "me", "name": "Ich", "me": true, "editing": false],
                                     ["username": "anna", "name": "Anna Beispiel", "editing": true], ["username": "bert", "name": ""]])
    check(users.count == 2 && users[0].editing && users[1].name == "bert", "Anwesende ohne mich, Name-Ersatz")
    check(LiveLogic.presenceLine(users) == .many(n: 2, editing: 1) && LiveLogic.presenceLine([users[0]]) == .one(name: "Anna Beispiel", editing: true)
          && LiveLogic.presenceLine([]) == nil, "Anwesenheits-Satz")
    check(LiveLogic.initials("Anna Beispiel") == "AB" && LiveLogic.initials("bert") == "B" && LiveLogic.initials("") == "?", "Anfangsbuchstaben")

    // --- Benachrichtigungen ---
    let n1: [String: Any] = ["id": 10, "kind": "comment.reply", "created": 1.0, "read": false, "actor": ["name": "Anna"], "count": 2, "access": true,
                             "note": ["id": 12, "title": "Plan", "icon": "🌱"], "title": "Plan", "text": "2 neue Antworten", "url": "/notes#12:p2",
                             "thread_id": 7, "block_id": "p2", "excerpt": "Gut"]
    let it = NotifItem(n1)
    check(it?.noteID == 12 && it?.blockID == "p2" && it?.threadID == 7 && it?.openable == true && it?.count == 2, "Benachrichtigung lesen")
    check(it.map(NotifLogic.target) == .page(id: 12, block: "p2"), "Ziel Seite + Block")
    let gone = NotifItem(["id": 11, "kind": "share.added", "access": false, "title": "Kein Zugriff mehr", "text": "x"])
    check(gone?.openable == false && gone.map(NotifLogic.target) == NotifTarget.none, "Kein Zugriff: nicht antippbar")
    let ch = NotifItem(["id": 12, "kind": "chat.message", "title": "Anna", "text": "Neue Nachricht von Anna", "url": "/chats#c5", "chat": ["id": 5, "kind": "dm"]])
    check(ch.map(NotifLogic.target) == .chat(id: 5), "Chat-Benachrichtigung")
    check(NotifLogic.target(url: "/chats#c42") == .chat(id: 42) && NotifLogic.target(url: "/chats#7") == .chat(id: 7)
          && NotifLogic.target(url: "/notes#-2049:abc") == .page(id: -2049, block: "abc") && NotifLogic.target(url: "/") == .none, "Mitteilungs-URLs")
    var list = [it!, gone!]
    var r = NotifLogic.apply(["item": ["id": 13, "kind": "comment.new", "title": "T", "text": "x"], "unread": 4, "replaces": 10], to: list)
    check(r.items.map(\.id) == [13, 11] && r.unread == 4, "gebuendelt ersetzt alte")
    list = r.items
    r = NotifLogic.apply(["unread": 0, "read": "all"], to: list)
    check(r.items.allSatisfy(\.read) && r.unread == 0, "anderswo alle gelesen")
    r = NotifLogic.apply(["unread": 1, "read": [11]], to: list)
    check(r.items.first { $0.id == 11 }?.read == true && r.items.first { $0.id == 13 }?.read == false, "einzelne gelesen")

    // --- Foederation ---
    check(FedLogic.isRemote(-2049) && !FedLogic.isRemote(5), "negative ID = fremd")
    check(FedLogic.isPageID(12) && FedLogic.isPageID(-2049) && !FedLogic.isPageID(-1) && !FedLogic.isPageID(FedLogic.chatsTag) && !FedLogic.isPageID(0), "Seitenleisten-Bereiche sind keine Seiten")
    check(FedLogic.noteID(href: "/notes#-2049") == -2049 && FedLogic.noteID(href: "/notes#12") == 12 && FedLogic.noteID(href: "/notes#12:b") == nil
          && FedLogic.noteID(href: "https://x/notes#1") == nil, "Verweise auch negativ")
    let fd = Data(#"{"items":[{"id":-2049,"title":"Fremd","kind":"page","role":"edit","root":true,"owner":{"name":"Anna (anna@b.example)","handle":"anna@b.example","remote":true},"remote":{"peer":1,"host":"b.example","name":"B"}}],"unreachable":["c.example"]}"#.utf8)
    struct P: Decodable { let id: Int; let title: String; let remote: RemoteRef? }
    let fs = FedLogic.parseShared(fd, as: P.self)
    check(fs.items.count == 1 && fs.items[0].id == -2049 && fs.items[0].remote?.host == "b.example" && fs.unreachable == ["c.example"], "Mit mir geteilt (andere Instanzen)")
    check(FedLogic.parseShared(Data("{}".utf8), as: P.self).items.isEmpty, "ohne Foederation leer")
    check(FedLogic.problem(code: 502, message: "") == .unreachable
          && FedLogic.problem(code: 403, message: "Für Seiten anderer Saga-Instanzen brauchst du einen öffentlichen Namen (Einstellungen).") == .needsPublicName
          && FedLogic.problem(code: 403, message: "Pages from other Saga instances require a public name (Settings).") == .needsPublicName
          && FedLogic.problem(code: 404, message: "Für Seiten anderer Saga-Instanzen nicht verfügbar.") == .notAvailable
          && FedLogic.problem(code: 404, message: "not found") == .other, "Fehler fremder Seiten")
    check(FedLogic.looksLikeRemote("bert@b.example") && FedLogic.looksLikeRemote("@bert@b.example") && !FedLogic.looksLikeRemote("bert") && !FedLogic.looksLikeRemote("a@b"), "name@server erkennen")
    check(FedLogic.isImagePath("/api/fed/files/-2049/abc") && FedLogic.isImagePath("/files/x.png") && !FedLogic.isImagePath("https://x/y.png"), "Bilder fremder Seiten")
    let pa = PageAccess(page: ["id": -2049, "role": "edit", "remote": ["host": "b.example"]])
    check(pa.remote && pa.remoteHost == "b.example" && !pa.allows(.wide) && !pa.allows(.addSubpage) && !pa.allows(.export) && pa.allows(.editContent) && !pa.allows(.share), "fremde Seite: nur Titel/Inhalt")
    check(!PageAccess(page: ["id": 5, "role": "edit"]).remote && PageAccess(page: ["id": -5000]).remote, "lokal/fremd erkannt")
    check(ShareLogic.markMasked(#"<a href="/notes#-2049">Seite ohne Zugriff</a>"#) == "<na>🔒 Seite ohne Zugriff</na>", "Maskierung auch negativ")

    // --- Push ---
    check(PushLogic.hex(Data([0x0a, 0xff, 0x00])) == "0aff00", "Token hex")
    check(PushLogic.env(debug: true) == "sandbox" && PushLogic.env(debug: false) == "production", "APNs-Umgebung")
    let sub = PushLogic.subscription(token: "ab", env: "sandbox", name: String(repeating: "x", count: 100))
    check(sub["type"] as? String == "apns" && (sub["name"] as? String)?.count == 80, "Abo-Daten")
    let ps = PushState(data: Data(#"{"server":{"enabled":true,"apns":false},"settings":{"cats":{"share":true,"comment":false,"chat":true,"publish":true},"names":true,"quiet":{"on":true,"from":"22:00","to":"06:30","tz":"Europe/Berlin"}},"categories":["share","comment","chat","publish"],"devices":[{"id":3,"type":"apns","name":"iPhone","where":"Apple (sandbox)","app":true,"this_device":true,"fails":0,"encrypted":true}],"limit":20}"#.utf8))
    check(ps?.cats["comment"] == false && ps?.names == true && ps?.quietTo == "06:30" && ps?.thisDevice?.id == 3 && ps?.categories.count == 4, "Push-Zustand lesen")
    check(PushLogic.availability(ps) == .appleMissing && PushLogic.availability(nil) == .unsupported, "APNs nicht eingerichtet -> ausgegraut")
    var ps2 = ps!; ps2.apns = true
    check(PushLogic.availability(ps2) == .ok, "APNs eingerichtet")
    ps2.enabled = false
    check(PushLogic.availability(ps2) == .serverOff, "Server aus")
    check(PushLogic.validTime("07:05") && !PushLogic.validTime("24:00") && !PushLogic.validTime("7:5"), "Ruhezeiten HH:MM")
    check(PushLogic.testResult(code: 200, json: ["ok": true, "result": "ok"]) == .ok && PushLogic.testResult(code: 429, json: nil) == .tooFast
          && PushLogic.testResult(code: 200, json: ["result": "fail", "error": "BadDeviceToken"]) == .fail("BadDeviceToken"), "Test senden")

    // --- KI ---
    let st = AIStatus(data: Data(#"{"enabled":true,"host":"ollama.lan:11434","quota":20,"used":5,"quota_left":15,"history_allowed":true,"history":false,"notice":"Inhalte …","targets":["en","es"],"q_max":1000,"blocks_max":50}"#.utf8))
    check(st?.enabled == true && st?.targets == ["en", "es"] && st.flatMap(AILogic.quota)?.left == 15 && st.flatMap(AILogic.quota)?.of == 20, "KI-Status + Kontingent")
    check(AIStatus(data: Data(#"{"enabled":false}"#.utf8))?.enabled == false && AILogic.quota(AIStatus()) == nil, "KI aus / unbegrenzt")
    let src = [AISource(n: 1, pageID: 12, title: "Plan"), AISource(n: 2, pageID: nil, title: "Seite ohne Zugriff")]
    check(AILogic.pieces("Siehe [1] und [2], nicht [3].", sources: src) == [.text("Siehe "), .cite(1, pageID: 12), .text(" und "), .cite(2, pageID: nil), .text(", nicht [3].")], "Verweise [n] nur fuer echte Quellen")
    check(AILogic.pieces("[link](http://x)", sources: src) == [.text("[link](http://x)")], "kein Markdown")
    let dn = AIDone(["quota_left": 3, "blocks": [["id": "a", "type": "paragraph", "replaceable": true], ["id": "b", "type": "list", "replaceable": false]], "segments": ["x", "y"], "truncated": true])
    check(dn.blocks.count == 2 && dn.truncated && dn.quotaLeft == 3, "done lesen")
    check(!AILogic.canReplace(canEdit: true, locked: false, done: dn), "nicht alle ersetzbar -> kein Ersetzen")
    let dn2 = AIDone(["blocks": [["id": "a", "type": "paragraph", "replaceable": true]], "segments": ["x"]])
    check(AILogic.canReplace(canEdit: true, locked: false, done: dn2) && !AILogic.canReplace(canEdit: false, locked: false, done: dn2)
          && !AILogic.canReplace(canEdit: true, locked: true, done: dn2) && !AILogic.canReplace(canEdit: true, locked: false, done: AIDone(["blocks": [["id": "a", "replaceable": true]], "segments": NSNull()])), "Ersetzen-Regeln")
    check(AILogic.validQuestion(" x ", max: 1000) && !AILogic.validQuestion("   ", max: 1000) && !AILogic.validQuestion(String(repeating: "a", count: 1001), max: 1000), "Frage 1-1000 Zeichen")
    check(AILogic.updated(st!, quotaLeft: 14).quotaLeft == 14, "Kontingent nach done")

    // --- Chat-Logik ---
    let cc = ChatContent(type: "text", text: "Hallo [[note:42]] und [[note:7]] [[note:42]]", files: [ChatFileRef(id: "f1", key: "a2V5", name: "a.png", mime: "image/png", size: 3)], notes: [42, 7])
    let back = ChatContent(json: cc.json())
    check(back == cc, "Inhalt hin und zurueck")
    check(ChatContent(json: Data(#"{"v":1,"type":"text","text":"x","neu":1}"#.utf8))?.text == "x", "unbekannte Felder ignorieren")
    check(ChatContent(json: Data(#"{"v":1,"type":"title","title":"Team"}"#.utf8))?.title == "Team", "Gruppenname")
    check(ChatLogic.noteRefs(cc.text) == [42, 7], "Seitenverweise ohne Doppelte")
    check(ChatLogic.pieces("a [[note:5]]b") == [.text("a "), .note(5), .text("b")], "Text/Verweis-Stuecke")
    check(cc.files[0].isImage && !ChatFileRef(id: "x", key: "", name: "a.pdf", mime: "application/pdf", size: 1).isImage, "Bild-Anhang")
    check(ChatLogic.retention(0) == .off && ChatLogic.retention(3600) == .hours(1) && ChatLogic.retention(86400 * 7) == .days(7) && ChatLogic.retention(31_536_000) == .years(1), "Aufbewahrung")
    check(ChatLogic.retentionChoices([0, 3600, 86400, 604800], max: 86400) == [3600, 86400] && ChatLogic.retentionChoices([0, 3600], max: nil) == [0, 3600], "Aufbewahrung mit Server-Grenze")
    let rk = "LNBN-PIGL-JL7T-2DOX-VOJR-AT4Q-PHUZ-P77E"
    check(ChatLogic.confirmLast4("p77e", key: rk) && ChatLogic.confirmLast4("P7-7E", key: rk) && !ChatLogic.confirmLast4("PHUZ", key: rk) && !ChatLogic.confirmLast4("77E", key: rk), "letzte vier Zeichen")
    check(ChatLogic.trust(known: nil, current: "a") == .new && ChatLogic.trust(known: "a", current: "a") == .same && ChatLogic.trust(known: "a", current: "b") == .changed, "TOFU")
    check(ChatLogic.typing(["a": ("Anna", now), "b": ("Bert", now.addingTimeInterval(-6))], now: now) == ["Anna"], "tippt nur 5 s")
    check(!ChatLogic.matches("Hallo", "xyz") && ChatLogic.matches("Grüße", "GRÜ") && ChatLogic.matches("Gruesse", "gru") && !ChatLogic.matches("x", " "), "lokale Suche")
    let num = "03672 14150 98611 54486 57516 88114 68710 78412 97318 13879 97709 10663"
    check(ChatLogic.safetyQR(num) == "saga-safety:1:" + num.replacingOccurrences(of: " ", with: "") && ChatLogic.safetyFromQR(ChatLogic.safetyQR(num)) == ChatLogic.digitsOnly(num)
          && ChatLogic.safetyFromQR("saga-safety:1:123") == nil && ChatLogic.safetyFromQR("x") == nil, "QR der Sicherheitsnummer")
}
