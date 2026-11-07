package io.github.yggdrasil_sh.saga

import androidx.compose.ui.graphics.toArgb
import io.github.yggdrasil_sh.saga.ui.database.OptPalette
import io.github.yggdrasil_sh.saga.ui.theme.SagaNight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** A11Y-03/A11Y-20: Options-Chips - alle neun Farben hell und dunkel mit Kontrast >= 4,5:1 (WCAG 2.1 AA) */
class OptPaletteTest {
    /** relative Leuchtdichte nach WCAG 2.1 */
    private fun luminance(argb: Long): Double {
        fun ch(s: Int): Double {
            val v = ((argb shr s) and 0xFF).toDouble() / 255.0
            return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * ch(16) + 0.7152 * ch(8) + 0.0722 * ch(0)
    }

    private fun contrast(a: Long, b: Long): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    @Test
    fun kontrastformel() {
        assertEquals(21.0, contrast(0xFF000000L, 0xFFFFFFFFL), 0.01)
        assertEquals(1.0, contrast(0xFF777777L, 0xFF777777L), 0.0001)
    }

    @Test
    fun alleFarbenHellUndDunkelAA() {
        assertEquals(9, OptPalette.NAMES.size)
        for (dark in listOf(false, true)) {
            for (c in OptPalette.NAMES) {
                val bg = OptPalette.bgArgb(c, dark)
                assertEquals("Flaeche $c deckend", 0xFF, ((bg shr 24) and 0xFF).toInt())
                val r = contrast(OptPalette.textArgb(c, dark), bg)
                assertTrue("$c ${if (dark) "dunkel" else "hell"}: $r", r >= 4.5)
            }
        }
    }

    @Test
    fun wieImWeb() {
        // hell: feste --bc-* aus tokens.css, Text --tc-*
        assertEquals(0xFFEBEAE7L, OptPalette.bgArgb("gray", false))
        assertEquals(0xFFF8EFC8L, OptPalette.bgArgb("yellow", false))
        assertEquals(0xFF7F6200L, OptPalette.textArgb("yellow", false))
        // dunkel: --tc-* auf 30 % Grundton ueber dem Seitengrund
        assertEquals(0xFFE6C54FL, OptPalette.textArgb("yellow", true))
        assertEquals(SagaNight.toArgb() and 0xFFFFFF, OptPalette.DARK_GROUND)
        // unbekannt/leer -> Grau
        assertEquals(OptPalette.bgArgb("gray", true), OptPalette.bgArgb(null, true))
        assertEquals(OptPalette.textArgb("gray", false), OptPalette.textArgb("tuerkis", false))
    }
}
