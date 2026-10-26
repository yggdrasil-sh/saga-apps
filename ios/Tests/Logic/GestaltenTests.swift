import Foundation

// Saga 2.5 "Seite gestalten" (App/GestaltenLogic.swift): Regel 0 (Rundreise), Spaltengewichte, Bildbreite,
// Block-Farbe, Schrift der Seite, Toggle-Ueberschrift, Rueckfall-Karte
func gestaltenTests() {
    // MARK: Regel 0 - laden, einen ANDEREN Block aendern, speichern: Unbekanntes bleibt
    let body = #"""
    {"time":1,"version":"2.31.0","zukunft":{"x":1},"blocks":[
      {"id":"a","type":"paragraph","data":{"text":"eins","neu":"bleibt"},"tunes":{"hkstyle":{"c":"red","bg":true},"fremd":{"k":[1,2]}}},
      {"id":"b","type":"paragraph","data":{"text":"zwei"}},
      {"id":"c","type":"zauberblock","data":{"magie":42,"tief":{"liste":["x"]}},"tunes":{"t":1}},
      {"id":"d","type":"columns","data":{"cols":[{"w":0.7,"blocks":[{"id":"e","type":"paragraph","data":{"text":"in","extra":true},"tunes":{"hkstyle":{"c":"blue"}}}]},{"w":0.3,"blocks":[]}],"rand":"r"}},
      {"id":"f","type":"image","data":{"file":{"url":"/files/x"},"width":40,"align":"right","zukunft":1}},
      {"id":"g","type":"toggle","data":{"title":"T","level":2,"blocks":[{"id":"h","type":"paragraph","data":{"text":"i"},"tunes":{"z":"z"}}]}}
    ]}
    """#
    guard let doc = GestaltenLogic.parseDoc(body), let blocks = doc["blocks"] as? NSMutableArray else { check(false, "Seite nicht lesbar"); return }
    // anderen Block aendern (wie das Textfeld: data["text"] setzen)
    ((blocks[1] as? NSMutableDictionary)?["data"] as? NSMutableDictionary)?["text"] = "zwei geaendert"
    guard let out = GestaltenLogic.serialize(doc, blocks: blocks, timeMs: 99),
          let again = try? JSONSerialization.jsonObject(with: Data(out.utf8)) as? [String: Any],
          let bs = again["blocks"] as? [[String: Any]], bs.count == 6 else { check(false, "Rundreise: nicht gespeichert"); return }
    func data(_ i: Int) -> [String: Any] { bs[i]["data"] as? [String: Any] ?? [:] }
    func tunes(_ b: [String: Any]) -> [String: Any] { b["tunes"] as? [String: Any] ?? [:] }
    check((data(1)["text"] as? String) == "zwei geaendert", "geaenderter Block gespeichert")
    check((again["zukunft"] as? [String: Any])?["x"] as? Int == 1 && again["version"] as? String == "2.31.0" && again["time"] as? Int == 99, "Dokument-Felder bleiben")
    check(data(0)["neu"] as? String == "bleibt", "unbekannter data-Schluessel bleibt")
    check((tunes(bs[0])["hkstyle"] as? [String: Any])?["c"] as? String == "red" && (tunes(bs[0])["hkstyle"] as? [String: Any])?["bg"] as? Bool == true, "tunes.hkstyle bleibt")
    check(((tunes(bs[0])["fremd"] as? [String: Any])?["k"] as? [Int]) == [1, 2], "fremde tunes bleiben")
    check(bs[2]["type"] as? String == "zauberblock" && data(2)["magie"] as? Int == 42 && ((data(2)["tief"] as? [String: Any])?["liste"] as? [String]) == ["x"]
          && tunes(bs[2])["t"] as? Int == 1, "unbekannter Block-Typ unveraendert")
    let cols = data(3)["cols"] as? [[String: Any]] ?? []
    let inner = (cols.first?["blocks"] as? [[String: Any]])?.first ?? [:]
    check(cols.first?["w"] as? Double == 0.7 && data(3)["rand"] as? String == "r", "Spaltengewicht + fremdes Feld bleiben")
    check((inner["data"] as? [String: Any])?["extra"] as? Bool == true && (tunes(inner)["hkstyle"] as? [String: Any])?["c"] as? String == "blue", "verschachtelter Block: data + tunes bleiben")
    check(data(4)["width"] as? Int == 40 && data(4)["align"] as? String == "right" && data(4)["zukunft"] as? Int == 1, "Bild: width/align bleiben")
    let tin = (data(5)["blocks"] as? [[String: Any]])?.first ?? [:]
    check(data(5)["level"] as? Int == 2 && tunes(tin)["z"] as? String == "z", "Toggle: level + innere tunes bleiben")
    check(bs[0]["id"] as? String == "a" && bs[5]["id"] as? String == "g", "IDs bleiben")
    check(GestaltenLogic.parseDoc("{\"blocks\":{}}") == nil && GestaltenLogic.parseDoc("kein json") == nil, "kaputter Inhalt -> nil")

    // Umwandeln nimmt die Farbe mit
    let old = NSDictionary(dictionary: ["id": "x", "type": "paragraph", "data": [:], "tunes": ["hkstyle": ["c": "green"]]])
    let nb = NSMutableDictionary(dictionary: ["id": "x", "type": "header", "data": [:]])
    GestaltenLogic.carryTunes(from: old, to: nb)
    check(GestaltenLogic.style(nb) == GestaltenLogic.Style(color: "green", bg: false), "Umwandeln behaelt Farbe")
    let plainB = NSMutableDictionary(dictionary: ["type": "quote"])
    GestaltenLogic.carryTunes(from: NSDictionary(dictionary: ["type": "paragraph"]), to: plainB)
    check(plainB["tunes"] == nil, "ohne tunes: keine leeren tunes")

    // MARK: G3 Farbe lesen/setzen
    func blk(_ type: String, _ t: Any?) -> NSMutableDictionary {
        let b = NSMutableDictionary(dictionary: ["id": "q", "type": type, "data": NSMutableDictionary()]); if let t { b["tunes"] = t }; return b
    }
    check(GestaltenLogic.style(blk("paragraph", ["hkstyle": ["c": "purple", "bg": true]])) == GestaltenLogic.Style(color: "purple", bg: true), "Hintergrundfarbe")
    check(GestaltenLogic.style(blk("header", ["hkstyle": ["c": "red"]])) == GestaltenLogic.Style(color: "red", bg: false), "bg fehlt = Textfarbe")
    check(GestaltenLogic.style(blk("paragraph", ["hkstyle": ["c": "#ff0000"]])) == nil, "freie Farbe ignoriert")
    check(GestaltenLogic.style(blk("paragraph", ["hkstyle": ["c": "lime"]])) == nil, "Farbe ausserhalb der Palette ignoriert")
    check(GestaltenLogic.style(blk("image", ["hkstyle": ["c": "red"]])) == nil, "Bild: keine Farbe")
    check(GestaltenLogic.style(blk("paragraph", nil)) == nil, "ohne tunes: Standard")
    for t in ["paragraph", "header", "list", "quote", "toggle", "callout"] { check(GestaltenLogic.style(blk(t, ["hkstyle": ["c": "blue"]])) != nil, "Farbe fuer \(t)") }
    let sb = blk("paragraph", ["andere": 1])
    GestaltenLogic.setStyle(sb, GestaltenLogic.Style(color: "yellow", bg: true))
    check(GestaltenLogic.style(sb) == GestaltenLogic.Style(color: "yellow", bg: true) && (sb["tunes"] as? NSDictionary)?["andere"] as? Int == 1, "setzen behaelt andere tunes")
    GestaltenLogic.setStyle(sb, GestaltenLogic.Style(color: "yellow", bg: false))
    check(((sb["tunes"] as? NSDictionary)?["hkstyle"] as? NSDictionary)?["bg"] == nil, "Textfarbe ohne bg-Feld")
    GestaltenLogic.setStyle(sb, nil)
    check(GestaltenLogic.style(sb) == nil && (sb["tunes"] as? NSDictionary)?["andere"] as? Int == 1, "Standard entfernt nur hkstyle")
    let sc = blk("paragraph", ["hkstyle": ["c": "red"]]); GestaltenLogic.setStyle(sc, nil)
    check(sc["tunes"] == nil, "letzter Tune weg -> tunes entfernt")
    GestaltenLogic.setStyle(sc, GestaltenLogic.Style(color: "magenta", bg: false))
    check(sc["tunes"] == nil, "unbekannte Farbe wird nicht geschrieben")
    check(GestaltenLogic.palette.count == 9, "Palette mit 9 Farben")

    // MARK: G1 Spaltengewichte
    func colsOf(_ ws: [Any?]) -> NSArray { NSArray(array: ws.map { w -> NSDictionary in w.map { NSDictionary(dictionary: ["w": $0, "blocks": []]) } ?? NSDictionary(dictionary: ["blocks": []]) }) }
    let w1 = GestaltenLogic.colWeights(colsOf([0.7, 0.3])) ?? []
    check(w1.count == 2 && abs(w1[0] - 0.7) < 1e-9 && abs(w1[1] - 0.3) < 1e-9, "Gewichte 70/30")
    let w2 = GestaltenLogic.colWeights(colsOf([3, 1])) ?? []
    check(w2.count == 2 && abs(w2[0] - 0.75) < 1e-9, "Gewichte werden normiert (3:1)")
    check(GestaltenLogic.colWeights(colsOf([0.5, nil])) == nil, "eine Spalte ohne w -> gleich breit")
    check(GestaltenLogic.colWeights(colsOf([0.5, 0.5])) == nil, "gleiche Gewichte -> wie bisher")
    check(GestaltenLogic.colWeights(colsOf([1, -1])) == nil && GestaltenLogic.colWeights(colsOf([1, 0])) == nil, "Gewicht <= 0 -> gleich breit")
    check(GestaltenLogic.colWeights(colsOf([1, "x"])) == nil && GestaltenLogic.colWeights(colsOf([1, true])) == nil, "kein Zahlenwert -> gleich breit")
    let w3 = GestaltenLogic.colWeights(colsOf([0.98, 0.02])) ?? []
    check(w3.count == 2 && abs(w3[1] - 0.15) < 1e-9 && abs(w3[0] - 0.85) < 1e-9, "Mindestbreite 0,15")
    let w4 = GestaltenLogic.colWeights(colsOf([0.9, 0.04, 0.03, 0.03])) ?? []
    check(w4.count == 4 && w4.allSatisfy { $0 >= 0.15 - 1e-9 } && abs(w4.reduce(0, +) - 1) < 1e-9, "4 Spalten: alle >= 0,15, Summe 1")
    check(GestaltenLogic.colWeights(colsOf([1, 1, 1, 1, 1])) == nil && GestaltenLogic.colWeights(colsOf([1])) == nil, "mehr als 4 bzw. 1 Spalte -> wie bisher")

    // MARK: G2 Breite/Ausrichtung
    func d(_ x: [String: Any]) -> NSDictionary { NSDictionary(dictionary: x) }
    check(GestaltenLogic.mediaWidth(d(["width": 40]), compact: false) == 0.4, "Breite 40 %")
    check(GestaltenLogic.mediaWidth(d(["width": 40]), compact: true) == 0.5, "Telefon: mindestens 50 %")
    check(GestaltenLogic.mediaWidth(d(["width": 80]), compact: true) == 0.8, "Telefon: 80 % bleibt")
    check(GestaltenLogic.mediaWidth(d(["width": 3]), compact: false) == 0.1, "unter 10 -> 10 %")
    check(GestaltenLogic.mediaWidth(d(["width": 250]), compact: false) == nil && GestaltenLogic.mediaWidth(d(["width": 100]), compact: false) == nil, "ab 100 % -> wie bisher")
    check(GestaltenLogic.mediaWidth(d(["width": 40, "stretched": true]), compact: false) == nil, "stretched gewinnt")
    check(GestaltenLogic.mediaWidth(d(["width": "40"]), compact: false) == nil && GestaltenLogic.mediaWidth(d([:]), compact: false) == nil, "fehlt/kein Zahlenwert -> wie bisher")
    check(GestaltenLogic.mediaWidth(d(["width": 33.6]), compact: false) == 0.34, "gerundet")
    check(GestaltenLogic.align(d(["align": "left"])) == .left && GestaltenLogic.align(d(["align": "right"])) == .right, "Ausrichtung links/rechts")
    check(GestaltenLogic.align(d(["align": "justify"])) == .center && GestaltenLogic.align(nil) == .center, "Standard mittig")
    check(GestaltenLogic.isPlayer("https://www.youtube.com/watch?v=dQw4w9WgXcQ") && GestaltenLogic.isPlayer("https://youtu.be/dQw4w9WgXcQ"), "YouTube = Player")
    check(!GestaltenLogic.isPlayer("https://example.org/video") && !GestaltenLogic.isPlayer("https://www.youtube.com/"), "Link-Karte = kein Player")

    // MARK: G9 Schrift
    check(GestaltenLogic.pageFont(["font": "serif"]) == .serif && GestaltenLogic.pageFont(["font": "mono"]) == .mono, "Schrift serif/mono")
    check(GestaltenLogic.pageFont(["font": NSNull()]) == .standard && GestaltenLogic.pageFont([:]) == .standard && GestaltenLogic.pageFont(["font": "comic"]) == .standard, "Standard (null/fehlt/unbekannt)")
    check(GestaltenLogic.pageSmall(["small": true]) && GestaltenLogic.pageSmall(["small": 1]) && !GestaltenLogic.pageSmall(["small": false]) && !GestaltenLogic.pageSmall([:]), "kleiner Text")
    check(GestaltenLogic.supportsPageFont(["small": false]) && !GestaltenLogic.supportsPageFont(["wide": true]), "Umschalter nur mit Server 2.5")
    check(GestaltenLogic.patchValue(.standard) == "default" && GestaltenLogic.patchValue(.serif) == "serif", "PATCH-Werte")
    check(GestaltenLogic.smallerStep(3) == 1 && GestaltenLogic.smallerStep(1) == 0, "kleiner: zwei Stufen")

    // MARK: G10 Toggle-Ueberschrift
    check(GestaltenLogic.toggleLevel(d(["level": 2])) == 2 && GestaltenLogic.toggleLevel(d([:])) == 0, "Toggle-Ebene")
    check(GestaltenLogic.toggleLevel(d(["level": 9])) == 3 && GestaltenLogic.toggleLevel(d(["level": -1])) == 0 && GestaltenLogic.toggleLevel(d(["level": "2"])) == 0, "Toggle-Ebene begrenzt")
    check(GestaltenLogic.toggleLevel(d(["level": 1e300])) == 3 && GestaltenLogic.toggleLevel(d(["level": -1e300])) == 0, "Toggle-Ebene: riesige Werte ohne Absturz")

    // MARK: R Rueckfall-Karte
    check(GestaltenLogic.needsFallback("zauberblock") && GestaltenLogic.needsFallback("immich"), "unbekannter Typ -> Karte")
    check(!GestaltenLogic.needsFallback("paragraph") && !GestaltenLogic.needsFallback("nextcloud") && !GestaltenLogic.needsFallback("verweis"), "bekannte/stille Typen -> keine Karte")
    check(GestaltenLogic.webPath(note: 12, block: "ab-c_9") == "/notes?embed=1#12:ab-c_9", "Web-Pfad mit Block-Anker")
    check(GestaltenLogic.webPath(note: 12, block: "a b#") == "/notes?embed=1#12:a%20b%23", "Block-Anker kodiert")
    check(GestaltenLogic.webPath(note: -2049, block: nil) == "/notes?embed=1#-2049" && GestaltenLogic.webPath(note: 3, block: "") == "/notes?embed=1#3", "Web-Pfad ohne Anker")
}
