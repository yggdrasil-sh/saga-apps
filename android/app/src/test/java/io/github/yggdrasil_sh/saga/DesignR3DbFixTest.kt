package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.ui.database.DbCompact
import io.github.yggdrasil_sh.saga.ui.database.DbDlg
import io.github.yggdrasil_sh.saga.ui.database.DbDlg.Edge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

/** Nachbesserung der Android-Design-Runde 3 (Datenbank-Ansichten und -Dialoge) */
class DesignR3DbFixTest {
    private val src: File = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        .resolve("io/github/yggdrasil_sh/saga")
    private fun read(rel: String) = src.resolve(rel).readText()
    private val res: File = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }

    // ---- 28c/29/80: nur ganze Spalten, Pfeil bis zur naechsten Kante

    @Test fun nurGanzSichtbareSpalten() {
        val w = listOf(96f, 96f, 96f, 190f)
        // Fenster 131 breit am Anfang: Status (0-96) ganz, Start (96-192) angeschnitten -> nur bis 96
        assertEquals(0f to 96f, DbCompact.wholeColumns(w, 0f, 131f))
        // eingerastet auf 96: Start ganz (96-192), Zieltermin angeschnitten
        assertEquals(96f to 192f, DbCompact.wholeColumns(w, 96f, 131f))
        // am Ende (max = 478 - 131 = 347): links angeschnitten (Zieltermin 192-288) -> nur die letzte Spalte? 190 > 131
        // passt nicht ganz: das ganze Fenster bleibt sichtbar
        assertEquals(347f to 478f, DbCompact.wholeColumns(w, 347f, 131f))
        // breites Fenster: alle ganz
        assertEquals(0f to 478f, DbCompact.wholeColumns(w, 0f, 600f))
        // links und rechts angeschnitten: nur die mittlere
        assertEquals(96f to 192f, DbCompact.wholeColumns(w, 50f, 200f))
    }

    @Test fun pfeilScrolltZurNaechstenSpaltenkante() {
        val w = listOf(96f, 96f, 96f, 190f)
        assertEquals(96f, DbCompact.nextColumnScroll(w, 0f, 131f, 347f))      // Start steht danach ganz links
        assertEquals(192f, DbCompact.nextColumnScroll(w, 96f, 131f, 347f))
        assertEquals(288f, DbCompact.nextColumnScroll(w, 192f, 131f, 347f))
        assertEquals(347f, DbCompact.nextColumnScroll(w, 288f, 131f, 347f))  // hoechstens bis zum Ende
        assertEquals(347f, DbCompact.nextColumnScroll(w, 347f, 131f, 347f))
        val d = read("ui/database/DatabaseView.kt")
        assertTrue(d.contains("wholeColumns(st.hScroll, movingW)"))
        assertFalse(d.contains("animateScrollBy(240f)"))
    }

    @Test fun relationenEinzeiligOhneSilbentrennung() {
        assertEquals(1, DbCompact.cellLines("relation", wraps = false))
        assertEquals(2, DbCompact.cellLines("text", wraps = false))
        assertEquals(8, DbCompact.cellLines("relation", wraps = true))
        val c = read("ui/database/DbCells.kt")
        assertTrue(c.contains("hyphens = androidx.compose.ui.text.style.Hyphens.None"))
        assertTrue(c.contains("LineBreak.Simple"))
        assertFalse(c.contains("TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)"))
    }

    // ---- 36c: Zeitraum im selben Kalender

    private val d2 = LocalDate.of(2026, 11, 2)
    private val d5 = LocalDate.of(2026, 11, 5)

    @Test fun zeitraumStartUndEnde() {
        // Ende setzen
        assertEquals(d2 to d5, DbDlg.pickRange(d2, d2, Edge.END, d5))
        // Start nach vorne: Ende bleibt
        assertEquals(LocalDate.of(2026, 11, 1) to d5, DbDlg.pickRange(d2, d5, Edge.START, LocalDate.of(2026, 11, 1)))
        // Start hinter das Ende: Ende wandert mit (Ende >= Start)
        assertEquals(LocalDate.of(2026, 11, 9) to LocalDate.of(2026, 11, 9), DbDlg.pickRange(d2, d5, Edge.START, LocalDate.of(2026, 11, 9)))
        // Ende vor den Start: ein Tag
        assertEquals(LocalDate.of(2026, 10, 30) to LocalDate.of(2026, 10, 30), DbDlg.pickRange(d2, d5, Edge.END, LocalDate.of(2026, 10, 30)))
        // immer gueltig
        listOf(Edge.START, Edge.END).forEach { e ->
            (0L..10L).forEach { i ->
                val (s, en) = DbDlg.pickRange(d2, d5, e, d2.minusDays(3).plusDays(i))
                assertTrue(DbDlg.rangeValid(s, null, en, null))
            }
        }
        assertEquals(d2, DbDlg.shownDay(d2, d5, Edge.START))
        assertEquals(d5, DbDlg.shownDay(d2, d5, Edge.END))
    }

    @Test fun datumsdialogKompakt() {
        val d = read("ui/database/DbDlgDate.kt")
        assertTrue(d.contains("DateIntervalFormat"))                       // "2.–5. Nov. 2026" (Halbgeviertstrich aus ICU)
        assertTrue(d.contains("horizontalScroll(quickScroll)"))              // Schnellwahl in einer Zeile
        assertTrue(d.contains("quickScroll.value < quickScroll.maxValue"))  // Nachbesserung R3 (78): weicher Rand
        assertFalse(d.contains("leadingIcon = { SagaIcon(SagaIcons.calendar"))
    }

    // ---- 78/35b: Knopfzeile

    @Test fun knopftexteBrechenNichtUm() {
        val p = read("ui/database/DbDlgParts.kt")
        assertTrue(p.contains("maxLines = 1, softWrap = false"))
        assertTrue(p.contains("Hyphens.None"))
        assertTrue(p.contains("FlowRow("))
        assertTrue(p.contains("SagaFilledTonalButton(onClick = onConfirm"))
        // 35: eigene Optionszeile statt ListItem (16 dp Einzug)
        assertFalse(p.contains("ListItem("))
    }

    // ---- leichte Funde

    @Test fun werkzeugzeileMenueBoardKalender() {
        val d = read("ui/database/DatabaseView.kt")
        assertTrue(d.contains("val leftTools = pills && !wide"))                              // 28/30
        assertTrue(d.contains("DropdownMenu(expanded = viewMenu, onDismissRequest = { viewMenu = false }, content = viewMenuItems)")) // 28b
        assertTrue(d.contains("AsTableMenuItem(st.asTable(v.id)) { st.setAsTable(v.id, it) }"))   // Menue bleibt offen
        assertTrue(d.contains("leadingIcon = { MenuIconSlot() }"))
        assertTrue(d.contains("R.plurals.r3d_cal_entries"))                                   // 31b
        assertTrue(read("ui/database/DbCells25.kt").contains("selectedContainerColor = accent.goldSoft"))   // 35b
        val r = read("ui/database/DbDlgRules.kt")
        assertTrue(r.contains("padding(start = 4.dp, end = 16.dp"))                           // 32
        assertTrue(r.contains("focus.clearFocus()"))                                          // 82
    }

    @Test fun pluralInAllenSprachen() {
        listOf("", "-de", "-es", "-ru", "-th").forEach { l ->
            val x = res.resolve("values$l/strings_r3dbdlg.xml").readText()
            assertTrue(l, x.contains("<plurals name=\"r3d_cal_entries\">"))
            assertTrue(l, x.contains("quantity=\"other\""))
        }
    }
}
