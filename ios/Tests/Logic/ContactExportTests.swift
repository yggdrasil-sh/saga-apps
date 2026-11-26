import Foundation

// 2.6 (Bugs-DB 2027b, W2): Vorbefuellung "Zu Kontakten hinzufuegen" (App/ContactExportLogic.swift).
// Beispielwerte (Wegwerf-Namen, Nummern aus Beispielbereichen, example.org).

func contactExportTests() {
    typealias L = ContactBookLogic
    typealias X = ContactExportLogic
    func j(_ s: String) -> L.Contact? { L.contact(try? JSONSerialization.jsonObject(with: Data(s.utf8))) }

    // Chat-Kontakt mit Saga-Adresse, zwei Nummern, zwei Mails - die Saga-Adresse geht nie mit
    let chat = j("""
    {"id": 7, "ver": 2, "origin": "chat", "name": "Bert Berg", "own_name": null,
     "saga": {"ref": "~k3abc", "remote": false, "sub": "", "ready": true},
     "form": "informal", "salutation": "Lieber Bert",
     "addrs": [{"id": 1, "kind": "saga", "value": "~k3abc", "fixed": true},
               {"id": 2, "kind": "e164", "value": "+491701234567", "type": "mobile"},
               {"id": 3, "kind": "e164", "value": "+493012345678", "type": "work"},
               {"id": 4, "kind": "email", "value": "bert@example.org", "type": "home"},
               {"id": 5, "kind": "email", "value": "b.berg@example.org", "type": "work"}]}
    """)!
    let p = X.prefill(chat)!
    check(p.name == "Bert Berg", "Name der Person, wenn kein Name fuer mich")
    check(p.phones == [.init(value: "+491701234567", label: .mobile), .init(value: "+493012345678", label: .work)], "Nummern E.164 mit Typ, Reihenfolge wie gespeichert")
    check(p.mails == [.init(value: "bert@example.org", label: .home), .init(value: "b.berg@example.org", label: .work)], "Mails mit Typ")
    let all = ([p.name ?? ""] + p.phones.map(\.value) + p.mails.map(\.value)).joined(separator: "|")
    check(!all.contains("~k3abc") && !all.contains("Lieber Bert") && !all.contains("informal"), "nie Saga-Adresse, Anrede, Grussform")
    check(X.canExport(chat), "Knopf sichtbar")

    // fremder Server: auch ~x@host bleibt draussen; Name fuer mich geht vor
    let fed = j("""
    {"id": 8, "ver": 1, "origin": "chat", "name": "Oma", "own_name": "Oma",
     "saga": {"ref": "~p9@saga.example.net", "remote": true, "host": "saga.example.net"},
     "addrs": [{"kind": "fed", "value": "~p9@saga.example.net", "fixed": true},
               {"kind": "e164", "value": "+436641234567", "type": "home"}]}
    """)!
    let pf = X.prefill(fed)!
    check(pf.name == "Oma" && pf.phones.map(\.value) == ["+436641234567"] && pf.mails.isEmpty, "fremd: Name fuer mich, nur die Nummer")
    check(![pf.name ?? ""].contains { $0.contains("example.net") }, "kein Host des anderen Servers")

    // Typ-Abbildung
    check(X.phoneLabel("mobile") == .mobile && X.phoneLabel("home") == .home && X.phoneLabel("work") == .work && X.phoneLabel("other") == .other, "Nummer: mobile/home/work/other")
    check(X.phoneLabel(nil) == .other && X.phoneLabel("pager") == .other, "Nummer ohne/unbekannter Typ -> Andere")
    check(X.mailLabel("home") == .home && X.mailLabel("work") == .work && X.mailLabel("other") == .other, "Mail: home/work/other")
    check(X.mailLabel("mobile") == .other && X.mailLabel(nil) == .other, "Mail mobil/ohne Typ -> Andere (iOS hat kein Mobil fuer Mails)")

    // nur ein Saga-Kontakt ohne Nummer/Mail: kein Knopf
    let bare = j("""
    {"id": 9, "ver": 1, "origin": "chat", "name": "Cleo", "saga": {"ref": "~k3x"}, "addrs": [{"kind": "saga", "value": "~k3x", "fixed": true}]}
    """)!
    check(X.prefill(bare) == nil && !X.canExport(bare), "ohne Nummer/Mail kein Knopf")

    // Rueckfall-Name des Servers (erste Nummer/Mail) bzw. "–" ist kein Name
    let noName = j("""
    {"id": 10, "ver": 1, "origin": "manual", "name": "+491701234567", "own_name": null,
     "addrs": [{"kind": "e164", "value": "+491701234567", "type": null}]}
    """)!
    let pn = X.prefill(noName)!
    check(pn.name == nil && pn.phones == [.init(value: "+491701234567", label: .other)], "Rueckfall-Name (Nummer) nicht als Name, Nummer ohne Typ -> Andere")
    let mailName = j("""
    {"id": 11, "ver": 1, "origin": "manual", "addrs": [{"kind": "email", "value": "x@example.org"}]}
    """)!
    check(X.prefill(mailName)?.name == nil, "ohne name: App-Rueckfall (Mail) nicht als Name")
    let dash = j("""
    {"id": 12, "ver": 1, "origin": "manual", "name": "–", "addrs": [{"kind": "email", "value": "y@example.org"}]}
    """)!
    check(X.prefill(dash)?.name == nil, "\"–\" ist kein Name")
    let tilde = j("""
    {"id": 13, "ver": 1, "origin": "chat", "name": "~k3abc", "addrs": [{"kind": "e164", "value": "+491701234567"}]}
    """)!
    check(X.prefill(tilde)?.name == nil, "Saga-Handle als Name geht nicht mit")

    // Sonderzeichen, Steuerzeichen, leere/kaputte Werte, Doppelte
    let odd = j("""
    {"id": 14, "ver": 1, "origin": "manual", "name": "Ågot Øvre-Ström", "own_name": "  Ågot \\u0007Øvre-Ström \\n",
     "addrs": [{"kind": "e164", "value": "+4917012345"}, {"kind": "e164", "value": "+4917012345", "type": "work"},
               {"kind": "e164", "value": "0170 1234567"}, {"kind": "e164", "value": "+0123456789"},
               {"kind": "e164", "value": "tel:+491701234567"}, {"kind": "e164", "value": "  "},
               {"kind": "email", "value": "a,b@example.org"}, {"kind": "email", "value": "a b@example.org"},
               {"kind": "email", "value": "a@b@example.org"}, {"kind": "email", "value": "\\u200Bz@example.org"},
               {"kind": "email", "value": "ÄGOT+tag@Example.org"}, {"kind": "email", "value": "ägot+tag@example.org"},
               {"kind": "email", "value": "Q@EXAMPLE.ORG"}, {"kind": "email", "value": "q@example.org"},
               {"kind": "fax", "value": "+491701234567"}]}
    """)!
    let po = X.prefill(odd)!
    check(po.name == "Ågot Øvre-Ström", "Steuerzeichen raus, Leerraum zusammengefasst, Umlaute bleiben: \(po.name ?? "nil")")
    check(po.phones == [.init(value: "+4917012345", label: .other)], "nur gueltige E.164, Doppelte einmal (erste zaehlt): \(po.phones)")
    check(po.mails.map(\.value) == ["ÄGOT+tag@Example.org", "Q@EXAMPLE.ORG"], "nur ein Empfaenger je Mail, unsichtbare Zeichen raus, Doppelte einmal: \(po.mails.map(\.value))")

    // Pruefer pk27b: Bidi-/Formatzeichen (Cf) raus wie auf Android, Mail damit ungueltig
    check(X.clean("Anna\u{202E} Berg\u{2066}") == "Anna Berg", "Bidi-Steuerung raus: \(X.clean("Anna\u{202E} Berg\u{2066}"))")
    check(!X.validMail("anna\u{202E}@example.org") && !X.validMail("anna@exa\u{2066}mple.org") && !X.validMail("anna@example.org\u{00AD}"), "Mail mit Formatzeichen ungueltig")
    check(!X.validPhone("+491701234567\n") && !X.validPhone("+\u{0664}\u{0669}\u{0661}\u{0667}\u{0660}\u{0661}\u{0662}\u{0663}\u{0664}"), "E.164 nur ASCII-Ziffern, kein Zeilenende")

    // alles kaputt -> kein Knopf
    let broken = j("""
    {"id": 15, "ver": 1, "origin": "manual", "name": "Kaputt", "own_name": "Kaputt",
     "addrs": [{"kind": "e164", "value": "12345"}, {"kind": "email", "value": "kein-at"}]}
    """)!
    check(X.prefill(broken) == nil, "nur ungueltige Werte: kein Knopf")

    // hoechstens 10 je Art
    let many = L.Contact(id: 16, ver: 1, name: "Viele", ownName: "Viele",
                         addrs: (0..<12).map { L.Addr(id: $0, kind: "e164", value: "+4917012345\(String(format: "%02d", $0))", type: "mobile", fixed: false) })
    check(X.prefill(many)?.phones.count == 10, "hoechstens 10 Nummern")
    // feste e164 (gibt es nicht, aber falls) gehen nicht mit
    let fixed = L.Contact(id: 17, ver: 1, name: "F", addrs: [L.Addr(id: 1, kind: "e164", value: "+491701234567", type: nil, fixed: true)])
    check(X.prefill(fixed) == nil, "feste Werte nie")
    // langer Name: 120 Zeichen
    let long = L.Contact(id: 18, ver: 1, name: String(repeating: "x", count: 300), ownName: String(repeating: "x", count: 300),
                         addrs: [L.Addr(id: 1, kind: "email", value: "l@example.org", type: "work", fixed: false)])
    check(X.prefill(long)?.name?.count == 120, "Name hoechstens 120 Zeichen")

    // Vor-/Nachname: jedes Zeichen bleibt, sonst alles als Vorname
    check(X.parts("Anna Berg") == .init(given: "Anna", family: "Berg"), "Anna Berg -> Vor-/Nachname: \(X.parts("Anna Berg"))")
    check(X.parts("Oma") == .init(given: "Oma"), "ein Wort -> Vorname")
    check(X.parts("สมชาย") == .init(given: "สมชาย"), "Thai ohne Leerzeichen -> Vorname")
    let dr = X.parts("Praxis Dr. Weber")
    check([dr.given, dr.middle, dr.family].filter { !$0.isEmpty }.joined(separator: " ") == "Praxis Dr. Weber", "nichts geht verloren: \(dr)")
    for n in ["Ågot Øvre-Ström", "Jean-Luc de la Mer", "Иван Петров", "María José García López", "Dr. Anna Berg", "Anna Berg Jr."] {
        let q = X.parts(n)
        check([q.given, q.middle, q.family].filter { !$0.isEmpty }.joined(separator: " ") == n, "Zerlegung verlustfrei: \(n) -> \(q)")
    }
}
