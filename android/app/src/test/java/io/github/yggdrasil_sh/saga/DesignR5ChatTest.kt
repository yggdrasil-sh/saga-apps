package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.chat.ChatSummary
import io.github.yggdrasil_sh.saga.chat.NewChatLogic
import io.github.yggdrasil_sh.saga.chat.NewChatLogic.Empty
import io.github.yggdrasil_sh.saga.chat.NewChatLogic.Mode
import io.github.yggdrasil_sh.saga.chat.PeopleGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Design-Runde 5 (r5-chat): Chat-Oberflaeche - Neuer Chat, Vorschau in der Liste, Chat-Info, Menues */
class DesignR5ChatTest {

    // ---------------------------------------------------------------- CH-06 Neuer Chat

    @Test
    fun newChatEmptyStates() {
        assertEquals(Empty.NONE, NewChatLogic.empty("", 3, null, 0, false))
        assertEquals(Empty.NONE, NewChatLogic.empty("  ", 1, "  ", 0, false))
        // aeltere Server: Regel "ab 3 Zeichen" nur als Leerzustand
        assertEquals(Empty.TOO_SHORT, NewChatLogic.empty("an", 3, "an", 0, false))
        assertEquals(Empty.NONE, NewChatLogic.empty("an", 1, null, 0, false))          // Antwort steht noch aus
        assertEquals(Empty.NO_HITS, NewChatLogic.empty("an", 1, "an", 0, false))
        assertEquals(Empty.NONE, NewChatLogic.empty("anna", 1, "ann", 0, false))       // Antwort zu altem Stand
        assertEquals(Empty.NONE, NewChatLogic.empty("a@b.example", 1, "a@b.example", 0, true))  // name@server-Hinweis statt "Niemand"
        assertEquals(Empty.NONE, NewChatLogic.empty("anna", 1, "anna", 2, false))
    }

    @Test
    fun newChatFixedEntriesAndCreate() {
        assertTrue(NewChatLogic.showFixed(Mode.PERSON, ""))
        assertFalse(NewChatLogic.showFixed(Mode.PERSON, "a"))
        assertFalse(NewChatLogic.showFixed(Mode.GROUP, ""))
        assertFalse(NewChatLogic.showFixed(Mode.TEAM, ""))
        assertFalse(NewChatLogic.canCreate(Mode.GROUP, 0, 0))
        assertTrue(NewChatLogic.canCreate(Mode.GROUP, 0, 1))      // auch nur Personen anderer Server
        assertTrue(NewChatLogic.canCreate(Mode.GROUP, 2, 0))
        assertFalse(NewChatLogic.canCreate(Mode.PERSON, 2, 0))
    }

    @Test
    fun newChatTeams() {
        val chats = listOf(
            ChatSummary(1, kind = "group", group = "Vertrieb"), ChatSummary(2, kind = "group", group = "bau"),
            ChatSummary(3, kind = "dm"), ChatSummary(4, kind = "group", group = ""),
        )
        assertEquals(listOf("bau", "Vertrieb"), NewChatLogic.teams(chats, emptyList(), ""))
        assertEquals(listOf("Einkauf", "Vertrieb"), NewChatLogic.teams(chats, listOf(PeopleGroup("Vertrieb"), PeopleGroup("Einkauf")), "e"))
        assertEquals(emptyList<String>(), NewChatLogic.teams(emptyList(), emptyList(), "x"))
    }
}
