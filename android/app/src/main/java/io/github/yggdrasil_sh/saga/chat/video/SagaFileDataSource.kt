package io.github.yggdrasil_sh.saga.chat.video

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import io.github.yggdrasil_sh.saga.chat.ChatCryptoException
import io.github.yggdrasil_sh.saga.chat.FileV2
import io.github.yggdrasil_sh.saga.chat.FileV2Reader
import java.io.IOException

/**
 * Liest Ciphertext-Bereiche (einschliesslich Wiederaufnahme, wenn eine Antwort kuerzer ist - z. B. bei Anhaengen eines
 * anderen Servers hoechstens 8 MiB je Antwort, docs/APP-API.md "Kuerzere Antwort moeglich").
 */
@OptIn(UnstableApi::class)
class CipherReader(private val ct: DataSource, private val spec: (Long, Long) -> DataSpec) {
    private var open = false
    private var at = 0L
    private var until = 0L

    /** Bereich from..<until oeffnen */
    fun open(from: Long, until: Long) {
        close()
        this.at = from
        this.until = until
        reopen()
    }

    private fun reopen() {
        ct.open(spec(at, until - at))
        open = true
    }

    /** genau len Bytes nach buf (ab 0) */
    fun readFully(buf: ByteArray, len: Int) {
        var o = 0
        var empty = 0
        while (o < len) {
            val r = ct.read(buf, o, len - o)
            if (r == C.RESULT_END_OF_INPUT) {
                // Antwort zu Ende, Bereich aber noch nicht: Rest neu anfordern (hoechstens ein paar Mal ohne Fortschritt)
                if (++empty > 3) throw IOException("cipher range ended early")
                ct.close()
                open = false
                reopen()
                continue
            }
            empty = 0
            o += r
            at += r
        }
    }

    fun close() {
        if (open) {
            open = false
            ct.close()
        }
    }
}

/**
 * Media3-DataSource fuer saga-file/v2 (docs/APP-API.md "Wiedergabe je Plattform", Android): Positionen und Laengen sind
 * Klartext; darunter liegen Ciphertext-Bloecke aus ct (CacheDataSource -> OkHttp mit Medien-Token). Je Abruf hoechstens
 * RUN Bloecke (2 MiB) entschluesselt im Speicher - nie die ganze Datei, nie Klartext auf der Platte. Spulen = open() an
 * neuer Stelle.
 */
@OptIn(UnstableApi::class)
class SagaFileDataSource(
    private val reader: FileV2Reader,
    ct: DataSource,
    /** Ciphertext-Bereich (Position, Laenge) -> DataSpec fuer ct */
    cipherSpec: (Long, Long) -> DataSpec,
    private val run: Int = RUN,
) : BaseDataSource(true) {
    companion object {
        const val RUN = 8
    }

    private val cipher = CipherReader(ct, cipherSpec)
    private val cs = reader.cs
    private val size = reader.size
    private val plain = Array(run) { ByteArray(cs) }
    private val plainLen = IntArray(run)
    private val ctBuf = ByteArray(cs + FileV2.TAG)
    private var winFirst = -1L
    private var winCount = 0
    private var uri: Uri? = null
    private var pos = 0L
    private var end = -1L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        try {
            if (!reader.hasHeader) {
                val h = ByteArray(FileV2.HEADER)
                cipher.open(0, FileV2.HEADER.toLong())
                cipher.readFully(h, FileV2.HEADER)
                cipher.close()
                reader.header(h)
            }
        } catch (e: ChatCryptoException) {
            throw IOException(e)
        }
        pos = dataSpec.position
        if (pos > size) throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        end = if (dataSpec.length == C.LENGTH_UNSET.toLong()) size - 1 else minOf(size - 1, pos + dataSpec.length - 1)
        opened = true
        transferStarted(dataSpec)
        return end - pos + 1
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (pos > end) return C.RESULT_END_OF_INPUT
        val i = pos / cs
        val slot = ensure(i)
        val inBlock = (pos - i * cs).toInt()
        val n = minOf(length.toLong(), (plainLen[slot] - inBlock).toLong(), end - pos + 1).toInt()
        System.arraycopy(plain[slot], inBlock, buffer, offset, n)
        pos += n
        bytesTransferred(n)
        return n
    }

    /** Block i im Fenster? Sonst Bloecke i..i+RUN-1 (bis zum benoetigten Ende) in einem Abruf holen und entschluesseln */
    private fun ensure(i: Long): Int {
        if (winFirst >= 0 && i >= winFirst && i < winFirst + winCount) return (i - winFirst).toInt()
        val last = minOf(i + run - 1, end / cs, reader.n - 1)
        val from = FileV2.blockOffset(i, cs)
        val until = FileV2.blockOffset(last, cs) + FileV2.blockCipherLen(last, size, cs)
        winFirst = -1
        winCount = 0
        cipher.open(from, until)
        try {
            for (k in i..last) {
                val len = FileV2.blockCipherLen(k, size, cs)
                cipher.readFully(ctBuf, len)
                val slot = (k - i).toInt()
                plainLen[slot] = try {
                    reader.block(k, ctBuf, len, plain[slot])
                } catch (e: ChatCryptoException) {
                    throw IOException(e)                  // Tag falsch: nichts von diesem Block ausgeben
                }
            }
        } finally {
            cipher.close()
        }
        winFirst = i
        winCount = (last - i + 1).toInt()
        return 0
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        cipher.close()
        if (opened) {
            opened = false
            transferEnded()
        }
    }
}
