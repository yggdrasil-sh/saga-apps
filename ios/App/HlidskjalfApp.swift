import SwiftUI

@main
struct HlidskjalfApp: App {
    @StateObject private var store = Store()
    #if os(macOS)
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
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
        MenuBarExtra("Saga", systemImage: "book") { QuickCaptureView() }     // Schnellerfassung in der Menueleiste
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
