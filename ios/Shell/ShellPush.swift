import AppKit
import UserNotifications

/// Mitteilungen der Mac-Huelle ueber APNs (wie App/Push.swift): Erlaubnis nur auf Klick in den App-Einstellungen,
/// Geraetetoken beim eigenen Server anmelden (POST /api/push/subscriptions {type:"apns", token, env, name}) - mit der
/// Web-Sitzung der Huelle, die an ihre Geraete-Anmeldung gebunden ist. Bei jedem Start erneut senden. Bereiche,
/// Ruhezeiten und Stummschalten stellt man im Web ein (Einstellungen -> Mitteilungen), der Server wendet sie an.
/// Braucht die Berechtigung aps-environment (Shell/Shell-push.entitlements, SAGA_MAC_PUSH_SUFFIX = -push mit
/// Bereitstellungsprofil); ohne sie meldet das System einen Fehler, der in den App-Einstellungen steht.
@MainActor
final class ShellPush: NSObject, ObservableObject {
    weak var model: ShellModel?
    @Published var busy = false
    @Published var message: String?
    @Published private(set) var on = false

    private var waiters: [CheckedContinuation<String?, Never>] = []
    private var renewed = false
    private var wantKey: String { "shell.push.on." + (AppConfig.serverOrigin ?? "-") }
    /// Person hat Mitteilungen auf diesem Mac eingeschaltet (je Server)
    private var wanted: Bool {
        get { UserDefaults.standard.bool(forKey: wantKey) }
        set { UserDefaults.standard.set(newValue, forKey: wantKey); on = newValue }
    }
    private var subID: Int? {
        get { (UserDefaults.standard.object(forKey: wantKey + ".id") as? Int) }
        set { UserDefaults.standard.set(newValue, forKey: wantKey + ".id") }
    }

    static var env: String {
        #if DEBUG
        return "sandbox"
        #else
        return "production"
        #endif
    }

    func refreshState() { on = wanted }

    /// Angemeldet (Start, Anmeldung): eingeschaltet -> Token neu holen und anmelden (gleiche Daten = gleiches Abo).
    /// Fragt nie nach der Erlaubnis (keine Systemdialoge ohne Klick).
    func appStarted() {
        on = wanted
        guard wanted, !renewed else { return }                       // einmal je Start (nicht bei jeder Sitzungserneuerung)
        renewed = true
        Task {
            let s = await UNUserNotificationCenter.current().notificationSettings()
            guard s.authorizationStatus == .authorized || s.authorizationStatus == .provisional else { return }
            if let t = await requestToken() { await subscribe(t) }
        }
    }

    /// Abgemeldet: das Abo loescht der Server mit der Geraete-Anmeldung
    func signedOut() { subID = nil; message = nil; renewed = false; on = wanted }

    /// Schalter "Mitteilungen auf diesem Mac" (nur auf Klick: erst dann die System-Rueckfrage)
    func setEnabled(_ v: Bool) async {
        busy = true; defer { busy = false }
        message = nil
        if v {
            let ok = (try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge])) ?? false
            guard ok else {
                message = String(localized: "Nicht erlaubt.") + " " + String(localized: "Mitteilungen in den Systemeinstellungen für Saga erlauben.")
                on = false; return
            }
            guard let t = await requestToken() else {
                if message == nil { message = String(localized: "Dieses Gerät kann gerade keine Mitteilungen empfangen.") }
                on = false; return
            }
            wanted = true
            await subscribe(t)
        } else {
            wanted = false
            if let id = subID { _ = await model?.sessionAPI("api/push/subscriptions/\(id)", method: "DELETE") }
            subID = nil
        }
    }

    /// Geraetetoken vom System (hoechstens 20 s warten)
    private func requestToken() async -> String? {
        await withCheckedContinuation { (c: CheckedContinuation<String?, Never>) in
            let first = waiters.isEmpty
            waiters.append(c)
            NSApplication.shared.registerForRemoteNotifications()
            if first {                                                       // ein Zeitlimit je Anfrage-Runde
                let g = round
                Task { try? await Task.sleep(for: .seconds(20)); if self.round == g { self.finish(nil) } }
            }
        }
    }
    private var round = 0
    private func finish(_ t: String?) {
        round += 1
        let w = waiters; waiters = []
        for c in w { c.resume(returning: t) }
    }
    func didRegister(_ data: Data) {
        let hex = data.map { String(format: "%02x", $0) }.joined()
        let had = !waiters.isEmpty
        finish(hex)
        if !had && wanted { Task { await subscribe(hex) } }                  // neues Token ohne Anfrage
    }
    func didFail(_ e: Error) { message = e.localizedDescription; finish(nil) }

    private func subscribe(_ t: String) async {
        guard let model, model.isSignedIn else { return }
        let body: [String: Any] = ["type": "apns", "token": t, "env": Self.env, "name": String(ShellKeys.deviceName.prefix(80))]
        guard let (d, code) = await model.sessionAPI("api/push/subscriptions", method: "POST", body: body) else {
            message = String(localized: "Server nicht erreichbar – bitte die Adresse prüfen."); return
        }
        let j = (try? JSONSerialization.jsonObject(with: d)) as? [String: Any]
        guard (200..<300).contains(code) else { message = j?["error"] as? String ?? "\(code)"; return }
        message = nil
        let keep = j?["id"] as? Int
        subID = keep
        // aeltere Abos derselben Geraete-Anmeldung (frueheres Token) entfernen - sonst steht der Mac doppelt in der Liste
        guard let keep, let (ld, lc) = await model.sessionAPI("api/push"), lc == 200,
              let lj = (try? JSONSerialization.jsonObject(with: ld)) as? [String: Any] else { return }
        for dev in lj["devices"] as? [[String: Any]] ?? [] {
            guard (dev["this_device"] as? Bool) == true, (dev["type"] as? String) == "apns", let id = dev["id"] as? Int, id != keep else { continue }
            _ = await model.sessionAPI("api/push/subscriptions/\(id)", method: "DELETE")
        }
    }
}

/// Anzeige im Vordergrund und Klick auf eine Mitteilung
final class ShellNotificationDelegate: NSObject, UNUserNotificationCenterDelegate {
    static let shared = ShellNotificationDelegate()

    func userNotificationCenter(_ c: UNUserNotificationCenter, willPresent n: UNNotification) async -> UNNotificationPresentationOptions {
        let kind = n.request.content.userInfo["kind"] as? String
        // Fenster vorn: das Web klingelt bzw. zeigt die Glocke selbst - Anrufe und Chat nicht doppelt
        let active = await MainActor.run { NSApp.isActive && ShellModel.shared.phase == .web && !ShellModel.shared.offline }
        if active, ShellLogic.isRinging(kind: kind) || kind == "chat.message" || kind == "chat.mention" { return [.list] }
        return [.banner, .list, .sound]
    }

    func userNotificationCenter(_ c: UNUserNotificationCenter, didReceive r: UNNotificationResponse) async {
        let url = r.notification.request.content.userInfo["url"] as? String
        await MainActor.run { ShellModel.shared.notificationClicked(url) }
    }
}
