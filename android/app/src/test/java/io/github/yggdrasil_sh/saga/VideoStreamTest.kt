package io.github.yggdrasil_sh.saga

import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import io.github.yggdrasil_sh.saga.chat.ChatCrypto
import io.github.yggdrasil_sh.saga.chat.FileV2
import io.github.yggdrasil_sh.saga.chat.FileV2Reader
import io.github.yggdrasil_sh.saga.chat.FileV2Writer
import io.github.yggdrasil_sh.saga.chat.video.SagaFileDataSource
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest

/**
 * Datenstrom (Senden, Speichern) und Wiedergabe (DataSource, Spulen): Speicher bleibt bei grossen Dateien konstant,
 * Bereiche werden richtig auf Bloecke abgebildet, kuerzere Antworten werden nachgefordert, falsche Tags nie ausgegeben.
 */
class VideoStreamTest {
    private val crypto = ChatCrypto(LazySodiumJava(SodiumJava()))
    private val key = ByteArray(32) { (it * 7 + 1).toByte() }
    private val prefix = ByteArray(16) { (0xa0 + it).toByte() }

    private fun usedHeap(): Long {
        val rt = Runtime.getRuntime()
        System.gc()
        return rt.totalMemory() - rt.freeMemory()
    }

    /** Hash-Senke: schreibt nichts in den Speicher */
    private class ShaSink : OutputStream() {
        val md: MessageDigest = MessageDigest.getInstance("SHA-256")
        var n = 0L
        override fun write(b: Int) { md.update(b.toByte()); n++ }
        override fun write(b: ByteArray, off: Int, len: Int) { md.update(b, off, len); n += len }
    }

    /**
     * 640 MB (mehr als der Heap der Test-JVM) verschluesseln und wieder entschluesseln, beides als Strom
     * (FileV2Writer.stream -> FileV2Reader.decryptStream). Der belegte Speicher waechst dabei nicht mit der Datei.
     */
    @Test
    fun constantMemoryForLargeFiles() {
        val size = 640L * 1024 * 1024 + 12345
        val src = GenSource(size)
        val w = FileV2Writer(crypto, key, prefix, src)
        val r = FileV2Reader(crypto, key, size)
        val base = usedHeap()
        var peak = 0L
        val sink = ShaSink()
        w.stream().use { input ->
            r.decryptStream(input, sink) { i, _ ->
                if (i % 256 == 0L) peak = maxOf(peak, usedHeap() - base)
            }
        }
        assertEquals(size, sink.n)
        // erwarteter Klartext-Hash (ebenfalls als Strom)
        val ref = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(1 shl 20)
        var p = 0L
        while (p < size) {
            val n = minOf(buf.size.toLong(), size - p).toInt()
            src.readFully(p, buf, n)
            ref.update(buf, 0, n)
            p += n
        }
        assertArrayEquals(ref.digest(), sink.md.digest())
        assertTrue("Speicher gewachsen: ${peak / 1024} KiB", peak < 24L * 1024 * 1024)
    }

    /** Upstream aus einem Blob; liefert je open() hoechstens cap Bytes (wie ein Server mit kuerzeren Antworten) */
    private class BlobSource(private val blob: ByteArray, private val cap: Int = Int.MAX_VALUE) : BaseDataSource(true) {
        private var pos = 0
        private var left = 0
        var opens = 0
        override fun open(dataSpec: DataSpec): Long {
            opens++
            pos = dataSpec.position.toInt()
            val want = if (dataSpec.length == C.LENGTH_UNSET.toLong()) blob.size - pos else dataSpec.length.toInt()
            left = minOf(want, cap, blob.size - pos)
            return left.toLong()
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (left == 0) return C.RESULT_END_OF_INPUT
            val n = minOf(length, left)
            System.arraycopy(blob, pos, buffer, offset, n)
            pos += n
            left -= n
            return n
        }
        override fun getUri(): android.net.Uri? = null
        override fun close() {}
    }

    /**
     * DataSpec ohne Uri: In den JVM-Tests gibt es kein echtes android.net.Uri (Stub), DataSpec verlangt aber eine. Die
     * DataSources hier lesen nur position/length - daher ueber sun.misc.Unsafe (nur im Test) angelegt.
     */
    private fun spec(pos: Long, len: Long): DataSpec {
        val uc = Class.forName("sun.misc.Unsafe")
        val unsafe = uc.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val d = uc.getMethod("allocateInstance", Class::class.java).invoke(unsafe, DataSpec::class.java) as DataSpec
        for ((name, v) in listOf("position" to pos, "length" to len)) {
            val off = uc.getMethod("objectFieldOffset", java.lang.reflect.Field::class.java).invoke(unsafe, DataSpec::class.java.getDeclaredField(name)) as Long
            uc.getMethod("putLong", Any::class.java, Long::class.javaPrimitiveType, Long::class.javaPrimitiveType).invoke(unsafe, d, off, v)
        }
        return d
    }

    private fun readRange(ds: SagaFileDataSource, a: Long, len: Long): ByteArray {
        val n = ds.open(spec(a, len))
        val out = ByteArrayOutputStream()
        val buf = ByteArray(7777)
        while (true) {
            val r = ds.read(buf, 0, buf.size)
            if (r == C.RESULT_END_OF_INPUT) break
            out.write(buf, 0, r)
        }
        ds.close()
        assertEquals(n, out.size().toLong())
        return out.toByteArray()
    }

    @Test
    fun dataSourceSeeksAndShortResponses() {
        val size = 3L * FileV2.CS + 4321
        val plain = ByteArray(size.toInt()).also { GenSource(size).readFully(0, it, size.toInt()) }
        val w = FileV2Writer(crypto, key, prefix, GenSource(size))
        val blob = ByteArrayOutputStream().also { w.write(0, w.blobSize, it) }.toByteArray()
        for (cap in intArrayOf(Int.MAX_VALUE, 100_000)) {
            val up = BlobSource(blob, cap)
            val ds = SagaFileDataSource(FileV2Reader(crypto, key, size), up, ::spec, run = 2)
            val spots = listOf(0L to size, 0L to 1L, (FileV2.CS - 1).toLong() to 2L, 500_000L to 250_000L, size - 1 to 1L, 2L * FileV2.CS to (size - 2L * FileV2.CS))
            for ((a, len) in spots) {
                assertArrayEquals("cap=$cap a=$a", plain.copyOfRange(a.toInt(), (a + len).toInt()), readRange(ds, a, len))
            }
            // bis zum Ende (Laenge offen)
            val n = ds.open(spec(size - 10, C.LENGTH_UNSET.toLong()))
            assertEquals(10L, n)
            ds.close()
        }
    }

    @Test
    fun dataSourceNeverOutputsTamperedBlock() {
        val size = 2L * FileV2.CS
        val w = FileV2Writer(crypto, key, prefix, GenSource(size))
        val blob = ByteArrayOutputStream().also { w.write(0, w.blobSize, it) }.toByteArray()
        blob[(FileV2.blockOffset(1) + 10).toInt()] = (blob[(FileV2.blockOffset(1) + 10).toInt()].toInt() xor 1).toByte()
        val ds = SagaFileDataSource(FileV2Reader(crypto, key, size), BlobSource(blob), ::spec, run = 1)
        // Block 0 ist in Ordnung
        assertEquals(100, readRange(ds, 0, 100).size)
        try {
            readRange(ds, FileV2.CS.toLong() + 5, 10)
            fail("veraenderter Block darf nicht gelesen werden")
        } catch (_: IOException) {
        }
    }
}
