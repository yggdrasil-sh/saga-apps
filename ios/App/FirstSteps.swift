import SwiftUI

// 2.6 (Bugs-DB 2031): "Erste Schritte" (Frage beim ersten Anmelden, Abschnitt unter Einstellungen > Info) und der
// gemeinsame leere Zustand mit naechstem Schritt. Entscheidungen: FirstStepsLogic.swift (mit Tests).

/// Stand von GET /api/first-steps fuer dieses Konto (ein Modell fuer die ganze App)
@MainActor final class FirstStepsModel: ObservableObject {
    static let shared = FirstStepsModel()
    /// nil: unbekannt, aelterer Server oder abgeschaltet - dann nichts zeigen
    @Published private(set) var state: FirstStepsLogic.State?
    /// Server sagt ask:true und in dieser Sitzung noch nicht beantwortet: Frage zeigen, sobald nichts anderes vorne liegt
    @Published var askPending = false
    private var throttle = FirstStepsLogic.Throttle()
    private var asked = false
    private var owner: FirstStepsLogic.Owner?
    /// zaehlt Zuruecksetzungen (Abmelden, Kontowechsel): Antworten auf Anfragen davor gelten nicht mehr
    private var gen = 0
    /// Sitzung, in der die offene Frage entstand - eine Antwort darf nur an dieses Konto gehen
    private var askGen = 0

    private func currentOwner(_ store: Store) -> FirstStepsLogic.Owner {
        FirstStepsLogic.Owner(origin: AppConfig.serverOrigin ?? "", account: store.accountName)
    }

    /// Abrufen (hakt auf dem Server ab): beim Start, beim Wechsel in den Vordergrund, in den Einstellungen - gedrosselt
    func check(_ store: Store, force: Bool = false) async {
        guard AppConfig.hasServer, !store.needsLogin, !store.loginPostponed else { return }
        let who = currentOwner(store)
        if let o = owner, who.differs(from: o) { reset() }           // anderes Konto: Frage und Stand gelten nicht mehr
        owner = who.merged(owner ?? who)                              // Kaltstart: Konto noch unbekannt -> kein Wechsel
        guard throttle.allow(now: Date(), force: force) else { return }
        let g = gen
        guard let (d, code) = try? await store.apiResponse(FirstStepsLogic.path) else { return }   // offline: Stand behalten
        // waehrend des Abrufs abgemeldet bzw. Konto gewechselt (Kopplung): Antwort gehoert zum alten Konto
        guard g == gen, !currentOwner(store).differs(from: who) else { return }
        switch FirstStepsLogic.parse(status: code, data: d) {
        case .off: state = nil; askPending = false
        case .state(let s):
            state = s
            if s.ask && !asked { askPending = true; askGen = gen }
        case .failed: break
        }
    }

    /// Kontowechsel ohne Anmeldefenster (store.accountSwitches): vergessen und fuer das neue Konto neu abrufen
    func accountSwitched(_ store: Store) async {
        reset(); owner = nil
        await check(store, force: true)
    }

    /// Antwort auf die Frage: nur, wenn sie noch zu diesem Konto gehoert (sonst still verwerfen)
    func reply(_ store: Store, yes: Bool) async throws -> Int? {
        guard askGen == gen, !(owner.map { currentOwner(store).differs(from: $0) } ?? false) else { askPending = false; return nil }
        return try await answer(store, yes: yes)
    }

    /// Antwort senden ("yes" legt die Seite an bzw. neu an) -> ID der Seite
    @discardableResult
    func answer(_ store: Store, yes: Bool) async throws -> Int? {
        asked = true; askPending = false
        let g = gen
        let (d, code) = try await store.apiResponse(FirstStepsLogic.path, method: "POST", body: FirstStepsLogic.body(yes: yes))
        guard g == gen else { return nil }                            // inzwischen anderes Konto: nichts uebernehmen/oeffnen
        switch FirstStepsLogic.parsePost(status: code, data: d) {
        case .ok(let page):
            state = FirstStepsLogic.answered(state, yes: yes, page: page)
            return page
        case .off:
            state = nil
            throw APIError.other(String(localized: "Die Einführung ist auf diesem Server abgeschaltet."))
        case .error(let m): throw APIError.http(code, m)
        }
    }

    /// Abgemeldet bzw. anderes Konto
    func reset() { state = nil; askPending = false; asked = false; throttle.reset(); gen += 1 }

    #if DEBUG
    /// nur fuer Bilder (EmptyPreview)
    func preview(_ s: FirstStepsLogic.State?, ask: Bool) { state = s; askPending = ask }
    #endif
}

/// Frage "Moechtest du eine kurze Einfuehrung?" - einmal, nicht ueber anderen Dialogen (Blockers).
/// Anrufe und Chats sind eigene ObservableObjects: hier beobachtet, damit die Sperren aktuell ausgewertet werden.
/// Wegklicken (Escape/⌘.) speichert nichts - nur die beiden Knoepfe antworten; ohne Antwort kommt die Frage beim
/// naechsten Start wieder (wie Web/Android/Linux). Deshalb hat "Nein, danke" KEINE cancel-Rolle.
struct FirstStepsAsk: ViewModifier {
    @ObservedObject var model: FirstStepsModel
    @ObservedObject var calls: CallManager
    @ObservedObject var chat: ChatModel
    let store: Store
    let blockers: () -> FirstStepsLogic.Blockers
    let open: (Int) -> Void
    func body(content: Content) -> some View {
        content.alert(Text("Möchtest du eine kurze Einführung?"),
                      isPresented: Binding(get: { FirstStepsLogic.showAsk(pending: model.askPending, blockers: blockers()) },
                                           set: { if !$0 { model.askPending = false } })) {
            Button("Ja, gern") { reply(true) }.keyboardShortcut(.defaultAction)
            Button("Nein, danke") { reply(false) }
        } message: {
            Text("Saga legt dir eine Seite „Erste Schritte“ mit ein paar Häkchen an: Profilbild, Chats, App, erste Seite und Aufgabe. Was du erledigt hast, hakt Saga selbst ab – und die Seite kannst du jederzeit löschen.")
        }
    }
    private func reply(_ yes: Bool) {
        Task { @MainActor in
            do {
                let page = try await model.reply(store, yes: yes)
                if yes, let page { open(page) }
            } catch { store.error = error.localizedDescription }
        }
    }
}

/// Einstellungen > Info: "Erste Schritte" wieder abrufen (beide Knoepfe = POST yes, dann die Seite oeffnen)
struct FirstStepsSection: View {
    var close: (() -> Void)?
    @EnvironmentObject private var store: Store
    @ObservedObject private var model = FirstStepsModel.shared
    @State private var busy = false
    @State private var error = ""
    var body: some View {
        if let h = FirstStepsLogic.help(model.state) {
            Section {
                switch h {
                case .open(let n, let all): Text("Deine Seite „Erste Schritte“: \(n) von \(all) erledigt.")
                case .create: Text("Eine Seite mit ein paar Häkchen für den Anfang – Saga hakt Erledigtes selbst ab.").foregroundStyle(.secondary)
                }
                Button { go() } label: {
                    if h == .create { Text("Erste Schritte anlegen") } else { Text("Erste Schritte öffnen") }
                }.disabled(busy)
                if !error.isEmpty { Text(error).font(.footnote).foregroundStyle(.red) }
            } header: { Text("Erste Schritte") }
            .id("firststeps")
        }
    }
    private func go() {
        busy = true; error = ""
        Task { @MainActor in
            defer { busy = false }
            do {
                guard let page = try await model.answer(store, yes: true) else { return }
                await store.refresh(quiet: true)
                close?()                                                 // erst die Einstellungen schliessen, dann die Seite
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.6) {
                    NotificationCenter.default.post(name: .hkOpenTarget, object: NotifTarget.page(id: page, block: nil))
                }
            } catch { self.error = error.localizedDescription }
        }
    }
}

/// Leerer Zustand einer Liste: ein Satz, was hier erscheint, und hoechstens EIN Knopf zum naechsten Schritt
/// (bzw. Hinweis und Knopf einer fehlenden Voraussetzung, z. B. "Kalender verbinden"). Ohne KI.
struct EmptyHint: View {
    let text: Text
    var action: Text? = nil
    var run: (() -> Void)? = nil
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            text.foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
            if let action, let run {
                Button(action: run) { action }
                    .prominentGold()
                    .controlSize(.large)                                     // Touch-Ziel >= 44 pt
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.vertical, 6)
        .accessibilityElement(children: .contain)
    }
}
