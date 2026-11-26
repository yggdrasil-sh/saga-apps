import SwiftUI

// 2.6 Tageszeile „Kurz gesagt“ oben in „Heute“ (Bugs-DB 2271 K-D, Server docs/APP-API-KI.md §13, Regeln in
// Shared/TodayLineLogic.swift). Nur mit Server-Merkmal aitoday1 und Faehigkeit "ai" (sonst enabled:false -> nichts).
// Teile als Knoepfe (>= 44 pt, oeffnen nur), daneben ✕ „Gerade nicht“ (auch Wischen) und ⋯ („Tageszeile ausblenden“,
// „Warum sehe ich das?“). Bei Ruhe die Pille „Ruhe bis morgen · Wieder an“ in gleicher Hoehe, nach dem dritten ✕ einmal
// die Frage. „Wartet auf dich“ eigene Zeile -> Saga › Aufträge. Neu laden beim Erscheinen, Ziehen und bei Live
// `ai.today.changed` (400 ms entprellt). Keine KI-Marke an der Zeile; Texte unveraendert vom Server.

@MainActor
final class TodayLineModel: ObservableObject {
    weak var store: Store?
    @Published private(set) var state: TodayLineState?
    @Published private(set) var busy = false
    private var liveTask: Task<Void, Never>?
    private var gen = 0

    var show: TodayLineShow { TodayLineLogic.show(state) }
    private var tz: String { TimeZone.current.identifier }

    /// Stand holen (mit ETag -> 304 behaelt ihn); alter Server/Fehler -> nichts zeigen
    func load() async {
        guard let store, TodayLineLogic.shouldFetch(features: store.features.set) else { if state != nil { state = nil }; return }
        #if DEBUG
        if TodayLinePreview.mode != nil { return }
        #endif
        gen += 1; let g = gen
        var h: [String: String] = [:]
        if let s = state, s.tz == tz, let e = TodayLineLogic.etag(s.v) { h["If-None-Match"] = e }
        do {
            let (d, code) = try await store.apiResponse(TodayLineLogic.path(tz: tz), headers: h)
            if g == gen { apply(TodayLineLogic.result(status: code, data: d)) }
        } catch {
            if !Store.isCancel(error), g == gen, state != nil { state = nil }
        }
    }

    /// Live (Wiederverbinden): nur nachholen, wenn schon etwas gezeigt wurde
    func resync() async { if state != nil { await load() } }

    /// Live `ai.today.changed {v}`: entprellt neu laden, wenn der Stand abweicht
    func live(_ d: [String: Any]) {
        #if DEBUG
        TodayLineDebug.log("live v=" + (d["v"] as? String ?? "-"))
        #endif
        guard TodayLineLogic.liveNeedsReload(eventV: d["v"] as? String, own: state?.v) else { return }
        liveTask?.cancel()
        liveTask = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(TodayLineLogic.debounceMs))
            if Task.isCancelled { return }
            await self?.load()
        }
    }

    func reset() { gen += 1; liveTask?.cancel(); liveTask = nil; state = nil }

    /// ✕ bzw. Wischen: Ruhe bis morgen 05:00 auf allen Geraeten
    func quiet() async { await send("POST", TodayLineLogic.quietPath(tz: tz)) }
    /// „Wieder an“ an der Pille
    func unquiet() async { await send("DELETE", TodayLineLogic.quietPath(tz: tz)) }
    /// ⋯ „Tageszeile ausblenden“ bzw. Einstellungen › Saga
    func setOn(_ on: Bool) async { await send("PUT", TodayLineLogic.path(tz: tz), body: ["on": on]) }
    /// Frage „Tageszeile ausblenden?“
    func answer(yes: Bool) async { await send("PUT", TodayLineLogic.path(tz: tz), body: TodayLineLogic.askBody(yes: yes)) }

    private func send(_ method: String, _ path: String, body: [String: Any]? = nil) async {
        guard let store, !busy else { return }
        #if DEBUG
        if TodayLinePreview.mode != nil { return }
        #endif
        busy = true; defer { busy = false }
        gen += 1; let g = gen
        do {
            let (d, code) = try await store.apiResponse(path, method: method, body: body)
            guard g == gen else { return }
            if (200..<300).contains(code) { apply(TodayLineLogic.result(status: code, data: d)) }
            else { store.error = Self.errorText(d) }
        } catch {
            if !Store.isCancel(error) { store.error = error.localizedDescription }
        }
    }
    private static func errorText(_ d: Data) -> String {
        (try? JSONSerialization.jsonObject(with: d) as? [String: Any])?["error"] as? String ?? String(localized: "Server nicht erreichbar")
    }

    private func apply(_ r: TodayLineFetch) {
        switch r {
        case .state(let s): if s != state { state = s }
        case .keep: break
        case .nothing: if state != nil { state = nil }
        }
        #if DEBUG
        TodayLineDebug.log(r == .keep ? "304" : TodayLineDebug.describe(state))
        #endif
    }

    #if DEBUG
    /// nur fuer Bilder/Previews: Stand aus einer gespeicherten Antwort
    func preview(_ json: String) { state = TodayLineLogic.decode(Data(json.utf8)) }
    #endif
}

/// Mindesthoehe von Zeile, Pille und Frage (gleiche Hoehe: kein Springen beim ✕)
private let todayLineMinHeight: CGFloat = 76

/// Abschnitt oben in „Heute“ - leer (kein Platzhalter), wenn es nichts zu zeigen gibt
struct TodayLineSection: View {
    @ObservedObject var m: TodayLineModel
    /// Saga-Ort vorhanden: „Wartet auf dich“ antippbar
    var jobsReachable: Bool
    var open: (TodayLineNav) -> Void
    @State private var info = false
    /// zuletzt gemessene Hoehe der Zeile: Pille und Frage bekommen dieselbe (kein Springen beim ✕)
    @State private var lineHeight: CGFloat = todayLineMinHeight
    private var rowMin: CGFloat { max(todayLineMinHeight, lineHeight) }

    var body: some View {
        let s = m.show
        if !s.isEmpty {
            Section {
                if let a = s.ask { askRow(a) }
                else if let q = s.pill { pillRow(q) }
                else if let k = s.kurz { lineRow(k) }
                if let w = s.waiting { waitingRow(w) }
            }
            .alert(Text("Warum sehe ich das?"), isPresented: $info) {
                Button("OK", role: .cancel) {}
            } message: { Text(verbatim: m.state?.info ?? "") }
        }
    }

    private func lineRow(_ k: TodayLineShow.Kurz) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 0) {
                Text(verbatim: k.label).font(.caption.weight(.semibold)).foregroundStyle(Color.secondary)
                    .accessibilityLabel(Text(verbatim: k.a11y))          // Vorlesetext der ganzen Zeile (ohne Symbole)
                Spacer(minLength: 4)
                Button { Task { await m.quiet() } } label: {
                    Image(systemName: "xmark").font(.subheadline.weight(.semibold)).foregroundStyle(Color.secondary)
                        .frame(width: 44, height: 44).contentShape(Rectangle())
                }
                .buttonStyle(.borderless).accessibilityLabel(Text("Gerade nicht")).disabled(m.busy)
                Menu {
                    Button { Task { await m.setOn(false) } } label: { Label("Tageszeile ausblenden", systemImage: "eye.slash") }
                    Button { info = true } label: { Label("Warum sehe ich das?", systemImage: "info.circle") }
                } label: {
                    Image(systemName: "ellipsis").font(.subheadline.weight(.semibold)).foregroundStyle(Color.secondary)
                        .frame(width: 44, height: 44).contentShape(Rectangle())
                }
                .menuIndicator(.hidden).fixedSize()
                .accessibilityLabel(Text("Optionen")).disabled(m.busy)
            }
            .padding(.trailing, -8)                                      // Trefferflaechen bis an den Rand
            TodayFlow(spacing: 6) {
                ForEach(Array(k.parts.enumerated()), id: \.offset) { _, p in part(p) }
            }
            .padding(.bottom, 4)
        }
        .frame(minHeight: todayLineMinHeight)
        .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { lineHeight = $0 }
        .accessibilityElement(children: .contain)
        .swipeActions(edge: .trailing, allowsFullSwipe: true) {         // Wischen = ✕
            Button { Task { await m.quiet() } } label: { Label("Gerade nicht", systemImage: "moon") }.tint(.gray)
        }
    }

    @ViewBuilder private func part(_ p: TodayLinePart) -> some View {
        let chip = Text(verbatim: p.text).font(.subheadline).foregroundStyle(Color.primary).multilineTextAlignment(.leading)
            .padding(.horizontal, 10).padding(.vertical, 7)
            .background(Color.secondary.opacity(0.12), in: Capsule())
            .frame(minHeight: 44)                                        // Trefferflaeche >= 44 pt
        if let n = TodayLineLogic.nav(p.target) {
            Button { open(n) } label: { chip.contentShape(Rectangle()) }.buttonStyle(.borderless)
        } else {
            chip                                                         // unbekanntes Ziel: nicht antippbar
        }
    }

    private func pillRow(_ q: TodayQuiet) -> some View {
        HStack(spacing: 8) {
            Label { Text(verbatim: q.text) } icon: { Image(systemName: "moon").foregroundStyle(Color.secondary) }
                .font(.subheadline).foregroundStyle(Color.secondary)
            Spacer(minLength: 4)
            Button { Task { await m.unquiet() } } label: { Text(verbatim: q.undo).frame(minHeight: 44) }
                .buttonStyle(.borderless).disabled(m.busy)
        }
        .padding(.horizontal, 14)
        .background(Color.secondary.opacity(0.10), in: Capsule())
        .frame(maxWidth: .infinity, minHeight: rowMin)
    }

    private func askRow(_ a: TodayAsk) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(verbatim: a.text).font(.subheadline.weight(.semibold))
            HStack(spacing: 10) {
                Button { Task { await m.answer(yes: true) } } label: { Text(verbatim: a.yes).frame(minHeight: 44) }.buttonStyle(.bordered)
                Button { Task { await m.answer(yes: false) } } label: { Text(verbatim: a.no).frame(minHeight: 44) }.buttonStyle(.bordered)
            }
            .disabled(m.busy)
        }
        .padding(.vertical, 4)
        .frame(maxWidth: .infinity, minHeight: rowMin, alignment: .leading)
    }

    @ViewBuilder private func waitingRow(_ w: TodayWaiting) -> some View {
        let t = Text(verbatim: w.text).font(.subheadline).foregroundStyle(jobsReachable ? Color.gold : Color.secondary)
            .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
        if jobsReachable, let n = TodayLineLogic.nav(w.target ?? TodayLineTarget(type: "jobs")) {
            Button { open(n) } label: { t.contentShape(Rectangle()) }.buttonStyle(.borderless)
        } else { t }
    }
}

/// Zeilenumbruch fuer die Teile; ein Teil breiter als die Zeile bricht selbst um (statt abgeschnitten)
struct TodayFlow: Layout {
    var spacing: CGFloat = 6
    private func sizes(_ subviews: Subviews, width w: CGFloat) -> [CGSize] {
        subviews.map { s in
            let ideal = s.sizeThatFits(.unspecified)
            return ideal.width <= w ? ideal : s.sizeThatFits(ProposedViewSize(width: w, height: nil))
        }
    }
    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let w = proposal.width ?? 320
        var x: CGFloat = 0, y: CGFloat = 0, row: CGFloat = 0, maxW: CGFloat = 0
        for z in sizes(subviews, width: w) {
            if x > 0 && x + z.width > w { y += row + spacing; x = 0; row = 0 }
            x += z.width + spacing; row = max(row, z.height); maxW = max(maxW, x - spacing)
        }
        return CGSize(width: min(w, maxW), height: y + row)
    }
    func placeSubviews(in b: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var x = b.minX, y = b.minY, row: CGFloat = 0
        for (s, z) in zip(subviews, sizes(subviews, width: b.width)) {
            if x > b.minX && x + z.width > b.maxX { y += row + spacing; x = b.minX; row = 0 }
            s.place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(z))
            x += z.width + spacing; row = max(row, z.height)
        }
    }
}

/// Einstellungen › Saga: Schalter „Tageszeile auf „Heute““ (PUT /api/ai/today {on}) - nur, wenn der Server sie liefert
struct TodayLineToggle: View {
    @ObservedObject var m: TodayLineModel
    var body: some View {
        Group {
            if let s = m.state, s.enabled, let on = s.on {
                Section {
                    Toggle("Tageszeile auf „Heute“", isOn: Binding(get: { on }, set: { v in Task { await m.setOn(v) } }))
                        .disabled(m.busy)
                } footer: {
                    if let i = s.info, !i.isEmpty { Text(verbatim: i) }
                }
            }
        }
        .task { await m.load() }
    }
}

#if DEBUG
/// nur fuer Tests (Startparameter, Gegenlauf gegen einen echten Server): -hkTodayLineLog YES schreibt jeden Stand nach
/// tmp/saga-dbg.log (Zeilen "TL ..."); -hkTodayLineAct quiet,unquiet,quiet,ja|nein,hide,show,reload loest die Aktionen
/// nacheinander aus (je 2 s Pause), danach "TL fertig"
enum TodayLineDebug {
    static func log(_ s: String) {
        guard UserDefaults.standard.bool(forKey: "hkTodayLineLog") else { return }
        let u = FileManager.default.temporaryDirectory.appendingPathComponent("saga-dbg.log")
        let line = Data(("TL " + s + "\n").utf8)
        if let h = try? FileHandle(forWritingTo: u) { h.seekToEndOfFile(); h.write(line); try? h.close() } else { try? line.write(to: u) }
    }
    static func describe(_ s: TodayLineState?) -> String {
        let x = TodayLineLogic.show(s)
        let v: String = s?.v ?? "-"
        let on: String = s?.on.map { $0 ? "true" : "false" } ?? "-"
        let kinds: String = x.kurz?.parts.map(\.kind).joined(separator: "+") ?? "-"
        let parts: String = x.kurz?.parts.map(\.text).joined(separator: " | ") ?? "-"
        let pill: String = x.pill?.text ?? "-"
        let ask: String = x.ask?.text ?? "-"
        let wait: String = x.waiting?.text ?? "-"
        let pend: String = s?.pending?.joined(separator: ",") ?? "-"
        return ["v=" + v, "on=" + on, "kinds=" + kinds, "parts=" + parts, "pill=" + pill, "ask=" + ask, "waiting=" + wait, "pending=" + pend].joined(separator: " ")
    }
    @MainActor static func run(_ m: TodayLineModel) async {
        guard let acts = UserDefaults.standard.string(forKey: "hkTodayLineAct") else { return }
        for _ in 0..<40 where m.state == nil { try? await Task.sleep(for: .milliseconds(250)) }
        for a in acts.split(separator: ",") {
            try? await Task.sleep(for: .seconds(2))
            log("act " + a)
            switch a {
            case "quiet": await m.quiet()
            case "unquiet": await m.unquiet()
            case "ja": await m.answer(yes: true)
            case "nein": await m.answer(yes: false)
            case "hide": await m.setOn(false)
            case "show": await m.setOn(true)
            default: await m.load()
            }
        }
        try? await Task.sleep(for: .seconds(1))
        log("fertig")
    }
}

/// nur fuer Bilder (ohne Server): -hkTodayLine normal|ruhe|frage (zusammen mit -hkE2Preview heute); Antworten aus
/// Tests/vectors/today_vectors.json (erfundene Namen)
enum TodayLinePreview {
    static var mode: String? { UserDefaults.standard.string(forKey: "hkTodayLine") }
    static var json: String? {
        switch mode {
        case "normal": return #"{"enabled": true, "on": true, "day": "2026-07-07", "tz": "Europe/Berlin", "label": "Kurz gesagt", "line": {"text": "Elternabend 19:30 · „Pavillon abholen“ überfällig (+2) · „Kuchen backen“ heute", "a11y": "Kurz gesagt: Elternabend 19:30, „Pavillon abholen“ überfällig, plus 2, „Kuchen backen“ heute", "parts": [{"kind": "event", "text": "Elternabend 19:30", "target": {"type": "events", "date": "2026-07-07"}}, {"kind": "over", "text": "„Pavillon abholen“ überfällig (+2)", "n": 3, "target": {"type": "task", "id": 7}}, {"kind": "due", "text": "„Kuchen backen“ heute", "target": {"type": "row", "id": 512}}]}, "waiting": {"n": 1, "text": "1 Auftrag wartet auf dich ›", "target": {"type": "jobs", "group": "wartet_auf_dich"}}, "counts": {"events": 1, "over": 3, "due": 1, "waiting": 1}, "quiet": null, "ask": null, "pending": [], "info": "Ohne KI berechnet – aus deinen Aufgaben, Terminen und Aufträgen. Nichts davon geht an ein Sprachmodell.", "v": "4c01b4a34ebc"}"#
        case "ruhe": return #"{"enabled": true, "on": true, "day": "2026-07-07", "tz": "Europe/Berlin", "label": "Kurz gesagt", "line": null, "waiting": {"n": 1, "text": "1 Auftrag wartet auf dich ›", "target": {"type": "jobs", "group": "wartet_auf_dich"}}, "counts": {"events": 1, "over": 3, "due": 1, "waiting": 1}, "quiet": {"until": "2026-07-08T05:00:00+02:00", "text": "Ruhe bis morgen", "undo": "Wieder an"}, "ask": null, "pending": [], "info": "Ohne KI berechnet – aus deinen Aufgaben, Terminen und Aufträgen. Nichts davon geht an ein Sprachmodell.", "v": "89c0cb5de019"}"#
        case "frage": return #"{"enabled": true, "on": true, "label": "Kurz gesagt", "line": null, "waiting": null, "quiet": {"until": "2026-07-08T05:00:00+02:00", "text": "Ruhe bis morgen", "undo": "Wieder an"}, "ask": {"text": "Tageszeile ausblenden?", "yes": "Ja, ausblenden", "no": "Nein, passt"}, "v": "764a8f8da23f"}"#
        default: return nil
        }
    }
}
#endif
