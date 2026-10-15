package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.chat.ChatAttach
import io.github.yggdrasil_sh.saga.chat.quietly
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.io.InputStream

/** Regressionstests der Bugfix-Runde nach dem Bughunt (Android, Chat) */
class BugfixChatTest {
    // BUG-003 / BUG-066: Nachladefehler (IOException) nach chat.message bzw. nach dem Senden wird abgefangen
    @Test
    fun quietlySwallowsNetworkErrors() = runTest {
        assertFalse(quietly { throw IOException("unexpected end of stream") })
        assertTrue(quietly { })
    }

    @Test
    fun quietlyPassesCancellation() = runTest {
        try {
            quietly { throw CancellationException("weg") }
            fail("Abbruch muss weitergereicht werden")
        } catch (_: CancellationException) {
        }
    }

    // BUG-005: grosse Datei -> "zu gross", ohne sie ganz einzulesen
    @Test
    fun declaredSizeOverLimitIsTooBig() {
        val max = 10L * 1048576
        assertTrue(ChatAttach.tooBig(600L * 1048576, max))
        assertFalse(ChatAttach.tooBig(max, max))
        assertFalse(ChatAttach.tooBig(null, max))
    }

    @Test
    fun readLimitedStopsEarly() {
        val max = 1000L
        var served = 0L
        // "endlose" Datei: hoechstens ein Puffer ueber das Limit hinaus darf gelesen werden
        val endless = object : InputStream() {
            override fun read(): Int = 0.also { served++ }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                served += len
                return len
            }
        }
        assertNull(ChatAttach.readLimited(endless, max))
        assertTrue("gelesen: $served", served <= max + 64 * 1024)
        val small = ByteArray(1000) { it.toByte() }
        assertArrayEquals(small, ChatAttach.readLimited(small.inputStream(), max))
        assertEquals(0, ChatAttach.readLimited(ByteArray(0).inputStream(), max)!!.size)
        assertNull(ChatAttach.readLimited(ByteArray(1001).inputStream(), max))
    }
}
