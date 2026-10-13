import SwiftUI

/// Symbol eines Dienstes (selfh.st wie die Kacheln im Web) - ueber den eigenen Server (/api/services/icon/<name>),
/// fehlt es: Anfangsbuchstabe wie im Web.
struct ServiceIcon: View {
    let icon: String?
    let name: String
    var size: CGFloat = 30
    @EnvironmentObject var store: Store
    @State private var img: PlatformImage?
    var body: some View {
        Group {
            if let img {
                #if os(iOS)
                Image(uiImage: img).resizable().scaledToFit()
                #else
                Image(nsImage: img).resizable().scaledToFit()
                #endif
            } else {
                Text(String(name.prefix(1)).uppercased()).font(.system(size: size * 0.5, weight: .bold))
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(.secondary.opacity(0.15), in: RoundedRectangle(cornerRadius: size * 0.22))
            }
        }
        .frame(width: size, height: size)
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
