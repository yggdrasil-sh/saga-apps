package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.blocks.BlockDoc
import io.github.yggdrasil_sh.saga.blocks.MoveLogic
import io.github.yggdrasil_sh.saga.data.ApiException
import io.github.yggdrasil_sh.saga.data.Drafts
import io.github.yggdrasil_sh.saga.data.MemoryDrafts
import io.github.yggdrasil_sh.saga.data.PageMeta
import io.github.yggdrasil_sh.saga.ui.page.PageBackend
import io.github.yggdrasil_sh.saga.ui.page.PageEditor
import io.github.yggdrasil_sh.saga.util.UiText
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class E2MoveTest {
    private class Srv : PageBackend {
        var rev = 3
        var body = """{"blocks":[{"id":"a","type":"paragraph","data":{"text":"A"}},{"id":"b","type":"paragraph","data":{"text":"B"}},{"id":"c","type":"paragraph","data":{"text":"C"}}]}"""
        val moves = ArrayList<MoveLogic.Request>()
        var failFirst409 = false
        val saves = ArrayList<Int?>()
        override val currentServer = "https://s.example.org"
        override val signedOut = false
        private val d = MemoryDrafts()
        override fun draftsForCurrent(): Drafts = d
        override suspend fun <T> withPageLock(id: Long, block: suspend () -> T): T = block()
        override suspend fun loadPage(id: Long): JsonObject = buildJsonObject { put("id", id); put("title", "T"); put("body", body); put("format", "blocks"); put("rev", rev) }
        override suspend fun savePage(id: Long, title: String, body: String?, rev: Int?): Int? {
            saves.add(rev)
            if (rev != this.rev) throw ApiException(409, UiText.Raw("c"))
            this.body = body!!
            return ++this.rev
        }
        override suspend fun moveBlocks(req: MoveLogic.Request): String {
            moves.add(req)
            if (failFirst409) { failFirst409 = false; rev++; throw ApiException(409, UiText.Raw("conflict"), """{"error":"conflict","rev_a":$rev,"rev_b":9}""") }
            if (req.revA != rev) throw ApiException(409, UiText.Raw("conflict"))
            val o = Json.parseToJsonElement(body).jsonObject
            body = JsonObject(mapOf("blocks" to kotlinx.serialization.json.JsonArray(o["blocks"]!!.jsonArray.filter { BlockDoc.id(it) !in req.ids }))).toString()
            rev++
            return """{"ok":true,"rev_a":$rev,"rev_b":10,"ids":["b"],"renamed":{},"files_copied":0,"unsigned":[],"comments":0}"""
        }
        override suspend fun createPageWith(title: String, body: String): Long = 1
        override suspend fun setPinned(id: Long, pinned: Boolean) {}
        override suspend fun setLocked(id: Long, locked: Boolean) {}
        override fun updateTitleLocal(id: Long, title: String) {}
        override fun visited(id: Long) {}
        override fun friendly(e: Throwable): UiText = UiText.Raw("?")
    }

    private fun TestScope.editor(srv: PageBackend): PageEditor {
        val d = StandardTestDispatcher(testScheduler)
        return PageEditor(1, srv, backgroundScope, io = d, cpu = d, clock = { testScheduler.currentTime })
    }

    @Test fun moveSavesFirstRetriesOn409AndUndoIsReverse() = runTest {
        val srv = Srv()
        val ed = editor(srv)
        ed.load()
        ed.setString(listOf("blocks", 0, "data", "text"), "A!")      // ungespeichert -> vorher speichern
        srv.failFirst409 = true
        val job = backgroundScope.launchMove(ed)
        advanceTimeBy(30_000); runCurrent()
        val (req, res) = job.await()
        assertEquals(listOf(3), srv.saves)                             // erst gespeichert
        assertEquals(2, srv.moves.size)                                // 409 -> neu laden und einmal erneut
        assertEquals(listOf("a", "c"), BlockDoc.blocks(ed.doc).map { BlockDoc.id(it) })   // Seite neu geladen
        assertEquals("a", ed.lastMovePrev)
        val body = req.body()
        assertEquals(JsonPrimitive(456), body["to"])
        assertEquals(JsonPrimitive("end"), body["at"])
        // Rueckgaengig: Gegenrichtung, hinter den alten Vorgaenger
        val back = MoveLogic.undo(req, res, "a")
        assertEquals(456L, back.from)
        assertEquals(1L, back.to)
        assertEquals(10, back.revA)
        assertEquals(JsonPrimitive("a"), back.body()["after"])
        assertNull(back.body()["at"])
        val top = MoveLogic.undo(req, res, null)
        assertEquals(JsonPrimitive("start"), top.body()["at"])
    }

    private fun kotlinx.coroutines.CoroutineScope.launchMove(ed: PageEditor) = this.async { ed.moveBlocksTo(listOf("b"), 456) }

    @Test fun errorsTargetsAndAvailability() {
        assertEquals(MoveLogic.Error.FORBIDDEN, MoveLogic.error(403, ""))
        assertEquals(MoveLogic.Error.LOCKED, MoveLogic.error(423, ""))
        assertEquals(MoveLogic.Error.QUOTA, MoveLogic.error(507, """{"quota":true}"""))
        assertEquals(MoveLogic.Error.FEDERATED, MoveLogic.error(400, """{"federated":true}"""))
        assertEquals(MoveLogic.Error.OTHER, MoveLogic.error(400, """{"error":"x"}"""))
        val pages = listOf(PageMeta(1), PageMeta(2), PageMeta(3, kind = "database"), PageMeta(-4), PageMeta(5, role = "view"), PageMeta(6, role = "edit"), PageMeta(2))
        assertEquals(listOf(2L, 6L), MoveLogic.targets(pages, 1).map { it.id })
        assertTrue(MoveLogic.available(5, setOf("move1")))
        assertFalse(MoveLogic.available(-5, setOf("move1")))           // fremder Server: Menuepunkt ausblenden
        assertFalse(MoveLogic.available(5, emptySet()))
        val r = MoveLogic.parse("""{"ok":true,"rev_a":4,"rev_b":31,"ids":["x2"],"renamed":{"x":"x2"},"files_copied":2,"unsigned":["b1"]}""")
        assertEquals(31, r.revB)
        assertEquals(1, r.unsigned)
        assertEquals("x2", r.renamed["x"])
    }
}
