package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.blocks.ButtonRights
import io.github.yggdrasil_sh.saga.blocks.ColumnsChrome
import io.github.yggdrasil_sh.saga.blocks.DbProps
import io.github.yggdrasil_sh.saga.blocks.NumFormat
import io.github.yggdrasil_sh.saga.blocks.PageWidgets
import io.github.yggdrasil_sh.saga.db.DbFilter
import io.github.yggdrasil_sh.saga.db.DbQuery
import io.github.yggdrasil_sh.saga.db.DbRow
import io.github.yggdrasil_sh.saga.db.DbSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

/** Fix-Runde 27.10. (Galerie-Testrunde): #10 Formate, #11 Anlege-Schaltflaechen, #14 Spalten-Leisten, Relationsfilter */
class Fix27AndroidTest {
    private fun o(s: String) = Json.parseToJsonElement(s).jsonObject
    private val de = Locale.GERMANY
    private val en = Locale.US

    // ---------------------------------------------------------------- #10 Formate

    @Test fun percentIsWhole() {
        assertEquals("41 %", NumFormat.percent(41.0377, de))           // Leseliste: Regal-Karte
        assertEquals("43 %", NumFormat.percent(42.5, de))              // kaufmaennisch
        assertEquals("119 %", NumFormat.percent(119.2083, de))
        assertEquals("0 %", NumFormat.percent(-0.2, de))
        assertEquals("1.250 %", NumFormat.percent(1250.0, de))
        assertEquals("41 %", NumFormat.prop(41.0377, "percent", en))
    }

    @Test fun euroAlwaysTwoDecimals() {
        assertEquals("1.430,50 €", NumFormat.euro(1430.5, de).replace(' ', ' '))
        assertEquals("12,00 €", NumFormat.euro(12.0, de).replace(' ', ' '))
        assertEquals("€1,430.50", NumFormat.euro(1430.5, en))
        assertEquals("€0.13", NumFormat.euro(0.125, en))
    }

    @Test fun calcAndNumberUseFormat() {
        val schema = o(
            """{"props":[{"id":"s","name":"Seite","type":"number"},{"id":"n","name":"Seiten","type":"number"},
            {"id":"f","name":"Fortschritt","type":"calc","a":"s","b":"n","op":"pct","fmt":"percent"},
            {"id":"k","name":"Kosten","type":"number","fmt":"euro"},{"id":"q","name":"Quote","type":"calc","a":"s","b":"n","op":"div"}]}""",
        )
        val props = o("""{"s":87,"n":212,"k":1430.5}""")
        val f = DbProps.fields(schema, props, number = DbProps.localNum(de), locale = de).associate { it.name to it.value.replace(' ', ' ') }
        assertEquals("41 %", f["Fortschritt"])                          // vorher "41,0377 %"
        assertEquals("1.430,50 €", f["Kosten"])
        assertEquals("0,4104", f["Quote"])                               // ohne Format: Zahl wie bisher
    }

    @Test fun widgetFormats() {
        assertEquals("42%", PageWidgets.format(41.6, "pct", en))
        assertEquals("€1,200.00", PageWidgets.format(1200.0, "eur", en))
        assertEquals("1.430,5", PageWidgets.format(1430.5, "num", de))
    }

    @Test fun progressOver100IsShownAsComputed() {
        val p = PageWidgets.Progress(1430.5, 1200.0)                     // Reisebudget
        assertEquals(119, p.percent)
        assertEquals(1f, p.fraction)                                      // Balken voll
        assertEquals(0, PageWidgets.Progress(5.0, 0.0).percent)
        assertEquals(0, NumFormat.progressPercent(-3.0, 10.0))
    }

    // ---------------------------------------------------------------- #11 Anlege-Schaltflaechen

    @Test fun createButtonsGreyedForReaders() {
        assertTrue(ButtonRights.usable("page", true, false))             // Seite oeffnen geht immer
        assertFalse(ButtonRights.usable("db", true, false))
        assertFalse(ButtonRights.usable("task", false, false))
        assertTrue(ButtonRights.usable("db", true, true))
        assertFalse(ButtonRights.usable("db", false, true))              // ohne Ziel nie
        assertTrue(ButtonRights.readOnlyHint(listOf("page" to true, "db" to true), canEdit = false))
        assertFalse(ButtonRights.readOnlyHint(listOf("page" to true), canEdit = false))
        assertFalse(ButtonRights.readOnlyHint(listOf("db" to false), canEdit = false))   // kaputte Schaltflaeche: kein Rechte-Hinweis
        assertFalse(ButtonRights.readOnlyHint(listOf("db" to true), canEdit = true))
    }

    // ---------------------------------------------------------------- #14 Spalten-Leisten

    @Test fun columnControlsOnlyWhileWritingInside() {
        val block = listOf<Any>("blocks", 3)
        val inside = listOf<Any>("blocks", 3, "data", "cols", 1, "blocks", 0)
        val other = listOf<Any>("blocks", 4)
        assertFalse(ColumnsChrome.showBar(true, null, block))
        assertFalse(ColumnsChrome.showBar(true, other, block))
        assertFalse(ColumnsChrome.showBar(true, block, block))            // der Block selbst ist kein Feld darin
        assertTrue(ColumnsChrome.showBar(true, inside, block))
        assertFalse(ColumnsChrome.showBar(false, inside, block))          // Lesen/Kommentieren: nie
        assertTrue(ColumnsChrome.showColumnControls(true, null, block, columnEmpty = true))
        assertFalse(ColumnsChrome.showColumnControls(true, null, block, columnEmpty = false))
        assertFalse(ColumnsChrome.showColumnControls(false, null, block, columnEmpty = true))
    }

    // ---------------------------------------------------------------- Relationsfilter (Server 82e26d6)

    private val schema = DbSchema(o("""{"props":[{"id":"proj","name":"Projekt","type":"relation","target":9}]}"""))
    private val q = DbQuery(schema, LocalDate.of(2026, 10, 27), ZoneOffset.UTC) { null }
    private fun row(id: Long, props: String) = DbRow(id, "R$id", props = o(props), created = 0.0, updated = 0.0)
    private val rows = listOf(
        row(1, """{"proj":[5]}"""), row(2, """{}"""), row(3, """{"proj":null}"""), row(4, """{"proj":[5,6]}"""),
        row(5, """{"proj":"5"}"""), row(6, """{"proj":[]}"""), row(7, """{"proj":[6]}"""),
    )

    @Test fun relationFilterMissingIsEmptyList() {
        fun ids(op: String) = q.filter(rows, listOf(DbFilter("proj", op, "5"))).map { it.id }
        assertEquals(listOf(1L, 4L), ids("contains"))                    // Einzelwert "5" zaehlt nicht (wie Server)
        assertEquals(listOf(2L, 3L, 5L, 6L, 7L), ids("not_contains"))     // ohne Verknuepfung: gehoert dazu, kein Absturz
        assertEquals(listOf(2L, 3L, 6L), q.filter(rows, listOf(DbFilter("proj", "is_empty"))).map { it.id })
    }
}
