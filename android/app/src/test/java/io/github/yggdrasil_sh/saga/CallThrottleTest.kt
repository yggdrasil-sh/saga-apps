package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.call.CallDiag
import io.github.yggdrasil_sh.saga.call.HornLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2.5 (9.11.) Kamera am Knoten frueher drosseln: Waerme >= MODERATE oder anhaltend CPU-begrenzt, mit Hysterese */
class CallThrottleTest {
    @Test
    fun waermeSchaltetSofort() {
        val t = CallDiag.Throttle()
        assertFalse(t.update(0, 1, null))                     // LIGHT: nichts
        assertTrue(t.update(3000, 2, null))                   // MODERATE: sofort
        assertTrue(t.why.startsWith("waerme"))
        assertTrue(t.update(30_000, 1, false))                // abgekuehlt, aber noch keine 60 s
        assertTrue(t.update(89_000, 1, false))
        assertFalse(t.update(90_000, 1, false))               // 60 s am Stueck ruhig -> wieder hoch
    }

    @Test
    fun cpuErstNachZwanzigSekunden() {
        val t = CallDiag.Throttle()
        for (s in 0..18 step 3) assertFalse(t.update(s * 1000L, 0, true))
        assertFalse(t.update(19_000, 0, false))               // Unterbrechung: zaehlt neu
        for (s in 20..38 step 3) assertFalse(t.update(s * 1000L, 0, true))
        assertTrue(t.update(41_000, 0, true))                 // 21 s am Stueck
        assertTrue(t.why.startsWith("cpu"))
        assertTrue(t.update(50_000, 0, null))                 // keine Messung zaehlt als ruhig ...
        assertTrue(t.update(80_000, 0, true))                 // ... CPU wieder begrenzt: Ruhezeit beginnt neu
        assertTrue(t.update(139_000, 0, false))
        assertTrue(t.update(198_000, 0, false))
        assertFalse(t.update(199_000, 0, false))
    }

    @Test
    fun pendelnVerdoppeltDieWartezeit() {
        val t = CallDiag.Throttle()
        t.update(0, 2, null)
        assertEquals(60_000L, t.calmNeed)
        t.update(1000, 0, false); assertFalse(t.update(61_000, 0, false))
        assertTrue(t.update(70_000, 2, null))                 // kurz danach wieder warm
        assertEquals(120_000L, t.calmNeed)
        t.update(71_000, 0, false)
        assertTrue(t.update(150_000, 0, false))               // 60 s reichen jetzt nicht
        assertFalse(t.update(191_000, 0, false))
    }

    @Test
    fun cpuAnteil() {
        assertNull(CallDiag.cpuLimited(null, null, 1.0, 1000))           // erste Messung
        assertNull(CallDiag.cpuLimited(5.0, 0, 1.0, 3000))               // Zaehler neu (anderes Bein)
        assertTrue(CallDiag.cpuLimited(1.0, 0, 3.0, 3000) == true)       // 2 s von 3 s
        assertTrue(CallDiag.cpuLimited(1.0, 0, 1.5, 3000) == false)      // 0,5 s von 3 s
    }

    @Test
    fun planOhneHMitFuenfzehnFps() {
        val (on, fps) = HornLogic.camActive("udp", 3, lowPower = true)
        assertFalse("h" in on)
        assertEquals(15, fps)
        val tcp = HornLogic.txPlan("tcp", 0, 3, lowPower = true)
        assertEquals(15, tcp.maxFps)
    }
}
