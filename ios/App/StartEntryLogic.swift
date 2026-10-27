import Foundation

// fix27 E2: eigene Startseite als Kontoeinstellung (GET/PATCH /api/account "start_page", Server 2.5) - die bisherige
// Startseite dieses Geraets (hk.homePage) bleibt Zwischenspeicher und Rueckfall fuer aeltere Server.
enum StartPageLogic {
    /// Antwort von GET /api/account: (unterstuetzt, Seiten-ID oder nil). Fehlt das Feld: aelterer Server.
    static func parse(_ j: [String: Any]) -> (supported: Bool, page: Int?) {
        guard j.keys.contains("start_page") else { return (false, nil) }
        if let n = j["start_page"] as? NSNumber, CFGetTypeID(n) != CFBooleanGetTypeID(), n.intValue > 0 { return (true, n.intValue) }
        return (true, nil)
    }

    enum Sync: Equatable {
        case keep                 // aelterer Server: nur Geraete-Startseite
        case adopt(Int?)          // Server-Wert uebernehmen (nil = keine Startseite)
        case upload(Int)          // einmalig: bisherige Geraete-Startseite ins Konto uebernehmen
    }
    /// Abgleich nach GET /api/account. `migrated`: die Geraete-Startseite wurde fuer dieses Konto schon abgeglichen.
    static func sync(supported: Bool, server: Int?, local: Int?, migrated: Bool) -> Sync {
        guard supported else { return .keep }
        if let s = server { return .adopt(s) }
        if let l = local, l > 0, !migrated { return .upload(l) }
        return .adopt(nil)
    }

    /// Nach dem Abgleich beim Kaltstart: Auswahl nachziehen, solange die Person noch nichts anderes geoeffnet hat
    /// (Auswahl leer oder noch die zwischengespeicherte Startseite). nil-Rueckgabe = Auswahl unveraendert lassen.
    static func reselect(selection: Int?, before: Int?, after: Int?) -> Int?? {
        guard before != after, selection == nil || selection == before else { return nil }
        return .some(after)
    }

    /// Seitenmenue: Eintrag anbieten? Mit Konto-Startseite nur Seiten dieses Servers (keine foederierten, negative IDs)
    static func offered(id: Int, supported: Bool) -> Bool { supported ? id > 0 : true }
}

// fix27 E4: aus einer Eintrags-Vorlage angelegter Eintrag (POST /api/notes/{tpl}/duplicate liefert "fresh" + "placeholder"):
// leerer Titel mit Platzhalter; unveraendert geschlossen -> POST /api/notes/{id}/discard {"fresh": ...} loescht endgueltig.
// Ob wirklich nichts geaendert wurde, prueft der Server (Fingerabdruck, 15 min, nur die anlegende Person) - sonst 409.
struct FreshEntry: Equatable {
    var token: String
    var placeholder: String
    /// Titel beim Anlegen: leer bzw. Titel einer Schaltflaeche ("Tagebuch {datum}") - unveraendert = verwerfen (wie Web)
    var title: String = ""
}

enum FreshEntryLogic {
    /// Antwort von duplicate -> (neue ID, FreshEntry falls der Server sie als "frisch" markiert hat)
    static func parse(_ d: Data) -> (id: Int, fresh: FreshEntry?)? {
        guard let j = try? JSONSerialization.jsonObject(with: d) as? [String: Any], let id = (j["id"] as? NSNumber)?.intValue else { return nil }
        guard let tok = j["fresh"] as? String, (8...64).contains(tok.count),
              tok.allSatisfy({ $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "-" || $0 == "_") }) else { return (id, nil) }
        let ph = (j["placeholder"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        return (id, FreshEntry(token: tok, placeholder: String(ph.prefix(500))))
    }

    /// Beim Schliessen verwerfen? Nur mit Markierung, Titel wie beim Anlegen (leer bzw. Titel der Schaltflaeche), ohne
    /// ungesendete Aenderungen und wenn die Seite nirgends mehr offen ist (Peek gewechselt/geschlossen, aus dem Stapel genommen)
    static func shouldDiscard(fresh: FreshEntry?, title: String, unsaved: Bool, stillOpen: Bool) -> Bool {
        guard let fresh else { return false }
        let ws = CharacterSet.whitespacesAndNewlines
        return title.trimmingCharacters(in: ws) == fresh.title.trimmingCharacters(in: ws) && !unsaved && !stillOpen
    }

    static func discardPath(_ id: Int) -> String { "api/notes/\(id)/discard" }
    static func discardBody(_ f: FreshEntry) -> [String: Any] { ["fresh": f.token] }

    enum Outcome: Equatable { case deleted, kept, failed }
    /// HTTP-Status + Antwort von discard: 200 deleted:true -> geloescht; 409 (geaendert/abgelaufen) und 404 -> bleibt
    /// (Markierung vergessen); sonst (offline, 5xx) -> spaeter nicht erneut versuchen, Eintrag bleibt
    static func outcome(status: Int, _ d: Data?) -> Outcome {
        if (200..<300).contains(status), let d, let j = try? JSONSerialization.jsonObject(with: d) as? [String: Any], j["deleted"] as? Bool == true { return .deleted }
        if status == 409 || status == 404 { return .kept }
        return .failed
    }

    /// Platzhalter fuer den Titel: Vorlagenname, sonst der uebliche
    static func placeholder(_ f: FreshEntry?, fallback: String) -> String {
        guard let p = f?.placeholder, !p.isEmpty else { return fallback }
        return p
    }
}
