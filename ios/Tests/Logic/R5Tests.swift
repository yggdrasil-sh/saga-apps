import Foundation

// 2.5 (vor R5): Geraetegrenze beim Koppeln (device_limit) und Sprache des Push-Abos (push2)
func r5Tests() {
    // 409 an der Grenze
    let e: [String: Any] = ["error": "Du hast schon 10 Chat-Geräte.", "code": "device_limit", "max": 10, "count": 10]
    check(ChatDeviceLimit.parse(code: 409, json: e) == .init(max: 10, count: 10, error: "Du hast schon 10 Chat-Geräte."), "409 device_limit erkannt")
    check(ChatDeviceLimit.parse(code: 400, json: e) == nil, "nur 409")
    check(ChatDeviceLimit.parse(code: 409, json: ["error": "anderes"]) == nil, "409 ohne code: kein Grenzfall")
    check(ChatDeviceLimit.parse(code: 409, json: nil) == nil, "409 ohne JSON")
    check(ChatDeviceLimit.parse(["code": "device_limit", "max": 3])?.count == 3, "count fehlt -> max")
    // Zustand der Anfrage (neues Geraet)
    check(ChatDeviceLimit.poll(["state": "approved", "sealed": "x"]) == .approved, "approved")
    check(ChatDeviceLimit.poll(["state": "pending"]) == .pending, "pending")
    var f = e; f["state"] = "failed"
    check(ChatDeviceLimit.poll(f) == .limit(.init(max: 10, count: 10, error: "Du hast schon 10 Chat-Geräte.")), "failed + device_limit")
    check(ChatDeviceLimit.poll(["state": "failed", "error": "kaputt"]) == .failed("kaputt"), "failed ohne Grenze: Grund")
    check(ChatDeviceLimit.poll(["state": "failed"]) == .failed(nil), "failed ohne alles")
    for s in ["expired", "gone", "declined", "neu-unbekannt"] { check(ChatDeviceLimit.poll(["state": s]) == .ended, "Ende: \(s)") }
    check(ChatDeviceLimit.poll([:]) == .ended, "ohne state: Ende")
    // Hinweis vorab (nur mit devices_count)
    check(ChatDeviceLimit.reached(count: 10, max: 10) == .init(max: 10, count: 10, error: nil), "erreicht")
    check(ChatDeviceLimit.reached(count: 11, max: 10) != nil, "ueber der Grenze")
    check(ChatDeviceLimit.reached(count: 9, max: 10) == nil, "darunter")
    check(ChatDeviceLimit.reached(count: nil, max: 10) == nil, "aelterer Server: keine Zahl, kein Hinweis")
    check(ChatDeviceLimit.reached(count: 5, max: nil) == nil, "ohne max")
    check(ChatDeviceLimit.reached(count: 0, max: 0) == nil, "max 0 = keine Grenze")

    // Push: payload des Servers
    let p2 = PushState(data: Data(#"{"server":{"enabled":true,"apns":true,"payload":2,"sub_caps":["update"]}}"#.utf8))
    check(p2?.payload == 2, "server.payload 2")
    check(PushState(data: Data(#"{"server":{"enabled":true}}"#.utf8))?.payload == 0, "aelterer Server: payload 0")
    // Abo: lang nur ab payload 2, nur unterstuetzte Sprachen, nie caps
    let old = PushLogic.subscription(token: "ab", env: "production", name: "iPhone", lang: "de", payload: 0)
    check(old["lang"] == nil && old["caps"] == nil && old.count == 4, "aelterer Server: Abo unveraendert")
    let new = PushLogic.subscription(token: "ab", env: "production", name: "iPhone", lang: "th", payload: 2)
    check(new["lang"] as? String == "th" && new["caps"] == nil, "payload 2: lang, keine caps")
    check(PushLogic.subscription(token: "ab", env: "sandbox", name: "x", lang: "fr", payload: 2)["lang"] == nil, "nicht unterstuetzte Sprache weglassen")
    check(PushLogic.pushLang("es-419", payload: 3) == "es", "Region abschneiden, payload > 2")
    check(PushLogic.pushLang(nil, payload: 2) == nil, "ohne Sprache")
    check(PushLogic.subscription(token: "ab", env: "production", name: "x", type: "apns-voip", lang: "ru", payload: 2)["lang"] as? String == "ru", "VoIP-Abo auch mit lang")
    // Sprachwechsel
    check(PushLogic.langChanged(sent: "de", now: "en", payload: 2), "gewechselt")
    check(!PushLogic.langChanged(sent: "en", now: "en", payload: 2), "gleich")
    check(PushLogic.langChanged(sent: nil, now: "de", payload: 2), "noch nie gesendet")
    check(!PushLogic.langChanged(sent: "de", now: "en", payload: 0), "aelterer Server: nie erneuern")
}
