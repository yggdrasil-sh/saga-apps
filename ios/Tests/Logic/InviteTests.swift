import Foundation

// 2.5 (L7) "Erneut klingeln": Zustaende, Reihenfolge, Knopf-Regeln, rv, Ablauf "klingelt", Klick-Ablauf, eigenes
// erneutes Klingeln (invite_since) - gegen den Vertrag docs/APP-API.md "Anrufe: Erneut klingeln und Nicht dabei".

func inviteTests() {
    typealias I = Invites
    // Beispiel aus dem Vertrag (Antwort von /invite)
    let resp: [String: Any] = ["ok": true, "n": 1, "rv": 1794677400.123, "now": 1794677400.125, "invites_more": 0,
        "invites": [["who": "cora", "name": "Cora Beispiel", "avatar": "/api/avatar/x", "state": "ringing", "since": 1794677400.12],
                    ["who": "@~abcdefghijklmnop@peer.example", "name": "Person auf peer.example (mnop)", "host": "peer.example",
                     "handle": "~abcdefghijklmnop@peer.example", "state": "missed", "since": 1794677350.0],
                    ["who": "dora", "name": "Dora Alt", "state": "old", "since": 1794677300.0]]]
    let p = I.parse(resp["invites"]) ?? []
    check(p.count == 3 && p[0].avatar == "/api/avatar/x" && p[1].host == "peer.example" && p[1].handle == "~abcdefghijklmnop@peer.example",
          "invites: Felder gelesen")
    check(p[2].avatar == nil && p[2].state == .old && p[0].since == 1794677400.12, "invites: ohne Profilbild, Zustand, seit")
    check(I.parse(nil) == nil, "invites fehlt (aelterer Server) -> nil")
    check(I.parse([["who": "x", "state": "zauber"]])?.first?.state == I.State.none, "unbekannter Zustand wie none")
    check(I.parse([["who": "x"]])?.first?.name == "x", "ohne Namen: Kennung")
    check(I.parse([["name": "ohne who"]])?.isEmpty == true, "Eintrag ohne who verworfen")

    // Knopf-Regeln
    check(I.action(.ringing, host: true) == .hidden, "klingelt: kein Knopf")
    check(I.action(.declined, host: false) == .again && I.action(.missed, host: false) == .again && I.action(.left, host: false) == .again,
          "abgelehnt/verpasst/verlassen: Erneut klingeln")
    check(I.action(.none, host: false) == .ring, "nie geklingelt: Klingeln")
    check(I.action(.removed, host: false) == .hidden && I.action(.removed, host: true) == .again, "entfernt: nur Gastgeber")
    check(I.action(.old, host: true) == .old, "App zu alt: deaktiviert")

    // Liste uebernehmen, rv, Uhrabstand
    var L = I.List()
    check(!L.known && !L.visible, "anfangs unbekannt")
    let dev = 1000.0                                                        // Geraete-Uhr (Server ist 1794676400.125 voraus)
    check(L.apply(resp, device: dev), "Antwort uebernommen")
    check(L.known && L.visible && L.rv == 1794677400.123 && abs(L.skew - (1794677400.125 - dev)) < 1e-6, "rv und Uhrabstand")
    var older = resp; older["rv"] = 1794677399.0; older["invites"] = [] as [Any]
    check(!L.apply(older, device: dev) && L.entries?.count == 3, "aelteres rv verworfen")
    var same = resp; same["invites"] = [["who": "cora", "name": "Cora Beispiel", "state": "declined", "since": 1794677401.0]]
    check(L.apply(same, device: dev) && L.entries?.count == 1, "gleiches rv uebernommen (nicht kleiner)")
    var norv = resp; norv.removeValue(forKey: "rv")
    check(L.apply(norv, device: dev) && L.rv == 1794677400.123, "ohne rv uebernommen, Stand bleibt")
    check(!L.apply(["rv": 1794677500.0], device: dev), "ohne invites: nichts")
    var ev: [String: Any] = ["call_id": String(repeating: "a", count: 32), "chat_id": 42, "rv": 1794677412.481, "now": 1794677412.479, "ring_s": 30,
                             "invites_more": 2, "invites": [["who": "cora", "name": "Cora Beispiel", "state": "declined", "since": 1794677412.4]]]
    check(L.apply(ev, device: dev) && L.ringS == 30 && L.more == 2 && L.count() == 3, "Ereignis call.invites: ring_s, und n weitere")
    ev["invites"] = [] as [Any]; ev["invites_more"] = 0; ev["rv"] = 1794677413.0
    check(L.apply(ev, device: dev) && L.known && !L.visible, "leer = alle dabei, Abschnitt aus")

    // Ablauf "klingelt" nach since + ring_s (Server-Uhr)
    var R = I.List()
    let now0 = 5000.0
    R.apply(["rv": 1.0, "now": now0, "ring_s": 45, "invites": [
        ["who": "a", "name": "Anna Beispiel", "state": "missed", "since": now0 - 100],
        ["who": "b", "name": "Bert Beispiel", "state": "declined", "since": now0 - 5],
        ["who": "c", "name": "Cora Beispiel", "state": "ringing", "since": now0 - 40],
        ["who": "d", "name": "Dora Beispiel", "state": "old", "since": now0 - 300],
        ["who": "e", "name": "Emil Beispiel", "state": "none"],
        ["who": "f", "name": "Frida Beispiel", "state": "removed", "since": now0 - 9],
        ["who": "g", "name": "Gert Beispiel", "state": "left", "since": now0 - 20]]], device: 100)
    check(R.rows(device: 100).map(\.entry.who) == ["c", "b", "a", "g", "e", "f", "d"], "Reihenfolge nach Zustand")
    check(R.state(R.entries![2], device: 104) == .ringing && R.state(R.entries![2], device: 105.01) == .missed, "klingelt -> nicht angenommen nach ring_s")
    check(abs((R.nextChange(device: 100) ?? 0) - 5) < 1e-6, "naechster Ablauf in 5 s")
    check(R.rows(device: 106).map(\.entry.who) == ["b", "a", "c", "g", "e", "f", "d"], "abgelaufen: sortiert wie missed (nach Name)")
    check(R.nextChange(device: 106) == nil, "danach kein Ablauf")
    check(I.ringAll(R.rows(device: 100)) == ["b", "a", "e"], "Alle erneut klingeln: abgelehnt, verpasst, nie geklingelt")
    var R1 = I.List(); R1.apply(["invites": [["who": "a", "name": "A", "state": "missed"], ["who": "b", "name": "B", "state": "left"]]], device: 0)
    check(I.ringAll(R1.rows(device: 0)).isEmpty, "Alle erneut klingeln erst ab zwei")
    var big = I.List()
    big.apply(["invites": (0..<60).map { ["who": "u\($0)", "name": "U\($0)", "state": "missed"] }], device: 0)
    check(I.ringAll(big.rows(device: 0)).count == 50, "hoechstens 50 je /invite")

    // Klick: vorlaeufig, Antwort, Fehler
    var K = R
    let until = K.ringStart(["b"], device: 100)
    check(K.state(K.entries![1], device: 101) == .ringing && K.rows(device: 101).first { $0.entry.who == "b" }?.pending == true, "Klick: sofort klingelt (vorlaeufig)")
    check(Invites.action(K.state(K.entries![1], device: 101), host: false) == .hidden, "vorlaeufig: Knopf weg")
    check(I.ringAll(K.rows(device: 101)) == ["a", "e"], "vorlaeufig zaehlt nicht fuer Alle")
    check(abs((K.nextChange(device: 100) ?? 0) - 5) < 1e-6, "vorlaeufig: Ablauf weiter beachtet")
    var K2 = K
    K2.ringEnd(["b"], until: until)
    check(K2.state(K2.entries![1], device: 101) == .declined, "Fehler: zurueckgenommen")
    let until2 = K.ringStart(["b"], device: 102)
    K.ringEnd(["b"], until: until)
    check(K.pending["b"] == until2, "aelterer Klick nimmt neueren nicht zurueck")
    check(K.state(K.entries![1], device: 102 + I.pendingHold + 1) == .declined, "ohne Antwort: verfaellt")
    check(I.failure(code: 200, body: nil) == nil, "200 ok")
    check(I.failure(code: 429, body: ["error": "x"]) == .tooOften, "429 -> eigener Text")
    let t403 = "Nur wer den Anruf begonnen hat, kann entfernte Personen erneut klingeln lassen."
    check(I.failure(code: 403, body: ["error": t403, "removed": true]) == .server(t403), "403 -> Text des Servers")
    check(I.failure(code: nil, body: nil) == .generic && I.failure(code: 500, body: [:]) == .generic, "Netz/ohne Text -> allgemein")

    // Eigenes Klingeln: Alter ab invite_since
    check(I.ringAge(now: 1000, device: 1, inviteSince: 990, created: 100) == 10, "Alter ab invite_since")
    check(I.ringAge(now: 1000, device: 1, inviteSince: nil, created: 100) == 900, "aeltere Server: ab created")
    check(I.ringAge(now: nil, device: 1000, inviteSince: nil, created: 970) == 30, "ohne now: Geraete-Uhr")
    // erneut klingeln, obwohl hier schon erledigt
    check(I.ringAgain(invite: "ringing", mine: 0, state: "active", inviteSince: 990, now: 1000, device: 0, ringS: 45, handled: 900) == 35,
          "neues Klingeln: Restdauer since + ring_s - now")
    check(I.ringAgain(invite: "ringing", mine: 0, state: "active", inviteSince: 990, now: 1000, device: 0, ringS: 45, handled: nil) == 35,
          "nie hier geklingelt (selbst begonnen/verlassen): klingelt")
    check(I.ringAgain(invite: "ringing", mine: 0, state: "active", inviteSince: 990, now: 1000, device: 0, ringS: 45, handled: 990) == nil,
          "dasselbe Klingeln (z. B. hier abgelehnt, Antwort unterwegs): nicht nochmal")
    check(I.ringAgain(invite: "ringing", mine: 0, state: "active", inviteSince: 990, now: 1000, device: 0, ringS: 45, handled: 995) == nil,
          "hier beigetreten (ohne Klingeln) und aufgelegt: Einladung noch ringing, aelter als das Auflegen - nicht")
    check(I.ringAgain(invite: "ringing", mine: 0, state: "active", inviteSince: nil, now: 1000, device: 0, ringS: 45, handled: nil) == nil,
          "aeltere Server ohne invite_since: wie bisher")
    check(I.ringAgain(invite: "missed", mine: 0, state: "active", inviteSince: 990, now: 1000, device: 0, ringS: 45, handled: nil) == nil, "nicht ringing")
    check(I.ringAgain(invite: "ringing", mine: 1, state: "active", inviteSince: 990, now: 1000, device: 0, ringS: 45, handled: nil) == nil, "eigenes Geraet dabei")
    check(I.ringAgain(invite: "ringing", mine: 0, state: "ended", inviteSince: 990, now: 1000, device: 0, ringS: 45, handled: nil) == nil, "beendet")
    check(I.ringAgain(invite: "ringing", mine: 0, state: "active", inviteSince: 950, now: 1000, device: 0, ringS: 45, handled: nil) == nil, "Klingeldauer vorbei")
    // Abfrage /api/calls/active: invite_since und now werden gelesen
    let run = CallRoom.running(["id": String(repeating: "c", count: 32), "kind": "group", "state": "active", "invite": "ringing",
                                "created": 100.0, "invite_since": 990.5, "now": 1000.25, "ring_s": 45, "mine": [] as [Any]])
    check(run?.inviteSince == 990.5 && run?.now == 1000.25, "Running: invite_since/now")
    check(CallRoom.running(["id": String(repeating: "c", count: 32), "kind": "group", "state": "active"])?.inviteSince == nil, "Running: aeltere Server")
    // Gruppen-Klingeln mit dem Alter ab invite_since: spaeteres erneutes Klingeln klingelt (vorher: Alter ab created -> nie)
    let age = I.ringAge(now: 1000, device: 0, inviteSince: 990, created: 100)
    check(CallRoom.ringGroup(invite: "ringing", mine: [], silent: false, busy: false, age: age, ringS: 45), "erneutes Klingeln im laufenden Anruf klingelt")
    check(CallLogic.ringPoll(group: true, state: "active", mine: 0, invite: "ringing", age: age, ringS: 45) == .keep, "Abfrage waehrend des Klingelns: weiter")
}
