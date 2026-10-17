import Foundation

// Logik-Tests Saga 2.0 (Live, Benachrichtigungen, Foederation, Push, KI, Chat) ohne Xcode: ./run.sh
var failed = 0, passed = 0
func check(_ c: Bool, _ msg: String, line: Int = #line) {
    if c { passed += 1 } else { failed += 1; print("FEHLER Z.\(line): \(msg)") }
}
v2Tests()
fixTests()
bughuntTests()
mediaTests()
v21bTests()
v21bReauthTests()
v21bPushTests()
v21cTests()
callAudioTests()
print("Logik-Tests 2.0: \(passed) ok, \(failed) fehlgeschlagen")
exit(failed == 0 ? 0 : 1)
