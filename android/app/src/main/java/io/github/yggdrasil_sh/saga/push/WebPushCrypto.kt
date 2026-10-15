package io.github.yggdrasil_sh.saga.push

import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Schluessel dieses Geraets fuer verschluesselte Mitteilungen (Web Push, RFC 8291) */
class PushKeys(val privatePkcs8: ByteArray, val publicRaw: ByteArray, val auth: ByteArray) {
    /** p256dh/auth fuer POST /api/push/subscriptions (base64url ohne Padding) */
    val p256dh: String get() = WebPushCrypto.b64u(publicRaw)
    val authB64: String get() = WebPushCrypto.b64u(auth)
}

/**
 * Entschluesseln von Mitteilungen nach RFC 8291 (Message Encryption for Web Push, Content-Encoding aes128gcm, RFC 8188) -
 * nur Plattform-Kryptografie (JCA: ECDH P-256, HMAC-SHA-256/HKDF, AES-GCM), keine zusaetzliche Bibliothek.
 * Gegenstueck zu saga/push.py encrypt().
 */
object WebPushCrypto {
    fun b64u(b: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    fun unb64u(s: String): ByteArray = Base64.getUrlDecoder().decode(s.trim().trimEnd('='))

    private fun params(): ECParameterSpec {
        val p = AlgorithmParameters.getInstance("EC")
        p.init(ECGenParameterSpec("secp256r1"))
        return p.getParameterSpec(ECParameterSpec::class.java)
    }

    /** neues Schluesselpaar + 16 Byte Auth-Geheimnis */
    fun generate(random: SecureRandom = SecureRandom()): PushKeys {
        val g = KeyPairGenerator.getInstance("EC")
        g.initialize(ECGenParameterSpec("secp256r1"), random)
        val kp = g.generateKeyPair()
        val auth = ByteArray(16).also { random.nextBytes(it) }
        return PushKeys(kp.private.encoded, raw(kp.public as ECPublicKey), auth)
    }

    /** Privater Schluessel aus der Zahl d (Testvektoren) */
    fun fromScalar(d: ByteArray, publicRaw: ByteArray, auth: ByteArray): PushKeys {
        val k = KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(BigInteger(1, d), params()))
        return PushKeys(k.encoded, publicRaw, auth)
    }

    private fun fixed32(v: BigInteger): ByteArray {
        val b = v.toByteArray()
        return when {
            b.size == 32 -> b
            b.size > 32 -> b.copyOfRange(b.size - 32, b.size)
            else -> ByteArray(32 - b.size) + b
        }
    }

    /** unkomprimierter Punkt 0x04 || X || Y (65 Byte) */
    fun raw(k: ECPublicKey): ByteArray = byteArrayOf(4) + fixed32(k.w.affineX) + fixed32(k.w.affineY)

    private fun publicFromRaw(raw: ByteArray): java.security.PublicKey {
        require(raw.size == 65 && raw[0] == 4.toByte()) { "public key" }
        val x = BigInteger(1, raw.copyOfRange(1, 33))
        val y = BigInteger(1, raw.copyOfRange(33, 65))
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), params()))
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val m = Mac.getInstance("HmacSHA256")
        m.init(SecretKeySpec(key, "HmacSHA256"))
        return m.doFinal(data)
    }

    /** HKDF (RFC 5869) mit SHA-256, Laenge <= 32 */
    private fun hkdf(salt: ByteArray, ikm: ByteArray, info: ByteArray, len: Int): ByteArray =
        hmac(hmac(salt, ikm), info + byteArrayOf(1)).copyOf(len)

    /** Koerper (Kopf + ein Datensatz) -> Klartext */
    fun decrypt(body: ByteArray, keys: PushKeys): ByteArray {
        require(body.size > 21 + 65 + 16) { "short" }
        val bb = ByteBuffer.wrap(body)
        val salt = ByteArray(16).also { bb.get(it) }
        val rs = bb.int
        val idlen = bb.get().toInt() and 0xff
        require(idlen == 65 && rs >= 18) { "header" }
        val asPub = ByteArray(idlen).also { bb.get(it) }
        val ct = ByteArray(bb.remaining()).also { bb.get(it) }
        require(ct.size <= rs) { "records" }                       // Saga schickt genau einen Datensatz

        val priv: PrivateKey = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(keys.privatePkcs8))
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(priv)
        ka.doPhase(publicFromRaw(asPub), true)
        val shared = ka.generateSecret()
        val info = "WebPush: info".toByteArray() + byteArrayOf(0) + keys.publicRaw + asPub
        val ikm = hkdf(keys.auth, shared, info, 32)
        val cek = hkdf(salt, ikm, "Content-Encoding: aes128gcm".toByteArray() + byteArrayOf(0), 16)
        val nonce = hkdf(salt, ikm, "Content-Encoding: nonce".toByteArray() + byteArrayOf(0), 12)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(cek, "AES"), GCMParameterSpec(128, nonce))
        val pt = c.doFinal(ct)
        // Auffuellung: Nullen am Ende, davor der Trenner 0x02 (letzter Datensatz)
        var i = pt.size - 1
        while (i >= 0 && pt[i] == 0.toByte()) i--
        require(i >= 0 && pt[i] == 2.toByte()) { "padding" }
        return pt.copyOf(i)
    }
}
