package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.ui.database.RowSheetLogic
import io.github.yggdrasil_sh.saga.ui.today.TodayLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Android-Design-Runde 6 "Tablet" (r6-inhalt): Heute zweispaltig (A11Y-23), Zeile als Seitenblatt (A11Y-24/ED-27) */
class DesignR6InhaltTest {
    // ---------------------------------------------------------------- A11Y-23 Heute
    @Test fun todayTwoColumnsFrom840() {
        assertFalse(TodayLogic.twoColumns(839.9f))
        assertTrue(TodayLogic.twoColumns(840f))
        assertTrue(TodayLogic.twoColumns(1280f))
        assertFalse(TodayLogic.twoColumns(600f))
    }

    @Test fun todayContentWidth() {
        // einspaltig: hoechstens die Lesebreite, schmaler = verfuegbare Breite
        assertEquals(360f, TodayLogic.contentWidthDp(360f), 0.01f)
        assertEquals(760f, TodayLogic.contentWidthDp(800f), 0.01f)
        // zweispaltig: 2 x (480 + 2 x 16) = 1024, darunter die verfuegbare Breite
        assertEquals(1024f, 2 * TodayLogic.COLUMN_BOX_DP, 0.01f)
        assertEquals(840f, TodayLogic.contentWidthDp(840f), 0.01f)
        assertEquals(1024f, TodayLogic.contentWidthDp(1400f), 0.01f)
    }

    @Test fun todayQuickAddIndexTwoColumns() {
        // Kopf, (Weitermachen), Spalten-Eintrag
        assertEquals(1, TodayLogic.quickAddIndex(false, 5, twoColumns = true))
        assertEquals(2, TodayLogic.quickAddIndex(true, 0, twoColumns = true))
        // einspaltig unveraendert: Kopf + Termin-Kopf + max(1, n) Termine + Aufgaben-Kopf
        assertEquals(4, TodayLogic.quickAddIndex(false, 0))
        assertEquals(7, TodayLogic.quickAddIndex(true, 3))
    }

    // ---------------------------------------------------------------- A11Y-24/ED-27 Seitenblatt
    @Test fun sheetOnlyExpanded() {
        assertFalse(RowSheetLogic.enabled(390f))       // Telefon
        assertFalse(RowSheetLogic.enabled(700f))       // 600-840: Vollbild wie bisher
        assertFalse(RowSheetLogic.enabled(839f))
        assertTrue(RowSheetLogic.enabled(840f))
    }

    @Test fun sheetWidthClamped() {
        assertEquals(480f, RowSheetLogic.sheetWidthDp(840f), 0.01f)
        assertEquals(480f, RowSheetLogic.sheetWidthDp(980f), 0.01f)
        assertEquals(540f, RowSheetLogic.sheetWidthDp(1200f), 0.01f)
        assertEquals(560f, RowSheetLogic.sheetWidthDp(2000f), 0.01f)
        // links bleibt bei 840 dp genug Ansicht
        assertTrue(840f - RowSheetLogic.sheetWidthDp(840f) >= 280f)
    }

    @Test fun neighborInViewOrder() {
        val ids = listOf(5L, 3L, 9L, 1L)                // Reihenfolge der Ansicht, nicht nach ID
        assertEquals(9L, RowSheetLogic.neighbor(ids, 3L, 1))
        assertEquals(5L, RowSheetLogic.neighbor(ids, 3L, -1))
        // Raender
        assertNull(RowSheetLogic.neighbor(ids, 5L, -1))
        assertNull(RowSheetLogic.neighbor(ids, 1L, 1))
        // Zeile weggefiltert / nicht geladen: keine Pfeile
        assertNull(RowSheetLogic.neighbor(ids, 42L, 1))
        assertNull(RowSheetLogic.neighbor(ids, 42L, -1))
        assertNull(RowSheetLogic.neighbor(emptyList(), 1L, 1))
        // einzige Zeile
        assertNull(RowSheetLogic.neighbor(listOf(7L), 7L, 1))
        assertNull(RowSheetLogic.neighbor(listOf(7L), 7L, -1))
    }

    @Test fun neighborFilteredList() {
        // Filter blendet 3 aus: von 5 geht es direkt zu 9
        val all = listOf(5L, 3L, 9L)
        val filtered = all.filter { it != 3L }
        assertEquals(9L, RowSheetLogic.neighbor(filtered, 5L, 1))
        assertEquals(5L, RowSheetLogic.neighbor(filtered, 9L, -1))
    }

    @Test fun orderPerViewType() {
        val rows = listOf(1L, 2L, 3L)
        assertEquals(rows, RowSheetLogic.order("table", rows, emptyList(), emptyMap(), emptyList()))
        assertEquals(rows, RowSheetLogic.order(null, rows, emptyList(), emptyMap(), emptyList()))
        // Board: Spalten in Spalten-Reihenfolge, "Ohne" (null) zuerst, wenn es so in den Schluesseln steht
        val cols = mapOf<String?, List<Long>>("b" to listOf(20L, 21L), null to listOf(10L), "a" to listOf(30L))
        assertEquals(listOf(10L, 30L, 20L, 21L), RowSheetLogic.order("board", rows, listOf(null, "a", "b"), cols, emptyList()))
        // Kalender: Eintraege des Monats
        assertEquals(listOf(8L, 4L), RowSheetLogic.order("calendar", rows, emptyList(), emptyMap(), listOf(8L, 4L)))
    }

    @Test fun backClosesSheetFirst() {
        assertEquals(RowSheetLogic.Back.CLOSE_SHEET, RowSheetLogic.back(sheetOpen = true))
        assertEquals(RowSheetLogic.Back.NAVIGATION, RowSheetLogic.back(sheetOpen = false))
    }

    @Test fun narrowOpensAsPage() {
        assertEquals(RowSheetLogic.Narrow.OPEN_AS_PAGE, RowSheetLogic.onNarrow(12L, enabled = false))
        assertEquals(RowSheetLogic.Narrow.NOTHING, RowSheetLogic.onNarrow(12L, enabled = true))
        assertEquals(RowSheetLogic.Narrow.NOTHING, RowSheetLogic.onNarrow(null, enabled = false))
    }

    // ---------------------------------------------------------------- Texte
    @Test fun stringsInAllLanguages() {
        val res = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }
        val re = Regex("<string name=\"([^\"]+)\">([^<]*)</string>")
        fun load(dir: String) = re.findAll(File(res, "$dir/strings_r6inhalt.xml").readText()).associate { it.groupValues[1] to it.groupValues[2] }
        val base = load("values")
        assertEquals(5, base.size)
        for (d in listOf("values-de", "values-es", "values-ru", "values-th")) {
            val l = load(d)
            assertEquals(d, base.keys, l.keys)
            assertTrue(d, l.values.all { it.isNotBlank() })
        }
        assertEquals("Vorige Zeile", load("values-de")["r6_sheet_prev"])
        assertEquals("In voller Größe öffnen", load("values-de")["r6_sheet_open_full"])
    }
}
