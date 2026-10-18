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
        // ohne Live-Kanal wie bisher jede Minute
        assertFalse(RefreshPolicy.due(50_000, liveConnected = false))
        assertTrue(RefreshPolicy.due(60_000, liveConnected = false))
        // mit Live-Kanal seltener
        assertFalse(RefreshPolicy.due(60_000, liveConnected = true))
        assertFalse(RefreshPolicy.due(290_000, liveConnected = true))
        assertTrue(RefreshPolicy.due(300_000, liveConnected = true))
        // nach Wiederverbinden: sofort, wenn der letzte Abgleich aelter als der 60-s-Takt ist
        assertFalse(RefreshPolicy.staleAfterReconnect(20_000))
        assertTrue(RefreshPolicy.staleAfterReconnect(120_000))
        // seltener Takt nur mit Server, der notes.tree meldet (ab 2.2)
        assertFalse(io.github.yggdrasil_sh.saga.util.Versions.isLess("2.2.0", TreeEvents.MIN_SERVER))
        assertTrue(io.github.yggdrasil_sh.saga.util.Versions.isLess("2.1.0", TreeEvents.MIN_SERVER))
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

    @Test
    fun live_notes_tree() {
        // tolerant: Zahlen auch als Text/Gleitkomma, Zusatzfelder egal, fehlendes op -> ""
        val d = json.parseToJsonElement("""{"ids":[5,"7",9.0,"x"],"op":"created","by":{"name":"A"},"neu":1}""")
            as kotlinx.serialization.json.JsonObject
        assertEquals(listOf(5L, 7L, 9L) to "created", TreeEvents.parse(d))
        assertEquals(emptyList<Long>() to "", TreeEvents.parse(json.parseToJsonElement("{}") as kotlinx.serialization.json.JsonObject))
        // wenige angelegte/geaenderte Seiten: gezielt; sonst voller Abgleich
        assertEquals(TreeEvents.Plan.Targeted(setOf(5L, 7L)), TreeEvents.plan(listOf(5L, 7L, 5L), listOf("created", "changed")))
        for (op in listOf("moved", "trashed", "archived", "restored", "", "irgendwas"))
            assertEquals(op, TreeEvents.Plan.Full, TreeEvents.plan(listOf(5L), listOf("created", op)))
        assertEquals(TreeEvents.Plan.Full, TreeEvents.plan(emptyList(), listOf("changed")))
        assertEquals(TreeEvents.Plan.Full, TreeEvents.plan((1L..21L).toList(), listOf("changed")))
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
