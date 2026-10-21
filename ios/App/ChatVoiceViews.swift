import SwiftUI

// Sprachnachrichten (Saga 2.5): Blase (Play, 64 Balken - gehoerter Teil eingefaerbt, Tippen springt -, Dauer, Tempo),
// Aufnahme (iPhone/iPad: halten, nach oben sperren, nach links verwerfen; Mac: Klick startet, Klick stoppt), Vorschau mit
// Abspielen, Verwerfen und Senden. Ablauf: ChatVoice.swift, Logik: ChatX3Logic.swift (VoiceLogic).

/// Balken der Wellenform (0-255), gehoerter Teil in der Akzentfarbe
struct VoiceWave: View {
    let bars: [UInt8]
    var played = 0
    var tint: Color = .gold
    var body: some View {
        Canvas { ctx, size in
            let n = max(1, bars.count), gap: CGFloat = 1.5
            let w = max(1, (size.width - gap * CGFloat(n - 1)) / CGFloat(n))
            for (i, v) in bars.enumerated() {
                let h = max(3, size.height * CGFloat(v) / 255)
                let r = CGRect(x: CGFloat(i) * (w + gap), y: (size.height - h) / 2, width: w, height: h)
                ctx.fill(Path(roundedRect: r, cornerRadius: w / 2), with: .color(i < played ? tint : Color.secondary.opacity(0.45)))
            }
        }
        .accessibilityHidden(true)
    }
}

/// Live-Pegel waehrend der Aufnahme (die letzten Sekunden, rechts die neuesten)
struct VoiceLiveWave: View {
    let levels: [Float]
    var body: some View {
        let n = 48
        let tail = Array(levels.suffix(n))
        let bars = [UInt8](repeating: 0, count: max(0, n - tail.count)) + tail.map { UInt8(min(255, max(0, $0 * 255))) }
        VoiceWave(bars: bars, played: n, tint: .red.opacity(0.8))
    }
}

/// Sprachnachricht in der Blase
struct VoiceMessageView: View {
    let file: ChatFileRef
    @ObservedObject var conv: ConversationModel
    @ObservedObject private var player = VoicePlayer.shared
    @State private var err: String?
    var body: some View {
        let dur = file.dur ?? 0
        let current = player.isCurrent(file.id)
        let pos = current ? player.position : 0
        let failure = err ?? (current ? player.failed : nil)
        let playing = current && player.playing && failure == nil
        HStack(spacing: 10) {
            Button { toggle() } label: {
                ZStack {
                    Circle().fill(Color.gold)
                    if current && player.loading { ProgressView().controlSize(.small).tint(.black) }
                    else { Image(systemName: playing ? "pause.fill" : "play.fill").foregroundStyle(.black).font(.body.weight(.bold)) }
                }
                .frame(width: 40, height: 40).padding(2).contentShape(Circle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(playing ? Text("Pause") : Text("Sprachnachricht abspielen"))
            VStack(alignment: .leading, spacing: 4) {
                GeometryReader { g in
                    VoiceWave(bars: VoiceLogic.bars(file.wf), played: VoiceLogic.played(position: pos, duration: dur))
                        .contentShape(Rectangle())
                        .gesture(DragGesture(minimumDistance: 0).onEnded { v in seek(fraction: v.location.x / max(1, g.size.width), dur: dur) })
                }
                .frame(height: 28)
                .accessibilityElement()
                .accessibilityLabel(Text("Position"))
                .accessibilityValue(Text(verbatim: VoiceLogic.fmt(pos) + " / " + VoiceLogic.fmt(dur)))
                .accessibilityAdjustableAction { d in
                    let step = d == .increment ? 5.0 : -5.0
                    seek(fraction: (pos + step) / max(1, dur), dur: dur)
                }
                HStack(spacing: 6) {
                    Text(verbatim: VoiceLogic.fmt(current && (player.playing || pos > 0) ? pos : dur)).font(.caption2.monospacedDigit()).foregroundStyle(.secondary)
                    Spacer(minLength: 0)
                    Button { player.cycleRate() } label: {
                        Text(verbatim: VoiceLogic.speedLabel(player.rate)).font(.caption2.weight(.bold).monospacedDigit())
                            .padding(.horizontal, 6).padding(.vertical, 2).background(Color.secondary.opacity(0.15), in: Capsule())
                            .padding(.horizontal, 8).padding(.vertical, 12).contentShape(Rectangle())   // Trefferflaeche ~44 pt
                            .padding(.horizontal, -8).padding(.vertical, -12)
                    }
                    .buttonStyle(.plain).help(Text("Tempo")).accessibilityLabel(Text("Tempo"))
                    .accessibilityValue(Text(verbatim: VoiceLogic.speedLabel(player.rate)))
                }
                if let e = failure {                                            // eigene Zeile: nicht abgeschnitten
                    Label(e, systemImage: "exclamationmark.triangle.fill").font(.caption2).foregroundStyle(.orange)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
        .frame(minWidth: 220, idealWidth: 260, maxWidth: 300)
    }
    private func toggle() {
        if player.isCurrent(file.id) && player.currentItemReady { player.toggle(); return }
        VoicePlay.start(file, conv: conv) { err = $0 }
    }
    private func seek(fraction: Double, dur: Double) {
        let s = VoiceLogic.seek(fraction: fraction, duration: dur)
        if player.isCurrent(file.id) { player.seek(s) } else { VoicePlay.start(file, conv: conv, from: s) { err = $0 } }
    }
}

extension VoicePlayer {
    var currentItemReady: Bool { player.currentItem != nil }
}

/// Abspielen starten, danach die naechste Sprachnachricht des Chats
@MainActor
enum VoicePlay {
    static func start(_ f: ChatFileRef, conv: ConversationModel, from: Double? = nil, error fail: @escaping (String?) -> Void = { _ in }) {
        fail(nil)
        Task { [weak conv] in
            do {
                guard let c = conv else { return }
                let src = try await c.voiceSource(f)
                VoicePlayer.shared.play(id: f.id, source: src, duration: f.dur ?? 0, from: from) { [weak conv] in
                    guard let conv, let n = conv.voiceAfter(f.id) else { return }
                    start(n.1, conv: conv)
                }
            } catch { fail(String(localized: "Sprachnachricht nicht abspielbar.")) }
        }
    }
}

/// Mikrofon-Knopf im Eingabefeld: iPhone/iPad halten (hoch = sperren, links = verwerfen), Mac klicken
struct VoiceMicButton: View {
    @ObservedObject var rec: VoiceRecorder
    var hint: (String) -> Void
    #if os(iOS)
    @State private var down: Date?
    @State private var cancelled = false
    #endif
    var body: some View {
        #if os(iOS)
        Image(systemName: rec.state == .recording ? "mic.fill" : "mic")
            .font(.title2).frame(minWidth: 44, minHeight: 44).contentShape(Rectangle())
            .foregroundStyle(rec.state == .recording ? Color.red : Color.gold)
            .scaleEffect(rec.state == .recording && !rec.locked ? 1.35 : 1)
            .animation(.easeOut(duration: 0.15), value: rec.state)
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { v in
                        if down == nil {
                            down = Date(); cancelled = false
                            Task { _ = await rec.start() }
                            return
                        }
                        guard rec.state == .recording, !rec.locked, !cancelled else { return }
                        switch VoiceLogic.gesture(dx: v.translation.width, dy: v.translation.height) {
                        case .lock: rec.locked = true
                        case .cancel: cancelled = true; rec.cancel()
                        case .hold: break
                        }
                    }
                    .onEnded { _ in
                        let held = down.map { Date().timeIntervalSince($0) } ?? 0
                        down = nil
                        if cancelled { return }
                        if held < 0.35 && !rec.locked {                       // nur getippt: erklaeren statt aufnehmen
                            rec.cancel(); hint(String(localized: "Zum Aufnehmen gedrückt halten – nach oben schieben zum Sperren."))
                            return
                        }
                        if !rec.locked { Task { await rec.stop() } }
                    }
            )
            .accessibilityLabel(Text("Sprachnachricht aufnehmen"))
            .accessibilityHint(Text("Gedrückt halten zum Aufnehmen"))
            .accessibilityAction { Task { if await rec.start() { rec.locked = true } } }   // VoiceOver: Doppeltippen startet gesperrt
        #else
        Button { Task { if rec.state == .recording { await rec.stop() } else { _ = await rec.start() } } } label: {
            Image(systemName: "mic").font(.title2).frame(minWidth: 44, minHeight: 44).contentShape(Rectangle())
        }
        .buttonStyle(.plain).foregroundStyle(Color.gold)
        .help(Text("Sprachnachricht aufnehmen"))
        .accessibilityLabel(Text("Sprachnachricht aufnehmen"))
        #endif
    }
}

/// Statt der Eingabe waehrend der Aufnahme bzw. in der Vorschau
struct VoiceRecordBar: View {
    @ObservedObject var rec: VoiceRecorder
    var send: () -> Void
    var progress: Double?
    @ObservedObject private var player = VoicePlayer.shared
    var body: some View {
        switch rec.state {
        case .preview, .sending: preview
        default: recording
        }
    }
    private var recording: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 10) {
                Circle().fill(Color.red).frame(width: 10, height: 10).accessibilityHidden(true)
                Text(verbatim: rec.state == .starting ? String(localized: "Mikrofon …") : VoiceLogic.fmt(rec.secs) + " / " + VoiceLogic.fmt(rec.maxS))
                    .font(.callout.monospacedDigit()).foregroundStyle(rec.warned ? Color.orange : Color.primary).fixedSize()
                    .accessibilityLabel(Text("Aufnahme läuft"))
                    .accessibilityValue(Text(verbatim: VoiceLogic.fmt(rec.secs)))
                VoiceLiveWave(levels: rec.live).frame(maxWidth: 280).frame(height: 24)
                if showButtons {
                    Button(role: .destructive) { rec.cancel() } label: { Image(systemName: "trash") }
                        .buttonStyle(.bordered).help(Text("Verwerfen")).accessibilityLabel(Text("Verwerfen"))
                        .keyboardShortcut(.cancelAction)
                    Button { Task { await rec.stop() } } label: { Label("Stopp", systemImage: "stop.fill").fixedSize() }
                        .prominentGold().disabled(rec.state != .recording).fixedSize()
                }
            }
            if rec.warned { Text("Noch eine Minute – dann endet die Aufnahme.").font(.caption).foregroundStyle(.orange) }
            #if os(iOS)
            if !rec.locked && rec.state == .recording {
                HStack {
                    Label("Zum Verwerfen nach links", systemImage: "chevron.left")
                    Spacer()
                    Label("Zum Sperren nach oben", systemImage: "lock")
                }.font(.caption2).foregroundStyle(.secondary)
            }
            #endif
        }
    }
    private var showButtons: Bool {
        #if os(iOS)
        rec.locked
        #else
        true
        #endif
    }
    @ViewBuilder private var preview: some View {
        if let c = rec.clip {
            let current = player.isCurrent(VoicePlayer.previewID)
            HStack(spacing: 10) {
                Button {
                    if current { player.toggle() } else { player.play(id: VoicePlayer.previewID, source: nil, url: c.url, duration: c.dur) }
                } label: {
                    Image(systemName: current && player.playing ? "pause.circle.fill" : "play.circle.fill").font(.title)
                }
                .buttonStyle(.plain).foregroundStyle(Color.gold)
                .accessibilityLabel(current && player.playing ? Text("Pause") : Text("Vorschau abspielen"))
                VoiceWave(bars: c.wf, played: current ? VoiceLogic.played(position: player.position, duration: c.dur) : 0)
                    .frame(maxWidth: 280).frame(height: 26)                    // wie die Aufnahmeleiste (Mac: nicht fensterbreit)
                Text(verbatim: progress.map { "\(Int(($0 * 100).rounded())) %" } ?? VoiceLogic.fmt(current ? player.position : c.dur))
                    .font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                Button(role: .destructive) { rec.cancel() } label: { Image(systemName: "trash") }
                    .buttonStyle(.bordered).disabled(rec.state == .sending)
                    .help(Text("Verwerfen")).accessibilityLabel(Text("Verwerfen"))
                Button { send() } label: { Label("Senden", systemImage: "arrow.up").fixedSize() }
                    .prominentGold().disabled(rec.state == .sending).fixedSize()
                    .keyboardShortcut(.return, modifiers: [])
            }
        }
    }
}
