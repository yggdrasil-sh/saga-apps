import SwiftUI
import UniformTypeIdentifiers
#if os(iOS)
import Photos
#else
import AppKit
#endif

// Chat-Medien 1: Bilder aus dem Chat sichern - iPhone/iPad in Fotos (nur Hinzufuegen, derselbe Weg wie bei Videos:
// ChatVideoSave.toPhotos, Klartext nur kurz als geschuetzte Kopie), Mac "Sichern unter …" (NSSavePanel).

@MainActor
enum ChatImageSave {
    /// Dateiname zum Sichern: ohne Pfadteile, mit Endung (Fotos erkennt das Format an der Endung)
    nonisolated static func fileName(_ f: ChatFileRef) -> String {
        var n = f.name.replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: ":", with: "_")
        if n.isEmpty { n = "bild" }
        if (n as NSString).pathExtension.isEmpty, let ext = UTType(mimeType: f.mime)?.preferredFilenameExtension { n += "." + ext }
        return n
    }

    #if os(iOS)
    /// Bilder (Original) in Fotos sichern -> Hinweis fuer die Anzeige
    static func toPhotos(_ files: [ChatFileRef], conv: ConversationModel) async -> String {
        do {
            for f in files {
                let d = try await conv.original(f)
                try await toPhotos(d, name: fileName(f))
            }
            return String(localized: "In Fotos gesichert.")
        } catch {
            return String(localized: "Nicht gesichert: \(error.localizedDescription)")
        }
    }
    /// Klartext als geschuetzte Kopie ablegen, an Fotos uebergeben, Kopie sofort wieder loeschen
    static func toPhotos(_ d: Data, name: String) async throws {
        let u = ChatVideoFiles.tempSave(name)
        defer { ChatVideoFiles.dropTemp(u) }
        try d.write(to: u, options: [.atomic, .completeFileProtection])
        try await ChatVideoSave.toPhotos(u, type: .photo)
    }
    #else
    /// Original laden und "Sichern unter …" zeigen -> Hinweis (nil = abgebrochen)
    static func saveAs(_ f: ChatFileRef, conv: ConversationModel) async -> String? {
        let d: Data
        do { d = try await conv.original(f) } catch { return String(localized: "Nicht gesichert: \(error.localizedDescription)") }
        return saveAs(d, name: fileName(f), mime: f.mime)
    }
    /// "Sichern unter …" fuer schon geladene Bilddaten -> Hinweis (nil = abgebrochen)
    static func saveAs(_ d: Data, name: String, mime: String) -> String? {
        let panel = NSSavePanel()
        panel.nameFieldStringValue = name
        if let t = UTType(mimeType: mime) { panel.allowedContentTypes = [t] }
        guard panel.runModal() == .OK, let dst = panel.url else { return nil }
        do {
            try d.write(to: dst, options: [.atomic])
            return String(localized: "Gesichert.")
        } catch {
            return String(localized: "Nicht gesichert: \(error.localizedDescription)")
        }
    }
    #endif
}

/// Kleines Vorschaubild im Antwort-Zitat bzw. in der Antwort-Leiste (nur Vorschaubild/Poster, nie das Video selbst)
struct ChatReplyThumb: View {
    let file: ChatFileRef
    @ObservedObject var conv: ConversationModel
    var side: CGFloat = 32
    @State private var img: ChatImage?
    var body: some View {
        ZStack {
            Rectangle().fill(Color.secondary.opacity(0.15))
            if let cg = (img ?? ChatMedia.cached(file.id))?.cg {
                Image(decorative: cg, scale: 1).resizable().interpolation(.medium).scaledToFill()
            } else {
                Image(systemName: file.isVideo ? "film" : "photo").font(.caption).foregroundStyle(.secondary)
            }
            if file.isVideo {
                Image(systemName: "play.fill").font(.system(size: side * 0.3)).foregroundStyle(.white).shadow(radius: 1)
            }
        }
        .frame(width: side, height: side)
        .clipShape(RoundedRectangle(cornerRadius: 5))
        .accessibilityHidden(true)
        .task(id: file.id) {
            if let m = ChatMedia.cached(file.id) { if img !== m { img = m }; return }
            if let m = try? await conv.thumbnail(file) { img = m }
        }
    }
}

/// Zitierte Nachricht: Text bzw. "Foto"/"Video"/"Sprachnachricht"/Dateiname
@MainActor
enum ChatReplyText {
    static func line(_ m: ChatMessage, conv: ConversationModel) -> String {
        let files = m.content?.files ?? []
        switch ChatReplyPreview.label(text: m.text, files: files) {
        case .text: return conv.plainText(m.text)
        case .photo: return String(localized: "Foto")
        case .video: return String(localized: "Video")
        case .voice: return String(localized: "Sprachnachricht")
        case .file(let n): return n.isEmpty ? String(localized: "Datei") : n
        case .none: return ""
        }
    }
}
