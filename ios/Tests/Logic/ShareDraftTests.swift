import Foundation

// 2.6 K-G (Bugs-DB 2275, F1 a): iOS-Teilen -> Auftrag. Shared/ShareDraftLogic.swift (Entwurf in der App-Gruppe: hoechstens
// einer, 24 h, nach Uebernahme weg, nie macOS), SagaLogic.giveBody(shared:), Plan-Herkunft, Grenzen und Host wie der
// Server - gegen Tests/vectors/share_contract.json (Kopie von Server tests/vectors/share_contract.json).
func shareDraftTests() {
    typealias L = ShareDraftLogic
    func data(_ s: String) -> Data { Data(s.utf8) }
    func fresh(_ name: String) -> UserDefaults {
        let n = "saga-test-share-\(name)-\(UUID().uuidString)"
        let d = UserDefaults(suiteName: n)!
        d.removePersistentDomain(forName: n)
        return d
    }
    let p1 = SharePayload(text: "Elternabend am 7.7. um 19:30", url: "https://www.schule.example/termine", title: "Termine 2026")
    let p2 = SharePayload(text: "Grillkohle besorgen", url: nil, title: nil)
    let t0 = 1_764_320_000.0
    let srv = "https://saga.example"

    // --- Entwurf: Verfall, nur einer, Uebernahme loescht -------------------------------------------------------------
    let d = fresh("a")
    check(L.save(p1, server: srv, now: t0, in: d, platform: true), "Entwurf ablegen")
    check(L.peek(in: d, now: t0 + 60, server: srv, platform: true)?.payload == p1, "Entwurf liegt bereit")
    check(L.peek(in: d, now: t0 + L.maxAge - 1, server: srv, platform: true) != nil, "knapp unter 24 h gueltig")
    check(L.peek(in: d, now: t0 + L.maxAge + 1, server: srv, platform: true) == nil, "nach 24 h verfallen")
    check(d.data(forKey: L.draftKey) == nil, "verfallener Entwurf ist geloescht")
    _ = L.save(p1, server: srv, now: t0, in: d, platform: true)
    check(L.peek(in: d, now: t0 - L.futureSlack - 1, server: srv, platform: true) == nil, "Entwurf aus der Zukunft (Uhr verstellt) gilt nicht")
    _ = L.save(p1, server: srv, now: t0, in: d, platform: true)
    _ = L.save(p2, server: srv, now: t0 + 10, in: d, platform: true)
    check(L.take(in: d, now: t0 + 20, server: srv, platform: true)?.payload == p2, "hoechstens einer: der neue ersetzt den alten")
    check(L.take(in: d, now: t0 + 21, server: srv, platform: true) == nil, "nach der Uebernahme geloescht")
    check(d.data(forKey: L.draftKey) == nil, "nichts bleibt liegen")
    _ = L.save(p1, server: srv, now: t0, in: d, platform: true)
    check(L.take(in: d, now: t0 + 5, server: "https://anderer.example", platform: true) == nil, "anderer Server: nicht uebernehmen")
    check(d.data(forKey: L.draftKey) == nil, "Entwurf fuer einen anderen Server verworfen")
    _ = L.save(p1, server: srv, now: t0, in: d, platform: true)
    check(L.take(in: d, now: t0 + L.maxAge + 5, server: srv, platform: true) == nil && d.data(forKey: L.draftKey) == nil, "take: abgelaufen -> nil und weg")
    d.set(data("kaputt"), forKey: L.draftKey)
    check(L.peek(in: d, now: t0, server: srv, platform: true) == nil && d.data(forKey: L.draftKey) == nil, "unlesbarer Entwurf: weg")
    // Kein Ort "Saga" (Pruefer): offline bleibt der Entwurf, mit Antwort des Servers (KI aus) wird er verworfen
    _ = L.save(p1, server: srv, now: t0, in: d, platform: true)
    check(L.takeWithoutPlace(reachable: false, in: d, now: t0 + 5, server: srv, platform: true)?.payload == p1, "offline: Entwurf erkannt")
    check(L.peek(in: d, now: t0 + 6, server: srv, platform: true)?.payload == p1, "offline: Entwurf bleibt fuer spaeter")
    check(L.takeWithoutPlace(reachable: true, in: d, now: t0 + 7, server: srv, platform: true) != nil && d.data(forKey: L.draftKey) == nil,
          "Server antwortet, kein Ort Saga: Entwurf verworfen")
    _ = L.save(p1, server: srv, now: t0, in: d, platform: true)
    check(L.takeWithoutPlace(reachable: false, in: d, now: t0 + L.maxAge + 1, server: srv, platform: true) == nil && d.data(forKey: L.draftKey) == nil,
          "offline, aber abgelaufen: trotzdem weg")
    _ = L.save(p1, server: srv, now: t0, in: d, platform: true); L.clear(in: d)
    check(L.peek(in: d, now: t0, server: srv, platform: true) == nil, "clear (Abmelden)")
    // Der Entwurf traegt nur Text/Adresse/Titel - keinen Schluessel
    _ = L.save(p1, server: srv, now: t0, in: d, platform: true)
    let raw = String(data: d.data(forKey: L.draftKey) ?? Data(), encoding: .utf8) ?? ""
    check(!raw.contains("hkd_") && !raw.contains("hk_") && !raw.contains("token"), "kein Schluessel im Entwurf")

    // --- nie macOS ---------------------------------------------------------------------------------------------------
    #if os(macOS)
    check(!L.platformOK, "macOS: Plattform aus")
    let m = fresh("mac")
    check(!L.save(p1, server: srv, now: t0, in: m), "macOS: kein Entwurf")
    check(m.data(forKey: L.draftKey) == nil, "macOS: nichts in der App-Gruppe")
    m.set(true, forKey: L.jobsKey)
    check(!L.optionShown(in: m), "macOS: Option nie, auch wenn der Schalter gesetzt ist")
    _ = L.save(p1, server: srv, now: t0, in: m, platform: true)
    check(L.peek(in: m, now: t0, server: srv) == nil && m.data(forKey: L.draftKey) == nil, "macOS: liegengebliebener Entwurf wird geloescht")
    #endif

    // --- Schalter der App fuer die Erweiterung -----------------------------------------------------------------------
    let s = fresh("sw")
    check(!L.optionShown(in: s, platform: true), "ohne Schalter keine Option")
    L.setJobs(true, name: "Marin", in: s)
    check(L.optionShown(in: s, platform: true) && s.string(forKey: L.nameKey) == "Marin", "Schalter an + Name")
    L.setJobs(false, name: "Marin", in: s)
    check(!L.optionShown(in: s, platform: true) && s.string(forKey: L.nameKey) == nil, "Schalter aus, Name weg")
    check(L.jobsAllowed(feature: true, jobsOn: true, consentOK: true, caps: ["ai", "ai_act"]), "alles da: Option")
    check(!L.jobsAllowed(feature: false, jobsOn: true, consentOK: true, caps: ["ai", "ai_act"]), "ohne aishare1: nie")
    check(!L.jobsAllowed(feature: true, jobsOn: false, consentOK: true, caps: ["ai", "ai_act"]), "ohne Auftraege: nie")
    check(!L.jobsAllowed(feature: true, jobsOn: true, consentOK: false, caps: ["ai", "ai_act"]), "ohne Einwilligung: nie")
    check(!L.jobsAllowed(feature: true, jobsOn: true, consentOK: true, caps: ["ai"]), "ohne ai_act (RT-17): nie")
    check(!L.jobsAllowed(feature: true, jobsOn: true, consentOK: true, caps: []), "aelterer Server ohne caps: nie")
    let full = #"""
    {"enabled":true,"caps":["ai","ai_act"],"style":{"label":"Marin · KI"},"jobs":true,"level":"plans","consent":{"state":"granted","host":"llm.example"},
     "intro":{"version":"k19-1","host":"llm.example","lines":["Ich bin Marin."],"details":[],"bar":""},
     "share_notice":"Was du teilst, liest die KI auf llm.example (im Internet)."}
    """#
    let st = SagaStatus(data: data(full))!
    check(st.shareJobs(features: [L.feature]), "Status mit ai_act + aishare1: Option")
    check(!st.shareJobs(features: []), "Status ohne aishare1: keine Option")
    check(!SagaStatus(data: data(full.replacingOccurrences(of: #""ai","ai_act""#, with: #""ai""#)))!.shareJobs(features: [L.feature]), "eingeschraenkte Gruppe: keine Option")
    check(!SagaStatus(data: data(full.replacingOccurrences(of: #""level":"plans""#, with: #""level":"answers""#)))!.shareJobs(features: [L.feature]), "Stufe answers: keine Option")
    check(!SagaStatus(data: data(full.replacingOccurrences(of: #""state":"granted""#, with: #""state":"withdrawn""#)))!.shareJobs(features: [L.feature]), "Einwilligung zurueckgezogen: keine Option")
    check(st.shareNotice == "Was du teilst, liest die KI auf llm.example (im Internet).", "share_notice aus dem Status")
    check(SagaStatus(data: data(#"{"enabled":true}"#))?.shareNotice == nil, "aelterer Server: kein Hinweis")

    // --- giveBody(shared:) -------------------------------------------------------------------------------------------
    let gb = SagaLogic.giveBody(text: "  Trag das als Termin ein ", shared: p1)
    check(gb["source"] as? String == "share", "source share")
    check(gb["text"] as? String == "Trag das als Termin ein", "text = nur die eigenen Worte")
    let sh = gb["shared"] as? [String: Any] ?? [:]
    check(sh["text"] as? String == p1.text && sh["url"] as? String == p1.url && sh["title"] as? String == p1.title && sh["from"] as? String == "app",
          "shared {text, url, title, from: app}")
    let empty = SagaLogic.giveBody(text: "", shared: p2)
    check(empty["text"] as? String == "" && (empty["text"] as? String)?.contains("Grillkohle") == false, "ohne eigene Worte: text leer (Systembitte), nie der geteilte Text")
    check((empty["shared"] as? [String: Any])?["url"] == nil && (empty["shared"] as? [String: Any])?["title"] == nil, "leere Felder weggelassen")
    let plain = SagaLogic.giveBody(text: "Mach was", note: 3)
    check(plain["source"] as? String == "saga" && plain["shared"] == nil, "ohne Teilen unveraendert")
    let withChoice = SagaLogic.giveBody(text: "", choice: "Als Aufgabe", shared: p2)
    check(withChoice["choice"] as? String == "Als Aufgabe" && withChoice["shared"] != nil, "Rueckfrage-Wahl: shared erneut mit")
    for (k, v) in gb where k != "shared" { check(!(String(describing: v)).contains("Elternabend"), "geteilter Text nie ausserhalb von shared (\(k))") }

    // --- welcher Inhalt geht mit -------------------------------------------------------------------------------------
    check(L.sharedFor(recipe: true, choice: nil, askShared: p1, line: p2) == nil, "nie mit einem Rezept")
    check(L.sharedFor(recipe: false, choice: "x", askShared: p1, line: p2) == p1, "Rueckfrage: Inhalt der Rueckfrage")
    check(L.sharedFor(recipe: false, choice: "x", askShared: nil, line: p2) == nil, "Rueckfrage nach Neustart: kein Inhalt (nicht die Zeile)")
    check(L.sharedFor(recipe: false, choice: nil, askShared: nil, line: p2) == p2, "neuer Auftrag: Zeile 'Geteilt'")
    check(L.line(p2, asJob: true, allowed: true) == p2, "Zeile mit Als Auftrag + Freigabe")
    check(L.line(p2, asJob: false, allowed: true) == nil, "Zeile nur mit Als Auftrag")
    check(L.line(p2, asJob: true, allowed: false) == nil, "nach Entzug (Gespraech nicht offen): Zeile weg, nichts mitgeschickt")
    check(L.canSend("", shared: p2) && !L.canSend("  ", shared: nil) && L.canSend("x", shared: nil), "mit geteiltem Inhalt auch ohne eigene Worte")
    check(!L.canSend(String(repeating: "a", count: 1001), shared: p2), "eigene Worte hoechstens 1000")
    check(L.isShareLink(URL(string: "saga://share")!) && L.isShareLink(URL(string: "hlidskjalf://share")!) && !L.isShareLink(URL(string: "saga://saga")!), "saga://share")

    // --- Grenzen, Steuerzeichen, Adresse -----------------------------------------------------------------------------
    let lang = L.payload(text: String(repeating: "x", count: 30_000), url: nil, title: String(repeating: "t", count: 300))!
    check(lang.text.utf16.count == 20_000 && lang.title?.utf16.count == 200, "Text 20 000, Titel 200")
    let emo = L.payload(text: String(repeating: "x", count: 19_999) + "😀😀", url: nil, title: "")!
    check(emo.text.utf16.count <= 20_000 && emo.text.hasSuffix("x"), "Emoji an der Grenze nicht zerschnitten")
    let fam = L.payload(text: "👨‍👩‍👧", url: nil, title: "")!
    check(fam.text == "👨‍👩‍👧", "zusammengesetzte Zeichen bleiben")
    check(L.payload(text: "a\u{0}\n\u{7}b\tc", url: nil, title: "")?.text == "a\nb\tc", "Steuerzeichen weg, Umbruch/Tab bleiben")
    check(L.payload(text: "", url: nil, title: "Zeile1\nZeile2")?.title == nil, "nur Titel -> kein Inhalt")
    check(L.payload(text: "x", url: nil, title: "Zeile1\nZeile2")?.title == "Zeile1 Zeile2", "Titel einzeilig")
    check(L.payload(text: "  \n ", url: nil, title: "") == nil, "nur Leerraum -> keine Option")
    let ftp = L.payload(text: "", url: "ftp://files.example/a", title: "")!
    check(ftp.url == nil && ftp.text == "ftp://files.example/a", "ungueltige Adresse geht als Text (nichts verloren)")
    check(L.urlOk("https://schule.example/x") && L.urlOk("HTTP://a.example"), "http(s) ok")
    for bad in ["ftp://files.example/a", "javascript:alert(1)", "https://a.example/x y", "https:///x", "https://a.example/<b>", "https://a.example/\u{7}",
                "https://" + String(repeating: "a", count: 2000), ""] {
        check(!L.urlOk(bad), "Adresse abgelehnt: \(bad.prefix(40))")
    }
    check(L.host("https://www.schule.example:8443/termine?id=7") == "schule.example", "Host ohne www./Port")
    check(L.host("https://user:pw@WWW.Schule.Example:8443/x") == "schule.example", "Host ohne Zugangsdaten, klein")
    check(L.host("https://www.example/x") == "www.example", "www. nur mit zwei Punkten weg (wie der Server)")
    check(L.host("http://a.example#x") == "a.example" && L.host("https://A.Example./x") == "a.example", "Fragment, Punkt am Ende")
    check(L.host("http://[::1]:8080/") == "::1", "IPv6")
    check(L.host("https:///x") == nil && L.host("") == nil && L.host(nil) == nil, "ohne Host nil")

    // --- Vektoren share_contract.json --------------------------------------------------------------------------------
    let u = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("vectors/share_contract.json")
    guard let rawV = try? Data(contentsOf: u), let v = (try? JSONSerialization.jsonObject(with: rawV)) as? [String: Any] else {
        check(false, "share_contract.json lesbar"); return
    }
    check(v["format"] as? String == "saga-share/1" && v["feature"] as? String == L.feature, "Format saga-share/1, Merkmal aishare1")
    let lim = v["limits"] as? [String: Any] ?? [:]
    check(ShareJSON.int(lim["text"]) == L.textMax && ShareJSON.int(lim["url"]) == L.urlMax && ShareJSON.int(lim["title"]) == L.titleMax, "Grenzen wie der Server")
    func expand(_ x: Any) -> Any {
        if let o = x as? [String: Any] {
            if let r = o["$repeat"] as? String, let n = ShareJSON.int(o["n"]) { return (o["prefix"] as? String ?? "") + String(repeating: r, count: n) }
            return o.mapValues(expand)
        }
        if let a = x as? [Any] { return a.map(expand) }
        return x
    }
    var n = 0
    for r in v["requests"] as? [[String: Any]] ?? [] {
        let name = r["name"] as? String ?? "?"
        let body = expand(r["body"] ?? [:]) as? [String: Any] ?? [:]
        let shv = body["shared"] as? [String: Any]
        let allStr = shv.map { $0.values.allSatisfy { $0 is String } } ?? false
        let p = allStr ? L.payload(text: shv?["text"] as? String ?? "", url: shv?["url"] as? String, title: shv?["title"] as? String ?? "") : nil
        let status = ShareJSON.int(r["status"]) ?? 0
        if status == 200, let want = (r["expect"] as? [String: Any])?["origin"] as? [String: Any], let url = shv?["url"] as? String {
            check(L.host(url) == ShareJSON.str(want["host"]), "\(name): Host wie der Server")
        }
        if r["code"] as? String == "share_empty" { check(p == nil, "\(name): ohne Inhalt keine Option"); n += 1; continue }
        guard let p else { continue }
        let out = SagaLogic.giveBody(text: body["text"] as? String ?? "", shared: p)
        let o = out["shared"] as? [String: Any] ?? [:]
        check(out["source"] as? String == "share" && out["recipe"] == nil, "\(name): source share, nie mit recipe")
        check(o["from"] as? String == "app", "\(name): from app")
        check(((o["text"] as? String)?.utf16.count ?? 0) <= 20_000 && ((o["title"] as? String)?.utf16.count ?? 0) <= 200, "\(name): Grenzen")
        if let x = o["url"] as? String { check(L.urlOk(x), "\(name): url gueltig") }
        check(!((o["text"] as? String) ?? "").isEmpty || o["url"] != nil, "\(name): Inhalt Pflicht")
        n += 1
    }
    check(n >= 15, "Vektoren gelesen (\(n))")

    // --- Herkunft, eigener Zug ---------------------------------------------------------------------------------------
    let ex = v["examples"] as? [String: Any] ?? [:]
    let de = (v["texts"] as? [String: Any])?["de"] as? [String: Any] ?? [:]
    let mit = SagaPlanOrigin(ex["plan_origin_mit_warnung"])
    check(mit?.shown == true && mit?.text == "Angeregt durch: geteilten Inhalt (verein.example)", "Herkunft mit Host")
    check(mit?.warnShown == de["warn_text"] as? String, "Warnung = warn_text vom Server")
    let ohne = SagaPlanOrigin(ex["plan_origin_ohne_warnung"])
    check(ohne?.shown == true && ohne?.warnShown == nil, "ohne warn keine Warnzeile")
    let nohost = SagaPlanOrigin(ex["plan_origin_ohne_host"])
    check(nohost?.host == nil && nohost?.from == "web" && nohost?.text == de["origin"] as? String, "ohne Host")
    check(SagaPlanOrigin(ex["plan_ohne_teilen"]) == nil, "Plan ohne Teilen: origin null")
    check(SagaPlanOrigin(text: "x", warn: true, warnText: " ").warnShown == nil, "warn ohne Text: keine Zeile")
    var planD: [String: Any] = ["job": 5, "plan_v": 1, "plan_hash": String(repeating: "a", count: 64), "title": "Elternabend",
                                "steps": [["n": 1, "tool": "aufgabe", "klass": "own", "text": "Aufgabe anlegen"]],
                                "read": ex["plan_read"] ?? [], "read_text": "Ich lese dafür: den geteilten Inhalt (schule.example)"]
    planD["origin"] = ex["plan_origin_mit_warnung"]
    let plan = SagaPlan(planD)
    check(plan?.origin == mit && plan?.readLabels == ["den geteilten Inhalt (schule.example)"], "Ereignis plan mit origin + read share")
    planD["origin"] = NSNull()
    check(SagaPlan(planD)?.origin == nil, "origin null")
    planD.removeValue(forKey: "origin")
    check(SagaPlan(planD)?.origin == nil, "aelterer Server ohne Feld")
    let me = SagaTurn(ex["turn_me"])
    check(me?.shareMark == ShareMark(host: "schule.example", title: nil) && me.map { L.labelKind($0.shareMark!) } == .host, "eigener Zug: nur Host")
    let me2 = SagaTurn(ex["turn_me_ohne_worte"])
    check(me2?.shareMark == ShareMark(host: nil, title: nil) && me2?.text == de["request"] as? String, "ohne eigene Worte: Systembitte wie Vektor")
    check(me2.map { L.labelKind($0.shareMark!) } == .plain, "ohne Host: 'Geteilter Inhalt'")
    check(SagaTurn(["role": "me", "zug": "job", "text": "x"])?.shareMark == nil, "ohne fields.shared keine Marke")
    check(SagaTurn(["role": "ai", "zug": "plan", "text": "x", "fields": ["shared": ["host": "a.example"]]])?.shareMark == nil, "KI-Zug nie")
    check(L.labelKind(L.mark(SharePayload(text: "x", url: nil, title: "Termine 2026"))) == .title, "lokal: Titel nur ohne Host")
    check(L.labelKind(L.mark(p1)) == .host && L.mark(p1).host == "schule.example", "lokal: Host vor Titel")
    let act = ex["act_approve"] as? [String: Any]
    check(ShareJSON.int(act?["status"]) == 409 && (act?["body"] as? [String: Any])?["code"] as? String == "open", "act approve -> 409 open (nie aus der App)")
}
