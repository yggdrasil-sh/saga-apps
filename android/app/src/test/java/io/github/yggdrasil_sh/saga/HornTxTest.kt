package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.call.HornLogic
import io.github.yggdrasil_sh.saga.call.HornLogic.TxGov
import io.github.yggdrasil_sh.saga.call.HornLogic.TxStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A1 "Video stockt ueber Gjallarhorn" (Android als Sender): Senderate auf TCP/TLS - dieselben Faelle wie
 * tests/horn_logik.js (txPlan, TxGov) im Server-Repo, dazu die Android-Besonderheiten (gleiche Bildrate in allen
 * Schichten, Energiesparen, Encodings beim Anlegen).
 */
class HornTxTest {
    private val H = HornLogic

    // ---- txPlan --------------------------------------------------------------------------------------------------

    @Test
    fun planTcpTls() {
        var p = H.txPlan("tcp", 0, others = 1, h = 720, sh = 1080)
        val l = p.cam.getValue("l")
        val m = p.cam.getValue("m")
        assertTrue("TCP zu zweit: l bleibt an (Ausweichschicht)", l.active && m.active)
        assertEquals(150_000, l.maxBps)
        assertEquals(600_000, m.maxBps)
        assertEquals(1.0, m.scaleDown!!, 0.0)
        assertEquals(HornLogic.ScreenSet(900_000, 8, 1.0), p.screen)

        p = H.txPlan("turn", 0, others = 1, h = 1080, sh = 1440)
        assertEquals("TLS: m 450 kbit/s bis 540p (1080/540 = 2)", 450_000, p.cam.getValue("m").maxBps)
        assertEquals(2.0, p.cam.getValue("m").scaleDown!!, 0.0)
        assertEquals(120_000, p.cam.getValue("l").maxBps)
        assertEquals("Bildschirm 600 kbit/s 5 fps bis 720p", HornLogic.ScreenSet(600_000, 5, 2.0), p.screen)

        p = H.txPlan("turn", 0, others = 1, h = 720)
        assertEquals("TLS, Kamera 720p: m auf 540p (1,33)", 1.33, p.cam.getValue("m").scaleDown!!, 0.0)

        p = H.txPlan("tcp", 0, others = 0)
        assertFalse("allein: keine Kamera-Schicht (auch TCP)", p.cam.getValue("l").active || p.cam.getValue("m").active)

        p = H.txPlan("turn", 0, others = 1, share = true)
        assertTrue("Bildschirm teilen auf TLS: Kamera nur l", p.cam.getValue("l").active && !p.cam.getValue("m").active)

        p = H.txPlan("tcp", 2, others = 4)
        assertTrue("Stufe 2: m aus (nur l)", !p.cam.getValue("m").active && p.cam.getValue("l").active)
        assertEquals("Bildschirm 0,4 x 900 = 360 kbit/s bei 3 fps", HornLogic.ScreenSet(360_000, 3, 1.0), p.screen)

        p = H.txPlan("turn", 3, others = 1, sh = 720)
        assertEquals("Stufe 3 (TLS): l 84 kbit/s", 84_000, p.cam.getValue("l").maxBps)
        assertEquals("Bildschirm 150 kbit/s, 2 fps, 1,5-fach kleiner", HornLogic.ScreenSet(150_000, 2, 1.5), p.screen)

        p = H.txPlan("tcp", 1, others = 1)
        assertTrue(p.cam.getValue("m").active)
        assertEquals("Stufe 1: m 360 kbit/s", 360_000, p.cam.getValue("m").maxBps)
        assertEquals("Stufe 1: Bildschirm 5 fps", 5, p.screen!!.maxFps)

        p = H.txPlan("turn", 3, others = 1)
        assertEquals("450 x 0,25 = 112,5 -> 113 (wie Math.round im Web)", 113_000, p.cam.getValue("m").maxBps)
        assertEquals("Stufe ausserhalb 0..3 wird begrenzt", H.txPlan("tcp", 3, 1), H.txPlan("tcp", 9, 1))
        assertEquals(H.txPlan("tcp", 0, 1), H.txPlan("tcp", -1, 1))
        assertFalse("h gibt es auf TCP nicht", H.txPlan("tcp", 0, 3, rids = listOf("l", "m", "h")).cam.getValue("h").active)
    }

    @Test
    fun planUdpUnchanged() {
        var p = H.txPlan("udp", 0, others = 1)
        assertTrue("UDP zu zweit (P03): ohne l, m und h an (h faellt bei Engpass weg, m bleibt)",
            !p.cam.getValue("l").active && p.cam.getValue("m").active && p.cam.getValue("h").active)
        assertNull("UDP: keine Deckel (GCC)", p.cam.getValue("m").maxBps)
        assertNull(p.cam.getValue("m").scaleDown)
        assertNull("UDP: kein Bildschirm-Plan", p.screen)
        p = H.txPlan("udp", 0, others = 3)
        assertTrue("UDP in der Gruppe: alle Schichten an", p.cam.values.all { it.active })
        p = H.txPlan("udp", 0, others = 3, lowPower = true)
        assertFalse("Energiesparen: ohne h", p.cam.getValue("h").active)
        assertEquals(15, p.maxFps)
        assertTrue("UDP allein: nichts", H.txPlan("udp", 0, 0).cam.values.none { it.active })
    }

    @Test
    fun planUniformAndLowPower() {
        // Fehler 26.10.: keine Bildrate je Schicht - der Plan kennt nur eine gemeinsame (maxFps)
        assertEquals(15, H.txPlan("tcp", 0, 1, lowPower = true).maxFps)
        assertNull(H.txPlan("tcp", 0, 1).maxFps)
        assertNull("allein: keine Grenze noetig", H.txPlan("tcp", 0, 0, lowPower = true).maxFps)
        val p = H.txPlan("tcp", 0, 1, lowPower = true)
        assertTrue("Energiesparen auf TCP: l bleibt (es gibt kein h zum Weglassen)", p.cam.getValue("l").active && p.cam.getValue("m").active)
    }

    @Test
    fun encodingsAtCreate() {
        assertEquals(listOf(150, 600), H.camEncodings("tcp").map { it.maxKbit })
        assertEquals(listOf(120, 450), H.camEncodings("turn").map { it.maxKbit })
        assertEquals(listOf(150, 500, 1500), H.camEncodings("udp").map { it.maxKbit })
        assertEquals(900 to 8, H.screenRate("tcp"))
        assertEquals(600 to 5, H.screenRate("turn"))
        assertEquals(1800 to 15, H.screenRate("udp"))
    }

    // ---- TxGov ---------------------------------------------------------------------------------------------------

    private class Feed(path: String) {
        val g = TxGov(path)
        var t = 0L
        fun feed(rtt: Double?, n: Int, step: Long = 3000): TxStep? {
            var last: TxStep? = null
            repeat(n) { t += step; g.add(t, rtt)?.let { last = it } }
            return last
        }
    }

    @Test
    fun govDown() {
        val f = Feed("turn")
        f.feed(160.0, 5)
        assertEquals("ruhig bei 160 ms: Stufe 0", 0, f.g.level)
        assertEquals(160.0, f.g.base, 0.0)
        assertNull("eine Messung 330 ms reicht nicht", f.feed(330.0, 1))
        val ch = f.feed(330.0, 1)
        assertEquals("zweite Messung in Folge: Stufe 1", TxStep(0, 1, 330, 160), ch)
        assertEquals("Diagnose-Zeile", "L0>L1 rtt=330 base=160 turn", ch!!.line("turn"))
        f.feed(330.0, 1)
        assertEquals("< 6 s seit dem Schritt: bleibt 1", 1, f.g.level)
        f.feed(330.0, 1)
        assertEquals("nach 6 s weiter Stau: Stufe 2", 2, f.g.level)
        f.feed(330.0, 10)
        assertEquals("hoechstens Stufe 3", 3, f.g.level)
    }

    @Test
    fun govThresholds() {
        var f = Feed("tcp")
        f.feed(160.0, 2); f.feed(260.0, 6)
        assertEquals("100 ms ueber dem Grundwert (< 120): kein Stau", 0, f.g.level)
        f = Feed("tcp")
        f.feed(20.0, 2); f.feed(150.0, 2)
        assertEquals("Grundwert 20, RTT 150: Stau (130 ms und > 50 %)", 1, f.g.level)
        f = Feed("turn")
        f.feed(200.0, 2)
        assertEquals("RTT 5,4 s: sofort", 1, f.feed(5400.0, 1)?.to)
    }

    @Test
    fun govRecovery() {
        val f = Feed("tcp")
        f.feed(100.0, 2); f.feed(400.0, 2)
        assertEquals(1, f.g.level)
        f.feed(100.0, 4)
        assertEquals("12 s frei: noch 1", 1, f.g.level)
        f.feed(100.0, 2)
        assertEquals("15 s frei: zurueck auf 0", 0, f.g.level)
        f.feed(400.0, 2)
        assertEquals("Stau kurz nach dem Schritt nach oben: wieder 1", 1, f.g.level)
        assertEquals("Wartezeit verdoppelt", 30_000L, f.g.upWait)
        f.feed(100.0, 6)
        assertEquals("18 s frei: noch 1 (Wartezeit 30 s)", 1, f.g.level)
        f.feed(100.0, 5)
        assertEquals("nach 30 s frei: 0", 0, f.g.level)

        val g = Feed("tcp")
        g.feed(100.0, 2); g.feed(400.0, 2); g.feed(100.0, 4); g.feed(190.0, 1); g.feed(100.0, 4)
        assertEquals("Zwischenwert setzt die Erholungszeit zurueck", 1, g.g.level)
    }

    /** Pruefung A1: Grundwert ueber ein gleitendes Fenster (2 min) - Routenwechsel und Zwischenband haengen nicht fest */
    @Test
    fun govBaseWindow() {
        val f = Feed("tcp")
        f.feed(20.0, 4)
        f.feed(180.0, 3)
        assertTrue("Routenwechsel 20 -> 180 ms sieht zuerst wie Stau aus", f.g.level >= 1)
        f.feed(180.0, 100)                                   // 5 min auf der neuen Route
        assertEquals("Grundwert folgt der neuen Route", 180.0, f.g.base, 0.0)
        assertEquals("und die Stufe kehrt auf 0 zurueck (Web: bliebe auf 3)", 0, f.g.level)

        val g = Feed("tcp")
        g.feed(100.0, 2); g.feed(400.0, 2)
        assertEquals(1, g.g.level)
        g.feed(190.0, 30)                                    // 90 s im Band 60..120 ueber dem alten Grundwert
        assertEquals("innerhalb des Fensters: Stufe bleibt", 1, g.g.level)
        g.feed(190.0, 30)                                    // nach > 2 min: Grundwert 190, frei
        assertEquals("Zwischenband haengt nicht fuer immer", 0, g.g.level)
        assertEquals(190.0, g.g.base, 0.0)

        val h = Feed("turn")
        h.feed(150.0, 2)
        h.feed(200.0, 1)
        h.feed(150.0, 1)
        h.feed(170.0, 20)                                    // 60 s spaeter: das Minimum 150 liegt noch im Fenster
        assertEquals("kleinster Wert im Fenster zaehlt", 150.0, h.g.base, 0.0)
    }

    @Test
    fun govUdpAndMissing() {
        val f = Feed("udp")
        f.feed(100.0, 2); f.feed(5000.0, 4)
        assertEquals("UDP: Regler aus (GCC regelt)", 0, f.g.level)
        val g = TxGov("tcp")
        assertNull(g.add(1000, null))
        assertNull(g.add(2000, 0.0))
        assertNull(g.add(3000, Double.NaN))
        assertNull(g.add(4000, Double.POSITIVE_INFINITY))
        assertEquals(0, g.level)
    }

    // ---- Verdrahtung (Quelltext) ---------------------------------------------------------------------------------

    private fun src(name: String): String {
        val main = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
        return File(main, "java/io/github/yggdrasil_sh/saga/call/$name").readText()
    }

    @Test
    fun sessionWiring() {
        val s = src("HornSession.kt")
        assertTrue("tune nutzt txPlan", s.contains("HornLogic.txPlan(conn.path, lv, others, share"))
        assertTrue("Regler je Verbindung (TCP/TLS)", s.contains("HornLogic.TxGov(path)"))
        assertTrue("Diagnose-Ereignis tx", s.contains("host.diagEvent(\"tx\", d)"))
        assertTrue("Kamera BALANCED", s.contains("DegradationPreference.BALANCED"))
        assertTrue("Bildschirm MAINTAIN_RESOLUTION", s.contains("DegradationPreference.MAINTAIN_RESOLUTION"))
        assertFalse("tune nutzt camActive nicht mehr direkt", s.contains("HornLogic.camActive("))
    }
}
