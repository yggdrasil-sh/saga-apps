package io.github.yggdrasil_sh.saga.chat

import io.github.yggdrasil_sh.saga.chat.ChatFormat.b64
import io.github.yggdrasil_sh.saga.chat.ChatFormat.lp
import io.github.yggdrasil_sh.saga.chat.ChatFormat.unb64

// Chat-Stufe 3 (Saga 2.5, Konzept Chat B1): Reaktionen - genau wie saga/chat_v3.py und static/chat-crypto.js
// (encryptReaction/verifyReaction/decryptReaction). Nachrichten und Bearbeitungen sind saga-chat/v2 (FedCrypto).
//   K_r  = generichash(32, LP(V3+"react-key", chat_ref, msg_id), key = K der Nachricht)
//   body = {"e": "<Emoji>" | "", "ts": <ms>}            ("" = zuruecknehmen)
//   ct   = B64(nonce24 || xchacha20poly1305(body, AD, nonce, K_r)), AD = LP(V3+"react", chat_ref, msg_id, handle, geraet)
//   sig  = sign_detached(LP(V3+"react-sig", chat_ref, msg_id, handle, geraet, ts, generichash32(raw(ct))), device_sign_sk)
// Geprueft gegen tests/vectors/chat_v3.json (Kopie in src/test/resources, Unit-Test ChatV3VectorsTest).

class ReactCrypto(private val c: ChatCrypto) {
    companion object {
        const val V3 = "saga-chat/v3/"
    }

    fun reactKey(k: ByteArray, ref: String, msgId: String): ByteArray = c.genericHashKeyed(lp(V3 + "react-key", ref, msgId), k)

    fun reactAd(ref: String, msgId: String, handle: String, device: String): ByteArray = lp(V3 + "react", ref, msgId, handle, device)

    fun reactSigMsg(ref: String, msgId: String, handle: String, device: String, ts: Long, ct: String): ByteArray =
        lp(V3 + "react-sig", ref, msgId, handle, device, ts.toString(), c.genericHash(unb64(ct) ?: ByteArray(0)))

    /** Reaktion fuer PUT .../reaction (nonce nur fuer Testvektoren vorgeben) */
    fun encrypt(
        k: ByteArray, ref: String, msgId: String, handle: String, device: String, signSk: ByteArray, emoji: String, ts: Long,
        nonce: ByteArray = c.random(24),
    ): Reaction {
        val body = ChatX3.reactBodyJson(emoji, ts).toByteArray(Charsets.UTF_8)
        val ct = b64(nonce + c.aeadEncrypt(body, reactAd(ref, msgId, handle, device), nonce, reactKey(k, ref, msgId)))
        val sig = b64(c.sign(reactSigMsg(ref, msgId, handle, device, ts, ct), signSk))
        return Reaction(handle, device, ts, ct, sig)
    }

    fun verify(r: Reaction, ref: String, msgId: String, signPk: ByteArray?): Boolean {
        if (r.ct.length > 4096) return false
        val raw = unb64(r.ct) ?: return false
        if (raw.size < 24 + 16 + 2) return false
        return try {
            c.verify(unb64(r.sig, 64), reactSigMsg(ref, msgId, r.by, r.dev, r.ts, r.ct), signPk)
        } catch (_: Exception) {
            false
        }
    }

    /** -> Emoji ("" = zurueckgenommen) oder null (Schluessel, Bindung oder Inhalt passen nicht) */
    fun decrypt(r: Reaction, k: ByteArray, ref: String, msgId: String): String? = try {
        val raw = unb64(r.ct) ?: throw ChatCryptoException("ct")
        if (raw.size <= 24 + 16) throw ChatCryptoException("ct")
        val pt = c.aeadDecrypt(raw.copyOfRange(24, raw.size), reactAd(ref, msgId, r.by, r.dev), raw.copyOfRange(0, 24), reactKey(k, ref, msgId))
        ChatX3.reactBody(String(pt, Charsets.UTF_8), r.ts)
    } catch (_: Exception) {
        null
    }

    /** Pruefen + oeffnen: Signatur des Geraets (aus den selbst geprueften Schluesseln), dann Inhalt */
    fun open(r: Reaction, k: ByteArray, ref: String, msgId: String, signPk: ByteArray?): String? =
        if (verify(r, ref, msgId, signPk)) decrypt(r, k, ref, msgId) else null
}

/** Reaktionen pruefen und oeffnen (Hintergrund-Thread): je Person die neueste, nur mit bekanntem Geraeteschluessel */
object ReactOpen {
    class Job(val mid: String, val key: ByteArray, val items: List<Reaction>)

    private val cache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** pks: "<handle>|<geraet>" -> Ed25519-Schluessel */
    fun run(rc: ReactCrypto, jobs: List<Job>, pks: Map<String, ByteArray>, ref: String): Map<String, List<OpenedReaction>> {
        if (cache.size > 20_000) cache.clear()
        val out = LinkedHashMap<String, List<OpenedReaction>>()
        for (j in jobs) {
            val list = ArrayList<OpenedReaction>()
            for (r in ChatX3.newest(j.items)) {
                val pk = pks[r.by + "|" + r.dev] ?: continue
                val ck = ref + "|" + j.mid + "|" + b64(j.key) + "|" + r.by + "|" + r.dev + "|" + r.ts + "|" + r.sig + "|" + r.ct + "|" + b64(pk)
                val e = cache[ck] ?: rc.open(r, j.key, ref, j.mid, pk)?.also { cache[ck] = it } ?: continue
                list.add(OpenedReaction(r.by, e, r.ts))
            }
            out[j.mid] = list
        }
        return out
    }
}
