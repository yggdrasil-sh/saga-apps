package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.blocks.PageWidgets
import io.github.yggdrasil_sh.saga.blocks.WidgetDesign
import io.github.yggdrasil_sh.saga.db.DbChart
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** Widget-Design-Runde (9.11.): Gestaltungslogik der Seiten-Widgets und Diagramme wie im Web */
class WidgetDesignTest {
    private fun cats(vararg p: Pair<String?, String?>) = p.toList()

    // ---------------------------------------------------------------- Palette

    @Test
    fun paletteWithoutOptionColors() {
        // Fund: "Offen" ohne Farbe (gray) war grau - eine echte Optionsfarbe reicht nicht, also Palette der Reihe nach
        val c = WidgetDesign.chartColors(cats("a" to "gray", "b" to "brown", "c" to null), one = false)
        assertEquals(listOf("blue", "orange", "green"), c)
    }

    @Test
    fun optionColorsOnlyWithTwoDistinctRealOnes() {
        val c = WidgetDesign.chartColors(cats("a" to "red", "b" to "green", "c" to "gray", "d" to null), one = false)
        assertEquals(listOf("red", "green", "gray", "gray"), c)
        // zweimal dieselbe Farbe: nur eine verschiedene -> Palette
        assertEquals(listOf("blue", "orange"), WidgetDesign.chartColors(cats("a" to "red", "b" to "red"), one = false))
    }

    @Test
    fun otherAndEmptyStayNeutral() {
        val c = WidgetDesign.chartColors(cats("a" to null, null to null, "__other__" to "red", "b" to null), one = false)
        assertEquals(listOf("blue", WidgetDesign.OTHER, WidgetDesign.OTHER, "orange"), c)
        // neutrale zaehlen nicht als echte Optionsfarben
        val d = WidgetDesign.chartColors(cats("a" to "red", "__other__" to "green"), one = false)
        assertEquals(listOf("blue", WidgetDesign.OTHER), d)
    }

    @Test
    fun singleSeriesIsOneColor() {
        assertEquals(listOf(WidgetDesign.ONE, WidgetDesign.ONE), WidgetDesign.chartColors(cats("a" to null, "b" to "gray"), one = true))
        // mit echten Optionsfarben gewinnen die Optionen auch bei einer Reihe
        assertEquals(listOf("red", "blue"), WidgetDesign.chartColors(cats("a" to "red", "b" to "blue"), one = true))
    }

    @Test
    fun paletteOrderAndWrap() {
        val c = WidgetDesign.chartColors((1..9).map { "k$it" to null }, one = false)
        assertEquals(WidgetDesign.PALETTE + "blue", c)
        assertEquals(listOf("blue", "orange", "green", "purple", "pink", "yellow", "red", "brown"), WidgetDesign.PALETTE)
    }

    // ---------------------------------------------------------------- Wert/Einheit

    @Test
    fun splitValueUnits() {
        assertEquals(WidgetDesign.ValueParts("", "2.250,00", "€"), WidgetDesign.splitValue("2.250,00 €"))
        assertEquals(WidgetDesign.ValueParts("", "68", "%"), WidgetDesign.splitValue("68 %"))
        assertEquals(WidgetDesign.ValueParts("", "42", "Tage"), WidgetDesign.splitValue("42 Tage"))
        assertEquals(WidgetDesign.ValueParts("", "12", "°C"), WidgetDesign.splitValue("12 °C"))
        assertEquals(WidgetDesign.ValueParts("€", "1,234.50", ""), WidgetDesign.splitValue("€1,234.50"))
        assertEquals(WidgetDesign.ValueParts("", "1.234", ""), WidgetDesign.splitValue("1.234"))
        assertEquals(WidgetDesign.ValueParts("", "09:32", ""), WidgetDesign.splitValue("09:32"))
        assertEquals(WidgetDesign.ValueParts("", "Heute", ""), WidgetDesign.splitValue("Heute"))
        assertEquals(WidgetDesign.ValueParts("", "–", ""), WidgetDesign.splitValue("–"))
        // Prozent aus dem App-Format (Java/Android) mit geschuetztem Leerzeichen
        val pct = PageWidgets.format(68.0, "pct", Locale.GERMANY)
        assertEquals("%", WidgetDesign.splitValue(pct).unit)
        assertEquals("€", WidgetDesign.splitValue(PageWidgets.format(2250.0, "eur", Locale.GERMANY)).unit)
    }

    @Test
    fun unitWordFromPlural() {
        assertEquals("Tage", WidgetDesign.unitWord("42 Tage", "42"))
        assertEquals("дней", WidgetDesign.unitWord("5 дней", "5"))
    }

    @Test
    fun valueFontClamp() {
        assertEquals(28f, WidgetDesign.valueSp(400f, "m"), 0.01f)
        assertEquals(17.6f, WidgetDesign.valueSp(100f, "m"), 0.01f)
        assertEquals(26f, WidgetDesign.valueSp(200f, "m"), 0.01f)
        assertEquals(19.2f, WidgetDesign.valueSp(400f, "s"), 0.01f)
        assertEquals(40f, WidgetDesign.valueSp(600f, "l"), 0.01f)
    }

    @Test
    fun boxSizes() {
        assertEquals(WidgetDesign.Box(10, 12, 72, 6), WidgetDesign.box("s"))
        assertEquals(14, WidgetDesign.box("m").vPad)
        assertEquals(104, WidgetDesign.box("x").minHeight)
        assertEquals(144, WidgetDesign.box("l").minHeight)
    }

    // ---------------------------------------------------------------- Zitat

    @Test
    fun quoteSourceDetected() {
        assertEquals("Der Weg ist das Ziel." to "Konfuzius", WidgetDesign.quoteSplit("Der Weg ist das Ziel. – Konfuzius"))
        assertEquals("Der Weg ist das Ziel." to "Konfuzius", WidgetDesign.quoteSplit("Der Weg ist das Ziel. — Konfuzius"))
        assertEquals("Wer kämpft, kann verlieren" to "Bertolt Brecht", WidgetDesign.quoteSplit("Wer kämpft, kann verlieren\n– Bertolt Brecht"))
        assertEquals("„Carpe diem“" to "Horaz", WidgetDesign.quoteSplit("„Carpe diem“ -- Horaz"))
    }

    @Test
    fun quoteDashInsideSentenceIsNoSource() {
        // Gedankenstrich mitten im Satz (nicht nach Satzende/Zeilenumbruch)
        val t = "Erst denken – dann handeln"
        assertEquals(t to null, WidgetDesign.quoteSplit(t))
        // Quelle klein geschrieben, zu lang oder mit Satzzeichen am Ende: keine Quelle
        assertNull(WidgetDesign.quoteSplit("Das ist gut. – und das auch").second)
        assertNull(WidgetDesign.quoteSplit("Das ist gut. – Eins zwei drei vier fünf sechs sieben").second)
        assertNull(WidgetDesign.quoteSplit("Das ist gut. – Ende.").second)
        assertNull(WidgetDesign.quoteSplit("Kurz. – Name").second)   // Text vor der Quelle unter 8 Zeichen
    }

    @Test
    fun quoteLinesBySize() {
        assertEquals(4, WidgetDesign.quoteLines("s"))
        assertEquals(6, WidgetDesign.quoteLines("m"))
        assertEquals(8, WidgetDesign.quoteLines("l"))
    }

    // ---------------------------------------------------------------- Gewohnheiten

    @Test
    fun habitSevenDaysCircles() {
        assertEquals(WidgetDesign.HabitLayout(7, false, null, false), WidgetDesign.habitLayout(7, 300f, "m"))
        assertEquals(WidgetDesign.HabitLayout(7, false, null, true), WidgetDesign.habitLayout(7, 500f, "m"))
    }

    @Test
    fun habitHeatmapRange() {
        // breit: alle 30 Tage als Heatmap
        assertEquals(WidgetDesign.HabitLayout(30, true, null, true), WidgetDesign.habitLayout(30, 600f, "m"))
        // schmal: letzte 14 + Hinweis
        assertEquals(WidgetDesign.HabitLayout(14, true, 14, false), WidgetDesign.habitLayout(30, 300f, "m"))
        // 14 Tage schmal: alle 14, kein Hinweis
        assertEquals(WidgetDesign.HabitLayout(14, true, null, false), WidgetDesign.habitLayout(14, 300f, "m"))
        // sehr schmal: letzte 7 als Kreise + Hinweis
        assertEquals(WidgetDesign.HabitLayout(7, false, 7, false), WidgetDesign.habitLayout(30, 200f, "m"))
        // Groesse S: letzte 7 (Heatmap) + Hinweis
        assertEquals(WidgetDesign.HabitLayout(7, true, 7, false), WidgetDesign.habitLayout(14, 300f, "s"))
    }

    @Test
    fun heatLabels() {
        assertEquals("10", WidgetDesign.heatLabel(10, false))
        assertEquals("", WidgetDesign.heatLabel(11, false))
        assertEquals("11", WidgetDesign.heatLabel(11, true))
    }

    // ---------------------------------------------------------------- Wetter, Spalten

    @Test
    fun weatherListBelow330() {
        assertTrue(WidgetDesign.weatherAsList(280f))
        assertFalse(WidgetDesign.weatherAsList(360f))
        assertFalse(WidgetDesign.weatherRain(170f))
        assertTrue(WidgetDesign.weatherRain(250f))
    }

    @Test
    fun columnsWrapByWidth() {
        assertEquals(listOf(listOf(0), listOf(1), listOf(2), listOf(3)), WidgetDesign.columnRows(4, 400f))
        assertEquals(listOf(listOf(0, 1), listOf(2, 3)), WidgetDesign.columnRows(4, 700f))
        assertEquals(listOf(listOf(0, 1, 2, 3)), WidgetDesign.columnRows(4, 1000f))
        assertEquals(listOf(listOf(0, 1), listOf(2)), WidgetDesign.columnRows(3, 600f))
        assertEquals(listOf(listOf(0, 1, 2)), WidgetDesign.columnRows(3, 700f))
        assertEquals(listOf(listOf(0, 1)), WidgetDesign.columnRows(2, 500f))
        assertEquals(listOf(listOf(0, 1, 2), listOf(3, 4, 5)), WidgetDesign.columnRows(6, 1000f))
        assertEquals(listOf(listOf(0, 1, 2), listOf(3, 4)), WidgetDesign.columnRows(5, 900f))
        assertEquals(emptyList<List<Int>>(), WidgetDesign.columnRows(0, 900f))
    }

    // ---------------------------------------------------------------- Diagramm

    @Test
    fun chartHeightBySize() {
        assertEquals(140f, WidgetDesign.chartHeight("s", 400f), 0.01f)
        assertEquals(220f, WidgetDesign.chartHeight("m", 400f), 0.01f)
        assertEquals(300f, WidgetDesign.chartHeight("l", 400f), 0.01f)
        assertEquals(160f, WidgetDesign.chartHeight("m", 200f), 0.01f)   // schmal: 0,8 x Breite
        assertEquals(120f, WidgetDesign.chartHeight("l", 100f), 0.01f)
    }

    @Test
    fun ringNotScreenFilling() {
        // Handy (360 dp, M): Legende daneben, Durchmesser 45 % der Breite
        val r = WidgetDesign.ring(360f, WidgetDesign.chartHeight("m", 360f))
        assertTrue(r.beside)
        assertEquals(162f, r.diameter, 0.01f)
        // schmale Spalte: Legende darunter, hoechstens 220 dp
        val n = WidgetDesign.ring(280f, WidgetDesign.chartHeight("l", 280f))
        assertFalse(n.beside)
        assertEquals(220f, n.diameter, 0.01f)
        assertEquals(6, n.legendMax)
        assertEquals(4, WidgetDesign.ring(170f, 136f).legendMax)
    }

    @Test
    fun legendCut() {
        assertEquals(3 to 0, WidgetDesign.legendCut(3, 6))
        assertEquals(6 to 0, WidgetDesign.legendCut(6, 6))
        assertEquals(5 to 3, WidgetDesign.legendCut(8, 6))
    }

    @Test
    fun ticksAndRingText() {
        assertEquals(3, WidgetDesign.tickCount(240f, 200f))
        assertEquals(4, WidgetDesign.tickCount(300f, 150f))
        assertEquals(5, WidgetDesign.tickCount(300f, 220f))
        assertEquals(26f, WidgetDesign.ringValueSize(70f, "31"), 0.01f)
        assertEquals(12f, WidgetDesign.ringValueSize(10f, "31"), 0.01f)
    }

    @Test
    fun compactAxis() {
        val de = Locale.GERMANY
        assertEquals("7,5", WidgetDesign.axisText(7.5, "num", de))
        assertEquals("2.500", WidgetDesign.axisText(2500.0, "num", de))
        assertEquals("25 Tsd.", WidgetDesign.axisText(25_000.0, "num", de))
        assertEquals("1,5 Mio.", WidgetDesign.axisText(1_500_000.0, "num", de))
        assertEquals("1.5M", WidgetDesign.axisText(1_500_000.0, "num", Locale.US))
        assertTrue(WidgetDesign.axisText(50.0, "pct", de).startsWith("50"))
        assertTrue(WidgetDesign.axisText(20_000.0, "eur", de).endsWith("€"))
        assertTrue(WidgetDesign.axisText(250.0, "eur", de).contains("250"))
    }

    @Test
    fun chartRowsOptional() {
        val c = DbChart.parseResult(Json.parseToJsonElement("""{"type":"donut","cats":[{"key":"a","label":"A"}],"series":[{"key":"v","values":[3]}],"rows":8}""") as JsonObject)
        assertEquals(8, c.rows)
        val d = DbChart.parseResult(Json.parseToJsonElement("""{"type":"bar","cats":[],"series":[]}""") as JsonObject)
        assertNull(d.rows)
    }

    @Test
    fun stackedRangeMixedSigns() {
        // Stapel zeichnet positive und negative Teile getrennt -> Bereich muss beide Summen umfassen (wie Web)
        val c = DbChart.parseResult(Json.parseToJsonElement("""{"type":"bar","stack":true,"cats":[{"key":"a","label":"A"},{"key":"b","label":"B"}],
            "series":[{"key":"x","values":[5,1]},{"key":"y","values":[-3,null]}]}""") as JsonObject)
        assertEquals(-3.0 to 5.0, c.yRange())
    }

    @Test
    fun overGoalMarker() {
        assertEquals(50.0, WidgetDesign.overPercent(150.0)!!, 1e-9)
        assertNull(WidgetDesign.overPercent(100.0))
        assertTrue(WidgetDesign.goalReached(100.0))
        assertFalse(WidgetDesign.goalReached(99.0))
        assertTrue(WidgetDesign.sameText(" Summe ", "summe"))
    }

    @Test
    fun upcomingDaysParsed() {
        val u = PageWidgets.upcoming(Json.parseToJsonElement("""{"items":[],"days":14}""") as JsonObject)!!
        assertEquals(14, u.days)
        assertEquals(7, PageWidgets.upcoming(Json.parseToJsonElement("""{"items":[]}""") as JsonObject)!!.days)
    }
}
