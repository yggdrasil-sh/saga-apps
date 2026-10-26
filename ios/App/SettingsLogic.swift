import Foundation

// Einstellungen in drei Bereichen (2.5): Konto · Einstellungen · Info - Spruenge aus anderen Teilen der App
// (CloudNav.pendingSection, Benachrichtigung hkOpenSettings, DEBUG -hkSettings). Tests: Tests/Logic/UpdateHintTests.swift
enum SettingsArea: String, CaseIterable, Hashable {
    case konto, einstellungen, info

    private static let kontoRows: Set<String> = ["konto", "server", "token", "avatar", "security", "backup", "apps"]
    private static let settingsRows: Set<String> = ["einstellungen", "sprache", "darstellung", "notifs", "push", "chats", "privacy",
                                                "nextcloud", "immich", "kalender"]
    private static let infoRows: Set<String> = ["info", "about", "update"]

    /// Bereich zu einem Sprungziel; nil: nur die Startliste (z. B. "YES" aus -hkSettings)
    static func area(for section: String?) -> SettingsArea? {
        guard let s = section?.lowercased() else { return nil }
        if kontoRows.contains(s) { return .konto }
        if settingsRows.contains(s) { return .einstellungen }
        if infoRows.contains(s) { return .info }
        return nil
    }

    /// Zeile, zu der im Bereich gescrollt wird (nil: oben bleiben). "konto" ist die Zeile "Oeffentlicher Name".
    static func scrollTarget(for section: String?) -> String? {
        guard let s = section?.lowercased(), area(for: s) != nil, !["einstellungen", "info", "about"].contains(s) else { return nil }
        return s
    }
}
