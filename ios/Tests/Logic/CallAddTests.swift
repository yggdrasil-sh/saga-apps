import Foundation

// 2.5 (L8 "Personen hinzufuegen", L9 "1:1 -> Konferenz"): Knopf-Regeln, Plaetze, Auswahl (ausblenden/markieren),
// Antwort- und Fehlerauswertung von POST /api/calls/{id}/add, Umzug-Zustand (moved, call.move, verpasster Umzug),
// Chat-Eintrag moved - gegen den Vertrag docs/APP-API.md (Beispiele von dort).

func callAddTests() {
    typealias A = CallAdd
    let both = [A.feature, A.featureMove]

    // Felder des Anruf-Objekts
    let g = A.Caps(["kind": "group", "legs": [], "can_add": true, "add_page": "Plan"])
    check(g.canAdd && g.addPage == "Plan" && !g.canMove, "Caps: can_add/add_page gelesen")
    check(A.Caps(["can_add": false, "add_page": "Plan"]).addPage == nil, "Caps: add_page nur mit can_add")
    let dmh = A.Caps(["kind": "group", "dm": true, "legs": [], "can_move": false, "move_block": "fed", "move_hint": "Geht nicht"])
    check(!dmh.canMove && dmh.moveBlock == "fed" && dmh.moveHint == "Geht nicht", "Caps: move_block/move_hint gelesen")
    check(A.Caps(["can_move": true, "move_block": "fed"]).moveBlock == nil, "Caps: can_move -> kein move_block")
    check(A.Caps([:]) == A.Caps(), "Caps: aelterer Server -> alles aus")

    // Knopf-Regeln
    func mode(_ f: [String] = both, kind: String = "group", dm: Bool = false, caps: A.Caps, live: Bool = true, comp: Bool = false) -> A.Mode {
        A.mode(features: f, kind: kind, dmRoom: dm, caps: caps, live: live, companion: comp)
    }
    check(mode(caps: A.Caps(canAdd: true)) == .add, "Gruppe mit can_add -> add")
    check(mode(caps: A.Caps(canAdd: false)) == .none, "Gruppe ohne can_add (Benutzergruppe, kein Admin) -> kein Knopf")
    check(mode([A.featureMove], caps: A.Caps(canAdd: true)) == .none, "ohne Feature call_add -> kein Knopf")
    check(mode([], caps: A.Caps(canAdd: true)) == .none, "aelterer Server -> kein Knopf")
    check(mode(caps: A.Caps(canAdd: true), live: false) == .none, "Anruf (noch) nicht da -> kein Knopf")
    check(mode(caps: A.Caps(canAdd: true), comp: true) == .none, "Begleitgeraet -> kein Knopf")
    check(mode(dm: true, caps: A.Caps(canMove: true)) == .move, "1:1 ueber Knoten mit can_move -> move")
    check(mode(dm: true, caps: A.Caps(moveBlock: "ringing")) == .move, "1:1 Objekt vom Klingeln (ringing) -> move (Server prueft frisch)")
    check(mode(dm: true, caps: A.Caps(moveBlock: "fed", moveHint: "X")) == .hint("X"), "1:1 foederiert -> nur Hinweis")
    check(mode(dm: true, caps: A.Caps(moveBlock: "ended")) == .none, "1:1 beendet -> kein Knopf")
    check(mode([A.feature], dm: true, caps: A.Caps(canMove: true)) == .none, "1:1 ohne call_move -> kein Knopf (aeltere Server unveraendert)")
    check(mode(dm: true, caps: A.Caps(canAdd: true)) == .none, "1:1 ueber Knoten: can_add allein reicht nicht")
    check(mode(kind: "dm", caps: A.Caps()) == .hint(nil), "direktes 1:1 -> Hinweis (Ersatztext)")
    check(mode(kind: "dm", caps: A.Caps(moveBlock: "direct", moveHint: "Direkt")) == .hint("Direkt"), "direktes 1:1 -> Text des Servers")
    check(mode([A.feature], kind: "dm", caps: A.Caps()) == .none, "direktes 1:1 ohne call_move -> nichts")

    // Plaetze
    check(A.seats(legs: 6, limit: 6).full && A.seats(legs: 6, limit: 6).left == 0, "voll: legs >= limit")
    check(!A.seats(legs: 2, limit: 6).full && A.seats(legs: 2, limit: 6).left == 4, "Plaetze frei")
    check(!A.seats(legs: 3, limit: 0).full, "Grenze unbekannt -> nicht voll")

    // Auswahl
    let legs = CallRoom.legs([["device": "d1", "who": "ich", "name": "Max", "ref": "~ich0000000000000"],
                              ["device": "d2", "who": "cora", "name": "Cora", "ref": "~cora000000000000"],
                              ["device": "d3", "who": "@~abcdefghijklmnop@peer.example", "name": "Fremd", "handle": "~abcdefghijklmnop@peer.example", "host": "peer.example"]])
    check(legs.count == 3 && legs[1].ref == "~cora000000000000", "legs[].ref gelesen")
    let inCall = A.inCall(legs)
    let inv = Invites.parse([["who": "dora", "ref": "~dora000000000000", "name": "Dora", "state": "missed"]]) ?? []
    check(inv.first?.ref == "~dora000000000000", "invites[].ref gelesen")
    let ks = [ChatContact(ref: "~cora000000000000", name: "Cora"), ChatContact(ref: "~dora000000000000", name: "Dora"),
              ChatContact(ref: "~emil000000000000", name: "Emil", gone: true),
              ChatContact(ref: "~abcdefghijklmnop@peer.example", name: "Fremd", host: "peer.example", remote: true),
              ChatContact(ref: "~zoe0000000000000", name: "Zoe", host: "peer.example", remote: true)]
    let v = A.visible(A.people(contacts: ks), inCall: inCall, away: A.away(inv))
    check(v.map(\.ref) == ["~dora000000000000", "~emil000000000000", "~zoe0000000000000"], "wer im Anruf ist, fehlt (ref und Handle)")
    check(v[0].away && !v[1].away, "Mitglied ohne Geraet: klingelt erneut")
    check(!A.selectable(v[1], seats: A.seats(legs: 1, limit: 6)), "Konto weg -> deaktiviert")
    check(!A.selectable(v[0], seats: A.seats(legs: 6, limit: 6)) && A.selectable(v[0], seats: A.seats(legs: 2, limit: 6)), "voll -> deaktiviert")
    let s = A.people(search: ["users": [["username": "cora", "name": "Cora", "sub": "cora"], ["username": "jo", "name": ""], ["username": "cora"]],
                              "remote": [["handle": "~qqqqqqqqqqqqqqqq@peer.example", "label": "Quinn", "host": "peer.example"]]])
    check(s.map(\.ref) == ["cora", "jo", "~qqqqqqqqqqqqqqqq@peer.example"], "Suche: users[].username, remote[].handle, doppelt einmal")
    check(s[2].remote && s[2].name == "Quinn" && s[1].name == "jo", "Suche: Name aus label, sonst Kennung")
    check(A.visible(s, inCall: inCall, away: []).map(\.ref) == ["jo", "~qqqqqqqqqqqqqqqq@peer.example"], "Suche: who im Anruf fehlt")
    check((A.body("~x", confirmPage: false)["uids"] as? [String]) == ["~x"] && A.body("~x", confirmPage: false)["confirm_page"] == nil,
          "Body: uids")
    check((A.body("~x", confirmPage: true)["confirm_page"] as? Bool) == true, "Body: confirm_page nach Rueckfrage")

    // Antworten von /add (Beispiele aus dem Vertrag)
    let ok: [String: Any] = ["ok": true, "n": 1, "added": ["~x4bq"], "failed": [], "pending": [], "request": [],
                             "invites": [["who": "~x4bq@saga.example", "name": "Dora Neu", "state": "ringing", "since": 1794904205.1]],
                             "invites_more": 0, "seats": ["used": 2, "limit": 6, "left": 4, "topo": "mesh"], "rv": 1794904205.12, "now": 1794904205.13]
    guard case .done(let d) = A.outcome(code: 200, body: ok, confirmed: false) else { check(false, "200 -> done"); return }
    check(d.n == 1 && d.added == ["~x4bq"] && d.moved == nil && A.note(d) == .ringing, "200: klingelt, kein Umzug")
    if case .done(let d2) = A.outcome(code: 200, body: ["n": 0, "added": []], confirmed: false) { check(A.note(d2) == .already, "n 0 -> schon im Anruf") }
    if case .done(let d3) = A.outcome(code: 200, body: ["n": 0, "added": ["~a"], "request": ["~a"]], confirmed: false) {
        check(A.note(d3) == .request, "request -> eingeladen (Nachrichtenanfrage)")
    }
    if case .done(let d4) = A.outcome(code: 200, body: ["n": 0, "pending": ["@~a@x"]], confirmed: false) { check(A.note(d4) == .pending, "pending -> anderer Server") }
    if case .done(let d5) = A.outcome(code: 200, body: ["n": 1, "failed": [["who": "~b", "error": "Mit diesem Konto ist kein Chat möglich.", "kind": "unreachable"]]], confirmed: false) {
        check(A.note(d5) == .failed("Mit diesem Konto ist kein Chat möglich."), "failed: Text der ersten")
    }
    let pc: [String: Any] = ["error": "Dora bekommt Zugriff auf die Seite „Plan“.", "page_confirm": true, "title": "Plan", "names": ["Dora"]]
    check(A.outcome(code: 409, body: pc, confirmed: false) == .pageConfirm("Dora bekommt Zugriff auf die Seite „Plan“."), "409 page_confirm -> Rueckfrage")
    check(A.outcome(code: 409, body: pc, confirmed: true) == .server("Dora bekommt Zugriff auf die Seite „Plan“."), "page_confirm nach Zustimmung -> kein zweites Mal fragen")
    check(A.outcome(code: 409, body: ["error": "Der Anruf ist voll.", "full": true, "seats": ["used": 6, "limit": 6]], confirmed: false) == .server("Der Anruf ist voll."), "409 full -> Text")
    check(A.outcome(code: 403, body: ["error": "Keine Rechte", "rights": true], confirmed: false) == .server("Keine Rechte"), "403 rights -> Text")
    check(A.outcome(code: 403, body: ["error": "Nur wer …", "removed": true], confirmed: false) == .server("Nur wer …"), "403 removed -> Text")
    check(A.outcome(code: 409, body: ["error": "Erst wenn …", "dm": true, "move_block": "ringing"], confirmed: false) == .server("Erst wenn …"), "409 dm/move_block -> Text")
    check(A.outcome(code: 429, body: ["error": "x"], confirmed: false) == .tooOften, "429 -> zu oft")
    check(A.outcome(code: 500, body: nil, confirmed: false) == .generic && A.outcome(code: nil, body: nil, confirmed: false) == .generic, "ohne Text/Netz -> allgemein")

    // 1:1 -> Konferenz
    let mv: [String: Any] = ["ok": true, "n": 1, "added": ["~x4bq"], "failed": [], "invites": [], "seats": [:], "rv": 1794904205.12,
                             "moved": ["from_chat": 12, "chat_id": 37], "call": ["id": "c", "chat_id": 37, "kind": "group", "can_add": true]]
    if case .done(let dm) = A.outcome(code: 200, body: mv, confirmed: false) { check(dm.moved == A.Moved(from: 12, to: 37), "200 moved -> Umzug 12 -> 37") }
    else { check(false, "moved: done") }
    check(A.moved(["from_chat": 12, "chat_id": true]) == nil && A.moved(["chat_id": 0]) == nil && A.moved(["chat_id": "37"]) == A.Moved(from: 0, to: 37),
          "moved: chat_id nur positive Zahl")
    check(A.chatID(1.5) == nil && A.chatID("1e3") == nil && A.chatID("012") == nil && A.chatID(42) == 42, "Chat-ID streng")
    let cid = String(repeating: "a", count: 32)
    let ev: [String: Any] = ["call_id": cid, "from_chat": 12, "chat_id": 37, "kind": "group", "media": "audio", "by": "eva"]
    check(A.moveEvent(ev, callID: cid) == A.Moved(from: 12, to: 37), "call.move fuer den laufenden Anruf")
    check(A.moveEvent(ev, callID: String(repeating: "b", count: 32)) == nil && A.moveEvent(ev, callID: nil) == nil, "call.move fremder Anruf -> nichts")
    var ev2 = ev; ev2["kind"] = "dm"
    check(A.moveEvent(ev2, callID: cid) == nil, "call.move ohne kind group -> nichts")
    let after: [String: Any] = ["id": cid, "chat_id": 37, "kind": "group", "legs": []]
    check(A.missedMove(after, dmRoom: true, chatID: 12) == A.Moved(from: 12, to: 37), "Abfrage nach verpasstem call.move -> Umzug")
    check(A.missedMove(after, dmRoom: false, chatID: 12) == nil, "echte Gruppe -> kein Umzug")
    var stillDM = after; stillDM["dm"] = true; stillDM["chat_id"] = 12
    check(A.missedMove(stillDM, dmRoom: true, chatID: 12) == nil, "1:1 unveraendert -> kein Umzug")
    var noLegs = after; noLegs["legs"] = nil
    check(A.missedMove(noLegs, dmRoom: true, chatID: 12) == nil && A.missedMove(after, dmRoom: true, chatID: 0) == nil, "Teilobjekt bzw. Chat unbekannt -> kein Umzug")
    check(A.openNew(open: 12, move: A.Moved(from: 12, to: 37)) && !A.openNew(open: 5, move: A.Moved(from: 12, to: 37)) && !A.openNew(open: nil, move: A.Moved(from: 12, to: 37)),
          "altes 1:1 offen -> neue Gruppe oeffnen")
    check(A.resendTitle(added: ["~a"], kind: "free", title: "Team", moved: false), "Gruppenname fuer Neue senden")
    check(!A.resendTitle(added: [], kind: "free", title: "Team", moved: false) && !A.resendTitle(added: ["~a"], kind: "page", title: "Team", moved: false)
          && !A.resendTitle(added: ["~a"], kind: "free", title: " ", moved: false) && !A.resendTitle(added: ["~a"], kind: "free", title: "T", moved: true),
          "kein Name/keine Neuen/Seiten-Chat/nach Umzug -> nichts senden")

    // Fokus nach "Abbrechen" der Seiten-Rueckfrage (Pruefer F3): iPhone nie ins Suchfeld (Tastatur)
    check(A.askCancelFocus(phone: false, voiceOver: false) == .field && A.askCancelFocus(phone: false, voiceOver: true) == .field,
          "iPad/Mac: Abbrechen -> Suchfeld")
    check(A.askCancelFocus(phone: true, voiceOver: false) == .keep, "iPhone: Abbrechen -> kein Feld-Fokus (keine Tastatur)")
    check(A.askCancelFocus(phone: true, voiceOver: true) == .row, "iPhone mit VoiceOver: Abbrechen -> Zeile der Person")

    // Chat-Eintrag im alten 1:1
    let e = CallLogic.Entry(json: Data(#"{"type":"call","call":"x","media":"audio","status":"ended","dur":42,"from":"max","to":"eva","moved":37}"#.utf8))
    check(e?.moved == 37 && e?.kind() == .ended("0:42"), "Eintrag moved: Gruppe 37, Dauer bis zum Umzug")
    check(CallLogic.Entry(json: Data(#"{"type":"call","status":"ended","dur":42,"moved":"x"}"#.utf8))?.moved == nil
          && CallLogic.Entry(json: Data(#"{"type":"call","status":"ended","dur":42,"moved":true}"#.utf8))?.moved == nil
          && CallLogic.Entry(json: Data(#"{"type":"call","status":"ended","dur":42}"#.utf8))?.moved == nil, "Eintrag moved nur mit gueltiger Chat-ID")
}
