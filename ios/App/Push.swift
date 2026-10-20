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

    func signedOut() { state = nil; message = nil }                 // Abo verschwindet beim Abmelden auf dem Server

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
        let body = PushLogic.subscription(token: t, env: Self.env, name: DeviceKey.deviceName)
        do {
            let (d, code) = try await store.apiResponse("api/push/subscriptions", method: "POST", body: body)
            if !(200..<300).contains(code) { message = ShareJSON.obj(d).flatMap { ShareJSON.str($0["error"]) } ?? "\(code)" }
            await load()
            // aeltere Abos derselben App-Anmeldung (frueheres Token) entfernen - sonst steht das Geraet doppelt in der Liste
            if (200..<300).contains(code), let keep = ShareJSON.obj(d).flatMap({ ShareJSON.int($0["id"]) }), let s = state {
                let dup = PushLogic.duplicates(s.devices, keep: keep)
                for id in dup { _ = try? await store.api("api/push/subscriptions/\(id)", method: "DELETE") }
                if !dup.isEmpty { await load() }
            }
            return
        } catch { message = error.localizedDescription }
        await load()
    }

    /// Anrufe (2.1, iOS): PushKit-Token als eigenes Abo "apns-voip" - der Server schickt dorthin nur klingelnde Anrufe
    func voipTokenChanged(_ hex: String) { if wanted { Task { await subscribeVoIP() } } }
    private func subscribeVoIP() async {
        #if os(iOS)
        guard CallEnv.callKit, let t = CallSystem.shared.voipToken, let store, AppConfig.hasServer, !store.needsLogin else { return }
        let body = PushLogic.subscription(token: t, env: Self.env, name: DeviceKey.deviceName, type: "apns-voip")
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

    /// Mitteilung angetippt: `url` relativ zu BASE_URL -> Ziel oeffnen, Benachrichtigung als gelesen markieren
    nonisolated static func handleTap(_ userInfo: [AnyHashable: Any], action: String = UNNotificationDefaultActionIdentifier) {
        // Anruf (2.1): klingeln/annehmen. Alles andere (z. B. Chat-Nachricht WAEHREND eines Anrufs) oeffnet nur das Ziel;
        // RootView verkleinert dafuer zuerst die Anruf-Oberflaeche (2.3.1) - der Anruf selbst bleibt unberuehrt.
        if case .call = CallLogic.tapRoute(kind: userInfo["kind"] as? String, url: userInfo["url"] as? String, call: userInfo["call"] as? String,
                                           phase: "idle", minimized: false, callKit: false) {
            let info = userInfo.reduce(into: [AnyHashable: Any]()) { r, kv in if kv.value is String || kv.value is NSNumber { r[kv.key] = kv.value } }
            let a = action
            Task { @MainActor in CallSystem.shared.notificationAction(a, info: info) }
            return
        }
        let t = NotifLogic.target(url: userInfo["url"] as? String, kind: userInfo["kind"] as? String)
        Task { @MainActor in
            PushModel.pending = t                                   // Kaltstart: RootView holt es beim Erscheinen ab
            NotificationCenter.default.post(name: .hkOpenTarget, object: t)
            await PushModel.current?.store?.notifs.markReadFor(target: t)
        }
    }
}

/// Mitteilungen: Anzeige im Vordergrund und Antippen
final class PushCenterDelegate: NSObject, UNUserNotificationCenterDelegate {
    static let shared = PushCenterDelegate()
    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification) async -> UNNotificationPresentationOptions {
        [.banner, .list, .sound]
    }
    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        PushModel.handleTap(response.notification.request.content.userInfo, action: response.actionIdentifier)
    }
}

#if os(iOS)
final class IOSAppDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        UNUserNotificationCenter.current().delegate = PushCenterDelegate.shared
        ImagePaste.install()                                                         // Bilder aus der Zwischenablage (Seite, Chat)
        MainActor.assumeIsolated { CallSystem.shared.setup() }                       // Anrufe: PushKit (VoIP) + Mitteilungs-Aktionen
        return true
    }
    func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        Task { @MainActor in PushModel.current?.didRegister(token: deviceToken) }
    }
    func application(_ application: UIApplication, didFailToRegisterForRemoteNotificationsWithError error: Error) {
        Task { @MainActor in PushModel.current?.didFail(error) }
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
                let av = PushLogic.availability(push.state)
                // Schalter und Zustand duerfen sich nicht widersprechen: nur AN, wenn der Server zustellen kann
                let on = av == .ok && push.wanted && push.state?.thisDevice != nil
                Toggle(isOn: Binding(get: { on }, set: { v in Task { await push.setEnabled(v) } })) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Mitteilungen auf diesem Gerät")
                        if let d = push.state?.thisDevice, let e = d.lastError { Text("Letzter Fehler: \(e)").font(.caption).foregroundStyle(.orange) }
                    }
                }
                .disabled(push.busy || av != .ok)
                .id("push")
                switch av {
                case .serverOff: Label("Mitteilungen sind auf diesem Server abgeschaltet.", systemImage: "bell.slash").font(.footnote).foregroundStyle(.secondary)
                case .appleMissing: Label("Mitteilungen an die Apple-App sind auf diesem Server noch nicht eingerichtet (Admin-Bereich).", systemImage: "info.circle").font(.footnote).foregroundStyle(.secondary)
                default: EmptyView()
                }
                if let m = push.message { Text(m).font(.footnote).foregroundStyle(.secondary) }
                // "Worueber?" nur, wenn Mitteilungen eingeschaltet sind UND der Server zustellen kann - sonst wirkten
                // eingeschaltete Themen-Schalter, als kaeme etwas an (BUG-102)
                if let s = push.state, s.enabled, av == .ok { options(s, enabled: on) }
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
