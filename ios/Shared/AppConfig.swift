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

    /// Eingetragene Server-Adresse als Text ("" = noch keiner) - fuer Eingabefelder
    static var serverString: String {
        var s = store.string(forKey: "server") ?? defaultServer
        if let n = movedServers[s.trimmingCharacters(in: CharacterSet(charactersIn: "/"))] { s = n; store.set(n, forKey: "server") }
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
    /// App-Zugangsschluessel (hk_...) - darf nur ablegen und "Heute" lesen.
    static var token: String {
        get { store.string(forKey: "token") ?? "" }
        set { store.set(newValue.trimmingCharacters(in: .whitespacesAndNewlines), forKey: "token") }
    }
    static var hasToken: Bool { token.hasPrefix("hk_") }
}
