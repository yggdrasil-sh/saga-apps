package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.blocks.SlashLogic
import io.github.yggdrasil_sh.saga.ui.page.EditorChromeLogic
import io.github.yggdrasil_sh.saga.ui.page.EditorChromeLogic.SlashGroup
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Android-Design-Runde 3 (Editor): Werkzeugleiste, "/"-Menue, Seitenkopf, Listen */
class DesignR3EditTest {
    private val all = SlashLogic.Need.entries.toSet()
    private val label: (SlashLogic.Item) -> String = {
        when (it.id) {
            "header" -> "Überschrift ${it.level}"
            "table" -> "Tabelle"
            "db_new" -> "Neue Datenbank"
            "paragraph" -> "Text"
            else -> it.id
        }
    }

    @Test fun slashGruppenBleibenZusammen() {
        val list = EditorChromeLogic.arrange(SlashLogic.filter("", all, label), "", label)
        assertEquals(SlashLogic.ITEMS.size, list.size)
        assertEquals("paragraph", list.first().id)
        // jede Gruppe ist ein zusammenhaengender Abschnitt
        val groups = list.map { EditorChromeLogic.group(it.id) }
        val runs = groups.zipWithNext().count { (a, b) -> a != b } + 1
        assertEquals(groups.distinct().size, runs)
        assertEquals(listOf(SlashGroup.BASIC, SlashGroup.MEDIA, SlashGroup.SAGA, SlashGroup.DATABASE), groups.distinct())
        // Tabelle und Spalten gehoeren zu den Grundbloecken (wie im Web), nicht zwischen die Medien
        assertEquals(SlashGroup.BASIC, EditorChromeLogic.group("table"))
        assertEquals(SlashGroup.BASIC, EditorChromeLogic.group("columns"))
        assertEquals(SlashGroup.MEDIA, EditorChromeLogic.group("nextcloud"))
    }

    @Test fun slashTrefferAmNamensanfangZuerst() {
        // "tab" steht im Namen "Tabelle" am Anfang und bei "Neue Datenbank" nur in den Suchwoertern
        val q = "tab"
        val list = EditorChromeLogic.arrange(SlashLogic.filter(q, all, label), q, label)
        assertEquals("table", list.first().id)
        assertTrue(list.any { it.id == "db_new" })
        // "ber" steht in "Überschrift" (gefaltet "uberschrift") mitten im Namen
        val h = EditorChromeLogic.arrange(SlashLogic.filter("uber", all, label), "uber", label)
        assertEquals("header", h.first().id)
        assertEquals(1, h.first().level)
    }

    @Test fun slashSymboleSindLiniensymbole() {
        val src = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
            .resolve("io/github/yggdrasil_sh/saga/ui/theme/SagaIcons.kt").readText()
        SlashLogic.ITEMS.forEach { item ->
            val n = EditorChromeLogic.iconName(item)
            assertTrue("${item.key}: $n", Regex("val $n\\b").containsMatchIn(src))
        }
    }

    @Test fun slashMenueOeffnetUeberDerZeileWennUntenKeinPlatz() {
        // Inhaltsbereich 100..1000 px, mindestens 240 px darunter
        val low = EditorChromeLogic.slashPlacement(anchorTop = 800, anchorBottom = 850, viewTop = 100, viewBottom = 1000, minBelow = 240, cap = 700, gap = 8)
        assertTrue(low.above)
        assertEquals(692, low.maxHeight)
        val high = EditorChromeLogic.slashPlacement(200, 250, 100, 1000, 240, 700, 8)
        assertFalse(high.above)
        assertEquals(700, high.maxHeight)                  // gedeckelt
        // oben noch weniger Platz als unten: unten bleiben
        val tiny = EditorChromeLogic.slashPlacement(150, 800, 100, 1000, 240, 700, 8)
        assertFalse(tiny.above)
        assertEquals(192, tiny.maxHeight)
    }

    @Test fun slashMenueNieUnterDerWerkzeugleiste() {
        val p = EditorChromeLogic.SlashPlacement(above = false, maxHeight = 300)
        // Menue waere 400 px hoch und liefe ueber die Unterkante (Werkzeugleiste) - es wird nach oben geschoben
        val y = EditorChromeLogic.slashY(p, anchorTop = 700, anchorBottom = 750, popupHeight = 400, viewTop = 100, viewBottom = 1000, gap = 8)
        assertEquals(600, y)
        assertTrue(y + 400 <= 1000)
        val up = EditorChromeLogic.SlashPlacement(above = true, maxHeight = 600)
        assertEquals(700 - 8 - 300, EditorChromeLogic.slashY(up, 700, 750, 300, 100, 1000, 8))
        // nie ueber die Oberkante
        assertEquals(100, EditorChromeLogic.slashY(up, 150, 200, 300, 100, 1000, 8))
        assertEquals(80, EditorChromeLogic.slashX(200, 300, 380))
        assertEquals(10, EditorChromeLogic.slashX(10, 300, 380))
    }

    @Test fun leererSeiteninhalt() {
        fun blocks(json: String) = (Json.parseToJsonElement(json) as JsonArray).toList()
        assertTrue(EditorChromeLogic.isEmptyBody(emptyList()))
        assertTrue(EditorChromeLogic.isEmptyBody(blocks("""[{"id":"a","type":"paragraph","data":{"text":""}}]""")))
        assertTrue(EditorChromeLogic.isEmptyBody(blocks("""[{"id":"a","type":"paragraph","data":{"text":"<br>"}}]""")))
        assertFalse(EditorChromeLogic.isEmptyBody(blocks("""[{"id":"a","type":"paragraph","data":{"text":"Hallo"}}]""")))
        assertFalse(EditorChromeLogic.isEmptyBody(blocks("""[{"id":"a","type":"header","data":{"text":"","level":2}}]""")))
        assertFalse(EditorChromeLogic.isEmptyBody(blocks("""[{"id":"a","type":"paragraph","data":{"text":""}},{"id":"b","type":"paragraph","data":{"text":""}}]""")))
        assertTrue(EditorChromeLogic.isFirstTopLevel(listOf("blocks", 0)))
        assertFalse(EditorChromeLogic.isFirstTopLevel(listOf("blocks", 1)))
        assertFalse(EditorChromeLogic.isFirstTopLevel(listOf("blocks", 0, "data", "blocks", 0)))
        // unbenutzt, aber gueltiges JSON-Objekt
        assertTrue(blocks("""[{"id":"a"}]""").first().jsonObject.containsKey("id"))
    }

    @Test fun kopfUndFarbmenue() {
        assertFalse(EditorChromeLogic.headerAddsVisible(titleFocused = false, headTapped = false, hint = false))
        assertTrue(EditorChromeLogic.headerAddsVisible(titleFocused = true, headTapped = false, hint = false))
        assertTrue(EditorChromeLogic.headerAddsVisible(titleFocused = false, headTapped = true, hint = false))
        assertTrue(EditorChromeLogic.headerAddsVisible(titleFocused = false, headTapped = false, hint = true))
        val none = EditorChromeLogic.colorSections(hasSelection = false, inlineColor = true, blockStylable = true)
        assertFalse(none.text)
        assertTrue(none.block)
        assertTrue(EditorChromeLogic.colorSections(true, true, false).text)
        assertFalse(EditorChromeLogic.colorSections(true, false, false).any)
    }

    @Test fun werkzeugleisteOhneTextknoepfeUndOhneAltesMenue() {
        val src = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }.resolve("io/github/yggdrasil_sh/saga")
        val page = src.resolve("ui/page/PageScreen.kt").readText()
        val start = page.indexOf("private fun EditorToolbar(")
        val bar = page.substring(start, page.indexOf("\n}\n", start))
        // Formate als Liniensymbole, keine Buchstaben F/K/U/S mehr
        listOf("bold", "italic", "underline", "strikethrough").forEach { assertTrue(it, bar.contains("SagaIcons.$it")) }
        assertFalse(bar.contains("format_bold_short"))
        // "+" ohne eigenes DropdownMenu (dieselbe Liste wie "/"), "Fertig" tonal, Block-Menue mit Loeschen in Fehlerfarbe
        assertFalse(bar.contains("addMenu"))
        assertTrue(bar.contains("SagaFilledTonalButton"))
        assertTrue(bar.contains("SagaIcons.moreVert"))
        assertTrue(bar.contains("color = MaterialTheme.colorScheme.error"))
        // Hoch/Runter bleiben ueber die Bedienhilfe erreichbar
        assertTrue(bar.contains("CustomAccessibilityAction"))
        // Speicherstatus als Wolke, nicht als Haken
        val save = page.substring(page.indexOf("private fun SaveState("), page.indexOf("\n}\n", page.indexOf("private fun SaveState(")))
        assertFalse(save.contains("SagaIcons.check"))
        assertTrue(save.contains("SagaIcons.cloud"))
    }

    @Test fun neueTexteInAllenSprachen() {
        val main = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
        val keys = Regex("name=\"([a-z0-9_]+)\"").findAll(File(main, "res/values/strings_r3edit.xml").readText()).map { it.groupValues[1] }.toList()
        assertTrue(keys.size >= 30)
        listOf("values-de", "values-es", "values-ru", "values-th").forEach { d ->
            val s = File(main, "res/$d/strings_r3edit.xml").readText()
            keys.forEach { k -> assertTrue("$d: $k", s.contains("name=\"$k\"")) }
        }
    }
}
