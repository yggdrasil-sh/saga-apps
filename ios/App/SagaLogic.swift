import Foundation

// Ort "Saga" (2.6, Bugs-DB 2269 K-I + 2267 K-B, Konzept K1/K2/K10/K11/K19, M1/M3/M4/M7): reine Logik ohne UI (mit
// swiftc pruefbar, Tests/Logic/SagaTests.swift). Server: docs/APP-API-KI.md Abschnitt 9-11 - /api/ai/status (style,
// jobs, level, endpoint, consent, intro, notice), /api/ai/consent, /api/ai/thread (SSE), /api/ai/jobs (SSE, Liste,
// Detail, plan, approve, cancel, undo), /api/ai/prefs, /api/ai/mydata. Alle Texte zum Anbieter/Host, Zustandswoerter,
// Klassen und Saetze kommen fertig vom Server - die App baut nie selbst "im Heimnetz", "lokal" oder Persona-Saetze.

/// Einwilligung (K19): none · later · granted · withdrawn · stale (erteilt, aber fuer einen anderen Host)
struct SagaConsent: Equatable {
    enum State: String { case none, later, granted, withdrawn, stale }
    var state: State
    var at: Double?
    var ideas: String?
    var host: String?
    init(state: State, at: Double? = nil, ideas: String? = nil, host: String? = nil) { self.state = state; self.at = at; self.ideas = ideas; self.host = host }
    init?(_ v: Any?) {
        guard let d = v as? [String: Any], let s = d["state"] as? String else { return nil }
        self.init(state: State(rawValue: s) ?? .none, at: ShareJSON.num(d["at"]), ideas: ShareJSON.str(d["ideas"]), host: ShareJSON.str(d["host"]))
    }
    var granted: Bool { state == .granted }
}

/// Text der Kennenlernen-Karte (vom Server gebildet, Sprache der Anfrage)
struct SagaIntro: Equatable {
    var version: String
    var host: String
    var lines: [String]
    var note: String?
    var question: String?
    var ideasAllowed: Bool
    var details: [String]
    var bar: String
    init?(_ v: Any?) {
        guard let d = v as? [String: Any], let ver = ShareJSON.str(d["version"]) else { return nil }
        version = ver
        host = d["host"] as? String ?? ""
        lines = (d["lines"] as? [Any] ?? []).compactMap { $0 as? String }
        note = ShareJSON.str(d["note"])
        question = ShareJSON.str(d["question"])
        ideasAllowed = ShareJSON.bool(d["ideas_allowed"])
        details = (d["details"] as? [Any] ?? []).compactMap { $0 as? String }
        bar = d["bar"] as? String ?? ""
    }
}

/// Was der Ort "Saga" aus GET /api/ai/status braucht (aeltere Felder liest AIStatus)
struct SagaStatus: Equatable {
    var enabled = false
    /// style.label: "Marin · KI" bzw. "Saga KI" (Namensregel M1, bildet der Server)
    var label: String?
    var jobs = false
    var level: String?
    var endpointLabel: String?
    var consent: SagaConsent?
    var intro: SagaIntro?
    var notice = ""
    var caps: [String] = []
    var qMax = 1000
    init() {}
    init?(data: Data) {
        guard let j = ShareJSON.obj(data) else { return nil }
        enabled = ShareJSON.bool(j["enabled"])
        label = (j["style"] as? [String: Any]).flatMap { ShareJSON.str($0["label"]) }
        jobs = ShareJSON.bool(j["jobs"])
        level = ShareJSON.str(j["level"])
        endpointLabel = (j["endpoint"] as? [String: Any]).flatMap { ShareJSON.str($0["label"]) }
        consent = SagaConsent(j["consent"])
        intro = SagaIntro(j["intro"])
        notice = j["notice"] as? String ?? ""
        caps = (j["caps"] as? [Any] ?? []).compactMap { $0 as? String }
        qMax = ShareJSON.int(j["q_max"]) ?? 1000
    }
    /// Tab "Saga" nur mit KI fuer diese Person UND einem Server, der Einwilligung + Kennenlernen-Text kennt (K-I)
    var hasPlace: Bool { enabled && consent != nil && intro != nil }
    var consentOK: Bool { consent?.granted == true }
    /// Auftraege (K-B): Faehigkeit vom Server und Einwilligung
    var jobsOn: Bool { hasPlace && jobs && level != "answers" }
}

/// Ein Zug im Gespraech (GET /api/ai/thread bzw. nach "done"). Auftraege (11.7): Bitte {me, zug job}, dann {ai, zug plan
/// + job_id} · {ai, zug ask, fields {choices, q}} · {ai, zug refuse, fields {alternative, q}}
struct SagaTurn: Identifiable, Equatable {
    var n: Int?
    var role: String                 // me | ai
    var zug: String?                 // answer | no_match | job | plan | ask | refuse
    var text: String
    var sources: [AISource]
    var created: Double?
    var jobID: Int?
    var choices: [String] = []
    var q: String?
    var alternative: SagaJobOutcome.Alternative?
    var local = UUID()
    var id: String { n.map { "t\($0)" } ?? local.uuidString }
    var mine: Bool { role == "me" }
    init(n: Int? = nil, role: String, zug: String? = nil, text: String, sources: [AISource] = [], created: Double? = nil,
         jobID: Int? = nil, choices: [String] = [], q: String? = nil, alternative: SagaJobOutcome.Alternative? = nil) {
        self.n = n; self.role = role; self.zug = zug; self.text = text; self.sources = sources; self.created = created
        self.jobID = jobID; self.choices = choices; self.q = q; self.alternative = alternative
    }
    init?(_ v: Any?) {
        guard let d = v as? [String: Any], let r = d["role"] as? String, r == "me" || r == "ai" else { return nil }
        let f = d["fields"] as? [String: Any] ?? [:]
        let src = (f["sources"] as? [Any] ?? []).compactMap(AISource.init)
        self.init(n: ShareJSON.int(d["n"]), role: r, zug: ShareJSON.str(d["zug"]), text: d["text"] as? String ?? "", sources: src,
                  created: ShareJSON.num(d["created"]), jobID: ShareJSON.int(d["job_id"]),
                  choices: (f["choices"] as? [Any] ?? []).compactMap { $0 as? String }, q: ShareJSON.str(f["q"]),
                  alternative: SagaLogic.alternative(f["alternative"]))
    }
    static func ==(a: Self, b: Self) -> Bool { a.id == b.id && a.text == b.text && a.zug == b.zug && a.jobID == b.jobID }
    /// Darstellung des Zugs
    enum Kind: Equatable { case mine, mineJob, answer, plan(Int), ask, refuse }
    func kind(jobsOn: Bool) -> Kind {
        if mine { return zug == "job" ? .mineJob : .mine }
        guard jobsOn else { return .answer }
        switch zug {
        case "plan": return jobID.map { .plan($0) } ?? .answer
        case "ask": return .ask
        case "refuse": return .refuse
        default: return .answer
        }
    }
    /// "Melden …" nur unter echten Antworten (nicht unter "nichts gefunden" ohne Modell, nicht an Auftrags-Zuegen)
    var reportable: Bool { !mine && (zug == nil || zug == "answer") && !text.isEmpty }
}

struct SagaThread: Equatable {
    var turns: [SagaTurn] = []
    var history = false
    var keep: Int?
    init() {}
    init?(data: Data) {
        guard let j = ShareJSON.obj(data) else { return nil }
        turns = (j["turns"] as? [Any] ?? []).compactMap(SagaTurn.init)
        history = ShareJSON.bool(j["history"]); keep = ShareJSON.int(j["keep"])
    }
}

/// Strom von POST /api/ai/thread (sources, status, reset, delta*, done | error) - ein Ereignis nach dem anderen
struct SagaStream: Equatable {
    var text = ""
    var sources: [AISource] = []
    var status: String?
    var finished = false
    var error: String?
    var turn: Int?
    var noMatch = false
    var quotaLeft: Int?
    /// Ereignis anwenden; true = Strom zu Ende (done oder error)
    @discardableResult
    mutating func apply(_ e: SSEEvent) -> Bool {
        guard !finished else { return true }
        let d = e.json
        switch e.event {
        case "sources": sources = (d["sources"] as? [Any] ?? []).compactMap(AISource.init)
        case "status": status = ShareJSON.str(d["text"]) ?? status
        case "reset": text = ""
        case "delta": text += d["t"] as? String ?? ""
        case "done":
            if let t = d["text"] as? String, !t.isEmpty { text = t }           // Server hat Marker entfernt: diese Fassung gilt
            turn = ShareJSON.int(d["turn"]); noMatch = ShareJSON.bool(d["no_match"]); quotaLeft = ShareJSON.int(d["quota_left"])
            finished = true
        case "error":
            error = ShareJSON.str(d["error"]) ?? ""
            finished = true
        default: break
        }
        return finished
    }
    /// Fertiger KI-Zug fuer den Verlauf
    func turnValue(now: Double) -> SagaTurn {
        SagaTurn(n: turn, role: "ai", zug: noMatch ? "no_match" : "answer", text: text, sources: sources, created: now)
    }
}

// MARK: - Auftraege (K-B)

/// Wert eines aenderbaren Feldes im Plan ("Aendern"): Text oder Schalter (allday)
enum SagaField: Equatable {
    case text(String), flag(Bool)
    init?(_ v: Any?) {
        if let n = v as? NSNumber {                           // JSON-Wahrheitswert vs. Zahl (beide kommen als NSNumber)
            self = CFGetTypeID(n) == CFBooleanGetTypeID() ? .flag(n.boolValue) : .text(n.stringValue); return
        }
        if let s = v as? String { self = .text(s); return }
        return nil
    }
    var json: Any {
        switch self {
        case .text(let s): return s.trimmingCharacters(in: .whitespacesAndNewlines)
        case .flag(let b): return b
        }
    }
    var string: String { if case .text(let s) = self { return s }; return "" }
    var bool: Bool { if case .flag(let b) = self { return b }; return false }
}

struct SagaStep: Identifiable, Equatable {
    var n: Int
    var tool: String
    var klass: String                // read | own | out
    var kindText: String
    var undoClass: String?
    var classText: String
    var text: String
    var wording: String?
    var edit: [String]
    var fields: [String: SagaField]
    var id: Int { n }
    init?(_ v: Any?) {
        guard let d = v as? [String: Any], let n = ShareJSON.int(d["n"]) else { return nil }
        self.n = n
        tool = d["tool"] as? String ?? ""
        klass = d["klass"] as? String ?? ""
        kindText = d["kind_text"] as? String ?? ""
        undoClass = ShareJSON.str(d["undo_class"])
        classText = d["class_text"] as? String ?? ""
        text = d["text"] as? String ?? ""
        wording = ShareJSON.str(d["wording"])
        edit = (d["edit"] as? [Any] ?? []).compactMap { $0 as? String }
        var f: [String: SagaField] = [:]
        for (k, x) in d["fields"] as? [String: Any] ?? [:] { if let v = SagaField(x) { f[k] = v } }
        fields = f
    }
    /// Symbol der Art (◎ Lesen · ✎ Eigenes · ↗ Nach aussen) - nie nur Farbe, die Klasse steht immer als Wort daneben
    var symbol: String { SagaLogic.kindSymbol(klass) }
    /// Startwert eines Feldes fuer "Aendern" (wie im Web: Wortlaut aus wording, wenn markdown fehlt)
    func initial(_ k: String) -> SagaField {
        if let v = fields[k] { return v }
        if k == "allday" { return .flag(false) }
        if k == "markdown" { return .text(wording ?? "") }
        return .text("")
    }
}

/// Plan (Ereignis "plan" und detail.plan) - ein Vertrag: Freigabe nur mit genau diesem plan_v + plan_hash
struct SagaPlan: Equatable {
    var job: Int
    var planV: Int
    var planHash: String
    var title: String
    var state: String?
    var steps: [SagaStep]
    var readText: String
    var readLabels: [String]
    var say: String?
    var edited: Bool
    init?(_ v: Any?) {
        guard let d = v as? [String: Any], let job = ShareJSON.int(d["job"]), let pv = ShareJSON.int(d["plan_v"]),
              let ph = ShareJSON.str(d["plan_hash"]) else { return nil }
        self.job = job; planV = pv; planHash = ph
        title = d["title"] as? String ?? ""
        state = ShareJSON.str(d["state"])
        steps = (d["steps"] as? [Any] ?? []).compactMap(SagaStep.init)
        readText = d["read_text"] as? String ?? ""
        readLabels = (d["read"] as? [Any] ?? []).compactMap { ($0 as? [String: Any]).flatMap { ShareJSON.str($0["label"]) } }
        say = ShareJSON.str(d["say"])
        edited = ShareJSON.bool(d["edited"])
    }
    var editable: Bool { steps.contains { !$0.edit.isEmpty } }
    /// "geändert – bitte neu freigeben": nach "Ändern" (edited) - auch nach dem Neuladen, das edited nicht mitliefert
    var changed: Bool { edited || planV > 1 }
}

/// Ausgang von POST /api/ai/jobs (SSE): genau eines von plan, ask, refuse, error (status davor nur als Text)
enum SagaJobOutcome: Equatable {
    struct Alternative: Equatable { var recipe: String; var label: String?; var fields: [String: String] }
    case plan(SagaPlan)
    case ask(question: String, choices: [String])
    case refuse(text: String, alternative: Alternative?)
    case error(String, status: Int?)
}

/// Zeile der Auftrags-Liste (B5)
struct SagaJobItem: Identifiable, Equatable {
    var id: Int
    var title: String
    var state: String
    var stateText: String
    var group: String
    var line: String
    var updated: Double?
    init?(_ v: Any?) {
        guard let d = v as? [String: Any], let id = ShareJSON.int(d["id"]) else { return nil }
        self.id = id
        title = d["title"] as? String ?? ""
        state = d["state"] as? String ?? ""
        stateText = d["state_text"] as? String ?? ""
        group = ShareJSON.str(d["group"]) ?? SagaLogic.groupOf(state) ?? ""
        line = d["line"] as? String ?? ""
        updated = ShareJSON.num(d["updated"])
    }
    /// Nebenzeile wie im Web: Zustandswort, ggf. mit Grund ("Wartet auf dich · …"), nie doppelt
    var subline: String { SagaLogic.subline(stateText: stateText, line: line) }
}

struct SagaJobGroup: Identifiable, Equatable {
    var key: String
    var label: String
    var items: [SagaJobItem]
    var id: String { key }
}

struct SagaJobList: Equatable {
    var groups: [SagaJobGroup] = []
    init() {}
    init?(data: Data) {
        guard let j = ShareJSON.obj(data), let gs = j["groups"] as? [Any] else { return nil }
        groups = SagaLogic.ordered(gs.compactMap { v -> SagaJobGroup? in
            guard let g = v as? [String: Any], let k = g["key"] as? String else { return nil }
            return SagaJobGroup(key: k, label: g["label"] as? String ?? "", items: (g["items"] as? [Any] ?? []).compactMap(SagaJobItem.init))
        })
    }
    var total: Int { groups.reduce(0) { $0 + $1.items.count } }
    /// Gruppen mit Eintraegen (leere Gruppen zeigt die Liste nicht)
    var visible: [SagaJobGroup] { groups.filter { !$0.items.isEmpty } }
}

/// Eine Aenderung eines Auftrags (detail.changes[])
struct SagaChange: Identifiable, Equatable {
    var id: Int
    var label: String
    var undoClass: String?
    var classText: String
    var undone: Bool
    var canUndo: Bool
    var why: String?
    var versionURL: String?
    init?(_ v: Any?) {
        guard let d = v as? [String: Any], let id = ShareJSON.int(d["id"]) else { return nil }
        self.id = id
        label = d["label"] as? String ?? ""
        undoClass = ShareJSON.str(d["undo_class"])
        classText = d["class_text"] as? String ?? ""
        undone = ShareJSON.bool(d["undone"]); canUndo = ShareJSON.bool(d["can_undo"])
        why = ShareJSON.str(d["why"])
        versionURL = SagaLogic.beforePath(ShareJSON.str(d["version_url"]))
    }
}

/// Rueckmeldung (B4): Ergebnis zuerst, je Aenderung eine Zeile
struct SagaReport: Equatable {
    struct Line: Identifiable, Equatable {
        var change: Int; var text: String; var undone: Bool; var canUndo: Bool; var label: String? = nil
        var id: Int { change }
    }
    var text: String
    var lines: [Line]
    init(text: String, lines: [Line] = []) { self.text = text; self.lines = lines }
    init?(_ v: Any?) {
        guard let d = v as? [String: Any] else { return nil }
        text = d["text"] as? String ?? ""
        lines = (d["lines"] as? [Any] ?? []).compactMap { x -> Line? in
            guard let l = x as? [String: Any], let c = ShareJSON.int(l["change"]) else { return nil }
            return Line(change: c, text: l["text"] as? String ?? "", undone: ShareJSON.bool(l["undone"]), canUndo: ShareJSON.bool(l["can_undo"]),
                        label: ShareJSON.str(l["label"]))
        }
    }
}

struct SagaJobDetail: Equatable {
    struct Event: Identifiable, Equatable { var at: Double; var kind: String; var text: String; var i: Int; var id: Int { i } }
    var id: Int
    var title: String
    var state: String
    var stateText: String
    var reasonText: String?
    var plan: SagaPlan?
    var planSteps: [SagaStep]
    var readText: String
    var approvedAt: Double?
    var approvedDevice: String?
    var changes: [SagaChange]
    var report: SagaReport?
    var events: [Event]
    var actions: [String]
    init?(data: Data) { self.init(ShareJSON.obj(data)) }
    init?(_ v: Any?) {
        guard let d = v as? [String: Any], let id = ShareJSON.int(d["id"]) else { return nil }
        self.id = id
        title = d["title"] as? String ?? ""
        state = d["state"] as? String ?? ""
        stateText = d["state_text"] as? String ?? ""
        reasonText = ShareJSON.str(d["reason_text"])
        plan = SagaPlan(d["plan"])
        let pd = d["plan"] as? [String: Any]
        planSteps = (pd?["steps"] as? [Any] ?? []).compactMap(SagaStep.init)
        readText = pd?["read_text"] as? String ?? ""
        let ap = d["approved"] as? [String: Any]
        approvedAt = ShareJSON.num(ap?["at"]); approvedDevice = ShareJSON.str(ap?["device"])
        changes = (d["changes"] as? [Any] ?? []).compactMap(SagaChange.init)
        report = SagaReport(d["report"])
        events = (d["events"] as? [Any] ?? []).enumerated().compactMap { i, x -> Event? in
            guard let e = x as? [String: Any], let tx = ShareJSON.str(e["text"]) else { return nil }
            return Event(at: ShareJSON.num(e["at"]) ?? 0, kind: e["kind"] as? String ?? "", text: tx, i: i)
        }
        actions = (d["actions"] as? [Any] ?? []).compactMap { $0 as? String }
    }
    /// Plan-Karte im Detail nur, solange der Auftrag auf das OK wartet
    var awaitingOK: Bool { state == "wartet_ok" && plan != nil && !(plan?.steps.isEmpty ?? true) }
    /// Rueckmeldung bei Erledigt/Zurueckgenommen; ohne report der Grund bzw. das Zustandswort
    var feedback: SagaReport? {
        guard state == "erledigt" || state == "zurueckgenommen" else { return nil }
        return report ?? SagaReport(text: reasonText ?? stateText)
    }
    /// "Auftrag abbrechen" ausserhalb der Plan-Karte
    var cancellable: Bool { actions.contains("cancel") && state != "wartet_ok" }
    /// Plan-Karte mit Freigabe nur mit Einwilligung (sonst lehnt der Server ab, 403 consent); ohne Einwilligung
    /// wird der Plan nur gezeigt und "Auftrag abbrechen" steht auch bei "Wartet auf dein OK" (Widerruf, 2518)
    func showsPlanCard(consentOK: Bool) -> Bool { awaitingOK && consentOK }
    func cancellable(consentOK: Bool) -> Bool { cancellable || (!consentOK && state == "wartet_ok" && actions.contains("cancel")) }
    func change(_ id: Int) -> SagaChange? { changes.first { $0.id == id } }
    /// Text neben "Rückgängig": das Ergebnis nennt die Aenderungen schon - bei einer Aenderung nur der Knopf, sonst der
    /// Gegenstand (label; aeltere Server: die ganze Zeile)
    static func lineCaption(_ l: SagaReport.Line, count: Int) -> String? { count <= 1 ? nil : (l.label ?? l.text) }
}

/// Einstellungen der Person (GET /api/ai/prefs, K-H + K-I)
struct SagaPrefs: Equatable {
    var enabled = true
    var salutation: String?
    var lively: String?
    var defaultSalutation = "du"
    var defaultLively = "warm"
    var salutationChoices: [String] = ["du", "sie"]
    var livelyChoices: [String] = []
    var ideas = "off"
    var ideasAllowed = false
    var history = false
    var historyAllowed = false
    var sources: [String: Bool] = [:]
    var consent: SagaConsent?
    init() {}
    init?(data: Data) {
        guard let j = ShareJSON.obj(data) else { return nil }
        if j["enabled"] as? Bool == false { enabled = false; return }
        salutation = ShareJSON.str(j["salutation"]); lively = ShareJSON.str(j["lively"])
        if let df = j["default"] as? [String: Any] {
            defaultSalutation = ShareJSON.str(df["salutation"]) ?? defaultSalutation; defaultLively = ShareJSON.str(df["lively"]) ?? defaultLively
        }
        if let ch = j["choices"] as? [String: Any] {
            salutationChoices = (ch["salutation"] as? [Any] ?? []).compactMap { $0 as? String }
            livelyChoices = (ch["lively"] as? [Any] ?? []).compactMap { $0 as? String }
        }
        ideas = ShareJSON.str(j["ideas"]) ?? "off"
        ideasAllowed = ShareJSON.bool(j["ideas_allowed"])
        history = ShareJSON.bool(j["history"]); historyAllowed = ShareJSON.bool(j["history_allowed"])
        for (k, v) in j["sources"] as? [String: Any] ?? [:] { sources[k] = ShareJSON.bool(v) }
        consent = SagaConsent(j["consent"])
    }
    /// Quelle an? (fehlt = an, wie der Server)
    func source(_ k: String) -> Bool { sources[k] ?? true }
}

enum SagaLogic {
    /// Seitenleisten-Tag des Orts "Saga" (neben Aufgaben -1 … Heute -6)
    static let tag = -7
    /// Gruppen der Liste in fester Reihenfolge (K2)
    static let groupOrder = ["wartet_auf_dich", "laeuft", "fertig", "nicht_geklappt"]
    /// Quellen in Einstellungen › Saga (Reihenfolge wie im Web)
    static let sourceKeys = ["pages", "tasks", "calendar", "chat"]

    /// Zustand (7, intern) -> Gruppe (4, nach aussen) - nur Rueckfall, wenn der Server "group" nicht nennt
    static func groupOf(_ state: String) -> String? {
        switch state {
        case "wartet_ok", "wartet_dich": return "wartet_auf_dich"
        case "laeuft": return "laeuft"
        case "erledigt", "abgebrochen", "zurueckgenommen": return "fertig"
        case "fehlgeschlagen": return "nicht_geklappt"
        default: return nil
        }
    }
    /// Gruppen in die feste Reihenfolge bringen; unbekannte Gruppen hinten (in Server-Reihenfolge)
    static func ordered(_ gs: [SagaJobGroup]) -> [SagaJobGroup] {
        let known = groupOrder.compactMap { k in gs.first { $0.key == k } }
        return known + gs.filter { !groupOrder.contains($0.key) }
    }
    /// SF-Symbol je Gruppe (Liste): nie nur Farbe - die Nebenzeile nennt das Zustandswort
    static func groupSymbol(_ key: String) -> String {
        switch key {
        case "nicht_geklappt": return "exclamationmark.circle"
        case "laeuft": return "clock"
        case "fertig": return "checkmark.circle"
        default: return "circle.fill"
        }
    }
    /// Symbol einer Zeile: in "Fertig" je Zustand (✓ nur bei Erledigt, – Abgebrochen, ↶ Zurueckgenommen), sonst je Gruppe
    static func itemSymbol(group: String, state: String) -> String {
        switch state {
        case "abgebrochen": return "minus.circle"
        case "zurueckgenommen": return "arrow.uturn.backward.circle"
        default: return groupSymbol(group)
        }
    }
    static func kindSymbol(_ klass: String) -> String {
        switch klass {
        case "read": return "◎"
        case "own": return "✎"
        case "out": return "↗"
        default: return "•"
        }
    }
    static func subline(stateText: String, line: String) -> String {
        if line.isEmpty || line.compare(stateText, options: .caseInsensitive) == .orderedSame { return stateText }   // "läuft" = "Läuft"
        if stateText.isEmpty || line.hasPrefix(stateText) { return line }
        return stateText + " · " + line
    }

    // MARK: Einwilligung

    enum ConsentAnswer: String { case ideas, noIdeas = "no_ideas", later }
    /// Knoepfe der Karte: mit ai_ideas "Ja, mit Ideen" · "Ja, ohne Ideen", sonst "Ja, einverstanden" (= no_ideas); immer "Jetzt nicht"
    static func introAnswers(ideasAllowed: Bool) -> [ConsentAnswer] { ideasAllowed ? [.ideas, .noIdeas, .later] : [.noIdeas, .later] }
    /// Body fuer POST /api/ai/consent: Fassung und Host der Karte, die die Person gelesen hat (409 bei Abweichung)
    static func consentBody(_ a: ConsentAnswer, intro: SagaIntro) -> [String: Any] {
        a == .later ? ["answer": a.rawValue] : ["answer": a.rawValue, "version": intro.version, "host": intro.host]
    }
    enum ConsentResult: Equatable { case ok(SagaConsent?), reload(String), failed(String) }
    /// Antwort auf die Einwilligung: 409 = Text/Host geaendert -> Status neu laden, Karte neu zeigen
    static func consentResult(code: Int, body: Data) -> ConsentResult {
        let j = ShareJSON.obj(body) ?? [:]
        if (200..<300).contains(code) { return .ok(SagaConsent(j["consent"])) }
        let msg = ShareJSON.str(j["error"]) ?? ""
        return code == 409 ? .reload(msg) : .failed(msg)
    }
    /// Serverantwort "erst kennenlernen" (403 {consent: "needed"}): Status neu laden, Karte zeigen
    static func needsConsent(code: Int, body: Data?) -> Bool {
        code == 403 && body.flatMap(ShareJSON.obj).map { ($0["consent"] as? String) == "needed" } == true
    }

    // MARK: Auftraege

    /// Body fuer POST /api/ai/jobs: Bitte (+ Rueckfrage-Wahl) oder Rezept direkt (nur die Kennung, nie ein Objekt - RT-21)
    static func giveBody(text: String, choice: String? = nil, note: Int? = nil) -> [String: Any] {
        var b: [String: Any] = ["text": String(text.trimmingCharacters(in: .whitespacesAndNewlines).prefix(1000)), "source": "saga"]
        if let choice { b["choice"] = String(choice.prefix(200)) }
        if let note, note > 0 { b["context"] = ["note": note] }
        return b
    }
    static func recipeBody(_ alt: SagaJobOutcome.Alternative, text: String) -> [String: Any] {
        ["recipe": alt.recipe, "fields": alt.fields, "text": text.isEmpty ? " " : String(text.prefix(1000))]
    }
    /// Ereignis des Auftrags-Stroms -> Ausgang (nil: status oder unbekannt)
    static func outcome(_ e: SSEEvent) -> SagaJobOutcome? {
        let d = e.json
        switch e.event {
        case "plan": return SagaPlan(d).map { .plan($0) } ?? .error("", status: nil)
        case "ask": return .ask(question: d["question"] as? String ?? "", choices: (d["choices"] as? [Any] ?? []).compactMap { $0 as? String })
        case "refuse": return .refuse(text: d["text"] as? String ?? "", alternative: alternative(d["alternative"]))
        case "error": return .error(ShareJSON.str(d["error"]) ?? "", status: ShareJSON.int(d["status"]))
        default: return nil
        }
    }
    /// Alternative einer Absage ({recipe, fields, label}) - nur Kennung + Textfelder
    static func alternative(_ v: Any?) -> SagaJobOutcome.Alternative? {
        guard let a = v as? [String: Any], let r = ShareJSON.str(a["recipe"]) else { return nil }
        var f: [String: String] = [:]
        for (k, x) in a["fields"] as? [String: Any] ?? [:] { if let s = x as? String { f[k] = s } }
        return .init(recipe: r, label: ShareJSON.str(a["label"]), fields: f)
    }
    /// "Passt, los": genau dieser Plan (plan_v + plan_hash) - der Server lehnt alles andere mit 409 ab
    static func approveBody(_ p: SagaPlan) -> [String: Any] { ["plan_v": p.planV, "plan_hash": p.planHash] }
    /// "Aendern": nur Felder aus steps[].edit; Texte ohne Leerraum am Rand
    static func editBody(_ p: SagaPlan, values: [Int: [String: SagaField]]) -> [String: Any] {
        var edits: [[String: Any]] = []
        for s in p.steps where !s.edit.isEmpty {
            guard let v = values[s.n] else { continue }
            var args: [String: Any] = [:]
            for k in s.edit { if let x = v[k] { args[k] = x.json } }
            if !args.isEmpty { edits.append(["n": s.n, "args": args]) }
        }
        return ["plan_v": p.planV, "edits": edits]
    }

    enum ActionResult: Equatable {
        case ok(stateText: String?, text: String?)
        case stale(String)                 // 409 code stale: Plan geaendert -> Detail neu laden
        case state(String)                 // 409 code state: nicht mehr in diesem Zustand
        case changed(versionURL: String?)  // 409 code changed (Rueckgaengig): Ziel wurde seitdem geaendert
        case consent(String)               // 403 consent needed
        case failed(String)
    }
    static func actionResult(code: Int, body: Data) -> ActionResult {
        let j = ShareJSON.obj(body) ?? [:]
        let msg = ShareJSON.str(j["error"]) ?? ""
        if (200..<300).contains(code) { return .ok(stateText: ShareJSON.str(j["state_text"]), text: ShareJSON.str(j["say"]) ?? ShareJSON.str(j["text"])) }
        if code == 403, (j["consent"] as? String) == "needed" { return .consent(msg) }
        if code == 409 {
            switch j["code"] as? String {
            case "stale": return .stale(msg)
            case "changed": return .changed(versionURL: beforePath(ShareJSON.str(j["version_url"])))
            default: return .state(msg)
            }
        }
        return .failed(msg)
    }
    /// "Alte Fassung holen": nur ein Pfad dieses Servers unter /api/ai/jobs/{id}/changes/{c}/before (nie fremde Hosts)
    static func beforePath(_ s: String?) -> String? {
        guard let s, !s.contains("://"), !s.hasPrefix("//"), !s.contains(".."), !s.contains("?"), !s.contains("#") else { return nil }
        let p = s.split(separator: "/", omittingEmptySubsequences: false)
        guard p.count == 8, p[0].isEmpty, p[1] == "api", p[2] == "ai", p[3] == "jobs", Int(p[4]) != nil, p[5] == "changes", Int(p[6]) != nil, p[7] == "before"
        else { return nil }
        return String(s.dropFirst())                      // relativ fuer Store.api
    }
    /// Hoechste Zugnummer des Servers (lokal angehaengte Zuege ohne n zaehlen nicht)
    static func maxN(_ turns: [SagaTurn]) -> Int { turns.compactMap(\.n).max() ?? 0 }
    /// Hat der Server seit n0 Zuege angelegt? Ueber die Nummer, nicht ueber die Anzahl: mit Verlauf kuerzt der Server auf
    /// 200 Zuege, die Anzahl bleibt dann gleich (sonst haengte die App die Zuege ein zweites Mal an)
    static func hasServerTurns(_ turns: [SagaTurn], after n0: Int) -> Bool { turns.contains { ($0.n ?? 0) > n0 } }
    /// Live-Ereignis ai.job.changed {id, state, sub, plan_v} (nur Kennungen)
    static func jobChanged(_ d: [String: Any]) -> (id: Int, state: String?)? {
        ShareJSON.int(d["id"]).map { ($0, ShareJSON.str(d["state"])) }
    }
    /// Rueckmeldung an Ort und Stelle: beobachteter Auftrag hat "laeuft" verlassen
    static func reportDue(watched: Bool, state: String?) -> Bool { watched && state != nil && state != "laeuft" }

    // MARK: Aendern - Datumsfelder (due JJJJ-MM-TT, start/end ganztaegig JJJJ-MM-TT oder JJJJ-MM-TTTHH:MM)

    enum DateKind: Equatable { case day, minute }
    static func dateKind(field: String, value: String) -> DateKind? {
        switch field {
        case "due": return .day
        case "start", "end": return value.count == 10 ? .day : .minute
        default: return nil
        }
    }
    private static func fmt(_ k: DateKind) -> DateFormatter {
        let f = DateFormatter(); f.locale = Locale(identifier: "en_US_POSIX"); f.timeZone = .current
        f.dateFormat = k == .day ? "yyyy-MM-dd" : "yyyy-MM-dd'T'HH:mm"
        return f
    }
    /// Wert -> Datum; mit Sekunden/Zone (falls ein Server sie liefert) ueber ISO 8601, sonst Ortszeit ohne Zone
    static func date(_ s: String, kind: DateKind) -> Date? {
        let t = s.trimmingCharacters(in: .whitespaces)
        if kind == .day { return fmt(.day).date(from: String(t.prefix(10))) }
        if t.count > 16 {
            let iso = ISO8601DateFormatter()
            for o: ISO8601DateFormatter.Options in [[.withInternetDateTime], [.withInternetDateTime, .withFractionalSeconds]] {
                iso.formatOptions = o; if let d = iso.date(from: t) { return d }
            }
        }
        return fmt(.minute).date(from: String(t.prefix(16)))
    }
    /// Datum -> Wert wie das Werkzeug ihn erwartet (JJJJ-MM-TT bzw. JJJJ-MM-TTThh:mm ohne Zone)
    static func string(_ d: Date, kind: DateKind) -> String { fmt(kind).string(from: d) }

    // MARK: Meine Daten

    static func exportName(format: String, now: Date) -> String {
        let f = DateFormatter(); f.locale = Locale(identifier: "en_US_POSIX"); f.dateFormat = "yyyy-MM-dd"
        return "saga-ki-\(f.string(from: now)).\(format == "md" ? "md" : "json")"
    }
    /// POST /api/ai/mydata/delete braucht die ausdrueckliche Bestaetigung
    static let deleteBody: [String: Any] = ["confirm": true]

    // MARK: Melden (wie Abschnitt 6: where ask | job)

    static func reportBody(where w: String, job: Int?, reason: String, text: String?) -> [String: Any] {
        var b: [String: Any] = ["where": w, "reason": reason]
        if let job { b["page"] = job }
        let t = (text ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        if !t.isEmpty { b["text"] = String(t.prefix(2000)) }
        return b
    }
    /// Text einer Plan-Karte zum Mitschicken (Schritte + Wortlaut)
    static func planText(_ p: SagaPlan) -> String {
        p.steps.map { s in s.text + (s.wording.map { "\n" + $0 } ?? "") }.joined(separator: "\n")
    }
}
