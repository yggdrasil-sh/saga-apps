import XCTest

// Bugs-DB 2187: Mitteilung antippen oeffnet Saga am Ziel (Seite bzw. Chat) - echter Weg ueber SpringBoard im Simulator.
// Ablauf je Fall (Tests/NotifTap/run.sh steuert):
//   1. App starten (Startparameter aus TEST_RUNNER_NOTIF_ARGS, z. B. -hkPairLink ... -hkAskNotif YES), Erlaubnis bestaetigen
//   2. Zustand herstellen: "fg" App vorne, "bg" Home-Taste, "cold" App beenden
//   3. "NOTIFTAP_BEREIT <fall>" ausgeben -> run.sh schickt `xcrun simctl push` mit echtem Server-Format
//   4. Banner (Text aus TEST_RUNNER_NOTIF_MARK) auf SpringBoard antippen
//   5. "NOTIFTAP_GETIPPT" ausgeben, pruefen, ob das Ziel (TEST_RUNNER_NOTIF_EXPECT, Text in der App) erscheint
// Ohne TEST_RUNNER_NOTIF_CASE wird der Test uebersprungen (kein Server, kein Push).
final class NotifTapUITests: XCTestCase {
    private let env = ProcessInfo.processInfo.environment
    private let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")

    func testTapOpensTarget() throws {
        guard let mode = env["NOTIF_CASE"], let mark = env["NOTIF_MARK"], let expect = env["NOTIF_EXPECT"] else {
            throw XCTSkip("nur ueber Tests/NotifTap/run.sh")
        }
        let app = XCUIApplication()
        app.launchArguments = (env["NOTIF_ARGS"] ?? "").split(separator: " ").map(String.init)
        app.launch()
        allowNotifications()
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: 20))
        sleep(UInt32(env["NOTIF_SETTLE"].flatMap(UInt32.init) ?? 8))       // Anmeldung, Startablauf
        // Gegenprobe: das Ziel ist VOR der Mitteilung nicht zu sehen (sonst bewiese das Ergebnis nichts)
        let before = target(app, expect).exists
        print("NOTIFTAP_VORHER \(before ? "ZIEL_SICHTBAR" : "ok")")
        XCTAssertFalse(before, "Ziel \(expect) schon vor der Mitteilung sichtbar")
        switch mode {
        case "bg": XCUIDevice.shared.press(.home); sleep(2)
        case "cold": app.terminate(); sleep(2)
        default: break
        }
        print("NOTIFTAP_BEREIT \(mode)")
        let banner = springboard.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS[c] %@", mark)).firstMatch
        let seen = banner.waitForExistence(timeout: 60)
        XCTAssertTrue(seen, "Banner mit \(mark) erscheint")
        if seen {                                                          // kurz stehen lassen, run.sh macht das Bannerfoto
            print("NOTIFTAP_BANNER \(mode)")
            if let hold = env["NOTIF_HOLD"].flatMap(UInt32.init), hold > 0 { sleep(hold) }
        }
        banner.tap()
        print("NOTIFTAP_GETIPPT \(mode)")
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: 20), "Saga kommt nach vorne")
        let found = target(app, expect).waitForExistence(timeout: 15)
        print("NOTIFTAP_ERGEBNIS \(mode) \(found ? "ZIEL" : "KEIN_ZIEL")")
        sleep(6)                                                            // run.sh macht hier das Bildschirmfoto
        XCTAssertTrue(found, "Ziel \(expect) nach dem Antippen sichtbar")
    }

    /// 2270 (K-C): Auftrags-Mitteilung mit Knoepfen auf SpringBoard. run.sh-Ablauf wie oben, Fall in TEST_RUNNER_JOB_CASE:
    /// "unlocked" (Home, Banner lang druecken, Knopf TEST_RUNNER_JOB_BUTTON antippen -> act im Hintergrund) bzw. "locked"
    /// (Sperrbildschirm, Mitteilung lang druecken - nur Bild). Ausgaben JOBTAP_BEREIT/_BANNER/_OFFEN/_GEDRUECKT fuer Bilder.
    func testJobButtons() throws {
        guard let mode = env["JOB_CASE"], let mark = env["JOB_MARK"] else { throw XCTSkip("nur ueber das Gegenlauf-Skript") }
        let app = XCUIApplication()
        app.launchArguments = (env["NOTIF_ARGS"] ?? "").split(separator: " ").map(String.init)
        app.launch()
        // Rueckfrage "Mitteilungen erlauben" kommt erst nach der Kopplung (-hkAskNotif) - bis 60 s warten
        let end = Date().addingTimeInterval(60)
        loop: while Date() < end {
            for l in ["Allow", "Erlauben"] where springboard.buttons[l].waitForExistence(timeout: 2) { springboard.buttons[l].tap(); break loop }
        }
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: 20))
        sleep(UInt32(env["NOTIF_SETTLE"].flatMap(UInt32.init) ?? 10))
        XCUIDevice.shared.press(.home); sleep(2)
        if mode == "locked" { XCUIDevice.shared.perform(NSSelectorFromString("pressLockButton")); sleep(2) }
        print("JOBTAP_BEREIT \(mode)")
        let banner = springboard.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS[c] %@", mark)).firstMatch
        XCTAssertTrue(banner.waitForExistence(timeout: 60), "Mitteilung mit \(mark) erscheint")
        print("JOBTAP_BANNER \(mode)"); sleep(3)
        banner.press(forDuration: 1.6)
        sleep(2)
        print("JOBTAP_OFFEN \(mode)"); sleep(4)
        guard mode == "unlocked", let label = env["JOB_BUTTON"] else { return }
        let b = springboard.buttons[label]
        XCTAssertTrue(b.waitForExistence(timeout: 10), "Knopf \(label) sichtbar")
        b.tap()
        print("JOBTAP_GEDRUECKT \(label)")
        sleep(12)                                                           // act im Hintergrund, Skript fragt den Server
    }

    private func target(_ app: XCUIApplication, _ text: String) -> XCUIElement {
        app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS[c] %@ OR value CONTAINS[c] %@ OR placeholderValue CONTAINS[c] %@",
                                                             text, text, text)).firstMatch
    }

    /// System-Rueckfrage "Mitteilungen erlauben" (nur beim ersten Start)
    private func allowNotifications() {
        for label in ["Allow", "Erlauben"] {
            let b = springboard.buttons[label]
            if b.waitForExistence(timeout: 4) { b.tap(); return }
        }
    }
}
