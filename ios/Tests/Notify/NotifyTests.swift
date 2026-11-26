import XCTest
import UserNotifications

// 2262: Notification Service Extension (Notify/NotificationService.swift) mit den Server-Vektoren (Tests/vectors/push_e2e.json).
// `xcrun simctl push` startet die Erweiterung im Simulator nicht (CoreSimulatorBridge reicht die Mitteilung direkt an
// SpringBoard), deshalb hier: dieselbe Klasse wie in der Erweiterung, Anfrage mit dem APNs-Koerper des Vektors, Ergebnis
// pruefen. Laeuft in der App (TEST_HOST) - mit TEST_RUNNER_NOTIFY_BANNER=1 zeigt die App das Ergebnis zusaetzlich als
// Banner (Tests/Notify/run.sh macht die Bilder; Mitteilungen muessen fuer Saga erlaubt sein).
final class NotifyTests: XCTestCase {
    private var vectors: [String: Any] = [:]

    override func setUpWithError() throws {
        let url = try XCTUnwrap(Bundle(for: NotifyTests.self).url(forResource: "push_e2e", withExtension: "json"))
        vectors = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
        let k = try XCTUnwrap(vectors["keys"] as? [String: String])
        UserDefaults.standard.set("\(k["ua_priv"]!).\(k["auth"]!)", forKey: "hkPushE2ETestKey")   // DEBUG-Testschluessel
    }
    override func tearDown() { UserDefaults.standard.removeObject(forKey: "hkPushE2ETestKey") }

    private func body(_ name: String) throws -> [String: Any] {
        let cs = try XCTUnwrap(vectors["cases"] as? [[String: Any]])
        let c = try XCTUnwrap(cs.first { $0["name"] as? String == name })
        return try XCTUnwrap(c["apns_body"] as? [String: Any])
    }
    private func msg(_ name: String) throws -> [String: Any] {
        let cs = try XCTUnwrap(vectors["cases"] as? [[String: Any]])
        return try XCTUnwrap(cs.first { $0["name"] as? String == name }?["msg"] as? [String: Any])
    }

    /// Anfrage wie vom System: sichtbarer Teil aus aps (Loc-Keys aufgeloest wie SpringBoard), userInfo = APNs-Koerper
    private func service(_ apns: [String: Any]) -> UNNotificationContent {
        let c = UNMutableNotificationContent()
        let aps = apns["aps"] as? [String: Any] ?? [:]
        if let a = aps["alert"] as? [String: Any] {
            c.title = String(localized: String.LocalizationValue(a["title-loc-key"] as? String ?? ""))
            c.body = String(localized: String.LocalizationValue(a["loc-key"] as? String ?? ""))
        }
        if aps["sound"] != nil { c.sound = .default }
        if aps["interruption-level"] as? String == "time-sensitive" { c.interruptionLevel = .timeSensitive }
        c.userInfo = apns
        let req = UNNotificationRequest(identifier: UUID().uuidString, content: c, trigger: nil)
        let s = NotificationService()
        var out: UNNotificationContent?
        let done = expectation(description: "contentHandler")
        s.didReceive(req) { r in out = r; done.fulfill() }
        wait(for: [done], timeout: 5)
        s.serviceExtensionTimeWillExpire()                                              // danach: kein zweiter Aufruf
        return out ?? c
    }

    func testChat() throws {
        let m = try msg("chat"), r = service(try body("chat"))
        XCTAssertEqual(r.title, m["title"] as? String)
        XCTAssertEqual(r.body, m["body"] as? String)
        XCTAssertEqual(r.threadIdentifier, m["tag"] as? String)                       // ohne thread: tag
        XCTAssertNotNil(r.sound)
        XCTAssertEqual(r.categoryIdentifier, "")
        XCTAssertEqual(r.userInfo["kind"] as? String, "chat.message")
        XCTAssertEqual(r.userInfo["url"] as? String, m["url"] as? String)
        XCTAssertEqual((r.userInfo["chat"] as? NSNumber)?.intValue, 12)
        XCTAssertNil(r.userInfo["saga"], "Umschlag entfernt")
        try banner("chat", r)
    }
    func testKommentarFaden() throws {
        let m = try msg("kommentar"), r = service(try body("kommentar"))
        XCTAssertEqual(r.body, m["body"] as? String)
        XCTAssertEqual(r.threadIdentifier, m["thread"] as? String)                    // thread vor tag
        try banner("kommentar", r)
    }
    func testGruppenanruf() throws {
        let m = try msg("gruppenanruf_alert"), r = service(try body("gruppenanruf_alert"))
        XCTAssertEqual(r.body, m["body"] as? String)
        XCTAssertEqual(r.categoryIdentifier, "SAGA_CALL")                              // Annehmen/Ablehnen
        XCTAssertEqual(r.interruptionLevel, .timeSensitive)
        XCTAssertEqual(r.userInfo["kind"] as? String, "call.group")
        try banner("anruf", r)
    }
    func testStill() throws {
        let r = service(try body("sammelmeldung_still"))
        XCTAssertNil(r.sound, "silent: ohne Ton")
        XCTAssertEqual(r.userInfo["kind"] as? String, "status.digest")
    }
    func testKollegin() throws {
        let r = service(try body("auftrag_kollegin"))
        XCTAssertEqual(r.categoryIdentifier, "", "Auftrag der Kollegin ist kein Anruf")
        XCTAssertNotEqual(r.interruptionLevel, .timeSensitive)
    }
    func testUnlesbar() throws {
        var b = try body("chat")
        let bad = try XCTUnwrap((vectors["bad"] as? [[String: String]])?.first { $0["name"] == "anderer_schluessel" })
        var saga = try XCTUnwrap(b["saga"] as? [String: Any]); saga["c"] = bad["c"]; b["saga"] = saga
        let r = service(b)
        XCTAssertEqual(r.title, String(localized: "SAGA_PUSH_TITLE"))
        XCTAssertEqual(r.body, String(localized: "SAGA_PUSH_BODY"))
        XCTAssertFalse(r.body.contains("Mira"), "kein Inhalt")
        XCTAssertNotNil(r.userInfo["saga"], "Umschlag bleibt (App entschluesselt beim Antippen selbst)")
        try banner("unlesbar", r)
    }
    func testUnbekannterSchluessel() throws {
        UserDefaults.standard.removeObject(forKey: "hkPushE2ETestKey")
        let r = service(try body("gruppenanruf_alert"))
        XCTAssertEqual(r.body, String(localized: "SAGA_PUSH_CALL"), "Anruf-Platzhalter")
        XCTAssertEqual(r.interruptionLevel, .timeSensitive)
    }
    func testKlartextAlterServer() throws {
        let plain: [String: Any] = ["aps": ["alert": ["title": "Saga", "body": "Neue Nachricht von hk-test-b"], "sound": "default"],
                                    "url": "/chats#c1", "kind": "chat.message"]
        let c = UNMutableNotificationContent(); c.title = "Saga"; c.body = "Neue Nachricht von hk-test-b"; c.userInfo = plain
        let s = NotificationService()
        var out: UNNotificationContent?
        let done = expectation(description: "h")
        s.didReceive(UNNotificationRequest(identifier: "p", content: c, trigger: nil)) { out = $0; done.fulfill() }
        wait(for: [done], timeout: 5)
        XCTAssertEqual(out?.body, "Neue Nachricht von hk-test-b", "Klartext unveraendert")
        XCTAssertEqual(out?.userInfo["kind"] as? String, "chat.message")
    }

    // MARK: 2270 (K-C): Auftraege der Kollegin (Tests/vectors/push_kollegin.json, gleiche App-Schluessel)

    private func jobCase(_ name: String) throws -> [String: Any] {
        let url = try XCTUnwrap(Bundle(for: NotifyTests.self).url(forResource: "push_kollegin", withExtension: "json"))
        let v = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
        let cs = try XCTUnwrap(v["cases"] as? [[String: Any]])
        return try XCTUnwrap(cs.first { $0["name"] as? String == name })
    }
    private func jobService(_ name: String, auth: Bool = true) throws -> (UNNotificationContent, [String: Any]) {
        let c = try jobCase(name)
        let ab = try XCTUnwrap((c["transport"] as? [String: Any])?["apns_body"] as? [String: Any])
        UserDefaults.standard.set(auth, forKey: "hkJobDeviceAuth")
        defer { UserDefaults.standard.removeObject(forKey: "hkJobDeviceAuth") }
        return (service(ab), c)
    }
    private func checkJob(_ r: UNNotificationContent, _ c: [String: Any], category: String) {
        let e = c["expect"] as? [String: Any] ?? [:]
        let t = e["text"] as? [String: String] ?? [:]
        XCTAssertEqual(r.title, t["title"]); XCTAssertEqual(r.body, t["body"])
        XCTAssertEqual(r.threadIdentifier, e["thread_id"] as? String)
        XCTAssertEqual(r.categoryIdentifier, category)
        XCTAssertNil(r.userInfo["saga"], "Umschlag entfernt")
        XCTAssertNil(r.userInfo["detail"], "detail nicht in userInfo")
        XCTAssertNotEqual(r.interruptionLevel, .timeSensitive)
    }
    func testJobErledigt() throws {
        let (r, c) = try jobService("done_undo_apns")
        checkJob(r, c, category: "saga.job.done")
        XCTAssertNotNil(r.sound)
        XCTAssertEqual(r.userInfo["atok.undo"] as? String, "WuKANryrN8RlH60qY3vFUDoR")
        XCTAssertEqual((r.userInfo["pv"] as? NSNumber)?.intValue, 2)
        XCTAssertEqual((r.userInfo["job"] as? NSNumber)?.intValue, 42)
        XCTAssertEqual(r.userInfo["url"] as? String, "/saga?tab=jobs&job=42")
        try banner("job-done", r)
    }
    func testJobWartetAufOK() throws {
        let (r, c) = try jobService("needs_ok_apns")
        checkJob(r, c, category: "saga.job.needs.ok")
        try banner("job-needs", r)
    }
    func testJobOhneCode() throws {
        let (r, c) = try jobService("needs_ok_apns", auth: false)
        checkJob(r, c, category: "saga.job.needs.ok~open-later")              // ohne Geraete-Code nie "Passt, los"
    }
    func testJobFehlgeschlagen() throws {
        let (r, c) = try jobService("failed_apns")
        checkJob(r, c, category: "saga.job.failed")
        try banner("job-failed", r)
    }
    func testJobStill() throws {
        let (r, c) = try jobService("done_silent_apns")
        checkJob(r, c, category: "saga.job.done")
        XCTAssertNil(r.sound, "silent: ohne Ton")
    }
    func testJobOhneCaps() throws {
        let (r, c) = try jobService("done_nocaps_apns")
        checkJob(r, c, category: "saga.job.done.plain~")                       // keine Token -> keine Knoepfe
        XCTAssertNil(r.userInfo["atok.quiet"])
    }
    func testJobKategorienAngemeldet() throws {
        let cats = PushJobLogic.notificationCategories()
        XCTAssertEqual(cats.count, PushJobLogic.iosCategories().count)
        let done = try XCTUnwrap(cats.first { $0.identifier == "saga.job.done" })
        XCTAssertEqual(done.actions.map(\.identifier), ["saga.job.undo", "saga.job.quiet"])
        XCTAssertTrue(done.actions[0].options.contains(.authenticationRequired))
        XCTAssertFalse(done.actions[0].options.contains(.foreground))
        XCTAssertFalse(done.actions[1].options.contains(.authenticationRequired))
        let needs = try XCTUnwrap(cats.first { $0.identifier == "saga.job.needs" })
        XCTAssertTrue(needs.actions[0].options.contains(.foreground), "Ansehen oeffnet Saga")
        XCTAssertFalse(cats.contains { $0.actions.contains { $0 is UNTextInputNotificationAction } }, "kein Textfeld")
    }

    /// Ergebnis als Banner zeigen (nur mit TEST_RUNNER_NOTIFY_BANNER): gleiche Inhalte, lokal zugestellt
    private func banner(_ name: String, _ r: UNNotificationContent) throws {
        guard ProcessInfo.processInfo.environment["NOTIFY_BANNER"] == "1" else { return }
        guard let c = r.mutableCopy() as? UNMutableNotificationContent else { return }
        c.interruptionLevel = .active                                                   // ohne Berechtigung "Time Sensitive"
        // Haupt-Faden nicht blockieren: der Mitteilungs-Delegat der App (willPresent) laeuft dort
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: "notify-" + name, content: c, trigger: nil)) { e in
            print("NOTIFY_BANNER \(name) \(e.map { "\($0)" } ?? "ok")")
        }
        RunLoop.main.run(until: Date().addingTimeInterval(6))                          // run.sh macht das Bild
        UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: ["notify-" + name])
        RunLoop.main.run(until: Date().addingTimeInterval(1))
    }
}
