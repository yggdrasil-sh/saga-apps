import Foundation

// Graph der Seiten (2.5, wie Obsidian) - reine Logik ohne SwiftUI, mit swiftc testbar (Tests/Logic/GraphTests.swift).
// Daten: GET /api/graph -> {nodes: [[id, titel, symbol, art, eigen, eltern]], edges: [[a, b, art]], tags: [[name, [ids]]],
// truncated}. Vorbild ist static/graph.js im Web: gleiche Filter, gleiche Kraefte, gleiche Trefferflaechen.

/// Antwort von /api/graph, unveraendert (Filter und lokaler Ausschnitt entstehen erst in `GraphData.build`)
struct GraphRaw {
    struct Node { let id: Int; let title: String; let icon: String; let kind: String; let own: Bool; let parent: Int? }
    struct Edge { let a: Int; let b: Int; let kind: Character }
    var nodes: [Node] = []
    var edges: [Edge] = []
    var tags: [(name: String, ids: [Int])] = []
    var truncated = false

    /// nil bei unlesbarer Antwort. Einzelne kaputte Eintraege werden uebersprungen, nicht die ganze Antwort verworfen.
    static func parse(_ data: Data) -> GraphRaw? {
        guard let j = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any], let ns = j["nodes"] as? [[Any]] else { return nil }
        var g = GraphRaw()
        g.truncated = j["truncated"] as? Bool ?? false
        func int(_ v: Any?) -> Int? { (v as? NSNumber).flatMap { CFGetTypeID($0) == CFBooleanGetTypeID() ? nil : $0.intValue } }
        for n in ns {
            guard n.count >= 4, let id = int(n[0]) else { continue }
            g.nodes.append(Node(id: id, title: n[1] as? String ?? "", icon: n[2] as? String ?? "", kind: n[3] as? String ?? "page",
                                own: n.count > 4 ? (int(n[4]) ?? 1) != 0 : true, parent: n.count > 5 ? int(n[5]) : nil))
        }
        for e in j["edges"] as? [[Any]] ?? [] {
            guard e.count >= 3, let a = int(e[0]), let b = int(e[1]), let k = (e[2] as? String)?.first else { continue }
            g.edges.append(Edge(a: a, b: b, kind: k))
        }
        for t in j["tags"] as? [[Any]] ?? [] {
            guard t.count >= 2, let name = t[0] as? String else { continue }
            g.tags.append((name, (t[1] as? [Any] ?? []).compactMap { int($0) }))
        }
        return g
    }
}

/// Schalter im Graph (wie im Web): Kantenarten, Tags als eigene Knoten, Datenbank-Eintraege, Waisen
struct GraphFilter: Equatable, Codable {
    var link = true, child = true, rel = true, tags = false, rows = true, orphans = true
    func allows(_ k: Character) -> Bool { k == "l" ? link : k == "c" ? child : k == "r" ? rel : false }
}

enum GraphKind: Equatable { case page, database, row, tag }

struct GraphNode: Equatable {
    let pageID: Int?                 // nil = Tag-Knoten
    let title: String
    let icon: String
    let kind: GraphKind
    let own: Bool
    var deg = 0
    var key: String { pageID.map(String.init) ?? "tag:" + title }
}

struct GraphEdge: Equatable { let a: Int; let b: Int; let kind: Character }     // a/b = Indizes in GraphData.nodes; t = Tag

/// Angezeigter Graph: gefiltert, mit Tag-Knoten und (lokal) nur der Nachbarschaft der offenen Seite
struct GraphData {
    var nodes: [GraphNode] = []
    var edges: [GraphEdge] = []
    var neighbors: [[Int]] = []       // je Knoten die Nachbarn (ohne Doppelte)
    var missing = false               // lokal: die Seite ist nicht im Graph (archiviert, Vorlage, andere Instanz)

    static func build(_ raw: GraphRaw, filter f: GraphFilter, focus: Int? = nil, depth: Int = 1, untitled: String = "Untitled") -> GraphData {
        var g = GraphData()
        var index: [Int: Int] = [:]
        var kind: [Int: String] = [:]
        for n in raw.nodes { kind[n.id] = n.kind }
        for n in raw.nodes where index[n.id] == nil {
            if !f.rows && n.kind == "row" && focus != n.id { continue }
            index[n.id] = g.nodes.count
            let k: GraphKind = n.kind == "database" ? .database : n.kind == "row" ? .row : .page
            g.nodes.append(GraphNode(pageID: n.id, title: n.title.isEmpty ? untitled : n.title, icon: n.icon, kind: k, own: n.own))
        }
        var seenEdge = Set<Int64>()
        func add(_ a: Int, _ b: Int, _ k: Character) {
            guard a != b else { return }
            let lo = min(a, b), hi = max(a, b), key = Int64(lo) << 32 | Int64(hi)
            guard seenEdge.insert(key).inserted else { return }         // eine Linie je Paar (Verweis + Unterseite = eine)
            g.edges.append(GraphEdge(a: a, b: b, kind: k))
        }
        for e in raw.edges where f.allows(e.kind) {
            if let a = index[e.a], let b = index[e.b] { add(a, b, e.kind) }
        }
        if f.tags {
            for t in raw.tags {
                let ids = t.ids.compactMap { index[$0] }
                guard !ids.isEmpty else { continue }
                let ti = g.nodes.count
                g.nodes.append(GraphNode(pageID: nil, title: "#" + t.name, icon: "", kind: .tag, own: true))
                for i in ids { add(i, ti, "t") }
            }
        }
        if let focus {                                                  // lokal: Breitensuche bis zur Tiefe
            guard let start = index[focus] else { return GraphData(missing: true) }
            let adj = GraphData.adjacency(g.nodes.count, g.edges)
            var dist = [Int](repeating: -1, count: g.nodes.count)
            dist[start] = 0
            var queue = [start], head = 0
            while head < queue.count {
                let x = queue[head]; head += 1
                if dist[x] >= max(1, depth) { continue }
                for y in adj[x] where dist[y] < 0 { dist[y] = dist[x] + 1; queue.append(y) }
            }
            g = g.subset { dist[$0] >= 0 }
        }
        g.neighbors = GraphData.adjacency(g.nodes.count, g.edges)
        for i in g.nodes.indices { g.nodes[i].deg = g.neighbors[i].count }
        if !f.orphans && focus == nil {
            g = g.subset { g.nodes[$0].deg > 0 }
            g.neighbors = GraphData.adjacency(g.nodes.count, g.edges)
        }
        return g
    }

    static func adjacency(_ n: Int, _ edges: [GraphEdge]) -> [[Int]] {
        var adj = [[Int]](repeating: [], count: n)
        for e in edges { adj[e.a].append(e.b); adj[e.b].append(e.a) }
        return adj
    }

    /// Teilgraph: nur Knoten, fuer die `keep` gilt; Kanten mit neuen Indizes
    func subset(_ keep: (Int) -> Bool) -> GraphData {
        var map = [Int](repeating: -1, count: nodes.count)
        var out = GraphData()
        for i in nodes.indices where keep(i) { map[i] = out.nodes.count; out.nodes.append(nodes[i]) }
        for e in edges where map[e.a] >= 0 && map[e.b] >= 0 { out.edges.append(GraphEdge(a: map[e.a], b: map[e.b], kind: e.kind)) }
        out.neighbors = GraphData.adjacency(out.nodes.count, out.edges)
        return out
    }

    func index(of pageID: Int) -> Int? { nodes.firstIndex { $0.pageID == pageID } }

    /// Suche: Treffer im Titel (ohne Gross/Klein und Akzente). Leere Suche = nil (nichts hervorheben)
    func matches(_ query: String) -> [Int]? {
        let q = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !q.isEmpty else { return nil }
        return nodes.indices.filter { nodes[$0].title.range(of: q, options: [.caseInsensitive, .diacriticInsensitive]) != nil }
    }
}

// MARK: - Simulation

/// Kraefte-Simulation wie im Web: Abstossung (ab `bhMin` Knoten Barnes-Hut), Federn an Kanten, Zug zur Mitte.
/// `alpha` kuehlt je Schritt ab; unter `alphaMin` steht alles still (kein Dauerlauf).
final class GraphSim {
    struct Params: Equatable { var center = 0.5, repel = 0.6, spring = 0.5, dist = 0.5 }
    static let alphaMin = 0.002
    static let bhMin = 300
    static var testDirect = false                   // nur Tests: Barnes-Hut mit direkter Rechnung vergleichen

    private(set) var x: [Double], y: [Double]
    private var vx: [Double], vy: [Double]
    private(set) var ea: [Int32], eb: [Int32]
    private var deg: [Double]
    var alpha = 1.0
    var params = Params()
    var pinned: Int?                                // gezogener Knoten: steht, die Nachbarn folgen
    private(set) var ticks = 0
    var count: Int { x.count }
    var running: Bool { alpha > GraphSim.alphaMin }

    /// `old`: Positionen nach Schluessel (Filter geaendert -> bekannte Knoten bleiben, wo sie sind)
    init(_ g: GraphData, old: [String: (Double, Double)] = [:], focus: Int? = nil) {
        let n = g.nodes.count
        x = .init(repeating: 0, count: n); y = x; vx = x; vy = x
        let r = (Double(n) + 1).squareRoot() * 18
        for i in 0..<n {
            if let p = old[g.nodes[i].key] { x[i] = p.0; y[i] = p.1; continue }
            if let focus, i == focus { continue }
            let a = Double(i) * 2.399963, rr = r * ((Double(i) + 0.5) / Double(n)).squareRoot()   // Sonnenblume: gleichmaessig
            x[i] = cos(a) * rr; y[i] = sin(a) * rr
        }
        ea = g.edges.map { Int32($0.a) }; eb = g.edges.map { Int32($0.b) }
        deg = g.nodes.map { Double(max(1, $0.deg)) }
    }

    func position(_ i: Int) -> (Double, Double) { (x[i], y[i]) }
    func move(_ i: Int, to p: (Double, Double)) { x[i] = p.0; y[i] = p.1; vx[i] = 0; vy[i] = 0 }
    func heat(_ a: Double) { alpha = max(alpha, a) }

    /// Vorlauf ohne Bild (hoechstens `budget` Sekunden), damit das erste Bild schon nach Graph aussieht
    func prerun(budget: TimeInterval) {
        let t0 = Date()
        while running && alpha > 0.05 && Date().timeIntervalSince(t0) < budget { tick() }
    }
    /// Reduzierte Bewegung: gleich fertig rechnen
    func settle(maxTicks: Int = 600) {
        var i = 0
        while running && i < maxTicks { tick(); i += 1 }
        alpha = 0
    }

    func tick() {
        let n = x.count
        guard n > 0 else { return }
        let a = alpha, p = params
        let f0 = (30 + p.repel * 600) * a, spring = 0.02 + p.spring * 0.25, len = 20 + p.dist * 120, cen = 0.002 + p.center * 0.03
        x.withUnsafeMutableBufferPointer { X in y.withUnsafeMutableBufferPointer { Y in
        vx.withUnsafeMutableBufferPointer { VX in vy.withUnsafeMutableBufferPointer { VY in
            if n >= GraphSim.bhMin && !GraphSim.testDirect {
                var tree = QuadTree(); tree.build(X, Y)
                for i in 0..<n { tree.repel(i, X, Y, f0, &VX[i], &VY[i]) }
            } else {
                for i in 0..<n {
                    var fx = 0.0, fy = 0.0
                    for j in 0..<n where j != i {
                        let dx = X[j] - X[i], dy = Y[j] - Y[i], d2 = dx * dx + dy * dy + 0.01
                        if d2 > 1e6 { continue }
                        let f = f0 / d2; fx -= dx * f; fy -= dy * f
                    }
                    VX[i] += fx; VY[i] += fy
                }
            }
            deg.withUnsafeBufferPointer { D in ea.withUnsafeBufferPointer { A in eb.withUnsafeBufferPointer { B in
                for k in 0..<A.count {
                    let s = Int(A[k]), t = Int(B[k])
                    let dx = X[t] - X[s], dy = Y[t] - Y[s], d = (dx * dx + dy * dy).squareRoot()
                    let dd = d > 0 ? d : 1
                    let f = (dd - len) / dd * spring * a, bs = D[s] / (D[s] + D[t])
                    VX[t] -= dx * f * bs; VY[t] -= dy * f * bs; VX[s] += dx * f * (1 - bs); VY[s] += dy * f * (1 - bs)
                }
            } } }
            let pin = pinned ?? -1
            for i in 0..<n {
                VX[i] -= X[i] * cen * a; VY[i] -= Y[i] * cen * a
                if i == pin { VX[i] = 0; VY[i] = 0; continue }
                VX[i] = min(50, max(-50, VX[i] * 0.6)); VY[i] = min(50, max(-50, VY[i] * 0.6))
                X[i] += VX[i]; Y[i] += VY[i]
            }
        } } } }
        alpha *= 0.985
        ticks += 1
    }

    /// Groesste Geschwindigkeit des letzten Schritts (Test: steht still)
    var maxSpeed: Double { zip(vx, vy).map { ($0 * $0 + $1 * $1).squareRoot() }.max() ?? 0 }

    func bounds() -> (x0: Double, y0: Double, x1: Double, y1: Double)? {
        guard let x0 = x.min(), let x1 = x.max(), let y0 = y.min(), let y1 = y.max() else { return nil }
        return (x0, y0, x1, y1)
    }
}

/// Barnes-Hut-Quadtree als flaches Feld (keine Objekte je Zelle - schnell auch bei 5000 Knoten)
struct QuadTree {
    struct Cell { var x: Double; var y: Double; var s: Double; var m: Double = 0; var cx = 0.0; var cy = 0.0; var child: Int32 = -1; var body: Int32 = -1 }
    private(set) var cells: [Cell] = []
    private var stack: [Int32] = []

    mutating func build(_ X: UnsafeMutableBufferPointer<Double>, _ Y: UnsafeMutableBufferPointer<Double>) {
        var x0 = Double.infinity, y0 = Double.infinity, x1 = -Double.infinity, y1 = -Double.infinity
        for i in 0..<X.count { x0 = min(x0, X[i]); x1 = max(x1, X[i]); y0 = min(y0, Y[i]); y1 = max(y1, Y[i]) }
        cells.removeAll(keepingCapacity: true); cells.reserveCapacity(X.count * 3)
        cells.append(Cell(x: x0, y: y0, s: max(x1 - x0, y1 - y0) + 1))
        for i in 0..<X.count { insert(Int32(i), X[i], Y[i]) }
    }

    private func quadrant(_ c: Cell, _ px: Double, _ py: Double) -> Int32 {
        let h = c.s / 2
        return (px >= c.x + h ? 1 : 0) + (py >= c.y + h ? 2 : 0)
    }
    private mutating func split(_ ci: Int) {
        let c = cells[ci], h = c.s / 2, first = Int32(cells.count)
        for q in 0..<4 { cells.append(Cell(x: c.x + (q & 1 != 0 ? h : 0), y: c.y + (q & 2 != 0 ? h : 0), s: h)) }
        let ob = c.body, q = Int(first + quadrant(c, c.cx, c.cy))
        cells[q].body = ob; cells[q].m = c.m; cells[q].cx = c.cx; cells[q].cy = c.cy
        cells[ci].child = first; cells[ci].body = -1
    }
    private mutating func insert(_ b: Int32, _ px: Double, _ py: Double) {
        var ci = 0, depth = 0
        while true {
            let c = cells[ci]
            if c.child < 0 && c.body < 0 && c.m == 0 {                  // leer: Koerper hier ablegen
                cells[ci].body = b; cells[ci].m = 1; cells[ci].cx = px; cells[ci].cy = py; return
            }
            if c.child < 0 {
                if depth > 40 {                                           // gleiche Stelle: nur Masse zaehlen
                    let m = c.m; cells[ci].cx = (c.cx * m + px) / (m + 1); cells[ci].cy = (c.cy * m + py) / (m + 1); cells[ci].m = m + 1; return
                }
                split(ci)
            }
            let m = cells[ci].m
            cells[ci].cx = (cells[ci].cx * m + px) / (m + 1); cells[ci].cy = (cells[ci].cy * m + py) / (m + 1); cells[ci].m = m + 1
            ci = Int(cells[ci].child + quadrant(cells[ci], px, py)); depth += 1
        }
    }

    /// Abstossung auf Knoten i (theta 0.9 wie im Web)
    mutating func repel(_ i: Int, _ X: UnsafeMutableBufferPointer<Double>, _ Y: UnsafeMutableBufferPointer<Double>, _ f0: Double,
                        _ vx: inout Double, _ vy: inout Double) {
        let px = X[i], py = Y[i], me = Int32(i)
        var st: [Int32] = []; swap(&st, &stack)
        st.removeAll(keepingCapacity: true); st.append(0)
        defer { swap(&st, &stack) }
        cells.withUnsafeBufferPointer { C in
            while let ci = st.popLast() {
                let c = C[Int(ci)]
                if c.m == 0 { continue }
                if c.child < 0 && c.body == me { continue }
                let dx = c.cx - px, dy = c.cy - py, d2 = dx * dx + dy * dy + 0.01
                if c.child < 0 || (c.s * c.s) / d2 < 0.81 {
                    if d2 > 1e6 { continue }
                    let f = f0 * c.m / d2; vx -= dx * f; vy -= dy * f
                    continue
                }
                for q in 0..<4 { st.append(c.child + Int32(q)) }
            }
        }
    }
}

// MARK: - Kamera, Groessen, Treffer

struct GraphCamera: Equatable {
    var k = 1.0, tx = 0.0, ty = 0.0
    static let minK = 0.04, maxK = 8.0
    func toWorld(_ sx: Double, _ sy: Double) -> (Double, Double) { ((sx - tx) / k, (sy - ty) / k) }
    func toScreen(_ wx: Double, _ wy: Double) -> (Double, Double) { (wx * k + tx, wy * k + ty) }
    /// Zoomen um einen Bildschirmpunkt (der Punkt bleibt, wo er ist)
    mutating func zoom(at sx: Double, _ sy: Double, by f: Double) {
        let nk = min(GraphCamera.maxK, max(GraphCamera.minK, k * f)), g = nk / k
        tx = sx - (sx - tx) * g; ty = sy - (sy - ty) * g; k = nk
    }
    /// Alles einpassen (seitlich Platz fuer Beschriftungen), hoechstens Zoom 2
    static func fit(_ b: (x0: Double, y0: Double, x1: Double, y1: Double), w: Double, h: Double) -> GraphCamera {
        let pad = min(90, min(w, h) * 0.08), padX = pad + min(50, w * 0.05)
        let k = max(minK, min((w - 2 * padX) / (b.x1 - b.x0 + 1), (h - 2 * pad) / (b.y1 - b.y0 + 1), 2))
        return GraphCamera(k: k, tx: w / 2 - (b.x0 + b.x1) / 2 * k, ty: h / 2 - (b.y0 + b.y1) / 2 * k)
    }
    func centered(on wx: Double, _ wy: Double, w: Double, h: Double, k nk: Double? = nil) -> GraphCamera {
        let kk = nk ?? k
        return GraphCamera(k: kk, tx: w / 2 - wx * kk, ty: h / 2 - wy * kk)
    }
    /// Weicher Uebergang: Zoom logarithmisch, Bildmitte linear (wie im Web)
    static func mix(_ a: GraphCamera, _ b: GraphCamera, _ t: Double, w: Double, h: Double) -> GraphCamera {
        let k = exp(log(a.k) + (log(b.k) - log(a.k)) * t)
        let cx0 = (w / 2 - a.tx) / a.k, cy0 = (h / 2 - a.ty) / a.k, cx1 = (w / 2 - b.tx) / b.k, cy1 = (h / 2 - b.ty) / b.k
        let cx = cx0 + (cx1 - cx0) * t, cy = cy0 + (cy1 - cy0) * t
        return GraphCamera(k: k, tx: w / 2 - cx * k, ty: h / 2 - cy * k)
    }
}

enum GraphGeo {
    static let hitMouse = 10.0, hitTouch = 22.0         // Mindest-Trefferflaeche (pt um den Mittelpunkt)

    /// Radius in Weltkoordinaten: nach Verbindungen, mindestens 2,5 pt auf dem Bildschirm
    static func radius(deg: Int, tag: Bool, k: Double, size: Double = 1) -> Double {
        max((2.5 + Double(deg).squareRoot() * 1.6) * size * (tag ? 0.85 : 1), 2.5 / k)
    }
    /// Gezeichneter Radius mit Wachsen beim Hervorheben (g 0...1): +35 %, mindestens 5 pt auf dem Bildschirm
    static func drawRadius(_ r: Double, grow g: Double, k: Double) -> Double {
        let e = ease(g)
        return e > 0 ? max(r * (1 + 0.35 * e), (2.5 + 2.5 * e) / k) : r
    }
    static func ease(_ t: Double) -> Double { let x = min(1, max(0, t)); return x * x * (3 - 2 * x) }

    /// Knoten unter einem Bildschirmpunkt. Trefferflaeche = groesser von (Radius + 4 pt) und `slop` (Maus 10, Finger 22),
    /// bei Ueberlappung gewinnt der, dessen Rand am naechsten ist.
    static func hit(sx: Double, sy: Double, cam: GraphCamera, xs: [Double], ys: [Double], radii: [Double], slop: Double) -> Int? {
        let (wx, wy) = cam.toWorld(sx, sy)
        var best: Int?, bd = Double.infinity
        for i in xs.indices {
            let dx = xs[i] - wx, dy = ys[i] - wy, d = (dx * dx + dy * dy).squareRoot() * cam.k
            let rs = radii[i] * cam.k, lim = max(rs + 4, slop)
            if d < lim && d - rs < bd { bd = d - rs; best = i }
        }
        return best
    }

    /// Beschriftung: blendet je Zoom weich ein (wie Obsidian); wichtige Knoten frueher. Wenige Knoten: immer.
    static func labelAlpha(k: Double, deg: Int, few: Bool, threshold: Double = 1.1) -> Double {
        if few { return 1 }
        let thr = threshold * 1.6, z = k * (1 + Double(deg).squareRoot() * 0.3)
        return max(0, min(1, (z - thr * 0.72) / (thr * 0.28)))
    }
}

/// Hervorheben weich nachfuehren (~180 ms): e = gehoert zur Hervorhebung, g = waechst (der Knoten selbst), dim = Rest blass
struct GraphHighlight {
    static let duration = 0.18
    var e: [Double] = [], g: [Double] = [], dim = 0.0
    init(count: Int) { e = .init(repeating: 0, count: count); g = e }

    /// true = noch in Bewegung (weiter zeichnen)
    mutating func step(dt: Double, focus: Int?, neighbors: [[Int]], matches: Set<Int>?, instant: Bool) -> Bool {
        let s = instant ? 1 : min(1, dt / GraphHighlight.duration)
        var busy = false
        func to(_ cur: Double, _ tgt: Double) -> Double {
            if cur == tgt { return cur }
            let d = tgt - cur
            if abs(d) <= s { return tgt }
            busy = true; return cur + (d > 0 ? s : -s)
        }
        dim = to(dim, focus != nil || matches != nil ? 1 : 0)
        var hot = Set<Int>()
        if let f = focus, f < e.count { hot.insert(f); hot.formUnion(neighbors[f]) }
        for i in e.indices {
            let te: Double = focus != nil ? (hot.contains(i) ? 1 : 0) : (matches?.contains(i) == true ? 1 : 0)
            e[i] = to(e[i], te); g[i] = to(g[i], i == focus ? 1 : 0)
        }
        return busy
    }
}

// MARK: - Beispielgraph (nur Bilder und Messungen, neutrale Titel)

enum GraphDemo {
    /// Erfundener Graph mit `n` Seiten: Themen-Inseln mit Unterseiten, Verweise, eine Datenbank mit Zeilen und Relationen
    static func raw(nodes n: Int, seed: UInt64 = 7) -> GraphRaw {
        var rng = seed
        func rnd(_ m: Int) -> Int { rng = rng &* 6364136223846793005 &+ 1442695040888963407; return Int((rng >> 33) % UInt64(max(1, m))) }
        let topics = ["Garden", "Recipes", "Travel", "Reading list", "Project Atlas", "Home", "Fitness", "Ideas", "Meetings", "Music", "Learning", "Finance"]
        let words = ["Notes", "Plan", "Overview", "Draft", "Checklist", "Summary", "Archive", "Week", "Goals", "Questions", "Sources", "Log"]
        var g = GraphRaw()
        let hubs = min(topics.count, max(3, n / 8))
        for i in 0..<min(hubs, n) { g.nodes.append(.init(id: i + 1, title: topics[i], icon: "", kind: "page", own: true, parent: nil)) }
        let dbID = hubs + 1
        if n > hubs { g.nodes.append(.init(id: dbID, title: "Tasks", icon: "", kind: "database", own: true, parent: nil)) }
        var id = dbID
        while g.nodes.count < n {
            id += 1
            let hub = rnd(hubs) + 1, r = rnd(10)
            if r == 0 {                                                   // Zeile der Datenbank
                g.nodes.append(.init(id: id, title: "Task \(id)", icon: "", kind: "row", own: true, parent: dbID))
                g.edges.append(.init(a: dbID, b: id, kind: "c"))
                if rnd(2) == 0 { g.edges.append(.init(a: id, b: hub, kind: "r")) }
            } else {
                let own = rnd(7) != 0
                g.nodes.append(.init(id: id, title: "\(topics[hub - 1]) – \(words[rnd(words.count)]) \(id)", icon: "", kind: "page", own: own, parent: hub))
                g.edges.append(.init(a: hub, b: id, kind: "c"))
                if rnd(3) == 0, id > dbID + 2 { g.edges.append(.init(a: id, b: dbID + 1 + rnd(id - dbID - 1), kind: "l")) }
            }
        }
        if n > 4 { g.edges.append(.init(a: 1, b: 2, kind: "l")); g.edges.append(.init(a: 2, b: 3, kind: "l")) }
        if n > 20 { g.nodes.append(.init(id: id + 1, title: "Loose note", icon: "", kind: "page", own: true, parent: nil)) }   // Waise
        g.tags = [("idea", Array(stride(from: dbID + 1, to: id, by: 9))), ("todo", Array(stride(from: dbID + 3, to: id, by: 13)))]
        return g
    }
}
