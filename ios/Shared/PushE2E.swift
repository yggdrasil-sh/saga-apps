import Foundation
import CryptoKit

// 2262 (Saga 2.6, Feature pushrelay1): Apple-Mitteilungen Ende-zu-Ende zwischen Saga-Server und App - Vertrag
// docs/PUSH-RELAY.md im Server-Repo. Der Server verschluesselt den bisherigen Feldsatz (title, body, url, kind, tag, ...)
// fuer den Schluessel des Abos (RFC 8291 aes128gcm, mit Auffuellen) und schickt an APNs nur
//   {"aps": {"alert": {"title-loc-key": "SAGA_PUSH_TITLE", "loc-key": "SAGA_PUSH_BODY"}, "mutable-content": 1, ...},
//    "saga": {"e2e": 1, "k": <Schluessel-Kennung>, "c": <Chiffrat base64url>}}
// Entschluesselt wird in der Notification Service Extension (Notify/), im PushKit-Weg (CallSystem) und im
// Hintergrund-Haken (IOSAppDelegate). Gleiche Datei in App und Erweiterung; reine Logik in PushE2ELogic (swiftc-Tests).

/// Schluessel eines Abos: P-256 (Empfaenger) + 16 Byte auth, je Server (Origin)
struct PushE2EKey: Equatable {
    let priv: P256.KeyAgreement.PrivateKey
    let auth: Data
    /// Erzeugt (Unix-Sekunden)
    let created: Double
    let origin: String

    static func == (a: PushE2EKey, b: PushE2EKey) -> Bool { a.priv.rawRepresentation == b.priv.rawRepresentation && a.auth == b.auth }

    /// Oeffentlicher Schluessel unkomprimiert (65 Byte, 0x04 ...) - geht als keys.p256dh ans Abo
    var p256dh: Data { priv.publicKey.x963Representation }
    var kid: String { PushE2ELogic.kid(p256dh: p256dh) }
    /// Feld "keys" des Abos
    var subscriptionKeys: [String: String] { ["p256dh": PushE2ELogic.b64u(p256dh), "auth": PushE2ELogic.b64u(auth)] }

    static func generate(origin: String, now: Double) -> PushE2EKey {
        let a = SymmetricKey(size: .bits128).withUnsafeBytes { Data($0) }          // 16 Byte aus dem System-Zufall
        return PushE2EKey(priv: P256.KeyAgreement.PrivateKey(), auth: a, created: now, origin: origin)
    }
    init(priv: P256.KeyAgreement.PrivateKey, auth: Data, created: Double, origin: String) {
        self.priv = priv; self.auth = auth; self.created = created; self.origin = origin
    }
    /// Gespeicherte Form (Schluesselbund): {"d": priv roh b64u, "a": auth b64u, "t": erzeugt, "o": origin}
    init?(stored s: String) {
        guard let o = try? JSONSerialization.jsonObject(with: Data(s.utf8)) as? [String: Any],
              let d = (o["d"] as? String).flatMap(PushE2ELogic.b64uDecode), let a = (o["a"] as? String).flatMap(PushE2ELogic.b64uDecode),
              a.count == 16, let k = try? P256.KeyAgreement.PrivateKey(rawRepresentation: d) else { return nil }
        self.init(priv: k, auth: a, created: (o["t"] as? NSNumber)?.doubleValue ?? 0, origin: o["o"] as? String ?? "")
    }
    var stored: String {
        let o: [String: Any] = ["d": PushE2ELogic.b64u(priv.rawRepresentation), "a": PushE2ELogic.b64u(auth), "t": created, "o": origin]
        return (try? JSONSerialization.data(withJSONObject: o, options: [.sortedKeys])).flatMap { String(data: $0, encoding: .utf8) } ?? ""
    }
    /// Testschluessel (DEBUG, Startparameter -hkPushE2ETestKey bzw. Info.plist SagaPushTestKey): "<priv b64u>.<auth b64u>"
    init?(test s: String, origin: String = "test") {
        let p = s.split(separator: ".").map(String.init)
        guard p.count == 2, let d = PushE2ELogic.b64uDecode(p[0]), let a = PushE2ELogic.b64uDecode(p[1]), a.count == 16,
              let k = try? P256.KeyAgreement.PrivateKey(rawRepresentation: d) else { return nil }
        self.init(priv: k, auth: a, created: 0, origin: origin)
    }
}

enum PushE2ELogic {
    /// Fassung, die die App kann (Server meldet server.apns_e2e)
    static let version = 1
    /// Schluessel erneuern nach 30 Tagen, alte noch 7 Tage behalten (Mitteilungen unterwegs, Server noch nicht erneuert)
    static let rotateAfter: Double = 30 * 86400
    static let keepOld: Double = 7 * 86400
    /// Kategorie der Anruf-Mitteilungen (= CallSystem.category, Annehmen/Ablehnen)
    static let callCategory = "SAGA_CALL"
    /// Loc-Keys des sichtbaren Teils (Shared/Localizable.xcstrings) - Platzhalter, wenn die Erweiterung nicht entschluesseln kann
    static let titleKey = "SAGA_PUSH_TITLE", bodyKey = "SAGA_PUSH_BODY", callKey = "SAGA_PUSH_CALL"

    static func b64u(_ d: Data) -> String {
        d.base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
    }
    static func b64uDecode(_ s: String) -> Data? {
        var t = s.trimmingCharacters(in: .whitespacesAndNewlines).replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        t = t.replacingOccurrences(of: "=", with: "")
        if t.count % 4 == 1 { return nil }
        t += String(repeating: "=", count: (4 - t.count % 4) % 4)
        return Data(base64Encoded: t)
    }
    /// Kennung des Schluessels ("k"): erste 8 Zeichen base64url(SHA-256(p256dh roh, 65 Byte))
    static func kid(p256dh: Data) -> String { String(b64u(Data(SHA256.hash(data: p256dh))).prefix(8)) }

    /// Umschlag einer Mitteilung: saga.e2e == 1 mit k und c - sonst nil (alte Server, Klartext)
    struct Envelope: Equatable { let k: String; let c: Data }
    static func isE2E(_ info: [AnyHashable: Any]) -> Bool { info["saga"] is [String: Any] || info["saga"] is [AnyHashable: Any] }
    static func envelope(_ info: [AnyHashable: Any]) -> Envelope? {
        let s: [String: Any]? = (info["saga"] as? [String: Any]) ?? (info["saga"] as? [AnyHashable: Any]).map { d in
            Dictionary(uniqueKeysWithValues: d.compactMap { k, v in (k as? String).map { ($0, v) } })
        }
        guard let s, (s["e2e"] as? NSNumber)?.intValue == version || (s["e2e"] as? String) == "1",
              let k = s["k"] as? String, !k.isEmpty, let cs = s["c"] as? String, let c = b64uDecode(cs) else { return nil }
        return Envelope(k: k, c: c)
    }

    /// RFC 8291 (aes128gcm, RFC 8188) entschluesseln, Auffuellen entfernen. nil bei falschem Schluessel/kaputtem Koerper.
    static func decrypt(_ body: Data, key: PushE2EKey) -> Data? {
        let b = [UInt8](body)
        guard b.count >= 21 + 65 + 17 else { return nil }
        let salt = Data(b[0..<16])
        let rs = (UInt32(b[16]) << 24) | (UInt32(b[17]) << 16) | (UInt32(b[18]) << 8) | UInt32(b[19])
        let idlen = Int(b[20])
        guard idlen == 65, rs >= 18, b.count > 21 + idlen + 16 else { return nil }
        let asPub = Data(b[21..<(21 + idlen)])
        let ct = Data(b[(21 + idlen)...])
        guard ct.count <= Int(rs), let sender = try? P256.KeyAgreement.PublicKey(x963Representation: asPub),
              let shared = try? key.priv.sharedSecretFromKeyAgreement(with: sender) else { return nil }
        let uaPub = key.p256dh
        let ikm = shared.hkdfDerivedSymmetricKey(using: SHA256.self, salt: key.auth,
                                                 sharedInfo: Data("WebPush: info".utf8) + Data([0]) + uaPub + asPub, outputByteCount: 32)
        let cek = HKDF<SHA256>.deriveKey(inputKeyMaterial: ikm, salt: salt, info: Data("Content-Encoding: aes128gcm".utf8) + Data([0]), outputByteCount: 16)
        let nonce = HKDF<SHA256>.deriveKey(inputKeyMaterial: ikm, salt: salt, info: Data("Content-Encoding: nonce".utf8) + Data([0]), outputByteCount: 12)
        let nb = nonce.withUnsafeBytes { Data($0) }
        guard let n = try? AES.GCM.Nonce(data: nb),
              let box = try? AES.GCM.SealedBox(nonce: n, ciphertext: ct.dropLast(16), tag: ct.suffix(16)),
              let plain = try? AES.GCM.open(box, using: cek) else { return nil }
        return unpad(plain)
    }
    /// RFC 8188: Inhalt, Trenner 0x02 (letzter Datensatz), dann beliebig viele 0x00
    static func unpad(_ p: Data) -> Data? {
        guard let i = p.lastIndex(where: { $0 != 0 }), p[i] == 2 else { return nil }
        return p[p.startIndex..<i]
    }
    /// Innerer Feldsatz (JSON-Objekt, flach)
    static func inner(_ plain: Data) -> [String: Any]? {
        try? JSONSerialization.jsonObject(with: plain) as? [String: Any]
    }

    /// Was die Erweiterung anzeigt (aus dem inneren Feldsatz)
    struct Shown {
        var title: String
        var body: String
        var thread: String?
        var sound: Bool
        var call: Bool
        /// userInfo der Mitteilung: die inneren Felder flach (wie bisher der Klartext - PushCenterDelegate liest kind/url)
        var info: [String: Any]
    }
    static func flag(_ v: Any?) -> Bool {
        if let b = v as? Bool { return b }
        if let n = v as? NSNumber { return n.intValue != 0 }
        if let s = v as? String { return s == "1" || s == "true" }
        return false
    }
    static func shown(_ inner: [String: Any]) -> Shown {
        let kind = inner["kind"] as? String ?? ""
        let thread = (inner["thread"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? (inner["tag"] as? String).flatMap { $0.isEmpty ? nil : $0 }
        return Shown(title: inner["title"] as? String ?? "", body: inner["body"] as? String ?? "", thread: thread,
                     sound: !flag(inner["silent"]), call: kind == "call" || kind == "call.group", info: inner)
    }

    /// Schluesselwechsel je Server: neuer Schluessel noetig? Welche alten loeschen (Nachfolger aelter als keepOld)?
    /// keys: (kid, erzeugt) eines Servers
    static func rotation(_ keys: [(kid: String, created: Double)], now: Double) -> (needNew: Bool, delete: [String]) {
        let s = keys.sorted { $0.created > $1.created }
        guard let newest = s.first else { return (true, []) }
        let needNew = now - newest.created >= rotateAfter || newest.created > now + 86400     // Uhr sprang zurueck: neu
        let delete = s.dropFirst().filter { _ in now - newest.created >= keepOld }.map(\.kid)
        return (needNew, delete)
    }
}

/// Schluessel im Schluesselbund (Gruppe SAGA_KEYCHAIN_GROUP, fuer App und Erweiterung lesbar, nach dem ersten Entsperren):
/// Dienst saga.push.e2e, Konto "<origin>|<kid>". Die Erweiterung sucht ueber die Kennung k (alle Konten des Dienstes).
enum PushE2E {
    static let service = "saga.push.e2e"
    /// Seit 2262 erzeugte Schluessel (nur App)
    static func account(_ k: PushE2EKey) -> String { k.origin + "|" + k.kid }

    #if DEBUG
    /// Testschluessel (nur DEBUG): Startparameter -hkPushE2ETestKey "<priv>.<auth>" (App) oder Info.plist SagaPushTestKey
    /// (Erweiterung, Build-Einstellung SAGA_PUSH_TEST_KEY) - unsignierte Simulator-Builds teilen keinen Schluesselbund
    static var testKey: PushE2EKey? {
        let v = UserDefaults.standard.string(forKey: "hkPushE2ETestKey")
            ?? (Bundle.main.object(forInfoDictionaryKey: "SagaPushTestKey") as? String).flatMap { $0.hasPrefix("$(") || $0.isEmpty ? nil : $0 }
        return v.flatMap { PushE2EKey(test: $0) }
    }
    #endif

    /// Alle gespeicherten Schluessel (optional nur eines Servers)
    static func all(origin: String? = nil) -> [PushE2EKey] {
        Keychain.accounts(service: service).filter { a in origin.map { a.hasPrefix($0 + "|") } ?? true }
            .compactMap { Keychain.read(service: service, account: $0).flatMap(PushE2EKey.init(stored:)) }
    }
    /// Schluessel zur Kennung k
    static func keys(kid: String) -> [PushE2EKey] {
        var out: [PushE2EKey] = []
        #if DEBUG
        if let t = testKey, t.kid == kid { out.append(t) }
        #endif
        out += Keychain.accounts(service: service).filter { $0.hasSuffix("|" + kid) }
            .compactMap { Keychain.read(service: service, account: $0).flatMap(PushE2EKey.init(stored:)) }
        return out
    }

    /// Mitteilung oeffnen: E2E-Umschlag -> innere Felder (flach). nil = kein Umschlag ODER nicht lesbar
    /// (Aufrufer: `PushE2E.open(info) ?? info` - Klartext alter Server bleibt unveraendert).
    static func open(_ info: [AnyHashable: Any]) -> [String: Any]? {
        guard let e = PushE2ELogic.envelope(info) else { return nil }
        for k in keys(kid: e.k) {
            if let p = PushE2ELogic.decrypt(e.c, key: k), let i = PushE2ELogic.inner(p) { return i }
        }
        return nil
    }
    /// Wie open, aber als userInfo (AnyHashable) fuer die bisherigen Parser
    static func openInfo(_ info: [AnyHashable: Any]) -> [AnyHashable: Any] {
        guard let i = open(info) else { return info }
        var out = info
        out.removeValue(forKey: "saga")
        for (k, v) in i { out[k] = v }
        return out
    }

    /// Aktueller Schluessel fuer einen Server (App, beim Abo): anlegen bzw. nach 30 Tagen erneuern, alte nach 7 Tagen loeschen.
    /// nil, wenn der Schluesselbund nicht schreibt (dann kein E2E-Abo - Server schickt wie bisher bzw. gar nicht ueber das Relais).
    static func current(origin: String, group: String?, now: Double = Date().timeIntervalSince1970) -> PushE2EKey? {
        #if DEBUG
        if let t = UserDefaults.standard.string(forKey: "hkPushE2ETestKey").flatMap({ PushE2EKey(test: $0, origin: origin) }) { return t }
        #endif
        let have = all(origin: origin)
        let plan = PushE2ELogic.rotation(have.map { ($0.kid, $0.created) }, now: now)
        for k in have where plan.delete.contains(k.kid) { Keychain.delete(service: service, account: account(k)) }
        if !plan.needNew, let k = have.max(by: { $0.created < $1.created }) { return k }
        let k = PushE2EKey.generate(origin: origin, now: now)
        // Erweiterung muss lesen koennen: mit Gruppe nur, wenn der Eintrag wirklich in der Gruppe liegt
        let st = Keychain.write(k.stored, service: service, account: account(k), group: group)
        guard st == .shared || (st == .local && (group ?? "").isEmpty) else {
            if st == .local { Keychain.delete(service: service, account: account(k)) }
            return have.max(by: { $0.created < $1.created })
        }
        return k
    }
    /// Abmelden vom Server: Schluessel dieses Servers loeschen
    static func forget(origin: String) {
        for k in all(origin: origin) { Keychain.delete(service: service, account: account(k)) }
    }
}
