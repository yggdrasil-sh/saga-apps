import Foundation

// Bughunt-Runde 2, Rolle apps: I03/I04 (Anruf-Kopf), V5 (eigenes Bild), A10 (Kontowechsel), Leer-Revisionen
func bh2AppsTests() {
    typealias L = CallLogic
    // I04: Geraetezaehler nur im Gruppenanruf
    check(L.showsDeviceCount(kind: "group", dmRoom: false), "Gruppenanruf: n/Grenze")
    check(!L.showsDeviceCount(kind: "group", dmRoom: true), "1:1 als Knoten-Raum (dm): kein Zaehler")
    check(!L.showsDeviceCount(kind: "dm", dmRoom: false), "1:1 ueber den Knoten mit kind dm: kein Zaehler")
    // I03: iPhone quer (402 pt hoch) - Buehne bekommt den Platz
    let h = 402.0, head = 30.0, ctrl = 52.0
    let flatStage = h - L.stageTop(header: head, flat: true) - L.stageBottom(controls: ctrl, flat: true)
    let oldStage = h - L.stageTop(header: 110, flat: false) - L.stageBottom(controls: 120, flat: false)
    check(flatStage >= 280, "quer: Buehne mind. 280 pt (\(flatStage))")
    check(oldStage < 100, "vorher blieb ein Streifen (\(oldStage))")
    check(L.stageBottom(controls: 100, flat: false) == 170 && L.stageTop(header: 50, flat: false) == 84, "hoch unveraendert")
    // V5: eigenes Bild quer kleiner, damit unten eine eigene Ecke bleibt
    let s = L.pipSize(w: 110, h: 160, avail: 600)
    check(s.w == 110 && s.h == 160, "genug Platz: unveraendert")
    let q = L.pipSize(w: 110, h: 160, avail: 250)
    check(q.h == 121 && q.w == 83, "quer: zweimal uebereinander passend (\(q))")
    check(L.pipSize(w: 110, h: 160, avail: 50).h == 80, "hoechstens bis zur Haelfte")
    check(L.pipCorner(x: 10, y: 10, width: 400, top: 50, bottom: 300) == 0, "oben links")
    check(L.pipCorner(x: 390, y: 290, width: 400, top: 50, bottom: 300) == 3, "unten rechts")
    // A10: Kontowechsel loescht Chat-Schluessel nur bei sicher anderem Konto
    check(ChatLogic.forgetsOldChat(old: ("https://a", "mia"), new: ("https://a", "tom")), "anderer Benutzer")
    check(ChatLogic.forgetsOldChat(old: ("https://a", "mia"), new: ("https://b", "mia")), "anderer Server")
    check(!ChatLogic.forgetsOldChat(old: ("https://a", "mia"), new: ("https://a", "mia")), "gleiches Konto: behalten")
    check(!ChatLogic.forgetsOldChat(old: ("https://a", "mia"), new: ("https://a", "")), "Konto unbekannt: behalten")
    // Leer-Revisionen: unveraenderter Stand wird nicht erneut gesendet
    let b1 = #"{"time":1,"blocks":[{"id":"a","type":"database","data":{"db":3,"views":[{"id":"v","name":"X"}]}}]}"#
    let b2 = #"{"blocks":[{"type":"database","id":"a","data":{"views":[{"name":"X","id":"v"}],"db":3}}],"time":2}"#
    let b3 = #"{"time":3,"blocks":[{"id":"a","type":"database","data":{"db":3,"views":[{"id":"v","name":"XY"}]}}]}"#
    let saved = SavedPageState(body: b1, title: "T", rev: 14)
    check(saveIsNoop(body: b2, title: "T", rev: 14, saved: saved), "gleiche Bloecke (andere Zeit/Reihenfolge): nicht senden")
    check(!saveIsNoop(body: b3, title: "T", rev: 14, saved: saved), "Name geaendert: senden")
    check(!saveIsNoop(body: b2, title: "T2", rev: 14, saved: saved), "Titel geaendert: senden")
    check(!saveIsNoop(body: b2, title: "T", rev: 15, saved: saved), "inzwischen neue Revision: normal senden")
    check(!saveIsNoop(body: b2, title: "T", rev: 14, saved: nil), "noch nichts bestaetigt: senden")
}
