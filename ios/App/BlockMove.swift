import SwiftUI

// Saga 2.5 (P6-4, move1): Block auf eine andere Seite verschieben - Seitenauswahl, POST /api/notes/{a}/blocks/move,
// vorher speichern, 409 -> speichern/neu laden und einmal erneut, Rueckgaengig = Gegenrichtung, danach beide neu laden.
// Logik: MoveLogic (MergeLogic.swift).

struct BlockMoveRequest: Identifiable, Equatable { var ids: [String]; var id: String { ids.joined(separator: ",") } }

/// Letzte Verschiebung (fuer Rueckgaengig)
struct BlockMoveDone: Equatable {
    var to: Int
    var toTitle: String
    var response: [String: Any]
    var prev: String?
    static func == (a: BlockMoveDone, b: BlockMoveDone) -> Bool { a.to == b.to && (a.response["ids"] as? [String]) == (b.response["ids"] as? [String]) }
}

extension PageEditor {
    /// Verschieben: vorher speichern; veraltet (409) -> neu laden und einmal erneut
    func moveBlocks(_ ids: [String], to target: Int, store: Store) async -> String? {
        await flush()
        if doc.dirty { return String(localized: "Erst speichern – die Seite ist noch nicht gespeichert.") }
        let prev = ids.first.flatMap { MoveLogic.previous(of: $0, in: doc.root) }
        for attempt in 0..<2 {
            let revB = (try? await store.api("api/notes/\(target)")).flatMap { ShareJSON.obj($0)?["rev"] as? Int }
            let body = MoveLogic.body(ids: ids, to: target, revA: doc.currentRev, revB: revB)
            guard let (d, code) = try? await store.apiResponse("api/notes/\(doc.id)/blocks/move", method: "POST", body: body) else {
                return String(localized: "Server nicht erreichbar")
            }
            let j = ShareJSON.obj(d)
            switch MoveLogic.outcome(code: code, json: j) {
            case .ok:
                focus = nil
                lastMove = BlockMoveDone(to: target, toTitle: store.page(target)?.label ?? "", response: j ?? [:], prev: prev)
                await doc.load(store, force: true); store.dataChanged(); await store.refresh(quiet: true)
                return nil
            case .conflict where attempt == 0:
                await doc.load(store); if doc.dirty { await flush() }
            case .conflict:
                return String(localized: "Die Seite wurde gerade geändert – bitte noch einmal versuchen.")
            case .message(let m): return m
            }
        }
        return nil
    }
    func undoMove(store: Store) async -> String? {
        guard let m = lastMove, let body = MoveLogic.undoBody(response: m.response, from: doc.id, prevBlock: m.prev) else { return nil }
        lastMove = nil
        guard let (d, code) = try? await store.apiResponse("api/notes/\(m.to)/blocks/move", method: "POST", body: body) else { return String(localized: "Server nicht erreichbar") }
        await doc.load(store, force: true); store.dataChanged()
        if case .message(let e) = MoveLogic.outcome(code: code, json: ShareJSON.obj(d)) { return e }
        if code == 409 { return String(localized: "Die Seite wurde gerade geändert – bitte noch einmal versuchen.") }
        return nil
    }
}

/// Seitenauswahl "Auf andere Seite verschieben …"
struct BlockMoveSheet: View {
    let request: BlockMoveRequest
    @ObservedObject var ed: PageEditor
    @EnvironmentObject var store: Store
    @Environment(\.dismiss) private var dismiss
    @State private var q = ""
    @State private var busy = false
    @State private var error: String?
    var body: some View {
        NavigationStack {
            let targets = (store.pages + store.shared).filter { p in
                MoveLogic.allowed(target: p.id, source: ed.doc.id, isDatabase: p.isDatabase) && (p.role == nil || p.role == "owner" || p.role == "edit")
                    && (q.isEmpty || p.label.localizedCaseInsensitiveContains(q))
            }
            List {
                if let error { Text(error).foregroundStyle(.red).font(.footnote) }
                ForEach(targets.prefix(200)) { p in
                    Button {
                        Task {
                            busy = true; error = await ed.moveBlocks(request.ids, to: p.id, store: store); busy = false
                            if error == nil { dismiss() }
                        }
                    } label: {
                        HStack {
                            PageIcon(icon: p.icon, store: store)
                            VStack(alignment: .leading, spacing: 1) {
                                Text(p.label)
                                let path = store.path(p.id).map(\.label).joined(separator: " / ")
                                if !path.isEmpty { Text(path).font(.caption).foregroundStyle(.secondary).lineLimit(1) }
                            }
                            Spacer(minLength: 0)
                        }.contentShape(Rectangle())
                    }.buttonStyle(.plain).disabled(busy)
                }
            }
            .overlay { if busy { ProgressView() } }
            .searchable(text: $q, prompt: "Seite suchen")
            .navigationTitle("Auf andere Seite verschieben")
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Abbrechen") { dismiss() } } }
        }
        .macFrame(minWidth: 380, minHeight: 460)
    }
}

/// "Verschoben nach … – Rückgängig"
struct BlockMoveUndoBar: View {
    let done: BlockMoveDone
    @ObservedObject var ed: PageEditor
    @EnvironmentObject var store: Store
    @State private var error: String?
    var body: some View {
        HStack(spacing: 8) {
            Label("Verschoben nach „\(done.toTitle)“", systemImage: "arrow.turn.up.right").font(.caption).lineLimit(1)
            Spacer(minLength: 4)
            Button("Rückgängig") { Task { error = await ed.undoMove(store: store) } }.font(.caption.weight(.semibold)).buttonStyle(.borderless)
            Button { ed.lastMove = nil } label: { Image(systemName: "xmark").font(.caption2) }.buttonStyle(.plain).foregroundStyle(.tertiary).accessibilityLabel(Text("Schließen"))
        }
        .padding(.horizontal, 10).padding(.vertical, 6)
        .background(.secondary.opacity(0.1), in: RoundedRectangle(cornerRadius: 8))
        if let error { Text(error).font(.caption).foregroundStyle(.orange) }
    }
}
