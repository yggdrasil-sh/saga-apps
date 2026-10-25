import Foundation

// Teilen-Blatt: Lesezeichen (2.5, Shared/ShareBookmarkLogic.swift) - Vorauswahl, Name, Kategorien, Body
func shareBookmarkTests() {
    typealias L = ShareBookmarkLogic
    // Vorauswahl
    check(L.initialKind(last: nil, hasURL: true) == "page", "ohne Erinnerung wie bisher: Seite")
    check(L.initialKind(last: "bookmark", hasURL: true) == "bookmark", "zuletzt Lesezeichen -> wieder Lesezeichen")
    check(L.initialKind(last: "bookmark", hasURL: false) == "page", "Lesezeichen ohne Link nicht moeglich")
    check(L.initialKind(last: "task", hasURL: false) == "task", "Aufgabe bleibt")
    check(L.initialKind(last: "quatsch", hasURL: true) == "page", "Unbekanntes -> Seite")
    // Name
    check(L.defaultName(title: "  Ein Artikel  ", url: "https://example.org/a") == "Ein Artikel", "Titel getrimmt")
    check(L.defaultName(title: "Zeile 1\nZeile 2", url: nil) == "Zeile 1", "nur erste Zeile")
    check(L.defaultName(title: "", url: "https://www.example.org/x?y=1") == "example.org", "ohne Titel: Host ohne www.")
    check(L.defaultName(title: "", url: nil) == "", "nichts -> leer")
    check(L.defaultName(title: String(repeating: "x", count: 200), url: nil).count == 80, "hoechstens 80 Zeichen")
    // Kategorien
    check(L.categories(server: ["Arbeit", " Lesen ", "", "Arbeit"], standard: "Lesezeichen") == ["Arbeit", "Lesen", "Lesezeichen"],
          "ohne Leere/Doppelte, Standard angehaengt")
    check(L.categories(server: ["Lesezeichen", "Arbeit"], standard: "Lesezeichen") == ["Lesezeichen", "Arbeit"], "Standard nicht doppelt")
    check(L.categories(server: ["arbeit", "Arbeit"], standard: "Lesezeichen").count == 3, "Gross/Klein wie der Server getrennt")
    check(L.categories(server: [], standard: "Bookmarks") == ["Bookmarks"], "ohne Server nur Standard")
    check(L.initialCategory(last: "Lesen", available: ["Arbeit", "Lesen"], standard: "Lesezeichen") == "Lesen", "letzte Kategorie")
    check(L.initialCategory(last: "Weg", available: ["Arbeit"], standard: "Lesezeichen") == "Lesezeichen", "verschwundene -> Standard")
    check(L.initialCategory(last: nil, available: [], standard: "Bookmarks") == "Bookmarks", "keine -> Standard")
    // Body
    let b = L.payload(name: " Name ", url: " https://example.org ", category: String(repeating: "k", count: 50))
    check(b["name"] as? String == "Name" && b["url"] as? String == "https://example.org", "getrimmt")
    check((b["category"] as? String)?.count == 40, "Kategorie hoechstens 40")
    check(b["icon"] as? String == "", "Symbol leer")
}
