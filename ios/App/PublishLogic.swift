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

/// Was der Dialog zeigt - abgeleitet aus Zustand und der gerade getippten Adresse
struct PublishDisplay: Equatable {
    enum MainTitle: Equatable { case isPublic, alsoOwnAddress }
    enum Withdrawn: Equatable { case byAdmin(Double), byUser(Double) }
    enum Preview: Equatable { case url(String), pendingChange }

    var mainTitle: MainTitle
    var toggleOn: Bool
    var toggleDisabled: Bool
    /// Admin hat das Veroeffentlichen abgeschaltet
    var serverDisabled: Bool
    /// Schon oeffentlich ueber eine Elternseite (nur, solange die Seite selbst nicht veroeffentlicht ist)
    var via: PubLink?
    var withdrawn: Withdrawn?
    var slugChanged: Bool
    var showChangeSlug: Bool
    var changeSlugEnabled: Bool
    /// nil: keine Vorschau (veroeffentlicht und unveraendert - die Adresse steht dann im Link-Bereich)
    var preview: Preview?
    /// Link, Kopieren/Oeffnen und "Veroeffentlichung beenden"
    var showLink: Bool
    var link: String?
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
        slugChanged = s.published && slug != s.slug
        showChangeSlug = s.published
        changeSlugEnabled = slugChanged && PublishLogic.slugLooksValid(slug)
        if slugChanged { preview = .pendingChange }
        else if s.published { preview = nil }
        else { preview = .url(PublishLogic.previewURL(s, slug: slug, server: server)) }
        showLink = s.published && s.url != nil
        link = s.published ? s.url : nil
        profileReplaces = s.profileOther?.title
        isHome = s.home && s.published
    }
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
