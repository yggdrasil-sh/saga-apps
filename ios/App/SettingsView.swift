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
    #if os(macOS)
    @AppStorage("hk.coverHeight") private var coverHeight: Double = 200
    #endif

    var body: some View {
        Form {
            Section("Server") {
                TextField("https://saga.example.org", text: $server)
                    #if os(iOS)
                    .keyboardType(.URL).textInputAutocapitalization(.never)
                    #endif
                    .autocorrectionDisabled()
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
            ImmichSettingsSection()                       // wie im Web: 👤 → Einstellungen
            CalendarSettingsSection()
            AppsDevicesSection()
            if let onLogout {
                Section { Button("Abmelden", role: .destructive) { dismiss(); onLogout() } } footer: { Text("Löscht die Anmeldung und den Zwischenspeicher auf diesem Gerät.") }
            }
        }
        .navigationTitle("Einstellungen")
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
    private func save() {
        if let u = URL(string: server.trimmingCharacters(in: .whitespacesAndNewlines).trimmingCharacters(in: CharacterSet(charactersIn: "/"))),
           u.scheme == "https", u.host != nil, u != AppConfig.server {
            AppConfig.server = u; Task { await store.serverChanged() }
        }
        if !(token.isEmpty && AppConfig.hasToken) { AppConfig.token = token }   // automatisch eingetragenen Schluessel nicht leeren
        WidgetCenter.shared.reloadAllTimelines()
    }
    private func test() async {
        save(); busy = true; defer { busy = false }
        do {
            let d = try await API.today()
            status = "✓ Verbunden – heute \(d.events.count) Termine, \(d.tasks.count) offene Aufgaben"
        } catch { status = "✗ " + error.localizedDescription }
    }
}
