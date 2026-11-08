package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.push.Account
import io.github.yggdrasil_sh.saga.push.ChatShortcuts
import io.github.yggdrasil_sh.saga.push.NotifyChannels
import io.github.yggdrasil_sh.saga.push.NotifyReadReceiver
import io.github.yggdrasil_sh.saga.push.NotifyText
import io.github.yggdrasil_sh.saga.push.NotifyText.Sentence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Nachbesserung Design-Runde 4 (Mitteilungen): Saetze in der App-Sprache, Shortcuts, Konto-Kennung, Kanalgruppen */
class DesignR4NotifyFixTest {
    @Test
    fun recognizesServerSentencesInAllLanguages() {
        assertEquals(NotifyText.Parsed(Sentence.COMMENT_MENTION_FROM, "Bruno Design"),
            NotifyText.parse("comment.mention", "Bruno Design mentioned you in a comment"))
        assertEquals(NotifyText.Parsed(Sentence.COMMENT_FROM, "Bruno Design"),
            NotifyText.parse("comment.new", "Bruno Design commented on your page"))
        assertEquals(NotifyText.Parsed(Sentence.CHAT_FROM, "Bruno Design"), NotifyText.parse("chat.message", "New message from Bruno Design"))
        assertEquals(NotifyText.Parsed(Sentence.CHAT_FROM, "Ana"), NotifyText.parse("chat.message", "Mensaje nuevo de Ana"))
        assertEquals(NotifyText.Parsed(Sentence.CHAT_FROM, "Иван"), NotifyText.parse("chat.message", "Новое сообщение от Иван"))
        assertEquals(NotifyText.Parsed(Sentence.CHAT_FROM, "Somchai"), NotifyText.parse("chat.message", "ข้อความใหม่จาก Somchai"))
        assertEquals(NotifyText.Parsed(Sentence.CHAT_FROM, "Bruno"), NotifyText.parse("chat.message", "Neue Nachricht von Bruno"))
        // ohne Namen
        assertEquals(NotifyText.Parsed(Sentence.CHAT), NotifyText.parse("chat.message", "New message"))
        assertEquals(NotifyText.Parsed(Sentence.MENTION), NotifyText.parse("page.mention", "You were mentioned"))
        assertEquals(NotifyText.Parsed(Sentence.TASKS), NotifyText.parse("task.due", "Tareas para hoy o vencidas"))
    }

    @Test
    fun extractsCountsIncludingRussianPluralAndThaiOrder() {
        assertEquals(NotifyText.Parsed(Sentence.COMMENTS_N, null, 3), NotifyText.parse("comment.new", "3 new comments on your page"))
        assertEquals(NotifyText.Parsed(Sentence.COMMENTS_N, null, 5),
            NotifyText.parse("comment.new", "5 новых комментариев на твоей странице"))
        assertEquals(NotifyText.Parsed(Sentence.MISSED_N_FROM, "Bruno", 2), NotifyText.parse("call.missed", "2 missed calls from Bruno"))
        assertEquals(NotifyText.Parsed(Sentence.MISSED_N_FROM, "Bruno", 4), NotifyText.parse("call.missed", "สายที่ไม่ได้รับจาก Bruno 4 สาย"))
        assertEquals(NotifyText.Parsed(Sentence.MISSED_FROM, "Bruno"), NotifyText.parse("call.missed", "Missed call from Bruno"))
        assertEquals(NotifyText.Parsed(Sentence.MISSED_N, null, 3), NotifyText.parse("call.missed", "3 missed calls"))
        assertEquals(NotifyText.Parsed(Sentence.DB_N_FROM, "Bruno", 3), NotifyText.parse("db.assigned", "Bruno assigned you 3 times"))
        assertEquals(NotifyText.Parsed(Sentence.DB_FROM, "Bruno"), NotifyText.parse("db.assigned", "Bruno assigned you"))
    }

    @Test
    fun unknownOrForeignKindStaysServerText() {
        assertNull(NotifyText.parse("comment.new", "Something else entirely"))
        assertNull(NotifyText.parse("share.added", "Bruno shared a page with you (Edit)"))      // Rolle: Satz des Servers bleibt
        assertNull(NotifyText.parse("chat.message", "Bruno commented on your page"))           // nur Saetze der eigenen Art
        assertNull(NotifyText.parse("chat.message", ""))
        assertNull(NotifyText.parse("chat.message", "New message from "))
    }

    @Test
    fun nameMeansNamesAllowed() {
        assertTrue(NotifyText.named(NotifyText.parse("chat.message", "New message from Bruno")))
        assertFalse(NotifyText.named(NotifyText.parse("chat.message", "New message")))
        assertFalse(NotifyText.named(null))
    }

    @Test
    fun shortcutIdsPerAccountAndChat() {
        val a = Account.tag("https://saga.example.org", "anna")
        val b = Account.tag("https://saga.example.org", "bruno")
        assertEquals(12, a.length)
        assertEquals(a, Account.tag("https://saga.example.org", "anna"))
        assertNotEquals(a, b)
        assertEquals("", Account.tag("", ""))
        val id = ChatShortcuts.id(a, 42)
        assertEquals(42L, ChatShortcuts.chatOf(a, id))
        assertNull(ChatShortcuts.chatOf(b, id))
        assertNull(ChatShortcuts.chatOf(a, "other-shortcut"))
    }

    @Test
    fun markReadOnlyForSameAccount() {
        assertTrue(NotifyReadReceiver.sameAccount("abc", "abc"))
        assertFalse(NotifyReadReceiver.sameAccount("abc", "def"))
        assertFalse(NotifyReadReceiver.sameAccount(null, "abc"))
        assertFalse(NotifyReadReceiver.sameAccount("", ""))
    }

    @Test
    fun channelGroupsKeepIds() {
        assertEquals(NotifyChannels.G_NOTIFY, NotifyChannels.channelGroup(NotifyChannels.CHATS))
        assertEquals(NotifyChannels.G_NOTIFY, NotifyChannels.channelGroup("saga_push"))
        assertEquals(NotifyChannels.G_NOTIFY, NotifyChannels.channelGroup(NotifyChannels.TASKS))
        assertEquals(NotifyChannels.G_CALLS, NotifyChannels.channelGroup(NotifyChannels.CALLS))
        assertEquals(NotifyChannels.G_CALLS, NotifyChannels.channelGroup("saga_call_ring2"))
        assertEquals(NotifyChannels.G_CALLS, NotifyChannels.channelGroup("saga_call"))
        assertEquals(NotifyChannels.G_BACKGROUND, NotifyChannels.channelGroup("saga_push_stream"))
        assertNull(NotifyChannels.channelGroup("unknown"))
    }
}
