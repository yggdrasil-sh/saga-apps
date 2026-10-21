import SwiftUI
import AVFoundation

// Sprachnachrichten (Saga 2.5, Konzept Chat B3): Aufnahme (AVAudioEngine -> 48 kHz mono -> Opus 24 kbit/s -> Ogg,
// VoiceCodec.swift/VoiceOgg.swift), Senden als saga-file/v2 (Anhang mit voice/dur/wf), Wiedergabe. Ogg/Opus spielt
// AVFoundation nativ (iOS/macOS 26: AVURLAsset, AVAudioFile) - daher ein AVPlayer ueber den Medien-Weg der Videos
// (saga-media://, Range, blockweise entschluesselt) mit Tempo 1x/1,5x/2x. Ansichten: ChatVoiceViews.swift.

/// Fertige Aufnahme fuer Vorschau und Senden
struct VoiceClip: Equatable {
    var data: Data
    var dur: Double
    var wf: [UInt8]
    /// Kopie fuer die Vorschau (wird beim Verwerfen/Senden geloescht)
    var url: URL
}

@MainActor
final class VoiceRecorder: ObservableObject {
    enum State: Equatable { case idle, starting, recording, preview, sending, denied }
    @Published private(set) var state: State = .idle
    @Published private(set) var secs: Double = 0
    @Published private(set) var level: Float = 0
    /// Pegel der letzten Sekunden (Live-Wellenform)
    @Published private(set) var live: [Float] = []
    @Published private(set) var warned = false
    @Published var clip: VoiceClip?
    @Published var error: String?
    /// iPhone/iPad: nach oben geschoben (Finger darf los)
    @Published var locked = false
    var maxS: Double = 900
    var kbps = 24
    /// Ablauf beim Erreichen der Grenze (Ansicht zeigt dann die Vorschau)
    var onLimit: (() -> Void)?

    private var engine: AVAudioEngine?
    private let q = DispatchQueue(label: "saga.voice.rec", qos: .userInitiated)
    private nonisolated(unsafe) var enc: VoiceEncoder?
    private nonisolated(unsafe) var conv: AVAudioConverter?
    private var ticker: Task<Void, Never>?
    private var fileFeed: Task<Void, Never>?

    /// Aufnahme starten; false = kein Recht/kein Geraet (Hinweis in `error` bzw. state .denied)
    func start() async -> Bool {
        guard state == .idle || state == .denied else { return false }
        error = nil; warned = false; secs = 0; live = []; level = 0; locked = false
        state = .starting
        #if DEBUG
        if let p = UserDefaults.standard.string(forKey: "hkVoiceFile") { return startFromFile(URL(fileURLWithPath: p)) }   // nur Tests: Datei statt Mikrofon
        #endif
        guard await AVAudioApplication.requestRecordPermission() else {
            state = .denied
            error = String(localized: "Kein Zugriff auf das Mikrofon – bitte in den Einstellungen erlauben.")
            return false
        }
        do {
            VoicePlayer.shared.pause()
            #if os(iOS)
            let s = AVAudioSession.sharedInstance()
            try s.setCategory(.playAndRecord, mode: .spokenAudio, options: [.defaultToSpeaker, .allowBluetoothHFP])
            try s.setActive(true)
            #endif
            let e = AVAudioEngine()
            let input = e.inputNode
            let fmt = input.outputFormat(forBus: 0)
            guard fmt.sampleRate > 0, fmt.channelCount > 0, let c = AVAudioConverter(from: fmt, to: VoiceFormats.pcm) else {
                throw VoiceCodecError.format
            }
            let en = try VoiceEncoder(kbps: kbps)
            enc = en; conv = c
            input.installTap(onBus: 0, bufferSize: 4096, format: fmt) { [weak self] buf, _ in
                self?.q.async { self?.push(buf) }
            }
            e.prepare()
            try e.start()
            engine = e
            began()
            return true
        } catch {
            stopEngine()
            state = .idle
            self.error = String(localized: "Aufnahme nicht möglich: \(error.localizedDescription)")
            return false
        }
    }
    /// nur fuer Tests (Simulator): eine Datei statt des Mikrofons in Echtzeit einspeisen
    func startFromFile(_ url: URL) -> Bool {
        guard let f = try? AVAudioFile(forReading: url), let c = AVAudioConverter(from: f.processingFormat, to: VoiceFormats.pcm),
              let en = try? VoiceEncoder(kbps: kbps) else { state = .idle; error = String(localized: "Aufnahme nicht möglich: \("?")"); return false }
        enc = en; conv = c
        began()
        fileFeed = Task { [weak self] in
            while !Task.isCancelled {
                guard let b = AVAudioPCMBuffer(pcmFormat: f.processingFormat, frameCapacity: 4800) else { return }
                if (try? f.read(into: b)) == nil || b.frameLength == 0 { return }
                self?.q.async { self?.push(b) }
                try? await Task.sleep(for: .milliseconds(100))
            }
        }
        return true
    }
    private func began() {
        state = .recording
        ticker = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .milliseconds(100))
                guard let self, self.state == .recording else { return }
                self.tick()
            }
        }
    }
    /// Audio-Warteschlange: in 48 kHz mono wandeln und kodieren
    private nonisolated func push(_ buf: AVAudioPCMBuffer) {
        guard let c = conv, let en = enc else { return }
        let ratio = 48_000 / buf.format.sampleRate
        guard let out = AVAudioPCMBuffer(pcmFormat: VoiceFormats.pcm, frameCapacity: AVAudioFrameCount(Double(buf.frameLength) * ratio + 64)) else { return }
        var given = false
        _ = c.convert(to: out, error: nil) { _, st in
            if given { st.pointee = .noDataNow; return nil }
            given = true; st.pointee = .haveData; return buf
        }
        guard out.frameLength > 0, let p = out.floatChannelData?[0] else { return }
        try? en.append(UnsafeBufferPointer(start: p, count: Int(out.frameLength)))
    }
    private func tick() {
        let (s, lv): (Double, Float) = q.sync { (enc?.seconds ?? 0, enc?.lastLevel ?? 0) }
        secs = s; level = min(1, lv * 4)
        live.append(level); if live.count > 120 { live.removeFirst(live.count - 120) }
        switch VoiceLogic.tick(secs: s, maxS: maxS, warned: warned) {
        case .run: break
        case .warn: warned = true
        case .stop: onLimit?()
        }
    }
    private func stopEngine() {
        ticker?.cancel(); ticker = nil
        fileFeed?.cancel(); fileFeed = nil
        if let e = engine { e.inputNode.removeTap(onBus: 0); e.stop() }
        engine = nil
        #if os(iOS)
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        #endif
    }
    /// Stopp: unter 1 s verwerfen (false), sonst Vorschau
    @discardableResult func stop() async -> Bool {
        guard state == .recording else { return false }
        stopEngine()
        let r: (Data, Double, [Float])? = await withCheckedContinuation { c in
            q.async { [self] in
                guard let en = enc, let d = try? en.finish() else { c.resume(returning: nil); return }
                c.resume(returning: (d, min(maxS, en.seconds), en.levels))
            }
        }
        enc = nil; conv = nil
        guard let (data, dur, levels) = r, VoiceLogic.keep(duration: dur) else {
            state = .idle
            if r != nil { error = String(localized: "Zu kurz – nicht gesendet.") }
            return false
        }
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("voice-\(UUID().uuidString).ogg")
        #if os(iOS)
        try? data.write(to: url, options: [.atomic, .completeFileProtection])
        #else
        try? data.write(to: url, options: [.atomic])
        #endif
        clip = VoiceClip(data: data, dur: VideoSendLogic.roundDur(dur), wf: VoiceLogic.waveform(levels), url: url)
        state = .preview
        return true
    }
    /// Verwerfen (Aufnahme oder Vorschau)
    func cancel() {
        stopEngine()
        q.sync { enc = nil; conv = nil }
        if let c = clip { if VoicePlayer.shared.currentID == VoicePlayer.previewID { VoicePlayer.shared.stop() }; try? FileManager.default.removeItem(at: c.url) }
        clip = nil; locked = false; state = .idle
    }
    func sending(_ on: Bool) { if clip != nil { state = on ? .sending : .preview } }
    #if DEBUG
    /// nur fuer Bilder (ChatX3Preview.swift): Zustand setzen, ohne Mikrofon
    func setMock(state s: State, secs t: Double, live l: [Float], clip c: VoiceClip?) { state = s; secs = t; live = l; clip = c; warned = t >= maxS - 60 }
    #endif
    /// nach dem Senden aufraeumen
    func sent() { cancel() }
    var active: Bool { state != .idle && state != .denied }
}

// MARK: - Wiedergabe (ein gemeinsamer Player)

@MainActor
final class VoicePlayer: ObservableObject {
    static let shared = VoicePlayer()
    static let previewID = "voice-preview"
    @Published private(set) var currentID: String?
    @Published private(set) var playing = false
    @Published private(set) var position: Double = 0
    @Published private(set) var loading = false
    @Published private(set) var failed: String?
    @Published var rate: Double = {
        let v = UserDefaults.standard.double(forKey: "hk.voice.speed")
        return VoiceLogic.speeds.contains(v) ? v : 1
    }()
    let player = AVPlayer()
    private var loader: ChatVideoLoader?
    private var timeObs: Any?
    private var endObs: NSObjectProtocol?
    private var statusObs: NSKeyValueObservation?
    private var next: (() -> Void)?
    private var duration: Double = 0

    init() {
        player.automaticallyWaitsToMinimizeStalling = false               // nach dem ersten Block (256 KiB ~ 85 s) los
        timeObs = player.addPeriodicTimeObserver(forInterval: CMTime(value: 1, timescale: 10), queue: .main) { [weak self] t in
            MainActor.assumeIsolated { if let s = self, s.currentID != nil { s.position = max(0, t.seconds.isFinite ? t.seconds : 0) } }
        }
        #if os(iOS)
        NotificationCenter.default.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { [weak self] n in
            let began = (n.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt).flatMap(AVAudioSession.InterruptionType.init) == .began
            MainActor.assumeIsolated { if began { self?.pause() } }               // Anruf (auch von anderen Apps): pausieren
        }
        #endif
    }

    /// Abspielen: v2 ueber Range (blockweise entschluesselt), v1 bzw. Vorschau aus dem Speicher bzw. einer Datei
    func play(id: String, source: ChatVideoSource?, url: URL? = nil, duration dur: Double, from: Double? = nil, next: (() -> Void)? = nil) {
        self.next = next
        if currentID == id, player.currentItem != nil {
            if let from { seek(from) }
            resume(); return
        }
        stop()
        let asset: AVURLAsset
        if let url { asset = AVURLAsset(url: url) }
        else if let source, let u = URL(string: "\(ChatVideoLoader.scheme)://\(source.file.id).ogg") {
            let l = ChatVideoLoader(source)
            asset = AVURLAsset(url: u)
            asset.resourceLoader.setDelegate(l, queue: l.queue)
            loader = l
        } else { return }
        let item = AVPlayerItem(asset: asset)
        item.audioTimePitchAlgorithm = .timeDomain                       // schneller ohne Micky-Maus-Stimme
        currentID = id; duration = dur; position = from ?? 0; failed = nil; loading = true
        statusObs = item.observe(\.status) { [weak self] it, _ in
            Task { @MainActor in
                guard let self, self.player.currentItem === it else { return }
                if it.status == .failed { self.failed = String(localized: "Sprachnachricht nicht abspielbar."); self.playing = false; self.loading = false }
                if it.status == .readyToPlay { self.loading = false }
            }
        }
        endObs = NotificationCenter.default.addObserver(forName: .AVPlayerItemDidPlayToEndTime, object: item, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.finished() }
        }
        player.replaceCurrentItem(with: item)
        if let from, from > 0 { player.seek(to: CMTime(seconds: from, preferredTimescale: 48_000)) }
        session(true)
        player.playImmediately(atRate: Float(rate))
        playing = true
    }
    func resume() {
        guard player.currentItem != nil else { return }
        session(true)
        player.playImmediately(atRate: Float(rate)); playing = true
    }
    func pause() { player.pause(); playing = false }
    func toggle() { playing ? pause() : resume() }
    func pauseForCall() { if playing { pause() } }
    func seek(_ sec: Double) {
        position = sec
        player.seek(to: CMTime(seconds: sec, preferredTimescale: 48_000), toleranceBefore: .zero, toleranceAfter: .zero)
    }
    func cycleRate() {
        rate = VoiceLogic.nextSpeed(rate)
        UserDefaults.standard.set(rate, forKey: "hk.voice.speed")
        if playing { player.rate = Float(rate) }
    }
    func stop() {
        player.pause(); player.replaceCurrentItem(with: nil)
        loader?.cancelAll(); loader = nil
        if let o = endObs { NotificationCenter.default.removeObserver(o) }
        endObs = nil; statusObs = nil
        currentID = nil; playing = false; position = 0; loading = false
        session(false)
    }
    private func finished() {
        let n = next
        stop()
        n?()                                                           // danach die naechste Sprachnachricht
    }
    /// iOS: Wiedergabe auch im Hintergrund (UIBackgroundModes audio), gesprochene Inhalte
    private func session(_ on: Bool) {
        #if os(iOS)
        let s = AVAudioSession.sharedInstance()
        if on { try? s.setCategory(.playback, mode: .spokenAudio); try? s.setActive(true) }
        else { try? s.setActive(false, options: .notifyOthersOnDeactivation) }
        #endif
    }
    func isCurrent(_ id: String) -> Bool { currentID == id }
}

// MARK: - Hochladen und Senden

@MainActor
enum ChatVoiceUpload {
    /// saga-file/v2 (fortsetzbarer Upload, im Speicher - hoechstens wenige MB) bzw. v1 bei Servern ohne file2
    static func upload(_ data: Data, chatID: Int, cid: String, store: Store, v2: Bool, progress: @escaping (Double) -> Void) async throws -> (id: String, key: String) {
        guard v2 else {
            guard let e = ChatCrypto.encryptChatFile(cid, data: data) else { throw APIError.other(String(localized: "Upload fehlgeschlagen")) }
            let d = try await store.apiRaw("api/chats/\(chatID)/files", data: e.blob)
            guard let fid = ShareJSON.obj(d)?["id"] as? String else { throw APIError.other(String(localized: "Upload fehlgeschlagen")) }
            progress(1)
            return (fid, ChatCrypto.b64(e.key))
        }
        let k = FileV2Key.fresh(size: Int64(data.count))
        guard let blob = await Task.detached(priority: .userInitiated, operation: { FileV2Crypto.encryptAll(data, k: k) }).value else {
            throw APIError.other(String(localized: "Upload fehlgeschlagen"))
        }
        let (d, code) = try await store.apiResponse("api/chats/\(chatID)/uploads", method: "POST", body: ["size": k.total, "fmt": 2])
        let o = ShareJSON.obj(d)
        guard (200..<300).contains(code), let up = o?["upload"] as? String, let url = store.apiURL("api/chat/uploads/\(up)") else {
            throw APIError.http(code, o.flatMap { ShareJSON.str($0["error"]) } ?? "")
        }
        var off = (o?["offset"] as? NSNumber)?.int64Value ?? 0, tries = 0
        while let p = UploadLogic.part(offset: off, total: k.total, partMax: ShareJSON.int(o?["part_max"])) {
            var req = URLRequest(url: url, timeoutInterval: 120)
            req.httpMethod = "PUT"
            for (h, v) in await store.authHeaders(for: url) { req.setValue(v, forHTTPHeaderField: h) }
            req.setValue("application/octet-stream", forHTTPHeaderField: "Content-Type")
            req.setValue(UploadLogic.contentRange(from: p.from, to: p.to, total: k.total), forHTTPHeaderField: "Content-Range")
            let r = try? await URLSession.shared.upload(for: req, from: blob.subdata(in: Int(p.from)..<Int(p.to) + 1))
            let http = r?.1 as? HTTPURLResponse
            switch UploadLogic.afterPut(status: http?.statusCode ?? 0, body: r.flatMap { ShareJSON.obj($0.0) },
                                        headerOffset: http?.value(forHTTPHeaderField: "Upload-Offset").flatMap { Int64($0) }) {
            case .next(let n): off = n; tries = 0; progress(Double(n) / Double(max(1, k.total)))
            case .done(let fid): progress(1); return (fid ?? up, ChatCrypto.b64(k.key))
            case .retry, .reauth:
                tries += 1
                guard tries <= UploadLogic.maxFails else { throw APIError.other(String(localized: "Upload abgebrochen – keine Verbindung.")) }
                if http?.statusCode == 401 { _ = await store.resumeSession() }
                try await Task.sleep(for: .seconds(UploadLogic.backoff(tries)))
            case .gone: throw APIError.other(String(localized: "Upload abgebrochen."))
            case .fail(let m): throw APIError.other(m)
            }
        }
        progress(1)
        return (up, ChatCrypto.b64(k.key))
    }
}

extension ConversationModel {
    /// Sprachnachrichten in diesem Chat aufnehmen/senden (x3 + Server kann es)
    var voiceOK: Bool { x3 && model.me.x3.voice }
    /// Aufnahme hochladen und als Anhang (voice, dur, wf) senden; true = gesendet
    func sendVoice(_ c: VoiceClip, replyTo: String?, progress: @escaping (Double) -> Void) async -> Bool {
        guard let store, voiceOK else { return false }
        guard c.data.count <= x3Limits.voiceMaxBytes else { sendError = String(localized: "Sprachnachricht zu groß."); return false }
        sending = true; sendError = nil; defer { sending = false }
        do {
            let v2 = model.me.file2
            let cid = info?.fileCID ?? String(chatID)
            let u = try await ChatVoiceUpload.upload(c.data, chatID: chatID, cid: cid, store: store, v2: v2, progress: progress)
            guard var ref = ChatFileRef(VoiceLogic.fileDict(id: u.id, key: u.key, size: c.data.count, v2: v2, cs: FileV2.cs, dur: c.dur, wf: c.wf)) else {
                throw APIError.other(String(localized: "Nicht gesendet"))
            }
            ref.voice = true
            return try await post(text: "", title: nil, refs: [ref], ids: [u.id], replyTo: replyTo, kind: "msg", ai: nil)
        } catch {
            sendError = String(localized: "Nicht gesendet: \(error.localizedDescription)")
            return false
        }
    }
    /// Quelle zum Abspielen: v2 ueber Range, v1 ganz (klein) entschluesselt
    func voiceSource(_ f: ChatFileRef) async throws -> ChatVideoSource {
        if f.isV2 { return ChatVideoSource(file: f, store: store) }
        return ChatVideoSource(file: f, store: store, plainV1: try await original(f))
    }
    /// Sprachnachrichten in Reihenfolge (fuer "danach die naechste")
    func voiceAfter(_ id: String) -> (ChatMessage, ChatFileRef)? {
        let all = messages.flatMap { m in (m.content?.files ?? []).filter(\.voice).map { (m, $0) } }
        guard let i = all.firstIndex(where: { $0.1.id == id }), i + 1 < all.count else { return nil }
        return all[i + 1]
    }
}
