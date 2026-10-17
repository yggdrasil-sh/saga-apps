import Foundation

// Fehlerrunde nach Bughunt 2 (Bericht apple.md)
func bh2Tests() {
    typealias L = CallLogic
    // [S2] Auflegen waehrend "Verbinde ...": nach der POST-Antwort nicht mehr klingeln lassen
    check(L.dialStillWanted(ending: false, preparing: true, sameCall: true), "Anruf normal: weiter klingeln")
    check(!L.dialStillWanted(ending: true, preparing: false, sameCall: true), "waehrend Verbinde aufgelegt: kein Klingeln")
    check(!L.dialStillWanted(ending: true, preparing: true, sameCall: true), "ending allein reicht")
    check(!L.dialStillWanted(ending: false, preparing: false, sameCall: true), "Phase schon idle (nach Ende): kein Klingeln")
    check(!L.dialStillWanted(ending: false, preparing: true, sameCall: false), "inzwischen neuer Anruf: alten nicht klingeln lassen")

    // [S4] CallKit: auf anderem Geraet angenommen -> answeredElsewhere (nicht remoteEnded = "verpasst")
    check(L.systemEnd(.elsewhere, failed: false) == .answeredElsewhere, "anderswo angenommen")
    check(L.systemEnd(L.endNote(reason: "timeout", role: "callee", connectedFor: nil), failed: false) == .remoteEnded, "verpasst bleibt remoteEnded")
    check(L.systemEnd(.ended(dur: 12), failed: false) == .remoteEnded, "normal beendet")
    check(L.systemEnd(nil, failed: true) == .failed && L.systemEnd(.elsewhere, failed: true) == .failed, "Fehler hat Vorrang")
}
