import Foundation

// Saga 2.5 Aufgabenquellen (Shared/TaskSourceLogic.swift): Liste lesen (echte Antworten), Anzeige, Rueckgaengig,
// Warteschlange (letzte Aktion gewinnt, prev, Fehlercodes), Widget-Antwort.
func taskSourceTests() {
    func decode<T: Decodable>(_ t: T.Type, _ name: String) -> T? {
        let j = e2Fixture(name)
        guard let d = try? JSONSerialization.data(withJSONObject: j) else { return nil }
        do { return try JSONDecoder().decode(T.self, from: d) } catch { check(false, "\(name): \(error)"); return nil }
    }
    // MARK: Liste (Server-Reihenfolge)
    let l = decode(TaskSourcesList.self, "tasks_src.json")
    let items = l?.items ?? []
    check(items.map(\.key) == ["r:8", "t:1", "t:2", "r:7", "r:9"], "items in Server-Reihenfolge")
    check(items[0].isRow && items[0].overdue == true && items[0].src == 6 && !items[0].readOnly, "Zeile ueberfaellig, bearbeitbar")
    check(items[3].time == "09:30" && items[4].due_end == "2026-10-31", "Uhrzeit und Zeitraum")
    check(!items[1].isRow && items[1].repeat == nil, "Inbox-Aufgabe")
    let day: (String) -> String = { String($0.suffix(5)) }
    check(TaskSourceLogic.when(items[3], day: day) == "10-28 09:30", "Datum mit Uhrzeit")
    check(TaskSourceLogic.when(items[4], day: day) == "10-29 – 10-31", "Zeitraum")
    check(TaskSourceLogic.when(TaskItem(key: "t:1", kind: "task", id: 1, text: "x"), day: day) == nil, "ohne Datum")
    var ro = items[0]; ro.edit = false
    check(ro.readOnly, "edit: false -> nur lesen")
    let src = l?.sources ?? []
    check(src.count == 1 && src[0].canAdd && src[0].map?.done_value == "fertig" && src[0].canAssigned, "Quelle mit Zuordnung (Ziel fuer neue Aufgaben)")
    var inactive = src[0]; inactive.inactive = true
    var viewer = src[0]; viewer.role = "view"
    var unm = src[0]; unm.map = nil
    check(TaskSourceLogic.targets([inactive, viewer, unm]).isEmpty, "Ziele: ohne inactive, nur owner/edit, nur mit map")
    // Personen: nie Anmeldenamen
    var it = items[0]; it.assignees = ["~abcdefghijklmnop", "~zzzzzzzzzzzzzzzz"]
    let names = TaskSourceLogic.names(it, people: ["~abcdefghijklmnop": TaskPerson(name: "Anna", avatar: nil, me: true)])
    check(names == ["Anna", String(localized: "Unbekannte Person")], "Zugewiesene aus people")
    check(TaskSourceLogic.listPath(today: "2026-10-28", tz: "Europe/Berlin") == "api/tasks?with_sources=1&today=2026-10-28&tz=Europe%2FBerlin", "Abfrage")
    struct SL: Decodable { var sources: [TaskSource]; var max: Int }
    let so = decode(SL.self, "tasks_sources.json")
    check(so?.sources.first?.scope == "all" && so?.max == 20, "GET /api/tasks/sources")

    // MARK: Abhaken / Rueckgaengig (echte Antwort)
    let dn = e2Fixture("task_done.json")
    let prev = TaskValue(dn["prev"])
    check(prev == .text("lauf"), "prev = Wert vorher (Option)")
    let undo = TaskSourceLogic.doneBody(done: false, value: prev)
    check(undo["done"] as? Bool == false && undo["value"] as? String == "lauf", "Rueckgaengig schickt value = prev")
    check(TaskSourceLogic.doneBody(done: true, value: prev)["value"] == nil, "Abhaken ohne value")
    check(TaskValue(true as Any) == .bool(true) && TaskValue(NSNull()) == .null && TaskValue(nil) == .null, "Haekchen/null")
    check(TaskSourceLogic.doneBody(done: false, value: .null)["value"] is NSNull, "Haekchen vorher null")
    let rt = try? JSONDecoder().decode([TaskValue].self, from: JSONEncoder().encode([TaskValue.text("x"), .bool(false), .null]))
    check(rt == [.text("x"), .bool(false), .null], "TaskValue Codable")

    // MARK: Warteschlange
    var q = TaskQueue()
    q.put(key: "r:7", done: true, serverDone: false, prev: .text("lauf"))
    check(q.entries["r:7"]?.done == true && q.entries["r:7"]?.prev == .text("lauf"), "abhaken vorgemerkt mit prev")
    q.put(key: "r:7", done: false, serverDone: true, prev: nil)
    check(q.isEmpty, "abhaken + wieder oeffnen = nichts")
    q.put(key: "r:9", done: true, serverDone: false, prev: .text("neu"))
    q.put(key: "r:9", done: true, serverDone: false, prev: .text("fertig"))
    check(q.entries["r:9"]?.done == true && q.entries["r:9"]?.prev == .text("neu"), "doppelt abgehakt: prev des ersten Abhakens bleibt")
    var q2 = TaskQueue()
    q2.put(key: "r:5", done: false, serverDone: true, prev: .text("lauf"))
    q2.put(key: "r:5", done: true, serverDone: true, prev: nil)
    check(q2.isEmpty, "erledigte Zeile oeffnen + wieder abhaken = nichts")
    q2.put(key: "r:5", done: false, serverDone: true, prev: .text("lauf"))
    check(q2.entries["r:5"]?.done == false && q2.entries["r:5"]?.prev == .text("lauf"), "letzte Aktion gewinnt")
    q.put(key: "t:1", done: true, serverDone: false, prev: nil)
    check(q.pending.map(\.key) == ["r:9", "t:1"], "feste Reihenfolge")
    let r1 = TaskQueue.request(q.entries["r:9"]!)
    check(r1?.path == "api/tasks/src/9" && r1?.body["done"] as? Bool == true, "Zeile: PATCH /api/tasks/src")
    let r2 = TaskQueue.request(q.entries["t:1"]!)
    check(r2?.path == "api/tasks/1" && r2?.body["done"] as? Bool == true, "Inbox: PATCH /api/tasks")
    let reopen = TaskQueue.request(TaskQueueEntry(key: "r:3", done: false, prev: .text("lauf"), serverDone: true))
    check(reopen?.body["value"] as? String == "lauf", "offline wieder oeffnen mit gemerktem Wert (nie pauschal open_value)")
    check(TaskQueue.request(TaskQueueEntry(key: "x:1", done: true, prev: nil, serverDone: false)) == nil, "unbekannter Schluessel")
    check(TaskQueue.outcome(200) == .done && TaskQueue.outcome(403) == .drop && TaskQueue.outcome(404) == .drop && TaskQueue.outcome(400) == .drop, "2xx erledigt, 403/404/400 verwerfen")
    check(TaskQueue.outcome(nil) == .keep && TaskQueue.outcome(502) == .keep && TaskQueue.outcome(429) == .keep, "Netzfehler/5xx behalten")
    let qd = try? JSONDecoder().decode(TaskQueue.self, from: JSONEncoder().encode(q))
    check(qd == q, "Warteschlange speicherbar")

    // MARK: Widget (/api/app/today?sources=1)
    let td = decode(TodayData.self, "app_today.json")
    check(td?.items?.count == 6 && td?.more == 0 && td?.sources?.first?.title == "Projekt Alpha" && td?.sources?.first?.map == nil, "Heute mit Quellen")
    check(td?.tasks.count == 2, "bisherige Felder bleiben")
    let old = try? JSONDecoder().decode(TodayData.self, from: Data(#"{"date":"2026-10-28","events":[],"tasks":[{"id":3,"text":"a"}]}"#.utf8))
    check(old?.items == nil && old?.tasks.first?.id == 3, "aelterer Server ohne items")
}
