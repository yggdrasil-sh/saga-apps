package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.chat.ChatFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Reine Format-Hilfen des Chats (ohne libsodium) - Werte aus tests/chat_vectors.json des Servers */
class ChatFormatTest {
    @Test
    fun lengthPrefixed() {
        assertEquals("00000001610000000262630000000000000002c3a4", ChatFormat.hex(ChatFormat.lp("a", "bc", "", "ä")))
    }

    @Test
    fun base32AndRecovery() {
        assertEquals("LNBNPIGLJL7T2DOXVOJRAT4QPHUZP77E", ChatFormat.base32(ChatFormat.unhex("5b42d7a0cb4aff3d0dd7ab93104f9079e997ffe4")))
        assertEquals("LNBNPIGLJL7T2DOXVOJRAT4QPHUZP77E", ChatFormat.normRecovery("lnbn pigl jl7t 2dox vojr at4q phuz p77e"))
        assertEquals("LNBN-PIGL-JL7T-2DOX-VOJR-AT4Q-PHUZ-P77E", ChatFormat.formatRecovery("LNBNPIGLJL7T2DOXVOJRAT4QPHUZP77E"))
        // 0->O, 1->I, 8->B
        assertEquals("OIBNPIGLJL7T2DOXVOJRAT4QPHUZP77E", ChatFormat.normRecovery("018N-PIGL-JL7T-2DOX-VOJR-AT4Q-PHUZ-P77E"))
        assertNull(ChatFormat.normRecovery("zu kurz"))
        assertNull(ChatFormat.normRecovery("LNBNPIGLJL7T2DOXVOJRAT4QPHUZP77E9"))
    }

    @Test
    fun digitBlocksAndHelpers() {
        val h = ByteArray(20) { 0xff.toByte() }
        // 0xffffffffff = 1099511627775 -> mod 100000 = 27775
        assertEquals("27775 27775 27775 27775", ChatFormat.digitBlocks(h, 4))
        assertEquals("00000", ChatFormat.digitBlocks(ByteArray(5), 1))
        assertEquals(listOf(42L, 7L), ChatFormat.noteRefs("Siehe [[note:42]] und [[note:7]] und nochmal [[note:42]]"))
        assertEquals("saga-safety:1:0123456789", ChatFormat.safetyQr("01234 56789"))
        assertEquals(true, ChatFormat.compareBytes("anna".toByteArray(), "bert".toByteArray()) < 0)
        assertEquals(null, ChatFormat.unb64("AAAA", 2))
        assertEquals(3, ChatFormat.unb64("AAAA")!!.size)
    }
}
