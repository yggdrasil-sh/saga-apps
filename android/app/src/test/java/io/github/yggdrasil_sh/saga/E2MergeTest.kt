package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.blocks.BlockDoc
import io.github.yggdrasil_sh.saga.blocks.MergeLogic
import io.github.yggdrasil_sh.saga.blocks.RenderPreview
import io.github.yggdrasil_sh.saga.blocks.SyncEdit
import io.github.yggdrasil_sh.saga.data.ApiException
import io.github.yggdrasil_sh.saga.data.Drafts
import io.github.yggdrasil_sh.saga.data.MemoryDrafts
import io.github.yggdrasil_sh.saga.ui.page.PageBackend
import io.github.yggdrasil_sh.saga.ui.page.PageEditor
import io.github.yggdrasil_sh.saga.ui.page.PageLiveLogic
import io.github.yggdrasil_sh.saga.ui.page.PresenceThrottle
import io.github.yggdrasil_sh.saga.util.UiText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Server-Attrappe mit Zusammenfuehren: kennt je rev den Stand (Basis) und fuehrt blockweise zusammen (vereinfachte
 * Regel: Bloecke, die ich gegenueber der Basis geaendert habe, gewinnen, sonst gilt der Server). Merkt sich, was
 * gesendet wurde (merge/base).
 */
private class MergeServer : PageBackend {
    val json = Json
    var title = "T"
    var rev = 1
    val bodies = HashMap<Int, String>()
    val sent = HashMap<Int, String>()                  // eingereichte Fassung je Basis-rev (base "sent")
    val calls = ArrayList<Triple<Int?, Boolean, Boolean>>()   // rev, merge, baseSent
    var since = ArrayList<Int?>()
    var gate: CompletableDeferred<Unit>? = null
    var mergeSupported = true
    val drafts = MemoryDrafts()
    var conflictsOut: List<String> = emptyList()

    init { bodies[1] = doc("a" to "Eins", "b" to "Zwei") }

    fun doc(vararg p: Pair<String, String>) = """{"blocks":[${p.joinToString(",") { (id, t) -> """{"id":"$id","type":"paragraph","data":{"text":"$t"}}""" }}]}"""
    fun texts(body: String): List<String> = BlockDoc.blocks(BlockDoc.parse(body, "blocks").doc).map { BlockDoc.str(BlockDoc.data(it), "text") }
    fun blocksOf(body: String) = BlockDoc.blocks(BlockDoc.parse(body, "blocks").doc).associateBy { BlockDoc.id(it)!! }

    /** fremde Person speichert */
    fun theirEdit(id: String, text: String) {
        val bl = BlockDoc.blocks(BlockDoc.parse(bodies[rev]!!, "blocks").doc).map { b ->
            if (BlockDoc.id(b) == id) """{"id":"$id","type":"paragraph","data":{"text":"$text"}}""" else b.toString()
        }
        rev++
        bodies[rev] = """{"blocks":[${bl.joinToString(",")}]}"""
    }

    override val currentServer = "https://s.example.org"
    override val signedOut = false
    override fun draftsForCurrent(): Drafts = drafts
    override suspend fun <T> withPageLock(id: Long, block: suspend () -> T): T = block()
    override suspend fun loadPage(id: Long): JsonObject = buildJsonObject {
        put("id", id); put("title", title); put("body", bodies[rev]!!); put("format", "blocks"); put("rev", rev)
    }

    override suspend fun loadPageSince(id: Long, since: Int?): JsonObject {
        this.since.add(since)
        val base = since?.let { bodies[it] } ?: return loadPage(id)
        val old = blocksOf(base)
        val cur = BlockDoc.blocks(BlockDoc.parse(bodies[rev]!!, "blocks").doc)
        val arr = cur.joinToString(",") { b -> val i = BlockDoc.id(b)!!; if (old[i] == b) """{"id":"$i"}""" else b.toString() }
        return json.parseToJsonElement("""{"id":$id,"title":"$title","format":"blocks","rev":$rev,"delta":{"from":$since,"blocks":[$arr]}}""").jsonObject
    }

    override suspend fun savePage(id: Long, title: String, body: String?, rev: Int?): Int? {
        calls.add(Triple(rev, false, false))
        if (rev != null && rev != this.rev) throw ApiException(409, UiText.Raw("conflict"))
        this.rev++
        bodies[this.rev] = body!!
        return this.rev
    }

    override suspend fun savePageMerge(id: Long, title: String, body: String?, rev: Int?, merge: Boolean, baseSent: Boolean): MergeLogic.SaveOutcome {
        gate?.await()
        calls.add(Triple(rev, merge, baseSent))
        if (!mergeSupported) return MergeLogic.SaveOutcome(savePage(id, title, body, rev))
        val baseBody = (if (baseSent) sent[rev!!] else bodies[rev!!]) ?: throw ApiException(409, UiText.Raw("conflict"))
        sent[this.rev + 1] = body!!
        if (rev == this.rev && !baseSent) {
            this.rev++
            bodies[this.rev] = body
            return MergeLogic.SaveOutcome(this.rev)
        }
        val base = blocksOf(baseBody)
        val mine = blocksOf(body)
        val theirs = BlockDoc.blocks(BlockDoc.parse(bodies[this.rev]!!, "blocks").doc)
        val out = theirs.map { b -> val i = BlockDoc.id(b)!!; if (mine[i] != null && mine[i] != base[i]) mine[i]!! else b }
        val merged = """{"blocks":[${out.joinToString(",")}]}"""
        this.rev++
        bodies[this.rev] = merged
        val resp = """{"ok":true,"rev":${this.rev},"body":${JsonPrimitive(merged)},"title":"$title","format":"blocks",
            "merged":{"from":$rev,"conflicts":${conflictsOut.map { "\"$it\"" }},"restored":[],"lost":[]}}"""
        return MergeLogic.parseSave(resp)
    }

    override suspend fun createPageWith(title: String, body: String): Long = 9
    override suspend fun setPinned(id: Long, pinned: Boolean) {}
    override suspend fun setLocked(id: Long, locked: Boolean) {}
    override fun updateTitleLocal(id: Long, title: String) {}
    override fun visited(id: Long) {}
    override fun friendly(e: Throwable): UiText = UiText.Raw(e.message ?: "?")
}

@OptIn(ExperimentalCoroutinesApi::class)
class E2MergeTest {
    private val json = Json

    private fun TestScope.settle() {
        advanceTimeBy(30_000)
        runCurrent()
    }

    private fun TestScope.editor(srv: PageBackend): PageEditor {
        val d = StandardTestDispatcher(testScheduler)
        return PageEditor(1, srv, backgroundScope, io = d, cpu = d, clock = { testScheduler.currentTime })
    }

    private fun texts(ed: PageEditor) = BlockDoc.blocks(ed.doc).map { BlockDoc.str(BlockDoc.data(it), "text") }
    private val pa = listOf<Any>("blocks", 0, "data", "text")
    private val pb = listOf<Any>("blocks", 1, "data", "text")

    @Test fun unchangedEditorAdoptsMergedBody() = runTest {
        val srv = MergeServer()
        val ed = editor(srv)
        ed.load()
        srv.theirEdit("b", "Zwei (Web)")                 // fremde Aenderung, rev 2
        ed.setString(pa, "Eins (App)")
        settle()
        assertEquals(Triple<Int?, Boolean, Boolean>(1, true, false), srv.calls.last())
        assertEquals(listOf("Eins (App)", "Zwei (Web)"), texts(ed))   // blockweise uebernommen
        assertFalse(ed.dirty)
        assertFalse(ed.conflict)
        assertEquals(3, ed.currentRev)
        assertFalse(ed.baseSentNext)
    }

    @Test fun keptTypingSendsBaseSent() = runTest {
        val srv = MergeServer()
        val ed = editor(srv)
        ed.load()
        srv.theirEdit("b", "Zwei (Web)")
        ed.setString(pa, "Eins A")
        srv.gate = CompletableDeferred()
        advanceTimeBy(1300); runCurrent()                 // Speichern laeuft, haengt
        ed.setString(pa, "Eins AB")                       // weitergeschrieben
        srv.gate!!.complete(Unit)
        srv.gate = null
        settle()
        // erster Aufruf rev 1 merge, zweiter auf der neuen rev mit base "sent"
        assertEquals(Triple<Int?, Boolean, Boolean>(1, true, false), srv.calls[0])
        assertEquals(Triple<Int?, Boolean, Boolean>(3, true, true), srv.calls[1])
        assertEquals(listOf("Eins AB", "Zwei (Web)"), texts(ed))
        assertEquals(listOf("Eins AB", "Zwei (Web)"), srv.texts(srv.bodies[srv.rev]!!))
        assertFalse(ed.dirty)
    }

    @Test fun oldServer409KeepsDialog() = runTest {
        val srv = FakeOld()
        val ed = editor(srv)
        ed.load()
        srv.rev = 4
        ed.setString(pa, "Meins")
        settle()
        assertTrue(ed.conflict)                           // Konfliktdialog bleibt als Rueckfall
    }

    private class FakeOld : PageBackend {
        var rev = 1
        override val currentServer = "https://s.example.org"
        override val signedOut = false
        val d = MemoryDrafts()
        override fun draftsForCurrent(): Drafts = d
        override suspend fun <T> withPageLock(id: Long, block: suspend () -> T): T = block()
        override suspend fun loadPage(id: Long): JsonObject = buildJsonObject {
            put("id", id); put("title", "t"); put("body", """{"blocks":[{"id":"a","type":"paragraph","data":{"text":"x"}}]}"""); put("format", "blocks"); put("rev", rev)
        }
        override suspend fun savePage(id: Long, title: String, body: String?, rev: Int?): Int? {
            if (rev != this.rev) throw ApiException(409, UiText.Raw("conflict"))
            return ++this.rev
        }
        override suspend fun createPageWith(title: String, body: String): Long = 1
        override suspend fun setPinned(id: Long, pinned: Boolean) {}
        override suspend fun setLocked(id: Long, locked: Boolean) {}
        override fun updateTitleLocal(id: Long, title: String) {}
        override fun visited(id: Long) {}
        override fun friendly(e: Throwable): UiText = UiText.Raw("?")
    }

    @Test fun remoteChangeUsesDeltaAndKeepsUnchangedObjects() = runTest {
        val srv = MergeServer()
        val ed = editor(srv)
        ed.load()
        val a0 = BlockDoc.blocks(ed.doc)[0]
        srv.theirEdit("b", "Zwei!")
        assertTrue(ed.followRemoteChange())
        settle()
        assertEquals(listOf<Int?>(1), srv.since)          // ?since=<bekannte rev>
        assertEquals(listOf("Eins", "Zwei!"), texts(ed))
        assertTrue(BlockDoc.blocks(ed.doc)[0] === a0)     // unveraenderter Block bleibt dasselbe Objekt (Fokus)
        assertEquals(2, ed.currentRev)
    }

    @Test fun remoteChangeWhileDirtySavesWithMerge() = runTest {
        val srv = MergeServer()
        val ed = editor(srv)
        ed.load()
        ed.setString(pa, "Eins (mein)")
        srv.theirEdit("b", "Zwei (fremd)")
        assertTrue(ed.followRemoteChange())
        settle()
        assertEquals(listOf("Eins (mein)", "Zwei (fremd)"), texts(ed))  // ungespeicherter eigener Block bleibt
        assertFalse(ed.dirty)
    }

    @Test fun applyDeltaFallsBackWhenBaseMissing() {
        val base = json.parseToJsonElement("""{"blocks":[{"id":"a","type":"paragraph","data":{"text":"1"}}]}""").jsonObject
        val ok = MergeLogic.applyDelta(base, json.parseToJsonElement("""{"from":1,"blocks":[{"id":"n","type":"paragraph","data":{"text":"neu"}},{"id":"a"}]}""").jsonObject)!!
        assertEquals(listOf("n", "a"), BlockDoc.blocks(ok).map { BlockDoc.id(it) })
        assertNull(MergeLogic.applyDelta(base, json.parseToJsonElement("""{"from":1,"blocks":[{"id":"x"}]}""").jsonObject))
    }

    @Test fun conflictBlockAcceptAndDiscard() {
        val doc = json.parseToJsonElement("""{"blocks":[
            {"id":"a1","type":"paragraph","data":{"text":"Server"}},
            {"id":"a1-k","type":"paragraph","data":{"text":"Meins"},"tunes":{"hkconflict":{"by":"~aaaaaaaaaaaaaaaa","name":"M","at":1,"of":"a1"},"hkstyle":{"c":"red","bg":false}}},
            {"id":"z","type":"paragraph","data":{"text":"z"}}]}""").jsonObject
        val info = MergeLogic.conflict(BlockDoc.blocks(doc)[1])!!
        assertEquals("M", info.name)
        assertEquals("a1", info.of)
        val acc = MergeLogic.acceptConflict(doc, "a1-k")
        assertEquals(listOf("a1-k", "z"), BlockDoc.blocks(acc).map { BlockDoc.id(it) })
        val tunes = BlockDoc.blocks(acc)[0].jsonObject["tunes"]!!.jsonObject
        assertEquals(setOf("hkstyle"), tunes.keys)        // andere tunes bleiben
        val dis = MergeLogic.discardConflict(doc, "a1-k")
        assertEquals(listOf("a1", "z"), BlockDoc.blocks(dis).map { BlockDoc.id(it) })
        // verschachtelt (Spalte)
        val nested = json.parseToJsonElement("""{"blocks":[{"id":"c","type":"columns","data":{"cols":[{"blocks":[
            {"id":"x","type":"paragraph","data":{"text":"S"}},
            {"id":"x-k","type":"paragraph","data":{"text":"M"},"tunes":{"hkconflict":{"name":"M","of":"x"}}}]},{"blocks":[]}]}}]}""").jsonObject
        val na = MergeLogic.acceptConflict(nested, "x-k")
        val col = BlockDoc.data(BlockDoc.blocks(na)[0])["cols"]!!.jsonArray[0].jsonObject["blocks"]!!.jsonArray
        assertEquals(1, col.size)
        assertNull(col[0].jsonObject["tunes"])
    }

    @Test fun editorConflictActionsSave() = runTest {
        val srv = MergeServer()
        srv.bodies[1] = """{"blocks":[{"id":"a","type":"paragraph","data":{"text":"S"}},{"id":"a-k","type":"paragraph","data":{"text":"M"},"tunes":{"hkconflict":{"name":"Anna","of":"a"}}}]}"""
        val ed = editor(srv)
        ed.load()
        ed.acceptConflict("a-k")
        settle()
        assertEquals(listOf("M"), srv.texts(srv.bodies[srv.rev]!!))
        assertFalse(srv.bodies[srv.rev]!!.contains("hkconflict"))
    }

    @Test fun parseSaveFieldsAndHints() {
        val o = MergeLogic.parseSave("""{"ok":true,"rev":12,"body":"{\"blocks\":[]}","title":"X","merged":{"from":10,"conflicts":["a1-k"],"restored":["b2"],"lost":["db7"],"title_lost":true},
            "synced":{"AAAAAAAAAAAAAAAA":"ok","BBBBBBBBBBBBBBBB":"stale"},"synced_rev":{"AAAAAAAAAAAAAAAA":31}}""")
        assertEquals(12, o.rev)
        assertEquals(listOf("a1-k"), o.merged!!.conflicts)
        assertTrue(o.merged!!.titleLost)
        assertEquals("X", o.title)
        assertEquals(31, o.syncedRev["AAAAAAAAAAAAAAAA"])
        assertTrue(o.syncReload)
        val plain = MergeLogic.parseSave("""{"ok":true,"rev":3,"updated":1.5}""")
        assertNull(plain.merged)
        assertNull(plain.body)
        assertEquals(MergeLogic.Rebase.PLAIN, MergeLogic.rebase(plain, 1, 1))
        assertEquals(MergeLogic.Rebase.ADOPT, MergeLogic.rebase(o, 1, 1))
        assertEquals(MergeLogic.Rebase.KEEP_LOCAL_BASE_SENT, MergeLogic.rebase(o, 1, 2))
        assertEquals(emptyMap<String, Any>(), MergeLogic.saveFields(null, true, false))
        assertEquals(setOf("merge", "base"), MergeLogic.saveFields(3, true, true).keys)
        assertTrue(MergeLogic.canMerge(false, emptySet()))
        assertFalse(MergeLogic.canMerge(true, setOf("presence")))
        assertTrue(MergeLogic.canMerge(true, setOf("merge1")))
    }

    @Test fun presenceThrottleAndParsing() {
        val t = PresenceThrottle()
        assertEquals(0L, t.due("a", 10_000))
        t.sent("a", 10_000)
        assertNull(t.due("a", 10_100))                    // gleicher Block: nichts
        assertEquals(700L, t.due("b", 10_300))            // Wechsel innerhalb 1 s: warten
        assertEquals(0L, t.due("b", 11_000))
        val users = PageLiveLogic.users(json.parseToJsonElement("""[
            {"username":"x","name":"Anna","editing":true,"block":"b1","color":"green"},
            {"username":"y","name":"Bert","editing":false,"block":"b2","color":"red"},
            {"username":"me","name":"Ich","editing":true,"me":true,"block":"b1","color":"blue"},
            {"username":"z","name":"Cleo","editing":true,"block":"b1","color":"lila"}]""").jsonArray)
        assertEquals("green", users[0].color)
        assertNull(users[3].color)                        // nur Palette
        val m = PageLiveLogic.byBlock(users)
        assertEquals(listOf("Anna", "Cleo"), m["b1"]!!.map { it.name })
        assertNull(m["b2"])                               // nicht editing
    }

    // ---------------------------------------------------------------- synchronisierte Bloecke (sync1)

    private val syncedDoc = """{"blocks":[
        {"id":"o","type":"synced","data":{"sid":"OOOOOOOOOOOOOOOO","blocks":[{"id":"i1","type":"paragraph","data":{"text":"orig"}}],"src":{"original":true,"refs":1}}},
        {"id":"k","type":"synced","data":{"sid":"OOOOOOOOOOOOOOOO","src":{"missing":true}}},
        {"id":"c","type":"synced","data":{"sid":"CCCCCCCCCCCCCCCC","blocks":[{"id":"i2","type":"paragraph","data":{"text":"kopie"}}],"src":{"note":7,"block":"x","role":"edit","rev":4,"refs":2}}},
        {"id":"v","type":"synced","data":{"sid":"VVVVVVVVVVVVVVVV","blocks":[{"id":"i3","type":"paragraph","data":{"text":"lesen"}}],"src":{"note":8,"block":"y","role":"view","rev":2,"refs":1}}}]}"""

    @Test fun originalAndCopyOnSamePageKeepContent() {
        // Pruefer-Fund 466f188: Original und Kopie derselben sid auf einer Seite - das Original darf nie leer gespeichert werden
        val doc = json.parseToJsonElement(syncedDoc).jsonObject
        val saved = RenderPreview.docForSave(doc)
        val o = BlockDoc.data(BlockDoc.blocks(saved)[0])
        assertEquals("orig", BlockDoc.str(BlockDoc.data(o["blocks"]!!.jsonArray[0]), "text"))
        assertNull(o["src"])
        val k = BlockDoc.data(BlockDoc.blocks(saved)[1])
        assertEquals(setOf("sid"), k.keys)
        val c = BlockDoc.data(BlockDoc.blocks(saved)[2])
        assertEquals(setOf("sid"), c.keys)               // unveraenderte Kopie: nur {sid}
    }

    @Test fun editedCopyGoesBackWithSrc() {
        val doc = json.parseToJsonElement(syncedDoc).jsonObject
        val loaded = SyncEdit.contents(doc)
        assertEquals(setOf("CCCCCCCCCCCCCCCC"), loaded.keys)   // nur bearbeitbare Kopien (view/original nicht)
        val p = listOf<Any>("blocks", 2, "data", "blocks", 0, "data", "text")
        val ed = io.github.yggdrasil_sh.saga.blocks.JsonTree.set(doc, p, JsonPrimitive("kopie neu")) as JsonObject
        val edited = SyncEdit.edited(ed, loaded)
        assertEquals(setOf("CCCCCCCCCCCCCCCC"), edited)
        val saved = RenderPreview.docForSave(ed, edited)
        val c = BlockDoc.data(BlockDoc.blocks(saved)[2])
        assertEquals(4, c["src"]!!.jsonObject["rev"].toString().toInt())     // src unveraendert
        assertEquals("kopie neu", BlockDoc.str(BlockDoc.data(c["blocks"]!!.jsonArray[0]), "text"))
        // Original bleibt auch dann erhalten
        assertNotNull(BlockDoc.data(BlockDoc.blocks(saved)[0])["blocks"])
        // Antwort synced_rev -> src.rev
        val after = SyncEdit.withSrcRev(ed, mapOf("CCCCCCCCCCCCCCCC" to 9))
        assertEquals("9", BlockDoc.data(BlockDoc.blocks(after)[2])["src"]!!.jsonObject["rev"].toString())
        assertFalse(SyncEdit.editable(BlockDoc.data(BlockDoc.blocks(doc)[3])))
        assertTrue(SyncEdit.isOriginal(BlockDoc.data(BlockDoc.blocks(doc)[0])))
    }
}
