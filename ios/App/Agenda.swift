import SwiftUI

private let isoDayF: DateFormatter = DateFormatter.isoDay()
private func dayKey(_ d: Date) -> String { isoDayF.string(from: d) }
let repeatNames: [(String?, String)] = [(nil, String(localized: "Nie")), ("daily", String(localized: "Täglich")), ("weekdays", String(localized: "Werktags")), ("weekly", String(localized: "Wöchentlich")), ("monthly", String(localized: "Monatlich")), ("yearly", String(localized: "Jährlich"))]

// MARK: - Aufgaben

/// Aufgabenliste wie im Dashboard: Schnell-Eingabe, Gruppen nach Faelligkeit, abhaken, antippen = bearbeiten
struct TaskListView: View {
    var compact = false
    @EnvironmentObject var store: Store
    @State private var newText = ""
    @State private var editing: OpenTask?
    @State private var showDone = false
    @EnvironmentObject private var nav: NavModel
    @FocusState private var adding: Bool

    private var groups: [(String, [OpenTask])] {
        let today = dayKey(.now), tomorrow = dayKey(Calendar.current.date(byAdding: .day, value: 1, to: .now)!)
        let all = store.tasks
        return [(String(localized: "Überfällig"), all.filter { ($0.due ?? "").isEmpty == false && ($0.due ?? "") < today }), (String(localized: "Heute"), all.filter { $0.due == today }),
                (String(localized: "Morgen"), all.filter { $0.due == tomorrow }), (String(localized: "Später"), all.filter { ($0.due ?? "") > tomorrow }),
                (String(localized: "Ohne Datum"), all.filter { $0.due == nil || $0.due == "" })].filter { !$0.1.isEmpty }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 8) {
                Image(systemName: "plus").foregroundStyle(.secondary)
                TextField("Neue Aufgabe …", text: $newText).textFieldStyle(.plain).focused($adding)
                    .onSubmit { let t = newText.trimmingCharacters(in: .whitespaces); newText = ""; if !t.isEmpty { Task { await store.addTask(t) } } }
                    .task(id: nav.wantQuickAdd) {                        // Widget "+ Aufgabe": Eingabe sofort bereit
                        guard nav.wantQuickAdd, !compact else { return }
                        nav.wantQuickAdd = false
                        try? await Task.sleep(for: .milliseconds(350)); adding = true
                    }
            }
            .padding(.vertical, 6)
            if store.tasks.isEmpty { Text("Alles erledigt 🎉").foregroundStyle(.secondary).padding(.vertical, 4) }
            ForEach(groups, id: \.0) { g in
                if !compact || groups.count > 1 { Text(g.0).font(.caption.weight(.semibold)).foregroundStyle(g.0 == String(localized: "Überfällig") ? .red : .secondary).padding(.top, 6) }
                ForEach(g.1) { t in TaskRow(task: t) { editing = t } }
            }
            if !store.doneTasks.isEmpty {
                Button { withAnimation { showDone.toggle() } } label: {
                    Label("\(store.doneTasks.count) erledigt", systemImage: showDone ? "chevron.down" : "chevron.right").font(.caption)
                }.buttonStyle(.plain).foregroundStyle(.secondary).padding(.top, 6)
                if showDone { ForEach(store.doneTasks.prefix(compact ? 5 : 50)) { t in TaskRow(task: t) { editing = t } } }
            }
        }
        .sheet(item: $editing) { t in TaskEditor(task: t) }
    }
}

struct TaskRow: View {
    let task: OpenTask
    let open: () -> Void
    @EnvironmentObject var store: Store
    var body: some View {
        let done = task.done == true
        HStack(spacing: 10) {
            Button { Task { if done { await store.updateTask(task.id, ["done": false]) } else { await store.completeTask(task) } } } label: {
                Image(systemName: done ? "checkmark.circle.fill" : "circle").font(.title3).foregroundStyle(done ? Color.accentColor : task.prio == 1 ? .red : .secondary)
            }.buttonStyle(.plain)
            .accessibilityLabel(done ? "Als offen markieren" : "Erledigt")
            .accessibilityValue(task.text)
            Button(action: open) {
                HStack(spacing: 6) {
                    Text(task.text).strikethrough(done).foregroundStyle(done ? .secondary : .primary).lineLimit(2)
                    Spacer(minLength: 6)
                    if task.rep != nil { Image(systemName: "repeat").font(.caption).foregroundStyle(.secondary) }
                    if let d = task.due, !d.isEmpty, !done {
                        Text(dayText(d)).font(.caption).foregroundStyle(d < dayKey(.now) ? .red : .secondary)
                    }
                    if task.prio == 1 { Image(systemName: "flag.fill").font(.caption).foregroundStyle(.red).accessibilityLabel("Hohe Priorität") }
                }.contentShape(Rectangle())
            }.buttonStyle(.plain)
        }
        .padding(.vertical, 4)
        #if os(iOS)
        .swipeActions(edge: .leading) { Button { Task { await store.completeTask(task) } } label: { Label("Erledigt", systemImage: "checkmark") }.tint(.green) }
        .swipeActions(edge: .trailing) { Button(role: .destructive) { Task { await store.deleteTask(task.id) } } label: { Label("Löschen", systemImage: "trash") } }
        #endif
        .contextMenu {
            Button { Task { await store.completeTask(task) } } label: { Label("Erledigt", systemImage: "checkmark") }
            Button(role: .destructive) { Task { await store.deleteTask(task.id) } } label: { Label("Löschen", systemImage: "trash") }
        }
    }
}

struct TaskEditor: View {
    let task: OpenTask
    @EnvironmentObject var store: Store
    @Environment(\.dismiss) private var dismiss
    @State private var text = ""
    @State private var hasDue = false
    @State private var due = Date()
    @State private var prio = 2
    @State private var rep: String?
    var body: some View {
        NavigationStack {
            Form {
                TextField("Aufgabe", text: $text, axis: .vertical)
                Toggle("Fällig", isOn: $hasDue)
                if hasDue { DatePicker("Datum", selection: $due, displayedComponents: .date) }
                Picker("Priorität", selection: $prio) { Text("Hoch").tag(1); Text("Normal").tag(2); Text("Niedrig").tag(3) }.pickerStyle(.segmented)
                Picker("Wiederholen", selection: $rep) { ForEach(repeatNames, id: \.1) { Text($0.1).tag($0.0) } }
                Section { Button("Aufgabe löschen", role: .destructive) { Task { await store.deleteTask(task.id); dismiss() } } }
            }
            .formStyle(.grouped)
            .navigationTitle("Aufgabe")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Abbrechen") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button("Sichern") { Task { await save(); dismiss() } }.disabled(text.trimmingCharacters(in: .whitespaces).isEmpty) }
            }
            .onAppear {
                text = task.text; prio = task.prio ?? 2; rep = task.rep
                if let d = task.due, let date = isoDayF.date(from: d) { hasDue = true; due = date }
            }
        }
        .macFrame(minWidth: 380, minHeight: 360)
    }
    private func save() async {
        await store.updateTask(task.id, ["text": text.trimmingCharacters(in: .whitespaces), "due": hasDue ? dayKey(due) : NSNull(),
                                         "prio": prio, "repeat": rep ?? NSNull(), "today": dayKey(.now)])
    }
}

struct TasksScreen: View {
    @EnvironmentObject var store: Store
    var body: some View {
        ScrollView { TaskListView().padding(.horizontal, 18).padding(.vertical, 10).frame(maxWidth: 760).frame(maxWidth: .infinity) }
            .navigationTitle("Aufgaben")
            .refreshable { await store.loadToday() }
            .task { await store.loadToday() }
    }
}

// MARK: - Termine

struct EventRow: View {
    let e: CalEvent
    var showDay = false
    private static let iso = ISO8601DateFormatter()
    var body: some View {
        HStack(spacing: 10) {
            RoundedRectangle(cornerRadius: 2).fill(Color(hex: e.color) ?? .blue).frame(width: 4, height: 30)
            VStack(alignment: .leading, spacing: 1) {
                HStack(spacing: 4) { Text(e.summary.isEmpty ? String(localized: "(ohne Titel)") : e.summary).lineLimit(1); if e.series == true { Image(systemName: "repeat").font(.caption2).foregroundStyle(.secondary) } }
                Text(when).font(.caption).foregroundStyle(.secondary).lineLimit(1)
            }
            Spacer(minLength: 0)
        }.contentShape(Rectangle())
    }
    private var when: String {
        let day = SagaDate.dateTime().weekday(.abbreviated).day().month(.abbreviated)      // Format der App-Sprache, gregorianisch
        var s: String
        if e.allday { s = (showDay ? (isoDayF.date(from: e.start).map { $0.formatted(day) + " · " } ?? "") : "") + String(localized: "ganztägig") }
        else { s = Self.iso.date(from: e.start).map { showDay ? $0.formatted(day.hour().minute()) : $0.sagaFormatted(date: .omitted, time: .shortened) } ?? e.start }
        if let l = e.location, !l.isEmpty { s += " · " + l }
        return s
    }
}

/// Termin anlegen/bearbeiten (Serien und nur-lesbare Kalender: nur ansehen - wie im Web)
struct EventEditor: View {
    let event: CalEvent?
    var day: Date = .now
    @EnvironmentObject var store: Store
    @Environment(\.dismiss) private var dismiss
    @State private var title = ""
    @State private var allday = false
    @State private var start = Date()
    @State private var end = Date()
    @State private var target: CalTarget?
    @State private var targets: [CalTarget] = []
    @State private var location = ""
    @State private var desc = ""
    @State private var error = ""
    private var readOnly: Bool { event?.series == true || event?.writable == false }
    var body: some View {
        NavigationStack {
            Form {
                TextField("Titel", text: $title)
                Toggle("Ganztägig", isOn: $allday)
                DatePicker("Beginn", selection: $start, displayedComponents: allday ? .date : [.date, .hourAndMinute])
                    .onChange(of: start) { o, n in if end <= n { end = allday ? n : n.addingTimeInterval(end.timeIntervalSince(o) > 0 ? end.timeIntervalSince(o) : 3600) } }
                DatePicker("Ende", selection: $end, in: start..., displayedComponents: allday ? .date : [.date, .hourAndMinute])
                if event == nil {
                    Picker("Kalender", selection: $target) { ForEach(targets) { Text($0.name).tag(Optional($0)) } }
                } else { LabeledContent("Kalender", value: event?.calendar ?? "") }
                TextField("Ort", text: $location)
                TextField("Beschreibung", text: $desc, axis: .vertical).lineLimit(3...8)
                if readOnly { Text(event?.series == true ? "🔁 Serientermin – bitte direkt in mailcow/Nextcloud bearbeiten, damit die Serie erhalten bleibt." : "Dieser Kalender ist nur lesbar.").font(.footnote).foregroundStyle(.secondary) }
                if !error.isEmpty { Text(error).foregroundStyle(.red).font(.footnote) }
                if let event, !readOnly { Section { Button("Termin löschen", role: .destructive) { Task { do { try await store.deleteEvent(event); dismiss() } catch { self.error = error.localizedDescription } } } } }
            }
            .formStyle(.grouped)
            .disabled(readOnly)
            .navigationTitle(event == nil ? "Neuer Termin" : "Termin")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(readOnly ? "Schließen" : "Abbrechen") { dismiss() } }
                if !readOnly { ToolbarItem(placement: .confirmationAction) { Button(event == nil ? "Anlegen" : "Sichern") { Task { await save() } }.disabled(event == nil && target == nil) } }
            }
            .task { await setup() }
        }
        .macFrame(minWidth: 400, minHeight: 460)
    }
    private func setup() async {
        if let e = event {
            title = e.summary; allday = e.allday; location = e.location ?? ""; desc = e.description ?? ""
            if e.allday { start = isoDayF.date(from: e.start) ?? .now; end = Calendar.current.date(byAdding: .day, value: -1, to: isoDayF.date(from: e.end) ?? start) ?? start }
            else { let iso = ISO8601DateFormatter(); start = iso.date(from: e.start) ?? .now; end = iso.date(from: e.end) ?? start.addingTimeInterval(3600) }
        } else {
            let c = Calendar.current, h = c.component(.hour, from: .now) + 1
            start = c.date(bySettingHour: min(h, 22), minute: 0, second: 0, of: day) ?? day; end = start.addingTimeInterval(3600)
            targets = await store.calTargets(); target = targets.first
            if targets.isEmpty { error = String(localized: "Kein beschreibbarer Kalender verbunden (Einstellungen → Kalender im Web).") }
        }
    }
    private func save() async {
        var b: [String: Any] = ["summary": title.isEmpty ? String(localized: "Termin") : title, "allday": allday, "location": location, "description": desc]
        if allday { b["start"] = dayKey(start); b["end"] = dayKey(Calendar.current.date(byAdding: .day, value: 1, to: max(end, start))!) }
        else { let iso = ISO8601DateFormatter(); b["start"] = iso.string(from: start); b["end"] = iso.string(from: max(end, start.addingTimeInterval(60))) }
        if let e = event { b["account"] = e.account ?? 0; b["cal"] = e.cal ?? ""; b["href"] = e.href ?? ""; b["etag"] = e.etag ?? ""; b["uid"] = e.uid ?? "" }
        else if let t = target { b["account"] = t.account; b["cal"] = t.cal }
        do { try await store.saveEvent(b, edit: event != nil); dismiss() } catch { self.error = error.localizedDescription }
    }
}

/// Termine der naechsten Tage (Bereich in der Seitenleiste und Kalender-Block)
struct EventList: View {
    var days = 14
    var compact = false
    @EnvironmentObject var store: Store
    @State private var events: [CalEvent] = []
    @State private var editing: CalEvent?
    @State private var creating = false
    @State private var loaded = false
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                if compact { Label("Termine (\(days) Tage)", systemImage: "calendar").font(.headline) }
                Spacer()
                Button { creating = true } label: { Label("Termin", systemImage: "plus") }.font(.subheadline)
            }
            if loaded && events.isEmpty { Text("Keine Termine").foregroundStyle(.secondary) }
            ForEach(events) { e in Button { editing = e } label: { EventRow(e: e, showDay: true) }.buttonStyle(.plain).padding(.vertical, 2) }
        }
        .task { await reload() }
        .onChange(of: store.eventsTick) { _, _ in Task { await reload(fresh: true) } }
        .sheet(item: $editing, onDismiss: { Task { await reload() } }) { e in EventEditor(event: e) }
        .sheet(isPresented: $creating, onDismiss: { Task { await reload() } }) { EventEditor(event: nil) }
    }
    private func reload(fresh: Bool = false) async {
        if !loaded, let c = store.cachedEvents(days: days) { events = c; loaded = true }    // sofort aus dem Zwischenspeicher
        if let e = await store.events(days: days, fresh: fresh) { events = e }              // Fehler: Stand behalten
        loaded = true
    }
}

struct EventsScreen: View {
    @EnvironmentObject var store: Store
    var body: some View {
        ScrollView { EventList(days: 31).padding(.horizontal, 18).padding(.vertical, 10).frame(maxWidth: 760).frame(maxWidth: .infinity) }
            .navigationTitle("Termine")
            .refreshable { store.refreshEvents() }
    }
}
