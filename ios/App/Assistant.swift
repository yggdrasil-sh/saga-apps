import SwiftUI

// KI-Assistent (Saga 1.6): "Frag deine Seiten" und "Diese Seite" (Zusammenfassen, Aufgaben, Verbessern, Uebersetzen).
// Antworten kommen als Strom (POST, text/event-stream) vom EIGENEN Server - die App spricht nie mit dem Modell.
// Der Server aendert nie etwas: Uebernehmen macht die App ueber die normalen Wege, nach einem Klick. Logik: AILogic.swift

@MainActor
final class AssistantModel: ObservableObject {
    struct QA: Identifiable { let id = UUID(); var q: String; var answer = ""; var sources: [AISource] = []; var error: String?; var running = true; var stopped = false }
    weak var store: Store?
    @Published var status: AIStatus?
    @Published var qa: [QA] = []
    @Published var running = false
    // Diese Seite
    @Published var pageText = ""
    @Published var pageSources: [AISource] = []
    @Published var pageDone: AIDone?
    @Published var pageAction: String?
    @Published var pageError: String?
    @Published var history: [[String: Any]] = []
    private var task: Task<Void, Never>?
    private lazy var session: URLSession = {
        let c = URLSessionConfiguration.default
        c.timeoutIntervalForRequest = 60; c.timeoutIntervalForResource = 600
        return URLSession(configuration: c)
    }()

    func loadStatus() async {
        guard let store, let (d, code) = try? await store.apiResponse("api/ai/status") else { return }
        status = code == 200 ? (AIStatus(data: d) ?? AIStatus()) : AIStatus()
    }

    func stop() { task?.cancel(); task = nil; running = false }

    func ask(_ q: String, tasks: Bool, calendar: Bool) {
        guard !running else { return }
        qa.append(QA(q: q))
        let idx = qa.count - 1
        var body: [String: Any] = ["q": q]
        if tasks { body["tasks"] = true }
        if calendar { body["calendar"] = true }
        run("api/ai/ask", body,
            sources: { [weak self] s in self?.qa[idx].sources = s },
            delta: { [weak self] t in self?.qa[idx].answer += t },
            done: { [weak self] _ in self?.qa[idx].running = false },
            fail: { [weak self] e, partial in self?.qa[idx].error = e; self?.qa[idx].running = false; if !partial { self?.qa[idx].answer = "" } },
            cancelled: { [weak self] in self?.qa[idx].running = false; self?.qa[idx].stopped = true })
    }

    func page(_ id: Int, action: String, blocks: [String]? = nil, target: String? = nil) {
        guard !running else { return }
        pageText = ""; pageSources = []; pageDone = nil; pageError = nil; pageAction = action
        var body: [String: Any] = ["action": action]
        if let blocks { body["blocks"] = blocks }
        if let target { body["target"] = target }
        run("api/ai/page/\(id)", body,
            sources: { [weak self] s in self?.pageSources = s },
            delta: { [weak self] t in self?.pageText += t },
            done: { [weak self] d in self?.pageDone = d },
            fail: { [weak self] e, partial in self?.pageError = e; if !partial { self?.pageText = "" } },
            cancelled: {})
    }

    /// Strom lesen: sources -> delta* -> done | error. Fehler VOR dem Strom kommen als JSON {error}.
    private func run(_ path: String, _ body: [String: Any], sources: @escaping ([AISource]) -> Void, delta: @escaping (String) -> Void,
                     done: @escaping (AIDone) -> Void, fail: @escaping (String, Bool) -> Void, cancelled: @escaping () -> Void) {
        guard let store else { return }
        running = true
        task = Task { [weak self] in
            defer { Task { @MainActor in self?.running = false } }
            do {
                let (bytes, code, err) = try await store.openStream(path, method: "POST", body: body, session: self?.session ?? .shared)
                guard code == 200 else {
                    let msg = err.flatMap(ShareJSON.obj).flatMap { ShareJSON.str($0["error"]) } ?? APIError.http(code, "").localizedDescription
                    fail(msg, false); return
                }
                var parser = SSEParser()
                var buf = Data()
                for try await b in bytes {
                    buf.append(b)
                    guard b == 0x0A else { continue }
                    let evs = parser.feed(bytes: buf); buf.removeAll(keepingCapacity: true)
                    for e in evs {
                        let d = e.json
                        switch e.event {
                        case "sources": sources((d["sources"] as? [Any] ?? []).compactMap(AISource.init))
                        case "delta": delta(d["t"] as? String ?? "")
                        case "done":
                            let x = AIDone(d)
                            if let s = self?.status { self?.status = AILogic.updated(s, quotaLeft: x.quotaLeft) }
                            done(x); return
                        case "error": fail(ShareJSON.str(d["error"]) ?? String(localized: "Fehler"), ShareJSON.bool(d["partial"])); return
                        default: break
                        }
                    }
                }
            } catch {
                if Task.isCancelled || (error as? URLError)?.code == .cancelled { cancelled() } else { fail(error.localizedDescription, true) }
            }
        }
    }

    // MARK: Verlauf

    func loadHistory() async {
        guard let store, let d = try? await store.api("api/ai/history"), let j = ShareJSON.obj(d) else { return }
        history = j["items"] as? [[String: Any]] ?? []
        if var s = status { s.history = ShareJSON.bool(j["history"]); status = s }
    }
    func setHistory(_ on: Bool) async {
        guard let store else { return }
        if let d = try? await store.api("api/ai/settings", method: "PUT", body: ["history": on]), let j = ShareJSON.obj(d), var s = status {
            s.history = j["history"].map(ShareJSON.bool) ?? on; status = s
        }
        await loadHistory()
    }
    func deleteHistory(_ id: Int?) async {
        guard let store else { return }
        _ = try? await store.api(id.map { "api/ai/history/\($0)" } ?? "api/ai/history", method: "DELETE")
        await loadHistory()
    }
}

/// Uebernehmen im Editor (nur nach Klick, nur mit Bearbeiten-Recht)
struct AssistantPageActions {
    var canEdit: Bool
    var locked: Bool
    var remote: Bool
    var selected: [String]
    var flush: () async -> Void
    var replace: ([String], [String]) -> Void
    var insertBelow: (String) -> Void
    var insertChecklist: ([String]) -> Void
}

struct AssistantSheet: View {
    let pageID: Int?
    var actions: AssistantPageActions?
    var initialTab = 0
    var initialAction: String?
    var openPage: (Int) -> Void
    @EnvironmentObject var store: Store
    @StateObject private var m = AssistantModel()
    @Environment(\.dismiss) private var dismiss
    @State private var tab = 0
    @State private var q = ""
    @State private var withTasks = false
    @State private var withCal = false
    @State private var target = "en"
    @State private var showHistory = false
    @State private var picked: Set<Int> = []
    @State private var askReplace = false
    @State private var info: String?

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                if pageID != nil {
                    Picker("", selection: $tab) {
                        Text("Frag deine Seiten").tag(0)
                        Text("Diese Seite").tag(1)
                    }
                    .pickerStyle(.segmented).labelsHidden().padding([.horizontal, .top])
                }
                if tab == 0 { askTab } else { pageTab }
                footer
            }
            .navigationTitle(store.aiName ?? String(localized: "KI-Assistent"))
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Schließen") { m.stop(); dismiss() } }
                if m.status?.historyAllowed == true {
                    ToolbarItem(placement: .primaryAction) { Button { showHistory = true } label: { Label("Verlauf", systemImage: "clock.arrow.circlepath") } }
                }
            }
            .sheet(isPresented: $showHistory) { AssistantHistorySheet(m: m, openPage: { id in showHistory = false; dismiss(); openPage(id) }) }
        }
        .macFrame(minWidth: 520, idealWidth: 600, minHeight: 560, idealHeight: 700)
        .task {
            m.store = store; tab = pageID == nil ? 0 : initialTab
            await m.loadStatus()
            if let t = m.status?.targets.first(where: { $0 != AppConfig.appLanguage.prefix(2).lowercased() }) ?? m.status?.targets.first { target = t }
            #if DEBUG
            if let qq = UserDefaults.standard.string(forKey: "hkAIAsk") { q = qq; ask() }        // nur fuer Tests
            #endif
            if let a = initialAction { run(a) }
        }
        .onDisappear { m.stop() }
    }

    // MARK: Frag deine Seiten

    private var askTab: some View {
        VStack(spacing: 0) {
            ScrollViewReader { proxy in
                ScrollView {
                    VStack(alignment: .leading, spacing: 16) {
                        if m.qa.isEmpty {
                            VStack(alignment: .leading, spacing: 8) {
                                if store.aiPersona != nil { AIAvatarView(persona: store.aiPersona, size: 44) }
                                else { Image(systemName: "sparkles").font(.largeTitle).foregroundStyle(Color.gold) }
                                Text("Frag etwas – der Assistent sucht in den Seiten, die du sehen darfst, und nennt seine Quellen.")
                                Text("Er kann nichts ändern: Vorschläge übernimmst du selbst. Chats liest er nie; Aufgaben und Kalender nur, wenn du sie einschaltest.")
                                    .font(.callout).foregroundStyle(.secondary)
                            }.padding(.top, 20)
                        }
                        ForEach(m.qa) { x in
                            VStack(alignment: .leading, spacing: 8) {
                                Text(verbatim: x.q).padding(10).background(Color.gold.opacity(0.14), in: RoundedRectangle(cornerRadius: 12))
                                    .frame(maxWidth: .infinity, alignment: .trailing)
                                if let n = store.aiName, x.running || !x.answer.isEmpty { AIWhoLine(persona: store.aiPersona, name: n) }   // Antwort mit Bild + Name
                                if x.running && x.answer.isEmpty { HStack { ProgressView().controlSize(.small); Text(verbatim: store.aiThinking).foregroundStyle(.secondary) } }
                                if !x.answer.isEmpty { AnswerText(text: x.answer + (x.stopped ? " " + String(localized: "(abgebrochen)") : ""), sources: x.sources, openPage: open) }
                                if let e = x.error { Label(e, systemImage: "exclamationmark.triangle").font(.footnote).foregroundStyle(.orange) }
                                if !x.running && x.error == nil { SourceChips(sources: x.sources, openPage: open) }
                            }.id(x.id)
                        }
                    }
                    .padding()
                }
                .onChange(of: m.qa.last?.answer) { _, _ in if let id = m.qa.last?.id { proxy.scrollTo(id, anchor: .bottom) } }
            }
            Divider()
            VStack(alignment: .leading, spacing: 8) {
                HStack(alignment: .bottom) {
                    TextField(store.aiName.map { String(localized: "Frage \($0) etwas zu deinen Seiten …") } ?? String(localized: "Frag etwas zu deinen Seiten …"), text: $q, axis: .vertical).lineLimit(1...5).textFieldStyle(.roundedBorder)
                        .onSubmit { ask() }
                    if m.running { Button("Stopp") { m.stop() }.buttonStyle(.bordered) }
                    else { Button("Fragen") { ask() }.prominentGold().disabled(!AILogic.validQuestion(q, max: m.status?.qMax ?? 1000)) }
                }
                HStack(spacing: 8) {                                                // kompakt als Schalt-Chips (je Frage)
                    Toggle(isOn: $withTasks) { Label("Aufgaben einbeziehen", systemImage: "checklist") }
                    Toggle(isOn: $withCal) { Label("Kalender einbeziehen", systemImage: "calendar") }
                }
                .toggleStyle(.button).buttonStyle(.bordered).controlSize(.small).font(.caption)
            }
            .padding()
        }
    }
    private func ask() {
        let t = q.trimmingCharacters(in: .whitespacesAndNewlines)
        guard AILogic.validQuestion(t, max: m.status?.qMax ?? 1000) else { return }
        q = ""; m.ask(t, tasks: withTasks, calendar: withCal)
    }
    private func open(_ id: Int) { m.stop(); dismiss(); openPage(id) }

    // MARK: Diese Seite

    private var pageTab: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                if actions?.remote == true {
                    Label("Seiten anderer Saga-Instanzen bearbeitet der Assistent nicht.", systemImage: "info.circle").foregroundStyle(.secondary)
                } else {
                    let sel = actions?.selected.count ?? 0
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 200), spacing: 8)], spacing: 8) {
                        actionButton("Zusammenfassen", "text.alignleft", "summarize")
                        actionButton("Aufgaben vorschlagen", "checklist", "tasks")
                        actionButton("Markierten Text verbessern", "wand.and.stars", "improve").disabled(sel == 0)
                        Menu {                                                       // Zielsprache direkt waehlen
                            ForEach(m.status?.targets ?? [], id: \.self) { t in
                                Button(Locale.current.localizedString(forLanguageCode: t)?.capitalized ?? t) { target = t; run("translate") }
                            }
                        } label: { Label("Übersetzen nach", systemImage: "character.bubble").lineLimit(2).frame(maxWidth: .infinity, minHeight: 42, alignment: .leading) }
                        .menuIndicator(.visible).buttonStyle(.bordered).controlSize(.large).disabled(sel == 0 || m.running)
                    }
                    if sel == 0 { Text("Für „Verbessern“ und „Übersetzen“ zuerst Text auf der Seite markieren.").font(.caption).foregroundStyle(.secondary) }
                    else { Text(sel == 1 ? String(localized: "Markiert: 1 Block") : String(localized: "Markiert: \(sel) Blöcke")).font(.caption).foregroundStyle(.secondary) }
                    Divider()
                    pageResult
                }
            }.padding()
        }
        .alert("Markierten Text ersetzen?", isPresented: $askReplace) {
            Button("Ersetzen", role: .destructive) {
                if let d = m.pageDone, let seg = d.segments { actions?.replace(d.blocks.map(\.id), seg); info = String(localized: "Ersetzt.") }
            }
            Button("Abbrechen", role: .cancel) {}
        } message: { Text("Der Text der markierten Blöcke wird durch den Vorschlag ersetzt (Formatierungen gehen dabei verloren). Frühere Fassungen findest du im Versionsverlauf.") }
    }
    private func actionButton(_ t: LocalizedStringKey, _ icon: String, _ a: String) -> some View {
        // gleich hohe Kacheln (Platz fuer zwei Zeilen, z. B. "Markierten Text verbessern" auf Spanisch)
        Button { run(a) } label: { Label(t, systemImage: icon).lineLimit(2).multilineTextAlignment(.leading).frame(maxWidth: .infinity, minHeight: 42, alignment: .leading) }
            .buttonStyle(.bordered).controlSize(.large).disabled(m.running)
    }
    private func run(_ a: String) {
        guard let id = pageID else { return }
        info = nil; picked = []
        Task {
            await actions?.flush()                                          // Server liest den gespeicherten Stand
            switch a {
            case "improve": m.page(id, action: a, blocks: actions?.selected)
            case "translate": m.page(id, action: a, blocks: actions?.selected, target: target)
            default: m.page(id, action: a)
            }
        }
    }
    @ViewBuilder private var pageResult: some View {
        if let n = store.aiName, m.running || !m.pageText.isEmpty { AIWhoLine(persona: store.aiPersona, name: n) }
        if m.running && m.pageText.isEmpty { HStack { ProgressView().controlSize(.small); Text(verbatim: store.aiThinking).foregroundStyle(.secondary) } }
        if let e = m.pageError { Label(e, systemImage: "exclamationmark.triangle").foregroundStyle(.orange) }
        if let d = m.pageDone, d.truncated { Label("Die Seite ist lang – nur der Anfang wurde verwendet.", systemImage: "scissors").font(.footnote).foregroundStyle(.secondary) }
        if m.pageAction == "tasks", let d = m.pageDone {
            if d.tasks.isEmpty { Text("Keine Aufgaben gefunden.").foregroundStyle(.secondary) }
            ForEach(Array(d.tasks.enumerated()), id: \.offset) { i, t in
                Toggle(isOn: Binding(get: { !picked.contains(i) }, set: { v in if v { picked.remove(i) } else { picked.insert(i) } })) { Text(verbatim: t) }
                    .toggleStyle(.checkboxLike)
            }
            if !d.tasks.isEmpty {
                let chosen = d.tasks.enumerated().filter { !picked.contains($0.offset) }.map(\.element)
                ViewThatFits(in: .horizontal) {
                    HStack { taskButtons(chosen) }
                    VStack(alignment: .leading, spacing: 8) { taskButtons(chosen) }
                }
            }
        } else if !m.pageText.isEmpty {
            AnswerText(text: m.pageText, sources: m.pageSources, openPage: open)
                .padding(12).background(Color.secondary.opacity(0.08), in: RoundedRectangle(cornerRadius: 10))
            if m.pageDone != nil {
                HStack {
                    if ["improve", "translate"].contains(m.pageAction ?? ""), let a = actions {
                        if AILogic.canReplace(canEdit: a.canEdit, locked: a.locked, done: m.pageDone) {
                            Button((m.pageDone?.blocks.count ?? 0) > 1 ? String(localized: "\(m.pageDone?.blocks.count ?? 0) Blöcke ersetzen") : String(localized: "Ersetzen")) { askReplace = true }.prominentGold()
                        }
                        if AILogic.canInsertBelow(canEdit: a.canEdit, locked: a.locked, done: m.pageDone) {
                            Button("Darunter einfügen") { a.insertBelow(m.pageText); info = String(localized: "Eingefügt.") }.buttonStyle(.bordered)
                        }
                    }
                    Button("Kopieren") { copyText(m.pageText); info = String(localized: "Kopiert.") }.buttonStyle(.bordered)
                }
            }
        }
        if let info { Label(info, systemImage: "checkmark.circle").font(.footnote).foregroundStyle(.green) }
    }
    @ViewBuilder private func taskButtons(_ chosen: [String]) -> some View {
        Button("In Aufgaben übernehmen") { Task { await addTasks(chosen) } }.prominentGold().disabled(chosen.isEmpty)
        if let a = actions, a.canEdit && !a.locked { Button("Als Checkliste einfügen") { a.insertChecklist(chosen); info = String(localized: "Eingefügt.") }.buttonStyle(.bordered).disabled(chosen.isEmpty) }
    }
    private func addTasks(_ list: [String]) async {
        var n = 0
        for t in list { if (try? await store.api("api/tasks", method: "POST", body: ["text": t])) != nil { n += 1 } }
        await store.loadToday()
        info = n == 1 ? String(localized: "1 Aufgabe übernommen.") : String(localized: "\(n) Aufgaben übernommen.")
    }

    // MARK: Fusszeile: Hinweis + Kontingent (immer sichtbar)

    private var footer: some View {
        VStack(alignment: .leading, spacing: 2) {
            Divider()
            if let s = m.status {
                if !s.notice.isEmpty { Text(verbatim: s.notice).font(.caption).foregroundStyle(.secondary) }
                if let qn = AILogic.quota(s) { Text("Heute noch \(qn.left) von \(qn.of) Anfragen").font(.caption).foregroundStyle(.secondary) }
            }
        }
        .padding(.horizontal).padding(.bottom, 8).frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// Antwort als Klartext; [n] -> antippbarer Verweis auf die Quelle
struct AnswerText: View {
    let text: String
    let sources: [AISource]
    var openPage: (Int) -> Void
    var body: some View {
        var a = AttributedString()
        for p in AILogic.pieces(text, sources: sources) {
            switch p {
            case .text(let s): a += AttributedString(s)
            case .cite(let n, let id):
                var x = AttributedString("[\(n)]")
                if let id { x.link = URL(string: "hkaisrc://\(id)") }
                x.foregroundColor = Color.gold
                a += x
            }
        }
        return Text(a).textSelection(.enabled).fixedSize(horizontal: false, vertical: true)
            .environment(\.openURL, OpenURLAction { u in
                if u.scheme == "hkaisrc", let id = Int(u.host ?? "") { openPage(id); return .handled }
                return .discarded
            })
    }
}

struct SourceChips: View {
    let sources: [AISource]
    var openPage: (Int) -> Void
    var body: some View {
        if sources.isEmpty { Text("Keine passenden Seiten gefunden.").font(.caption).foregroundStyle(.secondary) }
        else {
            VStack(alignment: .leading, spacing: 4) {
                Text("Quellen:").font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                ForEach(sources) { s in
                    Button { if let id = s.pageID { openPage(id) } } label: {
                        Text(verbatim: "\(s.n) · \(s.title.isEmpty ? String(localized: "Unbenannt") : s.title)" + (s.owner.map { " · \($0)" } ?? ""))
                            .font(.caption).padding(.horizontal, 8).padding(.vertical, 4).background(Color.secondary.opacity(0.12), in: Capsule())
                    }.buttonStyle(.plain).disabled(s.pageID == nil)
                }
            }
        }
    }
}

struct AssistantHistorySheet: View {
    @ObservedObject var m: AssistantModel
    var openPage: (Int) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var askOff = false
    @State private var askAll = false
    var body: some View {
        NavigationStack {
            List {
                Section {
                    Toggle("Verlauf speichern", isOn: Binding(get: { m.status?.history ?? false }, set: { v in if v { Task { await m.setHistory(true) } } else { askOff = true } }))
                    Text(m.status?.history == true ? "Gespeichert werden Fragen, Antworten und die Quellseiten. Quellen ohne Zugriff zeigen keinen Titel mehr." : "Aus: Fragen und Antworten werden nicht gespeichert.")
                        .font(.caption).foregroundStyle(.secondary)
                }
                Section {
                    if m.history.isEmpty { Text("Noch nichts gespeichert.").foregroundStyle(.secondary) }
                    ForEach(m.history.indices, id: \.self) { i in
                        let h = m.history[i]
                        VStack(alignment: .leading, spacing: 4) {
                            Text(verbatim: h["q"] as? String ?? "").font(.subheadline.weight(.semibold))
                            AnswerText(text: h["answer"] as? String ?? "", sources: (h["sources"] as? [Any] ?? []).compactMap(AISource.init), openPage: openPage).font(.callout)
                            SourceChips(sources: (h["sources"] as? [Any] ?? []).compactMap(AISource.init), openPage: openPage)
                        }
                        .swipeActions { Button("Löschen", role: .destructive) { Task { await m.deleteHistory(ShareJSON.int(h["id"])) } } }
                        .contextMenu { Button("Eintrag löschen", role: .destructive) { Task { await m.deleteHistory(ShareJSON.int(h["id"])) } } }
                    }
                    if !m.history.isEmpty { Button("Alles löschen", role: .destructive) { askAll = true } }
                }
            }
            .navigationTitle("Verlauf des Assistenten")
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Fertig") { dismiss() } } }
            .task { await m.loadHistory() }
            .alert("Verlauf ausschalten?", isPresented: $askOff) {
                Button("Ausschalten", role: .destructive) { Task { await m.setHistory(false) } }
                Button("Abbrechen", role: .cancel) {}
            } message: { Text("Der gespeicherte Verlauf wird dabei gelöscht.") }
            .alert("Verlauf löschen?", isPresented: $askAll) {
                Button("Löschen", role: .destructive) { Task { await m.deleteHistory(nil) } }
                Button("Abbrechen", role: .cancel) {}
            } message: { Text("Alle gespeicherten Fragen und Antworten werden gelöscht.") }
        }
        .macFrame(minWidth: 460, idealWidth: 520, minHeight: 480)
    }
}

/// Haekchen-Darstellung fuer die Aufgabenvorschlaege (wie Checkliste)
struct CheckboxLikeToggleStyle: ToggleStyle {
    func makeBody(configuration: Configuration) -> some View {
        Button { configuration.isOn.toggle() } label: {
            HStack(alignment: .firstTextBaseline, spacing: 8) { CheckBoxIcon(on: configuration.isOn); configuration.label }
        }.buttonStyle(.plain)
    }
}
extension ToggleStyle where Self == CheckboxLikeToggleStyle { static var checkboxLike: CheckboxLikeToggleStyle { .init() } }
