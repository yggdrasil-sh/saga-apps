import Foundation
import CryptoKit

// Gjallarhorn (Saga 2.5): Frame-E2E saga-horn/v1, reine Logik ohne WebRTC (Vertrag: docs/HORN-E2E.md im Server-Repo,
// Vektoren tests/vectors/horn_e2e.json -> ios/Tests/vectors/horn_e2e.json, Test HornE2EVectorsTests).
// Enthaelt: Ableitungen (FK, Saga-Ratsche), Rahmenformat (AES-128-GCM, Trailer IV||0x0C||kid), Empfaenger fuer einen
// Sender mit Schluesselwahl, Ratsche beim ersten Rahmen, Ablauf und Wiederholungsschutz, Runensiegel, fkey-Pruefung,
// Plan fuer die FrameCryptor-Schluesselplaetze (vorab geratschte Stufen, Ueberschreiben abgelaufener Plaetze).
enum HornE2E {
    static let frameSalt = Data("saga-horn/v1/frame".utf8)
    static let ratchetSalt = Data("saga-horn-ratchet".utf8)
    static let frameInfo = Data(count: 128)
    static let ivLen = 12, tagLen = 16, trailerLen = 14, ring = 16, window = 128, ahead = 4, keep = 3
    static let switchMs = 1500.0, graceMs = 3000.0

    // MARK: Ableitungen

    /// FK = HKDF-SHA256(K, salt "saga-horn/v1/frame", info 128 x 00, 16) - genau der FrameCryptor-HKDF-Modus
    static func frameKey(_ k: Data) -> SymmetricKey {
        HKDF<SHA256>.deriveKey(inputKeyMaterial: SymmetricKey(data: k), salt: frameSalt, info: frameInfo, outputByteCount: 16)
    }
    /// Saga-Ratsche K' = HKDF-SHA256(K, salt "saga-horn-ratchet", info leer, 32) - NICHT die FrameCryptor-Ratsche
    static func ratchet(_ k: Data) -> Data {
        HKDF<SHA256>.deriveKey(inputKeyMaterial: SymmetricKey(data: k), salt: ratchetSalt, info: Data(), outputByteCount: 32)
            .withUnsafeBytes { Data($0) }
    }
    static func ratchet(_ k: Data, times: Int) -> Data { (0..<max(0, times)).reduce(k) { r, _ in ratchet(r) } }

    // MARK: Rahmen

    enum Kind: String { case audio, video }
    /// Laenge des Klartext-Kopfs (AAD): Opus 1, VP8-Schluesselbild 10, VP8-Zwischenbild 3; nil = verwerfen
    static func headerLen(_ kind: Kind, _ type: String?) -> Int? {
        switch kind {
        case .audio: return 1
        case .video: return type == "key" ? 10 : type == "delta" ? 3 : nil
        }
    }
    /// IV = SSRC || RTP-Zeitstempel || Zaehler (je u32 big-endian)
    static func iv(ssrc: UInt32, ts: UInt32, ctr: UInt32) -> Data {
        var d = Data(capacity: 12)
        for v in [ssrc, ts, ctr] { withUnsafeBytes(of: v.bigEndian) { d.append(contentsOf: $0) } }
        return d
    }
    /// Kopf || Chiffrat || Tag || IV || 0x0C || kid; nil, wenn der Rahmen zu kurz ist
    static func seal(_ fk: SymmetricKey, frame: Data, h: Int, kid: Int, iv: Data) -> Data? {
        guard frame.count >= h, iv.count == ivLen, (0..<ring).contains(kid),
              let box = try? AES.GCM.seal(frame.dropFirst(h), using: fk, nonce: AES.GCM.Nonce(data: iv), authenticating: frame.prefix(h))
        else { return nil }
        var out = Data(frame.prefix(h)); out.append(box.ciphertext); out.append(box.tag); out.append(iv)
        out.append(UInt8(ivLen)); out.append(UInt8(kid))
        return out
    }

    enum Drop: String, Error { case codec, short, format, nokey, replay, auth }
    struct Parsed { let kid: Int; let ivAt: Int; let ssrc: UInt32; let ts: UInt32 }
    static func parse(_ d: Data, h: Int?) -> Result<Parsed, Drop> {
        guard let h else { return .failure(.codec) }
        let b = [UInt8](d)
        guard b.count >= h + tagLen + trailerLen else { return .failure(.short) }
        guard b[b.count - 2] == UInt8(ivLen), Int(b[b.count - 1]) < ring else { return .failure(.format) }
        let at = b.count - trailerLen
        func u32(_ i: Int) -> UInt32 { b[i..<i + 4].reduce(0) { $0 << 8 | UInt32($1) } }
        return .success(Parsed(kid: Int(b[b.count - 1]), ivAt: at, ssrc: u32(at), ts: u32(at + 4)))
    }
    static func open(_ fk: SymmetricKey, _ d: Data, h: Int, _ p: Parsed) -> Data? {
        let b = [UInt8](d)
        let ivb = Data(b[p.ivAt..<p.ivAt + ivLen]), body = b[h..<p.ivAt]
        guard body.count >= tagLen, let nonce = try? AES.GCM.Nonce(data: ivb),
              let box = try? AES.GCM.SealedBox(nonce: nonce, ciphertext: Data(body.dropLast(tagLen)), tag: Data(body.suffix(tagLen))),
              let plain = try? AES.GCM.open(box, using: fk, authenticating: Data(b[0..<h]))
        else { return nil }
        return Data(b[0..<h]) + plain
    }
}

extension HornE2E {
    // MARK: Wiederholungsschutz (Fenster 128 ueber den RTP-Zeitstempel der IV, serielle Arithmetik mod 2^32)

    struct ReplayWindow {
        let size: Int
        private(set) var vals: [UInt32] = []
        private(set) var max: UInt32?
        init(size: Int = HornE2E.window) { self.size = size }
        /// > 0 neuer, < 0 aelter, 0 gleich
        private func rel(_ t: UInt32, _ m: UInt32) -> Int64 {
            let d = t &- m
            return d == 0 ? 0 : d < 0x8000_0000 ? Int64(d) : -Int64(UInt32.max - d) - 1
        }
        func check(_ t: UInt32) -> Bool {
            guard let m = max else { return true }
            let r = rel(t, m)
            if r > 0 { return true }
            if r == 0 || vals.contains(t) { return false }
            if vals.count < size { return true }
            let oldest = vals.map { Int64(m &- $0) }.max() ?? 0
            return -r < oldest
        }
        mutating func accept(_ t: UInt32) {
            if let m = max { if rel(t, m) > 0 { max = t } } else { max = t }
            vals.append(t)
            if vals.count > size { vals.removeFirst() }
        }
    }

    // MARK: Senden (eigener Transformer)

    /// Zaehler je (Schluessel, SSRC) ab 0, nie wiederholt
    struct Sender {
        private(set) var n: Int
        private(set) var key: Data
        private var fk: SymmetricKey
        private var ctr: [UInt32: UInt32] = [:]
        init(n: Int, key: Data) { self.n = n; self.key = key; fk = HornE2E.frameKey(key) }
        mutating func use(n: Int, key: Data) { self.n = n; self.key = key; fk = HornE2E.frameKey(key); ctr = [:] }
        mutating func encrypt(_ frame: Data, kind: Kind, type: String?, ssrc: UInt32, ts: UInt32) -> Data? {
            guard let h = HornE2E.headerLen(kind, type), frame.count >= h, frame.count > 0 else { return nil }
            let c = ctr[ssrc, default: 0]
            guard c < UInt32.max else { return nil }                         // Zaehler erschoepft: neuer Schluessel noetig
            ctr[ssrc] = c + 1
            return HornE2E.seal(fk, frame: frame, h: h, kid: n % HornE2E.ring, iv: HornE2E.iv(ssrc: ssrc, ts: ts, ctr: c))
        }
    }

    // MARK: Empfangen (eigener Transformer), EIN Sender

    final class Receiver {
        private struct Gen { let key: Data; let fk: SymmetricKey; var until = Double.infinity; var win: [UInt32: ReplayWindow] = [:] }
        private var keys: [Int: Gen] = [:], cand: [Int: Gen] = [:]
        private(set) var newest = -1, active = -1
        let session: String
        init(session: String) { self.session = session }

        func add(n: Int, key: Data) {
            keys = keys.filter { $0.key == n || $0.key % HornE2E.ring != n % HornE2E.ring }
            keys[n] = Gen(key: key, fk: HornE2E.frameKey(key))
            cand[n] = nil
            newest = Swift.max(newest, n)
        }
        var held: [Int] { keys.keys.sorted() }
        private func trim(_ now: Double) {
            keys = keys.filter { $0.key >= newest - HornE2E.keep + 1 && $0.value.until > now }
        }
        func decrypt(_ d: Data, kind: Kind, type: String?, now: Double) -> Result<Data, Drop> {
            let h = HornE2E.headerLen(kind, type)
            let p: Parsed
            switch HornE2E.parse(d, h: h) { case .failure(let e): return .failure(e); case .success(let x): p = x }
            guard let h, newest >= 0 else { return .failure(.nokey) }
            trim(now)
            var kn: Int, g: Gen, isCand = false
            if let hit = keys.first(where: { $0.key % HornE2E.ring == p.kid }) {
                (kn, g) = (hit.key, hit.value)
            } else {
                let j = ((p.kid - newest % HornE2E.ring) % HornE2E.ring + HornE2E.ring) % HornE2E.ring
                guard (1...HornE2E.ahead).contains(j), let base = keys[newest] else { return .failure(.nokey) }
                kn = newest + j
                if cand[kn] == nil { let k = HornE2E.ratchet(base.key, times: j); cand[kn] = Gen(key: k, fk: HornE2E.frameKey(k)) }
                g = cand[kn]!; isCand = true
            }
            if let w = g.win[p.ssrc], !w.check(p.ts) { return .failure(.replay) }
            guard let out = HornE2E.open(g.fk, d, h: h, p) else { return .failure(.auth) }
            g.win[p.ssrc, default: ReplayWindow()].accept(p.ts)
            if isCand { cand[kn] = nil; newest = Swift.max(newest, kn) }
            keys[kn] = g
            if kn > active {
                active = kn
                for (m, x) in keys where m < kn && x.until == .infinity { keys[m]?.until = now + HornE2E.graceMs }
            }
            return .success(out)
        }
    }
}

extension HornE2E {
    // MARK: Runensiegel

    static let runes: [Character] = Array("ᚠᚢᚦᚨᚱᚲᚷᚹᚺᚾᛁᛃᛇᛈᛉᛊᛏᛒᛖᛗᛚᛜᛞᛟ")
    static let runeNames = ["fehu", "uruz", "thurisaz", "ansuz", "raidho", "kaunan", "gebo", "wunjo", "hagalaz", "naudiz",
                            "isa", "jera", "eihwaz", "perthro", "algiz", "sowilo", "tiwaz", "berkanan", "ehwaz", "mannaz",
                            "laguz", "ingwaz", "dagaz", "othala"]
    /// H = SHA-256(call_id || sortierte, eindeutige sign_pk); 40 Bit -> 6 Runen (Basis 24, r_0 zuerst)
    static func runeSeal(callId: String, signPks: [Data]) -> (hash: Data, idx: [Int], runes: String) {
        let pks = Set(signPks).sorted { $0.lexicographicallyPrecedes($1) }
        var d = Data(callId.utf8); pks.forEach { d.append($0) }
        let hash = Data(SHA256.hash(data: d))
        var v = hash.prefix(5).reduce(UInt64(0)) { $0 << 8 | UInt64($1) }, idx: [Int] = []
        for _ in 0..<6 { idx.append(Int(v % 24)); v /= 24 }
        return (hash, idx, String(idx.map { runes[$0] }))
    }

    // MARK: fkey

    struct FKey: Equatable { let n: Int; let key: Data; let s: String }
    /// {"t":"fkey","n","kid","key"(Base64, 32 Byte),"s"(16 hex klein)}; nil = still ignorieren
    static func parseFKey(_ o: Any?) -> FKey? {
        guard let b = o as? [String: Any], b["t"] as? String == "fkey", let nn = b["n"] as? NSNumber,
              CFGetTypeID(nn) == CFNumberGetTypeID(), !CFNumberIsFloatType(nn), let kid = b["kid"] as? NSNumber,
              CFGetTypeID(kid) == CFNumberGetTypeID(), !CFNumberIsFloatType(kid),
              let s = b["s"] as? String, let ks = b["key"] as? String else { return nil }
        let n = nn.int64Value
        guard (0...0x7FFF_FFFF).contains(n), kid.int64Value == n % Int64(ring), s.count == 16,
              s.allSatisfy({ "0123456789abcdef".contains($0) }),
              let key = Data(base64Encoded: ks), key.count == 32, key.base64EncodedString() == ks else { return nil }
        return FKey(n: Int(n), key: key, s: s)
    }

    // MARK: FrameCryptor-Plan (Apps, docs/HORN-E2E.md §6)

    /// Schluesselplaetze, die die App am FrameCryptor-KeyProvider fuer einen Sender vorab setzt: die gehaltene neueste
    /// Generation n und die Ratschenstufen n+1 ... n+4 (FrameCryptor kann die Saga-Ratsche beim ersten Rahmen nicht).
    static func cryptorSlots(newest n: Int, key: Data) -> [(n: Int, kid: Int, key: Data)] {
        var out = [(n: n, kid: n % ring, key: key)], k = key
        for j in 1...ahead { k = ratchet(k); out.append((n + j, (n + j) % ring, k)) }
        return out
    }
    /// FrameCryptor kann Schluessel nicht loeschen: abgelaufene Plaetze mit Zufall ueberschreiben
    static func scrubKey() -> Data { SymmetricKey(size: .bits256).withUnsafeBytes { Data($0) } }
}
