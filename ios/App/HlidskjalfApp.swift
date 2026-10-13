import SwiftUI

@main
struct HlidskjalfApp: App {
    @StateObject private var store = Store()

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
