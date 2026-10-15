import SwiftUI
import PhotosUI
import UniformTypeIdentifiers

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
    @State private var safetyPeer: ChatMember?
    @State private var showMembers = false
    @State private var askDelete: ChatMessage?
    @State private var askLeave = false
    @State private var rename = false
    @State private var newName = ""
    @State private var search = ""
    @State private var searching = false
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
            // Suche im Chat steckt im Menue (Befehl-F): eine zweite Lupe neben dem Suchfeld der Liste/Seitenleiste verwirrt
            ToolbarItem(placement: .primaryAction) { menu }
        }
        .task {
            chat.open = conv
            await conv.load()
            #if DEBUG
            if UserDefaults.standard.bool(forKey: "hkChatSafety"), let p = conv.info?.peer { try? await Task.sleep(for: .seconds(2.5)); safetyPeer = p }   // nur fuer Tests
            if UserDefaults.standard.bool(forKey: "hkChatMembers") { try? await Task.sleep(for: .seconds(2.5)); showMembers = true }
            if let t = UserDefaults.standard.string(forKey: "hkChatSend") {                 // nur fuer Tests: Nachricht (+ Bild) senden
                var f: [ChatPendingFile] = []
                if UserDefaults.standard.bool(forKey: "hkChatSendImage"), let png = Self.testImage() { f.append(ChatPendingFile(name: "garten.png", mime: "image/png", data: png)) }
                let reply = UserDefaults.standard.bool(forKey: "hkChatReply") ? conv.messages.last(where: { !$0.mine })?.id : nil
                await conv.send(text: t, replyTo: reply, files: f)
            }
            if let q = UserDefaults.standard.string(forKey: "hkChatSearch") { searching = true; search = q }
            if UserDefaults.standard.bool(forKey: "hkChatAck") { for u in conv.changedPeers { conv.acknowledge(u) } }   // nur fuer Tests: "Verstanden"
            #endif
        }
        .onDisappear { if chat.open === conv { chat.open = nil } }
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
                    ForEach(Array(shown.enumerated()), id: \.element.id) { i, m in
                        if i == 0 || !Calendar.current.isDate(shown[i - 1].date, inSameDayAs: m.date) {
                            Text(m.date.formatted(date: .complete, time: .omitted)).font(.caption2.weight(.semibold)).foregroundStyle(.secondary).padding(.top, 8)
                        }
                        MessageBubble(m: m, showName: conv.info?.kind != "dm" && !m.mine && (i == 0 || shown[i - 1].sender != m.sender),
                                      quoted: m.replyTo.flatMap { r in conv.messages.first { $0.id == r } }, hasReply: m.replyTo != nil,
                                      conv: conv, highlight: !search.isEmpty && ChatLogic.matches(m.text, search),
                                      reply: { replyTo = m; focused = true }, delete: { askDelete = m },
                                      openPage: { id in nav.push(PageRoute(id: id)) },
                                      checkSafety: { safetyPeer = ChatMember(username: m.sender, name: m.senderName, me: false) })
                            .id(m.id)
                    }
                    if let e = conv.error { Text("Nachrichten nicht geladen: \(e)").font(.footnote).foregroundStyle(.orange) }
                    Color.clear.frame(height: 4).id("end")
                }
                .padding(.horizontal, 12).padding(.vertical, 8)
                .frame(maxWidth: 820).frame(maxWidth: .infinity)
            }
            .defaultScrollAnchor(.bottom)
            .scrollDismissesKeyboard(.interactively)
            .onChange(of: conv.messages.last?.id) { _, _ in withAnimation { proxy.scrollTo("end", anchor: .bottom) } }
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
                                Image(systemName: f.mime.hasPrefix("image/") ? "photo" : "doc")
                                Text(verbatim: f.name).lineLimit(1)
                                Button { files.removeAll { $0.id == f.id } } label: { Image(systemName: "xmark") }.buttonStyle(.plain).accessibilityLabel("Entfernen")
                            }
                            .font(.caption).padding(.horizontal, 8).padding(.vertical, 4).background(Color.secondary.opacity(0.12), in: Capsule())
                        }
                    }
                }
            }
            HStack(alignment: .bottom, spacing: 8) {
                Menu {
                    Button { pickPhotos = true } label: { Label("Fotos", systemImage: "photo") }
                    Button { pickFile = true } label: { Label("Datei", systemImage: "doc") }
                    Button { pickPage = true } label: { Label("Seite verlinken", systemImage: "at") }
                } label: { Image(systemName: "plus.circle.fill").font(.title2) }
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
                Button { sendNow() } label: { Image(systemName: conv.sending ? "ellipsis.circle" : "arrow.up.circle.fill").font(.title2) }
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
        Task {
            if await conv.send(text: t, replyTo: r, files: f) { text = ""; files = []; replyTo = nil }
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
                if i.kind == "page", let n = i.noteID { Button { nav.push(PageRoute(id: n)) } label: { Label("Seite öffnen", systemImage: "doc.text") } }
                if i.kind == "free" {
                    Divider()
                    Button { newName = i.decryptedTitle ?? ""; rename = true } label: { Label("Umbenennen …", systemImage: "pencil") }
                    Button(role: .destructive) { askLeave = true } label: { Label("Gruppe verlassen", systemImage: "rectangle.portrait.and.arrow.right") }
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
    #if DEBUG
    @MainActor static var infoShown = false                       // nur fuer Bilder: Erklaerung einmal oeffnen
    #endif
    var body: some View {
        HStack {
            if m.mine { Spacer(minLength: 48) }
            VStack(alignment: m.mine ? .trailing : .leading, spacing: 3) {
                if showName { Text(verbatim: m.senderName).font(.caption.weight(.semibold)).foregroundStyle(.secondary) }
                VStack(alignment: .leading, spacing: 6) {
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
            if !m.mine { Spacer(minLength: 48) }
        }
    }
    /// deckende Blasen (eigene golden wie auf iPhone/iPad, auch am Mac mit Graphit-Akzent)
    private var bubbleColor: Color {
        m.mine ? .adaptive(ChatLogic.Palette.bubbleMine.light, ChatLogic.Palette.bubbleMine.dark)
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
    private var quote: some View {
        HStack(spacing: 6) {
            Rectangle().fill(Color.gold).frame(width: 3)
            VStack(alignment: .leading, spacing: 1) {
                if let q = quoted {
                    Text(verbatim: q.mine ? String(localized: "du") : q.senderName).font(.caption.weight(.semibold))
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

/// Anhang: Bild inline (nach dem Entschluesseln), sonst Datei zum Teilen/Sichern
struct ChatAttachmentView: View {
    let file: ChatFileRef
    @ObservedObject var conv: ConversationModel
    @State private var data: Data?
    @State private var err: String?
    @State private var url: URL?
    var body: some View {
        Group {
            if file.isImage, let data, let img = PlatformImage(data: data) {
                #if os(iOS)
                Image(uiImage: img).resizable().scaledToFit().frame(maxWidth: 280, maxHeight: 320).clipShape(RoundedRectangle(cornerRadius: 10))
                #else
                Image(nsImage: img).resizable().scaledToFit().frame(maxWidth: 280, maxHeight: 320).clipShape(RoundedRectangle(cornerRadius: 10))
                #endif
            } else {
                HStack(spacing: 8) {
                    Image(systemName: "doc.fill").foregroundStyle(.secondary)
                    VStack(alignment: .leading, spacing: 1) {
                        Text(verbatim: file.name.isEmpty ? String(localized: "Datei") : file.name).font(.callout).lineLimit(1)
                        Text(ByteCountFormatter.string(fromByteCount: Int64(file.size), countStyle: .file)).font(.caption2).foregroundStyle(.secondary)
                    }
                    if let url { ShareLink(item: url) { Image(systemName: "square.and.arrow.up") }.accessibilityLabel(Text("Teilen")) }
                    else if err == nil && !file.isImage { Button { Task { await fetch() } } label: { Image(systemName: "arrow.down.circle") }.buttonStyle(.plain).accessibilityLabel(Text("Laden")) }
                }
            }
            if let err { Text("Anhang nicht geladen: \(err)").font(.caption).foregroundStyle(.orange) }
        }
        .task { if file.isImage { await fetch() } }
    }
    private func fetch() async {
        do {
            let d = try await conv.file(file)
            data = d
            if !file.isImage {
                // nur fuer "Teilen/Sichern": entschluesselte Kopie im temporaeren Ordner (wird vom System aufgeraeumt)
                let dir = FileManager.default.temporaryDirectory.appendingPathComponent("chat-\(file.id)", isDirectory: true)
                try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
                let u = dir.appendingPathComponent(file.name.isEmpty ? "datei" : file.name.replacingOccurrences(of: "/", with: "_"))
                try d.write(to: u, options: [.atomic, .completeFileProtection])
                url = u
            }
        } catch { err = error.localizedDescription }
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
                                ShareAvatar(name: m.name, group: false)
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
