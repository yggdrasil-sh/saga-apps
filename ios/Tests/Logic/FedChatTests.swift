import Foundation

// Chats ueber Servergrenzen (2.3): FedChatLogic - TOFU v2 gegen tests/vectors/chat_v2.json ("tofu", Kopie in ios/Tests/vectors),
// Handles, Adressbuch-Treffer, Zusatzfelder foederierter Chats, Systemzeilen, Zustellstatus, Senden-Pruefung.
// Die Krypto-Vektoren (Zertifikate, Nachrichten, Dateien, Sicherheitsnummer, reject) prueft ios/Tests/ChatVectors (libsodium).
func fedChatTests() {
    // TOFU-Regeln maschinenlesbar aus den gemeinsamen Vektoren
    let path = URL(fileURLWithPath: #filePath).deletingLastPathComponent().appendingPathComponent("../vectors/chat_v2.json").standardized.path
    let v = FileManager.default.contents(atPath: path).flatMap { try? JSONSerialization.jsonObject(with: $0) as? [String: Any] } ?? [:]
    let rules = (v["tofu"] as? [String: Any])?["rules"] as? [[String: Any]] ?? []
    check(rules.count >= 9, "tofu: Regeln aus \(path) gelesen (\(rules.count))")
    func trust(_ x: Any?) -> FedTrust? {
        guard let t = x as? [String: Any], let id = t["ident"] as? String else { return nil }
        return FedTrust(ident: id, verified: t["verified"] as? Bool ?? false, changed: t["changed"] as? Bool ?? false, old: t["old"] as? String,
                        dver: (t["dver"] as? NSNumber)?.intValue ?? 0)
    }
    for r in rules where !(r["seen"] as? String ?? "").hasPrefix("invalid") {
        let t = FedChatLogic.tofu(trust(r["stored"]), ident: r["seen"] as? String ?? "", ver: (r["ver"] as? NSNumber)?.intValue ?? 0)
        check(t.result.rawValue == r["result"] as? String, "tofu \(r["step"] ?? "?"): \(t.result) statt \(r["result"] ?? "?")")
        if r["store"] != nil { check(t.store == trust(r["store"]), "tofu \(r["step"] ?? "?"): gespeichert \(String(describing: t.store))") }
    }
    // "Verstanden"/"ueberprueft": Warnung weg, Stand der Geraete-Liste bleibt nur beim gleichen Schluessel
    let warned = FedTrust(ident: "B", verified: false, changed: true, old: "A", dver: 3)
    check(FedChatLogic.confirm(warned, ident: "B", verified: false) == FedTrust(ident: "B", verified: false, dver: 3), "confirm: Verstanden")
    check(FedChatLogic.confirm(warned, ident: "C", verified: true) == FedTrust(ident: "C", verified: true, dver: 0), "confirm: anderer Schluessel")
    check(FedChatLogic.tofu(FedChatLogic.confirm(warned, ident: "B", verified: false), ident: "B", ver: 3).result == .trust, "nach Verstanden: trust")
    check(FedChatLogic.isVerified(FedTrust(ident: "B", verified: true), ident: "B", verdict: .trust)
          && !FedChatLogic.isVerified(FedTrust(ident: "B", verified: true), ident: "C", verdict: .block)
          && !FedChatLogic.isVerified(FedTrust(ident: "B", verified: false), ident: "B", verdict: .trust), "isVerified")
    check(FedChatLogic.blocksSending(.warn) && FedChatLogic.blocksSending(.block) && FedChatLogic.blocksSending(.reject)
          && !FedChatLogic.blocksSending(.trust) && !FedChatLogic.blocksSending(.missing) && !FedChatLogic.blocksSending(nil), "blocksSending")

    // Handles
    check(FedChatLogic.isHandle("~k3m5q7r2tz4wxa6b@saga.verein.example") && FedChatLogic.isHandle("~qrstuvwxyz234567@b.example:8443"), "isHandle gueltig")
    check(!FedChatLogic.isHandle("~k3m5q7r2tz4wxa6@x.example") && !FedChatLogic.isHandle("anna@b.example") && !FedChatLogic.isHandle("~K3M5Q7R2TZ4WXA6B@b.example")
          && !FedChatLogic.isHandle("~k3m5q7r2tz4wxa1b@b.example"), "isHandle ungueltig")
    check(FedChatLogic.host(of: "~qrstuvwxyz234567@b.example:8443") == "b.example:8443" && FedChatLogic.host(of: "00ff@a.example") == "a.example"
          && FedChatLogic.host(of: "kein-at") == "", "host(of:)")

    // Adressbuch-Treffer
    let people = FedChatLogic.people([
        ["type": "remote", "name": "Anna Berg", "host": "b.example", "label": "Anna Berg · b.example", "handle": "~abcdefghijklmnop@b.example",
         "target": "@annab@b.example", "pub": "annab@b.example", "avatar": "/api/avatar/x"],
        ["type": "remote", "name": "Anna Berg", "host": "b.example", "handle": "~qrstuvwxyz234567@b.example", "target": "@~qrstuvwxyz234567@b.example", "pub": NSNull()],
        ["type": "remote_group", "name": "x", "host": "b.example", "target": "@x@b.example"],
        ["type": "remote", "name": "alt", "host": "c.example", "target": "@alt@c.example"],          // Server 2.2: ohne Handle
    ])
    check(people.count == 3 && people[0].label == "Anna Berg · b.example" && people[0].avatar == "/api/avatar/x" && people[0].pub == "annab@b.example",
          "people: Treffer + label")
    check(people[1].label == "Anna Berg · b.example" && people[1].pub == nil && people[1].target == "@~qrstuvwxyz234567@b.example", "people: ohne label -> Name · host")
    check(people[2].handle == nil && people[2].id == "@alt@c.example", "people: ohne Handle (2.2)")
    check(FedChatLogic.searchReady("h") && !FedChatLogic.searchReady(" ") && ShareLogic.searchReady("hd") && !ShareLogic.minHint("h"), "Suche ab 1 Zeichen")
    let st = ShareLogic.parseTargets(try! JSONSerialization.data(withJSONObject: ["users": [], "groups": [], "remote": [
        ["type": "remote", "target": "@~qrstuvwxyz234567@b.example", "name": "Bert", "host": "b.example", "label": "Bert · b.example · ~qrstuv…"],
        ["type": "remote", "target": "@bert@c.example", "name": "Bert", "host": "c.example"]]]))
    check(st.count == 2 && st[0].remoteLine == "Bert · b.example · ~qrstuv…" && st[1].remoteLine == "Bert · c.example", "share targets: label/host")

    // Zusatzfelder eines foederierten Chats
    let chatJ: [String: Any] = ["id": 7, "kind": "dm", "v": 2, "ref": "00112233445566778899aabbccddeeff@a.example", "fed": true, "home": "a.example",
        "home_self": true, "my_handle": "~abcdefghijklmnop@a.example", "state": "request", "request": true, "readonly": false, "readonly_reason": NSNull(),
        "members": [["username": "anna", "name": "Anna", "me": true, "handle": "~abcdefghijklmnop@a.example", "host": "a.example"],
                    ["username": "@~qrstuvwxyz234567@b.example", "name": "Bert", "me": false, "handle": "~qrstuvwxyz234567@b.example", "host": "b.example",
                     "remote": true, "label": "Bert · b.example"]],
        "peer": ["username": "@~qrstuvwxyz234567@b.example", "handle": "~qrstuvwxyz234567@b.example", "name": "Bert", "label": "Bert · b.example", "host": "b.example"]]
    let fi = FedChatInfo(chatJ)
    check(fi?.ref == "00112233445566778899aabbccddeeff@a.example" && fi?.homeSelf == true && fi?.request == true && fi?.callable == false
          && fi?.peer?.host == "b.example" && fi?.members.count == 2 && fi?.handles.count == 2 && fi?.members[1].remote == true, "FedChatInfo")
    check(FedChatInfo(["id": 1, "kind": "dm", "members": []]) == nil && FedChatInfo(["v": 1, "ref": "x"]) == nil, "FedChatInfo: v1-Chat -> nil")
    var ro = chatJ; ro["request"] = false; ro["state"] = "ok"; ro["readonly"] = true; ro["readonly_reason"] = "Verbindung getrennt"
    check(FedChatInfo(ro).map { !$0.request && $0.readonly && $0.readonlyReason == "Verbindung getrennt" && !$0.callable } == true, "FedChatInfo: schreibgeschuetzt")
    var ok2 = chatJ; ok2["request"] = false; ok2["state"] = "ok"
    check(FedChatInfo(ok2)?.callable == true, "FedChatInfo: angenommen -> Anrufe")

    // Senden pruefen
    let mem = fi?.members ?? []
    check(FedChatLogic.sendCheck(members: mem, verdicts: ["~abcdefghijklmnop@a.example": .trust, "~qrstuvwxyz234567@b.example": .trust], myHandle: "~abcdefghijklmnop@a.example") == .ok, "sendCheck ok")
    check(FedChatLogic.sendCheck(members: mem, verdicts: ["~abcdefghijklmnop@a.example": .trust, "~qrstuvwxyz234567@b.example": .warn], myHandle: "~abcdefghijklmnop@a.example") == .blocked(["Bert"]), "sendCheck Warnung")
    check(FedChatLogic.sendCheck(members: mem, verdicts: ["~abcdefghijklmnop@a.example": .reject, "~qrstuvwxyz234567@b.example": .missing], myHandle: "~abcdefghijklmnop@a.example") == .ownKeys, "sendCheck eigene Schluessel")
    check(FedChatLogic.needsFreshKeys(code: 409, body: ["handles": []]) && !FedChatLogic.needsFreshKeys(code: 409, body: ["error": "x"]) && !FedChatLogic.needsFreshKeys(code: 200, body: nil), "409 handles")

    // Systemzeilen, Zustellstatus, KI-Hash
    check(FedChatLogic.sysLine("{\"type\":\"ai\",\"on\":true,\"by\":\"~h@a\",\"name\":\"Anna\"}", myHandle: "~h@a") == .ai(on: true, byMe: true, name: "Anna")
          && FedChatLogic.sysLine("{\"type\":\"ai\",\"on\":false,\"by\":\"~x@b\",\"name\":\"Bert\"}", myHandle: "~h@a") == .ai(on: false, byMe: false, name: "Bert")
          && FedChatLogic.sysLine("{\"type\":\"neu\"}", myHandle: nil) == .other && FedChatLogic.sysLine("kaputt", myHandle: nil) == .other, "sysLine")
    check(FedChatLogic.delivery("pending") == .pending && FedChatLogic.delivery("failed") == .failed && FedChatLogic.delivery(NSNull()) == .delivered
          && FedChatLogic.liveDelivery(["failed": true]) == .failed && FedChatLogic.liveDelivery(["id": "x"]) == .delivered, "delivery")
    let h = AIChatLogic.sha256Hex("Hallo")
    check(FedChatLogic.aiMeta(h, bodyRef: "r1") == AIReplyMeta(ref: "r1", sha256: h) && FedChatLogic.aiMeta(nil, bodyRef: "r1") == nil
          && FedChatLogic.aiMeta(["ref": "r", "sha256": "s"], bodyRef: nil) == AIReplyMeta(ref: "r", sha256: "s"), "aiMeta v2/v1")
    check(AIChatLogic.check(bodyRef: "r1", text: "Hallo", meta: FedChatLogic.aiMeta(h, bodyRef: "r1")) == .ok
          && AIChatLogic.check(bodyRef: "r1", text: "Hallo!", meta: FedChatLogic.aiMeta(h, bodyRef: "r1")) == .bad, "KI-Hash v2 gegen Text")
}
