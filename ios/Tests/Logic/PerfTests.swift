import Foundation

// Leistung Phase 1: Seitenliste im Hintergrund, Index, lokales Einfuegen, Spotlight-Abgleich, Abgleich-Takt.
// Vergleicht das neue Verhalten mit dem bisherigen Code (alte Fassungen hier nachgebaut).
func perfTests() {
    func pg(_ id: Int, _ title: String, parent: Int? = nil, kind: String = "page", archived: Bool = false, icon: String = "") -> Page {
        var p = Page.created(id: id, parent: parent, title: title, icon: icon, kind: kind, updated: 1)
        p.archived = archived
        return p
    }
    // Baum: 1 Seite > 2 Unterseite > 3 Datenbank > 4,5 Eintraege; 6 archiviert mit Kind 7; 8 oberste
    let all = [pg(1, "Projekte", icon: "📁"), pg(2, "Saga", parent: 1), pg(3, "Aufgaben", parent: 2, kind: "database"),
               pg(4, "Eintrag A", parent: 3), pg(5, "", parent: 3), pg(6, "Alt", archived: true), pg(7, "Alt-Kind", parent: 6, archived: true),
               pg(8, "Notizen", icon: "/files/x.png")]
    let l = PageList.build(all)
    // bisherige Berechnung (Store.setPages)
    let oldVis = all.filter { $0.archived != true }
    let aIDs = Set(all.filter { $0.archived == true }.map(\.id))
    let oldArch = all.filter { $0.archived == true && !($0.parent_id.map { aIDs.contains($0) } ?? false) }
    check(l.visible == oldVis, "sichtbare Seiten wie bisher")
    check(l.archivedTop == oldArch && l.archivedTop.map(\.id) == [6], "Archiv: nur oberste wie bisher")
    check(l.index[4] == 3 && l.index[6] == nil && l.ids.contains(6), "Index ueber sichtbare, ids inkl. Archiv")
    // Kinder wie Store.children(of:) bisher (Reihenfolge der Liste)
    func oldChildren(_ id: Int?) -> [Int] {
        oldVis.filter { p in p.parent_id == id && !(p.parent_id.map { pid in oldVis.first { $0.id == pid }?.isDatabase ?? false } ?? false) }.map(\.id)
    }
    func newChildren(_ id: Int?) -> [Int] {
        if let id, l.visible[l.index[id] ?? 0].id == id, l.visible[l.index[id]!].isDatabase { return [] }
        return (l.kids[id] ?? []).map { l.visible[$0].id }
    }
    for id in [nil, 1, 2, 3, 4, 8] as [Int?] { check(oldChildren(id) == newChildren(id), "Kinder von \(String(describing: id)) wie bisher") }

    // Abgleich: unveraendert -> nil (nichts zuweisen, Spotlight/Cache nicht anfassen); Aenderung erkannt
    check(PageList.update(all, previous: l) == nil, "gleiche Liste: keine Uebernahme")
    var changed = all; changed[1].title = "Saga 2"
    let u = PageList.update(changed, previous: l)
    check(u != nil && u!.visibleChanged && !u!.archivedChanged, "Titel geaendert: sichtbar neu, Archiv nicht")
    var archOnly = all; archOnly[6].title = "Alt-Kind 2"
    let ua = PageList.update(archOnly, previous: l)
    check(ua != nil && !ua!.visibleChanged && !ua!.archivedChanged, "nur archiviertes Kind geaendert: nichts neu zeichnen")
    check(PageList.update(all, previous: nil)?.visibleChanged == true, "erste Liste wird uebernommen")

    // Lokal einfuegen = was der Server danach liefert (am Ende, gleiche Felder)
    let neu = Page.created(id: 9, parent: 3, updated: 5)
    let ins = PageList.inserting(neu, into: all, ids: l.ids)
    check(ins?.last == neu && ins?.count == all.count + 1, "neuer Eintrag am Ende (sort = jetzt)")
    check(PageList.inserting(Page.created(id: 10, parent: 999, updated: 5), into: all, ids: l.ids) == nil, "fremde Elternseite: neu laden")
    check(PageList.inserting(Page.created(id: 4, parent: 3, updated: 5), into: all, ids: l.ids) == all, "schon vorhanden: unveraendert")
    check(PageList.inserting(Page.created(id: 11, parent: 6, updated: 5), into: all, ids: l.ids) != nil, "Elternseite archiviert, aber eigene")
    let json = #"[{"id":9,"title":"","icon":"","parent_id":3,"updated":5,"tags":[],"pinned":false,"template":false,"kind":"page","archived":false,"locked":false,"shared":false,"unread":false}]"#
    check((try? PageList.decode(Data(json.utf8)))?.first == neu, "lokal angelegt == Serverantwort /api/notes (kein Neuzeichnen beim Abgleich)")

    // Spotlight: Inhalt wie bisher (SpotlightIndex.update alt), DB-Eintraege bleiben auffindbar
    let e = SpotlightPlan.entries(l.visible)
    let byId = Dictionary(l.visible.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
    func oldPath(_ p: Page) -> String {
        var out: [String] = [], cur = p.parent_id, n = 0
        while let c = cur, let pp = byId[c], n < 20 { out.insert(pp.label, at: 0); cur = pp.parent_id; n += 1 }
        return out.joined(separator: " / ")
    }
    for (p, x) in zip(l.visible, e) {
        let pth = oldPath(p)
        let desc = pth.isEmpty ? (p.isDatabase ? String(localized: "Datenbank") : String(localized: "Seite")) : pth
        var dn: String?; if let ic = p.icon, !ic.isEmpty, ic.count <= 4, !ic.hasPrefix("/") { dn = ic + " " + p.label }
        check(x.id == p.id && x.title == p.label && x.desc == desc && x.display == dn, "Spotlight-Eintrag \(p.id) wie bisher")
    }
    check(e.first { $0.id == 4 }?.isRow == true && e.first { $0.id == 3 }?.isRow == false, "DB-Eintrag erkannt, Datenbank selbst nicht")
    check(e.first { $0.id == 4 }?.desc == "Projekte / Saga / Aufgaben", "Pfad eines Eintrags")
    let full = SpotlightPlan.diff(old: nil, new: e)
    check(full.full && full.upsert.count == e.count && full.upsert.prefix(4).allSatisfy { !$0.isRow }, "erster Abgleich: alles, Seiten vor Eintraegen")
    let known = SpotlightPlan.known(e)
    check(SpotlightPlan.diff(old: known, new: e) == .init(full: false, upsert: [], delete: []), "unveraendert: nichts senden")
    let e2 = SpotlightPlan.entries(u!.list.visible)                    // "Saga" -> "Saga 2": Seite + alle darunter
    let d2 = SpotlightPlan.diff(old: known, new: e2)
    check(Set(d2.upsert.map(\.id)) == [2, 3, 4, 5] && d2.delete.isEmpty, "Titel geaendert: Seite und Pfade darunter")
    let e3 = SpotlightPlan.entries(l.visible.filter { $0.id != 8 })
    check(SpotlightPlan.diff(old: known, new: e3) == .init(full: false, upsert: [], delete: [8]), "geloescht: nur entfernen")

    // Abgleich-Takt: ohne Live-Kanal wie bisher (> 50 s), mit Live-Kanal seltener; Nachholen nach Wiederverbinden
    let t0 = Date(timeIntervalSince1970: 1000)
    check(RefreshPolicy.due(last: t0, now: t0 + 61, liveConnected: false), "ohne Live: jede Minute")
    check(!RefreshPolicy.due(last: t0, now: t0 + 40, liveConnected: false), "ohne Live: nicht oefter als bisher")
    check(!RefreshPolicy.due(last: t0, now: t0 + 61, liveConnected: true) && RefreshPolicy.due(last: t0, now: t0 + 121, liveConnected: true), "mit Live ohne notes.tree: alle 2 Minuten (maessig)")
    check(!RefreshPolicy.due(last: t0, now: t0 + 241, liveConnected: true, treeEvents: true) && RefreshPolicy.due(last: t0, now: t0 + 301, liveConnected: true, treeEvents: true), "Server meldet Baum-Aenderungen: Takt nur Sicherheitsnetz (5 min)")
    check(RefreshPolicy.due(last: t0, now: t0 + 61, liveConnected: false, treeEvents: true), "Live getrennt: wieder jede Minute")
    check(RefreshPolicy.catchUp(last: t0, now: t0 + 60) && !RefreshPolicy.catchUp(last: t0, now: t0 + 5), "Wiederverbinden: nachholen, ausser gerade abgeglichen")
}
