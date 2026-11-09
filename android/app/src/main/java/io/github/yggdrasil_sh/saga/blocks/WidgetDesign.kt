package io.github.yggdrasil_sh.saga.blocks

import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Widget-Design-Runde (9.11.): reine Gestaltungslogik der Seiten-Widgets und Diagramme wie im Web (static/pages.js
 * wgVal / chartColors / chartSvg, static/style.css .wg/.ch-/.hb-). Ohne Compose, damit testbar (WidgetDesignTest).
 */
object WidgetDesign {
    /** Palette ohne Optionsfarben (Web ch-s0..7) */
    val PALETTE = listOf("blue", "orange", "green", "purple", "pink", "yellow", "red", "brown")

    /** Farbe eines Eintrags: Name der Palette/Option, [OTHER] (grau: "Andere"/"Leer") oder [ONE] (eine Reihe: Widget-Farbe bzw. blau) */
    const val OTHER = "other"
    const val ONE = "one"

    /**
     * Farben je Kategorie bzw. Reihe (key == null: "Leer", "__other__": "Andere" - immer neutral). "gray"/leer = keine
     * Farbe. Optionsfarben nur, wenn mindestens 2 verschiedene echte vorkommen; sonst Palette der Reihe nach bzw. bei
     * [one] (eine Reihe ohne Optionsfarben, kein Kreis) einfarbig.
     */
    fun chartColors(items: List<Pair<String?, String?>>, one: Boolean): List<String> {
        fun neutral(k: String?) = k == null || k == "__other__"
        val real = items.filter { !neutral(it.first) }.mapNotNull { it.second?.takeIf { c -> c.isNotEmpty() && c != "gray" } }.toSet()
        val useOpt = real.size >= 2
        var k = 0
        return items.map { (key, color) ->
            when {
                neutral(key) -> OTHER
                useOpt -> color?.takeIf { it.isNotEmpty() && it != "gray" } ?: "gray"
                one -> ONE
                else -> PALETTE[k++ % PALETTE.size]
            }
        }
    }

    // ---------------------------------------------------------------- Wert mit kleiner Einheit

    data class ValueParts(val prefix: String, val main: String, val unit: String)

    private const val WS = "\\s\\u00a0\\u202f"
    private val UNIT_AFTER = Regex("""^(.*?[\d)])[$WS]*([^\d$WS.,\-−+()]{1,4})$""")
    private val UNIT_BEFORE = Regex("""^([^\d\-−+$WS]{1,4})[$WS]*(.*\d.*)$""")

    /** "2.250,00 €" -> 2.250,00 + €, "62 %" -> 62 + %, "42 Tage" -> 42 + Tage, "$5" -> $ + 5 (Web wgVal) */
    fun splitValue(s: String): ValueParts {
        UNIT_AFTER.matchEntire(s)?.let { return ValueParts("", it.groupValues[1], it.groupValues[2]) }
        UNIT_BEFORE.matchEntire(s)?.let { return ValueParts(it.groupValues[1], it.groupValues[2], "") }
        return ValueParts("", s, "")
    }

    /** Einheitswort aus einem Plural-Text mit Zahl ("42 Tage" -> "Tage"); ohne Zahl im Text der ganze Text */
    fun unitWord(text: String, number: String): String = text.replace(number, "").trim()

    /** Schriftgroesse des Werts (sp): clamp(1,1 rem, 13 % der Kartenbreite, Hoechstwert je Groesse) - Wert bricht nie um */
    fun valueSp(widthDp: Float, size: String): Float {
        val maxSp = when (size) { "s" -> 19.2f; "l" -> 40f; else -> 28f }
        return min(maxSp, max(17.6f, widthDp * 0.13f))
    }

    // ---------------------------------------------------------------- Groessen

    /** Innenabstand senkrecht/waagrecht (dp), Mindesthoehe (dp) je Groesse S/M/L */
    data class Box(val vPad: Int, val hPad: Int, val minHeight: Int, val gap: Int)

    fun box(size: String): Box = when (size) {
        "s" -> Box(10, 12, 72, 6)
        "l" -> Box(18, 20, 144, 10)
        else -> Box(14, 16, 104, 8)
    }

    /** Diagrammhoehe (dp) nach Groesse; ohne Groesse (Datenbank-Ansicht) nach Breite */
    fun chartHeight(size: String?, widthDp: Float): Float {
        if (size == null) return max(160f, min(widthDp * 0.55f, 300f))
        val h = when (size) { "s" -> 140f; "l" -> 300f; else -> 220f }
        return min(h, max(120f, widthDp * 0.8f)).let { Math.round(it).toFloat() }
    }

    data class Ring(val diameter: Float, val beside: Boolean, val legendMax: Int)

    /** Kreis/Ring: ab 300 dp Legende daneben; Durchmesser nie bildschirmfuellend */
    fun ring(widthDp: Float, heightDp: Float): Ring {
        val beside = widthDp >= 300f
        val d = Math.round(if (beside) min(heightDp, widthDp * 0.45f) else min(min(heightDp, widthDp), 220f)).toFloat()
        val tiny = widthDp < 180f
        return Ring(d, beside, if (beside) max(3, floor(d / 24f).toInt()) else if (tiny) 4 else 6)
    }

    /** Legende mit hoechstens max Zeilen: gezeigte Eintraege und "+ n weitere" (0 = keine Zeile) */
    fun legendCut(n: Int, maxRows: Int): Pair<Int, Int> = if (n > maxRows) (maxRows - 1) to (n - maxRows + 1) else n to 0

    /** Anzahl y-Ticks: schmal (< 260 dp) 3, niedrig (< 180 dp) 4, sonst 5 */
    fun tickCount(widthDp: Float, heightDp: Float): Int = if (widthDp < 260f) 3 else if (heightDp < 180f) 4 else 5

    /** Schriftgroesse des Gesamtwerts im Ring (dp) bei innerem Radius ri */
    fun ringValueSize(ri: Float, text: String): Float = max(12f, min(26f, min(ri * 0.42f, ri * 1.7f / max(3, text.length) * 1.6f)))

    // ---------------------------------------------------------------- Achsen: kompakte Zahlen

    private fun compactWords(locale: Locale): Triple<String, String, String> = when (locale.language) {
        "de" -> Triple(" Tsd.", " Mio.", " Mrd.")
        "es" -> Triple(" mil", " M", " mil M")
        "ru" -> Triple(" тыс.", " млн", " млрд")
        else -> Triple("K", "M", "B")
    }

    /** Achsenbeschriftung: kompakt ("12 Tsd.", "1,5 Mio."), Prozent ganzzahlig; volle Zahl in Tabelle/Legende */
    fun axisText(v: Double, fmt: String, locale: Locale): String {
        if (fmt == "pct") return NumberFormat.getPercentInstance(locale).apply { maximumFractionDigits = 0; roundingMode = RoundingMode.HALF_UP }.format(v / 100.0)
        val a = abs(v)
        val (k, m, b) = compactWords(locale)
        val (x, suf) = when {
            a >= 1e9 -> v / 1e9 to b
            a >= 1e6 -> v / 1e6 to m
            a >= 1e4 -> v / 1e3 to k
            else -> v to ""
        }
        val n = NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 1; roundingMode = RoundingMode.HALF_UP }.format(x) + suf
        if (fmt != "eur") return n
        if (suf.isEmpty()) {
            return NumberFormat.getCurrencyInstance(locale).apply {
                currency = Currency.getInstance("EUR"); minimumFractionDigits = 0; maximumFractionDigits = 1
            }.format(v)
        }
        return "$n €"
    }

    // ---------------------------------------------------------------- Zitat

    private val QUOTE_SRC = Regex("""^([\s\S]{8,}?(?:[.!?…"“”»«’']|\n))\s*(?:—|–|--)\s*(\p{Lu}[^—–\n]{1,59})$""")

    /** Zitat und Quelle: "Text. – Name" bzw. Zeilenumbruch + "— Name" -> (Text, Name); sonst (Text, null) */
    fun quoteSplit(text: String): Pair<String, String?> {
        val t = text.trim()
        val m = QUOTE_SRC.matchEntire(t) ?: return t to null
        val src = m.groupValues[2].trim()
        if (src.split(Regex("\\s+")).size > 6 || Regex("[.!?…:]$").containsMatchIn(src)) return t to null
        return m.groupValues[1].trimEnd() to src
    }

    /** Zeilen des Zitats je Groesse S/M/L */
    fun quoteLines(size: String): Int = when (size) { "s" -> 4; "l" -> 8; else -> 6 }

    // ---------------------------------------------------------------- Gewohnheiten

    /**
     * shown: gezeigte (letzte) Tage, heat: Quadrate statt Kreise, cut: Hinweis "letzte n Tage" (null = keiner),
     * wide: eine Zeile je Gewohnheit (Name | Tage | Quote), sonst Name+Quote oben und Tage darunter.
     */
    data class HabitLayout(val shown: Int, val heat: Boolean, val cut: Int?, val wide: Boolean)

    fun habitLayout(days: Int, widthDp: Float, size: String): HabitLayout {
        val nd = max(days, 1)
        val wide = widthDp >= 420f
        if (nd <= 7) return HabitLayout(nd, false, null, wide)
        if (widthDp < 240f) return HabitLayout(7, false, 7, false)
        if (size == "s") return HabitLayout(7, true, 7, wide)
        if (widthDp < 420f) return HabitLayout(min(nd, 14), true, if (nd > 14) 14 else null, false)
        return HabitLayout(nd, true, null, true)
    }

    /** Beschriftung der Heatmap-Spalte: nur jeder 5. Tag und heute */
    fun heatLabel(dayOfMonth: Int, today: Boolean): String = if (dayOfMonth % 5 == 0 || today) dayOfMonth.toString() else ""

    // ---------------------------------------------------------------- Wetter

    /** Unter 330 dp Tage als Liste (Tag | Symbol | max/min | Regen) statt Kacheln */
    fun weatherAsList(widthDp: Float): Boolean = widthDp < 330f

    /** Regen in der Liste erst ab 180 dp */
    fun weatherRain(widthDp: Float): Boolean = widthDp >= 180f

    /** Ebene eines Wetter-Symbols: Schriftfarbe (Wolke, Schnee), Akzent (Sonne, Blitz), Blau (Regentropfen) - wie Web .wx-acc/.wx-wet */
    enum class WxLayer { FG, ACCENT, WET }

    data class WxPart(val layer: WxLayer, val path: String)

    /** SVG-Kreis als Pfad (fuer den Pfad-Parser) */
    private fun circle(cx: Float, cy: Float, r: Float): String = "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0z"

    private const val WX_CLOUD_LOW = "M7 13.5h10.5a3.8 3.8 0 0 0 .5-7.5 5.5 5.5 0 0 0-10.5 1.4A3.1 3.1 0 0 0 7 13.5z"

    /**
     * Linien-Symbole des Wetters wie im Web (static/pages.js WX_SVG, 24er-Raster, Strich 1,6, runde Enden) - statt Emoji,
     * die je System anders aussehen.
     */
    val WX_SVG: Map<String, List<WxPart>> = mapOf(
        "sun" to listOf(
            WxPart(WxLayer.ACCENT, circle(12f, 12f, 4.2f)),
            WxPart(WxLayer.ACCENT, "M12 2.5v2.2M12 19.3v2.2M2.5 12h2.2M19.3 12h2.2M5.3 5.3l1.6 1.6M17.1 17.1l1.6 1.6M5.3 18.7l1.6-1.6M17.1 6.9l1.6-1.6"),
        ),
        "part" to listOf(
            WxPart(WxLayer.ACCENT, circle(8.5f, 8.5f, 3.2f)),
            WxPart(WxLayer.ACCENT, "M8.5 2.2v1.6M2.2 8.5h1.6M4 4l1.1 1.1M13 4l-1.1 1.1"),
            WxPart(WxLayer.FG, "M8 19.5h9a3.5 3.5 0 0 0 .4-7 5 5 0 0 0-9.6 1.3A2.9 2.9 0 0 0 8 19.5z"),
        ),
        "cloud" to listOf(WxPart(WxLayer.FG, "M7 18.5h10.5a4 4 0 0 0 .5-8 5.8 5.8 0 0 0-11.1 1.5A3.3 3.3 0 0 0 7 18.5z")),
        "fog" to listOf(
            WxPart(WxLayer.FG, "M7 13.5h10.5a3.6 3.6 0 0 0 .5-7.1 5.3 5.3 0 0 0-10.1 1.3A3 3 0 0 0 7 13.5z"),
            WxPart(WxLayer.FG, "M4 17h16M6 20.5h12"),
        ),
        "drizzle" to listOf(
            WxPart(WxLayer.FG, "M7 14.5h10.5a3.8 3.8 0 0 0 .5-7.5 5.5 5.5 0 0 0-10.5 1.4A3.1 3.1 0 0 0 7 14.5z"),
            WxPart(WxLayer.WET, "M9 17.5v1M13 17.5v1M17 17.5v1"),
        ),
        "rain" to listOf(WxPart(WxLayer.FG, WX_CLOUD_LOW), WxPart(WxLayer.WET, "M8.5 16.5l-1 3M12.5 16.5l-1 3M16.5 16.5l-1 3")),
        "snow" to listOf(WxPart(WxLayer.FG, WX_CLOUD_LOW), WxPart(WxLayer.FG, "M8.5 17.2v2.6M7.2 18.5h2.6M15.5 17.2v2.6M14.2 18.5h2.6")),
        "storm" to listOf(WxPart(WxLayer.FG, WX_CLOUD_LOW), WxPart(WxLayer.ACCENT, "M12.5 14.5l-2 3.5h3l-2 3.5")),
    )

    /** WMO-Code -> Symbol (Web wxKind); null = kein Code (Web zeigt dann "·") */
    fun wxKind(code: Int?): String? {
        val c = code ?: return null
        return when {
            c >= 95 -> "storm"
            c >= 85 -> "snow"
            c >= 80 -> "rain"
            c >= 71 -> "snow"
            c >= 61 -> "rain"
            c >= 51 -> "drizzle"
            c >= 45 -> "fog"
            c >= 3 -> "cloud"
            c >= 1 -> "part"
            else -> "sun"
        }
    }

    /** Flamme der Serie (Web WG_FLAME, gefuellt, Farbe orange) */
    const val FLAME = "M12 2c1 3.5 5 6 5 11a5 5 0 0 1-10 0c0-2.2 1-3.8 2.3-5 .2 1.6 1 2.7 2.2 3.2C11 8.6 11.3 5.3 12 2z"

    // ---------------------------------------------------------------- Spalten

    /**
     * Spalten-Bloecke: Umbruch nach Breite des Blocks (Web: container cols): < 440 dp untereinander; 3 Spalten bis 660 dp
     * 2 + 1; 4 bis 880 dp 2x2; 5 bis 660 dp Zweier-, bis 1100 dp Dreierreihen; 6 bis 660/1330 dp Zweier-/Dreierreihen.
     */
    fun columnRows(cols: Int, widthDp: Float): List<List<Int>> {
        if (cols <= 0) return emptyList()
        val all = (0 until cols).toList()
        if (widthDp <= 440f) return all.map { listOf(it) }
        val per = when {
            cols <= 2 -> cols
            cols == 3 -> if (widthDp <= 660f) 2 else 3
            cols == 4 -> if (widthDp <= 880f) 2 else 4
            cols == 5 -> if (widthDp <= 660f) 2 else if (widthDp <= 1100f) 3 else 5
            else -> if (widthDp <= 660f) 2 else if (widthDp <= (if (cols == 6) 1330f else 1100f)) 3 else cols
        }
        return all.chunked(per)
    }

    /** Kennzahl: Kontextzeile entfaellt, wenn sie nur den Titel wiederholt */
    fun sameText(a: String, b: String): Boolean = a.trim().lowercase() == b.trim().lowercase()

    /** Fortschritt ueber 100 %: Marker "+n %" (> 100,05), "Ziel erreicht" ab 99,95 */
    fun overPercent(raw: Double): Double? = if (raw > 100.05) raw - 100.0 else null

    fun goalReached(raw: Double): Boolean = raw >= 99.95 && raw <= 100.05
}
