import Foundation

// 2270 (2.6, K-C, jobpush1): Mitteilungen der Kollegin zu Auftraegen gegen die Server-Vektoren Tests/vectors/push_kollegin.json
// (Kopie von tests/vectors/push_kollegin.json, tools/kollegin_push_vectors.py) - Entschluesseln mit den App-Schluesseln je
// Weg, Felder, Kategorie, Knoepfe nach K15, Klartext-Wege ohne Kennung, Aktualisierung "removed", App-Reaktion je Code,
// Deep-Links und die Knopf-Texte in Localizable.xcstrings (de/en/es/ru/th wie texts[lang]).

private func jobVectors() -> [String: Any]? {
    let u = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("vectors/push_kollegin.json")
    guard let d = try? Data(contentsOf: u) else { return nil }
    return try? JSONSerialization.jsonObject(with: d) as? [String: Any]
}
private func jsonEqual(_ a: Any?, _ b: Any?) -> Bool {
    guard let a, let b, let x = try? JSONSerialization.data(withJSONObject: a, options: [.sortedKeys]),
          let y = try? JSONSerialization.data(withJSONObject: b, options: [.sortedKeys]) else { return false }
    return x == y
}

func pushJobTests() {
    guard let v = jobVectors() else { check(false, "push_kollegin.json lesbar"); return }
    check(v["format"] as? String == "saga-push-kollegin/1", "Format saga-push-kollegin/1")
    let keys = v["keys"] as? [String: String] ?? [:]
    guard let key = PushE2EKey(test: (keys["ua_priv"] ?? "") + "." + (keys["auth"] ?? "")) else { check(false, "Schluessel der Vektoren"); return }
    check(PushE2ELogic.b64u(key.p256dh) == keys["ua_pub"] && key.kid == keys["k"], "Schluessel = push_e2e.json (ua_pub, k)")
    let L = PushJobLogic.self
    let params = v["params"] as? [String: Any] ?? [:]

    // Kategorien und Aktionen wie im Vertrag
    let cats = (v["categories"] as? [[String: Any]] ?? []).map { ($0["id"] as? String ?? "", $0["buttons"] as? [String] ?? []) }
    check(cats.count == 5 && cats.map(\.0) == L.categories.map(\.id) && cats.map(\.1) == L.categories.map(\.buttons), "5 Kategorien mit Knoepfen in Reihenfolge")
    let acts = v["actions"] as? [[String: Any]] ?? []
    check(acts.map { $0["a"] as? String ?? "" } == L.actions, "Aktionen approve, undo, later, quiet, open")
    check(acts.allSatisfy { ($0["auth"] as? Bool ?? false) == L.authActions.contains($0["a"] as? String ?? "") }, "auth nur approve/undo")
    check(Set((params["kinds"] as? [String: String] ?? [:]).values) == L.kinds, "Arten job.needs_you/done/failed")
    check(params["update_kind"] as? String == L.updateKind && params["cat"] as? String == L.cat, "job.changed, Bereich jobs")
    for r in v["reserved_actions"] as? [String] ?? [] { check(L.action(fromID: L.actionID(r)) == nil, "reserviert ohne Knopf: \(r)") }
    check(L.action(fromID: "saga.job.undo") == "undo" && L.action(fromID: "SAGA_CALL_ANSWER") == nil, "Aktions-Kennung")

    // iOS-Kategorien: alle Vertrags-Kennungen, eindeutig
    let ios = L.iosCategories()
    check(Set(ios.map(\.id)).count == ios.count, "iOS-Kategorien eindeutig")
    check(Set(L.categories.map(\.id)).isSubset(of: Set(ios.map(\.id))), "volle Kategorien angemeldet")
    check(ios.allSatisfy { c in c.buttons.contains("open") || !L.buttons(of: c.id.components(separatedBy: "~")[0]).contains("open") }, "open bleibt immer")
    check(!ios.contains { $0.id == "SAGA_CALL" }, "Anruf-Kategorie nicht ueberschrieben")

    // Faelle: entschluesseln, Felder, Anzeige
    let cases = v["cases"] as? [[String: Any]] ?? []
    check(cases.count >= 17, "17 Faelle")
    for c in cases {
        let name = c["name"] as? String ?? "?"
        let expect = c["expect"] as? [String: Any] ?? [:]
        var inner: [String: Any]?
        if let b = (c["body"] as? String).flatMap(PushE2ELogic.b64uDecode) {
            let plain = PushE2ELogic.decrypt(b, key: key)
            check(plain != nil, "\(name): entschluesselt")
            check(plain?.count == c["plain_len"] as? Int, "\(name): Laenge ohne Auffuellen")
            inner = plain.flatMap(PushE2ELogic.inner)
            if let m = c["msg"] { check(jsonEqual(inner, m), "\(name): innere Felder = msg") }
            if let p = c["plain"] as? String, let pl = plain { check(String(data: pl, encoding: .utf8) == p, "\(name): Klartext exakt") }
            // APNs: Umschlag wie im Vertrag, Kennung k
            if let tr = c["transport"] as? [String: Any], let ab = tr["apns_body"] as? [String: Any] {
                check(PushE2ELogic.envelope(ab)?.k == keys["k"], "\(name): APNs-Umschlag mit k")
                let aps = ab["aps"] as? [String: Any] ?? [:]
                check(aps["category"] == nil, "\(name): keine category im aps (setzt die NSE)")
                let open = PushE2ELogic.envelope(ab).flatMap { PushE2ELogic.decrypt($0.c, key: key) }.flatMap(PushE2ELogic.inner)
                check(jsonEqual(open, c["msg"]), "\(name): Umschlag -> innere Felder")
            }
        } else if let r = c["request"] as? [String: Any], c["way"] as? String == "unifiedpush-plain" {
            inner = r
        } else if let tr = c["transport"] as? [String: Any], let ab = tr["apns_body"] as? [String: Any] {
            var i = ab; let aps = ab["aps"] as? [String: Any] ?? [:]; i.removeValue(forKey: "aps")
            let al = aps["alert"] as? [String: Any] ?? [:]
            i["title"] = al["title"]; i["body"] = al["body"]; i["thread"] = aps["thread-id"]
            inner = i
        }
        guard let inner else { if c["way"] as? String != "ntfy" { check(false, "\(name): Inhalt") }; continue }
        if let rt = expect["remove_tag"] as? String {
            check(L.removeTag(inner) == rt, "\(name): update removed -> tag \(rt)")
            check(L.shown(inner) == nil, "\(name): keine Anzeige")
            continue
        }
        guard let s = L.shown(inner) else { check(false, "\(name): Anzeige"); continue }
        check(s.category == expect["category"] as? String, "\(name): Kategorie \(s.category)")
        let tx = expect["text"] as? [String: String] ?? [:]
        check(s.title == tx["title"] && s.body == tx["body"], "\(name): Text \(s.title) / \(s.body)")
        check(s.thread == expect["thread_id"] as? String && s.tag == expect["notification_tag"] as? String, "\(name): Faden/tag")
        check(s.buttons == expect["buttons_unlocked_phone"] as? [String], "\(name): Knoepfe \(s.buttons)")
        check(s.deeplink == expect["deeplink"] as? String, "\(name): Deep-Link")
        check(ios.contains { $0.id == s.iosCategory && $0.buttons == s.buttons }, "\(name): iOS-Kategorie angemeldet")
        check(s.sound == !((c["input"] as? [String: Any])?["silent"] as? Bool ?? false), "\(name): Ton nach silent")
        let flat = L.flatInfo(inner)
        check(flat["detail"] == nil && flat["actions"] == nil && flat.values.allSatisfy { $0 is String || $0 is NSNumber }, "\(name): userInfo flach")
        if (params["plain_ways"] as? [String] ?? []).contains(c["way"] as? String ?? "") {
            check(s.job == nil && !s.detail && L.tokens(inner).isEmpty && s.tag == "saga-job", "\(name): Klartext ohne Kennung/Inhalt/Token")
        }
        // Vorschau "nie" -> neutral, ohne approve/undo; Geraet ohne Code -> ohne approve/undo
        if let hidden = L.shown(inner, previews: false) {
            check(hidden.title == inner["title"] as? String && !hidden.buttons.contains("undo") && !hidden.buttons.contains("approve"), "\(name): Vorschau nie -> neutral")
        }
        if let noauth = L.shown(inner, deviceAuth: false) { check(!noauth.buttons.contains { L.authActions.contains($0) }, "\(name): ohne Code ohne approve/undo") }
    }

    // Groesse (Thai): Titel gekuerzt, detail.f entfallen
    if let th = cases.first(where: { $0["name"] as? String == "gross_th_webpush" }), let m = th["msg"] as? [String: Any] {
        check((m["detail"] as? [String: Any])?["f"] == nil && (th["dropped"] as? [String]) == ["detail.f"], "Thai: detail.f entfallen")
    }

    // K15-Matrix (100 Zeilen)
    let k15 = v["k15"] as? [[String: Any]] ?? []
    check(k15.count == 100, "K15: 100 Zeilen")
    var bad = 0
    for r in k15 {
        let b = L.k15(category: r["cat"] as? String ?? "", device: r["device"] as? String ?? "", content: r["content"] as? String == "on")
        let want = (r["buttons"] as? [[String: Any]] ?? []).map { ($0["a"] as? String ?? "", $0["auth"] as? Bool ?? false) }
        let ok = b.map(\.a) == want.map(\.0) && b.map(\.auth) == want.map(\.1)
            && L.direct(b, locked: r["locked"] as? Bool ?? false) == r["direct"] as? [String]
        if !ok { bad += 1; print("  K15: \(r)") }
    }
    check(bad == 0, "K15-Matrix: \(bad) abweichend")

    // App-Reaktion je Code
    let tok = v["tokens"] as? [String: Any] ?? [:]
    let client = tok["client"] as? [String: String] ?? [:]
    for (code, want) in client {
        check(L.outcome(status: code == "net" ? nil : Int(code)).rawValue == want, "act \(code) -> \(want)")
    }
    check(L.retry(status: nil, attempt: 0) && !L.retry(status: nil, attempt: 1) && !L.retry(status: 409, attempt: 0), "Netzfehler: einmal wiederholen")
    for tc in tok["cases"] as? [[String: Any]] ?? [] {
        let st = tc["status"] as? Int ?? 0
        let o = L.outcome(status: st)
        check(o.rawValue == client[String(st)], "act-Fall \(tc["name"] ?? "") \(st) -> \(o.rawValue)")
    }
    // Koerper fuer act aus der gezeigten Mitteilung (done_undo_webpush): {action, atok, pv}
    if let c = cases.first(where: { $0["name"] as? String == "done_undo_webpush" }), let m = c["msg"] as? [String: Any] {
        let flat = L.flatInfo(m) as [AnyHashable: Any]
        check(jsonEqual(L.actBody(action: "undo", info: flat), tok["request"]), "act-Koerper = tokens.request")
        check(L.actBody(action: "approve", info: flat) == nil && L.actBody(action: "open", info: flat) == nil, "ohne Token bzw. open: kein act")
    }

    // Deep-Links
    for d in v["deeplinks"] as? [[String: String]] ?? [] {
        guard let u = URL(string: d["link"] ?? "") else { continue }
        let arg = u.pathComponents.first { $0 != "/" }
        if u.host == "job" || u.host == "jobs" { check(L.linkRoute(host: u.host, arg: arg) == d["route"], "Deep-Link \(d["link"] ?? "")") }
        if let r = d["route"], r.contains("tab=jobs") {
            let t = L.jobTarget(url: r)
            check(t?.jobs == true && t?.job == (u.host == "job" ? Int(arg ?? "") : nil), "Route \(r) -> Auftrag")
        }
    }
    check(L.linkRoute(host: "job", arg: "4x2") == "/saga?tab=jobs" && L.jobTarget(url: "/saga?tab=jobs&job=1;2")?.job == nil, "Kennung nur Ziffern")
    check(L.jobTarget(url: "/notes#4") == nil && L.jobTarget(url: "/saga") == nil, "andere Adressen kein Auftrag")

    // Abo-caps (iOS): widgets, detail, actions - nie update
    check(L.subscriptionCaps(serverCaps: params["sub_caps"] as? [String] ?? []) == ["widgets", "detail", "actions"], "Abo-caps iOS")
    check(L.subscriptionCaps(serverCaps: ["update", "widgets"]) == ["widgets"] && L.subscriptionCaps(serverCaps: []).isEmpty, "aelterer Server: wie bisher")

    // Ruhe (notify_quiet_until)
    check(L.quietActive(until: 1795759200, now: 1795725000) && !L.quietActive(until: nil, now: 1) && !L.quietActive(until: 5, now: 5), "Ruhe aktiv")

    // Knopf-Texte in Localizable.xcstrings = texts[lang] (Vertrag)
    let src = ProcessInfo.processInfo.environment["SAGA_APP_SRC"] ?? ""
    let xu = URL(fileURLWithPath: src).deletingLastPathComponent().appendingPathComponent("Shared/Localizable.xcstrings")
    if let xd = try? Data(contentsOf: xu), let xo = try? JSONSerialization.jsonObject(with: xd) as? [String: Any],
       let strings = xo["strings"] as? [String: Any], let texts = v["texts"] as? [String: [String: Any]] {
        func val(_ k: String, _ l: String) -> String? {
            (((strings[k] as? [String: Any])?["localizations"] as? [String: Any])?[l] as? [String: Any]).flatMap { ($0["stringUnit"] as? [String: Any])?["value"] as? String }
        }
        for (l, t) in texts {
            let b = t["buttons"] as? [String: String] ?? [:]
            for a in L.actions { check(val(JobTexts.key(a), l) == b[a], "Knopf \(a) [\(l)] = \(b[a] ?? "")") }
            check(val(JobTexts.hintKey, l) == t["hint"] as? String, "Hinweis [\(l)]")
        }
    } else { check(false, "Localizable.xcstrings lesbar (\(xu.path))") }
}
