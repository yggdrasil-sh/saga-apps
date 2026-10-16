import SwiftUI
import WebRTC

// Anrufe (Saga 2.1): Oberflaeche - Klingeln (ohne CallKit: Mac, Tests), Gespraech (Video gross, eigenes Bild klein),
// Stumm/Kamera/Lautsprecher/Auflegen, "Ende-zu-Ende verschluesselt" erst nach der Pruefung des DTLS-Fingerabdrucks.
// iPhone/iPad: Vollbild ueber der App; Mac: eigenes Fenster "Anruf" (HlidskjalfApp).

struct CallScreen: View {
    @ObservedObject var calls: CallManager
    var close: () -> Void = {}
    var body: some View {
        ZStack {
            LinearGradient(colors: [Color(red: 0.10, green: 0.09, blue: 0.07), Color(red: 0.04, green: 0.04, blue: 0.05)], startPoint: .top, endPoint: .bottom)
                .ignoresSafeArea()
            if showRemoteVideo {
                RTCVideoBox(track: calls.remoteVideo, fill: !calls.remote.screen).ignoresSafeArea()
            }
            VStack(spacing: 14) {
                header
                if !showRemoteVideo { Spacer(); peerBlock; Spacer() } else { Spacer() }
                if calls.phase == .incoming { incomingButtons } else if calls.phase == .ended { endedBlock } else { controls }
            }
            .padding(.horizontal, 20).padding(.vertical, 24)
            if calls.cam, let t = calls.localVideo, calls.phase != .incoming, calls.phase != .ended {
                RTCVideoBox(track: t, mirror: true)
                    .frame(width: 110, height: 160).clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                    .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(.white.opacity(0.3)))
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing)
                    .padding(.top, 70).padding(.trailing, 16)
                    .accessibilityLabel(Text("Eigenes Kamerabild"))
            }
        }
        .foregroundStyle(.white)
        .environment(\.colorScheme, .dark)
    }

    private var showRemoteVideo: Bool { calls.remoteVideo != nil && (calls.remote.cam || calls.remote.screen) && (calls.phase == .active || calls.phase == .connecting) }

    private var header: some View {
        VStack(spacing: 4) {
            if showRemoteVideo { Text(verbatim: calls.peerName).font(.headline).shadow(radius: 3) }
            HStack(spacing: 6) {
                if calls.verified {
                    Label("Ende-zu-Ende verschlüsselt", systemImage: "lock.fill").labelStyle(.titleAndIcon)
                } else if calls.phase == .active {
                    Label("Verschlüsselung wird geprüft …", systemImage: "lock.open")
                }
                if calls.relay { Text(verbatim: "·"); Text("über Relais") }
            }
            .font(.caption).foregroundStyle(.white.opacity(0.85)).shadow(radius: 2)
        }
        .frame(maxWidth: .infinity)
    }

    private var peerBlock: some View {
        VStack(spacing: 12) {
            AvatarView(name: calls.peerName, avatar: calls.peerAvatar, size: 120)
                .overlay(Circle().strokeBorder(Color.gold.opacity(calls.phase == .incoming || calls.phase == .ringingOut ? 0.8 : 0), lineWidth: 3).padding(-6))
            Text(verbatim: calls.peerName).font(.title.weight(.semibold)).multilineTextAlignment(.center)
            statusLine
            if calls.phase == .active && !calls.remote.mic {
                Label("\(calls.peerName) ist stummgeschaltet", systemImage: "mic.slash.fill").font(.footnote).foregroundStyle(.white.opacity(0.8))
            }
        }
    }
    @ViewBuilder private var statusLine: some View {
        if calls.phase == .active, let t0 = calls.connectedAt {
            TimelineView(.periodic(from: t0, by: 1)) { ctx in
                Text(verbatim: CallLogic.duration(Int(ctx.date.timeIntervalSince(t0)))).font(.title3.monospacedDigit()).foregroundStyle(.white.opacity(0.85))
            }
        } else if !calls.status.isEmpty {
            Text(verbatim: calls.status).font(.callout).foregroundStyle(.white.opacity(0.85)).multilineTextAlignment(.center).frame(maxWidth: 420)
        }
    }

    private var incomingButtons: some View {
        HStack(spacing: 40) {
            round("phone.down.fill", String(localized: "Ablehnen"), .red) { calls.decline() }
            round("phone.fill", String(localized: "Annehmen"), .green) { calls.answer(video: false) }
            if calls.media == "video" { round("video.fill", String(localized: "Mit Video"), .green) { calls.answer(video: true) } }
        }
        .padding(.bottom, 10)
    }

    private var controls: some View {
        VStack(spacing: 10) {
            if showRemoteVideo || calls.phase != .active { if showRemoteVideo { statusLine } }
            HStack(spacing: 22) {
                toggle(calls.mic ? "mic.fill" : "mic.slash.fill", calls.mic ? String(localized: "Stumm") : String(localized: "Ton an"), on: !calls.mic) { calls.toggleMic() }
                toggle(calls.cam ? "video.fill" : "video.slash.fill", calls.cam ? String(localized: "Kamera aus") : String(localized: "Kamera an"), on: !calls.cam) { calls.toggleCam() }
                #if os(iOS)
                if !calls.cam { toggle("speaker.wave.2.fill", String(localized: "Lautsprecher"), on: calls.speaker) { calls.toggleSpeaker() } }
                if calls.cam && calls.canSwitchCamera { toggle("arrow.triangle.2.circlepath.camera", String(localized: "Kamera wechseln"), on: false) { calls.switchCamera() } }
                #endif
                round("phone.down.fill", String(localized: "Auflegen"), .red) { calls.hangup() }
            }
        }
        .padding(.bottom, 6)
    }

    private var endedBlock: some View {
        VStack(spacing: 12) {
            if showRemoteVideo { Text(verbatim: calls.status).font(.callout) }
            Button(String(localized: "Schließen")) { calls.dismissEnded(); close() }
                .buttonStyle(.bordered).tint(.white).controlSize(.large)
        }
    }

    private func round(_ icon: String, _ label: String, _ color: Color, _ a: @escaping () -> Void) -> some View {
        Button(action: a) {
            VStack(spacing: 6) {
                Image(systemName: icon).font(.title2).frame(width: 60, height: 60).background(color, in: Circle()).foregroundStyle(.white)
                Text(verbatim: label).font(.caption2).lineLimit(1).fixedSize()
            }
        }
        .buttonStyle(.plain).accessibilityLabel(Text(verbatim: label))
    }
    private func toggle(_ icon: String, _ label: String, on: Bool, _ a: @escaping () -> Void) -> some View {
        Button(action: a) {
            VStack(spacing: 6) {
                Image(systemName: icon).font(.title3).frame(width: 60, height: 60)
                    .background(on ? Color.white : Color.white.opacity(0.18), in: Circle())
                    .foregroundStyle(on ? Color.black : Color.white)
                Text(verbatim: label).font(.caption2).lineLimit(1).fixedSize()
            }
        }
        .buttonStyle(.plain).accessibilityLabel(Text(verbatim: label)).accessibilityAddTraits(on ? .isSelected : [])
    }
}

/// Anruf-Oberflaeche einblenden (iPhone/iPad: Vollbild; Mac: Fenster "Anruf") + Hinweise
struct CallPresenter: ViewModifier {
    @ObservedObject var calls: CallManager
    #if os(macOS)
    @Environment(\.openWindow) private var openWindow
    #endif
    func body(content: Content) -> some View {
        content
            #if os(iOS)
            .fullScreenCover(isPresented: Binding(get: { show }, set: { if !$0 { calls.dismissEnded() } })) { CallScreen(calls: calls) }
            #else
            .onChange(of: calls.phase) { _, p in if p != .idle { openWindow(id: "call"); NSApp.activate() } }
            #endif
            #if DEBUG
            .task { if let s = calls.store { await CallTestHook.run(s) } }
            #endif
            .overlay(alignment: .bottom) {
                if let t = calls.toast {
                    Text(verbatim: t).font(.subheadline).padding(.horizontal, 16).padding(.vertical, 10)
                        .background(.regularMaterial, in: Capsule()).padding(.bottom, 24)
                        .task { try? await Task.sleep(for: .seconds(4)); calls.toast = nil }
                }
            }
    }
    #if os(iOS)
    /// Mit CallKit klingelt das System selbst (eigene Oberflaeche erst nach dem Annehmen)
    private var show: Bool { calls.phase != .idle && !(CallEnv.callKit && calls.phase == .incoming) }
    #endif
}

#if os(macOS)
/// Inhalt des Mac-Fensters "Anruf": schliesst sich, wenn nichts mehr laeuft
struct CallWindowContent: View {
    @EnvironmentObject var store: Store
    @Environment(\.dismissWindow) private var dismissWindow
    var body: some View {
        CallWindowBody(calls: store.calls) { dismissWindow(id: "call") }
            .frame(minWidth: 380, idealWidth: 460, minHeight: 520, idealHeight: 640)
    }
}
private struct CallWindowBody: View {
    @ObservedObject var calls: CallManager
    let close: () -> Void
    var body: some View {
        CallScreen(calls: calls, close: close)
            .onChange(of: calls.phase) { _, p in if p == .idle { close() } }
            .onAppear { if calls.phase == .idle { close() } }
            .onDisappear { if calls.busy { calls.hangup() } }                // Fenster geschlossen = auflegen
    }
}
#endif

#if DEBUG
/// nur fuer Tests (Startparameter -hkCallStart "<chat-id>:audio|video"): nach dem Start anrufen
enum CallTestHook {
    @MainActor static func run(_ store: Store) async {
        guard let v = UserDefaults.standard.string(forKey: "hkCallStart") else { return }
        let p = v.split(separator: ":"), video = p.count > 1 && p[1] == "video"
        guard let cid = p.first.flatMap({ Int($0) }) else { return }
        try? await Task.sleep(for: .seconds(UserDefaults.standard.double(forKey: "hkCallStartDelay").nonZero ?? 6))
        await store.chat.load()
        guard let d = try? await store.api("api/chats/\(cid)?\(store.chat.deviceParam)"), let c = ShareJSON.obj(d)?["chat"],
              let s = ChatSummary(c), let peer = s.peer else { CallEnv.log("start: chat not found"); return }
        store.calls.start(chatID: cid, peer: peer, video: video)
    }
}
private extension Double { var nonZero: Double? { self == 0 ? nil : self } }
#endif
