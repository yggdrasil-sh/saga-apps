import Foundation

// 2.5 (L8/L9, Vertrag docs/APP-API.md "Anrufe: Personen hinzufuegen" und "1:1 -> Konferenz"): reine Logik ohne
// SwiftUI (mit swiftc pruefbar, Tests/Logic/CallAddTests.swift). Ablauf in CallAdd.swift, Ansicht CallAddViews.swift.
// Web als Vorbild: static/calls.js addOk/moveOk/addOpen/addPick/addAsk/moveIn, static/chat.js callText/callRow.

enum CallAdd {
    /// Server-Features (GET /api/calls/config features)
    static let feature = "call_add"
    static let featureMove = "call_move"
    /// hoechstens so viele Kennungen je /add (davon hoechstens 10 neue Mitglieder) - die App schickt immer eine
    static let maxUIDs = 50

    // MARK: Felder des Anruf-Objekts

    /// can_add/add_page (Gruppe) bzw. can_move/move_block/move_hint (1:1). Jedes volle Anruf-Objekt ersetzt den Stand
    /// (call.roster traegt die Felder nicht).
    struct Caps: Equatable {
        var canAdd = false
        var addPage: String?
        var canMove = false
        var moveBlock: String?
        var moveHint: String?
        init(canAdd: Bool = false, addPage: String? = nil, canMove: Bool = false, moveBlock: String? = nil, moveHint: String? = nil) {
            self.canAdd = canAdd; self.addPage = addPage; self.canMove = canMove; self.moveBlock = moveBlock; self.moveHint = moveHint
        }
        init(_ c: [String: Any]) {
            func s(_ k: String) -> String? { (c[k] as? String).flatMap { $0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : $0 } }
            canAdd = (c["can_add"] as? Bool) == true
            addPage = canAdd ? s("add_page") : nil
            canMove = (c["can_move"] as? Bool) == true
            moveBlock = canMove ? nil : s("move_block")
            moveHint = canMove ? nil : s("move_hint")
        }
    }

    /// Was der Knopf "Person hinzufuegen" tut
    enum Mode: Equatable {
        /// kein Knopf (Feature fehlt, keine Rechte, Begleitgeraet, beendet ...)
        case none
        /// Gruppenanruf: Auswahl, POST /add (L8)
        case add
        /// 1:1 ueber Knoten: Auswahl, POST /add zieht in eine neue Gruppe um (L9)
        case move
        /// nur ein Hinweis (direktes bzw. foederiertes 1:1): Text des Servers, nil = eigener Ersatztext
        case hint(String?)
    }

    /// kind: "dm" (direkt) | "group"; dmRoom: 1:1 ueber Knoten (Raum mit dm: true); live: Anruf steht (aktiv)
    static func mode(features: [String], kind: String, dmRoom: Bool, caps: Caps, live: Bool, companion: Bool) -> Mode {
        guard live, !companion else { return .none }
        if kind == "group" && !dmRoom {
            return features.contains(feature) && caps.canAdd ? .add : .none
        }
        guard features.contains(featureMove) else { return .none }          // aeltere Server: unveraendert
        if kind == "group" {                                                // 1:1 ueber Knoten
            if caps.canMove || caps.moveBlock == "ringing" { return .move }   // Objekt vom Klingeln: Server prueft frisch
            if caps.moveBlock == "ended" { return .none }
            return caps.moveBlock == nil ? .none : .hint(caps.moveHint)
        }
        return .hint(caps.moveHint)                                         // direktes 1:1 (kind dm)
    }

    // MARK: Plaetze

    struct Seats: Equatable {
        var used: Int; var limit: Int
        var left: Int { limit > 0 ? max(0, limit - used) : 99 }
        var full: Bool { limit > 0 && used >= limit }
    }
    /// belegt = Zahl der legs (jedes Geraet zaehlt, auch Begleitgeraete), Grenze = limit des Anrufs
    static func seats(legs: Int, limit: Int) -> Seats { Seats(used: max(0, legs), limit: max(0, limit)) }

    // MARK: Auswahl

    /// Zeile der Auswahl (Kontakt bzw. Treffer der Personensuche)
    struct Person: Equatable, Identifiable {
        /// Kennung fuer /add (Kontakt: ref '~x' bzw. '~x@host'; Suche: username bzw. Handle)
        var ref: String
        var name: String
        var sub: String?
        var avatar: String?
        var gone = false
        var remote = false
        /// Mitglied ohne Geraet im Anruf ("im Chat - klingelt erneut")
        var away = false
        var id: String { ref }
    }
    /// Kontakte (GET /api/chat/contacts) als Zeilen - Unterzeile wie in der Gruppeninfo
    static func people(contacts: [ChatContact]) -> [Person] {
        contacts.map { k in Person(ref: k.ref, name: k.name, sub: ContactsLogic.subline(k), avatar: k.avatar, gone: k.gone, remote: k.remote) }
    }
    /// Treffer der Personensuche (GET /api/chat/people?q=): users[].username, remote[].handle
    static func people(search j: [String: Any]?) -> [Person] {
        var out: [Person] = [], seen = Set<String>()
        func s(_ d: [String: Any], _ k: String) -> String? { (d[k] as? String).flatMap { $0.trimmingCharacters(in: .whitespaces).isEmpty ? nil : $0 } }
        for case let u as [String: Any] in (j?["users"] as? [Any] ?? []) {
            guard let r = s(u, "username"), seen.insert(r).inserted else { continue }
            out.append(Person(ref: r, name: PersonName.shown(s(u, "name") ?? r, id: r), sub: PersonRef.sub(u["sub"]), avatar: s(u, "avatar")))
        }
        for case let u as [String: Any] in (j?["remote"] as? [Any] ?? []) {
            guard let h = s(u, "handle"), seen.insert(h).inserted else { continue }
            let host = s(u, "host") ?? FedChatLogic.host(of: h)
            out.append(Person(ref: h, name: PersonName.shown(s(u, "label") ?? s(u, "name") ?? h, id: h, host: host),
                              sub: String(localized: "Person auf \(host)"), avatar: s(u, "avatar"), remote: true))
        }
        return out
    }
    /// Kennungen der Personen mit Geraet im Anruf (ref, Handle, who - je nachdem, was Kontakt bzw. Suche liefert)
    static func inCall(_ legs: [CallRoom.Leg]) -> Set<String> {
        var s = Set<String>()
        for x in legs { s.insert(x.who); if let r = x.ref { s.insert(r) }; if let h = x.handle { s.insert(h); s.insert("@" + h) } }
        return s
    }
    /// Kennungen der Mitglieder ohne Geraet im Anruf ("Nicht dabei")
    static func away(_ entries: [Invites.Entry]) -> Set<String> {
        var s = Set<String>()
        for e in entries { s.insert(e.who); if let r = e.ref { s.insert(r) }; if let h = e.handle { s.insert(h) } }
        return s
    }
    /// Wer schon im Anruf ist, fehlt; Mitglieder ohne Geraet sind markiert (klingeln erneut)
    static func visible(_ list: [Person], inCall: Set<String>, away: Set<String>) -> [Person] {
        list.filter { !inCall.contains($0.ref) }.map { p in var q = p; q.away = away.contains(p.ref); return q }
    }
    /// Zeile waehlbar? (voll bzw. Konto weg: deaktiviert)
    static func selectable(_ p: Person, seats: Seats) -> Bool { !seats.full && !p.gone }

    // MARK: POST /api/calls/{id}/add

    static func body(_ ref: String, confirmPage: Bool) -> [String: Any] {
        confirmPage ? ["uids": [ref], "confirm_page": true] : ["uids": [ref]]
    }

    struct Moved: Equatable { var from: Int; var to: Int }

    struct Done: Equatable {
        var n: Int
        var added: [String]
        /// Text des ersten Eintrags in failed (die anderen gingen trotzdem)
        var failed: String?
        var pending: Bool
        var request: Bool
        /// 2.5 (L9): Anruf ist in den neuen Chat umgezogen
        var moved: Moved?
    }
    enum Outcome: Equatable {
        case done(Done)
        /// 409 page_confirm (Seiten-Chat): nachfragen und mit confirm_page wiederholen
        case pageConfirm(String)
        /// Fehler mit fertigem Text des Servers (voll, Rechte, entfernt, beendet, 1:1 ohne Umzug ...)
        case server(String)
        case tooOften
        case generic
    }
    static func outcome(code: Int?, body: [String: Any]?, confirmed: Bool) -> Outcome {
        guard let c = code else { return .generic }
        if (200..<300).contains(c) {
            let f = (body?["failed"] as? [Any] ?? []).compactMap { ($0 as? [String: Any]).flatMap { ($0["error"] as? String).flatMap { $0.isEmpty ? nil : $0 } } }.first
            return .done(Done(n: (body?["n"] as? NSNumber)?.intValue ?? 0,
                              added: (body?["added"] as? [Any] ?? []).compactMap { $0 as? String },
                              failed: f,
                              pending: !(body?["pending"] as? [Any] ?? []).isEmpty,
                              request: !(body?["request"] as? [Any] ?? []).isEmpty,
                              moved: moved(body?["moved"])))
        }
        if c == 429 { return .tooOften }
        let e = (body?["error"] as? String).flatMap { $0.trimmingCharacters(in: .whitespaces).isEmpty ? nil : $0 }
        if c == 409, (body?["page_confirm"] as? Bool) == true, !confirmed {
            return .pageConfirm(e ?? String(localized: "Die Seite wird dafür mit Leserecht geteilt."))
        }
        return e.map { .server($0) } ?? .generic
    }

    /// Hinweis nach einer erfolgreichen Antwort (wie Web addPick)
    enum Note: Equatable { case failed(String), pending, request, already, ringing }
    static func note(_ d: Done) -> Note {
        if let f = d.failed { return .failed(f) }
        if d.pending { return .pending }
        if d.request { return .request }
        if d.n == 0 { return .already }
        return .ringing
    }

    // MARK: 1:1 -> Konferenz (L9)

    /// Chat-ID aus JSON: positive ganze Zahl (wie Web /^[1-9][0-9]{0,17}$/), kein Bool
    static func chatID(_ v: Any?) -> Int? { CallLogic.Entry.chatRef(v) }
    /// "moved" der /add-Antwort: {from_chat, chat_id}
    static func moved(_ v: Any?) -> Moved? {
        guard let d = v as? [String: Any], let to = chatID(d["chat_id"]) else { return nil }
        return Moved(from: chatID(d["from_chat"]) ?? 0, to: to)
    }
    /// Ereignis call.move {call_id, from_chat, chat_id, kind, media, by} fuer den laufenden Anruf
    static func moveEvent(_ d: [String: Any], callID: String?) -> Moved? {
        guard let cid = callID, (d["call_id"] as? String) == cid, let to = chatID(d["chat_id"]) else { return nil }
        if let k = d["kind"] as? String, k != "group" { return nil }
        return Moved(from: chatID(d["from_chat"]) ?? 0, to: to)
    }
    /// Volles Anruf-Objekt (Abfrage) haengt an einem anderen Chat und traegt kein dm mehr: der Umzug wurde verpasst
    /// (Ereignis call.move kam nicht an, z. B. waehrend der Live-Kanal neu verband)
    static func missedMove(_ c: [String: Any], dmRoom: Bool, chatID cur: Int) -> Moved? {
        guard dmRoom, cur != 0, (c["kind"] as? String) == "group", c["dm"] == nil, c["legs"] != nil,
              let to = chatID(c["chat_id"]), to != cur else { return nil }
        return Moved(from: cur, to: to)
    }
    /// Nach dem Umzug: ist das alte 1:1 offen, den neuen Chat oeffnen
    static func openNew(open: Int?, move: Moved) -> Bool { move.from != 0 && open == move.from }

    // MARK: Nach dem Hinzufuegen (L8)

    /// Gruppennamen erneut verschluesselt senden (wie die Gruppeninfo), damit Neue ihn lesen: nur freie Gruppen mit Namen,
    /// nur wenn jemand neu Mitglied wurde - nach einem Umzug hat die neue Gruppe keinen Namen
    static func resendTitle(added: [String], kind: String?, title: String?, moved: Bool) -> Bool {
        !moved && !added.isEmpty && kind == "free" && !(title ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }
}
