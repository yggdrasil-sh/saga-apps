package io.github.yggdrasil_sh.saga

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.yggdrasil_sh.saga.ui.chat.ChatUi
import io.github.yggdrasil_sh.saga.ui.theme.Dark
import io.github.yggdrasil_sh.saga.ui.theme.Light
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Android-Design-Runde 2 (CH-01, CH-13, A11Y-15, A11Y-18): Blasen wie `.ct-bubble` im Web. Gerechnet wird mit den
 * Schiefergrau-Werten aus `static/tokens.css` (nicht mit dem aktuellen App-Schema, das parallel umgestellt wird).
 */
class ChatBubbleWebTest {
    private fun hex(v: Long) = Color(0xFF000000 or v)

    // tokens.css hell: --bg #FDFCFA, --card #FFF, --fg #1D2430, --muted #5D6878, --border #E7E2D7, --accent #7A5A12
    private val light = lightColorScheme(
        surface = hex(0xFDFCFA), background = hex(0xFDFCFA), surfaceContainerLow = hex(0xFFFFFF),
        onSurface = hex(0x1D2430), onSurfaceVariant = hex(0x5D6878), outlineVariant = hex(0xE7E2D7),
    )
    // tokens.css dunkel: --bg #0F141C, --card #18202C, --fg #E6EBF2, --muted #8B97A8, --border #263242, --accent #C9A45C
    private val dark = darkColorScheme(
        surface = hex(0x0F141C), background = hex(0x0F141C), surfaceContainerLow = hex(0x18202C),
        onSurface = hex(0xE6EBF2), onSurfaceVariant = hex(0x8B97A8), outlineVariant = hex(0x263242),
    )
    private val goldLight = hex(0x7A5A12)
    private val goldDark = hex(0xC9A45C)

    /** WCAG 2.x, unabhaengig von ChatUi.contrast nachgerechnet */
    private fun lin(c: Float): Double = if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    private fun lum(c: Color) = 0.2126 * lin(c.red) + 0.7152 * lin(c.green) + 0.0722 * lin(c.blue)
    private fun wcag(a: Color, b: Color): Double {
        val x = lum(a) + 0.05
        val y = lum(b) + 0.05
        return maxOf(x, y) / minOf(x, y)
    }
    private fun rgb(c: Color) = listOf(c.red, c.green, c.blue).map { (it * 255).roundToInt() }

    @Test
    fun ownBubbleMatchesWebColorMix() {
        // Web: color-mix(in srgb, --accent 16%, --card) - hell ~#EAE5D9, dunkel ~#343534
        val l = ChatUi.bubble(light, true, goldLight)
        val d = ChatUi.bubble(dark, true, goldDark)
        assertEquals(listOf(234, 229, 217), rgb(l))
        assertEquals(listOf(52, 53, 52), rgb(d))
        assertEquals(1f, l.alpha)
        // fremde Blase = Karte, Rand = outlineVariant; eigener Rand mit 35 % Gold
        assertEquals(light.surfaceContainerLow, ChatUi.bubble(light, false, goldLight))
        assertEquals(dark.outlineVariant, ChatUi.bubbleBorder(dark, false, goldDark))
        assertEquals(rgb(ChatUi.mix(light.outlineVariant, goldLight, 0.35f)), rgb(ChatUi.bubbleBorder(light, true, goldLight)))
    }

    @Test
    fun textTimeAndTicksReadable() {
        for ((name, scheme, gold) in listOf(Triple("hell", light, goldLight), Triple("dunkel", dark, goldDark))) {
            for (mine in listOf(true, false)) {
                val bg = ChatUi.bubble(scheme, mine, gold)
                val text = wcag(scheme.onSurface, bg)
                val meta = wcag(ChatUi.meta(bg, scheme.onSurface), bg)
                assertTrue("Text $name mine=$mine: $text", text >= 7.0)
                assertTrue("Zeit/Haken $name mine=$mine: $meta", meta >= 4.5)
                // eigene Formel stimmt mit ChatUi.contrast ueberein
                assertEquals(meta, ChatUi.contrast(ChatUi.meta(bg, scheme.onSurface), bg), 0.02)
                // Antwort-Zitat: gedaempfter Text auf der Zitatflaeche
                val q = ChatUi.inset(bg, scheme.onSurface)
                assertTrue("Zitat $name mine=$mine", wcag(ChatUi.meta(q, scheme.onSurface), q) >= 4.5)
                // "Echtheit nicht bestätigt"
                assertTrue("unbestaetigt $name mine=$mine", wcag(ChatUi.unverifiedText(scheme, mine), bg) >= 4.5)
            }
            // gelesen-Haken in Gold auf der eigenen Blase
            val own = ChatUi.bubble(scheme, true, gold)
            val read = wcag(gold, own)
            assertTrue("Gelesen-Haken $name: $read", read >= 4.5)
        }
    }

    @Test
    fun tintFollowsScheme() {
        // App-Schema: hell kraeftiges Gold (goldText), dunkel Zier-Gold (wie Web --accent)
        assertEquals(goldLight, ChatUi.tint(Light))
        assertEquals(goldDark, ChatUi.tint(Dark))
        assertEquals(goldLight, ChatUi.tint(light))
        assertEquals(goldDark, ChatUi.tint(dark))
    }

    @Test
    fun widthAndGap() {
        assertEquals(328.8f, ChatUi.bubbleMax(411.dp).value, 0.01f)   // Phone: 80 %
        assertEquals(520f, ChatUi.bubbleMax(900.dp).value, 0.01f)     // Tablet: Deckel
        assertEquals(48f, ChatUi.GAP.value, 0f)
        assertEquals(200 to 280, ChatUi.mediaBox(48 to 280))          // schmales Hochformat
        assertEquals(280 to 210, ChatUi.mediaBox(280 to 210))
    }
}
