package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.call.CallLogic
import io.github.yggdrasil_sh.saga.ui.chat.BubbleA11y
import io.github.yggdrasil_sh.saga.ui.chat.RecoveryLast4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Android-Design-Runde 1, Chats & Anrufe (CH-02, CH-05, CH-22, A11Y-01) */
class R1ChatDesignTest {

    @Test
    fun recoveryLast4() {
        val key = "ABCDEFGHJKLMNOPQRSTUVWXYZ234567AB0I"     // normalisiert endet auf "ABOI"
        val norm = RecoveryLast4.norm(key)
        assertEquals(RecoveryLast4.EMPTY, RecoveryLast4.check("", norm, stored = true))
        assertEquals(RecoveryLast4.EMPTY, RecoveryLast4.check("ab", norm, stored = true))
        assertEquals(RecoveryLast4.WRONG, RecoveryLast4.check("ZZZZ", norm, stored = true))
        // 0/1/8 wie O/I/B, Kleinschreibung und Leerzeichen egal
        assertEquals(RecoveryLast4.OK, RecoveryLast4.check(" ab01 ", norm, stored = true))
        assertEquals(RecoveryLast4.NOT_STORED, RecoveryLast4.check("ABOI", norm, stored = false))
        assertEquals(RecoveryLast4.WRONG, RecoveryLast4.check("ABOI", "", stored = true))
    }

    @Test
    fun bubbleDescription() {
        assertEquals("Du: Hallo, 14:43, Gelesen", BubbleA11y.describe("Du", "Hallo", "14:43", listOf("Gelesen")))
        assertEquals("Bruno: Gern, um 15 Uhr?, 14:43", BubbleA11y.describe("Bruno", "Gern, um 15 Uhr?", "14:43", emptyList()))
        assertEquals("Bruno, 14:43", BubbleA11y.describe("Bruno", "", "14:43", listOf("")))
        assertEquals("Hallo, 14:43", BubbleA11y.describe("", "Hallo", "14:43", emptyList()))
        assertEquals("Foto, Schau mal", BubbleA11y.body("Foto", "Schau mal"))
        assertEquals("Foto", BubbleA11y.body("Foto", " "))
        assertEquals("", BubbleA11y.body(null, null))
    }

    @Test
    fun callEntryDirectionAndRedial() {
        val e = CallLogic.parseEntry("""{"v":1,"type":"call","call":"c0","media":"audio","status":"failed","from":"anna","to":"bert"}""")!!
        assertTrue(CallLogic.failed(e))
        assertTrue(CallLogic.redial(e, "anna"))
        assertFalse(CallLogic.redial(e, "bert"))
        assertEquals("out_failed", CallLogic.direction(e, "anna"))
        assertEquals("in", CallLogic.direction(e, "bert"))
        val ended = e.copy(status = "ended")
        assertFalse(CallLogic.failed(ended))
        assertFalse(CallLogic.redial(ended, "anna"))
        assertEquals("out", CallLogic.direction(ended, "anna"))
        assertEquals("missed", CallLogic.direction(e.copy(status = "missed"), "bert"))
    }

    @Test
    fun endScreenIdle() {
        assertEquals(30_000L, CallLogic.endDismissMs(error = true, sticky = false))
        // keine Eingabe: schliessen
        assertEquals(0L, CallLogic.endIdleLeft(30_000L, 0L, 1_000_000L))
        // Eingabe vor 10 s: noch 20 s
        assertEquals(20_000L, CallLogic.endIdleLeft(30_000L, 990_000L, 1_000_000L))
        // Eingabe vor 40 s: schliessen
        assertTrue(CallLogic.endIdleLeft(30_000L, 960_000L, 1_000_000L) <= 0)
    }
}
