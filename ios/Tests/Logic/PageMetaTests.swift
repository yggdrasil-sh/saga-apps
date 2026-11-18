import Foundation

// 2.5 Bugs-DB 2121 (App/PageMetaLogic.swift): Symbol, Titelbild, Sperre, Eigenschaften anderer Geraete kommen live an.
// Ereignisse wie note.changed in docs/APP-API-LIVE.md (meta: true, rev gleich), Seiten frei erfunden.
func pageMetaTests() {
    typealias L = PageMetaLogic
    // MARK: Ereignis -> Aktion
    check(L.change(eventRev: 5, ownRev: 5, dirty: false, gone: false, meta: true) == .refreshHeader, "meta: Kopf nachziehen (rev gleich)")
    check(L.change(eventRev: 5, ownRev: 5, dirty: true, gone: false, meta: true) == .refreshHeader, "meta mit eigenen Eingaben: trotzdem Kopf (Inhalt bleibt)")
    check(L.change(eventRev: nil, ownRev: 5, dirty: false, gone: false, meta: true) == .refreshHeader, "meta ohne rev: Kopf")
    check(L.change(eventRev: 5, ownRev: 5, dirty: false, gone: false, meta: false) == .ignore, "ohne meta, rev gleich: wie bisher nichts")
    check(L.change(eventRev: 6, ownRev: 5, dirty: false, gone: false, meta: true) == .reloadSilently, "neuere rev geht vor meta")
    check(L.change(eventRev: 6, ownRev: 5, dirty: true, gone: false, meta: true) == .ask, "neuere rev + Eingaben: wie bisher")
    check(L.change(eventRev: nil, ownRev: 5, dirty: false, gone: true, meta: true) == .gone, "weg geht vor meta")
    // Gegenprobe alter Stand: genau dieser Fall wurde ignoriert (Ursache des Befunds)
    check(LiveLogic.pageChange(eventRev: 5, ownRev: 5, dirty: false, gone: false) == .ignore, "Gegenprobe: pageChange allein ignoriert meta")

    // MARK: Anwesenheit ohne Live-Kanal
    check(L.headerNewer(updated: 1700000010.5, known: 1700000000.0), "presence: neuerer Stand")
    check(!L.headerNewer(updated: 1700000000.0, known: 1700000000.0) && !L.headerNewer(updated: 1700000000.0002, known: 1700000000.0), "presence: gleicher Stand (Rundung)")
    check(!L.headerNewer(updated: nil, known: 1) && !L.headerNewer(updated: 0, known: nil), "presence: ohne updated nichts")
    check(L.headerNewer(updated: 5, known: nil), "presence: bisher unbekannt -> nachsehen")

    // MARK: Kopf zusammenfuehren
    func obj(_ s: String) -> [String: Any] { (try? JSONSerialization.jsonObject(with: Data(s.utf8))) as? [String: Any] ?? [:] }
    let local = obj(#"""
    {"id": 41, "title": "Reiseplan", "rev": 7, "body": "{\"blocks\":[]}", "format": "blocks", "icon": "🗺️",
        "cover": "", "cover_pos": 50.0, "locked": false, "props": {"p1": "a"}, "wide": false, "updated": 100.5, "blocked": true,
        "people": {"u1": {"name": "Ada"}}, "role": "owner"}
    """#)
    let fresh = obj(#"""
    {"id": 41, "title": "Reiseplan NEU", "rev": 7, "delta": {"changed": []}, "format": "blocks", "icon": "🧭",
        "cover": "/covers/balke-nordkap.jpg", "cover_pos": 55.0, "locked": true, "props": {"p1": "b"}, "wide": false, "updated": 130.0,
        "people": {"u2": {"name": "Bo"}}, "role": "owner"}
    """#)
    let m = L.merge(local: local, fresh: fresh, ownRev: 7, keepCover: false)
    check(!m.newerContent, "merge: gleiche rev -> nur Kopf")
    check(m.page["icon"] as? String == "🧭" && m.page["cover"] as? String == "/covers/balke-nordkap.jpg"
          && ShareJSON.num(m.page["cover_pos"]) == 55 && ShareJSON.bool(m.page["locked"]), "merge: Symbol, Titelbild + Position, Sperre")
    check((m.page["props"] as? [String: Any])?["p1"] as? String == "b" && ShareJSON.num(m.page["updated"]) == 130, "merge: Eigenschaften, Stand")
    check(m.page["title"] as? String == "Reiseplan" && m.page["body"] as? String == "{\"blocks\":[]}" && ShareJSON.int(m.page["rev"]) == 7
          && m.page["delta"] == nil, "merge: Titel, Inhalt, Revision unangetastet, kein delta")
    check(m.page["blocked"] == nil && m.changed.contains("blocked"), "merge: vom Server weggelassenes Feld verschwindet")
    let pp = m.page["people"] as? [String: Any]
    check(pp?["u1"] != nil && pp?["u2"] != nil, "merge: Personen ergaenzt (Erwaehnungen im Inhalt bleiben)")
    check(L.lockChanged(m) && !m.changed.contains("title") && !m.changed.contains("wide") && !m.changed.contains("role"), "merge: geaenderte Felder genau")
    let busy = L.merge(local: local, fresh: fresh, ownRev: 7, keepCover: true)
    check(busy.page["cover"] as? String == "" && ShareJSON.num(busy.page["cover_pos"]) == 50 && busy.page["icon"] as? String == "🧭",
          "merge: eigenes Hochladen laeuft -> Titelbild bleibt, Rest kommt")
    let same = L.merge(local: m.page, fresh: fresh, ownRev: 7, keepCover: false)
    check(same.changed.isEmpty, "merge: zweites Mal nichts geaendert (kein Neuzeichnen)")
    var newer = fresh; newer["rev"] = 8
    check(L.merge(local: local, fresh: newer, ownRev: 7, keepCover: false).newerContent, "merge: neuere rev -> normal nachladen")
    check(L.same(NSNull(), NSNull()) && !L.same(nil, NSNull()) && L.same(["a": [1, 2]], ["a": [1, 2]]) && !L.same(1, "1"), "same: JSON-Werte")

    // MARK: Titel bei fokussiertem Feld
    let t1 = L.title(local: "Alt", server: "Neu", focused: true)
    check(t1.shown == "Alt" && t1.pending == "Neu", "Titel: fokussiert -> nicht ueberschreiben, vormerken")
    let t2 = L.title(local: "Alt", server: "Neu", focused: false)
    check(t2.shown == "Neu" && t2.pending == nil, "Titel: nicht fokussiert -> uebernehmen")
    check(L.title(local: "Gleich", server: "Gleich", focused: true).pending == nil && L.title(local: "X", server: nil, focused: true).shown == "X", "Titel: gleich/ohne")
    check(L.titleOnBlur(shown: "Alt", kept: "Alt", pending: "Neu") == "Neu", "Titel: beim Verlassen nachziehen")
    check(L.titleOnBlur(shown: "Alt!", kept: "Alt", pending: "Neu") == nil, "Titel: inzwischen getippt -> eigene Eingabe bleibt")
    check(L.titleOnBlur(shown: "Alt", kept: "Alt", pending: nil) == nil, "Titel: nichts vorgemerkt")

    // MARK: Sperre
    check(L.saveAfterUnlock(wasLocked: true, locked: false, dirty: true), "entsperrt mit offenen Eingaben: speichern")
    check(!L.saveAfterUnlock(wasLocked: true, locked: false, dirty: false) && !L.saveAfterUnlock(wasLocked: false, locked: true, dirty: true), "sonst nicht")
    check(L.lockedBySave(code: 423) && !L.lockedBySave(code: 409) && isPermanentSaveError(423), "423: Sperre zeigen, nicht automatisch wiederholen")
    check(DraftResend.outcome(423) == .hold, "423: Entwurf bleibt liegen (nicht verworfen, nicht wiederholt)")

    // MARK: Karten (notes.tree)
    check(L.treeTouches(["ids": [3, 41], "op": "updated"], rows: [41, 42]), "notes.tree: Zeile betroffen")
    check(!L.treeTouches(["ids": [3], "op": "updated"], rows: [41]), "notes.tree: andere Seite")
    check(L.treeTouches(["full": true], rows: [41]) && !L.treeTouches(["full": true], rows: []), "notes.tree full: nur mit Zeilen")
    check(!L.treeTouches(["op": "updated"], rows: [41]), "notes.tree ohne ids")
}
