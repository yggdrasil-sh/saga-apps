import AppIntents
import CoreSpotlight
import Foundation
#if canImport(UniformTypeIdentifiers)
import UniformTypeIdentifiers
#endif

// Siri, Kurzbefehle, Action-Button und Spotlight-Aktionen.
// Siri-Saetze: hier Englisch (Entwicklungssprache), Deutsch/Spanisch in AppShortcuts.xcstrings.
// Ablegen laeuft ueber den App-Schluessel (/api/app/*) - funktioniert auch, ohne die App zu oeffnen.

struct AddTaskIntent: AppIntent {
    static var title: LocalizedStringResource = "Aufgabe anlegen"
    static var description = IntentDescription("Legt eine Aufgabe in Saga an – optional mit Fälligkeit.")
    @Parameter(title: "Aufgabe", requestValueDialog: "Was soll auf die Liste?") var text: String
    @Parameter(title: "Fällig am") var due: Date?
    /// 2.5 capsrc1: Inbox oder eine Aufgabenquelle; leer = zuletzt gewaehltes Ziel. Server ohne capsrc1: nur Inbox.
    @Parameter(title: "Aufgabenziel") var target: HKTaskTargetEntity?
    static var parameterSummary: some ParameterSummary { Summary("Aufgabe \(\.$text) anlegen") { \.$due; \.$target } }
    func perform() async throws -> some IntentResult & ProvidesDialog {
        let st = AppConfig.store
        let cap = CaptureTargetLogic.capsrc(live: await API.features(), cached: st.bool(forKey: CaptureTargetLogic.featureKey))
        let chosen: Int?
        if let target { chosen = target.id }
        else { chosen = cap ? CaptureTargetLogic.defaultTarget(last: st.object(forKey: CaptureTargetLogic.lastKey) as? Int, choices: CaptureTargets.cached() ?? []) : nil }
        let src = CaptureTargetLogic.src(target: chosen, capsrc: cap)
        let name = target.map(\.label) ?? src.flatMap { s in CaptureTargets.cached()?.first { $0.db == s }?.name } ?? String(localized: "Aufgabenquelle")
        do {
            try await API.captureTask(title: text, due: due, src: src, capsrc: cap)
        } catch APIError.http(let code, let msg) {
            let f = CaptureTargetLogic.failure(code: code, message: msg, src: src)
            if CaptureTargetLogic.forgetLast(f) { st.removeObject(forKey: CaptureTargetLogic.lastKey) }
            if let m = CaptureTargetLogic.message(f, name: name) { throw APIError.other(m) }
            throw APIError.http(code, msg)
        }
        if cap, let chosen { st.set(chosen, forKey: CaptureTargetLogic.lastKey) }   // zuletzt gewaehlt = naechste Vorgabe
        if src != nil {
            return .result(dialog: "Aufgabe „\(text)“ in „\(name)“ angelegt.")
        }
        if let due {
            let when = due.formatted(SagaDate.dateTime().weekday(.wide).day().month(.wide))
            return .result(dialog: "Aufgabe „\(text)“ angelegt – fällig \(when).")
        }
        return .result(dialog: "Aufgabe „\(text)“ angelegt.")
    }
}

/// Ziel fuer "Aufgabe anlegen": Inbox (id 0) oder eine Aufgabenquelle (Datenbank-ID)
struct HKTaskTargetEntity: AppEntity {
    static var typeDisplayRepresentation: TypeDisplayRepresentation = "Aufgabenziel"
    static var defaultQuery = HKTaskTargetQuery()
    let id: Int
    let name: String
    let icon: String?
    /// Name fuer Meldungen (Inbox uebersetzt, Quellen wie geliefert)
    var label: String { id == CaptureTargetLogic.inbox ? String(localized: "Inbox") : name }
    var displayRepresentation: DisplayRepresentation {
        id == CaptureTargetLogic.inbox ? DisplayRepresentation(title: "Inbox", image: .init(systemName: "tray"))
            : DisplayRepresentation(title: "\(icon.map { $0 + " " } ?? "")\(name)")
    }
    static let inbox = HKTaskTargetEntity(id: CaptureTargetLogic.inbox, name: "", icon: nil)
    init(id: Int, name: String, icon: String?) { self.id = id; self.name = name; self.icon = icon }
    init(_ s: TaskSource) { self.init(id: s.db, name: s.name, icon: s.emoji) }
}

/// Gemerkte Ziele (von der App beim Laden der Aufgabenliste geschrieben); ohne sie die abonnierten Quellen vom Server
enum CaptureTargets {
    static func cached() -> [TaskSource]? { CaptureTargetLogic.decodeTargets(AppConfig.store.data(forKey: CaptureTargetLogic.targetsKey)) }
    static func load() async -> [HKTaskTargetEntity] {
        let cap = CaptureTargetLogic.capsrc(live: await API.features(), cached: AppConfig.store.bool(forKey: CaptureTargetLogic.featureKey))
        guard cap else { return [.inbox] }                                   // aelterer Server: nur Inbox
        let c = cached()
        let list = CaptureTargetLogic.choices(cached: c, current: c == nil ? await API.todaySources() : nil)
        return [.inbox] + list.map(HKTaskTargetEntity.init)
    }
}

struct HKTaskTargetQuery: EntityQuery {
    func entities(for identifiers: [Int]) async throws -> [HKTaskTargetEntity] {
        let all = await CaptureTargets.load()
        // im Kurzbefehl gespeichertes Ziel, das es nicht mehr gibt: trotzdem aufloesen (perform meldet dann den Fehler)
        return identifiers.map { i in all.first { $0.id == i } ?? HKTaskTargetEntity(id: i, name: String(localized: "Aufgabenquelle"), icon: nil) }
    }
    func suggestedEntities() async throws -> [HKTaskTargetEntity] { await CaptureTargets.load() }
    func defaultResult() async -> HKTaskTargetEntity? {
        let all = await CaptureTargets.load()
        let d = CaptureTargetLogic.defaultTarget(last: AppConfig.store.object(forKey: CaptureTargetLogic.lastKey) as? Int,
                                                 choices: all.filter { $0.id != CaptureTargetLogic.inbox }.map { TaskSource(db: $0.id) })
        return all.first { $0.id == d } ?? .inbox
    }
}

struct CaptureNoteIntent: AppIntent {
    static var title: LocalizedStringResource = "Notiz ablegen"
    static var description = IntentDescription("Legt eine Notiz als neue Seite im Eingang ab.")
    @Parameter(title: "Notiz", inputOptions: String.IntentInputOptions(multiline: true), requestValueDialog: "Was möchtest du notieren?") var text: String
    static var parameterSummary: some ParameterSummary { Summary("Notiz \(\.$text) ablegen") }
    func perform() async throws -> some IntentResult & ProvidesDialog {
        let lines = text.split(separator: "\n", maxSplits: 1, omittingEmptySubsequences: false)
        let title = String(lines.first ?? "").trimmingCharacters(in: .whitespaces)
        try await API.capture(kind: "page", title: String(title.prefix(80)), text: lines.count > 1 ? String(lines[1]) : (title.count > 80 ? text : ""), url: nil)
        return .result(dialog: "Notiz im Eingang abgelegt.")
    }
}

/// Seite fuer "Seite öffnen" (Auswahl mit Suche)
struct HKPageEntity: AppEntity {
    static var typeDisplayRepresentation: TypeDisplayRepresentation = "Seite"
    static var defaultQuery = HKPageQuery()
    let id: Int
    let name: String
    let icon: String?
    var displayRepresentation: DisplayRepresentation { DisplayRepresentation(title: "\(icon.map { $0 + " " } ?? "")\(name)") }
}
struct HKPageQuery: EntityStringQuery {
    private static func all() async throws -> [HKPageEntity] {
        try await API.pages().map { HKPageEntity(id: $0.id, name: $0.title.isEmpty ? String(localized: "Unbenannt") : $0.title, icon: $0.emoji) }
    }
    func entities(for identifiers: [Int]) async throws -> [HKPageEntity] { let a = try await Self.all(); return identifiers.compactMap { i in a.first { $0.id == i } } }
    func entities(matching string: String) async throws -> [HKPageEntity] { try await Self.all().filter { $0.name.localizedCaseInsensitiveContains(string) } }
    func suggestedEntities() async throws -> [HKPageEntity] { Array(try await Self.all().prefix(60)) }
}

struct OpenPageIntent: AppIntent {
    static var title: LocalizedStringResource = "Seite öffnen"
    static var description = IntentDescription("Öffnet eine Seite in Saga.")
    @Parameter(title: "Seite") var page: HKPageEntity
    static var parameterSummary: some ParameterSummary { Summary("\(\.$page) öffnen") }
    func perform() async throws -> some IntentResult & OpensIntent {
        .result(opensIntent: OpenURLIntent(URL(string: "hlidskjalf://page/\(page.id)")!))
    }
}

struct OpenTasksIntent: AppIntent {
    static var title: LocalizedStringResource = "Aufgaben öffnen"
    static var description = IntentDescription("Zeigt die offenen Aufgaben.")
    func perform() async throws -> some IntentResult & OpensIntent { .result(opensIntent: OpenURLIntent(URL(string: "hlidskjalf://tasks")!)) }
}

#if os(iOS)
// MARK: - 2.6 K-G (Bugs-DB 2275): Saga per Siri - "Frag Saga", "Gib Saga einen Auftrag", "Was steht heute an"
// Logik: SagaIntentLogic.swift. Laeuft im App-Prozess mit derselben Sitzung wie die App (Store.app(), auch wenn Siri die
// App im Hintergrund startet). Alle drei nur entsperrt (.requiresAuthentication: gesperrt/CarPlay kein Vorlesen).
// Siri-Saetze nennen immer "Saga" (App-Name), nie den Persona-Namen. Fragen und Auftraege laufen ueber das SagaModel:
// dauert es laenger, geht es in der App weiter - der Strom wird dabei NIE abgebrochen (ein Abbruch stoppte serverseitig
// das Modell). Aus Siri wird nie freigegeben: kein approve, kein act (F3, RT-32) - der Auftrag endet an der Plan-Karte.

@MainActor
enum SagaIntentClient {
    /// Angemeldet wie beim Start der App (Geraete-Schluessel bzw. App-Schluessel) und kein Anmeldefenster offen
    static func signedIn(_ s: Store) -> Bool { AppConfig.hasServer && (DeviceKey.get() != nil || AppConfig.hasToken) && !s.needsLogin }
    /// Status frisch holen (Einwilligung kann sich auf einem anderen Geraet geaendert haben)
    static func status(_ s: Store) async -> SagaStatus? {
        await s.loadAIStatus()
        return s.sagaStatus
    }
    /// Warten, bis `done()` gilt (true) oder die Zeit um ist (false)
    static func wait(_ seconds: TimeInterval, _ done: () -> Bool) async -> Bool {
        let end = Date().addingTimeInterval(seconds)
        while Date() < end {
            if done() { return true }
            try? await Task.sleep(for: .milliseconds(200))
        }
        return done()
    }
    /// Text vom Server unveraendert als Siri-Antwort (kein Schluessel im Katalog)
    static func verbatim(_ s: String) -> IntentDialog { IntentDialog("\(s)") }
}

struct AskSagaIntent: AppIntent {
    static var title: LocalizedStringResource = "Saga fragen"
    static var description = IntentDescription("Stellt Saga eine Frage zu deinen Seiten. Die Antwort kommt in Siri – dauert es länger, geht es in der App weiter.")
    static var authenticationPolicy: IntentAuthenticationPolicy = .requiresAuthentication
    static var supportedModes: IntentModes = [.background, .foreground(.dynamic)]
    @Parameter(title: "Frage", requestValueDialog: "Was möchtest du Saga fragen?") var question: String
    static var parameterSummary: some ParameterSummary { Summary("Saga \(\.$question) fragen") }

    @MainActor
    func perform() async throws -> some IntentResult & ProvidesDialog {
        let store = Store.app()
        let st = await SagaIntentClient.status(store)
        switch SagaIntentLogic.gate(.ask, signedIn: SagaIntentClient.signedIn(store), status: st) {
        case .signIn: return .result(dialog: "Melde dich zuerst in der Saga-App an.")
        case .off: return .result(dialog: "Fragen an Saga sind für dich nicht eingeschaltet.")
        case .intro:                                              // ohne Einwilligung nichts ans Modell: App mit Kennenlernen
            try await continueInForeground(nil, alwaysConfirm: false)
            SagaOpen.request(.conversation)
            return .result(dialog: "Lerne Saga zuerst in der App kennen.")
        case .ok: break
        }
        let m = store.saga
        let q = question.trimmingCharacters(in: .whitespacesAndNewlines)
        guard AILogic.validQuestion(q, max: st?.qMax ?? 1000) else { return .result(dialog: "Die Frage ist leer oder zu lang.") }
        // liegengebliebene Fehler-/Abbruch-Karten zaehlen nicht (send/giveInThread raeumen sie selbst weg)
        guard !SagaIntentLogic.busy(asking: m.live != nil, askFailed: m.liveError != nil, planning: m.liveJob != nil,
                                    planEnded: m.liveJob?.error != nil || m.liveJob?.stopped == true) else {
            return .result(dialog: try await onward("Saga ist gerade beschäftigt – weiter in der App."))
        }
        m.send(q)
        guard m.live != nil else { return .result(dialog: "Das hat nicht geklappt.") }
        let done = await SagaIntentClient.wait(SagaIntentLogic.answerWait) { m.live == nil || m.liveError != nil }
        let ok = done && m.live == nil && m.liveError == nil
        let answer = ok ? m.thread.turns.last(where: { !$0.mine })?.text : nil
        switch SagaIntentLogic.askEnd(running: !done, error: done && !ok ? (m.liveError ?? "") : nil, answer: answer) {
        case .answer(let t, let cut):
            return .result(dialog: SagaIntentClient.verbatim(cut ? t + " " + String(localized: "Mehr in der Saga-App.") : t))
        case .foreground:                                         // F2: Strom laeuft im SagaModel weiter
            return .result(dialog: try await onward("Das dauert etwas länger – die Antwort erscheint in Saga."))
        case .failed(let e):
            return .result(dialog: e.isEmpty ? "Das hat nicht geklappt." : SagaIntentClient.verbatim(e))
        }
    }

    @MainActor
    private func onward(_ dialog: IntentDialog) async throws -> IntentDialog {
        try await continueInForeground(nil, alwaysConfirm: false)
        SagaOpen.request(.conversation)
        return dialog
    }
}

struct GiveJobIntent: AppIntent {
    static var title: LocalizedStringResource = "Saga einen Auftrag geben"
    static var description = IntentDescription("Saga plant den Auftrag gleich. Der Plan wartet in der App auf dein OK – aus Siri wird nichts freigegeben.")
    static var authenticationPolicy: IntentAuthenticationPolicy = .requiresAuthentication
    static var supportedModes: IntentModes = [.background, .foreground(.dynamic)]
    @Parameter(title: "Auftrag", inputOptions: String.IntentInputOptions(multiline: true), requestValueDialog: "Was soll Saga für dich erledigen?") var text: String
    static var parameterSummary: some ParameterSummary { Summary("Saga den Auftrag \(\.$text) geben") }

    @MainActor
    func perform() async throws -> some IntentResult & ProvidesDialog {
        let store = Store.app()
        let st = await SagaIntentClient.status(store)
        switch SagaIntentLogic.gate(.give, signedIn: SagaIntentClient.signedIn(store), status: st) {
        case .signIn: return .result(dialog: "Melde dich zuerst in der Saga-App an.")
        case .off: return .result(dialog: "Aufträge an Saga sind für dich nicht eingeschaltet.")
        case .intro: return .result(dialog: try await onward("Lerne Saga zuerst in der App kennen."))
        case .ok: break
        }
        let m = store.saga
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard AILogic.validQuestion(t, max: st?.qMax ?? 1000) else { return .result(dialog: "Der Auftrag ist leer oder zu lang.") }
        // liegengebliebene Fehler-/Abbruch-Karten zaehlen nicht (send/giveInThread raeumen sie selbst weg)
        guard !SagaIntentLogic.busy(asking: m.live != nil, askFailed: m.liveError != nil, planning: m.liveJob != nil,
                                    planEnded: m.liveJob?.error != nil || m.liveJob?.stopped == true) else {
            return .result(dialog: try await onward("Saga ist gerade beschäftigt – weiter in der App."))
        }
        if !m.threadLoaded { await m.loadThread() }
        m.noteContext = nil                                       // eine Seite von vorhin gehoert nicht zu diesem Auftrag
        let n0 = SagaLogic.maxN(m.thread.turns), count = m.thread.turns.count
        // Einziger Serverweg: POST /api/ai/jobs {thread: true} (SagaIntentLogic.giveRequest) - Plan-Karte, keine Freigabe
        m.jobMode = true
        m.giveInThread(t)
        guard m.liveJob != nil else { return .result(dialog: "Das hat nicht geklappt.") }
        let done = await SagaIntentClient.wait(SagaIntentLogic.planWait) { m.liveJob == nil || m.liveJob?.error != nil || m.liveJob?.stopped == true }
        switch SagaIntentLogic.giveEnd(running: !done, error: m.liveJob?.error, turns: m.thread.turns, n0: n0, count: count) {
        case .plan: return .result(dialog: try await onward("Der Plan liegt in Saga bereit."))
        case .ask: return .result(dialog: try await onward("Saga hat eine Rückfrage – antworte in der App."))
        case .refuse(let r):
            let s = SagaIntentLogic.speech(r).text
            return .result(dialog: try await onward(s.isEmpty ? "Das macht Saga so nicht – mehr in der App." : SagaIntentClient.verbatim(s)))
        case .planning: return .result(dialog: try await onward("Saga plant noch – der Plan erscheint in der App."))
        case .failed(let e): return .result(dialog: e.isEmpty ? "Das hat nicht geklappt." : SagaIntentClient.verbatim(e))
        }
    }

    /// Weiter in der App (Ort "Saga", Gespraech mit der Plan-Karte) - das Planen laeuft im SagaModel weiter
    @MainActor
    private func onward(_ dialog: IntentDialog) async throws -> IntentDialog {
        try await continueInForeground(nil, alwaysConfirm: false)
        SagaOpen.request(.conversation)
        return dialog
    }
}

struct TodayIntent: AppIntent {
    static var title: LocalizedStringResource = "Was steht heute an?"
    static var description = IntentDescription("Liest den Tag aus Saga vor – Termine, fällige Aufgaben und was auf dich wartet. Ohne Sprachmodell berechnet.")
    static var authenticationPolicy: IntentAuthenticationPolicy = .requiresAuthentication

    @MainActor
    func perform() async throws -> some IntentResult & ProvidesDialog {
        let store = Store.app()
        let signed = SagaIntentClient.signedIn(store)
        if signed && store.features.set.isEmpty {                         // Start im Hintergrund: Merkmale erst holen
            await store.checkVersion()
            if store.serverVersion == nil { return .result(dialog: "Das hat nicht geklappt.") }   // Netz weg: nicht "nicht eingeschaltet"
        }
        switch SagaIntentLogic.todayGate(signedIn: signed, features: store.features.set, state: nil) {
        case .signIn: return .result(dialog: "Melde dich zuerst in der Saga-App an.")
        case .off, .intro: return .result(dialog: "Der Tagesüberblick ist für dich nicht eingeschaltet.")
        case .ok: break
        }
        guard let (d, code) = try? await store.apiResponse(TodayLineLogic.path(tz: TimeZone.current.identifier)) else {
            return .result(dialog: "Das hat nicht geklappt.")                // Netz weg: kurzer Satz statt Siri-Fehler
        }
        let s = TodayLineLogic.decode(d)
        if (200..<300).contains(code), SagaIntentLogic.todayGate(signedIn: signed, features: store.features.set, state: s) == .off {
            return .result(dialog: "Der Tagesüberblick ist für dich nicht eingeschaltet.")
        }
        guard case .state(let state) = TodayLineLogic.result(status: code, data: d) else { return .result(dialog: "Das hat nicht geklappt.") }
        guard let t = SagaIntentLogic.todaySpeech(state) else { return .result(dialog: "Für heute liegt nichts an.") }
        return .result(dialog: SagaIntentClient.verbatim(t))
    }
}
#endif

struct HKShortcuts: AppShortcutsProvider {
    // 7 von 10 moeglichen Kurzbefehlen (2.6 K-G: drei fuer Saga, nur iOS)
    static var appShortcuts: [AppShortcut] {
        AppShortcut(intent: AddTaskIntent(), phrases: ["New task in \(.applicationName)", "Add a task in \(.applicationName)", "\(.applicationName) task"],
                    shortTitle: "Aufgabe anlegen", systemImageName: "checklist")
        AppShortcut(intent: CaptureNoteIntent(), phrases: ["Note in \(.applicationName)", "Save a note in \(.applicationName)"],
                    shortTitle: "Notiz ablegen", systemImageName: "square.and.pencil")
        AppShortcut(intent: OpenPageIntent(), phrases: ["Open a page in \(.applicationName)", "Open \(\.$page) in \(.applicationName)"],
                    shortTitle: "Seite öffnen", systemImageName: "doc.text")
        AppShortcut(intent: OpenTasksIntent(), phrases: ["Tasks in \(.applicationName)", "Show \(.applicationName) tasks"],
                    shortTitle: "Aufgaben", systemImageName: "checkmark.circle")
        #if os(iOS)
        AppShortcut(intent: AskSagaIntent(), phrases: ["Ask \(.applicationName)", "Ask \(.applicationName) a question"],
                    shortTitle: "Saga fragen", systemImageName: "sparkles")
        AppShortcut(intent: GiveJobIntent(), phrases: ["Give \(.applicationName) a job", "New job for \(.applicationName)"],
                    shortTitle: "Auftrag geben", systemImageName: "checklist.checked")
        AppShortcut(intent: TodayIntent(), phrases: ["What's on today in \(.applicationName)", "\(.applicationName) today"],
                    shortTitle: "Heute in Saga", systemImageName: "sun.max")
        #endif
    }
}

// MARK: - Spotlight: alle Seiten in der Systemsuche (Tippen oeffnet die Seite)

/// Seiten in der Systemsuche. Abgleich im Hintergrund (nie auf dem Main-Thread), gebuendelt (nur der neueste Stand
/// zaehlt) und inkrementell: nach dem ersten Abgleich je Prozess werden nur geaenderte/neue Eintraege indiziert und
/// entfernte geloescht (bisher: nach jeder Aenderung alles loeschen und neu). Inhalt der Eintraege unveraendert.
enum SpotlightIndex {
    private static let worker = SpotlightWorker()
    /// Nach jeder Aenderung der Seitenliste (Aufruf vom Main-Thread kostet nur das Uebergeben des Arrays)
    static func update(_ pages: [Page]) { worker.submit(pages) }
    static func clear() { worker.clear() }
    /// "page-12" aus einem Spotlight-Treffer
    static func pageID(_ activity: NSUserActivity) -> Int? {
        (activity.userInfo?[CSSearchableItemActivityIdentifier] as? String).flatMap { $0.hasPrefix("page-") ? Int($0.dropFirst(5)) : nil }
    }
}

/// Ein Arbeiter fuer alle Spotlight-Auftraege: nacheinander, im Hintergrund; neue Staende ersetzen noch nicht
/// begonnene (bei schnellen Aenderungen wird nur der letzte indiziert). Abmelden bricht laufendes Indizieren ab.
final class SpotlightWorker: @unchecked Sendable {
    private let lock = NSLock()
    private var pending: [Page]?
    private var clearWanted = false
    private var running = false
    private var epoch = 0                                   // zaehlt bei jedem clear(): laufender Abgleich hoert auf
    private var known: [Int: SpotlightEntry]?               // was dieser Prozess indiziert hat (nur im Arbeiter benutzt)
    private static let chunk = 500
    private static let domain = "pages"

    func submit(_ pages: [Page]) {
        lock.lock(); pending = pages; let start = !running; running = true; lock.unlock()
        if start { Task.detached(priority: .utility) { await self.drain() } }
    }
    func clear() {
        lock.lock(); pending = nil; clearWanted = true; epoch += 1; let start = !running; running = true; lock.unlock()
        if start { Task.detached(priority: .utility) { await self.drain() } }
    }
    private func currentEpoch() -> Int { lock.lock(); defer { lock.unlock() }; return epoch }

    /// Naechsten Auftrag holen (nil: nichts mehr zu tun, Arbeiter endet)
    private func take() -> (wipe: Bool, next: [Page]?, epoch: Int)? {
        lock.lock(); defer { lock.unlock() }
        let wipe = clearWanted, next = pending
        clearWanted = false; pending = nil
        if !wipe && next == nil { running = false; return nil }
        return (wipe, next, epoch)
    }

    private func drain() async {
        while let (wipe, next, ep) = take() {
            let idx = CSSearchableIndex.default()
            if wipe { known = nil; try? await idx.deleteSearchableItems(withDomainIdentifiers: [Self.domain]) }
            if let next { await apply(next, epoch: ep, idx) }
        }
    }

    private func apply(_ pages: [Page], epoch ep: Int, _ idx: CSSearchableIndex) async {
        let entries = SpotlightPlan.entries(pages)
        let d = SpotlightPlan.diff(old: known, new: entries)
        if d.full { try? await idx.deleteSearchableItems(withDomainIdentifiers: [Self.domain]) }
        if !d.delete.isEmpty { try? await idx.deleteSearchableItems(withIdentifiers: d.delete.map { "page-\($0)" }) }
        var i = 0, failed = false
        while i < d.upsert.count {
            guard currentEpoch() == ep else { known = nil; return }        // abgemeldet: der folgende clear() raeumt auf
            let items = d.upsert[i..<min(i + Self.chunk, d.upsert.count)].map(Self.item)
            do { try await idx.indexSearchableItems(items) } catch { failed = true }
            i += Self.chunk
        }
        // Fehler beim Indizieren: beim naechsten Mal wieder alles (wie bisher), sonst fehlten Eintraege dauerhaft
        known = currentEpoch() == ep && !failed ? SpotlightPlan.known(entries) : nil
    }

    private static func item(_ e: SpotlightEntry) -> CSSearchableItem {
        let a = CSSearchableItemAttributeSet(contentType: .text)
        a.title = e.title
        a.contentDescription = e.desc
        if let dn = e.display { a.displayName = dn }
        return CSSearchableItem(uniqueIdentifier: "page-\(e.id)", domainIdentifier: domain, attributeSet: a)
    }
}
