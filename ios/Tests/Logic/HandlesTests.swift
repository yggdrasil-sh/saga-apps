import Foundation

// 2.4 Testrunde: W4 (Handles statt Anmeldenamen anderer Konten) und W9 (waehrend des Anrufs gekoppeltes Geraet)
func handlesTests() {
    // W4: Name zum Zeigen - nie der Anmeldename, wenn Name oder Handle da sind
    check(PersonRef.display(name: "Anna Berg", ref: "~abcdefghijklmnop", id: "anna") == "Anna Berg", "Anzeigename zuerst")
    check(PersonRef.display(name: " ", ref: "~abcdefghijklmnop", id: "anna") == "Unbekannte Person (mnop)", "ohne Namen: lesbarer Ersatz statt Handle/Anmeldename (D01)")
    // D01: lesbarer Ersatz
    check(PersonName.unnamed("~s545sgsgkkyhspqz") == PersonName.Unnamed(tag: "spqz", host: nil), "Kennung -> Unnamed")
    check(PersonName.unnamed("~s545sgsgkkyhspqz@b.example") == PersonName.Unnamed(tag: "spqz", host: "b.example"), "Kennung mit Server")
    check(PersonName.unnamed("", id: "@~s545sgsgkkyhspqz@b.example") == PersonName.Unnamed(tag: "spqz", host: "b.example"), "Prinzipal als Kennung")
    check(PersonName.unnamed("Anna", id: "~s545sgsgkkyhspqz") == nil, "echter Name bleibt")
    check(PersonName.unnamed("~kurz") == nil && PersonName.unnamed("", id: "anna") == nil, "keine Kennung -> nil")
    check(PersonName.shown("~s545sgsgkkyhspqz", host: "c.example:8443") == "Person auf c.example:8443 (spqz)", "fremder Server lesbar")
    check(PersonName.shown("Unbekannte Person (spqz)") == "Unbekannte Person (spqz)", "Ersatz vom Server bleibt")
    check(AccountName.prefill(username: "anna", name: "anna") == "" && AccountName.prefill(username: "anna", name: " Anna B ") == "Anna B", "Vorbelegung: Anmeldename gilt als leer")
    let acc = AccountName.parse(["username": "anna", "name": "anna", "email": "a@example.org", "can_edit": true])
    check(acc?.name == "" && acc?.canEdit == true && acc?.email == "a@example.org", "Konto lesen")
    let bd = AccountName.body(name: "  Anna  ", email: "a@example.org")
    check(bd["name"] as? String == "Anna" && bd["email"] as? String == "a@example.org", "PATCH mit unveraenderter E-Mail")
    check(AccountName.body(name: "x", email: nil)["email"] == nil, "ohne bekannte E-Mail nur der Name")
    check(PersonRef.display(name: nil, ref: nil, id: "anna") == "anna", "aelterer Server: Kennung bleibt Rueckfall")
    // W4: Personensuche liefert Handles, Mitglieder username + ref
    let mem: [(username: String, ref: String?)] = [("anna", "~aaaaaaaaaaaaaaaa"), ("bert", nil)]
    check(PersonRef.isMember("~aaaaaaaaaaaaaaaa", members: mem), "Handle aus der Suche = Mitglied ueber ref")
    check(PersonRef.isMember("bert", members: mem), "aelterer Server: Abgleich ueber username")
    check(!PersonRef.isMember("~bbbbbbbbbbbbbbbb", members: mem), "anderer Handle ist kein Mitglied")
    // W9: unbekanntes Geraet -> einmal frisch holen; bekanntes nicht
    check(CallRoom.needsFreshKeys(device: "c0de", signed: ["0e32"]), "neu gekoppeltes Geraet: Schluessel frisch holen")
    check(!CallRoom.needsFreshKeys(device: "0e32", signed: ["0e32"]), "bekanntes Geraet: Zwischenspeicher genuegt")
    check(!CallRoom.needsFreshKeys(device: "", signed: []), "ohne Geraet nichts holen")
}
