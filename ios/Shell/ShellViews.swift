import AppKit
import SwiftUI

struct ShellRootView: View {
    @ObservedObject var model: ShellModel

    var body: some View {
        ZStack {
            ShellWebView(webView: model.webView)
                .opacity(model.phase == .web ? 1 : 0)
            if model.phase == .web, model.loading {
                ProgressView().controlSize(.large)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(Color(nsColor: .windowBackgroundColor).opacity(0.6))
            }
            if model.phase == .signIn { ShellSignInView(model: model) }
            if model.phase == .starting { ProgressView().controlSize(.large) }
            if let t = model.toast {
                VStack {
                    Spacer()
                    Text(t).padding(.horizontal, 14).padding(.vertical, 8)
                        .background(.regularMaterial, in: Capsule())
                        .padding(.bottom, 24)
                }
                .transition(.opacity)
                .allowsHitTesting(false)
            }
        }
        .animation(.easeOut(duration: 0.15), value: model.toast)
        .navigationTitle(model.phase == .web ? model.title : "Saga")
        .sheet(isPresented: $model.showSettings) { ShellSettingsView(model: model) }
    }
}

/// Erster Start / abgemeldet: Server eintragen, Anmeldung im Browser
struct ShellSignInView: View {
    @ObservedObject var model: ShellModel
    @FocusState private var focused: Bool

    var body: some View {
        VStack(spacing: 18) {
            Image(nsImage: NSApp.applicationIconImage).resizable().frame(width: 84, height: 84)
            Text("Saga").font(.largeTitle.weight(.semibold))
            Text(String(localized: "Melde dich bei deinem Saga-Server an. Die Anmeldung öffnet sich im Browser."))
                .foregroundStyle(.secondary).multilineTextAlignment(.center).frame(maxWidth: 380)
            TextField(String(localized: "Server-Adresse"), text: $model.serverField, prompt: Text("https://saga.example.org"))
                .textFieldStyle(.roundedBorder).frame(width: 340)
                .focused($focused)
                .onSubmit { Task { await model.signIn() } }
                .disabled(model.busy)
            Button {
                Task { await model.signIn() }
            } label: {
                if model.busy { ProgressView().controlSize(.small).frame(width: 160) }
                else { Text(String(localized: "Im Browser anmelden")).frame(width: 160) }
            }
            .buttonStyle(.borderedProminent).controlSize(.large)
            .keyboardShortcut(.defaultAction)
            .disabled(model.busy || model.serverField.trimmingCharacters(in: .whitespaces).isEmpty)
            if let e = model.error {
                Text(e).foregroundStyle(.red).multilineTextAlignment(.center).frame(maxWidth: 380)
            }
        }
        .padding(40)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color(nsColor: .windowBackgroundColor))
        .onAppear { focused = model.serverField.isEmpty }
    }
}

/// Eigene Einstellungen der Huelle (Web-Nachricht `settings`, ⌥⌘,): Server, Konto, Abmelden, Version
struct ShellSettingsView: View {
    @ObservedObject var model: ShellModel
    @ObservedObject var push = ShellModel.shared.push
    @Environment(\.dismiss) private var dismiss
    @AppStorage("SagaInspectable") private var inspectable = false

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text(String(localized: "App-Einstellungen")).font(.title2.weight(.semibold))
            Form {
                LabeledContent(String(localized: "Server")) {
                    Text(AppConfig.hasServer ? AppConfig.displayHost(AppConfig.server) : "–").textSelection(.enabled)
                }
                LabeledContent(String(localized: "Konto")) { Text(model.accountName.isEmpty ? "–" : model.accountName) }
                LabeledContent(String(localized: "Version")) { Text(model.versionLabel).textSelection(.enabled) }
                if model.phase == .web {
                    Toggle(String(localized: "Mitteilungen auf diesem Mac"),
                           isOn: Binding(get: { push.on }, set: { v in Task { await push.setEnabled(v) } }))
                        .disabled(push.busy)
                    if let m = push.message { Text(m).font(.caption).foregroundStyle(.red) }
                    Text(String(localized: "Worüber und wann: in Saga unter Einstellungen → Mitteilungen."))
                        .font(.caption).foregroundStyle(.secondary)
                }
                Toggle(String(localized: "Web-Inspektor erlauben"), isOn: $inspectable)
                    .onChange(of: inspectable) { _, v in model.webView.isInspectable = v }
            }
            .formStyle(.grouped)
            .onAppear { push.refreshState() }
            HStack {
                if model.phase == .web {
                    Button(String(localized: "Abmelden …"), role: .destructive) { dismiss(); model.confirmSignOut() }
                }
                Spacer()
                Button(String(localized: "Fertig")) { dismiss() }.keyboardShortcut(.defaultAction)
            }
        }
        .padding(20)
        .frame(width: 440)
    }
}

extension ShellModel {
    /// Abmelden nach Rueckfrage (Menue, App-Einstellungen)
    func confirmSignOut() {
        let a = NSAlert()
        a.messageText = String(localized: "Von Saga abmelden?")
        a.informativeText = String(localized: "Die Anmeldung dieses Geräts und alle lokal gespeicherten Daten der App werden gelöscht.")
        a.addButton(withTitle: String(localized: "Abmelden"))
        a.addButton(withTitle: String(localized: "Abbrechen"))
        a.buttons.first?.hasDestructiveAction = true
        let go: (NSApplication.ModalResponse) -> Void = { r in if r == .alertFirstButtonReturn { Task { await self.signOut() } } }
        if let w = webView.window { a.beginSheetModal(for: w, completionHandler: go) } else { go(a.runModal()) }
    }
}
