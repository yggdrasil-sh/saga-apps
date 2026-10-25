package io.github.yggdrasil_sh.saga.ui.comments

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.blocks.InlineHtml
import io.github.yggdrasil_sh.saga.data.Comment
import io.github.yggdrasil_sh.saga.data.CommentPosted
import io.github.yggdrasil_sh.saga.data.CommentThread
import io.github.yggdrasil_sh.saga.data.CommentsResponse
import io.github.yggdrasil_sh.saga.data.PageShareInfo
import io.github.yggdrasil_sh.saga.data.Role
import io.github.yggdrasil_sh.saga.util.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Kommentare einer Seite (SagaRepository; in Tests eine Attrappe) */
interface CommentBackend {
    suspend fun comments(id: Long, resolved: Boolean): CommentsResponse
    /** Neue Unterhaltung (blockId, "" = ganze Seite) oder Antwort (threadId) */
    suspend fun addComment(id: Long, blockId: String?, threadId: Long?, body: String): CommentPosted
    suspend fun editComment(cid: Long, body: String)
    /** all = ganze Unterhaltung (nur Eigentuemer der Seite) */
    suspend fun deleteComment(cid: Long, all: Boolean)
    suspend fun resolveComment(cid: Long, resolved: Boolean)
    suspend fun commentSeen(cid: Long)
    /** Punkt "neue Kommentare" im Seitenbaum nachziehen */
    fun unreadChanged(id: Long, unread: Boolean) {}
    fun friendly(e: Throwable): UiText
}

/** Aktionen im ⋯-Menue eines Kommentars */
enum class CommentAction { EDIT, DELETE, DELETE_THREAD }

/** Text eines Kommentars: Klartext + erkannte Links (nur http/https) */
data class Segment(val text: String, val url: String? = null)

/** Zitat des Blocks ueber einer Unterhaltung */
sealed interface BlockQuote {
    data class Text(val text: String) : BlockQuote
    data object Image : BlockQuote
    data object Other : BlockQuote
}

/** Kurze Meldung im Sheet (das Sheet liegt ueber der Snackbar der App) */
class SheetToast(val text: UiText, val action: UiText? = null, val onAction: (() -> Unit)? = null)

/** Reine Regeln (Zustand -> Anzeige), wie static/comments.js - Unit-Tests */
object CommentLogic {
    const val MAX = 2000
    private val TEXT_KEYS = setOf("text", "title", "code", "content", "caption", "items", "blocks", "cols")

    fun openThreads(threads: List<CommentThread>): List<CommentThread> = threads.filter { !it.resolved }

    /** Zahl am Block: offene Unterhaltungen je Block (ohne "ganze Seite") */
    fun blockCounts(threads: List<CommentThread>): Map<String, Int> =
        openThreads(threads).filter { it.blockId.isNotEmpty() }.groupingBy { it.blockId }.eachCount()

    fun unreadBlocks(threads: List<CommentThread>): Set<String> =
        openThreads(threads).filter { it.unread && it.blockId.isNotEmpty() }.map { it.blockId }.toSet()

    /** Angezeigte Unterhaltungen: ohne erledigte (ausser "Erledigte zeigen"), in der Blockansicht nur die des Blocks */
    fun shown(threads: List<CommentThread>, showResolved: Boolean, block: String?): List<CommentThread> =
        threads.filter { showResolved || !it.resolved }.filter { block == null || it.blockId == block }

    /** Leerer Zustand mit naechstem Schritt (Texte wie im Web; Handy: Absatz antippen) */
    fun emptyText(block: String?, role: Role, resolvedCount: Int, showResolved: Boolean): UiText {
        val can = role.canComment
        if (block != null) return UiText.Res(
            when {
                !can -> R.string.comments_empty_block_ro
                resolvedCount > 0 && !showResolved -> R.string.comments_empty_block_open
                else -> R.string.comments_empty_block
            },
        )
        if (resolvedCount > 0 && !showResolved) {
            return UiText.Plural(R.plurals.comments_empty_resolved, resolvedCount)
        }
        if (!can) return UiText.Res(R.string.comments_empty_ro)
        return UiText.Res(if (role == Role.COMMENT) R.string.comments_empty_tap else R.string.comments_empty)
    }

    /** Hat jemand anderes in der Unterhaltung geantwortet? (dann bleibt beim Loeschen des ersten Kommentars der Rest) */
    fun othersReplied(t: CommentThread): Boolean {
        val first = t.comments.firstOrNull() ?: return false
        return t.comments.drop(1).any { it.author.username == null || it.author.username != first.author.username }
    }

    /** ⋯-Menue: eigene bearbeiten/loeschen; Eigentuemer moderiert (loeschen, ganze Unterhaltung) */
    fun actions(t: CommentThread, c: Comment, first: Boolean, role: Role): List<CommentAction> {
        val mine = c.mine && role.canComment && !c.deleted
        val mod = role.isOwner
        return buildList {
            if (mine) add(CommentAction.EDIT)
            if (!c.deleted && (mine || mod)) add(CommentAction.DELETE)
            if (first && mod && (othersReplied(t) || c.deleted)) add(CommentAction.DELETE_THREAD)
        }
    }

    /** Rueckfrage beim Loeschen: (Titel, Text) */
    fun deletePrompt(t: CommentThread, first: Boolean, action: CommentAction): Pair<Int, Int> = when {
        action == CommentAction.DELETE_THREAD -> R.string.comments_delete_thread_title to R.string.comments_delete_thread_all
        first && !othersReplied(t) -> R.string.comments_delete_thread_title to R.string.comments_delete_thread_text
        first -> R.string.comments_delete_title to R.string.comments_delete_keep_replies
        else -> R.string.comments_delete_title to R.string.comments_delete_text
    }

    /** Ungelesen: Punkt neben dem letzten Kommentar von anderen (-1 = keiner) */
    fun unreadIndex(t: CommentThread): Int = if (!t.unread) -1 else t.comments.indexOfLast { !it.mine }

    /** Links im Kommentar (gemeinsame Regel mit Chat und KI-Antwort: util.Links) */
    fun linkify(text: String): List<Segment> = io.github.yggdrasil_sh.saga.util.Links.split(text).map { Segment(it.text, it.url) }

    /** "✓ Erledigt" bzw. "Erledigt von {name}" ("von dir") */
    fun resolvedLabel(t: CommentThread): UiText {
        val by = t.resolvedBy ?: return UiText.Res(R.string.comments_resolved)
        return if (by.me) UiText.Res(R.string.comments_resolved_by_you) else UiText.Res(R.string.comments_resolved_by, by.name)
    }

    /** Zitat des Blocks (hoechstens 70 Zeichen); null = Block nicht (mehr) da ("Block entfernt") */
    fun snippet(doc: JsonObject, blockId: String): BlockQuote? {
        val b = findBlock(doc["blocks"], blockId) ?: return null
        val texts = ArrayList<String>()
        fun collect(el: JsonElement?) {
            when (el) {
                is JsonPrimitive -> if (el.isString) texts.add(InlineHtml.plain(el.content))
                is JsonArray -> el.forEach { collect(it) }
                is JsonObject -> for ((k, v) in el) if (k in TEXT_KEYS) collect(v)
                else -> {}
            }
        }
        collect(b["data"])
        val s = texts.joinToString(" ").replace(Regex("\\s+"), " ").trim()
        if (s.isEmpty()) return if ((b["type"] as? JsonPrimitive)?.content == "image") BlockQuote.Image else BlockQuote.Other
        return BlockQuote.Text(if (s.length > 70) s.take(69) + "…" else s)
    }

    /** Index des obersten Blocks, der den Block id enthaelt (auch in Ausklappbar/Spalten), sonst -1 - fuer "Zum Block" */
    fun topLevelIndex(doc: JsonObject, id: String): Int {
        val arr = doc["blocks"] as? JsonArray ?: return -1
        arr.forEachIndexed { i, x -> if (findBlock(JsonArray(listOf(x)), id) != null) return i }
        return -1
    }

    private fun findBlock(el: JsonElement?, id: String): JsonObject? {
        val arr = el as? JsonArray ?: return null
        for (x in arr) {
            val o = x as? JsonObject ?: continue
            if ((o["id"] as? JsonPrimitive)?.content == id) return o
            val d = o["data"] as? JsonObject ?: continue
            findBlock(d["blocks"], id)?.let { return it }
            (d["cols"] as? JsonArray)?.forEach { c -> findBlock((c as? JsonObject)?.get("blocks"), id)?.let { return it } }
        }
        return null
    }
}

/**
 * Kommentare einer Seite: Liste (Sheet von unten), Zaehler fuer den Seitenkopf und die Zahl am Block.
 * Vor dem ersten Laden gelten die Zaehler aus GET /api/notes/{id}.
 */
class CommentsModel(val pageId: Long, private val backend: CommentBackend, initial: PageShareInfo) {
    var role by mutableStateOf(initial.role)
        private set
    var threads by mutableStateOf<List<CommentThread>>(emptyList())
        private set
    var resolvedCount by mutableIntStateOf(initial.commentsResolved)
        private set
    var loaded by mutableStateOf(false)
        private set
    var loading by mutableStateOf(false)
        private set
    var showResolved by mutableStateOf(false)
        private set
    /** Sheet offen; block = Blockansicht (null = alle Unterhaltungen) */
    var visible by mutableStateOf(false)
        private set
    var block by mutableStateOf<String?>(null)
        private set
    var replyTo by mutableStateOf<Long?>(null)
    var editing by mutableStateOf<Long?>(null)
    var expanded by mutableStateOf(setOf<Long>())
    var busy by mutableStateOf(false)
        private set
    var toast by mutableStateOf<SheetToast?>(null)
    var loadError by mutableStateOf<UiText?>(null)
        private set
    private var initialCounts by mutableStateOf(initial.commentCounts)
    private var initialOpen by mutableIntStateOf(initial.commentsOpen)
    private var initialUnread by mutableIntStateOf(initial.commentsUnread)
    private val seenSent = HashSet<Long>()
    private var seq = 0

    val canComment: Boolean get() = role.canComment
    val blockCounts: Map<String, Int> get() = if (loaded) CommentLogic.blockCounts(threads) else initialCounts
    val unreadBlocks: Set<String> get() = if (loaded) CommentLogic.unreadBlocks(threads) else emptySet()
    val openCount: Int get() = if (loaded) CommentLogic.openThreads(threads).size else initialOpen
    val unreadCount: Int get() = if (loaded) CommentLogic.openThreads(threads).count { it.unread } else initialUnread
    val shownThreads: List<CommentThread> get() = CommentLogic.shown(threads, showResolved, block)
    val emptyText: UiText get() = CommentLogic.emptyText(block, role, resolvedCount, showResolved)
    /** Schalter "Erledigte zeigen" nur, wenn es erledigte gibt */
    val showResolvedSwitch: Boolean get() = resolvedCount > 0 || showResolved

    /** Neuer Seitenstand (Laden, Neu laden): Zaehler und Rolle uebernehmen */
    fun update(info: PageShareInfo) {
        role = info.role
        initialCounts = info.commentCounts
        initialOpen = info.commentsOpen
        initialUnread = info.commentsUnread
        if (!loaded) resolvedCount = info.commentsResolved
    }

    suspend fun load() {
        val n = ++seq
        loading = true
        try {
            val r = backend.comments(pageId, showResolved)
            if (n != seq) return
            threads = r.threads
            resolvedCount = r.resolvedCount
            r.role?.let { role = Role.of(it) }
            loaded = true
            loadError = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (n == seq) loadError = UiText.Res(R.string.comments_load_failed, backend.friendly(e))
        } finally {
            if (n == seq) loading = false
        }
    }

    fun openAll() {
        block = null
        replyTo = null
        editing = null
        visible = true
    }

    fun openBlock(id: String) {
        block = id
        replyTo = null
        editing = null
        visible = true
    }

    fun showAll() {
        block = null
        replyTo = null
        editing = null
    }

    fun close() {
        visible = false
        block = null
        replyTo = null
        editing = null
    }

    suspend fun setShowResolved(on: Boolean) {
        showResolved = on
        load()
    }

    fun expand(threadId: Long) {
        expanded = expanded + threadId
    }

    private fun failed(res: Int, e: Exception) {
        toast = SheetToast(UiText.Res(res, backend.friendly(e)))
    }

    private suspend fun write(failRes: Int, block: suspend () -> Unit): Boolean {
        if (busy) return false
        busy = true
        return try {
            block()
            load()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed(failRes, e)
            false
        } finally {
            busy = false
        }
    }

    /** Ungesendete neue Kommentare je Block ("" = ganze Seite) - ueberleben das Schliessen des Blatts (BUG-067) */
    private val drafts = mutableStateMapOf<String, String>()

    var draft: String
        get() = drafts[block.orEmpty()].orEmpty()
        set(v) {
            if (v.isEmpty()) drafts.remove(block.orEmpty()) else drafts[block.orEmpty()] = v
        }

    /** Neue Unterhaltung am aktuellen Block (bzw. zur ganzen Seite) -> true = gesendet (Entwurf geleert) */
    suspend fun send(body: String): Boolean {
        val t = body.trim()
        if (t.isEmpty() || !canComment) return false
        val key = block.orEmpty()
        return write(R.string.comments_not_sent) { backend.addComment(pageId, key, null, t.take(CommentLogic.MAX)) }
            .also { if (it) drafts.remove(key) }
    }

    suspend fun reply(threadId: Long, body: String): Boolean {
        val t = body.trim()
        if (t.isEmpty() || !canComment) return false
        return write(R.string.comments_not_sent) {
            backend.addComment(pageId, null, threadId, t.take(CommentLogic.MAX))
            replyTo = null
        }
    }

    suspend fun edit(cid: Long, body: String): Boolean {
        val t = body.trim()
        if (t.isEmpty()) return false
        return write(R.string.comments_not_saved) {
            backend.editComment(cid, t.take(CommentLogic.MAX))
            editing = null
        }
    }

    suspend fun delete(cid: Long, all: Boolean) {
        write(R.string.comments_not_deleted) { backend.deleteComment(cid, all) }
    }

    /** "✓ Erledigt" / "Wieder öffnen"; beim Erledigen Meldung mit "Rückgängig" */
    suspend fun resolve(t: CommentThread, on: Boolean, onUndo: () -> Unit = {}) {
        write(R.string.comments_not_saved) {
            backend.resolveComment(t.id, on)
            if (on) {
                // Zahl am Block sofort weg, nicht erst nach dem Neuladen
                threads = threads.map { if (it.id == t.id) it.copy(resolvedAt = System.currentTimeMillis() / 1000.0) else it }
                toast = SheetToast(UiText.Res(R.string.comments_thread_resolved), UiText.Res(R.string.action_undo), onUndo)
            }
            expanded = expanded - t.id
        }
    }

    /** Sichtbare, ungelesene Unterhaltungen als gelesen merken (der Punkt verschwindet) */
    suspend fun markSeen() {
        if (!visible) return
        val todo = shownThreads.filter { it.unread && !it.resolved && it.id !in seenSent }
        if (todo.isEmpty()) return
        for (t in todo) {
            seenSent.add(t.id)
            try {
                backend.commentSeen(t.id)
                threads = threads.map { if (it.id == t.id) it.copy(unread = false) else it }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                seenSent.remove(t.id)
            }
        }
        if (unreadCount == 0) backend.unreadChanged(pageId, false)
    }
}
