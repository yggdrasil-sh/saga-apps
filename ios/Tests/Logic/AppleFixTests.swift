import Foundation

// Funde des Apple-Tests 2.4: Papierkorb (F1), Knoepfe in Listenzeilen, weitere Logik
func appleFixTests() {
    // F1: Papierkorb-Eintrag lesen, Rueckfrage
    let it = TrashLogic.item(["id": 14, "title": " Notizen ", "icon": "", "parent_id": 3, "subpages": 2, "purge_at": 1_800_000_000], parentLabel: { $0 == 3 ? "Projekte" : nil })
    check(it.id == 14 && it.title == "Notizen" && it.icon == nil && it.parent == "Projekte" && it.subpages == 2 && it.purgeAt == 1_800_000_000, "Papierkorb-Eintrag gelesen")
    check(TrashLogic.item(["id": 1]).parent.isEmpty && TrashLogic.item(["id": 1, "subpages": -3]).subpages == 0, "ohne Eltern / negative Unterseiten")
    check(TrashLogic.purgeQuestion(it).contains("Notizen") && TrashLogic.purgeQuestion(it).contains("2"), "Rueckfrage nennt Titel und Unterseiten")
    check(TrashLogic.purgeQuestion(nil).isEmpty, "ohne Auswahl keine Rueckfrage")

    // Knoepfe in Listenzeilen: Muster erkennen
    let bad = """
    List {
        ForEach(items) { it in
            HStack {
                Text("x")
                Button("Zurückholen") { restore() }
                Button("Endgültig löschen", role: .destructive) { purge() }
            }
        }
    }
    """
    check(RowButtonLint.findings(file: "a", source: bad) == [.init(file: "a", line: 3)], "Papierkorb-Muster (2.3) wird erkannt")
    let fixed = "List {\n HStack {\n Button(\"A\") {}\n Button(\"B\") {}\n }\n .rowButtons()\n}"
    check(RowButtonLint.findings(file: "a", source: fixed).isEmpty, "mit .rowButtons() erlaubt")
    let styled = """
    Form { Section {
        HStack {
            Button("A") { }.buttonStyle(.bordered)
            Button("B") { }.prominentGold()
        }
    } }
    """
    check(RowButtonLint.findings(file: "b", source: styled).isEmpty, "jeder Knopf mit eigenem Stil: erlaubt")
    let outside = "VStack {\n HStack { Button(\"A\") {}; Button(\"B\") {} }\n}"
    check(RowButtonLint.findings(file: "c", source: outside).isEmpty, "ausserhalb von List/Form: erlaubt")
    let oneStyled = "Form {\n HStack {\n Button(\"A\") {}.buttonStyle(.bordered)\n Button(\"B\") {}\n }\n}"
    check(RowButtonLint.findings(file: "d", source: oneStyled).count == 1, "ein Knopf ohne Stil: Fund")

    let nested = "Form {\n VStack {\n Text(\"x\")\n HStack {\n Button(\"A\") {}\n Button(\"B\") {}\n }\n .rowButtons()\n }\n}"
    check(RowButtonLint.findings(file: "e", source: nested).isEmpty, "innerer Stapel mit .rowButtons() deckt den aeusseren ab")
    let menu = "List {\n HStack {\n Button(\"A\") {}.buttonStyle(.bordered)\n Menu {\n Button(\"X\") {}\n Button(\"Y\") {}\n } label: { Text(\"m\") }\n }\n}"
    check(RowButtonLint.findings(file: "f", source: menu).isEmpty, "Knoepfe im Menue zaehlen nicht")

    // Ganze App: kein Fund (Regression fuer F1 und alle gleichartigen Stellen)
    if let dir = ProcessInfo.processInfo.environment["SAGA_APP_SRC"],
       let files = try? FileManager.default.contentsOfDirectory(atPath: dir) {
        var all: [RowButtonLint.Finding] = []
        for f in files.sorted() where f.hasSuffix(".swift") {
            if let src = try? String(contentsOfFile: dir + "/" + f, encoding: .utf8) { all += RowButtonLint.findings(file: f, source: src) }
        }
        check(all.isEmpty, "Knoepfe ohne Stil in Listenzeilen: \(all.map { "\($0.file):\($0.line)" })")
        check(files.count > 50, "App-Quellen gefunden")
    } else { check(false, "SAGA_APP_SRC fehlt") }
}

func appleFixEditorTests() {
    // F4: Zeichen beim Fokuswechsel weiterreichen
    check(EditorCarry.inserted(old: "Punkt A", new: "Punkt AP") == "P", "angehaengt")
    check(EditorCarry.inserted(old: "ab", new: "aXYb") == "XY", "in der Mitte eingefuegt")
    check(EditorCarry.inserted(old: "aa", new: "aaa") == "a", "gleiche Zeichen")
    check(EditorCarry.inserted(old: "abc", new: "ab") == nil && EditorCarry.inserted(old: "abc", new: "xbcd") == nil, "Loeschen/Ersetzen: nichts weiterreichen")
    check(EditorCarry.inserted(old: "", new: "B\n") == "B\n", "aus leerem Feld")
    let a = EditorCarry.split("unkt B\n\n")
    check(a.head == "unkt B" && a.newline && a.rest == "\n", "bis zum Return, Rest weiter")
    let b = EditorCarry.split("xy")
    check(b.head == "xy" && !b.newline && b.rest.isEmpty, "ohne Return")
}

func appleFixConvertTests() {
    // F5: Bild & Co. nicht in Text umwandeln; verlustbehaftet nur mit Rueckfrage
    check(BlockConvert.mode(type: "image", data: ["file": ["url": "x"]]) == .no, "Bild: kein Umwandeln")
    for t in ["table", "code", "embed", "delimiter", "columns", "file", "nextcloud", "database", "buttons"] { check(BlockConvert.mode(type: t, data: [:]) == .no, "\(t): kein Umwandeln") }
    for t in ["paragraph", "header", "quote", "callout"] { check(BlockConvert.mode(type: t, data: ["text": "a"]) == .free, "\(t): frei") }
    check(BlockConvert.mode(type: "list", data: ["items": [["content": "a", "items": []]]]) == .free, "flache Liste: frei")
    check(BlockConvert.mode(type: "list", data: ["items": [["content": "a", "items": [["content": "b"]]]]]) == .confirm, "Unterpunkte: Rueckfrage")
    check(BlockConvert.mode(type: "toggle", data: ["blocks": [["type": "paragraph"]]]) == .confirm && BlockConvert.mode(type: "toggle", data: [:]) == .free, "Aufklapp-Block mit Inhalt: Rueckfrage")
}

func appleFixLinkTests() {
    // F6: Seitenverweis zeigt den aktuellen Titel
    let pages: [Int: PageLinkTitles.Target] = [15: .init(title: "Reiseplanung", icon: nil), 16: .init(title: "", icon: "🎉"), -7: .init(title: "Fremd <b>", icon: nil)]
    let look: (Int) -> PageLinkTitles.Target? = { pages[$0] }
    check(PageLinkTitles.apply("📄 <a href=\"/notes#15\">Unbenannt</a>", lookup: look) == "📄 <a href=\"/notes#15\">Reiseplanung</a>", "Unbenannt -> aktueller Titel")
    check(PageLinkTitles.apply("<a href=\"/notes#16\">alt</a> und <a href=\"/notes#99\">weg</a>", lookup: look) == "<a href=\"/notes#16\">🎉 \(String(localized: "Unbenannt"))</a> und <a href=\"/notes#99\">weg</a>", "Symbol vorne, unbekannte Seite bleibt")
    check(PageLinkTitles.apply("<a href=\"/notes#-7\">x</a>", lookup: look) == "<a href=\"/notes#-7\">Fremd &lt;b&gt;</a>", "fremde Seite, Titel maskiert")
    check(PageLinkTitles.apply("<a href=\"/notes#15\"><b>fett</b></a>", lookup: look) == "<a href=\"/notes#15\"><b>fett</b></a>", "formatierter Link bleibt")
    check(PageLinkTitles.apply("<a href=\"https://x.org/notes#15\">x</a>", lookup: look).contains(">x<"), "fremde Adresse bleibt")
    check(PageLinkTitles.apply("ohne Link", lookup: look) == "ohne Link" && PageLinkTitles.apply("<a href=\"/notes#15\">a</a>", lookup: nil) == "<a href=\"/notes#15\">a</a>", "nichts zu tun")
}

func appleFixNetTests() {
    // Entwickler-Builds: http nur zu privaten Netzen
    for h in ["127.0.0.1", "localhost", "::1", "[::1]", "10.1.2.3", "172.16.0.1", "172.31.255.255", "192.168.1.12", "169.254.3.4", "100.64.0.1", "fd12:3456::1", "fe80::1"] {
        check(PrivateNet.isPrivate(host: h), "privat: \(h)")
    }
    for h in ["8.8.8.8", "172.32.0.1", "172.15.0.1", "192.169.1.1", "saga.example.org", "192.168.1", "192.168.1.256", "1.2.3.4.5", "2001:db8::1", "fdomain.org", "evil.192.168.1.12.nip.io"] {
        check(!PrivateNet.isPrivate(host: h), "nicht privat: \(h)")
    }
}

func appleFixMiscTests() {
    // F17: ungueltige Nextcloud-Adresse frueh erkennen
    for ok in ["cloud.example.org", "https://cloud.example.org/", "http://192.168.1.5:8080", "nc.local", "localhost:8080", "nextcloud"] { check(CloudURLCheck.plausible(ok), "Adresse ok: \(ok)") }
    for bad in ["", "kein url", "https://", "ftp://x.org", "a_b?.org", "  "] { check(!CloudURLCheck.plausible(bad), "Adresse ungueltig: \(bad)") }
    // F14: Abbruch ist kein Fehler
    check(ServiceErrors.isCancel(CancellationError()) && ServiceErrors.isCancel(URLError(.cancelled)) && !ServiceErrors.isCancel(URLError(.timedOut)), "Abbruch erkannt")
}
