package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.call.CallDiag
import io.github.yggdrasil_sh.saga.call.CallV3
import io.github.yggdrasil_sh.saga.call.HornLogic
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2.5 Anruf-Diagnose (Analyse 1.11.): wie ios/Tests/Logic/CallDiagTests.swift */
class CallDiagTest {
    @Test
    fun gaugeOver30s() {
        val g = CallDiag.Gauge()
        g.push(0, 0, 0, 0)
        var r = g.push(3000, 0, 300, 1)
        assertEquals("ein Freeze in 3 s = 2/min statt 20/min", 2.0, r.freezes, 1e-9)
        assertEquals("mid", HornLogic.quality(HornLogic.Net(40.0, r.loss, r.freezes)))
        for (i in 2..10) r = g.push(i * 3000L, 0, 300L * i, 1)
        assertEquals(2.0, r.freezes, 1e-9)
        r = g.push(36_000, 0, 3600, 1)
        assertEquals("nach 30 s faellt der Freeze heraus", 0.0, r.freezes, 1e-9)

        val g2 = CallDiag.Gauge()
        g2.push(0, 0, 0, 0)
        assertEquals(0.1, g2.push(3000, 10, 90, 0).loss, 1e-9)
        assertEquals(0.01, g2.push(6000, 10, 990, 0).loss, 1e-9)
        r = g2.push(9000, 0, 10, 0)                                  // Zaehler neu (neues Bein)
        assertEquals(0.0, r.loss, 0.0)
        assertEquals(1, g2.size)

        val g3 = CallDiag.Gauge()
        g3.push(0, 0, 0, 0)
        r = g3.push(3000, 0, 100, 2)
        assertEquals("bad", HornLogic.quality(HornLogic.Net(40.0, freezes = r.freezes)))
    }

    @Test
    fun debounceTwoInARow() {
        val d = CallDiag.Debounce("good")
        assertEquals("good", d.push("bad"))
        assertEquals("good", d.push("good"))
        assertEquals("good", d.push("bad"))
        assertEquals("zwei schlechte: rot", "bad", d.push("bad"))
        assertEquals("bad", d.push("good"))
        assertEquals("bad", d.push("mid"))
        assertEquals("mid", d.push("mid"))
        assertEquals("mid", d.push("good"))
        assertEquals("good", d.push("good"))
    }

    @Test
    fun thermalHold() {
        assertFalse("MODERATE (2) drosselt nicht mehr", CallDiag.hotRaw(2))
        assertTrue("SEVERE (3)", CallDiag.hotRaw(3))
        assertTrue(CallDiag.hotRaw(4))
        assertFalse("unbekannt", CallDiag.hotRaw(-1))
        val h = CallDiag.Hold()
        assertFalse(h.update(0, false))
        assertTrue(h.update(1000, true))
        assertTrue("kurz kuehler: bleibt heiss", h.update(6000, false))
        assertTrue(h.update(11_000, true))
        assertTrue(h.update(60_000, false))
        assertTrue(h.update(115_000, false))
        assertFalse("60 s am Stueck kuehl", h.update(120_000, false))
        val h2 = CallDiag.Hold()
        h2.update(0, true)
        assertTrue(h2.update(1000, false))
        assertTrue("mindestens 60 s heiss", h2.update(59_000, false))
        assertFalse(h2.update(61_000, false))
        // Pendeln wie im Fehlerbild (alle 5 s an/aus) -> bleibt durchgehend heiss
        val h3 = CallDiag.Hold()
        var flips = 0
        var last = false
        for (i in 0 until 60) {
            val v = h3.update(i * 5000L, i % 2 == 0)
            if (v != last) flips++
            last = v
        }
        assertEquals("ein Wechsel statt 60", 1, flips)
    }

    @Test
    fun bufferSamplesEvents() {
        val b = CallDiag.Buffer("c1", "android", "2.5.0", 1000)
        b.path = "horn"; b.transport = CallDiag.transportHorn("turn"); b.node = "horn-1.example.org"
        assertEquals("p1", b.alias("devA")); assertEquals("p2", b.alias("devB")); assertEquals("p1", b.alias("devA")); assertEquals("unbekannt: leer (Server-Zeichensatz)", "", b.alias(null))
        assertFalse(b.due(20_000)); assertTrue(b.due(31_000))
        assertNull(b.take(2000))
        assertNull(b.sample("outbound-rtp", mapOf("kind" to "audio"), "me", "a", 2000, null))
        val out = mapOf<String, Any?>("kind" to "video", "rid" to "h", "framesPerSecond" to 29.6, "frameHeight" to 720L, "bytesSent" to 0L,
            "nackCount" to 1L, "pliCount" to 2L, "keyFramesEncoded" to 1L, "qualityLimitationReason" to "cpu", "encoderImplementation" to "MediaCodec")
        val s1 = b.sample("outbound-rtp", out, "me", "o.h", 2000, 41.6)!!
        assertEquals(CallDiag.Sample(1000, "out", "me", "h", fps = 30, h = 720, rtt = 42, qlr = "cpu", enc = "MediaCodec"), s1)
        val s2 = b.sample("outbound-rtp", out + mapOf("bytesSent" to 375_000L, "pliCount" to 5L, "qualityLimitationReason" to "weird"), "me", "o.h", 5000, null)!!
        assertEquals(1000, s2.kbps); assertEquals(3, s2.pli); assertEquals("other", s2.qlr)
        val inb = mapOf<String, Any?>("kind" to "video", "framesPerSecond" to 15.0, "frameHeight" to 360L, "bytesReceived" to 10L, "packetsLost" to 4, "freezeCount" to 1L)
        b.sample("inbound-rtp", inb, "p1", "i1", 2000, null)
        val s3 = b.sample("inbound-rtp", inb + mapOf("packetsLost" to 9, "freezeCount" to 3L), "p1", "i1", 5000, null)!!
        assertEquals("in", s3.dir); assertEquals("", s3.rid); assertEquals(5, s3.lost); assertEquals(2, s3.freeze); assertEquals("", s3.qlr)
        assertTrue(b.sampleDue("i1", 0)); assertFalse(b.sampleDue("i1", 2000)); assertTrue(b.sampleDue("i1", 3000))
        b.add(s1); b.add(s2); b.add(s3)
        b.event("layers", "schichten [m] fps 15 (andere 1) waerme 3", 3000)
        b.event("bogus", "x", 3000)
        b.event("path", "udp -> tcp 10.0.0.1 fe80::1:2:3 " + "x".repeat(200), 4000)
        assertEquals(2, b.events.size)
        val d = b.events[1].d
        assertFalse(d, d.contains("10.0.0.1") || d.contains("fe80"))
        assertEquals(120, d.length)
        val body = b.take(6000)!!
        assertEquals(1, body["v"]!!.jsonPrimitive.int)
        for ((k, v) in mapOf("call" to "c1", "plat" to "android", "app" to "2.5.0", "path" to "horn", "transport" to "tls", "node" to "horn-1.example.org"))
            assertEquals(k, v, body[k]!!.jsonPrimitive.content)
        assertEquals(3, body["samples"]!!.jsonArray.size)
        assertEquals(2, body["events"]!!.jsonArray.size)
        assertTrue(b.isEmpty)
        assertEquals(setOf("t", "dir", "src", "rid", "fps", "h", "kbps", "lost", "nack", "pli", "freeze", "kf", "rtt", "qlr", "enc"),
            body["samples"]!!.jsonArray[0].jsonObject.keys)
        assertEquals(setOf("v", "call", "plat", "app", "path", "transport", "node", "samples", "events"), body.keys)
        b.event("quality", "good -> mid", 7000)
        assertEquals(11_000, b.wait(10_000))
        assertNull("hoechstens ein POST je 15 s", b.take(10_000))
        assertNotNull(b.take(21_000))
    }

    @Test
    fun bufferTrimmed() {
        val b = CallDiag.Buffer("c2", "android", "2.5.0", 0)
        for (i in 0 until 500) {
            b.add(CallDiag.Sample(i.toLong(), "in", "p1", "", enc = "e".repeat(60)))
            b.event("switch", "s".repeat(120), i.toLong())
        }
        val body: JsonObject = b.take(40_000)!!
        val s = body["samples"] as JsonArray
        val e = body["events"] as JsonArray
        val size = body.toString().toByteArray().size
        assertTrue("${s.size} Proben", s.size <= 200)
        assertTrue("${e.size} Ereignisse", e.size <= 100)
        assertTrue("$size Bytes", size in 20_001..32 * 1024)
        assertEquals("neueste bleiben", 499, s.last().jsonObject["t"]!!.jsonPrimitive.int)
    }

    @Test
    fun transports() {
        assertEquals("udp", CallDiag.transportHorn("udp")); assertEquals("tcp", CallDiag.transportHorn("tcp")); assertEquals("?", CallDiag.transportHorn(""))
        assertEquals("udp", CallDiag.transportP2p(CallV3.Path.DIRECT)); assertEquals("tcp", CallDiag.transportP2p(CallV3.Path.RELAY_TCP))
        assertEquals("?", CallDiag.transportP2p(null))
    }

    /** Vertrag saga/calldiag.py (Pruefer 1.11.): src/enc nur im Server-Zeichensatz, rid nur l/m/h, Zahlen endlich und >= 0 */
    @Test
    fun serverContract() {
        val b = CallDiag.Buffer("c3", "android", "2.5.0-rc.3-debug", 0)
        val odd = mapOf<String, Any?>("kind" to "video", "rid" to "f", "framesPerSecond" to Double.NaN, "frameHeight" to -1,
            "bytesSent" to Double.POSITIVE_INFINITY, "encoderImplementation" to "Encoder \u00e4\"x\"\n{1}", "qualityLimitationReason" to "none")
        val s = b.sample("outbound-rtp", odd, "me?<x>", "o", 1000, Double.NEGATIVE_INFINITY)!!
        assertEquals("mex", s.src); assertEquals("", s.rid); assertEquals(0, s.fps); assertEquals(0, s.h); assertEquals(0, s.kbps)
        assertEquals(0, s.rtt); assertEquals("Encoder x1", s.enc)
        assertEquals(0, CallDiag.int(Double.NaN)); assertEquals(0, CallDiag.int(-3.0)); assertEquals(3, CallDiag.int(2.6))
        assertEquals(1_000_000_000, CallDiag.int(1e12))
        b.add(s); b.add(CallDiag.Sample(1000, "in", b.alias(null), ""))
        val body = b.take(2000)!!
        println("PRUEFER-BODY\t" + body.toString())
    }
}
