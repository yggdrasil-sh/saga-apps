import SwiftUI

// 2.5 (L7): Abschnitt "Nicht dabei" unter der Teilnehmerliste eines Gruppenanrufs (Mesh: MeetPeoplePanel, Knoten:
// HornPeoplePanel). Gestaltung wie die Zeilen darueber (Profilbild 32, Name, Zustand klein darunter, Knopf rechts);
// grosse Schrift: Knopf unter den Namen. VoiceOver: Knoepfe "{Name} erneut klingeln lassen"; verschwindet der Knopf
// (klingelt), geht der Fokus auf die Ueberschrift, bei einem Fehler zurueck auf den Knopf.

struct InviteSection: View {
    @ObservedObject var calls: CallManager
    /// Gastgeber (call.host): darf entfernte Personen erneut klingeln lassen
    var host: Bool
    @Environment(\.dynamicTypeSize) private var dts
    @AccessibilityFocusState private var focus: InviteFocus?
    @State private var lastTap: InviteFocus?

    var body: some View {
        let _ = calls.invTick                                               // Ablauf "klingelt …" zeichnet neu
        let rows = calls.invites.rows(device: Date().timeIntervalSince1970)
        let all = Invites.ringAll(rows)
        VStack(alignment: .leading, spacing: 0) {
            Divider()
            header(all: all, rows: rows)
            if let n = calls.invNote {
                Label { Text(verbatim: n) } icon: { Image(systemName: "exclamationmark.triangle.fill") }
                    .font(.caption).foregroundStyle(.orange)
                    .padding(.horizontal, 14).padding(.bottom, 6)
                    .fixedSize(horizontal: false, vertical: true)
            }
            ForEach(rows) { r in row(r); Divider().padding(.leading, 56) }
            if calls.invites.more > 0 {
                Text("und \(calls.invites.more) weitere").font(.caption).foregroundStyle(.secondary)
                    .padding(.horizontal, 14).padding(.vertical, 8)
            }
        }
        .accessibilityElement(children: .contain)
        .onChange(of: calls.invNote) { _, n in if n != nil, let t = lastTap { focus = t } }   // Fehler: zurueck auf den Knopf
    }

    private func header(all: [String], rows: [Invites.Row]) -> some View {
        let lay = dts.isAccessibilitySize ? AnyLayout(VStackLayout(alignment: .leading, spacing: 6)) : AnyLayout(HStackLayout(spacing: 8))
        return lay {
            Text("Nicht dabei (\(calls.invites.count()))").font(.subheadline.weight(.semibold))
                .accessibilityAddTraits(.isHeader)
                .accessibilityFocused($focus, equals: .header)
            if !dts.isAccessibilitySize { Spacer(minLength: 4) }
            if !all.isEmpty {
                Button(String(localized: "Alle erneut klingeln")) {
                    tap(.all, rows.filter { all.contains($0.entry.who) }.map(\.entry))
                }
                .buttonStyle(.bordered).controlSize(.small)
                .accessibilityLabel(Text("Alle erneut klingeln (\(all.count))"))
                .accessibilityFocused($focus, equals: .all)
            }
        }
        .padding(.horizontal, 14).padding(.top, 12).padding(.bottom, 6)
    }

    private func stateText(_ s: Invites.State) -> String {
        switch s {
        case .ringing: return String(localized: "klingelt …")
        case .declined: return String(localized: "hat abgelehnt")
        case .missed: return String(localized: "nicht angenommen")
        case .left: return String(localized: "hat den Anruf verlassen")
        case .none: return String(localized: "noch nicht eingeladen")
        case .removed: return String(localized: "entfernt")
        case .old: return String(localized: "App zu alt")
        }
    }

    private func row(_ r: Invites.Row) -> some View {
        let e = r.entry
        let act = Invites.action(r.state, host: host)
        let lay = dts.isAccessibilitySize ? AnyLayout(VStackLayout(alignment: .leading, spacing: 6)) : AnyLayout(HStackLayout(spacing: 10))
        return lay {
            HStack(spacing: 10) {
                AvatarView(name: e.name, avatar: e.avatar, size: 32).opacity(r.state == .old ? 0.6 : 1).accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 1) {
                    Text(verbatim: e.name).font(.subheadline.weight(.semibold)).lineLimit(dts.isAccessibilitySize ? 3 : 1)
                    HStack(spacing: 5) {
                        if r.state == .ringing { Circle().fill(Color.green).frame(width: 6, height: 6).accessibilityHidden(true) }
                        Text(verbatim: stateText(r.state)).font(.caption).foregroundStyle(.secondary).lineLimit(dts.isAccessibilitySize ? 3 : 1)
                    }
                }
            }
            .accessibilityElement(children: .combine)
            if !dts.isAccessibilitySize { Spacer(minLength: 4) }
            switch act {
            case .hidden: EmptyView()
            case .ring, .again:
                Button(act == .ring ? String(localized: "Klingeln") : String(localized: "Erneut klingeln")) { tap(.row(e.who), [e]) }
                    .buttonStyle(.bordered).controlSize(.small)
                    .accessibilityLabel(act == .ring ? Text("\(e.name) klingeln lassen") : Text("\(e.name) erneut klingeln lassen"))
                    .accessibilityFocused($focus, equals: .row(e.who))
            case .old:
                Button(String(localized: "Erneut klingeln")) {}
                    .buttonStyle(.bordered).controlSize(.small).disabled(true)
                    .opacity(0.45)                                          // Liste setzt die Schriftfarbe fest - deaktiviert sichtbar machen
                    .accessibilityLabel(Text("\(e.name): App zu alt – braucht eine neuere Saga-Version"))
                    .help(Text("App zu alt – braucht eine neuere Saga-Version"))
            }
        }
        .padding(.horizontal, 14).padding(.vertical, 8).frame(minHeight: 52, alignment: .leading)
        .accessibilityElement(children: .contain)
    }

    private func tap(_ f: InviteFocus, _ entries: [Invites.Entry]) {
        lastTap = f
        calls.inviteRing(entries)
        focus = .header                                                     // Knopf verschwindet (klingelt …)
    }
}

enum InviteFocus: Hashable { case header, all, row(String) }
