import Foundation

// 2.5 Fehlerberichte/Diagnose (clientlog1): Bereinigung, Endpunkt-Muster, Dedupe/Drossel, Ringpuffer, JSON-Form,
// Absturz-Notiz (App/ClientLogLogic.swift). Fixtures nach docs/APP-API.md "Fehlerberichte und Diagnose".
func clientLogTests() {
    typealias L = ClientLogLogic
    // Bereinigung: Beispiel aus dem Vertrag (Log-Zeile mit Testdaten)
    let probe = "Error: Seite \"Geheimer Plan\" fuer anna@example.org nicht geladen von https://saga.example.org/notes#12 (id 4711, 3f2b1c9e-1d2a-4b3c-8d4e-5f6a7b8c9d0e, deadbeefcafe, hk_abcdef123456, @anna)"
    let s = L.scrub(probe)
    check(s == "Error: <w> \"<str>\" <w> <email> <w> <w> <w> <url> (<w> <n>, <uuid>, <hex>, <secret>, <id>)", "scrub wie Vertrag: \(s)")
    check(!s.contains("Geheim") && !s.contains("anna") && !s.contains("example") && !s.contains("4711"), "keine Inhalte")
    check(L.scrub("URLError: The network connection was lost.") == "URLError: The network connection was lost.", "englische Fehlermeldung bleibt")
    check(L.scrub("Cannot read properties of undefined (reading 'title')") == "Cannot read properties of undefined (reading 'title')", "Eigenschaft in Anfuehrungszeichen bleibt")
    check(L.scrub("Wochenplan Einkauf") == "<w> <w>", "Seitentitel ohne Anfuehrungszeichen -> <w>")
    check(L.scrub("Datei /Users/someone/Library/x.plist fehlt") == "<w> <path> <w>", "Pfad mit Nutzername -> <path>: \(L.scrub("Datei /Users/someone/Library/x.plist fehlt"))")
    check(L.scrub("token ~abcdef12 und sgaABCDEFGHIJKLMNOPQRSTU1") == "token <id> <w> <tok>", "Handle und Zufallsfolge: \(L.scrub("token ~abcdef12 und sgaABCDEFGHIJKLMNOPQRSTU1"))")
    check(L.scrub("a\u{202e}b\u{0007}c") == "a b c", "Steuer- und Richtungszeichen weg")
    check(L.scrub(String(repeating: "x.y ", count: 200)).count <= 300, "auf 300 gekuerzt")
    // eigene Meldungen ueberstehen die Server-Bereinigung (Punkt-Bezeichner)
    check(L.scrub(L.httpMessage(code: 503, method: "PATCH", pattern: "/api/notes/:id")) == "HTTPError.status5xx: http.PATCH api.notes.ID", "5xx-Meldung: \(L.httpMessage(code: 503, method: "PATCH", pattern: "/api/notes/:id"))")
    let dec = L.decodeMessage(DecodingError.keyNotFound(CodingKeysProbe.x, .init(codingPath: [], debugDescription: "Geheim")), pattern: "/api/app/today")
    check(dec == "DecodingError.keyNotFound: http.GET api.app.today" && L.scrub(dec) == dec, "Dekodierfehler ohne Schluesselnamen: \(dec)")
    check(L.callMessage(phase: "connecting", reason: "failed") == "CallError.setup: phase.connecting reason.failed", "Anruf-Meldung")
    check(L.callMessage(phase: "x y", reason: "Anna ruft") == "CallError.setup: phase.other reason.other", "Anruf-Meldung nur Bezeichner")
    check(L.saveMessage(code: 503) == "SaveError.status5xx", "Speicher-Meldung")
    // Freitext
    check(L.note("Hallo\nMeine Adresse a@b.de, Schluessel hk_abcdefgh1 https://x.y/z") == "Hallo\nMeine Adresse <email>, Schluessel <secret> <url>", "Freitext maskiert: \(L.note("Hallo\nMeine Adresse a@b.de, Schluessel hk_abcdefgh1 https://x.y/z"))")
    check(L.note(String(repeating: "ab ", count: 900)).count == 1000, "Freitext <= 1000")

    // Endpunkt-Muster
    check(L.endpoint("api/notes/123/comments?x=1") == "/api/notes/:id/comments", "Muster mit ID und Query")
    check(L.endpoint("https://saga.example.org/sub/api/notes/-2049#a") == "/api/notes/:id", "volle Adresse: Host/Pfad davor weg")
    check(L.endpoint("api/chats/42/messages/abcDEF/reaction") == "/api/chats/:id/messages/:x/reaction", "unbekannter Abschnitt -> :x")
    check(L.endpoint("api/nextcloud/list?path=/Privat/Steuer") == "/api/nextcloud/list", "Query mit Pfad weg")
    check(L.endpoint("api/immich/thumb/Urlaub2025") == "/api/immich/thumb/:x", "Name im Pfad -> :x")
    check(L.endpoint("/static/app.js") == nil, "kein API-Pfad -> nil")
    check(L.dotted("/api/notes/:id/comments") == "api.notes.ID.comments" && L.dotted("/api/account/api-tokens/:x") == "api.account.api_tokens.X", "Muster mit Punkten")

    // Kopf
    let h = L.head(app: "ios", ver: "2.5.0-rc.3", os: "iOS 26", dev: "phone", sid: "k3J9xQ2mP7aZ4tWb")
    check(h["v"] as? Int == 1 && h["app"] as? String == "ios" && h["ver"] as? String == "2.5.0-rc.3" && h["os"] as? String == "iOS 26" && h["dev"] as? String == "phone", "Kopf")
    check(L.head(app: "mac", ver: "2.5 beta/1", os: "macOS\u{0}26", dev: "x", sid: "abcdefgh")["ver"] as? String == "2.5beta1" && L.head(app: "mac", ver: "1", os: nil, dev: "x", sid: "abcdefgh")["dev"] == nil, "Kopf bereinigt")
    var g = FixedRNG(seed: 7)
    let sid = L.newSID(using: &g)
    check(sid.count == 16 && sid.range(of: "^[A-Za-z0-9_-]{8,40}$", options: .regularExpression) != nil && sid != L.newSID(), "Sitzungskennung")

    // Reporter: Dedupe, 20er-Grenze, 30-s-Drossel, 404/429/401
    var r = L.Reporter()
    let e1 = L.Item.make("error", "HTTPError.status5xx: http.GET api.notes.ID", screen: "pages", t: 100)
    r.add(e1); r.add(e1); r.add(e1)
    check(r.pending.count == 1 && r.pending[0].n == 3, "gleiche Fehler gezaehlt")
    check(r.due(now: 0), "erster Bericht sofort")
    let first = r.take(now: 1000)
    check(first?.count == 1 && r.isEmpty, "entnommen")
    for i in 0..<25 { r.add(L.Item.make("error", "E\(i)x.y", t: 2000)) }
    check(r.pending.count == 20, "hoechstens 20 Eintraege")
    check(!r.due(now: 20_000) && r.take(now: 20_000) == nil && r.wait(now: 20_000) == 11_000, "30 s Abstand")
    let second = r.take(now: 31_000)
    check(second?.count == 20, "nach 30 s")
    r.response(code: 429, retryS: 120, items: second ?? [], now: 31_000)
    r.add(e1)
    check(!r.due(now: 100_000) && r.due(now: 151_001), "429: retry_s abwarten, nicht wiederholen (\(r.wait(now: 100_000)))")
    let third = r.take(now: 152_000) ?? []
    r.response(code: 401, retryS: nil, items: third, now: 152_000)
    check(r.pending.count == 1 && r.due(now: 152_001), "401: Eintraege fuer nach der Anmeldung aufheben")
    r.response(code: 404, retryS: nil, items: [], now: 153_000)
    check(r.off && r.isEmpty && !r.add(e1), "404: bis zum naechsten Start nichts mehr")

    // Bericht-JSON (Form wie im Vertrag)
    var c = L.Item.make("crash", "Signal.SIGSEGV", stack: "Saga Saga.PageDoc.save + 120", src: "/Users/x/NativePage.swift", line: 88, screen: "pages", t: 183_000)
    c.n = 2
    let body = L.logBody(head: h, items: [c])!
    let j = (try? JSONSerialization.jsonObject(with: body)) as? [String: Any] ?? [:]
    let it = (j["items"] as? [[String: Any]])?.first ?? [:]
    check(j["v"] as? Int == 1 && it["k"] as? String == "crash" && it["msg"] as? String == "Signal.SIGSEGV" && it["src"] as? String == "NativePage.swift"
          && it["line"] as? Int == 88 && it["screen"] as? String == "pages" && it["n"] as? Int == 2 && it["t"] as? Int == 183_000, "Bericht-JSON: \(it)")
    let big = (0..<20).map { L.Item.make("error", "E\($0)x.y", stack: String(repeating: "Saga a.b + 1\n", count: 600), t: 1) }
    let bb = L.logBody(head: h, items: big)!
    check(bb.count <= L.logBytes && ((try? JSONSerialization.jsonObject(with: bb)) as? [String: Any])?["items"] != nil, "Bericht <= 16 KB (\(bb.count))")
    check(L.Item.make("error", "x", screen: "Seite 12", t: 1).json["screen"] as? String == "other", "screen nur nach Muster")

    // Ringpuffer + Diagnose-JSON
    var ring = L.Ring()
    ring.push("life", t: 10, ["st": "start"])
    ring.push("view", t: 2100, ["screen": "pages"])
    ring.net(method: "GET", pattern: "/api/notes/:id", status: 200, ms: 84, t: 2300)
    ring.net(method: "PATCH", pattern: "/api/notes/:id", status: 409, ms: 120, t: 9100)
    ring.push("save", t: 9120, ["st": "conflict", "s": 409])
    ring.push("conn", t: 15_000, ["ch": "sse", "st": "retry"])
    ring.err(L.Item.make("error", "URLError: The network connection was lost.", screen: "pages", t: 16_000))
    check(ring.count == 7, "Ring: Ereignisse")
    for i in 0..<300 { ring.push("view", t: Double(20_000 + i), ["screen": "chat"]) }
    check(ring.count == 200 && ring.events.first?["t"] as? Int == 20_100, "Ring: hoechstens 200, aelteste fallen heraus")
    let db = L.diagBody(head: h, ring: ring.events, note: "Beim Speichern hing es kurz.")!
    let dj = (try? JSONSerialization.jsonObject(with: db)) as? [String: Any] ?? [:]
    check((dj["ev"] as? [Any])?.count == 200 && dj["note"] as? String == "Beim Speichern hing es kurz." && dj["sid"] as? String == "k3J9xQ2mP7aZ4tWb", "Diagnose-JSON")
    check(L.diagBody(head: h, ring: [], note: "  ").flatMap { (try? JSONSerialization.jsonObject(with: $0)) as? [String: Any] }?["note"] == nil, "leerer Freitext: kein note")
    var fat = L.Ring()
    for i in 0..<200 { fat.err(L.Item.make("error", String(repeating: "abc.def ", count: 40) + "\(i)", t: Double(i))) }
    let fb = L.diagBody(head: h, ring: fat.events, note: "")!
    check(fb.count <= L.diagBytes, "Diagnose <= 64 KB (\(fb.count))")
    check(L.diagID(Data(#"{"ok":true,"id":"D-7K3FM"}"#.utf8)) == "D-7K3FM" && L.diagID(Data(#"{"ok":true,"id":"D-7K3F0"}"#.utf8)) == nil, "Kennung D-XXXXX")

    // Absturz-Notiz
    check(L.stackLine("3   Saga                                0x0000000104f2a1c8 $s4Saga7PageDocC4saveyyYaF + 120", demangle: { _ in "Saga.PageDoc.save(Saga.Store) async -> ()" }) == "Saga Saga.PageDoc.save + 120", "Stackzeile: Modul + Symbol + Offset")
    check(L.stackLine("0   libsystem_kernel.dylib              0x00000001a2b3c4d5 __pthread_kill + 8") == "libsystem_kernel.dylib __pthread_kill + 8", "Stackzeile System")
    check(L.stackLine("5   Saga                                0x0000000104f2a1c8 0x104000000 + 117432") == "Saga + 117432", "ohne Symbol: Offset")
    check(L.stackLine("1   /Users/someone/Library/Developer/Saga.debug.dylib 0x1 foo + 1") == "Saga.debug.dylib foo + 1", "Pfad im Modul -> Dateiname")
    check(L.shortSymbol("closure #1 () -> () in Saga.Store.refresh() async -> ()") == "closure in Saga.Store.refresh", "Closure")
    let note = "SIG SIGSEGV\n0   libsystem_kernel.dylib              0x00000001a2b3c4d5 __pthread_kill + 8\n3   Saga                                0x0000000104f2a1c8 0x104000000 + 117432\n"
    let ci = L.crashItem(note, t: 50)
    check(ci?.k == "crash" && ci?.msg == "Signal.SIGSEGV" && ci?.stack == "libsystem_kernel.dylib __pthread_kill + 8\nSaga + 117432", "Signal-Notiz -> crash: \(String(describing: ci))")
    let ex = L.crashItem("EXC NSRangeException: *** -[__NSArrayM objectAtIndex:]: index 5 beyond bounds [0 .. 3]\n", t: 1)
    check(ex?.msg.hasPrefix("NSRangeException:") == true && ex?.msg.contains("5") == false, "Exception-Notiz bereinigt: \(ex?.msg ?? "-")")
    check(L.crashItem("", t: 1) == nil && L.crashItem("Unsinn", t: 1) == nil && L.crashItem("SIG FOO", t: 1)?.msg == "Signal.other", "unlesbare Notiz")
    check(L.signalLine("SIGABRT") == "SIG SIGABRT\n", "Signal-Zeile")
    // grober Ort
    check(ClientLogScreen.of(selection: 4711) == "pages" && ClientLogScreen.of(selection: -4) == "chat" && ClientLogScreen.of(selection: nil) == "dashboard", "Ort aus der Auswahl")
    var st = ClientLogScreen.Stack(); st.base = "pages"
    st.show("settings", true); st.show("calls", true)
    check(st.current == "calls", "Anruf ueber Einstellungen")
    st.show("calls", false); check(st.current == "settings", "Anruf zu: Einstellungen")
    st.show("settings", false); check(st.current == "pages", "zurueck zur Seite")
}

private enum CodingKeysProbe: String, CodingKey { case x }
private struct FixedRNG: RandomNumberGenerator {
    var s: UInt64
    init(seed: UInt64) { s = seed }
    mutating func next() -> UInt64 { s = s &* 6364136223846793005 &+ 1442695040888963407; return s >> 11 }
}
