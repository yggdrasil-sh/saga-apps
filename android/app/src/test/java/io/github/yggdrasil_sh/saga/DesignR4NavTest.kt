package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.ui.nav.AppNavigator
import io.github.yggdrasil_sh.saga.ui.nav.Dest
import io.github.yggdrasil_sh.saga.ui.nav.NavModel
import io.github.yggdrasil_sh.saga.ui.nav.NavModel.Tab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Android-Design-Runde 4 (Navigation): untere Leiste, Kopf-Aktionen, Seitenleiste, Papierkorb-Ziel, Kontomenue */
class DesignR4NavTest {
    @Test
    fun tabsLikeWebOnPhone() {
        assertEquals(listOf(Tab.TODAY, Tab.PAGES, Tab.SEARCH, Tab.CHATS), NavModel.tabs(chatOn = true))
        assertEquals(listOf(Tab.TODAY, Tab.PAGES, Tab.SEARCH), NavModel.tabs(chatOn = false))
    }

    @Test
    fun bottomBarOnlyCompactAndNotInEditorChatOrKeyboard() {
        assertTrue(NavModel.showBottomBar(compact = true, current = Dest.Today, imeVisible = false))
        assertTrue(NavModel.showBottomBar(true, Dest.Chats, false))
        assertTrue(NavModel.showBottomBar(true, Dest.Tasks, false))
        assertFalse(NavModel.showBottomBar(false, Dest.Today, false))           // Tablet: Rail bleibt
        assertFalse(NavModel.showBottomBar(true, Dest.Page(4), false))          // Editor
        assertFalse(NavModel.showBottomBar(true, Dest.Chat(7), false))          // Unterhaltung
        assertFalse(NavModel.showBottomBar(true, Dest.Search, imeVisible = true))
    }

    @Test
    fun selectedTab() {
        assertEquals(Tab.TODAY, NavModel.selectedTab(Dest.Today, drawerOpen = false))
        assertEquals(Tab.PAGES, NavModel.selectedTab(Dest.Today, drawerOpen = true))
        assertEquals(Tab.SEARCH, NavModel.selectedTab(Dest.Search, false))
        assertEquals(Tab.CHATS, NavModel.selectedTab(Dest.Chats, false))
        assertEquals(Tab.CHATS, NavModel.selectedTab(Dest.Chat(3), false))
        assertEquals(Tab.PAGES, NavModel.selectedTab(Dest.Page(3), false))
        assertNull(NavModel.selectedTab(Dest.Tasks, false))
        assertNull(NavModel.selectedTab(Dest.Settings, false))
    }

    @Test
    fun headActionsOnMainDestinations() {
        // Phone: Glocke ja, Lupe nein (unten gibt es "Suchen")
        assertEquals(NavModel.Head(bell = true, search = false), NavModel.head(Dest.Today, false, bottomBar = true, notifOn = true))
        // Tablet/breit: Glocke und Lupe
        assertEquals(NavModel.Head(true, true), NavModel.head(Dest.Tasks, false, bottomBar = false, notifOn = true))
        // Auf der Suche keine Lupe
        assertEquals(NavModel.Head(true, false), NavModel.head(Dest.Search, false, false, true))
        // Server ohne Benachrichtigungen: keine Glocke
        assertEquals(NavModel.Head(false, true), NavModel.head(Dest.Events, false, false, notifOn = false))
        // Unterseiten mit "Zurueck", Editor, Benachrichtigungen selbst, Einstellungen: nichts
        assertEquals(NavModel.Head(false, false), NavModel.head(Dest.Tasks, canGoBack = true, bottomBar = true, notifOn = true))
        assertEquals(NavModel.Head(false, false), NavModel.head(Dest.Page(1), false, true, true))
        assertEquals(NavModel.Head(false, false), NavModel.head(Dest.Notifications, false, true, true))
        assertEquals(NavModel.Head(false, false), NavModel.head(Dest.Settings, false, true, true))
        assertTrue(NavModel.head(Dest.Graph(), false, true, true).bell)
        assertTrue(NavModel.head(Dest.Trash, false, true, true).bell)
    }

    @Test
    fun sidebarOnlyWhatIsNotInBottomBar() {
        assertEquals(listOf(Dest.Tasks, Dest.Events), NavModel.sidebarMain(bottomBar = true, chatOn = true))
        assertEquals(listOf(Dest.Today, Dest.Chats, Dest.Tasks, Dest.Events), NavModel.sidebarMain(bottomBar = false, chatOn = true))
        assertEquals(listOf(Dest.Today, Dest.Tasks, Dest.Events), NavModel.sidebarMain(false, chatOn = false))
        // Kein Suchen, keine Benachrichtigungen, keine Dienste mehr als Hauptpunkt
        for (b in listOf(true, false)) {
            val m = NavModel.sidebarMain(b, true)
            assertFalse(Dest.Search in m || Dest.Notifications in m || Dest.Services in m)
        }
    }

    @Test
    fun sidebarFooter() {
        assertEquals(listOf(Dest.Services, Dest.Graph(), Dest.Trash, Dest.Settings), NavModel.sidebarFooter(graphOn = true))
        assertEquals(listOf(Dest.Services, Dest.Trash, Dest.Settings), NavModel.sidebarFooter(graphOn = false))
        assertTrue(NavModel.same(Dest.Graph(12), Dest.Graph()))
        assertTrue(NavModel.same(Dest.SettingsGeneral, Dest.Settings))
        assertTrue(NavModel.same(Dest.SettingsNotifications, Dest.Settings))
        assertTrue(NavModel.same(Dest.SettingsChats, Dest.Settings))
        assertTrue(NavModel.same(Dest.Chat(2), Dest.Chats))
        assertFalse(NavModel.same(Dest.Today, Dest.Trash))
    }

    @Test
    fun sharedIndexMatchesSidebarOrder() {
        // Kopf, Suchfeld, 2 Hauptpunkte, "Seiten", 3 Baumzeilen, "Neue Seite" -> "Mit mir geteilt" an 9
        assertEquals(9, NavModel.sharedIndex(mainCount = 2, favorites = 0, treeRows = 3))
        // + Favoriten (Abschnitt + 2)
        assertEquals(12, NavModel.sharedIndex(2, 2, 3))
        // leerer Baum: "Noch keine Seiten" zaehlt als Zeile
        assertEquals(7, NavModel.sharedIndex(2, 0, 0))
    }

    @Test
    fun trashIsOwnDestinationAndSurvivesProcessDeath() {
        assertEquals("trash", Dest.encode(Dest.Trash))
        assertEquals(Dest.Trash, Dest.decode("trash"))
        for (d in listOf(Dest.SettingsNotifications, Dest.SettingsChats)) assertEquals(d, Dest.decode(Dest.encode(d)))
        val nav = AppNavigator()
        nav.top(Dest.Trash)
        assertEquals(Dest.Trash, nav.root)
        assertFalse(nav.canGoBack)
    }

    @Test
    fun badgeText() {
        assertNull(NavModel.badgeText(0))
        assertEquals("7", NavModel.badgeText(7))
        assertEquals("99+", NavModel.badgeText(140))
    }

    @Test
    fun adminUrlOnlyForAdmins() {
        assertEquals("https://admin.example.org", NavModel.adminUrl("""{"name":"a","is_admin":true,"admin_url":"https://admin.example.org"}"""))
        assertNull(NavModel.adminUrl("""{"name":"a","is_admin":false,"admin_url":""}"""))
        assertNull(NavModel.adminUrl("""{"name":"a"}"""))
        assertNull(NavModel.adminUrl("""{"admin_url":"javascript:alert(1)"}"""))
        assertNull(NavModel.adminUrl("kein json"))
    }
}
