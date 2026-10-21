package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.call.HornCryptor
import io.github.yggdrasil_sh.saga.call.HornE2E
import io.github.yggdrasil_sh.saga.call.HornE2E.Kind
import io.github.yggdrasil_sh.saga.call.HornE2E.Res
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.webrtc.RTCStats
import org.webrtc.RTCStatsReport
import java.security.MessageDigest

/**
 * Gjallarhorn (Saga 2.5): saga-horn/v1 gegen tests/vectors/horn_e2e.json (Server-Repo, Kopie unter
 * test/resources/vectors, gleicher SHA-256). Bitgenau mit der JDK-Kryptografie (HornE2E.kt, eigener Saga-Transformer):
 * Ableitungen, Rahmen, Empfaenger-Faelle, Fenster, Runensiegel, fkey, Plan der FrameCryptor-Plaetze.
 * FrameCryptor selbst (native) prueft HornLoopbackTest auf Geraet/Emulator.
 */
class HornE2EVectorsTest {
    companion object {
        /** SHA-256 von tests/vectors/horn_e2e.json im Server-Repo (Stand Gjallarhorn P4) */
        const val SOURCE_SHA256 = "ace680c47a284611c8d105d05640934a2725afdbc4daa050df11d4f2d8b9d36d"
        const val SID = "aaaaaaaaaaaaaaaa"

        private val raw: ByteArray by lazy {
            HornE2EVectorsTest::class.java.classLoader!!.getResourceAsStream("vectors/horn_e2e.json")!!.use { it.readBytes() }
        }
        val v: JsonObject by lazy { Json.parseToJsonElement(raw.toString(Charsets.UTF_8)).jsonObject }
        val keys: List<JsonObject> by lazy { v["keys"]!!.jsonArray.map { it.jsonObject } }
        fun key(n: Int): ByteArray = hex(keys.first { it.i("n") == n }.s("K"))

        fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
        fun hexs(d: ByteArray): String = d.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content
        fun JsonObject.i(k: String) = this[k]!!.jsonPrimitive.int
        fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
    }

    @Test
    fun copyMatchesSource() {
        val sha = hexs(MessageDigest.getInstance("SHA-256").digest(raw))
        assertEquals("Vektoren muessen bytegleich mit tests/vectors/horn_e2e.json sein", SOURCE_SHA256, sha)
    }

    @Test
    fun format() {
        assertEquals("saga-horn/v1", v.s("format"))
        val p = v["params"]!!.jsonObject
        assertEquals("saga-horn/v1/frame", p.s("frame_salt"))
        assertEquals("saga-horn-ratchet", p.s("ratchet_salt"))
        assertEquals(HornE2E.IV_LEN, p.i("iv_len"))
        assertEquals(HornE2E.TAG_LEN, p.i("tag_len"))
        assertEquals(HornE2E.TRAILER_LEN, p.i("trailer_len"))
        assertEquals(HornE2E.RING, p.i("kid_ring"))
        assertEquals(HornE2E.WINDOW, p.i("window"))
        assertEquals(HornE2E.AHEAD, p.i("ratchet_ahead"))
        assertEquals(HornE2E.KEEP, p.i("keep"))
        assertEquals(HornE2E.SWITCH_MS, p["switch_ms"]!!.jsonPrimitive.double, 0.0)
        assertEquals(HornE2E.GRACE_MS, p["grace_ms"]!!.jsonPrimitive.double, 0.0)
        val h = p["header"]!!.jsonObject
        assertEquals(HornE2E.headerLen(Kind.AUDIO, null), h.i("opus"))
        assertEquals(HornE2E.headerLen(Kind.VIDEO, "key"), h.i("vp8_key"))
        assertEquals(HornE2E.headerLen(Kind.VIDEO, "delta"), h.i("vp8_delta"))
        assertNull(HornE2E.headerLen(Kind.VIDEO, "empty"))
        assertNull(HornE2E.headerLen(Kind.VIDEO, null))
    }

    // ---- Ableitungen ------------------------------------------------------------------------------------------------

    @Test
    fun keysAndRatchet() {
        assertEquals(3, keys.size)
        for ((i, k) in keys.withIndex()) {
            val kk = hex(k.s("K"))
            assertEquals("FK n=${k.i("n")}", k.s("fk"), hexs(HornE2E.frameKey(kk)))
            assertEquals(k.i("n") % HornE2E.RING, k.i("kid"))
            if (i + 1 < keys.size) assertEquals("Ratsche n=$i", keys[i + 1].s("K"), hexs(HornE2E.ratchet(kk)))
        }
        assertArrayEquals(key(2), HornE2E.ratchet(key(0), 2))
        assertArrayEquals(key(0), HornE2E.ratchet(key(0), 0))
    }

    /** RFC 5869 A.1 als Gegenprobe der eigenen HKDF */
    @Test
    fun hkdfRfc5869() {
        val okm = HornE2E.hkdf(hex("0b".repeat(22)), hex("000102030405060708090a0b0c"), hex("f0f1f2f3f4f5f6f7f8f9"), 42)
        assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865", hexs(okm))
    }

    /**
     * Was FrameCryptor im HKDF-Modus fuer ratchetKey liefert (HKDF(K, ratchetSalt, 128 x 00, 32), Befund Apple M150):
     * die ersten 16 Byte sind das FK - deshalb ist FrameCryptors Ratsche fuer Saga verboten (§1).
     */
    @Test
    fun frameCryptorRatchetWouldLeakFk() {
        for (k in keys) {
            val kk = hex(k.s("K"))
            val fcRatchet = HornE2E.hkdf(kk, HornE2E.frameSalt, HornE2E.frameInfo, 32)
            assertEquals(k.s("fk"), hexs(fcRatchet.copyOf(16)))
            assertFalse(fcRatchet.contentEquals(HornE2E.ratchet(kk)))
        }
    }

    @Test
    fun cryptorSlots() {
        val s = HornE2E.cryptorSlots(0, key(0))
        assertEquals(listOf(0, 1, 2, 3, 4), s.map { it.n })
        assertEquals(listOf(0, 1, 2, 3, 4), s.map { it.kid })
        assertArrayEquals(key(0), s[0].key)
        assertArrayEquals(key(1), s[1].key)
        assertArrayEquals(key(2), s[2].key)
        val w = HornE2E.cryptorSlots(14, key(0))
        assertEquals("Ring 16 laeuft ueber", listOf(14, 15, 0, 1, 2), w.map { it.kid })
        assertEquals(32, HornE2E.scrubKey().size)
        assertFalse(HornE2E.scrubKey().contentEquals(HornE2E.scrubKey()))
    }

    // ---- Rahmenformat -----------------------------------------------------------------------------------------------

    @Test
    fun framesOpenAndSealBitExact() {
        val frames = v["frames"]!!.jsonArray.map { it.jsonObject }
        assertEquals(5, frames.size)
        var fc = 0
        for (f in frames) {
            val name = f.s("name")
            val kind = Kind.of(f.s("kind"))
            val type = f.str("type")
            val n = f.i("key_n")
            val fk = HornE2E.frameKey(key(n))
            val plain = hex(f.s("plain"))
            val out = hex(f.s("out"))
            val iv = hex(f.s("iv"))
            val h = f.i("h")
            assertEquals(name, HornE2E.headerLen(kind, type), h)
            assertEquals("$name: 30 Byte Zusatz", plain.size + 30, out.size)
            assertEquals("$name: Trailer", listOf(0x0C.toByte(), (n % 16).toByte()), out.takeLast(2))
            assertArrayEquals("$name: IV im Trailer", iv, out.copyOfRange(out.size - 14, out.size - 2))
            val p = (HornE2E.parse(out, h) as Res.Ok).value
            assertEquals(f["ssrc"]!!.jsonPrimitive.long, p.ssrc)
            assertEquals(f["ts"]!!.jsonPrimitive.long, p.ts)
            assertEquals(n % 16, p.kid)
            assertArrayEquals("$name: oeffnen", plain, HornE2E.open(fk, out, h, p))
            if (f.str("iv_mode") != "fc") {
                val ivc = HornE2E.iv(f["ssrc"]!!.jsonPrimitive.long, f["ts"]!!.jsonPrimitive.long, f["ctr"]!!.jsonPrimitive.long)
                assertArrayEquals("$name: IV", iv, ivc)
                assertArrayEquals("$name: bitgleich", out, HornE2E.seal(fk, plain, h, n % 16, iv))
            } else {
                fc++
            }
        }
        assertEquals("ein Rahmen mit FrameCryptor-IV", 1, fc)
    }

    @Test
    fun senderCounterAndRoundtrip() {
        val s = HornE2E.Sender(1, key(1))
        val f = hex("310c00") + ByteArray(50) { 7 }
        val a = s.encrypt(f, Kind.VIDEO, "delta", 5, 900)!!
        val b = s.encrypt(f, Kind.VIDEO, "delta", 5, 3900)!!
        assertArrayEquals(HornE2E.iv(5, 900, 0), a.copyOfRange(a.size - 14, a.size - 2))
        assertArrayEquals(HornE2E.iv(5, 3900, 1), b.copyOfRange(b.size - 14, b.size - 2))
        assertEquals(1.toByte(), a.last())
        assertNull("leer: verwerfen", s.encrypt(ByteArray(0), Kind.VIDEO, "delta", 5, 1))
        assertNull("type empty: verwerfen", s.encrypt(f, Kind.VIDEO, "empty", 5, 1))
        assertNull("kuerzer als Kopf: verwerfen", s.encrypt(hex("310c"), Kind.VIDEO, "delta", 5, 1))
        val r = HornE2E.Receiver(SID)
        r.add(1, key(1))
        assertArrayEquals(f, (r.decrypt(a, Kind.VIDEO, "delta", 0.0) as Res.Ok).value)
        assertArrayEquals(f, (r.decrypt(b, Kind.VIDEO, "delta", 0.0) as Res.Ok).value)
        assertEquals(Res.Fail(HornE2E.Drop.REPLAY), r.decrypt(a, Kind.VIDEO, "delta", 0.0))
        // neuer Schluessel: Zaehler beginnt neu
        s.use(2, key(2))
        val c = s.encrypt(f, Kind.VIDEO, "delta", 5, 6900)!!
        assertArrayEquals(HornE2E.iv(5, 6900, 0), c.copyOfRange(c.size - 14, c.size - 2))
        assertEquals(2.toByte(), c.last())
    }

    @Test
    fun receiverCases() {
        val cases = v["cases"]!!.jsonArray.map { it.jsonObject }
        assertEquals(16, cases.size)
        var steps = 0
        val seen = HashSet<String>()
        for (c in cases) {
            val name = c.s("name")
            val r = HornE2E.Receiver(SID)
            for (n in c["have"]!!.jsonArray) r.add(n.jsonPrimitive.int, key(n.jsonPrimitive.int))
            for ((i, s) in c["steps"]!!.jsonArray.map { it.jsonObject }.withIndex()) {
                steps++
                val want = s.s("expect")
                seen += want
                when (val res = r.decrypt(hex(s.s("data")), Kind.of(s.s("kind")), s.str("type"), s["at"]!!.jsonPrimitive.double)) {
                    is Res.Ok -> {
                        assertEquals("$name#$i", want, "ok")
                        assertEquals("$name#$i: Klartext", s.s("plain"), hexs(res.value))
                    }
                    is Res.Fail -> assertEquals("$name#$i", want, res.drop.code)
                }
            }
        }
        assertTrue(steps > 30)
        assertEquals(setOf("ok", "nokey", "auth", "replay", "short", "format"), seen)
    }

    @Test
    fun replayWindow() {
        val w = v["window"]!!.jsonObject
        assertEquals(128, w.i("size"))
        val win = HornE2E.ReplayWindow(w.i("size"))
        val steps = w["steps"]!!.jsonArray.map { it.jsonObject }
        assertTrue(steps.size > 130)
        for ((i, s) in steps.withIndex()) {
            val t = s["ts"]!!.jsonPrimitive.long
            val ok = win.check(t)
            assertEquals("Fenster #$i ts=$t", s["ok"]!!.jsonPrimitive.boolean, ok)
            if (ok) win.accept(t)
        }
    }

    @Test
    fun runes() {
        assertEquals(24, HornE2E.RUNES.codePointCount(0, HornE2E.RUNES.length))
        assertEquals(24, HornE2E.runeNames.size)
        assertEquals(v.s("rune_alphabet"), HornE2E.RUNES)
        val rs = v["runes"]!!.jsonArray.map { it.jsonObject }
        assertEquals(3, rs.size)
        for (r in rs) {
            val s = HornE2E.runeSeal(r.s("call_id"), r["devices"]!!.jsonArray.map { hex(it.jsonPrimitive.content) })
            assertEquals(r.s("name"), r.s("hash"), hexs(s.hash))
            assertEquals(r["idx"]!!.jsonArray.map { it.jsonPrimitive.int }, s.idx)
            assertEquals(r.s("runes"), s.runes)
        }
        assertEquals(rs[0].s("runes"), rs[2].s("runes"))
        assertNotEquals(rs[0].s("hash"), rs[1].s("hash"))
    }

    @Test
    fun fkey() {
        val f = v["fkey"]!!.jsonObject
        val k = HornE2E.parseFKey(Json.parseToJsonElement(f.s("body")))
        assertNotNull(k)
        assertEquals(f.i("n"), k!!.n)
        assertEquals(f.s("key_hex"), hexs(k.key))
        assertEquals("0123456789abcdef", k.s)
        val bad = f["invalid"]!!.jsonArray
        assertEquals(8, bad.size)
        // ueber JSON-Text, damit Zahlentypen wie im Signal ankommen (1.5 bleibt Gleitkomma)
        for ((i, b) in bad.withIndex()) assertNull("ungueltig #$i", HornE2E.parseFKey(Json.parseToJsonElement(b.toString())))
        val body = Json.parseToJsonElement(f.s("body")).jsonObject
        fun with(k2: String, e: JsonElement) = JsonObject(body + (k2 to e))
        assertNull("n als Wahrheitswert", HornE2E.parseFKey(with("n", JsonPrimitive(true))))
        assertNull("n als Text", HornE2E.parseFKey(with("n", JsonPrimitive("1"))))
        assertNull("n zu gross", HornE2E.parseFKey(Json.parseToJsonElement(f.s("body").replace("\"n\":1", "\"n\":2147483665"))))
        assertNull("kein Objekt", HornE2E.parseFKey(JsonArray(listOf(body))))
        assertNull("1e0", HornE2E.parseFKey(Json.parseToJsonElement(f.s("body").replace("\"n\":1", "\"n\":1e0"))))
    }

    /** "need" aus getStats: Bytes kommen an, framesReceived bleibt 0 (FrameCryptor meldet mit failureTolerance -1 nichts) */
    @Test
    fun unreadableFromStats() {
        fun rep(vararg st: RTCStats) = RTCStatsReport(0, st.associateBy { it.id })
        fun inb(id: String, kind: String, bytes: Long, frames: Long, track: String = "t1") = RTCStats(
            0, "inbound-rtp", id,
            mapOf("kind" to kind, "bytesReceived" to java.math.BigInteger.valueOf(bytes), "framesReceived" to frames, "trackIdentifier" to track),
        )
        assertTrue(HornCryptor.unreadable(rep(inb("a", "video", 20_000, 0))))
        assertFalse(HornCryptor.unreadable(rep(inb("a", "video", 20_000, 12))))
        assertFalse("noch nichts angekommen", HornCryptor.unreadable(rep(inb("a", "video", 0, 0))))
        assertFalse("Ton zaehlt nicht", HornCryptor.unreadable(rep(inb("a", "audio", 20_000, 0))))
        assertFalse(HornCryptor.unreadable(rep()))
        val two = rep(inb("a", "video", 20_000, 0, "x"), inb("b", "video", 20_000, 30, "y"))
        assertTrue(HornCryptor.unreadable(two, setOf("x")))
        assertFalse(HornCryptor.unreadable(two, setOf("y")))
    }
}
