import Foundation
#if os(iOS)
import UIKit
#endif

/// Geraete-Schluessel (hkd_...) im Schluesselbund: erneuert die 8-Stunden-Sitzung ohne Anmeldefenster.
/// Nur dieses Geraet, nicht in iCloud; nach dem ersten Entsperren lesbar (auch im Hintergrund).
enum DeviceKey {
    private static let service = AppConfig.deviceKeyService                  // <App-ID>.device-login
    private static let account = AppConfig.deviceKeyAccount

    /// Gespeicherter Schluessel - unabhaengig vom Server (nur zum Abmelden beim ausstellenden Server)
    static func stored() -> String? {
        Keychain.read(service: service, account: account).flatMap { $0.hasPrefix("hkd_") ? $0 : nil }
    }
    /// Schluessel fuer den eingetragenen Server - gehoert er zu einem anderen Server, wird er nie gesendet
    static func get() -> String? {
        AppConfig.keyMatchesServer(device: true) ? stored() : nil
    }
    /// Speichern (nil = loeschen); `origin`: Server, der ihn ausgestellt hat
    static func set(_ value: String?, origin: String? = AppConfig.serverOrigin) {
        Keychain.write(value, service: service, account: account, group: AppConfig.privateKeychainGroup)
        AppConfig.setKeyOrigin(device: true, value == nil ? nil : origin)
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
