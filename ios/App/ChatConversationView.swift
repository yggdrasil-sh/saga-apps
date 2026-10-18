import SwiftUI
import PhotosUI
import UniformTypeIdentifiers
import ImageIO

// Unterhaltung: Blasen (eigene rechts), Antworten (Zitat), Anhaenge (Bilder inline, sonst teilen), @-Seiten,
// "tippt …", Menue (Sicherheitsnummer, Mitglieder, Stummschalten, Aufbewahrung, Umbenennen/Verlassen), lokale Suche.

struct ConversationView: View {
    let chatID: Int
    @ObservedObject var chat: ChatModel
    @StateObject private var conv: ConversationModel
    @EnvironmentObject var store: Store
    @EnvironmentObject var nav: NavModel
    @State private var text = ""
    @State private var replyTo: ChatMessage?
    @State private var files: [ChatPendingFile] = []
    @State private var pickPhotos = false
    @State private var photoItems: [PhotosPickerItem] = []
    @State private var pickFile = false
    @State private var pickPage = false
    @State private var pickImmich = false
    @State private var immichOn = false              // "Aus Immich" nur, wenn Immich fuer mich eingerichtet ist
    @State private var safetyPeer: ChatMember?
    @State private var showMembers = false
    @State private var askDelete: ChatMessage?
    @State private var askLeave = false
    @State private var rename = false
    @State private var newName = ""
    @State private var search = ""
    @State private var searching = false
    @State private var askAIOn = false                    // KI im Chat erlauben? (Rueckfrage)
    @State private var deleting: ChatSummary?             // "Chat loeschen …" (2.1)
    @State private var perfScroll: String?                // nur fuer Tests (-hkChatPerf): automatisch scrollen
    @FocusState private var focused: Bool

    init(chatID: Int, chat: ChatModel) {
        self.chatID = chatID; self.chat = chat
        _conv = StateObject(wrappedValue: ConversationModel(chatID: chatID, model: chat))
    }

    var body: some View {
        VStack(spacing: 0) {
            if conv.notFound {
                ContentUnavailableView("Chat", systemImage: "bubble.left.and.exclamationmark.bubble.right", description: Text("Diesen Chat gibt es nicht (mehr) oder du gehörst nicht mehr dazu."))
            } else {
                banners
                messagesList
                composer
            }
        }
        .navigationTitle(conv.info?.title ?? "")
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #else
        .navigationSubtitle(conv.info.map { $0.kind == "dm" ? $0.kindLabel : "\($0.kindLabel) · \(String(localized: "\($0.memberCount) Personen"))" } ?? "")
        #endif
        .toolbar {
            // Anrufen (2.1): nur im Direkt-Chat
            ToolbarItemGroup(placement: .primaryAction) {
                if let i = conv.info, i.kind == "dm", let p = i.peer, store.calls.enabled {
                    Button { store.calls.start(chatID: chatID, peer: p, video: false) } label: { Label("Anrufen", systemImage: "phone") }
                        .help(Text("Anrufen"))
                    Button { store.calls.start(chatID: chatID, peer: p, video: true) } label: { Label("Videoanruf", systemImage: "video") }
                        .help(Text("Videoanruf"))
                }
            }
            // Suche im Chat steckt im Menue (Befehl-F): eine zweite Lupe neben dem Suchfeld der Liste/Seitenleiste verwirrt
            ToolbarItem(placement: .primaryAction) { menu }
        }
        .task {
            chat.open = conv
            #if DEBUG
            ChatPerf.begin()                                                                // nur fuer Tests: Messung
            #endif
            await conv.load()
            #if DEBUG
            if ChatPerf.on { await perfRun() }
            if UserDefaults.standard.bool(forKey: "hkChatSafety"), let p = conv.info?.peer { try? await Task.sleep(for: .seconds(2.5)); safetyPeer = p }   // nur fuer Tests
            if UserDefaults.standard.bool(forKey: "hkChatMembers") { try? await Task.sleep(for: .seconds(2.5)); showMembers = true }
            if let t = UserDefaults.standard.string(forKey: "hkChatSend") {                 // nur fuer Tests: Nachricht (+ Bild) senden
                var f: [ChatPendingFile] = []
                if UserDefaults.standard.bool(forKey: "hkChatSendImage"), let png = Self.testImage() { f.append(ChatPendingFile(name: "garten.png", mime: "image/png", data: png)) }
                let reply = UserDefaults.standard.bool(forKey: "hkChatReply") ? conv.messages.last(where: { !$0.mine })?.id : nil
                if await conv.send(text: t, replyTo: reply, files: f), conv.aiAllowed, AIChatLogic.mentions(t, name: conv.aiName), let qid = conv.lastSentID {
                    conv.askAI(question: t, qid: qid)                                       // wie sendNow: "@Name" fragt die KI
                }
            }
            if let t = UserDefaults.standard.string(forKey: "hkChatDraft") { try? await Task.sleep(for: .seconds(1)); text = t }   // nur fuer Tests: Entwurf (@-Vorschlag)
            if UserDefaults.standard.bool(forKey: "hkChatAIConfirm") { try? await Task.sleep(for: .seconds(2)); askAIOn = true }   // nur fuer Bilder: Rueckfrage zeigen
            if let q = UserDefaults.standard.string(forKey: "hkChatSearch") { searching = true; search = q }
            if UserDefaults.standard.bool(forKey: "hkChatFocus") { try? await Task.sleep(for: .seconds(1)); focused = true }   // nur fuer Tests: Einsetzen
            if UserDefaults.standard.bool(forKey: "hkChatPendingImage"), let png = Self.testImage() {   // nur fuer Tests: Anhang vorbereiten, nicht senden
                try? await Task.sleep(for: .seconds(2)); files.append(ChatPendingFile(name: "garten.png", mime: "image/png", data: png))
            }
            if UserDefaults.standard.bool(forKey: "hkChatImmich") { try? await Task.sleep(for: .seconds(2)); pickImmich = true }   // nur fuer Tests: Immich-Auswahl
            let after = UserDefaults.standard.double(forKey: "hkChatSendPendingAfter")        // nur fuer Tests: Anhaenge nach n s senden
            if after > 0 { try? await Task.sleep(for: .seconds(after)); if text.isEmpty { text = "Eingesetzt" }; sendNow() }
            if UserDefaults.standard.bool(forKey: "hkChatAck") { for u in conv.changedPeers { conv.acknowledge(u) } }   // nur fuer Tests: "Verstanden"
            #endif
        }
        .onDisappear { if chat.open === conv { chat.open = nil } }
        .onChange(of: conv.purged) { _, _ in text = ""; files = []; replyTo = nil; askDelete = nil }   // Chat geloescht: Entwurf weg
        .sheet(item: $deleting) { c in ChatDeleteSheet(chat: chat, c: c) }
        .modifier(ChatMediaInput(files: $files, pickImmich: $pickImmich, immichOn: $immichOn, enabled: !conv.notFound, maxBytes: chat.me.fileMax))
        .sheet(item: $safetyPeer) { p in SafetyNumberSheet(conv: conv, peer: p) }
        .sheet(isPresented: $showMembers) { MembersSheet(conv: conv) { p in showMembers = false; safetyPeer = p } }
        .sheet(isPresented: $pickPage) { PagePickSheet { id, title in text += (text.isEmpty || text.hasSuffix(" ") ? "" : " ") + "[[note:\(id)]] "; conv.notes[id] = (title, "") } }
        .photosPicker(isPresented: $pickPhotos, selection: $photoItems, maxSelectionCount: 5, matching: .images)
        .onChange(of: photoItems) { _, items in
            Task {
                for it in items {
                    if let d = try? await it.loadTransferable(type: Data.self) {
                        let jpg = Self.jpeg(d)
                        files.append(ChatPendingFile(name: "bild-\(files.count + 1).\(jpg.ext)", mime: jpg.mime, data: jpg.data))
                    }
                }
                photoItems = []
            }
        }
        .fileImporter(isPresented: $pickFile, allowedContentTypes: [.item], allowsMultipleSelection: true) { r in
            for u in (try? r.get()) ?? [] {
                let ok = u.startAccessingSecurityScopedResource(); defer { if ok { u.stopAccessingSecurityScopedResource() } }
                if let d = try? Data(contentsOf: u) {
                    files.append(ChatPendingFile(name: u.lastPathComponent, mime: UTType(filenameExtension: u.pathExtension)?.preferredMIMEType ?? "application/octet-stream", data: d))
                }
            }
        }
        .alert("Nachricht für alle löschen?", isPresented: Binding(get: { askDelete != nil }, set: { if !$0 { askDelete = nil } }), presenting: askDelete) { m in
            Button("Löschen", role: .destructive) { Task { await conv.delete(m) } }
            Button("Abbrechen", role: .cancel) {}
        } message: { _ in Text("Die Nachricht und ihre Anhänge werden vom Server gelöscht. Wer sie schon gelesen hat, kann eine Kopie haben.") }
        .alert(Text("\(conv.aiName) in diesem Chat erlauben?"), isPresented: $askAIOn) {
            Button("Erlauben") { Task { await conv.setAI(true) } }
            Button("Abbrechen", role: .cancel) {}
        } message: {
            Text("Dann kann jede Person im Chat \(conv.aiName) mit @\(conv.aiName) fragen. Nur diese Nachricht und bis zu \(AIChatLogic.historyMax) Nachrichten davor gehen im Klartext an die KI (\(aiHost)) – der Server speichert sie nicht. Alle Mitglieder sehen einen Hinweis.")
        }
        .alert("Gruppe verlassen?", isPresented: $askLeave) {
            Button("Verlassen", role: .destructive) { Task { if await conv.leave() { nav.back() } } }
            Button("Abbrechen", role: .cancel) {}
        } message: { Text("Du bekommst keine neuen Nachrichten mehr.") }
        .alert("Gruppe umbenennen", isPresented: $rename) {
            TextField("Name der Gruppe", text: $newName)
            Button("Speichern") { let n = newName.trimmingCharacters(in: .whitespaces); if !n.isEmpty { Task { await conv.send(text: "", replyTo: nil, files: [], kind: "title", title: n) } } }
            Button("Abbrechen", role: .cancel) {}
        } message: { Text("Der Name wird wie eine Nachricht verschlüsselt.") }
    }

    // MARK: Hinweise oben

    @ViewBuilder private var banners: some View {
        if searching {
            HStack {
                Image(systemName: "magnifyingglass").foregroundStyle(.secondary)
                TextField("Suchen (nur auf diesem Gerät, in geladenen Nachrichten)", text: $search).textFieldStyle(.plain)
                let n = search.isEmpty ? 0 : conv.messages.filter { ChatLogic.matches($0.text, search) }.count
                if !search.isEmpty { Text("\(n) Treffer").font(.caption).foregroundStyle(.secondary) }
                Button { searching = false; search = "" } label: { Image(systemName: "xmark.circle.fill").foregroundStyle(.secondary) }
                    .buttonStyle(.plain).help(Text("Suche schließen")).accessibilityLabel(Text("Suche schließen"))
            }
            .padding(10).background(.bar)
            Divider()
        }
        if conv.aiAllowed {                                                  // KI im Chat (2.1): sichtbar, was an die KI geht
            HStack(alignment: .top, spacing: 8) {
                AIAvatarView(persona: store.aiPersona, size: 20)
                Text("\(conv.aiName) ist in diesem Chat erlaubt – nur auf Zuruf: Nachrichten mit @\(conv.aiName) und bis zu \(AIChatLogic.historyMax) Nachrichten davor gehen im Klartext an die KI (\(aiHost)). Alles andere bleibt Ende-zu-Ende-verschlüsselt.")
                    .font(.caption).foregroundStyle(.secondary).lineLimit(4)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding(.horizontal, 12).padding(.vertical, 8).frame(maxWidth: .infinity, alignment: .leading)
            .background(Color.gold.opacity(0.10))
            .accessibilityElement(children: .combine)
            Divider()
        }
        ForEach(conv.changedPeers, id: \.self) { u in
            let name = conv.info?.members.first { $0.username == u }?.name ?? u
            VStack(alignment: .leading, spacing: 8) {
                Label("Die Sicherheitsnummer von \(name) hat sich geändert – z. B. nach „Neu beginnen“ oder bei einem neuen Konto. Prüfe sie, bevor du Vertrauliches schreibst.", systemImage: "exclamationmark.shield.fill")
                    .font(.callout)
                HStack {
                    Button("Sicherheitsnummer") { safetyPeer = ChatMember(username: u, name: name, me: false) }.buttonStyle(.bordered)
                    Button("Verstanden") { conv.acknowledge(u) }.buttonStyle(.bordered)
                }.controlSize(.small)
            }
            .padding(12).frame(maxWidth: .infinity, alignment: .leading).background(Color.orange.opacity(0.14))
        }
    }

    // MARK: Nachrichten

    private var messagesList: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(spacing: 6) {
                    if conv.more { Button("Ältere Nachrichten laden") { Task { await conv.loadOlder() } }.font(.footnote).padding(.vertical, 6) }
                    Label("Nachrichten in diesem Chat sind Ende-zu-Ende-verschlüsselt.", systemImage: "lock.fill")
                        .font(.caption).foregroundStyle(.secondary).padding(.vertical, 8).frame(maxWidth: .infinity)
                    if conv.info?.kind != "dm" { Text("Wer neu dazukommt, sieht nur Nachrichten ab dem Beitritt.").font(.caption2).foregroundStyle(.tertiary) }
                    if let i = conv.info, ChatDeleteLogic.showCleared(clearedSeq: i.clearedSeq, moreOlder: conv.more) {   // 2.1: statt "nicht lesbar"
                        Label("Der bisherige Verlauf wurde gelöscht.", systemImage: "trash").font(.caption).foregroundStyle(.secondary).padding(.vertical, 4)
                    }
                    ForEach(Array(shown.enumerated()), id: \.element.id) { i, m in
                        if i == 0 || !Calendar.current.isDate(shown[i - 1].date, inSameDayAs: m.date) {
                            Text(m.date.sagaFormatted(date: .complete, time: .omitted)).font(.caption2.weight(.semibold)).foregroundStyle(.secondary).padding(.top, 8)
                        }
                        if let e = m.call {
                            CallEntryRow(entry: e, mine: m.mine, date: m.date) {
                                if let p = conv.info?.peer { store.calls.start(chatID: chatID, peer: p, video: e.media == "video") }
                            }.id(m.id)
                        } else {
                        MessageBubble(m: m, showName: conv.info?.kind != "dm" && !m.mine && (i == 0 || shown[i - 1].sender != m.sender || shown[i - 1].call != nil || shown[i - 1].aiState == .ok),
                                      quoted: m.replyTo.flatMap { r in conv.messages.first { $0.id == r } }, hasReply: m.replyTo != nil,
                                      conv: conv, highlight: !search.isEmpty && ChatLogic.matches(m.text, search),
                                      reply: { replyTo = m; focused = true }, delete: { askDelete = m },
                                      openPage: { id in nav.push(PageRoute(id: id)) },
                                      checkSafety: { safetyPeer = ChatMember(username: m.sender, name: m.senderName, me: false) })
                            .id(m.id)
                        }
                    }
                    if let p = conv.aiPending {                                    // KI antwortet gerade (Strom)
                        AIPendingBubble(persona: store.aiPersona, name: conv.aiName, text: p).id("aipending")
                    }
                    if let e = conv.error { Text("Nachrichten nicht geladen: \(e)").font(.footnote).foregroundStyle(.orange) }
                    Color.clear.frame(height: 4).id("end")
                }
                .padding(.horizontal, 12).padding(.vertical, 8)
                .frame(maxWidth: 820).frame(maxWidth: .infinity)
            }
            .defaultScrollAnchor(.bottom)
            // Anhang-/Antwort-Leiste, Tastatur, "tippt ...": Verlauf bleibt unten verankert statt unter der Leiste zu verschwinden
            .defaultScrollAnchor(.bottom, for: .sizeChanges)
            .scrollDismissesKeyboard(.interactively)
            .onChange(of: conv.messages.last?.id) { _, _ in
                withAnimation { proxy.scrollTo("end", anchor: .bottom) }
                if conv.aiPending != nil || conv.messages.last?.aiState == .ok {   // KI-Antwort ersetzt die laufende Blase: nach dem Umbau nachziehen
                    Task { try? await Task.sleep(for: .milliseconds(350)); proxy.scrollTo("end", anchor: .bottom) }
                }
            }
            .onChange(of: conv.aiPending == nil) { _, _ in                  // KI-Blase erscheint/verschwindet: Ende sichtbar halten
                Task { try? await Task.sleep(for: .milliseconds(120)); proxy.scrollTo("end", anchor: .bottom) }
            }
            .onChange(of: conv.aiPending?.count ?? 0) { _, _ in proxy.scrollTo("end", anchor: .bottom) }   // mitlaufender Text
            .onChange(of: files.count) { old, new in                        // Anhang gewaehlt: Leiste schiebt sich ueber den Verlauf -> ans Ende
                guard new > old else { return }
                Task { try? await Task.sleep(for: .milliseconds(80)); withAnimation { proxy.scrollTo("end", anchor: .bottom) } }
            }
            .onChange(of: perfScroll) { _, id in if let id { proxy.scrollTo(id, anchor: .center) } }
            .overlay { if conv.loading && conv.messages.isEmpty { ProgressView() } }
        }
    }
    private var shown: [ChatMessage] { conv.messages }

    // MARK: Eingabe

    private var composer: some View {
        VStack(spacing: 6) {
            if !conv.typingNames.isEmpty {
                Text("\(conv.typingNames.joined(separator: ", ")) tippt …").font(.caption).foregroundStyle(.secondary).frame(maxWidth: .infinity, alignment: .leading)
            }
            if let s = conv.sendError { Text(verbatim: s).font(.footnote).foregroundStyle(.red).frame(maxWidth: .infinity, alignment: .leading) }
            if let s = conv.aiError { Text(verbatim: s).font(.footnote).foregroundStyle(.red).frame(maxWidth: .infinity, alignment: .leading) }
            aiComposerHints
            if let r = replyTo {
                HStack {
                    Rectangle().fill(Color.gold).frame(width: 3)
                    VStack(alignment: .leading) {
                        Text("Antwort an \(r.mine ? String(localized: "du") : r.senderName)").font(.caption.weight(.semibold))
                        Text(verbatim: r.text).font(.caption).lineLimit(1).foregroundStyle(.secondary)
                    }
                    Spacer()
                    Button { replyTo = nil } label: { Image(systemName: "xmark.circle.fill") }.buttonStyle(.plain).foregroundStyle(.secondary).accessibilityLabel("Antwort abbrechen")
                }.frame(height: 36)
            }
            if !files.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack {
                        ForEach(files) { f in
                            HStack(spacing: 4) {
                                PendingFileIcon(file: f)
                                Text(verbatim: f.name).lineLimit(1)
                                Button { files.removeAll { $0.id == f.id } } label: { Image(systemName: "xmark") }.buttonStyle(.plain).accessibilityLabel("Entfernen")
                            }
                            .font(.caption).padding(.horizontal, 8).padding(.vertical, 4).background(Color.secondary.opacity(0.12), in: RoundedRectangle(cornerRadius: 12))
                        }
                    }
                }
            }
            HStack(alignment: .bottom, spacing: 8) {
                Menu {
                    Button { pickPhotos = true } label: { Label("Fotos", systemImage: "photo") }
                    if immichOn { Button { pickImmich = true } label: { Label("Aus Immich", systemImage: "photo.on.rectangle.angled") } }
                    Button { pickFile = true } label: { Label("Datei", systemImage: "doc") }
                    Button { pickPage = true } label: { Label("Seite verlinken", systemImage: "at") }
                } label: { Image(systemName: "plus.circle.fill").font(.title2).frame(minWidth: 44, minHeight: 44).contentShape(Rectangle()) }   // Trefferflaeche 44 pt
                .menuIndicator(.hidden).fixedSize().accessibilityLabel("Anhang")
                TextField("Nachricht …", text: $text, axis: .vertical)
                    .lineLimit(1...6).textFieldStyle(.plain)
                    .padding(.horizontal, 12).padding(.vertical, 8)
                    .background(Color.secondary.opacity(0.1), in: RoundedRectangle(cornerRadius: 18))
                    .focused($focused)
                    .onChange(of: text) { _, v in if !v.isEmpty { conv.typed() } }
                    .onSubmit { sendNow() }
                    #if os(macOS)
                    .onKeyPress(.return, phases: .down) { k in if k.modifiers.contains(.shift) { return .ignored }; sendNow(); return .handled }
                    #endif
                Button { sendNow() } label: {
                    Image(systemName: conv.sending ? "ellipsis.circle" : "arrow.up.circle.fill").font(.title2).frame(minWidth: 44, minHeight: 44).contentShape(Rectangle())
                }
                    .disabled(conv.sending || (text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && files.isEmpty))
                    .keyboardShortcut(.return, modifiers: .command)
                    .accessibilityLabel("Senden")
            }
            .buttonStyle(.plain).foregroundStyle(Color.gold)
        }
        .padding(.horizontal, 12).padding(.vertical, 8)
        .background(.bar)
    }
    private func sendNow() {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty || !files.isEmpty, !conv.sending else { return }
        let f = files, r = replyTo?.id
        let ask = conv.aiAllowed && AIChatLogic.mentions(t, name: conv.aiName)      // "@Marin …": danach die KI fragen
        Task {
            if await conv.send(text: t, replyTo: r, files: f) {
                text = ""; files = []; replyTo = nil
                if ask, let qid = conv.lastSentID { conv.askAI(question: t, qid: qid) }
            }
        }
    }
    private var aiHost: String { store.aiHost.isEmpty ? "?" : store.aiHost }

    /// @-Vorschlag (KI zuerst, dann "Seite verlinken") und Hinweis, dass die Nachricht an die KI geht
    @ViewBuilder private var aiComposerHints: some View {
        let q = AIChatLogic.mentionQuery(text)
        if q != nil {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    if conv.aiAllowed && AIChatLogic.suggests(query: q, name: conv.aiName) {
                        Button { text = AIChatLogic.complete(text, name: conv.aiName); focused = true } label: {
                            HStack(spacing: 6) {
                                AIAvatarView(persona: store.aiPersona, size: 20)
                                Text(verbatim: conv.aiName).font(.callout.weight(.semibold))
                                Text("KI fragen").font(.caption).foregroundStyle(.secondary)
                            }
                            .padding(.horizontal, 10).padding(.vertical, 5).background(Color.gold.opacity(0.14), in: Capsule())
                        }.buttonStyle(.plain)
                    }
                    Button { text = String(text.dropLast((q ?? "").count + 1)); pickPage = true } label: {
                        Label("Seite verlinken", systemImage: "doc.text").font(.callout)
                            .padding(.horizontal, 10).padding(.vertical, 5).background(Color.secondary.opacity(0.12), in: Capsule())
                    }.buttonStyle(.plain)
                }
            }
        }
        if conv.aiAllowed && AIChatLogic.mentions(text, name: conv.aiName) {
            HStack(spacing: 6) {
                AIAvatarView(persona: store.aiPersona, size: 16)
                Text("Geht an \(conv.aiName): diese Nachricht und bis zu \(AIChatLogic.historyMax) davor, im Klartext an \(aiHost).")
                    .font(.caption).foregroundStyle(.secondary)
            }.frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    // MARK: Menue

    private var menu: some View {
        Menu {
            Button { searching.toggle(); if !searching { search = "" } } label: {
                Label(searching ? "Suche schließen" : "In diesem Chat suchen", systemImage: "magnifyingglass")
            }.keyboardShortcut("f", modifiers: .command)
            Divider()
            if let i = conv.info {
                if i.kind == "dm", let p = i.peer { Button { safetyPeer = p } label: { Label("Sicherheitsnummer", systemImage: "checkmark.shield") } }
                Button { showMembers = true } label: { Label("Mitglieder", systemImage: "person.2") }
                Button { Task { await conv.patch(["muted": !i.muted]) } } label: {
                    Label(i.muted ? "Benachrichtigungen an" : "Stummschalten", systemImage: i.muted ? "bell" : "bell.slash")
                }
                Menu {
                    Picker("Nachrichten automatisch löschen nach", selection: Binding(get: { i.retention }, set: { v in Task { await conv.patch(["retention_s": v]) } })) {
                        ForEach(ChatLogic.retentionChoices(chat.me.retentions, max: chat.me.retentionMax), id: \.self) { s in Text(Self.retentionText(s)).tag(s) }
                    }
                    Text("Gilt für neue Nachrichten.")
                } label: { Label("Nachrichten automatisch löschen nach", systemImage: "timer") }
                if store.aiChat, AIChatLogic.mayToggle(kind: i.kind, role: i.role, ownsPage: i.noteID.map { n in store.pages.contains { $0.id == n } } ?? false) {
                    Button { if i.ai { Task { await conv.setAI(false) } } else { askAIOn = true } } label: {
                        Label(i.ai ? String(localized: "\(conv.aiName) hier nicht mehr erlauben") : String(localized: "\(conv.aiName) in diesem Chat erlauben"), systemImage: "sparkle")
                    }
                }
                if i.kind == "page", let n = i.noteID { Button { nav.push(PageRoute(id: n)) } label: { Label("Seite öffnen", systemImage: "doc.text") } }
                if i.kind == "free" {
                    Divider()
                    Button { newName = i.decryptedTitle ?? ""; rename = true } label: { Label("Umbenennen …", systemImage: "pencil") }
                    Button(role: .destructive) { askLeave = true } label: { Label("Gruppe verlassen", systemImage: "rectangle.portrait.and.arrow.right") }
                }
                if i.canArchive {                                            // Archiv und Loeschen (2.1)
                    Divider()
                    Button { let on = !i.archived; conv.info?.archived = on; Task { await chat.setArchived(chatID, on) } } label: {
                        Label(i.archived ? "Aus Archiv holen" : "Archivieren", systemImage: i.archived ? "tray.and.arrow.up" : "archivebox")
                    }
                    Button(role: .destructive) { deleting = i } label: { Label("Chat löschen …", systemImage: "trash") }
                }
            }
        } label: { Image(systemName: "ellipsis.circle") }
        .accessibilityLabel("Mehr").help(Text("Mehr"))
    }
    static func retentionText(_ s: Int) -> String {
        switch ChatLogic.retention(s) {
        case .off: return String(localized: "Aus")
        case .hours(let h): return h == 1 ? String(localized: "1 Stunde") : String(localized: "\(h) Stunden")
        case .days(let d): return d == 1 ? String(localized: "1 Tag") : String(localized: "\(d) Tage")
        case .years(let y): return y == 1 ? String(localized: "1 Jahr") : String(localized: "\(y) Jahre")
        }
    }
    #if DEBUG
    /// nur fuer Tests (-hkChatPerf): erster Bildschirm, ganzer Verlauf, hoch- und runterscrollen (je eine Nachricht alle 60 ms)
    private func perfRun() async {
        ChatPerf.report("geladen", extra: "nachrichten=\(conv.messages.count)")
        await ChatPerf.settle()
        ChatPerf.report("erster-bildschirm")
        ChatPerf.phase()
        while conv.more { let n = conv.messages.count; await conv.loadOlder(); if conv.messages.count == n { break } }
        try? await Task.sleep(for: .milliseconds(500))
        ChatPerf.report("verlauf-geladen", extra: "nachrichten=\(conv.messages.count)")
        let ids = conv.messages.map(\.id)
        let step = max(1, UserDefaults.standard.integer(forKey: "hkChatPerfStep"))
        let ms = UserDefaults.standard.integer(forKey: "hkChatPerfMs") > 0 ? UserDefaults.standard.integer(forKey: "hkChatPerfMs") : 100
        for (name, list) in [("hoch-gescrollt", Array(ids.reversed())), ("runter-gescrollt", ids)] {
            ChatPerf.phase()
            for id in list.enumerated().filter({ $0.offset % step == 0 }).map(\.element) { perfScroll = id; try? await Task.sleep(for: .milliseconds(ms)) }
            perfScroll = list.last
            let end = Date()
            await ChatPerf.settle()
            ChatPerf.report(name, extra: "nachladen_nach_scroll=\(Int(Date().timeIntervalSince(end) * 1000) - 700)ms")
        }
        if UserDefaults.standard.bool(forKey: "hkChatPerfQuit") { ChatPerf.log("ende"); exit(0) }
    }
    /// nur fuer Tests: kleines Bild (Farbverlauf) als Anhang
    static func testImage() -> Data? {
        let w = 240, h = 160
        guard let ctx = CGContext(data: nil, width: w, height: h, bitsPerComponent: 8, bytesPerRow: 0, space: CGColorSpaceCreateDeviceRGB(),
                                  bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return nil }
        let g = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(), colors: [CGColor(red: 0.45, green: 0.7, blue: 0.35, alpha: 1), CGColor(red: 0.85, green: 0.7, blue: 0.3, alpha: 1)] as CFArray, locations: [0, 1])!
        ctx.drawLinearGradient(g, start: .zero, end: CGPoint(x: w, y: h), options: [])
        guard let cg = ctx.makeImage() else { return nil }
        #if os(iOS)
        return UIImage(cgImage: cg).pngData()
        #else
        return NSBitmapImageRep(cgImage: cg).representation(using: .png, properties: [:])
        #endif
    }
    #endif
    /// Fotos: JPEG/PNG/GIF unveraendert, sonst (HEIC) als JPEG
    static func jpeg(_ d: Data) -> (data: Data, mime: String, ext: String) {
        let h = [UInt8](d.prefix(4))
        if h.starts(with: [0xFF, 0xD8]) { return (d, "image/jpeg", "jpg") }
        if h.starts(with: [0x89, 0x50, 0x4E, 0x47]) { return (d, "image/png", "png") }
        if h.starts(with: [0x47, 0x49, 0x46]) { return (d, "image/gif", "gif") }
        #if os(iOS)
        if let j = UIImage(data: d)?.jpegData(compressionQuality: 0.85) { return (j, "image/jpeg", "jpg") }
        #else
        if let img = NSImage(data: d), let t = img.tiffRepresentation, let j = NSBitmapImageRep(data: t)?.representation(using: .jpeg, properties: [.compressionFactor: 0.85]) { return (j, "image/jpeg", "jpg") }
        #endif
        return (d, "application/octet-stream", "bin")
    }
}

// MARK: - Anruf-Eintrag (2.1)

/// Mittig, dezent: Art, Ergebnis, Dauer; Tippen = zurueckrufen
struct CallEntryRow: View {
    let entry: CallLogic.Entry
    let mine: Bool
    let date: Date
    var callBack: () -> Void
    var body: some View {
        Button(action: callBack) {
            HStack(spacing: 8) {
                Image(systemName: icon).foregroundStyle(missed ? Color.red : Color.secondary).accessibilityHidden(true)
                Text(verbatim: text).font(.caption.weight(.medium))
                Text(date, format: .dateTime.hour().minute()).font(.caption2).foregroundStyle(.secondary)
            }
            .padding(.horizontal, 12).padding(.vertical, 6)
            .background(Color.secondary.opacity(0.1), in: Capsule())
        }
        .buttonStyle(.plain).frame(maxWidth: .infinity).padding(.vertical, 2)
        .help(Text("Zurückrufen"))
        .accessibilityHint(Text("Zurückrufen"))
    }
    private var video: Bool { entry.media == "video" }
    private var missed: Bool { entry.kind() == .missed && !mine }
    private var icon: String {
        switch entry.kind() {
        case .missed, .declined, .busy, .failed: return video ? "video.slash" : "phone.down"
        case .ended: return video ? "video" : (mine ? "phone.arrow.up.right" : "phone.arrow.down.left")
        }
    }
    private var text: String {
        switch entry.kind() {
        case .ended(let d):
            let base = video ? (mine ? String(localized: "Ausgehender Videoanruf") : String(localized: "Eingehender Videoanruf"))
                             : (mine ? String(localized: "Ausgehender Anruf") : String(localized: "Eingehender Anruf"))
            return d.map { base + " · " + $0 } ?? base
        case .missed: return mine ? String(localized: "Nicht angenommen") : (video ? String(localized: "Verpasster Videoanruf") : String(localized: "Verpasster Anruf"))
        case .declined: return String(localized: "Anruf abgelehnt")
        case .busy: return String(localized: "Besetzt")
        case .failed: return String(localized: "Anruf fehlgeschlagen")
        }
    }
}

// MARK: - Blase

struct MessageBubble: View {
    let m: ChatMessage
    let showName: Bool
    let quoted: ChatMessage?
    let hasReply: Bool
    @ObservedObject var conv: ConversationModel
    var highlight = false
    var reply: () -> Void
    var delete: () -> Void
    var openPage: (Int) -> Void
    var checkSafety: () -> Void = {}
    @State private var info = false
    @EnvironmentObject var store: Store
    /// bestaetigte KI-Antwort (2.1): links mit Bild + Name der KI, "gefragt von …"
    private var isAI: Bool { m.aiState == .ok }
    private var mine: Bool { m.mine && !isAI }
    private var aiName: String { store.aiName ?? m.content?.aiName ?? String(localized: "Saga KI") }
    #if DEBUG
    @MainActor static var infoShown = false                       // nur fuer Bilder: Erklaerung einmal oeffnen
    #endif
    var body: some View {
        HStack {
            if mine { Spacer(minLength: 48) }
            VStack(alignment: mine ? .trailing : .leading, spacing: 3) {
                if isAI {
                    AIWhoLine(persona: store.aiPersona, name: aiName, askedBy: m.mine ? String(localized: "dir") : m.senderName)
                } else if showName {
                    HStack(spacing: 5) {
                        AvatarView(name: m.senderName, avatar: m.senderAvatar, size: 18)
                        Text(verbatim: m.senderName).font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                    }
                }
                VStack(alignment: .leading, spacing: 6) {
                    if m.aiState == .bad { aiUnconfirmed }
                    if hasReply { quote }
                    if let c = m.content {
                        if !c.text.isEmpty { textView(c.text) }
                        ForEach(c.files) { f in ChatAttachmentView(file: f, conv: conv) }
                    } else {
                        Label("Nicht entschlüsselbar", systemImage: "lock.trianglebadge.exclamationmark").font(.callout.italic())
                        Text("Diese Nachricht lässt sich auf diesem Gerät nicht entschlüsseln.").font(.caption).foregroundStyle(.secondary)
                    }
                    if !m.verified && m.content != nil { unverifiedHint }
                }
                .padding(.horizontal, 12).padding(.vertical, 8)
                .background(bubbleColor, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).strokeBorder(isAI ? Color.gold.opacity(0.55) : .clear, lineWidth: 1))
                .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).strokeBorder(highlight ? Color.gold : .clear, lineWidth: 2))
                HStack(spacing: 4) {
                    Text(m.date, format: .dateTime.hour().minute())
                    if m.expiresAt != nil { Image(systemName: "timer") }
                }.font(.caption2).foregroundStyle(.secondary)
            }
            .contextMenu {
                Button { reply() } label: { Label("Antworten", systemImage: "arrowshape.turn.up.left") }
                if !m.text.isEmpty { Button { copyText(m.text) } label: { Label("Text kopieren", systemImage: "doc.on.doc") } }
                if m.mine { Button(role: .destructive) { delete() } label: { Label("Für alle löschen", systemImage: "trash") } }
            }
            .accessibilityElement(children: .combine)
            .accessibilityHint(m.verified ? Text(verbatim: "") : Text("Echtheit nicht bestätigt"))
            if !mine { Spacer(minLength: 48) }
        }
    }
    /// deckende Blasen (eigene golden wie auf iPhone/iPad, auch am Mac mit Graphit-Akzent)
    private var bubbleColor: Color {
        mine ? .adaptive(ChatLogic.Palette.bubbleMine.light, ChatLogic.Palette.bubbleMine.dark)
               : .adaptive(ChatLogic.Palette.bubbleOther.light, ChatLogic.Palette.bubbleOther.dark)
    }
    /// "Echtheit nicht bestaetigt": Inhalt bleibt voll deckend; Symbol orange, Text in lesbarer Sekundaerfarbe (>= 4,5:1),
    /// antippbar mit kurzer Erklaerung und dem naechsten Schritt
    private var unverifiedHint: some View {
        Button { info = true } label: {
            HStack(spacing: 4) {
                Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(Color.adaptive(ChatLogic.Palette.warn.light, ChatLogic.Palette.warn.dark))
                Text("Echtheit nicht bestätigt").foregroundStyle(Color.adaptive(ChatLogic.Palette.hint.light, ChatLogic.Palette.hint.dark))
                Image(systemName: "info.circle").foregroundStyle(Color.adaptive(ChatLogic.Palette.hint.light, ChatLogic.Palette.hint.dark))
            }.font(.caption.weight(.semibold))
        }
        .buttonStyle(.plain)
        .accessibilityHint(Text("Zeigt, was das bedeutet"))
        #if DEBUG
        .background {
            if UserDefaults.standard.bool(forKey: "hkChatInfo") {
                GeometryReader { g in
                    Color.clear.onChange(of: g.frame(in: .global), initial: true) { _, f in if info { SnapshotHelper.anchor = f } }
                        .onChange(of: info) { _, v in if v { SnapshotHelper.anchor = g.frame(in: .global) } }
                }
            }
        }
        .task {
            if UserDefaults.standard.bool(forKey: "hkChatInfo") && !Self.infoShown { Self.infoShown = true; try? await Task.sleep(for: .seconds(2.5)); info = true }
        }
        #endif
        .popover(isPresented: $info) {
            VStack(alignment: .leading, spacing: 12) {
                Label("Echtheit nicht bestätigt", systemImage: "exclamationmark.triangle.fill").font(.headline)
                Text("Diese Nachricht konnte keinem bestätigten Gerät des Absenders zugeordnet werden – z. B. weil das Gerät inzwischen entfernt wurde. Vergleiche im Zweifel die Sicherheitsnummer.")
                    .fixedSize(horizontal: false, vertical: true)
                Button("Sicherheitsnummer prüfen") { info = false; checkSafety() }.prominentGold()
            }
            .padding(18).frame(idealWidth: 340, maxWidth: 380)
            #if os(iOS)
            .presentationCompactAdaptation(.popover)
            #endif
        }
    }
    /// Inhalt gibt sich als KI-Antwort aus, der Beleg des Servers passt aber nicht (Hash/Kennung)
    private var aiUnconfirmed: some View {
        HStack(spacing: 4) {
            Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(Color.adaptive(ChatLogic.Palette.warn.light, ChatLogic.Palette.warn.dark))
            Text("Als KI-Antwort ausgegeben, aber nicht bestätigt").foregroundStyle(Color.adaptive(ChatLogic.Palette.hint.light, ChatLogic.Palette.hint.dark))
        }
        .font(.caption.weight(.semibold))
        .accessibilityElement(children: .combine)
    }
    private var quote: some View {
        HStack(spacing: 6) {
            Rectangle().fill(Color.gold).frame(width: 3)
            VStack(alignment: .leading, spacing: 1) {
                if let q = quoted {
                    Text(verbatim: q.aiState == .ok ? (store.aiName ?? q.content?.aiName ?? String(localized: "Saga KI")) : q.mine ? String(localized: "du") : q.senderName).font(.caption.weight(.semibold))
                    Text(verbatim: q.text.isEmpty ? (q.content?.files.first?.name ?? "") : q.text).font(.caption).lineLimit(2).foregroundStyle(.secondary)
                } else { Text("Nachricht nicht mehr da").font(.caption.italic()).foregroundStyle(.secondary) }
            }
        }.fixedSize(horizontal: false, vertical: true)
    }
    /// Klartext mit Seitenverweisen (antippbar) - kein Markdown aus Nachrichten
    private func textView(_ t: String) -> some View {
        var a = AttributedString()
        for p in ChatLogic.pieces(t) {
            switch p {
            case .text(let s): a += AttributedString(s)
            case .note(let id):
                let n = conv.notes[id]
                var x = AttributedString((n?.icon.isEmpty == false ? n!.icon + " " : "📄 ") + (n?.title ?? String(localized: "Seite")))
                if n?.icon != "🔒" { x.link = URL(string: "hkchatnote://\(id)"); x.foregroundColor = Color.gold; x.underlineStyle = .single }
                else { x.foregroundColor = .secondary }
                a += x
            }
        }
        return Text(a).textSelection(.enabled).fixedSize(horizontal: false, vertical: true)
            .environment(\.openURL, OpenURLAction { u in
                if u.scheme == "hkchatnote", let id = Int(u.host ?? "") { openPage(id); return .handled }
                return .discarded
            })
    }
}

/// KI antwortet gerade: Bild + Name, "… denkt nach" bzw. der mitlaufende Text
struct AIPendingBubble: View {
    var persona: AIPersona?
    var name: String
    var text: String
    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 3) {
                AIWhoLine(persona: persona, name: name)
                Group {
                    if text.isEmpty {
                        HStack(spacing: 6) { ProgressView().controlSize(.small); Text("\(name) denkt nach …").foregroundStyle(.secondary) }
                    } else { Text(verbatim: text).fixedSize(horizontal: false, vertical: true) }
                }
                .padding(.horizontal, 12).padding(.vertical, 8)
                .background(Color.adaptive(ChatLogic.Palette.bubbleOther.light, ChatLogic.Palette.bubbleOther.dark), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).strokeBorder(Color.gold.opacity(0.55), lineWidth: 1))
            }
            Spacer(minLength: 48)
        }
        .accessibilityElement(children: .combine)
    }
}

/// Anhang: Bild als Vorschaubild (Platzhalter in fester Groesse aus w/h, Original erst beim Antippen), sonst Datei zum Teilen/Sichern
struct ChatAttachmentView: View {
    let file: ChatFileRef
    @ObservedObject var conv: ConversationModel
    var body: some View {
        if file.isImage || file.thumb != nil { ChatImageThumb(file: file, conv: conv) } else { ChatFileRow(file: file, conv: conv) }
    }
}

/// Bild im Verlauf: nur das Vorschaubild (im Hintergrund geladen/entschluesselt/dekodiert, Abbruch beim Wegscrollen)
struct ChatImageThumb: View {
    let file: ChatFileRef
    @ObservedObject var conv: ConversationModel
    @State private var img: ChatImage?
    @State private var err: String?
    @State private var open = false
    var body: some View {
        let a = aspect
        let sz = ChatMediaLogic.displaySize(w: a.w, h: a.h)
        Button { open = true } label: {
            ZStack {
                Rectangle().fill(Color.secondary.opacity(0.12))
                if let cg = (img ?? ChatMedia.cached(file.id))?.cg {
                    Image(decorative: cg, scale: 1).resizable().interpolation(.medium).scaledToFill()
                } else if err != nil {
                    Image(systemName: "photo.badge.exclamationmark").font(.title2).foregroundStyle(.secondary)
                } else {
                    Image(systemName: "photo").font(.title2).foregroundStyle(.tertiary)
                }
            }
            .frame(width: sz.w, height: sz.h).clipShape(RoundedRectangle(cornerRadius: 10)).contentShape(RoundedRectangle(cornerRadius: 10))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text(verbatim: file.name.isEmpty ? String(localized: "Bild") : file.name))
        .accessibilityHint(Text("Öffnen"))
        .help(Text("Öffnen"))
        .overlay(alignment: .bottomLeading) {
            if let err {
                Text("Anhang nicht geladen: \(err)").font(.caption2).foregroundStyle(.orange).lineLimit(2)
                    .padding(6).background(.regularMaterial, in: RoundedRectangle(cornerRadius: 6)).padding(6)
            }
        }
        .task(id: file.id) { await load() }
        .sheet(isPresented: $open) { ChatImageViewer(file: file, conv: conv) }
    }
    /// Seitenverhaeltnis: w/h der Nachricht, sonst Vorschaubild, sonst schon einmal geladen (alte Nachrichten)
    private var aspect: (w: Int?, h: Int?) {
        if let w = file.w, let h = file.h { return (w, h) }
        if let t = file.thumb, t.w > 0, t.h > 0 { return (t.w, t.h) }
        if let k = ChatMedia.knownAspect(file.id) { return (k.w, k.h) }
        if let i = img { return (i.w, i.h) }
        return (nil, nil)
    }
    private func load() async {
        if let m = ChatMedia.cached(file.id) { if img !== m { img = m }; return }
        #if DEBUG
        ChatPerf.imgStart()
        #endif
        do {
            let m = try await conv.thumbnail(file)
            img = m; err = nil
            #if DEBUG
            ChatPerf.imgDone()
            #endif
        } catch {
            #if DEBUG
            if Task.isCancelled || error is CancellationError { ChatPerf.imgCancel() } else { ChatPerf.imgDone(ok: false); if ChatPerf.on { ChatPerf.log("fehler \(error)") } }
            #endif
            if !Task.isCancelled && !(error is CancellationError) { err = error.localizedDescription }
        }
    }
}

/// Bild oeffnen: Original (verkleinert auf Bildschirmgroesse dekodiert), Teilen/Sichern
struct ChatImageViewer: View {
    let file: ChatFileRef
    @ObservedObject var conv: ConversationModel
    @Environment(\.dismiss) private var dismiss
    @State private var img: CGImage?
    @State private var url: URL?
    @State private var err: String?
    var body: some View {
        NavigationStack {
            ZStack {
                Color.black.ignoresSafeArea()
                if let img {
                    Image(decorative: img, scale: 1).resizable().scaledToFit()
                        .accessibilityLabel(Text(verbatim: file.name.isEmpty ? String(localized: "Bild") : file.name))
                } else if let err {
                    Text("Anhang nicht geladen: \(err)").foregroundStyle(.white).padding()
                } else {
                    ZStack {
                        if let t = ChatMedia.cached(file.id)?.cg { Image(decorative: t, scale: 1).resizable().scaledToFit().blur(radius: 6).opacity(0.7) }
                        ProgressView().tint(.white)
                    }
                }
            }
            .navigationTitle(Text(verbatim: file.name))
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Schließen") { dismiss() }.keyboardShortcut(.cancelAction) }
                ToolbarItem(placement: .primaryAction) {
                    if let url { ShareLink(item: url) { Label("Teilen", systemImage: "square.and.arrow.up") } }
                }
            }
        }
        #if os(macOS)
        .frame(minWidth: 640, idealWidth: 900, minHeight: 480, idealHeight: 700)
        #endif
        .task(id: file.id) { await load() }
    }
    private func load() async {
        do {
            let d = try await conv.original(file)
            let name = file.name.isEmpty ? "bild" : file.name.replacingOccurrences(of: "/", with: "_")
            let fid = file.id
            // Dekodieren (verkleinert) und die Kopie fuer "Teilen/Sichern" im Hintergrund
            let r = await Task.detached(priority: .userInitiated) { () -> (CGImage?, URL?) in
                let cg = ChatImageIO.decode(d, maxPixel: 4096)
                let dir = FileManager.default.temporaryDirectory.appendingPathComponent("chat-\(fid)", isDirectory: true)
                try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
                let u = dir.appendingPathComponent(name)
                #if os(iOS)
                let ok = (try? d.write(to: u, options: [.atomic, .completeFileProtection])) != nil
                #else
                let ok = (try? d.write(to: u, options: [.atomic])) != nil
                #endif
                return (cg, ok ? u : nil)
            }.value
            guard let cg = r.0 else { throw ChatMediaError.undecodable }
            img = cg; url = r.1
        } catch { if !(error is CancellationError) { err = error.localizedDescription } }
    }
}

/// Datei (kein Bild): Name, Groesse, Laden -> Teilen/Sichern
struct ChatFileRow: View {
    let file: ChatFileRef
    @ObservedObject var conv: ConversationModel
    @State private var err: String?
    @State private var url: URL?
    @State private var loading = false
    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 8) {
                Image(systemName: "doc.fill").foregroundStyle(.secondary)
                VStack(alignment: .leading, spacing: 1) {
                    Text(verbatim: file.name.isEmpty ? String(localized: "Datei") : file.name).font(.callout).lineLimit(1)
                    Text(ByteCountFormatter.string(fromByteCount: Int64(file.size), countStyle: .file)).font(.caption2).foregroundStyle(.secondary)
                }
                if let url { ShareLink(item: url) { Image(systemName: "square.and.arrow.up") }.accessibilityLabel(Text("Teilen")) }
                else if loading { ProgressView().controlSize(.small) }
                else { Button { Task { await fetch() } } label: { Image(systemName: "arrow.down.circle") }.buttonStyle(.plain).accessibilityLabel(Text("Laden")) }
            }
            if let err { Text("Anhang nicht geladen: \(err)").font(.caption).foregroundStyle(.orange) }
        }
    }
    private func fetch() async {
        loading = true; defer { loading = false }
        do {
            let d = try await conv.file(file)
            let name = file.name.isEmpty ? "datei" : file.name.replacingOccurrences(of: "/", with: "_")
            let fid = file.id
            // nur fuer "Teilen/Sichern": entschluesselte Kopie im temporaeren Ordner (wird vom System aufgeraeumt)
            url = try await Task.detached(priority: .userInitiated) { () -> URL in
                let dir = FileManager.default.temporaryDirectory.appendingPathComponent("chat-\(fid)", isDirectory: true)
                try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
                let u = dir.appendingPathComponent(name)
                #if os(iOS)
                try d.write(to: u, options: [.atomic, .completeFileProtection])
                #else
                try d.write(to: u, options: [.atomic])
                #endif
                return u
            }.value
            err = nil
        } catch { if !(error is CancellationError) { err = error.localizedDescription } }
    }
}

// MARK: - Mitglieder

struct MembersSheet: View {
    @ObservedObject var conv: ConversationModel
    var safety: (ChatMember) -> Void
    @EnvironmentObject var store: Store
    @Environment(\.dismiss) private var dismiss
    @State private var q = ""
    @State private var hits: [(String, String)] = []
    var body: some View {
        NavigationStack {
            Form {
                if let i = conv.info {
                    Section {
                        ForEach(i.members) { m in
                            HStack {
                                ShareAvatar(name: m.name, group: false, avatar: m.avatar)
                                VStack(alignment: .leading) {
                                    Text(verbatim: m.me ? m.name + " (" + String(localized: "du") + ")" : m.name)
                                    Text(verbatim: m.username).font(.caption).foregroundStyle(.secondary)
                                }
                                Spacer()
                                if !m.me {
                                    if ChatTrustStore.known(conv.model.me.uid, m.username)?.verified == true { Image(systemName: "checkmark.seal.fill").foregroundStyle(.green).accessibilityLabel(Text("überprüft")) }
                                    Button("Sicherheitsnummer") { safety(m) }.buttonStyle(.bordered).controlSize(.small)
                                }
                            }
                        }
                    } footer: {
                        switch i.kind {
                        case "group": Text("Alle Mitglieder der Gruppe „\(i.group ?? "")“ – ändert sich mit der Gruppe.")
                        case "page": Text("Alle, die die Seite sehen dürfen – ändert sich mit den Freigaben.")
                        default: EmptyView()
                        }
                    }
                    if i.kind == "free" && i.role == "admin" {
                        Section("Person hinzufügen") {
                            TextField("Person hinzufügen (ab 3 Zeichen)", text: $q).autocorrectionDisabled()
                            if ChatFedHint.show(q, hits: hits.count) { FedChatHint() }
                            ForEach(hits, id: \.0) { h in
                                Button { Task { await conv.addMember(h.0); q = "" } } label: { Text(verbatim: "\(h.1) · \(h.0)") }
                            }
                        }
                    }
                }
            }
            .formStyle(.grouped)
            .navigationTitle("Mitglieder")
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Fertig") { dismiss() } } }
            .task(id: q) {
                guard ShareLogic.searchReady(q), let e = q.addingPercentEncoding(withAllowedCharacters: .alphanumerics) else { hits = []; return }
                try? await Task.sleep(for: .milliseconds(250))
                guard let d = try? await store.api("api/chat/people?q=\(e)"), let j = ShareJSON.obj(d) else { return }
                hits = (j["users"] as? [Any] ?? []).compactMap { v in (v as? [String: Any]).flatMap { u in (u["username"] as? String).map { ($0, u["name"] as? String ?? $0) } } }
            }
        }
        .macFrame(minWidth: 420, idealWidth: 460, minHeight: 420)
    }
}

/// @-Verweis: eigene und mit mir geteilte Seiten (GET /api/chat/pages?q=)
struct PagePickSheet: View {
    var picked: (Int, String) -> Void
    @EnvironmentObject var store: Store
    @Environment(\.dismiss) private var dismiss
    @State private var q = ""
    @State private var pages: [(Int, String, String)] = []
    var body: some View {
        NavigationStack {
            List(pages, id: \.0) { p in
                Button { picked(p.0, p.1); dismiss() } label: { Text(verbatim: (p.2.isEmpty ? "📄" : p.2) + " " + p.1) }
            }
            .searchable(text: $q, prompt: Text("Seiten durchsuchen"))
            .navigationTitle("Seite verlinken")
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Abbrechen") { dismiss() } } }
            .task(id: q) {
                try? await Task.sleep(for: .milliseconds(200))
                let e = q.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? ""
                guard let d = try? await store.api("api/chat/pages?q=\(e)"), let j = ShareJSON.obj(d) else { return }
                pages = (j["pages"] as? [Any] ?? []).compactMap { v in
                    guard let o = v as? [String: Any], let id = ShareJSON.int(o["id"]) else { return nil }
                    return (id, o["title"] as? String ?? "", o["icon"] as? String ?? "")
                }
            }
        }
        .macFrame(minWidth: 380, idealWidth: 420, minHeight: 420)
    }
}

// MARK: - Anhaenge vor dem Senden: Bilder aus der Zwischenablage und aus Immich (2.1)

/// Chat-Eingabe: Cmd-V/Einsetzen mit Bild -> Anhang (mit Vorschau), "Aus Immich" -> Foto ueber den Server-Proxy laden.
/// Beides wird beim Senden wie jeder Anhang Ende-zu-Ende verschluesselt. Eigener Modifier: Typpruefung der Unterhaltung.
struct ChatMediaInput: ViewModifier {
    @Binding var files: [ChatPendingFile]
    @Binding var pickImmich: Bool
    @Binding var immichOn: Bool
    let enabled: Bool
    let maxBytes: Int
    @EnvironmentObject var store: Store
    func body(content: Content) -> some View {
        content
            .imagePasteTarget(enabled: { enabled }, maxBytes: { maxBytes }) { imgs in add(imgs) }
            .sheet(isPresented: $pickImmich) {
                ImmichPicker(onPick: { _ in }, forChat: { it in add([try await ImmichPicker.loadForChat(it, store: store, maxBytes: maxBytes)]) })
                    .environmentObject(store)
            }
            .task { immichOn = await ImmichPicker.connected(store) }
    }
    private func add(_ imgs: [PastedImage]) {
        for p in imgs {
            let n = p.name.hasPrefix("bild.") ? "bild-\(files.count + 1)." + (p.name.split(separator: ".").last.map(String.init) ?? "png") : p.name
            files.append(ChatPendingFile(name: n, mime: p.mime, data: p.data))
        }
    }
}

/// Symbol eines Anhangs vor dem Senden: Bilder als kleine Vorschau
struct PendingFileIcon: View {
    let file: ChatPendingFile
    @State private var thumb: CGImage?
    var body: some View {
        Group {
            if let thumb { Image(decorative: thumb, scale: 2).resizable().scaledToFill().frame(width: 36, height: 36).clipShape(RoundedRectangle(cornerRadius: 6)) }
            else { Image(systemName: file.mime.hasPrefix("image/") ? "photo" : "doc") }
        }
        .accessibilityHidden(true)
        .task(id: file.id) {
            guard file.mime.hasPrefix("image/") else { return }
            let d = file.data
            thumb = await Task.detached(priority: .utility) { () -> CGImage? in
                guard let src = CGImageSourceCreateWithData(d as CFData, nil) else { return nil }
                let o: [CFString: Any] = [kCGImageSourceCreateThumbnailFromImageAlways: true, kCGImageSourceCreateThumbnailWithTransform: true, kCGImageSourceThumbnailMaxPixelSize: 96]
                return CGImageSourceCreateThumbnailAtIndex(src, 0, o as CFDictionary)
            }.value
        }
    }
}
