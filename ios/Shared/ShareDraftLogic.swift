import Foundation

// 2.6 K-G (Bugs-DB 2275, Entscheidung F1 a): Teilen -> Auftrag auf iPhone/iPad.
// Die Teilen-Erweiterung legt nur einen lokalen Entwurf in der App-Gruppe ab (hoechstens einer, verfaellt nach 24 h,
// nach der Uebernahme geloescht, nie auf dem Mac) und meldet sich mit einer neutralen lokalen Mitteilung. Kein hkd_, kein
// Serveraufruf in der Erweiterung - die App uebernimmt den Entwurf ueber saga://share in den Ort "Saga" (Zeile
// "Geteilt: host ×", "Als Auftrag" an) und sendet erst auf Tippen (POST /api/ai/jobs mit source "share" + shared).
// Vertrag: Server docs/APP-API-KI.md Abschnitt 14.1, Vektoren tests/vectors/share_contract.json (Kopie Tests/vectors).
// Reine Regeln (Logic-Tests: ShareDraftTests) - Vorlage Android ai/SagaShare.kt.

/// Geteilter Inhalt fuer POST /api/ai/jobs `shared` (from ist in der App immer "app")
struct SharePayload: Codable, Equatable {
    var text: String
    var url: String?
    var title: String?
    /// Host der Adresse fuer die Zeile "Geteilt: host" (wie der Server: klein, ohne www., Port und Zugangsdaten)
    var host: String? { ShareDraftLogic.host(url) }
}

/// Eigener Zug mit geteiltem Inhalt (fields.shared {host, from}) bzw. lokal: nur Host/Titel, nie der Text
struct ShareMark: Equatable {
    var host: String?
    var title: String?
}

/// Entwurf in der App-Gruppe (nur iOS): Inhalt, Zeitpunkt, Server (Ursprung), fuer den er gedacht war
struct ShareDraft: Codable, Equatable {
    var payload: SharePayload
    var created: Double
    var server: String?
}

enum ShareDraftLogic {
    /// Server-Merkmal (/api/version features): nur damit gibt es die Option "{Name} soll sich kümmern …"
    static let feature = "aishare1"
    static let textMax = 20_000
    static let urlMax = 2_000
    static let titleMax = 200
    /// Entwurf verfaellt nach 24 h
    static let maxAge: Double = 24 * 3600
    /// Uhr zurueckgestellt: Entwurf "aus der Zukunft" gilt nur mit kleiner Toleranz
    static let futureSlack: Double = 300

    // Schluessel in AppConfig.store (App-Gruppe)
    static let draftKey = "share.draft"
    /// Von der App gesetzt: aishare1 ∧ Auftraege ∧ ai_act ∧ Einwilligung - nur dann zeigt die Erweiterung die Option
    static let jobsKey = "share.jobs"
    /// Name der Kollegin fuer "{Name} soll sich kümmern …" in der Erweiterung (von der App gesetzt)
    static let nameKey = "share.name"
    /// Lokale Mitteilung der Erweiterung (feste Kennung: hoechstens eine) und ihr Ziel
    static let notifyID = "saga.share.draft"
    static let notifyKind = "saga.share"
    static let linkHost = "share"

    /// Nur iPhone/iPad - auf dem Mac (Huelle, eingefrorene Mac-App) nie ein Entwurf und nie die Option
    static var platformOK: Bool {
        #if os(iOS)
        return true
        #else
        return false
        #endif
    }

    // MARK: Freigabe der Option

    /// F4: aishare1, Auftraege (jobs, Stufe nicht answers), Faehigkeit ai_act (eingeschraenkte Gruppe: nie, RT-17) und
    /// Einwilligung. Aeltere Server ohne caps: nein.
    static func jobsAllowed(feature: Bool, jobsOn: Bool, consentOK: Bool, caps: [String]) -> Bool {
        feature && jobsOn && consentOK && caps.contains("ai_act")
    }
    /// Zeigt die Erweiterung die Option? (Schalter der App + Plattform)
    static func optionShown(in d: UserDefaults, platform: Bool = platformOK) -> Bool {
        platform && d.bool(forKey: jobsKey)
    }
    /// App: Schalter und Name fuer die Erweiterung setzen (aus = Name weg)
    static func setJobs(_ on: Bool, name: String?, in d: UserDefaults) {
        if d.bool(forKey: jobsKey) != on { d.set(on, forKey: jobsKey) }
        let n = on ? name?.trimmingCharacters(in: .whitespacesAndNewlines) : nil
        if d.string(forKey: nameKey) != n { if let n, !n.isEmpty { d.set(n, forKey: nameKey) } else { d.removeObject(forKey: nameKey) } }
    }

    // MARK: Grenzen und Host (wie der Server, ai_jobs.parse_shared / ai_plan.share_host)

    /// Zeichen, die der Server in einer Adresse ablehnt (_URL_OK: kein Leerraum, keine Steuerzeichen, kein <>"\^`{|})
    private static let urlBad: Set<Character> = ["<", ">", "\"", "\\", "^", "`", "{", "|", "}"]

    /// Adresse brauchbar fuer shared.url? nur http(s), ohne Leerraum, hoechstens 2000 Zeichen, mit Host
    static func urlOk(_ url: String?) -> Bool {
        guard let u = url, !u.isEmpty, u.utf16.count <= urlMax else { return false }
        if u.unicodeScalars.contains(where: { $0.properties.isWhitespace || $0.value < 0x20 || $0.value == 0x7F }) { return false }
        if u.contains(where: { urlBad.contains($0) }) { return false }
        let l = u.lowercased()
        guard l.hasPrefix("https://") || l.hasPrefix("http://") else { return false }
        return hostPart(u) != nil
    }

    /// Host wie der Server (share_host): klein, ohne www. (nur mit mindestens zwei Punkten), ohne Port/Zugangsdaten; sonst nil
    static func host(_ url: String?) -> String? {
        guard urlOk(url) else { return nil }
        return hostPart(url!)
    }

    private static func hostPart(_ u: String) -> String? {
        guard let r = u.range(of: "://") else { return nil }
        var a = Substring(u[r.upperBound...])
        if let e = a.firstIndex(where: { $0 == "/" || $0 == "?" || $0 == "#" }) { a = a[..<e] }
        if let at = a.lastIndex(of: "@") { a = a[a.index(after: at)...] }
        if a.hasPrefix("[") {
            guard let c = a.firstIndex(of: "]") else { return nil }
            a = a[a.index(after: a.startIndex)..<c]
        } else if let c = a.firstIndex(of: ":") {
            a = a[..<c]
        }
        var h = String(a).trimmingCharacters(in: CharacterSet(charactersIn: ".")).lowercased()
        if h.hasPrefix("www.") && h.filter({ $0 == "." }).count >= 2 { h = String(h.dropFirst(4)) }
        h = String(h.prefix(253))
        return h.isEmpty ? nil : h
    }

    /// Hoechstens n UTF-16-Einheiten (n UTF-16 >= n Codepunkte beim Server), ohne ein Zeichen zu zerschneiden
    static func cut(_ s: String, _ n: Int) -> String {
        guard s.utf16.count > n else { return s }
        var out = ""
        var used = 0
        for sc in s.unicodeScalars {
            let w = sc.utf16.count
            if used + w > n { break }
            out.unicodeScalars.append(sc); used += w
        }
        return out
    }

    /// Steuerzeichen ausser Zeilenumbruch/Tab weg (der Server tut es auch - so stimmt die Laenge)
    static func clean(_ s: String) -> String {
        var out = String.UnicodeScalarView()
        for sc in s.unicodeScalars where sc == "\n" || sc == "\t" || (sc.value >= 0x20 && sc.value != 0x7F) { out.append(sc) }
        return String(out)
    }

    /// Aus dem Teilen-Blatt: Text bis 20 000 (F9), Titel bis 200, Adresse nur gueltig (sonst als Text, damit nichts
    /// verloren geht). Ohne Text und ohne Adresse -> nil (der Server lehnte es mit share_empty ab).
    static func payload(text: String, url: String?, title: String) -> SharePayload? {
        var t = clean(text).trimmingCharacters(in: .whitespacesAndNewlines)
        var u = url?.trimmingCharacters(in: .whitespacesAndNewlines)
        if u?.isEmpty == true { u = nil }
        if let x = u, !urlOk(x) {
            t = t.isEmpty ? x : t + "\n" + x
            u = nil
        }
        t = cut(t, textMax)
        let ti = cut(clean(title).replacingOccurrences(of: "\n", with: " ").trimmingCharacters(in: .whitespacesAndNewlines), titleMax)
            .trimmingCharacters(in: .whitespacesAndNewlines)
        if t.isEmpty && u == nil { return nil }
        return SharePayload(text: t, url: u, title: ti.isEmpty ? nil : ti)
    }

    /// Feld shared (14.1): leere Felder weglassen, from immer "app"
    static func sharedJSON(_ p: SharePayload) -> [String: Any] {
        var o: [String: Any] = ["from": "app"]
        if !p.text.isEmpty { o["text"] = p.text }
        if let u = p.url, urlOk(u) { o["url"] = u }
        if let t = p.title, !t.isEmpty { o["title"] = t }
        return o
    }

    /// Senden moeglich? Mit geteiltem Inhalt auch ohne eigene Worte (Systembitte "Kümmere dich darum.")
    static func canSend(_ draft: String, shared: SharePayload?, max: Int = 1000) -> Bool {
        let t = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        if t.count > max { return false }
        return !t.isEmpty || shared != nil
    }

    /// Welcher geteilte Inhalt mit einem Auftrag aus dem Gespraech geht (14.1): nie mit einem Rezept, bei der Antwort auf
    /// eine Rueckfrage der Inhalt dieser Rueckfrage (nach einem Neustart nil), sonst der aus der Zeile "Geteilt".
    static func sharedFor(recipe: Bool, choice: String?, askShared: SharePayload?, line: SharePayload?) -> SharePayload? {
        if recipe { return nil }
        if choice != nil { return askShared }
        return line
    }

    /// Zeile "Geteilt" im Gespraech: nur mit "Als Auftrag" und solange die Freigabe gilt (F4/RT-17) - nach einem Entzug
    /// (Admin, ai_act, Einwilligung, aishare1) weder sichtbar noch mitgeschickt, auch wenn das Gespraech nicht offen war
    static func line(_ p: SharePayload?, asJob: Bool, allowed: Bool) -> SharePayload? { asJob && allowed ? p : nil }

    /// "Geteilt: host" | "Geteilt: „Titel“" (nur ohne Host) | "Geteilt" - welche Form (der Satz steht im Katalog)
    enum LabelKind: Equatable { case host, title, plain }
    static func labelKind(_ m: ShareMark) -> LabelKind {
        if let h = m.host, !h.trimmingCharacters(in: .whitespaces).isEmpty { return .host }
        if let t = m.title, !t.trimmingCharacters(in: .whitespaces).isEmpty { return .title }
        return .plain
    }
    static func mark(_ p: SharePayload) -> ShareMark { ShareMark(host: p.host, title: p.title) }
    /// Markierung eines eigenen Zugs aus dem Verlauf (fields.shared {host, from}); ohne shared bzw. KI-Zug -> nil
    static func turnMark(role: String, fields: [String: Any]) -> ShareMark? {
        guard role == "me", let sh = fields["shared"] as? [String: Any] else { return nil }
        let h = (sh["host"] as? String)?.trimmingCharacters(in: .whitespaces)
        return ShareMark(host: h?.isEmpty == false ? h : nil, title: nil)
    }

    // MARK: Entwurf in der App-Gruppe (nur iOS)

    /// Erweiterung: Entwurf ablegen - ersetzt einen aelteren (hoechstens einer). Auf dem Mac nie (false).
    @discardableResult
    static func save(_ p: SharePayload, server: String?, now: Double, in d: UserDefaults, platform: Bool = platformOK) -> Bool {
        guard platform, let data = try? JSONEncoder().encode(ShareDraft(payload: p, created: now, server: server)) else { return false }
        d.set(data, forKey: draftKey)
        return true
    }

    /// Gueltig? nicht aelter als 24 h, nicht aus der Zukunft, fuer denselben Server
    static func fresh(_ x: ShareDraft, now: Double, server: String?) -> Bool {
        let age = now - x.created
        guard age <= maxAge, age >= -futureSlack else { return false }
        return x.server == nil || server == nil || x.server == server
    }

    /// Liegt ein gueltiger Entwurf bereit? (abgelaufene bzw. fremde werden dabei geloescht)
    static func peek(in d: UserDefaults, now: Double, server: String?, platform: Bool = platformOK) -> ShareDraft? {
        guard let data = d.data(forKey: draftKey) else { return nil }
        guard platform, let x = try? JSONDecoder().decode(ShareDraft.self, from: data), fresh(x, now: now, server: server) else {
            d.removeObject(forKey: draftKey); return nil
        }
        return x
    }

    /// App: Entwurf uebernehmen - danach ist er geloescht (auch wenn er abgelaufen war)
    static func take(in d: UserDefaults, now: Double, server: String?, platform: Bool = platformOK) -> ShareDraft? {
        let x = peek(in: d, now: now, server: server, platform: platform)
        d.removeObject(forKey: draftKey)
        return x
    }

    /// App ohne Ort "Saga" (2600: nie still): nur verwerfen, wenn der Server geantwortet hat (KI aus, abgemeldet). Nicht
    /// erreichbar (z. B. offline geteilt): der Entwurf bleibt liegen und verfaellt nach 24 h - wie das Teilen-Blatt zusagt.
    static func takeWithoutPlace(reachable: Bool, in d: UserDefaults, now: Double, server: String?, platform: Bool = platformOK) -> ShareDraft? {
        reachable ? take(in: d, now: now, server: server, platform: platform) : peek(in: d, now: now, server: server, platform: platform)
    }

    static func clear(in d: UserDefaults) { d.removeObject(forKey: draftKey) }

    /// Ziel saga://share bzw. hlidskjalf://share
    static func isShareLink(_ u: URL) -> Bool {
        (u.scheme == "saga" || u.scheme == "hlidskjalf") && u.host == linkHost
    }
}
