import Foundation
import CoreGraphics

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
        guard let a = avatar?.trimmingCharacters(in: .whitespaces), a.hasPrefix("/api/avatar/") || isAI(a), !a.contains("://"), !a.hasPrefix("//") else { return nil }
        let base = server.hasSuffix("/") ? String(server.dropLast()) : server
        return URL(string: base + a + (a.contains("?") ? "&" : "?") + "s=\(px)")
    }

    /// Profilbild der KI (2.1): /api/ai/avatar[?v=…]
    static func isAI(_ a: String) -> Bool { a == "/api/ai/avatar" || a.hasPrefix("/api/ai/avatar?") }

    /// Adresse ohne Version (?v=...): fester Pfad je Person - so findet das Live-Ereignis avatar.changed {user, avatar, path}
    /// alle angezeigten Bilder dieser Person, auch wenn sich v aendert oder das Bild entfernt wird
    static func path(_ avatar: String?) -> String? {
        guard let a = avatar?.trimmingCharacters(in: .whitespaces), a.hasPrefix("/api/avatar/") || isAI(a) else { return nil }
        return a.split(separator: "?", maxSplits: 1, omittingEmptySubsequences: false).first.map(String.init)
    }
    /// avatar.changed lesen: (Pfad, neue Adresse oder nil = Initialen); nur Saga-Pfade, neue Adresse zum selben Pfad
    static func change(_ d: [String: Any]) -> (path: String, avatar: String?)? {
        guard let p = d["path"] as? String, p.hasPrefix("/api/avatar/"), !p.contains("?"), !p.contains("://") else { return nil }
        let a = (d["avatar"] as? String).flatMap { $0.isEmpty ? nil : $0 }
        if let a, path(a) != p { return nil }
        return (p, a)
    }
    /// Angezeigte Adresse: gab es fuer diese Person seitdem ein avatar.changed, gilt dessen Adresse (nil = Initialen)
    static func current(_ avatar: String?, changes: [String: String?]) -> String? {
        guard let p = path(avatar), let c = changes[p] else { return avatar }
        return c
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
    /// Zwischenspeicher: nach so vielen Sekunden beim naechsten Anzeigen (Seitenwechsel, Liste) beim Server nachfragen
    /// (bedingt per ETag -> meist 304); nach einem voruebergehenden Fehler frueher erneut versuchen
    static let revalidateAfter: TimeInterval = 600
    static let retryAfterError: TimeInterval = 60
    /// Antwort des Servers -> was der Zwischenspeicher tut. Nur 404 heisst "kein Bild" (Initialen, bis zur
    /// Revalidierung); 401/429/5xx usw. sind voruebergehend und duerfen das Bild nicht fuer die Sitzung sperren.
    enum Fetch: Equatable { case image, missing, transient }
    static func fetchResult(code: Int?, decoded: Bool) -> Fetch {
        guard let code else { return .transient }                        // offline
        if code == 200 || code == 304 { return decoded ? .image : .missing }
        if code == 404 || code == 410 { return .missing }
        return .transient
    }
    /// Eintrag (Bild oder "fehlt") noch frisch? checked = letzter Abgleich, retry = naechster Versuch nach Fehler
    static func fresh(checked: Date?, retry: Date?, now: Date) -> Bool {
        if let retry { return now < retry }
        guard let checked else { return false }
        return now.timeIntervalSince(checked) < revalidateAfter
    }
    /// Ausschnitt auf side x side Pixel zeichnen, transparente Flaechen weiss (JPEG kennt kein Alpha - sonst schwarz,
    /// wie die Chat-Vorschaubilder)
    static func flatten(_ piece: CGImage, side: Int) -> CGImage? {
        guard side > 0, let ctx = CGContext(data: nil, width: side, height: side, bitsPerComponent: 8, bytesPerRow: 0, space: CGColorSpaceCreateDeviceRGB(),
                                            bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue) else { return nil }
        ctx.setFillColor(CGColor(red: 1, green: 1, blue: 1, alpha: 1))
        ctx.fill(CGRect(x: 0, y: 0, width: side, height: side))
        ctx.interpolationQuality = .high
        ctx.draw(piece, in: CGRect(x: 0, y: 0, width: side, height: side))
        return ctx.makeImage()
    }
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
    /// Feld als Einmal-Code kennzeichnen (textContentType .oneTimeCode), sobald ein Code moeglich ist: sonst bietet
    /// iOS an, den 6-stelligen Code als Passwort fuer den Server zu sichern (und ersetzt ggf. das echte Passwort)
    static func oneTimeCodeField(methods: [String]) -> Bool { methods.contains("totp") }
}
