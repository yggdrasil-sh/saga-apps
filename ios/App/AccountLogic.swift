import Foundation

// Saga 2.1, Konto: "Anmeldung & Sicherheit" (Status aus GET /api/account/security, verwaltet wird im Browser) und
// Profilbilder (Feld `avatar` in Personen-Objekten, eigenes Bild hochladen mit Zuschnitt). Reine Logik ohne UI (swiftc).

/// Status "Anmeldung & Sicherheit" (nur lesen - Einrichten/Aendern im System-Browser unter /security)
struct SecurityStatus: Equatable {
    var supported = true
    var enabled = false
    var required = false
    var passkeys = 0
    var totp = false
    var recoveryLeft: Int?
    init(supported: Bool = true, enabled: Bool = false, required: Bool = false, passkeys: Int = 0, totp: Bool = false, recoveryLeft: Int? = nil) {
        self.supported = supported; self.enabled = enabled; self.required = required; self.passkeys = passkeys; self.totp = totp; self.recoveryLeft = recoveryLeft
    }
    init?(data: Data) {
        guard let j = ShareJSON.obj(data) else { return nil }
        supported = j["supported"].map(ShareJSON.bool) ?? true
        enabled = ShareJSON.bool(j["enabled"]); required = ShareJSON.bool(j["required"])
        passkeys = (j["passkeys"] as? [Any])?.count ?? 0
        totp = ShareJSON.bool(j["totp"])
        recoveryLeft = ShareJSON.int(j["recovery_left"])
    }
    /// Warnung "nur noch N Wiederherstellungscodes" (bei <= 3, solange ein zweiter Faktor an ist)
    var fewRecoveryCodes: Bool { enabled && (recoveryLeft.map { $0 <= 3 } ?? false) }
    /// Admin verlangt einen zweiten Faktor, aber keiner ist eingerichtet
    var mustSetUp: Bool { required && !enabled }
}

enum AvatarLogic {
    /// Stufen des Servers (er liefert die naechstgroessere)
    static let steps = [64, 128, 256]
    /// Anzeigegroesse (pt) x Skalierung -> angefragte Stufe (gleiche Stufe = gleicher Zwischenspeicher)
    static func step(points: Double, scale: Double) -> Int {
        let px = Int((points * max(1, scale)).rounded(.up))
        return steps.first { $0 >= px } ?? steps.last!
    }
    /// Adresse zum Laden: nur relative Adressen des eigenen Servers ("/api/avatar/…"), nie fremde Hosts
    static func url(server: String, avatar: String?, px: Int) -> URL? {
        guard let a = avatar?.trimmingCharacters(in: .whitespaces), a.hasPrefix("/api/avatar/"), !a.contains("://"), !a.hasPrefix("//") else { return nil }
        let base = server.hasSuffix("/") ? String(server.dropLast()) : server
        return URL(string: base + a + (a.contains("?") ? "&" : "?") + "s=\(px)")
    }

    /// Woher das eigene Bild kommt (Text in den Einstellungen)
    enum Source: Equatable { case upload, sso, gravatar, none }
    static func source(_ s: String?) -> Source {
        switch s { case "upload": return .upload; case "sso": return .sso; case "gravatar": return .gravatar; default: return .none }
    }

    // MARK: Zuschnitt (quadratisch, Kreis-Vorschau)

    /// Grundmassstab: Bild fuellt das Quadrat der Seitenlaenge `side` (wie object-fit: cover)
    static func coverScale(image w: Double, _ h: Double, side: Double) -> Double { side / max(1, min(w, h)) }
    /// Verschiebung so begrenzen, dass das Bild das Quadrat immer ganz bedeckt
    static func clamp(offset: (x: Double, y: Double), image w: Double, _ h: Double, side: Double, zoom: Double) -> (x: Double, y: Double) {
        let s = coverScale(image: w, h, side: side) * max(1, zoom)
        let mx = max(0, (w * s - side) / 2), my = max(0, (h * s - side) / 2)
        return (min(mx, max(-mx, offset.x)), min(my, max(-my, offset.y)))
    }
    /// Sichtbares Quadrat in Bildpixeln (x, y, Kante) fuer Zoom und Verschiebung (Bildmitte + offset = Quadratmitte)
    static func cropRect(image w: Double, _ h: Double, side: Double, zoom: Double, offset: (x: Double, y: Double)) -> (x: Double, y: Double, size: Double) {
        let z = max(1, zoom), s = coverScale(image: w, h, side: side) * z
        let o = clamp(offset: offset, image: w, h, side: side, zoom: z)
        let size = min(min(w, h), side / s)
        let x = (w * s / 2 - side / 2 - o.x) / s, y = (h * s / 2 - side / 2 - o.y) / s
        return (min(max(0, x), w - size), min(max(0, y), h - size), size)
    }
    /// Kantenlaenge des hochgeladenen Bildes: 512...1024 px (kleine Bilder werden auf 512 vergroessert)
    static func outputSide(_ crop: Double) -> Int { Int(min(1024, max(512, crop.rounded()))) }
}

enum ReauthLogic {
    /// Chat "Neu beginnen": 6 Ziffern bei eingerichteter Authenticator-App = Code, sonst Passwort (wie static/chat.js)
    static func body(_ input: String, methods: [String]) -> [String: String] {
        let t = input.trimmingCharacters(in: .whitespaces)
        let digits = t.replacingOccurrences(of: " ", with: "")
        if methods.contains("totp"), digits.count == 6, digits.allSatisfy({ $0.isASCII && $0.isNumber }) { return ["code": digits] }
        return ["password": input]
    }
    /// Eingabefeld zeigen? Nur Passkey (ohne Passwort/Code) -> nur im Browser moeglich
    static func nativeInput(methods: [String]) -> Bool { methods.isEmpty || methods.contains("password") || methods.contains("totp") }
}
