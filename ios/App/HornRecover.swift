import Foundation

/// Wiederaufbau eines Knoten-Anrufs nach Verbindungsverlust (Saga 2.5, Horn-Fix). Reine Entscheidungslogik ohne
/// WebRTC/Netz, gleiche Regeln wie Android HornRecover.kt:
/// - immer ueber einen Knoten neu verbinden, nie direkt (p2p): es gibt keinen solchen Schritt
/// - den einzigen bekannten Knoten nicht meiden (avoid leer), andere nur, wenn noch einer uebrig bleibt
/// - waehrend ein Versuch laeuft oder geplant ist, bleibt die Beobachtung stumm (kein neues "switch lost")
/// - Backoff 1/2/4/8/15 s mit +-20 % Zufall; Rueckstellung erst, wenn die neue Verbindung >= 10 s Daten liefert;
///   Verlust < 10 s nach dem Uebernehmen zaehlt als Fehlversuch
/// - 429 vom Server mit retry_after (bzw. Retry-After): so lange warten (hoechstens 60 s), kein "kein Knoten"
/// - nach 120 s ohne Erfolg aufgeben
struct HornRecover {
    static let steps: [Double] = [1, 2, 4, 8, 15]
    static let jitter = 0.2
    static let maxMs = 120_000.0
    static let settleMs = 10_000.0
    static let maxWait = 60

    enum Step: Equatable {
        /// naechster Versuch in s (0 = sofort)
        case wait(Double)
        /// aufgeben (Anruf mit Meldung beenden)
        case giveUp
    }

    /// Wiederaufbau laeuft (bis Erfolg oder Aufgabe)
    private(set) var on = false
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
    mutating func lost(now: Double, rnd: Double) -> Step? {
        if busy { return nil }
        if on && adoptAt > 0 && now - adoptAt < Self.settleMs {
            adoptAt = 0; busy = true                                        // neue Verbindung hielt nicht: Fehlversuch
            return next(now: now, retryAfter: nil, rnd: rnd)
        }
        on = true; t0 = now; fails = 0; adoptAt = 0; busy = true
        return .wait(0)
    }

    /// Versuch gescheitert; retryAfter = Wartezeit aus 429 (s), nil = normaler Backoff
    mutating func failed(now: Double, retryAfter: Int?, rnd: Double) -> Step {
        if !on { on = true; t0 = now; fails = 0; adoptAt = 0 }
        busy = true
        return next(now: now, retryAfter: retryAfter, rnd: rnd)
    }

    private mutating func next(now: Double, retryAfter: Int?, rnd: Double) -> Step {
        let left = Self.maxMs - (now - t0)
        if left <= 0 { busy = false; return .giveUp }
        var s: Double
        if let ra = retryAfter { s = Double(min(max(ra, 1), Self.maxWait)) }       // 429: kein Fehlversuch
        else { fails += 1; s = Self.delay(fails: fails, rnd: rnd) }
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

    /// Zu meidende Knoten: bad/verlorene nur, wenn danach noch ein bekannter Knoten uebrig bleibt
    static func avoid(_ bad: [String], known: [String]) -> [String] {
        let a = Set(bad), k = Set(known)
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
