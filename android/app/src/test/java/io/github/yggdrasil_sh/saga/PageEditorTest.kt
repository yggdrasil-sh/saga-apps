package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.blocks.BlockDoc
import io.github.yggdrasil_sh.saga.data.ApiException
import io.github.yggdrasil_sh.saga.data.Draft
import io.github.yggdrasil_sh.saga.data.Drafts
import io.github.yggdrasil_sh.saga.data.MemoryDrafts
import io.github.yggdrasil_sh.saga.ui.page.PageBackend
import io.github.yggdrasil_sh.saga.ui.page.PageEditor
import io.github.yggdrasil_sh.saga.util.UiText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** Server-Attrappe fuer eine Seite: Revision, 409 bei veralteter Revision, optional haengender Request */
private class FakePageServer : PageBackend {
    var title = "Test"
    var body = """{"blocks":[{"id":"p1","type":"paragraph","data":{"text":"Hallo"}}]}"""
    var rev = 1
    val saves = ArrayList<Pair<String, Int?>>()          // gespeicherter Text + mitgeschickte Revision
    var gate: CompletableDeferred<Unit>? = null          // != null: Speichern haengt, bis freigegeben
    var cancelledSaves = 0
    var offline = false
    var server = "https://s.example.org"
    var copies = ArrayList<Pair<String, String>>()
    val drafts = MemoryDrafts()
    private val locks = HashMap<Long, Mutex>()

    override val currentServer get() = server
    override val signedOut = false
    override fun draftsForCurrent(): Drafts = drafts
    override suspend fun <T> withPageLock(id: Long, block: suspend () -> T): T = locks.getOrPut(id) { Mutex() }.withLock { block() }
    override suspend fun loadPage(id: Long): JsonObject = buildJsonObject {
        put("id", id); put("title", title); put("body", body); put("format", "blocks"); put("rev", rev)
    }

    override suspend fun savePage(id: Long, title: String, body: String?, rev: Int?): Int? {
        if (offline) throw IOException("offline")
        try {
            gate?.await()
        } catch (e: kotlinx.coroutines.CancellationException) {
            cancelledSaves++
            throw e
        }
        if (rev != null && rev != this.rev) throw ApiException(409, UiText.Raw("conflict"))
        this.title = title
        this.body = body ?: this.body
        this.rev++
        saves += text(this.body) to rev
        return this.rev
    }

    override suspend fun createPageWith(title: String, body: String): Long {
        copies += title to text(body)
        return 99
    }

    override suspend fun setPinned(id: Long, pinned: Boolean) {}
    override suspend fun setLocked(id: Long, locked: Boolean) {}
    override fun updateTitleLocal(id: Long, title: String) {}
    override fun visited(id: Long) {}
    override fun friendly(e: Throwable): UiText = UiText.Raw(e.message ?: "?")

    fun text(b: String): String {
        val d = BlockDoc.parse(b, "blocks").doc
        return BlockDoc.str(BlockDoc.data(BlockDoc.blocks(d)[0]), "text")
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class PageEditorTest {
    private val path = listOf<Any>("blocks", 0, "data", "text")

    /** Alles abarbeiten - auch Hintergrund-Aufgaben (advanceUntilIdle laesst backgroundScope liegen) */
    private fun TestScope.settle() {
        advanceTimeBy(30_000)
        runCurrent()
    }

    private fun TestScope.editor(srv: FakePageServer): PageEditor {
        val d = StandardTestDispatcher(testScheduler)
        return PageEditor(1, srv, backgroundScope, io = d, cpu = d, clock = { testScheduler.currentTime })
    }

    @Test
    fun typingDuringSaveNeverCancelsTheRequest() = runTest {
        val srv = FakePageServer()
        val ed = editor(srv)
        ed.load()
        ed.setString(path, "Hallo W")
        srv.gate = CompletableDeferred()
        advanceTimeBy(1300)                               // Tipp-Pause -> Speichern startet und haengt
        runCurrent()
        assertTrue(ed.saving)
        ed.setString(path, "Hallo Welt")                  // weitertippen, waehrend der Request laeuft
        advanceTimeBy(1300)
        ed.flush()                                        // Seite verlassen
        runCurrent()
        srv.gate!!.complete(Unit)
        settle()
        assertEquals(0, srv.cancelledSaves)               // nie abgebrochen
        assertEquals(listOf("Hallo W" to 1, "Hallo Welt" to 2), srv.saves)   // richtige Revisionen -> kein falscher 409
        assertFalse(ed.dirty)
        assertFalse(ed.conflict)
        assertNull("Entwurf nach Speichern geloescht", srv.drafts.read(1))
    }

    @Test
    fun conflictKeepsLocalTextAndDraft() = runTest {
        val srv = FakePageServer()
        val ed = editor(srv)
        ed.load()
        srv.rev = 5                                       // woanders gespeichert
        srv.body = """{"blocks":[{"id":"p1","type":"paragraph","data":{"text":"Vom Web"}}]}"""
        ed.setString(path, "Meins")
        settle()
        assertTrue(ed.conflict)
        assertEquals("Meins", BlockDoc.str(BlockDoc.data(BlockDoc.blocks(ed.doc)[0]), "text"))   // nichts verworfen
        assertTrue(ed.dirty)
        assertNotNull(srv.drafts.read(1))
        assertEquals("Vom Web", srv.text(srv.body))
        ed.keepMine()                                     // meine Fassung behalten -> mit aktueller Revision ueberschreiben
        settle()
        assertFalse(ed.conflict)
        assertEquals("Meins", srv.text(srv.body))
        assertEquals(5, srv.saves.last().second)
    }

    @Test
    fun conflictSaveAsCopy() = runTest {
        val srv = FakePageServer()
        val ed = editor(srv)
        ed.load()
        srv.rev = 7
        ed.setString(path, "Meine Fassung")
        settle()
        assertTrue(ed.conflict)
        val id = ed.saveCopyAndLoadServer("Test (meine Fassung)")
        settle()
        assertEquals(99L, id)
        assertEquals(listOf("Test (meine Fassung)" to "Meine Fassung"), srv.copies)
        assertFalse(ed.conflict)
        assertEquals("Hallo", BlockDoc.str(BlockDoc.data(BlockDoc.blocks(ed.doc)[0]), "text"))
    }

    @Test
    fun offlineKeepsDraftAndRetries() = runTest {
        val srv = FakePageServer()
        val ed = editor(srv)
        ed.load()
        srv.offline = true
        ed.setString(path, "Offline getippt")
        advanceTimeBy(2000)
        runCurrent()
        assertTrue("Offline-Hinweis statt Fehler", ed.offline)
        assertNull(ed.error)
        assertEquals("Offline getippt", srv.drafts.read(1)?.let { srv.text(it.body!!) })
        srv.offline = false
        advanceTimeBy(11_000)                             // automatischer neuer Versuch
        runCurrent()
        settle()
        assertEquals("Offline getippt", srv.text(srv.body))
        assertNull(ed.error)
        assertFalse(ed.offline)
        assertNull(srv.drafts.read(1))
    }

    @Test
    fun draftIsOfferedAndRestoredAfterProcessDeath() = runTest {
        val srv = FakePageServer()
        srv.drafts.write(Draft(1, "Test", """{"blocks":[{"id":"p1","type":"paragraph","data":{"text":"Vor dem Absturz"}}]}""", 1, 0))
        val ed = editor(srv)
        ed.load()
        assertNotNull("Banner: Nicht gespeicherte Aenderungen", ed.pendingDraft)
        assertFalse("bis zur Entscheidung nur lesbar", ed.editable)
        settle()
        assertTrue(srv.saves.isEmpty())
        ed.restoreDraft()
        settle()
        assertEquals("Vor dem Absturz", srv.text(srv.body))   // wiederhergestellt und gespeichert
        assertNull(srv.drafts.read(1))
        assertFalse(ed.conflict)
    }

    @Test
    fun discardedDraftIsGone() = runTest {
        val srv = FakePageServer()
        srv.drafts.write(Draft(1, "Test", """{"blocks":[{"id":"p1","type":"paragraph","data":{"text":"Verworfen"}}]}""", 1, 0))
        val ed = editor(srv)
        ed.load()
        ed.discardDraft()
        settle()
        assertNull(ed.pendingDraft)
        assertTrue(ed.editable)
        assertNull(srv.drafts.read(1))
        assertTrue(srv.saves.isEmpty())
        assertEquals("Hallo", srv.text(srv.body))
    }

    @Test
    fun draftOnOutdatedRevisionIsAConflictNotAnOverwrite() = runTest {
        val srv = FakePageServer()
        srv.rev = 3
        srv.drafts.write(Draft(1, "Test", """{"blocks":[{"id":"p1","type":"paragraph","data":{"text":"Alt"}}]}""", 1, 0))
        val ed = editor(srv)
        ed.load()
        assertFalse("noch kein Konflikt, nur das Angebot", ed.conflict)
        ed.restoreDraft()
        settle()
        assertTrue(ed.conflict)
        assertTrue(srv.saves.isEmpty())
        assertNotNull("Entwurf bleibt bis zur Entscheidung", srv.drafts.read(1))
    }

    /** N4: gespeichert, aber Prozess vor dem Loeschen des Entwurfs beendet -> Revision hoeher, Inhalt gleich */
    @Test
    fun alreadySavedDraftIsNoConflictAfterProcessDeath() = runTest {
        val srv = FakePageServer()
        srv.rev = 4
        srv.body = """{"time":123456,"blocks":[{"id":"p1","type":"paragraph","data":{"text":"Schon gespeichert"}}]}"""
        srv.drafts.write(Draft(1, "Test", """{"blocks":[{"id":"p1","type":"paragraph","data":{"text":"Schon gespeichert"}}]}""", 3, 0))
        val ed = editor(srv)
        ed.load()
        settle()
        assertFalse(ed.conflict)
        assertNull("kein Banner", ed.pendingDraft)
        assertNull("Entwurf erledigt", srv.drafts.read(1))
        assertTrue(srv.saves.isEmpty())
        assertFalse(ed.dirty)
    }

    /** N4: gleiche Revision, gleicher Inhalt (nur der Zeitstempel unterscheidet sich) -> nichts anzubieten */
    @Test
    fun identicalDraftIsDroppedSilently() = runTest {
        val srv = FakePageServer()
        srv.drafts.write(Draft(1, "Test", """{"time":1,"blocks":[{"id":"p1","type":"paragraph","data":{"text":"Hallo"}}]}""", 1, 0))
        val ed = editor(srv)
        ed.load()
        settle()
        assertNull(ed.pendingDraft)
        assertFalse(ed.conflict)
        assertNull(srv.drafts.read(1))
    }

    @Test
    fun neverSavesToAnotherServer() = runTest {
        val srv = FakePageServer()
        val ed = editor(srv)
        ed.load()
        ed.setString(path, "Fuer Server A")
        srv.server = "https://b.example.org"              // Serverwechsel
        settle()
        assertTrue(srv.saves.isEmpty())
        assertNotNull("Entwurf bleibt fuer Server A", srv.drafts.read(1))
    }

    @Test
    fun unexpectedDataDoesNotCrash() = runTest {
        val srv = FakePageServer()
        srv.body = """{"blocks":[{"id":"p1","type":"paragraph","data":"kaputt"}]}"""
        val ed = editor(srv)
        ed.load()
        ed.setString(listOf("blocks", 0, "data", "text"), "x")   // Pfad passt nicht zu den Daten
        ed.setString(listOf("blocks", 5, "data", "text"), "x")
        settle()
        assertTrue(srv.saves.isEmpty())
        assertEquals(JsonPrimitive("kaputt"), (BlockDoc.blocks(ed.doc)[0] as JsonObject)["data"])
    }
}
