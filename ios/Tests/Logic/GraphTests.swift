import Foundation

// Graph der Seiten (2.5): Simulation, lokaler Graph, Filter, Trefferflaechen, Antwort lesen, Texte
func graphTests() {
    // Antwort lesen: kaputte Eintraege ueberspringen, Bool nicht als Zahl
    let json = #"{"nodes":[[1,"A","",  "page",1,null],[2,"B","x","database",0,null],[3,"C","","page",1,2],["x"],[4,"","","page",1,null]],"edges":[[1,2,"l"],[2,3,"c"],[true,3,"l"],[3,1,"r"]],"tags":[["t",[1,3,99]]],"truncated":true}"#
    let raw = GraphRaw.parse(Data(json.utf8))
    check(raw?.nodes.count == 4, "Graph: kaputter Knoten uebersprungen")
    check(raw?.edges.count == 3, "Graph: Kante mit Bool-ID uebersprungen")
    check(raw?.truncated == true, "Graph: gekuerzt erkannt")
    check(raw?.nodes[1].own == false && raw?.nodes[2].parent == 2, "Graph: eigen/eltern gelesen")
    check(GraphRaw.parse(Data("{}".utf8)) == nil, "Graph: ohne nodes -> nil")

    // Filter
    if let raw {
        var f = GraphFilter()
        var g = GraphData.build(raw, filter: f, untitled: "Ohne Titel")
        check(g.nodes.count == 4 && g.edges.count == 3, "Graph: alle Knoten/Kanten (\(g.nodes.count)/\(g.edges.count))")
        check(g.nodes[3].title == "Ohne Titel" && g.nodes[3].deg == 0, "Graph: leerer Titel + Waise")
        check(g.nodes[0].deg == 2, "Graph: Grad nach Verbindungen")
        f.orphans = false
        g = GraphData.build(raw, filter: f)
        check(g.nodes.count == 3, "Graph: Waisen ausgeblendet")
        f = GraphFilter(); f.child = false; f.rel = false
        g = GraphData.build(raw, filter: f)
        check(g.edges.count == 1 && g.edges[0].kind == "l", "Graph: nur Verweise")
        f = GraphFilter(); f.tags = true
        g = GraphData.build(raw, filter: f)
        check(g.nodes.count == 5 && g.nodes[4].kind == .tag && g.nodes[4].deg == 2, "Graph: Tag als Knoten mit 2 Seiten (unbekannte ID ignoriert)")
        check(g.matches("b")?.count == 1 && g.matches("  ") == nil, "Graph: Suche")
        // Datenbank-Eintraege ausblenden: Zeile = Seite unter einer Datenbank (Art row)
        let rowRaw = GraphRaw(nodes: [.init(id: 1, title: "DB", icon: "", kind: "database", own: true, parent: nil),
                                      .init(id: 2, title: "R", icon: "", kind: "row", own: true, parent: 1)],
                              edges: [.init(a: 1, b: 2, kind: "c")])
        var fr = GraphFilter(); fr.rows = false
        check(GraphData.build(rowRaw, filter: fr).nodes.count == 1, "Graph: Eintraege ausgeblendet")
        check(GraphData.build(rowRaw, filter: fr, focus: 2).nodes.count == 2, "Graph: lokaler Graph zeigt die offene Zeile trotzdem")
        // doppelte Kante (Verweis + Unterseite) = eine Linie
        let dup = GraphRaw(nodes: rowRaw.nodes, edges: [.init(a: 1, b: 2, kind: "c"), .init(a: 2, b: 1, kind: "l")])
        check(GraphData.build(dup, filter: GraphFilter()).edges.count == 1, "Graph: eine Linie je Paar")
    }

    // Lokaler Graph: Kette 1-2-3-4-5 + Abzweig 3-6
    let chain = GraphRaw(nodes: (1...7).map { .init(id: $0, title: "N\($0)", icon: "", kind: "page", own: true, parent: nil) },
                         edges: [.init(a: 1, b: 2, kind: "l"), .init(a: 2, b: 3, kind: "l"), .init(a: 3, b: 4, kind: "c"),
                                 .init(a: 4, b: 5, kind: "l"), .init(a: 3, b: 6, kind: "r")])
    let l1 = GraphData.build(chain, filter: GraphFilter(), focus: 3, depth: 1)
    check(Set(l1.nodes.compactMap(\.pageID)) == [2, 3, 4, 6], "Graph lokal Tiefe 1: \(l1.nodes.compactMap(\.pageID))")
    check(l1.edges.count == 3, "Graph lokal: nur Kanten innerhalb")
    let l2 = GraphData.build(chain, filter: GraphFilter(), focus: 3, depth: 2)
    check(Set(l2.nodes.compactMap(\.pageID)) == [1, 2, 3, 4, 5, 6], "Graph lokal Tiefe 2")
    var noRel = GraphFilter(); noRel.rel = false
    check(GraphData.build(chain, filter: noRel, focus: 3, depth: 2).index(of: 6) == nil, "Graph lokal: Filter wirkt auf Nachbarschaft")
    let alone = GraphData.build(chain, filter: GraphFilter(), focus: 7, depth: 2)
    check(alone.nodes.count == 1 && !alone.missing, "Graph lokal: Seite ohne Verbindung allein")
    check(GraphData.build(chain, filter: GraphFilter(), focus: 99).missing, "Graph lokal: Seite fehlt")
    check(Set(l1.neighbors[l1.index(of: 3)!].map { l1.nodes[$0].pageID! }) == [2, 4, 6], "Graph: Nachbarn")

    // Simulation konvergiert: steht still, endlich, verbundene Knoten naeher als der Rest
    func converges(_ n: Int, _ label: String) {
        let g = GraphData.build(GraphDemo.raw(nodes: n), filter: GraphFilter())
        let sim = GraphSim(g)
        var guardTicks = 0
        while sim.running && guardTicks < 2000 { sim.tick(); guardTicks += 1 }
        check(!sim.running && sim.ticks < 450, "\(label): kuehlt ab (\(sim.ticks) Schritte)")
        check(sim.maxSpeed < 0.5, "\(label): steht still (v=\(sim.maxSpeed))")
        check(sim.x.allSatisfy(\.isFinite) && sim.y.allSatisfy(\.isFinite), "\(label): endlich")
        func d(_ a: Int, _ b: Int) -> Double { let p = sim.position(a), q = sim.position(b); return ((p.0 - q.0) * (p.0 - q.0) + (p.1 - q.1) * (p.1 - q.1)).squareRoot() }
        let edgeMean = g.edges.map { d($0.a, $0.b) }.reduce(0, +) / Double(max(1, g.edges.count))
        var all = 0.0, cnt = 0
        for i in stride(from: 0, to: g.nodes.count, by: 3) { for j in stride(from: 1, to: g.nodes.count, by: 5) where i != j { all += d(i, j); cnt += 1 } }
        check(edgeMean < all / Double(cnt), "\(label): Kanten kurz (\(Int(edgeMean)) < \(Int(all / Double(cnt))))")
        // kein Knoten auf einem anderen (Abstossung wirkt)
        var close = 0
        for i in 0..<min(200, g.nodes.count) { for j in (i + 1)..<min(200, g.nodes.count) where d(i, j) < 0.5 { close += 1 } }
        check(close == 0, "\(label): keine Knoten aufeinander (\(close))")
    }
    converges(60, "Sim direkt")
    converges(600, "Sim Barnes-Hut")
    // reduzierte Bewegung: settle rechnet fertig, alpha 0
    let s0 = GraphSim(GraphData.build(GraphDemo.raw(nodes: 80), filter: GraphFilter()))
    s0.settle()
    check(!s0.running && s0.alpha == 0, "Sim: settle steht")
    // Filter geaendert: bekannte Knoten behalten ihre Position
    let gA = GraphData.build(chain, filter: GraphFilter())
    let sA = GraphSim(gA); sA.settle()
    var old: [String: (Double, Double)] = [:]
    for i in gA.nodes.indices { old[gA.nodes[i].key] = sA.position(i) }
    let gB = GraphData.build(chain, filter: noRel), sB = GraphSim(gB, old: old)
    check(sB.position(gB.index(of: 2)!) == sA.position(gA.index(of: 2)!), "Sim: Position bleibt beim Filtern")
    // gezogener Knoten bleibt stehen
    let sP = GraphSim(gA); sP.move(0, to: (500, 500)); sP.pinned = 0; sP.alpha = 0.3
    for _ in 0..<30 { sP.tick() }
    check(sP.position(0) == (500, 500), "Sim: gezogener Knoten steht")

    // Barnes-Hut ~ direkte Rechnung (Richtung der Kraft)
    let gq = GraphData.build(GraphDemo.raw(nodes: 320), filter: GraphFilter())
    let start = GraphSim(gq)
    let bh = GraphSim(gq); bh.tick()
    GraphSim.testDirect = true
    let di = GraphSim(gq); di.tick()
    GraphSim.testDirect = false
    var agree = 0
    for i in 0..<gq.nodes.count {
        let p0 = start.position(i), a = bh.position(i), b = di.position(i)
        let ax = a.0 - p0.0, ay = a.1 - p0.1, bx = b.0 - p0.0, by = b.1 - p0.1
        let na = (ax * ax + ay * ay).squareRoot(), nb = (bx * bx + by * by).squareRoot()
        if nb < 1e-6 || (ax * bx + ay * by) / (na * nb + 1e-12) > 0.8 { agree += 1 }
    }
    check(Double(agree) / Double(gq.nodes.count) > 0.9, "Barnes-Hut nahe an direkt (\(agree)/\(gq.nodes.count))")

    // Trefferflaeche: weit herausgezoomt winziger Punkt -> trotzdem 10 pt (Maus) bzw. 22 pt (Finger)
    let cam = GraphCamera(k: 0.05, tx: 100, ty: 100)
    let r = GraphGeo.radius(deg: 0, tag: false, k: cam.k)
    check(abs(r * cam.k - 2.5) < 0.001, "Treffer: Mindestradius 2,5 pt auf dem Bildschirm")
    let xs = [0.0], ys = [0.0], rs = [r]
    check(GraphGeo.hit(sx: 109, sy: 100, cam: cam, xs: xs, ys: ys, radii: rs, slop: GraphGeo.hitMouse) == 0, "Treffer: 9 pt Maus")
    check(GraphGeo.hit(sx: 111, sy: 100, cam: cam, xs: xs, ys: ys, radii: rs, slop: GraphGeo.hitMouse) == nil, "Treffer: 11 pt Maus daneben")
    check(GraphGeo.hit(sx: 121, sy: 100, cam: cam, xs: xs, ys: ys, radii: rs, slop: GraphGeo.hitTouch) == 0, "Treffer: 21 pt Finger")
    check(GraphGeo.hit(sx: 123, sy: 100, cam: cam, xs: xs, ys: ys, radii: rs, slop: GraphGeo.hitTouch) == nil, "Treffer: 23 pt Finger daneben")
    // gross gezoomt: ganzer Kreis + 4 pt, naechster Rand gewinnt
    let big = GraphCamera(k: 4, tx: 0, ty: 0), rb = GraphGeo.radius(deg: 9, tag: false, k: 4)
    check(GraphGeo.hit(sx: rb * 4 + 3, sy: 0, cam: big, xs: [0], ys: [0], radii: [rb], slop: 10) == 0, "Treffer: Rand + 4 pt")
    check(GraphGeo.hit(sx: 10, sy: 0, cam: GraphCamera(), xs: [0, 14], ys: [0, 0], radii: [3, 3], slop: 10) == 1, "Treffer: naechster gewinnt")

    // Kamera: Zoom um Punkt, Grenzen, Einpassen
    var c = GraphCamera(k: 1, tx: 10, ty: 20)
    let before = c.toWorld(200, 150); c.zoom(at: 200, 150, by: 2); let after = c.toWorld(200, 150)
    check(abs(before.0 - after.0) < 1e-9 && abs(before.1 - after.1) < 1e-9 && c.k == 2, "Kamera: Punkt bleibt beim Zoomen")
    c.zoom(at: 0, 0, by: 1000); check(c.k == GraphCamera.maxK, "Kamera: hoechstens 8")
    let fit = GraphCamera.fit((-100, -50, 100, 50), w: 800, h: 600)
    let (sx0, _) = fit.toScreen(-100, 0), (sx1, _) = fit.toScreen(100, 0)
    check(sx0 > 0 && sx1 < 800 && abs((sx0 + sx1) / 2 - 400) < 1e-6, "Kamera: einpassen mittig")
    check(GraphGeo.labelAlpha(k: 0.2, deg: 0, few: false) == 0 && GraphGeo.labelAlpha(k: 3, deg: 0, few: false) == 1
          && GraphGeo.labelAlpha(k: 0.1, deg: 0, few: true) == 1, "Beschriftung je Zoom")

    // Hervorheben: nach 180 ms fertig, Nachbarn mit, Rest blass
    var h = GraphHighlight(count: l1.nodes.count)
    let f3 = l1.index(of: 3)!, f2 = l1.index(of: 2)!
    var busy = h.step(dt: 0.09, focus: f2, neighbors: l1.neighbors, matches: nil, instant: false)
    check(busy && abs(h.dim - 0.5) < 1e-9, "Hervorheben: halb nach 90 ms")
    busy = h.step(dt: 0.1, focus: f2, neighbors: l1.neighbors, matches: nil, instant: false)
    check(h.e[f3] == 1 && h.g[f2] == 1 && h.g[f3] == 0 && h.e[l1.index(of: 6)!] == 0, "Hervorheben: Knoten + Nachbarn")
    check(!h.step(dt: 0.016, focus: f2, neighbors: l1.neighbors, matches: nil, instant: false), "Hervorheben: steht danach")
    _ = h.step(dt: 0, focus: nil, neighbors: l1.neighbors, matches: nil, instant: true)
    check(h.dim == 0 && h.e.allSatisfy { $0 == 0 }, "Hervorheben: reduzierte Bewegung sofort")

    // Leistung: 2000 Knoten (Debug-Build ohne -O: nur Richtwert, Release ist deutlich schneller)
    let g2k = GraphData.build(GraphDemo.raw(nodes: 2000), filter: GraphFilter())
    let s2k = GraphSim(g2k)
    let t0 = Date(); for _ in 0..<20 { s2k.tick() }
    let ms = Date().timeIntervalSince(t0) * 1000 / 20
    print("  Graph 2000 Knoten / \(g2k.edges.count) Kanten: \(String(format: "%.2f", ms)) ms je Schritt")
    let t1 = Date(); var hits = 0
    let radii = g2k.nodes.map { GraphGeo.radius(deg: $0.deg, tag: $0.kind == .tag, k: 1) }
    for i in 0..<200 { if GraphGeo.hit(sx: Double(i), sy: Double(i), cam: GraphCamera(), xs: s2k.x, ys: s2k.y, radii: radii, slop: 10) != nil { hits += 1 } }
    print("  Treffer 2000 Knoten: \(String(format: "%.3f", Date().timeIntervalSince(t1) * 1000 / 200)) ms je Abfrage")

    // Beschriftungen: keine Ueberlappung, wichtigere gewinnt, erzwungene immer, Zahlen nach Sprache
    typealias LB = GraphLabels.Box
    let lc: [GraphLabels.Candidate] = [
        .init(box: LB(x0: 0, y0: 0, x1: 60, y1: 14), deg: 1, hot: 0),
        .init(box: LB(x0: 30, y0: 5, x1: 90, y1: 19), deg: 5, hot: 0),          // ueberlappt 0, mehr Verbindungen
        .init(box: LB(x0: 200, y0: 0, x1: 260, y1: 14), deg: 0, hot: 0),        // frei
        .init(box: LB(x0: 40, y0: 10, x1: 100, y1: 24), deg: 0, hot: 1),        // hervorgehoben schlaegt Grad
        .init(box: LB(x0: 250, y0: 0, x1: 270, y1: 14), deg: 0, hot: 0, forced: true),   // erzwungen, verdraengt 2
        .init(box: LB(x0: 500, y0: 0, x1: 560, y1: 14), deg: 0, hot: 0),        // frei
    ]
    let placed = Set(GraphLabels.place(lc))
    check(placed == [3, 4, 5], "Beschriftung: nur ueberlappungsfrei, Vorrang erzwungen > hervorgehoben > Grad (\(placed.sorted()))")
    check(GraphLabels.place([.init(box: LB(x0: 0, y0: 0, x1: 60, y1: 14), deg: 1, hot: 0), .init(box: LB(x0: 30, y0: 5, x1: 90, y1: 19), deg: 5, hot: 0)]) == [1],
          "Beschriftung: Knoten mit mehr Verbindungen behaelt seine")
    check(GraphLabels.place((0..<500).map { .init(box: LB(x0: Double($0) * 100, y0: 0, x1: Double($0) * 100 + 50, y1: 14), deg: 0, hot: 0) }).count == 300,
          "Beschriftung: hoechstens 300")
    // dichter lokaler Graph (iPhone): kein Paar ueberlappt
    var dense: [GraphLabels.Candidate] = []
    for i in 0..<60 { let x = Double(i % 6) * 40, y = Double(i / 6) * 9; dense.append(.init(box: LB(x0: x, y0: y, x1: x + 70, y1: y + 14), deg: i % 7, hot: 0)) }
    let dp = GraphLabels.place(dense).map { dense[$0].box }
    var dclash = 0
    for a in dp.indices { for b in dp.indices where a < b && GraphLabels.overlaps(dp[a], dp[b], gap: 0) { dclash += 1 } }
    check(dclash == 0 && !dp.isEmpty, "Beschriftung: dichter Graph ohne Ueberlappung (\(dp.count) von 60)")
    check(GraphText.formatted("1234 Seiten", 1234, locale: Locale(identifier: "de_DE")) == "1.234 Seiten", "Zahl de")
    check(GraphText.formatted("1234 pages", 1234, locale: Locale(identifier: "en_US")) == "1,234 pages", "Zahl en")
    check(GraphText.formatted("12 Seiten", 12, locale: Locale(identifier: "de_DE")) == "12 Seiten", "Zahl klein unveraendert")
    check(GraphText.formatted("1234 страницы", 1234, locale: Locale(identifier: "ru_RU")).hasPrefix("1\u{a0}234") || GraphText.formatted("1234 страницы", 1234, locale: Locale(identifier: "ru_RU")).hasPrefix("1\u{202f}234"), "Zahl ru")
    // Einpassen: wenig Rand (iPad), Graph fuellt die Flaeche weitgehend
    let fitPad = GraphCamera.fit((-500, -500, 500, 500), w: 1000, h: 900)
    let (_, py0) = fitPad.toScreen(0, -500), (_, py1) = fitPad.toScreen(0, 500)
    check(py1 - py0 > 900 * 0.85 || fitPad.k == 2, "Einpassen: wenig Rand (\(Int(py1 - py0)) von 900)")

    // Texte des Graphen: eigener Katalog, alle Sprachen, Russisch mit Pluralformen
    let dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent().appendingPathComponent("../../App/Graph.xcstrings")
    let cat = (try? Data(contentsOf: dir)).flatMap { try? JSONSerialization.jsonObject(with: $0) as? [String: Any] }?["strings"] as? [String: [String: Any]] ?? [:]
    check(cat.count > 30, "Graph.xcstrings geladen (\(cat.count))")
    var miss = 0, ph = 0
    for (key, entry) in cat {
        let locs = entry["localizations"] as? [String: [String: Any]] ?? [:]
        let ref = catalogPlaceholders(key)
        for l in ["de", "en", "es", "th", "ru"] {
            guard let loc = locs[l] else { miss += 1; print("  fehlt [\(l)] \(key)"); continue }
            var vs: [String] = []
            if let u = loc["stringUnit"] as? [String: Any], let v = u["value"] as? String { vs = [v] }
            if let p = (loc["variations"] as? [String: Any])?["plural"] as? [String: [String: Any]] { vs = p.values.compactMap { ($0["stringUnit"] as? [String: Any])?["value"] as? String } }
            if vs.isEmpty || vs.contains(where: \.isEmpty) { miss += 1; print("  leer [\(l)] \(key)") }
            for v in vs where catalogPlaceholders(v) != ref { ph += 1; print("  Platzhalter [\(l)] \(key) -> \(v)") }
        }
        let ints = ref.filter { $0.hasSuffix("d") }
        if ints.count == 1 && ref.count == 1 {
            let forms = ((locs["ru"]?["variations"] as? [String: Any])?["plural"] as? [String: Any])?.keys.sorted() ?? []
            check(forms == ["few", "many", "one", "other"], "Graph.xcstrings ru-Plural: \(key)")
        }
    }
    // Russisch: Hinweise duzen (Imperativ 2. Person Singular)
    var siezen = 0
    for (key, entry) in cat {
        guard let u = ((entry["localizations"] as? [String: [String: Any]])?["ru"]?["stringUnit"] as? [String: Any])?["value"] as? String else { continue }
        if u.range(of: #"\b(Выберите|Создайте|Нажмите|Попросите|Войдите|Откройте|Обновите|вы|вас|вам|ваш\w*)\b"#, options: [.regularExpression, .caseInsensitive]) != nil, key != "Mit dir geteilt" { siezen += 1; print("  ru siezt: \(u)") }
    }
    check(siezen == 0, "Graph.xcstrings ru duzt")
    check(miss == 0 && ph == 0, "Graph.xcstrings vollstaendig (\(miss) fehlen, \(ph) Platzhalter)")
}
