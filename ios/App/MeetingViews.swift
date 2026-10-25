import SwiftUI
import WebRTC

// Meeting-Ansicht (MEETING-SPEC): eine Buehne fuer 1:1, Mesh-Gruppen und Knoten-Anrufe. Personenkacheln (eine je
// Person), Bildschirmkacheln ganz sichtbar (contain, schwarzer Grund, kein Kamera-Bild-im-Bild), Layouts Auto/Galerie/
// Sprecher/Praesentation, Galerie mit Seiten, Streifen rechts bzw. unten, Hinweis-Chip unten links mit x, Anheften,
// Zoom und Vollbild. Logik: MeetingLogic.swift; Zustand je Anruf: CallManager.meet (MeetingCall.swift).

/// Gemeinsame Farben/Masse
enum MeetStyle {
    static let ring = Color(red: 0x56 / 255, green: 0xd3 / 255, blue: 0x64 / 255)
    static let gap: CGFloat = 8
    static var phone: Bool {
        #if os(iOS)
        return UIDevice.current.userInterfaceIdiom == .phone
        #else
        return false
        #endif
    }
}

/// Angezeigter Name einer Kachel ("Du", "Bildschirm von Anna (Android)", "Alex (iPad)")
func meetTitle(_ t: Meeting.Tile) -> String {
    switch t.kind {
    case .screen:
        return t.devLabel.isEmpty ? String(localized: "Bildschirm von \(t.name)") : String(localized: "Bildschirm von \(t.name) (\(t.devLabel))")
    case .camera: return t.devLabel.isEmpty ? t.name : "\(t.name) (\(t.devLabel))"
    case .person: return t.me ? String(localized: "Du") : t.name
    }
}

struct MeetingStage: View {
    @ObservedObject var calls: CallManager
    let built: Meeting.Built
    let mode: Meeting.Mode
    var chromeHidden: Bool
    /// freier Rand bei sichtbaren Leisten (Kopf oben, Knoepfe unten)
    var top: CGFloat
    var bottom: CGFloat
    /// iPhone quer: Knopfspalte rechts
    var trailing: CGFloat = 0
    /// Seitenleiste "Teilnehmende" (ab 900 pt): schiebt die Buehne zusammen, blendet nicht aus
    var sidebar: CGFloat = 0
    @AppStorage("call.showNoVideo") private var showNoVideo = true
    @AppStorage("call.strip") private var stripCollapsed = false
    @AppStorage("hkCallSelfHidden") private var selfHidden = false
    @Environment(\.hornPreviewFill) private var previewFill
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private var tiles: [Meeting.Tile] {
        // eigenes Bild ausgeblendet: auch keine eigene Kachel; Kacheln ohne Video aus: nur Sprechende bleiben
        built.tiles.filter { t in
            if t.me { return !selfHidden }
            if !showNoVideo && mode == .gallery && t.kind == .person && !t.video && !calls.meet.speaking.contains(t.who) && calls.meet.pin != t.id { return false }
            return true
        }
    }
    /// Kacheln ohne Video aus: so viele Personen stehen nicht im Raster ("+ 3 ohne Video")
    private var noVideoHidden: Int {
        func n(_ l: [Meeting.Tile]) -> Int { l.filter { $0.kind == .person && !$0.me && !$0.video }.count }
        return showNoVideo || mode != .gallery ? 0 : n(built.tiles) - n(tiles)
    }

    var body: some View {
        GeometryReader { g in
            let size = g.size
            Group {
                switch mode {
                case .duo: duo(size)
                case .gallery: gallery(size)
                case .speaker, .presentation: stageAndStrip(size)
                }
            }
            .frame(width: size.width, height: size.height)
        }
        .overlay(alignment: .topTrailing) { hornButton }
        .padding(.trailing, (chromeHidden || mode == .duo ? 0 : trailing) + sidebar)
        .onChange(of: built.tiles.map(\.id)) { _, _ in calls.meetCheckPin(built.tiles) }
    }

    // MARK: 1:1 - Gegenueber gross, eigenes Bild schwebend

    @ViewBuilder private func duo(_ size: CGSize) -> some View {
        ZStack {
            if let other = tiles.first(where: { !$0.me }) {
                MeetTileView(calls: calls, tile: other, stage: true, chromeHidden: chromeHidden, videoOK: true, showName: false)   // Name im Kopf
                    .ignoresSafeArea()
            }
            selfPip(top: top, bottom: bottom, small: false)
        }
    }

    // MARK: Buehne + Streifen (Sprecher, Praesentation)

    private func stageAndStrip(_ size: CGSize) -> some View {
        let place = Meeting.strip(width: Double(size.width), height: Double(size.height), presentation: mode == .presentation)
        let rightStrip = place.side == .right
        // eigenes Bild: schwebt nur bis 2 Personen und nie ueber einem Bildschirm - Streifen rechts: in den Streifen
        let floats = Meeting.selfFloats(mode, people: built.people) && !(mode == .presentation && rightStrip)
        let stageID = Meeting.stage(mode, tiles: tiles, pin: calls.meet.pin, speaker: calls.meet.stage)
        let rest = Meeting.strip(tiles, stage: stageID, selfFloats: floats)
        let stageTile = tiles.first { $0.id == stageID }
        let collapsed = stripCollapsed && !rest.isEmpty
        let s = CGFloat(place.size)
        let topPad: CGFloat = chromeHidden ? 4 : top, botPad: CGFloat = chromeHidden ? 4 : bottom
        let stage = Group {
            if let t = stageTile {
                MeetTileView(calls: calls, tile: t, stage: true, chromeHidden: chromeHidden, videoOK: true)
            } else { Color.black }
        }
        .overlay {
            if let t = stageTile, t.kind == .screen {
                GeometryReader { sg in
                    shareChip(t).frame(maxWidth: sg.size.width * (sg.size.width < 600 ? 0.8 : 0.5), alignment: .leading)
                        .padding(12).padding(.bottom, rightStrip && mode == .presentation ? bottom - 12 : 0)
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomLeading)
                }
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityLabel(Text("Bühne: \(stageTile.map(meetTitle) ?? "")"))
        return Group {
            if rightStrip {
                HStack(spacing: MeetStyle.gap) {
                    stage.padding(.top, mode == .presentation ? 0 : topPad).padding(.bottom, mode == .presentation ? 0 : botPad)
                    if !rest.isEmpty {
                        strip(rest, side: .right, size: s, collapsed: collapsed)
                            .frame(width: collapsed ? 8 : s)
                            .padding(.top, topPad).padding(.bottom, botPad)
                    }
                }
                .padding(.horizontal, 6)
            } else {
                VStack(spacing: MeetStyle.gap) {
                    stage.padding(.top, mode == .presentation ? 0 : topPad)
                    if !rest.isEmpty {
                        strip(rest, side: .bottom, size: s, collapsed: collapsed)
                            .frame(height: collapsed ? 8 : s)
                    }
                }
                .padding(.bottom, botPad).padding(.horizontal, 6)
            }
        }
        .overlay {
            // Handy-Praesentation: klein (S) in der Ecke ueber dem Streifen, nie ueber dem Bildschirm-Inhalt oben
            if floats { selfPip(top: top, bottom: (rightStrip ? bottom : botPad + s + MeetStyle.gap) + 4, small: !rightStrip && mode == .presentation) }
        }
    }

    private func strip(_ list: [Meeting.Tile], side: Meeting.Side, size: CGFloat, collapsed: Bool) -> some View {
        let allowed = Meeting.videoAllowed(list, max: Meeting.stripVideos(phone: MeetStyle.phone), pin: calls.meet.pin, speaker: calls.meet.stage, recent: calls.meet.recent)
        return ZStack(alignment: side == .right ? .leading : .top) {
            if collapsed {
                // eingeklappt: schmale Leiste in der Sprechring-Farbe, wenn jemand im Streifen spricht
                let talking = list.contains { calls.meet.speaking.contains($0.who) }
                RoundedRectangle(cornerRadius: 4).fill(talking ? MeetStyle.ring : Color.white.opacity(0.25))
            } else {
                ScrollView(side == .right ? .vertical : .horizontal, showsIndicators: false) {
                    let items = ForEach(list) { t in
                        MeetTileView(calls: calls, tile: t, compact: true, chromeHidden: chromeHidden, videoOK: allowed.contains(t.id))
                            .frame(width: side == .right ? size : size * 16 / 9, height: side == .right ? size * 9 / 16 : size)
                            // Antippen = auf die Buehne (anheften); erstes Tippen bei ausgeblendeten Leisten zeigt nur die Leisten
                            .onTapGesture { if !chromeHidden && !t.me && calls.meet.pin != t.id { calls.meetPin(t.id) } }
                            .accessibilityAction(named: Text("Auf die Bühne")) { calls.meetPin(t.id) }
                    }
                    if side == .right { VStack(spacing: MeetStyle.gap) { items } } else { HStack(spacing: MeetStyle.gap) { items } }
                }
                .accessibilityElement(children: .contain)
                .accessibilityLabel(Text("Teilnehmende, \(list.count)"))
            }
        }
        .overlay(alignment: side == .right ? .leading : .top) {
            // Griff zum Ein-/Ausklappen (44 pt), blendet mit den Leisten aus
            Button { withAnimation(reduceMotion ? nil : .easeOut(duration: 0.2)) { stripCollapsed.toggle() } } label: {
                Image(systemName: side == .right ? (collapsed ? "chevron.left" : "chevron.right") : (collapsed ? "chevron.up" : "chevron.down"))
                    .font(.caption.weight(.bold)).frame(width: 28, height: 28).background(.black.opacity(0.55), in: Circle())
                    .frame(width: 44, height: 44).contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .offset(x: side == .right ? -26 : 0, y: side == .bottom ? -26 : 0)
            .opacity(chromeHidden ? 0 : 1).allowsHitTesting(!chromeHidden)
            .accessibilityLabel(collapsed ? Text("Streifen ausklappen") : Text("Streifen einklappen"))
            .help(collapsed ? Text("Streifen ausklappen") : Text("Streifen einklappen"))
        }
    }

    // MARK: Galerie mit Seiten

    @ViewBuilder private func gallery(_ size: CGSize) -> some View {
        let topPad: CGFloat = chromeHidden ? 6 : top, botPad: CGFloat = chromeHidden ? 6 : bottom
        let w = Double(size.width - 12), h = Double(max(60, size.height - topPad - botPad - 26))
        let all = calls.meetOrder.update(tiles, pin: calls.meet.pin, recent: Set(calls.meet.recent))   // stabil (kein Springen)
        let g0 = Meeting.grid(n: all.count, width: w, height: h)
        let pages = Meeting.pages(all.count, perPage: g0.perPage)
        let page = min(calls.meet.page, pages - 1)
        let _ = { calls.meetPages = pages }()                                // fuer die Tastenkuerzel [ ]
        let list = Meeting.page(all, page: page, perPage: g0.perPage)
        let g = Meeting.grid(n: list.count, width: w, height: h)
        let allowed = Meeting.videoAllowed(list, max: g.videoMax, pin: calls.meet.pin, speaker: calls.meet.stage, recent: calls.meet.recent)
        VStack(spacing: 6) {
            galleryRows(list, cols: max(1, g.cols), w: CGFloat(w), h: CGFloat(h), allowed: allowed)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .contentShape(Rectangle())
                .gesture(DragGesture(minimumDistance: 40).onEnded { e in
                    if e.translation.width < -60 { turn(page + 1, pages) } else if e.translation.width > 60 { turn(page - 1, pages) }
                })
                .overlay { if pages > 1 { pageArrows(page, pages) } }
            if pages > 1 { pageDots(page, pages) } else if noVideoHidden > 0 { noVideoChip } else { Color.clear.frame(height: 20) }
        }
        .padding(.top, topPad).padding(.bottom, botPad).padding(.horizontal, 6)
        .overlay(alignment: .bottomLeading) { galleryHint.padding(.bottom, botPad + 28).padding(.leading, 12) }
    }
    private func galleryRows(_ list: [Meeting.Tile], cols: Int, w: CGFloat, h: CGFloat, allowed: Set<String>) -> some View {
        let rows = max(1, (list.count + cols - 1) / cols), sp = MeetStyle.gap
        let cw = (w - sp * CGFloat(cols - 1)) / CGFloat(cols), ch = (h - sp * CGFloat(rows - 1)) / CGFloat(rows)
        // Kachel hoechstens 16:9 breit; hochkant hoechstens 3:4 (Handy) bzw. quadratisch (Tablet/Desktop), mittig
        let tw = min(cw, ch * 16 / 9), th = min(ch, tw * (MeetStyle.phone ? 4.0 / 3 : 1))
        return VStack(spacing: sp) {
            ForEach(0..<rows, id: \.self) { r in
                HStack(spacing: sp) {
                    ForEach(list[(r * cols)..<min(list.count, r * cols + cols)]) { t in
                        MeetTileView(calls: calls, tile: t, chromeHidden: chromeHidden, videoOK: allowed.contains(t.id))
                            .frame(width: tw, height: th)
                    }
                }
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityLabel(Text("Teilnehmende, \(tiles.count)"))
    }
    private func turn(_ p: Int, _ n: Int) {
        let q = min(max(0, p), n - 1)
        guard q != calls.meet.page else { return }
        withAnimation(reduceMotion ? nil : .easeOut(duration: 0.2)) { calls.meet.page = q }
    }
    private func pageArrows(_ p: Int, _ n: Int) -> some View {
        HStack {
            arrow("chevron.left", Text("Vorherige Seite"), enabled: p > 0) { turn(p - 1, n) }
            Spacer()
            arrow("chevron.right", Text("Nächste Seite"), enabled: p < n - 1) { turn(p + 1, n) }
        }
        .opacity(chromeHidden ? 0 : 1).allowsHitTesting(!chromeHidden)
    }
    private func arrow(_ icon: String, _ label: Text, enabled: Bool, _ a: @escaping () -> Void) -> some View {
        Button(action: a) {
            Image(systemName: icon).font(.title3.weight(.semibold)).frame(width: 48, height: 48).background(.black.opacity(0.5), in: Circle())
        }
        .buttonStyle(.plain).opacity(enabled ? 1 : 0).disabled(!enabled)
        .accessibilityLabel(label).help(label)
    }
    private func pageDots(_ p: Int, _ n: Int) -> some View {
        HStack(spacing: 6) {
            ForEach(0..<n, id: \.self) { i in Circle().fill(i == p ? Color.white : Color.white.opacity(0.35)).frame(width: 7, height: 7) }
        }
        .frame(height: 20)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text("Seite \(p + 1) von \(n)"))
        .accessibilityAdjustableAction { d in turn(d == .increment ? p + 1 : p - 1, n) }
    }
    private var noVideoChip: some View {
        Text("+ \(noVideoHidden) ohne Video").font(.caption.weight(.semibold))
            .padding(.horizontal, 10).padding(.vertical, 3).background(Color.white.opacity(0.16), in: Capsule())
            .frame(height: 20)
    }

    // MARK: Hinweise

    /// Bildschirm-Hinweis als Bildunterschrift unten links in der Buehne (blendet mit den Leisten aus, x = fuer diese Freigabe weg)
    @ViewBuilder private func shareChip(_ t: Meeting.Tile) -> some View {
        if !calls.meet.hintGone.contains(t.id) {
            HStack(spacing: 2) {
                Image(systemName: "rectangle.inset.filled.on.rectangle").accessibilityHidden(true)
                Text("\(t.devLabel.isEmpty ? t.name : "\(t.name) (\(t.devLabel))") teilt den Bildschirm").lineLimit(1).truncationMode(.middle)
                Button { calls.meet.hintGone.insert(t.id) } label: {
                    Image(systemName: "xmark").font(.caption2.weight(.bold)).frame(width: 44, height: 44).contentShape(Rectangle())
                }
                .buttonStyle(.plain).padding(.vertical, -6)
                .accessibilityLabel(Text("Hinweis ausblenden"))
            }
            .font(.caption.weight(.semibold)).foregroundStyle(.white)
            .padding(.leading, 10).frame(height: 32)
            .background(.black.opacity(0.7), in: Capsule())
            .opacity(chromeHidden ? 0 : 1).allowsHitTesting(!chromeHidden)
        }
    }
    /// Galerie: jemand teilt -> einmalig "Anna teilt den Bildschirm - Anzeigen" (schaltet fuer diesen Anruf auf Praesentation)
    @ViewBuilder private var galleryHint: some View {
        if let t = built.tiles.first(where: { $0.kind == .screen && !calls.meet.hintGone.contains("g:" + $0.id) }) {
            HStack(spacing: 8) {
                Image(systemName: "rectangle.inset.filled.on.rectangle").accessibilityHidden(true)
                Text("\(t.name) teilt den Bildschirm").lineLimit(1)
                Button(String(localized: "Anzeigen")) {
                    calls.meet.override = .presentation; calls.meet.hintGone.insert("g:" + t.id)
                }
                .buttonStyle(.borderedProminent).tint(.white).foregroundStyle(.black).controlSize(.small)
                Button { calls.meet.hintGone.insert("g:" + t.id) } label: {
                    Image(systemName: "xmark").font(.caption2.weight(.bold)).frame(width: 32, height: 32).contentShape(Rectangle())
                }
                .buttonStyle(.plain).accessibilityLabel(Text("Hinweis ausblenden"))
            }
            .font(.caption.weight(.semibold)).padding(.leading, 12).padding(.trailing, 4).padding(.vertical, 4)
            .background(.black.opacity(0.75), in: Capsule())
        }
    }

    // MARK: eigenes Bild, Knoten-Knopf

    @ViewBuilder private func selfPip(top: CGFloat, bottom: CGFloat, small: Bool) -> some View {
        if calls.cam, calls.localVideo != nil || previewFill, calls.phase != .incoming, calls.phase != .ended {
            SelfPip(aspect: calls.selfAspect, fallback: SelfPipDefaults.aspect, top: top, bottom: bottom, side: 16, forceSmall: small, forceCorner: small ? 3 : nil) {
                Group {
                    if let t = calls.localVideo {
                        RTCVideoBox(track: t, mirror: calls.frontCamera, fill: true, onSize: { sz in Task { @MainActor in calls.noteSelfSize(sz) } })
                    } else { HornPreviewPicture(seed: "self") }
                }
                .accessibilityLabel(Text("Eigenes Kamerabild"))
                .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(Color.white.opacity(0.2)))
                .overlay(alignment: .bottomLeading) {
                    Text("Du").font(.caption2.weight(.semibold)).padding(.horizontal, 6).padding(.vertical, 2)
                        .background(.black.opacity(0.55), in: Capsule()).padding(6).accessibilityHidden(true)
                }
                #if os(iOS)
                .overlay(alignment: .bottomTrailing) {
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
    }
    /// Teilnehmerliste (oben rechts, blendet mit den Leisten aus); Knoten: Liste mit Moderation
    @ViewBuilder private var hornButton: some View {
        Group {
            if let H = calls.horn { HornPeopleButton(horn: H) }
            else if mode != .duo {
                Button { calls.meet.people.toggle() } label: {
                    Image(systemName: "person.2.fill").frame(width: 36, height: 36)
                        .background(calls.meet.people ? Color.white.opacity(0.35) : Color.black.opacity(0.45), in: Circle())
                        .frame(width: 44, height: 44).contentShape(Rectangle())
                }
                .buttonStyle(.plain).foregroundStyle(.white)
                .accessibilityLabel(Text("Teilnehmende")).accessibilityAddTraits(calls.meet.people ? .isSelected : []).help(Text("Teilnehmende (⇧⌘P)"))
            }
        }
        .padding(.top, top - 50 > 0 ? top - 50 : 4).padding(.trailing, 6)
        .opacity(chromeHidden ? 0 : 1).allowsHitTesting(!chromeHidden)
    }
}

private struct HornPeopleButton: View {
    @ObservedObject var horn: HornSession
    var body: some View {
        Button { horn.plist.toggle() } label: {
            Image(systemName: "person.2.fill").frame(width: 36, height: 36)
                .background(horn.plist ? Color.white.opacity(0.35) : Color.black.opacity(0.45), in: Circle())
                .frame(width: 44, height: 44).contentShape(Rectangle())
        }
        .buttonStyle(.plain).foregroundStyle(.white)
        .accessibilityLabel(Text("Teilnehmende")).accessibilityAddTraits(horn.plist ? .isSelected : []).help(Text("Teilnehmende"))
    }
}


// MARK: - Kachel

struct MeetTileView: View {
    @ObservedObject var calls: CallManager
    let tile: Meeting.Tile
    var compact = false
    /// auf der Buehne (gross): Bildschirm schwarz, ohne Rahmen, Namensleiste blendet mit den Leisten aus
    var stage = false
    var chromeHidden = false
    /// Video zeigen (Hoechstzahl gleichzeitig dekodierter Videos)
    var videoOK = true
    var showName = true
    @Environment(\.hornPreviewFill) private var previewFill
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var base = 1.0
    @State private var pan = CGSize.zero
    @State private var panBase = CGSize.zero
    @State private var hover = false

    private var speaking: Bool { calls.meet.speaking.contains(tile.who) && tile.kind != .screen }
    private var pinned: Bool { calls.meet.pin == tile.id }
    private var screenStage: Bool { stage && tile.kind == .screen }

    var body: some View {
        GeometryReader { g in
            ZStack(alignment: .bottomLeading) {
                (tile.kind == .screen ? Color.black : Color.white.opacity(0.08))
                content
                    .scaleEffect(screenStage ? calls.meet.zoom : 1)
                    .offset(screenStage ? pan : .zero)
                    .frame(width: g.size.width, height: g.size.height).clipped()
                if !screenStage && showName { nameBar.opacity(stage && chromeHidden ? 0 : 1) }   // Bildschirm: Chip unten links statt Namensleiste
            }
            .gesture(screenStage ? zoomGesture(g.size) : nil)
            .simultaneousGesture(screenStage && calls.meet.zoom > 1 ? panGesture(g.size) : nil)
        }
        .clipShape(RoundedRectangle(cornerRadius: screenStage ? 0 : 14, style: .continuous))
        .overlay {
            if !screenStage {
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .strokeBorder(speaking ? MeetStyle.ring : Color.white.opacity(0.15), lineWidth: speaking ? 3 : 1)
            }
        }
        .overlay(alignment: .bottomLeading) { if screenStage && calls.meet.zoom > 1.01 { zoomChip } }
        #if os(macOS)
        .overlay(alignment: .topTrailing) {                                   // Mac: Kachelmenue auch ueber "..." beim Zeigen
            if hover && !tile.me {
                Menu { menu } label: {
                    Image(systemName: "ellipsis").font(.callout.weight(.bold)).frame(width: 28, height: 28).background(.black.opacity(0.6), in: Circle())
                }
                .menuIndicator(.hidden).buttonStyle(.plain).fixedSize().padding(6)
                .accessibilityLabel(Text("Kachelmenü"))
            }
        }
        .onHover { hover = $0 }
        #endif
        .animation(reduceMotion ? nil : .easeOut(duration: 0.2), value: speaking)
        .contentShape(Rectangle())
        .onTapGesture(count: 2) { if screenStage { meetToggleFullScreen(calls) } else if !tile.me { calls.meetPin(tile.id) } }
        .contextMenu { menu }
        .accessibilityElement(children: .combine)
        .accessibilityLabel(Text(verbatim: label))
        .accessibilityAddTraits(tile.me ? [] : .isButton)
        .accessibilityAction(named: pinned ? Text("Lösen") : Text("Für mich anheften")) { calls.meetPin(tile.id) }
    }

    @ViewBuilder private var content: some View {
        let track = videoOK && tile.video ? calls.meetTrack(tile.dev, screen: tile.kind == .screen) : nil
        if let track {
            RTCVideoBox(track: track, mirror: tile.me && calls.frontCamera, fill: tile.kind != .screen)
        } else if previewFill && videoOK && tile.video {
            if tile.kind == .screen { HornPreviewScreen().aspectRatio(16.0 / 10, contentMode: .fit) } else { HornPreviewPicture(seed: tile.id) }
        } else if tile.kind == .screen {
            VStack(spacing: 6) {                                              // nie eine schwarze Kachel
                Image(systemName: "rectangle.inset.filled.on.rectangle").font(compact ? .title3 : .largeTitle)
                if tile.screenMissing { Text("Über den Knoten nicht verfügbar").font(.caption).multilineTextAlignment(.center) }
            }
            .foregroundStyle(.white.opacity(0.75)).padding(8)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else {
            AvatarView(name: tile.name, avatar: tile.avatar, size: compact ? 40 : stage ? 120 : 64)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }

    private var nameBar: some View {
        HStack(spacing: 4) {
            if !tile.mic && tile.kind != .screen { Image(systemName: "mic.slash.fill") }
            if speaking { Image(systemName: "speaker.wave.2.fill").foregroundStyle(MeetStyle.ring) }   // nicht nur Farbe
            if tile.kind == .screen { Image(systemName: "rectangle.inset.filled.on.rectangle") }
            if pinned { Image(systemName: "pin.fill") }
            Text(verbatim: meetTitle(tile)).fontWeight(.semibold).lineLimit(1).truncationMode(.tail)
        }
        .font(compact ? .caption2 : .caption)
        .padding(.horizontal, 8).padding(.vertical, 4).background(.black.opacity(0.7), in: Capsule()).padding(compact ? 4 : 6)
        .accessibilityHidden(true)
    }

    @ViewBuilder private var menu: some View {
        if !tile.me {
            Button { calls.meetPin(tile.id) } label: {
                pinned ? Label("Lösen", systemImage: "pin.slash") : Label("Für mich anheften", systemImage: "pin")
            }
        }
        if tile.kind == .screen {
            Button { calls.meetPin(tile.id); meetToggleFullScreen(calls) } label: { Label("Vollbild", systemImage: "arrow.up.left.and.arrow.down.right") }
        }
    }

    private var zoomChip: some View {
        HStack(spacing: 8) {
            Text(verbatim: "\(Int((calls.meet.zoom * 100).rounded())) %").monospacedDigit()
            Button(String(localized: "Zurücksetzen")) { calls.meet.zoom = 1; pan = .zero; panBase = .zero; base = 1 }
                .buttonStyle(.bordered).tint(.white).controlSize(.small)
        }
        .font(.caption.weight(.semibold)).padding(.horizontal, 10).padding(.vertical, 4)
        .background(.black.opacity(0.7), in: Capsule()).padding(12).padding(.bottom, 40)
    }
    private func zoomGesture(_ size: CGSize) -> some Gesture {
        MagnifyGesture()
            .onChanged { v in calls.meet.zoom = Meeting.zoom(base * v.magnification) }
            .onEnded { _ in
                base = calls.meet.zoom
                if calls.meet.zoom <= 1.01 { pan = .zero; panBase = .zero }
            }
    }
    private func panGesture(_ size: CGSize) -> some Gesture {
        DragGesture(minimumDistance: 4)
            .onChanged { v in
                let p = Meeting.pan((x: Double(panBase.width + v.translation.width), y: Double(panBase.height + v.translation.height)),
                                    zoom: calls.meet.zoom, size: (w: Double(size.width), h: Double(size.height)))
                pan = CGSize(width: p.x, height: p.y)
            }
            .onEnded { _ in panBase = pan }
    }

    private var label: String {
        var p = [meetTitle(tile)]
        if tile.kind != .screen {
            if speaking { p.append(String(localized: "spricht")) }
            if !tile.mic { p.append(String(localized: "stumm")) }
            p.append(tile.video ? String(localized: "Kamera ist an") : String(localized: "keine Kamera"))
        }
        if pinned { p.append(String(localized: "angeheftet")) }
        return p.joined(separator: ", ")
    }
}

/// Vollbild: Mac echtes Vollbild-Fenster, iPad/iPhone Leisten und System-Overlays aus
@MainActor func meetToggleFullScreen(_ calls: CallManager) {
    #if os(macOS)
    (NSApp.keyWindow ?? NSApp.mainWindow)?.toggleFullScreen(nil)
    #else
    calls.meet.immersive.toggle()
    #endif
}

// MARK: - Layout-Knopf (Bedienleiste, rechts vom Lautsprecher, vor Auflegen)

struct MeetLayoutMenu<Label: View>: View {
    @ObservedObject var calls: CallManager
    var sharing: Bool
    @ViewBuilder var label: () -> Label
    @AppStorage("call.layout") private var layoutRaw = ""
    @AppStorage("call.showNoVideo") private var showNoVideo = true
    @AppStorage("hkCallSelfHidden") private var selfHidden = false
    var body: some View {
        Menu {
            Picker(selection: Binding(get: { meetLayoutPref(layoutRaw) }, set: { layoutRaw = $0.rawValue; calls.meet.override = nil })) {
                Text("Auto").tag(Meeting.Layout.auto)
                Text("Galerie").tag(Meeting.Layout.gallery)
                Text("Sprecher").tag(Meeting.Layout.speaker)
                Text("Präsentation").tag(Meeting.Layout.presentation).disabled(!sharing)
            } label: { Text("Layout") }
            .pickerStyle(.inline)
            Divider()
            Toggle("Kacheln ohne Video zeigen", isOn: $showNoVideo)
            Toggle("Eigenes Bild zeigen", isOn: Binding(get: { !selfHidden }, set: { selfHidden = !$0 }))
        } label: { label() }
        .menuIndicator(.hidden)
        .buttonStyle(.plain)
    }
}
/// Gespeichertes Layout (call.layout, sonst die alte Knoten-Einstellung saga-horn-view)
func meetLayoutPref(_ raw: String) -> Meeting.Layout {
    Meeting.layout(raw.isEmpty ? nil : raw, legacy: UserDefaults.standard.string(forKey: "saga-horn-view"))
}
func meetLayoutName(_ l: Meeting.Layout) -> String {
    switch l {
    case .auto: return String(localized: "Auto")
    case .gallery: return String(localized: "Galerie")
    case .speaker: return String(localized: "Sprecher")
    case .presentation: return String(localized: "Präsentation")
    }
}

// MARK: - Eigene Begleitgeraete (Kopf)

struct MeetCompanionChip: View {
    @ObservedObject var calls: CallManager
    let others: [Meeting.Device]
    var body: some View {
        if calls.companion {
            // auf dem Begleitgeraet: wer das Hauptgeraet ist
            let main = others.first { !$0.companion }
            Text(main.map { String(localized: "Begleitgerät · Hauptgerät \($0.devName.isEmpty ? String(localized: "anderes Gerät") : $0.devName)") } ?? String(localized: "Begleitgerät"))
                .font(.caption.weight(.semibold)).padding(.horizontal, 8).padding(.vertical, 2).background(.black.opacity(0.4), in: Capsule())
        } else if !others.isEmpty {
            let names = others.map { $0.devName.isEmpty ? String(localized: "Gerät") : $0.devName }.joined(separator: ", ")
            Menu {
                ForEach(others, id: \.device) { d in
                    Button(role: .destructive) { calls.meetDropOwn(d.device) } label: {
                        Text("\(d.devName.isEmpty ? String(localized: "Gerät") : d.devName) trennen")
                    }
                }
            } label: {
                Label("\(names) verbunden", systemImage: "iphone.and.arrow.forward")
                    .font(.caption.weight(.semibold)).lineLimit(1)
                    .padding(.horizontal, 8).padding(.vertical, 2).background(.black.opacity(0.4), in: Capsule())
                    .padding(.vertical, 10).contentShape(Rectangle()).padding(.vertical, -10)
            }
            .menuIndicator(.hidden).buttonStyle(.plain)
            .accessibilityLabel(Text("Eigene Begleitgeräte: \(names)"))
        }
    }
}

// MARK: - Tastenkuerzel (Mac, iPad mit Tastatur; MEETING-SPEC §6.3)

struct MeetShortcuts: View {
    @ObservedObject var calls: CallManager
    @Binding var layoutRaw: String
    var poke: () -> Void
    var body: some View {
        ZStack {
            key("m", []) { calls.toggleMic() }
            key("m", [.command, .shift]) { calls.toggleMic() }
            key("v", []) { calls.toggleCam() }
            key("v", [.command, .shift]) { calls.toggleCam() }
            key("s", []) { if calls.phase == .active { calls.toggleScreen() } }
            key("g", []) { setLayout(.gallery) }
            key("p", []) { setLayout(.speaker) }
            key("a", []) { setLayout(.auto) }
            key("f", []) { meetToggleFullScreen(calls) }
            key("[", []) { calls.meet.page = max(0, calls.meet.page - 1) }
            key("]", []) { calls.meet.page = min(calls.meetPages - 1, calls.meet.page + 1) }
            key(.pageUp, []) { calls.meet.page = max(0, calls.meet.page - 1) }
            key(.pageDown, []) { calls.meet.page = min(calls.meetPages - 1, calls.meet.page + 1) }
            key("=", [.command]) { calls.meet.zoom = Meeting.zoom(calls.meet.zoom + 0.25) }
            key("+", [.command]) { calls.meet.zoom = Meeting.zoom(calls.meet.zoom + 0.25) }
            key("-", [.command]) { calls.meet.zoom = Meeting.zoom(calls.meet.zoom - 0.25) }
            key("0", [.command]) { calls.meet.zoom = 1 }
            key(.escape, []) { escape() }
            key("p", [.command, .shift]) { if let H = calls.horn { H.plist.toggle() } else { calls.meet.people.toggle() } }
        }
        .frame(width: 0, height: 0).opacity(0).allowsHitTesting(false)
    }
    private func setLayout(_ l: Meeting.Layout) { layoutRaw = l.rawValue; calls.meet.override = nil }
    /// Esc: Pin loesen, Vollbild verlassen, Zoom zuruecksetzen (in dieser Reihenfolge)
    private func escape() {
        if calls.meet.pin != nil { calls.meetPin(nil); return }
        if calls.meet.people { calls.meet.people = false; return }
        #if os(macOS)
        if let w = NSApp.keyWindow, w.styleMask.contains(.fullScreen) { w.toggleFullScreen(nil); return }
        #else
        if calls.meet.immersive { calls.meet.immersive = false; return }
        #endif
        if calls.meet.zoom != 1 { calls.meet.zoom = 1 }
    }
    private func key(_ k: KeyEquivalent, _ m: EventModifiers, _ a: @escaping () -> Void) -> some View {
        Button { a(); poke() } label: { EmptyView() }.keyboardShortcut(k, modifiers: m)
    }
}

// MARK: - Teilnehmerliste (Mesh und 1:1; Knoten: HornPeoplePanel mit Moderation) - ab 900 pt als Seitenleiste

struct MeetPeoplePanel: View {
    @ObservedObject var calls: CallManager
    let devices: [Meeting.Device]
    /// Seitenleiste (ab 900 pt) statt Karte
    var sidebar = false
    private struct Person: Identifiable { var id: String; var name: String; var avatar: String?; var me: Bool; var devs: [Meeting.Device] }
    private var people: [Person] {
        var order: [String] = [], by: [String: [Meeting.Device]] = [:]
        for d in devices { if by[d.who] == nil { order.append(d.who) }; by[d.who, default: []].append(d) }
        return order.map { w in
            let l = by[w] ?? []
            let main = l.first { !$0.companion } ?? l[0]
            return Person(id: w, name: main.name, avatar: l.compactMap(\.avatar).first, me: l.contains { $0.here }, devs: l)
        }
    }
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Text("Im Anruf (\(people.count))").font(.headline)
                Spacer()
                Button { calls.meet.people = false } label: { Image(systemName: "xmark").frame(width: 44, height: 44).contentShape(Rectangle()) }
                    .buttonStyle(.plain).accessibilityLabel(Text("Schließen")).padding(-7)
            }
            .padding(.horizontal, 14).padding(.vertical, 10)
            Divider()
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    ForEach(people) { p in row(p); Divider().padding(.leading, 56) }
                }
            }
        }
        .frame(maxWidth: sidebar ? .infinity : 440, maxHeight: sidebar ? .infinity : nil)
        .fixedSize(horizontal: false, vertical: !sidebar)
        .background(HornCardBackground(), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .shadow(radius: sidebar ? 0 : 12)
        .padding(sidebar ? 0 : 16)
        .accessibilityAddTraits(sidebar ? [] : .isModal)
    }
    private func row(_ p: Person) -> some View {
        let speaking = calls.meet.speaking.contains(p.id)
        let sub = p.devs.map { d in
            let n = d.devName.isEmpty ? String(localized: "Gerät") : d.devName
            return d.companion ? "\(n) (\(String(localized: "Begleitgerät")))" : n
        }.joined(separator: " · ")
        return HStack(spacing: 10) {
            AvatarView(name: p.name, avatar: p.avatar, size: 32)
                .overlay(Circle().strokeBorder(MeetStyle.ring, lineWidth: speaking ? 2 : 0).padding(-3))
            VStack(alignment: .leading, spacing: 1) {
                Text(verbatim: p.me ? "\(p.name) (\(String(localized: "du")))" : p.name).font(.subheadline.weight(.semibold)).lineLimit(1)
                Text(verbatim: sub).font(.caption).foregroundStyle(.secondary).lineLimit(1)
            }
            Spacer(minLength: 4)
            if !p.devs.contains(where: \.mic) { Image(systemName: "mic.slash.fill").foregroundStyle(.secondary).accessibilityLabel(Text("stumm")) }
            if p.devs.contains(where: \.cam) { Image(systemName: "video.fill").foregroundStyle(.secondary).accessibilityLabel(Text("Kamera ist an")) }
            if p.devs.contains(where: \.screen) { Image(systemName: "rectangle.inset.filled.on.rectangle").foregroundStyle(.secondary).accessibilityLabel(Text("teilt den Bildschirm")) }
            if !p.me {
                Menu {
                    Button { calls.meetPin(p.id) } label: {
                        calls.meet.pin == p.id ? Label("Lösen", systemImage: "pin.slash") : Label("Für mich anheften", systemImage: "pin")
                    }
                } label: { Image(systemName: "ellipsis").frame(width: 32, height: 44).contentShape(Rectangle()) }
                .menuIndicator(.hidden).buttonStyle(.plain).fixedSize()
                .accessibilityLabel(Text("Aktionen für \(p.name)"))
            }
        }
        .padding(.horizontal, 14).padding(.vertical, 8).frame(minHeight: 56)
        .accessibilityElement(children: .contain)
    }
}
