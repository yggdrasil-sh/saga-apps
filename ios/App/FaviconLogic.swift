import Foundation

/// Saga 2.5: Favicons nur fuer Lesezeichen, Rueckfall auf dem Geraet. Reihenfolge: erst `icon_url` ueber den eigenen
/// Server; klappt das nicht, der Reihe nach `icon_direct` (der Server liefert es nur bei Lesezeichen) direkt von der
/// Seite wie ein Browser-Tab - mit Zeitlimit, Groessenlimit, nur Rasterbildern, ohne Cookies/Anmeldedaten; sonst
/// Anfangsbuchstabe. Fehlt `icon_direct` (aelterer Server, Dienste), bleibt alles wie bisher.
/// Reine Logik ohne UI und Netz (Logic-Runner: Tests/Logic/FaviconTests.swift).
enum FaviconLogic {
    static let timeout: TimeInterval = 5                 // je direktem Abruf
    static let maxBytes = 512 * 1024
    static let maxDirect = 3
    static let okTTL: TimeInterval = 7 * 86_400          // geladenes Favicon 7 Tage merken
    static let failTTL: TimeInterval = 3_600             // Fehlversuch 1 Stunde merken

    enum Source: Equatable { case server(String), direct(URL) }

    /// Serverpfad wie bisher: `icon_url` (nur eigener Server, beginnt mit /api/), sonst festes Symbol (aeltere Server).
    static func serverPath(iconURL: String?, icon: String?) -> String? {
        if let u = iconURL, u.hasPrefix("/api/") { return u }
        if let i = icon, !i.isEmpty { return "/api/services/icon/" + i }
        return nil
    }

    /// Erlaubte direkte Adressen: absolut, nur http/https (bei einem https-Lesezeichen nur https), Host und Port wie das
    /// Lesezeichen, keine Anmeldedaten in der Adresse, ohne Doppelte, hoechstens 3. Fehlt die Liste: leer (wie bisher).
    static func directCandidates(_ list: [String]?, bookmarkURL: String) -> [URL] {
        guard let list, !list.isEmpty, let b = URLComponents(string: bookmarkURL.trimmingCharacters(in: .whitespaces)),
              let bs = b.scheme?.lowercased(), bs == "http" || bs == "https", let bh = b.host?.lowercased(), !bh.isEmpty
        else { return [] }
        var out: [URL] = []
        for raw in list.prefix(maxDirect) {
            guard let c = URLComponents(string: raw), let s = c.scheme?.lowercased(), s == "http" || s == "https",
                  !(bs == "https" && s == "http"), c.host?.lowercased() == bh, c.user == nil, c.password == nil,
                  samePort(c, b), let u = c.url, !out.contains(u) else { continue }
            out.append(u)
        }
        return out
    }
    private static func effectivePort(_ c: URLComponents) -> Int {
        c.port ?? (c.scheme?.lowercased() == "https" ? 443 : 80)
    }
    private static func standardPort(_ c: URLComponents) -> Int { c.scheme?.lowercased() == "https" ? 443 : 80 }
    /// gleicher Port - oder beide auf dem Standardport ihres Schemas (http-Lesezeichen, Favicon per https)
    private static func samePort(_ a: URLComponents, _ b: URLComponents) -> Bool {
        effectivePort(a) == effectivePort(b) || (effectivePort(a) == standardPort(a) && effectivePort(b) == standardPort(b))
    }

    /// Reihenfolge der Versuche
    static func plan(serverPath: String?, direct: [URL]) -> [Source] {
        (serverPath.map { [Source.server($0)] } ?? []) + direct.map { .direct($0) }
    }

    /// Erstes Ergebnis in Planreihenfolge, das `fetch` liefert (synchron fuer Tests; die Ansicht geht dieselbe Reihe async).
    static func first(_ plan: [Source], fetch: (Source) -> Data?) -> (Source, Data)? {
        for s in plan { if let d = fetch(s) { return (s, d) } }
        return nil
    }

    /// Nur Rasterbilder nach Dateianfang: PNG, ICO, JPEG, GIF, WebP. SVG/HTML/sonstiges nie.
    static func isRaster(_ d: Data) -> Bool {
        let b = [UInt8](d.prefix(12))
        guard b.count >= 4 else { return false }
        if b.starts(with: [0x89, 0x50, 0x4E, 0x47]) { return true }                       // PNG
        if b.starts(with: [0x00, 0x00, 0x01, 0x00]) { return true }                       // ICO
        if b.starts(with: [0xFF, 0xD8, 0xFF]) { return true }                             // JPEG
        if b.starts(with: Array("GIF8".utf8)) { return true }                             // GIF
        if b.count >= 12, b.starts(with: Array("RIFF".utf8)), Array(b[8..<12]) == Array("WEBP".utf8) { return true }
        return false
    }

    /// Antwort eines direkten Abrufs annehmen? 200, angekuendigte und echte Groesse im Limit, kein SVG, Rasterbild.
    static func accept(status: Int, contentType: String?, declaredLength: Int64?, data: Data) -> Bool {
        guard status == 200, !data.isEmpty, data.count <= maxBytes else { return false }
        if let l = declaredLength, l > Int64(maxBytes) { return false }
        let ct = (contentType ?? "").lowercased()
        if ct.contains("svg") || ct.hasPrefix("text/") { return false }
        return isRaster(data)
    }
    /// Schon die Kopfzeilen verraten Uebergroesse/SVG -> Abruf gleich abbrechen
    static func rejectEarly(status: Int, contentType: String?, declaredLength: Int64?) -> Bool {
        if status != 200 { return true }
        if let l = declaredLength, l > Int64(maxBytes) { return true }
        let ct = (contentType ?? "").lowercased()
        return ct.contains("svg") || ct.hasPrefix("text/")
    }

    enum CacheDecision: Equatable { case use, knownMissing, reload }
    /// Zwischenspeicher: Treffer 7 Tage, Fehlversuch 1 Stunde; Zeit in der Zukunft (Uhr verstellt) -> neu laden
    static func cacheDecision(ok: Bool, savedAt: Date, now: Date) -> CacheDecision {
        let age = now.timeIntervalSince(savedAt)
        if age < 0 { return .reload }
        if ok { return age < okTTL ? .use : .reload }
        return age < failTTL ? .knownMissing : .reload
    }

    /// Schluessel des lokalen Zwischenspeichers je Kandidatenliste (FNV-1a 64, stabil ueber Starts)
    static func cacheKey(_ urls: [URL]) -> String {
        var h: UInt64 = 0xcbf29ce484222325
        for byte in urls.map(\.absoluteString).joined(separator: "\n").utf8 { h ^= UInt64(byte); h = h &* 0x100000001b3 }
        return String(format: "%016llx", h)
    }
}
