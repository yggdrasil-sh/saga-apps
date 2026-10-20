package io.github.yggdrasil_sh.saga

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import io.github.yggdrasil_sh.saga.chat.ChatCrypto
import io.github.yggdrasil_sh.saga.chat.ChatCryptoException
import io.github.yggdrasil_sh.saga.chat.ChatFormat
import io.github.yggdrasil_sh.saga.chat.ChatFormat.hex
import io.github.yggdrasil_sh.saga.chat.ChatFormat.unb64
import io.github.yggdrasil_sh.saga.chat.ChatFormat.unhex
import io.github.yggdrasil_sh.saga.chat.FileV2
import io.github.yggdrasil_sh.saga.chat.FileV2Reader
import io.github.yggdrasil_sh.saga.chat.FileV2Writer
import io.github.yggdrasil_sh.saga.chat.PlainSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/** Klartext der Testvektoren: plain[k] = k mod 251 (ohne die Datei im Speicher zu halten) */
class GenSource(override val size: Long) : PlainSource {
    override fun readFully(pos: Long, buf: ByteArray, len: Int) {
        var v = (pos % 251).toInt()
        for (k in 0 until len) {
            buf[k] = v.toByte()
            if (++v == 251) v = 0
        }
    }
}

/**
 * Pflicht (docs/APP-API.md "Format saga-file/v2"): tests/vectors/file_v2.json vollstaendig - Kopf, AD/Nonce des ersten und
 * letzten Blocks, Blockanfaenge/-enden, SHA-256 des Blobs, Entschluesseln, reject (muss scheitern) und ranges.
 * Mit der echten libsodium der JVM (lazysodium-java).
 */
class FileV2VectorsTest {
    private val crypto = ChatCrypto(LazySodiumJava(SodiumJava()))
    private val v: JsonObject = Json.parseToJsonElement(
        javaClass.classLoader!!.getResourceAsStream("file_v2.json")!!.bufferedReader().readText(),
    ).jsonObject

    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content
    private fun JsonObject.l(k: String) = this[k]!!.jsonPrimitive.long
    private fun JsonObject.i(k: String) = this[k]!!.jsonPrimitive.int

    private fun blob(c: JsonObject): ByteArray {
        val w = FileV2Writer(crypto, unb64(c.s("key"), 32)!!, unhex(c.s("prefix_hex")), GenSource(c.l("size")), c.i("cs"))
        val out = ByteArrayOutputStream()
        w.write(0, w.blobSize, out)
        return out.toByteArray()
    }

    private fun sha(b: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(b))

    private fun plain(n: Long) = ByteArray(n.toInt()).also { GenSource(n).readFully(0, it, n.toInt()) }

    @Test
    fun formatAndLp() {
        assertEquals("saga-file/v2", v.s("format"))
        assertEquals(FileV2.CS.toLong(), v.l("cs"))
        assertEquals(FileV2.HEADER.toLong(), v.l("header_len"))
        assertEquals(FileV2.TAG.toLong(), v.l("tag_len"))
        val lp = v["lp_example"]!!.jsonObject
        val f = lp["fields_hex"]!!.jsonArray.map { unhex(it.jsonPrimitive.content) }
        assertEquals(lp.s("hex"), hex(ChatFormat.lp(*f.toTypedArray())))
    }

    @Test
    fun cases() {
        val cases = v["cases"]!!.jsonObject
        assertEquals(8, cases.size)
        for ((name, e) in cases) {
            val c = e.jsonObject
            val size = c.l("size")
            val cs = c.i("cs")
            val prefix = unhex(c.s("prefix_hex"))
            val n = FileV2.blocks(size, cs)
            assertEquals(name, c.l("blocks"), n)
            assertEquals(name, c.l("blob_size"), FileV2.cipherSize(size, cs))
            assertEquals(name, c.s("header_hex"), hex(FileV2.header(size, prefix, cs)))
            assertEquals(name, c.s("ad0_hex"), hex(FileV2.ad(0, n, cs, size)))
            assertEquals(name, c.s("nonce0_hex"), hex(FileV2.nonce(prefix, 0)))
            assertEquals(name, c.s("ad_last_hex"), hex(FileV2.ad(n - 1, n, cs, size)))
            assertEquals(name, c.s("nonce_last_hex"), hex(FileV2.nonce(prefix, n - 1)))
            val b = blob(c)
            assertEquals(name, c.s("blob_sha256"), sha(b))
            // Blockanfaenge/-enden (je bis zu 64 Bytes)
            fun block(i: Long) = b.copyOfRange(FileV2.blockOffset(i, cs).toInt(), (FileV2.blockOffset(i, cs) + FileV2.blockCipherLen(i, size, cs)).toInt())
            val b0 = block(0)
            val bl = block(n - 1)
            assertEquals(name, c.l("block0_len"), b0.size.toLong())
            assertEquals(name, c.l("block_last_len"), bl.size.toLong())
            assertEquals(name, c.s("block0_head_hex"), hex(b0.copyOf(minOf(64, b0.size))))
            assertEquals(name, c.s("block0_tail_hex"), hex(b0.copyOfRange(maxOf(0, b0.size - 64), b0.size)))
            assertEquals(name, c.s("block_last_head_hex"), hex(bl.copyOf(minOf(64, bl.size))))
            assertEquals(name, c.s("block_last_tail_hex"), hex(bl.copyOfRange(maxOf(0, bl.size - 64), bl.size)))
            // Kopf lesen + ganz entschluesseln
            val h = FileV2.parseHeader(b)!!
            assertEquals(cs, h.cs)
            assertEquals(size, h.size)
            assertArrayEquals(prefix, h.prefix)
            val out = ByteArrayOutputStream()
            FileV2Reader(crypto, unb64(c.s("key"), 32)!!, size, cs).decryptStream(ByteArrayInputStream(b), out)
            assertArrayEquals(name, plain(size), out.toByteArray())
        }
    }

    /** Teile an beliebigen Grenzen (auch mitten im Block) ergeben denselben Blob - Grundlage fuers Fortsetzen */
    @Test
    fun writerAtAnyOffset() {
        val c = v["cases"]!!.jsonObject["rest_block"]!!.jsonObject
        val whole = blob(c)
        val w = FileV2Writer(crypto, unb64(c.s("key"), 32)!!, unhex(c.s("prefix_hex")), GenSource(c.l("size")))
        val out = ByteArrayOutputStream()
        var p = 0L
        val cuts = longArrayOf(1, 47, 48, 49, 262_207, 262_208, 300_001, 524_367, 524_368, w.blobSize)
        for (cut in cuts) {
            w.write(p, cut, out)
            p = cut
        }
        assertArrayEquals(whole, out.toByteArray())
        // Strom ab einer Stelle mitten im Block
        val tail = w.stream(300_000).readBytes()
        assertArrayEquals(whole.copyOfRange(300_000, whole.size), tail)
    }

    private fun mutate(b0: ByteArray, op: JsonObject, cs: Int): ByteArray {
        var b = b0.copyOf()
        fun putBe(off: Int, value: Long, n: Int) {
            for (k in 0 until n) b[off + k] = (value ushr (8 * (n - 1 - k))).toByte()
        }
        when (op.s("op")) {
            "swap_blocks" -> {
                val bi = FileV2.blockOffset(op.l("i"), cs).toInt()
                val bj = FileV2.blockOffset(op.l("j"), cs).toInt()
                val x = b.copyOfRange(bi, bi + cs + 16)
                val y = b.copyOfRange(bj, bj + cs + 16)
                System.arraycopy(y, 0, b, bi, y.size)
                System.arraycopy(x, 0, b, bj, x.size)
            }
            "drop_last_block" -> b = b.copyOf(FileV2.blockOffset(FileV2.blocks(op.l("size"), cs) - 1, cs).toInt())
            "drop_last_block_fix_size" -> {
                val n = FileV2.blocks(op.l("size"), cs)
                b = b.copyOf(FileV2.blockOffset(n - 1, cs).toInt())
                putBe(16, (n - 1) * cs, 8)
            }
            "set_size" -> putBe(16, op.l("value"), 8)
            "set_cs" -> putBe(8, op.l("value"), 4)
            "set_byte" -> b[op.i("offset")] = op.i("value").toByte()
            "flip" -> b[op.i("offset")] = (b[op.i("offset")].toInt() xor 1).toByte()
            "truncate" -> b = b.copyOf(op.i("length"))
            else -> fail("op " + op.s("op"))
        }
        return b
    }

    @Test
    fun reject() {
        val list = v["reject"]!!.jsonArray
        assertEquals(12, list.size)
        for (e in list) {
            val op = e.jsonObject
            val c = v["cases"]!!.jsonObject[op.s("case")]!!.jsonObject
            val cs = c.i("cs")
            val bad = mutate(blob(c), op, cs)
            val sink = ByteArrayOutputStream()
            try {
                FileV2Reader(crypto, unb64(c.s("key"), 32)!!, c.l("size"), cs).decryptStream(ByteArrayInputStream(bad), sink)
                fail("muss scheitern: " + op.s("name"))
            } catch (_: ChatCryptoException) {
            }
        }
    }

    @Test
    fun ranges() {
        val list = v["ranges"]!!.jsonArray
        assertEquals(7, list.size)
        for (e in list) {
            val r = e.jsonObject
            val got = FileV2.range(r.l("a"), r.l("b"), r.l("size"))
            assertEquals(r.toString(), FileV2.Range(r.l("first_block"), r.l("last_block"), r.l("cipher_from"), r.l("cipher_to")), got)
        }
    }

    @Test
    fun headerChecks() {
        val good = FileV2.header(100, ByteArray(16) { it.toByte() })
        assertEquals(100L, FileV2.parseHeader(good)!!.size)
        assertNull(FileV2.parseHeader(good.copyOf(47)))
        assertNull(FileV2.parseHeader(good.copyOf().also { it[45] = 1 }))         // prefix-Rest nicht 0
        assertNull(FileV2.parseHeader(FileV2.header(1, ByteArray(16), 1000)))    // keine Zweierpotenz
        assertNull(FileV2.parseHeader(FileV2.header(1, ByteArray(16), 32 * 1024)))  // zu klein
    }
}
