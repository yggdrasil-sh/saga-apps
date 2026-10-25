package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.data.TaskItem
import io.github.yggdrasil_sh.saga.widget.PageExcerpt
import io.github.yggdrasil_sh.saga.widget.PageSnapshot
import io.github.yggdrasil_sh.saga.widget.TodayLayout
import io.github.yggdrasil_sh.saga.widget.TodayLogic
import io.github.yggdrasil_sh.saga.widget.TodaySnapshot
import io.github.yggdrasil_sh.saga.widget.WEvent
import io.github.yggdrasil_sh.saga.widget.WTask
import io.github.yggdrasil_sh.saga.widget.WidgetStates
import io.github.yggdrasil_sh.saga.widget.WidgetUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** Homescreen-Widgets: Auswahl/Sortierung "Heute", Seiten-Auszug, Zustaende (ohne Anmeldung, offline, leer) */
class WidgetLogicTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private val today = LocalDate.of(2026, 10, 25)

    // ------------------------------------------------------------ Aufgaben

    @Test
    fun tasksDueTodayOverdueOrWithoutDate() {
        // wie iOS-Widget und Ansicht "Heute": ohne Datum dabei (zuletzt), spaetere und unlesbare nicht
        val all = listOf(
            WTask(1, "ohne Datum"),
            WTask(2, "morgen", "2026-10-26"),
            WTask(3, "heute", "2026-10-25"),
            WTask(4, "gestern", "2026-10-24"),
            WTask(5, "kaputtes Datum", "bald"),
            WTask(6, "leeres Datum", ""),
            WTask(7, "ohne Datum, wichtig", null, 1),
        )
        val r = TodayLogic.tasks(all, today)
        assertEquals(listOf(4, 3, 7, 1, 6), r.map { it.task.id })
        assertEquals(listOf(true, false, false, false, false), r.map { it.overdue })
    }

    @Test
    fun tasksSortedByDueThenPrioThenId() {
        val all = listOf(
            WTask(10, "heute normal", "2026-10-25", 2),
            WTask(11, "heute wichtig", "2026-10-25", 1),
            WTask(12, "heute ohne Prio", "2026-10-25"),
            WTask(13, "lange ueberfaellig", "2026-09-01", 3),
            WTask(9, "heute normal (kleinere ID)", "2026-10-25", 2),
        )
        assertEquals(listOf(13, 11, 9, 10, 12), TodayLogic.tasks(all, today).map { it.task.id })
    }

    @Test
    fun hiddenTasksAreLeftOut() {
        val all = listOf(WTask(1, "a", "2026-10-25"), WTask(2, "b", "2026-10-25"))
        assertEquals(listOf(2), TodayLogic.tasks(all, today, hidden = setOf(1)).map { it.task.id })
    }

    @Test
    fun snapshotKeepsTasksUntilTomorrowOnly() {
        val open = listOf(
            TaskItem(1, "heute", due = "2026-10-25"),
            TaskItem(2, "morgen", due = "2026-10-26"),
            TaskItem(3, "uebermorgen", due = "2026-10-27"),
            TaskItem(4, "ohne Datum"),
            TaskItem(5, "erledigt", done = true, due = "2026-10-25"),
            TaskItem(6, "erledigt ohne Datum", done = true),
        )
        assertEquals(listOf(1, 2, 4), TodayLogic.forSnapshot(open, today.plusDays(1)).map { it.id })
    }

    @Test
    fun snapshotFromYesterdayStillCorrectToday() {
        // offline nach Mitternacht: gestern geladene "morgen"-Aufgabe ist heute faellig, gestrige "heute" ueberfaellig
        val snap = listOf(WTask(1, "war heute", "2026-10-24"), WTask(2, "war morgen", "2026-10-25"))
        val r = TodayLogic.tasks(snap, today)
        assertEquals(listOf(1, 2), r.map { it.task.id })
        assertEquals(listOf(true, false), r.map { it.overdue })
    }

    // ------------------------------------------------------------ Termine

    @Test
    fun eventsOfToday() {
        val all = listOf(
            WEvent("Abend", "2026-10-25T19:00:00+01:00", "2026-10-25T20:00:00+01:00"),
            WEvent("Urlaub", "2026-10-23", "2026-10-27", allday = true),
            WEvent("Gestern ganztaegig", "2026-10-24", "2026-10-25", allday = true),
            WEvent("Morgen frueh", "2026-10-26T08:00:00+01:00", "2026-10-26T09:00:00+01:00"),
            WEvent("Nachtschicht", "2026-10-24T22:00:00+02:00", "2026-10-25T06:00:00+01:00"),
            WEvent("Morgens", "2026-10-25T09:30:00+01:00", "2026-10-25T09:45:00+01:00"),
            WEvent("Heute ganztaegig ohne Ende", "2026-10-25", "", allday = true),
            WEvent("Kaputt", "irgendwann", ""),
        )
        val r = TodayLogic.events(all, today, zone)
        assertEquals(listOf("Heute ganztaegig ohne Ende", "Urlaub", "Nachtschicht", "Morgens", "Abend"), r.map { it.event.summary })
        assertTrue(r[0].allday && r[1].allday)
        assertEquals(9, r[3].start?.hour)
    }

    @Test
    fun eventEndingExactlyAtMidnightBelongsToYesterday() {
        val e = WEvent("bis Mitternacht", "2026-10-24T23:00:00+02:00", "2026-10-25T00:00:00+02:00")
        assertTrue(TodayLogic.events(listOf(e), today, zone).isEmpty())
    }

    // ------------------------------------------------------------ Platz

    @Test
    fun layoutSplit() {
        assertEquals(0 to 0, TodayLayout.split(0, 3, 3))
        assertEquals(3 to 0, TodayLayout.split(4, 3, 0))
        assertEquals(0 to 4, TodayLayout.split(4, 0, 9))
        assertEquals(2 to 2, TodayLayout.split(4, 5, 5))
        assertEquals(1 to 3, TodayLayout.split(4, 1, 5))       // nur ein Termin: Rest fuer Aufgaben
        assertEquals(3 to 1, TodayLayout.split(4, 5, 1))       // nur eine Aufgabe: Rest fuer Termine
        assertEquals(1 to 1, TodayLayout.split(2, 4, 4))
        assertEquals(0 to 1, TodayLayout.split(1, 4, 4))       // nur eine Zeile: die Aufgabe (abhakbar)
        assertTrue(TodayLayout.rows(120f) in 2..3)
        assertTrue(TodayLayout.rows(250f) >= 7)
    }

    // ------------------------------------------------------------ Seiten-Auszug

    @Test
    fun excerptFromBlocks() {
        val body = """{"blocks":[
            {"id":"a","type":"header","data":{"text":"Ziel","level":2}},
            {"id":"b","type":"paragraph","data":{"text":"Bis <b>Freitag</b>&nbsp;fertig &amp; abgestimmt."}},
            {"id":"c","type":"list","data":{"style":"unordered","items":[{"content":"Eins","items":[{"content":"Unterpunkt","items":[]}]},{"content":"Zwei","items":[]}]}},
            {"id":"d","type":"image","data":{"file":{"url":"/files/x"}}},
            {"id":"e","type":"code","data":{"code":"a < b"}},
            {"id":"f","type":"paragraph","data":{"text":"   "}}
        ]}"""
        assertEquals("Ziel\nBis Freitag fertig & abgestimmt.\n• Eins\n• Unterpunkt\n• Zwei\na < b", PageExcerpt.fromBody(body, "blocks"))
    }

    @Test
    fun excerptOldTextNotesAndBrokenContent() {
        assertEquals("Zeile eins\nZeile zwei", PageExcerpt.fromBody("Zeile eins\n\nZeile zwei", "text"))
        assertEquals("", PageExcerpt.fromBody("{kaputt", "blocks"))
        assertEquals("", PageExcerpt.fromBody(null, "blocks"))
        assertEquals("", PageExcerpt.fromBody("", ""))
    }

    @Test
    fun truncateAtWordBoundary() {
        val t = "Das ist ein ziemlich langer Satz, der nicht ganz ins Widget passt"
        val r = PageExcerpt.truncate(t, 30)
        assertTrue(r, r.endsWith("…"))
        assertTrue(r.length <= 31)
        assertEquals("Das ist ein ziemlich langer…", r)
        assertEquals("kurz", PageExcerpt.truncate("  kurz ", 30))
        // ohne Leerzeichen: hart kuerzen
        assertEquals("abcdefghij…", PageExcerpt.truncate("abcdefghijklmnop", 10))
        // kein halbes Emoji am Ende
        val e = PageExcerpt.truncate("abcdefghi😀xyz", 10)
        assertFalse(Character.isHighSurrogate(e[e.length - 2]))
    }

    @Test
    fun longPageIsCutToMax() {
        val para = (1..200).joinToString(" ") { "Wort$it" }
        val body = """{"blocks":[{"type":"paragraph","data":{"text":"$para"}}]}"""
        val r = PageExcerpt.fromBody(body, "blocks", max = 120)
        assertTrue(r.length <= 121)
        assertTrue(r.endsWith("…"))
    }

    @Test
    fun emojiIcons() {
        assertEquals("📚", PageExcerpt.emoji("📚"))
        assertEquals("👩‍💻", PageExcerpt.emoji("👩‍💻"))
        assertEquals(null, PageExcerpt.emoji("/files/abc.png"))
        assertEquals(null, PageExcerpt.emoji("https://example.org/i.png"))
        assertEquals(null, PageExcerpt.emoji(""))
        assertEquals(null, PageExcerpt.emoji(null))
    }

    // ------------------------------------------------------------ Zustaende

    private val now = 1_800_000_000_000L
    private val todaySnap = TodaySnapshot(day = "2026-10-25", fetchedAt = now - 60_000, tasks = listOf(WTask(1, "a", "2026-10-25")))

    @Test
    fun todayStates() {
        assertEquals(WidgetUi.SignedOut, WidgetStates.today(true, todaySnap, null, today, zone, now))
        assertEquals(WidgetUi.Loading, WidgetStates.today(false, null, null, today, zone, now))
        assertEquals(WidgetUi.Content(null, empty = false, offline = false), WidgetStates.today(false, todaySnap, null, today, zone, now))
        // leer: nichts heute (auch wenn die einzige Aufgabe gerade abgehakt wurde)
        val empty = WidgetStates.today(false, todaySnap.copy(tasks = emptyList()), null, today, zone, now)
        assertEquals(WidgetUi.Content(null, empty = true, offline = false), empty)
        assertEquals(true, (WidgetStates.today(false, todaySnap, null, today, zone, now, hidden = setOf(1)) as WidgetUi.Content).empty)
    }

    @Test
    fun todayOfflineShowsTimestamp() {
        val r = WidgetStates.today(false, todaySnap, failedAt = now, today, zone, now) as WidgetUi.Content
        assertEquals(todaySnap.fetchedAt, r.staleSince)
        assertTrue(r.offline)
        // Fehler VOR dem letzten erfolgreichen Abruf zaehlt nicht
        val ok = WidgetStates.today(false, todaySnap, failedAt = todaySnap.fetchedAt - 1, today, zone, now) as WidgetUi.Content
        assertEquals(null, ok.staleSince)
        // alt, aber ohne Fehler: Zeitstempel, nicht "offline"
        val old = WidgetStates.today(false, todaySnap.copy(fetchedAt = now - WidgetStates.STALE_MS - 1), null, today, zone, now) as WidgetUi.Content
        assertEquals(now - WidgetStates.STALE_MS - 1, old.staleSince)
        assertFalse(old.offline)
    }

    @Test
    fun pageStates() {
        val p = PageSnapshot(id = 7, title = "Ideen", excerpt = "Text", fetchedAt = now)
        assertEquals(WidgetUi.SignedOut, WidgetStates.page(true, 7, p, null, now))
        assertEquals(WidgetUi.NotConfigured, WidgetStates.page(false, null, null, null, now))
        assertEquals(WidgetUi.Loading, WidgetStates.page(false, 7, null, null, now))
        assertEquals(WidgetUi.Missing, WidgetStates.page(false, 7, p.copy(missing = true), null, now))
        assertEquals(WidgetUi.Content(null, empty = false, offline = false), WidgetStates.page(false, 7, p, null, now))
        assertEquals(WidgetUi.Content(null, empty = true, offline = false), WidgetStates.page(false, 7, p.copy(excerpt = " "), null, now))
        assertEquals(WidgetUi.Content(now, empty = false, offline = true), WidgetStates.page(false, 7, p, now + 5, now + 10))
    }
}
