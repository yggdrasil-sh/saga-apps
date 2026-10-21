import SwiftUI

// Gelesen-Bestaetigungen und Online-Status (Saga 2.4, "wie WhatsApp"): Haken ⏳ → ✓ → ✓✓ an eigenen Nachrichten,
// "Gelesen von …" in Gruppen, "online"/"zuletzt online" im Chatkopf, Punkt in der Chat-Liste, Einstellungen →
// Datenschutz (zwei Schalter, gegenseitig). Ende-zu-Ende: ReceiptCrypto.swift; Logik/Tests: ReceiptLogic.swift.
// Bestaetigt wird nur, was sichtbar war (Zeile erschienen), solange die App aktiv und der Chat offen ist - gebuendelt
// (hoechstens eine je Chat und 2 s). Online-Abo nur fuer sichtbare Liste bzw. offenen Chat, im Hintergrund abgemeldet.
// Aeltere Server (ohne features "receipts"/"online"): alles ausgeblendet.

// MARK: - Unterhaltung: senden, empfangen, Haken

extension ConversationModel {
    var statusFlags: ReceiptLogic.ChatFlags { info?.statusFlags ?? ReceiptLogic.ChatFlags() }
    /// Bestaetigungen in diesem Chat senden und anzeigen (Server kann es, eigene Einstellung an, Chat erlaubt es)
    var receiptsActive: Bool { info != nil && ReceiptLogic.receiptsActive(model.me.status, statusFlags) }
    /// Position, die alle Teilnehmer gleich sehen: lokal seq, ueber Servergrenzen fseq (Heim)
    func position(_ m: ChatMessage) -> Int { fed != nil ? m.fseq : m.seq }
    /// eigene Nachricht im Sinne der Bestaetigung (bestaetigte KI-Antworten zaehlen als fremd)
    private func ownForReceipt(_ m: ChatMessage) -> Bool { m.mine && m.aiState != .ok }

    /// Zeile ist erschienen (LazyVStack): bis hierher war der Verlauf sichtbar
    func saw(_ m: ChatMessage) {
        let n = position(m)
        guard !ownForReceipt(m), n > seenUpTo else { return }
        seenUpTo = n
        scheduleReceipt(after: 0.3)                                     // mehrere Zeilen auf einmal: eine Bestaetigung
    }
    func scheduleReceipt(after s: Double) {
        rcLater?.cancel()
        rcLater = Task { [weak self] in
            try? await Task.sleep(for: .seconds(s))
            guard !Task.isCancelled else { return }
            await self?.sendReceiptIfDue()
        }
    }

    /// Faellige Bestaetigung senden (mit dem Lese-Zeiger): nur App aktiv + Chat offen, hoechstens eine je 2 s
    func sendReceiptIfDue() async {
        guard let store, receiptsActive, model.appActive, model.open === self, let k = model.keys else { return }
        let pos = messages.map { ReceiptLogic.Pos(id: $0.id, n: position($0), mine: ownForReceipt($0)) }
        guard let want = ReceiptLogic.want(pos, sent: rcSent, seenUpTo: seenUpTo) else { return }
        let now = Date().timeIntervalSince1970
        let wait = ReceiptLogic.wait(lastAt: rcAt, now: now, gap: model.me.status.limits.receiptGap)
        if wait > 0 { scheduleReceipt(after: wait + 0.05); return }    // nachziehen statt verwerfen
        let body = ReceiptLogic.body(id: want.id, n: want.n, ts: Int64(now * 1000))
        let keys = receiptKeys(body: body, k: k)
        if keys.isEmpty {
            if fed == nil && recipients == nil { await loadRecipients(); if recipients != nil { scheduleReceipt(after: 0.1) } }
            return
        }
        rcAt = now
        let seq = max(messages.last?.seq ?? 0, lastReadSent)
        lastReadSent = seq
        guard let (d, code) = try? await store.apiResponse("api/chats/\(chatID)/read", method: "POST", body: ["seq": seq, "receipt": ["keys": keys]]),
              (200..<300).contains(code) else { scheduleReceipt(after: 5); return }
        switch ReceiptLogic.readResult(ShareJSON.obj(d)?["receipt"]) {
        case .sent: rcSent = max(rcSent, want.n)
        case .off: model.me.status.privacy.receipts = false; seen = [:]
        case .retryLater: scheduleReceipt(after: 5)
        case .drop: rcSent = max(rcSent, want.n)                       // falsche Form: nicht endlos wiederholen
        }
    }

    /// Je anderem Mitglied versiegelt fuer dessen Backup-Schluessel (lokal: geprueft wie beim Senden; v2: selbst gepruefte Schluessel)
    private func receiptKeys(body: String, k: ChatKeys) -> [String: String] {
        var out: [String: String] = [:]
        if let f = fed {
            for m in f.members where !m.me && m.handle != f.myHandle {
                guard let st = fk[m.handle], let keys = st.keys, st.verdict != .reject, st.verdict != .block,
                      let s = ChatCrypto.sealReceipt(chat: f.ref, reader: f.myHandle, device: k.deviceID, signSK: k.sign.sk, body: body, v2: true, toPK: keys.backupPK)
                else { continue }
                out[ReceiptLogic.keyID(m.handle)] = s
            }
        } else {
            for r in recipients ?? [] {
                let uid = r["username"] as? String ?? ""
                guard uid != me, !uid.isEmpty, ShareJSON.bool(r["ready"]), let bpk = verifiedBackup(r),
                      let s = ChatCrypto.sealReceipt(chat: String(chatID), reader: me, device: k.deviceID, signSK: k.sign.sk, body: body, v2: false, toPK: bpk)
                else { continue }
                out[ReceiptLogic.keyID(uid)] = s
            }
        }
        return Dictionary(uniqueKeysWithValues: out.sorted { $0.key < $1.key }.prefix(50).map { ($0.key, $0.value) })
    }
    /// Backup-Schluessel eines Empfaengers (lokal), nur mit gueltigem Zertifikat der Identitaet
    private func verifiedBackup(_ r: [String: Any]) -> Data? {
        let uid = r["username"] as? String ?? ""
        guard let ipk = ChatCrypto.unb64(r["ident_pub"] as? String, 32), let bpk = ChatCrypto.unb64(r["backup_pub"] as? String, 32),
              ChatCrypto.verify(ChatCrypto.unb64(r["backup_sig"] as? String), ChatCrypto.backupMsg(uid: uid, backupPK: bpk), pk: ipk) else { return nil }
        return bpk
    }
    /// Signierendes Geraet einer lesenden Person -> Ed25519-Schluessel (nur gepruefte Geraete)
    private func readerKey(from: String, handle: String?, dev: String) -> Data? {
        if fed != nil {
            guard let h = handle, let st = fk[h], st.verdict != .reject, let d = st.keys?.devices.first(where: { $0.id == dev }) else { return nil }
            return d.sign
        }
        guard let r = recipients?.first(where: { $0["username"] as? String == from }),
              let ipk = ChatCrypto.unb64(r["ident_pub"] as? String, 32) else { return nil }
        for case let dv as [String: Any] in (r["devices"] as? [Any] ?? []) where dv["id"] as? String == dev {
            guard let enc = ChatCrypto.unb64(dv["enc_pub"] as? String, 32), let sg = ChatCrypto.unb64(dv["sign_pub"] as? String, 32),
                  ChatCrypto.verify(ChatCrypto.unb64(dv["sig"] as? String), ChatCrypto.deviceMsg(uid: from, deviceID: dev, encPK: enc, signPK: sg), pk: ipk) else { return nil }
            return sg
        }
        return nil
    }

    /// Beim Oeffnen: neueste Bestaetigung je anderem Mitglied
    func loadReceipts() async {
        guard let store, receiptsActive, model.keys != nil else { if !receiptsActive { seen = [:] }; return }
        if fed == nil && recipients == nil { await loadRecipients() }
        guard let d = try? await store.api("api/chats/\(chatID)/receipts"), let j = ShareJSON.obj(d) else { return }
        if j["enabled"].map(ShareJSON.bool) == false { model.me.status.privacy.receipts = false; seen = [:]; return }
        seen = [:]
        take(j["receipts"])
    }
    /// Live chat.receipt (gebuendelt; bei "more" nur ein Hinweis -> abrufen)
    func liveReceipts(_ d: [String: Any]) {
        guard receiptsActive else { return }
        if ShareJSON.bool(d["more"]) { Task { await loadReceipts() } } else { take(d["receipts"]) }
    }
    /// Oeffnen, pruefen (Geraet der Person, Chat und Name gebunden), je Person nur das groesste n
    private func take(_ list: Any?) {
        guard let k = model.keys else { return }
        var next = seen
        for case let x as [String: Any] in (list as? [Any] ?? []) {
            guard let from = x["from"] as? String else { continue }
            let handle = ShareJSON.str(x["handle"])
            guard let r = ChatCrypto.openReceipt(x["sealed"] as? String, pk: k.backupPK, sk: k.backupSKData),
                  let pk = readerKey(from: from, handle: handle, dev: r.dev) else { continue }
            let chat = fed?.ref ?? String(chatID), reader = fed != nil ? (handle ?? "") : from
            guard ChatCrypto.verifyReceipt(chat: chat, reader: reader, r, signPK: pk, v2: fed != nil), let b = ReceiptLogic.parseBody(r.body) else { continue }
            let key = fed != nil ? (handle ?? from) : from
            if let m = ReceiptLogic.merge(next[key], b) { next[key] = m }
        }
        if next != seen { seen = next }
    }
    /// Eigene Einstellung geaendert (anderes Geraet bzw. hier): Haken neu
    func privacyChanged() {
        if receiptsActive { Task { await loadReceipts(); await sendReceiptIfDue() } } else { seen = [:] }
    }

    /// Andere Mitglieder (Schluessel wie in `seen`) mit Namen
    var receiptMembers: [(key: String, name: String)] {
        if let f = fed { return f.members.filter { !$0.me && $0.handle != f.myHandle }.map { ($0.handle, $0.name) } }
        return (info?.members ?? []).filter { !$0.me && $0.username != me }.map { ($0.username, $0.name) }
    }
    func tick(_ m: ChatMessage) -> ReceiptLogic.Tick {
        guard m.call == nil, m.sys == nil, m.aiState != .ok else { return .none }
        return ReceiptLogic.tick(mine: m.mine, pending: m.delivery == .pending, failed: m.delivery == .failed, n: position(m),
                                 others: receiptMembers.map(\.key), seen: seen, active: receiptsActive, shown: model.me.status.receipts)
    }
    func readers(_ m: ChatMessage) -> [ReceiptLogic.Reader] { ReceiptLogic.readers(n: position(m), members: receiptMembers, seen: seen) }
    var isGroup: Bool { info.map { $0.kind != "dm" } ?? false }
}

// MARK: - Chat-Modell: Einstellungen, Live, Online-Abo

extension ChatModel {
    /// Datenschutz-Schalter (PUT /api/chat/privacy) -> neuer Stand; wirft bei Fehler
    func setPrivacy(receipts: Bool? = nil, online: Bool? = nil) async throws {
        guard let store else { return }
        let (d, code) = try await store.apiResponse("api/chat/privacy", method: "PUT", body: ReceiptLogic.privacyBody(receipts: receipts, online: online))
        guard (200..<300).contains(code) else { throw APIError.http(code, ShareJSON.obj(d).flatMap { ShareJSON.str($0["error"]) } ?? "") }
        applyPrivacy(ReceiptLogic.privacy(ShareJSON.obj(d)), force: true)
    }
    private func applyPrivacy(_ p: ReceiptLogic.Privacy, force: Bool = false) {
        guard force || me.status.privacy != p else { return }
        me.status.privacy = p
        if !p.online { presence = [:] }
        open?.privacyChanged()
        Task { await presenceTick() }
    }

    func statusLive(_ type: String, _ d: [String: Any]) {
        switch type {
        case "chat.receipt":
            if let o = open, o.chatID == ShareJSON.int(d["chat_id"]) { o.liveReceipts(d) }
        case "chat.presence":
            guard me.status.onlineOn else { return }
            var p = presence
            if ReceiptLogic.applyLive(d, to: &p) { presence = p }
        case "chat.privacy":
            applyPrivacy(ReceiptLogic.privacy(d))
        default: break
        }
    }

    /// App vorne/hinten (scenePhase): im Hintergrund abmelden, vorne Abo + faellige Bestaetigung
    func setAppActive(_ on: Bool) {
        guard on != appActive else { return }
        appActive = on
        Task { await presenceTick() }
        if on, let o = open { Task { await o.sendReceiptIfDue() } }
    }
    func setListVisible(_ on: Bool) {
        guard on != listVisible else { return }
        listVisible = on
        Task { await presenceTick() }
    }
    /// Takt fuer das Online-Abo (alle 5 s pruefen; Anfragen nur nach Plan: 60 s erneuern, 10 s nachfragen)
    func startPresence() {
        guard presenceLoop == nil else { Task { await presenceTick() }; return }
        presenceLoop = Task { [weak self] in
            while !Task.isCancelled {
                await self?.presenceTick()
                try? await Task.sleep(for: .seconds(5))
            }
        }
    }
    func presenceSoon() { Task { try? await Task.sleep(for: .milliseconds(400)); await presenceTick() } }

    private func peerRef(_ c: ChatSummary) -> ReceiptLogic.PeerRef {
        ReceiptLogic.PeerRef(user: c.kind == "dm" ? (c.peer?.username ?? "") : "", flags: c.statusFlags, archived: c.archived)
    }
    func presenceTick() async {
        guard let store else { return }
        let openRef = open?.info.map(peerRef)
        let want = ReceiptLogic.presenceWant(open: openRef, list: listVisible ? chats.map(peerRef) : nil, caps: me.status)
        let now = Date().timeIntervalSince1970
        switch presenceSub.plan(want: want, active: appActive && state == .ready, now: now) {
        case .none: return
        case .leave(let users):
            if !presence.isEmpty { presence = [:] }
            _ = try? await store.apiResponse("api/chat/presence", method: "POST", body: ["users": users, "leave": true])
        case .subscribe(let users):
            guard let (d, code) = try? await store.apiResponse("api/chat/presence", method: "POST", body: ["users": users]), (200..<300).contains(code) else {
                presenceSub.failed(); return
            }
            let r = ReceiptLogic.presence(ShareJSON.obj(d))
            presenceSub.answered(r.users, now: Date().timeIntervalSince1970)
            if !r.enabled { me.status.privacy.online = false; presence = [:]; return }
            if presence != r.users { presence = r.users }
        }
    }
    /// Online-Status einer Person, wenn der Chat ihn zeigen darf
    func presenceOf(_ c: ChatSummary?) -> ReceiptLogic.Presence? {
        guard let c, c.kind == "dm", ReceiptLogic.onlineActive(me.status, c.statusFlags), let u = c.peer?.username else { return nil }
        return presence[u]
    }
}

// MARK: - Ansichten

/// Haken unter der eigenen Blase: ✓ zugestellt, ✓✓ gelesen (alle: farbig), Gruppen mit Zaehler; Tippen = "Gelesen von"
struct ReceiptTickView: View {
    let tick: ReceiptLogic.Tick
    var group = false
    var open: () -> Void = {}
    var body: some View {
        switch tick {
        case .none, .pending: EmptyView()                               // ⏳: FedDeliveryMark
        case .delivered:
            mark("✓", color: .secondary, label: String(localized: "Zugestellt"))
        case .read(let c, let all):
            let label = all ? (group ? String(localized: "Von allen gelesen") : String(localized: "Gelesen"))
                            : String(localized: "Gelesen von") + " \(c)"
            HStack(spacing: 1) {
                mark("✓✓", color: all ? Self.readColor : .secondary, label: label)
                if group && !all { Text(verbatim: "\(c)").font(.caption2.monospacedDigit()).foregroundStyle(.secondary).accessibilityHidden(true) }
            }
        }
    }
    @ViewBuilder private func mark(_ s: String, color: Color, label: String) -> some View {
        let t = Text(verbatim: s).font(.caption2.weight(.bold)).foregroundStyle(color).kerning(-2.5)
        if group {
            Button(action: open) { t.padding(12).contentShape(Rectangle()).padding(-12) }.buttonStyle(.plain)   // 2.5: groessere Trefferflaeche.help(Text(verbatim: label)).accessibilityLabel(Text(verbatim: label))
                .accessibilityHint(Text("Gelesen von"))
        } else {
            t.help(Text(verbatim: label)).accessibilityLabel(Text(verbatim: label))
        }
    }
    /// gelesen: Blau wie gewohnt (Kontrast >= 4,5:1 auf hellem und dunklem Grund)
    static let readColor = Color.adaptive(0x1A6FD1, 0x5AB0FF)
}

/// "Gelesen von": Namen + Zeit, Hinweis auf abgeschaltete Bestaetigungen
struct ReadBySheet: View {
    @ObservedObject var conv: ConversationModel
    let message: ChatMessage
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        NavigationStack {
            List {
                let rd = conv.readers(message)
                Section {
                    if rd.isEmpty { Text("Noch von niemandem gelesen.").foregroundStyle(.secondary) }
                    ForEach(rd, id: \.key) { r in
                        HStack {
                            Text(verbatim: r.name)
                            Spacer()
                            if r.ts > 0 {
                                Text(Date(timeIntervalSince1970: r.ts / 1000).sagaFormatted(date: .abbreviated, time: .shortened)).font(.caption).foregroundStyle(.secondary)
                            }
                        }.accessibilityElement(children: .combine)
                    }
                } footer: { Text("Wer Gelesen-Bestätigungen abgeschaltet hat, erscheint hier nicht.") }
            }
            .navigationTitle("Gelesen von")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Fertig") { dismiss() } } }
        }
        #if os(macOS)
        .frame(minWidth: 340, minHeight: 280)
        #endif
    }
}

/// "online" bzw. "zuletzt online vor 5 Minuten" (relativ)
enum PresenceText {
    static func text(_ p: ReceiptLogic.Presence?, now: Date = Date()) -> String? {
        switch ReceiptLogic.shown(p, now: now) {
        case .online: return String(localized: "online")
        case .last(let d):
            let f = RelativeDateTimeFormatter(); f.unitsStyle = .full
            return String(localized: "zuletzt online \(f.localizedString(for: d, relativeTo: now))")
        case .none: return nil
        }
    }
}

/// Kopf eines Direkt-Chats (iPhone/iPad): Name, darunter online/zuletzt online
struct ChatTitleWithPresence: View {
    let title: String
    let status: String?
    let online: Bool
    var body: some View {
        VStack(spacing: 0) {
            Text(verbatim: title).font(.headline).lineLimit(1)
            if let status {
                Text(verbatim: status).font(.caption2).foregroundStyle(online ? Color.adaptive(0x1E7B34, 0x4CD964) : .secondary).lineLimit(1)
            }
        }
        .accessibilityElement(children: .combine)
    }
}

/// Gruener Punkt am Bild (Chat-Liste)
struct OnlineDot: View {
    var body: some View {
        Circle().fill(Color.adaptive(0x2DA44E, 0x3FD06A)).frame(width: 11, height: 11)
            .overlay(Circle().strokeBorder(Color.adaptive(0xFFFFFF, 0x1C1C1E), lineWidth: 2))
            .accessibilityLabel(Text("online"))
    }
}

/// Einstellungen → Datenschutz: zwei Schalter, gegenseitig (nur Server ab 2.4)
struct ChatPrivacySection: View {
    @ObservedObject var chat: ChatModel
    @State private var busy = false
    @State private var err: String?
    var body: some View {
        if chat.state == .ready && chat.me.status.any {
            Section {
                if chat.me.status.receipts {
                    Toggle(isOn: binding(\.receipts) { try await chat.setPrivacy(receipts: $0) }) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Gelesen-Bestätigungen senden")
                            Text("Aus: Andere sehen nicht, ob du ihre Nachrichten gelesen hast – und du siehst es bei ihnen auch nicht.")
                                .font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                        }
                    }
                }
                if chat.me.status.online {
                    Toggle(isOn: binding(\.online) { try await chat.setPrivacy(online: $0) }) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Online-Status zeigen")
                            Text("Aus: Deine Chat-Partner sehen nicht, ob und wann du zuletzt online warst – und du siehst es bei ihnen auch nicht.")
                                .font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                        }
                    }
                }
                if let err { Text(verbatim: err).font(.footnote).foregroundStyle(.red) }
            } header: { Text("Datenschutz") } footer: {
                Text("Gilt gegenseitig: Wer etwas abschaltet, sieht es bei anderen auch nicht.")
            }
            .disabled(busy)
        }
    }
    private func binding(_ kp: WritableKeyPath<ReceiptLogic.Privacy, Bool>, _ set: @escaping (Bool) async throws -> Void) -> Binding<Bool> {
        Binding(get: { chat.me.status.privacy[keyPath: kp] }, set: { v in
            let old = chat.me.status.privacy
            chat.me.status.privacy[keyPath: kp] = v                    // sofort zeigen, bei Fehler zuruecknehmen
            busy = true; err = nil
            Task {
                do { try await set(v) } catch { chat.me.status.privacy = old; err = String(localized: "Nicht gespeichert: \(error.localizedDescription)") }
                busy = false
            }
        })
    }
}
