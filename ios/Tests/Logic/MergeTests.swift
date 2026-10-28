import Foundation

// Saga 2.5 App-Etappe 2 (App/MergeLogic.swift): Rebase-Buchfuehrung, Uebernahme der zusammengefuehrten Antwort,
// Delta (?since), Konfliktblock Uebernehmen/Verwerfen, Presence-Drossel und Block-Markierungen.
// Fixtures: echte Antworten eines Testservers (Tests/Logic/FixturesE2, Wegwerf-Konten).
func e2Fixture(_ name: String) -> [String: Any] {
    let dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent().appendingPathComponent("FixturesE2")
    guard let d = try? Data(contentsOf: dir.appendingPathComponent(name)),
          let j = try? JSONSerialization.jsonObject(with: d) as? [String: Any] else { check(false, "Fixture \(name)"); return [:] }
    return j
}

func mergeTests() {
    func doc(_ blocks: [[String: Any]]) -> NSMutableArray {
        let d = try! JSONSerialization.data(withJSONObject: blocks)
        return try! JSONSerialization.jsonObject(with: d, options: .mutableContainers) as! NSMutableArray
    }
    func P(_ id: String, _ t: String) -> [String: Any] { ["id": id, "type": "paragraph", "data": ["text": t]] }
    func ids(_ a: NSArray) -> [String] { a.compactMap { ($0 as? NSDictionary)?["id"] as? String } }

    // MARK: Caps und Rumpf
    check(SagaCaps.header.split(separator: ",").contains("merge1"), "merge1 im Kopf")
    check(Set(SagaCaps.all).isSubset(of: ["inline2", "cols6", "merge1", "sync1"]), "nur bekannte Faehigkeiten")
    check(MergeLogic.useMerge(remote: false, caps: []) && !MergeLogic.useMerge(remote: true, caps: ["db"]) && MergeLogic.useMerge(remote: true, caps: ["merge1"]),
          "merge: eigene Seiten immer, fremde nur mit merge1")
    let f0 = MergeLogic.patchFields(rev: 7, merge: true, baseSent: false)
    check(f0["rev"] as? Int == 7 && f0["merge"] as? Int == 1 && f0["base"] == nil, "PATCH mit merge: 1")
    let f1 = MergeLogic.patchFields(rev: 8, merge: true, baseSent: true)
    check(f1["base"] as? String == "sent", "weitergeschrieben -> base: sent")
    let f2 = MergeLogic.patchFields(rev: 8, merge: false, baseSent: true)
    check(f2["merge"] == nil && f2["base"] == nil, "ohne merge (fremder Server ohne merge1) kein base")

    // MARK: Rebase-Buchfuehrung (echte Antwort)
    let mr = e2Fixture("merge_resp.json")
    check(MergeLogic.outcome(response: mr, editsAtSend: 4, editsNow: 4) == .adopt, "unveraendert seit dem Senden -> uebernehmen")
    check(MergeLogic.outcome(response: mr, editsAtSend: 4, editsNow: 6) == .keepLocal, "weitergeschrieben -> lokal behalten, base: sent")
    check(MergeLogic.outcome(response: ["ok": true, "rev": 3], editsAtSend: 1, editsNow: 2) == .saved, "ohne merged -> normal")
    let mi = MergeInfo(mr["merged"])
    check(mi?.conflicts == ["a3-k"] && mi?.from == 1 && mi?.hint == nil, "merged gelesen (Konflikt, kein Hinweis)")
    check(MergeInfo(["restored": ["b2"]])?.hint == .restored && MergeInfo(["lost": ["x"]])?.hint == .lost
          && MergeInfo(["title_lost": true])?.hint == .lost && MergeInfo(nil) == nil, "Hinweise restored/lost/title_lost")

    // Uebernahme: gleiche Bloecke bleiben dieselben Objekte, geaenderte/neue kommen aus der Antwort
    let local = doc([P("a1", "eins Ich"), P("a2", "zwei"), P("a3", "drei mein")])
    let a1 = local[0] as AnyObject
    let srv = MergeLogic.blocks(ofBody: mr["body"] as! String)!
    let adopted = MergeLogic.adopt(local: local, server: srv)
    check(ids(adopted) == ["a1", "a2", "a3", "a3-k"], "Reihenfolge der Antwort")
    check(adopted[0] as AnyObject === a1, "unveraenderter Block (Fokus) bleibt dasselbe Objekt")
    check(((adopted[1] as? NSDictionary)?["data"] as? NSDictionary)?["text"] as? String == "zwei X", "fremde Aenderung uebernommen")
    check(adopted[1] is NSMutableDictionary && (adopted[1] as! NSMutableDictionary)["data"] is NSMutableDictionary, "uebernommene Bloecke veraenderbar")
    let k = MergeLogic.conflict(adopted[3] as? NSDictionary)
    check(k?.of == "a3" && k?.name.isEmpty == false && k?.by.hasPrefix("~") == true, "Konfliktblock erkannt (of, name, by)")

    // MARK: Konfliktblock Uebernehmen / Verwerfen
    let acc = MergeLogic.adopt(local: NSArray(), server: srv)
    check(MergeLogic.accept("a3-k", in: acc), "Uebernehmen")
    check(ids(acc) == ["a1", "a2", "a3-k"] && MergeLogic.conflict(acc[2] as? NSDictionary) == nil
          && (acc[2] as? NSDictionary)?["tunes"] == nil, "Uebernehmen: Original weg, hkconflict weg (leere tunes entfernt)")
    let dis = MergeLogic.adopt(local: NSArray(), server: srv)
    check(MergeLogic.discard("a3-k", in: dis) && ids(dis) == ["a1", "a2", "a3"], "Verwerfen: Konfliktblock weg")
    check(!MergeLogic.discard("a2", in: dis) && !MergeLogic.accept("a1", in: dis), "normale Bloecke nicht betroffen")
    // in einer Spalte, andere Tunes bleiben
    let col = doc([["id": "c", "type": "columns", "data": ["cols": [["blocks": [P("o", "alt"),
        ["id": "o-k", "type": "paragraph", "data": ["text": "neu"], "tunes": ["hkstyle": ["color": "red"], "hkconflict": ["by": "@bob@b.example", "name": "Bob", "of": "o"]]]]]]]]])
    check(MergeLogic.conflict(MergeLogic.locate("o-k", in: col).map { $0.0[$0.1] as? NSDictionary } ?? nil)?.name == "Bob", "Konflikt in Spalte gefunden")
    check(MergeLogic.accept("o-k", in: col), "Uebernehmen in Spalte")
    let inner = (((col[0] as! NSDictionary)["data"] as! NSDictionary)["cols"] as! NSArray)[0] as! NSDictionary
    let ib = inner["blocks"] as! NSArray
    check(ids(ib) == ["o-k"] && ((ib[0] as! NSDictionary)["tunes"] as? NSDictionary)?["hkstyle"] != nil, "Spalte: Original weg, Farbe bleibt")

    // MARK: Delta (?since, echte Antwort)
    let dr = e2Fixture("delta_resp.json")
    let delta = dr["delta"] as! [String: Any]
    let base = doc([P("a1", "eins"), P("a2", "zwei"), P("a3", "drei")])
    let b2 = base[1] as AnyObject
    let nb = MergeLogic.applyDelta(delta, local: base)
    check(nb.map(ids) == ["a1", "a2", "a3", "a3-k"], "Delta: Reihenfolge")
    check((nb?[0] as? NSDictionary)?["data"].flatMap { ($0 as? NSDictionary)?["text"] as? String } == "eins Ich", "Delta: geaenderter Block")
    check(nb?[1] as AnyObject !== b2, "geaenderter Block ersetzt")
    let sparse: [String: Any] = ["from": 2, "blocks": [["id": "a2"], P("n", "neu"), ["id": "a1"]]]
    let sb = MergeLogic.applyDelta(sparse, local: base)
    check(sb.map(ids) == ["a2", "n", "a1"] && sb?[0] as AnyObject === b2, "Delta: unveraendert = eigener Stand, neue Reihenfolge")
    check(MergeLogic.applyDelta(["from": 1, "blocks": [["id": "fehlt"]]], local: base) == nil, "Delta mit unbekanntem Block -> normal laden")

    // MARK: Presence je Block
    var th = PresenceThrottle()
    let t0 = Date(timeIntervalSince1970: 1000)
    check(th.delay(for: "a", now: t0) == 0, "erster Blockwechsel sofort")
    th.sent("a", at: t0)
    check(th.delay(for: "a", now: t0.addingTimeInterval(0.2)) == nil, "gleicher Block: nichts")
    check(abs((th.delay(for: "b", now: t0.addingTimeInterval(0.3)) ?? -1) - 0.7) < 0.001, "schneller Wechsel: warten bis 1 s")
    check(th.delay(for: "c", now: t0.addingTimeInterval(2)) == 0, "nach 1 s sofort")
    check(th.delay(for: nil, now: t0.addingTimeInterval(2)) == 0, "Fokus weg melden")
    th.sent(nil, at: t0.addingTimeInterval(2))
    check(th.delay(for: nil, now: t0.addingTimeInterval(5)) == nil, "kein Block -> kein Block: nichts")
    let pb = PresenceBlocks.body(note: 5, editing: true, block: "a1", sendBlock: true)
    check(pb["block"] as? String == "a1" && pb["editing"] as? Bool == true, "block nur mit editing")
    check(PresenceBlocks.body(note: 5, editing: false, block: "a1", sendBlock: true)["block"] == nil, "ohne editing kein block")
    check(PresenceBlocks.body(note: -5, editing: true, block: "a1", sendBlock: false)["block"] == nil, "fremder Server ohne presence2: kein block")
    check(PresenceBlocks.body(note: 5, editing: true, block: nil, sendBlock: true)["block"] is NSNull, "Fokus weg: block null")
    let pr = e2Fixture("presence.json")
    check(PresenceBlocks.marks(pr["users"] as? [Any]).isEmpty, "eigener Block wird nicht markiert")
    let users: [Any] = [["username": "x", "name": "Anna", "editing": true, "block": "a2", "color": "pink", "me": false],
                        ["username": "y", "name": "Bert", "editing": false, "block": "a2", "color": "blue"],
                        ["username": "z", "name": "", "editing": true, "block": "a2", "color": "green"],
                        ["username": "w", "name": "Cleo", "editing": true, "block": NSNull(), "color": "red"]]
    let m = PresenceBlocks.marks(users)
    check(m.keys.sorted() == ["a2"] && m["a2"] == [PresenceMark(name: "Anna", color: "pink"), PresenceMark(name: String(localized: "Jemand"), color: "green")],
          "Markierung je Block (nur bearbeitend, mit Block; ohne Name -> Jemand)")
}
