package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.chat.ChatSummary
import io.github.yggdrasil_sh.saga.data.TaskCounts
import io.github.yggdrasil_sh.saga.data.TaskEntry
import io.github.yggdrasil_sh.saga.data.TaskItem
import io.github.yggdrasil_sh.saga.data.TasksWithSources
import io.github.yggdrasil_sh.saga.ui.nav.AppNavigator
import io.github.yggdrasil_sh.saga.ui.nav.Dest
import io.github.yggdrasil_sh.saga.ui.nav.NavModel
import io.github.yggdrasil_sh.saga.ui.pages.HitKind
import io.github.yggdrasil_sh.saga.ui.pages.SearchHits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Nachbesserung Android-Design-Runde 4 (Navigation/Einstellungen): 46b, 22, 68, 17, 39/79b, 20, Texte */
class DesignR4FixNavTest {
    // ---- 46b: Chats-Badge zaehlt Gespraeche
    @Test
    fun chatBadgeCountsConversationsNotMessages() {
        // Wie im Fund: 3 Nachrichten in einem Gespraech -> 1
        val chats = listOf(ChatSummary(1, unread = 3), ChatSummary(2), ChatSummary(3))
        assertEquals(1, NavModel.unreadChats(chats, totalMessages = 3))
        val many = listOf(ChatSummary(1, unread = 2), ChatSummary(2, unread = 5), ChatSummary(3, unreadMark = true))
        assertEquals(3, NavModel.unreadChats(many, 8))
    }

    @Test
    fun chatBadgeSameRulesAsServerCounter() {
        val chats = listOf(
            ChatSummary(1, unread = 4, muted = true),           // stumm zaehlt nicht
            ChatSummary(2, unread = 1, archived = true),        // archiviert zaehlt nicht
            ChatSummary(3, unread = 1, request = true),         // offene Anfrage zaehlt nicht
            ChatSummary(4, unread = 2),
        )
        assertEquals(1, NavModel.unreadChats(chats, 2))
    }

    @Test
    fun chatBadgeServerSumWinsAndUnknownList() {
        assertEquals(0, NavModel.unreadChats(listOf(ChatSummary(1, unread = 3)), 0))   // Summe 0: nichts
        assertEquals(NavModel.CHATS_UNKNOWN, NavModel.unreadChats(null, 3))            // Liste fehlt: Punkt
        assertEquals(0, NavModel.unreadChats(null, 0))
        assertEquals(1, NavModel.unreadChats(listOf(ChatSummary(1)), 2))              // Liste hinkt: wenigstens 1
        assertEquals(2, NavModel.unreadChats((1L..5L).map { ChatSummary(it, unread = 1) }, 2))  // nie mehr als Summe
    }

    // ---- 22: Seite aus der Schublade ueber dem Leistenziel, Zurueck -> dorthin
    @Test
    fun pageFromDrawerReturnsToBarDestination() {
        val nav = AppNavigator(listOf(Dest.Chats))
        nav.openOver(Dest.Page(5))
        assertEquals(listOf(Dest.Chats, Dest.Page(5)), nav.stack.toList())
        assertTrue(nav.pop())
        assertEquals(Dest.Chats, nav.current)
    }

    @Test
    fun openOverDropsIntermediateAndReplacesPageRoot() {
        val nav = AppNavigator(listOf(Dest.Chats, Dest.Chat(3)))
        nav.openOver(Dest.Page(5))
        assertEquals(listOf(Dest.Chats, Dest.Page(5)), nav.stack.toList())
        nav.openOver(Dest.Page(6))
        assertEquals(listOf(Dest.Chats, Dest.Page(6)), nav.stack.toList())
        val old = AppNavigator(listOf(Dest.Page(1)))                                  // alte Wurzel = Seite
        old.openOver(Dest.Page(2))
        assertEquals(listOf(Dest.Page(2)), old.stack.toList())
        val same = AppNavigator(listOf(Dest.Search))
        same.openOver(Dest.Search)
        assertEquals(listOf(Dest.Search), same.stack.toList())
    }

    // ---- 68: Papierkorb als Unterziel
    @Test
    fun trashIsPushedFromFooter() {
        assertTrue(NavModel.footerPush(Dest.Trash))
        assertFalse(NavModel.footerPush(Dest.Settings))
        assertFalse(NavModel.footerPush(Dest.Services))
        val nav = AppNavigator(listOf(Dest.Chats))
        nav.push(Dest.Trash)
        assertTrue(nav.canGoBack)                       // SagaTopBar zeigt den Zurueck-Pfeil
        nav.pop()
        assertEquals(Dest.Chats, nav.current)
        // ohne Kopf-Glocke im Unterziel
        assertFalse(NavModel.head(Dest.Trash, canGoBack = true, bottomBar = true, notifOn = true).bell)
    }

    // ---- 39/79b: Fusszeile in zwei Reihen
    @Test
    fun footerInRowsOfTwo() {
        val four = NavModel.sidebarFooter(graphOn = true)
        assertEquals(listOf(listOf(Dest.Services, Dest.Graph()), listOf(Dest.Trash, Dest.Settings)), NavModel.footerRows(four))
        val three = NavModel.sidebarFooter(graphOn = false)
        assertEquals(listOf(listOf(Dest.Services, Dest.Trash), listOf(Dest.Settings)), NavModel.footerRows(three))
    }

    // ---- 17: Rot nur fuer Ueberfaelliges
    @Test
    fun overdueCountIsPartOfBadge() {
        val today = "2026-11-08"
        fun e(id: Long, due: String?, overdue: Boolean = false, done: Boolean = false) =
            TaskEntry(key = "t:$id", kind = "task", id = id, due = due, overdue = overdue, done = done)
        val merged = TasksWithSources(
            items = listOf(e(1, today), e(2, "2026-11-01", overdue = true), e(3, "2026-11-02"), e(4, "2026-11-03", done = true), e(5, null)),
            sources = emptyList(), people = emptyMap(),
        )
        assertEquals(3, TaskCounts.badge(merged, emptyList(), today))
        assertEquals(2, NavModel.overdueCount(merged, emptyList(), today))
        val own = listOf(TaskItem(1, "a", due = today), TaskItem(2, "b", due = "2026-11-01"))
        assertEquals(1, NavModel.overdueCount(null, own, today))
        assertEquals(0, NavModel.overdueCount(null, listOf(TaskItem(1, "a", due = today)), today))
    }

    // ---- 20: Datenbanken ohne Ausschnitt
    @Test
    fun databaseHitsWithoutSnippet() {
        assertFalse(SearchHits.showSnippet(HitKind.DATABASE))
        assertTrue(SearchHits.showSnippet(HitKind.PAGE))
        assertTrue(SearchHits.showSnippet(HitKind.ROW))
    }

    // ---- Texte: alle Sprachen, Plurals vollstaendig
    private fun res(dir: String) = File("src/main/res/$dir/strings_r4nav.xml").readText()

    @Test
    fun newTextsInAllLanguagesWithPlurals() {
        val dirs = listOf("values", "values-de", "values-es", "values-ru", "values-th")
        for (d in dirs) {
            val s = res(d)
            assertTrue(d, s.contains("name=\"r4nav_chats_unread_some\""))
            for (p in listOf("r4nav_unread_chats", "r4nav_tasks_due", "r4nav_tasks_overdue")) {
                val block = s.substringAfter("<plurals name=\"$p\">").substringBefore("</plurals>")
                val need = when (d) {
                    "values-ru" -> listOf("one", "few", "many", "other")
                    "values-es" -> listOf("one", "many", "other")
                    "values-th" -> listOf("other")
                    else -> listOf("one", "other")
                }
                need.forEach { q -> assertTrue("$d $p $q", block.contains("quantity=\"$q\"") && block.contains("%d")) }
            }
        }
        assertTrue(res("values-de").contains("%d ungelesenes Gespräch"))
    }
}
