import Foundation

// 2.5: SVG-Bilder (Trilium-Import) und Seitenlinks auf Dateien (/files/<id>) - gleiche Faelle wie SvgLogicTest.kt
func svgTests() {
    func d(_ s: String) -> Data { Data(s.utf8) }
    check(SvgLogic.isSvg(d("<svg xmlns=\"http://www.w3.org/2000/svg\"/>")), "svg erkannt")
    check(SvgLogic.isSvg(d("\u{feff}<?xml version=\"1.0\"?>\n<!-- x -->\n<!DOCTYPE svg>\n<SVG width=\"1\">")), "BOM, xml, Kommentar, DOCTYPE")
    check(!SvgLogic.isSvg(d("<html><svg></svg></html>")), "HTML mit svg: nein")
    check(!SvgLogic.isSvg(d("<svgx>")), "svgx: nein")
    check(!SvgLogic.isSvg(Data([0x89, 0x50, 0x4e, 0x47])) && !SvgLogic.isSvg(Data()), "PNG/leer: nein")
    check(SvgLogic.ratio(d("<svg width=\"200\" height=\"100\">")) == 2, "width/height")
    check(SvgLogic.ratio(d("<svg width='300px' height='150px'>")) == 2, "px")
    check(SvgLogic.ratio(d("<svg stroke-width=\"5\" viewBox=\"0 0 40 80\">")) == 0.5, "viewBox (stroke-width zaehlt nicht)")
    check(SvgLogic.ratio(d("<svg width=\"100%\" height=\"100%\" viewBox=\"0,0,30,10\">")) == 3, "Prozent -> viewBox")
    check(SvgLogic.ratio(d("<svg>")) == nil && SvgLogic.ratio(d("<svg width=\"0\" height=\"5\">")) == nil, "unbekannt")
    check(SvgLogic.ratio(d("<svg width=\"10000\" height=\"1\">")) == 20, "begrenzt")
    let h = SvgLogic.html(d("<svg/>"))
    check(h.contains("default-src 'none'; img-src data:") && h.contains("<img alt=\"\" src=\"data:image/svg+xml;base64,PHN2Zy8+\">"), "nur data:-img mit CSP")
    check(!h.lowercased().contains("<script") && !h.contains("<svg"), "kein Skript, SVG nicht inline")
    check(SvgLogic.html(d("<svg/>"), cover: true).contains("object-fit:cover"), "Titelbild: cover")
    check(SvgLogic.allowNavigation(URL(string: "about:blank")) && !SvgLogic.allowNavigation(URL(string: "https://example.com/x")), "nur about:blank")
    check(!SvgLogic.allowNavigation(URL(string: "data:text/html,x")), "keine data:-Navigation")

    let srv = URL(string: "https://saga.example.org/")!
    let id = "0123456789abcdef01234567"
    check(LinkPolicy.fileLink("/files/\(id)", server: srv)?.absoluteString == "https://saga.example.org/files/\(id)", "/files/<id> -> eigener Server")
    check(LinkPolicy.fileLink("/files/\(id.uppercased())", server: srv)?.absoluteString == "https://saga.example.org/files/\(id)", "Grossbuchstaben")
    check(LinkPolicy.fileLink("/files/\(id)x", server: srv) == nil && LinkPolicy.fileLink("/files/123", server: srv) == nil, "falsche Laenge")
    check(LinkPolicy.fileLink("/files/\(id)/../x", server: srv) == nil && LinkPolicy.fileLink("//evil/files/\(id)", server: srv) == nil, "nur genau diese Form")
    check(LinkPolicy.fileHref(URL(string: "https://saga.example.org/files/\(id)")!, server: srv) == "/files/\(id)", "Rueckweg beim Speichern")
    check(LinkPolicy.fileHref(URL(string: "https://other.example.org/files/\(id)")!, server: srv) == nil, "fremder Server bleibt absolut")
}
