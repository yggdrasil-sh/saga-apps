import SwiftUI
import UserNotifications

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

/// TOFU v2 (Chats ueber Servergrenzen, 2.3): je Handle {ident, verified, changed, old, dver} - getrennt von den v1-Eintraegen
enum FedTrustStore {
    private static func key(_ me: String) -> String { "hk.chat.trust2." + (AppConfig.serverOrigin ?? "-") + "|" + me }
    static func get(_ me: String, _ handle: String) -> FedTrust? {
        guard let d = UserDefaults.standard.dictionary(forKey: key(me))?[handle] as? Data else { return nil }
        return try? JSONDecoder().decode(FedTrust.self, from: d)
    }
    static func set(_ me: String, _ handle: String, _ t: FedTrust) {
        var a = UserDefaults.standard.dictionary(forKey: key(me)) ?? [:]
        a[handle] = try? JSONEncoder().encode(t)
        UserDefaults.standard.set(a, forKey: key(me))
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
    /// 2.1: womit "Neu beginnen" bestaetigt werden kann (password, passkey, totp)
    var reauthMethods: [String] = []
    var msgMax = 65_536
    var fileMax = 20 * 1_048_576
    var filesPerMsg = 10
    /// 2.1-c: Kennungen je Nachricht (Original + Vorschaubild); aeltere Server: files_per_msg
    var fileIDsPerMsgServer: Int?
    var fileIDsPerMsg: Int { fileIDsPerMsgServer ?? filesPerMsg }
    var membersMax = 50
    var retentionMax: Int?
    var retentions: [Int] = [0, 3600, 86_400, 604_800, 2_592_000, 7_776_000, 31_536_000]
    /// 2.4: Gelesen-Bestaetigungen/Online-Status (features, privacy, status_limits; aeltere Server: nichts)
    var status = ReceiptLogic.Caps()
    /// 2.4: Videos im Chat (saga-file/v2): Server kann es, groesstes Video (Klartext), Teil je PUT, gleichzeitige Uploads,
    /// Chat-Speicher des Kontos (Grenze/belegt, Bytes). Aeltere Server: file2 fehlt -> nur v1 bis file_max.
    var file2 = false
    var videoMax: Int?
    var uploadPart: Int?
    var uploadsMax = 2
    var quota: Int64?
    var used: Int64?
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
        reauthMethods = (j["reauth_methods"] as? [Any] ?? []).compactMap { $0 as? String }
        if let l = j["limits"] as? [String: Any] {
            msgMax = ShareJSON.int(l["msg_max"]) ?? msgMax; fileMax = ShareJSON.int(l["file_max"]) ?? fileMax
            filesPerMsg = ShareJSON.int(l["files_per_msg"]) ?? filesPerMsg; membersMax = ShareJSON.int(l["members_max"]) ?? membersMax
            fileIDsPerMsgServer = ShareJSON.int(l["file_ids_per_msg"])
            retentionMax = ShareJSON.int(l["retention_max"]).flatMap { $0 > 0 ? $0 : nil }
            if let r = l["retentions"] as? [Any] { retentions = r.compactMap(ShareJSON.int) }
            file2 = ShareJSON.bool(l["file2"]); videoMax = ShareJSON.int(l["video_max"]); uploadPart = ShareJSON.int(l["upload_part"])
            uploadsMax = max(1, ShareJSON.int(l["uploads_max"]) ?? uploadsMax)
            quota = ShareJSON.num(l["quota"]).map { Int64($0) }; used = ShareJSON.num(l["used"]).map { Int64($0) }
        }
        status = ReceiptLogic.caps(j)
        x3 = ChatX3.caps(j)
    }
    /// 2.5: Reaktionen, Bearbeiten, Sprachnachrichten (features, x3_limits; aeltere Server: nichts)
    var x3 = ChatX3.Caps()
    var hasIdentity: Bool { identPub != nil }
}

struct ChatMember: Equatable, Identifiable {
    var username: String; var name: String; var me: Bool
    /// Profilbild (2.1): relative Adresse /api/avatar/... oder nil
    var avatar: String? = nil
    /// 2.4 (W4): Kennung wie in der Personensuche (pseudonymer Handle); username bleibt fuer die Krypto, wird nie gezeigt
    var ref: String? = nil
    /// 2.4: Unterzeile fuer Gleichnamige (members[].sub: oeffentlicher Name bzw. Handle, nie der Anmeldename)
    var sub: String? = nil
    /// 2.5: Handle '~x@host' (Chats v1 liefern ihn Clients der Stufe 3 fuer die Vertrauens-Uebernahme; Stufe 3: = username)
    var handle: String? = nil
    var id: String { username }
}

struct ChatSummary: Identifiable, Equatable {
    var id: Int; var kind: String; var name: String?; var group: String?; var noteID: Int?
    var members: [ChatMember]; var memberCount: Int; var unread: Int; var muted: Bool; var retention: Int; var role: String
    var lastAt: Double; var lastSeq: Int; var peer: ChatMember?
    var titleMsg: [String: Any]?
    /// entschluesselter Name einer freien Gruppe
    var decryptedTitle: String?
    /// KI im Chat erlaubt (2.1, nur auf Zuruf)
    var ai = false
    /// Archiv und Loeschen (2.1): Server kennt es, von mir archiviert, "Verlauf geloescht bis", was ich fuer alle loeschen darf
    var canArchive = false
    var archived = false
    var clearedSeq = 0
    var deleteForAll: ChatDeleteLogic.ForAll?
    /// Chat ueber Servergrenzen (2.3, v: 2): chat_ref, Heim, eigener Handle, Anfrage, schreibgeschuetzt, Handles
    var fed: FedChatInfo?
    var isFed: Bool { fed != nil }
    /// Kennung fuer Anhaenge: v1 die chat_id, v2 die chat_ref
    var fileCID: String { fed?.ref ?? String(id) }
    /// 2.4: der andere Server kann Gelesen/Online (v2: receipts_ok/online_ok; lokal immer)
    var receiptsOK = true
    var onlineOK = true
    /// 2.4: alle beteiligten Server koennen grosse Videos (chatfile2); nil = Feld fehlt (lokal bzw. alter Server)
    var bigFiles: Bool?
    /// 2.4 E5 (chat.call): Gruppenanrufe moeglich (alle beteiligten Server calls3), Hinweis, laufender Gruppenanruf
    var callGroup: Bool?
    var callGroupBlock: String?
    var callRunning: String?
    var statusFlags: ReceiptLogic.ChatFlags {
        ReceiptLogic.ChatFlags(kind: kind, fed: isFed, request: fed?.request ?? false, readonly: fed?.readonly ?? false, receiptsOK: receiptsOK, onlineOK: onlineOK)
    }
    static func == (a: ChatSummary, b: ChatSummary) -> Bool {
        a.id == b.id && a.kind == b.kind && a.name == b.name && a.members == b.members && a.unread == b.unread && a.muted == b.muted
            && a.retention == b.retention && a.lastAt == b.lastAt && a.lastSeq == b.lastSeq && a.decryptedTitle == b.decryptedTitle && a.role == b.role && a.ai == b.ai
            && a.canArchive == b.canArchive && a.archived == b.archived && a.clearedSeq == b.clearedSeq && a.deleteForAll == b.deleteForAll && a.fed == b.fed
            && a.receiptsOK == b.receiptsOK && a.onlineOK == b.onlineOK && a.bigFiles == b.bigFiles
            && a.callGroup == b.callGroup && a.callGroupBlock == b.callGroupBlock && a.callRunning == b.callRunning
    }
    init?(_ v: Any?) {
        guard let d = v as? [String: Any], let i = ShareJSON.int(d["id"]) else { return nil }
        id = i; kind = d["kind"] as? String ?? "dm"; name = ShareJSON.str(d["name"]); group = ShareJSON.str(d["group"]); noteID = ShareJSON.int(d["note_id"])
        members = (d["members"] as? [Any] ?? []).compactMap { v in
            guard let m = v as? [String: Any], let u = m["username"] as? String else { return nil }
            let ref = ShareJSON.str(m["ref"])
            return ChatMember(username: u, name: PersonRef.display(name: m["name"] as? String, ref: ref, id: u), me: ShareJSON.bool(m["me"]),
                              avatar: ShareJSON.str(m["avatar"]), ref: ref, sub: PersonRef.sub(m["sub"]),
                              handle: ShareJSON.str(m["handle"]).flatMap { FedChatLogic.isHandle($0) ? $0 : nil })
        }
        memberCount = ShareJSON.int(d["member_count"]) ?? members.count
        unread = ShareJSON.int(d["unread"]) ?? 0; muted = ShareJSON.bool(d["muted"]); retention = ShareJSON.int(d["retention_s"]) ?? 0
        role = d["role"] as? String ?? "member"; lastAt = ShareJSON.num(d["last_at"]) ?? 0; lastSeq = ShareJSON.int(d["last_seq"]) ?? 0
        if let p = d["peer"] as? [String: Any], let u = p["username"] as? String { peer = ChatMember(username: u, name: PersonName.shown(p["name"] as? String, id: ShareJSON.str(p["handle"]) ?? u, host: ShareJSON.str(p["host"])), me: false, avatar: ShareJSON.str(p["avatar"])) }
        titleMsg = d["title_msg"] as? [String: Any]
        ai = ShareJSON.bool(d["ai"])
        canArchive = ChatDeleteLogic.supported(d)
        archived = ShareJSON.bool(d["archived"]); clearedSeq = ShareJSON.int(d["cleared_seq"]) ?? 0
        deleteForAll = ChatDeleteLogic.forAll(d["delete_for_all"])
        fed = FedChatInfo(d)
        (receiptsOK, onlineOK) = ReceiptLogic.flags(d)
        bigFiles = d["big_files"] as? Bool
        if let k = d["call"] as? [String: Any] {
            callGroup = k["group"] as? Bool; callGroupBlock = ShareJSON.str(k["group_block"])
            if let r = k["running"] as? [String: Any], (r["state"] as? String) != "ended", (r["kind"] as? String) == "group" { callRunning = r["id"] as? String }
        }
    }
    var title: String {
        switch kind {
        case "free": return decryptedTitle ?? members.filter { !$0.me }.map(\.name).joined(separator: ", ")
        default: return name.map { PersonName.shown($0) } ?? String(localized: "Chat")     // D01: Kennung -> lesbar
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
    /// Nachrichtenanfragen von Personen anderer Server (2.3, zaehlen nicht in unread)
    @Published var requests = 0
    /// eigener foederierter Handle (2.3, '~x@host'; erst nach erstem Bedarf)
    @Published var myHandle: String?
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
    private(set) var keys: ChatKeys? {
        // Chats gerade entsperrt/eingerichtet: Anruf-Faehigkeiten jetzt melden und laden - sonst fehlten die
        // Gruppenanruf-Knoepfe bis zum naechsten App-Start (F8)
        didSet { if oldValue == nil, keys != nil, state != .loading { Task { await store?.calls.chatKeysReady() } } }
    }
    /// Schluessel dieses Geraets fuer FedFlow (einmal je Schluessel berechnet statt je Nachricht)
    private var ownMemo: (keys: ChatKeys, own: FedFlow.Own)?
    var own: FedFlow.Own? {
        guard let k = keys else { return nil }
        if let m = ownMemo, m.keys == k { return m.own }
        let o = k.fedOwn; ownMemo = (k, o); return o
    }
    private var pendingDevice: (id: String, enc: ChatCrypto.KeyPair, sign: ChatCrypto.KeyPair)?
    private var linkPoll: Task<Void, Never>?
    private var handledLinks = Set<String>()
    /// Zertifikate v2 werden gerade hochgeladen (ChatFed.swift)
    var fedCertsRunning = false
    /// Gruppenname v2 je Chat (Nachrichten-ID der title_msg -> geprueft entschluesselter Name)
    var titleMemo: [Int: (msg: String, title: String)] = [:]
    /// 2.4: Online-Status abonnierter Personen (Benutzername bzw. Prinzipal) und Abo-Zustand (ChatReceipts.swift)
    @Published var presence: [String: ReceiptLogic.Presence] = [:]
    var presenceSub = ReceiptLogic.PresenceSub()
    var presenceLoop: Task<Void, Never>?
    /// App im Vordergrund (scenePhase) und Chat-Liste sichtbar - nur dann Bestaetigungen und Online-Abo
    var appActive = true
    var listVisible = false

    var deviceID: String? { keys?.deviceID }
    var deviceParam: String { keys.map { "device=" + $0.deviceID } ?? "device=" }

    func signedOut() {
        keys = nil; me = ChatMe(); chats = []; unread = 0; requests = 0; myHandle = nil; state = .loading; approval = nil; linking = nil; titleMemo = [:]
        linkPoll?.cancel(); pendingDevice = nil; handledLinks = []
        presenceLoop?.cancel(); presenceLoop = nil; presence = [:]; presenceSub = ReceiptLogic.PresenceSub()
        ChatMedia.clearAll()                                            // Bild-Caches (Speicher + Ciphertext auf der Platte)
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
                    ChatKeyStore.delete(uid: m.uid); k = nil; ChatMedia.clearAll()   // Neu begonnen/entfernt: alte Schluessel + Bild-Caches weg
                }
            }
            keys = k
            guard let k else { state = .newDevice; return }
            if did.isEmpty { _ = try? await store.apiResponse("api/chat/me?device=\(k.deviceID)") }   // "zuletzt gesehen"
            state = .ready
            startPresence()                                             // 2.4: Online-Abo (nur Sichtbares, nur vorne)
            Task { await fedCerts() }                                   // 2.3: Zertifikate v2 (still, nur Server mit Foederation)
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
            ChatH2UStore.note(me.uid, list)                             // 2.5: Handle -> Benutzer aus Chats v1 (Vertrauen)
            for i in list.indices where list[i].kind == "free" { list[i].decryptedTitle = decryptTitle(list[i]) }
            await decryptTitlesV2(&list)                                // 2.3: Gruppen ueber Servergrenzen (Absender selbst pruefen)
            if list != chats { chats = list }
            unread = ShareJSON.int(j["unread"]) ?? unread
            requests = ShareJSON.int(j["requests"]) ?? list.filter { $0.fed?.request == true }.count
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
    func setup(recoveryKey rk: String, reset: Bool = false, auth: [String: String]? = nil) async throws {
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
        if reset { body["reset"] = true; ChatMedia.clearAll() }       // Neu beginnen: alte Bilder sind danach ohnehin nicht mehr lesbar
        for (k, v) in auth ?? [:] { body[k] = v }                       // {password} oder {code} (2.1)
        let (d, code) = try await store.apiResponse("api/chat/identity", method: "PUT", body: body)
        guard (200..<300).contains(code) else {
            let j = ShareJSON.obj(d)
            if code == 403, let r = ShareJSON.str(j?["reauth"]) {
                if let m = j?["reauth_methods"] as? [Any] { me.reauthMethods = m.compactMap { $0 as? String } }
                throw ChatReauth(kind: r, message: ShareJSON.str(j?["error"]) ?? "")
            }
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
        guard ChatCrypto.bundleFormatOK(bundle) else { throw APIError.other(String(localized: "Unbekanntes Schlüsselformat")) }
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
        if id == keys?.deviceID { ChatKeyStore.delete(uid: me.uid); keys = nil; ChatMedia.clearAll() }
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

    // MARK: Archivieren und Loeschen (2.1)

    /// Server kennt Archiv/Loeschen (aeltere Server: Funktionen ausblenden)
    var archiveSupported: Bool { chats.contains { $0.canArchive } }

    /// Archivieren / aus dem Archiv holen (nur fuer mich)
    func setArchived(_ cid: Int, _ on: Bool) async {
        guard let store else { return }
        do {
            let (d, code) = try await store.apiResponse("api/chats/\(cid)", method: "PATCH", body: ChatDeleteLogic.archiveBody(on))
            guard (200..<300).contains(code) else { throw APIError.http(code, ShareJSON.obj(d).flatMap { ShareJSON.str($0["error"]) } ?? "") }
            if let i = chats.firstIndex(where: { $0.id == cid }) { chats[i].archived = on }
            toast = on ? String(localized: "Chat archiviert – er kommt mit der nächsten Nachricht zurück.") : String(localized: "Chat aus dem Archiv geholt.")
        } catch { toast = error.localizedDescription }
        await loadList()
    }

    /// Chat loeschen: "Nur fuer mich" (Verlauf bis jetzt, ggf. Gruppe verlassen) oder "Fuer alle"
    func deleteChat(_ c: ChatSummary, scope: ChatDeleteLogic.Scope, leave: Bool) async throws {
        guard let store else { return }
        let (d, code) = try await store.apiResponse(ChatDeleteLogic.path(c.id, scope), method: "DELETE")
        guard (200..<300).contains(code) else { throw APIError.http(code, ShareJSON.obj(d).flatMap { ShareJSON.str($0["error"]) } ?? "") }
        let effect = ChatDeleteLogic.effect(kind: c.kind, scope: scope, forAll: c.deleteForAll)
        let leaving = leave && ChatDeleteLogic.offerLeave(kind: c.kind, scope: scope)
        purge(c.id, upTo: scope == .me ? ShareJSON.int(ShareJSON.obj(d)?["cleared_seq"]) : nil, close: true)
        if leaving { _ = try? await store.apiResponse("api/chats/\(c.id)/members/\(me.uid)", method: "DELETE") }
        if effect == .removed || effect == .hidden || leaving { chats.removeAll { $0.id == c.id } }
        switch effect {
        case .removed: toast = String(localized: "Chat für alle gelöscht.")
        case .historyCleared: toast = String(localized: "Verlauf für alle gelöscht.")
        case .archived: toast = leaving ? String(localized: "Verlauf gelöscht, Gruppe verlassen.") : String(localized: "Verlauf für dich gelöscht, Chat archiviert.")
        case .hidden: toast = String(localized: "Chat für dich gelöscht.")
        }
        await loadList()
    }

    /// Geoeffneter Chat soll zu (geloescht, ausgeblendet) - ChatsScreen beobachtet das
    struct CloseRequest: Equatable { let id: Int; let token = UUID() }
    @Published var closeRequest: CloseRequest?

    /// Lokalen Zwischenspeicher eines Chats leeren: Nachrichten (bis upTo, nil = alles), entschluesselte Inhalte,
    /// Bilder, Entwurf, Benachrichtigungen (Glocke und Mitteilungszentrale); ggf. schliessen
    func purge(_ cid: Int, upTo: Int?, close: Bool) {
        var extra: [String] = []
        if let o = open, o.chatID == cid {
            extra = o.messages.flatMap { m in (m.content?.files ?? []).flatMap { [$0.id] + ($0.thumb.map { [$0.id] } ?? []) } }
            o.purge(upTo: upTo)
        }
        ChatMedia.purge(chatID: cid, extra: extra)
        store?.notifs.dropChat(cid)
        ChatNotifCenter.removeDelivered(chatID: cid)
        if close { closeRequest = CloseRequest(id: cid) }
    }

    // MARK: Live

    func live(_ type: String, _ d: [String: Any]) {
        let cid = ShareJSON.int(d["chat_id"])
        if let a = ChatDeleteLogic.live(type, d, me: me.uid, kind: chats.first { $0.id == cid }?.kind) {   // geloescht (2.1)
            purge(a.chatID, upTo: a.purgeUpTo, close: a.close)
            if a.removeFromList { chats.removeAll { $0.id == a.chatID } }
            switch a.notice {
            case .chatDeleted(let n): toast = String(localized: "\(n) hat den Chat gelöscht.")
            case .historyDeleted(let n): toast = String(localized: "\(n) hat den Verlauf gelöscht.")
            case nil: break
            }
            if !a.close, let o = open, o.chatID == a.chatID { Task { await o.load() } }   // Verlauf neu (Hinweis "geloescht")
            Task { await loadList(); if keys == nil { await refreshUnread() } }
            return
        }
        switch type {
        case "chat.message", "chat.deleted", "chat.read", "chat.changed":
            if let o = open, o.chatID == cid { o.live(type, d) }
            Task { await loadList(); if keys == nil { await refreshUnread() } }
        case "chat.typing", "chat.react", "chat.edited":                // 2.5: Reaktionen, Bearbeitungen (kein Push, keine Liste)
            if let o = open, o.chatID == cid { o.live(type, d) }
        case "chat.receipt", "chat.presence", "chat.privacy":           // 2.4: Gelesen-Bestaetigungen, Online-Status
            statusLive(type, d)
        case "chat.request":                                            // 2.3: neue Nachrichtenanfrage (still)
            Task { await loadList() }
        case "chat.keys.changed":                                       // 2.3: Schluessel einer Person bzw. die eigenen
            if ShareJSON.bool(d["own"]) { Task { await fedCerts() } }
            else if let h = d["handle"] as? String, let o = open, o.info?.fed?.handles.contains(h) == true { Task { await o.fedLoad(fresh: true) } }
        case "chat.link":
            let id = d["id"] as? String
            if let l = linking, l.id == id { Task { await pollLink() }; return }
            if ShareJSON.bool(d["gone"]) || ShareJSON.bool(d["approved"]) { if approval?.id == id { approval = nil } }
            else if keys != nil { Task { await checkLinks() } }
        case "chat.device":
            if let id = d["id"] as? String, id == keys?.deviceID, ShareJSON.bool(d["revoked"]) {
                ChatKeyStore.delete(uid: me.uid); keys = nil; state = .newDevice; chats = []; ChatMedia.clearAll()
            }
        default: break
        }
    }

    // MARK: Freie Gruppe: verschluesselter Name

    func decryptTitle(_ c: ChatSummary) -> String? {
        if c.isFed {                                                    // v2: geprueft in decryptTitlesV2 (gemerkt je Nachricht)
            guard let mid = c.titleMsg?["id"] as? String, let t = titleMemo[c.id], t.msg == mid else { return nil }
            return t.title
        }
        guard let m = c.titleMsg, let content = decrypt(chatID: c.id, raw: m, devices: nil, idents: nil)?.content, content.type == "title" else { return nil }
        return content.title
    }

    /// Nachricht oeffnen + Echtheit pruefen (Geraet gehoert dem Absender, Zertifikat gegen dessen Identitaet, Signatur)
    func decrypt(chatID: Int, raw m: [String: Any], devices: [String: Any]?, idents: [String: Any]?) -> (content: ChatContent?, verified: Bool)? {
        guard let o = own, let r = FedFlow.openV1(m, chatID: String(chatID), own: o, devices: devices, idents: idents) else { return nil }
        return (r.plain.flatMap { ChatContent(json: $0) }, r.verified)
    }
}

/// "Neu beginnen" braucht eine frische Anmeldung bzw. das Passwort
struct ChatReauth: Error, LocalizedError {
    let kind: String; let message: String
    var errorDescription: String? { message }
}

/// Zugestellte Mitteilungen eines Chats aus der Mitteilungszentrale entfernen (Chat geloescht, 2.1)
enum ChatNotifCenter {
    static func removeDelivered(chatID: Int) {
        let c = UNUserNotificationCenter.current()
        c.getDeliveredNotifications { list in
            let ids = list.filter { n in
                let u = n.request.content.userInfo
                return ShareJSON.int(u["chat_id"]) == chatID || NotifLogic.chatID(fromURL: u["url"] as? String) == chatID
            }.map(\.request.identifier)
            if !ids.isEmpty { c.removeDeliveredNotifications(withIdentifiers: ids) }
        }
    }
}
