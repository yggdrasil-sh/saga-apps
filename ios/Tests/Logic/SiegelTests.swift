import Foundation

// 2.5 Saga-Siegel als Seitensymbol (App/SiegelLogic.swift): Pfade wie der Server (saga/siegel.py PATH_RE), Anzeige als Bild
// (FedLogic.isImagePath, Unterseiten-Widget), Liste GET /api/siegel (Daten wie docs/APP-API.md, gekuerzt), Auswahl.
func siegelTests() {
    typealias S = SiegelLogic

    // MARK: Pfade
    check(S.parse("/siegel/weinrot/rabe.svg").map { $0.color + "|" + $0.motif } == "weinrot|rabe", "Siegel: Pfad gelesen")
    check(S.isPath("/siegel/smaragd/yggdrasil.svg") && S.isPath("/siegel/elfenbein/teetasse.svg"), "Siegel: alle drei Farben")
    for bad in ["/siegel/blau/rabe.svg", "/siegel/weinrot/rabe.png", "/siegel/weinrot/rabe.svg?w=64", "/siegel/weinrot/Rabe.svg",
                "/siegel/weinrot/r.svg", "/siegel/weinrot/../rabe.svg", "/siegel/weinrot/rabe/x.svg", "/siegel/weinrot.svg",
                " /siegel/weinrot/rabe.svg", "/files/abc", "", "🐉", "/siegel/weinrot/rabe2.svg", "/siegel//rabe.svg"] {
        check(!S.isPath(bad), "Siegel: kein gueltiger Pfad: \(bad)")
    }
    check(S.path(color: "smaragd", motif: "rabe") == "/siegel/smaragd/rabe.svg", "Siegel: Pfad bauen")

    // MARK: Anzeige als Bild (mit ?w= -> PNG)
    check(FedLogic.isImagePath("/siegel/weinrot/rabe.svg"), "Siegel: als Bild laden")
    check(FedLogic.isImagePath("/siegel/purpur/neues-motiv2.svg"), "Siegel: spaeteres Motiv/Farbe trotzdem als Bild, nicht als Text")
    check(FedLogic.isImagePath("/files/x.png") && FedLogic.isImagePath("/api/fed/files/-2049/abc"), "Siegel: eigene/fremde Bilder unveraendert")
    for bad in ["/siegel/weinrot/rabe.svg?w=64", "/siegel/../files/x.svg", "/siegel/a/b/c.svg", "/siegel/weinrot/rabe.png",
                "/siegel/a b/c.svg", "/siegel/weinrot/\\rabe.svg", "/siegel/weinrot/.svg", "https://x/siegel/weinrot/rabe.svg"] {
        check(!S.isDisplayPath(bad), "Siegel: nicht als Bild: \(bad)")
    }
    check(!FedLogic.isImagePath("🐉") && !FedLogic.isImagePath("/siegel/"), "Siegel: Emoji/alter gekuerzter Wert kein Bild")

    // Text-Stellen (Chat-Verweise): Bilder nie als Pfad-Text
    check(S.textSymbol("🐉") == "🐉" && S.textSymbol("🔒") == "🔒", "Siegel: Emoji bleibt Text")
    check(S.textSymbol("/siegel/weinrot/rabe.svg") == nil && S.textSymbol("/files/a") == nil && S.textSymbol("") == nil
          && S.textSymbol(nil) == nil && S.textSymbol("  ") == nil, "Siegel: Bildpfad/leer -> Standardsymbol")

    // Unterseiten-Widget: Siegel als Symbol erlaubt, Titelbild-Galerie weiter nicht
    let sub = WidgetLogic.subpages(["items": [["id": 1, "title": "A", "icon": "/siegel/smaragd/rabe.svg"],
                                              ["id": 2, "title": "B", "icon": "/siegel/weinrot/rabe.svg?w=64"],
                                              ["id": 3, "title": "C", "icon": "/covers/x.jpg"],
                                              ["id": 4, "title": "D", "icon": "/files/abc"]]])
    check(sub?.items.map(\.icon) == ["/siegel/smaragd/rabe.svg", "", "", "/files/abc"], "Siegel: Unterseiten-Widget zeigt Siegel")

    // MARK: Liste GET /api/siegel
    let json = #"""
    {"colors": [{"id": "weinrot", "label": "Weinrot"}, {"id": "blau", "label": "Blau"}, {"id": "smaragd", "label": " Smaragd "},
                {"id": "weinrot", "label": "doppelt"}],
     "motifs": [{"id": "yggdrasil", "label": "Yggdrasil"}, {"id": "rabe", "label": "Raven"}, {"id": "Rabe", "label": "gross"},
                {"id": "../x", "label": "boese"}, {"id": "rabe", "label": "doppelt"}, {"id": "teetasse", "label": ""}, {"id": 7}],
     "path": "/siegel/{color}/{motif}.svg", "sizes": [64, 128, 256], "neu": true}
    """#
    guard let l = S.parse(listing: Data(json.utf8)) else { check(false, "Siegel: Liste lesbar"); return }
    check(l.colors.map(\.id) == ["weinrot", "smaragd", "elfenbein"], "Siegel: nur bekannte Farben, fehlende hinten")
    check(l.colors.map(\.label) == ["Weinrot", "Smaragd", "Elfenbein"], "Siegel: Farbnamen (getrimmt, Ersatzname)")
    check(l.motifs.map(\.id) == ["yggdrasil", "rabe", "teetasse"], "Siegel: Motive in Reihenfolge des Servers, ungueltige/doppelte weg")
    check(l.motifs.map(\.label) == ["Yggdrasil", "Raven", "Teetasse"], "Siegel: Motivnamen in der Sprache des Servers, leer -> Ersatz")
    check(S.parse(listing: Data("{}".utf8)) == nil && S.parse(listing: Data("<html>".utf8)) == nil
          && S.parse(listing: Data(#"{"motifs": []}"#.utf8)) == nil, "Siegel: unlesbare/leere Liste")
    check(S.offered(code: 200) == true && S.offered(code: 404) == false && S.offered(code: 403) == false
          && S.offered(code: 401) == false && S.offered(code: 502) == nil, "Siegel: Antwort einordnen")

    // MARK: Auswahl
    check(S.offered(own: ["siegel1"], remote: false, remoteCaps: []), "Siegel: eigener Server mit siegel1")
    check(!S.offered(own: [], remote: false, remoteCaps: ["siegel1"]), "Siegel: eigener Server ohne siegel1 -> keine Auswahl")
    check(!S.offered(own: ["siegel1"], remote: true, remoteCaps: ["db"]), "Siegel: fremde Seite, deren Server ohne siegel1 (409)")
    check(S.offered(own: ["siegel1"], remote: true, remoteCaps: ["db", "siegel1"]), "Siegel: fremde Seite mit siegel1")
    check(S.initialColor(current: "/siegel/elfenbein/rabe.svg", remembered: "smaragd") == "elfenbein", "Siegel: Farbe des aktuellen Siegels")
    check(S.initialColor(current: "🐉", remembered: "smaragd") == "smaragd", "Siegel: zuletzt gewaehlte Farbe")
    check(S.initialColor(current: nil, remembered: "blau") == "weinrot" && S.initialColor(current: nil, remembered: nil) == "weinrot",
          "Siegel: sonst die erste Farbe")
    check(S.name(color: "weinrot", motif: "rabe", in: l) == "Raven (Weinrot)", "Siegel: Name wie im Web")
    check(S.name(color: "elfenbein", motif: "katze", in: nil) == "Katze (Elfenbein)", "Siegel: Name ohne Liste")
    check(S.name(of: "/siegel/smaragd/yggdrasil.svg", in: l) == "Yggdrasil (Smaragd)" && S.name(of: "🐉", in: l) == nil, "Siegel: Name eines Symbols")
    check((S.fields(color: "smaragd", motif: "rabe", current: "🐉")?["icon"] as? String) == "/siegel/smaragd/rabe.svg", "Siegel: PATCH icon")
    check(S.fields(color: "smaragd", motif: "rabe", current: "/siegel/smaragd/rabe.svg") == nil, "Siegel: gleiches Siegel nicht speichern")
}
