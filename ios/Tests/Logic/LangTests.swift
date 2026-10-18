import Foundation

// Sprachwahl der App (AppLanguageChoice, App/LanguageLogic.swift)
func langTests() {
    typealias C = AppLanguageChoice
    // gespeicherter Wert -> Wahl
    check(C.from(appleLanguages: nil) == .system, "kein Eintrag = Automatisch")
    check(C.from(appleLanguages: []) == .system, "leere Liste = Automatisch")
    check(C.from(appleLanguages: ["de"]) == .de, "de")
    check(C.from(appleLanguages: ["en-GB", "de"]) == .en, "en-GB -> en")
    check(C.from(appleLanguages: ["es-419"]) == .es, "es-419 -> es")
    check(C.from(appleLanguages: ["ES_es"]) == .es, "Grossschreibung egal")
    check(C.from(appleLanguages: ["fr"]) == .system, "nicht unterstuetzt -> Automatisch")
    // Wahl -> gespeicherter Wert (Hin und zurueck)
    check(C.system.appleLanguages == nil, "Automatisch entfernt den Eintrag")
    for c in C.allCases { check(C.from(appleLanguages: c.appleLanguages) == c, "Rundweg \(c)") }
    check(C.allCases.count == 4 && C.allCases.first == .system, "Reihenfolge: Automatisch zuerst")
    check(C.allCases.compactMap(\.nativeName) == ["Deutsch", "English", "Español"], "Eigennamen")
    // tatsaechliche Sprache
    check(C.system.effective(system: ["de-DE", "en"]) == "de", "System de")
    check(C.system.effective(system: ["fr-FR", "es-ES", "de"]) == "es", "erste unterstuetzte Systemsprache")
    check(C.system.effective(system: ["fr", "it"]) == "en", "nichts Unterstuetztes -> en")
    check(C.system.effective(system: []) == "en", "leer -> en")
    check(C.en.effective(system: ["de-DE"]) == "en", "feste Wahl schlaegt System")
    // Neustart-Hinweis
    check(!C.system.needsRestart(system: ["de-DE"], running: "de"), "System de, laeuft de")
    check(C.en.needsRestart(system: ["de-DE"], running: "de"), "en gewaehlt, laeuft de -> Neustart")
    check(!C.en.needsRestart(system: ["de-DE"], running: "en"), "en gewaehlt, laeuft en")
    check(C.system.needsRestart(system: ["de-DE"], running: "es"), "zurueck auf Automatisch -> Neustart")
    check(!C.es.needsRestart(system: ["de"], running: "es-419"), "Region egal")
}
