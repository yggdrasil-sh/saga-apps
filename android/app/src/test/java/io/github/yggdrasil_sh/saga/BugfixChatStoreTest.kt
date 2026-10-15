package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.chat.ChatStore
import io.github.yggdrasil_sh.saga.chat.DeviceKeys
import io.github.yggdrasil_sh.saga.chat.Identity
import io.github.yggdrasil_sh.saga.chat.LinkSession
import io.github.yggdrasil_sh.saga.chat.PendingSetup
import io.github.yggdrasil_sh.saga.data.MemorySecretStore
import io.github.yggdrasil_sh.saga.data.SecureStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** BUG-022 / BUG-068: Einrichtung und Kopplung in Arbeit ueberleben Prozess-Tod (verschluesselte Ablage, je Konto) */
class BugfixChatStoreTest {
    private fun bytes(n: Int, seed: Int) = ByteArray(n) { (it * 31 + seed).toByte() }
    private val ident = Identity(bytes(32, 1), bytes(64, 2), bytes(32, 3), bytes(32, 4))
    private val dev = DeviceKeys("dev-1", bytes(32, 5), bytes(32, 6), bytes(32, 7), bytes(64, 8))

    @Test
    fun pendingSetupSurvivesNewProcess() {
        val mem = MemorySecretStore()
        val a = ChatStore(SecureStore(mem)) { "srv|konto" }
        assertNull(a.pending())
        a.savePending(PendingSetup(ident, dev, "ABCD-EFGH"))
        // "neuer Prozess": neue Objekte auf derselben Ablage
        val p = ChatStore(SecureStore(mem)) { "srv|konto" }.pending()
        assertNotNull(p)
        assertEquals("ABCD-EFGH", p!!.recoveryKey)
        assertArrayEquals(ident.identSk, p.ident.identSk)
        assertArrayEquals(ident.backupSk, p.ident.backupSk)
        assertArrayEquals(dev.signSk, p.dev.signSk)
        assertEquals("dev-1", p.dev.id)
        // anderes Konto sieht nichts; verwerfen loescht
        assertNull(ChatStore(SecureStore(mem)) { "srv|anders" }.pending())
        a.clearPending()
        assertNull(a.pending())
    }

    @Test
    fun linkSessionSurvivesNewProcess() {
        val mem = MemorySecretStore()
        val a = ChatStore(SecureStore(mem)) { "srv|konto" }
        a.saveLink(LinkSession("lnk1", "12345 67890 13579 24680", dev, 1234.5))
        val l = ChatStore(SecureStore(mem)) { "srv|konto" }.link()
        assertNotNull(l)
        assertEquals("lnk1", l!!.id)
        assertEquals("12345 67890 13579 24680", l.code)
        assertEquals(1234.5, l.expires!!, 0.0)
        assertArrayEquals(dev.encSk, l.dev.encSk)
        a.clearLink()
        assertNull(a.link())
    }
}
