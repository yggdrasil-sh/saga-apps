import Foundation
import CryptoKit

// 2262 (2.6, pushrelay1): Ende-zu-Ende-Mitteilungen - Entschluesseln (RFC 8291 + Auffuellen), Umschlag, Anzeige,
// Schluesselwechsel, Abo-Felder und Schalter-Zustand. Vektoren: Tests/vectors/push_e2e.json (Kopie von
// tests/vectors/push_e2e.json aus dem Server-Repo, erzeugt mit tools/push_e2e_vectors.py).

/// Gegenstueck des Servers (push.encrypt) fuer Rundlauf-Tests: aes128gcm, ein Datensatz, Auffuellen auf padTo
private func e2eEncrypt(_ plain: Data, to key: PushE2EKey, padTo: Int? = nil) -> Data {
    let asKey = P256.KeyAgreement.PrivateKey()
    let asPub = asKey.publicKey.x963Representation
    let shared = try! asKey.sharedSecretFromKeyAgreement(with: key.priv.publicKey)
    let ikm = shared.hkdfDerivedSymmetricKey(using: SHA256.self, salt: key.auth,
                                             sharedInfo: Data("WebPush: info".utf8) + Data([0]) + key.p256dh + asPub, outputByteCount: 32)
    let salt = SymmetricKey(size: .bits128).withUnsafeBytes { Data($0) }
    let cek = HKDF<SHA256>.deriveKey(inputKeyMaterial: ikm, salt: salt, info: Data("Content-Encoding: aes128gcm".utf8) + Data([0]), outputByteCount: 16)
    let nonce = HKDF<SHA256>.deriveKey(inputKeyMaterial: ikm, salt: salt, info: Data("Content-Encoding: nonce".utf8) + Data([0]), outputByteCount: 12)
    var p = plain + Data([2])
    if let padTo, p.count < padTo { p += Data(count: padTo - p.count) }
    let box = try! AES.GCM.seal(p, using: cek, nonce: try! AES.GCM.Nonce(data: nonce.withUnsafeBytes { Data($0) }))
    return salt + Data([0, 0, 0x10, 0]) + Data([65]) + asPub + box.ciphertext + box.tag
}

private func e2eVectors() -> [String: Any]? {
    let u = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("vectors/push_e2e.json")
    guard let d = try? Data(contentsOf: u) else { return nil }
    return try? JSONSerialization.jsonObject(with: d) as? [String: Any]
}

func pushE2ETests() {
    let b64 = PushE2ELogic.b64uDecode
    // RFC 8291 Anhang A (gleicher Vektor wie tests/push.py vector_test)
    if let d = b64("q1dXpw3UpT5VOmu_cf_v6ih07Aems3njxI-JWgLcM94"), let a = b64("BTBZMqHH6r4Tts7J_aSIgg"),
       let k = try? P256.KeyAgreement.PrivateKey(rawRepresentation: d),
       let body = b64("DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPTpK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN") {
        let key = PushE2EKey(priv: k, auth: a, created: 0, origin: "x")
        check(PushE2ELogic.b64u(key.p256dh) == "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4", "RFC 8291: oeffentlicher Schluessel")
        let p = PushE2ELogic.decrypt(body, key: key)
        check(p.flatMap { String(data: $0, encoding: .utf8) } == "When I grow up, I want to be a watermelon", "RFC 8291 Anhang A entschluesselt")
        var bad = body; bad[bad.count - 1] ^= 1
        check(PushE2ELogic.decrypt(bad, key: key) == nil, "RFC 8291: veraenderter Koerper -> nil")
        let other = PushE2EKey.generate(origin: "x", now: 0)
        check(PushE2ELogic.decrypt(body, key: other) == nil, "RFC 8291: falscher Schluessel -> nil")
        check(PushE2ELogic.decrypt(Data(body.prefix(40)), key: key) == nil, "zu kurz -> nil")
    } else { check(false, "RFC-8291-Vektor lesbar") }

    // Auffuellen (RFC 8188): Trenner 0x02, dann Nullen
    check(PushE2ELogic.unpad(Data([0x41, 0x42, 2, 0, 0, 0])) == Data([0x41, 0x42]), "Auffuellen entfernt")
    check(PushE2ELogic.unpad(Data([0x41, 2])) == Data([0x41]), "ohne Auffuellen")
    check(PushE2ELogic.unpad(Data([0x41, 1, 0])) == nil, "Trenner 0x01 (nicht letzter Datensatz) -> nil")
    check(PushE2ELogic.unpad(Data([0, 0])) == nil, "nur Nullen -> nil")

    // Rundlauf mit Auffuellen 512/1024/2048, Umschlag, Anzeige
    let key = PushE2EKey.generate(origin: "https://saga.example.org", now: 1000)
    check(key.kid.count == 8 && key.kid == PushE2ELogic.kid(p256dh: key.p256dh), "Kennung k: 8 Zeichen")
    check(PushE2EKey(stored: key.stored) == key && PushE2EKey(stored: key.stored)?.created == 1000
          && PushE2EKey(stored: key.stored)?.origin == "https://saga.example.org", "Schluessel speichern/lesen")
    check(PushE2EKey(stored: "{}") == nil && PushE2EKey(stored: "kaputt") == nil, "kaputter Eintrag -> nil")
    let inner: [String: Any] = ["title": "Anna", "body": "Hallo ✓", "url": "/chats#c1", "kind": "chat.message", "tag": "chat-1",
                                "thread": "chat-1", "chat": 1, "v": 2, "lang": "de", "n": 3, "group_key": "chat:1"]
    let plain = try! JSONSerialization.data(withJSONObject: inner)
    for pad in [512, 1024, 2048] {
        let c = e2eEncrypt(plain, to: key, padTo: pad)
        check(c.count == 16 + 4 + 1 + 65 + pad + 16, "Auffuellen \(pad): Laenge verdeckt")
        let got = PushE2ELogic.decrypt(c, key: key).flatMap(PushE2ELogic.inner)
        check(got?["body"] as? String == "Hallo ✓" && (got?["chat"] as? NSNumber)?.intValue == 1, "Rundlauf \(pad)")
    }
    let c = e2eEncrypt(plain, to: key, padTo: 512)
    let info: [AnyHashable: Any] = ["aps": ["alert": ["title-loc-key": "SAGA_PUSH_TITLE", "loc-key": "SAGA_PUSH_BODY"], "mutable-content": 1],
                                    "saga": ["e2e": 1, "k": key.kid, "c": PushE2ELogic.b64u(c)]]
    check(PushE2ELogic.isE2E(info), "Umschlag erkannt")
    check(PushE2ELogic.envelope(info) == PushE2ELogic.Envelope(k: key.kid, c: c), "Umschlag gelesen")
    check(!PushE2ELogic.isE2E(["aps": [:], "kind": "chat.message"]) && PushE2ELogic.envelope(["kind": "x"]) == nil, "Klartext alter Server: kein Umschlag")
    check(PushE2ELogic.envelope(["saga": ["e2e": 2, "k": "a", "c": "AA"]]) == nil, "unbekannte Fassung -> nil")
    check(PushE2ELogic.envelope(["saga": ["e2e": 1, "k": "", "c": "AA"]]) == nil, "ohne k -> nil")
    let sh = PushE2ELogic.shown(inner)
    check(sh.title == "Anna" && sh.body == "Hallo ✓" && sh.thread == "chat-1" && sh.sound && !sh.call, "Anzeige Chat")
    check(PushE2ELogic.shown(["tag": "t1", "silent": 1]).thread == "t1" && !PushE2ELogic.shown(["silent": true]).sound, "Faden aus tag, ohne Ton bei silent")
    check(PushE2ELogic.shown(["kind": "call.group"]).call && PushE2ELogic.shown(["kind": "call"]).call, "Anruf erkannt")
    check(PushE2ELogic.shown(["kind": "job.done"]).call == false, "Kollegin-Art ist kein Anruf")

    // Schluesselwechsel: neu nach 30 Tagen, alte erst 7 Tage nach dem Nachfolger loeschen
    let day = 86400.0
    check(PushE2ELogic.rotation([], now: 0) == (true, []), "ohne Schluessel: neu")
    check(PushE2ELogic.rotation([("a", 0)], now: 29 * day).needNew == false, "29 Tage: behalten")
    check(PushE2ELogic.rotation([("a", 0)], now: 30 * day).needNew, "30 Tage: neu")
    check(PushE2ELogic.rotation([("a", 0), ("b", 30 * day)], now: 31 * day) == (false, []), "alter Schluessel noch 7 Tage da")
    check(PushE2ELogic.rotation([("a", 0), ("b", 30 * day)], now: 37 * day) == (false, ["a"]), "nach 7 Tagen alter weg")
    check(PushE2ELogic.rotation([("a", 100 * day)], now: 0).needNew, "Uhr zurueck: neu")

    // Abo: keys/topic nur an Server mit apns_e2e
    let keys = key.subscriptionKeys
    let old = PushLogic.subscription(token: "ab", env: "production", name: "iPhone", payload: 2, e2e: 0, keys: keys, topic: "org.example.saga")
    check(old["keys"] == nil && old["topic"] == nil && old.count == 4, "alter Server: Abo genau wie bisher")
    let neu = PushLogic.subscription(token: "ab", env: "production", name: "iPhone", type: "apns-voip", payload: 2, e2e: 1, keys: keys, topic: "org.example.saga")
    check((neu["keys"] as? [String: String]) == keys && neu["topic"] as? String == "org.example.saga" && neu["type"] as? String == "apns-voip", "E2E-Abo mit keys/topic")
    check(PushLogic.subscription(token: "ab", env: "p", name: "n", e2e: 1, keys: nil, topic: "org.example.saga")["keys"] == nil, "ohne Schluessel keine Felder")
    check(PushE2ELogic.b64uDecode(keys["p256dh"]!)?.count == 65 && PushE2ELogic.b64uDecode(keys["auth"]!)?.count == 16, "p256dh 65 Byte, auth 16 Byte")

    // Schalter-Zustand
    func st(_ j: String) -> PushState? { PushState(data: Data(j.utf8)) }
    let B = "org.example.saga"
    check(PushLogic.availability(st(#"{"server":{"enabled":true,"apns":true}}"#), bundle: B, e2eReady: true) == .ok, "Server 2.5 mit Schluessel: ok")
    check(PushLogic.availability(st(#"{"server":{"enabled":true,"apns":false}}"#), bundle: B, e2eReady: true) == .appleMissing, "Server 2.5 ohne Schluessel")
    let relay = #"{"server":{"enabled":true,"apns":false,"apns_e2e":1,"apns_topics":["org.example.saga"],"apns_relay":{"state":"ok","topics":["org.example.saga"]}}}"#
    let rs = st(relay)
    check(rs?.e2e == 1 && rs?.topics == [B] && rs?.relayState == "ok" && rs?.relayTopics == [B], "apns_e2e/apns_topics/apns_relay gelesen")
    check(PushLogic.availability(rs, bundle: B, e2eReady: true) == .ok, "ueber Relais: ok")
    check(PushLogic.availability(rs, bundle: B, e2eReady: false) == .appleMissing, "App ohne Erweiterung: wie bisher (kein Schluessel)")
    check(PushLogic.availability(rs, bundle: "org.example.other", e2eReady: true) == .appleMissing, "fremde App-ID: nicht zustellbar")
    let down = relay.replacingOccurrences(of: #""state":"ok""#, with: #""state":"down""#)
    check(PushLogic.availability(st(down), bundle: B, e2eReady: true) == .relayDown, "Relais gestoert")
    check(PushLogic.usable(.relayDown) && PushLogic.usable(.ok) && !PushLogic.usable(.appleMissing), "Schalter bedienbar bei gestoertem Relais")
    let direct = #"{"server":{"enabled":true,"apns":true,"apns_bundle":"org.example.saga","apns_e2e":1,"apns_topics":["org.example.saga"],"apns_relay":{"state":"down","topics":["org.example.saga"]}}}"#
    check(PushLogic.availability(st(direct), bundle: B, e2eReady: true) == .ok, "eigener Schluessel hat Vorrang vor gestoertem Relais")
    let mdm = #"{"server":{"enabled":true,"apns":true,"apns_bundle":"org.example.mdm","apns_e2e":1,"apns_topics":["org.example.mdm","org.example.saga"],"apns_relay":{"state":"ok","topics":["org.example.saga"]}}}"#
    check(PushLogic.availability(st(mdm), bundle: B, e2eReady: true) == .ok && PushLogic.availability(st(mdm), bundle: "org.example.mdm", e2eReady: true) == .ok,
          "eigener Schluessel fuer MDM-App, Store-App ueber Relais")
    check(PushLogic.availability(st(#"{"server":{"enabled":true,"apns":false,"apns_e2e":1,"apns_topics":[],"apns_relay":null}}"#), bundle: B, e2eReady: true) == .appleMissing,
          "kein Schluessel, kein Relais")
    check(PushLogic.availability(st(#"{"server":{"enabled":false,"apns":true,"apns_e2e":1}}"#), bundle: B, e2eReady: true) == .serverOff, "Server aus")

    pushE2EVectorTests()
}

/// Server-Vektoren (tools/push_e2e_vectors.py): Format im Kopf der Datei, siehe docs/PUSH-RELAY.md
func pushE2EVectorTests() {
    guard let v = e2eVectors() else { check(false, "Tests/vectors/push_e2e.json fehlt"); return }
    _ = v
}
