import SwiftUI

// Ort "Saga" (2.6, Bugs-DB 2269 K-I + 2267 K-B): Zustand und Serverwege fuer Tab, Kennenlernen, Gespraech, Auftraege und
// Einstellungen › Saga. Reine Logik: SagaLogic.swift. Der Server prueft alles (Einwilligung, Rechte, Plan-Vertrag) -
// die App zeigt nur, was er meldet, und schickt Freigaben nur mit genau dem gezeigten plan_v + plan_hash.

@MainActor
final class SagaModel: ObservableObject {
    weak var store: Store?

    /// Bühne im Bereich "Aufträge": was nach "Auftrag geben" gerade zu sehen ist (eine Karte)
    enum Stage: Equatable {
        case thinking(String)
        case plan(SagaPlan)
        case ask(question: String, choices: [String], text: String)
        case refuse(text: String, alternative: SagaJobOutcome.Alternative?, asked: String)
        case done(job: Int, stateText: String, text: String)        // nach Freigabe/Abbruch geschrumpft
        case feedback(SagaJobDetail)                                // Rueckmeldung an Ort und Stelle
        case error(String)
    }

    // Gespraech
    @Published var thread = SagaThread()
    @Published var threadLoaded = false
    @Published var live: SagaStream?                                // laufende Antwort
    @Published var liveQ: String?
    @Published var liveError: String?
    // Auftraege
    @Published var jobs = SagaJobList()
    @Published var jobsLoaded = false
    @Published var stage: Stage?
    @Published var detail: SagaJobDetail?
    @Published var openJob: Int?
    @Published var planning = false
    // Hinweise
    @Published var toast: String?
    @Published var cardError: String?
    @Published var busy = false

    private var streamTask: Task<Void, Never>?
    private var jobTask: Task<Void, Never>?
    private var watch: Set<Int> = []
    private lazy var session: URLSession = {
        let c = URLSessionConfiguration.default
        c.timeoutIntervalForRequest = 90; c.timeoutIntervalForResource = 600
        return URLSession(configuration: c)
    }()

    var status: SagaStatus? { store?.sagaStatus }

    func reset() {
        streamTask?.cancel(); jobTask?.cancel()
        thread = SagaThread(); threadLoaded = false; live = nil; liveQ = nil; liveError = nil
        jobs = SagaJobList(); jobsLoaded = false; stage = nil; detail = nil; openJob = nil; planning = false
        toast = nil; cardError = nil; busy = false; watch = []
    }

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
        guard let store, status?.consentOK == true else { return }
        guard let (d, code) = try? await store.apiResponse("api/ai/thread") else { return }
        if code == 200, let t = SagaThread(data: d) { thread = t; threadLoaded = true }
        else if SagaLogic.needsConsent(code: code, body: d) { await consentLost() }
    }

    func send(_ q: String) {
        let t = q.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let store, live == nil, AILogic.validQuestion(t, max: status?.qMax ?? 1000) else { return }
        liveQ = t; liveError = nil; live = SagaStream()
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
            } catch {
                if Task.isCancelled || (error as? URLError)?.code == .cancelled { self.finish(q: t, s, error: nil, stopped: true) }
                else { self.finish(q: t, s, error: error.localizedDescription) }
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
    func stop() { streamTask?.cancel() }
    func dismissLiveError() { live = nil; liveQ = nil; liveError = nil }

    func clearThread() async {
        guard let store else { return }
        do {
            let (d, code) = try await store.apiResponse("api/ai/thread", method: "DELETE")
            guard (200..<300).contains(code) else { toast = err(d, code); return }
            thread.turns = []; dismissLiveError()
        } catch { toast = error.localizedDescription }
    }

    // MARK: Auftraege (K-B)

    func loadJobs() async {
        guard let store, status?.jobsOn == true else { return }
        guard let (d, code) = try? await store.apiResponse("api/ai/jobs") else { return }
        if code == 200, let l = SagaJobList(data: d) { jobs = l; jobsLoaded = true }
        else if SagaLogic.needsConsent(code: code, body: d) { await consentLost() }
    }

    /// "Auftrag geben" (SSE): status → plan | ask | refuse | error
    func give(_ text: String, body: [String: Any]? = nil) {
        guard let store, !planning else { return }
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard body != nil || !t.isEmpty else { return }
        planning = true; openJob = nil; detail = nil
        stage = .thinking(store.aiThinking)
        let b = body ?? SagaLogic.giveBody(text: t)
        jobTask = Task { [weak self] in
            guard let self else { return }
            defer { self.planning = false; self.jobTask = nil }
            do {
                let (bytes, code, raw) = try await store.openStream("api/ai/jobs", method: "POST", body: b, session: self.session)
                guard code == 200 else {
                    if SagaLogic.needsConsent(code: code, body: raw) { await self.consentLost() }
                    self.stage = .error(raw.flatMap(ShareJSON.obj).flatMap { ShareJSON.str($0["error"]) } ?? String(localized: "Das hat nicht geklappt."))
                    return
                }
                var parser = SSEParser(), buf = Data()
                for try await x in bytes {
                    buf.append(x)
                    guard x == 0x0A else { continue }
                    let evs = parser.feed(bytes: buf); buf.removeAll(keepingCapacity: true)
                    for e in evs {
                        if e.event == "status", let s = ShareJSON.str(e.json["text"]) { self.stage = .thinking(s); continue }
                        guard let o = SagaLogic.outcome(e) else { continue }
                        switch o {
                        case .plan(let p): self.stage = .plan(p); await self.loadJobs()
                        case .ask(let q, let ch): self.stage = .ask(question: q, choices: ch, text: t)
                        case .refuse(let tx, let alt): self.stage = .refuse(text: tx, alternative: alt, asked: t)
                        case .error(let m, _): self.stage = .error(m.isEmpty ? String(localized: "Das hat nicht geklappt.") : m)
                        }
                        return
                    }
                }
                self.stage = .error(String(localized: "Das hat nicht geklappt."))
            } catch {
                if Task.isCancelled || (error as? URLError)?.code == .cancelled { self.stage = nil }
                else { self.stage = .error(error.localizedDescription) }
            }
        }
    }
    func stopPlanning() { jobTask?.cancel() }

    /// Rueckfrage beantwortet: dieselbe Bitte mit der Wahl
    func choose(_ choice: String, text: String) { planning = false; give(text, body: SagaLogic.giveBody(text: text, choice: choice)) }
    /// "Als Aufgabe anlegen" (Rezept direkt, ohne Modell)
    func alternative(_ a: SagaJobOutcome.Alternative, text: String) { give(text, body: SagaLogic.recipeBody(a, text: text)) }

    /// "Passt, los" - Ergebnis fuer die Karte (nil = ok)
    func approve(_ p: SagaPlan) async -> String? {
        guard let store else { return nil }
        do {
            let (d, code) = try await store.apiResponse("api/ai/jobs/\(p.job)/approve", method: "POST", body: SagaLogic.approveBody(p))
            switch SagaLogic.actionResult(code: code, body: d) {
            case .ok(let st, let tx):
                watch.insert(p.job)
                stage = .done(job: p.job, stateText: st ?? "", text: tx ?? "")
                await loadJobs(); if openJob == p.job { await open(p.job) }
                return nil
            case .stale(let m), .state(let m):                    // Plan geaendert/schon erledigt: neuen Stand zeigen
                await reloadPlan(p.job)
                return m
            case .consent(let m): await consentLost(); return m
            case .changed: return err(d, code)
            case .failed(let m): return m.isEmpty ? err(d, code) : m
            }
        } catch { return error.localizedDescription }
    }
    /// "Lieber nicht" / "Auftrag abbrechen"
    func cancel(_ job: Int) async -> String? {
        guard let store else { return nil }
        do {
            let (d, code) = try await store.apiResponse("api/ai/jobs/\(job)/cancel", method: "POST", body: [:])
            switch SagaLogic.actionResult(code: code, body: d) {
            case .ok(let st, let tx):
                if case .plan(let p)? = stage, p.job == job { stage = .done(job: job, stateText: st ?? "", text: tx ?? "") }
                await loadJobs(); if openJob == job { await open(job) }
                return nil
            case .consent(let m): await consentLost(); return m
            default: return err(d, code)
            }
        } catch { return error.localizedDescription }
    }
    /// "Aendern" speichern -> neuer Plan (neuer plan_hash, "geaendert – bitte neu freigeben")
    func saveEdit(_ p: SagaPlan, values: [Int: [String: SagaField]]) async -> (SagaPlan?, String?) {
        guard let store else { return (nil, nil) }
        do {
            let (d, code) = try await store.apiResponse("api/ai/jobs/\(p.job)/plan", method: "PUT", body: SagaLogic.editBody(p, values: values))
            if (200..<300).contains(code), let np = SagaPlan(ShareJSON.obj(d)) {
                replacePlan(np); await loadJobs()
                return (np, nil)
            }
            if SagaLogic.needsConsent(code: code, body: d) { await consentLost() }
            if code == 409 { await reloadPlan(p.job) }
            return (nil, err(d, code))
        } catch { return (nil, error.localizedDescription) }
    }
    private func replacePlan(_ np: SagaPlan) {
        if case .plan(let old)? = stage, old.job == np.job { stage = .plan(np) }
        if detail?.id == np.job { detail?.plan = np; detail?.planSteps = np.steps }
    }
    private func reloadPlan(_ job: Int) async {
        guard let store, let (d, code) = try? await store.apiResponse("api/ai/jobs/\(job)"), code == 200, let x = SagaJobDetail(data: d) else { return }
        if case .plan(let old)? = stage, old.job == job {
            if x.awaitingOK, let p = x.plan { stage = .plan(p) } else { stage = .done(job: job, stateText: x.stateText, text: x.reasonText ?? "") }
        }
        if openJob == job { detail = x }
        await loadJobs()
    }

    func open(_ job: Int) async {
        guard let store else { return }
        openJob = job
        guard let (d, code) = try? await store.apiResponse("api/ai/jobs/\(job)") else { return }
        if code == 200, let x = SagaJobDetail(data: d) { if openJob == job { detail = x } }
        else if code == 404 { if openJob == job { openJob = nil; detail = nil }; await loadJobs() }
    }
    func close() { openJob = nil; detail = nil }

    enum UndoResult: Equatable { case ok, changed(String?), failed(String) }
    func undo(job: Int, change: Int) async -> UndoResult {
        guard let store else { return .failed("") }
        do {
            let (d, code) = try await store.apiResponse("api/ai/jobs/\(job)/undo", method: "POST", body: ["change": change])
            switch SagaLogic.actionResult(code: code, body: d) {
            case .ok:
                toast = String(localized: "Zurückgenommen")
                await refreshJob(job)
                return .ok
            case .changed(let u): await refreshJob(job); return .changed(u)
            default: return .failed(err(d, code))
            }
        } catch { return .failed(error.localizedDescription) }
    }
    private func refreshJob(_ job: Int) async {
        if openJob == job { await open(job) }
        if case .feedback(let x)? = stage, x.id == job, let store,
           let (d, code) = try? await store.apiResponse("api/ai/jobs/\(job)"), code == 200, let n = SagaJobDetail(data: d) { stage = .feedback(n) }
        await loadJobs()
    }
    /// "Alte Fassung ansehen" -> (Titel, Klartext)
    func before(_ path: String) async -> (String, String)? {
        guard let store, let (d, code) = try? await store.apiResponse(path), code == 200, let j = ShareJSON.obj(d) else { return nil }
        return (j["title"] as? String ?? "", j["text"] as? String ?? "")
    }

    /// Live: ai.job.changed {id, state} - Liste/Detail neu, Rueckmeldung an Ort und Stelle fuer eben freigegebene Auftraege
    func live(_ e: LiveEvent) {
        guard e.type == "ai.job.changed", let (id, st) = SagaLogic.jobChanged(e.data) else { return }
        Task {
            await loadJobs()
            if openJob == id { await open(id) }
            if SagaLogic.reportDue(watched: watch.contains(id), state: st) {
                watch.remove(id)
                if let store, let (d, code) = try? await store.apiResponse("api/ai/jobs/\(id)"), code == 200, let x = SagaJobDetail(data: d) {
                    if case .done(let j, _, _)? = stage, j == id { stage = .feedback(x) }
                    else if stage == nil { stage = .feedback(x) }
                }
            }
        }
    }

    // MARK: Melden (Abschnitt 6)

    func report(where w: String, job: Int?, reason: ChatStd.ReportReason, text: String?) async -> String? {
        guard let store else { return nil }
        do {
            let (d, code) = try await store.apiResponse("api/ai/report", method: "POST", body: SagaLogic.reportBody(where: w, job: job, reason: reason.rawValue, text: text))
            guard (200..<300).contains(code) else { return err(d, code) }
            toast = String(localized: "Danke – die Meldung ist bei den Admins.")
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
            stage = nil; close()
            return n > 0 ? String(localized: "Widerrufen – \(n) Aufträge angehalten.") : String(localized: "Widerrufen.")
        } catch { return error.localizedDescription }
    }
    func deleteAll() async -> String {
        guard let store else { return "" }
        do {
            let (d, code) = try await store.apiResponse("api/ai/mydata/delete", method: "POST", body: SagaLogic.deleteBody)
            guard (200..<300).contains(code) else { return err(d, code) }
            thread.turns = []; jobs = SagaJobList(); stage = nil; close(); dismissLiveError()
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
    /// Nur fuer Tests (Startparameter): Einwilligung, Frage, Auftrag, Freigabe und Rueckgaengig ohne Antippen
    /// -hkSagaConsent ideas|no_ideas · -hkSagaAsk <frage> · -hkSagaGive <bitte> · -hkSagaApprove YES · -hkSagaUndo YES
    func debugRun() async {
        let d = UserDefaults.standard
        func log(_ s: String) { print("SAGA_DBG " + s); fflush(stdout) }
        func wait(_ what: String, _ ok: () -> Bool) async -> Bool {
            for _ in 0..<120 { if ok() { return true }; try? await Task.sleep(for: .milliseconds(250)) }
            log("timeout " + what); return false
        }
        guard await wait("status", { status != nil }) else { return }
        if let c = d.string(forKey: "hkSagaConsent"), let a = SagaLogic.ConsentAnswer(rawValue: c), status?.consentOK == false {
            try? await Task.sleep(for: .seconds(2))
            await consent(a); log("consent \(status?.consent?.state.rawValue ?? "-") \(cardError ?? "")")
        }
        if let q = d.string(forKey: "hkSagaAsk"), await wait("thread", { threadLoaded }) {
            send(q)
            if await wait("answer", { live == nil || liveError != nil }) { log("ask turns=\(thread.turns.count) err=\(liveError ?? "-")") }
        }
        if let g = d.string(forKey: "hkSagaGive") {
            try? await Task.sleep(for: .seconds(1))
            give(g)
            guard await wait("plan", { if case .plan = stage { return true }; if case .error = stage { return true }; return false }) else { return }
            if case .error(let e)? = stage { log("give error \(e)"); return }
            if case .plan(let p)? = stage { log("plan job=\(p.job) v=\(p.planV) steps=\(p.steps.count)") }
        }
        if d.bool(forKey: "hkSagaApprove"), case .plan(let p)? = stage {
            try? await Task.sleep(for: .seconds(Double(d.integer(forKey: "hkSagaPause"))))
            let e = await approve(p); log("approve \(e ?? "ok")")
            if await wait("feedback", { if case .feedback = stage { return true }; return false }), case .feedback(let x)? = stage {
                log("feedback \(x.stateText) lines=\(x.feedback?.lines.count ?? 0)")
            }
        }
        if d.bool(forKey: "hkSagaUndo"), case .feedback(let x)? = stage, let ln = x.feedback?.lines.first(where: \.canUndo) {
            try? await Task.sleep(for: .seconds(Double(d.integer(forKey: "hkSagaPause"))))
            let r = await undo(job: x.id, change: ln.change); log("undo \(r)")
            if case .feedback(let y)? = stage { log("after undo \(y.stateText)") }
        }
        log("fertig")
    }
    #endif
}
