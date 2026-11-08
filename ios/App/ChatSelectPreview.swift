#if DEBUG
import SwiftUI

// Nur fuer Bilder (Debug-Build, ohne Server): echte Unterhaltung (ConversationView) mit erfundenem Zustand -
// Mehrfachauswahl, Loeschen-Dialog und der Hinweis "Warum keine Reaktionen?". Start: -hkSelPreview select|delete|hint
// [-hkX3Dark YES] (iPhone/iPad: xcrun simctl io <id> screenshot).

enum SelectPreview {
    static var mode: String? { UserDefaults.standard.string(forKey: "hkSelPreview") }
}

struct SelectPreviewView: View {
    @ObservedObject var store: Store
    @StateObject private var chat = ChatModel()
    @StateObject private var nav = NavModel()
    @State private var conv: ConversationModel?

    var body: some View {
        NavigationStack {
            if let conv { ConversationView(preview: conv, chat: chat) }
        }
        .environmentObject(store)
        .environmentObject(nav)
        .preferredColorScheme(UserDefaults.standard.bool(forKey: "hkX3Dark") ? .dark : .light)
        .task { setUp() }
    }

    private func setUp() {
        guard conv == nil else { return }
        if let d = try? JSONSerialization.data(withJSONObject: ["uid": "max", "identity": ["ident_pub": "x"]]), let m = ChatMe(data: d) { chat.me = m }
        let c = ConversationModel(chatID: 1, model: chat)
        if SelectPreview.mode == "blank" { blankSetUp(c); return }
        let summary: [String: Any] = [
            "id": 1, "kind": "free", "name": "Sommerfest", "member_count": 3, "role": "admin",
            "members": [["username": "max", "name": "Max", "me": true], ["username": "bea", "name": "Bea"], ["username": "tom", "name": "Tom"]],
            "x3_why": "devices",
            "x3_wait": ["mine": ["Linux-Laptop", "Safari · iOS"], "others": 1, "until": 1_800_000_000],
        ]
        c.info = ChatSummary(summary)
        c.info?.decryptedTitle = "Sommerfest"
        let now = Date().timeIntervalSince1970
        func msg(_ id: String, _ who: String, _ name: String, _ text: String, ago: Double, files: [ChatFileRef] = []) -> ChatMessage {
            ChatMessage(seq: c.messages.count + 1, id: id, sender: who, senderName: name, ts: (now - ago) * 1000, created: now - ago, expiresAt: nil,
                        replyTo: nil, content: ChatContent(text: text, files: files), verified: true, mine: who == "max")
        }
        let pdf = ChatFileRef(["id": "f1", "k": "k", "n": "Einkaufsliste.pdf", "m": "application/pdf", "s": 48_200])!
        var call = ChatMessage(seq: 0, id: "c1", sender: "tom", senderName: "Tom", ts: (now - 3500) * 1000, created: now - 3500, expiresAt: nil,
                               replyTo: nil, content: nil, verified: true, mine: false)
        call.call = CallLogic.Entry(json: Data(#"{"type":"call","call":"x","media":"audio","status":"ended","dur":312,"from":"tom","to":"max"}"#.utf8))
        var list = [
            msg("m1", "bea", "Bea", "Wer bringt den Grill mit?", ago: 3600),
            msg("m2", "tom", "Tom", "Ich! Und Kohle für zwei Stunden.", ago: 3400),
            msg("m3", "max", "Max", "Super. Ich kümmere mich um Getränke.", ago: 3000),
            msg("m4", "bea", "Bea", "Hier die Liste:", ago: 2400, files: [pdf]),
            msg("m5", "max", "Max", "Klingt gut – bis Samstag!", ago: 60),
        ]
        if call.call != nil { list.insert(call, at: 2) }
        for i in list.indices { list[i].seq = i + 1 }
        c.messages = list
        conv = c
    }

    /// 2.5 (leerchat): Direkt-Chat wie im Fehlerbild - leer geoeffnet, dann wie load() auf einmal ~73 Eintraege (67 Anrufe,
    /// dazwischen Bilder mit w/h); -hkBlankReload n: n-mal im Abstand von 4 s erneut ersetzen (wie ein Live-Neuladen); -hkBlankCount n: Anzahl
    private func blankSetUp(_ c: ConversationModel) {
        c.info = ChatSummary(["id": 1, "kind": "dm", "name": "", "member_count": 2, "role": "member",
                              "members": [["username": "max", "name": "Max", "me": true], ["username": "bea", "name": "Bea"]]])
        conv = c
        let now = Date().timeIntervalSince1970
        var list: [ChatMessage] = []
        let total = UserDefaults.standard.integer(forKey: "hkBlankCount") > 0 ? UserDefaults.standard.integer(forKey: "hkBlankCount") : 73
        for i in 0..<total {
            let ago = Double(total - i) * 1800
            var m = ChatMessage(seq: i + 1, id: "b\(i)", sender: i % 3 == 0 ? "max" : "bea", senderName: i % 3 == 0 ? "Max" : "Bea",
                                ts: (now - ago) * 1000, created: now - ago, expiresAt: nil, replyTo: nil, content: nil, verified: true, mine: i % 3 == 0)
            if i % 12 == 5 || i % 12 == 11 {                                 // 6 Bild-/Textnachrichten, Rest Anruf-Eintraege
                let img = ChatFileRef(id: "img\(i)", key: "k", name: "", mime: "image/jpeg", size: 120_000, w: i % 2 == 0 ? 3024 : 1080, h: i % 2 == 0 ? 4032 : 1920)
                m.content = ChatContent(text: i % 24 == 5 ? "Schau mal" : "", files: [img, img, img].enumerated().map { var f = $1; f.id += "-\($0)"; return f })
            } else {
                m.call = CallLogic.Entry(json: Data(#"{"type":"call","call":"x","media":"audio","status":"missed","dur":0,"from":"bea","to":"max"}"#.utf8))
            }
            list.append(m)
        }
        let reloads = UserDefaults.standard.integer(forKey: "hkBlankReload")
        Task { @MainActor in
            try? await Task.sleep(for: .milliseconds(600))
            c.previewReplace(list)
            for _ in 0..<reloads { try? await Task.sleep(for: .seconds(4)); c.previewReplace(list) }
        }
    }
}
#endif
