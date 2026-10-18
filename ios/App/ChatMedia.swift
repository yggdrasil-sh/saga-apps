import SwiftUI
import ImageIO
import UniformTypeIdentifiers

// Chat-Bilder schnell (2.1-c, Format: chat-thumbs-spec.md): im Verlauf nur Vorschaubilder (320 px, beim Senden erzeugt),
// Original erst beim Oeffnen. Speicher-LRU (NSCache) fuer entschluesselte Vorschaubilder, Platten-Cache nur fuer
// Ciphertext (Blobs wie vom Server bzw. eigene, mit dem Dateischluessel verschluesselte Vorschaubilder) mit 200 MB LRU.
// Laden/Entschluesseln/Dekodieren nie im UI-Thread, hoechstens 4 gleichzeitig, Abbruch beim Wegscrollen.

/// Dekodiertes Bild fuer die Anzeige (w/h: Seitenverhaeltnis)
final class ChatImage: @unchecked Sendable {
    let cg: CGImage; let w: Int; let h: Int
    init(_ cg: CGImage, w: Int? = nil, h: Int? = nil) { self.cg = cg; self.w = w ?? cg.width; self.h = h ?? cg.height }
    var cost: Int { cg.bytesPerRow * cg.height }
}

// MARK: - Bilddaten (ImageIO, threadsicher)

enum ChatImageIO {
    /// Pixelgroesse in angezeigter Ausrichtung (EXIF-Drehung 5-8 tauscht Breite und Hoehe)
    static func size(_ d: Data) -> (w: Int, h: Int)? {
        guard let src = CGImageSourceCreateWithData(d as CFData, [kCGImageSourceShouldCache: false] as CFDictionary),
              let p = CGImageSourceCopyPropertiesAtIndex(src, 0, nil) as? [CFString: Any],
              let w = p[kCGImagePropertyPixelWidth] as? Int, let h = p[kCGImagePropertyPixelHeight] as? Int else { return nil }
        let o = (p[kCGImagePropertyOrientation] as? Int) ?? 1
        return o >= 5 && o <= 8 ? (h, w) : (w, h)
    }

    /// Vorschaubild nach Format: Drehung angewendet, laengste Seite <= 320 (nie hochskalieren), Transparenz auf Weiss,
    /// JPEG ~70 ohne Metadaten. GIF/animiert: erstes Bild. nil = Format unbekannt.
    static func makeThumb(_ d: Data) -> (jpeg: Data, image: CGImage, ow: Int, oh: Int)? {
        guard let (ow, oh) = size(d), let src = CGImageSourceCreateWithData(d as CFData, [kCGImageSourceShouldCache: false] as CFDictionary) else { return nil }
        let (tw, th) = ChatMediaLogic.thumbSize(w: ow, h: oh)
        let opts: [CFString: Any] = [kCGImageSourceCreateThumbnailFromImageAlways: true, kCGImageSourceCreateThumbnailWithTransform: true,
                                     kCGImageSourceThumbnailMaxPixelSize: max(tw, th), kCGImageSourceShouldCacheImmediately: true]
        guard let t = CGImageSourceCreateThumbnailAtIndex(src, 0, opts as CFDictionary) else { return nil }
        // auf Weiss zeichnen (JPEG kennt keine Transparenz) - genau tw x th, ohne Farbprofil/Metadaten
        guard let ctx = CGContext(data: nil, width: tw, height: th, bitsPerComponent: 8, bytesPerRow: 0, space: CGColorSpaceCreateDeviceRGB(),
                                  bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue) else { return nil }
        ctx.setFillColor(CGColor(red: 1, green: 1, blue: 1, alpha: 1)); ctx.fill(CGRect(x: 0, y: 0, width: tw, height: th))
        ctx.interpolationQuality = .high
        ctx.draw(t, in: CGRect(x: 0, y: 0, width: tw, height: th))
        guard let img = ctx.makeImage() else { return nil }
        let out = NSMutableData()
        guard let dst = CGImageDestinationCreateWithData(out, UTType.jpeg.identifier as CFString, 1, nil) else { return nil }
        CGImageDestinationAddImage(dst, img, [kCGImageDestinationLossyCompressionQuality: ChatMediaLogic.thumbQuality] as CFDictionary)
        guard CGImageDestinationFinalize(dst), let clean = ChatMediaLogic.stripJPEG(out as Data) else { return nil }
        return (clean, img, ow, oh)
    }

    /// Dekodieren (sofort, nicht erst beim Zeichnen im UI-Thread); maxPixel = laengste Seite (Original verkleinert)
    static func decode(_ d: Data, maxPixel: Int? = nil) -> CGImage? {
        guard let src = CGImageSourceCreateWithData(d as CFData, [kCGImageSourceShouldCache: false] as CFDictionary) else { return nil }
        if let maxPixel {
            let o: [CFString: Any] = [kCGImageSourceCreateThumbnailFromImageAlways: true, kCGImageSourceCreateThumbnailWithTransform: true,
                                      kCGImageSourceThumbnailMaxPixelSize: maxPixel, kCGImageSourceShouldCacheImmediately: true]
            return CGImageSourceCreateThumbnailAtIndex(src, 0, o as CFDictionary)
        }
        return CGImageSourceCreateImageAtIndex(src, 0, [kCGImageSourceShouldCacheImmediately: true] as CFDictionary)
    }
}

// MARK: - Platten-Cache: nur Ciphertext, 200 MB, LRU (letzte Benutzung = Aenderungsdatum)

final class ChatBlobCache: @unchecked Sendable {
    static let shared = ChatBlobCache()
    private let q = DispatchQueue(label: "saga.chat.blobs")
    private var total: Int?                                    // Summe der Dateigroessen (beim ersten Zugriff gezaehlt)
    let dir: URL = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        .appendingPathComponent("hk", isDirectory: true).appendingPathComponent("chat-blobs", isDirectory: true)

    private func url(_ name: String) -> URL { dir.appendingPathComponent(name, isDirectory: false) }
    private func ensure() {
        if total != nil { return }
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        total = entries().reduce(0) { $0 + $1.size }
    }
    private func entries() -> [ChatMediaLogic.DiskEntry] {
        let keys: [URLResourceKey] = [.fileSizeKey, .contentModificationDateKey]
        let list = (try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: keys)) ?? []
        return list.compactMap { u in
            guard let v = try? u.resourceValues(forKeys: Set(keys)) else { return nil }
            return .init(name: u.lastPathComponent, size: v.fileSize ?? 0, used: v.contentModificationDate ?? .distantPast)
        }
    }

    /// Blob lesen (nie im UI-Thread aufrufen); markiert ihn als benutzt
    func read(_ name: String) -> Data? {
        q.sync {
            ensure()
            let u = url(name)
            guard let d = try? Data(contentsOf: u) else { return nil }
            try? FileManager.default.setAttributes([.modificationDate: Date()], ofItemAtPath: u.path)
            return d
        }
    }
    /// Blob ablegen; ueber 200 MB fallen die am laengsten nicht benutzten weg
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
            if (total ?? 0) > ChatMediaLogic.diskLimit {
                let all = entries()
                for n in ChatMediaLogic.evict(all) where n != name { try? FileManager.default.removeItem(at: url(n)) }
                total = entries().reduce(0) { $0 + $1.size }
            }
        }
    }
    /// Abmelden, "Neu beginnen", Geraet entfernt
    func clear() {
        q.sync { try? FileManager.default.removeItem(at: dir); total = nil; index = ChatCacheIndex(); try? FileManager.default.removeItem(at: indexURL) }
    }

    // Zuordnung Datei-Kennung -> Chat (2.1: Chat loeschen leert genau diesen Chat)
    private let indexURL: URL = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        .appendingPathComponent("hk", isDirectory: true).appendingPathComponent("chat-blob-index.json", isDirectory: false)
    private var index: ChatCacheIndex?
    private func loadIndex() -> ChatCacheIndex {
        if let index { return index }
        let i = (try? Data(contentsOf: indexURL)).flatMap { try? JSONDecoder().decode(ChatCacheIndex.self, from: $0) } ?? ChatCacheIndex()
        index = i; return i
    }
    private func saveIndex(_ i: ChatCacheIndex) {
        index = i
        try? FileManager.default.createDirectory(at: indexURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        if let d = try? JSONEncoder().encode(i) { try? d.write(to: indexURL, options: .atomic) }
    }
    /// Kennungen der Anhaenge eines Chats merken (beim Laden/Senden)
    func note(chatID: Int, ids: [String]) {
        guard !ids.isEmpty else { return }
        q.async { [self] in var i = loadIndex(); if i.add(chatID, ids) { saveIndex(i) } }
    }
    /// Ciphertext und eigene Vorschaubilder eines Chats von der Platte -> entfernte Kennungen (fuer den Speicher-Cache)
    @discardableResult func purge(chatID: Int, extra: [String] = []) -> [String] {
        q.sync {
            var i = loadIndex()
            let ids = Array(Set(i.take(chatID) + extra))
            saveIndex(i)
            for n in ChatCacheIndex.diskNames(ids) { try? FileManager.default.removeItem(at: url(n)) }
            total = nil                                                 // beim naechsten Zugriff neu zaehlen
            return ids
        }
    }
    #if DEBUG
    var bytes: Int { q.sync { ensure(); return total ?? 0 } }
    #endif
}

// MARK: - Laden: Speicher-LRU, hoechstens 4 gleichzeitig, gleiche Anfragen zusammengefasst, Abbruch ohne Interessenten

/// Holt den Blob einer Datei-ID vom Server (ConversationModel -> Store.api)
typealias ChatBlobFetch = @Sendable (String) async throws -> Data

enum ChatMediaError: LocalizedError {
    case undecryptable, undecodable
    var errorDescription: String? {
        switch self {
        case .undecryptable: return String(localized: "Nicht entschlüsselbar")
        case .undecodable: return String(localized: "Bild nicht lesbar")
        }
    }
}

actor ChatMedia {
    static let shared = ChatMedia()
    /// entschluesselte Vorschaubilder (Schluessel: Datei-ID des Originals) - NSCache ist threadsicher
    nonisolated(unsafe) static let memory: NSCache<NSString, ChatImage> = {   // NSCache ist threadsicher
        let c = NSCache<NSString, ChatImage>()
        c.countLimit = ChatMediaLogic.memCount; c.totalCostLimit = ChatMediaLogic.memBytes
        return c
    }()
    /// Seitenverhaeltnis alter Bilder (ohne w/h), sobald bekannt - der Platzhalter springt nur beim ersten Laden
    final class Aspect { let w: Int, h: Int; init(_ w: Int, _ h: Int) { self.w = w; self.h = h } }
    nonisolated(unsafe) static let aspects = NSCache<NSString, Aspect>()

    nonisolated static func cached(_ fileID: String) -> ChatImage? { memory.object(forKey: fileID as NSString) }
    nonisolated static func remember(_ img: ChatImage, for fileID: String) {
        memory.setObject(img, forKey: fileID as NSString, cost: img.cost)
        aspects.setObject(Aspect(img.w, img.h), forKey: fileID as NSString)
    }
    nonisolated static func knownAspect(_ fileID: String) -> (w: Int, h: Int)? {
        aspects.object(forKey: fileID as NSString).map { ($0.w, $0.h) }
    }
    /// alles vergessen (Abmelden, "Neu beginnen", Geraet entfernt)
    nonisolated static func clearAll() {
        memory.removeAllObjects(); aspects.removeAllObjects(); ChatBlobCache.shared.clear()
    }
    /// Chat geloescht (2.1): entschluesselte Vorschaubilder im Speicher und Ciphertext auf der Platte dieses Chats weg
    nonisolated static func purge(chatID: Int, extra: [String] = []) {
        for id in ChatBlobCache.shared.purge(chatID: chatID, extra: extra) {
            memory.removeObject(forKey: id as NSString); aspects.removeObject(forKey: id as NSString)
        }
    }

    // Plaetze (hoechstens 4 Downloads/Entschluesselungen gleichzeitig)
    private var running = 0
    private var waiting: [(id: UUID, c: CheckedContinuation<Bool, Never>)] = []
    private func acquire() async -> Bool {
        if running < ChatMediaLogic.parallel { running += 1; return true }
        let id = UUID()
        return await withTaskCancellationHandler {
            await withCheckedContinuation { c in
                if Task.isCancelled { c.resume(returning: false) } else { waiting.append((id, c)) }
            }
        } onCancel: { Task { await self.dropWaiter(id) } }
    }
    private func dropWaiter(_ id: UUID) {
        if let i = waiting.firstIndex(where: { $0.id == id }) { waiting.remove(at: i).c.resume(returning: false) }
    }
    private func release() {
        if waiting.isEmpty { running -= 1 } else { waiting.removeFirst().c.resume(returning: true) }   // Platz geht direkt weiter
    }
    /// Arbeit in einem Platz (im Hintergrund, nicht auf diesem Actor)
    func slot<T: Sendable>(_ work: @escaping @Sendable () async throws -> T) async throws -> T {
        guard await acquire() else { throw CancellationError() }
        defer { release() }
        try Task.checkCancellation()
        let d = Task.detached(priority: .userInitiated) { try await work() }
        return try await withTaskCancellationHandler { try await d.value } onCancel: { d.cancel() }
    }

    // gleiche Vorschaubilder nur einmal laden; ohne Interessenten (weggescrollt) abbrechen
    private var inflight: [String: (task: Task<ChatImage, Error>, n: Int)] = [:]
    func thumbnail(_ f: ChatFileRef, chatID: String, fetch: @escaping ChatBlobFetch) async throws -> ChatImage {
        if let m = Self.cached(f.id) { return m }
        let t: Task<ChatImage, Error>
        if let e = inflight[f.id] { inflight[f.id] = (e.task, e.n + 1); t = e.task }
        else {
            t = Task { try await self.slot { try await Self.loadThumb(f, chatID: chatID, fetch: fetch) } }
            inflight[f.id] = (t, 1)
        }
        defer { leave(f.id, t) }
        do {
            return try await withTaskCancellationHandler { try await t.value } onCancel: { Task { await self.abandon(f.id, t) } }
        } catch where t.isCancelled && !Task.isCancelled {
            // gemeinsamer Ladevorgang wurde abgebrochen (alle anderen weggescrollt), ich warte aber noch: neu laden
            if inflight[f.id]?.task == t { inflight[f.id] = nil }
            return try await thumbnail(f, chatID: chatID, fetch: fetch)
        }
    }
    private func leave(_ id: String, _ t: Task<ChatImage, Error>) {
        guard let e = inflight[id], e.task == t else { return }
        if e.n <= 1 { inflight[id] = nil } else { inflight[id] = (t, e.n - 1) }
    }
    private func abandon(_ id: String, _ t: Task<ChatImage, Error>) {
        guard let e = inflight[id], e.task == t, e.n <= 1 else { return }
        t.cancel()                                                  // niemand wartet mehr: Download abbrechen
    }

    // MARK: Ablauf (im Hintergrund)

    /// Ciphertext einer Datei-ID: Platte, sonst Server (und auf der Platte ablegen, wenn `keep`)
    static func blob(_ id: String, keep: Bool = true, fetch: ChatBlobFetch) async throws -> Data {
        let name = ChatMediaLogic.cacheName(id)
        if let name, let d = ChatBlobCache.shared.read(name) { return d }
        let d = try await fetch(id)
        try Task.checkCancellation()
        if keep, let name { ChatBlobCache.shared.write(name, d) }
        return d
    }

    /// Vorschaubild: "th" laden; alte Nachricht ohne "th": eigenes (verschluesselt auf der Platte) oder aus dem Original
    /// erzeugen - das Original bleibt dabei nicht im Speicher und nicht auf der Platte (verdraengte sonst die Vorschaubilder).
    static func loadThumb(_ f: ChatFileRef, chatID: String, fetch: ChatBlobFetch) async throws -> ChatImage {
        if let th = f.thumb, let k = ChatCrypto.unb64(th.key, 32) {
            let b = try await blob(th.id, fetch: fetch)
            guard let jpg = ChatCrypto.decryptFile(chatID: chatID, blob: b, key: k) else { throw ChatMediaError.undecryptable }
            guard let cg = ChatImageIO.decode(jpg) else { throw ChatMediaError.undecodable }
            let img = ChatImage(cg, w: f.w ?? th.w, h: f.h ?? th.h)
            remember(img, for: f.id); return img
        }
        guard let k = ChatCrypto.unb64(f.key, 32) else { throw ChatMediaError.undecryptable }
        let local = ChatMediaLogic.cacheName(f.id, local: true)
        if let local, let b = ChatBlobCache.shared.read(local), let jpg = ChatCrypto.decryptFile(chatID: chatID, blob: b, key: k),
           let cg = ChatImageIO.decode(jpg) {
            let img = ChatImage(cg, w: f.w, h: f.h); remember(img, for: f.id); return img
        }
        let b = try await blob(f.id, keep: false, fetch: fetch)
        guard let data = ChatCrypto.decryptFile(chatID: chatID, blob: b, key: k) else { throw ChatMediaError.undecryptable }
        try Task.checkCancellation()
        guard let t = ChatImageIO.makeThumb(data) else { throw ChatMediaError.undecodable }
        // eigenes Vorschaubild mit dem Dateischluessel des Originals verschluesselt ablegen (eigene Nonce, gleiche AD)
        if let local, let e = ChatCrypto.encryptFile(chatID: chatID, data: t.jpeg, key: k) { ChatBlobCache.shared.write(local, e.blob) }
        let img = ChatImage(t.image, w: f.w ?? t.ow, h: f.h ?? t.oh)
        remember(img, for: f.id); return img
    }

    /// Original (entschluesselt) zum Oeffnen/Teilen - Ciphertext bleibt im Platten-Cache
    func original(_ f: ChatFileRef, chatID: String, fetch: @escaping ChatBlobFetch) async throws -> Data {
        try await slot {
            guard let k = ChatCrypto.unb64(f.key, 32) else { throw ChatMediaError.undecryptable }
            let b = try await Self.blob(f.id, keep: f.size <= ChatMediaLogic.diskLimit / 8, fetch: fetch)
            guard let d = ChatCrypto.decryptFile(chatID: chatID, blob: b, key: k) else { throw ChatMediaError.undecryptable }
            return d
        }
    }

    /// Beim Senden: eigenes Bild sofort anzeigen und die hochgeladenen Blobs nicht noch einmal laden
    nonisolated static func prime(_ f: ChatFileRef, image: CGImage?, origBlob: Data?, thumbBlob: Data?) {
        if let image { remember(ChatImage(image, w: f.w, h: f.h), for: f.id) }
        if let thumbBlob, let th = f.thumb, let n = ChatMediaLogic.cacheName(th.id) { ChatBlobCache.shared.write(n, thumbBlob) }
        if let origBlob, origBlob.count <= ChatMediaLogic.diskLimit / 8, let n = ChatMediaLogic.cacheName(f.id) { ChatBlobCache.shared.write(n, origBlob) }
    }
}

/// Vorschaubild beim Senden: erzeugen und verschluesseln (im Hintergrund) -> nil, wenn das Format unbekannt ist
struct ChatThumbUpload: Sendable {
    let jpegCount: Int; let w: Int; let h: Int; let ow: Int; let oh: Int; let blob: Data; let key: Data
    let image: CGImage
    static func make(_ data: Data, chatID: String, enabled: Bool = true) async -> ChatThumbUpload? {
        guard enabled else { return nil }
        return await Task.detached(priority: .userInitiated) { () -> ChatThumbUpload? in
            guard let t = ChatImageIO.makeThumb(data), let e = ChatCrypto.encryptFile(chatID: chatID, data: t.jpeg) else { return nil }
            return ChatThumbUpload(jpegCount: t.jpeg.count, w: t.image.width, h: t.image.height, ow: t.ow, oh: t.oh, blob: e.blob, key: e.key, image: t.image)
        }.value
    }
}
