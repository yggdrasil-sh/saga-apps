package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.push.NotifySig
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** BUG-110: saga_url nur mit gueltiger Signatur dieser Installation (fremde Apps koennen keine erzeugen) */
class BugfixNotifyTest {
    private val key = ByteArray(32) { it.toByte() }

    @Test
    fun onlySignedUrlsAreAccepted() {
        val sig = NotifySig.sign(key, "/notes#1307")
        assertTrue(NotifySig.check(key, "/notes#1307", sig))
        assertFalse(NotifySig.check(key, "/notes#1307", null))                // am start --es saga_url ...
        assertFalse(NotifySig.check(key, "/notes#1308", sig))                 // andere Adresse
        assertFalse(NotifySig.check(ByteArray(32), "/notes#1307", sig))       // anderer Schluessel
        assertArrayEquals(key, NotifySig.unhex(NotifySig.hex(key)))
    }
}
