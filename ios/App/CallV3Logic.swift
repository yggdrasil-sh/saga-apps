import Foundation

// Anrufe 2.4 (saga-call v3, Etappe E0) und Bild-im-Bild: reine Logik ohne WebRTC/UI (mit swiftc pruefbar).
// Konzept 2.4 §1.3/§3: neue Felder im (signierten, versiegelten) Body - pc, caps, n; t-Typen offer/answer/ice/state/bye;
// state.want.video off|small|large. Neuverhandlung (Perfect Negotiation, ICE-Restart) nur, wenn das Gegenueber
// "reneg" in caps gemeldet hat - gegenueber 2.3-Apps bleibt das Verhalten exakt wie bisher.

enum CallV3 {
    /// Faehigkeiten dieses Geraets (gehen in offer/answer mit)
    static let myCaps = ["reneg", "pip_hint"]
    /// Neuverhandlung (Angebote waehrend des Gespraechs, ICE-Restart) nur mit einem Gegenueber, das "reneg" meldet
    static func canReneg(_ peerCaps: [String]?) -> Bool { peerCaps?.contains("reneg") == true }

    enum Want: String, Equatable { case off, small, large }

    /// Zusatzfelder eines Signal-Bodys (2.3-Bodys: alles nil)
    struct Meta: Equatable {
        var pc: String?
        var caps: [String]?
        var n: Int?
        var pip: Bool?
        var want: Want?
    }
    static func meta(_ body: String) -> Meta {
        guard let j = (try? JSONSerialization.jsonObject(with: Data(body.utf8))) as? [String: Any] else { return Meta() }
        var m = Meta()
        if let p = j["pc"] as? String, !p.isEmpty, p.count <= 64 { m.pc = p }
        if let c = j["caps"] as? [Any] { m.caps = Array(c.compactMap { $0 as? String }.prefix(32)) }
        if let n = j["n"] as? NSNumber, CFGetTypeID(n) != CFBooleanGetTypeID() { m.n = n.intValue }
        if let p = j["pip"] as? Bool { m.pip = p }
        if let w = j["want"] as? [String: Any], let v = w["video"] as? String { m.want = Want(rawValue: v) }
        return m
    }

    // MARK: Bodys (Felder von 2.3 bleiben gleich, neue kommen dazu - aeltere Apps lesen nur t/sdp/media/fp/c/mic/cam/screen)

    static func offerBody(sdp: String, media: String, pc: String, n: Int) -> String {
        CallLogic.json(["t": "offer", "sdp": sdp, "media": media, "fp": CallLogic.sdpFingerprints(sdp), "pc": pc, "caps": myCaps, "n": n])
    }
    static func answerBody(sdp: String, pc: String, n: Int) -> String {
        CallLogic.json(["t": "answer", "sdp": sdp, "fp": CallLogic.sdpFingerprints(sdp), "pc": pc, "caps": myCaps, "n": n])
    }
    static func iceBody(_ c: [CallLogic.Candidate], pc: String) -> String {
        CallLogic.json(["t": "ice", "c": c.prefix(CallLogic.iceBatch).map(\.dict), "pc": pc])
    }
    static func stateBody(mic: Bool, cam: Bool, screen: Bool, pip: Bool, want: Want) -> String {
        CallLogic.json(["t": "state", "mic": mic, "cam": cam, "screen": screen, "pip": pip, "want": ["video": want.rawValue]])
    }
    static func byeBody(pc: String) -> String { CallLogic.json(["t": "bye", "pc": pc]) }

    /// Gehoert ein Signal zu unserer PeerConnection? Ohne pc (2.3) bzw. vor der Festlegung: ja
    static func samePC(_ signal: String?, _ mine: String?) -> Bool { signal == nil || mine == nil || signal == mine }

    // MARK: Perfect Negotiation

    /// Hoeflich ("polite") ist die lexikographisch groessere device_id (Konzept §1.3)
    static func polite(me: String, peer: String) -> Bool { me > peer }

    /// Zustandsmaschine nach W3C "Perfect Negotiation": n = laufende Nummer je Absender. Bei Zusammenstoss (beide bieten
    /// gleichzeitig an) verwirft die unhoefliche Seite das fremde Angebot, die hoefliche nimmt ihr eigenes zurueck.
    struct Negotiator: Equatable {
        var polite = false
        private(set) var makingOffer = false
        /// n meines letzten Angebots bzw. des noch offenen (auf Antwort wartend)
        private(set) var localN = 0
        private(set) var pendingN: Int?
        /// hoechstes n eines Angebots des Gegenuebers (aeltere/doppelte werden verworfen)
        private(set) var remoteN = 0
        /// zuletzt ein fremdes Angebot verworfen (Zusammenstoss, ich bin unhoeflich)
        private(set) var ignoredOffer = false

        init(polite: Bool = false) { self.polite = polite }

        enum OfferAction: Equatable { case ignore, stale, accept(rollback: Bool) }
        enum AnswerAction: Equatable { case apply, ignore }

        /// Eigenes Angebot beginnt: liefert dessen n
        mutating func beginOffer() -> Int { makingOffer = true; localN += 1; return localN }
        /// Angebot gesetzt und verschickt -> wartet auf Antwort
        mutating func offerSent(_ n: Int) { makingOffer = false; pendingN = n }
        mutating func offerFailed() { makingOffer = false; pendingN = nil }
        /// Erstes Angebot (Anrufer, vor dem Klingeln)
        mutating func initialOffer() -> Int { let n = beginOffer(); offerSent(n); return n }
        /// Erstes Angebot des Gegenuebers angenommen (Angerufener)
        mutating func initialRemote(_ n: Int?) { remoteN = max(remoteN, n ?? 0) }

        /// Angebot des Gegenuebers; stable = Signalisierungszustand "stable" der PeerConnection
        mutating func remoteOffer(n: Int?, stable: Bool) -> OfferAction {
            if let n, n <= remoteN { return .stale }
            let collision = makingOffer || !stable
            ignoredOffer = !polite && collision
            if ignoredOffer { return .ignore }
            if let n { remoteN = n }
            if collision { makingOffer = false; pendingN = nil; return .accept(rollback: true) }
            return .accept(rollback: false)
        }
        /// Antwort des Gegenuebers; haveLocalOffer = Zustand "have-local-offer". Ohne n (2.3): nur das erste Angebot
        mutating func remoteAnswer(n: Int?, haveLocalOffer: Bool) -> AnswerAction {
            guard haveLocalOffer, let p = pendingN else { return .ignore }
            if let n, n != p { return .ignore }
            pendingN = nil
            return .apply
        }
    }

    // MARK: ICE-Restart

    enum IceAction: Equatable { case none, wait, restart, fail }
    /// Verbindungszustand -> Reaktion. Ohne "reneg" beim Gegenueber wie 2.3 (unterbrochen: warten, fehlgeschlagen: Ende).
    /// Vor der ersten Verbindung bleibt es beim alten Ablauf (Zeitgrenze/Fehler).
    static let maxRestarts = 3
    static let restartDelay = 3.0
    static let restartTimeout = 20.0
    static func iceAction(state: String, everConnected: Bool, canReneg: Bool, attempts: Int) -> IceAction {
        switch state {
        case "failed":
            return everConnected && canReneg && attempts < maxRestarts ? .restart : .fail
        case "disconnected":
            return everConnected && canReneg && attempts < maxRestarts ? .wait : .none
        default: return .none
        }
    }

    /// Netz (NWPathMonitor): erreichbar + Art der bevorzugten Schnittstelle (wifi, cellular, wired, other)
    struct NetPath: Equatable { var satisfied: Bool; var primary: String? }
    /// Netzwechsel WLAN <-> LTE bzw. Netz wieder da -> ICE-Restart. Erste Meldung (Start) loest nichts aus.
    static func netChangeNeedsRestart(old: NetPath?, new: NetPath) -> Bool {
        guard let old, new.satisfied else { return false }
        return !old.satisfied || old.primary != new.primary
    }

    // MARK: Pfad und Bitrate (Tabelle §1.3)

    enum Path: String, Equatable { case direct, relayUDP, relayTCP }
    /// Gewaehltes Kandidatenpaar (getStats: local-candidate/remote-candidate) -> Pfad. TCP ohne Relais (ICE-TCP) hat
    /// dieselben Probleme wie TURN/TLS (Head-of-Line-Blocking) und bekommt dieselben Grenzen.
    static func path(local: [String: Any]?, remote: [String: Any]?) -> Path? {
        guard let local else { return nil }
        let lt = local["candidateType"] as? String, rt = remote?["candidateType"] as? String
        let proto = (local["protocol"] as? String)?.lowercased()
        if lt == "relay" {
            let rp = (local["relayProtocol"] as? String)?.lowercased()
            return rp == "tcp" || rp == "tls" ? .relayTCP : .relayUDP
        }
        if proto == "tcp" { return .relayTCP }
        return rt == "relay" ? .relayUDP : .direct
    }

    enum Kind: Equatable { case camera, screen }
    struct Limits: Equatable {
        var maxBitrate: Int
        var scaleDown: Double
        var maxFramerate: Int
        var maintainResolution = false
        var active = true
    }
    /// Grenzen fuer einen Video-Sender. streams = Gegenstellen, an die dieses Geraet sendet (1:1 = 1, Mesh n>=3 -> >=2),
    /// want = Wunsch des Gegenuebers (PiP: small), sourceHeight = kurze Seite der Quelle (Kamera 720, Testbild 360).
    static func videoLimits(path: Path, kind: Kind, streams: Int, want: Want, sourceHeight: Int) -> Limits {
        let src = Double(max(1, sourceHeight))
        func scale(_ target: Double) -> Double { max(1, (src / target * 100).rounded() / 100) }
        let mesh = streams >= 2
        var l: Limits
        switch (kind, path) {
        case (.screen, .direct): l = Limits(maxBitrate: 2_500_000, scaleDown: scale(1080), maxFramerate: 15)
        case (.screen, .relayUDP): l = Limits(maxBitrate: 1_800_000, scaleDown: scale(1080), maxFramerate: 15)
        case (.screen, .relayTCP): l = Limits(maxBitrate: 1_200_000, scaleDown: scale(1080), maxFramerate: 10, maintainResolution: true)
        case (.camera, .direct): l = mesh ? Limits(maxBitrate: 600_000, scaleDown: scale(540), maxFramerate: 30) : Limits(maxBitrate: 1_500_000, scaleDown: scale(720), maxFramerate: 30)
        case (.camera, .relayUDP): l = mesh ? Limits(maxBitrate: 500_000, scaleDown: scale(540), maxFramerate: 30) : Limits(maxBitrate: 1_200_000, scaleDown: scale(720), maxFramerate: 30)
        case (.camera, .relayTCP): l = mesh ? Limits(maxBitrate: 350_000, scaleDown: scale(360), maxFramerate: 15) : Limits(maxBitrate: 800_000, scaleDown: scale(540), maxFramerate: 30)
        }
        if kind == .screen { l.maintainResolution = true }                 // Text bleibt lesbar, PiP-Wunsch aendert nichts
        guard kind == .camera else { return l }
        switch want {
        case .large: break
        case .small:                                                        // Gegenstelle im PiP: <= 300 (Mesh 200) kbit/s, 1/2 bzw. 1/3
            l.maxBitrate = min(l.maxBitrate, mesh ? 200_000 : 300_000)
            l.scaleDown = max(l.scaleDown, path == .relayTCP ? 3 : 2)
        case .off: l.active = false
        }
        return l
    }
    /// Opus: 32 kbit/s, ueber TCP/TLS 24 kbit/s (FEC ist bei Opus in WebRTC Standard)
    static func audioBitrate(path: Path) -> Int { path == .relayTCP ? 24_000 : 32_000 }
}

// MARK: - Bild-im-Bild: Zustand und want-Signal (iOS-PiP bzw. Mac-Schwebefenster)

enum PiPLogic {
    /// Gegenueber bitten: im PiP/Schwebefenster kleines Bild, sonst gross
    static func want(pip: Bool) -> CallV3.Want { pip ? .small : .large }
    /// PiP moeglich: Anruf verbunden bzw. im Aufbau und ein Fernbild ist da
    static func available(phase: String, hasRemoteVideo: Bool) -> Bool {
        (phase == "connecting" || phase == "active") && hasRemoteVideo
    }
    /// iPhone/iPad: PiP beim Verkleinern (Leiste) bzw. beim Wechsel in den Hintergrund starten, beim Zurueckkehren beenden
    static func iosWantsPiP(phase: String, hasRemoteVideo: Bool, minimized: Bool, background: Bool) -> Bool {
        available(phase: phase, hasRemoteVideo: hasRemoteVideo) && (minimized || background)
    }
    /// Mac: Schwebefenster, wenn das Anruffenster verkleinert/verdeckt ist (oder per Knopf verkleinert wurde)
    static func macPanelShows(phase: String, video: Bool, callWindowVisible: Bool) -> Bool {
        CallLogic.isOngoing(phase) && phase != "preparing" && video && !callWindowVisible
    }
    /// want nur bei Aenderung senden (ein state-Signal je Wechsel)
    struct WantSender: Equatable {
        private(set) var last = CallV3.Want.large
        mutating func update(_ w: CallV3.Want) -> Bool { guard w != last else { return false }; last = w; return true }
    }
}
