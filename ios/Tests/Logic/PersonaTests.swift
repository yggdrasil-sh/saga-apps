import Foundation

// KI-Persona und "KI im Chat - nur auf Zuruf" (2.1, App/AILogic.swift, ChatContent in App/ChatLogic.swift)
func personaTests() {
    // Persona lesen
    let p = AIPersona(["name": " Marin ", "custom_name": true, "avatar": "/api/ai/avatar?v=3", "avatar_etag": "1f0c", "enabled": true])
    check(p?.name == "Marin" && p?.customName == true && p?.avatar == "/api/ai/avatar?v=3" && p?.avatarEtag == "1f0c" && p?.enabled == true, "Persona")
    check(AIPersona(["name": "", "avatar": nil]) == nil, "ohne Namen keine Persona")
    check(AIPersona(["name": "X", "avatar": "https://evil.example/a.png"])?.avatar == nil, "fremde Bild-Adresse verworfen")
    check(AIPersona(["name": "X", "avatar": "/api/avatar/abc"])?.avatar == nil, "nur /api/ai/avatar")
    check(AIPersona(["name": "X", "avatar": NSNull()])?.avatar == nil, "null = Symbol")
    check(AIPersona(["name": "X"])?.enabled == nil, "Live-Ereignis ohne enabled")
    let merged = AIPersona(name: "A", enabled: true).merged(AIPersona(name: "B"))
    check(merged.name == "B" && merged.enabled == true, "Live-Ereignis behaelt enabled")
    // Status
    let st = AIStatus(data: Data(#"{"enabled":true,"host":"llm.example","chat":true,"persona":{"name":"Marin","custom_name":true,"avatar":null}}"#.utf8))
    check(st?.chat == true && st?.persona?.name == "Marin" && st?.persona?.avatar == nil, "Status mit Persona")
    let old = AIStatus(data: Data(#"{"enabled":true,"host":"x"}"#.utf8))
    check(old?.persona == nil && old?.chat == false, "aelterer Server: keine Persona, kein Chat")
    // Profilbild-Adresse
    check(AvatarLogic.url(server: "https://s.example", avatar: "/api/ai/avatar?v=3", px: 64)?.absoluteString == "https://s.example/api/ai/avatar?v=3&s=64", "KI-Bild-Adresse")
    check(AvatarLogic.path("/api/ai/avatar?v=4") == "/api/ai/avatar", "fester Pfad der KI")

    // @-Erkennung (wie der Server)
    typealias L = AIChatLogic
    check(L.mentions("@Marin wann fährt der Zug?", name: "Marin"), "am Anfang")
    check(L.mentions("Hallo @marin!", name: "Marin"), "Gross-/Kleinschreibung egal, Satzzeichen danach")
    check(L.mentions("(@Marin)", name: "Marin") && L.mentions("[@Marin]", name: "Marin") && L.mentions("\"@Marin\"", name: "Marin") && L.mentions("'@Marin'", name: "Marin"), "davor ( [ \" '")
    check(L.mentions("Frage:\n@Marin", name: "Marin"), "nach Zeilenumbruch")
    check(!L.mentions("mail@Marin", name: "Marin"), "mitten im Wort nicht")
    check(!L.mentions("@Marina hilf", name: "Marin"), "danach Buchstabe")
    check(!L.mentions("@Marin2", name: "Marin") && !L.mentions("@Marin_x", name: "Marin"), "danach Ziffer/Unterstrich")
    check(!L.mentions("@Marinö", name: "Marin"), "danach Umlaut")
    check(L.mentions("@Saga KI was meinst du?", name: "Saga KI"), "Name mit Leerzeichen")
    check(L.mentions("@A.I. hi", name: "A.I.") && !L.mentions("@AxIx hi", name: "A.I."), "Sonderzeichen im Namen maskiert")
    check(!L.mentions("Marin ohne at", name: "Marin") && !L.mentions("@Marin", name: ""), "ohne @ / ohne Namen")

    // Verlauf: hoechstens 12 vor der Frage, aelteste zuerst, je 600 Zeichen
    let lines = (1...20).map { AIChatLine(id: "m\($0)", name: "P\($0 % 3)", text: "Text \($0)") }
    let h = L.history(lines, before: "m18")
    check(h.count == 12 && h.first?.id == "m6" && h.last?.id == "m17", "12 Nachrichten direkt vor der Frage")
    check(!h.contains { $0.id == "m18" || $0.id == "m19" }, "Frage und spaetere nicht")
    check(L.history(Array(lines.prefix(3)), before: "m3").map(\.id) == ["m1", "m2"], "weniger als 12")
    check(L.history([AIChatLine(id: "a", name: "A", text: String(repeating: "x", count: 900)), AIChatLine(id: "q", name: "B", text: "@Marin")], before: "q").first?.text.count == 600, "auf 600 Zeichen gekuerzt")
    check(L.history([AIChatLine(id: "a", name: "A", text: "  "), AIChatLine(id: "b", name: "B", text: "ok")], before: nil).map(\.id) == ["b"], "leere weglassen")
    let body = L.askBody(q: "@Marin hi", msgID: String(repeating: "a", count: 32), history: h)
    check((body["history"] as? [[String: String]])?.count == 12 && body["msg_id"] as? String == String(repeating: "a", count: 32), "Sende-Body")
    check(L.askBody(q: "x", msgID: "kaputt", history: [])["msg_id"] == nil, "msg_id nur 32 Hex")
    check((L.askBody(q: String(repeating: "q", count: 1500), msgID: nil, history: [])["q"] as? String)?.count == 1000, "q hoechstens 1000")

    // Hash-Pruefung empfangener KI-Antworten
    check(L.sha256Hex("") == "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", "SHA-256 leer")
    check(L.sha256Hex("abc") == "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", "SHA-256 abc")
    let ans = "Der Zug fährt um 9:12 ✓", sha = "42d456ec13b2ac02acdebeebeb2d1db215c4dae7403aaeb480a5bbbba0611d52"
    check(L.sha256Hex(ans) == sha, "SHA-256 UTF-8 wie der Server")
    let meta = AIReplyMeta(["ref": "r1", "sha256": sha, "by": "ben", "q_msg": "q"])
    check(meta?.by == "ben" && meta?.qMsg == "q", "Beleg lesen")
    check(L.check(bodyRef: "r1", text: ans, meta: meta) == .ok, "passt")
    check(L.check(bodyRef: "r1", text: "\n " + ans + "  \n", meta: meta) == .ok, "Leerraum am Rand egal")
    check(L.check(bodyRef: "r1", text: ans + ".", meta: meta) == .bad, "Text geaendert -> Warnung")
    check(L.check(bodyRef: "r2", text: ans, meta: meta) == .bad, "andere Beleg-Kennung -> Warnung")
    check(L.check(bodyRef: "r1", text: ans, meta: nil) == .bad, "ohne Server-Beleg -> Warnung")
    check(L.check(bodyRef: "r1", text: ans, meta: AIReplyMeta(ref: "r1", sha256: sha.uppercased())) == .ok, "Hex gross/klein")
    check(L.check(bodyRef: nil, text: ans, meta: meta) == .none, "normale Nachricht")
    check(AIReplyMeta(["ref": "", "sha256": "x"]) == nil, "unvollstaendiger Beleg")

    // Inhalt mit KI-Kennzeichen (verschluesselt)
    let c = ChatContent(text: ans, aiReply: "r1", aiName: "Marin")
    let back = ChatContent(json: c.json())
    check(back?.aiReply == "r1" && back?.aiName == "Marin" && back?.text == ans, "ai im Inhalt hin und zurueck")
    check(ChatContent(json: ChatContent(text: "hi").json())?.aiReply == nil, "normale Nachricht ohne ai")
    check(!String(decoding: ChatContent(text: "hi").json(), as: UTF8.self).contains("\"ai\""), "kein ai-Feld ohne Antwort")
    check(ChatContent(json: Data(#"{"v":1,"type":"text","text":"x","ai":{}}"#.utf8))?.aiReply == "", "kaputtes ai -> Warnung statt KI")
    check(L.check(bodyRef: "", text: "x", meta: nil) == .bad, "kaputtes ai -> bad")

    // @-Vorschlag
    check(L.mentionQuery("Hallo @Ma") == "Ma" && L.mentionQuery("@") == "" && L.mentionQuery("mail@x") == nil && L.mentionQuery("@Ma rin") == nil, "Teiltext nach @")
    check(L.suggests(query: "ma", name: "Marin") && L.suggests(query: "", name: "Marin") && !L.suggests(query: "x", name: "Marin") && !L.suggests(query: nil, name: "Marin"), "KI vorschlagen")
    check(L.complete("Hallo @Ma", name: "Marin") == "Hallo @Marin ", "vervollstaendigen")
    check(L.complete("", name: "Marin") == "@Marin ", "leer -> @Name")
    check(L.mentions(L.complete("Was meinst du @m", name: "Saga KI") + "?", name: "Saga KI"), "Vervollstaendigt wird erkannt")
    // Umschalten
    check(L.mayToggle(kind: "free", role: "admin", ownsPage: false) && !L.mayToggle(kind: "free", role: "member", ownsPage: false), "freie Gruppe: Admins")
    check(L.mayToggle(kind: "dm", role: "member", ownsPage: false), "Direkt-Chat: beide")
    check(L.mayToggle(kind: "page", role: "member", ownsPage: true) && !L.mayToggle(kind: "page", role: "member", ownsPage: false), "Seiten-Chat: Eigentuemer")
}
