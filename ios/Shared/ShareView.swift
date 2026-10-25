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

/// Saga-Gold (wie AccentColor der App; die Erweiterungen haben keinen eigenen Farbkatalog)
extension Color {
    static let shareGold: Color = {
        #if os(iOS)
        return Color(UIColor { t in t.userInterfaceStyle == .dark ? UIColor(red: 0xC9 / 255, green: 0xA4 / 255, blue: 0x5C / 255, alpha: 1)
                                                                  : UIColor(red: 0x7A / 255, green: 0x5A / 255, blue: 0x12 / 255, alpha: 1) })
        #else
        return Color(NSColor(name: nil) { a in a.bestMatch(from: [.darkAqua, .aqua]) == .darkAqua ? NSColor(red: 0xC9 / 255, green: 0xA4 / 255, blue: 0x5C / 255, alpha: 1)
                                                                                          : NSColor(red: 0x7A / 255, green: 0x5A / 255, blue: 0x12 / 255, alpha: 1) })
        #endif
    }()
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
    @State private var newCategory = false             // "Neue Kategorie …": freie Eingabe
    @FocusState private var categoryFocus: Bool
    @State private var standardCategory = String(localized: "Lesezeichen")
    @State private var bookmarkUnsupported = false      // Server ohne /api/app/bookmarks (vor 2.5)
    private let preset: [String]?                       // nur Bilder (Debug): Kategorien ohne Server

    init(content: SharedContent, categories preset: [String]? = nil, done: @escaping (Bool) -> Void) {
        _content = State(initialValue: content)
        self.done = done
        self.preset = preset
        _kind = State(initialValue: ShareBookmarkLogic.initialKind(last: AppConfig.store.string(forKey: ShareBookmarkLogic.lastKindKey),
                                                                   hasURL: content.url != nil))
        _name = State(initialValue: ShareBookmarkLogic.defaultName(title: content.title, url: content.url))
    }

    private var hasToken: Bool { preset != nil || AppConfig.hasToken }     // Bild (Debug): wie eingerichtet
    @ScaledMetric private var labelWidth: CGFloat = 86
    private static let newTag = "\u{1}neu"

    private var canSave: Bool {
        guard !busy, hasToken else { return false }
        if kind == "bookmark" && newCategory && category.trimmingCharacters(in: .whitespaces).isEmpty { return false }
        return kind != "bookmark" || (content.url != nil && !bookmarkUnsupported)
    }

    /// Arten: Seite im Eingang, Aufgabe, Lesezeichen (nur mit Link) - mit SF Symbols statt Emoji
    private var kinds: [(tag: String, title: LocalizedStringKey, icon: String)] {
        var k: [(String, LocalizedStringKey, String)] = [("page", content.url != nil ? "Eingang" : "Seite im Eingang", "tray.and.arrow.down"),
                                                        ("task", "Aufgabe", "checkmark.circle")]
        if content.url != nil { k.append(("bookmark", "Lesezeichen (Segment)", "bookmark")) }
        return k
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Button("Abbrechen") { done(false) }
                Spacer()
                Text("In Saga speichern").font(.headline)
                Spacer()
                Button { Task { await save() } } label: { if busy { ProgressView().controlSize(.small) } else { Text("Speichern") } }
                    .bold().disabled(!canSave)
            }
            kindPicker
            if kind == "bookmark" {
                HStack(spacing: 8) {
                    Text("Name").foregroundStyle(.secondary).frame(width: labelWidth, alignment: .leading)
                    TextField("Name", text: $name).textFieldStyle(.roundedBorder)
                }
                HStack(spacing: 8) {
                    Text("Kategorie").foregroundStyle(.secondary).frame(width: labelWidth, alignment: .leading)
                    categoryPicker
                    Spacer(minLength: 0)
                }
                if newCategory {
                    HStack(spacing: 8) {
                        Color.clear.frame(width: labelWidth, height: 1)
                        TextField("Neue Kategorie", text: $category).textFieldStyle(.roundedBorder).focused($categoryFocus)
                            .onChange(of: category) { _, v in if v.count > ShareBookmarkLogic.categoryMax { category = String(v.prefix(ShareBookmarkLogic.categoryMax)) } }
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
            if !hasToken {
                Text("Noch kein App-Schlüssel – bitte einmal die Saga-App öffnen und unter Einstellungen eintragen.").font(.caption).foregroundStyle(.orange)
            }
            if !error.isEmpty { Text(error).font(.caption).foregroundStyle(.red) }
            Spacer(minLength: 0)
        }
        .padding()
        .frame(minWidth: 360, minHeight: 240)
        .tint(.shareGold)
        .task { await loadCategories() }
    }

    /// Umschalter der Art: eigene Segmente, damit Symbol und Text zusammen passen (Segment-Picker zeigt nur eins)
    private var kindPicker: some View {
        HStack(spacing: 2) {
            ForEach(kinds, id: \.tag) { k in
                let on = kind == k.tag
                Button { kind = k.tag } label: {
                    Label(k.title, systemImage: k.icon).labelStyle(.titleAndIcon)
                        .font(.subheadline.weight(on ? .semibold : .regular)).lineLimit(1).minimumScaleFactor(0.85)
                        .frame(maxWidth: .infinity, minHeight: 32).padding(.horizontal, 4)
                        .foregroundStyle(on ? Color.shareGold : Color.primary)
                        .background { if on { RoundedRectangle(cornerRadius: 8).fill(.background).shadow(color: .black.opacity(0.12), radius: 2, y: 1) } }
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(on ? .isSelected : [])
            }
        }
        .padding(2)
        .background(Color.secondary.opacity(0.14), in: RoundedRectangle(cornerRadius: 10))
    }

    /// Kategorie als Menue-Zeile: bekannte Kategorien und "Neue Kategorie …" (freie Eingabe darunter)
    private var categoryPicker: some View {
        let options = categories.isEmpty ? [standardCategory] : categories
        return Picker(selection: Binding(get: { newCategory ? Self.newTag : (category.isEmpty ? standardCategory : category) },
                                         set: { v in
                                             if v == Self.newTag { newCategory = true; category = ""; categoryFocus = true }
                                             else { newCategory = false; category = v }
                                         })) {
            ForEach(options, id: \.self) { c in Text(verbatim: c).tag(c) }
            Divider()
            Text("Neue Kategorie …").tag(Self.newTag)
        } label: { Text("Kategorie") }
            .pickerStyle(.menu).labelsHidden().fixedSize()
    }

    /// Kategorien nur laden, wenn ein Link dabei ist; ohne Antwort bleibt der Standard (Fehler zeigt erst "Speichern").
    private func loadCategories() async {
        guard content.url != nil else { return }
        var server: [String] = []
        if let preset { server = preset } else { do {
            let r = try await API.bookmarkCategories()
            server = r.categories
            if let d = r.default, !d.isEmpty { standardCategory = d }
        } catch APIError.http(404, _) {
            bookmarkUnsupported = true
        } catch {} }
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
