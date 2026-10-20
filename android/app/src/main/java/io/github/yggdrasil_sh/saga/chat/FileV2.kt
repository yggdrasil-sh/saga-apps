package io.github.yggdrasil_sh.saga.chat

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/**
 * Datei-Format saga-file/v2 (2.4, Videos im Chat; docs/APP-API.md "Videos im Chat"): blockweise verschluesselt,
 * wahlfreier Zugriff (Range, Spulen) und fortsetzbarer Upload. Gegenstueck zu saga/file_v2.py und static/chat-video.js,
 * geprueft mit tests/vectors/file_v2.json.
 *
 *   Blob    = Kopf(48 B) || Block_0 || ... || Block_{N-1}
 *   Kopf    = "SGF2" || u8 2 || u8 0 || u16 0 || u32 cs || u32 0 || u64 s || prefix(16) || 8 x 0      (big-endian)
 *   Block_i = xchacha20poly1305_ietf(plain[i*cs ..< (i+1)*cs], AD_i, nonce_i, K)
 *   nonce_i = prefix || u64_be(i)
 *   AD_i    = LP("saga-file/v2", u64_be(i), u64_be(N), u32_be(cs), u64_be(s), u8(i == N-1))
 *
 * Alles hier arbeitet mit festen Puffern (ein Block Klartext + ein Block Ciphertext): der Speicherbedarf haengt nicht
 * von der Dateigroesse ab (Test FileV2StreamTest).
 */
object FileV2 {
    const val FORMAT = "saga-file/v2"
    const val HEADER = 48
    const val TAG = 16
    /** Blockgroesse in 2.4 (Apps senden immer diese) */
    const val CS = 256 * 1024
    const val CS_MIN = 64 * 1024
    const val CS_MAX = 1024 * 1024
    private val MAGIC = byteArrayOf('S'.code.toByte(), 'G'.code.toByte(), 'F'.code.toByte(), '2'.code.toByte())

    /** Anzahl Bloecke N (auch eine leere Datei hat einen Block: nur das Tag) */
    fun blocks(size: Long, cs: Int = CS): Long = maxOf(1L, (size + cs - 1) / cs)

    /** Groesse des Blobs: 48 + s + 16 * N */
    fun cipherSize(size: Long, cs: Int = CS): Long = HEADER + size + TAG * blocks(size, cs)

    /** Beginn von Block i im Blob */
    fun blockOffset(i: Long, cs: Int = CS): Long = HEADER + i * (cs + TAG)

    /** Klartext-Laenge von Block i */
    fun blockPlainLen(i: Long, size: Long, cs: Int = CS): Int = (minOf(size, (i + 1) * cs) - i * cs).coerceAtLeast(0).toInt()

    /** Ciphertext-Laenge von Block i (der letzte ist kuerzer) */
    fun blockCipherLen(i: Long, size: Long, cs: Int = CS): Int = blockPlainLen(i, size, cs) + TAG

    fun u64(v: Long) = ByteArray(8) { (v ushr (56 - 8 * it)).toByte() }
    fun u32(v: Int) = ByteArray(4) { (v ushr (24 - 8 * it)).toByte() }

    fun nonce(prefix: ByteArray, i: Long): ByteArray = prefix + u64(i)

    fun ad(i: Long, n: Long, cs: Int, size: Long): ByteArray =
        ChatFormat.lp(FORMAT, u64(i), u64(n), u32(cs), u64(size), byteArrayOf(if (i == n - 1) 1 else 0))

    fun header(size: Long, prefix: ByteArray, cs: Int = CS): ByteArray {
        require(prefix.size == 16) { "prefix" }
        return MAGIC + byteArrayOf(2, 0, 0, 0) + u32(cs) + ByteArray(4) + u64(size) + prefix + ByteArray(8)
    }

    class Head(val cs: Int, val size: Long, val prefix: ByteArray) {
        val n: Long get() = blocks(size, cs)
    }

    private fun be(b: ByteArray, off: Int, n: Int): Long {
        var v = 0L
        for (k in 0 until n) v = (v shl 8) or (b[off + k].toLong() and 0xff)
        return v
    }

    /** 48 Bytes -> Kopf oder null (falsche Kennung/Version/reservierte Felder/Blockgroesse) - wie saga/file_v2.py */
    fun parseHeader(raw: ByteArray): Head? {
        if (raw.size < HEADER) return null
        for (k in 0 until 4) if (raw[k] != MAGIC[k]) return null
        if (raw[4].toInt() != 2 || raw[5].toInt() != 0 || raw[6].toInt() != 0 || raw[7].toInt() != 0) return null
        val cs = be(raw, 8, 4)
        if (be(raw, 12, 4) != 0L) return null
        val size = be(raw, 16, 8)
        for (k in 40 until 48) if (raw[k].toInt() != 0) return null
        if (cs < CS_MIN || cs > CS_MAX || (cs and (cs - 1)) != 0L) return null
        if (size < 0 || size >= (1L shl 50)) return null
        return Head(cs.toInt(), size, raw.copyOfRange(24, 40))
    }

    /** Klartext-Bereich a..b (einschliesslich) -> Bloecke und Ciphertext-Bereich (einschliesslich) */
    data class Range(val firstBlock: Long, val lastBlock: Long, val cipherFrom: Long, val cipherTo: Long)

    fun range(a: Long, b: Long, size: Long, cs: Int = CS): Range {
        require(a in 0..b && (b < size || size == 0L)) { "range" }
        val i = a / cs
        val j = b / cs
        return Range(i, j, blockOffset(i, cs), minOf(blockOffset(j + 1, cs), cipherSize(size, cs)) - 1)
    }
}

/** Klartext mit wahlfreiem Zugriff (Datei, Testdaten); read liest genau len Bytes ab pos (sonst EOFException) */
interface PlainSource {
    val size: Long
    fun readFully(pos: Long, buf: ByteArray, len: Int)
}

/** Datei ueber einen FileChannel (lesen an beliebiger Stelle, ohne die Datei zu laden) */
class ChannelPlainSource(private val ch: java.nio.channels.FileChannel, override val size: Long = ch.size()) : PlainSource {
    override fun readFully(pos: Long, buf: ByteArray, len: Int) {
        val bb = java.nio.ByteBuffer.wrap(buf, 0, len)
        var p = pos
        while (bb.hasRemaining()) {
            val n = ch.read(bb, p)
            if (n < 0) throw EOFException()
            p += n
        }
    }
}

/**
 * Erzeugt den Blob einer Datei an beliebiger Stelle neu (deterministisch: K und prefix fest) - fuer den Upload in Teilen
 * und das Fortsetzen mitten im Block. Ein Block Klartext + ein Block Ciphertext im Speicher.
 */
class FileV2Writer(
    private val crypto: ChatCrypto, private val key: ByteArray, private val prefix: ByteArray, private val src: PlainSource,
    val cs: Int = FileV2.CS,
) {
    val size: Long = src.size
    val n: Long = FileV2.blocks(size, cs)
    val blobSize: Long = FileV2.cipherSize(size, cs)
    private val head = FileV2.header(size, prefix, cs)
    private val pt = ByteArray(cs)
    private val ct = ByteArray(cs + FileV2.TAG)
    private var ctBlock = -1L
    private var ctLen = 0

    init {
        require(key.size == 32 && prefix.size == 16)
    }

    /** Block i verschluesseln (bleibt im Puffer, bis ein anderer gebraucht wird) */
    private fun block(i: Long) {
        if (ctBlock == i) return
        val len = FileV2.blockPlainLen(i, size, cs)
        if (len > 0) src.readFully(i * cs, pt, len)
        ctLen = crypto.aeadEncryptInto(ct, pt, len, FileV2.ad(i, n, cs, size), FileV2.nonce(prefix, i), key)
        ctBlock = i
    }

    /** Blob-Bytes from..<until in out schreiben */
    fun write(from: Long, until: Long, out: OutputStream) {
        require(from in 0..until && until <= blobSize)
        var p = from
        while (p < until) {
            if (p < FileV2.HEADER) {
                val e = minOf(until, FileV2.HEADER.toLong()).toInt()
                out.write(head, p.toInt(), e - p.toInt())
                p = e.toLong()
                continue
            }
            val i = (p - FileV2.HEADER) / (cs + FileV2.TAG)
            block(i)
            val start = FileV2.blockOffset(i, cs)
            val off = (p - start).toInt()
            val m = minOf((ctLen - off).toLong(), until - p).toInt()
            out.write(ct, off, m)
            p += m
        }
    }

    /** Der ganze Blob als Datenstrom (ab Stelle from) */
    fun stream(from: Long = 0): InputStream = object : InputStream() {
        private var pos = from
        private val one = ByteArray(1)
        override fun read(): Int = if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= blobSize) return -1
            if (len == 0) return 0
            val m = minOf(len.toLong(), blobSize - pos, 64L * 1024).toInt()
            val sink = object : OutputStream() {
                var at = off
                override fun write(x: Int) { b[at++] = x.toByte() }
                override fun write(src: ByteArray, o: Int, l: Int) { System.arraycopy(src, o, b, at, l); at += l }
            }
            write(pos, pos + m, sink)
            pos += m
            return m
        }
    }
}

/**
 * Entschluesseln (Wiedergabe, Speichern): prueft den Kopf gegen die Angaben der Nachricht (s, cs) und jeden Block
 * (Tag, Index, Anzahl, Groesse, Endmarke). Nie Klartext eines Blocks ausgeben, dessen Tag nicht stimmt.
 */
class FileV2Reader(private val crypto: ChatCrypto, private val key: ByteArray, val size: Long, val cs: Int = FileV2.CS) {
    val n: Long = FileV2.blocks(size, cs)
    val blobSize: Long = FileV2.cipherSize(size, cs)
    private var prefix: ByteArray? = null

    init {
        if (key.size != 32 || cs < FileV2.CS_MIN || cs > FileV2.CS_MAX) throw ChatCryptoException("file2")
    }

    /** Kopf pruefen (Kennung, Version, cs und s wie in der Nachricht) und das prefix uebernehmen */
    fun header(raw: ByteArray) {
        val h = FileV2.parseHeader(raw) ?: throw ChatCryptoException("file2 header")
        if (h.cs != cs || h.size != size) throw ChatCryptoException("file2 header")
        prefix = h.prefix
    }

    val hasHeader: Boolean get() = prefix != null

    /** Block i (ct[0..<ctLen]) nach out entschluesseln -> Klartext-Laenge */
    fun block(i: Long, ct: ByteArray, ctLen: Int, out: ByteArray): Int {
        val p = prefix ?: throw ChatCryptoException("file2 header")
        if (i < 0 || i >= n || ctLen != FileV2.blockCipherLen(i, size, cs)) throw ChatCryptoException("file2 block")
        return crypto.aeadDecryptInto(out, ct, ctLen, FileV2.ad(i, n, cs, size), FileV2.nonce(p, i), key)
    }

    /**
     * Ganzen Blob als Strom entschluesseln (input ab Byte 0) und den Klartext nach out schreiben. Nach dem letzten Block
     * darf nichts mehr folgen. onBlock(i, n) nach jedem Block (Fortschritt).
     */
    fun decryptStream(input: InputStream, out: OutputStream, onBlock: (Long, Long) -> Unit = { _, _ -> }) {
        val raw = ByteArray(FileV2.HEADER)
        readFully(input, raw, FileV2.HEADER)
        header(raw)
        val ct = ByteArray(cs + FileV2.TAG)
        val pt = ByteArray(cs)
        for (i in 0 until n) {
            val len = FileV2.blockCipherLen(i, size, cs)
            readFully(input, ct, len)
            val m = block(i, ct, len, pt)
            out.write(pt, 0, m)
            onBlock(i, n)
        }
        if (input.read() >= 0) throw ChatCryptoException("file2 trailing")
    }

    companion object {
        fun readFully(input: InputStream, buf: ByteArray, len: Int) {
            var o = 0
            while (o < len) {
                val r = input.read(buf, o, len - o)
                if (r < 0) throw ChatCryptoException("file2 truncated")
                o += r
            }
        }
    }
}

/** Klartext im Speicher (2.5: Sprachnachrichten, hoechstens wenige MB) */
class BytesPlainSource(private val data: ByteArray) : PlainSource {
    override val size: Long = data.size.toLong()

    override fun readFully(pos: Long, buf: ByteArray, len: Int) {
        if (pos < 0 || pos + len > data.size) throw EOFException()
        System.arraycopy(data, pos.toInt(), buf, 0, len)
    }
}
