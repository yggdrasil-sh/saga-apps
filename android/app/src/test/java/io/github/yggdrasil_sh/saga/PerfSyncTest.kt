package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.data.PageList
import io.github.yggdrasil_sh.saga.data.PageMeta
import io.github.yggdrasil_sh.saga.data.PageTree
import io.github.yggdrasil_sh.saga.data.RefreshPolicy
import io.github.yggdrasil_sh.saga.data.TreeEvents
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Schnelle Gewinne (2.2): Takt, Seitenliste lokal pflegen statt neu laden */
class PerfSyncTest {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false; isLenient = true }

    @Test
    fun takt() {
        // ohne Live-Kanal wie bisher jede Minute (auch wenn schon notes.tree kam)
        assertFalse(RefreshPolicy.due(50_000, liveConnected = false, treeSeen = false))
        assertTrue(RefreshPolicy.due(60_000, liveConnected = false, treeSeen = false))
        assertTrue(RefreshPolicy.due(60_000, liveConnected = false, treeSeen = true))
        // Live-Kanal verbunden, aber noch kein notes.tree empfangen (z. B. testing-Server meldet 2.2.0, schickt es aber
        // nicht): weiter jede Minute - die Serverversion entscheidet nicht mehr
        assertTrue(RefreshPolicy.due(60_000, liveConnected = true, treeSeen = false))
        // erst nach dem ersten notes.tree seltener (5 min)
        assertFalse(RefreshPolicy.due(60_000, liveConnected = true, treeSeen = true))
        assertFalse(RefreshPolicy.due(290_000, liveConnected = true, treeSeen = true))
        assertTrue(RefreshPolicy.due(300_000, liveConnected = true, treeSeen = true))
        // nach Wiederverbinden: sofort, wenn der letzte Abgleich aelter als der 60-s-Takt ist
        assertFalse(RefreshPolicy.staleAfterReconnect(20_000))
        assertTrue(RefreshPolicy.staleAfterReconnect(120_000))
    }

    @Test
    fun kleine_listen_jede_minute() {
        // Mit mir geteilt, Glocke, Chats, Heute: unabhaengig vom Seitenbaum-Takt jede Minute
        assertFalse(RefreshPolicy.lightDue(30_000))
        assertTrue(RefreshPolicy.lightDue(60_000))
        // Simulation: Live-Kanal + notes.tree, 10-min-Fenster im 10-s-Takt -> 2 volle, 8 kleine Abgleiche
        var now = 0L
        var lastFull = 0L
        var lastLight = 0L
        var full = 0
        var light = 0
        while (now < 600_000L) {
            now += RefreshPolicy.TICK_MS
            if (RefreshPolicy.due(now - lastFull, liveConnected = true, treeSeen = true)) {
                full++; lastFull = now
            } else if (RefreshPolicy.lightDue(minOf(now - lastFull, now - lastLight))) {
                light++; lastLight = now
            }
        }
        assertEquals(2, full)
        assertEquals(8, light)
    }

    @Test
    fun tree_gesehen_vergessen() {
        // Flag in LiveCenter: erst mit notes.tree an, bei Abmelden/Kontowechsel/Serverwechsel (clear) wieder aus
        val src = java.io.File("src/main/java/io/github/yggdrasil_sh/saga/live/LiveCenter.kt").readText()
        val clear = src.substringAfter("fun clear()").substringBefore("\n    }")
        assertTrue(clear.contains("treeSeen = false"))
        val tree = src.substringAfter("TreeEvents.EVENT ->").substringBefore("}")
        assertTrue(tree.contains("treeSeen = true"))
        // Taktentscheidung nutzt das Flag, nicht die Serverversion
        val repo = java.io.File("src/main/java/io/github/yggdrasil_sh/saga/data/SagaRepository.kt").readText()
        assertTrue(repo.contains("live.treeSeen"))
        assertFalse(repo.contains("MIN_SERVER"))
        // kleiner Abgleich im Minutentakt: geteilt, andere Server, Glocke, Chats, Heute - ohne Seitenliste
        val light = repo.substringAfter("private suspend fun refreshLight()").substringBefore("\n    }")
        for (call in listOf("loadShared()", "loadFedShared()", "live.loadNotificationCount()", "live.loadChatUnread()", "loadTasks()", "loadTodayEvents()"))
            assertTrue(call, light.contains(call))
        assertFalse(light.contains("loadPages()"))
        val poll = repo.substringAfter("suspend fun pollWhileForeground()").substringBefore("\n    }")
        assertTrue(poll.contains("refreshLight()"))
    }

    private fun p(id: Long, parent: Long? = null, archived: Boolean = false, kind: String? = null) =
        PageMeta(id = id, title = "S$id", parentId = parent, archived = archived, kind = kind)

    @Test
    fun liste_aufteilen() {
        val s = PageList.split(listOf(p(1), p(2, archived = true), p(3, 1)))
        assertEquals(listOf(1L, 3L), s.active.map { it.id })
        assertEquals(listOf(2L), s.archived.map { it.id })
        assertEquals(setOf(1L, 2L, 3L), s.ids)
    }

    @Test
    fun teilbaum_linear_auch_ueber_archivierte() {
        // 1 > 2 > (3 archiviert) > 4 ; 5 daneben
        val all = listOf(p(1), p(2, 1), p(3, 2, archived = true), p(4, 3), p(5))
        assertEquals(setOf(1L, 2L, 3L, 4L), PageList.subtree(all, 1))
        assertEquals(setOf(5L), PageList.subtree(all, 5))
        // Zyklus in kaputten Daten: endet trotzdem
        assertEquals(setOf(7L, 8L), PageList.subtree(listOf(p(7, 8), p(8, 7)), 7))
        // grosse Liste: linear
        val big = (1L..50_000L).map { p(it, if (it == 1L) null else it / 2) }
        assertEquals(50_000, PageList.subtree(big, 1).size)
    }

    @Test
    fun eintrag_aus_seite_wie_in_der_liste() {
        // GET api/notes/{id} einer eigenen Seite (gekuerzt) -> dieselben Felder wie ein Eintrag aus GET api/notes
        val detail = """{"id":42,"title":"Neu","icon":"📄","parent_id":7,"updated":1760000000.5,"tags":["a"],"pinned":false,
            "template":false,"kind":"page","published":false,"public_url":null,"body":"{\"blocks\":[]}","format":"blocks",
            "props":{},"cover":"","cover_pos":50.0,"created":1760000000.5,"wide":false,"rev":1,"archived":false,"locked":false,
            "role":"owner","owner":{"name":"A","username":"a","me":true},"shared":false,"comment_counts":{},
            "comments_open":0,"comments_unread":0,"comments_resolved":0,"updated_by":null,"created_by":null}"""
        val m = PageList.metaFromDetail(json, detail)
        val fromList = json.decodeFromString<List<PageMeta>>(
            """[{"id":42,"title":"Neu","icon":"📄","parent_id":7,"updated":1760000000.5,"tags":["a"],"pinned":false,
            "template":false,"kind":"page","archived":false,"locked":false,"shared":false,"unread":false}]""").single()
        assertEquals(fromList, m)
        assertNull(m.owner)
        assertNull(m.role)
        // ungelesene Kommentare -> Punkt wie in der Liste
        assertTrue(PageList.metaFromDetail(json, detail.replace("\"comments_unread\":0", "\"comments_unread\":2")).unread)
    }

    private fun ev(s: String) = TreeEvents.parse(json.parseToJsonElement(s) as kotlinx.serialization.json.JsonObject)

    @Test
    fun live_notes_tree() {
        // Vertrag: {ids:[int], op, ops:[...], full:bool}; tolerant: Zahlen auch als Text/Gleitkomma, Zusatzfelder egal
        val d = ev("""{"ids":[5,"7",9.0,"x"],"op":"created","ops":["created"],"full":false,"by":{"name":"A"},"neu":1}""")
        assertEquals(listOf(5L, 7L, 9L), d.ids)
        assertEquals(setOf("created"), d.ops)
        assertFalse(d.full)
        assertEquals(TreeEvents.Plan.Targeted(setOf(5L, 7L, 9L)), TreeEvents.plan(d.ids, d.ops))
        // fehlende Felder -> unbekannt -> voll
        val leer = ev("{}")
        assertEquals(emptyList<Long>(), leer.ids)
        assertEquals(TreeEvents.Plan.Full, TreeEvents.plan(leer.ids, leer.ops))
        // wenige angelegte/geaenderte Seiten: gezielt; sonst voller Abgleich
        assertEquals(TreeEvents.Plan.Targeted(setOf(5L, 7L)), TreeEvents.plan(listOf(5L, 7L, 5L), listOf("created", "updated")))
        for (op in listOf("moved", "trashed", "restored", "archived", "deleted", "shared", "mixed", "", "irgendwas")) {
            val e = ev("""{"ids":[5],"op":"$op","ops":["$op"],"full":false}""")
            assertEquals(op, TreeEvents.Plan.Full, TreeEvents.plan(e.ids, e.ops))
        }
        assertEquals(TreeEvents.Plan.Full, TreeEvents.plan(emptyList(), listOf("updated")))
        assertEquals(TreeEvents.Plan.Full, TreeEvents.plan((1L..21L).toList(), listOf("updated")))
    }

    @Test
    fun live_notes_tree_full_und_mixed() {
        // full: true -> immer voller Abgleich, auch mit "harmloser" Art (und leeren ids)
        val f = ev("""{"ids":[],"op":"updated","ops":["updated"],"full":true}""")
        assertTrue(f.full)
        assertEquals(TreeEvents.Plan.Full, TreeEvents.plan(f.ids, f.ops))
        val f2 = ev("""{"ids":[1,2],"op":"created","ops":["created"],"full":true}""")
        assertEquals(TreeEvents.Plan.Full, TreeEvents.plan(f2.ids, f2.ops))
        // full: false ausdruecklich -> gezielt moeglich; full als Text "true" ist kein Bool -> nicht voll
        assertFalse(ev("""{"ids":[1],"op":"created","ops":["created"],"full":"ja"}""").full)
        // mixed: es zaehlen die gesammelten Arten (ops)
        val m = ev("""{"ids":[3,4],"op":"mixed","ops":["created","updated"],"full":false}""")
        assertEquals(setOf("created", "updated"), m.ops)
        assertEquals(TreeEvents.Plan.Targeted(setOf(3L, 4L)), TreeEvents.plan(m.ids, m.ops))
        val m2 = ev("""{"ids":[3],"op":"mixed","ops":["moved","updated"],"full":false}""")
        assertEquals(TreeEvents.Plan.Full, TreeEvents.plan(m2.ids, m2.ops))
        // mixed ohne ops -> unbekannt -> voll
        assertEquals(TreeEvents.Plan.Full, ev("""{"ids":[3],"op":"mixed"}""").let { TreeEvents.plan(it.ids, it.ops) })
        // aeltere Form ohne ops: op zaehlt
        assertEquals(TreeEvents.Plan.Targeted(setOf(3L)), ev("""{"ids":[3],"op":"updated"}""").let { TreeEvents.plan(it.ids, it.ops) })
        // gesammelt (entprellt): ein full im Buendel macht alles voll
        assertEquals(TreeEvents.Plan.Full, TreeEvents.plan(listOf(1L, 2L), listOf("created", TreeEvents.FULL)))
    }

    @Test
    fun einpflegen_stelle_bleibt() {
        val l = listOf(p(1), p(2), p(3))
        assertEquals(listOf(1L, 2L, 3L), PageList.upsert(l, p(2).copy(title = "neu")).map { it.id })
        assertEquals("neu", PageList.upsert(l, p(2).copy(title = "neu"))[1].title)
        assertEquals(listOf(1L, 2L, 3L, 4L), PageList.upsert(l, p(4)).map { it.id })
    }

    @Test
    fun baum_mit_neuer_seite_wie_nach_neuladen() {
        val before = listOf(p(1), p(2, 1), p(3))
        val local = before + p(4, 1)                     // lokal angehaengt (neue Seiten: sort = Anlegezeit -> am Ende)
        val t = PageTree(local)
        assertEquals(listOf(2L, 4L), t.children(1).map { it.id })
        assertEquals(listOf(1L, 3L), t.children(null).map { it.id })
        // Datenbank-Zeilen erscheinen nicht im Baum (wie bisher)
        val db = PageTree(listOf(p(9, kind = "database"), p(10, 9)))
        assertTrue(db.children(9).isEmpty())
    }
}
