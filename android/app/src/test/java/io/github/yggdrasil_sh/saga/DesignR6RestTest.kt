package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.ui.database.DbCompact
import io.github.yggdrasil_sh.saga.ui.database.RowSheetLogic
import io.github.yggdrasil_sh.saga.ui.login.LoginLayout
import io.github.yggdrasil_sh.saga.ui.nav.AppNavigator
import io.github.yggdrasil_sh.saga.ui.nav.Dest
import io.github.yggdrasil_sh.saga.ui.today.TodayLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** R6-Rest (Kleinkram nach Runde 6 „Tablet“) */
class DesignR6RestTest {
    private val src = File("src/main/java/io/github/yggdrasil_sh/saga/ui")

    @Test fun ansichtsPillenLassenSymboleWegWennSieUeberlaufen() {
        // erster Ueberlauf: Breite mit Symbolen merken (400 + 40); danach bleibt sie stehen
        assertEquals(440, DbCompact.pillsNeedWidth(0, maxScroll = 40, viewport = 400))
        assertEquals(0, DbCompact.pillsNeedWidth(0, maxScroll = 0, viewport = 400))
        assertEquals(0, DbCompact.pillsNeedWidth(0, maxScroll = 40, viewport = 0))     // noch nicht gemessen
        assertEquals(440, DbCompact.pillsNeedWidth(440, maxScroll = 0, viewport = 410)) // ohne Symbole passt es - bleibt
        assertTrue(DbCompact.pillsHideIcons(440, 400))
        assertTrue(DbCompact.pillsHideIcons(440, 300))   // Blatt faehrt ein: kein Flackern
        assertFalse(DbCompact.pillsHideIcons(440, 440))  // wieder breit genug: Symbole zurueck
        assertFalse(DbCompact.pillsHideIcons(0, 400))
        val code = File(src, "database/DatabaseView.kt").readText()
        assertTrue(code.contains("if (!noIcons) SagaIcon(viewIcon(x.type)"))
    }

    @Test fun seitenbaumBlendetUnterDerFusszeileAus() {
        val code = File(src, "nav/Sidebar.kt").readText()
        assertTrue(code.contains("listState.canScrollForward"))
        assertTrue(code.contains("BlendMode.DstOut"))
    }

    @Test fun weitermachenKartenAufDemTabletBreiter() {
        assertEquals(140f, TodayLogic.continueCardWidthDp(411f), 0.01f)
        assertEquals(140f, TodayLogic.continueCardWidthDp(599f), 0.01f)
        assertEquals(184f, TodayLogic.continueCardWidthDp(600f), 0.01f)
        assertEquals(184f, TodayLogic.continueCardWidthDp(1200f), 0.01f)
    }

    @Test fun anmeldekarteAufNiedrigerFlaecheDichter() {
        // Tablet quer: 800 dp hoch abzueglich Leisten ~ 728 dp - bei 100 % gross, bei 130 % Schrift dichter
        assertFalse(LoginLayout.cardCompact(728f, 1f))
        assertTrue(LoginLayout.cardCompact(728f, 1.3f))
        assertTrue(LoginLayout.cardCompact(600f, 1f))      // niedriges Fenster (geteilter Bildschirm)
        assertFalse(LoginLayout.cardCompact(1100f, 1.3f))  // Tablet hoch
        assertEquals(72f, LoginLayout.logo(card = true, viewportHDp = 728f, fontScale = 1.3f).value, 0.01f)
        assertEquals(104f, LoginLayout.logo(card = true, viewportHDp = 728f).value, 0.01f)
        assertEquals(104f, LoginLayout.logo(card = false, viewportHDp = 380f, fontScale = 1.3f).value, 0.01f) // Phone unveraendert
        assertEquals(14f, LoginLayout.gap(card = false, viewportHDp = 380f).value, 0.01f)
        assertEquals(10f, LoginLayout.gap(card = true, viewportHDp = 728f, fontScale = 1.3f).value, 0.01f)
        assertEquals(20f, LoginLayout.cardPadding(728f, 1.3f).value, 0.01f)
        assertEquals(28f, LoginLayout.cardPadding(728f).value, 0.01f)
    }

    @Test fun phoneQuerOhneKarte() {
        // 915 x 411 (Pixel quer) und 851 x 393: keine Karte; Tablet quer 1280 x 800: Karte
        assertFalse(LoginLayout.card(915f, 411f))
        assertFalse(LoginLayout.card(851f, 393f))
        assertTrue(LoginLayout.card(1280f, 800f))
    }

    @Test fun zurueckSchliesstErstDieSchubladeDannDasBlatt() {
        assertEquals(RowSheetLogic.Back.CLOSE_DRAWER, RowSheetLogic.back(sheetOpen = true, drawerOpen = true))
        assertEquals(RowSheetLogic.Back.CLOSE_SHEET, RowSheetLogic.back(sheetOpen = true, drawerOpen = false))
        assertEquals(RowSheetLogic.Back.NAVIGATION, RowSheetLogic.back(sheetOpen = false, drawerOpen = false))
        assertTrue(File(src, "page/RowSheetHost.kt").readText().contains("LocalNavDrawerOpen.current"))
    }

    @Test fun linkAufDieUmgebendeSeiteRuftDenHaken() {
        val nav = AppNavigator(listOf(Dest.Today, Dest.Page(7)))
        var hit: Dest? = null
        nav.onSameTarget = { d -> hit = d; true }
        nav.push(Dest.Page(7))
        assertEquals(Dest.Page(7), hit)
        assertEquals(listOf(Dest.Today, Dest.Page(7)), nav.stack.toList()) // Stapel unveraendert
        hit = null
        nav.push(Dest.Page(8))
        assertEquals(null, hit)
        assertEquals(Dest.Page(8), nav.current)
        assertTrue(File(src, "page/PageScreen.kt").readText().contains("nav.onSameTarget = hook"))
    }
}
