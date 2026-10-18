import SwiftUI
import UserNotifications

@main
struct HlidskjalfApp: App {
    @StateObject private var store = Store()
    #if os(macOS)
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    #else
    @UIApplicationDelegateAdaptor(IOSAppDelegate.self) private var appDelegate   // Mitteilungen (APNs-Token, Antippen)
    #endif

    var body: some Scene {
        #if os(macOS)
        WindowGroup {
            RootView(store: store)
                .environment(\.calendar, SagaDate.calendar)                    // Datumswahl gregorianisch (Thai: nicht 2569)
                .handlesExternalEvents(preferring: ["*"], allowing: ["*"])   // Widget-Links im offenen Fenster, kein zweites
        }
            .handlesExternalEvents(matching: ["*"])
            .commands {
                // "Über Saga" mit der angezeigten Version (2.2.0-rc.N in Testbuilds) statt der nackten CFBundleShortVersionString
                CommandGroup(replacing: .appInfo) {
                    Button("Über Saga") {
                        NSApp.orderFrontStandardAboutPanel(options: [.applicationVersion: Store.appVersionLabel, .version: Store.appBuild])
                    }
                }
                CommandGroup(after: .toolbar) { Button("Aktualisieren") { Task { await store.refresh() } }.keyboardShortcut("r") }
                CommandGroup(after: .appSettings) { Button("Abmelden") { Task { await store.logout() } } }
                HKMenuCommands()
                HKEditorCommands()
            }
        Settings { SettingsView().environmentObject(store).environment(\.calendar, SagaDate.calendar).frame(width: 600, height: 720) }
        Window("Anruf", id: "call") { CallWindowContent().environmentObject(store) }      // Anrufe (2.1): eigenes Fenster statt CallKit
            .windowResizability(.contentMinSize)
            .defaultSize(width: 460, height: 640)
        MenuBarExtra("Saga", systemImage: "book", isInserted: .constant(!SnapshotHelper.active)) { QuickCaptureView() }     // Schnellerfassung in der Menueleiste (nicht bei Test-Bildern)
            .menuBarExtraStyle(.window)
        #else
        WindowGroup { RootView(store: store).environment(\.calendar, SagaDate.calendar) }     // Datumswahl gregorianisch
            .commands { HKMenuCommands(); HKEditorCommands() }                     // iPad mit Tastatur
        #endif
    }
}

#if os(macOS)
/// Beenden (⌘Q): erst offene Seiten speichern - hoechstens 8 Sekunden warten (Entwurf liegt ohnehin lokal)
final class AppDelegate: NSObject, NSApplicationDelegate {
    func applicationDidFinishLaunching(_ notification: Notification) {
        UNUserNotificationCenter.current().delegate = PushCenterDelegate.shared       // Mitteilungen: Antippen, Anzeige im Vordergrund
        ImagePaste.install()                                                         // Bilder aus der Zwischenablage (Seite, Chat)
        CallSystem.shared.setup()                                                    // Anrufe: Mitteilungs-Aktionen Annehmen/Ablehnen
        #if DEBUG
        SnapshotHelper.setUp()                                                       // nur fuer Tests: Fenster unsichtbar abbilden
        #endif
    }
    func application(_ application: NSApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        Task { @MainActor in PushModel.current?.didRegister(token: deviceToken) }
    }
    func application(_ application: NSApplication, didFailToRegisterForRemoteNotificationsWithError error: Error) {
        Task { @MainActor in PushModel.current?.didFail(error) }
    }
    func applicationShouldTerminate(_ sender: NSApplication) -> NSApplication.TerminateReply {
        guard MainActor.assumeIsolated({ SaveRegistry.hasUnsaved }) else { return .terminateNow }
        let once = Once(sender)
        Task { @MainActor in await SaveRegistry.flushAll(); once.reply() }
        Task { @MainActor in try? await Task.sleep(for: .seconds(8)); once.reply() }
        return .terminateLater
    }
    @MainActor private final class Once {
        let app: NSApplication; var done = false
        init(_ app: NSApplication) { self.app = app }
        func reply() { if !done { done = true; app.reply(toApplicationShouldTerminate: true) } }
    }
}
#endif
