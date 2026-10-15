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
                .handlesExternalEvents(preferring: ["*"], allowing: ["*"])   // Widget-Links im offenen Fenster, kein zweites
        }
            .handlesExternalEvents(matching: ["*"])
            .commands {
                CommandGroup(after: .toolbar) { Button("Aktualisieren") { Task { await store.refresh() } }.keyboardShortcut("r") }
                CommandGroup(after: .appSettings) { Button("Abmelden") { Task { await store.logout() } } }
                HKMenuCommands()
                HKEditorCommands()
            }
        Settings { SettingsView().environmentObject(store).frame(width: 600, height: 720) }
        MenuBarExtra("Saga", systemImage: "book", isInserted: .constant(!SnapshotHelper.active)) { QuickCaptureView() }     // Schnellerfassung in der Menueleiste (nicht bei Test-Bildern)
            .menuBarExtraStyle(.window)
        #else
        WindowGroup { RootView(store: store) }
            .commands { HKMenuCommands(); HKEditorCommands() }                     // iPad mit Tastatur
        #endif
    }
}

#if os(macOS)
/// Beenden (⌘Q): erst offene Seiten speichern - hoechstens 8 Sekunden warten (Entwurf liegt ohnehin lokal)
final class AppDelegate: NSObject, NSApplicationDelegate {
    func applicationDidFinishLaunching(_ notification: Notification) {
        UNUserNotificationCenter.current().delegate = PushCenterDelegate.shared       // Mitteilungen: Antippen, Anzeige im Vordergrund
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
