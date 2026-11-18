import Foundation

// 2.5 (Bugs-DB 2154): voller Anruf - Text des Servers bzw. Wortlaut wie im Web (CallRoom.fullText/serverText),
// "Erneut klingeln" bei vollem Anruf gesperrt (Invites.action/ringAll mit full).
func callFullTests() {
    typealias C = CallRoom
    check(C.serverText(["error": "Server sagt voll", "full_msg": "x"]) == "Server sagt voll", "voll: error zuerst")
    check(C.serverText(["error": " ", "full_msg": "Platz weg"]) == "Platz weg" && C.serverText(["msg": "m"]) == "m", "voll: full_msg/msg")
    check(C.serverText(["join_block": "full"]) == nil && C.serverText(nil) == nil, "voll: ohne Text")
    check(C.fullText(server: "Vom Server", limit: 6) == "Vom Server", "voll: Text des Servers")
    check(C.fullText(server: nil, limit: 6) == "Der Anruf ist voll – alle 6 Plätze sind belegt (jedes Gerät zählt, auch ein zweites zum Teilen). Erst wenn jemand auflegt, kann noch jemand dazukommen.",
          "voll: Wortlaut wie im Web mit Zahl")
    check(C.fullText(server: "  ", limit: 0) == "Der Anruf ist voll – jedes Gerät zählt, auch ein zweites zum Teilen.", "voll: ohne Zahl kurz")
    check(C.fullText(server: nil, limit: 4, mine: true).hasPrefix("Alle 4 Plätze sind belegt") && C.fullText(server: nil, limit: 4, mine: true).contains("Hierher übernehmen"),
          "voll: eigenes Geraet dabei - Uebernehmen geht")
    check(C.fullText(server: "S", limit: 4, mine: true) == "S", "voll: Servertext auch in der Leiste")

    typealias I = Invites
    check(I.action(.missed, host: false, full: true) == .full && I.action(.declined, host: false, full: true) == .full
          && I.action(.none, host: false, full: true) == .full && I.action(.left, host: false, full: true) == .full, "voll: Klingeln gesperrt")
    check(I.action(.ringing, host: false, full: true) == .hidden && I.action(.old, host: false, full: true) == .old
          && I.action(.removed, host: false, full: true) == .hidden && I.action(.removed, host: true, full: true) == .full, "voll: andere Zustaende")
    check(I.action(.missed, host: false, full: false) == .again && I.action(.none, host: false) == .ring, "nicht voll: wie bisher")
    let rows = ["a", "b", "c"].map { I.Row(entry: I.Entry(who: $0, name: $0.uppercased(), state: .missed), state: .missed, pending: false) }
    check(I.ringAll(rows, full: false) == ["a", "b", "c"] && I.ringAll(rows, full: true).isEmpty, "voll: Alle erneut klingeln ausgeblendet")
}
