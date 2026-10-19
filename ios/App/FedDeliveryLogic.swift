import Foundation

/// 2.3: Zustellstand eigener Nachrichten in Chats ueber Servergrenzen - "wird zugestellt" darf nicht haengen bleiben
/// (wie Android FedDelivery.kt).
///
/// Ist das Heim des Chats ein anderer Server, legt der eigene Server die Nachricht erst als 'pending' an und meldet sie
/// sofort (chat.message) - die App laedt sie als wartend. Bestaetigt das Heim gleich (synchron), meldeten Server bis
/// 2.3.0 das nicht als Ereignis. Daher: (1) Antwort von POST ohne `pending` = zugestellt, auch fuer eine erst danach
/// geladene Kopie; (2) Abgleich: solange eigene Nachrichten warten und der Chat sichtbar ist, ihren Stand beim Nachladen
/// bzw. alle 15 s beim Server nachsehen - so heilen auch schon haengende Nachrichten von selbst.
enum FedDelivery {
    typealias State = FedChatLogic.Delivery

    /// Abgleich hoechstens so oft (Sekunden), solange eigene Nachrichten warten
    static let checkEvery: TimeInterval = 15

    /// Geladene Nachricht, soweit fuer den Zustellstand noetig
    struct Item: Equatable {
        var seq: Int
        var id: String
        var mine: Bool
        var delivery: State
    }

    /// Antwort von POST /api/chats/{id}/messages: `pending: true` = wartet beim eigenen Server, sonst vom Heim bestaetigt
    static func confirmedByPost(_ pending: Bool) -> Bool { !pending }

    /// Neuer Zustand einer geladenen Nachricht. server: Stand laut Server (nil = nicht in der Serverliste);
    /// confirmed: per POST bestaetigt
    static func state(current: State, server: State?, confirmed: Bool) -> State {
        var s = server ?? current
        if s == .pending && current != .pending { s = current }       // nie zurueck auf "wird zugestellt" (aelterer Stand)
        return s == .pending && confirmed ? .delivered : s
    }

    /// Aelteste noch wartende eigene Nachricht (seq) - Abgleich ab dort; nil = nichts wartet
    static func oldestPending(_ items: [Item]) -> Int? {
        items.filter { $0.mine && $0.delivery == .pending }.map(\.seq).min()
    }

    /// Abgleich jetzt? Nur bei sichtbarem Chat, wenn etwas wartet und der letzte lange genug zurueckliegt
    static func due(_ items: [Item], lastCheck: Date, now: Date, visible: Bool = true) -> Bool {
        visible && oldestPending(items) != nil && now.timeIntervalSince(lastCheck) >= checkEvery
    }

    /// Wartende eigene Nachrichten, die der Server im abgeglichenen Bereich nicht mehr hat (z. B. vom Heim mit
    /// "Empfaenger geaendert" abgewiesen und unter neuer Kennung gesendet) -> Kennungen zum Entfernen.
    /// Bereich: ab der aeltesten wartenden bis zur letzten gelieferten (more) bzw. bis zum Ende.
    /// from: ab welcher seq abgeglichen wurde - bei from <= 1 (after=0 liefert die neuesten) nichts werten.
    static func gone(_ items: [Item], server: [String: State], more: Bool, maxSeq: Int, from: Int) -> Set<String> {
        guard from > 1 else { return [] }
        return Set(items.filter { $0.mine && $0.delivery == .pending && server[$0.id] == nil && (!more || $0.seq <= maxSeq) }.map(\.id))
    }

    /// Stand bekannter Nachrichten aus einer Serverliste (id -> Stand) uebernehmen -> geaenderte (Index, neuer Stand)
    static func apply(_ items: [Item], server: [String: State], confirmed: Set<String>) -> [(index: Int, state: State)] {
        items.enumerated().compactMap { i, m in
            if m.delivery == .delivered && server[m.id] == nil { return nil }
            let n = state(current: m.delivery, server: server[m.id], confirmed: confirmed.contains(m.id))
            return n != m.delivery ? (i, n) : nil
        }
    }

    /// Serverliste GET .../messages -> (id -> Stand, more, hoechste seq); ohne Entschluesseln
    static func parse(_ j: [String: Any]?) -> (server: [String: State], more: Bool, maxSeq: Int) {
        var out: [String: State] = [:], maxSeq = 0
        for case let m as [String: Any] in (j?["messages"] as? [Any] ?? []) {
            guard let id = m["id"] as? String else { continue }
            out[id] = FedChatLogic.delivery(m["fstate"])
            if let s = (m["seq"] as? NSNumber)?.intValue { maxSeq = max(maxSeq, s) }
        }
        return (out, (j?["more"] as? Bool) ?? false, maxSeq)
    }
}
