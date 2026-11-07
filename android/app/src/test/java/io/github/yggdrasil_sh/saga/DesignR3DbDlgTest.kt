package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.db.DbFilter
import io.github.yggdrasil_sh.saga.db.DbOption
import io.github.yggdrasil_sh.saga.db.DbProp
import io.github.yggdrasil_sh.saga.db.DbSort
import io.github.yggdrasil_sh.saga.ui.database.DbDlg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

/** Android-Design-Runde 3 (AND-ED-23 bis -28): Auswahl- und Regel-Dialoge der Datenbanken */
class DesignR3DbDlgTest {
    private val status = DbProp(
        "s", "Status", "select", status = true,
        options = listOf(DbOption("o1", "Offen", "gray", "todo"), DbOption("o3", "Abgeschlossen", "green", "done")),
    )
    private val groups = mapOf("open" to "Nicht erledigt", "done" to "Erledigt")
    private fun value(p: DbProp, f: DbFilter) = DbDlg.filterValue(p, f, { groups[it] ?: it }, { "TAG($it)" })

    @Test fun regelImKlartext() {
        val f = DbFilter("s", "is_not", "o3")
        assertEquals("Abgeschlossen", value(status, f))
        assertEquals("Status · ist nicht · Abgeschlossen", DbDlg.filterHeadline("Status", "ist nicht", value(status, f)))
    }

    @Test fun regelOhneWertHatZweiTeile() {
        val f = DbFilter("s", "is_empty")
        assertNull(value(status, f))
        assertEquals("Status · ist leer", DbDlg.filterHeadline("Status", "ist leer", value(status, f)))
    }

    @Test fun fehlenderWertAlsPlatzhalter() {
        val name = DbProp("title", "Name", "text")
        assertEquals("Name · enthält · …", DbDlg.filterHeadline("Name", "enthält", value(name, DbFilter("title", "contains", " "))))
        assertEquals("Projekt", value(name, DbFilter("title", "contains", " Projekt ")))
    }

    @Test fun statusGruppeDatumUndUnbekannteOption() {
        assertEquals("Erledigt", value(status, DbFilter("s", "status_group", "done")))
        assertEquals(DbDlg.MISSING, value(status, DbFilter("s", "status_group", "")))
        // geloeschte Option: Kennung statt nichts
        assertEquals("o9", value(status, DbFilter("s", "is", "o9")))
        val due = DbProp("d", "Fällig", "date")
        assertEquals("TAG(2026-11-07)", value(due, DbFilter("d", "before", "2026-11-07")))
        assertEquals("7", value(due, DbFilter("d", "next_days", "7")))
        assertNull(value(due, DbFilter("d", "today")))
    }

    @Test fun personUndRelationOhneWert() {
        assertNull(value(DbProp("p", "Zuständig", "person"), DbFilter("p", "contains", "u:1")))
        assertNull(value(DbProp("r", "Projekt", "relation"), DbFilter("r", "contains", "12")))
    }

    @Test fun sortierKopf() {
        assertEquals("Datum · Absteigend", DbDlg.sortHeadline("Datum", "Absteigend"))
        assertEquals("Absteigend", DbDlg.sortHeadline(" ", "Absteigend"))
    }

    @Test fun aenderungenErkennen() {
        assertFalse(DbDlg.changed(listOf("a", "b"), listOf("b", "a")))
        assertTrue(DbDlg.changed(listOf("a"), listOf("a", "b")))
        assertFalse(DbDlg.changed(emptyList<String>(), emptyList()))
        // Regeln: Reihenfolge zaehlt (erste Sortierung gewinnt)
        assertTrue(DbDlg.rulesChanged(listOf(DbSort("a"), DbSort("b")), listOf(DbSort("b"), DbSort("a"))))
        assertFalse(DbDlg.rulesChanged(listOf(DbFilter("s", "is", "o1")), listOf(DbFilter("s", "is", "o1"))))
        assertTrue(DbDlg.rulesChanged(listOf(DbFilter("s", "is", "o1")), listOf(DbFilter("s", "is", "o3"))))
    }

    @Test fun schnellwahl() {
        val sat = LocalDate.of(2026, 11, 7)
        assertEquals(sat, DbDlg.quickDay(DbDlg.Quick.TODAY, sat))
        assertEquals(LocalDate.of(2026, 11, 8), DbDlg.quickDay(DbDlg.Quick.TOMORROW, sat))
        assertEquals(LocalDate.of(2026, 11, 9), DbDlg.quickDay(DbDlg.Quick.NEXT_WEEK, sat))
        // Montag -> Montag der naechsten Woche (nicht derselbe Tag)
        assertEquals(LocalDate.of(2026, 11, 16), DbDlg.quickDay(DbDlg.Quick.NEXT_WEEK, LocalDate.of(2026, 11, 9)))
        // Jahreswechsel
        assertEquals(LocalDate.of(2027, 1, 1), DbDlg.quickDay(DbDlg.Quick.TOMORROW, LocalDate.of(2026, 12, 31)))
    }

    @Test fun endeWandertMit() {
        val (s, e) = DbDlg.moveStart(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 4), LocalDate.of(2026, 11, 9))
        assertEquals(LocalDate.of(2026, 11, 9), s)
        assertEquals(LocalDate.of(2026, 11, 11), e)
        assertNull(DbDlg.moveStart(LocalDate.of(2026, 11, 2), null, LocalDate.of(2026, 11, 9)).second)
    }

    @Test fun zeitraumPruefen() {
        val d = LocalDate.of(2026, 11, 7)
        assertTrue(DbDlg.rangeValid(d, null, null, null))
        assertFalse(DbDlg.rangeValid(d, null, d.minusDays(1), null))
        assertFalse(DbDlg.rangeValid(d, 600, d, 540))
        assertTrue(DbDlg.rangeValid(d, 540, d, 600))
        assertTrue(DbDlg.rangeValid(d, 600, d.plusDays(1), 540))
    }

    // ---- Quelltext: eine Regel fuer alle Auswahl-Dialoge

    private val src: File = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        .resolve("io/github/yggdrasil_sh/saga")
    private fun read(rel: String) = src.resolve(rel).readText()

    @Test fun ausserhalbTippenSpeichertNichtMehr() {
        val cells = read("ui/database/DbCells.kt")
        assertFalse(cells.contains("onDismissRequest = { if (multi) onSet(cur)"))
        assertTrue(Regex("onDismissRequest = \\{ discard\\.dismiss\\(").findAll(cells).count() >= 2)
        assertTrue(read("ui/database/DbCells25.kt").contains("discard.dismiss(changed"))
        // Leeren links, rechts Abbrechen/Uebernehmen - ueberall dieselbe Knopfzeile
        val parts = read("ui/database/DbDlgParts.kt")
        assertTrue(parts.contains("Arrangement.SpaceBetween"))
        assertTrue(read("ui/tasks/TaskParts.kt").contains("destructive = true"))
    }

    @Test fun optionszeilenMitRadioUndCheckbox() {
        val parts = read("ui/database/DbDlgParts.kt")
        assertTrue(parts.contains("toggleable(value = selected, role = Role.Checkbox"))
        assertTrue(parts.contains("selectable(selected = selected, role = Role.RadioButton"))
        assertTrue(parts.contains("heightIn(min = 56.dp)"))
        // keine eigene Beschreibung mehr ("Aktiv, ")
        assertFalse(read("ui/database/DbCells.kt").contains("o.name + if (on) \", \""))
    }

    @Test fun filterUndSortierenAlsSheet() {
        val d = read("ui/database/DbDialogs.kt")
        assertTrue(d.contains("FilterSheet(") && d.contains("SortSheet("))
        assertFalse(d.contains("private fun FilterDialog"))
        val r = read("ui/database/DbDlgRules.kt")
        assertTrue(r.contains("ModalBottomSheet(") && r.contains("OutlinedCard(") && r.contains("DbDlgDropdown("))
        assertTrue(read("ui/database/DbDlgParts.kt").contains("ExposedDropdownMenuBox("))
    }

    @Test fun datumDirektImKalender() {
        val c = read("ui/database/DbCells.kt")
        assertTrue(c.contains("\"date\" -> DbDlgDateDialog("))
        assertFalse(c.contains("LegacyDateDialog"))
        val d = read("ui/database/DbDlgDate.kt")
        assertTrue(d.contains("DateRangePicker(") && d.contains("DbDlg.Quick.NEXT_WEEK"))
    }

    @Test fun eigenschaftszeilen48dp() {
        val c = read("ui/database/DbCells.kt")
        assertTrue(c.contains("fillMaxWidth().heightIn(min = 48.dp)"))
        assertTrue(c.contains("R.string.r3d_add_value") && c.contains("onSurfaceVariant.copy(alpha = 0.6f)"))
        assertTrue(c.contains("hoverable(src)"))
    }

    @Test fun texteInAllenSprachen() {
        val res = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }
        val keys = Regex("name=\"(r3d_[a-z_]+)\"").findAll(res.resolve("values/strings_r3dbdlg.xml").readText()).map { it.groupValues[1] }.toSet()
        assertEquals(11, keys.size)
        listOf("de", "es", "ru", "th").forEach { l ->
            val k = Regex("name=\"(r3d_[a-z_]+)\"").findAll(res.resolve("values-$l/strings_r3dbdlg.xml").readText()).map { it.groupValues[1] }.toSet()
            assertEquals(l, keys, k)
        }
    }
}
