import Foundation

// Chat-Mehrfachauswahl (2.5, App/ChatSelectLogic.swift): Auswahl-Regeln, Bereich, Kopiertext, Loeschen fuer alle,
// Rueckfall bei aelteren Servern, Weiterleiten, Seite aus Nachrichten, Hinweis x3_why
func chatSelectTests() {
    typealias L = ChatSelectLogic
    let t0 = Date(timeIntervalSince1970: 1_700_000_000)
    let time: (Date) -> String = { d in "T\(Int(d.timeIntervalSince(t0)))" }
    let a = L.Item(id: "a", seq: 1, mine: false, name: "Alma", date: t0, text: "Hallo")
    let b = L.Item(id: "b", seq: 2, mine: true, name: "Bo", date: t0.addingTimeInterval(60), text: "Bild anbei", atts: [.image("x.png")])
    let c = L.Item(id: "c", seq: 3, mine: true, name: "Bo", date: t0.addingTimeInterval(120), text: "", atts: [.file("plan.pdf"), .voice])

    // auswaehlbar
    check(L.selectable(call: false, sys: false, decrypted: true), "normale Nachricht auswaehlbar")
    check(!L.selectable(call: true, sys: false, decrypted: true), "Anruf-Eintrag nicht")
    check(!L.selectable(call: false, sys: true, decrypted: true), "Systemzeile nicht")
    check(!L.selectable(call: false, sys: false, decrypted: false), "nicht entschluesselt nicht")

    // an-/abwaehlen, Bereich
    var s: Set<String> = []
    check(L.toggle(&s, "a") && s == ["a"], "anwaehlen")
    check(!L.toggle(&s, "a") && s.isEmpty, "abwaehlen -> leer")
    let order = ["a", "sys", "b", "c", "d"]
    let sel: Set<String> = ["a", "b", "c", "d"]
    check(L.range(order, selectable: sel, from: "a", to: "c") == ["a", "b", "c"], "Bereich ohne Systemzeile")
    check(L.range(order, selectable: sel, from: "d", to: "b") == ["b", "c", "d"], "Bereich rueckwaerts")
    check(L.range(order, selectable: sel, from: nil, to: "b") == ["b"], "ohne Anker nur das Ziel")
    check(L.range(order, selectable: sel, from: "a", to: "sys") == ["a"], "Ziel nicht auswaehlbar")

    // Kopieren
    check(L.copyText([a], time: time) == "Hallo", "eine Nachricht: nur der Text")
    check(L.copyText([b], time: time) == "Bild anbei [Bild]", "eine Nachricht mit Bild")
    check(L.copyText([c, a, b], time: time) == "Alma, T0: Hallo\nBo, T60: Bild anbei [Bild]\nBo, T120: [Datei: plan.pdf] [Sprachnachricht]",
          "mehrere: Reihenfolge, Name, Zeit, Anhaenge")
    var lab = L.Labels(); lab.image = "[Image]"; lab.file = { "[File: \($0)]" }
    check(L.copyText([b, c], time: time, labels: lab).contains("[Image]") && L.copyText([c], time: time, labels: lab) == "[File: plan.pdf] [Sprachnachricht]", "Texte uebersetzbar")
    check(L.copyText([L.Item(id: "m", seq: 1, mine: true, name: "X", date: t0, text: "  Zeile 1\nZeile 2 \n")], time: time) == "Zeile 1\nZeile 2", "Leerraum am Rand weg")

    // Loeschen fuer alle
    check(L.canDeleteForAll([b, c]), "nur eigene -> fuer alle")
    check(!L.canDeleteForAll([a, b]), "fremde dabei -> nicht fuer alle")
    check(!L.canDeleteForAll([b], allowed: false), "nicht erlaubt (schreibgeschuetzt)")
    check(!L.canDeleteForAll([]), "leer")
    check(L.chunks((1...250).map(String.init), 100).map(\.count) == [100, 100, 50], "Teile zu 100")
    check(L.chunks([], 100).isEmpty, "keine Teile")

    // Rueckfall
    check(L.deleteResult(code: 404, body: ["detail": "Not Found"], asked: ["b"]) == .fallback, "404 -> Einzel-DELETE")
    check(L.deleteResult(code: 405, body: nil, asked: ["b"]) == .fallback, "405 -> Einzel-DELETE")
    check(L.deleteResult(code: 200, body: ["deleted": ["b", "zz"], "failed": ["c": "not yours"]], asked: ["b", "c"]) == .ok(done: ["b"], failed: ["c": "not yours"]),
          "Antwort deleted/failed (nur gefragte)")
    check(L.deleteResult(code: 403, body: ["error": "verboten"], asked: ["b"]) == .error("verboten"), "Fehler weitergeben")
    check(L.hideResult(code: 200, body: ["hidden": 2]) == .ok(done: [], failed: [:]), "hide ok")
    check(L.hideResult(code: 404, body: nil) == .fallback && L.hideResult(code: 405, body: nil) == .fallback, "hide 404/405 -> nur lokal")
    check(L.hideResult(code: 500, body: nil) == .error("HTTP 500"), "hide Serverfehler")
    check(L.hiddenIDs(["chat_id": 7, "ids": ["a", "b", 3]], chatID: 7) == ["a", "b"], "chat.hidden")
    check(L.hiddenIDs(["chat_id": 8, "ids": ["a"]], chatID: 7) == nil, "chat.hidden anderer Chat")

    // Weiterleiten
    check(L.forward(a) == L.Forward(text: "Hallo", upload: [], skipped: 0), "nur Text")
    check(L.forward(b) == L.Forward(text: "Bild anbei", upload: [0], skipped: 0), "Bild neu hochladen")
    check(L.forward(c) == L.Forward(text: "[Anhang nicht weitergeleitet]", upload: [0], skipped: 1), "Sprachnachricht als Hinweis")
    check(L.forward(L.Item(id: "v", seq: 1, mine: true, name: "X", date: t0, text: "Film", atts: [.video("f.mp4")])).text == "Film\n[Anhang nicht weitergeleitet]", "Video als Hinweis")
    check(L.forwardAllowed(50) && !L.forwardAllowed(51) && !L.forwardAllowed(0), "hoechstens 50")

    // Seite
    check(L.pageTitle(chat: "Team", date: "25.10.26") == "Chat Team – 25.10.26", "Titel")
    var n = 0
    let blocks = L.pageBlocks([b, a], time: time, newID: { n += 1; return "id\(n)" })
    check(blocks.count == 2 && (blocks[0]["data"] as? [String: Any])?["text"] as? String == "<b>Alma</b> · T0<br>Hallo", "Absatz Name · Zeit + Text, Reihenfolge")
    check((blocks[1]["data"] as? [String: Any])?["text"] as? String == "<b>Bo</b> · T60<br>Bild anbei [Bild]", "Bild als Hinweis")
    check(L.html("<a href=\"x\">&\n") == "&lt;a href=&quot;x&quot;&gt;&amp;<br>", "HTML maskiert")
    let neu = L.appended(body: "{\"blocks\":[{\"id\":\"p\",\"type\":\"paragraph\",\"data\":{\"text\":\"\"}}]}", format: "blocks", blocks: blocks)
    let neuBlocks = neu.flatMap { $0.data(using: .utf8) }.flatMap { try? JSONSerialization.jsonObject(with: $0) as? [String: Any] }?["blocks"] as? [Any]
    check(neuBlocks?.count == 2, "leerer Platzhalter ersetzt")
    let alt = L.appended(body: "{\"blocks\":[{\"id\":\"p\",\"type\":\"header\",\"data\":{\"text\":\"Notizen\"}}]}", format: "blocks", blocks: blocks)
    let altBlocks = alt.flatMap { $0.data(using: .utf8) }.flatMap { try? JSONSerialization.jsonObject(with: $0) as? [String: Any] }?["blocks"] as? [Any]
    check(altBlocks?.count == 3, "angehaengt, Bestehendes bleibt")
    check(L.appended(body: "alter Text", format: nil, blocks: blocks) == nil, "alte Textnotiz: nicht anfassen")
    check(L.appended(body: "{kaputt", format: "blocks", blocks: blocks) == nil, "unlesbarer Inhalt: nicht speichern")
    check(L.appended(body: "", format: nil, blocks: blocks) != nil, "leere Seite ok")

    // x3_why
    check(L.why(["x3_ok": true, "x3_why": "devices"]) == nil, "x3_ok -> kein Hinweis")
    check(L.why(["id": 1]) == nil, "aelterer Server: kein Feld, kein Hinweis")
    check(L.why(["x3_why": NSNull()]) == nil, "null = geht")
    check(L.why(["x3_why": "setting"]) == .setting, "setting")
    check(L.why(["x3_why": "devices", "x3_wait": ["mine": ["Linux", "Safari · iOS"], "others": 2, "until": 1_800_000_000]]) == .devices(mine: ["Linux", "Safari · iOS"], others: 2, until: 1_800_000_000), "devices")
    check(L.why(["x3_why": "devices"]) == .devices(mine: [], others: 0, until: nil), "devices ohne x3_wait")
    check(L.why(["x3_why": "server", "x3_hosts": ["b.example"]]) == .server(hosts: ["b.example"]), "server")
    check(L.why(["x3_why": "readonly"]) == .readonly && L.why(["x3_why": "pending"]) == .pending && L.why(["x3_why": "neu"]) == .other, "readonly/pending/unbekannt")
    check(L.offerDevices(.devices(mine: ["Linux"], others: 0, until: nil)) && !L.offerDevices(.devices(mine: [], others: 3, until: nil)) && !L.offerDevices(.setting),
          "Geraete verwalten nur bei eigenen Geraeten")
}
