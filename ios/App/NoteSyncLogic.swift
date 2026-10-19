import Foundation

// Seitenbaum per Abgleich (Leistung 2.2, Phase 2b): statt jedes Mal den ganzen Baum (GET /api/notes) nur das seit dem
// letzten Stand Geaenderte holen (GET /api/notes?since=<cursor>). Vertrag: docs/APP-API-LIVE.md "Seitenbaum abgleichen".
// - ?delta=1 -> {cursor, full: true, notes, removed: []}; ?since=c -> {cursor, full: false, notes (mit "after"), removed}
// - full: true jederzeit moeglich -> Liste ersetzen; cursor: null -> beim naechsten Mal wieder delta=1
// - Antwort ist ein Array -> aelterer Server ohne Abgleich: wie bisher die ganze Liste
// Reine Logik - geprueft mit Tests/Logic/run.sh (DeltaTests.swift).

/// Stand des Abgleichs. Gilt je Server UND Konto (nicht je Geraet): nach Abmelden/Kontowechsel/Serverwechsel verwerfen.
struct NoteCursor: Codable, Equatable, Sendable {
    /// undurchsichtiger Stand vom Server (nicht zerlegen)
    var value: String
    var origin: String
    var account: String
    /// Zeitpunkt der letzten ganzen Liste (Sicherheitsnetz: spaetestens nach `NoteSync.fullEvery` wieder ganz laden)
    var fullAt: Double

    /// Nur fuer genau diesen Server und dieses Konto benutzen (unbekanntes Konto: nie)
    func usable(origin o: String?, account a: String?, now: Date = Date()) -> Bool {
        guard !value.isEmpty, let o, let a, !a.isEmpty, o == origin, a == account else { return false }
        let age = now.timeIntervalSince1970 - fullAt
        return age >= 0 && age < NoteSync.fullEvery
    }
}

/// Ein Eintrag einer Abgleich-Antwort: die Seite und wohin sie gehoert
struct DeltaNote: Decodable, Sendable {
    enum After: Equatable, Sendable { case missing, first, id(Int) }
    let page: Page
    let after: After
    private enum K: String, CodingKey { case after }
    init(page: Page, after: After) { self.page = page; self.after = after }
    init(from d: Decoder) throws {
        page = try Page(from: d)                                    // "after" gehoert nicht zur Seite (Page kennt es nicht)
        let c = try d.container(keyedBy: K.self)
        if !c.contains(.after) { after = .missing }
        else if try c.decodeNil(forKey: .after) { after = .first }
        else { after = .id(try c.decode(Int.self, forKey: .after)) }
    }
}

enum NoteSync {
    /// Sicherheitsnetz: einmal am Tag die ganze Liste (der Server liefert full: true ohnehin, wenn der Stand nicht passt)
    static let fullEvery: TimeInterval = 24 * 3600

    /// Anfrage: mit Stand nur Geaendertes, sonst den ganzen Baum mit Stand (aeltere Server ignorieren beides)
    static func path(since: String?) -> String {
        guard let since, !since.isEmpty else { return "api/notes?delta=1" }
        var allowed = CharacterSet.urlQueryAllowed; allowed.remove(charactersIn: "&+=?#")
        return "api/notes?since=" + (since.addingPercentEncoding(withAllowedCharacters: allowed) ?? since)
    }

    /// Antwort des Servers
    enum Response: Sendable {
        /// aelterer Server: ganze Liste (Array)
        case list([Page])
        /// Abgleich: cursor nil = beim naechsten Mal wieder delta=1
        case delta(cursor: String?, full: Bool, notes: [DeltaNote], removed: [Int])
    }
    private struct Envelope: Decodable {
        let cursor: CursorValue?
        let full: Bool?
        let notes: [DeltaNote]
        let removed: [Int]?
    }
    /// Stand als Text oder Zahl (undurchsichtig)
    private struct CursorValue: Decodable {
        let text: String?
        init(from d: Decoder) throws {
            let c = try d.singleValueContainer()
            if c.decodeNil() { text = nil }
            else if let s = try? c.decode(String.self) { text = s }
            else if let i = try? c.decode(Int64.self) { text = String(i) }
            else { text = String(try c.decode(Double.self)) }
        }
    }
    static func parse(_ d: Data) throws -> Response {
        // erstes Zeichen entscheidet: "[" = Liste (alter Server), sonst Objekt
        let first = d.first { ![0x20, 0x09, 0x0A, 0x0D, 0xEF, 0xBB, 0xBF].contains($0) }
        if first == UInt8(ascii: "[") { return .list(try PageList.decode(d)) }
        let e = try JSONDecoder().decode(Envelope.self, from: d)
        let cur = e.cursor?.text.flatMap { $0.isEmpty ? nil : $0 }
        return .delta(cursor: cur, full: e.full ?? false, notes: e.notes, removed: e.removed ?? [])
    }

    /// Abgleich einarbeiten (full: false): removed- und notes-IDs entfernen, notes der Reihe nach hinter ihr "after"
    /// (bzw. an den Anfang) einsetzen. `consistent` false: ein "after" war unbekannt (Stand weicht ab) - die Seite steht
    /// dann am Ende, beim naechsten Mal die ganze Liste holen.
    static func merge(_ base: [Page], notes: [DeltaNote], removed: [Int]) -> (pages: [Page], consistent: Bool) {
        if notes.isEmpty && removed.isEmpty { return (base, true) }
        var drop = Set(removed); drop.reserveCapacity(removed.count + notes.count)
        for n in notes { drop.insert(n.page.id) }
        // after -> einzusetzende Seiten (in Listen-Reihenfolge); Schluessel nil = ganz vorn
        var follow = [Int?: [Page]](minimumCapacity: notes.count)
        var consistent = true, orphans: [Page] = []
        for n in notes {
            switch n.after {
            case .first: follow[nil, default: []].append(n.page)
            case .id(let a): follow[a, default: []].append(n.page)
            case .missing: consistent = false; orphans.append(n.page)
            }
        }
        var out = [Page](); out.reserveCapacity(base.count + notes.count)
        // ohne Rekursion: lange Ketten neuer Seiten (jede hinter der vorigen) sprengen sonst den Stack
        func emit(after key: Int?) {
            guard let f = follow.removeValue(forKey: key) else { return }
            var stack = Array(f.reversed())
            while let p = stack.popLast() {
                out.append(p)
                if let g = follow.removeValue(forKey: p.id) { stack.append(contentsOf: g.reversed()) }
            }
        }
        emit(after: nil)
        for p in base where !drop.contains(p.id) { out.append(p); emit(after: p.id) }
        if !follow.isEmpty {                                               // "after" unbekannt: ans Ende, Stand weicht ab
            consistent = false
            let rest = Set(follow.values.joined().map(\.id))
            for n in notes where rest.contains(n.page.id) { out.append(n.page) }
        }
        out.append(contentsOf: orphans)
        return (out, consistent)
    }

    /// Ergebnis einer Antwort gegen die bisherige Liste
    struct Outcome: Sendable {
        /// neue ganze Liste (nil: unveraendert - nichts zuweisen)
        let pages: [Page]?
        /// neuer Stand (nil: beim naechsten Mal ganz laden; auch bei alten Servern)
        let cursor: String?
        /// ganze Liste erhalten (Array oder full: true)
        let full: Bool
        /// Abgleich passte nicht (unbekanntes "after"): gleich noch einmal ganz holen
        let refetch: Bool
        /// aelterer Server (Antwort war ein Array)
        var legacy = false
    }
    /// `sentSince`: Anfrage hatte einen Stand. Ohne Stand ist jede Antwort die ganze Liste (auch wenn full fehlt).
    static func outcome(_ r: Response, base: [Page], sentSince: Bool) -> Outcome {
        switch r {
        case .list(let l):
            return Outcome(pages: l, cursor: nil, full: true, refetch: false, legacy: true)
        case .delta(let cur, let full, let notes, let removed):
            if full || !sentSince { return Outcome(pages: notes.map(\.page), cursor: cur, full: true, refetch: false) }
            if notes.isEmpty && removed.isEmpty { return Outcome(pages: nil, cursor: cur, full: false, refetch: false) }
            let m = merge(base, notes: notes, removed: removed)
            return Outcome(pages: m.pages, cursor: m.consistent ? cur : nil, full: false, refetch: !m.consistent)
        }
    }

    /// Zwischenspeicher der Seitenliste: die gemergte Liste als Array (gleiches Format wie die bisherige rohe Antwort -
    /// aeltere App-Fassungen und PageList.decode lesen sie unveraendert)
    static func encodeCache(_ pages: [Page]) -> Data? { try? JSONEncoder().encode(pages) }
}
