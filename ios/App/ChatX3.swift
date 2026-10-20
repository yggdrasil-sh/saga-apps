import Foundation

// Chat-Stufe 3 und Chat-Grundlagen (Saga 2.5, Konzept Chat A3/A4/B1/B2): Vertrauens-Uebernahme v1 -> v2, Reaktionen
// (pruefen und oeffnen im Hintergrund, senden entprellt, live), Bearbeiten (pruefen gegen das Original, senden, live).
// Logik: ChatX3Logic.swift, Krypto: ChatCryptoV3.swift, Ansichten: ChatX3Views.swift.

/// Handle -> Benutzername aus Chats v1 (members[].handle, nur fuer Clients der Stufe 3), damit trust:<benutzer> bei gleichem
/// Identitaetsschluessel zu trust2:<handle> wird. Oeffentliche Zuordnung, daher in den Einstellungen (wie ChatTrustStore).
enum ChatH2UStore {
    private static func key(_ me: String) -> String { "hk.chat.h2u." + (AppConfig.serverOrigin ?? "-") + "|" + me }
    static func all(_ me: String) -> [String: String] { UserDefaults.standard.dictionary(forKey: key(me)) as? [String: String] ?? [:] }
    static func user(_ me: String, handle: String) -> String? { all(me)[handle] }
    /// Zuordnungen aus Chats v1 merken (nur andere Konten, nur echte Handles)
    static func note(_ me: String, _ chats: [ChatSummary]) {
        var a = all(me), changed = false
        for c in chats where c.fed == nil {
            for m in c.members where !m.me && m.username != me {
                guard let h = m.handle, h != m.username, a[h] != m.username else { continue }
                a[h] = m.username; changed = true
            }
        }
        if changed { UserDefaults.standard.set(a, forKey: key(me)) }
    }
    /// v1-Vertrauensstand zu einem Handle (falls bekannt)
    static func v1Trust(_ me: String, handle: String) -> FedChatLogic.V1Trust? {
        guard let u = user(me, handle: handle), let k = ChatTrustStore.known(me, u) else { return nil }
        return FedChatLogic.V1Trust(ident: k.pk, verified: k.verified, changed: k.changed)
    }
}

extension ConversationModel {
    /// Reaktionen/Bearbeiten/Sprachnachrichten in diesem Chat (Server meldet x3_ok, Format v2, angenommen, beschreibbar)
    var x3: Bool { (fed?.x3OK ?? false) && !(fed?.request ?? true) && !(fed?.readonly ?? true) }
    var x3Limits: ChatX3.Limits { model.me.x3.limits }
    var myHandle: String { fed?.myHandle ?? "" }

    // MARK: Reaktionen

    func canReact(_ m: ChatMessage) -> Bool {
        x3 && model.me.x3.react && m.content != nil && m.verified && m.delivery == .delivered && m.call == nil && m.sys == nil && msgKeys[m.id] != nil
    }
    func chips(_ m: ChatMessage) -> [ChatX3.Chip] { ChatX3.chips(reactions[m.id] ?? [], me: myHandle) }
    func myReaction(_ m: ChatMessage) -> String? { ChatX3.mine(reactions[m.id] ?? [], me: myHandle) }
    /// Name zu einem Handle ("du", Mitglied, sonst ehemaliges Mitglied)
    func name(ofHandle h: String) -> String {
        if h == myHandle { return String(localized: "du") }
        return fed?.members.first { $0.handle == h }?.name ?? String(localized: "Ehemaliges Mitglied")
    }

    /// Reaktionen der Nachrichten `mids` pruefen und oeffnen (Hintergrund); eigene, noch nicht gesendete bleiben stehen
    func openReactions(_ mids: [String]) {
        guard let f = fed, !mids.isEmpty else { return }
        var jobs: [ChatX3Open.Job] = []
        for mid in mids {
            guard let items = rxRaw[mid], !items.isEmpty, let K = msgKeys[mid] else { continue }
            jobs.append(.init(mid: mid, K: K, items: items))
        }
        guard !jobs.isEmpty else { return }
        var pks: [String: Data] = [:]
        for (h, st) in fk where st.verdict != .reject {
            for d in st.keys?.devices ?? [] where !d.revoked { pks[h + "|" + d.id] = d.sign }
        }
        let ref = f.ref
        Task {
            let res = await Task.detached(priority: .userInitiated) { ChatX3Open.run(jobs, pks: pks, ref: ref) }.value
            var next = reactions
            for (mid, list) in res where rxSend[mid] == nil { next[mid] = list }
            if next != reactions { reactions = next }
        }
    }
    /// Reaktion setzen bzw. zuruecknehmen (gleiches Emoji nochmal): sofort zeigen, nach 500 ms nur die letzte Wahl senden
    func react(_ m: ChatMessage, _ picked: String) {
        guard canReact(m), ChatX3.emojiOK(picked) else { return }
        let e = ChatX3.toggle(current: myReaction(m), picked: picked)
        let me = myHandle, mid = m.id
        reactions[mid] = ChatX3.applyLocal(reactions[mid] ?? [], me: me, e: e, ts: Int64(Date().timeIntervalSince1970 * 1000))
        if !e.isEmpty { EmojiRecent.remember(e) }
        rxSend[mid]?.cancel()
        rxSend[mid] = Task { [weak self] in
            try? await Task.sleep(for: .seconds(ChatX3.reactDebounce))
            guard let self, !Task.isCancelled else { return }
            await self.sendReaction(mid: mid, e: e)
        }
    }
    private func sendReaction(mid: String, e: String) async {
        defer { rxSend[mid] = nil }
        guard let store, let f = fed, let k = model.keys, let K = msgKeys[mid] else { return }
        let ts = Int64(Date().timeIntervalSince1970 * 1000)
        guard let r = ChatCrypto.encryptReaction(K: K, ref: f.ref, msgID: mid, handle: f.myHandle, device: k.deviceID, signSK: k.sign.sk, emoji: e, ts: ts) else { return }
        let ok: Bool
        if let (d, code) = try? await store.apiResponse("api/chats/\(chatID)/messages/\(mid)/reaction", method: "PUT", body: r.body) {
            ok = (200..<300).contains(code)
            if !ok { model.toast = String(localized: "Reaktion nicht gesendet: \(ShareJSON.obj(d).flatMap { ShareJSON.str($0["error"]) } ?? "HTTP \(code)")") }
        } else {
            ok = false; model.toast = String(localized: "Reaktion nicht gesendet: \(String(localized: "Server nicht erreichbar"))")
        }
        if ok { rxRaw[mid] = ChatX3.merge(rxRaw[mid] ?? [], [r]) }
        rxSend[mid] = nil
        openReactions([mid])                                            // Stand des Servers (bzw. vor dem Fehler) zeigen
    }
    /// Live chat.react: gebuendelte Eintraege bzw. nur Hinweis (more) -> nachladen
    func liveReactions(_ d: [String: Any]) {
        if ShareJSON.bool(d["more"]) {
            let known = Set(messages.map(\.id))
            let mids = (d["mids"] as? [Any] ?? []).compactMap { $0 as? String }.filter(known.contains).prefix(100)
            guard !mids.isEmpty, let store else { return }
            Task {
                guard let data = try? await store.api("api/chats/\(chatID)/reactions?mids=" + mids.joined(separator: ",")),
                      let all = ShareJSON.obj(data)?["reactions"] as? [String: Any] else { return }
                for (mid, v) in all { rxRaw[mid] = ChatX3.newest((v as? [Any] ?? []).compactMap(ChatX3.Reaction.init)) }
                openReactions(Array(all.keys))
            }
            return
        }
        var per: [String: [ChatX3.Reaction]] = [:]
        for case let x as [String: Any] in (d["items"] as? [Any] ?? []) {
            guard let mid = x["id"] as? String, let r = ChatX3.Reaction(x) else { continue }
            per[mid, default: []].append(r)
        }
        for (mid, items) in per { rxRaw[mid] = ChatX3.merge(rxRaw[mid] ?? [], items) }
        openReactions(Array(per.keys))
    }

    // MARK: Bearbeiten

    func canEdit(_ m: ChatMessage) -> Bool {
        guard x3, model.me.x3.edit, let c = m.content, m.call == nil, m.sys == nil else { return false }
        let voice = c.files.contains { $0.voice }
        return ChatX3.canEdit(mine: m.mine && !m.legacy && m.senderHandle == myHandle, hasText: !c.text.isEmpty, ai: m.aiMeta != nil || m.aiState != .none,
                              voice: voice, pending: m.delivery != .delivered, created: m.created, editN: m.editN,
                              now: Date().timeIntervalSince1970, limits: x3Limits)
    }
    /// Bearbeitung am Original (GET .../messages: edit) pruefen und anwenden - sonst bleibt das Original
    func applyEdit(_ cm: ChatMessage, raw m: [String: Any]) -> ChatMessage {
        guard let e = m["edit"] as? [String: Any], let f = fed, let o = model.own,
              let x = FedFlow.open(e, ref: f.ref, own: o, keys: fk, key: nil), x.verified, let pt = x.plain,
              let body = (try? JSONSerialization.jsonObject(with: pt)) as? [String: Any] else { return cm }
        let rej = ChatX3.editCheck(origID: cm.id, origSender: ShareJSON.str(m["sender_handle"]), origAI: ShareJSON.str(m["ai"]) != nil,
                                   origCreated: ShareJSON.num(m["created"]) ?? 0, editOf: ShareJSON.str(e["edit_of"]),
                                   editSender: ShareJSON.str(e["sender_handle"]), editCreated: ShareJSON.num(e["created"]) ?? 0,
                                   body: body, window: x3Limits.editWindow)
        guard rej == nil, let t = body["t"] as? String, var c = cm.content else { return cm }
        c.text = t; c.notes = ChatLogic.noteRefs(t)
        var out = cm
        out.content = c
        out.editedAt = ShareJSON.num(m["edited_at"]) ?? ShareJSON.num(e["created"]) ?? Date().timeIntervalSince1970
        return out
    }
    /// Bearbeitung senden (normale v2-Nachricht kind "edit" mit edit_of); true = gesendet bzw. nichts zu tun
    func sendEdit(_ m: ChatMessage, text: String) async -> Bool {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard canEdit(m), !t.isEmpty else { return false }
        if t == m.text { return true }
        sending = true; sendError = nil; defer { sending = false }
        do {
            try await sendV2(ChatX3.editBody(msgID: m.id, text: t), replyTo: nil, fileIDs: [], ai: nil, kind: "edit", extra: ["edit_of": m.id])
            if let i = messages.firstIndex(where: { $0.id == m.id }), var c = messages[i].content {
                c.text = t; c.notes = ChatLogic.noteRefs(t)
                messages[i].content = c; messages[i].editedAt = Date().timeIntervalSince1970; messages[i].editN += 1
            }
            return true
        } catch {
            sendError = String(localized: "Nicht bearbeitet: \(error.localizedDescription)")
            return false
        }
    }
    /// Live chat.edited: Original mit der neuesten Bearbeitung nachladen
    func liveEdited(_ d: [String: Any]) {
        guard let id = d["id"] as? String, let i = messages.firstIndex(where: { $0.id == id }), let store else { return }
        let seq = messages[i].seq
        Task {
            guard let data = try? await store.api("api/chats/\(chatID)/messages?\(model.deviceParam)&after=\(seq - 1)&limit=1&calls=1"),
                  let j = ShareJSON.obj(data) else { return }
            for case let m as [String: Any] in (j["messages"] as? [Any] ?? []) where m["id"] as? String == id {
                guard let k = messages.firstIndex(where: { $0.id == id }) else { return }
                var cm = messages[k]
                cm.editN = ShareJSON.int(m["edit_n"]) ?? cm.editN
                cm = applyEdit(cm, raw: m)
                messages[k] = cm
            }
        }
    }
}

/// Zuletzt benutzte Emojis (nur Bequemlichkeit; fehlt der Speicher, gibt es keine Liste)
enum EmojiRecent {
    private static let key = "hk.chat.emoji.recent"
    static var list: [String] { (UserDefaults.standard.array(forKey: key) as? [String] ?? []).filter(ChatX3.emojiOK).prefix(24).map { $0 } }
    static func remember(_ e: String) {
        var r = list.filter { $0 != e }; r.insert(e, at: 0)
        UserDefaults.standard.set(Array(r.prefix(24)), forKey: key)
    }
}
