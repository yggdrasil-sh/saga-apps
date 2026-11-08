package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.chat.ChatDeviceLimit
import io.github.yggdrasil_sh.saga.chat.ChatDeviceLimit.Poll
import io.github.yggdrasil_sh.saga.chat.ChatLimits
import io.github.yggdrasil_sh.saga.chat.DeviceLimit
import io.github.yggdrasil_sh.saga.chat.DeviceLimitException
import io.github.yggdrasil_sh.saga.chat.LinkState
import io.github.yggdrasil_sh.saga.data.ApiException
import io.github.yggdrasil_sh.saga.live.NotifItem
import io.github.yggdrasil_sh.saga.push.NotifyText
import io.github.yggdrasil_sh.saga.push.NotifyText.Parsed
import io.github.yggdrasil_sh.saga.push.NotifyText.Sentence
import io.github.yggdrasil_sh.saga.push.NotifyUpdate
import io.github.yggdrasil_sh.saga.push.NotifyUpdate.Line
import io.github.yggdrasil_sh.saga.push.NotifyUpdate.Plan
import io.github.yggdrasil_sh.saga.push.PushLocal
import io.github.yggdrasil_sh.saga.push.PushParse
import io.github.yggdrasil_sh.saga.push.PushServerInfo
import io.github.yggdrasil_sh.saga.push.PushSubLogic
import io.github.yggdrasil_sh.saga.ui.notifications.NotifGroups
import io.github.yggdrasil_sh.saga.util.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2.5 (r5pre): Push-Nutzlast 2 (Textbau, Abo, Aktualisierung), Glocke notification.changed, Chat-Geraetegrenze */
class R5PrePushChatTest {
    // ---------------------------------------------------------------- Textbau v2

    @Test
    fun v2BuildsSentenceFromFieldsWithoutParsing() {
        assertEquals(Parsed(Sentence.CHAT_FROM, "Jonas"), NotifyText.fromV2("chat.message", "Jonas", 3))
        assertEquals(Parsed(Sentence.CHAT, n = 3), NotifyText.fromV2("chat.message", null, 3))
        assertEquals(Parsed(Sentence.CHAT_MENTION_FROM, "Jonas"), NotifyText.fromV2("chat.mention", "Jonas", 1))
        assertEquals(Parsed(Sentence.CHAT_MENTION), NotifyText.fromV2("chat.mention", "  ", 1))
        assertEquals(Parsed(Sentence.MISSED_N_FROM, "Jonas", 2), NotifyText.fromV2("call.missed", "Jonas", 2))
        assertEquals(Parsed(Sentence.MISSED_FROM, "Jonas"), NotifyText.fromV2("call.missed", "Jonas", 1))
        assertEquals(Parsed(Sentence.MISSED_N, null, 4), NotifyText.fromV2("call.missed", null, 4))
        assertEquals(Parsed(Sentence.MISSED), NotifyText.fromV2("call.missed", null, null))
        assertEquals(Parsed(Sentence.COMMENTS_N, null, 5), NotifyText.fromV2("comment.new", null, 5))
        assertEquals(Parsed(Sentence.COMMENT_FROM, "Ana"), NotifyText.fromV2("comment.new", "Ana", 1))
        assertEquals(Parsed(Sentence.COMMENT), NotifyText.fromV2("comment.new", null, 1))
        assertEquals(Parsed(Sentence.REPLIES_N, null, 2), NotifyText.fromV2("comment.reply", null, 2))
        assertEquals(Parsed(Sentence.MENTION), NotifyText.fromV2("page.mention", null, 1))
        assertEquals(Parsed(Sentence.DB_N_FROM, "Ana", 3), NotifyText.fromV2("db.assigned", "Ana", 3))
        assertEquals(Parsed(Sentence.TASKS), NotifyText.fromV2("task.due", null, 1))
        assertEquals(Parsed(Sentence.TEST), NotifyText.fromV2("test", null, null))
        // Freigabe mit Namen nennt eine Rolle, die nicht in der Nutzlast steht: Satz des Servers bleibt
        assertNull(NotifyText.fromV2("share.added", "Ana", 1))
        assertEquals(Parsed(Sentence.SHARE), NotifyText.fromV2("share.added", null, 1))
        assertNull(NotifyText.fromV2("backup.failed", null, 1))
    }

    @Test
    fun v2NeverInventsNamesAndOldServersStillParse() {
        // ohne actor kein Name - auch wenn der Satz (anderer Weg) einen enthielte
        assertNull(NotifyText.of(2, "chat.message", "New message from Bruno", null, 1)?.name)
        assertFalse(NotifyText.named(NotifyText.of(2, "comment.new", "Bruno commented on your page", null, 1)))
        // alter Server (v fehlt = 0): Satzerkennung wie bisher
        assertEquals(Parsed(Sentence.CHAT_FROM, "Bruno"), NotifyText.of(0, "chat.message", "Neue Nachricht von Bruno", null, null))
        assertNull(NotifyText.of(0, "share.added", "Unbekannter Satz", null, null))
    }

    @Test
    fun parsesV2PayloadIncludingSilentRemoval() {
        val upd = PushParse.parse(
            """{"v":2,"update":true,"removed":true,"kind":"chat.message","tag":"chat-12","group_key":"chat:12","chat":12,"n":0,
               "title":"Saga","body":"","url":"/chats#c12","ts":1794000000,"lang":"de"}""".toByteArray(), null,
        )!!
        assertTrue(upd.update)
        assertTrue(upd.removed)
        assertEquals(0, upd.n)
        assertEquals("chat:12", upd.groupKey)
        assertNull(upd.actorName)
        val msg = PushParse.parse("""{"v":2,"kind":"chat.message","tag":"chat-12","title":"Saga","body":"Neue Nachricht von Jonas","actor":"Jonas","n":2}""".toByteArray(), null)!!
        assertEquals("Jonas", msg.actorName)
        assertFalse(msg.update)
        // unerwartete Form von actor bricht die Mitteilung nicht
        assertNull(PushParse.parse("""{"v":2,"kind":"chat.message","body":"x","actor":{"name":"A"}}""".toByteArray(), null)!!.actorName)
        // alter Server: keine neuen Felder
        val old = PushParse.parse("""{"title":"Saga","body":"New message","kind":"chat.message","tag":"chat-3"}""".toByteArray(), null)!!
        assertEquals(0, old.v)
        assertFalse(old.update)
    }

    // ---------------------------------------------------------------- Abo

    @Test
    fun subscriptionFieldsOnlyForPayload2() {
        val old = PushServerInfo(enabled = true)
        val v2 = PushServerInfo(enabled = true, payload = 2, subCaps = listOf("update"))
        assertFalse(PushSubLogic.v2(old))
        assertTrue(PushSubLogic.caps(old).isEmpty())
        assertEquals(listOf("update"), PushSubLogic.caps(v2))
        assertEquals(listOf("update"), PushSubLogic.caps(PushServerInfo(payload = 2)))
        assertTrue(PushSubLogic.caps(PushServerInfo(payload = 2, subCaps = listOf("other"))).isEmpty())
        val l = PushLocal("pkg", "t", "https://up.example/x", 7)
        assertFalse(PushSubLogic.needsRenew(l, old, "de"))                       // alter Server: nie
        assertTrue(PushSubLogic.needsRenew(l, v2, "de"))                         // noch nie gemeldet
        assertFalse(PushSubLogic.needsRenew(l.copy(lang = "de", caps = true), v2, "de"))
        assertTrue(PushSubLogic.needsRenew(l.copy(lang = "de", caps = true), v2, "th"))   // Sprachwechsel
        assertTrue(PushSubLogic.needsRenew(l.copy(lang = "de", caps = true), v2, ""))     // zurueck auf System
        assertFalse(PushSubLogic.needsRenew(l.copy(endpoint = null), v2, "de"))  // noch keine Adresse
        assertFalse(PushSubLogic.needsRenew(null, v2, "de"))
    }

    // ---------------------------------------------------------------- Aktualisierung

    @Test
    fun updateTrimsOldestLinesAndCancelsWhenNothingLeft() {
        val lines = listOf(Line(2, "Ana"), Line(1, "Bruno"), Line(3, "Ana"))
        assertEquals(Plan.Keep, NotifyUpdate.lines(lines, "chat.message", 6, "Ana"))
        assertEquals(Plan.Keep, NotifyUpdate.lines(lines, "chat.message", 9, "Ana"))
        // 6 gezeigt, 4 uebrig, neuester Absender unveraendert -> von der aeltesten Zeile an kuerzen
        assertEquals(Plan.Lines(listOf(1 to 1, 2 to 3)), NotifyUpdate.lines(lines, "chat.message", 4, "Ana"))
        assertEquals(Plan.Lines(listOf(2 to 2)), NotifyUpdate.lines(lines, "chat.message", 2, null))
        assertEquals(Plan.Cancel, NotifyUpdate.lines(lines, "chat.message", 0, null))
    }

    @Test
    fun updateTrimsNewestWhenServerNamesAnotherLatestSender() {
        val lines = listOf(Line(1, "Ana"), Line(2, "Bruno"))
        // Server nennt jetzt Ana als neuesten Absender: Brunos Nachricht wurde geloescht
        assertEquals(Plan.Lines(listOf(0 to 1, 1 to 1)), NotifyUpdate.lines(lines, "chat.message", 2, "Ana"))
        assertEquals(Plan.Lines(listOf(0 to 1)), NotifyUpdate.lines(lines, "chat.message", 1, "Ana"))
        // unbekannter Name (z. B. anderer Anzeigename im 1:1-Chat): wie ohne Namen
        assertEquals(Plan.Lines(listOf(1 to 1)), NotifyUpdate.lines(lines, "chat.message", 1, "Carla"))
    }

    @Test
    fun updateTouchesOnlyLinesOfItsKind() {
        val lines = listOf(Line(0, "Ana"), Line(2, "Bruno"), Line(0, "Carla"))
        // Nachrichten-Aktualisierung laesst Erwaehnungen stehen
        assertEquals(Plan.Lines(listOf(0 to 0, 2 to 0)), NotifyUpdate.lines(lines, "chat.message", 0, null))
        // Erwaehnungs-Aktualisierung: aelteste Erwaehnung weg
        assertEquals(Plan.Lines(listOf(1 to 2, 2 to 0)), NotifyUpdate.lines(lines, "chat.mention", 1, null))
        assertEquals(Plan.Keep, NotifyUpdate.lines(listOf(Line(0, "Ana")), "chat.message", 0, null))
        assertEquals(Plan.Cancel, NotifyUpdate.lines(listOf(Line(0, "Ana")), "chat.mention", 0, null))
        assertEquals(Plan.Keep, NotifyUpdate.lines(emptyList(), "chat.message", 0, null))
    }

    @Test
    fun neutralUpdateSetsCountOrCloses() {
        assertEquals(Plan.Count(2), NotifyUpdate.neutral(false, 3, "chat.message", 2))
        assertEquals(Plan.Keep, NotifyUpdate.neutral(false, 2, "chat.message", 2))
        assertEquals(Plan.Cancel, NotifyUpdate.neutral(false, 3, "chat.message", 0))
        assertEquals(Plan.Keep, NotifyUpdate.neutral(true, 1, "chat.message", 0))       // zeigt eine Erwaehnung
        assertEquals(Plan.Cancel, NotifyUpdate.neutral(true, 1, "chat.mention", 0))
        assertEquals(Plan.Keep, NotifyUpdate.neutral(false, 1, "chat.mention", 0))
    }

    @Test
    fun countsFollowMessagingStyle() {
        assertEquals(listOf(1, 1, 4), NotifyUpdate.defaultCounts(3, 4, false))
        assertEquals(listOf(1, 0), NotifyUpdate.defaultCounts(2, 1, true))
        assertEquals(listOf(1, 3), NotifyUpdate.nextCounts(listOf(1, 2), true, 3, 6))
        assertEquals(listOf(1, 2, 1), NotifyUpdate.nextCounts(listOf(1, 2), false, 1, 6))
        assertEquals(listOf(3, 4, 5, 6, 7, 0), NotifyUpdate.nextCounts(listOf(1, 2, 3, 4, 5, 6, 7), false, 0, 6))
    }

    // ---------------------------------------------------------------- Glocke

    private fun chat(id: Long, ids: List<Long>, counts: List<Int>) =
        NotifItem(id = id, kind = "chat.message", groupKey = "chat:12", ids = ids, counts = counts, count = counts.sum(), created = id.toDouble())

    @Test
    fun bellReplacesOrRemovesChangedEntry() {
        val share = NotifItem(id = 3, kind = "share.added", created = 3.0)
        val list = NotifGroups.regroup(listOf(chat(912, listOf(912, 900), listOf(3, 1)), share))
        // Zahl kleiner (gleiche id): Zeile bleibt, Zaehler sinkt
        val smaller = NotifGroups.changed(list, 912, NotifItem(id = 912, kind = "chat.message", groupKey = "chat:12", count = 2, created = 912.0), false)
        val row = smaller.first { it.groupKey == "chat:12" }
        assertEquals(listOf(912L, 900L), NotifGroups.idsOf(row))
        assertEquals(3, row.count)
        assertEquals(2, smaller.size)
        // Eintrag weg: Rest der Zeile bleibt
        val gone = NotifGroups.changed(list, 912, null, true)
        assertEquals(listOf(900L), NotifGroups.idsOf(gone.first { it.groupKey == "chat:12" }))
        assertTrue(NotifGroups.hasUnread(gone, "chat:12"))
        // letzter Eintrag weg: Zeile weg
        val empty = NotifGroups.changed(gone, 900, null, true)
        assertEquals(listOf(3L), empty.map { it.id })
        assertFalse(NotifGroups.hasUnread(empty, "chat:12"))
        // unbekannte id: nichts
        assertEquals(list, NotifGroups.changed(list, 5, null, true))
    }

    // ---------------------------------------------------------------- Chat-Geraetegrenze

    @Test
    fun deviceLimitFromConflictBody() {
        val body = """{"error":"Du hast schon 10 Chat-Geräte.","code":"device_limit","max":10,"count":10}"""
        assertEquals(DeviceLimit(10, 10), ChatDeviceLimit.parse(body))
        assertEquals(DeviceLimit(10, 10), ChatDeviceLimit.of(ApiException(409, UiText.Raw("x"), body)))
        assertNull(ChatDeviceLimit.of(ApiException(400, UiText.Raw("x"), body)))
        // alter Server: 409 ohne code -> wie bisher (Text des Servers)
        assertNull(ChatDeviceLimit.of(ApiException(409, UiText.Raw("x"), """{"error":"Höchstens 10 Geräte"}""")))
        assertNull(ChatDeviceLimit.parse("kein json"))
        assertNull(ChatDeviceLimit.parse(null))
        assertEquals(DeviceLimit(8, 8), ChatDeviceLimit.of(DeviceLimitException(DeviceLimit(8, 8))))
        assertNull(ChatDeviceLimit.of(IllegalStateException()))
    }

    @Test
    fun mapLimitTurnsConflictIntoDeviceLimitException() {
        val body = """{"code":"device_limit","max":10,"count":11}"""
        val e = try {
            ChatDeviceLimit.mapLimit<Unit> { throw ApiException(409, UiText.Raw("x"), body) }
            null
        } catch (x: DeviceLimitException) {
            x
        }
        assertEquals(DeviceLimit(10, 11), e?.limit)
        val other = try {
            ChatDeviceLimit.mapLimit<Unit> { throw ApiException(409, UiText.Raw("x"), "{}") }
            null
        } catch (x: ApiException) {
            x
        }
        assertEquals(409, other?.code)
        assertEquals(5, ChatDeviceLimit.mapLimit { 5 })
    }

    @Test
    fun atLimitOnlyWithDeviceCount() {
        assertNull(ChatDeviceLimit.atLimit(ChatLimits(devicesMax = 10)))             // alter Server
        assertNull(ChatDeviceLimit.atLimit(ChatLimits(devicesMax = 10, devicesCount = 9)))
        assertEquals(DeviceLimit(10, 10), ChatDeviceLimit.atLimit(ChatLimits(devicesMax = 10, devicesCount = 10)))
        assertNull(ChatDeviceLimit.atLimit(null))
    }

    @Test
    fun linkPollStates() {
        assertEquals(Poll.Pending, ChatDeviceLimit.poll(LinkState("pending")))
        assertEquals(Poll.Approved, ChatDeviceLimit.poll(LinkState("approved", sealed = "x")))
        assertEquals(Poll.Expired, ChatDeviceLimit.poll(LinkState("expired")))
        assertEquals(Poll.Gone, ChatDeviceLimit.poll(LinkState("gone")))
        assertEquals(Poll.Limit(DeviceLimit(10, 10)), ChatDeviceLimit.poll(LinkState("failed", code = "device_limit", max = 10, count = 10)))
        assertEquals(Poll.Limit(DeviceLimit(12, 12)), ChatDeviceLimit.poll(LinkState("failed", code = "device_limit"), fallbackMax = 12))
        assertEquals(Poll.Failed, ChatDeviceLimit.poll(LinkState("failed", code = "other")))
        assertEquals(Poll.Failed, ChatDeviceLimit.poll(LinkState("weird")))            // unbekannt: nicht weiter warten
    }
}
