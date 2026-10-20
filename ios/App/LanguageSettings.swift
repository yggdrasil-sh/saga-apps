import SwiftUI
#if os(iOS)
import UIKit
#else
import AppKit
#endif

/// Sprache der App (Automatisch/Deutsch/English/Spanisch/Thai/Russisch).
/// iOS/iPadOS: Systemfunktion je App (Einstellungen-App -> Saga -> Sprache), hier nur Anzeige + Sprung dorthin.
/// Mac: Auswahl in der App (AppleLanguages der App-Domaene), wirkt nach Neustart.
/// Accept-Language der Anfragen folgt automatisch (AppConfig.appLanguage = Bundle.preferredLocalizations).
enum AppLanguage {
    /// Sprache, in der die App gerade laeuft ("de", "en", "es", "th", "ru")
    static var running: String { AppLanguageChoice.code(Bundle.main.preferredLocalizations.first ?? "en") }
    static var runningName: String { AppLanguageChoice(rawValue: running)?.nativeName ?? running }

    #if os(macOS)
    /// Gespeicherte Wahl (nur die eigene Domaene der App - Startparameter wie -AppleLanguages zaehlen nicht)
    static var choice: AppLanguageChoice {
        get {
            let dom = UserDefaults.standard.persistentDomain(forName: Bundle.main.bundleIdentifier ?? "")
            return AppLanguageChoice.from(appleLanguages: dom?["AppleLanguages"] as? [String])
        }
        set {
            if let l = newValue.appleLanguages { UserDefaults.standard.set(l, forKey: "AppleLanguages") }
            else { UserDefaults.standard.removeObject(forKey: "AppleLanguages") }
        }
    }
    /// Bevorzugte Sprachen des Systems (global, ohne die Wahl der App)
    static var systemLanguages: [String] {
        UserDefaults.standard.persistentDomain(forName: UserDefaults.globalDomain)?["AppleLanguages"] as? [String]
            ?? Locale.preferredLanguages
    }
    static var needsRestart: Bool { choice.needsRestart(system: systemLanguages, running: running) }

    /// Saga neu starten: wartet, bis diese Instanz beendet ist, und oeffnet die App erneut
    static func relaunch() {
        let p = Process()
        p.executableURL = URL(fileURLWithPath: "/bin/sh")
        p.arguments = ["-c", "while kill -0 \"$1\" 2>/dev/null; do sleep 0.2; done; /usr/bin/open \"$2\"", "sh",
                       String(ProcessInfo.processInfo.processIdentifier), Bundle.main.bundlePath]
        do { try p.run() } catch {                               // Rueckfall: zweite Instanz starten, dann beenden
            let cfg = NSWorkspace.OpenConfiguration(); cfg.createsNewApplicationInstance = true
            NSWorkspace.shared.openApplication(at: Bundle.main.bundleURL, configuration: cfg) { _, _ in }
        }
        NSApp.terminate(nil)
    }
    #endif
}

/// Abschnitt "Sprache" in den Einstellungen
struct LanguageSettingsSection: View {
    #if os(macOS)
    @State private var choice = AppLanguage.choice
    @State private var restart = AppLanguage.needsRestart
    #endif

    var body: some View {
        Section {
            #if os(iOS)
            Button { openSystemSettings() } label: {
                LabeledContent("Sprache") {
                    HStack(spacing: 6) {
                        Text(verbatim: AppLanguage.runningName)
                        Image(systemName: "arrow.up.forward.app").foregroundStyle(.tint).accessibilityHidden(true)
                    }
                }
            }.tint(.primary)
            // VoiceOver las "Sprache, Deutsch, Sprache" (N13): eine Bezeichnung, ein Wert, ein Hinweis
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(Text("Sprache"))
            .accessibilityValue(Text(verbatim: AppLanguage.runningName))
            .accessibilityHint(Text("Öffnet die Einstellungen-App."))
            .accessibilityAddTraits(.isButton)
            #else
            Picker("Sprache", selection: $choice) {
                ForEach(AppLanguageChoice.allCases) { c in
                    if let n = c.nativeName { Text(verbatim: n).tag(c) } else { Text("Automatisch (System)").tag(c) }
                }
            }
            .onChange(of: choice) { _, c in AppLanguage.choice = c; restart = AppLanguage.needsRestart }
            if restart {
                HStack {
                    Text("Saga neu starten, um die Sprache zu wechseln.").font(.footnote)
                    Spacer()
                    Button("Jetzt neu starten") { AppLanguage.relaunch() }
                }
            }
            #endif
        } header: { Text("Sprache") } footer: {
            #if os(iOS)
            Text("Saga folgt der Systemsprache. Eine eigene Sprache nur für Saga wählst du in der Einstellungen-App unter Saga → Sprache.")
            #else
            Text("Gilt nur für Saga auf diesem Mac. „Automatisch“ folgt der Systemsprache.")
            #endif
        }
        .id("sprache")
    }
}

/// Kleiner Sprachumschalter fuer den Anmeldebildschirm (fremder Rechner: Sprache vor der Anmeldung waehlen)
struct LanguageQuickSwitch: View {
    #if os(macOS)
    @State private var choice = AppLanguage.choice
    @State private var restart = AppLanguage.needsRestart
    #endif

    var body: some View {
        #if os(iOS)
        Button { openSystemSettings() } label: {
            Label { Text(verbatim: AppLanguage.runningName) } icon: { Image(systemName: "globe") }
        }
        .font(.footnote).buttonStyle(.borderless)
        .accessibilityLabel(Text("Sprache: \(AppLanguage.runningName)"))
        #else
        VStack(spacing: 6) {
            Picker(selection: $choice) {
                ForEach(AppLanguageChoice.allCases) { c in
                    if let n = c.nativeName { Text(verbatim: n).tag(c) } else { Text("Automatisch (System)").tag(c) }
                }
            } label: { Image(systemName: "globe").accessibilityLabel(Text("Sprache")) }
            .pickerStyle(.menu).fixedSize()
            .onChange(of: choice) { _, c in AppLanguage.choice = c; restart = AppLanguage.needsRestart }
            if restart {
                Button("Saga neu starten, um die Sprache zu wechseln") { AppLanguage.relaunch() }
                    .font(.footnote).buttonStyle(.link)
            }
        }
        #endif
    }
}

#if os(iOS)
/// Einstellungen-App bei Saga oeffnen (dort: Sprache)
@MainActor private func openSystemSettings() {
    if let u = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(u) }
}
#endif
