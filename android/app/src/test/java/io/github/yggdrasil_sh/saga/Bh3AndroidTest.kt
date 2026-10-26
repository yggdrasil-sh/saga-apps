package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.call.CallScreen
import io.github.yggdrasil_sh.saga.call.HornLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Bughunt 3 (26.10.), Android: Anruf-Weg ueber Gjallarhorn-Knoten - Gegenstuecke zu den drei iOS-Fehlern, die nur auf
 * echten Geraeten auffielen.
 */
class Bh3AndroidTest {

    /**
     * Fund 1: VP8-Simulcast mit verschiedener Bildrate bzw. zeitlichen Schichten je Schicht (frueher l 20 fps/L1T2,
     * m 30 fps/L1T3) - libwebrtc M150 kodiert 720p dann nicht. Alle Schichten muessen dieselben Werte haben; die
     * Bildrate wird nur gemeinsam begrenzt (wie ios/App/HornCamPlan.swift).
     */
    @Test
    fun camSimulcastUniform() {
        for (path in listOf("udp", "tcp", "turn")) {
            val enc = HornLogic.camEncodings(path)
            // beim Anlegen: keine Bildrate je Schicht, ueberall CAM_TEMPORAL zeitliche Schichten
            assertTrue(path, HornLogic.camUniform(enc.map { null }, enc.map { HornLogic.CAM_TEMPORAL }))
            // tune(): fuer jede Lage (allein, zu zweit, Gruppe, Energiesparen) dieselbe Grenze an allen Schichten
            for (others in 0..4) for (low in listOf(false, true)) {
                val f = HornLogic.camMaxFps(HornLogic.camActive(path, others, low).second)
                assertTrue("$path $others $low", HornLogic.camUniform(enc.map { f }, enc.map { HornLogic.CAM_TEMPORAL }))
            }
        }
        // die alte Belegung waere nicht einheitlich gewesen
        assertFalse(HornLogic.camUniform(listOf(20, 30), listOf(2, 3)))
        assertFalse(HornLogic.camUniform(listOf(30, 30), listOf(2, 3)))
        assertFalse(HornLogic.camUniform(listOf(20, 30), listOf(3, 3)))
        assertNull(HornLogic.camMaxFps(null))
        assertEquals(15, HornLogic.camMaxFps(15))
        assertEquals(30, HornLogic.camMaxFps(60))
        assertEquals(1, HornLogic.camMaxFps(0))
        assertEquals(listOf(4.0, 2.0, 1.0), HornLogic.camEncodings("udp").map { it.scaleDown })
        assertEquals(listOf(4.0, 1.0), HornLogic.camEncodings("tcp").map { it.scaleDown })
    }

    private fun src(name: String): String {
        val main = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
        return File(main, "java/io/github/yggdrasil_sh/saga/call/$name").readText()
    }

    /** Fund 1 (Code): HornSession setzt am Kamera-Sender weder scalabilityMode noch eine Bildrate je Schicht */
    @Test
    fun camSimulcastSource() {
        val s = src("HornSession.kt")
        assertFalse(s.contains("scalabilityMode ="))
        assertTrue(s.contains("numTemporalLayers = HornLogic.CAM_TEMPORAL"))
        assertTrue(s.contains("val f = HornLogic.camMaxFps(fps)"))
    }

    /**
     * Fund 2: stehender Bildschirm - MediaProjection liefert keine neuen Bilder, das vom Knoten angeforderte
     * Schluesselbild kommt erst mit dem naechsten. Das letzte Bild wird spaetestens alle 500 ms wiederholt, mit streng
     * aufsteigendem Zeitstempel (die Wiederholung aus der SurfaceTexture traegt sonst den des Originals).
     */
    @Test
    fun screenRepeat() {
        val ms = 1_000_000L
        assertEquals(500L, CallScreen.REPEAT_MS)
        assertFalse(CallScreen.repeatDue(0, 10_000 * ms))                 // noch kein Bild: nichts wiederholen
        assertFalse(CallScreen.repeatDue(1000 * ms, 1499 * ms))
        assertTrue(CallScreen.repeatDue(1000 * ms, 1500 * ms))
        assertFalse(CallScreen.repeatDue(1000 * ms, 900 * ms))            // Uhr rueckwaerts: nie
        assertTrue(CallScreen.repeatDue(1000 * ms, 1010 * ms, 0))         // ms < 1 -> 1 ms
        // Zeitstempel: Original vor der Wiederholung, Wiederholung mit gleichem Quell-Stempel -> trotzdem aufsteigend
        var last = 0L
        val out = ArrayList<Long>()
        for (src in listOf(5000 * ms, 5000 * ms, 5000 * ms, 5200 * ms, 5200 * ms)) {
            last = CallScreen.nextTimestamp(src, last)
            out.add(last)
        }
        assertEquals(out.sorted().distinct(), out)
        assertEquals(5000 * ms, out[0])
        assertEquals(5200 * ms, out[3])
    }

    /** Fund 2 (Code): die Aufnahme laeuft ueber ScreenRepeat (forceFrame) und wird vor stopCapture angehalten */
    @Test
    fun screenRepeatWired() {
        val cm = src("CallManager.kt")
        assertTrue(cm.contains("cap.initialize(sth, app, rep)"))
        assertTrue(cm.indexOf("s.repeat.stop()") in 1 until cm.indexOf("s.capturer.stopCapture()"))
        val r = src("ScreenRepeat.kt")
        assertTrue(r.contains("surface.forceFrame()"))
        assertTrue(r.contains("CallScreen.nextTimestamp("))
    }
}
