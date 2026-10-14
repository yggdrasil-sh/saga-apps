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

    static func read(service: String, account: String) -> String? {
        for dp in variants {
            var q = base(service, account, dataProtection: dp)
            q[kSecReturnData as String] = true; q[kSecMatchLimit as String] = kSecMatchLimitOne
            var out: AnyObject?
            if SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let d = out as? Data,
               let s = String(data: d, encoding: .utf8), !s.isEmpty { return s }
        }
        return nil
    }

    static func delete(service: String, account: String) {
        for dp in variants { SecItemDelete(base(service, account, dataProtection: dp) as CFDictionary) }
    }

    /// Schreiben (nil = loeschen). Rueckgabe: in der gemeinsamen Gruppe, nur fuer diese App oder gar nicht gespeichert.
    @discardableResult
    static func write(_ value: String?, service: String, account: String, group: String? = nil) -> Stored {
        delete(service: service, account: account)
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
