package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.data.buttonEntryBody
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** Schaltflaeche "Neuer Eintrag" mit Vorlage: via auch auf Seiten eines anderen Servers (negative IDs, tplbtn) */
class ButtonEntryFedTest {
    @Test
    fun viaOnRemotePage() {
        val b = buttonEntryBody(-1500417L, "", -1505537L, -1499137L)
        assertEquals(-1500417L, (b["parent_id"] as JsonPrimitive).long)
        assertEquals(-1499137L, (b["via"] as JsonPrimitive).long)
        assertFalse(b.containsKey("title"))                  // mit Vorlage ohne Titel: Titel der Vorlage bleibt
    }

    @Test
    fun viaLocalAndWithout() {
        assertEquals(12L, (buttonEntryBody(5L, "Bug", 9L, 12L)["via"] as JsonPrimitive).long)
        assertNull(buttonEntryBody(5L, "", 9L, null)["via"])
        assertNull(buttonEntryBody(5L, "", 9L, 0L)["via"])
        val leer = buttonEntryBody(-1500417L, "", null, -1499137L)          // ohne Vorlage: kein via, Titel immer
        assertNull(leer["via"])
        assertEquals("", (leer["title"] as JsonPrimitive).content)
    }
}
