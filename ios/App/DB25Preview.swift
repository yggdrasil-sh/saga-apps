import SwiftUI

#if DEBUG
// Nur fuer Bilder (2.5 App-Runde E1, ohne Server): Datenbank-Ansichten, Diagramme, Zeitleiste und Widgets mit
// Beispieldaten aus einem Testserver (Wegwerf-Konten).   -hkDB25Preview tabelle|board|diagramm|zeitleiste|widgets
enum DB25Preview {
    static var mode: String? { UserDefaults.standard.string(forKey: "hkDB25Preview") }
    static let rows = #"""
{"schema":{"_since":{"anh":[1,"562a8eeebf0a"],"blk":[1,"562a8eeebf0a"],"frist":[1,"562a8eeebf0a"],"menge":[1,"562a8eeebf0a"],"par":[1,"562a8eeebf0a"],"pbud":[1,"562a8eeebf0a"],"pnam":[1,"562a8eeebf0a"],"preis":[1,"562a8eeebf0a"],"proj":[1,"562a8eeebf0a"],"quot":[1,"562a8eeebf0a"],"rbccbabd5":[1,"562a8eeebf0a"],"rd7bec3cc":[1,"562a8eeebf0a"],"spaet":[1,"562a8eeebf0a"],"status/lauf":[1,"562a8eeebf0a"],"status/neu":[1,"562a8eeebf0a"],"status/ok":[1,"562a8eeebf0a"],"status/warten":[1,"562a8eeebf0a"],"summe":[1,"562a8eeebf0a"],"tag":[1,"562a8eeebf0a"],"wer":[1,"562a8eeebf0a"]},"props":[{"id":"status","name":"Status","options":[{"color":"gray","group":"todo","id":"neu","name":"Neu"},{"color":"blue","group":"doing","id":"lauf","name":"Läuft"},{"color":"yellow","group":"doing","id":"warten","name":"Wartet"},{"color":"green","group":"done","id":"ok","name":"Fertig"}],"status":true,"type":"select"},{"id":"menge","name":"Menge","type":"number"},{"fmt":"euro","id":"preis","name":"Preis","type":"number"},{"expr":"prop(\"menge\") * prop(\"preis\")","fmt":"euro","id":"summe","name":"Summe","rtype":"number","type":"formula"},{"expr":"10 / prop(\"menge\")","id":"quot","name":"Quote","rtype":"number","type":"formula"},{"expr":"formatDate(prop(\"frist\"), \"dddd\")","id":"tag","name":"Wochentag","rtype":"text","type":"formula"},{"expr":"prop(\"menge\") > 2","id":"spaet","name":"Spät","rtype":"checkbox","type":"formula"},{"id":"frist","name":"Frist","range":true,"time":true,"type":"date"},{"id":"proj","name":"Projekt","target":48,"type":"relation"},{"fn":"sum","id":"pbud","name":"Projektbudget","rel":"proj","rtype":"number","target":"bud","type":"rollup"},{"fn":"show","id":"pnam","name":"Projektnamen","rel":"proj","rtype":"list","target":"title","type":"rollup"},{"id":"wer","multi":true,"name":"Zuständig","notify":true,"type":"person"},{"id":"anh","max":10,"name":"Anhänge","type":"files"},{"id":"par","name":"Übergeordnet","pair":"rd7bec3cc","role":"parent","target":51,"type":"relation"},{"id":"blk","name":"Blockiert durch","pair":"rbccbabd5","role":"blocked_by","target":51,"type":"relation"},{"id":"rd7bec3cc","name":"Unteraufgaben","pair":"par","role":"children","target":51,"type":"relation"},{"id":"rbccbabd5","name":"Blockiert","pair":"blk","role":"blocking","target":51,"type":"relation"}],"srev":1,"views":[{"id":"tab","name":"Tabelle","type":"table","hidden":["quot","pnam","par","blk","r889b02fb","rd08b57ef","preis","anh"]},{"id":"board","name":"Board","type":"board","group_by":"status","group_status":true,"colors":[{"pid":"menge","op":"gt","value":"2","color":"red"}]},{"id":"liste","name":"Liste","type":"list","sub":{"parent":"par","mode":"nested"}},{"id":"zeit","name":"Zeitleiste","type":"timeline","date_by":"frist","scale":"day"},{"id":"woche","name":"Woche","type":"calendar","date_by":"frist","range":"week"}]},"rows":[{"created":1793148184.233071,"depth":0,"icon":"","id":52,"props":{"anh":[{"image":false,"mime":"application/octet-stream","name":"plan.pdf","size":13,"url":"/files/d4f9f47b6236032ccdf37e2b"}],"frist":"2026-10-27T09:00+01:00","menge":2,"pbud":1250.5,"pnam":["Alpha","Beta"],"preis":3.5,"proj":[49,50],"quot":5,"spaet":false,"status":"ok","summe":7,"tag":"Dienstag","wer":["~gc27sqqye2yzgxoa"]},"sub_of":null,"title":"Planen","updated":1793148184.2940974},{"created":1793148184.2403429,"depth":0,"icon":"","id":53,"props":{"frist":"2026-10-28/2026-10-30","menge":4,"pbud":1000,"pnam":["Alpha"],"preis":1,"proj":[49],"quot":2.5,"rbccbabd5":[54],"rd7bec3cc":[54],"spaet":true,"status":"lauf","summe":4,"tag":"Mittwoch","wer":["~gc27sqqye2yzgxoa","~2pjnl6f3eg3is6jl"]},"sub_of":null,"title":"Bauen","updated":1793148184.3021193},{"blocked":true,"created":1793148184.2527552,"depth":1,"icon":"","id":54,"props":{"blk":[53],"frist":"2026-10-29T14:30+01:00/2026-10-29T16:00+01:00","menge":3,"par":[53],"pnam":[],"preis":10,"quot":3.3333333333333335,"spaet":true,"status":"neu","summe":30,"tag":"Donnerstag","wer":["~2pjnl6f3eg3is6jl"]},"sub_of":53,"title":"Testen","updated":1793148184.27934},{"created":1793148184.2631783,"depth":0,"icon":"","id":55,"props":{"pnam":[],"spaet":false},"sub_of":null,"title":"Ohne","updated":1793148184.2631783},{"created":1793148184.2681053,"depth":0,"fx_err":{"quot":"num"},"icon":"","id":56,"props":{"menge":0,"pnam":[],"spaet":false,"status":"warten"},"sub_of":null,"title":"Kaputt","updated":1793148184.2681053}],"people":{"~2pjnl6f3eg3is6jl":{"avatar":null,"me":false,"name":"Bert Beispiel","remote":false},"~gc27sqqye2yzgxoa":{"avatar":null,"me":true,"name":"Anna Beispiel","remote":false}},"role":"owner"}
"""#
    static let chart1 = #"""
{"as_of": "2026-10-28T00:43:04Z", "cats": [{"color": "gray", "key": "todo", "label": "Zu erledigen"}, {"color": "blue", "key": "doing", "label": "In Arbeit"}, {"color": "green", "key": "done", "label": "Erledigt"}], "fmt": "num", "other": 0, "rows": 5, "series": [{"color": null, "key": "~gc27sqqye2yzgxoa", "label": "Anna Beispiel", "values": [0, 1, 1]}, {"color": null, "key": "~2pjnl6f3eg3is6jl", "label": "Bert Beispiel", "values": [1, 1, 0]}], "total": 5, "truncated": false, "type": "bar", "x_kind": "cat"}
"""#
    static let chart2 = #"""
{"as_of": "2026-10-28T00:43:04Z", "bucket": "day", "cats": [{"color": null, "key": "2026-10-27", "label": "2026-10-27", "start": "2026-10-27"}, {"color": null, "key": "2026-10-28", "label": "2026-10-28", "start": "2026-10-28"}, {"color": null, "key": "2026-10-29", "label": "2026-10-29", "start": "2026-10-29"}], "cumulative": true, "fmt": "eur", "goal": 50, "other": 0, "rows": 5, "series": [{"color": null, "key": "", "label": "", "values": [7, 11, 41]}], "total": 41, "truncated": false, "type": "line", "x_kind": "date"}
"""#
    static let widgets = #"""
{"widgets": {"ch": {"as_of": "2026-10-28T00:43:04Z", "data": {"cats": [{"color": "gray", "key": "neu", "label": "Neu"}, {"color": "blue", "key": "lauf", "label": "Läuft"}, {"color": "yellow", "key": "warten", "label": "Wartet"}, {"color": "green", "key": "ok", "label": "Fertig"}], "fmt": "num", "other": 0, "rows": 5, "series": [{"color": null, "key": "", "label": "", "values": [1, 1, 1, 1]}], "total": 5, "truncated": false, "type": "donut", "x_kind": "cat"}, "error": null, "fmt": "num", "kind": "chart", "max": null, "text": null, "value": 5}, "hb": {"as_of": "2026-10-28T00:43:04Z", "data": {"can_check": true, "days": ["2026-10-22", "2026-10-23", "2026-10-24", "2026-10-25", "2026-10-26", "2026-10-27", "2026-10-28"], "habits": [{"done": [false, false, false, true, false, true, true], "name": "Sport", "pct": 43, "pid": "sport", "streak": 2, "week": 2}], "today": "2026-10-28"}, "error": null, "fmt": null, "kind": "habit", "max": 1, "text": null, "value": 1}, "nm": {"as_of": "2026-10-28T00:43:04Z", "data": {"cats": [], "fmt": "num", "other": 0, "rows": 5, "series": [], "total": 5, "truncated": false, "type": "number", "x_kind": "cat"}, "error": null, "fmt": "num", "kind": "chart", "max": null, "text": null, "value": 5}, "up": {"as_of": "2026-10-28T00:43:04Z", "data": {"days": 7, "errors": 0, "from": "2026-10-28", "items": [{"allday": true, "cal": null, "color": null, "end": "2026-10-30", "k": "row", "note": 53, "overdue": false, "start": "2026-10-28", "task": null, "title": "Bauen"}, {"allday": false, "cal": null, "color": null, "end": "2026-10-29T16:00+01:00", "k": "row", "note": 54, "overdue": false, "start": "2026-10-29T14:30+01:00", "task": null, "title": "Testen"}], "more": 0}, "error": null, "fmt": null, "kind": "upcoming", "max": null, "text": null, "value": 2}, "wx": {"kind": "weather", "value": 12.3, "error": null, "data": {"now": {"t": 12.3, "code": 3, "wind": 14}, "days": [{"d": "2026-10-28", "min": 6, "max": 13, "code": 61, "rain": 70}, {"d": "2026-10-29", "min": 5, "max": 11, "code": 2, "rain": 10}, {"d": "2026-10-30", "min": 4, "max": 10, "code": 0, "rain": 0}, {"d": "2026-10-31", "min": 7, "max": 12, "code": 80, "rain": 60}, {"d": "2026-11-01", "min": 3, "max": 8, "code": 71, "rain": 40}], "units": "metric", "stale": false, "place": "Hamburg"}}}}
"""#
}

struct DB25PreviewView: View {
    @ObservedObject var store: Store
    @StateObject private var nav = NavModel()
    @StateObject private var m = DBModel(dbID: 1)
    @State private var ready = false
    private func view(_ id: String) -> [String: Any]? { m.views.first { $0["id"] as? String == id } }
    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    if ready {
                        switch DB25Preview.mode {
                        case "board": BoardView(m: m, view: view("board"))
                        case "diagramm":
                            ChartBody(data: chart(DB25Preview.chart1), height: 240)
                            ChartBody(data: chart(DB25Preview.chart2), height: 200)
                        case "zeitleiste":
                            DBTimelineView(m: m, view: view("zeit"))
                            if let p = m.prop("frist") { DBWeekView(m: m, view: view("woche"), prop: p) }
                        case "widgets": widgetCards
                        case "liste": RowList(m: m, view: view("liste"))
                        default: TableGrid(m: m, view: view("tab"))
                        }
                    }
                }
                .padding()
            }
            .navigationTitle(Text(verbatim: "Aufgaben"))
        }
        .environmentObject(store).environmentObject(nav)
        .task {
            store.features = ServerFeatures(["formula1", "rollup1", "person1", "file1", "status1", "datetime1", "relfilter1", "subtasks1", "tableopts1", "chart1", "weather1", "habit1", "upcoming1"])
            m.previewApply(Data(DB25Preview.rows.utf8))
            ready = true
        }
    }
    private func chart(_ s: String) -> ChartData { ChartData.parse((try? JSONSerialization.jsonObject(with: Data(s.utf8))) as? [String: Any] ?? [:]) }
    @ViewBuilder private var widgetCards: some View {
        let r = WidgetLogic.parse(Data(DB25Preview.widgets.utf8)) ?? WidgetLogic.Response()
        let ed = PageEditor(doc: PageDoc(id: 1))
        let vals = PageWidgetValues(preview: .loaded(r))
        ForEach([("wx", "weather", "Draußen"), ("hb", "habit", "Gewohnheiten"), ("up", "upcoming", "Anstehend"), ("ch", "chart", "Status")], id: \.0) { w in
            WidgetBlockView(data: ["kind": w.1, "title": w.2, "cfg": [:] as [String: Any]] as NSDictionary, blockID: w.0)
        }
        .environmentObject(vals).environmentObject(ed)
    }
}
#endif
