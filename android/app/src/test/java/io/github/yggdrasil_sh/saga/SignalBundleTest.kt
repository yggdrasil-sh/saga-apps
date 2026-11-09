package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.call.SignalBundle
import io.github.yggdrasil_sh.saga.call.SignalBundle.Reply
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2.5 (9.11.) Raum-Signal-Buendel nach einer Uebergabe (APP-API "Uebergabe": dropped bzw. 400 eines aelteren Servers) */
class SignalBundleTest {
    @Test
    fun antwort() {
        assertEquals(Reply.Ok(emptyList()), SignalBundle.reply(200, """{"ok":true,"n":2}"""))
        assertEquals(Reply.Ok(listOf("aa")), SignalBundle.reply(200, """{"ok":true,"n":1,"dropped":["aa",5,""]}"""))
        assertEquals(Reply.Ok(emptyList()), SignalBundle.reply(200, ""))                 // aeltere Server: leerer/anderer Text
        assertEquals(Reply.Rejected, SignalBundle.reply(400, """{"error":"x"}"""))
        assertEquals(Reply.Gone, SignalBundle.reply(409, null))
        assertEquals(Reply.Other, SignalBundle.reply(null, null))
        assertEquals(Reply.Other, SignalBundle.reply(500, null))
    }

    @Test
    fun erneutNurSchluesselUndZustandAnVerbliebene() {
        val b = mapOf("a" to """{"t":"fkey","n":1}""", "b" to """{"t":"state","mic":true}""", "c" to """{"t":"offer"}""", "d" to "kein json")
        val r = SignalBundle.resendable(b)
        assertEquals(setOf("a", "b"), r.keys)
        val t = SignalBundle.targets(r + ("me" to """{"t":"state"}"""), live = setOf("b", "me"), here = "me")
        assertEquals(setOf("b"), t.keys)                                                  // gegangenes Geraet faellt weg, nie an sich selbst
    }

    @Test
    fun listeHoechstensAlleZweiSekunden() {
        assertTrue(SignalBundle.pullDue(0, 100))
        assertFalse(SignalBundle.pullDue(100, 1500))
        assertTrue(SignalBundle.pullDue(100, 2100))
    }
}
