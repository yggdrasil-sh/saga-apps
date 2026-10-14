import SwiftUI
import WidgetKit

/// Einstellungen der App: Server und App-Schluessel (fuer Widget und Teilen-Erweiterung).
struct SettingsView: View {
    var onLogout: (() -> Void)? = nil
    @Environment(\.dismiss) private var dismiss
    @State private var server = AppConfig.serverString
    @State private var token = AppConfig.token
    @State private var status = ""
    @State private var busy = false
    @State private var askSwitch: URL?                    // anderer Server: erst nach Rueckfrage (meldet vom alten ab)
    #if os(macOS)
    @AppStorage("hk.coverHeight") private var coverHeight: Double = 200
    #endif

    var body: some View {
        ScrollViewReader { proxy in
            form
            #if DEBUG
            .task {                                                      // nur fuer Tests: zum Konto-Block (Startparameter -hkSettings konto)
                guard UserDefaults.standard.string(forKey: "hkSettings") == "konto" else { return }
                try? await Task.sleep(for: .seconds(2)); proxy.scrollTo("konto", anchor: .center)
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
                if store.appTooOld, let m = store.minApp {
                    Text("Diese App ist älter als der Server erwartet (ab \(m)). Bitte die App aktualisieren.").font(.footnote).foregroundStyle(.orange)
                }
            }
            PublicNameSection()                           // Konto: oeffentlicher Name (Veroeffentlichen)
            ImmichSettingsSection()                       // wie im Web: 👤 → Einstellungen
            CalendarSettingsSection()
            AppsDevicesSection()
            if let onLogout {
                Section { Button("Abmelden", role: .destructive) { dismiss(); onLogout() } } footer: { Text("Löscht die Anmeldung und den Zwischenspeicher auf diesem Gerät.") }
            }
        }
        .navigationTitle("Einstellungen")
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
            #endif
        }
        #if os(macOS)
        .formStyle(.grouped)
        .onDisappear { save() }
        #endif
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
    private func save() {
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
