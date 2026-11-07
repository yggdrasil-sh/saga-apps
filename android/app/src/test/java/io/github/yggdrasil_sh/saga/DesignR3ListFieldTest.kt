package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.blocks.BlockDoc
import io.github.yggdrasil_sh.saga.blocks.InlineHtml
import io.github.yggdrasil_sh.saga.blocks.JsonPath
import io.github.yggdrasil_sh.saga.data.Drafts
import io.github.yggdrasil_sh.saga.data.MemoryDrafts
import io.github.yggdrasil_sh.saga.ui.page.FieldState
import io.github.yggdrasil_sh.saga.ui.page.PageBackend
import io.github.yggdrasil_sh.saga.ui.page.PageEditor
import io.github.yggdrasil_sh.saga.util.UiText
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class ListFieldServer(var body: String) : PageBackend {
    override val currentServer = "https://s.example.org"
    override val signedOut = false
    private val drafts = MemoryDrafts()
    override fun draftsForCurrent(): Drafts = drafts
    override suspend fun <T> withPageLock(id: Long, block: suspend () -> T): T = block()
    override suspend fun loadPage(id: Long): JsonObject = buildJsonObject {
        put("id", id); put("title", "T"); put("body", body); put("format", "blocks"); put("rev", 1)
    }
    override suspend fun savePage(id: Long, title: String, body: String?, rev: Int?): Int? {
        this.body = body ?: this.body
        return 2
    }
    override suspend fun createPageWith(title: String, body: String): Long = 99
    override suspend fun setPinned(id: Long, pinned: Boolean) {}
    override suspend fun setLocked(id: Long, locked: Boolean) {}
    override fun updateTitleLocal(id: Long, title: String) {}
    override fun visited(id: Long) {}
    override fun friendly(e: Throwable): UiText = UiText.Raw(e.message ?: "?")
}

/**
 * Nachbesserung R3 (Bilder 99i-99n): Enter am Anfang eines gefuellten Listenpunkts, Ruecktaste im neuen leeren Punkt
 * -> die App zeigte einen gefuellten Punkt als leer an (Feldzustand je Index-Schluessel, Echo-Erkennung verwechselte
 * den nachgerueckten Inhalt mit einem frueheren Wert desselben Schluessels). Simuliert wird das Zusammenspiel von
 * ListItems (Schluessel = Index-Pfad) und RichField (Feldzustand je Schluessel, Abgleich beim Komponieren).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DesignR3ListFieldTest {
    private val page = """
        {"blocks":[{"id":"L","type":"list","data":{"style":"unordered","meta":{},"items":[
          {"content":"Angebot","meta":{},"items":[]},
          {"content":"Raumplan","meta":{},"items":[]}
        ]}}]}
    """.trimIndent()

    private val blockPath: JsonPath = listOf("blocks", 0)
    private fun itemPath(i: Int): JsonPath = blockPath + listOf("data", "items", i)
    private fun contentPath(i: Int): JsonPath = itemPath(i) + "content"

    private fun TestScope.editor(): PageEditor {
        val d = StandardTestDispatcher(testScheduler)
        return PageEditor(1, ListFieldServer(page), backgroundScope, io = d, cpu = d, clock = { testScheduler.currentTime })
    }

    private fun docItems(ed: PageEditor): List<String> =
        (BlockDoc.data(BlockDoc.blocks(ed.doc)[0])["items"] as JsonArray).map { BlockDoc.str(it as JsonObject, "content") }

    /** Feldzustaende wie im RichField: remember(fieldKey, fieldEpoch) */
    private class Fields(val ed: PageEditor, val useDoc: Boolean) {
        val cache = HashMap<Pair<String, Int>, FieldState>()

        /** Komponieren der Liste: je Punkt Feldzustand holen und mit Parameter (+ Dokument) abgleichen -> angezeigte Texte */
        fun render(items: List<String>): Map<String, String> = items.mapIndexed { i, html ->
            val key = "b:L/$i"
            val st = cache.getOrPut(key to ed.fieldEpoch) { FieldState(html, false, "\n") }
            val docHtml = if (useDoc) ed.stringAt(listOf("blocks", 0, "data", "items", i, "content")) else null
            if (st.needsReset(html, docHtml)) {
                st.accept(html)
                st.rich = InlineHtml.parse(html)
            }
            key to st.rich.text
        }.toMap()

        /** Eigene Eingabe im Feld i (publish im RichField) */
        fun publish(i: Int, html: String) {
            val st = cache.getValue("b:L/$i" to ed.fieldEpoch)
            st.rich = InlineHtml.parse(html)
            st.sent(html)
            ed.setString(listOf("blocks", 0, "data", "items", i, "content"), html)
        }
    }

    /** Enter am Anfang von Punkt i: Feld behaelt "", der Rest kommt als neuer Punkt dahinter (onEnter in ListItems) */
    private fun splitAtStart(ed: PageEditor, f: Fields, i: Int) {
        val right = docItems(ed)[i]
        f.publish(i, "")
        ed.insertItemAfter(itemPath(i), right, false, "b:L/${i + 1}")
        f.render(docItems(ed))
    }

    private fun expected(ed: PageEditor) = docItems(ed).mapIndexed { i, s -> "b:L/$i" to s }.toMap()

    @Test fun splitAmAnfangUndRuecktasteZeigtRichtigenInhalt() = runTest {
        val ed = editor()
        ed.load()
        val f = Fields(ed, useDoc = true)
        f.render(docItems(ed))
        splitAtStart(ed, f, 1)                       // [Angebot, "", Raumplan]
        splitAtStart(ed, f, 2)                       // [Angebot, "", "", Raumplan]
        assertEquals(listOf("Angebot", "", "", "Raumplan"), docItems(ed))
        assertTrue(ed.backspaceAtItemStart(blockPath, "L", itemPath(1)))
        assertEquals(listOf("Angebot", "", "Raumplan"), docItems(ed))
        // Schluessel -> Text stimmt: b:L/2 zeigt "Raumplan" (vorher blieb es leer)
        assertEquals(expected(ed), f.render(docItems(ed)))
        // Tippen im (jetzt richtig angezeigten) Punkt 2 aendert genau diesen Punkt
        f.publish(2, "Raumplan klären")
        assertEquals(listOf("Angebot", "", "Raumplan klären"), docItems(ed))
        assertEquals(expected(ed), f.render(docItems(ed)))
    }

    /** Ursache belegt: nur mit der alten Echo-Erkennung bleibt der nachgerueckte Punkt leer */
    @Test fun alteEchoErkennungAlleinZeigtFalschenInhalt() = runTest {
        val ed = editor()
        ed.load()
        val f = Fields(ed, useDoc = false)
        f.render(docItems(ed))
        splitAtStart(ed, f, 1)
        splitAtStart(ed, f, 2)
        ed.backspaceAtItemStart(blockPath, "L", itemPath(1))
        assertNotEquals(expected(ed), f.render(docItems(ed)))
    }

    @Test fun ruecktasteImErstenNeuenPunktVorGefuelltemPunkt() = runTest {
        val ed = editor()
        ed.load()
        val f = Fields(ed, useDoc = true)
        f.render(docItems(ed))
        splitAtStart(ed, f, 0)                       // ["", Angebot, Raumplan]
        splitAtStart(ed, f, 1)                       // ["", "", Angebot, Raumplan]
        assertTrue(ed.backspaceAtItemStart(blockPath, "L", itemPath(1)))
        assertEquals(listOf("", "Angebot", "Raumplan"), docItems(ed))
        assertEquals(expected(ed), f.render(docItems(ed)))
    }

    /** Verspaetetes eigenes Echo (alter Parameter, Dokument hat schon den neuen Stand) setzt nie zurueck */
    @Test fun verspaetetesEchoSetztNichtZurueck() = runTest {
        val ed = editor()
        ed.load()
        val f = Fields(ed, useDoc = true)
        f.render(docItems(ed))
        f.publish(0, "Angebot a")
        f.publish(0, "Angebot ab")
        val st = f.cache.getValue("b:L/0" to ed.fieldEpoch)
        assertFalse(st.needsReset("Angebot a", ed.stringAt(contentPath(0))))
        assertFalse(st.needsReset("Angebot ab", ed.stringAt(contentPath(0))))
        // Aenderung von aussen (Parameter und Dokument passen zusammen, Feld zeigt etwas anderes) -> neu setzen
        ed.setString(contentPath(0), "Von aussen")
        assertTrue(st.needsReset("Von aussen", ed.stringAt(contentPath(0))))
    }

    @Test fun neuLadenVerwirftAlleFeldzustaende() = runTest {
        val ed = editor()
        ed.load()
        val e0 = ed.fieldEpoch
        ed.load()                                    // automatisches Laden (Live) behaelt die Felder
        assertEquals(e0, ed.fieldEpoch)
        val f = Fields(ed, useDoc = true)
        f.render(docItems(ed))
        ed.load(discardFields = true)                // "Neu laden" / Version wiederhergestellt
        assertNotEquals(e0, ed.fieldEpoch)
        assertTrue(f.cache.keys.none { it.second == ed.fieldEpoch })
        assertEquals(expected(ed), f.render(docItems(ed)))
    }
}
