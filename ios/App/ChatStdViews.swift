import SwiftUI
import PhotosUI

// Saga 2.5 App-Runde "Chat-Standard": Ansichten (Gruppenbild/Beschreibung/Rechte, Pins-Leiste, Grabstein, eingeklappte
// Nachricht gesperrter Personen, Melden, Stummschalten mit Dauer, Medienuebersicht, Linkvorschau, Sperrliste,
// formatierter Text). Modell: ChatStd.swift, Logik: ChatStdLogic.swift/ChatFmtLogic.swift.

// MARK: - Formatierter Text (chatfmt1) mit Links, Seitenverweisen, Erwaehnungen

struct ChatRichText: View {
    let text: String
    @ObservedObject var conv: ConversationModel
    var openPage: (Int) -> Void
    var body: some View {
        let blocks: [ChatFmt.Block] = conv.std.fmt ? ChatFmt.blocks(ChatFmt.render(text)) : [.para([.text(text)])]
        VStack(alignment: .leading, spacing: 4) {
            ForEach(Array(blocks.enumerated()), id: \.offset) { _, b in block(b) }
        }
        .environment(\.openURL, OpenURLAction { u in
            if u.scheme == "hkchatnote", let id = Int(u.host ?? "") { openPage(id); return .handled }
            if u.scheme == "http" || u.scheme == "https" { return .systemAction }
            return .discarded
        })
    }
    private func block(_ b: ChatFmt.Block) -> AnyView {
        switch b {
        case .para(let ns): return AnyView(Text(attr(ns)).selectableOnMac().fixedSize(horizontal: false, vertical: true))
        case .list(let items):
            return AnyView(VStack(alignment: .leading, spacing: 2) {
                ForEach(Array(items.enumerated()), id: \.offset) { _, it in
                    HStack(alignment: .firstTextBaseline, spacing: 6) { Text(verbatim: "•"); Text(attr(it)).fixedSize(horizontal: false, vertical: true) }
                }
            })
        case .quote(let bs):
            return AnyView(HStack(spacing: 6) {
                Rectangle().fill(Color.secondary.opacity(0.5)).frame(width: 3)
                VStack(alignment: .leading, spacing: 2) { ForEach(Array(bs.enumerated()), id: \.offset) { _, x in block(x) } }.foregroundStyle(.secondary)
            }.fixedSize(horizontal: false, vertical: true))
        case .pre(let t):
            return AnyView(Text(verbatim: t).font(.system(.callout, design: .monospaced)).padding(6)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Color.secondary.opacity(0.12), in: RoundedRectangle(cornerRadius: 6)).selectableOnMac())
        }
    }
    /// Inline-Knoten -> AttributedString (nur Text + feste Attribute; nie Markup aus dem Text)
    private func attr(_ ns: [FmtNode], bold: Bool = false, italic: Bool = false, strike: Bool = false) -> AttributedString {
        var out = AttributedString()
        for n in ns {
            switch n {
            case .text(let s): out += leaves(s, bold: bold, italic: italic, strike: strike)
            case .el("strong", let k): out += attr(k, bold: true, italic: italic, strike: strike)
            case .el("em", let k): out += attr(k, bold: bold, italic: true, strike: strike)
            case .el("s", let k): out += attr(k, bold: bold, italic: italic, strike: true)
            case .el("code", let k):
                var a = AttributedString(k.map { if case .text(let t) = $0 { return t }; return "" }.joined())
                a.font = .system(.body, design: .monospaced); a.backgroundColor = Color.secondary.opacity(0.15)
                out += a
            case .el(_, let k): out += attr(k, bold: bold, italic: italic, strike: strike)
            }
        }
        return out
    }
    private func leaves(_ s: String, bold: Bool, italic: Bool, strike: Bool) -> AttributedString {
        var out = AttributedString()
        let pieces: [ChatFmt.Leaf] = ChatFmt.leafPieces(s)
        for p in pieces {
            var a: AttributedString
            switch p {
            case .text(let t): a = AttributedString(t)
            case .note(let id):
                let n = conv.notes[id]
                a = AttributedString((n?.icon.isEmpty == false ? n!.icon + " " : "📄 ") + (n?.title ?? String(localized: "Seite")))
                if n?.icon != "🔒" { a.link = URL(string: "hkchatnote://\(id)"); a.foregroundColor = Color.gold; a.underlineStyle = .single }
                else { a.foregroundColor = .secondary }
            case .url(let u):
                a = AttributedString(u)
                if conv.std.fmt || conv.std.unfurl, let url = URL(string: u) { a.link = url; a.underlineStyle = .single }
            case .mention(let r):
                if conv.std.mention {
                    let me = r == conv.info?.std.meRef || r == conv.fed?.myHandle
                    a = AttributedString("@" + (conv.mentionName(r) ?? String(localized: "Unbekannt")))
                    a.foregroundColor = Color.gold
                    a.inlinePresentationIntent = me ? .stronglyEmphasized : nil
                    if me { a.backgroundColor = Color.gold.opacity(0.22) }
                } else { a = AttributedString("<@" + r + ">") }
            }
            var intent: InlinePresentationIntent = a.inlinePresentationIntent ?? []
            if bold { intent.insert(.stronglyEmphasized) }
            if italic { intent.insert(.emphasized) }
            if !intent.isEmpty { a.inlinePresentationIntent = intent }
            if strike { a.strikethroughStyle = .single }
            out += a
        }
        return out
    }
}

// MARK: - Linkvorschau (unfurl1)

struct LinkPreviewCard: View {
    let title: String
    let desc: String
    let site: String
    var image: CGImage? = nil
    var remove: (() -> Void)? = nil
    var body: some View {
        HStack(alignment: .top, spacing: 8) {
            if let image { Image(decorative: image, scale: 1).resizable().scaledToFill().frame(width: 56, height: 56).clipShape(RoundedRectangle(cornerRadius: 6)) }
            VStack(alignment: .leading, spacing: 2) {
                if !site.isEmpty { Text(verbatim: site).font(.caption2).foregroundStyle(.secondary).lineLimit(1) }
                if !title.isEmpty { Text(verbatim: title).font(.callout.weight(.semibold)).lineLimit(2) }
                if !desc.isEmpty { Text(verbatim: desc).font(.caption).foregroundStyle(.secondary).lineLimit(3) }
            }
            Spacer(minLength: 0)
            if let remove {
                Button(action: remove) { Image(systemName: "xmark.circle.fill").foregroundStyle(.secondary) }
                    .buttonStyle(.plain).accessibilityLabel(Text("Vorschau entfernen")).help(Text("Vorschau entfernen"))
            }
        }
        .padding(8)
        .background(Color.secondary.opacity(0.08), in: RoundedRectangle(cornerRadius: 10))
        .overlay(alignment: .leading) { Rectangle().fill(Color.gold).frame(width: 3).clipShape(RoundedRectangle(cornerRadius: 2)) }
        .accessibilityElement(children: .combine)
    }
}

/// Karte in einer empfangenen Nachricht: alles aus dem (verschluesselten) Inhalt, Bild vom eigenen Server
struct MessagePreviewCard: View {
    let p: ChatStd.Preview
    @ObservedObject var conv: ConversationModel
    @State private var img: CGImage?
    @Environment(\.openURL) private var openURL
    var body: some View {
        Button { if let u = URL(string: p.url) { openURL(u) } } label: {
            LinkPreviewCard(title: p.title, desc: p.desc, site: p.site.isEmpty ? (URL(string: p.url)?.host ?? "") : p.site, image: img)
        }
        .buttonStyle(.plain).frame(maxWidth: 320)
        .task(id: p.img?.id) {
            guard let t = p.img else { return }
            // als Anhang mit Vorschaubild = sich selbst laden (gleiche Verschluesselung wie th)
            let f = ChatFileRef(id: t.id, key: t.key, name: "", mime: "image/jpeg", size: t.size, w: t.w, h: t.h, thumb: t)
            img = (try? await conv.thumbnail(f))?.cg
        }
    }
}

// MARK: - Pins-Leiste (msgpin1)

struct PinnedBar: View {
    @ObservedObject var conv: ConversationModel
    var jump: (String) -> Void
    @State private var index = 0
    var body: some View {
        if conv.std.msgPin, let i = conv.info, let s = ChatStd.pinShown(i.std.pins, index: index) {
            let m = conv.messages.first { $0.id == s.id }
            HStack(spacing: 8) {
                Button {
                    jump(s.id)
                    if s.count > 1 { index = (s.index + 1) % s.count }
                } label: {
                    HStack(spacing: 8) {
                        Image(systemName: "pin.fill").foregroundStyle(Color.gold)
                        VStack(alignment: .leading, spacing: 1) {
                            Text(s.count > 1 ? String(localized: "Angeheftet (\(s.index + 1)/\(s.count))") : String(localized: "Angeheftet")).font(.caption.weight(.semibold))
                            Text(verbatim: m.map { ($0.mine ? String(localized: "du") : $0.senderName) + ": " + preview($0) } ?? String(localized: "Ältere Nachricht"))
                                .font(.caption).foregroundStyle(.secondary).lineLimit(1)
                        }
                        Spacer(minLength: 0)
                    }.contentShape(Rectangle())
                }.buttonStyle(.plain)
                if i.std.canPin, let m {
                    Button { Task { await conv.pin(m, false) } } label: { Image(systemName: "xmark") }
                        .buttonStyle(.plain).foregroundStyle(.secondary).accessibilityLabel(Text("Anheftung lösen")).help(Text("Lösen"))
                }
            }
            .padding(.horizontal, 12).padding(.vertical, 6).background(.bar)
            Divider()
        }
    }
    private func preview(_ m: ChatMessage) -> String {
        let t = conv.plainText(m.text).replacingOccurrences(of: "\n", with: " ")
        if !t.isEmpty { return t }
        if let f = m.content?.files.first { return "📎 " + f.name }
        return m.content == nil ? String(localized: "Nicht entschlüsselbar") : ""
    }
}

// MARK: - Grabstein, eingeklappte Nachricht

struct TombRow: View {
    let m: ChatMessage
    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: "trash").foregroundStyle(.secondary).accessibilityHidden(true)
            Text(verbatim: ChatStd.tombText(sender: m.mine ? nil : m.senderName)).font(.caption.italic())
            Text(m.date, format: .dateTime.hour().minute()).font(.caption2).foregroundStyle(.secondary)
        }
        .padding(.horizontal, 12).padding(.vertical, 6)
        .background(Color.secondary.opacity(0.1), in: Capsule())
        .frame(maxWidth: .infinity).padding(.vertical, 2)
        .accessibilityElement(children: .combine)
    }
}

struct BlockedMessageRow: View {
    var show: () -> Void
    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: "hand.raised.fill").foregroundStyle(.secondary).accessibilityHidden(true)
            Text("Nachricht einer gesperrten Person").font(.caption)
            Button("Anzeigen", action: show).font(.caption.weight(.semibold)).buttonStyle(.borderless)
        }
        .padding(.horizontal, 12).padding(.vertical, 6)
        .background(Color.secondary.opacity(0.1), in: Capsule())
        .frame(maxWidth: .infinity).padding(.vertical, 2)
    }
}

// MARK: - Melden (report1)

struct ReportSheet: View {
    let chatID: Int
    let messages: [ChatMessage]
    @ObservedObject var conv: ConversationModel
    @Environment(\.dismiss) private var dismiss
    @State private var reason: ChatStd.ReportReason = .spam
    @State private var include = true
    @State private var text = ""
    @State private var busy = false
    @State private var err: String?
    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text("Die Meldung geht an die Admins dieses Saga-Servers. Chats sind Ende-zu-Ende-verschlüsselt – die Admins sehen nur, was du hier mitschickst.")
                        .font(.callout).foregroundStyle(.secondary)
                    Picker("Grund", selection: $reason) {
                        Text("Spam").tag(ChatStd.ReportReason.spam)
                        Text("Belästigung oder Beleidigung").tag(ChatStd.ReportReason.abuse)
                        Text("Rechtswidrige Inhalte").tag(ChatStd.ReportReason.illegal)
                        Text("Sonstiges").tag(ChatStd.ReportReason.other)
                    }
                }
                Section {
                    Toggle("Text der gemeldeten Nachrichten mitschicken", isOn: $include)
                    if include {
                        TextEditor(text: $text).frame(minHeight: 100).accessibilityLabel(Text("Mitgeschickter Text"))
                    }
                } footer: { Text("Du kannst den Text vor dem Senden ändern oder kürzen.") }
                if let err { Text(verbatim: err).foregroundStyle(.red).font(.footnote) }
            }
            .formStyle(.grouped)
            .navigationTitle("Melden")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Abbrechen") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Melden") {
                        busy = true
                        Task {
                            err = await conv.model.report(chat: chatID, msgs: messages.map(\.id), reason: reason, text: include ? text : nil)
                            busy = false
                            if err == nil { dismiss() }
                        }
                    }.disabled(busy)
                }
            }
            .onAppear {
                text = ChatStd.reportText(messages.map { (name: $0.senderName, text: conv.plainText($0.text)) })
                if text.count > ChatStd.reportTextMax { text = String(text.prefix(ChatStd.reportTextMax)) }
            }
            .onChange(of: text) { _, v in if v.count > ChatStd.reportTextMax { text = String(v.prefix(ChatStd.reportTextMax)) } }
        }
        .macFrame(minWidth: 420, idealWidth: 480, minHeight: 420, idealHeight: 520)
    }
}

// MARK: - Stummschalten mit Dauer + Mitteilungen (mute2)

struct ChatMuteMenu: View {
    @ObservedObject var chat: ChatModel
    let c: ChatSummary
    var body: some View {
        let st = ChatStd.muteState(mutedUntil: c.std.mutedUntil, muted: c.muted, notify: c.std.notify, now: Date())
        Menu {
            if c.muted { Button { Task { await chat.mute(c.id, .off) } } label: { Label("Stummschaltung aufheben", systemImage: "bell") } }
            Button("1 Stunde") { Task { await chat.mute(c.id, .hour) } }
            Button("8 Stunden") { Task { await chat.mute(c.id, .eight) } }
            Button("1 Woche") { Task { await chat.mute(c.id, .week) } }
            Button("Für immer") { Task { await chat.mute(c.id, .forever) } }
            Divider()
            Picker("Mitteilungen", selection: Binding(get: { c.std.notify }, set: { n in Task { await chat.setNotify(c.id, n) } })) {
                Text("Alle Nachrichten").tag("all")
                Text("Nur Erwähnungen und Antworten").tag("mentions")
                Text("Keine").tag("none")
            }
        } label: {
            Label(ChatStd.muteLabel(st, format: Self.fmt), systemImage: c.muted ? "bell.slash" : "bell")
        }
    }
    static func fmt(_ d: Date) -> String { d.formatted(.dateTime.weekday(.abbreviated).day().month(.twoDigits).hour().minute()) }
}

/// Eintraege fuer Liste und Chat-Menue: Stumm (Dauer), Anheften, ungelesen, ausblenden - nur mit den Features
struct ChatAlltagItems: View {
    @ObservedObject var chat: ChatModel
    let c: ChatSummary
    var closeIfOpen: () -> Void = {}
    var body: some View {
        let f = chat.std
        if f.mute2 { ChatMuteMenu(chat: chat, c: c) }
        else {
            Button { Task { await chat.mute(c.id, c.muted ? .off : .forever) } } label: {
                Label(c.muted ? "Benachrichtigungen an" : "Stummschalten", systemImage: c.muted ? "bell" : "bell.slash")
            }
        }
        if f.chatPin {
            Button { Task { await chat.setPinned(c, !c.std.pinned) } } label: {
                Label(c.std.pinned ? "Loslösen" : "Anheften", systemImage: c.std.pinned ? "pin.slash" : "pin")
            }
        }
        if ChatStd.offerMarkUnread(unread: c.unread, mark: c.std.unreadMark, f: f) {
            Button { Task { await chat.markUnread(c); closeIfOpen() } } label: { Label("Als ungelesen markieren", systemImage: "circle.fill") }
        }
        if f.hide && c.std.canHide {
            Button { Task { await chat.setHidden(c, !c.std.hidden); if !c.std.hidden { closeIfOpen() } } } label: {
                Label(c.std.hidden ? "Wieder anzeigen" : "Für mich ausblenden", systemImage: c.std.hidden ? "eye" : "eye.slash")
            }
        }
    }
}

// MARK: - Medien und Dateien (chatmedia1)

struct ChatMediaSheet: View {
    @ObservedObject var conv: ConversationModel
    @Environment(\.dismiss) private var dismiss
    enum Tab: Hashable { case media, files, links }
    @State private var tab: Tab = .media
    @State private var items: [(ChatFileRef, ChatMessage)] = []
    @State private var before: Int?
    @State private var more = true
    @State private var loading = false
    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                Picker("", selection: $tab) {
                    Text("Medien").tag(Tab.media); Text("Dateien").tag(Tab.files); Text("Links").tag(Tab.links)
                }.pickerStyle(.segmented).padding()
                ScrollView {
                    content.padding(.horizontal)
                    if tab != .links && more {
                        Button(loading ? String(localized: "Lädt …") : String(localized: "Ältere laden")) { Task { await load() } }
                            .disabled(loading).padding()
                    }
                }
            }
            .navigationTitle("Medien und Dateien")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Fertig") { dismiss() } } }
            .task { if items.isEmpty { await load() } }
        }
        .macFrame(minWidth: 480, idealWidth: 560, minHeight: 480, idealHeight: 620)
    }
    @ViewBuilder private var content: some View {
        switch tab {
        case .media:
            let ms = items.filter { ChatStd.mediaKind($0.0) == .media }
            if ms.isEmpty && !loading { empty }
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 96), spacing: 4)], spacing: 4) {
                ForEach(ms, id: \.0.id) { f, _ in ChatAttachmentView(file: f, conv: conv).frame(maxWidth: 120, maxHeight: 120).clipped() }
            }
        case .files:
            let fs = items.filter { ChatStd.mediaKind($0.0) == .file }
            if fs.isEmpty && !loading { empty }
            ForEach(fs, id: \.0.id) { f, _ in ChatFileRow(file: f, conv: conv).frame(maxWidth: .infinity, alignment: .leading).padding(.vertical, 4) }
        case .links:
            let ls = links
            if ls.isEmpty { empty }
            ForEach(ls, id: \.self) { u in
                if let url = URL(string: u) { Link(destination: url) { Text(verbatim: u).lineLimit(1) }.frame(maxWidth: .infinity, alignment: .leading).padding(.vertical, 4) }
            }
        }
    }
    private var empty: some View { Text("Nichts gefunden (in den geladenen Nachrichten).").font(.callout).foregroundStyle(.secondary).padding() }
    /// Links kennt der Server nicht: aus den geladenen Nachrichten
    private var links: [String] {
        var seen = Set<String>(), out: [String] = []
        for m in conv.messages.reversed() {
            for p in ChatFmt.leafPieces(m.text) { if case .url(let u) = p, seen.insert(u).inserted { out.append(u) } }
        }
        return out
    }
    private func load() async {
        loading = true; defer { loading = false }
        let r = await conv.loadMedia(before: before)
        items += r.items.reversed(); before = r.before; more = r.more
    }
}

// MARK: - Sperrliste (Einstellungen -> Chat)

struct ChatBlocksSection: View {
    @ObservedObject var chat: ChatModel
    var body: some View {
        if chat.std.block && chat.state == .ready {
            Section {
                if chat.blocks.isEmpty { Text("Niemand gesperrt.").foregroundStyle(.secondary) }
                ForEach(chat.blocks) { b in
                    HStack {
                        Image(systemName: b.kind == "server" ? "server.rack" : "person.crop.circle.badge.xmark").foregroundStyle(.secondary)
                        Text(verbatim: b.name)
                        Spacer()
                        Button("Entsperren") { Task { await chat.unblock(b.target) } }.buttonStyle(.borderless)
                    }
                }
            } header: { Text("Gesperrte Personen") } footer: {
                Text("Gesperrte Personen können dir keine Direktnachrichten schicken und dich nicht anrufen. In Gruppen werden ihre Nachrichten eingeklappt.")
            }
            .task { await chat.loadBlocks() }
        }
    }
}

// MARK: - Gruppeninfo: Bild, Beschreibung, Rechte (groupinfo2)

struct GroupAvatarView: View {
    let c: ChatSummary
    @ObservedObject var conv: ConversationModel
    var size: CGFloat = 44
    @State private var img: CGImage?
    var body: some View {
        ZStack {
            Circle().fill(Color.gold.opacity(0.16))
            if let img { Image(decorative: img, scale: 1).resizable().scaledToFill() }
            else { Image(systemName: c.kind == "page" ? "doc.text" : "person.2.fill").font(.system(size: size * 0.36)) }
        }
        .frame(width: size, height: size).clipShape(Circle()).accessibilityHidden(true)
        .task(id: c.avatarFile?.id) {
            guard let f = c.avatarFile else { img = nil; return }
            img = (try? await conv.thumbnail(f))?.cg
        }
    }
}

/// Gruppenbild in der Chatliste (ohne offenen Chat): selbst laden
struct ListGroupAvatar: View {
    let c: ChatSummary
    @EnvironmentObject var store: Store
    @State private var img: CGImage?
    var body: some View {
        ZStack {
            Circle().fill(Color.gold.opacity(0.16))
            if let img { Image(decorative: img, scale: 1).resizable().scaledToFill() }
            else { Image(systemName: "person.2.fill").font(.caption) }
        }
        .frame(width: 36, height: 36).clipShape(Circle()).accessibilityHidden(true)
        .task(id: c.avatarFile?.id) {
            guard let f = c.avatarFile else { img = nil; return }
            if let m = ChatMedia.cached(f.id) { img = m.cg; return }
            let s = store
            let fetch: ChatBlobFetch = { [weak s] id in
                guard let s else { throw APIError.other("") }
                return try await s.api("api/chat/files/\(id)")
            }
            img = (try? await ChatMedia.shared.thumbnail(f, chatID: c.fileCID, fetch: fetch))?.cg
        }
    }
}

struct GroupInfo2Section: View {
    let i: ChatSummary
    @ObservedObject var conv: ConversationModel
    @State private var editDesc = false
    @State private var desc = ""
    @State private var photo: PhotosPickerItem?
    @State private var pickPhoto = false
    @State private var busy = false
    var body: some View {
        let f = conv.std
        let edit = ChatStd.canEditInfo2(kind: i.kind, info: i.std, readonly: i.fed?.readonly ?? false, f: f)
        if f.groupInfo2 && i.kind == "free" {
            Section {
                if edit {
                    HStack {
                        Button { pickPhoto = true } label: { Label(i.avatarFile == nil ? "Gruppenbild wählen …" : "Neues Bild wählen …", systemImage: "camera") }.buttonStyle(.borderless)
                        if i.avatarFile != nil {
                            Spacer()
                            Button(role: .destructive) { busy = true; Task { _ = await conv.setGroupAvatar(nil); busy = false } } label: { Label("Bild entfernen", systemImage: "xmark") }.buttonStyle(.borderless)
                        }
                    }.disabled(busy)
                }
                if editDesc {
                    TextField("Worum geht es in dieser Gruppe?", text: $desc, axis: .vertical).lineLimit(3...6)
                        .onChange(of: desc) { _, v in if v.count > ChatStd.descMax { desc = String(v.prefix(ChatStd.descMax)) } }
                    HStack {
                        Text(verbatim: "\(desc.count) / \(ChatStd.descMax)").font(.caption).foregroundStyle(.secondary)
                        Spacer()
                        Button("Abbrechen") { editDesc = false }.buttonStyle(.borderless)
                        Button("Speichern") { busy = true; Task { if await conv.setDescription(desc) { editDesc = false }; busy = false } }
                            .buttonStyle(.borderless).disabled(busy)
                    }
                } else {
                    if let d = i.desc { Text(verbatim: d).fixedSize(horizontal: false, vertical: true) }
                    else if edit { Text("Keine Beschreibung.").foregroundStyle(.secondary) }
                    if edit {
                        Button(i.desc == nil ? "Beschreibung hinzufügen" : "Beschreibung bearbeiten") { desc = i.desc ?? ""; editDesc = true }
                    }
                }
            } header: { Text("Beschreibung") }
            .photosPicker(isPresented: $pickPhoto, selection: $photo, matching: .images)
            .onChange(of: photo) { _, it in
                guard let it else { return }
                busy = true
                Task { if let d = try? await it.loadTransferable(type: Data.self) { _ = await conv.setGroupAvatar(d) }; photo = nil; busy = false }
            }
            if ChatStd.showPerms(kind: i.kind, info: i.std, f: f) {
                Section {
                    Toggle("Nur Admins dürfen Nachrichten senden", isOn: Binding(get: { i.std.permSend == "admins" }, set: { v in Task { await conv.setPerm("perm_send", admins: v) } }))
                        .disabled(!i.std.canPerms)
                    Toggle("Nur Admins dürfen Name, Bild und Beschreibung ändern und Nachrichten anheften", isOn: Binding(get: { i.std.permInfo == "admins" }, set: { v in Task { await conv.setPerm("perm_info", admins: v) } }))
                        .disabled(!i.std.canPerms)
                } header: { Text("Gruppenrechte") } footer: {
                    if !i.std.canPerms { Text("Nur Admins ändern die Rechte.") }
                }
            }
        }
    }
}

// MARK: - Nutzereinstellung "Linkvorschau" (Standard an, nur dieses Geraet)

enum ChatLinkPreviewSetting {
    static let key = "hk.chat.linkPreview"
    static var on: Bool { UserDefaults.standard.object(forKey: key) as? Bool ?? true }
}

struct ChatLinkPreviewToggle: View {
    @ObservedObject var chat: ChatModel
    @AppStorage(ChatLinkPreviewSetting.key) private var on = true
    var body: some View {
        if chat.std.unfurl && chat.state == .ready {
            Section {
                Toggle(isOn: $on) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Linkvorschau")
                        Text("Beim Tippen einer Adresse holt dein Server eine Vorschau; sie geht verschlüsselt mit der Nachricht.")
                            .font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                    }
                }
            }
        }
    }
}

// MARK: - Kamera direkt im Chat (iOS): Foto oder Video -> wie ein Anhang

#if os(iOS)
import UIKit
import UniformTypeIdentifiers

struct ChatCamera: UIViewControllerRepresentable {
    static var available: Bool { UIImagePickerController.isSourceTypeAvailable(.camera) }
    var done: (ChatPendingFile?) -> Void
    func makeCoordinator() -> Coord { Coord(done: done) }
    func makeUIViewController(context: Context) -> UIImagePickerController {
        let p = UIImagePickerController()
        p.sourceType = .camera
        p.mediaTypes = [UTType.image.identifier, UTType.movie.identifier]
        p.videoQuality = .typeHigh
        p.delegate = context.coordinator
        return p
    }
    func updateUIViewController(_ vc: UIImagePickerController, context: Context) {}
    final class Coord: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let done: (ChatPendingFile?) -> Void
        init(done: @escaping (ChatPendingFile?) -> Void) { self.done = done }
        func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
            var out: ChatPendingFile?
            if let u = info[.mediaURL] as? URL { out = ChatVideoPick.pending(fromFile: u) }      // Video: Kopie als Datei
            else if let img = info[.originalImage] as? UIImage, let d = img.jpegData(compressionQuality: 0.85) {
                out = ChatPendingFile(name: "kamera.jpg", mime: "image/jpeg", data: d)
            }
            picker.dismiss(animated: true) { self.done(out) }
        }
        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) { picker.dismiss(animated: true) { self.done(nil) } }
    }
}
#endif
