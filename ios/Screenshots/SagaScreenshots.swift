import XCTest

/// Screenshots fuer README/Projektseite - nur gegen einen Demo-Server mit Beispieldaten.
/// Laeuft nur, wenn SAGA_SHOT_DIR gesetzt ist (beim Aufruf als TEST_RUNNER_SAGA_SHOT_DIR).
/// Anmeldung: SAGA_PAIR_LINK (TEST_RUNNER_SAGA_PAIR_LINK) = Kopplungs-Link <app>://pair?u=...&c=...
/// Optional SAGA_SHOT_PAGE / SAGA_SHOT_DB: Seiten-IDs von "Willkommen" und der Datenbank.
final class SagaScreenshots: XCTestCase {
    private let env = ProcessInfo.processInfo.environment
    private var app: XCUIApplication!
    private var dir: URL!

    override func setUpWithError() throws {
        guard let d = env["SAGA_SHOT_DIR"], !d.isEmpty else { throw XCTSkip("SAGA_SHOT_DIR fehlt") }
        let pad = UIDevice.current.userInterfaceIdiom == .pad
        if name.contains("IPad") != pad { throw XCTSkip(pad ? "nur iPhone" : "nur iPad") }
        dir = URL(fileURLWithPath: d, isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        continueAfterFailure = false
        app = XCUIApplication()
        app.launchArguments += ["-AppleLanguages", "(de)", "-AppleLocale", "de_DE"]
        app.launch()
        try signInIfNeeded()
    }

    // MARK: - Motive (Design hell/dunkel stellt das Skript per "simctl ui" ein, SAGA_SHOT_MODE=light|dark)

    func testIPhone() throws {
        let page = value("SAGA_SHOT_PAGE", "1"), db = value("SAGA_SHOT_DB", "5")
        if dark {
            open("hlidskjalf://today"); shot("ios-dunkel-start")
            open("hlidskjalf://page/\(page)"); shot("ios-dunkel-seite")
            open("hlidskjalf://page/\(db)"); board(); shot("ios-dunkel-board")
            return
        }
        open("hlidskjalf://today"); shot("ios-start")
        open("hlidskjalf://page/\(page)"); shot("ios-seite")
        open("hlidskjalf://tasks"); shot("ios-aufgaben")
        open("hlidskjalf://page/\(db)"); shot("ios-projekte")
        board(); shot("ios-projekte-board")
        open("hlidskjalf://today")
        let services = app.staticTexts["Dienste"].firstMatch
        if services.waitForExistence(timeout: 5) { services.tap(); settle(); shot("ios-dienste") }
    }

    func testIPad() throws {
        let page = value("SAGA_SHOT_PAGE", "1"), db = value("SAGA_SHOT_DB", "5")
        XCUIDevice.shared.orientation = .landscapeLeft
        let p = dark ? "ipad-dunkel-" : "ipad-"
        open("hlidskjalf://page/\(page)"); shot(p + "seite")
        open("hlidskjalf://page/\(db)"); shot(p + "projekte")
        board(); shot(p + "projekte-board")
        open("hlidskjalf://tasks"); shot(p + "aufgaben")
        XCUIDevice.shared.orientation = .portrait
    }

    // MARK: - Hilfen

    private var dark: Bool { env["SAGA_SHOT_MODE"] == "dark" }

    private func value(_ k: String, _ def: String) -> String { env[k].flatMap { $0.isEmpty ? nil : $0 } ?? def }

    /// Einmal koppeln, danach bleibt die App angemeldet (Geraete-Schluessel)
    private func signInIfNeeded() throws {
        let login = app.buttons["Mit QR-Code anmelden"]
        guard login.waitForExistence(timeout: 4) else { return }
        guard let link = env["SAGA_PAIR_LINK"], let url = URL(string: link) else { throw XCTSkip("nicht angemeldet und SAGA_PAIR_LINK fehlt") }
        app.open(url)
        confirmSystemDialog()
        let gone = NSPredicate(format: "exists == false")
        expectation(for: gone, evaluatedWith: login)
        waitForExpectations(timeout: 20)
    }

    /// Ansicht "Board" der Datenbank waehlen
    private func board() {
        let b = app.buttons.matching(NSPredicate(format: "label CONTAINS 'Board'")).firstMatch
        if b.waitForExistence(timeout: 5) { b.tap(); settle() }
    }

    private func open(_ s: String) {
        app.open(URL(string: s)!)
        confirmSystemDialog()
        settle()
    }

    /// Rueckfrage "In Saga oeffnen?" bestaetigen, falls das System sie zeigt
    private func confirmSystemDialog() {
        let sb = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        for t in ["Öffnen", "Open"] where sb.buttons[t].waitForExistence(timeout: 1.5) { sb.buttons[t].tap(); return }
    }

    /// Warten, bis nichts mehr laedt
    private func settle() {
        sleep(2)
        let deadline = Date().addingTimeInterval(15)
        while Date() < deadline && app.activityIndicators.count > 0 { sleep(1) }
        sleep(2)
    }

    private func shot(_ name: String) {
        let png = XCUIScreen.main.screenshot().pngRepresentation
        try? png.write(to: dir.appendingPathComponent(name + ".png"))
    }
}
