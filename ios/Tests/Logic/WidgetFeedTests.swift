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
