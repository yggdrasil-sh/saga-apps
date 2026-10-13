#if os(iOS)
import AVFoundation
import SwiftUI
import UIKit

/// QR-Code scannen (Anmeldung per Kopplungs-Code aus dem Web). Kamera nur waehrend das Blatt offen ist.
struct QRScannerSheet: View {
    var onCode: (String) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var denied = false

    var body: some View {
        NavigationStack {
            ZStack {
                Color.black.ignoresSafeArea()
                if denied {
                    VStack(spacing: 12) {
                        Image(systemName: "camera.fill").font(.largeTitle)
                        Text("Kein Zugriff auf die Kamera").font(.headline)
                        Text("In den iOS-Einstellungen → Saga → Kamera erlauben.").multilineTextAlignment(.center).foregroundStyle(.secondary)
                    }.foregroundStyle(.white).padding()
                } else {
                    QRCameraView(onCode: onCode).ignoresSafeArea()
                    RoundedRectangle(cornerRadius: 24).strokeBorder(.white.opacity(0.85), lineWidth: 3)
                        .frame(width: 250, height: 250)
                    VStack {
                        Spacer()
                        Text("QR-Code aus Saga im Browser scannen\n👤 → Einstellungen → „Apps & Geräte“")
                            .multilineTextAlignment(.center).font(.callout).foregroundStyle(.white)
                            .padding(12).background(.black.opacity(0.55), in: RoundedRectangle(cornerRadius: 12)).padding(.bottom, 40)
                    }
                }
            }
            .navigationTitle("QR-Code scannen").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Abbrechen") { dismiss() } } }
            .task {
                switch AVCaptureDevice.authorizationStatus(for: .video) {
                case .authorized: break
                case .notDetermined: denied = !(await AVCaptureDevice.requestAccess(for: .video))
                default: denied = true
                }
            }
        }
    }
}

private struct QRCameraView: UIViewControllerRepresentable {
    var onCode: (String) -> Void
    func makeUIViewController(context: Context) -> QRCameraController { let c = QRCameraController(); c.onCode = onCode; return c }
    func updateUIViewController(_ c: QRCameraController, context: Context) {}
}

final class QRCameraController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
    var onCode: ((String) -> Void)?
    private let session = AVCaptureSession()
    private var preview: AVCaptureVideoPreviewLayer?
    private var done = false

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        guard let dev = AVCaptureDevice.default(for: .video), let input = try? AVCaptureDeviceInput(device: dev),
              session.canAddInput(input) else { return }
        session.addInput(input)
        let out = AVCaptureMetadataOutput()
        guard session.canAddOutput(out) else { return }
        session.addOutput(out)
        out.setMetadataObjectsDelegate(self, queue: .main)
        out.metadataObjectTypes = [.qr]
        let p = AVCaptureVideoPreviewLayer(session: session)
        p.videoGravity = .resizeAspectFill
        view.layer.addSublayer(p)
        preview = p
    }
    override func viewDidLayoutSubviews() { super.viewDidLayoutSubviews(); preview?.frame = view.bounds }
    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        let s = session
        DispatchQueue.global(qos: .userInitiated).async { if !s.isRunning { s.startRunning() } }
    }
    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        let s = session
        DispatchQueue.global(qos: .userInitiated).async { if s.isRunning { s.stopRunning() } }
    }
    func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput objs: [AVMetadataObject], from connection: AVCaptureConnection) {
        guard !done, let v = (objs.first as? AVMetadataMachineReadableCodeObject)?.stringValue,
              Store.parsePairLink(v) != nil else { return }                 // nur Saga-Kopplungscodes
        done = true
        UINotificationFeedbackGenerator().notificationOccurred(.success)
        onCode?(v)
    }
}
#endif
