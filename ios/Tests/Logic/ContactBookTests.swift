import Foundation

// 2.6 (Bugs-DB 2027a-5): Adressbuch (App/ContactBookLogic.swift). Fixtures FixturesCS/k2_*.json: echte Antworten eines
// Testservers (Wegwerf-Konten, Beispielnummern/-adressen).

private func k2(_ name: String) -> Any? {
    let dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent().appendingPathComponent("FixturesCS")
    guard let d = try? Data(contentsOf: dir.appendingPathComponent(name + ".json")) else { check(false, "Fixture \(name) fehlt"); return nil }
    return try? JSONSerialization.jsonObject(with: d)
}
private func k2Data(_ name: String) -> Data {
    let dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent().appendingPathComponent("FixturesCS")
    return (try? Data(contentsOf: dir.appendingPathComponent(name + ".json"))) ?? Data()
}

func contactBookTests() {
    typealias L = ContactBookLogic
    func j(_ s: String) -> Any? { try? JSONSerialization.jsonObject(with: Data(s.utf8)) }

    // Feature
    let feats = ((k2("k2_version") as? [String: Any])?["features"] as? [Any] ?? []).compactMap { $0 as? String }
    check(feats.contains(L.feature) && feats.contains(ContactsLogic.feature), "Server meldet contacts2 (und contacts1)")
    check(!ServerFeatures().has(L.feature), "ohne Server-Feature: nichts")

    // Liste: Reihenfolge wie vom Server, feste zuerst, manuelle ohne saga
    let list = L.list(k2("k2_list"))
    check(list.contacts.count == 6 && list.total == 6, "Liste: 6 Kontakte (\(list.contacts.count))")
    check(list.contacts.map(\.name).prefix(2) == ["Klara Neumann", "Praxis Dr. Weber"], "zuletzt geaendert zuerst (wie Server)")
    let klara = list.contacts[0]
    check(klara.manual && klara.saga == nil && klara.ownName == "Klara Neumann" && klara.form == "informal", "manueller Kontakt")
    check(L.sub(klara) == .addr("+49 15112345678"), "Unterzeile: erste Nummer lesbar \(L.sub(klara))")
    check(!L.canMessage(klara), "ohne Saga-Adresse keine Nachricht")

    // Detail aus einem Chat
    guard let bert = L.contact(k2("k2_contact_chat")) else { check(false, "Kontakt lesen"); return }
    check(!bert.manual && bert.name == "Bert Berg" && bert.ownName == nil && bert.ver == 2, "Chat-Kontakt: Name der Person, ver")
    check(bert.addrs.first?.kind == "saga" && bert.addrs.first?.fixed == true && bert.fixedAddrs.count == 1, "feste Saga-Adresse zuerst")
    check(bert.phones.map(\.value) == ["+491701234567"] && bert.phones[0].type == "mobile" && bert.mails.map(\.value) == ["bert@example.org"], "Nummer und Mail mit Typ")
    check(bert.saga?.ref.hasPrefix("~") == true && bert.saga?.remote == false && L.canMessage(bert), "Saga-Ref, Nachricht moeglich")
    check(bert.salutation == "Lieber Bert" && bert.form == "informal", "Anrede/Grussform")
    if case .local = L.sub(bert) {} else { check(false, "Unterzeile lokal \(L.sub(bert))") }

    // Robust: unbekannte Felder/Arten, kaputte Werte
    let odd = L.contact(j(#"{"id":5,"ver":1,"origin":"x","name":"","addrs":[{"kind":"fax","value":"1"},{"kind":"e164","value":"+4930123456","type":"pager"},{"kind":"email","value":""}],"form":"herzlich","neu":1}"#))
    check(odd?.origin == "chat" && odd?.addrs.count == 1 && odd?.addrs.first?.type == nil && odd?.form == nil && odd?.name == "+4930123456", "unbekannte Arten/Typen verworfen, Name aus Nummer")
    check(L.contact(j(#"{"id":"5","ver":1}"#)) == nil && L.contact(j(#"{"id":5}"#)) == nil && L.contact(j(#"{"id":5,"ver":true}"#)) == nil, "ohne gueltige id/ver: nil")
    let fed = L.contact(j(#"{"id":7,"ver":1,"name":"Fern","saga":{"ref":"~abcdefghijklmnop@b.example","remote":true,"gone":true},"addrs":[{"id":1,"kind":"fed","value":"~abcdefghijklmnop@b.example"}]}"#))
    check(fed?.saga?.host == "b.example" && fed?.addrs.first?.fixed == true && L.sub(fed!) == .gone(remote: true, host: "b.example") && !L.canMessage(fed!), "fremde Person: Host aus ref, gone")
    check(L.dmBody(fed!.saga!, device: "d1")["handle"] as? String == "~abcdefghijklmnop@b.example" && L.dmBody(bert.saga!, device: "d1")["user"] as? String == bert.saga!.ref, "Direkt-Chat: handle bzw. user")

    // Anzeige / Links
    check(L.phoneShown("+491701234567") == "+49 1701234567" && L.phoneShown("+12025550123") == "+1 2025550123" && L.phoneShown("+420601123456") == "+420 601123456", "Landesvorwahl abgesetzt")
    check(L.phoneShown("+999123") == "+999123" && L.phoneShown("0170") == "0170", "unbekannt: unveraendert")
    check(L.telURL("+491701234567")?.absoluteString == "tel:+491701234567" && L.telURL("0170 1") == nil && L.telURL("+49;x") == nil, "tel: nur fuer E.164")
    check(L.mailURL("anna@example.org")?.absoluteString == "mailto:anna@example.org" && L.mailURL("a@b@c") == nil && L.mailURL("a b@c") == nil, "mailto:")
    check(L.mailURL("x?subject=1@example.org").map { !$0.absoluteString.contains("?") } == true, "mailto: keine Parameter einschleusen")
    check(L.mailURL("a,b;c@example.org")?.absoluteString == "mailto:a%2Cb%3Bc@example.org", "mailto: keine weiteren Empfaenger einschleusen")

    // Pfade
    check(L.listPath(q: "") == "api/contacts?limit=200&offset=0" && L.listPath(q: " 0170 123 ") == "api/contacts?limit=200&offset=0&q=0170%20123", "Listenpfad mit Suche")
    check(L.listPath(q: "a&b=c").hasSuffix("q=a%26b%3Dc"), "Suche kodiert")
    check(L.path(17) == "api/contacts/17", "Detailpfad")

    // Entwurf aus Kontakt -> PATCH nur Geaendertes
    var d = L.Draft(bert)
    check(d.ownName == "" && d.form == "informal" && d.salutation == "Lieber Bert" && d.phones.map(\.value) == ["+49 1701234567"], "Entwurf aus Kontakt")
    check(!L.dirty(d, base: bert) && L.patchBody(d, base: bert, cc: "49").count == 1, "unveraendert: nur ver")
    d.ownName = "  Bertie  "
    var p = L.patchBody(d, base: bert, cc: nil)
    check(p["ver"] as? Int == 2 && p["own_name"] as? String == "Bertie" && p["addrs"] == nil && p["form"] == nil && p["cc"] == nil, "nur own_name geaendert")
    d.form = nil; d.salutation = ""
    p = L.patchBody(d, base: bert, cc: nil)
    check(p["form"] is NSNull && p["salutation"] is NSNull, "leeren = null")
    d.phones.append(.init(kind: "e164", value: "0170 7654321", type: "work"))
    d.mails = []
    p = L.patchBody(d, base: bert, cc: "49")
    let pa = p["addrs"] as? [[String: Any]] ?? []
    check(pa.count == 2 && pa.allSatisfy { ($0["kind"] as? String) == "e164" } && pa[1]["type"] as? String == "work" && p["cc"] as? String == "49", "addrs ersetzt private, cc nur mit addrs")
    check(!pa.contains { ($0["kind"] as? String) == "saga" }, "feste Adresse wird nicht gesendet")
    check(L.hasNational(d) && !L.hasNational(L.Draft(bert)), "nationale Nummer erkannt")
    var d2 = L.Draft(bert); d2.phones[0].type = "home"
    check(L.patchBody(d2, base: bert, cc: nil)["addrs"] != nil, "Typ geaendert -> addrs")
    var d3 = L.Draft(bert); d3.phones.append(.init(kind: "e164", value: "  ", type: "mobile")); d3.mails.append(.init(kind: "email", value: "bert@example.org", type: "home"))
    check(d3.addrs.count == 2, "leere Zeilen und doppelte Werte zaehlen nicht")
    var d4 = L.Draft(bert); d4.phones = []; d4.mails = []
    check(L.canSave(d4, manual: false) && !L.canSave(d4, manual: true), "Chat-Kontakt ohne Nummern sicherbar, manueller nicht")

    // Neu
    var n = L.Draft.new()
    check(n.phones.count == 1 && !L.canSave(n, manual: true) && !L.dirty(n, base: nil), "neuer Entwurf: leer, nicht sicherbar")
    n.ownName = "Praxis"; check(!L.canSave(n, manual: true) && L.dirty(n, base: nil), "Name allein reicht nicht")
    n.phones[0].value = "+49 30 1234567"
    check(L.canSave(n, manual: true), "Name + Nummer")
    let cb = L.createBody(n, cc: nil)
    check(cb["own_name"] as? String == "Praxis" && (cb["addrs"] as? [[String: Any]])?.first?["type"] as? String == "mobile" && cb["form"] == nil && cb["salutation"] == nil && cb["cc"] == nil, "POST-Koerper")
    n.form = "formal"; n.salutation = String(repeating: "x", count: 90)
    let cb2 = L.createBody(n, cc: "43")
    check(cb2["form"] as? String == "formal" && (cb2["salutation"] as? String)?.count == 80 && cb2["cc"] as? String == "43", "Anrede, Grussform <= 80, cc")
    n.ownName = String(repeating: "N", count: 130) + "\u{7}"
    check(n.trimmedName.count == 120, "Name <= 120")
    check(L.canAdd(Array(repeating: L.DraftAddr(kind: "e164", value: "1"), count: 9)) && !L.canAdd(Array(repeating: L.DraftAddr(kind: "e164", value: "1"), count: 10)), "hoechstens 10 je Art")

    // Antworten (echte Fixtures)
    if case .ok(let c) = L.saveResult(status: 200, data: k2Data("k2_contact_chat")) { check(c.id == bert.id, "200 -> Kontakt") } else { check(false, "200 ok") }
    if case .conflict(let c, let e) = L.saveResult(status: 409, data: k2Data("k2_patch_409")) {
        check(c.ver == 2 && c.name == "Bert Berg" && (e ?? "").contains("geändert"), "409 mit contact -> neuer Stand")
    } else { check(false, "409 conflict") }
    if case .duplicate(let id, let e) = L.saveResult(status: 409, data: k2Data("k2_post_dup_409")) {
        check(id == bert.id && e != nil && !(e ?? "").contains("170"), "409 doppelt -> contact_id, ohne Wert")
    } else { check(false, "409 duplicate") }
    check(L.saveResult(status: 400, data: k2Data("k2_post_400")) == .failed("Für nationale Nummern bitte die Landesvorwahl einstellen oder die Nummer mit + beginnen."), "400 mit Server-Text")
    check(L.saveResult(status: 500, data: Data()) == .failed(nil), "500 ohne Text")

    // Landesvorwahl
    check(L.cc(k2("k2_settings")) == nil && L.cc(j(#"{"cc":"49"}"#)) == "49", "settings {cc}")
    check(L.normCC("+49") == "49" && L.normCC(49) == "49" && L.normCC(" 43 ") == "43" && L.normCC("1") == "1" && L.normCC("420") == "420", "cc normalisiert")
    check(L.normCC("049") == nil && L.normCC("4911") == nil && L.normCC("") == nil && L.normCC(true) == nil && L.normCC("a1") == nil && L.normCC(NSNull()) == nil, "ungueltige cc")
    check(L.suggestedCC(region: "DE") == "49" && L.suggestedCC(region: "at") == "43" && L.suggestedCC(region: "US") == "1" && L.suggestedCC(region: "CA") == "1"
          && L.suggestedCC(region: "TH") == "66" && L.suggestedCC(region: "RU") == "7" && L.suggestedCC(region: "ES") == "34", "Vorschlag aus der Region")
    check(L.suggestedCC(region: nil) == nil && L.suggestedCC(region: "ZZ") == nil && L.suggestedCC(region: "001") == nil, "unbekannte Region: kein Vorschlag")
    check(L.regionCodes.count > 240 && L.callingCodes.contains("49") && !L.callingCodes.contains("0"), "Tabelle vollstaendig")
    check(L.requestCC(setting: "43", suggestion: "49") == nil && L.requestCC(setting: nil, suggestion: "49") == "49", "Konto-Einstellung geht vor (nichts mitsenden)")
    check(L.effectiveCC(setting: "43", suggestion: "49") == "43" && L.effectiveCC(setting: nil, suggestion: nil) == nil, "Hinweis im Formular")

    // Live
    check(L.changed(["id": 4])! == (4, false) && L.changed(["id": 4, "deleted": true])! == (4, true) && L.changed(["x": 1]) == nil, "contacts.changed")

    // Chat-Kontakte: contact_id (APP-API-CHAT §7)
    let ks = ContactsLogic.parse(k2("k2_chat_contacts") as? [String: Any])
    check(ks.count == 4 && ks.allSatisfy { $0.contactId != nil } && ks.first { $0.name == "Bert Berg" }?.contactId == bert.id, "contact_id aus /api/chat/contacts")
    check(ChatContact(["ref": "~abcdefghijklmnop", "name": "Alt"])?.contactId == nil, "aelterer Server: ohne contact_id")

    // Sprungziel Einstellungen
    check(SettingsArea.area(for: "kontakte") == .kontakte && SettingsArea.area(for: "contacts") == .kontakte && SettingsArea.scrollTarget(for: "kontakte") == nil, "Einstellungen › Kontakte")
}
