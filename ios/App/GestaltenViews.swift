import SwiftUI

// Saga 2.5 "Seite gestalten": Darstellung (Spaltenbreiten, Bildbreite, Block-Farbe, Schrift der Seite, Rueckfall-Karte).
// Die Regeln stehen in GestaltenLogic.swift (mit Tests).

/// Textfarbe eines farbigen Blocks fuer die Textfelder darin (EditText setzt sonst .primary)
private struct BlockTintKey: EnvironmentKey { static let defaultValue: Color? = nil }
extension EnvironmentValues { var blockTint: Color? { get { self[BlockTintKey.self] } set { self[BlockTintKey.self] = newValue } } }

/// Farbe eines Blocks (tunes.hkstyle): Text- oder Hintergrundfarbe aus der Palette der Auswahl-Farben (hell/dunkel
/// passen sich die Systemfarben selbst an)
struct BlockStyleMod: ViewModifier {
    let style: GestaltenLogic.Style?
    func body(content: Content) -> some View {
        if let style {
            let c = optColor(style.color)
            if style.bg {
                content.padding(.horizontal, 6).padding(.vertical, 3)
                    .background(c.opacity(0.18), in: RoundedRectangle(cornerRadius: 6))
            } else {
                content.foregroundStyle(c).environment(\.blockTint, c)
            }
        } else {
            content
        }
    }
}

/// Spalten nach Gewicht (G1): Breite je Spalte = Anteil der Breite ohne Zwischenraeume
struct WeightedColumns: Layout {
    let weights: [Double]
    let spacing: CGFloat
    private func widths(_ total: CGFloat, _ n: Int) -> [CGFloat] {
        let free = max(0, total - spacing * CGFloat(max(0, n - 1)))
        return (0..<n).map { i in free * CGFloat(i < weights.count ? weights[i] : 1 / Double(max(n, 1))) }
    }
    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let total = proposal.width ?? 600
        let ws = widths(total, subviews.count)
        let h = subviews.indices.map { subviews[$0].sizeThatFits(ProposedViewSize(width: ws[$0], height: nil)).height }.max() ?? 0
        return CGSize(width: total, height: h)
    }
    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        let ws = widths(bounds.width, subviews.count)
        var x = bounds.minX
        for (i, s) in subviews.enumerated() {
            s.place(at: CGPoint(x: x, y: bounds.minY), anchor: .topLeading, proposal: ProposedViewSize(width: ws[i], height: nil))
            x += ws[i] + spacing
        }
    }
}

/// Bild/Player mit eigener Breite (G2): Anteil der Inhaltsbreite, links/mittig/rechts
struct FractionWidth: Layout {
    let fraction: Double
    let align: GestaltenLogic.Align
    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let total = proposal.width ?? 600
        let h = subviews.first?.sizeThatFits(ProposedViewSize(width: total * fraction, height: proposal.height)).height ?? 0
        return CGSize(width: total, height: h)
    }
    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        guard let s = subviews.first else { return }
        let w = bounds.width * fraction
        let x: CGFloat = align == .left ? bounds.minX : align == .right ? bounds.maxX - w : bounds.midX - w / 2
        s.place(at: CGPoint(x: x, y: bounds.minY), anchor: .topLeading, proposal: ProposedViewSize(width: w, height: bounds.height))
    }
}

extension View {
    /// Breite/Ausrichtung aus block.data (nil = wie bisher)
    @ViewBuilder func mediaSized(_ data: NSDictionary?, compact: Bool) -> some View {
        if let f = GestaltenLogic.mediaWidth(data, compact: compact) {
            FractionWidth(fraction: f, align: GestaltenLogic.align(data)) { self }
        } else { self }
    }
}

/// Schrift und kleiner Text der Seite (G9): Titel, Inhalt, Eigenschaften; Code-Bloecke bleiben Mono (eigene Schrift)
struct PageTypography: ViewModifier {
    let font: GestaltenLogic.PageFont
    let small: Bool
    @Environment(\.dynamicTypeSize) private var dts
    func body(content: Content) -> some View {
        let all = DynamicTypeSize.allCases
        let i = all.firstIndex(of: dts) ?? 3
        content
            .fontDesign(font == .serif ? .serif : font == .mono ? .monospaced : nil)
            .dynamicTypeSize(small ? all[GestaltenLogic.smallerStep(i)] : dts)
    }
}

/// Unbekannter Block-Typ (R): nicht still weglassen - Hinweis und Weg in den Web-Editor; der Block bleibt unveraendert
struct FallbackBlockCard: View {
    let type: String
    let blockID: String
    let note: Int
    @EnvironmentObject private var nav: NavModel
    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: "square.dashed").font(.title3).foregroundStyle(.secondary)
            Text("Dieser Block lässt sich in der App noch nicht anzeigen.").font(.subheadline).foregroundStyle(.secondary)
            Spacer(minLength: 8)
            Button { nav.peek = nil; nav.push(PageRoute(id: note, web: true, block: blockID)) } label: { Label("Im Web öffnen", systemImage: "safari") }
                .buttonStyle(.bordered).controlSize(.small)
        }
        .padding(12).frame(maxWidth: .infinity, alignment: .leading)
        .overlay(RoundedRectangle(cornerRadius: 10).strokeBorder(.secondary.opacity(0.35), style: StrokeStyle(lineWidth: 1, dash: [4, 3])))
        .accessibilityElement(children: .combine)
        .help(Text(verbatim: type))
    }
}

/// "Farbe" im Block-Menue (G3): Standard, Textfarben, Hintergruende
struct BlockColorMenu: View {
    let id: String
    @EnvironmentObject var ed: PageEditor
    var body: some View {
        if let b = ed.block(id), GestaltenLogic.styledTypes.contains(b["type"] as? String ?? "") {
            let cur = GestaltenLogic.style(b)
            Menu {
                Button { ed.setStyle(id, nil) } label: { pick(String(localized: "Standard"), on: cur == nil) }
                Menu {
                    ForEach(GestaltenLogic.palette, id: \.self) { c in
                        Button { ed.setStyle(id, GestaltenLogic.Style(color: c, bg: false)) } label: { pick(dbColorNames[c] ?? c, on: cur == GestaltenLogic.Style(color: c, bg: false)) }
                    }
                } label: { Label("Textfarbe", systemImage: "textformat") }
                Menu {
                    ForEach(GestaltenLogic.palette, id: \.self) { c in
                        Button { ed.setStyle(id, GestaltenLogic.Style(color: c, bg: true)) } label: { pick(dbColorNames[c] ?? c, on: cur == GestaltenLogic.Style(color: c, bg: true)) }
                    }
                } label: { Label("Hintergrund", systemImage: "paintbrush") }
            } label: { Label("Farbe", systemImage: "paintpalette") }
        }
    }
    @ViewBuilder private func pick(_ name: String, on: Bool) -> some View {
        if on { Label(name, systemImage: "checkmark") } else { Text(name) }
    }
}

extension PageEditor {
    func block(_ id: String) -> NSMutableDictionary? {
        guard let (arr, i) = locate(id) else { return nil }
        return arr[i] as? NSMutableDictionary
    }
    func setStyle(_ id: String, _ style: GestaltenLogic.Style?) {
        guard !locked, let b = block(id) else { return }
        GestaltenLogic.setStyle(b, style)
        objectWillChange.send(); changed()
    }
}
