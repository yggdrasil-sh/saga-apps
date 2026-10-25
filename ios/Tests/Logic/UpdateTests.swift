import Foundation

// Selbst-Update der Mac-App (Sparkle): Einschalten nur in DMG-Builds, Vorabversion am Versionslabel erkennen
func updateTests() {
    let key = Data(repeating: 7, count: 32).base64EncodedString()
    let ok: [String: Any] = ["SagaSelfUpdate": true, "SUFeedURL": "https://example.org/appcast.xml", "SUPublicEDKey": key]
    check(UpdateLogic.isEnabled(ok), "DMG-Build mit Feed und Schluessel: aktiv")
    check(UpdateLogic.isEnabled(ok.merging(["SagaSelfUpdate": "YES"]) { $1 }), "Merker als Text YES")
    check(!UpdateLogic.isEnabled([:]), "eigener Build ohne Schluessel: aus")
    check(!UpdateLogic.isEnabled(ok.merging(["SagaSelfUpdate": false]) { $1 }), "Merker aus")
    check(!UpdateLogic.isEnabled(ok.filter { $0.key != "SagaSelfUpdate" }), "ohne Merker aus")
    check(!UpdateLogic.isEnabled(ok.merging(["SUFeedURL": "http://example.org/appcast.xml"]) { $1 }), "nur https")
    check(!UpdateLogic.isEnabled(ok.merging(["SUFeedURL": "$(SAGA_SPARKLE_FEED)"]) { $1 }), "nicht ersetzte Variable")
    check(!UpdateLogic.isEnabled(ok.merging(["SUPublicEDKey": ""]) { $1 }), "leerer Schluessel")
    check(!UpdateLogic.isEnabled(ok.merging(["SUPublicEDKey": "abc"]) { $1 }), "Schluessel muss 32 Byte sein")
    check(UpdateLogic.isPrerelease("2.5.0-rc.74") && UpdateLogic.isPrerelease("2.5.0-rc.0"), "Vorabversion erkannt")
    check(!UpdateLogic.isPrerelease("2.5.0"), "stabile Version")
    check(!UpdateLogic.isPrerelease("2.5.0-rc.") && !UpdateLogic.isPrerelease("2.5-rc.3") && !UpdateLogic.isPrerelease("x2.5.0-rc.3"), "nur x.y.z-rc.N")
}
