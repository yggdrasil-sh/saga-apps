import Foundation
import Clibsodium

// Chat mit Ende-zu-Ende-Verschluesselung (Saga 2.0): Krypto-Format v1 - genau wie static/chat-crypto.js (libsodium.js)
// und tests/chatlib.py (PyNaCl). Nur libsodium-Primitive (swift-sodium/Clibsodium), nichts selbst gebaut:
// crypto_sign (Ed25519), crypto_box_seal (X25519), crypto_pwhash (Argon2id13), crypto_aead_xchacha20poly1305_ietf,
// crypto_generichash (BLAKE2b). Reine Logik ohne UI - geprueft gegen tests/chat_vectors.json (ios/Tests/ChatVectors).

enum ChatCrypto {
    static let V = "saga-chat/v1/"
    static let defaultOps = 2
    static let defaultMem = 67_108_864
    static let alphabet = Array("ABCDEFGHIJKLMNOPQRSTUVWXYZ234567")

    private static let initialized: Bool = sodium_init() >= 0
    /// libsodium einmal initialisieren (mehrfach aufrufbar)
    @discardableResult static func ready() -> Bool { initialized }

    // MARK: Hilfen

    /// LP(f1..fn) = je Feld uint32_be(len) || bytes (Strings als UTF-8)
    static func lp(_ fields: [Any]) -> Data {
        var out = Data()
        for f in fields {
            let b: Data = (f as? Data) ?? Data(String(describing: f).utf8)
            var n = UInt32(b.count).bigEndian
            out.append(Data(bytes: &n, count: 4)); out.append(b)
        }
        return out
    }
    static func lp(_ fields: Any...) -> Data { lp(fields) }

    static func b64(_ d: Data) -> String { d.base64EncodedString() }
    /// Standard-Base64 mit Padding; `n`: erwartete Laenge
    static func unb64(_ s: String?, _ n: Int? = nil) -> Data? {
        guard let s, let d = Data(base64Encoded: s) else { return nil }
        if let n, d.count != n { return nil }
        return d
    }
    static func hex(_ d: Data) -> String { d.map { String(format: "%02x", $0) }.joined() }
    static func unhex(_ s: String) -> Data? {
        guard s.count % 2 == 0 else { return nil }
        var out = Data(capacity: s.count / 2), i = s.startIndex
        while i < s.endIndex {
            let j = s.index(i, offsetBy: 2)
            guard let b = UInt8(s[i..<j], radix: 16) else { return nil }
            out.append(b); i = j
        }
        return out
    }

    /// Zufall nur aus libsodium (randombytes_buf)
    static func random(_ n: Int) -> Data {
        ready()
        var b = [UInt8](repeating: 0, count: n)
        randombytes_buf(&b, n)
        return Data(b)
    }
    /// 32 Hex-Zeichen (16 Zufallsbytes): Geraete- und Nachrichten-IDs
    static func hexID() -> String { hex(random(16)) }

    /// crypto_generichash (BLAKE2b) ohne Schluessel
    static func gh(_ d: Data, _ n: Int = 32) -> Data {
        ready()
        var out = [UInt8](repeating: 0, count: n)
        let m = [UInt8](d)
        _ = crypto_generichash(&out, n, m, UInt64(m.count), nil, 0)
        return Data(out)
    }
}

// MARK: - Schluessel, Signatur, Versiegeln, AEAD, Argon2id

extension ChatCrypto {
    struct KeyPair: Equatable { var pk: Data; var sk: Data }

    /// Ed25519 (crypto_sign); `seed` (32 B) nur fuer Testvektoren
    static func signKeypair(seed: Data? = nil) -> KeyPair {
        ready()
        var pk = [UInt8](repeating: 0, count: Int(crypto_sign_publickeybytes()))
        var sk = [UInt8](repeating: 0, count: Int(crypto_sign_secretkeybytes()))
        if let seed { let s = [UInt8](seed); _ = crypto_sign_seed_keypair(&pk, &sk, s) } else { _ = crypto_sign_keypair(&pk, &sk) }
        return KeyPair(pk: Data(pk), sk: Data(sk))
    }
    /// X25519 (crypto_box)
    static func boxKeypair(seed: Data? = nil) -> KeyPair {
        ready()
        var pk = [UInt8](repeating: 0, count: Int(crypto_box_publickeybytes()))
        var sk = [UInt8](repeating: 0, count: Int(crypto_box_secretkeybytes()))
        if let seed { let s = [UInt8](seed); _ = crypto_box_seed_keypair(&pk, &sk, s) } else { _ = crypto_box_keypair(&pk, &sk) }
        return KeyPair(pk: Data(pk), sk: Data(sk))
    }
    /// oeffentlicher X25519-Schluessel zum privaten (backup_pk aus backup_sk)
    static func scalarmultBase(_ sk: Data) -> Data? {
        ready()
        guard sk.count == 32 else { return nil }
        var pk = [UInt8](repeating: 0, count: 32)
        let s = [UInt8](sk)
        return crypto_scalarmult_base(&pk, s) == 0 ? Data(pk) : nil
    }
    static func sign(_ msg: Data, sk: Data) -> Data {
        ready()
        var sig = [UInt8](repeating: 0, count: Int(crypto_sign_bytes()))
        let m = [UInt8](msg), k = [UInt8](sk)
        _ = crypto_sign_detached(&sig, nil, m, UInt64(m.count), k)
        return Data(sig)
    }
    static func verify(_ sig: Data?, _ msg: Data, pk: Data?) -> Bool {
        ready()
        guard let sig, let pk, sig.count == 64, pk.count == 32 else { return false }
        let s = [UInt8](sig), m = [UInt8](msg), p = [UInt8](pk)
        return crypto_sign_verify_detached(s, m, UInt64(m.count), p) == 0
    }
    /// crypto_box_seal (anonym, 48 Bytes mehr)
    static func seal(_ msg: Data, pk: Data) -> Data? {
        ready()
        guard pk.count == 32 else { return nil }
        let m = [UInt8](msg), p = [UInt8](pk)
        var out = [UInt8](repeating: 0, count: m.count + Int(crypto_box_sealbytes()))
        return crypto_box_seal(&out, m, UInt64(m.count), p) == 0 ? Data(out) : nil
    }
    static func sealOpen(_ c: Data, pk: Data, sk: Data) -> Data? {
        ready()
        let sb = Int(crypto_box_sealbytes())
        guard c.count >= sb, pk.count == 32, sk.count == 32 else { return nil }
        let cc = [UInt8](c), p = [UInt8](pk), s = [UInt8](sk)
        var out = [UInt8](repeating: 0, count: cc.count - sb)
        return crypto_box_seal_open(&out, cc, UInt64(cc.count), p, s) == 0 ? Data(out) : nil
    }
    static func aeadKey() -> Data { random(Int(crypto_aead_xchacha20poly1305_ietf_keybytes())) }
    static func aeadEncrypt(_ msg: Data, ad: Data, nonce: Data, key: Data) -> Data? {
        ready()
        guard nonce.count == 24, key.count == 32 else { return nil }
        let m = [UInt8](msg), a = [UInt8](ad), n = [UInt8](nonce), k = [UInt8](key)
        var out = [UInt8](repeating: 0, count: m.count + Int(crypto_aead_xchacha20poly1305_ietf_abytes()))
        var olen: UInt64 = 0
        guard crypto_aead_xchacha20poly1305_ietf_encrypt(&out, &olen, m, UInt64(m.count), a, UInt64(a.count), nil, n, k) == 0 else { return nil }
        return Data(out.prefix(Int(olen)))
    }
    static func aeadDecrypt(_ c: Data, ad: Data, nonce: Data, key: Data) -> Data? {
        ready()
        let ab = Int(crypto_aead_xchacha20poly1305_ietf_abytes())
        guard c.count >= ab, nonce.count == 24, key.count == 32 else { return nil }
        let cc = [UInt8](c), a = [UInt8](ad), n = [UInt8](nonce), k = [UInt8](key)
        var out = [UInt8](repeating: 0, count: cc.count - ab)
        var olen: UInt64 = 0
        guard crypto_aead_xchacha20poly1305_ietf_decrypt(&out, &olen, nil, cc, UInt64(cc.count), a, UInt64(a.count), n, k) == 0 else { return nil }
        return Data(out.prefix(Int(olen)))
    }
    /// crypto_pwhash Argon2id13 -> 32 Bytes
    static func pwhash(_ password: String, salt: Data, ops: Int, mem: Int) -> Data? {
        ready()
        guard salt.count == Int(crypto_pwhash_saltbytes()), ops >= 1, mem >= 8192 else { return nil }
        var out = [UInt8](repeating: 0, count: 32)
        let pw = Array(password.utf8).map { CChar(bitPattern: $0) }, s = [UInt8](salt)
        let r = crypto_pwhash(&out, 32, pw, UInt64(pw.count), s, UInt64(ops), Int(mem), crypto_pwhash_alg_argon2id13())
        return r == 0 ? Data(out) : nil
    }
}

// MARK: - Wiederherstellungsschluessel und Buendel

extension ChatCrypto {
    /// Base32 (A-Z2-7) ohne Padding
    static func base32(_ d: Data) -> String {
        var bits = 0, val = 0, out = ""
        for x in d {
            val = (val << 8) | Int(x); bits += 8
            while bits >= 5 { out.append(alphabet[(val >> (bits - 5)) & 31]); bits -= 5 }
            val &= (1 << bits) - 1
        }
        if bits > 0 { out.append(alphabet[(val << (5 - bits)) & 31]) }
        return out
    }
    /// Eingabe normalisieren: Grossbuchstaben, Leerzeichen/"-" weg, 0->O, 1->I, 8->B; nil wenn ungueltig
    static func normRecovery(_ s: String) -> String? {
        let r = String(s.uppercased().filter { !$0.isWhitespace && $0 != "-" }
            .map { $0 == "0" ? "O" : $0 == "1" ? "I" : $0 == "8" ? "B" : $0 })
        return r.count == 32 && r.allSatisfy({ alphabet.contains($0) }) ? r : nil
    }
    /// Neuer Wiederherstellungsschluessel (20 Zufallsbytes), Anzeige 8x4 mit "-"
    static func newRecoveryKey(raw: Data? = nil) -> String {
        let k = Array(base32(raw ?? random(20)))
        return stride(from: 0, to: k.count, by: 4).map { String(k[$0..<min($0 + 4, k.count)]) }.joined(separator: "-")
    }
    static func recoveryKDF(_ rk: String, salt: Data, ops: Int = defaultOps, mem: Int = defaultMem) -> Data? {
        guard let n = normRecovery(rk) else { return nil }
        return pwhash(n, salt: salt, ops: ops, mem: mem)
    }

    /// Entschluesseltes Buendel: Identitaet + Backup-Schluessel des Kontos
    struct Bundle: Equatable {
        var identSK: Data; var identPK: Data; var backupSK: Data; var backupPK: Data
    }
    /// Klartext genau wie JSON.stringify/json.dumps(separators=(",",":"))
    static func bundlePlain(identSK: Data, backupSK: Data) -> String {
        "{\"v\":1,\"ident_sk\":\"\(b64(identSK))\",\"backup_sk\":\"\(b64(backupSK))\"}"
    }
    static func parsePlain(_ s: String) -> Bundle? {
        guard let j = (try? JSONSerialization.jsonObject(with: Data(s.utf8))) as? [String: Any],
              let isk = unb64(j["ident_sk"] as? String, 64), let bsk = unb64(j["backup_sk"] as? String, 32),
              let bpk = scalarmultBase(bsk) else { return nil }
        return Bundle(identSK: isk, identPK: isk.suffix(32), backupSK: bsk, backupPK: bpk)
    }
    /// Buendel mit dem Wiederherstellungsschluessel verschluesseln -> JSON-Text fuer den Server
    static func sealBundle(uid: String, recoveryKey rk: String, plain: String, salt: Data? = nil, nonce: Data? = nil,
                           ops: Int = defaultOps, mem: Int = defaultMem) -> String? {
        let salt = salt ?? random(16), nonce = nonce ?? random(24)
        guard let key = recoveryKDF(rk, salt: salt, ops: ops, mem: mem),
              let ct = aeadEncrypt(Data(plain.utf8), ad: lp(V + "bundle", uid), nonce: nonce, key: key) else { return nil }
        return "{\"v\":1,\"kdf\":\"argon2id13\",\"ops\":\(ops),\"mem\":\(mem),\"salt\":\"\(b64(salt))\",\"nonce\":\"\(b64(nonce))\",\"ct\":\"\(b64(ct))\"}"
    }
    /// Buendel mit dem Wiederherstellungsschluessel oeffnen (ops/mem aus dem Buendel); nil = falscher Schluessel
    /// Obergrenzen fuer die Argon2-Parameter aus dem Buendel (Schutz vor absurden Werten vom Server)
    static let bundleMaxOps = 10, bundleMaxMem = 256 * 1024 * 1024

    /// Buendel im bekannten Format (v=1, argon2id13, ops 1...10, mem 8 KiB...256 MiB)? Sonst "Unbekanntes Schluesselformat".
    static func bundleFormatOK(_ bundle: String) -> Bool {
        guard let j = (try? JSONSerialization.jsonObject(with: Data(bundle.utf8))) as? [String: Any],
              (j["v"] as? NSNumber)?.intValue == 1, j["kdf"] as? String == "argon2id13",
              let ops = (j["ops"] as? NSNumber)?.intValue, let mem = (j["mem"] as? NSNumber)?.intValue else { return false }
        return (1...bundleMaxOps).contains(ops) && (8192...bundleMaxMem).contains(mem)
    }

    static func openBundle(uid: String, recoveryKey rk: String, bundle: String) -> Bundle? {
        guard bundleFormatOK(bundle), let j = (try? JSONSerialization.jsonObject(with: Data(bundle.utf8))) as? [String: Any],
              let salt = unb64(j["salt"] as? String, 16), let nonce = unb64(j["nonce"] as? String, 24), let ct = unb64(j["ct"] as? String),
              let ops = (j["ops"] as? NSNumber)?.intValue, let mem = (j["mem"] as? NSNumber)?.intValue,
              let key = recoveryKDF(rk, salt: salt, ops: ops, mem: mem),
              let pt = aeadDecrypt(ct, ad: lp(V + "bundle", uid), nonce: nonce, key: key),
              let s = String(data: pt, encoding: .utf8) else { return nil }
        return parsePlain(s)
    }
}

// MARK: - Zertifikate, Nachrichten, Anhaenge, Codes

extension ChatCrypto {
    static func backupMsg(uid: String, backupPK: Data) -> Data { lp(V + "backup", uid, backupPK) }
    static func deviceMsg(uid: String, deviceID: String, encPK: Data, signPK: Data) -> Data { lp(V + "device", uid, deviceID, encPK, signPK) }
    static func bundleUpdateMsg(uid: String, bundle: String) -> Data { lp(V + "bundle-update", uid, bundle) }

    static func msgAD(chatID: String, msgID: String, sender: String, device: String, ts: Int64, replyTo: String?) -> Data {
        lp(V + "msg", chatID, msgID, sender, device, String(ts), replyTo ?? "")
    }
    static func sigMsg(chatID: String, msgID: String, sender: String, device: String, ts: Int64, replyTo: String?, bodyCT: String) -> Data {
        lp(V + "sig", chatID, msgID, sender, device, String(ts), replyTo ?? "", gh(unb64(bodyCT) ?? Data()))
    }

    struct Sealed { var bodyCT: String; var sig: String; var key: Data }
    /// Inhalt verschluesseln + signieren (K und nonce nur fuer Testvektoren vorgeben)
    static func encryptMessage(chatID: String, msgID: String, sender: String, device: String, signSK: Data, ts: Int64,
                               replyTo: String?, plain: Data, key: Data? = nil, nonce: Data? = nil) -> Sealed? {
        let k = key ?? aeadKey(), n = nonce ?? random(24)
        guard let ct = aeadEncrypt(plain, ad: msgAD(chatID: chatID, msgID: msgID, sender: sender, device: device, ts: ts, replyTo: replyTo), nonce: n, key: k)
        else { return nil }
        let body = b64(n + ct)
        let sig = sign(sigMsg(chatID: chatID, msgID: msgID, sender: sender, device: device, ts: ts, replyTo: replyTo, bodyCT: body), sk: signSK)
        return Sealed(bodyCT: body, sig: b64(sig), key: k)
    }
    /// Nachricht oeffnen: versiegelten Schluessel mit (enc|backup)-Schluessel, dann Inhalt
    static func decryptMessage(chatID: String, msgID: String, sender: String, device: String, ts: Int64, replyTo: String?,
                               bodyCT: String, sealedKey: String, pk: Data, sk: Data) -> Data? {
        guard let sk2 = unb64(sealedKey, 80), let k = sealOpen(sk2, pk: pk, sk: sk), let raw = unb64(bodyCT), raw.count > 24 else { return nil }
        return aeadDecrypt(raw.dropFirst(24), ad: msgAD(chatID: chatID, msgID: msgID, sender: sender, device: device, ts: ts, replyTo: replyTo),
                           nonce: raw.prefix(24), key: k)
    }
    static func verifyMessage(chatID: String, msgID: String, sender: String, device: String, ts: Int64, replyTo: String?,
                              bodyCT: String, sig: String, signPK: Data?) -> Bool {
        verify(unb64(sig, 64), sigMsg(chatID: chatID, msgID: msgID, sender: sender, device: device, ts: ts, replyTo: replyTo, bodyCT: bodyCT), pk: signPK)
    }
    /// Anhang: nonce24 || AEAD(datei, AD = LP("saga-chat/v1/file", chat_id))
    static func encryptFile(chatID: String, data: Data, key: Data? = nil, nonce: Data? = nil) -> (blob: Data, key: Data)? {
        let k = key ?? aeadKey(), n = nonce ?? random(24)
        guard let ct = aeadEncrypt(data, ad: lp(V + "file", chatID), nonce: n, key: k) else { return nil }
        return (n + ct, k)
    }
    static func decryptFile(chatID: String, blob: Data, key: Data) -> Data? {
        guard blob.count > 24 else { return nil }
        return aeadDecrypt(blob.dropFirst(24), ad: lp(V + "file", chatID), nonce: blob.prefix(24), key: key)
    }
    /// n Bloecke a 5 Bytes -> BE-Zahl mod 100000 -> "01234 56789 ..."
    static func digits(_ h: Data, blocks: Int) -> String {
        let b = [UInt8](h)
        return (0..<blocks).map { i -> String in
            var v: UInt64 = 0
            for j in 0..<5 { v = (v << 8) | UInt64(b[i * 5 + j]) }
            return String(format: "%05llu", v % 100_000)
        }.joined(separator: " ")
    }
    /// Kopplungs-Code (20 Ziffern) eines neuen Geraets
    static func linkCode(uid: String, deviceID: String, encPK: Data, signPK: Data) -> String {
        digits(gh(lp(V + "link", uid, deviceID, encPK, signPK)), blocks: 4)
    }
    /// Sicherheitsnummer (60 Ziffern), unabhaengig von der Reihenfolge der beiden Konten
    static func safetyNumber(uidA: String, identA: Data, uidB: String, identB: Data) -> String {
        var a = (uidA, identA), b = (uidB, identB)
        if Array(b.0.utf8).lexicographicallyPrecedes(Array(a.0.utf8)) { swap(&a, &b) }
        return digits(gh(lp(V + "safety", a.0, a.1, b.0, b.1), 64), blocks: 12)
    }
}
