import SwiftUI
import AVFoundation
import WebRTC

// Anrufe (Saga 2.1): WebRTC-Grundlagen - Fabrik, Kamera bzw. Test-Bildquelle, Test-Tonquelle (nur Tests, nie das echte
// Mikrofon), Video-Ansichten fuer SwiftUI, Klingel-/Freizeichen. Ablauf und Signalisierung: Calls.swift.

enum CallEnv {
    /// Nur Tests (DEBUG, Startparameter -hkFakeMedia YES): Test-Bild und Test-Ton statt Kamera/Mikrofon, kein CallKit
    static var fakeMedia: Bool {
        #if DEBUG
        return UserDefaults.standard.bool(forKey: "hkFakeMedia")
        #else
        return false
        #endif
    }
    /// CallKit nur auf echten iPhones/iPads (nicht im Simulator, nicht mit Test-Medien)
    static var callKit: Bool {
        #if os(iOS) && !targetEnvironment(simulator)
        return !fakeMedia
        #else
        return false
        #endif
    }
    /// Testausgaben (Konsole des Simulators) - keine Inhalte, nur Zustaende und Zahlen
    static func log(_ s: @autoclosure () -> String) {
        #if DEBUG
        if fakeMedia { print("CALL-TEST " + s()) }
        #endif
    }
}

enum CallRTC {
    private static var made: RTCPeerConnectionFactory?
    @MainActor static var factory: RTCPeerConnectionFactory {
        if let f = made { return f }
        RTCInitializeSSL()
        let enc = RTCDefaultVideoEncoderFactory(), dec = RTCDefaultVideoDecoderFactory()
        var f: RTCPeerConnectionFactory
        #if os(iOS)
        if CallEnv.fakeMedia { f = RTCPeerConnectionFactory(encoderFactory: enc, decoderFactory: dec, audioDevice: FakeAudioDevice.shared) }
        else {
            // Audio-Session-Modus steht schon seit dem App-Start fest (CallAudio.prepare) - hier NICHT erneut setzen:
            // die Fabrik entsteht beim ersten eingehenden Anruf erst NACH provider(_:didActivate:), ein
            // isAudioEnabled = false an dieser Stelle schaltete den Ton fuer den ganzen Anruf ab.
            CallAudio.prepare()
            f = RTCPeerConnectionFactory(encoderFactory: enc, decoderFactory: dec)
        }
        #else
        f = RTCPeerConnectionFactory(encoderFactory: enc, decoderFactory: dec)
        #endif
        made = f
        return f
    }
    static let constraints = RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: ["DtlsSrtpKeyAgreement": "true"])
}

#if os(iOS)
/// Audio-Session fuer Anrufe (iPhone/iPad). Mit CallKit gehoert die Aktivierung dem System: WebRTC laeuft mit
/// useManualAudio und bekommt den Ton erst in provider(_:didActivate:). Ohne CallKit (Simulator) steuert WebRTC selbst.
/// Kategorie/Modus gehen ueber RTCAudioSessionConfiguration.webRTC - sonst stellt WebRTC beim Start der Wiedergabe
/// wieder auf voiceChat ohne Lautsprecher (Video lief dann leise ueber die Hoermuschel).
enum CallAudio {
    nonisolated(unsafe) private static var prepared = false
    /// Einmal beim App-Start (vor jedem CallKit-Anruf) und vor dem Bau der Fabrik; danach nie wieder zuruecksetzen
    static func prepare() {
        guard !prepared, !CallEnv.fakeMedia else { return }
        prepared = true
        let s = RTCAudioSession.sharedInstance()
        s.useManualAudio = CallEnv.callKit
        s.isAudioEnabled = !CallEnv.callKit
        s.ignoresPreferredAttributeConfigurationErrors = true
    }
    static func options(speaker: Bool) -> AVAudioSession.CategoryOptions {
        speaker ? [.allowBluetoothHFP, .defaultToSpeaker] : [.allowBluetoothHFP]
    }
    /// Vor dem Annehmen/Anrufen: Kategorie playAndRecord, Modus voiceChat bzw. videoChat (+ Lautsprecher)
    static func configure(video: Bool) {
        guard !CallEnv.fakeMedia else { return }
        prepare()
        let c = RTCAudioSessionConfiguration.webRTC()
        c.category = AVAudioSession.Category.playAndRecord.rawValue
        c.mode = (video ? AVAudioSession.Mode.videoChat : .voiceChat).rawValue
        c.categoryOptions = options(speaker: video)
        RTCAudioSessionConfiguration.setWebRTC(c)
        let s = RTCAudioSession.sharedInstance()
        s.lockForConfiguration()
        do { try s.setConfiguration(c) } catch { CallEnv.log("audio config: \(error.localizedDescription)") }
        s.unlockForConfiguration()
    }
    /// CallKit hat die Session aktiviert
    static func activated(_ session: AVAudioSession) {
        let s = RTCAudioSession.sharedInstance()
        s.audioSessionDidActivate(session)
        s.isAudioEnabled = true
    }
    static func deactivated(_ session: AVAudioSession) {
        let s = RTCAudioSession.sharedInstance()
        s.audioSessionDidDeactivate(session)
        s.isAudioEnabled = false
    }
    /// Rueckfall: CallKit hat (noch) nicht aktiviert, obwohl der Anruf verbunden ist (z. B. angenommen ohne
    /// CXAnswerCallAction) - Session selbst aktivieren, sonst bleibt der Anruf stumm
    static var enabled: Bool { RTCAudioSession.sharedInstance().isAudioEnabled }
    static func ensureActive() {
        guard !CallEnv.fakeMedia else { return }
        let s = RTCAudioSession.sharedInstance()
        guard !s.isAudioEnabled || !s.isActive else { return }
        s.lockForConfiguration()
        do { try s.setConfiguration(RTCAudioSessionConfiguration.webRTC(), active: true); manual = true } catch { CallEnv.log("audio activate: \(error.localizedDescription)") }
        s.unlockForConfiguration()
        s.isAudioEnabled = true
    }
    nonisolated(unsafe) private static var manual = false
    /// Anrufende: selbst aktivierte Session wieder freigeben (andere Apps duerfen wieder spielen)
    static func releaseManual() {
        guard manual else { return }
        manual = false
        let s = RTCAudioSession.sharedInstance()
        s.lockForConfiguration(); try? s.setActive(false); s.unlockForConfiguration()
        if CallEnv.callKit { s.isAudioEnabled = false }
    }
    /// Lautsprecher an/aus (Knopf): Optionen der Konfiguration mitziehen, damit WebRTC es nicht zuruecksetzt
    static func route(speaker: Bool, video: Bool) {
        guard !CallEnv.fakeMedia else { return }
        let c = RTCAudioSessionConfiguration.webRTC()
        c.mode = (speaker && video ? AVAudioSession.Mode.videoChat : .voiceChat).rawValue
        c.categoryOptions = options(speaker: speaker)
        RTCAudioSessionConfiguration.setWebRTC(c)
        let s = RTCAudioSession.sharedInstance()
        s.lockForConfiguration()
        try? s.setCategory(.playAndRecord, mode: AVAudioSession.Mode(rawValue: c.mode), options: c.categoryOptions)
        try? s.overrideOutputAudioPort(speaker ? .speaker : .none)
        s.unlockForConfiguration()
    }
}
#endif

// MARK: - Bildquelle

/// Kamera (vorn) bzw. im Testmodus ein bewegtes Testbild; liefert an eine RTCVideoSource
@MainActor
final class CallCamera {
    let source: RTCVideoSource
    let track: RTCVideoTrack
    private var camera: RTCCameraVideoCapturer?
    private var fake: FakeVideoCapturer?
    private(set) var front = true
    /// kurze Seite des Aufnahmeformats (Bitrate-Regeln: scaleResolutionDownBy relativ dazu)
    private(set) var height = 720
    /// 2.4: Kamera vom System unterbrochen (true) bzw. wieder frei (false), z. B. im Bild-im-Bild ohne Multitasking-Freigabe
    var onInterrupt: ((Bool) -> Void)?
    private var observers: [NSObjectProtocol] = []
    init?(factory: RTCPeerConnectionFactory) {
        source = factory.videoSource()
        track = factory.videoTrack(with: source, trackId: "v" + ChatCrypto.hexID().prefix(8))
        if CallEnv.fakeMedia {
            let f = FakeVideoCapturer(delegate: source); fake = f; f.start(); height = 360
            return
        }
        guard RTCCameraVideoCapturer.captureDevices().isEmpty == false else { return nil }
        let cap = RTCCameraVideoCapturer(delegate: source)
        camera = cap
        #if os(iOS)
        // Kamera im Bild-im-Bild (Videoanruf-App, iOS 16+): nur wenn das System es fuer diese App erlaubt; sonst
        // pausiert sie im Hintergrund und das Gegenueber bekommt cam=false (kein Fehler)
        let session = cap.captureSession
        if session.isMultitaskingCameraAccessSupported && !session.isMultitaskingCameraAccessEnabled {
            session.beginConfiguration(); session.isMultitaskingCameraAccessEnabled = true; session.commitConfiguration()
        }
        let nc = NotificationCenter.default
        observers.append(nc.addObserver(forName: AVCaptureSession.wasInterruptedNotification, object: session, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.onInterrupt?(true) }
        })
        observers.append(nc.addObserver(forName: AVCaptureSession.interruptionEndedNotification, object: session, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.onInterrupt?(false) }
        })
        #endif
        start()
    }
    private func start() {
        guard let camera else { return }
        let devs = RTCCameraVideoCapturer.captureDevices()
        guard let dev = devs.first(where: { $0.position == (front ? .front : .back) }) ?? devs.first else { return }
        // Format um 1280x720, hoechstens 30 fps
        let formats = RTCCameraVideoCapturer.supportedFormats(for: dev)
        let fmt = formats.min(by: { a, b in
            let da = CMVideoFormatDescriptionGetDimensions(a.formatDescription), db = CMVideoFormatDescriptionGetDimensions(b.formatDescription)
            return abs(Int(da.width) - 1280) + abs(Int(da.height) - 720) < abs(Int(db.width) - 1280) + abs(Int(db.height) - 720)
        }) ?? formats.first
        guard let fmt else { return }
        let dims = CMVideoFormatDescriptionGetDimensions(fmt.formatDescription)
        height = Int(min(dims.width, dims.height))
        let fps = min(30, Int(fmt.videoSupportedFrameRateRanges.map(\.maxFrameRate).max() ?? 30))
        camera.startCapture(with: dev, format: fmt, fps: fps)
    }
    #if os(iOS)
    func switchCamera() { front.toggle(); camera?.stopCapture(); start() }
    var canSwitch: Bool { camera != nil && RTCCameraVideoCapturer.captureDevices().count > 1 }
    #endif
    func stop() {
        for o in observers { NotificationCenter.default.removeObserver(o) }
        observers = []; onInterrupt = nil
        camera?.stopCapture(); fake?.stop(); camera = nil; fake = nil; track.isEnabled = false
    }
}

/// Testbild (nur Tests): Farbbalken mit wanderndem Balken, 640x360 bei 15 fps - keine Kamera noetig
final class FakeVideoCapturer: RTCVideoCapturer, @unchecked Sendable {
    private var timer: DispatchSourceTimer?
    private var n = 0
    private let w: Int32 = 640, h: Int32 = 360
    func start() {
        let t = DispatchSource.makeTimerSource(queue: DispatchQueue(label: "saga.fakevideo"))
        t.schedule(deadline: .now(), repeating: .milliseconds(66))
        t.setEventHandler { [weak self] in self?.frame() }
        t.resume(); timer = t
    }
    func stop() { timer?.cancel(); timer = nil }
    private func frame() {
        n += 1
        let W = Int(w), H = Int(h), cw = W / 2, ch = H / 2
        var y = [UInt8](repeating: 0, count: W * H), u = [UInt8](repeating: 128, count: cw * ch), v = [UInt8](repeating: 128, count: cw * ch)
        let bar = (n * 6) % W
        for r in 0..<H { for c in 0..<W { y[r * W + c] = (abs(c - bar) < 24) ? 235 : UInt8(40 + (c * 160 / W)) } }
        let colors: [(UInt8, UInt8)] = [(90, 240), (54, 34), (240, 110), (128, 128), (200, 60), (60, 200)]
        for r in 0..<ch { for c in 0..<cw { let k = colors[(c * colors.count) / cw]; u[r * cw + c] = k.0; v[r * cw + c] = k.1 } }
        let buf = RTCI420Buffer(width: w, height: h, dataY: y, dataU: u, dataV: v)
        let f = RTCVideoFrame(buffer: buf, rotation: ._0, timeStampNs: Int64(DispatchTime.now().uptimeNanoseconds))
        delegate?.capturer(self, didCapture: f)
    }
}

#if os(iOS)
/// Testton (nur Tests): 440 Hz statt Mikrofon, Wiedergabe wird nur gemessen (Pegel = Ton kommt an), nie ausgegeben
final class FakeAudioDevice: NSObject, RTCAudioDevice, @unchecked Sendable {
    static let shared = FakeAudioDevice()
    private let rate = 48_000.0, frames = 480
    private var delegate: RTCAudioDeviceDelegate?
    private var timer: DispatchSourceTimer?
    private var phase = 0.0
    private(set) var playing = false, recording = false
    /// hoechster Pegel der empfangenen Wiedergabe seit dem letzten Abruf (0...1)
    private var peak: Double = 0
    private let lock = NSLock()
    func takePeak() -> Double { lock.lock(); defer { lock.unlock() }; let p = peak; peak = 0; return p }

    var deviceInputSampleRate: Double { rate }
    var inputIOBufferDuration: TimeInterval { 0.01 }
    var inputNumberOfChannels: Int { 1 }
    var inputLatency: TimeInterval { 0 }
    var deviceOutputSampleRate: Double { rate }
    var outputIOBufferDuration: TimeInterval { 0.01 }
    var outputNumberOfChannels: Int { 1 }
    var outputLatency: TimeInterval { 0 }
    var isInitialized: Bool { delegate != nil }
    func initialize(with delegate: RTCAudioDeviceDelegate) -> Bool { self.delegate = delegate; return true }
    func terminateDevice() -> Bool { stopTimer(); delegate = nil; playing = false; recording = false; return true }
    var isPlayoutInitialized: Bool { delegate != nil }
    func initializePlayout() -> Bool { true }
    var isPlaying: Bool { playing }
    /// -hkFakeNoPlayout YES: Wiedergabe startet nie (nachgestellt: Audio-Session nicht aktiv) - prueft die Ton-Diagnose
    func startPlayout() -> Bool { playing = !UserDefaults.standard.bool(forKey: "hkFakeNoPlayout"); startTimer(); return true }
    func stopPlayout() -> Bool { playing = false; return true }
    var isRecordingInitialized: Bool { delegate != nil }
    func initializeRecording() -> Bool { true }
    var isRecording: Bool { recording }
    func startRecording() -> Bool { recording = true; startTimer(); return true }
    func stopRecording() -> Bool { recording = false; return true }

    private func startTimer() {
        guard timer == nil else { return }
        let t = DispatchSource.makeTimerSource(queue: DispatchQueue(label: "saga.fakeaudio", qos: .userInteractive))
        t.schedule(deadline: .now(), repeating: .milliseconds(10))
        t.setEventHandler { [weak self] in self?.tick() }
        t.resume(); timer = t
    }
    private func stopTimer() { timer?.cancel(); timer = nil }
    private func tick() {
        guard let d = delegate else { return }
        var flags = AudioUnitRenderActionFlags()
        var ts = AudioTimeStamp()
        let bytes = frames * 2
        let mem = UnsafeMutableRawPointer.allocate(byteCount: bytes, alignment: 2)
        defer { mem.deallocate() }
        var abl = AudioBufferList(mNumberBuffers: 1, mBuffers: AudioBuffer(mNumberChannels: 1, mDataByteSize: UInt32(bytes), mData: mem))
        if recording {
            let p = mem.bindMemory(to: Int16.self, capacity: frames)
            for i in 0..<frames { p[i] = Int16(sin(phase) * 6000); phase += 2 * .pi * 440 / rate }
            if phase > 2 * .pi * 1000 { phase = phase.truncatingRemainder(dividingBy: 2 * .pi) }
            _ = d.deliverRecordedData(&flags, &ts, 1, UInt32(frames), &abl, nil, nil)
        }
        if playing {
            abl.mBuffers.mDataByteSize = UInt32(bytes)
            _ = d.getPlayoutData(&flags, &ts, 0, UInt32(frames), &abl)
            let p = mem.bindMemory(to: Int16.self, capacity: frames)
            var m: Int16 = 0
            for i in 0..<frames { m = max(m, p[i] == Int16.min ? Int16.max : abs(p[i])) }
            lock.lock(); peak = max(peak, Double(m) / 32767); lock.unlock()
        }
    }
}
#endif

// MARK: - Video-Ansicht

#if os(iOS)
struct RTCVideoBox: UIViewRepresentable {
    let track: RTCVideoTrack?
    var mirror = false
    var fill = true
    /// 2.5: gemeldete Videogroesse (nach Drehung) - fuer den Rahmen des eigenen Bilds
    var onSize: ((CGSize) -> Void)? = nil
    func makeUIView(context: Context) -> RTCMTLVideoView {
        let v = RTCMTLVideoView(frame: .zero)
        v.videoContentMode = fill ? .scaleAspectFill : .scaleAspectFit
        v.clipsToBounds = true
        v.delegate = context.coordinator
        return v
    }
    func updateUIView(_ v: RTCMTLVideoView, context: Context) {
        v.transform = mirror ? CGAffineTransform(scaleX: -1, y: 1) : .identity
        context.coordinator.onSize = onSize
        context.coordinator.attach(track, to: v)
    }
    static func dismantleUIView(_ v: RTCMTLVideoView, coordinator: Coord) { coordinator.attach(nil, to: v) }
    func makeCoordinator() -> Coord { Coord() }
    final class Coord: NSObject, RTCVideoViewDelegate {
        weak var track: RTCVideoTrack?
        var onSize: ((CGSize) -> Void)?
        func attach(_ t: RTCVideoTrack?, to v: RTCVideoRenderer) {
            guard t !== track else { return }
            track?.remove(v); track = t; t?.add(v)
        }
        func videoView(_ videoView: RTCVideoRenderer, didChangeVideoSize size: CGSize) { onSize?(size) }
    }
}
#else
struct RTCVideoBox: NSViewRepresentable {
    let track: RTCVideoTrack?
    var mirror = false
    var fill = true
    // webrtc-sdk: RTCMTLVideoView ist am Mac selbst ein NSView (RTCMTLNSVideoView steht nur noch im Header, ohne Symbol)
    /// 2.5: gemeldete Videogroesse - der Mac kennt kein aspectFill, der Rahmen folgt deshalb dem Verhaeltnis des Videos
    var onSize: ((CGSize) -> Void)? = nil
    func makeNSView(context: Context) -> RTCMTLVideoView {
        let v = RTCMTLVideoView(frame: .zero)
        v.wantsLayer = true
        v.delegate = context.coordinator
        return v
    }
    func updateNSView(_ v: RTCMTLVideoView, context: Context) {
        v.layer?.setAffineTransform(mirror ? CGAffineTransform(scaleX: -1, y: 1) : .identity)
        context.coordinator.onSize = onSize
        context.coordinator.attach(track, to: v)
    }
    static func dismantleNSView(_ v: RTCMTLVideoView, coordinator: Coord) { coordinator.attach(nil, to: v) }
    func makeCoordinator() -> Coord { Coord() }
    final class Coord: NSObject, RTCVideoViewDelegate {
        weak var track: RTCVideoTrack?
        var onSize: ((CGSize) -> Void)?
        func attach(_ t: RTCVideoTrack?, to v: RTCVideoRenderer) {
            guard t !== track else { return }
            track?.remove(v); track = t; t?.add(v)
        }
        func videoView(_ videoView: RTCVideoRenderer, didChangeVideoSize size: CGSize) { onSize?(size) }
    }
}
#endif

// MARK: - Toene (Freizeichen beim Anrufer, Klingeln ohne CallKit)

@MainActor
final class CallTones {
    static let shared = CallTones()
    private var player: AVAudioPlayer?
    enum Kind { case ringback, ring }
    func play(_ k: Kind) {
        stop()
        guard !CallEnv.fakeMedia else { return }                     // Tests: still
        guard let p = try? AVAudioPlayer(data: Self.wav(k)) else { return }
        p.numberOfLoops = -1; p.volume = k == .ring ? 0.8 : 0.35
        p.play(); player = p
    }
    func stop() { player?.stop(); player = nil }
    /// 16-bit-Mono-WAV: Freizeichen 425 Hz 1 s an / 3 s aus, Klingeln zwei kurze Doppeltoene je 3 s
    private static func wav(_ k: Kind) -> Data {
        let rate = 16_000, total = k == .ringback ? 4 * rate : 3 * rate
        var s = [Int16](repeating: 0, count: total)
        for i in 0..<total {
            let t = Double(i) / Double(rate)
            var a = 0.0
            switch k {
            case .ringback: if t < 1 { a = sin(2 * .pi * 425 * t) * 0.5 }
            case .ring: if t < 0.4 || (t > 0.6 && t < 1.0) { a = (sin(2 * .pi * 880 * t) + sin(2 * .pi * 660 * t)) * 0.3 }
            }
            s[i] = Int16(max(-1, min(1, a)) * 30000)
        }
        var d = Data()
        func u32(_ v: Int) { var x = UInt32(v).littleEndian; d.append(Data(bytes: &x, count: 4)) }
        func u16(_ v: Int) { var x = UInt16(v).littleEndian; d.append(Data(bytes: &x, count: 2)) }
        d.append(Data("RIFF".utf8)); u32(36 + total * 2); d.append(Data("WAVEfmt ".utf8)); u32(16); u16(1); u16(1); u32(rate); u32(rate * 2); u16(2); u16(16)
        d.append(Data("data".utf8)); u32(total * 2)
        s.withUnsafeBufferPointer { d.append(Data(buffer: $0)) }
        return d
    }
}
