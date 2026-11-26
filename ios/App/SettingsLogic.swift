import Foundation

// Einstellungen in drei Bereichen (2.5): Konto · Einstellungen · Info - Spruenge aus anderen Teilen der App
// (CloudNav.pendingSection, Benachrichtigung hkOpenSettings, DEBUG -hkSettings). Tests: Tests/Logic/UpdateHintTests.swift
enum SettingsArea: String, CaseIterable, Hashable {
    case konto, einstellungen, info
    case kontakte                                           // 2.6 (2027a-5, contacts2): iOS-Startliste; Mac-Tabs ohne (eingefroren)
    case saga                                               // 2.6 (2269): Einstellungen › Saga (nur mit Ort "Saga"); Mac-Tabs ohne

    private static let kontoRows: Set<String> = ["konto", "server", "token", "avatar", "security", "backup", "apps"]
    private static let settingsRows: Set<String> = ["einstellungen", "sprache", "darstellung", "notifs", "push", "erreichbarkeit", "chats", "privacy",
                                                "nextcloud", "immich", "kalender", "bookorbit"]
    private static let infoRows: Set<String> = ["info", "about", "update"]
    private static let contactRows: Set<String> = ["kontakte", "contacts"]
    private static let sagaRows: Set<String> = ["saga", "mydata"]

    /// Bereich zu einem Sprungziel; nil: nur die Startliste (z. B. "YES" aus -hkSettings)
    static func area(for section: String?) -> SettingsArea? {
        guard let s = section?.lowercased() else { return nil }
        if kontoRows.contains(s) { return .konto }
        if settingsRows.contains(s) { return .einstellungen }
        if infoRows.contains(s) { return .info }
        if contactRows.contains(s) { return .kontakte }
        if sagaRows.contains(s) { return .saga }
        return nil
    }

    /// Zeile, zu der im Bereich gescrollt wird (nil: oben bleiben). "konto" ist die Zeile "Oeffentlicher Name".
    static func scrollTarget(for section: String?) -> String? {
        guard let s = section?.lowercased(), area(for: s) != nil, !["einstellungen", "info", "about", "kontakte", "contacts", "saga", "mydata"].contains(s) else { return nil }
        return s
    }
}
