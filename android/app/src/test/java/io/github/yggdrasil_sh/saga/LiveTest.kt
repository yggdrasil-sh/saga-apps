package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.live.LivePolicy
import io.github.yggdrasil_sh.saga.live.NotifItem
import io.github.yggdrasil_sh.saga.live.NotifLogic
import io.github.yggdrasil_sh.saga.live.NotifList
import io.github.yggdrasil_sh.saga.live.SseParser
import io.github.yggdrasil_sh.saga.ui.page.PageLiveLogic
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Live-Kanal (1.4): SSE lesen, Wiederverbinden, Benachrichtigungen, fremde Aenderungen an der offenen Seite */
class LiveTest {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false; isLenient = true }

    @Test
    fun sseParser() {
        val pings = ArrayList<String>()
        val p = SseParser { pings.add(it) }
        val lines = listOf(
            "retry: 5000", ":" + " ".repeat(20), "", "event: hello", "data: {\"boot\":\"b1\",\"ping\":25}", "",
            "id: b1-7", "event: notification", "data: {\"unread\":3,", "data: \"x\":1}", "",
            ": ping 1760000000", "", "event: reset", "data: {}", "\r",
        )
        val evs = lines.mapNotNull { p.feed(it) }
        assertEquals(listOf("hello", "notification", "reset"), evs.map { it.event })
        assertNull(evs[0].id)
        assertEquals("b1-7", evs[1].id)
        assertEquals("{\"unread\":3,\n\"x\":1}", evs[1].data)
        assertTrue(pings.any { it.startsWith("ping") })
    }

    @Test
    fun reconnectPolicy() {
        assertEquals(0L, LivePolicy.afterBye("refresh"))
        assertEquals(0L, LivePolicy.afterBye("auth"))
        assertNull(LivePolicy.afterBye("account"))
        assertTrue(LivePolicy.afterBye("limit")!! >= 60_000)            // andere Verbindung hat uebernommen: nicht sofort
        assertEquals(5_000L, LivePolicy.afterBye("shutdown"))
        assertEquals(5_000L, LivePolicy.afterBye("slow"))
        assertNull(LivePolicy.afterStatus(401, 1))
        assertNull(LivePolicy.afterStatus(404, 1))
        assertEquals(30_000L, LivePolicy.afterStatus(503, 1))
        assertTrue(LivePolicy.backoff(1) < LivePolicy.backoff(3))
        assertEquals(60_000L, LivePolicy.backoff(20))
    }

    private fun item(id: Long, read: Boolean = false) = NotifItem(id = id, kind = "comment.new", read = read, text = "t$id")

    @Test
    fun notificationList() {
        val l = listOf(item(5), item(4), item(3, true))
        // gebuendelt: neue ersetzt die alte (replaces) und steht oben
        val l2 = NotifLogic.insert(l, item(6), replaces = 4)
        assertEquals(listOf(6L, 5L, 3L), l2.map { it.id })
        // gleiche ID nicht doppelt
        assertEquals(listOf(6L, 5L, 3L), NotifLogic.insert(l2, item(6), null).map { it.id })
        assertEquals(listOf(true, false, true), NotifLogic.markRead(l2, setOf(6L), false).map { it.read })
        assertTrue(NotifLogic.markRead(l2, null, true).all { it.read })
    }

    @Test
    fun notificationItemsAndTargets() {
        val r = json.decodeFromString<NotifList>(
            """{"items":[{"id":9,"kind":"comment.reply","created":1760000000.5,"read":false,"actor":{"name":"Bert","username":"bert"},
               "count":2,"access":true,"note":{"id":12,"title":"Plan","icon":"📄"},"title":"Plan","text":"Bert hat in einer Unterhaltung geantwortet",
               "url":"/notes#12:abc","excerpt":"ok","comment_id":33,"thread_id":31,"block_id":"abc"},
               {"id":8,"kind":"share.added","access":false,"title":"Kein Zugriff mehr","text":"x"},
               {"id":7,"kind":"chat.message","access":true,"chat":{"id":5,"kind":"dm"},"url":"/chats#c5","title":"Bert","text":"Neue Nachricht von Bert"},
               {"id":6,"kind":"comment.new","remote":true,"access":true,"note":{"id":-2049,"title":"Fremd","icon":"","host":"b.example"},"url":"/notes#-2049","text":"y"}],
               "unread":3,"more":true,"next":6}""",
        )
        assertEquals(4, r.items.size)
        assertTrue(NotifLogic.tappable(r.items[0]))
        assertFalse(NotifLogic.tappable(r.items[1]))                    // kein Zugriff mehr: nicht antippbar
        assertTrue(NotifLogic.tappable(r.items[2]))
        assertEquals(31L, r.items[0].threadId)
        assertEquals(-2049L, r.items[3].note?.id)
        assertEquals(NotifLogic.Target.Page(12, "abc"), NotifLogic.target("/notes#12:abc"))
        assertEquals(NotifLogic.Target.Page(-2049, null), NotifLogic.target("/notes#-2049"))
        assertEquals(NotifLogic.Target.Chat(5), NotifLogic.target("/chats#c5"))
        assertEquals(NotifLogic.Target.Chat(7), NotifLogic.target("/chats#7"))
        assertNull(NotifLogic.target("/"))
        assertNull(NotifLogic.target("https://evil.example/notes#1"))
    }

    @Test
    fun foreignChangesOnOpenPage() {
        assertEquals(PageLiveLogic.Action.RELOAD_SILENT, PageLiveLogic.onChanged(5, 4, dirty = false, gone = false))
        assertEquals(PageLiveLogic.Action.SHOW_BANNER, PageLiveLogic.onChanged(5, 4, dirty = true, gone = false))
        assertEquals(PageLiveLogic.Action.NONE, PageLiveLogic.onChanged(4, 4, dirty = false, gone = false))
        assertEquals(PageLiveLogic.Action.NONE, PageLiveLogic.onChanged(null, 4, dirty = false, gone = false))
        assertEquals(PageLiveLogic.Action.GONE, PageLiveLogic.onChanged(null, 4, dirty = true, gone = true))
        assertTrue(PageLiveLogic.editing(100_000, 80_000, canEdit = true))
        assertFalse(PageLiveLogic.editing(200_000, 80_000, canEdit = true))     // > 30 s nach der Eingabe
        assertFalse(PageLiveLogic.editing(100_000, 80_000, canEdit = false))    // nur mit Bearbeiten
        assertFalse(PageLiveLogic.editing(100_000, 0, canEdit = true))
        val users = PageLiveLogic.users(json.parseToJsonElement("""[{"username":"a","name":"Anna","editing":true,"me":false},{"username":"me","name":"Ich","me":true}]""") as JsonArray)
        assertEquals(2, users.size)
        assertTrue(users[0].editing && !users[0].me)
        assertTrue(users[1].me)
    }
}
