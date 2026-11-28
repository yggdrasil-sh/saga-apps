import SwiftUI

// Ort "Saga" (2.6, Bugs-DB 2269 K-I + 2267 K-B): Zustand und Serverwege fuer Tab, Kennenlernen, Gespraech, Auftraege und
// Einstellungen › Saga. Reine Logik: SagaLogic.swift. Der Server prueft alles (Einwilligung, Rechte, Plan-Vertrag) -
// die App zeigt nur, was er meldet, und schickt Freigaben nur mit genau dem gezeigten plan_v + plan_hash.

@MainActor
final class SagaModel: ObservableObject {
    weak var store: Store?

    /// Laufendes Planen im Gespraech ("Als Auftrag"): Bitte, Statuszeile, Fehler bzw. abgebrochen
    struct LiveJob: Equatable { var said: String; var status: String; var error: String?; var stopped = false; var mark: ShareMark? = nil }

    // Gespraech
    @Published var thread = SagaThread()
    @Published var threadLoaded = false
    @Published var live: SagaStream?                                // laufende Antwort
    @Published var liveQ: String?
    @Published var liveError: String?
    // Auftraege
    @Published var jobs = SagaJobList()
    @Published var jobsLoaded = false
    @Published var detail: SagaJobDetail?
    @Published var openJob: Int?
    /// 2.6 (2271): "Wartet auf dich" aus der Tageszeile - SagaScreen zeigt das Segment "Aufträge"
    @Published var wantJobs = false
    /// "Als Auftrag" (Umschalter neben dem Eingabefeld, Abschnitt 11.7)
    @Published var jobMode = false
    /// 2.6 K-G (2508): Auftrag zu einer offenen Seite - "Zur Seite „…“" ueber dem Eingabefeld (× weg), jeder Auftrag geht
    /// dann mit context.note an den Server (der prueft das Leserecht; ohne Recht bleibt die Seite still draussen)
    struct NoteContext: Equatable { var id: Int; var title: String }
    @Published var noteContext: NoteContext?
    /// 2.6 K-G: Siri bzw. "soll sich kümmern …" - SagaScreen zeigt das Gespraech (nicht die Auftrags-Liste)
    @Published var wantConversation = false
    /// 2.6 K-G (2275): geteilter Inhalt aus der Teilen-Erweiterung (Entwurf uebernommen) - Zeile "Geteilt: host ×" ueber dem
    /// Feld; geht nur mit dem naechsten Auftrag (bzw. der Antwort auf seine Rueckfrage) als shared an den Server
    @Published var shareLine: SharePayload?
    /// Rueckfragen zu geteiltem Inhalt: Zug -> Inhalt (geht mit der Wahl erneut mit; nur im Speicher, nach Neustart weg)
    var askShared: [String: SharePayload] = [:]
    @Published var liveJob: LiveJob?
    /// Jetziger Stand je Auftrag (Zuege mit job_id im Gespraech); weg = 404
    @Published var jobState: [Int: SagaJobDetail] = [:]
    @Published var jobGone: Set<Int> = []
    /// Plan-Karten, an denen gerade "Aendern" offen ist (Live zeichnet sie dann nicht neu)
    var editing: Set<Int> = []
    /// 2515: Auftraege, deren Freigabe mit 409 stale abgelehnt wurde - ihr neuer Plan zeigt "geändert – bitte neu freigeben"
    @Published var staleSeen: Set<Int> = []
    /// 2515: Plan-Fassungen ("job/plan_v"), die anderswo freigegeben wurden (409 state): "Passt, los" bleibt aus
    @Published var settled: Set<String> = []
    /// 2515: Rueckgaengig mit 409 changed je Aenderung -> Pfad der alten Fassung ("" = keine); lebt laenger als die Karte
    @Published var undoConflicts: [Int: String] = [:]
    private var lastResync: Date?
    // Hinweise
    /// Kurzer Hinweis unten; VoiceOver sagt ihn an (sonst hoerte man z. B. "Zurückgenommen" nach Rueckgaengig nicht)
    @Published var toast: String? { didSet { if let t = toast, t != oldValue { AccessibilityNotification.Announcement(t).post() } } }
    @Published var cardError: String?
    @Published var busy = false
    /// Gemeldete Antworten/Karten (Abschnitt 6: je Antwort eine Meldung, Knopf danach "Gemeldet")
    @Published var reported: Set<String> = []

    private var streamTask: Task<Void, Never>?
    private var jobTask: Task<Void, Never>?
    private lazy var session: URLSession = {
        let c = URLSessionConfiguration.default
        c.timeoutIntervalForRequest = 90; c.timeoutIntervalForResource = 600
        return URLSession(configuration: c)
    }()

    var status: SagaStatus? { store?.sagaStatus }

    func reset() {
        streamTask?.cancel(); jobTask?.cancel()
        thread = SagaThread(); threadLoaded = false; live = nil; liveQ = nil; liveError = nil
        jobs = SagaJobList(); jobsLoaded = false; detail = nil; openJob = nil; jobMode = false; liveJob = nil; noteContext = nil; shareLine = nil; askShared = [:]
        jobState = [:]; jobGone = []; editing = []; toast = nil; cardError = nil; busy = false; reported = []
        staleSeen = []; settled = []; undoConflicts = [:]; lastResync = nil
    }
    /// Hoechste Zugnummer des Servers im Verlauf (lokal angehaengte Zuege ohne n zaehlen nicht)
    private var maxN: Int { SagaLogic.maxN(thread.turns) }
    private func serverTurns(after n0: Int) -> Bool { SagaLogic.hasServerTurns(thread.turns, after: n0) }

    private func err(_ d: Data, _ code: Int) -> String {
        ShareJSON.obj(d).flatMap { ShareJSON.str($0["error"]) } ?? APIError.http(code, "").localizedDescription
    }
    /// Server meldet "erst kennenlernen": Status neu laden (die Karte erscheint wieder)
    private func consentLost() async { await store?.loadAIStatus() }

    // MARK: Einwilligung (K19)

    func consent(_ a: SagaLogic.ConsentAnswer) async {
        guard let store, let intro = status?.intro else { return }
        busy = true; cardError = nil
        defer { busy = false }
        do {
            let (d, code) = try await store.apiResponse("api/ai/consent", method: "POST", body: SagaLogic.consentBody(a, intro: intro))
            switch SagaLogic.consentResult(code: code, body: d) {
            case .ok:
                await store.loadAIStatus()
                if a != .later { await loadThread(); if status?.jobsOn == true { await loadJobs() } }
            case .reload(let m):                                   // Text oder Host geaendert: neu laden, Karte neu zeigen
                cardError = m.isEmpty ? nil : m
                await store.loadAIStatus()
            case .failed(let m): cardError = m.isEmpty ? APIError.http(code, "").localizedDescription : m
            }
        } catch { cardError = error.localizedDescription }
    }

    // MARK: Gespraech (K10)

    func loadThread() async {
        guard let store, status?.consentOK == true, streamTask == nil else { return }   // waehrend einer Frage nicht ersetzen
        guard let (d, code) = try? await store.apiResponse("api/ai/thread") else { return }
        if code == 200, let t = SagaThread(data: d) { thread = t; threadLoaded = true }
        else if SagaLogic.needsConsent(code: code, body: d) { await consentLost() }
    }

    func send(_ q: String) {
        let t = q.trimmingCharacters(in: .whitespacesAndNewlines)
        clearFinished()
        guard let store, live == nil, liveJob == nil, AILogic.validQuestion(t, max: status?.qMax ?? 1000) else { return }
        liveQ = t; liveError = nil; live = SagaStream()
        let n0 = maxN
        streamTask = Task { [weak self] in
            guard let self else { return }
            var s = SagaStream()
            do {
                let (bytes, code, body) = try await store.openStream("api/ai/thread", method: "POST", body: ["q": t], session: self.session)
                guard code == 200 else {
                    if SagaLogic.needsConsent(code: code, body: body) { await self.consentLost() }
                    self.finish(q: t, s, error: body.flatMap(ShareJSON.obj).flatMap { ShareJSON.str($0["error"]) } ?? APIError.http(code, "").localizedDescription)
                    return
                }
                var parser = SSEParser(), buf = Data()
                for try await b in bytes {
                    buf.append(b)
                    guard b == 0x0A else { continue }
                    for e in parser.feed(bytes: buf) { s.apply(e) }
                    buf.removeAll(keepingCapacity: true)
                    self.live = s
                    if s.finished { break }
                }
                self.finish(q: t, s, error: s.error ?? (s.finished ? nil : String(localized: "Die Verbindung ist abgebrochen.")))
                if !s.finished { await self.settleAsk(after: n0) }
            } catch {
                if Task.isCancelled || (error as? URLError)?.code == .cancelled { self.finish(q: t, s, error: nil, stopped: true) }
                else { self.finish(q: t, s, error: error.localizedDescription); await self.settleAsk(after: n0) }
            }
        }
    }
    private func finish(q: String, _ s: SagaStream, error: String?, stopped: Bool = false) {
        streamTask = nil
        if s.finished && s.error == nil {
            thread.turns.append(SagaTurn(role: "me", text: q, created: Date().timeIntervalSince1970))
            thread.turns.append(s.turnValue(now: Date().timeIntervalSince1970))
            live = nil; liveQ = nil
            return
        }
        // Fehler/Abbruch: die Frage bleibt sichtbar mit Hinweis (der Server hat sie ggf. nicht gespeichert)
        live = stopped ? nil : s; liveError = stopped ? nil : (error ?? "")
        if stopped { liveQ = nil }
    }
    /// Verbindung gerissen (z. B. App im Hintergrund): hat der Server die Antwort trotzdem gespeichert, zeigt der Verlauf sie -
    /// die Fehlerkarte mit derselben Frage faellt dann weg (sonst stuende die Frage doppelt)
    private func settleAsk(after n0: Int) async {
        await loadThread()
        guard liveError != nil, serverTurns(after: n0) else { return }
        if thread.turns.contains(where: { ($0.n ?? 0) > n0 && !$0.mine }) { dismissLiveError() }
        else { liveQ = "" }                                          // nur die Frage ist gespeichert: Hinweis ohne zweite Frage
    }
    func stop() { streamTask?.cancel() }
    /// Liegengebliebene Fehler-/Abbruch-Karten raeumen, bevor etwas Neues startet
    private func clearFinished() {
        if liveError != nil { dismissLiveError() }
        if liveJob?.error != nil || liveJob?.stopped == true { liveJob = nil }
    }
    func dismissLiveError() { live = nil; liveQ = nil; liveError = nil }

    func clearThread() async {
        guard let store else { return }
        do {
            let (d, code) = try await store.apiResponse("api/ai/thread", method: "DELETE")
            guard (200..<300).contains(code) else { toast = err(d, code); return }
            thread.turns = []; dismissLiveError()
        } catch { toast = error.localizedDescription }
    }

    // MARK: Auftraege (K-B, Abschnitt 11.7): im Gespraech ueber "Als Auftrag", Liste + Detail im Segment "Auftraege"

    func loadJobs() async {
        guard let store, status?.jobsOn == true else { return }
        guard let (d, code) = try? await store.apiResponse("api/ai/jobs") else { return }
        if code == 200, let l = SagaJobList(data: d) { jobs = l; jobsLoaded = true }
        else if SagaLogic.needsConsent(code: code, body: d) { await consentLost() }
    }

    /// Jetziger Stand eines Auftrags fuer seinen Zug im Gespraech (Plan-Karte, Rueckmeldung oder Zustand)
    func loadJobState(_ id: Int) async {
        guard let store, let (d, code) = try? await store.apiResponse("api/ai/jobs/\(id)") else { return }
        if code == 200, let x = SagaJobDetail(data: d) { jobState[id] = x; jobGone.remove(id) }
        else if code == 404 { jobState[id] = nil; jobGone.insert(id) }
        if openJob == id, code == 200, let x = SagaJobDetail(data: d) { detail = x }
    }

    /// "Auftrag geben" im Gespraech (POST /api/ai/jobs mit thread: true): status → plan | ask | refuse | error.
    /// Bitte und Ergebnis stehen danach als Zuege im Verlauf (Server); aeltere Server ohne thread: lokal angehaengt.
    func giveInThread(_ text: String, choice: String? = nil, alternative alt: SagaJobOutcome.Alternative? = nil, said: String? = nil,
                      askShared: SharePayload? = nil) {
        clearFinished()
        guard let store, liveJob == nil, live == nil else { return }
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        // 2275: geteilter Inhalt nur mit einem neuen Auftrag (aus der Zeile) bzw. der Antwort auf seine Rueckfrage - nie mit
        // einem Rezept; dann auch ohne eigene Worte (Systembitte des Servers)
        let sh = ShareDraftLogic.sharedFor(recipe: alt != nil, choice: choice, askShared: askShared,
                                           line: ShareDraftLogic.line(shareLine, asJob: true, allowed: store.shareJobsAllowed))
        guard alt != nil || !t.isEmpty || sh != nil else { return }
        // 2508: mit offener Seite als Kontext (nicht bei Rezepten, wie das Web)
        var b = alt.map { SagaLogic.recipeBody($0, text: t) } ?? SagaLogic.giveBody(text: t, choice: choice, note: noteContext?.id, shared: sh)
        b["thread"] = true
        let shown = said ?? (t.isEmpty && sh != nil ? String(localized: "Kümmere dich darum.") : t)
        let n0 = maxN
        let fresh = alt == nil && choice == nil
        liveJob = LiveJob(said: shown, status: store.aiThinking, mark: sh.map(ShareDraftLogic.mark))
        jobTask = Task { [weak self] in
            guard let self else { return }
            defer { self.jobTask = nil }
            do {
                let (bytes, code, raw) = try await store.openStream("api/ai/jobs", method: "POST", body: b, session: self.session)
                guard code == 200 else {
                    if SagaLogic.needsConsent(code: code, body: raw) { await self.consentLost() }
                    self.liveJob?.error = raw.flatMap(ShareJSON.obj).flatMap { ShareJSON.str($0["error"]) } ?? String(localized: "Das hat nicht geklappt.")
                    return
                }
                var parser = SSEParser(), buf = Data()
                for try await x in bytes {
                    buf.append(x)
                    guard x == 0x0A else { continue }
                    let evs = parser.feed(bytes: buf); buf.removeAll(keepingCapacity: true)
                    for e in evs {
                        if e.event == "status", let s = ShareJSON.str(e.json["text"]) { self.liveJob?.status = s; continue }
                        guard let o = SagaLogic.outcome(e) else { continue }
                        // 2275: einmal verbraucht, sobald der Server etwas daraus gemacht hat (bei Fehlern bleibt die Zeile)
                        if case .error = o {} else if fresh, sh != nil, self.shareLine == sh { self.shareLine = nil }
                        await self.landed(o, said: shown, q: t, after: n0, shared: sh)
                        return
                    }
                }
                if await self.settleJob(after: n0) { return }
                self.liveJob?.error = String(localized: "Das hat nicht geklappt.")
            } catch {
                let stopped = Task.isCancelled || (error as? URLError)?.code == .cancelled
                if await self.settleJob(after: n0) { return }
                if stopped { self.liveJob?.stopped = true } else { self.liveJob?.error = error.localizedDescription }
            }
        }
    }
    /// Verbindung weg bzw. Stopp nach dem Planen: der Server legt die Zuege trotzdem an (ai_jobs.plan_job) - dann diese
    /// zeigen statt "nichts ist passiert" (true = Zuege da, laufende Karte weg)
    private func settleJob(after n0: Int) async -> Bool {
        await loadThread()
        guard serverTurns(after: n0) else { return false }
        liveJob = nil
        await loadJobs()
        return true
    }
    /// Ergebnis des Planens in den Verlauf (Server-Zuege, sonst lokal)
    private func landed(_ o: SagaJobOutcome, said: String, q: String, after n0: Int, shared: SharePayload? = nil) async {
        let now = Date().timeIntervalSince1970
        var ai: SagaTurn?
        switch o {
        case .plan(let p):
            await loadJobState(p.job)                                // Detail nicht geladen: der Zug laedt es selbst nach
            ai = SagaTurn(role: "ai", zug: "plan", text: p.title, created: now, jobID: p.job)
            AccessibilityNotification.Announcement(String(localized: "Plan bereit – wartet auf dein OK")).post()
            await loadJobs()
        case .ask(let qq, let ch): ai = SagaTurn(role: "ai", zug: "ask", text: qq, created: now, choices: ch, q: q)
        case .refuse(let tx, let alt): ai = SagaTurn(role: "ai", zug: "refuse", text: tx, created: now, q: q, alternative: alt)
        case .error(let m, _):
            liveJob?.error = m.isEmpty ? String(localized: "Das hat nicht geklappt.") : m
            return
        }
        await loadThread()
        if !serverTurns(after: n0), let ai {                          // Server ohne Zuege fuer Auftraege (aelter als 11.7)
            thread.turns.append(SagaTurn(role: "me", zug: "job", text: said, created: now, shareMark: shared.map(ShareDraftLogic.mark)))
            thread.turns.append(ai)
        }
        // 2275: Rueckfrage zu geteiltem Inhalt merken - die Wahl schickt ihn erneut mit (14.1)
        if case .ask = o, let shared, let at = thread.turns.last(where: { $0.zug == "ask" && !$0.mine && (($0.n ?? 0) > n0 || $0.n == nil) }) {
            askShared[at.id] = shared
        }
        liveJob = nil
    }
    func stopPlanning() { jobTask?.cancel() }

    /// 2508: "{Name} soll sich kümmern …" aus dem KI-Bereich einer Seite - Titel vom Server (wie das Web: GET /api/notes/<id>,
    /// zugleich die Probe, ob die Seite lesbar ist), "Als Auftrag" an. Nicht lesbar: Hinweis, kein Kontext.
    func startForNote(_ id: Int) async {
        guard let store, id > 0 else { return }
        jobMode = true
        if noteContext?.id != id { noteContext = nil }
        guard let (d, code) = try? await store.apiResponse("api/notes/\(id)"), code == 200, let j = ShareJSON.obj(d) else {
            toast = String(localized: "Diese Seite kannst du nicht öffnen."); return
        }
        noteContext = NoteContext(id: id, title: j["title"] as? String ?? "")
    }
    func dismissLiveJob() { liveJob = nil }

    /// 2.6 K-G (2275): Entwurf aus der Teilen-Erweiterung uebernehmen (saga://share, Mitteilung, App vorne) - Gespraech,
    /// "Als Auftrag" an, Zeile "Geteilt: …"; gesendet wird erst auf Tippen. Der Entwurf ist danach geloescht. Ohne Entwurf
    /// (verfallen, schon uebernommen, nach einem Neustart weg) bzw. ohne Freigabe: kurzer Hinweis statt Stille (2600).
    /// - Returns: true, wenn etwas uebernommen wurde
    @discardableResult
    func takeShareDraft(allowed: Bool, server: String?, quietIfNone: Bool = false, now: Date = Date()) -> Bool {
        let d = AppConfig.store
        guard let x = ShareDraftLogic.take(in: d, now: now.timeIntervalSince1970, server: server) else {
            if !quietIfNone && shareLine == nil { toast = String(localized: "Nichts Geteiltes mehr da – bitte teile es noch einmal.") }
            return false
        }
        guard allowed else {
            toast = String(localized: "\(store?.sagaName ?? "Saga") kann gerade nichts Geteiltes übernehmen.")
            return false
        }
        shareLine = x.payload
        jobMode = true
        wantConversation = true
        return true
    }

    /// "Passt, los" - Ergebnis fuer die Karte (nil = ok)
    func approve(_ p: SagaPlan) async -> String? {
        guard let store else { return nil }
        do {
            let (d, code) = try await store.apiResponse("api/ai/jobs/\(p.job)/approve", method: "POST", body: SagaLogic.approveBody(p))
            let r = SagaLogic.actionResult(code: code, body: d)
            switch r {
            case .ok(let st, _):
                if let st { AccessibilityNotification.Announcement(st).post() }
                await loadJobState(p.job); await loadJobs()
                return nil
            case .stale(let m), .state(let m):                    // Plan geaendert/schon erledigt: neuen Stand zeigen
                if SagaLogic.approveConflict(r) == .stale { staleSeen.insert(p.job) } else { settled.insert(Self.planKey(p)) }
                await loadJobState(p.job); await loadJobs()
                return m
            case .consent(let m): await consentLost(); return m
            case .changed: return err(d, code)
            case .failed(let m): return m.isEmpty ? err(d, code) : m
            }
        } catch { return error.localizedDescription }
    }
    static func planKey(_ p: SagaPlan) -> String { "\(p.job)/\(p.planV)" }

    /// "Lieber nicht" / "Auftrag abbrechen"
    func cancel(_ job: Int) async -> String? {
        guard let store else { return nil }
        do {
            let (d, code) = try await store.apiResponse("api/ai/jobs/\(job)/cancel", method: "POST", body: [:])
            switch SagaLogic.actionResult(code: code, body: d) {
            case .ok(let st, let tx):
                if let a = tx ?? st { AccessibilityNotification.Announcement(a).post() }
                await loadJobState(job); await loadJobs()
                return nil
            case .consent(let m): await consentLost(); return m
            default: await loadJobState(job); return err(d, code)
            }
        } catch { return error.localizedDescription }
    }
    /// "Aendern" speichern -> neuer Plan (neuer plan_hash, "geaendert – bitte neu freigeben")
    func saveEdit(_ p: SagaPlan, values: [Int: [String: SagaField]]) async -> (SagaPlan?, String?) {
        guard let store else { return (nil, nil) }
        do {
            let (d, code) = try await store.apiResponse("api/ai/jobs/\(p.job)/plan", method: "PUT", body: SagaLogic.editBody(p, values: values))
            if (200..<300).contains(code), let np = SagaPlan(ShareJSON.obj(d)) {
                await loadJobState(p.job)
                if var x = jobState[p.job] { x.plan = np; x.planSteps = np.steps; jobState[p.job] = x }   // edited: true behalten
                if detail?.id == p.job { detail?.plan = np; detail?.planSteps = np.steps }
                await loadJobs()
                return (np, nil)
            }
            if SagaLogic.needsConsent(code: code, body: d) { await consentLost() }
            if code == 409 {
                if SagaLogic.approveConflict(SagaLogic.actionResult(code: code, body: d)) == .stale { staleSeen.insert(p.job) }
                await loadJobState(p.job)
            }
            return (nil, err(d, code))
        } catch { return (nil, error.localizedDescription) }
    }

    func open(_ job: Int) async {
        guard let store else { return }
        openJob = job
        guard let (d, code) = try? await store.apiResponse("api/ai/jobs/\(job)") else { return }
        if code == 200, let x = SagaJobDetail(data: d) { if openJob == job { detail = x }; if jobState[job] != nil { jobState[job] = x } }
        else if code == 404 { if openJob == job { openJob = nil; detail = nil }; await loadJobs() }
    }
    func close() { openJob = nil; detail = nil }

    enum UndoResult: Equatable { case ok, changed(String?), failed(String) }
    func undo(job: Int, change: Int) async -> UndoResult {
        guard let store else { return .failed("") }
        do {
            let (d, code) = try await store.apiResponse("api/ai/jobs/\(job)/undo", method: "POST", body: ["change": change])
            switch SagaLogic.actionResult(code: code, body: d) {
            case .ok(_, let tx):                                     // auch 200 {already, text}: schon weg (z. B. Termin geloescht)
                toast = tx ?? String(localized: "Zurückgenommen")
                await refreshJob(job)
                return .ok
            case .changed(let u): undoConflicts[change] = u ?? ""; await refreshJob(job); return .changed(u)
            default: return .failed(err(d, code))
            }
        } catch { return .failed(error.localizedDescription) }
    }
    private func refreshJob(_ job: Int) async {
        if openJob == job { await open(job) }
        if jobState[job] != nil { await loadJobState(job) }
        await loadJobs()
    }
    /// "Alte Fassung ansehen" -> (Titel, Klartext)
    func before(_ path: String) async -> (String, String)? {
        guard let store, let (d, code) = try? await store.apiResponse(path), code == 200, let j = ShareJSON.obj(d) else { return nil }
        return (j["title"] as? String ?? "", j["text"] as? String ?? "")
    }

    /// Live: ai.job.changed {id, state} - Liste, Detail und der Zug im Gespraech auf den neuen Stand
    /// (nicht, waehrend "Aendern" an dieser Karte offen ist)
    func live(_ e: LiveEvent) {
        guard e.type == "ai.job.changed", let c = SagaLogic.jobChanged(e.data) else { return }
        Task {
            await loadJobs()
            if openJob == c.id, !editing.contains(c.id) { await open(c.id) }
            if let old = jobState[c.id], !editing.contains(c.id), old.state != c.state || c.state == "erledigt" { await loadJobState(c.id) }
        }
    }

    /// 2515: beim (Wieder-)Verbinden (Live "hello") und beim Zurueckkehren in den Vordergrund den Stand der gezeigten
    /// Auftraege einmal neu holen - ai.job.changed kann beim Wiederverbinden verloren gehen (sonst bliebe "Läuft" stehen)
    func resync() async {
        guard status?.consentOK == true || !jobState.isEmpty || openJob != nil else { return }
        guard SagaLogic.resyncNow(last: lastResync, now: Date()) else { return }
        lastResync = Date()
        if jobsLoaded { await loadJobs() }
        for (id, d) in jobState where SagaLogic.resyncDue(d.state) && !editing.contains(id) { await loadJobState(id) }
        if let id = openJob, !editing.contains(id), jobState[id] == nil, SagaLogic.resyncDue(detail?.state) { await open(id) }
    }

    // MARK: Melden (Abschnitt 6)

    func report(where w: String, job: Int?, reason: ChatStd.ReportReason, text: String?, key: String? = nil) async -> String? {
        guard let store else { return nil }
        do {
            let (d, code) = try await store.apiResponse("api/ai/report", method: "POST", body: SagaLogic.reportBody(where: w, job: job, reason: reason.rawValue, text: text))
            guard (200..<300).contains(code) else { return err(d, code) }
            toast = String(localized: "Danke – die Meldung ist bei den Admins.")
            if let key { reported.insert(key) }
            return nil
        } catch { return error.localizedDescription }
    }

    // MARK: Einstellungen › Saga, Meine Daten (M7)

    func loadPrefs() async -> SagaPrefs? {
        guard let store, let (d, code) = try? await store.apiResponse("api/ai/prefs"), code == 200 else { return nil }
        return SagaPrefs(data: d)
    }
    /// PUT /api/ai/prefs (nur genannte Felder) -> neue Prefs oder Fehlertext
    func putPrefs(_ body: [String: Any]) async -> (SagaPrefs?, String?) {
        guard let store else { return (nil, nil) }
        do {
            let (d, code) = try await store.apiResponse("api/ai/prefs", method: "PUT", body: body)
            guard (200..<300).contains(code) else { return (nil, err(d, code)) }
            if let p = SagaPrefs(data: d), p.enabled, p.consent != nil { return (p, nil) }
            return (await loadPrefs(), nil)
        } catch { return (nil, error.localizedDescription) }
    }
    /// Widerruf: laufende Auftraege halten an -> Hinweis
    func withdraw() async -> String {
        guard let store else { return "" }
        do {
            let (d, code) = try await store.apiResponse("api/ai/consent", method: "DELETE")
            guard (200..<300).contains(code) else { return err(d, code) }
            let n = ShareJSON.obj(d).flatMap { ShareJSON.int($0["paused"]) } ?? 0
            await store.loadAIStatus()
            close()
            return n > 0 ? String(localized: "Widerrufen – \(n) Aufträge angehalten.") : String(localized: "Widerrufen.")
        } catch { return error.localizedDescription }
    }
    func deleteAll() async -> String {
        guard let store else { return "" }
        do {
            let (d, code) = try await store.apiResponse("api/ai/mydata/delete", method: "POST", body: SagaLogic.deleteBody)
            guard (200..<300).contains(code) else { return err(d, code) }
            thread.turns = []; jobs = SagaJobList(); jobState = [:]; close(); dismissLiveError(); liveJob = nil
            await loadJobs()
            return String(localized: "Gelöscht.")
        } catch { return error.localizedDescription }
    }
    /// Export (JSON bzw. Markdown) - gespeichert/geteilt wird ueber saveDataFile (Teilen-Blatt)
    func export(_ format: String) async throws -> Data {
        guard let store else { throw APIError.other("") }
        return try await store.api("api/ai/mydata?format=\(format == "md" ? "md" : "json")", timeout: 60)
    }

    #if DEBUG
    /// Nur fuer Tests (Startparameter): Einwilligung, Frage, Auftrag, Aendern, Freigabe und Rueckgaengig ohne Antippen
    /// -hkSagaConsent ideas|no_ideas · -hkSagaAsk <frage> · -hkSagaGive <bitte> · -hkSagaEdit <text> [-hkSagaStale YES]
    /// · -hkSagaApprove YES · -hkSagaUndo YES · -hkSagaPause s · -hkSaga2515 YES [-hkSaga2515Resync YES] (anderswo aendern/freigeben)
    func debugRun() async {
        let d = UserDefaults.standard
        func log(_ s: String) {                                        // auch als Datei (Tests lesen sie aus dem App-Container)
            print("SAGA_DBG " + s); fflush(stdout)
            let u = FileManager.default.temporaryDirectory.appendingPathComponent("saga-dbg.log")
            let line = Data(("SAGA_DBG " + s + "\n").utf8)
            if let h = try? FileHandle(forWritingTo: u) { h.seekToEndOfFile(); h.write(line); try? h.close() } else { try? line.write(to: u) }
        }
        func wait(_ what: String, _ ok: () -> Bool) async -> Bool {
            for _ in 0..<160 { if ok() { return true }; try? await Task.sleep(for: .milliseconds(250)) }
            log("timeout " + what); return false
        }
        let pause = Double(d.integer(forKey: "hkSagaPause"))
        guard await wait("status", { status != nil }) else { return }
        if let c = d.string(forKey: "hkSagaConsent"), let a = SagaLogic.ConsentAnswer(rawValue: c), status?.consentOK == false {
            try? await Task.sleep(for: .seconds(2))
            await consent(a); log("consent \(status?.consent?.state.rawValue ?? "-") \(cardError ?? "")")
        }
        if let q = d.string(forKey: "hkSagaAsk"), await wait("thread", { threadLoaded }) {
            send(q)
            if await wait("answer", { live == nil || liveError != nil }) { log("ask turns=\(thread.turns.count) err=\(liveError ?? "-")") }
        }
        guard let g = d.string(forKey: "hkSagaGive"), await wait("thread", { threadLoaded }) else { log("fertig"); return }
        try? await Task.sleep(for: .seconds(1))
        jobMode = true
        let n0 = thread.turns.count
        giveInThread(g)
        guard await wait("plan", { liveJob == nil || liveJob?.error != nil }) else { return }
        if let e = liveJob?.error { log("give error \(e)"); return }
        guard let jt = thread.turns.dropFirst(n0).last(where: { $0.jobID != nil }), let job = jt.jobID, let p = jobState[job]?.plan else {
            log("give ohne Plan: \(thread.turns.dropFirst(n0).map { $0.zug ?? "-" })"); return
        }
        log("plan job=\(job) v=\(p.planV) steps=\(p.steps.count) turns=\(thread.turns.count - n0)")
        if d.bool(forKey: "hkSaga2515"), let store, let st = p.steps.first(where: { $0.edit.contains("text") }) {
            // 2515 (Gegenprobe): "anderswo" = direkt am Server, am Modell vorbei - wie ein zweites Geraet
            try? await Task.sleep(for: .seconds(pause))
            let c1 = (try? await store.apiResponse("api/ai/jobs/\(job)/plan", method: "PUT", body: SagaLogic.editBody(p, values: [st.n: ["text": .text("Anderswo geändert")]])))?.1 ?? 0
            let e1 = await approve(p)                                  // alte Fassung -> 409 stale
            let np = jobState[job]?.plan
            log("2515 stale put=\(c1) err=\(e1 ?? "ok") v=\(np?.planV ?? -1) edited=\(np?.edited.map { String($0) } ?? "nil") hint=\(np?.changed(afterStale: staleSeen.contains(job)) ?? false)")
            guard let np else { log("fertig"); return }
            if d.bool(forKey: "hkSaga2515Resync") {                     // Live-Ereignis geht verloren: Kanal zu, anderswo freigeben
                store.live.stop()
                let c3 = (try? await store.apiResponse("api/ai/jobs/\(job)/approve", method: "POST", body: SagaLogic.approveBody(np)))?.1 ?? 0
                try? await Task.sleep(for: .seconds(3))
                log("2515 resync approve=\(c3) vorher=\(jobState[job]?.state ?? "-")")
                store.live.start()                                     // hello -> resync
                _ = await wait("resync", { jobState[job]?.state != "wartet_ok" })
                log("2515 resync nachher=\(jobState[job]?.state ?? "-") card=\(jobState[job]?.awaitingOK ?? false)")
            } else {
                store.live.stop()                                      // ai.job.changed kaeme sonst vor dem Tippen
                let c2 = (try? await store.apiResponse("api/ai/jobs/\(job)/approve", method: "POST", body: SagaLogic.approveBody(np)))?.1 ?? 0
                let e2 = await approve(np)                             // hier "Passt, los" -> 409 state
                log("2515 state approve=\(c2) err=\(e2 ?? "ok") settled=\(settled.contains(Self.planKey(np))) state=\(jobState[job]?.state ?? "-") card=\(jobState[job]?.awaitingOK ?? false)")
                store.live.start()
            }
            log("fertig"); return
        }
        var cur = p
        if let e = d.string(forKey: "hkSagaEdit"), let st = p.steps.first(where: { $0.edit.contains("text") }) {
            try? await Task.sleep(for: .seconds(pause))
            let (np, err) = await saveEdit(p, values: [st.n: ["text": .text(e)]])
            log("edit v=\(np?.planV ?? -1) edited=\(np?.edited ?? false) err=\(err ?? "-")")
            if let np { cur = np }
            if d.bool(forKey: "hkSagaStale") {                     // alte Fassung freigeben -> 409 stale, Karte zeigt den neuen Plan
                let e2 = await approve(p)
                log("stale \(e2 ?? "ok") now v=\(jobState[job]?.plan?.planV ?? -1)")
            }
        }
        guard d.bool(forKey: "hkSagaApprove") else { log("fertig"); return }
        try? await Task.sleep(for: .seconds(pause))
        let e = await approve(cur); log("approve \(e ?? "ok")")
        guard await wait("feedback", { jobState[job]?.feedback != nil }), let x = jobState[job] else { return }
        log("feedback \(x.stateText) lines=\(x.feedback?.lines.count ?? 0)")
        if d.bool(forKey: "hkSagaUndo"), let ln = x.feedback?.lines.first(where: \.canUndo) {
            try? await Task.sleep(for: .seconds(pause))
            let r = await undo(job: job, change: ln.change); log("undo \(r) -> \(jobState[job]?.stateText ?? "-")")
        }
        log("fertig")
    }
    #endif
}

/// 2.6 K-G (2275/2508): Ort "Saga" im Gespraech oeffnen - aus Siri (auch nach einem Start im Hintergrund, dann holt
/// RootView es beim Erscheinen nach) bzw. aus dem KI-Bereich einer Seite ("{Name} soll sich kümmern …", mit Seite)
@MainActor
enum SagaOpen {
    /// share = Entwurf der Teilen-Erweiterung uebernehmen (2275)
    enum Target: Equatable { case conversation, note(Int), share }
    static var pending: Target?
    static func request(_ t: Target) {
        pending = t
        NotificationCenter.default.post(name: .hkSagaOpen, object: nil)
    }
}
extension Notification.Name { static let hkSagaOpen = Notification.Name("hk.sagaOpen") }
