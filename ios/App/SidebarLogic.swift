import Foundation

// Saga 2.5 "Notion-Gefuehl" (Quick Wins Mac/iPad): Reihenfolge der Seitenleiste. Reine Logik (mit Tests).
// iPhone (und iPad in schmaler Breite) behalten die bisherige Reihenfolge.

enum SidebarLayout {
    enum Part: String, CaseIterable {
        case tools          // Aufgaben, Termine, Dienste, Chats, Graph (offen, wie bisher)
        case today          // "Heute" mit Terminen und Aufgaben (wie bisher)
        case deskTop        // Mac/iPad: oben fest (nicht einklappbar): Heute, Chats, Aufgaben, Termine
        case favorites, recent, pages, shared
        case moreFolded     // Mac/iPad: "Mehr" unten, einklappbar: Dienste, Graph
        case archive, trash
    }

    /// Zeilen in den Mac/iPad-Teilen (Tags: siehe Sidebar.part in RootView)
    enum Row: String, CaseIterable { case today, chats, tasks, events, services, graph }

    /// Mac/iPad (normale Breite) wie Notion: wichtige Werkzeuge oben fest, Seitenbaum darunter, Rest unten einklappbar
    static func parts(desk: Bool) -> [Part] {
        desk ? [.deskTop, .favorites, .pages, .shared, .recent, .moreFolded, .archive, .trash]
             : [.tools, .today, .favorites, .recent, .pages, .shared, .archive, .trash]
    }

    /// Zaehler der Zeile "Heute": Termine heute + offene Aufgaben (heute/ueberfaellig/ohne Datum, hoechstens 5 wie die Liste)
    static func todayCount(events: Int, tasksDueTodayOrUndated: Int) -> Int { max(0, events) + min(max(0, tasksDueTodayOrUndated), 5) }

    /// Oben fest (immer sichtbar)
    static let deskTopRows: [Row] = [.today, .chats, .tasks, .events]

    /// Fach "Mehr" (einklappbar): Dienste immer, Graph nur wenn der Server ihn kann
    static func moreRows(graph: Bool) -> [Row] { graph ? [.services, .graph] : [.services] }
}

extension SidebarLayout {
    /// Auswahl der Zeile "Heute" (Mac/iPad): zeigt dieselbe Ansicht wie keine Auswahl (TodayScreen)
    static let todayTag = -6
}

extension SidebarLayout {
    /// ⌘\: war die Seitenleiste aus (nur Inhalt), wieder zeigen - sonst ausblenden. true = danach ausgeblendet.
    static func toggled(_ hiddenNow: Bool) -> Bool { !hiddenNow }
}
