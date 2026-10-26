import SwiftUI

#if DEBUG
// Nur fuer Bilder (2.5, ohne Server): Einstellungen mit Beispieldaten.
//   -hkSettingsPreview start|konto|einstellungen|info   (Bereich wie -hkSettings)
enum SettingsPreview {
    static var mode: String? { UserDefaults.standard.string(forKey: "hkSettingsPreview") }
    static var name: String? { mode == nil ? nil : "Alex Beispiel" }
}

struct SettingsPreviewView: View {
    @ObservedObject var store: Store
    @State private var ready = false
    var body: some View {
        Group {
            if ready { SettingsView(onLogout: {}).environmentObject(store) }
            else { Color.clear }
        }
        .task {
            if let m = SettingsPreview.mode, m != "start" { UserDefaults.standard.set(m, forKey: "hkSettings") }
            else { UserDefaults.standard.removeObject(forKey: "hkSettings") }
            store.serverVersion = "2.5.0-rc.141"
            // Bekanntes Update im eigenen Kanal (Zwischenspeicher, keine Anfrage)
            let label = Store.appVersionLabel, ch = UpdateHint.channel(label) ?? "testing"
            let v = ch == "testing" ? "\(label.split(separator: "-").first ?? "2.5.0")-rc.999" : "9.9.9"
            let r = UpdateHint.Release(version: v, page: URL(string: "https://github.com/example/saga-apps/releases/tag/v\(v)"), assets: [:])
            if let d = UpdateHint.encode(.init(checked: Date(), release: r)) { UserDefaults.standard.set(d, forKey: "hk.updateHint.cache") }
            ready = true
        }
    }
}
#endif
