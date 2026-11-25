import SwiftUI

// 2.6 (Bugs-DB 2029-6): eigener Status in der App - Verfuegbar / Beschaeftigt / Nicht stoeren (Vertrag docs/APP-API.md
// "Eigener Status"). Stand + Live (status.changed, status.digest, chat.status), Blatt "Mein Status", Einstellungen
// "Erreichbarkeit" (prefs + Ausnahmen), Status von Chat-Partnern (Abzeichen, Chip im Chat-Kopf). Logik/Tests:
// UserStatusLogic.swift. Nur mit Server-Feature userstatus1 - sonst ist nichts davon zu sehen.

@MainActor
final class UserStatusModel: ObservableObject {
    typealias L = UserStatusLogic
    weak var store: Store?
    @Published private(set) var view: L.Status?
    /// Status der Chat-Partner (Benutzername bzw. Prinzipal -> Status) aus dem Online-Abo und chat.status
    @Published private(set) var peers: [String: L.Peer] = [:]
    /// Ende der Ruhezeit "HH:MM" (fuer "Ruhezeit bis 7:00")
    @Published private(set) var quietTo: String?
    /// Blatt "Mein Status" offen (Einstellungen, Kontoknopf)
    @Published var sheet = false
    private var liveAt: [String: Double] = [:]
    private var plan: Task<Void, Never>?
    private var callLoad: Task<Void, Never>?
    private var quietAt = 0.0
    /// Abstand zur Server-Uhr (Ablauf, "bis ...")
    private(set) var skew = 0.0

    var on: Bool { store?.features.has(L.feature) == true }
    var now: Double { Date().timeIntervalSince1970 + skew }

    // MARK: Laden, uebernehmen

    func load() async {
        guard on, let store else { if view != nil { view = nil }; if !peers.isEmpty { peers = [:] }; return }
        await sendPendingFocus()
        guard let (d, code) = try? await store.apiResponse("api/account/status"), code == 200 else { schedule(); return }
        take(L.view(try? JSONSerialization.jsonObject(with: d)))
    }
    @discardableResult func take(_ v: L.Status?) -> Bool {
        guard let v, L.newer(v, than: view) else { return false }
        if let n = v.now { skew = n - Date().timeIntervalSince1970 }
        if v != view { view = v }
        if v.quiet && Date().timeIntervalSince1970 - quietAt > 300 { Task { await loadQuiet() } }
        schedule()
        return true
    }
    /// Ablauf, Ruhezeit und Anruf aendern effective ohne Ereignis an uns - nachsehen
    private func schedule() {
        plan?.cancel()
        let s = L.nextCheck(view, now: now)
        plan = Task { [weak self] in
            try? await Task.sleep(for: .seconds(s))
            guard !Task.isCancelled else { return }
            await self?.load()
        }
    }
    private func loadQuiet() async {
        quietAt = Date().timeIntervalSince1970
        guard let store, let d = try? await store.api("api/push") else { quietTo = nil; return }
        quietTo = L.quietTo(try? JSONSerialization.jsonObject(with: d))
    }

    // MARK: Setzen

    enum Outcome: Equatable { case saved, conflict }
    /// PUT mit ver; 409 = anderswo geaendert: Ansicht still uebernehmen (.conflict)
    private func put(_ path: String, _ body: [String: Any]) async throws -> Outcome {
        guard let store else { throw APIError.other("–") }
        let (d, code) = try await store.apiResponse(path, method: "PUT", body: body)
        switch L.putResult(status: code, data: d) {
        case .ok(let v): take(v); return .saved
        case .conflict(let v): take(v); return .conflict
        case .failed(let m): throw APIError.http(code, m ?? "")
        }
    }
    func set(state: String, duration: L.Duration, text: String) async throws -> Outcome {
        try await put("api/account/status", L.setBody(state: state, duration: duration, text: text, ver: view?.ver, now: Date(), calendar: SagaDate.calendar,
                                                      max: view?.limits.text ?? 80))
    }
    func prefs(_ body: [String: Any]) async throws { _ = try await put("api/account/status/prefs", body) }

    // MARK: Fokus (StatusFocusFilter)

    nonisolated static let pendingKey = "hk.status.focusPending"
    /// Fokus an/aus: NUR {state, source: "focus"} (RT-29). Ohne Sitzung: vormerken, beim naechsten Laden senden
    func focus(_ state: String) async -> Bool {
        guard on, let store else { UserDefaults.standard.set(state, forKey: Self.pendingKey); return false }
        guard let (d, code) = try? await store.apiResponse("api/account/status", method: "PUT", body: L.focusBody(state: state)),
              (200..<300).contains(code) || code == 409 else { UserDefaults.standard.set(state, forKey: Self.pendingKey); return false }
        UserDefaults.standard.removeObject(forKey: Self.pendingKey)
        take(L.view(try? JSONSerialization.jsonObject(with: d)))
        return true
    }
    private func sendPendingFocus() async {
        guard let s = UserDefaults.standard.string(forKey: Self.pendingKey) else { return }
        UserDefaults.standard.removeObject(forKey: Self.pendingKey)
        _ = await focus(s)
    }

    // MARK: Live

    func live(_ type: String, _ d: [String: Any]) {
        switch type {
        case "status.changed": take(L.view(d))
        case "status.digest":
            if let g = L.digest(d) { store?.calls.toast = UserStatusText.digest(g) }
        case "chat.status":
            var p = peers
            if L.applyLive(d, to: &p) { peers = p }
            if let u = d["username"] as? String { liveAt[u] = Date().timeIntervalSince1970 }
        default: break
        }
    }
    /// "Im Anruf" kommt ohne Ereignis - nach einem Anruf-Wechsel kurz nachsehen
    func callChanged() {
        guard view != nil else { return }
        callLoad?.cancel()
        callLoad = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(700))
            guard !Task.isCancelled else { return }
            await self?.load()
        }
    }
    /// Antwort des Online-Abos (Feld status); sent = Zeitpunkt der Anfrage
    func presence(_ status: Any?, sent: Double) {
        guard on else { return }
        let next = L.merge(answer: L.peers(status), old: peers, liveAt: &liveAt, sent: sent)
        if next != peers { peers = next }
    }
    /// Status des Gegenuebers eines Direkt-Chats (nil: nichts zeigen - unbekannt oder verfuegbar)
    func peerOf(_ c: ChatSummary?) -> L.Peer? {
        guard on, let c, c.kind == "dm", let u = c.peer?.username else { return nil }
        return L.visible(peers[u])
    }
    func clear() { view = nil; peers = [:]; liveAt = [:]; plan?.cancel() }
}

// MARK: - Texte

enum UserStatusText {
    static func name(_ s: String) -> String {
        switch s {
        case "busy": return String(localized: "Beschäftigt")
        case "dnd": return String(localized: "Nicht stören")
        case "in_call": return String(localized: "Im Anruf")
        default: return String(localized: "Verfügbar")
        }
    }
    /// Ende als kurzer Text: heute "14:30", morgen "morgen 7:00", diese Woche "Fr. 14:30", sonst "24.12., 14:30"
    static func when(_ ts: Double, now: Date = Date()) -> String {
        let cal = SagaDate.calendar, d = Date(timeIntervalSince1970: ts)
        let days = cal.dateComponents([.day], from: cal.startOfDay(for: now), to: cal.startOfDay(for: d)).day ?? 0
        let time = d.sagaFormatted(date: .omitted, time: .shortened)
        if days <= 0 { return time }
        if days == 1 { return String(localized: "morgen \(time)") }
        if days < 7 { return d.formatted(SagaDate.dateTime().weekday(.abbreviated)) + " " + time }
        return d.formatted(SagaDate.dateTime().day().month(.defaultDigits)) + ", " + time
    }
    static func until(_ ts: Double?) -> String? { ts.map { String(localized: "bis \(when($0))") } }
    /// Eigener Status: "Jetzt: Nicht stören · bis 14:30"
    static func hint(_ s: UserStatusLogic.Shown, quietTo: String?) -> String? {
        var parts: [String] = []
        switch s.hint {
        case .none: break
        case .quiet:
            if let q = quietTo { parts.append(String(localized: "Ruhezeit bis \(q) – zählt als Nicht stören")) }
            else { parts.append(String(localized: "Ruhezeit – zählt als Nicht stören")) }
        case .under(let st): parts.append(String(localized: "darunter: \(name(st))"))
        case .until(let u): parts.append(until(u) ?? "")
        }
        if s.focus { parts.append(String(localized: "vom Gerät (Fokus)")) }
        let t = parts.filter { !$0.isEmpty }.joined(separator: " · ")
        return t.isEmpty ? nil : t
    }
    /// Status anderer: "Nicht stören bis 14:30 · Im Kundentermin"
    static func peer(_ p: UserStatusLogic.Peer, withText: Bool) -> String {
        var s = name(p.s)
        if p.s != "in_call", let u = until(p.until) { s += " " + u }
        if withText, let t = p.text { s += " · " + t }
        return s
    }
    /// Sammelmeldung - nur Zahlen, nie Namen
    static func digest(_ g: UserStatusLogic.Digest) -> String {
        var parts: [String] = []
        if g.messages > 0 { parts.append(String(localized: "\(g.messages) Nachrichten")) }
        if g.calls > 0 { parts.append(String(localized: "\(g.calls) verpasste Anrufe")) }
        if g.other > 0 { parts.append(String(localized: "\(g.other) weitere Mitteilungen")) }
        return String(localized: "Während „Nicht stören“: \(ListFormatter.localizedString(byJoining: parts))")
    }
}

// MARK: - Punkt

/// Punkt je Zustand (Form zusaetzlich zur Farbe: Nicht stoeren mit Balken, Im Anruf mit Hoerer)
struct StatusDot: View {
    let state: String
    var size: CGFloat = 12
    var ring = true
    /// Name fuer Bildschirmleser (nil: nur Schmuck, ausgeblendet)
    var label: String? = nil
    var body: some View {
        if let label { dot.accessibilityElement().accessibilityLabel(Text(verbatim: label)) } else { dot.accessibilityHidden(true) }
    }
    private var dot: some View {
        ZStack {
            Circle().fill(Self.color(state))
            if state == "dnd" { Capsule().fill(.white).frame(width: size * 0.55, height: max(1.5, size * 0.16)) }
            if state == "in_call" { Image(systemName: "phone.fill").font(.system(size: size * 0.5, weight: .bold)).foregroundStyle(.white) }
        }
        .frame(width: size, height: size)
        .overlay { if ring { Circle().strokeBorder(Color.adaptive(0xFFFFFF, 0x1C1C1E), lineWidth: 2).padding(-1) } }
    }
    /// Kontrast >= 3:1 zum Grund (hell/dunkel)
    static func color(_ s: String) -> Color {
        switch s {
        case "busy": return .adaptive(0xB45309, 0xF5A524)
        case "dnd": return .adaptive(0xC62828, 0xFF6B6B)
        case "in_call": return .adaptive(0x6D28D9, 0xB39DFA)
        default: return .adaptive(0x2DA44E, 0x3FD06A)
        }
    }
}

/// Punkt am eigenen Kontoknopf (nur, wenn nicht verfuegbar)
struct MyStatusDot: View {
    @ObservedObject var model: UserStatusModel
    var body: some View {
        if let v = model.view, UserStatusLogic.shown(v).state != "available" {
            StatusDot(state: UserStatusLogic.shown(v).state, size: 11)
        }
    }
}

/// Kontoknopf: Status fuer Bildschirmleser ("Einstellungen", Wert "Status: Nicht stoeren, bis 14:30")
struct MyStatusA11y: ViewModifier {
    @ObservedObject var model: UserStatusModel
    func body(content: Content) -> some View {
        if let v = model.view, UserStatusLogic.shown(v).state != "available" {
            let s = UserStatusLogic.shown(v)
            content.accessibilityValue(Text(verbatim: String(localized: "Status: \(UserStatusText.name(s.state))") + (UserStatusText.hint(s, quietTo: model.quietTo).map { ", " + $0 } ?? "")))
        } else { content }
    }
}

// MARK: - Zeile in den Einstellungen

/// "Status: Nicht stören · bis 14:30 – ändern" (oeffnet das Blatt)
struct MyStatusRow: View {
    @ObservedObject var model: UserStatusModel
    /// Blatt oeffnen (Einstellungen: eigenes Blatt ueber dem Einstellungs-Blatt)
    var open: (() -> Void)? = nil
    var body: some View {
        if let v = model.view {
            let s = UserStatusLogic.shown(v), hint = UserStatusText.hint(s, quietTo: model.quietTo)
            Button { if let open { open() } else { model.sheet = true } } label: {
                HStack(spacing: 12) {
                    StatusDot(state: s.state, size: 14, ring: false)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(verbatim: UserStatusText.name(s.state)).foregroundStyle(.primary)
                        if let hint { Text(verbatim: hint).font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true) }
                        if let t = v.text, v.source == "manual", v.state != "available" { Text(verbatim: t).font(.caption).foregroundStyle(.secondary).lineLimit(2) }
                    }
                    Spacer(minLength: 4)
                    Image(systemName: "chevron.right").font(.caption.weight(.semibold)).foregroundStyle(.tertiary).accessibilityHidden(true)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(Text(verbatim: String(localized: "Status: \(UserStatusText.name(s.state))") + (hint.map { ", " + $0 } ?? "")))
            .accessibilityHint(Text("ändern"))
        }
    }
}

/// Abschnitt "Mein Status" (Startliste der Einstellungen)
struct MyStatusSection: View {
    @ObservedObject var model: UserStatusModel
    var open: () -> Void
    var body: some View {
        if model.view != nil { Section { MyStatusRow(model: model, open: open) } header: { Text("Mein Status") } }
    }
}

// MARK: - Blatt "Mein Status"

struct StatusSheet: View {
    @ObservedObject var model: UserStatusModel
    var openReach: (() -> Void)? = nil
    @Environment(\.dismiss) private var dismiss
    @State private var state = "available"
    @State private var duration: UserStatusLogic.Duration = .off
    @State private var text = ""
    @State private var busy = false
    @State private var err: String?
    @State private var loadedVer = -1
    @FocusState private var textFocus: Bool

    var body: some View {
        NavigationStack {
            Form {
                if let v = model.view {
                    let s = UserStatusLogic.shown(v)
                    Section {
                        Text(verbatim: String(localized: "Jetzt: \(UserStatusText.name(s.state))") + (UserStatusText.hint(s, quietTo: model.quietTo).map { " · " + $0 } ?? ""))
                            .font(.subheadline).foregroundStyle(.secondary)
                    }
                    Section("Status") {
                        Picker(selection: $state) {
                            ForEach(UserStatusLogic.states, id: \.self) { st in
                                Label { Text(verbatim: UserStatusText.name(st)) } icon: { StatusDot(state: st, size: 14, ring: false) }.tag(st)
                            }
                        } label: { EmptyView() }
                        .pickerStyle(.inline).labelsHidden()
                    }
                    Section("Dauer") {
                        Picker(selection: $duration) {
                            ForEach(durations(v), id: \.self) { d in Text(verbatim: label(d)).tag(d) }
                        } label: { EmptyView() }
                        .pickerStyle(.inline).labelsHidden()
                        .disabled(state == "available")
                    }
                    Section {
                        TextField("z. B. „Im Kundentermin“", text: $text)
                            .focused($textFocus).submitLabel(.done).onSubmit { save() }
                            .onChange(of: text) { _, t in if t.count > v.limits.text { text = String(t.prefix(v.limits.text)) } }
                            .accessibilityLabel(Text("Eigener Text (optional)"))
                    } header: { Text("Eigener Text (optional)") } footer: {
                        Text("\(text.count) von \(v.limits.text) Zeichen").foregroundStyle(text.count >= v.limits.text ? Color.orange : Color.secondary)
                    }
                    if let err { Section { Text(verbatim: err).foregroundStyle(.red).font(.footnote) } }
                    if let openReach {
                        Section { Button("Erreichbarkeit …") { dismiss(); openReach() } }
                    }
                } else {
                    ProgressView()
                }
            }
            .navigationTitle("Mein Status")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Abbrechen") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button("Speichern") { save() }.disabled(busy || model.view == nil) }
            }
            .disabled(busy)
            .task { await model.load(); fill() }
            .onChange(of: model.view?.ver) { _, _ in if loadedVer < 0 { fill() } }
        }
        #if os(macOS)
        .frame(minWidth: 380, minHeight: 520)
        #endif
    }
    private func durations(_ v: UserStatusLogic.Status) -> [UserStatusLogic.Duration] { UserStatusLogic.durations(v, now: model.now) }
    private func label(_ d: UserStatusLogic.Duration) -> String {
        switch d {
        case .hour: return String(localized: "1 Stunde")
        case .morning: return UserStatusLogic.morning(now: Date(), calendar: SagaDate.calendar).today ? String(localized: "Bis 7:00") : String(localized: "Bis morgen 7:00")
        case .off: return String(localized: "Bis ich es ausschalte")
        case .keep(let u): return String(localized: "Bis \(UserStatusText.when(u)) (wie bisher)")
        }
    }
    /// Formular aus dem aktuellen Stand (beim Oeffnen und nach 409)
    private func fill() {
        guard let v = model.view else { return }
        loadedVer = v.ver
        state = v.state
        duration = UserStatusLogic.defaultDuration(v, now: model.now)
        text = v.source == "manual" ? (v.text ?? "") : ""
    }
    private func save() {
        busy = true; err = nil
        Task {
            defer { busy = false }
            do {
                switch try await model.set(state: state, duration: state == "available" ? .off : duration, text: text) {
                case .saved: dismiss()
                case .conflict: fill()                                   // anderswo geaendert: still neu zeigen
                }
            } catch { err = error.localizedDescription }
        }
    }
}

// MARK: - Einstellungen "Erreichbarkeit"

struct ReachabilitySection: View {
    @ObservedObject var model: UserStatusModel
    @State private var status: String?
    @State private var failed = false
    @State private var addOpen = false
    @State private var repeatS = 180
    @State private var statusOpen = false
    var body: some View {
        if let v = model.view {
            let P = v.prefs
            Section {
                Text("Wie dich andere erreichen, während du „Beschäftigt“ oder „Nicht stören“ eingestellt hast, in Ruhezeiten und während du telefonierst.")
                    .font(.footnote).foregroundStyle(.secondary)
                MyStatusRow(model: model, open: { statusOpen = true })
                    .sheet(isPresented: $statusOpen) { StatusSheet(model: model) }
                toggle("Ruhezeiten zählen als „Nicht stören“", String(localized: "In den Ruhezeiten der Mitteilungen gilt dasselbe wie bei „Nicht stören“: Ausnahmen und wiederholte Anrufe kommen durch."),
                       P.quietDnd) { ["quiet_dnd": $0] }
                toggle("Sammelmeldung auch nach Ruhezeiten", String(localized: "Nach „Nicht stören“ kommt eine Meldung mit der Zahl verpasster Nachrichten und Anrufe – mit diesem Schalter auch am Ende einer Ruhezeit."),
                       P.quietDigest) { ["quiet_digest": $0] }
                toggle("Erwähnungen kommen durch", String(localized: "Wer dich in einem Chat oder auf einer Seite erwähnt, erreicht dich auch bei „Nicht stören“."),
                       P.mentionBreak) { ["mention_break": $0] }
            } header: { Text("Erreichbarkeit") }
            .id("erreichbarkeit")
            Section {
                picker("Anrufe, während du telefonierst", "Anklopfen: ein Hinweis im laufenden Gespräch, ohne Klingeln.", P.inCall, "in_call",
                       [("knock", String(localized: "Anklopfen lassen")), ("busy", String(localized: "Besetzt melden"))])
                toggle("Andere sehen „Im Anruf“", String(localized: "Nur Chat-Partner, und nur, wenn ihr beide den Online-Status zeigt."), P.showCall) { ["show_call": $0] }
                picker("Bei „Beschäftigt“", "„Beschäftigt“ zeigt anderen, dass du gerade wenig Zeit hast.", P.busy, "busy",
                       [("show", String(localized: "Mitteilungen wie sonst")), ("silent", String(localized: "Mitteilungen ohne Ton"))])
                toggle("Wiederholte Anrufe kommen durch", String(localized: "Ruft dieselbe Person innerhalb von \(P.repeatS) Sekunden noch einmal an, klingelt es trotz „Nicht stören“."),
                       P.repeatOn) { ["repeat": $0] }
                Stepper(value: $repeatS, in: v.limits.repeatMin...v.limits.repeatMax, step: 30) {
                    LabeledContent(String(localized: "Zeitraum in Sekunden (\(v.limits.repeatMin)–\(v.limits.repeatMax))")) { Text(verbatim: "\(repeatS)").monospacedDigit() }
                } onEditingChanged: { editing in
                    if !editing, repeatS != P.repeatS { save(["repeat_s": repeatS]) { repeatS = model.view?.prefs.repeatS ?? 180 } }
                }
                .disabled(!P.repeatOn)
                .accessibilityLabel(Text("Zeitraum für wiederholte Anrufe in Sekunden"))
                .onAppear { repeatS = P.repeatS }
                .onChange(of: P.repeatS) { _, n in repeatS = n }
            }
            Section {
                if v.exceptions.isEmpty { Text("Noch keine Ausnahmen.").foregroundStyle(.secondary) }
                ForEach(v.exceptions) { x in
                    HStack {
                        Image(systemName: x.type == "group" ? "person.2" : "person").foregroundStyle(.secondary).accessibilityHidden(true)
                        Text(verbatim: x.shown)
                        if x.type == "group" { Text("Gruppe").font(.caption).foregroundStyle(.secondary) }
                        Spacer()
                        Button(role: .destructive) { remove(x) } label: { Image(systemName: "minus.circle.fill").foregroundStyle(.red) }
                            .buttonStyle(.borderless)
                            .accessibilityLabel(Text("„\(x.shown)“ entfernen"))
                    }
                }
                Button { addOpen = true } label: { Label("Ausnahme hinzufügen …", systemImage: "plus") }
                    .disabled(v.exceptions.count >= v.limits.exceptions)
                    .sheet(isPresented: $addOpen) { ExceptionPicker(model: model) }
                if let status { Text(verbatim: status).font(.footnote).foregroundStyle(failed ? Color.red : Color.secondary) }
            } header: { Text("Ausnahmen") } footer: {
                Text("Diese Personen und Gruppen erreichen dich auch bei „Nicht stören“ und in Ruhezeiten – mit Anrufen und Mitteilungen. \(v.exceptions.count) von \(v.limits.exceptions)")
            }

        }
    }
    private func toggle(_ title: LocalizedStringKey, _ hint: String, _ value: Bool, _ body: @escaping (Bool) -> [String: Any]) -> some View {
        Toggle(isOn: Binding(get: { value }, set: { save(body($0)) })) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                Text(verbatim: hint).font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
            }
        }
    }
    private func picker(_ title: LocalizedStringKey, _ hint: LocalizedStringKey, _ value: String, _ key: String, _ opts: [(String, String)]) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Picker(selection: Binding(get: { value }, set: { save([key: $0]) })) {
                ForEach(opts.indices, id: \.self) { i in Text(verbatim: opts[i].1).tag(opts[i].0) }
            } label: { Text(title) }
            Text(hint).font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
        }
    }
    private func save(_ b: [String: Any], undo: (() -> Void)? = nil) {
        status = nil; failed = false
        Task {
            do { try await model.prefs(b); status = String(localized: "✓ Gespeichert") }
            catch { status = error.localizedDescription; failed = true; undo?() }
        }
    }
    private func remove(_ x: UserStatusLogic.Exception) {
        guard let v = model.view else { return }
        save(["exceptions": UserStatusLogic.exceptionsBody(v.exceptions.filter { $0 != x })])
    }
}

/// Ausnahme suchen: Personen (auch anderer Server mit Handle) und eigene Gruppen (GET /api/share/targets)
struct ExceptionPicker: View {
    @ObservedObject var model: UserStatusModel
    @Environment(\.dismiss) private var dismiss
    @State private var q = ""
    @State private var hits: [ShareTarget] = []
    @State private var err: String?
    @State private var searched = false
    var body: some View {
        NavigationStack {
            List {
                if UserStatusLogic.isHandle(q.trimmingCharacters(in: .whitespaces)) && hits.isEmpty {
                    let h = q.trimmingCharacters(in: .whitespaces)
                    Button { add(.init(type: "user", ref: h, name: nil)) } label: { Text(verbatim: PersonName.shown("", id: h)) }
                }
                ForEach(hits) { t in
                    if let x = UserStatusLogic.exception(type: t.type, target: t.target, name: t.name) {
                        Button { add(x) } label: {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(verbatim: t.isGroup ? t.name : (t.remoteLine ?? t.name)).foregroundStyle(.primary)
                                if t.isGroup { Text("Gruppe").font(.caption).foregroundStyle(.secondary) }
                                else if let s = t.sub { Text(verbatim: s).font(.caption).foregroundStyle(.secondary) }
                            }
                        }
                    }
                }
                if searched && hits.isEmpty && !UserStatusLogic.isHandle(q.trimmingCharacters(in: .whitespaces)) { Text("Keine Treffer.").foregroundStyle(.secondary) }
                if let err { Text(verbatim: err).foregroundStyle(.red).font(.footnote) }
            }
            .searchable(text: $q, prompt: Text("Person oder Gruppe suchen …"))
            .task(id: q) {
                let s = q.trimmingCharacters(in: .whitespaces)
                guard !s.isEmpty else { hits = []; searched = false; return }
                try? await Task.sleep(for: .milliseconds(250))
                guard !Task.isCancelled, let enc = s.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) else { return }
                let d = try? await model.store?.api("api/share/targets?q=\(enc)")
                guard !Task.isCancelled else { return }
                hits = d.map(ShareLogic.parseTargets) ?? []
                searched = true
            }
            .navigationTitle("Ausnahme hinzufügen")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Abbrechen") { dismiss() } } }
        }
        #if os(macOS)
        .frame(minWidth: 380, minHeight: 420)
        #endif
    }
    private func add(_ x: UserStatusLogic.Exception) {
        guard let v = model.view else { return }
        guard let next = UserStatusLogic.adding(x, to: v.exceptions, max: v.limits.exceptions) else { dismiss(); return }
        Task {
            do { try await model.prefs(["exceptions": UserStatusLogic.exceptionsBody(next)]); dismiss() }
            catch { err = error.localizedDescription }
        }
    }
}

// MARK: - Status anderer

/// Chip im Kopf des Direkt-Chats: Punkt + "Nicht stören bis 14:30 · Im Kundentermin"; Tippen zeigt den vollen Text
struct PeerStatusChip: View {
    let peer: UserStatusLogic.Peer
    var tap: (String) -> Void = { _ in }
    var body: some View {
        let full = UserStatusText.peer(peer, withText: true)
        Button { tap(full) } label: {
            HStack(spacing: 4) {
                StatusDot(state: peer.s, size: 8, ring: false)
                Text(verbatim: full).lineLimit(1)
            }
            .font(.caption2.weight(.medium))
            .foregroundStyle(StatusDot.color(peer.s))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text(verbatim: String(localized: "Status: \(full)")))
    }
}

/// Abzeichen am Bild in der Chat-Liste: Status (nicht verfuegbar) vor dem gruenen Online-Punkt
struct ChatAvatarBadge: View {
    @ObservedObject var model: UserStatusModel
    let c: ChatSummary
    let online: Bool
    var body: some View {
        if let p = model.peerOf(c) {
            StatusDot(state: p.s, size: 12, label: UserStatusText.peer(p, withText: false)).offset(x: 2, y: 2)
        } else if online { OnlineDot().offset(x: 2, y: 2) }
    }
}

/// Kopf eines Direkt-Chats mit Status (iPhone/iPad): Name, darunter online/zuletzt online und der Status
struct ChatTitleWithStatus: View {
    let title: String
    let presence: String?
    let online: Bool
    let peer: UserStatusLogic.Peer?
    var tap: (String) -> Void = { _ in }
    var body: some View {
        VStack(spacing: 0) {
            Text(verbatim: title).font(.headline).lineLimit(1)
            HStack(spacing: 6) {
                if let presence {
                    Text(verbatim: presence).font(.caption2).foregroundStyle(online ? Color.adaptive(0x1E7B34, 0x4CD964) : .secondary).lineLimit(1)
                }
                if let peer { PeerStatusChip(peer: peer, tap: tap) }
            }
        }
    }
}

/// Kopf des Direkt-Chats (iPhone/iPad) - beobachtet den Status (Abo/Live), sonst wie ChatTitleWithPresence
struct DMStatusTitle: View {
    @ObservedObject var model: UserStatusModel
    let info: ChatSummary
    let presence: String?
    let online: Bool
    var tap: (String) -> Void = { _ in }
    var body: some View {
        let p = model.peerOf(info)
        if presence != nil || p != nil {
            ChatTitleWithStatus(title: info.title, presence: presence, online: online, peer: p, tap: tap)
        } else {
            Text(verbatim: info.title).font(.headline).lineLimit(1)
        }
    }
}
