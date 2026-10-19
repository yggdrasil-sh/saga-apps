package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.data.ApiException
import io.github.yggdrasil_sh.saga.data.NoteCursor
import io.github.yggdrasil_sh.saga.data.NoteSync
import io.github.yggdrasil_sh.saga.data.PageMeta
import io.github.yggdrasil_sh.saga.data.TreeSync
import io.github.yggdrasil_sh.saga.util.UiText
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Leistung 2.2, Phase 2b: Seitenbaum per Abgleich (NoteSync.kt). Ein nachgebauter Server (Liste wie GET /api/notes:
 * Reihenfolge nach sort, bei Gleichstand id) erzeugt Zufallsfolgen von Aenderungen; nach jedem Abgleich muss die
 * eingearbeitete Liste GENAU der ganzen Liste entsprechen (wie DeltaTests.swift / test_delta.py).
 */
class NoteSyncTest {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false; isLenient = true }

    /** reproduzierbarer Zufall (gleiche Formel wie iOS/Linux) */
    class Rng(var s: ULong) {
        fun next(n: Int): Int {
            s = s * 6364136223846793005UL + 1442695040888963407UL
            return ((s shr 33) % maxOf(n, 1).toULong()).toInt()
        }
    }

    class Server {
        class N(var page: PageMeta, var sort: Double, var changed: Int)
        val notes = HashMap<Long, N>()
        val trash = HashMap<Long, N>()
        val removedAt = HashMap<Long, Int>()
        var seq = 0
        var nextId = 1L
        var clock = 100.0
        /** Anfragen (Pfade) */
        val log = ArrayList<String>()

        fun list(): List<PageMeta> = notes.values.sortedWith(compareBy<N>({ it.sort }, { it.page.id })).map { it.page }
        fun touch(id: Long, sort: Double? = null, f: (PageMeta) -> PageMeta = { it }) {
            val n = notes[id] ?: return
            seq++
            if (sort != null) n.sort = sort
            n.page = f(n.page)
            n.changed = seq
        }
        fun create(parent: Long?, kind: String = "page"): Long {
            val id = nextId++
            seq++
            clock += 1
            notes[id] = N(PageMeta(id = id, title = "Seite $id", icon = "", parentId = parent, kind = kind, updated = clock), clock, seq)
            removedAt.remove(id)
            return id
        }
        fun subtree(id: Long): List<Long> {
            val out = arrayListOf(id)
            var i = 0
            while (i < out.size) { val p = out[i]; out += notes.values.filter { it.page.parentId == p }.map { it.page.id }.sorted(); i++ }
            return out
        }
        fun trashTree(id: Long) { for (x in subtree(id)) notes.remove(x)?.let { seq++; trash[x] = it; removedAt[x] = seq } }
        fun restore(id: Long) {
            val n = trash.remove(id) ?: return
            if (n.page.parentId != null && notes[n.page.parentId] == null) n.page = n.page.copy(parentId = null)
            seq++; n.changed = seq; notes[id] = n; removedAt.remove(id)
        }
        fun purge(id: Long) { if (trash.remove(id) != null) { seq++; removedAt[id] = seq } }

        private fun obj(p: PageMeta, after: Any?): JsonObject {
            val o = json.encodeToJsonElement(PageMeta.serializer(), p) as JsonObject
            val m = LinkedHashMap(o)
            m["locked"] = JsonPrimitive(false)                    // Feld wie vom Server
            when (after) {
                Unit -> {}
                null -> m["after"] = JsonNull
                is Long -> m["after"] = JsonPrimitive(after)
            }
            return JsonObject(m)
        }

        fun response(since: Int?): String {
            val l = list()
            if (since == null) {
                return JsonObject(mapOf("cursor" to JsonPrimitive("c.$seq"), "full" to JsonPrimitive(true),
                    "removed" to JsonArray(emptyList()), "notes" to JsonArray(l.map { obj(it, Unit) }))).toString()
            }
            val out = l.mapIndexedNotNull { i, p -> if (notes[p.id]!!.changed > since) obj(p, if (i == 0) null else l[i - 1].id) else null }
            val rem = removedAt.filter { it.value > since && notes[it.key] == null }.keys.sorted()
            return JsonObject(mapOf("cursor" to JsonPrimitive("c.$seq"), "full" to JsonPrimitive(false),
                "removed" to JsonArray(rem.map { JsonPrimitive(it) }), "notes" to JsonArray(out))).toString()
        }

        /** wie ein Server ab 2.2 auf GET path antwortet */
        fun get(path: String): String {
            log += path
            val since = path.substringAfter("since=", "").takeIf { it.isNotEmpty() }
            return response(since?.removePrefix("c.")?.toInt())
        }

        fun randomStep(rng: Rng, step: Int) {
            val ids = notes.keys.sorted()
            val pick = if (ids.isEmpty()) null else ids[rng.next(ids.size)]
            when (rng.next(11)) {
                0 -> create(if (rng.next(3) == 0) null else pick)
                1 -> pick?.let { touch(it) { p -> p.copy(title = "T$step-${rng.next(99)}") } }
                2 -> pick?.let { id -> val to = ids[rng.next(ids.size)]; if (to !in subtree(id)) touch(id) { p -> p.copy(parentId = to) } }
                3 -> pick?.let { touch(it, sort = rng.next(300).toDouble()) }
                4 -> pick?.let { trashTree(it) }
                5 -> trash.keys.minOrNull()?.let { restore(it) }
                6 -> pick?.let { touch(it) { p -> p.copy(archived = !p.archived) } }
                7 -> { val db = create(pick, "database"); repeat(1 + rng.next(3)) { create(db) } }
                8 -> {
                    val db = notes.values.firstOrNull { it.page.isDatabase }?.page?.id
                    notes.values.filter { db != null && it.page.parentId == db }.map { it.page.id }.minOrNull()?.let {
                        touch(it) { p -> p.copy(title = "Eintrag ${rng.next(50)}", tags = listOf("x")) }
                    }
                }
                9 -> trash.keys.maxOrNull()?.let { purge(it) }
                else -> pick?.let { touch(it) { p -> p.copy(shared = true, unread = !p.unread, pinned = true, icon = "⭐") } }
            }
        }

        companion object {
            private val json = Json { explicitNulls = true; encodeDefaults = true }
        }
    }

    private fun seqOf(c: String?): Int? = c?.removePrefix("c.")?.toInt()

    @Test
    fun zufallsfolgen_gleich_ganzem_baum() {
        val failed = ArrayList<Int>()
        for (run in 0 until 60) {
            val rng = Rng((run * 7919 + 17).toULong())
            val srv = Server()
            repeat(12) { val ids = srv.notes.keys.sorted(); srv.create(if (ids.isEmpty() || rng.next(3) == 0) null else ids[rng.next(ids.size)]) }
            val first = NoteSync.outcome(NoteSync.parse(json, srv.response(null)), emptyList(), false)
            assertTrue(first.full && !first.legacy)
            var app = first.pages!!
            var cursor = first.cursor
            val history = arrayListOf(cursor to app)
            var ok = app == srv.list()
            for (step in 0 until 40) {
                repeat(1 + rng.next(4)) { srv.randomStep(rng, step) }
                val o = NoteSync.outcome(NoteSync.parse(json, srv.response(seqOf(cursor))), app, true)
                assertTrue("Lauf $run/$step", !o.full && !o.refetch && !o.legacy)
                o.pages?.let { app = it }
                cursor = o.cursor
                if (app != srv.list()) ok = false
                history += cursor to app
                if (step % 7 == 6) {                              // Neustart mit aelterem Stand auf der Platte
                    val (oc, ol) = history[rng.next(history.size)]
                    val o2 = NoteSync.outcome(NoteSync.parse(json, srv.response(seqOf(oc))), ol, true)
                    if ((o2.pages ?: ol) != srv.list() || o2.refetch) ok = false
                    val again = NoteSync.outcome(NoteSync.parse(json, srv.response(seqOf(oc))), app, true)
                    if ((again.pages ?: app) != srv.list()) ok = false
                }
            }
            if (!ok) failed += run
        }
        assertEquals(emptyList<Int>(), failed)
    }

    private fun pg(id: Long, t: String = "", parent: Long? = null) = PageMeta(id = id, title = t, parentId = parent, updated = 1.0)
    private fun dn(p: PageMeta, after: NoteSync.After) = NoteSync.DeltaNote(p, after)

    @Test
    fun einzelfaelle() {
        val base = listOf(pg(1, "A"), pg(2, "B"), pg(3, "C"))
        assertEquals(base to true, NoteSync.merge(base, emptyList(), emptyList()))
        var m = NoteSync.merge(base, listOf(dn(pg(4, "D"), NoteSync.After.First)), listOf(2))
        assertTrue(m.second); assertEquals(listOf(4L, 1, 3), m.first.map { it.id })
        m = NoteSync.merge(base, listOf(dn(pg(5), NoteSync.After.Id(3)), dn(pg(6), NoteSync.After.Id(5))), listOf(99))
        assertTrue(m.second); assertEquals(listOf(1L, 2, 3, 5, 6), m.first.map { it.id })
        m = NoteSync.merge(base, listOf(dn(pg(1, "A2"), NoteSync.After.Id(3))), emptyList())
        assertTrue(m.second); assertEquals(listOf(2L, 3, 1), m.first.map { it.id }); assertEquals("A2", m.first.last().title)
        m = NoteSync.merge(base, listOf(dn(pg(7), NoteSync.After.Id(42))), emptyList())
        assertFalse(m.second); assertEquals(listOf(1L, 2, 3, 7), m.first.map { it.id })
        val o = NoteSync.outcome(NoteSync.Response.Delta("x", false, listOf(dn(pg(7), NoteSync.After.Id(42))), emptyList()), base, true)
        assertTrue(o.refetch); assertNull(o.cursor); assertEquals(4, o.pages!!.size)
        m = NoteSync.merge(base, listOf(dn(pg(8), NoteSync.After.Missing)), emptyList())
        assertFalse(m.second); assertEquals(8L, m.first.last().id)
        val chain = (100L until 50_100L).map { dn(pg(it), NoteSync.After.Id(if (it == 100L) 3 else it - 1)) }
        m = NoteSync.merge(base, chain, emptyList())
        assertTrue(m.second); assertEquals(50_003, m.first.size); assertEquals(100L, m.first[3].id); assertEquals(50_099L, m.first.last().id)
    }

    @Test
    fun antworten_lesen() {
        val base = listOf(pg(1), pg(2), pg(3))
        val arr = """  [{"id":9,"title":"","icon":"","parent_id":3,"updated":5,"tags":[],"pinned":false,"template":false,"kind":"page","archived":false,"locked":false,"shared":false,"unread":false}]"""
        val ra = NoteSync.parse(json, arr)
        assertTrue(ra is NoteSync.Response.Legacy)
        val oa = NoteSync.outcome(ra, base, true)
        assertTrue(oa.legacy && oa.full); assertNull(oa.cursor); assertEquals(listOf(9L), oa.pages!!.map { it.id })
        val of = NoteSync.outcome(NoteSync.parse(json, """{"cursor":"k1","full":true,"removed":[],"notes":[{"id":5,"title":"X","parent_id":null,"updated":1}]}"""), base, true)
        assertTrue(of.full && !of.legacy); assertEquals(listOf(5L), of.pages!!.map { it.id }); assertEquals("k1", of.cursor)
        assertEquals(listOf(5L), NoteSync.outcome(NoteSync.parse(json, """{"cursor":"k2","notes":[{"id":5,"title":"X","updated":1}]}"""), base, false).pages!!.map { it.id })
        val on = NoteSync.outcome(NoteSync.parse(json, """{"cursor":null,"full":false,"removed":[2],"notes":[]}"""), base, true)
        assertNull(on.cursor); assertEquals(listOf(1L, 3), on.pages!!.map { it.id })
        assertEquals("88412", (NoteSync.parse(json, """{"cursor":88412,"full":false,"notes":[],"removed":[]}""") as NoteSync.Response.Delta).cursor)
        assertNull(NoteSync.outcome(NoteSync.parse(json, """{"cursor":"k3","full":false,"notes":[],"removed":[]}"""), base, true).pages)
        val d = NoteSync.parse(json, """{"cursor":"c","full":false,"removed":[],"notes":[{"id":1,"title":"a","after":null},{"id":2,"title":"b","after":1},{"id":3,"title":"c"}]}""") as NoteSync.Response.Delta
        assertEquals(listOf(NoteSync.After.First, NoteSync.After.Id(1), NoteSync.After.Missing), d.notes.map { it.after })
        assertFalse(NoteSync.encodeList(json, d.notes.map { it.page }).contains("after"))
        // Zwischenspeicher: Liste kommt unveraendert zurueck (Format von GET api/notes)
        val rich = pg(10, "Reich", parent = 1).copy(icon = "📁", tags = listOf("a", "b"), shared = true, unread = true, archived = true, locked = true, pinned = true, template = true, kind = "database")
        val lst = base + rich
        assertEquals(lst, json.decodeFromString<List<PageMeta>>(NoteSync.encodeList(json, lst)))
        try {
            NoteSync.parse(json, """{"error":"x"}""")
            throw AssertionError("Objekt ohne notes muss scheitern")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun anfrage_und_stand() {
        assertEquals("api/notes?delta=1", NoteSync.path(null))
        assertEquals("api/notes?delta=1", NoteSync.path(""))
        assertEquals("api/notes?since=3f9c0a1b2c4d.88412", NoteSync.path("3f9c0a1b2c4d.88412"))
        assertEquals("api/notes?since=a%2Bb%26c%3Dd", NoteSync.path("a+b&c=d"))
        val now = 2_000_000_000L
        val c = NoteCursor("c.5", "https://a.example", "anna", now - 60_000)
        assertTrue(c.usable("https://a.example", "anna", now))
        assertFalse(c.usable("https://a.example", "bernd", now))
        assertFalse(c.usable("https://b.example", "anna", now))
        assertFalse(c.usable("https://a.example", "", now))
        assertFalse(c.usable(null, null, now))
        assertFalse(c.usable("https://a.example", "anna", now + NoteSync.FULL_EVERY_MS))
        assertFalse(c.copy(value = "").usable("https://a.example", "anna", now))
        assertEquals(c, json.decodeFromString(NoteCursor.serializer(), json.encodeToString(NoteCursor.serializer(), c)))
    }

    // --- TreeSync: Anfrage, Stand, Zwischenspeicher -------------------------------------------------------------------
    private fun tmp(): File = Files.createTempDirectory("notesync").toFile()

    @Test
    fun treesync_abgleich_und_zwischenspeicher() = runBlocking {
        val dir = tmp()
        val srv = Server()
        repeat(8) { srv.create(if (it % 3 == 0) null else it.toLong()) }
        val ts = TreeSync(json, dir) { srv.get(it) }
        var l = ts.load("https://a.example", "anna")
        assertEquals("api/notes?delta=1", srv.log.last())
        assertEquals(srv.list(), l)
        assertEquals("c.${srv.seq}", ts.cursor!!.value)
        val rng = Rng(5UL)
        for (step in 0 until 25) {
            repeat(1 + rng.next(3)) { srv.randomStep(rng, step) }
            l = ts.load("https://a.example", "anna")
            assertTrue(srv.log.last().startsWith("api/notes?since=c."))
            assertEquals(srv.list(), l)
            assertEquals(srv.list(), json.decodeFromString<List<PageMeta>>(File(dir, "notes.json").readText()))
            assertEquals(ts.cursor, json.decodeFromString(NoteCursor.serializer(), File(dir, "cursor.json").readText()))
        }
        // Neustart: Zwischenspeicher nur fuer denselben Server und dasselbe Konto
        assertNull(TreeSync(json, dir) { srv.get(it) }.restore("https://a.example", "bernd"))
        assertNull(TreeSync(json, dir) { srv.get(it) }.restore("https://b.example", "anna"))
        val ts2 = TreeSync(json, dir) { srv.get(it) }
        assertEquals(srv.list(), ts2.restore("https://a.example", "anna"))
        srv.create(null)
        val before = srv.log.size
        assertEquals(srv.list(), ts2.load("https://a.example", "anna"))
        assertEquals(before + 1, srv.log.size)
        assertTrue(srv.log.last().startsWith("api/notes?since="))
        // anderes Konto: Stand nicht benutzen
        ts2.load("https://a.example", "bernd")
        assertEquals("api/notes?delta=1", srv.log.last())
        assertEquals("bernd", ts2.cursor!!.account)
        // verwerfen / leeren
        ts2.dropCursor()
        assertNull(ts2.cursor); assertFalse(File(dir, "cursor.json").exists())
        ts2.load("https://a.example", "bernd")
        assertEquals("api/notes?delta=1", srv.log.last())
        ts2.clear()
        assertNull(ts2.cursor); assertTrue(ts2.base.isEmpty()); assertFalse(File(dir, "notes.json").exists())
        dir.deleteRecursively()
        Unit
    }

    @Test
    fun treesync_alter_server_full_und_abweichung() = runBlocking {
        val dir = tmp()
        // aelterer Server: immer die Liste (Array) -> kein Stand, kein since, kein Zwischenspeicher
        val legacy = """[{"id":1,"title":"A"},{"id":2,"title":"B","parent_id":1}]"""
        val paths = ArrayList<String>()
        val old = TreeSync(json, dir) { paths += it; legacy }
        assertEquals(listOf(1L, 2), old.load("https://a.example", "anna").map { it.id })
        assertEquals(listOf(1L, 2), old.load("https://a.example", "anna").map { it.id })
        assertEquals(listOf("api/notes?delta=1", "api/notes?delta=1"), paths)
        assertNull(old.cursor); assertFalse(File(dir, "cursor.json").exists()); assertFalse(File(dir, "notes.json").exists())

        val srv = Server()
        repeat(6) { srv.create(null) }
        var mode = "normal"
        val ts = TreeSync(json, dir) { p ->
            when {
                mode == "reject" && p.contains("since=") -> { srv.log += p; throw ApiException(400, UiText.Raw("bad cursor")) }
                mode == "full" -> { srv.log += p; srv.response(null) }
                else -> srv.get(p)
            }
        }
        ts.load("https://a.example", "anna")
        // full: true auf since: ersetzen
        mode = "full"
        srv.create(null)
        assertEquals(srv.list(), ts.load("https://a.example", "anna"))
        // abgelehnter Stand: gleich ganz holen
        mode = "reject"
        srv.create(null)
        val n = srv.log.size
        assertEquals(srv.list(), ts.load("https://a.example", "anna"))
        assertTrue(srv.log[n].contains("since=")); assertEquals("api/notes?delta=1", srv.log.last())
        // Grundlage weicht ab (unbekanntes after): in derselben Abfrage ganz holen
        mode = "normal"
        val ts3 = TreeSync(json, null) { srv.get(it) }
        ts3.load("https://a.example", "anna")
        val c = ts3.cursor!!
        val bad = TreeSync(json, null) { srv.get(it) }
        // Stand ohne passende Grundlage (leere Liste) -> after unbekannt -> refetch
        val f = TreeSync::class.java.getDeclaredField("cursor"); f.isAccessible = true; f.set(bad, c)
        val id = srv.create(srv.list().first().id)
        srv.touch(id, sort = 101.5)                       // steht hinter Seite 1 (in leerer Grundlage unbekannt)
        val before = srv.log.size
        assertEquals(srv.list(), bad.load("https://a.example", "anna"))
        assertEquals(2, srv.log.size - before)
        assertTrue(srv.log[before].contains("since=")); assertEquals("api/notes?delta=1", srv.log.last())
        assertNotNull(bad.cursor)
        dir.deleteRecursively()
        Unit
    }

    @Test
    fun treesync_verworfen_waehrend_des_ladens() = runBlocking {
        val srv = Server()
        repeat(3) { srv.create(null) }
        lateinit var ts: TreeSync
        var dropNow = false
        ts = TreeSync(json, null) { p -> val r = srv.get(p); if (dropNow) ts.dropCursor(); r }
        ts.load("https://a.example", "anna")
        dropNow = true
        srv.create(null)
        ts.load("https://a.example", "anna")                       // Abmelden/reset waehrend der Anfrage
        assertNull(ts.cursor)                                       // nichts uebernommen
        dropNow = false
        // Server/Konto waehrend der Anfrage gewechselt: nicht uebernehmen
        ts.load("https://a.example", "anna")
        val c = ts.cursor
        srv.create(null)
        ts.load("https://a.example", "anna") { "https://b.example" to "anna" }
        assertEquals(c, ts.cursor)
        Unit
    }
}
