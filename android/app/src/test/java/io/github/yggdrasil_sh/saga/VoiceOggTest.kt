package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.chat.ChatFile
import io.github.yggdrasil_sh.saga.chat.voice.OggOpus
import io.github.yggdrasil_sh.saga.chat.voice.VoiceLogic
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Sprachnachrichten (Konzept B3): Ogg-Muxer/-Demuxer (Round-Trip, Dauer, Granule, Pruefsumme, byte-gleich mit dem Muxer
 * des Browsers static/chat-voice.js), Opus-TOC, Wellenform gleich wie im Web (gleiche Pegel -> gleiche 64 Werte),
 * Grenzen, Gesten, Anhang.
 */
class VoiceOggTest {
    /** dieselben Pakete wie beim Erzeugen von voice_ref.ogg mit oggOpus() aus static/chat-voice.js (node, serial fest) */
    private fun refPackets(): List<ByteArray> = (0 until 120).map { i ->
        var n = (i * 37) % 90 + 20
        if (i == 7) n = 300
        if (i == 8) n = 510
        ByteArray(n) { k -> if (k == 0) (if (i % 10 == 9) 0x79 else 0x78).toByte() else ((i * 31 + k * 7) and 255).toByte() }
    }

    private val refSerial = 0x3fffffff                  // (0.25 * 0xffffffff) >>> 0 wie im Browser mit Math.random() = 0.25

    @Test
    fun muxIsByteEqualToBrowser() {
        val want = javaClass.classLoader!!.getResourceAsStream("vectors/voice_ref.ogg")!!.readBytes()
        val got = OggOpus.mux(refPackets(), 312, refSerial, "Saga", webCompat = true)
        assertEquals(want.size, got.size)
        assertArrayEquals(want, got)
    }

    @Test
    fun roundTripGranulesDuration() {
        val pk = refPackets()
        val p = OggOpus.demux(OggOpus.mux(pk, 312, 7))
        assertEquals(312, p.preskip)
        assertEquals(48_000, p.rate)
        assertEquals(1, p.channels)
        assertEquals("Saga", p.vendor)
        assertTrue(p.eos)
        assertEquals(pk.size, p.packets.size)
        pk.indices.forEach { assertArrayEquals(pk[it], p.packets[it]) }
        // 108 Pakete a 20 ms (960) + 12 mit zwei Rahmen (1920): Granule = alle Samples (RFC 7845), Dauer ohne pre-skip
        val samples = pk.sumOf { OggOpus.samples(it).toLong() }
        assertEquals(108 * 960L + 12 * 1920L, samples)
        assertEquals(samples, p.lastGranule)
        assertEquals((samples - 312) / 48_000.0, p.duration, 1e-9)
        // Web-kompatibel: Zaehlung ab pre-skip (wie static/chat-voice.js)
        assertEquals(312 + samples, OggOpus.demux(OggOpus.mux(pk, 312, 7, webCompat = true)).lastGranule)
        // Granule je Seite steigen und passen zu den Paketen der Seite (hoechstens 50 je Seite)
        assertTrue(p.granules.zipWithNext().all { (a, b) -> b > a })
        assertEquals(3, p.granules.size)
    }

    @Test
    fun crcAndStructureChecked() {
        val ogg = OggOpus.mux(refPackets(), 312, 9)
        val bad = ogg.copyOf()
        bad[200] = (bad[200].toInt() xor 1).toByte()
        try {
            OggOpus.demux(bad)
            fail("Pruefsumme muss auffallen")
        } catch (e: OggOpus.OggException) {
            assertEquals("crc", e.message)
        }
        try {
            OggOpus.demux(ogg.copyOf(ogg.size - 3))
            fail("abgeschnitten")
        } catch (_: OggOpus.OggException) {
        }
        // Pruefsumme der ersten Seite (OpusHead) unabhaengig nachgerechnet (Ogg: CRC32 ohne Spiegelung, Polynom 04c11db7)
        val first = ogg.copyOfRange(0, 27 + 1 + 19)
        val stored = (first[22].toInt() and 0xff) or ((first[23].toInt() and 0xff) shl 8) or ((first[24].toInt() and 0xff) shl 16) or ((first[25].toInt() and 0xff) shl 24)
        for (i in 22..25) first[i] = 0
        assertEquals(stored, OggOpus.crc(first))
        assertEquals(0, OggOpus.crc(ByteArray(0)))
    }

    @Test
    fun emptyAndPreskip() {
        val p = OggOpus.demux(OggOpus.mux(emptyList(), 120, 1))
        assertEquals(0, p.packets.size)
        assertEquals(0.0, p.duration, 0.0)
        assertTrue(p.eos)
        // Codec2-Konfiguration ("AOPUSHDR" + Laenge + OpusHead) bzw. reiner OpusHead
        val head = OggOpus.head(312)
        val c2 = "AOPUSHDR".toByteArray() + ByteArray(8).also { it[0] = 19 } + head
        assertEquals(312, OggOpus.preskipOf(c2))
        assertEquals(312, OggOpus.preskipOf(head))
        assertNull(OggOpus.preskipOf(ByteArray(10)))
    }

    @Test
    fun opusToc() {
        assertEquals(960, OggOpus.samples(byteArrayOf(0x78)))           // cfg 15 (Hybrid FB 20 ms), 1 Rahmen
        assertEquals(1920, OggOpus.samples(byteArrayOf(0x79)))          // 2 Rahmen
        assertEquals(120, OggOpus.samples(byteArrayOf((16 shl 3).toByte())))     // CELT cfg 16: 2,5 ms
        assertEquals(960 * 3, OggOpus.samples(byteArrayOf(((31 shl 3) or 3).toByte(), 3)))   // Code 3: 3 Rahmen a 20 ms
        assertEquals(2880, OggOpus.samples(byteArrayOf((3 shl 3).toByte())))     // SILK 60 ms
        assertEquals(0, OggOpus.samples(ByteArray(0)))
    }

    @Test
    fun waveformMatchesBrowser() {
        // Erwartung aus static/chat-voice.js (waveform) mit denselben Pegeln (node, Float32Array)
        val lv = (0 until 150).map { (Math.abs(Math.sin(it * 0.37)) * (it % 7) / 7).toFloat() }
        val want = intArrayOf(63, 171, 229, 44, 131, 255, 85, 88, 242, 102, 131, 187, 102, 170, 170, 86, 180, 207, 47, 162, 240, 61, 111, 255, 92, 105,
            230, 104, 146, 159, 99, 184, 165, 77, 208, 219, 19, 199, 249, 73, 156, 252, 98, 118, 214, 104, 159, 122, 95, 195, 186, 66,
            208, 227, 38, 190, 254, 83, 134, 245, 101, 129, 194, 147)
        assertArrayEquals(want, VoiceLogic.waveform(lv).map { it.toInt() and 0xff }.toIntArray())
        // weniger Pegel als Balken (node: 0..9 * 0.01)
        val want2 = intArrayOf(0, 0, 0, 0, 0, 0, 0, 85, 85, 85, 85, 85, 85, 120, 120, 120, 120, 120, 120, 120, 147, 147, 147, 147, 147, 147, 170, 170,
            170, 170, 170, 170, 190, 190, 190, 190, 190, 190, 190, 208, 208, 208, 208, 208, 208, 225, 225, 225, 225, 225, 225, 225, 240, 240, 240,
            240, 240, 240, 255, 255, 255, 255, 255, 255)
        assertArrayEquals(want2, VoiceLogic.waveform((0 until 10).map { (it * 0.01).toFloat() }).map { it.toInt() and 0xff }.toIntArray())
        assertArrayEquals(IntArray(32) { 255 } + IntArray(32) { 180 }, VoiceLogic.waveform(listOf(0.5f, 0.25f)).map { it.toInt() and 0xff }.toIntArray())
        assertTrue(VoiceLogic.waveform(emptyList()).all { it == 0.toByte() })
        assertTrue(VoiceLogic.waveform(listOf(0f, 0f, 0f)).all { it == 0.toByte() })
        assertEquals(64, VoiceLogic.waveform(List(10_000) { 0.1f }).size)
        // RMS (Pegel je 100 ms)
        assertEquals(0.5f, VoiceLogic.rms(ShortArray(4800) { if (it % 2 == 0) 16384 else -16384 }), 1e-6f)
        assertEquals(0f, VoiceLogic.rms(ShortArray(0)), 0f)
    }

    @Test
    fun helpers() {
        assertArrayEquals(intArrayOf(1, 2, 3, 0), VoiceLogic.bars(java.util.Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))).copyOf(4))
        assertEquals(64, VoiceLogic.bars(null).size)
        assertEquals(64, VoiceLogic.bars("kaputt!!").size)
        assertEquals(0, VoiceLogic.played(0.0, 10.0))
        assertEquals(32, VoiceLogic.played(5.0, 10.0))
        assertEquals(64, VoiceLogic.played(12.0, 10.0))
        assertEquals(0, VoiceLogic.played(1.0, 0.0))
        assertEquals(5.0, VoiceLogic.seek(0.5, 10.0), 0.0)
        assertEquals(9.95, VoiceLogic.seek(1.5, 10.0), 1e-9)
        assertEquals("0:00", VoiceLogic.fmt(0.0))
        assertEquals("0:12", VoiceLogic.fmt(12.4))
        assertEquals("15:00", VoiceLogic.fmt(899.6))
        assertEquals("0:00", VoiceLogic.fmt(Double.NaN))
        assertEquals(1.5f, VoiceLogic.nextSpeed(1f))
        assertEquals(2f, VoiceLogic.nextSpeed(1.5f))
        assertEquals(1f, VoiceLogic.nextSpeed(2f))
        assertEquals("1,5×", VoiceLogic.speedLabel(1.5f))
        assertEquals("1.5×", VoiceLogic.speedLabel(1.5f, '.'))
        assertEquals("2×", VoiceLogic.speedLabel(2f))
        val f = VoiceLogic.file("fid", "k", 1000, true, 262_144, 12.44, byteArrayOf(1, 2))
        assertEquals(true, f.voice)
        assertTrue(f.isVoice)
        assertEquals("audio/ogg; codecs=opus", f.m)
        assertEquals(2, f.x)
        assertEquals(262_144, f.cs)
        assertEquals(12.4, f.dur!!, 0.0)
        assertEquals("AQI=", f.wf)
        assertFalse(ChatFile(id = "x", m = "image/png", voice = true).isVoice)
        // im verschluesselten Inhalt: voice, dur, wf, x, cs (Feldnamen wie im Web)
        val c = io.github.yggdrasil_sh.saga.chat.ChatLogic.content("", listOf(f), emptyList())
        listOf("\"voice\":true", "\"dur\":12.4", "\"wf\":\"AQI=\"", "\"x\":2", "\"cs\":262144", "\"m\":\"audio/ogg; codecs=opus\"").forEach { assertTrue(it, c.contains(it)) }
        val v1 = VoiceLogic.file("fid", "k", 1000, false, 0, 3.0, ByteArray(64))
        assertNull(v1.x)
        assertNull(v1.cs)
    }

    @Test
    fun limitsAndGestures() {
        assertEquals(VoiceLogic.Tick.RUN, VoiceLogic.tick(10.0, 900.0, false))
        assertEquals(VoiceLogic.Tick.WARN, VoiceLogic.tick(840.0, 900.0, false))      // Hinweis bei 14:00
        assertEquals(VoiceLogic.Tick.RUN, VoiceLogic.tick(841.0, 900.0, true))
        assertEquals(VoiceLogic.Tick.STOP, VoiceLogic.tick(900.0, 900.0, true))       // Stopp bei 15:00
        assertFalse(VoiceLogic.keep(0.99))                                             // unter 1 s verwerfen
        assertTrue(VoiceLogic.keep(1.0))
        assertEquals(VoiceLogic.Gesture.HOLD, VoiceLogic.gesture(0.0, 0.0))
        assertEquals(VoiceLogic.Gesture.LOCK, VoiceLogic.gesture(-20.0, -80.0))       // nach oben sperrt
        assertEquals(VoiceLogic.Gesture.CANCEL, VoiceLogic.gesture(-130.0, -20.0))    // nach links verwirft
        assertEquals(VoiceLogic.Gesture.HOLD, VoiceLogic.gesture(-60.0, -50.0))
        assertEquals(VoiceLogic.Gesture.HOLD, VoiceLogic.gesture(120.0, 0.0))
        assertEquals(29, VoiceLogic.RECORD_MIN_SDK)                                  // Android 8/9: nur Abspielen
    }
}
