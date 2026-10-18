import Foundation

/// Sprachwahl der App: Automatisch (Systemsprache) oder fest Deutsch/English/Spanisch/Thai/Russisch.
/// Reine Logik ohne SwiftUI - geprueft in Tests/Logic (LangTests.swift).
/// Gespeichert wird wie bei der Systemfunktion "Sprache je App": AppleLanguages in der eigenen Domaene der App
/// (iOS: Einstellungen-App -> Saga -> Sprache; Mac: Auswahl in den Einstellungen der App, wirkt nach Neustart).
enum AppLanguageChoice: String, CaseIterable, Identifiable {
    case system = "", de, en, es, th, ru
    var id: String { rawValue }

    /// Sprachen mit vollstaendigen Texten (Shared/Localizable.xcstrings)
    static let supported = ["de", "en", "es", "th", "ru"]

    /// Eigenname der Sprache - bewusst nicht uebersetzt, damit man die eigene Sprache auch in einer fremden Oberflaeche findet
    var nativeName: String? {
        switch self {
        case .system: return nil
        case .de: return "Deutsch"
        case .en: return "English"
        case .es: return "Español"
        case .th: return "ไทย"
        case .ru: return "Русский"
        }
    }

    /// Zwei-Buchstaben-Code ("de-DE", "es_419" -> "de", "es")
    static func code(_ s: String) -> String { String(s.prefix(2)).lowercased() }

    /// Gespeicherte Wahl aus AppleLanguages der App-Domaene (fehlt/unbekannt -> Automatisch)
    static func from(appleLanguages: [String]?) -> AppLanguageChoice {
        guard let f = appleLanguages?.first else { return .system }
        return AppLanguageChoice(rawValue: code(f)) ?? .system
    }

    /// Wert fuer AppleLanguages der App-Domaene; nil = Eintrag entfernen (dann gilt die Systemsprache)
    var appleLanguages: [String]? { self == .system ? nil : [rawValue] }

    /// Sprache, in der die App mit dieser Wahl laeuft: feste Wahl, sonst die erste unterstuetzte der
    /// bevorzugten Systemsprachen (wie Bundle.preferredLocalizations), sonst Englisch (developmentLanguage)
    func effective(system: [String]) -> String {
        if self != .system { return rawValue }
        return system.map(Self.code).first(where: Self.supported.contains) ?? "en"
    }

    /// Neustart noetig, wenn die laufende Sprache nicht zur Wahl passt (Mac: Texte werden beim Start geladen)
    func needsRestart(system: [String], running: String) -> Bool {
        effective(system: system) != Self.code(running)
    }
}
