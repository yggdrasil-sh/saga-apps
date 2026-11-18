import Foundation

// 2.5 (Bugs-DB 2121): Seitenkopf der offenen Seite live nachziehen - reine Logik ohne UI (mit swiftc pruefbar).
// Server: note.changed {id, rev, meta: true} = nur Kopf-Felder geaendert (Symbol, Titelbild + Position, Sperre,
// Eigenschaften, Breite, Schrift, Ort ...), rev bleibt. Ohne Live-Kanal meldet POST /api/presence {rev, updated}.
// Vorbild: Web static/pages.js metaRefresh().

enum PageMetaLogic {
    /// Was nach note.changed bzw. der Anwesenheits-Antwort mit der offenen Seite zu tun ist
    /// - `meta`: Ereignis traegt meta: true bzw. Anwesenheit meldet einen neueren Stand bei gleicher Revision
    static func change(eventRev: Int?, ownRev: Int, dirty: Bool, gone: Bool, meta: Bool) -> LiveLogic.PageChange {
        let c = LiveLogic.pageChange(eventRev: eventRev, ownRev: ownRev, dirty: dirty, gone: gone)
        if c == .ignore && meta { return .refreshHeader }      // neuere Revision geht vor (bringt den Kopf ohnehin mit)
        return c
    }

    /// Anwesenheit ohne Live-Kanal: Stand der Seite (`updated`) neuer als der bekannte, Revision gleich -> Kopf geaendert
    static func headerNewer(updated: Double?, known: Double?) -> Bool {
        guard let u = updated, u > 0 else { return false }
        guard let k = known else { return true }
        return u > k + 0.0005                                   // Sekunden mit Nachkommastellen: Rundung nicht als neu werten
    }

    /// Felder, die eine Kopf-Auffrischung nie anfasst: Inhalt, Titel (eigenes Feld, Revision), Revision
    static let contentKeys: Set<String> = ["body", "delta", "format", "title", "rev", "people"]
    /// Titelbild: waehrend eines eigenen Hochladens nicht ueberschreiben
    static let coverKeys: Set<String> = ["cover", "cover_pos"]

    struct Merge {
        var page: [String: Any]
        /// geaenderte Felder (leer = nichts zu tun)
        var changed: Set<String>
        /// Server hat inzwischen eine neuere Revision (Inhalt/Titel): normal nachladen statt nur den Kopf
        var newerContent: Bool
    }

    /// Frische Antwort (GET /api/notes/{id}?since=) in die geladene Seite uebernehmen - nur die Kopf-Felder.
    /// Felder, die der Server weglaesst (z. B. blocked, fx_err), verschwinden auch lokal; Personen werden ergaenzt.
    static func merge(local: [String: Any], fresh: [String: Any], ownRev: Int, keepCover: Bool) -> Merge {
        let fr = ShareJSON.int(fresh["rev"]) ?? ownRev
        var out = local
        var changed = Set<String>()
        let skip = contentKeys.union(keepCover ? coverKeys : [])
        for (k, v) in fresh where !skip.contains(k) {
            if !same(local[k], v) { out[k] = v; changed.insert(k) }
        }
        for k in local.keys where fresh[k] == nil && !skip.contains(k) {
            out.removeValue(forKey: k); changed.insert(k)
        }
        if let pp = fresh["people"] as? [String: Any], !pp.isEmpty {
            let cur = local["people"] as? [String: Any] ?? [:]
            let m = cur.merging(pp) { _, b in b }
            if !same(cur, m) { out["people"] = m; changed.insert("people") }
        }
        return Merge(page: out, changed: changed, newerContent: fr > ownRev)
    }

    /// JSON-Werte vergleichen (Zahl/Bool/Text/Liste/Woerterbuch, NSNull) - wie Foundation sie liefert
    static func same(_ a: Any?, _ b: Any?) -> Bool {
        switch (a, b) {
        case (nil, nil): return true
        case (nil, _), (_, nil): return false
        default: return (a! as AnyObject).isEqual(b! as AnyObject)
        }
    }

    /// Sperre geaendert?
    static func lockChanged(_ m: Merge) -> Bool { m.changed.contains("locked") }

    // MARK: Titel

    /// Server-Titel uebernehmen, ohne das fokussierte Titelfeld zu ueberschreiben: dann bleibt der angezeigte Titel und
    /// der Server-Titel wird vorgemerkt (beim Verlassen des Feldes nachgezogen)
    static func title(local: String, server: String?, focused: Bool) -> (shown: String, pending: String?) {
        guard let s = server, s != local else { return (local, nil) }
        return focused ? (local, s) : (s, nil)
    }
    /// Titelfeld verlassen: vorgemerkten Server-Titel uebernehmen - aber nur, wenn seitdem nicht getippt wurde
    /// (`kept`: Titel zum Zeitpunkt des Vormerkens). Eigene Eingaben gehen vor (sie werden normal gespeichert).
    static func titleOnBlur(shown: String, kept: String, pending: String?) -> String? {
        guard let p = pending, shown == kept, p != shown else { return nil }
        return p
    }

    // MARK: Sperre

    /// Seite entsperrt (hier oder anderswo), eigene Eingaben noch nicht gespeichert (z. B. nach 423) -> jetzt speichern
    static func saveAfterUnlock(wasLocked: Bool, locked: Bool, dirty: Bool) -> Bool { wasLocked && !locked && dirty }
    /// 423 beim Speichern: die Seite ist (inzwischen) gesperrt - Sperre sofort anzeigen, Eingaben behalten
    static func lockedBySave(code: Int) -> Bool { code == 423 }

    // MARK: Karten/Listen

    /// notes.tree {ids, full}: betrifft das eine der angezeigten Zeilen (Titelbild/Symbol/Sperre einer Karte)?
    /// Titelbild-Aenderungen einer Zeile melden aeltere Server nicht als db.rows - der Baum meldet sie aber.
    static func treeTouches(_ d: [String: Any], rows: Set<Int>) -> Bool {
        if ShareJSON.bool(d["full"]) { return !rows.isEmpty }
        guard let ids = d["ids"] as? [Any] else { return false }
        return ids.contains { ShareJSON.int($0).map(rows.contains) ?? false }
    }
}
