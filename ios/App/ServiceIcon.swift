import SwiftUI

/// Symbol eines Dienstes (selfh.st wie die Kacheln im Web) - ueber den eigenen Server. iconURL (Server 2.5, Feld
/// icon_url in /api/services) ist die fertige Adresse, auch fuer automatische Symbole (geratenes selfh.st-Symbol oder
/// Favicon der Seite); aeltere Server: /api/services/icon/<name>. Fehlt es: Anfangsbuchstabe wie im Web.
/// 2.5 Lesezeichen: liefert der Server das Favicon nicht (z. B. Bot-Schutz), laedt das Geraet es der Reihe nach aus
/// iconDirect (Feld icon_direct, nur bei Lesezeichen) direkt von der Seite - FaviconLogic/FaviconLoader.
struct ServiceIcon: View {
    let icon: String?
    var iconURL: String? = nil
    var iconDirect: [String]? = nil
    var url: String = ""                                             // Adresse des Lesezeichens (Host-Pruefung)
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
        .task(id: (iconURL ?? "") + "|" + (icon ?? "") + "|" + (iconDirect ?? []).joined(separator: " ")) {
            let server = FaviconLogic.serverPath(iconURL: iconURL, icon: icon)
            let direct = FaviconLogic.directCandidates(iconDirect, bookmarkURL: url)
            if let server, let d = await store.imageData(server), let i = Self.decode(d) { img = i; return }
            guard !direct.isEmpty, !Task.isCancelled else { return }
            if let d = await FaviconLoader.shared.load(direct, decodes: { Self.decode($0) != nil }), let i = Self.decode(d) { img = i }
        }
    }
    private static func decode(_ d: Data) -> PlatformImage? {
        #if os(iOS)
        UIImage(data: d)
        #else
        NSImage(data: d)
        #endif
    }
}
