import SwiftUI
#if os(macOS)
import AppKit
#endif

// Seiten-Funktionen wie im Web: Tags, "Verlinkt von", Versionsverlauf, Export, Inhaltsverzeichnis.

// MARK: - Tags unter dem Titel

struct TagsRow: View {
    @ObservedObject var doc: PageDoc
    @EnvironmentObject var store: Store
    @EnvironmentObject var ed: PageEditor
    @State private var adding = false
    @State private var text = ""
    @FocusState private var focus: Bool
    private var tags: [String] { doc.page["tags"] as? [String] ?? [] }
    private var known: [String] { Array(Set(store.pages.flatMap { $0.tags ?? [] })).sorted() }

    var body: some View {
        WrapLayout(spacing: 6, lineSpacing: 6) {
            ForEach(tags, id: \.self) { t in
                HStack(spacing: 3) {
                    Text("#" + t).font(.caption)
                    if !ed.locked { Button { set(tags.filter { $0 != t }) } label: { Image(systemName: "xmark").font(.caption2) }.buttonStyle(.plain).foregroundStyle(.secondary).accessibilityLabel("Tag entfernen") }
                }
                .padding(.horizontal, 7).padding(.vertical, 2).background(.secondary.opacity(0.15), in: Capsule())
            }
            if !ed.locked {
                if adding {
                    TextField("Tag", text: $text).textFieldStyle(.plain).font(.caption).frame(width: 110).focused($focus)
                        .onSubmit { add() }.onChange(of: focus) { _, f in if !f { add(); adding = false } }
                        .popover(isPresented: Binding(get: { focus && !suggest.isEmpty }, set: { _ in })) {
                            VStack(alignment: .leading, spacing: 2) {
                                ForEach(suggest, id: \.self) { s in Button("#" + s) { text = s; add() }.buttonStyle(.plain).padding(.vertical, 3) }
                            }.padding(8)
                        }
                } else {
                    Button { adding = true; DispatchQueue.main.async { focus = true } } label: { Text("+ Tag").font(.caption).foregroundStyle(.tertiary) }.buttonStyle(.plain)
                }
            }
        }
    }
    private var suggest: [String] {
        let q = text.lowercased(); return known.filter { !tags.contains($0) && (q.isEmpty || $0.lowercased().contains(q)) }.prefix(6).map { $0 }
    }
    private func add() {
        let t = text.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: "#", with: "")
        text = ""
        guard !t.isEmpty, !tags.contains(t) else { return }
        set(tags + [String(t.prefix(30))])
    }
    private func set(_ new: [String]) {
        doc.page["tags"] = new
        Task { do { try await store.patch(doc.id, ["tags": new]); await store.refresh() } catch { doc.error = error.localizedDescription } }
    }
}

// MARK: - Verlinkt von (Rueckverweise)

struct BacklinksView: View {
    let id: Int
    @EnvironmentObject var store: Store
    @EnvironmentObject var nav: NavModel
    @State private var list: [Page] = []
    var body: some View {
        Group {
            if !list.isEmpty {
                VStack(alignment: .leading, spacing: 4) {
                    Divider().padding(.vertical, 8)
                    Text("Verlinkt von").font(.caption).foregroundStyle(.secondary)
                    ForEach(list) { p in
                        Button { nav.push(PageRoute(id: p.id)) } label: {
                            HStack(spacing: 6) { PageIcon(icon: p.icon, store: store); Text(p.label) }.padding(.vertical, 3)
                        }.buttonStyle(.plain)
                    }
                }
            }
        }
        .task(id: id) {
            if let d = try? await store.api("api/notes/\(id)/backlinks"), let l = try? JSONDecoder().decode([Page].self, from: d) { list = l }
        }
    }
}

// MARK: - Versionsverlauf

struct VersionsSheet: View {
    let id: Int
    let restored: () -> Void
    @EnvironmentObject var store: Store
    @Environment(\.dismiss) private var dismiss
    struct V: Decodable, Identifiable { let id: Int; let title: String; let created: Double; var author: A? }
    /// Wer die Fassung geschrieben hat (1.3; nil = du selbst)
    struct A: Decodable { let name: String? }
    struct P: Decodable { let title: String; let text: String }
    @State private var list: [V] = []
    @State private var sel: Int?
    @State private var prev: P?
    @State private var loaded = false
    #if os(iOS)
    @Environment(\.horizontalSizeClass) private var hsc
    private var compact: Bool { hsc == .compact }
    #else
    private let compact = false
    #endif
    var body: some View {
        NavigationStack {
            (compact ? AnyLayout(VStackLayout(spacing: 0)) : AnyLayout(HStackLayout(spacing: 0))) {     // iPhone: Liste oben, Vorschau darunter
                List(list, selection: $sel) { v in
                    VStack(alignment: .leading) {
                        Text(Date(timeIntervalSince1970: v.created).formatted(date: .abbreviated, time: .shortened)).font(.subheadline)
                        Text(v.title.isEmpty ? String(localized: "Unbenannt") : v.title).font(.caption).foregroundStyle(.secondary)
                        if let n = v.author?.name, !n.isEmpty { Text("von \(n)").font(.caption).foregroundStyle(.secondary) }
                    }.tag(v.id)
                }
                .frame(width: compact ? nil : 230, height: compact ? 220 : nil)
                Divider()
                ScrollView {
                    VStack(alignment: .leading, spacing: 10) {
                        if let prev {
                            Text(prev.title.isEmpty ? String(localized: "Unbenannt") : prev.title).font(.title3.bold())
                            Text(prev.text.isEmpty ? String(localized: "(leer)") : prev.text).font(.body).textSelection(.enabled)
                        } else {
                            Text(loaded && list.isEmpty ? "Noch keine früheren Versionen. Beim Bearbeiten wird höchstens alle 10 Minuten ein Stand gesichert (bis zu 50 pro Seite)."
                                                        : "Version auswählen, um sie anzusehen.").foregroundStyle(.secondary)
                        }
                    }.padding(16).frame(maxWidth: .infinity, alignment: .leading)
                }
            }
            .navigationTitle("Versionsverlauf")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Schließen") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Diese Version wiederherstellen") {
                        guard let sel else { return }
                        Task { _ = try? await store.api("api/notes/\(id)/versions/\(sel)/restore", method: "POST", body: [:]); restored(); dismiss() }
                    }.disabled(sel == nil)
                }
            }
        }
        .macFrame(minWidth: 680, minHeight: 440)
        .task {
            if let d = try? await store.api("api/notes/\(id)/versions"), let l = try? JSONDecoder().decode([V].self, from: d) { list = l }
            loaded = true
        }
        .onChange(of: sel) { _, v in
            prev = nil
            guard let v else { return }
            Task { if let d = try? await store.api("api/notes/\(id)/versions/\(v)"), let p = try? JSONDecoder().decode(P.self, from: d) { prev = p } }
        }
    }
}

// MARK: - Export (Markdown / mit Unterseiten als .zip)

@MainActor
func exportPage(_ id: Int, title: String, withSub: Bool, store: Store) async -> String? {
    do {
        let data = try await store.api("api/notes/\(id)/export" + (withSub ? "?sub=1" : ""))
        let base = title.isEmpty ? String(localized: "Seite") : title.replacingOccurrences(of: "/", with: "-")
        #if os(macOS)
        let panel = NSSavePanel()
        panel.nameFieldStringValue = base + (withSub ? ".zip" : ".md")
        panel.canCreateDirectories = true
        guard panel.runModal() == .OK, let url = panel.url else { return nil }
        try data.write(to: url)
        return nil
        #else
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(base + (withSub ? ".zip" : ".md"))
        try data.write(to: url)
        return url.path
        #endif
    } catch { return String(localized: "Export fehlgeschlagen: \(error.localizedDescription)") }
}

// MARK: - Inhaltsverzeichnis (Mac: rechts neben der Seite, ab 3 Ueberschriften)

struct TOCItem: Identifiable { let id: String; let text: String; let level: Int }

func tocItems(_ root: NSArray) -> [TOCItem] {
    var out: [TOCItem] = []
    func walk(_ a: NSArray) {
        for case let b as NSDictionary in a {
            let d = b["data"] as? NSDictionary
            if b["type"] as? String == "header", let id = b["id"] as? String {
                let t = plain(d?["text"] as? String ?? "")
                if !t.trimmingCharacters(in: .whitespaces).isEmpty { out.append(TOCItem(id: id, text: t, level: d?["level"] as? Int ?? 2)) }
            }
            if let cols = d?["cols"] as? NSArray { for case let c as NSDictionary in cols { if let inner = c["blocks"] as? NSArray { walk(inner) } } }
        }
    }
    walk(root)
    return out
}

struct PageTOC: View {
    let items: [TOCItem]
    let go: (String) -> Void
    @State private var open = true
    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Button { withAnimation { open.toggle() } } label: {
                Label("Inhalt", systemImage: "list.bullet.indent").font(.caption.weight(.semibold)).foregroundStyle(.secondary)
            }.buttonStyle(.plain)
            if open {
                ForEach(items) { it in
                    Button { go(it.id) } label: {
                        Text(it.text).font(.caption).lineLimit(1).foregroundStyle(.secondary)
                            .padding(.leading, CGFloat(max(0, it.level - 1)) * 10)
                    }.buttonStyle(.plain)
                }
            }
        }
        .padding(10).frame(width: 200, alignment: .leading)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 10))
    }
}
