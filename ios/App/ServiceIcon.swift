import SwiftUI

/// Symbol eines Dienstes (selfh.st wie die Kacheln im Web) - ueber den eigenen Server (/api/services/icon/<name>),
/// fehlt es: Anfangsbuchstabe wie im Web.
struct ServiceIcon: View {
    let icon: String?
    let name: String
    var size: CGFloat = 30
    @EnvironmentObject var store: Store
    @State private var img: PlatformImage?
    @Environment(\.colorScheme) private var scheme
    var body: some View {
        Group {
            if let img {
                Group {
                    #if os(iOS)
                    Image(uiImage: img).resizable().scaledToFit()
                    #else
                    Image(nsImage: img).resizable().scaledToFit()
                    #endif
                }
                // Dunkelmodus: helle Plakette dahinter, sonst verschwinden dunkle Logos (z. B. schwarz auf schwarz)
                .padding(scheme == .dark ? size * 0.12 : 0)
                .background { if scheme == .dark { RoundedRectangle(cornerRadius: size * 0.24, style: .continuous).fill(.white.opacity(0.9)) } }
            } else {
                Text(String(name.prefix(1)).uppercased()).font(.system(size: size * 0.5, weight: .bold))   // Groesse folgt dem Symbol (fest)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(.secondary.opacity(0.15), in: RoundedRectangle(cornerRadius: size * 0.22))
            }
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)                                   // der Name steht daneben
        .task(id: icon) {
            guard let icon, !icon.isEmpty, let d = await store.imageData("/api/services/icon/" + icon) else { return }
            #if os(iOS)
            img = UIImage(data: d)
            #else
            img = NSImage(data: d)
            #endif
        }
    }
}
