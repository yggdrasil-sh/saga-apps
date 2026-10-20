package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.call.CallRoom
import io.github.yggdrasil_sh.saga.chat.ChatLogic
import io.github.yggdrasil_sh.saga.chat.ChatPerson
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2.4 Testrunde: W4 (Handles statt Anmeldenamen anderer Konten) und W9 (waehrend des Anrufs gekoppeltes Geraet) */
class HandlesTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun memberRefIsRead() {
        val m = json.decodeFromString(ChatPerson.serializer(), """{"username":"anna","ref":"~aaaaaaaaaaaaaaaa","name":"Anna Berg","me":false}""")
        assertEquals("~aaaaaaaaaaaaaaaa", m.ref)
        assertEquals("Anna Berg", ChatLogic.shown(m))
        assertEquals("~aaaaaaaaaaaaaaaa", ChatLogic.shown(m.copy(name = "")))        // ohne Namen: Handle, nie der Anmeldename
        assertEquals("bert", ChatLogic.shown(ChatPerson(username = "bert")))           // aelterer Server: Rueckfall
    }

    @Test fun searchHitMatchesMemberByRef() {
        val mem = listOf(ChatPerson(username = "anna", ref = "~aaaaaaaaaaaaaaaa"), ChatPerson(username = "bert"))
        assertTrue(ChatLogic.isMember("~aaaaaaaaaaaaaaaa", mem))
        assertTrue(ChatLogic.isMember("bert", mem))                                     // aelterer Server: Benutzername
        assertFalse(ChatLogic.isMember("~bbbbbbbbbbbbbbbb", mem))
        assertFalse(ChatLogic.isMember("", mem))
    }

    @Test fun deviceCoupledDuringCallFetchesKeysOnce() {
        assertTrue(CallRoom.needsFreshKeys("c0de", setOf("0e32")))
        assertFalse(CallRoom.needsFreshKeys("0e32", setOf("0e32")))
        assertFalse(CallRoom.needsFreshKeys("", emptySet()))
    }
}
