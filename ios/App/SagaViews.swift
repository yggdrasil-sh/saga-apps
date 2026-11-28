import SwiftUI
import UserNotifications

// Ort "Saga" (2.6, Bugs-DB 2269 K-I, Konzept K10/K11/K19, M1/M3/M4, UX §0/§3/§6): Tab "Saga" (nicht in der Chat-Liste),
// Leiste B6, Kennenlernen-Karte, Gespraech mit der KI (eigene Tabelle, kein E2E-Chat). Auftraege: SagaJobsViews.swift.
// iPhone: Segment Gespraech / Auftraege. iPad (normale Breite): Auftraege links, Gespraech bzw. Detail rechts.
// Alle Texte zu Anbieter/Host kommen fertig vom Server; Modelltext nur ueber die KI-Darstellung (AnswerText, 2248).

extension Color {
    /// --ai-tint (Kartenflaeche der KI): hell #f7f0df, dunkel #1e2530
    static let sagaTint = Color.adaptive(SagaPalette.tint)
    /// --ai-edge (Kante, >= 3:1): hell #a07c2c, dunkel #8f7440
    static let sagaEdge = Color.adaptive(SagaPalette.edge)
    static func adaptive(_ c: (light: UInt32, dark: UInt32)) -> Color {
        func t(_ x: UInt32) -> (Int, Int, Int) { (Int(x >> 16 & 0xFF), Int(x >> 8 & 0xFF), Int(x & 0xFF)) }
        return adaptive(light: t(c.light), dark: t(c.dark))
    }
    static func adaptive(light l: (Int, Int, Int), dark d: (Int, Int, Int)) -> Color {
        func c(_ x: (Int, Int, Int)) -> (CGFloat, CGFloat, CGFloat) { (CGFloat(x.0) / 255, CGFloat(x.1) / 255, CGFloat(x.2) / 255) }
        let a = c(l), b = c(d)
        #if os(iOS)
        return Color(uiColor: UIColor { t in t.userInterfaceStyle == .dark ? UIColor(red: b.0, green: b.1, blue: b.2, alpha: 1) : UIColor(red: a.0, green: a.1, blue: a.2, alpha: 1) })
        #else
        return Color(nsColor: NSColor(name: nil) { ap in ap.bestMatch(from: [.darkAqua, .aqua]) == .darkAqua ? NSColor(red: b.0, green: b.1, blue: b.2, alpha: 1) : NSColor(red: a.0, green: a.1, blue: a.2, alpha: 1) })
        #endif
    }
}

extension Store {
    /// Kennzeichnung "Marin · KI" (style.label vom Server, Namensregel M1); Rueckfall wie das Web
    var sagaLabel: String {
        if let l = sagaStatus?.label { return l }
        guard let p = aiPersona else { return String(localized: "Saga KI") }
        return p.customName ? String(localized: "\(p.name) · KI") : p.name
    }
    var sagaName: String { aiPersona?.name ?? String(localized: "Saga KI") }
    /// 2.6 K-G (2275): Teilen an die Kollegin erlaubt (aishare1 ∧ Auftraege ∧ ai_act ∧ Einwilligung)
    var shareJobsAllowed: Bool { sagaStatus?.shareJobs(features: features.set) ?? false }
    /// ... als Schalter fuer die Teilen-Erweiterung in die App-Gruppe (sie hat keine Sitzung und fragt den Server nie)
    func syncShareJobs() {
        ShareDraftLogic.setJobs(shareJobsAllowed && ShareDraftLogic.platformOK, name: sagaName, in: AppConfig.store)
    }
    /// Entwurf der Teilen-Erweiterung uebernehmen (saga://share, Mitteilung, App vorne)
    @discardableResult
    func takeShareDraft(quietIfNone: Bool = false) -> Bool {
        UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: [ShareDraftLogic.notifyID])
        return saga.takeShareDraft(allowed: shareJobsAllowed, server: AppConfig.serverOrigin, quietIfNone: quietIfNone)
    }
    /// Einstellungen › Saga oeffnen (aus dem Tab)
    func openSagaSettings() { NotificationCenter.default.post(name: .hkOpenSettings, object: "saga") }
}

/// Karte im KI-Stil (Flaeche + Kante; nie nur Farbe - Kopf und Woerter tragen die Bedeutung)
struct SagaCardStyle: ViewModifier {
    func body(content: Content) -> some View {
        content.padding(14).frame(maxWidth: .infinity, alignment: .leading)
            .background(Color.sagaTint, in: RoundedRectangle(cornerRadius: 14))
            .overlay(RoundedRectangle(cornerRadius: 14).strokeBorder(Color.sagaEdge.opacity(0.6), lineWidth: 1))
    }
}
extension View { func sagaCard() -> some View { modifier(SagaCardStyle()) } }

/// Umrandeter Knopf auf der KI-Karte ("Ja, ohne Ideen", "Rückgängig", 2515): Gold-Schrift (>= 4,5:1) und Kante
/// sagaEdge (>= 3:1) auf sagaTint, hell und dunkel - .bordered legt eine graue Flaeche ohne Rand darunter (dunkel: Flaeche
/// 1,48:1 zur Karte, Gold darauf 4,43:1 - beides unter AA). Kapsel wie die Systemknoepfe, gesperrt grau, Hoehe >= 44 pt.
/// Pruefung: SagaTests (SagaPalette).
struct SagaOutlineButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var enabled
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.body.weight(.medium))
            .padding(.horizontal, 14).padding(.vertical, 7).frame(minHeight: 44)
            .foregroundStyle(enabled ? Color.gold : Color.secondary)
            .background(Capsule().fill(Color.gold.opacity(configuration.isPressed ? 0.16 : 0)))
            .overlay(Capsule().strokeBorder(enabled ? Color.sagaEdge : Color.secondary.opacity(0.4), lineWidth: 1))
            .contentShape(Capsule())
    }
}

/// B1 KI-Kopf: Persona-Bild · "Marin · KI" · rechts Zustandswort oder Uhrzeit. VoiceOver: "Marin · KI, 14:12"
struct SagaHead: View {
    @EnvironmentObject var store: Store
    var size: CGFloat = 24
    var extra: String?
    var body: some View {
        HStack(spacing: 8) {
            AIAvatarView(persona: store.aiPersona, size: size)
            Text(verbatim: store.sagaLabel).font(.subheadline.weight(.semibold))
            if let extra, !extra.isEmpty {
                Spacer(minLength: 4)
                Text(verbatim: extra).font(.subheadline).foregroundStyle(.secondary)
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isHeader)
    }
}

/// ⓘ-Details (M3): Fachwoerter nur hier - "nicht Ende-zu-Ende verschluesselt", was genau rausgeht
struct SagaInfoSheet: View {
    let details: [String]
    var note: String?
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        NavigationStack {
            List {
                ForEach(Array(details.enumerated()), id: \.offset) { _, d in Text(verbatim: d).fixedSize(horizontal: false, vertical: true) }
                if let note { Section { Text(verbatim: note).foregroundStyle(.secondary) } }
            }
            .navigationTitle("Was genau rausgeht")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Fertig") { dismiss() } } }
        }
        .presentationDetents([.medium, .large])
        .macFrame(minWidth: 420, idealWidth: 480, minHeight: 360)
    }
}

/// B6 Leiste fest oben im Saga-Bereich: einzeilig, nicht wegklickbar, antippbar (oeffnet die ⓘ-Details)
struct SagaBar: View {
    let text: String
    let open: () -> Void
    @Environment(\.dynamicTypeSize) private var dts
    var body: some View {
        Button(action: open) {
            HStack(spacing: 6) {
                Image(systemName: "lock.open").font(.footnote).accessibilityHidden(true)
                Text(verbatim: text).font(.footnote).lineLimit(dts.isAccessibilitySize ? nil : 1).truncationMode(.head)   // Host bleibt sichtbar (Ende des Satzes; sehr grosse Schrift: ganzer Satz)
                Spacer(minLength: 4)
                Image(systemName: "info.circle").font(.body)
            }
            .foregroundStyle(.secondary)
            .padding(.horizontal, 14).frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .background(Color.sagaTint.opacity(0.6))
        .accessibilityLabel(Text(verbatim: text))
        .accessibilityHint(Text("Details"))
    }
}

/// Kennenlernen-Karte (K19): drei Saetze vom Server, Host-Hinweis mit ⓘ, Knoepfe untereinander (Handy, R1.5)
struct SagaIntroCard: View {
    @EnvironmentObject var store: Store
    @ObservedObject var m: SagaModel
    let intro: SagaIntro
    /// Einstellungen-Variante: ohne "Mehr einstellen" (man ist schon dort)
    var inSettings = false
    var onAnswer: (SagaLogic.ConsentAnswer) -> Void = { _ in }
    @State private var info = false
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            SagaHead(size: 36)
            ForEach(Array(intro.lines.enumerated()), id: \.offset) { i, l in
                if i == 1 {
                    VStack(alignment: .leading, spacing: 6) {
                        Text(verbatim: l).fixedSize(horizontal: false, vertical: true)
                        Button { info = true } label: { Label("Was genau rausgeht", systemImage: "info.circle").font(.subheadline) }
                            .buttonStyle(.borderless).frame(minHeight: 44)
                        if let n = intro.note { Text(verbatim: n).font(.subheadline).foregroundStyle(.secondary) }
                    }
                } else { Text(verbatim: l).fixedSize(horizontal: false, vertical: true) }
            }
            if let q = intro.question { Text(verbatim: q).font(.body.weight(.semibold)).fixedSize(horizontal: false, vertical: true) }
            VStack(spacing: 10) {
                ForEach(SagaLogic.introAnswers(ideasAllowed: intro.ideasAllowed), id: \.rawValue) { a in answerButton(a) }
            }
            .disabled(m.busy)
            if let e = m.cardError { Label(e, systemImage: "exclamationmark.circle").font(.subheadline).foregroundStyle(.orange) }
            if !inSettings {
                Button("Mehr einstellen") { store.openSagaSettings() }.buttonStyle(.borderless).frame(minHeight: 44)
            }
        }
        .sagaCard()
        .accessibilityElement(children: .contain)
        .accessibilityLabel(Text("\(store.sagaName) kennenlernen"))
        .sheet(isPresented: $info) { SagaInfoSheet(details: intro.details) }
    }
    @ViewBuilder private func answerButton(_ a: SagaLogic.ConsentAnswer) -> some View {
        let go: () -> Void = { _ = Task { await m.consent(a); if m.cardError == nil { onAnswer(a) } } }
        switch a {
        case .ideas:
            Button { go() } label: { Text("Ja, mit Ideen").frame(maxWidth: .infinity, minHeight: 30) }.prominentGold().controlSize(.large)
        case .noIdeas:
            if intro.ideasAllowed {
                Button { go() } label: { Text("Ja, ohne Ideen").frame(maxWidth: .infinity, minHeight: 30) }.buttonStyle(SagaOutlineButtonStyle())
            } else {
                Button { go() } label: { Text("Ja, einverstanden").frame(maxWidth: .infinity, minHeight: 30) }.prominentGold().controlSize(.large)
            }
        case .later:
            Button { go() } label: { Text("Jetzt nicht").frame(maxWidth: .infinity, minHeight: 44) }.buttonStyle(.borderless).padding(.top, 6)
        }
    }
}

/// Tab "Saga" (K10, M1)
struct SagaScreen: View {
    @EnvironmentObject var store: Store
    @ObservedObject var m: SagaModel
    @State private var tab = 0                                        // 0 Gespraech, 1 Auftraege (iPhone)
    @State private var info = false
    @State private var askClear = false
    #if os(iOS)
    @Environment(\.horizontalSizeClass) private var hsc
    private var wide: Bool { hsc == .regular }
    #else
    private var wide: Bool { true }
    #endif

    /// "Ansehen" an einem Auftrag im Gespraech: Detail im Segment "Aufträge" (iPad: rechts)
    private func openJob(_ id: Int) { tab = 1; Task { await m.open(id) } }
    /// Beispiel aus der leeren Liste: ins Gespraech, als Auftrag geben
    private func giveExample(_ t: String) {
        tab = 0; m.close()
        guard store.sagaStatus?.consentOK == true else { return }    // ohne Einwilligung: nur die Kennenlernen-Karte, nichts ans Modell
        m.jobMode = true; m.giveInThread(t)
    }
    /// Hinweis ueber der Liste ohne Einwilligung (wie das Web, pweb 1): warum die Auftraege stehen, was weiter geht
    private func pausedNote(_ st: SagaStatus) -> String {
        switch st.consent?.state {
        case .withdrawn?: return String(localized: "Deine Aufträge sind angehalten, weil du die Einwilligung widerrufen hast. Ansehen, abbrechen und rückgängig machen geht weiter – neue Aufträge erst, wenn du \(store.sagaName) wieder kennenlernst.")
        case .stale?: return String(localized: "Der Admin hat die KI umgestellt – deine Aufträge warten, bis du \(store.sagaName) noch einmal kennenlernst.")
        default: return String(localized: "Neue Aufträge gibst du, sobald du \(store.sagaName) kennengelernt hast.")
        }
    }
    private func introOnly(_ intro: SagaIntro) -> some View {
        ScrollView {                                                   // vor der Einwilligung: nur die Karte (nichts ans Modell)
            SagaIntroCard(m: m, intro: intro).frame(maxWidth: 620).padding()
                .frame(maxWidth: .infinity)
        }
    }

    var body: some View {
        VStack(spacing: 0) {
            if let st = store.sagaStatus {
                if let intro = st.intro, !intro.bar.isEmpty {
                    SagaBar(text: intro.bar) { info = true }
                    Divider()
                }
                if !st.consentOK, let intro = st.intro {
                    // Ohne Einwilligung (Widerruf, stale) bleiben vorhandene Auftraege erreichbar - Liste, Detail, Abbrechen,
                    // Rueckgaengig (der Server erlaubt das ohne Einwilligung); Gespraech und "Als Auftrag" bleiben gesperrt (11.7)
                    if st.jobsOn && m.jobs.total > 0 {
                        Picker("Bereich", selection: $tab) {
                            Text("Gespräch").tag(0)
                            Text("Aufträge").tag(1)
                        }
                        .pickerStyle(.segmented).labelsHidden().padding(.horizontal).padding(.vertical, 8)
                        if tab == 1 {
                            if m.openJob == nil {
                                Text(verbatim: pausedNote(st)).font(.subheadline).foregroundStyle(.secondary)
                                    .fixedSize(horizontal: false, vertical: true).frame(maxWidth: .infinity, alignment: .leading)
                                    .padding(.horizontal).padding(.top, 4)
                            }
                            SagaJobsPane(m: m, inlineDetail: true, give: giveExample)
                        } else { introOnly(intro) }
                    } else { introOnly(intro) }
                } else if st.jobsOn && wide {
                    HStack(spacing: 0) {
                        SagaJobsPane(m: m, inlineDetail: false, give: giveExample).frame(width: 360)
                        Divider()
                        if m.openJob != nil { SagaJobDetailView(m: m, closeLabel: true) } else { SagaConversation(m: m, openJob: openJob) }
                    }
                } else {
                    if st.jobsOn {
                        Picker("Bereich", selection: $tab) {
                            Text("Gespräch").tag(0)
                            Text("Aufträge").tag(1)
                        }
                        .pickerStyle(.segmented).labelsHidden().padding(.horizontal).padding(.vertical, 8)
                    }
                    if tab == 1 && st.jobsOn { SagaJobsPane(m: m, inlineDetail: true, give: giveExample) } else { SagaConversation(m: m, openJob: openJob) }
                }
            } else {
                ContentUnavailableView { Label("Saga", systemImage: "sparkles") } description: { Text("Der KI-Assistent ist für dich nicht eingeschaltet.") }
            }
        }
        .navigationTitle(Text(verbatim: "Saga"))
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .toolbar {
            ToolbarItem(placement: .principal) { SagaHead(size: 28).environmentObject(store) }
            if store.sagaStatus?.consentOK == true {
                ToolbarItem(placement: .primaryAction) {
                    Menu {
                        Button(role: .destructive) { askClear = true } label: { Label("Gespräch leeren", systemImage: "trash") }
                        Button { store.openSagaSettings() } label: { Label("Einstellungen › Saga", systemImage: "gearshape") }
                    } label: { Label("Mehr", systemImage: "ellipsis.circle") }
                }
            }
        }
        .sheet(isPresented: $info) { SagaInfoSheet(details: store.sagaStatus?.intro?.details ?? [], note: store.sagaStatus?.intro?.note) }
        .alert("Gespräch leeren?", isPresented: $askClear) {
            Button("Leeren", role: .destructive) { Task { await m.clearThread() } }
            Button("Abbrechen", role: .cancel) {}
        } message: { Text("Alle Beiträge dieses Gesprächs werden gelöscht.") }
        .overlay(alignment: .bottom) {
            if let t = m.toast {
                Text(verbatim: t).font(.subheadline).padding(.horizontal, 16).padding(.vertical, 10)
                    .background(.regularMaterial, in: Capsule()).padding(.bottom, 80)
                    .task(id: t) { try? await Task.sleep(for: .seconds(3)); if m.toast == t { m.toast = nil } }
                    .accessibilityAddTraits(.updatesFrequently)
            }
        }
        #if DEBUG
        .task {                                                        // nur fuer Tests: Bereich und Ablauf per Startparameter
            if UserDefaults.standard.integer(forKey: "hkSagaTab") == 1 { tab = 1 }
            if UserDefaults.standard.integer(forKey: "hkSagaOpen") > 0 { openJob(UserDefaults.standard.integer(forKey: "hkSagaOpen")) }
            await m.debugRun()
        }
        #endif
        // 2270: Mitteilung eines Auftrags bzw. saga://job/<id> -> Bereich "Auftraege" (Detail oeffnet RootView.openJob)
        .onReceive(NotificationCenter.default.publisher(for: .hkSagaJobs)) { _ in tab = 1; JobPush.wantJobsTab = false }
        .onAppear { if JobPush.wantJobsTab { JobPush.wantJobsTab = false; tab = 1 } }
        .onChange(of: m.wantJobs, initial: true) { _, w in if w { tab = 1; m.wantJobs = false } }   // 2.6 (2271): aus der Tageszeile
        .onChange(of: m.wantConversation, initial: true) { _, w in if w { tab = 0; m.wantConversation = false } }   // 2.6 K-G: Siri, 2508
        .task(id: store.sagaStatus?.consentOK) {
            await store.loadAIStatus()
            if store.sagaStatus?.consentOK == true { await m.loadThread(); await m.loadJobs() }
            else if store.sagaStatus?.jobsOn == true { await m.loadJobs() }      // vorhandene Auftraege auch ohne Einwilligung
        }
    }
}

/// Gespraech im Ort "Saga" (POST /api/ai/thread als Strom)
struct SagaConversation: View {
    @EnvironmentObject var store: Store
    @EnvironmentObject var nav: NavModel
    @ObservedObject var m: SagaModel
    var openJob: (Int) -> Void = { _ in }
    @State private var q = ""
    @State private var reporting: SagaReportItem?
    @FocusState private var focused: Bool

    var body: some View {
        VStack(spacing: 0) {
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 14) {
                        if m.thread.turns.isEmpty && m.liveQ == nil && m.liveJob == nil && m.threadLoaded { empty }
                        ForEach(m.thread.turns) { t in turn(t).id(t.id) }
                        if let lq = m.liveQ {
                            if !lq.isEmpty { mine(lq) }
                            liveCard
                        }
                        if let lj = m.liveJob {
                            mine(lj.said, job: true, mark: lj.mark)
                            liveJobCard(lj)
                        }
                        keepLine
                        Color.clear.frame(height: 1).id("sg-end")
                    }
                    .padding()
                    .frame(maxWidth: 760).frame(maxWidth: .infinity)
                }
                .scrollDismissesKeyboard(.interactively)
                .onChange(of: m.live?.text) { _, _ in proxy.scrollTo("sg-end", anchor: .bottom) }
                .onChange(of: m.thread.turns.count) { _, _ in proxy.scrollTo("sg-end", anchor: .bottom) }
                .onChange(of: m.liveJob?.said) { _, _ in proxy.scrollTo("sg-end", anchor: .bottom) }
                .onAppear { proxy.scrollTo("sg-end", anchor: .bottom) }
            }
            Divider()
            input
        }
        .sheet(item: $reporting) { r in SagaReportSheet(m: m, item: r) }
        // 2275: Teilen-Option weg (Admin, Merkmal, ai_act, Einwilligung) -> nie mit dem alten geteilten Inhalt senden
        .onChange(of: store.shareJobsAllowed) { _, ok in if !ok { m.shareLine = nil } }
    }

    private var empty: some View {
        VStack(alignment: .leading, spacing: 12) {
            AIAvatarView(persona: store.aiPersona, size: 56)
            Text("Sag mir, was du brauchst – zum Beispiel:").font(.body)
            ForEach([String(localized: "Was steht diese Woche an?"), String(localized: "Fass meine Notizen zum Sommerfest zusammen"),
                     String(localized: "Welche Aufgaben sind offen?")], id: \.self) { ex in
                Button { m.send(ex) } label: {
                    Text(verbatim: ex).multilineTextAlignment(.leading).padding(.horizontal, 14).frame(minHeight: 44)
                        .background(Color.sagaTint, in: Capsule()).overlay(Capsule().strokeBorder(Color.sagaEdge.opacity(0.5)))
                }
                .buttonStyle(.plain).disabled(m.live != nil)
            }
        }
        .padding(.top, 12)
    }

    /// Eigene Bitte; als Auftrag mit kleiner Marke "Auftrag"; 2275: zu geteiltem Inhalt darunter "Geteilt: host" (nie der Text)
    private func mine(_ text: String, job: Bool = false, mark: ShareMark? = nil) -> some View {
        VStack(alignment: .trailing, spacing: 4) {
            if job {
                Label("Auftrag", systemImage: "checklist").font(.caption.weight(.semibold)).foregroundStyle(.secondary)
            }
            Text(verbatim: text).textSelection(.enabled)
            if let mark { Text(verbatim: SagaShareText.label(mark)).font(.caption).foregroundStyle(.secondary) }
        }
        .padding(12).background(Color.gold.opacity(0.16), in: RoundedRectangle(cornerRadius: 14))
        .frame(maxWidth: .infinity, alignment: .trailing).padding(.leading, 40)
        .accessibilityElement(children: .combine)
    }

    @ViewBuilder private func turn(_ t: SagaTurn) -> some View {
        switch t.kind(jobsOn: store.sagaStatus?.jobsOn == true) {
        case .mine: mine(t.text)
        case .mineJob: mine(t.text, job: true, mark: t.shareMark)
        case .plan(let id): SagaJobTurnView(m: m, job: id, title: t.text, openJob: openJob)
        case .ask:
            SagaAskCard(question: t.text, choices: t.choices, disabled: busy) { c in m.giveInThread(t.q ?? "", choice: c, said: c, askShared: m.askShared[t.id]) }
        case .refuse:
            SagaRefuseCard(text: t.text, alternative: t.alternative, disabled: busy,
                           take: { a in m.giveInThread(t.q ?? " ", alternative: a, said: a.label ?? String(localized: "Als Aufgabe anlegen")) },
                           rephrase: { m.jobMode = true; q = t.q ?? ""; focused = true })
        case .answer:
            VStack(alignment: .leading, spacing: 8) {
                SagaHead(extra: t.created.map { Date(timeIntervalSince1970: $0).formatted(date: .omitted, time: .shortened) })
                AnswerText(text: t.text, sources: t.sources, openPage: open)
                SagaSourceLine(sources: t.sources, open: open)
                if t.reportable {
                    SagaReportButton(m: m, key: "a" + t.id) { reporting = SagaReportItem(where: "ask", job: nil, text: t.text, key: "a" + t.id) }
                }
            }
            .sagaCard()
        }
    }

    /// Frage oder Auftrag laeuft gerade
    private var busy: Bool { (m.live != nil && m.liveError == nil) || (m.liveJob != nil && m.liveJob?.error == nil && m.liveJob?.stopped != true) }

    @ViewBuilder private func liveJobCard(_ lj: SagaModel.LiveJob) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            SagaHead()
            if let e = lj.error {
                Label(e.isEmpty ? String(localized: "Das hat nicht geklappt.") : e, systemImage: "exclamationmark.circle").foregroundStyle(.orange)
                Button("Schließen") { m.dismissLiveJob() }.buttonStyle(SagaOutlineButtonStyle()).frame(minHeight: 44)
            } else if lj.stopped {
                Text("Abgebrochen – nichts ist passiert.").foregroundStyle(.secondary)
                Button("Schließen") { m.dismissLiveJob() }.buttonStyle(SagaOutlineButtonStyle()).frame(minHeight: 44)
            } else {
                HStack(spacing: 8) { ProgressView().controlSize(.small); Text(verbatim: lj.status).foregroundStyle(.secondary) }
                    .accessibilityElement(children: .combine)
            }
        }
        .sagaCard()
    }

    @ViewBuilder private var liveCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            SagaHead()
            if let l = m.live, !l.text.isEmpty { AnswerText(text: l.text, sources: l.sources, openPage: open) }
            else if m.liveError == nil {
                HStack(spacing: 8) { ProgressView().controlSize(.small); Text(verbatim: m.live?.status ?? store.aiThinking).foregroundStyle(.secondary) }
                    .accessibilityElement(children: .combine)
            }
            if let e = m.liveError {
                Label(e.isEmpty ? String(localized: "Das hat nicht geklappt.") : e, systemImage: "exclamationmark.circle").foregroundStyle(.orange)
                Button("Schließen") { m.dismissLiveError() }.buttonStyle(SagaOutlineButtonStyle()).frame(minHeight: 44)
            }
        }
        .sagaCard()
    }

    @ViewBuilder private var keepLine: some View {
        if m.threadLoaded {
            HStack(alignment: .firstTextBaseline, spacing: 6) {
                Text(m.thread.history ? "Verlauf an – das Gespräch bleibt gespeichert." : "Verlauf aus – das Gespräch verschwindet nach 24 Stunden.")
                    .font(.footnote).foregroundStyle(.secondary)
                Button("Ändern") { store.openSagaSettings() }.font(.footnote).buttonStyle(.borderless)
            }
            .padding(.top, 4)
        }
    }

    @Environment(\.dynamicTypeSize) private var dts
    /// Eingabe: Feld ueber die volle Breite, darunter "Als Auftrag" (Umschalter mit Wort, 11.7) und der Knopf
    /// (sehr grosse Schrift: untereinander, damit weder "Als Auftrag" bricht noch "Senden" abgeschnitten wird)
    private var input: some View {
        let jobs = store.sagaStatus?.jobsOn == true
        let asJob = jobs && m.jobMode
        let shared = ShareDraftLogic.line(m.shareLine, asJob: asJob, allowed: store.shareJobsAllowed)   // 2275: nur mit "Als Auftrag" + Freigabe
        return VStack(alignment: .leading, spacing: 8) {
            if let p = shared { sharedLine(p) }
            if asJob, let nc = m.noteContext {                         // 2508: Auftrag zu einer offenen Seite, × = ohne sie
                HStack(spacing: 6) {
                    Image(systemName: "doc.text").foregroundStyle(.secondary).accessibilityHidden(true)
                    Text(verbatim: String(localized: "Zur Seite „\(nc.title.isEmpty ? String(localized: "Unbenannt") : nc.title)“"))
                        .font(.subheadline).lineLimit(1).truncationMode(.middle)
                    Spacer(minLength: 4)
                    Button { m.noteContext = nil; focused = true } label: { Image(systemName: "xmark.circle.fill").foregroundStyle(.secondary) }
                        .buttonStyle(.borderless).frame(minWidth: 44, minHeight: 44).accessibilityLabel(Text("Ohne diese Seite"))
                        .disabled(busy)
                }
            }
            TextField(shared != nil ? String(localized: "Was soll \(store.sagaName) damit tun?")
                      : asJob ? String(localized: "Sag \(store.sagaName), was du brauchst …") : String(localized: "Schreib \(store.sagaName) …"),
                      text: $q, axis: .vertical)
                .lineLimit(1...5).textFieldStyle(.roundedBorder).focused($focused)
                .accessibilityLabel(asJob ? Text("Auftrag an \(store.sagaName)") : Text("Nachricht an \(store.sagaName)"))
                .disabled(busy)
            let row = dts.isAccessibilitySize ? AnyLayout(VStackLayout(alignment: .leading, spacing: 8)) : AnyLayout(HStackLayout(spacing: 8))
            row {
                if jobs {                                              // klarer Umschalter, kein Raten, ob es ein Auftrag ist
                    Toggle(isOn: $m.jobMode) { Label("Als Auftrag", systemImage: "checklist") }
                        .toggleStyle(.button).controlSize(.large).frame(minHeight: 44).disabled(busy)
                }
                Spacer(minLength: 4)
                if busy {
                    Button("Stopp") { if m.liveJob != nil { m.stopPlanning() } else { m.stop() } }.buttonStyle(.bordered).controlSize(.large)
                } else {
                    Button(asJob ? String(localized: "Auftrag geben") : String(localized: "Senden")) {
                        let t = q; q = ""
                        if asJob { m.giveInThread(t) } else { m.send(t) }
                    }
                    .prominentGold().controlSize(.large).lineLimit(1)
                    .disabled(!(shared != nil ? ShareDraftLogic.canSend(q, shared: shared) : AILogic.validQuestion(q, max: store.sagaStatus?.qMax ?? 1000))
                              || !store.reachable)
                }
            }
            if !store.reachable {
                Text("Offline – gespeicherter Stand. Senden geht, sobald du wieder verbunden bist.").font(.footnote).foregroundStyle(.secondary)
            }
        }
        .padding(.horizontal).padding(.vertical, 10)
    }

    private func open(_ id: Int) { nav.push(PageRoute(id: id)) }

    /// 2275: "Geteilt: host ×" ueber dem Feld, darunter share_notice des Servers (RT-17: vor dem Senden sichtbar)
    private func sharedLine(_ p: SharePayload) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack(spacing: 6) {
                Image(systemName: "square.and.arrow.down").foregroundStyle(.secondary).accessibilityHidden(true)
                Text(verbatim: SagaShareText.label(ShareDraftLogic.mark(p))).font(.subheadline).lineLimit(1).truncationMode(.middle)
                Spacer(minLength: 4)
                Button { m.shareLine = nil; focused = true } label: { Image(systemName: "xmark.circle.fill").foregroundStyle(.secondary) }
                    .buttonStyle(.borderless).frame(minWidth: 44, minHeight: 44).accessibilityLabel(Text("Geteiltes weglassen"))
                    .disabled(busy).accessibilityIdentifier("kg-share-remove")
            }
            if let n = store.sagaStatus?.shareNotice?.trimmingCharacters(in: .whitespacesAndNewlines), !n.isEmpty {
                Text(verbatim: n).font(.footnote).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
            }
        }
    }
}

/// 2275: Zeile "Geteilt: host" | "Geteilt: „Titel“" | "Geteilt"
enum SagaShareText {
    static func label(_ m: ShareMark) -> String {
        switch ShareDraftLogic.labelKind(m) {
        case .host: return String(localized: "Geteilt: \(m.host ?? "")")
        case .title: return String(localized: "Geteilt: „\(m.title ?? "")“")
        case .plain: return String(localized: "Geteilter Inhalt")
        }
    }
}

/// "aus: Sommerfest, Einladung" (antippbar)
struct SagaSourceLine: View {
    let sources: [AISource]
    let open: (Int) -> Void
    var body: some View {
        let list = sources.filter { $0.pageID != nil }
        if !list.isEmpty {
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 6) { label; ForEach(list) { chip($0) } }
                VStack(alignment: .leading, spacing: 4) { label; ForEach(list) { chip($0) } }
            }
        }
    }
    private var label: some View { Text("aus:").font(.footnote.weight(.semibold)).foregroundStyle(.secondary) }
    private func chip(_ s: AISource) -> some View {
        Button { if let id = s.pageID { open(id) } } label: {
            Text(verbatim: s.title.isEmpty ? String(localized: "Unbenannt") : s.title).font(.footnote).lineLimit(1)
                .padding(.horizontal, 10).frame(minHeight: 32).background(Color.secondary.opacity(0.12), in: Capsule())
        }
        .buttonStyle(.plain).frame(minHeight: 44)
    }
}

// MARK: - Melden (Abschnitt 6: dieselben Gruende wie im Chat, Dialog bleibt bei Fehlern offen)

struct SagaReportItem: Identifiable { let id = UUID(); var `where`: String; var job: Int?; var text: String; var key: String? = nil }

/// "Melden …" unter einer Antwort bzw. Karte; danach "Gemeldet" (je Antwort eine Meldung, Abschnitt 6)
struct SagaReportButton: View {
    @ObservedObject var m: SagaModel
    let key: String
    let open: () -> Void
    var body: some View {
        let done = m.reported.contains(key)
        Button(action: open) { Text(done ? "Gemeldet" : "Melden …") }
            .font(.subheadline).buttonStyle(.borderless).foregroundStyle(.secondary).frame(minHeight: 44).disabled(done)
    }
}

struct SagaReportSheet: View {
    @ObservedObject var m: SagaModel
    let item: SagaReportItem
    @Environment(\.dismiss) private var dismiss
    @State private var reason: ChatStd.ReportReason = .other
    @State private var include = true
    @State private var text = ""
    @State private var busy = false
    @State private var err: String?
    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text("Die Meldung geht an die Admins dieses Saga-Servers – mit dem Text, den du hier mitschickst.").font(.callout).foregroundStyle(.secondary)
                    Picker("Grund", selection: $reason) {
                        Text("Spam").tag(ChatStd.ReportReason.spam)
                        Text("Belästigung oder Beleidigung").tag(ChatStd.ReportReason.abuse)
                        Text("Rechtswidrige Inhalte").tag(ChatStd.ReportReason.illegal)
                        Text("Sonstiges").tag(ChatStd.ReportReason.other)
                    }
                }
                Section {
                    Toggle("Antwort der KI mitschicken", isOn: $include)
                    if include { TextEditor(text: $text).frame(minHeight: 120).accessibilityLabel(Text("Mitgeschickter Text")) }
                } footer: { Text("Du kannst den Text vor dem Senden ändern oder kürzen.") }
                if let err { Text(verbatim: err).foregroundStyle(.red).font(.footnote) }
            }
            .formStyle(.grouped)
            .navigationTitle("KI-Antwort melden")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Abbrechen") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Melden") {
                        busy = true
                        Task {
                            err = await m.report(where: item.where, job: item.job, reason: reason, text: include ? text : nil, key: item.key)
                            busy = false
                            if err == nil { dismiss() }
                        }
                    }.disabled(busy)
                }
            }
            .onAppear { text = String(item.text.prefix(2000)) }
            .onChange(of: text) { _, v in if v.count > 2000 { text = String(v.prefix(2000)) } }
        }
        .macFrame(minWidth: 420, idealWidth: 480, minHeight: 420, idealHeight: 520)
    }
}
