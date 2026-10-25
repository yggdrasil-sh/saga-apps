import Foundation

// Selbst-Update der Mac-App (Sparkle 2) - reine Logik, ohne Sparkle (Tests: Tests/Logic/UpdateTests.swift).
// Aktiv nur in DMG-Builds: ios/build-dmg.sh traegt SagaSelfUpdate, SUFeedURL und SUPublicEDKey in die Info.plist ein.
// Eigene Builds (build-install.sh, MDM) und iOS haben die Schluessel nicht und bleiben unveraendert.
// Kanal: steckt in SUFeedURL (appcast.xml bzw. appcast-rc.xml), nicht in sparkle:channel - siehe build-dmg.sh.
enum UpdateLogic {
    /// Selbst-Update eingeschaltet: Merker gesetzt, https-Appcast und oeffentlicher EdDSA-Schluessel vorhanden
    static func isEnabled(_ info: [String: Any]) -> Bool {
        guard flag(info["SagaSelfUpdate"]) else { return false }
        guard let feed = string(info["SUFeedURL"]), let u = URL(string: feed), u.scheme == "https", u.host != nil else { return false }
        guard let key = string(info["SUPublicEDKey"]), let raw = Data(base64Encoded: key), raw.count == 32 else { return false }
        return true
    }

    /// Vorabversion ("2.5.0-rc.74"): build-dmg.sh traegt dann den Appcast der Vorabversionen ein (appcast-rc.xml),
    /// stabile Builds lesen appcast.xml - Testversionen bleiben so bei Testversionen
    static func isPrerelease(_ label: String) -> Bool {
        label.trimmingCharacters(in: .whitespaces).range(of: #"^[0-9]+\.[0-9]+\.[0-9]+-rc\.[0-9]+$"#, options: .regularExpression) != nil
    }

    private static func flag(_ v: Any?) -> Bool {
        if let b = v as? Bool { return b }
        if let s = v as? String { return ["1", "yes", "true"].contains(s.lowercased()) }
        if let n = v as? NSNumber { return n.boolValue }
        return false
    }

    /// Leere Werte und nicht ersetzte Build-Variablen ("$(...)") zaehlen als fehlend
    private static func string(_ v: Any?) -> String? {
        guard let s = (v as? String)?.trimmingCharacters(in: .whitespacesAndNewlines), !s.isEmpty, !s.hasPrefix("$(") else { return nil }
        return s
    }
}
