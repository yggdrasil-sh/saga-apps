import Foundation

// Update-Hinweis in Einstellungen -> Info (alle Apps gleich, docs: tools/update-info.py) - reine Logik ohne Netz/SwiftUI.
// Quelle: https://raw.githubusercontent.com/<repo>/appcast/<kanal>.json (CI, Zweig appcast). Verglichen wird nur im eigenen
// Kanal: Vorabversion x.y.z-rc.N -> testing.json, sonst stable.json. Entwickler-Labels (kein x.y.z(-rc.N)?) -> keine Pruefung.
// Tests: Tests/Logic/UpdateHintTests.swift
enum UpdateHint {
    /// Repo der Downloads (DMG-Builds koennen es per Info.plist SagaUpdateRepo aus SAGA_REPO ueberschreiben)
    static let defaultRepo = "yggdrasil-sh/saga-apps"
    /// Automatische Pruefung beim App-Start hoechstens so oft
    static let startInterval: TimeInterval = 12 * 3600
    /// Beim Oeffnen von Info nicht oefter als so (sonst jedes Antippen eine Anfrage)
    static let infoInterval: TimeInterval = 15 * 60
    /// Groesste angenommene Antwort (die Datei hat wenige hundert Byte)
    static let maxBytes = 64 * 1024

    // MARK: Versionen

    struct Version: Comparable, Equatable {
        let major: Int, minor: Int, patch: Int
        let rc: Int?                                     // nil = Release

        init?(_ s: String) {
            let t = s.trimmingCharacters(in: .whitespaces)
            guard t.range(of: #"^[0-9]{1,9}\.[0-9]{1,9}\.[0-9]{1,9}(-rc\.[0-9]{1,9})?$"#, options: .regularExpression) != nil else { return nil }
            let parts = t.split(separator: "-", maxSplits: 1)
            let n = parts[0].split(separator: ".").compactMap { Int($0) }
            guard n.count == 3 else { return nil }
            major = n[0]; minor = n[1]; patch = n[2]
            if parts.count == 2 {
                guard let r = Int(parts[1].dropFirst(3)) else { return nil }
                rc = r
            } else { rc = nil }
        }

        var prerelease: Bool { rc != nil }

        /// x.y.z numerisch; gleiche x.y.z: Release > jede -rc.N; -rc.N numerisch
        static func < (a: Version, b: Version) -> Bool {
            if (a.major, a.minor, a.patch) != (b.major, b.minor, b.patch) { return (a.major, a.minor, a.patch) < (b.major, b.minor, b.patch) }
            switch (a.rc, b.rc) {
            case let (x?, y?): return x < y
            case (.some, nil): return true
            default: return false
            }
        }
    }

    /// Kanal der eigenen Version ("testing" / "stable"); nil: Entwickler-Label -> keine Pruefung
    static func channel(_ label: String) -> String? {
        Version(label).map { $0.prerelease ? "testing" : "stable" }
    }

    /// Neuer als die eigene Version (beide gueltig, sonst false)
    static func isNewer(_ remote: String, than local: String) -> Bool {
        guard let r = Version(remote), let l = Version(local) else { return false }
        return l < r
    }

    // MARK: Quelle

    /// Repo aus der Info.plist (nur owner/name), sonst die Vorgabe
    static func repo(_ info: [String: Any]) -> String {
        guard let s = (info["SagaUpdateRepo"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines),
              s.range(of: #"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$"#, options: .regularExpression) != nil,
              !s.contains("..") else { return defaultRepo }
        return s
    }

    static func feedURL(label: String, repo: String = defaultRepo) -> URL? {
        guard let ch = channel(label) else { return nil }
        return URL(string: "https://raw.githubusercontent.com/\(repo)/appcast/\(ch).json")
    }

    // MARK: Datei (format 1)

    struct Release: Codable, Equatable {
        let version: String
        let page: URL?
        let assets: [String: URL]
    }

    /// Nur https-Links auf github.com (keine Benutzerangaben, kein anderer Port)
    static func safeLink(_ v: Any?) -> URL? {
        guard let s = v as? String, s.count < 2048, let u = URL(string: s), u.scheme?.lowercased() == "https",
              u.host?.lowercased() == "github.com", u.user == nil, u.password == nil, u.port == nil else { return nil }
        return u
    }

    /// Datei lesen: format 1, passender Kanal, gueltige Version im selben Kanal; unsichere Links fallen weg
    static func parse(_ data: Data, channel expected: String) -> Release? {
        guard data.count <= maxBytes,
              let j = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              (j["format"] as? Int) == 1,
              let v = (j["version"] as? String)?.trimmingCharacters(in: .whitespaces),
              channel(v) == expected else { return nil }
        if let ch = j["channel"] as? String, ch != expected { return nil }
        var assets: [String: URL] = [:]
        for (k, x) in j["assets"] as? [String: Any] ?? [:] { if let u = safeLink(x) { assets[k] = u } }
        return Release(version: v, page: safeLink(j["page"]), assets: assets)
    }

    // MARK: Schaltflaeche

    enum Platform { case ios, mac }
    enum Action: Equatable { case none, sparkle, open(URL) }

    /// Was "Update" tut: iOS nichts (kommt ueber die Geraeteverwaltung), Mac Sparkle bzw. DMG/Release-Seite
    static func action(_ r: Release, platform: Platform, sparkle: Bool) -> Action {
        switch platform {
        case .ios: return .none
        case .mac:
            if sparkle { return .sparkle }
            if let u = r.assets["macos"] ?? r.page { return .open(u) }
            return .none
        }
    }

    enum ButtonState: Equatable { case check, checking, update }

    /// Eine Schaltflaeche: "Nach Updates suchen" / "Pruefe …" / "Update" (nur wenn es eine Aktion gibt)
    static func button(checking: Bool, available: Release?, platform: Platform, sparkle: Bool) -> ButtonState {
        if checking { return .checking }
        if let r = available, action(r, platform: platform, sparkle: sparkle) != .none { return .update }
        return .check
    }

    enum Status: Equatable { case none, available(String), upToDate, failed }

    /// Statuszeile ueber der Schaltflaeche; "fehlgeschlagen" nur nach manueller Pruefung (failed setzt nur sie)
    static func status(available: Release?, failed: Bool, checked: Date?) -> Status {
        if let r = available { return .available(r.version) }
        if failed { return .failed }
        return checked == nil ? .none : .upToDate
    }

    /// Bekanntes Update aus dem Zwischenspeicher: nur neuer und nur im eigenen Kanal
    static func available(_ r: Release?, label: String) -> Release? {
        guard let r, let ch = channel(label), channel(r.version) == ch, isNewer(r.version, than: label) else { return nil }
        return r
    }

    /// Faellig: nie geprueft, Uhr zurueckgestellt oder aelter als das Intervall
    static func due(last: Date?, now: Date, interval: TimeInterval) -> Bool {
        guard let last else { return true }
        let age = now.timeIntervalSince(last)
        return age < 0 || age >= interval
    }

    // MARK: Zwischenspeicher (UserDefaults)

    struct Cache: Codable, Equatable {
        let checked: Date
        let release: Release?
    }

    static func encode(_ c: Cache) -> Data? { try? JSONEncoder().encode(c) }
    static func decode(_ d: Data?) -> Cache? { d.flatMap { try? JSONDecoder().decode(Cache.self, from: $0) } }
}
