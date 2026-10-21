#if DEBUG
import SwiftUI

// Nur fuer Bilder (Debug-Build, ohne Server): Chat mit Reaktionen, "bearbeitet", Sprachnachricht, Bearbeiten-Leiste,
// Aufnahme und Vorschau - mit erfundenem Zustand. Start: -hkX3Preview chat|record|preview|who|bar|menu [-hkX3Menu m3] [-hkX3Dark YES]
// (Mac zusammen mit -hkSnapshot <pfad.png>; iPhone/iPad: xcrun simctl io <id> screenshot).

enum X3Preview {
    static var mode: String? { UserDefaults.standard.string(forKey: "hkX3Preview") }
}

struct X3PreviewView: View {
    @ObservedObject var store: Store
    @StateObject private var chat = ChatModel()
    @StateObject private var rec = VoiceRecorder()
    @State private var conv: ConversationModel?
    @State private var who: ChatMessage?
    private let mode = X3Preview.mode ?? "chat"
    private let me = "~aaaaaaaaaaaaaaaa@a.example", bea = "~bbbbbbbbbbbbbbbb@a.example", tom = "~cccccccccccccccc@a.example"

    var body: some View {
        NavigationStack {
            if let conv {
                VStack(spacing: 0) {
                    ScrollView {
                        LazyVStack(spacing: 6) {
                            Label("Nachrichten in diesem Chat sind Ende-zu-Ende-verschlüsselt.", systemImage: "lock.fill")
                                .font(.caption).foregroundStyle(.secondary).padding(.vertical, 8).frame(maxWidth: .infinity)
                            FedSysRow(line: .crypto(verify: false), date: Date(timeIntervalSince1970: 1_767_225_000), myHandle: me)
                            ForEach(conv.messages) { m in
                                MessageBubble(m: m, showName: !m.mine, quoted: nil, hasReply: false, conv: conv, reply: {}, delete: {}, openPage: { _ in })
                                    .overlay(alignment: .top) {
                                        if mode == "bar" && m.id == "m2" {
                                            ReactionBar(current: "👍", pick: { _ in }, more: {})
                                                .background(.regularMaterial, in: Capsule()).shadow(radius: 8).offset(y: -64)
                                        }
                                    }
                            }
                        }
                        .padding(.horizontal, 12).padding(.vertical, 8).frame(maxWidth: 820).frame(maxWidth: .infinity)
                    }
                    .defaultScrollAnchor(.bottom)
                    composer(conv)
                }
                .navigationTitle(Text(verbatim: "Planung Sommerfest"))
                #if os(iOS)
                .navigationBarTitleDisplayMode(.inline)
                #endif
                .sheet(item: $who) { m in ReactionsWhoSheet(conv: conv, message: m) }
            }
        }
        .environmentObject(store)
        .preferredColorScheme(UserDefaults.standard.bool(forKey: "hkX3Dark") ? .dark : .light)
        .task { setUp() }
    }

    @ViewBuilder private func composer(_ conv: ConversationModel) -> some View {
        VStack(spacing: 6) {
            switch mode {
            case "record", "preview", "recfile":
                VoiceRecordBar(rec: rec, send: {}, progress: nil)
            default:
                EditBar(original: "Treffen wir uns um 8?") {}
                HStack(alignment: .bottom, spacing: 8) {
                    Image(systemName: "plus.circle.fill").font(.title2).frame(minWidth: 44, minHeight: 44)
                    Text(verbatim: "Treffen wir uns um 9?").frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 12).padding(.vertical, 8)
                        .background(Color.secondary.opacity(0.1), in: RoundedRectangle(cornerRadius: 18))
                    Image(systemName: "checkmark.circle.fill").font(.title2).frame(minWidth: 44, minHeight: 44)
                }
                .foregroundStyle(Color.gold)
            }
        }
        .padding(.horizontal, 12).padding(.vertical, 8)
        .background(.bar)
    }

    private func setUp() {
        guard conv == nil else { return }
        if let d = try? JSONSerialization.data(withJSONObject: ["uid": "ich", "features": ["receipts", "online", "chat3", "react", "edit", "voice"],
                                                                "identity": ["ident_pub": "x"]]), let m = ChatMe(data: d) { chat.me = m }
        let c = ConversationModel(chatID: 1, model: chat)
        let summary: [String: Any] = [
            "id": 1, "kind": "free", "v": 3, "ref": "0123456789abcdef0123456789abcdef@a.example", "my_handle": me, "home_self": true, "x3_ok": true,
            "member_count": 3,
            "members": [["username": "ich", "handle": me, "name": "Max", "me": true, "host": "a.example"],
                        ["username": bea, "handle": bea, "name": "Bea", "host": "a.example"],
                        ["username": tom, "handle": tom, "name": "Tom", "host": "a.example"]],
        ]
        c.info = ChatSummary(summary)
        let now = Date().timeIntervalSince1970
        func msg(_ id: String, _ who: String, _ name: String, _ text: String, ago: Double, files: [ChatFileRef] = [], edited: Bool = false) -> ChatMessage {
            var m = ChatMessage(seq: c.messages.count + 1, id: id, sender: who, senderName: name, ts: (now - ago) * 1000, created: now - ago, expiresAt: nil,
                                replyTo: nil, content: ChatContent(text: text, files: files), verified: true, mine: who == me)
            m.senderHandle = who; m.fseq = m.seq
            if edited { m.editedAt = now - ago + 120; m.editN = 1 }
            c.msgKeys[id] = Data(repeating: 1, count: 32)
            return m
        }
        let wf = VoiceLogic.waveform((0..<124).map { Float(0.15 + 0.85 * abs(sin(Double($0) * 0.21)) * (0.4 + 0.6 * abs(cos(Double($0) * 0.05)))) })
        let voice = ChatFileRef(VoiceLogic.fileDict(id: "v1", key: "k", size: 37_000, v2: true, cs: FileV2.cs, dur: 12.4, wf: wf))!
        c.messages = [
            msg("m1", bea, "Bea", "Wer bringt den Grill mit?", ago: 3600),
            msg("m2", tom, "Tom", "Ich! Und Kohle für zwei Stunden.", ago: 3400),
            msg("m3", me, "Max", "Treffen wir uns um 9?", ago: 3000, edited: true),
            msg("m4", bea, "Bea", "", ago: 2400, files: [voice]),
            msg("m5", me, "Max", "Klingt gut – bis Samstag!", ago: 60),
        ]
        c.reactions = [
            "m2": [.init(by: bea, e: "👍", ts: 1), .init(by: me, e: "👍", ts: 2), .init(by: tom, e: "🔥", ts: 3)],
            "m3": [.init(by: bea, e: "❤️", ts: 1)],
            "m4": [.init(by: tom, e: "😂", ts: 1), .init(by: me, e: "😂", ts: 2)],
        ]
        conv = c
        rec.maxS = 900
        switch mode {
        case "record": rec.mockRecording(secs: 47, live: (0..<120).map { Float(0.2 + 0.7 * abs(sin(Double($0) * 0.33))) })
        case "preview":
            if let u = Bundle.main.url(forResource: "voice_sine", withExtension: "ogg"), let d = try? Data(contentsOf: u) {
                rec.mockPreview(VoiceClip(data: d, dur: 2.5, wf: wf, url: u))
            } else { rec.mockPreview(VoiceClip(data: Data(), dur: 23.6, wf: wf, url: URL(fileURLWithPath: "/dev/null"))) }
        case "who": Task { try? await Task.sleep(for: .seconds(1)); who = c.messages[1] }
        case "recfile":                                                    // echte Aufnahme-Kette mit Datei-Eingang (-hkVoiceFile), nie Mikrofon
            guard UserDefaults.standard.string(forKey: "hkVoiceFile") != nil else { return }
            Task {
                _ = await rec.start()
                try? await Task.sleep(for: .seconds(3.2))
                await rec.stop()
                if let c = rec.clip, let f = try? OggOpus.demux(c.data) { print("X3REC dur=\(c.dur) ogg=\(f.duration) packets=\(f.packets.count) bytes=\(c.data.count)") }
                else { print("X3REC fehlgeschlagen \(rec.error ?? "-")") }
            }
        default: break
        }
    }
}

extension VoiceRecorder {
    /// nur fuer Bilder: Zustand ohne Mikrofon
    func mockRecording(secs: Double, live: [Float]) { setMock(state: .recording, secs: secs, live: live, clip: nil); locked = true }
    func mockPreview(_ c: VoiceClip) { setMock(state: .preview, secs: c.dur, live: [], clip: c) }
}
#endif
