import Foundation

// Saga 2.5 Titelbild-Galerie (Server: GET /api/covers, docs/APP-API.md "Titelbild-Galerie"): reine Logik ohne SwiftUI,
// mit swiftc pruefbar (Tests/Logic/CoverGalleryTests.swift). Liste lesen und pruefen, nach Gruppen ordnen, Auswahl
// setzen (cover + empfohlene cover_pos), Zufall, "Ueber dieses Bild". Zusaetzliche Felder des Servers werden ignoriert.

enum CoverGalleryLogic {
    static let listPath = "api/covers"
    /// Vorschaubreite, falls der Server keine "thumb"-Adresse mitschickt (wie das Web)
    static let thumbWidth = 384

    struct Cover: Equatable, Identifiable {
        var id: String
        var url: String                    // /covers/<id>.jpg - genau dieser Wert kommt in note.cover
        var thumb: String                  // verkleinert (?w=384)
        var title: String
        var artist: String
        var date: String
        var sourceURL: String              // Quelle (nur http/https), sonst leer
        var source: String                 // Nachweis, z. B. "KI-generiert für Saga"
        var group: String
        var pos: Double                    // empfohlene Position 0 (oben) ... 100 (unten)
        var builtin: Bool                  // mitgeliefert (gibt es auf jedem Saga-Server gleich)

        /// "Kuenstler, Datum" wie im Web (leere Teile fallen weg)
        var credit: String { [artist, date].filter { !$0.isEmpty }.joined(separator: ", ") }
        /// Bedienungshilfen und Tooltip wie im Web: "Titel – Kuenstler, Datum"
        var label: String { credit.isEmpty ? title : title + " – " + credit }
    }

    struct Group: Equatable, Identifiable {
        var id: String                     // "" = ohne Gruppe
        var label: String
        var covers: [Cover]
    }

    struct Listing: Equatable {
        var covers: [Cover]
        var groups: [(id: String, label: String)]
        var source: String                 // Nachweis der mitgelieferten Kunstwerke (fuer den Hinweis unten)
        var license: String
        static func == (a: Listing, b: Listing) -> Bool {
            a.covers == b.covers && a.source == b.source && a.license == b.license &&
                a.groups.map { $0.id + "\u{1}" + $0.label } == b.groups.map { $0.id + "\u{1}" + $0.label }
        }
    }

    // MARK: Pfade

    /// Galerie-Pfad wie im Server (saga/covers.py PATH_RE): /covers/<id>.jpg, id [a-z0-9][a-z0-9-]{0,63}
    static func isGalleryPath(_ s: String?) -> Bool { id(of: s) != nil }

    static func id(of s: String?) -> String? {
        guard let s, s.hasPrefix("/covers/"), s.hasSuffix(".jpg") else { return nil }
        let id = String(s.dropFirst("/covers/".count).dropLast(".jpg".count))
        return validID(id) ? id : nil
    }

    static func validID(_ id: String) -> Bool {
        guard let f = id.unicodeScalars.first, id.unicodeScalars.count <= 64 else { return false }
        func ok(_ c: Unicode.Scalar) -> Bool { ("a"..."z").contains(c) || ("0"..."9").contains(c) }
        return ok(f) && id.unicodeScalars.allSatisfy { ok($0) || $0 == "-" }
    }

    /// Vorschauadresse: die des Servers, wenn sie zum Bild gehoert (gleicher Pfad mit Abfrage), sonst ?w=384
    static func thumb(_ raw: Any?, url: String) -> String {
        if let t = raw as? String, t == url || t.hasPrefix(url + "?") { return t }
        return url + "?w=\(thumbWidth)"
    }

    /// Antwort einordnen: true = Galerie da, false = aelterer Server (404) bzw. nicht erlaubt (403), nil = spaeter wieder
    static func offered(code: Int) -> Bool? {
        switch code {
        case 200..<300: return true
        case 403, 404: return false
        default: return nil
        }
    }

    // MARK: Lesen

    static func pos(_ v: Any?) -> Double {
        if let x = v as? NSNumber, CFGetTypeID(x) == CFBooleanGetTypeID() { return 50 }    // JSON true/false ist keine Position
        let d: Double?
        switch v {
        case let x as NSNumber: d = x.doubleValue
        case let x as Double: d = x
        case let x as Int: d = Double(x)
        default: d = nil
        }
        guard let d, d.isFinite else { return 50 }
        return min(100, max(0, d))
    }

    private static func str(_ v: Any?) -> String { ((v as? String) ?? "").trimmingCharacters(in: .whitespacesAndNewlines) }

    private static func web(_ v: Any?) -> String {
        let s = str(v)
        guard let u = URL(string: s), let sc = u.scheme?.lowercased(), sc == "https" || sc == "http", u.host?.isEmpty == false else { return "" }
        return s
    }

    /// GET /api/covers lesen; nil = keine lesbare Antwort. Eintraege ohne gueltigen Pfad fallen weg, doppelte IDs einmal.
    static func parse(_ data: Data) -> Listing? {
        guard let j = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any], let arr = j["covers"] as? [Any] else { return nil }
        var seen = Set<String>(), out: [Cover] = []
        for case let c as [String: Any] in arr {
            let url = str(c["url"])
            guard let id = id(of: url), !seen.contains(id) else { continue }
            seen.insert(id)
            let title = str(c["title"])
            out.append(Cover(id: id, url: url, thumb: thumb(c["thumb"], url: url), title: title.isEmpty ? id : title,
                             artist: str(c["artist"]), date: str(c["date"]), sourceURL: web(c["source_url"]), source: str(c["source"]),
                             group: str(c["group"]), pos: pos(c["pos"]), builtin: (c["builtin"] as? Bool) ?? false))
        }
        var groups: [(id: String, label: String)] = []
        for case let g as [String: Any] in (j["groups"] as? [Any] ?? []) {
            let id = str(g["id"])
            guard !groups.contains(where: { $0.id == id }) else { continue }
            groups.append((id, str(g["label"])))
        }
        return Listing(covers: out, groups: groups, source: str(j["source"]), license: str(j["license"]))
    }

    // MARK: Auswahl

    /// Auswahl der Seite: fremde Instanz (remote) nur mitgelieferte Bilder (Uploads des Admins gibt es dort nicht)
    static func choosable(_ l: Listing, remote: Bool) -> [Cover] { remote ? l.covers.filter(\.builtin) : l.covers }

    /// Nach Gruppen in der Reihenfolge des Servers; Gruppen, die der Server nicht nennt, dahinter (Name = ID),
    /// Bilder ohne Gruppe zuletzt unter `other` ("Weitere"). Leere Gruppen fallen weg.
    static func grouped(_ l: Listing, remote: Bool, other: String) -> [Group] {
        let list = choosable(l, remote: remote)
        var order = l.groups
        for c in list where !order.contains(where: { $0.id == c.group }) { order.append((c.group, c.group)) }
        if let i = order.firstIndex(where: { $0.id == "" }) { let e = order.remove(at: i); order.append(e) }   // ohne Gruppe zuletzt
        return order.compactMap { g in
            let items = list.filter { $0.group == g.id }
            guard !items.isEmpty else { return nil }
            let label = g.label.isEmpty ? (g.id.isEmpty ? other : g.id) : g.label
            return Group(id: g.id, label: label, covers: items)
        }
    }

    /// Felder fuer PATCH /api/notes/{id}: Pfad und empfohlene Position. nil = schon das Titelbild (nicht speichern).
    static func fields(_ c: Cover, current: String?) -> [String: Any]? {
        guard c.url != current else { return nil }
        return ["cover": c.url, "cover_pos": c.pos]
    }

    /// "Zufaellig" wie im Web: ein anderes als das jetzige; `roll` waehlt einen Index 0..<n (Tests: fest)
    static func random(_ l: Listing, remote: Bool, current: String?, roll: (Int) -> Int = { Int.random(in: 0..<$0) }) -> Cover? {
        let list = choosable(l, remote: remote).filter { $0.url != current }
        guard !list.isEmpty else { return nil }
        return list[min(max(0, roll(list.count)), list.count - 1)]
    }

    /// "Ueber dieses Bild": Eintrag zum jetzigen Titelbild. .gone = Galerie-Pfad, aber nicht (mehr) in der Auswahl
    /// (ausgeblendet/entfernt); .none = kein Galerie-Bild (Menuepunkt nicht anbieten)
    enum About: Equatable { case cover(Cover), gone, none }
    static func about(_ l: Listing?, current: String?) -> About {
        guard isGalleryPath(current) else { return .none }
        if let c = l?.covers.first(where: { $0.url == current }) { return .cover(c) }
        return .gone
    }
}
