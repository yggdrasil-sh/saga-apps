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

extension View {
    /// Hauptknopf in Gold: im Dunkelmodus dunkle Schrift auf hellem Gold (weiss waere zu kontrastarm)
    func prominentGold() -> some View {
        buttonStyle(.borderedProminent).foregroundStyle(Color("OnAccent"))
    }

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
