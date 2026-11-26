import SwiftUI

// Einstellungen › Saga (2.6, Bugs-DB 2268 K-H + 2269 K-I, Konzept UX §4, M7): Anrede und Lebhaftigkeit, Einwilligung,
// Ideen, Verlauf, Quellen und "Meine Daten" (Widerruf mit ehrlichem Text, Export teilen, "Alles löschen" mit Rueckfrage,
// "Was mein Admin sehen kann"). Nur mit Ort "Saga" (store.sagaStatus); Server: GET/PUT /api/ai/prefs, /api/ai/consent,
// /api/ai/mydata. Texte wie im Web (static/settings.js, static/saga.js).

/// Zeile auf der Einstellungen-Startliste (iOS) - nur mit Faehigkeit
struct SagaSettingsLink: View {
    @EnvironmentObject private var store: Store
    var body: some View {
        if store.sagaStatus != nil {
            NavigationLink(value: SettingsArea.saga) {
                Label { Text(verbatim: "Saga") } icon: { Image(systemName: "sparkles") }
            }
        }
    }
}

struct SagaSettingsView: View {
    @EnvironmentObject private var store: Store
    @ObservedObject var m: SagaModel
    @State private var p: SagaPrefs?
    @State private var loaded = false
    @State private var msg: String?
    @State private var askWithdraw = false
    @State private var askDelete = false
    @State private var working = false
    @State private var adminOpen = false

    private var name: String { store.sagaName }
    private var hostLabel: String { store.sagaStatus?.endpointLabel ?? "–" }
    private var english: Bool { AppConfig.appLanguage.lowercased().hasPrefix("en") }

    var body: some View {
        Form {
            if let p, p.enabled {
                speech(p)
                TodayLineToggle(m: store.todayLine)                  // 2.6 (2271): Tageszeile auf „Heute“
                state(p)
                SagaNotifySection(push: store.push, name: name)          // 2270 (K-C): Mitteilungen von {Persona}
                if p.consent?.granted == true { ideasAndSources(p) }
                myData(p)
            } else if loaded {
                Text("Der KI-Assistent ist für dich nicht eingeschaltet.").foregroundStyle(.secondary)
            } else {
                ProgressView()
            }
            if let msg { Section { Text(verbatim: msg).font(.footnote).foregroundStyle(.secondary) } }
        }
        .formStyle(.grouped)
        .navigationTitle(Text(verbatim: "Saga"))
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .task { await reload() }
        .onChange(of: store.sagaStatus?.consent) { _, _ in Task { await reload() } }
        .alert("Einwilligung widerrufen?", isPresented: $askWithdraw) {
            Button("Widerrufen", role: .destructive) { Task { working = true; msg = await m.withdraw(); await reload(); working = false } }
            Button("Abbrechen", role: .cancel) {}
        } message: {
            Text("Danach erledigt \(name) keine Aufträge mehr (laufende halten an), bringt keine Ideen, und das Gespräch im Ort „Saga“ ist aus. Fragen an anderen Stellen, etwa „Frag deine Seiten“, gehen weiter an \(hostLabel) – nur, wenn du sie stellst. Du kannst \(name) jederzeit wieder kennenlernen.")
        }
        .alert("Alles löschen?", isPresented: $askDelete) {
            Button("Alles löschen", role: .destructive) { Task { working = true; msg = await m.deleteAll(); working = false } }
            Button("Abbrechen", role: .cancel) {}
        } message: {
            Text("Gelöscht werden deine Aufträge mit ihrem Protokoll, das Gespräch mit \(name) und dein Verlauf. Nachrichten, die schon gesendet wurden, bleiben bei den Empfängern. Das lässt sich nicht rückgängig machen.")
        }
    }

    private func reload() async {
        let x = await m.loadPrefs()
        p = x; loaded = true
    }
    private func put(_ body: [String: Any]) {
        Task {
            let (np, e) = await m.putPrefs(body)
            if let np { p = np; msg = String(localized: "Gespeichert.") } else { msg = e; await reload() }
            if body["history"] != nil { await m.loadThread() }
        }
    }

    // MARK: Anrede, Lebhaftigkeit (K-H)

    private static func livelyName(_ v: String) -> String {
        switch v {
        case "sachlich": return String(localized: "sachlich")
        case "warm": return String(localized: "warm")
        case "verspielt": return String(localized: "verspielt")
        default: return v
        }
    }
    private static func salutationName(_ v: String) -> String { v == "sie" ? String(localized: "Sie (Anrede)") : String(localized: "du (Anrede)") }

    @ViewBuilder private func speech(_ p: SagaPrefs) -> some View {
        Section {
            if !english && !p.salutationChoices.isEmpty {               // Englisch: keine Anrede-Unterscheidung
                Picker("Anrede", selection: Binding(get: { p.salutation ?? "" }, set: { put(["salutation": $0.isEmpty ? NSNull() as Any : $0 as Any]) })) {
                    Text("Standard (\(Self.salutationName(p.defaultSalutation)))").tag("")
                    ForEach(p.salutationChoices, id: \.self) { Text(verbatim: Self.salutationName($0)).tag($0) }
                }
            }
            if p.livelyChoices.count > 1 {
                Picker("Lebhaftigkeit", selection: Binding(get: { p.lively ?? "" }, set: { put(["lively": $0.isEmpty ? NSNull() as Any : $0 as Any]) })) {
                    Text("Standard (\(Self.livelyName(p.defaultLively)))").tag("")
                    ForEach(p.livelyChoices, id: \.self) { Text(verbatim: Self.livelyName($0)).tag($0) }
                }
            }
        } header: {
            Text("So spricht \(name)")
        } footer: {
            Text("So spricht die KI in ihren Sätzen mit dir – in Rückmeldungen, Plänen und Rückfragen. Entwürfe in deinem Namen richten sich nach der Anrede im Kontakt, nicht nach dieser Einstellung.")
        }
    }

    // MARK: Einwilligung

    @ViewBuilder private func state(_ p: SagaPrefs) -> some View {
        let c = p.consent ?? SagaConsent(state: .none)
        Section {
            switch c.state {
            case .granted:
                Text("Du nutzt \(name) seit \(Date(timeIntervalSince1970: c.at ?? 0).formatted(date: .long, time: .omitted)).")
            case .withdrawn:
                Text("Du hast die Einwilligung widerrufen: \(name) erledigt keine Aufträge, bringt keine Ideen, und das Gespräch im Ort „Saga“ ist aus. Fragen an anderen Stellen, etwa „Frag deine Seiten“, gehen weiter an \(hostLabel) – nur, wenn du sie stellst.")
            case .stale:
                Text("Der Admin hat die KI umgestellt – lerne \(name) noch einmal kennen, bevor es weitergeht.")
            default:
                Text("Du hast \(name) noch nicht kennengelernt.")
            }
            if c.state != .granted, let intro = store.sagaStatus?.intro {
                NavigationLink {
                    ScrollView { SagaIntroCard(m: m, intro: intro, inSettings: true).padding() }
                        .navigationTitle(Text("\(name) kennenlernen"))
                } label: { Text("\(name) kennenlernen") }
            }
        }
    }

    // MARK: Ideen, Verlauf, Quellen (K-I)

    @ViewBuilder private func ideasAndSources(_ p: SagaPrefs) -> some View {
        Section {
            if p.ideasAllowed {
                Picker("Ideen von \(name)", selection: Binding(get: { p.ideas }, set: { put(["ideas": $0]) })) {
                    Text("Aus").tag("off")
                    Text("Nur auf Heute").tag("today")
                }
            }
            if p.historyAllowed {
                Toggle("Verlauf speichern", isOn: Binding(get: { p.history }, set: { put(["history": $0]) }))
            }
        } footer: {
            if p.historyAllowed { Text("Aus: Das Gespräch verschwindet nach 24 Stunden. Ausschalten löscht den gespeicherten Verlauf.") }
        }
        Section("Was \(name) lesen darf") {
            ForEach(SagaLogic.sourceKeys, id: \.self) { k in
                Toggle(isOn: Binding(get: { p.source(k) }, set: { put(["sources": [k: $0]]) })) { Text(Self.sourceName(k)) }
            }
        }
    }
    private static func sourceName(_ k: String) -> String {
        switch k {
        case "pages": return String(localized: "Seiten (eigene und für dich geteilte)")
        case "tasks": return String(localized: "Aufgaben")
        case "calendar": return String(localized: "Termine")
        default: return String(localized: "Chat-Nachrichten, wenn du die KI im Chat rufst")
        }
    }

    // MARK: Meine Daten (M7)

    @ViewBuilder private func myData(_ p: SagaPrefs) -> some View {
        let c = p.consent?.state ?? .none
        Section {
            if c == .granted || c == .stale {
                Button("Einwilligung widerrufen") { askWithdraw = true }.disabled(working)
            }
            Button("Verlauf und Aufträge exportieren (JSON)") { export("json") }.disabled(working)
            Button("Als Text exportieren (Markdown)") { export("md") }.disabled(working)
            Button("Alles löschen", role: .destructive) { askDelete = true }.disabled(working)
            DisclosureGroup("Was mein Admin sehen kann", isExpanded: $adminOpen) {
                VStack(alignment: .leading, spacing: 8) {
                    Text("Dein Admin betreibt diesen Server. Technisch kann er alles lesen, was auf dem Server liegt – auch deine Seiten, Aufgaben und Termine, deine Aufträge und das Gespräch mit \(name), solange es gespeichert ist.")
                    Text("Deine Chats sind Ende-zu-Ende verschlüsselt – die kann er nicht lesen. Ausnahme: Nachrichten, in denen du die KI im Chat rufst, gehen im Klartext an die KI.")
                    Text("In der Admin-Seite sieht er nur Zahlen (Anfragen pro Tag), keine Inhalte; auf Servern mit weniger als 5 Konten nur Summen.")
                    Text("Was du \(name) gibst, sieht auch der Dienst, der die KI betreibt: \(hostLabel).")
                }
                .font(.subheadline).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
            }
        } header: {
            Text("Meine Daten")
        } footer: {
            Text("Hier siehst und steuerst du, was \(name) über dich gespeichert hat: Einwilligung, Gespräch, Aufträge und Verlauf.")
        }
    }
    private func export(_ f: String) {
        working = true
        Task {
            do { let d = try await m.export(f); saveDataFile(d, name: SagaLogic.exportName(format: f, now: Date())) }
            catch { msg = error.localizedDescription }
            working = false
        }
    }
}
