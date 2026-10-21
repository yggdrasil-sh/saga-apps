import Foundation
import AVFoundation
import UniformTypeIdentifiers
import ImageIO
import Network
#if os(iOS)
import Photos
#endif

// Videos im Chat (Saga 2.4, Etappe E2; Vertrag docs/APP-API.md "Videos im Chat: saga-file/v2"):
// - Absender: Poster (320 px, JPEG <= 30 KB), Dauer und Abmessungen mit AVAssetImageGenerator; "optimiert 720p" per
//   AVAssetExportSession (Preset 1280x720, faststart), hoechstens ein Export gleichzeitig, bei Waerme pausiert.
// - Wiedergabe: AVURLAsset mit eigenem Schema saga-media:// und AVAssetResourceLoaderDelegate - Range-Anfragen mit
//   Medien-Token, blockweise entschluesselt (nie ein Komplett-Download, Klartext nie auf der Platte), Ciphertext-Cache
//   512 MB LRU (getrennt vom Bildcache). Ein gemeinsamer AVPlayer, Bild im Bild erlaubt.
// - Speichern: Entschluesseln als Datenstrom in eine Datei (iOS: Fotos nur Hinzufuegen bzw. Dateien; Mac: Sichern-Dialog).
// Upload (fortsetzbar, im Hintergrund): ChatVideoUploads.swift. Oberflaeche: ChatVideoViews.swift.

enum ChatVideoError: LocalizedError {
    case unreadable, header, network(Int), crypto, tooBig(Int), peerOld(Int), serverOld(Int), cancelled, photos
    var errorDescription: String? {
        switch self {
        case .unreadable: return String(localized: "Video nicht lesbar")
        case .header: return String(localized: "Video beschädigt oder unvollständig")
        case .network(let c): return c == 0 ? String(localized: "Server nicht erreichbar") : String(localized: "Serverfehler \(c)")
        case .crypto: return String(localized: "Nicht entschlüsselbar")
        case .tooBig(let mb): return String(localized: "Das Video ist zu groß (höchstens \(mb) MB).")
        case .peerOld(let mb): return String(localized: "Der Server des Gegenübers kann große Videos noch nicht (höchstens \(mb) MB).")
        case .serverOld(let mb): return String(localized: "Dieser Server kann große Videos noch nicht (höchstens \(mb) MB).")
        case .cancelled: return String(localized: "Abgebrochen")
        case .photos: return String(localized: "Kein Zugriff auf Fotos – bitte in den Einstellungen erlauben.")
        }
    }
}

// MARK: - Ablage vor dem Senden (Kopie im App-Ordner, bis die Nachricht gesendet ist)

enum ChatVideoFiles {
    /// Application Support/hk/chat-video (nicht im Backup, iOS: geschuetzt bis zum ersten Entsperren - Uploads im Hintergrund)
    static var dir: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("hk", isDirectory: true).appendingPathComponent("chat-video", isDirectory: true)
        if !FileManager.default.fileExists(atPath: base.path) {
            try? FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
            var u = base; var v = URLResourceValues(); v.isExcludedFromBackup = true; try? u.setResourceValues(v)
        }
        return base
    }
    static func url(_ name: String) -> URL { dir.appendingPathComponent(name, isDirectory: false) }
    /// Datei uebernehmen (kopieren, nie ganz in den Speicher) -> Name in der Ablage
    static func adopt(_ src: URL, ext: String) throws -> URL {
        let dst = url(UUID().uuidString + "." + (ext.isEmpty ? "mp4" : ext.lowercased()))
        try FileManager.default.copyItem(at: src, to: dst)
        protect(dst)
        return dst
    }
    static func protect(_ u: URL) {
        #if os(iOS)
        try? FileManager.default.setAttributes([.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication], ofItemAtPath: u.path)
        #endif
    }
    /// nur Dateien in der Ablage loeschen
    static func remove(_ u: URL?) {
        guard let u, u.standardizedFileURL.path.hasPrefix(dir.standardizedFileURL.path) else { return }
        try? FileManager.default.removeItem(at: u)
    }
    static func size(_ u: URL) -> Int64 { Int64((try? u.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0) }
    /// Reste aufraeumen (z. B. Auswahl ohne Senden): aelter als einen Tag und zu keinem Auftrag gehoerend
    static func sweep(keep: Set<String>) {
        let keys: [URLResourceKey] = [.contentModificationDateKey]
        for u in (try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: keys)) ?? [] where !keep.contains(u.lastPathComponent) {
            let m = (try? u.resourceValues(forKeys: Set(keys)))?.contentModificationDate ?? .distantPast
            if m.timeIntervalSinceNow < -86_400 { try? FileManager.default.removeItem(at: u) }
        }
    }
    /// Klartext-Kopie zum Speichern/Teilen (iOS: NSFileProtectionComplete) - nach Gebrauch sofort loeschen
    static func tempSave(_ name: String) -> URL {
        let d = FileManager.default.temporaryDirectory.appendingPathComponent("chat-video-save", isDirectory: true)
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        let safe = name.replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: ":", with: "_")
        return d.appendingPathComponent(safe.isEmpty ? "video.mp4" : safe)
    }
    static func dropTemp(_ u: URL) { try? FileManager.default.removeItem(at: u.deletingLastPathComponent()) }
}

// MARK: - Metadaten und Poster

struct VideoMeta: Sendable {
    var dur: Double = 0
    var w = 0, h = 0
    var fs: Bool?
    var poster: Data?
    var pw = 0, ph = 0
    var posterImage: CGImage?
}

enum ChatVideoProbe {
    /// Dauer, Abmessungen (Anzeige-Ausrichtung), faststart, Poster bei 1 s bzw. 10 %
    static func probe(_ url: URL) async -> VideoMeta {
        var m = VideoMeta()
        let asset = AVURLAsset(url: url)
        if let d = try? await asset.load(.duration) { m.dur = VideoSendLogic.roundDur(d.seconds) }
        if let t = try? await asset.loadTracks(withMediaType: .video).first,
           let st = try? await t.load(.naturalSize, .preferredTransform) {
            let rot = VideoSendLogic.rotation(a: Double(st.1.a), b: Double(st.1.b))
            let d = VideoSendLogic.display(w: Int(abs(st.0.width).rounded()), h: Int(abs(st.0.height).rounded()), rot: rot)
            m.w = d.w; m.h = d.h
        }
        m.fs = faststart(url)
        let gen = AVAssetImageGenerator(asset: asset)
        gen.appliesPreferredTrackTransform = true
        gen.maximumSize = CGSize(width: VideoSendLogic.posterSide, height: VideoSendLogic.posterSide)
        gen.requestedTimeToleranceBefore = CMTime(seconds: 0.5, preferredTimescale: 600)
        gen.requestedTimeToleranceAfter = CMTime(seconds: 0.5, preferredTimescale: 600)
        if let img = try? await gen.image(at: CMTime(seconds: VideoSendLogic.posterTime(m.dur), preferredTimescale: 600)).image,
           let jpg = jpeg(img) {
            m.poster = jpg; m.posterImage = img; m.pw = img.width; m.ph = img.height
            if m.w == 0 || m.h == 0 { m.w = img.width; m.h = img.height }
        }
        return m
    }
    /// JPEG <= 30 KB ohne Metadaten (Qualitaet schrittweise kleiner)
    static func jpeg(_ img: CGImage) -> Data? {
        for q in VideoSendLogic.posterQualities {
            let out = NSMutableData()
            guard let dst = CGImageDestinationCreateWithData(out, UTType.jpeg.identifier as CFString, 1, nil) else { return nil }
            CGImageDestinationAddImage(dst, img, [kCGImageDestinationLossyCompressionQuality: q] as CFDictionary)
            guard CGImageDestinationFinalize(dst), let clean = ChatMediaLogic.stripJPEG(out as Data) else { return nil }
            if clean.count <= VideoSendLogic.posterBytes { return clean }
        }
        return nil                                                     // ohne Poster senden (wie ein Video ohne "th")
    }
    static func faststart(_ url: URL) -> Bool? {
        guard let h = try? FileHandle(forReadingFrom: url) else { return nil }
        defer { try? h.close() }
        return MP4Logic.faststart(size: ChatVideoFiles.size(url)) { o, l in
            (try? h.seek(toOffset: UInt64(o))).flatMap { _ in try? h.read(upToCount: l) }
        }
    }
}

// MARK: - "optimiert 720p" (hoechstens ein Export gleichzeitig, bei thermalState >= .serious pausiert)

actor ChatVideoExporter {
    static let shared = ChatVideoExporter()
    private var busy = false
    private var waiters: [CheckedContinuation<Void, Never>] = []
    private func acquire() async { if !busy { busy = true; return }; await withCheckedContinuation { waiters.append($0) } }
    private func release() { if waiters.isEmpty { busy = false } else { waiters.removeFirst().resume() } }

    static var hot: Bool { ProcessInfo.processInfo.thermalState.rawValue >= ProcessInfo.ThermalState.serious.rawValue }

    /// -> neue Datei in der Ablage (MP4, 1280x720, moov vorn); progress 0...1
    func optimize(_ src: URL, progress: @escaping @Sendable (Double) -> Void, paused: @escaping @Sendable (Bool) -> Void) async throws -> URL {
        await acquire()
        defer { release() }
        for _ in 0..<20 {
            while Self.hot { paused(true); try await Task.sleep(for: .seconds(5)) }
            paused(false)
            try Task.checkCancellation()
            let out = ChatVideoFiles.url(UUID().uuidString + "-720p.mp4")
            guard let s = AVAssetExportSession(asset: AVURLAsset(url: src), presetName: AVAssetExportPreset1280x720) else { throw ChatVideoError.unreadable }
            s.shouldOptimizeForNetworkUse = true
            let work = Task { try await s.export(to: out, as: .mp4) }
            let watch = Task {
                for await st in s.states(updateInterval: 0.5) {
                    if case .exporting(let p) = st { progress(p.fractionCompleted) }
                    if Self.hot { work.cancel(); return }                // zu warm: abbrechen, abkuehlen lassen, neu
                }
            }
            do {
                try await withTaskCancellationHandler { try await work.value } onCancel: { work.cancel() }
                watch.cancel()
                ChatVideoFiles.protect(out)
                return out
            } catch {
                watch.cancel(); ChatVideoFiles.remove(out)
                if Task.isCancelled { throw CancellationError() }
                if !Self.hot { throw error }                            // echter Fehler (kein Waerme-Abbruch)
            }
        }
        throw ChatVideoError.unreadable
    }
}

// MARK: - Netz: Medien-Token, Range-Abruf

enum ChatVideoNet {
    /// eigene Sitzung ohne Cookies (Medien-Token statt Sitzung)
    static let session: URLSession = {
        let c = URLSessionConfiguration.ephemeral
        c.httpShouldSetCookies = false; c.httpCookieAcceptPolicy = .never
        c.requestCachePolicy = .reloadIgnoringLocalCacheData; c.urlCache = nil
        c.timeoutIntervalForRequest = 30; c.httpMaximumConnectionsPerHost = 4
        return URLSession(configuration: c)
    }()

    /// Mobilfunk/teures Netz (kleinerer Vorlauf, kein Autoplay)
    final class Path: @unchecked Sendable {
        static let shared = Path()
        private let mon = NWPathMonitor()
        private(set) var expensive = false
        init() { mon.pathUpdateHandler = { [weak self] p in self?.expensive = p.isExpensive || p.isConstrained }; mon.start(queue: DispatchQueue(label: "saga.video.path")) }
    }
}

/// Medien-Token je Datei (15 min; bei 401 neu)
actor ChatMediaTokens {
    static let shared = ChatMediaTokens()
    private var tokens: [String: (token: String, until: Date)] = [:]
    func token(_ fid: String, store: Store, fresh: Bool = false) async throws -> String {
        if !fresh, let t = tokens[fid], t.until.timeIntervalSinceNow > 30 { return t.token }
        let d = try await store.api("api/chat/files/\(fid)/token", method: "POST")
        guard let j = ShareJSON.obj(d), let t = j["token"] as? String, !t.isEmpty else { throw ChatVideoError.network(500) }
        let ttl = ShareJSON.num(j["ttl"]) ?? 900
        tokens[fid] = (t, Date().addingTimeInterval(max(60, ttl - 30)))
        return t
    }
    func clear() { tokens = [:] }
}

// MARK: - Platten-Cache fuer Video-Ciphertext (512 MB LRU, getrennt vom Bildcache)

final class ChatVideoCache: @unchecked Sendable {
    static let shared = ChatVideoCache()
    private let q = DispatchQueue(label: "saga.chat.video-cache")
    private var total: Int?
    let dir: URL = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        .appendingPathComponent("hk", isDirectory: true).appendingPathComponent("chat-video", isDirectory: true)
    private func url(_ n: String) -> URL { dir.appendingPathComponent(n, isDirectory: false) }
    private func entries() -> [ChatMediaLogic.DiskEntry] {
        let keys: [URLResourceKey] = [.fileSizeKey, .contentModificationDateKey]
        return ((try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: keys)) ?? []).compactMap { u in
            guard let v = try? u.resourceValues(forKeys: Set(keys)) else { return nil }
            return .init(name: u.lastPathComponent, size: v.fileSize ?? 0, used: v.contentModificationDate ?? .distantPast)
        }
    }
    private func ensure() {
        if total != nil { return }
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        total = entries().reduce(0) { $0 + $1.size }
    }
    func read(_ name: String) -> Data? {
        q.sync {
            ensure()
            let u = url(name)
            guard let d = try? Data(contentsOf: u) else { return nil }
            try? FileManager.default.setAttributes([.modificationDate: Date()], ofItemAtPath: u.path)
            return d
        }
    }
    func write(_ name: String, _ data: Data) {
        q.sync {
            ensure()
            let u = url(name)
            let old = (try? u.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
            #if os(iOS)
            let opts: Data.WritingOptions = [.atomic, .completeFileProtectionUntilFirstUserAuthentication]
            #else
            let opts: Data.WritingOptions = [.atomic]
            #endif
            guard (try? data.write(to: u, options: opts)) != nil else { return }
            total = (total ?? 0) - old + data.count
            if (total ?? 0) > VideoCacheLogic.limit {
                for n in VideoCacheLogic.evict(entries()) where n != name { try? FileManager.default.removeItem(at: url(n)) }
                total = entries().reduce(0) { $0 + $1.size }
            }
        }
    }
    /// Abschnitte dieser Datei-Kennungen (Chat geloescht)
    func remove(_ ids: [String]) {
        guard !ids.isEmpty else { return }
        q.sync {
            let pre = Set(ids.compactMap { ChatMediaLogic.cacheName($0) })
            for e in entries() where pre.contains(String(e.name.split(separator: ".").first ?? "")) { try? FileManager.default.removeItem(at: url(e.name)) }
            total = nil
        }
    }
    func clear() { q.sync { try? FileManager.default.removeItem(at: dir); total = nil } }
    var bytes: Int { q.sync { ensure(); return total ?? 0 } }
}

// MARK: - Quelle: Klartext-Bereiche einer Datei (v2 blockweise vom Server/Cache, v1 aus dem Speicher)

final class ChatVideoSource: @unchecked Sendable {
    let file: ChatFileRef
    let size: Int64
    private let fileKey: Data
    private let plainV1: Data?
    private weak var store: Store?
    private let lock = NSLock()
    private var k: FileV2Key?

    /// v2: Kopf und Bloecke vom Server; v1: der ganze (kleine) Klartext
    init(file: ChatFileRef, store: Store?, plainV1: Data? = nil) {
        self.file = file; self.store = store; self.plainV1 = plainV1
        self.fileKey = ChatCrypto.unb64(file.key, 32) ?? Data()
        self.size = plainV1.map { Int64($0.count) } ?? Int64(file.size)
    }
    var contentType: String {
        if file.voice { return UTType(filenameExtension: "ogg")?.identifier ?? "org.xiph.ogg-audio" }   // 2.5: Sprachnachricht (Ogg/Opus)
        return UTType(mimeType: file.mime)?.identifier ?? UTType.mpeg4Movie.identifier
    }

    /// Ciphertext-Bytes from...to (Medien-Token; kuerzere 206-Antworten werden fortgesetzt)
    func fetch(_ from: Int64, _ to: Int64) async throws -> Data {
        guard let store = self.store, let url = await store.apiURL("api/chat/files/\(file.id)") else { throw ChatVideoError.network(0) }
        var out = Data(capacity: Int(to - from + 1)), pos = from, fresh = false, useCookie = false, tries = 0
        while pos <= to {
            try Task.checkCancellation()
            var req = URLRequest(url: url, timeoutInterval: 30)
            req.setValue("bytes=\(pos)-\(to)", forHTTPHeaderField: "Range")
            if useCookie {
                for (k, v) in await store.authHeaders(for: url, fresh: fresh) { req.setValue(v, forHTTPHeaderField: k) }
            } else if let t = try? await ChatMediaTokens.shared.token(file.id, store: store, fresh: fresh) {
                req.setValue(t, forHTTPHeaderField: "X-Saga-Media-Token")
            } else {
                useCookie = true; continue                              // ohne Token (Fehler beim Holen): wie bisher mit der Sitzung
            }
            let (d, r) = try await ChatVideoNet.session.data(for: req)
            let code = (r as? HTTPURLResponse)?.statusCode ?? 0
            if code == 401 {
                tries += 1; guard tries <= 2 else { throw ChatVideoError.network(401) }
                if useCookie { _ = await store.resumeSession() }
                fresh = true; continue
            }
            fresh = false
            if code == 200 {                                            // Server ohne Range (sollte nicht vorkommen): zuschneiden
                guard Int64(d.count) > to else { throw ChatVideoError.header }
                out.append(d.subdata(in: Int(pos)..<Int(to) + 1)); pos = to + 1; continue
            }
            guard code == 206, !d.isEmpty else { throw ChatVideoError.network(code) }
            let start = ((r as? HTTPURLResponse)?.value(forHTTPHeaderField: "Content-Range")).flatMap(Self.rangeStart) ?? pos
            guard start == pos else { throw ChatVideoError.header }
            let take = min(Int64(d.count), to - pos + 1)
            out.append(d.prefix(Int(take))); pos += take
        }
        return out
    }
    /// "bytes a-b/n" -> a
    static func rangeStart(_ s: String) -> Int64? {
        guard let r = s.range(of: "bytes ") else { return nil }
        return Int64(s[r.upperBound...].split(separator: "-").first ?? "")
    }

    /// Schluessel + Kopf (einmal, dann im Cache); prueft s/cs gegen die Nachricht
    func key() async throws -> FileV2Key {
        if let k = lock.withLock({ k }) { return k }
        let hname = VideoCacheLogic.name(file.id, chunk: nil)
        var head = hname.flatMap { ChatVideoCache.shared.read($0) }
        if head?.count != FileV2.headerLen {
            head = try await fetch(0, Int64(FileV2.headerLen) - 1)
            if let hname, let head { ChatVideoCache.shared.write(hname, head) }
        }
        guard let head, let nk = FileV2Crypto.key(header: head, fileKey: fileKey, size: Int64(file.size), cs: file.cs ?? FileV2.cs) else { throw ChatVideoError.header }
        lock.withLock { k = nk }
        return nk
    }
    /// Ciphertext eines Cache-Abschnitts (16 Bloecke); `keep`: im Cache ablegen
    private func chunk(_ c: Int64, k: FileV2Key, keep: Bool) async throws -> Data {
        let name = VideoCacheLogic.name(file.id, chunk: c)
        let (b0, b1) = VideoCacheLogic.chunkBlocks(c, n: k.n)
        let from = FileV2.blockStart(b0, cs: k.cs), to = min(FileV2.blockStart(b1 + 1, cs: k.cs), k.total) - 1
        if let name, let d = ChatVideoCache.shared.read(name), Int64(d.count) == to - from + 1 { return d }
        let d = try await fetch(from, to)
        if keep, let name { ChatVideoCache.shared.write(name, d) }
        return d
    }
    /// Klartext a...b (einschliesslich) blockweise an `emit` - nie Klartext eines Blocks mit falschem Tag
    func plain(_ a: Int64, _ b: Int64, keep: Bool = true, emit: (Data) throws -> Void) async throws {
        guard a <= b, b < size else { return }
        if let p = plainV1 { try emit(p.subdata(in: Int(a)..<Int(b) + 1)); return }
        let k = try await key()
        var c = VideoCacheLogic.chunk(of: a / Int64(k.cs))
        while c <= VideoCacheLogic.chunk(of: b / Int64(k.cs)) {
            try Task.checkCancellation()
            let ct = try await chunk(c, k: k, keep: keep)
            let first = VideoCacheLogic.chunkBlocks(c, n: k.n).0
            try FileV2.openBlocks(ct, first: first, size: k.size, cs: k.cs, open: { FileV2Crypto.open($0, i: $1, k: k) }) { i, p in
                let base = i * Int64(k.cs), lo = max(a, base), hi = min(b, base + Int64(p.count) - 1)
                if lo <= hi { try emit(p.subdata(in: Int(lo - base)..<Int(hi - base) + 1)) }
            }
            c += 1
        }
    }
}

// MARK: - AVAssetResourceLoaderDelegate (Schema saga-media://)

final class ChatVideoLoader: NSObject, AVAssetResourceLoaderDelegate, @unchecked Sendable {
    static let scheme = "saga-media"
    let source: ChatVideoSource
    let queue = DispatchQueue(label: "saga.video.loader")
    private var tasks: [ObjectIdentifier: Task<Void, Never>] = [:]       // nur auf `queue`
    init(_ source: ChatVideoSource) { self.source = source }

    func resourceLoader(_ resourceLoader: AVAssetResourceLoader, shouldWaitForLoadingOfRequestedResource r: AVAssetResourceLoadingRequest) -> Bool {
        if let info = r.contentInformationRequest {
            info.contentType = source.contentType
            info.contentLength = source.size
            info.isByteRangeAccessSupported = true
        }
        guard let d = r.dataRequest else { r.finishLoading(); return true }
        let id = ObjectIdentifier(r), src = source, q = queue
        tasks[id] = Task.detached(priority: .userInitiated) { [weak self] in
            do {
                guard let (a, b) = FileV2.requested(offset: d.requestedOffset, length: d.requestedLength,
                                                     toEnd: d.requestsAllDataToEndOfResource, size: src.size) else {
                    q.sync { r.finishLoading() }; return
                }
                // Blöcke a/cs ... in Abschnitten von hoechstens 4 MiB: holen, entschluesseln, zuschneiden, weitergeben
                var pos = max(a, d.currentOffset)
                while let c = FileV2.nextChunk(from: pos, end: b, size: src.size) {
                    try Task.checkCancellation()
                    try await src.plain(c.a, c.b) { p in q.sync { d.respond(with: p) } }
                    pos = c.b + 1
                }
                q.sync { if !r.isCancelled { r.finishLoading() } }
            } catch {
                q.sync { if !r.isCancelled && !r.isFinished { r.finishLoading(with: error) } }
            }
            q.async { self?.tasks[id] = nil }
        }
        return true
    }
    func resourceLoader(_ resourceLoader: AVAssetResourceLoader, didCancel r: AVAssetResourceLoadingRequest) {
        let id = ObjectIdentifier(r)
        tasks[id]?.cancel(); tasks[id] = nil
    }
    func cancelAll() { queue.async { [self] in tasks.values.forEach { $0.cancel() }; tasks = [:] } }
}

// MARK: - Ein gemeinsamer Player

@MainActor
final class ChatVideoPlayer: ObservableObject {
    static let shared = ChatVideoPlayer()
    let player = AVPlayer()
    private var loader: ChatVideoLoader?
    @Published private(set) var currentID: String?
    /// Bild im Bild laeuft: beim Schliessen des Fensters nicht anhalten
    var pip = false

    func play(_ source: ChatVideoSource, autoplay: Bool = true) {
        if currentID == source.file.id, player.currentItem != nil { if autoplay { player.play() }; return }
        stop(force: true)
        let l = ChatVideoLoader(source)
        guard let url = URL(string: "\(ChatVideoLoader.scheme)://\(source.file.id)") else { return }
        let asset = AVURLAsset(url: url)
        asset.resourceLoader.setDelegate(l, queue: l.queue)
        let item = AVPlayerItem(asset: asset)
        item.preferredForwardBufferDuration = ChatVideoNet.Path.shared.expensive ? 10 : 20
        loader = l; currentID = source.file.id
        player.replaceCurrentItem(with: item)
        #if os(iOS)
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
        #endif
        if autoplay && !ChatVideoNet.Path.shared.expensive { player.play() }   // Mobilfunk: erst auf Tippen
    }
    func stop(force: Bool = false) {
        guard force || !pip else { return }
        player.pause(); player.replaceCurrentItem(with: nil)
        loader?.cancelAll(); loader = nil; currentID = nil; pip = false
    }
}

// MARK: - Speichern (Entschluesseln als Datenstrom in eine Datei)

enum ChatVideoSave {
    /// Ganze Datei blockweise nach `dst` (nie mehr als ein Abschnitt im Speicher); progress 0...1
    static func decrypt(_ src: ChatVideoSource, to dst: URL, progress: @escaping @Sendable (Double) -> Void) async throws {
        FileManager.default.createFile(atPath: dst.path, contents: nil)
        #if os(iOS)
        try? FileManager.default.setAttributes([.protectionKey: FileProtectionType.complete], ofItemAtPath: dst.path)
        #endif
        let h = try FileHandle(forWritingTo: dst)
        defer { try? h.close() }
        try h.truncate(atOffset: 0)
        var pos: Int64 = 0
        do {
            while let c = FileV2.nextChunk(from: pos, end: src.size - 1, size: src.size) {
                try Task.checkCancellation()
                try await src.plain(c.a, c.b, keep: false) { try h.write(contentsOf: $0) }
                pos = c.b + 1
                progress(Double(pos) / Double(max(1, src.size)))
            }
        } catch { try? FileManager.default.removeItem(at: dst); throw error }
    }
    #if os(iOS)
    /// In Fotos sichern (nur Hinzufuegen, keine Leseberechtigung)
    static func toPhotos(_ url: URL) async throws {
        let st = await PHPhotoLibrary.requestAuthorization(for: .addOnly)
        guard st == .authorized || st == .limited else { throw ChatVideoError.photos }
        try await PHPhotoLibrary.shared().performChanges {
            let o = PHAssetResourceCreationOptions(); o.originalFilename = url.lastPathComponent
            PHAssetCreationRequest.forAsset().addResource(with: .video, fileURL: url, options: o)
        }
    }
    #endif
}
