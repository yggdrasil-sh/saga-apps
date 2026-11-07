package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.ui.database.DbCompact
import org.junit.Assert.assertEquals
import org.junit.Test

/** Nachbesserung R3 (80b): Scroll-Ende liegt auf einer Spaltenkante - keine Luecke zwischen sichtbaren Spalten */
class DesignR3EndPadTest {
    @Test fun endeLiegtAufSpaltenkante() {
        val w = listOf(150f, 170f, 230f, 300f)              // 850 breit
        val vp = 600f
        val pad = DbCompact.endPad(w, vp)
        // ab Kante 320 passen 230 + 300 = 530 ins Fenster -> 70 Leerraum, Scroll-Ende = 850 + 70 - 600 = 320
        assertEquals(70f, pad, 0.01f)
        val max = w.sum() + pad - vp
        assertEquals(320f, max, 0.01f)
        // am Ende sind die sichtbaren Spalten ganz und lueckenlos ab dem linken Rand
        assertEquals(320f to 850f, DbCompact.wholeColumns(w, max, vp))
        // der Pfeil landet am Ende genau auf dieser Kante (vorher: 250, mitten in der zweiten Spalte)
        assertEquals(320f, DbCompact.nextColumnScroll(w, 0f, vp, max))
    }

    @Test fun keinLeerraumWennAllesPasstOderGenauAufKante() {
        assertEquals(0f, DbCompact.endPad(listOf(100f, 100f), 300f), 0.01f)
        assertEquals(0f, DbCompact.endPad(listOf(100f, 100f, 100f), 200f), 0.01f)   // Ende 100 = Kante
        assertEquals(0f, DbCompact.endPad(listOf(100f), 0f), 0.01f)
        // letzte Spalte breiter als das Fenster: wie bisher ganzes Fenster sichtbar, kein Leerraum
        assertEquals(0f, DbCompact.endPad(listOf(96f, 96f, 96f, 190f), 131f), 0.01f)
    }
}
