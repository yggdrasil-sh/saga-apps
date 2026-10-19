import Foundation

// Leistung 2.2, Phase 2b: Seitenbaum per Abgleich (NoteSyncLogic.swift). Ein nachgebauter Server (Liste wie
// GET /api/notes: Reihenfolge nach sort, bei Gleichstand id) erzeugt Zufallsfolgen von Aenderungen; nach jedem Abgleich
// muss die eingearbeitete Liste GENAU der ganzen Liste entsprechen (wie GET /api/notes?delta=1 sie jetzt liefern wuerde).
func deltaTests() {
    // MARK: nachgebauter Server
    struct Rng { var s: UInt64; mutating func next(_ n: Int) -> Int { s = s &* 6364136223846793005 &+ 1442695040888963407; return Int((s >> 33) % UInt64(max(n, 1))) } }
    final class Server {
        struct N { var page: Page; var sort: Double; var changed: Int }
        var notes: [Int: N] = [:]
        var trash: [Int: N] = [:]
        var removedAt: [Int: Int] = [:]
        var seq = 0, nextID = 1, clock = 100.0
        func list() -> [Page] { notes.values.sorted { $0.sort != $1.sort ? $0.sort < $1.sort : $0.page.id < $1.page.id }.map(\.page) }
        func touch(_ id: Int, _ f: (inout N) -> Void) { guard var n = notes[id] else { return }; seq += 1; f(&n); n.changed = seq; notes[id] = n }
        @discardableResult func create(parent: Int?, kind: String = "page") -> Int {
            let id = nextID; nextID += 1; seq += 1; clock += 1
            notes[id] = N(page: .created(id: id, parent: parent, title: "Seite \(id)", kind: kind, updated: clock), sort: clock, changed: seq)
            removedAt[id] = nil
            return id
        }
        func subtree(_ id: Int) -> [Int] {
            var out = [id], i = 0
            while i < out.count { let p = out[i]; out += notes.values.filter { $0.page.parent_id == p }.map(\.page.id).sorted(); i += 1 }
            return out
        }
        func trashTree(_ id: Int) {                             // Papierkorb: Seite samt Unterseiten verschwindet aus der Liste
            for x in subtree(id) { if let n = notes.removeValue(forKey: x) { seq += 1; trash[x] = n; removedAt[x] = seq } }
        }
        func restore(_ id: Int) {
            guard var n = trash.removeValue(forKey: id) else { return }
            if let p = n.page.parent_id, notes[p] == nil { n.page.parent_id = nil }
            seq += 1; n.changed = seq; notes[id] = n; removedAt[id] = nil
        }
        func purge(_ id: Int) { if trash.removeValue(forKey: id) != nil { seq += 1; removedAt[id] = seq } }
        /// Antwort als JSON, wie der Server sie schickt (since nil = delta=1)
        func response(since: Int?) -> Data {
            let l = list()
            func dict(_ p: Page, after: Any?) -> [String: Any] {
                var d = (try? JSONSerialization.jsonObject(with: JSONEncoder().encode(p))) as? [String: Any] ?? [:]
                d["locked"] = false                              // Feld, das die App nicht kennt
                if let after { d["after"] = after }
                return d
            }
            guard let since else {
                return try! JSONSerialization.data(withJSONObject: ["cursor": "c.\(seq)", "full": true, "removed": [Int](), "notes": l.map { dict($0, after: nil) }])
            }
            var out: [[String: Any]] = []
            for (i, p) in l.enumerated() where notes[p.id]!.changed > since {
                out.append(dict(p, after: i == 0 ? NSNull() : l[i - 1].id))
            }
            let rem = removedAt.filter { $0.value > since && notes[$0.key] == nil }.map(\.key).sorted()
            return try! JSONSerialization.data(withJSONObject: ["cursor": "c.\(seq)", "full": false, "removed": rem, "notes": out])
        }
    }
    func seqOf(_ c: String?) -> Int? { c.flatMap { Int($0.dropFirst(2)) } }

    // MARK: Zufallsfolgen
    var failedRuns = 0
    for run in 0..<60 {
        var rng = Rng(s: UInt64(run) &* 7919 &+ 17)
        let srv = Server()
        for _ in 0..<12 { srv.create(parent: srv.notes.isEmpty || rng.next(3) == 0 ? nil : srv.notes.keys.sorted()[rng.next(srv.notes.count)]) }
        // App: erster Start ohne Stand -> delta=1
        guard case let .delta(c0, full0, n0, _) = try! NoteSync.parse(srv.response(since: nil)), full0 else { check(false, "delta=1: full"); return }
        var app = NoteSync.outcome(.delta(cursor: c0, full: true, notes: n0, removed: []), base: [], sentSince: false).pages!
        var cursor = c0
        var history: [(cursor: String?, list: [Page])] = [(cursor, app)]
        var ok = app == srv.list()
        for step in 0..<40 {
            for _ in 0..<(1 + rng.next(4)) {                       // mehrere Aenderungen zwischen zwei Abgleichen
                let ids = srv.notes.keys.sorted()
                let pick = ids.isEmpty ? nil : ids[rng.next(ids.count)]
                switch rng.next(11) {
                case 0: srv.create(parent: rng.next(3) == 0 ? nil : pick)                                    // anlegen
                case 1: if let id = pick { srv.touch(id) { $0.page.title = "T\(step)-\(rng.next(99))" } }      // umbenennen
                case 2: if let id = pick, let to = ids.randomElementSeeded(&rng.s), !srv.subtree(id).contains(to) {
                            srv.touch(id) { $0.page.parent_id = to } }                                        // verschieben
                case 3: if let id = pick { let s = Double(rng.next(300)); srv.touch(id) { $0.sort = s } }     // Reihenfolge
                case 4: if let id = pick { srv.trashTree(id) }                                                // Papierkorb
                case 5: if let id = srv.trash.keys.sorted().first { srv.restore(id) }                         // wiederherstellen
                case 6: if let id = pick { srv.touch(id) { $0.page.archived = !($0.page.archived ?? false) } } // archivieren
                case 7: let db = srv.create(parent: pick, kind: "database")                                   // DB + Eintraege
                        for _ in 0..<(1 + rng.next(3)) { srv.create(parent: db) }
                case 8: if let db = srv.notes.values.first(where: { $0.page.isDatabase })?.page.id,
                           let row = srv.notes.values.filter({ $0.page.parent_id == db }).map(\.page.id).sorted().first {
                            srv.touch(row) { $0.page.title = "Eintrag \(rng.next(50))"; $0.page.tags = ["x"] } }
                case 9: if let id = srv.trash.keys.sorted().last { srv.purge(id) }                             // endgueltig loeschen
                default: if let id = pick { srv.touch(id) { $0.page.shared = true; $0.page.unread = !($0.page.unread ?? false); $0.page.pinned = true; $0.page.icon = "⭐" } }
                }
            }
            let r = try! NoteSync.parse(srv.response(since: seqOf(cursor)))
            let o = NoteSync.outcome(r, base: app, sentSince: true)
            if let p = o.pages { app = p }
            cursor = o.cursor
            check(!o.full && !o.refetch && !o.legacy, "Lauf \(run)/\(step): Abgleich ohne Neuladen")
            if app != srv.list() { ok = false }
            history.append((cursor, app))
            // Neustart mit einem aelteren Stand auf der Platte (Liste + Stand passten zusammen): erneuter Abgleich ab dort
            if step % 7 == 6 {
                let old = history[rng.next(history.count)]
                let o2 = NoteSync.outcome(try! NoteSync.parse(srv.response(since: seqOf(old.cursor))), base: old.list, sentSince: true)
                if (o2.pages ?? old.list) != srv.list() || o2.refetch { ok = false }
                // derselbe Abgleich doppelt eingearbeitet (Stand auf der Platte aelter als die Liste): harmlos
                let again = NoteSync.outcome(try! NoteSync.parse(srv.response(since: seqOf(old.cursor))), base: app, sentSince: true)
                if (again.pages ?? app) != srv.list() { ok = false }
            }
        }
        if !ok { failedRuns += 1 }
        check(ok, "Zufallsfolge \(run): Abgleich == ganzer Baum")
    }
    check(failedRuns == 0, "alle Zufallsfolgen stimmen")

    // MARK: Einzelfaelle
    func pg(_ id: Int, _ t: String = "", parent: Int? = nil) -> Page { .created(id: id, parent: parent, title: t, updated: 1) }
    let base = [pg(1, "A"), pg(2, "B"), pg(3, "C")]
    check(NoteSync.merge(base, notes: [], removed: []).pages == base, "leerer Abgleich: unveraendert")
    var m = NoteSync.merge(base, notes: [DeltaNote(page: pg(4, "D"), after: .first)], removed: [2])
    check(m.consistent && m.pages.map(\.id) == [4, 1, 3], "ganz vorn einsetzen, entfernen")
    m = NoteSync.merge(base, notes: [DeltaNote(page: pg(5), after: .id(3)), DeltaNote(page: pg(6), after: .id(5))], removed: [99])
    check(m.consistent && m.pages.map(\.id) == [1, 2, 3, 5, 6], "Kette: after steht selbst in notes; unbekannte removed-ID ignoriert")
    m = NoteSync.merge(base, notes: [DeltaNote(page: pg(1, "A2"), after: .id(3))], removed: [])
    check(m.consistent && m.pages.map(\.id) == [2, 3, 1] && m.pages.last?.title == "A2", "verschieben + umbenennen")
    m = NoteSync.merge(base, notes: [DeltaNote(page: pg(7), after: .id(42))], removed: [])
    check(!m.consistent && m.pages.map(\.id) == [1, 2, 3, 7], "after unbekannt: ans Ende, Stand weicht ab")
    let o = NoteSync.outcome(.delta(cursor: "x", full: false, notes: [DeltaNote(page: pg(7), after: .id(42))], removed: []), base: base, sentSince: true)
    check(o.refetch && o.cursor == nil && o.pages?.count == 4, "abweichender Stand: Stand verwerfen, gleich ganz holen")
    m = NoteSync.merge(base, notes: [DeltaNote(page: pg(8), after: .missing)], removed: [])
    check(!m.consistent && m.pages.last?.id == 8, "after fehlt in since-Antwort: Stand weicht ab")
    // lange Kette (jede neue Seite hinter der vorigen): ohne Rekursion
    let chain = (100..<50_100).map { DeltaNote(page: pg($0), after: .id($0 == 100 ? 3 : $0 - 1)) }
    m = NoteSync.merge(base, notes: chain, removed: [])
    check(m.consistent && m.pages.count == 50_003 && m.pages[3].id == 100 && m.pages.last?.id == 50_099, "50.000er-Kette")

    // Array-Antwort = aelterer Server: wie bisher die ganze Liste, kein Stand
    let arr = #"[{"id":9,"title":"","icon":"","parent_id":3,"updated":5,"tags":[],"pinned":false,"template":false,"kind":"page","archived":false,"locked":false,"shared":false,"unread":false}]"#
    let ra = try! NoteSync.parse(Data(("  \n" + arr).utf8))
    if case .list(let l) = ra { check(l.map(\.id) == [9], "Array erkannt") } else { check(false, "Array erkannt") }
    let oa = NoteSync.outcome(ra, base: base, sentSince: true)
    check(oa.legacy && oa.full && oa.cursor == nil && oa.pages?.map(\.id) == [9], "alter Server: ersetzen, kein Stand")
    // full: true jederzeit: ersetzen statt einarbeiten (auch bei since)
    let fj = #"{"cursor":"k1","full":true,"removed":[],"notes":[{"id":5,"title":"X","parent_id":null,"updated":1}]}"#
    let of = NoteSync.outcome(try! NoteSync.parse(Data(fj.utf8)), base: base, sentSince: true)
    check(of.full && of.pages?.map(\.id) == [5] && of.cursor == "k1" && !of.legacy, "full: true ersetzt")
    // ohne Stand gefragt: Antwort immer die ganze Liste (auch wenn full fehlt)
    let nf = #"{"cursor":"k2","notes":[{"id":5,"title":"X","updated":1}]}"#
    check(NoteSync.outcome(try! NoteSync.parse(Data(nf.utf8)), base: base, sentSince: false).pages?.map(\.id) == [5], "delta=1: ersetzen")
    // cursor null -> naechstes Mal delta=1; Zahl als Stand
    let cn = #"{"cursor":null,"full":false,"removed":[2],"notes":[]}"#
    let on = NoteSync.outcome(try! NoteSync.parse(Data(cn.utf8)), base: base, sentSince: true)
    check(on.cursor == nil && on.pages?.map(\.id) == [1, 3], "cursor null: kein Stand, Abgleich trotzdem eingearbeitet")
    if case .delta(let c, _, _, _) = try! NoteSync.parse(Data(#"{"cursor":88412,"full":false,"notes":[],"removed":[]}"#.utf8)) { check(c == "88412", "Stand als Zahl") }
    check(NoteSync.outcome(try! NoteSync.parse(Data(#"{"cursor":"k3","full":false,"notes":[],"removed":[]}"#.utf8)), base: base, sentSince: true).pages == nil, "nichts geaendert: nichts zuweisen")
    // after: null / Zahl / fehlt; "after" gehoert nicht zur Seite
    let aj = #"{"cursor":"c","full":false,"removed":[],"notes":[{"id":1,"title":"a","after":null},{"id":2,"title":"b","after":1},{"id":3,"title":"c"}]}"#
    if case .delta(_, _, let ns, _) = try! NoteSync.parse(Data(aj.utf8)) {
        check(ns.map(\.after) == [.first, .id(1), .missing], "after: null/ID/fehlt")
        let enc = String(data: NoteSync.encodeCache(ns.map(\.page))!, encoding: .utf8)!
        check(!enc.contains("after"), "after landet nicht im Zwischenspeicher")
    }
    // Zwischenspeicher: gemergte Liste als Array, liest sich wie die bisherige rohe Antwort
    var rich = pg(10, "Reich", parent: 1); rich.icon = "📁"; rich.tags = ["a", "b"]; rich.shared = true; rich.unread = true; rich.archived = true
    rich.owner = PageOwner(name: "N", username: "u", handle: nil, remote: nil, avatar: nil); rich.remote = RemoteRef(peer: 2, host: "h.example", name: "H")
    let lst = base + [rich]
    check((try? PageList.decode(NoteSync.encodeCache(lst)!)) == lst, "Zwischenspeicher: Liste kommt unveraendert zurueck")

    // Anfrage
    check(NoteSync.path(since: nil) == "api/notes?delta=1" && NoteSync.path(since: "") == "api/notes?delta=1", "ohne Stand: delta=1")
    check(NoteSync.path(since: "3f9c0a1b2c4d.88412") == "api/notes?since=3f9c0a1b2c4d.88412", "Stand unveraendert")
    check(NoteSync.path(since: "a+b&c=d") == "api/notes?since=a%2Bb%26c%3Dd", "Stand kodiert")

    // Stand je Server UND Konto; Kontowechsel/Serverwechsel/abgemeldet/unbekanntes Konto -> verwerfen (delta=1)
    let now = Date(timeIntervalSince1970: 2_000_000)
    let c = NoteCursor(value: "c.5", origin: "https://a.example", account: "anna", fullAt: now.timeIntervalSince1970 - 60)
    check(c.usable(origin: "https://a.example", account: "anna", now: now), "gleicher Server, gleiches Konto: since")
    check(!c.usable(origin: "https://a.example", account: "bernd", now: now), "Kontowechsel: Stand verwerfen")
    check(!c.usable(origin: "https://b.example", account: "anna", now: now), "Serverwechsel: Stand verwerfen")
    check(!c.usable(origin: nil, account: nil, now: now) && !c.usable(origin: "https://a.example", account: nil, now: now), "abgemeldet/Konto unbekannt: ganz laden")
    check(!c.usable(origin: "https://a.example", account: "anna", now: now + NoteSync.fullEvery), "Sicherheitsnetz: nach einem Tag ganz laden")
    check(!NoteCursor(value: "", origin: "https://a.example", account: "anna", fullAt: now.timeIntervalSince1970).usable(origin: "https://a.example", account: "anna", now: now), "leerer Stand")
    check((try? JSONDecoder().decode(NoteCursor.self, from: JSONEncoder().encode(c))) == c, "Stand speichern/lesen")
}

extension Array where Element == Int {
    /// zufaelliges Element mit eigenem Zufallsstand (reproduzierbar)
    func randomElementSeeded(_ s: inout UInt64) -> Int? {
        guard !isEmpty else { return nil }
        s = s &* 6364136223846793005 &+ 1442695040888963407
        return self[Int((s >> 33) % UInt64(count))]
    }
}
