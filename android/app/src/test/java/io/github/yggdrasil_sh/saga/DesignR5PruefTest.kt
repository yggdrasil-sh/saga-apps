package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.chat.ListPreview
import io.github.yggdrasil_sh.saga.chat.ListPreviewLogic
import io.github.yggdrasil_sh.saga.ui.common.NotifPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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

    @Test
    fun kindOnlyStoresNoText() {
        val p = ListPreview(seq = 3, upTo = 3, kind = "image", text = "Urlaub", from = "Clara")
        assertEquals("", ListPreviewLogic.forMode(p, ListPreviewLogic.Mode.KIND)!!.text)
        assertEquals("Clara", ListPreviewLogic.forMode(p, ListPreviewLogic.Mode.KIND)!!.from)
        assertEquals(p, ListPreviewLogic.forMode(p, ListPreviewLogic.Mode.TEXT))
        assertNull(ListPreviewLogic.forMode(null, ListPreviewLogic.Mode.KIND))
        val all = linkedMapOf(1L to p, 2L to ListPreview(seq = 1, text = "geheim"), 3L to ListPreview(seq = 2, kind = "missed"))
        val s = ListPreviewLogic.stripTexts(all)
        assertTrue(s.values.all { it.text.isEmpty() })
        assertEquals(listOf(1L, 2L, 3L), s.keys.toList())
        assertEquals("image", s[1L]!!.kind)
        assertSame(s, ListPreviewLogic.stripTexts(s))                          // nichts zu tun -> unveraendert
        val svc = src.resolve("chat/ChatService.kt").readText()
        assertTrue(svc.contains("ListPreviewLogic.forMode(p0, listPreviewMode.value)") && svc.contains("ListPreviewLogic.stripTexts("))
    }
}
