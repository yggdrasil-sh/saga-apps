import AppKit
import SwiftUI

/// Saga-Desktop-Huelle fuer macOS (P2, Etappe 1). Test-Ziel mit eigener App-ID neben der normalen Saga-App.
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
    }
}

@MainActor
final class ShellAppDelegate: NSObject, NSApplicationDelegate {
    func applicationDidFinishLaunching(_ n: Notification) { ShellModel.shared.start() }
    func application(_ app: NSApplication, open urls: [URL]) { for u in urls { ShellModel.shared.handle(link: u) } }
    func applicationShouldTerminateAfterLastWindowClosed(_ app: NSApplication) -> Bool { false }
}

/// Menue: nur wenige eigene Befehle. Kuerzel des Webs (⌘K, ⌘/, ⌘[ …) bleiben frei; ⌘, oeffnet die Web-Einstellungen.
struct ShellCommands: Commands {
    @ObservedObject var model: ShellModel

    var body: some Commands {
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
