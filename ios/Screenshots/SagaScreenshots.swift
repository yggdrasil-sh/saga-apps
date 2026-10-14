import XCTest

/// Screenshots fuer README/Projektseite - nur gegen einen Demo-Server mit Beispieldaten.
/// Laeuft nur, wenn SAGA_SHOT_DIR gesetzt ist (beim Aufruf als TEST_RUNNER_SAGA_SHOT_DIR).
/// Anmeldung: SAGA_PAIR_LINK (TEST_RUNNER_SAGA_PAIR_LINK) = Kopplungs-Link <app>://pair?u=...&c=...
/// Optional SAGA_SHOT_PAGE / SAGA_SHOT_DB: Seiten-IDs von "Willkommen" und der Datenbank.
/// SAGA_SHOT_LANG (de|en|es, Vorgabe de) / SAGA_SHOT_LOCALE: Sprache; bei en/es bekommen die Dateien die Endung -en/-es.
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
        app.launchArguments += ["-AppleLanguages", "(\(lang))", "-AppleLocale", value("SAGA_SHOT_LOCALE", "de_DE")]
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
        let services = app.staticTexts[ui(de: "Dienste", en: "Services", es: "Servicios")].firstMatch
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

    /// Regression: "Bild aus Immich" in der Leiste ueber der Tastatur darf die App nicht beenden (1.2.0 stuerzte ab).
    /// SAGA_TOOLBAR_PAGE = ID einer Seite mit Absatz; die App fokussiert den ersten Absatz (-hkFocusFirst).
    func testIPhoneKeyboardImmich() throws {
        guard let page = env["SAGA_TOOLBAR_PAGE"], !page.isEmpty else { throw XCTSkip("SAGA_TOOLBAR_PAGE fehlt") }
        app.terminate()
        app.launchArguments += ["-hkOpen", page, "-hkFocusFirst", "YES"]
        app.launch()
        XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 20), "Tastatur erscheint nicht")
        settle()
        let immich = app.buttons["Bild aus Immich"].firstMatch
        XCTAssertTrue(immich.waitForExistence(timeout: 5), "Knopf fehlt in der Tastaturleiste")
        shot("toolbar-vorher")
        immich.tap()
        sleep(3)
        shot("toolbar-nachher")
        XCTAssertEqual(app.state, .runningForeground, "App nach Tipp auf \"Bild aus Immich\" beendet")
        XCTAssertTrue(app.navigationBars["Immich"].waitForExistence(timeout: 10), "Immich-Auswahl nicht geoeffnet")
        app.buttons["Abbrechen"].firstMatch.tap()
        sleep(2)
        XCTAssertEqual(app.state, .runningForeground)
        // Fotos aus derselben Leiste (oeffnet die Auswahl der Seite)
        app.terminate(); app.launch()
        XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 20), "Tastatur erscheint nicht (2)")
        settle()
        let photos = app.buttons["Fotos"].firstMatch
        XCTAssertTrue(photos.waitForExistence(timeout: 5), "Fotos-Knopf fehlt in der Tastaturleiste")
        photos.tap()
        sleep(4)
        shot("toolbar-fotos")
        XCTAssertEqual(app.state, .runningForeground, "App nach Tipp auf \"Fotos\" beendet")
        // erstes Foto waehlen und uebernehmen (Auswahl laeuft ausser Haus: per Bildschirmposition) -> Bild nach dem 1. Absatz
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.17, dy: 0.43)).tap()
        sleep(1)
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.905, dy: 0.145)).tap()
        sleep(8)
        shot("toolbar-fotos-eingefuegt")
        XCTAssertEqual(app.state, .runningForeground, "App nach Uebernahme des Fotos beendet")
    }

    // MARK: - Hilfen

    private var dark: Bool { env["SAGA_SHOT_MODE"] == "dark" }
    private var lang: String { value("SAGA_SHOT_LANG", "de") }
    /// Beschriftung der App in der gewaehlten Sprache (zum Finden von Knoepfen)
    private func ui(de: String, en: String, es: String) -> String { lang == "en" ? en : lang == "es" ? es : de }

    private func value(_ k: String, _ def: String) -> String { env[k].flatMap { $0.isEmpty ? nil : $0 } ?? def }

    /// Einmal koppeln, danach bleibt die App angemeldet (Geraete-Schluessel)
    private func signInIfNeeded() throws {
        let login = app.buttons[ui(de: "Mit QR-Code anmelden", en: "Sign in with QR code", es: "Iniciar sesión con código QR")]
        guard login.waitForExistence(timeout: 4) else { return }
        guard let link = env["SAGA_PAIR_LINK"], let url = URL(string: link) else { throw XCTSkip("nicht angemeldet und SAGA_PAIR_LINK fehlt") }
        if !dark && UIDevice.current.userInterfaceIdiom == .phone { shot("ios-anmeldung") }
        app.open(url)
        confirmSystemDialog()
        // Die App fragt vor dem Koppeln nach (Zielserver wird angezeigt) - bestaetigen
        let pair = app.alerts.buttons[ui(de: "Koppeln", en: "Pair", es: "Vincular")]
        if pair.waitForExistence(timeout: 10) {
            if !dark && UIDevice.current.userInterfaceIdiom == .phone { shot("ios-kopplung") }
            pair.tap()
        }
        let gone = NSPredicate(format: "exists == false")
        expectation(for: gone, evaluatedWith: login)
        waitForExpectations(timeout: 20)
    }

    /// Ansicht "Board" der Datenbank waehlen (iPad: Reiter; iPhone: Ansichts-Menue)
    private func board() {
        let isBoard = NSPredicate(format: "label CONTAINS 'Board' OR label CONTAINS 'Tablero'")
        let menu = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", ui(de: "Ansicht:", en: "View:", es: "Vista:"))).firstMatch
        if menu.waitForExistence(timeout: 3) {
            menu.tap()
            let item = app.buttons.matching(isBoard).firstMatch
            if item.waitForExistence(timeout: 3) { item.tap(); settle() }
            return
        }
        let b = app.buttons.matching(isBoard).firstMatch
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
        for t in ["Öffnen", "Open", "Abrir"] where sb.buttons[t].waitForExistence(timeout: 1.5) { sb.buttons[t].tap(); return }
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
        try? png.write(to: dir.appendingPathComponent(name + (lang == "de" ? "" : "-" + lang) + ".png"))   // ios-start-en.png
    }
}
