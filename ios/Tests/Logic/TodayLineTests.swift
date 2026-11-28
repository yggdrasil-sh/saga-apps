import Foundation

// 2.6 Tageszeile (Bugs-DB 2271 K-D, Shared/TodayLineLogic.swift) gegen die Server-Vektoren Tests/vectors/today_vectors.json
// (Kopie von tests/today_vectors.json aus dem Server-Repo, Format saga-today/v1): jede `response` decodieren und die
// Anzeige gegen `show` pruefen; dazu alter Server (404), 304, Ziele, Live, Pfade, Widget (Mittel/Gross/Klein/Sperre).
func todayLineTests() {
    let u = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("vectors/today_vectors.json")
    guard let raw = try? Data(contentsOf: u), let v = (try? JSONSerialization.jsonObject(with: raw)) as? [String: Any],
          let cases = v["cases"] as? [[String: Any]] else { check(false, "Tests/vectors/today_vectors.json fehlt"); return }
    check(v["format"] as? String == "saga-today/v1", "Vektoren: Format saga-today/v1")
    check(cases.count >= 21, "Vektoren: alle Faelle (\(cases.count))")
    func data(_ o: Any?) -> Data? {
        guard let o, !(o is NSNull) else { return nil }
        return try? JSONSerialization.data(withJSONObject: o)
    }
    func byID(_ id: String) -> [String: Any]? { cases.first { $0["id"] as? String == id } }
    func strs(_ o: Any?) -> [String] { (o as? [Any])?.compactMap { $0 as? String } ?? [] }

    /// show-Feld des Vektors mit der Anzeige vergleichen (Texte unveraendert vom Server)
    func same(_ got: TodayLineShow, _ want: [String: Any], _ id: String) {
        if let k = want["kurz"] as? [String: Any] {
            check(got.kurz?.label == k["label"] as? String, "\(id): label")
            check(got.kurz?.parts.map(\.text) == strs(k["parts"]), "\(id): Teile")
            check(got.kurz?.a11y == k["a11y"] as? String, "\(id): a11y")
        } else { check(got.kurz == nil, "\(id): keine Zeile") }
        if let p = want["pill"] as? [String: Any] {
            check(got.pill?.text == p["text"] as? String && got.pill?.undo == p["undo"] as? String, "\(id): Pille")
        } else { check(got.pill == nil, "\(id): keine Pille") }
        if let a = want["ask"] as? [String: Any] {
            check(got.ask?.text == a["text"] as? String && got.ask?.yes == a["yes"] as? String && got.ask?.no == a["no"] as? String, "\(id): Frage")
        } else { check(got.ask == nil, "\(id): keine Frage") }
        check(got.waiting?.text == want["waiting"] as? String, "\(id): Wartet auf dich")
    }

    let normal = TodayLineLogic.decode(data(byID("normal")?["response"]))
    let normalTag = (byID("normal")?["headers"] as? [String: Any])?["ETag"] as? String
    check(normal?.v != nil && TodayLineLogic.etag(normal?.v) == normalTag, "normal decodiert (v = ETag)")
    var tested = 0
    for c in cases {
        let id = c["id"] as? String ?? "?"
        let req = c["request"] as? [String: Any] ?? [:]
        let status = c["status"] as? Int ?? 0
        let show = c["show"] as? [String: Any] ?? [:]
        let path = req["path"] as? String ?? ""
        if path.hasPrefix("/api/app/today") {
            // Widget: TodayData mit line (Mittel/Gross Text, Klein/Sperre nur Zahlen wie bisher)
            guard let d = data(c["response"]), let td = try? JSONDecoder().decode(TodayData.self, from: d) else { check(false, "\(id): TodayData"); continue }
            check(TodayLineLogic.widgetText(td.line, size: .medium) == show["medium"] as? String, "\(id): Widget mittel")
            check(TodayLineLogic.widgetText(td.line, size: .large) == show["medium"] as? String, "\(id): Widget gross wie mittel")
            check(show["small"] as? String == "counts" && TodayLineLogic.widgetText(td.line, size: .small) == nil, "\(id): Widget klein nur Zahlen")
            check(show["lock"] as? String == "counts" && TodayLineLogic.widgetText(td.line, size: .lock) == nil, "\(id): Sperrbildschirm nur Zahlen")
            check(TodayLineLogic.widgetWaiting(td.line, size: .large) == show["waiting"] as? String, "\(id): Widget gross wartet")
            check(TodayLineLogic.widgetWaiting(td.line, size: .medium) == nil && TodayLineLogic.widgetWaiting(td.line, size: .small) == nil, "\(id): wartet nur gross")
            if td.line?.enabled == true { check(td.line?.counts != nil, "\(id): counts fuers Widget") }
            tested += 1
            continue
        }
        let r = TodayLineLogic.result(status: status, data: data(c["response"]))
        var st: TodayLineState?
        switch r {
        case .state(let s): st = s
        case .keep: st = normal                                       // 304: Anzeige des bisherigen Stands bleibt
        case .nothing: st = nil
        }
        if status == 304 { check(r == .keep, "\(id): 304 behaelt") }
        if status >= 400 { check(r == .nothing, "\(id): \(status) -> nichts") }
        same(TodayLineLogic.show(st), show, id)
        // `sentence` nie zeigen (nur fuer Siri decodiert, K-G)
        if let sen = (c["response"] as? [String: Any])?["sentence"] as? String {
            let shown = TodayLineLogic.show(st)
            check(!(shown.kurz?.parts.map(\.text) ?? []).contains(sen) && shown.kurz?.a11y != sen, "\(id): sentence nicht angezeigt")
        }
        if let h = c["headers"] as? [String: Any], let e = h["ETag"] as? String, let s = st, status == 200 {
            check(TodayLineLogic.etag(s.v) == e, "\(id): ETag = \"v\"")
        }
        tested += 1
    }
    check(tested == cases.count, "alle Vektoren geprueft (\(tested)/\(cases.count))")

    // --- Felder, nicht Texte -------------------------------------------------------------------------------------------
    let ohne = TodayLineLogic.result(status: 200, data: Data(#"{"enabled": false}"#.utf8))
    check(ohne == .nothing, "enabled false -> nichts")
    var aus = normal; aus?.on = false
    check(TodayLineLogic.show(aus).kurz == nil && TodayLineLogic.show(aus).waiting != nil, "on false: keine Zeile, wartet bleibt")
    var both = normal; both?.quiet = TodayQuiet(until: nil, text: "x", undo: "y")
    check(TodayLineLogic.show(both).kurz == nil && TodayLineLogic.show(both).pill?.text == "x", "Ruhe gewinnt ueber eine Zeile")
    check(TodayLineLogic.result(status: 500, data: data(byID("normal")?["response"])) == .nothing, "500 -> nichts")
    check(TodayLineLogic.result(status: 200, data: Data("kaputt".utf8)) == .nothing, "kein JSON -> nichts")
    check(TodayLineLogic.result(status: 304, data: nil) == .keep, "304 ohne Inhalt")
    let unknown = TodayLineLogic.decode(Data(#"{"enabled":true,"on":true,"label":"L","ideas":[1],"neu":{"x":1},"line":{"text":"a · b","parts":[{"kind":"x","text":"a"},{"text":5},{"kind":"due","text":"b","target":{"type":"zukunft","id":1}}]}}"#.utf8))
    check(unknown?.line?.parts.count == 2, "unbekannte Felder ignoriert, kaputter Teil ausgelassen")
    check(TodayLineLogic.nav(unknown?.line?.parts.first?.target) == nil && TodayLineLogic.nav(unknown?.line?.parts.last?.target) == nil, "ohne/unbekanntes Ziel -> nicht antippbar")

    // --- Ziele: oeffnen nur -----------------------------------------------------------------------------------------------
    check(normal?.line?.parts.map { TodayLineLogic.nav($0.target) } == [.events("2026-07-07"), .task(7), .row(512)], "Ziele Termin/Aufgabe/Zeile")
    check(TodayLineLogic.nav(normal?.waiting?.target) == .jobs("wartet_auf_dich"), "Wartet auf dich -> Saga › Aufträge")
    check(TodayLineLogic.nav(TodayLineTarget(type: "tasks", filter: "overdue")) == .tasks("overdue"), "Ziel tasks/overdue")
    check(TodayLineLogic.nav(TodayLineTarget(type: "task")) == nil, "task ohne id -> nicht antippbar")

    // --- Live, Pfade, Frage, Merkmal ------------------------------------------------------------------------------------
    let live = v["live"] as? [String: Any]
    check(live?["event"] as? String == "ai.today.changed" && live?["debounce_ms"] as? Int == TodayLineLogic.debounceMs, "Live-Ereignis und 400 ms")
    let lv = (live?["data"] as? [String: Any])?["v"] as? String
    check(!TodayLineLogic.liveNeedsReload(eventV: lv, own: normal?.v) && TodayLineLogic.liveNeedsReload(eventV: lv, own: "000000000000"), "Live: nur bei anderem v")
    check(TodayLineLogic.liveNeedsReload(eventV: nil, own: "x"), "Live ohne v: neu laden")
    check(TodayLineLogic.path(tz: "Europe/Berlin") == "api/ai/today?tz=Europe/Berlin", "Pfad mit tz")
    check(TodayLineLogic.quietPath(tz: "Etc/GMT+1") == "api/ai/today/quiet?tz=Etc/GMT%2B1", "tz: + kodiert")
    check(TodayLineLogic.widgetPath(tz: "America/Argentina/Buenos_Aires") == "api/app/today?sources=1&line=1&tz=America/Argentina/Buenos_Aires", "Widget-Pfad line=1&tz")
    check(TodayLineLogic.askBody(yes: true) == ["on": false, "asked": true] && TodayLineLogic.askBody(yes: false) == ["asked": true], "Frage Ja/Nein")
    check(!TodayLineLogic.shouldFetch(features: ["tasksrc1"]) && TodayLineLogic.shouldFetch(features: ["aitoday1"]), "nur mit aitoday1 fragen")

    // --- Widget: alte Antworten und kaputtes line ---------------------------------------------------------------------
    let oldW = try? JSONDecoder().decode(TodayData.self, from: Data(#"{"date":"2026-07-07","events":[],"tasks":[],"errors":[]}"#.utf8))
    check(oldW != nil && oldW?.line == nil, "Widget: aelterer Server ohne line")
    let badW = try? JSONDecoder().decode(TodayData.self, from: Data(#"{"date":"2026-07-07","events":[],"tasks":[],"line":"x"}"#.utf8))
    check(badW != nil && TodayLineLogic.widgetText(badW?.line, size: .medium) == nil, "Widget: kaputtes line bricht nichts")
    if let w = byID("widget"), let d = data(w["response"]), var td = try? JSONDecoder().decode(TodayData.self, from: d) {
        check(TodayLineLogic.widgetRows(td.line, size: .medium) == 1 && TodayLineLogic.widgetRows(td.line, size: .large) == 3 && TodayLineLogic.widgetRows(td.line, size: .small) == 0, "Widget-Zeilen")
        let enc = try? JSONEncoder().encode(td)                         // gemerkter Stand (WidgetSnapshot) uebersteht Speichern
        check(enc.flatMap { try? JSONDecoder().decode(TodayData.self, from: $0) }?.line == td.line, "Widget-Stand speichern/lesen")
        td.line?.on = false
        check(TodayLineLogic.widgetText(td.line, size: .medium) == nil, "Widget: aus -> keine Zeile")
    } else { check(false, "widget-Vektor") }
}
