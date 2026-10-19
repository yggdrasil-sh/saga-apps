package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.auth.LinkGate
import io.github.yggdrasil_sh.saga.auth.PairLink
import io.github.yggdrasil_sh.saga.auth.PairSwitch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Kopplungslink bei bestehender Anmeldung: immer nachfragen, auch wenn derselbe Link erneut kommt */
class LinkGateTest {
    private val link = "saga://pair?u=https%3A%2F%2Fsaga.example.org&c=abcdefghijklmnopqrstuvwxyz012345"
    private val h = LinkGate.hash(link)

    @Test
    fun `hash ist stabil und je Link verschieden`() {
        assertEquals(h, LinkGate.hash(link))
        assertNotEquals(h, LinkGate.hash(link + "6"))
        assertEquals(64, h.length)
    }

    @Test
    fun `neuer Link wird immer verarbeitet`() {
        assertTrue(LinkGate.shouldHandle("pair", h, null, newIntent = false))
        assertTrue(LinkGate.shouldHandle("pair", h, "anders", newIntent = false))
        assertTrue(LinkGate.shouldHandle("auth", h, "anders", newIntent = true))
    }

    @Test
    fun `derselbe Kopplungslink bei laufender App zeigt erneut die Rueckfrage`() {
        // z. B. Rueckfrage abgebrochen und den Link noch einmal angetippt - frueher passierte dann nichts
        assertTrue(LinkGate.shouldHandle("pair", h, h, newIntent = true))
    }

    @Test
    fun `alter Start-Intent nach Neustart der Aufgabe wird ignoriert`() {
        assertFalse(LinkGate.shouldHandle("pair", h, h, newIntent = false))
        assertFalse(LinkGate.shouldHandle("auth", h, h, newIntent = false))
    }

    @Test
    fun `Anmelde-Rueckkehr nie doppelt einloesen`() {
        assertFalse(LinkGate.shouldHandle("auth", h, h, newIntent = true))
    }

    @Test
    fun `Kopplungslink angemeldet - Hinweis je nach Server und Konto`() {
        val t = PairLink.parse(link)!!
        assertEquals("https://saga.example.org", t.server)
        assertEquals(PairSwitch.SameServer("anna"), PairSwitch.of(true, "https://SAGA.example.org/", t.server, " anna "))
        assertEquals(PairSwitch.OtherServer("alt.example.org"), PairSwitch.of(true, "https://alt.example.org", t.server, "anna"))
        assertEquals(PairSwitch.None, PairSwitch.of(false, "https://saga.example.org", t.server, "anna"))
        // ungueltige Links -> keine Rueckfrage, sondern Fehlermeldung (wie bisher)
        assertNull(PairLink.parse("saga://pair?u=https%3A%2F%2Fsaga.example.org&c=kurz"))
        assertNull(PairLink.parse("saga://pair?c=abcdefghijklmnopqrstuvwxyz012345"))
    }
}
