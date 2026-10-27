package io.github.yggdrasil_sh.saga.blocks

import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/**
 * Einheitliche Zahlenformate wie im Web (Fix #10): Prozent ganzzahlig ("41 %" statt "41,0377 %"), Waehrung immer mit
 * 2 Nachkommastellen in der Sprache der Person, Werte ueber 100 % werden echt angezeigt (119 %).
 */
object NumFormat {
    /** Prozentwert (42.6 -> "43 %"), kaufmaennisch gerundet; Schreibweise wie bisher: Zahl + " %" */
    fun percent(n: Double, locale: Locale): String {
        if (n.isNaN() || n.isInfinite()) return DbProps.num(n) + " %"
        val f = NumberFormat.getIntegerInstance(locale).apply { roundingMode = RoundingMode.HALF_UP; isGroupingUsed = true }
        val s = f.format(n)
        return (if (s == "-0") "0" else s) + " %"
    }

    /** Euro mit genau 2 Nachkommastellen ("1.430,50 €" bzw. "€1,430.50") */
    fun euro(n: Double, locale: Locale): String =
        NumberFormat.getCurrencyInstance(locale).apply {
            currency = Currency.getInstance("EUR")
            minimumFractionDigits = 2
            maximumFractionDigits = 2
            roundingMode = RoundingMode.HALF_UP
        }.format(n)

    /** Wert einer Zahl-/Rechen-Eigenschaft nach ihrem Format (percent, euro, sonst Zahl mit hoechstens 4 Stellen) */
    fun prop(n: Double, fmt: String?, locale: Locale): String = when (fmt) {
        "percent" -> percent(n, locale)
        "euro" -> euro(n, locale)
        else -> DbProps.localNum(locale)(n)
    }

    /** Fortschritt in ganzen Prozent, nicht gedeckelt (1430,5 von 1200 -> 119); Balken deckelt selbst */
    fun progressPercent(value: Double, max: Double): Int =
        if (max > 0 && !value.isNaN()) Math.round((value / max * 100).coerceAtLeast(0.0)).toInt() else 0
}
