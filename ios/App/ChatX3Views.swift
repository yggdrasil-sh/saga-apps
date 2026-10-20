import SwiftUI
#if os(iOS)
import UIKit
#else
import AppKit
#endif

// Reaktionen und Bearbeiten (Saga 2.5): Schnellleiste (6 Emojis + "+" mit der System-Emoji-Auswahl), Chips unter der
// Blase (Emoji + Zahl, eigene hervorgehoben), "Reaktionen von …", Hinweis "bearbeitet", Leiste "Bearbeiten · Abbrechen".
// iPhone/iPad: langes Druecken (Leiste oben im Kontextmenue); Mac: Ueberfahren (Knopf neben der Blase) und Rechtsklick.

/// Schnellleiste als Palette im Kontextmenue (oben, waagerecht)
struct ReactionMenuPalette: View {
    let current: String?
    var pick: (String) -> Void
    var more: () -> Void
    var body: some View {
        ControlGroup {
            ForEach(ChatX3.quick, id: \.self) { e in
                Button { pick(e) } label: { Text(verbatim: e) }
                    .accessibilityLabel(Text(verbatim: e))
            }
            Button { more() } label: { Label("Weitere Emojis", systemImage: "plus") }
        } label: { Text("Reagieren") }
        .controlGroupStyle(.palette)
    }
}

/// Schnellleiste als eigene Ansicht (Mac: Popover beim Ueberfahren; Vorschau-Bilder)
struct ReactionBar: View {
    let current: String?
    var pick: (String) -> Void
    var more: () -> Void
    var body: some View {
        HStack(spacing: 2) {
            ForEach(ChatX3.quick, id: \.self) { e in
                Button { pick(e) } label: {
                    Text(verbatim: e).font(.title2).frame(width: 40, height: 40)
                        .background(current == e ? Color.gold.opacity(0.28) : .clear, in: Circle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text(verbatim: e))
                .accessibilityAddTraits(current == e ? .isSelected : [])
            }
            Button { more() } label: {
                Image(systemName: "plus").font(.body.weight(.semibold)).frame(width: 40, height: 40)
                    .background(Color.secondary.opacity(0.14), in: Circle())
            }
            .buttonStyle(.plain)
            .help(Text("Weitere Emojis")).accessibilityLabel(Text("Weitere Emojis"))
        }
        .padding(6)
        .accessibilityElement(children: .contain)
        .accessibilityLabel(Text("Reagieren"))
    }
}

/// Chips unter der Blase; Tippen = eigene Reaktion setzen/zuruecknehmen, langes Druecken/Rechtsklick = wer
struct ReactionChips: View {
    let chips: [ChatX3.Chip]
    let enabled: Bool
    let mine: Bool
    var tap: (String) -> Void
    var who: (ChatX3.Chip) -> Void
    var body: some View {
        FlowRow(spacing: 4, alignment: mine ? .trailing : .leading) {
            ForEach(chips) { c in
                Button { if enabled { tap(c.e) } else { who(c) } } label: {
                    HStack(spacing: 3) {
                        Text(verbatim: c.e).font(.callout)
                        Text(verbatim: "\(c.n)").font(.caption.monospacedDigit().weight(.semibold))
                            .foregroundStyle(c.mine ? Color.primary : Color.secondary)
                    }
                    .padding(.horizontal, 8).padding(.vertical, 3)
                    .background(c.mine ? Color.gold.opacity(0.24) : Color.secondary.opacity(0.12), in: Capsule())
                    .overlay(Capsule().strokeBorder(c.mine ? Color.gold.opacity(0.8) : .clear, lineWidth: 1))
                }
                .buttonStyle(.plain)
                .contextMenu { Button { who(c) } label: { Label("Reaktionen von …", systemImage: "person.2") } }
                .accessibilityLabel(Text("Reaktion \(c.e): \(String(c.n))"))
                .accessibilityAddTraits(c.mine ? .isSelected : [])
                .accessibilityHint(!enabled ? Text(verbatim: "") : c.mine ? Text("Reaktion zurücknehmen") : Text("Ebenso reagieren"))
            }
        }
    }
}

/// Einfacher Zeilenumbruch fuer Chips
struct FlowRow: Layout {
    var spacing: CGFloat = 4
    var alignment: HorizontalAlignment = .leading
    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let w = proposal.width ?? 300
        var x: CGFloat = 0, y: CGFloat = 0, row: CGFloat = 0, maxW: CGFloat = 0
        for s in subviews {
            let z = s.sizeThatFits(.unspecified)
            if x > 0 && x + z.width > w { y += row + spacing; x = 0; row = 0 }
            x += z.width + spacing; row = max(row, z.height); maxW = max(maxW, x - spacing)
        }
        return CGSize(width: min(w, maxW), height: y + row)
    }
    func placeSubviews(in b: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var rows: [[(Subviews.Element, CGSize)]] = [[]], x: CGFloat = 0
        for s in subviews {
            let z = s.sizeThatFits(.unspecified)
            if x > 0 && x + z.width > b.width { rows.append([]); x = 0 }
            rows[rows.count - 1].append((s, z)); x += z.width + spacing
        }
        var y = b.minY
        for r in rows {
            let rw = r.reduce(0) { $0 + $1.1.width } + spacing * CGFloat(max(0, r.count - 1))
            var px = alignment == .trailing ? b.maxX - rw : b.minX
            let rh = r.map(\.1.height).max() ?? 0
            for (s, z) in r { s.place(at: CGPoint(x: px, y: y), proposal: ProposedViewSize(z)); px += z.width + spacing }
            y += rh + spacing
        }
    }
}

/// "Reaktionen von …": je Emoji die Namen
struct ReactionsWhoSheet: View {
    @ObservedObject var conv: ConversationModel
    let message: ChatMessage
    var only: String?
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        NavigationStack {
            List {
                let chips = conv.chips(message).filter { only == nil || $0.e == only }
                if chips.isEmpty { Text("Noch keine Reaktionen.").foregroundStyle(.secondary) }
                ForEach(chips) { c in
                    Section {
                        ForEach(c.who, id: \.self) { h in Text(verbatim: conv.name(ofHandle: h)) }
                    } header: { Text(verbatim: "\(c.e)  \(c.n)").font(.headline) }
                }
            }
            .navigationTitle("Reaktionen von …")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Fertig") { dismiss() } } }
        }
        #if os(macOS)
        .frame(minWidth: 320, minHeight: 280)
        #else
        .presentationDetents([.medium, .large])
        #endif
    }
}

/// "+": System-Emoji-Auswahl (iPhone/iPad: Emoji-Tastatur; Mac: Zeichenpalette) und eine durchsuchbare Liste
struct EmojiPickSheet: View {
    var pick: (String) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var typed = ""
    @State private var q = ""
    @FocusState private var focus: Bool
    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 12) {
                #if os(iOS)
                EmojiKeyboardField(text: $typed).frame(height: 1).opacity(0.02).accessibilityHidden(true)
                Text("Wähle ein Emoji auf der Tastatur – oder unten aus der Liste.").font(.footnote).foregroundStyle(.secondary)
                #else
                HStack {
                    TextField("Emoji", text: $typed).textFieldStyle(.roundedBorder).frame(width: 90).focused($focus)
                    Button { focus = true; NSApp.orderFrontCharacterPalette(nil) } label: { Label("Emojis und Symbole", systemImage: "face.smiling") }
                }
                #endif
                if !EmojiRecent.list.isEmpty {
                    Text("Zuletzt benutzt").font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                    grid(EmojiRecent.list)
                }
                TextField("Emojis durchsuchen", text: $q).textFieldStyle(.roundedBorder).autocorrectionDisabled()
                ScrollView { grid(filtered) }
            }
            .padding()
            .navigationTitle("Weitere Emojis")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Abbrechen") { dismiss() } } }
        }
        .onChange(of: typed) { _, v in if let e = ChatX3.pickedEmoji(v) { done(e) } else if !v.isEmpty { typed = "" } }
        #if os(macOS)
        .frame(minWidth: 420, minHeight: 440)
        .task { focus = true }
        #else
        .presentationDetents([.medium, .large])
        #endif
    }
    private var filtered: [String] {
        let t = q.trimmingCharacters(in: .whitespaces).lowercased()
        var seen = Set<String>(), out: [String] = []
        for (e, words) in ChatX3.quick.map({ ($0, "") }) + emojiList where t.isEmpty || words.contains(t) {
            if ChatX3.emojiOK(e), seen.insert(e).inserted { out.append(e) }
        }
        return out
    }
    private func grid(_ list: [String]) -> some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 40), spacing: 4)], spacing: 4) {
            ForEach(list, id: \.self) { e in
                Button { done(e) } label: { Text(verbatim: e).font(.title2).frame(width: 40, height: 40) }
                    .buttonStyle(.plain).accessibilityLabel(Text(verbatim: e))
            }
        }
    }
    private func done(_ e: String) { dismiss(); pick(e) }
}

#if os(iOS)
/// Textfeld, das die Emoji-Tastatur des Systems oeffnet (sofern eingerichtet; sonst die normale Tastatur)
struct EmojiKeyboardField: UIViewRepresentable {
    @Binding var text: String
    final class Field: UITextField {
        override var textInputMode: UITextInputMode? { UITextInputMode.activeInputModes.first { $0.primaryLanguage == "emoji" } ?? super.textInputMode }
        override var textInputContextIdentifier: String? { "" }
    }
    func makeUIView(context: Context) -> Field {
        let f = Field()
        f.addTarget(context.coordinator, action: #selector(Coord.changed(_:)), for: .editingChanged)
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { f.becomeFirstResponder() }
        return f
    }
    func updateUIView(_ v: Field, context: Context) { if v.text != text { v.text = text } }
    func makeCoordinator() -> Coord { Coord(self) }
    final class Coord: NSObject {
        let p: EmojiKeyboardField
        init(_ p: EmojiKeyboardField) { self.p = p }
        @objc func changed(_ f: UITextField) { p.text = f.text ?? "" }
    }
}
#endif

/// Leiste ueber dem Eingabefeld: "Bearbeiten · Abbrechen"
struct EditBar: View {
    let original: String
    var cancel: () -> Void
    var body: some View {
        HStack {
            Rectangle().fill(Color.gold).frame(width: 3)
            VStack(alignment: .leading) {
                Label("Bearbeiten", systemImage: "pencil").font(.caption.weight(.semibold))
                Text(verbatim: original).font(.caption).lineLimit(1).foregroundStyle(.secondary)
            }
            Spacer()
            Button("Abbrechen") { cancel() }.font(.caption).buttonStyle(.bordered).controlSize(.small).keyboardShortcut(.cancelAction)
        }
        .frame(height: 40)                                              // wie die Antwort-Leiste: nicht in die Hoehe wachsen
        .accessibilityElement(children: .contain)
    }
}

/// Hinweis "bearbeitet" neben der Zeit (Zeitpunkt im Tooltip bzw. fuer VoiceOver)
struct EditedMark: View {
    let at: Double
    var body: some View {
        let d = Date(timeIntervalSince1970: at)
        Text("bearbeitet")
            .help(Text("Bearbeitet am \(d.sagaFormatted(date: .abbreviated, time: .shortened))"))
            .accessibilityLabel(Text("Bearbeitet am \(d.sagaFormatted(date: .abbreviated, time: .shortened))"))
    }
}
