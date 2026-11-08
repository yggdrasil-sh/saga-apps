package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.call.HornRecover
import io.github.yggdrasil_sh.saga.call.HornRecover.Step
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Horn-Fix (Saga 2.5): Wiederaufbau nach Knotenverlust - dieselben Faelle wie iOS HornRecoverTests.swift. 30 s ueber Knoten, dann direkt. */
class HornRecoverTest {
    private fun obj(s: String) = Json.parseToJsonElement(s) as JsonObject
    private fun ms(s: Step?): Long? = (s as? Step.Wait)?.ms
    private fun src(name: String): String {
        val main = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
        return File(main, "java/io/github/yggdrasil_sh/saga/call/$name").readText()
    }

    @Test
    fun backoffSequence() {
        assertEquals(listOf(1000L, 2000, 4000, 8000, 15000, 15000, 15000), listOf(1, 2, 3, 4, 5, 6, 9).map { HornRecover.delayMs(it, 0.5) })
        assertEquals(800L, HornRecover.delayMs(1, 0.0))
        assertEquals(1200L, HornRecover.delayMs(1, 1.0))
        assertEquals(12000L, HornRecover.delayMs(5, 0.0))
        assertEquals(16500L, HornRecover.delayMs(5, 0.75))
        assertEquals(1000L, HornRecover.delayMs(0, 0.5))
        val r = HornRecover()
        assertTrue(ms(r.lost(1000, 0.5)) == 0L)                     // Verlust: sofort
        var t = 1000L
        val seq = (0 until 6).map { t += 100; ms(r.failed(t, null, 0.5)) }
        assertEquals(listOf(1000L, 2000, 4000, 8000, 15000, 15000), seq)
    }

    @Test
    fun monitorMutedAndRetryNotCancelled() {
        val r = HornRecover()
        assertFalse(r.active || r.muted)
        r.lost(1000, 0.5)
        assertTrue(r.active && r.muted)
        assertNull(r.lost(1500, 0.5))                               // zweites lost waehrend des Versuchs: ignoriert
        r.failed(2000, null, 0.5)
        assertTrue(r.muted)                                         // geplant: weiter stumm
        assertNull(r.lost(2100, 0.5))                               // geplanter Retry bleibt
    }

    @Test
    fun resetOnlyAfterData() {
        val r = HornRecover()
        r.lost(0, 0.5)
        r.failed(1000, null, 0.5)
        r.adopted(3000)
        assertTrue(r.active && !r.muted)
        assertFalse(r.tick(4000, true))                              // < 10 s
        assertEquals(2000L, ms(r.lost(8000, 0.5)))                  // Verlust 5 s nach Adopt = Fehlversuch
        assertEquals(2, r.fails)
        assertEquals(0L, r.t0)
        r.adopted(20_000)
        assertFalse(r.tick(40_000, false))
        assertTrue(r.tick(40_000, true))
        assertFalse(r.active)
        assertEquals(0, r.fails)
        assertEquals(0L, ms(r.lost(50_000, 0.5)))
        assertEquals(50_000L, r.t0)
        val r2 = HornRecover()
        r2.lost(0, 0.5); r2.adopted(1000)
        assertEquals(0L, ms(r2.lost(12_000, 0.5)))                  // >= 10 s nach Adopt: neu beginnen
        assertEquals(12_000L, r2.t0)
        val r3 = HornRecover()
        r3.lost(0, 0.5); r3.adopted(1000)
        assertEquals(1000L, ms(r3.lost(15_000, 0.5, rx = false)))   // > 10 s, aber ohne Daten: Fehlversuch derselben Folge
        assertEquals(0L, r3.t0)
        assertEquals(1, r3.fails)
    }

    @Test
    fun tooManyRequests() {
        val r = HornRecover()
        r.lost(0, 0.5)
        assertEquals(7000L, ms(r.failed(100, 7, 0.5)))              // max(7, Backoff 1)
        assertEquals(60_000L, ms(r.failed(200, 300, 0.5)))          // hoechstens 60 s
        assertEquals(4000L, ms(r.failed(300, null, 0.5)))           // alter Server ohne Feld: Backoff normal weiter
        assertEquals(8000L, ms(r.failed(400, 2, 0.5)))              // 429 kuerzer als Backoff
        assertEquals(12, HornRecover.retryAfter(429, obj("""{"error":"busy","retry_after":12}""")))
        assertEquals(5, HornRecover.retryAfter(429, obj("""{"error":"busy","retry_after":"5"}""")))
        assertEquals(9, HornRecover.retryAfter(429, obj("""{"error":"busy"}"""), "9"))
        assertNull(HornRecover.retryAfter(429, obj("""{"error":"busy"}""")))
        assertNull(HornRecover.retryAfter(429, null))
        assertEquals(60, HornRecover.retryAfter(429, obj("""{"retry_after":999}""")))
        assertEquals(1, HornRecover.retryAfter(429, obj("""{"retry_after":0}""")))
        assertNull(HornRecover.retryAfter(503, obj("""{"retry_after":5}""")))
        assertNull(HornRecover.retryAfter(429, obj("""{"retry_after":true}""")))
    }

    @Test
    fun avoidRule() {
        assertEquals(emptyList<String>(), HornRecover.avoid(listOf("n1"), listOf("n1")))
        assertEquals(emptyList<String>(), HornRecover.avoid(listOf("n1"), emptyList()))
        assertEquals(listOf("n1"), HornRecover.avoid(listOf("n1"), listOf("n1", "n2")))
        assertEquals(emptyList<String>(), HornRecover.avoid(listOf("n1", "n2"), listOf("n1", "n2")))
        assertEquals(listOf("n1", "n2"), HornRecover.avoid(listOf("n2", "n1"), listOf("n1", "n2", "n3")))
        assertEquals(emptyList<String>(), HornRecover.avoid(listOf("n1"), listOf("n1", "n2"), lost = "n1"))   // verlorenen nie
        assertEquals(listOf("n2"), HornRecover.avoid(listOf("n1", "n2"), listOf("n1", "n2", "n3"), lost = "n1"))
    }

    @Test
    fun directAfter30s() {
        var r = HornRecover()
        r.lost(0, 0.5, canDirect = true)
        val st = ArrayList<Step>()
        var t = 0L
        while (t < 200_000) {
            val s = r.failed(t, null, 0.5, canDirect = true)
            st.add(s)
            if (s is Step.Wait) t += s.ms + 500 else break
        }
        assertTrue(st.last() is Step.Direct)
        assertTrue(t in 30_000 until 31_000)
        assertTrue(st.dropLast(1).all { it is Step.Wait })
        assertTrue(r.muted)                                         // Direkt-Anfrage laeuft
        assertNull(r.direct(HornRecover.DirectAnswer.Switched(listOf("b")), t, 0.5))
        assertFalse(r.active || r.muted)
        // 429 im Fenster endet am Fensterende
        r = HornRecover()
        r.lost(0, 0.5, canDirect = true)
        assertEquals(20_000L, ms(r.failed(10_000, 60, 0.5, canDirect = true)))
        assertTrue(r.failed(30_000, null, 0.5, canDirect = true) is Step.Direct)
        // Knoten wieder da: kein Umschalten
        r = HornRecover()
        r.lost(0, 0.5, canDirect = true)
        r.failed(1000, null, 0.5, canDirect = true)
        r.adopted(5000)
        assertTrue(r.tick(15_000, true))
        // ohne Direkt-Weg weiter ueber Knoten, wird er moeglich: umschalten
        r = HornRecover()
        r.lost(0, 0.5)
        assertTrue(r.failed(40_000, null, 0.5, canDirect = false) is Step.Wait)
        assertTrue(r.failed(45_000, null, 0.5, canDirect = true) is Step.Direct)
    }

    @Test
    fun directRefused() {
        val r = HornRecover()
        r.lost(0, 0.5, canDirect = true)
        assertTrue(r.failed(31_000, null, 0.5, canDirect = true) is Step.Direct)
        assertEquals(1000L, ms(r.direct(HornRecover.DirectAnswer.NodeReady, 31_500, 0.5)))   // erst wieder Knoten
        assertTrue(r.failed(34_000, null, 0.5, canDirect = true) is Step.Direct)
        assertEquals(20_000L, ms(r.direct(HornRecover.DirectAnswer.Busy(20), 34_500, 0.5)))
        assertTrue(r.failed(55_000, null, 0.5, canDirect = true) is Step.Direct)
        assertTrue(r.direct(HornRecover.DirectAnswer.NoPath("limit"), 55_500, 0.5) is Step.Wait)
        assertTrue(r.noDirect)
        assertTrue(r.failed(70_000, null, 0.5, canDirect = true) is Step.Wait)   // nach limit: nicht mehr fragen
        assertTrue(r.failed(120_000, null, 0.5, canDirect = true) is Step.GiveUp) // kein Direkt-Weg: Ende nach 120 s
        val r2 = HornRecover()
        r2.lost(0, 0.5, canDirect = true)
        r2.failed(40_000, null, 0.5, canDirect = true)
        assertNull(r2.direct(HornRecover.DirectAnswer.Ended, 40_500, 0.5))
        assertFalse(r2.active)
        // noDirect gilt bis zum erfolgreichen Wiederaufbau (wie iOS)
        val r4 = HornRecover()
        r4.lost(0, 0.5, canDirect = true)
        r4.failed(31_000, null, 0.5, canDirect = true)
        r4.direct(HornRecover.DirectAnswer.NoPath("limit"), 31_500, 0.5)
        assertTrue(r4.noDirect)
        r4.adopted(40_000)
        assertTrue(r4.tick(50_000, true))
        assertFalse(r4.noDirect)
    }

    @Test
    fun directAnswerAndTargets() {
        assertEquals(HornRecover.DirectAnswer.Switched(listOf("b", "c")),
            HornRecover.directAnswer(200, obj("""{"ok":true,"call":{"topo":"mesh"},"offer_to":["b","c"]}""")))
        assertEquals(HornRecover.DirectAnswer.Error, HornRecover.directAnswer(200, obj("""{"ok":true,"call":{"topo":"horn"}}""")))
        assertEquals(HornRecover.DirectAnswer.NodeReady, HornRecover.directAnswer(409, obj("""{"horn_block":"node_ready","horn":{}}""")))
        assertEquals(HornRecover.DirectAnswer.NoPath("limit"), HornRecover.directAnswer(409, obj("""{"horn_block":"limit"}""")))
        assertEquals(HornRecover.DirectAnswer.NoPath("fed"), HornRecover.directAnswer(409, obj("""{"horn_block":"fed"}""")))
        assertEquals(HornRecover.DirectAnswer.Ended, HornRecover.directAnswer(404, obj("""{"ended":true}""")))
        assertEquals(HornRecover.DirectAnswer.Busy(9), HornRecover.directAnswer(429, obj("""{"retry_after":9}""")))
        assertEquals(HornRecover.DirectAnswer.Error, HornRecover.directAnswer(500, null))
        assertEquals(HornRecover.DirectAnswer.Error, HornRecover.directAnswer(null, null))
        val me = "5".repeat(32)
        val lo = "1".repeat(32)
        val hi = "a".repeat(32)
        assertEquals(listOf(hi), HornRecover.offerTargets(listOf(hi, lo, hi), me))       // nur groessere IDs (kein Glare)
        assertEquals(listOf("n1"), HornRecover.unreached(mapOf("n1" to 100_000L, "n2" to 50_000L), 60_000, 60_000))
        assertEquals("node", HornRecover.startNote("node"))
        assertEquals("old", HornRecover.startNote("old"))
        assertEquals("old", HornRecover.startNote("peer"))
        assertNull(HornRecover.startNote("off"))
        assertNull(HornRecover.startNote("caps"))
        assertNull(HornRecover.startNote("fed"))
        assertNull(HornRecover.startNote(null))
    }

    @Test
    fun giveUpAfter120sWithoutDirect() {
        val r = HornRecover()
        r.lost(0, 0.5)
        val steps = ArrayList<Step>()
        var t = 0L
        while (t < 200_000) {
            val s = r.failed(t, null, 0.5)
            steps.add(s)
            if (s is Step.GiveUp) break
            t += (s as Step.Wait).ms + 500
        }
        assertTrue(steps.last() is Step.GiveUp)
        assertTrue(t in 120_000 until 121_000)
        assertTrue(steps.dropLast(1).all { it is Step.Wait })      // ohne Direkt-Weg: nur Knoten-Versuche
        assertFalse(r.muted)
        val r2 = HornRecover()
        r2.lost(0, 0.5)
        assertEquals(5000L, ms(r2.failed(115_000, 30, 0.5)))        // Wartezeit endet an der Frist
        assertTrue(r2.failed(120_000, null, 0.5) is Step.GiveUp)
        assertTrue(r2.expired(120_000))
    }

    @Test
    fun fedDmViaHorn() {
        val caps = io.github.yggdrasil_sh.saga.call.CallRoom.myCaps(setOf("reneg", "multi", "horn"))
        assertTrue(io.github.yggdrasil_sh.saga.call.CallRoom.dmViaHorn(false, true, true, caps))     // foederiert + horn_dm_fed
        assertFalse(io.github.yggdrasil_sh.saga.call.CallRoom.dmViaHorn(false, true, false, caps))   // lokal: direkt
        assertTrue(io.github.yggdrasil_sh.saga.call.CallRoom.dmViaHorn(true, false, false, caps))
        assertFalse(io.github.yggdrasil_sh.saga.call.CallRoom.dmViaHorn(false, true, true, listOf("horn")))
        val cfg = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString(io.github.yggdrasil_sh.saga.call.CallConfig.serializer(), """{"horn_dm_fed":true}""")
        assertTrue(cfg.hornDmFed)
    }

    @Test
    fun wiring() {
        val s = src("HornSession.kt")
        assertTrue(s.contains("if (why == \"lost\") { recover(why, cur?.node, (cur?.rx ?: 0L) > 0); return }"))
        assertTrue(s.contains("if (c != null && !switching && !rec.muted) {"))
        assertTrue(s.contains("rec.adopted(now)"))
        val adopt = s.substring(s.indexOf("private fun adopt("), s.indexOf("fun switch(why"))
        assertFalse(adopt.contains("retryJob?.cancel()"))           // Adopt bricht den Plan nicht ab
        assertTrue(s.contains("private fun directOk(): Boolean = since > 0 && host.canDirect()"))
        assertTrue(s.contains("val a = host.direct(HornRecover.unreached(bad, System.currentTimeMillis(), HornLogic.BAD_MS))"))
        val cm = src("CallManager.kt")
        assertTrue(cm.contains("throw HornSession.HornBusy(HornRecover.retryAfter(e.code, b))"))   // 429 ist kein noNode
        assertTrue(cm.contains("private fun hornDirect1(): Boolean = \"horn_direct1\" in"))
        assertTrue(cm.contains("api.post(\"api/calls/\${r.id}/direct\""))
        assertTrue(cm.contains("\"call.topo\" -> {") && cm.contains("hornToMesh(c, null)"))
        assertTrue(cm.contains("HornRecover.offerTargets(others, me)"))
        assertTrue(cm.contains("directStartNote(c, CallRoom.str(o, \"horn_block\"))"))
    }
}
