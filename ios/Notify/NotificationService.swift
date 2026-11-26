import UserNotifications
import LocalAuthentication

// 2262 (Saga 2.6): Notification Service Extension "HlidskjalfNotify" - entschluesselt Ende-zu-Ende-Mitteilungen
// (Shared/PushE2E.swift, Vertrag docs/PUSH-RELAY.md im Server-Repo) und setzt Titel, Text, Faden, Ton und Anruf-Kategorie.
// Nicht lesbar (Schluessel fehlt, vor dem ersten Entsperren, kaputt) oder Zeit abgelaufen: neutraler Platzhalter
// (Loc-Keys SAGA_PUSH_*), nie Inhalte. Klartext alter Server (ohne "saga") bleibt unveraendert.
// Grenzen der Erweiterung: ca. 30 s, ca. 24 MB - hier nur Schluesselbund + CryptoKit, kein Netz.
final class NotificationService: UNNotificationServiceExtension {
    private var handler: ((UNNotificationContent) -> Void)?
    private var fallback: UNMutableNotificationContent?

    override func didReceive(_ request: UNNotificationRequest, withContentHandler contentHandler: @escaping (UNNotificationContent) -> Void) {
        let info = request.content.userInfo
        guard let c = request.content.mutableCopy() as? UNMutableNotificationContent else { contentHandler(request.content); return }
        guard PushE2ELogic.isE2E(info) else { contentHandler(c); return }          // Klartext (Server vor 2.6): wie bisher
        handler = contentHandler
        fallback = Self.placeholder(c)
        guard let inner = PushE2E.open(info) else { finish(fallback ?? c); return }
        if PushJobLogic.isJob(inner["kind"] as? String) { job(inner, info, c); return }   // 2270: Auftrag der Kollegin
        let s = PushE2ELogic.shown(inner)
        c.title = s.title.isEmpty ? String(localized: "SAGA_PUSH_TITLE") : s.title
        c.body = s.body
        if let t = s.thread { c.threadIdentifier = t }
        c.sound = s.sound ? .default : nil
        if s.call {
            c.categoryIdentifier = PushE2ELogic.callCategory                         // Annehmen/Ablehnen (CallSystem.setup)
            c.interruptionLevel = .timeSensitive
        }
        var ui = info
        ui.removeValue(forKey: "saga")
        for (k, v) in s.info { ui[k] = v }                                            // flach wie der Klartext (kind, url, chat, ...)
        c.userInfo = ui
        finish(c)
    }

    /// 2270 (K-C): Mitteilung zu einem Auftrag - Titel t1/Text t2 (nur mit detail und Vorschau nicht "nie"), Kategorie aus
    /// Zustand + Token (approve/undo nur mit Geraete-Code), Faden job-<id>, Ton nur ohne silent. Token flach in userInfo
    /// ("atok.<a>"), damit PushCenterDelegate sie ueber Faeden weitergeben kann (PushLogic.plainInfo).
    private func job(_ inner: [String: Any], _ info: [AnyHashable: Any], _ c: UNMutableNotificationContent) {
        UNUserNotificationCenter.current().getNotificationSettings { st in
            let previews = st.showPreviewsSetting != .never
            guard let j = PushJobLogic.shown(inner, previews: previews, deviceAuth: Self.deviceAuth()) else { self.finish(self.fallback ?? c); return }
            c.title = j.title.isEmpty ? String(localized: "SAGA_PUSH_TITLE") : j.title
            c.body = j.body.isEmpty ? String(localized: "SAGA_PUSH_BODY") : j.body
            c.threadIdentifier = j.thread
            c.sound = j.sound ? .default : nil
            c.categoryIdentifier = j.iosCategory
            var ui = info
            ui.removeValue(forKey: "saga")
            for (k, v) in PushJobLogic.flatInfo(inner) { ui[k] = v }
            c.userInfo = ui
            self.finish(c)
        }
    }
    /// Geraet mit Code (Entsperren schuetzt approve/undo) - ohne Code nie diese Knoepfe (K15 phone_noauth)
    static func deviceAuth() -> Bool {
        #if DEBUG
        if let v = UserDefaults.standard.object(forKey: "hkJobDeviceAuth") as? Bool { return v }   // nur fuer Tests (Simulator ohne Code)
        #endif
        return LAContext().canEvaluatePolicy(.deviceOwnerAuthentication, error: nil)
    }

    override func serviceExtensionTimeWillExpire() {
        if let f = fallback { finish(f) }
    }

    private func finish(_ c: UNNotificationContent) {
        guard let h = handler else { return }
        handler = nil
        h(c)
    }

    /// Platzhalter: kein Inhalt; Anruf-Hinweis bleibt als Anruf erkennbar
    private static func placeholder(_ orig: UNMutableNotificationContent) -> UNMutableNotificationContent {
        let p = UNMutableNotificationContent()
        p.title = String(localized: "SAGA_PUSH_TITLE")
        let call = orig.interruptionLevel == .timeSensitive
        p.body = call ? String(localized: "SAGA_PUSH_CALL") : String(localized: "SAGA_PUSH_BODY")
        p.sound = orig.sound
        p.interruptionLevel = orig.interruptionLevel
        p.userInfo = orig.userInfo                     // Umschlag bleibt: die App entschluesselt beim Antippen selbst (PushCenterDelegate)
        return p
    }
}
