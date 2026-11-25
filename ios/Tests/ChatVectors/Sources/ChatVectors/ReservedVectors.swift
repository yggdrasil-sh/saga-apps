import Foundation

// 2.6 (Bugs-DB 2263): reservierte Signal-Typen dtmf/svc-join (tests/call_signal_reserved.json, Kopie in ios/Tests/vectors)
// echt versiegelt: Absender devices.peer (= callee aus call_vectors.json) signiert mit seinem Schluessel und versiegelt an
// devices.me (= caller); die App oeffnet und prueft wie Calls.openBody und liest den Typ wie handleRaw (CallLogic.parse).
// Typ-Erkennung im Knoten-Anruf (HornLogic.sigKind) pruefen Tests/Logic (CallV3Tests) und HornLogicTests.
func reservedVectors(_ path: String, callVectors cpath: String) -> [(String, Bool)] {
    let C = ChatCrypto.self
    var res: [(String, Bool)] = []
    func ok(_ n: String, _ c: Bool) { res.append(("reserved " + n, c)) }
    guard let raw = FileManager.default.contents(atPath: path), let v = (try? JSONSerialization.jsonObject(with: raw)) as? [String: Any],
          let cases = v["cases"] as? [[String: Any]], let craw = FileManager.default.contents(atPath: cpath),
          let cv = (try? JSONSerialization.jsonObject(with: craw)) as? [String: Any] else {
        return [("reserved Vektoren lesbar (\(path), \(cpath))", false)]
    }
    func o(_ d: [String: Any], _ k: String) -> [String: Any] { d[k] as? [String: Any] ?? [:] }
    func s(_ d: [String: Any], _ k: String) -> String { d[k] as? String ?? "" }
    let me = o(cv, "caller"), peer = o(cv, "callee"), cid = s(cv, "call_id"), devs = o(v, "devices")
    ok("Geraete = call_vectors (me = caller, peer = callee)", s(devs, "me") == s(me, "device") && s(devs, "peer") == s(peer, "device"))
    let peerSign = C.signKeypair(seed: C.unhex(s(peer, "sign_seed_hex")) ?? Data())
    let meEnc = C.boxKeypair(seed: C.unhex(s(me, "enc_seed_hex")) ?? Data())
    var ignored = 0
    for c in cases {
        let name = s(c, "name"), ignore = s(c, "expect") == "ignore"
        guard let b = c["body"] as? [String: Any], let t = b["t"] as? String,
              let d = try? JSONSerialization.data(withJSONObject: b, options: [.sortedKeys]), let body = String(data: d, encoding: .utf8) else {
            ok("\(name) body", false); continue
        }
        let sealed = C.sealSignal(callID: cid, fromUID: s(peer, "uid"), fromDevice: s(peer, "device"), signSK: peerSign.sk, toUID: s(me, "uid"),
                                  toDevice: s(me, "device"), toPK: meEnc.pk, body: body)
        let op = sealed.flatMap { C.openSignal($0, pk: meEnc.pk, sk: meEnc.sk) }
        let good = op.map { C.verifySignal(callID: cid, fromUID: s(peer, "uid"), fromDevice: s(peer, "device"), toUID: s(me, "uid"),
                                           toDevice: s(me, "device"), body: $0.body, sig: $0.sig, signPK: peerSign.pk) } ?? false
        ok("\(name) versiegelt, geoeffnet, Signatur gueltig", good && op?.body == body)
        let sg = op.flatMap { CallLogic.parse($0.body) }
        if ignore {
            ok("\(name) still ignoriert (.other(\(t)))", sg == .other(t)); ignored += 1
        } else if t == "state" {
            if case .state? = sg { ok("\(name) Kontrollfall gelesen", true) } else { ok("\(name) Kontrollfall gelesen", false) }
        }
    }
    ok("8 reservierte Faelle", ignored == 8)
    return res
}
