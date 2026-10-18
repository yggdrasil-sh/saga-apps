import Foundation
import ImageIO
import CoreGraphics
import UniformTypeIdentifiers

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

    // Bild fuer Titelbild/Symbol vorbereiten
    func img(_ w: Int, _ h: Int, _ type: UTType, alpha: Bool = false, orientation: Int = 1) -> Data {
        let ctx = CGContext(data: nil, width: w, height: h, bitsPerComponent: 8, bytesPerRow: 0, space: CGColorSpaceCreateDeviceRGB(),
                            bitmapInfo: (alpha ? CGImageAlphaInfo.premultipliedLast : .noneSkipLast).rawValue)!
        ctx.setFillColor(CGColor(red: 0.2, green: 0.5, blue: 0.9, alpha: alpha ? 0.5 : 1)); ctx.fill(CGRect(x: 0, y: 0, width: w / 2, height: h))
        let out = NSMutableData(); let d = CGImageDestinationCreateWithData(out, type.identifier as CFString, 1, nil)!
        CGImageDestinationAddImage(d, ctx.makeImage()!, [kCGImagePropertyOrientation: orientation] as CFDictionary); CGImageDestinationFinalize(d)
        return out as Data
    }
    func info(_ d: Data) -> (type: String, w: Int, h: Int) {
        let s = CGImageSourceCreateWithData(d as CFData, nil)!
        let p = CGImageSourceCopyPropertiesAtIndex(s, 0, nil) as! [CFString: Any]
        return (CGImageSourceGetType(s)! as String, p[kCGImagePropertyPixelWidth] as! Int, p[kCGImagePropertyPixelHeight] as! Int)
    }
    let big = img(4000, 3000, .jpeg)
    let bigOut = HeaderImage.prepare(big, maxPixel: HeaderImage.coverMaxPixel)!
    check(info(bigOut).w == 3000 && info(bigOut).h == 2250 && info(bigOut).type == UTType.jpeg.identifier, "grosses Foto -> 3000 px JPEG: \(info(bigOut))")
    let small = img(800, 600, .jpeg)
    check(HeaderImage.prepare(small, maxPixel: HeaderImage.coverMaxPixel) == small, "passendes JPEG unveraendert")
    let heic = img(3000, 2000, .heic)
    let heicOut = HeaderImage.prepare(heic, maxPixel: HeaderImage.coverMaxPixel)!
    check(info(heicOut).type == UTType.jpeg.identifier && info(heicOut).w == 3000, "HEIC -> JPEG (Server nimmt kein HEIC): \(info(heicOut))")
    let rotated = img(300, 200, .jpeg, orientation: 6)
    let rotOut = HeaderImage.prepare(rotated, maxPixel: HeaderImage.coverMaxPixel)!
    check(info(rotOut).w == 200 && info(rotOut).h == 300, "EXIF-Drehung eingerechnet (Server dreht nicht): \(info(rotOut))")
    let png = img(2000, 2000, .png, alpha: true)
    let pngOut = HeaderImage.prepare(png, maxPixel: HeaderImage.iconMaxPixel)!
    check(info(pngOut).type == UTType.png.identifier && info(pngOut).w == 640, "Symbol mit Transparenz -> PNG 640: \(info(pngOut))")
    let gif = img(1200, 1200, .gif)
    check(HeaderImage.prepare(gif, maxPixel: HeaderImage.iconMaxPixel) == gif, "GIF unveraendert (Animation)")
    check(HeaderImage.prepare(Data("kein Bild".utf8), maxPixel: 100) == nil, "kein Bild -> nil")
}
