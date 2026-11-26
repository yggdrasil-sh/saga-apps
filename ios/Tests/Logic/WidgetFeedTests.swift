import Foundation

// Bugs-DB 2185: Homescreen-Widgets (Shared/WidgetFeedLogic.swift) - Abstand, "Stand: ...", Server-Abfrage, letzter Stand
func widgetFeedTests() {
    typealias F = WidgetFeed
    let now = Date(timeIntervalSince1970: 1_800_000_000)

    // MARK: Abstand und Eintraege
    check(F.nextReload(ok: true, now: now).timeIntervalSince(now) == 15 * 60, "Feed: 15 min nach Erfolg (frueher 30 min / 2 h)")
    check(F.nextReload(ok: false, now: now).timeIntervalSince(now) == 10 * 60, "Feed: 10 min nach Fehler")
    let ok = F.entryPlan(ok: true, hasCached: false, fetchedAt: nil, now: now)
    check(ok.count == 2 && ok[0].date == now && !ok[0].stamp && ok[1].stamp, "Feed: Erfolg -> jetzt frisch, spaeter mit Stand")
    check(ok[1].date.timeIntervalSince(now) == F.staleAfter && F.staleAfter > F.okInterval * 2,
          "Feed: Stand-Eintrag erst nach mehreren verpassten Abrufen")
    let failC = F.entryPlan(ok: false, hasCached: true, fetchedAt: now.addingTimeInterval(-3600), now: now)
    check(failC.count == 1 && failC[0].stamp, "Feed: Fehler mit letztem Stand -> sofort mit Stand (nicht stumm alt)")
    let fail = F.entryPlan(ok: false, hasCached: false, fetchedAt: nil, now: now)
    check(fail.count == 1 && !fail[0].stamp, "Feed: Fehler ohne gemerkten Stand -> nur die Meldung")

    // MARK: Stand-Text
    var cal = Calendar(identifier: .gregorian); cal.timeZone = TimeZone(identifier: "Europe/Berlin")!
    let de = Locale(identifier: "de_DE")
    let t = F.stampTime(now, now: now.addingTimeInterval(60), calendar: cal, locale: de)
    check(t.contains(":") && !t.contains("."), "Feed: heute nur Uhrzeit (\(t))")
    let y = F.stampTime(now.addingTimeInterval(-86400), now: now, calendar: cal, locale: de)
    check(y.contains(":") && y.contains("."), "Feed: anderer Tag mit Datum (\(y))")
    check(F.stamp(now, now: now).hasPrefix("Stand: "), "Feed: Text ueber vorhandenen Schluessel \"Stand: %@\"")

    // MARK: Abfrage an /api/app/rows (Server rechnet die Ansicht)
    let p = F.rowsPath(db: 42, view: "offen", tz: "Europe/Berlin")
    check(p.hasPrefix("api/app/rows/42?"), "Feed: Pfad der Datenbank (\(p))")
    let items = URLComponents(string: "https://x/" + p)?.queryItems ?? []
    func q(_ n: String) -> String? { items.first { $0.name == n }?.value }
    check(q("view") == "offen" && q("limit") == "10" && q("tz") == "Europe/Berlin" && q("group") == "",
          "Feed: Ansicht, Grenze 10, Zeitzone, ohne Gruppierung")
    check(URLComponents(string: "https://x/" + F.rowsPath(db: 1, view: nil, tz: "UTC"))?.queryItems?.first { $0.name == "view" }?.value == "",
          "Feed: ohne Ansicht -> view leer (Server nimmt die erste, wie lokal)")
    let gmt = F.rowsPath(db: 1, view: "a b&c", tz: "Etc/GMT+1")
    let gi = URLComponents(string: "https://x/" + gmt)?.queryItems ?? []
    check(gi.first { $0.name == "tz" }?.value == "Etc/GMT+1" && gi.first { $0.name == "view" }?.value == "a b&c",
          "Feed: + und & werden kodiert (\(gmt))")
    check(F.serverMatched(Data(#"{"schema":{},"rows":[],"matched":17,"total":2200}"#.utf8)) == 17, "Feed: matched vom Server")
    check(F.serverMatched(Data(#"{"schema":{},"rows":[]}"#.utf8)) == nil, "Feed: aelterer Server ohne matched -> lokal filtern")
    check(F.serverMatched(Data(#"{"matched":true}"#.utf8)) == nil && F.serverMatched(Data("x".utf8)) == nil, "Feed: kaputtes matched")
    check(F.total(matched: 17, shown: 10) == 17 && F.total(matched: nil, shown: 23) == 23 && F.total(matched: 3, shown: 5) == 5,
          "Feed: Gesamtzahl fuer + N weitere")

    // MARK: Letzter guter Stand
    let k1 = F.snapshotKey(widget: "page", target: "v:7:offen", server: "https://a.example", token: "hk_1")
    let k2 = F.snapshotKey(widget: "page", target: "v:7:offen", server: "https://a.example", token: "hk_2")
    check(k1 != nil && k1 != k2 && !(k1 ?? "").contains("hk_1"), "Feed: Schluessel je Konto, ohne den App-Schluessel selbst")
    check(F.snapshotKey(widget: "page", target: "x", server: "https://a.example", token: "") == nil, "Feed: ohne Schluessel nichts merken")
    check(F.fnv("abc") == F.fnv("abc") && F.fnv("abc") != F.fnv("abd"), "Feed: Pruefsumme stabil")
    check(F.imageFile(key: "k", which: "cover") != F.imageFile(key: "k", which: "icon"), "Feed: Titelbild und Symbol getrennt")

    let suite = "saga.widgetfeed.tests.\(UUID().uuidString)"
    let d = UserDefaults(suiteName: suite)!
    struct S: Codable, Equatable { var n: Int }
    WidgetSnapshot.save(S(n: 3), at: now, key: k1, to: d)
    let back = WidgetSnapshot<S>.load(k1, from: d)
    check(back?.value == S(n: 3) && back?.at == now, "Feed: Stand gemerkt und gelesen")
    check(WidgetSnapshot<S>.load(k2, from: d) == nil, "Feed: anderes Konto sieht den Stand nicht")
    d.set(1, forKey: "w.tasks")
    F.clearSnapshots(d, cardsDir: nil)
    check(WidgetSnapshot<S>.load(k1, from: d) == nil && d.object(forKey: "w.tasks") != nil, "Feed: Abmelden loescht nur die Staende")
    d.removePersistentDomain(forName: suite)
    check(F.dropSnapshots(oldToken: "hk_1", newToken: "") && F.dropSnapshots(oldToken: "hk_1", newToken: "hk_2")
          && F.dropSnapshots(oldToken: "", newToken: "hk_2") && !F.dropSnapshots(oldToken: "hk_1", newToken: "hk_1"),
          "Feed: Abmelden und Kontowechsel verwerfen die Staende, gleicher Schluessel nicht")

    // MARK: Bild-Abruf der Seiten-Karte
    let img = Data([1, 2, 3])
    check(WidgetImage.from(status: 200, data: img) == .image(img), "Feed: Bild")
    check(WidgetImage.from(status: 404, data: Data()) == .none, "Feed: kein Bild (404)")
    check(WidgetImage.from(status: nil, data: nil) == .failed && WidgetImage.from(status: 500, data: img) == .failed
          && WidgetImage.from(status: 401, data: nil) == .failed, "Feed: Netz-/Serverfehler")
    check(WidgetImage.failed.resolved(cached: img) == img, "Feed: Fehler -> gemerktes Titelbild statt keins")
    check(WidgetImage.none.resolved(cached: img) == nil, "Feed: Titelbild entfernt -> keins (nicht das alte)")
    check(WidgetImage.image(Data([9])).resolved(cached: img) == Data([9]), "Feed: neues Titelbild gewinnt")
}

// Bugs-DB 2188: Widget-Push (stiller Push ohne Inhalt), Meldung der Widgets, Titelbild nach Versionsschluessel
func widgetPushTests() {
    typealias W = WidgetPushLogic
    let now = Date(timeIntervalSince1970: 1_800_000_000)
    check(W.isWidgetPush(["kind": "widgets.update", "update": true]) && !W.isWidgetPush(["kind": "chat.message"]) && !W.isWidgetPush([:]),
          "WPush: nur kind widgets.update laedt Widgets neu")
    check(W.subscriptionCaps(serverCaps: ["update", "widgets"]) == ["widgets"] && W.subscriptionCaps(serverCaps: ["update"]).isEmpty,
          "WPush: caps widgets nur, wenn der Server sie anbietet (nie update)")
    let sub = PushLogic.subscription(token: "ab", env: "production", name: "iPhone", payload: 2, caps: ["widgets"])
    check((sub["caps"] as? [String]) == ["widgets"], "WPush: Abo apns mit caps widgets")
    check(PushLogic.subscription(token: "ab", env: "production", name: "iPhone", type: "apns-voip", payload: 2, caps: ["widgets"])["caps"] == nil,
          "WPush: VoIP-Abo nie mit caps (nie Widget-Push ueber VoIP)")
    check(PushLogic.subscription(token: "ab", env: "production", name: "iPhone", payload: 2)["caps"] == nil, "WPush: ohne Server-Faehigkeit kein Feld caps")
    let st = PushState(data: Data(#"{"server": {"enabled": true, "sub_caps": ["update", "widgets"]}}"#.utf8))
    check(st?.subCaps == ["update", "widgets"], "WPush: PushState liest sub_caps")
    check(PushState(data: Data(#"{"server": {"enabled": true}}"#.utf8))?.subCaps == [], "WPush: aeltere Server ohne sub_caps")

    // Eintraege der Widgets -> Meldung
    check(W.account(server: "", token: "hk_x") == nil && W.account(server: "https://s", token: "") == nil, "WPush: ohne Server/Schluessel nichts merken")
    let a = W.account(server: "https://s.example", token: "hk_1")!, b = W.account(server: "https://s.example", token: "hk_2")!
    check(a != b, "WPush: Konto-Kennung je App-Schluessel")
    let t = now.timeIntervalSince1970
    let e: [String: Double] = [W.regKey(account: a, kind: "pagecard", target: "12"): t - 60, W.regKey(account: a, kind: "page", target: "7"): t - 3600,
                               W.regKey(account: a, kind: "page", target: "12"): t - 10, W.regKey(account: a, kind: "today", target: "today"): t - 5,
                               W.regKey(account: a, kind: "pagecard", target: "99"): t - 3 * 86400,       // zu alt
                               W.regKey(account: b, kind: "page", target: "5"): t,                          // anderes Konto
                               W.regKey(account: a, kind: "page", target: "x"): t, "w.snap.page.1": t]
    let r = W.collect(e, account: a, present: nil, now: now)
    check(r.pages == [7, 12] && r.today, "WPush: Seiten sortiert ohne doppelte, Heute erkannt (\(r.pages))")
    check(Set(r.stale) == [W.regKey(account: a, kind: "pagecard", target: "99"), W.regKey(account: b, kind: "page", target: "5")],
          "WPush: zu alte und fremde Eintraege weg, kaputtes Ziel bleibt nur ungemeldet (\(r.stale))")
    let r2 = W.collect(e, account: a, present: ["pagecard", "lock"], now: now)
    check(r2.pages == [12] && r2.today && r2.stale.contains(W.regKey(account: a, kind: "page", target: "7")),
          "WPush: Widget-Art nicht mehr auf dem Homescreen -> Eintraege weg; Sperrbildschirm zaehlt als Heute")
    let r3 = W.collect(e, account: a, present: [], now: now)
    check(r3.pages.isEmpty && !r3.today, "WPush: keine Widgets mehr -> leere Meldung (Server vergisst das Geraet)")
    let many = Dictionary(uniqueKeysWithValues: (1...100).map { (W.regKey(account: a, kind: "page", target: String($0)), t) })
    check(W.collect(many, account: a, present: nil, now: now).pages.count == W.pagesMax, "WPush: hoechstens 64 Seiten")
    check(W.fingerprint(pages: [7, 12], today: true) != W.fingerprint(pages: [7, 12], today: false)
          && W.fingerprint(pages: [7], today: false) != W.fingerprint(pages: [7, 12], today: false), "WPush: Fingerabdruck aendert sich mit der Meldung")
    let body = W.body(pages: [7, 12], today: true)
    check((body["pages"] as? [Int]) == [7, 12] && (body["today"] as? Bool) == true && body.count == 2, "WPush: Meldung nur Kennungen und Heute")
    let d = UserDefaults(suiteName: "wpush-test-\(UUID().uuidString)")!
    W.record(kind: "pagecard", target: "12", server: "https://s.example", token: "hk_1", now: now, to: d)
    W.record(kind: "pagecard", target: "13", server: "", token: "hk_1", now: now, to: d)
    check(d.double(forKey: W.regKey(account: a, kind: "pagecard", target: "12")) == t
          && d.dictionaryRepresentation().keys.filter { $0.hasPrefix(W.regPrefix) }.count == 1, "WPush: record merkt Ziel und Zeit (ohne Server nichts)")

    // Einzelseite und Titelbild nach Versionsschluessel
    typealias F = WidgetFeed
    check(F.pageGone(status: 404, data: Data(#"{"error": "not found"}"#.utf8)) == true, "Karte: 404 mit error -> Seite weg")
    check(F.pageGone(status: 404, data: Data(#"{"detail": "Not Found"}"#.utf8)) == nil, "Karte: 404 ohne error -> aelterer Server (Liste)")
    check(F.pageGone(status: 200, data: nil) == false, "Karte: 200 -> nicht weg")
    check(F.imagePlan(version: nil, cached: "abc", hasData: true) == .fetch(version: nil), "Karte: ohne cover_v (aelterer Server) immer frisch")
    check(F.imagePlan(version: "", cached: "abc", hasData: true) == .none, "Karte: cover_v leer -> kein Bild, kein Abruf")
    check(F.imagePlan(version: "abc", cached: "abc", hasData: true) == .reuse, "Karte: gleicher Schluessel + Bild gemerkt -> kein Abruf")
    check(F.imagePlan(version: "abc", cached: "abc", hasData: false) == .fetch(version: "abc"), "Karte: Bild fehlt -> laden")
    check(F.imagePlan(version: "def", cached: "abc", hasData: true) == .fetch(version: "def"), "Karte: neuer Schluessel -> neues Bild")
    let p1 = F.imagePath(id: 5, which: "cover", version: "abc"), p2 = F.imagePath(id: 5, which: "cover", version: "def")
    check(p1.hasPrefix("api/app/page-image/5/cover?w=640&v=") && p1 != p2, "Karte: Adresse mit Version (\(p1))")
    check(F.imagePath(id: 5, which: "icon", version: nil) == "api/app/page-image/5/icon?w=160", "Karte: ohne Version Adresse wie bisher")
    check(F.imagePath(id: 5, which: "icon", version: "/siegel/rot/x.svg").contains("&v=") && !F.imagePath(id: 5, which: "icon", version: "/siegel/rot/x.svg").contains("siegel"),
          "Karte: Symbol-Adresse nur als Pruefsumme in der Adresse")
}
