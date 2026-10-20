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
