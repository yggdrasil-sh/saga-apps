import Foundation

// Reine Logik fuer Bilder aus der Zwischenablage und aus Immich (ohne AppKit/UIKit, mit Tests/Logic pruefbar).

enum MediaLogic {
    /// Was ein Einsetzen (Cmd-V, Bearbeiten -> Einsetzen) tun soll
    enum PasteKind: Equatable {
        case text                       // wie bisher: das Textfeld setzt selbst ein
        case imageFiles([Int])          // Indizes der Bilddateien (Finder: Datei kopiert)
        case imageData(String)          // Bilddaten dieses Typs (Bildschirmfoto, "Bild kopieren")
    }

    /// Bildtypen der Zwischenablage in Vorzugsreihenfolge (verlustfrei/klein zuerst)
    static let imageTypes = ["public.png", "public.jpeg", "com.compuserve.gif", "org.webmproject.webp",
                             "public.heic", "public.heif", "public.tiff", "com.microsoft.bmp"]
    /// Formatierter Text: dann ist das Bild nur Beiwerk (z. B. aus Pages/Word/Browser kopierter Absatz)
    static let richTextTypes = ["public.rtf", "public.html", "com.apple.flat-rtfd", "com.apple.rtfd", "com.apple.webarchive"]
    static let imageExtensions: Set<String> = ["png", "jpg", "jpeg", "gif", "webp", "heic", "heif", "tif", "tiff", "bmp"]

    static func isImageFile(_ name: String) -> Bool {
        guard let dot = name.lastIndex(of: ".") else { return false }
        return imageExtensions.contains(name[name.index(after: dot)...].lowercased())
    }

    /// Nur eine Adresse (Browser legt beim "Bild kopieren" oft die Bildadresse als Text dazu)
    static func isBareURL(_ s: String) -> Bool {
        let t = s.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty, !t.contains(where: { $0.isWhitespace }) else { return false }
        return t.hasPrefix("http://") || t.hasPrefix("https://") || t.hasPrefix("file://")
    }

    /// Entscheidung fuers Einsetzen. types: Typen der Zwischenablage, files: Namen kopierter Dateien, text: Klartext (falls vorhanden).
    /// Text bleibt Text: nur wenn wirklich ein Bild (und kein Text daneben) vorliegt, wird ein Bild eingesetzt.
    static func pasteKind(types: [String], files: [String], text: String?) -> PasteKind {
        if !files.isEmpty {
            let idx = files.indices.filter { isImageFile(files[$0]) }
            return idx.isEmpty ? .text : .imageFiles(idx)       // andere Dateien: wie bisher (Dateiname als Text)
        }
        guard let t = imageTypes.first(where: { types.contains($0) }) else { return .text }
        if types.contains(where: { richTextTypes.contains($0) }) { return .text }
        if let text, !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, !isBareURL(text) { return .text }
        return .imageData(t)
    }

    /// Bildformat an den ersten Bytes: (MIME, Endung) fuer PNG/JPEG/GIF/WebP - sonst nil (muss umgewandelt werden)
    static func sniff(_ d: Data) -> (mime: String, ext: String)? {
        let h = [UInt8](d.prefix(12))
        if h.starts(with: [0x89, 0x50, 0x4E, 0x47]) { return ("image/png", "png") }
        if h.starts(with: [0xFF, 0xD8]) { return ("image/jpeg", "jpg") }
        if h.starts(with: [0x47, 0x49, 0x46]) { return ("image/gif", "gif") }
        if h.count >= 12, h[0...3] == [0x52, 0x49, 0x46, 0x46], h[8...11] == [0x57, 0x45, 0x42, 0x50] { return ("image/webp", "webp") }
        return nil
    }

    /// Dateiname mit passender Endung (z. B. nach Umwandlung TIFF -> PNG)
    static func rename(_ name: String, ext: String) -> String {
        let base = name.lastIndex(of: ".").map { String(name[..<$0]) } ?? name
        return (base.isEmpty ? "bild" : base) + "." + ext
    }

    /// Verkleinerung, damit ein Bild unter die Grenze passt: Faktor fuer die lange Kante (1 = unveraendert)
    static func shrinkFactor(bytes: Int, limit: Int) -> Double {
        guard bytes > limit, limit > 0 else { return 1 }
        return max(0.1, min(0.9, (Double(limit) / Double(bytes)).squareRoot() * 0.9))
    }

    // MARK: Immich im Chat (GET /api/immich/thumb/{id}?size=original|preview)

    /// Reihenfolge der Abrufe: Original, wo der Server es liefern kann (PNG/JPEG/GIF/WebP), sonst gleich die grosse Vorschau
    static func immichSizes(mime: String) -> [String] {
        ["image/png", "image/jpeg", "image/gif", "image/webp"].contains(mime.lowercased()) ? ["original", "preview"] : ["preview"]
    }
    /// 404 (nicht verfuegbar), 413 (zu gross), 415 (Format): naechste Groesse versuchen
    static func immichFallback(status: Int) -> Bool { [404, 413, 415].contains(status) }

    /// Name fuer den Chat-Anhang aus dem Immich-Namen (Endung passend zum geladenen Format)
    static func immichName(_ name: String, ext: String) -> String {
        let clean = name.replacingOccurrences(of: "/", with: "_").trimmingCharacters(in: .whitespaces)
        return rename(clean.isEmpty ? "immich" : clean, ext: ext)
    }
}
