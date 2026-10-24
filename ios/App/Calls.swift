import SwiftUI
import AVFoundation
import Network
import WebRTC

// Anrufe (Saga 2.1): 1:1 aus dem Direkt-Chat, WebRTC Geraet <-> Geraet. Signalisierung ueber /api/calls + Live-Kanal
// (call.ring, call.signal, call.state); jede Signal-Nachricht signiert + versiegelt (CallCrypto.swift, saga-call/v1).
// Pruefung beim Empfang: Geraet des Gegenuebers (Zertifikat + TOFU wie im Chat), Signatur mit MIR als Ziel,
// fp == Fingerabdruecke der SDP, nach dem Verbinden DTLS-Zertifikat des Gegenuebers in fp. Ablauf wie static/calls.js.
// Medien: CallMedia.swift; CallKit/PushKit bzw. Klingeln am Mac: CallSystem.swift; Oberflaeche: CallViews.swift.
// 2.4 E4/E5: ein Anruf ist ein Raum - je anderem Geraet eine Verbindung (CallLink.swift, Mesh); weitere eigene Geraete
// (Begleitgeraet, "Hierher uebernehmen") und Gruppenanrufe: CallRoom.swift, Logik CallRoomLogic.swift.

struct CallPeerDevice { var enc: Data; var sign: Data }
struct CallRemoteState: Equatable {
    var mic = true; var cam = false; var screen = false
    /// 2.4 E3: mid, auf der der Bildschirm des Gegenuebers laeuft (nil = 2.1/2.3/E0: die einzige Videospur), Ton gemischt
    var screenMid: String? = nil; var screenAudio = false
}

/// Klingelnder Anruf (Anzeige ohne CallKit bzw. vor dem Annehmen)
struct IncomingCall: Identifiable, Equatable {
    var id: String; var chatID: Int; var media: String; var name: String; var username: String; var avatar: String?
    /// 2.4 E5: Gruppenanruf (Annehmen = beitreten), Name des Chats
    var group = false; var chatName: String? = nil
}

/// 2.4 E4/E5: Kachel je sichtbarer Verbindung (Kachelansicht)
struct CallTile: Identifiable, Equatable {
    var id: String
    var name: String
    var avatar: String?
    var devName: String
    var companion: Bool
    var own: Bool
    var mic: Bool
    var video: RTCVideoTrack?
    var side: RTCVideoTrack?
    var screen: Bool
    var connected: Bool
    var speaking: Bool
    static func == (a: CallTile, b: CallTile) -> Bool {
        a.id == b.id && a.name == b.name && a.avatar == b.avatar && a.devName == b.devName && a.companion == b.companion && a.own == b.own
            && a.mic == b.mic && a.video === b.video && a.side === b.side && a.screen == b.screen && a.connected == b.connected && a.speaking == b.speaking
    }
}

@MainActor
final class CallManager: NSObject, ObservableObject {
    weak var store: Store?
    enum Phase: String, Equatable { case idle, preparing, ringingOut, incoming, connecting, active, ended }
    @Published var phase: Phase = .idle { didSet { if phase != oldValue { pipRefresh(); if phase != .idle && phase != .ended { VoicePlayer.shared.pauseForCall() } } } }   // 2.5: Sprachnachricht pausiert beim Anruf
    @Published var status = ""
    @Published var peerName = ""
    @Published var peerAvatar: String?
    @Published var media = "audio"
    @Published var role = "caller"
    @Published var mic = true
    @Published var cam = false
    @Published var remote = CallRemoteState()
    @Published var verified = false
    @Published var relay = false
    @Published var connectedAt: Date?
    @Published var localVideo: RTCVideoTrack?
    /// Eigenes Bild nur bei der Frontkamera spiegeln (Rueckkamera: Schrift lesbar)
    @Published var frontCamera = true
    @Published var remoteVideo: RTCVideoTrack? { didSet { if remoteVideo !== oldValue { pipRefresh() } } }
    @Published var incoming: IncomingCall?
    @Published var failed = false
    @Published var speaker = false
    /// Ton-Diagnose (getStats, alle 2 s): kommt Ton an, liefert das Mikrofon etwas - fuer den Handtest im Anruffenster
    @Published var audioIn = CallLogic.AudioIn.unknown
    @Published var audioMic = CallLogic.AudioMic.unknown
    var meter = CallLogic.AudioMeter()
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
    @Published var path: CallV3.Path?
    /// 2.4: Verbindung wird per ICE-Restart neu aufgebaut
    @Published var reconnecting = false
    // 2.4 E3: Bildschirm teilen (ScreenShareLogic.swift, ScreenCapture.swift)
    /// Ich teile gerade meinen Bildschirm
    @Published var sharing = false
    /// Kamera des Gegenuebers, waehrend es den Bildschirm teilt (klein; remoteVideo ist dann der Bildschirm)
    @Published var remoteSide: RTCVideoTrack?
    /// Ansicht getauscht (Kamera gross, Bildschirm klein); neues Teilen beginnt wieder mit dem Bildschirm gross
    @Published var swapView = false
    /// Kamera beim Teilen pausiert (Relais TCP, Betriebsart B)
    @Published var shareCamPaused = false
    /// Hinweis vor dem ersten Teilen sichtbar
    @Published var screenHint = false
    let capture = ScreenCapture()
    // 2.4 E4/E5: Raum (CallRoom.swift)
    /// "dm" (1:1, auch mit Begleitgeraeten) bzw. "group"
    @Published var kind = "dm"
    /// 2.5: 1:1 ueber einen Knoten (Raum-Anruf mit dm: true) - Anzeige wie 1:1, nicht als Gruppenanruf
    @Published var dmRoom = false
    /// 2.5: vom Gastgeber stummgeschaltet (Name) - Hinweis bleibt, bis man das Mikrofon selbst einschaltet
    @Published var mutedBy: String?
    /// dieses Geraet ist Begleitgeraet (ohne Mikrofon und Ton gestartet)
    @Published var companion = false
    /// Wiedergabe an (Begleitgeraet: aus, Echo-Schutz)
    @Published var sound = true
    /// Kachelansicht (Gruppe bzw. mehr als eine sichtbare Verbindung) und ihre Kacheln
    @Published var meshView = false
    @Published var tiles: [CallTile] = []
    /// Geraet, das gerade spricht (Pegel der empfangenen Tonspur)
    @Published var speakerDev: String? { didSet { if speakerDev != oldValue { pipRefresh() } } }
    /// "Hierher uebernehmen" laeuft
    @Published var handingOver = false
    /// Geraete je Anruf (Mesh-Grenze)
    @Published var limit = CallRoom.videoMax
    /// Personen ohne Saga 2.4 (Gruppe: sehen nur den Chat-Eintrag)
    @Published var oldNames: [String] = []
    /// Leiste "Anruf laeuft" (GET /api/calls/active): andere eigene Geraete bzw. Gruppenanrufe in eigenen Chats
    @Published var runBars: [CallRoom.Running] = []
    /// dieses Geraet ist (per join) im Raum
    @Published var inRoom = false
    /// 2.5 Gjallarhorn: Gruppenanruf ueber einen Knoten (topo=horn, HornCall.swift); nil = Mesh bzw. 1:1
    @Published var horn: HornSession?
    /// Aufbau des Anrufs laut Server ("mesh" | "horn"; leer = aelterer Server)
    var topo = ""
    /// 2.5 P10: Hinweis horn_mesh (Gruppenanruf ueber Servergrenzen ohne Knoten) schon gezeigt
    var meshNoted = false
    /// 2.5: dieses Konto hat den Gruppenanruf begonnen (Gastgeber: stummschalten, entfernen)
    var callHost = false

    // Zustand des laufenden Anrufs
    var id: String?
    var chatID = 0
    /// 1:1: Person des Gegenuebers (Benutzername bzw. Handle)
    var peer = ""
    /// Verbindungen (je anderem Geraet eine)
    var links: [CallLink] = []
    var seen = Set<Int>()
    var early: [[String: Any]] = []
    var ready = false
    /// irgendeine Verbindung stand schon (Dauer, Phase "aktiv")
    var connected = false
    var ending = false
    var answered = false
    var audioTrack: RTCAudioTrack?
    var camera: CallCamera?
    var aliveTask: Task<Void, Never>?
    var statsTask: Task<Void, Never>?
    var cfg: [String: Any] = [:]
    /// Schluessel je Person schon einmal frisch geholt (unbekanntes Geraet)
    var keysRefreshed = Set<String>()
    /// 2.3: Anruf ueber Servergrenzen (saga-call/v2): Handles statt Benutzernamen (1:1: eigener, Gegenueber)
    var v2: (me: String, peer: String)?
    /// 2.4: Raum ueber Servergrenzen (call.v == 2) - eigener Handle
    var roomHandle: String?
    /// Anrufe, die schon beendet/abgelehnt wurden (spaete call.ring/Push nicht erneut klingeln lassen)
    var done = Set<String>()
    /// Server-Features aus /api/calls/config (2.4: "reneg", "pip_hint", "screen2", "multi"; aeltere Server: keine)
    var serverFeatures: [String] = []
    var wantOut = PiPLogic.WantSender()
    var net: NWPathMonitor?
    var lastNet: CallV3.NetPath?
    /// eigene Kamera vom System pausiert (PiP/Hintergrund ohne Kamera-Multitasking) -> state.cam = false
    var camPaused = false
    /// Signale nacheinander verarbeiten (Neuverhandlung darf sich nicht mit ICE/Antworten ueberholen)
    var chain: Task<Void, Never>?
    // 2.4 E3: Bildschirm - Quelle/Spur und eigener Stream (gilt fuer alle Verbindungen)
    var scrSource: RTCVideoSource?
    var scrTrack: RTCVideoTrack?
    var scrSid = ""
    // 2.4 E4/E5: Raum
    var roster = CallRoom.Roster()
    var ho = CallRoom.Handover()
    var hoTask: Task<Void, Never>?
    /// mit welcher Rolle dieses Geraet beigetreten ist ("" = angerufen/angenommen wie bisher)
    var joinRole = ""
    var title = ""
    /// Signale von Geraeten ohne Verbindung (bis Liste und Schluessel geprueft sind)
    var pend: [String: [[String: Any]]] = [:]
    var admitting = Set<String>()
    var badDevs = Set<String>()
    var keysBy: [String: [String: CallPeerDevice]] = [:]
    var speakerState = CallRoom.Speaker()
    var capsSent = ""
    var runTask: Task<Void, Never>?

    var myCaps: [String] { CallV3.myCaps(serverFeatures: serverFeatures) }
    var hasScreen2: Bool { myCaps.contains("screen2") }
    var multi: Bool { myCaps.contains("multi") }
    /// Caps-Hinweis im JSON von /api/calls bzw. /answer nur an Server mit saga-call v3 (aeltere kennen das Feld nicht)
    var capsHint: [String: Any] { serverFeatures.isEmpty ? [:] : ["caps": myCaps] }
    /// Raum-Verhalten: Fehler/bye trennen nur die eine Verbindung
    var roomMode: Bool { kind == "group" || links.count > 1 || !joinRole.isEmpty }
    /// erste sichtbare Verbindung (Einzelansicht, Ton-Diagnose)
    var primary: CallLink? { links.first { !quiet($0) } ?? links.first }

    var busy: Bool { phase != .idle && phase != .ended }
    var callID: String? { id }
    /// Chat des laufenden Anrufs (0 = keiner) - fuer den Hinweis in der Unterhaltung
    var callChatID: Int { chatID }
    var me: String { store?.chat.me.uid ?? "" }
    var myDevice: String? { store?.chat.keys?.deviceID }

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

    /// Was PiP bzw. Schwebefenster zeigen: im Gruppenanruf (Kachelansicht) den aktiven Sprecher, sonst das Gegenueber
    struct Focus { var track: RTCVideoTrack?; var name: String; var avatar: String?; var video: Bool; var screen: Bool }
    var focus: Focus {
        if let H = horn {                                                    // 2.5: Knoten - Bildschirm bzw. Sprecher
            return Focus(track: H.focusTrack, name: H.focusName, avatar: nil, video: H.focusTrack != nil, screen: H.share != nil)
        }
        if meshView, let L = links.first(where: { $0.dev != nil && $0.dev == speakerDev && !$0.gone })
            ?? links.first(where: { !quiet($0) && $0.remoteVideo != nil && ($0.remote.cam || $0.remote.screen) }) ?? primary {
            return Focus(track: L.remoteVideo, name: L.name, avatar: L.avatar, video: L.remoteVideo != nil && (L.remote.cam || L.remote.screen), screen: L.remote.screen)
        }
        return Focus(track: remoteVideo, name: peerName, avatar: peerAvatar, video: remoteVideo != nil && (remote.cam || remote.screen), screen: remote.screen)
    }
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

    // MARK: Einstellungen

    @discardableResult
    func loadConfig() async -> Bool {
        guard let store, let (d, code) = try? await store.apiResponse("api/calls/config") else { return false }
        if code == 404 || code == 405 { enabled = false; return false }
        guard code == 200, let j = ShareJSON.obj(d) else { return false }
        cfg = j
        let f = (j["features"] as? [Any] ?? []).compactMap { $0 as? String }
        if f != serverFeatures { serverFeatures = f; store.objectWillChange.send() }   // Chat-Ansicht: Gruppenanruf-Knoepfe neu zeigen
        let on = ShareJSON.bool(j["enabled"])
        if on != enabled { enabled = on }
        return on
    }
    var aliveSeconds: Double { Double(ShareJSON.int(cfg["alive_s"]) ?? 15) }
    var handoverSeconds: Double { Double(ShareJSON.int((cfg["limits"] as? [String: Any])?["handover_s"]) ?? Int(CallRoom.handoverSeconds)) }

    /// Chat-Schluessel dieses Geraets bereit? (Anrufe brauchen das Chat-Geraet)
    func ensureChat() async -> Bool {
        guard let store else { return false }
        if store.chat.keys == nil || store.chat.me.uid.isEmpty { await store.chat.load() }
        return store.chat.keys != nil
    }

    // MARK: Schluessel einer Person (Zertifikat + TOFU wie im Chat)

    /// Geprueft: Geraete-Zertifikate vom Identitaetsschluessel der Person. fedHandle = Person auf einem anderen Server
    /// (saga-call/v2: geprueft wie im Chat, signierte Geraete-Liste, TOFU v2). Eigene Person: gegen den eigenen Schluessel.
    func personKeys(user: String, name: String, fedHandle: String?, own ownHandle: String?, fresh: Bool) async throws -> [String: CallPeerDevice] {
        let ck = (fedHandle.map { "h:" + $0 } ?? "u:" + user)
        if !fresh, let k = keysBy[ck] { return k }
        let out: [String: CallPeerDevice]
        if let h = fedHandle {
            guard let st = try await store?.chat.fedKeys([h], fresh: fresh, own: ownHandle)[h], let k = st.keys else {
                throw APIError.other(String(localized: "\(name) hat Chats noch auf keinem Gerät eingerichtet – Anrufe gehen erst danach."))
            }
            guard st.verdict == .trust else {
                throw APIError.other(String(localized: "Die Sicherheitsnummer von \(name) hat sich geändert – erst im Chat prüfen oder „Verstanden“ wählen."))
            }
            out = Dictionary(k.active.map { ($0.id, CallPeerDevice(enc: $0.enc, sign: $0.sign)) }, uniquingKeysWith: { a, _ in a })
        } else {
            guard let store, let enc = user.addingPercentEncoding(withAllowedCharacters: .alphanumerics) else { return [:] }
            let d = try await store.api("api/chat/keys?users=\(enc)")
            guard let u = PersonRef.keyEntry(ShareJSON.obj(d), for: user), let ipk = ChatCrypto.unb64(u["ident_pub"] as? String, 32) else {
                throw APIError.other(String(localized: "\(name) hat Chats noch auf keinem Gerät eingerichtet – Anrufe gehen erst danach."))
            }
            if user == me {                                                 // eigene Geraete: gegen den eigenen Identitaetsschluessel
                guard let mine = store.chat.keys, mine.identPK == ipk else { throw APIError.other(String(localized: "Sicherheitsprüfung fehlgeschlagen")) }
            } else {
                let t = ChatTrustStore.observe(me, user, pk: ChatCrypto.b64(ipk))
                if t == .changed || ChatTrustStore.known(me, user)?.changed == true {
                    throw APIError.other(String(localized: "Die Sicherheitsnummer von \(name) hat sich geändert – erst im Chat prüfen oder „Verstanden“ wählen."))
                }
            }
            var m: [String: CallPeerDevice] = [:]
            for case let dv as [String: Any] in (u["devices"] as? [Any] ?? []) {
                guard !ShareJSON.bool(dv["revoked"]), let did = dv["id"] as? String, let e = ChatCrypto.unb64(dv["enc_pub"] as? String, 32),
                      let s = ChatCrypto.unb64(dv["sign_pub"] as? String, 32),
                      ChatCrypto.verify(ChatCrypto.unb64(dv["sig"] as? String), ChatCrypto.deviceMsg(uid: user, deviceID: did, encPK: e, signPK: s), pk: ipk) else { continue }
                m[did] = CallPeerDevice(enc: e, sign: s)
            }
            out = m
        }
        keysBy[ck] = out
        return out
    }
    /// 1:1: Schluessel des Gegenuebers in die (erste) Verbindung
    func loadPeerKeys(_ L: CallLink, fresh: Bool = false) async throws {
        L.devs = try await personKeys(user: L.who, name: L.name, fedHandle: L.v2?.peer, own: L.v2?.me, fresh: fresh)
    }
    /// Schluessel der Person einer Verbindung (Raum): eigene/lokale Personen lokal, Personen anderer Server ueber v2
    func linkKeys(_ L: CallLink, host: String?, fresh: Bool = false) async throws {
        let foreign = L.v2 != nil && host != nil && !L.own
        L.devs = try await personKeys(user: L.who, name: L.name, fedHandle: foreign ? L.v2?.peer : nil, own: L.v2?.me, fresh: fresh)
    }

    // MARK: Versiegeln / Oeffnen

    func seal(_ L: CallLink, _ to: String, _ body: String) -> String? {
        guard let id, let k = store?.chat.keys, let pd = L.devs[to] else { return nil }
        if let v = L.v2 {
            return ChatCrypto.sealSignalV2(callID: id, fromHandle: v.me, fromDevice: k.deviceID, signSK: k.sign.sk, toHandle: v.peer, toDevice: to, toPK: pd.enc, body: body)
        }
        return ChatCrypto.sealSignal(callID: id, fromUID: me, fromDevice: k.deviceID, signSK: k.sign.sk, toUID: L.who, toDevice: to, toPK: pd.enc, body: body)
    }
    /// Oeffnen + pruefen -> Inhalt (+ 2.4-Zusatzfelder); nil = Pruefung fehlgeschlagen. Unbekanntes Geraet: Schluessel einmal neu holen.
    func open(_ L: CallLink, _ s: [String: Any]) async -> (sig: CallLogic.Signal, meta: CallV3.Meta)? {
        guard let body = await openBody(L, s) else { return nil }
        return CallLogic.parse(body).map { ($0, CallV3.meta(body)) }
    }
    /// Oeffnen + pruefen -> Klartext des Signals (2.5: auch fkey/fkey-ack im Knoten-Anruf); nil = Pruefung fehlgeschlagen
    func openBody(_ L: CallLink, _ s: [String: Any]) async -> String? {
        guard let id, let k = store?.chat.keys, let from = s["from_device"] as? String, let sealed = s["sealed"] as? String else { return nil }
        if L.devs[from] == nil && !keysRefreshed.contains(L.who) {
            keysRefreshed.insert(L.who)
            if let x = roster.leg(L.dev) { try? await linkKeys(L, host: x.host, fresh: true) } else { try? await loadPeerKeys(L, fresh: true) }
        }
        if let v = L.v2 {                                               // 2.3: an MEINEN Handle/mein Geraet gebunden
            guard let pd = L.devs[from], let o = ChatCrypto.openSignalV2(sealed, pk: k.enc.pk, sk: k.enc.sk),
                  ChatCrypto.verifySignalV2(callID: id, fromHandle: v.peer, fromDevice: from, toHandle: v.me, toDevice: k.deviceID, body: o.body, sig: o.sig, signPK: pd.sign)
            else { return nil }
            return o.body
        }
        guard let pd = L.devs[from], let o = ChatCrypto.openSignal(sealed, pk: k.enc.pk, sk: k.enc.sk),
              ChatCrypto.verifySignal(callID: id, fromUID: L.who, fromDevice: from, toUID: me, toDevice: k.deviceID, body: o.body, sig: o.sig, signPK: pd.sign)
        else { return nil }
        return o.body
    }
    func sendSignal(_ L: CallLink, _ body: String) async { await sendSignal(L) { _ in body } }
    /// Body je Zielgeraet (2.4: pc ist je Geraet verschieden, solange der Anrufer noch keine Antwort hat)
    func sendSignal(_ L: CallLink, _ make: (String) -> String) async {
        guard let store, let id, let dev = myDevice, !ending, !L.gone else { return }
        var sig: [String: String] = [:]
        for d in L.targets { if let x = seal(L, d, make(d)) { sig[d] = x } }
        guard !sig.isEmpty, let (_, code) = try? await store.apiResponse("api/calls/\(id)/signal", method: "POST", body: ["device": dev, "signals": sig]) else { return }
        if code == 409 && !roomMode { remoteEnded(reason: "gone") }
    }
    /// Zustand an alle Verbindungen (bzw. eine)
    func stateOut(_ only: CallLink? = nil) {
        guard ready else { return }
        if horn != nil { hornStateOut(); return }                           // 2.5: Knoten-Anruf - state an alle Endpunkte
        for L in only.map({ [$0] }) ?? links where !L.gone { stateOne(L) }
    }
    /// 2.4: pip + want (aeltere Gegenueber lesen nur mic/cam/screen); E3: screen, screen_mid, tracks/msid; E4: ready/replaces
    func stateOne(_ L: CallLink, replaces: String? = nil) {
        guard let dev = myDevice else { return }
        let scr = sharing && L.scrMode != nil
        let (m, c, p, pth) = (mic && audioTrack != nil, cam && !camPaused && !(scr && L.scrMode == .replace), pip, L.path)   // A: Kamera pausiert
        let roles = myRoles(L), smid = roles == nil ? nil : myScreenMid(L)
        Task { await sendSignal(L) { d in CallV3.stateBody(pc: L.pcFor(dev, d), mic: m, cam: c, screen: scr, pip: p, want: PiPLogic.want(pip: p), path: pth,
                                                           screenMid: smid, screenAudio: false, roles: roles, replaces: replaces) } }
    }
    /// Rollen der eigenen m-Zeilen (tracks/msid) - nur an Gegenueber, wenn ich "screen2" melde
    func myRoles(_ L: CallLink) -> CallV3.Roles? {
        guard hasScreen2, L.pc != nil else { return nil }
        var lines: [ScreenShare.Line] = []
        if let a = L.audioTr { lines.append(.init(mid: a.midOrNil, role: "mic", stream: a.sender.streamIds.first)) }
        if let v = L.camTr { lines.append(.init(mid: v.midOrNil, role: "cam", stream: v.sender.streamIds.first)) }
        if let s = L.scrTr { lines.append(.init(mid: s.midOrNil, role: "screen", stream: sharing && L.scrMode == .track ? scrSid : nil)) }
        return ScreenShare.roles(lines)
    }
    func myScreenMid(_ L: CallLink) -> String? {
        ScreenShare.screenMid(sharing: sharing && L.scrMode != nil, mode: L.scrMode, screenLine: L.scrTr?.midOrNil, camLine: L.camTr?.midOrNil)
    }
    func link(_ dev: String?) -> CallLink? { guard let dev else { return nil }; return links.first { $0.dev == dev && !$0.gone } }

    // MARK: Anrufen

    func start(chatID cid: Int, peer p: ChatMember, video: Bool, fed: FedChatInfo? = nil) {
        if busy { toast = String(localized: "Du telefonierst gerade schon."); return }
        if let f = fed, !f.callable { toast = String(localized: "Anrufe gehen erst, wenn die Anfrage angenommen ist."); return }
        reset()
        chatID = cid; peer = p.username; peerName = p.name; peerAvatar = p.avatar; role = "caller"; media = video ? "video" : "audio"
        if let f = fed, let h = f.peer?.handle, !f.myHandle.isEmpty { v2 = (f.myHandle, h); roomHandle = f.myHandle }   // 2.3: saga-call/v2
        let L = CallLink(mgr: self, dev: nil, who: v2?.peer ?? peer, name: peerName)
        L.avatar = peerAvatar; L.v2 = v2; L.role = "caller"
        links = [L]
        speaker = video                                                     // Video: Lautsprecher (wie FaceTime), Audio: Hoermuschel
        phase = .preparing; status = String(localized: "Kamera und Mikrofon …")
        CallEnv.log("start \(media)")
        Task {
            do {
                guard await ensureChat() else { throw APIError.other(String(localized: "Chats sind auf diesem Gerät noch nicht bereit.")) }
                guard await loadConfig() else { throw APIError.other(String(localized: "Anrufe sind auf diesem Server abgeschaltet.")) }
                try await loadPeerKeys(L)
                if L.devs.isEmpty { throw APIError.other(String(localized: "\(peerName) hat Chats noch auf keinem Gerät eingerichtet – Anrufe gehen erst danach.")) }
                try await prepareMedia(video: video)
                guard phase == .preparing else { return }
                id = ChatCrypto.hexID()
                CallSystem.shared.startOutgoing(self, id: id!, name: peerName, video: media == "video")
                makePC(L)
                addLocal(L, fromOffer: false)
                guard let pc = L.pc else { return }
                let offer = try await pc.offerAsync(CallRTC.constraints)
                try await pc.setLocalAsync(offer)
                try await dial(L, again: false)
            } catch { fail(error.localizedDescription) }
        }
    }
    private func dial(_ L: CallLink, again: Bool) async throws {
        guard let store, let id, let dev = myDevice, let sdp = L.pc?.localDescription?.sdp, !ending, phase == .preparing else { return }
        status = String(localized: "Verbinde …")
        let n = L.neg.localN > 0 ? L.neg.localN : L.neg.initialOffer()  // 2.4: pc, caps, n (aeltere Apps lesen sdp/media/fp)
        var sig: [String: String] = [:]                                // pc je Zielgeraet: "min:max" der device_ids
        let roles = myRoles(L)                                         // E3: Rolle je m-Zeile (nur mit screen2)
        for d in L.devs.keys { if let x = seal(L, d, CallV3.offerBody(sdp: sdp, media: media, pc: CallV3.pairPC(dev, d), n: n, caps: myCaps, roles: roles)) { sig[d] = x } }
        var req: [String: Any] = ["id": id, "chat_id": chatID, "media": media, "device": dev, "signals": sig]
        req.merge(capsHint) { a, _ in a }
        let (d, code) = try await store.apiResponse("api/calls", method: "POST", body: req)
        // Waehrend des POST aufgelegt (oder schon ein neuer Anruf): nicht klingeln lassen, sondern den eben angelegten
        // Anruf beim Server beenden - das /end aus hangup() kam ggf. vor dem Anlegen an und ging ins Leere.
        guard CallLogic.dialStillWanted(ending: ending, preparing: phase == .preparing, sameCall: self.id == id) else {
            CallEnv.log("dial: aufgelegt waehrend Verbinde")
            if (200..<300).contains(code) {
                _ = try? await store.apiResponse("api/calls/\(id)/end", method: "POST", body: ["reason": "hangup", "connected": false, "device": dev])
            }
            return
        }
        let j = ShareJSON.obj(d)
        if code == 409, j?["devices"] != nil, !again {
            if L.v2 == nil { keysRefreshed.insert(L.who) }
            try await loadPeerKeys(L, fresh: true); try await dial(L, again: true); return
        }
        if code == 409, ShareJSON.bool(j?["busy"]) { finish(.busy); return }
        guard (200..<300).contains(code) else { throw APIError.http(code, ShareJSON.str(j?["error"]) ?? "") }
        ready = true; phase = .ringingOut; status = String(localized: "Klingelt …")
        if let c = j?["call"] as? [String: Any] { adoptCall(c) }
        CallEnv.log("ringing \(id.prefix(8))")
        CallTones.shared.play(.ringback)
        flushIce(L); aliveOn()
        let pending = early; early = []                                  // Antwort kam schneller als die POST-Antwort
        for s in pending { await handleRaw(s) }
    }

    // MARK: Klingeln (eingehend)

    /// call.ring, call.invite oder VoIP-Push: Zustand holen, nur bei "ringing" klingeln
    func ringing(callID cid: String, hint: CallLogic.Push? = nil, silent: Bool = false) async {
        guard let store else { return }
        if done.contains(cid) { CallSystem.shared.endReported(cid, reason: .remoteEnded); return }   // schon erledigt (VoIP-Push kam spaet)
        if incoming?.id == cid || id == cid { return }
        guard let (d, code) = try? await store.apiResponse("api/calls/\(cid)"), code == 200,
              let c = ShareJSON.obj(d)?["call"] as? [String: Any] else { CallSystem.shared.endReported(cid, reason: .failed); return }
        if (c["kind"] as? String) == "group" { await ringGroup(c, hint: hint, silent: silent); return }   // 2.4 E5
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
        v2 = fedPair; roomHandle = fedPair?.0                              // 2.4: weitere Geraete im Raum ebenfalls v2
        incoming = inc; phase = .incoming; role = "callee"; media = inc.media; peerName = inc.name; peerAvatar = inc.avatar; peer = inc.username
        chatID = inc.chatID
        status = inc.media == "video" ? String(localized: "Eingehender Videoanruf") : String(localized: "Eingehender Anruf")
        CallEnv.log("incoming \(cid.prefix(8)) \(inc.media)")
        CallSystem.shared.reportIncoming(self, inc)
        aliveRinging(cid)
        #if DEBUG
        if let a = UserDefaults.standard.string(forKey: "hkCallAutoAnswer") {      // nur fuer Tests: annehmen/ablehnen
            Task { try? await Task.sleep(for: .seconds(4)); if incoming?.id == cid { if a == "decline" { decline() } else { answer(video: a == "video") } } }
        }
        #endif
    }

    /// Klingelt noch? (alle 5 s) - beim Gegenueber aufgelegt bzw. (Gruppe) Einladung erledigt: aufhoeren
    func aliveRinging(_ cid: String) {
        aliveTask?.cancel()
        aliveTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(5))
                guard let self, let inc = self.incoming, inc.id == cid, let store = self.store else { return }
                guard let (d, c) = try? await store.apiResponse("api/calls/\(cid)"), c == 200, let call = ShareJSON.obj(d)?["call"] as? [String: Any],
                      self.incoming?.id == cid else { continue }
                let st = call["state"] as? String
                if inc.group {
                    let mine = (call["mine"] as? [Any] ?? []).count
                    if st == "ended" { self.remoteEnded(reason: call["reason"] as? String); return }
                    if mine > 0 || (call["invite"] as? String).map({ $0 != "ringing" }) == true { self.stopGroupRing(cid, declined: (call["invite"] as? String) == "declined"); return }
                } else if st != "ringing" {
                    self.remoteEnded(reason: st == "active" ? "elsewhere" : (call["reason"] as? String))
                    return
                }
            }
        }
    }

    // MARK: Annehmen / Ablehnen

    func answer(video: Bool) {
        guard let inc = incoming, phase == .incoming else { return }
        if inc.group { answerGroup(inc, video: video); return }           // 2.4 E5: Gruppenanruf = beitreten
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
                adoptCall(c)
                let callerDev = c["caller_device"] as? String ?? ""
                let L = CallLink(mgr: self, dev: callerDev, who: v2?.peer ?? peer, name: peerName)
                L.avatar = peerAvatar; L.v2 = v2; L.role = "callee"
                links = [L]
                try await loadPeerKeys(L)
                var offer: (sdp: String, fp: [String], meta: CallV3.Meta)?
                for s in j["signals"] as? [[String: Any]] ?? [] {
                    if let q = ShareJSON.int(s["seq"]) { _ = CallLogic.fresh(q, seen: &seen) }
                    guard let o = await open(L, s) else { securityFail(); return }
                    guard (s["from_device"] as? String) == callerDev else { continue }
                    switch o.sig {
                    case .offer(let sdp, _, let fp): if offer == nil { offer = (sdp, fp, o.meta) }
                    case .ice(let c): L.remoteIce += c
                    default: break
                    }
                }
                guard let offer, CallLogic.fingerprintsMatch(sdp: offer.sdp, fp: offer.fp) else { securityFail(); return }
                L.fps = CallLogic.sdpFingerprints(offer.sdp)
                L.pcID = offer.meta.pc ?? CallV3.pairPC(dev, callerDev)     // vom Anrufer uebernehmen
                adoptPeer(L, offer.meta, device: callerDev)
                peerTracksIn(L, offer.meta)
                L.neg.initialRemote(offer.meta.n)
                try await prepareMedia(video: video)
                guard phase == .connecting else { return }
                makePC(L)
                guard let pc = L.pc else { return }
                try await setRemote(L, RTCSessionDescription(type: .offer, sdp: offer.sdp))
                addLocal(L, fromOffer: true)
                findRemoteVideo(L)
                let ans = try await pc.answerAsync(CallRTC.constraints)
                try await pc.setLocalAsync(ans)
                guard let sdp = pc.localDescription?.sdp,
                      let sealed = seal(L, callerDev, CallV3.answerBody(sdp: sdp, media: media, pc: L.pcFor(dev, callerDev), n: offer.meta.n ?? 1, caps: myCaps, roles: myRoles(L))) else { throw APIError.other("–") }
                var req: [String: Any] = ["device": dev, "signals": [callerDev: sealed]]
                req.merge(capsHint) { a, _ in a }
                let (ad, ac) = try await store.apiResponse("api/calls/\(inc.id)/answer", method: "POST", body: req)
                if ac == 409 { throw APIError.other(String(localized: "Der Anruf ist schon beendet oder wurde auf einem anderen Gerät angenommen.")) }
                guard (200..<300).contains(ac) else { throw APIError.http(ac, ShareJSON.obj(ad).flatMap { ShareJSON.str($0["error"]) } ?? "") }
                ready = true; L.established = true
                applyRemoteIce(L); flushIce(L); aliveOn(); stateOut(L)
                let pending = early; early = []
                for s in pending { await handleRaw(s) }
                connTimer(L)
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
        runSoon()
    }

    // MARK: Medien

    /// askMic false (Begleitgeraet): nicht nach dem Mikrofon fragen, keine Tonspur - erst beim Einschalten
    func prepareMedia(video: Bool, askMic: Bool = true) async throws {
        if askMic && !CallEnv.fakeMedia {
            guard await AVCaptureDevice.requestAccess(for: .audio) else {
                #if os(iOS)
                throw APIError.other(String(localized: "Mikrofon nicht erlaubt – bitte in den Einstellungen für Saga erlauben."))
                #else
                throw APIError.other(String(localized: "Mikrofon nicht erlaubt – bitte in den Systemeinstellungen unter Datenschutz für Saga erlauben."))
                #endif
            }
        }
        if askMic { makeAudioTrack() }
        if video {
            let allowed = CallEnv.fakeMedia ? true : await AVCaptureDevice.requestAccess(for: .video)
            if allowed, let c = CallCamera(factory: CallRTC.factory) { camera = c; localVideo = c.track; cam = true; frontCamera = c.front; wire(c) }
            else { toast = String(localized: "Keine Kamera verfügbar – Anruf nur mit Ton."); media = role == "caller" && kind == "dm" && joinRole.isEmpty ? "audio" : media }
        }
    }
    func makeAudioTrack() {
        #if os(macOS)
        let noMic = CallEnv.fakeMedia                                      // Tests am Mac: kein Ton-Track -> das echte Mikrofon wird nie geoeffnet
        #else
        let noMic = false
        #endif
        guard !noMic, audioTrack == nil else { return }
        let f = CallRTC.factory
        audioTrack = f.audioTrack(with: f.audioSource(with: CallRTC.constraints), trackId: "a" + ChatCrypto.hexID().prefix(8))
        audioTrack?.isEnabled = mic
    }

    func makePC(_ L: CallLink) {
        let c = RTCConfiguration()
        c.sdpSemantics = .unifiedPlan
        c.bundlePolicy = .maxBundle
        c.iceTransportPolicy = ShareJSON.bool(cfg["relay_only"]) ? .relay : .all
        c.iceServers = (cfg["ice_servers"] as? [[String: Any]] ?? []).compactMap { s in
            let urls = (s["urls"] as? [String]) ?? ((s["urls"] as? String).map { [$0] } ?? [])
            guard !urls.isEmpty else { return nil }
            return RTCIceServer(urlStrings: urls, username: s["username"] as? String, credential: s["credential"] as? String)
        }
        L.pc = CallRTC.factory.peerConnection(with: c, constraints: CallRTC.constraints, delegate: L)
    }
    /// Immer zwei Transceiver (Audio + Video sendrecv) - Kamera an/aus ohne Neuverhandlung
    func addLocal(_ L: CallLink, fromOffer: Bool) {
        guard let pc = L.pc else { return }
        let sid = "s" + (id ?? "0").prefix(8)
        if !fromOffer {
            let ai = RTCRtpTransceiverInit(); ai.direction = .sendRecv; ai.streamIds = [sid]
            if let a = audioTrack { pc.addTransceiver(with: a, init: ai) } else { pc.addTransceiver(of: .audio, init: ai) }
            let vi = RTCRtpTransceiverInit(); vi.direction = .sendRecv; vi.streamIds = [sid]
            if let v = camera?.track { pc.addTransceiver(with: v, init: vi) } else { pc.addTransceiver(of: .video, init: vi) }
            return
        }
        // Erstes Angebot: eine Ton- und eine Video-m-Zeile; weitere (z. B. ein Bildschirm einer spaeteren Fassung) nur empfangen
        var hadAudio = false, hadVideo = false
        for t in pc.transceivers {
            var e: NSError?
            let first = t.mediaType == .audio ? !hadAudio : t.mediaType == .video ? !hadVideo : false
            if t.mediaType == .audio { hadAudio = true } else if t.mediaType == .video { hadVideo = true }
            guard first else { t.setDirection(.recvOnly, error: &e); continue }
            t.setDirection(.sendRecv, error: &e)
            t.sender.streamIds = [sid]
            if t.mediaType == .audio, let a = audioTrack { t.sender.track = a }
            if t.mediaType == .video, let v = camera?.track { t.sender.track = v }
        }
    }
    /// Fernbilder zuordnen (2.4 E3, Vertrag "Empfaenger: Kamera und Bildschirm unterscheiden"): remoteVideo = Bildschirm,
    /// wenn das Gegenueber teilt (auch PiP/Schwebefenster zeigen ihn), sonst die Kamera; remoteSide = Kamera daneben
    func findRemoteVideo(_ L: CallLink) {
        guard !L.gone else { return }
        let vids = (L.pc?.transceivers ?? []).filter { $0.mediaType == .video }
        var byMid: [String: RTCVideoTrack] = [:], order: [String] = [], recv = Set<String>()
        for t in vids {
            guard let m = t.midOrNil, let tr = t.receiver.track as? RTCVideoTrack else { continue }
            byMid[m] = tr; order.append(m)
            var d = RTCRtpTransceiverDirection.inactive
            if t.currentDirection(&d) ? (d == .sendRecv || d == .recvOnly) : true { recv.insert(m) }   // vor der Aushandlung: wie bisher
        }
        let v = ScreenShare.classify(videoMids: order, receiving: recv, peerTracks: L.peerTracks, screen: L.remote.screen, screenMid: L.remote.screenMid)
        let fallback = vids.first?.receiver.track as? RTCVideoTrack           // 2.3: erste Videospur, auch ohne mid
        let main = (v.screen ?? v.cam).flatMap { byMid[$0] } ?? (v.screen == nil ? fallback : nil)
        let side = v.screen != nil && L.remote.cam ? v.cam.flatMap { byMid[$0] } : nil
        L.remoteVideo = main; L.remoteSide = side
        applyPlayback(L)
        syncPrimary()
    }
    /// Ton der Gegenstellen: an, solange die Wiedergabe an ist (Begleitgeraet: aus); eigene Geraete nie abspielen
    func applyPlayback(_ only: CallLink? = nil) {
        for L in only.map({ [$0] }) ?? links {
            for t in L.pc?.transceivers ?? [] where t.mediaType == .audio {
                guard let a = t.receiver.track else { continue }
                let on = sound && !L.own
                if a.isEnabled != on { a.isEnabled = on }
            }
            #if os(macOS)
            if CallEnv.fakeMedia, let a = L.audioTr?.receiver.track as? RTCAudioTrack { a.source.volume = 0 }   // Tests: still
            #endif
        }
        if only == nil { hornAudioRoute() }                                 // 2.5: Knoten-Anruf
    }
    /// Veroeffentlichte Werte (Einzelansicht, PiP, Kopfzeile) aus den Verbindungen ziehen
    func syncPrimary() {
        let p = primary
        let r = p?.remote ?? CallRemoteState()
        if remote != r { remote = r }
        if remoteVideo !== p?.remoteVideo { remoteVideo = p?.remoteVideo }
        if remoteSide !== p?.remoteSide { remoteSide = p?.remoteSide }
        if let p, p.swap != swapView { p.swap = swapView }
        let on = links.filter { $0.connected }
        // 2.5: Knoten-Anruf ohne Mesh-Verbindungen - verschluesselt ist er ab hornGo (Frame-E2E), nicht ueber links
        let v = horn?.started == true || (!on.isEmpty && on.allSatisfy(\.verified))
        if verified != v { verified = v }
        let rel = links.contains { $0.relay }
        if relay != rel { relay = rel }
        if path != p?.path { path = p?.path }
        let rc = links.contains { $0.reconnecting }
        if reconnecting != rc { reconnecting = rc }
        let paused = links.contains { $0.paused }
        if shareCamPaused != paused { shareCamPaused = paused }
        refreshTiles()
    }
    func quiet(_ L: CallLink) -> Bool {
        let x = roster.leg(L.dev)
        return CallRoom.quiet(.init(device: L.dev ?? "", own: L.own, companion: x?.companion ?? false, gotState: L.gotState,
                                    mic: L.remote.mic, cam: L.remote.cam, screen: L.remote.screen))
    }
    /// Kacheln je sichtbarer Verbindung (Gruppe: alle anderen Personen, auch ohne Bild)
    func refreshTiles() {
        let live = links.filter { !$0.gone && $0.dev != nil }
        let shown = live.filter { !quiet($0) }
        let mv = CallRoom.meshView(kind: kind, shown: shown.count)
        if meshView != mv { meshView = mv; pipRefresh() }
        let list = mv ? live.filter { !quiet($0) || (kind == "group" && !$0.own) } : []
        let t: [CallTile] = list.map { L in
            let x = roster.leg(L.dev)
            let vid = L.remoteVideo != nil && (L.remote.cam || L.remote.screen)
            let big = L.swap && L.remoteSide != nil ? L.remoteSide : L.remoteVideo
            let small = L.remoteSide == nil ? nil : (L.swap ? L.remoteVideo : L.remoteSide)
            return CallTile(id: L.dev ?? "", name: L.own ? String(localized: "du") : L.name, avatar: L.avatar, devName: x?.devName ?? L.devName,
                            companion: x?.companion ?? false, own: L.own, mic: L.remote.mic, video: vid ? big : nil, side: vid ? small : nil,
                            screen: L.remote.screen && !L.swap, connected: L.connected, speaking: L.dev != nil && L.dev == speakerDev)
        }
        if tiles != t { tiles = t }
    }
    /// Kachel: Bildschirm und Kamera tauschen
    func swapTile(_ dev: String) { if let L = link(dev) { L.swap.toggle(); refreshTiles() } }

    // MARK: ICE

    func flushIce(_ L: CallLink) {
        L.outTask?.cancel(); L.outTask = nil
        guard ready, !L.outIce.isEmpty, let dev = myDevice else { return }
        while !L.outIce.isEmpty {
            let n = min(CallLogic.iceBatch, L.outIce.count)
            let list = Array(L.outIce.prefix(n)); L.outIce.removeFirst(n)
            Task { await sendSignal(L) { d in CallV3.iceBody(list, pc: L.pcFor(dev, d)) } }
        }
    }
    func localCandidate(_ L: CallLink, _ c: CallLogic.Candidate) {
        guard !L.gone else { return }
        L.outIce.append(c)
        if L.outTask == nil {
            L.outTask = Task { [weak self, weak L] in
                try? await Task.sleep(for: .milliseconds(Int(CallLogic.iceDelay * 1000)))
                guard !Task.isCancelled, let L else { return }
                L.outTask = nil; self?.flushIce(L)
            }
        }
    }
    /// Kandidaten setzen, die zur aktuellen Remote-SDP passen; fremde ufrag zurueckhalten (kommen oft vor der Antwort
    /// eines ICE-Restarts), aeltere Generationen verwerfen
    func applyRemoteIce(_ L: CallLink) {
        guard let pc = L.pc, let rsdp = pc.remoteDescription?.sdp else { return }
        let cur = CallV3.sdpUfrags(rsdp)
        let list = L.remoteIce; L.remoteIce = []
        for c in list {
            switch CallV3.iceUse(c, current: cur, old: L.oldUfrags) {
            case .apply: pc.add(RTCIceCandidate(sdp: c.candidate, sdpMLineIndex: c.sdpMLineIndex, sdpMid: c.sdpMid)) { _ in }
            case .hold: L.remoteIce.append(c)
            case .drop: break
            }
        }
        if L.remoteIce.count > 120 { L.remoteIce.removeFirst(L.remoteIce.count - 120) }
    }
    /// Remote-SDP setzen und die ufrags der vorigen als alte Generation merken
    func setRemote(_ L: CallLink, _ d: RTCSessionDescription) async throws {
        guard let pc = L.pc else { return }
        let prev = CallV3.sdpUfrags(pc.remoteDescription?.sdp ?? "")
        try await pc.setRemoteAsync(d)
        L.oldUfrags.formUnion(prev.subtracting(CallV3.sdpUfrags(pc.remoteDescription?.sdp ?? "")))
    }
    func connTimer(_ L: CallLink) {
        L.connTask?.cancel()
        L.connTask = Task { [weak self, weak L] in                        // in finish()/close() abgebrochen
            try? await Task.sleep(for: .seconds(CallLogic.connectTimeout))
            guard let self, let L, !Task.isCancelled, !L.connected, !L.gone, self.ready else { return }
            self.linkFailed(L)
        }
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
        case "call.invite":                                                 // 2.4 E5: Gruppenanruf klingelt
            guard let cid, CallLogic.isCallID(cid) else { return }
            if !busy && incoming == nil && !done.contains(cid) { Task { await ringing(callID: cid, silent: ShareJSON.bool(d["silent"])) } }
            runSoon()
        case "call.roster":                                                 // 2.4: Teilnehmerliste
            guard let cid else { return }
            let rv = (d["rv"] as? NSNumber)?.doubleValue ?? Date().timeIntervalSince1970
            if cid == id, ready { rosterIn(CallRoom.legs(d["legs"]), rv: rv, extra: d) }
            if let inc = incoming, inc.id == cid, inc.group, CallRoom.stopRinging(CallRoom.legs(d["legs"]), me: me) { stopGroupRing(cid, declined: false) }
            runSoon()
        case "call.horn_move": hornMoveIn(d)                               // 2.5: Knoten wird geleert/aktualisiert/entfernt
        case "call.mod": hornModIn(d)                                       // 2.5: Gastgeber hat stummgeschaltet/entfernt
        case "call.state":
            guard let cid else { return }
            let st = d["state"] as? String, group = (d["kind"] as? String) == "group"
            defer { runSoon() }
            if let inc = incoming, inc.id == cid {
                if st == "ended" { remoteEnded(reason: d["reason"] as? String) }
                else if st == "declined" { stopGroupRing(cid, declined: true) }
                else if st == "active", !inc.group, !group, (d["device"] as? String) != myDevice { remoteEnded(reason: "elsewhere") }
                return
            }
            guard cid == id else { if st == "ended" { CallSystem.shared.endReported(cid, reason: .remoteEnded) }; return }
            if st == "ended" { remoteEnded(reason: d["reason"] as? String) }
            else if group || kind == "group" || d["moved"] != nil || !joinRole.isEmpty { /* 2.4: Raum - regelt die Teilnehmerliste */ }
            else if st == "active", role == "caller", let dev = d["device"] as? String, let L = links.first, L.dev == nil || L.dev == dev {
                L.dev = L.dev ?? dev; status = String(localized: "Verbinde …"); CallTones.shared.stop()
            }
        default: break
        }
    }
    /// Chat-Schluessel eben eingerichtet (Wiederherstellungsschluessel, Kopplung): Faehigkeiten wie beim Start
    func chatKeysReady() async {
        guard capsSent.isEmpty else { return }
        _ = await loadConfig(); await reportCaps()
    }
    /// Nach dem Start bzw. Zurueckkommen: klingelt gerade etwas fuer mich? (verpasste call.ring); 2.4: Faehigkeiten
    /// melden, Leiste "Anruf laeuft" auffrischen
    func checkActive() async {
        if capsSent.isEmpty, store?.chat.keys != nil { _ = await loadConfig(); await reportCaps() }
        if busy, ready, let id, let dev = myDevice, let store {                // Live-Kanal neu verbunden: verpasste Signale nachholen
            let after = seen.max() ?? 0
            let asked = Date().timeIntervalSince1970
            guard let (d, code) = try? await store.apiResponse("api/calls/\(id)?device=\(dev)&after=\(after)"), code == 200, let j = ShareJSON.obj(d) else { return }
            if let c = j["call"] as? [String: Any], (c["state"] as? String) == "ended" { remoteEnded(reason: c["reason"] as? String); return }
            if let c = j["call"] as? [String: Any], c["legs"] != nil, multi { rosterIn(CallRoom.legs(c["legs"]), rv: asked, extra: c) }
            for s in j["signals"] as? [[String: Any]] ?? [] {
                if let q = ShareJSON.int(s["seq"]), CallLogic.fresh(q, seen: &seen) { await handleRaw(s) }
            }
            return
        }
        await refreshRunning(ringDM: true)
    }

    func handleRaw(_ s: [String: Any]) async {
        guard let from = s["from_device"] as? String, !ending else { return }
        if horn != nil || topo == "horn" {                                 // 2.5: Knoten-Anruf - nur fkey/fkey-ack/state
            if horn == nil { horn = HornSession(nil) }
            await hornSig(s); return
        }
        var found = link(from)
        if found == nil, let lead = links.first, lead.dev == nil, kind == "dm", lead.devs[from] != nil { found = lead }   // 1:1 vor der Antwort
        guard let L = found else {                                          // 2.4: Geraet ohne Verbindung (Raum) - pruefen, dann annehmen
            guard multi, ready else { return }
            var q = pend[from] ?? []
            q.append(s); if q.count > 80 { q.removeFirst() }
            pend[from] = q
            admit(from)
            return
        }
        guard let (sg, m) = await open(L, s) else { securityFail(L); return }
        guard !L.gone, !ending else { return }
        // 2.4 E4: altes Geraet - das neue eigene Geraet hat uebernommen
        if case .state = sg, CallRoom.handoverOut(ready: m.ready == true, replaces: m.replaces, here: myDevice ?? "", fromOwn: L.own) {
            handoverOut(from: from); return
        }
        guard let dev = myDevice, CallV3.samePC(m.pc, L.pcID ?? CallV3.pairPC(dev, from)) else { return }   // andere PeerConnection - nicht unsere
        switch sg {
        case .ice(let c):
            L.remoteIce += c; applyRemoteIce(L)
        case .answer(let sdp, let fp):
            guard let pc = L.pc else { return }
            if pc.remoteDescription != nil { await renegAnswer(L, sdp: sdp, fp: fp, meta: m, from: from); return }
            guard L.role == "caller" else { return }
            if let pd = L.dev, from != pd { return }
            guard CallLogic.fingerprintsMatch(sdp: sdp, fp: fp) else { securityFail(L); return }
            L.dev = from; L.fps = CallLogic.sdpFingerprints(sdp)
            L.pcID = m.pc ?? CallV3.pairPC(dev, from); adoptPeer(L, m, device: from)
            peerTracksIn(L, m)
            _ = L.neg.remoteAnswer(n: m.n, haveLocalOffer: true)
            if links.count == 1 && kind == "dm" && joinRole.isEmpty {        // 1:1 wie bisher
                CallTones.shared.stop()
                phase = .connecting; status = String(localized: "Verbinde …")
            } else if phase != .active { phase = .connecting }
            do {
                try await setRemote(L, RTCSessionDescription(type: .answer, sdp: sdp))
                L.established = true
                findRemoteVideo(L); applyRemoteIce(L)
                stateOut(L)
                connTimer(L)
            } catch { linkError(L, String(localized: "Verbindung nicht aufgebaut: \(error.localizedDescription)")) }
        case .offer(let sdp, _, let fp):
            if L.established { await renegOffer(L, sdp: sdp, fp: fp, meta: m, from: from) }   // 1:1: erstes Angebot kommt ueber answer()
            else if L.pc == nil && L.role == "callee" && L.dev == from { await acceptOffer(L, sdp: sdp, fp: fp, meta: m) }   // 2.4: Raum
        case .state(let mi, let c, let sc):
            let was = L.remote.screen
            L.remote = CallRemoteState(mic: mi, cam: c, screen: sc, screenMid: sc ? m.screenMid : nil, screenAudio: sc && m.screenAudio == true)
            L.gotState = true
            peerTracksIn(L, m)
            L.swap = ScreenShare.swapAfter(state: was, isScreen: sc, swap: L.swap)   // neues Teilen: Bildschirm gross
            if L === primary { swapView = L.swap }
            findRemoteVideo(L)
            let w = m.want ?? .large                                       // 2.3-Gegenueber: immer grosses Bild
            if w != L.remoteWant || m.path != L.remotePath { L.remoteWant = w; L.remotePath = m.path; applyLimits(L) }
            pipRefresh()
        case .bye:
            guard L.dev == from else { return }
            if roomMode { dropLink(L, nil) } else { remoteEnded(reason: nil) }   // 2.4: Raum - nur dieses Geraet geht
        case .other: break                                                 // unbekannte t-Typen: ignorieren
        }
    }

    // MARK: Neuverhandlung (2.4, saga-call v3) - nur mit Gegenuebern, die "reneg" melden

    /// Faehigkeiten des Gegenuebers uebernehmen; hoeflich = groessere device_id
    func adoptPeer(_ L: CallLink, _ m: CallV3.Meta, device: String) {
        L.peerCaps = m.caps ?? []                                          // fehlt (2.1/2.3): []
        if let mine = myDevice { L.neg.polite = CallV3.polite(me: mine, peer: device) }
    }
    /// 2.4 E3: Rollen der m-Zeilen des Gegenuebers (letzter Stand aus offer/answer/state gilt)
    func peerTracksIn(_ L: CallLink, _ m: CallV3.Meta) { if let t = m.tracks, !t.isEmpty { L.peerTracks = t } }
    /// SDP waehrend des Gespraechs: gleiches Geraet, Fingerabdruecke passen und sind dieselben wie beim Aufbau
    /// (DTLS-Zertifikat aendert sich bei einer Neuverhandlung nicht - sonst Abbruch wie bei jeder Pruefung)
    private func renegTrusted(_ L: CallLink, sdp: String, fp: [String], from: String?) -> Bool? {
        guard let pd = L.dev, from == pd else { return nil }
        guard CallLogic.fingerprintsMatch(sdp: sdp, fp: fp), Set(CallLogic.sdpFingerprints(sdp)) == Set(L.fps) else { return false }
        return true
    }
    private func renegOffer(_ L: CallLink, sdp: String, fp: [String], meta: CallV3.Meta, from: String?) async {
        guard ready, L.canReneg(myCaps), let pc = L.pc, pc.remoteDescription != nil, !ending, let dev = myDevice else { return }
        guard let ok = renegTrusted(L, sdp: sdp, fp: fp, from: from) else { return }
        guard ok else { securityFail(L); return }
        switch L.neg.remoteOffer(n: meta.n, stable: pc.signalingState == .stable) {
        case .ignore, .stale:
            CallEnv.log("reneg: Angebot verworfen (Zusammenstoss/alt)")
            return
        case .accept(let rollback):
            do {
                if rollback { try await pc.setLocalAsync(RTCSessionDescription(type: .rollback, sdp: "")); L.negPending = true }   // eigenes Angebot spaeter neu
                peerTracksIn(L, meta)
                try await setRemote(L, RTCSessionDescription(type: .offer, sdp: sdp))
                applyRemoteIce(L)
                let ans = try await pc.answerAsync(CallRTC.constraints)
                try await pc.setLocalAsync(ans)
                guard let out = pc.localDescription?.sdp else { return }
                findRemoteVideo(L)
                CallEnv.log("reneg: Antwort n=\(meta.n ?? 0)")
                let (md, cp, ro) = (media, myCaps, myRoles(L))
                await sendSignal(L) { d in CallV3.answerBody(sdp: out, media: md, pc: L.pcFor(dev, d), n: meta.n ?? 0, caps: cp, roles: ro) }
                renegLater(L)
            } catch { CallEnv.log("reneg offer: \(error.localizedDescription)") }
        }
    }
    /// Aufgeschobenes eigenes Angebot (Bildschirm an/aus waehrend einer laufenden Aushandlung) nachholen
    private func renegLater(_ L: CallLink) {
        guard L.negPending, let pc = L.pc, pc.signalingState == .stable, !L.neg.makingOffer else { return }
        L.negPending = false
        Task { await renegotiate(L, iceRestart: false) }
    }
    private func renegAnswer(_ L: CallLink, sdp: String, fp: [String], meta: CallV3.Meta, from: String?) async {
        guard L.canReneg(myCaps), let pc = L.pc else { return }
        guard let ok = renegTrusted(L, sdp: sdp, fp: fp, from: from) else { return }
        guard ok else { securityFail(L); return }
        guard L.neg.remoteAnswer(n: meta.n, haveLocalOffer: pc.signalingState == .haveLocalOffer) == .apply else { return }
        do {
            peerTracksIn(L, meta)
            try await setRemote(L, RTCSessionDescription(type: .answer, sdp: sdp))
            applyRemoteIce(L); findRemoteVideo(L)
            L.appliedScreen = nil; applyLimits(L)                          // neue m-Zeile: Sende-Parameter erst jetzt setzbar
            CallEnv.log("reneg: fertig n=\(meta.n ?? 0)")
            renegLater(L)
        } catch { CallEnv.log("reneg answer: \(error.localizedDescription)") }
    }
    /// Eigenes Angebot waehrend des Gespraechs (ICE-Restart, 2.4 E3: Bildschirm-Spur an/aus). Zusammenstoss regelt
    /// Perfect Negotiation; laeuft gerade eine Aushandlung, wird das Angebot danach nachgeholt (renegLater).
    func renegotiate(_ L: CallLink, iceRestart: Bool) async {
        guard ready, L.canReneg(myCaps), let pc = L.pc, pc.remoteDescription != nil, !ending, !L.gone, let dev = myDevice else { return }
        guard pc.signalingState == .stable, !L.neg.makingOffer else { if !iceRestart { L.negPending = true }; return }
        let n = L.neg.beginOffer()
        do {
            let c = iceRestart ? RTCMediaConstraints(mandatoryConstraints: ["IceRestart": "true"], optionalConstraints: ["DtlsSrtpKeyAgreement": "true"])
                               : CallRTC.constraints
            let o = try await pc.offerAsync(c)
            guard L.neg.makingOffer, pc.signalingState == .stable, !ending else {   // inzwischen fremdes Angebot angenommen
                L.neg.offerFailed(); if !iceRestart { L.negPending = true; renegLater(L) }; return
            }
            try await pc.setLocalAsync(o)
            L.neg.offerSent(n)
            guard let sdp = pc.localDescription?.sdp else { return }
            CallEnv.log("reneg: Angebot n=\(n) restart=\(iceRestart)")
            let (md, cp, ro) = (media, myCaps, myRoles(L))
            await sendSignal(L) { d in CallV3.offerBody(sdp: sdp, media: md, pc: L.pcFor(dev, d), n: n, caps: cp, restart: iceRestart, roles: ro) }
            if L.stateDue { L.stateDue = false; stateOut(L) }               // E3: screen_mid steht erst jetzt fest
        } catch { L.neg.offerFailed(); CallEnv.log("reneg: \(error.localizedDescription)") }
    }

    // MARK: ICE-Restart (2.4): Verbindung weg bzw. Netzwechsel WLAN <-> Mobilfunk

    private func iceRestart(_ L: CallLink) {
        L.restarts += 1; L.reconnecting = true
        status = String(localized: "Verbindung wird neu aufgebaut …")
        syncPrimary()
        Task { await renegotiate(L, iceRestart: true) }
        L.restartTask?.cancel()
        L.restartTask = Task { [weak self, weak L] in                     // kommt die Verbindung nicht zurueck: wie bisher beenden
            try? await Task.sleep(for: .seconds(CallV3.restartTimeout))
            guard let self, let L, !Task.isCancelled, L.reconnecting, !self.ending, !L.gone else { return }
            if CallV3.iceAction(state: "failed", everConnected: true, canReneg: L.canReneg(self.myCaps), attempts: L.restarts) == .restart { self.iceRestart(L) }
            else { self.linkFailed(L) }
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
        guard connected, !ending, CallV3.netChangeNeedsRestart(old: old, new: np) else { return }
        for L in links where L.connected && L.canReneg(myCaps) && !L.gone {
            CallEnv.log("netz gewechselt -> ICE-Restart")
            L.restarts = 0
            iceRestart(L)
        }
    }

    // MARK: Bitrate je Pfad (2.4, Tabelle §1.3; ab 3 Endpunkten Mesh-Werte) - nur bei Aenderung setParameters

    func applyLimits(_ L: CallLink) {
        guard let pc = L.pc, let path = CallV3.worse(L.path, L.remotePath) else { return }   // schlechterer von eigenem/gemeldetem Weg
        let src = camera?.height                                            // ohne Kamera unbekannt (small: 480)
        let n = CallRoom.streams(links: links.filter { !$0.gone }.count)   // 2.4 E5: Mesh je Strom
        // 2.4 E3: Betriebsart A - auf der Kamera-m-Zeile laeuft der Bildschirm (Bildschirm-Regeln); B - Kamera daneben
        // gedrosselt (<= 300 kbit/s, ueber Relais TCP aus), Bildschirm auf eigener m-Zeile
        let replace = sharing && L.scrMode == .replace, beside = sharing && L.scrMode == .track
        let v = replace ? CallV3.videoLimits(path: path, kind: .screen, streams: n, want: L.remoteWant, sourceHeight: nil)
                        : CallV3.videoLimits(path: path, kind: .camera, streams: n, want: L.remoteWant, sourceHeight: src, sharing: beside)
        if v != L.appliedVideo, let s = L.camTr?.sender, set(s, v) {
            L.appliedVideo = v
            CallEnv.log("limits video \(path.rawValue) \(v.maxBitrate / 1000)k /\(v.scaleDown) want=\(L.remoteWant.rawValue) share=\(sharing) n=\(n)")
        }
        L.paused = beside && !v.active && camera != nil
        if beside, let s = L.scrTr?.sender {
            let l = CallV3.videoLimits(path: path, kind: .screen, streams: n, want: L.remoteWant, sourceHeight: nil)
            if l != L.appliedScreen, set(s, l) { L.appliedScreen = l; CallEnv.log("limits screen \(path.rawValue) \(l.maxBitrate / 1000)k \(l.maxFramerate)fps") }
        }
        if sharing { capture.setFps(ScreenShare.captureFps(path: CallV3.worse(primary?.path, primary?.remotePath))) }
        let a = CallV3.audioBitrate(path: path)
        if a != L.appliedAudio, let s = pc.transceivers.first(where: { $0.mediaType == .audio })?.sender {
            let p = s.parameters
            if !p.encodings.isEmpty {
                for e in p.encodings { e.maxBitrateBps = NSNumber(value: a) }
                s.parameters = p
                L.appliedAudio = a
            }
        }
    }
    /// Sende-Parameter setzen; false = noch nicht ausgehandelt (keine Encodings)
    private func set(_ s: RTCRtpSender, _ v: CallV3.Limits) -> Bool {
        let p = s.parameters
        guard !p.encodings.isEmpty else { return false }
        for e in p.encodings {
            e.maxBitrateBps = NSNumber(value: v.maxBitrate)
            e.scaleResolutionDownBy = NSNumber(value: v.scaleDown)
            e.maxFramerate = NSNumber(value: v.maxFramerate)
            e.isActive = v.active
        }
        p.degradationPreference = NSNumber(value: (v.maintainResolution ? RTCDegradationPreference.maintainResolution : .balanced).rawValue)
        s.parameters = p
        return true
    }
    /// Mesh-Groesse geaendert (Verbindung dazu/weg): Bitrate je Strom neu
    func relimitAll() { for L in links where !L.gone { L.appliedVideo = nil; L.appliedScreen = nil; applyLimits(L) } }

    // MARK: Bildschirm teilen (2.4 E3, Vertrag "Bildschirm v2") - gilt fuer alle Verbindungen, Betriebsart je Verbindung

    /// Knopf "Bildschirm teilen" bzw. "Teilen beenden"
    func toggleScreen() {
        if sharing { stopScreen(); return }
        guard phase == .active, links.contains(where: { $0.pc != nil }) || horn?.cur != nil else { return }
        if ScreenShare.showHint(.standard) { screenHint = true; return }
        pickScreen()
    }
    /// Hinweis bestaetigt ("Bildschirm waehlen"); dontShow = "Nicht mehr anzeigen" (je Geraet)
    func screenHintDone(go: Bool, dontShow: Bool) {
        screenHint = false
        if dontShow { UserDefaults.standard.set(true, forKey: ScreenShare.hintKey) }
        if go, phase == .active { pickScreen() }
    }
    /// Verbunden: Rueckmeldungen der Aufnahme verdrahten; iPhone/iPad lauscht ab jetzt auf die ReplayKit-Erweiterung
    /// (auch ein Start ueber das Kontrollzentrum landet so im Anruf)
    func screenOn() {
        capture.onStart = { [weak self] in self?.screenStarted() }
        capture.onStop = { [weak self] in self?.stopScreen() }
        capture.onError = { [weak self] m in self?.toast = m }
        #if os(iOS)
        capture.listen()
        #endif
    }
    private func pickScreen() {
        screenOn()
        capture.pick(fps: ScreenShare.captureFps(path: CallV3.worse(primary?.path, primary?.remotePath)))
    }
    /// Aufnahme laeuft: Spur anlegen, je Verbindung die Betriebsart waehlen (B nur, wenn beide reneg + screen2 melden
    /// und verbunden)
    private func screenStarted() {
        guard phase == .active, !ending else { capture.stop(); return }
        if sharing { return }
        let src = CallRTC.factory.videoSource(forScreenCast: true)          // Detail/Text: Aufloesung vor Bildrate
        let track = CallRTC.factory.videoTrack(with: src, trackId: "d" + ChatCrypto.hexID().prefix(8))
        scrSource = src; scrTrack = track
        if scrSid.isEmpty { scrSid = "k" + ChatCrypto.hexID().prefix(8) }   // eigener Stream fuer den Bildschirm
        capture.sink.set(src)
        sharing = true
        CallEnv.log("screen start")
        for L in links where !L.gone { screenLink(L) }
        hornScreen()                                                        // 2.5: ueber den Knoten
    }
    /// Bildschirm auf eine Verbindung (auch: eine spaeter hinzukommende bekommt den laufenden Bildschirm)
    func screenLink(_ L: CallLink) {
        guard sharing, let track = scrTrack, let pc = L.pc, L.scrMode == nil, !L.gone else { return }
        let mode = ScreenShare.mode(mine: myCaps, peer: L.peerCaps, established: L.connected && pc.remoteDescription != nil)
        L.scrMode = mode
        L.appliedVideo = nil; L.appliedScreen = nil
        CallEnv.log("screen link mode=\(mode.rawValue)")
        if mode == .track {
            let lines = pc.transceivers.filter { $0.mediaType == .video }.map { ScreenShare.VideoLine(mid: $0.midOrNil, isCam: $0 === L.camTr) }
            if let m = ScreenShare.reuseMid(own: L.scrTr?.midOrNil, lines: lines, peerTracks: L.peerTracks),
               let t = pc.transceivers.first(where: { $0.mediaType == .video && $0.midOrNil == m }) {
                var e: NSError?
                t.sender.track = track; t.sender.streamIds = [scrSid]
                t.setDirection(.sendRecv, error: &e)
                L.scrTr = t
            } else {
                let ini = RTCRtpTransceiverInit(); ini.direction = .sendRecv; ini.streamIds = [scrSid]
                L.scrTr = pc.addTransceiver(with: track, init: ini)
            }
            L.stateDue = true                                               // Zustand nach dem Angebot (mid steht dann fest)
            Task { await renegotiate(L, iceRestart: false) }
            stateLater(L)
        } else {
            L.camTr?.sender.track = track                                   // A: Bildschirm statt Kamera
            stateOut(L)
        }
        applyLimits(L)
    }
    /// stateDue je Verbindung: spaetestens nach 3 s den Zustand schicken
    private func stateLater(_ L: CallLink) {
        Task { [weak self, weak L] in
            try? await Task.sleep(for: .seconds(3))
            guard let self, let L, L.stateDue, !L.gone else { return }
            L.stateDue = false; self.stateOut(L)
        }
    }
    /// Teilen beenden (Knopf, System, Auflegen): B - Spur weg, m-Zeile "recvonly" (bleibt fuer das naechste Teilen),
    /// Neuverhandlung; A - Kamera zurueck auf den Sender
    func stopScreen() {
        guard sharing || scrSource != nil else { capture.stop(); return }
        capture.sink.set(nil)
        capture.stop()
        sharing = false; shareCamPaused = false
        scrTrack?.isEnabled = false; scrTrack = nil; scrSource = nil
        CallEnv.log("screen stop")
        hornScreen()
        for L in links {
            let mode = L.scrMode
            L.scrMode = nil; L.paused = false; L.appliedVideo = nil; L.appliedScreen = nil
            guard let pc = L.pc, !ending, !L.gone else { continue }
            if mode == .track, let t = L.scrTr {
                var e: NSError?
                t.sender.track = nil
                t.setDirection(.recvOnly, error: &e)
                L.stateDue = true
                Task { await renegotiate(L, iceRestart: false) }
                stateLater(L)
            } else if mode == .replace, pc.connectionState != .closed {
                L.camTr?.sender.track = camera?.track
                stateOut(L)
            }
            applyLimits(L)
        }
    }

    // MARK: Verbindung, Pruefung

    func connectionChanged(_ L: CallLink, _ st: RTCPeerConnectionState) {
        guard L.pc != nil, !ending, !L.gone else { return }
        switch st {
        case .connected:
            if !L.connected {
                L.connected = true; L.connTask?.cancel()
                CallEnv.log("connected \(L.dev?.prefix(6) ?? "-")")
                if !connected {
                    connected = true; connectedAt = Date(); phase = .active; status = ""
                    CallTones.shared.stop()
                    CallSystem.shared.connected(self)
                    statsOn(); netOn()
                    screenOn()
                } else if phase != .active { phase = .active; status = ""; CallTones.shared.stop() }
                else { status = "" }
                stateOut(L)
                screenLink(L)                                               // 2.4: teile ich schon, bekommt die Verbindung den Bildschirm
                applyPlayback(L)
                relimitAll()
                hoCheck()
                syncPrimary()
            } else if L.reconnecting || !status.isEmpty {                    // 2.4: nach ICE-Restart wieder da
                L.reconnecting = false; L.restarts = 0; L.restartTask?.cancel(); status = ""
                L.appliedVideo = nil; L.appliedAudio = nil; L.appliedScreen = nil   // Pfad kann sich geaendert haben
                CallEnv.log("reconnected")
                syncPrimary()
            }
        case .disconnected:
            status = String(localized: "Verbindung unterbrochen – versuche es weiter …")
        case .failed:
            if CallV3.iceAction(state: "failed", everConnected: L.connected, canReneg: L.canReneg(myCaps), attempts: L.restarts) == .restart {
                if !L.reconnecting { iceRestart(L) }
            } else { linkFailed(L) }
        default: break
        }
    }
    /// 2.4: ICE-Zustand - "disconnected" laenger als 2,5 s bzw. "failed" -> ICE-Restart (nur mit reneg auf beiden Seiten)
    func iceChanged(_ L: CallLink, _ st: RTCIceConnectionState) {
        guard L.pc != nil, !ending, !L.reconnecting, !L.gone else { return }
        switch st {
        case .disconnected:
            guard CallV3.iceAction(state: "disconnected", everConnected: L.connected, canReneg: L.canReneg(myCaps), attempts: L.restarts) == .wait else { return }
            Task { [weak self, weak L] in
                try? await Task.sleep(for: .seconds(CallV3.restartDelay))
                guard let self, let L, !self.ending, !L.reconnecting, !L.gone, L.pc?.iceConnectionState == .disconnected else { return }
                self.iceRestart(L)
            }
        case .failed:
            if CallV3.iceAction(state: "failed", everConnected: L.connected, canReneg: L.canReneg(myCaps), attempts: L.restarts) == .restart { iceRestart(L) }
        default: break
        }
    }
    /// Verbindung kam nicht zustande bzw. ist weg: im Raum nur diese trennen, im 1:1 wie bisher beenden
    func linkFailed(_ L: CallLink) {
        if roomMode { dropLink(L, String(localized: "Verbindung zu \(L.name) verloren.")); return }
        iceFailed()
    }
    func linkError(_ L: CallLink, _ msg: String) {
        if roomMode { dropLink(L, msg); return }
        fail(msg)
    }
    private func iceFailed() {
        fail(ShareJSON.bool(cfg["turn"]) ? String(localized: "Keine Verbindung möglich – auch nicht über den TURN-Server. Bitte später erneut versuchen.")
             : String(localized: "Direktverbindung nicht möglich (z. B. strenge Firewall oder Mobilfunknetz). Der Admin kann einen TURN-Server eintragen."), reason: "failed")
    }
    /// DTLS-Zertifikat je Verbindung (Statistik) muss in den SIGNIERTEN Fingerabdruecken stehen; Weg direkt/Relais;
    /// Pegel der empfangenen Tonspur (Sprecher in der Kachelansicht: Takt 1 s, sonst 2 s)
    private func statsOn() {
        statsTask?.cancel()
        statsTask = Task { [weak self] in
            #if DEBUG
            var toggled = false
            #endif
            while !Task.isCancelled {
                guard let self, !self.ending else { return }
                var levels: [(device: String, level: Double, mic: Bool)] = []
                for L in self.links where !L.gone {
                    guard let pc = L.pc else { continue }
                    let rep = await pc.statsAsync()
                    guard !L.gone, !self.ending else { continue }
                    var byID: [String: RTCStatistics] = [:]
                    for (k, v) in rep.statistics { byID[k] = v }
                    let tr = rep.statistics.values.first { $0.type == "transport" }
                    if !L.verified, let cid = tr?.values["remoteCertificateId"] as? String, let cert = byID[cid],
                       let alg = cert.values["fingerprintAlgorithm"] as? String, let fp = cert.values["fingerprint"] as? String {
                        let c = CallLogic.certFingerprint(algorithm: alg, fingerprint: fp)
                        guard !L.fps.isEmpty, L.fps.contains(c) else { CallEnv.log("dtls-mismatch"); self.securityFail(L); continue }
                        L.verified = true
                        CallEnv.log("verified dtls")
                    }
                    // Pfad bei jedem Takt (Netzwechsel/ICE-Restart waehlt ein neues Paar) -> Relais-Hinweis und Bitrate-Regeln
                    if let pid = tr?.values["selectedCandidatePairId"] as? String, let pair = byID[pid] {
                        let l = (pair.values["localCandidateId"] as? String).flatMap { byID[$0] }, r = (pair.values["remoteCandidateId"] as? String).flatMap { byID[$0] }
                        L.relay = (l?.values["candidateType"] as? String) == "relay" || (r?.values["candidateType"] as? String) == "relay"
                        let p = CallV3.path(local: l?.values as [String: Any]?, remote: r?.values as [String: Any]?)
                        if p != L.path { L.path = p; CallEnv.log("pfad \(p?.rawValue ?? "-")"); self.stateOut(L) }   // path an das Gegenueber
                        self.applyLimits(L)
                    }
                    let au = CallLogic.AudioSample.from(rep.statistics.values.map { (type: $0.type, values: $0.values as [String: Any]) })
                    L.level = au.levelIn
                    if let d = L.dev, !L.own { levels.append((d, au.levelIn, L.remote.mic)) }
                    if L === self.primary {
                        self.meter.update(au, micOn: self.mic && self.audioTrack != nil)
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
                            CallEnv.log("stats audioIn=\(au.bytesIn) samplesIn=\(au.samplesIn) levelIn=\(String(format: "%.4f", au.levelIn)) energyIn=\(String(format: "%.5f", au.energyIn)) audioOut=\(au.bytesOut) micLevel=\(String(format: "%.4f", au.levelMic)) ton=\(self.meter.input) mikro=\(self.meter.mic) videoIn=\(v) framesDecoded=\(fr) playoutPeak=\(String(format: "%.3f", peak)) verified=\(L.verified) relay=\(L.relay) links=\(self.links.count)")
                        }
                        #endif
                    }
                }
                if self.meshView, self.speakerState.update(levels, now: Date().timeIntervalSince1970) { self.speakerDev = self.speakerState.device }
                self.syncPrimary()
                #if DEBUG
                if CallEnv.fakeMedia {
                    let ud = UserDefaults.standard, el = self.connectedAt.map { Date().timeIntervalSince($0) } ?? 0
                    if !toggled, let t = Double(ud.string(forKey: "hkCallToggleAt") ?? ""), el > t {   // nur fuer Tests: Stumm + Kamera aus
                        toggled = true; self.toggleMic(); if self.cam { self.toggleCam() }; CallEnv.log("toggled mic=\(self.mic) cam=\(self.cam)")
                    }
                    if let h = Double(ud.string(forKey: "hkCallHangupAfter") ?? ""), el > h { self.hangup(); return }
                }
                #endif
                try? await Task.sleep(for: .seconds(self.meshView ? 1 : 2))
            }
        }
    }
    /// Lebenszeichen mit dem eigenen Geraet - 2.4: leg "left" = dieses Geraet ist nicht mehr im Anruf (z. B. uebernommen)
    func aliveOn() {
        aliveTask?.cancel()
        let s = aliveSeconds
        aliveTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(s))
                guard let self, let id = self.id, let store = self.store, !self.ending else { return }
                var b: [String: Any] = [:]
                if let d = self.myDevice { b["device"] = d }
                guard let (d, c) = try? await store.apiResponse("api/calls/\(id)/alive", method: "POST", body: b), c == 200, let j = ShareJSON.obj(d) else { continue }
                if (j["state"] as? String) == "ended" { self.remoteEnded(reason: j["reason"] as? String); return }
                if (j["leg"] as? String) == "left" { self.finish(nil, message: String(localized: "Dieses Gerät ist nicht mehr im Anruf.")); return }
            }
        }
    }

    // MARK: Bedienung

    func toggleMic() {
        if audioTrack == nil && !mic {                                      // 2.4: Begleitgeraet - Mikrofon erst jetzt
            Task { await micOn() }
            return
        }
        mic.toggle(); audioTrack?.isEnabled = mic
        CallSystem.shared.muted(self, !mic)
        stateOut()
        hornDraw()                                                          // 2.5: eigene Kachel (Stumm-Zeichen)
    }
    /// Mikrofon einschalten (Begleitgeraet bzw. Uebernahme): Erlaubnis, Tonspur anlegen, auf alle Verbindungen
    func micOn() async {
        if audioTrack == nil {
            if !CallEnv.fakeMedia {
                guard await AVCaptureDevice.requestAccess(for: .audio) else { toast = String(localized: "Mikrofon nicht erlaubt."); return }
            }
            guard busy else { return }
            mic = true
            makeAudioTrack()
            for L in links { if let a = audioTrack, let t = L.audioTr { t.sender.track = a } }
            hornMic()
        }
        mic = true; audioTrack?.isEnabled = true
        CallSystem.shared.muted(self, false)
        stateOut()
    }
    /// von CallKit (Stummschalten in der System-Oberflaeche)
    func setMuted(_ m: Bool) { if mic == m { toggleMic() } }
    /// 2.4: Wiedergabe an/aus (Begleitgeraet startet stumm)
    func toggleSound() { sound.toggle(); applyPlayback() }
    func toggleCam() {
        if let c = camera {
            c.stop(); camera = nil; localVideo = nil; cam = false
            for L in links where !(sharing && L.scrMode == .replace) { L.camTr?.sender.track = nil }   // A: dort laeuft gerade der Bildschirm
            hornCam()
            stateOut(); return
        }
        Task {
            if !CallEnv.fakeMedia { guard await AVCaptureDevice.requestAccess(for: .video) else { toast = String(localized: "Kamera nicht erlaubt."); return } }
            guard busy, let c = CallCamera(factory: CallRTC.factory) else { toast = String(localized: "Keine Kamera verfügbar."); return }
            camera = c; localVideo = c.track; cam = true; frontCamera = c.front; wire(c)
            for L in links where !(sharing && L.scrMode == .replace) { L.camTr?.sender.track = c.track }   // A: Kamera erst nach dem Teilen
            hornCam()
            relimitAll()
            stateOut()
        }
    }
    /// Kamera-Unterbrechung (PiP/Hintergrund ohne Multitasking-Freigabe) ans Gegenueber melden
    private func wire(_ c: CallCamera) {
        camPaused = false
        c.onInterrupt = { [weak self] on in self?.cameraInterrupted(on) }
    }
    #if os(iOS)
    func switchCamera() { camera?.switchCamera(); frontCamera = camera?.front ?? true; hornDraw() }
    var canSwitchCamera: Bool { camera?.canSwitch ?? false }
    func toggleSpeaker() {
        speaker.toggle()
        CallAudio.route(speaker: speaker, video: media == "video")
    }
    #endif

    /// bye an alle Verbindungen, die es melden (vor /end bzw. /leave; 2.3-Apps bekommen es nicht)
    func byes(reason: String = "hangup", except: String? = nil) -> [String: String] {
        guard let dev = myDevice else { return [:] }
        var b: [String: String] = [:]
        for L in links where L.established && !L.gone && CallV3.canBye(L.peerCaps) && L.dev != except {
            for d in L.targets { if let x = seal(L, d, CallV3.byeBody(pc: L.pcFor(dev, d), reason: reason)) { b[d] = x } }
        }
        return b
    }
    /// Auflegen (Knopf, CallKit). 2.4: mit device - Begleitgeraet bzw. Gruppe: nur dieses Geraet verlaesst den Anruf;
    /// Hauptgeraet im 1:1: Ende fuer alle (zum Weitermachen erst auf dem anderen Geraet uebernehmen)
    func hangup() {
        if phase == .incoming { decline(); return }
        guard busy || phase == .preparing else { return }
        if let id, !ending {
            let c = connected
            let bye = ready ? byes() : [:]
            let dev = myDevice
            Task {
                if !bye.isEmpty, let dev { _ = try? await store?.apiResponse("api/calls/\(id)/signal", method: "POST", body: ["device": dev, "signals": bye]) }
                var b: [String: Any] = ["reason": "hangup", "connected": c]
                if let dev { b["device"] = dev }
                _ = try? await store?.apiResponse("api/calls/\(id)/end", method: "POST", body: b)
            }
        }
        finish(.ended(dur: connectedAt.map { Int(Date().timeIntervalSince($0)) }))
    }
    func fail(_ msg: String, reason: String = "hangup") {
        guard phase != .idle, !ending else { return }
        if let id {
            let c = connected
            var b: [String: Any] = ["reason": reason == "failed" ? "failed" : "hangup", "connected": c]
            if let dev = myDevice { b["device"] = dev }
            Task { _ = try? await store?.apiResponse("api/calls/\(id)/end", method: "POST", body: b) }
        }
        CallEnv.log("failed: \(reason)")
        failed = true
        finish(nil, message: msg)
    }
    func securityFail(_ L: CallLink? = nil) {
        if let L, roomMode {                                                // 2.4: nur diese Verbindung trennen
            dropLink(L, String(localized: "\(L.name): Sicherheitsprüfung fehlgeschlagen – Verbindung getrennt."))
            return
        }
        fail(String(localized: "Sicherheitsprüfung fehlgeschlagen: Die Signatur bzw. der Schlüssel der Gegenseite passt nicht. Der Anruf wurde abgebrochen – möglicherweise versucht jemand mitzuhören. Prüfe die Sicherheitsnummer im Chat."), reason: "failed")
    }
    func remoteEnded(reason: String?) {
        guard phase != .idle, !ending else { return }
        CallEnv.log("remote ended \(reason ?? "-")")
        if reason == "elsewhere" { finish(.elsewhere); return }
        finish(CallLogic.endNote(reason: reason, role: kind == "group" ? "member" : role, connectedFor: connectedAt.map { Int(Date().timeIntervalSince($0)) }))
    }

    /// Alles abbauen; Hinweis kurz stehen lassen, dann zu
    func finish(_ note: CallLogic.EndNote?, message: String? = nil) {
        guard !ending else { return }
        ending = true
        let cid = id ?? incoming?.id
        if let cid { done.insert(cid) }
        aliveTask?.cancel(); statsTask?.cancel(); hoTask?.cancel()
        net?.cancel(); net = nil
        #if os(iOS)
        CallPiP.shared.stop()
        #else
        CallPanel.shared.close()
        #endif
        CallTones.shared.stop()
        // 2.4 E3: Teilen endet mit dem Anruf (auch bei Verlust der Verbindung)
        capture.sink.set(nil); capture.stop()
        #if os(iOS)
        capture.close()
        #endif
        sharing = false; scrTrack = nil; scrSource = nil; shareCamPaused = false; screenHint = false
        camera?.stop(); camera = nil
        for L in links { L.close() }
        hornStop()                                                          // 2.5: Beine am Knoten schliessen
        links = []; tiles = []; meshView = false; speakerDev = nil; handingOver = false
        remoteSide = nil
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
        runSoon()                                                           // laeuft der Anruf auf anderen Geraeten weiter -> Leiste
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

    func reset() {
        for L in links { L.close() }
        hornStop(); horn = nil; topo = ""; meshNoted = false; callHost = false
        id = nil; chatID = 0; peer = ""; links = []; seen = []; early = []
        v2 = nil; roomHandle = nil
        ready = false; connected = false; ending = false; answered = false; keysRefreshed = []; failed = false
        mic = true; cam = false; remote = CallRemoteState(); verified = false; relay = false; connectedAt = nil; speaker = false
        meter = CallLogic.AudioMeter(); audioIn = .unknown; audioMic = .unknown
        localVideo = nil; remoteVideo = nil; incoming = nil; minimized = false
        reconnecting = false; path = nil
        net?.cancel(); net = nil; lastNet = nil; camPaused = false; chain = nil; pip = false; wantOut = PiPLogic.WantSender()
        sharing = false; scrSource = nil; scrTrack = nil; scrSid = ""
        remoteSide = nil; swapView = false; shareCamPaused = false; screenHint = false
        // 2.4 E4/E5
        kind = "dm"; dmRoom = false; mutedBy = nil; companion = false; sound = true; meshView = false; tiles = []; speakerDev = nil; handingOver = false
        limit = CallRoom.videoMax; oldNames = []; inRoom = false
        roster.reset(); ho = CallRoom.Handover(); hoTask?.cancel(); hoTask = nil; joinRole = ""; title = ""
        pend = [:]; admitting = []; badDevs = []; keysBy = [:]; speakerState = CallRoom.Speaker()
    }
    func signedOut() { if busy { hangup() }; enabled = true; done = []; capsSent = ""; runBars = [] }
}

extension RTCRtpTransceiver {
    /// mid erst nach der Aushandlung bekannt (vorher leer)
    var midOrNil: String? { let m: String? = mid; return m?.isEmpty == false ? m : nil }
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
