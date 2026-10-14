import Foundation

// Veroeffentlichen: reine Logik ohne SwiftUI (Zustand vom Server -> Anzeige, Fehlerfaelle, Namenspruefung).
// Die Texte stehen in den Ansichten (PublishSheet.swift); hier nur Faelle - so laesst sich alles mit swiftc pruefen.
// Server: /api/notes/{id}/publish (GET/PUT/DELETE), /api/account/public-name (GET/PUT, check=true nur pruefen).

/// Verweis auf eine andere Seite (Elternseite mit "Unterseiten mit veroeffentlichen", bisherige Profilseite)
struct PubLink: Equatable {
    var noteID: Int
    var title: String
    var url: String?

    init(noteID: Int, title: String, url: String? = nil) { self.noteID = noteID; self.title = title; self.url = url }
    init?(_ v: Any?) {
        guard let d = v as? [String: Any], let id = PubJSON.int(d["note_id"]) else { return nil }
        self.init(noteID: id, title: PubJSON.str(d["title"]) ?? "", url: PubJSON.str(d["url"]))
    }
    var dict: [String: Any] { ["note_id": noteID, "title": title, "url": url ?? NSNull()] }
}

/// Werte aus JSONSerialization (NSNumber, NSNull) robust lesen
enum PubJSON {
    static func str(_ v: Any?) -> String? { (v as? String).flatMap { $0.isEmpty ? nil : $0 } }
    static func bool(_ v: Any?) -> Bool { (v as? Bool) ?? ((v as? NSNumber)?.boolValue ?? false) }
    static func num(_ v: Any?) -> Double? { v is NSNull ? nil : (v as? Double) ?? (v as? NSNumber)?.doubleValue }
    static func int(_ v: Any?) -> Int? { v is NSNull ? nil : (v as? Int) ?? (v as? NSNumber)?.intValue }
}

/// Veroeffentlichung einer Seite, wie GET/PUT/DELETE /api/notes/{id}/publish sie liefern
struct PublishState: Equatable {
    var enabled = true
    var name: String?
    var published = false
    var slug = ""
    var children = false
    var indexable = false
    var profile = false
    var url: String?
    var profileURL: String?
    var home = false
    var suggestedName: String?
    var via: PubLink?
    var withdrawnAt: Double?
    var withdrawnBy: String?
    var profileOther: PubLink?
    var replacedProfile: PubLink?

    init() {}
    init(json j: [String: Any]) {
        enabled = j["enabled"].map(PubJSON.bool) ?? true                 // fehlt (aelterer Server): als erlaubt behandeln
        name = PubJSON.str(j["name"])
        published = PubJSON.bool(j["published"])
        slug = PubJSON.str(j["slug"]) ?? ""
        children = PubJSON.bool(j["children"]); indexable = PubJSON.bool(j["indexable"]); profile = PubJSON.bool(j["profile"])
        url = PubJSON.str(j["url"]); profileURL = PubJSON.str(j["profile_url"])
        home = PubJSON.bool(j["home"])
        suggestedName = PubJSON.str(j["suggested_name"])
        via = PubLink(j["published_via"])
        withdrawnAt = PubJSON.num(j["withdrawn_at"]); withdrawnBy = PubJSON.str(j["withdrawn_by"])
        profileOther = PubLink(j["profile_other"]); replacedProfile = PubLink(j["replaced_profile"])
    }
    init?(data: Data) {
        guard let j = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { return nil }
        self.init(json: j)
    }
}

/// Antwort des Servers auf eine Anfrage an /publish
enum PublishOutcome: Equatable {
    case state(PublishState)
    /// Noch kein oeffentlicher Name (409 bzw. 400 beim mitgeschickten, ungueltigen Namen)
    case needName(suggested: String?, message: String?)
    /// Fehler mit (uebersetztem) Text vom Server, sonst nur der Code
    case failed(message: String?, code: Int)

    static func parse(code: Int, data: Data) -> PublishOutcome {
        let j = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
        if (200..<300).contains(code) {
            guard let j else { return .failed(message: nil, code: code) }
            return .state(PublishState(json: j))
        }
        let msg = PubJSON.str(j?["error"])
        if PubJSON.bool(j?["need_name"]) { return .needName(suggested: PubJSON.str(j?["suggested_name"]), message: msg) }
        return .failed(message: msg, code: code)
    }
}

/// Live-Pruefung des oeffentlichen Namens (PUT /api/account/public-name mit check=true)
enum NameCheck: Equatable {
    case idle, checking, ok
    case bad(String?)
    var isOK: Bool { self == .ok }

    static func parse(code: Int, data: Data) -> NameCheck {
        let j = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
        guard (200..<300).contains(code), let j else { return .bad(PubJSON.str(j?["error"])) }
        return PubJSON.bool(j["ok"]) ? .ok : .bad(PubJSON.str(j["error"]))
    }
}

/// Was der Dialog zeigt - abgeleitet aus Zustand und der gerade getippten Adresse.
/// Reihenfolge (alle Plattformen): Schalter -> Link-Karte -> Adresse -> Optionen -> "Veroeffentlichung beenden"
struct PublishDisplay: Equatable {
    enum MainTitle: Equatable { case isPublic, alsoOwnAddress }
    enum Withdrawn: Equatable { case byAdmin(Double), byUser(Double) }
    /// Link-Karte: aktiver Link (Kopieren/Oeffnen/Teilen) oder - solange privat - die kuenftige Adresse, gedaempft
    enum LinkCard: Equatable {
        case live(String), future(String)
        var url: String { switch self { case .live(let u), .future(let u): return u } }
        var isLive: Bool { if case .live = self { return true }; return false }
    }

    var mainTitle: MainTitle
    var toggleOn: Bool
    var toggleDisabled: Bool
    /// Admin hat das Veroeffentlichen abgeschaltet
    var serverDisabled: Bool
    /// Schon oeffentlich ueber eine Elternseite (nur, solange die Seite selbst nicht veroeffentlicht ist)
    var via: PubLink?
    var withdrawn: Withdrawn?
    /// nil: keine eigene Link-Karte (nur ueber die Elternseite oeffentlich - die Karte der Elternseite steht an ihrer Stelle)
    var linkCard: LinkCard?
    /// Zweite Zeile der Link-Karte "Profiladresse: .../@name" (Profilseite eingeschaltet)
    var profileLink: String?
    /// Adresse und Optionen: bei "ueber Elternseite" erst, wenn "Zusaetzlich ..." an ist
    var showAddress: Bool
    var showOptions: Bool
    /// Ueberschrift "Gilt, sobald die Seite oeffentlich ist" ueber den Optionen (solange privat)
    var optionsPending: Bool
    var slugChanged: Bool
    /// "Adresse aendern" + Hinweis "Wird erst mit ... uebernommen." nur, wenn die Eingabe von der gespeicherten Adresse abweicht
    var showChangeSlug: Bool
    var changeSlugEnabled: Bool
    var showStop: Bool
    /// Titel der Profilseite, die ein Einschalten von "Als Profilseite" ersetzen wuerde
    var profileReplaces: String?
    var isHome: Bool

    init(_ s: PublishState, slugInput: String, server: String = "") {
        let slug = PublishLogic.normalize(slugInput)
        let viaOnly = s.via != nil && !s.published
        mainTitle = viaOnly ? .alsoOwnAddress : .isPublic
        toggleOn = s.published
        toggleDisabled = !s.enabled && !s.published
        serverDisabled = !s.enabled
        via = viaOnly ? s.via : nil
        if !s.published, let t = s.withdrawnAt { withdrawn = s.withdrawnBy == "admin" ? .byAdmin(t) : .byUser(t) } else { withdrawn = nil }
        if viaOnly { linkCard = nil }
        else if s.published, let u = s.url { linkCard = .live(u) }
        else { linkCard = .future(PublishLogic.previewURL(s, slug: s.published ? s.slug : slug, server: server)) }
        profileLink = !viaOnly && s.profile ? (s.profileURL ?? PublishLogic.namePreview(s.name ?? "", server: server)) : nil
        showAddress = !viaOnly
        showOptions = !viaOnly
        optionsPending = !s.published
        slugChanged = s.published && slug != s.slug
        showChangeSlug = slugChanged
        changeSlugEnabled = slugChanged && PublishLogic.slugLooksValid(slug)
        showStop = s.published
        profileReplaces = s.profileOther?.title
        isHome = s.home && s.published
    }
}

/// Wo eine Rueckmeldung steht (Fehler direkt am Feld, nicht in einer Statuszeile)
enum PubField: Equatable {
    case main, link, slug
    case option(String)

    /// Fehler einer PUT-Anfrage zuordnen: Adresse mitgeschickt und 400/409 -> am Adressfeld;
    /// nur eine Option geaendert -> an dieser Option; sonst am Schalter
    static func forError(body: [String: Any], code: Int) -> PubField {
        if body["slug"] != nil, code == 400 || code == 409 { return .slug }
        if body.count == 1, let k = body.keys.first, ["children", "indexable", "profile"].contains(k) { return .option(k) }
        return .main
    }
}

/// Einstellungen -> Konto: Live-Vorschau beim Aendern des oeffentlichen Namens
struct NameChangePreview: Equatable {
    var new: String
    var old: String
}

/// Hinweis im Seitenkopf: aus GET /api/notes/{id} (published, public_url, published_via)
enum PublicBadge: Equatable {
    case own(url: String?)
    case via(title: String, url: String?)

    /// Server kann veroeffentlichen? (aeltere Server liefern kein "published" -> Menuepunkt ausblenden)
    static func supported(_ page: [String: Any]) -> Bool { page["published"] != nil && !(page["published"] is NSNull) }

    static func from(_ page: [String: Any]) -> PublicBadge? {
        if PubJSON.bool(page["published"]) { return .own(url: PubJSON.str(page["public_url"])) }
        if let v = PubLink(page["published_via"]) { return .via(title: v.title, url: v.url) }
        return nil
    }
    var url: String? { switch self { case .own(let u): return u; case .via(_, let u): return u } }
}

enum PublishLogic {
    static let nameMax = 32, slugMax = 60

    /// Wie der Server: Leerraum weg, klein
    static func normalize(_ s: String) -> String { s.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() }

    private static func matches(_ s: String, max: Int) -> Bool {
        let ok = Set("abcdefghijklmnopqrstuvwxyz0123456789_-")
        guard let f = s.first, s.count >= 2, s.count <= max, s.allSatisfy({ ok.contains($0) }) else { return false }
        return f != "_" && f != "-"
    }
    /// Grobe Vorpruefung (die verbindliche Pruefung macht der Server, mit Text in der App-Sprache)
    static func nameLooksValid(_ s: String) -> Bool { matches(s, max: nameMax) }
    static func slugLooksValid(_ s: String) -> Bool { matches(s, max: slugMax) }

    /// Vorschau der Adresse: wie der Server sie baut (profile_url = Basis-Adresse + /@name), sonst ueber den App-Server
    static func previewURL(_ s: PublishState, slug: String, server: String) -> String {
        let tail = slug.isEmpty ? "…" : slug
        if let p = s.profileURL { return p + "/" + tail }
        return server.trimmingCharacters(in: CharacterSet(charactersIn: "/")) + "/@" + (s.name ?? "…") + "/" + tail
    }
    /// Vorschau fuer den Namensschritt
    static func namePreview(_ name: String, server: String) -> String {
        server.trimmingCharacters(in: CharacterSet(charactersIn: "/")) + "/@" + (name.isEmpty ? "…" : name)
    }

    /// Anzeige einer Adresse: Umbruch nur nach "/" (U+200B nach jeder Folge von "/"), nie nach "-" (U+2060).
    /// Nur fuer die Anzeige - kopiert/geoeffnet wird immer die unveraenderte Adresse.
    static func breakable(_ url: String) -> String {
        var out = "", chars = Array(url)
        for (i, c) in chars.enumerated() {
            out.append(c)
            let next: Character? = i + 1 < chars.count ? chars[i + 1] : nil
            if c == "/", next != nil, next != "/" { out.append("\u{200B}") }
            else if c == "-", next != nil { out.append("\u{2060}") }
        }
        return out
    }

    /// Basis-Adresse fuer /@name: aus der bisherigen Profiladresse (.../@name), sonst der App-Server
    static func publicBase(url: String?, name: String?, server: String) -> String {
        if let u = url, let n = name, u.hasSuffix("/@" + n) { return String(u.dropLast(n.count + 2)) }
        return server.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
    }
    /// Einstellungen: "Neue Adresse: .../@neu (bisher .../@alt)" - nur beim Aendern eines bestehenden Namens
    static func nameChangePreview(input: String, current: String?, currentURL: String?, server: String) -> NameChangePreview? {
        let v = normalize(input)
        guard let c = current, !v.isEmpty, v != c else { return nil }
        let base = publicBase(url: currentURL, name: c, server: server)
        return NameChangePreview(new: base + "/@" + v, old: currentURL ?? base + "/@" + c)
    }

    /// Einschalten: alle Einstellungen mitschicken (die Adresse nur, wenn eine eingetippt ist)
    static func publishBody(_ s: PublishState, slugInput: String) -> [String: Any] {
        var b: [String: Any] = ["children": s.children, "indexable": s.indexable, "profile": s.profile]
        let slug = normalize(slugInput)
        if !slug.isEmpty { b["slug"] = slug }
        return b
    }

    /// Kopf-Hinweis: langer Titel gekuerzt (wie im Web: hoechstens 28 Zeichen)
    static func shortTitle(_ t: String, max: Int = 28) -> String { t.count > max ? String(t.prefix(max - 1)) + "…" : t }

    /// Einstellungen -> Konto: speichern erlaubt? (geprueft, frei und anders als bisher)
    static func canSaveName(input: String, current: String?, check: NameCheck) -> Bool {
        let v = normalize(input)
        return !v.isEmpty && v != current && check.isOK
    }
    /// Rueckfrage noetig? Nur beim Aendern eines bestehenden Namens, wenn schon Seiten oeffentlich sind (alte Links brechen)
    static func needsNameChangeConfirm(input: String, current: String?, publishedCount: Int) -> Bool {
        guard let c = current else { return false }
        return normalize(input) != c && publishedCount > 0
    }

    /// Seitenkopf nach einer Aenderung im Dialog: Felder fuer GET /api/notes/{id} nachfuehren
    static func pageFields(_ s: PublishState) -> [String: Any] {
        ["published": s.published, "public_url": (s.published ? s.url : nil) ?? NSNull(), "published_via": s.via?.dict ?? NSNull()]
    }
}
