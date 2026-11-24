import Foundation

// 2.6 (Bugs-DB 2031): Erste Schritte und leere Zustaende (App/FirstStepsLogic.swift)
func firstStepsTests() {
    typealias L = FirstStepsLogic
    func d(_ s: String) -> Data { Data(s.utf8) }

    // GET: Frage, Seite, Punkte
    let ask = L.parse(status: 200, data: d(#"{"enabled":true,"ask":true,"state":null,"page":null,"items":[{"key":"avatar","done":false},{"key":"app","done":true}]}"#))
    guard case .state(let s1) = ask else { check(false, "GET ask: Stand erwartet"); return }
    check(s1.ask && s1.answer == nil && s1.page == nil, "ask:true ohne Zustand und Seite")
    check(s1.items == [.init(key: "avatar", done: false), .init(key: "app", done: true)] && s1.done == 1, "Punkte gelesen, 1 erledigt")
    check(L.help(s1) == .create, "ohne Seite: Erste Schritte anlegen")

    let yes = L.parse(status: 200, data: d(#"{"enabled":true,"ask":false,"state":"yes","page":42,"items":[{"key":"avatar","done":true},{"key":"chat","done":true},{"key":"app","done":false},{"key":"page","done":false},{"key":"task","done":true}]}"#))
    guard case .state(let s2) = yes else { check(false, "GET yes: Stand erwartet"); return }
    check(!s2.ask && s2.answer == "yes" && s2.page == 42, "state yes mit Seite")
    check(L.help(s2) == .open(done: 3, all: 5), "Hilfe: 3 von 5 erledigt")

    // abgeschaltet, aeltere Server, Fehler
    check(L.parse(status: 200, data: d(#"{"enabled":false,"ask":true,"state":null,"page":null,"items":[]}"#)) == .off, "enabled:false -> nichts zeigen (auch bei ask)")
    check(L.parse(status: 200, data: d(#"{"ask":true}"#)) == .off, "ohne enabled -> nichts zeigen")
    check(L.parse(status: 404, data: d("Not Found")) == .off, "aelterer Server (404) -> still nichts")
    check(L.parse(status: 403, data: d("{}")) == .off, "403 -> nichts")
    check(L.parse(status: 500, data: d("{}")) == .failed && L.parse(status: 0, data: Data()) == .failed, "5xx/offline -> Stand behalten")
    check(L.parse(status: 200, data: d("kaputt")) == .failed, "kein JSON -> Stand behalten")
    check(L.help(nil) == nil, "kein Stand -> kein Abschnitt in den Einstellungen")

    // Seiten-ID: nur positive ganze Zahlen
    check(L.pageID(NSNumber(value: 7)) == 7 && L.pageID(nil) == nil && L.pageID(NSNull()) == nil, "page-ID")
    check(L.pageID(NSNumber(value: true)) == nil && L.pageID("7") == nil && L.pageID(NSNumber(value: 0)) == nil && L.pageID(NSNumber(value: 1.5)) == nil, "page-ID: kein Bool/String/0/Bruch")
    if case .state(let s) = L.parse(status: 200, data: d(#"{"enabled":true,"ask":false,"state":"vielleicht","page":true,"items":[{"done":true},{"key":"page"}]}"#)) {
        check(s.answer == nil && s.page == nil && s.items == [.init(key: "page", done: false)], "unbekannter Zustand/Seite/Punkt ohne key verworfen")
    } else { check(false, "robust lesen") }

    // POST
    check((L.body(yes: true)["answer"] as? String) == "yes" && (L.body(yes: false)["answer"] as? String) == "no", "POST-Body")
    check(L.parsePost(status: 200, data: d(#"{"ok":true,"page":42}"#)) == .ok(page: 42), "POST yes -> Seite")
    check(L.parsePost(status: 200, data: d(#"{"ok":true}"#)) == .ok(page: nil), "POST no -> ok")
    check(L.parsePost(status: 403, data: d(#"{"error":"aus"}"#)) == .off, "POST 403 (abgeschaltet)")
    check(L.parsePost(status: 404, data: d("")) == .off, "POST 404 (aelterer Server)")
    check(L.parsePost(status: 500, data: d(#"{"error":"kaputt"}"#)) == .error("kaputt"), "POST 5xx -> Fehlermeldung")
    check(L.parsePost(status: 200, data: d(#"{"ok":false}"#)) == .error(""), "ok:false -> Fehler")

    // Stand nach der Antwort
    let a1 = L.answered(s1, yes: true, page: 9)
    check(a1?.ask == false && a1?.answer == "yes" && a1?.page == 9 && L.help(a1) == .open(done: 1, all: 2), "Ja: Seite gemerkt, Frage weg")
    let a2 = L.answered(s1, yes: false, page: nil)
    check(a2?.ask == false && a2?.answer == "no" && a2?.page == nil && L.help(a2) == .create, "Nein: keine Seite, Abschnitt bietet Anlegen")
    check(L.answered(s2, yes: false, page: nil)?.answer == "yes", "einmal ja bleibt ja (wie im Web)")
    check(L.answered(nil, yes: true, page: 1) == nil, "ohne Stand nichts erfinden")

    // Drosselung: hoechstens alle 60 s, force (neue Anmeldung) immer
    var t = L.Throttle()
    let t0 = Date(timeIntervalSince1970: 1_000_000)
    check(t.allow(now: t0), "erster Abruf")
    check(!t.allow(now: t0.addingTimeInterval(10)), "nach 10 s: gedrosselt")
    check(!t.allow(now: t0.addingTimeInterval(59)), "nach 59 s: gedrosselt")
    check(t.allow(now: t0.addingTimeInterval(61)), "nach 61 s: wieder")
    check(t.allow(now: t0.addingTimeInterval(62), force: true), "force: sofort")
    check(t.allow(now: t0.addingTimeInterval(-3600)), "Uhr zurueckgestellt: nicht ewig gesperrt")
    t.reset()
    check(t.allow(now: t0.addingTimeInterval(63)), "nach reset (Abmelden): sofort")

    // Frage nur, wenn nichts anderes vorne liegt
    check(L.showAsk(pending: true, blockers: .init()), "frei: fragen")
    check(!L.showAsk(pending: false, blockers: .init()), "nicht ausstehend: nicht fragen")
    for b in [L.Blockers(login: true), L.Blockers(pairing: true), L.Blockers(sheet: true), L.Blockers(chatSetup: true), L.Blockers(call: true)] {
        check(!L.showAsk(pending: true, blockers: b), "blockiert: \(b)")
    }

    // leere Zustaende
    typealias E = EmptyStateLogic
    check(E.tasks(open: 2, done: 0) == .open && E.tasks(open: 0, done: 3) == .allDone && E.tasks(open: 0, done: 0) == .never, "Aufgaben: offen/alles erledigt/noch nie")
    check(E.rows(total: 5, shown: 2) == .shown && E.rows(total: 5, shown: 0) == .filtered && E.rows(total: 0, shown: 0) == .empty, "Datenbank: Eintraege/gefiltert/leer")
    check(E.calendarConnected(status: 200, data: d(#"{"accounts":[],"allowed":["x"]}"#)) == false, "kein Kalender verbunden")
    check(E.calendarConnected(status: 200, data: d(#"{"accounts":[{"id":1,"calendars":[]}]}"#)) == true, "Kalender-Konto verbunden")
    check(E.calendarConnected(status: 404, data: d("")) == nil && E.calendarConnected(status: 200, data: d("{}")) == nil, "unbekannt -> kein Hinweis")

    // Verdrahtung: Abruf beim Start, im Vordergrund, nach dem Anmelden; Hilfe-Abschnitt; Frage an der Wurzel
    if let dir = ProcessInfo.processInfo.environment["SAGA_APP_SRC"],
       let root = try? String(contentsOfFile: dir + "/RootView.swift", encoding: .utf8),
       let set = try? String(contentsOfFile: dir + "/SettingsView.swift", encoding: .utf8) {
        check(root.contains("if p == .active { Task { await store.refresh(); store.live.start(); await store.calls.checkActive(); await FirstStepsModel.shared.check(store) }"), "Vordergrund: Erste Schritte abrufen")
        check(root.contains("Task { await FirstStepsModel.shared.check(store) }"), "Start: Erste Schritte abrufen")
        check(root.contains("check(store, force: true)") && root.contains("FirstStepsModel.shared.reset()"), "Anmelden: sofort, Abmelden: vergessen")
        check(root.contains(".modifier(FirstStepsAsk("), "Frage haengt an der Wurzel")
        check(set.contains("FirstStepsSection(close: close)"), "Einstellungen > Info: Abschnitt Erste Schritte")
    } else { check(false, "SAGA_APP_SRC fehlt") }
}
