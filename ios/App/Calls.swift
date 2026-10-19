import SwiftUI
import AVFoundation
import Network
import WebRTC

// Anrufe (Saga 2.1): 1:1 aus dem Direkt-Chat, WebRTC Geraet <-> Geraet. Signalisierung ueber /api/calls + Live-Kanal
// (call.ring, call.signal, call.state); jede Signal-Nachricht signiert + versiegelt (CallCrypto.swift, saga-call/v1).
// Pruefung beim Empfang: Geraet des Gegenuebers (Zertifikat + TOFU wie im Chat), Signatur mit MIR als Ziel,
// fp == Fingerabdruecke der SDP, nach dem Verbinden DTLS-Zertifikat des Gegenuebers in fp. Ablauf wie static/calls.js.
// Medien: CallMedia.swift; CallKit/PushKit bzw. Klingeln am Mac: CallSystem.swift; Oberflaeche: CallViews.swift.

struct CallPeerDevice { var enc: Data; var sign: Data }
struct CallRemoteState: Equatable { var mic = true; var cam = false; var screen = false }

/// Klingelnder Anruf (Anzeige ohne CallKit bzw. vor dem Annehmen)
struct IncomingCall: Identifiable, Equatable {
    var id: String; var chatID: Int; var media: String; var name: String; var username: String; var avatar: String?
}

@MainActor
final class CallManager: NSObject, ObservableObject {
    weak var store: Store?
    enum Phase: String, Equatable { case idle, preparing, ringingOut, incoming, connecting, active, ended }
    @Published private(set) var phase: Phase = .idle { didSet { if phase != oldValue { pipRefresh() } } }
    @Published var status = ""
    @Published private(set) var peerName = ""
    @Published private(set) var peerAvatar: String?
    @Published private(set) var media = "audio"
    @Published private(set) var role = "caller"
    @Published private(set) var mic = true
    @Published private(set) var cam = false
    @Published private(set) var remote = CallRemoteState()
    @Published private(set) var verified = false
    @Published private(set) var relay = false
    @Published private(set) var connectedAt: Date?
    @Published private(set) var localVideo: RTCVideoTrack?
    /// Eigenes Bild nur bei der Frontkamera spiegeln (Rueckkamera: Schrift lesbar)
    @Published private(set) var frontCamera = true
    @Published private(set) var remoteVideo: RTCVideoTrack? { didSet { if remoteVideo !== oldValue { pipRefresh() } } }
    @Published private(set) var incoming: IncomingCall?
    @Published private(set) var failed = false
    @Published var speaker = false
    /// Ton-Diagnose (getStats, alle 2 s): kommt Ton an, liefert das Mikrofon etwas - fuer den Handtest im Anruffenster
    @Published private(set) var audioIn = CallLogic.AudioIn.unknown
    @Published private(set) var audioMic = CallLogic.AudioMic.unknown
    private var meter = CallLogic.AudioMeter()
    /// Server kann Anrufe (aus /api/calls/config); unbekannt = an (Knopf zeigen, Fehler beim Anrufen)
    @Published private(set) var enabled = true
    /// Hinweis ausserhalb eines Anrufs ("Du telefonierst gerade schon.")
    @Published var toast: String?
    /// 2.3.1: Anruf-Oberflaeche verkleinert (iPhone/iPad) - zurueck in der App, oben die Leiste "Anruf laeuft".
    /// Nur die Anzeige: Verbindung, Ton und Video laufen unveraendert weiter (spaeter auch Bild-im-Bild).
    @Published var minimized = false
    /// Vollbild gerade wirklich auf dem Bildschirm (von der Oberflaeche gesetzt; zum Warten vor dem Navigieren)
    var coverVisible = false
    /// 2.4: Bild-im-Bild (iOS-PiP bzw. Mac-Schwebefenster) aktiv -> Gegenueber bekommt want small
    @Published private(set) var pip = false
    /// 2.4: Pfad des gewaehlten Kandidatenpaars (Bitrate-Regeln, Konzept §1.3)
    @Published private(set) var path: CallV3.Path?
    /// 2.4: Verbindung wird per ICE-Restart neu aufgebaut
    @Published private(set) var reconnecting = false

    // Zustand des laufenden Anrufs
    private var id: String?
    private var chatID = 0
    private var peer = ""
    private var peerDevs: [String: CallPeerDevice] = [:]
    private var peerDevice: String?
    private var fps: [String] = []
    private var seen = Set<Int>()
    private var early: [[String: Any]] = []
    private var remoteIce: [CallLogic.Candidate] = []
    private var outIce: [CallLogic.Candidate] = []
    private var outTask: Task<Void, Never>?
    private var ready = false
    private var connected = false
    private var ending = false
    private var answered = false
    private var pc: RTCPeerConnection?
    private var audioTrack: RTCAudioTrack?
    private var camera: CallCamera?
    private var aliveTask: Task<Void, Never>?
    private var connTask: Task<Void, Never>?
    private var statsTask: Task<Void, Never>?
    private var cfg: [String: Any] = [:]
    private var keysRefreshed = false
    /// 2.3: Anruf ueber Servergrenzen (saga-call/v2): Handles statt Benutzernamen
    private var v2: (me: String, peer: String)?
    /// Anrufe, die schon beendet/abgelehnt wurden (spaete call.ring/Push nicht erneut klingeln lassen)
    private var done = Set<String>()
    // 2.4 (saga-call v3): Kennung der PeerConnection, Faehigkeiten des Gegenuebers (nil = 2.3), Neuverhandlung
    /// pc-Kennung: legt der Anrufer je Zielgeraet fest (sortiertes Paar der device_ids), der Angerufene uebernimmt sie
    private var pcID: String?
    /// Server-Features aus /api/calls/config (2.4: "reneg", "pip_hint"; aeltere Server: keine)
    private var serverFeatures: [String] = []
    /// Weg, den das Gegenueber meldet (state.path) - es gilt der schlechtere
    private var remotePath: CallV3.Path?
    /// ICE-ufrags frueherer Remote-SDPs (Kandidaten dieser Generationen verwerfen)
    private var oldUfrags = Set<String>()
    private var peerCaps: [String]?
    private var neg = CallV3.Negotiator()
    private var remoteWant = CallV3.Want.large
    private var wantOut = PiPLogic.WantSender()
    private var appliedVideo: CallV3.Limits?
    private var appliedAudio: Int?
    private var restarts = 0
    private var restartTask: Task<Void, Never>?
    private var net: NWPathMonitor?
    private var lastNet: CallV3.NetPath?
    /// eigene Kamera vom System pausiert (PiP/Hintergrund ohne Kamera-Multitasking) -> state.cam = false
    private var camPaused = false
    /// Signale nacheinander verarbeiten (Neuverhandlung darf sich nicht mit ICE/Antworten ueberholen)
    private var chain: Task<Void, Never>?
    private var myCaps: [String] { CallV3.myCaps(serverFeatures: serverFeatures) }
    private var canReneg: Bool { CallV3.canReneg(mine: myCaps, peer: peerCaps) }
    /// pc fuer ein Zielgeraet: festgelegt bzw. (Anrufer vor der Antwort) das Paar mit diesem Geraet
    private func pcFor(_ dev: String) -> String { pcID ?? CallV3.pairPC(myDevice ?? "", dev) }
    /// Caps-Hinweis im JSON von /api/calls bzw. /answer nur an Server mit saga-call v3 (aeltere kennen das Feld nicht)
    private var capsHint: [String: Any] { serverFeatures.isEmpty ? [:] : ["caps": myCaps] }

    var busy: Bool { phase != .idle && phase != .ended }
    var callID: String? { id }
    /// Chat des laufenden Anrufs (0 = keiner) - fuer den Hinweis in der Unterhaltung
    var callChatID: Int { chatID }

    // MARK: Verkleinern / Zurueckkehren (2.3.1)

    /// iPhone/iPad: Vollbild gerade sichtbar (sonst Leiste bzw. nichts)
    var showsFullScreen: Bool { CallLogic.showsFullScreen(phase: phase.rawValue, minimized: minimized, callKit: CallEnv.callKit) }
    /// Zurueck in die App: Vollbild schliessen, Anruf laeuft weiter; Endhinweis gleich zu
    func minimize() {
        if phase == .ended { dismissEnded(); return }
        if CallLogic.isOngoing(phase.rawValue) { minimized = true; pipRefresh() }
    }
    func expand() { if CallLogic.isOngoing(phase.rawValue) { minimized = false; pipRefresh() } }

    // MARK: Bild-im-Bild (2.4)

    /// iOS: PiP-Steuerung nachziehen (Phase, Fernbild, verkleinert); Mac: Schwebefenster
    func pipRefresh() {
        #if os(iOS)
        CallPiP.shared.update(self)
        #else
        CallPanel.shared.refresh()
        #endif
    }
    /// PiP bzw. Schwebefenster an/aus -> Gegenueber um kleines/grosses Bild bitten (ein state-Signal je Wechsel)
    func pipChanged(_ on: Bool) {
        if pip != on { pip = on }
        if wantOut.update(PiPLogic.want(pip: on)) { stateOut() }
    }
    /// Kamera vom System unterbrochen (Hintergrund ohne Multitasking-Freigabe) bzw. wieder frei
    func cameraInterrupted(_ on: Bool) {
        guard camPaused != on else { return }
        camPaused = on; stateOut()
    }
    /// Vor dem Navigieren (Mitteilung, Link): Vollbild schliessen und warten, bis es wirklich weg ist
    func minimizeUI() async {
        #if os(iOS)
        guard showsFullScreen else { return }
        minimize()
        for _ in 0..<30 where coverVisible { try? await Task.sleep(for: .milliseconds(50)) }   // hoechstens 1,5 s
        try? await Task.sleep(for: .milliseconds(150))                     // Ausblende-Animation fertig
        #endif
    }
    private var me: String { store?.chat.me.uid ?? "" }
    private var myDevice: String? { store?.chat.keys?.deviceID }

    // MARK: Einstellungen

    @discardableResult
    func loadConfig() async -> Bool {
        guard let store, let (d, code) = try? await store.apiResponse("api/calls/config") else { return false }
        if code == 404 || code == 405 { enabled = false; return false }
        guard code == 200, let j = ShareJSON.obj(d) else { return false }
        cfg = j
        serverFeatures = (j["features"] as? [Any] ?? []).compactMap { $0 as? String }
        let on = ShareJSON.bool(j["enabled"])
        if on != enabled { enabled = on }
        return on
    }
    private var aliveSeconds: Double { Double(ShareJSON.int(cfg["alive_s"]) ?? 15) }

    /// Chat-Schluessel dieses Geraets bereit? (Anrufe brauchen das Chat-Geraet)
    private func ensureChat() async -> Bool {
        guard let store else { return false }
        if store.chat.keys == nil || store.chat.me.uid.isEmpty { await store.chat.load() }
        return store.chat.keys != nil
    }

    // MARK: Schluessel des Gegenuebers (Zertifikat + TOFU wie im Chat)

    private func loadPeerKeys(_ user: String, name: String) async throws {
        if let v = v2 {                                                 // 2.3: geprueft wie im Chat (signierte Geraete-Liste, TOFU v2)
            guard let st = try await store?.chat.fedKeys([v.peer], fresh: keysRefreshed, own: v.me)[v.peer], let k = st.keys else {
                throw APIError.other(String(localized: "\(name) hat Chats noch auf keinem Gerät eingerichtet – Anrufe gehen erst danach."))
            }
            guard st.verdict == .trust else {
                throw APIError.other(String(localized: "Die Sicherheitsnummer von \(name) hat sich geändert – erst im Chat prüfen oder „Verstanden“ wählen."))
            }
            peerDevs = Dictionary(k.active.map { ($0.id, CallPeerDevice(enc: $0.enc, sign: $0.sign)) }, uniquingKeysWith: { a, _ in a })
            return
        }
        guard let store, let enc = user.addingPercentEncoding(withAllowedCharacters: .alphanumerics) else { return }
        let d = try await store.api("api/chat/keys?users=\(enc)")
        guard let u = (ShareJSON.obj(d)?["users"] as? [String: Any])?[user] as? [String: Any], let ipk = ChatCrypto.unb64(u["ident_pub"] as? String, 32) else {
            throw APIError.other(String(localized: "\(name) hat Chats noch auf keinem Gerät eingerichtet – Anrufe gehen erst danach."))
        }
        let t = ChatTrustStore.observe(me, user, pk: ChatCrypto.b64(ipk))
        if t == .changed || ChatTrustStore.known(me, user)?.changed == true {
            throw APIError.other(String(localized: "Die Sicherheitsnummer von \(name) hat sich geändert – erst im Chat prüfen oder „Verstanden“ wählen."))
        }
        var out: [String: CallPeerDevice] = [:]
        for case let dv as [String: Any] in (u["devices"] as? [Any] ?? []) {
            guard !ShareJSON.bool(dv["revoked"]), let did = dv["id"] as? String, let e = ChatCrypto.unb64(dv["enc_pub"] as? String, 32),
                  let s = ChatCrypto.unb64(dv["sign_pub"] as? String, 32),
                  ChatCrypto.verify(ChatCrypto.unb64(dv["sig"] as? String), ChatCrypto.deviceMsg(uid: user, deviceID: did, encPK: e, signPK: s), pk: ipk) else { continue }
            out[did] = CallPeerDevice(enc: e, sign: s)
        }
        peerDevs = out
    }

    // MARK: Versiegeln / Oeffnen

    private func seal(_ to: String, _ body: String) -> String? {
        guard let id, let k = store?.chat.keys, let pd = peerDevs[to] else { return nil }
        if let v = v2 {
            return ChatCrypto.sealSignalV2(callID: id, fromHandle: v.me, fromDevice: k.deviceID, signSK: k.sign.sk, toHandle: v.peer, toDevice: to, toPK: pd.enc, body: body)
        }
        return ChatCrypto.sealSignal(callID: id, fromUID: me, fromDevice: k.deviceID, signSK: k.sign.sk, toUID: peer, toDevice: to, toPK: pd.enc, body: body)
    }
    private func sealAll(_ devs: [String], _ body: String) -> [String: String] {
        var out: [String: String] = [:]
        for d in devs { if let s = seal(d, body) { out[d] = s } }
        return out
    }
    /// Oeffnen + pruefen -> Inhalt (+ 2.4-Zusatzfelder); nil = Pruefung fehlgeschlagen. Unbekanntes Geraet: Schluessel einmal neu holen.
    private func open(_ s: [String: Any]) async -> (sig: CallLogic.Signal, meta: CallV3.Meta)? {
        guard let id, let k = store?.chat.keys, let from = s["from_device"] as? String, let sealed = s["sealed"] as? String else { return nil }
        if peerDevs[from] == nil && !keysRefreshed { keysRefreshed = true; try? await loadPeerKeys(peer, name: peerName) }
        if let v = v2 {                                                 // 2.3: an MEINEN Handle/mein Geraet gebunden
            guard let pd = peerDevs[from], let o = ChatCrypto.openSignalV2(sealed, pk: k.enc.pk, sk: k.enc.sk),
                  ChatCrypto.verifySignalV2(callID: id, fromHandle: v.peer, fromDevice: from, toHandle: v.me, toDevice: k.deviceID, body: o.body, sig: o.sig, signPK: pd.sign)
            else { return nil }
            return CallLogic.parse(o.body).map { ($0, CallV3.meta(o.body)) }
        }
        guard let pd = peerDevs[from], let o = ChatCrypto.openSignal(sealed, pk: k.enc.pk, sk: k.enc.sk),
              ChatCrypto.verifySignal(callID: id, fromUID: peer, fromDevice: from, toUID: me, toDevice: k.deviceID, body: o.body, sig: o.sig, signPK: pd.sign)
        else { return nil }
        return CallLogic.parse(o.body).map { ($0, CallV3.meta(o.body)) }
    }
    private var targets: [String] { peerDevice.map { [$0] } ?? Array(peerDevs.keys) }
    private func sendSignal(_ body: String) async { await sendSignal { _ in body } }
    /// Body je Zielgeraet (2.4: pc ist je Geraet verschieden, solange der Anrufer noch keine Antwort hat)
    private func sendSignal(_ make: (String) -> String) async {
        guard let store, let id, let dev = myDevice, !ending else { return }
        var sig: [String: String] = [:]
        for d in targets { if let x = seal(d, make(d)) { sig[d] = x } }
        guard !sig.isEmpty, let (_, code) = try? await store.apiResponse("api/calls/\(id)/signal", method: "POST", body: ["device": dev, "signals": sig]) else { return }
        if code == 409 { remoteEnded(reason: "gone") }
    }
    private func stateOut() {
        guard ready else { return }
        // 2.4: pip + want (aeltere Gegenueber lesen nur mic/cam/screen)
        let (m, c, p, pth) = (mic, cam && !camPaused, pip, path)
        Task { await sendSignal { d in CallV3.stateBody(pc: self.pcFor(d), mic: m, cam: c, screen: false, pip: p, want: PiPLogic.want(pip: p), path: pth) } }
    }

    // MARK: Anrufen

    func start(chatID cid: Int, peer p: ChatMember, video: Bool, fed: FedChatInfo? = nil) {
        if busy { toast = String(localized: "Du telefonierst gerade schon."); return }
        if let f = fed, !f.callable { toast = String(localized: "Anrufe gehen erst, wenn die Anfrage angenommen ist."); return }
        reset()
        chatID = cid; peer = p.username; peerName = p.name; peerAvatar = p.avatar; role = "caller"; media = video ? "video" : "audio"
        if let f = fed, let h = f.peer?.handle, !f.myHandle.isEmpty { v2 = (f.myHandle, h) }   // 2.3: saga-call/v2
        speaker = video                                                     // Video: Lautsprecher (wie FaceTime), Audio: Hoermuschel
        phase = .preparing; status = String(localized: "Kamera und Mikrofon …")
        CallEnv.log("start \(media)")
        Task {
            do {
                guard await ensureChat() else { throw APIError.other(String(localized: "Chats sind auf diesem Gerät noch nicht bereit.")) }
                guard await loadConfig() else { throw APIError.other(String(localized: "Anrufe sind auf diesem Server abgeschaltet.")) }
                try await loadPeerKeys(peer, name: peerName)
                if peerDevs.isEmpty { throw APIError.other(String(localized: "\(peerName) hat Chats noch auf keinem Gerät eingerichtet – Anrufe gehen erst danach.")) }
                try await prepareMedia(video: video)
                guard phase == .preparing else { return }
                id = ChatCrypto.hexID()
                CallSystem.shared.startOutgoing(self, id: id!, name: peerName, video: media == "video")
                makePC()
                addLocal(fromOffer: false)
                guard let pc else { return }
                let offer = try await pc.offerAsync(CallRTC.constraints)
                try await pc.setLocalAsync(offer)
                try await dial(again: false)
            } catch { fail(error.localizedDescription) }
        }
    }
    private func dial(again: Bool) async throws {
        guard let store, let id, let dev = myDevice, let sdp = pc?.localDescription?.sdp, !ending, phase == .preparing else { return }
        status = String(localized: "Verbinde …")
        let n = neg.localN > 0 ? neg.localN : neg.initialOffer()       // 2.4: pc, caps, n (aeltere Apps lesen sdp/media/fp)
        var sig: [String: String] = [:]                                // pc je Zielgeraet: "min:max" der device_ids
        for d in peerDevs.keys { if let x = seal(d, CallV3.offerBody(sdp: sdp, media: media, pc: CallV3.pairPC(dev, d), n: n, caps: myCaps)) { sig[d] = x } }
        var req: [String: Any] = ["id": id, "chat_id": chatID, "media": media, "device": dev, "signals": sig]
        req.merge(capsHint) { a, _ in a }
        let (d, code) = try await store.apiResponse("api/calls", method: "POST", body: req)
        // Waehrend des POST aufgelegt (oder schon ein neuer Anruf): nicht klingeln lassen, sondern den eben angelegten
        // Anruf beim Server beenden - das /end aus hangup() kam ggf. vor dem Anlegen an und ging ins Leere.
        guard CallLogic.dialStillWanted(ending: ending, preparing: phase == .preparing, sameCall: self.id == id) else {
            CallEnv.log("dial: aufgelegt waehrend Verbinde")
            if (200..<300).contains(code) {
                _ = try? await store.apiResponse("api/calls/\(id)/end", method: "POST", body: ["reason": "hangup", "connected": false])
            }
            return
        }
        let j = ShareJSON.obj(d)
        if code == 409, j?["devices"] != nil, !again { keysRefreshed = v2 != nil; try await loadPeerKeys(peer, name: peerName); try await dial(again: true); return }
        if code == 409, ShareJSON.bool(j?["busy"]) { finish(.busy); return }
        guard (200..<300).contains(code) else { throw APIError.http(code, ShareJSON.str(j?["error"]) ?? "") }
        ready = true; phase = .ringingOut; status = String(localized: "Klingelt …")
        CallEnv.log("ringing \(id.prefix(8))")
        CallTones.shared.play(.ringback)
        flushIce(); aliveOn()
        let pending = early; early = []                                  // Antwort kam schneller als die POST-Antwort
        for s in pending { await handleRaw(s) }
    }

    // MARK: Klingeln (eingehend)

    /// call.ring oder VoIP-Push: Zustand holen, nur bei "ringing" klingeln
    func ringing(callID cid: String, hint: CallLogic.Push? = nil) async {
        guard let store else { return }
        if done.contains(cid) { CallSystem.shared.endReported(cid, reason: .remoteEnded); return }   // schon erledigt (VoIP-Push kam spaet)
        if incoming?.id == cid || id == cid { return }
        guard let (d, code) = try? await store.apiResponse("api/calls/\(cid)"), code == 200,
              let c = ShareJSON.obj(d)?["call"] as? [String: Any] else { CallSystem.shared.endReported(cid, reason: .failed); return }
        let st = c["state"] as? String, r = c["role"] as? String
        guard st == "ringing", r == "callee" else { CallSystem.shared.endReported(cid, reason: st == "active" ? .answeredElsewhere : .remoteEnded); return }
        // Live-Kanal (call.ring) und Nachholen (checkActive/Push) koennen gleichzeitig fragen: klingelt derselbe Anruf
        // inzwischen schon, ist das kein "besetzt" (sonst lehnte das zweite ringing() den eigenen Anruf ab)
        switch CallLogic.ringDecision(sameCallActive: incoming?.id == cid || id == cid || done.contains(cid), busy: busy) {
        case .ignore: return
        case .busy:                                                         // schon im Gespraech: besetzt
            _ = try? await store.apiResponse("api/calls/\(cid)/decline", method: "POST", body: ["reason": "busy"])
            CallSystem.shared.endReported(cid, reason: .unanswered); return
        case .ring: break
        }
        let p = c["peer"] as? [String: Any] ?? [:]
        // 2.3: Anruf ueber Servergrenzen (saga-call/v2) - eigener Handle noetig (Signale sind daran gebunden)
        var fedPair: (String, String)?
        if ShareJSON.int(c["v"]) == 2 {
            guard let h = ShareJSON.str(p["handle"]), let mh = await store.chat.fedMyHandle() else { CallSystem.shared.endReported(cid, reason: .failed); return }
            fedPair = (mh, h)
            if incoming?.id == cid || id == cid { return }
        }
        let inc = IncomingCall(id: cid, chatID: ShareJSON.int(c["chat_id"]) ?? 0, media: (c["media"] as? String) == "video" ? "video" : "audio",
                               name: ShareJSON.str(p["name"]) ?? hint?.caller ?? "", username: p["username"] as? String ?? "", avatar: ShareJSON.str(p["avatar"]))
        reset()
        v2 = fedPair
        incoming = inc; phase = .incoming; role = "callee"; media = inc.media; peerName = inc.name; peerAvatar = inc.avatar; peer = inc.username
        chatID = inc.chatID
        status = inc.media == "video" ? String(localized: "Videoanruf") : String(localized: "Anruf")
        CallEnv.log("incoming \(cid.prefix(8)) \(inc.media)")
        CallSystem.shared.reportIncoming(self, inc)
        aliveRinging(cid)
        #if DEBUG
        if let a = UserDefaults.standard.string(forKey: "hkCallAutoAnswer") {      // nur fuer Tests: annehmen/ablehnen
            Task { try? await Task.sleep(for: .seconds(4)); if incoming?.id == cid { if a == "decline" { decline() } else { answer(video: a == "video") } } }
        }
        #endif
    }

    /// Klingelt noch? (alle alive_s) - beim Gegenueber aufgelegt: aufhoeren
    private func aliveRinging(_ cid: String) {
        aliveTask?.cancel()
        aliveTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(5))
                guard let self, self.incoming?.id == cid, let store = self.store else { return }
                if let (d, c) = try? await store.apiResponse("api/calls/\(cid)"), c == 200, let call = ShareJSON.obj(d)?["call"] as? [String: Any],
                   (call["state"] as? String) != "ringing", self.incoming?.id == cid {
                    self.remoteEnded(reason: (call["state"] as? String) == "active" ? "elsewhere" : (call["reason"] as? String))
                    return
                }
            }
        }
    }

    // MARK: Annehmen / Ablehnen

    func answer(video: Bool) {
        guard let inc = incoming, phase == .incoming else { return }
        incoming = nil
        id = inc.id; phase = .connecting; status = String(localized: "Verbinde …"); answered = true
        speaker = video
        aliveTask?.cancel()
        CallTones.shared.stop()
        CallSystem.shared.answering(inc.id, video: video)
        CallEnv.log("answer \(video ? "video" : "audio")")
        Task {
            do {
                guard let store, await ensureChat(), let dev = myDevice else { throw APIError.other(String(localized: "Chats sind auf diesem Gerät noch nicht bereit.")) }
                _ = await loadConfig()
                let (d, code) = try await store.apiResponse("api/calls/\(inc.id)?device=\(dev)")
                guard code == 200, let j = ShareJSON.obj(d), let c = j["call"] as? [String: Any] else { throw APIError.http(code, "") }
                guard (c["role"] as? String) == "callee", (c["state"] as? String) == "ringing" else {
                    throw APIError.other((c["state"] as? String) == "active" ? String(localized: "Der Anruf wurde auf einem anderen Gerät angenommen.")
                                         : String(localized: "Der Anruf ist schon beendet."))
                }
                let callerDev = c["caller_device"] as? String ?? ""
                try await loadPeerKeys(peer, name: peerName)
                var offer: (sdp: String, fp: [String], meta: CallV3.Meta)?
                for s in j["signals"] as? [[String: Any]] ?? [] {
                    if let q = ShareJSON.int(s["seq"]) { _ = CallLogic.fresh(q, seen: &seen) }
                    guard let (sg, m) = await open(s) else { securityFail(); return }
                    guard (s["from_device"] as? String) == callerDev else { continue }
                    switch sg {
                    case .offer(let sdp, _, let fp): if offer == nil { offer = (sdp, fp, m) }
                    case .ice(let c): remoteIce += c
                    default: break
                    }
                }
                guard let offer, CallLogic.fingerprintsMatch(sdp: offer.sdp, fp: offer.fp) else { securityFail(); return }
                peerDevice = callerDev; fps = CallLogic.sdpFingerprints(offer.sdp)
                pcID = offer.meta.pc ?? CallV3.pairPC(dev, callerDev)       // vom Anrufer uebernehmen
                adoptPeer(offer.meta, device: callerDev)
                neg.initialRemote(offer.meta.n)
                try await prepareMedia(video: video)
                guard phase == .connecting else { return }
                makePC()
                guard let pc else { return }
                try await setRemote(RTCSessionDescription(type: .offer, sdp: offer.sdp))
                addLocal(fromOffer: true)
                findRemoteVideo()
                let ans = try await pc.answerAsync(CallRTC.constraints)
                try await pc.setLocalAsync(ans)
                guard let sdp = pc.localDescription?.sdp,
                      let sealed = seal(callerDev, CallV3.answerBody(sdp: sdp, media: media, pc: pcFor(callerDev), n: offer.meta.n ?? 1, caps: myCaps)) else { throw APIError.other("–") }
                var req: [String: Any] = ["device": dev, "signals": [callerDev: sealed]]
                req.merge(capsHint) { a, _ in a }
                let (ad, ac) = try await store.apiResponse("api/calls/\(inc.id)/answer", method: "POST", body: req)
                if ac == 409 { throw APIError.other(String(localized: "Der Anruf ist schon beendet oder wurde auf einem anderen Gerät angenommen.")) }
                guard (200..<300).contains(ac) else { throw APIError.http(ac, ShareJSON.obj(ad).flatMap { ShareJSON.str($0["error"]) } ?? "") }
                ready = true
                applyRemoteIce(); flushIce(); aliveOn(); stateOut()
                let pending = early; early = []
                for s in pending { await handleRaw(s) }
                connTask = Task {                                       // in finish() abgebrochen
                    try? await Task.sleep(for: .seconds(CallLogic.connectTimeout))
                    if !Task.isCancelled, !self.connected, self.id == inc.id { self.iceFailed() }
                }
            } catch { fail(error.localizedDescription) }
        }
    }
    func decline() {
        guard let inc = incoming else { return }
        done.insert(inc.id)
        Task { _ = try? await store?.apiResponse("api/calls/\(inc.id)/decline", method: "POST", body: ["reason": "declined"]) }
        CallEnv.log("declined")
        CallSystem.shared.endReported(inc.id, reason: .declinedLocally)
        incoming = nil; aliveTask?.cancel(); CallTones.shared.stop()
        phase = .idle; status = ""
    }

    // MARK: Medien

    private func prepareMedia(video: Bool) async throws {
        if !CallEnv.fakeMedia {
            guard await AVCaptureDevice.requestAccess(for: .audio) else {
                #if os(iOS)
                throw APIError.other(String(localized: "Mikrofon nicht erlaubt – bitte in den Einstellungen für Saga erlauben."))
                #else
                throw APIError.other(String(localized: "Mikrofon nicht erlaubt – bitte in den Systemeinstellungen unter Datenschutz für Saga erlauben."))
                #endif
            }
        }
        let f = CallRTC.factory
        #if os(macOS)
        let noMic = CallEnv.fakeMedia                                      // Tests am Mac: kein Ton-Track -> das echte Mikrofon wird nie geoeffnet
        #else
        let noMic = false
        #endif
        if !noMic {
            audioTrack = f.audioTrack(with: f.audioSource(with: CallRTC.constraints), trackId: "a" + ChatCrypto.hexID().prefix(8))
            audioTrack?.isEnabled = mic
        }
        if video {
            let allowed = CallEnv.fakeMedia ? true : await AVCaptureDevice.requestAccess(for: .video)
            if allowed, let c = CallCamera(factory: f) { camera = c; localVideo = c.track; cam = true; frontCamera = c.front; wire(c) }
            else { toast = String(localized: "Keine Kamera verfügbar – Anruf nur mit Ton."); media = role == "caller" ? "audio" : media }
        }
    }

    private func makePC() {
        let c = RTCConfiguration()
        c.sdpSemantics = .unifiedPlan
        c.bundlePolicy = .maxBundle
        c.iceTransportPolicy = ShareJSON.bool(cfg["relay_only"]) ? .relay : .all
        c.iceServers = (cfg["ice_servers"] as? [[String: Any]] ?? []).compactMap { s in
            let urls = (s["urls"] as? [String]) ?? ((s["urls"] as? String).map { [$0] } ?? [])
            guard !urls.isEmpty else { return nil }
            return RTCIceServer(urlStrings: urls, username: s["username"] as? String, credential: s["credential"] as? String)
        }
        pc = CallRTC.factory.peerConnection(with: c, constraints: CallRTC.constraints, delegate: self)
    }
    /// Immer zwei Transceiver (Audio + Video sendrecv) - Kamera an/aus ohne Neuverhandlung
    private func addLocal(fromOffer: Bool) {
        guard let pc else { return }
        let sid = "s" + (id ?? "0").prefix(8)
        if !fromOffer {
            let ai = RTCRtpTransceiverInit(); ai.direction = .sendRecv; ai.streamIds = [sid]
            if let a = audioTrack { pc.addTransceiver(with: a, init: ai) } else { pc.addTransceiver(of: .audio, init: ai) }
            let vi = RTCRtpTransceiverInit(); vi.direction = .sendRecv; vi.streamIds = [sid]
            if let v = camera?.track { pc.addTransceiver(with: v, init: vi) } else { pc.addTransceiver(of: .video, init: vi) }
            return
        }
        for t in pc.transceivers {
            var e: NSError?
            t.setDirection(.sendRecv, error: &e)
            t.sender.streamIds = [sid]
            if t.mediaType == .audio, let a = audioTrack { t.sender.track = a }
            if t.mediaType == .video, let v = camera?.track { t.sender.track = v }
        }
    }
    private var videoSender: RTCRtpSender? { pc?.transceivers.first { $0.mediaType == .video }?.sender }
    private func findRemoteVideo() {
        if let t = pc?.transceivers.first(where: { $0.mediaType == .video })?.receiver.track as? RTCVideoTrack, remoteVideo !== t { remoteVideo = t }
        for t in pc?.transceivers ?? [] where t.mediaType == .audio { if let a = t.receiver.track, !a.isEnabled { a.isEnabled = true } }   // Ton des Gegenuebers immer an
        #if os(macOS)
        if CallEnv.fakeMedia, let a = pc?.transceivers.first(where: { $0.mediaType == .audio })?.receiver.track as? RTCAudioTrack { a.source.volume = 0 }   // Tests: still
        #endif
    }

    // MARK: ICE

    private func flushIce() {
        outTask?.cancel(); outTask = nil
        guard ready, !outIce.isEmpty else { return }
        while !outIce.isEmpty {
            let n = min(CallLogic.iceBatch, outIce.count)
            let list = Array(outIce.prefix(n)); outIce.removeFirst(n)
            Task { await sendSignal { d in CallV3.iceBody(list, pc: self.pcFor(d)) } }
        }
    }
    fileprivate func localCandidate(_ c: CallLogic.Candidate) {
        outIce.append(c)
        if outTask == nil {
            outTask = Task { [weak self] in
                try? await Task.sleep(for: .milliseconds(Int(CallLogic.iceDelay * 1000)))
                guard !Task.isCancelled else { return }
                self?.outTask = nil; self?.flushIce()
            }
        }
    }
    /// Kandidaten setzen, die zur aktuellen Remote-SDP passen; fremde ufrag zurueckhalten (kommen oft vor der Antwort
    /// eines ICE-Restarts), aeltere Generationen verwerfen
    private func applyRemoteIce() {
        guard let pc, let rsdp = pc.remoteDescription?.sdp else { return }
        let cur = CallV3.sdpUfrags(rsdp)
        let list = remoteIce; remoteIce = []
        for c in list {
            switch CallV3.iceUse(c, current: cur, old: oldUfrags) {
            case .apply: pc.add(RTCIceCandidate(sdp: c.candidate, sdpMLineIndex: c.sdpMLineIndex, sdpMid: c.sdpMid)) { _ in }
            case .hold: remoteIce.append(c)
            case .drop: break
            }
        }
    }
    /// Remote-SDP setzen und die ufrags der vorigen als alte Generation merken
    private func setRemote(_ d: RTCSessionDescription) async throws {
        guard let pc else { return }
        let prev = CallV3.sdpUfrags(pc.remoteDescription?.sdp ?? "")
        try await pc.setRemoteAsync(d)
        oldUfrags.formUnion(prev.subtracting(CallV3.sdpUfrags(pc.remoteDescription?.sdp ?? "")))
    }

    // MARK: Live-Ereignisse

    func live(_ type: String, _ d: [String: Any]) {
        let cid = d["call_id"] as? String
        switch type {
        case "call.ring":
            guard let cid, CallLogic.isCallID(cid) else { return }
            if busy, cid != id, incoming?.id != cid {
                Task { _ = try? await store?.apiResponse("api/calls/\(cid)/decline", method: "POST", body: ["reason": "busy"]) }
                return
            }
            Task { await ringing(callID: cid) }
        case "call.signal":
            guard cid != nil, cid == id, (d["to_device"] as? String) == myDevice, let seq = ShareJSON.int(d["seq"]), CallLogic.fresh(seq, seen: &seen) else { return }
            if !ready { early.append(d); return }
            let prev = chain
            chain = Task { await prev?.value; await self.handleRaw(d) }   // der Reihe nach (Neuverhandlung)
        case "call.state":
            guard let cid else { return }
            let st = d["state"] as? String
            if incoming?.id == cid {
                if st == "ended" { remoteEnded(reason: d["reason"] as? String) }
                else if st == "active", (d["device"] as? String) != myDevice { remoteEnded(reason: "elsewhere") }
                return
            }
            guard cid == id else { if st == "ended" { CallSystem.shared.endReported(cid, reason: .remoteEnded) }; return }
            if st == "ended" { remoteEnded(reason: d["reason"] as? String) }
            else if st == "active", role == "caller", let dev = d["device"] as? String { peerDevice = peerDevice ?? dev; status = String(localized: "Verbinde …"); CallTones.shared.stop() }
        default: break
        }
    }
    /// Nach dem Start bzw. Zurueckkommen: klingelt gerade etwas fuer mich? (verpasste call.ring)
    func checkActive() async {
        if busy, ready, let id, let dev = myDevice, let store {                // Live-Kanal neu verbunden: verpasste Signale nachholen
            let after = seen.max() ?? 0
            guard let (d, code) = try? await store.apiResponse("api/calls/\(id)?device=\(dev)&after=\(after)"), code == 200, let j = ShareJSON.obj(d) else { return }
            if let c = j["call"] as? [String: Any], (c["state"] as? String) == "ended" { remoteEnded(reason: c["reason"] as? String); return }
            for s in j["signals"] as? [[String: Any]] ?? [] {
                if let q = ShareJSON.int(s["seq"]), CallLogic.fresh(q, seen: &seen) { await handleRaw(s) }
            }
            return
        }
        guard !busy, let store, let (d, code) = try? await store.apiResponse("api/calls/active"), code == 200 else { return }
        for c in ShareJSON.obj(d)?["calls"] as? [[String: Any]] ?? [] where (c["state"] as? String) == "ringing" && (c["role"] as? String) == "callee" {
            if let cid = c["id"] as? String { await ringing(callID: cid); return }
        }
    }

    private func handleRaw(_ s: [String: Any]) async {
        let from = s["from_device"] as? String
        guard let (sg, m) = await open(s) else { securityFail(); return }
        guard CallV3.samePC(m.pc, pcID ?? from.map(pcFor)) else { return }                // 2.4: andere PeerConnection (spaeter Mesh) - nicht unsere
        switch sg {
        case .ice(let c):
            remoteIce += c; applyRemoteIce()
        case .answer(let sdp, let fp):
            guard let pc else { return }
            if pc.remoteDescription != nil { await renegAnswer(sdp: sdp, fp: fp, meta: m, from: from); return }
            guard role == "caller" else { return }
            if let pd = peerDevice, from != pd { return }
            guard CallLogic.fingerprintsMatch(sdp: sdp, fp: fp) else { securityFail(); return }
            peerDevice = from; fps = CallLogic.sdpFingerprints(sdp)
            if let from, let dev = myDevice { pcID = m.pc ?? CallV3.pairPC(dev, from); adoptPeer(m, device: from) }
            _ = neg.remoteAnswer(n: m.n, haveLocalOffer: true)
            CallTones.shared.stop()
            phase = .connecting; status = String(localized: "Verbinde …")
            do {
                try await setRemote(RTCSessionDescription(type: .answer, sdp: sdp))
                findRemoteVideo(); applyRemoteIce()
                stateOut()
                connTask = Task { [weak self] in
                    try? await Task.sleep(for: .seconds(CallLogic.connectTimeout))
                    if let self, !self.connected, self.ready { self.iceFailed() }
                }
            } catch { fail(String(localized: "Verbindung nicht aufgebaut: \(error.localizedDescription)")) }
        case .offer(let sdp, _, let fp):
            await renegOffer(sdp: sdp, fp: fp, meta: m, from: from)       // erstes Angebot kommt ueber answer(), nicht hier
        case .state(let mi, let c, let sc):
            remote = CallRemoteState(mic: mi, cam: c, screen: sc); findRemoteVideo()
            let w = m.want ?? .large                                       // 2.3-Gegenueber: immer grosses Bild
            if w != remoteWant || m.path != remotePath { remoteWant = w; remotePath = m.path; applyLimits() }
            pipRefresh()
        case .bye:
            if let pd = peerDevice, from == pd { remoteEnded(reason: nil) }
        case .other: break                                                 // unbekannte t-Typen: ignorieren
        }
    }

    // MARK: Neuverhandlung (2.4, saga-call v3) - nur mit Gegenuebern, die "reneg" melden

    /// Faehigkeiten des Gegenuebers uebernehmen; die pc-Kennung legt der Anrufer fest (Angerufener uebernimmt sie);
    /// hoeflich = groessere device_id
    private func adoptPeer(_ m: CallV3.Meta, device: String) {
        peerCaps = m.caps ?? []                                            // fehlt (2.1/2.3): []
        if let mine = myDevice { neg.polite = CallV3.polite(me: mine, peer: device) }
    }
    /// SDP waehrend des Gespraechs: gleiches Geraet, Fingerabdruecke passen und sind dieselben wie beim Aufbau
    /// (DTLS-Zertifikat aendert sich bei einer Neuverhandlung nicht - sonst Abbruch wie bei jeder Pruefung)
    private func renegTrusted(sdp: String, fp: [String], from: String?) -> Bool? {
        guard let pd = peerDevice, from == pd else { return nil }
        guard CallLogic.fingerprintsMatch(sdp: sdp, fp: fp), Set(CallLogic.sdpFingerprints(sdp)) == Set(fps) else { return false }
        return true
    }
    private func renegOffer(sdp: String, fp: [String], meta: CallV3.Meta, from: String?) async {
        guard ready, canReneg, let pc, pc.remoteDescription != nil, !ending else { return }
        guard let ok = renegTrusted(sdp: sdp, fp: fp, from: from) else { return }
        guard ok else { securityFail(); return }
        switch neg.remoteOffer(n: meta.n, stable: pc.signalingState == .stable) {
        case .ignore, .stale:
            CallEnv.log("reneg: Angebot verworfen (Zusammenstoss/alt)")
            return
        case .accept(let rollback):
            do {
                if rollback { try await pc.setLocalAsync(RTCSessionDescription(type: .rollback, sdp: "")) }
                try await setRemote(RTCSessionDescription(type: .offer, sdp: sdp))
                applyRemoteIce()
                let ans = try await pc.answerAsync(CallRTC.constraints)
                try await pc.setLocalAsync(ans)
                guard let out = pc.localDescription?.sdp else { return }
                findRemoteVideo()
                CallEnv.log("reneg: Antwort n=\(meta.n ?? 0)")
                let (md, cp) = (media, myCaps)
                await sendSignal { d in CallV3.answerBody(sdp: out, media: md, pc: self.pcFor(d), n: meta.n ?? 0, caps: cp) }
            } catch { CallEnv.log("reneg offer: \(error.localizedDescription)") }
        }
    }
    private func renegAnswer(sdp: String, fp: [String], meta: CallV3.Meta, from: String?) async {
        guard canReneg, let pc else { return }
        guard let ok = renegTrusted(sdp: sdp, fp: fp, from: from) else { return }
        guard ok else { securityFail(); return }
        guard neg.remoteAnswer(n: meta.n, haveLocalOffer: pc.signalingState == .haveLocalOffer) == .apply else { return }
        do {
            try await setRemote(RTCSessionDescription(type: .answer, sdp: sdp))
            applyRemoteIce(); findRemoteVideo()
            CallEnv.log("reneg: fertig n=\(meta.n ?? 0)")
        } catch { CallEnv.log("reneg answer: \(error.localizedDescription)") }
    }
    /// Eigenes Angebot waehrend des Gespraechs (ICE-Restart). Zusammenstoss regelt Perfect Negotiation.
    private func renegotiate(iceRestart: Bool) async {
        guard ready, canReneg, let pc, pc.remoteDescription != nil, pc.signalingState == .stable, !neg.makingOffer, !ending else { return }
        let n = neg.beginOffer()
        do {
            let c = iceRestart ? RTCMediaConstraints(mandatoryConstraints: ["IceRestart": "true"], optionalConstraints: ["DtlsSrtpKeyAgreement": "true"])
                               : CallRTC.constraints
            let o = try await pc.offerAsync(c)
            guard neg.makingOffer, pc.signalingState == .stable, !ending else { neg.offerFailed(); return }   // inzwischen fremdes Angebot angenommen
            try await pc.setLocalAsync(o)
            neg.offerSent(n)
            guard let sdp = pc.localDescription?.sdp else { return }
            CallEnv.log("reneg: Angebot n=\(n) restart=\(iceRestart)")
            let (md, cp) = (media, myCaps)
            await sendSignal { d in CallV3.offerBody(sdp: sdp, media: md, pc: self.pcFor(d), n: n, caps: cp, restart: iceRestart) }
        } catch { neg.offerFailed(); CallEnv.log("reneg: \(error.localizedDescription)") }
    }

    // MARK: ICE-Restart (2.4): Verbindung weg bzw. Netzwechsel WLAN <-> Mobilfunk

    private func iceRestart() {
        restarts += 1; reconnecting = true
        status = String(localized: "Verbindung wird neu aufgebaut …")
        Task { await renegotiate(iceRestart: true) }
        restartTask?.cancel()
        restartTask = Task { [weak self] in                               // kommt die Verbindung nicht zurueck: wie bisher beenden
            try? await Task.sleep(for: .seconds(CallV3.restartTimeout))
            guard let self, !Task.isCancelled, self.reconnecting, !self.ending else { return }
            if CallV3.iceAction(state: "failed", everConnected: true, canReneg: self.canReneg, attempts: self.restarts) == .restart { self.iceRestart() }
            else { self.iceFailed() }
        }
    }
    private func netOn() {
        guard net == nil else { return }
        let m = NWPathMonitor()
        m.pathUpdateHandler = { [weak self] p in
            let prim: String? = p.usesInterfaceType(.wifi) ? "wifi" : p.usesInterfaceType(.cellular) ? "cellular"
                : p.usesInterfaceType(.wiredEthernet) ? "wired" : p.availableInterfaces.first.map { _ in "other" }
            let np = CallV3.NetPath(satisfied: p.status == .satisfied, primary: prim)
            Task { @MainActor [weak self] in self?.netChanged(np) }
        }
        m.start(queue: DispatchQueue(label: "saga.call.net"))
        net = m
    }
    private func netChanged(_ np: CallV3.NetPath) {
        let old = lastNet; lastNet = np
        guard connected, !ending, canReneg, CallV3.netChangeNeedsRestart(old: old, new: np) else { return }
        CallEnv.log("netz gewechselt -> ICE-Restart")
        restarts = 0
        iceRestart()
    }

    // MARK: Bitrate je Pfad (2.4, Tabelle §1.3) - nur bei Aenderung setParameters

    private func applyLimits() {
        guard let pc, let path = CallV3.worse(path, remotePath) else { return }   // schlechterer von eigenem/gemeldetem Weg
        let src = camera?.height ?? 720
        let v = CallV3.videoLimits(path: path, kind: .camera, streams: 1, want: remoteWant, sourceHeight: src)
        if v != appliedVideo, let s = pc.transceivers.first(where: { $0.mediaType == .video })?.sender {
            let p = s.parameters
            if !p.encodings.isEmpty {
                for e in p.encodings {
                    e.maxBitrateBps = NSNumber(value: v.maxBitrate)
                    e.scaleResolutionDownBy = NSNumber(value: v.scaleDown)
                    e.maxFramerate = NSNumber(value: v.maxFramerate)
                    e.isActive = v.active
                }
                p.degradationPreference = NSNumber(value: (v.maintainResolution ? RTCDegradationPreference.maintainResolution : .balanced).rawValue)
                s.parameters = p
                appliedVideo = v
                CallEnv.log("limits video \(path.rawValue) \(v.maxBitrate / 1000)k /\(v.scaleDown) want=\(remoteWant.rawValue)")
            }
        }
        let a = CallV3.audioBitrate(path: path)
        if a != appliedAudio, let s = pc.transceivers.first(where: { $0.mediaType == .audio })?.sender {
            let p = s.parameters
            if !p.encodings.isEmpty {
                for e in p.encodings { e.maxBitrateBps = NSNumber(value: a) }
                s.parameters = p
                appliedAudio = a
            }
        }
    }

    // MARK: Verbindung, Pruefung

    fileprivate func connectionChanged(_ st: RTCPeerConnectionState) {
        guard pc != nil, !ending else { return }
        switch st {
        case .connected:
            if !connected {
                connected = true; connectedAt = Date(); phase = .active; status = ""
                connTask?.cancel(); CallTones.shared.stop()
                CallSystem.shared.connected(self)
                CallEnv.log("connected")
                stateOut(); statsOn(); netOn()
            } else if reconnecting || !status.isEmpty {                      // 2.4: nach ICE-Restart wieder da
                reconnecting = false; restarts = 0; restartTask?.cancel(); status = ""
                appliedVideo = nil; appliedAudio = nil                      // Pfad kann sich geaendert haben
                CallEnv.log("reconnected")
            }
        case .disconnected:
            status = String(localized: "Verbindung unterbrochen – versuche es weiter …")
        case .failed:
            if CallV3.iceAction(state: "failed", everConnected: connected, canReneg: canReneg, attempts: restarts) == .restart {
                if !reconnecting { iceRestart() }
            } else { iceFailed() }
        default: break
        }
    }
    /// 2.4: ICE-Zustand - "disconnected" laenger als 2,5 s bzw. "failed" -> ICE-Restart (nur mit reneg auf beiden Seiten)
    fileprivate func iceChanged(_ st: RTCIceConnectionState) {
        guard pc != nil, !ending, !reconnecting else { return }
        switch st {
        case .disconnected:
            guard CallV3.iceAction(state: "disconnected", everConnected: connected, canReneg: canReneg, attempts: restarts) == .wait else { return }
            Task { [weak self] in
                try? await Task.sleep(for: .seconds(CallV3.restartDelay))
                guard let self, !self.ending, !self.reconnecting, self.pc?.iceConnectionState == .disconnected else { return }
                self.iceRestart()
            }
        case .failed:
            if CallV3.iceAction(state: "failed", everConnected: connected, canReneg: canReneg, attempts: restarts) == .restart { iceRestart() }
        default: break
        }
    }
    private func iceFailed() {
        fail(ShareJSON.bool(cfg["turn"]) ? String(localized: "Keine Verbindung möglich – auch nicht über den TURN-Server. Bitte später erneut versuchen.")
             : String(localized: "Direktverbindung nicht möglich (z. B. strenge Firewall oder Mobilfunknetz). Der Admin kann einen TURN-Server eintragen."), reason: "failed")
    }
    /// DTLS-Zertifikat des Gegenuebers (Statistik) muss in den SIGNIERTEN Fingerabdruecken stehen; Weg direkt/Relais
    private func statsOn() {
        statsTask?.cancel()
        statsTask = Task { [weak self] in
            var checked = false
            #if DEBUG
            var toggled = false
            #endif
            while !Task.isCancelled {
                guard let self, let pc = self.pc else { return }
                let rep = await pc.statsAsync()
                if !checked {
                    var byID: [String: RTCStatistics] = [:]
                    for (k, v) in rep.statistics { byID[k] = v }
                    let tr = rep.statistics.values.first { $0.type == "transport" }
                    if let cid = tr?.values["remoteCertificateId"] as? String, let cert = byID[cid],
                       let alg = cert.values["fingerprintAlgorithm"] as? String, let fp = cert.values["fingerprint"] as? String {
                        let c = CallLogic.certFingerprint(algorithm: alg, fingerprint: fp)
                        guard !self.fps.isEmpty, self.fps.contains(c) else { CallEnv.log("dtls-mismatch"); self.securityFail(); return }
                        self.verified = true; checked = true
                        CallEnv.log("verified dtls")
                    }
                }
                // Pfad bei jedem Takt (Netzwechsel/ICE-Restart waehlt ein neues Paar) -> Relais-Hinweis und Bitrate-Regeln
                do {
                    var byID: [String: RTCStatistics] = [:]
                    for (k, v) in rep.statistics { byID[k] = v }
                    let tr = rep.statistics.values.first { $0.type == "transport" }
                    if let pid = tr?.values["selectedCandidatePairId"] as? String, let pair = byID[pid] {
                        let l = (pair.values["localCandidateId"] as? String).flatMap { byID[$0] }, r = (pair.values["remoteCandidateId"] as? String).flatMap { byID[$0] }
                        let rel = (l?.values["candidateType"] as? String) == "relay" || (r?.values["candidateType"] as? String) == "relay"
                        if self.relay != rel { self.relay = rel }
                        let p = CallV3.path(local: l?.values as [String: Any]?, remote: r?.values as [String: Any]?)
                        if p != self.path { self.path = p; CallEnv.log("pfad \(p?.rawValue ?? "-")"); self.stateOut() }   // path an das Gegenueber
                        self.applyLimits()
                    }
                }
                let au = CallLogic.AudioSample.from(rep.statistics.values.map { (type: $0.type, values: $0.values as [String: Any]) })
                self.meter.update(au, micOn: self.mic)
                if self.audioIn != self.meter.input { self.audioIn = self.meter.input }
                if self.audioMic != self.meter.mic { self.audioMic = self.meter.mic }
                #if DEBUG
                if CallEnv.fakeMedia {
                    var v = 0, fr = 0
                    for s in rep.statistics.values where s.type == "inbound-rtp" && (s.values["kind"] as? String) == "video" {
                        v += (s.values["bytesReceived"] as? NSNumber)?.intValue ?? 0; fr += (s.values["framesDecoded"] as? NSNumber)?.intValue ?? 0
                    }
                    #if os(iOS)
                    let peak = FakeAudioDevice.shared.takePeak()
                    #else
                    let peak = 0.0
                    #endif
                    CallEnv.log("stats audioIn=\(au.bytesIn) samplesIn=\(au.samplesIn) levelIn=\(String(format: "%.4f", au.levelIn)) energyIn=\(String(format: "%.5f", au.energyIn)) audioOut=\(au.bytesOut) micLevel=\(String(format: "%.4f", au.levelMic)) ton=\(self.meter.input) mikro=\(self.meter.mic) videoIn=\(v) framesDecoded=\(fr) playoutPeak=\(String(format: "%.3f", peak)) verified=\(self.verified) relay=\(self.relay)")
                    let ud = UserDefaults.standard, el = self.connectedAt.map { Date().timeIntervalSince($0) } ?? 0
                    if !toggled, let t = Double(ud.string(forKey: "hkCallToggleAt") ?? ""), el > t {   // nur fuer Tests: Stumm + Kamera aus
                        toggled = true; self.toggleMic(); if self.cam { self.toggleCam() }; CallEnv.log("toggled mic=\(self.mic) cam=\(self.cam)")
                    }
                    if let h = Double(ud.string(forKey: "hkCallHangupAfter") ?? ""), el > h { self.hangup(); return }
                }
                #endif
                try? await Task.sleep(for: .seconds(2))
            }
        }
    }
    private func aliveOn() {
        aliveTask?.cancel()
        let s = aliveSeconds
        aliveTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(s))
                guard let self, let id = self.id, let store = self.store, !self.ending else { return }
                if let (d, c) = try? await store.apiResponse("api/calls/\(id)/alive", method: "POST", body: [:]), c == 200,
                   let j = ShareJSON.obj(d), (j["state"] as? String) == "ended" { self.remoteEnded(reason: j["reason"] as? String); return }
            }
        }
    }

    // MARK: Bedienung

    func toggleMic() {
        mic.toggle(); audioTrack?.isEnabled = mic
        CallSystem.shared.muted(self, !mic)
        stateOut()
    }
    /// von CallKit (Stummschalten in der System-Oberflaeche)
    func setMuted(_ m: Bool) { if mic == m { toggleMic() } }
    func toggleCam() {
        if let c = camera {
            c.stop(); camera = nil; localVideo = nil; cam = false
            videoSender?.track = nil
            stateOut(); return
        }
        Task {
            if !CallEnv.fakeMedia { guard await AVCaptureDevice.requestAccess(for: .video) else { toast = String(localized: "Kamera nicht erlaubt."); return } }
            guard busy, let c = CallCamera(factory: CallRTC.factory) else { toast = String(localized: "Keine Kamera verfügbar."); return }
            camera = c; localVideo = c.track; cam = true; frontCamera = c.front; wire(c)
            videoSender?.track = c.track
            appliedVideo = nil; applyLimits()
            stateOut()
        }
    }
    /// Kamera-Unterbrechung (PiP/Hintergrund ohne Multitasking-Freigabe) ans Gegenueber melden
    private func wire(_ c: CallCamera) {
        camPaused = false
        c.onInterrupt = { [weak self] on in self?.cameraInterrupted(on) }
    }
    #if os(iOS)
    func switchCamera() { camera?.switchCamera(); frontCamera = camera?.front ?? true }
    var canSwitchCamera: Bool { camera?.canSwitch ?? false }
    func toggleSpeaker() {
        speaker.toggle()
        CallAudio.route(speaker: speaker, video: media == "video")
    }
    #endif

    /// Auflegen (Knopf, CallKit)
    func hangup() {
        if phase == .incoming { decline(); return }
        guard busy || phase == .preparing else { return }
        if let id, !ending {
            let c = connected
            // 2.4: "bye" an Gegenueber, die "bye" melden (vor /end; 2.3-Apps bekommen es nicht)
            var bye: [String: String]?
            if ready && CallV3.canBye(peerCaps) {
                var b: [String: String] = [:]
                for d in targets { if let x = seal(d, CallV3.byeBody(pc: pcFor(d))) { b[d] = x } }
                bye = b
            }
            let dev = myDevice
            Task {
                if let bye, !bye.isEmpty, let dev { _ = try? await store?.apiResponse("api/calls/\(id)/signal", method: "POST", body: ["device": dev, "signals": bye]) }
                _ = try? await store?.apiResponse("api/calls/\(id)/end", method: "POST", body: ["reason": "hangup", "connected": c])
            }
        }
        finish(.ended(dur: connectedAt.map { Int(Date().timeIntervalSince($0)) }))
    }
    private func fail(_ msg: String, reason: String = "hangup") {
        guard phase != .idle, !ending else { return }
        if let id {
            let c = connected
            Task { _ = try? await store?.apiResponse("api/calls/\(id)/end", method: "POST", body: ["reason": reason == "failed" ? "failed" : "hangup", "connected": c]) }
        }
        CallEnv.log("failed: \(reason)")
        failed = true
        finish(nil, message: msg)
    }
    private func securityFail() {
        fail(String(localized: "Sicherheitsprüfung fehlgeschlagen: Die Signatur bzw. der Schlüssel der Gegenseite passt nicht. Der Anruf wurde abgebrochen – möglicherweise versucht jemand mitzuhören. Prüfe die Sicherheitsnummer im Chat."), reason: "failed")
    }
    private func remoteEnded(reason: String?) {
        guard phase != .idle, !ending else { return }
        CallEnv.log("remote ended \(reason ?? "-")")
        if reason == "elsewhere" { finish(.elsewhere); return }
        finish(CallLogic.endNote(reason: reason, role: role, connectedFor: connectedAt.map { Int(Date().timeIntervalSince($0)) }))
    }

    /// Alles abbauen; Hinweis kurz stehen lassen, dann zu
    private func finish(_ note: CallLogic.EndNote?, message: String? = nil) {
        guard !ending else { return }
        ending = true
        let cid = id ?? incoming?.id
        if let cid { done.insert(cid) }
        aliveTask?.cancel(); connTask?.cancel(); statsTask?.cancel(); outTask?.cancel(); restartTask?.cancel()
        net?.cancel(); net = nil
        #if os(iOS)
        CallPiP.shared.stop()
        #else
        CallPanel.shared.close()
        #endif
        CallTones.shared.stop()
        camera?.stop(); camera = nil
        pc?.close(); pc = nil
        audioTrack = nil; localVideo = nil; remoteVideo = nil
        #if os(iOS)
        CallAudio.releaseManual()
        #endif
        if let cid {
            let r: CallSystem.EndReason
            switch CallLogic.systemEnd(note, failed: failed) {
            case .failed: r = .failed
            case .answeredElsewhere: r = .answeredElsewhere
            case .remoteEnded: r = .remoteEnded
            }
            CallSystem.shared.endReported(cid, reason: r, local: true)
        }
        incoming = nil
        status = message ?? note.map(Self.text) ?? String(localized: "Anruf beendet")
        CallEnv.log("ended \(status.prefix(40))")
        if minimized { toast = status }                                     // verkleinert: kein Vollbild mehr, nur der Hinweis
        phase = .ended
        Task { [weak self] in
            try? await Task.sleep(for: .seconds(message == nil ? 2 : 6))
            guard let self, self.phase == .ended else { return }
            self.phase = .idle; self.status = ""; self.failed = false; self.minimized = false
        }
    }
    /// Fenster sofort schliessen (Knopf "Schliessen" nach Fehlermeldung)
    func dismissEnded() { if phase == .ended { phase = .idle; status = ""; failed = false; minimized = false } }

    static func text(_ n: CallLogic.EndNote) -> String {
        switch n {
        case .ended(let d): return d.map { String(localized: "Anruf beendet · \(CallLogic.duration($0))") } ?? String(localized: "Anruf beendet")
        case .declined: return String(localized: "Abgelehnt")
        case .busy: return String(localized: "Besetzt")
        case .missed: return String(localized: "Keine Antwort")
        case .unanswered: return String(localized: "Verpasster Anruf")
        case .cancelled: return String(localized: "Anruf abgebrochen")
        case .failed: return String(localized: "Anruf fehlgeschlagen")
        case .lost: return String(localized: "Verbindung verloren")
        case .gone: return String(localized: "Gerät entfernt – Anruf beendet")
        case .elsewhere: return String(localized: "Auf einem anderen Gerät angenommen")
        }
    }

    private func reset() {
        id = nil; chatID = 0; peer = ""; peerDevs = [:]; peerDevice = nil; fps = []; seen = []; early = []; remoteIce = []; outIce = []
        v2 = nil
        ready = false; connected = false; ending = false; answered = false; keysRefreshed = false; failed = false
        mic = true; cam = false; remote = CallRemoteState(); verified = false; relay = false; connectedAt = nil; speaker = false
        meter = CallLogic.AudioMeter(); audioIn = .unknown; audioMic = .unknown
        localVideo = nil; remoteVideo = nil; incoming = nil; minimized = false
        pcID = nil; remotePath = nil; oldUfrags = []; peerCaps = nil; neg = CallV3.Negotiator(); remoteWant = .large; wantOut = PiPLogic.WantSender()
        appliedVideo = nil; appliedAudio = nil; restarts = 0; restartTask?.cancel(); reconnecting = false; path = nil
        net?.cancel(); net = nil; lastNet = nil; camPaused = false; chain = nil; pip = false
    }
    func signedOut() { if busy { hangup() }; enabled = true; done = [] }
}

// MARK: - WebRTC-Delegat (Rueckrufe kommen auf dem WebRTC-Signalisierungs-Thread)

extension CallManager: RTCPeerConnectionDelegate {
    nonisolated func peerConnection(_ pc: RTCPeerConnection, didChange stateChanged: RTCSignalingState) {}
    nonisolated func peerConnection(_ pc: RTCPeerConnection, didAdd stream: RTCMediaStream) {}
    nonisolated func peerConnection(_ pc: RTCPeerConnection, didRemove stream: RTCMediaStream) {}
    nonisolated func peerConnectionShouldNegotiate(_ pc: RTCPeerConnection) {}
    nonisolated func peerConnection(_ pc: RTCPeerConnection, didChange newState: RTCIceConnectionState) {
        Task { @MainActor [weak self] in self?.iceChanged(newState) }
    }
    nonisolated func peerConnection(_ pc: RTCPeerConnection, didChange newState: RTCIceGatheringState) {}
    nonisolated func peerConnection(_ pc: RTCPeerConnection, didRemove candidates: [RTCIceCandidate]) {}
    nonisolated func peerConnection(_ pc: RTCPeerConnection, didOpen dataChannel: RTCDataChannel) {}
    nonisolated func peerConnection(_ pc: RTCPeerConnection, didGenerate candidate: RTCIceCandidate) {
        guard !candidate.sdp.isEmpty else { return }
        var c = CallLogic.Candidate(candidate: candidate.sdp, sdpMid: candidate.sdpMid, sdpMLineIndex: candidate.sdpMLineIndex)
        c.usernameFragment = CallV3.candidateUfrag(c)                      // 2.4: usernameFragment mitsenden
        Task { @MainActor [weak self] in self?.localCandidate(c) }
    }
    nonisolated func peerConnection(_ pc: RTCPeerConnection, didChange newState: RTCPeerConnectionState) {
        Task { @MainActor [weak self] in self?.connectionChanged(newState) }
    }
    nonisolated func peerConnection(_ pc: RTCPeerConnection, didStartReceivingOn transceiver: RTCRtpTransceiver) {
        Task { @MainActor [weak self] in self?.findRemoteVideo() }
    }
}

// MARK: - async-Huellen

extension RTCPeerConnection {
    func offerAsync(_ c: RTCMediaConstraints) async throws -> RTCSessionDescription {
        try await withCheckedThrowingContinuation { k in
            offer(for: c) { sdp, e in if let sdp { k.resume(returning: sdp) } else { k.resume(throwing: e ?? APIError.other("offer")) } }
        }
    }
    func answerAsync(_ c: RTCMediaConstraints) async throws -> RTCSessionDescription {
        try await withCheckedThrowingContinuation { k in
            answer(for: c) { sdp, e in if let sdp { k.resume(returning: sdp) } else { k.resume(throwing: e ?? APIError.other("answer")) } }
        }
    }
    func setLocalAsync(_ d: RTCSessionDescription) async throws {
        try await withCheckedThrowingContinuation { (k: CheckedContinuation<Void, Error>) in
            setLocalDescription(d) { e in if let e { k.resume(throwing: e) } else { k.resume() } }
        }
    }
    func setRemoteAsync(_ d: RTCSessionDescription) async throws {
        try await withCheckedThrowingContinuation { (k: CheckedContinuation<Void, Error>) in
            setRemoteDescription(d) { e in if let e { k.resume(throwing: e) } else { k.resume() } }
        }
    }
    func statsAsync() async -> RTCStatisticsReport {
        await withCheckedContinuation { k in statistics { k.resume(returning: $0) } }
    }
}
