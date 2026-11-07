import AppKit
import AuthenticationServices
import CryptoKit
import Network
import UserNotifications
import WebKit
import WidgetKit

/// Mac-Huelle (Desktop-Huelle P2): die Web-Oberflaeche in einem `WKWebView`, dazu nur, was ein Browser-Tab nicht kann
/// (Anmeldung mit Geraete-Schluessel im Schluesselbund, Dock-Zahl, Widgets anstossen, Systembrowser, Deep-Links,
/// Downloads, Offline-Seite). Vertrag mit dem Web: docs/APP-API-HOST.md im Server-Repo.
/// Etappe 2: Widget- und Teilen-Erweiterung (eigene Ziele mit der App-Gruppe der Huelle), APNs (ShellPush),
/// Menueleisten-Erfassung, Spotlight, Sparkle (ShellExtras), QR-Kopplung und `saga://new` nativ.
@MainActor
final class ShellModel: NSObject, ObservableObject {
    enum Phase: Equatable { case starting, signIn, web }

    static let shared = ShellModel()

    @Published var phase: Phase = .starting
    @Published var loading = true                     // Ladeanzeige ueber der Web-Ansicht (bis `ready`/erstes Laden)
    @Published var offline = false                    // lokale Offline-Seite wird gezeigt
    @Published var busy = false
    @Published var error: String?
    @Published var title = "Saga"
    @Published var accountName = UserDefaults.standard.string(forKey: "shellAccountName") ?? ""
    @Published var showSettings = false
    private var switchNotice = false                  // P3: einmaliger Hinweis nach der Umstellung von der nativen App
    @Published var toast: String?
    @Published var serverField = AppConfig.serverString

    let webView: WKWebView
    var version: String { Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0" }
    var versionLabel: String { Bundle.main.object(forInfoDictionaryKey: "SagaVersionLabel") as? String ?? version }

    private var webReady = false                      // Web hat `ready` gesendet (Bruecke steht)
    private var pendingPath: String?                  // Deep-Link vor `ready`
    private var offlinePath = "/"                     // dahin nach "Erneut versuchen"
    private var lastResume = Date.distantPast
    private var resuming = false                      // /app/resume laeuft: Links nur merken (pendingPath)
    private var widgetTimer: Timer?
    private var lastWidgetReload = Date.distantPast
    private var authSession: ASWebAuthenticationSession?
    private var titleObs: NSKeyValueObservation?
    private let pathMonitor = NWPathMonitor()
    private var netUp: Bool?
    var popups: [PopupCatcher] = []                   // offene leere Fenster (window.open('', '_blank'))
    let push = ShellPush()

    override init() {
        let cfg = WKWebViewConfiguration()
        cfg.websiteDataStore = .default()                                     // Sitzung, IndexedDB, Service Worker bleiben
        cfg.applicationNameForUserAgent = ShellLogic.userAgent(version: Self.bundleVersion)
        cfg.preferences.isElementFullscreenEnabled = true                     // Videos im Chat, Bildschirm teilen
        cfg.preferences.javaScriptCanOpenWindowsAutomatically = true
        cfg.userContentController.addUserScript(WKUserScript(source: ShellLogic.hostInfoScript(version: Self.bundleVersion),
                                                             injectionTime: .atDocumentStart, forMainFrameOnly: true))
        webView = WKWebView(frame: .zero, configuration: cfg)
        super.init()
        cfg.userContentController.add(ShellWeakHandler(self), name: "hk")
        webView.navigationDelegate = self
        webView.uiDelegate = self
        webView.allowsBackForwardNavigationGestures = true
        webView.allowsMagnification = true
        #if DEBUG
        webView.isInspectable = true
        #else
        webView.isInspectable = UserDefaults.standard.bool(forKey: "SagaInspectable")
        #endif
        titleObs = webView.observe(\.title, options: [.new]) { [weak self] wv, _ in
            Task { @MainActor in
                guard let self, !self.offline else { return }
                let t = (wv.title ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
                self.title = t.isEmpty ? "Saga" : t
            }
        }
        let nc = NotificationCenter.default
        nc.addObserver(forName: NSApplication.didBecomeActiveNotification, object: nil, queue: .main) { [weak self] _ in
            Task { @MainActor in self?.send(["type": "active", "active": true]) }
        }
        nc.addObserver(forName: NSApplication.didResignActiveNotification, object: nil, queue: .main) { [weak self] _ in
            Task { @MainActor in self?.send(["type": "active", "active": false]); self?.reloadWidgets(force: true) }
        }
        pathMonitor.pathUpdateHandler = { [weak self] p in
            Task { @MainActor in self?.network(up: p.status == .satisfied) }
        }
        pathMonitor.start(queue: .main)
        push.model = self
    }

    static var bundleVersion: String { Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0" }
    var server: URL { AppConfig.server }

    // MARK: Start, Sitzung

    func start() {
        let d = UserDefaults.standard
        switchNotice = ShellLogic.showsSwitchNotice(noticeShown: d.bool(forKey: "shellSwitchNoticeShown"),
                                                    hasDeviceToken: AppConfig.hasServer && ShellKeys.deviceToken() != nil,
                                                    hasShellAccount: d.object(forKey: "shellAccountName") != nil)
        guard AppConfig.hasServer, ShellKeys.deviceToken() != nil else { phase = .signIn; return }
        Task { await resumeAndLoad(pendingPath ?? "/") }
    }

    /// Sitzung mit dem Geraete-Schluessel erneuern und eine eigene Adresse laden
    func resumeAndLoad(_ path: String) async {
        phase = .web; loading = true
        lastResume = Date()
        resuming = true
        let r = await resume()
        resuming = false
        // Deep-Link/Mitteilungsklick waehrend des Erneuerns: der gewinnt vor dem Startpfad
        let target = pendingPath ?? path
        pendingPath = nil
        switch r {
        case .ok: load(target); signedIn()
        case .offline: load(target)                        // offline: Seite trotzdem laden (Cookie/Service Worker), sonst Offline-Seite
        case .rejected:
            await clearLocal()
            error = String(localized: "Diese Anmeldung gilt nicht mehr. Bitte neu anmelden.")
            phase = .signIn
        }
    }

    enum ResumeResult { case ok, rejected, offline }

    private func resume() async -> ResumeResult {
        guard AppConfig.isAllowedServer(server), let tok = ShellKeys.deviceToken() else { return .rejected }
        var body: [String: Any] = ["device_token": tok]
        if AppConfig.hasToken { body["app_key"] = AppConfig.token }      // Widget-Schluessel diesem Geraet zuordnen
        guard let (d, http) = await post("app/resume", body) else { return .offline }
        if http.statusCode == 401 || http.statusCode == 403 { return .rejected }
        guard http.statusCode == 200 else { return .offline }
        await adoptCookies(http)
        if let n = (try? JSONSerialization.jsonObject(with: d) as? [String: Any])?["name"] as? String { setAccount(n) }
        return .ok
    }

    private func post(_ path: String, _ body: [String: Any], base: URL? = nil) async -> (Data, HTTPURLResponse)? {
        var req = URLRequest(url: (base ?? server).appendingPathComponent(path), timeoutInterval: 20)
        req.httpMethod = "POST"; req.httpShouldHandleCookies = false
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.setValue(AppConfig.appLanguage, forHTTPHeaderField: "Accept-Language")
        req.httpBody = try? JSONSerialization.data(withJSONObject: body)
        guard let (d, r) = try? await URLSession.shared.data(for: req), let http = r as? HTTPURLResponse else { return nil }
        return (d, http)
    }

    /// Sitzungs-Cookie der Antwort in den Cookie-Speicher des WebViews (docs Abschnitt 7.2)
    private func adoptCookies(_ http: HTTPURLResponse) async {
        guard let url = http.url else { return }
        let h = http.allHeaderFields.reduce(into: [String: String]()) { r, kv in
            if let k = kv.key as? String, let v = kv.value as? String { r[k] = v }
        }
        for c in HTTPCookie.cookies(withResponseHeaderFields: h, for: url) {
            await webView.configuration.websiteDataStore.httpCookieStore.setCookie(c)
        }
    }

    private func setAccount(_ n: String) {
        accountName = String(n.prefix(80))
        UserDefaults.standard.set(accountName, forKey: "shellAccountName")
    }

    func load(_ path: String) {
        let p = path.hasPrefix("/") && !path.hasPrefix("//") ? path : "/"
        guard let u = URL(string: server.absoluteString + p) else { return }
        offline = false; webReady = false; loading = true; phase = .web
        webView.load(URLRequest(url: u))
    }

    func reload() {
        if offline || webView.url == nil { Task { await resumeAndLoad(offline ? offlinePath : "/") } } else { webView.reload() }
    }

    // MARK: Anmeldung (Systembrowser, PKCE: /app/login -> saga://auth -> /app/exchange)

    func signIn() async {
        error = nil
        guard let target = AppConfig.normalizedServer(serverField) else {
            error = String(localized: "Bitte eine https-Adresse eingeben, z. B. https://saga.example.org"); return
        }
        busy = true; defer { busy = false }
        guard await probe(target) else {
            error = String(localized: "Unter dieser Adresse antwortet kein Saga-Server – bitte die Adresse prüfen."); return
        }
        let verifier = Self.randomB64(32)
        let challenge = Self.b64url(Data(SHA256.hash(data: Data(verifier.utf8))))
        guard let url = URL(string: target.absoluteString + "/app/login?challenge=" + challenge + "&scheme=saga&lang=" + AppConfig.appLanguage)
        else { return }
        let cb: URL? = await withCheckedContinuation { cont in
            let once = Once(cont)
            let s = ASWebAuthenticationSession(url: url, callbackURLScheme: "saga") { u, _ in once.resume(u) }
            s.presentationContextProvider = self
            authSession = s
            if !s.start() { once.resume(nil) }
        }
        authSession = nil
        guard let cb, case .auth(let code) = ShellLogic.route(cb) else { return }           // abgebrochen
        guard let code, !code.isEmpty else { error = String(localized: "Keine Antwort vom Server"); return }
        await exchange(target, code: code, verifier: verifier)
    }

    private func probe(_ target: URL) async -> Bool {
        var req = URLRequest(url: target.appendingPathComponent("api/version"), timeoutInterval: 10)
        req.httpShouldHandleCookies = false
        guard let (d, r) = try? await URLSession.shared.data(for: req), (r as? HTTPURLResponse)?.statusCode == 200,
              let j = try? JSONSerialization.jsonObject(with: d) as? [String: Any] else { return false }
        return j["version"] != nil
    }

    private func exchange(_ target: URL, code: String, verifier: String) async {
        let switching = AppConfig.serverOrigin != AppConfig.origin(target)
        let body: [String: Any] = ["code": code, "verifier": verifier, "device": ShellKeys.deviceName, "app_key": true]
        guard let (d, http) = await post("app/exchange", body, base: target) else {
            error = String(localized: "Server nicht erreichbar – bitte die Adresse prüfen."); return
        }
        let j = (try? JSONSerialization.jsonObject(with: d)) as? [String: Any]
        guard http.statusCode == 200, let tok = j?["device_token"] as? String, tok.hasPrefix("hkd_") else {
            error = j?["error"] as? String ?? String(localized: "Anmeldung fehlgeschlagen"); return
        }
        if switching { await clearLocal() }                                  // anderer Server: nichts vom alten mitnehmen
        adopt(target, token: tok, json: j)
        await adoptCookies(http)
        load(pendingPath ?? "/")
        pendingPath = nil
        signedIn()
    }

    /// Neue Geraete-Anmeldung uebernehmen (Browser-Anmeldung, Kopplung)
    private func adopt(_ target: URL, token tok: String, json j: [String: Any]?) {
        AppConfig.server = target
        serverField = AppConfig.serverString
        ShellKeys.setDeviceToken(tok)
        if let k = ShellLogic.appKey(j?["app_key"]) { AppConfig.token = k; reloadWidgets(force: true) }
        if let n = j?["name"] as? String { setAccount(n) }
    }

    /// Angemeldet (Start, Anmeldung, Kopplung): Mitteilungen erneut anmelden, Spotlight abgleichen
    private func signedIn() {
        push.appStarted()
        ShellSpotlight.shared.refresh(force: true)
    }

    // MARK: Kopplung per QR-Code (saga://pair, immer mit Rueckfrage - docs Abschnitt 7.4)

    private var pairAsking = false
    var isSignedIn: Bool { AppConfig.hasServer && ShellKeys.deviceToken() != nil }

    func askPair(_ link: URL) {
        guard !busy, authSession == nil, !pairAsking else { return }        // laufende Anmeldung/Kopplung: Link verwerfen
        pairAsking = true; defer { pairAsking = false }
        guard let (target, code) = ShellLogic.pairLink(link.absoluteString, allowed: AppConfig.isAllowedServer) else {
            alert(String(localized: "Kopplung per QR-Code"), String(localized: "Das ist kein Saga-QR-Code.")); return
        }
        let a = NSAlert()
        a.messageText = String(localized: "Mit diesem Server koppeln?")
        var info = [String(localized: "Saga meldet sich bei \(AppConfig.displayHost(target)) an.")]
        let notice = ShellLogic.pairNotice(target: target, current: AppConfig.hasServer ? server : nil, signedIn: isSignedIn)
        switch notice {
        case .none: break
        case .otherServer(let h): info.append("⚠️ " + String(localized: "Du bist gerade bei \(h) angemeldet und wirst dort abgemeldet."))
        case .sameServer:
            info.append("⚠️ " + (accountName.isEmpty
                ? String(localized: "Du wirst abgemeldet und mit dem Konto aus dem QR-Code angemeldet.")
                : String(localized: "Du wirst als „\(accountName)“ abgemeldet und mit dem Konto aus dem QR-Code angemeldet.")))
        }
        a.informativeText = info.joined(separator: "\n\n")
        a.addButton(withTitle: String(localized: "Koppeln"))
        a.addButton(withTitle: String(localized: "Abbrechen"))
        if notice != .none { a.buttons.first?.hasDestructiveAction = true }
        NSApp.activate()
        guard a.runModal() == .alertFirstButtonReturn else { return }
        Task { await pair(target, code: code) }
    }

    private func pair(_ target: URL, code: String) async {
        busy = true; defer { busy = false }
        let oldTok = ShellKeys.deviceToken(), oldBase = AppConfig.hasServer ? server : nil
        guard let (d, http) = await post("app/pair", ["code": code, "device": ShellKeys.deviceName, "app_key": true], base: target) else {
            alert(String(localized: "Kopplung fehlgeschlagen"), String(localized: "Server nicht erreichbar – bitte die Adresse prüfen.")); return
        }
        let j = (try? JSONSerialization.jsonObject(with: d)) as? [String: Any]
        guard http.statusCode == 200, let tok = j?["device_token"] as? String, tok.hasPrefix("hkd_") else {
            alert(String(localized: "Kopplung fehlgeschlagen"), j?["error"] as? String ?? String(localized: "Anmeldung fehlgeschlagen")); return
        }
        // bisherige Anmeldung sauber beenden (beim Server, der sie ausgestellt hat), dann die neue uebernehmen
        if let oldTok, let oldBase { _ = await post("app/forget", ["device_token": oldTok], base: oldBase) }
        if oldTok != nil || AppConfig.serverOrigin != AppConfig.origin(target) { await clearLocal() }
        error = nil
        adopt(target, token: tok, json: j)
        await resumeAndLoad(pendingPath ?? "/")
        pendingPath = nil
    }

    func alert(_ title: String, _ text: String) {
        let a = NSAlert(); a.messageText = title; a.informativeText = text
        NSApp.activate(); a.runModal()
    }

    // MARK: saga://new?db=&view=&k= bzw. ?parent= - nativ anlegen (wie RootView.createFromLink)

    func create(_ e: ShellLogic.NewEntry) {
        guard isSignedIn else { pendingPath = e.fallbackPath; return }
        if !e.trusted(secret: AppConfig.linkSecret) {
            let a = NSAlert()
            a.messageText = String(localized: "Neuen Eintrag anlegen?")
            a.informativeText = String(localized: "Ein Link möchte in Saga einen neuen Eintrag anlegen.")
            a.addButton(withTitle: String(localized: "Anlegen"))
            a.addButton(withTitle: String(localized: "Abbrechen"))
            NSApp.activate()
            guard a.runModal() == .alertFirstButtonReturn else { return }
        }
        Task {
            var body: [String: Any] = ["title": ""]
            if let db = e.db {
                body["parent_id"] = db
                if let d = try? await API.rows(db), let j = (try? JSONSerialization.jsonObject(with: d)) as? [String: Any] {
                    let p = ShellLogic.rowDefaults(schema: j["schema"] as? [String: Any], viewID: e.view)
                    if !p.isEmpty { body["props"] = p }
                }
            } else if let p = e.parent { body["parent_id"] = p }
            if let (d, code) = await sessionAPI("api/notes", method: "POST", body: body), (200..<300).contains(code),
               let id = ((try? JSONSerialization.jsonObject(with: d)) as? [String: Any])?["id"] as? Int {
                openPath("/notes#\(id)")
                reloadWidgets(force: false)
            } else {
                show(toast: String(localized: "Eintrag konnte nicht angelegt werden"))
                openPath(e.fallbackPath)
            }
        }
    }

    /// Anfrage mit der Web-Sitzung (Cookie aus dem WKWebView-Speicher), z. B. Mitteilungs-Abo, neuer Eintrag
    func sessionAPI(_ path: String, method: String = "GET", body: [String: Any]? = nil) async -> (Data, Int)? {
        let r = await sessionOnce(path, method: method, body: body)
        // Web-Sitzung abgelaufen: einmal still mit dem Geraete-Schluessel erneuern und wiederholen
        guard r?.1 == 401, isSignedIn, case .ok = await resume() else { return r }
        return await sessionOnce(path, method: method, body: body)
    }

    private func sessionOnce(_ path: String, method: String, body: [String: Any]?) async -> (Data, Int)? {
        guard AppConfig.hasServer, AppConfig.isAllowedServer(server),
              let url = URL(string: server.absoluteString.trimmingCharacters(in: CharacterSet(charactersIn: "/")) + "/" + path) else { return nil }
        let all = await webView.configuration.websiteDataStore.httpCookieStore.allCookies()
        let mine = all.filter { c in
            let d = c.domain.hasPrefix(".") ? String(c.domain.dropFirst()) : c.domain
            return (url.host ?? "") == d || (c.domain.hasPrefix(".") && (url.host ?? "").hasSuffix(c.domain))
        }
        var req = URLRequest(url: url, timeoutInterval: 20)
        req.httpMethod = method; req.httpShouldHandleCookies = false
        for (k, v) in HTTPCookie.requestHeaderFields(with: mine) { req.setValue(v, forHTTPHeaderField: k) }
        req.setValue("1", forHTTPHeaderField: "X-Saga")
        req.setValue(AppConfig.appLanguage, forHTTPHeaderField: "Accept-Language")
        if let body {
            req.setValue("application/json", forHTTPHeaderField: "Content-Type")
            req.httpBody = try? JSONSerialization.data(withJSONObject: body)
        }
        guard let (d, r) = try? await URLSession.shared.data(for: req), let http = r as? HTTPURLResponse else { return nil }
        return (d, http.statusCode)
    }

    /// Abmelden: Geraete-Anmeldung beim Server loeschen, Schluessel und alle Website-Daten weg (docs 7.3)
    func signOut() async {
        if let tok = ShellKeys.deviceToken() { _ = await post("app/forget", ["device_token": tok]) }
        await clearLocal()
        phase = .signIn
    }

    private func clearLocal() async {
        ShellKeys.setDeviceToken(nil)
        AppConfig.token = ""
        setAccount("")
        NSApp.dockTile.badgeLabel = nil
        webView.stopLoading()
        webView.loadHTMLString("", baseURL: nil)
        let store = webView.configuration.websiteDataStore
        await store.removeData(ofTypes: WKWebsiteDataStore.allWebsiteDataTypes(), modifiedSince: .distantPast)
        reloadWidgets(force: true)
        ShellSpotlight.shared.clear()
        push.signedOut()
        UNUserNotificationCenter.current().removeAllDeliveredNotifications()
    }

    // MARK: Bruecke Web -> Huelle (docs Abschnitt 3)

    func receive(_ m: WKScriptMessage) {
        guard let body = m.body as? [String: Any], let action = body["action"] as? String else { return }
        if action == "retry" {                                               // nur von der lokalen Offline-Seite
            if offline { Task { await resumeAndLoad(offlinePath) } }
            return
        }
        let o = m.frameInfo.securityOrigin
        guard m.frameInfo.isMainFrame, !offline,
              ShellLogic.origin(scheme: o.protocol, host: o.host, port: o.port == 0 ? nil : o.port) == ShellLogic.origin(server)
        else { return }
        switch action {
        case "ready":
            webReady = true; loading = false
            if let p = pendingPath { pendingPath = nil; openPath(p) }
            if switchNotice { switchNotice = false; showSwitchNotice() }
        case "badge":
            let l = ShellLogic.badgeLabel(body["n"])
            NSApp.dockTile.badgeLabel = l.isEmpty ? nil : l
        case "widgets.reload":
            reloadWidgets(force: false)
            ShellSpotlight.shared.refresh(force: false)
        case "openExternal":
            if let u = ShellLogic.externalURL(body["url"], server: server) { NSWorkspace.shared.open(u) }
        case "settings":
            showSettings = true
        case "token":
            if let k = ShellLogic.appKey(body["token"]) {
                AppConfig.token = k; reloadWidgets(force: true)
                show(toast: String(localized: "In dieser App gespeichert ✓"))
            }
        default: break
        }
    }

    /// Ereignis an das Web (docs Abschnitt 4) - nur, wenn die eigene Seite geladen ist
    func send(_ evt: [String: Any]) {
        guard webReady, !offline, ShellLogic.isOwn(webView.url, server: server), let js = ShellLogic.eventJS(evt) else { return }
        webView.evaluateJavaScript(js, completionHandler: nil)
    }

    /// Widgets neu laden: hoechstens alle 10 s (das Web drosselt schon; zweite Sicherung, WidgetKit hat ein Budget)
    func reloadWidgets(force: Bool) {
        let wait = 10 - Date().timeIntervalSince(lastWidgetReload)
        if force || wait <= 0 {
            widgetTimer?.invalidate(); widgetTimer = nil
            lastWidgetReload = Date()
            WidgetCenter.shared.reloadAllTimelines()
        } else if widgetTimer == nil {
            widgetTimer = Timer.scheduledTimer(withTimeInterval: wait, repeats: false) { [weak self] _ in
                Task { @MainActor in self?.widgetTimer = nil; self?.reloadWidgets(force: true) }
            }
        }
    }

    /// Nach der Umstellung von der nativen Mac-App (gleiche App-ID, Anmeldung uebernommen): einmal erklaeren, was neu ist
    private func showSwitchNotice() {
        UserDefaults.standard.set(true, forKey: "shellSwitchNoticeShown")
        let a = NSAlert()
        a.messageText = String(localized: "Saga am Mac ist jetzt die Web-Oberfläche")
        a.informativeText = String(localized: "Deine Anmeldung, die Widgets und das Teilen-Menü bleiben. Für verschlüsselte Chats bestätigst du diesen Mac einmal neu, wenn Saga danach fragt.")
        a.addButton(withTitle: String(localized: "OK"))
        if let w = webView.window { a.beginSheetModal(for: w) } else { a.runModal() }
    }

    func show(toast t: String) {
        toast = t
        Task { try? await Task.sleep(for: .seconds(2.5)); if toast == t { toast = nil } }
    }

    // MARK: Deep-Links (docs Abschnitt 6)

    func handle(link: URL) {
        switch ShellLogic.route(link) {
        case .path(let p): openPath(p); NSApp.activate()
        case .auth: break                                                    // gehoert zur laufenden Anmeldung (ASWebAuthenticationSession)
        case .pair(let u): askPair(u)
        case .create(let e): create(e); NSApp.activate()
        case .none: break
        }
    }

    func openPath(_ p: String) {
        guard phase == .web, !offline, !resuming else { pendingPath = p; return }
        if webReady { send(["type": "open", "path": p]) } else if webView.url == nil { load(p) } else { pendingPath = p }
    }

    func openWebSettings() { openPath("/notes?settings=account") }

    /// Mitteilung angeklickt (docs Abschnitt 4: `notificationClick`) - Ziel nur als eigener Pfad
    func notificationClicked(_ url: Any?) {
        let p = ShellLogic.notificationPath(url, server: server)
        NSApp.activate()
        guard phase == .web, !offline, !resuming else { pendingPath = p; return }
        if webReady { send(["type": "notificationClick", "url": p]) } else { openPath(p) }
    }

    // MARK: Offline (O0)

    func showOffline(for url: URL?) {
        if let u = url, ShellLogic.isOwn(u, server: server) { offlinePath = ShellLogic.pathOf(u) }
        guard let f = Bundle.main.url(forResource: "offline", withExtension: "html"),
              let html = try? String(contentsOf: f, encoding: .utf8) else { return }
        offline = true; webReady = false; loading = false; title = "Saga"
        webView.loadHTMLString(html, baseURL: nil)
    }

    private func network(up: Bool) {
        defer { netUp = up }
        guard let was = netUp, was != up else { return }
        send(["type": up ? "online" : "offline"])
        if up, offline { Task { await resumeAndLoad(offlinePath) } }
    }

    /// Navigation zu /login: Sitzung abgelaufen -> erneuern; bye=1 -> im Web abgemeldet
    func loginNavigation(_ nav: ShellLogic.LoginNav) {
        switch nav {
        case .none: break
        case .expired(let next):
            if Date().timeIntervalSince(lastResume) < 15 {                    // gerade erneuert und wieder raus: neu anmelden
                Task { await clearLocal(); error = String(localized: "Diese Anmeldung gilt nicht mehr. Bitte neu anmelden."); phase = .signIn }
            } else {
                Task { await resumeAndLoad(next) }
            }
        case .loggedOut:
            Task { await signOut() }
        }
    }

    // MARK: Hilfen

    static func b64url(_ d: Data) -> String {
        d.base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
    static func randomB64(_ n: Int) -> String {
        var b = [UInt8](repeating: 0, count: n)
        _ = SecRandomCopyBytes(kSecRandomDefault, n, &b)
        return b64url(Data(b))
    }
}

extension ShellModel: ASWebAuthenticationPresentationContextProviding {
    nonisolated func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        MainActor.assumeIsolated { NSApp.keyWindow ?? NSApp.windows.first ?? NSWindow() }
    }
}

/// Fortsetzung genau einmal (Abschluss der Anmeldung kann zusaetzlich zu einem Startfehler kommen)
final class Once: @unchecked Sendable {
    private var cont: CheckedContinuation<URL?, Never>?
    private let lock = NSLock()
    init(_ c: CheckedContinuation<URL?, Never>) { cont = c }
    func resume(_ u: URL?) {
        lock.lock(); let c = cont; cont = nil; lock.unlock()
        c?.resume(returning: u)
    }
}

/// WKUserContentController haelt seine Handler stark -> schwacher Verweis gegen einen Haltezyklus
final class ShellWeakHandler: NSObject, WKScriptMessageHandler {
    weak var target: ShellModel?
    init(_ t: ShellModel) { target = t }
    func userContentController(_ uc: WKUserContentController, didReceive m: WKScriptMessage) {
        MainActor.assumeIsolated { target?.receive(m) }
    }
}

/// Geraete-Schluessel (hkd_) der Huelle im Schluesselbund - nur fuer die Huelle, an den ausstellenden Server gebunden
enum ShellKeys {
    static func deviceToken() -> String? {
        guard AppConfig.keyMatchesServer(device: true) else { return nil }
        return Keychain.read(service: AppConfig.deviceKeyService, account: AppConfig.deviceKeyAccount).flatMap { $0.hasPrefix("hkd_") ? $0 : nil }
    }
    static func setDeviceToken(_ v: String?) {
        Keychain.write(v, service: AppConfig.deviceKeyService, account: AppConfig.deviceKeyAccount, group: AppConfig.privateKeychainGroup)
        AppConfig.setKeyOrigin(device: true, v == nil ? nil : AppConfig.serverOrigin)
    }
    /// Name in den Einstellungen (Geraete): Rechnername + Hinweis auf die Desktop-App
    static var deviceName: String {
        let n = (Host.current().localizedName ?? "Mac").trimmingCharacters(in: .whitespacesAndNewlines)
        return String((n.isEmpty ? "Mac" : n).prefix(40)) + " · " + String(localized: "Desktop")
    }
}
