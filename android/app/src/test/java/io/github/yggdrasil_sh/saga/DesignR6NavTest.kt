package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.ui.common.TopBarAlign
import io.github.yggdrasil_sh.saga.ui.login.LoginLayout
import io.github.yggdrasil_sh.saga.ui.nav.Dest
import io.github.yggdrasil_sh.saga.ui.nav.NavModel
import io.github.yggdrasil_sh.saga.ui.nav.NavModel.Layout
import io.github.yggdrasil_sh.saga.ui.nav.NavModel.Tab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Android-Design-Runde 6 (Tablet, r6-nav): Rail-Ziele, Kopf-Aktionen, Chats quer, Anmeldung, Titel-Flucht */
class DesignR6NavTest {
    @Test
    fun layoutByWidthAndArea() {
        assertEquals(Layout.BAR, NavModel.layout(411f, Dest.Today))
        assertEquals(Layout.BAR, NavModel.layout(599.9f, Dest.Chats))
        assertEquals(Layout.RAIL, NavModel.layout(600f, Dest.Today))
        assertEquals(Layout.RAIL, NavModel.layout(839f, Dest.Settings))
        assertEquals(Layout.SIDEBAR, NavModel.layout(840f, Dest.Today))
        assertEquals(Layout.SIDEBAR, NavModel.layout(1280f, Dest.Page(3)))
        // CH-14: quer in den Chats zur Rail eingeklappt
        assertEquals(Layout.RAIL, NavModel.layout(1280f, Dest.Chats))
        assertEquals(Layout.RAIL, NavModel.layout(840f, Dest.Chat(5)))
    }

    @Test
    fun railHasPhoneTargets() {
        assertEquals(listOf(Tab.TODAY, Tab.PAGES, Tab.SEARCH, Tab.CHATS), NavModel.railTabs(chatOn = true))
        assertEquals(listOf(Tab.TODAY, Tab.PAGES, Tab.SEARCH), NavModel.railTabs(chatOn = false))
        assertTrue(NavModel.tabsShown(Layout.BAR))
        assertTrue(NavModel.tabsShown(Layout.RAIL))
        assertFalse(NavModel.tabsShown(Layout.SIDEBAR))
        // Seitenleiste in der Schublade zeigt dann nur den Rest (Aufgaben, Termine; Dienste in der Fusszeile)
        assertEquals(listOf(Dest.Tasks, Dest.Events), NavModel.sidebarMain(NavModel.tabsShown(Layout.RAIL), chatOn = true))
    }

    @Test
    fun railSelection() {
        assertEquals(Tab.TODAY, NavModel.selectedTab(Dest.Today, false, Dest.Today))
        // Seite offen (ueber Heute geoeffnet) -> "Seiten" markiert
        assertEquals(Tab.PAGES, NavModel.selectedTab(Dest.Today, false, Dest.Page(9)))
        assertEquals(Tab.PAGES, NavModel.selectedTab(Dest.Chats, drawerOpen = true, current = Dest.Chats))
        assertEquals(Tab.CHATS, NavModel.selectedTab(Dest.Chats, false, Dest.Chat(2)))
        assertNull(NavModel.selectedTab(Dest.Tasks, false, Dest.Tasks))
        assertTrue(NavModel.railSettingsSelected(Dest.SettingsAccount, drawerOpen = false))
        assertFalse(NavModel.railSettingsSelected(Dest.Settings, drawerOpen = true))
        assertFalse(NavModel.railSettingsSelected(Dest.Today, false))
    }

    @Test
    fun headWithRailOnlyBellNoSearch() {
        val rail = NavModel.tabsShown(Layout.RAIL)
        assertEquals(NavModel.Head(bell = true, search = false), NavModel.head(Dest.Today, false, rail, notifOn = true))
        assertEquals(NavModel.Head(bell = true, search = false), NavModel.head(Dest.Chats, false, rail, notifOn = true))
        // feste Seitenleiste: Glocke und Lupe wie bisher
        assertEquals(NavModel.Head(true, true), NavModel.head(Dest.Tasks, false, NavModel.tabsShown(Layout.SIDEBAR), true))
    }

    @Test
    fun drawerOpensPagesOverTabOnlyUpright() {
        assertTrue(NavModel.drawerOpensOver(Layout.BAR, 400f))
        assertTrue(NavModel.drawerOpensOver(Layout.RAIL, 700f))
        // quer in den Chats: Seite ersetzt den Stapel -> feste Seitenleiste kommt zurueck
        assertFalse(NavModel.drawerOpensOver(Layout.RAIL, 1200f))
        assertFalse(NavModel.drawerOpensOver(Layout.SIDEBAR, 1200f))
        assertEquals(Layout.SIDEBAR, NavModel.layout(1200f, Dest.Page(1)))
    }

    @Test
    fun chatListWidthAndKeyboard() {
        assertEquals(320f, NavModel.chatListWidth(720f), 0.01f)
        assertEquals(330f, NavModel.chatListWidth(1100f), 0.01f)
        assertEquals(360f, NavModel.chatListWidth(1900f), 0.01f)
        assertTrue(NavModel.chatListHidden(twoPane = true, chatOpen = true, imeVisible = true, conversationFocused = true))
        // Tastatur fuer die Suche in der Liste: Liste bleibt
        assertFalse(NavModel.chatListHidden(true, true, true, conversationFocused = false))
        assertFalse(NavModel.chatListHidden(true, chatOpen = false, imeVisible = true, conversationFocused = true))
        assertFalse(NavModel.chatListHidden(twoPane = false, chatOpen = true, imeVisible = true, conversationFocused = true))
        assertFalse(NavModel.chatListHidden(true, true, imeVisible = false, conversationFocused = true))
    }

    @Test
    fun loginCardFromTabletWidth() {
        assertFalse(LoginLayout.card(411f))
        assertFalse(LoginLayout.card(599f))
        assertTrue(LoginLayout.card(600f))
        assertTrue(LoginLayout.card(1280f))
        assertEquals(480f, LoginLayout.CARD_MAX.value, 0.01f)
    }

    @Test
    fun topBarInsetAlignsWithCenteredColumn() {
        val c = TopBarAlign.CONTENT.value
        assertEquals(0f, TopBarAlign.inset(600f, c), 0.01f)
        assertEquals(0f, TopBarAlign.inset(760f, c), 0.01f)
        // 1280 dp quer minus 300 dp Seitenleiste = 980 dp -> je 110 dp
        assertEquals(110f, TopBarAlign.inset(980f, c), 0.01f)
        // breitere Spalte (z. B. Heute zweispaltig, ~1000 dp)
        assertEquals(0f, TopBarAlign.inset(980f, 1000f), 0.01f)
        assertEquals(100f, TopBarAlign.inset(1200f, 1000f), 0.01f)
    }
}
