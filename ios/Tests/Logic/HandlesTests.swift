import Foundation

// 2.4 Testrunde: W4 (Handles statt Anmeldenamen anderer Konten) und W9 (waehrend des Anrufs gekoppeltes Geraet)
func handlesTests() {
    // W4: Name zum Zeigen - nie der Anmeldename, wenn Name oder Handle da sind
    check(PersonRef.display(name: "Anna Berg", ref: "~abcdefghijklmnop", id: "anna") == "Anna Berg", "Anzeigename zuerst")
    check(PersonRef.display(name: " ", ref: "~abcdefghijklmnop", id: "anna") == "~abcdefghijklmnop", "ohne Namen: Handle statt Anmeldename")
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
