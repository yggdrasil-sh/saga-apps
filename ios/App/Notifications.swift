import SwiftUI

// Benachrichtigungen (Glocke, Saga 1.4): Liste, Zaehler, als gelesen markieren, Einstellungen je Art.
// Live ueber LiveHub ("notification"), sonst beim Aktualisieren. Logik: LiveLogic.swift (NotifItem, NotifLogic)

@MainActor
final class NotificationsModel: ObservableObject {
    weak var store: Store?
    @Published var items: [NotifItem] = []
    @Published var unread = 0
    @Published var more = false
    @Published var loading = false
    @Published var error: String?
    /// Server kennt Benachrichtigungen (aelterer Server: 404 -> Glocke ausblenden)
    @Published var supported = true
    private var next: Int?

    func reset() { items = []; unread = 0; more = false; next = nil; error = nil }

    /// nur den Zaehler (limit=0)
    func loadCount() async {
        guard let store, let (d, code) = try? await store.apiResponse("api/notifications?limit=0") else { return }
        if code == 404 { supported = false; return }
        guard code == 200, let j = ShareJSON.obj(d) else { return }
        supported = true
        if let n = ShareJSON.int(j["unread"]), n != unread { unread = n }
    }
    func load() async {
        guard let store else { return }
        loading = true; defer { loading = false }
        do {
            let (d, code) = try await store.apiResponse("api/notifications?limit=30")
            if code == 404 { supported = false; return }
            guard code == 200, let j = ShareJSON.obj(d) else { throw APIError.http(code, "") }
            items = (j["items"] as? [Any] ?? []).compactMap(NotifItem.init)
            unread = ShareJSON.int(j["unread"]) ?? unread; more = ShareJSON.bool(j["more"]); next = ShareJSON.int(j["next"])
            error = nil
        } catch { self.error = error.localizedDescription }
    }
    func loadOlder() async {
        guard let store, let n = next, more else { return }
        guard let d = try? await store.api("api/notifications?limit=30&before=\(n)"), let j = ShareJSON.obj(d) else { return }
        let older = (j["items"] as? [Any] ?? []).compactMap(NotifItem.init).filter { o in !items.contains { $0.id == o.id } }
        items += older; more = ShareJSON.bool(j["more"]); next = ShareJSON.int(j["next"])
    }
    func markRead(_ ids: [Int]) async {
        guard let store, !ids.isEmpty else { return }
        for i in items.indices where ids.contains(items[i].id) { items[i].read = true }
        if let d = try? await store.api("api/notifications/read", method: "POST", body: ["ids": ids]), let n = ShareJSON.int(ShareJSON.obj(d)?["unread"]) { unread = n }
    }
    func markAll() async {
        guard let store else { return }
        for i in items.indices { items[i].read = true }
        if let d = try? await store.api("api/notifications/read", method: "POST", body: ["all": true]) { unread = ShareJSON.int(ShareJSON.obj(d)?["unread"]) ?? 0 }
    }
    /// SSE "notification"
    func apply(_ d: [String: Any]) {
        let r = NotifLogic.apply(d, to: items)
        if r.items != items { items = r.items }
        if let n = r.unread, n != unread { unread = n }
    }
    /// Chat geloescht (2.1): seine Benachrichtigungen verschwinden (der Server hat sie schon entfernt)
    func dropChat(_ cid: Int) {
        guard items.contains(where: { $0.chatID == cid }) else { return }
        items.removeAll { $0.chatID == cid }
        Task { await loadCount() }
    }
    /// Mitteilung angetippt (traegt bewusst keine id): passende ungelesene Benachrichtigung finden und markieren
    func markReadFor(target: NotifTarget) async {
        guard let store, let d = try? await store.api("api/notifications?unread=1&limit=30"), let j = ShareJSON.obj(d) else { return }
        var pageID: Int?
        if case .page(let id, _) = target { pageID = id }
        let hits = (j["items"] as? [Any] ?? []).compactMap(NotifItem.init).filter { n in NotifLogic.target(n) == target || (pageID != nil && n.noteID == pageID) }
        await markRead(hits.map { $0.id })
    }
}

// MARK: - Glocke + Liste

struct BellButton: View {
    @ObservedObject var notifs: NotificationsModel
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            Image(systemName: notifs.unread > 0 ? "bell.badge" : "bell")
                .symbolRenderingMode(notifs.unread > 0 ? .multicolor : .monochrome)
        }
        .help("Benachrichtigungen")
        .accessibilityLabel(notifs.unread > 0 ? Text("Benachrichtigungen, \(notifs.unread) ungelesen") : Text("Benachrichtigungen"))
    }
}

struct NotificationsSheet: View {
    @ObservedObject var notifs: NotificationsModel
    var open: (NotifTarget) -> Void
    var settings: () -> Void
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        NavigationStack {
            List {
                if notifs.items.isEmpty && !notifs.loading {
                    ContentUnavailableView {
                        Label("Keine Benachrichtigungen", systemImage: "bell.slash")
                    } description: {
                        Text("Hier erscheint, wenn jemand etwas mit dir teilt oder auf deinen Seiten kommentiert.")
                    }
                    .listRowBackground(Color.clear)
                }
                ForEach(notifs.items) { n in
                    Button { tap(n) } label: { NotifRow(n: n) }
                        .buttonStyle(.plain).disabled(!n.openable && n.read)
                }
                if notifs.more {
                    Button("Ältere laden") { Task { await notifs.loadOlder() } }
                }
                if let e = notifs.error { Text("Nicht geladen: \(e)").font(.footnote).foregroundStyle(.orange) }
            }
            .overlay { if notifs.loading && notifs.items.isEmpty { ProgressView() } }
            .refreshable { await notifs.load() }
            .navigationTitle("Benachrichtigungen")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Schließen") { dismiss() } }
                // wie im Web: "Alle gelesen" sichtbar, daneben die Einstellungen
                ToolbarItem(placement: .primaryAction) {
                    #if os(iOS)
                    // als Symbol: der lange Text verdraengte auf dem iPhone den Titel des Blatts
                    Button { Task { await notifs.markAll() } } label: { Label("Alle gelesen", systemImage: "checkmark.circle") }
                        .disabled(notifs.unread == 0)
                    #else
                    Button("Alle gelesen") { Task { await notifs.markAll() } }.disabled(notifs.unread == 0)
                    #endif
                }
                ToolbarItem(placement: .primaryAction) {
                    Button { dismiss(); settings() } label: { Label("Benachrichtigungen einstellen", systemImage: "gearshape") }
                        .help(Text("Benachrichtigungen einstellen"))
                }
            }
        }
        .macFrame(minWidth: 420, idealWidth: 480, minHeight: 480, idealHeight: 620)
        .task { await notifs.load() }
    }
    private func tap(_ n: NotifItem) {
        Task { await notifs.markRead([n.id]) }
        let t = NotifLogic.target(n)
        if t != .none { dismiss(); open(t) }
    }
}

/// Zeile wie im Web: Person (Kuerzel), Ereignis, Seite bzw. Chat, Auszug, relative Zeit; ungelesen = Goldpunkt + fett
struct NotifRow: View {
    let n: NotifItem
    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            ZStack {
                Circle().fill(Color.gold.opacity(0.16))
                if n.kind == "publish.withdrawn" { Text(verbatim: "🛡") }
                else if n.system { Image(systemName: "externaldrive.badge.exclamationmark").font(.callout).foregroundStyle(.red) }   // Backups (2.3)
                else if let a = n.actorName { AvatarView(name: a, avatar: n.actorAvatar, size: 34) }
                else { Image(systemName: "person.fill.questionmark").font(.caption) }
            }
            .frame(width: 34, height: 34).accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(verbatim: n.text).font(.subheadline.weight(n.read ? .regular : .semibold)).fixedSize(horizontal: false, vertical: true)
                Text(verbatim: page).font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
                if let x = n.excerpt { Text(verbatim: "„\(x)“").font(.footnote).foregroundStyle(.secondary).lineLimit(2) }
                HStack(spacing: 6) {
                    Text(Date(timeIntervalSince1970: n.created), format: SagaDate.relative)
                    if let h = n.host { Text(verbatim: "· \(h)") }
                }.font(.caption).foregroundStyle(.secondary)
            }
            Spacer(minLength: 0)
            if !n.read { Circle().fill(Color.gold).frame(width: 9, height: 9).padding(.top, 6).accessibilityLabel(Text("Ungelesen")) }
        }
        .padding(.vertical, 2).contentShape(Rectangle())
        .opacity(n.access ? 1 : 0.7)
    }
    private var page: String {
        if n.system { return n.title.isEmpty ? CloudLogic.systemTitle(n.kind) : n.title }   // "Mein Backup" / "Backups" statt Seite
        if !n.access { return "🔒 " + String(localized: "Kein Zugriff mehr") }
        if n.chatID != nil { return "💬 " + (n.title.isEmpty ? String(localized: "Chat") : n.title) }
        let icon = n.noteIcon.flatMap { $0.isEmpty || $0.hasPrefix("/") ? nil : $0 } ?? "📄"
        return icon + " " + (n.title.isEmpty ? String(localized: "Unbenannt") : n.title)
    }
}

// MARK: - Einstellungen: Benachrichtigungen je Art

struct NotificationSettingsSection: View {
    @EnvironmentObject var store: Store
    @State private var kinds: [(kind: String, on: Bool)] = []
    @State private var supported = true
    @State private var status: String?
    var body: some View {
        if supported {
            Section {
                ForEach(kinds.indices, id: \.self) { i in
                    Toggle(Self.label(kinds[i].kind), isOn: Binding(get: { kinds[i].on }, set: { v in kinds[i].on = v; Task { await save(kinds[i].kind, v) } }))
                        .id(i == 0 ? "notifs" : "notif-\(i)")
                }
                if let status { Text(status).font(.footnote).foregroundStyle(.secondary) }
            } header: { Text("Benachrichtigungen") } footer: { Text("Erscheinen in Saga unter der Glocke – sofort, solange Saga offen ist.") }
            .task { await load() }
        }
    }
    static func label(_ k: String) -> String {
        switch k {
        case "share.added": return String(localized: "Jemand teilt eine Seite mit mir")
        case "share.role": return String(localized: "Meine Rolle auf einer geteilten Seite ändert sich")
        case "comment.new": return String(localized: "Neue Kommentare auf meinen Seiten")
        case "comment.reply": return String(localized: "Antworten in Unterhaltungen, an denen ich beteiligt bin")
        case "publish.withdrawn": return String(localized: "Ein Admin zieht eine Veröffentlichung von mir zurück")
        case "comment.mention": return String(localized: "Jemand erwähnt mich")
        case "chat.message": return String(localized: "Neue Chat-Nachrichten (ohne Inhalt; stummgeschaltete Chats nie)")
        case "call.missed": return String(localized: "Verpasste Anrufe")
        case "userbackup.failed": return String(localized: "Mein Backup ist fehlgeschlagen")
        case "backup.failed": return String(localized: "Backups des Servers sind fehlgeschlagen (Admin)")
        default: return k
        }
    }
    private func parse(_ d: Data) -> [(String, Bool)] {
        (ShareJSON.obj(d)?["kinds"] as? [Any] ?? []).compactMap { v in
            guard let o = v as? [String: Any], let k = o["kind"] as? String else { return nil }
            return (k, ShareJSON.bool(o["on"]))
        }
    }
    private func load() async {
        guard let (d, code) = try? await store.apiResponse("api/notifications/settings") else { return }
        if code == 404 { supported = false; return }
        kinds = parse(d).map { (kind: $0.0, on: $0.1) }
    }
    private func save(_ k: String, _ on: Bool) async {
        do { let d = try await store.api("api/notifications/settings", method: "PUT", body: [k: on]); kinds = parse(d).map { (kind: $0.0, on: $0.1) }; status = String(localized: "✓ Gespeichert") }
        catch { status = error.localizedDescription }
    }
}
