import SwiftUI
import UniformTypeIdentifiers

/// Inhalte aus dem Teilen-Blatt auslesen (Link, Text, Titel) - gleich fuer iOS und macOS.
struct SharedContent { var url: String?; var text: String; var title: String }

func readShared(_ items: [NSExtensionItem]) async -> SharedContent {
    var c = SharedContent(url: nil, text: "", title: "")
    for item in items {
        if c.title.isEmpty, let t = item.attributedTitle?.string ?? item.attributedContentText?.string { c.title = t }
        for p in item.attachments ?? [] {
            if c.url == nil, p.hasItemConformingToTypeIdentifier(UTType.url.identifier),
               let u = try? await p.loadItem(forTypeIdentifier: UTType.url.identifier) as? URL, ["http", "https"].contains(u.scheme ?? "") {
                c.url = u.absoluteString
            } else if p.hasItemConformingToTypeIdentifier(UTType.plainText.identifier),
                      let s = try? await p.loadItem(forTypeIdentifier: UTType.plainText.identifier) as? String {
                if c.url == nil, let u = URL(string: s.trimmingCharacters(in: .whitespacesAndNewlines)), ["http", "https"].contains(u.scheme ?? ""), u.host != nil {
                    c.url = u.absoluteString
                } else { c.text += (c.text.isEmpty ? "" : "\n\n") + s }
            }
        }
    }
    return c
}

struct ShareView: View {
    @State var content: SharedContent
    var done: (Bool) -> Void
    @State private var kind: String
    @State private var busy = false
    @State private var error = ""
    // Lesezeichen (2.5)
    @State private var name: String
    @State private var category = ""
    @State private var categories: [String] = []
    @State private var standardCategory = String(localized: "Lesezeichen")
    @State private var bookmarkUnsupported = false      // Server ohne /api/app/bookmarks (vor 2.5)

    init(content: SharedContent, done: @escaping (Bool) -> Void) {
        _content = State(initialValue: content)
        self.done = done
        _kind = State(initialValue: ShareBookmarkLogic.initialKind(last: AppConfig.store.string(forKey: ShareBookmarkLogic.lastKindKey),
                                                                   hasURL: content.url != nil))
        _name = State(initialValue: ShareBookmarkLogic.defaultName(title: content.title, url: content.url))
    }

    private var canSave: Bool {
        guard !busy, AppConfig.hasToken else { return false }
        return kind != "bookmark" || (content.url != nil && !bookmarkUnsupported)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Button("Abbrechen") { done(false) }
                Spacer()
                Text("In Saga speichern").font(.headline)
                Spacer()
                Button(busy ? "…" : "Sichern") { Task { await save() } }.bold().disabled(!canSave)
            }
            Picker("", selection: $kind) {
                Text("📥 Seite im Eingang").tag("page")
                Text("✓ Aufgabe").tag("task")
                if content.url != nil { Text("🔖 Lesezeichen").tag("bookmark") }
            }.pickerStyle(.segmented).labelsHidden()
            if kind == "bookmark" {
                TextField("Name", text: $name).textFieldStyle(.roundedBorder)
                HStack(spacing: 6) {
                    TextField("Kategorie", text: $category).textFieldStyle(.roundedBorder)
                    if !categories.isEmpty {
                        Menu {
                            ForEach(categories, id: \.self) { c in Button(c) { category = c } }
                        } label: { Image(systemName: "chevron.up.chevron.down") }
                        .accessibilityLabel(Text("Kategorie wählen"))
                        .fixedSize()
                    }
                }
            } else {
                TextField("Titel", text: $content.title).textFieldStyle(.roundedBorder)
            }
            if let u = content.url { Label(u, systemImage: "link").font(.caption).lineLimit(2).foregroundStyle(.secondary) }
            if !content.text.isEmpty && kind == "page" {
                Text(content.text).font(.caption).lineLimit(6).foregroundStyle(.secondary)
            }
            if kind == "bookmark" && !bookmarkUnsupported {
                Text("Erscheint unter „Dienste“. Ein Symbol kannst du später in der App wählen.").font(.caption).foregroundStyle(.secondary)
            }
            if kind == "bookmark" && bookmarkUnsupported {
                Text("Dein Saga-Server kann noch keine Lesezeichen aus dem Teilen-Blatt anlegen (ab Saga 2.5).").font(.caption).foregroundStyle(.orange)
            }
            if !AppConfig.hasToken {
                Text("Noch kein App-Schlüssel – bitte einmal die Saga-App öffnen und unter Einstellungen eintragen.").font(.caption).foregroundStyle(.orange)
            }
            if !error.isEmpty { Text(error).font(.caption).foregroundStyle(.red) }
            Spacer(minLength: 0)
        }
        .padding()
        .frame(minWidth: 360, minHeight: 240)
        .task { await loadCategories() }
    }

    /// Kategorien nur laden, wenn ein Link dabei ist; ohne Antwort bleibt der Standard (Fehler zeigt erst "Sichern").
    private func loadCategories() async {
        guard content.url != nil else { return }
        var server: [String] = []
        do {
            let r = try await API.bookmarkCategories()
            server = r.categories
            if let d = r.default, !d.isEmpty { standardCategory = d }
        } catch APIError.http(404, _) {
            bookmarkUnsupported = true
        } catch {}
        categories = ShareBookmarkLogic.categories(server: server, standard: standardCategory)
        if category.isEmpty {
            category = ShareBookmarkLogic.initialCategory(last: AppConfig.store.string(forKey: ShareBookmarkLogic.lastCategoryKey),
                                                          available: categories, standard: standardCategory)
        }
    }

    private func save() async {
        busy = true; error = ""
        do {
            if kind == "bookmark", let u = content.url {
                let body = ShareBookmarkLogic.payload(name: name, url: u, category: category)
                try await API.addBookmark(body)
                AppConfig.store.set(body["category"] as? String, forKey: ShareBookmarkLogic.lastCategoryKey)
            } else {
                try await API.capture(kind: kind, title: content.title, text: kind == "page" ? content.text : "", url: content.url)
            }
            AppConfig.store.set(kind, forKey: ShareBookmarkLogic.lastKindKey)
            done(true)
        } catch { self.error = error.localizedDescription; busy = false }
    }
}
