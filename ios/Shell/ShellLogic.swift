import Foundation

/// Reine Logik der Mac-Huelle (Desktop-Huelle P2): Deep-Links -> Web-Routen, Origin-Pruefung der Bruecke,
/// Pruefung von Nachrichten aus dem Web. Ohne AppKit/WebKit, damit Tests/Logic/run.sh sie mit swiftc pruefen kann.
/// Vertrag: docs/APP-API-HOST.md im Server-Repo (Abschnitte 3, 6, 7, 8).
enum ShellLogic {
    /// Fähigkeiten, die die Mac-Huelle meldet. Kein `notify`: Mitteilungen kommen am Mac ueber APNs (sonst doppelt).
    static let caps = ["badge", "widgets", "openExternal", "settings", "token"]

    /// Link-Schemata, die die Huelle annimmt (registriert: SAGA_SHELL_URL_SCHEME/_ALT = saga, hlidskjalf).
    /// `saga-test` nur fuer eine Test-Huelle mit eigener App-ID (Config/Base.xcconfig); Inhalt wie `saga://`.
    static let linkSchemes: Set<String> = ["saga", "hlidskjalf", "saga-test"]

    /// Bereiche, die `saga://settings/<bereich>` oeffnen darf (wie static/host.js)
    static let settingsAreas: Set<String> = ["account", "security", "push", "notify", "pages", "calendar", "bookmarks",
                                             "immich", "nextcloud", "apps", "api", "backup", "help", "lang"]

    enum Link: Equatable {
        case path(String)                 // Web-Route der eigenen Origin
        case auth(code: String?)          // Rueckkehr der Browser-Anmeldung (nur waehrend einer Anmeldung gueltig)
        case pair(URL)                    // QR-Kopplung (immer mit Rueckfrage)
        case create(NewEntry)             // saga://new?db=&view=&k= bzw. ?parent= - die Huelle legt nativ an (Etappe 2)
        case none
    }

    /// Neuer Eintrag aus einem Link (Datenbank-Widget "+", Seiten-Widget): `key` = `k` (AppConfig.linkSecret)
    struct NewEntry: Equatable {
        var db: Int?
        var view: String?
        var parent: Int?
        var key: String?
        /// Ziel, falls nicht angelegt wird (abgelehnt, Fehler): die Datenbank bzw. Elternseite
        var fallbackPath: String { "/notes#\(db ?? parent ?? 0)" }
        /// Ohne Rueckfrage nur mit dem Geheimnis der eigenen Widgets (wie RootView.openLink)
        func trusted(secret: String) -> Bool { db != nil && !secret.isEmpty && key == secret }
    }

    private static func isID(_ s: String?) -> Bool {
        guard let s, (1...12).contains(s.count) else { return false }
        return s.allSatisfy { $0.isASCII && $0.isNumber }
    }

    /// Deep-Link -> Aktion (Tabelle docs/APP-API-HOST.md Abschnitt 6, gleiche Ergebnisse wie `sagaHost.route`)
    static func route(_ link: URL) -> Link {
        guard let scheme = link.scheme?.lowercased(), linkSchemes.contains(scheme) else { return .none }
        let host = (link.host ?? "").lowercased()
        let arg = link.path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        let q = URLComponents(url: link, resolvingAgainstBaseURL: false)?.queryItems ?? []
        func item(_ n: String) -> String? { q.first(where: { $0.name == n })?.value }
        switch host {
        case "", "today": return .path("/")
        case "page": return isID(arg) ? .path("/notes#" + arg) : .none
        case "tasks": return .path("/notes?v=tasks")
        case "events": return .path("/notes?v=events")
        case "chats": return .path("/chats")
        case "new-task": return .path("/notes?v=tasks&new=task")
        case "new-page": return .path("/notes?new=blank")
        case "new":
            let db = item("db"), parent = item("parent")
            if isID(db) {
                let v = item("view").flatMap { $0.count <= 64 && $0.allSatisfy { $0.isASCII && ($0.isLetter || $0.isNumber || "-_".contains($0)) } ? $0 : nil }
                return .create(NewEntry(db: Int(db!), view: v, parent: nil, key: item("k")))
            }
            if isID(parent) { return .create(NewEntry(db: nil, view: nil, parent: Int(parent!), key: item("k"))) }
            return .path("/notes?new=blank")
        case "settings": return .path("/notes?settings=" + (settingsAreas.contains(arg) ? arg : "account"))
        case "auth": return .auth(code: item("code"))
        case "pair": return .pair(link)
        default: return .none
        }
    }

    /// Ursprung als Text (schema://host[:port]), Standard-Ports weggelassen
    static func origin(scheme: String?, host: String?, port: Int?) -> String? {
        guard let s = scheme?.lowercased(), let h = host?.lowercased(), !s.isEmpty, !h.isEmpty else { return nil }
        let std = (s == "https" && port == 443) || (s == "http" && port == 80)
        if let p = port, p > 0, !std { return "\(s)://\(h):\(p)" }
        return "\(s)://\(h)"
    }
    static func origin(_ u: URL?) -> String? { u.flatMap { origin(scheme: $0.scheme, host: $0.host, port: $0.port) } }

    /// Gehoert die Adresse zur Server-Origin (Schema, Host, Port)?
    static func isOwn(_ u: URL?, server: URL) -> Bool {
        guard let a = origin(u), let b = origin(server) else { return false }
        return a == b
    }

    /// Pfad + Abfrage + Anker einer eigenen Adresse (fuer `next` nach dem Erneuern der Sitzung)
    static func pathOf(_ u: URL) -> String {
        guard var c = URLComponents(url: u, resolvingAgainstBaseURL: false) else { return "/" }
        c.scheme = nil; c.host = nil; c.port = nil; c.user = nil; c.password = nil
        let s = c.string ?? "/"
        return s.hasPrefix("/") ? s : "/"
    }

    /// Navigation zu /login der eigenen Origin: abgelaufene Sitzung (-> erneuern) oder Abmelden (bye=1)
    enum LoginNav: Equatable { case none, expired(next: String), loggedOut }
    static func loginNav(_ u: URL, server: URL) -> LoginNav {
        guard isOwn(u, server: server), u.path == "/login" || u.path == "/login/" else { return .none }
        let q = URLComponents(url: u, resolvingAgainstBaseURL: false)?.queryItems ?? []
        if q.contains(where: { $0.name == "bye" }) { return .loggedOut }
        let n = q.first(where: { $0.name == "next" })?.value ?? "/"
        // nur eigene Pfade (kein //fremd, keine volle Adresse)
        return .expired(next: n.hasPrefix("/") && !n.hasPrefix("//") && !n.hasPrefix("/\\") ? n : "/")
    }

    /// `openExternal`: nur http(s)/mailto, nie die eigene Origin, nie App-Schemata (docs Abschnitt 8)
    static func externalURL(_ s: Any?, server: URL) -> URL? {
        guard let t = s as? String, !t.isEmpty, t.count <= 4000, let u = URL(string: t), let sch = u.scheme?.lowercased() else { return nil }
        if sch == "mailto" { return u }
        guard sch == "http" || sch == "https", u.host != nil, !isOwn(u, server: server) else { return nil }
        return u
    }

    /// Zahl fuer das Dock (`badge`): 0 = leer, sonst gekappt
    static func badgeLabel(_ n: Any?) -> String {
        let v = (n as? Int) ?? (n as? Double).map { Int($0) } ?? (n as? NSNumber)?.intValue ?? 0
        if v <= 0 { return "" }
        return v > 99999 ? "99999" : String(v)
    }

    /// App-Schluessel aus der Web-Nachricht `token`
    static func appKey(_ s: Any?) -> String? {
        guard let t = s as? String, t.hasPrefix("hk_"), t.count < 120,
              t.dropFirst(3).allSatisfy({ $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "-" || $0 == "_") }) else { return nil }
        return t
    }

    /// Skript zum Dokumentbeginn: Faehigkeiten melden (docs Abschnitt 1)
    static func hostInfoScript(version: String) -> String {
        let v = version.filter { $0.isASCII && ($0.isLetter || $0.isNumber || ".-+".contains($0)) }
        let c = caps.map { "'\($0)'" }.joined(separator: ", ")
        return "window.sagaHostInfo = { kind: 'mac', version: '\(v)', caps: [\(c)] };"
    }

    /// User-Agent-Zusatz (WKWebView haengt ihn an den Standard an)
    static func userAgent(version: String) -> String { "SagaApp/\(version) (mac-shell)" }

    /// Ereignis an das Web als JSON-Text (fuer `sagaHost.receive(...)`)
    static func eventJS(_ evt: [String: Any]) -> String? {
        guard JSONSerialization.isValidJSONObject(evt), let d = try? JSONSerialization.data(withJSONObject: evt),
              let s = String(data: d, encoding: .utf8) else { return nil }
        return "window.sagaHost && window.sagaHost.receive(\(s))"
    }

    // MARK: Etappe 2

    /// Kopplungslink aus dem QR-Code: `https://server/app/pair?c=CODE` oder `<schema>://pair?u=https://server&c=CODE`
    /// (wie Store.parsePairLink, dazu das Test-Schema). Nur https (bzw. was `allowed` erlaubt).
    static func pairLink(_ s: String, allowed: (URL) -> Bool) -> (server: URL, code: String)? {
        guard let u = URLComponents(string: s.trimmingCharacters(in: .whitespacesAndNewlines)) else { return nil }
        let q = u.queryItems ?? []
        guard let code = q.first(where: { $0.name == "c" })?.value, (20...200).contains(code.count),
              code.allSatisfy({ $0.isASCII && ($0.isLetter || $0.isNumber || "-_.".contains($0)) }) else { return nil }
        var srv: URL?
        let us = u.scheme?.lowercased()
        if us == "https" || us == "http", u.path == "/app/pair", let host = u.host, !host.isEmpty {
            var b = URLComponents(); b.scheme = us; b.host = host.lowercased(); b.port = u.port
            srv = b.url
        } else if let sc = u.scheme?.lowercased(), linkSchemes.contains(sc), u.host == "pair",
                  let v = q.first(where: { $0.name == "u" })?.value, var c = URLComponents(string: v),
                  c.scheme?.lowercased() == "https" || c.scheme?.lowercased() == "http", let h = c.host, !h.isEmpty {
            c.scheme = c.scheme?.lowercased(); c.host = h.lowercased()
            c.path = ""; c.query = nil; c.fragment = nil; c.user = nil; c.password = nil
            srv = c.url
        }
        guard let s = srv, allowed(s) else { return nil }
        return (s, code)
    }

    /// Hinweis in der Kopplungs-Rueckfrage: was mit der bisherigen Anmeldung passiert
    enum PairNotice: Equatable { case none, otherServer(String), sameServer }
    static func pairNotice(target: URL, current: URL?, signedIn: Bool) -> PairNotice {
        guard signedIn, let c = current else { return .none }
        return origin(c) == origin(target) ? .sameServer : .otherServer(c.host ?? "")
    }

    /// Vorgaben fuer einen neuen Datenbank-Eintrag aus den Filtern der Ansicht ("Status ist Offen" -> Status = Offen),
    /// wie DBQuery.defaults. `schema` = `schema` aus GET /api/app/rows/<db>; ohne passende Ansicht die erste.
    static func rowDefaults(schema: [String: Any]?, viewID: String?) -> [String: Any] {
        let props = (schema?["props"] as? [[String: Any]]) ?? []
        let views = (schema?["views"] as? [[String: Any]]) ?? []
        let view = views.first { ($0["id"] as? String) == viewID && viewID != nil } ?? views.first
        var types = [String: String]()
        for p in props { if let id = p["id"] as? String { types[id] = p["type"] as? String ?? "" } }
        var d: [String: Any] = [:]
        for f in view?["filters"] as? [[String: Any]] ?? [] {
            let op = f["op"] as? String
            guard op == "is" || op == "eq" || op == "checked", let pid = f["pid"] as? String, pid != "title",
                  let t = types[pid] else { continue }
            if op == "checked" { d[pid] = true } else if let v = f["value"] as? String, !v.isEmpty { d[pid] = t == "multi_select" ? [v] : v }
        }
        return d
    }

    /// Spotlight: eine Seite der Liste (GET /api/app/pages)
    struct IndexPage: Equatable { var id: Int; var title: String; var icon: String; var kind: String; var parent: Int? }
    struct IndexEntry: Equatable { var id: String; var title: String; var desc: String; var display: String? }
    /// Eintraege fuer die Systemsuche: Titel, Pfad der Elternseiten (sonst "Seite"/"Datenbank"), Emoji vor dem Titel.
    /// Hoechstens `limit` Eintraege (Seiten und Datenbanken vor Datenbank-Zeilen).
    static func indexEntries(_ pages: [IndexPage], untitled: String, page: String, database: String, limit: Int = 20000) -> [IndexEntry] {
        var by = [Int: IndexPage](minimumCapacity: pages.count)
        for p in pages where by[p.id] == nil { by[p.id] = p }
        func label(_ p: IndexPage) -> String {
            let t = p.title.trimmingCharacters(in: .whitespacesAndNewlines)
            return t.isEmpty ? untitled : String(t.prefix(200))
        }
        var cache = [Int: String]()
        func path(_ p: IndexPage) -> String {
            guard let pid = p.parent, by[pid] != nil else { return "" }
            if let c = cache[pid] { return c }
            var out: [String] = [], cur: Int? = pid, n = 0
            while let c = cur, let pp = by[c], n < 20 { out.insert(label(pp), at: 0); cur = pp.parent; n += 1 }
            let s = out.joined(separator: " / "); cache[pid] = s
            return s
        }
        var seen = Set<Int>(), top: [IndexEntry] = [], rows: [IndexEntry] = []
        for p in pages where seen.insert(p.id).inserted && p.id > 0 {
            let pth = path(p), l = label(p)
            let emoji = !p.icon.isEmpty && p.icon.count <= 4 && !p.icon.hasPrefix("/")
            let e = IndexEntry(id: "page-\(p.id)", title: l, desc: pth.isEmpty ? (p.kind == "database" ? database : page) : pth,
                               display: emoji ? p.icon + " " + l : nil)
            if p.parent.flatMap({ by[$0] })?.kind == "database" { rows.append(e) } else { top.append(e) }
        }
        return Array((top + rows).prefix(limit))
    }
    /// "page-12" aus einem Spotlight-Treffer -> Web-Route
    static func spotlightPath(_ ident: String?) -> String? {
        guard let s = ident, s.hasPrefix("page-"), isID(String(s.dropFirst(5))) else { return nil }
        return "/notes#" + s.dropFirst(5)
    }

    /// Mitteilung angeklickt: `url` aus der APNs-Mitteilung (Pfad relativ zur Server-Origin) -> eigener Pfad.
    /// Volle Adressen nur der eigenen Origin; alles andere -> Startseite.
    static func notificationPath(_ url: Any?, server: URL) -> String {
        guard let s = url as? String, !s.isEmpty, s.count <= 2000 else { return "/" }
        if s.hasPrefix("/") && !s.hasPrefix("//") && !s.hasPrefix("/\\") { return s }
        if s.hasPrefix("?") { return "/" + s }
        if let u = URL(string: s), isOwn(u, server: server) { return pathOf(u) }
        return "/"
    }
    /// Klingelnder Anruf (Server-Art `call` bzw. `call.group`): Dock-Aufmerksamkeit
    static func isRinging(kind: Any?) -> Bool { (kind as? String) == "call" || (kind as? String) == "call.group" }

    // MARK: Umstellung von der nativen Mac-App (P3)

    /// Einmaliger Hinweis beim ersten Start der Huelle, wenn vorher die native Mac-App angemeldet war (gleiche App-ID:
    /// derselbe Container und Geraete-Schluessel, die Anmeldung geht ueber). Die Huelle selbst merkt sich ein Konto erst
    /// nach eigener Anmeldung (`shellAccountName`) - fehlt das, stammt ein vorhandener Geraete-Schluessel von der nativen App.
    static func showsSwitchNotice(noticeShown: Bool, hasDeviceToken: Bool, hasShellAccount: Bool) -> Bool {
        !noticeShown && hasDeviceToken && !hasShellAccount
    }
}
