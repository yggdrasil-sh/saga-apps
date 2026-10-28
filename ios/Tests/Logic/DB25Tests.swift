import Foundation

// Saga 2.5 App-Runde Etappe 1: Formeln/Rollups, Personen, Dateien, Datum mit Uhrzeit (Tag/Tag), relative Filter
// (Teilmenge der Server-Vektoren), Status, Unteraufgaben, Tabellen-Felder, Inline-Formen, Diagramme, Widgets 2,
// Zeitleiste. Fixtures: echte Antworten eines Testservers (Tests/Logic/Fixtures25, Wegwerf-Konten).

private func fx(_ name: String) -> Data {
    let dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent().appendingPathComponent("Fixtures25")
    return (try? Data(contentsOf: dir.appendingPathComponent(name + ".json"))) ?? Data()
}
private func fxObj(_ name: String) -> [String: Any] { (try? JSONSerialization.jsonObject(with: fx(name))) as? [String: Any] ?? [:] }

func db25Tests() {
    // MARK: Formeln/Rollups aus /rows
    guard var q = DBQuery.parse(fx("rows_liste")) else { check(false, "rows_liste lesen"); return }
    let byTitle = Dictionary(q.rows.map { ($0.title, $0) }, uniquingKeysWith: { a, _ in a })
    let summe = q.prop("summe")!, tag = q.prop("tag")!, spaet = q.prop("spaet")!, pbud = q.prop("pbud")!, pnam = q.prop("pnam")!, quot = q.prop("quot")!
    check(summe.isFx && summe.valueType == "number" && tag.valueType == "text" && pnam.valueType == "list", "rtype gelesen")
    check(FxLogic.cell(summe, value: q.value(summe, byTitle["Planen"]!), err: nil) == .number(7), "Formel Zahl")
    check(FxLogic.cell(tag, value: q.value(tag, byTitle["Planen"]!), err: nil) == .text("Dienstag"), "Formel Text (tz Europe/Berlin)")
    check(FxLogic.cell(spaet, value: q.value(spaet, byTitle["Bauen"]!), err: nil) == .check(true), "Formel Haekchen")
    check(FxLogic.cell(pbud, value: q.value(pbud, byTitle["Planen"]!), err: nil) == .number(1250.5), "Rollup Summe")
    check(FxLogic.cell(pnam, value: q.value(pnam, byTitle["Planen"]!), err: nil) == .list(["Alpha", "Beta"]), "Rollup Liste")
    check(FxLogic.cell(pnam, value: q.value(pnam, byTitle["Ohne"]!), err: nil) == .empty, "leere Liste")
    let kaputt = byTitle["Kaputt"]!
    check(kaputt.fxErr["quot"] == "num" && FxLogic.cell(quot, value: kaputt.props["quot"]?.base, err: kaputt.fxErr["quot"]) == .error("num"), "fx_err -> #Fehler")
    check(FxLogic.computed(q.props) == ["summe", "quot", "tag", "spaet", "pbud", "pnam"], "berechnete Spalten nie senden")
    // PATCH-Antwort: calc uebernehmen
    var bauen = byTitle["Bauen"]!
    FxLogic.apply(patch: fxObj("patch_calc"), to: &bauen)
    check(bauen.props["summe"] == AnyHashable(4.0) && bauen.props["tag"] == AnyHashable("Mittwoch") && bauen.fxErr.isEmpty, "calc aus PATCH")

    // MARK: Personen
    let dir = PeopleLogic.directory(q.people)
    let wer = q.prop("wer")!
    let ps = PeopleLogic.persons(q.value(wer, byTitle["Bauen"]!), dir)
    check(ps.map(PeopleLogic.label) == ["Anna Beispiel", "Bert Beispiel"] && ps[0].me && !ps[1].me, "Personen mit Namen, ich")
    check(PeopleLogic.persons(["~zzzzzzzzzzzzzzzz"], dir).first?.gone == true, "unbekannte Kennung -> geloescht")
    check(PeopleLogic.label(DBPerson(ref: "~x", name: "", gone: true)) == "Gelöschte Person", "Text geloescht")
    let pick = PeopleLogic.picker(fx("people_q"))
    check(pick.count == 2 && pick.allSatisfy { $0.ref.hasPrefix("~") } && !String(decoding: fx("people_q"), as: UTF8.self).contains("fx-"), "Auswahl ohne Anmeldenamen")
    check(PeopleLogic.toggle(["a"], "b", multi: false) == ["b"] && PeopleLogic.toggle(["a"], "b", multi: true) == ["a", "b"] && PeopleLogic.toggle(["a", "b"], "a", multi: true) == ["b"], "Personen umschalten")
    check(q.me == dir.values.first { $0.me }?.ref, "eigene Kennung")
    check(q.rows(for: ["filters": [["pid": "wer", "op": "me"]]]).map(\.title).sorted() == ["Bauen", "Planen"], "Filter ich")
    check(q.rows(for: ["sorts": [["pid": "wer", "dir": "asc"]]]).map(\.title).prefix(3) == ["Planen", "Bauen", "Testen"], "Sortierung nach erstem Namen")

    // MARK: Dateien
    let anh = q.prop("anh")!
    let files = FilesLogic.files(q.value(anh, byTitle["Planen"]!))
    guard files.count == 1 else { check(false, "Datei-Wert gelesen: \(String(describing: q.value(anh, byTitle["Planen"]!)))"); return }
    check(files[0].name == "plan.pdf" && files[0].url.hasPrefix("/files/") && files[0].size == 13, "Datei-Wert")
    check(FilesLogic.symbol(files[0]) == "doc.richtext", "PDF-Symbol")
    let more = FilesLogic.adding(files, url: "/files/x", name: "b.png", max: 10)!
    check(FilesLogic.stored(more).map { $0.keys.sorted() } == [["name", "url"], ["name", "url"]], "nur url+name speichern")
    check(FilesLogic.adding(files, url: "/files/y", name: "c", max: 1) == nil && FilesLogic.adding(files, url: files[0].url, name: "x", max: 1) == files, "Grenze max")
    let pf = fxObj("patch_files")
    var planen = byTitle["Planen"]!
    FxLogic.apply(patch: pf, to: &planen)
    check(FilesLogic.files(planen.props["anh"]?.base).count == 1, "values aus PATCH")

    // MARK: Status
    let st = q.prop("status")!
    check(st.status && st.options.map { $0.group ?? "" } == ["todo", "doing", "doing", "done"], "Status-Gruppen")
    check(StatusLogic.grouped(st).map(\.group) == ["todo", "doing", "done"] && StatusLogic.grouped(st)[1].options.count == 2, "Auswahl nach Gruppen")
    check(StatusLogic.toggled(st, current: "neu") == "ok" && StatusLogic.toggled(st, current: "ok") == "neu", "Abhaken = erste Option der Gruppe")
    check(StatusLogic.drop(st, current: "lauf", group: "doing") == nil && StatusLogic.drop(st, current: "neu", group: "doing") == .some("lauf"), "Board ziehen")
    check(q.rows(for: ["filters": [["pid": "status", "op": "status_group", "value": "open"]]]).count == 3, "status_group open (leer passt nie)")
    if let qb = DBQuery.parse(fx("rows_board")) {
        let g = GroupLogic.groups(qb, rows: qb.rows, by: qb.prop("status")!, byStatus: true)
        check(g.map(\.id) == [nil, "todo", "doing", "done"] && g.map(\.count) == [1, 1, 2, 1], "Board nach Status-Gruppen \(g.map(\.count))")
    }
    // Gruppen nach Formel (Wochentag): wie der Server Mo..So
    if let qw = DBQuery.parse(fx("rows_wtag")), let srv = fxObj("rows_wtag")["groups"] as? [[String: Any]] {
        let g = GroupLogic.groups(qw, rows: qw.rows, by: qw.prop("tag")!, byStatus: false)
        check(g.map(\.id) == srv.map { $0["id"] as? String }, "Gruppen nach Formel wie Server \(g.map(\.id))")
        check(!GroupLogic.draggable(qw.prop("tag")!) && GroupLogic.draggable(qw.prop("status")!), "Formel-Gruppen nur lesend")
    }

    // MARK: Unteraufgaben und blockiert
    let nested = q.rows(for: q.view("liste"))
    check(nested.map(\.title) == q.rows.map(\.title) || nested.first { $0.title == "Testen" }?.depth == 1, "eingerueckt")
    let srvOrder = (fxObj("rows_liste")["rows"] as? [[String: Any]] ?? []).map { $0["title"] as? String ?? "" }
    check(nested.map(\.title) == srvOrder, "Reihenfolge wie Server \(nested.map(\.title))")
    check(byTitle["Testen"]!.blocked && byTitle["Testen"]!.depth == 1 && byTitle["Testen"]!.subOf == byTitle["Bauen"]!.id, "blocked/depth/sub_of gelesen")
    check(q.prop("par")?.role == "parent" && q.prop("blk")?.role == "blocked_by", "Rollen")

    // MARK: Datum mit Uhrzeit / Zeitraum
    let berlin = TimeZone(identifier: "Europe/Berlin")!, ny = TimeZone(identifier: "America/New_York")!, bkk = TimeZone(identifier: "Asia/Bangkok")!
    let de = Locale(identifier: "de_DE"), now = DBDateTestNow
    check(DBDate.display("2026-10-27T14:30+01:00", tz: berlin, locale: de, now: now) == "27. Okt., 14:30", "Zeitpunkt Berlin: \(DBDate.display("2026-10-27T14:30+01:00", tz: berlin, locale: de, now: now) ?? "")")
    check(DBDate.display("2026-10-27T14:30+01:00", tz: ny, locale: de, now: now) == "27. Okt., 09:30", "in New York umgerechnet")
    check(DBDate.display("2026-10-29T14:30+01:00/2026-10-29T16:00+01:00", tz: berlin, locale: de, now: now) == "29. Okt., 14:30 – 16:00", "Zeitraum gleicher Tag")
    check(DBDate.display("2026-10-28/2026-10-30", tz: berlin, locale: de, now: now) == "28. Okt. – 30. Okt.", "Zeitraum Tage")
    check(DBDate.display("2025-10-28", tz: berlin, locale: de, now: now) == "28. Okt. 2025", "anderes Jahr mit Jahr")
    check(DBDate.originalHint("2026-10-27T14:30+01:00", tz: ny) != nil && DBDate.originalHint("2026-10-27T14:30+01:00", tz: berlin) == nil, "Originalzeit-Hinweis")
    check(DBDate.part("2026-02-30") == nil && DBDate.part("2026-10-27T24:00+01:00") == nil && DBDate.part("1800-01-01T10:00+00:00") == nil, "streng wie Server")
    check(DBDate.spans("2026-10-27T23:30+02:00", bkk).map { DBDate.iso($0.0) } == "2026-10-28", "Tag in Bangkok")
    check(DBDate.stamp(Date(timeIntervalSince1970: 1793107800), tz: berlin) == "2026-10-27T14:30+01:00", "Schreiben mit Offset \(DBDate.stamp(Date(timeIntervalSince1970: 1793107800), tz: berlin))")
    let d27 = Date(timeIntervalSince1970: 1793107800)                     // 27.10.2026 14:30 Berlin
    check(DBDate.compose(start: d27, startTime: false, end: nil, endTime: false, old: "2026-10-27T09:00+01:00", tz: berlin) == "2026-10-27/2026-10-27", "Uhrzeit entfernen = Tag/Tag")
    check(DBDate.compose(start: d27, startTime: false, end: nil, endTime: false, old: "2026-10-27", tz: berlin) == "2026-10-27", "reiner Tag bleibt Tag")
    check(DBDate.compose(start: d27, startTime: false, end: nil, endTime: false, old: "2026-10-25T09:00+01:00", tz: berlin) == "2026-10-27", "anderer Tag: nur Tag")
    check(DBDate.compose(start: d27, startTime: true, end: d27.addingTimeInterval(3600), endTime: true, old: nil, tz: berlin) == "2026-10-27T14:30+01:00/2026-10-27T15:30+01:00", "Zeitraum mit Uhrzeit")
    check(DBDate.compose(start: d27, startTime: false, end: d27.addingTimeInterval(86400 * 2), endTime: false, old: nil, tz: berlin) == "2026-10-27/2026-10-29", "Zeitraum Tage schreiben")
    let rules = fxObj("date_rules")
    check(rules["day_only"] as? String == "2026-10-27T09:00+01:00" && rules["day_day"] as? String == "2026-10-27", "Server: Tag behaelt Uhrzeit, Tag/Tag entfernt sie")
    check(DBDate.civil(DBDate.days(2026, 10, 27)) == (2026, 10, 27) && DBDate.weekday(DBDate.days(2026, 10, 26)) == 0, "Tagesnummern, Montag")

    // MARK: Relative Filter wie der Server (Teilmenge tests/dbquery_vectors.json)
    q.tz = berlin; q.today = "2026-10-27"
    check(q.rows(for: q.view("bald")).map(\.title) == ["Planen", "Bauen", "Testen"], "next_days 7")
    if let v = fxObj("relfilter_vectors")["cases"] as? [[String: Any]], let dbs = fxObj("relfilter_vectors")["dbs"] as? [String: Any] {
        var ok = 0, bad = 0
        for c in v {
            guard let raw = dbs["\(c["db"] as? Int ?? -1)"] as? [String: Any],
                  let data = try? JSONSerialization.data(withJSONObject: ["schema": raw["schema"] ?? [:], "rows": raw["rows"] ?? []]),
                  var vq = DBQuery.parse(data) else { bad += 1; continue }
            vq.tz = TimeZone(identifier: c["tz"] as? String ?? "UTC") ?? .gmt; vq.today = c["today"] as? String
            let got = vq.rows(for: ["filters": c["filters"] ?? []]).map(\.id).sorted()
            if got == (c["ids"] as? [Int] ?? []) { ok += 1 } else { bad += 1; if bad <= 3 { print("  relfilter \(c["filters"] ?? "") \(c["today"] ?? "") \(c["tz"] ?? ""): \(got) != \(c["ids"] ?? "")") } }
        }
        check(bad == 0 && ok > 50, "relative Filter wie Server: \(ok) ok, \(bad) falsch")
    } else { check(false, "relfilter_vectors lesen") }

    // MARK: Tabellen-Block (tableopts1)
    let page = fxObj("inline_page")["blocks"] as? [[String: Any]] ?? []
    let tbl = page.first { $0["type"] as? String == "table" }?["data"] as? [String: Any] ?? [:]
    check(TableOpts.widths(tbl, cols: 2) == [0.3, 0.7] && TableOpts.widths(tbl, cols: 3).count == 3 && TableOpts.headCol(tbl), "Breiten/Kopfspalte")
    check(TableOpts.color(tbl, row: 1, col: 1) == "red" && TableOpts.color(tbl, row: 1, col: 0) == "blue" && TableOpts.color(tbl, row: 0, col: 0) == nil, "Zelle > Zeile")
    // Bearbeiten einer Zelle: alle Felder ausser content bleiben (Regel 0)
    let td = NSMutableDictionary(dictionary: tbl)
    var tm = TableModel(content: td["content"]); tm.set(tm.rows[1].id, tm.cols[0], "neu"); td["content"] = tm.content
    check((td["colWidths"] as? [Any])?.count == 2 && td["colors"] != nil && td["headCol"] as? Bool == true, "Tabellenfelder bleiben beim Bearbeiten")

    // MARK: Inline-Formen und Vorschaubilder
    let para = (page.first { $0["id"] as? String == "p1" }?["data"] as? [String: Any])?["text"] as? String ?? ""
    check(InlineLogic.hasProtectedForms(para) && !InlineLogic.hasProtectedForms("<b>x</b> <mark class=\"cdx-marker\">y</mark> <code>z</code>"), "geschuetzte Inline-Formen erkannt")
    check(InlineLogic.colorClass(" class=\"hk-c-red\"", prefix: "hk-c-") == "red" && InlineLogic.colorClass(" class=\"hk-c-lila\"", prefix: "hk-c-") == nil, "Farbklasse")
    check(InlineLogic.mentionRef(" class=\"hk-mention\" data-u=\"~abc\"") == "~abc", "Erwaehnung data-u")
    check(InlineLogic.capsHeader == nil, "X-Saga-Caps noch nicht setzen (Formen werden noch nicht bearbeitet)")
    let math = page.first { $0["type"] as? String == "math" }?["data"] as? [String: Any] ?? [:]
    let mmd = page.first { $0["id"] as? String == "g1" }?["data"] as? [String: Any] ?? [:]
    check(RenderPreview.parse(math)?.stale == true && RenderPreview.parse(mmd)?.stale == false && RenderPreview.isMermaid(mmd), "render: stale/Mermaid")
    let cols = (page.first { $0["type"] as? String == "columns" }?["data"] as? [String: Any])?["cols"] as? [Any]
    check(cols?.count == 6 && ColumnsLogic.maxColumns == 6, "6 Spalten bleiben")
    check(ColumnsLogic.perRow(count: 6, width: 1200) == 6 && ColumnsLogic.perRow(count: 6, width: 700) == 3 && ColumnsLogic.perRow(count: 2, width: 100) == 1, "Spalten umbrechen")
    check(page.contains { $0["type"] as? String == "synced" } && GestaltenLogic.knownTypes.contains("synced") && GestaltenLogic.knownTypes.contains("math"), "synced/math bekannt")

    // MARK: Diagramme
    let (c1, e1) = ChartData.response(fx("chart_dia"))
    check(e1 == nil && c1?.cats.map(\.key) == ["todo", "doing", "done"] && c1?.series.count == 2 && c1?.series[0].label == "Anna Beispiel", "Diagramm mit Reihen")
    check(c1?.catColor(0) == "gray" && c1?.seriesColor(1) == "orange", "Farben: Palette bzw. Ersatzreihenfolge")
    let (c2, _) = ChartData.response(fx("chart_dia2"))
    check(c2?.xKind == "date" && c2?.cumulative == true && c2?.goal == 50 && c2?.series[0].values == [7, 11, 41] && c2?.cats[0].start == "2026-10-27", "Datum, kumuliert, Ziel")
    check(c2.map { $0.range == (0, 50) } == true, "Bereich mit Ziel")
    check(ChartData.response(fx("chart_bad")).1 == "bad_cfg", "bad_cfg")
    if let t = fxObj("chart_ticks")["cases"] as? [[String: Any]] {
        var bad = 0
        for c in t {
            let lo = (c["lo"] as? NSNumber)!.doubleValue, hi = (c["hi"] as? NSNumber)!.doubleValue
            let want = (c["want"] as? [NSNumber] ?? []).map(\.doubleValue)
            if ChartData.ticks(lo, hi) != want { bad += 1; print("  ticks \(lo) \(hi): \(ChartData.ticks(lo, hi)) != \(want)") }
        }
        check(bad == 0 && t.count > 10, "Achsenwerte wie Server (\(t.count))")
    }

    // MARK: Widgets Etappe 2
    let w = fxObj("widgets")["widgets"] as? [String: [String: Any]] ?? [:]
    let hb = Widget2Logic.habit(w["hb"]?["data"] as? [String: Any])
    check(hb?.days.count == 7 && hb?.habits.first?.name == "Sport" && hb?.canCheck == true && hb?.habits.first?.streak == 2, "Gewohnheit lesen")
    let hp = Widget2Logic.habit(((fxObj("habit_post")["widget"] as? [String: Any])?["data"]) as? [String: Any])
    check(hp?.habits.first?.streak == 4, "Abhaken liefert neuen Stand")
    check(Widget2Logic.habitBody(pid: "sport", day: "2026-10-27", on: true)["on"] as? Bool == true, "Abhaken-Body")
    let up = Widget2Logic.upcoming(w["up"]?["data"] as? [String: Any])
    check(up?.items.count == 2 && up?.items[1].allday == false && up?.items[0].note != nil, "Anstehendes lesen")
    if let up { let s = Widget2Logic.sections(up, today: up.from, tz: berlin); check(s.map(\.0) == [.today, .tomorrow], "Anstehendes: Heute/Morgen \(s.map(\.0))") }
    check(ChartData.parse(w["ch"]?["data"] as? [String: Any] ?? [:]).type == "donut" && ChartData.parse(w["nm"]?["data"] as? [String: Any] ?? [:]).total == 5, "Diagramm-Widget")
    check(w["wx"]?["error"] as? String == "disabled" && Widget2Logic.weatherNotice("disabled") != nil, "Wetter ausgeschaltet")
    check(Widget2Logic.weatherSymbol(0) == "sun.max" && Widget2Logic.weatherSymbol(48) == "cloud.fog" && Widget2Logic.weatherSymbol(63) == "cloud.rain" && Widget2Logic.weatherSymbol(99) == "cloud.bolt.rain", "WMO-Symbole")
    let wx = Widget2Logic.weather(["now": ["t": 12.3, "code": 3, "wind": 14], "days": [["d": "2026-10-27", "min": 6, "max": 13, "code": 61, "rain": 70]], "units": "metric", "stale": true, "place": "Hamburg"])
    check(wx?.now == 12.3 && wx?.days.first?.rain == 70 && wx?.stale == true, "Wetter lesen")
    check(WidgetLogic.kinds.isSuperset(of: Widget2Logic.kinds), "neue Arten bekannt")
    check(ServerFeatures.parse(["features": ["chart1", "datetime1"]]).chart && !ServerFeatures.parse([:]).datetime, "Features")

    // MARK: Zeitleiste und Woche
    let tl = TimelineLogic.bars(q, rows: q.rows, view: ["date_by": "frist"], tz: berlin)
    check(tl.bars.map(\.title) == ["Planen", "Bauen", "Testen"] && tl.undated.count == 2, "Zeitleiste: Balken + ohne Termin \(tl.bars.map(\.title)) \(tl.undated)")
    check(tl.bars[1].end - tl.bars[1].start == 2, "Zeitraum als Balken")
    check(TimelineLogic.byStart(tl.bars).count == 3, "Telefon: nach Start gruppiert")
    let wk = TimelineLogic.week(of: DBDate.days(2026, 10, 29))
    check(DBDate.iso(wk[0]) == "2026-10-26" && wk.count == 7, "Woche ab Montag")
    check(TimelineLogic.rows(on: DBDate.days(2026, 10, 29), q, rows: q.rows, prop: q.prop("frist")!, tz: berlin).map(\.title) == ["Bauen", "Testen"], "Zeitraum auf jedem Tag")
}

/// fester "jetzt"-Zeitpunkt fuer Anzeige-Tests (Jahr 2026)
let DBDateTestNow = Date(timeIntervalSince1970: 1793107800)
