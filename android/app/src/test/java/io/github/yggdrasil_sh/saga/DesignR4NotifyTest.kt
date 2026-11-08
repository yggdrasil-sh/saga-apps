package io.github.yggdrasil_sh.saga

import android.app.NotificationManager
import io.github.yggdrasil_sh.saga.live.NotifActor
import io.github.yggdrasil_sh.saga.live.NotifChat
import io.github.yggdrasil_sh.saga.live.NotifItem
import io.github.yggdrasil_sh.saga.live.NotifList
import io.github.yggdrasil_sh.saga.push.NotifyChannels
import io.github.yggdrasil_sh.saga.push.NotifyRoute
import io.github.yggdrasil_sh.saga.ui.notifications.NotifGroups
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Design-Runde 4 (r4-notify): Glocke gebuendelt (NAV-03/22), System-Kanaele und Gelesen-Aktion (CH-04) */
class DesignR4NotifyTest {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false; isLenient = true }

    private fun msg(id: Long, chat: Long = 5, read: Boolean = false, who: String = "Bruno", created: Double = id.toDouble()) = NotifItem(
        id = id, kind = "chat.message", read = read, created = created, actor = NotifActor(who), chat = NotifChat(chat, "dm"),
        groupKey = "chat:$chat", text = "Neue Nachricht von $who", title = "Chat $chat",
    )

    @Test
    fun parsesGroupedAndOldServer() {
        val grouped = """{"items":[{"id":9,"kind":"chat.message","created":90.0,"read":false,"actor":{"name":"Bruno"},"count":1,
            "access":true,"url":"/chats#c5","chat":{"id":5,"kind":"group"},"group_key":"chat:5","ids":[9,7,4],"counts":[1,1,2],
            "first":40.0,"actors":["Bruno","Clara"],"text":"4 neue Nachrichten (von Bruno, Clara)","title":"Team"},
            {"id":8,"kind":"share.added","read":true,"count":1,"access":true,"group_key":null,"text":"x","title":"Seite"}],
            "unread":4,"more":false,"next":null,"unread_groups":1}"""
        val r = json.decodeFromString<NotifList>(grouped)
        assertEquals(1, r.unreadGroups)
        val g = r.items[0]
        assertEquals(listOf(9L, 7L, 4L), g.ids)
        assertEquals(listOf(1, 1, 2), NotifGroups.countsOf(g))
        assertEquals("chat:5", g.groupKey)
        assertEquals(listOf("Bruno", "Clara"), g.actors)
        assertEquals(40.0, g.first!!, 0.0)
        assertTrue(NotifGroups.isGroup(g))
        assertEquals(NotifGroups.Sentence.MESSAGES_FROM, NotifGroups.sentence(g))
        assertNull(NotifGroups.sentence(r.items[1]))                 // einzeln: Satz des Servers

        // alter Server (ohne ?group=1): kein unread_groups, keine ids - jede Zeile einzeln
        val old = json.decodeFromString<NotifList>("""{"items":[{"id":3,"kind":"chat.message","text":"a"},{"id":2,"kind":"chat.message","text":"b"}],"unread":2}""")
        assertNull(old.unreadGroups)
        assertEquals(2, NotifGroups.regroup(old.items).size)
        assertEquals(listOf(3L), NotifGroups.idsOf(old.items[0]))
    }

    @Test
    fun regroupSameUnitAndReadState() {
        val list = listOf(msg(10), msg(8, who = "Clara"), msg(7, read = true), msg(6, chat = 9), msg(5),
            NotifItem(id = 4, kind = "share.added", text = "s"))
        val r = NotifGroups.regroup(list)
        assertEquals(listOf(10L, 7L, 6L, 4L), r.map { NotifGroups.head(it) })
        val g = r[0]
        assertEquals(listOf(10L, 8L, 5L), g.ids)
        assertEquals(3, g.count)
        assertEquals(listOf("Bruno", "Clara"), g.actors)
        assertEquals(5.0, g.first!!, 0.0)
        assertTrue(r[1].read)                                      // gelesen bleibt eigene Zeile
        // Sentence: 1:1-Chat -> ohne Namen
        assertEquals(NotifGroups.Sentence.MESSAGES, NotifGroups.sentence(g))
        val (fresh, earlier) = NotifGroups.sections(r)
        assertEquals(3, fresh.size)
        assertEquals(1, earlier.size)
    }

    @Test
    fun liveInsertBecomesHeadAndReplaces() {
        val start = NotifGroups.regroup(listOf(msg(5), msg(3)))
        val r = NotifGroups.insert(start, msg(9, who = "Clara"), replaces = 3)
        assertEquals(1, r.size)
        assertEquals(9L, r[0].id)                                    // neuer Kopf (alle seine Felder)
        assertEquals(listOf(9L, 5L), r[0].ids)
        assertEquals(2, r[0].count)
        assertEquals("Clara", r[0].actor?.name)
        // gleiche id nochmal (Aktualisierung): nicht doppelt
        assertEquals(listOf(9L, 5L), NotifGroups.insert(r, msg(9), null)[0].ids)
        // Aeltere Mitglieder (naechste Seite) haengen sich an die vorhandene Zeile, Kopf bleibt
        val more = NotifGroups.regroup(r + listOf(msg(2)))
        assertEquals(listOf(9L, 5L, 2L), more[0].ids)
        assertEquals(9L, more[0].id)
    }

    @Test
    fun regroupDropsDuplicateIds() {
        // Nachladen ueberlappt mit frisch geladener erster Seite: einzelne und gebuendelte Zeilen nicht doppelt
        val share = NotifItem(id = 4, kind = "share.added")
        val page1 = NotifGroups.regroup(listOf(msg(9), msg(5), share))
        val merged = NotifGroups.regroup(page1 + listOf(share, msg(5), msg(2)))
        assertEquals(2, merged.size)
        assertEquals(listOf(9L, 5L, 2L), merged[0].ids)
        assertEquals(1, merged.count { it.id == 4L })
        val heads = merged.map { NotifGroups.head(it) }
        assertEquals(heads.size, heads.toSet().size)
    }

    @Test
    fun markReadWholeRowsAndGroups() {
        val rows = NotifGroups.regroup(listOf(msg(10), msg(8), msg(6, chat = 9), NotifItem(id = 4, kind = "share.added")))
        // nach Gruppe: ganze Zeile, Schluessel gemeldet
        val a = NotifGroups.markRead(rows, emptySet(), setOf("chat:5"), false)
        assertTrue(a.list.first { it.groupKey == "chat:5" }.read)
        assertFalse(a.partial)
        assertTrue("chat:5" in a.readKeys)
        // nur ein Teil der ids: Zeile bleibt ungelesen, partial -> neu laden
        val b = NotifGroups.markRead(rows, setOf(8L), emptySet(), false)
        assertFalse(b.list.first { it.groupKey == "chat:5" }.read)
        assertTrue(b.partial)
        // alle ids einer Zeile
        val c = NotifGroups.markRead(rows, setOf(10L, 8L), emptySet(), false)
        assertTrue(c.list.first { it.groupKey == "chat:5" }.read)
        assertEquals(setOf("chat:5"), c.readKeys)
        // alle
        val d = NotifGroups.markRead(rows, emptySet(), emptySet(), true)
        assertTrue(d.list.all { it.read })
        assertEquals(setOf("chat:5", "chat:9"), d.readKeys)
        // gelesene Zeile verschmilzt mit bereits gelesener derselben Einheit
        val e = NotifGroups.markRead(NotifGroups.regroup(listOf(msg(10), msg(3, read = true))), setOf(10L), emptySet(), false)
        assertEquals(1, e.list.size)
        assertEquals(listOf(10L, 3L), e.list[0].ids)
    }

    @Test
    fun dropIdsKeepsRest() {
        val g = NotifGroups.regroup(listOf(msg(10), msg(8), msg(5)))
        val r = NotifGroups.dropIds(g, setOf(10L))
        assertEquals(listOf(8L, 5L), r[0].ids)
        assertEquals(8L, r[0].id)
        assertEquals(2, r[0].count)
        assertTrue(NotifGroups.dropIds(g, setOf(10L, 8L, 5L)).isEmpty())
    }

    @Test
    fun sentencesForUnits() {
        val call = NotifItem(id = 2, kind = "call.missed", groupKey = "call:5", ids = listOf(2, 1), media = "video")
        assertEquals(NotifGroups.Sentence.VIDEO_CALLS, NotifGroups.sentence(call))
        assertEquals(NotifGroups.Sentence.CALLS, NotifGroups.sentence(call.copy(media = "audio")))
        val page = NotifItem(id = 2, kind = "comment.new", groupKey = "page:7", ids = listOf(2, 1))
        assertEquals(NotifGroups.Sentence.COMMENTS, NotifGroups.sentence(page))
        assertEquals(NotifGroups.Sentence.COMMENTS_MENTION, NotifGroups.sentence(page.copy(mention = true)))
        assertEquals("A, B, C …", NotifGroups.namesShort(listOf("A", "B", "C", "D")))
        assertEquals("A, B", NotifGroups.namesShort(listOf("A", "B")))
    }

    @Test
    fun channelsPerKind() {
        assertEquals(NotifyChannels.CHATS, NotifyChannels.forKind("chat.message"))
        assertEquals(NotifyChannels.CHATS, NotifyChannels.forKind("chat.mention"))
        assertEquals(NotifyChannels.CALLS, NotifyChannels.forKind("call.missed"))
        assertEquals(NotifyChannels.TASKS, NotifyChannels.forKind("task.due"))
        assertEquals(NotifyChannels.TASKS, NotifyChannels.forKind("db.assigned"))
        for (k in listOf("comment.new", "comment.reply", "comment.mention", "page.mention", "share.added", "publish.withdrawn", "backup.failed", "test")) {
            assertEquals(k, NotifyChannels.MENTIONS, NotifyChannels.forKind(k))
        }
        assertEquals("saga_push", NotifyChannels.MENTIONS)          // bisherige ID bleibt (Nutzereinstellungen)
        assertEquals(4, NotifyChannels.ALL.toSet().size)
        assertEquals("saga-chat", NotifyChannels.group(NotifyChannels.CHATS))
        assertEquals("saga", NotifyChannels.group(NotifyChannels.MENTIONS))
        // Migration: Wichtigkeit des alten Kanals gewinnt, sonst Vorgabe
        assertEquals(NotificationManager.IMPORTANCE_LOW, NotifyChannels.importance(NotificationManager.IMPORTANCE_LOW, NotifyChannels.CHATS))
        assertEquals(NotificationManager.IMPORTANCE_NONE, NotifyChannels.importance(NotificationManager.IMPORTANCE_NONE, NotifyChannels.TASKS))
        assertEquals(NotificationManager.IMPORTANCE_HIGH, NotifyChannels.importance(null, NotifyChannels.CHATS))
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, NotifyChannels.importance(null, NotifyChannels.TASKS))
    }

    @Test
    fun pushToGroupKey() {
        assertEquals("chat:12", NotifyRoute.groupKey("chat.message", "chat-12", "/chats#c12"))
        assertNull(NotifyRoute.groupKey("chat.mention", "chat-12", "/chats#c12"))   // Server buendelt Erwaehnungen nicht
        assertEquals("call:12", NotifyRoute.groupKey("call.missed", "missed-12", "/chats#c12"))
        assertNull(NotifyRoute.groupKey("call.missed", "missed-x", "/chats#c12"))
        assertEquals("page:7", NotifyRoute.groupKey("comment.reply", "thread-3", "/notes#7:b1"))
        assertNull(NotifyRoute.groupKey("share.added", "note-7", "/notes#7"))
        assertEquals(12L, NotifyRoute.chatId("/chats#c12"))
        assertTrue(NotifyRoute.canMarkRead("chat.message", "/chats#c12"))
        assertTrue(NotifyRoute.canMarkRead("comment.new", "/notes#7"))
        assertFalse(NotifyRoute.canMarkRead("test", "/"))
        assertFalse(NotifyRoute.canMarkRead("call", "/chats#call-ab"))
    }
}
