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

// Widget-Design-Runde (9.11.): Wert/Einheit, Zitat-Quelle, Gewohnheiten je Breite, Diagramm-Palette/Masse, Spalten-Umbruch
func widgetDesignTests() {
    typealias W = WidgetLogic
    // MARK: Wert und Einheit (Regel 5)
    check(W.valueParts("2.250,00 €") == .init(prefix: nil, number: "2.250,00", unit: "€"), "Euro klein dahinter")
    check(W.valueParts("42 Tage") == .init(prefix: nil, number: "42", unit: "Tage") && W.valueParts("68 %") == .init(prefix: nil, number: "68", unit: "%"), "Tage/Prozent")
    check(W.valueParts("12\u{00A0}°C").unit == "°C" && W.valueParts("12\u{00A0}°C").number == "12", "Grad mit geschuetztem Leerzeichen")
    check(W.valueParts("$1,250.50") == .init(prefix: "$", number: "1,250.50", unit: nil), "Waehrung davor")
    check(W.valueParts("09:32") == .init(prefix: nil, number: "09:32", unit: nil) && W.valueParts("1234").unit == nil && W.valueParts("–").unit == nil, "ohne Einheit")
    check(W.valueParts("12 Kilometer").unit == nil, "lange Woerter sind keine Einheit")

    // MARK: Zitat-Quelle (Regel 11)
    let q1 = W.quoteParts("Der Weg ist das Ziel. – Konfuzius")
    check(q1.text == "Der Weg ist das Ziel." && q1.source == "Konfuzius", "Quelle nach Satzende")
    let q2 = W.quoteParts("Erst denken,\n— Marie von Ebner-Eschenbach")
    check(q2.text == "Erst denken," && q2.source == "Marie von Ebner-Eschenbach", "Quelle nach Zeilenumbruch")
    check(W.quoteParts("Von 9 – 17 Uhr geöffnet").source == nil, "Strich mitten im Satz ist keine Quelle")
    check(W.quoteParts("Kurz. – klein").source == nil, "Quelle braucht Grossbuchstaben")
    check(W.quoteParts("Ein langer Satz hier. – Das ist kein Name sondern ein ganzer Satz.").source == nil, "Satz statt Name")
    check(W.quoteLines(.s) == 4 && W.quoteLines(.m) == 6 && W.quoteLines(.l) == 8, "Zitat-Zeilen S/M/L")

    // MARK: Gewohnheiten je Breite (Regel 12)
    check(W.habitLayout(days: 7, width: 300, size: .m) == .init(shown: 7, heat: false, header: true, oneLine: false, hint: nil), "7 Tage: Kreise")
    check(W.habitLayout(days: 7, width: 500, size: .m).oneLine, "breit: eine Zeile je Gewohnheit")
    check(W.habitLayout(days: 30, width: 500, size: .m) == .init(shown: 30, heat: true, header: true, oneLine: true, hint: nil), "30 Tage breit: Heatmap ganz")
    check(W.habitLayout(days: 30, width: 350, size: .m) == .init(shown: 14, heat: true, header: true, oneLine: false, hint: 14), "30 Tage schmal: letzte 14")
    check(W.habitLayout(days: 14, width: 350, size: .m).hint == nil && W.habitLayout(days: 14, width: 350, size: .m).shown == 14, "14 Tage schmal: alle")
    check(W.habitLayout(days: 30, width: 200, size: .m) == .init(shown: 7, heat: false, header: false, oneLine: false, hint: 7), "sehr schmal: letzte 7 als Kreise")
    check(W.habitLayout(days: 14, width: 350, size: .s) == .init(shown: 7, heat: true, header: true, oneLine: false, hint: 7), "Groesse S: letzte 7")
    // Zellmasse: fest quadratisch, Maximum wie --hbc, nie groesser bei breiter Karte (iPad-Fund: Zellen wuchsen)
    func geo(_ d: Int, _ w: Double, _ s: W.Size = .m) -> W.HabitGeom { W.habitGeom(width: w, layout: W.habitLayout(days: d, width: w, size: s), size: s) }
    check(geo(30, 350).cell == 13 && geo(30, 1000).cell == 13 && geo(30, 2000, .l).cell == 17, "Heatmap: Maximum 13/17 auch sehr breit")
    check(geo(30, 1000).name == 144 && geo(30, 500).name == 80, "breit: Name gibt Platz ab, wenn die Tage sonst schrumpfen")
    check(geo(30, 500).cell == 7.5, "30 Tage bei 500: Zellen schrumpfen statt Ueberlauf")
    check(geo(7, 200).cell == 22 && geo(7, 1100).cell == 22 && geo(7, 120).cell == 14 && geo(7, 300, .l).cell == 30, "Kreise: Maximum, schmal kleiner")
    for d in [7, 14, 30] { for s in [W.Size.s, .m, .l] { for w in stride(from: 120.0, through: 1400, by: 20) {
        let L = W.habitLayout(days: d, width: w, size: s), g = W.habitGeom(width: w, layout: L, size: s)
        let row = L.oneLine ? w - 2 * W.habitRowGap - W.habitStatsWidth - g.name : w
        let used = Double(L.shown) * g.cell + Double(L.shown - 1) * g.gap + 2 * W.habitRingPad
        if used > row + 0.01 || g.cell > W.habitCellMax(heat: L.heat, size: s) { check(false, "Gewohnheiten passen nicht: \(d) Tage, \(s), \(w)") }
    } } }
    check(W.weatherList(width: 250) && !W.weatherList(width: 400), "Wetter schmal als Liste")
    check(W.padding(.s) == (10, 12) && W.padding(.l) == (18, 20) && W.minHeight(.m) == 104 && W.chartHeight(.l) == 300, "Abstaende und Hoehen je Groesse")

    // MARK: Diagramm-Palette (Regel 14)
    typealias P = ChartData.Paint
    check(ChartData.colors([(false, nil), (false, nil), (false, nil)], one: false) == [P.named("blue"), .named("orange"), .named("green")], "Palette der Reihe nach")
    check(ChartData.colors([(false, "gray"), (false, nil), (false, "gray")], one: false) == [P.named("blue"), .named("orange"), .named("green")], "grau zaehlt als keine Farbe")
    check(ChartData.colors([(false, "blue"), (false, "gray"), (false, "green")], one: false) == [P.named("blue"), .named("gray"), .named("green")], "zwei echte Optionsfarben: Optionsfarben")
    check(ChartData.colors([(false, "red"), (false, nil)], one: false) == [P.named("blue"), .named("orange")], "eine echte Optionsfarbe reicht nicht")
    check(ChartData.colors([(false, nil), (true, nil), (false, nil)], one: false) == [P.named("blue"), .other, .named("orange")], "Andere/Leer neutral, zaehlt nicht mit")
    check(ChartData.colors([(false, nil), (false, "red")], one: true) == [P.one, .one], "eine Reihe einfarbig")
    var ring = ChartData(type: "donut", xKind: "cat", cats: [.init(key: "a", label: "Anna", color: "gray"), .init(key: "o", label: "Offen", color: nil), .init(key: nil, label: "", color: nil)],
                         series: [.init(key: "", label: "", color: nil, values: [12, 13, 6])], total: 31, fmt: "num", goal: nil, stack: false, cumulative: false, truncated: false, other: 0, rows: 8)
    check(ring.catColors == [P.named("blue"), .named("orange"), .other], "Ring: Option ohne Farbe nimmt die Palette, Leer neutral")
    check(ring.context(agg: "sum") == "Summe · 8 Einträge" && ring.context(agg: nil) == "Anzahl · 8 Einträge", "Kontextzeile Rechenart · Anzahl")
    check(ring.context(agg: "sum", yName: "Betrag", xName: "Person") == "Summe Betrag nach Person · 8 Einträge", "Kontextzeile mit Namen")
    ring.rows = nil; check(ring.context(agg: "count") == "Anzahl", "ohne Anzahl der Eintraege")
    let bar = ChartData(type: "bar", xKind: "cat", cats: [.init(key: "x", label: "X", color: nil), .init(key: "y", label: "Y", color: nil)],
                        series: [.init(key: "s1", label: "A", color: nil, values: [1, 2]), .init(key: "s2", label: "B", color: nil, values: [2, 1])],
                        total: 6, fmt: "num", goal: nil, stack: true, cumulative: false, truncated: false, other: 0)
    check(bar.multi && bar.seriesColors == [P.named("blue"), .named("orange")], "mehrere Reihen: Palette je Reihe")

    // MARK: Diagramm-Masse
    let l1 = ChartData.layout(width: 340, height: 220)
    check(l1.beside && l1.height == 220 && l1.diameter == 153 && l1.ticks == 5 && !l1.tiny, "breit: Ring neben der Legende, nicht bildschirmfuellend")
    let l2 = ChartData.layout(width: 250, height: 220)
    check(!l2.beside && l2.height == 200 && l2.diameter == 200 && l2.ticks == 3 && l2.legendMax == 6, "schmal: Ring ueber der Legende, 3 Ticks")
    check(ChartData.layout(width: 900, height: 140).diameter == 140 && ChartData.layout(width: 900, height: nil).height == 300, "Groesse S klein, Ansicht hoechstens 300")
    let lg = ChartData.legend(Array(1...9), max: 6)
    check(lg.shown == [1, 2, 3, 4, 5] && lg.more == 4 && ChartData.legend([1, 2, 3], max: 6).more == 0, "Legende mit + n weitere")
    let en = Locale(identifier: "en_US")
    check(ChartData.axisText(1500, fmt: "num", locale: en) == "1.5K" && ChartData.axisText(7.5, fmt: "num", locale: en) == "7.5" && ChartData.axisText(10, fmt: "num", locale: en) == "10", "Achse kompakt")

    // MARK: Wetterlage, Anstehendes S
    check(Widget2Logic.weatherText(0) == "Klar" && Widget2Logic.weatherText(3) == "Bedeckt" && Widget2Logic.weatherText(48) == "Nebel" && Widget2Logic.weatherText(99) == "Gewitter", "Wetterlage als Text")
    let it = Widget2Logic.Upcoming.Item(k: "task", title: "x", start: "2026-11-09", end: nil, allday: true, color: nil, cal: nil, note: nil, task: 1, overdue: false)
    let up6 = Widget2Logic.Upcoming(from: "2026-11-09", days: 7, more: 2, errors: 0, items: Array(repeating: it, count: 6))
    check(Widget2Logic.upcomingShown(up6, small: true).0.items.count == 4 && Widget2Logic.upcomingShown(up6, small: true).more == 4
          && Widget2Logic.upcomingShown(up6, small: false).more == 2 && Widget2Logic.upcomingShown(up6, small: false).0.items.count == 6, "Anstehendes S: 4 Eintraege + weitere")
    check(ChartData.aggLabel("pct_checked") == "% angehakt" && ChartData.aggLabel("avg") == "Durchschnitt", "Rechenart als Text")

    // MARK: Spalten (Regel 18)
    typealias C = ColumnsLogic
    check(C.rows(count: 4, width: 1000).map(\.items) == [[0, 1, 2, 3]] && C.rows(count: 4, width: 700) == [.init(items: [0, 1], slots: 2), .init(items: [2, 3], slots: 2)], "4 -> 2x2")
    check(C.rows(count: 3, width: 600) == [.init(items: [0, 1], slots: 2), .init(items: [2], slots: 1)] && C.rows(count: 3, width: 700).count == 1, "3 -> 2 + 1 (volle Breite)")
    check(C.rows(count: 2, width: 430).map(\.items) == [[0], [1]] && C.rows(count: 2, width: 500).count == 1, "unter 440 untereinander")
    check(C.rows(count: 5, width: 600).last == .init(items: [4], slots: 2) && C.rows(count: 6, width: 1200).map(\.items) == [[0, 1, 2], [3, 4, 5]], "5-6 Spalten")
    let wcol: NSArray = [["type": "widget", "data": ["kind": "clock"]]], tcol: NSArray = [["type": "paragraph"]]
    check(C.equalHeight([wcol, wcol]) && !C.equalHeight([wcol, tcol]) && !C.equalHeight([wcol, [wcol[0], wcol[0]] as NSArray]) && !C.equalHeight([wcol]), "gleich hoch nur bei je genau einem Widget")
}
