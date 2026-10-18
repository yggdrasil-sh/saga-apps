import SwiftUI
import WidgetKit

/// Einstellungen der App: Server und App-Schluessel (fuer Widget und Teilen-Erweiterung).
struct SettingsView: View {
    var onLogout: (() -> Void)? = nil
    @Environment(\.dismiss) private var dismiss
    @State private var server = ShotDisplay.server(AppConfig.serverString)
    @State private var token = AppConfig.token
    @State private var status = ""
    @State private var busy = false
    @State private var askSwitch: URL?                    // anderer Server: erst nach Rueckfrage (meldet vom alten ab)
    @State private var crop: CropSource?                  // Profilbild zuschneiden (2.1)
    @State private var cropped: Data?
    #if os(macOS)
    @AppStorage("hk.coverHeight") private var coverHeight: Double = 200
    #endif

    var body: some View {
        ScrollViewReader { proxy in
            form
            #if DEBUG
            .task {                                                      // nur fuer Tests: zum Konto-Block (Startparameter -hkSettings konto)
                guard let v = UserDefaults.standard.string(forKey: "hkSettings"), v != "YES" else { return }
                try? await Task.sleep(for: .seconds(2)); proxy.scrollTo(v, anchor: v == "konto" ? .center : .top)
                try? await Task.sleep(for: .seconds(2)); proxy.scrollTo(v, anchor: v == "konto" ? .center : .top)   // Zeilen kommen ggf. erst nach dem Laden
            }
            #endif
        }
    }

    private var form: some View {
        Form {
            Section("Server") {
                TextField("https://saga.example.org", text: $server)
                    #if os(iOS)
                    .keyboardType(.URL).textInputAutocapitalization(.never)
                    #endif
                    .autocorrectionDisabled()
                    .onSubmit { applyServer() }
                if server.trimmingCharacters(in: .whitespacesAndNewlines) != AppConfig.serverString {
                    Button("Server übernehmen …") { applyServer() }
                }
            }
            Section {
                SecureField("hk_…", text: $token)
                Text("Den Schlüssel erzeugst du im Web unter 👤 → Einstellungen → „Apps & Geräte“. In der App genügt dort „In dieser App verwenden“.")
                    .font(.footnote).foregroundStyle(.secondary)
            } header: { Text("App-Schlüssel (Widget & Teilen)") }
            LanguageSettingsSection()                     // Sprache der App (Automatisch/de/en/es)
            #if os(macOS)
            Section("Darstellung") {
                LabeledContent("Titelbild-Höhe") {
                    HStack {
                        Slider(value: $coverHeight, in: 100...600, step: 10)
                        Text("\(Int(coverHeight)) pt").monospacedDigit().frame(width: 52, alignment: .trailing)
                        Button("Standard") { coverHeight = 200 }
                    }
                }
                Text("Gilt für alle Seiten auf diesem Mac. Alternativ die Unterkante des Titelbilds ziehen.").font(.footnote).foregroundStyle(.secondary)
            }
            #endif
            Section {
                Button(busy ? "Prüfe …" : "Verbindung testen") { Task { await test() } }.disabled(busy)
                if !status.isEmpty { Text(status).font(.footnote) }
            }
            Section("Über") {
                LabeledContent("App", value: "Saga \(Store.appVersion) (\(Store.appBuild))")
                LabeledContent("Server", value: store.serverVersion.map { "Saga \($0)" } ?? "–")
                Text("Anrufe nutzen WebRTC (libwebrtc, © The WebRTC project authors, BSD-Lizenz) und libsodium (ISC-Lizenz).")
                    .font(.footnote).foregroundStyle(.secondary)
                if store.appTooOld, let m = store.minApp {
                    Text("Diese App ist älter als der Server erwartet (ab \(m)). Bitte die App aktualisieren.").font(.footnote).foregroundStyle(.orange)
                }
            }
            PublicNameSection()                           // Konto: oeffentlicher Name (Veroeffentlichen)
            AvatarSettingsSection(crop: $crop, cropped: $cropped)   // Profilbild (2.1)
            SecuritySettingsSection()                     // Anmeldung & Sicherheit (2.1, verwaltet im Browser)
            NotificationSettingsSection()                 // Benachrichtigungen (Glocke, 1.4)
            PushSettingsSection(push: store.push)         // Mitteilungen aufs Geraet (1.7)
            ChatDevicesSection(chat: store.chat)          // Chats & Geraete (2.0)
            ImmichSettingsSection()                       // wie im Web: 👤 → Einstellungen
            CalendarSettingsSection()
            AppsDevicesSection()
            if let onLogout {
                Section { Button("Abmelden", role: .destructive) { loggingOut = true; dismiss(); onLogout() } } footer: { Text("Löscht die Anmeldung und den Zwischenspeicher auf diesem Gerät.") }
            }
        }
        .navigationTitle("Einstellungen")
        .sheet(item: $crop) { c in AvatarCropSheet(source: c) { jpeg in cropped = jpeg } }
        .task { if store.chat.state == .loading { await store.chat.load() } }
        .confirmationDialog("Server wechseln?", isPresented: Binding(get: { askSwitch != nil }, set: { if !$0 { askSwitch = nil } }), titleVisibility: .visible) {
            Button("Abmelden und wechseln", role: .destructive) {
                if let u = askSwitch { askSwitch = nil; Task { await store.switchServer(to: u); server = AppConfig.serverString; dismiss() } }
            }
            Button("Abbrechen", role: .cancel) { askSwitch = nil; server = AppConfig.serverString }
        } message: {
            Text("Du wirst vom bisherigen Server abgemeldet. Anmeldung, Schlüssel und Zwischenspeicher auf diesem Gerät werden gelöscht.")
        }
        .toolbar {
            #if os(iOS)
            ToolbarItem(placement: .confirmationAction) { Button("Fertig") { save(); dismiss() } }
            #else
            if onLogout != nil {                                   // Mac als Blatt (nicht das Einstellungen-Fenster): "Fertig" unten
                ToolbarItem(placement: .confirmationAction) { Button("Fertig") { save(); dismiss() } }
            }
            #endif
        }
        #if os(macOS)
        .formStyle(.grouped)
        #endif
        .onDisappear { save() }                                   // auch iOS: Blatt weggewischt statt "Fertig" (BUG-101)
    }

    @EnvironmentObject private var store: Store
    /// Server-Adresse uebernehmen: gleicher Server -> sofort; anderer Server -> nachfragen, dann sauber abmelden
    private func applyServer() {
        guard let u = AppConfig.normalizedServer(server) else {
            status = String(localized: "Bitte eine https-Adresse eingeben, z. B. https://saga.example.org"); return
        }
        guard u != AppConfig.server else { server = AppConfig.serverString; return }
        if let cur = AppConfig.serverOrigin, cur != AppConfig.origin(u) { askSwitch = u }
        else { Task { await store.switchServer(to: u); server = AppConfig.serverString } }
    }
    /// Nur den App-Schluessel sichern - die Server-Adresse aendert ausschliesslich "Server uebernehmen"
    /// Schluessel beim Oeffnen: nur eine echte Eingabe schreiben - sonst setzte das Schliessen nach "Abmelden" den eben
    /// geloeschten Schluessel wieder ein
    @State private var tokenAtOpen = AppConfig.token
    @State private var loggingOut = false
    private func save() {
        guard token != tokenAtOpen, !loggingOut else { return }
        tokenAtOpen = token
        if !(token.isEmpty && AppConfig.hasToken) && token != AppConfig.token { AppConfig.token = token }   // automatisch eingetragenen Schluessel nicht leeren
        WidgetCenter.shared.reloadAllTimelines()
    }
    private func test() async {
        save(); busy = true; defer { busy = false }
        do {
            let d = try await API.today()
            status = String(localized: "✓ Verbunden – Termine heute: \(d.events.count) · offene Aufgaben: \(d.tasks.count)")
        } catch { status = "✗ " + error.localizedDescription }
    }
}
