import SwiftUI

/// Kontrollkaestchen (Checkliste, Datenbank). Gesperrt (Knopf .disabled: nur lesen, kommentieren, Seite gesperrt)
/// sichtbar gedimmt: Textfarbe mit 45 % Deckkraft - auch im Dunkeln gut erkennbar, nicht in Akzentfarbe.
struct CheckBoxIcon: View {
    let on: Bool
    @Environment(\.isEnabled) private var enabled
    var body: some View {
        Image(systemName: on ? "checkmark.square.fill" : "square")
            .foregroundStyle(!enabled ? AnyShapeStyle(Color.primary.opacity(0.45)) : on ? AnyShapeStyle(Color.accentColor) : AnyShapeStyle(.secondary))
    }
}

extension Color {
    /// Saga-Gold aus dem Asset-Katalog. Am Mac folgt Color.accentColor der Systemfarbe (z. B. Graphit) - die eigene Blase,
    /// Ungelesen-Punkte und Codes sollen aber ueberall gleich golden sein.
    static let gold = Color("AccentColor")

    /// Feste Farbe je Erscheinungsbild (hell/dunkel) aus 0xRRGGBB
    static func adaptive(_ light: UInt32, _ dark: UInt32) -> Color {
        func c(_ v: UInt32) -> (Double, Double, Double) { (Double((v >> 16) & 255) / 255, Double((v >> 8) & 255) / 255, Double(v & 255) / 255) }
        let l = c(light), d = c(dark)
        #if os(macOS)
        return Color(nsColor: NSColor(name: nil) { a in
            let x = a.bestMatch(from: [.darkAqua, .aqua]) == .darkAqua ? d : l
            return NSColor(srgbRed: x.0, green: x.1, blue: x.2, alpha: 1)
        })
        #else
        return Color(uiColor: UIColor { t in
            let x = t.userInterfaceStyle == .dark ? d : l
            return UIColor(red: x.0, green: x.1, blue: x.2, alpha: 1)
        })
        #endif
    }
}

/// Mac: Hauptknopf in Gold, der auch im inaktiven Fenster lesbar bleibt (dort nimmt .borderedProminent den Goldgrund weg,
/// die erzwungene OnAccent-Schrift stand dann weiss auf Hellgrau). Gesperrt: neutraler Grund, Sekundaerschrift.
struct GoldButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var enabled
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .padding(.horizontal, 12).padding(.vertical, 5)
            .foregroundStyle(enabled ? Color("OnAccent") : Color.secondary)
            .background(RoundedRectangle(cornerRadius: 6, style: .continuous)
                .fill(enabled ? Color.gold.opacity(configuration.isPressed ? 0.8 : 1) : Color.secondary.opacity(0.18)))
            .contentShape(Rectangle())
    }
}

/// iOS: .borderedProminent in Gold; OnAccent-Schrift nur, solange der Knopf bedienbar ist (gesperrt stellt das System Grau dar)
private struct ProminentGold: ViewModifier {
    @Environment(\.isEnabled) private var enabled
    func body(content: Content) -> some View {
        #if os(macOS)
        content.buttonStyle(GoldButtonStyle())
        #else
        if enabled { content.buttonStyle(.borderedProminent).tint(Color.gold).foregroundStyle(Color("OnAccent")) }
        else { content.buttonStyle(.borderedProminent).tint(Color.gold) }
        #endif
    }
}

extension View {
    /// Hauptknopf in Gold: im Dunkelmodus dunkle Schrift auf hellem Gold (weiss waere zu kontrastarm)
    func prominentGold() -> some View { modifier(ProminentGold()) }

    /// Mindestgroesse nur fuer Mac-Fenster und -Blaetter. Auf iPhone/iPad bestimmt das System die Groesse
    /// (feste Mac-Breiten wuerden dort abgeschnitten).
    @ViewBuilder
    func macFrame(minWidth: CGFloat? = nil, idealWidth: CGFloat? = nil, minHeight: CGFloat? = nil, idealHeight: CGFloat? = nil) -> some View {
        #if os(macOS)
        frame(minWidth: minWidth, idealWidth: idealWidth, minHeight: minHeight, idealHeight: idealHeight)
        #else
        self
        #endif
    }

    /// Mehrere Knoepfe in EINER List-/Form-Zeile: iOS loest bei Knoepfen im Standardstil sonst alle Aktionen der Zeile
    /// gleichzeitig aus (Papierkorb: "Zurueckholen" loeschte zusaetzlich endgueltig). Borderless macht jeden Knopf
    /// einzeln tippbar; Knoepfe mit eigenem Stil behalten ihn. Am Mac unveraendert (dort tritt der Fehler nicht auf).
    /// Pruefung im Quelltext: RowButtonLint (Logik-Tests).
    @ViewBuilder
    func rowButtons() -> some View {
        #if os(iOS)
        buttonStyle(.borderless)
        #else
        self
        #endif
    }

    /// Feste Breite nur am Mac (z. B. Befehlspalette); sonst volle Breite
    @ViewBuilder
    func macWidth(_ w: CGFloat) -> some View {
        #if os(macOS)
        frame(width: w)
        #else
        frame(maxWidth: .infinity)
        #endif
    }
}

/// Nur fuer Bilder (Debug, -hkDisplayServer https://saga.example.org): Adresse des Testservers in Anzeigen ersetzen
enum ShotDisplay {
    static func server(_ s: String) -> String {
        #if DEBUG
        if let d = UserDefaults.standard.string(forKey: "hkDisplayServer") {
            return s.replacingOccurrences(of: AppConfig.serverString.trimmingCharacters(in: CharacterSet(charactersIn: "/")), with: d)
        }
        #endif
        return s
    }
}
