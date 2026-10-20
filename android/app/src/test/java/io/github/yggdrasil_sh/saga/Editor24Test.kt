package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.blocks.BlockDoc
import io.github.yggdrasil_sh.saga.blocks.BlockOps
import io.github.yggdrasil_sh.saga.blocks.JsonTree
import io.github.yggdrasil_sh.saga.blocks.SlashLogic
import io.github.yggdrasil_sh.saga.data.BookmarkLogic
import io.github.yggdrasil_sh.saga.data.CalAccount
import io.github.yggdrasil_sh.saga.data.CalEvent
import io.github.yggdrasil_sh.saga.data.CalInfo
import io.github.yggdrasil_sh.saga.data.Drafts
import io.github.yggdrasil_sh.saga.data.EventForm
import io.github.yggdrasil_sh.saga.data.MemoryDrafts
import io.github.yggdrasil_sh.saga.data.MoveLogic
import io.github.yggdrasil_sh.saga.data.PageMeta
import io.github.yggdrasil_sh.saga.data.Person
import io.github.yggdrasil_sh.saga.data.ServiceItem
import io.github.yggdrasil_sh.saga.data.TrashItem
import io.github.yggdrasil_sh.saga.data.TrashState
import io.github.yggdrasil_sh.saga.ui.page.BlockInserter
import io.github.yggdrasil_sh.saga.ui.page.PageBackend
import io.github.yggdrasil_sh.saga.ui.page.PageEditor
import io.github.yggdrasil_sh.saga.util.UiText
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Seite mit allen Blocktypen aus Web/iOS, fremden Feldern und einem unbekannten Block (muss 1:1 erhalten bleiben) */
internal val ALL_BLOCKS_DOC = """
{"time":1729400000000,"version":"2.31.0","hkExtra":{"a":[1,2,{"b":null}]},"blocks":[
 {"id":"p1","type":"paragraph","data":{"text":"Hallo <b>fett</b> &amp; <a href=\"/notes#5\">Link</a>","alignment":"left"},"tunes":{"hkcomment":{"x":1}}},
 {"id":"h1","type":"header","data":{"text":"Titel","level":3}},
 {"id":"l1","type":"list","data":{"style":"checklist","meta":{},"items":[{"content":"a","meta":{"checked":true},"items":[{"content":"b","meta":{"checked":false},"items":[]}]}]}},
 {"id":"q1","type":"quote","data":{"text":"Zitat","caption":"Quelle","alignment":"left"}},
 {"id":"c1","type":"callout","data":{"emoji":"🔥","text":"Hinweis","color":"red"}},
 {"id":"t1","type":"toggle","data":{"title":"Auf","open":false,"blocks":[{"id":"t1a","type":"paragraph","data":{"text":"innen"}}]}},
 {"id":"k1","type":"code","data":{"code":"x < 1 && y","language":"kotlin"}},
 {"id":"d1","type":"delimiter","data":{}},
 {"id":"i1","type":"image","data":{"file":{"url":"/files/abc","width":800},"caption":"Bild","withBorder":false,"stretched":true,"withBackground":false}},
 {"id":"tb","type":"table","data":{"withHeadings":true,"stretched":false,"content":[["A","B","C"],["1","<i>2</i>"],["x","y","z"]]}},
 {"id":"co","type":"columns","data":{"cols":[{"blocks":[{"id":"c1p","type":"paragraph","data":{"text":"links"}}],"w":1},{"blocks":[{"id":"c2p","type":"header","data":{"text":"rechts","level":2}}]},{"blocks":[{"id":"c3p","type":"paragraph","data":{"text":"drei"}},{"id":"c3q","type":"image","data":{"file":{"url":"/files/q"}}}]}]}},
 {"id":"at","type":"attaches","data":{"file":{"url":"/files/f","name":"a.pdf","size":1234,"extension":"pdf"},"title":"a.pdf"}},
 {"id":"em","type":"embed","data":{"url":"https://youtu.be/dQw4w9WgXcQ","kind":"youtube"}},
 {"id":"nc","type":"nextcloud","data":{"kind":"file","path":"/Docs/x.pdf","author":"mia","share":{"token":"t"}}},
 {"id":"db","type":"database","data":{"db":42,"view":"board","views":[{"id":"v1"}]}},
 {"id":"ta","type":"tasks","data":{}},
 {"id":"ca","type":"calendar","data":{"view":"month","days":14}},
 {"id":"bu","type":"buttons","data":{"buttons":[{"label":"Neu","icon":"➕","action":"db","target":42}]}},
 {"id":"im","type":"immich","data":{}},
 {"id":"zz","type":"hkFutureBlock","data":{"deep":{"x":[1,{"y":"z"}],"n":1500.5,"t":true}},"extra":"bleibt"},
 {"type":"paragraph","data":{"text":"ohne ID"}}
]}
""".trimIndent()

/** Server-Attrappe (wie in PageEditorTest) */
private class Fake24Server(var body: String = ALL_BLOCKS_DOC) : PageBackend {
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

@OptIn(ExperimentalCoroutinesApi::class)
class Editor24Test {
    private fun TestScope.settle() {
        advanceTimeBy(30_000)
        runCurrent()
    }

    private fun TestScope.editor(srv: Fake24Server): PageEditor {
        val d = StandardTestDispatcher(testScheduler)
        return PageEditor(1, srv, backgroundScope, io = d, cpu = d, clock = { testScheduler.currentTime })
    }

    private fun parse(s: String): JsonObject = Json.parseToJsonElement(s) as JsonObject
    private fun blocks(d: JsonObject) = BlockDoc.blocks(d)
    private fun withoutTime(d: JsonObject) = JsonObject(LinkedHashMap(d).apply { remove("time") })
    private fun byId(d: JsonObject, id: String): JsonObject? = blocks(d).firstOrNull { BlockDoc.id(it) == id } as? JsonObject

    // ---------------------------------------------------------------- Round-Trip

    @Test
    fun roundTripKeepsEveryBlockAndField() {
        val orig = parse(ALL_BLOCKS_DOC)
        val parsed = BlockDoc.parse(ALL_BLOCKS_DOC, "blocks").doc
        assertEquals(orig, parsed)
        assertEquals(orig, parse(BlockDoc.serialize(parsed)))
    }

    @Test
    fun editingOneBlockSavesAllOthersUnchanged() = runTest {
        val srv = Fake24Server()
        val ed = editor(srv)
        ed.load()
        val before = ed.doc
        ed.setString(listOf("blocks", 0, "data", "text"), "Neu")
        // nur der Pfad wird kopiert: alle anderen Bloecke sind dieselben Objekte (kein Neuberechnen, kein Verlust)
        for (i in 1 until blocks(before).size) assertSame(blocks(before)[i], blocks(ed.doc)[i])
        settle()
        val saved = parse(srv.body)
        val exp = JsonTree.set(parse(ALL_BLOCKS_DOC), listOf("blocks", 0, "data", "text"), JsonPrimitive("Neu")) as JsonObject
        assertEquals(withoutTime(exp), withoutTime(saved))
        assertEquals("bleibt", (byId(saved, "zz")!!["extra"] as JsonPrimitive).content)
    }

    @Test
    fun tableAndColumnEditsKeepUnknownBlocks() = runTest {
        val srv = Fake24Server()
        val ed = editor(srv)
        ed.load()
        ed.updateBlock(listOf("blocks", 9)) { BlockOps.setCell(it, 1, 2, "neu") }
        ed.updateBlock(listOf("blocks", 10)) { BlockOps.setColumnCount(it, 2) }
        settle()
        val saved = parse(srv.body)
        val orig = parse(ALL_BLOCKS_DOC)
        assertEquals(blocks(orig).size, blocks(saved).size)
        for (id in listOf("p1", "nc", "db", "zz", "bu", "im", "em", "at")) assertEquals(byId(orig, id), byId(saved, id))
        assertEquals(blocks(orig).last(), blocks(saved).last())
        assertEquals(listOf("1", "<i>2</i>", "neu"), BlockOps.cells(byId(saved, "tb")!!)[1])
        assertEquals(false, BlockDoc.bool(BlockDoc.data(byId(saved, "tb")), "stretched"))
    }

    // ---------------------------------------------------------------- Tabelle

    private val table = parse(ALL_BLOCKS_DOC).let { blocks(it)[9] as JsonObject }

    @Test
    fun tableCellsRowsAndColumns() {
        assertEquals(3, BlockOps.columnCount(table))
        val t1 = BlockOps.setCell(table, 1, 2, "C2")                         // kurze Zeile wird aufgefuellt
        assertEquals(listOf("1", "<i>2</i>", "C2"), BlockOps.cells(t1)[1])
        assertEquals(BlockOps.cells(table)[0], BlockOps.cells(t1)[0])
        val t2 = BlockOps.insertRow(t1, 1)
        assertEquals(4, BlockOps.cells(t2).size)
        assertEquals(listOf("", "", ""), BlockOps.cells(t2)[1])
        val t3 = BlockOps.insertColumn(t2, 0)
        assertEquals(4, BlockOps.columnCount(t3))
        assertEquals("", BlockOps.cells(t3)[0][0])
        assertEquals("A", BlockOps.cells(t3)[0][1])
        val t4 = BlockOps.removeColumn(BlockOps.removeRow(t3, 1), 0)
        assertEquals(BlockOps.cells(t1), BlockOps.cells(t4))
        assertTrue(BlockDoc.data(t4).containsKey("stretched"))                 // fremde Felder bleiben
        assertEquals("tb", BlockDoc.id(t4))
        assertFalse(BlockOps.headings(BlockOps.setHeadings(t4, false)))
        assertEquals(BlockOps.cells(t4), BlockOps.cells(BlockOps.setHeadings(t4, false)))
    }

    @Test
    fun tableNeverLosesLastRowOrColumn() {
        val one = BlockOps.table(1, 1)
        assertSame(one, BlockOps.removeRow(one, 0))
        assertSame(one, BlockOps.removeColumn(one, 0))
        assertSame(table, BlockOps.setCell(table, 9, 0, "x"))               // ausserhalb: unveraendert
        val t = BlockOps.table(2, 3)
        assertEquals(listOf(listOf("", "", ""), listOf("", "", "")), BlockOps.cells(t))
        assertTrue(BlockOps.headings(t))
    }

    // ---------------------------------------------------------------- Spalten

    private fun ids(b: JsonObject): List<String?> = (BlockDoc.data(b)["cols"] as JsonArray).flatMap { c ->
        ((c as JsonObject)["blocks"] as JsonArray).map { BlockDoc.id(it) }
    }

    @Test
    fun columnsReduceMergesWithoutLoss() {
        val cols = blocks(parse(ALL_BLOCKS_DOC))[10] as JsonObject
        val two = BlockOps.setColumnCount(cols, 2)
        assertEquals(2, BlockOps.columnBlocksCount(two))
        assertEquals(ids(cols), ids(two))                                    // gleiche Bloecke in gleicher Reihenfolge
        val first = (BlockDoc.data(two)["cols"] as JsonArray)[0] as JsonObject
        assertEquals(JsonPrimitive(1), first["w"])                            // fremdes Feld der Spalte bleibt
        val back = BlockOps.setColumnCount(two, 3)
        assertEquals(3, BlockOps.columnBlocksCount(back))
        assertEquals(ids(two), ids(back).dropLast(1))
        assertSame(two, BlockOps.setColumnCount(two, 2))
        assertEquals(2, BlockOps.columnBlocksCount(BlockOps.setColumnCount(two, 1)))   // mindestens 2
        assertEquals(3, BlockOps.columnBlocksCount(BlockOps.columns(3)))
    }

    // ---------------------------------------------------------------- "/"-Menue

    @Test
    fun slashQueryAndFilter() {
        assertEquals("", SlashLogic.query("/"))
        assertEquals("tab", SlashLogic.query("/Tab"))
        assertNull(SlashLogic.query("/a b"))
        assertNull(SlashLogic.query("Text /tab"))
        assertNull(SlashLogic.query("/" + "x".repeat(25)))
        val all = SlashLogic.Need.entries.toSet()
        val label: (SlashLogic.Item) -> String = { if (it.id == "header") "Überschrift ${it.level}" else it.id }
        assertEquals(SlashLogic.ITEMS.size, SlashLogic.filter("", all, label).size)
        assertTrue(SlashLogic.filter("tab", all, label).any { it.id == "table" })
        assertTrue(SlashLogic.filter("uber", all, label).all { it.id == "header" })
        assertTrue(SlashLogic.filter("таблица", all, label).any { it.id == "table" })
        assertTrue(SlashLogic.filter("ตาราง", all, label).any { it.id == "table" })
        val none = SlashLogic.filter("", emptySet(), label).map { it.id }
        assertFalse("nextcloud" in none || "image" in none || "file" in none || "tasks" in none || "page" in none)
        assertTrue(listOf("table", "columns", "embed", "callout", "toggle", "code", "delimiter").all { it in none })
        assertEquals(SlashLogic.ITEMS.size, SlashLogic.ITEMS.map { it.key }.toSet().size)
    }

    @Test
    fun slashTurnsParagraphIntoBlockAndKeepsTheRest() = runTest {
        val srv = Fake24Server("""{"blocks":[{"id":"a","type":"paragraph","data":{"text":"/spa"}},{"id":"s","type":"paragraph","data":{"text":"/tab"}},{"id":"z","type":"hkFutureBlock","data":{"q":1}}]}""")
        val ed = editor(srv)
        ed.load()
        val ins = BlockInserter(ed)
        ins.start(listOf("blocks", 1), SlashLogic.ITEMS.first { it.id == "table" })
        assertEquals("table", BlockDoc.type(blocks(ed.doc)[1]))
        assertEquals("s", BlockDoc.id(blocks(ed.doc)[1]))
        ins.insertAfter(listOf("blocks", 1), SlashLogic.ITEMS.first { it.id == "delimiter" })
        assertEquals(listOf("paragraph", "table", "delimiter", "paragraph", "hkFutureBlock"), blocks(ed.doc).map { BlockDoc.type(it) })
        ins.start(listOf("blocks", 3), SlashLogic.ITEMS.first { it.id == "header" && it.level == 3 })
        assertEquals(3, BlockDoc.int(BlockDoc.data(blocks(ed.doc)[3]), "level"))
        ins.start(listOf("blocks", 0), SlashLogic.ITEMS.first { it.id == "columns" && it.level == 3 })   // "/spa" -> 3 Spalten
        assertEquals(3, BlockOps.columnBlocksCount(blocks(ed.doc)[0] as JsonObject))
        settle()
        assertEquals(parse("""{"id":"z","type":"hkFutureBlock","data":{"q":1}}"""), blocks(parse(srv.body)).last())
    }

    // ---------------------------------------------------------------- Loeschen mit Rueckgaengig

    @Test
    fun deleteBlockUndoRestoresExactly() = runTest {
        val srv = Fake24Server()
        val ed = editor(srv)
        ed.load()
        val before = ed.doc
        val undo = ed.deleteBlockUndoable(listOf("blocks", 19))!!            // unbekannter Block
        assertNull(byId(ed.doc, "zz"))
        undo()
        assertEquals(before, ed.doc)
        undo()                                                               // doppelt: nicht doppelt einfuegen
        assertEquals(before, ed.doc)
        val inner = ed.deleteBlockUndoable(listOf("blocks", 5, "data", "blocks", 0))!!   // im Aufklapp-Block
        inner()
        assertEquals(before, ed.doc)
    }

    @Test
    fun deleteLastBlockUndoRemovesStarter() = runTest {
        val srv = Fake24Server("""{"blocks":[{"id":"x","type":"table","data":{"withHeadings":false,"content":[["a"]]}}]}""")
        val ed = editor(srv)
        ed.load()
        val before = ed.doc
        val undo = ed.deleteBlockUndoable(listOf("blocks", 0))!!
        assertEquals(listOf("paragraph"), blocks(ed.doc).map { BlockDoc.type(it) })
        undo()
        assertEquals(before, ed.doc)
    }

    // ---------------------------------------------------------------- Performance (500 Bloecke)

    @Test
    fun largePageTypingTouchesOnlyOneBlock() = runTest {
        val big = buildString {
            append("{\"blocks\":[")
            for (i in 0 until 500) {
                if (i > 0) append(',')
                append(if (i % 50 == 7) "{\"id\":\"t$i\",\"type\":\"table\",\"data\":{\"withHeadings\":true,\"content\":[[\"a\",\"b\"],[\"c\",\"d\"]]}}"
                else "{\"id\":\"b$i\",\"type\":\"paragraph\",\"data\":{\"text\":\"Absatz $i mit <b>Text</b>\"}}")
            }
            append("]}")
        }
        val srv = Fake24Server(big)
        val ed = editor(srv)
        ed.load()
        val first = ed.doc
        val path = listOf<Any>("blocks", 250, "data", "text")
        val t0 = System.nanoTime()
        for (n in 1..300) ed.setString(path, "Absatz 250 " + "x".repeat(n))
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("300 Eingaben auf 500 Bloecken: $ms ms", ms < 1500)
        for (i in 0 until 500) if (i != 250) assertSame(blocks(first)[i], blocks(ed.doc)[i])
        settle()
        assertEquals(500, blocks(parse(srv.body)).size)
    }

    // ---------------------------------------------------------------- Verschieben

    private fun pg(id: Long, parent: Long? = null, kind: String? = null, owner: Person? = null, role: String? = null, root: Boolean = false) =
        PageMeta(id = id, title = "P$id", parentId = parent, kind = kind, owner = owner, role = role, root = root)

    @Test
    fun moveTargetsForOwner() {
        val own = listOf(pg(1), pg(2, 1), pg(3, 2), pg(4), pg(5, kind = "database"), pg(6, 5), pg(7, 4))
        val t = MoveLogic.targets(own[1], own, emptyList())
        val ids = t.map { it.id }
        assertEquals(null, ids.first())                                      // oberste Ebene zuerst
        assertFalse(2L in ids)                                              // nicht in sich selbst
        assertFalse(3L in ids)                                              // nicht in eigene Unterseite
        assertFalse(5L in ids)                                              // nicht in eine Datenbank
        assertFalse(6L in ids)                                              // Datenbank-Zeilen sind keine Ziele
        assertTrue(listOf(1L, 4L, 7L).all { it in ids })
        assertTrue(t.first { it.id == 1L }.current)
        assertFalse(MoveLogic.canMove(own[5], own))                          // Datenbank-Zeile
        assertFalse(MoveLogic.canMove(pg(-3), own))                          // andere Saga-Instanz
        assertEquals(setOf(1L, 2L, 3L), MoveLogic.subtree(1, own))
        assertEquals(listOf(4L), MoveLogic.filter(t, "p4", "Unbenannt").map { it.id })
    }

    @Test
    fun moveTargetsInSharedArea() {
        val anna = Person(name = "Anna", username = "anna")
        val ben = Person(name = "Ben", username = "ben")
        val shared = listOf(
            pg(10, null, owner = anna, role = "edit", root = true), pg(11, 10, owner = anna, role = "edit"),
            pg(12, 10, owner = anna, role = "edit"), pg(13, 10, owner = anna, role = "view"),
            pg(20, null, owner = ben, role = "edit", root = true),
        )
        val ids = MoveLogic.targets(shared[1], emptyList(), shared).map { it.id }
        assertFalse(null in ids)                                             // nicht auf die oberste Ebene
        assertTrue(10L in ids && 12L in ids)
        assertFalse(13L in ids)                                             // nur lesen
        assertFalse(20L in ids)                                             // anderer Eigentuemer
        assertFalse(11L in ids)
        assertTrue(MoveLogic.targets(shared[0], emptyList(), shared).isEmpty())   // oberste geteilte Seite: nicht
        assertTrue(MoveLogic.targets(shared[3], emptyList(), shared).isEmpty())   // nur lesen: nicht
    }

    // ---------------------------------------------------------------- Papierkorb

    @Test
    fun trashStates() {
        var st = TrashState()
        assertTrue(st.loading)
        st = st.loaded(listOf(TrashItem(1, "a", deletedAt = 10.0), TrashItem(2, "b", deletedAt = 20.0, subpages = 2)))
        assertFalse(st.loading)
        assertEquals(listOf(2L, 1L), st.items!!.map { it.id })               // neueste zuerst
        st = st.working(2)
        assertTrue(2L in st.busy)
        st = st.idle(2)                                                      // Fehler: bleibt, nicht mehr beschaeftigt
        assertEquals(2, st.items!!.size)
        st = st.working(2).removed(2)
        assertEquals(listOf(1L), st.items!!.map { it.id })
        assertTrue(st.busy.isEmpty())
        assertTrue(st.emptied().empty)
        val f = TrashState().failed(UiText.Raw("x"))
        assertFalse(f.loading)
        assertNull(f.items)
        val now = 1_000_000.0
        assertEquals(30, TrashState.daysLeft(TrashItem(1, purgeAt = now + 29.5 * 86400), now))
        assertEquals(0, TrashState.daysLeft(TrashItem(1, purgeAt = now - 5), now))
        assertNull(TrashState.daysLeft(TrashItem(1), now))
    }

    // ---------------------------------------------------------------- Termine

    @Test
    fun eventPayloads() {
        val zone = ZoneId.of("Europe/Berlin")
        val d = LocalDate.of(2026, 10, 20)
        val all = EventForm(summary = " ", allday = true, startDate = d, endDate = d.plusDays(1)).payload("Termin", zone)
        assertEquals("Termin", all["summary"]!!.jsonPrimitive.content)
        assertEquals("2026-10-20", all["start"]!!.jsonPrimitive.content)
        assertEquals("2026-10-22", all["end"]!!.jsonPrimitive.content)       // Ende exklusiv
        val timed = EventForm(summary = "Zahnarzt", startDate = d, startTime = LocalTime.of(9, 30), endDate = d, endTime = LocalTime.of(10, 15)).payload("Termin", zone)
        assertEquals("2026-10-20T09:30:00+02:00", timed["start"]!!.jsonPrimitive.content)
        assertEquals("2026-10-20T10:15:00+02:00", timed["end"]!!.jsonPrimitive.content)
        val bad = EventForm(startDate = d, startTime = LocalTime.of(23, 30), endDate = d, endTime = LocalTime.of(8, 0)).normalized()
        assertEquals(d.plusDays(1), bad.endDate)
        assertEquals(LocalTime.of(0, 30), bad.endTime)
        val e = CalEvent(summary = "X", start = "2026-10-20T07:30:00+00:00", end = "2026-10-20T08:00:00+00:00", href = "/c/1.ics", account = 1, cal = "/c/")
        val f = EventForm.of(e, zone)
        assertEquals(LocalTime.of(9, 30), f.startTime)
        assertEquals("2026-10-20T09:30:00+02:00", f.payload("T", zone)["start"]!!.jsonPrimitive.content)
        val ad = EventForm.of(CalEvent(summary = "U", start = "2026-10-20", end = "2026-10-23", allday = true), zone)
        assertEquals(LocalDate.of(2026, 10, 22), ad.endDate)                 // inklusive angezeigt
        assertTrue(EventForm.readOnly(e.copy(series = true)))
        assertTrue(EventForm.readOnly(e.copy(writable = false)))
        assertFalse(EventForm.readOnly(e))
        val targets = EventForm.targets(listOf(CalAccount(1, "Mail", "mia", listOf(CalInfo("/a", "Privat"), CalInfo("/b", "Nur lesen", writable = false), CalInfo("/c", "Aus", show = false)))))
        assertEquals(listOf("Mail · Privat"), targets.map { it.name })
    }

    // ---------------------------------------------------------------- Lesezeichen, Einbetten

    @Test
    fun bookmarksAndEmbeds() {
        assertEquals(7L, BookmarkLogic.idOf(ServiceItem(name = "x", own = true, key = "bm7")))
        assertNull(BookmarkLogic.idOf(ServiceItem(name = "x", own = false, key = "svc")))
        assertTrue(BookmarkLogic.validUrl("example.org/x"))
        assertFalse(BookmarkLogic.validUrl("javascript:alert(1)"))
        assertFalse(BookmarkLogic.validUrl("https://user@host"))
        assertEquals("dQw4w9WgXcQ", BlockOps.youtubeId("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=1"))
        assertEquals("youtube", BlockDoc.str(BlockDoc.data(BlockOps.embed("https://youtu.be/dQw4w9WgXcQ")), "kind"))
        assertFalse(BlockDoc.data(BlockOps.embed("https://example.org")).containsKey("kind"))
        assertEquals("https://example.org", BlockOps.normalizeUrl("example.org"))
        assertNull(BlockOps.normalizeUrl("ftp://x"))
        assertNull(BlockOps.normalizeUrl("a b"))
        assertNull(BlockOps.normalizeUrl("javascript:alert(1)"))
        assertEquals("http://nas:8080/x", BlockOps.normalizeUrl("http://nas:8080/x"))
        val link = BlockOps.pageLink(5, "A<B", "📄")
        assertEquals("📄 <a href=\"/notes#5\">A&lt;B</a>", BlockDoc.str(BlockDoc.data(link), "text"))
        val att = BlockOps.attaches(buildJsonObject { put("url", "/files/x"); put("name", "x.pdf"); put("size", 3) })
        assertEquals("x.pdf", BlockDoc.str(BlockDoc.data(att), "title"))
        assertNotNull((BlockDoc.data(att)["file"] as JsonObject)["size"])
    }
}
