#if os(macOS)
import SwiftUI
import Sparkle

// Selbst-Update der Mac-App ueber Sparkle 2 (Standard-Oberflaeche: Installieren / Spaeter / Ueberspringen).
// Nur in DMG-Builds aktiv (UpdateLogic.isEnabled); sonst wird Sparkle nie gestartet.
// Sandbox: Installation ueber den Installer-XPC-Dienst von Sparkle (SUEnableInstallerLauncherService und die
// mach-lookup-Ausnahmen <App-ID>-spks/-spki setzt build-dmg.sh). Appcast und DMG sind mit EdDSA signiert.
@MainActor
final class AppUpdater: NSObject, ObservableObject, SPUUpdaterDelegate {
    static let shared = AppUpdater()

    let enabled = UpdateLogic.isEnabled(Bundle.main.infoDictionary ?? [:])
    @Published private(set) var canCheck = false
    @Published private(set) var automaticChecks = true
    private var controller: SPUStandardUpdaterController?

    /// Beim Start der App (applicationDidFinishLaunching) - plant die automatische Pruefung
    func start() {
        guard enabled, controller == nil else { return }
        let c = SPUStandardUpdaterController(startingUpdater: true, updaterDelegate: self, userDriverDelegate: nil)
        controller = c
        c.updater.publisher(for: \.canCheckForUpdates).receive(on: RunLoop.main).assign(to: &$canCheck)
        automaticChecks = c.updater.automaticallyChecksForUpdates
    }

    /// Menue "Nach Updates suchen …"
    func checkForUpdates() { controller?.checkForUpdates(nil) }

    /// Schalter in den Einstellungen (Vorgabe an: SUEnableAutomaticChecks)
    func setAutomaticChecks(_ on: Bool) {
        controller?.updater.automaticallyChecksForUpdates = on
        automaticChecks = controller?.updater.automaticallyChecksForUpdates ?? on
    }
}

/// Menue Saga -> "Nach Updates suchen …" (nur in DMG-Builds sichtbar)
struct CheckForUpdatesButton: View {
    @ObservedObject var updater = AppUpdater.shared
    var body: some View {
        if updater.enabled {
            Button("Nach Updates suchen …") { updater.checkForUpdates() }.disabled(!updater.canCheck)
        }
    }
}

/// Einstellungen: automatische Pruefung ein/aus (nur in DMG-Builds sichtbar)
struct UpdateSettingsSection: View {
    @ObservedObject var updater = AppUpdater.shared
    var body: some View {
        if updater.enabled {
            Section("Updates") {
                Toggle("Automatisch nach Updates suchen", isOn: Binding(get: { updater.automaticChecks }, set: { updater.setAutomaticChecks($0) }))
                HStack {
                    Text(UpdateLogic.isPrerelease(Store.appVersionLabel)
                         ? "Diese Testversion erhält auch neue Testversionen."
                         : "Diese Version erhält nur stabile Versionen.")
                        .font(.footnote).foregroundStyle(.secondary)
                    Spacer()
                    Button("Jetzt suchen") { updater.checkForUpdates() }.disabled(!updater.canCheck)
                }
            }
        }
    }
}
#endif
