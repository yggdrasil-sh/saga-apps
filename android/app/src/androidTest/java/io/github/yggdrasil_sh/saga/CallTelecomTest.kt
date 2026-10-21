package io.github.yggdrasil_sh.saga

import android.telecom.Connection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yggdrasil_sh.saga.call.CallTelecom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Gjallarhorn P12: Gruppenanruf als selbstverwalteter Anruf beim System (Telecom, nur eigenes Test-AVD): melden ->
 * waehlt, verbunden -> aktiv, Ende -> getrennt. Kein Ton, keine Kamera.
 */
@RunWith(AndroidJUnit4::class)
class CallTelecomTest {
    @Test
    fun selfManagedGroupCall() {
        val ins = InstrumentationRegistry.getInstrumentation()
        val ctx = ins.targetContext
        ins.runOnMainSync { CallTelecom.start(ctx, "Testgruppe", "0123456789abcdef0123456789abcdef", false) }
        var c = CallTelecom.conn
        val t0 = System.currentTimeMillis()
        while (c == null && System.currentTimeMillis() - t0 < 10_000) {
            Thread.sleep(100)
            c = CallTelecom.conn
        }
        assertNotNull("Telecom hat die Verbindung angelegt", c)
        ins.runOnMainSync { CallTelecom.active() }
        Thread.sleep(300)
        assertEquals(Connection.STATE_ACTIVE, c!!.state)
        ins.runOnMainSync { CallTelecom.end() }
        Thread.sleep(300)
        assertNull(CallTelecom.conn)
        assertEquals(Connection.STATE_DISCONNECTED, c.state)
    }
}
