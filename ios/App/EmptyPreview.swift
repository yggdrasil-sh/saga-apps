import SwiftUI

#if DEBUG
// Nur fuer Bilder (2.6, Bugs-DB 2031, ohne Server): leere Zustaende und die Frage "Erste Schritte".
//   -hkEmptyPreview start|aufgaben|termine|datenbank|liste|frage|info
// start = Startliste (iPhone: Werkzeuge, Heute, Seiten) eines neuen Kontos; termine: kein Kalender verbunden.
enum EmptyPreview {
    static var mode: String? { UserDefaults.standard.string(forKey: "hkEmptyPreview") }
    /// Kalender verbunden? (Store.calendarConnected) - in den Bildern: keiner
    static var calendar: Bool? { mode == nil ? nil : false }
}

struct EmptyPreviewView: View {
    @ObservedObject var store: Store
    @StateObject private var nav = NavModel()
    @StateObject private var m = DBModel(dbID: 1)
    @State private var ready = false
    @State private var selection: Int?
    private func view(_ id: String) -> [String: Any]? { m.views.first { $0["id"] as? String == id } }
    var body: some View {
        NavigationStack {
            if ready {
                switch EmptyPreview.mode {
                case "aufgaben": TasksScreen()
                case "termine": EventsScreen()
                case "datenbank", "liste":
                    ScrollView {
                        VStack(alignment: .leading) {
                            if EmptyPreview.mode == "liste" { RowList(m: m, view: view("liste")) } else { TableGrid(m: m, view: view("tab")) }
                        }.padding()
                    }
                    .navigationTitle(Text(verbatim: "Projekte"))
                case "info": InfoSettings()
                default:                                                     // start, frage
                    Sidebar(store: store, selection: $selection, query: "", results: [], desk: false).navigationTitle("Saga")
                }
            }
        }
        .environmentObject(store).environmentObject(nav)
        .modifier(EmptyPreviewAsk(store: store))                             // NEU2031
        .task {
            store.features = ServerFeatures([])
            if EmptyPreview.mode == "datenbank" || EmptyPreview.mode == "liste",
               var j = (try? JSONSerialization.jsonObject(with: Data(DB25Preview.rows.utf8))) as? [String: Any] {
                j["rows"] = [Any]()                                          // Schema der Beispieldaten, aber keine Eintraege
                if let d = try? JSONSerialization.data(withJSONObject: j) { m.previewApply(d) }
            }
            previewFirstSteps()                                              // NEU2031
            ready = true
        }
    }
    // NEU2031-START
    private func previewFirstSteps() {
        let items = ["avatar", "chat", "app", "page", "task"].enumerated().map { FirstStepsLogic.Item(key: $0.element, done: $0.offset < 2) }
        switch EmptyPreview.mode {
        case "frage": FirstStepsModel.shared.preview(.init(enabled: true, ask: true, answer: nil, page: nil, items: items), ask: true)
        case "info": FirstStepsModel.shared.preview(.init(enabled: true, ask: false, answer: "yes", page: 7, items: items), ask: false)
        default: break
        }
    }
    // NEU2031-ENDE
}

// NEU2031-START
private struct EmptyPreviewAsk: ViewModifier {
    let store: Store
    func body(content: Content) -> some View {
        content.modifier(FirstStepsAsk(model: FirstStepsModel.shared, store: store, blockers: .init()) { _ in })
    }
}
// NEU2031-ENDE
#endif
