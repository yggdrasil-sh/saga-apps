package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.blocks.BlockDoc
import io.github.yggdrasil_sh.saga.data.ApiException
import io.github.yggdrasil_sh.saga.data.Drafts
import io.github.yggdrasil_sh.saga.data.MemoryDrafts
import io.github.yggdrasil_sh.saga.ui.page.PageBackend
import io.github.yggdrasil_sh.saga.ui.page.PageEditor
import io.github.yggdrasil_sh.saga.ui.page.PageLiveLogic
import io.github.yggdrasil_sh.saga.util.UiText
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Server-Attrappe fuer Kopf-Felder (Bugs-DB 2121): Symbol, Titelbild, Sperre, Eigenschaften aendern die Revision nicht
 * (wie PATCH ohne body/title, note.changed meta: true); Titel/Inhalt speichern auf gesperrter Seite -> 423.
 */
private class MetaServer : PageBackend {
    var title = "Reiseplan"
    var body = """{"blocks":[{"id":"a","type":"paragraph","data":{"text":"Eins"}}]}"""
    var rev = 3
    var icon = "🗺️"
    var cover = ""
    var coverPos = 50.0
    var locked = false
    var props = """{"p1":"a"}"""
    var updated = 100.0
    var saves = 0
    val since = ArrayList<Int?>()
    val drafts = MemoryDrafts()

    private fun page(withBody: Boolean): JsonObject = Json.parseToJsonElement(
        """{"id":1,"title":${JsonPrimitive(title)},
           ${if (withBody) "\"body\":" + JsonPrimitive(body) + "," else "\"delta\":{\"from\":$rev,\"blocks\":[{\"id\":\"a\"}]},"}
           "format":"blocks","rev":$rev,"icon":"$icon","cover":"$cover","cover_pos":$coverPos,"locked":$locked,
           "props":$props,"parent_id":null,"updated":$updated,"role":"owner"}""",
    ).jsonObject

    override val currentServer = "https://s.example.org"
    override val signedOut = false
    override fun draftsForCurrent(): Drafts = drafts
    override suspend fun <T> withPageLock(id: Long, block: suspend () -> T): T = block()
    override suspend fun loadPage(id: Long): JsonObject = page(true)
    override suspend fun loadPageSince(id: Long, since: Int?): JsonObject {
        this.since.add(since)
        return if (since == rev) page(false) else page(true)
    }
    override suspend fun savePage(id: Long, title: String, body: String?, rev: Int?): Int? {
        if (locked) throw ApiException(423, UiText.Raw("Seite ist gesperrt – erst entsperren"))
        if (rev != null && rev != this.rev) throw ApiException(409, UiText.Raw("conflict"))
        saves++
        this.title = title
        this.body = body ?: this.body
        this.rev++
        return this.rev
    }
    override suspend fun createPageWith(title: String, body: String): Long = 9
    override suspend fun setPinned(id: Long, pinned: Boolean) {}
    override suspend fun setLocked(id: Long, locked: Boolean) { this.locked = locked }
    override fun updateTitleLocal(id: Long, title: String) {}
    override fun visited(id: Long) {}
    override fun friendly(e: Throwable): UiText = UiText.Raw(e.message ?: "?")
}

@OptIn(ExperimentalCoroutinesApi::class)
class PageMetaTest {
    private fun TestScope.settle() {
        advanceTimeBy(30_000)
        runCurrent()
    }

    private fun TestScope.editor(srv: PageBackend): PageEditor {
        val d = StandardTestDispatcher(testScheduler)
        return PageEditor(1, srv, backgroundScope, io = d, cpu = d, clock = { testScheduler.currentTime })
    }

    private fun text(ed: PageEditor) = BlockDoc.str(BlockDoc.data(BlockDoc.blocks(ed.doc)[0] as JsonObject), "text")
    private val pa = listOf<Any>("blocks", 0, "data", "text")

    @Test fun actions() {
        val L = PageLiveLogic
        assertEquals(PageLiveLogic.Action.REFRESH_HEADER, L.onChanged(5, 5, dirty = false, gone = false, meta = true))
        assertEquals(PageLiveLogic.Action.REFRESH_HEADER, L.onChanged(5, 5, dirty = true, gone = false, meta = true))
        assertEquals(PageLiveLogic.Action.REFRESH_HEADER, L.onChanged(null, 5, dirty = false, gone = false, meta = true))
        assertEquals(PageLiveLogic.Action.RELOAD_SILENT, L.onChanged(6, 5, dirty = false, gone = false, meta = true))  // neuere rev geht vor
        assertEquals(PageLiveLogic.Action.SHOW_BANNER, L.onChanged(6, 5, dirty = true, gone = false, meta = true))
        assertEquals(PageLiveLogic.Action.GONE, L.onChanged(null, 5, dirty = false, gone = true, meta = true))
        assertEquals(PageLiveLogic.Action.NONE, L.onChanged(5, null, dirty = false, gone = false, meta = true))     // nie geladen
        // Gegenprobe: ohne meta wie bisher nichts (genau das war der Befund)
        assertEquals(PageLiveLogic.Action.NONE, L.onChanged(5, 5, dirty = false, gone = false))
        assertTrue(L.headerNewer(130.0, 100.0))
        assertFalse(L.headerNewer(100.0002, 100.0))
        assertFalse(L.headerNewer(null, 100.0))
        assertTrue(L.headerNewer(5.0, null))
    }

    @Test fun titleAndUnlockRules() {
        val L = PageLiveLogic
        assertEquals("Alt" to "Neu", L.title("Alt", "Neu", focused = true))
        assertEquals("Neu" to null, L.title("Alt", "Neu", focused = false))
        assertEquals("X" to null, L.title("X", "X", focused = true))
        assertEquals("Neu", L.titleOnBlur("Alt", "Alt", "Neu"))
        assertNull(L.titleOnBlur("Alt!", "Alt", "Neu"))                  // inzwischen getippt: eigene Eingabe bleibt
        assertNull(L.titleOnBlur("Alt", "Alt", null))
        assertTrue(L.saveAfterUnlock(wasLocked = true, locked = false, dirty = true))
        assertFalse(L.saveAfterUnlock(wasLocked = true, locked = false, dirty = false))
        assertFalse(L.saveAfterUnlock(wasLocked = false, locked = true, dirty = true))
        val tree = Json.parseToJsonElement("""{"ids":[3,41],"op":"updated"}""").jsonObject
        assertTrue(L.treeTouches(tree, setOf(41L, 42L)))
        assertFalse(L.treeTouches(tree, setOf(7L)))
        assertTrue(L.treeTouches(Json.parseToJsonElement("""{"full":true}""").jsonObject, setOf(7L)))
        assertFalse(L.treeTouches(Json.parseToJsonElement("""{"full":true}""").jsonObject, emptySet()))
        assertFalse(L.treeTouches(Json.parseToJsonElement("""{"op":"updated"}""").jsonObject, setOf(7L)))
    }

    @Test fun headerFollowsWithoutTouchingInput() = runTest {
        val srv = MetaServer()
        val ed = editor(srv)
        ed.load()
        ed.setString(pa, "Eins (App)")                                   // eigene Eingabe offen
        assertTrue(ed.dirty)
        srv.icon = "🧭"; srv.cover = "/covers/balke-nordkap.jpg"; srv.coverPos = 55.0; srv.props = """{"p1":"b"}"""; srv.updated = 130.0
        assertTrue(ed.refreshHeader())
        assertEquals("🧭", ed.icon)
        assertEquals("/covers/balke-nordkap.jpg", ed.cover)
        assertEquals(55.0, ed.coverPos, 0.0)
        assertEquals("b", BlockDoc.str(ed.props!!, "p1"))
        assertEquals(130.0, ed.updatedAt!!, 0.0)
        assertEquals("Eins (App)", text(ed))                             // Eingabe bleibt
        assertTrue(ed.dirty)
        assertEquals(listOf<Int?>(3), srv.since)                          // ?since= -> ohne Inhalt
        // eigenes Titelbild laedt gerade hoch: das bleibt stehen, Rest kommt
        ed.coverBusy = true
        srv.cover = "/covers/anders.jpg"; srv.icon = "🌊"
        ed.refreshHeader()
        assertEquals("/covers/balke-nordkap.jpg", ed.cover)
        assertEquals("🌊", ed.icon)
    }

    @Test fun lockArrivesLiveAndUnlockSavesPendingInput() = runTest {
        val srv = MetaServer()
        val ed = editor(srv)
        ed.load()
        srv.locked = true                                                // anderswo gesperrt (meta)
        ed.refreshHeader()
        assertTrue(ed.locked)
        assertFalse(ed.editable)
        assertFalse(ed.titleEditable)
        srv.locked = false                                               // anderswo entsperrt
        ed.refreshHeader()
        assertFalse(ed.locked)
        assertTrue(ed.editable)
    }

    @Test fun save423ShowsLockKeepsInputAndUnlockSaves() = runTest {
        val srv = MetaServer()
        val ed = editor(srv)
        ed.load()
        ed.setString(pa, "Eins (App)")
        srv.locked = true                                                // Sperre kam nicht live an (z. B. offline)
        settle()
        assertTrue(ed.locked)                                            // 423 -> Schloss sofort
        assertNotNull(ed.error)
        assertTrue(ed.dirty)                                             // Eingabe nicht verloren
        assertEquals("Eins (App)", text(ed))
        assertEquals(0, srv.saves)
        ed.setLocked(false)                                              // Eigentuemer entsperrt hier
        settle()
        assertEquals(1, srv.saves)
        assertFalse(ed.dirty)
        assertTrue(srv.body.contains("Eins (App)"))
    }

    @Test fun unlockElsewhereSavesPendingInput() = runTest {
        val srv = MetaServer()
        val ed = editor(srv)
        ed.load()
        ed.setString(pa, "Eins (App)")
        srv.locked = true
        settle()
        assertTrue(ed.locked && ed.dirty)
        srv.locked = false                                               // anderswo entsperrt -> meta
        ed.refreshHeader()
        settle()
        assertEquals(1, srv.saves)
        assertFalse(ed.dirty)
    }

    @Test fun newerRevisionIsContentNotHeader() = runTest {
        val srv = MetaServer()
        val ed = editor(srv)
        ed.load()
        srv.rev = 4; srv.title = "Neu"
        assertFalse(ed.refreshHeader())                                  // -> wie Inhaltsaenderung behandeln
        assertEquals("Reiseplan", ed.title)
    }

    @Test fun focusedTitleIsNotOverwritten() = runTest {
        val srv = MetaServer()
        val ed = editor(srv)
        ed.load()
        ed.titleFocusChanged(true)
        srv.title = "Reiseplan (Web)"; srv.rev = 4
        ed.load()                                                        // fremde Titelaenderung (rev+1), still geladen
        assertEquals("Reiseplan", ed.title)                              // Feld bleibt, solange fokussiert
        ed.titleFocusChanged(false)
        assertEquals("Reiseplan (Web)", ed.title)                        // danach nachgezogen
        // nicht fokussiert: sofort
        srv.title = "Reiseplan 2"; srv.rev = 5
        ed.load()
        assertEquals("Reiseplan 2", ed.title)
        // fokussiert und inzwischen getippt: eigene Eingabe bleibt
        ed.titleFocusChanged(true)
        srv.title = "Fremd"; srv.rev = 6
        ed.load()
        ed.editTitle("Reiseplan 2!")
        ed.titleFocusChanged(false)
        assertEquals("Reiseplan 2!", ed.title)
    }
}
