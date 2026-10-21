import Foundation

// Anrufe 2.4 (Etappen E4/E5): ein Anruf ist ein Raum mit Endpunkten je Geraet - weitere eigene Geraete (Begleitgeraet,
// "Hierher uebernehmen") und Gruppenanrufe als Mesh (je Geraetepaar eine PeerConnection). Reine Logik ohne WebRTC/UI
// (mit swiftc pruefbar). Vertrag: Saga-Server docs/APP-API.md, "Anrufe: Mehrgeraete und Gruppen"; Vorbild Web:
// static/calls.js (rosterIn, runBar, hoCheck, speakers, drawTiles).

enum CallRoom {
    /// Mesh-Grenzen (Konzept §5.2): Geraete je Anruf mit Video bzw. nur Ton; der Admin kann nur senken
    static let videoMax = 4
    static let audioMax = 6
    /// Uebernahme: so lange darf das neue Geraet brauchen (limits.handover_s)
    static let handoverSeconds = 15.0
    /// Gruppen mit mehr Mitgliedern klingeln nicht (limits.ring_silent)
    static let ringSilent = 8

    // MARK: Teilnehmerliste (legs)

    struct Leg: Equatable {
        var who: String
        var name: String
        var device: String
        var devName: String
        var role: String
        var state: String
        var caps: [String]
        var joined: Double
        var handle: String?
        var host: String?
        var replaces: String?
        /// 2.5 Gjallarhorn: Kennung des Geraets am Knoten des Anrufs (hp) bzw. je Knoten (hpn), Profilbild
        var hp: String? = nil
        var hpn: [String: String] = [:]
        var avatar: String? = nil
        var companion: Bool { role == "companion" }
        var multi: Bool { caps.contains("multi") }
    }
    /// Geraete-ID wie im Chat: 1-64 Zeichen [A-Za-z0-9_-]
    static func validDevice(_ s: String) -> Bool {
        !s.isEmpty && s.count <= 64 && s.unicodeScalars.allSatisfy { $0.isASCII && (CharacterSet.alphanumerics.contains($0) || $0 == "_" || $0 == "-") }
    }
    /// legs aus GET /api/calls/{id} bzw. call.roster; Ungueltiges faellt weg, doppelte Geraete zaehlen einmal
    static func legs(_ any: Any?) -> [Leg] {
        var out: [Leg] = [], seen = Set<String>()
        for case let d as [String: Any] in (any as? [Any] ?? []) {
            guard let dev = d["device"] as? String, validDevice(dev), let who = d["who"] as? String, !who.isEmpty, seen.insert(dev).inserted else { continue }
            let caps = (d["caps"] as? [Any] ?? []).compactMap { $0 as? String }.prefix(16)
            let joined = (d["joined"] as? NSNumber)?.doubleValue ?? 0
            out.append(Leg(who: who, name: (d["name"] as? String) ?? who, device: dev, devName: (d["dev_name"] as? String) ?? "",
                           role: (d["role"] as? String) ?? "member", state: (d["state"] as? String) ?? "active", caps: Array(caps), joined: joined,
                           handle: (d["handle"] as? String).flatMap { $0.isEmpty ? nil : $0 },
                           host: (d["host"] as? String).flatMap { $0.isEmpty ? nil : $0 },
                           replaces: (d["replaces"] as? String).flatMap { validDevice($0) ? $0 : nil },
                           hp: (d["hp"] as? String).flatMap { $0.isEmpty || $0.count > 64 ? nil : $0 },
                           hpn: (d["hpn"] as? [String: Any] ?? [:]).reduce(into: [String: String]()) { r, x in if let v = x.value as? String, !v.isEmpty, v.count <= 64 { r[x.key] = v } },
                           avatar: (d["avatar"] as? String).flatMap { $0.isEmpty ? nil : $0 }))
        }
        return out
    }

    /// Stand der Liste: rv aus dem Ereignis (Foederation) bzw. lokal - Live-Ereignis = Ankunftszeit, GET-Antwort =
    /// Zeitpunkt der ANFRAGE. Eine aeltere Liste ueberschreibt nie eine neuere.
    struct Roster: Equatable {
        private(set) var legs: [Leg] = []
        private(set) var rv: Double = 0
        private(set) var known = false
        @discardableResult
        mutating func apply(_ l: [Leg], rv v: Double) -> Bool {
            if known && v < rv { return false }
            legs = l; rv = v; known = true
            return true
        }
        mutating func reset() { self = Roster() }
        func leg(_ device: String?) -> Leg? { device.flatMap { d in legs.first { $0.device == d } } }
    }
    /// Wer kam dazu, wer ist weg (ohne dieses Geraet)? Erste Liste: keine Hinweise
    static func diff(old: [Leg]?, new: [Leg], here: String) -> (added: [Leg], removed: [Leg]) {
        guard let old else { return ([], []) }
        let o = Set(old.map(\.device)), n = Set(new.map(\.device))
        return (new.filter { !o.contains($0.device) && $0.device != here }, old.filter { !n.contains($0.device) && $0.device != here })
    }

    // MARK: Mesh

    /// Alle PeerConnections eines Raums: je Geraetepaar eine (sortiertes Paar "min:max")
    static func pairs(_ devices: [String]) -> [String] {
        let d = Array(Set(devices)).sorted()
        var out: [String] = []
        for i in 0..<d.count { for j in (i + 1)..<max(i + 1, d.count) { out.append(CallV3.pairPC(d[i], d[j])) } }
        return out
    }
    /// Wer hinzukommt, bietet allen anderen an (jedem Geraet ein Angebot, in einem POST /join)
    static func offerTargets(_ legs: [Leg], here: String) -> [String] { legs.map(\.device).filter { $0 != here } }
    /// Grenze fuer den Anruf: Server-Wert (limit bzw. limits.endpoints_*) gilt nur, wenn er niedriger ist
    /// 2.5: Anruf ueber einen Knoten - die Grenze des Anrufs kommt vom Server (TCP 12/50, UDP 25/100)
    static func limit(media: String, server: Int?, horn: Bool) -> Int {
        guard horn else { return limit(media: media, server: server) }
        guard let s = server, s >= 2 else { return media == "video" ? 12 : 50 }
        return s
    }
    /// 2.5 P10: Hinweis horn_mesh in Teilnehmerliste/Antwort auf join (Gruppenanruf ueber Servergrenzen bleibt im Mesh,
    /// weil ein Server keinen Knoten nutzen kann) - Text vom Server in der Sprache der Anfrage; leer/kein Text = nichts
    static func meshNote(_ c: [String: Any]?) -> String? {
        guard let m = c?["horn_mesh"] as? String else { return nil }
        let t = m.trimmingCharacters(in: .whitespacesAndNewlines)
        return t.isEmpty ? nil : String(t.prefix(300))
    }
    static func limit(media: String, server: Int?) -> Int {
        let base = media == "video" ? videoMax : audioMax
        guard let s = server, s >= 2 else { return base }
        return min(base, s)
    }
    static func full(count: Int, limit: Int) -> Bool { count >= limit }
    /// Bitrate: Gegenstellen, an die dieses Geraet sendet (CallV3.videoLimits streams) - ab 3 Endpunkten Mesh-Werte
    static func streams(links: Int) -> Int { max(1, links) }

    /// Warum Beitreten nicht geht (join_block)
    enum Block: String, Equatable { case old, full, ended, none, other }
    static func block(_ s: String?) -> Block? {
        guard let s, !s.isEmpty else { return nil }
        return Block(rawValue: s) ?? .other
    }

    // MARK: Rollen, Begleitgeraet, Uebernahme

    /// Start eines Geraets im Raum. Begleitgeraet: Mikrofon aus, Wiedergabe stumm, Kamera aus und NICHT nach dem
    /// Mikrofon fragen; Uebernahme: erst still, bis alle Verbindungen stehen; Mitglied: wie ein normaler Anruf
    struct Start: Equatable { var mic: Bool; var sound: Bool; var cam: Bool; var askMic: Bool }
    static func start(role: String, video: Bool) -> Start {
        switch role {
        case "companion": return Start(mic: false, sound: false, cam: false, askMic: false)
        case "handover": return Start(mic: false, sound: false, cam: video, askMic: true)
        default: return Start(mic: true, sound: true, cam: video, askMic: true)
        }
    }
    /// Eigenes Hauptgeraet im Anruf (fuer "Hierher uebernehmen"): eigenes Geraet ausser diesem, bevorzugt kein Begleitgeraet
    static func mainDevice(_ legs: [Leg], me: String, here: String) -> Leg? {
        let mine = legs.filter { $0.who == me && $0.device != here }
        return mine.first { !$0.companion } ?? mine.first
    }
    /// Auflegen: im 1:1 beendet das Hauptgeraet den Anruf fuer alle (Server), ein Begleitgeraet bzw. eine Gruppe
    /// verlaesst ihn nur
    static func hangupEndsAll(kind: String, companion: Bool) -> Bool { kind != "group" && !companion }
    /// Altes Geraet: state.ready mit replaces == dieses Geraet, von einem EIGENEN Geraet -> abgeben
    static func handoverOut(ready: Bool, replaces: String?, here: String, fromOwn: Bool) -> Bool { ready && fromOwn && replaces == here }

    /// Uebernahme auf dem neuen Geraet: warten, bis alle Verbindungen zu ANDEREN Personen stehen (dann Ton an und dem
    /// alten Geraet state.ready schicken), hoechstens handover_s - sonst verlassen (das alte Geraet bleibt)
    struct Handover: Equatable {
        enum Phase: Equatable { case idle, waiting, done, failed }
        private(set) var phase = Phase.idle
        private(set) var rep: String?
        private(set) var deadline = 0.0
        enum Step: Equatable { case none, wait, takeOver(String), fail }
        /// rep = bisheriges eigenes Geraet; false = keins da (dann sofort selbst Hauptgeraet)
        @discardableResult
        mutating func begin(rep r: String?, now: Double, timeout: Double = CallRoom.handoverSeconds) -> Bool {
            guard let r else { phase = .done; rep = nil; return false }
            phase = .waiting; rep = r; deadline = now + timeout
            return true
        }
        /// others = verbunden je Verbindung zu einer anderen Person
        mutating func check(others: [Bool], now: Double) -> Step {
            guard phase == .waiting, let rep else { return .none }
            if !others.isEmpty && others.allSatisfy({ $0 }) { phase = .done; return .takeOver(rep) }
            if now >= deadline { phase = .failed; return .fail }
            return .wait
        }
        mutating func cancel() { if phase == .waiting { phase = .idle; rep = nil } }
        var active: Bool { phase == .waiting }
    }

    // MARK: Ansicht: Kacheln, Sprecher

    /// Zustand einer Verbindung fuer die Ansicht
    struct LinkView: Equatable {
        var device: String
        var own: Bool
        var companion: Bool
        var gotState: Bool
        var mic: Bool
        var cam: Bool
        var screen: Bool
    }
    /// "Stille" Verbindung: eigenes Geraet bzw. Begleitgeraet ohne Ton, Kamera und Bildschirm - keine eigene Kachel
    static func quiet(_ l: LinkView) -> Bool {
        if l.cam || l.screen { return false }
        return l.own || (l.companion && (!l.gotState || !l.mic))
    }
    /// Kachelansicht: Gruppenanruf oder mehr als eine sichtbare Verbindung (1:1 mit stillem Begleitgeraet bleibt einzeln)
    static func meshView(kind: String, shown: Int) -> Bool { kind == "group" || shown >= 2 }
    /// Spalten: iPhone 2x2; iPad/Mac ab 5 Kacheln drei Spalten (groessere Flaeche)
    static func columns(_ n: Int, wide: Bool) -> Int {
        if n <= 1 { return 1 }
        if n <= 4 { return 2 }
        return wide ? 3 : 2
    }
    /// Sprecher hervorheben: hoechster Pegel der empfangenen Tonspur (ueber der Schwelle, nur ungestummt); haelt kurz nach
    struct Speaker: Equatable {
        static let threshold = 0.03
        static let hold = 1.5
        private(set) var device: String?
        private(set) var at = 0.0
        /// true = geaendert
        mutating func update(_ levels: [(device: String, level: Double, mic: Bool)], now: Double) -> Bool {
            var best: String?, top = Self.threshold
            for l in levels where l.mic && l.level > top { top = l.level; best = l.device }
            let old = device
            if let best { device = best; at = now } else if now - at > Self.hold { device = nil }
            return old != device
        }
        mutating func drop(_ dev: String) { if device == dev { device = nil } }
    }

    // MARK: Leiste "Anruf laeuft" (GET /api/calls/active)

    struct Running: Equatable {
        var id: String
        var chatID: Int
        var kind: String
        var media: String
        var state: String
        var mine: [String]
        var legs: [Leg]
        var joinable: Bool
        var block: Block?
        var limit: Int
        var invite: String?
        var created: Double
        var ringS: Double
        var peerName: String
        var role: String
        var silent = false
    }
    static func running(_ any: Any?) -> Running? {
        guard let d = any as? [String: Any], let id = d["id"] as? String, id.count == 32 else { return nil }
        let peer = d["peer"] as? [String: Any] ?? [:]
        return Running(id: id, chatID: (d["chat_id"] as? NSNumber)?.intValue ?? 0, kind: (d["kind"] as? String) == "group" ? "group" : "dm",
                       media: (d["media"] as? String) == "video" ? "video" : "audio", state: (d["state"] as? String) ?? "",
                       mine: (d["mine"] as? [Any] ?? []).compactMap { $0 as? String }, legs: legs(d["legs"]),
                       joinable: (d["joinable"] as? Bool) == true, block: block(d["join_block"] as? String),
                       limit: limit(media: (d["media"] as? String) ?? "audio", server: (d["limit"] as? NSNumber)?.intValue),
                       invite: d["invite"] as? String, created: (d["created"] as? NSNumber)?.doubleValue ?? 0,
                       ringS: (d["ring_s"] as? NSNumber)?.doubleValue ?? 45, peerName: (peer["name"] as? String) ?? "", role: (d["role"] as? String) ?? "")
    }
    enum Bar: Equatable {
        case none
        /// eigenes Geraet im Anruf, dieses nicht: "Anruf laeuft auf <Geraet>" - Hinzufuegen / Hierher uebernehmen
        case elsewhere(device: String, peer: String)
        /// Gruppenanruf im Chat ohne eigenes Geraet: "Gruppenanruf laeuft · n Geraete" - Beitreten
        case group(devices: Int)
    }
    /// Leiste zeigen? Nicht fuer den eigenen laufenden Anruf, nicht waehrend er hier klingelt, nicht wenn dieses Geraet dabei ist
    static func bar(_ x: Running, here: String, current: String?, ringing: Bool) -> Bar {
        if x.id == current || x.state == "ended" || ringing || x.mine.contains(here) { return .none }
        if !x.mine.isEmpty && (x.kind == "group" || x.state == "active") {
            let own = x.legs.filter { x.mine.contains($0.device) }
            let main = own.first { !$0.companion } ?? own.first
            return .elsewhere(device: main?.devName ?? "", peer: x.kind == "group" ? "" : x.peerName)
        }
        if x.kind == "group" && x.mine.isEmpty { return .group(devices: x.legs.count) }
        return .none
    }

    // MARK: Gruppenanruf: Knopf, Klingeln

    /// Knopf "Gruppenanruf" nur in Gruppen-, freien und Seiten-Chats, wenn der Server es meldet (chat.call.group - auch:
    /// alle beteiligten Server koennen calls3) und dieses Geraet "multi" kann
    static func groupButton(kind: String, group: Bool?, myCaps: [String], enabled: Bool) -> Bool {
        enabled && kind != "dm" && group == true && myCaps.contains("multi")
    }
    /// Einladung klingeln lassen? Nicht still (grosse Gruppe), Einladung klingelt noch, kein eigenes Geraet dabei,
    /// innerhalb der Klingeldauer, nicht schon im Gespraech
    static func ringGroup(invite: String?, mine: [String], silent: Bool, busy: Bool, age: Double, ringS: Double) -> Bool {
        !silent && !busy && invite == "ringing" && mine.isEmpty && age < ringS
    }
    /// Klingeln auf diesem Geraet beenden: ein eigenes Geraet ist dem Gruppenanruf beigetreten (call.roster)
    static func stopRinging(_ legs: [Leg], me: String) -> Bool { legs.contains { $0.who == me } }

    // MARK: Angebote unbekannter Geraete (Geister-Geraet)

    enum Admit: Equatable { case accept, reject, unknownLeg }
    /// Geraet laut Liste im Anruf und in der SIGNIERTEN Geraeteliste seiner Person? Sonst ablehnen
    static func admit(device: String, legs: [Leg], signed: Set<String>) -> Admit {
        guard legs.contains(where: { $0.device == device }) else { return .unknownLeg }
        return signed.contains(device) ? .accept : .reject
    }
    /// 2.4 (Testrunde W9): Schluessel sind je Person zwischengespeichert - ein Geraet, das sie WAEHREND des Anrufs gekoppelt
    /// hat (Hinzufuegen/Hierher uebernehmen), fehlt dort noch: dann die signierte Liste einmal frisch holen, erst danach ablehnen
    static func needsFreshKeys(device: String, signed: Set<String>) -> Bool { !device.isEmpty && !signed.contains(device) }

    /// Faehigkeiten melden (PUT /api/calls/caps): einmal je Geraet und Faehigkeiten
    static func capsKey(device: String, caps: [String]) -> String { device + "|" + caps.joined(separator: ",") }
}
