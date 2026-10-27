import Foundation

// 2.5 Lesezeichen-Favicons: Rueckfall auf dem Geraet (App/FaviconLogic.swift) - Reihenfolge, URL-Pruefung, Limits, Cache
func faviconTests() {
    typealias L = FaviconLogic
    let bm = "https://shop.example.org/start?x=1"
    let direct = ["https://shop.example.org/apple-touch-icon.png", "https://shop.example.org/favicon.ico"]
    // Serverpfad wie bisher
    check(L.serverPath(iconURL: "/api/services/favicon/k1?h=ab", icon: nil) == "/api/services/favicon/k1?h=ab", "icon_url zuerst")
    check(L.serverPath(iconURL: "https://fremd.example/x.png", icon: "nextcloud") == "/api/services/icon/nextcloud", "fremde icon_url nie, festes Symbol")
    check(L.serverPath(iconURL: nil, icon: "") == nil, "nichts -> Buchstabe")
    // Kandidaten
    let c = L.directCandidates(direct, bookmarkURL: bm)
    check(c.map(\.absoluteString) == direct, "Reihenfolge wie geliefert")
    check(L.directCandidates(nil, bookmarkURL: bm).isEmpty, "aelterer Server ohne icon_direct: wie bisher")
    check(L.directCandidates([], bookmarkURL: bm).isEmpty, "leere Liste")
    check(L.directCandidates(["https://andere.example.org/favicon.ico"], bookmarkURL: bm).isEmpty, "anderer Host nie")
    check(L.directCandidates(["https://SHOP.example.org/favicon.ico"], bookmarkURL: bm).count == 1, "Host ohne Gross/Klein")
    check(L.directCandidates(["http://shop.example.org/favicon.ico"], bookmarkURL: bm).isEmpty, "https-Lesezeichen: kein http")
    check(L.directCandidates(["http://shop.example.org/favicon.ico", "https://shop.example.org/favicon.ico"], bookmarkURL: "http://shop.example.org/").count == 2, "http-Lesezeichen: http und https")
    check(L.directCandidates(["ftp://shop.example.org/favicon.ico", "data:image/png;base64,AAAA", "javascript:alert(1)", "/favicon.ico"], bookmarkURL: bm).isEmpty, "nur absolute http/https")
    check(L.directCandidates(["https://u:p@shop.example.org/favicon.ico"], bookmarkURL: bm).isEmpty, "keine Anmeldedaten in der Adresse")
    check(L.directCandidates(["https://shop.example.org:8443/favicon.ico"], bookmarkURL: bm).isEmpty, "anderer Port nie")
    check(L.directCandidates(["https://shop.example.org:443/favicon.ico"], bookmarkURL: bm).count == 1, "Standardport gleich")
    check(L.directCandidates(direct, bookmarkURL: "mailto:a@example.org").isEmpty, "Lesezeichen ohne http(s)")
    let many = (1...5).map { "https://shop.example.org/i\($0).png" }
    check(L.directCandidates(many, bookmarkURL: bm).count == 3, "hoechstens 3")
    check(L.directCandidates([direct[1], direct[1]], bookmarkURL: bm).count == 1, "ohne Doppelte")
    // Plan + Reihenfolge
    let p = L.plan(serverPath: "/api/services/favicon/k", direct: c)
    check(p == [.server("/api/services/favicon/k"), .direct(c[0]), .direct(c[1])], "erst Server, dann direkt der Reihe nach")
    check(L.plan(serverPath: nil, direct: []).isEmpty, "Dienst ohne Symbol: Buchstabe")
    var tried: [L.Source] = []
    let r = L.first(p) { s in tried.append(s); return s == .direct(c[1]) ? Data([1]) : nil }
    check(r?.0 == .direct(c[1]) && tried.count == 3, "Server 503 + touch-icon 404 -> favicon.ico")
    tried = []
    _ = L.first(p) { s in tried.append(s); return Data([1]) }
    check(tried == [p[0]], "Server klappt -> kein Geraete-Abruf")
    check(L.first(p) { _ in nil } == nil, "alles scheitert -> Buchstabe")
    // Rasterbilder
    let png = Data([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]), ico = Data([0, 0, 1, 0, 1, 0]), jpg = Data([0xFF, 0xD8, 0xFF, 0xE0])
    let gif = Data("GIF89a".utf8), webp = Data("RIFF\u{0}\u{0}\u{0}\u{0}WEBPVP8 ".utf8)
    check([png, ico, jpg, gif, webp].allSatisfy(L.isRaster), "PNG/ICO/JPEG/GIF/WebP")
    check(!L.isRaster(Data("<svg xmlns=\"http://www.w3.org/2000/svg\"/>".utf8)), "SVG nie")
    check(!L.isRaster(Data("<!doctype html>".utf8)), "HTML (Bot-Schutzseite) nie")
    check(!L.isRaster(Data("RIFF\u{0}\u{0}\u{0}\u{0}WAVE".utf8)), "RIFF ohne WEBP nie")
    check(!L.isRaster(Data([0x89])), "zu kurz")
    // Annahme / Limits
    check(L.accept(status: 200, contentType: "image/x-icon", declaredLength: 6, data: ico), "ICO ok")
    check(L.accept(status: 200, contentType: "application/octet-stream", declaredLength: nil, data: png), "falscher Typ, echtes PNG ok")
    check(!L.accept(status: 503, contentType: "image/png", declaredLength: nil, data: png), "nur 200")
    check(!L.accept(status: 200, contentType: "image/svg+xml", declaredLength: nil, data: png), "SVG-Typ nie")
    check(!L.accept(status: 200, contentType: "text/html", declaredLength: nil, data: png), "text/* nie")
    let big = png + Data(count: L.maxBytes)
    check(!L.accept(status: 200, contentType: "image/png", declaredLength: nil, data: big), "ueber 512 KB nie")
    check(L.accept(status: 200, contentType: "image/png", declaredLength: nil, data: png + Data(count: L.maxBytes - png.count)), "genau 512 KB ok")
    check(!L.accept(status: 200, contentType: "image/png", declaredLength: Int64(L.maxBytes) + 1, data: png), "angekuendigt zu gross")
    check(L.rejectEarly(status: 200, contentType: "image/png", declaredLength: 10_000_000), "frueh abbrechen: zu gross")
    check(L.rejectEarly(status: 404, contentType: "image/png", declaredLength: nil), "frueh abbrechen: 404")
    check(!L.rejectEarly(status: 200, contentType: nil, declaredLength: nil), "ohne Kopfzeilen weiterlesen")
    check(L.timeout <= 5 && L.maxBytes == 524_288, "Limits laut Vertrag")
    // Cache
    let t0 = Date(timeIntervalSince1970: 1_800_000_000)
    check(L.cacheDecision(ok: true, savedAt: t0, now: t0.addingTimeInterval(6 * 86_400)) == .use, "Treffer 6 Tage alt: nutzen")
    check(L.cacheDecision(ok: true, savedAt: t0, now: t0.addingTimeInterval(7 * 86_400 + 1)) == .reload, "nach 7 Tagen neu")
    check(L.cacheDecision(ok: false, savedAt: t0, now: t0.addingTimeInterval(59 * 60)) == .knownMissing, "Fehlversuch < 1 h: nicht erneut")
    check(L.cacheDecision(ok: false, savedAt: t0, now: t0.addingTimeInterval(3_601)) == .reload, "Fehlversuch nach 1 h erneut")
    check(L.cacheDecision(ok: true, savedAt: t0, now: t0.addingTimeInterval(-10)) == .reload, "Uhr verstellt: neu")
    check(L.cacheKey(c) == L.cacheKey(c) && L.cacheKey(c) != L.cacheKey(Array(c.reversed())) && L.cacheKey(c).count == 16, "stabiler Schluessel")
}
