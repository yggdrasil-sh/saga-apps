import Foundation

// Ort "Saga" (Bugs-DB 2269 K-I + 2267 K-B, App/SagaLogic.swift): Tab nur mit Faehigkeit, Einwilligung (409), SSE-Strom,
// Plan-Vertrag (plan_v + plan_hash), Gruppen/Zustaende, Rueckgaengig (409 changed), sichere Pfade
func sagaTests() {
    func data(_ s: String) -> Data { Data(s.utf8) }
    func ev(_ name: String, _ json: String) -> SSEEvent { SSEEvent(id: nil, event: name, data: json) }
    typealias L = SagaLogic

    // --- Tab nur mit Faehigkeit ---------------------------------------------------------------------------------------
    let full = #"""
    {"enabled":true,"host":"llm.example","notice":"Was du hier fragst, liest die KI auf llm.example (im Internet).",
     "caps":["ai","ai_ideas"],"style":{"label":"Marin · KI","form":"sie"},"jobs":true,"level":"plans",
     "endpoint":{"host":"llm.example","net":"internet","label":"llm.example (im Internet)"},
     "consent":{"state":"none"},
     "intro":{"version":"k19-1","host":"llm.example","lines":["Ich bin Marin.","Deine Frage … gehen an llm.example (im Internet).","Ich ändere nichts."],
              "note":null,"question":"Soll ich dir Ideen zeigen?","ideas_allowed":true,"details":["Nicht Ende-zu-Ende verschlüsselt: …","Was genau rausgeht: …"],
              "bar":"Was du hier schreibst, liest die KI auf llm.example (im Internet)"}}
    """#
    let st = SagaStatus(data: data(full))
    check(st?.hasPlace == true && st?.jobsOn == true && st?.consentOK == false, "Status mit Ort, Auftraegen, ohne Einwilligung")
    check(st?.label == "Marin · KI" && st?.endpointLabel == "llm.example (im Internet)", "Kennzeichnung und Host-Hinweis vom Server")
    check(st?.intro?.lines.count == 3 && st?.intro?.note == nil && st?.intro?.ideasAllowed == true && st?.intro?.details.count == 2, "Kennenlernen-Text")
    check(st?.intro?.bar.hasPrefix("Was du hier schreibst") == true, "Leiste B6 vom Server")
    let old = SagaStatus(data: data(#"{"enabled":true,"host":"x","persona":{"name":"Marin"}}"#))
    check(old?.hasPlace == false && old?.jobsOn == false, "aelterer Server ohne consent/intro: kein Tab")
    let off = SagaStatus(data: data(full.replacingOccurrences(of: #""enabled":true"#, with: #""enabled":false"#)))
    check(off?.hasPlace == false, "KI fuer die Person aus: kein Tab")
    let noIntro = SagaStatus(data: data(#"{"enabled":true,"consent":{"state":"none"}}"#))
    check(noIntro?.hasPlace == false, "ohne intro kein Tab (halber Server-Stand)")
    let answers = SagaStatus(data: data(full.replacingOccurrences(of: #""level":"plans""#, with: #""level":"answers""#)))
    check(answers?.hasPlace == true && answers?.jobsOn == false, "Stufe 'answers': Tab ja, Auftraege nein")
    let noJobs = SagaStatus(data: data(full.replacingOccurrences(of: #""jobs":true"#, with: #""jobs":false"#)))
    check(noJobs?.jobsOn == false, "jobs false: keine Auftraege")
    check(SagaStatus(data: data("kaputt")) == nil, "kaputte Antwort")
    let granted = SagaStatus(data: data(full.replacingOccurrences(of: #"{"state":"none"}"#, with: #"{"state":"granted","at":1764000000,"version":"k19-1","ideas":"today","host":"llm.example"}"#)))
    check(granted?.consentOK == true && granted?.consent?.ideas == "today" && granted?.consent?.at == 1764000000, "Einwilligung erteilt")
    let stale = SagaStatus(data: data(full.replacingOccurrences(of: #"{"state":"none"}"#, with: #"{"state":"stale","at":1,"host":"alt.example"}"#)))
    check(stale?.consentOK == false && stale?.consent?.state == .stale, "stale gilt nicht (Karte erneut)")
    check(SagaConsent(["state": "irgendwas"])?.state == SagaConsent.State.none, "unbekannter Zustand = none")

    // --- Einwilligung -------------------------------------------------------------------------------------------------
    check(L.introAnswers(ideasAllowed: true) == [.ideas, .noIdeas, .later], "Knoepfe mit Ideen")
    check(L.introAnswers(ideasAllowed: false) == [.noIdeas, .later], "ohne ai_ideas: Ja, einverstanden (no_ideas) · Jetzt nicht")
    if let intro = st?.intro {
        let b = L.consentBody(.ideas, intro: intro)
        check(b["answer"] as? String == "ideas" && b["version"] as? String == "k19-1" && b["host"] as? String == "llm.example", "Einwilligung mit Fassung und Host der Karte")
        let l = L.consentBody(.later, intro: intro)
        check(l["answer"] as? String == "later" && l["host"] == nil && l["version"] == nil, "Jetzt nicht ohne Fassung")
        check(L.consentBody(.noIdeas, intro: intro)["answer"] as? String == "no_ideas", "ohne Ideen")
    } else { check(false, "intro fehlt") }
    check(L.consentResult(code: 200, body: data(#"{"consent":{"state":"granted","host":"llm.example"},"prefs":{}}"#)) == .ok(SagaConsent(state: .granted, host: "llm.example")), "Einwilligung ok")
    check(L.consentResult(code: 409, body: data(#"{"error":"Die KI ist inzwischen woanders eingerichtet","version":"k19-1","host":"neu.example"}"#)) == .reload("Die KI ist inzwischen woanders eingerichtet"), "409 Host -> neu laden")
    check(L.consentResult(code: 409, body: data(#"{"error":"Der Text hat sich geändert","version":"k19-2"}"#)) == .reload("Der Text hat sich geändert"), "409 Fassung -> neu laden")
    check(L.consentResult(code: 403, body: data(#"{"error":"aus"}"#)) == .failed("aus"), "403 -> Fehler")
    check(L.needsConsent(code: 403, body: data(#"{"error":"Lerne Marin zuerst kennen","consent":"needed"}"#)), "403 consent needed erkannt")
    check(!L.needsConsent(code: 403, body: data(#"{"error":"aus"}"#)) && !L.needsConsent(code: 429, body: data(#"{"consent":"needed"}"#)), "andere 403/429 nicht")

    // --- Gespraech: GET und SSE ---------------------------------------------------------------------------------------
    let th = SagaThread(data: data(#"{"turns":[{"n":1,"role":"me","text":"Hallo"},{"n":2,"role":"ai","zug":"answer","text":"Hi [1]","fields":{"sources":[{"n":1,"id":7,"title":"Sommerfest"}]},"created":1764000000},{"n":3,"role":"ai","zug":"no_match","text":"Dazu finde ich nichts."},{"n":4,"role":"system","text":"x"}],"history":false,"keep":86400,"consent":{"state":"granted"}}"#))
    check(th?.turns.count == 3 && th?.keep == 86400 && th?.history == false, "Verlauf: fremde Rollen fallen weg")
    check(th?.turns[1].sources.first?.pageID == 7 && th?.turns[1].reportable == true, "KI-Zug mit Quelle, meldbar")
    check(th?.turns[0].mine == true && th?.turns[0].reportable == false && th?.turns[2].reportable == false, "eigener Zug / no_match nicht meldbar")

    // Auftraege im Gespraech (11.7): Zuege job/plan/ask/refuse
    let jt = SagaThread(data: data(#"""
    {"turns":[{"n":5,"role":"me","zug":"job","text":"Erinnere mich"},{"n":6,"role":"ai","zug":"plan","job_id":12,"text":"Plan: Pavillon"},
              {"n":7,"role":"ai","zug":"ask","text":"Welcher Ben?","fields":{"choices":["Ben B.","Ben K."],"q":"Frag Ben"}},
              {"n":8,"role":"ai","zug":"refuse","text":"Kann ich nicht.","fields":{"alternative":{"recipe":"aufgabe","fields":{"text":"Kohle"},"label":"Als Aufgabe anlegen"},"q":"Bestell Kohle"}},
              {"n":9,"role":"ai","zug":"plan","text":"ohne job_id"}],"history":false}
    """#))
    let tk = jt?.turns.map { $0.kind(jobsOn: true) } ?? []
    check(tk == [.mineJob, .plan(12), .ask, .refuse, .answer], "Zug-Arten im Gespraech")
    check(jt?.turns.map { $0.kind(jobsOn: false) } == [.mineJob, .answer, .answer, .answer, .answer], "ohne Auftraege: alles als Text")
    check(jt?.turns[2].choices == ["Ben B.", "Ben K."] && jt?.turns[2].q == "Frag Ben", "Rueckfrage mit Wahl und Bitte")
    check(jt?.turns[3].alternative == .init(recipe: "aufgabe", label: "Als Aufgabe anlegen", fields: ["text": "Kohle"]) && jt?.turns[3].q == "Bestell Kohle", "Absage mit Alternative")
    check(jt?.turns.contains { $0.reportable } == false, "Auftrags-Zuege ohne Melden-Knopf am Text")

    var s = SagaStream()
    var p = SSEParser()
    let raw = ":" + String(repeating: " ", count: 40) + "\n\nevent: sources\ndata: {\"sources\":[{\"n\":1,\"id\":7,\"title\":\"Sommerfest\"}]}\n\nevent: status\ndata: {\"text\":\"Marin denkt nach …\"}\n\nevent: delta\ndata: {\"t\":\"Hal\"}\n\n"
    for e in p.feed(bytes: data(raw)) { s.apply(e) }
    check(s.text == "Hal" && s.sources.count == 1 && s.status == "Marin denkt nach …" && !s.finished, "Strom: Quellen, Status, erstes Stueck")
    for e in p.feed(bytes: data("event: reset\ndata: {}\n\nevent: delta\ndata: {\"t\":\"Hallo [1]\"}\n\nevent: do")) { s.apply(e) }
    check(s.text == "Hallo [1]" && !s.finished, "reset verwirft den Anfang; halbes Ereignis wartet")
    for e in p.feed(bytes: data("ne\ndata: {\"turn\":6,\"read\":[\"note:7\"],\"quota_left\":9}\n\n")) { s.apply(e) }
    check(s.finished && s.turn == 6 && s.quotaLeft == 9 && s.text == "Hallo [1]", "done mit Zug-Nummer")
    check(s.apply(ev("delta", #"{"t":"zu spaet"}"#)) && s.text == "Hallo [1]", "nach done nichts mehr")
    let t6 = s.turnValue(now: 5)
    check(t6.n == 6 && t6.zug == "answer" && t6.role == "ai" && t6.sources.count == 1, "fertiger Zug")
    var s2 = SagaStream(); s2.apply(ev("delta", #"{"t":"roh"}"#)); s2.apply(ev("done", #"{"text":"bereinigt","turn":2,"no_match":true}"#))
    check(s2.text == "bereinigt" && s2.noMatch && s2.turnValue(now: 0).zug == "no_match", "done.text ersetzt den Strom (Marker entfernt)")
    var s3 = SagaStream(); s3.apply(ev("delta", #"{"t":"halb"}"#))
    check(s3.apply(ev("error", #"{"error":"Der Assistent ist gerade nicht erreichbar.","partial":true}"#)) && s3.error == "Der Assistent ist gerade nicht erreichbar.", "error beendet")

    // --- Auftrag geben: Ereignisse --------------------------------------------------------------------------------------
    let planJSON = #"""
    {"job":12,"plan_v":2,"plan_hash":"ab12cd","title":"Pavillon","state":"wartet_ok","say":"So würde ich es machen:",
     "read":[{"label":"Sommerfest"}],"read_text":"Ich lese dafür: Sommerfest","edited":false,
     "steps":[{"n":0,"tool":"read_page","klass":"read","kind_text":"Lesen","undo_class":null,"class_text":"nur lesen","text":"Seite „Sommerfest“ lesen","wording":null,"edit":[],"fields":{}},
              {"n":1,"tool":"create_task","klass":"own","kind_text":"Eigenes","undo_class":"undo","class_text":"rückgängig","text":"Aufgabe „Pavillon abholen“ für Fr anlegen","wording":null,"edit":["text","due"],"fields":{"text":"Pavillon abholen","due":"2026-07-10"}},
              {"n":2,"tool":"create_event","klass":"own","kind_text":"Eigenes","undo_class":"delete_only","class_text":"nur löschbar","text":"Termin eintragen","edit":["summary","start","allday"],"fields":{"summary":"Fest","start":"2026-07-11","allday":true,"n":3}}]}
    """#
    guard case .plan(let plan)? = L.outcome(ev("plan", planJSON)) else { check(false, "Plan nicht erkannt"); return }
    check(plan.job == 12 && plan.planV == 2 && plan.planHash == "ab12cd" && plan.steps.count == 3 && plan.editable, "Plan gelesen")
    check(plan.changed && SagaPlan(["job": 1, "plan_v": 1, "plan_hash": "x", "steps": []])?.changed == false
          && SagaPlan(["job": 1, "plan_v": 1, "plan_hash": "x", "edited": true])?.changed == true, "geaendert: edited oder plan_v > 1")
    check(plan.steps[0].symbol == "◎" && plan.steps[1].symbol == "✎" && L.kindSymbol("out") == "↗" && L.kindSymbol("?") == "•", "Art-Symbole")
    check(plan.steps[1].classText == "rückgängig" && plan.steps[2].undoClass == "delete_only" && plan.steps[0].undoClass == nil, "Klasse je Schritt")
    check(plan.readText == "Ich lese dafür: Sommerfest" && plan.readLabels == ["Sommerfest"], "Ich lese dafür (Server)")
    check(plan.steps[2].fields["allday"] == .flag(true) && plan.steps[2].fields["n"] == .text("3"), "Wahrheitswert vs. Zahl")
    check(plan.steps[1].initial("due") == .text("2026-07-10") && plan.steps[2].initial("allday") == .flag(true), "Startwerte")
    check(L.outcome(ev("status", #"{"phase":"planning","text":"Marin denkt nach …"}"#)) == nil, "status ist kein Ausgang")
    check(L.outcome(ev("ask", #"{"question":"Welcher Ben?","choices":["Ben B.","Ben K."]}"#)) == .ask(question: "Welcher Ben?", choices: ["Ben B.", "Ben K."]), "Rueckfrage")
    let ref = L.outcome(ev("refuse", #"{"zug":"cant","case":"level","text":"Das bekomme ich hier nicht sicher hin.","alternative":{"recipe":"aufgabe","fields":{"text":"Kohle bestellen"},"label":"Als Aufgabe anlegen"}}"#))
    check(ref == .refuse(text: "Das bekomme ich hier nicht sicher hin.", alternative: .init(recipe: "aufgabe", label: "Als Aufgabe anlegen", fields: ["text": "Kohle bestellen"])), "Absage mit Alternative")
    check(L.outcome(ev("refuse", #"{"text":"Nein."}"#)) == .refuse(text: "Nein.", alternative: nil), "Absage ohne Alternative")
    check(L.outcome(ev("error", #"{"status":503,"error":"nicht erreichbar"}"#)) == .error("nicht erreichbar", status: 503), "Fehler im Strom")
    check(L.outcome(ev("plan", #"{"job":1}"#)) == .error("", status: nil), "kaputter Plan -> Fehler, nie halbe Karte")
    let gb = L.giveBody(text: "  Erinnere mich  ", choice: "Ben B.", note: 7)
    check(gb["text"] as? String == "Erinnere mich" && gb["choice"] as? String == "Ben B." && (gb["context"] as? [String: Int])?["note"] == 7 && gb["source"] as? String == "saga", "Auftrag-Body")
    check(L.giveBody(text: "x", note: -5)["context"] == nil && (L.giveBody(text: String(repeating: "a", count: 1200))["text"] as? String)?.count == 1000, "fremde Seite kein Kontext, Laenge begrenzt")
    if case .refuse(_, let alt?)? = ref {
        let rb = L.recipeBody(alt, text: "Kohle")
        check(rb["recipe"] as? String == "aufgabe" && (rb["fields"] as? [String: String])?["text"] == "Kohle bestellen", "Rezept nur als Kennung + Felder")
    }

    // --- Plan-Vertrag ---------------------------------------------------------------------------------------------------
    let ab = L.approveBody(plan)
    check(ab["plan_v"] as? Int == 2 && ab["plan_hash"] as? String == "ab12cd" && ab.count == 2, "Freigabe mit plan_v + plan_hash")
    let eb = L.editBody(plan, values: [1: ["text": .text(" Pavillon holen "), "due": .text("2026-07-11"), "prio": .text("1")], 0: ["x": .text("y")], 2: ["allday": .flag(false)]])
    let edits = eb["edits"] as? [[String: Any]] ?? []
    check(eb["plan_v"] as? Int == 2 && edits.count == 2, "Aendern: nur Schritte mit Feldern")
    check((edits.first?["args"] as? [String: Any])?["text"] as? String == "Pavillon holen" && (edits.first?["args"] as? [String: Any])?["prio"] == nil, "nur erlaubte Felder, getrimmt")
    check(edits.last?["n"] as? Int == 2 && (edits.last?["args"] as? [String: Any])?["allday"] as? Bool == false, "Schalter als Wahrheitswert")
    // 409-Faelle
    check(L.actionResult(code: 200, body: data(#"{"ok":true,"state":"laeuft","state_text":"Läuft","say":"Ich lege los."}"#)) == .ok(stateText: "Läuft", text: "Ich lege los."), "Freigabe ok")
    check(L.actionResult(code: 409, body: data(#"{"error":"Der Plan hat sich inzwischen geändert","code":"stale","plan_v":3}"#)) == .stale("Der Plan hat sich inzwischen geändert"), "409 stale")
    check(L.actionResult(code: 409, body: data(#"{"error":"wartet nicht mehr","code":"state","state":"laeuft"}"#)) == .state("wartet nicht mehr"), "409 state")
    check(L.actionResult(code: 409, body: data(#"{"error":"Das wurde seitdem geändert.","code":"changed","version_url":"/api/ai/jobs/12/changes/5/before"}"#)) == .changed(versionURL: "api/ai/jobs/12/changes/5/before"), "409 changed mit alter Fassung")
    check(L.actionResult(code: 409, body: data(#"{"code":"changed","version_url":null}"#)) == .changed(versionURL: nil), "409 changed ohne alte Fassung")
    check(L.actionResult(code: 403, body: data(#"{"error":"Lerne Marin zuerst kennen","consent":"needed"}"#)) == .consent("Lerne Marin zuerst kennen"), "403 Einwilligung")
    check(L.actionResult(code: 500, body: data("x")) == .failed(""), "sonst Fehler")
    check(L.actionResult(code: 200, body: data(#"{"ok":true,"already":true,"text":"Schon erledigt – der Termin ist weg."}"#)) == .ok(stateText: nil, text: "Schon erledigt – der Termin ist weg."), "Rueckgaengig: Termin schon weg = 200 already")
    check(L.actionResult(code: 200, body: data(#"{"ok":true,"state":"abgebrochen","state_text":"Abgebrochen","text":"Nichts ist passiert."}"#)) == .ok(stateText: "Abgebrochen", text: "Nichts ist passiert."), "Abbruch-Text")
    // Pfad der alten Fassung: nur eigener Server
    check(L.beforePath("/api/ai/jobs/1/changes/2/before") == "api/ai/jobs/1/changes/2/before", "eigener Pfad")
    for bad in ["https://evil.example/api/ai/jobs/1/changes/2/before", "//evil.example/api/ai/jobs/1/changes/2/before", "/api/ai/jobs/1/changes/2/../../x/before",
                "/api/notes/1", "/api/ai/jobs/x/changes/2/before", "/api/ai/jobs/1/changes/2/before?x=1", "/api/ai/jobs/1/changes/2/before/x", ""] {
        check(L.beforePath(bad) == nil, "fremder Pfad verworfen: \(bad)")
    }

    // --- Liste: 4 Gruppen ---------------------------------------------------------------------------------------------
    let list = SagaJobList(data: data(#"""
    {"groups":[{"key":"laeuft","label":"Läuft","items":[{"id":3,"title":"Kohle","state":"laeuft","state_text":"Läuft","group":"laeuft","line":"läuft"}]},
               {"key":"wartet_auf_dich","label":"Wartet auf dich","items":[{"id":4,"title":"Pavillon","state":"wartet_ok","state_text":"Wartet auf dein OK","line":"Wartet auf dein OK"},
                                                                     {"id":5,"title":"Fest","state":"wartet_dich","state_text":"Wartet auf dich","line":"Einwilligung widerrufen"}]},
               {"key":"nicht_geklappt","label":"Hat nicht geklappt","items":[]},{"key":"fertig","label":"Fertig","items":[{"id":6,"title":"","state":"zurueckgenommen","state_text":"Zurückgenommen","line":""}]},
               {"key":"neu","label":"Neu","items":[]}],"counts":{}}
    """#))
    check(list?.groups.map(\.key) == ["wartet_auf_dich", "laeuft", "fertig", "nicht_geklappt", "neu"], "feste Reihenfolge, Unbekanntes hinten")
    check(list?.visible.map(\.key) == ["wartet_auf_dich", "laeuft", "fertig"] && list?.total == 4, "leere Gruppen unsichtbar")
    check(list?.groups[0].items[0].group == "wartet_auf_dich", "fehlende Gruppe aus dem Zustand")
    check(list?.groups[0].items[0].subline == "Wartet auf dein OK" && list?.groups[0].items[1].subline == "Wartet auf dich · Einwilligung widerrufen", "Nebenzeile ohne Dopplung")
    check(list?.groups[1].items[0].subline == "Läuft", "Nebenzeile laeuft: nie 'Läuft · läuft'")
    check(L.subline(stateText: "Läuft", line: "Läuft · wartet auf Ben") == "Läuft · wartet auf Ben" && L.subline(stateText: "Erledigt", line: "") == "Erledigt", "Nebenzeile Praefix")
    for (st, g) in [("wartet_ok", "wartet_auf_dich"), ("wartet_dich", "wartet_auf_dich"), ("laeuft", "laeuft"), ("erledigt", "fertig"), ("abgebrochen", "fertig"),
                    ("zurueckgenommen", "fertig"), ("fehlgeschlagen", "nicht_geklappt")] {
        check(L.groupOf(st) == g, "Zustand \(st) -> \(g)")
    }
    check(L.groupOf("neu") == nil && L.groupSymbol("nicht_geklappt") == "exclamationmark.circle" && L.groupSymbol("wartet_auf_dich") == "circle.fill", "Gruppen-Symbole")
    check(SagaJobList(data: data(#"{"error":"x"}"#)) == nil, "ohne groups keine Liste")

    // --- Detail und Rueckmeldung --------------------------------------------------------------------------------------
    let det = SagaJobDetail(data: data(#"""
    {"id":12,"title":"Pavillon","state":"erledigt","state_text":"Erledigt","reason_text":null,"group":"fertig",
     "plan":\#(planJSON),"approved":{"at":1764000100,"device":"iPhone"},
     "changes":[{"id":5,"step":1,"target":"task:9","label":"Aufgabe „Pavillon abholen“","undo_class":"undo","class_text":"rückgängig","undone":false,"can_undo":true,"why":null,"version_url":null},
                {"id":6,"step":2,"target":"note:3","label":"Seite „Fest“","undo_class":"undo","class_text":"rückgängig","undone":false,"can_undo":false,"why":"changed","version_url":"/api/ai/jobs/12/changes/6/before"}],
     "report":{"text":"Erledigt: Pavillon steht in deinen Aufgaben.","lines":[{"change":5,"text":"Aufgabe „Pavillon abholen“ angelegt","undone":false,"can_undo":true},{"change":6,"text":"Seite „Fest“ geändert","undone":false,"can_undo":false}]},
     "events":[{"at":1764000000,"kind":"state","text":"Plan vorgeschlagen"},{"at":1764000100,"kind":"approve","text":"Freigegeben · iPhone"},{"at":1,"kind":"x"}],"actions":["undo"]}
    """#))
    check(det?.stateText == "Erledigt" && det?.approvedDevice == "iPhone" && det?.events.count == 2 && det?.awaitingOK == false, "Detail gelesen (Protokoll ohne leere Zeilen)")
    check(det?.feedback?.text.hasPrefix("Erledigt:") == true && det?.feedback?.lines.count == 2 && det?.cancellable == false, "Rueckmeldung: Ergebnis zuerst")
    check(det?.change(6)?.why == "changed" && det?.change(6)?.versionURL == "api/ai/jobs/12/changes/6/before" && det?.change(5)?.canUndo == true, "Aenderung: Rueckgaengig bzw. alte Fassung")
    let l1 = SagaReport.Line(change: 1, text: "Aufgabe „X“ angelegt", undone: false, canUndo: true, label: "Aufgabe „X“")
    check(SagaJobDetail.lineCaption(l1, count: 1) == nil && SagaJobDetail.lineCaption(l1, count: 2) == "Aufgabe „X“", "neben Rueckgaengig: bei einer Aenderung nur der Knopf, sonst der Gegenstand")
    check(SagaJobDetail.lineCaption(SagaReport.Line(change: 2, text: "Ganze Zeile", undone: false, canUndo: true), count: 2) == "Ganze Zeile", "aeltere Server ohne label")
    check(SagaReport(["text": "t", "lines": [["change": 3, "text": "a", "label": "b", "can_undo": true]]])?.lines.first?.label == "b", "label gelesen")
    check(L.itemSymbol(group: "fertig", state: "erledigt") == "checkmark.circle" && L.itemSymbol(group: "fertig", state: "abgebrochen") == "minus.circle"
          && L.itemSymbol(group: "fertig", state: "zurueckgenommen") == "arrow.uturn.backward.circle" && L.itemSymbol(group: "laeuft", state: "laeuft") == "clock", "Listen-Symbole je Zustand")
    check(det?.planSteps.count == 3 && det?.readText == "Ich lese dafür: Sommerfest", "Plan im Detail")
    let waiting = SagaJobDetail(data: data(#"{"id":12,"title":"P","state":"wartet_ok","state_text":"Wartet auf dein OK","plan":\#(planJSON),"changes":[],"events":[],"actions":["approve","edit","cancel"]}"#))
    check(waiting?.awaitingOK == true && waiting?.cancellable == false && waiting?.feedback == nil, "wartet_ok: Plan-Karte, kein Abbrechen-Knopf daneben")
    let running = SagaJobDetail(data: data(#"{"id":3,"title":"K","state":"wartet_dich","state_text":"Wartet auf dich","reason_text":"Einwilligung widerrufen","plan":{},"changes":[],"events":[],"actions":["cancel"]}"#))
    check(running?.cancellable == true && running?.plan == nil && running?.reasonText == "Einwilligung widerrufen", "wartet_dich: abbrechen, Grund")
    let undone = SagaJobDetail(data: data(#"{"id":3,"title":"K","state":"zurueckgenommen","state_text":"Zurückgenommen","reason_text":"","changes":[],"events":[],"actions":[]}"#))
    check(undone?.feedback?.text == "Zurückgenommen", "ohne report: Zustandswort")
    // Live
    check(L.jobChanged(["id": 12, "state": "erledigt", "sub": NSNull()])! == (12, "erledigt"), "ai.job.changed")
    check(L.jobChanged(["state": "x"]) == nil, "ohne id nichts")
    check(L.reportDue(watched: true, state: "erledigt") && !L.reportDue(watched: true, state: "laeuft") && !L.reportDue(watched: false, state: "erledigt") && !L.reportDue(watched: true, state: nil), "Rueckmeldung an Ort und Stelle")

    // --- Datumsfelder beim Aendern -------------------------------------------------------------------------------------
    check(L.dateKind(field: "due", value: "") == .day && L.dateKind(field: "start", value: "2026-07-11") == .day && L.dateKind(field: "end", value: "2026-07-11T18:00") == .minute && L.dateKind(field: "text", value: "x") == nil, "Feldart")
    if let d = L.date("2026-07-11T18:30", kind: .minute) { check(L.string(d, kind: .minute) == "2026-07-11T18:30", "Minute hin und zurueck") } else { check(false, "Minute") }
    if let d = L.date("2026-07-11", kind: .day) { check(L.string(d, kind: .day) == "2026-07-11", "Tag hin und zurueck") } else { check(false, "Tag") }
    check(L.date("2026-07-11T18:30:00+00:00", kind: .minute) != nil && L.date("morgen", kind: .day) == nil, "ISO mit Zone, Unsinn nil")

    // --- Einstellungen, Meine Daten, Melden ----------------------------------------------------------------------------
    let pr = SagaPrefs(data: data(#"{"salutation":null,"lively":"sachlich","effective":{"salutation":"du","lively":"sachlich"},"default":{"salutation":"sie","lively":"warm"},"choices":{"salutation":["du","sie"],"lively":["sachlich","warm"]},"ideas":"off","sources":{"pages":true,"tasks":false},"history":false,"history_allowed":true,"ideas_allowed":true,"consent":{"state":"granted","at":5}}"#))
    check(pr?.enabled == true && pr?.salutation == nil && pr?.lively == "sachlich" && pr?.defaultSalutation == "sie" && pr?.livelyChoices == ["sachlich", "warm"], "Prefs Anrede/Lebhaftigkeit")
    check(pr?.source("tasks") == false && pr?.source("calendar") == true && pr?.historyAllowed == true && pr?.consent?.granted == true, "Prefs Quellen/Verlauf/Einwilligung")
    check(SagaPrefs(data: data(#"{"enabled":false}"#))?.enabled == false, "KI aus")
    var cal = DateComponents(); cal.year = 2026; cal.month = 11; cal.day = 26; cal.hour = 12
    if let now = Calendar(identifier: .gregorian).date(from: cal) {
        check(L.exportName(format: "md", now: now) == "saga-ki-2026-11-26.md" && L.exportName(format: "evil/../x", now: now) == "saga-ki-2026-11-26.json", "Dateiname des Exports")
    }
    check(L.deleteBody["confirm"] as? Bool == true, "Alles loeschen mit confirm")
    let rb = L.reportBody(where: "job", job: 12, reason: "other", text: String(repeating: "x", count: 2500))
    check(rb["where"] as? String == "job" && rb["page"] as? Int == 12 && (rb["text"] as? String)?.count == 2000, "Melden: Auftrag, Text begrenzt")
    check(L.reportBody(where: "ask", job: nil, reason: "spam", text: "  ")["text"] == nil && L.reportBody(where: "ask", job: nil, reason: "spam", text: nil)["page"] == nil, "Melden ohne Text")
    check(SettingsArea.area(for: "saga") == .saga && SettingsArea.area(for: "mydata") == .saga && SettingsArea.scrollTarget(for: "saga") == nil, "Einstellungen › Saga (Sprungziel)")
    check(L.planText(plan).contains("Aufgabe „Pavillon abholen“") && L.tag == -7 && !FedLogic.isPageID(L.tag), "Plan-Text, Tag ist keine Seite")

    // --- piossaga: neue Server-Zuege ueber die Nummer (Verlauf kuerzt auf 200 - die Anzahl bleibt gleich) -----------------
    let t200 = (1...200).map { SagaTurn(n: $0, role: $0 % 2 == 1 ? "me" : "ai", text: "t\($0)") }
    let m0 = L.maxN(t200 + [SagaTurn(role: "me", text: "lokal")])
    let trimmed = Array(t200.dropFirst(2)) + [SagaTurn(n: 201, role: "me", zug: "job", text: "Bitte"), SagaTurn(n: 202, role: "ai", zug: "plan", text: "Plan: X", jobID: 7)]
    check(m0 == 200 && trimmed.count == t200.count && L.hasServerTurns(trimmed, after: m0), "200 Zuege gekuerzt: neue Zuege trotz gleicher Anzahl erkannt (kein doppeltes Anhaengen)")
    check(!L.hasServerTurns(t200, after: m0) && !L.hasServerTurns(t200 + [SagaTurn(role: "ai", text: "lokal")], after: m0), "aelterer Server ohne Zuege: nichts Neues (lokaler Rueckfall)")
    check(L.maxN([]) == 0 && L.hasServerTurns([SagaTurn(n: 1, role: "me", text: "x")], after: 0), "leerer Verlauf")
}
