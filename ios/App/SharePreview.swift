#if DEBUG && os(iOS)
import SwiftUI

// Nur fuer Bilder (Debug-Build, ohne Server): das Teilen-Blatt (Shared/ShareView.swift) als Blatt ueber einer
// leeren Flaeche, mit erfundenen Kategorien. Start: -hkSharePreview bookmark|page|task [-hkShareDark YES]
enum SharePreview {
    static var mode: String? { UserDefaults.standard.string(forKey: "hkSharePreview") }
    static var on: Bool { mode != nil }
}

struct SharePreviewView: View {
    @State private var show = false
    var body: some View {
        Color(.systemGroupedBackground).ignoresSafeArea()
            .sheet(isPresented: $show) {
                ShareView(content: SharedContent(url: "https://example.org/recipes/sourdough", text: "", title: "Sourdough bread – step by step"),
                          categories: ["Kochen", "Lesen", "Werkzeuge"]) { _ in }
                    .presentationDetents([.medium, .large])
            }
            .preferredColorScheme(UserDefaults.standard.bool(forKey: "hkShareDark") ? .dark : .light)
            .task {
                if let m = SharePreview.mode { AppConfig.store.set(m, forKey: ShareBookmarkLogic.lastKindKey) }
                try? await Task.sleep(for: .seconds(0.5)); show = true
            }
    }
}
#endif
