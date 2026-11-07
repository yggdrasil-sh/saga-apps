package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.blocks.BlockDoc
import io.github.yggdrasil_sh.saga.blocks.JsonTree
import io.github.yggdrasil_sh.saga.data.Drafts
import io.github.yggdrasil_sh.saga.data.MemoryDrafts
import io.github.yggdrasil_sh.saga.ui.page.PageBackend
import io.github.yggdrasil_sh.saga.ui.page.PageEditor
import io.github.yggdrasil_sh.saga.util.UiText
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FixR3Server(var body: String) : PageBackend {
    var rev = 1
    val drafts = MemoryDrafts()
    private val locks = HashMap<Long, Mutex>()
    override val currentServer = "https://s.example.org"
    override val signedOut = false
    override fun draftsForCurrent(): Drafts = drafts
    override suspend fun <T> withPageLock(id: Long, block: suspend () -> T): T = locks.getOrPut(id) { Mutex() }.withLock { block() }
    override suspend fun loadPage(id: Long): JsonObject = buildJsonObject {
        put("id", id); put("title", "T"); put("body", body); put("format", "blocks"); put("rev", rev)
    }
    override suspend fun savePage(id: Long, title: String, body: String?, rev: Int?): Int? {
        this.body = body ?: this.body
        this.rev++
        return this.rev
    }
    override suspend fun createPageWith(title: String, body: String): Long = 99
    override suspend fun setPinned(id: Long, pinned: Boolean) {}
    override suspend fun setLocked(id: Long, locked: Boolean) {}
    override fun updateTitleLocal(id: Long, title: String) {}
    override fun visited(id: Long) {}
    override fun friendly(e: Throwable): UiText = UiText.Raw(e.message ?: "?")
}

/**
 * Nachbesserung R3 Editor: Ruecktaste in einem leeren Absatz darf nie den Block darunter (Bild) loeschen - auch wenn
 * die Tastatur die Ruecktaste doppelt meldet, bevor das Feld mit dem neuen Index neu komponiert ist.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DesignR3EditFixTest {
    private val page = """
        {"blocks":[
         {"id":"desc","type":"paragraph","data":{"text":"Überblick für die Woche."}},
         {"id":"img","type":"image","data":{"file":{"url":"/files/a"},"caption":"Plan","withBorder":false,"stretched":false,"withBackground":false}},
         {"id":"h","type":"header","data":{"text":"Ziele","level":2}}
        ]}
    """.trimIndent()

    private fun TestScope.editor(body: String): PageEditor {
        val d = StandardTestDispatcher(testScheduler)
        return PageEditor(1, FixR3Server(body), backgroundScope, io = d, cpu = d, clock = { testScheduler.currentTime })
    }

    private fun ids(ed: PageEditor) = BlockDoc.blocks(ed.doc).map { BlockDoc.id(it) }
    private fun types(ed: PageEditor) = BlockDoc.blocks(ed.doc).map { BlockDoc.type(it) }

    /** Enter am Ende der Beschreibung, Enter im neuen leeren Absatz (so wie splitToParagraph in Blocks.kt) */
    private fun twoEnters(ed: PageEditor): Pair<String, String> {
        ed.insertBlockAfter(listOf("blocks", 0), BlockDoc.newBlock("paragraph", ""))
        ed.insertBlockAfter(listOf("blocks", 1), BlockDoc.newBlock("paragraph", ""))
        val b = BlockDoc.blocks(ed.doc)
        return BlockDoc.id(b[1])!! to BlockDoc.id(b[2])!!
    }

    @Test fun ruecktasteImLeerenAbsatzLaesstBildStehen() = runTest {
        val ed = editor(page)
        ed.load()
        val (p1, p2) = twoEnters(ed)
        assertEquals(listOf("paragraph", "paragraph", "paragraph", "image", "header"), types(ed))
        val stale = listOf<Any>("blocks", 2)
        // Ruecktaste im zweiten leeren Absatz - die Tastatur meldet sie zweimal mit demselben (alten) Pfad
        assertTrue(ed.backspaceAtBlockStart(stale, p2, empty = true))
        assertFalse(ed.backspaceAtBlockStart(stale, p2, empty = true))
        assertEquals(listOf("desc", p1, "img", "h"), ids(ed))
        // Fokus geht an das Ende des ersten leeren Absatzes
        assertEquals(ed.blockKey(BlockDoc.blocks(ed.doc)[1] as JsonObject, listOf("blocks", 1)), ed.focusRequest)
    }

    @Test fun ohneIdWirdNieEinNichtTextblockOderVollerBlockEntfernt() = runTest {
        val ed = editor(page)
        ed.load()
        assertFalse(ed.backspaceAtBlockStart(listOf("blocks", 1), null, empty = true))   // Bild
        assertFalse(ed.backspaceAtBlockStart(listOf("blocks", 0), null, empty = true))   // Absatz mit Text
        assertFalse(ed.backspaceAtBlockStart(listOf("blocks", 1), null, empty = false))  // Bild nie zusammenfuehren
        assertEquals(listOf("desc", "img", "h"), ids(ed))
        // falsche ID am Pfad: der Block wird ueber die ID gesucht, das Bild bleibt
        assertFalse(ed.backspaceAtBlockStart(listOf("blocks", 1), "weg", empty = true))
        assertEquals(listOf("desc", "img", "h"), ids(ed))
    }

    @Test fun doppeltesZusammenfuehrenNimmtNichtDenNaechstenAbsatz() = runTest {
        val ed = editor("""{"blocks":[{"id":"a","type":"paragraph","data":{"text":"A"}},{"id":"b","type":"paragraph","data":{"text":"B"}},{"id":"c","type":"paragraph","data":{"text":"C"}}]}""")
        ed.load()
        val stale = listOf<Any>("blocks", 1)
        assertTrue(ed.backspaceAtBlockStart(stale, "b", empty = false))
        assertFalse(ed.backspaceAtBlockStart(stale, "b", empty = false))
        assertEquals(listOf("a", "c"), ids(ed))
        assertEquals("AB", BlockDoc.str(BlockDoc.data(BlockDoc.blocks(ed.doc)[0]), "text"))
        assertEquals("C", BlockDoc.str(BlockDoc.data(BlockDoc.blocks(ed.doc)[1]), "text"))
    }

    @Test fun pfadWirdUeberIdFrischAufgeloest() = runTest {
        val ed = editor(page)
        ed.load()
        val (_, p2) = twoEnters(ed)
        // Absatz vor dem leeren eingefuegt -> der alte Index 2 zeigt auf den ersten leeren Absatz, geloescht wird p2
        ed.insertBlockAfter(listOf("blocks", 0), BlockDoc.newBlock("paragraph", "neu"))
        assertTrue(ed.backspaceAtBlockStart(listOf("blocks", 2), p2, empty = true))
        assertNull(BlockDoc.blocks(ed.doc).firstOrNull { BlockDoc.id(it) == p2 })
        assertEquals(listOf("paragraph", "paragraph", "paragraph", "image", "header"), types(ed))
        assertEquals(listOf<Any>("blocks", 2), ed.resolveBlockPath(listOf("blocks", 0), BlockDoc.id(BlockDoc.blocks(ed.doc)[2])))
    }

    @Test fun listenpunktDoppelteRuecktasteLoeschtNachbarnNicht() = runTest {
        val ed = editor("""{"blocks":[{"id":"l","type":"list","data":{"style":"unordered","meta":{},"items":[{"content":"a","meta":{},"items":[]},{"content":"","meta":{},"items":[]},{"content":"wichtig","meta":{},"items":[]}]}}]}""")
        ed.load()
        val item = listOf<Any>("blocks", 0, "data", "items", 1)
        assertTrue(ed.backspaceAtItemStart(listOf("blocks", 0), "l", item))
        assertFalse(ed.backspaceAtItemStart(listOf("blocks", 0), "l", item))
        val items = JsonTree.get(ed.doc, listOf("blocks", 0, "data", "items")) as JsonArray
        assertEquals(listOf("a", "wichtig"), items.map { BlockDoc.str(it as JsonObject, "content") })
    }
}
