import SwiftUI
import CoreImage
import CoreImage.CIFilterBuiltins

// Chat-Ansichten: Bereich "Chats" (Liste + Unterhaltung nebeneinander bzw. gestapelt), Einrichtung mit
// Wiederherstellungsschluessel, neues Geraet (Kopplung/Wiederherstellung/Neu beginnen), Bestaetigung neuer Geraete,
// Einstellungen "Chats & Geraete". Texte wie im Web (GLOSSAR "Chat").

struct ChatsScreen: View {
    @EnvironmentObject var store: Store
    @ObservedObject var chat: ChatModel
    @Binding var openChat: Int?
    @Environment(\.horizontalSizeClass) private var hsc
    @State private var width: CGFloat = 1000
    var body: some View {
        Group {
            switch chat.state {
            case .loading: ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            case .unsupported:
                ContentUnavailableView("Chats", systemImage: "bubble.left.and.bubble.right", description: Text("Dieser Server kennt noch keine Chats."))
            case .off(let m): ContentUnavailableView("Chats", systemImage: "bubble.left.and.bubble.right", description: Text(verbatim: m))
            case .failed(let e):
                ContentUnavailableView {
                    Label("Chats", systemImage: "exclamationmark.triangle")
                } description: { Text("Chats nicht geladen: \(e)") } actions: {
                    Button("Erneut versuchen") { chat.state = .loading; Task { await chat.load() } }.buttonStyle(.bordered)
                }
            case .setup: ScrollView { ChatSetupView(chat: chat).padding() }
            case .newDevice: ScrollView { ChatNewDeviceView(chat: chat).padding() }
            case .ready:
                #if DEBUG
                if UserDefaults.standard.bool(forKey: "hkChatForceSetup") { ScrollView { ChatSetupView(chat: chat).padding() } }   // nur fuer Bilder: nichts wird gesendet
                else if UserDefaults.standard.bool(forKey: "hkChatForceNewDevice") { ScrollView { ChatNewDeviceView(chat: chat).padding() } }
                else { ready }
                #else
                ready
                #endif
            }
        }
        .navigationTitle("Chats")
        #if os(iOS)
        .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { width = $0 }   // iPad hochkant: gestapelt
        #endif
        .onChange(of: chat.closeRequest) { _, r in if let r, openChat == r.id { openChat = nil } }   // geloescht (2.1): schliessen
        .task {
            if chat.state == .loading || chat.state == .ready { await chat.load() }
            #if DEBUG
            let ud = UserDefaults.standard                                                   // nur fuer Tests (Startparameter)
            if chat.state == .setup, let k = ud.string(forKey: "hkChatSetupKey"), ud.string(forKey: "hkChatSetupStep") == nil { try? await chat.setup(recoveryKey: k) }
            if chat.state == .newDevice, ud.bool(forKey: "hkChatLink") { try? await Task.sleep(for: .seconds(1)); await chat.startLink() }
            if chat.state == .newDevice, let k = ud.string(forKey: "hkChatRestoreKey") { try? await chat.restore(recoveryKey: k) }
            #endif
        }
    }

    @ViewBuilder private var ready: some View {
        if hsc == .compact || width < 720 {                                  // schmal (iPhone, iPad hochkant mit Seitenleiste): gestapelt
            ChatListView(chat: chat, selection: $openChat)
                .navigationDestination(item: $openChat) { id in ConversationView(chatID: id, chat: chat).id(id) }
        } else {
            HStack(spacing: 0) {
                ChatListView(chat: chat, selection: $openChat).frame(width: 320)
                Divider()
                if let id = openChat { ConversationView(chatID: id, chat: chat).id(id) }
                else {
                    ContentUnavailableView("Kein Chat gewählt", systemImage: "bubble.left.and.bubble.right",
                                           description: Text("Wähle links einen Chat. Alle Chats sind Ende-zu-Ende-verschlüsselt."))
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                }
            }
        }
    }
}

// MARK: - Liste

struct ChatListView: View {
    @ObservedObject var chat: ChatModel
    @Binding var selection: Int?
    @State private var newChat = false
    @State private var q = ""
    @State private var showArchive = false                    // Bereich "Archiviert" aufgeklappt
    @State private var deleting: ChatSummary?
    var body: some View {
        List(selection: $selection) {
            #if os(macOS)
            // Mac: zweites .searchable im selben Fenster wuerde die Werkzeugleiste sprengen (doppelte Suche) -> Feld in der Liste
            TextField("Chats durchsuchen", text: $q).textFieldStyle(.roundedBorder).padding(.vertical, 4)
            #endif
            if let e = chat.listError { Text("Chats nicht geladen: \(e)").font(.footnote).foregroundStyle(.orange) }
            if chat.chats.isEmpty {
                ContentUnavailableView {
                    Label("Noch keine Chats", systemImage: "bubble.left.and.bubble.right")
                } description: { Text("Deine Chats sind Ende-zu-Ende-verschlüsselt.") } actions: {
                    Button("Neue Unterhaltung") { newChat = true }.prominentGold()
                }
                .listRowBackground(Color.clear)
            }
            let parts = ChatDeleteLogic.split(filtered) { $0.archived }
            ForEach(parts.active) { c in row(c) }
            if !parts.archived.isEmpty {                                     // Archiv (2.1): aufklappbar unten
                let open = showArchive || !q.isEmpty
                Button { withAnimation { showArchive.toggle() } } label: {
                    HStack {
                        Label("Archiviert (\(parts.archived.count))", systemImage: "archivebox")
                        Spacer()
                        let n = ChatDeleteLogic.unread(chat.chats, archived: { $0.archived }, muted: { $0.muted }, unread: { $0.unread }).archive
                        if n > 0 {
                            Text(verbatim: "\(n)").font(.caption2.bold()).foregroundStyle(.secondary)
                                .padding(.horizontal, 6).padding(.vertical, 1).background(Color.secondary.opacity(0.18), in: Capsule())
                                .accessibilityLabel(Text("\(n) ungelesen"))
                        }
                        Image(systemName: "chevron.right").font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                            .rotationEffect(.degrees(open ? 90 : 0)).accessibilityHidden(true)
                    }
                    .foregroundStyle(.secondary).contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityValue(Text(open ? "aufgeklappt" : "zugeklappt"))
                .selectionDisabled()
                if open { ForEach(parts.archived) { c in row(c) } }
            }
        }
        #if os(macOS)
        .listStyle(.inset)
        #endif
        #if os(iOS)
        .searchable(text: $q, prompt: Text("Chats durchsuchen"))
        #endif
        .refreshable { await chat.loadList() }
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button { newChat = true } label: { Label("Neue Unterhaltung", systemImage: "square.and.pencil") }
            }
        }
        .sheet(isPresented: $newChat) { NewChatSheet(chat: chat) { id in selection = id } }
        .sheet(item: $deleting) { c in ChatDeleteSheet(chat: chat, c: c) }
        #if DEBUG
        .task {                                                              // nur fuer Tests/Bilder (Startparameter)
            let ud = UserDefaults.standard
            if ud.bool(forKey: "hkChatArchiveOpen") { showArchive = true }
            if let s = ud.string(forKey: "hkChatArchive"), let cid = Int(s.dropFirst()) {   // "+12" archivieren, "-12" herausholen
                try? await Task.sleep(for: .seconds(2)); await chat.setArchived(cid, s.hasPrefix("+"))
            }
            if let s = ud.string(forKey: "hkChatDeleteSheet"), let cid = Int(s) {
                try? await Task.sleep(for: .seconds(2)); deleting = chat.chats.first { $0.id == cid }
            }
        }
        #endif
    }
    private var filtered: [ChatSummary] { q.isEmpty ? chat.chats : chat.chats.filter { ChatLogic.matches($0.title, q) } }

    /// Zeile mit Wischgesten (iPhone/iPad) und Kontextmenue (Mac, iPad, langes Druecken) - nur, wenn der Server es kann
    @ViewBuilder private func row(_ c: ChatSummary) -> some View {
        if c.canArchive {
            ChatRow(c: c).tag(c.id)
                .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                    Button { deleting = c } label: { Label("Löschen", systemImage: "trash") }.tint(.red)
                    Button { Task { await chat.setArchived(c.id, !c.archived) } } label: {
                        Label(c.archived ? "Aus Archiv holen" : "Archivieren", systemImage: c.archived ? "tray.and.arrow.up" : "archivebox")
                    }.tint(.indigo)
                }
                .contextMenu {
                    Button { Task { await chat.setArchived(c.id, !c.archived) } } label: {
                        Label(c.archived ? "Aus Archiv holen" : "Archivieren", systemImage: c.archived ? "tray.and.arrow.up" : "archivebox")
                    }
                    Divider()
                    Button(role: .destructive) { deleting = c } label: { Label("Chat löschen …", systemImage: "trash") }
                }
        } else {
            ChatRow(c: c).tag(c.id)
        }
    }
}

// MARK: - Chat loeschen (2.1)

/// "Chat loeschen …": Nur fuer mich / Fuer alle (nach delete_for_all, sonst ausgegraut mit Begruendung), bei freien
/// Gruppen "Gruppe ausserdem verlassen", klare Warnung
struct ChatDeleteSheet: View {
    @ObservedObject var chat: ChatModel
    let c: ChatSummary
    @Environment(\.dismiss) private var dismiss
    @State private var scope: ChatDeleteLogic.Scope = .me
    @State private var leave = false
    @State private var busy = false
    @State private var err: String?
    private var forAllKind: ChatDeleteLogic.ForAll { c.deleteForAll ?? (c.kind == "dm" || c.kind == "free" ? .chat : .history) }
    var body: some View {
        NavigationStack {
            Form {
                Section {
                    option(.me, title: String(localized: "Nur für mich"), detail: meDetail, enabled: true)
                    option(.all, title: forAllKind == .chat ? String(localized: "Für alle") : String(localized: "Verlauf für alle löschen"), detail: allDetail, enabled: c.deleteForAll != nil)
                    if c.deleteForAll == nil {
                        Label(deniedText, systemImage: "lock").font(.footnote).foregroundStyle(.secondary)
                    }
                } header: { Text("Für wen löschen?") }
                if ChatDeleteLogic.offerLeave(kind: c.kind, scope: scope) {
                    Section {
                        Toggle("Gruppe außerdem verlassen", isOn: $leave)
                    } footer: { Text("Du bekommst keine neuen Nachrichten mehr.") }
                }
                Section {
                    Label { Text(warning).fontWeight(.semibold) } icon: { Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(.orange) }
                    if let err { Label(err, systemImage: "exclamationmark.circle.fill").font(.footnote).foregroundStyle(.red) }
                }
            }
            .formStyle(.grouped)
            .navigationTitle(Text("„\(c.title)“ löschen?"))
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Abbrechen") { dismiss() }.disabled(busy) }
                ToolbarItem(placement: .destructiveAction) {
                    Button(scope == .me ? String(localized: "Für mich löschen") : forAllKind == .chat ? String(localized: "Für alle löschen") : String(localized: "Verlauf für alle löschen"), role: .destructive) { Task { await run() } }
                        .disabled(busy).foregroundStyle(.red)
                }
            }
            .interactiveDismissDisabled(busy)
        }
        .macFrame(minWidth: 440, idealWidth: 500, minHeight: 440, idealHeight: 520)
        #if DEBUG
        .task {                                                              // nur fuer Bilder/Tests
            let ud = UserDefaults.standard
            if ud.string(forKey: "hkChatDeleteScope") == "all", c.deleteForAll != nil { scope = .all }
            if ud.bool(forKey: "hkChatDeleteLeave") { leave = true }
            if ud.bool(forKey: "hkChatDeleteRun") { try? await Task.sleep(for: .seconds(2)); await run() }
        }
        #endif
    }
    private func option(_ s: ChatDeleteLogic.Scope, title: String, detail: String, enabled: Bool) -> some View {
        Button { if enabled { scope = s; if s == .all { leave = false } } } label: {
            HStack(alignment: .top, spacing: 12) {
                Image(systemName: scope == s ? "largecircle.fill.circle" : "circle").font(.title3)
                    .foregroundStyle(enabled ? (scope == s ? Color.red : Color.secondary) : Color.secondary.opacity(0.5)).accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 3) {
                    Text(verbatim: title).font(.headline)
                    Text(verbatim: detail).font(.callout).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                }
                Spacer(minLength: 0)
            }
            .opacity(enabled ? 1 : 0.5).contentShape(Rectangle())
        }
        .buttonStyle(.plain).disabled(!enabled || busy)
        .accessibilityAddTraits(scope == s ? .isSelected : [])
    }
    private var peerName: String { c.peer?.name ?? c.title }
    private var meDetail: String {
        c.kind == "dm"
            ? String(localized: "Der Verlauf verschwindet nur bei dir, auf allen deinen Geräten. \(peerName) behält ihn. Der Chat erscheint wieder, sobald eine neue Nachricht kommt.")
            : String(localized: "Der Verlauf verschwindet nur bei dir, auf allen deinen Geräten; der Chat wird archiviert. Die anderen behalten ihn.")
    }
    private var allDetail: String {
        if forAllKind == .history { return String(localized: "Alle Nachrichten und Anhänge werden für alle Mitglieder gelöscht. Der Chat selbst bleibt, solange es die Gruppe bzw. Seite gibt.") }
        return c.kind == "dm"
            ? String(localized: "Der Chat wird für \(peerName) und dich gelöscht – mit allen Nachrichten, Anhängen und Anrufen.")
            : String(localized: "Die Gruppe wird für alle Mitglieder gelöscht – mit allen Nachrichten, Anhängen und Anrufen.")
    }
    private var deniedText: String {
        switch ChatDeleteLogic.denied(kind: c.kind, forAll: c.deleteForAll) {
        case .groupAdmin: return String(localized: "Nur Admins dieser Gruppe können sie für alle löschen.")
        case .sagaAdmin: return String(localized: "Den Verlauf für alle löschen können nur Saga-Admins.")
        case .pageOwner: return String(localized: "Den Verlauf für alle löschen kann nur, wem die Seite gehört.")
        default: return String(localized: "Für alle löschen ist in diesem Chat nicht möglich.")
        }
    }
    /// Klare Warnung (wie im Web); "Nur fuer mich": auch der Wiederherstellungsschluessel hilft danach nicht
    private var warning: String {
        scope == .all
            ? String(localized: "Das lässt sich nicht rückgängig machen. Wer Nachrichten schon gesehen hat, kann Kopien davon haben.")
            : String(localized: "Das lässt sich nicht rückgängig machen – auch nicht mit dem Wiederherstellungsschlüssel.")
    }
    private func run() async {
        busy = true; err = nil; defer { busy = false }
        do { try await chat.deleteChat(c, scope: scope, leave: leave); dismiss() }
        catch { err = error.localizedDescription }
    }
}

struct ChatRow: View {
    let c: ChatSummary
    var body: some View {
        HStack(spacing: 10) {
            ChatAvatar(c: c)
            VStack(alignment: .leading, spacing: 2) {
                HStack {
                    Text(verbatim: c.title).font(.body.weight(c.unread > 0 ? .semibold : .regular)).lineLimit(1)
                    if c.muted { Image(systemName: "bell.slash").font(.caption).foregroundStyle(.secondary).accessibilityLabel(Text("Stummschalten")) }
                }
                Text(c.kind == "dm" ? c.kindLabel : "\(c.kindLabel) · \(String(localized: "\(c.memberCount) Personen"))").font(.caption).foregroundStyle(.secondary).lineLimit(1)
            }
            Spacer(minLength: 4)
            VStack(alignment: .trailing, spacing: 4) {
                if c.lastAt > 0 { Text(Date(timeIntervalSince1970: c.lastAt), format: .relative(presentation: .named)).font(.caption2).foregroundStyle(.secondary) }
                if c.unread > 0 {
                    Text(verbatim: "\(c.unread)").font(.caption2.bold()).foregroundStyle(Color("OnAccent"))
                        .padding(.horizontal, 6).padding(.vertical, 1).background(Color.gold, in: Capsule())
                        .accessibilityLabel(Text("\(c.unread) ungelesen"))
                }
            }
        }
        .padding(.vertical, 2)
    }
}

struct ChatAvatar: View {
    let c: ChatSummary
    var body: some View {
        ZStack {
            Circle().fill(Color.gold.opacity(0.16))
            switch c.kind {
            case "dm": AvatarView(name: c.title, avatar: c.peer?.avatar, size: 36)
            case "page": Image(systemName: "doc.text").font(.subheadline)
            default: Image(systemName: "person.2.fill").font(.caption)
            }
        }
        .frame(width: 36, height: 36).accessibilityHidden(true)
    }
}

// MARK: - Neue Unterhaltung

struct NewChatSheet: View {
    @ObservedObject var chat: ChatModel
    var opened: (Int) -> Void
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject var store: Store
    @State private var q = ""
    @State private var users: [(username: String, name: String, ready: Bool, avatar: String?)] = []
    @State private var groups: [String] = []
    @State private var multi = false
    @State private var picked: [(username: String, name: String)] = []
    @State private var groupName = ""
    @State private var err: String?
    @State private var busy = false
    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField(multi ? "Person hinzufügen (ab 3 Zeichen)" : "Person oder Gruppe suchen (ab 3 Zeichen)", text: $q)
                        .autocorrectionDisabled()
                        #if os(iOS)
                        .textInputAutocapitalization(.never)
                        #endif
                    if ShareLogic.searchReady(q) && users.isEmpty && groups.isEmpty { Text("Keine Treffer.").foregroundStyle(.secondary) }
                    ForEach(users, id: \.username) { u in
                        Button { pick(u) } label: {
                            HStack {
                                ShareAvatar(name: u.name, group: false, avatar: u.avatar)
                                VStack(alignment: .leading) {
                                    Text(verbatim: u.name)
                                    Text(verbatim: u.ready ? u.username : u.username + " · " + String(localized: "hat Chats noch nicht eingerichtet")).font(.caption).foregroundStyle(.secondary)
                                }
                                Spacer()
                                if picked.contains(where: { $0.username == u.username }) { Image(systemName: "checkmark").foregroundStyle(Color.gold) }
                            }.contentShape(Rectangle())
                        }.buttonStyle(.plain).disabled(busy)
                    }
                    if !multi {
                        ForEach(groups, id: \.self) { g in
                            Button { Task { await create(["kind": "group", "group": g]) } } label: {
                                HStack {
                                    ShareAvatar(name: g, group: true)
                                    VStack(alignment: .leading) { Text(verbatim: g); Text("Gruppen-Chat für alle in der Gruppe").font(.caption).foregroundStyle(.secondary) }
                                }.contentShape(Rectangle())
                            }.buttonStyle(.plain).disabled(busy)
                        }
                    }
                } footer: { if !multi { Button("+ Gruppe mit mehreren Personen …") { multi = true; q = "" }.font(.footnote) } }
                if multi {
                    Section {
                        TextField("Name der Gruppe", text: $groupName)
                        ForEach(picked, id: \.username) { p in Text(verbatim: p.name) }
                        Button("Gruppe anlegen") { Task { await createFree() } }
                            .disabled(picked.isEmpty || groupName.trimmingCharacters(in: .whitespaces).isEmpty || busy)
                    } header: { Text("Gruppe anlegen") } footer: { Text("Freie Gruppe: ausgewählte Personen, der Name wird verschlüsselt.") }
                }
                if let err { Text(verbatim: err).font(.footnote).foregroundStyle(.red) }
            }
            .formStyle(.grouped)
            .navigationTitle("Neue Unterhaltung")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Abbrechen") { dismiss() } } }
            .task(id: q) { await search() }
        }
        .macFrame(minWidth: 420, idealWidth: 460, minHeight: 460, idealHeight: 560)
    }
    private func search() async {
        guard ShareLogic.searchReady(q) else { users = []; groups = []; return }
        try? await Task.sleep(for: .milliseconds(250))
        guard !Task.isCancelled, let enc = q.trimmingCharacters(in: .whitespaces).addingPercentEncoding(withAllowedCharacters: .alphanumerics),
              let d = try? await store.api("api/chat/people?q=\(enc)"), let j = ShareJSON.obj(d) else { return }
        users = (j["users"] as? [Any] ?? []).compactMap { v in
            guard let u = v as? [String: Any], let n = u["username"] as? String else { return nil }
            return (n, u["name"] as? String ?? n, ShareJSON.bool(u["ready"]), ShareJSON.str(u["avatar"]))
        }
        groups = (j["groups"] as? [Any] ?? []).compactMap { ($0 as? [String: Any])?["name"] as? String }
    }
    private func pick(_ u: (username: String, name: String, ready: Bool, avatar: String?)) {
        if multi {
            if let i = picked.firstIndex(where: { $0.username == u.username }) { picked.remove(at: i) } else { picked.append((u.username, u.name)) }
        } else { Task { await create(["kind": "dm", "user": u.username]) } }
    }
    @discardableResult private func create(_ body: [String: Any]) async -> Int? {
        busy = true; defer { busy = false }
        var b = body; b["device"] = chat.deviceID ?? ""
        do {
            let d = try await store.api("api/chats", method: "POST", body: b)
            guard let id = ShareJSON.int((ShareJSON.obj(d)?["chat"] as? [String: Any])?["id"]) else { return nil }
            await chat.loadList(); dismiss(); opened(id); return id
        } catch { err = error.localizedDescription; return nil }
    }
    private func createFree() async {
        let name = groupName.trimmingCharacters(in: .whitespaces)
        busy = true
        guard let d = try? await store.api("api/chats", method: "POST", body: ["kind": "free", "members": picked.map(\.username), "device": chat.deviceID ?? ""]),
              let id = ShareJSON.int((ShareJSON.obj(d)?["chat"] as? [String: Any])?["id"]) else { busy = false; err = String(localized: "Mit diesem Konto ist kein Chat möglich."); return }
        let conv = ConversationModel(chatID: id, model: chat)
        await conv.loadRecipients()
        _ = await conv.send(text: "", replyTo: nil, files: [], kind: "title", title: name)   // Name verschluesselt wie eine Nachricht
        busy = false
        await chat.loadList(); dismiss(); opened(id)
    }
}

// MARK: - Einrichtung (erstes Geraet) und Neu beginnen

/// Wiederherstellungsschluessel gross, kopieren/teilen
struct RecoveryKeyBox: View {
    let key: String
    @State private var copied = false
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            VStack(spacing: 6) {                                          // 2 Zeilen a 4 Gruppen (nie mitten in einer Gruppe umbrechen)
                ForEach(ChatLogic.recoveryLines(key), id: \.self) { Text(verbatim: $0) }
            }
            .font(.title3.monospaced().weight(.semibold)).minimumScaleFactor(0.6).lineLimit(1).textSelection(.enabled)
                .frame(maxWidth: .infinity).padding(14)
                .background(Color.secondary.opacity(0.1), in: RoundedRectangle(cornerRadius: 10))
                .accessibilityLabel(Text("Wiederherstellungsschlüssel"))
            HStack {
                Button { copyText(key); copied = true } label: { Label(copied ? String(localized: "Kopiert.") : String(localized: "Kopieren"), systemImage: copied ? "checkmark" : "doc.on.doc") }
                ShareLink(item: String(localized: "Saga – Wiederherstellungsschlüssel für Chats") + "\n\n" + key) { Label("Teilen", systemImage: "square.and.arrow.up") }
            }.buttonStyle(.bordered)
        }
    }
}

struct ChatSetupView: View {
    @ObservedObject var chat: ChatModel
    var reset = false
    var done: (() -> Void)? = nil
    @EnvironmentObject var store: Store
    enum Step { case intro, show, confirm }
    @State private var step = Step.intro
    @State private var key = ""
    @State private var last4 = ""
    @State private var busy = false
    @State private var err: String?
    @State private var password = ""
    @State private var reauth: String?
    @State private var stored = false
    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Label(reset ? "Chats neu beginnen" : "Chats einrichten", systemImage: "lock.shield").font(.title2.bold())
            switch step {
            case .intro:
                Text("Saga-Chats sind Ende-zu-Ende-verschlüsselt: Nachrichten und Anhänge werden auf deinen Geräten ver- und entschlüsselt. Der Server – auch der Admin – sieht nur verschlüsselte Daten.")
                Text("Dafür bekommst du gleich einen Wiederherstellungsschlüssel. Damit holst du deine Chats auf ein neues Gerät, wenn kein anderes mehr da ist.")
                Label { Text("Ohne ihn sind deine Nachrichten verloren – auch der Admin kann sie nicht wiederherstellen.").fontWeight(.semibold) }
                    icon: { Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(.orange) }
                if reset {
                    Label("Achtung: Neu beginnen erzeugt neue Schlüssel. Bisherige Nachrichten sind dann für dich nicht mehr lesbar, deine Gesprächspartner sehen eine geänderte Sicherheitsnummer.", systemImage: "exclamationmark.triangle.fill")
                        .foregroundStyle(.orange)
                }
                Button("Schlüssel erzeugen") { key = ChatCrypto.newRecoveryKey(); step = .show }.prominentGold()
            case .show:
                Text("Dein Wiederherstellungsschlüssel").font(.headline)
                RecoveryKeyBox(key: key)
                Text("Schreibe ihn ab oder speichere ihn an einem sicheren Ort (z. B. im Passwort-Manager). Saga zeigt ihn nur jetzt.").font(.callout)
                Toggle(isOn: $stored) { Text("Ich habe den Schlüssel sicher aufbewahrt") }
                    .toggleStyle(CheckboxLikeToggleStyle()).id("stored")
                Button("Weiter") { step = .confirm }.prominentGold().disabled(!stored)
            case .confirm:
                Text("Wiederherstellungsschlüssel bestätigen").font(.headline)
                Text("Gib zur Bestätigung die letzten vier Zeichen deines Wiederherstellungsschlüssels ein.")
                TextField("Letzte vier Zeichen", text: $last4).font(.title3.monospaced()).textFieldStyle(.roundedBorder).frame(maxWidth: 220)
                    .autocorrectionDisabled()
                    #if os(iOS)
                    .textInputAutocapitalization(.characters)
                    #endif
                if reauth == "password" && ReauthLogic.nativeInput(methods: chat.me.reauthMethods) {
                    // 2.1: mit Authenticator-App geht auch der 6-stellige Code; Passkey nur im Browser (keine Associated Domains)
                    let totp = chat.me.reauthMethods.contains("totp")
                    if totp { Text("Zur Sicherheit: Passwort oder Code aus der Authenticator-App").font(.callout) }
                    SecureField(totp ? String(localized: "Passwort oder Code") : String(localized: "Zur Sicherheit: dein Passwort"), text: $password)
                        .textFieldStyle(.roundedBorder).frame(maxWidth: 320)
                        .textContentType(ReauthLogic.oneTimeCodeField(methods: chat.me.reauthMethods) ? .oneTimeCode : .password)   // Code nicht als Passwort sichern
                    if chat.me.reauthMethods.contains("passkey") {
                        Text("Mit Passkey bestätigen geht nur im Browser.").font(.footnote).foregroundStyle(.secondary)
                    }
                } else if reauth == "login" || reauth == "password" {
                    // SSO- bzw. reine Passkey-Konten: "Neu beginnen" im Browser (die Browser-Sitzung bekommt die 5 Minuten, nicht die App)
                    Text("Zur Sicherheit bestätigst du das im Browser: Dort „Chats“ öffnen und neu beginnen.").foregroundStyle(.orange)
                    if let u = URL(string: AppConfig.server.absoluteString + "/chats") { Link(destination: u) { Label("Im Browser öffnen", systemImage: "safari") }.buttonStyle(.bordered) }
                }
                HStack {
                    Button("Zurück") { step = .show }.buttonStyle(.bordered)
                    Button(busy ? String(localized: "Schlüssel werden gesichert …") : String(localized: "Einrichten")) { Task { await run() } }
                        .prominentGold().disabled(busy || last4.count < 4)
                }
            }
            if let err { Label(err, systemImage: "exclamationmark.circle.fill").font(.footnote).foregroundStyle(.red) }
        }
        .frame(maxWidth: 560, alignment: .leading).frame(maxWidth: .infinity)
        .task {
            if reset { reauth = chat.me.reauth }
            #if DEBUG
            if let k = UserDefaults.standard.string(forKey: "hkChatSetupKey"), let s = UserDefaults.standard.string(forKey: "hkChatSetupStep") {   // nur fuer Tests
                key = k; step = s == "confirm" ? .confirm : s == "intro" ? .intro : .show
                if s == "confirm" { last4 = String(k.suffix(4)) }
            }
            #endif
        }
    }
    private func run() async {
        guard ChatLogic.confirmLast4(last4, key: key) else { err = String(localized: "Das passt nicht – bitte genau die letzten vier Zeichen."); return }
        busy = true; err = nil; defer { busy = false }
        do {
            try await chat.setup(recoveryKey: key, reset: reset, auth: reset && !password.isEmpty ? ReauthLogic.body(password, methods: chat.me.reauthMethods) : nil)
            chat.toast = String(localized: "Chats sind eingerichtet.")
            done?()
        } catch let r as ChatReauth { reauth = r.kind; err = r.message }
        catch { err = error.localizedDescription }
    }
}

// MARK: - Neues Geraet

struct ChatNewDeviceView: View {
    @ObservedObject var chat: ChatModel
    @State private var mode = 0                    // 0 Auswahl, 1 Kopplung, 2 Schluessel, 3 Neu beginnen
    @State private var rk = ""
    @State private var busy = false
    @State private var err: String?
    @State private var askReset = false
    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Label("Chats auf diesem Gerät", systemImage: "lock.shield").font(.title2.bold())
                #if DEBUG
                .task {
                    let m = UserDefaults.standard.integer(forKey: "hkChatNewDeviceMode"); if m == 2 || m == 3 { mode = m }   // nur fuer Bilder
                    if UserDefaults.standard.bool(forKey: "hkChatStartLink") { mode = 1; await chat.startLink() }   // nur fuer Tests: Kopplung anfragen
                }
                #endif
            switch chat.linking != nil && mode == 0 ? 1 : mode {          // Kopplung laeuft (auch von aussen gestartet)
            case 1: linkView
            case 2: restoreView
            case 3: ChatSetupView(chat: chat, reset: true)
            default:
                Text("Deine Chats sind Ende-zu-Ende-verschlüsselt. Damit dieses Gerät sie lesen kann, braucht es deine Schlüssel – wähle einen Weg:")
                choice("Mit anderem Gerät bestätigen", "Auf einem Gerät, auf dem deine Chats schon laufen, einen Code bestätigen.", "iphone.and.arrow.forward") {
                    mode = 1; Task { await chat.startLink() }
                }
                choice("Wiederherstellungsschlüssel eingeben", "Der Schlüssel, den du bei der Einrichtung bekommen hast.", "key") { mode = 2 }
                choice("Neu beginnen", "Beides nicht mehr vorhanden? Neue Schlüssel – ältere Nachrichten sind danach nicht mehr lesbar.", "arrow.counterclockwise", warn: true) { askReset = true }
            }
            if let t = chat.toast { Label(t, systemImage: "checkmark.circle").foregroundStyle(.green) }
        }
        .frame(maxWidth: 560, alignment: .leading).frame(maxWidth: .infinity)
        .alert("Chats neu beginnen?", isPresented: $askReset) {
            Button("Neu beginnen", role: .destructive) { mode = 3 }
            Button("Abbrechen", role: .cancel) {}
        } message: { Text("Neue Schlüssel: Bisherige Nachrichten sind dann für dich nicht mehr lesbar, deine anderen Geräte werden abgemeldet und deine Gesprächspartner sehen eine geänderte Sicherheitsnummer.") }
    }
    private func choice(_ t: LocalizedStringKey, _ s: LocalizedStringKey, _ icon: String, warn: Bool = false, _ a: @escaping () -> Void) -> some View {
        Button(action: a) {
            HStack(spacing: 12) {
                Image(systemName: icon).font(.title2).frame(width: 36).foregroundStyle(warn ? Color.red : Color.gold).accessibilityHidden(true)   // Deko: VoiceOver liest den Text
                VStack(alignment: .leading, spacing: 2) {
                    Text(t).font(.headline).foregroundStyle(warn ? Color.red : Color.primary)
                    Text(s).font(.callout).foregroundStyle(.secondary)
                }
                Spacer()
                Image(systemName: "chevron.right").foregroundStyle(.tertiary).accessibilityHidden(true)
            }
            .padding(14).background(Color.secondary.opacity(0.08), in: RoundedRectangle(cornerRadius: 12)).contentShape(Rectangle())
        }.buttonStyle(.plain)
    }
    @ViewBuilder private var linkView: some View {
        if let l = chat.linking {
            Text("Öffne auf einem Gerät, auf dem deine Chats schon laufen, die Seite „Chats“. Dort erscheint diese Anfrage – bestätige sie, wenn derselbe Code angezeigt wird:")
            LinkCodeView(code: l.code)
            HStack(spacing: 8) { ProgressView().controlSize(.small); Text("Die Anfrage gilt 10 Minuten.").font(.footnote).foregroundStyle(.secondary) }
            Button("Abbrechen") { chat.cancelLink(); mode = 0 }.buttonStyle(.bordered)
        } else if let e = chat.linkError {
            Label(e, systemImage: "exclamationmark.triangle").foregroundStyle(.orange)
            HStack {
                Button("Erneut versuchen") { Task { await chat.startLink() } }.buttonStyle(.bordered)
                Button("Zurück") { chat.linkError = nil; mode = 0 }.buttonStyle(.bordered)
            }
        } else { HStack { ProgressView(); Text("Anfrage wird gesendet …") } }
    }
    @ViewBuilder private var restoreView: some View {
        Text("Wiederherstellungsschlüssel eingeben").font(.headline)
        TextField(String(localized: "Wiederherstellungsschlüssel"), text: $rk, prompt: Text(verbatim: "XXXX-XXXX-XXXX-XXXX-…"), axis: .vertical).font(.body.monospaced()).textFieldStyle(.roundedBorder)
            .autocorrectionDisabled()
            #if os(iOS)
            .textInputAutocapitalization(.characters)
            #endif
        if !rk.isEmpty && ChatCrypto.normRecovery(rk) == nil { Text("Ein Wiederherstellungsschlüssel hat 32 Zeichen (A–Z, 2–7).").font(.footnote).foregroundStyle(.secondary) }
        HStack {
            Button("Zurück") { mode = 0; err = nil }.buttonStyle(.bordered)
            Button(busy ? String(localized: "Prüfe …") : String(localized: "Entsperren")) {
                Task { busy = true; err = nil; do { try await chat.restore(recoveryKey: rk) } catch { err = error.localizedDescription }; busy = false }
            }.prominentGold().disabled(busy || ChatCrypto.normRecovery(rk) == nil)
        }
        if let err { Label(err, systemImage: "exclamationmark.circle.fill").font(.footnote).foregroundStyle(.red) }
    }
}

/// Kopplungs-Code gross: 4 Gruppen a 5 Ziffern in einer Zeile, passt das nicht: 2 Zeilen a 2 Gruppen
struct LinkCodeView: View {
    let code: String
    var body: some View {
        ViewThatFits(in: .horizontal) {
            lines(oneLine: true)
            lines(oneLine: false)
        }
        .font(.system(.title, design: .monospaced).weight(.semibold))
        .padding(.horizontal, 12).frame(maxWidth: .infinity).padding(.vertical, 16)
        .background(Color.gold.opacity(0.12), in: RoundedRectangle(cornerRadius: 12))
        .textSelection(.enabled)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(verbatim: ChatLogic.digitsOnly(code).map { String($0) }.joined(separator: " ")))
    }
    private func lines(oneLine: Bool) -> some View {
        VStack(spacing: 6) { ForEach(ChatLogic.linkCodeLines(code, oneLine: oneLine), id: \.self) { Text(verbatim: $0).fixedSize() } }
    }
}

/// Auf einem vorhandenen Geraet: neues Geraet bestaetigen (Code vergleichen!)
struct LinkApproveSheet: View {
    @ObservedObject var chat: ChatModel
    let req: ChatLinkRequest
    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Label("Neues Gerät bestätigen", systemImage: "iphone.and.arrow.forward").font(.title3.bold())
            Text("„\(req.name.isEmpty ? String(localized: "Unbekanntes Gerät") : req.name)“ möchte auf deine Chats zugreifen.")
            Text("Bestätige nur, wenn auf dem neuen Gerät genau dieser Code steht:").foregroundStyle(.secondary)
            LinkCodeView(code: chat.code(for: req))
            HStack {
                Button("Ablehnen", role: .destructive) { Task { await chat.decline(req) } }.buttonStyle(.bordered)
                Spacer()
                Button("Code stimmt – bestätigen") { Task { await chat.approve(req) } }.prominentGold()
            }.controlSize(.large)
        }
        .padding(22)
        .frame(maxHeight: .infinity, alignment: .top)                   // Inhalt oben, nicht in der Mitte des Blatts
        .macFrame(minWidth: 460, idealWidth: 520)
        #if os(iOS)
        .presentationDetents([.medium, .large])
        .presentationBackground(Color(uiColor: .systemBackground))      // deckend (dunkel schien der Hintergrund durch)
        #endif
        .interactiveDismissDisabled()
    }
}

// MARK: - Einstellungen: Chats & Geraete

struct ChatDevicesSection: View {
    @EnvironmentObject var store: Store
    @ObservedObject var chat: ChatModel
    @State private var askRemove: ChatDeviceInfo?
    @State private var newKey: String?
    @State private var askNewKey = false
    @State private var msg: String?
    var body: some View {
        if chat.state == .ready || chat.state == .newDevice || chat.state == .setup {
            Section {
                if chat.state == .setup { Text("Chats sind noch nicht eingerichtet.").foregroundStyle(.secondary) }
                ForEach(chat.me.devices.filter { !$0.revoked }) { d in
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(verbatim: d.name + (d.id == chat.deviceID ? " · " + String(localized: "dieses Gerät") : ""))
                            Text(verbatim: [d.created.map { String(localized: "angemeldet \(Date(timeIntervalSince1970: $0).formatted(date: .numeric, time: .omitted))") },
                                            d.lastSeen.map { String(localized: "zuletzt \(Date(timeIntervalSince1970: $0).formatted(.relative(presentation: .named)))") }]
                                .compactMap { $0 }.joined(separator: " · ")).font(.caption).foregroundStyle(.secondary)
                        }
                        Spacer()
                        if d.id != chat.deviceID {                          // dieses Geraet ist nicht loeschbar (sonst sperrt man sich aus)
                            Button(role: .destructive) { askRemove = d } label: { Image(systemName: "trash") }
                                .buttonStyle(.borderless).help(Text("Entfernen")).accessibilityLabel(Text("Entfernen"))
                        }
                    }
                }
                if chat.state == .ready {
                    if let k = newKey {
                        Text("Neuer Wiederherstellungsschlüssel").font(.headline)
                        RecoveryKeyBox(key: k)
                        HStack {
                            Button("Übernehmen") { Task { do { try await chat.applyRecoveryKey(k); newKey = nil; msg = String(localized: "Neuer Wiederherstellungsschlüssel gilt ab jetzt.") } catch { msg = error.localizedDescription } } }
                                .prominentGold()
                            Button("Abbrechen") { newKey = nil }
                        }
                    } else {
                        Button("Neuen Wiederherstellungsschlüssel erzeugen") { askNewKey = true }
                        Text("Verloren oder womöglich gesehen? Erzeuge einen neuen – der alte gilt dann nicht mehr. Deine Schlüssel und Nachrichten bleiben gleich.")
                            .font(.footnote).foregroundStyle(.secondary)
                    }
                }
                if let msg { Text(verbatim: msg).font(.footnote).foregroundStyle(.secondary) }
            } header: { Text("Chats & Geräte").id("chats") } footer: {
                Text("Private Schlüssel verlassen dieses Gerät nie unverschlüsselt. Auf dem Server liegen nur öffentliche Schlüssel, verschlüsselte Nachrichten und dein mit dem Wiederherstellungsschlüssel verschlüsseltes Schlüssel-Bündel. Vergleiche Sicherheitsnummern, um sicherzugehen, dass niemand dazwischen sitzt.")
            }
            .alert("Gerät entfernen?", isPresented: Binding(get: { askRemove != nil }, set: { if !$0 { askRemove = nil } }), presenting: askRemove) { d in
                Button("Entfernen", role: .destructive) { Task { do { try await chat.removeDevice(d.id) } catch { msg = error.localizedDescription } } }
                Button("Abbrechen", role: .cancel) {}
            } message: { d in
                Text(d.id == chat.deviceID ? "Die Schlüssel werden von diesem Gerät gelöscht. Danach brauchst du ein anderes Gerät oder den Wiederherstellungsschlüssel."
                     : "Das Gerät bekommt keine neuen Nachrichten mehr und kann vorhandene nicht mehr abrufen.")
            }
            .alert("Neuen Wiederherstellungsschlüssel erzeugen?", isPresented: $askNewKey) {
                Button("Erzeugen") { newKey = ChatCrypto.newRecoveryKey() }
                Button("Abbrechen", role: .cancel) {}
            } message: { Text("Der bisherige Schlüssel funktioniert danach nicht mehr.") }
        }
    }
}

// MARK: - Sicherheitsnummer

func qrImage(_ text: String) -> Image? {
    let f = CIFilter.qrCodeGenerator()
    f.message = Data(text.utf8); f.correctionLevel = "M"
    guard let out = f.outputImage?.transformed(by: CGAffineTransform(scaleX: 8, y: 8)),
          let cg = CIContext().createCGImage(out, from: out.extent) else { return nil }
    return Image(decorative: cg, scale: 1).interpolation(.none)
}

struct SafetyNumberSheet: View {
    @ObservedObject var conv: ConversationModel
    let peer: ChatMember
    @Environment(\.dismiss) private var dismiss
    @State private var number: String?
    @State private var verified = false
    @State private var scanning = false
    @State private var scanResult: Bool?
    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    if let n = number {
                        let rows = ChatLogic.safetyRows(n)                     // 3 Zeilen x 4 Gruppen a 5 Ziffern (wie Web)
                        Grid(horizontalSpacing: 14, verticalSpacing: 8) {
                            ForEach(rows.indices, id: \.self) { r in
                                GridRow { ForEach(rows[r], id: \.self) { Text(verbatim: $0).font(.title3.monospaced()).fixedSize() } }
                            }
                        }
                        .frame(maxWidth: .infinity).padding().background(Color.secondary.opacity(0.08), in: RoundedRectangle(cornerRadius: 12))
                        .textSelection(.enabled)
                        if let img = qrImage(ChatLogic.safetyQR(n)) {
                            img.resizable().scaledToFit().frame(width: 180, height: 180).padding(10).background(.white, in: RoundedRectangle(cornerRadius: 10))
                                .frame(maxWidth: .infinity).accessibilityLabel(Text("Sicherheitsnummer"))
                        }
                        Text("Vergleiche diese Nummer mit \(peer.name) – persönlich, per Anruf oder auf deren Gerät. Stimmt sie überein, liest niemand mit. Sie ist auf beiden Seiten gleich.")
                            .foregroundStyle(.secondary)
                        if let ok = scanResult {
                            Label(ok ? String(localized: "Stimmt überein.") : String(localized: "Stimmt nicht überein!"), systemImage: ok ? "checkmark.seal.fill" : "xmark.octagon.fill")
                                .foregroundStyle(ok ? .green : .red)
                        }
                        ViewThatFits(in: .horizontal) {                         // schmal: untereinander
                            HStack { safetyButtons }
                            VStack(alignment: .leading, spacing: 10) { safetyButtons }
                        }
                    } else {
                        Text("\(peer.name) hat Chats noch nicht eingerichtet.").foregroundStyle(.secondary)
                    }
                }.padding()
            }
            .navigationTitle(Text("Sicherheitsnummer"))
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Fertig") { dismiss() } } }
        }
        .macFrame(minWidth: 460, idealWidth: 520, minHeight: 520)
        .task {
            guard let k = conv.model.keys, let p = await conv.identity(of: peer.username) else { return }
            number = ChatCrypto.safetyNumber(uidA: conv.model.me.uid, identA: k.identPK, uidB: peer.username, identB: p)
            verified = ChatTrustStore.known(conv.model.me.uid, peer.username)?.verified ?? false
        }
        #if os(iOS)
        .sheet(isPresented: $scanning) {
            QRScannerSheet { text in scanning = false; scanResult = ChatLogic.safetyFromQR(text).map { $0 == ChatLogic.digitsOnly(number ?? "") } ?? false }
        }
        #endif
    }
}

extension SafetyNumberSheet {
    @ViewBuilder var safetyButtons: some View {
        if verified { Label("überprüft", systemImage: "checkmark.seal.fill").foregroundStyle(.green) }
        else { Button("Als überprüft markieren") { ChatTrustStore.verify(conv.model.me.uid, peer.username); conv.acknowledge(peer.username); verified = true }.prominentGold() }
        #if os(iOS)
        Button { scanning = true } label: { Label("QR-Code scannen", systemImage: "qrcode.viewfinder") }.buttonStyle(.bordered)
        #endif
    }
}

extension Array { subscript(safe i: Int) -> Element? { indices.contains(i) ? self[i] : nil } }

// MARK: - Seitenleiste und Bestaetigungsdialog

/// "Chats" in der Seitenleiste mit Ungelesen-Zahl (nur, wenn der Server Chats kann)
struct ChatsSidebarRow: View {
    @ObservedObject var chat: ChatModel
    var body: some View {
        switch chat.state {
        case .unsupported, .off: EmptyView()
        default: Label("Chats", systemImage: "bubble.left.and.bubble.right").badge(chat.unread)
        }
    }
}

/// Kopplungsanfrage eines neuen Geraets: Dialog, sobald chat.link kommt; Hinweise (Toast) unten
struct ChatApprovalPresenter: ViewModifier {
    @ObservedObject var chat: ChatModel
    func body(content: Content) -> some View {
        content
            .sheet(item: $chat.approval) { r in LinkApproveSheet(chat: chat, req: r) }
            .overlay(alignment: .bottom) {
                if let t = chat.toast {
                    Text(verbatim: t).font(.subheadline).padding(.horizontal, 16).padding(.vertical, 10)
                        .background(.regularMaterial, in: Capsule()).padding(.bottom, 24)
                        .task { try? await Task.sleep(for: .seconds(4)); chat.toast = nil }
                }
            }
    }
}
