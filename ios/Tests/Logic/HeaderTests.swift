import Foundation

// Seitenkopf (Titelbild/Symbol) und Schaltflaechen-Bloecke (App/PageHeaderLogic.swift)
func headerTests() {
    // Optimistisch setzen + zuruecknehmen
    var page: [String: Any] = ["title": "T", "icon": "📝", "cover_pos": 30.0]
    let undo = HeaderLogic.apply(&page, ["cover": "/files/abc", "cover_pos": 50.0])
    check(page["cover"] as? String == "/files/abc" && page["cover_pos"] as? Double == 50, "Titelbild sofort uebernommen")
    check(undo["cover"] is NSNull && undo["cover_pos"] as? Double == 30, "alte Werte gemerkt (fehlendes Feld = NSNull)")
    HeaderLogic.apply(&page, undo)
    check(page["cover"] == nil && page["cover_pos"] as? Double == 30 && page["icon"] as? String == "📝", "Zuruecknehmen stellt den alten Stand her")
    let u2 = HeaderLogic.apply(&page, ["icon": NSNull()])
    check(page["icon"] == nil && u2["icon"] as? String == "📝", "NSNull entfernt das Feld")
    HeaderLogic.apply(&page, u2)
    check(page["icon"] as? String == "📝", "Symbol zurueck")

    // Position
    check(HeaderLogic.coverPos(nil) == 50, "Vorgabe 50")
    check(HeaderLogic.coverPos(50) == 50, "Int (frueher lokal gespeichert) wird gelesen")
    check(HeaderLogic.coverPos(NSNumber(value: 12.5)) == 12.5, "NSNumber")
    check(HeaderLogic.coverPos(150.0) == 100 && HeaderLogic.coverPos(-3.0) == 0, "auf 0...100 begrenzt")
    check(HeaderLogic.coverPos(Double.nan) == 50 && HeaderLogic.coverPos("x") == 50, "Unsinn -> 50")

    // Tippen auf Symbol/Titelbild
    check(HeaderLogic.tap(readOnlyRole: false, remote: false, fetched: true, locked: false) == .edit, "eigene Seite: Auswahl")
    check(HeaderLogic.tap(readOnlyRole: false, remote: false, fetched: true, locked: true) == .lockedHint, "gesperrt: Hinweis")
    check(HeaderLogic.tap(readOnlyRole: true, remote: false, fetched: true, locked: true) == .none, "nur lesen: nichts")
    check(HeaderLogic.tap(readOnlyRole: false, remote: true, fetched: true, locked: false) == .none, "fremde Instanz: nichts")
    check(HeaderLogic.tap(readOnlyRole: false, remote: false, fetched: false, locked: false) == .none, "nicht geladen: nichts (BUG-061)")

    // Nachladen: erster Versuch sofort, dann mit wachsenden Pausen, insgesamt begrenzt
    let r = HeaderLogic.retryDelays
    check(r.first == 0 && r.count >= 3 && zip(r, r.dropFirst()).allSatisfy { $0 < $1 } && r.reduce(0, +) <= 15, "Wiederholungen: \(r)")

    // Schaltflaechen: Titel-Vorlage wie im Web
    check(ButtonLogic.title("ToDo {datum}", today: "18.10.2026") == "ToDo 18.10.2026", "{datum}")
    check(ButtonLogic.title("Diary {date} / {fecha}", today: "x") == "Diary x / x", "{date} und {fecha} wie im Web")
    check(ButtonLogic.title("", today: "x") == "", "leer bleibt leer")

    // Tor gegen doppeltes Ausloesen
    var g = ButtonLogic.Gate()
    check(g.begin("0"), "erstes Tippen startet")
    check(!g.begin("0") && g.busy("0"), "zweites Tippen waehrend der Anfrage: ignoriert")
    check(g.begin("1"), "anderer Knopf unabhaengig")
    g.end("0")
    check(!g.busy("0") && g.begin("0"), "nach dem Ende wieder bereit")
    check(ButtonLogic.enabled(readOnlyRole: false) && !ButtonLogic.enabled(readOnlyRole: true), "gesperrt ok (wie Web), nur Lesen nicht")
}
