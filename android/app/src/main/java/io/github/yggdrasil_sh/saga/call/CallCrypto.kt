package io.github.yggdrasil_sh.saga.call

import io.github.yggdrasil_sh.saga.chat.ChatCrypto
import io.github.yggdrasil_sh.saga.chat.ChatCryptoException
import io.github.yggdrasil_sh.saga.chat.ChatFormat
import io.github.yggdrasil_sh.saga.chat.ChatFormat.b64
import io.github.yggdrasil_sh.saga.chat.ChatFormat.unb64
import io.github.yggdrasil_sh.saga.chat.DeviceKeys
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** Geoeffnetes Signal: body (JSON-Text) + Signatur (64 Bytes) - noch NICHT geprueft */
class OpenedSignal(val body: String, val sig: ByteArray)

/**
 * Anruf-Signalisierung saga-call/v1 (Gegenstueck zu static/chat-crypto.js sealSignal/openSignal/verifySignal und
 * tests/chatlib.py seal_signal/open_signal/verify_signal) - nur libsodium ueber ChatCrypto:
 *
 *   sig    = crypto_sign_detached(LP("saga-call/v1/signal", call_id, from_uid, from_device, to_uid, to_device, body), sign_sk)
 *   inner  = {"v":1,"body":body,"sig":B64(sig)}
 *   sealed = B64(crypto_box_seal(UTF-8(inner), ziel_enc_pk))
 *
 * Geprueft mit tests/call_vectors.json (Unit-Test CallVectorsTest).
 */
class CallCrypto(private val c: ChatCrypto) {
    companion object {
        const val VC = "saga-call/v1/"

        fun signalMsg(callId: String, fromUid: String, fromDevice: String, toUid: String, toDevice: String, body: String): ByteArray =
            ChatFormat.lp(VC + "signal", callId, fromUid, fromDevice, toUid, toDevice, body)

        /** Wie Python json.dumps(..., separators=(",", ":")): Feldreihenfolge v, body, sig; Nicht-ASCII als \\uXXXX */
        fun signalInner(body: String, sig: ByteArray): String =
            "{\"v\":1,\"body\":" + jsonString(body) + ",\"sig\":" + jsonString(b64(sig)) + "}"

        /** JSON-Zeichenkette wie Python (ensure_ascii) - fuer byte-gleiches inner; Empfaenger lesen jedes gueltige JSON */
        fun jsonString(s: String): String {
            val sb = StringBuilder(s.length + 16).append('"')
            for (ch in s) {
                when (ch) {
                    '"' -> sb.append("\\\"")
                    '\\' -> sb.append("\\\\")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    '\b' -> sb.append("\\b")
                    '\u000c' -> sb.append("\\f")
                    else -> if (ch.code < 0x20 || ch.code > 0x7f) sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
                }
            }
            return sb.append('"').toString()
        }

        private val FP_RE = Regex("^a=fingerprint:(\\S+) ([0-9A-Fa-f:]+)\\s*$", RegexOption.MULTILINE)

        /** sortierte, eindeutige Liste "<alg-klein> <HEX-GROSS>" aus allen a=fingerprint-Zeilen */
        fun sdpFingerprints(sdp: String?): List<String> =
            FP_RE.findAll(sdp.orEmpty().replace("\r\n", "\n")).map { it.groupValues[1].lowercase() + " " + it.groupValues[2].uppercase() }
                .toSortedSet().toList()

        /** fp aus dem Signal muss (sortiert) genau den Fingerabdruecken der SDP entsprechen und darf nicht leer sein */
        fun fpMatches(sdp: String?, fp: List<String>): Boolean {
            val want = sdpFingerprints(sdp)
            return want.isNotEmpty() && want == fp.sorted()
        }

        /** Pflicht 5: DTLS-Zertifikat des Gegenuebers (aus den Stats) muss in den signierten Fingerabdruecken stehen */
        fun certMatches(alg: String?, fingerprint: String?, fps: List<String>): Boolean? {
            if (alg.isNullOrBlank() || fingerprint.isNullOrBlank()) return null          // (noch) keine Angabe
            return (alg.lowercase() + " " + fingerprint.uppercase()) in fps
        }
    }

    fun sign(callId: String, fromUid: String, dev: DeviceKeys, toUid: String, toDevice: String, body: String): ByteArray =
        c.sign(signalMsg(callId, fromUid, dev.id, toUid, toDevice, body), dev.signSk)

    /** Signal signieren und fuer das Zielgeraet versiegeln -> B64 */
    fun sealSignal(callId: String, fromUid: String, dev: DeviceKeys, toUid: String, toDevice: String, toPk: ByteArray, body: String): String {
        val sig = sign(callId, fromUid, dev, toUid, toDevice, body)
        return b64(c.seal(signalInner(body, sig).toByteArray(Charsets.UTF_8), toPk))
    }

    /** Oeffnen mit dem eigenen Geraet; inner.v muss 1 sein (Pflicht 1) */
    fun openSignal(sealed: String, pk: ByteArray, sk: ByteArray): OpenedSignal {
        val raw = unb64(sealed) ?: throw ChatCryptoException("signal")
        val inner = String(c.sealOpen(raw, pk, sk), Charsets.UTF_8)
        val o = Json.parseToJsonElement(inner) as? JsonObject ?: throw ChatCryptoException("signal")
        if ((o["v"] as? JsonPrimitive)?.intOrNull != 1) throw ChatCryptoException("signal version")
        val body = (o["body"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw ChatCryptoException("signal")
        val sig = unb64((o["sig"] as? JsonPrimitive)?.content, 64) ?: throw ChatCryptoException("signal")
        return OpenedSignal(body, sig)
    }

    /** Pflicht 3: mit MEINEM uid/Geraet als Ziel pruefen (verhindert Umleiten an andere Geraete/Anrufe) */
    fun verifySignal(callId: String, fromUid: String, fromDevice: String, toUid: String, toDevice: String, body: String, sig: ByteArray, signPk: ByteArray?): Boolean =
        c.verify(sig, signalMsg(callId, fromUid, fromDevice, toUid, toDevice, body), signPk)
}
