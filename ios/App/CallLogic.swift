import Foundation

// Anrufe (Saga 2.1): reine Logik ohne WebRTC, Krypto und UI (mit swiftc pruefbar) - Signal-Inhalte (offer/answer/ice/
// state), DTLS-Fingerabdruecke aus der SDP, Gruende/Ergebnisse, Chat-Eintraege kind "call", VoIP-Push, Kennungen.
// Ablauf und Vorgaben wie static/calls.js (Web-Client) und saga/calls.py (Server).

enum CallLogic {
    static let format = "saga-call/v1"
    /// ICE-Kandidaten je Signal hoechstens (Server-Grenze fuer die Groesse), Sammelpause wie im Web
    static let iceBatch = 40
    static let iceDelay = 0.12
    /// Nach dem Annehmen ohne Verbindung -> Meldung "Direktverbindung nicht moeglich"
    static let connectTimeout = 30.0

    // MARK: JSON-Text wie JSON.stringify (fuer "inner" und Signal-Inhalte)

    static func jsonString(_ s: String) -> String {
        var out = "\""
        for u in s.unicodeScalars {
            switch u {
            case "\"": out += "\\\""
            case "\\": out += "\\\\"
            case "\n": out += "\\n"
            case "\r": out += "\\r"
            case "\t": out += "\\t"
            case "\u{08}": out += "\\b"
            case "\u{0C}": out += "\\f"
            default:
                if u.value < 0x20 { out += String(format: "\\u%04x", u.value) } else { out.unicodeScalars.append(u) }
            }
        }
        return out + "\""
    }
    static func json(_ o: [String: Any]) -> String {
        guard let d = try? JSONSerialization.data(withJSONObject: o, options: [.sortedKeys, .withoutEscapingSlashes]) else { return "{}" }
        return String(data: d, encoding: .utf8) ?? "{}"
    }

    // MARK: DTLS-Fingerabdruecke

    /// Sortierte Liste "<alg-klein> <HEX-GROSS>" aller a=fingerprint-Zeilen (ohne Doppelte), wie sdpFingerprints im Web
    static func sdpFingerprints(_ sdp: String) -> [String] {
        var set = Set<String>()
        for raw in sdp.split(omittingEmptySubsequences: true, whereSeparator: { $0 == "\n" || $0 == "\r\n" }) {
            var line = String(raw)
            while let l = line.last, l == "\r" || l == " " || l == "\t" { line.removeLast() }
            guard line.hasPrefix("a=fingerprint:") else { continue }
            let rest = line.dropFirst("a=fingerprint:".count)
            let parts = rest.split(separator: " ", maxSplits: 1, omittingEmptySubsequences: false)
            guard parts.count == 2, !parts[0].isEmpty, !parts[0].contains(where: \.isWhitespace) else { continue }
            let fp = parts[1]
            guard !fp.isEmpty, fp.allSatisfy({ hex($0) || $0 == ":" }) else { continue }
            set.insert(parts[0].lowercased() + " " + fp.uppercased())
        }
        return set.sorted()
    }
    /// Angebot/Antwort: mitgeschickte (signierte) Liste muss genau zur SDP passen und darf nicht leer sein
    static func fingerprintsMatch(sdp: String, fp: [String]) -> Bool {
        let s = sdpFingerprints(sdp)
        return !s.isEmpty && s == fp.sorted()
    }
    /// Zertifikat des Gegenuebers aus den Statistiken (fingerprintAlgorithm, fingerprint) -> Vergleichsform
    static func certFingerprint(algorithm: String, fingerprint: String) -> String {
        algorithm.lowercased() + " " + fingerprint.uppercased()
    }

    // MARK: Signal-Inhalte

    struct Candidate: Equatable {
        var candidate: String; var sdpMid: String?; var sdpMLineIndex: Int32
        /// 2.4 (saga-call v3): ICE-ufrag des Kandidaten (Generation nach ICE-Restart)
        var usernameFragment: String? = nil
        var dict: [String: Any] {
            var d: [String: Any] = ["candidate": candidate, "sdpMLineIndex": Int(sdpMLineIndex)]
            if let sdpMid { d["sdpMid"] = sdpMid }
            if let usernameFragment { d["usernameFragment"] = usernameFragment }
            return d
        }
    }
    enum Signal: Equatable {
        case offer(sdp: String, media: String, fp: [String])
        case answer(sdp: String, fp: [String])
        case ice([Candidate])
        case state(mic: Bool, cam: Bool, screen: Bool)
        /// 2.4 (saga-call v3): ein Endgeraet verlaesst den Anruf
        case bye
        case other(String)
    }
    static func offerBody(sdp: String, media: String) -> String { json(["t": "offer", "sdp": sdp, "media": media, "fp": sdpFingerprints(sdp)]) }
    static func answerBody(sdp: String) -> String { json(["t": "answer", "sdp": sdp, "fp": sdpFingerprints(sdp)]) }
    static func iceBody(_ c: [Candidate]) -> String { json(["t": "ice", "c": c.prefix(iceBatch).map(\.dict)]) }
    static func stateBody(mic: Bool, cam: Bool, screen: Bool) -> String { json(["t": "state", "mic": mic, "cam": cam, "screen": screen]) }

    /// Inhalt (nach Pruefung der Signatur) lesen; nil = kein JSON
    static func parse(_ body: String) -> Signal? {
        guard let j = (try? JSONSerialization.jsonObject(with: Data(body.utf8))) as? [String: Any], let t = j["t"] as? String else { return nil }
        let fp = (j["fp"] as? [Any] ?? []).compactMap { $0 as? String }
        switch t {
        case "offer":
            guard let sdp = j["sdp"] as? String else { return nil }
            return .offer(sdp: sdp, media: (j["media"] as? String) == "video" ? "video" : "audio", fp: fp)
        case "answer":
            guard let sdp = j["sdp"] as? String else { return nil }
            return .answer(sdp: sdp, fp: fp)
        case "ice":
            let list = (j["c"] as? [Any] ?? []).prefix(60).compactMap { v -> Candidate? in
                guard let d = v as? [String: Any], let c = d["candidate"] as? String, !c.isEmpty else { return nil }
                return Candidate(candidate: c, sdpMid: d["sdpMid"] as? String, sdpMLineIndex: Int32((d["sdpMLineIndex"] as? NSNumber)?.intValue ?? 0),
                                 usernameFragment: (d["usernameFragment"] as? String).flatMap { $0.isEmpty ? nil : $0 })
            }
            return .ice(list)
        case "state":
            return .state(mic: (j["mic"] as? Bool) != false, cam: (j["cam"] as? Bool) == true, screen: (j["screen"] as? Bool) == true)
        case "bye": return .bye
        default: return .other(t)                                        // unbekannte t-Typen: ignorieren (Aufrufer)
        }
    }

    // MARK: Ende, Gruende, Dauer

    /// Was die Person beim Ende sieht (Text in CallViews)
    enum EndNote: Equatable { case ended(dur: Int?), declined, busy, missed, unanswered, cancelled, failed, lost, gone, elsewhere }
    /// Grund vom Server (call.state/alive) aus Sicht dieses Geraets
    static func endNote(reason: String?, role: String, connectedFor: Int?) -> EndNote {
        switch reason {
        case "declined": return role == "caller" ? .declined : .ended(dur: nil)
        case "busy": return .busy
        case "timeout": return role == "caller" ? .missed : .unanswered
        case "cancelled": return role == "callee" ? .cancelled : .ended(dur: nil)
        case "failed": return .failed
        case "lost": return .lost
        case "gone": return .gone
        default: return .ended(dur: connectedFor)
        }
    }
    /// Nach der POST-Antwort von /api/calls: Anruf noch gewollt? Nein, wenn waehrend "Verbinde ..." aufgelegt wurde
    /// (ending / Phase nicht mehr "preparing") oder inzwischen ein anderer Anruf laeuft - dann nicht klingeln lassen.
    static func dialStillWanted(ending: Bool, preparing: Bool, sameCall: Bool) -> Bool { !ending && preparing && sameCall }

    /// Eingehender Anruf nach dem Abfragen seines Zustands: derselbe Anruf klingelt/laeuft schon (doppelte Meldung
    /// Live + Nachholen) -> nichts tun; sonst im Gespraech -> besetzt; sonst klingeln
    enum RingDecision: Equatable { case ignore, busy, ring }
    static func ringDecision(sameCallActive: Bool, busy: Bool) -> RingDecision { sameCallActive ? .ignore : busy ? .busy : .ring }

    /// Grund fuer das System (CallKit-Anrufliste): auf anderem Geraet angenommen ist kein "Gegenueber hat aufgelegt"
    enum SystemEnd: Equatable { case failed, remoteEnded, answeredElsewhere }
    static func systemEnd(_ note: EndNote?, failed: Bool) -> SystemEnd {
        if failed { return .failed }
        return note == .elsewhere ? .answeredElsewhere : .remoteEnded
    }

    /// Mac: Anruf-Fenster oeffnen nur beim Beginn eines Anrufs (aus idle/ended) und beim Annehmen (incoming -> connecting),
    /// nicht bei jedem Zustandswechsel. Phasen wie CallManager.Phase (rawValue).
    static func macOpensCallWindow(from old: String, to new: String) -> Bool {
        if new == "idle" || new == old { return false }
        if old == "idle" || old == "ended" { return true }
        return old == "incoming" && new == "connecting"
    }

    // MARK: Anruf verkleinern (2.3.1): zurueck in die App, Leiste oben, Hinweis im Chat

    /// Phasen, in denen ein Anruf laeuft bzw. aufgebaut wird (verkleinerbar; Leiste "Anruf laeuft")
    static func isOngoing(_ phase: String) -> Bool { ["preparing", "ringingOut", "connecting", "active"].contains(phase) }
    /// iPhone/iPad: Vollbild zeigen? Klingeln mit CallKit zeigt das System; verkleinert nur die Leiste. Endet ein
    /// verkleinerter Anruf, springt das Vollbild nicht mehr auf (Hinweis kommt als Toast).
    static func showsFullScreen(phase: String, minimized: Bool, callKit: Bool) -> Bool {
        if phase == "idle" || (callKit && phase == "incoming") { return false }
        if phase == "incoming" { return true }
        return !minimized
    }
    /// Leiste "Anruf laeuft - tippen zum Zurueckkehren": iPhone/iPad nur verkleinert, Mac immer (eigenes Fenster)
    static func showsCallBar(phase: String, minimized: Bool, separateWindow: Bool) -> Bool {
        isOngoing(phase) && (minimized || separateWindow)
    }
    /// Hinweis im Chat: der laufende Anruf gehoert zu diesem Chat
    static func chatHasCall(phase: String, callChat: Int, chatID: Int) -> Bool { isOngoing(phase) && callChat != 0 && callChat == chatID }

    /// Mitteilung angetippt: Anruf-Mitteilung -> Anruf; alles andere -> Ziel oeffnen. Laeuft ein Anruf im Vollbild,
    /// erst verkleinern (bzw. den Endhinweis schliessen) und DANN navigieren - Navigation unter einem offenen Vollbild
    /// brachte die App zum Absturz.
    enum TapRoute: Equatable { case call(String), open(minimizeFirst: Bool) }
    static func tapRoute(kind: String?, url: String?, call: String?, phase: String, minimized: Bool, callKit: Bool) -> TapRoute {
        if kind == "call" || callFromURL(url) != nil {
            if let c = call ?? callFromURL(url), isCallID(c) { return .call(c) }
        }
        return .open(minimizeFirst: showsFullScreen(phase: phase, minimized: minimized, callKit: callKit))
    }
    /// Ziel "Chat": liegt der Chat-Bereich noch nicht vorne, erst den Bereich wechseln und den Chat danach oeffnen
    /// (beides in einem Schritt schob zwei Navigationsstapel gleichzeitig)
    static func chatOpenNeedsTwoSteps(chatsVisible: Bool) -> Bool { !chatsVisible }

    /// 65 -> "1:05", 3725 -> "1:02:05"
    static func duration(_ s: Int) -> String {
        let v = max(0, s)
        return v >= 3600 ? String(format: "%d:%02d:%02d", v / 3600, v / 60 % 60, v % 60) : String(format: "%d:%02d", v / 60, v % 60)
    }

    // MARK: Kennungen

    /// 32 Hex-Zeichen <-> UUID (CallKit braucht eine UUID; gleiche Kennung auf allen Wegen)
    static func uuid(_ callID: String) -> UUID? {
        let h = callID.lowercased()
        guard h.count == 32, h.allSatisfy(hex) else { return nil }
        let a = Array(h)
        let s = String(a[0..<8]) + "-" + String(a[8..<12]) + "-" + String(a[12..<16]) + "-" + String(a[16..<20]) + "-" + String(a[20..<32])
        return UUID(uuidString: s)
    }
    static func callID(_ u: UUID) -> String { u.uuidString.replacingOccurrences(of: "-", with: "").lowercased() }
    static func isCallID(_ s: String?) -> Bool { guard let s else { return false }; return s.count == 32 && s.allSatisfy { hex($0) && !$0.isUppercase } }
    /// nur ASCII 0-9 a-f A-F (isHexDigit kennt auch Vollbreiten-Zeichen)
    static func hex(_ c: Character) -> Bool { c.isASCII && c.isHexDigit }

    // MARK: Chat-Eintrag (kind "call", unverschluesselte Metadaten)

    struct Entry: Equatable {
        var call: String; var media: String; var status: String; var dur: Int?; var from: String; var to: String
        init?(json d: Data) {
            guard let j = (try? JSONSerialization.jsonObject(with: d)) as? [String: Any], (j["type"] as? String) == "call" else { return nil }
            call = j["call"] as? String ?? ""; media = (j["media"] as? String) == "video" ? "video" : "audio"
            status = j["status"] as? String ?? "ended"; dur = (j["dur"] as? NSNumber)?.intValue
            from = j["from"] as? String ?? ""; to = j["to"] as? String ?? ""
        }
        enum Kind: Equatable { case ended(String?), missed, declined, busy, failed }
        /// Aus Sicht von `me`: ausgehend/eingehend + Ergebnis
        func kind() -> Kind {
            switch status {
            case "missed": return .missed
            case "declined": return .declined
            case "busy": return .busy
            case "failed": return .failed
            default: return .ended(dur.map(CallLogic.duration))
            }
        }
        func outgoing(me: String) -> Bool { from == me }
    }

    // MARK: Mitteilung (VoIP-Push bzw. Art "call")

    struct Push: Equatable { var call: String; var chat: Int?; var media: String; var caller: String? }
    static func push(_ info: [AnyHashable: Any]) -> Push? {
        guard (info["kind"] as? String) == "call", let c = info["call"] as? String, isCallID(c) else { return nil }
        let chat = (info["chat"] as? NSNumber)?.intValue ?? (info["chat"] as? String).flatMap { Int($0) }
        let caller = (info["caller"] as? String).flatMap { $0.isEmpty ? nil : $0 }
        return Push(call: c, chat: chat, media: (info["media"] as? String) == "video" ? "video" : "audio", caller: caller)
    }
    /// Ziel aus der Web-Adresse einer Anruf-Mitteilung (/chats#call-<id>)
    static func callFromURL(_ url: String?) -> String? {
        guard let u = url, let r = u.range(of: "#call-") else { return nil }
        let id = String(u[r.upperBound...].prefix(32))
        return isCallID(id) ? id : nil
    }

    /// Signale nach seq deduplizieren (Live + Nachholen per GET koennen dasselbe liefern)
    static func fresh(_ seq: Int, seen: inout Set<Int>) -> Bool { seen.insert(seq).inserted }

    // MARK: Ton-Diagnose (getStats, alle 2 s)

    /// Ton-Zahlen aus einem getStats-Bericht: inbound-rtp/outbound-rtp (kind audio) und media-source (kind audio)
    struct AudioSample: Equatable {
        var bytesIn = 0, samplesIn = 0, bytesOut = 0
        var energyIn = 0.0, levelIn = 0.0
        var energyMic = 0.0, levelMic = 0.0
        var hasMic = false
        /// getStats-Eintraege (type, values) -> Summen; Werte als NSNumber wie in RTCStatistics
        static func from(_ stats: [(type: String, values: [String: Any])]) -> AudioSample {
            var s = AudioSample()
            func num(_ v: Any?) -> Double { (v as? NSNumber)?.doubleValue ?? (v as? Double) ?? Double(v as? Int ?? 0) }
            for (t, v) in stats where (v["kind"] as? String ?? v["mediaType"] as? String) == "audio" {
                switch t {
                case "inbound-rtp":
                    s.bytesIn += Int(num(v["bytesReceived"])); s.samplesIn += Int(num(v["totalSamplesReceived"]))
                    s.energyIn += num(v["totalAudioEnergy"]); s.levelIn = max(s.levelIn, num(v["audioLevel"]))
                case "outbound-rtp": s.bytesOut += Int(num(v["bytesSent"]))
                case "media-source":
                    s.hasMic = true; s.energyMic += num(v["totalAudioEnergy"]); s.levelMic = max(s.levelMic, num(v["audioLevel"]))
                default: break
                }
            }
            return s
        }
    }
    enum AudioIn: Equatable { case unknown, receiving, silent, noPlayout, nothing }
    enum AudioMic: Equatable { case unknown, ok, silent, none }
    /// Bewertung ueber die letzten Messungen (Pausen beim Sprechen: "empfangen" haelt 3 Takte = 6 s)
    struct AudioMeter {
        private var last: AudioSample?
        private var heardAgo = Int.max, micAgo = Int.max, ticks = 0
        private(set) var input = AudioIn.unknown
        private(set) var mic = AudioMic.unknown
        static let hold = 3
        /// Pegel 0...1 (audioLevel) bzw. Energie-Zuwachs; ab hier gilt es als Ton (Rauschen eines stummen Kanals liegt darunter)
        static let level = 0.003, energy = 1e-6
        mutating func update(_ s: AudioSample, micOn: Bool) {
            defer { last = s; ticks += 1 }
            guard let p = last else { return }
            let dBytes = s.bytesIn - p.bytesIn, dSamples = s.samplesIn - p.samplesIn
            let heard = s.levelIn > Self.level || s.energyIn - p.energyIn > Self.energy
            heardAgo = heard ? 0 : (heardAgo == Int.max ? Int.max : heardAgo + 1)
            if heardAgo <= Self.hold { input = .receiving }
            else if dBytes <= 0 { input = .nothing }                          // keine Pakete (Weg/Gegenueber)
            else if dSamples <= 0 { input = .noPlayout }                     // Pakete kommen, Wiedergabe laeuft nicht (Audio-Session)
            else { input = .silent }                                         // Wiedergabe laeuft, aber nur Stille
            let spoke = s.levelMic > Self.level || s.energyMic - p.energyMic > Self.energy
            micAgo = spoke ? 0 : (micAgo == Int.max ? Int.max : micAgo + 1)
            if !s.hasMic || !micOn { mic = s.hasMic ? .unknown : .none }
            else if micAgo <= Self.hold { mic = .ok }
            else if ticks >= 4 { mic = .silent }                            // erst nach ein paar Takten melden
        }
    }
}
