import SwiftUI
import Combine
import WebRTC
#if os(macOS)
import AppKit

// Bild-im-Bild am Mac (Saga 2.4, Konzept §3.2): kleines, schwebendes Fenster (NSPanel, .floating, auf allen Spaces,
// neben Vollbild-Apps) mit dem Fernbild und den Knoepfen Stumm/Auflegen/Zurueck. Erscheint, wenn das Fenster
// "Anruf" verkleinert (Dock) oder ganz verdeckt wird bzw. per Knopf "Bild im Bild"; verschwindet, sobald das
// Anruffenster wieder sichtbar ist. Waehrenddessen bekommt das Gegenueber state.want.video = "small".

@MainActor
final class CallPanel: NSObject {
    static let shared = CallPanel()
    private weak var calls: CallManager?
    private weak var window: NSWindow?
    private var panel: NSPanel?
    private var observers: [NSObjectProtocol] = []
    private var subs = Set<AnyCancellable>()
    /// Fenster wird gerade ins Dock verkleinert (SwiftUI meldet dabei ggf. onDisappear - das ist KEIN Auflegen)
    private(set) var miniaturizing = false

    /// Anruffenster bekannt machen (aus CallWindowContent)
    func attach(window w: NSWindow, calls c: CallManager) {
        if window === w, calls === c { return }
        for o in observers { NotificationCenter.default.removeObserver(o) }
        observers = []; subs = []
        window = w; calls = c
        let nc = NotificationCenter.default
        let names: [Notification.Name] = [NSWindow.didMiniaturizeNotification, NSWindow.didDeminiaturizeNotification,
                                          NSWindow.didChangeOcclusionStateNotification, NSWindow.didBecomeKeyNotification]
        for n in names {
            observers.append(nc.addObserver(forName: n, object: w, queue: .main) { [weak self] _ in
                MainActor.assumeIsolated { self?.miniaturizing = false; self?.refresh() }
            })
        }
        observers.append(nc.addObserver(forName: NSWindow.willMiniaturizeNotification, object: w, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.miniaturizing = true }
        })
        observers.append(nc.addObserver(forName: NSWindow.willCloseNotification, object: w, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.window = nil; self?.close() }
        })
        c.$phase.combineLatest(c.$remote, c.$speakerDev, c.$meshView).sink { [weak self] _ in
            DispatchQueue.main.async { MainActor.assumeIsolated { self?.refresh() } }
        }.store(in: &subs)
    }
    /// Knopf "Bild im Bild" im Anruffenster: Fenster ins Dock, das Schwebefenster uebernimmt
    func enter() { window?.miniaturize(nil) }
    /// "Zurueck": Anruffenster wieder nach vorn
    func back() {
        guard let w = window else { return }
        if w.isMiniaturized { w.deminiaturize(nil) }
        NSApp.activate()
        w.makeKeyAndOrderFront(nil)
        close()
    }
    var windowHidden: Bool {
        guard let w = window else { return false }
        return w.isMiniaturized || !w.occlusionState.contains(.visible)
    }

    func refresh() {
        guard let calls else { return }
        let video = calls.media == "video" || calls.focus.video
        let show = window != nil && PiPLogic.macPanelShows(phase: calls.phase.rawValue, video: video, callWindowVisible: !windowHidden)
        if show { open(calls) } else { close() }
    }
    /// Anrufende bzw. Fenster wieder sichtbar
    func close() {
        guard let p = panel else { return }
        panel = nil
        p.orderOut(nil); p.contentView = nil
        calls?.pipChanged(false)
    }

    private func open(_ calls: CallManager) {
        if let p = panel { if !p.isVisible { p.orderFrontRegardless() }; return }
        let p = NSPanel(contentRect: NSRect(x: 0, y: 0, width: 320, height: 200),
                        styleMask: [.titled, .closable, .resizable, .fullSizeContentView, .nonactivatingPanel, .utilityWindow],
                        backing: .buffered, defer: false)
        p.titleVisibility = .hidden; p.titlebarAppearsTransparent = true
        p.standardWindowButton(.closeButton)?.isHidden = true
        p.standardWindowButton(.miniaturizeButton)?.isHidden = true
        p.standardWindowButton(.zoomButton)?.isHidden = true
        p.level = .floating
        p.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary]
        p.hidesOnDeactivate = false
        p.isMovableByWindowBackground = true
        p.isReleasedWhenClosed = false
        p.minSize = NSSize(width: 220, height: 140)
        p.title = String(localized: "Bild im Bild")
        p.contentView = NSHostingView(rootView: CallPanelView(calls: calls, back: { [weak self] in self?.back() }))
        if !p.setFrameUsingName("SagaCallPanel"), let s = NSScreen.main?.visibleFrame {   // Groesse/Ort merken
            p.setFrameOrigin(NSPoint(x: s.maxX - 340, y: s.minY + 20))
        }
        p.setFrameAutosaveName("SagaCallPanel")
        p.orderFrontRegardless()
        panel = p
        calls.pipChanged(true)
    }
}

/// Inhalt des Schwebefensters: Fernbild (bzw. Profilbild) und drei Knoepfe
private struct CallPanelView: View {
    @ObservedObject var calls: CallManager
    let back: () -> Void
    var body: some View {
        ZStack(alignment: .bottom) {
            Color.black
            let f = calls.focus                                              // 2.4 E5: in Gruppen der aktive Sprecher
            if f.video {
                RTCVideoBox(track: f.track, fill: !f.screen)
                    .overlay(alignment: .topLeading) {
                        if calls.meshView { Text(verbatim: f.name).font(.caption.weight(.semibold)).padding(6).shadow(radius: 2) }
                    }
            } else {
                VStack(spacing: 6) {
                    AvatarView(name: f.name, avatar: f.avatar, size: 56)
                    Text(verbatim: f.name).font(.headline)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
            HStack(spacing: 10) {
                button(calls.mic ? "mic.fill" : "mic.slash.fill", calls.mic ? String(localized: "Stumm") : String(localized: "Ton an"),
                       bg: calls.mic ? .white.opacity(0.2) : .white, fg: calls.mic ? .white : .black) { calls.toggleMic() }
                button("phone.down.fill", String(localized: "Auflegen"), bg: .red, fg: .white) { calls.hangup() }
                button("arrow.up.left.and.arrow.down.right", String(localized: "Zurück zum Anruf"), bg: .white.opacity(0.2), fg: .white) { back() }
            }
            .padding(.bottom, 10)
        }
        .foregroundStyle(.white)
        .environment(\.colorScheme, .dark)
        .ignoresSafeArea()
    }
    private func button(_ icon: String, _ label: String, bg: Color, fg: Color, _ a: @escaping () -> Void) -> some View {
        Button(action: a) {
            Image(systemName: icon).font(.body.weight(.semibold)).frame(width: 36, height: 36).background(bg, in: Circle()).foregroundStyle(fg)
        }
        .buttonStyle(.plain).help(Text(verbatim: label)).accessibilityLabel(Text(verbatim: label))
    }
}

/// Liefert das NSWindow einer SwiftUI-Ansicht
struct WindowReader: NSViewRepresentable {
    let found: (NSWindow) -> Void
    func makeNSView(context: Context) -> NSView { Probe(found) }
    func updateNSView(_ v: NSView, context: Context) { if let w = v.window { found(w) } }
    private final class Probe: NSView {
        let found: (NSWindow) -> Void
        init(_ f: @escaping (NSWindow) -> Void) { found = f; super.init(frame: .zero) }
        required init?(coder: NSCoder) { fatalError("init(coder:) not used") }
        override func viewDidMoveToWindow() { super.viewDidMoveToWindow(); if let w = window { found(w) } }
    }
}
#endif
