import Foundation
import Combine
import CoreGraphics

// Videos im Chat (2.4): Senden als Auftrag, der App-Neustarts uebersteht.
//   Vorbereiten: Poster/Dauer/Abmessungen, optional "optimiert 720p" (ChatVideo.swift), Format nach den Grenzen des
//   Servers (bis file_max v1 wie bisher, sonst saga-file/v2; 2.3-Server bzw. 2.3-Partnerserver: Hinweis statt Senden).
//   Hochladen (v2): POST /api/chats/{cid}/uploads, dann Teile (<= 8 MiB, blockweise verschluesselt in eine Datei) per
//   URLSession im Hintergrund (PUT, Content-Range), Fortschritt sichtbar, Abbrechen (DELETE). Nach Netzabbruch oder
//   Neustart: HEAD -> ab Upload-Offset weiter (Verschluesselung deterministisch).
//   Senden: Nachricht mit {id,k,n,m,s,x:2,cs,dur,w,h,fs,th} wie jede Nachricht (saga-chat v1 bzw. v2).
// K, Praefix, Datei und Upload-Kennung liegen bis zum Senden im Schluesselbund (danach geloescht).

struct VideoJob: Codable, Identifiable, Equatable {
    enum Stage: String, Codable { case queued, preparing, uploading, sending, failed }
    struct Thumb: Codable, Equatable { var id: String; var k: String; var w: Int; var h: Int; var s: Int }
    var id = UUID().uuidString
    var server: String
    var chatID: Int
    var text: String
    var replyTo: String?
    var name: String
    var mime: String
    /// Dateiname in ChatVideoFiles.dir (Auswahl) bzw. verkleinerte Fassung
    var source: String
    var prepared: String?
    var optimize: Bool
    var stage: Stage = .queued
    var error: String?
    var size: Int64?
    var dur: Double?
    var w: Int?
    var h: Int?
    var fs: Bool?
    var thumb: Thumb?
    var v2: FileV2Key?
    var upload: String?
    var offset: Int64 = 0
    var total: Int64 = 0
    var partMax: Int?
    var fileID: String?
    var fileKey: String?
    var x: Int?
}

@MainActor
final class ChatVideoUploads: NSObject, ObservableObject {
    static let shared = ChatVideoUploads()
    @Published private(set) var jobs: [VideoJob] = []
    /// Fortschritt des laufenden Schritts (0...1) je Auftrag
    @Published private(set) var progress: [String: Double] = [:]
    /// Verkleinern wartet (Geraet zu warm)
    @Published private(set) var hot: Set<String> = []
    private weak var store: Store?
    private var active: Set<String> = []
    private var work: [String: Task<Void, Never>] = [:]
    private var fails: [String: Int] = [:]
    private var bodies: [Int: Data] = [:]
    private var posters: [String: CGImage] = [:]
    /// iOS: vom System beim Aufwecken fuer Hintergrund-Uploads uebergeben
    var bgCompletion: (() -> Void)?
    private static let service = "saga.chat.video"
    private static let indexKey = "hkVideoJobs"

    private(set) lazy var session: URLSession = {
        let c = URLSessionConfiguration.background(withIdentifier: (Bundle.main.bundleIdentifier ?? "saga") + ".chatvideo")
        c.sessionSendsLaunchEvents = true; c.isDiscretionary = false
        c.httpShouldSetCookies = false; c.httpCookieAcceptPolicy = .never
        c.timeoutIntervalForRequest = 120
        return URLSession(configuration: c, delegate: self, delegateQueue: .main)
    }()

    override init() {
        super.init()
        let ids = UserDefaults.standard.stringArray(forKey: Self.indexKey) ?? []
        jobs = ids.compactMap { id in
            Keychain.read(service: Self.service, account: id).flatMap { try? JSONDecoder().decode(VideoJob.self, from: Data($0.utf8)) }
        }
    }

    /// Store verbinden (beim Start): Hintergrund-Sitzung wieder aufnehmen, offene Auftraege fortsetzen
    func attach(_ s: Store) {
        store = s
        _ = session
        let keep = Set(jobs.flatMap { [$0.source, $0.prepared ?? "", $0.id + ".part"] })
        Task.detached(priority: .background) { ChatVideoFiles.sweep(keep: keep) }
        Task { [weak self] in
            try? await Task.sleep(for: .seconds(2))                  // Anmeldung/Abgleich zuerst
            await self?.resumeAll()
        }
    }
    /// iOS: Hintergrund-Ereignisse (App vom System geweckt)
    func wake(_ completion: @escaping () -> Void) { bgCompletion = completion; _ = session }

    private var server: String { AppConfig.server.absoluteString }
    func jobs(chat: Int) -> [VideoJob] { jobs.filter { $0.chatID == chat && $0.server == server } }
    private func job(_ id: String) -> VideoJob? { jobs.first { $0.id == id } }
    private func update(_ id: String, _ f: (inout VideoJob) -> Void) {
        guard let i = jobs.firstIndex(where: { $0.id == id }) else { return }
        f(&jobs[i]); save(jobs[i])
    }
    private func save(_ j: VideoJob) {
        if let d = try? JSONEncoder().encode(j), let s = String(data: d, encoding: .utf8) { Keychain.write(s, service: Self.service, account: j.id) }
        var ids = UserDefaults.standard.stringArray(forKey: Self.indexKey) ?? []
        if !ids.contains(j.id) { ids.append(j.id); UserDefaults.standard.set(ids, forKey: Self.indexKey) }
    }
    private func drop(_ id: String) {
        if let j = job(id) {
            ChatVideoFiles.remove(ChatVideoFiles.url(j.source))
            if let p = j.prepared { ChatVideoFiles.remove(ChatVideoFiles.url(p)) }
        }
        try? FileManager.default.removeItem(at: partURL(id))
        jobs.removeAll { $0.id == id }
        Keychain.delete(service: Self.service, account: id)
        UserDefaults.standard.set((UserDefaults.standard.stringArray(forKey: Self.indexKey) ?? []).filter { $0 != id }, forKey: Self.indexKey)
        progress[id] = nil; hot.remove(id); fails[id] = nil; posters[id] = nil
        active.remove(id); work[id] = nil
    }
    private func partURL(_ id: String) -> URL { ChatVideoFiles.url(id + ".part") }

    // MARK: Auftraege

    /// Video aus der Auswahl senden (Text/Antwort reisen mit)
    func enqueue(chatID: Int, file: ChatPendingFile, text: String, replyTo: String?) {
        guard let u = file.fileURL else { return }
        let j = VideoJob(server: server, chatID: chatID, text: text, replyTo: replyTo, name: file.name, mime: file.mime,
                         source: u.lastPathComponent, optimize: file.optimize)
        jobs.append(j); save(j)
        kick()
    }
    /// Abbrechen: laufenden Schritt stoppen, Upload beim Server verwerfen, Dateien loeschen
    func cancel(_ id: String) {
        guard let j = job(id) else { return }
        work[id]?.cancel()
        session.getAllTasks { ts in
            for t in ts where t.taskDescription?.hasPrefix(id + "|") == true { t.cancel() }
        }
        if let up = j.upload, j.fileID == nil, let store {
            Task { _ = try? await store.apiResponse("api/chat/uploads/\(up)", method: "DELETE") }
        }
        drop(id)
        kick()
    }
    /// Nach einem Fehler erneut (Upload wird beim Server fortgesetzt, falls noch da)
    func retry(_ id: String) {
        update(id) { j in
            j.error = nil
            j.stage = j.fileID != nil ? .sending : j.upload != nil ? .uploading : .queued
        }
        fails[id] = 0
        kick()
    }
    /// Abmelden: alles verwerfen
    func cancelAll() { for j in jobs { cancel(j.id) } }

    /// Hoechstens uploads_max Auftraege gleichzeitig (der Export ist ohnehin einzeln)
    private func kick() {
        let limit = max(1, store?.chat.me.uploadsMax ?? 2)
        for j in jobs where j.server == server && j.stage != .failed && !active.contains(j.id) {
            guard active.count < limit, store != nil, AppConfig.hasServer else { return }
            active.insert(j.id)
            let id = j.id
            work[id] = Task { [weak self] in await self?.step(id) }
        }
    }
    private func resumeAll() async {
        let running = Set(await session.allTasks.compactMap { $0.taskDescription?.split(separator: "|").first.map(String.init) })
        for j in jobs where running.contains(j.id) { active.insert(j.id) }   // Teil laeuft noch im Hintergrund
        kick()
    }

    private func step(_ id: String) async {
        guard let j = job(id) else { active.remove(id); return }
        do {
            switch j.stage {
            case .queued, .preparing: if try await prepare(id) { await step(id) }
            case .uploading: try await head(id)
            case .sending: try await deliver(id)
            case .failed: active.remove(id)
            }
        } catch is CancellationError {
            active.remove(id)
        } catch {
            fail(id, error)
        }
    }
    private func fail(_ id: String, _ e: Error) {
        let msg = (e as? APIError).map { $0.localizedDescription } ?? e.localizedDescription
        update(id) { $0.stage = .failed; $0.error = msg }
        active.remove(id); work[id] = nil; progress[id] = nil; hot.remove(id)
        kick()
    }

    /// Chat-Angaben (fed, big_files, chat_ref) aus der Liste, sonst vom Server
    private func chatInfo(_ cid: Int) async -> ChatSummary? {
        if let c = store?.chat.open, c.chatID == cid, let i = c.info { return i }
        if let c = store?.chat.chats.first(where: { $0.id == cid }) { return c }
        guard let store, let d = try? await store.api("api/chats/\(cid)?\(store.chat.deviceParam)") else { return nil }
        return ShareJSON.obj(d)?["chat"].flatMap(ChatSummary.init)
    }

    // MARK: Vorbereiten (Poster, 720p, Format, Poster-Upload, v1 bzw. Upload anlegen)

    /// true = gleich weiter (v1: senden), false = der Upload laeuft im Hintergrund weiter (Delegate)
    private func prepare(_ id: String) async throws -> Bool {
        guard let store, var j = job(id) else { throw CancellationError() }
        update(id) { $0.stage = .preparing; $0.error = nil }
        let src = ChatVideoFiles.url(j.source)
        guard FileManager.default.fileExists(atPath: src.path) else { throw ChatVideoError.unreadable }
        var file = src
        var meta = await ChatVideoProbe.probe(src)
        try Task.checkCancellation()
        if let p = j.prepared, FileManager.default.fileExists(atPath: ChatVideoFiles.url(p).path) {
            file = ChatVideoFiles.url(p)
            let m2 = await ChatVideoProbe.probe(file); if m2.w > 0 { meta = m2 }
        } else if j.optimize && VideoSendLogic.shouldOptimize(w: meta.w, h: meta.h, bytes: ChatVideoFiles.size(src), dur: meta.dur) {
            progress[id] = 0
            do {
                let out = try await ChatVideoExporter.shared.optimize(src, progress: { p in Task { @MainActor in ChatVideoUploads.shared.progress[id] = p } },
                                                                      paused: { b in Task { @MainActor in
                                                                          if b { ChatVideoUploads.shared.hot.insert(id) } else { ChatVideoUploads.shared.hot.remove(id) } } })
                file = out
                let base = (j.name as NSString).deletingPathExtension
                update(id) { $0.prepared = out.lastPathComponent; $0.name = (base.isEmpty ? "video" : base) + "-720p.mp4"; $0.mime = "video/mp4" }
                let m2 = await ChatVideoProbe.probe(out)
                if m2.w > 0 { meta.w = m2.w; meta.h = m2.h; meta.fs = m2.fs; meta.dur = m2.dur > 0 ? m2.dur : meta.dur }
            } catch is CancellationError { throw CancellationError() }
            catch {
                // Verkleinern ging nicht: Original senden (wie im Web), nur wenn es die Grenzen erlauben
            }
            hot.remove(id)
        }
        try Task.checkCancellation()
        j = job(id) ?? j
        let size = ChatVideoFiles.size(file)
        let chat = await chatInfo(j.chatID)
        let me = store.chat.me
        let plan = VideoSendLogic.plan(size: size, fileMax: me.fileMax, file2: me.file2, videoMax: me.videoMax, fed: chat?.isFed ?? false, bigFiles: chat?.bigFiles)
        switch plan {
        case .tooBig(let mb): throw ChatVideoError.tooBig(mb)
        case .peerOld(let mb): throw ChatVideoError.peerOld(mb)
        case .serverOld(let mb): throw ChatVideoError.serverOld(mb)
        case .v1, .v2: break
        }
        let cid = chat?.fileCID ?? String(j.chatID)
        // Poster: v1-Anhang mit eigenem Schluessel (auch aeltere Apps zeigen ihn)
        var thumb: VideoJob.Thumb?
        if j.thumb == nil, let jpg = meta.poster, let e = ChatCrypto.encryptChatFile(cid, data: jpg),
           let d = try? await store.apiRaw("api/chats/\(j.chatID)/files", data: e.blob), let tid = ShareJSON.obj(d)?["id"] as? String {
            thumb = .init(id: tid, k: ChatCrypto.b64(e.key), w: meta.pw, h: meta.ph, s: jpg.count)
            if let n = ChatMediaLogic.cacheName(tid) { ChatBlobCache.shared.write(n, e.blob) }
            posters[id] = meta.posterImage
        }
        update(id) { j in
            j.size = size; j.dur = meta.dur > 0 ? meta.dur : nil; j.fs = meta.fs
            if meta.w > 0 && meta.h > 0 { j.w = meta.w; j.h = meta.h }
            if let thumb { j.thumb = thumb }
        }
        try Task.checkCancellation()
        if plan == .v1 {                                                  // bis file_max: ein Block wie bisher
            let data = try Data(contentsOf: file)
            guard let e = await Task.detached(priority: .userInitiated, operation: { ChatCrypto.encryptChatFile(cid, data: data) }).value else { throw ChatVideoError.crypto }
            progress[id] = 0.5
            let d = try await store.apiRaw("api/chats/\(j.chatID)/files", data: e.blob)
            guard let fid = ShareJSON.obj(d)?["id"] as? String else { throw ChatVideoError.network(500) }
            update(id) { $0.fileID = fid; $0.fileKey = ChatCrypto.b64(e.key); $0.x = nil; $0.stage = .sending }
            return true
        }
        // saga-file/v2: Upload anlegen (Kennung = spaetere Datei-Kennung)
        let k = FileV2Key.fresh(size: size)
        let (d, code) = try await store.apiResponse("api/chats/\(j.chatID)/uploads", method: "POST", body: ["size": k.total, "fmt": 2])
        let o = ShareJSON.obj(d)
        guard (200..<300).contains(code), let up = o?["upload"] as? String else {
            throw APIError.http(code, o.flatMap { ShareJSON.str($0["error"]) } ?? "")
        }
        update(id) { j in
            j.v2 = k; j.upload = up; j.offset = (o?["offset"] as? NSNumber)?.int64Value ?? 0; j.total = k.total
            j.partMax = ShareJSON.int(o?["part_max"]); j.x = 2; j.fileKey = ChatCrypto.b64(k.key); j.stage = .uploading
        }
        progress[id] = 0
        try await pump(id)
        return false                                                      // weiter im Delegate
    }

    // MARK: Hochladen (Teile im Hintergrund)

    /// naechsten Teil verschluesseln (Datei, blockweise) und als Hintergrund-Upload starten
    private func pump(_ id: String) async throws {
        guard let store, let j = job(id), let k = j.v2, let up = j.upload, let url = store.apiURL("api/chat/uploads/\(up)") else { throw CancellationError() }
        guard let p = UploadLogic.part(offset: j.offset, total: j.total, partMax: j.partMax ?? store.chat.me.uploadPart) else {
            try await head(id); return                                   // alles gesendet, Antwort fehlt: Stand holen
        }
        let src = ChatVideoFiles.url(j.prepared ?? j.source), part = partURL(id)
        try await Task.detached(priority: .utility) { try FileV2Crypto.writePart(source: src, k: k, from: p.from, to: p.to, into: part) }.value
        ChatVideoFiles.protect(part)
        try Task.checkCancellation()
        var req = URLRequest(url: url, timeoutInterval: 120)
        req.httpMethod = "PUT"
        for (h, v) in await store.authHeaders(for: url) { req.setValue(v, forHTTPHeaderField: h) }
        req.setValue("application/octet-stream", forHTTPHeaderField: "Content-Type")
        req.setValue(UploadLogic.contentRange(from: p.from, to: p.to, total: j.total), forHTTPHeaderField: "Content-Range")
        let t = session.uploadTask(with: req, fromFile: part)
        t.taskDescription = "\(id)|\(p.from)"
        t.resume()
    }
    /// Stand beim Server (HEAD) -> weiter, fertig oder weg
    private func head(_ id: String) async throws {
        guard let store, let j = job(id), let up = j.upload, let url = store.apiURL("api/chat/uploads/\(up)") else { throw CancellationError() }
        var req = URLRequest(url: url, timeoutInterval: 30)
        req.httpMethod = "HEAD"
        for (h, v) in await store.authHeaders(for: url) { req.setValue(v, forHTTPHeaderField: h) }
        let r = try? await URLSession.shared.data(for: req)
        let http = r?.1 as? HTTPURLResponse
        switch UploadLogic.afterHead(status: http?.statusCode ?? 0, offset: http?.value(forHTTPHeaderField: "Upload-Offset"),
                                     length: http?.value(forHTTPHeaderField: "Upload-Length"), done: http?.value(forHTTPHeaderField: "Upload-Done")) {
        case .resume(let o):
            update(id) { $0.offset = o; $0.stage = .uploading }
            progress[id] = Double(o) / Double(max(1, j.total))
            try await pump(id)
        case .done:
            update(id) { $0.fileID = up; $0.offset = $0.total; $0.stage = .sending }
            try await deliver(id)
        case .gone: throw APIError.other(String(localized: "Upload abgebrochen."))
        case .reauth: _ = await store.resumeSession(); later(id)
        case .retry: later(id)
        }
    }
    /// Netz weg o. Ae.: mit wachsender Pause erneut (HEAD), nach 8 Fehlschlaegen Fehler mit "Erneut versuchen"
    private func later(_ id: String) {
        let n = (fails[id] ?? 0) + 1
        fails[id] = n
        guard n <= UploadLogic.maxFails else { fail(id, APIError.other(String(localized: "Upload abgebrochen – keine Verbindung."))); return }
        work[id] = Task { [weak self] in
            try? await Task.sleep(for: .seconds(UploadLogic.backoff(n)))
            guard !Task.isCancelled, let self, self.job(id) != nil else { return }
            do { try await self.head(id) } catch is CancellationError {} catch { self.fail(id, error) }
        }
    }
    private func sent(_ task: URLSessionTask, _ bytes: Int64) {
        guard let d = task.taskDescription?.split(separator: "|"), d.count == 2, let base = Int64(d[1]), let j = job(String(d[0])) else { return }
        progress[j.id] = Double(base + bytes) / Double(max(1, j.total))
    }
    private func completed(_ task: URLSessionTask, _ error: Error?) {
        let body = bodies.removeValue(forKey: task.taskIdentifier)
        guard let d = task.taskDescription?.split(separator: "|"), let first = d.first else { return }
        let id = String(first)
        try? FileManager.default.removeItem(at: partURL(id))
        guard job(id) != nil else { return }                              // abgebrochen
        if (error as? URLError)?.code == .cancelled { return }
        let http = task.response as? HTTPURLResponse
        let next = UploadLogic.afterPut(status: error == nil ? (http?.statusCode ?? 0) : 0, body: body.flatMap(ShareJSON.obj),
                                        headerOffset: http?.value(forHTTPHeaderField: "Upload-Offset").flatMap { Int64($0) })
        active.insert(id)
        switch next {
        case .next(let o):
            fails[id] = 0
            update(id) { $0.offset = o }
            progress[id] = Double(o) / Double(max(1, job(id)?.total ?? 1))
            guard store != nil else { active.remove(id); return }       // nach Neustart im Hintergrund: attach() macht weiter
            work[id] = Task { [weak self] in
                do { try await self?.pump(id) } catch is CancellationError {} catch { self?.fail(id, error) }
            }
        case .done(let fid):
            update(id) { $0.fileID = fid ?? $0.upload; $0.offset = $0.total; $0.stage = .sending }
            progress[id] = 1
            guard store != nil else { active.remove(id); return }
            work[id] = Task { [weak self] in
                do { try await self?.deliver(id) } catch is CancellationError {} catch { self?.fail(id, error) }
            }
        case .retry: later(id)
        case .reauth:
            work[id] = Task { [weak self] in _ = await self?.store?.resumeSession(); self?.later(id) }
        case .gone: fail(id, APIError.other(String(localized: "Upload abgebrochen.")))
        case .fail(let m): fail(id, APIError.other(m))
        }
    }

    // MARK: Senden der Nachricht

    private func deliver(_ id: String) async throws {
        guard let store, let j = job(id), let fid = j.fileID, let key = j.fileKey else { throw CancellationError() }
        update(id) { $0.stage = .sending }
        let th = j.thumb.map { ChatThumbRef(id: $0.id, key: $0.k, w: $0.w, h: $0.h, size: $0.s) }
        let ref = ChatFileRef(id: fid, key: key, name: j.name, mime: j.mime, size: Int(j.size ?? 0), w: j.w, h: j.h, thumb: th,
                              x: j.x, cs: j.x == 2 ? (j.v2?.cs ?? FileV2.cs) : nil, dur: j.dur, rot: j.x == 2 ? 0 : nil, fs: j.fs)
        let conv = store.chat.open.flatMap { $0.chatID == j.chatID ? $0 : nil } ?? ConversationModel(chatID: j.chatID, model: store.chat)
        try await conv.sendPrepared(text: j.text, replyTo: j.replyTo, refs: [ref])
        if let img = posters[id] { ChatMedia.remember(ChatImage(img, w: j.w, h: j.h), for: fid) }
        drop(id)
        kick()
    }

    // MARK: Erneut senden ueber Servergrenzen: v2-Ciphertext als neue Datei kopieren (Strom, je 8 MiB)

    func copyV2(_ f: ChatFileRef, chatID: Int) async throws -> String {
        guard let store else { throw ChatVideoError.network(0) }
        let total = FileV2.cipherSize(Int64(f.size), cs: f.cs ?? FileV2.cs)
        let (d, code) = try await store.apiResponse("api/chats/\(chatID)/uploads", method: "POST", body: ["size": total, "fmt": 2])
        let o = ShareJSON.obj(d)
        guard (200..<300).contains(code), let up = o?["upload"] as? String, let url = store.apiURL("api/chat/uploads/\(up)") else {
            throw APIError.http(code, o.flatMap { ShareJSON.str($0["error"]) } ?? "")
        }
        let src = ChatVideoSource(file: f, store: store)
        var off = (o?["offset"] as? NSNumber)?.int64Value ?? 0, tries = 0
        while let p = UploadLogic.part(offset: off, total: total, partMax: ShareJSON.int(o?["part_max"])) {
            let body = try await src.fetch(p.from, p.to)
            var req = URLRequest(url: url, timeoutInterval: 120)
            req.httpMethod = "PUT"
            for (h, v) in await store.authHeaders(for: url) { req.setValue(v, forHTTPHeaderField: h) }
            req.setValue("application/octet-stream", forHTTPHeaderField: "Content-Type")
            req.setValue(UploadLogic.contentRange(from: p.from, to: p.to, total: total), forHTTPHeaderField: "Content-Range")
            let r = try? await URLSession.shared.upload(for: req, from: body)
            let http = r?.1 as? HTTPURLResponse
            switch UploadLogic.afterPut(status: http?.statusCode ?? 0, body: r.flatMap { ShareJSON.obj($0.0) },
                                        headerOffset: http?.value(forHTTPHeaderField: "Upload-Offset").flatMap { Int64($0) }) {
            case .next(let n): off = n; tries = 0
            case .done(let fid): return fid ?? up
            case .retry, .reauth:
                tries += 1
                guard tries <= UploadLogic.maxFails else { throw APIError.other(String(localized: "Upload abgebrochen – keine Verbindung.")) }
                if http?.statusCode == 401 { _ = await store.resumeSession() }
                try await Task.sleep(for: .seconds(UploadLogic.backoff(tries)))
            case .gone: throw APIError.other(String(localized: "Upload abgebrochen."))
            case .fail(let m): throw APIError.other(m)
            }
        }
        return up
    }
}

// MARK: - URLSession (Hintergrund): Antworten und Fortschritt (Delegate-Warteschlange = Hauptthread)

extension ChatVideoUploads: URLSessionDataDelegate {
    nonisolated func urlSession(_ session: URLSession, task: URLSessionTask, didSendBodyData bytesSent: Int64, totalBytesSent: Int64,
                                totalBytesExpectedToSend: Int64) {
        MainActor.assumeIsolated { sent(task, totalBytesSent) }
    }
    nonisolated func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        MainActor.assumeIsolated { bodies[dataTask.taskIdentifier, default: Data()].append(data.prefix(65_536)) }
    }
    nonisolated func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        MainActor.assumeIsolated { completed(task, error) }
    }
    nonisolated func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
        MainActor.assumeIsolated { bgCompletion?(); bgCompletion = nil }
    }
}
