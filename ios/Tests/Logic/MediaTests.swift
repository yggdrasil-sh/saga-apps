import Foundation

// 2.1: Bilder aus der Zwischenablage (Mac/iPhone/iPad) und Immich im Chat
func mediaTests() {
    typealias M = MediaLogic
    // Bildschirmfoto (Cmd-Shift-Ctrl-4): nur Bilddaten
    check(M.pasteKind(types: ["public.png", "Apple PNG pasteboard type"], files: [], text: nil) == .imageData("public.png"), "Bildschirmfoto -> PNG")
    check(M.pasteKind(types: ["public.tiff", "public.png"], files: [], text: nil) == .imageData("public.png"), "PNG vor TIFF")
    check(M.pasteKind(types: ["public.tiff"], files: [], text: nil) == .imageData("public.tiff"), "nur TIFF")
    check(M.pasteKind(types: ["public.heic"], files: [], text: "") == .imageData("public.heic"), "HEIC, leerer Text")
    // Text bleibt Text
    check(M.pasteKind(types: ["public.utf8-plain-text"], files: [], text: "Hallo") == .text, "Klartext")
    check(M.pasteKind(types: [], files: [], text: nil) == .text, "leer")
    check(M.pasteKind(types: ["public.png", "public.utf8-plain-text"], files: [], text: "Hallo Welt") == .text, "Bild + Text -> Text")
    check(M.pasteKind(types: ["public.tiff", "public.rtf", "public.utf8-plain-text"], files: [], text: "x") == .text, "formatierter Text mit Bild")
    check(M.pasteKind(types: ["public.tiff", "com.apple.flat-rtfd"], files: [], text: nil) == .text, "RTFD")
    check(M.pasteKind(types: ["public.png", "public.html"], files: [], text: nil) == .text, "HTML mit Bild")
    // Browser "Bild kopieren": Bild + Adresse
    check(M.pasteKind(types: ["public.tiff", "public.url", "public.utf8-plain-text"], files: [], text: "https://example.org/a.png") == .imageData("public.tiff"), "Bild + Adresse")
    check(M.isBareURL("https://example.org/x y") == false, "Adresse mit Leerzeichen ist Text")
    check(M.isBareURL(" https://example.org/x\n"), "Adresse mit Rand")
    // Finder: Dateien
    check(M.pasteKind(types: ["public.file-url", "public.utf8-plain-text", "public.tiff"], files: ["Foto.JPG"], text: "Foto.JPG") == .imageFiles([0]), "Finder-Bild")
    check(M.pasteKind(types: ["public.file-url"], files: ["a.pdf", "b.png", "c.heic"], text: nil) == .imageFiles([1, 2]), "nur Bilddateien")
    check(M.pasteKind(types: ["public.file-url", "public.tiff"], files: ["Bericht.pdf"], text: "Bericht.pdf") == .text, "keine Bilddatei -> Text (Dateiname)")
    check(M.isImageFile("x.TIFF") && M.isImageFile("a.b.webp") && !M.isImageFile("png") && !M.isImageFile("x.svg"), "Endungen")
    // Format erkennen
    check(M.sniff(Data([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A]))?.mime == "image/png", "PNG")
    check(M.sniff(Data([0xFF, 0xD8, 0xFF, 0xE0]))?.ext == "jpg", "JPEG")
    check(M.sniff(Data("GIF89a".utf8))?.mime == "image/gif", "GIF")
    check(M.sniff(Data("RIFF\u{0}\u{0}\u{0}\u{0}WEBPVP8 ".utf8))?.mime == "image/webp", "WebP")
    check(M.sniff(Data("MM\u{0}*".utf8)) == nil, "TIFF muss umgewandelt werden")
    check(M.rename("Bildschirmfoto 2026.tiff", ext: "png") == "Bildschirmfoto 2026.png", "Endung tauschen")
    check(M.rename("ohne", ext: "jpg") == "ohne.jpg" && M.rename(".x", ext: "png") == "bild.png", "Name ohne Endung")
    // Verkleinern
    check(M.shrinkFactor(bytes: 10, limit: 20) == 1, "klein genug")
    let f = M.shrinkFactor(bytes: 40_000_000, limit: 20_000_000)
    check(f < 0.71 && f > 0.5, "Faktor ~0.64: \(f)")
    check(M.shrinkFactor(bytes: 1_000_000_000, limit: 1_000_000) == 0.1, "Untergrenze")
    // Immich
    check(M.immichSizes(mime: "image/jpeg") == ["original", "preview"], "JPEG: Original zuerst")
    check(M.immichSizes(mime: "image/GIF") == ["original", "preview"], "GIF: Original")
    check(M.immichSizes(mime: "image/heic") == ["preview"], "HEIC: Vorschau")
    check(M.immichSizes(mime: "") == ["preview"], "unbekannt: Vorschau")
    check(M.immichFallback(status: 415) && M.immichFallback(status: 413) && M.immichFallback(status: 404), "Rueckfall")
    check(!M.immichFallback(status: 401) && !M.immichFallback(status: 500), "kein Rueckfall")
    check(M.immichName("IMG_0001.HEIC", ext: "jpg") == "IMG_0001.jpg" && M.immichName("", ext: "png") == "immich.png", "Immich-Name")
    check(M.immichName("a/b.png", ext: "png") == "a_b.png", "Schraegstrich")
}
