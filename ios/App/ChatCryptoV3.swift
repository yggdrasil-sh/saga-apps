import Foundation
import Clibsodium

// Chat-Stufe 3 (Saga 2.5, Konzept Chat B1): Reaktionen - genau wie saga/chat_v3.py und static/chat-crypto.js
// (encryptReaction/verifyReaction/decryptReaction). Nachrichten und Bearbeitungen sind saga-chat/v2 (ChatCryptoV2.swift).
//   K_r  = generichash(32, LP(V3+"react-key", chat_ref, msg_id), key = K der Nachricht)
//   body = {"e": "<Emoji>" | "", "ts": <ms>}            ("" = zuruecknehmen)
//   ct   = B64(nonce24 || xchacha20poly1305(body, AD, nonce, K_r)), AD = LP(V3+"react", chat_ref, msg_id, handle, geraet)
//   sig  = sign_detached(LP(V3+"react-sig", chat_ref, msg_id, handle, geraet, ts, generichash32(raw(ct))), device_sign_sk)
// Geprueft gegen tests/vectors/chat_v3.json (Kopie in ios/Tests/vectors, XCTest ChatX3Tests).

extension ChatCrypto {
    static let V3 = "saga-chat/v3/"

    /// crypto_generichash (BLAKE2b) mit Schluessel
    static func ghKeyed(_ d: Data, key: Data, _ n: Int = 32) -> Data {
        ready()
        var out = [UInt8](repeating: 0, count: n)
        let m = [UInt8](d), k = [UInt8](key)
        _ = crypto_generichash(&out, n, m, UInt64(m.count), k, k.count)
        return Data(out)
    }

    static func reactKey(_ K: Data, ref: String, msgID: String) -> Data { ghKeyed(lp(V3 + "react-key", ref, msgID), key: K) }
    static func reactAD(ref: String, msgID: String, handle: String, device: String) -> Data { lp(V3 + "react", ref, msgID, handle, device) }
    static func reactSigMsg(ref: String, msgID: String, handle: String, device: String, ts: Int64, ct: String) -> Data {
        lp(V3 + "react-sig", ref, msgID, handle, device, String(ts), gh(unb64(ct) ?? Data()))
    }

    /// Reaktion fuer PUT .../reaction (nonce nur fuer Testvektoren vorgeben). Inhalt kompakt wie JSON.stringify.
    static func encryptReaction(K: Data, ref: String, msgID: String, handle: String, device: String, signSK: Data, emoji: String,
                                ts: Int64, nonce: Data? = nil) -> ChatX3.Reaction? {
        let n = nonce ?? random(24)
        let body = Data(ChatX3.reactBodyJSON(emoji: emoji, ts: ts).utf8)
        guard let c = aeadEncrypt(body, ad: reactAD(ref: ref, msgID: msgID, handle: handle, device: device), nonce: n, key: reactKey(K, ref: ref, msgID: msgID))
        else { return nil }
        let ct = b64(n + c)
        let sig = sign(reactSigMsg(ref: ref, msgID: msgID, handle: handle, device: device, ts: ts, ct: ct), sk: signSK)
        return ChatX3.Reaction(by: handle, dev: device, ts: ts, ct: ct, sig: b64(sig))
    }
    static func verifyReaction(_ r: ChatX3.Reaction, ref: String, msgID: String, signPK: Data?) -> Bool {
        guard r.ct.count <= ChatX3.reactCTMax, let raw = unb64(r.ct), raw.count >= 24 + 16 + 2 else { return false }
        return verify(unb64(r.sig, 64), reactSigMsg(ref: ref, msgID: msgID, handle: r.by, device: r.dev, ts: r.ts, ct: r.ct), pk: signPK)
    }
    /// -> Emoji ("" = zurueckgenommen) oder nil (Schluessel, Bindung oder Inhalt passen nicht)
    static func decryptReaction(_ r: ChatX3.Reaction, K: Data, ref: String, msgID: String) -> String? {
        guard let raw = unb64(r.ct), raw.count > 24,
              let pt = aeadDecrypt(raw.dropFirst(24), ad: reactAD(ref: ref, msgID: msgID, handle: r.by, device: r.dev), nonce: raw.prefix(24),
                                   key: reactKey(K, ref: ref, msgID: msgID)) else { return nil }
        return ChatX3.reactBody(pt, ts: r.ts)
    }
    /// Pruefen + oeffnen: Signatur des Geraets (aus den selbst geprueften Schluesseln), dann Inhalt
    static func openReaction(_ r: ChatX3.Reaction, K: Data, ref: String, msgID: String, signPK: Data?) -> String? {
        guard verifyReaction(r, ref: ref, msgID: msgID, signPK: signPK) else { return nil }
        return decryptReaction(r, K: K, ref: ref, msgID: msgID)
    }
}

/// Reaktionen pruefen und oeffnen (ohne Hauptthread): Signatur des Geraets aus den selbst geprueften Schluesseln, Inhalt
enum ChatX3Open {
    struct Job: Sendable { var mid: String; var K: Data; var items: [ChatX3.Reaction] }
    private static let lock = NSLock()
    nonisolated(unsafe) private static var cache: [String: String] = [:]
    /// pks: "<handle>|<geraet>" -> Ed25519-Schluessel
    static func run(_ jobs: [Job], pks: [String: Data], ref: String) -> [String: [ChatX3.Opened]] {
        var out: [String: [ChatX3.Opened]] = [:]
        for j in jobs {
            var list: [ChatX3.Opened] = []
            for r in ChatX3.newest(j.items) {
                guard let pk = pks[r.by + "|" + r.dev] else { continue }
                let ck = ref + "|" + j.mid + "|" + ChatCrypto.b64(j.K) + "|" + r.by + "|" + r.dev + "|" + String(r.ts) + "|" + r.sig + "|" + r.ct + "|" + ChatCrypto.b64(pk)
                if let e = lock.withLock({ cache[ck] }) { list.append(.init(by: r.by, e: e, ts: r.ts)); continue }
                guard let e = ChatCrypto.openReaction(r, K: j.K, ref: ref, msgID: j.mid, signPK: pk) else { continue }
                lock.withLock { if cache.count > 20_000 { cache.removeAll() }; cache[ck] = e }
                list.append(.init(by: r.by, e: e, ts: r.ts))
            }
            out[j.mid] = list
        }
        return out
    }
}
