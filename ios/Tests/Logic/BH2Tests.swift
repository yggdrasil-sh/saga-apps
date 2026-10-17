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
}
