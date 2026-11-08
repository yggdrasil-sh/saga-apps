import Foundation

/// Wiederaufbau eines Knoten-Anrufs nach Verbindungsverlust (Saga 2.5, Horn-Fix). Reine Entscheidungslogik ohne
/// WebRTC/Netz, gleiche Regeln wie Android HornRecover.kt:
/// - zuerst 30 s lang ueber einen Knoten neu verbinden; erst danach (Horn faellt aus) auf direkt umschalten, wenn das
///   moeglich ist (canDirect, z. B. Gruppe in der Geraetegrenze); sonst weiter ueber Knoten bis 120 s, dann aufgeben
/// - den einzigen bekannten Knoten nicht meiden (avoid leer), andere nur, wenn noch einer uebrig bleibt
/// - waehrend ein Versuch laeuft oder geplant ist, bleibt die Beobachtung stumm (kein neues "switch lost")
/// - Backoff 1/2/4/8/15 s mit +-20 % Zufall; Rueckstellung erst, wenn die neue Verbindung >= 10 s Daten liefert;
///   Verlust < 10 s nach dem Uebernehmen zaehlt als Fehlversuch
/// - 429 vom Server mit retry_after (bzw. Retry-After): max(retry_after, Backoff) warten (retry_after hoechstens 60 s),
///   kein "kein Knoten"
/// - nach 120 s ohne Erfolg aufgeben (nur, wenn auch direkt nicht moeglich ist)
struct HornRecover {
    static let steps: [Double] = [1, 2, 4, 8, 15]
    static let jitter = 0.2
    static let maxMs = 120_000.0
    /// so lange nur ueber Knoten; danach direkt (wenn moeglich)
    static let windowMs = 30_000.0
    static let settleMs = 10_000.0
    static let maxWait = 60

    enum Step: Equatable {
        /// naechster Versuch in s (0 = sofort)
        case wait(Double)
        /// Knoten nicht erreichbar: auf direkt umschalten (Hinweis "Anruf-Knoten nicht erreichbar - direkt verbunden")
        case direct
        /// aufgeben (Anruf mit Meldung beenden)
        case giveUp
    }

    /// Antwort auf POST /api/calls/{cid}/direct (Vertrag horn_direct1)
    enum DirectAnswer: Equatable {
        /// 200: Anruf ist jetzt direkt; offerTo = Geraete, denen dieses Geraet anbietet
        case switched(offerTo: [String])
        /// 409 node_ready: es gibt einen bereiten Knoten - weiter ueber den Knoten
        case nodeReady
        /// 409 limit / fed (bzw. unbekannt): kein Direkt-Weg - weiter ueber Knoten, nach 120 s Ende
        case noPath(String)
        /// 429: so lange warten (nil = ohne Angabe)
        case busy(Int?)
        /// 404: Anruf vorbei
        case ended
        /// Netz/sonstiges: wie node_ready (spaeter erneut fragen)
        case error
    }

    /// Wiederaufbau laeuft (bis Erfolg oder Aufgabe)
    private(set) var on = false
    /// Server hat direkt abgelehnt (limit/fed): in diesem Anruf nicht mehr fragen
    private(set) var noDirect = false
    /// Beginn des Wiederaufbaus (ms)
    private(set) var t0 = 0.0
    /// Fehlversuche seit Beginn
    private(set) var fails = 0
    /// Versuch laeuft oder ist geplant
    private(set) var busy = false
    /// Zeitpunkt der zuletzt uebernommenen Verbindung waehrend des Wiederaufbaus
    private(set) var adoptAt = 0.0

    var active: Bool { on }
    /// Beobachtung stumm: kein neues "switch lost", kein Wechsel zu "besseren" Knoten
    var muted: Bool { busy }

    /// Verbindung verloren (bzw. Wechsel/Beitritt gescheitert). nil = laeuft schon (ignorieren).
    mutating func lost(now: Double, rnd: Double, canDirect: Bool = false) -> Step? {
        if busy { return nil }
        if on && adoptAt > 0 && now - adoptAt < Self.settleMs {
            adoptAt = 0; busy = true                                        // neue Verbindung hielt nicht: Fehlversuch
            return next(now: now, retryAfter: nil, rnd: rnd, canDirect: canDirect)
        }
        on = true; t0 = now; fails = 0; adoptAt = 0; busy = true
        return .wait(0)
    }

    /// Versuch gescheitert; retryAfter = Wartezeit aus 429 (s), nil = normaler Backoff; canDirect = Umschalten moeglich
    mutating func failed(now: Double, retryAfter: Int?, rnd: Double, canDirect: Bool = false) -> Step {
        if !on { on = true; t0 = now; fails = 0; adoptAt = 0 }
        busy = true
        return next(now: now, retryAfter: retryAfter, rnd: rnd, canDirect: canDirect)
    }

    private mutating func next(now: Double, retryAfter: Int?, rnd: Double, canDirect: Bool) -> Step {
        let left = Self.maxMs - (now - t0)
        if left <= 0 { busy = false; return .giveUp }                       // auch direkt ging nicht
        if canDirect && !noDirect {
            let w = Self.windowMs - (now - t0)
            if w <= 0 { return .direct }                                    // Horn faellt aus: direkt fragen (busy bleibt)
            return wait(retryAfter: retryAfter, rnd: rnd, left: w)          // letzter Knoten-Versuch am Fensterende
        }
        return wait(retryAfter: retryAfter, rnd: rnd, left: left)
    }

    /// Ergebnis der Direkt-Anfrage nach .direct. nil = umgeschaltet bzw. Anruf vorbei (Wiederaufbau beendet),
    /// sonst der naechste Schritt (weiter ueber Knoten mit Backoff; nach 120 s aufgeben).
    mutating func direct(_ a: DirectAnswer, now: Double, rnd: Double) -> Step? {
        switch a {
        case .switched, .ended:
            self = HornRecover(); return nil
        case .noPath:
            noDirect = true
            return next(now: now, retryAfter: nil, rnd: rnd, canDirect: false)
        case .busy(let s):
            return nodeAgain(now: now, retryAfter: s, rnd: rnd)
        case .nodeReady, .error:
            return nodeAgain(now: now, retryAfter: nil, rnd: rnd)
        }
    }
    /// nach abgelehnter Direkt-Anfrage: erst wieder ueber den Knoten (kein sofortiges erneutes .direct)
    private mutating func nodeAgain(now: Double, retryAfter: Int?, rnd: Double) -> Step {
        let left = Self.maxMs - (now - t0)
        if left <= 0 { busy = false; return .giveUp }
        return wait(retryAfter: retryAfter, rnd: rnd, left: left)
    }

    /// Antwort von POST /direct auswerten
    static func directAnswer(status: Int?, body: [String: Any]?) -> DirectAnswer {
        guard let st = status else { return .error }
        if (200..<300).contains(st) {
            if let c = body?["call"] as? [String: Any], let t = c["topo"] as? String, t != "mesh" { return .error }
            let to = (body?["offer_to"] as? [Any] ?? []).compactMap { $0 as? String }
            return .switched(offerTo: to)
        }
        if st == 404 { return .ended }
        if st == 429 { return .busy(retryAfter(status: st, body: body)) }
        if st == 409 {
            let b = body?["horn_block"] as? String ?? ""
            return b == "node_ready" ? .nodeReady : .noPath(b.isEmpty ? "?" : b)
        }
        return .error
    }

    /// Mesh nach dem Wechsel (Vertrag 3.2): Angebote nur an Endpunkte mit groesserer Geraete-ID (kein Glare)
    static func offerTargets(_ devices: [String], me: String) -> [String] {
        Array(Set(devices.filter { $0 > me })).sorted()
    }

    /// Knoten, die in den letzten 30 s nicht erreichbar waren (bad: Knoten -> gemieden bis, ms)
    static func unreached(_ bad: [String: Double], now: Double, badMs: Double) -> [String] {
        bad.filter { $0.value - badMs > now - windowMs }.map(\.key).sorted()
    }

    /// Hinweis beim Anrufstart (409 fallback direct): horn_block -> Text-Art
    enum StartNote: Equatable { case nodeDown, peerOld }
    static func startNote(_ block: String?) -> StartNote? {
        switch block {
        case "node": return .nodeDown
        case "old", "peer": return .peerOld
        default: return nil
        }
    }

    private mutating func wait(retryAfter: Int?, rnd: Double, left: Double) -> Step {
        fails += 1
        var s = Self.delay(fails: fails, rnd: rnd)
        if let ra = retryAfter { s = max(s, Double(min(max(ra, 1), Self.maxWait))) }   // 429: max(retry_after, Backoff)
        s = min(s, left / 1000)                                             // letzter Versuch genau an der Frist
        return .wait(s)
    }

    /// Neue Verbindung uebernommen (der Versuch ist fertig; noch nicht zurueckgestellt)
    mutating func adopted(now: Double) {
        guard on else { return }
        busy = false; adoptAt = now
    }

    /// Laufende Beobachtung: rx = die aktuelle Verbindung liefert Daten. true = Wiederaufbau erfolgreich beendet.
    mutating func tick(now: Double, rx: Bool) -> Bool {
        guard on, !busy, adoptAt > 0, rx, now - adoptAt >= Self.settleMs else { return false }
        self = HornRecover()
        return true
    }

    /// Frist abgelaufen (nur zur Anzeige/Pruefung)
    func expired(now: Double) -> Bool { on && now - t0 >= Self.maxMs }

    /// Backoff fuer den n-ten Fehlversuch (1 ...): 1, 2, 4, 8, 15, 15 ... s, rnd 0..<1 -> +-20 %
    static func delay(fails: Int, rnd: Double) -> Double {
        let b = steps[min(max(fails, 1), steps.count) - 1]
        return b * (1 - jitter + 2 * jitter * min(max(rnd, 0), 1))
    }

    /// Zu meidende Knoten: der verlorene nie (Vertrag 2: oft der einzige; er kommt als letzter Versuch dran), andere
    /// gescheiterte nur, wenn danach noch ein bekannter Knoten uebrig bleibt
    static func avoid(_ bad: [String], known: [String], lost: String? = nil) -> [String] {
        let a = Set(bad).subtracting([lost].compactMap { $0 }), k = Set(known)
        if k.count <= 1 || k.subtracting(a).isEmpty { return [] }
        return a.sorted()
    }

    /// 429-Vertrag: Status 429, Body {"error": ..., "retry_after": <s>} bzw. Header Retry-After -> Sekunden (1 ... 60).
    /// nil = kein 429 oder aelterer Server ohne Angabe (dann normaler Backoff).
    static func retryAfter(status: Int, body: [String: Any]?, header: String? = nil) -> Int? {
        guard status == 429 else { return nil }
        var s: Int?
        if let v = body?["retry_after"] {
            if let n = v as? NSNumber, CFGetTypeID(n) != CFBooleanGetTypeID() { s = n.intValue }
            else if let t = v as? String { s = Int(t.trimmingCharacters(in: .whitespaces)) }
        }
        if s == nil, let h = header { s = Int(h.trimmingCharacters(in: .whitespaces)) }
        guard let x = s, x >= 0 else { return nil }
        return min(max(x, 1), maxWait)
    }
}
