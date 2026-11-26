import Foundation

/// Bugs-DB 2460: eigenes Logo eines Lesezeichens (Server 2.6, Bugs-DB 1530: icon = "/files/<24 hex>", hochladen nur im Web).
/// Im Blatt "Lesezeichen bearbeiten" steht es nie als Pfad im Feld Symbol, sondern als "Eigenes Logo" mit Vorschau;
/// Entfernen gibt das Feld frei. Unveraendert gespeichert geht icon genau so zurueck (der Server prueft es dann nicht neu).
enum BookmarkLogoLogic {
    /// wie LOGO_RE im Server (saga/dashboard.py): nur Kleinbuchstaben-Hex, genau 24 Zeichen
    static func isLogo(_ icon: String?) -> Bool {
        guard let i = icon, i.hasPrefix("/files/") else { return false }
        let id = i.dropFirst(7)
        return id.count == 24 && id.allSatisfy { ("0"..."9").contains($0) || ("a"..."f").contains($0) }
    }
    /// Startwerte des Blatts: (gesetztes Logo oder "", Inhalt des Feldes Symbol)
    static func split(_ icon: String?) -> (logo: String, symbol: String) {
        isLogo(icon) ? (icon!, "") : ("", icon ?? "")
    }
    /// Feld icon beim Speichern: gesetztes Logo unveraendert, sonst das Symbolfeld (leer = automatisch)
    static func iconToSave(logo: String, symbol: String) -> String {
        isLogo(logo) ? logo : symbol
    }
}
