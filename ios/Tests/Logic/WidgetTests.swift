import Foundation

// Saga 2.5 Widgets in Seiten (App/WidgetLogic.swift): Block lesen, Antwort, Hinweise, Uhr, Countdown, Fortschritt,
// Kennzahl-Format, Zitat-Auswahl, Rundreise (Regel 0)
func widgetTests() {
    func nd(_ d: [String: Any]) -> NSDictionary { NSDictionary(dictionary: d) }
    let utc = TimeZone(identifier: "UTC")!
    var cal = Calendar(identifier: .gregorian); cal.timeZone = utc
    func at(_ s: String) -> Date { ISO8601DateFormatter().date(from: s)! }
    let de = Locale(identifier: "de_DE"), en = Locale(identifier: "en_US")

    // MARK: Block lesen
    let b = WidgetLogic.block(nd(["v": 1, "kind": "metric", "size": "l", "style": "plain", "accent": "blue", "title": "  Offen  ", "cfg": ["db": 4, "agg": "count"]]))
    check(b.kind == "metric" && b.size == .l && b.style == .plain && b.accent == "blue" && b.title == "Offen" && b.known, "Block gelesen")
    let d0 = WidgetLogic.block(nd(["kind": "clock", "size": "xl", "style": "neon", "accent": "#f00", "title": String(repeating: "x", count: 99)]))
    check(d0.size == .m && d0.style == .card && d0.accent == nil && d0.title.count == 80, "Standardwerte, freie Farbe ignoriert, Titel begrenzt")
    check(!WidgetLogic.block(nd(["kind": "wetter"])).known && !WidgetLogic.block(nil).known, "unbekannte Art")

    // MARK: Server noetig? Seite durchsuchen (Spalten, Toggles)
    func blk(_ kind: String, _ cfg: [String: Any]) -> WidgetLogic.Block { WidgetLogic.block(nd(["kind": kind, "cfg": cfg])) }
    check(WidgetLogic.needsServer(blk("metric", [:])) && WidgetLogic.needsServer(blk("progress", ["src": "db"])) && WidgetLogic.needsServer(blk("quote", ["src": "fixed"])), "Server-Arten")
    check(!WidgetLogic.needsServer(blk("clock", [:])) && !WidgetLogic.needsServer(blk("countdown", [:])) && !WidgetLogic.needsServer(blk("progress", ["src": "fixed"])), "lokale Arten")
    let nested: NSArray = [
        ["id": "p", "type": "paragraph", "data": ["text": "x"]],
        ["id": "c", "type": "columns", "data": ["cols": [["blocks": [["id": "w1", "type": "widget", "data": ["kind": "clock"]]]],
                                                        ["blocks": [["id": "t", "type": "toggle", "data": ["blocks": [["id": "w2", "type": "widget", "data": ["kind": "metric", "cfg": [:]]]]]]]]]]],
    ]
    check(WidgetLogic.pageNeedsServer(nested), "Kennzahl im Toggle in einer Spalte gefunden")
    var ids: [String] = []; WidgetLogic.walk(nested) { if let i = $0["id"] as? String { ids.append(i) } }
    check(ids == ["p", "c", "w1", "t", "w2"], "alle Bloecke durchlaufen")
    check(!WidgetLogic.pageNeedsServer([["id": "w", "type": "widget", "data": ["kind": "clock"]]]), "nur Uhr: keine Anfrage")

    // MARK: Antwort
    let json = #"{"widgets":{"w2":{"kind":"metric","value":12.5,"max":null,"text":null,"fmt":"eur","as_of":"2026-10-27T08:00:00Z","error":null},"w3":{"kind":"progress","value":3,"max":4,"text":null,"fmt":null,"as_of":"2026-10-27T08:00:00.123Z","error":null},"w4":{"kind":"metric","value":null,"error":"no_access"},"w5":{"kind":"metric","value":true}}}"#
    guard let r = WidgetLogic.parse(Data(json.utf8)) else { check(false, "Antwort nicht lesbar"); return }
    check(r.widgets["w2"]?.value == 12.5 && r.widgets["w2"]?.fmt == "eur" && r.widgets["w2"]?.asOf == at("2026-10-27T08:00:00Z"), "Kennzahl-Wert")
    check(r.widgets["w3"]?.max == 4 && r.widgets["w3"]?.asOf != nil, "Bruchteil-Sekunden im Zeitstempel")
    check(r.widgets["w4"]?.error == "no_access" && r.widgets["w4"]?.value == nil, "Fehler gelesen")
    check(r.widgets["w5"]?.value == nil, "true ist keine Zahl")
    check(!r.unsupported && WidgetLogic.parse(Data(#"{"widgets":{},"unsupported":true}"#.utf8))?.unsupported == true, "unsupported")
    check(WidgetLogic.parse(Data("kaputt".utf8)) == nil, "kaputte Antwort")
    check(WidgetLogic.load(code: 404, data: Data()) == .missing && WidgetLogic.load(code: 500, data: Data()) == .failed
          && WidgetLogic.load(code: 200, data: Data("x".utf8)) == .failed, "404 -> aelterer Server, sonst Fehler")
    if case .loaded(let rr) = WidgetLogic.load(code: 200, data: Data(json.utf8)) { check(rr == r, "200 -> Werte") } else { check(false, "200 -> Werte") }
    check(WidgetLogic.merge(old: .loaded(r), new: .failed) == .loaded(r) && WidgetLogic.merge(old: .idle, new: .failed) == .failed
          && WidgetLogic.merge(old: .loaded(r), new: .missing) == .missing, "offline behaelt alte Werte")

    // MARK: Hinweise
    let metric = blk("metric", ["db": 1])
    check(WidgetLogic.notice(block: blk("wetter", [:]), load: .loaded(r), value: nil) == .newerVersion, "neuere Version")
    check(WidgetLogic.notice(block: metric, load: .loaded(r), value: r.widgets["w4"]) == .noAccess, "kein Zugriff")
    for (e, n) in [("gone", WidgetLogic.Notice.gone), ("bad_cfg", .badCfg), ("too_big", .tooBig), ("neu", .unknownError)] {
        check(WidgetLogic.notice(error: e) == n, "Fehler \(e)")
    }
    check(WidgetLogic.notice(block: metric, load: .missing, value: nil) == .olderServer, "404 -> Hinweis")
    check(WidgetLogic.notice(block: metric, load: .loaded(WidgetLogic.Response(widgets: [:], unsupported: true)), value: nil) == .originOnly, "Foederation ohne widgets1")
    check(WidgetLogic.notice(block: metric, load: .failed, value: nil) == .failed && WidgetLogic.notice(block: metric, load: .idle, value: nil) == nil, "offline / noch nicht geladen")
    check(WidgetLogic.notice(block: blk("clock", [:]), load: .missing, value: nil) == nil, "Uhr auch mit altem Server")
    let fixedQuote = blk("quote", ["src": "fixed", "items": ["a", "b"]])
    check(WidgetLogic.notice(block: fixedQuote, load: .missing, value: nil) == nil, "festes Zitat: lokal statt Hinweis")
    check(WidgetLogic.value(.loaded(r), blockID: "w2")?.value == 12.5 && WidgetLogic.value(.missing, blockID: "w2") == nil, "Wert je Block")

    // MARK: Uhr
    let noon = at("2026-10-27T11:05:09Z")
    check(WidgetLogic.clockTime(noon, cfg: ["tz": "Europe/Berlin", "h24": true], locale: de) == "12:05", "Berlin 24 h")
    check(WidgetLogic.clockTime(noon, cfg: ["tz": "Europe/Berlin", "h24": true, "sec": true], locale: de) == "12:05:09", "mit Sekunden")
    let us = WidgetLogic.clockTime(noon, cfg: ["tz": "America/New_York", "h24": false], locale: en)
    check(us.hasPrefix("7:05") && us.contains("AM"), "New York 12 h (\(us))")
    check(WidgetLogic.clockTime(noon, cfg: ["tz": "", "h24": true], locale: de, viewer: utc) == "11:05", "leer = Betrachter")
    check(WidgetLogic.clockTime(noon, cfg: ["tz": "Mars/Olymp"], locale: de, viewer: utc) == "11:05", "unbekannte Zone = Betrachter")
    check(WidgetLogic.zoneLabel(["tz": "America/Argentina/Buenos_Aires"]) == "Buenos Aires" && WidgetLogic.zoneLabel(["tz": ""]) == "", "Ortsname")
    check(WidgetLogic.clockDate(noon, cfg: ["tz": "UTC"], locale: de).contains("27. Oktober"), "Datum unter der Uhr")
    let th = WidgetLogic.clockDate(noon, cfg: ["tz": "UTC"], locale: Locale(identifier: "th_TH"))
    check(!th.contains("2569"), "Thai gregorianisch")
    let h = WidgetLogic.hands(at("2026-10-27T15:30:15Z"), cfg: ["tz": "UTC"])
    check(h == WidgetLogic.Hands(hour: 105, minute: 181.5, second: 90), "Zeigerwinkel 15:30:15")

    // MARK: riesige Werte (unbereinigt, z. B. von einem aelteren Server) stuerzen nicht ab
    let huge = WidgetLogic.Progress(value: 1e11, max: 1e-300)
    check(huge.percent == 1_000_000_000 && huge.fraction == 1, "riesiger Fortschritt begrenzt")
    check(WidgetLogic.Progress(value: -1e300, max: 1e-10).percent == -1_000_000_000, "negativ begrenzt")

    // MARK: Countdown (UTC)
    let now = at("2026-10-27T10:00:00Z")
    check(WidgetLogic.countdown(["date": "2026-11-06"], now: now, calendar: cal) == .days(10, since: false), "noch 10 Tage")
    check(WidgetLogic.countdown(["date": "2026-10-27"], now: now, calendar: cal) == .today, "heute")
    check(WidgetLogic.countdown(["date": "2026-10-20"], now: now, calendar: cal) == .reached, "Ziel vorbei")
    check(WidgetLogic.countdown(["date": "2026-10-20", "dir": "since"], now: now, calendar: cal) == .days(7, since: true), "seit 7 Tagen")
    check(WidgetLogic.countdown(["date": "2026-11-01", "dir": "since"], now: now, calendar: cal) == .notYet, "seit: Zukunft")
    check(WidgetLogic.countdown(["date": "2026-10-27", "time": "13:30", "unit": "auto"], now: now, calendar: cal) == .hoursMinutes(3, 30, since: false), "auto: Std./Min.")
    check(WidgetLogic.countdown(["date": "2026-10-27", "time": "10:00", "unit": "auto", "dir": "since"], now: at("2026-10-27T10:59:59Z"), calendar: cal) == .hoursMinutes(0, 59, since: true), "seit: abgerundet")
    check(WidgetLogic.countdown(["date": "2026-10-27", "time": "10:00", "unit": "auto"], now: at("2026-10-27T09:59:30Z"), calendar: cal) == .hoursMinutes(0, 1, since: false), "bis: aufgerundet")
    check(WidgetLogic.countdown(["date": "2026-10-29", "time": "12:00", "unit": "auto"], now: now, calendar: cal) == .days(2, since: false), "auto ueber einem Tag: Tage")
    check(WidgetLogic.countdown(["date": "2026-10-27", "time": "09:00", "unit": "auto"], now: now, calendar: cal) == .reached, "auto: erreicht")
    check(WidgetLogic.countdown(["date": "2026-02-31"], now: now, calendar: cal) == .invalid && WidgetLogic.countdown(["date": "27.10.2026"], now: now, calendar: cal) == .invalid
          && WidgetLogic.countdown(["date": "2026-10-27", "time": "25:00"], now: now, calendar: cal) == .invalid && WidgetLogic.countdown([:], now: now, calendar: cal) == .invalid, "ungueltige Ziele")
    var berlin = Calendar(identifier: .gregorian); berlin.timeZone = TimeZone(identifier: "Europe/Berlin")!
    check(WidgetLogic.countdown(["date": "2026-10-28"], now: at("2026-10-27T23:30:00Z"), calendar: berlin) == .today, "Tag des Betrachters (Berlin)")
    var buddhist = Calendar(identifier: .buddhist); buddhist.timeZone = utc
    check(WidgetLogic.countdown(["date": "2026-11-06"], now: now, calendar: buddhist) == .days(10, since: false), "buddhistischer Kalender -> gregorianisch")

    // MARK: Fortschritt
    let pf = WidgetLogic.progress(blk("progress", ["src": "fixed", "value": 3, "max": 8]), value: nil)
    check(pf == WidgetLogic.Progress(value: 3, max: 8) && pf?.percent == 38 && abs((pf?.fraction ?? 0) - 0.375) < 1e-9, "fest 3/8")
    check(WidgetLogic.progress(blk("progress", ["value": 12, "max": 10]), value: nil)?.fraction == 1
          && WidgetLogic.progress(blk("progress", ["value": 12, "max": 10]), value: nil)?.percent == 120, "ueber 100 %: Balken voll")
    check(WidgetLogic.progress(blk("progress", ["value": 1, "max": 0]), value: nil) == nil && WidgetLogic.progress(blk("progress", ["value": true, "max": 2]), value: nil) == nil, "ungueltig")
    check(WidgetLogic.progress(blk("progress", ["src": "db", "value": 1, "max": 2]), value: r.widgets["w3"]) == WidgetLogic.Progress(value: 3, max: 4), "db: Server-Wert")
    check(WidgetLogic.progress(blk("progress", ["src": "db"]), value: nil) == nil, "db ohne Wert")

    // MARK: Kennzahl-Format
    check(WidgetLogic.format(1234.5, fmt: "num", locale: de) == "1.234,5" && WidgetLogic.format(1234.5, fmt: "num", locale: en) == "1,234.5", "Zahl lokalisiert")
    check(WidgetLogic.format(2.0 / 3, fmt: "num", locale: en) == "0.67", "zwei Nachkommastellen")
    let pct = WidgetLogic.format(42.5, fmt: "pct", locale: de)
    check(pct.hasPrefix("43") && pct.hasSuffix("%") && !pct.contains(","), "Prozent ganzzahlig (\(pct))")
    let eur = WidgetLogic.format(1234.5, fmt: "eur", locale: de)
    check(eur.contains("1.234,50") && eur.contains("€"), "Euro de (\(eur))")
    check(WidgetLogic.format(9.5, fmt: "eur", locale: en).contains("€9.50"), "Euro en")
    check(WidgetLogic.format(nil, fmt: "num", locale: de) == "–" && WidgetLogic.format(.nan, fmt: "num", locale: de) == "–", "kein Wert")
    check(WidgetLogic.metricFmt(blk("metric", ["fmt": "pct"]), value: nil) == "pct" && WidgetLogic.metricFmt(blk("metric", ["fmt": "pct"]), value: r.widgets["w2"]) == "eur"
          && WidgetLogic.metricFmt(blk("metric", ["fmt": "bitcoin"]), value: nil) == "num", "Format: Server vor Block, Unbekanntes -> num")
    check(WidgetLogic.goalReached(blk("metric", ["goal": 10]), value: r.widgets["w2"]) == true && WidgetLogic.goalReached(blk("metric", ["goal": 20]), value: r.widgets["w2"]) == false
          && WidgetLogic.goalReached(blk("metric", [:]), value: r.widgets["w2"]) == nil, "Ziel")

    // MARK: Zitat
    let qi = ["a", " ", "b", "c"]
    check(WidgetLogic.items(["items": qi + [5]]) == ["a", "b", "c"] && WidgetLogic.items(["items": Array(repeating: "x", count: 30)]).count == 20, "Texte bereinigt")
    let q = blk("quote", ["src": "fixed", "items": qi, "pick": "daily"])
    let t1 = WidgetLogic.quoteText(q, value: nil, local: true, blockID: "q1", now: now, calendar: cal, seed: 1)
    let t2 = WidgetLogic.quoteText(q, value: nil, local: true, blockID: "q1", now: now.addingTimeInterval(3600), calendar: cal, seed: 99)
    check(t1 != nil && t1 == t2, "daily: am selben Tag gleich, unabhaengig vom Wuerfel")
    var days = Set<String>()
    for i in 0..<20 { if let t = WidgetLogic.quoteText(q, value: nil, local: true, blockID: "q1", now: now.addingTimeInterval(Double(i) * 86_400), calendar: cal, seed: 0) { days.insert(t) } }
    check(days.count > 1, "daily: wechselt ueber die Tage")
    check(WidgetLogic.fnv1a("") == 0xcbf29ce484222325 && WidgetLogic.fnv1a("a") == 0xaf63dc4c8601ec8c, "FNV-1a stabil")
    let qo = blk("quote", ["items": qi, "pick": "open"])
    check(WidgetLogic.quoteText(qo, value: nil, local: true, blockID: "q", now: now, calendar: cal, seed: 4) == "b", "open: aus dem Wuerfel")
    check(WidgetLogic.quoteText(q, value: WidgetLogic.Value(kind: "quote", text: " Vom Server "), local: true, blockID: "q1", now: now, calendar: cal, seed: 0) == "Vom Server", "Server-Text gewinnt")
    check(WidgetLogic.quoteText(q, value: nil, local: false, blockID: "q1", now: now, calendar: cal, seed: 0) == nil, "waehrend des Ladens kein lokaler Text")
    check(WidgetLogic.quoteText(blk("quote", ["src": "db"]), value: nil, local: true, blockID: "q", now: now, calendar: cal, seed: 0) == nil, "db ohne Text")
    check(!WidgetLogic.quoteLocal(.idle) && WidgetLogic.quoteLocal(.missing) && WidgetLogic.quoteLocal(.failed), "lokale Auswahl erst nach dem Laden")

    // MARK: Regel 0 - Widget-Block (auch unbekannte Art, in Spalten) bleibt beim Speichern unveraendert
    let body = #"""
    {"time":1,"blocks":[
      {"id":"w","type":"widget","data":{"v":1,"kind":"metric","size":"s","style":"card","accent":"red","title":"T","cfg":{"db":7,"view":"v1","agg":"sum","prop":"p","fmt":"eur","goal":100,"zukunft":[1]}},"tunes":{"x":1}},
      {"id":"k","type":"columns","data":{"cols":[{"blocks":[{"id":"w9","type":"widget","data":{"v":2,"kind":"wetter","cfg":{"ort":"x"}}}]},{"blocks":[]}]}},
      {"id":"p","type":"paragraph","data":{"text":"a"}}
    ]}
    """#
    guard let doc = GestaltenLogic.parseDoc(body), let blocks = doc["blocks"] as? NSMutableArray else { check(false, "Widget-Seite nicht lesbar"); return }
    let before = try! JSONSerialization.jsonObject(with: Data(body.utf8)) as! [String: Any]
    for case let x as NSDictionary in blocks { _ = WidgetLogic.block(x["data"] as? NSDictionary) }   // Anzeige liest nur
    ((blocks[2] as? NSMutableDictionary)?["data"] as? NSMutableDictionary)?["text"] = "b"
    guard let out = GestaltenLogic.serialize(doc, blocks: blocks, timeMs: 2),
          let again = try? JSONSerialization.jsonObject(with: Data(out.utf8)) as? [String: Any],
          let bs = again["blocks"] as? [[String: Any]], let bb = before["blocks"] as? [[String: Any]] else { check(false, "Widget-Rundreise"); return }
    check(NSDictionary(dictionary: bs[0]).isEqual(to: bb[0]), "Widget-Block unveraendert")
    check(NSDictionary(dictionary: bs[1]).isEqual(to: bb[1]), "unbekannte Widget-Art in Spalte unveraendert")
    check(GestaltenLogic.knownTypes.contains("widget") && !GestaltenLogic.needsFallback("widget"), "widget ist bekannt (keine Rueckfall-Karte)")
}
