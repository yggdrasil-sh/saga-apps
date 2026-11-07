package io.github.yggdrasil_sh.saga

import androidx.compose.ui.input.key.Key
import io.github.yggdrasil_sh.saga.ui.chat.ChatUi
import io.github.yggdrasil_sh.saga.ui.common.OfflineBarColors
import io.github.yggdrasil_sh.saga.ui.login.LoginLayout
import io.github.yggdrasil_sh.saga.ui.tasks.TaskRowLogic
import io.github.yggdrasil_sh.saga.ui.theme.Dark
import io.github.yggdrasil_sh.saga.ui.theme.DarkAccent
import io.github.yggdrasil_sh.saga.ui.theme.Light
import io.github.yggdrasil_sh.saga.ui.theme.LightAccent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Nachbesserung Android-Design-Runde 1 (Anmeldung, Aufgaben, Offline-Leiste) */
class R1NavNachbesserungTest {
    @Test
    fun ueberfaelligPraefixNurOhneGruppen() {
        assertTrue(TaskRowLogic.overduePrefix(overdue = true, grouped = false))   // "Heute"
        assertFalse(TaskRowLogic.overduePrefix(overdue = true, grouped = true))   // Gruppe "Ueberfaellig" in Aufgaben
        assertFalse(TaskRowLogic.overduePrefix(overdue = false, grouped = false))
    }

    @Test
    fun prioritaetFlaggeOderText() {
        assertTrue(TaskRowLogic.showFlag(1, done = false))
        assertFalse(TaskRowLogic.showPrioText(1, done = false))                   // Flagge da -> kein Text
        assertFalse(TaskRowLogic.showFlag(1, done = true))
        assertTrue(TaskRowLogic.showPrioText(1, done = true))                     // erledigt: keine Flagge, Text bleibt
        assertFalse(TaskRowLogic.showFlag(2, done = false))
        assertFalse(TaskRowLogic.showPrioText(null, done = false))
    }

    @Test
    fun flaggeInGoldMitKontrast() {
        // Symbol (keine Schrift): WCAG 1.4.11 verlangt >= 3:1 zur Flaeche
        for ((scheme, acc) in listOf(Light to LightAccent, Dark to DarkAccent)) {
            for (bg in listOf(scheme.background, scheme.surface)) {
                val c = ChatUi.contrast(acc.goldText, bg)
                assertTrue("Flagge auf $bg: $c", c >= 3.0)
            }
        }
    }

    @Test
    fun offlineLeisteRuhigUndLesbar() {
        for ((scheme, name) in listOf(Light to "hell", Dark to "dunkel")) {
            assertEquals(scheme.surfaceContainerHigh, OfflineBarColors.container(scheme))
            val c = ChatUi.contrast(OfflineBarColors.content(scheme), OfflineBarColors.container(scheme))
            assertTrue("Offline-Leiste $name: $c", c >= 4.5)
        }
    }

    @Test
    fun anmeldungOhneSprungUndEnter() {
        // Logo und Abstand oben sind fest: Feld und Knoepfe bleiben beim Oeffnen der Tastatur auf derselben Hoehe
        assertEquals(104f, LoginLayout.LOGO.value)
        assertEquals(48f, LoginLayout.TOP_PADDING.value)
        assertTrue(LoginLayout.isEnter(Key.Enter))
        assertTrue(LoginLayout.isEnter(Key.NumPadEnter))
        assertFalse(LoginLayout.isEnter(Key.Tab))
    }
}
