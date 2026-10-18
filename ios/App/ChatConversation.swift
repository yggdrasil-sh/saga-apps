import SwiftUI

// Ein geoeffneter Chat: Nachrichten laden/entschluesseln/pruefen, senden (je Empfaenger-Geraet + Backup versiegelt),
// Anhaenge (clientseitig verschluesselt), Antworten, Loeschen, gelesen, "tippt …", Seitenverweise.

struct ChatMessage: Identifiable, Equatable {
    var seq: Int
    var id: String
    var sender: String
    var senderName: String
    var ts: Double
    var created: Double
    var expiresAt: Double?
    var replyTo: String?
    var content: ChatContent?
    var verified: Bool
    var mine: Bool
    /// Profilbild des Absenders (2.1)
    var senderAvatar: String? = nil
    /// Anruf-Eintrag (kind "call", 2.1): unverschluesselte Metadaten statt Inhalt
    var call: CallLogic.Entry? = nil
    /// KI-Antwort (2.1): Beleg des Servers und Ergebnis der Pruefung (Hash + Kennung)
    var aiMeta: AIReplyMeta? = nil
    var aiState: AIChatLogic.ReplyState = .none
    var date: Date { Date(timeIntervalSince1970: created > 0 ? created : ts / 1000) }
    var text: String { content?.text ?? "" }
}

/// Anhang vor dem Senden
struct ChatPendingFile: Identifiable, Equatable { let id = UUID(); var name: String; var mime: String; var data: Data }

@MainActor
final class ConversationModel: ObservableObject {
    let chatID: Int
    unowned let model: ChatModel
    @Published var info: ChatSummary?
    @Published var messages: [ChatMessage] = []
    @Published var more = false
    @Published var loading = false
    @Published var error: String?
    @Published var sendError: String?
    @Published var sending = false
    @Published var notFound = false
    @Published var typing: [String: (name: String, at: Date)] = [:]
    @Published var notes: [Int: (title: String, icon: String)] = [:]
    @Published var changedPeers: [String] = []                      // Sicherheitsnummer geaendert (TOFU)
    /// KI im Chat (2.1): laufende Antwort (Strom) und Fehler
    @Published var aiPending: String?
    @Published var aiError: String?
    private var aiTask: Task<Void, Never>?
    /// Kennung der zuletzt gesendeten Nachricht (fuer die Frage an die KI)
    private(set) var lastSentID: String?
    private var recipients: [[String: Any]]?
    private var lastTyping = Date.distantPast
    private var lastReadSent = 0

    init(chatID: Int, model: ChatModel) { self.chatID = chatID; self.model = model }
    private var store: Store? { model.store }
    private var me: String { model.me.uid }
    var typingNames: [String] { ChatLogic.typing(typing) }

    // MARK: Laden

    func load() async {
        guard let store else { return }
        loading = true; defer { loading = false }
        do {
            let (d, code) = try await store.apiResponse("api/chats/\(chatID)?\(model.deviceParam)")
            if code == 404 { notFound = true; return }
            guard code == 200, let c = ShareJSON.obj(d)?["chat"] else { throw APIError.http(code, ShareJSON.obj(d).flatMap { ShareJSON.str($0["error"]) } ?? "") }
            var s = ChatSummary(c)
            if s?.kind == "free", let x = s { s?.decryptedTitle = model.decryptTitle(x) }
            info = s
            if s?.ai == true && !store.aiChat { await store.loadAIStatus() }   // KI im Chat (2.1): Server-Schalter/Persona aktuell
            let m = try await store.api("api/chats/\(chatID)/messages?\(model.deviceParam)&limit=50&calls=1")
            messages = []; ingest(m, prepend: false)
            error = nil
            await markRead()
            await loadRecipients()
        } catch { self.error = error.localizedDescription }
    }
    func loadOlder() async {
        guard let store, more, let first = messages.first else { return }
        if let d = try? await store.api("api/chats/\(chatID)/messages?\(model.deviceParam)&before=\(first.seq)&limit=50&calls=1") { ingest(d, prepend: true) }
    }
    func fetchNew() async {
        guard let store else { return }
        let after = messages.last?.seq ?? 0
        if let d = try? await store.api("api/chats/\(chatID)/messages?\(model.deviceParam)&after=\(after)&limit=200&calls=1") { ingest(d, prepend: false, onlyMore: false) }
        await markRead()
    }

    private func ingest(_ d: Data, prepend: Bool, onlyMore: Bool = true) {
        guard let j = ShareJSON.obj(d) else { return }
        let devs = j["devices"] as? [String: Any], ids = j["idents"] as? [String: Any]
        var list: [ChatMessage] = []
        for case let m as [String: Any] in (j["messages"] as? [Any] ?? []) {
            let sender = (m["sender"] as? [String: Any])?["username"] as? String ?? ""
            let name = (m["sender"] as? [String: Any])?["name"] as? String ?? String(localized: "Gelöschtes Konto")
            let avatar = ShareJSON.str((m["sender"] as? [String: Any])?["avatar"])
            if (m["kind"] as? String) == "call" {                         // Anruf-Eintrag: Klartext-Metadaten, keine Krypto
                guard let e = (m["body_ct"] as? String).flatMap({ CallLogic.Entry(json: Data($0.utf8)) }) else { continue }
                list.append(ChatMessage(seq: ShareJSON.int(m["seq"]) ?? 0, id: m["id"] as? String ?? "", sender: sender, senderName: name,
                                        ts: ShareJSON.num(m["ts"]) ?? 0, created: ShareJSON.num(m["created"]) ?? 0, expiresAt: ShareJSON.num(m["expires_at"]),
                                        replyTo: nil, content: nil, verified: true, mine: e.outgoing(me: me), senderAvatar: avatar, call: e))
                continue
            }
            let r = model.decrypt(chatID: chatID, raw: m, devices: devs, idents: ids)
            let meta = AIReplyMeta(m["ai"])
            let aiState = AIChatLogic.check(bodyRef: r?.content?.aiReply, text: r?.content?.text, meta: meta)
            if sender != me, let ip = ids?[sender] as? String, ChatTrustStore.observe(me, sender, pk: ip) == .changed || ChatTrustStore.known(me, sender)?.changed == true {
                if !changedPeers.contains(sender) { changedPeers.append(sender) }
            }
            list.append(ChatMessage(seq: ShareJSON.int(m["seq"]) ?? 0, id: m["id"] as? String ?? "", sender: sender, senderName: name,
                                    ts: ShareJSON.num(m["ts"]) ?? 0, created: ShareJSON.num(m["created"]) ?? 0, expiresAt: ShareJSON.num(m["expires_at"]),
                                    replyTo: ShareJSON.str(m["reply_to"]), content: r?.content, verified: r?.verified ?? false, mine: sender == me,
                                    senderAvatar: avatar, aiMeta: meta, aiState: aiState))
        }
        let known = Set(messages.map(\.id))
        list.removeAll { known.contains($0.id) }
        if prepend { messages = list + messages; more = ShareJSON.bool(j["more"]) }
        else { messages += list; if messages.count == list.count { more = ShareJSON.bool(j["more"]) } }
        Task { await resolveNotes(list) }
    }

    /// Seitenverweise [[note:id]] aufloesen (Titel nur bei eigenem Zugriff)
    private func resolveNotes(_ list: [ChatMessage]) async {
        let ids = Set(list.flatMap { m in (m.content?.notes ?? []) + ChatLogic.noteRefs(m.text) }).subtracting(notes.keys)
        guard let store, !ids.isEmpty else { return }
        guard let d = try? await store.api("api/chat/notes?ids=" + ids.map(String.init).joined(separator: ",")), let j = ShareJSON.obj(d)?["notes"] as? [String: Any] else { return }
        for id in ids {
            if let n = j[String(id)] as? [String: Any] { notes[id] = (n["title"] as? String ?? "", n["icon"] as? String ?? "") }
            else { notes[id] = (String(localized: "Seite (kein Zugriff)"), "🔒") }
        }
    }

    func markRead() async {
        guard let store, let last = messages.last?.seq, last > lastReadSent else { return }
        lastReadSent = last
        _ = try? await store.api("api/chats/\(chatID)/read", method: "POST", body: ["seq": last])
        Task { await model.loadList() }
    }

    func live(_ type: String, _ d: [String: Any]) {
        switch type {
        case "chat.message": Task { await fetchNew() }
        case "chat.deleted": if let id = d["id"] as? String { messages.removeAll { $0.id == id } }
        case "chat.changed": recipients = nil; Task { await load() }
        case "chat.typing":
            if let u = d["user"] as? [String: Any], let n = u["username"] as? String, n != me {
                typing[n] = (u["name"] as? String ?? n, Date())
                Task { try? await Task.sleep(for: .seconds(5.5)); typing = typing.filter { Date().timeIntervalSince($0.value.at) < 5 } }
            }
        default: break
        }
    }

    // MARK: Senden

    func loadRecipients() async {
        guard let store, let d = try? await store.api("api/chats/\(chatID)/recipients") else { return }
        recipients = ShareJSON.obj(d)?["recipients"] as? [[String: Any]]
    }

    /// Empfaenger pruefen und Schluessel versiegeln -> keys-Map; wirft bei ungueltigen Schluesseln
    private func sealKeys(_ K: Data, _ rc: [[String: Any]], ackChanged: Bool) throws -> (keys: [String: String], notReady: [String]) {
        guard let k = model.keys else { throw APIError.other(String(localized: "Deine Schlüssel auf dem Server passen nicht zu diesem Gerät.")) }
        var out: [String: String] = [:], notReady: [String] = []
        for r in rc {
            let uid = r["username"] as? String ?? "", name = r["name"] as? String ?? uid
            guard ShareJSON.bool(r["ready"]) else { notReady.append(name); continue }
            guard let ipk = ChatCrypto.unb64(r["ident_pub"] as? String, 32), let bpk = ChatCrypto.unb64(r["backup_pub"] as? String, 32),
                  ChatCrypto.verify(ChatCrypto.unb64(r["backup_sig"] as? String), ChatCrypto.backupMsg(uid: uid, backupPK: bpk), pk: ipk) else {
                throw APIError.other(String(localized: "Ungültige Schlüssel von \(name) – Senden abgebrochen."))
            }
            if uid == me {
                guard ipk == k.identPK else { throw APIError.other(String(localized: "Deine Schlüssel auf dem Server passen nicht zu diesem Gerät.")) }
            } else {
                let t = ChatTrustStore.observe(me, uid, pk: ChatCrypto.b64(ipk))
                if (t == .changed || ChatTrustStore.known(me, uid)?.changed == true) && !ackChanged {
                    if !changedPeers.contains(uid) { changedPeers.append(uid) }
                    throw APIError.other(String(localized: "Die Sicherheitsnummer von \(name) hat sich geändert – erst prüfen oder „Verstanden“ wählen."))
                }
            }
            guard let sb = ChatCrypto.seal(K, pk: bpk) else { throw APIError.other(String(localized: "Ungültige Schlüssel von \(name) – Senden abgebrochen.")) }
            out["backup:" + uid] = ChatCrypto.b64(sb)
            for case let dv as [String: Any] in (r["devices"] as? [Any] ?? []) {
                guard let did = dv["id"] as? String, let enc = ChatCrypto.unb64(dv["enc_pub"] as? String, 32), let sg = ChatCrypto.unb64(dv["sign_pub"] as? String, 32),
                      ChatCrypto.verify(ChatCrypto.unb64(dv["sig"] as? String), ChatCrypto.deviceMsg(uid: uid, deviceID: did, encPK: enc, signPK: sg), pk: ipk),
                      let s = ChatCrypto.seal(K, pk: enc) else {
                    throw APIError.other(String(localized: "Ungültige Schlüssel von \(name) – Senden abgebrochen."))
                }
                out[did] = ChatCrypto.b64(s)
            }
        }
        return (out, notReady)
    }

    /// Text (+ Anhaenge, Antwort) senden; true = gesendet
    @discardableResult
    func send(text: String, replyTo: String?, files: [ChatPendingFile], kind: String = "msg", title: String? = nil,
              ai: (reply: String, name: String)? = nil) async -> Bool {
        guard let store, let k = model.keys else { return false }
        sending = true; sendError = nil; defer { sending = false }
        do {
            // Anhaenge zuerst (verschluesselter Blob, Server sieht nur Bytes)
            var refs: [ChatFileRef] = []
            var primes: [(ChatFileRef, CGImage?, Data, Data?)] = []
            let cid = String(chatID)
            for f in files {
                guard f.data.count <= model.me.fileMax else {
                    throw APIError.other(String(localized: "„\(f.name)“ ist zu groß (höchstens \(model.me.fileMax / 1_048_576) MB)."))
                }
                // Verschluesseln und Vorschaubild (2.1-c) im Hintergrund - nie im UI-Thread
                let data = f.data, image = ChatMediaLogic.canThumb(f.mime)
                async let enc = Task.detached(priority: .userInitiated) { ChatCrypto.encryptFile(chatID: cid, data: data) }.value
                async let th = ChatThumbUpload.make(data, chatID: cid, enabled: image)
                guard let e = await enc else { throw APIError.other(String(localized: "Upload fehlgeschlagen")) }
                let t = await th
                let d = try await store.apiRaw("api/chats/\(chatID)/files", data: e.blob)
                guard let fid = ShareJSON.obj(d)?["id"] as? String else { throw APIError.other(String(localized: "Upload fehlgeschlagen")) }
                var ref = ChatFileRef(id: fid, key: ChatCrypto.b64(e.key), name: f.name, mime: f.mime, size: f.data.count, w: t?.ow, h: t?.oh)
                if let t {
                    // Vorschaubild ist nur eine Zugabe: scheitert sein Upload, geht das Bild ohne "th" raus
                    if let td = try? await store.apiRaw("api/chats/\(chatID)/files", data: t.blob), let tid = ShareJSON.obj(td)?["id"] as? String {
                        ref.thumb = ChatThumbRef(id: tid, key: ChatCrypto.b64(t.key), w: t.w, h: t.h, size: t.jpegCount)
                    }
                }
                refs.append(ref)
                primes.append((ref, t?.image, e.blob, t?.blob))
            }
            let fit = ChatMediaLogic.sendIDs(refs, maxIDs: model.me.fileIDsPerMsg)
            refs = fit.refs
            let primed = primes.map { p in (refs.first { $0.id == p.0.id } ?? p.0, p.1, p.2, p.3) }
            await Task.detached(priority: .utility) {                     // Platte nicht im UI-Thread beschreiben
                for p in primed { ChatMedia.prime(p.0, image: p.1, origBlob: p.2, thumbBlob: p.3) }
            }.value
            var content = ChatContent(type: kind == "title" ? "title" : "text", text: text, title: title, files: refs, notes: ChatLogic.noteRefs(text),
                                      aiReply: ai?.reply, aiName: ai?.name)
            if kind == "title" { content.text = "" }
            let plain = content.json()
            if recipients == nil { await loadRecipients() }
            for attempt in 0..<2 {
                guard let rc = recipients else { throw APIError.other(String(localized: "Keine Antwort vom Server")) }
                let mid = ChatCrypto.hexID(), ts = Int64(Date().timeIntervalSince1970 * 1000)
                guard let sealed = ChatCrypto.encryptMessage(chatID: String(chatID), msgID: mid, sender: me, device: k.deviceID, signSK: k.sign.sk,
                                                             ts: ts, replyTo: replyTo, plain: plain) else { throw APIError.other(String(localized: "Nicht gesendet")) }
                let (keys, notReady) = try sealKeys(sealed.key, rc, ackChanged: false)
                var body: [String: Any] = ["id": mid, "ts": ts, "sender_device": k.deviceID, "body_ct": sealed.bodyCT, "sig": sealed.sig,
                                           "keys": keys, "files": fit.ids, "kind": kind]   // Original- und Vorschaubild-IDs
                if let replyTo { body["reply_to"] = replyTo }
                if let ai { body["ai_reply"] = ai.reply }                  // Beleg des Servers (nicht signiert, nur Verweis)
                let (d, code) = try await store.apiResponse("api/chats/\(chatID)/messages", method: "POST", body: body)
                if code == 409, attempt == 0, let r = ShareJSON.obj(d)?["recipients"] as? [[String: Any]] { recipients = r; continue }   // neu versiegeln
                if code == 403 { await model.load() }
                guard (200..<300).contains(code) else { throw APIError.http(code, ShareJSON.obj(d).flatMap { ShareJSON.str($0["error"]) } ?? "") }
                if !notReady.isEmpty { sendError = String(localized: "\(notReady.joined(separator: ", ")) hat Chats noch nicht eingerichtet und kann diese Nachricht nicht lesen.") }
                lastSentID = mid
                if kind == "title" { await load(); await model.loadList() } else { await fetchNew() }
                return true
            }
        } catch { sendError = String(localized: "Nicht gesendet: \(error.localizedDescription)") }
        return false
    }

    // MARK: KI im Chat (2.1) - nur auf Zuruf

    /// KI in diesem Chat erlaubt (Chat + Server-Schalter + Assistent fuer mich)?
    var aiAllowed: Bool { (info?.ai ?? false) && (store?.aiChat ?? false) }
    /// Name der KI (Persona; Rueckfall: Name aus der Antwort)
    var aiName: String { store?.aiName ?? String(localized: "Saga KI") }

    /// Erlauben/verbieten (PUT /api/chats/{cid}/ai); alle Mitglieder bekommen chat.changed
    func setAI(_ on: Bool) async {
        guard let store else { return }
        do {
            let (d, code) = try await store.apiResponse("api/chats/\(chatID)/ai", method: "PUT", body: ["allowed": on])
            guard (200..<300).contains(code) else { throw APIError.http(code, ShareJSON.obj(d).flatMap { ShareJSON.str($0["error"]) } ?? "") }
            info?.ai = ShareJSON.obj(d).map { ShareJSON.bool($0["ai"]) } ?? on
            await model.loadList()
        } catch { sendError = error.localizedDescription }
    }

    /// Verlauf fuer die KI: Text-Nachrichten vor der Frage (KI-Antworten mit dem Namen der KI)
    func aiHistory(before qid: String) -> [AIChatLine] {
        let lines = messages.compactMap { m -> AIChatLine? in
            guard m.call == nil, let c = m.content, c.type == "text" else { return nil }
            return AIChatLine(id: m.id, name: m.aiState == .ok ? (c.aiName ?? aiName) : m.senderName, text: c.text)
        }
        return AIChatLogic.history(lines, before: qid)
    }

    /// Nach dem Senden einer Nachricht mit @Name: Frage + Verlauf an den Server, Antwort als Strom zeigen, dann wie jede
    /// Nachricht fuer alle verschluesseln und mit dem Beleg (ai_reply) senden. Laeuft weiter, bis "done" da ist.
    func askAI(question: String, qid: String) {
        guard let store, aiTask == nil else { return }
        let body = AIChatLogic.askBody(q: question, msgID: qid, history: aiHistory(before: qid))
        let name = aiName
        aiPending = ""; aiError = nil
        aiTask = Task { [self] in
            defer { aiTask = nil; aiPending = nil }
            do {
                let (bytes, code, err) = try await store.openStream("api/chats/\(chatID)/ai", method: "POST", body: body, session: Self.aiSession)
                guard code == 200 else {
                    throw APIError.other(err.flatMap(ShareJSON.obj).flatMap { ShareJSON.str($0["error"]) } ?? APIError.http(code, "").localizedDescription)
                }
                var parser = SSEParser(), buf = Data()
                var done: [String: Any]?, fail: String?
                reading: for try await b in bytes {
                    buf.append(b)
                    guard b == 0x0A else { continue }
                    let evs = parser.feed(bytes: buf); buf.removeAll(keepingCapacity: true)
                    for e in evs {
                        let d = e.json
                        switch e.event {
                        case "delta": aiPending = (aiPending ?? "") + (d["t"] as? String ?? "")
                        case "reset": aiPending = ""
                        case "done": done = d; break reading
                        case "error": fail = ShareJSON.str(d["error"]) ?? String(localized: "Fehler"); break reading
                        default: break
                        }
                    }
                }
                if let fail { throw APIError.other(fail) }
                guard let d = done, let text = (d["text"] as? String).map(AIChatLogic.normalized), !text.isEmpty,
                      let reply = (d["reply"] as? [String: Any])?["id"] as? String else { throw APIError.other(String(localized: "Keine Antwort bekommen.")) }
                let pname = ((d["persona"] as? [String: Any])?["name"] as? String) ?? name
                aiPending = text
                let ok = await send(text: text, replyTo: qid, files: [], ai: (reply, pname))
                if !ok { throw APIError.other(sendError ?? String(localized: "Nicht gesendet")) }
            } catch {
                aiError = String(localized: "\(name) konnte nicht antworten: \(error.localizedDescription)")
            }
        }
    }
    private static let aiSession: URLSession = {
        let c = URLSessionConfiguration.default
        c.timeoutIntervalForRequest = 90; c.timeoutIntervalForResource = 600
        return URLSession(configuration: c)
    }()

    /// "tippt …" hoechstens alle 2,5 s
    func typed() {
        guard Date().timeIntervalSince(lastTyping) > 2.5, let store else { return }
        lastTyping = Date()
        Task { _ = try? await store.apiResponse("api/chats/\(chatID)/typing", method: "POST", body: [:]) }
    }

    func delete(_ m: ChatMessage) async {
        guard let store else { return }
        do { _ = try await store.api("api/chats/\(chatID)/messages/\(m.id)", method: "DELETE"); messages.removeAll { $0.id == m.id } }
        catch { sendError = error.localizedDescription }
    }

    /// Blob vom Server (Ciphertext; Platten-Cache und Entschluesseln in ChatMedia, im Hintergrund)
    private var fetcher: ChatBlobFetch {
        let s = store
        return { [weak s] id in
            guard let s else { throw APIError.other(String(localized: "nicht geladen")) }
            return try await s.api("api/chat/files/\(id)")
        }
    }
    /// Vorschaubild eines Bild-Anhangs (2.1-c)
    func thumbnail(_ f: ChatFileRef) async throws -> ChatImage {
        var f = f
        #if DEBUG
        if UserDefaults.standard.bool(forKey: "hkChatNoThumb") { f.thumb = nil }     // nur fuer Tests: wie alte Nachrichten ohne "th"
        #endif
        return try await ChatMedia.shared.thumbnail(f, chatID: String(chatID), fetch: fetcher)
    }
    /// Original (Bild beim Oeffnen, Datei zum Teilen) laden + entschluesseln
    func original(_ f: ChatFileRef) async throws -> Data {
        try await ChatMedia.shared.original(f, chatID: String(chatID), fetch: fetcher)
    }
    func file(_ f: ChatFileRef) async throws -> Data { try await original(f) }

    // MARK: Einstellungen des Chats

    func patch(_ body: [String: Any]) async {
        guard let store else { return }
        if let d = try? await store.api("api/chats/\(chatID)", method: "PATCH", body: body), let c = ShareJSON.obj(d)?["chat"] {
            var s = ChatSummary(c); if s?.kind == "free", let x = s { s?.decryptedTitle = model.decryptTitle(x) ?? info?.decryptedTitle }
            info = s
        }
        await model.loadList()
    }
    func leave() async -> Bool {
        guard let store else { return false }
        do { _ = try await store.api("api/chats/\(chatID)/members/\(me)", method: "DELETE"); await model.loadList(); return true }
        catch { sendError = error.localizedDescription; return false }
    }
    func addMember(_ user: String) async {
        guard let store else { return }
        do { _ = try await store.api("api/chats/\(chatID)/members", method: "POST", body: ["user": user]); recipients = nil; await load() }
        catch { sendError = error.localizedDescription }
    }
    /// Identitaetsschluessel eines Mitglieds (fuer die Sicherheitsnummer)
    func identity(of uid: String) async -> Data? {
        if let r = recipients?.first(where: { $0["username"] as? String == uid }), let p = ChatCrypto.unb64(r["ident_pub"] as? String, 32) { return p }
        guard let store, let d = try? await store.api("api/chat/keys?users=\(uid)"),
              let u = (ShareJSON.obj(d)?["users"] as? [String: Any])?[uid] as? [String: Any] else { return nil }
        return ChatCrypto.unb64(u["ident_pub"] as? String, 32)
    }
    func acknowledge(_ uid: String) { ChatTrustStore.acknowledge(me, uid); changedPeers.removeAll { $0 == uid } }
}
