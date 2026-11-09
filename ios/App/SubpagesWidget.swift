import SwiftUI

// Saga 2.5 Widget "Unterseiten" (subpages1): die direkten Unterseiten der Seite als Liste oder Galerie
// (APP-API "Unterseiten als Liste oder Galerie"). Werte kommen ueber GET /api/notes/{id}/widgets (PageWidgetValues),
// Auswerten/Regeln in WidgetLogic (mit Tests). Einrichten im Web; der Block bleibt beim Speichern unveraendert.
// Optik nach den Widget-Regeln: Titel in Textfarbe, gedaempfte Flaechen, kein Akzent-Gold.

struct SubpagesWidget: View {
    let block: WidgetLogic.Block
    let value: WidgetLogic.Value?
    /// Seite bearbeitbar (nicht gesperrt, nicht nur lesen)? Dann bietet der Leerzustand "Unterseite anlegen" an
    var editable = false
    @EnvironmentObject private var nav: NavModel
    @EnvironmentObject private var store: Store
    @EnvironmentObject private var ed: PageEditor
    @Environment(\.widgetSize) private var size
    @Environment(\.widgetWidth) private var width
    @State private var adding = false

    var body: some View {
        if let s = WidgetLogic.subpages(value?.dict) {
            if s.items.isEmpty {
                empty(s)
            } else {
                VStack(alignment: .leading, spacing: 8) {
                    if WidgetLogic.subView(block.cfg) == .gallery { grid(s) } else { list(s) }
                    if s.more > 0 { Text("und \(s.more) weitere").font(.footnote).foregroundStyle(.secondary).padding(.horizontal, 2) }
                }
                // VoiceOver: ein Bereich mit Namen, darin je Unterseite ein Element (wie eine Liste lesbar)
                .accessibilityElement(children: .contain)
                .accessibilityLabel(Text(verbatim: block.title.isEmpty ? String(localized: "Unterseiten") : block.title))
            }
        } else {
            Text(verbatim: "…").foregroundStyle(.secondary)
        }
    }

    private func open(_ it: WidgetLogic.Subpages.Item) { nav.open(it.id, mode: "") }   // auch Datenbanken und fremde Seiten (negative ID)

    // MARK: Galerie

    private func grid(_ s: WidgetLogic.Subpages) -> some View {
        let image = WidgetLogic.subImage(block.cfg)
        let n = WidgetLogic.subColumns(width: Double(width), size: size, image: image)
        let cols = Array(repeating: GridItem(.flexible(minimum: 40), spacing: WidgetLogic.subGap, alignment: .top), count: n)
        return LazyVGrid(columns: cols, alignment: .leading, spacing: 14) {
            ForEach(s.items) { it in SubpageCard(item: it, image: image, size: size) { open(it) } }
        }
    }

    // MARK: Liste

    private func list(_ s: WidgetLogic.Subpages) -> some View {
        VStack(alignment: .leading, spacing: 1) {
            ForEach(s.items) { it in SubpageRow(item: it, size: size) { open(it) } }
        }
    }

    // MARK: Leerzustand

    private func empty(_ s: WidgetLogic.Subpages) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Image(systemName: WidgetKind.icon("subpages")).font(.system(size: 18)).frame(height: 20).foregroundStyle(.secondary)
                .padding(.bottom, 2).accessibilityHidden(true)
            Text("Noch keine Unterseiten").font(.subheadline.weight(.medium))
            if s.canAdd && editable {
                Button { add() } label: { Label("Unterseite anlegen", systemImage: "plus") }
                    .buttonStyle(.bordered).tint(.primary).disabled(adding).padding(.top, 4)
            } else {
                Text("Hier erscheinen die Seiten, die unter dieser Seite liegen.").font(.footnote).foregroundStyle(.secondary)
            }
        }
    }

    /// Neue Unterseite anlegen und oeffnen (POST /api/notes mit parent_id - derselbe Weg wie "Unterseite hinzufuegen")
    private func add() {
        guard !adding else { return }
        adding = true
        let parent = ed.doc.id
        Task {
            await ed.flush()
            if let n = await store.newPage(parent: parent) { nav.focusTitle = n; nav.push(PageRoute(id: n)) }
            adding = false
        }
    }
}

/// Name fuer VoiceOver: "Titel, Seite" bzw. "Titel, Datenbank"
private func subpageLabel(_ it: WidgetLogic.Subpages.Item) -> Text {
    Text(verbatim: (it.title.isEmpty ? String(localized: "Unbenannt") : it.title) + ", " + (it.database ? String(localized: "Datenbank") : String(localized: "Seite")))
}

/// Symbol einer Unterseite: Emoji, eigenes Bild oder (leer) das Standardsymbol fuer Seite/Datenbank
private struct SubpageIcon: View {
    let item: WidgetLogic.Subpages.Item
    let size: CGFloat
    var body: some View {
        Group {
            if item.icon.hasPrefix("/") {
                RemoteImage(path: item.icon, fill: true, w: 160).frame(width: size * 1.15, height: size * 1.15)
                    .clipShape(RoundedRectangle(cornerRadius: max(3, size * 0.16)))
            } else if item.icon.isEmpty {
                Image(systemName: item.database ? "tablecells" : "doc.text").font(.system(size: size * 0.9)).foregroundStyle(.secondary)
            } else {
                Text(verbatim: item.icon).font(.system(size: size))
            }
        }
        .accessibilityHidden(true)
    }
}

/// Karte der Galerie: Bild oben (2:3 bzw. 16:9), darunter Symbol + Titel (hoechstens zwei Zeilen); ohne Bild eine ruhige
/// Flaeche mit grossem Seitensymbol; Kartenbild "none": kleine Karte mit Rand
private struct SubpageCard: View {
    let item: WidgetLogic.Subpages.Item
    let image: WidgetLogic.SubImage
    let size: WidgetLogic.Size
    let open: () -> Void
    @ScaledMetric(relativeTo: .footnote) private var ic: CGFloat = 15
    @Environment(\.dynamicTypeSize) private var dts

    var body: some View {
        Button(action: open) {
            if let ratio = WidgetLogic.subAspect(image) {
                VStack(alignment: .leading, spacing: 7) {
                    preview(CGFloat(ratio))
                    HStack(alignment: .firstTextBaseline, spacing: 5) {
                        if item.img != nil { SubpageIcon(item: item, size: ic).alignmentGuide(.firstTextBaseline) { $0[.bottom] - ic * 0.2 } }   // sonst zeigt es der Platzhalter schon gross
                        title
                    }
                    .padding(.horizontal, 2)
                }
                .frame(maxWidth: .infinity, alignment: .topLeading)
                .contentShape(Rectangle())
            } else {
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    SubpageIcon(item: item, size: ic * 1.3).alignmentGuide(.firstTextBaseline) { $0[.bottom] - ic * 0.26 }
                    title
                    Spacer(minLength: 0)
                }
                .padding(.vertical, 11).padding(.horizontal, 13)
                .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                .background(WGColor.card, in: RoundedRectangle(cornerRadius: 10))
                .overlay(RoundedRectangle(cornerRadius: 10).strokeBorder(WGColor.border, lineWidth: 1))
                .contentShape(RoundedRectangle(cornerRadius: 10))
            }
        }
        .buttonStyle(.plain)
        .subpageHover(lift: true)
        .accessibilityLabel(subpageLabel(item))
    }

    private var title: some View {
        Text(verbatim: item.title.isEmpty ? String(localized: "Unbenannt") : item.title)
            .font(size == .l ? .subheadline.weight(.medium) : .footnote.weight(.medium))
            .foregroundStyle(.primary)
            .lineLimit(dts.isAccessibilitySize ? 3 : 2).multilineTextAlignment(.leading).truncationMode(.tail)
            .fixedSize(horizontal: false, vertical: true)
    }

    private func preview(_ ratio: CGFloat) -> some View {
        let shape = RoundedRectangle(cornerRadius: 8)
        return shape.fill(Color.primary.opacity(0.05))
            .aspectRatio(ratio, contentMode: .fit)
            .overlay {
                if let img = item.img {
                    RemoteImage(path: WidgetLogic.subImageKey(img, image: image), fill: true)
                } else {
                    SubpageIcon(item: item, size: size == .s ? 32 : size == .l ? 51 : 42).opacity(0.9)
                }
            }
            .clipShape(shape)
            .overlay(shape.strokeBorder(WGColor.border.opacity(0.75), lineWidth: 1))
    }
}

/// Zeile der Liste: Symbol + Titel (Textfarbe, dezent unterstrichen wie im Web); Groesse = Zeilenhoehe/Schrift
private struct SubpageRow: View {
    let item: WidgetLogic.Subpages.Item
    let size: WidgetLogic.Size
    let open: () -> Void
    @ScaledMetric(relativeTo: .body) private var ic: CGFloat = 17

    var body: some View {
        let k: CGFloat = size == .s ? 0.88 : size == .l ? 1.2 : 1
        Button(action: open) {
            HStack(spacing: 8) {
                SubpageIcon(item: item, size: ic * k).frame(width: ic * k * 1.45)
                Text(verbatim: item.title.isEmpty ? String(localized: "Unbenannt") : item.title)
                    .font(size == .s ? .subheadline.weight(.medium) : size == .l ? .title3.weight(.medium) : .body.weight(.medium))
                    .underline(true, color: .primary.opacity(0.22))
                    .foregroundStyle(.primary).lineLimit(1).truncationMode(.tail)
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 6)
            .frame(maxWidth: .infinity, minHeight: size == .s ? 36 : size == .l ? 50 : 44, alignment: .leading)
            .contentShape(RoundedRectangle(cornerRadius: 8))
        }
        .buttonStyle(.plain)
        .subpageHover(lift: false)
        .accessibilityLabel(subpageLabel(item))
    }
}

private extension View {
    /// iPad-Zeiger: Karte hebt sich, Zeile wird hinterlegt (Mac: Standard)
    @ViewBuilder func subpageHover(lift: Bool) -> some View {
        #if os(iOS)
        self.hoverEffect(lift ? .lift : .highlight)
        #else
        self
        #endif
    }
}
