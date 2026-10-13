import Foundation
import Security
#if os(iOS)
import UIKit
#endif

/// Geraete-Schluessel (hkd_...) im Schluesselbund: erneuert die 8-Stunden-Sitzung ohne Anmeldefenster.
/// Nur dieses Geraet, nicht in iCloud; nach dem ersten Entsperren lesbar (auch im Hintergrund).
enum DeviceKey {
    private static let service = AppConfig.mainBundleID + ".device-login"     // <App-ID>.device-login
    private static let account = "device_token"

    private static func base(_ dataProtection: Bool) -> [String: Any] {
        var q: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
                                kSecAttrService as String: service, kSecAttrAccount as String: account]
        #if os(macOS)
        if dataProtection { q[kSecUseDataProtectionKeychain as String] = true }   // keine Rueckfrage-Dialoge am Mac
        #endif
        return q
    }

    static func get() -> String? {
        for dp in [true, false] {
            var q = base(dp); q[kSecReturnData as String] = true; q[kSecMatchLimit as String] = kSecMatchLimitOne
            var out: AnyObject?
            if SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let d = out as? Data,
               let s = String(data: d, encoding: .utf8), s.hasPrefix("hkd_") { return s }
            #if os(iOS)
            break
            #endif
        }
        return nil
    }

    static func set(_ value: String?) {
        for dp in [true, false] { SecItemDelete(base(dp) as CFDictionary) }
        guard let value, let data = value.data(using: .utf8) else { return }
        for dp in [true, false] {
            var q = base(dp)
            q[kSecValueData as String] = data
            q[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
            if SecItemAdd(q as CFDictionary, nil) == errSecSuccess { return }
        }
    }

    /// Name, unter dem das Geraet in den Einstellungen erscheint
    @MainActor static var deviceName: String {
        #if os(iOS)
        let n = UIDevice.current.name
        return n.isEmpty ? UIDevice.current.model : n
        #else
        return Host.current().localizedName ?? "Mac"
        #endif
    }
}
