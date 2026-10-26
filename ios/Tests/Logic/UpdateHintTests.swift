import Foundation

// Update-Hinweis (Einstellungen -> Info): Versionsvergleich, Kanalwahl, Datei format 1, Links, Schaltflaeche, Cache
func updateHintTests() {
    typealias V = UpdateHint.Version
    // Vergleich
    check(V("2.5.0")! > V("2.4.9")!, "x.y.z numerisch")
    check(V("2.10.0")! > V("2.9.0")!, "2.10 > 2.9 (nicht als Text)")
    check(V("2.5.0")! > V("2.5.0-rc.140")!, "Release > jede rc")
    check(V("2.5.0-rc.10")! > V("2.5.0-rc.9")!, "rc numerisch")
    check(V("2.5.1-rc.1")! > V("2.5.0")!, "naechste Patch-rc > Release")
    check(V("2.5.0-rc.3")! == V("2.5.0-rc.3")!, "gleich")
    check(V("2.5") == nil && V("2.5.0-beta") == nil && V("dev") == nil && V("2.5.0-rc.") == nil && V("v2.5.0") == nil && V("") == nil,
          "ungueltige Labels")
    check(UpdateHint.isNewer("2.5.0-rc.141", than: "2.5.0-rc.140"), "neuer")
    check(!UpdateHint.isNewer("2.5.0-rc.140", than: "2.5.0-rc.140"), "gleich ist nicht neuer")
    check(!UpdateHint.isNewer("2.4.0", than: "2.5.0"), "aelter")
    check(!UpdateHint.isNewer("3.0.0", than: "dev-build"), "Entwickler-Label: nie")
    // Kanal + Adresse
    check(UpdateHint.channel("2.5.0-rc.140") == "testing", "rc -> testing")
    check(UpdateHint.channel("2.5.0") == "stable", "Release -> stable")
    check(UpdateHint.channel("2.5.0-dirty") == nil, "Entwickler -> keine Pruefung")
    check(UpdateHint.feedURL(label: "2.5.0-rc.1")?.absoluteString == "https://raw.githubusercontent.com/yggdrasil-sh/saga-apps/appcast/testing.json", "testing.json")
    check(UpdateHint.feedURL(label: "2.5.0")?.absoluteString.hasSuffix("/appcast/stable.json") == true, "stable.json")
    check(UpdateHint.feedURL(label: "local") == nil, "keine Adresse ohne Kanal")
    check(UpdateHint.repo(["SagaUpdateRepo": "owner/fork"]) == "owner/fork", "Repo aus dem Build")
    check(UpdateHint.repo(["SagaUpdateRepo": "$(SAGA_REPO)"]) == UpdateHint.defaultRepo, "nicht ersetzte Variable")
    check(UpdateHint.repo(["SagaUpdateRepo": "a/../b"]) == UpdateHint.defaultRepo && UpdateHint.repo([:]) == UpdateHint.defaultRepo, "Repo pruefen")
    // Links
    check(UpdateHint.safeLink("https://github.com/o/r/releases/tag/v1.0.0") != nil, "github https")
    check(UpdateHint.safeLink("http://github.com/o/r") == nil, "kein http")
    check(UpdateHint.safeLink("https://github.com.evil.example/x") == nil, "kein fremder Host")
    check(UpdateHint.safeLink("https://user@github.com/x") == nil, "keine Benutzerangabe")
    check(UpdateHint.safeLink("https://github.com:8443/x") == nil, "kein Port")
    check(UpdateHint.safeLink("https://objects.githubusercontent.com/x") == nil, "nur github.com")
    check(UpdateHint.safeLink(42) == nil, "kein Text")
    // Datei
    let good = #"{"format":1,"channel":"testing","version":"2.5.0-rc.141","tag":"v2.5.0-rc.141","commit":"abc1234","published":"2026-10-26T06:00:00Z","page":"https://github.com/o/r/releases/tag/v2.5.0-rc.141","assets":{"android":"https://github.com/o/r/releases/download/v2.5.0-rc.141/Saga.apk","macos":"https://evil.example/Saga-macOS.dmg"}}"#
    let r = UpdateHint.parse(Data(good.utf8), channel: "testing")
    check(r?.version == "2.5.0-rc.141", "Datei gelesen")
    check(r?.assets["android"] != nil && r?.assets["macos"] == nil, "unsicherer Link faellt weg")
    check(r?.page?.host == "github.com", "Release-Seite")
    check(UpdateHint.parse(Data(good.utf8), channel: "stable") == nil, "fremder Kanal")
    check(UpdateHint.parse(Data(good.replacingOccurrences(of: #""format":1"#, with: #""format":2"#).utf8), channel: "testing") == nil, "unbekanntes format")
    check(UpdateHint.parse(Data(#"{"format":1,"version":"2.5.0"}"#.utf8), channel: "testing") == nil, "Version passt nicht zum Kanal")
    check(UpdateHint.parse(Data(#"{"format":1,"version":"2.5.0"}"#.utf8), channel: "stable")?.assets.isEmpty == true, "ohne Assets")
    check(UpdateHint.parse(Data("kaputt".utf8), channel: "stable") == nil, "kein JSON")
    check(UpdateHint.parse(Data(repeating: 32, count: UpdateHint.maxBytes + 1), channel: "stable") == nil, "zu gross")
    // Schaltflaeche
    let withDMG = UpdateHint.Release(version: "2.5.0", page: URL(string: "https://github.com/o/r/releases/tag/v2.5.0"),
                                     assets: ["macos": URL(string: "https://github.com/o/r/releases/download/v2.5.0/Saga-2.5.0-macOS.dmg")!])
    let bare = UpdateHint.Release(version: "2.5.0", page: nil, assets: [:])
    check(UpdateHint.action(withDMG, platform: .mac, sparkle: true) == .sparkle, "Mac: Sparkle")
    check(UpdateHint.action(withDMG, platform: .mac, sparkle: false) == .open(withDMG.assets["macos"]!), "Mac: DMG")
    let pageOnly = UpdateHint.Release(version: "2.5.0", page: withDMG.page, assets: [:])
    check(UpdateHint.action(pageOnly, platform: .mac, sparkle: false) == .open(withDMG.page!), "Mac: Release-Seite")
    check(UpdateHint.action(bare, platform: .mac, sparkle: false) == .none, "Mac: ohne Link nur Hinweis")
    check(UpdateHint.action(withDMG, platform: .ios, sparkle: false) == .none, "iOS: keine Aktion")
    check(UpdateHint.button(checking: true, available: withDMG, platform: .mac, sparkle: true) == .checking, "Pruefe …")
    check(UpdateHint.button(checking: false, available: withDMG, platform: .mac, sparkle: false) == .update, "Update")
    check(UpdateHint.button(checking: false, available: nil, platform: .mac, sparkle: true) == .check, "Nach Updates suchen")
    check(UpdateHint.button(checking: false, available: withDMG, platform: .ios, sparkle: false) == .check, "iOS bleibt Nach Updates suchen")
    check(UpdateHint.button(checking: false, available: bare, platform: .mac, sparkle: false) == .check, "ohne Aktion kein Update-Knopf")
    // Status
    let now = Date(timeIntervalSince1970: 1_800_000_000)
    check(UpdateHint.status(available: withDMG, failed: true, checked: now) == .available("2.5.0"), "verfuegbar gewinnt")
    check(UpdateHint.status(available: nil, failed: true, checked: now) == .failed, "fehlgeschlagen")
    check(UpdateHint.status(available: nil, failed: false, checked: now) == .upToDate, "aktuell")
    check(UpdateHint.status(available: nil, failed: false, checked: nil) == .none, "noch nie geprueft")
    check(UpdateHint.available(withDMG, label: "2.5.0-rc.9") == nil, "Release nicht fuer den testing-Kanal")
    check(UpdateHint.available(withDMG, label: "2.4.1") == withDMG, "stable: neuer")
    check(UpdateHint.available(withDMG, label: "2.5.0") == nil, "nicht neuer")
    check(UpdateHint.available(withDMG, label: "dev") == nil, "Entwickler-Label")
    // Faelligkeit + Cache
    check(UpdateHint.due(last: nil, now: now, interval: UpdateHint.startInterval), "nie geprueft")
    check(!UpdateHint.due(last: now.addingTimeInterval(-3600), now: now, interval: UpdateHint.startInterval), "vor 1 h: nicht faellig")
    check(UpdateHint.due(last: now.addingTimeInterval(-13 * 3600), now: now, interval: UpdateHint.startInterval), "vor 13 h: faellig")
    check(UpdateHint.due(last: now.addingTimeInterval(3600), now: now, interval: UpdateHint.startInterval), "Uhr zurueckgestellt")
    let c = UpdateHint.Cache(checked: now, release: withDMG)
    check(UpdateHint.decode(UpdateHint.encode(c)) == c, "Cache hin und zurueck")
    check(UpdateHint.decode(Data("x".utf8)) == nil && UpdateHint.decode(nil) == nil, "kaputter Cache")
    // Spruenge in die Bereiche
    check(SettingsArea.area(for: "chats") == .einstellungen && SettingsArea.scrollTarget(for: "chats") == "chats", "Chats & Geraete")
    check(SettingsArea.area(for: "konto") == .konto && SettingsArea.scrollTarget(for: "konto") == "konto", "oeffentlicher Name")
    check(SettingsArea.area(for: "backup") == .konto && SettingsArea.area(for: "kalender") == .einstellungen, "Backup, Kalender")
    check(SettingsArea.area(for: "info") == .info && SettingsArea.scrollTarget(for: "info") == nil, "Info oben")
    check(SettingsArea.area(for: "YES") == nil && SettingsArea.area(for: nil) == nil && SettingsArea.scrollTarget(for: "x") == nil, "nur Startliste")
}
