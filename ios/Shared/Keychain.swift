import Foundation
import Security

/// Geheimnisse im Schluesselbund (nur dieses Geraet, nicht in iCloud/Backups, nach dem ersten Entsperren lesbar).
/// Mit `group` landet der Eintrag in der gemeinsamen Gruppe von App, Widget und Teilen-Erweiterung.
enum Keychain {
    enum Stored { case shared, local, failed }

    private static func base(_ service: String, _ account: String, dataProtection: Bool) -> [String: Any] {
        var q: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
                                kSecAttrService as String: service, kSecAttrAccount as String: account]
        #if os(macOS)
        if dataProtection { q[kSecUseDataProtectionKeychain as String] = true }   // keine Rueckfrage-Dialoge am Mac
        #endif
        return q
    }
    /// macOS: zuerst der moderne Schluesselbund, sonst der klassische (z. B. ohne Bereitstellungsprofil)
    private static var variants: [Bool] {
        #if os(macOS)
        return [true, false]
        #else
        return [true]
        #endif
    }

    #if DEBUG
    /// Nur fuer Tests (Startparameter -hkTestKeychain <datei>): Datei statt Schluesselbund - z. B. fuer unsignierte
    /// Mac-Testbuilds, die sonst bei jedem neuen Build nach dem Schluesselbund-Zugriff fragen wuerden
    private static var testFile: URL? { UserDefaults.standard.string(forKey: "hkTestKeychain").map { URL(fileURLWithPath: $0) } }
    private static func testAll(_ f: URL) -> [String: String] { (try? JSONDecoder().decode([String: String].self, from: Data(contentsOf: f))) ?? [:] }
    private static func testSet(_ f: URL, _ k: String, _ v: String?) {
        var a = testAll(f); a[k] = v
        if let d = try? JSONEncoder().encode(a) { try? d.write(to: f, options: .atomic) }
    }
    #endif

    static func read(service: String, account: String) -> String? {
        #if DEBUG
        if let f = testFile { return testAll(f)[service + "|" + account] }
        #endif
        for dp in variants {
            var q = base(service, account, dataProtection: dp)
            q[kSecReturnData as String] = true; q[kSecMatchLimit as String] = kSecMatchLimitOne
            var out: AnyObject?
            if SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let d = out as? Data,
               let s = String(data: d, encoding: .utf8), !s.isEmpty { return s }
        }
        return nil
    }

    /// Alle Konten eines Dienstes (2262: Mitteilungs-Schluessel je Server, Suche ueber die Kennung)
    static func accounts(service: String) -> [String] {
        #if DEBUG
        if let f = testFile { return testAll(f).keys.filter { $0.hasPrefix(service + "|") }.map { String($0.dropFirst(service.count + 1)) }.sorted() }
        #endif
        var out = Set<String>()
        for dp in variants {
            var q: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
                                    kSecReturnAttributes as String: true, kSecMatchLimit as String: kSecMatchLimitAll]
            #if os(macOS)
            if dp { q[kSecUseDataProtectionKeychain as String] = true }
            #else
            _ = dp
            #endif
            var res: AnyObject?
            if SecItemCopyMatching(q as CFDictionary, &res) == errSecSuccess, let items = res as? [[String: Any]] {
                for i in items { if let a = i[kSecAttrAccount as String] as? String { out.insert(a) } }
            }
        }
        return out.sorted()
    }

    static func delete(service: String, account: String) {
        #if DEBUG
        if let f = testFile { testSet(f, service + "|" + account, nil); return }
        #endif
        for dp in variants { SecItemDelete(base(service, account, dataProtection: dp) as CFDictionary) }
    }

    /// Schreiben (nil = loeschen). Rueckgabe: in der gemeinsamen Gruppe, nur fuer diese App oder gar nicht gespeichert.
    @discardableResult
    static func write(_ value: String?, service: String, account: String, group: String? = nil) -> Stored {
        delete(service: service, account: account)
        #if DEBUG
        if let f = testFile { if let value, !value.isEmpty { testSet(f, service + "|" + account, value) }; return .local }
        #endif
        guard let value, !value.isEmpty, let data = value.data(using: .utf8) else { return .local }
        let groups: [String?] = (group?.isEmpty == false) ? [group, nil] : [nil]
        for dp in variants {
            for g in groups {
                var q = base(service, account, dataProtection: dp)
                q[kSecValueData as String] = data
                q[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
                if let g { q[kSecAttrAccessGroup as String] = g }
                if SecItemAdd(q as CFDictionary, nil) == errSecSuccess { return g == nil ? .local : .shared }
            }
        }
        return .failed
    }
}
