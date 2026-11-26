import Foundation
import UserNotifications

// 2270 (Saga 2.6, K-C, Feature jobpush1): Mitteilungen der Kollegin zu Auftraegen - Push-Nutzlast 3. Vertrag im Server-Repo:
// docs/APP-API.md "Push-Nutzlast 3 und Auftraege", docs/APP-API-KI.md Abschnitt 12, Vektoren tests/vectors/push_kollegin.json
// (Kopie: Tests/vectors/push_kollegin.json). Reine Logik ohne UIKit - gemeinsam fuer die Notification Service Extension
// (Notify/), die App (Push.swift, JobPush.swift) und den Logic-Runner (Tests/Logic/PushJobTests.swift).
//
// - Anzeige: mit `detail` Titel = t1, Text = t2 (baut der Server in der Abo-Sprache); sonst neutral title/body.
// - Kategorie waehlt die App aus `state` und den mitgeschickten Token (`actions[].a`) - das Relais erlaubt keine `category`
//   im aps. iOS-Kategorien sind feste Knopf-Saetze: fehlt ein Token, nimmt die NSE eine Variante ohne diesen Knopf.
// - K15: approve/undo nur mit Entsperren (.authenticationRequired), nur mit sichtbarem Inhalt und nur auf Geraeten mit
//   Code; `open` immer; kein Textfeld.

enum PushJobLogic {
    /// Arten mit Mitteilung (CATS["jobs"]) und die stille Aktualisierung
    static let kinds: Set<String> = ["job.needs_you", "job.done", "job.failed"]
    static let updateKind = "job.changed"
    static func isJob(_ kind: String?) -> Bool { kind.map(kinds.contains) ?? false }
    static func isJobKind(_ kind: String?) -> Bool { isJob(kind) || kind == updateKind }
    /// Bereich in PUT /api/push cats
    static let cat = "jobs"

    /// Knopf-Aktionen (Vertrag: approve, undo, later, quiet, open) - Kennung der iOS-Aktion: "saga.job.<a>"
    static let actions = ["approve", "undo", "later", "quiet", "open"]
    static let authActions: Set<String> = ["approve", "undo"]
    static let quietActions: Set<String> = ["later", "quiet"]
    static let actionPrefix = "saga.job."
    static func actionID(_ a: String) -> String { actionPrefix + a }
    static func action(fromID id: String) -> String? {
        guard id.hasPrefix(actionPrefix) else { return nil }
        let a = String(id.dropFirst(actionPrefix.count))
        return actions.contains(a) ? a : nil
    }

    /// Kategorien (alle Plattformen gleich) mit Knoepfen in Reihenfolge
    static let categories: [(id: String, buttons: [String])] = [
        ("saga.job.needs", ["open", "later"]),
        ("saga.job.needs.ok", ["approve", "open", "later"]),
        ("saga.job.done", ["undo", "quiet"]),
        ("saga.job.done.plain", ["quiet"]),
        ("saga.job.failed", ["open"]),
    ]
    static func buttons(of category: String) -> [String] { categories.first { $0.id == category }?.buttons ?? [] }

    /// Gruppe aus `state` (bzw. aus der Art, falls ein Feld fehlt)
    static func group(state: String?, kind: String?) -> String? {
        switch state ?? "" {
        case "needs", "done", "failed": return state
        default: break
        }
        switch kind ?? "" {
        case "job.needs_you": return "needs"
        case "job.done": return "done"
        case "job.failed": return "failed"
        default: return nil
        }
    }

    /// Kategorie aus Gruppe und vorhandenen Token
    static func category(group: String, tokens: Set<String>) -> String {
        switch group {
        case "needs": return tokens.contains("approve") ? "saga.job.needs.ok" : "saga.job.needs"
        case "done": return tokens.contains("undo") ? "saga.job.done" : "saga.job.done.plain"
        default: return "saga.job.failed"
        }
    }

    /// K15 (Vektoren `k15`): Knoepfe einer Kategorie je Geraet (phone, phone_noauth, watch, car, web) und Inhalt sichtbar.
    /// `auth` = nur nach Entsperren. Gesperrt direkt bedienbar: `direct(...)`.
    static func k15(category: String, device: String, content: Bool) -> [(a: String, auth: Bool)] {
        let all = buttons(of: category)
        var out: [String]
        switch device {
        case "phone": out = content ? all : all.filter { !authActions.contains($0) }
        case "watch", "car": out = all.filter(quietActions.contains)
        case "web":
            out = all.filter { quietActions.contains($0) || $0 == "open" }
            if !out.contains("open") { out.append("open") }
        default: out = all.filter { !authActions.contains($0) }                 // phone_noauth: Geraet ohne Code
        }
        return out.map { ($0, authActions.contains($0)) }
    }
    static func direct(_ b: [(a: String, auth: Bool)], locked: Bool) -> [String] { b.filter { !locked || !$0.auth }.map(\.a) }

    /// Knoepfe, die wirklich erscheinen: K15 und nur mit Token (`open` immer)
    static func shownButtons(category: String, tokens: Set<String>, device: String = "phone", content: Bool = true) -> [String] {
        k15(category: category, device: device, content: content).map(\.a).filter { $0 == "open" || tokens.contains($0) }
    }

    /// iOS: feste Knopf-Saetze je Kategorie - voller Satz = Kennung der Kategorie, sonst "<kategorie>~<a>-<b>" ("~" = keiner)
    static func iosCategory(_ category: String, buttons: [String]) -> String {
        buttons == self.buttons(of: category) ? category : category + "~" + buttons.joined(separator: "-")
    }
    /// Alle iOS-Kategorien zum Anmelden (setNotificationCategories): je Kategorie alle Teilmengen der Token-Knoepfe,
    /// `open` bleibt; approve/undo bleiben drin, wenn die Kategorie sie traegt (sonst waere es die andere Kategorie) -
    /// ausser bei verborgenem Inhalt bzw. Geraet ohne Code, dann ohne sie.
    static func iosCategories() -> [(id: String, buttons: [String])] {
        var out: [(String, [String])] = []
        var seen = Set<String>()
        for c in categories {
            let tok = c.buttons.filter { $0 != "open" }
            for mask in 0..<(1 << tok.count) {
                let keep = Set(tok.enumerated().filter { mask & (1 << $0.offset) != 0 }.map(\.element))
                let b = c.buttons.filter { $0 == "open" || keep.contains($0) }
                let id = iosCategory(c.id, buttons: b)
                if seen.insert(id).inserted { out.append((id, b)) }
            }
        }
        return out
    }

    /// Token der Mitteilung: actions [{a, t}] -> [a: t] (nur bekannte Aktionen, nicht leer)
    static func tokens(_ inner: [String: Any]) -> [String: String] {
        var out: [String: String] = [:]
        for x in inner["actions"] as? [Any] ?? [] {
            guard let d = x as? [String: Any], let a = d["a"] as? String, let t = d["t"] as? String,
                  actions.contains(a), a != "open", !t.isEmpty, t.count <= 64 else { continue }
            out[a] = t
        }
        return out
    }
    static func int(_ v: Any?) -> Int? {
        if let n = v as? NSNumber { return n.intValue }
        if let s = v as? String { return Int(s) }
        return nil
    }

    /// Was die NSE zeigt
    struct Shown: Equatable {
        var title: String
        var body: String
        var thread: String
        var tag: String
        var sound: Bool
        var category: String
        var iosCategory: String
        var buttons: [String]
        var job: Int?
        var deeplink: String
        /// detail wurde gezeigt (t1/t2)
        var detail: Bool
    }

    /// Anzeige einer job.*-Mitteilung (innere Felder). `previews`: Vorschau des Systems nicht "nie"; `deviceAuth`: Geraet
    /// hat einen Code (sonst nie approve/undo). nil = keine job.*-Mitteilung.
    static func shown(_ inner: [String: Any], previews: Bool = true, deviceAuth: Bool = true) -> Shown? {
        let kind = inner["kind"] as? String
        guard isJob(kind), let g = group(state: inner["state"] as? String, kind: kind) else { return nil }
        let d = inner["detail"] as? [String: Any]
        let t1 = (d?["t1"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        let t2 = (d?["t2"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        let useDetail = previews && !t1.isEmpty
        let tok = Set(tokens(inner).keys)
        let cat = category(group: g, tokens: tok)
        let content = useDetail
        let b = shownButtons(category: cat, tokens: tok, device: deviceAuth ? "phone" : "phone_noauth", content: content)
        let tag = (inner["tag"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? "saga-job"
        let thread = (inner["thread"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? tag
        let job = int(inner["job"]).flatMap { $0 > 0 ? $0 : nil }
        return Shown(title: useDetail ? t1 : inner["title"] as? String ?? "", body: useDetail ? t2 : inner["body"] as? String ?? "",
                     thread: thread, tag: tag, sound: !flag(inner["silent"]), category: cat, iosCategory: iosCategory(cat, buttons: b),
                     buttons: b, job: job, deeplink: deeplink(job: job), detail: useDetail)
    }
    static func flag(_ v: Any?) -> Bool {
        if let b = v as? Bool { return b }
        if let n = v as? NSNumber { return n.intValue != 0 }
        if let s = v as? String { return s == "1" || s == "true" }
        return false
    }

    /// Stille Aktualisierung job.changed mit update+removed: Mitteilung mit diesem tag schliessen -> tag, sonst nil
    static func removeTag(_ info: [AnyHashable: Any]) -> String? {
        guard info["kind"] as? String == updateKind, flag(info["update"]), flag(info["removed"]),
              let t = info["tag"] as? String, !t.isEmpty else { return nil }
        return t
    }

    /// userInfo der gezeigten Mitteilung: nur einfache Werte (PushLogic.plainInfo behaelt nur Text/Zahl) - Token als
    /// "atok.<a>", ohne detail (Inhalt steht schon in Titel/Text)
    static func flatInfo(_ inner: [String: Any]) -> [String: Any] {
        var out: [String: Any] = [:]
        for (k, v) in inner where k != "actions" && k != "detail" && (v is String || v is NSNumber) { out[k] = v }
        for (a, t) in tokens(inner) { out["atok." + a] = t }
        return out
    }

    /// Deep-Link (APP-API-HOST.md Abschnitt 6)
    static func deeplink(job: Int?) -> String { job.map { "saga://job/\($0)" } ?? "saga://jobs" }
    /// Web-Route eines Auftrags
    static func route(job: Int?) -> String { job.map { "/saga?tab=jobs&job=\($0)" } ?? "/saga?tab=jobs" }
    /// "/saga?tab=jobs&job=42" -> (true, 42); "/saga?tab=jobs" -> (true, nil); sonst nil. Nur Ziffern als Kennung.
    static func jobTarget(url: String?) -> (jobs: Bool, job: Int?)? {
        guard let u = url, u.hasPrefix("/saga"), let c = URLComponents(string: u), c.path == "/saga" else { return nil }
        let q = c.queryItems ?? []
        guard q.first(where: { $0.name == "tab" })?.value == "jobs" else { return nil }
        let j = q.first(where: { $0.name == "job" })?.value
        let id = j.flatMap { s in (1...12).contains(s.count) && s.allSatisfy { $0.isASCII && $0.isNumber } ? Int(s) : nil }
        return (true, id)
    }
    /// saga://job/<id> bzw. saga://jobs -> Web-Route; sonst nil
    static func linkRoute(host: String?, arg: String?) -> String? {
        switch (host ?? "").lowercased() {
        case "jobs": return route(job: nil)
        case "job":
            let a = arg ?? ""
            return (1...12).contains(a.count) && a.allSatisfy({ $0.isASCII && $0.isNumber }) ? route(job: Int(a)) : route(job: nil)
        default: return nil
        }
    }

    /// Antwort von POST /api/ai/jobs/{id}/act -> was die App tut (Vektoren tokens.client)
    enum Outcome: String { case remove, hint = "hint_open", openApp = "open_app" }
    /// status nil = Netzfehler (nach einer Wiederholung)
    static func outcome(status: Int?) -> Outcome {
        switch status {
        case 200?, 404?: return .remove
        case 403?, 401?: return .openApp
        default: return .hint                                                  // 409, 410, Netz, 5xx
        }
    }
    /// Einmal wiederholen nur bei Netzfehlern (kein Status)
    static func retry(status: Int?, attempt: Int) -> Bool { status == nil && attempt == 0 }
    /// Koerper fuer act (ohne Token fuer `open`)
    static func actBody(action: String, info: [AnyHashable: Any]) -> [String: Any]? {
        guard action != "open", let t = info["atok." + action] as? String, !t.isEmpty else { return nil }
        var b: [String: Any] = ["action": action, "atok": t]
        if let pv = int(info["pv"]) { b["pv"] = pv }
        return b
    }

    /// Abo-caps (iOS): "widgets" (2188) und ab Nutzlast 3 "detail", "actions" - nur, was der Server anbietet; nie "update"
    static func subscriptionCaps(serverCaps: [String]) -> [String] {
        ["widgets", "detail", "actions"].filter(serverCaps.contains)
    }

    /// Einstellungen: "Ruhe bis 07:00" aktiv? (notify_quiet_until in der Zukunft)
    static func quietActive(until: Double?, now: Double) -> Bool { (until ?? 0) > now }
}

/// Schluessel der Knopf- und Hinweistexte in Shared/Localizable.xcstrings (Werte = Vektoren texts[lang])
enum JobTexts {
    static func key(_ action: String) -> String { "SAGA_JOB_" + action.uppercased() }
    static let hintKey = "SAGA_JOB_HINT"
}

extension PushJobLogic {
    /// Text eines Knopfs in der App-Sprache (Vertragstexte, Localizable.xcstrings SAGA_JOB_*)
    static func title(_ a: String) -> String { String(localized: String.LocalizationValue(JobTexts.key(a))) }
    /// Alle Auftrags-Kategorien (Varianten je vorhandene Token, iosCategories). approve/undo nur nach dem Entsperren, ohne
    /// die App zu oeffnen; "Ansehen" oeffnet Saga; "Spaeter"/"Gerade nicht" im Hintergrund. Kein Textfeld.
    static func notificationCategories() -> Set<UNNotificationCategory> {
        let titles = Dictionary(uniqueKeysWithValues: actions.map { ($0, title($0)) })
        return Set(iosCategories().map { c in
            let acts = c.buttons.map { a -> UNNotificationAction in
                let opt: UNNotificationActionOptions = a == "open" ? [.foreground] : (authActions.contains(a) ? [.authenticationRequired] : [])
                return UNNotificationAction(identifier: actionID(a), title: titles[a] ?? a, options: opt)
            }
            return UNNotificationCategory(identifier: c.id, actions: acts, intentIdentifiers: [], options: [])
        })
    }
}
