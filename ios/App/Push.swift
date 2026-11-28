import SwiftUI
import UserNotifications
#if os(iOS)
import UIKit
#else
import AppKit
#endif

// Mitteilungen aufs Geraet (Saga 1.7) ueber APNs: Erlaubnis holen, Geraetetoken beim eigenen Server anmelden
// (POST /api/push/subscriptions {type:"apns", token, env, name}), bei jedem Start erneut senden. Inhalt kommt vom Server
// (nie Seiten-, Kommentar- oder Chat-Inhalte); Tippen oeffnet `url`. Logik: PushLogic.swift.
// Hinweis: braucht die Berechtigung aps-environment (iOS: App-iOS.entitlements; macOS: nur mit SAGA_MAC_PUSH=YES,
// siehe Config/Base.xcconfig - ohne Bereitstellungsprofil gibt es auf dem Mac keine Push-Berechtigung).

extension Notification.Name {
    /// Mitteilung angetippt -> Ziel oeffnen (object: NotifTarget)
    static let hkOpenTarget = Notification.Name("hk.openTarget")
}

@MainActor
final class PushModel: ObservableObject {
    static weak var current: PushModel?
    /// Ziel einer angetippten Mitteilung, das noch nicht geoeffnet wurde
    static var pending: NotifTarget?
    /// 2262: Haken fuer stille Mitteilungen (entschluesselte Felder; true = neue Daten) - z. B. Widget-Push (2188)
    static var backgroundHook: (([AnyHashable: Any]) async -> Bool)?
    weak var store: Store? { didSet { PushModel.current = self } }
    @Published var state: PushState?
    @Published var supported = true
    @Published var loadError: String?
    @Published var busy = false
    @Published var message: String?
    private var tokenWaiters: [CheckedContinuation<String?, Never>] = []

    private var wantKey: String { "hk.push.on." + (AppConfig.serverOrigin ?? "-") }
    /// Person hat Mitteilungen auf diesem Geraet eingeschaltet (je Server)
    var wanted: Bool {
        get { UserDefaults.standard.bool(forKey: wantKey) }
        set { UserDefaults.standard.set(newValue, forKey: wantKey) }
    }
    private(set) var token: String? = UserDefaults.standard.string(forKey: "hk.push.token")

    static var env: String {
        #if DEBUG
        return PushLogic.env(debug: true)
        #else
        return PushLogic.env(debug: false)
        #endif
    }

    func signedOut() {                                              // Abo verschwindet beim Abmelden auf dem Server
        state = nil; message = nil
        // 2262: Schluessel gehoeren zum Konto - das naechste Abo (auch ein anderes Konto desselben Servers) bekommt neue
        if let o = AppConfig.serverOrigin { PushE2E.forget(origin: o) }
    }

    /// 2262: App kann Ende-zu-Ende-Mitteilungen anzeigen - nur iOS mit eingebetteter Erweiterung HlidskjalfNotify
    /// (build-adhoc/-testflight bauen ohne sie weiter, wenn ihr Profil fehlt; dann keine Schluessel ans Abo, alles wie bisher)
    nonisolated static let e2eReady: Bool = {
        #if os(iOS)
        guard let u = Bundle.main.builtInPlugInsURL?.appendingPathComponent("HlidskjalfNotify.appex") else { return false }
        return FileManager.default.fileExists(atPath: u.path)
        #else
        return false                                                // Mac-Huelle: direkt, ohne Schluessel (2262-12 spaeter)
        #endif
    }()
    /// Schalter-Zustand fuer dieses Geraet
    var availability: PushLogic.Availability {
        PushLogic.availability(state, bundle: AppConfig.mainBundleID, e2eReady: Self.e2eReady)
    }
    /// keys/topic fuers Abo (nur Server mit apns_e2e; Schluessel je Server im gemeinsamen Schluesselbund, Wechsel nach 30 Tagen)
    private func e2eFields() -> (e2e: Int, keys: [String: String]?, topic: String?) {
        let e = state?.e2e ?? 0
        guard e >= 1, Self.e2eReady, let o = AppConfig.serverOrigin,
              let k = PushE2E.current(origin: o, group: AppConfig.keychainGroup) else { return (0, nil, nil) }
        return (e, k.subscriptionKeys, AppConfig.mainBundleID)
    }

    func load() async {
        guard let store else { return }
        guard let (d, code) = try? await store.apiResponse("api/push") else { return }
        if code == 404 { supported = false; return }
        guard code == 200, let s = PushState(data: d) else { loadError = "\(code)"; return }
        supported = true; loadError = nil; state = s
    }

    /// App-Start (angemeldet): eingeschaltet -> Token neu holen und anmelden (gleiche Daten = gleiches Abo)
    func appStarted() {
        guard wanted else { return }
        Task {
            if let t = await requestToken() { await subscribe(t) }
            await subscribeVoIP()
        }
    }

    /// 2.5 (push2): zuletzt beim Server angemeldete Sprache dieses Abos (je Server)
    private var langKey: String { "hk.push.lang." + (AppConfig.serverOrigin ?? "-") }
    /// App wieder vorne: hat sich die App-Sprache geaendert (iOS startet die App dabei meist ohnehin neu; Mac nach
    /// Neustart), Abo mit der neuen Sprache erneuern. Nur Server mit payload >= 2.
    func languageCheck() {
        guard wanted, let t = token, let s = state,
              PushLogic.langChanged(sent: UserDefaults.standard.string(forKey: langKey), now: AppConfig.appLanguage, payload: s.payload) else { return }
        Task { await subscribe(t); await subscribeVoIP() }
    }

    /// Schalter "Mitteilungen auf diesem Geraet"
    func setEnabled(_ on: Bool) async {
        busy = true; defer { busy = false }
        message = nil
        if on {
            var ok = false
            #if DEBUG
            ok = UserDefaults.standard.string(forKey: "hkPushToken") != nil                  // nur fuer Tests: ohne System-Rueckfrage
            #endif
            if !ok { ok = (try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge])) ?? false }
            guard ok else { message = String(localized: "Nicht erlaubt.") + " " + String(localized: "Mitteilungen in den Systemeinstellungen für Saga erlauben."); return }
            guard let t = await requestToken() else {
                message = String(localized: "Dieses Gerät kann gerade keine Mitteilungen empfangen.")
                return
            }
            wanted = true
            await subscribe(t)
            await subscribeVoIP()
        } else {
            wanted = false
            if let id = state?.thisDevice?.id { _ = try? await store?.api("api/push/subscriptions/\(id)", method: "DELETE") }
            if let id = state?.thisVoip?.id { _ = try? await store?.api("api/push/subscriptions/\(id)", method: "DELETE") }
            await load()
        }
    }

    /// Geraetetoken vom System (hoechstens 20 s warten)
    private func requestToken() async -> String? {
        #if DEBUG
        if let t = UserDefaults.standard.string(forKey: "hkPushToken") { didRegister(hex: t); return t }   // nur fuer Tests (Simulator ohne Push-Berechtigung)
        #endif
        return await withCheckedContinuation { (c: CheckedContinuation<String?, Never>) in
            tokenWaiters.append(c)
            #if os(iOS)
            UIApplication.shared.registerForRemoteNotifications()
            #else
            NSApplication.shared.registerForRemoteNotifications()
            #endif
            Task { try? await Task.sleep(for: .seconds(20)); self.finishWaiters(nil) }
        }
    }
    private func finishWaiters(_ t: String?) {
        let w = tokenWaiters; tokenWaiters = []
        for c in w { c.resume(returning: t) }
    }
    func didRegister(token data: Data) { didRegister(hex: PushLogic.hex(data)) }
    func didRegister(hex: String) {
        let changed = hex != token
        token = hex; UserDefaults.standard.set(hex, forKey: "hk.push.token")
        let hadWaiters = !tokenWaiters.isEmpty
        finishWaiters(hex)
        if changed && !hadWaiters && wanted { Task { await subscribe(hex) } }        // iOS liefert ein neues Token
    }
    func didFail(_ error: Error) { message = error.localizedDescription; finishWaiters(nil) }

    private func subscribe(_ t: String) async {
        guard let store, AppConfig.hasServer, !store.needsLogin else { return }
        if state == nil { await load() }                                // 2.5: payload des Servers kennen (lang nur ab 2)
        // 2262: Build ohne Erweiterung, Server hat noch Schluessel dieses Geraets -> Abo neu (sonst nur Platzhalter)
        if let id = PushLogic.staleE2E(state, e2eReady: Self.e2eReady) { _ = try? await store.api("api/push/subscriptions/\(id)", method: "DELETE") }
        let lang = PushLogic.pushLang(AppConfig.appLanguage, payload: state?.payload ?? 0)
        let f = e2eFields()
        let body = PushLogic.subscription(token: t, env: Self.env, name: DeviceKey.deviceName, lang: lang, payload: state?.payload ?? 0,
                                          e2e: f.e2e, keys: f.keys, topic: f.topic,
                                          caps: PushJobLogic.subscriptionCaps(serverCaps: state?.subCaps ?? []))   // 2188 widgets, 2270 detail/actions
        do {
            let (d, code) = try await store.apiResponse("api/push/subscriptions", method: "POST", body: body)
            if !(200..<300).contains(code) { message = ShareJSON.obj(d).flatMap { ShareJSON.str($0["error"]) } ?? "\(code)" }
            else if let lang { UserDefaults.standard.set(lang, forKey: langKey) }
            await load()
            // aeltere Abos derselben App-Anmeldung (frueheres Token) entfernen - sonst steht das Geraet doppelt in der Liste
            if (200..<300).contains(code), let keep = ShareJSON.obj(d).flatMap({ ShareJSON.int($0["id"]) }), let s = state {
                let dup = PushLogic.duplicates(s.devices, keep: keep)
                for id in dup { _ = try? await store.api("api/push/subscriptions/\(id)", method: "DELETE") }
                if !dup.isEmpty { await load() }
            }
            #if os(iOS)
            if (200..<300).contains(code) { await WidgetPush.report(store, force: true) }      // 2188: Widgets dieses Geraets melden
            #endif
            return
        } catch { message = error.localizedDescription }
        await load()
    }

    /// Anrufe (2.1, iOS): PushKit-Token als eigenes Abo "apns-voip" - der Server schickt dorthin nur klingelnde Anrufe
    func voipTokenChanged(_ hex: String) { if wanted { Task { await subscribeVoIP() } } }
    private func subscribeVoIP() async {
        #if os(iOS)
        guard CallEnv.callKit, let t = CallSystem.shared.voipToken, let store, AppConfig.hasServer, !store.needsLogin else { return }
        if state == nil { await load() }
        let f = e2eFields()                                                                             // 2262: derselbe Schluessel
        let body = PushLogic.subscription(token: t, env: Self.env, name: DeviceKey.deviceName, type: "apns-voip",
                                          lang: AppConfig.appLanguage, payload: state?.payload ?? 0,   // 2.5: Sprache (verpasste Anrufe)
                                          e2e: f.e2e, keys: f.keys, topic: f.topic)
        guard let (d, code) = try? await store.apiResponse("api/push/subscriptions", method: "POST", body: body), (200..<300).contains(code) else { return }
        await load()
        if let keep = ShareJSON.obj(d).flatMap({ ShareJSON.int($0["id"]) }), let s = state {
            let dup = PushLogic.duplicates(s.devices, keep: keep, type: "apns-voip")
            for id in dup { _ = try? await store.api("api/push/subscriptions/\(id)", method: "DELETE") }
            if !dup.isEmpty { await load() }
        }
        #endif
    }

    func save(_ body: [String: Any]) async {
        guard let store else { return }
        do {
            let d = try await store.api("api/push/settings", method: "PUT", body: body)
            if let s = PushState(data: d) { state = s }
            message = String(localized: "✓ Gespeichert")
        } catch { message = error.localizedDescription }
    }
    func test(_ id: Int) async -> PushLogic.TestResult {
        guard let store, let (d, code) = try? await store.apiResponse("api/push/subscriptions/\(id)/test", method: "POST", body: [:]) else { return .fail("–") }
        return PushLogic.testResult(code: code, json: ShareJSON.obj(d))
    }
    func remove(_ id: Int) async {
        if state?.devices.first(where: { $0.id == id })?.thisDevice == true { wanted = false }
        _ = try? await store?.api("api/push/subscriptions/\(id)", method: "DELETE")
        await load()
    }

    nonisolated static func plain(_ info: [AnyHashable: Any]) -> [AnyHashable: Any] { PushLogic.plainInfo(info) }
    /// Auf dem Haupt-Faden ausfuehren - sofort, wenn schon dort (Mitteilungs-Delegat: das System erwartet Main)
    nonisolated static func onMain(_ f: @escaping @MainActor () -> Void) {
        if Thread.isMainThread { MainActor.assumeIsolated { f() } } else { DispatchQueue.main.async { MainActor.assumeIsolated { f() } } }
    }

    /// Mitteilung angetippt: `url` relativ zu BASE_URL -> Ziel oeffnen, Benachrichtigung als gelesen markieren
    nonisolated static func handleTap(_ userInfo: [AnyHashable: Any], action: String = UNNotificationDefaultActionIdentifier) {
        // Anruf (2.1): klingeln/annehmen. Alles andere (z. B. Chat-Nachricht WAEHREND eines Anrufs) oeffnet nur das Ziel;
        // RootView verkleinert dafuer zuerst die Anruf-Oberflaeche (2.3.1) - der Anruf selbst bleibt unberuehrt.
        if case .call = CallLogic.tapRoute(kind: userInfo["kind"] as? String, url: userInfo["url"] as? String, call: userInfo["call"] as? String,
                                           phase: "idle", minimized: false, callKit: false) {
            let info = plain(userInfo)
            let a = action
            Task { @MainActor in CallSystem.shared.notificationAction(a, info: info) }
            return
        }
        // 2.6 K-G (2275): lokale Mitteilung der Teilen-Erweiterung -> Ort "Saga" uebernimmt den Entwurf (kein Server-Ziel)
        if (userInfo["kind"] as? String) == ShareDraftLogic.notifyKind {
            Task { @MainActor in SagaOpen.request(.share) }
            return
        }
        let t = PushLogic.tapTarget(userInfo)
        Task { @MainActor in
            PushModel.pending = t                                   // Kaltstart: RootView holt es beim Erscheinen ab
            NotificationCenter.default.post(name: .hkOpenTarget, object: t)
            await PushModel.current?.store?.notifs.markReadFor(target: t)
        }
    }
}

/// Mitteilungen: Anzeige im Vordergrund und Antippen
/// Bugs-DB 2187: bewusst die Varianten mit completionHandler, nicht `async`. Bei der async-Variante ruft Swift den
/// completionHandler des Systems nach dem Ende der Funktion auf einem Hintergrund-Faden (cooperative pool) auf; UIKit
/// prueft dort den Haupt-Faden (-[UIApplication _performBlockAfterCATransactionCommitSynchronizes:]) und beendet die App
/// (SIGABRT) - Antippen schloss nur die Mitteilung, Saga oeffnete das Ziel nie. Der Handler laeuft hier immer auf Main.
final class PushCenterDelegate: NSObject, UNUserNotificationCenterDelegate {
    static let shared = PushCenterDelegate()
    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
                                withCompletionHandler done: @escaping (UNNotificationPresentationOptions) -> Void) {
        let info = PushE2E.openInfo(notification.request.content.userInfo)   // 2262: Platzhalter der Erweiterung -> selbst oeffnen
        let remote = notification.request.trigger is UNPushNotificationTrigger
        let plain = PushModel.plain(info), rid = notification.request.identifier
        PushModel.onMain { done(PushCenterDelegate.presentation(plain, remote: remote, requestID: rid)) }
    }
    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse,
                                withCompletionHandler done: @escaping () -> Void) {
        let info = PushModel.plain(PushE2E.openInfo(response.notification.request.content.userInfo)), action = response.actionIdentifier
        // 2270: Knopf an einer Auftrags-Mitteilung (ausser "Ansehen") - im Hintergrund ausfuehren, erst danach done()
        if let a = PushJobLogic.action(fromID: action), a != "open" {
            let rid = response.notification.request.identifier
            let thread = response.notification.request.content.threadIdentifier
            Task { await JobPush.perform(a, info: info, requestID: rid, thread: thread); PushModel.onMain { done() } }
            return
        }
        PushModel.onMain {
            PushModel.handleTap(info, action: action)
            done()
        }
    }
    @MainActor private static func presentation(_ info: [AnyHashable: Any], remote: Bool, requestID: String = "") -> UNNotificationPresentationOptions {
        // Anruf-Mitteilung vom Server, waehrend die App vorne ist: die App klingelt selbst (CallKit bzw. Klingelton und
        // Fenster) - nicht zusaetzlich Banner und Ton; Klingeln anstossen, falls der Live-Kanal es verpasst hat
        if CallLogic.hideWhileRunning(kind: info["kind"] as? String, remote: remote) {
            CallSystem.shared.ringFromNotice(info)
            return []
        }
        // 4.11.: Nachricht aus dem Chat, der gerade offen und sichtbar ist - kein Banner/Ton (die Nachricht steht ja schon da)
        #if os(iOS)
        let active = UIApplication.shared.applicationState == .active
        #else
        let active = NSApplication.shared.isActive
        #endif
        let openID = active ? PushModel.current?.store?.chat.open?.chatID : nil
        if NotifLogic.hideWhileOpen(kind: info["kind"] as? String, url: info["url"] as? String, openChat: openID) { return [] }
        // 2270: Auftrag gerade offen (Saga vorne) - kein Banner; aeltere Mitteilungen desselben Auftrags entfernen
        if PushJobLogic.isJob(info["kind"] as? String), let job = PushJobLogic.int(info["job"]) {
            Task { await JobPush.remove(job: job, except: requestID) }                // nie die gerade gezeigte (gleiche collapse-id)
            if active, PushModel.current?.store?.saga.openJob == job { return [] }
        }
        // 2.6 (2029-6): Sammelmeldung bei offener App - der Live-Hinweis (status.digest) steht schon da
        if active, (info["kind"] as? String) == "status.digest", PushModel.current?.store?.ustatus.on == true { return [] }
        return [.banner, .list, .sound]
    }
}

#if os(iOS)
final class IOSAppDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        UNUserNotificationCenter.current().delegate = PushCenterDelegate.shared
        ImagePaste.install()                                                         // Bilder aus der Zwischenablage (Seite, Chat)
        MainActor.assumeIsolated { CallSystem.shared.setup() }                       // Anrufe: PushKit (VoIP) + Mitteilungs-Aktionen
        MainActor.assumeIsolated { WidgetPush.install() }                            // 2188: stiller Widget-Push -> Widgets neu laden
        return true
    }
    func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        Task { @MainActor in PushModel.current?.didRegister(token: deviceToken) }
    }
    func application(_ application: UIApplication, didFailToRegisterForRemoteNotificationsWithError error: Error) {
        Task { @MainActor in PushModel.current?.didFail(error) }
    }
    /// 2262: stille Mitteilung (content-available) - Ende-zu-Ende entschluesseln, an den Haken geben (2188 Widget-Push).
    /// Ohne Haken: nichts zu tun (.noData). Klartext alter Server geht unveraendert durch.
    func application(_ application: UIApplication, didReceiveRemoteNotification userInfo: [AnyHashable: Any],
                     fetchCompletionHandler completionHandler: @escaping (UIBackgroundFetchResult) -> Void) {
        let info = PushModel.plain(PushE2E.openInfo(userInfo))
        // 2270: Auftrag abgebrochen/zurueckgenommen (job.changed update+removed) - Mitteilung mit diesem tag schliessen
        if let tag = PushJobLogic.removeTag(info) {
            Task { await JobPush.remove(thread: tag); completionHandler(.newData) }
            return
        }
        MainActor.assumeIsolated {
            guard let hook = PushModel.backgroundHook else { completionHandler(.noData); return }
            Task { @MainActor in completionHandler(await hook(info) ? .newData : .noData) }
        }
    }
    /// 2.4: Video-Uploads im Hintergrund (URLSession background) - das System weckt die App fuer den naechsten Teil
    func application(_ application: UIApplication, handleEventsForBackgroundURLSession identifier: String, completionHandler: @escaping () -> Void) {
        MainActor.assumeIsolated { ChatVideoUploads.shared.wake(completionHandler) }
    }
}
#endif

// MARK: - Einstellungen → Mitteilungen

struct PushSettingsSection: View {
    @EnvironmentObject var store: Store
    @ObservedObject var push: PushModel
    @State private var askRemove: PushDevice?
    @State private var tests: [Int: String] = [:]
    var body: some View {
        if push.supported {
            Section {
                let av = push.availability
                // Schalter und Zustand duerfen sich nicht widersprechen: nur AN, wenn der Server zustellen kann
                // (2262: auch bei gestoertem Relais - die Mitteilungen warten, die Glocke zeigt alles)
                let on = PushLogic.usable(av) && push.wanted && push.state?.thisDevice != nil
                Toggle(isOn: Binding(get: { on }, set: { v in Task { await push.setEnabled(v) } })) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Mitteilungen auf diesem Gerät")
                        if let d = push.state?.thisDevice, let e = d.lastError { Text("Letzter Fehler: \(e)").font(.caption).foregroundStyle(.orange) }
                    }
                }
                .disabled(push.busy || !PushLogic.usable(av))
                .id("push")
                switch av {
                case .serverOff: Label("Mitteilungen sind auf diesem Server abgeschaltet.", systemImage: "bell.slash").font(.footnote).foregroundStyle(.secondary)
                case .appleMissing: Label("Mitteilungen an die Apple-App sind auf diesem Server noch nicht eingerichtet (Admin-Bereich).", systemImage: "info.circle").font(.footnote).foregroundStyle(.secondary)
                case .relayDown: Label("Mitteilungen ans iPhone sind gerade gestört – Saga zeigt alles in der Glocke.", systemImage: "exclamationmark.triangle").font(.footnote).foregroundStyle(.orange)
                default: EmptyView()
                }
                if let m = push.message { Text(m).font(.footnote).foregroundStyle(.secondary) }
                // "Worueber?" nur, wenn Mitteilungen eingeschaltet sind UND der Server zustellen kann - sonst wirkten
                // eingeschaltete Themen-Schalter, als kaeme etwas an (BUG-102)
                if let s = push.state, s.enabled, PushLogic.usable(av) { options(s, enabled: on) }
            } header: { Text("Mitteilungen") } footer: {
                Text("Mitteilungen aufs Handy oder den Computer – auch wenn Saga geschlossen ist. Inhalte von Seiten, Kommentaren und Chats werden nie mitgeschickt.")
            }
            .task {
                await push.load()
                #if DEBUG
                if UserDefaults.standard.string(forKey: "hkPushToken") != nil && !push.wanted { await push.setEnabled(true) }   // nur fuer Tests
                #endif
            }
            if let s = push.state, !s.devices.isEmpty { devices(s) }
        }
    }

    @ViewBuilder private func options(_ s: PushState, enabled: Bool) -> some View {
        Group {
        Text("Worüber?").font(.subheadline.weight(.semibold)).padding(.top, 4)
        ForEach(s.categories, id: \.self) { c in
            Toggle(Self.catLabel(c), isOn: Binding(get: { s.cats[c] ?? true }, set: { v in Task { await push.save(["cats": [c: v]]) } }))
        }
        Toggle(isOn: Binding(get: { s.names }, set: { v in Task { await push.save(["names": v]) } })) { Text("Namen zeigen") }
        Toggle(isOn: Binding(get: { s.quietOn }, set: { v in Task { await push.save(["quiet": ["on": v, "tz": TimeZone.current.identifier]]) } })) {
            VStack(alignment: .leading, spacing: 2) {
                Text("Ruhezeiten")
                Text("In dieser Zeit keine Mitteilungen (die Glocke in Saga zeigt alles)").font(.caption).foregroundStyle(.secondary)
            }
        }
        if s.quietOn {
            DatePicker("von", selection: timeBinding(s.quietFrom, key: "from"), displayedComponents: .hourAndMinute)
            DatePicker("bis", selection: timeBinding(s.quietTo, key: "to"), displayedComponents: .hourAndMinute)
        }
        }
        .disabled(!enabled).foregroundStyle(enabled ? Color.primary : Color.secondary)     // ausgegraut, solange Mitteilungen aus sind
    }

    private func devices(_ s: PushState) -> some View {
        Section {
            ForEach(s.devices) { d in
                VStack(alignment: .leading, spacing: 4) {
                    HStack {
                        VStack(alignment: .leading, spacing: 1) {
                            Text(verbatim: (d.name.isEmpty ? Self.serviceText(d) : d.name) + (d.thisDevice ? " · " + String(localized: "dieses Gerät") : ""))
                            Text(verbatim: Self.serviceText(d) + (d.encrypted ? "" : " · " + String(localized: "ohne Namen (Server sieht den Text)"))).font(.caption).foregroundStyle(.secondary)
                            Group {
                                if let e = d.lastError { Text("Letzter Fehler: \(e)") }
                                else if let ok = d.lastOK { Text("Zuletzt zugestellt \(Date(timeIntervalSince1970: ok).sagaRelative)") }
                                else { Text("Noch nichts zugestellt") }
                            }.font(.caption).foregroundStyle(.secondary)
                        }
                        Spacer()
                        if d.type != "apns-voip" {                       // VoIP-Abo: Test nur per Anruf (CallKit-Pflicht)
                            Button("Test") { Task { tests[d.id] = String(localized: "Sende …"); tests[d.id] = Self.testText(await push.test(d.id)) } }
                                .buttonStyle(.bordered).controlSize(.small).help("Testmitteilung an dieses Gerät senden")
                        }
                        if !d.thisDevice {                                   // dieses Geraet: ueber den Schalter oben
                            Button(role: .destructive) { askRemove = d } label: { Image(systemName: "trash") }
                                .buttonStyle(.borderless).help(Text("Entfernen")).accessibilityLabel(Text("Entfernen"))
                        }
                    }
                    if let t = tests[d.id] { Text(verbatim: t).font(.caption).foregroundStyle(t.hasPrefix("✓") ? .green : .secondary) }
                }
            }
        } header: { Text("Geräte") }
        .alert("Gerät entfernen?", isPresented: Binding(get: { askRemove != nil }, set: { if !$0 { askRemove = nil } }), presenting: askRemove) { d in
            Button("Entfernen", role: .destructive) { Task { await push.remove(d.id) } }
            Button("Abbrechen", role: .cancel) {}
        } message: { d in Text("„\(d.name.isEmpty ? Self.serviceText(d) : d.name)“ bekommt dann keine Mitteilungen mehr.") }
    }

    static func serviceText(_ d: PushDevice) -> String {
        switch PushLogic.service(type: d.type, whereText: d.whereText) {
        case .sagaApp(let test): return test ? String(localized: "Saga-App (Testversion)") : String(localized: "Saga-App")
        case .sagaCalls(let test): return test ? String(localized: "Saga-App: Anrufe (Testversion)") : String(localized: "Saga-App: Anrufe")
        case .other(let w): return w
        }
    }
    static func catLabel(_ c: String) -> String {
        switch c {
        case "share": return String(localized: "Teilen: jemand teilt eine Seite mit mir oder ändert meine Rolle")
        case "comment": return String(localized: "Kommentare und Antworten")
        case "chat": return String(localized: "Chat-Nachrichten")
        case "publish": return String(localized: "Veröffentlichung: ein Admin zieht etwas zurück")
        case "call": return String(localized: "Anrufe")
        case "admin": return String(localized: "Admin: Backups des Servers fehlgeschlagen")
        case "tasks": return String(localized: "Aufgaben heute fällig oder überfällig")
        case "jobs": return String(localized: "Rückmeldungen der KI zu Aufträgen")     // 2270
        default: return c
        }
    }
    static func testText(_ r: PushLogic.TestResult) -> String {
        switch r {
        case .ok: return String(localized: "✓ Zugestellt")
        case .fail(let e): return String(localized: "Nicht zugestellt: \(e)")
        case .tooFast: return String(localized: "Bitte ein paar Sekunden warten.")
        }
    }
    /// "HH:MM" <-> Datum (heute) fuer den DatePicker; Zeitzone = Geraetezeitzone
    private func timeBinding(_ v: String, key: String) -> Binding<Date> {
        Binding(get: {
            let p = v.split(separator: ":").compactMap { Int($0) }
            return Calendar.current.date(bySettingHour: p.first ?? 22, minute: p.count > 1 ? p[1] : 0, second: 0, of: Date()) ?? Date()
        }, set: { d in
            let c = Calendar.current.dateComponents([.hour, .minute], from: d)
            let s = String(format: "%02d:%02d", c.hour ?? 0, c.minute ?? 0)
            Task { await push.save(["quiet": [key: s, "tz": TimeZone.current.identifier]]) }
        })
    }
}
