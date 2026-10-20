package io.github.yggdrasil_sh.saga.chat.video

import io.github.yggdrasil_sh.saga.chat.ChatLimits
import java.io.InputStream
import kotlin.math.roundToInt

/** Videos im Chat (2.4): reine Rechnungen (Unit-Tests) - Weg (v1/v2), Ziel 720p, Poster-Zeitpunkt, Dauer, MP4-Aufbau */
object VideoLogic {
    /** Poster: laengste Seite, hoechstens so gross (JPEG) */
    const val POSTER_MAX = 320
    const val POSTER_BYTES = 30 * 1024
    /** "optimiert 720p": lange Seite 1280, kurze 720, ~2,5 Mbit/s */
    const val OPT_LONG = 1280
    const val OPT_SHORT = 720
    const val OPT_BITRATE = 2_500_000
    /** darunter lohnt das Verkleinern nicht (Bitrate bereits klein, kurze Seite <= 720) */
    const val OPT_SKIP_BITRATE = 3_200_000L

    enum class Route {
        /** bis file_max: wie bisher ein Block (POST .../files) */
        V1,
        /** saga-file/v2, Upload in Teilen */
        V2,
        /** groesser als file_max, Server kann kein saga-file/v2 (2.3) */
        NO_FILE2,
        /** Chat ueber Servergrenzen, der andere Server hat Saga 2.3 (big_files false) */
        PEER_OLD,
        /** groesser als video_max */
        TOO_BIG,
    }

    /** Weg fuer ein (ggf. optimiertes) Video der Groesse size */
    fun route(size: Long, l: ChatLimits, bigFiles: Boolean?): Route = when {
        size <= l.fileMax -> Route.V1
        !l.file2 -> Route.NO_FILE2
        bigFiles == false -> Route.PEER_OLD
        l.videoMax > 0 && size > l.videoMax -> Route.TOO_BIG
        else -> Route.V2
    }

    /** groesste Videogroesse, die in diesem Chat gesendet werden kann (Hinweistexte) */
    fun maxSize(l: ChatLimits, bigFiles: Boolean?): Long =
        if (!l.file2 || bigFiles == false) l.fileMax else maxOf(l.fileMax, l.videoMax.takeIf { it > 0 } ?: l.fileMax)

    /** N-13: verkleinerte Fassung nur nehmen, wenn sie wirklich kleiner ist (Groesse des Originals unbekannt: nehmen) */
    fun keepOptimized(optimized: Long, original: Long?): Boolean = optimized > 0 && (original == null || optimized < original)

    /** Anzeigemasse (nach Drehung) */
    fun shown(w: Int, h: Int, rot: Int): Pair<Int, Int> = if (rot == 90 || rot == 270) h to w else w to h

    /**
     * Zielgroesse fuer "optimiert 720p" aus den angezeigten Massen: kurze Seite 720, lange hoechstens 1280, gerade Zahlen,
     * nie hochskalieren. null = nicht noetig (schon klein genug und sparsam kodiert).
     */
    fun target720(w: Int, h: Int, bitrate: Long?): Pair<Int, Int>? {
        if (w <= 0 || h <= 0) return null
        val short = minOf(w, h)
        val long = maxOf(w, h)
        if (short <= OPT_SHORT && long <= OPT_LONG && (bitrate == null || bitrate <= OPT_SKIP_BITRATE)) return null
        val f = minOf(1.0, OPT_SHORT.toDouble() / short, OPT_LONG.toDouble() / long)
        fun even(x: Double) = maxOf(2, (x / 2).roundToInt() * 2)
        return even(w * f) to even(h * f)
    }

    /** Poster bei 1 s bzw. 10 % der Dauer (kurze Videos) - in Mikrosekunden */
    fun posterTimeUs(durMs: Long): Long = if (durMs <= 0) 0 else minOf(1_000_000L, durMs * 1000 / 10)

    /** Dauer mit einer Nachkommastelle (Sekunden) */
    fun durSeconds(ms: Long): Double = (ms / 100.0).roundToInt() / 10.0

    /** Anzeige "1:23" bzw. "1:02:03" */
    fun durText(s: Double?): String {
        val t = maxOf(0, (s ?: 0.0).roundToInt())
        val h = t / 3600
        val m = t % 3600 / 60
        val x = t % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, x) else "%d:%02d".format(m, x)
    }

    /** Drehung aus den Metadaten auf 0/90/180/270 */
    fun rotation(v: Int?): Int = (((v ?: 0) % 360 + 360) % 360).let { if (it % 90 == 0) it else 0 }

    /**
     * Liegt "moov" vor "mdat" (faststart)? Liest nur die Kopfzeilen der obersten Boxen (Groesse + Typ, auch 64-Bit-Groessen)
     * und springt ueber die Inhalte. null = kein MP4/unbekannt.
     */
    fun moovFirst(input: InputStream): Boolean? {
        val hdr = ByteArray(16)
        var guard = 0
        while (guard++ < 64) {
            if (!readN(input, hdr, 8)) return null
            var size = ((hdr[0].toLong() and 0xff) shl 24) or ((hdr[1].toLong() and 0xff) shl 16) or
                ((hdr[2].toLong() and 0xff) shl 8) or (hdr[3].toLong() and 0xff)
            val type = String(hdr, 4, 4, Charsets.ISO_8859_1)
            var used = 8L
            if (size == 1L) {
                if (!readN(input, hdr, 8)) return null
                size = 0
                for (k in 0 until 8) size = (size shl 8) or (hdr[k].toLong() and 0xff)
                used = 16
            }
            when (type) {
                "moov" -> return true
                "mdat" -> return false
            }
            if (size == 0L) return null                   // bis zum Ende - kein moov davor
            if (size < used) return null
            if (!skipN(input, size - used)) return null
        }
        return null
    }

    private fun readN(input: InputStream, b: ByteArray, n: Int): Boolean {
        var o = 0
        while (o < n) {
            val r = input.read(b, o, n - o)
            if (r < 0) return false
            o += r
        }
        return true
    }

    private fun skipN(input: InputStream, n: Long): Boolean {
        var left = n
        while (left > 0) {
            val s = input.skip(left)
            if (s <= 0) {
                if (input.read() < 0) return false
                left--
            } else {
                left -= s
            }
        }
        return true
    }
}
