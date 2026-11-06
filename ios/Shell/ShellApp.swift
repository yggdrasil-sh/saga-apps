import AppKit
import CoreSpotlight
import SwiftUI
import UserNotifications

/// Saga-Desktop-Huelle fuer macOS (P2). Bis zur Umstellung (P3) Test-Ziel mit eigener App-ID neben der normalen Saga-App.
@main
struct SagaShellApp: App {
    @NSApplicationDelegateAdaptor(ShellAppDelegate.self) private var appDelegate
    @StateObject private var model = ShellModel.shared

    var body: some Scene {
        Window("Saga", id: "main") {
            ShellRootView(model: model)
                .frame(minWidth: 640, minHeight: 440)
        }
        .defaultSize(width: 1280, height: 840)
        .commands { ShellCommands(model: model) }
        MenuBarExtra("Saga", systemImage: "book") { ShellCaptureView() }       // Schnellerfassung (App-Schluessel)
            .menuBarExtraStyle(.window)
    }
}

@MainActor
final class ShellAppDelegate: NSObject, NSApplicationDelegate {
    func applicationDidFinishLaunching(_ n: Notification) {
        UNUserNotificationCenter.current().delegate = ShellNotificationDelegate.shared
        ShellUpdater.shared.start()
        ShellModel.shared.start()
    }
    func application(_ app: NSApplication, open urls: [URL]) { for u in urls { ShellModel.shared.handle(link: u) } }
    func application(_ app: NSApplication, didRegisterForRemoteNotificationsWithDeviceToken t: Data) { ShellModel.shared.push.didRegister(t) }
    func application(_ app: NSApplication, didFailToRegisterForRemoteNotificationsWithError e: Error) { ShellModel.shared.push.didFail(e) }
    /// Mitteilung kommt an, waehrend die App laeuft: klingelnder Anruf -> Dock springt (bis die App vorne ist)
    func application(_ app: NSApplication, didReceiveRemoteNotification info: [String: Any]) {
        if ShellLogic.isRinging(kind: info["kind"]), !app.isActive { app.requestUserAttention(.criticalRequest) }
    }
    /// Spotlight-Treffer
    func application(_ app: NSApplication, continue a: NSUserActivity, restorationHandler: @escaping ([any NSUserActivityRestoring]) -> Void) -> Bool {
        guard a.activityType == CSSearchableItemActionType,
              let p = ShellLogic.spotlightPath(a.userInfo?[CSSearchableItemActivityIdentifier] as? String) else { return false }
        ShellModel.shared.openPath(p); NSApp.activate()
        return true
    }
    func applicationShouldTerminateAfterLastWindowClosed(_ app: NSApplication) -> Bool { false }
}

/// Menue: nur wenige eigene Befehle. Kuerzel des Webs (⌘K, ⌘/, ⌘[ …) bleiben frei; ⌘, oeffnet die Web-Einstellungen.
struct ShellCommands: Commands {
    @ObservedObject var model: ShellModel

    var body: some Commands {
        CommandGroup(after: .appInfo) { ShellUpdateButton() }
        CommandGroup(replacing: .appSettings) {
            Button(String(localized: "Einstellungen …")) { model.openWebSettings() }
                .keyboardShortcut(",", modifiers: .command)
                .disabled(model.phase != .web)
            Button(String(localized: "App-Einstellungen …")) { model.showSettings = true }
                .keyboardShortcut(",", modifiers: [.command, .option])
        }
        CommandGroup(replacing: .newItem) {}
        CommandGroup(after: .toolbar) {
            Button(String(localized: "Neu laden")) { model.reload() }
                .keyboardShortcut("r", modifiers: .command)
                .disabled(model.phase != .web)
            Divider()
            Button(String(localized: "Originalgröße")) { model.webView.pageZoom = 1 }
                .keyboardShortcut("0", modifiers: .command)
            Button(String(localized: "Vergrößern")) { model.webView.pageZoom = min(3, model.webView.pageZoom + 0.1) }
                .keyboardShortcut("+", modifiers: .command)
            Button(String(localized: "Verkleinern")) { model.webView.pageZoom = max(0.5, model.webView.pageZoom - 0.1) }
                .keyboardShortcut("-", modifiers: .command)
            Divider()
            Button(String(localized: "Zurück")) { model.webView.goBack() }.disabled(model.phase != .web)
            Button(String(localized: "Vorwärts")) { model.webView.goForward() }.disabled(model.phase != .web)
        }
        CommandGroup(before: .appTermination) {
            Button(String(localized: "Abmelden …")) { model.confirmSignOut() }
                .disabled(model.phase != .web)
            Divider()
        }
    }
}
