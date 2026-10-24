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
    /// Systemzeile (2.3, kind 'sys'): z. B. KI ein-/ausgeschaltet - Metadaten vom Heim
    var sys: FedChatLogic.SysLine? = nil
    /// Zustellung in Chats ueber Servergrenzen (2.3): wird zugestellt / nicht zugestellt
    var delivery: FedChatLogic.Delivery = .delivered
    /// 2.4: Position beim Heim (v2-Chats, fseq) - Gelesen-Bestaetigungen zaehlen dort danach; lokal gilt seq
    var fseq = 0
    /// 2.5: Absender als Handle (Format v2), Bearbeitung (Zeit, Anzahl), alte v1-Nachricht eines umgestellten Chats
    var senderHandle: String? = nil
    var editedAt: Double? = nil
    var editN = 0
    var legacy = false
    var date: Date { Date(timeIntervalSince1970: created > 0 ? created : ts / 1000) }
    var text: String { content?.text ?? "" }
}

/// Anhang vor dem Senden
struct ChatPendingFile: Identifiable, Equatable {
    let id = UUID(); var name: String; var mime: String; var data: Data
    /// 2.4: Video als Datei (nie ganz im Speicher) - Kopie im App-Ordner (ChatVideoFiles.pendingDir)
    var fileURL: URL? = nil
    /// 2.4: "optimiert 720p" (Standard auf dem iPhone) oder Original
    var optimize = false
    var isVideo: Bool { fileURL != nil && VideoSendLogic.isVideo(mime) }
    var byteCount: Int64 { fileURL.flatMap { (try? $0.resourceValues(forKeys: [.fileSizeKey]).fileSize).map(Int64.init) } ?? Int64(data.count) }
}

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
    var recipients: [[String: Any]]?
    /// 2.3: selbst gepruefte Schluessel der Mitglieder eines Chats ueber Servergrenzen (Handle -> Stand, TOFU)
    @Published var fk: [String: FedKeyState] = [:]
    var fed: FedChatInfo? { info?.fed }
    /// v2: SHA-256 der KI-Antworten je Beleg (aus "done") - wird in v2 mitsigniert
    private var aiHashes: [String: String] = [:]
    private var lastTyping = Date.distantPast
    var lastReadSent = 0
    /// 2.3: eigene Nachrichten, die das Heim schon bei POST bestaetigt hat (FedDelivery)
    private var confirmed: Set<String> = []
    /// 2.3: letzter Abgleich wartender eigener Nachrichten
    private var lastDeliveryCheck = Date.distantPast
    /// 2.4: Gelesen-Bestaetigungen (ChatReceipts.swift): je Person das groesste "gelesen bis", zuletzt gesendet, sichtbar bis
    @Published var seen: [String: ReceiptLogic.Seen] = [:]
    var rcSent = 0
    var rcAt: Double?
    var rcLater: Task<Void, Never>?
    var seenUpTo = 0
    /// 2.5 (ChatX3.swift): geoeffnete Reaktionen je Nachricht, Rohdaten, Nachrichten-Schluessel K, laufendes Senden,
    /// Anhaenge alter v1-Nachrichten eines umgestellten Chats (AD = chat_id)
    @Published var reactions: [String: [ChatX3.Opened]] = [:]
    var rxRaw: [String: [ChatX3.Reaction]] = [:]
    var msgKeys: [String: Data] = [:]
    var rxSend: [String: Task<Void, Never>] = [:]
    var legacyFiles: Set<String> = []

    init(chatID: Int, model: ChatModel) { self.chatID = chatID; self.model = model }
    var store: Store? { model.store }
    var me: String { model.me.uid }
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
            if let x = s { ChatH2UStore.note(me, [x]) }                  // 2.5: Handles im Chat v1 (Vertrauens-Uebernahme)
            info = s
            if s?.ai == true && !store.aiChat { await store.loadAIStatus() }   // KI im Chat (2.1): Server-Schalter/Persona aktuell
            if s?.isFed == true { await fedLoad() }                     // 2.3: Schluessel der Absender vor dem Entschluesseln
            if s?.isFed == true, s?.kind == "free", let x = s {         // 2.3: Gruppenname v2 (Absender selbst geprueft)
                var l = [x]; await model.decryptTitlesV2(&l); s = l[0]; info = s
            }
            let m = try await store.api("api/chats/\(chatID)/messages?\(model.deviceParam)&limit=50&calls=1")
            messages = []; ingest(m, prepend: false)
            error = nil
            await markRead()
            if s?.isFed != true { await loadRecipients() }
            await loadReceipts()                                        // 2.4: Haken ✓✓ (nach den Schluesseln der Mitglieder)
        } catch { self.error = error.localizedDescription }
    }
    func loadOlder() async {
        guard let store, more, let first = messages.first else { return }
        if let d = try? await store.api("api/chats/\(chatID)/messages?\(model.deviceParam)&before=\(first.seq)&limit=50&calls=1") { ingest(d, prepend: true) }
    }
    func fetchNew() async {
        guard let store else { return }
        if deliveryDue() { await checkDelivery() }
        let after = messages.last?.seq ?? 0
        if let d = try? await store.api("api/chats/\(chatID)/messages?\(model.deviceParam)&after=\(after)&limit=200&calls=1") { ingest(d, prepend: false, onlyMore: false) }
        await markRead()
    }

    private func ingest(_ d: Data, prepend: Bool, onlyMore: Bool = true) {
        guard let j = ShareJSON.obj(d) else { return }
        let devs = j["devices"] as? [String: Any], ids = j["idents"] as? [String: Any]
        var list: [ChatMessage] = []
        for case let m as [String: Any] in (j["messages"] as? [Any] ?? []) {
            let fseq = ShareJSON.int(m["fseq"]) ?? 0                    // 2.4: Position beim Heim (v2)
            let sender = (m["sender"] as? [String: Any])?["username"] as? String ?? ""
            let name = ((m["sender"] as? [String: Any])?["name"] as? String).map { PersonName.shown($0, id: sender) } ?? String(localized: "Gelöschtes Konto")   // D01
            let avatar = ShareJSON.str((m["sender"] as? [String: Any])?["avatar"])
            if (m["kind"] as? String) == "call" {                         // Anruf-Eintrag: Klartext-Metadaten, keine Krypto
                guard let e = (m["body_ct"] as? String).flatMap({ CallLogic.Entry(json: Data($0.utf8)) }) else { continue }
                list.append(ChatMessage(seq: ShareJSON.int(m["seq"]) ?? 0, id: m["id"] as? String ?? "", sender: sender, senderName: name,
                                        ts: ShareJSON.num(m["ts"]) ?? 0, created: ShareJSON.num(m["created"]) ?? 0, expiresAt: ShareJSON.num(m["expires_at"]),
                                        replyTo: nil, content: nil, verified: true,
                                        mine: e.outgoing(me: me) || (fed != nil && (sender == me || e.from == fed?.myHandle)),   // v2: Anrufer als Prinzipal/Handle
                                        senderAvatar: avatar, call: e))
                list[list.count - 1].fseq = fseq
                continue
            }
            if (m["kind"] as? String) == "sys" {                          // Systemzeile (2.3): Klartext-Metadaten vom Heim
                list.append(ChatMessage(seq: ShareJSON.int(m["seq"]) ?? 0, id: m["id"] as? String ?? "", sender: sender, senderName: name,
                                        ts: ShareJSON.num(m["ts"]) ?? 0, created: ShareJSON.num(m["created"]) ?? 0, expiresAt: ShareJSON.num(m["expires_at"]),
                                        replyTo: nil, content: nil, verified: true, mine: false,
                                        sys: FedChatLogic.sysLine(m["body_ct"] as? String, myHandle: fed?.myHandle)))
                list[list.count - 1].fseq = fseq
                continue
            }
            let r: (content: ChatContent?, verified: Bool)?
            let meta: AIReplyMeta?
            let mid = m["id"] as? String ?? ""
            let legacy = fed != nil && FedFlow.isLegacyV1(m)
            if let f = fed, !legacy {                                     // 2.3: saga-chat/v2 (alte Nachrichten umgestellter Chats: v1)
                if let o = model.own, let x = FedFlow.open(m, ref: f.ref, own: o, keys: fk, key: nil) {
                    r = (x.plain.flatMap { ChatContent(json: $0) }, x.verified)
                    if let k = x.key, f.x3OK { msgKeys[mid] = k }            // 2.5: K fuer Reaktionen
                } else { r = nil }
                meta = FedChatLogic.aiMeta(m["ai"], bodyRef: r?.content?.aiReply)
            } else {
                r = model.decrypt(chatID: chatID, raw: m, devices: devs, idents: ids)
                if let f = fed, f.x3OK, let o = model.own, let k = FedFlow.messageKey(m, own: o) { msgKeys[mid] = k }   // umgestellt: alte Nachrichten
                meta = AIReplyMeta(m["ai"])
                if sender != me, let ip = ids?[sender] as? String, ChatTrustStore.observe(me, sender, pk: ip) == .changed || ChatTrustStore.known(me, sender)?.changed == true {
                    if !changedPeers.contains(sender) { changedPeers.append(sender) }
                }
            }
            let aiState = AIChatLogic.check(bodyRef: r?.content?.aiReply, text: r?.content?.text, meta: meta)
            var cm = ChatMessage(seq: ShareJSON.int(m["seq"]) ?? 0, id: m["id"] as? String ?? "", sender: sender, senderName: name,
                                 ts: ShareJSON.num(m["ts"]) ?? 0, created: ShareJSON.num(m["created"]) ?? 0, expiresAt: ShareJSON.num(m["expires_at"]),
                                 replyTo: ShareJSON.str(m["reply_to"]), content: r?.content, verified: r?.verified ?? false, mine: sender == me,
                                 senderAvatar: avatar, aiMeta: meta, aiState: aiState)
            cm.delivery = FedChatLogic.delivery(m["fstate"])
            cm.fseq = fseq
            cm.senderHandle = ShareJSON.str(m["sender_handle"])          // 2.5: Stufe 3 - Absender als Handle
            if let h = cm.senderHandle, !h.isEmpty, h == fed?.myHandle { cm.mine = true }
            cm.legacy = legacy
            if legacy { legacyFiles.formUnion((r?.content?.files ?? []).flatMap { [$0.id] + ($0.thumb.map { [$0.id] } ?? []) }) }
            cm.editN = ShareJSON.int(m["edit_n"]) ?? 0                    // 2.5: bearbeitet (neueste Bearbeitung am Original)
            if m["edit"] != nil { cm = applyEdit(cm, raw: m) }
            if let rx = m["rx"] as? [Any] { rxRaw[mid] = ChatX3.newest(rx.compactMap(ChatX3.Reaction.init)) }   // 2.5: Reaktionen
            list.append(cm)
        }
        openReactions(list.map(\.id))                                   // auch schon bekannte: Reaktionen frisch vom Server
        let known = Set(messages.map(\.id))
        list.removeAll { known.contains($0.id) }
        // Anhaenge diesem Chat zuordnen (Chat loeschen leert dann genau seine Bilder, 2.1)
        ChatBlobCache.shared.note(chatID: chatID, ids: list.flatMap { m in (m.content?.files ?? []).flatMap { [$0.id] + ($0.thumb.map { [$0.id] } ?? []) } })
        if prepend { messages = list + messages; more = ShareJSON.bool(j["more"]) }
        else { messages += list; if messages.count == list.count { more = ShareJSON.bool(j["more"]) } }
        if list.contains(where: { $0.delivery == .pending }) { applyDelivery([:]) }   // 2.3: per POST schon bestaetigt
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
        await sendReceiptIfDue()                                        // 2.4: Gelesen-Bestaetigung (nur wenn sichtbar)
    }

    /// Chat geloescht (2.1): Nachrichten bis upTo (nil = alle) samt entschluesselter Inhalte vergessen; die Ansicht
    /// verwirft dazu Entwurf, Antwort und Anhaenge (`purged`)
    @Published private(set) var purged = 0
    func purge(upTo: Int?) {
        messages = messages.filter { ChatDeleteLogic.keep(seq: $0.seq, isTitle: $0.content?.type == "title", upTo: upTo) }
        more = false                                                    // alles Aeltere ist ohnehin weg
        notes = [:]; typing = [:]; aiTask?.cancel(); aiPending = nil; aiError = nil
        if let c = upTo { info?.clearedSeq = max(info?.clearedSeq ?? 0, c) } else if let l = info?.lastSeq { info?.clearedSeq = max(info?.clearedSeq ?? 0, max(l, 1)) }
        purged += 1
    }

    func live(_ type: String, _ d: [String: Any]) {
        switch type {
        case "chat.message":
            // 2.3: eigene wartende Nachricht jetzt zugestellt bzw. abgewiesen
            if let id = d["id"] as? String, let i = messages.firstIndex(where: { $0.id == id }), messages[i].delivery != .delivered {
                messages[i].delivery = FedChatLogic.liveDelivery(d)
            }
            if let id = d["id"] as? String, let f = ShareJSON.int(d["fseq"]), f > 0, let i = messages.firstIndex(where: { $0.id == id }) {
                messages[i].fseq = f                                    // 2.4: Position der zugestellten eigenen Nachricht
            }
            Task { await fetchNew() }
        case "chat.deleted": if let id = d["id"] as? String { messages.removeAll { $0.id == id } }
        case "chat.changed": recipients = nil; Task { await load() }
        case "chat.react": liveReactions(d)                              // 2.5
        case "chat.edited": liveEdited(d)
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
        guard let store, model.keys != nil else { return false }
        // 2.4: Videos gehen ueber den Video-Weg (Poster, "optimiert 720p", v1 oder saga-file/v2, Upload im Hintergrund,
        // Fortschritt ueber dem Eingabefeld). Der Text reist mit dem ersten Video; andere Anhaenge gehen sofort.
        let vids = files.filter(\.isVideo)
        if !vids.isEmpty, kind == "msg", ai == nil {
            for (i, v) in vids.enumerated() {
                ChatVideoUploads.shared.enqueue(chatID: chatID, file: v, text: i == 0 ? text : "", replyTo: i == 0 ? replyTo : nil)
            }
            let rest = files.filter { !$0.isVideo }
            return rest.isEmpty ? true : await send(text: "", replyTo: nil, files: rest)
        }
        sending = true; sendError = nil; defer { sending = false }
        do {
            // Anhaenge zuerst (verschluesselter Blob, Server sieht nur Bytes)
            var refs: [ChatFileRef] = []
            var primes: [(ChatFileRef, CGImage?, Data, Data?)] = []
            let cid = info?.fileCID ?? String(chatID)                    // AD der Anhaenge: v1 chat_id, v2 chat_ref
            for f in files {
                guard f.data.count <= model.me.fileMax else {
                    throw APIError.other(String(localized: "„\(f.name)“ ist zu groß (höchstens \(model.me.fileMax / 1_048_576) MB)."))
                }
                // Verschluesseln und Vorschaubild (2.1-c) im Hintergrund - nie im UI-Thread
                let data = f.data, image = ChatMediaLogic.canThumb(f.mime)
                async let enc = Task.detached(priority: .userInitiated) { ChatCrypto.encryptChatFile(cid, data: data) }.value
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
            return try await post(text: text, title: title, refs: refs, ids: fit.ids, replyTo: replyTo, kind: kind, ai: ai)
        } catch { sendError = String(localized: "Nicht gesendet: \(error.localizedDescription)") }
        return false
    }

    /// Video fertig hochgeladen (2.4): Nachricht mit den fertigen Anhaengen senden (wirft bei Fehlern)
    func sendPrepared(text: String, replyTo: String?, refs: [ChatFileRef]) async throws {
        if info == nil { await prepareForSend() }
        let fit = ChatMediaLogic.sendIDs(refs, maxIDs: model.me.fileIDsPerMsg)
        guard try await post(text: text, title: nil, refs: fit.refs, ids: fit.ids, replyTo: replyTo, kind: "msg", ai: nil) else {
            throw APIError.other(String(localized: "Nicht gesendet"))
        }
    }
    /// Nur was zum Senden noetig ist (ohne Verlauf/Gelesen): Chat, Schluessel der Mitglieder - fuer Videos, deren Upload
    /// fertig wurde, waehrend der Chat nicht offen war
    func prepareForSend() async {
        guard let store, let d = try? await store.api("api/chats/\(chatID)?\(model.deviceParam)"), let c = ShareJSON.obj(d)?["chat"] else { return }
        info = ChatSummary(c)
        if info?.isFed == true { await fedLoad() } else { await loadRecipients() }
    }

    /// Inhalt verschluesseln und senden (lokal saga-chat/v1, ueber Servergrenzen v2); true = gesendet
    func post(text: String, title: String?, refs: [ChatFileRef], ids: [String], replyTo: String?, kind: String,
                      ai: (reply: String, name: String)?) async throws -> Bool {
        guard let store, let k = model.keys else { return false }
        let fit = (refs: refs, ids: ids)
        do {
            var content = ChatContent(type: kind == "title" ? "title" : "text", text: text, title: title, files: fit.refs, notes: ChatLogic.noteRefs(text),
                                      aiReply: ai?.reply, aiName: ai?.name)
            if kind == "title" { content.text = "" }
            let plain = content.json()
            if fed != nil {                                              // 2.3: Chat ueber Servergrenzen (saga-chat/v2)
                try await sendV2(plain, replyTo: replyTo, fileIDs: fit.ids, ai: ai, kind: kind)
                return true
            }
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
                if code == 409, ShareJSON.bool(ShareJSON.obj(d)?["chat_changed"]) {       // 2.5: gerade auf Stufe 3 umgestellt
                    await load()
                    guard fed != nil else { throw APIError.http(code, ShareJSON.obj(d).flatMap { ShareJSON.str($0["error"]) } ?? "") }
                    // Anhaenge sind noch fuer das alte Format verschluesselt (AD chat_id): nicht still kaputt senden
                    guard fit.ids.isEmpty else { throw APIError.other(String(localized: "Dieser Chat wurde gerade umgestellt – bitte die Anhänge erneut auswählen.")) }
                    try await sendV2(plain, replyTo: replyTo, fileIDs: fit.ids, ai: ai, kind: kind)
                    return true
                }
                if code == 409, attempt == 0, let r = ShareJSON.obj(d)?["recipients"] as? [[String: Any]] { recipients = r; continue }   // neu versiegeln
                if code == 403 { await model.load() }
                guard (200..<300).contains(code) else { throw APIError.http(code, ShareJSON.obj(d).flatMap { ShareJSON.str($0["error"]) } ?? "") }
                if !notReady.isEmpty { sendError = String(localized: "\(notReady.joined(separator: ", ")) hat Chats noch nicht eingerichtet und kann diese Nachricht nicht lesen.") }
                lastSentID = mid
                if kind == "title" { await load(); await model.loadList() } else { await fetchNew() }
                return true
            }
        }
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
                if let h = (d["reply"] as? [String: Any])?["sha256"] as? String { aiHashes[reply] = h }   // v2: Hash wird mitsigniert
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
        return try await ChatMedia.shared.thumbnail(f, chatID: fileCID(f), fetch: fetcher)
    }
    /// Original (Bild beim Oeffnen, Datei zum Teilen) laden + entschluesseln
    func original(_ f: ChatFileRef) async throws -> Data {
        try await ChatMedia.shared.original(f, chatID: fileCID(f), fetch: fetcher)
    }
    /// AD eines Anhangs: v1 chat_id, v2 chat_ref; 2.5: Anhaenge alter v1-Nachrichten eines umgestellten Chats weiter chat_id
    func fileCID(_ f: ChatFileRef) -> String {
        legacyFiles.contains(f.id) || f.thumb.map({ legacyFiles.contains($0.id) }) == true ? String(chatID) : (info?.fileCID ?? String(chatID))
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
    /// Person hinzufuegen: Konto hier (`user`) bzw. Person eines anderen Servers (`handle`, nur Gruppen ueber Servergrenzen);
    /// danach den Gruppennamen erneut senden, damit Neue ihn lesen koennen (sie sehen nur Nachrichten ab dem Beitritt)
    func addMember(_ user: String) async { await addMember(["user": user]) }
    func addMember(handle: String) async { await addMember(["handle": handle]) }
    private func addMember(_ body: [String: Any]) async {
        guard let store else { return }
        do {
            let (d, code) = try await store.apiResponse("api/chats/\(chatID)/members", method: "POST", body: body)
            guard (200..<300).contains(code) else { throw APIError.http(code, ShareJSON.obj(d).flatMap { ShareJSON.str($0["error"]) } ?? "") }
            recipients = nil; sendError = nil
            await load()
            if info?.kind == "free", let t = info?.decryptedTitle, !t.isEmpty {
                _ = await send(text: "", replyTo: nil, files: [], kind: "title", title: t)
            }
        } catch { sendError = error.localizedDescription }
    }
    /// Mitglied entfernen (nur Admins einer freien Gruppe); `who` = members[].username
    func removeMember(_ who: String) async {
        guard let store else { return }
        var allowed = CharacterSet.alphanumerics; allowed.insert(charactersIn: "~.-_")
        do {
            let (d, code) = try await store.apiResponse("api/chats/\(chatID)/members/" + (who.addingPercentEncoding(withAllowedCharacters: allowed) ?? who), method: "DELETE")
            guard (200..<300).contains(code) else { throw APIError.http(code, ShareJSON.obj(d).flatMap { ShareJSON.str($0["error"]) } ?? "") }
            recipients = nil; sendError = nil
            await load(); await model.loadList()
        } catch { sendError = error.localizedDescription }
    }
    /// Identitaetsschluessel eines Mitglieds (fuer die Sicherheitsnummer)
    func identity(of uid: String) async -> Data? {
        if let r = recipients?.first(where: { $0["username"] as? String == uid }), let p = ChatCrypto.unb64(r["ident_pub"] as? String, 32) { return p }
        // nur Mitglieder (Server 2.4: Schluessel nur fuer Personen mit gemeinsamem Chat, Antwort unter der angefragten Kennung)
        guard let store, let q = uid.addingPercentEncoding(withAllowedCharacters: .alphanumerics),
              let d = try? await store.api("api/chat/keys?users=\(q)"), let u = PersonRef.keyEntry(ShareJSON.obj(d), for: uid) else { return nil }
        return ChatCrypto.unb64(u["ident_pub"] as? String, 32)
    }
    func acknowledge(_ uid: String) { ChatTrustStore.acknowledge(me, uid); changedPeers.removeAll { $0 == uid } }
}

// MARK: - Chats ueber Servergrenzen (2.3, saga-chat/v2, docs/APP-API-FED.md Abschnitt 6)

extension ConversationModel {
    /// Schluessel aller Mitglieder ueber den eigenen Server holen, selbst pruefen, TOFU (Warnung/Blockade erscheint sofort)
    func fedLoad(fresh: Bool = false) async {
        guard let f = fed else { return }
        if let r = try? await model.fedKeys(f.handles, fresh: fresh, own: f.myHandle) { fk = r }
    }
    // MARK: Zustellstand eigener Nachrichten (FedDelivery)

    private var deliveryItems: [FedDelivery.Item] {
        messages.map { FedDelivery.Item(seq: $0.seq, id: $0.id, mine: $0.mine, delivery: $0.delivery) }
    }
    func deliveryDue() -> Bool { FedDelivery.due(deliveryItems, lastCheck: lastDeliveryCheck, now: Date()) }

    /// Stand wartender eigener Nachrichten beim Server nachsehen (ab der aeltesten) - Server bis 2.3.0 melden die sofortige
    /// Bestaetigung des Heims nicht als Ereignis; so heilen auch schon haengende "wird zugestellt" von selbst
    func checkDelivery() async {
        guard let store, let from = FedDelivery.oldestPending(deliveryItems) else { return }
        lastDeliveryCheck = Date()
        guard let d = try? await store.api("api/chats/\(chatID)/messages?\(model.deviceParam)&after=\(from - 1)&limit=200&calls=1") else { return }
        let r = FedDelivery.parse(ShareJSON.obj(d))
        let gone = FedDelivery.gone(deliveryItems, server: r.server, more: r.more, maxSeq: r.maxSeq, from: from)
        if !gone.isEmpty { messages.removeAll { gone.contains($0.id) } }
        applyDelivery(r.server)
    }

    /// Zustellstand bekannter Nachrichten uebernehmen (Serverliste id -> Stand, dazu per POST bestaetigte)
    private func applyDelivery(_ server: [String: FedDelivery.State]) {
        for c in FedDelivery.apply(deliveryItems, server: server, confirmed: confirmed) where c.index < messages.count {
            messages[c.index].delivery = c.state
        }
    }

    /// Solange der Chat sichtbar ist (Aufrufer: .task der Ansicht, nur im Vordergrund): alle 15 s abgleichen,
    /// aber nur, wenn eigene Nachrichten warten
    func deliveryLoop() async {
        while !Task.isCancelled {
            try? await Task.sleep(for: .seconds(FedDelivery.checkEvery))
            if Task.isCancelled { return }
            if deliveryDue() { await checkDelivery() }
        }
    }

    /// Personen anderer Server mit Warnung/Blockade/ungueltigen Schluesseln
    var fedProblems: [(member: FedMember, verdict: FedChatLogic.Verdict)] {
        (fed?.members ?? []).filter { !$0.me }.compactMap { m in
            guard let v = fk[m.handle]?.verdict, FedChatLogic.blocksSending(v) else { return nil }
            return (m, v)
        }
    }

    /// Senden v2: Empfaenger = aktive Geraete aus den selbst geprueften Schluesseln aller Mitglieder + backup:<handle>;
    /// 409 mit handles (Mitglieder/Geraete geaendert) -> einmal frisch holen und neu versiegeln
    func sendV2(_ plain: Data, replyTo: String?, fileIDs: [String], ai: (reply: String, name: String)?, kind: String = "msg",
                extra: [String: Any] = [:]) async throws {
        guard let store, let k = model.keys, let f = fed else { return }
        let aiHash = ai.map { aiHashes[$0.reply] ?? AIChatLogic.sha256Hex(AIChatLogic.normalized(ChatContent(json: plain)?.text ?? "")) }
        for attempt in 0..<2 {
            let keys = try await model.fedKeys(f.handles, fresh: attempt > 0, own: f.myHandle)
            fk = keys
            switch FedChatLogic.sendCheck(members: f.members, verdicts: keys.mapValues(\.verdict), myHandle: f.myHandle) {
            case .blocked(let names):
                throw APIError.other(String(localized: "Die Sicherheitsnummer von \(names.joined(separator: ", ")) hat sich geändert – erst prüfen oder „Verstanden“ wählen."))
            case .ownKeys:
                await model.fedCerts()
                throw APIError.other(String(localized: "Deine Schlüssel für Chats über Servergrenzen sind noch nicht bereit – bitte gleich noch einmal versuchen."))
            case .ok: break
            }
            guard let (body, mid) = FedFlow.sendBody(info: f, own: k.fedOwn, keys: keys, plain: plain, replyTo: replyTo,
                                                     ai: aiHash.flatMap { h in ai.map { (h, $0.reply) } }, fileIDs: fileIDs, kind: kind, extra: extra)
            else { throw APIError.other(String(localized: "Nicht gesendet")) }
            let (d, code) = try await store.apiResponse("api/chats/\(chatID)/messages", method: "POST", body: body)
            let j = ShareJSON.obj(d)
            if FedChatLogic.needsFreshKeys(code: code, body: j), attempt == 0 { continue }
            if code == 403 { await model.fedCerts() }
            guard (200..<300).contains(code) else { throw APIError.http(code, j.flatMap { ShareJSON.str($0["error"]) } ?? "") }
            let missing = (fed?.members ?? []).filter { !$0.me && keys[$0.handle]?.keys == nil }.map(\.name)
            if !missing.isEmpty { sendError = String(localized: "\(missing.joined(separator: ", ")) hat Chats noch nicht eingerichtet und kann diese Nachricht nicht lesen.") }
            if ShareJSON.bool(j?["pending"]) { model.toast = String(localized: "Der andere Server ist gerade nicht erreichbar – die Nachricht wird zugestellt, sobald er wieder da ist.") }
            if FedDelivery.confirmedByPost(ShareJSON.bool(j?["pending"])) {
                // vom Heim bestaetigt: eine schon (ueber das Live-Ereignis) wartend geladene Kopie gleich als zugestellt zeigen
                confirmed.insert(mid); applyDelivery([:])
            }
            if kind != "edit" { lastSentID = mid }
            if kind == "title" { await load(); await model.loadList(); return }   // neuer Gruppenname
            await fetchNew()
            if info?.fed?.request == true { await load(); await model.loadList() }   // wer schreibt, nimmt an
            return
        }
        throw APIError.other(String(localized: "Die Mitglieder oder ihre Geräte haben sich geändert – bitte erneut senden."))
    }

    /// "Erneut senden" (nicht zugestellt): neu verschluesseln und senden, alte Nachricht loeschen
    func resend(_ m: ChatMessage) async {
        guard let store, var c = m.content, m.mine, m.delivery == .failed else { return }
        sending = true; sendError = nil; defer { sending = false }
        do {
            // Anhaenge bleiben beim Server an die alte Nachricht gebunden (dieselben IDs -> 400 "Anhang nicht gefunden"):
            // denselben Ciphertext (gleicher Chat, gleicher Schluessel) als neue Dateien hochladen
            var files: [ChatFileRef] = []
            for var f in c.files {
                f.id = f.isV2 ? try await ChatVideoUploads.shared.copyV2(f, chatID: chatID) : try await reupload(f.id)   // 2.4: v2 als Strom
                if var th = f.thumb { th.id = try await reupload(th.id); f.thumb = th }
                files.append(f)
            }
            c.files = files
            let ids = ChatMediaLogic.sendIDs(c.files, maxIDs: model.me.fileIDsPerMsg).ids
            try await sendV2(c.json(), replyTo: m.replyTo, fileIDs: ids, ai: nil)
            _ = try? await store.apiResponse("api/chats/\(chatID)/messages/\(m.id)", method: "DELETE")
            messages.removeAll { $0.id == m.id }
            await fetchNew()
        } catch { sendError = String(localized: "Nicht gesendet: \(error.localizedDescription)") }
    }
    /// Ciphertext eines Anhangs (Platten-Cache, sonst Server) als neue Datei hochladen -> neue Datei-ID
    private func reupload(_ id: String) async throws -> String {
        guard let store else { throw APIError.other(String(localized: "Upload fehlgeschlagen")) }
        let blob = try await ChatMedia.blob(id, fetch: fetcher)
        let d = try await store.apiRaw("api/chats/\(chatID)/files", data: blob)
        guard let nid = ShareJSON.obj(d)?["id"] as? String else { throw APIError.other(String(localized: "Upload fehlgeschlagen")) }
        return nid
    }

    /// Nachrichtenanfrage: annehmen, ablehnen, sperren (Person oder ganzer Server). true = Chat schliessen
    func respond(_ action: String, scope: String = "person") async -> Bool {
        guard let store else { return false }
        do {
            let (d, code) = try await store.apiResponse("api/chats/\(chatID)/request", method: "POST", body: ["action": action, "scope": scope])
            guard (200..<300).contains(code) else { throw APIError.http(code, ShareJSON.obj(d).flatMap { ShareJSON.str($0["error"]) } ?? "") }
            switch action {
            case "accept": model.toast = String(localized: "Anfrage angenommen."); await load(); await model.loadList(); return false
            case "block": model.toast = String(localized: "Gesperrt – von dort kommen keine Anfragen mehr.")
            default: model.toast = String(localized: "Anfrage abgelehnt.")
            }
            await model.loadList()
            return true
        } catch { sendError = error.localizedDescription; return false }
    }

    /// TOFU v2: "Verstanden" bzw. "Als ueberprueft markieren"
    func fedConfirm(_ handle: String, verified: Bool) async {
        guard let ip = fk[handle]?.keys?.identPub else { return }
        model.fedVerify(handle, identPub: ip, verified: verified)
        await fedLoad()
    }
    /// Sicherheitsnummer v2 mit einer Person (nil: noch keine Schluessel)
    func safetyV2(_ handle: String) async -> (number: String, state: FedKeyState)? {
        guard let f = fed else { return nil }
        if let r = try? await model.fedKeys([handle], own: f.myHandle) { fk.merge(r) { $1 } }
        guard let st = fk[handle], let ip = st.keys?.identPK, let n = model.safetyV2(myHandle: f.myHandle, handle: handle, identPK: ip) else { return nil }
        return (n, st)
    }
}
