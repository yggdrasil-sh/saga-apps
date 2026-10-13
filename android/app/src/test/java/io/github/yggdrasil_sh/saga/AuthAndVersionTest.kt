package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.auth.PairLink
import io.github.yggdrasil_sh.saga.auth.PairTarget
import io.github.yggdrasil_sh.saga.auth.Pkce
import io.github.yggdrasil_sh.saga.util.ServerUrl
import io.github.yggdrasil_sh.saga.util.Versions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PkceTest {
    @Test
    fun rfc7636Example() {
        // Beispiel aus RFC 7636, Anhang B
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", Pkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
    }

    @Test
    fun createdPairFitsServerRules() {
        val p = Pkce.create()
        assertTrue(p.verifier.length in 43..128)
        assertEquals(43, p.challenge.length)                               // Server: [A-Za-z0-9_-]{43}
        assertTrue(Regex("[A-Za-z0-9_-]{43}").matches(p.challenge))
        assertTrue(Regex("[A-Za-z0-9_-]+").matches(p.verifier))
        assertEquals(Pkce.challenge(p.verifier), p.challenge)
        assertNotEquals(p.verifier, Pkce.create().verifier)
    }
}

class PairLinkTest {
    private val code = "AbCdEfGhIjKlMnOpQrStUvWx_-12"

    @Test
    fun httpsLink() {
        assertEquals(PairTarget("https://saga.example.org", code), PairLink.parse("https://saga.example.org/app/pair?c=$code"))
        assertEquals(PairTarget("https://saga.example.org:8443", code), PairLink.parse("  https://saga.example.org:8443/app/pair?c=$code\n"))
        assertEquals(PairTarget("https://example.org/saga", code), PairLink.parse("https://example.org/saga/app/pair?c=$code"))
    }

    @Test
    fun appLink() {
        val u = "https%3A%2F%2Fsaga.example.org"
        assertEquals(PairTarget("https://saga.example.org", code), PairLink.parse("saga://pair?u=$u&c=$code"))
        assertEquals(PairTarget("https://saga.example.org", code), PairLink.parse("hlidskjalf://pair?u=$u&c=$code"))
        assertEquals(PairTarget("https://saga.example.org", code), PairLink.parse("saga://pair?c=$code&u=https://saga.example.org/"))
    }

    @Test
    fun rejectsOtherCodes() {
        assertNull(PairLink.parse("https://saga.example.org/app/pair?c=kurz"))
        assertNull(PairLink.parse("https://saga.example.org/anders?c=$code"))
        assertNull(PairLink.parse("https://saga.example.org/app/pair"))
        assertNull(PairLink.parse("saga://pair?c=$code"))
        assertNull(PairLink.parse("saga://pair?u=ftp://x.org&c=$code"))
        assertNull(PairLink.parse("saga://auth?u=https://x.org&c=$code"))
        assertNull(PairLink.parse("andere://pair?u=https://x.org&c=$code"))
        assertNull(PairLink.parse("Hallo Welt"))
        assertNull(PairLink.parse(""))
        assertNull(PairLink.parse("https://saga.example.org/app/pair?c=${code}%3Cscript%3E"))
    }
}

class VersionsTest {
    @Test
    fun compare() {
        assertTrue(Versions.isLess("1.2.0", "1.10.0"))
        assertFalse(Versions.isLess("1.10.0", "1.2.0"))
        assertFalse(Versions.isLess("1.0.0", "1.0.0"))
        assertTrue(Versions.isLess("1.0", "1.0.1"))
        assertFalse(Versions.isLess("1.0.0-debug", "1.0.0"))              // Zusaetze zaehlen nicht
        assertTrue(Versions.isLess("0.9.9-beta", "1.0.0"))
        assertFalse(Versions.isLess("v2.0.0", "1.99.99"))
        assertTrue(Versions.isLess("", "0.0.1"))
        assertFalse(Versions.isLess("1.0.0-testing.ab12cd3", "1.0.0"))
        assertEquals(listOf(1, 2, 0), Versions.parts("1.2"))
    }
}

class ServerUrlTest {
    @Test
    fun normalize() {
        assertEquals("https://saga.example.org", ServerUrl.normalize("saga.example.org"))
        assertEquals("https://saga.example.org", ServerUrl.normalize(" https://saga.example.org/ "))
        assertEquals("https://example.org/saga", ServerUrl.normalize("https://example.org/saga/"))
        assertNull(ServerUrl.normalize("http://saga.example.org"))
        assertNull(ServerUrl.normalize(""))
        assertNull(ServerUrl.normalize("https://"))
    }

    @Test
    fun absolute() {
        assertEquals("https://s.org/files/a", ServerUrl.absolute("https://s.org", "/files/a"))
        assertEquals("https://s.org/api/notes", ServerUrl.absolute("https://s.org/", "api/notes"))
        assertEquals("https://x.org/b", ServerUrl.absolute("https://s.org", "https://x.org/b"))
    }
}
