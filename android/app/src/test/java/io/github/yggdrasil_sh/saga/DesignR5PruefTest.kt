package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.ui.common.NotifPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pruefer-Auflagen Design-Runde 5: Mitteilungs-Frage (Liste/Anruf), "Nur Art" ohne gespeicherten Text */
class DesignR5PruefTest {
    private val src = listOf(java.io.File("src/main/java"), java.io.File("app/src/main/java")).first { it.isDirectory }
        .resolve("io/github/yggdrasil_sh/saga")

    @Test
    fun notifPromptAlsoInListAndOnCall() {
        assertFalse(NotifPrompt.listMoment(loaded = false, chats = 3))
        assertFalse(NotifPrompt.listMoment(loaded = true, chats = 0))          // nie ueber dem leeren Zustand
        assertTrue(NotifPrompt.listMoment(loaded = true, chats = 1))
        assertTrue(NotifPrompt.forCall(33, granted = false, askedBefore = false))
        assertFalse(NotifPrompt.forCall(33, granted = false, askedBefore = true))   // einmal fragen
        assertFalse(NotifPrompt.forCall(32, granted = false, askedBefore = false))
        assertFalse(NotifPrompt.forCall(34, granted = true, askedBefore = false))
        assertTrue(src.resolve("ui/chat/ChatsScreen.kt").readText().contains("NotifPrompt.listMoment("))
        val conv = src.resolve("ui/chat/Conversation.kt").readText()
        assertTrue(conv.contains("CallPermissions.missing(context, video, notifications = askNotif)"))
    }

}
