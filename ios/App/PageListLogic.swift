import Foundation

// Seitenliste ohne SwiftUI (Leistung, Phase 1): Dekodieren, Aufbereiten und Nachschlage-Index laufen im Hintergrund,
// der Main-Thread weist nur noch das fertige Ergebnis zu. Dazu der Spotlight-Abgleich (nur Geaendertes) und der
// Abgleich-Takt. Reine Logik - geprueft mit Tests/Logic/run.sh.

/// Codable: der Zwischenspeicher haelt seit dem Abgleich (2.2) die gemergte Liste statt der rohen Antwort
struct Page: Codable, Identifiable, Hashable {
    let id: Int
    var title: String
    var icon: String?
    var parent_id: Int?
    var kind: String?
    var pinned: Bool?
    var updated: Double?
    var snippet: String?
    var archived: Bool?
    var template: Bool?
    var tags: [String]?
    // Teilen (1.3): eigene Seiten mit Freigaben (shared), neue Kommentare (unread); in "Mit mir geteilt" dazu
    // Rolle, Eigentuemer und root (oberste geteilte Seite)
    var shared: Bool?
    var unread: Bool?
    var role: String?
    var root: Bool?
    var owner: PageOwner?
    /// Seite einer anderen Saga-Instanz (Foederation, 2.0): {peer, host, name}; die ID ist dann negativ
    var remote: RemoteRef?
    var label: String { title.isEmpty ? String(localized: "Unbenannt") : title }
    var isDatabase: Bool { kind == "database" }
    var isRemote: Bool { remote != nil || id < 0 }

    /// Gerade angelegte eigene Seite bzw. Datenbank-Eintrag - genau so, wie GET /api/notes sie danach liefert
    /// (api_note_add: sort = jetzt -> steht am Ende der Liste; keine Freigabe, kein Kommentar, nicht archiviert)
    static func created(id: Int, parent: Int?, title: String = "", icon: String = "", kind: String = "page", updated: Double) -> Page {
        Page(id: id, title: title, icon: icon, parent_id: parent, kind: kind, pinned: false, updated: updated, snippet: nil,
             archived: false, template: false, tags: [], shared: false, unread: false, role: nil, root: nil, owner: nil, remote: nil)
    }
}
struct PageOwner: Codable, Hashable { var name: String?; var username: String?; var handle: String?; var remote: Bool?; var avatar: String? }

/// Aufbereitete Seitenliste (eigener Baum aus GET /api/notes). Wird im Hintergrund gebaut (`build`), der Store
/// uebernimmt sie auf dem Main-Thread nur noch per Zuweisung.
struct PageList: Sendable {
    /// alles, wie vom Server (inkl. Archiv)
    var all: [Page] = []
    /// sichtbar (ohne Archiv) - das ist Store.pages
    var visible: [Page] = []
    /// Archiv: nur oberste archivierte Seiten
    var archivedTop: [Page] = []
    /// id -> Position in `visible`
    var index: [Int: Int] = [:]
    /// Elternseite -> Positionen der Kinder in `visible` (Reihenfolge wie die Liste; Schluessel nil = oberste Ebene)
    var kids: [Int?: [Int]] = [:]
    /// alle IDs inkl. Archiv (eigene Seite?)
    var ids: Set<Int> = []

    static func decode(_ d: Data) throws -> [Page] { try JSONDecoder().decode([Page].self, from: d) }

    static func build(_ all: [Page]) -> PageList {
        var l = PageList()
        l.all = all
        l.visible = all.filter { $0.archived != true }
        l.index.reserveCapacity(l.visible.count)
        for (i, p) in l.visible.enumerated() {
            if l.index[p.id] == nil { l.index[p.id] = i }            // wie pages.first { $0.id == id }
            l.kids[p.parent_id, default: []].append(i)
        }
        var arch = Set<Int>(); l.ids.reserveCapacity(all.count)
        for p in all { l.ids.insert(p.id); if p.archived == true { arch.insert(p.id) } }
        l.archivedTop = all.filter { $0.archived == true && !($0.parent_id.map { arch.contains($0) } ?? false) }
        return l
    }

    /// Ergebnis eines Abgleichs gegen den bisherigen Stand (im Hintergrund): nil = unveraendert (nichts zuweisen,
    /// nichts neu zeichnen, Spotlight nicht anfassen, Zwischenspeicher nicht neu schreiben)
    struct Update: Sendable {
        let list: PageList
        let visibleChanged: Bool
        let archivedChanged: Bool
    }
    static func update(_ all: [Page], previous: PageList?) -> Update? {
        if let previous, previous.all == all { return nil }
        let l = build(all)
        return Update(list: l, visibleChanged: l.visible != (previous?.visible ?? []), archivedChanged: l.archivedTop != (previous?.archivedTop ?? []))
    }

    /// Lokal angelegte Seite anfuegen (statt die ganze Liste neu zu laden). nil: Elternseite gehoert nicht zum eigenen
    /// Baum (z. B. Unterseite in einer geteilten Seite) - dann wie bisher neu laden.
    static func inserting(_ p: Page, into all: [Page], ids: Set<Int>) -> [Page]? {
        if let parent = p.parent_id, !ids.contains(parent) { return nil }
        if ids.contains(p.id) { return all }                            // schon da (z. B. Abgleich war schneller)
        return all + [p]
    }
}

/// Ein Spotlight-Eintrag (Inhalt wie bisher: Titel, Pfad bzw. "Seite"/"Datenbank", Symbol vor dem Titel)
struct SpotlightEntry: Equatable, Sendable {
    let id: Int
    let title: String
    let desc: String
    let display: String?
    /// Eintrag einer Datenbank: wird nach den Seiten/Datenbanken indiziert (niedrigere Prioritaet beim Abgleich)
    let isRow: Bool
}

enum SpotlightPlan {
    /// Eintraege fuer die sichtbaren Seiten (wie bisher alle - auch Datenbank-Eintraege, damit sie in der Systemsuche
    /// auffindbar bleiben)
    static func entries(_ pages: [Page]) -> [SpotlightEntry] {
        var byId = [Int: Page](minimumCapacity: pages.count)
        for p in pages where byId[p.id] == nil { byId[p.id] = p }
        var pathCache = [Int: String]()
        func path(_ p: Page) -> String {
            // Pfad der Elternseite einmal berechnen und wiederverwenden (Datenbank mit 10.000 Eintraegen: 1x statt 10.000x)
            guard let pid = p.parent_id, byId[pid] != nil else { return "" }
            if let c = pathCache[pid] { return c }
            var out: [String] = [], cur: Int? = pid, guardN = 0
            while let c = cur, let pp = byId[c], guardN < 20 { out.insert(pp.label, at: 0); cur = pp.parent_id; guardN += 1 }
            let s = out.joined(separator: " / ")
            pathCache[pid] = s
            return s
        }
        let seite = String(localized: "Seite"), datenbank = String(localized: "Datenbank")
        return pages.map { p in
            let pth = path(p)
            var display: String?
            if let ic = p.icon, !ic.isEmpty, ic.count <= 4, !ic.hasPrefix("/") { display = ic + " " + p.label }
            let row = p.parent_id.flatMap { byId[$0] }?.isDatabase ?? false
            return SpotlightEntry(id: p.id, title: p.label, desc: pth.isEmpty ? (p.isDatabase ? datenbank : seite) : pth, display: display, isRow: row)
        }
    }

    struct Diff: Equatable {
        /// alles neu (erster Abgleich in diesem Prozess): Bereich leeren, dann alles indizieren - wie bisher
        var full: Bool
        var upsert: [SpotlightEntry]
        var delete: [Int]
    }
    /// Nur Geaendertes/Neues indizieren, Entferntes loeschen. Seiten und Datenbanken zuerst, Datenbank-Eintraege danach.
    static func diff(old: [Int: SpotlightEntry]?, new: [SpotlightEntry]) -> Diff {
        guard let old else {
            return Diff(full: true, upsert: new.filter { !$0.isRow } + new.filter(\.isRow), delete: [])
        }
        var seen = Set<Int>(minimumCapacity: new.count)
        var up: [SpotlightEntry] = [], upRows: [SpotlightEntry] = []
        for e in new where seen.insert(e.id).inserted {
            if old[e.id] != e { if e.isRow { upRows.append(e) } else { up.append(e) } }
        }
        let del = old.keys.filter { !seen.contains($0) }.sorted()
        return Diff(full: false, upsert: up + upRows, delete: del)
    }
    static func known(_ entries: [SpotlightEntry]) -> [Int: SpotlightEntry] {
        var d = [Int: SpotlightEntry](minimumCapacity: entries.count)
        for e in entries where d[e.id] == nil { d[e.id] = e }
        return d
    }
}

/// Abgleich-Takt der Seitenliste im Vordergrund. Ohne Live-Kanal (getrennt, alter Server) wie bisher jede Minute.
/// Mit Live-Kanal: Server, die Baum-Aenderungen melden (Ereignis notes.tree - die App laedt dann sofort nach), brauchen
/// den Takt nur als Sicherheitsnetz (5 min). Solange kein notes.tree kam (aeltere Server: note.changed nur fuer offene
/// Seiten), nur maessig seltener (2 min), damit Aenderungen aus Web/anderen Geraeten zeitnah erscheinen.
enum RefreshPolicy {
    /// Mindestabstand fuer den Takt (der Takt selbst laeuft jede Minute)
    static let minGap: TimeInterval = 50
    static let minGapLive: TimeInterval = 110
    static let minGapLiveTree: TimeInterval = 290
    static func due(last: Date, now: Date, liveConnected: Bool, treeEvents: Bool = false) -> Bool {
        now.timeIntervalSince(last) > (!liveConnected ? minGap : treeEvents ? minGapLiveTree : minGapLive)
    }
    /// Live-Kanal (wieder) verbunden - z. B. nach Netzwechsel, Aufwachen oder Server-Neustart: verpasste Aenderungen
    /// nachholen, sofern nicht gerade erst abgeglichen wurde
    static func catchUp(last: Date, now: Date) -> Bool { now.timeIntervalSince(last) > minGap }
}
