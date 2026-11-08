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

/** Horn-Fix (Saga 2.5): Wiederaufbau nach Knotenverlust - dieselben Faelle wie iOS HornRecoverTests.swift. Nie direkt (p2p). */
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
    }

    @Test
    fun tooManyRequests() {
        val r = HornRecover()
        r.lost(0, 0.5)
        assertEquals(7000L, ms(r.failed(100, 7, 0.5)))
        assertEquals(0, r.fails)                                    // 429 ist kein Fehlversuch
        assertEquals(60_000L, ms(r.failed(200, 300, 0.5)))
        assertEquals(1000L, ms(r.failed(300, null, 0.5)))           // alter Server ohne Feld: Backoff normal
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
    }

    @Test
    fun giveUpAfter120sNeverDirect() {
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
        assertTrue(steps.dropLast(1).all { it is Step.Wait })      // nur Warten + neuer Knoten-Versuch
        assertFalse(r.muted)
        val r2 = HornRecover()
        r2.lost(0, 0.5)
        assertEquals(5000L, ms(r2.failed(115_000, 30, 0.5)))        // Wartezeit endet an der Frist
        assertTrue(r2.failed(120_000, null, 0.5) is Step.GiveUp)
        assertTrue(r2.expired(120_000))
    }

    @Test
    fun wiring() {
        val s = src("HornSession.kt")
        assertTrue(s.contains("if (why == \"lost\") { recover(why, cur?.node); return }"))
        assertTrue(s.contains("if (c != null && !switching && !rec.muted) {"))
        assertTrue(s.contains("rec.adopted(now)"))
        val adopt = s.substring(s.indexOf("private fun adopt("), s.indexOf("fun switch(why"))
        assertFalse(adopt.contains("retryJob?.cancel()"))           // Adopt bricht den Plan nicht ab
        val rec = s.substring(s.indexOf("private fun retry("), s.indexOf("private fun newRound()")).lowercase()
        assertFalse(rec.contains("mesh") || rec.contains("p2p") || rec.contains("topo"))   // kein Rueckfall auf direkt
        val cm = src("CallManager.kt")
        assertTrue(cm.contains("throw HornSession.HornBusy(HornRecover.retryAfter(e.code, b))"))   // 429 ist kein noNode
    }
}
