import SwiftUI
import WebRTC

// Anrufe (Saga 2.1): Oberflaeche - Klingeln (ohne CallKit: Mac, Tests), Gespraech (Video gross, eigenes Bild klein),
// Stumm/Kamera/Lautsprecher/Auflegen, "Ende-zu-Ende verschluesselt" erst nach der Pruefung des DTLS-Fingerabdrucks.
// iPhone/iPad: Vollbild ueber der App; Mac: eigenes Fenster "Anruf" (HlidskjalfApp).

struct CallScreen: View {
    @ObservedObject var calls: CallManager
    var close: () -> Void = {}
    /// Hoehe der Kopfzeile (Verschluesselung, Ton-Diagnose): das eigene Kamerabild beginnt darunter
    @State private var headerHeight: CGFloat = 0
    var body: some View {
        ZStack {
            LinearGradient(colors: [Color(red: 0.10, green: 0.09, blue: 0.07), Color(red: 0.04, green: 0.04, blue: 0.05)], startPoint: .top, endPoint: .bottom)
                .ignoresSafeArea()
            if showRemoteVideo {
                RTCVideoBox(track: calls.remoteVideo, fill: !calls.remote.screen).ignoresSafeArea()
            }
            VStack(spacing: 14) {
                header.onGeometryChange(for: CGFloat.self) { $0.size.height } action: { headerHeight = $0 }
                if !showRemoteVideo { Spacer(); peerBlock; Spacer() } else { Spacer() }
                if calls.phase == .incoming { incomingButtons } else if calls.phase == .ended { endedBlock } else { controls }
            }
            .padding(.horizontal, 20).padding(.vertical, 24)
            if calls.cam, let t = calls.localVideo, calls.phase != .incoming, calls.phase != .ended {
                RTCVideoBox(track: t, mirror: calls.frontCamera)                 // Rueckkamera nicht spiegeln (Schrift lesbar)
                    .accessibilityLabel(Text("Eigenes Kamerabild"))
                    .frame(width: 110, height: 160).clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                    .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(.white.opacity(0.3)))
                    #if os(iOS)
                    .overlay(alignment: .bottomTrailing) {                    // Kamera wechseln am eigenen Bild (Platz fuer den Lautsprecher-Knopf)
                        if calls.canSwitchCamera {
                            Button { calls.switchCamera() } label: {
                                Image(systemName: "arrow.triangle.2.circlepath.camera").font(.footnote).frame(width: 34, height: 34)
                                    .background(.black.opacity(0.45), in: Circle())
                            }
                            .buttonStyle(.plain).padding(6).accessibilityLabel(Text("Kamera wechseln"))
                        }
                    }
                    #endif
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing)
                    .padding(.top, 24 + headerHeight + 10).padding(.trailing, 16)   // unter der Kopfzeile (Ton-Diagnose bleibt lesbar)
            }
        }
        .foregroundStyle(.white)
        .environment(\.colorScheme, .dark)
        .dynamicTypeSize(...DynamicTypeSize.accessibility3)                 // groesste Stufen: Name/Status passen noch neben die Knoepfe
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
            if calls.phase == .active, let d = audioLine {
                Label(d.text, systemImage: d.ok ? "speaker.wave.2" : "speaker.slash")
                    .font(.caption2).foregroundStyle(.white.opacity(d.ok ? 0.6 : 0.85)).shadow(radius: 2)
                    .accessibilityLabel(Text(verbatim: d.text))
            }
        }
        .frame(maxWidth: .infinity)
    }

    /// Ton-Diagnose (dezent): "Ton: empfangen · Mikro: ok" bzw. warum nichts zu hoeren ist
    private var audioLine: (text: String, ok: Bool)? {
        var parts: [String] = [], ok = true
        switch calls.audioIn {
        case .unknown: return nil
        case .receiving: parts.append(String(localized: "Ton: empfangen"))
        case .silent:
            if calls.remote.mic { parts.append(String(localized: "Ton: kommt an, aber still")) } else { parts.append(String(localized: "Ton: Gegenüber stumm")) }
        case .noPlayout: parts.append(String(localized: "Ton: kein Ton (Wiedergabe aus)")); ok = false
        case .nothing:
            if calls.remote.mic { parts.append(String(localized: "Ton: kein Ton")); ok = false } else { parts.append(String(localized: "Ton: Gegenüber stumm")) }
        }
        switch calls.audioMic {
        case .ok: parts.append(String(localized: "Mikro: ok"))
        case .silent: parts.append(String(localized: "Mikro: kein Signal")); ok = false
        case .none: parts.append(String(localized: "Mikro: aus")); ok = false
        case .unknown: break
        }
        return (parts.joined(separator: " · "), ok)
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
        ViewThatFits(in: .horizontal) {
            incomingRow(spacing: 40)
            incomingRow(spacing: 12)
        }
        .dynamicTypeSize(...DynamicTypeSize.xxxLarge)                       // Bedienungshilfen-Groessen: Knoepfe bleiben im Bild
        .padding(.bottom, 10)
    }
    private func incomingRow(spacing: CGFloat) -> some View {
        HStack(spacing: spacing) {
            round("phone.down.fill", String(localized: "Ablehnen"), .red) { calls.decline() }
            round("phone.fill", String(localized: "Annehmen"), .green) { calls.answer(video: false) }
            if calls.media == "video" { round("video.fill", String(localized: "Mit Video"), .green) { calls.answer(video: true) } }
        }
    }

    private var controls: some View {
        VStack(spacing: 10) {
            if showRemoteVideo { statusLine }
            if showRemoteVideo && calls.phase == .active && !calls.remote.mic {        // sonst nur im (ausgeblendeten) Profilbild-Block
                Label("\(calls.peerName) ist stummgeschaltet", systemImage: "mic.slash.fill").font(.footnote).shadow(radius: 2)
                    .padding(.horizontal, 12).padding(.vertical, 6).background(.black.opacity(0.45), in: Capsule())
            }
            ViewThatFits(in: .horizontal) {
                controlRow(spacing: 18)
                controlRow(spacing: 6)
            }
            .dynamicTypeSize(...DynamicTypeSize.xxxLarge)                   // Bedienungshilfen-Groessen: Auflegen bleibt erreichbar
        }
        .padding(.bottom, 6)
    }
    private func controlRow(spacing: CGFloat) -> some View {
        HStack(spacing: spacing) {
            toggle(calls.mic ? "mic.fill" : "mic.slash.fill", calls.mic ? String(localized: "Stumm") : String(localized: "Ton an"), on: !calls.mic) { calls.toggleMic() }
            toggle(calls.cam ? "video.fill" : "video.slash.fill", calls.cam ? String(localized: "Kamera aus") : String(localized: "Kamera an"), on: !calls.cam) { calls.toggleCam() }
            #if os(iOS)
            if UIDevice.current.userInterfaceIdiom == .phone {           // nur iPhone hat eine Hoermuschel; auch bei Video umschaltbar
                toggle("speaker.wave.2.fill", String(localized: "Lautsprecher"), on: calls.speaker) { calls.toggleSpeaker() }
            }
            #endif
            round("phone.down.fill", String(localized: "Auflegen"), .red) { calls.hangup() }
        }
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
                buttonLabel(label)
            }
        }
        .buttonStyle(.plain).accessibilityLabel(Text(verbatim: label))
        .accessibilityShowsLargeContentViewer { Label(label, systemImage: icon) }
    }
    private func toggle(_ icon: String, _ label: String, on: Bool, _ a: @escaping () -> Void) -> some View {
        Button(action: a) {
            VStack(spacing: 6) {
                Image(systemName: icon).font(.title3).frame(width: 60, height: 60)
                    .background(on ? Color.white : Color.white.opacity(0.18), in: Circle())
                    .foregroundStyle(on ? Color.black : Color.white)
                buttonLabel(label)
            }
        }
        .buttonStyle(.plain).accessibilityLabel(Text(verbatim: label)).accessibilityAddTraits(on ? .isSelected : [])
        .accessibilityShowsLargeContentViewer { Label(label, systemImage: icon) }
    }
    /// Feste Breite je Knopf: lange Beschriftungen (es/de, grosse Schrift) werden kleiner statt die Zeile zu sprengen
    private func buttonLabel(_ label: String) -> some View {
        Text(verbatim: label).font(.caption2).lineLimit(1).minimumScaleFactor(0.6).frame(width: 72)
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
            // Fenster nur beim Beginn (eigener Anruf / es klingelt) und beim Annehmen oeffnen, nie die App aktivieren:
            // eingehend meldet sich CallSystem.reportIncoming dezent per Mitteilung + Dock (kein Fokus-Klau beim Tippen
            // in einer anderen App); Annehmen ueber die Mitteilung holt Saga selbst nach vorn (Aktion .foreground).
            .onChange(of: calls.phase) { old, p in
                if CallLogic.macOpensCallWindow(from: old.rawValue, to: p.rawValue) { openWindow(id: "call") }
            }
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
