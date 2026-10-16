package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.blocks.DbProp
import io.github.yggdrasil_sh.saga.blocks.DbQuery
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class DbQueryTest {
    private val sample = """{"schema":{"props":[""" +
        """{"id":"s","name":"Status","type":"select","options":[{"id":"o1","name":"Offen","color":"red"},{"id":"o2","name":"Fertig","color":"green"}]},""" +
        """{"id":"d","name":"Datum","type":"date"},""" +
        """{"id":"n","name":"Zahl","type":"number"},""" +
        """{"id":"c","name":"Erledigt","type":"checkbox"},""" +
        """{"id":"t","name":"Tags","type":"multi_select","options":[{"id":"x","name":"X"}]},""" +
        """{"id":"q","name":"Quote","type":"calc","a":"n","b":"n2","op":"pct"}""" +
        """],"views":[""" +
        """{"id":"v1","name":"Offen","type":"list","filters":[{"pid":"s","op":"is","value":"o1"}],"sorts":[{"pid":"n","dir":"desc"}],"colors":[{"pid":"c","op":"checked","color":"green"}]},""" +
        """{"id":"v2","type":"board","group_by":"s","sorts":[{"pid":"title","dir":"asc"}]}""" +
        """]},"rows":[""" +
        """{"id":1,"title":"Beta","props":{"s":"o1","n":3,"c":true,"t":["x"],"n2":4},"created":0,"updated":0},""" +
        """{"id":2,"title":"alpha","props":{"s":"o1","n":10,"d":"2026-01-02"},"created":0,"updated":0},""" +
        """{"id":3,"title":"Gamma","props":{"s":"o2"},"created":0,"updated":0,"preview":"/files/p"},""" +
        """{"id":4,"title":"Delta","props":{"s":"o1","zukunft":{"a":1}},"created":0,"updated":0}""" +
        """],"templates":[{"id":9,"title":"Vorlage","icon":"📝"}]}"""

    private val q = DbQuery.parse(sample)!!

    @Test
    fun parsesSchemaRowsAndTemplates() {
        assertEquals(6, q.props.size)
        assertEquals(2, q.views.size)
        assertEquals(4, q.rows.size)
        assertEquals(3.0, q.rows[0].props["n"])
        assertEquals(true, q.rows[0].props["c"])
        assertEquals(listOf("x"), q.rows[0].props["t"])
        assertEquals("/files/p", q.rows[2].cover)
        assertNull(q.rows[3].props["zukunft"])             // Unbekanntes wird ignoriert, nicht abgestuerzt
        assertEquals(9, q.templates.single().id)
        assertEquals("board", q.views[1].type)
        assertEquals("s", q.views[1].groupBy)
    }

    @Test
    fun filtersAndSortsLikeTheWeb() {
        // Status ist Offen, Zahl absteigend, Leeres ans Ende
        assertEquals(listOf(2, 1, 4), q.rows(q.view("v1")).map { it.id })
        // Titel aufsteigend, ohne Beachtung der Gross-/Kleinschreibung
        assertEquals(listOf(2, 1, 4, 3), q.rows(q.view("v2")).map { it.id })
        // keine Ansicht: alles in Server-Reihenfolge
        assertEquals(listOf(1, 2, 3, 4), q.rows(null).map { it.id })
    }

    @Test
    fun conditionalColorAndDefaults() {
        val v = q.view("v1")
        assertEquals("green", q.colorName(q.rows[0], v))
        assertNull(q.colorName(q.rows[1], v))
        assertEquals(mapOf("s" to JsonPrimitive("o1")), q.defaults(v))
    }

    @Test
    fun calcProperty() {
        val p = q.prop("q")!!
        assertEquals(75.0, q.value(p, q.rows[0]) as Double, 0.0001)
        assertNull(q.value(p, q.rows[1]))                       // B fehlt
        assertNull(DbQuery.calc(DbProp("x", "", "calc", a = "a", b = "b", op = "div"), mapOf("a" to 1.0, "b" to 0.0)))
        assertEquals(3.0, DbQuery.calc(DbProp("x", "", "calc", a = "a", b = "b", op = "add"), mapOf("a" to 3.0))!!, 0.0)
    }

    @Test
    fun relativeDates() {
        val today = LocalDate.of(2026, 10, 15)                   // Donnerstag
        assertTrue(DbQuery.relDate("today", "2026-10-15", today))
        assertTrue(DbQuery.relDate("this_week", "2026-10-12", today))
        assertTrue(DbQuery.relDate("this_week", "2026-10-18T10:00", today))
        assertFalse(DbQuery.relDate("this_week", "2026-10-19", today))
        assertTrue(DbQuery.relDate("this_month", "2026-10-01", today))
        assertFalse(DbQuery.relDate("today", "kaputt", today))
    }

    @Test
    fun multiSelectDefaultsAndPlainValues() {
        val p = DbQuery.parse("""{"schema":{"props":[{"id":"t","type":"multi_select"}],"views":[{"id":"v","filters":[{"pid":"t","op":"is","value":"x"}]}]},"rows":[]}""")!!
        assertEquals(mapOf("t" to JsonArray(listOf(JsonPrimitive("x")))), p.defaults(p.view("v")))
        assertNotNull(DbQuery.parse("""{"rows":[]}"""))
        assertNull(DbQuery.parse("kein json"))
    }
}
