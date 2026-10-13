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
    @State private var kind = "page"
    @State private var busy = false
    @State private var error = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Button("Abbrechen") { done(false) }
                Spacer()
                Text("In Saga speichern").font(.headline)
                Spacer()
                Button(busy ? "…" : "Sichern") { Task { await save() } }.bold().disabled(busy || !AppConfig.hasToken)
            }
            Picker("", selection: $kind) {
                Text("📥 Seite im Eingang").tag("page")
                Text("✓ Aufgabe").tag("task")
            }.pickerStyle(.segmented).labelsHidden()
            TextField("Titel", text: $content.title).textFieldStyle(.roundedBorder)
            if let u = content.url { Label(u, systemImage: "link").font(.caption).lineLimit(2).foregroundStyle(.secondary) }
            if !content.text.isEmpty && kind == "page" {
                Text(content.text).font(.caption).lineLimit(6).foregroundStyle(.secondary)
            }
            if !AppConfig.hasToken {
                Text("Noch kein App-Schlüssel – bitte einmal die Saga-App öffnen und unter Einstellungen eintragen.").font(.caption).foregroundStyle(.orange)
            }
            if !error.isEmpty { Text(error).font(.caption).foregroundStyle(.red) }
            Spacer(minLength: 0)
        }
        .padding()
        .frame(minWidth: 360, minHeight: 240)
    }

    private func save() async {
        busy = true; error = ""
        do {
            try await API.capture(kind: kind, title: content.title, text: kind == "page" ? content.text : "", url: content.url)
            done(true)
        } catch { self.error = error.localizedDescription; busy = false }
    }
}
