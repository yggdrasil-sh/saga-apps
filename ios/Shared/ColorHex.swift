import SwiftUI

extension Color {
    init?(hex: String?) {
        guard let h = hex?.trimmingCharacters(in: CharacterSet(charactersIn: "#")), h.count == 6, let v = Int(h, radix: 16) else { return nil }
        self.init(red: Double((v >> 16) & 255) / 255, green: Double((v >> 8) & 255) / 255, blue: Double(v & 255) / 255)
    }
}

extension NSArray { subscript(safe i: Int) -> Any? { i >= 0 && i < count ? self[i] : nil } }

/// Farbnamen der Auswahl-Optionen (wie im Web)
func optColor(_ name: String?) -> Color {
    switch name {
    case "red": return .red; case "orange": return .orange; case "yellow": return .yellow; case "green": return .green
    case "blue": return .blue; case "purple": return .purple; case "pink": return .pink; case "brown": return .brown
    default: return .gray
    }
}
