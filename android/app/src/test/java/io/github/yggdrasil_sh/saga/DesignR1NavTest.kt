package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.data.TaskCounts
import io.github.yggdrasil_sh.saga.data.TaskEntry
import io.github.yggdrasil_sh.saga.data.TaskItem
import io.github.yggdrasil_sh.saga.data.TasksWithSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import io.github.yggdrasil_sh.saga.data.LoginErrors.Kind
import io.github.yggdrasil_sh.saga.util.UiText

/** Android-Design-Runde 1: Aufgabenzahlen (NAV-01), Gruppen (NAV-12) */
class DesignR1NavTest {
    private val today = "2026-11-07"
    private val tomorrow = "2026-11-08"

    private fun entry(key: String, due: String?, overdue: Boolean = false, dueEnd: String? = null, done: Boolean = false) =
        TaskEntry(key = key, kind = if (key.startsWith("r:")) "row" else "task", id = key.substring(2).toLong(), due = due, overdue = overdue, dueEnd = dueEnd, done = done)

    // Wie im Fund: 2 eigene Aufgaben, die zusammengefuehrte Liste zeigt 5 (3 ueberfaellig), dazu spaetere und erledigte
    private val own = listOf(
        TaskItem(1, "eigen heute", due = today),
        TaskItem(2, "eigen ohne Datum"),
        TaskItem(3, "eigen spaeter", due = "2026-11-20"),
    )
    private val merged = TasksWithSources(
        items = listOf(
            entry("t:1", today),
            entry("t:2", null),
            entry("r:10", "2026-11-01", overdue = true),
            entry("r:11", "2026-11-02", overdue = true),
            entry("r:12", "2026-11-03", overdue = true),
            entry("t:3", "2026-11-20"),
            entry("r:13", tomorrow),
            entry("r:14", today, done = true),
        ),
        sources = emptyList(), people = emptyMap(),
    )

    @Test fun headSectionAndListAgree() {
        val list = TaskCounts.todayEntries(merged, today)!!
        assertEquals(5, list.size)
        // Kopf und Abschnittszahl kommen aus derselben Liste
        assertEquals(list.size, TaskCounts.todayCount(merged, own, today))
        // Sidebar-Badge: heute + ueberfaellig, ohne Datum zaehlt nicht
        assertEquals(4, TaskCounts.badge(merged, own, today))
    }

    @Test fun withoutSourcesUsesOwnTasks() {
        assertNull(TaskCounts.todayEntries(null, today))
        assertEquals(2, TaskCounts.todayCount(null, own, today))
        assertEquals(TaskCounts.todayTasks(own, today).size, TaskCounts.todayCount(null, own, today))
        assertEquals(1, TaskCounts.badge(null, own, today))
        assertEquals(2, TaskCounts.badge(null, own + TaskItem(4, "alt", due = "2026-10-01"), today))
        assertEquals(1, TaskCounts.badge(null, own + TaskItem(5, "fertig", done = true, due = today), today))
    }

    @Test fun groupsInOrder() {
        val g = TaskCounts.grouped(merged.items.filter { !it.done }) { TaskCounts.group(it, today, tomorrow) }
        assertEquals(
            listOf(TaskCounts.Group.OVERDUE, TaskCounts.Group.TODAY, TaskCounts.Group.TOMORROW, TaskCounts.Group.LATER, TaskCounts.Group.NONE),
            g.map { it.first },
        )
        assertEquals(listOf("r:10", "r:11", "r:12"), g[0].second.map { it.key })   // Server-Reihenfolge bleibt
        val own = TaskCounts.grouped(own) { TaskCounts.group(it, today, tomorrow) }
        assertEquals(listOf(TaskCounts.Group.TODAY, TaskCounts.Group.LATER, TaskCounts.Group.NONE), own.map { it.first })
    }

    @Test fun overdueRules() {
        // Zeitraum, der heute einschliesst: nicht ueberfaellig, Gruppe heute
        val range = entry("r:20", "2026-11-05", dueEnd = "2026-11-09")
        assertFalse(TaskCounts.overdue(range, today))
        assertEquals(TaskCounts.Group.TODAY, TaskCounts.group(range, today, tomorrow))
        // Server-Kennzeichen (Uhrzeit heute vorbei) zaehlt
        assertTrue(TaskCounts.overdue(entry("r:21", today, overdue = true), today))
        // ohne Kennzeichen, aber Datum vorbei
        assertTrue(TaskCounts.overdue(entry("t:22", "2026-11-06"), today))
        // erledigt ist nie ueberfaellig
        assertFalse(TaskCounts.overdue(entry("t:23", "2026-11-01", overdue = true, done = true), today))
        assertTrue(TaskCounts.overdue(TaskItem(9, "x", due = "2026-11-06"), today))
        assertFalse(TaskCounts.overdue(TaskItem(9, "x", due = today), today))
    }

    @Test fun offlineRules() {
        val L = io.github.yggdrasil_sh.saga.data.OfflineLogic
        assertTrue(L.offline(netOnline = false, liveSupported = false, liveConnected = false))
        assertFalse(L.offline(netOnline = true, liveSupported = false, liveConnected = false))   // alter Server ohne Live-Kanal
        assertTrue(L.offline(netOnline = true, liveSupported = true, liveConnected = false))
        assertFalse(L.offline(netOnline = true, liveSupported = true, liveConnected = true))
        assertEquals(3_000L, L.DEBOUNCE_MS)
        assertEquals(500L, L.stamp(100L, online = true, nowMs = 500L))
        assertEquals(100L, L.stamp(100L, online = false, nowMs = 500L))
        assertNull(L.stamp(null, online = false, nowMs = 500L))
        val utc = java.time.ZoneOffset.UTC
        assertTrue(L.sameDay(0L, 23 * 3_600_000L, utc))
        assertFalse(L.sameDay(0L, 25 * 3_600_000L, utc))
    }

    @Test fun loginErrorKinds() {
        val E = io.github.yggdrasil_sh.saga.data.LoginErrors
        fun wrapped(c: Throwable) = io.github.yggdrasil_sh.saga.util.UiTextException(UiText.Res(R.string.error_server_unreachable)).apply { initCause(c) }
        val dns = java.net.UnknownHostException("saga.example.invalid")
        assertEquals(Kind.NOT_FOUND, E.classify(wrapped(dns), online = true))
        assertEquals(Kind.OFFLINE, E.classify(wrapped(dns), online = false))
        assertEquals(Kind.NO_ANSWER, E.classify(wrapped(java.net.SocketTimeoutException()), online = true))
        assertEquals(Kind.NO_ANSWER, E.classify(java.net.ConnectException(), online = true))
        assertEquals(Kind.OFFLINE, E.classify(java.io.IOException("x"), online = false))
        assertEquals(Kind.NOT_SAGA, E.classify(io.github.yggdrasil_sh.saga.util.UiTextException(UiText.Res(R.string.login_not_saga)), online = true))
        // Zertifikat/https usw.: bisheriger Text bleibt
        val cert = io.github.yggdrasil_sh.saga.util.UiTextException(UiText.Res(R.string.error_certificate))
        assertEquals(Kind.OTHER, E.classify(cert, online = true))
        assertEquals(UiText.Res(R.string.error_certificate), E.text(Kind.OTHER, UiText.Res(R.string.error_certificate)))
        assertEquals(UiText.Res(R.string.r1_login_err_not_found), E.text(Kind.NOT_FOUND, UiText.Raw("x")))
    }
}
