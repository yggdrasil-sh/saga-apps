package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.ui.chat.ChatUi
import io.github.yggdrasil_sh.saga.ui.graph.LabelGrid
import io.github.yggdrasil_sh.saga.ui.theme.Dark
import io.github.yggdrasil_sh.saga.ui.theme.DarkAccent
import io.github.yggdrasil_sh.saga.ui.theme.Light
import io.github.yggdrasil_sh.saga.ui.theme.LightAccent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Feinschliff Graph (25.10.): Beschriftungen ohne Ueberlappung, Kontrast des Umschalters "Alle Seiten | Lokal" */
class GraphPolishTest {
    @Test
    fun labelGridErkenntUeberlappung() {
        val g = LabelGrid()
        g.reset(400f, 300f, 64f)
        assertFalse(g.hits(10f, 10f, 110f, 30f))
        g.add(10f, 10f, 110f, 30f)
        assertTrue("gleiche Stelle", g.hits(50f, 20f, 150f, 40f))
        assertFalse("daneben", g.hits(111f, 10f, 200f, 30f))
        assertFalse("darunter", g.hits(10f, 31f, 110f, 50f))
        // ueber Zellgrenzen hinweg und ausserhalb des Bildes (Rand)
        g.add(-150f, 280f, 600f, 320f)
        assertTrue(g.hits(300f, 300f, 310f, 310f))
        assertTrue(g.hits(-120f, 290f, -100f, 300f))
        // reset leert
        g.reset(400f, 300f, 64f)
        assertFalse(g.hits(50f, 20f, 150f, 40f))
        assertTrue(g.count == 0)
    }

    @Test
    fun labelGridVieleRechtecke() {
        val g = LabelGrid()
        g.reset(1080f, 2000f, 64f)
        var placed = 0
        for (i in 0 until 2000) {
            val x = (i * 37 % 1080).toFloat(); val y = (i * 53 % 2000).toFloat()
            if (!g.hits(x, y, x + 90f, y + 18f)) { g.add(x, y, x + 90f, y + 18f); placed++ }
        }
        assertTrue(placed in 50..1999)
    }

    @Test
    fun umschalterKontrast() {
        for ((s, acc, name) in listOf(Triple(Light, LightAccent, "hell"), Triple(Dark, DarkAccent, "dunkel"))) {
            // nicht gewaehlt (auch deaktiviert: onSurfaceVariant) auf dem Hintergrund
            assertTrue("Lokal nicht gewaehlt $name", ChatUi.contrast(s.onSurface, s.background) >= 4.5)
            assertTrue("Lokal deaktiviert $name", ChatUi.contrast(s.onSurfaceVariant, s.background) >= 4.5)
            assertTrue("gewaehlt $name", ChatUi.contrast(acc.onGoldSoft, acc.goldSoft) >= 4.5)
        }
    }
}
