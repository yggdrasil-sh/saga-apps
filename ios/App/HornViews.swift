import SwiftUI
import WebRTC

// Gjallarhorn (Saga 2.5, P11 Stufe 2): Oberflaeche des Gruppenanrufs ueber einen Knoten - Kacheln bis 25 (iPhone 4,
// iPad/Mac 9 bewegt, die uebrigen mit Profilbild und Sprechring), Sprecheransicht, Bildschirm gross, Weg UDP/TCP/TLS mit
// Qualitaetspunkt, Runensiegel mit Erklaerung, Teilnehmerliste (Gastgeber: stummschalten, entfernen). Hell/dunkel: das
// Anruf-Fenster ist immer dunkel (wie 2.4); Listen und Karten nutzen Materialien und passen sich an.

// Buehne und Kacheln: MeetingViews.swift (MEETING-SPEC, gemeinsam fuer 1:1, Mesh und Knoten)

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
