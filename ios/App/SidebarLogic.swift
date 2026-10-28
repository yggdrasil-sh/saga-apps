import Foundation

// Saga 2.5 "Notion-Gefuehl" (Quick Wins Mac/iPad): Reihenfolge der Seitenleiste. Reine Logik (mit Tests).
// iPhone (und iPad in schmaler Breite) behalten die bisherige Reihenfolge.

enum SidebarLayout {
    enum Part: String, CaseIterable {
        case tools          // Aufgaben, Termine, Dienste, Chats, Graph (offen, wie bisher)
        case today          // "Heute" mit Terminen und Aufgaben (wie bisher)
        case todayRow       // Mac/iPad: eine Zeile "Heute" mit Zaehler
        case favorites, recent, pages, shared
        case toolsFolded    // Mac/iPad: Werkzeuge unten, einklappbar
        case archive, trash
    }

    /// Mac/iPad (normale Breite): Seitenbaum nach oben, Werkzeuge nach unten
    static func parts(desk: Bool) -> [Part] {
        desk ? [.todayRow, .favorites, .pages, .shared, .recent, .toolsFolded, .archive, .trash]
             : [.tools, .today, .favorites, .recent, .pages, .shared, .archive, .trash]
    }

    /// Zaehler der Zeile "Heute": Termine heute + offene Aufgaben (heute/ueberfaellig/ohne Datum, hoechstens 5 wie die Liste)
    static func todayCount(events: Int, tasksDueTodayOrUndated: Int) -> Int { max(0, events) + min(max(0, tasksDueTodayOrUndated), 5) }

    /// Kopf "Werkzeuge" zugeklappt: ungelesene Chats dort zeigen, damit nichts untergeht
    static func foldedBadge(expanded: Bool, chatUnread: Int) -> Int { expanded ? 0 : max(0, chatUnread) }
}

extension SidebarLayout {
    /// Auswahl der Zeile "Heute" (Mac/iPad): zeigt dieselbe Ansicht wie keine Auswahl (TodayScreen)
    static let todayTag = -6
}

extension SidebarLayout {
    /// ⌘\: war die Seitenleiste aus (nur Inhalt), wieder zeigen - sonst ausblenden. true = danach ausgeblendet.
    static func toggled(_ hiddenNow: Bool) -> Bool { !hiddenNow }
}
