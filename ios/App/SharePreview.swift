#if DEBUG && os(iOS)
import SwiftUI

// Nur fuer Bilder (Debug-Build, ohne Server): das Teilen-Blatt (Shared/ShareView.swift) als Blatt ueber einer
// leeren Flaeche, mit erfundenen Kategorien. Start: -hkSharePreview bookmark|page|task [-hkShareDark YES]
// 2.6 K-G (2275): -hkSharePreview job (Option "{Name} soll sich kümmern …") | jobdone (Bestaetigung) | jobaus (ohne Freigabe)
enum SharePreview {
    static var mode: String? { UserDefaults.standard.string(forKey: "hkSharePreview") }
    static var on: Bool { mode != nil }
    static var job: Bool { mode?.hasPrefix("job") == true }
}

struct SharePreviewView: View {
    @State private var show = false
    var body: some View {
        Color(.systemGroupedBackground).ignoresSafeArea()
            .sheet(isPresented: $show) {
                ShareView(content: SharePreview.job
                            ? SharedContent(url: "https://www.schule.example/termine", text: "Elternabend der Klasse 3b am 7.7. um 19:30 in der Aula.", title: "Termine 2026")
                            : SharedContent(url: "https://example.org/recipes/sourdough", text: "", title: "Sourdough bread – step by step"),
                          categories: ["Kochen", "Lesen", "Werkzeuge"], jobSaved: SharePreview.mode == "jobdone" ? true : nil) { _ in }
                    .presentationDetents([.medium, .large])
            }
            .preferredColorScheme(UserDefaults.standard.bool(forKey: "hkShareDark") ? .dark : .light)
            .task {
                if let m = SharePreview.mode, !SharePreview.job { AppConfig.store.set(m, forKey: ShareBookmarkLogic.lastKindKey) }
                if SharePreview.job {                                        // 2275: Schalter wie von der App gesetzt (erfundener Name)
                    AppConfig.store.set("page", forKey: ShareBookmarkLogic.lastKindKey)
                    ShareDraftLogic.setJobs(SharePreview.mode != "jobaus", name: "Marin", in: AppConfig.store)
                }
                try? await Task.sleep(for: .seconds(0.5)); show = true
            }
    }
}
#endif
