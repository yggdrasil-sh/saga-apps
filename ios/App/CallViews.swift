import SwiftUI
import WebRTC

// Anrufe (Saga 2.1): Oberflaeche - Klingeln (ohne CallKit: Mac, Tests), Gespraech (Video gross, eigenes Bild klein),
// Stumm/Kamera/Lautsprecher/Auflegen, "Ende-zu-Ende verschluesselt" erst nach der Pruefung des DTLS-Fingerabdrucks.
// iPhone/iPad: Vollbild ueber der App; Mac: eigenes Fenster "Anruf" (HlidskjalfApp).

/// 2.5: Verhaeltnis des eigenen Bilds, solange die Kamera noch keins gemeldet hat (iPhone hoch, iPad/Mac quer)
enum SelfPipDefaults {
    static var aspect: Double {
        #if os(iOS)
        return UIDevice.current.userInterfaceIdiom == .phone ? 9.0 / 16 : 4.0 / 3
        #else
        return 16.0 / 9
        #endif
    }
    /// MEETING-SPEC §2.6: call.self.<Geraeteform>.size
    static var sizeKey: String {
        #if os(iOS)
        return UIDevice.current.userInterfaceIdiom == .phone ? "call.self.phone.size" : "call.self.tablet.size"
        #else
        return "call.self.desktop.size"
        #endif
    }
}

/// Zeitpunkt der letzten Bedienung (ohne Neuzeichnen setzbar)
final class PokeClock { var t = Date() }

struct CallScreen: View {
    @ObservedObject var calls: CallManager
    var close: () -> Void = {}
    /// Hoehe der Kopfzeile (Verschluesselung, Ton-Diagnose): das eigene Kamerabild beginnt darunter
    @State private var headerHeight: CGFloat = 0
    /// Hoehe der Steuerknoepfe samt Dauer (Buehne, Teilnehmerliste und Runensiegel enden darueber)
    @State private var controlsHeight: CGFloat = 0
    /// Breite fuer die Knopfreihe (feste Aufteilung statt ViewThatFits: genau eine Reihe im Baum, auch fuer VoiceOver)
    @State private var rowWidth: CGFloat = 0
    /// Farbschema des Systems (2.5: Teilnehmerliste/Runensiegel folgen hell/dunkel, das Anruf-Fenster bleibt dunkel)
    @Environment(\.colorScheme) private var systemScheme
    /// 2.5: Kopf und Knoepfe nach einigen Sekunden ohne Bedienung ausblenden (CallLogic.hideControls)
    @State private var lastPoke = PokeClock()                               // Klasse: Mausbewegung zeichnet nicht neu
    @State private var chromeHidden = false
    /// Mac: Maus ueber Kopf bzw. Knopfleiste (haelt sie sichtbar)
    @State private var overBar = false
    @Environment(\.accessibilityVoiceOverEnabled) private var voiceOver
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    /// MEETING-SPEC §2.6: Layout je Geraet fuer alle Anrufe
    @AppStorage("call.layout") private var layoutRaw = ""
    /// Echo-Rueckfrage (Begleitgeraet)
    @State private var echoAsk: EchoWhat?
    @State private var echoDevice = ""
    #if os(iOS)
    /// I03: iPhone quer (niedrig) - Kopf in einer Zeile, kleinere Knoepfe ohne Textzeile, weniger Rand
    @Environment(\.verticalSizeClass) private var vsc
    private var flat: Bool { vsc == .compact }
    #else
    private let flat = false
    #endif
    /// Oberkante fuer Buehne, Kacheln und eigenes Bild: unter dem Kopf
    private var topInset: CGFloat { CallLogic.stageTop(header: Double(headerHeight), flat: flat) }
    var body: some View {
        // 2.5: Breite vom Fenster, nie vom Inhalt: nach dem Drehen (quer -> hoch) blieb die Knopfreihe sonst auf der
        // Querformat-Breite stehen und schob den ganzen Bildschirm breiter als das Geraet (Knoepfe/eigenes Bild abgeschnitten)
        GeometryReader { g in
            screen.frame(width: g.size.width, height: g.size.height)
                .onChange(of: g.size.width, initial: true) { _, w in rowWidth = max(0, w - 40 + 16) }   // Rand 20 je Seite, Reihe -8 je Seite
        }
        .contentShape(Rectangle())
        .simultaneousGesture(TapGesture().onEnded { poke() })              // Tippen zeigt Kopf und Knoepfe wieder
        #if os(macOS)
        .onContinuousHover { _ in poke() }                                  // Mac: Mausbewegung
        #endif
        .onChange(of: controlsKey) { _, _ in poke() }                       // Bedienung bzw. neuer Zustand
        .background { MeetShortcuts(calls: calls, layoutRaw: $layoutRaw, poke: poke).accessibilityHidden(true) }
        .confirmationDialog(Text("Ist \(echoDevice) im selben Raum? Dann gibt es Echo."), isPresented: Binding(get: { echoAsk != nil }, set: { if !$0 { echoAsk = nil } }),
                            titleVisibility: .visible) {
            Button(String(localized: "Trotzdem einschalten")) { if let w = echoAsk { echoDo(w) }; echoAsk = nil }
            Button(String(localized: "Abbrechen"), role: .cancel) { echoAsk = nil }
        } message: {
            Text("Bitte zuerst an \(echoDevice) Mikrofon und Ton ausschalten.")
        }
        #if os(iOS)
        .persistentSystemOverlays(calls.meet.immersive ? .hidden : .automatic)
        .statusBarHidden(calls.meet.immersive)
        #endif
        .task(id: calls.phase) {                                            // Takt 1 s, solange die Ansicht steht
            while !Task.isCancelled {
                updateChrome()
                try? await Task.sleep(for: .seconds(1))
            }
        }
    }
    /// Bild auf der Buehne (sonst nur Ton: nie ausblenden)
    private var stageVideo: Bool { meetOn && built.tiles.contains { !$0.me && $0.video } }
    /// MEETING-SPEC: Personen und Kacheln aus den Geraeten (alle Wege: 1:1, Mesh, Knoten)
    private var built: Meeting.Built { Meeting.tiles(calls.meetingDevices()) }
    /// Meeting-Ansicht statt Profilbild-Block: Gruppe/Knoten immer, 1:1 sobald das Gegenueber Bild sendet
    private var meetOn: Bool {
        guard calls.phase == .active || calls.phase == .connecting || (calls.horn != nil && calls.phase == .ringingOut) else { return false }
        if calls.horn != nil { return true }
        #if DEBUG
        let demo = calls.meet.demo != nil
        #else
        let demo = false
        #endif
        guard !calls.links.isEmpty || demo else { return false }
        if calls.kind == "group" && !calls.dmRoom { return true }
        let b = built
        return b.tiles.contains { !$0.me && $0.video } || b.tiles.filter { $0.kind != .screen }.count > 2
    }
    private func meetMode(_ b: Meeting.Built) -> Meeting.Mode {
        let n = b.tiles.filter { $0.kind != .screen }.count, s = b.tiles.filter { $0.kind == .screen }.count
        return Meeting.mode(calls.meet.override ?? meetLayoutPref(layoutRaw), tiles: n, screens: s, reduceMotion: reduceMotion)
    }
    /// Offenes Menue bzw. Hinweis (Teilnehmerliste, Runensiegel, Entfernen-Rueckfrage, Bildschirm-Hinweis)
    private var menuOpen: Bool {
        if calls.screenHint { return true }
        if calls.meet.people && !peopleSidebar { return true }
        if let H = calls.horn { return H.plist || H.rpop || H.ppop || H.rmSure != nil }
        return false
    }
    /// Meldungen, die stehen bleiben muessen (Moderation, Wiederverbinden, Statuszeile)
    private var notice: Bool { (calls.mutedBy != nil && !calls.mic) || calls.reconnecting || !calls.status.isEmpty }
    /// Aenderungen, die Kopf und Knoepfe wieder zeigen
    private var controlsKey: [String] {
        [calls.phase.rawValue, "\(calls.mic)", "\(calls.cam)", "\(calls.sharing)", "\(calls.speaker)", "\(calls.sound)", calls.meet.pin ?? "", layoutRaw,
         "\(calls.meet.override?.rawValue ?? "")", "\(calls.meet.page)"]
    }
    private func poke() {
        lastPoke.t = Date()
        if chromeHidden { withAnimation(.easeOut(duration: 0.2)) { chromeHidden = false } }
    }
    private func updateChrome() {
        let idle = Date().timeIntervalSince(lastPoke.t)
        var hide = CallLogic.hideControls(idle: idle, phase: calls.phase.rawValue, video: stageVideo,
                                          voiceOver: voiceOver, menuOpen: menuOpen, notice: notice, pointerOverBar: overBar)
        if calls.meet.immersive { hide = !menuOpen && !voiceOver && idle >= CallLogic.controlsIdle }   // iPad: Vollbild, Leisten aus
        if hide != chromeHidden { withAnimation(reduceMotion ? nil : .easeInOut(duration: 0.2)) { chromeHidden = hide } }
    }
    private var screen: some View {
        ZStack {
            LinearGradient(colors: [Color(red: 0.10, green: 0.09, blue: 0.07), Color(red: 0.04, green: 0.04, blue: 0.05)], startPoint: .top, endPoint: .bottom)
                .ignoresSafeArea()
            if meetOn {
                // MEETING-SPEC: eine Buehne fuer 1:1, Mesh und Knoten - Personen zusammengefasst, Bildschirm ganz sichtbar
                let b = built
                MeetingStage(calls: calls, built: b, mode: meetMode(b), chromeHidden: chromeHidden, top: topInset, bottom: bottomInset,
                             trailing: sideBar ? 64 : 0, sidebar: peopleSidebar ? 336 : 0)
            }
            VStack(spacing: flat ? 6 : 14) {
                VStack(spacing: flat ? 6 : 14) { shareBar; header.opacity(chromeHidden ? 0 : 1).allowsHitTesting(!chromeHidden) }   // 2.5: Hoehe inkl. Leiste "teilt den Bildschirm" (bleibt sichtbar)
                    .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { headerHeight = $0 }
                if !meetOn { Spacer(); peerBlock; Spacer() } else { Spacer() }
                if calls.phase == .incoming { incomingButtons } else if calls.phase == .ended { endedBlock }
                else {
                    controls.opacity(chromeHidden ? 0 : 1).allowsHitTesting(!chromeHidden).accessibilityHidden(chromeHidden)
                        #if os(macOS)
                        .onHover { overBar = $0 }
                        #endif
                }
            }
            .padding(.horizontal, 20).padding(.vertical, flat ? 6 : 24)
            if !meetOn, calls.cam, let t = calls.localVideo, calls.phase != .incoming, calls.phase != .ended {   // sonst in der Buehne
                // 2.5: in eine Ecke ziehen (rastet ein) oder ausblenden - unter der Kopfzeile, ueber den Knoepfen
                SelfPip(aspect: calls.selfAspect, fallback: SelfPipDefaults.aspect, top: topInset, bottom: bottomInset, side: 16) {
                    RTCVideoBox(track: t, mirror: calls.frontCamera, fill: true,   // Rueckkamera nicht spiegeln (Schrift lesbar)
                                onSize: { sz in Task { @MainActor in calls.noteSelfSize(sz) } })
                        .accessibilityLabel(Text("Eigenes Kamerabild"))
                        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                        .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(.white.opacity(0.3)))
                        #if os(iOS)
                        .overlay(alignment: .bottomTrailing) {                // Kamera wechseln am eigenen Bild (Platz fuer den Lautsprecher-Knopf)
                            if calls.canSwitchCamera {
                                Button { calls.switchCamera() } label: {
                                    Image(systemName: "arrow.triangle.2.circlepath.camera").font(.footnote).frame(width: 34, height: 34)
                                        .background(.black.opacity(0.45), in: Circle())
                                }
                                .buttonStyle(.plain).padding(6).accessibilityLabel(Text("Kamera wechseln"))
                            }
                        }
                        #endif
                }
            }
            if meetOn && calls.horn == nil && calls.meet.people {             // Teilnehmerliste (Mesh/1:1)
                if peopleSidebar {
                    MeetPeoplePanel(calls: calls, devices: calls.meetingDevices(), sidebar: true)
                        .environment(\.colorScheme, systemScheme).foregroundStyle(systemScheme == .dark ? Color.white : Color.black)
                        .frame(width: 320).padding(.top, topInset).padding(.bottom, bottomInset).padding(.trailing, 8)
                        .frame(maxWidth: .infinity, alignment: .trailing)
                } else {
                    Color.black.opacity(0.35).ignoresSafeArea().onTapGesture { calls.meet.people = false }
                    MeetPeoplePanel(calls: calls, devices: calls.meetingDevices())
                        .environment(\.colorScheme, systemScheme).foregroundStyle(systemScheme == .dark ? Color.white : Color.black)
                        .padding(.top, topInset - 10).padding(.bottom, bottomInset - 10)
                }
            }
            if sideBar && calls.phase != .incoming && calls.phase != .ended {
                controlRow(spacing: 6, width: 56, vertical: true)
                    .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .trailing)
                    .padding(.trailing, 8)
                    .opacity(chromeHidden ? 0 : 1).allowsHitTesting(!chromeHidden).accessibilityHidden(chromeHidden)
            }
            if calls.screenHint { ScreenHintCard(calls: calls) }
            if let H = calls.horn { HornOverlays(calls: calls, horn: H, scheme: systemScheme, top: topInset - (flat ? 4 : 10), bottom: bottomInset - 10) }
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
                .opacity(chromeHidden ? 0 : 1).allowsHitTesting(!chromeHidden)
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
                .opacity(chromeHidden ? 0 : 1).allowsHitTesting(!chromeHidden)
            }
        }
        #endif
        .foregroundStyle(.white)
        .environment(\.colorScheme, .dark)
        .dynamicTypeSize(...DynamicTypeSize.accessibility3)                 // groesste Stufen: Name/Status passen noch neben die Knoepfe
    }

    /// Abstand unten fuer Buehne und Ebenen: Steuerknoepfe samt Dauer (gemessen) plus Rand
    private var bottomInset: CGFloat { CallLogic.stageBottom(controls: Double(controlsHeight), flat: flat) }

    /// Rote Leiste: "Du teilst deinen Bildschirm" (mit Beenden) bzw. "<Name> teilt den Bildschirm" (mit Tauschen)
    @ViewBuilder private var shareBar: some View {
        // 2.5 (Anruf-UI-Regeln): fremdes Teilen nur als Bildunterschrift am geteilten Bild - kein Banner ueber dem Kopf
        // MEETING-SPEC §5.5: fremdes Teilen als Chip unten links in der Buehne; hier nur die eigene Freigabe (blendet nie aus)
        if calls.sharing {
            HStack(spacing: 10) {
                Image(systemName: "rectangle.inset.filled.on.rectangle")
                VStack(alignment: .leading, spacing: 1) {
                    Text("Du teilst deinen Bildschirm").font(.subheadline.weight(.semibold))
                    if calls.sharing && calls.shareCamPaused { Text("Kamera pausiert (langsame Verbindung)").font(.caption) }
                }
                .lineLimit(1).minimumScaleFactor(0.75)
                Spacer(minLength: 4)
                Button(String(localized: "Teilen beenden")) { calls.stopScreen() }
                    .buttonStyle(.bordered).tint(.white).controlSize(.small)
            }
            .foregroundStyle(.white)
            .padding(.horizontal, 12).padding(.vertical, 3).frame(minHeight: 32)
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
        // I05: auf Video halbtransparente Kapsel hinter den Kopf-Texten (weiss auf hellem Bild sonst kaum lesbar)
        let onVideo = meetOn
        return Group {
            if flat {                                                        // I03: quer eine Zeile, sonst untereinander
                ViewThatFits(in: .horizontal) {
                    HStack(spacing: 10) { headerItems }
                    VStack(spacing: 2) { headerItems }
                }
            } else {
                VStack(spacing: 4) { headerItems }
            }
        }
        .padding(.horizontal, onVideo ? 12 : 0).padding(.vertical, onVideo ? 5 : 0)
        .background { if onVideo { RoundedRectangle(cornerRadius: 18, style: .continuous).fill(.black.opacity(0.38)) } }
        .frame(maxWidth: .infinity)
        #if os(macOS)
        .onHover { overBar = $0 }
        #endif
    }

    @ViewBuilder private var headerItems: some View {
            if meetOn { Text(verbatim: calls.peerName).font(.headline).lineLimit(1).truncationMode(.middle).shadow(radius: 3) }
            if meetOn { statusLine }                                         // Dauer direkt unter dem Titel (Anruf-UI-Regeln)
            if calls.phase != .ended { roomInfo }
            if let H = calls.horn, calls.phase != .ended { HornInfoLine(calls: calls, horn: H) }    // 2.5: Weg, Runensiegel, Schloss
            if calls.horn == nil, calls.phase != .ended { HStack(spacing: 6) {
                if calls.verified {
                    Label("Ende-zu-Ende verschlüsselt", systemImage: "lock.fill").labelStyle(.titleAndIcon)
                } else if calls.phase == .active {
                    Label("Verschlüsselung wird geprüft …", systemImage: "lock.open")
                }
                if calls.relay { Text(verbatim: "·"); Text("über Relais") }
            }
            .font(.caption).foregroundStyle(.white.opacity(0.85)).shadow(radius: 2) }
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
            Group {
                if calls.kind == "group" && !calls.dmRoom && calls.peerAvatar == nil {          // Gruppe: Symbol statt Initialen
                    Image(systemName: "person.3.fill").font(.system(size: 44)).frame(width: 120, height: 120)
                        .background(Color.white.opacity(0.12), in: Circle()).accessibilityHidden(true)
                } else { AvatarView(name: calls.peerName, avatar: calls.peerAvatar, size: 120) }
            }
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
                Text(verbatim: CallLogic.duration(Int(ctx.date.timeIntervalSince(t0)))).font(flat ? .subheadline.monospacedDigit() : .title3.monospacedDigit()).foregroundStyle(.white.opacity(0.85))
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
            if meetOn && calls.kind != "group" && calls.horn == nil && calls.phase == .active && !calls.remote.mic {   // sonst im Profilbild-Block
                Label("\(calls.peerName) ist stummgeschaltet", systemImage: "mic.slash.fill").font(.footnote).shadow(radius: 2)
                    .padding(.horizontal, 12).padding(.vertical, 6).background(.black.opacity(0.45), in: Capsule())
            }
            if let by = calls.mutedBy, !calls.mic, calls.phase == .active {
                Label("\(by) hat dich stummgeschaltet", systemImage: "mic.slash.fill").font(.footnote).shadow(radius: 2)
                    .padding(.horizontal, 12).padding(.vertical, 6).background(.black.opacity(0.55), in: Capsule())
            }
            companionBlock
            if !sideBar {
                let fit = CallLogic.controlFit(count: controlCount, width: Double(rowWidth > 0 ? rowWidth : 320))
                controlRow(spacing: fit.spacing, width: fit.width)
                    .frame(maxWidth: .infinity)
                    .padding(.horizontal, -8)
                    .dynamicTypeSize(...DynamicTypeSize.xxxLarge)           // Bedienungshilfen-Groessen: Auflegen bleibt erreichbar
            }
        }
        .padding(.bottom, 6)
        .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { controlsHeight = $0 }
        .onChange(of: calls.mic) { _, on in if on { calls.mutedBy = nil } }
    }
    /// Zahl der Knoepfe in der Reihe (wie controlRow)
    private var controlCount: Int {
        var n = 3                                                             // Mikrofon, Kamera, Auflegen
        if calls.phase == .active { n += 1 }
        if calls.companion || !calls.sound { n += 1 } else {
            #if os(iOS)
            if UIDevice.current.userInterfaceIdiom == .phone { n += 1 }
            #endif
        }
        if showLayoutButton { n += 1 }
        return n
    }
    /// MEETING-SPEC §2: Layout-Knopf nicht im 1:1 ohne Teilen und ohne zweite Kamera-Kachel
    private var showLayoutButton: Bool {
        guard meetOn, calls.phase == .active else { return false }
        let b = built
        return Meeting.layoutButton(tiles: b.tiles.filter { $0.kind != .screen }.count, screens: b.tiles.filter { $0.kind == .screen }.count)
    }
    private func layoutButton(width: CGFloat) -> some View {
        let b = built
        let cur = calls.meet.override ?? meetLayoutPref(layoutRaw)
        let label = String(localized: "Layout")
        return MeetLayoutMenu(calls: calls, sharing: b.tiles.contains { $0.kind == .screen }) {
            VStack(spacing: 6) {
                Image(systemName: "square.grid.2x2").font(.title3).frame(width: flat ? 48 : 60, height: flat ? 48 : 60)
                    .background(Color.white.opacity(0.18), in: Circle()).foregroundStyle(.white)
                if !flat { buttonLabel(label, width: width) }
            }
        }
        .accessibilityLabel(Text(verbatim: label))
        .accessibilityValue(Text(verbatim: meetLayoutName(cur)))
        .help(Text("Layout (L)"))
    }
    /// MEETING-SPEC §4.3: Begleitgeraet schaltet Mikrofon/Ton ein, waehrend ein anderes eigenes Geraet Mikrofon bzw. Ton an
    /// hat -> erst fragen (Echo). Ohne ask_mute beim Server/Hauptgeraet: Hinweis, dort stummzuschalten.
    enum EchoWhat { case mic, sound }
    private func echoGuard(_ w: EchoWhat) {
        let turningOn = w == .mic ? !calls.mic : !calls.sound
        let other = built.ownOthers.first { $0.mic || !$0.companion }
        if calls.companion, turningOn, let other {
            echoAsk = w; echoDevice = other.devName.isEmpty ? String(localized: "anderes Gerät") : other.devName
            return
        }
        echoDo(w)
    }
    private func echoDo(_ w: EchoWhat) { if w == .mic { calls.toggleMic() } else { calls.toggleSound() } }
    /// MEETING-SPEC §9: Teilnehmerliste ab 900 pt als Seitenleiste rechts (schiebt die Buehne zusammen)
    private var peopleSidebar: Bool { calls.meet.people && calls.horn == nil && rowWidth + 24 >= 900 }
    /// MEETING-SPEC §8: iPhone quer - Knoepfe als Spalte rechts (Buehne bleibt breit)
    private var sideBar: Bool { flat && MeetStyle.phone && meetOn }
    private func controlRow(spacing: CGFloat, width: CGFloat, vertical: Bool = false) -> some View {
        let lay = vertical ? AnyLayout(VStackLayout(spacing: 6)) : AnyLayout(HStackLayout(alignment: .top, spacing: spacing))
        return lay {
            toggle(calls.mic ? "mic.fill" : "mic.slash.fill", calls.mic ? String(localized: "Stumm") : String(localized: "Ton an"), on: !calls.mic, width: width) { echoGuard(.mic) }
                .help(Text("Mikrofon (M)"))
            toggle(calls.cam ? "video.fill" : "video.slash.fill", calls.cam ? String(localized: "Kamera aus") : String(localized: "Kamera an"), on: !calls.cam, width: width) { calls.toggleCam() }
                .help(Text("Kamera (V)"))
            if calls.phase == .active {                                       // 2.4 E3: Bildschirm teilen
                toggle("rectangle.inset.filled.on.rectangle", calls.sharing ? String(localized: "Teilen beenden") : String(localized: "Bildschirm teilen"),
                       on: calls.sharing, width: width) { calls.toggleScreen() }
            }
            if calls.companion || !calls.sound {                              // 2.4: Begleitgeraet - Wiedergabe an/aus (startet stumm)
                toggle(calls.sound ? "speaker.wave.2.fill" : "speaker.slash.fill", calls.sound ? String(localized: "Ton aus") : String(localized: "Ton an"),
                       on: !calls.sound, width: width) { echoGuard(.sound) }
            } else {
                #if os(iOS)
                if UIDevice.current.userInterfaceIdiom == .phone {           // nur iPhone hat eine Hoermuschel; auch bei Video umschaltbar
                    toggle("speaker.wave.2.fill", String(localized: "Lautsprecher"), on: calls.speaker, width: width) { calls.toggleSpeaker() }
                }
                #endif
            }
            if showLayoutButton { layoutButton(width: width) }
            round("phone.down.fill", String(localized: "Auflegen"), .red, width: width) { calls.hangup() }
        }
    }

    /// 2.4: Teilnehmer, Grenze, Personen ohne Saga 2.4, Begleitgeraet
    @ViewBuilder private var roomInfo: some View {
        if calls.kind == "group" || calls.inRoom || calls.tiles.count > 1 || meetOn {
            VStack(spacing: 2) {
                let b = built
                if CallLogic.showsDeviceCount(kind: calls.kind, dmRoom: calls.dmRoom), calls.phase == .active || calls.phase == .connecting || calls.phase == .ringingOut {
                    // MEETING-SPEC §4.1: Personen zaehlen; Geraete erst nahe der Grenze (die bleibt geraete-basiert)
                    let n = calls.horn != nil ? max(1, calls.roster.legs.count) : calls.links.count + 1
                    let p = max(1, b.people)
                    Label { Text(verbatim: n >= calls.limit - 2 ? "\(p) · \(n)/\(calls.limit)" : "\(p)") } icon: { Image(systemName: "person.2.fill") }
                        .font(.caption.weight(.semibold))
                        .padding(.horizontal, 8).padding(.vertical, 2).background(.black.opacity(0.4), in: Capsule())
                        .accessibilityElement(children: .ignore)
                        .accessibilityLabel(n >= calls.limit - 2 ? Text("\(p) Personen, \(n) von höchstens \(calls.limit) Geräten") : Text("\(p) Personen"))
                }
                if calls.phase == .active { MeetCompanionChip(calls: calls, others: b.ownOthers) }
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
            if meetOn { Text(verbatim: calls.status).font(.callout) }
            Button(String(localized: "Schließen")) { calls.dismissEnded(); close() }
                .buttonStyle(.bordered).tint(.white).controlSize(.large)
        }
    }

    private func round(_ icon: String, _ label: String, _ color: Color, width: CGFloat = 72, _ a: @escaping () -> Void) -> some View {
        Button(action: a) {
            VStack(spacing: 6) {
                Image(systemName: icon).font(.title2).frame(width: flat ? 48 : 60, height: flat ? 48 : 60).background(color, in: Circle()).foregroundStyle(.white)
                if !flat { buttonLabel(label, width: width) }           // I03: quer ohne Textzeile (VoiceOver liest die Beschriftung)
            }
        }
        .buttonStyle(.plain).accessibilityLabel(Text(verbatim: label))
        .accessibilityShowsLargeContentViewer { Label(label, systemImage: icon) }
    }
    private func toggle(_ icon: String, _ label: String, on: Bool, width: CGFloat = 72, _ a: @escaping () -> Void) -> some View {
        Button(action: a) {
            VStack(spacing: 6) {
                Image(systemName: icon).font(.title3).frame(width: flat ? 48 : 60, height: flat ? 48 : 60)
                    .background(on ? Color.white : Color.white.opacity(0.18), in: Circle())
                    .foregroundStyle(on ? Color.black : Color.white)
                if !flat { buttonLabel(label, width: width) }
            }
        }
        .buttonStyle(.plain).accessibilityLabel(Text(verbatim: label)).accessibilityAddTraits(on ? .isSelected : [])
        .accessibilityValue(on ? Text("an") : Text("aus"))
        .accessibilityShowsLargeContentViewer { Label(label, systemImage: icon) }
    }
    /// Feste Breite je Knopf, bis zu zwei Zeilen, nie unter 11 pt; alle oben ausgerichtet
    private func buttonLabel(_ label: String, width: CGFloat = 72) -> some View {
        // schmal (iPhone, 5 Knoepfe): 11 pt, damit auch "Lautsprecher" ungetrennt passt; breit: 12 pt
        Text(verbatim: label).font(width >= 80 ? .caption : .caption2).lineLimit(2).multilineTextAlignment(.center)
            .fixedSize(horizontal: false, vertical: true)
            .frame(width: width, alignment: .top)
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
                // Gruppe: alle im Anruf sehen es (ANRUF-UI)
                (calls.kind == "group" && !calls.dmRoom
                    ? Text("Alles, was in der Auswahl sichtbar ist, sehen alle im Anruf – auch Mitteilungen, Passwörter und andere Fenster oder Chats.")
                    : Text("Alles, was in der Auswahl sichtbar ist, sieht dein Gegenüber – auch Mitteilungen, Passwörter und andere Fenster oder Chats."))
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
    private let separateWindow = true                                       // Mac: eigenes Fenster "Anruf"
    private func returnToCall() { openWindow(id: "call") }                  // holt das (offene) Fenster nach vorn
    #else
    private let separateWindow = false
    private func returnToCall() { calls.expand() }
    #endif
    /// Gruene Leisten (Anruf laeuft / laeuft auf anderem Geraet / Gruppenanruf)
    private var bars: some View {
        VStack(spacing: 0) {
            if CallLogic.showsCallBar(phase: calls.phase.rawValue, minimized: calls.minimized, separateWindow: separateWindow) {
                CallBar(calls: calls) { returnToCall() }
            }
            CallRunBar(calls: calls)                                   // 2.4: Anruf laeuft auf anderem Geraet / Gruppenanruf
        }
    }
    func body(content: Content) -> some View {
        #if os(iOS)
        // iPhone/iPad: Leiste UEBER der App statt safeAreaInset - die Navigationsleiste beachtete den Einschub nicht und
        // lag unter der Leiste (Zurueck, Einstellungen usw. nicht tippbar, F9). Inhalt darunter bekommt keine Statusleiste.
        VStack(spacing: 0) {
            bars
            content
        }
        .modifier(rest)
        #else
        content
            .safeAreaInset(edge: .top, spacing: 0) { bars }
            .modifier(rest)
        #endif
    }
    private var rest: CallPresenterRest { CallPresenterRest(calls: calls) }
}

/// Restliche Anruf-Modifikatoren (Vollbild/Fenster, Bild-im-Bild, Hinweise)
private struct CallPresenterRest: ViewModifier {
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

/// Leiste "Anruf laeuft auf <Geraet>" (Hinzufuegen / Hierher uebernehmen) bzw. "Gruppenanruf laeuft" (Beitreten)
struct CallRunBar: View {
    @ObservedObject var calls: CallManager
    var body: some View {
        if !calls.busy, let x = calls.runBars.first(where: { calls.bar($0) != .none }) {
            let video = x.media == "video"
            // Breit: Text und Knoepfe in einer Zeile; schmal/grosse Schrift: Knoepfe darunter. Knoepfe nie umbrechen
            // (".layoutPriority(1)" am Text quetschte sie silbenweise, F9)
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 8) {
                    icon(video)
                    info(x).lineLimit(2)
                    Spacer(minLength: 4)
                    buttons(x, video: video)
                }
                VStack(alignment: .leading, spacing: 6) {
                    HStack(alignment: .firstTextBaseline, spacing: 8) { icon(video); info(x) }
                    HStack(spacing: 8) { Spacer(minLength: 0); buttons(x, video: video) }
                }
                VStack(alignment: .leading, spacing: 6) {                 // sehr grosse Schrift: Knoepfe untereinander
                    HStack(alignment: .firstTextBaseline, spacing: 8) { icon(video); info(x) }
                    VStack(alignment: .trailing, spacing: 6) { buttons(x, video: video) }.frame(maxWidth: .infinity, alignment: .trailing)
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
    private func icon(_ video: Bool) -> some View {
        Image(systemName: video ? "video.fill" : "phone.fill").accessibilityHidden(true)
    }
    @ViewBuilder private func info(_ x: CallRoom.Running) -> some View {
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
        .fixedSize(horizontal: false, vertical: true)
    }
    @ViewBuilder private func buttons(_ x: CallRoom.Running, video: Bool) -> some View {
        if x.joinable {
            if case .elsewhere = calls.bar(x) {
                Button(String(localized: "Hinzufügen")) { calls.join(x.id, role: "companion", video: false) }
                    .buttonStyle(.bordered).tint(.white).controlSize(.small).fixedSize()
                    .help(Text("Dieses Gerät als Begleitgerät hinzufügen (ohne Mikrofon und Ton)"))
                Button(String(localized: "Hierher übernehmen")) { calls.join(x.id, role: "handover", video: video) }
                    .buttonStyle(.borderedProminent).tint(.white).foregroundStyle(.black).controlSize(.small).fixedSize()
                    .help(Text("Anruf ohne Unterbrechung auf dieses Gerät holen"))
            } else {
                if video {
                    Button(String(localized: "Nur mit Ton")) { calls.join(x.id, role: "member", video: false) }
                        .buttonStyle(.bordered).tint(.white).controlSize(.small).fixedSize()
                }
                Button(String(localized: "Beitreten")) { calls.join(x.id, role: "member", video: video) }
                    .buttonStyle(.borderedProminent).tint(.white).foregroundStyle(.black).controlSize(.small).fixedSize()
                    .help(Text("Dem Gruppenanruf beitreten"))
            }
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


/// 2.5: eigenes Bild im Anruf - in eine Ecke ziehen (rastet an der naechsten ein) oder ausblenden (bleibt als kleiner
/// Knopf in der Ecke, Tippen zeigt es wieder). Ecke und Ausblenden merkt sich das Geraet fuer alle Anrufe.
/// top/bottom/side: freier Rand (Kopfzeile oben, Steuerknoepfe unten)
struct SelfPip<Content: View>: View {
    /// 2.5: Seitenverhaeltnis des eigenen Videos (nil = noch keins gemeldet, dann fallback); Groesse: CallLogic.selfPipSize
    var aspect: Double?
    var fallback: Double
    var top: CGFloat
    var bottom: CGFloat
    var side: CGFloat = 16
    /// MEETING-SPEC §7: auf dem Handy in der Praesentation immer klein (S), damit es den Bildschirm nicht verdeckt
    var forceSmall = false
    /// feste Ecke (Handy-Praesentation: unten rechts ueber dem Streifen)
    var forceCorner: Int? = nil
    @ViewBuilder var content: () -> Content
    @AppStorage("hkCallSelfCorner") private var corner = 1        // 0 oben links, 1 oben rechts, 2 unten links, 3 unten rechts
    @AppStorage("hkCallSelfHidden") private var hidden = false
    /// Groesse S/M/L je Geraeteform (Telefon/Tablet/Desktop), Doppeltippen schaltet weiter
    @AppStorage(SelfPipDefaults.sizeKey) private var sizeRaw = Meeting.SelfSize.m.rawValue
    @State private var drag = CGSize.zero
    var body: some View {
        GeometryReader { g in
            // V5: quer (wenig Hoehe) kleiner, damit oben und unten zwei getrennte Ecken bleiben - sonst wirkten die unteren nicht
            let k = forceSmall ? Meeting.SelfSize.s.factor : (Meeting.SelfSize(rawValue: sizeRaw) ?? .m).factor
            let base = CallLogic.selfPipSize(aspect: aspect, fallback: fallback, width: Double(g.size.width), avail: Double(g.size.height - top - bottom))
            let fit = CallLogic.pipSize(w: (base.w * k).rounded(.down), h: (base.h * k).rounded(.down), avail: Double(g.size.height - top - bottom))
            let w = hidden ? 44 : CGFloat(fit.w), h = hidden ? 44 : CGFloat(fit.h)
            let c = forceCorner ?? corner
            let x = c % 2 == 0 ? side : g.size.width - w - side
            let y = c < 2 ? top : max(top, g.size.height - h - bottom)
            Group {
                if hidden {
                    Button { withAnimation(.spring(duration: 0.25)) { hidden = false } } label: {
                        Image(systemName: "person.crop.rectangle").font(.body.weight(.semibold)).frame(width: 44, height: 44)
                            .background(.black.opacity(0.5), in: Circle()).overlay(Circle().strokeBorder(.white.opacity(0.3)))
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(Text("Eigenes Bild zeigen")).help(Text("Eigenes Bild zeigen"))
                } else {
                    content().frame(width: w, height: h)
                        .onTapGesture(count: 2) {
                            withAnimation(.spring(duration: 0.25)) { sizeRaw = (Meeting.SelfSize(rawValue: sizeRaw) ?? .m).next.rawValue }
                        }
                        .overlay(alignment: .topLeading) {
                            Button { withAnimation(.spring(duration: 0.25)) { hidden = true } } label: {
                                Image(systemName: "eye.slash").font(.caption2.weight(.semibold)).frame(width: 22, height: 22)
                                    .background(.black.opacity(0.5), in: Circle())
                                    .padding(4).frame(width: 32, height: 32, alignment: .topLeading).contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel(Text("Eigenes Bild ausblenden")).help(Text("Eigenes Bild ausblenden"))
                        }
                        .shadow(radius: 6)
                }
            }
            .position(x: x + w / 2 + drag.width, y: y + h / 2 + drag.height)
            .gesture(DragGesture(minimumDistance: 6)
                .onChanged { drag = $0.translation }
                .onEnded { e in
                    // V5: Wischen wie FaceTime - Ziel aus der vorhergesagten Endlage (Schwung), nicht aus der Fingerposition
                    let cx = x + w / 2 + e.predictedEndTranslation.width, cy = y + h / 2 + e.predictedEndTranslation.height
                    withAnimation(.spring(duration: 0.25)) {
                        corner = CallLogic.pipCorner(x: Double(cx), y: Double(cy), width: Double(g.size.width), top: Double(top),
                                                     bottom: Double(g.size.height - bottom)); drag = .zero
                    }
                })
            .accessibilityAction(named: Text("Eigenes Bild verschieben")) { corner = (corner + 1) % 4 }
            .accessibilityAction(named: Text("Größe des eigenen Bilds ändern")) { sizeRaw = (Meeting.SelfSize(rawValue: sizeRaw) ?? .m).next.rawValue }
        }
    }
}
