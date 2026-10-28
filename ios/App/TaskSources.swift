import SwiftUI

// Saga 2.5 Aufgabenquellen (tasksrc1): Abhaken mit Rueckgaengig, Offline-Warteschlange, neue Aufgabe mit Ziel,
// Quellen verwalten, taegliche Zusammenfassung (taskdue1). Logik: Shared/TaskSourceLogic.swift.

/// Abgehakt: Rueckgaengig anbieten (Zeile: value = prev)
struct TaskUndo: Equatable, Identifiable {
    var key: String
    var text: String
    var prev: TaskValue?
    var id: String { key }
}

extension Store {
    private static let queueKey = "hk.taskQueue"
    /// Warteschlange je Server/Konto (nie fuer ein anderes Konto nachholen)
    private var queueScope: String? {
        guard let o = AppConfig.serverOrigin, let a = DraftOwner.app.account(origin: o) else { return nil }
        return o + "\n" + a
    }
    func loadTaskQueue() -> TaskQueue {
        guard let sc = queueScope, let d = UserDefaults.standard.dictionary(forKey: Store.queueKey)?[sc] as? Data,
              let q = try? JSONDecoder().decode(TaskQueue.self, from: d) else { return TaskQueue() }
        return q
    }
    func saveTaskQueue(_ q: TaskQueue) {
        guard let sc = queueScope else { return }
        var all = UserDefaults.standard.dictionary(forKey: Store.queueKey) ?? [:]
        all[sc] = q.isEmpty ? nil : try? JSONEncoder().encode(q)
        UserDefaults.standard.set(all, forKey: Store.queueKey)
    }
    /// Offene Aktionen der Warteschlange auf die geladene Liste anwenden (abgehakt = ausblenden)
    func applyQueue(_ items: [TaskItem]) -> [TaskItem] {
        let q = loadTaskQueue()
        guard !q.isEmpty else { return items }
        return items.filter { it in q.entries[it.key].map { !$0.done } ?? true }
    }

    /// Eintrag abhaken bzw. wieder oeffnen. Online sofort, offline in die Warteschlange (letzte Aktion gewinnt).
    func setTaskDone(_ item: TaskItem, done: Bool, prev: TaskValue? = nil) async {
        if done { taskItems.removeAll { $0.key == item.key } }
        var q = loadTaskQueue()
        let path = item.isRow ? "api/tasks/src/\(item.id)" : "api/tasks/\(item.id)"
        let body: [String: Any] = item.isRow ? TaskSourceLogic.doneBody(done: done, value: prev) : ["done": done]
        if q.entries[item.key] == nil, let (d, code) = try? await apiResponse(path, method: "PATCH", body: body) {
            switch TaskQueue.outcome(code) {
            case .done:
                if done { taskUndo = TaskUndo(key: item.key, text: item.text, prev: (ShareJSON.obj(d)?["prev"]).map(TaskValue.init)) }
            case .drop:
                self.error = code == 403 ? String(localized: "Keine Berechtigung, diese Aufgabe zu ändern.") : nil
            case .keep:
                q.put(key: item.key, done: done, serverDone: !done, prev: prev); saveTaskQueue(q)
            }
            await loadToday()
            return
        }
        // offline (oder schon etwas offen fuer diesen Eintrag): vormerken; Wert vorher aus dem zuletzt Bekannten
        q.put(key: item.key, done: done, serverDone: !done, prev: prev)
        saveTaskQueue(q)
        if done { taskUndo = TaskUndo(key: item.key, text: item.text, prev: q.entries[item.key]?.prev) }
    }
    /// Rueckgaengig: Zeile mit dem Wert vorher wieder oeffnen (nie pauschal open_value)
    func undoTask(_ u: TaskUndo) async {
        taskUndo = nil
        let p = u.key.split(separator: ":")
        guard p.count == 2, let id = Int(p[1]) else { return }
        let it = TaskItem(key: u.key, kind: p[0] == "r" ? "row" : "task", id: id, text: u.text)
        await setTaskDone(it, done: false, prev: u.prev)
    }
    /// Warteschlange nachholen (beim Laden der Liste); danach liest loadToday die Liste neu
    func flushTaskQueue() async {
        var q = loadTaskQueue()
        guard !q.isEmpty else { return }
        for e in q.pending {
            guard let (path, body) = TaskQueue.request(e) else { q.remove(e.key); continue }
            let code = (try? await apiResponse(path, method: "PATCH", body: body))?.1
            switch TaskQueue.outcome(code) {
            case .done, .drop: q.remove(e.key)
            case .keep: saveTaskQueue(q); return                 // offline/Serverfehler: spaeter weiter
            }
        }
        saveTaskQueue(q)
    }

    /// Neue Aufgabe: Inbox (src nil) oder Zeile in einer Quelle
    func addTask(_ text: String, src: Int?) async {
        guard let src else { await addTask(text); return }
        let r = try? await apiResponse("api/tasks", method: "POST", body: ["text": text, "src": src])
        if let r, !(200..<300).contains(r.1) { error = ShareJSON.obj(r.0).flatMap { ShareJSON.str($0["error"]) } ?? String(localized: "Server-Fehler \(r.1)") }
        await loadToday(); dataChanged()
    }

    // MARK: Quellen verwalten
    func loadTaskSources() async -> [TaskSource]? {
        guard let d = try? await api("api/tasks/sources") else { return nil }
        struct R: Decodable { var sources: [TaskSource] }
        return (try? JSONDecoder().decode(R.self, from: d))?.sources
    }
    @discardableResult
    func setTaskSource(_ db: Int, scope: String? = nil, subscribe: Bool? = nil) async -> String? {
        var b: [String: Any] = [:]
        if let scope { b["scope"] = scope }
        if let subscribe { b["subscribe"] = subscribe }
        guard let (d, code) = try? await apiResponse("api/notes/\(db)/tasksource", method: "PUT", body: b) else { return String(localized: "Server nicht erreichbar") }
        await loadToday()
        return (200..<300).contains(code) ? nil : (ShareJSON.obj(d).flatMap { ShareJSON.str($0["error"]) } ?? String(localized: "Server-Fehler \(code)"))
    }
    func removeTaskSource(_ db: Int) async {
        _ = try? await apiResponse("api/notes/\(db)/tasksource", method: "DELETE")
        await loadToday()
    }
}

// MARK: - Zeile der gemeinsamen Liste

struct SourceTaskRow: View {
    let item: TaskItem
    let open: () -> Void
    @EnvironmentObject var store: Store
    private var source: TaskSource? { item.src.flatMap { s in store.taskSources.first { $0.db == s } } }
    var body: some View {
        HStack(spacing: 10) {
            Button { Task { await store.setTaskDone(item, done: true) } } label: {
                Image(systemName: "circle").font(.title3)
                    .foregroundStyle(item.readOnly ? Color.secondary.opacity(0.35) : item.prio == 1 ? .red : .secondary)
            }.buttonStyle(.plain).disabled(item.readOnly)
            .accessibilityLabel("Erledigt").accessibilityValue(item.text)
            Button(action: open) {
                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 6) {
                        Text(item.text).lineLimit(2)
                        Spacer(minLength: 6)
                        if item.repeat != nil { Image(systemName: "repeat").font(.caption).foregroundStyle(.secondary) }
                        if let w = TaskSourceLogic.when(item, day: dayText) {
                            Text(w).font(.caption).foregroundStyle(item.overdue == true ? .red : .secondary).lineLimit(1)
                        }
                        if item.prio == 1 { Image(systemName: "flag.fill").font(.caption).foregroundStyle(.red).accessibilityLabel("Hohe Priorität") }
                    }
                    if item.isRow || !(item.assignees ?? []).isEmpty {
                        HStack(spacing: 6) {
                            if let s = source {
                                Text(verbatim: (s.emoji.map { $0 + " " } ?? "🗂 ") + s.name).font(.caption2).foregroundStyle(.secondary).lineLimit(1)
                            }
                            ForEach(item.assignees ?? [], id: \.self) { r in
                                let p = store.taskPeople[r]
                                AvatarView(name: p?.name ?? "?", avatar: p?.avatar, size: 16)
                                    .accessibilityLabel(Text(verbatim: p?.name ?? String(localized: "Unbekannte Person")))
                            }
                        }
                    }
                }.contentShape(Rectangle())
            }.buttonStyle(.plain)
        }
        .padding(.vertical, 4)
        #if os(iOS)
        .swipeActions(edge: .leading) { if !item.readOnly { Button { Task { await store.setTaskDone(item, done: true) } } label: { Label("Erledigt", systemImage: "checkmark") }.tint(.green) } }
        #endif
        .contextMenu {
            if !item.readOnly { Button { Task { await store.setTaskDone(item, done: true) } } label: { Label("Erledigt", systemImage: "checkmark") } }
            if item.isRow { Button(action: open) { Label("Öffnen", systemImage: "arrow.up.right.square") } }
        }
    }
}

/// "Erledigt – Rückgängig"
struct TaskUndoBar: View {
    let undo: TaskUndo
    @EnvironmentObject var store: Store
    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: "checkmark.circle.fill").foregroundStyle(.green)
            Text("Erledigt: \(undo.text)").font(.caption).lineLimit(1)
            Spacer(minLength: 4)
            Button("Rückgängig") { Task { await store.undoTask(undo) } }.font(.caption.weight(.semibold)).buttonStyle(.borderless)
        }
        .padding(.horizontal, 10).padding(.vertical, 6)
        .background(.secondary.opacity(0.1), in: RoundedRectangle(cornerRadius: 8))
        .task(id: undo.key) { try? await Task.sleep(for: .seconds(8)); if store.taskUndo == undo { store.taskUndo = nil } }
    }
}

/// Ziel fuer "Neue Aufgabe" (Inbox oder Quelle)
struct TaskTargetMenu: View {
    @Binding var target: Int?
    let sources: [TaskSource]
    var body: some View {
        Menu {
            Button { target = nil } label: { Label("Inbox", systemImage: target == nil ? "checkmark" : "tray") }
            ForEach(sources) { s in
                Button { target = s.db } label: { Label { Text(verbatim: (s.emoji.map { $0 + " " } ?? "") + s.name) } icon: { if target == s.db { Image(systemName: "checkmark") } } }
            }
        } label: {
            let s = sources.first { $0.db == target }
            Text(verbatim: s.map { ($0.emoji.map { $0 + " " } ?? "") + $0.name } ?? String(localized: "Inbox"))
                .font(.caption).lineLimit(1).padding(.horizontal, 8).padding(.vertical, 3).background(.secondary.opacity(0.12), in: Capsule())
        }
        .menuStyle(.button).buttonStyle(.plain).menuIndicator(.hidden).fixedSize()
        .accessibilityLabel(Text("Ziel"))
    }
}

// MARK: - Quellen verwalten

struct TaskSourcesSheet: View {
    @EnvironmentObject var store: Store
    @EnvironmentObject var nav: NavModel
    @Environment(\.dismiss) private var dismiss
    @State private var list: [TaskSource]?
    @State private var msg: String?
    var body: some View {
        NavigationStack {
            Form {
                if let list {
                    if list.isEmpty { Text("Noch keine Aufgabenquellen. Eine Datenbank wird im Datenbank-Menü „Als Aufgabenquelle verwenden“ abonniert.").foregroundStyle(.secondary) }
                    ForEach(list) { s in
                        VStack(alignment: .leading, spacing: 6) {
                            HStack {
                                Text(verbatim: (s.emoji.map { $0 + " " } ?? "🗂 ") + s.name)
                                Spacer()
                                if let n = s.count { Text("\(n) offen").font(.caption).foregroundStyle(.secondary) }
                            }
                            if s.inactive == true { Text("Datenbank gelöscht oder archiviert").font(.caption).foregroundStyle(.orange) }
                            else if s.unmapped == true || s.map == nil { Text("Zuordnung fehlt – im Web einrichten").font(.caption).foregroundStyle(.orange) }
                            else if s.no_assignee == true { Text("Ohne Personen-Eigenschaft – nur „Alle“ möglich").font(.caption).foregroundStyle(.secondary) }
                            if s.truncated == true { Text("Nur die ersten 500 offenen Zeilen").font(.caption).foregroundStyle(.secondary) }
                            if s.canAssigned && s.inactive != true {
                                Picker("Zeilen", selection: Binding(get: { s.scope ?? "all" }, set: { v in Task { msg = await store.setTaskSource(s.db, scope: v); await reload() } })) {
                                    Text("Alle offenen").tag("all"); Text("Mir zugewiesene").tag("assigned")
                                }.pickerStyle(.segmented)
                            }
                            HStack {
                                Button { dismiss(); nav.push(PageRoute(id: s.db)) } label: { Label("Öffnen", systemImage: "arrow.up.right.square") }.buttonStyle(.borderless)
                                Spacer()
                                Button(role: .destructive) { Task { await store.removeTaskSource(s.db); await reload() } } label: { Label("Abo entfernen", systemImage: "minus.circle") }.buttonStyle(.borderless)
                            }.font(.caption)
                        }.padding(.vertical, 2)
                    }
                } else { ProgressView() }
                if let msg { Text(msg).font(.footnote).foregroundStyle(.orange) }
            }
            .formStyle(.grouped)
            .navigationTitle("Aufgabenquellen")
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Fertig") { dismiss() } } }
            .task { await reload() }
        }
        .macFrame(minWidth: 420, minHeight: 420)
    }
    private func reload() async { list = await store.loadTaskSources() ?? list ?? [] }
}

// MARK: - Datenbank als Aufgabenquelle abonnieren (DB-Menue)

/// Rueckfrage nach "Als Aufgabenquelle verwenden …" (Seitenmenue einer Datenbank): Abonnieren nur mit Zuordnung (map),
/// sonst Hinweis "im Web einrichten"; schon abonniert -> Abo entfernen anbieten
struct TaskSourceAsk: ViewModifier {
    let db: Int
    @Binding var trigger: Bool
    @EnvironmentObject var store: Store
    @State private var info: [String: Any]?
    @State private var ask = false
    @State private var msg: String?
    private var mapped: Bool { info?["map"] is [String: Any] }
    private var subscribed: Bool { info?["scope"] is String }
    func body(content: Content) -> some View {
        content
            .onChange(of: trigger) { _, v in if v { trigger = false; Task { await check() } } }
            .alert(Text("Aufgabenquelle"), isPresented: $ask) {
                if msg == nil && mapped {
                    if subscribed { Button("Abo entfernen", role: .destructive) { Task { await store.removeTaskSource(db) } } }
                    else { Button("Abonnieren") { Task { if let e = await store.setTaskSource(db, subscribe: true) { msg = e; ask = true } } } }
                    Button("Abbrechen", role: .cancel) {}
                } else { Button("OK", role: .cancel) { msg = nil } }
            } message: {
                if let msg { Text(msg) }
                else if mapped { Text(subscribed ? "Offene Zeilen dieser Datenbank stehen schon in deiner Aufgabenliste." : "Offene Zeilen dieser Datenbank in der Aufgabenliste zeigen?") }
                else { Text("Diese Datenbank ist noch nicht als Aufgabenquelle eingerichtet – bitte im Web einrichten.") }
            }
    }
    private func check() async {
        msg = nil
        guard let d = try? await store.api("api/notes/\(db)/tasksource") else { msg = String(localized: "Server nicht erreichbar"); ask = true; return }
        info = ShareJSON.obj(d); ask = true
    }
}

// MARK: - Taegliche Zusammenfassung (taskdue1)

struct TaskDigestSection: View {
    @EnvironmentObject var store: Store
    @State private var on = false
    @State private var time = Date()
    @State private var loaded = false
    var body: some View {
        if store.features.has("taskdue1") {
            Section {
                Toggle("Tägliche Zusammenfassung", isOn: Binding(get: { on }, set: { v in on = v; Task { await save(["on": v, "tz": TimeZone.current.identifier]) } }))
                    .disabled(!loaded)
                if on {
                    DatePicker("Uhrzeit", selection: Binding(get: { time }, set: { v in time = v; Task { await save(["time": Self.hhmm(v)]) } }), displayedComponents: .hourAndMinute)
                }
            } header: { Text("Aufgaben") } footer: { Text("Einmal am Tag, wenn etwas heute fällig oder überfällig ist – ohne Titel und Namen.") }
            .id("digest")
            .task { await load() }
        }
    }
    static func hhmm(_ d: Date) -> String { let c = Calendar.current.dateComponents([.hour, .minute], from: d); return String(format: "%02d:%02d", c.hour ?? 7, c.minute ?? 30) }
    private func load() async {
        guard let d = try? await store.api("api/tasks/digest"), let j = ShareJSON.obj(d) else { return }
        on = ShareJSON.bool(j["on"])
        let p = (ShareJSON.str(j["time"]) ?? "07:30").split(separator: ":").compactMap { Int($0) }
        if p.count == 2, let t = Calendar.current.date(bySettingHour: p[0], minute: p[1], second: 0, of: .now) { time = t }
        loaded = true
    }
    private func save(_ b: [String: Any]) async { _ = try? await store.api("api/tasks/digest", method: "PUT", body: b) }
}
