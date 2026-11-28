import SwiftUI

#if DEBUG
// Nur fuer Bilder (2.6 K-G, Bugs-DB 2275/2508/2531, ohne Server; erfundene Namen):
//   -hkKGPreview detail   Auftrags-Detail (erledigt) mit „Deine Bitte“ (2531)
//   -hkKGPreview seite    KI-Bereich einer Seite, Reiter „Diese Seite“ mit „{Name} soll sich kümmern …“ (2508)
//   -hkKGPreview auftrag  Ort „Saga“, Gespraech als Auftrag mit „Zur Seite „…“ ×“ (2508)
//   -hkKGPreview teilen   Ort „Saga“ nach saga://share: „Geteilt: host ×“, share_notice, „Was soll {Name} damit tun?“ (2275)
//   -hkKGPreview teilenplan  Gespraech mit eigenem Zug „Geteilt: host“ und Plan-Karte mit Herkunft + ⚠ Warnung (2275)
//   -hkKGPreview teilendetail  Auftrags-Detail (laeuft) mit „Herkunft“ (2275)
//   dazu -hkKGVorher YES: dieselben Ansichten ohne geteilten Inhalt/Herkunft (so sahen sie vor 2275 aus)
enum KGPreview {
    static var mode: String? { UserDefaults.standard.string(forKey: "hkKGPreview") }
    static let status = #"""
    {"enabled":true,"caps":["ai","ai_act"],"style":{"label":"Marin · KI"},"jobs":true,"level":"plans",
     "share_notice":"Was du teilst, liest die KI auf llm.example (im Internet).",
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
    /// 2275: Plan zu geteiltem Inhalt mit Warnung (Texte wie die Server-Vektoren share_contract.json)
    static let sharePlan = #"""
    {"job":21,"plan_v":1,"plan_hash":"x","title":"Sportfest eintragen","say":"So würde ich es machen:",
     "read_text":"Ich lese dafür: den geteilten Inhalt (verein.example)","read":[{"ref":"share","label":"den geteilten Inhalt (verein.example)"}],
     "origin":{"kind":"share","host":"verein.example","from":"app","text":"Angeregt durch: geteilten Inhalt (verein.example)","warn":true,
               "warn_text":"Der geteilte Inhalt enthält Anweisungen an die KI. Sie zählen nicht als deine Bitte."},
     "steps":[{"n":1,"tool":"create_task","klass":"own","kind_text":"Eigenes","class_text":"Eigenes · rückgängig","text":"Aufgabe „Sportfest am 14.7.“ anlegen","edit":[],"fields":{}}]}
    """#
    static func shareDetail(state: String, text: String, group: String) -> String {
        let sharePlan = UserDefaults.standard.bool(forKey: "hkKGVorher") ? Self.sharePlan.replacingOccurrences(of: #""origin":{"#, with: #""origin_alt":{"#) : Self.sharePlan
        return #"{"id":21,"title":"Sportfest eintragen","state":"\#(state)","state_text":"\#(text)","group":"\#(group)","request":"Mach daraus eine Aufgabe","plan":"#
            + sharePlan + #","events":[{"at":1764319900,"kind":"plan","text":"Plan erstellt"}],"actions":["cancel"]}"#
    }
}

struct KGPreviewView: View {
    @ObservedObject var store: Store
    @StateObject private var nav = NavModel()
    @State private var ready = false
    var body: some View {
        Group {
            if ready {
                switch KGPreview.mode {
                case "detail", "teilendetail": NavigationStack { SagaJobDetailView(m: store.saga, closeLabel: false) }
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
            let vorher = UserDefaults.standard.bool(forKey: "hkKGVorher")
            if KGPreview.mode?.hasPrefix("teilen") == true { store.saga.jobMode = true }
            if KGPreview.mode?.hasPrefix("teilen") == true && !vorher {      // 2275
                store.features = ServerFeatures([ShareDraftLogic.feature])
                store.saga.shareLine = SharePayload(text: "Elternabend der Klasse 3b am 7.7. um 19:30 in der Aula.",
                                                    url: "https://www.schule.example/termine", title: "Termine 2026")
            }
            if KGPreview.mode == "teilenplan" {
                store.saga.shareLine = nil
                var t = SagaThread(); t.history = true
                t.turns = [SagaTurn(n: 1, role: "me", zug: "job", text: "Mach daraus eine Aufgabe", created: 1764319900,
                                    shareMark: ShareMark(host: "verein.example", title: nil)),
                           SagaTurn(n: 2, role: "ai", zug: "plan", text: "Plan: Sportfest eintragen", created: 1764319905, jobID: 21)]
                if vorher { t.turns[0].shareMark = nil }
                store.saga.thread = t; store.saga.threadLoaded = true
                store.saga.jobState[21] = SagaJobDetail(data: Data(KGPreview.shareDetail(state: "wartet_ok", text: "Wartet auf dein OK", group: "offen").utf8))
            }
            if KGPreview.mode == "teilendetail" {
                store.saga.openJob = 21
                store.saga.detail = SagaJobDetail(data: Data(KGPreview.shareDetail(state: "laeuft", text: "Läuft", group: "offen").utf8))
            }
            ready = true
        }
    }
}
#endif
