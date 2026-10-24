package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.ui.settings.DisplayName
import io.github.yggdrasil_sh.saga.util.PersonName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Bughunt D01: lesbarer Ersatz statt Kennung, Anzeigename abfragen */
class DisplayNameD01Test {
    private val h = "~s545sgsgkkyhspqz"

    @Test fun rawHandleBecomesUnnamed() {
        assertEquals(PersonName.Unnamed("spqz", null), PersonName.unnamed(h))
        assertEquals(PersonName.Unnamed("spqz", "b.example"), PersonName.unnamed("$h@b.example"))
        assertEquals(PersonName.Unnamed("spqz", "b.example"), PersonName.unnamed("", "@$h@b.example"))
        assertEquals(PersonName.Unnamed("spqz", "c.example:8443"), PersonName.unnamed(h, null, "c.example:8443"))
        assertEquals(PersonName.Unnamed("spqz", null), PersonName.unnamed("", h))
    }

    @Test fun realNamesStay() {
        assertNull(PersonName.unnamed("Anna Berg", h))
        assertNull(PersonName.unnamed("~kurz"))
        assertNull(PersonName.unnamed("", "anna"))
        // ohne Kontext (JVM-Test) bleibt der Text unveraendert - kein Absturz
        assertEquals("Anna", PersonName.shown("Anna", h))
        assertEquals("anna", PersonName.shown("", "anna", c = null))
    }

    @Test fun prefillAndBody() {
        val i = DisplayName.parse("""{"username":"anna","name":"anna","email":"a@example.org","can_edit":true}""")!!
        assertEquals("", i.name)
        assertTrue(i.canEdit)
        val j = DisplayName.parse("""{"username":"anna","name":"Anna Berg","can_edit":false}""")!!
        assertEquals("Anna Berg", j.name)
        assertFalse(j.canEdit)
        val b = DisplayName.body("  Anna  ", i)
        assertEquals("\"Anna\"", b["name"].toString())
        assertEquals("\"a@example.org\"", b["email"].toString())
        assertNull(DisplayName.body("x", j)["email"])
        assertNull(DisplayName.parse("kaputt"))
    }
}
