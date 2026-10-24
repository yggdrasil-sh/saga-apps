import SwiftUI
import WebRTC

// Gjallarhorn (Saga 2.5, P11 Stufe 2): Oberflaeche des Gruppenanrufs ueber einen Knoten - Kacheln bis 25 (iPhone 4,
// iPad/Mac 9 bewegt, die uebrigen mit Profilbild und Sprechring), Sprecheransicht, Bildschirm gross, Weg UDP/TCP/TLS mit
// Qualitaetspunkt, Runensiegel mit Erklaerung, Teilnehmerliste (Gastgeber: stummschalten, entfernen). Hell/dunkel: das
// Anruf-Fenster ist immer dunkel (wie 2.4); Listen und Karten nutzen Materialien und passen sich an.

/// Buehne: Bildschirm gross bzw. Sprecher gross mit Streifen, sonst Kachelraster
struct HornStage: View {
    @ObservedObject var calls: CallManager
    @ObservedObject var horn: HornSession
    @Environment(\.hornPreviewFill) private var previewFill
    /// Raster: unterer Rand des sichtbaren Bereichs (fuer "+n weitere")
    @State private var seenBottom: CGFloat = 0
    /// zu zweit: Ecke des eigenen Bilds (0 oben links, 1 oben rechts, 2 unten links, 3 unten rechts) und laufendes Ziehen
    @State private var duoCorner = 1
    @State private var duoDrag = CGSize.zero
    var body: some View {
        GeometryReader { g in
            let big = horn.share != nil || (horn.view == "speaker" && horn.tiles.count > 1)
            // zu zweit (1:1 ueber Knoten bzw. Raum mit einer weiteren Person): Gegenueber gross, eigenes Bild klein -
            // wie der direkte 1:1-Anruf, statt zwei schmaler Hochkant-Kacheln
            if horn.share == nil, horn.tiles.count == 2, let me = horn.tiles.first(where: \.mine), let other = horn.tiles.first(where: { !$0.mine }) {
                duo(other: other, me: me, size: g.size)
            } else if big { strip(g.size) }
            else if horn.tiles.count == 3, horn.more == 0, g.size.width <= 640 { trio(g.size) }   // zwei oben, die dritte breit
            else { grid(g.size) }
        }
        .overlay(alignment: .topTrailing) { buttons }
    }
    private func grid(_ size: CGSize) -> some View {
        let n = max(1, horn.tiles.count + (horn.more > 0 ? 1 : 0))
        let cols = HornLogic.cols(n, width: size.width)
        let rows = (n + cols - 1) / cols
        let sp: CGFloat = 8
        let fit = (size.height - sp * CGFloat(rows - 1)) / CGFloat(rows)
        let h = max(cols == 2 && size.width <= 640 ? 120 : 90, fit)
        let hidden = HornLogic.hiddenTiles(n, cols: cols, rowHeight: h, spacing: sp, seenBottom: seenBottom > 0 ? seenBottom : size.height)
        return VStack(spacing: 6) {
            ScrollView(.vertical, showsIndicators: fit < h) {
                LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: sp), count: cols), spacing: sp) {
                    ForEach(horn.tiles) { t in tile(t).frame(height: h) }
                    if horn.more > 0 { moreTile.frame(height: h) }
                }
            }
            .scrollDisabled(fit >= h)
            .onScrollGeometryChange(for: CGFloat.self) { $0.contentOffset.y + $0.containerSize.height } action: { _, v in seenBottom = v }
            .accessibilityElement(children: .contain)
            .accessibilityLabel(Text("Teilnehmende"))
            if fit < h && hidden > 0 {                                      // nicht still weglassen: "+n weitere" unter dem Raster
                Text("+\(hidden) weitere").font(.caption.weight(.semibold)).foregroundStyle(.white)
                    .padding(.horizontal, 10).padding(.vertical, 3).background(Color.white.opacity(0.16), in: Capsule())
                    .accessibilityLabel(Text("\(hidden) weitere Teilnehmende – nach unten blättern"))
            }
        }
    }
    /// Kachel mit Doppeltipp (gross anzeigen); VoiceOver: als Aktion
    @ViewBuilder private func tile(_ t: HornTile, compact: Bool = false) -> some View {
        if t.mine {
            HornTileView(tile: t, compact: compact)
        } else {
            HornTileView(tile: t, compact: compact).onTapGesture(count: 2) { calls.hornPin(t.id) }
                .accessibilityAddTraits(.isButton)
                .accessibilityAction { calls.hornPin(t.id) }
        }
    }
    private func duo(other: HornTile, me: HornTile, size: CGSize) -> some View {
        let w: CGFloat = size.width <= 640 ? 110 : 180, h = w * 16 / 11
        // eigenes Bild in einer Ecke (Start oben rechts unter den Knoepfen); ziehen, rastet an der naechsten Ecke ein
        let left = duoCorner % 2 == 0, top = duoCorner < 2
        let x = left ? 8 : size.width - w - 8, y = top ? 50 : size.height - h - 8
        return ZStack(alignment: .topLeading) {
            tile(other).frame(maxWidth: .infinity, maxHeight: .infinity)
            tile(me, compact: true).frame(width: w, height: h)
                .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(.white.opacity(0.3)))
                .shadow(radius: 6)
                .offset(x: x + duoDrag.width, y: y + duoDrag.height)
                .gesture(DragGesture()
                    .onChanged { duoDrag = $0.translation }
                    .onEnded { g in
                        let cx = x + g.translation.width + w / 2, cy = y + g.translation.height + h / 2
                        withAnimation(.spring(duration: 0.25)) {
                            duoCorner = (cx < size.width / 2 ? 0 : 1) + (cy < size.height / 2 ? 0 : 2); duoDrag = .zero
                        }
                    })
                .accessibilityAction(named: Text("Eigenes Bild verschieben")) { duoCorner = (duoCorner + 1) % 4 }
        }
    }
    private func trio(_ size: CGSize) -> some View {
        VStack(spacing: 8) {
            HStack(spacing: 8) { tile(horn.tiles[0]); tile(horn.tiles[1]) }.frame(maxHeight: .infinity)
            tile(horn.tiles[2]).frame(maxHeight: .infinity)
        }
    }
    private func strip(_ size: CGSize) -> some View {
        let small: CGFloat = size.width <= 640 ? 92 : 120
        let n = (horn.share != nil ? horn.tiles.count : horn.tiles.count - 1) + (horn.more > 0 ? 1 : 0)
        // bis 3 Kacheln: passend verteilen (kein Anschnitt am Rand), sonst feste Breite und sichtbar weiterblaettern
        let w: CGFloat = n > 0 && n <= 3 ? min(small * 1.6, (size.width - 8 * CGFloat(n - 1)) / CGFloat(n)) : small * 1.4
        return VStack(spacing: 8) {
            Group {
                if let s = horn.share {
                    ZStack(alignment: .bottomLeading) {
                        Color.black.opacity(0.35)
                        if previewFill && s.track == nil { HornPreviewScreen() } else { RTCVideoBox(track: s.track, fill: false) }
                        Label("\(s.name) teilt den Bildschirm", systemImage: "rectangle.inset.filled.on.rectangle").font(.caption.weight(.semibold))
                            .padding(.horizontal, 8).padding(.vertical, 4).background(.black.opacity(0.5), in: Capsule()).padding(6)
                    }
                    .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                    .accessibilityElement(children: .combine)
                } else if let t = horn.tiles.first {
                    tile(t)
                }
            }
            .frame(maxHeight: .infinity)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(horn.share != nil ? horn.tiles : Array(horn.tiles.dropFirst())) { t in
                        tile(t, compact: true).frame(width: w, height: small)
                    }
                    if horn.more > 0 { moreTile.frame(width: small, height: small) }
                }
            }
            .frame(height: small)
        }
    }
    private var moreTile: some View {
        ZStack { Color.white.opacity(0.08); Text(verbatim: "+\(horn.more)").font(.title2.weight(.semibold)) }
            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
            .accessibilityLabel(Text("\(horn.more) weitere Teilnehmende"))
    }
    private var buttons: some View {
        HStack(spacing: 8) {
            let sv = horn.view == "speaker"
            Button { calls.hornSetView(sv ? "grid" : "speaker") } label: {
                Image(systemName: sv ? "square.grid.2x2" : "person.crop.rectangle").frame(width: 36, height: 36).background(.black.opacity(0.45), in: Circle())
                    .frame(width: 44, height: 44).contentShape(Rectangle())
            }
            .accessibilityLabel(Text(sv ? "Kachelansicht" : "Sprecheransicht")).help(Text(sv ? "Kachelansicht" : "Sprecheransicht"))
            Button { horn.plist.toggle() } label: {
                Image(systemName: "person.2.fill").frame(width: 36, height: 36).background(horn.plist ? Color.white.opacity(0.35) : Color.black.opacity(0.45), in: Circle())
                    .frame(width: 44, height: 44).contentShape(Rectangle())
            }
            .accessibilityLabel(Text("Teilnehmende")).accessibilityAddTraits(horn.plist ? .isSelected : []).help(Text("Teilnehmende"))
        }
        .buttonStyle(.plain).foregroundStyle(.white).padding(2)
    }
}

/// Eine Kachel: bewegtes Bild, sonst Profilbild; Sprechring, Stumm-Zeichen, Name
struct HornTileView: View {
    let tile: HornTile
    var compact = false
    @Environment(\.hornPreviewFill) private var previewFill
    var body: some View {
        ZStack(alignment: .bottomLeading) {
            Color.white.opacity(0.08)
            if let v = tile.video, tile.cam {
                RTCVideoBox(track: v, mirror: tile.mirror, fill: true)
            } else if previewFill && tile.moving && tile.cam {
                HornPreviewPicture(seed: tile.id).accessibilityHidden(true)   // nur Bilder (Debug)
            } else {
                AvatarView(name: tile.name, avatar: tile.avatar, size: compact ? 44 : 64).frame(maxWidth: .infinity, maxHeight: .infinity)
                    .accessibilityHidden(true)
            }
            HStack(spacing: 4) {
                if !tile.mic { Image(systemName: "mic.slash.fill").accessibilityHidden(true) }
                if tile.screen { Image(systemName: "rectangle.inset.filled.on.rectangle").accessibilityHidden(true) }
                Text(verbatim: shownName).fontWeight(.semibold).truncationMode(shownName.hasPrefix("~") ? .middle : .tail).layoutPriority(1)
                if !compact && !tile.mine && !tile.devName.isEmpty { Text(verbatim: tile.devName).opacity(0.8) }   // wird zuerst gekuerzt
            }
            .font(.caption).lineLimit(1)
            .padding(.horizontal, 8).padding(.vertical, 4).background(.black.opacity(0.7), in: Capsule()).padding(6)
        }
        .overlay(alignment: .topLeading) {                                  // verbindet noch: klein in der Ecke, nicht ueber dem Initial
            if tile.wait { ProgressView().controlSize(.small).tint(.white).padding(8).accessibilityHidden(true) }
        }
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous)
            .strokeBorder(tile.speaking ? Color.green : Color.white.opacity(0.15), lineWidth: tile.speaking ? 3 : 1))
        .animation(.easeOut(duration: 0.2), value: tile.speaking)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(Text(verbatim: label))
        .accessibilityHint(tile.mine ? Text(verbatim: "") : Text("Doppelt tippen: groß anzeigen"))
    }
    private var shownName: String { tile.mine ? String(localized: "Du") : tile.name }
    private var label: String {
        var p = [shownName]
        if !tile.devName.isEmpty { p.append(tile.devName) }
        if !tile.mic { p.append(String(localized: "stumm")) }
        if tile.speaking { p.append(String(localized: "spricht")) }
        if tile.screen { p.append(String(localized: "teilt den Bildschirm")) }
        return p.joined(separator: ", ")
    }
}

/// Kopfzeile: Weg zum Knoten mit Qualitaetspunkt, "wechselt den Knoten", Runensiegel
struct HornInfoLine: View {
    @ObservedObject var calls: CallManager
    @ObservedObject var horn: HornSession
    var body: some View {
        HStack(spacing: 8) {
            if !horn.path.isEmpty {
                Button { horn.ppop.toggle() } label: {
                    HStack(spacing: 5) {
                        Circle().fill(dot).frame(width: 8, height: 8)
                        Text(verbatim: HornLogic.pathName(horn.path)).font(.caption.weight(.semibold).monospaced())
                    }
                    .padding(.horizontal, 8).padding(.vertical, 3).background(.black.opacity(0.4), in: Capsule())
                    .padding(.vertical, 8).contentShape(Rectangle()).padding(.vertical, -8)   // groessere Trefferflaeche
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text(verbatim: pathText))
                .accessibilityHint(Text("Zeigt Einzelheiten zur Verbindung"))
                .help(Text(verbatim: pathText))
                .popover(isPresented: $horn.ppop) {
                    HornPathCard(horn: horn, way: way, qualityText: qualityText, dot: dot)
                        #if os(iOS)
                        .presentationCompactAdaptation(.popover)
                        #endif
                }
            }
            if horn.moving { Text("wechselt den Knoten …").font(.caption2) }
            if let r = horn.runes {
                Button { horn.rpop.toggle() } label: {
                    Text(verbatim: r).font(.callout).tracking(2).padding(.horizontal, 8).padding(.vertical, 2)
                        .background(.black.opacity(0.4), in: Capsule())
                        .padding(.vertical, 10).contentShape(Rectangle()).padding(.vertical, -10)   // Trefferflaeche >= 44 pt
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text("Runensiegel – zum Vergleichen"))
                .accessibilityValue(Text(verbatim: horn.runeNames.joined(separator: " ")))
                .help(Text("Runensiegel – zum Vergleichen"))
            }
            if calls.verified {                                              // eine Sicherheitszeile (Anruf-UI-Regeln)
                Label("Ende-zu-Ende verschlüsselt", systemImage: "lock.fill").labelStyle(.titleAndIcon).font(.caption)
                    .foregroundStyle(.white.opacity(0.85))
            }
        }
        .foregroundStyle(.white).shadow(radius: 2)
        if !horn.note.isEmpty {
            Text(verbatim: horn.note).font(.caption).multilineTextAlignment(.center).foregroundStyle(.white.opacity(0.9)).shadow(radius: 2)
        }
    }
    private var dot: Color { horn.quality == .good ? .green : horn.quality == .mid ? .yellow : .red }
    private var way: String {
        horn.path == "udp" ? String(localized: "UDP (schnellster Weg)") : horn.path == "turn" ? String(localized: "TLS über Port 443 (Rückfall)") : String(localized: "TCP über Port 443")
    }
    private var qualityText: String {
        horn.quality == .good ? String(localized: "gute Verbindung") : horn.quality == .mid ? String(localized: "mittlere Verbindung") : String(localized: "schwache Verbindung")
    }
    private var pathText: String {
        var s = String(localized: "Weg zum Knoten \(horn.node): \(way)") + " · " + qualityText
        if let r = horn.rtt { s += " · RTT \(Int(r)) ms" }
        if horn.loss > 0 { let pct = String(format: "%.1f %%", horn.loss * 100); s += " · " + String(localized: "Verlust \(pct)") }
        return s
    }
}

/// Feld der Weg-Anzeige: Knoten, Weg, RTT, Verlust, Qualitaet
struct HornPathCard: View {
    @ObservedObject var horn: HornSession
    let way: String
    let qualityText: String
    let dot: Color
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Label("Verbindung", systemImage: "point.3.connected.trianglepath.dotted").font(.headline)
            Grid(alignment: .leading, horizontalSpacing: 12, verticalSpacing: 6) {
                GridRow { Text("Knoten").foregroundStyle(.secondary); Text(verbatim: horn.node.isEmpty ? "–" : horn.node).textSelection(.enabled) }
                GridRow { Text("Weg").foregroundStyle(.secondary); Text(verbatim: way) }
                GridRow { Text(verbatim: "RTT").foregroundStyle(.secondary); Text(verbatim: horn.rtt.map { "\(Int($0)) ms" } ?? "–").monospacedDigit() }
                if horn.loss > 0 {
                    GridRow { Text("Verlust").foregroundStyle(.secondary); Text(verbatim: String(format: "%.1f %%", horn.loss * 100)).monospacedDigit() }
                }
                GridRow {
                    Text("Qualität").foregroundStyle(.secondary)
                    HStack(spacing: 6) { Circle().fill(dot).frame(width: 8, height: 8).accessibilityHidden(true); Text(verbatim: qualityText) }
                }
            }
            .font(.callout)
        }
        .padding(16)
        .frame(minWidth: 240, alignment: .leading)
        .fixedSize(horizontal: false, vertical: true)
    }
}

/// Runensiegel mit Erklaerung (zum Vergleichen, z. B. durch Vorlesen)
struct HornRunesCard: View {
    @ObservedObject var calls: CallManager
    @ObservedObject var horn: HornSession
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Label("Runensiegel", systemImage: "seal").font(.headline)
                Spacer()
                Button { horn.rpop = false } label: { Image(systemName: "xmark").frame(width: 44, height: 44).contentShape(Rectangle()) }
                    .buttonStyle(.plain).accessibilityLabel(Text("Schließen")).padding(-7)
            }
            Text(verbatim: horn.runes ?? "").font(.system(size: 40)).tracking(6).frame(maxWidth: .infinity)
                .accessibilityLabel(Text(verbatim: horn.runeNames.joined(separator: " ")))
            Text(verbatim: horn.runeNames.joined(separator: " · ")).font(.footnote.monospaced()).frame(maxWidth: .infinity).multilineTextAlignment(.center)
            Text("Vergleicht die sechs Runen, zum Beispiel durch Vorlesen. Sehen alle dieselben, sind genau die Geräte unter „Teilnehmende“ im Anruf – niemand sonst kann mithören, auch nicht der Server oder der Knoten.")
                .font(.callout).fixedSize(horizontal: false, vertical: true)
            Text("Die Runen ändern sich, sobald jemand beitritt oder geht.").font(.footnote).foregroundStyle(.secondary)
            if !horn.unverified.isEmpty {
                Label("Nicht geprüft: \(names(horn.unverified))", systemImage: "exclamationmark.triangle.fill").font(.footnote).foregroundStyle(.orange)
            }
        }
        .padding(16)
        .frame(maxWidth: 380)
        .background(HornCardBackground(), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .shadow(radius: 12)
        .padding(20)
        .accessibilityAddTraits(.isModal)
    }
    private func names(_ devs: [String]) -> String { devs.map { d in horn.people.first { $0.id == d }?.name ?? "?" }.joined(separator: ", ") }
}

/// Teilnehmerliste; Gastgeber: stummschalten, entfernen (zweistufig)
struct HornPeoplePanel: View {
    @ObservedObject var calls: CallManager
    @ObservedObject var horn: HornSession
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Text("Teilnehmende (\(horn.people.count))").font(.headline)
                Spacer()
                Button { horn.plist = false } label: { Image(systemName: "xmark").frame(width: 44, height: 44).contentShape(Rectangle()) }
                    .buttonStyle(.plain).accessibilityLabel(Text("Schließen")).padding(-7)
            }
            .padding(.horizontal, 14).padding(.vertical, 10)
            Divider()
            ViewThatFits(in: .vertical) {
                rows
                ScrollView { rows }
            }
        }
        .frame(maxWidth: 440)
        .background(HornCardBackground(), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .shadow(radius: 12)
        .padding(16)
        .accessibilityAddTraits(.isModal)
    }
    private var rows: some View {
        VStack(alignment: .leading, spacing: 0) {
            ForEach(horn.people) { p in row(p); Divider().padding(.leading, 56) }
        }
    }
    private func row(_ p: HornPerson) -> some View {
        HStack(spacing: 10) {
            AvatarView(name: p.name, avatar: p.avatar, size: 32)
                .overlay(Circle().strokeBorder(Color.green, lineWidth: p.speaking ? 2 : 0).padding(-3))
            VStack(alignment: .leading, spacing: 1) {
                Text(verbatim: p.mine ? "\(p.name) (\(String(localized: "du")))" : p.name).font(.subheadline.weight(.semibold)).lineLimit(1)
                if !p.sub.isEmpty { Text(verbatim: p.sub).font(.caption).foregroundStyle(.secondary).lineLimit(1) }
                if p.unverified { Text("nicht geprüft").font(.caption2).foregroundStyle(.orange) }
            }
            Spacer(minLength: 4)
            if !p.mic { Image(systemName: "mic.slash.fill").foregroundStyle(.secondary).accessibilityLabel(Text("stumm")) }
            if horn.host && !p.mine && !p.ownPerson {
                if p.mic {
                    Button(String(localized: "Stummschalten")) { calls.hornModerate(p.id, action: "mute") }
                        .buttonStyle(.bordered).controlSize(.small)
                        .accessibilityLabel(Text("\(p.name) stummschalten"))
                }
                let sure = horn.rmSure == p.id
                Button(sure ? String(localized: "Wirklich entfernen?") : String(localized: "Entfernen (Anruf)"), role: .destructive) {
                    if horn.rmSure != p.id {
                        horn.rmSure = p.id
                        AccessibilityNotification.Announcement(String(localized: "\(p.name) wirklich entfernen? Zum Bestätigen erneut tippen.")).post()
                        Task {
                            try? await Task.sleep(for: .seconds(4))
                            if horn.rmSure == p.id { horn.rmSure = nil; AccessibilityNotification.Announcement(String(localized: "Entfernen abgebrochen")).post() }
                        }
                    } else {
                        horn.rmSure = nil; calls.hornModerate(p.id, action: "remove")
                        AccessibilityNotification.Announcement(String(localized: "\(p.name) wird aus dem Anruf entfernt")).post()
                    }
                }
                .buttonStyle(.bordered).controlSize(.small).tint(.red)
                .accessibilityLabel(sure ? Text("\(p.name) wirklich entfernen?") : Text("\(p.name) aus dem Anruf entfernen"))
                .accessibilityHint(sure ? Text("Zum Bestätigen erneut tippen") : Text("Danach folgt eine Rückfrage"))
            }
        }
        .padding(.horizontal, 14).padding(.vertical, 8)
        .accessibilityElement(children: .contain)
    }
}

/// Leiste "… teilt den Bildschirm" im Knoten-Anruf (eigenes Teilen zeigt CallScreen.shareBar)
struct HornShareLine: View {
    @ObservedObject var horn: HornSession
    var body: some View {
        if let s = horn.share {
            Label("\(s.name) teilt den Bildschirm", systemImage: "rectangle.inset.filled.on.rectangle")
                .font(.subheadline.weight(.semibold)).foregroundStyle(.white)
                .padding(.horizontal, 12).padding(.vertical, 7)
                .background(Color(red: 0.78, green: 0.13, blue: 0.13), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
        }
    }
}

/// Ebenen ueber der Buehne: Teilnehmerliste und Runensiegel
struct HornOverlays: View {
    @ObservedObject var calls: CallManager
    @ObservedObject var horn: HornSession
    /// Farbschema des Systems (das Anruf-Fenster selbst ist immer dunkel; Liste und Karte folgen hell/dunkel)
    var scheme: ColorScheme = .dark
    /// freier Bereich: unter der Kopfzeile, ueber Dauer und Steuerknoepfen
    var top: CGFloat = 0
    var bottom: CGFloat = 0
    var body: some View {
        ZStack {
            if horn.plist {
                Color.black.opacity(0.35).ignoresSafeArea().onTapGesture { horn.plist = false }
                HornPeoplePanel(calls: calls, horn: horn).environment(\.colorScheme, scheme).foregroundStyle(scheme == .dark ? Color.white : Color.black)
                    .padding(.top, top).padding(.bottom, bottom)
            }
            if horn.rpop {
                Color.black.opacity(0.35).ignoresSafeArea().onTapGesture { horn.rpop = false }
                HornRunesCard(calls: calls, horn: horn).environment(\.colorScheme, scheme).foregroundStyle(scheme == .dark ? Color.white : Color.black)
                    .padding(.top, top).padding(.bottom, bottom)
            }
        }
    }
}

private struct HornPreviewFill: EnvironmentKey { static let defaultValue = false }
extension EnvironmentValues {
    /// nur Bilder (Debug-Vorschau, HornPreview.swift): bewegte Kacheln als Farbflaeche statt Video
    var hornPreviewFill: Bool { get { self[HornPreviewFill.self] } set { self[HornPreviewFill.self] = newValue } }
}

/// Nur fuer Bilder: Farbflaeche statt Kamerabild (Debug-Vorschau ohne WebRTC); im normalen Build nie sichtbar
struct HornPreviewPicture: View {
    let seed: String
    var body: some View {
        let h = Double(abs(seed.hashValue % 360)) / 360
        ZStack {
            LinearGradient(colors: [Color(hue: h, saturation: 0.35, brightness: 0.55), Color(hue: (h + 0.12).truncatingRemainder(dividingBy: 1), saturation: 0.45, brightness: 0.28)],
                           startPoint: .topLeading, endPoint: .bottomTrailing)
            Image(systemName: "person.fill").resizable().scaledToFit().padding(.top, 22).foregroundStyle(.white.opacity(0.28))
        }
    }
}
struct HornPreviewScreen: View {
    var body: some View {
        ZStack {
            Color(white: 0.93)
            VStack(alignment: .leading, spacing: 10) {
                RoundedRectangle(cornerRadius: 4).fill(Color.gray.opacity(0.35)).frame(height: 18)
                HStack(spacing: 10) {
                    RoundedRectangle(cornerRadius: 6).fill(Color.blue.opacity(0.25))
                    VStack(spacing: 8) { ForEach(0..<5, id: \.self) { _ in RoundedRectangle(cornerRadius: 3).fill(Color.gray.opacity(0.3)).frame(height: 10) } }
                }
            }
            .padding(18)
        }
    }
}

/// Kartengrund: deckend, folgt hell/dunkel (Teilnehmerliste, Runensiegel)
struct HornCardBackground: ShapeStyle {
    func resolve(in env: EnvironmentValues) -> Color { env.colorScheme == .dark ? Color(white: 0.15) : Color(white: 0.97) }
}
