import SwiftUI

// Update-Hinweis (Einstellungen -> Info): fragt die Update-Info der CI ab (UpdateHintLogic.swift), merkt das Ergebnis
// in UserDefaults und liefert Statuszeile + die eine Schaltflaeche. Anfrage: ephemere Sitzung ohne Cookies/Cache,
// nur die Datei, 10 s Zeitlimit. Fehler bleiben still - sichtbar nur nach "Nach Updates suchen".
@MainActor
final class UpdateHintModel: ObservableObject {
    static let shared = UpdateHintModel()

    private static let cacheKey = "hk.updateHint.cache"
    private static let enabledKey = "hk.updateHint.enabled"
    let label = Store.appVersionLabel

    @Published private(set) var checked: Date?
    @Published private(set) var release: UpdateHint.Release?
    @Published private(set) var checking = false
    @Published private(set) var failed = false
    @Published var enabled: Bool {
        didSet { UserDefaults.standard.set(enabled, forKey: Self.enabledKey); if !enabled { failed = false } }
    }

    private init() {
        enabled = UserDefaults.standard.object(forKey: Self.enabledKey) as? Bool ?? true
        if let c = UpdateHint.decode(UserDefaults.standard.data(forKey: Self.cacheKey)) { checked = c.checked; release = c.release }
    }

    /// Bekanntes Update im eigenen Kanal (nil: keins bzw. Hinweis aus)
    var available: UpdateHint.Release? { enabled ? UpdateHint.available(release, label: label) : nil }
    var channel: String? { UpdateHint.channel(label) }
    var status: UpdateHint.Status { UpdateHint.status(available: available, failed: failed, checked: checked) }

    /// App-Start: hoechstens alle 12 h
    func appStarted() { auto(interval: UpdateHint.startInterval) }
    /// Info geoeffnet: nur, wenn das letzte Ergebnis nicht ganz frisch ist
    func infoOpened() { auto(interval: UpdateHint.infoInterval) }

    private func auto(interval: TimeInterval) {
        guard enabled, !checking, UpdateHint.due(last: checked, now: Date(), interval: interval) else { return }
        Task { await check(manual: false) }
    }

    /// "Nach Updates suchen": sofort, ohne Zwischenspeicher
    func check(manual: Bool) async {
        guard enabled, !checking else { return }
        guard let url = UpdateHint.feedURL(label: label, repo: UpdateHint.repo(Bundle.main.infoDictionary ?? [:])),
              let ch = channel else { if manual { failed = true }; return }
        checking = true; defer { checking = false }
        if manual { failed = false }
        do {
            let r = try await Self.fetch(url, channel: ch)
            release = r; checked = Date(); failed = false
            if let d = UpdateHint.encode(.init(checked: checked!, release: r)) { UserDefaults.standard.set(d, forKey: Self.cacheKey) }
        } catch {
            if manual { failed = true }                       // automatische Pruefung: still, alter Stand bleibt
        }
    }

    private struct Bad: Error {}

    nonisolated private static func fetch(_ url: URL, channel: String) async throws -> UpdateHint.Release {
        let cfg = URLSessionConfiguration.ephemeral
        cfg.timeoutIntervalForRequest = 10; cfg.timeoutIntervalForResource = 10
        cfg.requestCachePolicy = .reloadIgnoringLocalAndRemoteCacheData
        cfg.httpShouldSetCookies = false; cfg.httpCookieAcceptPolicy = .never; cfg.urlCache = nil
        let session = URLSession(configuration: cfg)
        defer { session.finishTasksAndInvalidate() }
        var req = URLRequest(url: url); req.httpMethod = "GET"
        let (data, res) = try await session.data(for: req)
        guard (res as? HTTPURLResponse)?.statusCode == 200, let r = UpdateHint.parse(data, channel: channel) else { throw Bad() }
        return r
    }
}

/// Info: Schalter, Statuszeile und die eine Schaltflaeche ("Nach Updates suchen" / "Pruefe …" / "Update")
struct UpdateHintSection: View {
    @ObservedObject var model = UpdateHintModel.shared
    @Environment(\.openURL) private var openURL
    #if os(macOS)
    @ObservedObject var updater = AppUpdater.shared
    private var platform: UpdateHint.Platform { .mac }
    private var sparkle: Bool { updater.enabled }
    #else
    private var platform: UpdateHint.Platform { .ios }
    private var sparkle: Bool { false }
    #endif

    var body: some View {
        Section {
            Toggle("Auf neue Versionen hinweisen", isOn: $model.enabled)
            if model.enabled {
                statusLine
                HStack {
                    Spacer(minLength: 0)
                    button
                }
            }
            #if os(macOS)
            if updater.enabled {                                   // Sparkle (nur DMG-Builds): eigener Zeitplan
                Toggle("Automatisch nach Updates suchen", isOn: Binding(get: { updater.automaticChecks }, set: { updater.setAutomaticChecks($0) }))
            }
            #endif
        } header: { Text("Updates") } footer: {
            switch UpdateHint.channel(model.label) {
            case "testing": Text("Diese Testversion erhält nur neue Testversionen.")
            case "stable": Text("Diese Version erhält nur stabile Versionen.")
            default: Text("Eigener Build: keine Update-Prüfung.")
            }
        }
        .task { model.infoOpened() }
    }

    @ViewBuilder private var statusLine: some View {
        VStack(alignment: .leading, spacing: 4) {
            switch model.status {
            case .available(let v):
                if platform == .ios { Label("Saga \(v) ist verfügbar – kommt automatisch über die Geräteverwaltung.", systemImage: "arrow.down.circle.fill").foregroundStyle(.tint) }
                else { Label("Saga \(v) ist verfügbar", systemImage: "arrow.down.circle.fill").foregroundStyle(.tint) }
            case .upToDate: Label("Du hast die neueste Version.", systemImage: "checkmark.circle").foregroundStyle(.secondary)
            case .failed: Label("Prüfung fehlgeschlagen", systemImage: "exclamationmark.triangle").foregroundStyle(.orange)
            case .none: EmptyView()
            }
            if let c = model.checked {
                Text("Zuletzt geprüft: \(c.formatted(date: .abbreviated, time: .shortened))").font(.footnote).foregroundStyle(.secondary)
            }
        }
        .fixedSize(horizontal: false, vertical: true)
    }

    @ViewBuilder private var button: some View {
        switch UpdateHint.button(checking: model.checking, available: model.available, platform: platform, sparkle: sparkle) {
        case .checking: Button("Prüfe …") {}.disabled(true)
        case .check: Button("Nach Updates suchen") { Task { await model.check(manual: true) } }
        case .update: Button("Update") { runUpdate() }.prominentGold()
        }
    }

    private func runUpdate() {
        guard let r = model.available else { return }
        switch UpdateHint.action(r, platform: platform, sparkle: sparkle) {
        case .sparkle:
            #if os(macOS)
            updater.checkForUpdates()
            #endif
        case .open(let u): openURL(u)
        case .none: break
        }
    }
}
