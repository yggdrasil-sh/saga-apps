import SwiftUI

#if DEBUG
// Nur fuer Bilder (2.5 App-Runde E2, ohne Server): Konfliktblock, Block-Markierung, Inline-Farben, Aufgabenliste mit
// Quellen. Daten: Antworten eines Testservers (Wegwerf-Konten), Namen ersetzt.   -hkE2Preview seite|aufgaben
enum E2Preview {
    static var mode: String? { UserDefaults.standard.string(forKey: "hkE2Preview") }
    static let page = #"""
{"id": 1, "title": "Planung", "rev": 5, "format": "blocks", "role": "owner", "body": "{\"blocks\": [{\"id\": \"h1\", \"type\": \"header\", \"data\": {\"text\": \"Wochenplan\", \"level\": 2}}, {\"id\": \"a1\", \"type\": \"paragraph\", \"data\": {\"text\": \"Montag: <span class=\\\"hk-c-blue\\\">Kickoff</span> mit <span class=\\\"hk-mention\\\" data-u=\\\"~bbbbbbbbbbbbbbbb\\\">Bert</span>, danach <mark class=\\\"hk-bg-green\\\">Review</mark>.\"}}, {\"id\": \"a2\", \"type\": \"paragraph\", \"data\": {\"text\": \"Formel: <code class=\\\"hk-math\\\">E = mc^2</code> und <b><span class=\\\"hk-c-red\\\">dringend</span></b>\"}}, {\"id\": \"a3\", \"type\": \"paragraph\", \"data\": {\"text\": \"Dienstag: Entwurf an alle schicken\"}}, {\"id\": \"a3-k\", \"type\": \"paragraph\", \"data\": {\"text\": \"Dienstag: Entwurf bis Mittag an das Team\"}, \"tunes\": {\"hkconflict\": {\"by\": \"~bbbbbbbbbbbbbbbb\", \"name\": \"Bert Beispiel\", \"of\": \"a3\"}}}, {\"id\": \"a4\", \"type\": \"paragraph\", \"data\": {\"text\": \"Mittwoch: Abnahme vorbereiten\"}}]}", "people": {"~bbbbbbbbbbbbbbbb": {"name": "Bert Beispiel", "me": false}}}
"""#
    static let tasks = #"""
{"done": [], "items": [{"assignees": [], "done": false, "due": "2026-10-20", "edit": false, "id": 8, "key": "r:8", "kind": "row", "overdue": true, "prio": 3, "src": 6, "text": "Testen"}, {"done": false, "due": "2026-10-28", "id": 1, "key": "t:1", "kind": "task", "overdue": false, "prio": 2, "repeat": null, "text": "Inbox-Aufgabe"}, {"done": false, "due": "2026-10-28", "id": 2, "key": "t:2", "kind": "task", "overdue": false, "prio": 1, "repeat": null, "text": "Rechnung bezahlen"}, {"assignees": ["~aaaaaaaaaaaaaaaa", "~bbbbbbbbbbbbbbbb"], "done": false, "due": "2026-10-28", "edit": true, "id": 7, "key": "r:7", "kind": "row", "overdue": false, "prio": 1, "src": 6, "text": "Bauen", "time": "09:30"}, {"assignees": ["~aaaaaaaaaaaaaaaa"], "done": false, "due": "2026-10-29", "due_end": "2026-10-31", "edit": true, "id": 9, "key": "r:9", "kind": "row", "overdue": false, "prio": 2, "src": 6, "text": "Planen"}], "limits": {"rows": 500, "sources": 20}, "open": [{"id": 1, "text": "Inbox-Aufgabe", "due": "2026-10-28", "prio": 2}, {"id": 2, "text": "Rechnung bezahlen", "due": "2026-10-28", "prio": 1}], "people": {"~aaaaaaaaaaaaaaaa": {"name": "Anna Beispiel", "avatar": null, "me": true}, "~bbbbbbbbbbbbbbbb": {"name": "Bert Beispiel", "avatar": null, "me": false}}, "sources": [{"count": 3, "db": 6, "icon": "☑️", "map": {"assignee": "wer", "done": "st", "done_type": "status", "done_value": "fertig", "due": "fl", "open_value": "neu", "prio": "pr", "prio_values": {"1": "h", "2": "m", "3": "n"}}, "own": true, "role": "owner", "scope": "all", "title": "Projekt Alpha", "truncated": false}]}
"""#
}
#endif

#if DEBUG
extension PageDoc {
    /// nur fuer Bilder: Seite ohne Server anzeigen
    func previewApply(_ json: String, store: Store) async { await previewLoad(Data(json.utf8), store) }
}

struct E2PreviewView: View {
    @ObservedObject var store: Store
    @StateObject private var nav = NavModel()
    @StateObject private var doc = PageDoc(id: 1)
    @State private var ed: PageEditor?
    @StateObject private var widgets = PageWidgetValues()
    @State private var ready = false
    @Environment(\.horizontalSizeClass) private var hsc
    @Environment(\.dynamicTypeSize) private var dts
    var body: some View {
        // 2.5 Notion-Gefuehl: dieselben Masse wie die Seite (iPad normale Breite: mehr Luft)
        let wm = WritingLogic.metrics(mac: false, regularWidth: hsc == .regular)
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 10) {
                    if ready, let ed {
                        if E2Preview.mode == "aufgaben" {
                            TaskListView()
                        } else {
                            Text(verbatim: doc.title).font(WritingLogic.font(.title, fallback: .largeTitle.bold(), dts: dts, in: wm))
                            PresenceBar(users: [PresenceUser(username: "b", name: "Bert Beispiel", editing: true, me: false)])
                            MergeHintRow(hint: .restored, versions: nil) {}
                            BlocksView(arr: doc.root).environmentObject(ed).environmentObject(widgets).environment(\.writingMetrics, wm)
                            Divider().padding(.vertical, 6)
                            Text(verbatim: "Format").font(.headline)
                            Menu { InlineFormatItems { _ in } } label: { Label("Format", systemImage: "bold.italic.underline") }
                            HStack(spacing: 6) {
                                ForEach(InlineRuns.palette, id: \.self) { c in
                                    Text(verbatim: "Aa").font(.callout.bold()).foregroundStyle(HKPalette.textColor(c))
                                        .padding(.horizontal, 6).padding(.vertical, 3).background(HKPalette.bgColor(c), in: RoundedRectangle(cornerRadius: 4))
                                }
                            }
                        }
                    }
                }
                .padding(.horizontal, hsc == .regular ? 56 : 16).padding(.vertical, 16)
                .frame(maxWidth: hsc == .regular ? 760 + 112 : .infinity).frame(maxWidth: .infinity)
            }
            .navigationTitle(Text(verbatim: E2Preview.mode == "aufgaben" ? "Aufgaben" : ""))
        }
        .environmentObject(store).environmentObject(nav)
        .task {
            store.features = ServerFeatures(["tasksrc1", "taskdue1", "mention1", "synced1", "move1"])
            if E2Preview.mode == "aufgaben" {
                store.previewTasks(Data(E2Preview.tasks.utf8))
            } else {
                await doc.previewApply(E2Preview.page, store: store)
                let e = PageEditor(doc: doc); e.store = store
                e.presenceMarks = ["a1": [PresenceMark(name: "Bert Beispiel", color: "pink")]]
                ed = e
            }
            ready = true
        }
    }
}

extension Store {
    /// nur fuer Bilder: Aufgabenliste aus einer gespeicherten Antwort
    func previewTasks(_ d: Data) {
        struct T: Decodable { let open: [OpenTask] }
        tasks = (try? JSONDecoder().decode(T.self, from: d))?.open ?? []
        let s = try? JSONDecoder().decode(TaskSourcesList.self, from: d)
        taskItems = s?.items ?? []; taskSources = s?.sources ?? []; taskPeople = s?.people ?? [:]
        taskUndo = TaskUndo(key: "r:99", text: "Angebot schreiben", prev: .text("lauf"))
    }
}
#endif
