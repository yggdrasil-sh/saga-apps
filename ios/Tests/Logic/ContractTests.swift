import Foundation

// 2.4 Server-Vertrag nach Sicherheits-/Web-Runde 2 (docs/APP-API.md): Schluessel nur fuer Chat-Partner, Unterzeile sub,
// oeffentlicher Name != Anmeldename, Nextcloud-Autor als Handle
func contractTests() {
    // /api/chat/keys: Antwort unter der angefragten Kennung (Handle bleibt Handle), null/fehlend = keine Schluessel
    let keys: [String: Any] = ["users": ["anna": ["ident_pub": "QUJD"], "~abcdefghijklmnop": ["ident_pub": "REVG"], "otto": NSNull()]]
    check(PersonRef.keyEntry(keys, for: "anna")?["ident_pub"] as? String == "QUJD", "keys: Benutzername wie bisher")
    check(PersonRef.keyEntry(keys, for: "~abcdefghijklmnop")?["ident_pub"] as? String == "REVG", "keys: Antwort unter dem angefragten Handle")
    check(PersonRef.keyEntry(keys, for: "otto") == nil, "keys: null = noch keine Chats eingerichtet")
    check(PersonRef.keyEntry(keys, for: "fremd") == nil, "keys: fremdes Konto fehlt (vor dem ersten Chat keine Schluessel)")
    check(PersonRef.keyEntry(["users": [:]], for: "anna") == nil && PersonRef.keyEntry(nil, for: "anna") == nil, "keys: leere Antwort")

    // Ablauf "neuen Chat starten"/"Gruppe anlegen": erst POST /api/chats, dann Empfaenger des Chats, dann versiegeln -
    // nie Schluessel vor dem Anlegen (Server 2.4 liefert sie fuer Fremde nicht mehr)
    if let dir = ProcessInfo.processInfo.environment["SAGA_APP_SRC"],
       let views = try? String(contentsOfFile: dir + "/ChatViews.swift", encoding: .utf8),
       let conv = try? String(contentsOfFile: dir + "/ChatConversation.swift", encoding: .utf8) {
        check(!views.contains("api/chat/keys"), "Neue Unterhaltung holt keine Schluessel vor dem Anlegen")
        if let f = views.range(of: "private func createFree()"), let post = views.range(of: "\"api/chats\"", range: f.upperBound..<views.endIndex),
           let load = views.range(of: "conv.load()", range: f.upperBound..<views.endIndex),
           let send = views.range(of: "conv.send(", range: f.upperBound..<views.endIndex) {
            check(post.lowerBound < load.lowerBound && load.lowerBound < send.lowerBound, "Gruppe anlegen: Chat anlegen -> Empfaenger laden -> Namen versiegeln")
        } else { check(false, "createFree nicht gefunden") }
        check(conv.contains("api/chats/\\(chatID)/recipients"), "Versiegeln mit den Empfaengern des angelegten Chats")
    } else { check(false, "SAGA_APP_SRC fehlt") }

    // sub: Unterzeile fuer Gleichnamige (nie der Anmeldename), aeltere Server ohne Feld
    check(PersonRef.sub(" ~abcdefghijklmnop ") == "~abcdefghijklmnop" && PersonRef.sub("") == nil && PersonRef.sub(nil) == nil, "sub gelesen")
    check(PersonRef.subline("anna-b", "hat Chats noch nicht eingerichtet") == "anna-b · hat Chats noch nicht eingerichtet", "Unterzeile: sub · Hinweis")
    check(PersonRef.subline(nil, nil) == nil && PersonRef.subline("", "x") == "x", "Unterzeile ohne leere Teile")
    let t = ShareLogic.parseTargets(Data(#"{"users":[{"username":"~abcdefghijklmnop","name":"Anna","sub":"anna-b"},{"username":"~bbbbbbbbbbbbbbbb","name":"Anna"}]}"#.utf8))
    check(t.count == 2 && t[0].sub == "anna-b" && t[1].sub == nil, "Teilen-Treffer mit sub")
    if let dir = ProcessInfo.processInfo.environment["SAGA_APP_SRC"], let m = try? String(contentsOfFile: dir + "/ChatModel.swift", encoding: .utf8) {
        check(m.contains("sub: PersonRef.sub(m[\"sub\"])"), "members[].sub wird gelesen (ChatMember)")
    }

    // oeffentlicher Name: Hinweis bei is_login, solange genau dieser Name im Feld steht
    check(PublicNameLogic.loginHint(info: ["name": "anna", "is_login": true], input: " Anna "), "is_login: Hinweis")
    check(!PublicNameLogic.loginHint(info: ["name": "anna", "is_login": true], input: "anna-b"), "is_login: anderer Name getippt -> kein Hinweis")
    check(!PublicNameLogic.loginHint(info: ["name": "anna-b", "is_login": false], input: "anna-b"), "kein is_login -> kein Hinweis")
    check(!PublicNameLogic.loginHint(info: ["name": "anna"], input: "anna"), "aelterer Server ohne Feld -> kein Hinweis")

    // Nextcloud: fremde Bloecke tragen den Handle - Abgleich Block <-> Status ueber denselben Wert, ?a= nimmt ihn
    let st = NCStatus(data: Data(#"{"enabled": true, "items": [{"fileid": "7", "author": "~aaaaaaaaaaaaaaaa", "ok": true, "name": "a.pdf"}]}"#.utf8))
    check(st?.item(for: NCBlock(["fileid": "7", "author": "~aaaaaaaaaaaaaaaa"])!, remotePage: false)?.ok == true, "Nextcloud: fremder Block per Handle gefunden")
}
