package io.github.yggdrasil_sh.saga.chat

import io.github.yggdrasil_sh.saga.chat.ChatFormat.b64
import io.github.yggdrasil_sh.saga.chat.ChatFormat.lp
import io.github.yggdrasil_sh.saga.chat.ChatFormat.unb64
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

// Chat-Krypto saga-chat/v2 (Saga 2.3, Chats ueber Servergrenzen) - Gegenstueck zu saga/chat_v2.py, static/chat-crypto.js
// (checkKeysV2, tofuV2, recipientsV2, *SignalV2) und tests/chatlib.py. Gebunden an den foederierten Handle
// ('~' + 16 Zeichen '@' host) und die globale chat_ref ('<32 hex>@<heim>') statt an Anmeldename und lokale chat_id.
// Schluessel (Identitaet, Backup, Geraet) sind dieselben wie in v1; lokale Chats bleiben v1 (ChatCrypto, unveraendert).
// Geprueft mit tests/vectors/chat_v2.json (Unit-Test FedVectorsTest).

/** Eintrag aus GET /api/chat/v2/keys (ungeprueft) */
@Serializable
data class FedKeyDevice(
    val id: String = "",
    @SerialName("enc_pub") val encPub: String = "",
    @SerialName("sign_pub") val signPub: String = "",
    @SerialName("fed_sig") val fedSig: String = "",
    val revoked: Boolean = false,
)

@Serializable
data class FedDeviceSet(val ver: Long = 0, val ids: List<String> = emptyList(), val sig: String = "")

@Serializable
data class FedKeyEntry(
    val v: Int = 2,
    val handle: String = "",
    @SerialName("ident_pub") val identPub: String = "",
    @SerialName("backup_pub") val backupPub: String = "",
    val sig: String = "",
    val dset: FedDeviceSet? = null,
    val devices: List<FedKeyDevice> = emptyList(),
    val source: String? = null,
    val stale: Boolean = false,
)

/** Selbst gepruefte Schluessel eines Handles: nur Geraete mit gueltigem fed_sig; revoked auch aus der signierten Liste */
class FedKeys(
    val handle: String,
    val identPk: ByteArray,
    val backupPk: ByteArray,
    val identPub: String,
    /** Version der gueltig signierten Geraete-Liste (0 = keine/ungueltig, Altbestand) */
    val ver: Long,
    val signed: Boolean,
    val devices: List<FedKeyDevice>,
) {
    val active: List<FedKeyDevice> get() = devices.filter { !it.revoked }
    fun device(id: String): FedKeyDevice? = devices.firstOrNull { it.id == id }
}

/** Gemerkter Stand je Handle (TOFU v2, getrennt von den v1-Eintraegen) */
@Serializable
data class Trust2(
    val ident: String,
    val verified: Boolean = false,
    val changed: Boolean = false,
    val old: String? = null,
    val dver: Long = 0,
)

enum class TofuV2 { TRUST, WARN, BLOCK, REJECT, MISSING }

/** Ergebnis von tofuV2: Bewertung + neu zu speichernder Stand (null = nichts aendern) */
class TofuStep(val result: TofuV2, val store: Trust2?)

/** Geoeffnete Nachricht v2 */
class FedOpened(val plain: String?, val verified: Boolean)

/** Ergebnis von encryptV2 */
class EncryptedV2(val id: String, val ts: Long, val bodyCt: String, val sig: String, val keys: Map<String, String>, val replyTo: String?, val ai: String?)

object FedFormat {
    const val V2 = "saga-chat/v2/"
    const val VC2 = "saga-call/v2/"
    val HANDLE_RE = Regex("~[a-z2-7]{16}@[a-z0-9.:-]{1,260}")
    val DEVICE_RE = Regex("[0-9a-f]{32}")

    fun hostOf(handle: String): String = handle.substringAfter('@', "")

    fun handleMsg(handle: String, identPk: ByteArray, backupPk: ByteArray) = lp(V2 + "handle", handle, identPk, backupPk)

    fun deviceMsg(handle: String, id: String, encPk: ByteArray, signPk: ByteArray) = lp(V2 + "device", handle, id, encPk, signPk)

    fun devicesMsg(handle: String, ver: Long, ids: List<String>) = lp(V2 + "devices", handle, ver.toString(), ids.sorted().joinToString(","))

    fun msgAd(chatRef: String, id: String, sender: String, device: String, ts: Long, replyTo: String?, ai: String?) =
        lp(V2 + "msg", chatRef, id, sender, device, ts.toString(), replyTo ?: "", ai ?: "")

    fun fileAd(chatRef: String) = lp(V2 + "file", chatRef)

    fun deviceRecipient(id: String, host: String) = "dev:$id@$host"

    fun backupRecipient(handle: String) = "backup:$handle"

    fun signalMsg(callId: String, fromHandle: String, fromDevice: String, toHandle: String, toDevice: String, body: String) =
        lp(VC2 + "signal", callId, fromHandle, fromDevice, toHandle, toDevice, body)

    /** Wie Python json.dumps(..., separators=(",", ":")): {"v":2,"body":…,"sig":…} */
    fun signalInner(body: String, sig: ByteArray): String =
        "{\"v\":2,\"body\":" + io.github.yggdrasil_sh.saga.call.CallCrypto.jsonString(body) + ",\"sig\":" +
            io.github.yggdrasil_sh.saga.call.CallCrypto.jsonString(b64(sig)) + "}"

    /** Hex-SHA-256 eines Textes (KI-Antwort, mitsigniert als ai) - wie die Server-Belege: Text getrimmt */
    fun aiHash(text: String): String {
        val d = java.security.MessageDigest.getInstance("SHA-256").digest(text.trim().toByteArray(Charsets.UTF_8))
        return ChatFormat.hex(d)
    }

    /**
     * TOFU je Handle (tests/vectors/chat_v2.json "tofu"): erster Kontakt vertraut; gleicher Schluessel mit aelterer
     * Geraete-Liste -> REJECT; Wechsel -> WARN (bleibt, bis bestaetigt), war er ueberprueft -> BLOCK.
     */
    fun tofu(stored: Trust2?, ident: String, ver: Long?): TofuStep {
        val v = ver ?: 0
        if (stored == null) return TofuStep(TofuV2.TRUST, Trust2(ident, verified = false, dver = v))
        if (stored.ident == ident) {
            if (v < stored.dver) return TofuStep(TofuV2.REJECT, null)
            val up = if (v > stored.dver) stored.copy(dver = v) else null
            return TofuStep(if (stored.changed) TofuV2.WARN else TofuV2.TRUST, up)
        }
        if (stored.verified) return TofuStep(TofuV2.BLOCK, null)
        return TofuStep(TofuV2.WARN, Trust2(ident, verified = false, changed = true, old = stored.ident, dver = v))
    }

    /** Empfaenger: je Person backup:<handle>, je aktivem Geraet dev:<id>@<host aus dem Handle> */
    fun recipients(entries: List<FedKeys>): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        for (e in entries) {
            val host = hostOf(e.handle)
            out[backupRecipient(e.handle)] = e.backupPk
            for (d in e.devices) if (!d.revoked) unb64(d.encPub, 32)?.let { out[deviceRecipient(d.id, host)] = it }
        }
        return out
    }
}

class FedCrypto(private val c: ChatCrypto) {
    // ---------------------------------------------------------------- Zertifikate

    fun handleSig(handle: String, ident: Identity): ByteArray = c.sign(FedFormat.handleMsg(handle, ident.identPk, ident.backupPk), ident.identSk)

    fun deviceSig(handle: String, identSk: ByteArray, id: String, encPk: ByteArray, signPk: ByteArray): ByteArray =
        c.sign(FedFormat.deviceMsg(handle, id, encPk, signPk), identSk)

    fun deviceSetSig(handle: String, identSk: ByteArray, ver: Long, ids: List<String>): FedDeviceSet =
        FedDeviceSet(ver, ids.sorted(), b64(c.sign(FedFormat.devicesMsg(handle, ver, ids), identSk)))

    fun verifyHandle(handle: String, identPk: ByteArray?, backupPk: ByteArray?, sig: String?): Boolean =
        identPk != null && backupPk != null && FedFormat.HANDLE_RE.matches(handle) &&
            c.verify(unb64(sig, 64), FedFormat.handleMsg(handle, identPk, backupPk), identPk)

    fun verifyDevice(handle: String, identPk: ByteArray?, d: FedKeyDevice): Boolean {
        if (!FedFormat.DEVICE_RE.matches(d.id)) return false
        val e = unb64(d.encPub, 32) ?: return false
        val s = unb64(d.signPub, 32) ?: return false
        return c.verify(unb64(d.fedSig, 64), FedFormat.deviceMsg(handle, d.id, e, s), identPk)
    }

    fun verifyDeviceSet(handle: String, identPk: ByteArray?, ds: FedDeviceSet?): Boolean {
        if (ds == null || ds.ver <= 0 || ds.ver >= (1L shl 53) || ds.ids.size > 100) return false
        if (ds.ids.any { !FedFormat.DEVICE_RE.matches(it) } || ds.ids.toSet().size != ds.ids.size) return false
        return c.verify(unb64(ds.sig, 64), FedFormat.devicesMsg(handle, ds.ver, ds.ids), identPk)
    }

    /**
     * Eintrag aus /api/chat/v2/keys selbst pruefen (der eigene Server koennte luegen): Handle-Zertifikat mit genau dem
     * angefragten Handle (sonst null), Geraete einzeln (ungueltige verworfen), aktiv nur, was in der gueltig signierten
     * Geraete-Liste steht und nicht widerrufen ist; ohne gueltige Liste (Altbestand) gilt revoked vom Server.
     */
    fun checkKeys(handle: String, e: FedKeyEntry?): FedKeys? {
        if (e == null || e.handle != handle) return null
        val ip = unb64(e.identPub, 32) ?: return null
        val bp = unb64(e.backupPub, 32) ?: return null
        if (!c.verify(unb64(e.sig, 64), FedFormat.handleMsg(handle, ip, bp), ip)) return null
        val ds = e.dset?.takeIf { verifyDeviceSet(handle, ip, it) }
        val devs = e.devices.filter { d -> verifyDevice(handle, ip, d) }
            .map { d -> d.copy(revoked = d.revoked || (ds != null && d.id !in ds.ids)) }
        return FedKeys(handle, ip, bp, e.identPub, ds?.ver ?: 0, ds != null, devs)
    }

    // ---------------------------------------------------------------- Nachrichten

    fun sigMsg(chatRef: String, id: String, sender: String, device: String, ts: Long, replyTo: String?, ai: String?, bodyCt: String): ByteArray =
        lp(FedFormat.V2 + "sig", chatRef, id, sender, device, ts.toString(), replyTo ?: "", ai ?: "",
            c.genericHash(unb64(bodyCt) ?: throw ChatCryptoException("body")))

    /** recipients: Empfaenger-Kennung (dev:<id>@host | backup:<handle>) -> X25519-Schluessel */
    fun encrypt(
        chatRef: String, sender: String, dev: DeviceKeys, plain: String, recipients: Map<String, ByteArray>, replyTo: String? = null,
        ai: String? = null, id: String = c.hexId(), ts: Long = System.currentTimeMillis(), key: ByteArray = c.keygen(), nonce: ByteArray = c.random(24),
    ): EncryptedV2 {
        val ct = c.aeadEncrypt(plain.toByteArray(Charsets.UTF_8), FedFormat.msgAd(chatRef, id, sender, dev.id, ts, replyTo, ai), nonce, key)
        val bodyCt = b64(nonce + ct)
        val sig = b64(c.sign(sigMsg(chatRef, id, sender, dev.id, ts, replyTo, ai, bodyCt), dev.signSk))
        return EncryptedV2(id, ts, bodyCt, sig, recipients.mapValues { (_, pk) -> b64(c.seal(key, pk)) }, replyTo, ai)
    }

    fun decrypt(chatRef: String, id: String, sender: String, device: String, ts: Long, replyTo: String?, ai: String?, bodyCt: String, key: ByteArray): String {
        val raw = unb64(bodyCt) ?: throw ChatCryptoException("body")
        if (raw.size < 24 + 16) throw ChatCryptoException("body")
        val pt = c.aeadDecrypt(raw.copyOfRange(24, raw.size), FedFormat.msgAd(chatRef, id, sender, device, ts, replyTo, ai), raw.copyOfRange(0, 24), key)
        return String(pt, Charsets.UTF_8)
    }

    fun verifyMessage(chatRef: String, id: String, sender: String, device: String, ts: Long, replyTo: String?, ai: String?, bodyCt: String, sig: String?, signPk: ByteArray?): Boolean =
        try {
            c.verify(unb64(sig, 64), sigMsg(chatRef, id, sender, device, ts, replyTo, ai, bodyCt), signPk)
        } catch (_: Exception) {
            false
        }

    // ---------------------------------------------------------------- Anhaenge

    fun encryptFile(chatRef: String, data: ByteArray, key: ByteArray = c.keygen(), nonce: ByteArray = c.random(24)): Pair<ByteArray, ByteArray> =
        (nonce + c.aeadEncrypt(data, FedFormat.fileAd(chatRef), nonce, key)) to key

    fun decryptFile(chatRef: String, blob: ByteArray, key: ByteArray): ByteArray {
        if (blob.size < 24 + 16) throw ChatCryptoException("file")
        return c.aeadDecrypt(blob.copyOfRange(24, blob.size), FedFormat.fileAd(chatRef), blob.copyOfRange(0, 24), key)
    }

    // ---------------------------------------------------------------- Sicherheitsnummer v2

    /** beide (handle, ident_pk) nach Handle (UTF-8-Bytes) sortiert; 12 Bloecke a 5 Ziffern */
    fun safetyNumber(handleA: String, identA: ByteArray, handleB: String, identB: ByteArray): String {
        var a = handleA.toByteArray(Charsets.UTF_8) to identA
        var b = handleB.toByteArray(Charsets.UTF_8) to identB
        if (ChatFormat.compareBytes(a.first, b.first) > 0) a = b.also { b = a }
        return ChatFormat.digitBlocks(c.genericHash(lp(FedFormat.V2 + "safety", a.first, a.second, b.first, b.second), 64), 12)
    }

    // ---------------------------------------------------------------- Anrufe saga-call/v2

    fun signSignal(callId: String, fromHandle: String, dev: DeviceKeys, toHandle: String, toDevice: String, body: String): ByteArray =
        c.sign(FedFormat.signalMsg(callId, fromHandle, dev.id, toHandle, toDevice, body), dev.signSk)

    fun sealSignal(callId: String, fromHandle: String, dev: DeviceKeys, toHandle: String, toDevice: String, toPk: ByteArray, body: String): String {
        val sig = signSignal(callId, fromHandle, dev, toHandle, toDevice, body)
        return b64(c.seal(FedFormat.signalInner(body, sig).toByteArray(Charsets.UTF_8), toPk))
    }

    /** inner.v muss 2 sein */
    fun openSignal(sealed: String, pk: ByteArray, sk: ByteArray): io.github.yggdrasil_sh.saga.call.OpenedSignal {
        val raw = unb64(sealed) ?: throw ChatCryptoException("signal")
        val o = Json.parseToJsonElement(String(c.sealOpen(raw, pk, sk), Charsets.UTF_8)) as? JsonObject ?: throw ChatCryptoException("signal")
        if ((o["v"] as? JsonPrimitive)?.intOrNull != 2) throw ChatCryptoException("signal version")
        val body = (o["body"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw ChatCryptoException("signal")
        val sig = unb64((o["sig"] as? JsonPrimitive)?.content, 64) ?: throw ChatCryptoException("signal")
        return io.github.yggdrasil_sh.saga.call.OpenedSignal(body, sig)
    }

    fun verifySignal(callId: String, fromHandle: String, fromDevice: String, toHandle: String, toDevice: String, body: String, sig: ByteArray, signPk: ByteArray?): Boolean =
        c.verify(sig, FedFormat.signalMsg(callId, fromHandle, fromDevice, toHandle, toDevice, body), signPk)
}
