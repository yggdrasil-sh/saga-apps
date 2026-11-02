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
        /// 2.5 handover2: Beitrittsgeneration (ms, steigt mit jedem erneuten Beitritt) - bedingtes /leave {gen}
        var gen: Int? = nil
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
            out.append(Leg(who: who, name: PersonName.shown((d["name"] as? String) ?? who, id: ShareJSON.str(d["handle"]) ?? who, host: ShareJSON.str(d["host"])), device: dev, devName: (d["dev_name"] as? String) ?? "",
                           role: (d["role"] as? String) ?? "member", state: (d["state"] as? String) ?? "active", caps: Array(caps), joined: joined,
                           handle: (d["handle"] as? String).flatMap { $0.isEmpty ? nil : $0 },
                           host: (d["host"] as? String).flatMap { $0.isEmpty ? nil : $0 },
                           replaces: (d["replaces"] as? String).flatMap { validDevice($0) ? $0 : nil },
                           hp: (d["hp"] as? String).flatMap { $0.isEmpty || $0.count > 64 ? nil : $0 },
                           hpn: (d["hpn"] as? [String: Any] ?? [:]).reduce(into: [String: String]()) { r, x in if let v = x.value as? String, !v.isEmpty, v.count <= 64 { r[x.key] = v } },
                           avatar: (d["avatar"] as? String).flatMap { $0.isEmpty ? nil : $0 },
                           gen: gen(d["gen"])))
        }
        return out
    }

    /// Generation aus JSON: positive ganze Zahl (ms) unter 2^53, sonst nil (kein Bool, kein Text)
    static func gen(_ v: Any?) -> Int? {
        guard let n = v as? NSNumber, CFGetTypeID(n) != CFBooleanGetTypeID() else { return nil }
        let d = n.doubleValue
        guard d.isFinite, d > 0, d < 9_007_199_254_740_992, d == d.rounded() else { return nil }
        return Int(d)
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
    enum Block: String, Equatable { case old, full, ended, none, other, handoverBusy = "handover_busy" }
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
    /// Dieses Geraet fehlt in der neuen Liste, ein anderes eigenes Geraet fuehrt (role != companion) und hat die Fuehrung
    /// gerade uebernommen (neu dabei, vorher Begleitgeraet oder 2.5 vorher handover) -> hier beenden ("uebernommen").
    /// Ein Begleitgeraet, das Begleitgeraet bleibt, heisst nur: dieses Geraet ist kurz aus der Liste gefallen (R09).
    static func takenElsewhere(old: [Leg]?, new: [Leg], here: String, me: String) -> Leg? {
        guard let old, old.contains(where: { $0.device == here }), !new.contains(where: { $0.device == here }) else { return nil }
        return new.first { x in
            guard x.who == me, x.device != here, !x.companion else { return false }
            guard let o = old.first(where: { $0.device == x.device }) else { return true }
            return o.companion || o.role == "handover"
        }
    }
    /// Altes Geraet: state.ready mit replaces == dieses Geraet, von einem EIGENEN Geraet -> abgeben
    static func handoverOut(ready: Bool, replaces: String?, here: String, fromOwn: Bool) -> Bool { ready && fromOwn && replaces == here }

    /// Uebernahme auf dem neuen Geraet: warten, bis alle Verbindungen zu ANDEREN Personen stehen (dann Ton an und dem
    /// alten Geraet state.ready schicken), hoechstens handover_s - sonst verlassen (das alte Geraet bleibt)
    struct Handover: Equatable {
        enum Phase: Equatable { case idle, waiting, done, failed }
        private(set) var phase = Phase.idle
        private(set) var rep: String?
        private(set) var deadline = 0.0
        /// 2.5 (L3): Begleitgeraet ueber Knoten - erst befoerdern (POST /promote) und Ton-Bein neu, dann state.ready
        private(set) var promo = false
        /// Befoerderung eingetragen (bei Fehlschlag/Abbruch zuruecknehmen: promote {device: rep})
        private(set) var promoted = false
        /// Zaehler je Versuch: spaete Antworten eines frueheren Versuchs erkennen
        private(set) var attempt = 0
        enum Step: Equatable { case none, wait, takeOver(String), fail }
        /// rep = bisheriges eigenes Geraet; false = keins da (dann sofort selbst Hauptgeraet)
        @discardableResult
        mutating func begin(rep r: String?, now: Double, timeout: Double = CallRoom.handoverSeconds, promote: Bool = false) -> Bool {
            attempt += 1; promo = false; promoted = false
            guard let r else { phase = .done; rep = nil; return false }
            phase = .waiting; rep = r; deadline = now + timeout; promo = promote
            return true
        }
        /// Befoerderung eingetragen (already = war schon Hauptgeraet: nichts zuruecknehmen)
        mutating func promotedNow(already: Bool) { if phase == .waiting { promoted = !already } }
        /// Ton-Bein steht (bzw. Server ohne promote): ab jetzt gilt die normale Pruefung
        mutating func promoReady() { if phase == .waiting { promo = false } }
        /// others = verbunden je Verbindung zu einer anderen Person
        mutating func check(others: [Bool], now: Double) -> Step {
            guard phase == .waiting, let rep else { return .none }
            if !promo && !others.isEmpty && others.allSatisfy({ $0 }) { phase = .done; return .takeOver(rep) }
            if now >= deadline { phase = .failed; return .fail }
            return .wait
        }
        /// abbrechen; -> Geraet, dessen Befoerderung zurueckzunehmen ist (promote {device: rep})
        @discardableResult
        mutating func cancel() -> String? {
            guard phase == .waiting else { return nil }
            let undo = promoted ? rep : nil
            phase = .idle; rep = nil; promo = false; promoted = false
            return undo
        }
        /// nach .fail: zuruecknehmen?
        var undo: String? { phase == .failed && promoted ? rep : nil }
        var active: Bool { phase == .waiting }
    }

    // MARK: 2.5 Uebergabe hin und her (handover2, Vertrag APP-API "Geraete-Uebergabe hin und her")

    static let featureHandover2 = "handover2"
    /// Rueckfall des neuen Geraets nach state.ready: ueber Knoten nach 3 s, im Mesh (neu) nach handover_s/2
    static func fallbackDelay(horn: Bool, handoverS: Double) -> Double { horn ? 3 : max(1, handoverS / 2) }
    /// Body fuer /leave: mit handover2 immer gen (aus der Liste) und beim Rueckfall by = eigenes Geraet
    static func leaveBody(device: String, reason: String?, by: String?, legs: [Leg], v2: Bool) -> [String: Any] {
        var b: [String: Any] = ["device": device]
        if let reason { b["reason"] = reason }
        guard v2 else { return b }
        if let by, by != device { b["by"] = by }
        if let g = legs.first(where: { $0.device == device })?.gen { b["gen"] = g }
        return b
    }
    enum LeaveResult: Equatable { case left, stale, already, failed }
    static func leaveResult(code: Int?, body: [String: Any]?) -> LeaveResult {
        guard let code, (200..<300).contains(code) else { return .failed }
        if (body?["stale"] as? Bool) == true { return .stale }
        if (body?["already"] as? Bool) == true { return .already }
        return .left
    }
    /// Beitritt trotz joinable false? Nur "Hierher uebernehmen" mit takeover (voller Anruf, E6)
    static func joinAllowed(role: String, joinable: Bool?, takeover: Bool?) -> Bool {
        joinable != false || (role == "handover" && takeover == true)
    }
    /// Antwort auf POST /join
    enum JoinOutcome: Equatable {
        case ok(gen: Int?)
        case busy(String?)          // 409 handover_busy: Hinweis, NICHT wiederholen
        case reoffer                // 409 mit legs ohne join_block: Liste veraltet, einmal neu anbieten
        case blocked(Block, String?)
        case failed(Int, String?)
    }
    static func joinOutcome(code: Int, body: [String: Any]?) -> JoinOutcome {
        let err = (body?["error"] as? String).flatMap { $0.isEmpty ? nil : $0 }
        if (200..<300).contains(code) { return .ok(gen: gen(body?["gen"])) }
        let blk = block(body?["join_block"] as? String)
        if code == 409, blk == .handoverBusy { return .busy(err) }
        if code == 409, blk == nil, body?["legs"] != nil { return .reoffer }
        if code == 409, let blk { return .blocked(blk, err) }
        return .failed(code, err)
    }
    /// Antwort auf POST /promote
    enum PromoteOutcome: Equatable {
        case ok(already: Bool)      // horn/call stehen in der Antwort
        case old                    // foederiert ohne promote: wie bisher ohne Befoerderung uebernehmen
        case failed(String?)
    }
    static func promoteOutcome(code: Int?, body: [String: Any]?) -> PromoteOutcome {
        guard let code else { return .failed(nil) }
        if (200..<300).contains(code) { return .ok(already: (body?["already"] as? Bool) == true) }
        if code == 409, (body?["join_block"] as? String) == "old" { return .old }
        return .failed((body?["error"] as? String).flatMap { $0.isEmpty ? nil : $0 })
    }
    static func promoteBody(device: String, devClass: String?) -> [String: Any] {
        var b: [String: Any] = ["device": device]
        if let devClass { b["dev_class"] = devClass }
        return b
    }
    /// Begleitgeraet uebernimmt im Anruf: ueber Knoten mit handover2 erst befoerdern (Ton-Recht), im Mesh nicht noetig
    static func needsPromote(horn: Bool, v2: Bool) -> Bool { horn && v2 }
    /// call.mod {action: mute, why: handover} fuer dieses Geraet: ein anderes eigenes fuehrt - still als Begleitgeraet weiter
    enum Mod: Equatable { case none, mute(by: String), handoverMute, remove }
    static func mod(_ d: [String: Any], here: String) -> Mod {
        let a = d["action"] as? String
        if a == "mute", (d["device"] as? String) == here {
            return (d["why"] as? String) == "handover" ? .handoverMute : .mute(by: (d["by"] as? String) ?? "?")
        }
        return a == "remove" ? .remove : .none
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
        /// 2.5 handover2: "Hierher uebernehmen" geht (auch bei vollem Anruf, joinable false/full)
        var takeover = false
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
                       ringS: (d["ring_s"] as? NSNumber)?.doubleValue ?? 45, peerName: (peer["name"] as? String) ?? "", role: (d["role"] as? String) ?? "",
                       takeover: (d["takeover"] as? Bool) == true)
    }
    enum Bar: Equatable {
        case none
        /// eigenes Geraet im Anruf, dieses nicht: "Anruf laeuft auf <Geraet>" - Hinzufuegen / Hierher uebernehmen
        case elsewhere(device: String, peer: String)
        /// Gruppenanruf im Chat ohne eigenes Geraet: "Gruppenanruf laeuft · n Geraete" - Beitreten
        case group(devices: Int)
    }
    /// Leiste zeigen? Nicht fuer den eigenen laufenden Anruf, nicht waehrend er hier klingelt, nicht wenn dieses Geraet dabei ist
    /// 2.5 rejoin (handover2): steht dieses Geraet noch in der Liste, obwohl es hier keinen Anruf mehr gibt (schneller
    /// Rueckwechsel: sein Verlassen ist noch unterwegs, bzw. App neu gestartet), und fuehrt ein anderes eigenes Geraet,
    /// trotzdem "Hierher uebernehmen" anbieten - der Server nimmt es als Wiedereintritt
    static func bar(_ x: Running, here: String, current: String?, ringing: Bool, rejoin: Bool = false) -> Bar {
        if x.id == current || x.state == "ended" || ringing { return .none }
        let others = x.mine.filter { $0 != here }
        if x.mine.contains(here) && !(rejoin && !others.isEmpty) { return .none }
        if !others.isEmpty && (x.kind == "group" || x.state == "active") {
            let own = x.legs.filter { others.contains($0.device) }
            let main = own.first { !$0.companion } ?? own.first
            return .elsewhere(device: main?.devName ?? "", peer: x.kind == "group" ? "" : x.peerName)
        }
        if x.kind == "group" && x.mine.isEmpty { return .group(devices: x.legs.count) }
        return .none
    }

    /// Knoepfe der Leiste: Hinzufuegen (Begleitgeraet), Hierher uebernehmen, Beitreten (Gruppe ohne eigenes Geraet).
    /// 2.5 (L4/E6): voller Anruf mit takeover - nur "Hierher uebernehmen"
    struct BarActions: Equatable { var add = false; var takeover = false; var join = false }
    static func barActions(_ x: Running, bar b: Bar) -> BarActions {
        switch b {
        case .none: return BarActions()
        case .elsewhere: return x.joinable ? BarActions(add: true, takeover: true) : BarActions(takeover: x.takeover)
        case .group: return BarActions(join: x.joinable)
        }
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

    // MARK: 2.5 A07 "Alle Anrufe ueber Knoten" (horn_dm) - 1:1 ueber einen Knoten (wie Android CallRoom.dmViaHorn)

    /// Server meldet horn_dm und dieses Geraet hat horn + multi -> 1:1 zuerst mit via "horn" (ohne signals) anrufen
    static func dmViaHorn(hornDm: Bool, myCaps: [String]) -> Bool { hornDm && myCaps.contains("horn") && myCaps.contains("multi") }
    /// POST /api/calls fuer 1:1 ueber Knoten: ohne signals, mit via und Geraeteklasse
    static func dmHornBody(id: String, chatID: Int, media: String, device: String, caps: [String], devClass: String) -> [String: Any] {
        ["id": id, "chat_id": chatID, "media": media, "device": device, "caps": caps, "via": "horn", "dev_class": devClass]
    }
    enum DMHorn: Equatable {
        case room                 // Raum-Anruf ueber den Knoten laeuft (call in der Antwort)
        case direct               // nichts angelegt: mit derselben id direkt anrufen
        case busy                 // Gegenueber telefoniert
        case failed(String?)      // anderer Fehler (Meldung des Servers)
    }
    /// Antwort auf POST /api/calls mit via "horn". code nil = Netzfehler -> direkt (der Direktweg meldet dann selbst)
    static func dmHornOutcome(code: Int?, body: [String: Any]?) -> DMHorn {
        guard let code else { return .direct }
        if (200..<300).contains(code) { return body?["call"] is [String: Any] ? .room : .failed(nil) }
        if code == 409, (body?["fallback"] as? String) == "direct" { return .direct }
        if code == 409, (body?["busy"] as? Bool) == true { return .busy }
        let e = (body?["error"] as? String).map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
        return .failed(e?.isEmpty == false ? e : nil)
    }
}
