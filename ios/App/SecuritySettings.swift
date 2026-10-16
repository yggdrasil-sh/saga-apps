import SwiftUI

// "Anmeldung & Sicherheit" (Saga 2.1): nur Status aus GET /api/account/security - eingerichtet wird im System-Browser
// unter /security (Passkeys brauchen dort keine Associated Domains; selbst gehostete Server sind frei waehlbar).
// Die Anmeldung selbst bleibt ein Web-Ablauf (ASWebAuthenticationSession): der 2FA-Schritt erscheint dort.

struct SecuritySettingsSection: View {
    @EnvironmentObject var store: Store
    @Environment(\.openURL) private var openURL
    @State private var st: SecurityStatus?
    @State private var supported = true
    var body: some View {
        if supported {
            Section {
                if let st {
                    Label {
                        Text(st.enabled ? "Zweiter Faktor ist eingeschaltet." : "Zweiter Faktor ist aus.")
                    } icon: {
                        Image(systemName: st.enabled ? "checkmark.shield.fill" : "shield").foregroundStyle(st.enabled ? Color.green : Color.secondary)
                    }
                    if st.enabled || st.passkeys > 0 {
                        LabeledContent("Passkeys") { Text(verbatim: "\(st.passkeys)") }
                        LabeledContent("Authenticator-App") { Text(st.totp ? "eingerichtet" : "nicht eingerichtet") }
                    }
                    if st.fewRecoveryCodes, let n = st.recoveryLeft {
                        Label(n == 1 ? String(localized: "Nur noch 1 Wiederherstellungscode – erzeuge im Browser neue.")
                                     : String(localized: "Nur noch \(n) Wiederherstellungscodes – erzeuge im Browser neue."),
                              systemImage: "exclamationmark.triangle.fill").foregroundStyle(.orange).font(.callout)
                    }
                    if st.mustSetUp {
                        Label("Dein Admin verlangt einen zweiten Faktor – bitte im Browser einrichten.", systemImage: "exclamationmark.triangle.fill")
                            .foregroundStyle(.orange).font(.callout)
                    }
                } else { ProgressView() }
                Button { if let u = URL(string: AppConfig.server.absoluteString + "/security") { openURL(u) } } label: {
                    Label("Im Browser verwalten", systemImage: "safari")
                }
            } header: { Text("Anmeldung & Sicherheit").id("security") } footer: {
                Text("Passkeys, Authenticator-App und Wiederherstellungscodes richtest du im Browser ein. Ist ein zweiter Faktor eingeschaltet, fragt Saga beim Anmelden im Browserfenster danach.")
            }
            .task { await load() }
        }
    }
    private func load() async {
        guard let (d, code) = try? await store.apiResponse("api/account/security") else { return }
        if code == 404 || code == 405 { supported = false; return }
        guard code == 200, let s = SecurityStatus(data: d) else { return }
        if !s.supported { supported = false; return }
        st = s
    }
}
