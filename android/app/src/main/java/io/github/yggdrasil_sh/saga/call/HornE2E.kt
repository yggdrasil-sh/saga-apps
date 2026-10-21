package io.github.yggdrasil_sh.saga.call

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Gjallarhorn (Saga 2.5): Frame-E2E saga-horn/v1, reine Logik ohne WebRTC (Vertrag: docs/HORN-E2E.md im Server-Repo,
 * Vektoren tests/vectors/horn_e2e.json -> app/src/test/resources/vectors/horn_e2e.json, Test HornE2EVectorsTest).
 * Gleiche Logik wie ios/App/HornE2E.swift: Ableitungen (FK, Saga-Ratsche), Rahmenformat (AES-128-GCM, Trailer
 * IV||0x0C||kid), Empfaenger fuer einen Sender mit Schluesselwahl, Ratsche beim ersten Rahmen, Ablauf und
 * Wiederholungsschutz, Runensiegel, fkey-Pruefung, Plan fuer die FrameCryptor-Schluesselplaetze.
 * Nur JDK-Kryptografie (HMAC-SHA256, AES/GCM/NoPadding, SHA-256) - laeuft gleich auf der JVM und auf Android.
 */
object HornE2E {
    val frameSalt: ByteArray = "saga-horn/v1/frame".toByteArray(Charsets.UTF_8)
    val ratchetSalt: ByteArray = "saga-horn-ratchet".toByteArray(Charsets.UTF_8)
    val frameInfo = ByteArray(128)
    const val IV_LEN = 12
    const val TAG_LEN = 16
    const val TRAILER_LEN = 14
    const val RING = 16
    const val WINDOW = 128
    const val AHEAD = 4
    const val KEEP = 3
    const val SWITCH_MS = 1500.0
    const val GRACE_MS = 3000.0

    private val rnd = SecureRandom()

    // ---- Ableitungen --------------------------------------------------------------------------------------------

    /** HKDF-SHA256 (RFC 5869) */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, len: Int): ByteArray {
        require(len in 1..255 * 32)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(if (salt.isEmpty()) ByteArray(32) else salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val out = ByteArray(len)
        var t = ByteArray(0)
        var pos = 0
        var i = 1
        while (pos < len) {
            mac.update(t)
            mac.update(info)
            mac.update(i.toByte())
            t = mac.doFinal()
            val n = minOf(t.size, len - pos)
            System.arraycopy(t, 0, out, pos, n)
            pos += n
            i++
        }
        return out
    }

    /** FK = HKDF-SHA256(K, salt "saga-horn/v1/frame", info 128 x 00, 16) - genau der FrameCryptor-HKDF-Modus */
    fun frameKey(k: ByteArray): ByteArray = hkdf(k, frameSalt, frameInfo, 16)

    /** Saga-Ratsche K' = HKDF-SHA256(K, salt "saga-horn-ratchet", info leer, 32) - NICHT die FrameCryptor-Ratsche */
    fun ratchet(k: ByteArray): ByteArray = hkdf(k, ratchetSalt, ByteArray(0), 32)

    fun ratchet(k: ByteArray, times: Int): ByteArray {
        var r = k
        repeat(maxOf(0, times)) { r = ratchet(r) }
        return r
    }

    // ---- Rahmen -------------------------------------------------------------------------------------------------

    enum class Kind { AUDIO, VIDEO;

        companion object {
            fun of(s: String): Kind = when (s) {
                "audio" -> AUDIO
                "video" -> VIDEO
                else -> throw IllegalArgumentException(s)
            }
        }
    }

    /** Laenge des Klartext-Kopfs (AAD): Opus 1, VP8-Schluesselbild 10, VP8-Zwischenbild 3; null = verwerfen */
    fun headerLen(kind: Kind, type: String?): Int? = when (kind) {
        Kind.AUDIO -> 1
        Kind.VIDEO -> when (type) {
            "key" -> 10
            "delta" -> 3
            else -> null
        }
    }

    /** IV = SSRC || RTP-Zeitstempel || Zaehler (je u32 big-endian) */
    fun iv(ssrc: Long, ts: Long, ctr: Long): ByteArray {
        val d = ByteArray(IV_LEN)
        for ((j, v) in longArrayOf(ssrc, ts, ctr).withIndex()) {
            for (b in 0 until 4) d[j * 4 + b] = (v ushr (24 - 8 * b)).toByte()
        }
        return d
    }

    private fun gcm(mode: Int, fk: ByteArray, iv: ByteArray): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply { init(mode, SecretKeySpec(fk, "AES"), GCMParameterSpec(TAG_LEN * 8, iv)) }

    /** Kopf || Chiffrat || Tag || IV || 0x0C || kid; null, wenn der Rahmen zu kurz ist */
    fun seal(fk: ByteArray, frame: ByteArray, h: Int, kid: Int, iv: ByteArray): ByteArray? {
        if (frame.size < h || iv.size != IV_LEN || kid !in 0 until RING) return null
        return try {
            val c = gcm(Cipher.ENCRYPT_MODE, fk, iv)
            c.updateAAD(frame, 0, h)
            val ct = c.doFinal(frame, h, frame.size - h)              // Chiffrat || Tag
            val out = ByteArray(h + ct.size + TRAILER_LEN)
            System.arraycopy(frame, 0, out, 0, h)
            System.arraycopy(ct, 0, out, h, ct.size)
            System.arraycopy(iv, 0, out, h + ct.size, IV_LEN)
            out[out.size - 2] = IV_LEN.toByte()
            out[out.size - 1] = kid.toByte()
            out
        } catch (_: Exception) {
            null
        }
    }

    enum class Drop(val code: String) { CODEC("codec"), SHORT("short"), FORMAT("format"), NOKEY("nokey"), REPLAY("replay"), AUTH("auth") }

    data class Parsed(val kid: Int, val ivAt: Int, val ssrc: Long, val ts: Long)

    /** Ergebnis: Daten oder Grund zum Verwerfen */
    sealed class Res<out T> {
        data class Ok<T>(val value: T) : Res<T>()
        data class Fail(val drop: Drop) : Res<Nothing>()
    }

    private fun u32(b: ByteArray, i: Int): Long =
        ((b[i].toLong() and 0xFF) shl 24) or ((b[i + 1].toLong() and 0xFF) shl 16) or ((b[i + 2].toLong() and 0xFF) shl 8) or
            (b[i + 3].toLong() and 0xFF)

    fun parse(d: ByteArray, h: Int?): Res<Parsed> {
        if (h == null) return Res.Fail(Drop.CODEC)
        if (d.size < h + TAG_LEN + TRAILER_LEN) return Res.Fail(Drop.SHORT)
        val kid = d[d.size - 1].toInt() and 0xFF
        if ((d[d.size - 2].toInt() and 0xFF) != IV_LEN || kid >= RING) return Res.Fail(Drop.FORMAT)
        val at = d.size - TRAILER_LEN
        return Res.Ok(Parsed(kid, at, u32(d, at), u32(d, at + 4)))
    }

    fun open(fk: ByteArray, d: ByteArray, h: Int, p: Parsed): ByteArray? {
        if (p.ivAt - h < TAG_LEN) return null
        return try {
            val c = gcm(Cipher.DECRYPT_MODE, fk, d.copyOfRange(p.ivAt, p.ivAt + IV_LEN))
            c.updateAAD(d, 0, h)
            val plain = c.doFinal(d, h, p.ivAt - h)
            d.copyOfRange(0, h) + plain
        } catch (_: Exception) {
            null
        }
    }

    // ---- Wiederholungsschutz (Fenster 128 ueber den RTP-Zeitstempel der IV, serielle Arithmetik mod 2^32) --------

    class ReplayWindow(val size: Int = WINDOW) {
        private val vals = ArrayDeque<Long>()
        var max: Long? = null
            private set

        /** > 0 neuer, < 0 aelter, 0 gleich */
        private fun rel(t: Long, m: Long): Long {
            val d = (t - m) and 0xFFFF_FFFFL
            return if (d == 0L) 0 else if (d < 0x8000_0000L) d else d - 0x1_0000_0000L
        }

        fun check(t: Long): Boolean {
            val m = max ?: return true
            val r = rel(t, m)
            if (r > 0) return true
            if (r == 0L || vals.contains(t)) return false
            if (vals.size < size) return true
            val oldest = vals.maxOf { (m - it) and 0xFFFF_FFFFL }
            return -r < oldest
        }

        fun accept(t: Long) {
            val m = max
            if (m == null || rel(t, m) > 0) max = t
            vals.addLast(t)
            if (vals.size > size) vals.removeFirst()
        }
    }

    // ---- Senden (eigener Transformer) -----------------------------------------------------------------------------

    /** Zaehler je (Schluessel, SSRC) ab 0, nie wiederholt */
    class Sender(n: Int, key: ByteArray) {
        var n: Int = n
            private set
        var key: ByteArray = key
            private set
        private var fk = frameKey(key)
        private val ctr = HashMap<Long, Long>()

        fun use(n: Int, key: ByteArray) {
            this.n = n
            this.key = key
            fk = frameKey(key)
            ctr.clear()
        }

        fun encrypt(frame: ByteArray, kind: Kind, type: String?, ssrc: Long, ts: Long): ByteArray? {
            val h = headerLen(kind, type) ?: return null
            if (frame.isEmpty() || frame.size < h) return null
            val c = ctr[ssrc] ?: 0L
            if (c >= 0xFFFF_FFFFL) return null                      // Zaehler erschoepft: neuer Schluessel noetig
            ctr[ssrc] = c + 1
            return seal(fk, frame, h, n % RING, iv(ssrc, ts, c))
        }
    }

    // ---- Empfangen (eigener Transformer), EIN Sender ----------------------------------------------------------------

    class Receiver(val session: String) {
        private class Gen(val key: ByteArray, val fk: ByteArray) {
            var until = Double.POSITIVE_INFINITY
            val win = HashMap<Long, ReplayWindow>()
        }

        private var keys = HashMap<Int, Gen>()
        private val cand = HashMap<Int, Gen>()
        var newest = -1
            private set
        var active = -1
            private set

        fun add(n: Int, key: ByteArray) {
            keys = HashMap(keys.filter { it.key == n || it.key % RING != n % RING })
            keys[n] = Gen(key, frameKey(key))
            cand.remove(n)
            newest = maxOf(newest, n)
        }

        val held: List<Int> get() = keys.keys.sorted()

        private fun trim(now: Double) {
            keys = HashMap(keys.filter { it.key >= newest - KEEP + 1 && it.value.until > now })
        }

        fun decrypt(d: ByteArray, kind: Kind, type: String?, now: Double): Res<ByteArray> {
            val h = headerLen(kind, type)
            val p = when (val r = parse(d, h)) {
                is Res.Fail -> return r
                is Res.Ok -> r.value
            }
            if (h == null || newest < 0) return Res.Fail(Drop.NOKEY)
            trim(now)
            val kn: Int
            val g: Gen
            var isCand = false
            val hit = keys.entries.firstOrNull { it.key % RING == p.kid }
            if (hit != null) {
                kn = hit.key
                g = hit.value
            } else {
                val j = ((p.kid - newest % RING) % RING + RING) % RING
                val base = keys[newest]
                if (j !in 1..AHEAD || base == null) return Res.Fail(Drop.NOKEY)
                kn = newest + j
                g = cand.getOrPut(kn) { val k = ratchet(base.key, j); Gen(k, frameKey(k)) }
                isCand = true
            }
            val w = g.win[p.ssrc]
            if (w != null && !w.check(p.ts)) return Res.Fail(Drop.REPLAY)
            val out = open(g.fk, d, h, p) ?: return Res.Fail(Drop.AUTH)
            g.win.getOrPut(p.ssrc) { ReplayWindow() }.accept(p.ts)
            if (isCand) {
                cand.remove(kn)
                newest = maxOf(newest, kn)
            }
            keys[kn] = g
            if (kn > active) {
                active = kn
                for ((m, x) in keys) if (m < kn && x.until == Double.POSITIVE_INFINITY) x.until = now + GRACE_MS
            }
            return Res.Ok(out)
        }
    }

    // ---- Runensiegel ------------------------------------------------------------------------------------------------

    const val RUNES = "ᚠᚢᚦᚨᚱᚲᚷᚹᚺᚾᛁᛃᛇᛈᛉᛊᛏᛒᛖᛗᛚᛜᛞᛟ"
    val runeNames = listOf(
        "fehu", "uruz", "thurisaz", "ansuz", "raidho", "kaunan", "gebo", "wunjo", "hagalaz", "naudiz",
        "isa", "jera", "eihwaz", "perthro", "algiz", "sowilo", "tiwaz", "berkanan", "ehwaz", "mannaz",
        "laguz", "ingwaz", "dagaz", "othala",
    )

    data class RuneSeal(val hash: ByteArray, val idx: List<Int>, val runes: String)

    private fun cmpBytes(a: ByteArray, b: ByteArray): Int {
        for (i in 0 until minOf(a.size, b.size)) {
            val c = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (c != 0) return c
        }
        return a.size - b.size
    }

    /** H = SHA-256(call_id || sortierte, eindeutige sign_pk); 40 Bit -> 6 Runen (Basis 24, r_0 zuerst) */
    fun runeSeal(callId: String, signPks: List<ByteArray>): RuneSeal {
        val pks = signPks.distinctBy { it.toList() }.sortedWith(::cmpBytes)
        val md = MessageDigest.getInstance("SHA-256")
        md.update(callId.toByteArray(Charsets.UTF_8))
        pks.forEach { md.update(it) }
        val hash = md.digest()
        var v = 0L
        for (i in 0 until 5) v = (v shl 8) or (hash[i].toLong() and 0xFF)
        val idx = ArrayList<Int>(6)
        repeat(6) {
            idx.add((v % 24).toInt())
            v /= 24
        }
        val cps = RUNES.codePoints().toArray()
        val sb = StringBuilder()
        idx.forEach { sb.appendCodePoint(cps[it]) }
        return RuneSeal(hash, idx, sb.toString())
    }

    // ---- fkey ------------------------------------------------------------------------------------------------------

    data class FKey(val n: Int, val key: ByteArray, val s: String)

    private fun JsonElement?.strictInt(): Long? {
        val p = this as? JsonPrimitive ?: return null
        if (p.isString) return null
        val c = p.content
        if (c.isEmpty() || c.any { it == '.' || it == 'e' || it == 'E' }) return null
        return c.toLongOrNull()
    }

    /** {"t":"fkey","n","kid","key"(Base64, 32 Byte),"s"(16 hex klein)}; null = still ignorieren */
    fun parseFKey(o: JsonElement?): FKey? {
        val b = o as? JsonObject ?: return null
        val t = b["t"] as? JsonPrimitive
        if (t == null || !t.isString || t.content != "fkey") return null
        val n = b["n"].strictInt() ?: return null
        val kid = b["kid"].strictInt() ?: return null
        val sp = b["s"] as? JsonPrimitive
        val kp = b["key"] as? JsonPrimitive
        if (sp == null || !sp.isString || kp == null || !kp.isString) return null
        val s = sp.content
        val ks = kp.content
        if (n !in 0..0x7FFF_FFFFL || kid != n % RING || s.length != 16 || s.any { it !in "0123456789abcdef" }) return null
        val key = try {
            Base64.getDecoder().decode(ks)
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (key.size != 32 || Base64.getEncoder().encodeToString(key) != ks) return null   // Standard, mit Padding
        return FKey(n.toInt(), key, s)
    }

    // ---- FrameCryptor-Plan (Apps, docs/HORN-E2E.md §6) ------------------------------------------------------------

    data class Slot(val n: Int, val kid: Int, val key: ByteArray)

    /**
     * Schluesselplaetze, die die App am FrameCryptor-KeyProvider fuer einen Sender vorab setzt: die gehaltene neueste
     * Generation n und die Ratschenstufen n+1 ... n+4 (FrameCryptor kann die Saga-Ratsche beim ersten Rahmen nicht).
     */
    fun cryptorSlots(newest: Int, key: ByteArray): List<Slot> {
        val out = arrayListOf(Slot(newest, newest % RING, key))
        var k = key
        for (j in 1..AHEAD) {
            k = ratchet(k)
            out.add(Slot(newest + j, (newest + j) % RING, k))
        }
        return out
    }

    /** FrameCryptor kann Schluessel nicht loeschen: abgelaufene Plaetze mit Zufall ueberschreiben */
    fun scrubKey(): ByteArray = ByteArray(32).also { rnd.nextBytes(it) }
}
