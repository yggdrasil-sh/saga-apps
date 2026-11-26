package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.call.Meeting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bugs-DB 2472 (Pruefung): fester Takt der Pegelmessung ab Startzeit (Meeting.tickDelay) - Abstand zweier Messungen
 * bleibt 500 ms, auch wenn die Arbeit (getStats) dauert; verpasste Termine fallen weg. Gegenprobe: mit "500 ms Pause
 * nach der Arbeit" (alt) laesst eine einzelne leise Messung den eigenen Ring flackern (Nachlauf 600 ms).
 */
class MeetingTickTest {
    private val P = Meeting.LEVEL_TICK_MS

    @Test
    fun tickDelayFesterTakt() {
        assertEquals(500L, Meeting.tickDelay(1000, 1000, P))          // genau auf dem Termin: naechster in 500 ms
        assertEquals(350L, Meeting.tickDelay(1000, 1150, P))          // Arbeit 150 ms: Rest bis zum Termin
        assertEquals(1L, Meeting.tickDelay(1000, 1499, P))
        assertEquals(300L, Meeting.tickDelay(1000, 1700, P))          // Arbeit 700 ms: Termin 1500 verpasst -> 2000
        assertEquals(200L, Meeting.tickDelay(1000, 800, P))           // Uhr vor dem Start: bis zum Start
    }

    /** Ablauf: Arbeit dauert je Takt `work` ms; Zeitpunkte der Messungen (Beginn der Arbeit) */
    private fun schedule(work: List<Long>, fixed: Boolean): List<Long> {
        val t0 = 10_000L
        var now = t0
        val out = ArrayList<Long>()
        for (w in work) {
            now += if (fixed) Meeting.tickDelay(t0, now, P) else P
            out.add(now)
            now += w
        }
        return out
    }

    @Test
    fun abstandHoechstensEinTakt() {
        val work = listOf(40L, 150L, 90L, 200L, 10L, 120L, 180L, 60L)
        val ts = schedule(work, fixed = true)
        assertTrue(ts.zipWithNext { a, b -> b - a }.all { it == P })
        val old = schedule(work, fixed = false)
        assertTrue("Gegenprobe alt: Abstaende ueber 500 ms", old.zipWithNext { a, b -> b - a }.any { it > 600 })
    }

    @Test
    fun keinFlackernBeiEinzelnerLeiserMessung() {
        // laut, leise (Atempause), laut, ... - jede Messung frisch (at = Zeitpunkt der Messung)
        val work = List(8) { 150L }
        fun ring(ts: List<Long>): List<Boolean> {
            val r = Meeting.SelfRing()
            return ts.mapIndexed { i, t -> r.update(t, if (i % 2 == 0) 0.2 else 0.0, t, true) }
        }
        assertTrue("fester Takt: Ring bleibt an", ring(schedule(work, fixed = true)).all { it })
        assertFalse("Gegenprobe alt (650 ms Abstand): Ring flackert", ring(schedule(work, fixed = false)).all { it })
    }
}
