import SwiftUI

// Chat-Mehrfachauswahl (2.5, CHAT-AUSWAHL-SPEC): Aktionen fuer mehrere Nachrichten (Kopieren, Weiterleiten,
// In Seite uebernehmen, Loeschen fuer mich/fuer alle), Rueckfall bei aelteren Servern, und der Hinweis im Chat-Kopf,
// warum es (noch) keine Reaktionen gibt (x3_why). Logik: ChatSelectLogic.swift.

/// Nur auf diesem Geraet ausgeblendete Nachrichten (aelterer Server ohne POST .../hide)
enum ChatHiddenStore {
    private static func key(_ me: String, _ cid: Int) -> String { "hk.chat.hidden." + (AppConfig.serverOrigin ?? "-") + "|" + me + "|" + String(cid) }
    static func ids(_ me: String, _ cid: Int) -> Set<String> { Set(UserDefaults.standard.stringArray(forKey: key(me, cid)) ?? []) }
    static func add(_ me: String, _ cid: Int, _ ids: [String]) {
        let all = self.ids(me, cid).union(ids)
        UserDefaults.standard.set(Array(all.prefix(5000)), forKey: key(me, cid))
    }
}

extension ConversationModel {
    /// Auswaehlbar: keine Systemzeile, kein Anruf-Eintrag, entschluesselt
    func selectable(_ m: ChatMessage) -> Bool {
        ChatSelectLogic.selectable(call: m.call != nil, sys: m.sys != nil, decrypted: m.content != nil)
    }
    /// Nachricht fuer Kopieren/Seite/Weiterleiten: Seitenverweise als Titel, Anhaenge als Art
    func selectItem(_ m: ChatMessage) -> ChatSelectLogic.Item {
        var t = ""
        for p in ChatLogic.pieces(m.text) {
            switch p {
            case .text(let s): t += s
            case .note(let id): t += "📄 " + (notes[id]?.title ?? String(localized: "Seite"))
            }
        }
        let atts: [ChatSelectLogic.Att] = (m.content?.files ?? []).map { f in
            if f.voice { return .voice }
            if f.isVideo || f.isV2 { return .video(f.name) }
            if f.isImage { return .image(f.name) }
            return .file(f.name.isEmpty ? String(localized: "Datei") : f.name)
        }
        let name = m.aiState == .ok ? (m.content?.aiName ?? aiName) : m.senderName
        return .init(id: m.id, seq: m.seq, mine: m.mine, name: name, date: m.date, text: t, atts: atts)
    }
    static var selectLabels: ChatSelectLogic.Labels {
        var l = ChatSelectLogic.Labels()
        l.image = String(localized: "[Bild]")
        l.file = { String(localized: "[Datei: \($0)]") }
        l.voice = String(localized: "[Sprachnachricht]")
        l.video = String(localized: "[Video]")
        l.notForwarded = String(localized: "[Anhang nicht weitergeleitet]")
        return l
    }
    /// "Fuer alle loeschen" erlaubt (beschreibbarer Chat)
    var deleteAllowed: Bool { !(fed?.readonly ?? false) && !(fed?.request ?? false) }

    /// Mehrere eigene Nachrichten fuer alle loeschen; aeltere Server: einzeln. Rueckgabe: geloescht, fehlgeschlagen
    func deleteForAll(_ ids: [String]) async -> (done: Int, failed: Int) {
        guard let store else { return (0, ids.count) }
        var done: [String] = [], failed = 0, single = false
        for part in ChatSelectLogic.chunks(ids, ChatSelectLogic.deleteMax) {
            if !single {
                let r = try? await store.apiResponse("api/chats/\(chatID)/messages/delete", method: "POST", body: ["ids": part])
                switch r.map({ ChatSelectLogic.deleteResult(code: $0.1, body: ShareJSON.obj($0.0), asked: part) }) ?? .error(String(localized: "Keine Antwort vom Server")) {
                case .ok(let d, let f): done += d; failed += part.count - d.count; _ = f; continue
                case .error(let e): sendError = e; failed += part.count; continue
                case .fallback: single = true
                }
            }
            for id in part {                                                  // Rueckfall: DELETE je Nachricht
                if let (_, code) = try? await store.apiResponse("api/chats/\(chatID)/messages/\(id)", method: "DELETE"), (200..<300).contains(code) { done.append(id) }
                else { failed += 1 }
            }
        }
        let gone = Set(done)
        messages.removeAll { gone.contains($0.id) }
        return (done.count, failed)
    }

    /// Fuer mich ausblenden (auf allen eigenen Geraeten); aeltere Server: nur hier. Rueckgabe: Anzahl, nur lokal
    func hideForMe(_ ids: [String]) async -> (n: Int, localOnly: Bool, error: String?) {
        guard let store else { return (0, true, nil) }
        var local = false
        for part in ChatSelectLogic.chunks(ids, ChatSelectLogic.hideMax) {
            if local { continue }
            let r = try? await store.apiResponse("api/chats/\(chatID)/hide", method: "POST", body: ["ids": part])
            switch r.map({ ChatSelectLogic.hideResult(code: $0.1, body: ShareJSON.obj($0.0)) }) ?? .error(String(localized: "Keine Antwort vom Server")) {
            case .ok: break
            case .fallback: local = true
            case .error(let e): return (0, false, e)
            }
        }
        if local { ChatHiddenStore.add(me, chatID, ids) }
        let gone = Set(ids)
        messages.removeAll { gone.contains($0.id) }
        return (ids.count, local, nil)
    }

    /// Live: chat.hidden {chat_id, ids} (eigene andere Geraete haben ausgeblendet)
    func liveHidden(_ d: [String: Any]) {
        guard let ids = ChatSelectLogic.hiddenIDs(d, chatID: chatID), !ids.isEmpty else { return }
        let gone = Set(ids)
        messages.removeAll { gone.contains($0.id) }
    }
    /// lokal ausgeblendete (aelterer Server) nicht zeigen
    var locallyHidden: Set<String> { ChatHiddenStore.ids(me, chatID) }
}

// MARK: - Weiterleiten

@MainActor
enum ChatForward {
    /// Nachrichten in einen anderen Chat senden: Text neu verschluesselt, Bilder/Dateien ueber den normalen Sendeweg
    /// neu hochgeladen, Sprachnachrichten/Videos als Hinweis. Rueckgabe: gesendet, Fehler
    static func run(_ msgs: [ChatMessage], from conv: ConversationModel, to target: Int, model: ChatModel) async -> (sent: Int, skipped: Int, error: String?) {
        let to = ConversationModel(chatID: target, model: model)
        await to.prepareForSend()
        guard to.info != nil else { return (0, 0, String(localized: "Keine Antwort vom Server")) }
        var sent = 0, skipped = 0
        let order = msgs.sorted { $0.seq < $1.seq }
        for m in order {
            let it = conv.selectItem(m)
            let fw = ChatSelectLogic.forward(it, labels: ConversationModel.selectLabels)
            skipped += fw.skipped
            var files: [ChatPendingFile] = []
            for i in fw.upload {
                guard let f = m.content?.files[i] else { continue }
                do { files.append(ChatPendingFile(name: f.name.isEmpty ? "datei" : f.name, mime: f.mime, data: try await conv.original(f))) }
                catch { return (sent, skipped, String(localized: "Anhang nicht geladen: \(error.localizedDescription)")) }
            }
            if fw.text.isEmpty && files.isEmpty { continue }
            guard await to.send(text: fw.text, replyTo: nil, files: files) else { return (sent, skipped, to.sendError) }
            sent += 1
        }
        await model.loadList()
        return (sent, skipped, nil)
    }
}

/// Zielchat waehlen (Suche); Rueckfrage vor dem Senden in der Unterhaltung
struct ForwardPickSheet: View {
    @ObservedObject var chat: ChatModel
    let exclude: Int
    var picked: (ChatSummary) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var q = ""
    var body: some View {
        NavigationStack {
            let list = chat.chats.filter { c in
                c.id != exclude && !(c.fed?.request ?? false) && !(c.fed?.readonly ?? false) && !c.archived
                    && (q.isEmpty || c.title.localizedCaseInsensitiveContains(q))
            }
            List {
                if list.isEmpty { Text("Kein passender Chat.").foregroundStyle(.secondary) }
                ForEach(list) { c in
                    Button { dismiss(); picked(c) } label: { ChatRow(c: c).contentShape(Rectangle()) }
                        .buttonStyle(.plain)
                }
            }
            .searchable(text: $q, prompt: Text("Chats durchsuchen"))
            .navigationTitle("Weiterleiten an")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Abbrechen") { dismiss() }.keyboardShortcut(.cancelAction) } }
        }
        .macFrame(minWidth: 380, idealWidth: 420, minHeight: 460)
    }
}

// MARK: - In Seite uebernehmen

@MainActor
enum ChatToPage {
    enum Failure: Error { case notLoaded, conflict, http(Int) }
    /// Neue Seite (target nil) anlegen bzw. an eine bestehende anhaengen. Gespeichert wird nur eine erfolgreich
    /// geladene Seite (GET 200), mit ihrer Revision; bei Konflikt einmal frisch laden. Rueckgabe: Seiten-ID
    static func run(_ store: Store, items: [ChatSelectLogic.Item], target: Int?, title: String) async throws -> Int {
        let time: (Date) -> String = { $0.sagaFormatted(date: .numeric, time: .shortened) }
        let blocks = ChatSelectLogic.pageBlocks(items, time: time, labels: ConversationModel.selectLabels, newID: { String(UUID().uuidString.prefix(10)) })
        var id = target
        if id == nil {
            let (d, code) = try await store.apiResponse("api/notes", method: "POST", body: ["title": title])
            guard (200..<300).contains(code), let nid = ShareJSON.obj(d).flatMap({ ShareJSON.int($0["id"]) }) else { throw Failure.http(code) }
            id = nid
            await store.insertCreated(.created(id: nid, parent: nil, updated: Date().timeIntervalSince1970))
        }
        guard let pid = id else { throw Failure.notLoaded }
        for attempt in 0..<2 {
            let (d, code) = try await store.apiResponse("api/notes/\(pid)")
            guard code == 200, let p = ShareJSON.obj(d), let rev = ShareJSON.int(p["rev"]) else { throw Failure.notLoaded }   // nie ungeladen speichern
            guard let body = ChatSelectLogic.appended(body: p["body"] as? String ?? "", format: p["format"] as? String, blocks: blocks) else { throw Failure.notLoaded }
            let (_, sc) = try await store.apiResponse("api/notes/\(pid)", method: "PATCH", body: ["body": body, "format": "blocks", "rev": rev])
            if sc == 409, attempt == 0 { continue }
            if sc == 409 { throw Failure.conflict }
            guard (200..<300).contains(sc) else { throw Failure.http(sc) }
            store.dropCache("api/notes/\(pid)")
            return pid
        }
        throw Failure.conflict
    }
    static func message(_ e: Error) -> String {
        switch e as? Failure {
        case .notLoaded: return String(localized: "Die Seite ließ sich nicht laden – nichts gespeichert.")
        case .conflict: return String(localized: "Die Seite wurde gerade geändert – bitte noch einmal versuchen.")
        case .http(let c): return APIError.http(c, "").localizedDescription
        case nil: return e.localizedDescription
        }
    }
}

/// Ziel: neue Seite oder bestehende Seite (Auswahl wie beim Verschieben)
struct PageTargetSheet: View {
    var picked: (Int?) -> Void
    @EnvironmentObject var store: Store
    @Environment(\.dismiss) private var dismiss
    @State private var q = ""
    var body: some View {
        NavigationStack {
            let targets = store.pages.filter { !$0.isDatabase && !($0.parent_id.map { store.page($0)?.isDatabase ?? false } ?? false) }
                .filter { q.isEmpty || $0.label.localizedCaseInsensitiveContains(q) }
            List {
                if q.isEmpty {
                    Button { dismiss(); picked(nil) } label: {
                        Label("Neue Seite", systemImage: "doc.badge.plus").frame(maxWidth: .infinity, alignment: .leading).contentShape(Rectangle())
                    }.buttonStyle(.plain)
                }
                Section("An Seite anhängen") {
                    ForEach(targets) { p in
                        Button { dismiss(); picked(p.id) } label: {
                            HStack {
                                PageIcon(icon: p.icon, store: store)
                                VStack(alignment: .leading, spacing: 1) {
                                    Text(p.label)
                                    let path = store.path(p.id).map(\.label).joined(separator: " / ")
                                    if !path.isEmpty { Text(path).font(.caption).foregroundStyle(.secondary).lineLimit(1) }
                                }
                                Spacer(minLength: 0)
                            }.contentShape(Rectangle())
                        }.buttonStyle(.plain)
                    }
                }
            }
            .searchable(text: $q, prompt: "Seite suchen")
            .navigationTitle("In Seite übernehmen")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Abbrechen") { dismiss() }.keyboardShortcut(.cancelAction) } }
        }
        .macFrame(minWidth: 380, minHeight: 460)
    }
}

// MARK: - Leiste im Auswahlmodus

struct ChatSelectBar: View {
    let count: Int
    let busy: Bool
    var copy: () -> Void
    var forward: () -> Void
    var toPage: () -> Void
    var delete: () -> Void
    var body: some View {
        HStack(spacing: 0) {
            item("Kopieren", "doc.on.doc", copy)
            item("Weiterleiten", "arrowshape.turn.up.right", forward)
            item("In Seite", "doc.text", toPage, help: "In Seite übernehmen")
            item("Löschen", "trash", delete, destructive: true)
        }
        .disabled(count == 0 || busy)
        .overlay { if busy { ProgressView() } }
        .padding(.horizontal, 8).padding(.vertical, 6)
        .background(.bar)
    }
    /// Kurze Beschriftung in einheitlicher Groesse (kein Verkleinern); `help` = ausfuehrlich fuer Hilfe und VoiceOver
    private func item(_ t: LocalizedStringKey, _ icon: String, _ run: @escaping () -> Void, destructive: Bool = false,
                      help: LocalizedStringKey? = nil) -> some View {
        Button(role: destructive ? .destructive : nil, action: run) {
            VStack(spacing: 3) {
                Image(systemName: icon).font(.title3).frame(height: 26)
                Text(t).font(.caption2).lineLimit(1)
            }
            .frame(maxWidth: .infinity, minHeight: 44).contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .foregroundStyle(destructive ? Color.red : Color.gold)
        .help(Text(help ?? t))
        .accessibilityLabel(Text(help ?? t))
    }
}

/// Auswahlkreis vor einer Nachricht
struct SelectMark: View {
    let on: Bool
    var enabled = true
    var body: some View {
        // nicht auswaehlbar (Anruf, Systemzeile): kein Kreis, nur derselbe Platz - die Zeilen bleiben buendig
        Image(systemName: on ? "checkmark.circle.fill" : "circle")
            .font(.title3)
            .foregroundStyle(on ? Color.gold : Color.secondary.opacity(0.8))
            .opacity(enabled || on ? 1 : 0)
            .frame(width: 28)
            .accessibilityHidden(true)
    }
}

/// Loeschen mehrerer Nachrichten: Fuer mich / Fuer alle / Abbrechen, breit genug fuer ganze Woerter
struct ChatDeleteChoice: View {
    let count: Int
    let forAll: Bool
    var forMe: () -> Void
    var everyone: () -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var height: CGFloat = 320
    var body: some View {
        VStack(spacing: 16) {
            VStack(spacing: 6) {
                Text("\(count) Nachrichten löschen?").font(.headline)
                Group {
                    if forAll { Text("„Für mich löschen“ blendet die Nachrichten auf allen deinen Geräten aus. „Für alle löschen“ löscht sie vom Server.") }
                    else { Text("„Für mich löschen“ blendet die Nachrichten auf allen deinen Geräten aus. Nur eigene Nachrichten lassen sich für alle löschen.") }
                }
                .font(.subheadline).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
            }
            .multilineTextAlignment(.center)
            VStack(spacing: 10) {
                Button(role: .destructive, action: forMe) { Text("Für mich löschen").frame(maxWidth: .infinity) }
                    .buttonStyle(.bordered).controlSize(.large)
                if forAll {
                    Button(role: .destructive, action: everyone) { Text("Für alle löschen").frame(maxWidth: .infinity) }
                        .buttonStyle(.bordered).controlSize(.large)
                }
                Button { dismiss() } label: { Text("Abbrechen").frame(maxWidth: .infinity) }
                    .buttonStyle(.bordered).controlSize(.large).tint(.secondary)
                    .keyboardShortcut(.cancelAction)
            }
        }
        .padding(.horizontal, 24).padding(.top, 24).padding(.bottom, 16)
        .frame(maxWidth: 440)
        #if os(iOS)
        .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { height = $0 }
        .presentationDetents([.height(height)])
        .presentationDragIndicator(.visible)
        #else
        .frame(width: 400)
        #endif
    }
}

// MARK: - Warum keine Reaktionen (Chat-Kopf, i)

struct X3WhyButton: View {
    let why: ChatSelectLogic.Why
    @State private var open = false
    var body: some View {
        Button { open = true } label: { Image(systemName: "info.circle") }
            .help(Text("Warum keine Reaktionen?"))
            .accessibilityLabel(Text("Warum keine Reaktionen?"))
            #if DEBUG
            .task { if SelectPreview.mode == "hint" { try? await Task.sleep(for: .seconds(1)); open = true } }   // nur fuer Bilder
            #endif
            .popover(isPresented: $open) {
                VStack(alignment: .leading, spacing: 12) {
                    Label("Reaktionen und Bearbeiten", systemImage: "face.smiling").font(.headline)
                    Text(verbatim: Self.text(why)).fixedSize(horizontal: false, vertical: true)
                    if ChatSelectLogic.offerDevices(why) {
                        Button("Geräte verwalten") {
                            open = false
                            CloudNav.pendingSection = "chats"
                            NotificationCenter.default.post(name: .hkOpenSettings, object: "chats")
                        }.prominentGold()
                    }
                }
                .padding(18).frame(idealWidth: 340, maxWidth: 380)
                #if os(iOS)
                .presentationCompactAdaptation(.popover)
                #endif
            }
    }
    static func text(_ w: ChatSelectLogic.Why) -> String {
        switch w {
        case .setting:
            return String(localized: "Reaktionen und Bearbeiten sind auf diesem Server noch nicht eingeschaltet. Das kann die Verwaltung des Servers ändern.")
        case .devices(let mine, let others, let until):
            var parts = [String(localized: "Reaktionen und Bearbeiten kommen, sobald alle Geräte Saga 2.5 nutzen.")]
            let list = mine.joined(separator: ", ")
            if !mine.isEmpty && others == 1 { parts.append(String(localized: "Noch nicht aktualisiert: \(list) und Geräte einer weiteren Person.")) }
            else if !mine.isEmpty && others > 1 { parts.append(String(localized: "Noch nicht aktualisiert: \(list) und Geräte von \(others) weiteren Personen.")) }
            else if !mine.isEmpty { parts.append(String(localized: "Noch nicht aktualisiert: \(list).")) }
            else if others == 1 { parts.append(String(localized: "Noch nicht aktualisiert: Geräte einer weiteren Person.")) }
            else if others > 1 { parts.append(String(localized: "Noch nicht aktualisiert: Geräte von \(others) weiteren Personen.")) }
            if let u = until { parts.append(String(localized: "Spätestens am \(Date(timeIntervalSince1970: u).sagaFormatted(date: .long, time: .omitted)).")) }
            return parts.joined(separator: " ")
        case .server(let hosts):
            if hosts.isEmpty { return String(localized: "Reaktionen und Bearbeiten kommen, sobald alle beteiligten Server aktualisiert sind.") }
            return String(localized: "Reaktionen und Bearbeiten kommen, sobald \(ListFormatter.localizedString(byJoining: hosts)) aktualisiert ist.")
        case .readonly:
            return String(localized: "Dieser Chat ist schreibgeschützt – Reaktionen und Bearbeiten gehen hier nicht.")
        case .pending:
            return String(localized: "Reaktionen und Bearbeiten gehen, sobald die Anfrage angenommen ist.")
        case .other:
            return String(localized: "Reaktionen und Bearbeiten gehen in diesem Chat noch nicht.")
        }
    }
}

// MARK: - Zustand und Ablaeufe des Auswahlmodus

@MainActor
final class ChatSelection: ObservableObject {
    @Published var on = false
    @Published var ids: Set<String> = []
    @Published var busy = false
    @Published var askDelete = false
    @Published var askDeleteAll = false
    @Published var pickForward = false
    @Published var forwardTo: ChatSummary?
    @Published var askPage = false
    @Published var pickPage = false
    private(set) var anchor: String?
    func start(_ id: String) { ids = [id]; anchor = id; on = true }
    func end() { on = false; ids = []; anchor = nil }
    /// Antippen/Klick (Cmd-Klick gleich); Umschalt-Klick waehlt den Bereich ab dem letzten Klick
    func tap(_ id: String, shift: Bool, order: [String], selectable: Set<String>) {
        guard selectable.contains(id) else { return }
        if shift, anchor != nil {
            ids.formUnion(ChatSelectLogic.range(order, selectable: selectable, from: anchor, to: id))
        } else {
            _ = ChatSelectLogic.toggle(&ids, id)
        }
        anchor = id
    }
}

/// Alerts/Blaetter des Auswahlmodus (eigener Modifier: Typpruefung der Unterhaltung bleibt schnell)
struct ChatSelectFlows: ViewModifier {
    @ObservedObject var sel: ChatSelection
    @ObservedObject var conv: ConversationModel
    @ObservedObject var chat: ChatModel
    var openPage: (Int) -> Void
    @EnvironmentObject var store: Store
    private var msgs: [ChatMessage] { conv.messages.filter { sel.ids.contains($0.id) && conv.selectable($0) } }
    private var items: [ChatSelectLogic.Item] { msgs.map(conv.selectItem) }
    func body(content: Content) -> some View {
        content
            .sheet(isPresented: $sel.askDelete) {
                // eigenes Blatt statt confirmationDialog: das schmale Popover (iOS 26, iPad) trennte Woerter und
                // zeigte kein "Abbrechen"
                ChatDeleteChoice(count: msgs.count, forAll: ChatSelectLogic.canDeleteForAll(items, allowed: conv.deleteAllowed),
                                 forMe: { sel.askDelete = false; hide() },
                                 everyone: { sel.askDelete = false; DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) { sel.askDeleteAll = true } })
            }
            .alert(Text("\(msgs.count) Nachrichten für alle löschen?"), isPresented: $sel.askDeleteAll) {
                Button("Für alle löschen", role: .destructive) { deleteAll() }
                Button("Abbrechen", role: .cancel) {}
            } message: { Text("Die Nachrichten und ihre Anhänge werden vom Server gelöscht. Wer sie schon gelesen hat, kann eine Kopie haben.") }
            .sheet(isPresented: $sel.pickForward) {
                ForwardPickSheet(chat: chat, exclude: conv.chatID) { c in DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { sel.forwardTo = c } }
            }
            .alert(Text("\(msgs.count) Nachrichten an \(sel.forwardTo?.title ?? "") weiterleiten?"),
                   isPresented: Binding(get: { sel.forwardTo != nil }, set: { if !$0 { sel.forwardTo = nil } }), presenting: sel.forwardTo) { c in
                Button("Weiterleiten") { forward(to: c) }
                Button("Abbrechen", role: .cancel) {}
            } message: { _ in Text("Texte werden neu verschlüsselt gesendet, Bilder und Dateien neu hochgeladen.") }
            .alert("In Seite übernehmen?", isPresented: $sel.askPage) {
                Button("Fortfahren") { DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { sel.pickPage = true } }
                Button("Abbrechen", role: .cancel) {}
            } message: { Text("Die Seite ist nicht Ende-zu-Ende-verschlüsselt – der Server kann den Text lesen.") }
            .sheet(isPresented: $sel.pickPage) { PageTargetSheet { t in toPage(t) }.environmentObject(store) }
    }

    // MARK: Aktionen

    static func copy(_ items: [ChatSelectLogic.Item]) -> String {
        ChatSelectLogic.copyText(items, time: { $0.sagaFormatted(date: .numeric, time: .shortened) }, labels: ConversationModel.selectLabels)
    }
    private func hide() {
        let ids = msgs.map(\.id)
        sel.busy = true
        Task {
            let r = await conv.hideForMe(ids)
            sel.busy = false
            if let e = r.error { chat.toast = e; return }
            chat.toast = r.localOnly ? String(localized: "\(r.n) Nachrichten ausgeblendet – nur auf diesem Gerät.") : String(localized: "\(r.n) Nachrichten gelöscht.")
            sel.end()
        }
    }
    private func deleteAll() {
        let ids = msgs.filter(\.mine).map(\.id)
        sel.busy = true
        Task {
            let r = await conv.deleteForAll(ids)
            sel.busy = false
            chat.toast = r.failed > 0 ? String(localized: "\(r.done) gelöscht, \(r.failed) nicht gelöscht.") : String(localized: "\(r.done) Nachrichten gelöscht.")
            if r.failed == 0 { sel.end() } else { sel.ids.formIntersection(conv.messages.map(\.id)) }   // nicht Geloeschte bleiben ausgewaehlt
        }
    }
    private func forward(to c: ChatSummary) {
        let list = msgs
        guard ChatSelectLogic.forwardAllowed(list.count) else { return }
        sel.busy = true
        Task {
            let r = await ChatForward.run(list, from: conv, to: c.id, model: chat)
            sel.busy = false
            if let e = r.error { chat.toast = String(localized: "Weiterleiten abgebrochen: \(e)"); return }
            chat.toast = r.skipped > 0 ? String(localized: "\(r.sent) weitergeleitet, \(r.skipped) Anhänge nicht weitergeleitet.")
                                       : String(localized: "\(r.sent) Nachrichten weitergeleitet.")
            sel.end()
        }
    }
    private func toPage(_ target: Int?) {
        let its = items
        let title = ChatSelectLogic.pageTitle(chat: conv.info?.title ?? String(localized: "Chat"), date: Date().sagaFormatted(date: .numeric, time: .omitted))
        sel.busy = true
        Task {
            do {
                let id = try await ChatToPage.run(store, items: its, target: target, title: title)
                sel.busy = false; sel.end()
                chat.toast = String(localized: "In die Seite übernommen.")
                openPage(id)
            } catch {
                sel.busy = false
                chat.toast = ChatToPage.message(error)
            }
        }
    }
}
