import SwiftUI
import AVFoundation
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
    @Published private(set) var phase: Phase = .idle
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
    @Published private(set) var remoteVideo: RTCVideoTrack?
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
    /// Anrufe, die schon beendet/abgelehnt wurden (spaete call.ring/Push nicht erneut klingeln lassen)
    private var done = Set<String>()

    var busy: Bool { phase != .idle && phase != .ended }
    var callID: String? { id }
    private var me: String { store?.chat.me.uid ?? "" }
    private var myDevice: String? { store?.chat.keys?.deviceID }

    // MARK: Einstellungen

    @discardableResult
    func loadConfig() async -> Bool {
        guard let store, let (d, code) = try? await store.apiResponse("api/calls/config") else { return false }
        if code == 404 || code == 405 { enabled = false; return false }
        guard code == 200, let j = ShareJSON.obj(d) else { return false }
        cfg = j
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
        return ChatCrypto.sealSignal(callID: id, fromUID: me, fromDevice: k.deviceID, signSK: k.sign.sk, toUID: peer, toDevice: to, toPK: pd.enc, body: body)
    }
    private func sealAll(_ devs: [String], _ body: String) -> [String: String] {
        var out: [String: String] = [:]
        for d in devs { if let s = seal(d, body) { out[d] = s } }
        return out
    }
    /// Oeffnen + pruefen -> Inhalt; nil = Pruefung fehlgeschlagen. Unbekanntes Geraet: Schluessel einmal neu holen.
    private func open(_ s: [String: Any]) async -> CallLogic.Signal? {
        guard let id, let k = store?.chat.keys, let from = s["from_device"] as? String, let sealed = s["sealed"] as? String else { return nil }
        if peerDevs[from] == nil && !keysRefreshed { keysRefreshed = true; try? await loadPeerKeys(peer, name: peerName) }
        guard let pd = peerDevs[from], let o = ChatCrypto.openSignal(sealed, pk: k.enc.pk, sk: k.enc.sk),
              ChatCrypto.verifySignal(callID: id, fromUID: peer, fromDevice: from, toUID: me, toDevice: k.deviceID, body: o.body, sig: o.sig, signPK: pd.sign)
        else { return nil }
        return CallLogic.parse(o.body)
    }
    private var targets: [String] { peerDevice.map { [$0] } ?? Array(peerDevs.keys) }
    private func sendSignal(_ body: String) async {
        guard let store, let id, let dev = myDevice, !ending else { return }
        let sig = sealAll(targets, body)
        guard !sig.isEmpty, let (_, code) = try? await store.apiResponse("api/calls/\(id)/signal", method: "POST", body: ["device": dev, "signals": sig]) else { return }
        if code == 409 { remoteEnded(reason: "gone") }
    }
    private func stateOut() {
        guard ready else { return }
        let b = CallLogic.stateBody(mic: mic, cam: cam, screen: false)
        Task { await sendSignal(b) }
    }

    // MARK: Anrufen

    func start(chatID cid: Int, peer p: ChatMember, video: Bool) {
        if busy { toast = String(localized: "Du telefonierst gerade schon."); return }
        reset()
        chatID = cid; peer = p.username; peerName = p.name; peerAvatar = p.avatar; role = "caller"; media = video ? "video" : "audio"
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
        let body = CallLogic.offerBody(sdp: sdp, media: media)
        let (d, code) = try await store.apiResponse("api/calls", method: "POST", body: ["id": id, "chat_id": chatID, "media": media, "device": dev, "signals": sealAll(Array(peerDevs.keys), body)])
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
        if code == 409, j?["devices"] != nil, !again { try await loadPeerKeys(peer, name: peerName); try await dial(again: true); return }
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
        let inc = IncomingCall(id: cid, chatID: ShareJSON.int(c["chat_id"]) ?? 0, media: (c["media"] as? String) == "video" ? "video" : "audio",
                               name: ShareJSON.str(p["name"]) ?? hint?.caller ?? "", username: p["username"] as? String ?? "", avatar: ShareJSON.str(p["avatar"]))
        reset()
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
                var offer: (sdp: String, fp: [String])?
                for s in j["signals"] as? [[String: Any]] ?? [] {
                    if let q = ShareJSON.int(s["seq"]) { _ = CallLogic.fresh(q, seen: &seen) }
                    guard let sg = await open(s) else { securityFail(); return }
                    guard (s["from_device"] as? String) == callerDev else { continue }
                    switch sg {
                    case .offer(let sdp, _, let fp): if offer == nil { offer = (sdp, fp) }
                    case .ice(let c): remoteIce += c
                    default: break
                    }
                }
                guard let offer, CallLogic.fingerprintsMatch(sdp: offer.sdp, fp: offer.fp) else { securityFail(); return }
                peerDevice = callerDev; fps = CallLogic.sdpFingerprints(offer.sdp)
                try await prepareMedia(video: video)
                guard phase == .connecting else { return }
                makePC()
                guard let pc else { return }
                try await pc.setRemoteAsync(RTCSessionDescription(type: .offer, sdp: offer.sdp))
                addLocal(fromOffer: true)
                findRemoteVideo()
                let ans = try await pc.answerAsync(CallRTC.constraints)
                try await pc.setLocalAsync(ans)
                guard let sdp = pc.localDescription?.sdp, let sealed = seal(callerDev, CallLogic.answerBody(sdp: sdp)) else { throw APIError.other("–") }
                let (ad, ac) = try await store.apiResponse("api/calls/\(inc.id)/answer", method: "POST", body: ["device": dev, "signals": [callerDev: sealed]])
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
            if allowed, let c = CallCamera(factory: f) { camera = c; localVideo = c.track; cam = true }
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
            let b = CallLogic.iceBody(list)
            Task { await sendSignal(b) }
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
    private func applyRemoteIce() {
        guard let pc, pc.remoteDescription != nil else { return }
        let list = remoteIce; remoteIce = []
        for c in list { pc.add(RTCIceCandidate(sdp: c.candidate, sdpMLineIndex: c.sdpMLineIndex, sdpMid: c.sdpMid)) { _ in } }
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
            Task { await handleRaw(d) }
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
        guard let sg = await open(s) else { securityFail(); return }
        switch sg {
        case .ice(let c):
            remoteIce += c; applyRemoteIce()
        case .answer(let sdp, let fp):
            guard role == "caller", let pc, pc.remoteDescription == nil else { return }
            if let pd = peerDevice, from != pd { return }
            guard CallLogic.fingerprintsMatch(sdp: sdp, fp: fp) else { securityFail(); return }
            peerDevice = from; fps = CallLogic.sdpFingerprints(sdp)
            CallTones.shared.stop()
            phase = .connecting; status = String(localized: "Verbinde …")
            do {
                try await pc.setRemoteAsync(RTCSessionDescription(type: .answer, sdp: sdp))
                findRemoteVideo(); applyRemoteIce()
                stateOut()
                connTask = Task { [weak self] in
                    try? await Task.sleep(for: .seconds(CallLogic.connectTimeout))
                    if let self, !self.connected, self.ready { self.iceFailed() }
                }
            } catch { fail(String(localized: "Verbindung nicht aufgebaut: \(error.localizedDescription)")) }
        case .state(let m, let c, let sc):
            remote = CallRemoteState(mic: m, cam: c, screen: sc); findRemoteVideo()
        case .offer, .other: break
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
                stateOut(); statsOn()
            }
        case .disconnected: status = String(localized: "Verbindung unterbrochen – versuche es weiter …")
        case .failed: iceFailed()
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
                    if let pid = tr?.values["selectedCandidatePairId"] as? String, let pair = byID[pid] {
                        let l = (pair.values["localCandidateId"] as? String).flatMap { byID[$0] }, r = (pair.values["remoteCandidateId"] as? String).flatMap { byID[$0] }
                        self.relay = (l?.values["candidateType"] as? String) == "relay" || (r?.values["candidateType"] as? String) == "relay"
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
            camera = c; localVideo = c.track; cam = true
            videoSender?.track = c.track
            stateOut()
        }
    }
    #if os(iOS)
    func switchCamera() { camera?.switchCamera() }
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
            Task { _ = try? await store?.apiResponse("api/calls/\(id)/end", method: "POST", body: ["reason": "hangup", "connected": c]) }
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
        aliveTask?.cancel(); connTask?.cancel(); statsTask?.cancel(); outTask?.cancel()
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
        phase = .ended
        Task { [weak self] in
            try? await Task.sleep(for: .seconds(message == nil ? 2 : 6))
            guard let self, self.phase == .ended else { return }
            self.phase = .idle; self.status = ""; self.failed = false
        }
    }
    /// Fenster sofort schliessen (Knopf "Schliessen" nach Fehlermeldung)
    func dismissEnded() { if phase == .ended { phase = .idle; status = ""; failed = false } }

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
        ready = false; connected = false; ending = false; answered = false; keysRefreshed = false; failed = false
        mic = true; cam = false; remote = CallRemoteState(); verified = false; relay = false; connectedAt = nil; speaker = false
        meter = CallLogic.AudioMeter(); audioIn = .unknown; audioMic = .unknown
        localVideo = nil; remoteVideo = nil; incoming = nil
    }
    func signedOut() { if busy { hangup() }; enabled = true; done = [] }
}

// MARK: - WebRTC-Delegat (Rueckrufe kommen auf dem WebRTC-Signalisierungs-Thread)

extension CallManager: RTCPeerConnectionDelegate {
    nonisolated func peerConnection(_ pc: RTCPeerConnection, didChange stateChanged: RTCSignalingState) {}
    nonisolated func peerConnection(_ pc: RTCPeerConnection, didAdd stream: RTCMediaStream) {}
    nonisolated func peerConnection(_ pc: RTCPeerConnection, didRemove stream: RTCMediaStream) {}
    nonisolated func peerConnectionShouldNegotiate(_ pc: RTCPeerConnection) {}
    nonisolated func peerConnection(_ pc: RTCPeerConnection, didChange newState: RTCIceConnectionState) {}
    nonisolated func peerConnection(_ pc: RTCPeerConnection, didChange newState: RTCIceGatheringState) {}
    nonisolated func peerConnection(_ pc: RTCPeerConnection, didRemove candidates: [RTCIceCandidate]) {}
    nonisolated func peerConnection(_ pc: RTCPeerConnection, didOpen dataChannel: RTCDataChannel) {}
    nonisolated func peerConnection(_ pc: RTCPeerConnection, didGenerate candidate: RTCIceCandidate) {
        guard !candidate.sdp.isEmpty else { return }
        let c = CallLogic.Candidate(candidate: candidate.sdp, sdpMid: candidate.sdpMid, sdpMLineIndex: candidate.sdpMLineIndex)
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
