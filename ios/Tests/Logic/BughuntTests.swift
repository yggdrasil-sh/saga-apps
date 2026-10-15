import Foundation

// Regressionstests zur Fehlerbehebung nach dem Bughunt 2.0 (BUG-IDs aus der Triage)
func bughuntTests() {
    // BUG-062: Return im Titel -> kein Zeilenumbruch, Sprung in den Inhalt
    var r = TitleInput.apply(old: "Leer", new: "X\nLeer")
    check(r.title == "X Leer" && r.enter == false, "062: eingefuegtes \"X<Umbruch>\" ist keine reine Return-Eingabe: \(r.title)")
    r = TitleInput.apply(old: "X", new: "X\n")
    check(r.title == "X" && r.enter, "062: Return am Ende -> Titel bleibt, enter")
    r = TitleInput.apply(old: "XLeer", new: "X\nLeer")
    check(r.title == "XLeer" && r.enter, "062: Return in der Mitte -> Titel ohne Umbruch, enter")
    r = TitleInput.apply(old: "", new: "Zeile 1\nZeile 2\r\n")
    check(r.title == "Zeile 1 Zeile 2" && !r.enter, "062: eingefuegte Zeilen werden zu einer Zeile: \(r.title)")
    r = TitleInput.apply(old: "A", new: "Ab")
    check(r.title == "Ab" && !r.enter, "062: normales Tippen unveraendert")
    check(!TitleInput.apply(old: "", new: "a\u{2028}b").title.contains("\u{2028}"), "062: Unicode-Zeilentrenner entfernt")

    // BUG-020: liegengebliebene Entwuerfe nachsenden
    check(DraftResend.outcome(200) == .sent && DraftResend.outcome(204) == .sent, "020: 2xx gesendet")
    check(DraftResend.outcome(409) == .hold, "020: Konflikt -> nicht automatisch ueberschreiben")
    check(DraftResend.outcome(413) == .hold && DraftResend.outcome(404) == .hold, "020: dauerhafte Fehler -> anbieten statt Dauerschleife")
    check(DraftResend.outcome(502) == .retry && DraftResend.outcome(429) == .retry && DraftResend.outcome(0) == .retry, "020: voruebergehend -> spaeter")
    check(DraftResend.pageID("12.json") == 12 && DraftResend.pageID("-2049.json") == -2049 && DraftResend.pageID("x.json") == nil && DraftResend.pageID("12.tmp") == nil, "020: Seiten-ID aus Dateiname")
    let d = PageDraft(title: "T", body: "{\"blocks\":[]}", rev: 3, saved: Date(timeIntervalSince1970: 1000), conflict: false)
    var c = d; c.conflict = true
    check(DraftResend.shouldSend(d, id: 5, open: []) && !DraftResend.shouldSend(c, id: 5, open: []) && !DraftResend.shouldSend(d, id: 5, open: [5]),
          "020: Konfliktkopien und offene Seiten nicht nachsenden")
    // Ablage: Entwurf geht nur an das eigene Konto, Dateiname = Seiten-ID
    let base = FileManager.default.temporaryDirectory.appendingPathComponent("hk-bh-\(UUID().uuidString)")
    defer { try? FileManager.default.removeItem(at: base) }
    let ds = DraftStore(origin: "https://saga.test", account: "anna", base: base)
    ds.save(7, d); ds.save(-9, c)
    let ids = Set(ds.files.compactMap { DraftResend.pageID($0.lastPathComponent) })
    check(ids == [7, -9], "020: Entwuerfe im Konto-Ordner gefunden: \(ids)")
    check(ds.load(7) == d, "020: Entwurf unveraendert gelesen (Vergleich vor dem Entfernen)")
    check(DraftStore(origin: "https://saga.test", account: "bert", base: base).files.isEmpty, "020: anderes Konto sieht nichts")

    // BUG-063: Server-Pruefung vor dem Anmeldefenster
    check(ServerProbe.classify(versionCode: nil, versionIsSaga: false, loginCode: nil) == .unreachable, "063: keine Verbindung")
    check(ServerProbe.classify(versionCode: 200, versionIsSaga: true, loginCode: nil) == .ok, "063: Saga-Server")
    check(ServerProbe.classify(versionCode: 404, versionIsSaga: false, loginCode: 400) == .ok, "063: alter Saga-Server ohne /api/version")
    check(ServerProbe.classify(versionCode: 200, versionIsSaga: false, loginCode: 404) == .notSaga, "063: fremde Seite")
    check(ServerProbe.classify(versionCode: 404, versionIsSaga: false, loginCode: nil) == .notSaga, "063: fremde Seite ohne Login")
    check(ServerProbe.isSagaVersion(Data("{\"version\":\"2.0.0\"}".utf8)) && !ServerProbe.isSagaVersion(Data("<html>".utf8)), "063: /api/version erkennen")

    // BUG-060: Startseite gehoert zum Konto
    let k = HomeOwner.key(origin: "https://saga.test", account: "anna")
    check(!HomeOwner.mustForget(stored: k, origin: "https://saga.test", account: "anna"), "060: gleiches Konto behaelt Startseite")
    check(HomeOwner.mustForget(stored: k, origin: "https://saga.test", account: "bert"), "060: anderes Konto vergisst")
    check(HomeOwner.mustForget(stored: k, origin: "https://other.test", account: "anna"), "060: anderer Server vergisst")
    check(!HomeOwner.mustForget(stored: nil, origin: "https://saga.test", account: "anna"), "060: alte Fassung ohne Besitzer")
}
