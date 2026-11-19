import Foundation

// Bugs-DB 2186: Widgets in Seiten laden in der offenen Seite nach (App/WidgetLogic.swift sourceDBs/liveRefresh) -
// Quellen wie im Web (static/pages.js, hk-db-rows: cfg.db bzw. cfg.dbs[].db)
func widgetLiveTests() {
    typealias W = WidgetLogic
    func json(_ s: String) -> NSArray { (try? JSONSerialization.jsonObject(with: Data(s.utf8))) as? NSArray ?? [] }
    let blocks = json("""
    [{"id":"a","type":"widget","data":{"kind":"metric","cfg":{"db":12,"agg":"count"}}},
     {"id":"b","type":"columns","data":{"cols":[{"blocks":[
        {"id":"c","type":"widget","data":{"kind":"upcoming","cfg":{"dbs":[{"db":33,"date":"d"},{"db":34.0,"date":"d"},{"db":"35"}]}}}]}]}},
     {"id":"d","type":"toggle","data":{"blocks":[{"id":"e","type":"widget","data":{"kind":"habit","cfg":{"db":44,"date":"d"}}}]}},
     {"id":"f","type":"widget","data":{"kind":"progress","cfg":{"src":"fixed","db":55}}},
     {"id":"g","type":"widget","data":{"kind":"weather","cfg":{"lat":1,"lon":2}}},
     {"id":"h","type":"database","data":{"db":66}},
     {"id":"i","type":"widget","data":{"kind":"chart","cfg":{"db":true}}}]
    """)
    let src = W.sourceDBs(blocks)
    check(src == [12, 33, 34, 44], "Live-Widgets: Quellen aus cfg.db und cfg.dbs, auch in Spalten/Toggles (\(src.sorted()))")
    check(!src.contains(55), "Live-Widgets: fester Fortschritt rechnet lokal - keine Quelle")
    check(!src.contains(66), "Live-Widgets: Datenbank-Block laedt selbst nach (Database.swift), keine Widget-Quelle")
    check(!src.contains(35), "Live-Widgets: Text als ID zaehlt nicht (Server: _db_id nur Zahl)")

    // Ereignisse
    check(W.liveRefresh("db.rows", ["db": 12], blocks: blocks), "Live-Widgets: db.rows der Kennzahl-Quelle -> neu holen")
    check(W.liveRefresh("db.rows", ["db": NSNumber(value: 33)], blocks: blocks), "Live-Widgets: db.rows einer Anstehendes-Quelle")
    check(W.liveRefresh("db.rows", ["db": 44], blocks: blocks), "Live-Widgets: Gewohnheiten im Toggle")
    check(!W.liveRefresh("db.rows", ["db": 99], blocks: blocks), "Live-Widgets: andere Datenbank -> nichts")
    check(!W.liveRefresh("db.rows", ["db": 66], blocks: blocks), "Live-Widgets: eingebettete Datenbank ohne Widget -> nichts")
    check(!W.liveRefresh("db.rows", [:], blocks: blocks) && !W.liveRefresh("db.rows", ["db": true], blocks: blocks),
          "Live-Widgets: db.rows ohne gueltige ID")
    check(W.liveRefresh("note.changed", ["id": 12, "rev": 4, "row": 7], blocks: blocks), "Live-Widgets: note.changed mit Zeile der Quelle")
    check(W.liveRefresh("note.changed", ["id": 12, "rev": 5], blocks: blocks), "Live-Widgets: Schema/Ansicht der Quelle geaendert")
    check(!W.liveRefresh("note.changed", ["id": 12, "gone": true], blocks: blocks), "Live-Widgets: Quelle geloescht -> kein Abruf (Fehler kommt beim naechsten)")
    check(!W.liveRefresh("presence", ["note_id": 12], blocks: blocks) && !W.liveRefresh("comments.changed", ["note_id": 12], blocks: blocks),
          "Live-Widgets: Anwesenheit/Kommentare -> nichts")
    check(!W.liveRefresh("db.rows", ["db": 12], blocks: json("[]")), "Live-Widgets: Seite ohne Widgets -> nichts")
    check(W.liveDelay == .milliseconds(300), "Live-Widgets: kurz sammeln wie wgSoon im Web")
}
