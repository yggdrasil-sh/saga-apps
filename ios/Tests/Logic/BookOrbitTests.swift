import Foundation

// 2.5 BookOrbit (App/BookOrbitLogic.swift): Widget "Lesen" - alle Zustaende und Anzeige-Arten, Cover-Pfade,
// Heute-Karte, Einstellungen (Status, Verbinden). Daten wie in docs/APP-API.md (frei erfunden).
func bookOrbitTests() {
    typealias L = BookOrbitLogic
    func j(_ s: String) -> [String: Any] { (try! JSONSerialization.jsonObject(with: Data(s.utf8))) as! [String: Any] }

    // MARK: Faehigkeit, Arten
    check(Widget2Logic.featureFor("reading") == "bookorbit1" && Widget2Logic.featureFor("weather") == "weather1", "Faehigkeit je Art")
    check(WidgetLogic.kinds.contains("reading") && Widget2Logic.kinds.contains("reading"), "reading bekannt")
    check(WidgetLogic.needsServer(WidgetLogic.block(NSDictionary(dictionary: ["kind": "reading", "cfg": ["show": "goal"]]))), "reading holt Server-Wert")

    // MARK: current
    let cur = L.reading(j("""
    {"state": "ok", "show": "current", "books": [
      {"id": 11, "title": " Der Nachtzug ", "authors": ["Ida Feld", "", "Bo Lind", "Cy Dorn"], "progress": 42.6, "cover": "/api/bookorbit/cover/11", "format": "epub"},
      {"id": 12, "title": "", "authors": [], "progress": 140, "cover": null},
      {"title": "ohne id"},
      {"id": 13, "title": "Fremd", "progress": -3, "cover": "https://evil.example/x.png"}
    ], "more": 2}
    """))!
    check(cur.state == .ok && cur.show == .current && cur.books.count == 3 && cur.more == 2, "current: Buecher ohne id verworfen")
    let b0 = cur.books[0]
    check(b0.id == 11 && b0.title == "Der Nachtzug" && b0.authorLine == "Ida Feld, Bo Lind" && b0.percent == 43 && b0.cover == "/api/bookorbit/cover/11" && b0.format == "epub", "Buch gelesen")
    check(cur.books[1].progress == 100 && cur.books[1].cover == nil && cur.books[1].authorLine.isEmpty, "Fortschritt gedeckelt, kein Cover")
    check(cur.books[2].progress == 0 && cur.books[2].cover == nil, "fremde Cover-Adresse verworfen, negativ -> 0")
    if case .books(let bs, let more) = L.content(cur) { check(bs.count == 3 && more == 2, "Inhalt: Buecher") } else { check(false, "Inhalt: Buecher") }
    check(L.content(L.reading(j(#"{"state": "ok", "show": "current", "books": []}"#))!) == .noBooks, "current leer")

    // MARK: goal
    let g = L.reading(j(#"{"state": "ok", "show": "goal", "goal": {"goal": 12, "done": 3, "year": 2026}}"#))!
    check(g.show == .goal && g.goal == L.Goal(goal: 12, done: 3, year: 2026) && g.goal?.fraction == 0.25, "goal x/y")
    check(L.content(g) == .goal(L.Goal(goal: 12, done: 3, year: 2026)), "Inhalt: Ziel")
    let g0 = L.reading(j(#"{"state": "ok", "show": "goal", "goal": {"goal": null, "done": 5, "year": 2026}}"#))!
    check(g0.goal?.goal == nil && g0.goal?.fraction == nil && g0.goal?.done == 5, "goal ohne Ziel")
    check(L.reading(j(#"{"state": "ok", "show": "goal", "goal": {"goal": 2, "done": 7}}"#))!.goal?.fraction == 1, "Ziel ueberschritten -> 1")
    check(L.content(L.reading(j(#"{"state": "ok", "show": "goal", "goal": null}"#))!) == .noGoal, "goal null (Teil fehlgeschlagen)")

    // MARK: streak
    let s = L.reading(j(#"{"state": "ok", "show": "streak", "streak": {"current": 4, "longest": 9, "last7": [true, false, true, true, true, true, true, true]}}"#))!
    check(s.streak?.current == 4 && s.streak?.longest == 9 && s.streak?.last7.count == 7 && s.streak?.daysRead == 6, "streak, last7 auf 7 begrenzt")
    check(L.content(L.reading(j(#"{"state": "ok", "show": "streak"}"#))!) == .noStreak, "streak fehlt")

    // MARK: Zustaende ohne Daten
    for st in ["none", "expired", "unavailable"] {
        let r = L.reading(j(#"{"state": "\#(st)", "show": "current"}"#))!
        check(r.state.rawValue == st && L.content(r) == .hint(r.state), "Zustand \(st) -> Hinweis")
    }
    check(L.offersSettings(.none) && L.offersSettings(.expired) && !L.offersSettings(.unavailable) && !L.offersSettings(.ok), "Weg zu den Einstellungen")
    check(L.reading(j(#"{"state": "kaputt"}"#))!.state == .unavailable, "unbekannter Zustand -> nicht erreichbar")
    check(L.reading(j(#"{"state": "ok", "show": "seltsam", "books": []}"#))!.show == .current, "unbekannte Art -> current")
    check(L.reading(nil) == nil, "kein data (Fehler-Widget)")
    check(L.reading(j(#"{"state": "ok", "stale": true, "show": "goal", "goal": {"goal": 1, "done": 1}}"#))!.stale, "stale")

    // MARK: ueber die Widget-Antwort
    let resp = WidgetLogic.parse(Data("""
    {"widgets": {"r1": {"kind": "reading", "value": 1, "max": null, "error": null, "as_of": "2026-11-08T17:00:00Z",
      "data": {"state": "ok", "show": "current", "books": [{"id": 5, "title": "A", "authors": ["B"], "progress": 10, "cover": "/api/bookorbit/cover/5"}], "more": 0}},
     "r2": {"kind": "reading", "value": null, "error": null, "data": {"state": "expired", "show": "streak"}},
     "r3": {"kind": "reading", "error": "no_access", "data": null}}}
    """.utf8))!
    check(L.reading(resp.widgets["r1"]?.dict)?.books.first?.cover == "/api/bookorbit/cover/5", "Widget-Antwort: Buch")
    check(L.reading(resp.widgets["r2"]?.dict)?.state == .expired, "Widget-Antwort: abgelaufen")
    check(L.reading(resp.widgets["r3"]?.dict) == nil && WidgetLogic.notice(error: resp.widgets["r3"]?.error) == .noAccess, "Widget-Antwort: Foederation no_access")

    // MARK: Cover-Pfade
    check(L.coverPath("/api/bookorbit/cover/123") != nil, "Cover ok")
    for bad in ["/api/bookorbit/cover/", "/api/bookorbit/cover/1/../x", "/api/bookorbit/cover/1?x=1", "/files/1.png", "//evil/api/bookorbit/cover/1", "/api/bookorbit/cover/١٢"] {
        check(L.coverPath(bad) == nil, "Cover verworfen: \(bad)")
    }

    // MARK: Heute
    check(!L.todayVisible(nil) && !L.todayVisible(L.Reading(state: .none)), "Heute: ohne Verbindung ausgeblendet")
    check(L.todayVisible(L.Reading(state: .expired)) && L.todayVisible(L.Reading(state: .ok)), "Heute: abgelaufen zeigt Hinweis")

    // MARK: Einstellungen
    let st = L.status(j(#"{"available": true, "connected": true, "state": "ok", "name": "Anna", "url": "https://books.example.org", "since": 1762600000, "suggest": null}"#))!
    check(st.connected && st.state == .ok && st.name == "Anna" && st.host == "books.example.org" && st.suggest == nil && st.since == 1762600000, "Status verbunden")
    let st0 = L.status(j(#"{"available": true, "connected": false, "state": "none", "suggest": "https://books.example.org"}"#))!
    check(!st0.connected && st0.state == .none && st0.suggest == "https://books.example.org" && st0.name == nil, "Status getrennt mit Vorschlag")
    check(L.status(j(#"{"available": false}"#))?.available == false, "nicht verfuegbar")
    let body = L.connectBody(url: " https://books.example.org/ ", username: " anna ", password: " geheim ")
    check(body["url"] as? String == "https://books.example.org/" && body["username"] as? String == "anna" && body["password"] as? String == " geheim ", "Verbinden: Passwort unveraendert")
    check(!L.canConnect(url: "x", username: " ", password: "p") && L.canConnect(url: "x", username: "a", password: "p"), "Verbinden erst mit allen Feldern")
}
