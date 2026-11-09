package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.blocks.WidgetDesign
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Widget-Nacharbeit (9.11.): Wetter-Symbole, Countdown wie Web, Kontextzeile mit Quelle, Diagramm-Tooltip, Spaltenbreiten */
class WidgetNacharbeitTest {
    // ---------------------------------------------------------------- Wetter-Symbole (Nacharbeit 9)

    @Test
    fun wetterSymbolWieWeb() {
        // Web static/pages.js wxKind: 0 Sonne, 1-2 teils, 3/4x Wolke, 45/48 Nebel, 5x Niesel, 6x Regen, 7x Schnee, 80-82 Schauer, 85/86 Schnee, 9x Gewitter
        val want = mapOf(
            0 to "sun", 1 to "part", 2 to "part", 3 to "cloud", 44 to "cloud", 45 to "fog", 48 to "fog", 51 to "drizzle", 57 to "drizzle",
            61 to "rain", 66 to "rain", 67 to "rain", 71 to "snow", 77 to "snow", 80 to "rain", 82 to "rain", 85 to "snow", 86 to "snow",
            95 to "storm", 99 to "storm",
        )
        want.forEach { (code, kind) -> assertEquals("WMO $code", kind, WidgetDesign.wxKind(code)) }
        assertNull(WidgetDesign.wxKind(null))
        assertEquals("sun", WidgetDesign.wxKind(-1))
    }

    @Test
    fun wetterSymboleHabenPfade() {
        val kinds = (0..99).mapNotNull { WidgetDesign.wxKind(it) }.toSet()
        val path = Regex("""^[MmLlHhVvCcSsQqTtAaZz0-9.,\s\-]+$""")
        kinds.forEach { k ->
            val parts = WidgetDesign.WX_SVG[k]
            assertTrue("Symbol $k fehlt", !parts.isNullOrEmpty())
            parts!!.forEach { assertTrue("$k: ${it.path}", path.matches(it.path) && it.path.startsWith("M")) }
        }
        // Sonne und Blitz in Akzent, Regen blau, Wolke in Schriftfarbe
        assertTrue(WidgetDesign.WX_SVG.getValue("sun").all { it.layer == WidgetDesign.WxLayer.ACCENT })
        assertTrue(WidgetDesign.WX_SVG.getValue("rain").any { it.layer == WidgetDesign.WxLayer.WET })
        assertTrue(WidgetDesign.WX_SVG.getValue("storm").any { it.layer == WidgetDesign.WxLayer.ACCENT })
        assertEquals(listOf(WidgetDesign.WxLayer.FG), WidgetDesign.WX_SVG.getValue("cloud").map { it.layer })
        assertTrue(WidgetDesign.FLAME.startsWith("M12 2") && WidgetDesign.FLAME.endsWith("z"))
    }
}
