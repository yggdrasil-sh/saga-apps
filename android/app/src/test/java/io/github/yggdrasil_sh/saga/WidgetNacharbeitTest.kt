package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.blocks.PageWidgets
import io.github.yggdrasil_sh.saga.blocks.WidgetDesign
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Widget-Nacharbeit (9.11.): Wetter-Symbole, Countdown wie Web, Kontextzeile mit Quelle, Diagramm-Tooltip, Spaltenbreiten */
class WidgetNacharbeitTest {
    // ---------------------------------------------------------------- Wetter-Symbole (Nacharbeit 9)

    @Test
    fun wetterSymbolWieWeb() {
        // Web static/pages.js wxKind: 0 Sonne, 1-2 teils, 3/4x Wolke, 45/48 Nebel, 5x Niesel, 6x Regen, 7x Schnee, 80-82 Schauer, 85/86 Schnee, 9x Gewitter
        val want = mapOf(
            0 to "sun", 1 to "part", 2 to "part", 3 to "cloud", 44 to "cloud", 45 to "fog", 48 to "fog", 51 to "drizzle", 57 to "drizzle",
            61 to "rain", 66 to "rain", 67 to "rain", 71 to "snow", 77 to "snow", 80 to "rain", 82 to "rain", 85 to "snow", 86 to "snow",
            95 to "storm", 99 to "storm",
        )
        want.forEach { (code, kind) -> assertEquals("WMO $code", kind, WidgetDesign.wxKind(code)) }
        assertNull(WidgetDesign.wxKind(null))
        assertEquals("sun", WidgetDesign.wxKind(-1))
    }

    @Test
    fun wetterSymboleHabenPfade() {
        val kinds = (0..99).mapNotNull { WidgetDesign.wxKind(it) }.toSet()
        val path = Regex("""^[MmLlHhVvCcSsQqTtAaZz0-9.,\s\-]+$""")
        kinds.forEach { k ->
            val parts = WidgetDesign.WX_SVG[k]
            assertTrue("Symbol $k fehlt", !parts.isNullOrEmpty())
            parts!!.forEach { assertTrue("$k: ${it.path}", path.matches(it.path) && it.path.startsWith("M")) }
        }
        // Sonne und Blitz in Akzent, Regen blau, Wolke in Schriftfarbe
        assertTrue(WidgetDesign.WX_SVG.getValue("sun").all { it.layer == WidgetDesign.WxLayer.ACCENT })
        assertTrue(WidgetDesign.WX_SVG.getValue("rain").any { it.layer == WidgetDesign.WxLayer.WET })
        assertTrue(WidgetDesign.WX_SVG.getValue("storm").any { it.layer == WidgetDesign.WxLayer.ACCENT })
        assertEquals(listOf(WidgetDesign.WxLayer.FG), WidgetDesign.WX_SVG.getValue("cloud").map { it.layer })
        assertTrue(WidgetDesign.FLAME.startsWith("M12 2") && WidgetDesign.FLAME.endsWith("z"))
    }

    // ---------------------------------------------------------------- Kontextzeile mit Quelle (Nacharbeit 12)

    private fun pv(json: String) = PageWidgets.parseValue(Json.parseToJsonElement(json) as JsonObject)

    @Test
    fun quellNameVomServer() {
        assertEquals("Ausgaben", pv("""{"kind":"metric","value":3,"src_name":"Ausgaben"}""").srcName)
        assertNull(pv("""{"kind":"metric","value":3}""").srcName)                 // aelterer Server
        assertNull(pv("""{"kind":"metric","value":3,"src_name":null}""").srcName) // ohne Zugriff
        assertNull(pv("""{"kind":"metric","value":3,"src_name":"  "}""").srcName)
        assertNull(pv("""{"kind":"metric","value":3,"src_name":5}""").srcName)
        assertEquals(130, pv("""{"kind":"progress","value":1,"src_name":"${"x".repeat(200)}"}""").srcName!!.length)
    }

    @Test
    fun kontextRechenartUndQuelle() {
        assertEquals("Summe · Ausgaben", WidgetDesign.srcContext("metric", "db", "Summe", "Ausgaben"))
        assertEquals("Summe", WidgetDesign.srcContext("metric", "db", "Summe", null))            // wie bisher
        assertEquals("Summe · Ausgaben", WidgetDesign.srcContext("metric", "", "Summe", "Ausgaben"))   // ohne src = Datenbank
        assertEquals("Summe", WidgetDesign.srcContext("metric", "", "Summe", null))
        assertEquals("Ausgaben", WidgetDesign.srcContext("progress", "db", null, "Ausgaben"))
        assertEquals("", WidgetDesign.srcContext("progress", "db", null, null))
        assertEquals("Offen", WidgetDesign.srcContext("metric", "tasks", "Offen", "egal"))       // tasks unveraendert
        assertEquals("", WidgetDesign.srcContext("progress", "tasks", null, "egal"))
    }

    // ---------------------------------------------------------------- Diagramm-Tooltip (Nacharbeit 10)

    @Test
    fun tippenTrifftBand() {
        // 4 Kategorien a 50 px ab x = 40
        assertNull(WidgetDesign.bandAt(39f, 40f, 50f, 4))
        assertEquals(0, WidgetDesign.bandAt(40f, 40f, 50f, 4))
        assertEquals(1, WidgetDesign.bandAt(90f, 40f, 50f, 4))
        assertEquals(3, WidgetDesign.bandAt(239f, 40f, 50f, 4))
        assertNull(WidgetDesign.bandAt(240f, 40f, 50f, 4))
        assertNull(WidgetDesign.bandAt(10f, 0f, 50f, 0))
    }

    @Test
    fun gruppierteBalkenReihe() {
        // Band 100 px, 72 % = 72 px fuer 3 Reihen ab 14 px: je 24 px
        assertEquals(0, WidgetDesign.groupedSeriesAt(5f, 0f, 100f, 4, 3))      // Rand links -> erste Reihe
        assertEquals(0, WidgetDesign.groupedSeriesAt(20f, 0f, 100f, 4, 3))
        assertEquals(1, WidgetDesign.groupedSeriesAt(50f, 0f, 100f, 4, 3))
        assertEquals(2, WidgetDesign.groupedSeriesAt(80f, 0f, 100f, 4, 3))
        assertEquals(2, WidgetDesign.groupedSeriesAt(99f, 0f, 100f, 4, 3))
        assertEquals(0, WidgetDesign.groupedSeriesAt(80f, 0f, 100f, 4, 1))
    }

    @Test
    fun stapelReihe() {
        val col = listOf(10.0, null, 5.0, -4.0)
        assertEquals(0, WidgetDesign.stackedSeriesAt(col, 3.0))
        assertEquals(2, WidgetDesign.stackedSeriesAt(col, 12.0))
        assertEquals(2, WidgetDesign.stackedSeriesAt(col, 40.0))                // ueber dem Stapel -> oberste
        assertEquals(3, WidgetDesign.stackedSeriesAt(col, -2.0))
        assertNull(WidgetDesign.stackedSeriesAt(listOf(null, null), 1.0))
        assertEquals(15.0, WidgetDesign.stackTop(col, 2), 1e-9)
        assertEquals(-4.0, WidgetDesign.stackTop(col, 3), 1e-9)
    }

    @Test
    fun kreisSegment() {
        val v = listOf(1.0, 1.0, 0.0, 2.0)                                       // 0-90°, 90-180°, (leer), 180-360°
        assertEquals(0, WidgetDesign.pieAt(10f, -50f, 100f, 0f, v))              // oben rechts
        assertEquals(1, WidgetDesign.pieAt(30f, 50f, 100f, 0f, v))               // unten rechts
        assertEquals(3, WidgetDesign.pieAt(-50f, 10f, 100f, 0f, v))              // links
        assertNull(WidgetDesign.pieAt(200f, 0f, 100f, 0f, v))                    // ausserhalb
        assertNull(WidgetDesign.pieAt(5f, 5f, 100f, 62f, v))                     // im Loch des Rings
        assertEquals(45.0, WidgetDesign.pieMidDeg(v, 0), 1e-9)
        assertEquals(270.0, WidgetDesign.pieMidDeg(v, 3), 1e-9)
    }

    @Test
    fun tooltipTexteWieWeb() {
        assertEquals("Offen · 2025", WidgetDesign.tipName("Offen", "2025", multi = true))
        assertEquals("Offen", WidgetDesign.tipName("Offen", "2025", multi = false))
        assertEquals("Offen", WidgetDesign.tipName("Offen", "", multi = true))
        assertEquals("12 · 40 %", WidgetDesign.tipValue("12", "40 %", null, "Gesamt"))
        assertEquals("12 (Gesamt 30)", WidgetDesign.tipValue("12", null, "30", "Gesamt"))
        assertEquals("12", WidgetDesign.tipValue("12", null, null, "Gesamt"))
    }
}
