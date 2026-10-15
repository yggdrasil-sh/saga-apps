import Foundation

// Nachbesser-Runde 2.0: einheitliche Darstellung der Codes, lesbarer Echtheits-Hinweis, Schluesselzeilen, Geraetenamen, Push
func fixTests() {
    // 1. Sicherheitsnummer 3 x 4 Gruppen a 5 Ziffern, Kopplungscode 4 Gruppen in einer Zeile (sonst 2 x 2)
    let num = "03672 14150 98611 54486 57516 88114 68710 78412 97318 13879 97709 10663"
    let rows = ChatLogic.safetyRows(num)
    check(rows.count == 3 && rows.allSatisfy { $0.count == 4 && $0.allSatisfy { $0.count == 5 && $0.allSatisfy(\.isNumber) } }, "Sicherheitsnummer 3 x 4 x 5")
    check(rows[0] == ["03672", "14150", "98611", "54486"] && rows[2].last == "10663", "Sicherheitsnummer Reihenfolge zeilenweise")
    check(ChatLogic.safetyRows(num.replacingOccurrences(of: " ", with: "")) == rows, "Sicherheitsnummer ohne Leerzeichen gleich")
    let code = "12345 67890 13579 24680"
    check(ChatLogic.linkCodeLines(code, oneLine: true) == ["12345 67890 13579 24680"], "Kopplungscode eine Zeile")
    check(ChatLogic.linkCodeLines(code, oneLine: false) == ["12345 67890", "13579 24680"], "Kopplungscode 2 Zeilen a 2 Gruppen")
    check(ChatLogic.linkCodeLines("12345678901357924680", oneLine: true) == [code], "Kopplungscode aus reinen Ziffern")

    // 3. "Echtheit nicht bestaetigt": Hinweistext >= 4,5:1 auf beiden Blasen, hell und dunkel; Blasen deckend
    let P = ChatLogic.Palette.self
    for (txt, bg, name) in [(P.hint.light, P.bubbleOther.light, "hell fremd"), (P.hint.light, P.bubbleMine.light, "hell eigen"),
                            (P.hint.dark, P.bubbleOther.dark, "dunkel fremd"), (P.hint.dark, P.bubbleMine.dark, "dunkel eigen")] {
        check(ChatLogic.contrast(txt, bg) >= 4.5, "Kontrast Hinweis \(name): \(String(format: "%.2f", ChatLogic.contrast(txt, bg)))")
    }
    // Warnsymbol: grafisches Element >= 3:1
    check(ChatLogic.contrast(P.warn.light, P.bubbleOther.light) >= 3 && ChatLogic.contrast(P.warn.dark, P.bubbleOther.dark) >= 3, "Kontrast Warnsymbol")
    check(abs(ChatLogic.contrast(0x000000, 0xFFFFFF) - 21) < 0.01 && abs(ChatLogic.contrast(0x777777, 0x777777) - 1) < 0.001, "Kontrastformel WCAG")

    // 5. Wiederherstellungsschluessel: 2 Zeilen a 4 ganze Gruppen, zusammen wieder der Schluessel
    let rk = "LNBN-PIGL-JL7T-2DOX-VOJR-AT4Q-PHUZ-P77E"
    let lines = ChatLogic.recoveryLines(rk)
    check(lines == ["LNBN-PIGL-JL7T-2DOX", "VOJR-AT4Q-PHUZ-P77E"], "Schluessel 2 x 4 Gruppen")
    check(lines.joined(separator: "-") == rk && lines.allSatisfy { $0.split(separator: "-").allSatisfy { $0.count == 4 } }, "keine Gruppe zerteilt")

    // 9. Geraetenamen menschlich
    check(ChatLogic.deviceName(name: "iPhone", model: "iPhone") == "iPhone", "Name = Modell")
    check(ChatLogic.deviceName(name: "", model: "iPad") == "iPad", "leerer Name -> Modell")
    check(ChatLogic.deviceName(name: "Annas iPad", model: "iPad") == "Annas iPad", "Name enthaelt Modell")
    check(ChatLogic.deviceName(name: "Werkstatt", model: "Mac") == "Werkstatt (Mac)", "Name + Modell")
    check(ChatLogic.deviceName(name: String(repeating: "x", count: 200), model: "Mac").count == 80, "hoechstens 80 Zeichen")

    // 6. Push: Apple-Abos menschlich, Dubletten derselben App-Anmeldung
    check(PushLogic.service(type: "apns", whereText: "Apple (sandbox)") == .sagaApp(test: true)
          && PushLogic.service(type: "apns", whereText: "Apple (production)") == .sagaApp(test: false)
          && PushLogic.service(type: "webpush", whereText: "Mozilla (Firefox)") == .other("Mozilla (Firefox)"), "Dienst-Bezeichnung")
    let devs = [["id": 1, "type": "apns", "this_device": true], ["id": 2, "type": "apns", "this_device": true],
                ["id": 3, "type": "apns", "this_device": false], ["id": 4, "type": "webpush", "this_device": true]].compactMap { PushDevice($0) }
    check(PushLogic.duplicates(devs, keep: 2) == [1], "Dubletten: nur aeltere Abos dieses Geraets")
    var st = PushState(); st.enabled = true; st.apns = false
    check(PushLogic.availability(st) == .appleMissing, "Push: Apple fehlt -> Schalter gesperrt")
}
