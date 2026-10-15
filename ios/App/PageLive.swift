import SwiftUI

// Seitenkopf-Zusaetze fuer Saga 2.0: Anwesenheit (Live), Hinweis auf fremde Aenderungen, Seite einer anderen
// Saga-Instanz (Foederation), Start des KI-Assistenten.

extension Notification.Name {
    /// Einstellungen oeffnen (object: Abschnitt, z. B. "konto")
    static let hkOpenSettings = Notification.Name("hk.openSettings")
}

/// Aufruf des Assistenten aus der Seite (Reiter, Aktion, markierte Bloecke)
struct AssistantLaunch: Identifiable, Equatable {
    let id = UUID()
    var tab: Int
    var action: String?
    var selected: [String]
}

/// Klartext (Vorschlag des Assistenten) -> Inline-HTML des Editors
func htmlText(_ s: String) -> String {
    s.replacingOccurrences(of: "&", with: "&amp;").replacingOccurrences(of: "<", with: "&lt;").replacingOccurrences(of: ">", with: "&gt;")
        .replacingOccurrences(of: "\n", with: "<br>")
}

/// "Gerade auf dieser Seite": Anfangsbuchstaben + Satz (dezent)
struct PresenceBar: View {
    let users: [PresenceUser]
    var body: some View {
        HStack(spacing: 8) {
            HStack(spacing: -6) {
                ForEach(users.prefix(4)) { u in
                    Text(verbatim: LiveLogic.initials(u.name)).font(.caption2.weight(.semibold))
                        .frame(width: 24, height: 24)
                        .background(Circle().fill(u.editing ? Color.accentColor.opacity(0.35) : Color.secondary.opacity(0.2)))
                        .overlay(Circle().strokeBorder(.background, lineWidth: 1.5))
                }
            }
            .accessibilityHidden(true)
            Text(verbatim: line).font(.caption).foregroundStyle(.secondary).lineLimit(1)
        }
        .help(Text("Gerade auf dieser Seite"))
        .accessibilityElement(children: .combine)
        .accessibilityLabel(Text(verbatim: String(localized: "Gerade auf dieser Seite") + ": " + line))
    }
    private var line: String {
        switch LiveLogic.presenceLine(users) {
        case .one(let n, let e)?: return e ? String(localized: "\(n) bearbeitet gerade") : String(localized: "\(n) ist auch hier")
        case .many(let n, let e)?: return e > 0 ? String(localized: "\(n) Personen hier, \(e) bearbeiten") : String(localized: "\(n) Personen sind auch hier")
        case nil: return ""
        }
    }
}

/// Eigene ungespeicherte Eingaben, waehrend jemand anderes gespeichert hat
struct ChangedBanner: View {
    let name: String
    let reload: () -> Void
    var body: some View {
        HStack(spacing: 10) {
            Label("\(name) hat diese Seite geändert", systemImage: "person.crop.circle.badge.exclamationmark").font(.footnote)
            Spacer(minLength: 0)
            Button("Neu laden", action: reload).buttonStyle(.bordered).controlSize(.small)
        }
        .padding(10).background(Color.orange.opacity(0.12), in: RoundedRectangle(cornerRadius: 10))
    }
}

/// Seite liegt auf einem anderen Saga-Server (Foederation)
struct RemotePageBanner: View {
    let host: String
    var body: some View {
        Label {
            Text("Diese Seite liegt auf \(host.isEmpty ? String(localized: "einer anderen Saga-Instanz") : host). Hier kannst du sie lesen, kommentieren und – mit Bearbeiten – Titel und Inhalt ändern.")
        } icon: { Image(systemName: "point.3.connected.trianglepath.dotted") }
        .font(.footnote).foregroundStyle(.secondary)
        .padding(10).frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.secondary.opacity(0.08), in: RoundedRectangle(cornerRadius: 10))
    }
}

extension Store {
    /// KI-Assistent fuer mich an? (GET /api/ai/status; Admin kann umschalten -> beim Aktualisieren neu)
    func loadAIStatus() async {
        guard let (d, code) = try? await apiResponse("api/ai/status") else { return }
        let on = code == 200 && (AIStatus(data: d)?.enabled ?? false)
        if on != aiEnabled { aiEnabled = on }
    }
}
