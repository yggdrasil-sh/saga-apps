import SwiftUI
#if os(macOS)
import AppKit
#endif

// Graph der Seiten (2.5, wie Obsidian): "Alle Seiten" (Seitenleiste) und "Lokal" (Seitenmenue "Lokaler Graph").
// Logik (Filter, Simulation, Treffer, Kamera) in GraphLogic.swift; hier nur Zeichnen (Canvas) und Bedienung.
// Die Simulation laeuft nur, solange sie abkuehlt oder etwas animiert - danach steht die Bildschleife (Akku).
// Texte: eigener Katalog Graph.xcstrings (Tabelle "Graph").

func GT(_ k: String.LocalizationValue) -> String { String(localized: k, table: "Graph") }

enum GraphMode: Hashable { case all, local }

@MainActor
final class GraphModel: ObservableObject {
    enum Phase: Equatable { case loading, ready, error(String), offline, unsupported }
    @Published var phase: Phase = .loading
    @Published var stale = false                       // Anzeige aus dem Zwischenspeicher (offline)
    @Published var truncated = false
    @Published var mode: GraphMode = .all
    @Published var depth = 1
    @Published var filter = GraphModel.loadFilter() { didSet { if filter != oldValue { saveFilter(); rebuild(keep: true) } } }
    @Published var query = "" { didSet { if query != oldValue { applySearch() } } }
    @Published var selected: Int?                      // Antippen/Klick: Karte mit "Oeffnen"
    @Published private(set) var running = false        // Bildschleife aktiv
    @Published private(set) var revision = 0           // neuer Graph (Liste, Zaehler)
    @Published private(set) var noMatch = false

    var focusID: Int?
    var raw: GraphRaw?
    private(set) var data = GraphData()
    private(set) var sim = GraphSim(GraphData())
    private var hl = GraphHighlight(count: 0)
    var cam = GraphCamera()
    var size = CGSize.zero
    private(set) var hover: Int?
    var matches: Set<Int>?
    var mouse: CGPoint?
    var reduceMotion = false
    var touch = true
    private var camAnim: (from: GraphCamera, to: GraphCamera, p: Double)?
    private var autoFit = false
    private var lastFrame: Date?
    private var radii: [Double] = []
    private var dragState: (node: Int?, start: CGPoint, cam: GraphCamera, moved: Bool)?
    private var pinchStart: GraphCamera?
    /// Messung (Debug): Dauer der letzten Bilder in ms
    var frameMS: [Double] = []

    // MARK: Laden
    func load(_ store: Store?) async {
        if raw == nil { phase = .loading }
        #if DEBUG
        if let n = GraphPreview.nodes { finish(GraphDemo.raw(nodes: n)); return }
        #endif
        guard let store else { return }
        do {
            let d = try await store.get("api/graph")
            guard let r = GraphRaw.parse(d) else { phase = .error(GT("Unerwartete Antwort des Servers.")); return }
            stale = false; finish(r)
        } catch APIError.http(404, _) {
            phase = .unsupported
        } catch is CancellationError {
        } catch {
            let off = error is URLError || (error as? APIError).map { if case .other = $0 { return true }; return false } ?? false
            if off, raw == nil, let c = store.cachedData("api/graph"), let r = GraphRaw.parse(c) { stale = true; finish(r); return }
            if raw != nil { stale = off; return }                      // alter Stand bleibt sichtbar
            phase = off ? .offline : .error(error.localizedDescription)
        }
    }
    private func finish(_ r: GraphRaw) {
        raw = r; truncated = r.truncated; phase = .ready
        rebuild(keep: false)
    }

    // MARK: Aufbau
    func setMode(_ m: GraphMode) { mode = m == .local && focusID != nil ? .local : .all; selected = nil; rebuild(keep: false) }
    func setDepth(_ d: Int) { depth = d; rebuild(keep: true); autoFit = true; sim.heat(0.6); wake() }
    func resetFilter() { filter = GraphFilter() }
    func relayout() { rebuild(keep: false) }

    func rebuild(keep: Bool) {
        guard let raw else { return }
        let local = mode == .local && focusID != nil
        let g = GraphData.build(raw, filter: filter, focus: local ? focusID : nil, depth: depth, untitled: String(localized: "Unbenannt"))
        var old: [String: (Double, Double)] = [:]
        if keep { for i in data.nodes.indices where i < sim.count { old[data.nodes[i].key] = sim.position(i) } }
        let selKey = selected.flatMap { $0 < data.nodes.count ? data.nodes[$0].key : nil }
        let focusIdx = local ? g.index(of: focusID!) : nil
        data = g
        sim = GraphSim(g, old: old, focus: focusIdx)
        hl = GraphHighlight(count: g.nodes.count)
        hover = nil
        selected = selKey.flatMap { k in g.nodes.firstIndex { $0.key == k } }
        updateRadii()
        if keep {
            sim.alpha = 0.5
        } else {
            sim.prerun(budget: 0.06)                                    // erstes Bild sieht schon nach Graph aus
            autoFit = true
        }
        if reduceMotion { sim.settle() }
        if !keep || reduceMotion { fit(instant: true) }
        applySearch(center: false)
        revision += 1
        wake()
    }

    private func updateRadii() { radii = data.nodes.map { GraphGeo.radius(deg: $0.deg, tag: $0.kind == .tag, k: cam.k) } }

    // MARK: Suche
    private func applySearch(center: Bool = true) {
        guard let m = data.matches(query) else { matches = nil; noMatch = false; wake(); return }
        matches = Set(m); noMatch = m.isEmpty
        if center, let first = m.first {
            let p = sim.position(first)
            camTo(cam.centered(on: p.0, p.1, w: size.width, h: size.height, k: max(cam.k, 1)))
        }
        wake()
    }
    /// Eingabetaste in der Suche: ersten Treffer oeffnen
    var firstMatch: Int? { data.matches(query)?.first }

    // MARK: Kamera
    func fit(instant: Bool = false) {
        guard let b = sim.bounds(), size.width > 0 else { return }
        let t = GraphCamera.fit(b, w: size.width, h: size.height)
        if instant { camAnim = nil; cam = t; updateRadii() } else { camTo(t) }
        wake()
    }
    private func camTo(_ t: GraphCamera) {
        autoFit = false
        if reduceMotion || size.width == 0 { cam = t; camAnim = nil; updateRadii() } else { camAnim = (cam, t, 0) }
        wake()
    }
    func resize(_ s: CGSize) {
        guard s != size, s.width > 0 else { return }
        let first = size.width == 0
        if !first { cam.tx += (s.width - size.width) / 2; cam.ty += (s.height - size.height) / 2 }
        size = s
        if first || !touched { fit(instant: true) }                    // noch nicht bewegt: Groesse geaendert -> neu einpassen
        wake()
    }
    func zoom(by f: Double, at p: CGPoint? = nil) {
        user(); let c = p ?? CGPoint(x: size.width / 2, y: size.height / 2)
        cam.zoom(at: c.x, c.y, by: f); updateRadii(); wake()
    }
    func pan(dx: Double, dy: Double) { user(); cam.tx += dx; cam.ty += dy; wake() }
    private func user() { autoFit = false; camAnim = nil; touched = true }
    private var touched = false

    // MARK: Treffer, Hervorheben
    func node(at p: CGPoint, slop: Double) -> Int? {
        GraphGeo.hit(sx: p.x, sy: p.y, cam: cam, xs: sim.x, ys: sim.y, radii: drawRadii(), slop: slop)
    }
    private func drawRadii() -> [Double] {
        radii.indices.map { GraphGeo.drawRadius(radii[$0], grow: $0 < hl.g.count ? hl.g[$0] : 0, k: cam.k) }
    }
    func setHover(_ i: Int?) { if i != hover { hover = i; wake() } }
    func hoverAt(_ p: CGPoint?) {
        mouse = p
        guard dragState == nil else { return }
        setHover(p.flatMap { node(at: $0, slop: GraphGeo.hitMouse) })
    }
    var emphasis: Int? { hover ?? selected }

    /// Antippen (Touch): erst Karte, zweites Antippen oeffnet. Klick (Maus): waehlen; Doppelklick oeffnet (Ansicht).
    func tap(at p: CGPoint) -> Int? {
        let i = node(at: p, slop: touch ? GraphGeo.hitTouch : GraphGeo.hitMouse)
        if touch, let i, i == selected { return i }
        selected = i
        if touch, let i { zoomTo(i) }
        wake(); return nil
    }
    /// Touch: Knoten und Nachbarn einpassen - nur hineinzoomen, nie heraus
    private func zoomTo(_ i: Int) {
        var x0 = sim.x[i], x1 = x0, y0 = sim.y[i], y1 = y0
        for j in data.neighbors[i] { x0 = min(x0, sim.x[j]); x1 = max(x1, sim.x[j]); y0 = min(y0, sim.y[j]); y1 = max(y1, sim.y[j]) }
        let k = min((size.width - 140) / (x1 - x0 + 1), (size.height - 260) / (y1 - y0 + 1), 1.6)
        guard k > cam.k * 1.1 else { return }
        camTo(GraphCamera(k: k, tx: size.width / 2 - (x0 + x1) / 2 * k, ty: (size.height - 90) / 2 - (y0 + y1) / 2 * k))
    }

    // MARK: Ziehen und Zoomen (Gesten)
    func dragChanged(start: CGPoint, at p: CGPoint) {
        if dragState == nil {
            let n = node(at: start, slop: touch ? GraphGeo.hitTouch : GraphGeo.hitMouse)
            dragState = (n, start, cam, false)
            if let n { sim.pinned = n; if !touch { setHover(n) } }
        }
        guard var d = dragState else { return }
        d.moved = true; dragState = d; user()
        if let n = d.node {
            let w = cam.toWorld(p.x, p.y)
            sim.move(n, to: w); sim.heat(0.3)
        } else {
            cam.tx = d.cam.tx + p.x - d.start.x; cam.ty = d.cam.ty + p.y - d.start.y
        }
        wake()
    }
    func dragEnded() {
        let wasNode = dragState?.node != nil
        dragState = nil; sim.pinned = nil
        if wasNode && reduceMotion { sim.settle() }
        if let m = mouse { hoverAt(m) }
        wake()
    }
    func pinch(_ f: Double, at p: CGPoint) {
        if pinchStart == nil { pinchStart = cam; user() }
        cam = pinchStart!; cam.zoom(at: p.x, p.y, by: f); updateRadii(); wake()
    }
    func pinchEnded() { pinchStart = nil }

    // MARK: Bildschleife
    func wake() { lastFrame = nil; if !running { running = true } }

    /// Ein Bild weiter: Simulation (2 Schritte), Kamera, Hervorheben. Stoppt die Schleife, wenn nichts mehr laeuft.
    func step(_ now: Date) {
        let dt = lastFrame.map { min(0.064, max(0, now.timeIntervalSince($0))) } ?? 0.016
        lastFrame = now
        let dragNode = dragState?.node != nil && dragState?.moved == true
        if dragNode && sim.alpha < 0.3 { sim.alpha = 0.3 }
        let simOn = sim.running && !(reduceMotion && !dragNode)
        if simOn { sim.tick(); sim.tick() }
        var busy = simOn
        if var a = camAnim {
            a.p = min(1, a.p + dt / 0.26)
            cam = GraphCamera.mix(a.from, a.to, GraphGeo.ease(a.p), w: size.width, h: size.height)
            camAnim = a.p >= 1 ? nil : a; busy = true; updateRadii()
        } else if autoFit && simOn, let b = sim.bounds() {
            let t = GraphCamera.fit(b, w: size.width, h: size.height)
            if sim.alpha > 0.03 {
                let f = min(1, dt / 0.12) * 0.5
                cam.k += (t.k - cam.k) * f; cam.tx += (t.tx - cam.tx) * f; cam.ty += (t.ty - cam.ty) * f; updateRadii()
            } else { autoFit = false; camTo(t) }
        }
        if let m = mouse, dragState == nil, simOn || camAnim != nil { hover = node(at: m, slop: GraphGeo.hitMouse) }   // Inhalt bewegt sich unter dem Zeiger
        if hl.step(dt: dt, focus: emphasis, neighbors: data.neighbors, matches: matches, instant: reduceMotion) { busy = true }
        if !busy { DispatchQueue.main.async { [weak self] in if let s = self, s.lastFrame == now { s.running = false } } }
    }

    // MARK: Zeichnen
    struct Palette {
        var page, db, row, tag, line, accent, fg, bg: Color
        var lineA: Double
        static func make(dark: Bool) -> Palette {
            func c(_ h: UInt32) -> Color { Color(red: Double(h >> 16 & 255) / 255, green: Double(h >> 8 & 255) / 255, blue: Double(h & 255) / 255) }
            return dark
                ? Palette(page: c(0x9aa6b8), db: c(0xb39ddb), row: c(0x7fb0e0), tag: c(0x6cc48a), line: c(0x8b97a8), accent: .accentColor, fg: .white, bg: .black, lineA: 0.32)
                : Palette(page: c(0x6a7383), db: c(0x7b5cc0), row: c(0x3d72ad), tag: c(0x2e8049), line: c(0x5d6878), accent: .accentColor, fg: .black, bg: .white, lineA: 0.3)
        }
        func color(_ k: GraphKind) -> Color { k == .database ? db : k == .row ? row : k == .tag ? tag : page }
    }

    func draw(_ ctx: inout GraphicsContext, palette P: Palette) {
        let t0 = Date()
        let n = data.nodes.count, k = cam.k
        guard n > 0, n == sim.count else { return }
        let X = sim.x, Y = sim.y, w = size.width, h = size.height
        let D = GraphGeo.ease(hl.dim)
        func scr(_ i: Int) -> CGPoint { CGPoint(x: X[i] * k + cam.tx, y: Y[i] * k + cam.ty) }
        // Kanten: Grundschicht je Art als ein Pfad, hervorgehobene einzeln obendrauf
        let lw = max(0.5, 0.9) * min(1.6, max(0.6, k.squareRoot()))
        let base = P.lineA * (1 - 0.8 * D)
        var solid = Path(), dashed = Path(), tagged = Path(), hi: [(Int, Double)] = []
        for (ei, e) in data.edges.enumerated() {
            let a = scr(e.a), b = scr(e.b)
            if (a.x < 0 && b.x < 0) || (a.x > w && b.x > w) || (a.y < 0 && b.y < 0) || (a.y > h && b.y > h) { continue }
            let ee = emphasisOfEdge(e)
            if ee > 0 { hi.append((ei, ee)); continue }
            switch e.kind {
            case "r": dashed.move(to: a); dashed.addLine(to: b)
            case "t": tagged.move(to: a); tagged.addLine(to: b)
            default: solid.move(to: a); solid.addLine(to: b)
            }
        }
        ctx.stroke(solid, with: .color(P.line.opacity(base)), style: StrokeStyle(lineWidth: lw, lineCap: .round))
        ctx.stroke(dashed, with: .color(P.line.opacity(base)), style: StrokeStyle(lineWidth: lw, lineCap: .round, dash: [3, 3]))
        ctx.stroke(tagged, with: .color(P.line.opacity(base * 0.6)), style: StrokeStyle(lineWidth: lw * 0.8, lineCap: .round, dash: [1, 2.5]))
        for (ei, ee) in hi {
            let e = data.edges[ei], a = GraphGeo.ease(ee)
            var p = Path(); p.move(to: scr(e.a)); p.addLine(to: scr(e.b))
            ctx.stroke(p, with: .color(P.accent.opacity(0.9 * a)), style: StrokeStyle(lineWidth: lw * (1 + 0.8 * a), lineCap: .round, dash: e.kind == "r" ? [3, 3] : []))
        }
        // Knoten: unbeteiligte je Art gebuendelt, hervorgehobene einzeln
        let dimA = 1 - 0.85 * D
        var fills: [GraphKind: Path] = [:], shared: [GraphKind: Path] = [:], hiN: [Int] = []
        var labels: [(Int, Double)] = []
        let few = n <= 40
        let focusIdx = mode == .local ? focusID.flatMap { data.index(of: $0) } : nil
        for i in 0..<n {
            let p = scr(i), r = radii[i] * k
            if p.x + r < -40 || p.x - r > w + 40 || p.y + r < -40 || p.y - r > h + 40 { continue }
            let nd = data.nodes[i]
            if hl.e[i] > 0 || hl.g[i] > 0 || i == focusIdx { hiN.append(i) }
            else {
                let rect = CGRect(x: p.x - r, y: p.y - r, width: 2 * r, height: 2 * r)
                if nd.own { fills[nd.kind, default: Path()].addEllipse(in: rect) } else { shared[nd.kind, default: Path()].addEllipse(in: rect.insetBy(dx: 0.6, dy: 0.6)) }
            }
            let za = GraphGeo.labelAlpha(k: k, deg: nd.deg, few: few)
            var la = max(za * dimA, GraphGeo.ease(hl.e[i]))
            if let m = matches, !m.contains(i) { la = min(la, za * dimA) }
            if la > 0.03 { labels.append((i, la)) }
        }
        for (kind, p) in fills { ctx.fill(p, with: .color(P.color(kind).opacity(dimA))) }
        for (kind, p) in shared {                                       // freigegeben: Ring mit blasser Fuellung
            ctx.fill(p, with: .color(P.color(kind).opacity(dimA * 0.3)))
            ctx.stroke(p, with: .color(P.color(kind).opacity(dimA)), lineWidth: 1.2)
        }
        for i in hiN {
            let nd = data.nodes[i], a = GraphGeo.ease(hl.e[i]), g = GraphGeo.ease(hl.g[i]), cur = i == focusIdx
            let r = GraphGeo.drawRadius(radii[i], grow: hl.g[i], k: k) * k, p = scr(i)
            let rect = CGRect(x: p.x - r, y: p.y - r, width: 2 * r, height: 2 * r)
            let al = dimA + (1 - dimA) * a
            let col = cur ? P.accent : P.color(nd.kind)
            if nd.own { ctx.fill(Path(ellipseIn: rect), with: .color(col.opacity(al))) }
            else { ctx.fill(Path(ellipseIn: rect), with: .color(col.opacity(al * 0.3))); ctx.stroke(Path(ellipseIn: rect.insetBy(dx: 0.6, dy: 0.6)), with: .color(col.opacity(al)), lineWidth: 1.2) }
            if g > 0 && !cur { ctx.fill(Path(ellipseIn: rect), with: .color(P.accent.opacity(g))) }
            if cur { ctx.stroke(Path(ellipseIn: rect.insetBy(dx: -3.5, dy: -3.5)), with: .color(P.accent.opacity(max(dimA, a))), lineWidth: 1.6) }   // "du bist hier"
        }
        // Beschriftungen: feste Groesse, Rand in Hintergrundfarbe; hervorgehobene zuletzt (oben)
        labels.sort { hl.e[$0.0] < hl.e[$1.0] }
        if labels.count > 300 { labels.removeFirst(labels.count - 300) }
        let em = emphasis
        ctx.drawLayer { l in
            l.addFilter(.shadow(color: P.bg.opacity(0.9), radius: 1.2))
            for (i, a) in labels {
                let nd = data.nodes[i], big = i == em
                let r = GraphGeo.drawRadius(radii[i], grow: hl.g[i], k: k) * k, p = scr(i)
                var t = nd.title
                if t.count > 40 && !big { t = String(t.prefix(39)) + "…" }
                if !nd.icon.isEmpty && nd.icon.count <= 4 { t = nd.icon + " " + t }
                l.draw(Text(t).font(.system(size: big ? 13 : 11.5, weight: big ? .semibold : .regular)).foregroundStyle(P.fg.opacity(a * 0.92)),
                       at: CGPoint(x: p.x, y: p.y + r + 3), anchor: .top)
            }
        }
        #if DEBUG
        frameMS.append(Date().timeIntervalSince(t0) * 1000); if frameMS.count > 240 { frameMS.removeFirst(frameMS.count - 240) }
        #endif
    }
    private func emphasisOfEdge(_ e: GraphEdge) -> Double {
        guard let f = emphasis else { return 0 }
        return e.a == f || e.b == f ? min(hl.e[e.a], hl.e[e.b]) : 0
    }

    func describe(_ i: Int) -> String { GT("\(data.neighbors[i].count) Verbindungen") }

    // MARK: Filter speichern
    private static func loadFilter() -> GraphFilter {
        (UserDefaults.standard.data(forKey: "hk.graphFilter")).flatMap { try? JSONDecoder().decode(GraphFilter.self, from: $0) } ?? GraphFilter()
    }
    private func saveFilter() { if let d = try? JSONEncoder().encode(filter) { UserDefaults.standard.set(d, forKey: "hk.graphFilter") } }
}

// MARK: - Ansicht

struct GraphScreen: View {
    var store: Store?
    var focusID: Int?
    var initialMode: GraphMode = .all
    var onOpen: (Int) -> Void
    var onClose: (() -> Void)?
    @StateObject private var model = GraphModel()
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.accessibilityVoiceOverEnabled) private var voiceOver
    @Environment(\.colorScheme) private var scheme
    @State private var showList: Bool?
    @State private var started = false

    private var listMode: Bool { showList ?? voiceOver }

    var body: some View {
        content
            .navigationTitle(Text("Graph", tableName: "Graph"))
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .searchable(text: $model.query, prompt: Text("Seiten im Graph suchen", tableName: "Graph"))
            .onSubmit(of: .search) { if let i = model.firstMatch { open(i) } }
            .toolbar { toolbar }
            .task {
                guard !started else { return }
                started = true
                model.reduceMotion = reduceMotion
                #if os(macOS)
                model.touch = false
                #endif
                model.focusID = focusID
                model.mode = initialMode == .local && focusID != nil ? .local : .all
                #if DEBUG
                if GraphPreview.local { model.mode = .local; model.focusID = GraphPreview.focus }
                if let d = GraphPreview.depth { model.depth = d }
                #endif
                await model.load(store)
                #if DEBUG
                GraphPreview.afterLoad(model)
                #endif
            }
            .onChange(of: reduceMotion) { _, v in model.reduceMotion = v; model.wake() }
    }

    @ViewBuilder private var content: some View {
        switch model.phase {
        case .loading:
            VStack(spacing: 12) { ProgressView(); Text("Graph wird geladen …", tableName: "Graph").foregroundStyle(.secondary) }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        case .unsupported:
            ContentUnavailableView { Label { Text("Braucht Server 2.5", tableName: "Graph") } icon: { Image(systemName: "point.3.connected.trianglepath.dotted") } }
                description: { Text("Der Graph der Seiten ist ab Saga-Server 2.5 verfügbar.", tableName: "Graph") }
        case .offline:
            ContentUnavailableView { Label { Text("Offline", tableName: "Graph") } icon: { Image(systemName: "wifi.slash") } }
                description: { Text("Keine Verbindung zum Server. Der Graph erscheint, sobald die Verbindung wieder da ist.", tableName: "Graph") }
                actions: { Button { Task { await model.load(store) } } label: { Text("Erneut versuchen", tableName: "Graph") } }
        case .error(let m):
            ContentUnavailableView { Label { Text("Der Graph konnte nicht geladen werden.", tableName: "Graph") } icon: { Image(systemName: "exclamationmark.triangle") } }
                description: { Text(m) }
                actions: { Button { Task { await model.load(store) } } label: { Text("Erneut versuchen", tableName: "Graph") } }
        case .ready:
            ready
        }
    }

    @ViewBuilder private var ready: some View {
        VStack(spacing: 0) {
            header
            ZStack {
                if listMode { GraphList(model: model, open: open) } else { GraphCanvas(model: model, palette: .make(dark: scheme == .dark), open: open) }
                if !listMode { overlays }
                message
            }
        }
    }

    private var header: some View {
        HStack(spacing: 10) {
            Picker(selection: Binding(get: { model.mode }, set: { model.setMode($0) })) {
                Text("Alle Seiten", tableName: "Graph").tag(GraphMode.all)
                Text("Lokal", tableName: "Graph").tag(GraphMode.local)
            } label: { Text("Ansicht", tableName: "Graph") }
                .pickerStyle(.segmented).labelsHidden().fixedSize()
                .disabled(model.focusID == nil)
            if model.mode == .local {
                Picker(selection: Binding(get: { model.depth }, set: { model.setDepth($0) })) {
                    Text(verbatim: "1").tag(1); Text(verbatim: "2").tag(2)
                } label: { Text("Tiefe", tableName: "Graph") }
                    .pickerStyle(.segmented).fixedSize()
                    .accessibilityLabel(Text("Tiefe", tableName: "Graph"))
            }
            Spacer(minLength: 0)
            stats.font(.caption).foregroundStyle(.secondary).lineLimit(2).minimumScaleFactor(0.85).multilineTextAlignment(.trailing)
        }
        .padding(.horizontal, 12).padding(.vertical, 8)
    }

    private var stats: some View {
        let _ = model.revision
        var s = GT("\(model.data.nodes.count) Seiten") + " · " + GT("\(model.data.edges.count) Verbindungen")
        if model.truncated && model.mode == .all { s += " · " + GT("gekürzt") }
        return Text(s)
    }

    @ViewBuilder private var overlays: some View {
        VStack {
            HStack(alignment: .top) {
                if model.stale { banner(GT("Offline – letzter bekannter Stand"), "wifi.slash") }
                if model.truncated && model.mode == .all { banner(GT("Sehr viele Seiten – gezeigt werden die zuletzt geänderten."), "scissors") }
                if model.noMatch { banner(GT("Keine Treffer"), "magnifyingglass") }
                Spacer()
            }
            Spacer()
            HStack(alignment: .bottom) {
                legend
                Spacer()
                VStack(spacing: 8) {
                    Button { model.zoom(by: 1.25) } label: { Image(systemName: "plus").frame(width: 20, height: 20) }.accessibilityLabel(Text("Vergrößern", tableName: "Graph")).keyboardShortcut("+", modifiers: .command)
                    Button { model.zoom(by: 0.8) } label: { Image(systemName: "minus").frame(width: 20, height: 20) }.accessibilityLabel(Text("Verkleinern", tableName: "Graph")).keyboardShortcut("-", modifiers: .command)
                    Button { model.fit() } label: { Image(systemName: "scope").frame(width: 20, height: 20) }.accessibilityLabel(Text("Einpassen", tableName: "Graph")).keyboardShortcut("0", modifiers: .command)
                }
                .buttonStyle(.bordered).buttonBorderShape(.circle).controlSize(.regular)
            }
            if let i = model.selected, i < model.data.nodes.count { card(i) }
        }
        .padding(12)
    }

    private func banner(_ t: String, _ icon: String) -> some View {
        Label(t, systemImage: icon).font(.caption).padding(.horizontal, 10).padding(.vertical, 6)
            .background(.regularMaterial, in: Capsule())
    }

    private var legend: some View {
        let _ = model.revision
        let kinds = Set(model.data.nodes.map(\.kind)), shared = model.data.nodes.contains { !$0.own }
        let P = GraphModel.Palette.make(dark: scheme == .dark)
        return VStack(alignment: .leading, spacing: 4) {
            if kinds.contains(.page) { dot(P.page, GT("Seite")) }
            if kinds.contains(.database) { dot(P.db, GT("Datenbank")) }
            if kinds.contains(.row) { dot(P.row, GT("Eintrag")) }
            if kinds.contains(.tag) { dot(P.tag, GT("Tag")) }
            if shared { HStack(spacing: 6) { Circle().stroke(P.page, lineWidth: 1.2).frame(width: 9, height: 9); Text(GT("Freigegeben")) } }
            if model.mode == .local { HStack(spacing: 6) { Circle().fill(Color.accentColor).frame(width: 9, height: 9); Text(GT("Offene Seite")) } }
        }
        .font(.caption2).foregroundStyle(.secondary)
        .padding(8).background(.regularMaterial, in: RoundedRectangle(cornerRadius: 8))
        .accessibilityHidden(true)
    }
    private func dot(_ c: Color, _ t: String) -> some View { HStack(spacing: 6) { Circle().fill(c).frame(width: 9, height: 9); Text(t) } }

    private func card(_ i: Int) -> some View {
        let n = model.data.nodes[i]
        return HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text((n.icon.isEmpty || n.icon.count > 4 ? "" : n.icon + " ") + n.title).font(.headline).lineLimit(2)
                Text(model.describe(i)).font(.caption).foregroundStyle(.secondary)
            }
            Spacer()
            Button { open(i) } label: { Text(n.kind == .tag ? GT("Filtern") : GT("Öffnen")) }.buttonStyle(.borderedProminent)
        }
        .padding(12).frame(maxWidth: 520)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12))
        .transition(.move(edge: .bottom).combined(with: .opacity))
    }

    @ViewBuilder private var message: some View {
        let _ = model.revision
        let d = model.data, local = model.mode == .local
        let title = model.focusID.flatMap { id in model.raw?.nodes.first { $0.id == id }?.title } ?? ""
        if model.raw?.nodes.isEmpty == true {
            ContentUnavailableView { Label { Text("Noch keine Seiten", tableName: "Graph") } icon: { Image(systemName: "point.3.connected.trianglepath.dotted") } }
                description: { Text("Lege Seiten an und verlinke sie untereinander – hier siehst du dann, wie alles zusammenhängt.", tableName: "Graph") }
                .background(.background)
        } else if local && d.missing {
            ContentUnavailableView { Label { Text("Diese Seite ist nicht im Graph", tableName: "Graph") } icon: { Image(systemName: "questionmark.circle") } }
                description: { Text("Archivierte Seiten, Vorlagen und Seiten anderer Server erscheinen hier nicht.", tableName: "Graph") }
                actions: { Button { model.setMode(.all) } label: { Text("Alle Seiten", tableName: "Graph") } }
                .background(.background)
        } else if d.nodes.isEmpty {
            ContentUnavailableView { Label { Text("Keine Seiten passen zu den Filtern", tableName: "Graph") } icon: { Image(systemName: "line.3.horizontal.decrease.circle") } }
                actions: { Button { model.resetFilter() } label: { Text("Filter zurücksetzen", tableName: "Graph") } }
                .background(.background)
        } else if local && d.nodes.count == 1 {
            VStack(spacing: 4) {
                Text("„\(title)“ ist noch mit keiner anderen Seite verbunden", tableName: "Graph").font(.callout.weight(.semibold))
                Text("Verweise im Text, Unterseiten und Relationen erscheinen hier als Linien.", tableName: "Graph").font(.caption).foregroundStyle(.secondary)
            }
            .multilineTextAlignment(.center).padding(12).background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12))
            .frame(maxHeight: .infinity, alignment: .top).padding(.top, 40).padding(.horizontal)
        }
    }

    @ToolbarContentBuilder private var toolbar: some ToolbarContent {
        ToolbarItemGroup(placement: .primaryAction) {
            Menu {
                Toggle(isOn: $model.filter.link) { Text("Verweise im Text", tableName: "Graph") }
                Toggle(isOn: $model.filter.child) { Text("Unterseiten", tableName: "Graph") }
                Toggle(isOn: $model.filter.rel) { Text("Datenbank-Relationen", tableName: "Graph") }
                Toggle(isOn: $model.filter.tags) { Text("Tags als Knoten", tableName: "Graph") }
                Toggle(isOn: $model.filter.rows) { Text("Datenbank-Einträge", tableName: "Graph") }
                Toggle(isOn: $model.filter.orphans) { Text("Seiten ohne Verbindung", tableName: "Graph") }
                Divider()
                Button { model.relayout() } label: { Label { Text("Neu anordnen", tableName: "Graph") } icon: { Image(systemName: "arrow.triangle.2.circlepath") } }
                Button { model.resetFilter() } label: { Label { Text("Filter zurücksetzen", tableName: "Graph") } icon: { Image(systemName: "arrow.uturn.backward") } }
            } label: { Label { Text("Filter", tableName: "Graph") } icon: { Image(systemName: "line.3.horizontal.decrease.circle") } }
            Button { showList = !listMode } label: {
                Label { Text(listMode ? GT("Graph") : GT("Liste")) } icon: { Image(systemName: listMode ? "point.3.connected.trianglepath.dotted" : "list.bullet") }
            }
            .help(Text("Liste", tableName: "Graph"))
            if let onClose {
                Button { onClose() } label: { Label { Text("Schließen", tableName: "Graph") } icon: { Image(systemName: "xmark") } }
                    .keyboardShortcut(.cancelAction)
            }
        }
    }

    private func open(_ i: Int) {
        guard i < model.data.nodes.count else { return }
        let n = model.data.nodes[i]
        if n.kind == .tag { model.query = String(n.title.dropFirst()); model.selected = nil; return }   // Tag: danach filtern
        if let id = n.pageID { onOpen(id) }
    }
}

/// Zeichenflaeche: Canvas in einer TimelineView, die nur laeuft, solange sich etwas bewegt
struct GraphCanvas: View {
    @ObservedObject var model: GraphModel
    var palette: GraphModel.Palette
    var open: (Int) -> Void
    @State private var dragStart: CGPoint?
    #if os(macOS)
    @State private var monitor: Any?
    #endif

    var body: some View {
        GeometryReader { geo in
            TimelineView(.animation(minimumInterval: nil, paused: !model.running)) { tl in
                Canvas(rendersAsynchronously: false) { ctx, _ in
                    model.step(tl.date)
                    model.draw(&ctx, palette: palette)
                }
            }
            .background(palette.bg.opacity(0.001))
            .contentShape(Rectangle())
            .onAppear { model.resize(geo.size) }
            .onChange(of: geo.size) { _, s in model.resize(s) }
            .gesture(drag)
            .simultaneousGesture(magnify)
            #if os(macOS)
            .gesture(SpatialTapGesture(count: 2).onEnded { v in if let i = model.node(at: v.location, slop: GraphGeo.hitMouse) { open(i) } }
                .exclusively(before: SpatialTapGesture(count: 1).onEnded { v in _ = model.tap(at: v.location) }))
            #else
            .gesture(SpatialTapGesture(count: 1).onEnded { v in if let i = model.tap(at: v.location) { open(i) } })
            #endif
            .onContinuousHover(coordinateSpace: .local) { ph in
                switch ph {
                case .active(let p): model.hoverAt(p)
                case .ended: model.hoverAt(nil)
                }
            }
            #if os(macOS)
            .onAppear { installWheel() }
            .onDisappear { if let m = monitor { NSEvent.removeMonitor(m); monitor = nil } }
            #endif
        }
        .clipped()
        .accessibilityElement()
        .accessibilityLabel(Text("Graph der Seiten", tableName: "Graph"))
        .accessibilityValue(Text(GT("\(model.data.nodes.count) Seiten") + ", " + GT("\(model.data.edges.count) Verbindungen")))
        .accessibilityHint(Text("Für eine Liste aller Seiten „Liste“ wählen.", tableName: "Graph"))
    }

    private var drag: some Gesture {
        DragGesture(minimumDistance: model.touch ? 8 : 4, coordinateSpace: .local)
            .onChanged { v in model.dragChanged(start: v.startLocation, at: v.location) }
            .onEnded { _ in model.dragEnded() }
    }
    private var magnify: some Gesture {
        MagnifyGesture()
            .onChanged { v in model.pinch(v.magnification, at: v.startLocation) }
            .onEnded { _ in model.pinchEnded() }
    }

    #if os(macOS)
    /// Mausrad zoomt um den Zeiger, Trackpad-Wischen verschiebt (Trackpad-Zoom: MagnifyGesture)
    private func installWheel() {
        guard monitor == nil else { return }
        let model = model
        monitor = NSEvent.addLocalMonitorForEvents(matching: .scrollWheel) { [weak model] e in
            MainActor.assumeIsolated {
                guard let model, let m = model.mouse else { return e }
                if e.hasPreciseScrollingDeltas && !e.modifierFlags.contains(.command) {
                    model.pan(dx: e.scrollingDeltaX, dy: e.scrollingDeltaY)
                } else {
                    model.zoom(by: exp(e.scrollingDeltaY * (e.hasPreciseScrollingDeltas ? 0.01 : 0.08)), at: m)
                }
                return nil
            }
        }
    }
    #endif
}

/// Listenansicht fuer VoiceOver (und auf Wunsch): alle Knoten mit Anzahl der Verbindungen, meistverbundene zuerst
struct GraphList: View {
    @ObservedObject var model: GraphModel
    var open: (Int) -> Void

    var body: some View {
        let _ = model.revision
        let d = model.data
        let idx = (d.matches(model.query) ?? Array(d.nodes.indices)).sorted { d.nodes[$0].deg != d.nodes[$1].deg ? d.nodes[$0].deg > d.nodes[$1].deg : d.nodes[$0].title < d.nodes[$1].title }
        List(idx, id: \.self) { i in
            let n = d.nodes[i]
            Button { open(i) } label: {
                HStack {
                    Image(systemName: n.kind == .database ? "tablecells" : n.kind == .tag ? "number" : n.kind == .row ? "list.bullet.rectangle" : "doc.text")
                        .foregroundStyle(.secondary).accessibilityHidden(true)
                    Text(n.title).lineLimit(1)
                    if !n.own { Image(systemName: "person.2.fill").font(.caption2).foregroundStyle(.secondary).accessibilityLabel(Text("Freigegeben", tableName: "Graph")) }
                    Spacer()
                    Text(model.describe(i)).font(.caption).foregroundStyle(.secondary)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityElement(children: .combine)
        }
    }
}

#if DEBUG
/// Nur fuer Bilder und Messungen (Debug-Build, ohne Server): eingebauter Beispielgraph.
/// Start: -hkGraphDemo <knoten> [-hkGraphDark YES] [-hkGraphLocal <id>] [-hkGraphDepth 2] [-hkGraphHover <titel>]
/// [-hkGraphSelect <titel>] [-hkGraphSettle YES] [-hkGraphPerf YES] (Bildzeiten ins Protokoll)
enum GraphPreview {
    static var nodes: Int? { let v = UserDefaults.standard.integer(forKey: "hkGraphDemo"); return v > 0 ? v : nil }
    static var local: Bool { UserDefaults.standard.integer(forKey: "hkGraphLocal") > 0 }
    static var focus: Int? { let v = UserDefaults.standard.integer(forKey: "hkGraphLocal"); return v > 0 ? v : nil }
    static var depth: Int? { let v = UserDefaults.standard.integer(forKey: "hkGraphDepth"); return v > 0 ? v : nil }

    @MainActor static func afterLoad(_ m: GraphModel) {
        let ud = UserDefaults.standard
        if ud.bool(forKey: "hkGraphSettle") { m.relayoutSettled() }
        func find(_ t: String) -> Int? { m.data.nodes.firstIndex { $0.title == t } ?? m.data.nodes.firstIndex { $0.title.contains(t) } }
        Task { @MainActor in
            try? await Task.sleep(for: .seconds(ud.bool(forKey: "hkGraphSettle") ? 0.3 : 4))
            if let t = ud.string(forKey: "hkGraphHover"), let i = find(t) { m.setHover(i) }
            if let t = ud.string(forKey: "hkGraphSelect"), let i = find(t) { m.selected = i; m.wake() }
            if ud.bool(forKey: "hkGraphPerf") {
                // Messung: Simulation neu anwerfen und die Bildzeiten (Zeichnen) + Schrittzeit protokollieren
                m.frameMS = []
                let t0 = Date(); for _ in 0..<40 { m.sim.tick() }
                let tick = Date().timeIntervalSince(t0) * 1000 / 40
                m.sim.heat(1); m.wake()
                try? await Task.sleep(for: .seconds(3))
                let f = m.frameMS.sorted()
                let med = f.isEmpty ? 0 : f[f.count / 2], p95 = f.isEmpty ? 0 : f[min(f.count - 1, f.count * 95 / 100)]
                NSLog("GRAPHPERF nodes=%ld edges=%ld tick=%.2fms draw_med=%.2fms draw_p95=%.2fms frames=%ld", m.data.nodes.count, m.data.edges.count, tick, med, p95, f.count)
            }
        }
    }
}

struct GraphPreviewView: View {
    var body: some View {
        NavigationStack {
            GraphScreen(store: nil, focusID: GraphPreview.focus, initialMode: GraphPreview.local ? .local : .all, onOpen: { _ in })
        }
        .preferredColorScheme(UserDefaults.standard.bool(forKey: "hkGraphDark") ? .dark : .light)
    }
}

extension GraphModel {
    /// Bilder: fertiges Layout ohne Bewegung
    func relayoutSettled() { sim.settle(); fit(instant: true); wake() }
}
#endif

extension FedLogic { static let graphTag = -5 }          // Seitenleiste: Graph (neben Aufgaben -1, Termine -2, Dienste -3, Chats -4)

extension Store {
    /// Graph braucht Server 2.5 (aeltere antworten 404); Version noch unbekannt -> anbieten, die Ansicht meldet es dann
    var graphSupported: Bool { serverVersion.map { !Store.versionLess($0, "2.5.0") } ?? true }
}

/// Lokaler Graph aus dem Seitenmenue: eigenes Blatt, "Oeffnen" schliesst es und zeigt die Seite
struct LocalGraphSheet: View {
    let id: Int
    var open: (Int) -> Void
    @EnvironmentObject private var store: Store
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            GraphScreen(store: store, focusID: id, initialMode: .local, onOpen: { n in dismiss(); if n != id { open(n) } }, onClose: { dismiss() })
        }
        .interactiveDismissDisabled()                    // Ziehen im Graph soll das Blatt nicht schliessen
        #if os(macOS)
        .frame(minWidth: 720, idealWidth: 960, minHeight: 520, idealHeight: 720)
        #endif
    }
}
