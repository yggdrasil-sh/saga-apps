package io.github.yggdrasil_sh.saga.chat

import com.goterl.lazysodium.LazySodium
import com.goterl.lazysodium.interfaces.PwHash
import com.sun.jna.NativeLong
import io.github.yggdrasil_sh.saga.chat.ChatFormat.V
import io.github.yggdrasil_sh.saga.chat.ChatFormat.b64
import io.github.yggdrasil_sh.saga.chat.ChatFormat.lp
import io.github.yggdrasil_sh.saga.chat.ChatFormat.unb64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

class ChatCryptoException(msg: String) : Exception(msg)

/** Identitaet eines Kontos: Ed25519 (Signaturen) + X25519-Backup-Schluessel */
class Identity(val identPk: ByteArray, val identSk: ByteArray, val backupPk: ByteArray, val backupSk: ByteArray)

/** Geraet: id (32 Hex), X25519 (enc) + Ed25519 (sign) */
class DeviceKeys(val id: String, val encPk: ByteArray, val encSk: ByteArray, val signPk: ByteArray, val signSk: ByteArray)

/** Ergebnis von encrypt(): Felder fuer POST /api/chats/{id}/messages + Nachrichtenschluessel */
class Encrypted(val id: String, val ts: Long, val bodyCt: String, val sig: String, val keys: Map<String, String>, val replyTo: String?, val key: ByteArray)

/**
 * Chat-Kryptografie Format v1 - ausschliesslich libsodium-Primitive (lazysodium-android), nichts selbst gebaut:
 * crypto_sign (Ed25519), crypto_box_seal (X25519), crypto_pwhash (Argon2id13), crypto_aead_xchacha20poly1305_ietf,
 * crypto_generichash (BLAKE2b), randombytes_buf. Gegenstueck zu static/chat-crypto.js; geprueft mit tests/chat_vectors.json.
 */
class ChatCrypto(private val s: LazySodium) {
    companion object {
        const val OPS = 2L
        const val MEM = 67108864L
        private const val SEAL_OVERHEAD = 48
        private const val ABYTES = 16
    }

    fun random(n: Int): ByteArray = s.randomBytesBuf(n)

    fun hexId(): String = ChatFormat.hex(random(16))

    // ---------------------------------------------------------------- Schluessel

    fun identity(identSeed: ByteArray? = null, backupSeed: ByteArray? = null): Identity {
        val ipk = ByteArray(32)
        val isk = ByteArray(64)
        val bpk = ByteArray(32)
        val bsk = ByteArray(32)
        check(if (identSeed != null) s.cryptoSignSeedKeypair(ipk, isk, identSeed) else s.cryptoSignKeypair(ipk, isk))
        check(if (backupSeed != null) s.cryptoBoxSeedKeypair(bpk, bsk, backupSeed) else s.cryptoBoxKeypair(bpk, bsk))
        return Identity(ipk, isk, bpk, bsk)
    }

    fun device(id: String? = null, encSeed: ByteArray? = null, signSeed: ByteArray? = null): DeviceKeys {
        val epk = ByteArray(32)
        val esk = ByteArray(32)
        val spk = ByteArray(32)
        val ssk = ByteArray(64)
        check(if (encSeed != null) s.cryptoBoxSeedKeypair(epk, esk, encSeed) else s.cryptoBoxKeypair(epk, esk))
        check(if (signSeed != null) s.cryptoSignSeedKeypair(spk, ssk, signSeed) else s.cryptoSignKeypair(spk, ssk))
        return DeviceKeys(id ?: hexId(), epk, esk, spk, ssk)
    }

    private fun check(ok: Boolean) {
        if (!ok) throw ChatCryptoException("libsodium")
    }

    fun sign(msg: ByteArray, sk: ByteArray): ByteArray {
        val sig = ByteArray(64)
        check(s.cryptoSignDetached(sig, msg, msg.size.toLong(), sk))
        return sig
    }

    fun verify(sig: ByteArray?, msg: ByteArray, pk: ByteArray?): Boolean =
        sig != null && pk != null && sig.size == 64 && pk.size == 32 && try {
            s.cryptoSignVerifyDetached(sig, msg, msg.size, pk)
        } catch (_: Exception) {
            false
        }

    fun backupMsg(uid: String, backupPk: ByteArray) = lp(V + "backup", uid, backupPk)

    fun deviceMsg(uid: String, id: String, encPk: ByteArray, signPk: ByteArray) = lp(V + "device", uid, id, encPk, signPk)

    fun backupSig(uid: String, ident: Identity): ByteArray = sign(backupMsg(uid, ident.backupPk), ident.identSk)

    fun deviceSig(uid: String, identSk: ByteArray, d: DeviceKeys): ByteArray = sign(deviceMsg(uid, d.id, d.encPk, d.signPk), identSk)

    /** Geraet aus der API ({id, enc_pub, sign_pub, sig}, B64) gegen den Identitaetsschluessel pruefen */
    fun verifyDevice(uid: String, identPk: ByteArray?, id: String, encPub: String?, signPub: String?, sig: String?): Boolean {
        val e = unb64(encPub, 32) ?: return false
        val sp = unb64(signPub, 32) ?: return false
        return verify(unb64(sig, 64), deviceMsg(uid, id, e, sp), identPk)
    }

    fun verifyBackup(uid: String, identPk: ByteArray?, backupPub: String?, backupSig: String?): Boolean {
        val b = unb64(backupPub, 32) ?: return false
        return verify(unb64(backupSig, 64), backupMsg(uid, b), identPk)
    }

    // ---------------------------------------------------------------- Wiederherstellungsschluessel und Buendel

    /** 20 Zufallsbytes -> Base32 -> 8 x 4 mit "-" */
    fun recoveryKey(raw: ByteArray = random(20)): String = ChatFormat.formatRecovery(ChatFormat.base32(raw))

    fun recoveryKdf(rk: String, salt: ByteArray, ops: Long = OPS, mem: Long = MEM): ByteArray {
        val n = ChatFormat.normRecovery(rk) ?: throw ChatCryptoException("recovery key")
        val pw = n.toByteArray(Charsets.US_ASCII)
        val out = ByteArray(32)
        check(s.cryptoPwHash(out, 32, pw, pw.size, salt, ops, NativeLong(mem), PwHash.Alg.PWHASH_ALG_ARGON2ID13))
        return out
    }

    fun bundlePlain(identSk: ByteArray, backupSk: ByteArray): String =
        "{\"v\":1,\"ident_sk\":\"${b64(identSk)}\",\"backup_sk\":\"${b64(backupSk)}\"}"

    /** Klartext des Buendels -> Identitaet (ident_pk = letzte 32 Bytes von ident_sk, backup_pk = scalarmult_base) */
    fun parsePlain(pt: String): Identity {
        val o = Json.parseToJsonElement(pt) as? JsonObject ?: throw ChatCryptoException("bundle")
        if ((o["v"] as? JsonPrimitive)?.intOrNull != 1) throw ChatCryptoException("bundle version")
        val isk = unb64((o["ident_sk"] as? JsonPrimitive)?.content, 64) ?: throw ChatCryptoException("bundle")
        val bsk = unb64((o["backup_sk"] as? JsonPrimitive)?.content, 32) ?: throw ChatCryptoException("bundle")
        val bpk = ByteArray(32)
        check(s.cryptoScalarMultBase(bpk, bsk))
        return Identity(isk.copyOfRange(32, 64), isk, bpk, bsk)
    }

    fun aeadEncrypt(pt: ByteArray, ad: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray {
        val out = ByteArray(pt.size + ABYTES)
        val len = LongArray(1)
        check(s.cryptoAeadXChaCha20Poly1305IetfEncrypt(out, len, pt, pt.size.toLong(), ad, ad.size.toLong(), null, nonce, key))
        return out.copyOf(len[0].toInt())
    }

    fun aeadDecrypt(ct: ByteArray, ad: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray {
        if (ct.size < ABYTES || nonce.size != 24 || key.size != 32) throw ChatCryptoException("decrypt")
        val out = ByteArray(ct.size - ABYTES)
        val len = LongArray(1)
        if (!s.cryptoAeadXChaCha20Poly1305IetfDecrypt(out, len, null, ct, ct.size.toLong(), ad, ad.size.toLong(), nonce, key)) {
            throw ChatCryptoException("decrypt")
        }
        return out.copyOf(len[0].toInt())
    }

    fun keygen(): ByteArray {
        val k = ByteArray(32)
        s.cryptoAeadXChaCha20Poly1305IetfKeygen(k)
        return k
    }

    /** Buendel verschluesseln -> JSON-Text (Feldreihenfolge wie Web/Python) */
    fun sealBundle(
        uid: String, rk: String, ident: Identity, salt: ByteArray = random(16), nonce: ByteArray = random(24),
        ops: Long = OPS, mem: Long = MEM, plain: String? = null,
    ): String {
        val key = recoveryKdf(rk, salt, ops, mem)
        val ct = aeadEncrypt((plain ?: bundlePlain(ident.identSk, ident.backupSk)).toByteArray(Charsets.UTF_8), lp(V + "bundle", uid), nonce, key)
        return "{\"v\":1,\"kdf\":\"argon2id13\",\"ops\":$ops,\"mem\":$mem,\"salt\":\"${b64(salt)}\",\"nonce\":\"${b64(nonce)}\",\"ct\":\"${b64(ct)}\"}"
    }

    /** Buendel mit dem Wiederherstellungsschluessel oeffnen (ops/mem aus dem Buendel) */
    fun openBundle(uid: String, rk: String, bundle: String): Identity {
        val d = Json.parseToJsonElement(bundle) as? JsonObject ?: throw ChatCryptoException("bundle")
        if ((d["v"] as? JsonPrimitive)?.intOrNull != 1 || (d["kdf"] as? JsonPrimitive)?.content != "argon2id13") throw ChatCryptoException("bundle version")
        val ops = (d["ops"] as? JsonPrimitive)?.longOrNull ?: throw ChatCryptoException("bundle")
        val mem = (d["mem"] as? JsonPrimitive)?.longOrNull ?: throw ChatCryptoException("bundle")
        if (ops !in 1..20 || mem !in (8L shl 20)..(1L shl 30)) throw ChatCryptoException("bundle")
        val salt = unb64((d["salt"] as? JsonPrimitive)?.content, 16) ?: throw ChatCryptoException("bundle")
        val nonce = unb64((d["nonce"] as? JsonPrimitive)?.content, 24) ?: throw ChatCryptoException("bundle")
        val ct = unb64((d["ct"] as? JsonPrimitive)?.content) ?: throw ChatCryptoException("bundle")
        val key = recoveryKdf(rk, salt, ops, mem)
        val pt = aeadDecrypt(ct, lp(V + "bundle", uid), nonce, key)
        return parsePlain(String(pt, Charsets.UTF_8))
    }

    fun bundleUpdateSig(uid: String, bundle: String, identSk: ByteArray): ByteArray = sign(lp(V + "bundle-update", uid, bundle), identSk)

    // ---------------------------------------------------------------- Nachrichten

    fun msgAd(chatId: String, id: String, sender: String, device: String, ts: Long, replyTo: String?): ByteArray =
        lp(V + "msg", chatId, id, sender, device, ts.toString(), replyTo ?: "")

    fun genericHash(data: ByteArray, n: Int = 32): ByteArray {
        val out = ByteArray(n)
        check(s.cryptoGenericHash(out, n, data, data.size.toLong(), null, 0))
        return out
    }

    fun sigMsg(chatId: String, id: String, sender: String, device: String, ts: Long, replyTo: String?, bodyCt: String): ByteArray =
        lp(V + "sig", chatId, id, sender, device, ts.toString(), replyTo ?: "", genericHash(unb64(bodyCt) ?: throw ChatCryptoException("body")))

    fun seal(msg: ByteArray, pk: ByteArray): ByteArray {
        val out = ByteArray(msg.size + SEAL_OVERHEAD)
        check(s.cryptoBoxSeal(out, msg, msg.size.toLong(), pk))
        return out
    }

    fun sealOpen(ct: ByteArray, pk: ByteArray, sk: ByteArray): ByteArray {
        if (ct.size < SEAL_OVERHEAD) throw ChatCryptoException("seal")
        val out = ByteArray(ct.size - SEAL_OVERHEAD)
        if (!s.cryptoBoxSealOpen(out, ct, ct.size.toLong(), pk, sk)) throw ChatCryptoException("seal")
        return out
    }

    /**
     * Nachricht verschluesseln und signieren. recipients: Empfaenger-Kennung (Geraete-ID bzw. "backup:<uid>") -> X25519-Schluessel.
     * plain = Inhalt als JSON-Text.
     */
    fun encrypt(
        chatId: String, sender: String, dev: DeviceKeys, plain: String, recipients: Map<String, ByteArray>, replyTo: String? = null,
        id: String = hexId(), ts: Long = System.currentTimeMillis(), key: ByteArray = keygen(), nonce: ByteArray = random(24),
    ): Encrypted {
        val ct = aeadEncrypt(plain.toByteArray(Charsets.UTF_8), msgAd(chatId, id, sender, dev.id, ts, replyTo), nonce, key)
        val bodyCt = b64(nonce + ct)
        val sig = b64(sign(sigMsg(chatId, id, sender, dev.id, ts, replyTo, bodyCt), dev.signSk))
        val keys = recipients.mapValues { (_, pk) -> b64(seal(key, pk)) }
        return Encrypted(id, ts, bodyCt, sig, keys, replyTo, key)
    }

    /** Versiegelten Nachrichtenschluessel (80 Bytes) mit Geraete- oder Backup-Schluessel oeffnen */
    fun openKey(sealed: String?, pk: ByteArray, sk: ByteArray): ByteArray =
        sealOpen(unb64(sealed, 80) ?: throw ChatCryptoException("key"), pk, sk)

    /** -> Klartext (JSON-Text) */
    fun decrypt(chatId: String, id: String, sender: String, device: String, ts: Long, replyTo: String?, bodyCt: String, key: ByteArray): String {
        val raw = unb64(bodyCt) ?: throw ChatCryptoException("body")
        if (raw.size < 24 + ABYTES) throw ChatCryptoException("body")
        val pt = aeadDecrypt(raw.copyOfRange(24, raw.size), msgAd(chatId, id, sender, device, ts, replyTo), raw.copyOfRange(0, 24), key)
        return String(pt, Charsets.UTF_8)
    }

    fun verifyMessage(chatId: String, id: String, sender: String, device: String, ts: Long, replyTo: String?, bodyCt: String, sig: String?, signPk: ByteArray?): Boolean =
        try {
            verify(unb64(sig, 64), sigMsg(chatId, id, sender, device, ts, replyTo, bodyCt), signPk)
        } catch (_: Exception) {
            false
        }

    // ---------------------------------------------------------------- Anhaenge

    /** Blob = nonce24 || xchacha20poly1305(datei, AD=LP("saga-chat/v1/file", chat_id)) */
    fun encryptFile(chatId: String, data: ByteArray, key: ByteArray = keygen(), nonce: ByteArray = random(24)): Pair<ByteArray, ByteArray> =
        (nonce + aeadEncrypt(data, lp(V + "file", chatId), nonce, key)) to key

    fun decryptFile(chatId: String, blob: ByteArray, key: ByteArray): ByteArray {
        if (blob.size < 24 + ABYTES) throw ChatCryptoException("file")
        return aeadDecrypt(blob.copyOfRange(24, blob.size), lp(V + "file", chatId), blob.copyOfRange(0, 24), key)
    }

    // ---------------------------------------------------------------- Kopplung, Sicherheitsnummer

    /** 20 Ziffern "01234 56789 01234 56789" */
    fun linkCode(uid: String, deviceId: String, encPk: ByteArray, signPk: ByteArray): String =
        ChatFormat.digitBlocks(genericHash(lp(V + "link", uid, deviceId, encPk, signPk), 32), 4)

    /** 60 Ziffern; Reihenfolge nach UTF-8-Bytes der Konten (unabhaengig davon, wer fragt) */
    fun safetyNumber(uidA: String, identA: ByteArray, uidB: String, identB: ByteArray): String {
        var a = uidA.toByteArray(Charsets.UTF_8) to identA
        var b = uidB.toByteArray(Charsets.UTF_8) to identB
        if (ChatFormat.compareBytes(a.first, b.first) > 0) a = b.also { b = a }
        return ChatFormat.digitBlocks(genericHash(lp(V + "safety", a.first, a.second, b.first, b.second), 64), 12)
    }
}
