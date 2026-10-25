import Foundation

// 2.5 A07 "Alle Anrufe ueber Knoten" (horn_dm): 1:1 zuerst ueber einen Knoten, sonst direkt (Vektoren wie Android Bughunt25AndroidTest)
func hornDMTests() {
    let caps = CallV3.myCaps(serverFeatures: ["reneg", "multi", "horn"])
    check(CallRoom.dmViaHorn(hornDm: true, myCaps: caps), "horn_dm + horn + multi: ueber Knoten")
    check(!CallRoom.dmViaHorn(hornDm: false, myCaps: caps), "Einstellung aus: direkt")
    check(!CallRoom.dmViaHorn(hornDm: true, myCaps: CallV3.myCaps(serverFeatures: ["reneg", "multi"])), "Server ohne horn: direkt")
    check(!CallRoom.dmViaHorn(hornDm: true, myCaps: CallV3.myCaps(serverFeatures: ["reneg", "horn"])), "ohne multi kein horn")
    check(!CallRoom.dmViaHorn(hornDm: true, myCaps: ["horn"]), "nur horn ohne multi: direkt")

    let b = CallRoom.dmHornBody(id: "ab12", chatID: 7, media: "video", device: "d1", caps: caps, devClass: "mobile")
    check(b["via"] as? String == "horn" && b["signals"] == nil, "Knoten-Anruf: via horn, ohne signals")
    check(b["id"] as? String == "ab12" && b["chat_id"] as? Int == 7 && b["media"] as? String == "video"
          && b["device"] as? String == "d1" && b["dev_class"] as? String == "mobile" && (b["caps"] as? [String]) == caps, "Felder des Aufrufs")

    func o(_ s: String) -> [String: Any]? { (try? JSONSerialization.jsonObject(with: Data(s.utf8))) as? [String: Any] }
    check(CallRoom.dmHornOutcome(code: 409, body: o(#"{"fallback":"direct","horn_block":"node","error":"x"}"#)) == .direct, "409 fallback direct: direkt mit derselben id")
    check(CallRoom.dmHornOutcome(code: 409, body: o(#"{"ok":false,"busy":true,"error":"Besetzt"}"#)) == .busy, "409 busy: besetzt")
    check(CallRoom.dmHornOutcome(code: 409, body: o(#"{"incoming":"cafe","error":"Er ruft dich an"}"#)) == .failed("Er ruft dich an"), "409 incoming: Meldung, kein Rueckfall")
    check(CallRoom.dmHornOutcome(code: 400, body: o(#"{"fallback":"direct"}"#)) == .failed(nil), "nur 409 faellt zurueck")
    check(CallRoom.dmHornOutcome(code: 409, body: nil) == .failed(nil), "409 ohne Inhalt: Fehler")
    check(CallRoom.dmHornOutcome(code: 429, body: o(#"{"error":"  Zu viele  "}"#)) == .failed("Zu viele"), "429: Meldung des Servers")
    check(CallRoom.dmHornOutcome(code: nil, body: nil) == .direct, "Netzfehler: direkt")
    check(CallRoom.dmHornOutcome(code: 200, body: o(#"{"ok":true,"call":{"id":"ab12","kind":"group","dm":true,"topo":"horn","horn":{"candidates":[]}}}"#)) == .room, "200 mit call: Raum")
    check(CallRoom.dmHornOutcome(code: 200, body: o(#"{"ok":true}"#)) == .failed(nil), "200 ohne call: Fehler")
}
