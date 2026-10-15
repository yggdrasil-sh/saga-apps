package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.blocks.BlockDoc
import io.github.yggdrasil_sh.saga.data.ApiException
import io.github.yggdrasil_sh.saga.data.Drafts
import io.github.yggdrasil_sh.saga.data.MemoryDrafts
import io.github.yggdrasil_sh.saga.data.Role
import io.github.yggdrasil_sh.saga.ui.page.PageBackend
import io.github.yggdrasil_sh.saga.ui.page.PageEditor
import io.github.yggdrasil_sh.saga.blocks.InlineHtml
import io.github.yggdrasil_sh.saga.ui.page.LOCK
import io.github.yggdrasil_sh.saga.ui.page.LockMapping
import io.github.yggdrasil_sh.saga.ui.page.maskedStarts
import io.github.yggdrasil_sh.saga.util.UiText
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Server-Attrappe fuer eine fremde Seite (Server 1.3): Rolle, Eigentuemer, 409 mit "by", Entzug (403/404) */
private class SharedPageServer(var role: String? = "edit") : PageBackend {
    var title = "Projektplan"
    var body = """{"blocks":[{"id":"p1","type":"paragraph","data":{"text":"Hallo"}}]}"""
    var rev = 1
    var conflictBy: String? = null
    var failWith: Int? = null                            // naechstes Speichern: dieser Status (403/404)
    var gone = false                                     // Laden: 404
    val saves = ArrayList<Triple<String, Int?, Boolean>>()   // Text, Revision, overwrite
    val drafts = MemoryDrafts()
    private val locks = HashMap<Long, Mutex>()

    override val currentServer = "https://s.example.org"
    override val signedOut = false
    var accountKey = "uid:bert"
    override val currentAccountKey: String get() = accountKey
    override fun draftsForCurrent(): Drafts = drafts
    override suspend fun <T> withPageLock(id: Long, block: suspend () -> T): T = locks.getOrPut(id) { Mutex() }.withLock { block() }
    override suspend fun loadPage(id: Long): JsonObject {
        if (gone) throw ApiException(404, UiText.Raw("not found"))
        return buildJsonObject {
            put("id", id); put("title", title); put("body", body); put("format", "blocks"); put("rev", rev)
            put("updated", 1_000.0)
            if (role != null) {
                put("role", role)
                putJsonObject("owner") { put("name", "Anna Teilt"); put("username", "anna"); put("me", role == "owner") }
                put("shared", true)
                putJsonObject("comment_counts") { put("p1", 2) }
                put("comments_open", 2); put("comments_unread", 1); put("comments_resolved", 1)
                putJsonObject("updated_by") { put("name", "Anna Teilt"); put("username", "anna"); put("me", false) }
            }
        }
    }

    override suspend fun savePage(id: Long, title: String, body: String?, rev: Int?): Int? = savePage(id, title, body, rev, false)

    override suspend fun savePage(id: Long, title: String, body: String?, rev: Int?, overwrite: Boolean): Int? {
        failWith?.let { throw ApiException(it, UiText.Raw("nope")) }
        if (rev != null && rev != this.rev) {
            val by = conflictBy?.let { "\"$it\"" } ?: "null"
            throw ApiException(409, UiText.Raw("conflict"), """{"error":"conflict","rev":${this.rev},"by":$by}""")
        }
        this.title = title
        this.body = body ?: this.body
        this.rev++
        saves += Triple(text(this.body), rev, overwrite)
        return this.rev
    }

    override suspend fun createPageWith(title: String, body: String): Long = 99
    override suspend fun setPinned(id: Long, pinned: Boolean) {}
    override suspend fun setLocked(id: Long, locked: Boolean) {}
    override fun updateTitleLocal(id: Long, title: String) {}
    override fun visited(id: Long) {}
    override fun friendly(e: Throwable): UiText = UiText.Raw(e.message ?: "?")

    fun text(b: String): String = BlockDoc.str(BlockDoc.data(BlockDoc.blocks(BlockDoc.parse(b, "blocks").doc)[0]), "text")
}

@OptIn(ExperimentalCoroutinesApi::class)
class PageEditorShareTest {
    private val path = listOf<Any>("blocks", 0, "data", "text")

    private fun TestScope.settle() {
        advanceTimeBy(30_000)
        runCurrent()
    }

    private fun TestScope.editor(srv: SharedPageServer): PageEditor {
        val d = StandardTestDispatcher(testScheduler)
        return PageEditor(1, srv, backgroundScope, io = d, cpu = d, clock = { testScheduler.currentTime })
    }

    private fun text(ed: PageEditor) = BlockDoc.str(BlockDoc.data(BlockDoc.blocks(ed.doc)[0]), "text")

    @Test
    fun roleFromServerControlsEditing() = runTest {
        for ((role, canEdit) in listOf("view" to false, "comment" to false, "edit" to true, "owner" to true)) {
            val srv = SharedPageServer(role)
            val ed = editor(srv)
            ed.load()
            assertEquals(role, canEdit, ed.editable)
            assertEquals(role, canEdit, ed.titleEditable)
            ed.setString(path, "Geaendert")
            ed.editTitle("Neu")
            settle()
            assertEquals(role, if (canEdit) 1 else 0, srv.saves.size)       // Lesen/Kommentieren speichert nie
        }
    }

    @Test
    fun oldServerWithoutRoleIsOwner() = runTest {
        val ed = editor(SharedPageServer(role = null))
        ed.load()
        assertFalse(ed.share.supported)
        assertEquals(Role.OWNER, ed.share.role)
        assertTrue(ed.editable)
        assertFalse(ed.caps.canShare)                     // aelterer Server: kein Teilen
        assertFalse(ed.caps.showComments)
    }

    @Test
    fun pageInfoIsParsed() = runTest {
        val ed = editor(SharedPageServer("comment"))
        ed.load()
        val s = ed.share
        assertTrue(s.supported)
        assertEquals("Anna Teilt", s.owner?.name)
        assertEquals(mapOf("p1" to 2), s.commentCounts)
        assertEquals(2, s.commentsOpen)
        assertEquals(1, s.commentsUnread)
        assertEquals("Anna Teilt", s.updatedBy?.name)
        assertTrue(ed.caps.foreign)
        assertTrue(ed.caps.canComment)
        assertFalse(ed.caps.canShare)
    }

    @Test
    fun conflictNamesThePersonAndKeepMineOverwrites() = runTest {
        val srv = SharedPageServer("edit")
        val ed = editor(srv)
        ed.load()
        srv.rev = 4
        srv.conflictBy = "Bert Bearbeitet"
        ed.setString(path, "Meins")
        settle()
        assertTrue(ed.conflict)
        assertEquals("Bert Bearbeitet", ed.conflictBy)
        assertEquals("Meins", text(ed))                    // nichts verworfen
        ed.keepMine()
        settle()
        assertFalse(ed.conflict)
        assertNull(ed.conflictBy)
        assertEquals(Triple("Meins", 4, true), srv.saves.last())   // mit overwrite (die andere Fassung landet in den Versionen)
    }

    @Test
    fun loadNewVersionReturnsMyTextForCopying() = runTest {
        val srv = SharedPageServer("edit")
        val ed = editor(srv)
        ed.load()
        srv.rev = 3
        srv.conflictBy = "Bert"
        srv.body = """{"blocks":[{"id":"p1","type":"paragraph","data":{"text":"Von Bert"}}]}"""
        ed.setString(path, "Mein <b>Text</b>")
        settle()
        assertTrue(ed.conflict)
        val mine = ed.loadServerVersion()
        settle()
        assertEquals("Projektplan\n\nMein Text", mine)    // Klartext zum Kopieren
        assertFalse(ed.conflict)
        assertFalse(ed.dirty)
        assertEquals("Von Bert", text(ed))
        assertNull("Entwurf weg", srv.drafts.read(1))
        assertTrue(srv.saves.isEmpty())                     // nichts ueberschrieben
    }

    @Test
    fun accessRevokedWhileEditingStopsSavingAndOffersText() = runTest {
        val srv = SharedPageServer("edit")
        val ed = editor(srv)
        ed.load()
        srv.failWith = 404
        ed.setString(path, "Letzter Stand")
        settle()
        val lost = ed.lost
        assertTrue(lost != null && !lost.readOnly)
        assertEquals("Projektplan\n\nLetzter Stand", lost!!.text)
        assertFalse(ed.editable)
        assertNull(ed.error)
        srv.failWith = null
        ed.setString(path, "Noch mehr")                    // nichts mehr annehmen/speichern
        settle()
        assertTrue(srv.saves.isEmpty())
        srv.gone = true
        ed.closeLost()
        settle()
        assertNull(ed.lost)
        assertTrue(ed.noAccess)                            // "Kein Zugriff auf diese Seite"
        assertNull(srv.drafts.read(1))
    }

    @Test
    fun roleDroppedToViewMakesPageReadOnly() = runTest {
        val srv = SharedPageServer("edit")
        val ed = editor(srv)
        ed.load()
        srv.failWith = 403
        ed.setString(path, "x")
        settle()
        assertTrue(ed.lost!!.readOnly)
        srv.failWith = null
        srv.role = "view"
        ed.closeLost()
        settle()
        assertNull(ed.lost)
        assertFalse(ed.noAccess)
        assertFalse(ed.editable)                           // jetzt nur lesen
        assertEquals("Hallo", text(ed))
    }

    /** G2: nach Rollenwechsel/Entzug sofort der neue Zustand - nicht erst nach dem Kopier-Dialog */
    @Test
    fun lostAccessShowsNewStateRightAway() = runTest {
        val srv = SharedPageServer("edit")
        val ed = editor(srv)
        ed.load()
        srv.failWith = 403
        srv.role = "view"
        ed.setString(path, "Meins")
        settle()
        assertTrue(ed.lost!!.readOnly)
        assertEquals("Projektplan\n\nMeins", ed.lost!!.text)
        assertEquals(Role.VIEW, ed.share.role)             // Etikett "Du kannst lesen" schon hinter dem Dialog
        assertNull(ed.pendingDraft)                        // kein "Wiederherstellen" bei Nur-Lesen
        assertFalse(ed.dirty)                              // kein "Speichert…"
        assertFalse(ed.editable)
        // 404: "Kein Zugriff auf diese Seite" schon hinter dem Dialog
        val srv2 = SharedPageServer("edit")
        val ed2 = editor(srv2)
        ed2.load()
        srv2.failWith = 404
        srv2.gone = true
        ed2.setString(path, "x")
        settle()
        assertTrue(ed2.lost != null && !ed2.lost!!.readOnly)
        assertTrue(ed2.noAccess)
        assertFalse(ed2.fetched)
        ed2.closeLost()
        settle()
        assertNull(ed2.lost)
        assertTrue(ed2.noAccess)
    }

    /** G7: 🔒 vor verdeckten Verweisen auch beim Bearbeiten - nur Anzeige, Cursor nie im Schloss */
    @Test
    fun lockBeforeMaskedLinksWhileEditing() {
        val rt = InlineHtml.parse("Notizen in <a href=\"/notes#7\">Seite ohne Zugriff</a> und <a href=\"/notes#8\">Echt</a>")
        val at = maskedStarts(rt)
        assertEquals(listOf(11), at)
        assertEquals("Notizen in Seite ohne Zugriff und Echt", rt.text)  // Text unveraendert
        val m = LockMapping(1, rt.text.length, at)                        // mit START (Laenge 1)
        val n = LOCK.length
        assertEquals(0, m.originalToTransformed(1))
        assertEquals(11, m.originalToTransformed(12))                     // vor dem Schloss
        assertEquals(12 + n, m.originalToTransformed(13))                 // im Verweis: hinter dem Schloss
        assertEquals(12, m.transformedToOriginal(11))
        assertEquals(12, m.transformedToOriginal(12))                     // im Schloss -> vor den Verweis
        assertEquals(12, m.transformedToOriginal(11 + n))
        assertEquals(1 + rt.text.length, m.transformedToOriginal(rt.text.length + n))
        assertEquals(rt.text.length + n, m.originalToTransformed(1 + rt.text.length))
    }

    @Test
    fun ownerGets403AsNormalErrorNotLostAccess() = runTest {
        val srv = SharedPageServer("owner")
        val ed = editor(srv)
        ed.load()
        srv.failWith = 403
        ed.setString(path, "x")
        settle()
        assertNull(ed.lost)
        assertTrue(ed.error != null)
    }

    /** Pruefer: anderes Konto angemeldet (gleicher Server) - offene Seite des alten Kontos sendet nichts mehr */
    @Test
    fun accountSwitchNeverSavesIntoNewAccount() = runTest {
        val srv = SharedPageServer("edit")
        srv.accountKey = "uid:bert"
        val ed = editor(srv)
        ed.load()
        ed.setString(path, "Von Bert")
        srv.accountKey = "uid:cora"                         // z. B. Kopplung per QR mit einem anderen Konto
        ed.close()                                         // Seite wird neu geoeffnet (signInCount) -> flush
        settle()
        assertTrue(srv.saves.isEmpty())
        assertEquals("Von Bert", srv.text(srv.drafts.read(1)!!.body!!))   // bleibt als Entwurf (Ablage des alten Kontos)
        // gleiches Konto bzw. Kennung erst spaeter bekannt: normal speichern
        val srv2 = SharedPageServer("edit")
        srv2.accountKey = ""
        val ed2 = editor(srv2)
        ed2.load()
        srv2.accountKey = "uid:bert"
        ed2.setString(path, "Normal")
        settle()
        assertEquals(1, srv2.saves.size)
    }

    @Test
    fun pageNotSharedAnymoreOnOpen() = runTest {
        val srv = SharedPageServer("view")
        srv.gone = true
        val ed = editor(srv)
        ed.load()
        assertTrue(ed.noAccess)
        assertFalse(ed.fetched)
        assertNull(ed.error)
    }
}
