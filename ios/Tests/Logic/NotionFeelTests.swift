import Foundation

// Saga 2.5 "Notion-Gefuehl" (Quick Wins Mac/iPad): Schreibflaeche (App/WritingLogic.swift), Speicherstatus
// (App/SaveLogic.swift: SaveBadge), Seitenleiste (App/PageListLogic.swift: SidebarLayout)
func notionFeelTests() {
    let m = WritingLogic.mac
    // Mac: 16 pt Text, Titel 34-36 pt, H1 > H2 > H3 > Text
    check(WritingLogic.pointSize(.body, step: WritingLogic.defaultStep, in: m) == 16, "Mac: Text 16 pt")
    let t = WritingLogic.pointSize(.title, step: WritingLogic.defaultStep, in: m) ?? 0
    check(t >= 34 && t <= 36, "Mac: Titel 34-36 pt")
    let h = [WritingLogic.Role.h1, .h2, .h3, .body].map { WritingLogic.pointSize($0, step: 3, in: m) ?? 0 }
    check(h[0] > h[1] && h[1] > h[2] && h[2] > h[3], "Mac: Ueberschriften abgestuft")
    check(m.lineSpacing >= 2 && m.lineSpacing <= 4, "Mac: Zeilenabstand +2..4 pt")
    // Dynamic Type/Bedienhilfen: groessere Stufe = groessere Schrift, kleinere = kleinere; unbekannt = Standard
    check((WritingLogic.pointSize(.body, step: 6, in: m) ?? 0) > 16, "Dynamic Type: groesser")
    check((WritingLogic.pointSize(.body, step: 1, in: m) ?? 99) < 16, "kleiner Text: zwei Stufen kleiner")
    check((WritingLogic.pointSize(.body, step: 11, in: m) ?? 0) >= 45, "Bedienhilfen-Groesse waechst deutlich")
    check(WritingLogic.scale(step: -1) == 1 && WritingLogic.scale(step: 99) == 1, "unbekannte Stufe = Standard")
    check(WritingLogic.bodySteps.count == 12, "12 Stufen wie DynamicTypeSize.allCases")
    // iPad/iPhone: Textstile des Systems (keine feste Groesse); iPhone-Abstaende wie bisher
    check(WritingLogic.pointSize(.body, step: 3, in: WritingLogic.pad) == nil, "iPad: Textstil des Systems")
    let phone = WritingLogic.metrics(mac: false, regularWidth: false)
    check(phone == WritingLogic.standard && phone.blockSpacing == 10 && phone.headerPadding(level: 1) == 6 && phone.lineSpacing == 0, "iPhone unveraendert")
    check(WritingLogic.metrics(mac: false, regularWidth: true) == WritingLogic.pad, "iPad normale Breite")
    check(WritingLogic.metrics(mac: true, regularWidth: false) == WritingLogic.mac, "Mac immer")
    check(WritingLogic.pad.blockSpacing > 10 && m.headerPadding(level: 1) > m.headerPadding(level: 3), "mehr Luft, ueber H1 am meisten")
    check(m.headerPadding(level: 0) == m.headerPadding(level: 1) && m.headerPadding(level: 9) == m.headerTop.last, "Ebenen begrenzt")
    // Rollen
    check(WritingLogic.headerRole(level: 0) == .bodyBold && WritingLogic.headerRole(level: 1) == .h1 && WritingLogic.headerRole(level: 3) == .h3 && WritingLogic.headerRole(level: 6) == .h4, "Ueberschrift -> Rolle")
    check(!WritingLogic.isBold(.body) && WritingLogic.isBold(.title) && WritingLogic.isBold(.h2), "fett: nur Titel/Ueberschriften")
    // Griffe auf der ersten Zeile: Absatz ~ halbe Zeile, H1 tiefer (Abstand darueber)
    let p = WritingLogic.handleCenter(type: "paragraph", level: 0, step: 3, in: m)
    let h1 = WritingLogic.handleCenter(type: "header", level: 1, step: 3, in: m)
    check(p >= 11 && p <= 14, "Griff Absatz auf der ersten Zeile")
    check(h1 > p + 20, "Griff H1 unter dem Abstand darueber")
    check(WritingLogic.handleCenter(type: "image", level: 0, step: 0, in: WritingLogic.standard) >= 11, "mindestens halbe Griffhoehe")
}

func notionSidebarTests() {
    let phone = SidebarLayout.parts(desk: false), desk = SidebarLayout.parts(desk: true)
    check(phone == [.tools, .today, .favorites, .recent, .pages, .shared, .archive, .trash], "iPhone: Reihenfolge wie bisher")
    check(desk == [.deskTop, .favorites, .pages, .shared, .recent, .moreFolded, .archive, .trash], "Mac/iPad: Reihenfolge oben fest, Seiten, Mehr unten")
    check(desk.first == .deskTop, "Mac/iPad: Heute/Chats/Aufgaben/Termine ganz oben")
    check(desk.firstIndex(of: .pages)! < desk.firstIndex(of: .moreFolded)!, "Mac/iPad: Seitenbaum vor dem Fach Mehr")
    check(desk.firstIndex(of: .pages)! <= 2, "Mac/iPad: Seitenbaum weit oben")
    check(!desk.contains(.tools) && !desk.contains(.today), "Mac/iPad: kein offener Werkzeug-Block, Heute nur als Zeile")
    check(SidebarLayout.deskTopRows == [.today, .chats, .tasks, .events], "oben fest: Heute, Chats, Aufgaben, Termine")
    check(SidebarLayout.moreRows(graph: true) == [.services, .graph] && SidebarLayout.moreRows(graph: false) == [.services], "Mehr: Dienste, Graph nur wenn unterstuetzt")
    let all = SidebarLayout.deskTopRows + SidebarLayout.moreRows(graph: true)
    check(Set(all).count == all.count && Set(all) == Set(SidebarLayout.Row.allCases), "jede Zeile genau einmal (nichts geht verloren)")
    check(!SidebarLayout.moreRows(graph: true).contains(.chats), "Chats nie im einklappbaren Fach")
    check(Set(desk).count == desk.count && Set(phone).count == phone.count, "kein Teil doppelt")
    check(desk.suffix(2) == [.archive, .trash] && phone.suffix(2) == [.archive, .trash], "Archiv und Papierkorb zuletzt")
    check(SidebarLayout.todayCount(events: 2, tasksDueTodayOrUndated: 9) == 7 && SidebarLayout.todayCount(events: -1, tasksDueTodayOrUndated: 0) == 0, "Zaehler Heute")
}
