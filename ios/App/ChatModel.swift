import SwiftUI

// Chat mit Ende-zu-Ende-Verschluesselung (Saga 2.0): Zustand, Schluessel, Einrichtung, Kopplung neuer Geraete,
// Chat-Liste. Private Schluessel nur im Schluesselbund dieses Geraets (ThisDeviceOnly), nie an den Server, nie im
// Klartext auf die Platte. Krypto: ChatCrypto.swift (libsodium), Logik: ChatLogic.swift, Ansichten: ChatViews.swift.

/// Private Schluessel dieses Geraets (+ Identitaet und Backup-Schluessel des Kontos)
struct ChatKeys: Codable, Equatable {
    var uid: String
    var deviceID: String
    var encPK: String; var encSK: String
    var signPK: String; var signSK: String
    var identSK: String; var backupSK: String
    var enc: ChatCrypto.KeyPair { .init(pk: ChatCrypto.unb64(encPK) ?? Data(), sk: ChatCrypto.unb64(encSK) ?? Data()) }
    var sign: ChatCrypto.KeyPair { .init(pk: ChatCrypto.unb64(signPK) ?? Data(), sk: ChatCrypto.unb64(signSK) ?? Data()) }
    var identSKData: Data { ChatCrypto.unb64(identSK) ?? Data() }
    var identPK: Data { identSKData.suffix(32) }
    var backupSKData: Data { ChatCrypto.unb64(backupSK) ?? Data() }
    var backupPK: Data { ChatCrypto.scalarmultBase(backupSKData) ?? Data() }
    var bundlePlain: String { ChatCrypto.bundlePlain(identSK: identSKData, backupSK: backupSKData) }
}

/// Schluesselbund: je Server + Konto
enum ChatKeyStore {
    private static var service: String { (Bundle.main.bundleIdentifier ?? "saga") + ".chat-keys" }
    private static func account(_ uid: String) -> String { (AppConfig.serverOrigin ?? "-") + "|" + uid }
    static func load(uid: String) -> ChatKeys? {
        guard let s = Keychain.read(service: service, account: account(uid)), let k = try? JSONDecoder().decode(ChatKeys.self, from: Data(s.utf8)) else { return nil }
        return k.uid == uid ? k : nil
    }
    @discardableResult static func save(_ k: ChatKeys) -> Bool {
        guard let d = try? JSONEncoder().encode(k), let s = String(data: d, encoding: .utf8) else { return false }
        return Keychain.write(s, service: service, account: account(k.uid)) != .failed
    }
    static func delete(uid: String) { Keychain.delete(service: service, account: account(uid)) }
    /// Schluesselbund benutzbar? (vor der Einrichtung pruefen - sonst haette der Server eine Identitaet ohne Schluessel hier)
    static func probe() -> Bool {
        let acc = account("probe-" + ChatCrypto.hexID())
        defer { Keychain.delete(service: service, account: acc) }
        return Keychain.write("x", service: service, account: acc) != .failed && Keychain.read(service: service, account: acc) == "x"
    }
}

/// Gemerkte Identitaetsschluessel der Gegenueber (TOFU) + "ueberprueft"; oeffentlich, daher in den Einstellungen
enum ChatTrustStore {
    private static func key(_ me: String) -> String { "hk.chat.trust." + (AppConfig.serverOrigin ?? "-") + "|" + me }
    static func all(_ me: String) -> [String: [String: Any]] { UserDefaults.standard.dictionary(forKey: key(me)) as? [String: [String: Any]] ?? [:] }
    static func known(_ me: String, _ peer: String) -> (pk: String, verified: Bool, changed: Bool)? {
        guard let e = all(me)[peer], let pk = e["pk"] as? String else { return nil }
        return (pk, e["verified"] as? Bool ?? false, e["changed"] as? Bool ?? false)
    }
    static func set(_ me: String, _ peer: String, pk: String, verified: Bool, changed: Bool) {
        var a = all(me); a[peer] = ["pk": pk, "verified": verified, "changed": changed]
        UserDefaults.standard.set(a, forKey: key(me))
    }
    /// Neuer/gleicher/geaenderter Schluessel -> merken; geaendert = Warnung bis "Verstanden"
    @discardableResult static func observe(_ me: String, _ peer: String, pk: String) -> ChatLogic.Trust {
        let k = known(me, peer)
        let t = ChatLogic.trust(known: k?.pk, current: pk)
        switch t {
        case .new: set(me, peer, pk: pk, verified: false, changed: false)
        case .changed: set(me, peer, pk: pk, verified: false, changed: true)
        case .same: break
        }
        return t
    }
    static func acknowledge(_ me: String, _ peer: String) {
        if let k = known(me, peer) { set(me, peer, pk: k.pk, verified: k.verified, changed: false) }
    }
    static func verify(_ me: String, _ peer: String) {
        if let k = known(me, peer) { set(me, peer, pk: k.pk, verified: true, changed: false) }
    }
}

struct ChatDeviceInfo: Identifiable, Equatable {
    var id: String; var name: String; var created: Double?; var lastSeen: Double?; var revoked: Bool; var current: Bool
    var encPub: String; var signPub: String; var sig: String
    init?(_ v: Any?) {
        guard let d = v as? [String: Any], let i = d["id"] as? String else { return nil }
        id = i; name = d["name"] as? String ?? ""; created = ShareJSON.num(d["created"]); lastSeen = ShareJSON.num(d["last_seen"])
        revoked = ShareJSON.bool(d["revoked"]); current = ShareJSON.bool(d["current"])
        encPub = d["enc_pub"] as? String ?? ""; signPub = d["sign_pub"] as? String ?? ""; sig = d["sig"] as? String ?? ""
    }
}

struct ChatMe: Equatable {
    var enabled = true
    var uid = ""
    var name = ""
    var identPub: String?
    var backupPub: String?
    var bundle: String?
    var devices: [ChatDeviceInfo] = []
    var links = 0
    var reauth: String?
    var msgMax = 65_536
    var fileMax = 20 * 1_048_576
    var filesPerMsg = 10
    var membersMax = 50
    var retentionMax: Int?
    var retentions: [Int] = [0, 3600, 86_400, 604_800, 2_592_000, 7_776_000, 31_536_000]
    init() {}
    init?(data: Data) {
        guard let j = ShareJSON.obj(data) else { return nil }
        enabled = j["enabled"].map(ShareJSON.bool) ?? true
        uid = j["uid"] as? String ?? ""; name = j["name"] as? String ?? uid
        if let i = j["identity"] as? [String: Any] {
            identPub = i["ident_pub"] as? String; backupPub = i["backup_pub"] as? String; bundle = i["bundle"] as? String
        }
        devices = (j["devices"] as? [Any] ?? []).compactMap(ChatDeviceInfo.init)
        links = ShareJSON.int(j["links"]) ?? 0
        reauth = ShareJSON.str(j["reauth"])
        if let l = j["limits"] as? [String: Any] {
            msgMax = ShareJSON.int(l["msg_max"]) ?? msgMax; fileMax = ShareJSON.int(l["file_max"]) ?? fileMax
            filesPerMsg = ShareJSON.int(l["files_per_msg"]) ?? filesPerMsg; membersMax = ShareJSON.int(l["members_max"]) ?? membersMax
            retentionMax = ShareJSON.int(l["retention_max"]).flatMap { $0 > 0 ? $0 : nil }
            if let r = l["retentions"] as? [Any] { retentions = r.compactMap(ShareJSON.int) }
        }
    }
    var hasIdentity: Bool { identPub != nil }
}

struct ChatMember: Equatable, Identifiable { var username: String; var name: String; var me: Bool; var id: String { username } }

struct ChatSummary: Identifiable, Equatable {
    var id: Int; var kind: String; var name: String?; var group: String?; var noteID: Int?
    var members: [ChatMember]; var memberCount: Int; var unread: Int; var muted: Bool; var retention: Int; var role: String
    var lastAt: Double; var lastSeq: Int; var peer: ChatMember?
    var titleMsg: [String: Any]?
    /// entschluesselter Name einer freien Gruppe
    var decryptedTitle: String?
    static func == (a: ChatSummary, b: ChatSummary) -> Bool {
        a.id == b.id && a.kind == b.kind && a.name == b.name && a.members == b.members && a.unread == b.unread && a.muted == b.muted
            && a.retention == b.retention && a.lastAt == b.lastAt && a.lastSeq == b.lastSeq && a.decryptedTitle == b.decryptedTitle && a.role == b.role
    }
    init?(_ v: Any?) {
        guard let d = v as? [String: Any], let i = ShareJSON.int(d["id"]) else { return nil }
        id = i; kind = d["kind"] as? String ?? "dm"; name = ShareJSON.str(d["name"]); group = ShareJSON.str(d["group"]); noteID = ShareJSON.int(d["note_id"])
        members = (d["members"] as? [Any] ?? []).compactMap { v in
            guard let m = v as? [String: Any], let u = m["username"] as? String else { return nil }
            return ChatMember(username: u, name: m["name"] as? String ?? u, me: ShareJSON.bool(m["me"]))
        }
        memberCount = ShareJSON.int(d["member_count"]) ?? members.count
        unread = ShareJSON.int(d["unread"]) ?? 0; muted = ShareJSON.bool(d["muted"]); retention = ShareJSON.int(d["retention_s"]) ?? 0
        role = d["role"] as? String ?? "member"; lastAt = ShareJSON.num(d["last_at"]) ?? 0; lastSeq = ShareJSON.int(d["last_seq"]) ?? 0
        if let p = d["peer"] as? [String: Any], let u = p["username"] as? String { peer = ChatMember(username: u, name: p["name"] as? String ?? u, me: false) }
        titleMsg = d["title_msg"] as? [String: Any]
    }
    var title: String {
        switch kind {
        case "free": return decryptedTitle ?? members.filter { !$0.me }.map(\.name).joined(separator: ", ")
        default: return name ?? String(localized: "Chat")
        }
    }
    var kindLabel: String {
        switch kind {
        case "dm": return String(localized: "Direkt-Chat")
        case "group": return String(localized: "Gruppen-Chat")
        case "page": return String(localized: "Seiten-Chat")
        default: return String(localized: "Freie Gruppe")
        }
    }
}

/// Kopplungsanfrage eines neuen Geraets (auf einem vorhandenen Geraet bestaetigen)
struct ChatLinkRequest: Identifiable, Equatable {
    var id: String; var deviceID: String; var name: String; var encPub: String; var signPub: String; var expires: Double
    init?(_ v: Any?) {
        guard let d = v as? [String: Any], let i = d["id"] as? String, let did = d["device_id"] as? String else { return nil }
        id = i; deviceID = did; name = d["name"] as? String ?? ""; encPub = d["enc_pub"] as? String ?? ""; signPub = d["sign_pub"] as? String ?? ""
        expires = ShareJSON.num(d["expires"]) ?? 0
    }
}

@MainActor
final class ChatModel: ObservableObject {
    enum State: Equatable { case loading, unsupported, off(String), setup, newDevice, ready, failed(String) }
    weak var store: Store?
    @Published var state: State = .loading
    @Published var me = ChatMe()
    @Published var chats: [ChatSummary] = []
    @Published var unread = 0
    @Published var listError: String?
    /// Kopplungsanfrage, die hier bestaetigt werden soll (Dialog)
    @Published var approval: ChatLinkRequest?
    /// Hinweis nach Aktionen ("Geraet bestaetigt.")
    @Published var toast: String?
    /// Kopplung dieses (neuen) Geraets laeuft: Code + Ablauf
    @Published var linking: (id: String, code: String, expires: Date)?
    @Published var linkError: String?
    /// geoeffneter Chat (fuer Live-Ereignisse)
    weak var open: ConversationModel?
    private(set) var keys: ChatKeys?
    private var pendingDevice: (id: String, enc: ChatCrypto.KeyPair, sign: ChatCrypto.KeyPair)?
    private var linkPoll: Task<Void, Never>?
    private var handledLinks = Set<String>()

    var deviceID: String? { keys?.deviceID }
    var deviceParam: String { keys.map { "device=" + $0.deviceID } ?? "device=" }

    func signedOut() {
        keys = nil; me = ChatMe(); chats = []; unread = 0; state = .loading; approval = nil; linking = nil
        linkPoll?.cancel(); pendingDevice = nil; handledLinks = []
    }

    // MARK: Laden

    /// Eigener Stand -> Zustand (Einrichtung, neues Geraet, bereit); bereit -> Liste
    func load() async {
        guard let store else { return }
        let did = ChatKeyStore.load(uid: me.uid.isEmpty ? "-" : me.uid)?.deviceID ?? keys?.deviceID ?? ""
        do {
            let (d, code) = try await store.apiResponse("api/chat/me?device=\(did)")
            if code == 404 { state = .unsupported; return }
            if code == 403 { state = .off(ShareJSON.obj(d).flatMap { ShareJSON.str($0["error"]) } ?? String(localized: "Chats sind auf diesem Server abgeschaltet.")); return }
            guard code == 200, let m = ChatMe(data: d) else { throw APIError.http(code, ShareJSON.obj(d).flatMap { ShareJSON.str($0["error"]) } ?? "") }
            me = m
            guard m.enabled else { state = .off(String(localized: "Chats sind auf diesem Server abgeschaltet.")); return }
            guard m.hasIdentity else { keys = nil; state = .setup; return }
            var k = ChatKeyStore.load(uid: m.uid)
            if let kk = k {
                let dev = m.devices.first { $0.id == kk.deviceID }
                if ChatCrypto.b64(kk.identPK) != m.identPub || dev == nil || dev?.revoked == true {
                    ChatKeyStore.delete(uid: m.uid); k = nil                      // Neu begonnen/entfernt: alte Schluessel weg
                }
            }
            keys = k
            guard let k else { state = .newDevice; return }
            if did.isEmpty { _ = try? await store.apiResponse("api/chat/me?device=\(k.deviceID)") }   // "zuletzt gesehen"
            state = .ready
            await loadList()
            if m.links > 0 { await checkLinks() }
        } catch { if state == .loading { state = .failed(error.localizedDescription) } }
    }

    func loadList() async {
        guard let store, state == .ready else { return }
        do {
            let d = try await store.api("api/chats?\(deviceParam)")
            guard let j = ShareJSON.obj(d) else { return }
            var list = (j["chats"] as? [Any] ?? []).compactMap(ChatSummary.init)
            for i in list.indices where list[i].kind == "free" { list[i].decryptedTitle = decryptTitle(list[i]) }
            if list != chats { chats = list }
            unread = ShareJSON.int(j["unread"]) ?? unread
            listError = nil
        } catch { listError = error.localizedDescription }
    }
    /// Zaehler fuer die Seitenleiste (auch ohne eingerichtete Chats)
    func refreshUnread() async {
        guard let store, let (d, code) = try? await store.apiResponse("api/chats/unread"), code == 200, let j = ShareJSON.obj(d) else { return }
        if ShareJSON.bool(j["enabled"]) == false && state == .loading { state = .off(String(localized: "Chats sind auf diesem Server abgeschaltet.")) }
        let n = ShareJSON.int(j["unread"]) ?? 0
        if n != unread { unread = n }
    }

    // MARK: Einrichtung

    /// Erstes Geraet: Identitaet, Backup-Schluessel, Geraet, Buendel mit dem Wiederherstellungsschluessel
    func setup(recoveryKey rk: String, reset: Bool = false, password: String? = nil) async throws {
        guard let store else { return }
        guard ChatKeyStore.probe() else { throw APIError.other(String(localized: "Schlüssel konnten nicht im Schlüsselbund gespeichert werden.")) }
        let uid = me.uid
        let ident = ChatCrypto.signKeypair(), backup = ChatCrypto.boxKeypair()
        let enc = ChatCrypto.boxKeypair(), sign = ChatCrypto.signKeypair(), did = ChatCrypto.hexID()
        let plain = ChatCrypto.bundlePlain(identSK: ident.sk, backupSK: backup.sk)
        guard let bundle = await Task.detached(operation: { ChatCrypto.sealBundle(uid: uid, recoveryKey: rk, plain: plain) }).value   // Argon2id im Hintergrund
        else { throw APIError.other(String(localized: "Schlüssel konnten nicht erzeugt werden.")) }
        let cert = ChatCrypto.sign(ChatCrypto.deviceMsg(uid: uid, deviceID: did, encPK: enc.pk, signPK: sign.pk), sk: ident.sk)
        var body: [String: Any] = [
            "ident_pub": ChatCrypto.b64(ident.pk), "backup_pub": ChatCrypto.b64(backup.pk),
            "backup_sig": ChatCrypto.b64(ChatCrypto.sign(ChatCrypto.backupMsg(uid: uid, backupPK: backup.pk), sk: ident.sk)),
            "bundle": bundle,
            "device": ["id": did, "name": DeviceKey.deviceName, "enc_pub": ChatCrypto.b64(enc.pk), "sign_pub": ChatCrypto.b64(sign.pk), "sig": ChatCrypto.b64(cert)],
        ]
        if reset { body["reset"] = true }
        if let password { body["password"] = password }
        let (d, code) = try await store.apiResponse("api/chat/identity", method: "PUT", body: body)
        guard (200..<300).contains(code) else {
            let j = ShareJSON.obj(d)
            if code == 403, let r = ShareJSON.str(j?["reauth"]) { throw ChatReauth(kind: r, message: ShareJSON.str(j?["error"]) ?? "") }
            throw APIError.http(code, ShareJSON.str(j?["error"]) ?? "")
        }
        let k = ChatKeys(uid: uid, deviceID: did, encPK: ChatCrypto.b64(enc.pk), encSK: ChatCrypto.b64(enc.sk), signPK: ChatCrypto.b64(sign.pk),
                         signSK: ChatCrypto.b64(sign.sk), identSK: ChatCrypto.b64(ident.sk), backupSK: ChatCrypto.b64(backup.sk))
        guard ChatKeyStore.save(k) else { throw APIError.other(String(localized: "Schlüssel konnten nicht im Schlüsselbund gespeichert werden.")) }
        keys = k
        await load()
    }

    /// Neues Geraet mit dem Wiederherstellungsschluessel: Buendel oeffnen, eigenes Geraet anmelden (selbst signiert)
    func restore(recoveryKey rk: String) async throws {
        guard let store, let bundle = me.bundle else { return }
        let uid = me.uid
        let b = await Task.detached { ChatCrypto.openBundle(uid: uid, recoveryKey: rk, bundle: bundle) }.value    // Argon2id: nicht auf dem Hauptthread
        guard let b, ChatCrypto.b64(b.identPK) == me.identPub else { throw APIError.other(String(localized: "Dieser Schlüssel passt nicht.")) }
        let enc = ChatCrypto.boxKeypair(), sign = ChatCrypto.signKeypair(), did = ChatCrypto.hexID()
        let cert = ChatCrypto.sign(ChatCrypto.deviceMsg(uid: uid, deviceID: did, encPK: enc.pk, signPK: sign.pk), sk: b.identSK)
        let body: [String: Any] = ["id": did, "name": DeviceKey.deviceName, "enc_pub": ChatCrypto.b64(enc.pk), "sign_pub": ChatCrypto.b64(sign.pk), "sig": ChatCrypto.b64(cert)]
        _ = try await store.api("api/chat/devices", method: "POST", body: body)
        let k = ChatKeys(uid: uid, deviceID: did, encPK: ChatCrypto.b64(enc.pk), encSK: ChatCrypto.b64(enc.sk), signPK: ChatCrypto.b64(sign.pk),
                         signSK: ChatCrypto.b64(sign.sk), identSK: ChatCrypto.b64(b.identSK), backupSK: ChatCrypto.b64(b.backupSK))
        guard ChatKeyStore.save(k) else { throw APIError.other(String(localized: "Schlüssel konnten nicht im Schlüsselbund gespeichert werden.")) }
        keys = k
        await load()
    }

    // MARK: Kopplung (neues Geraet)

    /// "Mit anderem Geraet bestaetigen": Anfrage stellen, Code zeigen, alle 3 s abfragen
    func startLink() async {
        guard let store else { return }
        linkError = nil
        let enc = ChatCrypto.boxKeypair(), sign = ChatCrypto.signKeypair(), did = ChatCrypto.hexID()
        pendingDevice = (did, enc, sign)
        do {
            let d = try await store.api("api/chat/link", method: "POST", body: ["id": did, "name": DeviceKey.deviceName, "enc_pub": ChatCrypto.b64(enc.pk), "sign_pub": ChatCrypto.b64(sign.pk)])
            guard let j = ShareJSON.obj(d), let lid = j["id"] as? String else { throw APIError.other(String(localized: "Keine Antwort vom Server")) }
            let code = ChatCrypto.linkCode(uid: me.uid, deviceID: did, encPK: enc.pk, signPK: sign.pk)
            linking = (lid, code, Date(timeIntervalSince1970: ShareJSON.num(j["expires"]) ?? Date().timeIntervalSince1970 + 600))
            linkPoll?.cancel()
            linkPoll = Task { [weak self] in
                while !Task.isCancelled {
                    try? await Task.sleep(for: .seconds(3))
                    guard let self, !Task.isCancelled else { return }
                    if await self.pollLink() { return }
                }
            }
        } catch { linkError = error.localizedDescription; pendingDevice = nil }
    }
    func cancelLink() {
        linkPoll?.cancel()
        if let l = linking { Task { _ = try? await store?.api("api/chat/link/\(l.id)", method: "DELETE") } }
        linking = nil; pendingDevice = nil
    }
    /// -> fertig (bestaetigt, abgelaufen, abgelehnt)
    @discardableResult func pollLink() async -> Bool {
        guard let store, let l = linking, let p = pendingDevice else { return true }
        guard let d = try? await store.api("api/chat/link/\(l.id)"), let j = ShareJSON.obj(d) else { return false }
        switch j["state"] as? String {
        case "approved":
            linkPoll?.cancel(); linking = nil
            guard let sealed = ChatCrypto.unb64(j["sealed"] as? String), let pt = ChatCrypto.sealOpen(sealed, pk: p.enc.pk, sk: p.enc.sk),
                  let s = String(data: pt, encoding: .utf8), let b = ChatCrypto.parsePlain(s), ChatCrypto.b64(b.identPK) == me.identPub else {
                linkError = String(localized: "Die übertragenen Schlüssel passen nicht zu deinem Konto."); pendingDevice = nil; return true
            }
            let k = ChatKeys(uid: me.uid, deviceID: p.id, encPK: ChatCrypto.b64(p.enc.pk), encSK: ChatCrypto.b64(p.enc.sk), signPK: ChatCrypto.b64(p.sign.pk),
                             signSK: ChatCrypto.b64(p.sign.sk), identSK: ChatCrypto.b64(b.identSK), backupSK: ChatCrypto.b64(b.backupSK))
            pendingDevice = nil
            guard ChatKeyStore.save(k) else { linkError = String(localized: "Schlüssel konnten nicht im Schlüsselbund gespeichert werden."); return true }
            keys = k; toast = String(localized: "Dieses Gerät ist jetzt für Chats eingerichtet.")
            await load()
            return true
        case "pending": return false
        default:
            linkPoll?.cancel(); linking = nil; pendingDevice = nil
            linkError = String(localized: "Die Anfrage ist abgelaufen oder wurde abgelehnt.")
            return true
        }
    }

    // MARK: Kopplung (vorhandenes Geraet bestaetigt)

    func checkLinks() async {
        guard let store, keys != nil, approval == nil else { return }
        guard let d = try? await store.api("api/chat/link"), let j = ShareJSON.obj(d) else { return }
        let list = (j["links"] as? [Any] ?? []).compactMap(ChatLinkRequest.init).filter { !handledLinks.contains($0.id) && $0.deviceID != keys?.deviceID }
        if let first = list.first {
            approval = first
            #if DEBUG
            if UserDefaults.standard.bool(forKey: "hkChatApprove") { Task { try? await Task.sleep(for: .seconds(6)); if approval == first { await approve(first) } } }   // nur fuer Tests
            #endif
        }
    }
    func code(for r: ChatLinkRequest) -> String {
        ChatCrypto.linkCode(uid: me.uid, deviceID: r.deviceID, encPK: ChatCrypto.unb64(r.encPub) ?? Data(), signPK: ChatCrypto.unb64(r.signPub) ?? Data())
    }
    /// "Code stimmt – bestaetigen": Zertifikat fuer das neue Geraet + Buendel versiegelt an dessen enc_pk
    func approve(_ r: ChatLinkRequest) async {
        guard let store, let k = keys, let enc = ChatCrypto.unb64(r.encPub, 32), let sign = ChatCrypto.unb64(r.signPub, 32) else { return }
        handledLinks.insert(r.id); approval = nil
        let sig = ChatCrypto.sign(ChatCrypto.deviceMsg(uid: me.uid, deviceID: r.deviceID, encPK: enc, signPK: sign), sk: k.identSKData)
        guard let sealed = ChatCrypto.seal(Data(k.bundlePlain.utf8), pk: enc) else { return }
        do {
            _ = try await store.api("api/chat/link/\(r.id)", method: "POST", body: ["sig": ChatCrypto.b64(sig), "sealed": ChatCrypto.b64(sealed)])
            toast = String(localized: "Gerät bestätigt.")
            await load()
        } catch { toast = error.localizedDescription }
    }
    func decline(_ r: ChatLinkRequest) async {
        handledLinks.insert(r.id); approval = nil
        _ = try? await store?.api("api/chat/link/\(r.id)", method: "DELETE")
    }

    // MARK: Geraete, Wiederherstellungsschluessel, Neu beginnen

    func removeDevice(_ id: String) async throws {
        guard let store else { return }
        _ = try await store.api("api/chat/devices/\(id)", method: "DELETE")
        if id == keys?.deviceID { ChatKeyStore.delete(uid: me.uid); keys = nil }
        await load()
    }
    /// Neuen Wiederherstellungsschluessel setzen (Buendel neu verschluesselt, signiert)
    func applyRecoveryKey(_ rk: String) async throws {
        guard let store, let k = keys else { return }
        let uid = me.uid, plain = k.bundlePlain
        guard let bundle = await Task.detached(operation: { ChatCrypto.sealBundle(uid: uid, recoveryKey: rk, plain: plain) }).value else {
            throw APIError.other(String(localized: "Schlüssel konnten nicht erzeugt werden."))
        }
        let sig = ChatCrypto.sign(ChatCrypto.bundleUpdateMsg(uid: uid, bundle: bundle), sk: k.identSKData)
        _ = try await store.api("api/chat/bundle", method: "PUT", body: ["bundle": bundle, "sig": ChatCrypto.b64(sig)])
        await load()
    }

    // MARK: Live

    func live(_ type: String, _ d: [String: Any]) {
        let cid = ShareJSON.int(d["chat_id"])
        switch type {
        case "chat.message", "chat.deleted", "chat.read", "chat.changed":
            if let o = open, o.chatID == cid { o.live(type, d) }
            Task { await loadList(); if keys == nil { await refreshUnread() } }
        case "chat.typing":
            if let o = open, o.chatID == cid { o.live(type, d) }
        case "chat.link":
            let id = d["id"] as? String
            if let l = linking, l.id == id { Task { await pollLink() }; return }
            if ShareJSON.bool(d["gone"]) || ShareJSON.bool(d["approved"]) { if approval?.id == id { approval = nil } }
            else if keys != nil { Task { await checkLinks() } }
        case "chat.device":
            if let id = d["id"] as? String, id == keys?.deviceID, ShareJSON.bool(d["revoked"]) {
                ChatKeyStore.delete(uid: me.uid); keys = nil; state = .newDevice; chats = []
            }
        default: break
        }
    }

    // MARK: Freie Gruppe: verschluesselter Name

    func decryptTitle(_ c: ChatSummary) -> String? {
        guard let m = c.titleMsg, let content = decrypt(chatID: c.id, raw: m, devices: nil, idents: nil)?.content, content.type == "title" else { return nil }
        return content.title
    }

    /// Nachricht oeffnen + Echtheit pruefen (Geraet gehoert dem Absender, Zertifikat gegen dessen Identitaet, Signatur)
    func decrypt(chatID: Int, raw m: [String: Any], devices: [String: Any]?, idents: [String: Any]?) -> (content: ChatContent?, verified: Bool)? {
        guard let k = keys, let mid = m["id"] as? String, let body = m["body_ct"] as? String, let key = m["key"] as? String else { return nil }
        let sender = ((m["sender"] as? [String: Any])?["username"] as? String) ?? ""
        let dev = m["sender_device"] as? String ?? ""
        let ts = (m["ts"] as? NSNumber)?.int64Value ?? 0
        let reply = ShareJSON.str(m["reply_to"])
        let pair: ChatCrypto.KeyPair = (m["key_for"] as? String) == "backup" ? .init(pk: k.backupPK, sk: k.backupSKData) : k.enc
        let pt = ChatCrypto.decryptMessage(chatID: String(chatID), msgID: mid, sender: sender, device: dev, ts: ts, replyTo: reply,
                                           bodyCT: body, sealedKey: key, pk: pair.pk, sk: pair.sk)
        // Pruefung: devices/idents aus der Antwort (title_msg: device + ident_pub direkt)
        let d = (devices?[dev] as? [String: Any]) ?? (m["device"] as? [String: Any])
        let ip = (idents?[sender] as? String) ?? (m["ident_pub"] as? String)
        var ok = false
        if let d, (d["uid"] as? String ?? sender) == sender, let ipk = ChatCrypto.unb64(ip, 32),
           let enc = ChatCrypto.unb64(d["enc_pub"] as? String, 32), let sg = ChatCrypto.unb64(d["sign_pub"] as? String, 32),
           ChatCrypto.verify(ChatCrypto.unb64(d["sig"] as? String), ChatCrypto.deviceMsg(uid: sender, deviceID: dev, encPK: enc, signPK: sg), pk: ipk) {
            ok = ChatCrypto.verifyMessage(chatID: String(chatID), msgID: mid, sender: sender, device: dev, ts: ts, replyTo: reply, bodyCT: body,
                                          sig: m["sig"] as? String ?? "", signPK: sg)
        }
        return (pt.flatMap { ChatContent(json: $0) }, ok)
    }
}

/// "Neu beginnen" braucht eine frische Anmeldung bzw. das Passwort
struct ChatReauth: Error, LocalizedError {
    let kind: String; let message: String
    var errorDescription: String? { message }
}
