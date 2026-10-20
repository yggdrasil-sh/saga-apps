import ReplayKit
import VideoToolbox
import Darwin

// Bildschirm teilen (iPhone/iPad, Saga 2.4 E3): ReplayKit-Erweiterung "Broadcast Upload". Bekommt die Bilder des ganzen
// Bildschirms vom System, verkleinert sie (lange Kante <= 1920, heiss 1280) per VideoToolbox in einen Puffer aus einem
// kleinen Pool und schickt sie ueber einen Unix-Socket im Container der App-Gruppe an die Saga-App (ScreenCapture.swift),
// die sie in den Anruf speist. Keine WebRTC-Bibliothek hier, keine Warteschlange: Speichergrenze der Erweiterung ca.
// 50 MB, Ziel <= 35 MB - ist das vorige Bild noch unterwegs, wird das neue verworfen. Ton wird nicht uebertragen.

final class SampleHandler: RPBroadcastSampleHandler {
    private let queue = DispatchQueue(label: "saga.broadcast.send", qos: .userInitiated)
    private let lock = NSLock()
    private var busy = false
    private var fd: Int32 = -1
    private var lastNs: UInt64?
    private var transfer: VTPixelTransferSession?
    private var pool: CVPixelBufferPool?
    private var poolW = 0, poolH = 0
    private var ended = false

    override func broadcastStarted(withSetupInfo setupInfo: [String: NSObject]?) {
        guard connect() else {
            end(String(localized: "Bildschirm teilen geht nur während eines Anrufs in Saga."))
            return
        }
        VTPixelTransferSessionCreate(allocator: nil, pixelTransferSessionOut: &transfer)
        if let t = transfer { VTSessionSetProperty(t, key: kVTPixelTransferPropertyKey_ScalingMode, value: kVTScalingMode_Normal) }
    }
    override func broadcastFinished() { close() }
    override func broadcastPaused() {}
    override func broadcastResumed() {}

    override func processSampleBuffer(_ sb: CMSampleBuffer, with type: RPSampleBufferType) {
        guard type == .video, let src = CMSampleBufferGetImageBuffer(sb) else { return }
        let hot = ProcessInfo.processInfo.thermalState.rawValue >= ProcessInfo.ThermalState.serious.rawValue
        let now = clock_gettime_nsec_np(CLOCK_UPTIME_RAW)
        lock.lock()
        if fd < 0 || busy || ended || !ScreenFrame.due(lastNs: lastNs, nowNs: now, fps: hot ? ScreenFrame.hotFps : ScreenFrame.fps) {
            lock.unlock(); return                                           // voriges Bild noch unterwegs bzw. zu frueh: verwerfen
        }
        busy = true; lastNs = now
        lock.unlock()
        let orient = (CMGetAttachment(sb, key: RPVideoSampleOrientationKey as CFString, attachmentModeOut: nil) as? NSNumber)?.uint32Value ?? 1
        let rot = ScreenFrame.rotation(orientation: orient)
        guard let out = scale(src, maxLong: hot ? ScreenFrame.hotLongEdge : ScreenFrame.maxLongEdge) else { release(); return }
        queue.async { [weak self] in
            guard let self else { return }
            let ok = self.send(out, rotation: rot, pts: now)
            self.release()
            if !ok { self.end(String(localized: "Teilen beendet.")) }
        }
    }

    private func release() { lock.lock(); busy = false; lock.unlock() }

    /// Verkleinern in einen Puffer aus dem eigenen Pool (nie mehr als ein Bild unterwegs -> hoechstens zwei Puffer)
    private func scale(_ src: CVPixelBuffer, maxLong: Int) -> CVPixelBuffer? {
        guard let transfer else { return nil }
        let (w, h) = ScreenFrame.scaled(width: CVPixelBufferGetWidth(src), height: CVPixelBufferGetHeight(src), maxLong: maxLong)
        if pool == nil || poolW != w || poolH != h {
            pool = nil
            let attrs: [CFString: Any] = [kCVPixelBufferPixelFormatTypeKey: kCVPixelFormatType_420YpCbCr8BiPlanarFullRange,
                                          kCVPixelBufferWidthKey: w, kCVPixelBufferHeightKey: h,
                                          kCVPixelBufferIOSurfacePropertiesKey: [:] as [CFString: Any]]
            CVPixelBufferPoolCreate(nil, [kCVPixelBufferPoolMinimumBufferCountKey: 1] as CFDictionary, attrs as CFDictionary, &pool)
            poolW = w; poolH = h
        }
        guard let pool else { return nil }
        var out: CVPixelBuffer?
        let aux = [kCVPixelBufferPoolAllocationThresholdKey: 2] as CFDictionary
        guard CVPixelBufferPoolCreatePixelBufferWithAuxAttributes(nil, pool, aux, &out) == kCVReturnSuccess, let out,
              VTPixelTransferSessionTransferImage(transfer, from: src, to: out) == noErr else { return nil }
        return out
    }

    // MARK: Socket

    private func connect() -> Bool {
        guard let group = Bundle.main.object(forInfoDictionaryKey: "SagaAppGroup") as? String, !group.isEmpty,
              let dir = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: group),
              let path = ScreenFrame.socketPath(container: dir) else { return false }
        let s = socket(AF_UNIX, SOCK_STREAM, 0)
        guard s >= 0 else { return false }
        var one: Int32 = 1
        setsockopt(s, SOL_SOCKET, SO_NOSIGPIPE, &one, socklen_t(MemoryLayout<Int32>.size))
        var tv = timeval(tv_sec: 2, tv_usec: 0)                               // App haengt: nicht ewig warten
        setsockopt(s, SOL_SOCKET, SO_SNDTIMEO, &tv, socklen_t(MemoryLayout<timeval>.size))
        var addr = sockaddr_un()
        addr.sun_family = sa_family_t(AF_UNIX)
        let bytes = Array(path.utf8)
        withUnsafeMutableBytes(of: &addr.sun_path) { p in for (i, c) in bytes.enumerated() where i < p.count - 1 { p[i] = c } }
        let r = withUnsafePointer(to: &addr) { $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { Darwin.connect(s, $0, socklen_t(MemoryLayout<sockaddr_un>.size)) } }
        guard r == 0 else { Darwin.close(s); return false }
        lock.lock(); fd = s; lock.unlock()
        return true
    }
    private func writeAll(_ s: Int32, _ p: UnsafeRawPointer, _ n: Int) -> Bool {
        var off = 0
        while off < n {
            let w = Darwin.write(s, p + off, n - off)
            if w < 0 && errno == EINTR { continue }
            if w <= 0 { return false }
            off += w
        }
        return true
    }
    /// Kopf + Y-Ebene + UV-Ebene (zeilenweise, ohne Auffuellbytes)
    private func send(_ pb: CVPixelBuffer, rotation: Int, pts: UInt64) -> Bool {
        lock.lock(); let s = fd; lock.unlock()
        guard s >= 0 else { return false }
        let w = CVPixelBufferGetWidth(pb), h = CVPixelBufferGetHeight(pb)
        let head = ScreenFrame.encode(.init(width: w, height: h, rotation: rotation, ptsNs: pts))
        guard head.withUnsafeBytes({ writeAll(s, $0.baseAddress!, $0.count) }) else { return false }
        CVPixelBufferLockBaseAddress(pb, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(pb, .readOnly) }
        for plane in 0..<2 {
            guard let base = CVPixelBufferGetBaseAddressOfPlane(pb, plane) else { return false }
            let stride = CVPixelBufferGetBytesPerRowOfPlane(pb, plane), rows = plane == 0 ? h : h / 2
            if stride == w { if !writeAll(s, base, w * rows) { return false }; continue }
            for r in 0..<rows where !writeAll(s, base + r * stride, w) { return false }
        }
        return true
    }
    private func close() {
        lock.lock(); let s = fd; fd = -1; lock.unlock()
        if s >= 0 { Darwin.close(s) }
        queue.async { [weak self] in
            if let t = self?.transfer { VTPixelTransferSessionInvalidate(t) }
            self?.transfer = nil; self?.pool = nil
        }
    }
    /// Teilen beenden (App hat getrennt bzw. kein Anruf): die Meldung zeigt das System
    private func end(_ msg: String) {
        lock.lock(); let was = ended; ended = true; lock.unlock()
        guard !was else { return }
        close()
        finishBroadcastWithError(NSError(domain: "saga.broadcast", code: 1, userInfo: [NSLocalizedDescriptionKey: msg]))
    }
}
