import Foundation

/// Gemeinsame Einstellungen fuer App, Widget und Teilen-Erweiterung (App Group).
/// App-ID, App-Gruppe und optionaler Vorgabe-Server kommen aus der Info.plist (gesetzt ueber Config/*.xcconfig).
enum AppConfig {
    private static func info(_ key: String) -> String {
        let v = (Bundle.main.object(forInfoDictionaryKey: key) as? String ?? "").trimmingCharacters(in: .whitespaces)
        return v.hasPrefix("$(") ? "" : v                                  // nicht ersetzte Variable = nicht gesetzt
    }
    /// App-ID der Haupt-App (in Erweiterungen ohne .widget/.share/.sharemac)
    static let mainBundleID: String = {
        let id = Bundle.main.bundleIdentifier ?? "io.github.yggdrasil-sh.saga"
        for s in [".widget", ".sharemac", ".share"] where id.hasSuffix(s) { return String(id.dropLast(s.count)) }
        return id
    }()
    static let appGroup: String = { let g = info("SagaAppGroup"); return g.isEmpty ? "group." + mainBundleID : g }()
    /// Vorbelegter Server (optional, SAGA_DEFAULT_SERVER) - sonst leer: Server eintragen oder per QR-Code koppeln
    static let defaultServer = info("SagaDefaultServer")
    /// Fruehere Adressen desselben Servers -> automatisch auf die neue umstellen (Daten und Anmeldung bleiben gleich).
    /// Optional ueber SAGA_MOVED_SERVERS ("alt=neu alt2=neu2").
    static let movedServers: [String: String] = Dictionary(
        info("SagaMovedServers").split(separator: " ").compactMap { p -> (String, String)? in
            let kv = p.split(separator: "=", maxSplits: 1).map { $0.trimmingCharacters(in: CharacterSet(charactersIn: "/")) }
            return kv.count == 2 && !kv[0].isEmpty && !kv[1].isEmpty ? (kv[0], kv[1]) : nil
        }, uniquingKeysWith: { a, _ in a })
    static var store: UserDefaults { UserDefaults(suiteName: appGroup) ?? .standard }

    /// Gemeinsame Schluesselbund-Gruppe fuer den App-Schluessel (SAGA_KEYCHAIN_GROUP, siehe Config/Base.xcconfig)
    static let keychainGroup = info("SagaKeychainGroup")
    /// Nur fuer die App (Geraete-Schluessel): ihre eigene Kennung TEAMID.<App-ID> - nicht fuer Widget/Teilen lesbar
    static var privateKeychainGroup: String? {
        keychainGroup.hasSuffix(".shared") && keychainGroup.count > 7 ? String(keychainGroup.dropLast(7)) : nil
    }

    // MARK: Sprache

    /// Accept-Language aus der Sprache, in der die App gerade laeuft (Bundle.preferredLocalizations) - damit
    /// Fehlertexte des Servers passen. Unbekannte Sprache -> Englisch.
    /// Sprachen mit vollstaendigen Texten (Shared/Localizable.xcstrings, CFBundleLocalizations)
    static let appLanguages = ["de", "en", "es", "th", "ru"]
    static func acceptLanguage(_ preferred: [String]) -> String {
        let l = preferred.first.map { String($0.prefix(2)).lowercased() } ?? "en"
        return appLanguages.contains(l) ? l : "en"
    }
    static var appLanguage: String { acceptLanguage(Bundle.main.preferredLocalizations) }

    // MARK: Server

    /// Ursprung einer Adresse (schema://host[:port]) - Schluessel gelten nur fuer den Server, von dem sie stammen
    static func origin(_ u: URL) -> String {
        var o = (u.scheme ?? "").lowercased() + "://" + (u.host ?? "").lowercased()
        if let p = u.port { o += ":\(p)" }
        return o
    }
    /// Anzeige einer Server-Adresse: Host, mit Port, falls einer angegeben ist (z. B. "127.0.0.1:18090")
    static func displayHost(_ u: URL) -> String {
        guard let h = u.host, !h.isEmpty else { return u.absoluteString }
        let host = h.contains(":") ? "[\(h)]" : h                         // IPv6 in Klammern, sonst ist der Port nicht abgrenzbar
        return u.port.map { "\(host):\($0)" } ?? host
    }
    /// Erlaubte Server-Adresse: nur https. http nur in Entwickler-Builds: eigener Rechner und private Netze
    /// (Testserver im LAN, z. B. 192.168.x.y); Release unveraendert.
    static func isAllowedServer(_ u: URL) -> Bool {
        guard let h = u.host?.lowercased(), !h.isEmpty, u.user == nil, u.password == nil else { return false }
        if u.scheme?.lowercased() == "https" { return true }
        #if DEBUG
        if u.scheme?.lowercased() == "http", PrivateNet.isPrivate(host: h) { return true }
        #endif
        return false
    }
    /// Eingabe/Link -> Server-Adresse (ohne / am Ende, ohne Abfrage); nil, wenn nicht erlaubt
    static func normalizedServer(_ s: String) -> URL? {
        let t = s.trimmingCharacters(in: .whitespacesAndNewlines).trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        guard !t.isEmpty, let u = URL(string: t.contains("://") ? t : "https://" + t), isAllowedServer(u),
              u.query == nil, u.fragment == nil else { return nil }
        return u
    }

    private static let tokenOriginKey = "keyOrigin", deviceOriginKey = "deviceKeyOrigin"

    /// Eingetragene Server-Adresse als Text ("" = noch keiner) - fuer Eingabefelder
    static var serverString: String {
        var s = store.string(forKey: "server") ?? defaultServer
        if let n = movedServers[s.trimmingCharacters(in: CharacterSet(charactersIn: "/"))] {
            // derselbe Server unter neuer Adresse: die Schluessel gelten dort weiter
            if let old = URL(string: s).map(origin), let nu = URL(string: n) {
                for k in [tokenOriginKey, deviceOriginKey] where store.string(forKey: k) == old { store.set(origin(nu), forKey: k) }
            }
            s = n; store.set(n, forKey: "server")
        }
        return s
    }
    /// Gueltiger Server eingetragen?
    static var hasServer: Bool { URL(string: serverString).map { $0.host != nil } ?? false }
    /// Platzhalter ohne Server: .invalid loest nie auf -> Anfragen schlagen still fehl statt abzustuerzen
    private static let unconfigured = URL(string: "https://unconfigured.invalid")!

    static var server: URL {
        get { URL(string: serverString).flatMap { $0.host != nil ? $0 : nil } ?? unconfigured }
        set { store.set(newValue.absoluteString.trimmingCharacters(in: CharacterSet(charactersIn: "/")), forKey: "server") }
    }
    static var serverOrigin: String? { hasServer ? origin(server) : nil }

    // MARK: Schluessel (an den Server gebunden)

    /// Server, fuer den ein Schluessel ausgestellt wurde (nil: gilt fuer keinen Server)
    static func keyOrigin(device: Bool) -> String? {
        migrateKeyOrigin(device: device)
        return store.string(forKey: device ? deviceOriginKey : tokenOriginKey)
    }
    /// Einmalig (Migrationsmarke): ein Schluessel aus der Zeit vor der Bindung an den Server gehoert zum damals
    /// eingetragenen Server - mit dem wurde er geholt. Danach wird nie wieder automatisch gebunden: ein Schluessel ohne
    /// Ursprung (z. B. nach Abmelden/Serverwechsel liegen geblieben) gilt fuer keinen Server.
    /// Der Geraete-Schluessel wird nur in der App gelesen (Widget/Teilen haben keinen Zugriff darauf).
    static func migrateKeyOrigin(device: Bool) {
        let s = store
        KeyOriginMigration.run(s, originKey: device ? deviceOriginKey : tokenOriginKey, server: serverOrigin) {
            device ? Keychain.read(service: deviceKeyService, account: deviceKeyAccount) != nil
                   : (Keychain.read(service: tokenService, account: "app_key") != nil || !(s.string(forKey: "token") ?? "").isEmpty)
        }
    }
    /// Schluesselbund-Eintrag des Geraete-Schluessels (nur App)
    static let deviceKeyService = mainBundleID + ".device-login", deviceKeyAccount = "device_token"
    static func setKeyOrigin(device: Bool, _ o: String?) {
        KeyOriginMigration.set(store, originKey: device ? deviceOriginKey : tokenOriginKey, o)
    }
    /// Gehoert der Schluessel zum eingetragenen Server? Sonst wird er nie gesendet.
    static func keyMatchesServer(device: Bool) -> Bool {
        guard let s = serverOrigin else { return false }
        return keyOrigin(device: device) == s
    }

    private static let tokenService = mainBundleID + ".app-key"
    /// Gespeicherter App-Schluessel (Schluesselbund, gemeinsame Gruppe). Fruehere Fassungen hatten ihn im Klartext
    /// in den Einstellungen der App-Gruppe -> beim ersten Lesen in den Schluesselbund umziehen.
    private static var storedToken: String {
        if let t = Keychain.read(service: tokenService, account: "app_key") { return t }
        let legacy = store.string(forKey: "token") ?? ""
        if !legacy.isEmpty, Keychain.write(legacy, service: tokenService, account: "app_key", group: keychainGroup) == .shared {
            store.removeObject(forKey: "token")
        }
        return legacy
    }
    /// App-Zugangsschluessel (hk_...) - darf nur ablegen und "Heute" lesen. Nur fuer den Server, der ihn ausgestellt hat.
    static var token: String {
        get { keyMatchesServer(device: false) ? storedToken : "" }
        set {
            let v = newValue.trimmingCharacters(in: .whitespacesAndNewlines)
            let r = Keychain.write(v.isEmpty ? nil : v, service: tokenService, account: "app_key", group: keychainGroup)
            // Rueckfall (Schluesselbund-Gruppe nicht verfuegbar, z. B. unsignierter Build): wie bisher in der App-Gruppe
            if v.isEmpty || r == .shared { store.removeObject(forKey: "token") } else { store.set(v, forKey: "token") }
            setKeyOrigin(device: false, v.isEmpty ? nil : serverOrigin)
        }
    }
    static var hasToken: Bool { token.hasPrefix("hk_") }

    /// Kennung fuer Links aus den eigenen Widgets: deren Aktionen ("neuer Eintrag") laufen ohne Rueckfrage,
    /// fremde Links (Webseite, Nachricht) fragen vorher nach.
    static var linkSecret: String {
        if let s = store.string(forKey: "linkSecret"), s.count >= 16 { return s }
        let s = UUID().uuidString.replacingOccurrences(of: "-", with: "")
        store.set(s, forKey: "linkSecret"); return s
    }
}

/// Bindung eines Schluessels an seinen Server (reine Logik, mit `swift` pruefbar).
/// Die Marke `<originKey>Migrated` sorgt dafuer, dass ein fehlender Ursprung nur EINMAL (beim Update) ergaenzt wird.
enum KeyOriginMigration {
    /// Einmalig: kein Ursprung gespeichert, Server eingetragen und Schluessel wirklich vorhanden -> an diesen Server binden
    static func run(_ d: UserDefaults, originKey: String, server: String?, keyPresent: () -> Bool) {
        let marker = originKey + "Migrated"
        guard !d.bool(forKey: marker) else { return }
        d.set(true, forKey: marker)
        guard d.string(forKey: originKey) == nil, let server, keyPresent() else { return }
        d.set(server, forKey: originKey)
    }
    /// Ursprung ausdruecklich setzen/loeschen (neuer Schluessel, Abmelden) - danach gibt es nichts mehr zu migrieren
    static func set(_ d: UserDefaults, originKey: String, _ origin: String?) {
        d.set(true, forKey: originKey + "Migrated")
        if let origin { d.set(origin, forKey: originKey) } else { d.removeObject(forKey: originKey) }
    }
}

/// Anmeldebildschirm: was passiert mit der eingegebenen Adresse? (reine Logik, mit `swift` pruefbar)
/// Der eingetragene Server wird hier NIE geaendert - das geschieht erst nach erfolgreicher Anmeldung (Store.exchange).
enum LoginStep: Equatable {
    case invalid                     // keine erlaubte Adresse
    case confirmSwitch(URL)          // anderer Server, bisherige Anmeldung vorhanden -> erst nachfragen
    case signIn(URL)                 // Browser-Anmeldung gegen diese (vorgemerkte) Adresse
    static func plan(_ input: String, currentOrigin: String?, hasKeys: Bool) -> LoginStep {
        guard let u = AppConfig.normalizedServer(input) else { return .invalid }
        if let cur = currentOrigin, cur != AppConfig.origin(u), hasKeys { return .confirmSwitch(u) }
        return .signIn(u)
    }
}

/// Kopplungs-Rueckfrage: was passiert mit der bisherigen Anmeldung? (reine Logik, mit `swift` pruefbar)
/// Wer angemeldet ist, wird abgemeldet - auch auf demselben Server (der QR-Code kann zu einem anderen Konto gehoeren).
enum PairNotice: Equatable {
    case none                        // nicht angemeldet: nur Ziel zeigen
    case otherServer(String)         // anderer Server: bisheriger Host[:Port]
    case sameServer(account: String?) // gleicher Server: bisheriges Konto (Anzeigename, falls bekannt)
    static func plan(target: URL, current: URL?, signedIn: Bool, account: String?) -> PairNotice {
        guard signedIn, let current else { return .none }
        if AppConfig.origin(current) != AppConfig.origin(target) { return .otherServer(AppConfig.displayHost(current)) }
        let name = account?.trimmingCharacters(in: .whitespacesAndNewlines)
        return .sameServer(account: name?.isEmpty == false ? name : nil)
    }
}
