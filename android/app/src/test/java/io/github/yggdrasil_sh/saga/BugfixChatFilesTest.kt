package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.chat.ChatFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** BUG-023: entschluesselte Anhaenge nicht dauerhaft im Klartext-Cache, gleichnamige ueberschreiben sich nicht */
class BugfixChatFilesTest {
    private fun cache() = Files.createTempDirectory("saga-cache").toFile()

    @Test
    fun sameNameDoesNotOverwrite() {
        val c = cache()
        val a = ChatFiles.write(c, "bericht.pdf", byteArrayOf(1))
        val b = ChatFiles.write(c, "bericht.pdf", byteArrayOf(2))
        assertNotEquals(a.parentFile, b.parentFile)
        assertEquals(1.toByte(), a.readBytes()[0])
        assertEquals(2.toByte(), b.readBytes()[0])
        assertEquals("bericht.pdf", a.name)
        assertEquals("_.._etc_passwd", ChatFiles.safeName("/../etc/passwd"))
        c.deleteRecursively()
    }

    @Test
    fun removeAndPurge() {
        val c = cache()
        val a = ChatFiles.write(c, "a.txt", "geheim".toByteArray())
        ChatFiles.remove(a)
        assertFalse(a.exists())
        assertFalse(a.parentFile!!.exists())
        assertTrue(ChatFiles.dir(c).exists())                          // nur der Ordner dieses Anhangs
        val old = ChatFiles.write(c, "alt.txt", byteArrayOf(1))
        old.parentFile!!.setLastModified(1_000)
        val fresh = ChatFiles.write(c, "neu.txt", byteArrayOf(1))
        ChatFiles.purgeOlderThan(c, now = System.currentTimeMillis())
        assertFalse(old.exists())
        assertTrue(fresh.exists())
        // Abmelden / App-Start: alles weg
        ChatFiles.purge(c)
        assertFalse(fresh.exists())
        assertFalse(ChatFiles.dir(c).exists())
        c.deleteRecursively()
    }
}
