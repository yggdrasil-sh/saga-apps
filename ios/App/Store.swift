import Foundation
import SwiftUI
import WebKit
import CryptoKit
#if canImport(WidgetKit)
import WidgetKit
#endif
#if os(iOS)
import UIKit
#else
import AppKit
#endif

struct Page: Decodable, Identifiable, Hashable {
    let id: Int
    var title: String
    var icon: String?
    var parent_id: Int?
    var kind: String?
    var pinned: Bool?
    var updated: Double?
    var snippet: String?
    var archived: Bool?
    var template: Bool?
    var tags: [String]?
    // Teilen (1.3): eigene Seiten mit Freigaben (shared), neue Kommentare (unread); in "Mit mir geteilt" dazu
    // Rolle, Eigentuemer und root (oberste geteilte Seite)
    var shared: Bool?
    var unread: Bool?
    var role: String?
    var root: Bool?
    var owner: PageOwner?
    /// Seite einer anderen Saga-Instanz (Foederation, 2.0): {peer, host, name}; die ID ist dann negativ
    var remote: RemoteRef?
    var label: String { title.isEmpty ? String(localized: "Unbenannt") : title }
    var isDatabase: Bool { kind == "database" }
    var isRemote: Bool { remote != nil || id < 0 }
}
struct PageOwner: Decodable, Hashable { var name: String?; var username: String?; var handle: String?; var remote: Bool?; var avatar: String? }

struct OpenTask: Decodable, Identifiable, Hashable {
    let id: Int; var text: String; var due: String?; var prio: Int?; var rep: String?; var done: Bool?
    enum CodingKeys: String, CodingKey { case id, text, due, prio, rep = "repeat", done }
}
struct CalEvent: Decodable, Identifiable, Hashable {
    var summary: String; var start: String; var end: String; var allday: Bool
    var location: String?; var description: String?; var color: String?; var calendar: String?
    var href: String?; var etag: String?; var uid: String?; var series: Bool?; var writable: Bool?; var account: Int?; var cal: String?
    var id: String { (href ?? "") + start }
}
struct CalTarget: Identifiable, Hashable { let account: Int; let cal: String; let name: String; let color: String?; var id: String { "\(account)|\(cal)" } }

/// Native Daten fuer den App-Rahmen. Nutzt die ANMELDUNG DER WEB-ANSICHT (Sitzungs-Cookie aus dem gemeinsamen
/// WKWebsiteDataStore) - kein zusaetzlicher Schluessel noetig. Laeuft sie ab -> needsLogin.
@MainActor
final class Store: ObservableObject {
    @Published var pages: [Page] = []
    @Published var needsLogin = false
    /// Anmeldebildschirm abgebrochen (Server bekannt): Zwischenspeicher zeigen, Anmelden ueber den Hinweis oben
    @Published var loginPostponed = false
    @Published var pendingPair: PairRequest?               // Kopplungslink wartet auf Bestaetigung
    @Published var serverVersion: String?                // aus /api/version
    @Published var minApp: String?                       // aelteste App-Version, die der Server voll unterstuetzt
    var appTooOld: Bool { minApp.map { Store.versionLess(Store.appVersion, $0) } ?? false }
    nonisolated static var appVersion: String { Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "0" }
    /// Angezeigte Version: "2.2.0" im offiziellen Release, sonst "2.2.0-rc.N" (Info.plist SagaVersionLabel, tools/version-label.sh).
    /// Vergleiche (minApp) laufen weiter ueber die numerische appVersion.
    nonisolated static var appVersionLabel: String {
        let l = (Bundle.main.infoDictionary?["SagaVersionLabel"] as? String ?? "").trimmingCharacters(in: .whitespaces)
        return l.isEmpty || l.hasPrefix("$(") ? appVersion : l
    }
    nonisolated static var appBuild: String { Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "" }
    /// "1.2.0" < "1.10.0" (SemVer, Zusaetze wie -beta werden ignoriert)
    nonisolated static func versionLess(_ a: String, _ b: String) -> Bool {
        func p(_ s: String) -> [Int] {
            let core = s.trimmingCharacters(in: CharacterSet(charactersIn: "vV ")).split(separator: "-").first.map(String.init) ?? ""
            let n = core.split(separator: ".").prefix(3).map { Int($0) ?? 0 }
            return n + Array(repeating: 0, count: max(0, 3 - n.count))
        }
        return p(a).lexicographicallyPrecedes(p(b))
    }
    /// Server-Version abfragen (oeffentlich, keine Anmeldung noetig); aeltere Server ohne /api/version: nichts anzeigen
    func checkVersion() async {
        guard AppConfig.hasServer, let url = URL(string: AppConfig.server.absoluteString.trimmingCharacters(in: CharacterSet(charactersIn: "/")) + "/api/version"),
              let res = try? await URLSession.shared.data(from: url), (res.1 as? HTTPURLResponse)?.statusCode == 200,
              let j = (try? JSONSerialization.jsonObject(with: res.0)) as? [String: Any] else { serverVersion = nil; minApp = nil; return }
        serverVersion = j["version"] as? String; minApp = j["min_app"] as? String
    }
    @Published var loading = false
    /// Eigenes Profilbild (2.1, aus /api/account): relative Adresse oder nil
    @Published var myAvatar: String?
    @Published var error: String?
    @Published var tasks: [OpenTask] = []
    @Published var doneTasks: [OpenTask] = []
    @Published var events: [CalEvent] = []
    @Published var recent: [Int] = UserDefaults.standard.array(forKey: "recent") as? [Int] ?? []
    private var cookieHeader: [String: String]?
    private var cookieAt = Date.distantPast
    private let images = NSCache<NSString, NSData>()
    private let cacheDir: URL = {
        let d = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("hk", isDirectory: true)
        try? FileManager.default.createDirectory(at: d.appendingPathComponent("files"), withIntermediateDirectories: true)
        return d
    }()

    // Saga 2.0: Live-Kanal + Benachrichtigungen, Chat, Mitteilungen (Push) - eigene Modelle, die den Store benutzen
    let live = LiveHub()
    let notifs = NotificationsModel()
    let chat = ChatModel()
    let push = PushModel()
    let calls = CallManager()                                        // Anrufe (2.1)

    init() {                                                        // sofort aus dem Zwischenspeicher
        // einmalig nach dem Update: alte Entwuerfe (nur je Server) gehoeren zur aktuell bestehenden Anmeldung
        DraftOwner.app.migrate(origin: AppConfig.serverOrigin, signedIn: DeviceKey.get() != nil || AppConfig.hasToken)
        if let d = cachedData("api/notes"), let p = try? JSONDecoder().decode([Page].self, from: d) { setPages(p) }
        live.store = self; notifs.store = self; chat.store = self; push.store = self
        AvatarCache.shared.store = self
        calls.store = self; CallSystem.shared.manager = calls
    }

    /// Anfrage mit Sitzung fuer Stroeme (SSE: Live-Kanal, KI-Assistent). Abgelaufene Sitzung (401): einmal still mit dem
    /// Geraete-Schluessel erneuern und wiederholen. Status != 2xx: Antwort-Text (JSON {error}) mitliefern.
    func openStream(_ path: String, method: String = "GET", body: [String: Any]? = nil, headers: [String: String] = [:],
                    session: URLSession) async throws -> (URLSession.AsyncBytes, Int, Data?) {
        guard AppConfig.hasServer, let url = URL(string: AppConfig.server.absoluteString.trimmingCharacters(in: CharacterSet(charactersIn: "/")) + "/" + path)
        else { throw APIError.other(String(localized: "Ungültige Adresse")) }
        for attempt in 0..<2 {
            var req = URLRequest(url: url, timeoutInterval: 90)
            req.httpMethod = method; req.httpShouldHandleCookies = false
            req.setValue("1", forHTTPHeaderField: "X-Hlidskjalf"); req.setValue("1", forHTTPHeaderField: "X-Saga")
            req.setValue("text/event-stream", forHTTPHeaderField: "Accept")
            req.setValue(AppConfig.appLanguage, forHTTPHeaderField: "Accept-Language")
            for (k, v) in headers { req.setValue(v, forHTTPHeaderField: k) }
            if let body { req.setValue("application/json", forHTTPHeaderField: "Content-Type"); req.httpBody = try JSONSerialization.data(withJSONObject: body) }
            for (k, v) in await cookies(for: url) { req.setValue(v, forHTTPHeaderField: k) }
            let (bytes, resp) = try await session.bytes(for: req)
            let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
            if code == 401 && attempt == 0 {
                cookieHeader = nil
                if await resumeSession() == .ok { continue }
            }
            guard (200..<300).contains(code) else {
                var d = Data()
                for try await b in bytes { d.append(b); if d.count > 65_536 { break } }
                return (bytes, code, d)
            }
            return (bytes, code, nil)
        }
        throw APIError.http(401, String(localized: "Bitte anmelden"))
    }

    /// Rohdaten senden (verschluesselte Chat-Anhaenge: application/octet-stream)
    func apiRaw(_ path: String, method: String = "POST", data: Data, contentType: String = "application/octet-stream") async throws -> Data {
        guard AppConfig.hasServer, let url = URL(string: AppConfig.server.absoluteString.trimmingCharacters(in: CharacterSet(charactersIn: "/")) + "/" + path)
        else { throw APIError.other(String(localized: "Ungültige Adresse")) }
        var req = URLRequest(url: url, timeoutInterval: 120)
        req.httpMethod = method; req.httpShouldHandleCookies = false
        req.setValue("1", forHTTPHeaderField: "X-Hlidskjalf"); req.setValue(contentType, forHTTPHeaderField: "Content-Type")
        let (d, code) = try await send(req) { try await URLSession.shared.upload(for: $0, from: data) }
        guard (200..<300).contains(code) else {
            throw APIError.http(code, (try? JSONSerialization.jsonObject(with: d) as? [String: Any])?["error"] as? String ?? "")
        }
        return d
    }

    private func cacheURL(_ path: String) -> URL { cacheDir.appendingPathComponent(path.replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "?", with: "_")) }
    func cachedData(_ path: String) -> Data? { try? Data(contentsOf: cacheURL(path)) }
    /// Zwischenspeicher einer Antwort verwerfen (z. B. Freigabe entzogen: fremder Inhalt nicht offline lesbar lassen)
    func dropCache(_ path: String) { try? FileManager.default.removeItem(at: cacheURL(path)) }
    /// GET mit Speichern im Zwischenspeicher (fuer sofortige Anzeige beim naechsten Mal)
    func get(_ path: String) async throws -> Data {
        let d = try await api(path); try? d.write(to: cacheURL(path), options: .atomic); return d
    }

    private func cookies(for url: URL) async -> [String: String] {
        if let h = cookieHeader, Date().timeIntervalSince(cookieAt) < 60 { return h }
        let cs = await WKWebsiteDataStore.default().httpCookieStore.allCookies().filter { Store.cookieMatches($0, url) }
        let h = HTTPCookie.requestHeaderFields(with: cs); cookieHeader = h; cookieAt = Date(); return h
    }

    /// Cookie gehoert zu dieser Adresse? Domain nur an Punktgrenzen (evilexample.com passt nicht auf example.com),
    /// Secure nur ueber https, Pfad-Praefix.
    nonisolated static func cookieMatches(_ c: HTTPCookie, _ url: URL) -> Bool {
        guard let host = url.host?.lowercased() else { return false }
        let d = c.domain.lowercased().trimmingCharacters(in: CharacterSet(charactersIn: "."))
        guard !d.isEmpty, host == d || host.hasSuffix("." + d) else { return false }
        if c.isSecure && url.scheme?.lowercased() != "https" { return false }
        let path = url.path.isEmpty ? "/" : url.path
        return c.path.isEmpty || c.path == "/" || path == c.path || path.hasPrefix(c.path.hasSuffix("/") ? c.path : c.path + "/")
    }

    func api(_ path: String, method: String = "GET", body: [String: Any]? = nil) async throws -> Data {
        let (data, code) = try await apiResponse(path, method: method, body: body)
        guard (200..<300).contains(code) else {
            let msg = (try? JSONSerialization.jsonObject(with: data) as? [String: Any])?["error"] as? String ?? ""
            throw APIError.http(code, msg)
        }
        return data
    }

    /// Wie `api`, aber Fehlerantworten (4xx/5xx) kommen mit Code zurueck statt als Fehler - fuer Antworten mit
    /// zusaetzlichen Feldern (z. B. 409 need_name beim Veroeffentlichen). Abgelaufene Sitzung wie bei `api`.
    func apiResponse(_ path: String, method: String = "GET", body: [String: Any]? = nil) async throws -> (Data, Int) {
        // NICHT appendingPathComponent: das kodiert "?" als %3F und Abfrage-Parameter werden zum Pfad (404)
        guard AppConfig.hasServer else { needsLogin = true; throw APIError.http(401, String(localized: "Bitte Server eintragen")) }   // noch kein Server -> Anmeldebildschirm
        guard let url = URL(string: AppConfig.server.absoluteString.trimmingCharacters(in: CharacterSet(charactersIn: "/")) + "/" + path) else { throw APIError.other(String(localized: "Ungültige Adresse")) }
        var req = URLRequest(url: url, timeoutInterval: 20)
        req.httpMethod = method
        req.setValue("1", forHTTPHeaderField: "X-Hlidskjalf"); req.httpShouldHandleCookies = false   // Cookie selbst setzen
        if let body { req.setValue("application/json", forHTTPHeaderField: "Content-Type"); req.httpBody = try JSONSerialization.data(withJSONObject: body) }
        return try await send(req) { try await URLSession.shared.data(for: $0) }
    }

    /// Anfrage mit Sitzungs-Cookie; abgelaufene Sitzung (401) -> einmal still mit dem Geraete-Schluessel erneuern
    /// und wiederholen. Erst wenn das nicht geht, kommt das Anmeldefenster.
    private func send(_ base: URLRequest, _ run: (URLRequest) async throws -> (Data, URLResponse)) async throws -> (Data, Int) {
        for attempt in 0..<2 {
            var req = base
            if let url = req.url { for (k, v) in await cookies(for: url) { req.setValue(v, forHTTPHeaderField: k) } }
            req.setValue(AppConfig.appLanguage, forHTTPHeaderField: "Accept-Language")   // Fehlertexte des Servers in der App-Sprache
            let (data, resp) = try await run(req)
            let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
            guard code == 401 else { return (data, code) }
            cookieHeader = nil
            guard attempt == 0 else { break }
            let r = await resumeSession()
            if r == .offline { throw APIError.other(String(localized: "Server nicht erreichbar")) }
            if r == .rejected { break }
        }
        if !loginPostponed { needsLogin = true }                          // abgebrochen: nicht bei jeder Anfrage wieder aufdraengen
        throw APIError.http(401, String(localized: "Bitte anmelden"))
    }

    /// Anfrage mit Sitzung (z. B. Profilbilder): wie api, aber ohne JSON und mit eigenem URLRequest
    func sendQuiet(_ req: URLRequest) async throws -> (Data, Int) { try await send(req) { try await URLSession.shared.data(for: $0) } }

    enum ResumeResult { case ok, rejected, offline }
    private var resuming: Task<ResumeResult, Never>?

    /// Sitzung mit dem Geraete-Schluessel erneuern (gleichzeitige Anfragen teilen sich einen Versuch)
    func resumeSession() async -> ResumeResult {
        if let t = resuming { return await t.value }
        let t = Task { await doResume() }
        resuming = t
        let r = await t.value
        resuming = nil
        return r
    }

    private func doResume() async -> ResumeResult {
        // Schluessel nur an den Server, der ihn ausgestellt hat, und nur verschluesselt
        guard AppConfig.hasServer, AppConfig.isAllowedServer(AppConfig.server), let tok = DeviceKey.get() else { return .rejected }
        let url = AppConfig.server.appendingPathComponent("app/resume")
        var req = URLRequest(url: url, timeoutInterval: 20)
        req.httpMethod = "POST"; req.httpShouldHandleCookies = false
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        var body: [String: Any] = ["device_token": tok]
        if AppConfig.hasToken { body["app_key"] = AppConfig.token }      // Widget-Schluessel diesem Geraet zuordnen (Einstellungen: eine Liste)
        req.httpBody = try? JSONSerialization.data(withJSONObject: body)
        guard let r = try? await URLSession.shared.data(for: req), let http = r.1 as? HTTPURLResponse else { return .offline }
        if http.statusCode == 401 || http.statusCode == 403 { DeviceKey.set(nil); return .rejected }   // abgemeldet/gesperrt (gilt nur fuer diesen Server)
        guard http.statusCode == 200 else { return .offline }
        await storeCookies(http, url)
        return .ok
    }

    private func storeCookies(_ http: HTTPURLResponse, _ url: URL) async {
        let headers = http.allHeaderFields.reduce(into: [String: String]()) { r, kv in if let k = kv.key as? String, let v = kv.value as? String { r[k] = v } }
        for c in HTTPCookie.cookies(withResponseHeaderFields: headers, for: url) { await WKWebsiteDataStore.default().httpCookieStore.setCookie(c) }
        cookieHeader = nil
    }

    /// Antwort der Anmeldung: Geraete-Schluessel sichern, App-Schluessel (Widget/Teilen) uebernehmen, falls noch keiner da ist
    /// Rueckgabe false: Geraete-Schluessel kam, liess sich aber nicht im Schluesselbund speichern (BUG-104)
    @discardableResult
    private func adoptKeys(_ d: Data) -> Bool {
        let j = (try? JSONSerialization.jsonObject(with: d)) as? [String: Any]
        var ok = true
        if let t = j?["device_token"] as? String, t.hasPrefix("hkd_") { DeviceKey.set(t); ok = DeviceKey.stored() == t }
        if let k = j?["app_key"] as? String, k.hasPrefix("hk_"), !AppConfig.hasToken { AppConfig.token = k; reloadWidgets() }
        return ok
    }
    /// Text, wenn der Geraete-Schluessel nach Anmeldung/Kopplung nicht gespeichert werden konnte
    static var keychainFailedText: String {
        String(localized: "Der Geräte-Schlüssel konnte nicht im Schlüsselbund gespeichert werden. Die Anmeldung hält deshalb nur einige Stunden.")
    }
    private func reloadWidgets() {
        #if canImport(WidgetKit)
        WidgetCenter.shared.reloadAllTimelines()
        #endif
    }

    /// Schon angemeldet (z. B. vor diesem Update), aber noch kein Geraete-Schluessel -> einmal still nachholen
    private var deviceKeyTried = false
    private func ensureDeviceKey() async {
        guard !deviceKeyTried, DeviceKey.get() == nil else { return }
        deviceKeyTried = true
        if let d = try? await api("app/device", method: "POST", body: ["device": DeviceKey.deviceName, "app_key": !AppConfig.hasToken]) { adoptKeys(d) }
    }

    private var lastRefresh = Date.distantPast
    func refresh(quiet: Bool = false) async {
        if !quiet { loading = true }
        defer { if !quiet { loading = false } }
        lastRefresh = Date()
        do {
            setPages(try JSONDecoder().decode([Page].self, from: try await get("api/notes")))
            if needsLogin { needsLogin = false }
            if loginPostponed { loginPostponed = false }          // Sitzung wieder da (z. B. still erneuert)
            await loadShared()
            Task { await loadFedShared() }                        // fremde Server koennen dauern: nicht darauf warten
            Task { await notifs.loadCount(); await chat.refreshUnread(); await loadAIStatus() }
            if error != nil, error != Store.keychainFailedText { error = nil }   // Schluesselbund-Problem bleibt sichtbar (BUG-104)
            if serverVersion == nil { await checkVersion() }
            await loadToday()
            await ensureDeviceKey()
            // Konto fuer die Entwuerfe: einmal je Start, und solange es fuer diesen Server noch unbekannt ist
            if !accountChecked || AppConfig.serverOrigin.flatMap({ DraftOwner.app.account(origin: $0) }) == nil {
                accountChecked = true; await identifyAccount()
            }
            Task { await resendDrafts() }                         // offline Geaendertes nachsenden (BUG-020)
        } catch { if !needsLogin, self.error != error.localizedDescription { self.error = error.localizedDescription } }
    }
    /// Liegengebliebene Entwuerfe (offline geaendert, Seite inzwischen verlassen) senden, sobald der Server wieder
    /// antwortet - mit der Revision, auf der sie beruhen: hat sich die Seite inzwischen geaendert (409), wird nichts
    /// ueberschrieben, der Entwurf wird dann beim Oeffnen angeboten. Offene Seiten senden selbst (BUG-020).
    private var resendingDrafts = false
    func resendDrafts() async {
        guard !resendingDrafts, let o = AppConfig.serverOrigin, DraftOwner.app.account(origin: o) != nil,
              let ds = DraftOwner.app.store(origin: o) else { return }
        resendingDrafts = true; defer { resendingDrafts = false }
        let gen = sessionGen
        for f in ds.files {
            guard let id = DraftResend.pageID(f.lastPathComponent), let dr = ds.load(id),
                  DraftResend.shouldSend(dr, id: id, open: SaveRegistry.openIDs) else { continue }
            let b: [String: Any] = ["title": dr.title, "body": dr.body, "format": "blocks", "rev": dr.rev]
            guard let (_, code) = try? await apiResponse("api/notes/\(id)", method: "PATCH", body: b) else { return }   // offline: spaeter
            // abgemeldet/anderes Konto oder Seite inzwischen geoeffnet bzw. Entwurf geaendert: nichts mehr anfassen
            guard gen == sessionGen, AppConfig.serverOrigin == o, ds.load(id) == dr else { return }
            switch DraftResend.outcome(code) {
            case .sent: ds.remove(id); dropCache("api/notes/\(id)"); updateTitle(id, dr.title)
            case .hold: var c = dr; c.conflict = true; ds.save(id, c)
            case .retry: return
            }
        }
    }
    /// 60-s-Abgleich: still, hoechstens einmal pro Takt fuer alle Fenster, und nur Geaendertes neu setzen
    /// (sonst zeichnen alle Ansichten mit dem Store jede Minute neu)
    func periodicRefresh() async {
        guard Date().timeIntervalSince(lastRefresh) > 50 else { return }
        await refresh(quiet: true)
    }

    func loadToday() async {
        struct T: Decodable { let open: [OpenTask]; let done: [OpenTask]? }
        if let d = try? await api("api/tasks"), let t = try? JSONDecoder().decode(T.self, from: d) {
            if tasks != t.open { tasks = t.open }
            if doneTasks != (t.done ?? []) { doneTasks = t.done ?? [] }
        }
        if events.isEmpty, let c = cachedEvents(days: 1), !c.isEmpty { events = c }   // sofort anzeigen
        if let e = await self.events(days: 1), e != events { events = e }            // Fehler: bisherigen Stand behalten
    }

    func search(_ q: String) async -> [Page] {
        guard q.trimmingCharacters(in: .whitespaces).count >= 2,
              let d = try? await api("api/notes/search", method: "POST", body: ["q": q]) else { return [] }
        return (try? JSONDecoder().decode([Page].self, from: d)) ?? []
    }

    func newPage(parent: Int? = nil) async -> Int? {
        struct R: Decodable { let id: Int }
        var b: [String: Any] = ["title": ""]
        if let parent { b["parent_id"] = parent }
        guard let d = try? await api("api/notes", method: "POST", body: b), let r = try? JSONDecoder().decode(R.self, from: d) else { return nil }
        await refresh()
        return r.id
    }

    // MARK: Aufgaben (wie das Web: /api/tasks; Wiederholungen legt der Server beim Abhaken an)
    func addTask(_ text: String, due: String? = nil, prio: Int = 2, rep: String? = nil) async {
        var b: [String: Any] = ["text": text, "prio": prio]
        if let due { b["due"] = due }; if let rep { b["repeat"] = rep }
        let f = DateFormatter.isoDay(); b["today"] = f.string(from: .now)
        _ = try? await api("api/tasks", method: "POST", body: b); await loadToday()
    }
    func updateTask(_ id: Int, _ fields: [String: Any]) async { _ = try? await api("api/tasks/\(id)", method: "PATCH", body: fields); await loadToday() }
    func deleteTask(_ id: Int) async { tasks.removeAll { $0.id == id }; doneTasks.removeAll { $0.id == id }; _ = try? await api("api/tasks/\(id)", method: "DELETE"); await loadToday() }

    // MARK: Termine (externe Kalender)
    // Termine: sofort aus dem Zwischenspeicher, gleiche Anfragen zusammengelegt, laufen weiter wenn die Ansicht verschwindet
    @Published var eventsTick = 0                          // "Aktualisieren" -> Listen laden frisch
    private var evInflight: [String: Task<[CalEvent]?, Never>] = [:]
    private struct EvResp: Decodable { let events: [CalEvent] }
    private func evPath(_ days: Int) -> String {
        let f = DateFormatter.isoDay()
        let t0 = Calendar.current.startOfDay(for: .now), t1 = Calendar.current.date(byAdding: .day, value: days, to: t0)!
        return "api/calendar/events?start=\(f.string(from: t0))&end=\(f.string(from: t1))"
    }
    func cachedEvents(days: Int) -> [CalEvent]? {
        cachedData(evPath(days)).flatMap { try? JSONDecoder().decode(EvResp.self, from: $0).events }
    }
    /// nil = Fehler (dann den bisherigen Stand behalten, nicht leeren)
    func events(days: Int, fresh: Bool = false) async -> [CalEvent]? {
        let base = evPath(days), key = base + (fresh ? "&fresh=1" : "")
        if let running = evInflight[key] { return await running.value }
        let task = Task { () -> [CalEvent]? in
            guard let d = try? await self.api(key), let e = try? JSONDecoder().decode(EvResp.self, from: d) else { return nil }
            try? d.write(to: self.cacheURL(base), options: .atomic)
            return e.events
        }
        evInflight[key] = task
        let r = await task.value
        evInflight[key] = nil
        return r
    }
    func refreshEvents() { eventsTick += 1 }

    /// Eintraege angelegt/geaendert (Schaltflaeche, "Neu", Eigenschaften, Peek geschlossen) -> Datenbank-Ansichten laden neu
    @Published var dataTick = 0
    func dataChanged() { dataTick += 1 }

    /// Startseite dieses Geraets (wird beim App-Start geoeffnet)
    @Published var homePage: Int? = UserDefaults.standard.object(forKey: "hk.homePage") as? Int
    /// Startseite merkt sich ihr Konto (Server + Benutzername): ein anderes Konto oeffnet sie nie (BUG-060)
    func setHome(_ id: Int?) {
        homePage = id
        let d = UserDefaults.standard
        if let id {
            d.set(id, forKey: "hk.homePage")
            if let o = AppConfig.serverOrigin, let a = DraftOwner.app.account(origin: o) { d.set(HomeOwner.key(origin: o, account: a), forKey: "hk.homeOwner") }
        } else { d.removeObject(forKey: "hk.homePage"); d.removeObject(forKey: "hk.homeOwner") }
    }
    /// Konto erkannt: Startseite eines anderen Kontos vergessen
    private func checkHomeOwner(origin: String, account: String) {
        if homePage != nil, HomeOwner.mustForget(stored: UserDefaults.standard.string(forKey: "hk.homeOwner"), origin: origin, account: account) { setHome(nil) }
        else if homePage != nil, UserDefaults.standard.string(forKey: "hk.homeOwner") == nil { setHome(homePage) }   // alte Fassung: Besitzer nachtragen
    }
    func calTargets() async -> [CalTarget] {
        guard let d = try? await api("api/settings/calendars"), let j = try? JSONSerialization.jsonObject(with: d) as? [String: Any] else { return [] }
        return (j["accounts"] as? [[String: Any]] ?? []).flatMap { a in
            (a["calendars"] as? [[String: Any]] ?? []).compactMap { c -> CalTarget? in
                guard c["show"] as? Bool != false, c["writable"] as? Bool != false, let href = c["href"] as? String, let aid = a["id"] as? Int else { return nil }
                return CalTarget(account: aid, cal: href, name: ((a["label"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? (a["username"] as? String ?? "")) + " · " + (c["name"] as? String ?? ""), color: c["color"] as? String)
            }
        }
    }
    func saveEvent(_ e: [String: Any], edit: Bool) async throws { _ = try await api("api/calendar/events", method: edit ? "PATCH" : "POST", body: e); await loadToday() }
    func deleteEvent(_ e: CalEvent) async throws {
        _ = try await api("api/calendar/events/delete", method: "POST", body: ["account": e.account ?? 0, "cal": e.cal ?? "", "href": e.href ?? "", "etag": e.etag ?? ""]); await loadToday()
    }

    func completeTask(_ t: OpenTask) async {
        tasks.removeAll { $0.id == t.id }
        _ = try? await api("api/tasks/\(t.id)", method: "PATCH", body: ["done": true])
        await loadToday()
    }

    func visited(_ id: Int) {
        recent.removeAll { $0 == id }; recent.insert(id, at: 0); recent = Array(recent.prefix(8))
        UserDefaults.standard.set(recent, forKey: "recent")
    }

    // MARK: Mit mir geteilt (1.3)
    /// Fuer mich freigegebene fremde Seiten (flach; root = oberste geteilte Seite). Aeltere Server: leer.
    @Published var shared: [Page] = []
    /// Teilen abgeschaltet, aber Freigaben vorhanden -> Hinweis in der Seitenleiste
    @Published var shareOffHint = false
    func loadShared() async {
        guard let (d, code) = try? await apiResponse("api/shared") else { return }      // offline: bisherigen Stand behalten
        guard code == 200, let list = try? JSONDecoder().decode([Page].self, from: d) else {
            if code == 404 || code == 405 { if !shared.isEmpty { shared = [] }; shareOffHint = false }   // alter Server: alles Neue aus
            return
        }
        // nicht mehr geteilt: zwischengespeicherte Inhalte dieser Seiten verwerfen (offline nicht mehr lesbar)
        let gone = Set(shared.map(\.id)).subtracting(list.map(\.id)).subtracting(pages.map(\.id))
        for g in gone { dropCache("api/notes/\(g)"); dropCache("api/notes/\(g)/rows") }
        if list != shared { shared = list }
        var hint = false
        if list.isEmpty, let (i, c) = try? await apiResponse("api/share/info"), c == 200, let info = SharedInfo(data: i) {
            hint = info.showOffHint(sharedCount: 0)
        }
        if hint != shareOffHint { shareOffHint = hint }
    }
    /// Oberste geteilte Seiten bzw. Unterseiten einer geteilten Seite (ohne Datenbank-Eintraege, wie im eigenen Baum);
    /// enthaelt Seiten anderer Saga-Instanzen (Foederation, negative IDs)
    func sharedChildren(of id: Int?) -> [Page] {
        let all = shared + fedShared
        guard let id else { return all.filter { $0.root == true } }
        if all.first(where: { $0.id == id })?.isDatabase == true { return [] }
        return all.filter { $0.parent_id == id && $0.root != true }
    }

    // MARK: Foederation (2.0): mit mir geteilte Seiten anderer Saga-Instanzen
    @Published var fedShared: [Page] = []
    /// KI-Assistent fuer dieses Konto eingeschaltet (1.6)
    @Published var aiEnabled = false
    /// Persona der KI (2.1): Name + Profilbild; nil = aelterer Server (bisherige Beschriftung)
    @Published var aiPersona: AIPersona?
    /// "KI in Chats erlauben" auf dem Server an (2.1) und Ziel der Inhalte (host)
    @Published var aiChat = false
    @Published var aiHost = ""
    /// Server, die gerade nicht erreichbar sind (dezent anzeigen, kein Fehler)
    @Published var fedUnreachable: [String] = []
    private var fedLoading = false
    /// Getrennt laden (bis ~4 s je fremdem Server) - die Seitenleiste wartet nicht darauf
    func loadFedShared() async {
        guard !fedLoading else { return }
        fedLoading = true; defer { fedLoading = false }
        guard let (d, code) = try? await apiResponse("api/fed/shared") else { return }
        guard code == 200 else { if code == 404 || code == 405 { if !fedShared.isEmpty { fedShared = [] }; fedUnreachable = [] }; return }
        let r = FedLogic.parseShared(d, as: Page.self)
        let gone = Set(fedShared.map(\.id)).subtracting(r.items.map(\.id))
        for g in gone { dropCache("api/notes/\(g)") }
        if r.items != fedShared { fedShared = r.items }
        if r.unreachable != fedUnreachable { fedUnreachable = r.unreachable }
    }

    // Seitenbaum: oberste Ebene + Kinder (Datenbank-Zeilen erscheinen nicht im Baum, wie im Web)
    func page(_ id: Int) -> Page? { pages.first { $0.id == id } ?? shared.first { $0.id == id } ?? fedShared.first { $0.id == id } }
    func children(of id: Int?) -> [Page] {
        pages.filter { p in
            p.parent_id == id && !(p.parent_id.map { page($0)?.isDatabase ?? false } ?? false)
        }
    }
    var favorites: [Page] { pages.filter { $0.pinned == true } }

    /// Datei hochladen (wie der Web-Editor: Formularfeld "image") -> /files/<id>
    /// `note`: Seite eines anderen Kontos (Rolle Bearbeiten) - die Datei gehoert dann deren Eigentuemer (X-Saga-Note)
    func upload(_ data: Data, name: String, note: Int? = nil) async throws -> String {
        let url = AppConfig.server.appendingPathComponent("api/files")
        var req = URLRequest(url: url, timeoutInterval: 60)
        req.httpMethod = "POST"; req.httpShouldHandleCookies = false
        req.setValue("1", forHTTPHeaderField: "X-Hlidskjalf")
        if let note { req.setValue(String(note), forHTTPHeaderField: "X-Saga-Note") }
        let boundary = "hk-" + UUID().uuidString
        req.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
        var body = Data()
        body.append("--\(boundary)\r\nContent-Disposition: form-data; name=\"image\"; filename=\"\(name)\"\r\nContent-Type: application/octet-stream\r\n\r\n".data(using: .utf8)!)
        body.append(data); body.append("\r\n--\(boundary)--\r\n".data(using: .utf8)!)
        let (d, code) = try await send(req) { try await URLSession.shared.upload(for: $0, from: body) }
        let j = (try? JSONSerialization.jsonObject(with: d)) as? [String: Any]
        guard code == 200, let f = j?["file"] as? [String: Any], let u = f["url"] as? String else {
            throw APIError.other(j?["error"] as? String ?? String(localized: "Upload fehlgeschlagen"))
        }
        return u
    }

    /// Datei-Anhang hochladen (Formularfeld "file": beliebige Datei) -> {url, name, size, extension, title}
    func uploadFile(_ data: Data, name: String, note: Int? = nil) async throws -> [String: Any] {
        let url = AppConfig.server.appendingPathComponent("api/files")
        var req = URLRequest(url: url, timeoutInterval: 120)
        req.httpMethod = "POST"; req.httpShouldHandleCookies = false
        req.setValue("1", forHTTPHeaderField: "X-Hlidskjalf")
        if let note { req.setValue(String(note), forHTTPHeaderField: "X-Saga-Note") }
        let boundary = "hk-" + UUID().uuidString
        req.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
        let safe = name.replacingOccurrences(of: "\"", with: "_")
        var body = Data()
        body.append("--\(boundary)\r\nContent-Disposition: form-data; name=\"file\"; filename=\"\(safe)\"\r\nContent-Type: application/octet-stream\r\n\r\n".data(using: .utf8)!)
        body.append(data); body.append("\r\n--\(boundary)--\r\n".data(using: .utf8)!)
        let (d, code) = try await send(req) { try await URLSession.shared.upload(for: $0, from: body) }
        let j = (try? JSONSerialization.jsonObject(with: d)) as? [String: Any]
        guard code == 200, let f = j?["file"] as? [String: Any] else {
            throw APIError.other(j?["error"] as? String ?? String(localized: "Upload fehlgeschlagen"))
        }
        return f
    }

    /// Bild-Icon (/files/...) mit der Sitzung laden
    func imageData(_ path: String) async -> Data? {
        // Dateien aendern sich nie (eigene Adresse je Upload) -> Speicher + Platte
        if let m = images.object(forKey: path as NSString) { return m as Data }
        let disk = cacheDir.appendingPathComponent("files").appendingPathComponent(path.replacingOccurrences(of: "/", with: "_"))
        if let d = try? Data(contentsOf: disk) { images.setObject(d as NSData, forKey: path as NSString); return d }
        guard let d = try? await api(String(path.drop(while: { $0 == "/" }))) else { return nil }
        try? d.write(to: disk, options: .atomic); images.setObject(d as NSData, forKey: path as NSString)
        return d
    }

    // MARK: Seiten organisieren (dieselben Server-Wege wie das Web)
    @Published var undo: (id: Int, title: String)?          // "In den Papierkorb verschoben - Rueckgaengig"

    /// Icon, Titelbild, Position, Favorit, volle Breite aendern (ohne Inhalt -> keine Revision noetig)
    func patch(_ id: Int, _ fields: [String: Any]) async throws {
        _ = try await api("api/notes/\(id)", method: "PATCH", body: fields)
        try? FileManager.default.removeItem(at: cacheURL("api/notes/\(id)"))
        if let i = pages.firstIndex(where: { $0.id == id }) {
            if let v = fields["icon"] { pages[i].icon = v as? String }
            if let v = fields["pinned"] as? Bool { pages[i].pinned = v }
        }
    }
    /// Archiv: nicht im Baum/Suche, aber per Link erreichbar (wie im Web)
    @Published var archivedPages: [Page] = []
    private var lastAll: [Page]?
    private func setPages(_ all: [Page]) {
        guard all != lastAll else { return }                              // unveraendert: nichts neu zeichnen, Spotlight nicht neu bauen
        lastAll = all
        let vis = all.filter { $0.archived != true }
        if vis != pages { pages = vis }
        SpotlightIndex.update(pages)                                      // Seiten in der Systemsuche
        let ids = Set(all.filter { $0.archived == true }.map(\.id))
        let arch = all.filter { $0.archived == true && !($0.parent_id.map { ids.contains($0) } ?? false) }   // nur oberste
        if arch != archivedPages { archivedPages = arch }
    }
    func setArchived(_ id: Int, _ on: Bool) async {
        _ = try? await api("api/notes/\(id)/archive", method: "POST", body: ["archived": on])
        await refresh(); dataChanged()
    }
    func trash(_ id: Int) async -> Bool {
        let t = page(id)?.label ?? ""
        guard (try? await api("api/notes/\(id)", method: "DELETE")) != nil else { return false }
        undo = (id, t); await refresh()
        Task { try? await Task.sleep(for: .seconds(6)); if undo?.id == id { undo = nil } }
        return true
    }
    func restore(_ id: Int) async { undo = nil; _ = try? await api("api/trash/\(id)/restore", method: "POST", body: [:]); await refresh() }
    func move(_ id: Int, to parent: Int?) async throws {
        _ = try await api("api/notes/\(id)/move", method: "POST", body: ["parent_id": parent.map { $0 as Any } ?? NSNull(), "index": 0])
        await refresh()
    }
    func duplicate(_ id: Int) async -> Int? {
        struct R: Decodable { let id: Int }
        guard let d = try? await api("api/notes/\(id)/duplicate", method: "POST", body: [:]), let r = try? JSONDecoder().decode(R.self, from: d) else { return nil }
        await refresh(); return r.id
    }
    /// Pfad der uebergeordneten Seiten (fuer die Leiste)
    func path(_ id: Int) -> [Page] {
        var out: [Page] = [], cur = page(id)?.parent_id, guardN = 0
        while let c = cur, let p = page(c), guardN < 20 { out.insert(p, at: 0); cur = p.parent_id; guardN += 1 }
        return out
    }
    /// Seiten, die sich selbst oder ihre Unterseiten nicht als Ziel haben duerfen
    func descendants(_ id: Int) -> Set<Int> {
        var s: Set<Int> = [id], changed = true
        while changed { changed = false; for p in pages where p.parent_id.map(s.contains) == true && !s.contains(p.id) { s.insert(p.id); changed = true } }
        return s
    }
    /// Bild hochladen wie im Editor (HEIC -> JPEG), GIF/PNG/JPEG/WebP unveraendert
    func uploadImage(_ raw: Data, note: Int? = nil) async throws -> String {
        let head = [UInt8](raw.prefix(4))
        if head.starts(with: [0x47, 0x49, 0x46]) { return try await upload(raw, name: "bild.gif", note: note) }
        if head.starts(with: [0x89, 0x50, 0x4E, 0x47]) { return try await upload(raw, name: "bild.png", note: note) }
        if head.starts(with: [0xFF, 0xD8]) || (raw.count > 12 && raw[8..<12] == Data("WEBP".utf8)) { return try await upload(raw, name: "bild.jpg", note: note) }
        #if os(iOS)
        guard let j = UIImage(data: raw)?.jpegData(compressionQuality: 0.85) else { throw APIError.other(String(localized: "Bildformat nicht unterstützt")) }
        #else
        guard let img = NSImage(data: raw), let tiff = img.tiffRepresentation, let j = NSBitmapImageRep(data: tiff)?.representation(using: .jpeg, properties: [.compressionFactor: 0.85]) else { throw APIError.other(String(localized: "Bildformat nicht unterstützt")) }
        #endif
        return try await upload(j, name: "bild.jpg", note: note)
    }

    func updateTitle(_ id: Int, _ title: String) { if let i = pages.firstIndex(where: { $0.id == id }), pages[i].title != title { pages[i].title = title } }

    /// Anmeldung ueber das System-Anmeldefenster: Code + Geheimnis -> Sitzung (auch fuer die Web-Ansichten).
    /// `server` ist der beim Start der Anmeldung vorgemerkte Server. Er wird erst nach Erfolg uebernommen
    /// (wie bei `pair`); Abbruch, Fehler oder Tippfehler im Feld aendern nichts am bisherigen Server.
    func exchange(server: URL, code: String, verifier: String) async throws {
        guard AppConfig.isAllowedServer(server) else { throw APIError.other(String(localized: "Nur https-Adressen sind erlaubt.")) }
        let switching = AppConfig.serverOrigin != AppConfig.origin(server)
        let url = server.appendingPathComponent("app/exchange")
        var req = URLRequest(url: url, timeoutInterval: 20)
        req.httpMethod = "POST"; req.httpShouldHandleCookies = false
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.setValue(AppConfig.appLanguage, forHTTPHeaderField: "Accept-Language")       // Fehlertexte in der App-Sprache
        // device: Geraete-Anmeldung (bleibt angemeldet); app_key: Schluessel fuer Widget/Teilen, falls (fuer diesen Server) noch keiner da ist
        let body: [String: Any] = ["code": code, "verifier": verifier, "device": DeviceKey.deviceName, "app_key": switching || !AppConfig.hasToken]
        req.httpBody = try JSONSerialization.data(withJSONObject: body)
        let (d, resp) = try await URLSession.shared.data(for: req)
        guard let http = resp as? HTTPURLResponse, http.statusCode == 200 else {
            let msg = (try? JSONSerialization.jsonObject(with: d) as? [String: Any])?["error"] as? String ?? String(localized: "Anmeldung fehlgeschlagen")
            throw APIError.other(msg)
        }
        await adoptServer(server, switching: switching, http: http, url: url, data: d)
    }

    /// Erfolgreich angemeldet/gekoppelt: erst jetzt den Server uebernehmen. Anderer Server -> vorher sauber vom alten abmelden.
    /// `signOut`: auch auf demselben Server das bisherige Konto abloesen (QR-Code kann zu einem anderen Konto gehoeren);
    /// die Startseite wird dabei mit vergessen (Seiten-IDs gehoeren zum alten Konto).
    private func adoptServer(_ server: URL, switching: Bool, signOut: Bool = false, http: HTTPURLResponse, url: URL, data d: Data) async {
        if switching || signOut { await logout(switchingServer: true) }  // alter Server/altes Konto: Schluessel, Cookies, Zwischenspeicher weg
        AppConfig.server = server; cookieHeader = nil; serverVersion = nil; minApp = nil
        await storeCookies(http, url)
        let keyOK = adoptKeys(d); deviceKeyTried = true
        await identifyAccount()                                           // Entwuerfe gehoeren zu Server + Konto
        needsLogin = false; loginPostponed = false
        if !keyOK { error = Store.keychainFailedText }                    // nicht still: sonst spaeter unerklaert abgemeldet
    }

    /// Anmeldung per QR-Code (Kopplungs-Code aus dem Web). Der Server wird erst nach erfolgreicher Kopplung
    /// uebernommen; vorher wird sauber abgemeldet - vom alten Server, und auch auf demselben Server vom bisherigen Konto
    /// (so sagt es die Rueckfrage, `PairNotice`): sonst blieben Widget-Schluessel und Geraete-Anmeldung des alten Kontos liegen.
    func pair(server: URL, code: String) async throws {
        guard AppConfig.isAllowedServer(server) else { throw APIError.other(String(localized: "Nur https-Adressen sind erlaubt.")) }
        let switching = AppConfig.serverOrigin != AppConfig.origin(server)
        let signOut = switching || signedIn
        let url = server.appendingPathComponent("app/pair")
        var req = URLRequest(url: url, timeoutInterval: 20)
        req.httpMethod = "POST"; req.httpShouldHandleCookies = false
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.setValue(AppConfig.appLanguage, forHTTPHeaderField: "Accept-Language")       // Fehlertexte in der App-Sprache
        req.httpBody = try JSONSerialization.data(withJSONObject: ["code": code, "device": DeviceKey.deviceName, "app_key": signOut || !AppConfig.hasToken])
        let (d, resp) = try await URLSession.shared.data(for: req)
        guard let http = resp as? HTTPURLResponse, http.statusCode == 200 else {
            let msg = (try? JSONSerialization.jsonObject(with: d) as? [String: Any])?["error"] as? String ?? String(localized: "Anmeldung fehlgeschlagen")
            throw APIError.other(msg)
        }
        await adoptServer(server, switching: switching, signOut: signOut, http: http, url: url, data: d)
        await refresh()
    }

    /// Angemeldet (fuer die Kopplungs-Rueckfrage): Server eingetragen, nicht auf dem Anmeldebildschirm, Schluessel vorhanden
    var signedIn: Bool { !needsLogin && AppConfig.hasServer && (DeviceKey.stored() != nil || AppConfig.hasToken) }
    /// Was die Kopplungs-Rueckfrage ueber die bisherige Anmeldung sagt
    func pairNotice(for target: URL) -> PairNotice {
        PairNotice.plan(target: target, current: AppConfig.hasServer ? AppConfig.server : nil, signedIn: signedIn, account: accountName)
    }

    // MARK: Konto (Entwuerfe gehoeren zu Server + Konto)

    private var accountChecked = false
    /// Anzeigename des angemeldeten Kontos (sonst Benutzername); nur im Speicher, nur fuer Rueckfragen
    private(set) var accountName: String?
    /// Konto beim Server erfragen (Konto-ID = Benutzername aus /api/account) und fuer die Entwuerfe merken.
    /// Fehler (offline, alter Server): bisherigen Stand behalten.
    func identifyAccount() async {
        guard let o = AppConfig.serverOrigin, let d = try? await api("api/account"),
              let j = try? JSONSerialization.jsonObject(with: d) as? [String: Any],
              let u = j["username"] as? String, !u.isEmpty else { return }
        DraftOwner.app.signedIn(origin: o, account: u)
        checkHomeOwner(origin: o, account: u)
        let n = (j["name"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        accountName = n.isEmpty ? u : n
        let av = ShareJSON.str(j["avatar"])
        if av != myAvatar { myAvatar = av }
    }

    /// Anderen Server eintragen (Einstellungen): anderer Server = vom alten sauber abmelden
    func switchServer(to server: URL) async {
        if AppConfig.serverOrigin == AppConfig.origin(server) {
            AppConfig.server = server; await serverChanged(); return
        }
        if AppConfig.hasServer && (DeviceKey.stored() != nil || !pages.isEmpty) { await logout(switchingServer: true) }
        AppConfig.server = server; cookieHeader = nil; serverVersion = nil; minApp = nil
        needsLogin = true
    }

    /// Server-Adresse geaendert (gleicher Server): neu verbinden
    func serverChanged() async { cookieHeader = nil; serverVersion = nil; minApp = nil; await refresh() }

    /// Kopplungs-Link aus dem QR-Code: https://server/app/pair?c=CODE oder <app>://pair?u=https://server&c=CODE
    nonisolated static func parsePairLink(_ s: String) -> (URL, String)? {
        guard let u = URLComponents(string: s.trimmingCharacters(in: .whitespacesAndNewlines)) else { return nil }
        let q = u.queryItems ?? []
        guard let code = q.first(where: { $0.name == "c" })?.value, code.count >= 20 else { return nil }
        if u.scheme == "https" || u.scheme == "http", u.path == "/app/pair", let host = u.host {
            var b = URLComponents(); b.scheme = u.scheme; b.host = host; b.port = u.port
            return b.url.flatMap { AppConfig.isAllowedServer($0) ? ($0, code) : nil }    // nur https (Demo: localhost im DEBUG-Build)
        }
        if ["hlidskjalf", "saga"].contains(u.scheme ?? ""), u.host == "pair", let srv = q.first(where: { $0.name == "u" })?.value,
           let s = AppConfig.normalizedServer(srv) {
            return (s, code)
        }
        return nil
    }

    /// Zaehlt Abmeldungen/Kontowechsel: eine Seite, die vorher geladen wurde, gehoert zum alten Konto (PageDoc.staleSession)
    private(set) var sessionGen = 0

    /// Auf diesem Geraet abmelden: Sitzung + Zwischenspeicher loeschen. `switchingServer`: auch Startseite vergessen
    /// (Seiten-IDs gehoeren zum alten Server).
    func logout(switchingServer: Bool = false) async {
        sessionGen += 1                                                   // offene Seiten des alten Kontos senden nichts mehr
        // Geraete-Anmeldung beim Server loeschen, der sie ausgestellt hat (nie an einen anderen senden)
        if let tok = DeviceKey.stored(), let o = AppConfig.keyOrigin(device: true), let base = URL(string: o), AppConfig.isAllowedServer(base) {
            var req = URLRequest(url: base.appendingPathComponent("app/forget"), timeoutInterval: 10)
            req.httpMethod = "POST"; req.httpShouldHandleCookies = false
            req.setValue("application/json", forHTTPHeaderField: "Content-Type")
            req.httpBody = try? JSONSerialization.data(withJSONObject: ["device_token": tok])
            _ = try? await URLSession.shared.data(for: req)
        }
        DeviceKey.set(nil); deviceKeyTried = false
        if error == Store.keychainFailedText { error = nil }
        DraftOwner.app.signedOut(); accountChecked = false; accountName = nil // Entwuerfe bleiben liegen, aber nur fuer ihr Konto lesbar
        AppConfig.token = ""; reloadWidgets()                           // der Server loescht den Widget-Schluessel dieses Geraets mit
        let ds = WKWebsiteDataStore.default()
        let recs = await ds.dataRecords(ofTypes: WKWebsiteDataStore.allWebsiteDataTypes())
        await ds.removeData(ofTypes: WKWebsiteDataStore.allWebsiteDataTypes(), for: recs)
        try? FileManager.default.removeItem(at: cacheDir)
        try? FileManager.default.createDirectory(at: cacheDir.appendingPathComponent("files"), withIntermediateDirectories: true)
        images.removeAllObjects(); cookieHeader = nil; myAvatar = nil; AvatarCache.shared.reset()
        URLCache.shared.removeAllCachedResponses()                      // Profilbilder (private, max-age) u. a. nicht liegen lassen
        live.stop(); notifs.reset(); calls.signedOut(); chat.signedOut(); push.signedOut()   // 2.0: Live, Glocke, Chat-Schluessel im Speicher, Push-Abo
        pages = []; archivedPages = []; lastAll = nil; shared = []; fedShared = []; aiEnabled = false; aiPersona = nil; aiChat = false; aiHost = ""; fedUnreachable = []; shareOffHint = false; tasks = []; doneTasks = []; events = []; recent = []; UserDefaults.standard.removeObject(forKey: "recent")
        // Startseite immer vergessen: die naechste Anmeldung kann ein anderes Konto sein - dessen Start sonst die
        // Seiten-ID des alten Kontos an den Server schickte (BUG-060)
        setHome(nil)
        if switchingServer { serverVersion = nil; minApp = nil }
        SpotlightIndex.clear()                                            // abgemeldet: nichts mehr in der Systemsuche
        needsLogin = true
    }

    static func pkce() -> (verifier: String, challenge: String) {
        let b64 = { (d: Data) in d.base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "") }
        var bytes = [UInt8](repeating: 0, count: 48); _ = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        let v = b64(Data(bytes))
        return (v, b64(Data(SHA256.hash(data: Data(v.utf8)))))
    }
}
