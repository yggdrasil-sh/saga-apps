import Foundation

/// SVG-Bilder in Seiten (Saga 2.5, Trilium-Import: bereinigte SVGs als Bild, file.mime image/svg+xml) - reine Logik,
/// Logic-Tests; gleiche Regeln wie Android SvgLogic.kt. Anzeige nur als <img> in einer WebView ohne JavaScript mit
/// CSP "default-src 'none'; img-src data:" - SVG im <img> fuehrt nie Skript aus und laedt nichts nach.
enum SvgLogic {
    /// groessere SVGs nicht einbetten (Platzhalter mit "Bild öffnen")
    static let maxBytes = 3 * 1024 * 1024

    /// Daten sind ein SVG (Anfang: optional BOM, <?xml ...?>, Kommentare, DOCTYPE, dann <svg)
    static func isSvg(_ d: Data) -> Bool {
        guard !d.isEmpty else { return false }
        let head = String(decoding: d.prefix(4096), as: UTF8.self).lowercased()
        var s = Substring(head)
        if s.hasPrefix("\u{feff}") { s = s.dropFirst() }
        while true {
            s = s.drop(while: { $0.isWhitespace })
            if s.hasPrefix("<?") { guard let e = s.range(of: "?>") else { return false }; s = s[e.upperBound...]; continue }
            if s.hasPrefix("<!--") { guard let e = s.range(of: "-->") else { return false }; s = s[e.upperBound...]; continue }
            if s.hasPrefix("<!doctype") { guard let e = s.firstIndex(of: ">") else { return false }; s = s[s.index(after: e)...]; continue }
            break
        }
        guard s.hasPrefix("<svg") else { return false }
        let n = s.dropFirst(4).first
        return n == nil || n!.isWhitespace || n == ">" || n == "/"
    }

    /// Seitenverhaeltnis aus dem <svg>-Element: width/height (Zahl, px oder ohne Einheit), sonst viewBox; nil = unbekannt
    static func ratio(_ d: Data) -> Double? {
        let head = String(decoding: d.prefix(16_384), as: UTF8.self)
        guard let a = head.range(of: "<svg", options: .caseInsensitive), let e = head[a.upperBound...].firstIndex(of: ">") else { return nil }
        let tag = String(head[a.upperBound..<e])
        func attr(_ n: String) -> String? {
            guard let r = tag.range(of: #"(?i)(^|\s)"# + n + #"\s*=\s*("[^"]*"|'[^']*')"#, options: .regularExpression) else { return nil }
            let v = tag[r].split(separator: "=", maxSplits: 1).last.map(String.init) ?? ""
            return v.trimmingCharacters(in: CharacterSet(charactersIn: " \"'"))
        }
        func num(_ s: String?) -> Double? {
            guard var t = s?.trimmingCharacters(in: .whitespaces).lowercased(), !t.isEmpty, !t.hasSuffix("%") else { return nil }
            if t.hasSuffix("px") { t.removeLast(2) }
            guard let v = Double(t), v.isFinite, v > 0 else { return nil }
            return v
        }
        if let w = num(attr("width")), let h = num(attr("height")) { return clamp(w / h) }
        if let vb = attr("viewBox") {
            let p = vb.split(whereSeparator: { $0 == " " || $0 == "," }).compactMap { Double($0) }
            if p.count == 4, p[2] > 0, p[3] > 0, p[2].isFinite, p[3].isFinite { return clamp(p[2] / p[3]) }
        }
        return nil
    }
    private static func clamp(_ r: Double) -> Double { min(max(r, 0.05), 20) }

    /// Seite fuer die WebView: nur das SVG als data:-<img>, kein Skript, nichts nachladen
    static func html(_ d: Data, cover: Bool = false) -> String {
        let fit = cover ? "cover" : "contain"
        return "<!doctype html><html><head><meta charset=\"utf-8\">"
            + "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; img-src data:; style-src 'unsafe-inline'\">"
            + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
            + "<style>html,body{margin:0;padding:0;width:100%;height:100%;background:transparent;overflow:hidden}"
            + "img{display:block;width:100%;height:100%;object-fit:\(fit);-webkit-user-select:none;-webkit-touch-callout:none;pointer-events:none}</style>"
            + "</head><body><img alt=\"\" src=\"data:image/svg+xml;base64,\(d.base64EncodedString())\"></body></html>"
    }

    /// Navigation in der SVG-WebView: nur die eigene, leere Seite (loadHTMLString ohne Basis)
    static func allowNavigation(_ url: URL?) -> Bool {
        guard let u = url else { return true }
        return u.absoluteString == "about:blank"
    }
}
