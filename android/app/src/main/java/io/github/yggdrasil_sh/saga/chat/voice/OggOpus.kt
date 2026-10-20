package io.github.yggdrasil_sh.saga.chat.voice

import java.io.ByteArrayOutputStream

/**
 * Ogg-Kapselung fuer Opus (RFC 3533 / RFC 7845) - genau wie static/chat-voice.js (oggOpus) und ios/App/VoiceOgg.swift:
 * OpusHead (Version 1, mono, pre-skip, 48 kHz), OpusTags (Hersteller), dann Seiten mit bis zu 50 Paketen bzw. 240
 * Segmenten; Granule = alle Samples (48 kHz, einschliesslich pre-skip) bis zum Ende der Seite, aus dem TOC-Byte jedes
 * Pakets (RFC 6716 3.1), nicht aus Zeitstempeln; letzte Seite mit EOS. Hoerbare Dauer = letzte Granule - pre-skip (RFC 7845
 * Abschnitt 4). Abweichung: static/chat-voice.js und VoiceOgg.swift beginnen die Zaehlung bei pre-skip (Ende dort um
 * pre-skip = 6,5 ms zu spaet) - mit webCompat = true entsteht genau deren Datei (Test der Byte-Gleichheit). Dazu ein kleiner Demuxer (Pruefsumme, Pakete, Granule) fuer Tests und
 * die Dauer.
 */
object OggOpus {
    const val RATE = 48_000
    const val MIME = "audio/ogg; codecs=opus"

    private val CRC = IntArray(256).also { t ->
        for (i in 0 until 256) {
            var r = i shl 24
            repeat(8) { r = if (r and 0x80000000.toInt() != 0) (r shl 1) xor 0x04c11db7 else r shl 1 }
            t[i] = r
        }
    }

    /** Ogg-Pruefsumme (Polynom 0x04c11db7, ohne Spiegelung, Start 0) */
    fun crc(b: ByteArray, from: Int = 0, until: Int = b.size): Int {
        var c = 0
        for (i in from until until) c = (c shl 8) xor CRC[((c ushr 24) xor (b[i].toInt() and 0xff)) and 0xff]
        return c
    }

    /** Samples (48 kHz) eines Opus-Pakets aus dem TOC-Byte (RFC 6716, 3.1) */
    fun samples(p: ByteArray): Int {
        if (p.isEmpty()) return 0
        val toc = p[0].toInt() and 0xff
        val cfg = toc shr 3
        val c = toc and 3
        val n = when (c) {
            0 -> 1
            3 -> if (p.size > 1) p[1].toInt() and 63 else 0
            else -> 2
        }
        val us = when {
            cfg < 12 -> intArrayOf(10_000, 20_000, 40_000, 60_000)[cfg and 3]
            cfg < 16 -> intArrayOf(10_000, 20_000)[cfg and 1]
            else -> intArrayOf(2_500, 5_000, 10_000, 20_000)[cfg and 3]
        }
        return (n.toLong() * us * RATE / 1_000_000).toInt()
    }

    private fun le32(b: ByteArray, o: Int, v: Int) {
        b[o] = v.toByte(); b[o + 1] = (v ushr 8).toByte(); b[o + 2] = (v ushr 16).toByte(); b[o + 3] = (v ushr 24).toByte()
    }

    private fun rd32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or ((b[o + 2].toInt() and 0xff) shl 16) or ((b[o + 3].toInt() and 0xff) shl 24)

    /** OpusHead: "OpusHead", Version 1, 1 Kanal, pre-skip, 48000 Hz, Gain 0, Mapping 0 (19 Bytes) */
    fun head(preskip: Int): ByteArray {
        val h = ByteArray(19)
        "OpusHead".toByteArray(Charsets.US_ASCII).copyInto(h)
        h[8] = 1; h[9] = 1
        h[10] = preskip.toByte(); h[11] = (preskip ushr 8).toByte()
        le32(h, 12, RATE)
        return h
    }

    fun tags(vendor: String = "Saga"): ByteArray {
        val v = vendor.toByteArray(Charsets.UTF_8)
        val t = ByteArray(8 + 4 + v.size + 4)
        "OpusTags".toByteArray(Charsets.US_ASCII).copyInto(t)
        le32(t, 8, v.size)
        v.copyInto(t, 12)
        return t
    }

    /** Pakete -> Ogg-Datei (serial fest vorgeben fuer reproduzierbare Tests) */
    fun mux(
        pkts: List<ByteArray>, preskip: Int, serial: Int = (Math.random() * 0xffffffffL).toLong().toInt(), vendor: String = "Saga",
        webCompat: Boolean = false,
    ): ByteArray {
        val out = ByteArrayOutputStream(pkts.sumOf { it.size + 2 } + 512)
        var seq = 0
        fun page(packets: List<ByteArray>, granule: Long, flags: Int) {
            val lace = ArrayList<Int>()
            var len = 0
            for (p in packets) {
                var n = p.size
                while (n >= 255) { lace.add(255); n -= 255 }
                lace.add(n)
                len += p.size
            }
            val b = ByteArray(27 + lace.size + len)
            b[0] = 0x4f; b[1] = 0x67; b[2] = 0x67; b[3] = 0x53; b[4] = 0; b[5] = flags.toByte()
            le32(b, 6, (granule and 0xffffffffL).toInt()); le32(b, 10, (granule ushr 32).toInt())
            le32(b, 14, serial); le32(b, 18, seq++)
            b[26] = lace.size.toByte()
            for ((i, l) in lace.withIndex()) b[27 + i] = l.toByte()
            var o = 27 + lace.size
            for (p in packets) { p.copyInto(b, o); o += p.size }
            le32(b, 22, crc(b))
            out.write(b)
        }
        page(listOf(head(preskip)), 0, 2)
        page(listOf(tags(vendor)), 0, 0)
        var g = if (webCompat) preskip.toLong() else 0L
        val cur = ArrayList<ByteArray>()
        var size = 0
        for ((i, p) in pkts.withIndex()) {
            g += samples(p)
            cur.add(p)
            size += (p.size + 1 + 254) / 255
            val last = i == pkts.size - 1
            if (cur.size >= 50 || size > 240 || last) {
                page(cur.toList(), g, if (last) 4 else 0)
                cur.clear()
                size = 0
            }
        }
        if (pkts.isEmpty()) page(emptyList(), g, 4)
        return out.toByteArray()
    }

    /** Gelesene Datei: Kopf, Pakete, Granule je Seite (letzte = Ende), Pruefsummen ok */
    class Parsed(val preskip: Int, val rate: Int, val channels: Int, val vendor: String, val packets: List<ByteArray>, val granules: List<Long>, val eos: Boolean) {
        val lastGranule: Long get() = granules.lastOrNull() ?: 0
        /** Dauer in Sekunden (RFC 7845: letzte Granule minus pre-skip) */
        val duration: Double get() = maxOf(0L, lastGranule - preskip) / RATE.toDouble()
    }

    class OggException(msg: String) : Exception(msg)

    /** Ogg/Opus lesen (ein logischer Strom); falsche Pruefsumme, Kopf oder Laenge -> OggException */
    fun demux(b: ByteArray): Parsed {
        var o = 0
        val packets = ArrayList<ByteArray>()
        val granules = ArrayList<Long>()
        var partial = ByteArrayOutputStream()
        var eos = false
        var serial: Int? = null
        var seq = 0
        while (o < b.size) {
            if (o + 27 > b.size || b[o] != 0x4f.toByte() || b[o + 1] != 0x67.toByte() || b[o + 2] != 0x67.toByte() || b[o + 3] != 0x53.toByte()) {
                throw OggException("capture")
            }
            val flags = b[o + 5].toInt()
            val gran = (rd32(b, o + 6).toLong() and 0xffffffffL) or (rd32(b, o + 10).toLong() shl 32)
            val ser = rd32(b, o + 14)
            val pseq = rd32(b, o + 18)
            val crcIn = rd32(b, o + 22)
            val nseg = b[o + 26].toInt() and 0xff
            if (o + 27 + nseg > b.size) throw OggException("segments")
            var len = 0
            for (i in 0 until nseg) len += b[o + 27 + i].toInt() and 0xff
            val end = o + 27 + nseg + len
            if (end > b.size) throw OggException("length")
            val page = b.copyOfRange(o, end)
            le32(page, 22, 0)
            if (crc(page) != crcIn) throw OggException("crc")
            if (serial == null) serial = ser else if (ser != serial) throw OggException("serial")
            if (pseq != seq++) throw OggException("sequence")
            var d = o + 27 + nseg
            for (i in 0 until nseg) {
                val l = b[o + 27 + i].toInt() and 0xff
                partial.write(b, d, l)
                d += l
                if (l < 255) {
                    packets.add(partial.toByteArray())
                    partial = ByteArrayOutputStream()
                }
            }
            granules.add(gran)
            if (flags and 4 != 0) eos = true
            o = end
        }
        if (packets.size < 2) throw OggException("headers")
        val h = packets[0]
        if (h.size < 19 || String(h, 0, 8, Charsets.US_ASCII) != "OpusHead") throw OggException("OpusHead")
        val t = packets[1]
        if (t.size < 16 || String(t, 0, 8, Charsets.US_ASCII) != "OpusTags") throw OggException("OpusTags")
        val vl = rd32(t, 8)
        val vendor = if (vl in 0..(t.size - 12)) String(t, 12, vl, Charsets.UTF_8) else ""
        val preskip = (h[10].toInt() and 0xff) or ((h[11].toInt() and 0xff) shl 8)
        // Granule der Kopfseiten (0) zaehlen nicht zur Dauer
        return Parsed(preskip, rd32(h, 12), h[9].toInt() and 0xff, vendor, packets.subList(2, packets.size), granules.drop(2), eos)
    }

    /** pre-skip aus der Codec-Konfiguration des Encoders (enthaelt OpusHead; Codec2: "AOPUSHDR" + Laenge + OpusHead) */
    fun preskipOf(csd: ByteArray?): Int? {
        if (csd == null) return null
        val tag = "OpusHead".toByteArray(Charsets.US_ASCII)
        for (i in 0..(csd.size - 19)) {
            var hit = true
            for (k in tag.indices) if (csd[i + k] != tag[k]) { hit = false; break }
            if (hit) return (csd[i + 10].toInt() and 0xff) or ((csd[i + 11].toInt() and 0xff) shl 8)
        }
        return null
    }
}
