import SwiftUI
import WidgetKit

/// Einstellungen der App in drei Bereichen (2.5): Konto · Einstellungen · Info.
/// iOS: Startliste mit Unterseiten (Kopfzeile Profilbild + Name -> Konto, Abmelden ganz unten).
/// Mac: Tabs - im Einstellungsfenster (Settings-Scene, onLogout == nil) und als Blatt mit "Fertig".
/// Spruenge (CloudNav.pendingSection, hkOpenSettings, DEBUG -hkSettings <ziel>): SettingsLogic.swift
struct SettingsView: View {
    var onLogout: (() -> Void)? = nil
    @EnvironmentObject private var store: Store
    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var updates = UpdateHintModel.shared
    #if os(iOS)
    @State private var path: [SettingsArea] = []
    #else
    @State private var tab: SettingsArea = .konto
    #endif
    @State private var scroll: String?                    // Zeile, zu der der geoeffnete Bereich scrollt

    var body: some View {
        content
            .task { if store.chat.state == .loading { await store.chat.load() } }
            .task { jump(initial()) }
            .onReceive(NotificationCenter.default.publisher(for: .hkOpenSettings)) { n in jump(n.object as? String) }
            .onReceive(NotificationCenter.default.publisher(for: .hkOpenBackup)) { _ in jump("backup") }
    }

    /// Sprungziel beim Oeffnen: offener Bereich aus anderen Teilen der App, sonst (DEBUG) -hkSettings
    private func initial() -> String? {
        if let p = CloudNav.pendingSection { return p }
        #if DEBUG
        return UserDefaults.standard.string(forKey: "hkSettings")
        #else
        return nil
        #endif
    }

    private func jump(_ section: String?) {
        guard let a = SettingsArea.area(for: section) else { return }
        if section != "backup" {                                   // "backup" verbraucht MyBackupSection selbst (oeffnet "Verwalten")
            CloudNav.pendingSection = nil
            DispatchQueue.main.async { if CloudNav.pendingSection == section { CloudNav.pendingSection = nil } }   // RootView setzt ihn ggf. danach
        }
        scroll = SettingsArea.scrollTarget(for: section)
        #if os(iOS)
        if path != [a] { path = [a] }
        #else
        tab = a
        #endif
    }

    private var displayName: String? {
        #if DEBUG
        if let n = SettingsPreview.name { return n }               // nur fuer Bilder
        #endif
        return store.accountName
    }

    private func logout() {
        dismiss()
        if let onLogout { onLogout() } else { Task { await store.logout() } }
    }

    @ViewBuilder private func page(_ a: SettingsArea) -> some View {
        switch a {
        case .konto: KontoSettings(scroll: $scroll, close: { dismiss() }, logout: macLogout)
        case .einstellungen: GeneralSettings(scroll: $scroll)
        case .info: InfoSettings()
        }
    }

    /// Mac: Abmelden unten im Tab Konto; iOS: eigene Zeile auf der Startliste
    private var macLogout: (() -> Void)? {
        #if os(macOS)
        return { logout() }
        #else
        return nil
        #endif
    }

    // MARK: iOS - Startliste

    #if os(iOS)
    private var content: some View {
        NavigationStack(path: $path) {
            Form {
                Section {
                    NavigationLink(value: SettingsArea.konto) {
                        HStack(spacing: 14) {
                            AvatarView(name: displayName ?? "", avatar: store.myAvatar, size: 52)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(verbatim: displayName ?? String(localized: "Konto")).font(.title3.weight(.semibold))
                                Text(verbatim: ShotDisplay.server(AppConfig.serverString).replacingOccurrences(of: "https://", with: ""))
                                    .font(.footnote).foregroundStyle(.secondary).lineLimit(1)
                            }
                        }
                        .padding(.vertical, 4)
                    }
                    .accessibilityHint(Text("Konto"))
                }
                Section {
                    NavigationLink(value: SettingsArea.einstellungen) { Label("Einstellungen", systemImage: "gearshape") }
                    NavigationLink(value: SettingsArea.info) {
                        HStack {
                            Label("Info", systemImage: "info.circle")
                            Spacer()
                            if updates.available != nil {
                                Circle().fill(Color.red).frame(width: 9, height: 9).accessibilityLabel(Text("Update verfügbar"))
                            }
                        }
                    }
                }
                Section {
                    Button("Abmelden", role: .destructive) { logout() }
                } footer: { Text("Löscht die Anmeldung und den Zwischenspeicher auf diesem Gerät.") }
            }
            .navigationTitle("Einstellungen")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Fertig") { dismiss() } } }
            .navigationDestination(for: SettingsArea.self) { a in
                page(a)
                    .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Fertig") { dismiss() } } }
            }
        }
    }
    #else

    // MARK: Mac - Tabs

    @ViewBuilder private var content: some View {
        if onLogout != nil {                                   // Blatt: "Fertig" unten
            NavigationStack {
                tabs.toolbar { ToolbarItem(placement: .confirmationAction) { Button("Fertig") { dismiss() } } }
            }
        } else { tabs }
    }

    private var tabs: some View {
        TabView(selection: $tab) {
            page(.konto).tabItem { Label("Konto", systemImage: "person.crop.circle") }.tag(SettingsArea.konto)
            page(.einstellungen).tabItem { Label("Einstellungen", systemImage: "gearshape") }.tag(SettingsArea.einstellungen)
            page(.info).tabItem { Label("Info", systemImage: "info.circle") }.tag(SettingsArea.info)
        }
    }
    #endif
}

/// Nach dem Oeffnen eines Bereichs zur gewuenschten Zeile scrollen (Zeilen kommen ggf. erst nach dem Laden)
private struct ScrollToRow: ViewModifier {
    @Binding var target: String?
    let proxy: ScrollViewProxy
    func body(content: Content) -> some View {
        content.task(id: target) {
            guard let t = target else { return }
            for wait in [0.6, 1.5] {
                try? await Task.sleep(for: .seconds(wait))
                withAnimation { proxy.scrollTo(t, anchor: t == "konto" ? .center : .top) }
            }
            if target == t { target = nil }
        }
    }
}

// MARK: - Konto

/// Server, App-Schluessel, Verbindung, Name, Profilbild, Anmeldung & Sicherheit, Apps & Geraete, Mein Backup
struct KontoSettings: View {
    @Binding var scroll: String?
    let close: () -> Void
    var logout: (() -> Void)? = nil
    @EnvironmentObject private var store: Store
    @Environment(\.dynamicTypeSize) private var dts
    @State private var server = ShotDisplay.server(AppConfig.serverString)
    @State private var token = AppConfig.token
    @State private var status = ""
    @State private var busy = false
    @State private var askSwitch: URL?                    // anderer Server: erst nach Rueckfrage (meldet vom alten ab)
    @State private var crop: CropSource?                  // Profilbild zuschneiden (2.1)
    @State private var cropped: Data?
    @StateObject private var backup = BackupModel()       // Mein Backup: Blatt "Verwalten" haengt am Formular, nicht an der Zeile
    @State private var backupOpen = false
    /// Schluessel beim Oeffnen: nur eine echte Eingabe schreiben - sonst setzte das Schliessen nach "Abmelden" den eben
    /// geloeschten Schluessel wieder ein
    @State private var tokenAtOpen = AppConfig.token
    @State private var loggingOut = false

    var body: some View {
        ScrollViewReader { proxy in
            form.modifier(ScrollToRow(target: $scroll, proxy: proxy))
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
                if dts.isAccessibilitySize, !server.isEmpty {              // I07: groesste Schrift - Adresse ganz lesbar
                    Text(verbatim: server).font(.footnote).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                        .accessibilityHidden(true)
                }
                if server.trimmingCharacters(in: .whitespacesAndNewlines) != ShotDisplay.server(AppConfig.serverString) {
                    Button("Server übernehmen …") { applyServer() }
                }
                Button(busy ? "Prüfe …" : "Verbindung testen") { Task { await test() } }.disabled(busy)
                if !status.isEmpty { Text(status).font(.footnote) }
            }
            .id("server")
            Section {
                SecureField("hk_…", text: $token)
                Text("Den Schlüssel erzeugst du im Web unter 👤 → Einstellungen → „Apps & Geräte“. In der App genügt dort „In dieser App verwenden“.")
                    .font(.footnote).foregroundStyle(.secondary)
            } header: { Text("App-Schlüssel (Widget & Teilen)") }
            .id("token")
            DisplayNameSection()                          // Bughunt D01: Anzeigename (optional)
            PublicNameSection()                           // oeffentlicher Name (Veroeffentlichen), Zeile "konto"
            AvatarSettingsSection(crop: $crop, cropped: $cropped)   // Profilbild (2.1)
            SecuritySettingsSection()                     // Anmeldung & Sicherheit (2.1, verwaltet im Browser)
            AppsDevicesSection().id("apps")
            APITokensSection()                            // 2.5 api1: API-Tokens auflisten/widerrufen
            MyBackupSection(model: backup, open: $backupOpen)   // Mein Backup (2.3, nur wenn der Server es kennt)
            if let logout {                               // Mac: unten im Tab Konto
                Section {
                    Button("Abmelden", role: .destructive) { loggingOut = true; logout() }
                } footer: { Text("Löscht die Anmeldung und den Zwischenspeicher auf diesem Gerät.") }
            }
        }
        .navigationTitle("Konto")
        .sheet(item: $crop) { c in AvatarCropSheet(source: c) { jpeg in cropped = jpeg } }
        .sheet(isPresented: $backupOpen, onDismiss: { Task { await backup.load() } }) { MyBackupView(model: backup).environmentObject(store) }
        .confirmationDialog("Server wechseln?", isPresented: Binding(get: { askSwitch != nil }, set: { if !$0 { askSwitch = nil } }), titleVisibility: .visible) {
            Button("Abmelden und wechseln", role: .destructive) {
                if let u = askSwitch { askSwitch = nil; Task { await store.switchServer(to: u); server = AppConfig.serverString; close() } }
            }
            Button("Abbrechen", role: .cancel) { askSwitch = nil; server = AppConfig.serverString }
        } message: {
            Text("Du wirst vom bisherigen Server abgemeldet. Anmeldung, Schlüssel und Zwischenspeicher auf diesem Gerät werden gelöscht.")
        }
        #if os(macOS)
        .formStyle(.grouped)
        #endif
        .onDisappear { save() }                                   // auch iOS: Blatt weggewischt statt "Fertig" (BUG-101)
    }

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

// MARK: - Einstellungen

/// Allgemein · Benachrichtigungen · Chat · Dienste
struct GeneralSettings: View {
    @Binding var scroll: String?
    @EnvironmentObject private var store: Store
    #if os(macOS)
    @AppStorage("hk.coverHeight") private var coverHeight: Double = 200
    #endif

    var body: some View {
        ScrollViewReader { proxy in
            Form {
                SettingsGroupHeading("Allgemein")
                LanguageSettingsSection()                     // Sprache der App (Automatisch/de/en/es/th/ru)
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
                .id("darstellung")
                #endif
                SettingsGroupHeading("Benachrichtigungen")
                NotificationSettingsSection()                 // Glocke (1.4)
                PushSettingsSection(push: store.push)         // Mitteilungen aufs Geraet (1.7)
                TaskDigestSection()                           // 2.5 taskdue1: taegliche Zusammenfassung der Aufgaben
                ChatGroupHeading(chat: store.chat)
                ChatDevicesSection(chat: store.chat)          // Chats & Geraete (2.0), Zeile "chats"
                ChatPrivacySection(chat: store.chat).id("privacy")   // Gelesen-Bestaetigungen, Online-Status (2.4)
                SettingsGroupHeading("Dienste")
                NextcloudSettingsSection()                    // Nextcloud-Dateien in Seiten (2.3)
                ImmichSettingsSection().id("immich")          // wie im Web: 👤 → Einstellungen
                CalendarSettingsSection().id("kalender")
            }
            #if os(macOS)
            .formStyle(.grouped)
            #endif
            .navigationTitle("Einstellungen")
            .modifier(ScrollToRow(target: $scroll, proxy: proxy))
        }
    }
}

/// Ueberschrift einer Gruppe von Abschnitten (ohne eigene Zeilen)
struct SettingsGroupHeading: View {
    let title: LocalizedStringKey
    init(_ title: LocalizedStringKey) { self.title = title }
    var body: some View {
        Section {} header: {
            Text(title).font(.title3.weight(.semibold)).foregroundStyle(.primary).textCase(nil)
                .padding(.top, 8).accessibilityAddTraits(.isHeader)
        }
    }
}

/// "Chat" nur, wenn darunter auch Abschnitte stehen (wie ChatDevicesSection)
private struct ChatGroupHeading: View {
    @ObservedObject var chat: ChatModel
    var body: some View {
        if [.ready, .newDevice, .setup].contains(chat.state) { SettingsGroupHeading("Chat") }
    }
}

// MARK: - Info

/// Versionen, Kanal, Update-Hinweis, Lizenzhinweise
struct InfoSettings: View {
    @EnvironmentObject private var store: Store
    var body: some View {
        Form {
            Section("Versionen") {
                LabeledContent("App", value: "Saga \(Store.appVersionLabel) (\(Store.appBuild))")
                LabeledContent("Server", value: store.serverVersion.map { "Saga \($0)" } ?? "–")
                LabeledContent("Kanal") {
                    switch UpdateHint.channel(Store.appVersionLabel) {
                    case "testing": Text("Testversion")
                    case "stable": Text("Stabil")
                    default: Text("Eigener Build")
                    }
                }
                if store.appTooOld, let m = store.minApp {
                    Text("Diese App ist älter als der Server erwartet (ab \(m)). Bitte die App aktualisieren.").font(.footnote).foregroundStyle(.orange)
                }
            }
            UpdateHintSection()
            Section("Lizenzen") {
                Text("Anrufe nutzen WebRTC (libwebrtc, © The WebRTC project authors, BSD-Lizenz) und libsodium (ISC-Lizenz).")
                    .font(.footnote).foregroundStyle(.secondary)
            }
        }
        #if os(macOS)
        .formStyle(.grouped)
        #endif
        .navigationTitle("Info")
    }
}
