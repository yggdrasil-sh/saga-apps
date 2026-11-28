import SwiftUI

#if DEBUG
// Nur fuer Bilder (2.6 K-G, Bugs-DB 2275/2508/2531, ohne Server; erfundene Namen):
//   -hkKGPreview detail   Auftrags-Detail (erledigt) mit „Deine Bitte“ (2531)
//   -hkKGPreview seite    KI-Bereich einer Seite, Reiter „Diese Seite“ mit „{Name} soll sich kümmern …“ (2508)
//   -hkKGPreview auftrag  Ort „Saga“, Gespraech als Auftrag mit „Zur Seite „…“ ×“ (2508)
enum KGPreview {
    static var mode: String? { UserDefaults.standard.string(forKey: "hkKGPreview") }
    static let status = #"""
    {"enabled":true,"caps":["ai"],"style":{"label":"Marin · KI"},"jobs":true,"level":"plans",
     "endpoint":{"host":"llm.example","net":"internet","label":"llm.example (im Internet)"},
     "consent":{"state":"granted","at":1764000000,"host":"llm.example"},
     "intro":{"version":"k19-1","host":"llm.example","lines":["Ich bin Marin."],"details":[],"bar":""}}
    """#
    static let detail = #"""
    {"id":12,"title":"Elternabend eintragen","state":"erledigt","state_text":"Erledigt","group":"fertig",
     "request":"Trag bitte den Elternabend am 7.7. um 19:30 ein und erinnere mich am Vortag daran",
     "plan":{"job":12,"plan_v":1,"plan_hash":"x","title":"Elternabend eintragen","read_text":"Ich lese dafür: Schule",
             "read":[{"label":"Schule"}],
             "steps":[{"n":1,"tool":"create_event","klass":"own","kind_text":"Eigenes","class_text":"Eigenes · rückgängig","text":"Termin „Elternabend“ am 7.7. um 19:30 anlegen","edit":[],"fields":{}},
                      {"n":2,"tool":"create_task","klass":"own","kind_text":"Eigenes","class_text":"Eigenes · rückgängig","text":"Aufgabe „An Elternabend denken“ für den 6.7.","edit":[],"fields":{}}]},
     "approved":{"at":1764320000,"device":"iPhone"},
     "report":{"text":"Erledigt: Termin und Erinnerung sind eingetragen.","lines":[
        {"change":1,"text":"Termin „Elternabend“ angelegt","undone":false,"can_undo":true,"label":"Termin „Elternabend“"},
        {"change":2,"text":"Aufgabe „An Elternabend denken“ angelegt","undone":false,"can_undo":true,"label":"Aufgabe „An Elternabend denken“"}]},
     "changes":[{"id":1,"label":"Termin „Elternabend“","undo_class":"own","class_text":"Eigenes","undone":false,"can_undo":true},
                {"id":2,"label":"Aufgabe „An Elternabend denken“","undo_class":"own","class_text":"Eigenes","undone":false,"can_undo":true}],
     "events":[{"at":1764319900,"kind":"plan","text":"Plan erstellt"},{"at":1764320000,"kind":"approve","text":"Freigegeben"},{"at":1764320010,"kind":"done","text":"Erledigt"}],
     "actions":[]}
    """#
}

struct KGPreviewView: View {
    @ObservedObject var store: Store
    @StateObject private var nav = NavModel()
    @State private var ready = false
    var body: some View {
        Group {
            if ready {
                switch KGPreview.mode {
                case "detail": NavigationStack { SagaJobDetailView(m: store.saga, closeLabel: false) }
                case "seite": AssistantSheet(pageID: 1, initialTab: 1, openPage: { _ in })
                default: NavigationStack { SagaScreen(m: store.saga) }
                }
            } else { Color.clear }
        }
        .environmentObject(store).environmentObject(nav)
        .task {
            store.sagaStatus = SagaStatus(data: Data(KGPreview.status.utf8))
            store.setPersona(AIPersona(name: "Marin"))
            if KGPreview.mode == "detail" {
                store.saga.openJob = 12
                store.saga.detail = SagaJobDetail(data: Data(KGPreview.detail.utf8))
            }
            if KGPreview.mode == "auftrag" {                                  // KGNOTE
                store.saga.jobMode = true                                     // KGNOTE
                store.saga.noteContext = .init(id: 1, title: "Sommerfest 2026") // KGNOTE
            }                                                                 // KGNOTE
            ready = true
        }
    }
}
#endif
