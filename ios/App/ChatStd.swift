import SwiftUI
import ImageIO
import UniformTypeIdentifiers

// Saga 2.5 App-Runde "Chat-Standard" (Server docs/APP-API-CHAT.md 6a, 8, 9; Web static/chat.js): Modell-Seite.
// Logik in ChatStdLogic.swift/ChatFmtLogic.swift (Tests/Logic/ChatStdTests.swift). Jede Funktion nur mit dem Feature in
// /api/version, sonst wie bisher.

extension Store {
    var chatStd: ChatStdFeatures { ChatStdFeatures { self.features.has($0) } }
}

// MARK: - Chatliste: Stumm, Anheften, ungelesen, ausblenden, Sperrliste

extension ChatModel {
    var std: ChatStdFeatures { store?.chatStd ?? ChatStdFeatures() }

    /// PATCH /api/chats/{cid} (Felder frei kombinierbar) -> Meldung des Servers (409 "Hoechstens 5 …") als Hinweis
    @discardableResult
    func patchChat(_ cid: Int, _ body: [String: Any]) async -> ChatSummary? {
        guard let store else { return nil }
        do {
            let (d, code) = try await store.apiResponse("api/chats/\(cid)", method: "PATCH", body: body)
            guard (200..<300).contains(code) else { throw APIError.http(code, ShareJSON.obj(d).flatMap { ShareJSON.str($0["error"]) } ?? "") }
            await loadList()
            if hiddenOpen { await loadHidden() }
            var s = ShareJSON.obj(d)?["chat"].flatMap(ChatSummary.init)
            if s?.kind == "free", let x = s { s?.decryptedTitle = decryptTitle(x) ?? chats.first { $0.id == cid }?.decryptedTitle }
            if let s, let o = open, o.chatID == cid { o.info = o.info.map { var i = s; i.desc = $0.desc; i.avatarFile = $0.avatarFile; return i } ?? s }
            return s
        } catch { toast = error.localizedDescription; return nil }
    }
    func mute(_ cid: Int, _ c: ChatStd.MuteChoice) async {
        if std.mute2 { await patchChat(cid, ["muted_until": ChatStd.mutedUntil(c, now: Date())]) }
        else { await patchChat(cid, ["muted": c != .off]) }
    }
    func setNotify(_ cid: Int, _ n: String) async { await patchChat(cid, ["notify": n]) }
    func setPinned(_ c: ChatSummary, _ on: Bool) async { await patchChat(c.id, ["pinned": on]) }
    func markUnread(_ c: ChatSummary) async { await patchChat(c.id, ["unread_mark": true]) }
    func setHidden(_ c: ChatSummary, _ on: Bool) async {
        if await patchChat(c.id, ["hidden": on]) != nil, on {
            toast = String(localized: "Ausgeblendet – der Chat kommt zurück, wenn dich jemand erwähnt.")
        }
    }
    /// GET /api/chats?hidden=1 - nur ausgeblendete (Bereich am Ende der Liste)
    func loadHidden() async {
        guard let store, std.hide, let d = try? await store.api("api/chats?hidden=1&\(deviceParam)"), let j = ShareJSON.obj(d) else { return }
        var list = (j["chats"] as? [Any] ?? []).compactMap(ChatSummary.init)
        for i in list.indices where list[i].kind == "free" { list[i].decryptedTitle = decryptTitle(list[i]) }
        hiddenChats = list
    }

    // Sperrliste (block2)
    func loadBlocks() async {
        guard let store, std.block, let d = try? await store.api("api/chat/blocks") else { return }
        blocks = ChatStd.blocks(ShareJSON.obj(d))
    }
    @discardableResult
    func block(_ target: String, name: String) async -> Bool {
        guard let store else { return false }
        do {
            let (d, code) = try await store.apiResponse("api/chat/blocks", method: "POST", body: ["target": target])
            guard (200..<300).contains(code) else { throw APIError.http(code, ShareJSON.obj(d).flatMap { ShareJSON.str($0["error"]) } ?? "") }
            toast = String(localized: "\(name) ist gesperrt.")
            await loadBlocks(); await loadList()
            if let o = open { await o.load() }
            return true
        } catch { toast = error.localizedDescription; return false }
    }
    func unblock(_ target: String) async {
        guard let store else { return }
        do {
            let (d, code) = try await store.apiResponse(ChatStd.unblockPath(target), method: "DELETE")
            guard (200..<300).contains(code) else { throw APIError.http(code, ShareJSON.obj(d).flatMap { ShareJSON.str($0["error"]) } ?? "") }
            toast = String(localized: "Entsperrt.")
            blocks.removeAll { $0.target == target }
            await loadList()
            if let o = open { await o.load() }
        } catch { toast = error.localizedDescription }
    }
    /// Melden (report1): Text nur, was die Person freiwillig mitschickt
    func report(chat: Int, msgs: [String], reason: ChatStd.ReportReason, text: String?) async -> String? {
        guard let store else { return nil }
        do {
            let (d, code) = try await store.apiResponse("api/chat/report", method: "POST", body: ChatStd.reportBody(chat: chat, msgs: msgs, reason: reason, text: text))
            guard (200..<300).contains(code) else { throw APIError.http(code, ShareJSON.obj(d).flatMap { ShareJSON.str($0["error"]) } ?? "") }
            toast = String(localized: "Danke – die Meldung ist bei den Admins.")
            return nil
        } catch { return error.localizedDescription }
    }

    /// Bild/Beschreibung freier Gruppen: Steuer-Nachrichten entschluesseln und pruefen (v1 bzw. v2), gemerkt je Nachricht
    func decryptGroupInfo(_ list: inout [ChatSummary]) async {
        guard std.groupInfo2 else { return }
        for i in list.indices where list[i].kind == "free" {
            for (key, raw) in [("d", list[i].descMsg), ("a", list[i].avatarMsg)] {
                guard let m = raw, let mid = m["id"] as? String else { continue }
                let memoKey = "\(list[i].id):\(key)"
                var ctl: ChatStd.Control?
                if let x = infoMemo[memoKey], x.msg == mid { ctl = x.ctl }
                else {
                    var plain: Data?
                    if let f = list[i].fed {
                        if let h = ShareJSON.str(m["sender_handle"]), let fk = try? await fedKeys([h]), let o = own,
                           let r = FedFlow.open(m, ref: f.ref, own: o, keys: fk), r.verified { plain = r.plain }
                    } else if let o = own, let r = FedFlow.openV1(m, chatID: String(list[i].id), own: o, devices: nil, idents: nil), r.verified {
                        plain = r.plain
                    }
                    ctl = ChatStd.control(plain)
                    if ctl != nil { infoMemo[memoKey] = (mid, ctl) }
                }
                switch ctl {
                case .desc(let t)?: list[i].desc = t.isEmpty ? nil : t
                case .avatar(let f)?: list[i].avatarFile = f
                default: break
                }
            }
        }
    }
    /// Entwuerfe (nur auf dem Geraet, je Konto): fremde bzw. beim Abmelden alle verwerfen
    static func dropDrafts(keep account: String?) {
        let ud = UserDefaults.standard
        for k in ChatStd.foreignDraftKeys(Array(ud.dictionaryRepresentation().keys), account: account) { ud.removeObject(forKey: k) }
    }
}

// MARK: - Offener Chat

extension ConversationModel {
    var std: ChatStdFeatures { model.std }
    /// Grabsteine nur mit moddel1 abfragen
    var tombQuery: String { model.std.modDel ? "&tombs=1" : "" }

    // Gruppenbild/Beschreibung (groupinfo2)
    func setDescription(_ text: String) async -> Bool {
        let body = ChatStd.descBody(text)
        guard let plain = try? JSONSerialization.data(withJSONObject: body, options: [.sortedKeys]) else { return false }
        return await sendControl(kind: "desc", plain: plain, refs: [], ids: [])
    }
    /// Bild quadratisch (Mitte) auf 256 px als JPEG, verschluesselt hochladen, Steuer-Nachricht senden; nil = entfernen
    func setGroupAvatar(_ data: Data?) async -> Bool {
        guard let store else { return false }
        guard let data else {
            guard let plain = try? JSONSerialization.data(withJSONObject: ChatStd.avatarBody(nil), options: [.sortedKeys]) else { return false }
            return await sendControl(kind: "avatar", plain: plain, refs: [], ids: [])
        }
        guard let (jpeg, cg) = GroupAvatarImage.square(data, px: ChatStd.avatarPx) else { sendError = String(localized: "Bild nicht lesbar."); return false }
        let cid = info?.fileCID ?? String(chatID)
        guard let e = ChatCrypto.encryptChatFile(cid, data: jpeg) else { return false }
        do {
            let d = try await store.apiRaw("api/chats/\(chatID)/files", data: e.blob)
            guard let fid = ShareJSON.obj(d)?["id"] as? String else { throw APIError.other(String(localized: "Upload fehlgeschlagen")) }
            let ref = ChatFileRef(id: fid, key: ChatCrypto.b64(e.key), name: "gruppe.jpg", mime: "image/jpeg", size: jpeg.count, w: ChatStd.avatarPx, h: ChatStd.avatarPx)
            ChatMedia.remember(ChatImage(cg, w: ChatStd.avatarPx, h: ChatStd.avatarPx), for: fid)
            guard let plain = try? JSONSerialization.data(withJSONObject: ChatStd.avatarBody(ref), options: [.sortedKeys]) else { return false }
            return await sendControl(kind: "avatar", plain: plain, refs: [], ids: [fid])
        } catch { sendError = error.localizedDescription; return false }
    }
    private func sendControl(kind: String, plain: Data, refs: [ChatFileRef], ids: [String]) async -> Bool {
        sending = true; sendError = nil; defer { sending = false }
        do {
            let ok = try await post(text: "", title: nil, refs: refs, ids: ids, replyTo: nil, kind: kind, ai: nil, plainOverride: plain)
            if ok { model.toast = kind == "desc" ? String(localized: "Beschreibung gespeichert.") : String(localized: "Gruppenbild gespeichert.") }
            return ok
        } catch { sendError = error.localizedDescription; return false }
    }
    /// Gruppenrechte (nur Admins): PATCH perm_info/perm_send
    func setPerm(_ key: String, admins: Bool) async {
        await model.patchChat(chatID, [key: admins ? "admins" : "all"])
        await load()
    }

    // Anheften (msgpin1)
    func pin(_ m: ChatMessage, _ on: Bool) async {
        guard let store else { return }
        do {
            let (d, code) = try await store.apiResponse("api/chats/\(chatID)/pins/\(m.id)", method: on ? "POST" : "DELETE")
            guard (200..<300).contains(code) else { throw APIError.http(code, ShareJSON.obj(d).flatMap { ShareJSON.str($0["error"]) } ?? "") }
            info?.std.pins = (ShareJSON.obj(d)?["pins"] as? [Any] ?? []).compactMap { ShareJSON.str($0) }
            model.toast = on ? String(localized: "Angeheftet.") : String(localized: "Gelöst.")
        } catch { model.toast = error.localizedDescription }
    }
    // Admin-Loeschen (moddel1): Grabstein bleibt
    func modDelete(_ m: ChatMessage) async {
        guard let store else { return }
        do {
            let (d, code) = try await store.apiResponse("api/chats/\(chatID)/messages/\(m.id)", method: "DELETE")
            guard (200..<300).contains(code) else { throw APIError.http(code, ShareJSON.obj(d).flatMap { ShareJSON.str($0["error"]) } ?? "") }
            if let i = messages.firstIndex(where: { $0.id == m.id }) { messages[i].tomb = true; messages[i].content = nil }
            info?.std.pins.removeAll { $0 == m.id }
        } catch { sendError = error.localizedDescription }
    }
    /// Absender einer Nachricht gesperrt? (members[].blocked)
    func senderBlocked(_ m: ChatMessage) -> Bool {
        guard let i = info else { return false }
        return i.members.contains { $0.blocked && ($0.username == m.sender || ($0.handle != nil && $0.handle == m.senderHandle)) }
    }
    /// Sperr-Kennung des Absenders (Handle, nie Anmeldename)
    func blockTarget(_ m: ChatMessage) -> String? {
        let mem = info?.members.first { $0.username == m.sender }
        return ChatStd.blockTarget(senderHandle: m.senderHandle, member: mem.map { ($0.handle, $0.ref) })
    }
    // Erwaehnungen
    func mentionName(_ r: String) -> String? {
        ChatStd.mentionName(r, members: info?.members ?? [], meRef: info?.std.meRef, myHandle: fed?.myHandle, myName: model.me.name.isEmpty ? String(localized: "Du") : model.me.name)
    }
    func mentionsMe(_ m: ChatMessage) -> Bool {
        std.mention && !m.mine && ChatStd.mentionsMe(m.text, meRef: info?.std.meRef, myHandle: fed?.myHandle)
    }
    /// Text fuer Vorschauen (Pins, Antwort-Zitat): Marken weg, Erwaehnungen als @Name
    func plainText(_ t: String) -> String {
        let s = std.fmt ? ChatFmt.strip(t) : t
        return std.mention ? ChatStd.plainMentions(s, name: mentionName) : s
    }

    // Linkvorschau (unfurl1)
    func unfurl(_ url: String) async -> ChatStd.Unfurl? {
        guard let store, std.unfurl, let (d, code) = try? await store.apiResponse(ChatStd.unfurlPath(url)), code == 200 else { return nil }
        return ChatStd.Unfurl(ShareJSON.obj(d))
    }
    /// Bild der Vorschau verschluesselt hochladen (wie ein Vorschaubild); scheitert es, geht die Karte ohne Bild
    func previewForSend(_ u: ChatStd.Unfurl) async -> ChatStd.Preview {
        guard let store, let im = u.image, let e = ChatCrypto.encryptChatFile(info?.fileCID ?? String(chatID), data: im.data),
              let d = try? await store.apiRaw("api/chats/\(chatID)/files", data: e.blob), let id = ShareJSON.obj(d)?["id"] as? String
        else { return ChatStd.Preview(u, img: nil) }
        return ChatStd.Preview(u, img: ChatThumbRef(id: id, key: ChatCrypto.b64(e.key), w: im.w, h: im.h, size: im.data.count))
    }

    // Medienuebersicht (chatmedia1): Seite mit Anhaengen entschluesseln
    func loadMedia(before: Int?) async -> (items: [(ChatFileRef, ChatMessage)], before: Int?, more: Bool) {
        guard let store, let d = try? await store.api(ChatStd.mediaPath(chatID, device: model.deviceParam, before: before)), let j = ShareJSON.obj(d) else { return ([], before, false) }
        var out: [(ChatFileRef, ChatMessage)] = []
        let devs = j["devices"] as? [String: Any], ids = j["idents"] as? [String: Any]
        let raw = j["messages"] as? [[String: Any]] ?? []
        for m in raw where !ChatStd.isTomb(m) {
            let content: ChatContent?
            if let f = fed, !FedFlow.isLegacyV1(m), let o = model.own { content = FedFlow.open(m, ref: f.ref, own: o, keys: fk, key: nil)?.plain.flatMap { ChatContent(json: $0) } }
            else { content = model.decrypt(chatID: chatID, raw: m, devices: devs, idents: ids)?.content }
            guard let c = content else { continue }
            let sender = (m["sender"] as? [String: Any])?["username"] as? String ?? ""
            let cm = ChatMessage(seq: ShareJSON.int(m["seq"]) ?? 0, id: m["id"] as? String ?? "", sender: sender, senderName: "",
                                 ts: ShareJSON.num(m["ts"]) ?? 0, created: ShareJSON.num(m["created"]) ?? 0, expiresAt: nil, replyTo: nil,
                                 content: c, verified: true, mine: sender == me)
            if FedFlow.isLegacyV1(m) { legacyFiles.formUnion(c.files.flatMap { [$0.id] + ($0.thumb.map { [$0.id] } ?? []) }) }
            for f in c.files where ChatStd.mediaKind(f) != .skip { out.append((f, cm)) }
        }
        return (out, raw.first.flatMap { ShareJSON.int($0["seq"]) } ?? before, ShareJSON.bool(j["more"]))
    }
}

/// Gruppenbild: Mitte quadratisch ausschneiden, auf px verkleinern, JPEG
enum GroupAvatarImage {
    static func square(_ data: Data, px: Int) -> (Data, CGImage)? {
        guard let src = CGImageSourceCreateWithData(data as CFData, nil),
              let full = CGImageSourceCreateThumbnailAtIndex(src, 0, [kCGImageSourceCreateThumbnailFromImageAlways: true, kCGImageSourceCreateThumbnailWithTransform: true,
                                                                       kCGImageSourceThumbnailMaxPixelSize: 2048] as CFDictionary) else { return nil }
        let s = min(full.width, full.height)
        guard s > 0, let crop = full.cropping(to: CGRect(x: (full.width - s) / 2, y: (full.height - s) / 2, width: s, height: s)),
              let ctx = CGContext(data: nil, width: px, height: px, bitsPerComponent: 8, bytesPerRow: 0, space: CGColorSpaceCreateDeviceRGB(),
                                  bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue) else { return nil }
        ctx.setFillColor(CGColor(red: 1, green: 1, blue: 1, alpha: 1)); ctx.fill(CGRect(x: 0, y: 0, width: px, height: px))
        ctx.interpolationQuality = .high
        ctx.draw(crop, in: CGRect(x: 0, y: 0, width: px, height: px))
        guard let img = ctx.makeImage() else { return nil }
        let out = NSMutableData()
        guard let dst = CGImageDestinationCreateWithData(out, UTType.jpeg.identifier as CFString, 1, nil) else { return nil }
        CGImageDestinationAddImage(dst, img, [kCGImageDestinationLossyCompressionQuality: 0.85] as CFDictionary)
        guard CGImageDestinationFinalize(dst) else { return nil }
        return (out as Data, img)
    }
}

/// Entwuerfe je Konto und Chat in UserDefaults (nie an den Server; beim Abmelden geloescht)
enum ChatDraftStore {
    static func load(account: String, chatID: Int) -> ChatStd.Draft? {
        guard !account.isEmpty, let d = UserDefaults.standard.data(forKey: ChatStd.draftKey(account: account, chatID: chatID)) else { return nil }
        return try? JSONDecoder().decode(ChatStd.Draft.self, from: d)
    }
    static func save(account: String, chatID: Int, text: String, people: [String: String]) {
        guard !account.isEmpty else { return }
        let k = ChatStd.draftKey(account: account, chatID: chatID)
        if let dr = ChatStd.draft(text, people: people, now: Date()), let d = try? JSONEncoder().encode(dr) { UserDefaults.standard.set(d, forKey: k) }
        else { UserDefaults.standard.removeObject(forKey: k) }
    }
    static func has(account: String, chatID: Int) -> Bool {
        !account.isEmpty && UserDefaults.standard.data(forKey: ChatStd.draftKey(account: account, chatID: chatID)) != nil
    }
}
