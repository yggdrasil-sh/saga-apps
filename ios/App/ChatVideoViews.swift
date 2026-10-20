import SwiftUI
import AVKit
import UniformTypeIdentifiers
import CoreTransferable
#if os(macOS)
import AppKit
#endif

// Videos im Chat (2.4) - Oberflaeche: im Verlauf nur das Poster (lazy, wie Vorschaubilder) mit Dauer und Abspiel-Symbol,
// nie ein Player je Zelle. Der Player (ein gemeinsamer AVPlayer, Bild im Bild) entsteht erst beim Antippen. Fortschritt
// laufender Uploads ueber dem Eingabefeld (Abbrechen, Erneut versuchen). Auswahl: Fotos (Video als Datei) und Dateien.

/// Video im Verlauf: Poster (aus "th"), Dauer, Abspiel-Symbol
struct ChatVideoThumb: View {
    let file: ChatFileRef
    @ObservedObject var conv: ConversationModel
    @State private var img: ChatImage?
    @State private var open = false
    var body: some View {
        let sz = ChatMediaLogic.displaySize(w: file.w ?? file.thumb?.w ?? 16, h: file.h ?? file.thumb?.h ?? 9)
        Button { open = true } label: {
            ZStack {
                Rectangle().fill(Color.black.opacity(0.82))
                if let cg = (img ?? ChatMedia.cached(file.id))?.cg {
                    Image(decorative: cg, scale: 1).resizable().interpolation(.medium).scaledToFill()
                } else {
                    Image(systemName: "film").font(.title2).foregroundStyle(.white.opacity(0.5))
                }
                Image(systemName: "play.circle.fill").font(.system(size: 44)).symbolRenderingMode(.palette)
                    .foregroundStyle(.white, .black.opacity(0.45)).shadow(radius: 3)
            }
            .frame(width: sz.w, height: sz.h).clipShape(RoundedRectangle(cornerRadius: 10)).contentShape(RoundedRectangle(cornerRadius: 10))
            .overlay(alignment: .bottomTrailing) {
                if let d = file.dur, d > 0 {
                    Text(verbatim: VideoSendLogic.durText(d)).font(.caption2.monospacedDigit().weight(.semibold)).foregroundStyle(.white)
                        .padding(.horizontal, 6).padding(.vertical, 2).background(.black.opacity(0.55), in: Capsule()).padding(6)
                }
            }
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text("Video abspielen: \(file.name.isEmpty ? String(localized: "Video") : file.name)"))
        .help(Text("Abspielen"))
        .task(id: file.id) { await load() }
        #if os(iOS)
        .fullScreenCover(isPresented: $open) { ChatVideoPlayerSheet(file: file, conv: conv) }
        #else
        .sheet(isPresented: $open) { ChatVideoPlayerSheet(file: file, conv: conv) }
        #endif
    }
    /// nur das Poster ("th", v1-Anhang) - nie das Video selbst
    private func load() async {
        guard file.thumb != nil else { return }
        if let m = ChatMedia.cached(file.id) { if img !== m { img = m }; return }
        if let m = try? await conv.thumbnail(file) { img = m }
    }
}

/// Neueres Format (x > 2): nicht still scheitern
struct ChatNewerFormatRow: View {
    let file: ChatFileRef
    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: "film").foregroundStyle(.secondary)
            VStack(alignment: .leading, spacing: 1) {
                Text(verbatim: file.name.isEmpty ? String(localized: "Datei") : file.name).font(.callout).lineLimit(1)
                Text("Mit einer neueren Saga-Version öffnen").font(.caption2).foregroundStyle(.secondary)
            }
        }
    }
}

// MARK: - Player

struct ChatVideoPlayerSheet: View {
    let file: ChatFileRef
    @ObservedObject var conv: ConversationModel
    @EnvironmentObject var store: Store
    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var vp = ChatVideoPlayer.shared
    @State private var source: ChatVideoSource?
    @State private var err: String?
    @State private var saving: Double?
    @State private var saveTask: Task<Void, Never>?
    @State private var toast: String?
    #if os(iOS)
    @State private var exportURL: ExportItem?
    #endif
    var body: some View {
        NavigationStack {
            ZStack {
                Color.black.ignoresSafeArea()
                if source != nil {
                    ChatPlayerView(player: vp.player).ignoresSafeArea(edges: .bottom)
                } else if let err {
                    Text("Video nicht geladen: \(err)").foregroundStyle(.white).padding()
                } else {
                    ZStack {
                        if let t = ChatMedia.cached(file.id)?.cg { Image(decorative: t, scale: 1).resizable().scaledToFit().opacity(0.6) }
                        ProgressView().tint(.white)
                    }
                }
            }
            .overlay(alignment: .bottom) {
                if let saving {
                    HStack(spacing: 10) {
                        ProgressView(value: saving).frame(width: 140)
                        HStack(spacing: 4) { Text("Wird gesichert …"); Text(verbatim: "\(Int(saving * 100)) %") }.font(.caption.monospacedDigit())
                        Button { saveTask?.cancel() } label: { Image(systemName: "xmark.circle.fill") }.buttonStyle(.plain).accessibilityLabel(Text("Abbrechen"))
                    }
                    .padding(10).background(.regularMaterial, in: Capsule()).padding(.bottom, 70)
                } else if let toast {
                    Text(verbatim: toast).font(.callout).padding(10).background(.regularMaterial, in: Capsule()).padding(.bottom, 70)
                }
            }
            .navigationTitle(Text(verbatim: file.name))
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Schließen") { dismiss() }.keyboardShortcut(.cancelAction) }
                ToolbarItem(placement: .primaryAction) { saveMenu.disabled(source == nil || saving != nil) }
            }
        }
        #if os(macOS)
        .frame(minWidth: 640, idealWidth: 960, minHeight: 420, idealHeight: 640)
        #endif
        #if os(iOS)
        .sheet(item: $exportURL) { u in ChatDocumentExporter(url: u.url) { ChatVideoFiles.dropTemp(u.url); exportURL = nil } }
        #endif
        .task(id: file.id) { await start() }
        .onDisappear { saveTask?.cancel(); ChatVideoPlayer.shared.stop() }
    }

    @ViewBuilder private var saveMenu: some View {
        #if os(iOS)
        Menu {
            Button { save(photos: true) } label: { Label("In Fotos sichern", systemImage: "photo.on.rectangle") }
            Button { save(photos: false) } label: { Label("In Dateien sichern", systemImage: "folder") }
        } label: { Label("Sichern", systemImage: "square.and.arrow.down") }
        #else
        Button { saveMac() } label: { Label("Sichern …", systemImage: "square.and.arrow.down") }
        #endif
    }

    private func start() async {
        do {
            let s: ChatVideoSource
            if file.isV2 { s = ChatVideoSource(file: file, store: store); _ = try await s.key() }   // Kopf pruefen, bevor der Player startet
            else { s = ChatVideoSource(file: file, store: store, plainV1: try await conv.original(file)) }   // v1 (bis 10 MB): im Speicher
            source = s
            ChatVideoPlayer.shared.play(s)
        } catch { if !(error is CancellationError) { err = error.localizedDescription } }
    }

    #if os(iOS)
    private func save(photos: Bool) {
        guard let s = source else { return }
        saveTask = Task {
            let dst = ChatVideoFiles.tempSave(file.name.isEmpty ? "video.mp4" : file.name)
            saving = 0
            do {
                try await ChatVideoSave.decrypt(s, to: dst) { p in Task { @MainActor in if saving != nil { saving = p } } }
                saving = nil
                if photos {
                    defer { ChatVideoFiles.dropTemp(dst) }               // Klartext sofort wieder weg
                    try await ChatVideoSave.toPhotos(dst)
                    flash(String(localized: "In Fotos gesichert."))
                } else { exportURL = ExportItem(url: dst) }                                // Dateien: Kopie, danach geloescht
            } catch {
                saving = nil; ChatVideoFiles.dropTemp(dst)
                if !(error is CancellationError) { flash(String(localized: "Nicht gesichert: \(error.localizedDescription)")) }
            }
        }
    }
    #else
    private func saveMac() {
        guard let s = source else { return }
        let panel = NSSavePanel()
        panel.nameFieldStringValue = file.name.isEmpty ? "video.mp4" : file.name
        if let t = UTType(mimeType: file.mime) { panel.allowedContentTypes = [t] }
        guard panel.runModal() == .OK, let dst = panel.url else { return }
        saveTask = Task {
            saving = 0
            do {
                try await ChatVideoSave.decrypt(s, to: dst) { p in Task { @MainActor in if saving != nil { saving = p } } }   // direkt als Strom
                saving = nil; flash(String(localized: "Gesichert."))
            } catch {
                saving = nil
                if !(error is CancellationError) { flash(String(localized: "Nicht gesichert: \(error.localizedDescription)")) }
            }
        }
    }
    #endif
    private func flash(_ s: String) {
        toast = s
        Task { try? await Task.sleep(for: .seconds(3)); if toast == s { toast = nil } }
    }
}

/// Datei fuer "In Dateien sichern"
struct ExportItem: Identifiable { let url: URL; var id: String { url.path } }

#if os(iOS)
/// AVPlayerViewController mit Bild im Bild
struct ChatPlayerView: UIViewControllerRepresentable {
    let player: AVPlayer
    func makeUIViewController(context: Context) -> AVPlayerViewController {
        let c = AVPlayerViewController()
        c.player = player
        c.allowsPictureInPicturePlayback = true
        c.canStartPictureInPictureAutomaticallyFromInline = true
        c.delegate = context.coordinator
        return c
    }
    func updateUIViewController(_ c: AVPlayerViewController, context: Context) { if c.player !== player { c.player = player } }
    func makeCoordinator() -> Coordinator { Coordinator() }
    final class Coordinator: NSObject, AVPlayerViewControllerDelegate {
        func playerViewControllerWillStartPictureInPicture(_ c: AVPlayerViewController) { MainActor.assumeIsolated { ChatVideoPlayer.shared.pip = true } }
        func playerViewControllerDidStopPictureInPicture(_ c: AVPlayerViewController) {
            MainActor.assumeIsolated { ChatVideoPlayer.shared.pip = false; if c.view.window == nil { ChatVideoPlayer.shared.stop() } }
        }
    }
}

/// "In Dateien sichern": Kopie der (temporaeren) Datei in einen gewaehlten Ordner
struct ChatDocumentExporter: UIViewControllerRepresentable {
    let url: URL
    var done: () -> Void
    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        let c = UIDocumentPickerViewController(forExporting: [url], asCopy: true)
        c.delegate = context.coordinator
        return c
    }
    func updateUIViewController(_ c: UIDocumentPickerViewController, context: Context) {}
    func makeCoordinator() -> Coordinator { Coordinator(done) }
    final class Coordinator: NSObject, UIDocumentPickerDelegate {
        let done: () -> Void
        init(_ d: @escaping () -> Void) { done = d }
        func documentPicker(_ c: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) { done() }
        func documentPickerWasCancelled(_ c: UIDocumentPickerViewController) { done() }
    }
}
#else
/// AVPlayerView mit Bild im Bild
struct ChatPlayerView: NSViewRepresentable {
    let player: AVPlayer
    func makeNSView(context: Context) -> AVPlayerView {
        let v = AVPlayerView()
        v.player = player; v.controlsStyle = .floating; v.allowsPictureInPicturePlayback = true; v.showsFullScreenToggleButton = true
        v.pictureInPictureDelegate = context.coordinator
        return v
    }
    func updateNSView(_ v: AVPlayerView, context: Context) { if v.player !== player { v.player = player } }
    func makeCoordinator() -> Coordinator { Coordinator() }
    final class Coordinator: NSObject, AVPlayerViewPictureInPictureDelegate {
        func playerViewWillStartPicture(inPicture playerView: AVPlayerView) { MainActor.assumeIsolated { ChatVideoPlayer.shared.pip = true } }
        func playerViewDidStopPicture(inPicture playerView: AVPlayerView) {
            MainActor.assumeIsolated { ChatVideoPlayer.shared.pip = false; if playerView.window == nil { ChatVideoPlayer.shared.stop() } }
        }
    }
}
#endif

// MARK: - Laufende Uploads ueber dem Eingabefeld

struct ChatVideoUploadsBar: View {
    let chatID: Int
    @ObservedObject private var up = ChatVideoUploads.shared
    var body: some View {
        let list = up.jobs(chat: chatID)
        if !list.isEmpty {
            VStack(spacing: 4) {
                ForEach(list) { j in row(j) }
            }
        }
    }
    private func row(_ j: VideoJob) -> some View {
        HStack(spacing: 8) {
            Image(systemName: j.stage == .failed ? "exclamationmark.triangle.fill" : "film")
                .foregroundStyle(j.stage == .failed ? Color.orange : Color.secondary)
            VStack(alignment: .leading, spacing: 2) {
                Text(verbatim: j.name).font(.caption.weight(.semibold)).lineLimit(1)
                if j.stage == .failed {
                    Text(verbatim: j.error ?? String(localized: "Nicht gesendet")).font(.caption2).foregroundStyle(.orange).lineLimit(2)
                } else {
                    Text(status(j)).font(.caption2.monospacedDigit()).foregroundStyle(.secondary).lineLimit(1)
                    if let p = up.progress[j.id], j.stage == .uploading || j.stage == .preparing { ProgressView(value: p).controlSize(.small) }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            if j.stage == .failed {
                Button { up.retry(j.id) } label: { Image(systemName: "arrow.clockwise") }.buttonStyle(.plain)
                    .help(Text("Erneut versuchen")).accessibilityLabel(Text("Erneut versuchen"))
            }
            Button { up.cancel(j.id) } label: { Image(systemName: "xmark.circle.fill") }.buttonStyle(.plain).foregroundStyle(.secondary)
                .help(Text("Abbrechen")).accessibilityLabel(Text("Upload abbrechen"))
        }
        .padding(.horizontal, 10).padding(.vertical, 6)
        .background(Color.secondary.opacity(0.10), in: RoundedRectangle(cornerRadius: 10))
        .accessibilityElement(children: .combine)
    }
    private func status(_ j: VideoJob) -> String {
        let p = Int(((up.progress[j.id] ?? 0) * 100).rounded())
        switch j.stage {
        case .queued: return String(localized: "Wartet …")
        case .preparing:
            if up.hot.contains(j.id) { return String(localized: "Pausiert – Gerät ist zu warm") }
            return up.progress[j.id] != nil ? String(localized: "Wird verkleinert (720p) …") + " \(p) %" : String(localized: "Wird vorbereitet …")
        case .uploading:
            let total = ByteCountFormatter.string(fromByteCount: j.total, countStyle: .file)
            return String(localized: "Wird hochgeladen (\(total)) …") + " \(p) %"
        case .sending: return String(localized: "Wird gesendet …")
        case .failed: return ""
        }
    }
}

// MARK: - Auswahl: Video als Datei (nie ganz im Speicher)

/// Video aus der Fotos-Auswahl: als Datei in die Ablage kopiert
struct PickedMovie: Transferable {
    let url: URL
    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(contentType: .movie) { m in SentTransferredFile(m.url) } importing: { r in
            PickedMovie(url: try ChatVideoFiles.adopt(r.file, ext: r.file.pathExtension))
        }
    }
}

enum ChatVideoPick {
    /// Standard: vom iPhone "optimiert 720p", sonst Original
    static var optimizeDefault: Bool {
        #if os(iOS)
        return UIDevice.current.userInterfaceIdiom == .phone
        #else
        return false
        #endif
    }
    /// Datei aus "Dateien"/Finder: Videos als Kopie in der Ablage (nil = kein Video)
    static func pending(fromFile u: URL) -> ChatPendingFile? {
        guard let t = UTType(filenameExtension: u.pathExtension), t.conforms(to: .movie) || t.conforms(to: .video) else { return nil }
        guard let local = try? ChatVideoFiles.adopt(u, ext: u.pathExtension) else { return nil }
        return ChatPendingFile(name: u.lastPathComponent, mime: t.preferredMIMEType ?? "video/mp4", data: Data(), fileURL: local, optimize: optimizeDefault)
    }
    static func pending(movie: PickedMovie, index: Int) -> ChatPendingFile {
        let ext = movie.url.pathExtension.isEmpty ? "mov" : movie.url.pathExtension.lowercased()
        let mime = UTType(filenameExtension: ext)?.preferredMIMEType ?? "video/quicktime"
        return ChatPendingFile(name: "video-\(index).\(ext)", mime: mime, data: Data(), fileURL: movie.url, optimize: optimizeDefault)
    }
}

/// Chip eines Videos vor dem Senden: "optimiert 720p" / "Original"
struct PendingVideoToggle: View {
    @Binding var file: ChatPendingFile
    var body: some View {
        Menu {
            Picker("Qualität", selection: $file.optimize) {
                Text("Optimiert (720p)").tag(true)
                Text("Original").tag(false)
            }
        } label: {
            (file.optimize ? Text(verbatim: "720p") : Text("Original")).font(.caption2.weight(.semibold))
                .padding(.horizontal, 6).padding(.vertical, 2).background(Color.gold.opacity(0.18), in: Capsule())
        }
        .menuIndicator(.hidden).fixedSize()
        .accessibilityLabel(Text("Videoqualität"))
    }
}
