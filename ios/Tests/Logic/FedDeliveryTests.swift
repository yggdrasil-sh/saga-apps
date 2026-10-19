import Foundation

// 2.3: "wird zugestellt" bleibt nicht haengen (eigene Nachricht in einem foederierten Direkt-Chat, Heim = anderer Server)
// (App/FedDeliveryLogic.swift, wie Android FedDeliveryTest)
func fedDeliveryTests() {
    typealias F = FedDelivery
    func m(_ seq: Int, _ id: String, _ d: F.State, mine: Bool = true) -> F.Item { F.Item(seq: seq, id: id, mine: mine, delivery: d) }

    // POST ohne pending = zugestellt; schon ueber das Live-Ereignis wartend geladen -> zugestellt
    check(F.confirmedByPost(false) && !F.confirmedByPost(true), "POST: pending entscheidet")
    var ch = F.apply([m(5, "a", .pending)], server: [:], confirmed: ["a"])
    check(ch.count == 1 && ch[0].index == 0 && ch[0].state == .delivered, "per POST bestaetigt -> zugestellt")

    // Server 2.3.0: Bestaetigung ohne Ereignis - der Abgleich holt den Stand
    ch = F.apply([m(3, "x", .delivered, mine: false), m(5, "a", .pending), m(6, "b", .pending)],
                 server: ["x": .delivered, "a": .delivered, "b": .failed], confirmed: [])
    check(ch.map(\.index) == [1, 2], "nur geaenderte")
    check(ch.first { $0.index == 1 }?.state == .delivered, "haengende geheilt")
    check(ch.first { $0.index == 2 }?.state == .failed, "nicht zugestellt uebernommen")
    check(F.apply([m(5, "a", .pending)], server: ["z": .delivered], confirmed: []).isEmpty, "nicht in der Liste: bleibt")

    // nie zurueck auf "wird zugestellt" (aelterer Serverstand)
    check(F.state(current: .delivered, server: .pending, confirmed: false) == .delivered, "zugestellt bleibt")
    check(F.state(current: .pending, server: .pending, confirmed: false) == .pending, "wartet weiter")
    check(F.state(current: .pending, server: .pending, confirmed: true) == .delivered, "POST schlaegt alten Stand")
    check(F.state(current: .failed, server: nil, confirmed: true) == .failed, "nicht zugestellt bleibt")
    check(F.state(current: .failed, server: .pending, confirmed: false) == .failed, "failed nicht zurueck auf wartend")
    check(F.apply([m(5, "a", .delivered)], server: ["a": .pending], confirmed: []).isEmpty, "kein Rueckfall")

    // Abgleich nur, solange etwas wartet, gedrosselt, nur sichtbar
    let waiting = [m(4, "o", .pending, mine: false), m(7, "a", .pending), m(9, "b", .pending)]
    check(F.oldestPending(waiting) == 7, "aelteste eigene wartende")
    check(F.oldestPending([m(7, "a", .delivered), m(8, "c", .failed)]) == nil, "nichts wartet")
    let t0 = Date(timeIntervalSince1970: 1000)
    check(F.due(waiting, lastCheck: .distantPast, now: t0), "faellig")
    check(!F.due(waiting, lastCheck: t0.addingTimeInterval(-5), now: t0), "gedrosselt (15 s)")
    check(F.due(waiting, lastCheck: t0.addingTimeInterval(-15), now: t0), "nach 15 s wieder")
    check(!F.due(waiting, lastCheck: .distantPast, now: t0, visible: false), "unsichtbar: kein Abgleich")
    check(!F.due([m(7, "a", .delivered)], lastCheck: .distantPast, now: t0), "ohne Wartende kein Abgleich")

    // vom Server entfernt
    let l = [m(7, "a", .pending), m(9, "b", .pending), m(500, "c", .pending), m(8, "d", .delivered)]
    check(F.gone(l, server: ["z": .delivered], more: false, maxSeq: 8, from: 7) == ["a", "b", "c"], "verschwunden")
    check(F.gone(l, server: ["b": .delivered], more: true, maxSeq: 300, from: 7) == ["a"], "Seite voll: nur bis maxSeq")
    check(F.gone(l, server: [:], more: false, maxSeq: 0, from: 1).isEmpty, "after=0 liefert die neuesten: nichts werten")

    // Serverliste lesen (ohne Entschluesseln)
    let j = try? JSONSerialization.jsonObject(with: Data(#"{"messages":[{"seq":5,"id":"a","kind":"msg","fstate":"pending"},{"seq":6,"id":"b","fstate":null},{"seq":7,"id":"c","fstate":"failed"}],"more":true}"#.utf8)) as? [String: Any]
    let p = F.parse(j)
    check(p.server == ["a": .pending, "b": .delivered, "c": .failed] && p.more && p.maxSeq == 7, "parse")
    let e = F.parse(nil)
    check(e.server.isEmpty && !e.more && e.maxSeq == 0, "parse leer")
}
