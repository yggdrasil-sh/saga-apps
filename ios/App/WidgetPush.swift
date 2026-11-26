import Foundation
import WidgetKit

// 2.6 (Bugs-DB 2188): Widgets sofort aktuell. Der Server schickt nach Aenderungen (auch von anderen Geraeten) einen stillen
// Push ohne Inhalt (kind "widgets.update", content-available) - die App laedt dann alle Widgets neu. Damit der Server weiss,
// welche Seiten hier als Widget liegen, merken sich die Widgets ihr Ziel in der App-Gruppe (WidgetPushLogic.record) und die
// App meldet die Liste (PUT /api/app/widgets {pages, today}) - nur bei Aenderung und nur an Server mit sub_caps "widgets".
// Reine Logik: Shared/WidgetFeedLogic.swift (WidgetPushLogic), geprueft in Tests/Logic/WidgetFeedTests.swift.
@MainActor
enum WidgetPush {
    /// Haken fuer stille Mitteilungen (PushModel.backgroundHook, 2262): Widget-Push -> Widgets neu laden
    static func install() {
        PushModel.backgroundHook = { info in
            guard WidgetPushLogic.isWidgetPush(info) else { return false }
            WidgetCenter.shared.reloadAllTimelines()
            return true
        }
    }

    private static func sentKey(_ account: String) -> String { "hk.widgets.sent." + account }

    /// Eingerichtete Widgets dem Server melden. force: auch ohne Aenderung (nach einem neuen Abo).
    static func report(_ store: Store, force: Bool = false) async {
        guard !store.needsLogin, store.push.wanted, let st = store.push.state, st.subCaps.contains(WidgetPushLogic.cap),
              let acct = WidgetPushLogic.account(server: AppConfig.serverOrigin ?? "", token: AppConfig.token) else { return }
        let present: Set<String>? = (try? await WidgetCenter.shared.currentConfigurations()).map { Set($0.map(\.kind)) }
        let d = AppConfig.store
        var entries: [String: Double] = [:]
        for (k, v) in d.dictionaryRepresentation() where k.hasPrefix(WidgetPushLogic.regPrefix) {
            if let n = v as? Double { entries[k] = n } else if let n = v as? NSNumber { entries[k] = n.doubleValue }
        }
        let r = WidgetPushLogic.collect(entries, account: acct, present: present, now: .now)
        for k in r.stale { d.removeObject(forKey: k) }
        let fp = WidgetPushLogic.fingerprint(pages: r.pages, today: r.today)
        if !force && UserDefaults.standard.string(forKey: sentKey(acct)) == fp { return }
        guard let (_, code) = try? await store.apiResponse("api/app/widgets", method: "PUT",
                                                             body: WidgetPushLogic.body(pages: r.pages, today: r.today)),
              (200..<300).contains(code) else { return }
        UserDefaults.standard.set(fp, forKey: sentKey(acct))
    }
}
