package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.data.ApiException
import io.github.yggdrasil_sh.saga.data.Comment
import io.github.yggdrasil_sh.saga.data.CommentPosted
import io.github.yggdrasil_sh.saga.data.CommentThread
import io.github.yggdrasil_sh.saga.data.CommentsResponse
import io.github.yggdrasil_sh.saga.data.PageShareInfo
import io.github.yggdrasil_sh.saga.data.Person
import io.github.yggdrasil_sh.saga.data.Role
import io.github.yggdrasil_sh.saga.ui.comments.BlockQuote
import io.github.yggdrasil_sh.saga.ui.comments.CommentAction
import io.github.yggdrasil_sh.saga.ui.comments.CommentBackend
import io.github.yggdrasil_sh.saga.ui.comments.CommentLogic
import io.github.yggdrasil_sh.saga.ui.comments.CommentsModel
import io.github.yggdrasil_sh.saga.ui.comments.Segment
import io.github.yggdrasil_sh.saga.util.UiText
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val ANNA = Person("Anna", "anna")
private val BERT = Person("Bert", "bert")
private val ME = Person("Cora", "cora", me = true)

private fun c(id: Int, who: Person, body: String = "x", deleted: Boolean = false) =
    Comment(id = id, author = who, body = body, mine = who.me, deleted = deleted, created = 100.0 + id)

private fun t(id: Int, block: String, vararg cs: Comment, resolved: Boolean = false, unread: Boolean = false, by: Person? = null) =
    CommentThread(id = id, blockId = block, resolvedAt = if (resolved) 5.0 else null, resolvedBy = by, unread = unread, comments = cs.toList())

/** Server-Attrappe: merkt Aufrufe, liefert eine feste Liste */
private class FakeComments : CommentBackend {
    var threads = listOf(t(1, "b1", c(1, ANNA), unread = true), t(2, "", c(2, ME)), t(3, "b1", c(3, BERT), resolved = true))
    val calls = ArrayList<String>()
    var fail: Int? = null
    var unreadReported: Boolean? = null

    override suspend fun comments(id: Int, resolved: Boolean): CommentsResponse {
        calls += "load:$resolved"
        val list = if (resolved) threads else threads.filter { !it.resolved }
        return CommentsResponse(role = "comment", threads = list, resolvedCount = threads.count { it.resolved })
    }

    override suspend fun addComment(id: Int, blockId: String?, threadId: Int?, body: String): CommentPosted {
        fail?.let { throw ApiException(it, UiText.Raw("Zu viele Kommentare in kurzer Zeit – bitte kurz warten.")) }
        calls += "add:${blockId ?: ""}:${threadId ?: ""}:$body"
        return CommentPosted()
    }

    override suspend fun editComment(cid: Int, body: String) { calls += "edit:$cid:$body" }
    override suspend fun deleteComment(cid: Int, all: Boolean) { calls += "delete:$cid:$all" }
    override suspend fun resolveComment(cid: Int, resolved: Boolean) {
        calls += "resolve:$cid:$resolved"
        threads = threads.map { if (it.id == cid) it.copy(resolvedAt = if (resolved) 9.0 else null) else it }
    }
    override suspend fun commentSeen(cid: Int) {
        calls += "seen:$cid"
        threads = threads.map { if (it.id == cid) it.copy(unread = false) else it }
    }
    override fun unreadChanged(id: Int, unread: Boolean) { unreadReported = unread }
    override fun friendly(e: Throwable): UiText = (e as? ApiException)?.text ?: UiText.Raw(e.message ?: "?")
}

class CommentsTest {
    @Test
    fun countsAndFiltering() {
        val list = listOf(t(1, "b1", c(1, ANNA), unread = true), t(2, "b1", c(2, BERT)), t(3, "b2", c(3, ANNA), resolved = true), t(4, "", c(4, ME)))
        assertEquals(mapOf("b1" to 2), CommentLogic.blockCounts(list))        // nur offene, ohne "ganze Seite"
        assertEquals(setOf("b1"), CommentLogic.unreadBlocks(list))
        assertEquals(listOf(1, 2, 4), CommentLogic.shown(list, false, null).map { it.id })
        assertEquals(listOf(1, 2, 3, 4), CommentLogic.shown(list, true, null).map { it.id })
        assertEquals(listOf(1, 2), CommentLogic.shown(list, false, "b1").map { it.id })
    }

    @Test
    fun emptyStatesNameTheNextStep() {
        assertEquals(UiText.Res(R.string.comments_empty_tap), CommentLogic.emptyText(null, Role.COMMENT, 0, false))
        assertEquals(UiText.Res(R.string.comments_empty), CommentLogic.emptyText(null, Role.OWNER, 0, false))
        assertEquals(UiText.Res(R.string.comments_empty_ro), CommentLogic.emptyText(null, Role.VIEW, 0, false))
        assertEquals(UiText.Plural(R.plurals.comments_empty_resolved, 2), CommentLogic.emptyText(null, Role.EDIT, 2, false))
        assertEquals(UiText.Res(R.string.comments_empty_block), CommentLogic.emptyText("b", Role.EDIT, 0, false))
        assertEquals(UiText.Res(R.string.comments_empty_block_open), CommentLogic.emptyText("b", Role.EDIT, 1, false))
        assertEquals(UiText.Res(R.string.comments_empty_block_ro), CommentLogic.emptyText("b", Role.VIEW, 0, false))
    }

    @Test
    fun actionsByRole() {
        val mine = t(1, "b", c(1, ME), c(2, ANNA))
        // eigener erster Kommentar mit fremder Antwort: bearbeiten, loeschen (Antworten bleiben)
        assertEquals(listOf(CommentAction.EDIT, CommentAction.DELETE), CommentLogic.actions(mine, mine.comments[0], true, Role.COMMENT))
        assertEquals(emptyList<CommentAction>(), CommentLogic.actions(mine, mine.comments[1], false, Role.COMMENT))   // fremder
        assertEquals(emptyList<CommentAction>(), CommentLogic.actions(mine, mine.comments[0], true, Role.VIEW))      // Rolle Lesen
        // Eigentuemer moderiert: fremde loeschen, ganze Unterhaltung loeschen
        val other = t(2, "b", c(3, ANNA), c(4, BERT))
        assertEquals(listOf(CommentAction.DELETE, CommentAction.DELETE_THREAD), CommentLogic.actions(other, other.comments[0], true, Role.OWNER))
        val del = t(3, "b", c(5, ANNA, "", deleted = true), c(6, BERT))
        assertEquals(listOf(CommentAction.DELETE_THREAD), CommentLogic.actions(del, del.comments[0], true, Role.OWNER))
    }

    @Test
    fun deletePromptsLikeWeb() {
        val alone = t(1, "b", c(1, ME), c(2, ME))
        val withOthers = t(2, "b", c(3, ME), c(4, ANNA))
        assertEquals(R.string.comments_delete_thread_title to R.string.comments_delete_thread_text, CommentLogic.deletePrompt(alone, true, CommentAction.DELETE))
        assertEquals(R.string.comments_delete_title to R.string.comments_delete_keep_replies, CommentLogic.deletePrompt(withOthers, true, CommentAction.DELETE))
        assertEquals(R.string.comments_delete_title to R.string.comments_delete_text, CommentLogic.deletePrompt(withOthers, false, CommentAction.DELETE))
        assertEquals(R.string.comments_delete_thread_title to R.string.comments_delete_thread_all, CommentLogic.deletePrompt(withOthers, true, CommentAction.DELETE_THREAD))
    }

    @Test
    fun unreadDotSitsAtLastCommentFromOthers() {
        assertEquals(1, CommentLogic.unreadIndex(t(1, "b", c(1, ME), c(2, ANNA), c(3, ME), unread = true)))
        assertEquals(-1, CommentLogic.unreadIndex(t(1, "b", c(1, ANNA))))
    }

    @Test
    fun linksAreDetected() {
        assertEquals(
            listOf(Segment("Preise siehe "), Segment("https://example.org/holz", "https://example.org/holz"), Segment(".")),
            CommentLogic.linkify("Preise siehe https://example.org/holz."),
        )
        assertEquals(listOf(Segment("ohne Link")), CommentLogic.linkify("ohne Link"))
        assertEquals(listOf(Segment("javascript:alert(1)")), CommentLogic.linkify("javascript:alert(1)"))
        assertEquals(2, CommentLogic.linkify("http://a.de und http://b.de").count { it.url != null })
    }

    @Test
    fun resolvedLabel() {
        assertEquals(UiText.Res(R.string.comments_resolved), CommentLogic.resolvedLabel(t(1, "b", resolved = true)))
        assertEquals(UiText.Res(R.string.comments_resolved_by_you), CommentLogic.resolvedLabel(t(1, "b", resolved = true, by = ME)))
        assertEquals(UiText.Res(R.string.comments_resolved_by, "Anna"), CommentLogic.resolvedLabel(t(1, "b", resolved = true, by = ANNA)))
    }

    @Test
    fun blockQuoteAndJump() {
        val doc = Json.parseToJsonElement(
            """{"blocks":[{"id":"a","type":"paragraph","data":{"text":"Budget: etwa <b>300 €</b>"}},
               {"id":"t","type":"toggle","data":{"title":"Mehr","blocks":[{"id":"in","type":"paragraph","data":{"text":"Innen"}}]}},
               {"id":"i","type":"image","data":{"file":{"url":"/files/x.png"}}},
               {"id":"l","type":"paragraph","data":{"text":"${"x".repeat(80)}"}}]}""",
        ) as JsonObject
        assertEquals(BlockQuote.Text("Budget: etwa 300 €"), CommentLogic.snippet(doc, "a"))
        assertEquals(BlockQuote.Text("Innen"), CommentLogic.snippet(doc, "in"))
        assertEquals(BlockQuote.Image, CommentLogic.snippet(doc, "i"))
        assertEquals(70, (CommentLogic.snippet(doc, "l") as BlockQuote.Text).text.length)
        assertNull(CommentLogic.snippet(doc, "weg"))                         // "Block entfernt"
        assertEquals(1, CommentLogic.topLevelIndex(doc, "in"))
        assertEquals(-1, CommentLogic.topLevelIndex(doc, "weg"))
    }

    @Test
    fun modelUsesPageCountsUntilLoaded() = runTest {
        val m = CommentsModel(1, FakeComments(), PageShareInfo(supported = true, role = Role.COMMENT, commentCounts = mapOf("b9" to 3), commentsOpen = 3, commentsUnread = 1))
        assertEquals(mapOf("b9" to 3), m.blockCounts)
        assertEquals(3, m.openCount)
        assertEquals(1, m.unreadCount)
        m.load()
        assertEquals(mapOf("b1" to 1), m.blockCounts)
        assertEquals(2, m.openCount)
        assertEquals(1, m.resolvedCount)
        assertTrue(m.showResolvedSwitch)
    }

    @Test
    fun sendReplyResolveAndSeen() = runTest {
        val be = FakeComments()
        val m = CommentsModel(1, be, PageShareInfo(supported = true, role = Role.COMMENT))
        m.load()
        m.openBlock("b1")
        assertTrue(m.send("  Neuer Kommentar  "))
        assertTrue("add:b1::Neuer Kommentar" in be.calls)
        m.showAll()
        assertTrue(m.send("Zur Seite"))
        assertTrue("add:::Zur Seite" in be.calls)
        m.replyTo = 1
        assertTrue(m.reply(1, "Antwort"))
        assertTrue("add::1:Antwort" in be.calls)
        assertNull(m.replyTo)
        assertFalse(m.send("   "))                                     // leer: nichts senden
        // gesehen: ungelesene sichtbare Unterhaltung -> seen, Punkt im Baum weg
        m.markSeen()
        assertTrue("seen:1" in be.calls)
        assertEquals(0, m.unreadCount)
        assertEquals(false, be.unreadReported)
        // erledigen: Zahl am Block sofort weg + Meldung mit Rueckgaengig
        val th = m.threads.first { it.id == 1 }
        m.resolve(th, true)
        assertTrue("resolve:1:true" in be.calls)
        assertTrue(m.blockCounts.isEmpty())
        assertEquals(UiText.Res(R.string.comments_thread_resolved), m.toast?.text)
    }

    @Test
    fun rateLimitShowsFriendlyMessageInSheet() = runTest {
        val be = FakeComments().apply { fail = 429 }
        val m = CommentsModel(1, be, PageShareInfo(supported = true, role = Role.EDIT))
        m.openAll()
        assertFalse(m.send("Noch einer"))
        assertEquals(UiText.Res(R.string.comments_not_sent, UiText.Raw("Zu viele Kommentare in kurzer Zeit – bitte kurz warten.")), m.toast?.text)
    }

    @Test
    fun viewRoleCannotWrite() = runTest {
        val be = FakeComments()
        val m = CommentsModel(1, be, PageShareInfo(supported = true, role = Role.VIEW))
        assertFalse(m.canComment)
        m.openAll()
        assertFalse(m.send("x"))
        assertTrue(be.calls.none { it.startsWith("add") })
    }
}
