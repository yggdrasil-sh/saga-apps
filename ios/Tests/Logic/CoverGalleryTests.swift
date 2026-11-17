import Foundation

// 2.5 Titelbild-Galerie (App/CoverGalleryLogic.swift): Liste lesen (wie GET /api/covers in docs/APP-API.md, Daten frei
// erfunden), Gruppen, Auswahl mit empfohlener Position, Zufall, "Ueber dieses Bild", aeltere Server.
func coverGalleryTests() {
    typealias L = CoverGalleryLogic
    let json = #"""
    {"covers": [
      {"id": "balke-nordkap", "url": "/covers/balke-nordkap.jpg", "thumb": "/covers/balke-nordkap.jpg?w=384", "title": "Das Nordkap im Mondlicht",
       "artist": "Peder Balke", "date": "1848", "source_url": "https://www.metmuseum.org/art/collection/search/441379",
       "source": "The Metropolitan Museum of Art, Open Access (CC0 1.0)", "group": "nordisch", "pos": 55, "builtin": true},
      {"id": "saga-wolf", "url": "/covers/saga-wolf.jpg", "thumb": "https://evil.example/x.jpg", "title": " Trost beim Wolf ",
       "artist": "", "date": "", "source_url": "javascript:alert(1)", "source": "KI-generiert für Saga", "group": "saga-gefaehrten", "pos": 20.5, "builtin": true},
      {"id": "u-0123456789ab", "url": "/covers/u-0123456789ab.jpg", "title": "", "group": "eigene", "pos": 140, "builtin": false},
      {"id": "x", "url": "/files/abc", "title": "fremd"},
      {"id": "y", "url": "/covers/../etc.jpg", "title": "boese"},
      {"id": "balke-nordkap", "url": "/covers/balke-nordkap.jpg", "title": "doppelt"},
      {"id": "ohne", "url": "/covers/ohne.jpg", "title": "Ohne Gruppe", "pos": true, "builtin": true}
    ],
     "groups": [{"id": "nordisch", "label": "Nordisch"}, {"id": "romantik", "label": "Romantik"},
                {"id": "saga-gefaehrten", "label": "Saga · Gefährten"}, {"id": "", "label": "Weitere"}],
     "license": "CC0-1.0", "source": "The Metropolitan Museum of Art, Open Access (CC0 1.0)", "random_cover": true, "neu": 1}
    """#
    guard let l = L.parse(Data(json.utf8)) else { check(false, "Galerie: Liste lesbar"); return }

    // MARK: Lesen
    check(l.covers.map(\.id) == ["balke-nordkap", "saga-wolf", "u-0123456789ab", "ohne"], "Galerie: nur /covers/<id>.jpg, doppelte einmal")
    let b = l.covers[0]
    check(b.thumb == "/covers/balke-nordkap.jpg?w=384" && b.pos == 55 && b.builtin && b.group == "nordisch", "Galerie: Eintrag gelesen")
    check(b.credit == "Peder Balke, 1848" && b.label == "Das Nordkap im Mondlicht – Peder Balke, 1848", "Galerie: Nachweis wie im Web")
    check(b.sourceURL.hasPrefix("https://www.metmuseum.org/"), "Galerie: Quelle https")
    let w = l.covers[1]
    check(w.thumb == "/covers/saga-wolf.jpg?w=384", "Galerie: fremde Vorschauadresse -> eigene ?w=384")
    check(w.title == "Trost beim Wolf" && w.credit.isEmpty && w.label == "Trost beim Wolf", "Galerie: ohne Kuenstler nur Titel")
    check(w.sourceURL.isEmpty && w.source == "KI-generiert für Saga", "Galerie: javascript:-Quelle verworfen, Nachweis bleibt")
    check(w.pos == 20.5, "Galerie: Position mit Nachkomma")
    let u = l.covers[2]
    check(u.title == "u-0123456789ab" && u.pos == 100 && !u.builtin && u.thumb == "/covers/u-0123456789ab.jpg?w=384", "Galerie: Upload ohne Titel, Position gedeckelt")
    check(l.covers[3].pos == 50, "Galerie: Bool ist keine Position")
    check(L.pos(1) == 1 && L.pos(NSNumber(value: 0)) == 0 && L.pos("30") == 50 && L.pos(Double.nan) == 50, "Galerie: Position 1/0 bleibt, Text/NaN -> 50")
    check(l.source.hasPrefix("The Metropolitan") && l.license == "CC0-1.0", "Galerie: Nachweis der Liste")
    check(L.parse(Data("[]".utf8)) == nil && L.parse(Data("{}".utf8)) == nil && L.parse(Data("kaputt".utf8)) == nil, "Galerie: unlesbar -> nil")
    check(L.parse(Data(#"{"covers": []}"#.utf8))?.covers.isEmpty == true, "Galerie: leer ist lesbar")

    // MARK: Pfade
    check(L.isGalleryPath("/covers/hokusai-welle.jpg") && L.isGalleryPath("/covers/u-0123456789ab.jpg"), "Pfad: Galerie")
    check(!L.isGalleryPath("/files/abc") && !L.isGalleryPath("/covers/Gross.jpg") && !L.isGalleryPath("/covers/-a.jpg")
          && !L.isGalleryPath("/covers/a.jpg?w=384") && !L.isGalleryPath("/covers/.jpg") && !L.isGalleryPath(nil)
          && !L.isGalleryPath("/covers/" + String(repeating: "a", count: 65) + ".jpg"), "Pfad: keine Galerie")
    check(L.offered(code: 200) == true && L.offered(code: 404) == false && L.offered(code: 403) == false && L.offered(code: 500) == nil
          && L.offered(code: 0) == nil, "Galerie: aelterer Server 404 -> ausblenden, 5xx/offline -> spaeter wieder")

    // MARK: Gruppen
    let g = L.grouped(l, remote: false, other: "Weitere")
    check(g.map(\.id) == ["nordisch", "saga-gefaehrten", "eigene", ""], "Gruppen: Server-Reihenfolge, leere weg, unbekannte dahinter, ohne Gruppe zuletzt")
    check(g.map(\.label) == ["Nordisch", "Saga · Gefährten", "eigene", "Weitere"], "Gruppen: Namen")
    check(g[0].covers.map(\.id) == ["balke-nordkap"] && g[3].covers.map(\.id) == ["ohne"], "Gruppen: Bilder je Gruppe")
    let gr = L.grouped(l, remote: true, other: "Weitere")
    check(!gr.contains { $0.id == "eigene" } && gr.flatMap(\.covers).allSatisfy(\.builtin), "Gruppen: fremde Instanz nur mitgelieferte")
    let noLabel = L.parse(Data(#"{"covers": [{"url": "/covers/a.jpg", "group": ""}], "groups": []}"#.utf8))!
    check(L.grouped(noLabel, remote: false, other: "Weitere").map(\.label) == ["Weitere"], "Gruppen: ohne Gruppe und ohne Namen -> Weitere")

    // MARK: Setzen
    let f = L.fields(b, current: nil)
    check(f?["cover"] as? String == "/covers/balke-nordkap.jpg" && f?["cover_pos"] as? Double == 55, "Setzen: Pfad + empfohlene Position")
    check(L.fields(b, current: "/files/abc") != nil, "Setzen: eigenes Bild ersetzen")
    check(L.fields(b, current: "/covers/balke-nordkap.jpg") == nil, "Setzen: schon gesetzt -> nicht speichern")

    // MARK: Zufall
    check(L.random(l, remote: false, current: "/covers/balke-nordkap.jpg", roll: { _ in 0 })?.id == "saga-wolf", "Zufall: nicht das jetzige")
    check(L.random(l, remote: false, current: nil, roll: { $0 + 5 })?.id == "ohne", "Zufall: Index gedeckelt")
    check(L.random(l, remote: true, current: nil, roll: { n in n - 1 })?.builtin == true, "Zufall: fremde Instanz nur mitgelieferte")
    let one = L.parse(Data(#"{"covers": [{"url": "/covers/a.jpg"}]}"#.utf8))!
    check(L.random(one, remote: false, current: "/covers/a.jpg") == nil, "Zufall: nur das jetzige -> nichts")

    // MARK: Ueber dieses Bild
    check(L.about(l, current: "/covers/saga-wolf.jpg") == .cover(w), "Ueber: Eintrag")
    check(L.about(l, current: "/covers/versteckt.jpg") == .gone, "Ueber: ausgeblendet -> nicht mehr zur Auswahl")
    check(L.about(nil, current: "/covers/saga-wolf.jpg") == .gone, "Ueber: Liste fehlt -> Hinweis")
    check(L.about(l, current: "/files/abc") == .none && L.about(l, current: nil) == .none, "Ueber: kein Galerie-Bild -> nicht anbieten")
}
