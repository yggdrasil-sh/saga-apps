package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.auth.BrowserReturn
import io.github.yggdrasil_sh.saga.auth.PairSwitch
import io.github.yggdrasil_sh.saga.data.StdCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Nachtest Runde 2: Kopplungs-Text (N1), Rueckkehr aus dem Browser (P7), Standard-Kategorien (K3) */
class LoginFlowTest {

    // ---------------------------------------------------------------- N1

    @Test
    fun pairSameServerNamesAccountInsteadOfHost() {
        assertEquals(
            PairSwitch.SameServer("Alex Beispiel"),
            PairSwitch.of(true, "http://127.0.0.1:18090", "http://127.0.0.1:18090", "Alex Beispiel"),
        )
        // Name unbekannt -> Text ohne Namen
        assertEquals(PairSwitch.SameServer(""), PairSwitch.of(true, "https://saga.example.org", "https://saga.example.org/", "  "))
    }

    @Test
    fun pairOtherServerShowsOldHost() {
        assertEquals(
            PairSwitch.OtherServer("127.0.0.1:18090"),
            PairSwitch.of(true, "http://127.0.0.1:18090", "https://boese.example.org", "Alex"),
        )
        // anderer Port oder Unterpfad = anderer Server
        assertTrue(PairSwitch.of(true, "http://127.0.0.1:18090", "http://127.0.0.1:18091", "A") is PairSwitch.OtherServer)
        assertTrue(PairSwitch.of(true, "https://h.example.org/saga", "https://h.example.org", "A") is PairSwitch.OtherServer)
    }

    @Test
    fun pairNotSignedInNoWarning() {
        assertEquals(PairSwitch.None, PairSwitch.of(false, "https://a.example.org", "https://a.example.org", "A"))
        assertEquals(PairSwitch.None, PairSwitch.of(true, "", "https://a.example.org", "A"))
    }

    @Test
    fun sameServerNormalizesCaseDefaultPortAndSlash() {
        assertTrue(PairSwitch.sameServer("https://Saga.Example.org", "https://saga.example.org:443/"))
        assertTrue(PairSwitch.sameServer("https://h.example.org/saga/", "https://h.example.org/saga"))
        assertFalse(PairSwitch.sameServer("http://saga.example.org", "https://saga.example.org"))
        assertFalse(PairSwitch.sameServer("kaputt", "kaputt"))
    }

    // ---------------------------------------------------------------- P7

    @Test
    fun browserReturnClassified() {
        assertEquals(BrowserReturn.Code("abc"), BrowserReturn.of("abc", null))
        assertEquals(BrowserReturn.Code("abc"), BrowserReturn.of(" abc ", "cancelled"))   // Code hat Vorrang
        assertEquals(BrowserReturn.Cancelled, BrowserReturn.of(null, "cancelled"))
        assertEquals(BrowserReturn.Cancelled, BrowserReturn.of("", "access_denied"))
        assertEquals(BrowserReturn.NoCode, BrowserReturn.of(null, null))
        assertEquals(BrowserReturn.NoCode, BrowserReturn.of("  ", ""))
        assertEquals(BrowserReturn.Failed("expired"), BrowserReturn.of(null, "expired"))
    }

    // ---------------------------------------------------------------- K3

    private val app = mapOf(R.string.category_services to "Services", R.string.category_bookmarks to "Bookmarks")
    private fun show(name: String) = StdCategory.display(name) { app.getValue(it) }

    @Test
    fun standardCategoriesFollowAppLanguage() {
        assertEquals("Bookmarks", show("Lesezeichen"))
        assertEquals("Bookmarks", show("Marcadores"))
        assertEquals("Bookmarks", show("Bookmarks"))
        assertEquals("Services", show("Dienste"))
        assertEquals("Services", show("Servicios"))
        assertEquals(StdCategory.BOOKMARKS, StdCategory.of(" Lesezeichen "))
    }

    @Test
    fun ownCategoriesUnchanged() {
        assertEquals("Lesezeichen privat", show("Lesezeichen privat"))
        assertEquals("lesezeichen", show("lesezeichen"))       // nur die exakten Standardnamen wie der Server
        assertEquals("Medien", show("Medien"))
        assertEquals("", show(""))
        assertNull(StdCategory.of("Eingang"))
    }
}
