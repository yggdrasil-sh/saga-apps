import SwiftUI
import WebRTC
#if os(macOS)
import ScreenCaptureKit
#else
import ReplayKit
#endif

// Bildschirm teilen (Saga 2.4, E3): Aufnahme je Plattform; Ablauf, Betriebsart A/B und Signale: Calls.swift,
// Logik: ScreenShareLogic.swift. Bilder gehen als RTCVideoFrame an eine RTCVideoSource (forScreenCast = Detail/Text).
//
// macOS: ScreenCaptureKit. Auswahl Bildschirm/Fenster/App ueber SCContentSharingPicker (Freigabe je Sitzung, keine
//   dauerhafte Bildschirmaufnahme-Berechtigung; ab macOS 14 - die App braucht ohnehin macOS 26, ein eigener Picker
//   ist daher nicht noetig). Saga selbst ist ausgeschlossen. SCStream liefert NV12-CVPixelBuffer, die ohne Kopie als
//   RTCCVPixelBuffer weitergehen; 15 fps (ueber Relais TCP 8), lange Kante <= 1920.
// iPhone/iPad: ReplayKit-Erweiterung (Broadcast/SampleHandler.swift) schickt NV12-Bilder ueber einen Unix-Socket im
//   Container der App-Gruppe; die App lauscht nur waehrend eines verbundenen Anrufs und reicht sie weiter.
// Systemton: wird NICHT uebertragen. Laut Vertrag muesste er in die vorhandene Tonspur gemischt werden; die verwendete
//   WebRTC-Fassung bot bis 2.4 (stasel/WebRTC) am Mac kein eigenes Audio-Geraet (RTCAudioDevice nur iOS) und am iPhone
//   liefe der Ton der Erweiterung ueber denselben Socket in ein eigenes Audio-Geraet - beides zu riskant fuer E3.
//   Daher screen_audio = false; das Mikrofon bleibt wie es ist. Seit 2.5 (webrtc-sdk) gibt es am Mac RTCAudioDevice,
//   RTCAudioDeviceModule und RTCDesktopCapturer - damit liesse sich die Luecke spaeter schliessen (nicht in diesem Schritt).

/// Empfaenger der Bilder (thread-sicher; Bilder kommen auf Hintergrund-Threads)
final class ScreenSink: @unchecked Sendable {
    private let lock = NSLock()
    private var source: RTCVideoSource?
    private let capturer = RTCVideoCapturer()
    func set(_ s: RTCVideoSource?) { lock.lock(); source = s; lock.unlock() }
    func deliver(_ pb: CVPixelBuffer, rotation: RTCVideoRotation) {
        lock.lock(); let s = source; lock.unlock()
        guard let s else { return }
        let f = RTCVideoFrame(buffer: RTCCVPixelBuffer(pixelBuffer: pb), rotation: rotation,
                              timeStampNs: Int64(DispatchTime.now().uptimeNanoseconds))
        s.capturer(capturer, didCapture: f)
    }
}

#if os(macOS)
/// Mac: Auswahl per SCContentSharingPicker, Aufnahme per SCStream
@MainActor
final class ScreenCapture: NSObject {
    let sink = ScreenSink()
    /// Aufnahme laeuft (erste Auswahl getroffen) bzw. beendet (System-Menue "Teilen beenden", Fehler)
    var onStart: (() -> Void)?
    var onStop: (() -> Void)?
    var onError: ((String) -> Void)?
    private var stream: SCStream?
    private let output = Output()
    private var fps = 15
    private var filter: SCContentFilter?
    private var observing = false

    /// Auswahlfenster des Systems zeigen (Saga selbst ist nicht waehlbar)
    func pick(fps f: Int) {
        fps = f
        let p = SCContentSharingPicker.shared
        var c = SCContentSharingPickerConfiguration()
        c.allowedPickerModes = [.singleDisplay, .singleWindow, .singleApplication]
        c.allowsChangingSelectedContent = true
        if let b = Bundle.main.bundleIdentifier { c.excludedBundleIDs = [b] }
        p.defaultConfiguration = c
        p.maximumStreamCount = 1
        if !observing { p.add(self); observing = true }
        p.isActive = true
        p.present()
    }
    /// Bildrate je Weg (15 bzw. 8 ueber Relais TCP) - nur bei Aenderung
    func setFps(_ f: Int) {
        guard f != fps else { return }
        fps = f
        guard let stream, let filter else { return }
        stream.updateConfiguration(config(filter)) { _ in }
    }
    func stop() {
        let s = stream; stream = nil; filter = nil
        output.sink = nil
        s?.stopCapture { _ in }
        guard observing else { return }                                     // Auswahl nie benutzt: System nicht anfassen
        let p = SCContentSharingPicker.shared
        p.remove(self); observing = false
        p.isActive = false
    }

    private func config(_ f: SCContentFilter) -> SCStreamConfiguration {
        let c = SCStreamConfiguration()
        let r = f.contentRect, sz = ScreenShare.captureSize(width: r.width, height: r.height, scale: Double(f.pointPixelScale))
        c.width = sz.width; c.height = sz.height
        c.minimumFrameInterval = CMTime(value: 1, timescale: CMTimeScale(fps))
        c.pixelFormat = kCVPixelFormatType_420YpCbCr8BiPlanarFullRange      // NV12 wie der Hardware-Encoder
        c.queueDepth = 4
        c.showsCursor = true
        c.scalesToFit = true
        c.preservesAspectRatio = true
        c.capturesAudio = false                                             // Systemton: siehe oben
        return c
    }
    /// Ganzer Bildschirm: Saga-Fenster ausschliessen (currentProcess selbst braucht keine Freigabe, der eigene Filter schon)
    /// - nur mit dauerhafter Freigabe, sonst bleibt es beim Filter der Auswahl (ScreenShare.excludeSelf)
    private func withoutSelf(_ f: SCContentFilter) async -> SCContentFilter? {
        guard ScreenShare.excludeSelf(display: f.style == .display, hasAccess: CGPreflightScreenCaptureAccess()),
              let d = f.includedDisplays.first,
              let own = try? await SCShareableContent.currentProcess, !own.applications.isEmpty else { return nil }
        return SCContentFilter(display: d, excludingApplications: own.applications, exceptingWindows: [])
    }
    fileprivate func picked(_ f: SCContentFilter, for s: SCStream?) {
        if let stream, s == nil || s === stream {                           // Auswahl geaendert
            filter = f
            stream.updateContentFilter(f) { _ in }
            stream.updateConfiguration(config(f)) { _ in }
            return
        }
        guard stream == nil else { return }
        Task { await start(f) }
    }
    private func start(_ picked: SCContentFilter) async {
        output.sink = sink
        for f in [await withoutSelf(picked), picked].compactMap({ $0 }) {   // ohne Saga; geht das nicht: wie gewaehlt
            let s = SCStream(filter: f, configuration: config(f), delegate: output)
            do {
                try s.addStreamOutput(output, type: .screen, sampleHandlerQueue: output.queue)
                try await s.startCapture()
                stream = s; filter = f
                if ScreenShare.pickerConfigPerStream(hasAccess: CGPreflightScreenCaptureAccess()) {
                    SCContentSharingPicker.shared.setConfiguration(SCContentSharingPicker.shared.defaultConfiguration, for: s)
                }
                output.stopped = { [weak self] in Task { @MainActor in self?.ended() } }
                onStart?()
                return
            } catch { CallEnv.log("screen start: \(error.localizedDescription)") }
        }
        output.sink = nil
        onError?(String(localized: "Bildschirm teilen nicht möglich."))
        stop()
    }
    private func ended() {
        guard stream != nil else { return }
        stop()
        onStop?()
    }

    /// SCStream-Ausgabe auf eigener Warteschlange: nur vollstaendige Bilder, CVPixelBuffer ohne Kopie weiter
    final class Output: NSObject, SCStreamOutput, SCStreamDelegate, @unchecked Sendable {
        let queue = DispatchQueue(label: "saga.screen.capture", qos: .userInteractive)
        var sink: ScreenSink?
        var stopped: (() -> Void)?
        func stream(_ stream: SCStream, didOutputSampleBuffer sb: CMSampleBuffer, of type: SCStreamOutputType) {
            guard type == .screen, sb.isValid, let pb = sb.imageBuffer,
                  let att = (CMSampleBufferGetSampleAttachmentsArray(sb, createIfNecessary: false) as? [[SCStreamFrameInfo: Any]])?.first,
                  let raw = att[.status] as? Int, SCFrameStatus(rawValue: raw) == .complete else { return }
            sink?.deliver(pb, rotation: ._0)
        }
        func stream(_ stream: SCStream, didStopWithError error: Error) { stopped?() }
    }
}

extension ScreenCapture: SCContentSharingPickerObserver {
    nonisolated func contentSharingPicker(_ picker: SCContentSharingPicker, didCancelFor stream: SCStream?) {}
    nonisolated func contentSharingPicker(_ picker: SCContentSharingPicker, didUpdateWith filter: SCContentFilter, for stream: SCStream?) {
        Task { @MainActor in self.picked(filter, for: stream) }
    }
    nonisolated func contentSharingPickerStartDidFailWithError(_ error: Error) {
        Task { @MainActor in self.onError?(String(localized: "Bildschirm teilen nicht möglich.")) }
    }
}

#else

/// iPhone/iPad: Bilder der ReplayKit-Erweiterung ueber den Unix-Socket annehmen
@MainActor
final class ScreenCapture {
    let sink = ScreenSink()
    var onStart: (() -> Void)?
    var onStop: (() -> Void)?
    var onError: ((String) -> Void)?
    private let server = ScreenSocketServer()
    /// Systemauswahl "Bildschirm uebertragen" (vorausgewaehlt: die Saga-Erweiterung, ohne Mikrofon-Knopf)
    private var picker: RPSystemBroadcastPickerView?

    init() {
        server.sink = sink
        server.connected = { [weak self] in Task { @MainActor in self?.onStart?() } }
        server.closed = { [weak self] in Task { @MainActor in self?.onStop?() } }
    }
    /// Waehrend eines verbundenen Anrufs lauschen (auch fuer einen Start ueber das Kontrollzentrum)
    func listen() { server.listen() }
    func close() { server.close() }
    /// Unsichtbare Systemauswahl in die Anruf-Oberflaeche haengen (BroadcastPickerHost)
    func attach(_ p: RPSystemBroadcastPickerView) { picker = p }
    func pick(fps: Int) {
        guard let p = picker, let b = Self.button(in: p) else {
            onError?(String(localized: "Bildschirm teilen nicht möglich.")); return
        }
        b.sendActions(for: .allTouchEvents)
    }
    /// Knopf der Systemauswahl (auch tiefer verschachtelt, falls das System den Aufbau aendert)
    private static func button(in v: UIView) -> UIButton? {
        for s in v.subviews {
            if let b = s as? UIButton { return b }
            if let b = button(in: s) { return b }
        }
        return nil
    }
    func setFps(_ f: Int) {}                                                // Bildrate legt die Erweiterung fest (10/5)
    /// Teilen beenden: Verbindung trennen - die Erweiterung beendet die Uebertragung dann selbst
    func stop() { server.dropClient() }
    static var extensionID: String { (Bundle.main.bundleIdentifier ?? "") + ".broadcast" }
}

/// Unsichtbare RPSystemBroadcastPickerView (der eigentliche Knopf ist "Bildschirm teilen" im Anruf)
struct BroadcastPickerHost: UIViewRepresentable {
    let capture: ScreenCapture
    func makeUIView(context: Context) -> RPSystemBroadcastPickerView {
        let v = RPSystemBroadcastPickerView(frame: CGRect(x: 0, y: 0, width: 1, height: 1))
        v.preferredExtension = ScreenCapture.extensionID
        v.showsMicrophoneButton = false
        v.alpha = 0.011
        v.accessibilityElementsHidden = true
        capture.attach(v)
        return v
    }
    func updateUIView(_ v: RPSystemBroadcastPickerView, context: Context) { capture.attach(v) }
}

/// Unix-Socket im Container der App-Gruppe; je Verbindung ein Lese-Thread, Bilder in CVPixelBuffer aus einem Pool
final class ScreenSocketServer: @unchecked Sendable {
    var sink: ScreenSink?
    var connected: (() -> Void)?
    var closed: (() -> Void)?
    private let lock = NSLock()
    private var listenFD: Int32 = -1
    private var clientFD: Int32 = -1
    private var acceptSrc: DispatchSourceRead?
    private let queue = DispatchQueue(label: "saga.screen.socket")
    private var path: String?

    func listen() {
        lock.lock(); defer { lock.unlock() }
        guard listenFD < 0, let dir = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: AppConfig.appGroup),
              let p = ScreenFrame.socketPath(container: dir), let s = ScreenSocket.listen(path: p) else { return }
        listenFD = s; path = p
        let src = DispatchSource.makeReadSource(fileDescriptor: s, queue: queue)
        src.setEventHandler { [weak self] in self?.accept() }
        src.setCancelHandler { Darwin.close(s) }
        acceptSrc = src
        src.resume()
    }
    func close() {
        lock.lock()
        acceptSrc?.cancel(); acceptSrc = nil; listenFD = -1
        if let p = path { unlink(p) }; path = nil
        lock.unlock()
        dropClient()
    }
    func dropClient() {
        lock.lock(); let c = clientFD; lock.unlock()
        if c >= 0 { shutdown(c, SHUT_RDWR) }                                // Lese-Thread endet, meldet closed
    }
    private func accept() {
        lock.lock(); let l = listenFD, busy = clientFD >= 0; lock.unlock()
        guard let c = ScreenSocket.accept(l) else { return }               // blockierend (erbt sonst O_NONBLOCK)
        if busy { Darwin.close(c); return }                                 // nur eine Uebertragung zugleich
        lock.lock(); clientFD = c; lock.unlock()
        connected?()
        Thread.detachNewThread { [weak self] in self?.readLoop(c) }
    }
    private func readAll(_ c: Int32, _ p: UnsafeMutableRawPointer, _ n: Int) -> Bool { ScreenSocket.readAll(c, p, n) }
    private func readLoop(_ c: Int32) {
        var pool: CVPixelBufferPool?
        var pw = 0, ph = 0
        var head = [UInt8](repeating: 0, count: ScreenFrame.headerSize)
        while true {
            guard head.withUnsafeMutableBytes({ readAll(c, $0.baseAddress!, $0.count) }), let h = ScreenFrame.decode(head) else { break }
            if pool == nil || pw != h.width || ph != h.height {
                pool = nil
                let attrs: [CFString: Any] = [kCVPixelBufferPixelFormatTypeKey: kCVPixelFormatType_420YpCbCr8BiPlanarFullRange,
                                              kCVPixelBufferWidthKey: h.width, kCVPixelBufferHeightKey: h.height,
                                              kCVPixelBufferIOSurfacePropertiesKey: [:] as [CFString: Any]]
                CVPixelBufferPoolCreate(nil, nil, attrs as CFDictionary, &pool)
                pw = h.width; ph = h.height
            }
            var pb: CVPixelBuffer?
            guard let pool, CVPixelBufferPoolCreatePixelBuffer(nil, pool, &pb) == kCVReturnSuccess, let pb else { break }
            CVPixelBufferLockBaseAddress(pb, [])
            var ok = true
            for plane in 0..<2 where ok {
                guard let base = CVPixelBufferGetBaseAddressOfPlane(pb, plane) else { ok = false; break }
                let stride = CVPixelBufferGetBytesPerRowOfPlane(pb, plane), rows = plane == 0 ? h.height : h.height / 2
                if stride == h.width { ok = readAll(c, base, h.width * rows); continue }
                for r in 0..<rows where ok { ok = readAll(c, base + r * stride, h.width) }
            }
            CVPixelBufferUnlockBaseAddress(pb, [])
            guard ok else { break }
            let rot: RTCVideoRotation = h.rotation == 90 ? ._90 : h.rotation == 180 ? ._180 : h.rotation == 270 ? ._270 : ._0
            sink?.deliver(pb, rotation: rot)
        }
        lock.lock(); if clientFD == c { clientFD = -1 }; lock.unlock()
        Darwin.close(c)
        closed?()
    }
}
#endif
