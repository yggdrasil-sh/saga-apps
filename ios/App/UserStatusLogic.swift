import Foundation

// 2.6 (Bugs-DB 2029-6): eigener Status - Verfuegbar / Beschaeftigt / Nicht stoeren. Vertrag docs/APP-API.md "Eigener Status"
// (GET/PUT /api/account/status, PUT /api/account/status/prefs, Live status.changed / status.digest / chat.status,
// Presence-Abo mit "status"), "Anrufe und eigener Status" (waiting1, call.quiet, again) und "Grund am Chat-Eintrag" (hold).
// Reine Logik ohne SwiftUI (Tests: Tests/Logic/UserStatusTests.swift). Ablauf/Ansichten: UserStatus.swift, CallHold.swift,
// Fokus: StatusFocusFilter.swift. Vorbild Web: static/userstatus.js, static/push.js ("Erreichbarkeit"), static/chat.js,
// static/calls.js (holdBar/holdTake).

enum UserStatusLogic {
    /// Server kann es: /api/version bzw. /api/chat/me features
    static let feature = "userstatus1"
    static let states = ["available", "busy", "dnd"]

    // MARK: Ansicht

    struct Prefs: Equatable {
        var quietDnd = true
        var quietDigest = false
        var inCall = "knock"            // knock | busy
        var showCall = true
        var busy = "show"               // show | silent
        var repeatOn = true
        var repeatS = 180
        var mentionBreak = false
    }
    struct Exception: Equatable, Hashable, Identifiable {
        var type: String                // user | group
        var ref: String                 // Handle (user) bzw. Name (group)
        var name: String?               // Anzeige (vom Server bei user)
        var id: String { type + ":" + ref }
        var shown: String { type == "group" ? ref : (name.flatMap { $0.isEmpty ? nil : $0 } ?? ref) }
    }
    struct Limits: Equatable {
        var text = 80
        var exceptions = 100
        var repeatMin = 30
        var repeatMax = 3600
        var durMin = 60
    }
    struct Status: Equatable {
        var state = "available"
        var until: Double?
        var text: String?
        var source = "manual"
        var ver = 0
        var effective = "available"
        var why = "none"
        var quiet = false
        var inCall = false
        var prefs = Prefs()
        var exceptions: [Exception] = []
        var limits = Limits()
        var now: Double?
    }

    /// Ansicht aus GET/PUT (auch 409 und status.changed) - ohne gueltiges ver/state: nil
    static func view(_ any: Any?) -> Status? {
        guard let d = any as? [String: Any], let st = d["state"] as? String, states.contains(st),
              let verN = d["ver"] as? NSNumber, CFGetTypeID(verN) != CFBooleanGetTypeID() else { return nil }
        var v = Status()
        v.state = st; v.ver = verN.intValue
        v.until = num(d["until"]).flatMap { $0 > 0 ? $0 : nil }
        v.text = (d["text"] as? String).flatMap { $0.isEmpty ? nil : $0 }
        v.source = (d["source"] as? String).flatMap { ["manual", "focus", "system"].contains($0) ? $0 : nil } ?? "manual"
        let eff = d["effective"] as? String ?? st
        v.effective = (states + ["in_call"]).contains(eff) ? eff : st
        v.why = (d["why"] as? String).flatMap { ["manual", "quiet", "call", "none"].contains($0) ? $0 : nil } ?? "none"
        v.quiet = (d["quiet"] as? Bool) == true
        v.inCall = (d["in_call"] as? Bool) == true
        v.now = num(d["now"])
        if let p = d["prefs"] as? [String: Any] { v.prefs = prefs(p) }
        if let l = d["limits"] as? [String: Any] {
            v.limits.text = int(l["text"]) ?? v.limits.text
            v.limits.exceptions = int(l["exceptions"]) ?? v.limits.exceptions
            if let r = l["repeat_s"] as? [Any], r.count == 2, let a = int(r[0]), let b = int(r[1]), a <= b { v.limits.repeatMin = a; v.limits.repeatMax = b }
            v.limits.durMin = int(l["dur_min"]) ?? v.limits.durMin
        }
        v.exceptions = exceptions(d["exceptions"])
        return v
    }
    static func prefs(_ p: [String: Any]) -> Prefs {
        var o = Prefs()
        if let b = p["quiet_dnd"] as? Bool { o.quietDnd = b }
        if let b = p["quiet_digest"] as? Bool { o.quietDigest = b }
        if let s = p["in_call"] as? String, ["knock", "busy"].contains(s) { o.inCall = s }
        if let b = p["show_call"] as? Bool { o.showCall = b }
        if let s = p["busy"] as? String, ["show", "silent"].contains(s) { o.busy = s }
        if let b = p["repeat"] as? Bool { o.repeatOn = b }
        if let n = int(p["repeat_s"]) { o.repeatS = n }
        if let b = p["mention_break"] as? Bool { o.mentionBreak = b }
        return o
    }
    static func exceptions(_ any: Any?) -> [Exception] {
        (any as? [Any] ?? []).compactMap { x -> Exception? in
            guard let e = x as? [String: Any], let t = e["type"] as? String else { return nil }
            if t == "user", let r = e["ref"] as? String, !r.isEmpty { return Exception(type: t, ref: r, name: e["name"] as? String) }
            if t == "group", let n = e["name"] as? String, !n.isEmpty { return Exception(type: t, ref: n, name: n) }
            return nil
        }
    }
    /// Neuer als der bisherige Stand? (Antworten koennen sich ueberholen)
    static func newer(_ v: Status, than old: Status?) -> Bool { old.map { v.ver >= $0.ver } ?? true }

    /// Antwort auf PUT: 2xx = Ansicht, 409 mit Ansicht = anderswo geaendert (still uebernehmen), sonst Fehlertext
    enum PutResult: Equatable { case ok(Status), conflict(Status), failed(String?) }
    static func putResult(status: Int, data: Data) -> PutResult {
        let j = try? JSONSerialization.jsonObject(with: data)
        if (200..<300).contains(status), let v = view(j) { return .ok(v) }
        if status == 409, let v = view(j) { return .conflict(v) }
        return .failed(((j as? [String: Any])?["error"] as? String).flatMap { $0.isEmpty ? nil : $0 })
    }

    // MARK: Setzen

    enum Duration: Hashable { case hour, morning, off, keep(Double) }
    /// "Bis morgen 7:00" (vor 5 Uhr: heute 7:00 - "Bis 7:00")
    static func morning(now: Date, calendar cal: Calendar) -> (ts: Double, today: Bool) {
        let early = cal.component(.hour, from: now) < 5
        let day = cal.startOfDay(for: now)
        let d = cal.date(byAdding: .day, value: early ? 0 : 1, to: day) ?? day
        let t = cal.date(bySettingHour: 7, minute: 0, second: 0, of: d) ?? d
        return (t.timeIntervalSince1970, early)
    }
    /// Wahlmoeglichkeiten der Dauer: "wie bisher" nur, wenn ein Ende in mehr als einer Minute gesetzt ist
    static func durations(_ v: Status, now: Double) -> [Duration] {
        var out: [Duration] = [.hour, .morning, .off]
        if v.state != "available", let u = v.until, u > now + 60 { out.insert(.keep(u), at: 0) }
        return out
    }
    /// Vorauswahl der Dauer im Blatt
    static func defaultDuration(_ v: Status, now: Double) -> Duration { durations(v, now: now).first == .keep(v.until ?? 0) ? .keep(v.until ?? 0) : .off }
    /// PUT /api/account/status: {state, source: manual, text|null, dur|until, ver}
    static func setBody(state: String, duration: Duration, text: String, ver: Int?, now: Date, calendar: Calendar, max: Int = 80) -> [String: Any] {
        var b: [String: Any] = ["state": state, "source": "manual"]
        let t = clampText(text, max: max)
        b["text"] = t.isEmpty ? NSNull() : t
        if state != "available" {
            switch duration {
            case .hour: b["dur"] = 3600
            case .morning: b["until"] = morning(now: now, calendar: calendar).ts
            case .keep(let u): b["until"] = u
            case .off: break
            }
        }
        if let ver { b["ver"] = ver }
        return b
    }
    /// Eigener Text: getrimmt, hoechstens max Zeichen (Steuerzeichen als Leerzeichen wie der Server)
    static func clampText(_ s: String, max: Int) -> String {
        let clean = String(s.unicodeScalars.map { CharacterSet.controlCharacters.contains($0) ? " " : Character($0) })
        return String(clean.trimmingCharacters(in: .whitespacesAndNewlines).prefix(max))
    }
    /// Fokus (SetFocusFilterIntent): NUR Zustand und Quelle - nie Fokus-Name oder Text (RT-29)
    static func focusBody(state: String) -> [String: Any] {
        ["state": states.contains(state) ? state : "available", "source": "focus"]
    }

    // MARK: Anzeige des eigenen Status

    /// Was gerade wirkt (ehrlich): effective + Zusatz
    struct Shown: Equatable {
        enum Hint: Equatable { case none, quiet(to: String?), under(String), until(Double) }
        var state: String
        var hint: Hint
        var focus: Bool
    }
    static func shown(_ v: Status) -> Shown {
        let eff = v.effective
        var h = Shown.Hint.none
        if eff == "dnd" && v.why == "quiet" { h = .quiet(to: nil) }
        else if eff == "in_call" { if v.state != "available" { h = .under(v.state) } }
        else if let u = v.until, v.state == eff { h = .until(u) }
        return Shown(state: eff, hint: h, focus: v.source == "focus" && v.state != "available" && v.why != "quiet")
    }
    /// Ruhezeit-Ende "HH:MM" aus GET /api/push (settings.quiet {on, to})
    static func quietTo(_ push: Any?) -> String? {
        guard let q = ((push as? [String: Any])?["settings"] as? [String: Any])?["quiet"] as? [String: Any], (q["on"] as? Bool) == true,
              let to = q["to"] as? String, to.range(of: #"^\d\d:\d\d$"#, options: .regularExpression) != nil else { return nil }
        return to
    }
    /// Naechste Abfrage (Ablauf, Ruhezeit und Anruf aendern effective ohne Ereignis): bis zum Ende + 2,5 s, hoechstens 5 min
    static func nextCheck(_ v: Status?, now: Double) -> Double {
        var s = 300.0
        if let u = v?.until { s = Swift.max(2, Swift.min(s, u - now + 2.5)) }
        return s
    }

    // MARK: Status anderer (Presence-Abo "status", Live chat.status)

    struct Peer: Equatable { var s: String; var until: Double?; var text: String? }
    static func peer(_ any: Any?) -> Peer? {
        guard let d = any as? [String: Any], let s = d["s"] as? String, (states + ["in_call"]).contains(s) else { return nil }
        return Peer(s: s, until: num(d["until"]).flatMap { $0 > 0 ? $0 : nil }, text: (d["text"] as? String).flatMap { $0.isEmpty ? nil : String($0.prefix(80)) })
    }
    static func peers(_ any: Any?) -> [String: Peer] {
        var out: [String: Peer] = [:]
        for (u, x) in any as? [String: Any] ?? [:] { if let p = peer(x) { out[u] = p } }
        return out
    }
    /// Antwort des Abos uebernehmen: fehlt eine Person, ist kein Status bekannt - ausser chat.status kam nach dem Senden
    static func merge(answer: [String: Peer], old: [String: Peer], liveAt: inout [String: Double], sent: Double) -> [String: Peer] {
        var out = answer
        for (u, at) in liveAt {
            if at < sent { liveAt[u] = nil; continue }
            out[u] = old[u]
        }
        return out
    }
    /// Live chat.status {username, status: {...} | null} -> geaendert?
    static func applyLive(_ d: [String: Any], to map: inout [String: Peer]) -> Bool {
        guard let u = d["username"] as? String, !u.isEmpty else { return false }
        let p = peer(d["status"])
        if map[u] == p { return false }
        map[u] = p
        return true
    }
    /// Anzeigen? Verfuegbar zeigt nichts
    static func visible(_ p: Peer?) -> Peer? { p.flatMap { $0.s == "available" ? nil : $0 } }

    /// Vor dem Anruf: Nicht stoeren -> Rueckfrage, Im Anruf -> Hinweis "klopft an"
    enum CallAsk: Equatable { case none, confirmDnd, knockHint }
    static func callAsk(_ p: Peer?) -> CallAsk {
        switch visible(p)?.s {
        case "dnd": return .confirmDnd
        case "in_call": return .knockHint
        default: return .none
        }
    }

    // MARK: Sammelmeldung (status.digest)

    struct Digest: Equatable { var messages = 0; var calls = 0; var other = 0; var sum: Int { messages + calls + other } }
    /// Live {messages, calls, other, ...} bzw. Push-Feld digest - nur Zahlen (nie Namen); leer: nil
    static func digest(_ any: Any?) -> Digest? {
        guard let d = any as? [String: Any] else { return nil }
        let g = Digest(messages: Swift.max(0, int(d["messages"]) ?? 0), calls: Swift.max(0, int(d["calls"]) ?? 0), other: Swift.max(0, int(d["other"]) ?? 0))
        return g.sum > 0 ? g : nil
    }

    // MARK: Erreichbarkeit (PUT /api/account/status/prefs)

    static func repeatValid(_ n: Int, _ l: Limits) -> Bool { n >= l.repeatMin && n <= l.repeatMax }
    /// Ausnahmen fuer PUT (ersetzt die ganze Liste): user -> {type, ref}, group -> {type, name}
    static func exceptionsBody(_ list: [Exception]) -> [[String: String]] {
        list.map { $0.type == "group" ? ["type": "group", "name": $0.ref] : ["type": "user", "ref": $0.ref] }
    }
    /// Handle ~xxxxxxxxxxxxxxxx bzw. ~x@host (wie Web)
    static func isHandle(_ s: String) -> Bool { s.range(of: #"^~[a-z2-7]{16}(@[a-z0-9.:\-]{1,260})?$"#, options: .regularExpression) != nil }
    /// Ausnahme aus einem Suchtreffer (Personen, eigene Gruppen, Personen anderer Server mit Handle)
    static func exception(type: String, target: String, name: String) -> Exception? {
        switch type {
        case "user": return Exception(type: "user", ref: target, name: name)
        case "group": return Exception(type: "group", ref: target, name: target)
        case "remote": return isHandle(target) ? Exception(type: "user", ref: target, name: name) : nil
        default: return nil
        }
    }
    static func adding(_ x: Exception, to list: [Exception], max: Int) -> [Exception]? {
        if list.contains(where: { $0.type == x.type && $0.ref == x.ref }) { return nil }
        guard list.count < max else { return nil }
        return list + [x]
    }

    // MARK: Hilfen
    static func num(_ v: Any?) -> Double? {
        guard let n = v as? NSNumber, CFGetTypeID(n) != CFBooleanGetTypeID(), n.doubleValue.isFinite else { return nil }
        return n.doubleValue
    }
    static func int(_ v: Any?) -> Int? { num(v).flatMap { $0 == $0.rounded() && abs($0) < 1e15 ? Int($0) : nil } }
}

// MARK: - Anrufe und eigener Status (waiting1, call.quiet, again, hold)

enum CallHoldLogic {
    /// Faehigkeit melden erst, wenn der Server Anklopfen kann (sonst klingelt er nie mit waiting)
    static let cap = "waiting1"
    static func reportsWaiting(serverFeatures: [String]) -> Bool { serverFeatures.contains(cap) }
    /// Live-Kanal offen? Im Vordergrund immer; im Hintergrund nur waehrend eines eigenen Gespraechs - Anklopfen kommt
    /// ohne Push, und CallKit-Annehmen auf dem Sperrbildschirm laeuft mit der App im Hintergrund. Kein Dauer-Live ohne
    /// Gespraech (Klingeln allein haelt ihn nicht offen).
    static func liveWanted(foreground: Bool, phase: String) -> Bool { foreground || CallLogic.isOngoing(phase) }

    enum Mode: String, Equatable { case wait, quiet }
    /// Eingehender Anruf (Live, Abfrage, Push) nach dem Zustand des Anrufs: schon da -> nichts; zurueckgehalten (quiet)
    /// -> stille Leiste; Anklopfen (waiting) -> Leiste im Gespraech (here) bzw. "laeuft woanders"; ohne waiting im
    /// Gespraech -> besetzt (wie bisher); sonst klingeln. freed: das Gespraech hier ist eben zu Ende -> normal klingeln
    enum Ring: Equatable { case ignore, busy, ring, knock(here: Bool), quiet }
    static func ring(sameCallActive: Bool, busy: Bool, waiting: Bool, quiet: Bool, freed: Bool) -> Ring {
        if sameCallActive { return .ignore }
        if quiet { return .quiet }
        if waiting && busy { return .knock(here: true) }
        if waiting && !freed { return .knock(here: false) }
        if busy { return .busy }
        return .ring
    }
    /// Live call.invite: 1:1 ueber Knoten mit waiting = Anklopfen, mit quiet = stille Leiste; Gruppe mit quiet = still
    /// (wie silent - nur die Leiste "Gruppenanruf laeuft")
    enum Invite: Equatable { case normal, knock, quiet, silent }
    static func invite(dm: Bool, waiting: Bool, quiet: Bool) -> Invite {
        if dm && waiting { return .knock }
        if quiet { return dm ? .quiet : .silent }
        return .normal
    }
    /// Abfrage (alle 5 s) einer Leiste: vorbei, sobald beendet, abgelehnt oder angenommen
    static func pollGone(state: String?) -> Bool { ["ended", "declined", "active"].contains(state ?? "") }
    /// Restzeit der Leiste ab dem Erscheinen: expires_in bzw. ring_s (mindestens 5 s) plus 3 s Reserve
    static func lifetime(expiresIn: Double?, ringS: Double?) -> Double { Swift.max(5, expiresIn ?? ringS ?? 45) + 3 }
    static func left(expiresIn: Double?, ringS: Double?, shownAt: Double, now: Double) -> Double {
        (expiresIn ?? ringS ?? 45) - (now - shownAt)
    }
    /// Gespraech hier zu Ende: Anklopfen klingelt jetzt normal, stille Leiste bleibt still; fast abgelaufen: weg
    enum Free: Equatable { case drop, ring(Double), quiet(Double) }
    static func free(mode: Mode, left: Double) -> Free {
        if left < 2 { return .drop }
        return mode == .wait ? .ring(left) : .quiet(left)
    }
    /// Laeuft ein eigenes Gespraech auf einem anderen Geraet? (GET /api/calls/active) - dann kein "Annehmen" in der
    /// stillen Leiste (/answer prueft nicht auf besetzt - sonst zwei Gespraeche)
    static func busyElsewhere(_ rows: [[String: Any]], except id: String, current: String?) -> Bool {
        rows.contains { x in
            let xid = x["id"] as? String ?? ""
            guard xid != id, xid != current, (x["state"] as? String) == "active" else { return false }
            let mine = (x["mine"] as? [Any] ?? []).count
            let role = x["role"] as? String
            if (x["kind"] as? String) == "group" && (x["dm"] as? Bool) != true { return mine > 0 }
            return role == "caller" || role == "callee" || mine > 0
        }
    }
    /// Antwort beim Anrufen: 409 busy, mit again (schon angeklopft) samt Text des Servers
    enum Dial: Equatable { case ok, busy, again(String?), other }
    static func dial(code: Int, body: [String: Any]?) -> Dial {
        if (200..<300).contains(code) { return .ok }
        guard code == 409, (body?["busy"] as? Bool) == true else { return .other }
        if (body?["again"] as? Bool) == true { return .again((body?["error"] as? String).flatMap { $0.isEmpty ? nil : $0 }) }
        return .busy
    }
    /// Anrufende Seite: das Gegenueber telefoniert - der Anruf klopft an (Anruf-Objekt bzw. call.state)
    static func callerWaiting(_ call: [String: Any]?) -> Bool { (call?["waiting"] as? Bool) == true }
    /// Ende bestaetigt? 2xx bzw. 404/409 (schon vorbei) - sonst nicht annehmen (zwei Gespraeche)
    static func endConfirmed(_ code: Int?) -> Bool { code.map { (200..<300).contains($0) || $0 == 404 || $0 == 409 } ?? false }

    /// Text des Chat-Eintrags "Anruf" fuer die angerufene Person (hold nur dort; die anrufende Seite bekommt es nie)
    enum EntryNote: Equatable { case plain, dnd, inCall }
    static func entryNote(status: String, hold: String?, mine: Bool, group: Bool) -> EntryNote {
        guard !mine, !group else { return .plain }
        if hold == "dnd" && status == "missed" { return .dnd }
        if (hold == "knock" || hold == "again") && (status == "missed" || status == "busy") { return .inCall }
        return .plain
    }
}
