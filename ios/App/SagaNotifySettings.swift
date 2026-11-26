import SwiftUI

// 2270 (Saga 2.6, K-C): Einstellungen › Saga › Mitteilungen - "Mitteilungen von {Persona}" (Bereich jobs in PUT
// /api/push/settings cats), "Inhalt in Mitteilungen" (notify_content unlocked|never in /api/ai/prefs) und, solange aktiv,
// "Ruhe bis 07:00 · Wieder an" (notify_quiet_until, PUT null). Nur mit einem Server, der Nutzlast 3 kann (server.payload >= 3).
// Eigene Datei (SagaSettings.swift teilt sich K-D): dort nur eine Zeile.

struct SagaNotifyPrefs: Equatable {
    var content = true
    var quietUntil: Double?
    init?(data: Data) {
        guard let j = ShareJSON.obj(data), j["enabled"] as? Bool != false else { return nil }
        content = (ShareJSON.str(j["notify_content"]) ?? "unlocked") != "never"
        quietUntil = (j["notify_quiet_until"] as? NSNumber)?.doubleValue
    }
}

struct SagaNotifySection: View {
    @EnvironmentObject private var store: Store
    @ObservedObject var push: PushModel
    let name: String
    @State private var p: SagaNotifyPrefs?
    @State private var working = false
    @State private var loaded = false

    var body: some View {
        Group {
            if !loaded {
                // Leere Group: .task liefe in der Form nie - unsichtbare Zeile loest das Laden aus und verschwindet danach
                Color.clear.frame(height: 0).listRowBackground(Color.clear).listRowInsets(EdgeInsets()).accessibilityHidden(true)
                    .task {
                        if push.state == nil { await push.load() }
                        await reload()
                        loaded = true
                    }
            } else if let p, let s = push.state, s.payload >= 3 {
                Section {
                    if s.categories.contains(PushJobLogic.cat) || s.cats[PushJobLogic.cat] != nil {
                        Toggle(isOn: Binding(get: { s.cats[PushJobLogic.cat] ?? true },
                                             set: { v in Task { await push.save(["cats": [PushJobLogic.cat: v]]) } })) {
                            Text("Mitteilungen von \(name)")
                        }
                    }
                    Toggle(isOn: Binding(get: { p.content }, set: { v in put(["notify_content": v ? "unlocked" : "never"]) })) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Inhalt in Mitteilungen")
                            Text(p.content ? "Titel und Ergebnis in der Mitteilung – sichtbar, sobald das Gerät entsperrt ist."
                                 : "Nur „In Saga ansehen“ – ohne „Passt, los“ und „Rückgängig“.")
                                .font(.caption).foregroundStyle(.secondary)
                        }
                    }
                    .disabled(working)
                    if PushJobLogic.quietActive(until: p.quietUntil, now: Date().timeIntervalSince1970), let u = p.quietUntil {
                        HStack {
                            Label { Text("Ruhe bis \(Date(timeIntervalSince1970: u).formatted(date: .omitted, time: .shortened))") }
                                icon: { Image(systemName: "moon") }
                            Spacer()
                            Button("Wieder an") { put(["notify_quiet_until": NSNull()]) }.disabled(working)
                        }
                    }
                } header: {
                    Text("Mitteilungen")
                } footer: {
                    Text("Rückmeldungen von \(name) zu deinen Aufträgen, mit Knöpfen direkt in der Mitteilung. „Gerade nicht“ hält sie bis 7:00 an.")
                }
            }
        }
    }

    private func reload() async {
        guard let (d, code) = try? await store.apiResponse("api/ai/prefs"), code == 200 else { return }
        p = SagaNotifyPrefs(data: d)
    }
    private func put(_ body: [String: Any]) {
        working = true
        Task {
            _ = try? await store.apiResponse("api/ai/prefs", method: "PUT", body: body)
            await reload()                                                    // Antwort von PUT nennt nicht sicher alle Felder
            working = false
        }
    }
}
