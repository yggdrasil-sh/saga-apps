package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.ui.chat.ChatUi
import io.github.yggdrasil_sh.saga.ui.theme.Dark
import io.github.yggdrasil_sh.saga.ui.theme.DarkAccent
import io.github.yggdrasil_sh.saga.ui.theme.Light
import io.github.yggdrasil_sh.saga.ui.theme.LightAccent
import io.github.yggdrasil_sh.saga.ui.theme.sagaSegmentShape
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

    @Test
    fun segmentFormen() {
        // einzelner Eintrag: rundum gleich; mittlere Eintraege unterscheiden sich von Rand-Eintraegen
        assertTrue(sagaSegmentShape(0, 1) == sagaSegmentShape(0, 1))
        assertTrue(sagaSegmentShape(1, 3) != sagaSegmentShape(0, 3))
        assertTrue(sagaSegmentShape(2, 3) != sagaSegmentShape(0, 3))
    }
}
