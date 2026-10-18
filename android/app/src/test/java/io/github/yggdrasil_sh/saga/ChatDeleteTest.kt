package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.chat.ChatBlobCache
import io.github.yggdrasil_sh.saga.chat.ChatDeleteLogic
import io.github.yggdrasil_sh.saga.chat.ChatDeleteResult
import io.github.yggdrasil_sh.saga.chat.ChatList
import io.github.yggdrasil_sh.saga.chat.ChatSummary
import io.github.yggdrasil_sh.saga.chat.ChatWipe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** 2.1: Chats archivieren und loeschen (docs/APP-API-CHAT.md) */
class ChatDeleteTest {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false; isLenient = true }

    private fun obj(s: String) = Json.parseToJsonElement(s) as JsonObject

    @Test fun newFieldsAndOldServer() {
        val l = json.decodeFromString(
            ChatList.serializer(),
            """{"chats":[{"id":1,"kind":"dm","archived":false,"archived_at":null,"cleared_seq":0,"delete_for_all":"chat","unread":2},
               {"id":2,"kind":"group","archived":true,"archived_at":1700000000,"cleared_seq":812,"delete_for_all":null,"unread":5},
               {"id":3,"kind":"page","archived":true,"delete_for_all":"history","unread":1,"muted":true}],"unread":2}""",
        )
        assertEquals(false, l.chats[0].archived)
        assertEquals("chat", l.chats[0].deleteForAll)
        assertEquals(812L, l.chats[1].clearedSeq)
        assertNull(l.chats[1].deleteForAll)
        assertTrue(ChatDeleteLogic.supported(l.chats))
        // aelterer Server: Felder fehlen -> Funktionen aus, nichts gilt als archiviert
        val old = json.decodeFromString(ChatList.serializer(), """{"chats":[{"id":7,"kind":"dm","unread":3}]}""")
        assertNull(old.chats[0].archived)
        assertFalse(ChatDeleteLogic.supported(old.chats))
        assertFalse(ChatDeleteLogic.supported(old.chats[0]))
        assertFalse(ChatDeleteLogic.supported(null as ChatSummary?))
        assertEquals(1, ChatDeleteLogic.split(old.chats).first.size)
        assertEquals(0L, old.chats[0].clearedSeq)
    }

    @Test fun splitAndCounters() {
        val list = listOf(
            ChatSummary(1, unread = 2, archived = false),
            ChatSummary(2, unread = 5, archived = true),
            ChatSummary(3, unread = 1, archived = true, muted = true),
            ChatSummary(4, unread = 4, muted = true, archived = false),
        )
        val (active, archived) = ChatDeleteLogic.split(list)
        assertEquals(listOf(1L, 4L), active.map { it.id })
        assertEquals(listOf(2L, 3L), archived.map { it.id })
        assertEquals(5, ChatDeleteLogic.archivedUnread(list))       // stummgeschaltete zaehlen nicht
        assertEquals(2, ChatDeleteLogic.unreadTotal(list))          // ohne Archiv und Stummgeschaltete
    }

    @Test fun deleteOptions() {
        val dm = ChatSummary(1, kind = "dm", deleteForAll = "chat", archived = false)
        val free = ChatSummary(2, kind = "free", deleteForAll = null, archived = false)
        val freeAdmin = free.copy(deleteForAll = "chat")
        val group = ChatSummary(3, kind = "group", deleteForAll = null, archived = false)
        val page = ChatSummary(4, kind = "page", deleteForAll = "history", archived = false)
        assertEquals("chat", ChatDeleteLogic.forAll(dm))
        assertNull(ChatDeleteLogic.forAllReason(dm))
        assertEquals(R.string.chatdel_reason_free, ChatDeleteLogic.forAllReason(free))
        assertEquals(R.string.chatdel_reason_group, ChatDeleteLogic.forAllReason(group))
        assertEquals(R.string.chatdel_reason_page, ChatDeleteLogic.forAllReason(page.copy(deleteForAll = null)))
        assertNull(ChatDeleteLogic.forAll(dm.copy(deleteForAll = "kaputt")))      // unbekannter Wert = nicht erlaubt
        // "Gruppe außerdem verlassen" nur bei freien Gruppen und nur "für mich"
        assertTrue(ChatDeleteLogic.offerLeave(free, false))
        assertFalse(ChatDeleteLogic.offerLeave(freeAdmin, true))
        assertFalse(ChatDeleteLogic.offerLeave(group, false))
        assertFalse(ChatDeleteLogic.offerLeave(dm, false))
        // Erklaerungen und Hinweise
        assertEquals(R.string.chatdel_me_dm, ChatDeleteLogic.explain(dm, false))
        assertEquals(R.string.chatdel_me_other, ChatDeleteLogic.explain(group, false))
        assertEquals(R.string.chatdel_all_dm, ChatDeleteLogic.explain(dm, true))
        assertEquals(R.string.chatdel_all_free, ChatDeleteLogic.explain(freeAdmin, true))
        assertEquals(R.string.chatdel_all_history, ChatDeleteLogic.explain(page, true))
        assertEquals(R.string.chatdel_done_me, ChatDeleteLogic.doneText(dm, false, false))
        assertEquals(R.string.chatdel_done_me_archived, ChatDeleteLogic.doneText(group, false, false))
        assertEquals(R.string.chatdel_done_left, ChatDeleteLogic.doneText(free, false, true))
        assertEquals(R.string.chatdel_done_all, ChatDeleteLogic.doneText(dm, true, false))
        assertEquals(R.string.chatdel_done_all_history, ChatDeleteLogic.doneText(page, true, false))
    }

    @Test fun deleteResponses() {
        val me = json.decodeFromString(
            ChatDeleteResult.serializer(),
            """{"ok": true, "scope": "me", "removed": false, "cleared_seq": 812, "archived": true, "hidden": false}""",
        )
        assertEquals(812L, me.clearedSeq)
        assertTrue(me.archived)
        val all = json.decodeFromString(ChatDeleteResult.serializer(), """{"ok": true, "scope": "all", "removed": true}""")
        assertTrue(all.removed)
        val hist = json.decodeFromString(ChatDeleteResult.serializer(), """{"ok": true, "scope": "all", "removed": false, "deleted": 42}""")
        assertEquals(42, hist.deleted)
    }

    @Test fun liveEvents() {
        // Chat fuer alle geloescht - von jemand anderem: Hinweis; von mir: keiner
        val r = ChatDeleteLogic.parse("chat.removed", obj("""{"chat_id":5,"kind":"dm","by":{"username":"bob","name":"Bob B."}}"""), "alice")
        assertEquals(ChatWipe.Removed(5, "Bob B.", false), r)
        assertEquals(R.string.chatdel_by_chat, ChatDeleteLogic.noticeFor(r!!))
        assertTrue(ChatDeleteLogic.closes(r, "free"))
        val mine = ChatDeleteLogic.parse("chat.removed", obj("""{"chat_id":5,"kind":"dm","by":{"username":"alice","name":"A"}}"""), "alice")!!
        assertNull(ChatDeleteLogic.noticeFor(mine))
        // Verlauf fuer alle geloescht: alles weg, Hinweis, Chat bleibt offen
        val all = ChatDeleteLogic.parse("chat.cleared", obj("""{"chat_id":6,"scope":"all","by":{"username":"carol","name":""}}"""), "alice")
        assertEquals(ChatWipe.Cleared(6, true, Long.MAX_VALUE, "carol", false), all)    // leerer Name -> Benutzername
        assertEquals(R.string.chatdel_by_history, ChatDeleteLogic.noticeFor(all!!))
        assertFalse(ChatDeleteLogic.closes(all, "group"))
        // anderes eigenes Geraet: bis cleared_seq; Direkt-Chat schliessen, andere nicht; kein Hinweis
        val me = ChatDeleteLogic.parse("chat.cleared", obj("""{"chat_id":7,"scope":"me","cleared_seq":812}"""), "alice")!!
        assertEquals(ChatWipe.Cleared(7, false, 812, null, true), me)
        assertNull(ChatDeleteLogic.noticeFor(me))
        assertTrue(ChatDeleteLogic.closes(me, "dm"))
        assertFalse(ChatDeleteLogic.closes(me, "free"))
        // fremde/kaputte Ereignisse
        assertNull(ChatDeleteLogic.parse("chat.message", obj("""{"chat_id":7}"""), "alice"))
        assertNull(ChatDeleteLogic.parse("chat.removed", obj("""{"kind":"dm"}"""), "alice"))
        assertEquals(true, ChatDeleteLogic.archivedOf(obj("""{"chat_id":1,"archived":true}""")))
        assertNull(ChatDeleteLogic.archivedOf(obj("""{"chat_id":1}""")))
    }

    @Test fun keepAfterClearedSeq() {
        val seqs = listOf(1L, 5L, 9L, 12L)
        assertEquals(listOf(9L, 12L), ChatDeleteLogic.keepAfter(seqs, 5) { it })
        assertEquals(seqs, ChatDeleteLogic.keepAfter(seqs, 0) { it })
        assertEquals(emptyList<Long>(), ChatDeleteLogic.keepAfter(seqs, Long.MAX_VALUE) { it })
        assertEquals("chat-42", ChatDeleteLogic.notificationTag(42))
    }

    @Test fun blobCacheClearChat() {
        val dir = Files.createTempDirectory("blobs").toFile()
        val cache = ChatBlobCache(dir)
        val a1 = ChatBlobCache.key(1, "fileA")
        val a2 = ChatBlobCache.key(1, "self-fileB")
        val b1 = ChatBlobCache.key(12, "fileC")                 // c12_ darf nicht von c1_ erfasst werden
        listOf(a1, a2, b1, "legacyId").forEach { cache.put(it, ByteArray(10)) }
        assertEquals("c1_fileA", a1)
        cache.clearChat(ChatDeleteLogic.blobPrefix(1))
        assertNull(cache.get(a1))
        assertNull(cache.get(a2))
        assertNull(cache.get("legacyId"))                      // altes Format: keinem Chat zuzuordnen -> weg
        assertTrue(cache.get(b1) != null)
        assertEquals(10L, cache.size())
    }
}
