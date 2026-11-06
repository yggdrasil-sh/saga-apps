import Foundation

/// Reine Logik der Mac-Huelle (Desktop-Huelle P2): Deep-Links -> Web-Routen, Origin-Pruefung der Bruecke,
/// Pruefung von Nachrichten aus dem Web. Ohne AppKit/WebKit, damit Tests/Logic/run.sh sie mit swiftc pruefen kann.
/// Vertrag: docs/APP-API-HOST.md im Server-Repo (Abschnitte 3, 6, 7, 8).
enum ShellLogic {
    /// Fähigkeiten, die die Mac-Huelle meldet. Kein `notify`: Mitteilungen kommen am Mac ueber APNs (sonst doppelt).
    static let caps = ["badge", "widgets", "openExternal", "settings", "token"]

    /// Link-Schemata, die die Huelle annimmt. `saga-test` registriert nur die Test-Huelle (eigene App-ID), damit
    /// Links aus Widgets/Spotlight weiter bei der normalen Saga-App landen; Inhalt wie `saga://`.
    static let linkSchemes: Set<String> = ["saga", "hlidskjalf", "saga-test"]

    /// Bereiche, die `saga://settings/<bereich>` oeffnen darf (wie static/host.js)
    static let settingsAreas: Set<String> = ["account", "security", "push", "notify", "pages", "calendar", "bookmarks",
                                             "immich", "nextcloud", "apps", "api", "backup", "help", "lang"]

    enum Link: Equatable {
        case path(String)                 // Web-Route der eigenen Origin
        case auth(code: String?)          // Rueckkehr der Browser-Anmeldung (nur waehrend einer Anmeldung gueltig)
        case pair(URL)                    // QR-Kopplung (immer mit Rueckfrage)
        case none
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
            let p = item("db") ?? item("parent")
            return isID(p) ? .path("/notes#" + p!) : .path("/notes?new=blank")
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
}
