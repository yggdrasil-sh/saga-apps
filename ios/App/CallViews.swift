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
            if showTiles {
                // 2.4 E4/E5: Kachelansicht (Gruppe bzw. mehrere sichtbare Verbindungen), Sprecher hervorgehoben
                CallTileGrid(calls: calls)
                    .padding(.top, 24 + headerHeight + 10).padding(.bottom, 170).padding(.horizontal, 10)
            } else if showRemoteVideo {
                // 2.4 E3: Bildschirm gross und ganz sichtbar (aspect fit), Kamera klein; "Ansicht tauschen" tauscht
                RTCVideoBox(track: bigTrack, fill: !bigIsScreen).ignoresSafeArea()
            }
            VStack(spacing: 14) {
                shareBar
                header.onGeometryChange(for: CGFloat.self) { $0.size.height } action: { headerHeight = $0 }
                if !showRemoteVideo && !showTiles { Spacer(); peerBlock; Spacer() } else { Spacer() }
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
            if showRemoteVideo, let t = smallTrack {                         // 2.4 E3: zweites Fernbild klein (Tippen tauscht)
                Button { calls.swapView.toggle() } label: {
                    ZStack { RTCVideoBox(track: t, fill: bigIsScreen).allowsHitTesting(false); Color.clear }
                        .frame(width: 120, height: bigIsScreen ? 160 : 90).clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                        .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(.white.opacity(0.3)))
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text("Ansicht tauschen"))
                .help(Text("Ansicht tauschen"))
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                .padding(.top, 24 + headerHeight + 10).padding(.leading, 16)
            }
            if calls.screenHint { ScreenHintCard(calls: calls) }
        }
        #if os(iOS)
        .background { BroadcastPickerHost(capture: calls.capture).frame(width: 1, height: 1).allowsHitTesting(false) }
        #endif
        #if os(iOS)
        .overlay(alignment: .topLeading) {                                   // 2.3.1: zurueck in die App, Anruf laeuft weiter
            if CallLogic.isOngoing(calls.phase.rawValue) {
                Button { calls.minimize() } label: {
                    Image(systemName: "chevron.down").font(.body.weight(.semibold)).frame(width: 40, height: 40)
                        .background(.white.opacity(0.18), in: Circle())
                }
                .buttonStyle(.plain).foregroundStyle(.white)
                .padding(.leading, 16).padding(.top, 8)
                .accessibilityLabel(Text("Zurück zur App"))
                .help(Text("Zurück zur App"))
            }
        }
        #else
        .overlay(alignment: .topLeading) {                                   // 2.4: Schwebefenster (Bild im Bild)
            if CallLogic.isOngoing(calls.phase.rawValue) && calls.phase != .preparing && (calls.media == "video" || calls.remote.cam) {
                Button { CallPanel.shared.enter() } label: {
                    Image(systemName: "pip.enter").font(.body.weight(.semibold)).frame(width: 34, height: 34)
                        .background(.white.opacity(0.18), in: Circle())
                }
                .buttonStyle(.plain).foregroundStyle(.white)
                .padding(.leading, 14).padding(.top, 10)
                .accessibilityLabel(Text("Bild im Bild"))
                .help(Text("Bild im Bild"))
            }
        }
        #endif
        .foregroundStyle(.white)
        .environment(\.colorScheme, .dark)
        .dynamicTypeSize(...DynamicTypeSize.accessibility3)                 // groesste Stufen: Name/Status passen noch neben die Knoepfe
    }

    private var showRemoteVideo: Bool { !showTiles && calls.remoteVideo != nil && (calls.remote.cam || calls.remote.screen) && (calls.phase == .active || calls.phase == .connecting) }
    /// 2.4 E4/E5: Kacheln statt Einzelansicht
    private var showTiles: Bool { calls.meshView && !calls.tiles.isEmpty && (calls.phase == .active || calls.phase == .connecting) }
    // 2.4 E3: remoteVideo = Bildschirm (teilt das Gegenueber) bzw. Kamera, remoteSide = Kamera neben dem Bildschirm
    private var swapped: Bool { calls.swapView && calls.remoteSide != nil }
    private var bigTrack: RTCVideoTrack? { swapped ? calls.remoteSide : calls.remoteVideo }
    private var smallTrack: RTCVideoTrack? { calls.remoteSide == nil ? nil : swapped ? calls.remoteVideo : calls.remoteSide }
    private var bigIsScreen: Bool { calls.remote.screen && !swapped }

    /// Rote Leiste: "Du teilst deinen Bildschirm" (mit Beenden) bzw. "<Name> teilt den Bildschirm" (mit Tauschen)
    @ViewBuilder private var shareBar: some View {
        if calls.sharing || (calls.remote.screen && calls.phase == .active) {
            HStack(spacing: 10) {
                Image(systemName: "rectangle.inset.filled.on.rectangle")
                VStack(alignment: .leading, spacing: 1) {
                    if calls.sharing { Text("Du teilst deinen Bildschirm").font(.subheadline.weight(.semibold)) }
                    else { Text("\(calls.peerName) teilt den Bildschirm").font(.subheadline.weight(.semibold)) }
                    if calls.sharing && calls.shareCamPaused { Text("Kamera pausiert (langsame Verbindung)").font(.caption) }
                }
                .lineLimit(1).minimumScaleFactor(0.75)
                Spacer(minLength: 4)
                if calls.sharing {
                    Button(String(localized: "Teilen beenden")) { calls.stopScreen() }
                        .buttonStyle(.bordered).tint(.white).controlSize(.small)
                } else if calls.remoteSide != nil {
                    Button { calls.swapView.toggle() } label: { Image(systemName: "arrow.left.arrow.right") }
                        .buttonStyle(.bordered).tint(.white).controlSize(.small)
                        .accessibilityLabel(Text("Ansicht tauschen")).help(Text("Ansicht tauschen"))
                }
            }
            .foregroundStyle(.white)
            .padding(.horizontal, 12).padding(.vertical, 7)
            .background(Color(red: 0.78, green: 0.13, blue: 0.13), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
            #if os(iOS)
            .padding(.leading, 48)                                            // Platz fuer "Zurueck zur App"
            #else
            .padding(.leading, 40)                                            // Platz fuer "Bild im Bild"
            #endif
            .accessibilityElement(children: .combine)
        }
    }

    private var header: some View {
        VStack(spacing: 4) {
            if showRemoteVideo || showTiles { Text(verbatim: calls.peerName).font(.headline).shadow(radius: 3) }
            roomInfo
            HStack(spacing: 6) {
                if calls.verified {
                    Label("Ende-zu-Ende verschlüsselt", systemImage: "lock.fill").labelStyle(.titleAndIcon)
                } else if calls.phase == .active {
                    Label("Verschlüsselung wird geprüft …", systemImage: "lock.open")
                }
                if calls.relay { Text(verbatim: "·"); Text("über Relais") }
            }
            .font(.caption).foregroundStyle(.white.opacity(0.85)).shadow(radius: 2)
            if calls.reconnecting {                                          // 2.4: ICE-Restart laeuft
                Label("Verbindung wird neu aufgebaut …", systemImage: "arrow.triangle.2.circlepath")
                    .font(.caption).foregroundStyle(.white.opacity(0.9)).shadow(radius: 2)
            }
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
            companionBlock
            ViewThatFits(in: .horizontal) {
                controlRow(spacing: 18, width: 72)
                controlRow(spacing: 6, width: 72)
                controlRow(spacing: 4, width: 62)                            // 2.4: fuenf Knoepfe auf dem iPhone
            }
            .dynamicTypeSize(...DynamicTypeSize.xxxLarge)                   // Bedienungshilfen-Groessen: Auflegen bleibt erreichbar
        }
        .padding(.bottom, 6)
    }
    private func controlRow(spacing: CGFloat, width: CGFloat) -> some View {
        HStack(spacing: spacing) {
            toggle(calls.mic ? "mic.fill" : "mic.slash.fill", calls.mic ? String(localized: "Stumm") : String(localized: "Ton an"), on: !calls.mic, width: width) { calls.toggleMic() }
            toggle(calls.cam ? "video.fill" : "video.slash.fill", calls.cam ? String(localized: "Kamera aus") : String(localized: "Kamera an"), on: !calls.cam, width: width) { calls.toggleCam() }
            if calls.phase == .active {                                       // 2.4 E3: Bildschirm teilen
                toggle("rectangle.inset.filled.on.rectangle", calls.sharing ? String(localized: "Teilen beenden") : String(localized: "Bildschirm teilen"),
                       on: calls.sharing, width: width) { calls.toggleScreen() }
            }
            if calls.companion || !calls.sound {                              // 2.4: Begleitgeraet - Wiedergabe an/aus (startet stumm)
                toggle(calls.sound ? "speaker.wave.2.fill" : "speaker.slash.fill", calls.sound ? String(localized: "Ton aus") : String(localized: "Ton an"),
                       on: !calls.sound, width: width) { calls.toggleSound() }
            } else {
                #if os(iOS)
                if UIDevice.current.userInterfaceIdiom == .phone {           // nur iPhone hat eine Hoermuschel; auch bei Video umschaltbar
                    toggle("speaker.wave.2.fill", String(localized: "Lautsprecher"), on: calls.speaker, width: width) { calls.toggleSpeaker() }
                }
                #endif
            }
            round("phone.down.fill", String(localized: "Auflegen"), .red, width: width) { calls.hangup() }
        }
    }

    /// 2.4: Teilnehmer, Grenze, Personen ohne Saga 2.4, Begleitgeraet
    @ViewBuilder private var roomInfo: some View {
        if calls.kind == "group" || calls.inRoom || calls.tiles.count > 1 {
            VStack(spacing: 2) {
                if calls.phase == .active || calls.phase == .connecting || calls.phase == .ringingOut {
                    Text("\(calls.links.count + 1) von höchstens \(calls.limit) Geräten").font(.caption2)
                }
                if !calls.oldNames.isEmpty {
                    Text("Braucht Saga 2.4: \(calls.oldNames.joined(separator: ", "))").font(.caption2).multilineTextAlignment(.center)
                }
            }
            .foregroundStyle(.white.opacity(0.8)).shadow(radius: 2)
        }
    }
    /// 2.4: Begleitgeraet - Hinweis und eigener Knopf "Hierher uebernehmen"
    @ViewBuilder private var companionBlock: some View {
        if calls.companion && calls.phase != .ended && calls.phase != .incoming {
            VStack(spacing: 6) {
                Text(calls.kind == "group" ? String(localized: "Begleitgerät – Mikrofon und Ton sind aus.")
                     : String(localized: "Begleitgerät – Mikrofon und Ton sind aus. Auflegen am Hauptgerät beendet den Anruf für alle; zum Weitermachen erst hierher übernehmen."))
                    .font(.caption).multilineTextAlignment(.center).frame(maxWidth: 440)
                    .padding(.horizontal, 12).padding(.vertical, 6).background(.black.opacity(0.45), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                Button { calls.takeOver() } label: {
                    HStack(spacing: 6) {
                        if calls.handingOver { ProgressView().controlSize(.small).tint(.black) } else { Image(systemName: "arrow.down.forward.and.arrow.up.backward") }
                        Text("Hierher übernehmen")
                    }
                    .font(.subheadline.weight(.semibold))
                }
                .buttonStyle(.borderedProminent).tint(.white).foregroundStyle(.black).disabled(calls.handingOver || calls.phase != .active)
                .help(Text("Anruf ohne Unterbrechung auf dieses Gerät holen"))
            }
        } else if calls.handingOver {
            Label("Wird übernommen …", systemImage: "arrow.down.forward.and.arrow.up.backward").font(.caption)
                .padding(.horizontal, 12).padding(.vertical, 6).background(.black.opacity(0.45), in: Capsule())
        }
    }

    private var endedBlock: some View {
        VStack(spacing: 12) {
            if showRemoteVideo { Text(verbatim: calls.status).font(.callout) }
            Button(String(localized: "Schließen")) { calls.dismissEnded(); close() }
                .buttonStyle(.bordered).tint(.white).controlSize(.large)
        }
    }

    private func round(_ icon: String, _ label: String, _ color: Color, width: CGFloat = 72, _ a: @escaping () -> Void) -> some View {
        Button(action: a) {
            VStack(spacing: 6) {
                Image(systemName: icon).font(.title2).frame(width: 60, height: 60).background(color, in: Circle()).foregroundStyle(.white)
                buttonLabel(label, width: width)
            }
        }
        .buttonStyle(.plain).accessibilityLabel(Text(verbatim: label))
        .accessibilityShowsLargeContentViewer { Label(label, systemImage: icon) }
    }
    private func toggle(_ icon: String, _ label: String, on: Bool, width: CGFloat = 72, _ a: @escaping () -> Void) -> some View {
        Button(action: a) {
            VStack(spacing: 6) {
                Image(systemName: icon).font(.title3).frame(width: 60, height: 60)
                    .background(on ? Color.white : Color.white.opacity(0.18), in: Circle())
                    .foregroundStyle(on ? Color.black : Color.white)
                buttonLabel(label, width: width)
            }
        }
        .buttonStyle(.plain).accessibilityLabel(Text(verbatim: label)).accessibilityAddTraits(on ? .isSelected : [])
        .accessibilityShowsLargeContentViewer { Label(label, systemImage: icon) }
    }
    /// Feste Breite je Knopf: lange Beschriftungen (es/de, grosse Schrift) werden kleiner statt die Zeile zu sprengen
    private func buttonLabel(_ label: String, width: CGFloat = 72) -> some View {
        Text(verbatim: label).font(.caption2).lineLimit(1).minimumScaleFactor(0.6).frame(width: width)
    }
}

/// 2.4 E3: Hinweis vor dem ersten Teilen (Datenschutz, Konzept §4.4) - "Nicht mehr anzeigen" gilt je Geraet
struct ScreenHintCard: View {
    @ObservedObject var calls: CallManager
    @State private var dontShow = false
    var body: some View {
        ZStack {
            Color.black.opacity(0.55).ignoresSafeArea().onTapGesture { calls.screenHintDone(go: false, dontShow: false) }
            VStack(alignment: .leading, spacing: 12) {
                Label("Bildschirm teilen", systemImage: "rectangle.inset.filled.on.rectangle").font(.headline)
                Text("Alles, was in der Auswahl sichtbar ist, sieht dein Gegenüber – auch Mitteilungen, Passwörter und andere Fenster oder Chats.")
                    .fixedSize(horizontal: false, vertical: true)
                Text("Nichts wird aufgezeichnet oder auf dem Server gespeichert.").font(.footnote).foregroundStyle(.white.opacity(0.8))
                    .fixedSize(horizontal: false, vertical: true)
                #if os(iOS)
                Text("Im nächsten Schritt „Saga“ wählen und „Übertragung starten“ tippen. Ton wird nicht übertragen.")
                    .font(.footnote).foregroundStyle(.white.opacity(0.8)).fixedSize(horizontal: false, vertical: true)
                #else
                Text("Teile am besten nur ein Fenster. Ton wird nicht übertragen.").font(.footnote).foregroundStyle(.white.opacity(0.8))
                    .fixedSize(horizontal: false, vertical: true)
                #endif
                Toggle("Nicht mehr anzeigen", isOn: $dontShow)
                    #if os(macOS)
                    .toggleStyle(.checkbox)
                    #endif
                HStack {
                    Spacer()
                    Button(String(localized: "Abbrechen")) { calls.screenHintDone(go: false, dontShow: false) }
                        .buttonStyle(.bordered).tint(.white)
                    Button(String(localized: "Bildschirm wählen")) { calls.screenHintDone(go: true, dontShow: dontShow) }
                        .buttonStyle(.borderedProminent).tint(.white).foregroundStyle(.black)
                        .keyboardShortcut(.defaultAction)
                }
            }
            .padding(18)
            .frame(maxWidth: 380)
            .background(Color(white: 0.14), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            .padding(24)
            .accessibilityAddTraits(.isModal)
        }
    }
}

/// Anruf-Oberflaeche einblenden (iPhone/iPad: Vollbild; Mac: Fenster "Anruf") + Hinweise
struct CallPresenter: ViewModifier {
    @ObservedObject var calls: CallManager
    #if os(macOS)
    @Environment(\.openWindow) private var openWindow
    #else
    @Environment(\.scenePhase) private var scenePhase
    #endif
    func body(content: Content) -> some View {
        content
            #if os(iOS)
            // 2.4: Quellansicht fuer Bild-im-Bild (unsichtbar hinter der App) und Szenenwechsel (Hintergrund)
            .background { PiPSourceHost().allowsHitTesting(false).accessibilityHidden(true) }
            .onChange(of: scenePhase, initial: true) { _, p in CallPiP.shared.sceneActive(p == .active) }
            #endif
            // 2.3.1: laufender Anruf verkleinert bzw. Mac-Fenster im Hintergrund -> gruene Leiste oben, Tippen kehrt zurueck
            .safeAreaInset(edge: .top, spacing: 0) {
                VStack(spacing: 0) {
                    if CallLogic.showsCallBar(phase: calls.phase.rawValue, minimized: calls.minimized, separateWindow: separateWindow) {
                        CallBar(calls: calls) { returnToCall() }
                    }
                    CallRunBar(calls: calls)                                   // 2.4: Anruf laeuft auf anderem Geraet / Gruppenanruf
                }
            }
            .animation(.snappy(duration: 0.25), value: calls.runBars)
            .animation(.snappy(duration: 0.25), value: calls.minimized)
            #if os(iOS)
            // Vollbild nur, solange nicht verkleinert; "Zurueck zur App" schliesst es, der Anruf laeuft weiter
            .fullScreenCover(isPresented: Binding(get: { calls.showsFullScreen }, set: { if !$0 { calls.minimize() } })) {
                CallScreen(calls: calls)
                    .onAppear { calls.coverVisible = true }
                    .onDisappear { calls.coverVisible = false }
            }
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
    private let separateWindow = false
    private func returnToCall() { calls.expand() }
    #else
    private let separateWindow = true                                       // Mac: eigenes Fenster "Anruf"
    private func returnToCall() { openWindow(id: "call") }                  // holt das (offene) Fenster nach vorn
    #endif
}

/// Leiste "Anruf laeuft - tippen zum Zurueckkehren" (gruen, mit Dauer) ueber der App
struct CallBar: View {
    @ObservedObject var calls: CallManager
    let action: () -> Void
    /// etwas dunkler als das System-Gruen: weisse Schrift bleibt gut lesbar
    static let green = Color(red: 0.13, green: 0.58, blue: 0.27)
    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                Image(systemName: calls.media == "video" ? "video.fill" : "phone.fill")
                #if os(iOS)
                Text("Anruf läuft – tippen zum Zurückkehren").lineLimit(1).minimumScaleFactor(0.75)
                #else
                Text("Anruf läuft – klicken zum Zurückkehren").lineLimit(1).minimumScaleFactor(0.75)
                #endif
                Spacer(minLength: 4)
                CallDurationText(calls: calls)
            }
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(.white)
            .padding(.horizontal, 16).padding(.vertical, 8)
            .frame(maxWidth: .infinity)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .background(Self.green.ignoresSafeArea(edges: .top))
        .accessibilityLabel(Text("Anruf läuft – zurück zum Anruf mit \(calls.peerName)"))
        .transition(.move(edge: .top).combined(with: .opacity))
    }
}

/// Dauer des verbundenen Anrufs ("12:03"), sonst der Zustand ("Verbinde …", "Klingelt …")
struct CallDurationText: View {
    @ObservedObject var calls: CallManager
    var body: some View {
        if calls.phase == .active, let t0 = calls.connectedAt {
            TimelineView(.periodic(from: t0, by: 1)) { ctx in
                Text(verbatim: CallLogic.duration(Int(ctx.date.timeIntervalSince(t0)))).monospacedDigit()
            }
        } else if !calls.status.isEmpty {
            Text(verbatim: calls.status).lineLimit(1)
        }
    }
}

/// Hinweis in der Unterhaltung, zu der der laufende Anruf gehoert (2.3.1)
struct ChatCallBanner: View {
    @ObservedObject var calls: CallManager
    let chatID: Int
    #if os(macOS)
    @Environment(\.openWindow) private var openWindow
    #endif
    var body: some View {
        if CallLogic.chatHasCall(phase: calls.phase.rawValue, callChat: calls.callChatID, chatID: chatID) {
            HStack(spacing: 10) {
                Image(systemName: calls.media == "video" ? "video.fill" : "phone.fill").foregroundStyle(.green)
                VStack(alignment: .leading, spacing: 1) {
                    Text("Anruf läuft in diesem Chat").font(.subheadline.weight(.semibold))
                    CallDurationText(calls: calls).font(.caption).foregroundStyle(.secondary)
                }
                Spacer()
                Button("Zurück zum Anruf") {
                    #if os(iOS)
                    calls.expand()
                    #else
                    openWindow(id: "call")
                    #endif
                }
                .buttonStyle(.borderedProminent).tint(.green).controlSize(.small)
            }
            .padding(.horizontal, 12).padding(.vertical, 8)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color.green.opacity(0.12))
            Divider()
        }
    }
}

#if os(macOS)
/// Inhalt des Mac-Fensters "Anruf": schliesst sich, wenn nichts mehr laeuft
struct CallWindowContent: View {
    @EnvironmentObject var store: Store
    @Environment(\.dismissWindow) private var dismissWindow
    var body: some View {
        CallWindowBody(calls: store.calls) { dismissWindow(id: "call") }
            .frame(minWidth: 380, idealWidth: 460, minHeight: 520, idealHeight: 640)
            .background { WindowReader { CallPanel.shared.attach(window: $0, calls: store.calls) } }   // 2.4: Schwebefenster
    }
}
private struct CallWindowBody: View {
    @ObservedObject var calls: CallManager
    let close: () -> Void
    var body: some View {
        CallScreen(calls: calls, close: close)
            .onChange(of: calls.phase) { _, p in if p == .idle { close() } }
            .onAppear { if calls.phase == .idle { close() } }
            // Fenster geschlossen = auflegen; ins Dock verkleinert (Schwebefenster uebernimmt) ist KEIN Schliessen
            .onDisappear { if calls.busy && !CallPanel.shared.miniaturizing && !CallPanel.shared.windowHidden { calls.hangup() } }
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
        let at = UserDefaults.standard.double(forKey: "hkCallHangupAt")        // Auflegen n s nach dem Start (z. B. waehrend "Verbinde ...")
        if at > 0 { try? await Task.sleep(for: .seconds(at)); CallEnv.log("test hangup in \(store.calls.phase.rawValue)"); store.calls.hangup() }
    }
}
private extension Double { var nonZero: Double? { self == 0 ? nil : self } }
#endif

// MARK: - 2.4 E4/E5: Kachelansicht und Leiste "Anruf laeuft"

/// Kacheln je Verbindung (iPhone 2x2, iPad/Mac ab 5 Kacheln drei Spalten); Sprecher gruen umrandet
struct CallTileGrid: View {
    @ObservedObject var calls: CallManager
    var body: some View {
        GeometryReader { g in
            let n = max(1, calls.tiles.count)
            let cols = CallRoom.columns(n, wide: g.size.width > 600)
            let rows = (n + cols - 1) / cols
            let sp: CGFloat = 8
            let h = max(90, (g.size.height - sp * CGFloat(rows - 1)) / CGFloat(rows))
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: sp), count: cols), spacing: sp) {
                ForEach(calls.tiles) { t in CallTileView(tile: t) { calls.swapTile(t.id) }.frame(height: h) }
            }
            .accessibilityElement(children: .contain)
            .accessibilityLabel(Text("Teilnehmende"))
        }
    }
}
struct CallTileView: View {
    let tile: CallTile
    var swap: () -> Void = {}
    var body: some View {
        ZStack(alignment: .bottomLeading) {
            Color.white.opacity(0.08)
            if let v = tile.video {
                RTCVideoBox(track: v, fill: !tile.screen)
            } else {
                AvatarView(name: tile.name, avatar: tile.avatar, size: 64).frame(maxWidth: .infinity, maxHeight: .infinity)
            }
            if let s = tile.side {
                Button(action: swap) {
                    ZStack { RTCVideoBox(track: s, fill: true).allowsHitTesting(false); Color.clear }
                        .frame(width: 76, height: 56).clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
                        .overlay(RoundedRectangle(cornerRadius: 8, style: .continuous).strokeBorder(.white.opacity(0.3)))
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain).accessibilityLabel(Text("Ansicht tauschen")).help(Text("Ansicht tauschen"))
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing).padding(6)
            }
            HStack(spacing: 4) {
                if !tile.mic { Image(systemName: "mic.slash.fill").accessibilityHidden(true) }
                Text(verbatim: tile.name).fontWeight(.semibold)
                if !tile.devName.isEmpty { Text(verbatim: tile.devName).opacity(0.8) }
                if tile.companion { Text("Begleitgerät").opacity(0.8) }
            }
            .font(.caption).lineLimit(1).minimumScaleFactor(0.7)
            .padding(.horizontal, 8).padding(.vertical, 4).background(.black.opacity(0.5), in: Capsule()).padding(6)
            if !tile.connected { ProgressView().tint(.white).frame(maxWidth: .infinity, maxHeight: .infinity) }
        }
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous)
            .strokeBorder(tile.speaking ? Color.green : Color.white.opacity(0.15), lineWidth: tile.speaking ? 3 : 1))
        .animation(.easeOut(duration: 0.2), value: tile.speaking)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(Text(verbatim: label))
    }
    private var label: String {
        var p = [tile.name]
        if !tile.devName.isEmpty { p.append(tile.devName) }
        if tile.companion { p.append(String(localized: "Begleitgerät")) }
        if !tile.mic { p.append(String(localized: "stumm")) }
        if tile.speaking { p.append(String(localized: "spricht")) }
        return p.joined(separator: ", ")
    }
}

/// Leiste "Anruf laeuft auf <Geraet>" (Hinzufuegen / Hierher uebernehmen) bzw. "Gruppenanruf laeuft" (Beitreten)
struct CallRunBar: View {
    @ObservedObject var calls: CallManager
    var body: some View {
        if !calls.busy, let x = calls.runBars.first(where: { calls.bar($0) != .none }) {
            let video = x.media == "video"
            HStack(spacing: 8) {
                Image(systemName: video ? "video.fill" : "phone.fill").accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 1) {
                    switch calls.bar(x) {
                    case .elsewhere(let dev, let peer):
                        Text("Anruf läuft auf \(dev.isEmpty ? String(localized: "anderem Gerät") : dev)").font(.subheadline.weight(.semibold))
                        if !peer.isEmpty { Text(verbatim: peer).font(.caption) }
                    case .group(let n):
                        Text("Gruppenanruf läuft · \(n) Geräte").font(.subheadline.weight(.semibold))
                    case .none: EmptyView()
                    }
                    if !x.joinable { Text(verbatim: blocked(x)).font(.caption) }
                }
                .lineLimit(1).minimumScaleFactor(0.75)
                Spacer(minLength: 4)
                if x.joinable {
                    if case .elsewhere = calls.bar(x) {
                        Button(String(localized: "Hinzufügen")) { calls.join(x.id, role: "companion", video: false) }
                            .buttonStyle(.bordered).tint(.white).controlSize(.small)
                            .help(Text("Dieses Gerät als Begleitgerät hinzufügen (ohne Mikrofon und Ton)"))
                        Button(String(localized: "Hierher übernehmen")) { calls.join(x.id, role: "handover", video: video) }
                            .buttonStyle(.borderedProminent).tint(.white).foregroundStyle(.black).controlSize(.small)
                            .help(Text("Anruf ohne Unterbrechung auf dieses Gerät holen"))
                    } else {
                        if video {
                            Button(String(localized: "Nur mit Ton")) { calls.join(x.id, role: "member", video: false) }
                                .buttonStyle(.bordered).tint(.white).controlSize(.small)
                        }
                        Button(String(localized: "Beitreten")) { calls.join(x.id, role: "member", video: video) }
                            .buttonStyle(.borderedProminent).tint(.white).foregroundStyle(.black).controlSize(.small)
                            .help(Text("Dem Gruppenanruf beitreten"))
                    }
                }
            }
            .foregroundStyle(.white)
            .padding(.horizontal, 14).padding(.vertical, 7)
            .frame(maxWidth: .infinity)
            .background(CallBar.green.opacity(0.92).ignoresSafeArea(edges: .top))
            .accessibilityElement(children: .contain)
            .transition(.move(edge: .top).combined(with: .opacity))
        }
    }
    private func blocked(_ x: CallRoom.Running) -> String {
        switch x.block {
        case .old: return String(localized: "Braucht Saga 2.4")
        case .full: return String(localized: "Der Anruf ist voll")
        default: return calls.blockText(x.block, limit: x.limit)
        }
    }
}
