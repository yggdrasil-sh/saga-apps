import SwiftUI

// Kommentare (Saga 1.3), dezent wie im Web: am Block nur eine kleine Zahl (offene Unterhaltungen), die Unterhaltungen
// in einer Seitenleiste (iPad) bzw. einem Blatt von unten (iPhone) oder einem Blatt (Mac). Antworten, eigene bearbeiten/
// loeschen (⋯), "✓ Erledigt"/"Wieder oeffnen", "Erledigte zeigen" am Listenende, ungelesen = Punkt neben dem Autor.
// Logik: ShareLogic.swift

/// Was die Kommentarliste zeigt: alle Unterhaltungen (block nil) oder die eines Blocks
struct CommentsTarget: Identifiable, Equatable {
    var block: String?
    /// fest: Wechsel zwischen "alle" und einem Block laesst das Blatt offen (sheet(item:) wuerde sonst neu aufgehen)
    var id: Int { 0 }
}

/// Kleine Zahl am Block (nur bei offenen Unterhaltungen); Trefferflaeche 44 pt, ohne den Block hoeher zu machen
struct CommentBubble: View {
    let count: Int
    let unread: Bool
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            HStack(spacing: 2) {
                Image(systemName: "bubble.left").font(.caption2)
                Text(verbatim: "\(count)").font(.caption2.monospacedDigit())
                if unread { Circle().fill(Color.accentColor).frame(width: 5, height: 5) }
            }
            .foregroundStyle(.secondary)
            .padding(.horizontal, 6).padding(.vertical, 2)
            .background(Capsule().strokeBorder(.secondary.opacity(0.35)))
            .frame(minWidth: 44, minHeight: 44).contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .padding(.vertical, -11)
        .accessibilityLabel(count == 1 ? Text("1 Unterhaltung zu diesem Block") : Text("\(count) Unterhaltungen zu diesem Block"))
    }
}

/// Kopfzeile: "💬 3" (offene Unterhaltungen) mit Punkt bei Ungelesenem
struct CommentsHeadButton: View {
    let open: Int
    let unread: Int
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            HStack(spacing: 3) {
                Image(systemName: "bubble.left")
                Text(verbatim: "\(open)").monospacedDigit()
            }
            .overlay(alignment: .topTrailing) { if unread > 0 { Circle().fill(Color.accentColor).frame(width: 7, height: 7).offset(x: 5, y: -3) } }
        }
        .help(Self.label(open, unread))
        .accessibilityLabel(Text(verbatim: Self.label(open, unread)))
    }
    static func label(_ open: Int, _ unread: Int) -> String {
        var t = open == 1 ? String(localized: "1 offene Unterhaltung") : String(localized: "\(open) offene Unterhaltungen")
        if unread > 0 { t += " · " + (unread == 1 ? String(localized: "1 ungelesen") : String(localized: "\(unread) ungelesen")) }
        return t
    }
}

/// Liste der Unterhaltungen einer Seite
struct CommentsPanel: View {
    let noteID: Int
    @Binding var block: String?
    let role: ShareRole
    /// Text des Blocks fuer die Liste "alle" (nil = Block gibt es nicht mehr)
    let snippet: (String) -> String?
    /// Neuer Stand -> Zahlen an der Seite nachfuehren
    let onUpdate: (CommentsState) -> Void
    let close: () -> Void
    @EnvironmentObject private var store: Store
    @State private var st: CommentsState?
    @State private var loadError: String?
    @State private var showResolved = false
    @State private var expanded: Set<Int> = []
    @State private var replyTo: Int?
    @State private var editing: Int?
    @State private var newText = ""
    @State private var replyText = ""
    @State private var editText = ""
    @State private var error: String?
    @State private var busy = false
    @State private var askDelete: (CommentItem, CommentThread, Bool, Bool)?    // Kommentar, Unterhaltung, erster?, ganze Unterhaltung?
    @State private var undo: Int?
    #if os(iOS)
    private let touch = true
    #else
    private let touch = false
    #endif

    private var canComment: Bool { st?.canComment ?? role.canComment }
    private var shown: [CommentThread] { ShareLogic.visible(st?.threads ?? [], block: block, showResolved: showResolved) }

    var body: some View {
        VStack(spacing: 0) {
            header
            Divider()
            if block != nil {
                Button { block = nil } label: { Label("Alle Unterhaltungen", systemImage: "arrow.left").font(.subheadline).frame(minHeight: 44) }
                    .buttonStyle(.plain).foregroundStyle(.secondary).frame(maxWidth: .infinity, alignment: .leading).padding(.horizontal)
                Divider()
            }
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 12) {
                    if let undo {
                        HStack {
                            Text("Unterhaltung erledigt")
                            Spacer()
                            Button("Rückgängig") { Task { await resolve(undo, false); self.undo = nil } }.bold()
                        }
                        .font(.subheadline).padding(10).background(.regularMaterial, in: RoundedRectangle(cornerRadius: 10))
                    }
                    if st == nil && loadError == nil { ProgressView().frame(maxWidth: .infinity).padding() }
                    if let e = loadError { Label(String(localized: "Kommentare nicht geladen: \(e)"), systemImage: "exclamationmark.triangle").foregroundStyle(.orange).font(.footnote) }
                    ForEach(shown) { t in threadCard(t) }
                    if st != nil && shown.isEmpty { Text(emptyText).font(.callout).foregroundStyle(.secondary) }
                    if (st?.resolvedCount ?? 0) > 0 || showResolved {
                        Toggle("Erledigte zeigen", isOn: $showResolved).font(.subheadline)
                            .help(String(localized: "\(st?.resolvedCount ?? 0) erledigt"))
                    }
                }
                .padding()
            }
            Divider()
            footer
        }
        .task { await reload() }
        .onChange(of: showResolved) { _, _ in Task { await reload() } }
        .task(id: ShareLogic.toMarkSeen(shown)) { await markSeen() }
        .alert(deleteTitle, isPresented: Binding(get: { askDelete != nil }, set: { if !$0 { askDelete = nil } })) {
            Button("Löschen", role: .destructive) { if let a = askDelete { Task { await delete(a.0, all: a.3) } } }
            Button("Abbrechen", role: .cancel) {}
        } message: { Text(verbatim: deleteMessage) }
    }

    private var header: some View {
        HStack(spacing: 8) {
            Text("Kommentare").font(.headline)
            let n = st?.open.count ?? 0
            if n > 0 {
                Text(verbatim: "\(n)").font(.caption.monospacedDigit()).padding(.horizontal, 7).padding(.vertical, 2)
                    .background(Color.secondary.opacity(0.15), in: Capsule())
            }
            Spacer()
            Button { close() } label: { Image(systemName: "xmark").frame(width: 44, height: 44).contentShape(Rectangle()) }
                .buttonStyle(.plain).foregroundStyle(.secondary).keyboardShortcut(.cancelAction)
                .accessibilityLabel(Text("Kommentare schließen"))
        }
        .padding(.leading).padding(.trailing, 6)
    }

    @ViewBuilder private var footer: some View {
        if canComment {
            VStack(alignment: .trailing, spacing: 6) {
                editor(text: $newText, placeholder: block != nil ? String(localized: "Kommentar zu diesem Block") : String(localized: "Kommentar zur ganzen Seite"),
                       ok: String(localized: "Kommentieren"), cancel: nil) { v in await add(v) }
                if let error { Text(error).font(.footnote).foregroundStyle(.red).frame(maxWidth: .infinity, alignment: .leading) }
            }
            .padding()
        } else {
            Text("Du kannst Kommentare lesen, aber nicht schreiben.").font(.footnote).foregroundStyle(.secondary)
                .frame(maxWidth: .infinity, alignment: .leading).padding()
        }
    }

    private var emptyText: String {
        switch ShareLogic.empty(block: block, canComment: canComment, role: role, resolvedCount: st?.resolvedCount ?? 0, showResolved: showResolved, touch: touch) {
        case .blockNone: return String(localized: "Keine Kommentare zu diesem Block.")
        case .blockNoOpen: return String(localized: "Keine offenen Kommentare zu diesem Block.")
        case .blockNoneYet: return String(localized: "Noch keine Kommentare zu diesem Block.")
        case .noOpen(let n): return n == 1 ? String(localized: "Keine offenen Kommentare · 1 erledigt") : String(localized: "Keine offenen Kommentare · \(n) erledigt")
        case .noneYet: return String(localized: "Noch keine Kommentare.")
        case .tapToComment: return String(localized: "Noch keine Kommentare. Tippe auf einen Absatz, um ihn zu kommentieren.")
        case .noneYetHint: return touch ? String(localized: "Noch keine Kommentare. Halte einen Absatz gedrückt, um ihn zu kommentieren, oder schreibe unten einen Kommentar zur ganzen Seite.")
                                        : String(localized: "Noch keine Kommentare. Kommentiere einen Absatz über das Block-Menü oder schreibe unten einen Kommentar zur ganzen Seite.")
        }
    }

    // MARK: Unterhaltung

    @ViewBuilder private func threadCard(_ t: CommentThread) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            if block == nil { whereLine(t) }
            let by = t.resolvedBy.map { String(localized: "Erledigt von \($0.me ? String(localized: "dir") : $0.name)") }
            if t.resolved && !expanded.contains(t.id) {
                Button { expanded.insert(t.id) } label: {
                    HStack {
                        Text(verbatim: by.map { "✓ " + $0 } ?? String(localized: "✓ Erledigt"))
                        Spacer()
                        Text(t.comments.count == 1 ? String(localized: "1 Kommentar") : String(localized: "\(t.comments.count) Kommentare")).foregroundStyle(.secondary)
                    }
                    .font(.subheadline).frame(minHeight: 44).contentShape(Rectangle())
                }.buttonStyle(.plain)
            } else {
                if t.resolved { Text(verbatim: by.map { "✓ " + $0 } ?? String(localized: "✓ Erledigt")).font(.subheadline).foregroundStyle(.secondary) }
                ForEach(Array(t.comments.enumerated()), id: \.element.id) { i, c in
                    if i > 0 { Divider() }
                    commentView(c, t, first: i == 0, dot: i == t.unreadIndex)
                }
                if canComment { threadBar(t) }
            }
        }
        .padding(12)
        .background(RoundedRectangle(cornerRadius: 10).fill(Color.secondary.opacity(0.06)))
        .overlay(RoundedRectangle(cornerRadius: 10).strokeBorder(t.unread ? Color.accentColor.opacity(0.7) : Color.secondary.opacity(0.25)))
        .opacity(t.resolved ? 0.85 : 1)
    }

    @ViewBuilder private func whereLine(_ t: CommentThread) -> some View {
        if t.wholePage {
            Text("Ganze Seite").font(.caption).foregroundStyle(.secondary)
        } else if let s = t.blockExists ? snippet(t.blockID) : nil {
            Button { block = t.blockID } label: {
                HStack(spacing: 6) {
                    Rectangle().fill(.secondary.opacity(0.5)).frame(width: 2)
                    Text(verbatim: s).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                }.frame(minHeight: 22)
            }.buttonStyle(.plain)
        } else {
            Text("Block entfernt").font(.caption).italic().foregroundStyle(.secondary)
        }
    }

    @ViewBuilder private func commentView(_ c: CommentItem, _ t: CommentThread, first: Bool, dot: Bool) -> some View {
        let acts = ShareLogic.actions(c, in: t, first: first, role: role)
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 6) {
                Text(c.author.me ? String(localized: "Du") : (c.author.name.isEmpty ? String(localized: "Gelöschtes Konto") : c.author.name)).font(.subheadline.weight(.semibold))
                if dot { Circle().fill(Color.accentColor).frame(width: 6, height: 6).accessibilityLabel(Text("Neu")) }
                Text(Date(timeIntervalSince1970: c.created).formatted(.relative(presentation: .named))).font(.caption).foregroundStyle(.secondary)
                if c.edited != nil && !c.deleted { Text("bearbeitet").font(.caption).foregroundStyle(.secondary) }
                Spacer(minLength: 0)
            }
            .padding(.trailing, acts.isEmpty ? 0 : 30)
            // ⋯ als Overlay: 44-pt-Trefferflaeche, ohne die Kopfzeile des Kommentars hoeher zu machen
            .overlay(alignment: .trailing) {
                if !acts.isEmpty {
                    Menu {
                        ForEach(acts, id: \.self) { a in
                            switch a {
                            case .edit: Button { editing = c.id; editText = c.body; replyTo = nil } label: { Label("Bearbeiten", systemImage: "pencil") }
                            case .delete: Button(role: .destructive) { askDelete = (c, t, first, false) } label: { Label("Löschen", systemImage: "trash") }
                            case .deleteThread: Button(role: .destructive) { askDelete = (c, t, first, true) } label: { Label("Unterhaltung löschen", systemImage: "trash") }
                            }
                        }
                    } label: { Image(systemName: "ellipsis").frame(width: 44, height: 44).contentShape(Rectangle()) }
                    .menuStyle(.button).buttonStyle(.plain).menuIndicator(.hidden).fixedSize().foregroundStyle(.secondary)
                    .padding(.trailing, -8)
                    .accessibilityLabel(Text("Aktionen für diesen Kommentar"))
                }
            }
            if editing == c.id {
                editor(text: $editText, placeholder: String(localized: "Kommentar bearbeiten"), ok: String(localized: "Speichern"), cancel: { editing = nil }) { v in await edit(c, v) }
            } else if c.deleted {
                Text("Kommentar gelöscht").italic().foregroundStyle(.secondary)
            } else {
                Text(Self.linked(c.body)).textSelection(.enabled).fixedSize(horizontal: false, vertical: true)
            }
        }
        .accessibilityElement(children: .contain)
    }

    @ViewBuilder private func threadBar(_ t: CommentThread) -> some View {
        if replyTo == t.id {
            editor(text: $replyText, placeholder: String(localized: "Antworten"), ok: String(localized: "Antworten"), cancel: { replyTo = nil }) { v in await reply(t, v) }
        } else {
            HStack {
                Button("Antworten") { replyTo = t.id; replyText = ""; editing = nil }.frame(minHeight: 44)
                Spacer()
                Button(t.resolved ? String(localized: "Wieder öffnen") : String(localized: "✓ Erledigt##Aktion")) { Task { await resolve(t.id, !t.resolved) } }
                    .frame(minHeight: 44)
                    .help(t.resolved ? String(localized: "Unterhaltung wieder öffnen") : String(localized: "Als erledigt markieren – die Unterhaltung wird zugeklappt"))
            }
            .buttonStyle(.plain).font(.subheadline).foregroundStyle(.secondary)
        }
    }

    /// Eingabe mit Zaehler (erst kurz vor 2000 Zeichen), Senden mit ⌘↩
    private func editor(text: Binding<String>, placeholder: String, ok: String, cancel: (() -> Void)?, send: @escaping (String) async -> Bool) -> some View {
        let max = st?.max ?? ShareLogic.commentMax
        return VStack(alignment: .trailing, spacing: 6) {
            TextField(placeholder, text: text, axis: .vertical)
                .lineLimit(2...8).textFieldStyle(.plain)
                .padding(8).background(RoundedRectangle(cornerRadius: 8).strokeBorder(.secondary.opacity(0.4)))
                .onChange(of: text.wrappedValue) { _, v in if v.count > max { text.wrappedValue = String(v.prefix(max)) } }
                .accessibilityLabel(Text(verbatim: placeholder))
            HStack {
                if ShareLogic.showCounter(text.wrappedValue.count, max: max) {
                    Text("\(text.wrappedValue.count) von \(max)").font(.caption).foregroundStyle(.secondary)
                }
                Spacer()
                if let cancel { Button("Abbrechen", action: cancel).buttonStyle(.bordered) }
                let can = !busy && ShareLogic.canSend(text.wrappedValue, max: max)
                let btn = Button(ok) {
                    let v = text.wrappedValue.trimmingCharacters(in: .whitespacesAndNewlines)
                    Task { busy = true; if await send(v) { text.wrappedValue = "" }; busy = false }
                }
                .keyboardShortcut(.return, modifiers: .command)
                .buttonStyle(.borderedProminent)
                // gesperrt: Systemdarstellung (.borderedProminent + .disabled) - kein schwarzes Loch im Dunkeln, nicht zu blass
                if can { btn.foregroundStyle(Color("OnAccent")) } else { btn.disabled(true) }
            }
        }
    }

    /// Klartext + erkannte Links (nur http/https)
    static func linked(_ s: String) -> AttributedString {
        var out = AttributedString(s)
        for l in ShareLogic.links(s) {
            let lo = s.distance(from: s.startIndex, to: l.range.lowerBound), n = s.distance(from: l.range.lowerBound, to: l.range.upperBound)
            let a = out.index(out.startIndex, offsetByCharacters: lo), b = out.index(a, offsetByCharacters: n)
            out[a..<b].link = l.url; out[a..<b].underlineStyle = .single
        }
        return out
    }

    private var deleteTitle: String {
        guard let a = askDelete else { return "" }
        if a.3 || ShareLogic.deleteKind(first: a.2, thread: a.1) == .wholeThread { return String(localized: "Unterhaltung löschen?") }
        return String(localized: "Kommentar löschen?")
    }
    private var deleteMessage: String {
        guard let a = askDelete else { return "" }
        if a.3 { return String(localized: "Der erste Kommentar und alle Antworten werden gelöscht – auch die von anderen.") }
        switch ShareLogic.deleteKind(first: a.2, thread: a.1) {
        case .wholeThread: return String(localized: "Der erste Kommentar und alle Antworten werden gelöscht.")
        case .keepReplies: return String(localized: "Der Kommentar wird gelöscht, die Antworten bleiben stehen.")
        case .single: return String(localized: "Der Kommentar wird gelöscht.")
        }
    }

    // MARK: Server

    private func reload() async {
        do {
            let d = try await store.api("api/notes/\(noteID)/comments" + (showResolved ? "?resolved=1" : ""))
            guard let s = CommentsState(data: d) else { return }
            st = s; loadError = nil; onUpdate(s)
        } catch { if st == nil { loadError = error.localizedDescription } else { self.error = String(localized: "Kommentare nicht geladen: \(error.localizedDescription)") } }
    }
    /// Anfrage mit Fehlertext vom Server (z. B. 429 "Zu viele Kommentare ...") -> true bei Erfolg
    private func call(_ path: String, _ method: String, _ body: [String: Any]?, fail: (String) -> String) async -> Bool {
        error = nil
        do {
            let (d, code) = try await store.apiResponse(path, method: method, body: body)
            guard (200..<300).contains(code) else { throw APIError.http(code, ShareJSON.obj(d).flatMap { ShareJSON.str($0["error"]) } ?? "") }
            return true
        } catch { self.error = fail(error.localizedDescription); return false }
    }
    private func add(_ v: String) async -> Bool {
        let ok = await call("api/notes/\(noteID)/comments", "POST", ["block_id": block ?? "", "body": v]) { String(localized: "Nicht gesendet: \($0)") }
        if ok { await reload() }
        return ok
    }
    private func reply(_ t: CommentThread, _ v: String) async -> Bool {
        let ok = await call("api/notes/\(noteID)/comments", "POST", ["thread_id": t.id, "body": v]) { String(localized: "Nicht gesendet: \($0)") }
        if ok { replyTo = nil; await reload() }
        return ok
    }
    private func edit(_ c: CommentItem, _ v: String) async -> Bool {
        let ok = await call("api/comments/\(c.id)", "PATCH", ["body": v]) { String(localized: "Nicht gespeichert: \($0)") }
        if ok { editing = nil; await reload() }
        return ok
    }
    private func delete(_ c: CommentItem, all: Bool) async {
        if await call("api/comments/\(c.id)" + (all ? "?all=1" : ""), "DELETE", nil, fail: { String(localized: "Nicht gelöscht: \($0)") }) { await reload() }
    }
    private func resolve(_ id: Int, _ on: Bool) async {
        guard await call("api/comments/\(id)/resolve", "POST", ["resolved": on], fail: { String(localized: "Nicht gespeichert: \($0)") }) else { return }
        expanded.remove(id)
        if on {
            undo = id
            Task { try? await Task.sleep(for: .seconds(6)); if undo == id { undo = nil } }
        }
        await reload()
    }
    /// Sichtbare, ungelesene Unterhaltungen als gelesen melden (der Punkt verschwindet)
    private func markSeen() async {
        let ids = ShareLogic.toMarkSeen(shown)
        guard !ids.isEmpty, var s = st else { return }
        for id in ids {
            guard (try? await store.api("api/comments/\(id)/seen", method: "POST", body: [:])) != nil else { continue }
            if let i = s.threads.firstIndex(where: { $0.id == id }) { s.threads[i].unread = false }
        }
        st = s; onUpdate(s)
    }
}
