package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.ui.chat.ChatUi
import io.github.yggdrasil_sh.saga.ui.theme.Dark
import io.github.yggdrasil_sh.saga.ui.theme.DarkAccent
import io.github.yggdrasil_sh.saga.ui.theme.Light
import io.github.yggdrasil_sh.saga.ui.theme.DarkSignals
import io.github.yggdrasil_sh.saga.ui.theme.LightAccent
import io.github.yggdrasil_sh.saga.ui.theme.LightSignals
import io.github.yggdrasil_sh.saga.ui.theme.SagaRadius
import io.github.yggdrasil_sh.saga.ui.theme.SagaShapes
import io.github.yggdrasil_sh.saga.ui.theme.sagaSegmentShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Design-System: Saga-Akzente erfuellen WCAG AA (Text >= 4.5:1) auf den Flaechen, auf denen sie stehen */
class ThemeContrastTest {
    @Test
    fun goldTextAufFlaechen() {
        for ((scheme, acc, name) in listOf(Triple(Light, LightAccent, "hell"), Triple(Dark, DarkAccent, "dunkel"))) {
            for (bg in listOf(scheme.background, scheme.surface, scheme.surfaceContainerLow, scheme.surfaceContainer)) {
                val c = ChatUi.contrast(acc.goldText, bg)
                assertTrue("goldText $name auf $bg: $c", c >= 4.5)
            }
            val soft = ChatUi.contrast(acc.onGoldSoft, acc.goldSoft)
            assertTrue("onGoldSoft $name: $soft", soft >= 4.5)
            val body = ChatUi.contrast(scheme.onSurfaceVariant, scheme.surfaceContainerLow)
            assertTrue("onSurfaceVariant auf Karte $name: $body", body >= 4.5)
        }
    }

    private val schemes = listOf(Light to "hell", Dark to "dunkel")

    /** alle Flaechen, auf denen Text stehen kann (Hintergrund und alle Container-Stufen) */
    private fun flaechen(s: ColorScheme) = listOf(
        "background" to s.background, "surface" to s.surface, "surfaceBright" to s.surfaceBright, "surfaceDim" to s.surfaceDim,
        "surfaceVariant" to s.surfaceVariant, "Lowest" to s.surfaceContainerLowest, "Low" to s.surfaceContainerLow,
        "Container" to s.surfaceContainer, "High" to s.surfaceContainerHigh, "Highest" to s.surfaceContainerHighest,
    )

    private fun hex(c: Color) = "#%06X".format(c.value.shr(32).toLong() and 0xFFFFFF)

    /** A11Y-17/-04: Haupt- und Nebentext (auch Stellen, die Runde 1 von outline auf onSurfaceVariant umgestellt hat) */
    @Test
    fun textAufAllenFlaechen() {
        for ((s, name) in schemes) for ((fl, bg) in flaechen(s)) {
            for ((rolle, fg) in listOf("onSurface" to s.onSurface, "onSurfaceVariant" to s.onSurfaceVariant, "error" to s.error)) {
                val c = ChatUi.contrast(fg, bg)
                assertTrue("$rolle auf $fl $name (${hex(fg)} auf ${hex(bg)}): $c", c >= 4.5)
            }
        }
    }

    /** Text auf den eigenen Containern (on*-Paare) */
    @Test
    fun onPaare() {
        for ((s, name) in schemes) {
            val paare = listOf(
                "primary" to (s.onPrimary to s.primary), "primaryContainer" to (s.onPrimaryContainer to s.primaryContainer),
                "secondary" to (s.onSecondary to s.secondary), "secondaryContainer" to (s.onSecondaryContainer to s.secondaryContainer),
                "tertiaryContainer" to (s.onTertiaryContainer to s.tertiaryContainer), "error" to (s.onError to s.error),
                "errorContainer" to (s.onErrorContainer to s.errorContainer), "inverseSurface" to (s.inverseOnSurface to s.inverseSurface),
                "background" to (s.onBackground to s.background),
            )
            for ((rolle, p) in paare) {
                val c = ChatUi.contrast(p.first, p.second)
                assertTrue("$rolle $name: $c", c >= 4.5)
            }
        }
    }

    /** outline (Feldraender) >= 3:1 gegen alle Flaechen bis surfaceContainer, wo Felder stehen (Web: --field) */
    @Test
    fun outlineAufFlaechen() {
        for ((s, name) in schemes) {
            for ((fl, bg) in listOf("background" to s.background, "Lowest" to s.surfaceContainerLowest, "Low" to s.surfaceContainerLow, "Container" to s.surfaceContainer)) {
                val c = ChatUi.contrast(s.outline, bg)
                assertTrue("outline auf $fl $name: $c", c >= 3.0)
            }
        }
        // dunkel reicht es bis zur hoechsten Stufe
        for ((fl, bg) in flaechen(Dark)) assertTrue("outline dunkel auf $fl", ChatUi.contrast(Dark.outline, bg) >= 3.0)
    }

    /**
     * Nachbesserung R3: aktive Zustaende (B/I/U/S, getoente Knoepfe, gewaehlte Segmente) in Gold-Soft - nie mehr gleich
     * der Werkzeugleiste/Dialogflaeche. Text darauf >= 4,5:1; die Flaeche unterscheidet sich im Farbton (Helligkeit
     * nur ~1,1:1), daher traegt bei den Format-Knoepfen ein Rand in goldText den Zustand (>= 3:1 gegen die Leiste und
     * gegen die Gold-Soft-Flaeche selbst).
     */
    @Test
    fun secondaryContainerIstGoldSoft() {
        for ((s, acc, name) in listOf(Triple(Light, LightAccent, "hell"), Triple(Dark, DarkAccent, "dunkel"))) {
            assertEquals("secondaryContainer $name", acc.goldSoft, s.secondaryContainer)
            assertEquals("onSecondaryContainer $name", acc.onGoldSoft, s.onSecondaryContainer)
            for ((fl, bg) in listOf("High" to s.surfaceContainerHigh, "Container" to s.surfaceContainer, "Low" to s.surfaceContainerLow,
                "Highest" to s.surfaceContainerHighest, "surfaceVariant" to s.surfaceVariant)) {
                assertTrue("secondaryContainer $name darf nicht gleich $fl sein", s.secondaryContainer != bg)
            }
            assertTrue("onSecondaryContainer $name", ChatUi.contrast(s.onSecondaryContainer, s.secondaryContainer) >= 4.5)
            for ((fl, bg) in listOf("High" to s.surfaceContainerHigh, "Container" to s.surfaceContainer, "Low" to s.surfaceContainerLow, "goldSoft" to acc.goldSoft)) {
                val c = ChatUi.contrast(acc.goldText, bg)
                assertTrue("Rand goldText $name gegen $fl: $c", c >= 3.0)
            }
        }
    }

    /** Neutralfarben = Web-Tokens (static/tokens.css) */
    @Test
    fun neutralWieWeb() {
        assertEquals(Color(0xFF1D2430), Light.onSurface); assertEquals(Color(0xFF5D6878), Light.onSurfaceVariant)
        assertEquals(Color(0xFFFDFCFA), Light.background); assertEquals(Color.White, Light.surfaceContainerLow)
        assertEquals(Color(0xFFF6F3EC), Light.surfaceContainer); assertEquals(Color(0xFFE7E2D7), Light.outlineVariant)
        assertEquals(Color(0xFF8F8670), Light.outline)
        assertEquals(Color(0xFFE6EBF2), Dark.onSurface); assertEquals(Color(0xFF8B97A8), Dark.onSurfaceVariant)
        assertEquals(Color(0xFF0F141C), Dark.background); assertEquals(Color(0xFF18202C), Dark.surfaceContainerLow)
        assertEquals(Color(0xFF202B3A), Dark.surfaceContainer); assertEquals(Color(0xFF263242), Dark.outlineVariant)
        // Gold bleibt
        assertEquals(Color(0xFF7A5A12), Light.primary); assertEquals(Color(0xFFC9A45C), Dark.primary)
    }

    /** A11Y-22: Signal-Textfarben >= 4,5:1 auf Hintergrund und Karte (wie im Web) */
    @Test
    fun signalfarben() {
        for ((sig, s, name) in listOf(Triple(LightSignals, Light, "hell"), Triple(DarkSignals, Dark, "dunkel"))) {
            for ((rolle, fg) in listOf("ok" to sig.ok, "warn" to sig.warn, "down" to sig.down, "overdue" to sig.overdue, "info" to sig.info)) {
                for (bg in listOf(s.background, s.surfaceContainerLow)) {
                    val c = ChatUi.contrast(fg, bg)
                    assertTrue("$rolle $name auf ${hex(bg)}: $c", c >= 4.5)
                }
            }
            // overdue ist ueberall Text-sicher
            for ((fl, bg) in flaechen(s)) assertTrue("overdue $name auf $fl", ChatUi.contrast(sig.overdue, bg) >= 4.5)
        }
        assertEquals(Color(0xFF1F7A33), LightSignals.ok); assertEquals(Color(0xFF3FB950), DarkSignals.ok)
        assertEquals(Color(0xFF2EA043), LightSignals.callGreen); assertEquals(Color(0xFFDA3633), DarkSignals.callRed)
    }

    /** Anruf-Buehne (immer dunkel): Schrift/Symbole auf --cl-*-Flaechen, Hinweis-Link in Info-Blau der dunklen Palette */
    @Test
    fun anrufBuehne() {
        for (sig in listOf(LightSignals, DarkSignals)) {
            assertTrue("Weiss auf callRed", ChatUi.contrast(Color.White, sig.callRed) >= 4.5)
            assertTrue("Weiss auf callGreen (Symbol)", ChatUi.contrast(Color.White, sig.callGreen) >= 3.0)
            for (bg in listOf(sig.callBg, sig.callPanel, sig.callBtn)) assertTrue("callFg auf ${hex(bg)}", ChatUi.contrast(sig.callFg, bg) >= 4.5)
            assertTrue("info auf callBg", ChatUi.contrast(DarkSignals.info, sig.callBg) >= 4.5)
        }
    }

    /** A11Y-19: Radien wie im Web (--r-sm 6, --r-md 10) */
    @Test
    fun radienWieWeb() {
        assertEquals(6.dp, SagaRadius.s); assertEquals(10.dp, SagaRadius.md)
        assertEquals(RoundedCornerShape(6.dp), SagaShapes.small); assertEquals(RoundedCornerShape(10.dp), SagaShapes.medium)
        assertEquals(RoundedCornerShape(14.dp), SagaShapes.large); assertEquals(RoundedCornerShape(16.dp), SagaShapes.extraLarge)
        assertEquals(RoundedCornerShape(6.dp), SagaShapes.extraSmall)
    }

    @Test
    fun segmentFormen() {
        // einzelner Eintrag: rundum gleich; mittlere Eintraege unterscheiden sich von Rand-Eintraegen
        assertTrue(sagaSegmentShape(0, 1) == sagaSegmentShape(0, 1))
        assertTrue(sagaSegmentShape(1, 3) != sagaSegmentShape(0, 3))
        assertTrue(sagaSegmentShape(2, 3) != sagaSegmentShape(0, 3))
    }
}
