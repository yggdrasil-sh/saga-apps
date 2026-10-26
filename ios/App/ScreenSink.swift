import Foundation
import WebRTC

// Bildschirm teilen: Empfaenger der Bilder der Aufnahme (Mac: SCStream, iPhone/iPad: ReplayKit-Erweiterung ueber den
// Unix-Socket) vor der RTCVideoSource. Ausgelagert aus ScreenCapture.swift, damit der Knotentest (HornNodeTests) genau
// diesen Weg benutzt.
//
// Fehler 26.10. (iPhone teilt ueber den Knoten: Bild kam stark verzoegert an und blieb stehen): ReplayKit und
// ScreenCaptureKit liefern bei stehendem Bildschirm KEINE neuen Bilder. libwebrtc kodiert ein angefordertes
// Schluesselbild (PLI/FIR vom Knoten) aber erst mit dem naechsten Eingangsbild. Der Knoten fordert eines an, sobald ein
// Gegenueber den Bildschirm-Platz bekommt, und verwirft bis dahin alles; ebenso nach Stau (Warteschlange > 150 ms) oder
// Kontingent. Bei stehendem Bildschirm kam das Schluesselbild nie (bzw. erst bei der naechsten Aenderung) - das
// Gegenueber sah lange nichts oder ein stehendes Bild. Daher wiederholt der Empfaenger das letzte Bild, solange keine
// neuen kommen (ScreenFrame.repeatDue, alle 500 ms): dann gibt es immer ein Bild, an dem libwebrtc das Schluesselbild kodiert,
// und die Quelle bleibt am Knoten "aktiv". Gleiche Bilder kosten als Delta kaum Bitrate.

/// Empfaenger der Bilder (thread-sicher; Bilder kommen auf Hintergrund-Threads)
final class ScreenSink: @unchecked Sendable {
    private let lock = NSLock()
    /// Reihenfolge der Bilder an die Quelle (Aufnahme-Thread und Wiederholung): Zeitstempel bleiben aufsteigend
    private let pushLock = NSLock()
    private var source: RTCVideoSource?
    private let capturer = RTCVideoCapturer()
    private var last: (pb: CVPixelBuffer, rot: RTCVideoRotation)?
    private var lastNs: UInt64 = 0
    private var lastTs: Int64 = 0
    private var timer: DispatchSourceTimer?
    private let queue = DispatchQueue(label: "saga.screen.repeat", qos: .userInitiated)
    /// Abstand der Wiederholung in ms; nil = aus (nur Tests: Verhalten vor dem Fix)
    private let repeatMs: Int?
    /// wiederholte Bilder (Statistik/Tests)
    private(set) var repeats = 0

    init(repeatMs: Int? = ScreenFrame.repeatMs) { self.repeatMs = repeatMs }
    deinit { timer?.cancel() }

    func set(_ s: RTCVideoSource?) {
        lock.lock()
        source = s; last = nil
        let t = timer; timer = nil
        lock.unlock()
        t?.cancel()
        guard s != nil, let ms = repeatMs else { return }
        let n = DispatchSource.makeTimerSource(queue: queue)
        n.schedule(deadline: .now() + .milliseconds(ms / 2), repeating: .milliseconds(max(50, ms / 2)))
        n.setEventHandler { [weak self] in self?.tick() }
        lock.lock(); timer = n; lock.unlock()
        n.resume()
    }
    func deliver(_ pb: CVPixelBuffer, rotation: RTCVideoRotation) {
        lock.lock()
        let s = source
        if s != nil { last = (pb, rotation); lastNs = DispatchTime.now().uptimeNanoseconds }
        lock.unlock()
        guard let s else { return }
        push(s, pb, rotation)
    }
    /// Kein neues Bild seit repeatMs: das letzte noch einmal (neuer Zeitstempel)
    private func tick() {
        guard let ms = repeatMs else { return }
        let now = DispatchTime.now().uptimeNanoseconds
        lock.lock()
        guard let s = source, let l = last, ScreenFrame.repeatDue(lastNs: lastNs, nowNs: now, ms: ms) else { lock.unlock(); return }
        lastNs = now; repeats += 1
        lock.unlock()
        push(s, l.pb, l.rot)
    }
    private func push(_ s: RTCVideoSource, _ pb: CVPixelBuffer, _ rot: RTCVideoRotation) {
        pushLock.lock(); defer { pushLock.unlock() }
        let ts = ScreenFrame.nextTimestamp(now: Int64(DispatchTime.now().uptimeNanoseconds), last: lastTs)
        lastTs = ts
        s.capturer(capturer, didCapture: RTCVideoFrame(buffer: RTCCVPixelBuffer(pixelBuffer: pb), rotation: rot, timeStampNs: ts))
    }
}
