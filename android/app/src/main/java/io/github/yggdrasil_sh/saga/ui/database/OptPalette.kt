package io.github.yggdrasil_sh.saga.ui.database

import io.github.yggdrasil_sh.saga.blocks.Gestalten
import io.github.yggdrasil_sh.saga.blocks.InlineForms

/**
 * A11Y-03/A11Y-20: Farben der Options-/Status-Chips wie im Web (.opt-<farbe>): farbiger Text --tc-* auf zarter
 * Flaeche --bc-*. Alle neun Farben erreichen hell wie dunkel mindestens 4,5:1 (OptPaletteTest).
 *
 * - Hell: feste Flaechen --bc-* aus tokens.css. Text --tc-*; nur Orange ist eine Spur dunkler (#A05000 statt
 *   #A65300), weil das Web-Paar mit 4,46:1 knapp unter AA liegt.
 * - Dunkel: Grundton --hue-* zu 30 % auf dem Seitengrund (--bg, SagaNight) - im Web halbdurchsichtig, hier deckend
 *   vorgemischt, damit der Kontrast auch auf hoeheren Karten (Board, Zeilen-Detail) nicht unter AA faellt.
 */
object OptPalette {
    val NAMES = listOf("gray", "brown", "orange", "yellow", "green", "blue", "purple", "pink", "red")

    /** Seitengrund dunkel (= SagaNight, Web --bg) */
    const val DARK_GROUND = 0x0F141C

    private val BC_LIGHT = mapOf(
        "gray" to 0xEBEAE7, "brown" to 0xF1E6DF, "orange" to 0xFAE5D3, "yellow" to 0xF8EFC8, "green" to 0xDDEDEA,
        "blue" to 0xDDEBF1, "purple" to 0xEAE4F2, "pink" to 0xF6E0EB, "red" to 0xFBE4E4,
    )

    /** unbekannte oder fehlende Farbe -> Grau (wie im Web) */
    fun norm(color: String?): String = if (color != null && color in NAMES) color else "gray"

    /** Textfarbe (ARGB, deckend) */
    fun textArgb(color: String?, dark: Boolean): Long {
        val c = norm(color)
        if (!dark && c == "orange") return 0xFFA05000L
        return InlineForms.textArgb(c, dark)
    }

    /** Flaeche (ARGB, deckend) */
    fun bgArgb(color: String?, dark: Boolean): Long {
        val c = norm(color)
        val rgb = if (dark) mix(Gestalten.baseRgb(c), DARK_GROUND, 0.30f) else BC_LIGHT.getValue(c)
        return 0xFF000000L or rgb.toLong()
    }

    /** a zu t Anteil ueber b (deckend) */
    private fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(v: Int, s: Int) = (v shr s) and 0xFF
        fun m(s: Int) = Math.round(ch(a, s) * t + ch(b, s) * (1 - t))
        return (m(16) shl 16) or (m(8) shl 8) or m(0)
    }
}
