import Foundation

// 2.6 K-G (Bugs-DB 2275, App/SagaIntentLogic.swift): Siri "Frag Saga" / "Gib Saga einen Auftrag" / "Was steht heute an",
// Widget-Zaehler „Wartet auf dich“, 2508 (giveBody(note:)), 2531 („Deine Bitte“). RT-32 als Logik-Nachweis: der Auftrag
// aus Siri hat genau einen Serverweg (POST /api/ai/jobs) und endet an der Plan-Karte - kein approve, kein act.
func sagaIntentTests() {
    typealias L = SagaIntentLogic
    func data(_ s: String) -> Data { Data(s.utf8) }

    // --- Kuerzen fuer Siri -------------------------------------------------------------------------------------------
    let a = L.speech("Das Sommerfest ist am 7. Juli [1]. Es beginnt um 15 Uhr [1, 2]. Kuchen bringt Anna [3]. Danach wird aufgeräumt [2].")
    check(a.text == "Das Sommerfest ist am 7. Juli. Es beginnt um 15 Uhr. Kuchen bringt Anna.", "Marker [n] raus, hoechstens 3 Saetze: \(a.text)")
    check(a.cut, "gekuerzt gemeldet (Siri sagt 'Mehr in der Saga-App')")
    check(!a.text.contains("["), "kein [n] im Vorlesetext")
    let b = L.speech("Ja, z. B. am Montag [1]. Mehr steht nicht da.")
    check(b.text == "Ja, z. B. am Montag. Mehr steht nicht da." && !b.cut, "Abkuerzung 'z. B.' ist kein Satzende: \(b.text)")
    let md = L.speech("**Kurz:** Drei Punkte\n- Elternabend\n- Kuchen backen\n- Pavillon")
    check(!md.text.contains("*") && !md.text.contains("- ") && md.text.hasPrefix("Kurz: Drei Punkte."), "Markdown und Listen als Saetze: \(md.text)")
    check(md.cut, "Liste mit 4 Zeilen -> 3 Saetze, gekuerzt")
    check(L.speech("").text.isEmpty && L.speech(" [1] ").text.isEmpty, "leer bleibt leer")
    let thai = String(repeating: "วันนี้มีประชุม", count: 60)
    let t = L.speech(thai)
    check(t.text.count <= L.maxChars + 1 && t.cut && t.text.hasSuffix("…"), "ohne Satzzeichen (Thai): hart nach \(L.maxChars) Zeichen")
    check(L.speech("Eins. Zwei. Drei.").text == "Eins. Zwei. Drei.", "genau 3 Saetze bleiben ganz")
    check(!L.speech("Eins. Zwei. Drei.").cut, "genau 3 Saetze: nicht gekuerzt")

    // --- Sperren nach Feature / Faehigkeit / Einwilligung ------------------------------------------------------------
    let full = #"""
    {"enabled":true,"caps":["ai"],"style":{"label":"Marin · KI"},"jobs":true,"level":"plans","consent":{"state":"granted","host":"llm.example"},
     "intro":{"version":"k19-1","host":"llm.example","lines":["Ich bin Marin."],"details":[],"bar":""}}
    """#
    let ok = SagaStatus(data: data(full))
    let none = SagaStatus(data: data(full.replacingOccurrences(of: #""state":"granted""#, with: #""state":"none""#)))
    let withdrawn = SagaStatus(data: data(full.replacingOccurrences(of: #""state":"granted""#, with: #""state":"withdrawn""#)))
    let stale = SagaStatus(data: data(full.replacingOccurrences(of: #""state":"granted""#, with: #""state":"stale""#)))
    let answers = SagaStatus(data: data(full.replacingOccurrences(of: #""level":"plans""#, with: #""level":"answers""#)))
    let noJobs = SagaStatus(data: data(full.replacingOccurrences(of: #""jobs":true"#, with: #""jobs":false"#)))
    let off = SagaStatus(data: data(full.replacingOccurrences(of: #""enabled":true"#, with: #""enabled":false"#)))
    let old = SagaStatus(data: data(#"{"enabled":true,"host":"x"}"#))
    check(L.gate(.ask, signedIn: true, status: ok) == .ok && L.gate(.give, signedIn: true, status: ok) == .ok, "mit Einwilligung: Fragen und Auftraege")
    check(L.gate(.ask, signedIn: false, status: ok) == .signIn && L.gate(.give, signedIn: false, status: ok) == .signIn, "nicht angemeldet")
    for (n, s) in [("none", none), ("withdrawn", withdrawn), ("stale", stale)] {
        check(L.gate(.ask, signedIn: true, status: s) == .intro && L.gate(.give, signedIn: true, status: s) == .intro, "Einwilligung \(n): App mit Kennenlernen, nichts ans Modell")
    }
    check(L.gate(.ask, signedIn: true, status: nil) == .off && L.gate(.give, signedIn: true, status: nil) == .off, "ohne Ort 'Saga' (keine KI): aus")
    check(L.gate(.ask, signedIn: true, status: off) == .off && L.gate(.ask, signedIn: true, status: old) == .off, "KI aus bzw. aelterer Server ohne Einwilligung: aus")
    check(L.gate(.ask, signedIn: true, status: answers) == .ok && L.gate(.give, signedIn: true, status: answers) == .off, "Stufe answers: Fragen ja, Auftraege nein")
    check(L.gate(.give, signedIn: true, status: noJobs) == .off, "jobs false: kein Auftrag aus Siri")
    check(L.gate(.give, signedIn: true, status: SagaStatus(data: data(full.replacingOccurrences(of: #""jobs":true"#, with: #""jobs":false"#)
        .replacingOccurrences(of: #""state":"granted""#, with: #""state":"none""#)))) == .off, "ohne Auftraege hilft Kennenlernen nicht: aus vor intro")

    let feat: Set<String> = [TodayLineLogic.feature]
    check(L.todayGate(signedIn: true, features: feat, state: nil) == .ok, "Heute: Merkmal aitoday1 da")
    check(L.todayGate(signedIn: true, features: [], state: nil) == .off, "Heute: aelterer Server ohne aitoday1 -> aus")
    check(L.todayGate(signedIn: false, features: feat, state: nil) == .signIn, "Heute: nicht angemeldet")
    check(L.todayGate(signedIn: true, features: feat, state: TodayLineLogic.decode(data(#"{"enabled":false}"#))) == .off, "Heute: ohne Faehigkeit ai -> aus")

    // --- Was steht heute an: sentence + waiting.text (Server-Vektoren) -------------------------------------------------
    let u = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("vectors/today_vectors.json")
    if let raw = try? Data(contentsOf: u), let v = (try? JSONSerialization.jsonObject(with: raw)) as? [String: Any], let cases = v["cases"] as? [[String: Any]] {
        var n = 0
        for c in cases {
            guard let id = c["id"] as? String, c["status"] as? Int == 200, let r = c["response"] as? [String: Any],
                  (c["request"] as? [String: Any])?["path"].map({ "\($0)".hasPrefix("/api/ai/today") }) == true,
                  let d = try? JSONSerialization.data(withJSONObject: r) else { continue }
            let st = TodayLineLogic.decode(d)
            let sp = L.todaySpeech(st)
            if r["enabled"] as? Bool != true { check(sp == nil, "\(id): ohne ai nichts vorlesen"); n += 1; continue }
            let sen = r["sentence"] as? String ?? ""
            check(st?.sentence == sen, "\(id): sentence decodiert")
            check(sp?.hasPrefix(sen) == true, "\(id): Siri beginnt mit sentence")
            let w = r["waiting"] as? [String: Any]
            if let wn = w?["n"] as? Int, wn > 0, let wt = w?["text"] as? String {
                let bare = wt.replacingOccurrences(of: " ›", with: "")
                check(sp?.contains(bare) == true && sp?.contains("›") == false, "\(id): waiting.text ohne › angehaengt")
            } else {
                check(sp == sen, "\(id): ohne Wartendes nur sentence")
            }
            n += 1
        }
        check(n >= 14, "Heute-Vektoren fuer Siri geprueft (\(n))")
        // Widget-Antwort (hk_) kennt kein sentence
        if let w = cases.first(where: { $0["id"] as? String == "widget" }), let r = w["response"] as? [String: Any], let line = r["line"],
           let d = try? JSONSerialization.data(withJSONObject: line) {
            check(TodayLineLogic.decode(d)?.sentence == nil, "Widget-Zeile ohne sentence")
        }
    } else { check(false, "Tests/vectors/today_vectors.json fehlt") }
    let noSentence = TodayLineLogic.decode(data(#"{"enabled":true,"on":true,"line":{"text":"A · B","a11y":"Kurz gesagt: A, B","parts":[{"kind":"event","text":"A"}]},"waiting":{"n":0,"text":""}}"#))
    check(L.todaySpeech(noSentence) == "Kurz gesagt: A, B", "ohne sentence (aelterer Stand): Vorlesezeile")
    check(L.todaySpeech(TodayLineLogic.decode(data(#"{"enabled":true,"on":false,"line":null}"#))) == nil, "nichts vom Server: nil (Intent sagt 'Für heute liegt nichts an.')")

    // --- Widget-Zaehler (Klein, Sperrbildschirm) -----------------------------------------------------------------------
    let wl = TodayLineLogic.decode(data(#"{"enabled":true,"on":true,"line":{"text":"Elternabend","parts":[{"kind":"event","text":"Elternabend"}]},"waiting":{"n":2,"text":"2 Aufträge warten auf dich ›"},"counts":{"events":1,"over":0,"due":0,"waiting":2}}"#))
    check(TodayLineLogic.widgetCount(wl, size: .small) == 2 && TodayLineLogic.widgetCount(wl, size: .lock) == 2, "Klein/Sperrbildschirm: Zahl counts.waiting")
    check(TodayLineLogic.widgetCount(wl, size: .medium) == nil && TodayLineLogic.widgetCount(wl, size: .large) == nil, "Mittel/Gross: kein Zaehler (Gross hat die Zeile)")
    check(TodayLineLogic.widgetText(wl, size: .small) == nil && TodayLineLogic.widgetText(wl, size: .lock) == nil, "RT-31: Klein/Sperrbildschirm nie Text")
    check(TodayLineLogic.widgetWaiting(wl, size: .large) == "2 Aufträge warten auf dich ›", "Gross: „Wartet auf dich“ (als Link)")
    let quiet = TodayLineLogic.decode(data(#"{"enabled":true,"on":true,"line":null,"waiting":{"n":1,"text":"x"},"counts":{"waiting":1},"quiet":{"text":"Ruhe","undo":"An"}}"#))
    check(TodayLineLogic.widgetCount(quiet, size: .small) == 1, "Ruhe: Zaehler bleibt (wie waiting)")
    check(TodayLineLogic.widgetCount(TodayLineLogic.decode(data(#"{"enabled":true,"counts":{"waiting":0}}"#)), size: .small) == nil, "0 wartet: kein Zaehler")
    check(TodayLineLogic.widgetCount(TodayLineLogic.decode(data(#"{"enabled":false}"#)), size: .lock) == nil, "ohne ai: kein Zaehler")
    check(TodayLineLogic.widgetCount(nil, size: .small) == nil, "aelterer Server (kein line): kein Zaehler")
    check(TodayLineLogic.widgetCount(TodayLineLogic.decode(data(#"{"enabled":true,"waiting":{"n":3,"text":"x"}}"#)), size: .small) == 3, "ohne counts: waiting.n")

    // --- 2508: giveBody(note:) -----------------------------------------------------------------------------------------
    let gb = SagaLogic.giveBody(text: "  Fass das zusammen  ", note: 42)
    check(gb["text"] as? String == "Fass das zusammen" && gb["source"] as? String == "saga", "Bitte getrimmt, Quelle saga")
    check((gb["context"] as? [String: Int])?["note"] == 42, "context.note mit der Seite")
    check(SagaLogic.giveBody(text: "x")["context"] == nil && SagaLogic.giveBody(text: "x", note: 0)["context"] == nil, "ohne Seite (bzw. 0) kein context")
    check((SagaLogic.giveBody(text: "x", choice: "Ja", note: 7)["context"] as? [String: Int])?["note"] == 7, "Rueckfrage-Wahl behaelt die Seite")

    // --- RT-32: Auftrag aus Siri endet an der Plan-Karte ---------------------------------------------------------------
    let req = L.giveRequest("Trag den Elternabend ein")
    check(req.path == "api/ai/jobs" && req.method == "POST" && req.body["thread"] as? Bool == true, "einziger Weg: POST /api/ai/jobs mit thread")
    check(!req.path.contains("approve") && !req.path.contains("/act") && req.body["recipe"] == nil, "kein approve/act/recipe")
    func turn(_ n: Int?, _ role: String, _ zug: String, job: Int? = nil, text: String = "") -> SagaTurn { SagaTurn(n: n, role: role, zug: zug, text: text, jobID: job) }
    let before = [turn(1, "me", "job"), turn(2, "ai", "plan", job: 5)]
    let after = before + [turn(3, "me", "job"), turn(4, "ai", "plan", job: 9, text: "Plan: Elternabend")]
    check(L.giveEnd(running: false, error: nil, turns: after, n0: 2, count: 2) == .plan(job: 9), "neuer Plan-Zug -> Plan liegt bereit")
    check(L.giveEnd(running: false, error: nil, turns: before, n0: 2, count: 2) == .failed(""), "nur alter Plan (n <= n0): nicht der eigene")
    check(L.giveEnd(running: false, error: nil, turns: before + [turn(nil, "me", "job"), turn(nil, "ai", "plan", job: 11)], n0: 2, count: 2) == .plan(job: 11), "aelterer Server: lokal angehaengt")
    check(L.giveEnd(running: false, error: nil, turns: before + [turn(3, "me", "job"), turn(4, "ai", "ask", text: "Welcher Tag?")], n0: 2, count: 2) == .ask, "Rueckfrage -> App")
    check(L.giveEnd(running: false, error: nil, turns: before + [turn(3, "me", "job"), turn(4, "ai", "refuse", text: "Das kann ich nicht.")], n0: 2, count: 2) == .refuse("Das kann ich nicht."), "Absage")
    check(L.giveEnd(running: true, error: nil, turns: before, n0: 2, count: 2) == .planning, "plant noch -> App, Planen laeuft weiter")
    check(L.giveEnd(running: false, error: "Kontingent", turns: after, n0: 2, count: 2) == .failed("Kontingent"), "Fehler -> nichts passiert")
    for e in [L.GiveEnd.plan(job: 1), .ask, .refuse("x"), .planning, .failed("")] {
        // Kein Ausgang ist eine Freigabe: es gibt keinen Fall "approved" (Aufzaehlung vollstaendig, der Compiler prueft das)
        switch e { case .plan, .ask, .refuse, .planning, .failed: check(true, "Ausgang ohne Freigabe") }
    }
    // Quelltext-Nachweis: in den Saga-Intents und im Planen des Modells kein approve/act
    if let dir = ProcessInfo.processInfo.environment["SAGA_APP_SRC"],
       let src = try? String(contentsOfFile: dir + "/AppIntents.swift", encoding: .utf8),
       let model = try? String(contentsOfFile: dir + "/SagaModel.swift", encoding: .utf8) {
        func block(_ s: String, from a: String, to b: String) -> String {
            guard let r1 = s.range(of: a), let r2 = s.range(of: b, range: r1.upperBound..<s.endIndex) else { return "" }
            return String(s[r1.lowerBound..<r2.lowerBound])
        }
        let saga = block(src, from: "enum SagaIntentClient", to: "struct HKShortcuts")
        let give = block(src, from: "struct GiveJobIntent", to: "struct TodayIntent")
        check(!give.isEmpty && give.contains("giveInThread("), "GiveJobIntent plant ueber giveInThread")
        for bad in ["approve", "/act", "act(", "planHash", "atok"] { check(!saga.contains(bad), "RT-32: Saga-Intents ohne '\(bad)'") }
        check(!saga.contains("sagaName") && !saga.contains("aiName") && !saga.contains("aiPersona"), "Siri-Saetze nennen nie den Persona-Namen")
        check(saga.components(separatedBy: ".requiresAuthentication").count - 1 == 3, "alle drei Intents nur entsperrt")
        check(!saga.contains("stopPlanning") && !saga.contains("m.stop()") && !saga.contains(".cancel()"), "F2: Strom wird aus Siri nie abgebrochen")
        let gi = block(model, from: "func giveInThread(", to: "private func settleJob(")
        check(gi.contains("\"api/ai/jobs\"") && !gi.contains("approve") && !gi.contains("/act"), "giveInThread: nur POST /api/ai/jobs")
        check(gi.contains("note: noteContext?.id"), "2508: giveInThread schickt die Seite mit")
    } else { check(false, "SAGA_APP_SRC fehlt") }

    // --- Frag Saga -----------------------------------------------------------------------------------------------------
    check(L.askEnd(running: true, error: nil, answer: nil) == .foreground, "> 12 s: weiter in der App")
    check(L.askEnd(running: false, error: nil, answer: "Am Montag [1].") == .answer("Am Montag.", cut: false), "Antwort gekuerzt vorlesen")
    check(L.askEnd(running: false, error: "Kontingent erschoepft", answer: nil) == .failed("Kontingent erschoepft"), "Fehler vom Server")
    check(L.askEnd(running: false, error: nil, answer: "") == .foreground, "leere Antwort: in der App ansehen")
    check(L.answerWait == 12, "F2: ~12 s")
    check(L.busy(asking: true, askFailed: false, planning: false, planEnded: false), "Frage laeuft: beschaeftigt")
    check(L.busy(asking: false, askFailed: false, planning: true, planEnded: false), "Planen laeuft: beschaeftigt")
    check(!L.busy(asking: true, askFailed: true, planning: false, planEnded: false), "alte Fehlerkarte (Frage) blockiert Siri nicht")
    check(!L.busy(asking: false, askFailed: false, planning: true, planEnded: true), "alte Fehler-/Abbruchkarte (Auftrag) blockiert Siri nicht")
    check(!L.busy(asking: false, askFailed: false, planning: false, planEnded: false), "nichts laeuft")

    // --- 2531: Deine Bitte ---------------------------------------------------------------------------------------------
    let d1 = SagaJobDetail(data: data(#"{"id":3,"title":"Elternabend eintragen","state":"wartet_ok","request":"Trag bitte den Elternabend am 7.7. ein"}"#))
    check(d1?.requestShown == "Trag bitte den Elternabend am 7.7. ein", "Bitte anders als der Titel: zeigen")
    let d2 = SagaJobDetail(data: data(#"{"id":3,"title":"Elternabend eintragen","state":"erledigt","request":"Elternabend eintragen "}"#))
    check(d2?.requestShown == nil, "Bitte = Titel: nicht doppelt")
    check(SagaJobDetail(data: data(#"{"id":3,"title":"x","state":"laeuft","request":""}"#))?.requestShown == nil, "leere Bitte: nichts")
    check(SagaJobDetail(data: data(#"{"id":3,"title":"x","state":"laeuft"}"#))?.requestShown == nil, "aelterer Server ohne request: nichts")
}
