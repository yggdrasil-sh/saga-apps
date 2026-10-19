import SwiftUI
import WebRTC
#if os(iOS)
import AVKit

// Bild-im-Bild fuer Videoanrufe (iPhone/iPad, Saga 2.4, Konzept §3.2): AVPictureInPictureController mit
// AVPictureInPictureVideoCallViewController; darin ein AVSampleBufferDisplayLayer, gefuellt von einem eigenen
// RTCVideoRenderer (Hardware-Bilder als CVPixelBuffer ohne Kopie, siehe PiPBuffer.swift).
// Start: beim Verkleinern (Leiste aus 2.3.1) bzw. automatisch beim Wechsel in den Hintergrund; Ende beim
// Zurueckkehren. Waehrenddessen bekommt das Gegenueber state.want.video = "small" (CallManager.pipChanged).
// Im PiP nur Video und Name, keine Chat-Inhalte (§3.4).

/// Fernbild -> Sample-Buffer-Layer; laeuft auf dem Decoder-Thread von WebRTC
final class PiPRenderer: NSObject, RTCVideoRenderer, @unchecked Sendable {
    private let layer: AVSampleBufferDisplayLayer
    private let maker = PiPSampleMaker()
    private let lock = NSLock()
    private var feeding = false
    private var shape = (w: Int32(0), h: Int32(0), rot: RTCVideoRotation._0)
    /// Groesse/Drehung geaendert (auf dem Hauptthread gemeldet)
    var onShape: (@MainActor (CGSize, RTCVideoRotation) -> Void)?

    init(layer: AVSampleBufferDisplayLayer) { self.layer = layer }

    /// Nur waehrend PiP (bzw. kurz davor) Bilder einreihen - sonst kostet der Layer Akku ohne Nutzen
    func setFeeding(_ on: Bool) {
        lock.lock(); let was = feeding; feeding = on; lock.unlock()
        if was && !on { layer.sampleBufferRenderer.flush() }
    }
    func setSize(_ size: CGSize) {}
    func renderFrame(_ frame: RTCVideoFrame?) {
        guard let frame else { return }
        lock.lock(); let on = feeding; lock.unlock()
        guard on else { return }
        let pb: CVPixelBuffer?
        if let b = frame.buffer as? RTCCVPixelBuffer, b.cropX == 0, b.cropY == 0,
           Int(b.cropWidth) == CVPixelBufferGetWidth(b.pixelBuffer), Int(b.cropHeight) == CVPixelBufferGetHeight(b.pixelBuffer) {
            pb = b.pixelBuffer                                              // Hardware-Decoder: durchreichen, keine Kopie
        } else {
            let i = frame.buffer.toI420()                                   // Software-Decoder/Testbild: NV12 aus dem Pool
            pb = maker.nv12(width: Int(i.width), height: Int(i.height), y: i.dataY, strideY: Int(i.strideY),
                            u: i.dataU, strideU: Int(i.strideU), v: i.dataV, strideV: Int(i.strideV))
        }
        guard let pb, let sb = maker.wrap(pb, ptsNs: frame.timeStampNs) else { return }
        let w = Int32(CVPixelBufferGetWidth(pb)), h = Int32(CVPixelBufferGetHeight(pb))
        lock.lock()
        let changed = shape.w != w || shape.h != h || shape.rot != frame.rotation
        if changed { shape = (w, h, frame.rotation) }
        lock.unlock()
        if changed, let cb = onShape {
            let r = frame.rotation, size = CGSize(width: Int(w), height: Int(h))
            DispatchQueue.main.async { MainActor.assumeIsolated { cb(size, r) } }
        }
        let r = layer.sampleBufferRenderer
        if r.status == .failed || r.requiresFlushToResumeDecoding { r.flush() }
        r.enqueue(sb)
    }
}

/// Inhalt des PiP-Fensters: Fernbild (gedreht wie gesendet) und Name
final class PiPVideoView: UIView {
    let display = PiPDisplayView()
    let name = UILabel()
    var rotation: RTCVideoRotation = ._0 { didSet { setNeedsLayout() } }
    var showsVideo = true { didSet { display.isHidden = !showsVideo; setNeedsLayout() } }
    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .black
        display.displayLayer.videoGravity = .resizeAspectFill
        addSubview(display)
        name.textColor = .white; name.font = .preferredFont(forTextStyle: .headline); name.textAlignment = .center
        name.adjustsFontSizeToFitWidth = true; name.minimumScaleFactor = 0.6
        name.layer.shadowColor = UIColor.black.cgColor; name.layer.shadowOpacity = 0.8; name.layer.shadowRadius = 3; name.layer.shadowOffset = .zero
        addSubview(name)
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) not used") }
    override func layoutSubviews() {
        super.layoutSubviews()
        let b = bounds, quarter = rotation == ._90 || rotation == ._270
        display.transform = .identity
        display.bounds = CGRect(x: 0, y: 0, width: quarter ? b.height : b.width, height: quarter ? b.width : b.height)
        display.center = CGPoint(x: b.midX, y: b.midY)
        let angle: CGFloat = rotation == ._90 ? .pi / 2 : rotation == ._180 ? .pi : rotation == ._270 ? -.pi / 2 : 0
        display.transform = CGAffineTransform(rotationAngle: angle)
        let h: CGFloat = 28
        name.frame = showsVideo ? CGRect(x: 8, y: b.height - h - 6, width: b.width - 16, height: h)
                                : CGRect(x: 8, y: (b.height - h) / 2, width: b.width - 16, height: h)
    }
}
final class PiPDisplayView: UIView {
    override class var layerClass: AnyClass { AVSampleBufferDisplayLayer.self }
    var displayLayer: AVSampleBufferDisplayLayer { layer as! AVSampleBufferDisplayLayer }
}

@MainActor
final class CallPiP: NSObject, AVPictureInPictureControllerDelegate {
    static let shared = CallPiP()
    private weak var calls: CallManager?
    private weak var source: UIView?
    private var controller: AVPictureInPictureController?
    private var callVC: AVPictureInPictureVideoCallViewController?
    private let video = PiPVideoView(frame: CGRect(x: 0, y: 0, width: 360, height: 640))
    private lazy var renderer: PiPRenderer = {
        let r = PiPRenderer(layer: video.display.displayLayer)
        r.onShape = { [weak self] size, rot in self?.shape(size, rot) }
        return r
    }()
    private weak var track: RTCVideoTrack?
    private var possibleObs: NSKeyValueObservation?
    private(set) var active = false
    private var background = false
    private var wanted = false
    /// PiP-Fenster selbst geschlossen, waehrend der Anruf verkleinert ist: nicht sofort wieder oeffnen
    private var suppressed = false

    /// Unsichtbare Quellansicht hinter der App (Animation ins/aus dem PiP); von PiPSourceHost gesetzt
    func setSource(_ v: UIView) {
        guard source !== v else { return }
        source = v
        tearDown()
        if let c = calls { update(c) }
    }
    /// Szene aktiv/inaktiv (Hintergrund): automatischer Start uebernimmt das System, hier nur die Bildzufuhr
    func sceneActive(_ on: Bool) {
        background = !on
        if let c = calls { update(c) }
    }

    /// Zustand des Anrufs geaendert (Phase, Fernbild, verkleinert, Kamera des Gegenuebers)
    func update(_ calls: CallManager) {
        self.calls = calls
        guard AVPictureInPictureController.isPictureInPictureSupported() else { return }
        let avail = PiPLogic.available(phase: calls.phase.rawValue, hasRemoteVideo: calls.remoteVideo != nil)
        guard avail, let src = source, src.window != nil else {
            if active { controller?.stopPictureInPicture() }
            controller?.canStartPictureInPictureAutomaticallyFromInline = false
            attach(nil); renderer.setFeeding(false); wanted = false
            if !avail { tearDown() }
            return
        }
        let c = ensureController(src)
        attach(calls.remoteVideo)
        video.name.text = calls.peerName
        video.showsVideo = calls.remote.cam || calls.remote.screen
        c.canStartPictureInPictureAutomaticallyFromInline = true          // Wechsel in den Hintergrund
        wanted = PiPLogic.iosWantsPiP(phase: calls.phase.rawValue, hasRemoteVideo: true, minimized: calls.minimized, background: background)
        if !calls.minimized { suppressed = false }
        renderer.setFeeding(active || (wanted && !suppressed) || background)
        if calls.minimized && !active && !suppressed { startIfPossible() }               // Verkleinern (Leiste): PiP ueber der App
        if !calls.minimized && !background && active { c.stopPictureInPicture() }   // zurueck im Vollbild
    }
    /// Anrufende: alles abbauen
    func stop() {
        if active { controller?.stopPictureInPicture() }
        attach(nil); renderer.setFeeding(false); tearDown()
    }

    private func ensureController(_ src: UIView) -> AVPictureInPictureController {
        if let c = controller { return c }
        let vc = AVPictureInPictureVideoCallViewController()
        vc.preferredContentSize = CGSize(width: 9, height: 16)
        video.frame = vc.view.bounds
        video.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        vc.view.addSubview(video)
        let c = AVPictureInPictureController(contentSource: .init(activeVideoCallSourceView: src, contentViewController: vc))
        c.delegate = self
        possibleObs = c.observe(\.isPictureInPicturePossible, options: [.new]) { [weak self] _, ch in
            guard ch.newValue == true else { return }
            DispatchQueue.main.async { MainActor.assumeIsolated { self?.startIfPossible() } }
        }
        controller = c; callVC = vc
        return c
    }
    private func startIfPossible() {
        guard let c = controller, let calls, calls.minimized, !active, !suppressed, c.isPictureInPicturePossible else { return }
        renderer.setFeeding(true)
        c.startPictureInPicture()
    }
    private func tearDown() {
        possibleObs?.invalidate(); possibleObs = nil
        controller?.delegate = nil; controller = nil
        video.removeFromSuperview(); callVC = nil
        if active { active = false; calls?.pipChanged(false) }
    }
    private func attach(_ t: RTCVideoTrack?) {
        guard t !== track else { return }
        track?.remove(renderer); track = t; t?.add(renderer)
    }
    private func shape(_ size: CGSize, _ rot: RTCVideoRotation) {
        video.rotation = rot
        let quarter = rot == ._90 || rot == ._270
        let w = quarter ? size.height : size.width, h = quarter ? size.width : size.height
        if w > 0, h > 0 { callVC?.preferredContentSize = CGSize(width: w, height: h) }
    }

    // MARK: AVPictureInPictureControllerDelegate (Hauptthread)

    nonisolated func pictureInPictureControllerWillStartPictureInPicture(_ c: AVPictureInPictureController) {
        MainActor.assumeIsolated {
            active = true; renderer.setFeeding(true)
            calls?.pipChanged(true)
        }
    }
    nonisolated func pictureInPictureControllerDidStopPictureInPicture(_ c: AVPictureInPictureController) {
        MainActor.assumeIsolated {
            active = false
            if calls?.minimized == true { suppressed = true }               // selbst geschlossen: verkleinert bleiben, ohne PiP
            calls?.pipChanged(false)
            if let calls { update(calls) }
        }
    }
    nonisolated func pictureInPictureController(_ c: AVPictureInPictureController, failedToStartPictureInPictureWithError error: Error) {
        MainActor.assumeIsolated { CallEnv.log("pip failed: \(error.localizedDescription)"); active = false; calls?.pipChanged(false) }
    }
    /// "Zurueck" im PiP-Fenster: Anruf wieder im Vollbild
    nonisolated func pictureInPictureController(_ c: AVPictureInPictureController,
                                                restoreUserInterfaceForPictureInPictureStopWithCompletionHandler done: @escaping (Bool) -> Void) {
        MainActor.assumeIsolated { calls?.expand() }
        done(true)
    }
}

/// Unsichtbare Quellansicht fuer PiP (liegt hinter dem App-Inhalt, solange die App laeuft)
struct PiPSourceHost: UIViewRepresentable {
    func makeUIView(context: Context) -> UIView {
        let v = UIView(); v.backgroundColor = .clear; v.isUserInteractionEnabled = false
        v.accessibilityElementsHidden = true
        DispatchQueue.main.async { CallPiP.shared.setSource(v) }
        return v
    }
    func updateUIView(_ v: UIView, context: Context) {}
}
#endif
