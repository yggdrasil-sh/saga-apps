import SwiftUI
import UserNotifications

// 2270 (Saga 2.6, K-C, jobpush1): Mitteilungen der Kollegin zu Auftraegen - Knoepfe und ihre Wirkung in der App.
// Anzeige und Kategorie setzt die Notification Service Extension (Notify/NotificationService.swift, Logik Shared/PushJob.swift).
// Hier: Kategorien anmelden (zusammen mit der Anruf-Kategorie, CallSystem.setup), Knopf im Hintergrund ausfuehren
// (POST /api/ai/jobs/<id>/act mit dem Geraete-Schluessel hkd_, docs/APP-API-KI.md Abschnitt 12.3), lokaler Hinweis
// "Bitte in Saga ansehen" (409/410/403/Netz) -> saga://job/<id>, Mitteilungen eines Auftrags entfernen.

extension Notification.Name {
    /// Ort "Saga" im Bereich "Auftraege" zeigen (Mitteilung, saga://job/<id>, saga://jobs)
    static let hkSagaJobs = Notification.Name("hk.sagaJobs")
}

enum JobPush {
    /// Ort "Saga" soll beim Erscheinen den Bereich "Auftraege" zeigen (Kaltstart ueber eine Mitteilung)
    @MainActor static var wantJobsTab = false

    /// Alle Auftrags-Kategorien (Shared/PushJob.swift, PushJobLogic.notificationCategories)
    static func categories() -> Set<UNNotificationCategory> { PushJobLogic.notificationCategories() }
    static func isOwnCategory(_ id: String) -> Bool { id.hasPrefix("saga.job.") }

    /// Knopf gedrueckt (Mitteilungs-Delegat). `open`: wie Antippen. Sonst act im Hintergrund, Reaktion je Code.
    static func perform(_ a: String, info: [AnyHashable: Any], requestID: String, thread: String) async {
        let job = PushJobLogic.int(info["job"])
        guard let job, let body = PushJobLogic.actBody(action: a, info: info) else {
            await hint(job: job, thread: thread)                                     // kein Token (alter Stand): in Saga ansehen
            return
        }
        var status: Int?
        for attempt in 0..<2 {
            status = await post(job: job, body: body)
            if !PushJobLogic.retry(status: status, attempt: attempt) { break }
        }
        let c = UNUserNotificationCenter.current()
        switch PushJobLogic.outcome(status: status) {
        case .remove:
            c.removeDeliveredNotifications(withIdentifiers: [requestID])
            await remove(thread: thread)
        case .hint, .openApp:                                                      // Hintergrund kann die App nicht oeffnen
            c.removeDeliveredNotifications(withIdentifiers: [requestID])
            await hint(job: job, thread: thread)
        }
    }

    /// POST /api/ai/jobs/<id>/act mit hkd_ (Schluesselbund, nach dem ersten Entsperren lesbar) -> Status; nil = Netzfehler.
    /// Ohne Geraete-Schluessel (Schluesselbund nicht beschreibbar, z. B. unsignierter Build): ueber die Sitzung der App
    /// mit X-Saga (Vertrag 12.3; der Server prueft das Geraet ueber die Sitzung). Ohne beides: 403 (App oeffnen).
    static func post(job: Int, body: [String: Any]) async -> Int? {
        #if DEBUG
        if let t = UserDefaults.standard.string(forKey: "hkActStatus") { return Int(t) }               // nur fuer Tests
        #endif
        let path = "api/ai/jobs/\(job)/act"
        guard AppConfig.hasServer, AppConfig.isAllowedServer(AppConfig.server) else { return 403 }
        guard let tok = DeviceKey.get() else {
            guard let store = await MainActor.run(body: { PushModel.current?.store }) else { return 403 }
            do { return try await store.apiResponse(path, method: "POST", body: body, timeout: 10, headers: ["X-Saga": "1"]).1 }
            catch APIError.http(let c, _) { return c }
            catch { return Store.isCancel(error) ? 403 : nil }
        }
        guard let url = URL(string: AppConfig.server.absoluteString.trimmingCharacters(in: CharacterSet(charactersIn: "/")) + "/" + path),
              let data = try? JSONSerialization.data(withJSONObject: body) else { return 403 }
        var req = URLRequest(url: url, timeoutInterval: 10)
        req.httpMethod = "POST"; req.httpShouldHandleCookies = false
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.setValue("Bearer " + tok, forHTTPHeaderField: "Authorization")
        req.setValue("1", forHTTPHeaderField: "X-Hlidskjalf")
        req.setValue(AppConfig.appLanguage, forHTTPHeaderField: "Accept-Language")
        req.httpBody = data
        guard let (_, r) = try? await URLSession.shared.data(for: req), let h = r as? HTTPURLResponse else { return nil }
        return h.statusCode
    }

    /// Lokaler, neutraler Hinweis "Bitte in Saga ansehen" (ersetzt je Auftrag) - Antippen oeffnet saga://job/<id>
    static func hint(job: Int?, thread: String) async {
        let c = UNMutableNotificationContent()
        c.title = String(localized: "SAGA_PUSH_TITLE")
        c.body = String(localized: String.LocalizationValue(JobTexts.hintKey))
        c.threadIdentifier = thread
        var ui: [String: Any] = ["kind": "job.hint", "url": PushJobLogic.route(job: job), "tag": thread]
        if let job { ui["job"] = job }
        c.userInfo = ui
        c.sound = nil
        try? await UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: "job-hint-" + (job.map(String.init) ?? "x"), content: c, trigger: nil))
    }

    /// Gezeigte Mitteilungen eines Auftrags entfernen (Faden bzw. tag job-<id>); `except`: diese Kennung behalten (im
    /// Vordergrund die gerade ankommende Mitteilung - sie kann schon in deliveredNotifications stehen)
    static func remove(thread: String, except: String? = nil) async {
        let c = UNUserNotificationCenter.current()
        let ids = await c.deliveredNotifications().filter { n in
            n.request.identifier != except &&
                (n.request.content.threadIdentifier == thread || (n.request.content.userInfo["tag"] as? String) == thread)
        }.map(\.request.identifier)
        if !ids.isEmpty { c.removeDeliveredNotifications(withIdentifiers: ids) }
    }
    static func remove(job: Int, except: String? = nil) async { await remove(thread: "job-\(job)", except: except) }
}
