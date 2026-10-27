import Foundation

// Saga 2.5 Vorlagengalerie (App/GalleryLogic.swift): Liste lesen (tolerant), Gruppierung, Pfade, Request-Body,
// Antwort von "Verwenden", Fehlerabbildung (404 aelterer Server, 400/403/429, Text des Servers)
func galleryTests() {
    func d(_ s: String) -> Data { Data(s.utf8) }

    // MARK: Liste
    let list = #"""
    {"items":[
      {"id":"aufgaben-projekte","title":"Aufgaben & Projekte","desc":"Projekte mit Aufgaben","icon":"✅","category":"work","order":20,"needs":[],"contents":[{"kind":"page","title":"Start","icon":"🏠"},{"kind":"database","title":"Aufgaben","icon":"☑️","extra":1},{"kind":"page","title":""}],"neu":"x"},
      {"id":"mein-tag","title":"  Mein Tag  ","desc":"Startseite","icon":"☀️","category":"personal","order":10,"needs":["calendar"]},
      {"id":"leseliste","title":"Leseliste","category":"personal","order":10},
      {"id":"wiki","title":"Team-Wiki","category":"team","order":"5"},
      {"id":"x","title":"Unbekannt","category":"spass"},
      {"id":"ohne-titel","title":"   "},
      {"id":"../böse","title":"Pfad"},
      {"title":"ohne ID"},
      {"id":"mein-tag","title":"doppelt","category":"team"},
      "kein Objekt",
      {"id":"zahl","title":"Zahl","order":true}
    ],"total":9}
    """#
    guard let items = GalleryLogic.parseList(d(list)) else { check(false, "Liste nicht lesbar"); return }
    check(items.map(\.id) == ["aufgaben-projekte", "mein-tag", "leseliste", "wiki", "x", "zahl"], "gueltige Eintraege, doppelte ID nur einmal: \(items.map(\.id))")
    let ap = items[0]
    check(ap.title == "Aufgaben & Projekte" && ap.desc == "Projekte mit Aufgaben" && ap.icon == "✅" && ap.category == .work && ap.order == 20, "Felder gelesen")
    check(ap.contents.map(\.title) == ["Start", "Aufgaben"] && ap.contents[1].kind == "database", "Inhalt ohne leere Titel")
    check(items[1].title == "Mein Tag" && items[1].needs == ["calendar"], "Titel getrimmt, needs")
    check(items[2].desc == "" && items[2].icon == "" && items[2].contents.isEmpty, "fehlende Felder leer")
    check(items[3].order == 5, "order als Text")
    check(items[4].category == .other && items[4].order == 1000, "unbekannte Kategorie -> sonstige, Standard-Reihenfolge")
    check(items[5].order == 1000, "true ist keine Zahl")
    check(GalleryLogic.parseList(d(#"{"items":[]}"#)) == [], "leere Liste")
    check(GalleryLogic.parseList(d(#"{"error":"x"}"#)) == nil && GalleryLogic.parseList(d("<html>")) == nil && GalleryLogic.parseList(d("[]")) == nil, "keine Liste")
    let long = GalleryLogic.parseList(d(#"{"items":[{"id":"a","title":"\#(String(repeating: "t", count: 500))","desc":"\#(String(repeating: "d", count: 900))"}]}"#))
    check(long?.first?.title.count == 120 && long?.first?.desc.count == 400, "Laengen begrenzt")

    // MARK: Gruppierung
    let g = GalleryLogic.groups(items)
    check(g.map(\.category) == [.personal, .work, .team, .other], "Reihenfolge personal, work, team, sonstige")
    check(g[0].items.map(\.id) == ["leseliste", "mein-tag"], "gleiche order: nach Titel")
    check(g[3].items.map(\.id) == ["x", "zahl"], "sonstige nach Titel")
    check(GalleryLogic.groups(items.filter { $0.category == .team }).map(\.category) == [.team], "leere Gruppen fallen weg")
    check(GalleryLogic.groups([]).isEmpty, "keine Gruppen")
    var ord = items.filter { $0.category == .personal }
    ord[ord.firstIndex { $0.id == "leseliste" }!].order = 30
    check(GalleryLogic.groups(ord)[0].items.map(\.id) == ["mein-tag", "leseliste"], "order vor Titel")

    // MARK: Pfade und Body
    check(GalleryLogic.listPath(lang: "ru") == "api/gallery?lang=ru" && GalleryLogic.listPath(lang: "fr") == "api/gallery?lang=en", "Sprache in der Liste")
    check(GalleryLogic.usePath("mein-tag") == "api/gallery/mein-tag/use" && GalleryLogic.usePath("a_b-9") != nil, "Pfad Verwenden")
    check(GalleryLogic.usePath("") == nil && GalleryLogic.usePath("a/b") == nil && GalleryLogic.usePath("a?b") == nil && GalleryLogic.usePath("ä") == nil
          && GalleryLogic.usePath(String(repeating: "a", count: 65)) == nil, "ungueltige IDs nie im Pfad")
    func json(_ b: [String: Any]) -> String { String(data: try! JSONSerialization.data(withJSONObject: b, options: .sortedKeys), encoding: .utf8)! }
    check(json(GalleryLogic.useBody(parent: nil, examples: true)) == #"{"examples":true,"parent_id":null}"#, "Body oberste Ebene: \(json(GalleryLogic.useBody(parent: nil, examples: true)))")
    check(json(GalleryLogic.useBody(parent: 42, examples: false)) == #"{"examples":false,"parent_id":42}"#, "Body unter Seite ohne Beispiele")

    // MARK: Antwort Verwenden
    check(GalleryLogic.parseCreated(d(#"{"root":12,"created":[12,13,14],"warn":[]}"#)) == .init(root: 12, created: [12, 13, 14]), "Antwort gelesen")
    check(GalleryLogic.parseCreated(d(#"{"root":"7"}"#)) == .init(root: 7, created: []), "root als Text, created fehlt")
    check(GalleryLogic.parseCreated(d(#"{"root":1.5}"#)) == nil && GalleryLogic.parseCreated(d(#"{"root":true}"#)) == nil
          && GalleryLogic.parseCreated(d(#"{"created":[1]}"#)) == nil && GalleryLogic.parseCreated(d("")) == nil, "kaputte Antworten")
    check(GalleryLogic.parseCreated(d(#"{"root":5,"created":[5,"x",6.0]}"#))?.created == [5, 6], "created tolerant")

    // MARK: Fehler
    let err = d(#"{"error":"Unter föderiert geteilten Seiten nicht möglich"}"#)
    check(GalleryLogic.failure(code: 404, data: d(""), list: true) == .unsupported, "Liste 404: aelterer Server")
    check(GalleryLogic.failure(code: 404, data: d("<html>Not Found</html>"), list: false) == .gone, "Verwenden 404 ohne Text")
    check(GalleryLogic.failure(code: 404, data: d(#"{"error":"not found"}"#)) == .gone, "Verwenden 404 mit technischem Text (so antwortet der Server)")
    check(GalleryLogic.failure(code: 403, data: d(#"{"error":"forbidden"}"#)) == .forbidden, "technischer Kurztext -> eigener Text")
    check(GalleryLogic.failure(code: 404, data: d(#"{"error":"Elternseite fehlt"}"#), list: true) == .unsupported, "Liste 404 immer ausblenden")
    check(GalleryLogic.failure(code: 404, data: d(#"{"error":"Elternseite fehlt"}"#)) == .message("Elternseite fehlt"), "Verwenden 404 mit Text")
    check(GalleryLogic.failure(code: 400, data: err) == .message("Unter föderiert geteilten Seiten nicht möglich"), "400 mit Text")
    check(GalleryLogic.failure(code: 400, data: d("{}")) == .invalid && GalleryLogic.failure(code: 403, data: d(#"{"error":"  "}"#)) == .forbidden, "400/403 ohne Text")
    check(GalleryLogic.failure(code: 403, data: d(#"{"error":"Keine Rechte"}"#)) == .message("Keine Rechte"), "403 mit Text")
    check(GalleryLogic.failure(code: 429, data: d(#"{"error":"zu viele"}"#)) == .rateLimited, "429 eigene Meldung")
    check(GalleryLogic.failure(code: 500, data: d(#"{"error":"Traceback ..."}"#)) == .failed(500), "5xx: kein Server-Text")
    check(GalleryLogic.failure(code: 200, data: d("x")) == .badResponse, "2xx unlesbar")
    if case .message(let m) = GalleryLogic.failure(code: 400, data: d(#"{"error":"\#(String(repeating: "e", count: 999))"}"#)) { check(m.count == 300, "Text begrenzt") } else { check(false, "Text begrenzt") }

    // MARK: Angeboten?
    check(GalleryLogic.offered(code: 200) == true && GalleryLogic.offered(code: 404) == false && GalleryLogic.offered(code: 403) == false, "angeboten")
    check(GalleryLogic.offered(code: 500) == nil && GalleryLogic.offered(code: 0) == nil && GalleryLogic.offered(code: 429) == nil, "unklar: Stand behalten")
}
