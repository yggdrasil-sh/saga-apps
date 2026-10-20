import Foundation

// Gelesen-Bestaetigungen und Online-Status (2.4): Faehigkeiten, Gegenseitigkeit, faellige Bestaetigung, Haken,
// "Gelesen von", Online-Abo (Lebenszyklus). Krypto gegen die Vektoren: Tests/ChatVectors.
func receiptTests() {
    typealias R = ReceiptLogic
    // Faehigkeiten: aelterer Server -> nichts; neuer Server -> Standard an
    let old = R.caps(["uid": "anna"])
    check(!old.any && !old.receiptsOn && !old.onlineOn, "aelterer Server: Gelesen/Online ausblenden")
    check(!R.caps(nil).any, "ohne Antwort: ausblenden")
    let me: [String: Any] = ["features": ["receipts", "online"], "privacy": ["receipts": true, "online": true],
                             "status_limits": ["receipt_gap": 2.0, "presence_ttl": 90, "presence_users": 50, "last_round": 300]]
    var caps = R.caps(me)
    check(caps.receiptsOn && caps.onlineOn && caps.any, "neuer Server: beides an")
    check(caps.limits.receiptGap == 2 && caps.limits.presenceTTL == 90 && caps.limits.presenceUsers == 50 && caps.limits.lastRound == 300, "Grenzen gelesen")
    check(R.caps(["features": ["receipts", "online"]]).privacy == R.Privacy(), "ohne privacy: Standard an")
    check(R.caps(["features": ["receipts"]]).receipts && !R.caps(["features": ["receipts"]]).online, "nur Gelesen: Online ausblenden")
    // Einstellungen getrennt, gegenseitig
    check(R.privacy(["receipts": false, "online": true]) == R.Privacy(receipts: false, online: true), "getrennt schaltbar")
    check(R.privacy(["online": false]).receipts, "fehlendes Feld = an")
    check(R.privacyBody(receipts: false).count == 1 && R.privacyBody(online: false)["online"] as? Bool == false, "PUT nur mit dem geaenderten Feld")
    caps.privacy.receipts = false
    check(!caps.receiptsOn && caps.onlineOn, "Gelesen aus: nicht senden, nicht sehen (gegenseitig); Online bleibt")
    let dm = R.ChatFlags()
    check(!R.receiptsActive(caps, dm), "Gelesen aus: keine Haken ✓✓ im Chat")
    caps.privacy = R.Privacy(receipts: true, online: false)
    check(R.receiptsActive(caps, dm) && !R.onlineActive(caps, dm), "Online aus: kein Status bei anderen")
    caps.privacy = R.Privacy()
    check(!R.receiptsActive(caps, R.ChatFlags(fed: true, request: true)), "Nachrichtenanfrage: keine Bestaetigung")
    check(!R.receiptsActive(caps, R.ChatFlags(fed: true, readonly: true)), "schreibgeschuetzt: keine Bestaetigung")
    check(!R.receiptsActive(caps, R.ChatFlags(fed: true, receiptsOK: false)), "2.3-Server: keine Bestaetigung versiegeln")
    check(!R.onlineActive(caps, R.ChatFlags(fed: true, onlineOK: false)), "2.3-Server: kein Online-Status")
    check(!R.onlineActive(caps, R.ChatFlags(kind: "group")), "Online nur in Direkt-Chats")
    check(R.flags([:]) == (true, true) && R.flags(["receipts_ok": false, "online_ok": true]) == (false, true), "receipts_ok/online_ok, fehlt = true")

    // Format
    let b = R.body(id: "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", n: 17, ts: 1_792_444_800_000)
    check(b == "{\"id\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\",\"n\":17,\"ts\":1792444800000}", "body genau wie in den Vektoren")
    check(R.parseBody(b) == R.Seen(n: 17, id: "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", ts: 1_792_444_800_000), "body lesen")
    check(R.parseBody("{\"id\":\"x\",\"n\":0}") == nil && R.parseBody("{\"id\":\"x\",\"n\":1.5}") == nil && R.parseBody("x") == nil, "falsche Form ignorieren")
    let s5 = R.Seen(n: 5, id: "a", ts: 1), s7 = R.Seen(n: 7, id: "b", ts: 2)
    check(R.merge(nil, s5) == s5 && R.merge(s5, s7) == s7 && R.merge(s7, s5) == nil && R.merge(s7, s7) == nil, "je Person nur das groesste n")
    check(R.keyID("bert") == "backup:bert" && R.keyID("~abc@b.example") == "backup:~abc@b.example", "Schluessel-ID")

    // Faellig: nur Sichtbares, nicht eigenes, groesser als zuletzt
    let msgs = [R.Pos(id: "1", n: 1, mine: false), R.Pos(id: "2", n: 2, mine: true), R.Pos(id: "3", n: 3, mine: false), R.Pos(id: "4", n: 0, mine: false)]
    check(R.want(msgs, sent: 0, seenUpTo: 3) == msgs[2], "neueste fremde mit Position")
    check(R.want(msgs, sent: 3, seenUpTo: 3) == nil, "schon bestaetigt: nichts")
    check(R.want(msgs, sent: 0, seenUpTo: 2) == msgs[0], "noch nicht sichtbar: nur bis zur gesehenen")
    check(R.want(msgs, sent: 0, seenUpTo: 0) == nil, "nichts gesehen: nichts senden")
    check(R.want([R.Pos(id: "9", n: 9, mine: true)], sent: 0, seenUpTo: 9) == nil, "nur eigene: nichts")
    check(R.wait(lastAt: nil, now: 100, gap: 2) == 0 && R.wait(lastAt: 99.5, now: 100, gap: 2) == 1.5 && R.wait(lastAt: 90, now: 100, gap: 2) == 0,
          "hoechstens eine je 2 s (nachziehen)")
    check(R.readResult("ok") == .sent && R.readResult("none") == .sent && R.readResult("off") == .off && R.readResult("rate") == .retryLater
          && R.readResult("bad") == .drop && R.readResult(nil) == .drop, "Antwort von /read")

    // Haken
    let seen = ["bert": R.Seen(n: 10, id: "x", ts: 5), "cora": R.Seen(n: 4, id: "y", ts: 6)]
    func t(_ n: Int, _ others: [String], pending: Bool = false, failed: Bool = false, mine: Bool = true, active: Bool = true, shown: Bool = true) -> R.Tick {
        R.tick(mine: mine, pending: pending, failed: failed, n: n, others: others, seen: seen, active: active, shown: shown)
    }
    check(t(5, ["bert"], pending: true) == .pending, "⏳ wird zugestellt")
    check(t(5, ["bert"], failed: true) == .none, "nicht zugestellt: eigene Anzeige, kein Haken")
    check(t(5, ["bert"], mine: false) == .none, "fremde Nachricht: kein Haken")
    check(t(11, ["bert"]) == .delivered, "✓ zugestellt")
    check(t(10, ["bert"]) == .read(count: 1, all: true), "✓✓ gelesen (Direkt-Chat)")
    check(t(5, ["bert", "cora"]) == .read(count: 1, all: false), "Gruppe: ✓✓ mit Zaehler")
    check(t(4, ["bert", "cora"]) == .read(count: 2, all: true), "Gruppe: alle gelesen")
    check(t(10, ["bert"], active: false) == .delivered, "Gelesen aus (gegenseitig): nur ✓")
    check(t(10, ["bert"], shown: false) == .none, "aelterer Server: kein Haken wie bisher")
    check(t(5, ["bert"], pending: true, shown: false) == .pending, "⏳ auch auf aelteren Servern")
    check(t(0, ["bert"]) == .delivered, "ohne Position (noch ausstehend): ✓")
    let rd = R.readers(n: 4, members: [(key: "bert", name: "Bert"), (key: "cora", name: "Cora"), (key: "dora", name: "Dora")], seen: seen)
    check(rd.map(\.name) == ["Cora", "Bert"], "Gelesen von: neueste zuerst, ohne Unbekannte")
    check(R.readers(n: 0, members: [(key: "bert", name: "Bert")], seen: seen).isEmpty, "ohne Position: niemand")

    // Online-Status
    let p = R.presence(["enabled": true, "ttl": 90, "users": ["bert": ["online": true, "last": NSNull()],
                                                             "@~abcdefghijklmnop@b.example": ["online": false, "last": 1_792_449_900.0],
                                                             "cora": ["online": false, "last": NSNull()]]])
    check(p.enabled && p.users.count == 2 && p.users["bert"] == R.Presence(online: true, last: nil)
          && p.users["@~abcdefghijklmnop@b.example"]?.last == 1_792_449_900, "Abo-Antwort; unbekannt = nichts anzeigen")
    check(!R.presence(["enabled": false, "users": [:]]).enabled, "eigener Status aus -> enabled false")
    var map = p.users
    check(R.applyLive(["username": "bert", "online": false, "last": 1_792_450_200.0], to: &map) && map["bert"]?.online == false, "chat.presence offline")
    check(R.applyLive(["username": "bert", "online": false, "last": NSNull()], to: &map) && map["bert"] == nil, "abgeschaltet -> entfernen")
    check(!R.applyLive(["username": "bert", "online": false, "last": NSNull()], to: &map), "nichts geaendert")
    let now = Date(timeIntervalSince1970: 1_792_450_000)
    check(R.shown(R.Presence(online: true, last: nil), now: now) == .online, "online")
    check(R.shown(R.Presence(online: false, last: 1_792_449_700), now: now) == .last(Date(timeIntervalSince1970: 1_792_449_700)), "zuletzt online")
    check(R.shown(R.Presence(online: false, last: 1_792_450_300), now: now) == .last(now), "zuletzt online nie in der Zukunft")
    check(R.shown(nil, now: now) == .none && R.shown(R.Presence(online: false, last: nil), now: now) == .none, "unbekannt: nichts")

    // Wen abonnieren
    let open = R.PeerRef(user: "bert", flags: R.ChatFlags())
    let list = [R.PeerRef(user: "bert", flags: R.ChatFlags()), R.PeerRef(user: "", flags: R.ChatFlags(kind: "group")),
                R.PeerRef(user: "cora", flags: R.ChatFlags(), archived: true), R.PeerRef(user: "dora", flags: R.ChatFlags(fed: true, request: true)),
                R.PeerRef(user: "@~x@b.example", flags: R.ChatFlags(fed: true, onlineOK: false)), R.PeerRef(user: "emil", flags: R.ChatFlags())]
    check(R.presenceWant(open: open, list: list, caps: caps) == ["bert", "emil"], "nur sichtbare Direkt-Chats, ohne Archiv/Anfrage/2.3, ohne Doppelte")
    check(R.presenceWant(open: open, list: nil, caps: caps) == ["bert"], "Liste nicht sichtbar: nur offener Chat")
    var off = caps; off.privacy.online = false
    check(R.presenceWant(open: open, list: list, caps: off).isEmpty, "Online aus: nicht abonnieren (gegenseitig)")
    check(R.presenceWant(open: open, list: list, caps: old).isEmpty, "aelterer Server: nicht abonnieren")
    let many = (0..<80).map { R.PeerRef(user: "u\($0)", flags: R.ChatFlags()) }
    check(R.presenceWant(open: nil, list: many, caps: caps).count == 30, "Liste: erste 30")

    // Abo-Lebenszyklus
    var sub = R.PresenceSub()
    check(sub.plan(want: [], active: true, now: 0) == .none, "nichts sichtbar: kein Abo")
    check(sub.plan(want: ["bert"], active: true, now: 0) == .subscribe(["bert"]), "sichtbar: abonnieren")
    check(sub.plan(want: ["bert"], active: true, now: 15) == .none, "innerhalb 60 s: nichts")
    check(sub.plan(want: ["bert"], active: true, now: 60) == .subscribe(["bert"]), "nach 60 s erneuern")
    check(sub.plan(want: ["bert", "emil"], active: true, now: 70) == .subscribe(["bert", "emil"]), "Sichtbares geaendert: sofort")
    check(sub.plan(want: ["emil", "bert"], active: true, now: 75) == .none, "Reihenfolge egal")
    check(sub.plan(want: ["bert", "emil"], active: false, now: 80) == .leave(["bert", "emil"]), "Hintergrund: abmelden")
    check(sub.plan(want: ["bert", "emil"], active: false, now: 95) == .none, "im Hintergrund: still")
    check(sub.plan(want: ["bert"], active: true, now: 100) == .subscribe(["bert"]), "wieder vorne: sofort abonnieren")
    check(sub.plan(want: [], active: true, now: 101) == .leave(["bert"]), "Chat/Liste verlassen: abmelden")
    // Personen anderer Server: erste Antwort leer -> eine Minute lang alle 10 s
    let fedU = "@~abcdefghijklmnop@b.example"
    _ = sub.plan(want: [fedU], active: true, now: 200)
    sub.answered([:], now: 200)
    check(sub.plan(want: [fedU], active: true, now: 205) == .none && sub.plan(want: [fedU], active: true, now: 210) == .subscribe([fedU]), "fehlt: nach 10 s erneut")
    for i in 1...6 { sub.answered([:], now: 210 + Double(i) * 10); _ = sub.plan(want: [fedU], active: true, now: 220 + Double(i) * 10) }
    sub.answered([:], now: 290)
    check(sub.plan(want: [fedU], active: true, now: 300) == .none, "nach einer Minute: nur noch im 60-s-Takt")
    sub.answered([fedU: R.Presence(online: true, last: nil)], now: 301)
    check(sub.misses == 0 && sub.retryAt == nil, "Antwort da: kein Nachfragen")
    sub.failed()
    check(sub.plan(want: [fedU], active: true, now: 302) == .subscribe([fedU]), "Fehler: im naechsten Takt erneut")
}
