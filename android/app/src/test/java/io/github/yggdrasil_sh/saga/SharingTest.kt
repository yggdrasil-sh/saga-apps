package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.data.DocText
import io.github.yggdrasil_sh.saga.data.DraftSync
import io.github.yggdrasil_sh.saga.data.Masking
import io.github.yggdrasil_sh.saga.data.PageCaps
import io.github.yggdrasil_sh.saga.data.PageMeta
import io.github.yggdrasil_sh.saga.data.Person
import io.github.yggdrasil_sh.saga.data.Role
import io.github.yggdrasil_sh.saga.data.SharedTree
import io.github.yggdrasil_sh.saga.data.conflictAuthor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SharingTest {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }

    @Test
    fun roles() {
        assertEquals(Role.OWNER, Role.of(null))           // Server vor 1.3
        assertEquals(Role.EDIT, Role.of("edit"))
        assertEquals(Role.VIEW, Role.of("irgendwas"))     // unbekannt: vorsichtshalber nur lesen
        assertTrue(Role.COMMENT.canComment && !Role.COMMENT.canEdit)
        assertTrue(!Role.VIEW.canComment && !Role.VIEW.canEdit)
        assertTrue(Role.EDIT.canEdit && !Role.EDIT.isOwner)
    }

    @Test
    fun ownerOnlyActionsAndBlocks() {
        for (r in listOf(Role.VIEW, Role.COMMENT, Role.EDIT)) {
            val c = PageCaps(r, shareSupported = true)
            assertTrue(c.foreign)
            assertFalse("$r", c.canFavorite || c.canLock || c.canPublish || c.canShare || c.canArchive || c.canTrash)
            assertTrue(c.ownerOnlyBlock("tasks") && c.ownerOnlyBlock("calendar") && c.ownerOnlyBlock("immich"))
            assertFalse(c.ownerOnlyBlock("paragraph"))
            assertEquals(r != Role.VIEW, c.canComment)
            assertEquals(r == Role.EDIT, c.canEditContent)
        }
        val own = PageCaps(Role.OWNER, shareSupported = true)
        assertTrue(own.canFavorite && own.canLock && own.canPublish && own.canShare && own.canArchive && own.canTrash && own.canComment)
        assertFalse(own.ownerOnlyBlock("tasks"))
        val old = PageCaps(Role.OWNER, shareSupported = false)    // aelterer Server: Teilen/Kommentare ausblenden
        assertFalse(old.canShare || old.canComment || old.showComments)
        assertTrue(old.canPublish)
    }

    @Test
    fun maskedLinks() {
        assertTrue(Masking.isMasked("/notes#12", "Seite ohne Zugriff"))
        assertTrue(Masking.isMasked("/notes#12", "Página sin acceso"))
        assertFalse(Masking.isMasked("/notes#12", "Meilensteine"))
        assertFalse(Masking.isMasked("https://x.org", "Page without access"))
        assertFalse(Masking.isMasked(null, "Seite ohne Zugriff"))
        val html = """Siehe <a href="/notes#5">Meilensteine</a>, Notizen in <a href="/notes#6">Page without access</a>."""
        val shown = Masking.forDisplay(html)
        assertTrue(shown.contains("🔒 <a href=\"/notes#6\">Page without access</a>"))
        assertTrue(shown.contains("Siehe <a href=\"/notes#5\">Meilensteine</a>"))
        assertEquals("ohne Verweis", Masking.forDisplay("ohne Verweis"))
    }

    @Test
    fun conflictAuthorFromServerBody() {
        assertEquals("Bert", conflictAuthor("""{"error":"conflict","rev":3,"by":"Bert"}"""))
        assertNull(conflictAuthor("""{"error":"conflict","rev":3,"by":null}"""))
        assertNull(conflictAuthor("""{"error":"conflict"}"""))
        assertNull(conflictAuthor("kaputt"))
    }

    @Test
    fun docTextForCopying() {
        val d = json.parseToJsonElement(
            """{"blocks":[{"id":"a","type":"header","data":{"text":"Ziele","level":2}},
               {"id":"b","type":"paragraph","data":{"text":"Wir <b>planen</b> &amp; bauen"}},
               {"id":"c","type":"list","data":{"style":"checklist","items":[{"content":"Hochbeet","items":[{"content":"Holz"}]},{"content":"Tomaten"}]}},
               {"id":"d","type":"toggle","data":{"title":"Mehr","blocks":[{"id":"e","type":"paragraph","data":{"text":"Innen"}}]}},
               {"id":"f","type":"columns","data":{"cols":[{"blocks":[{"id":"g","type":"paragraph","data":{"text":"Links"}}]}]}},
               {"id":"h","type":"tasks","data":{}}]}""",
        ) as JsonObject
        assertEquals("Plan\n\nZiele\n\nWir planen & bauen\n\n• Hochbeet\n  • Holz\n• Tomaten\n\nMehr\n\nInnen\n\nLinks", DocText.plain("Plan", d))
        assertEquals("", DocText.plain("", json.parseToJsonElement("""{"blocks":[]}""") as JsonObject))
    }

    @Test
    fun sharedWithMeTree() {
        val o = Person("Anna", "anna")
        val list = listOf(
            PageMeta(1, "Garten", root = true, owner = o, role = "edit"),
            PageMeta(2, "Meilensteine", parentId = 1, owner = o),
            PageMeta(3, "Aufgabenliste", parentId = 1, kind = "database", owner = o),
            PageMeta(4, "Eintrag", parentId = 3, owner = o),
            PageMeta(5, "Rezepte", root = true, owner = Person("Cora", "cora")),
        )
        val closed = SharedTree.rows(list, emptySet())
        assertEquals(listOf(1L, 5L), closed.map { it.page.id })
        assertTrue(closed[0].hasChildren)
        assertFalse(closed[1].hasChildren)
        val open = SharedTree.rows(list, setOf(1, 3))
        assertEquals(listOf(1L, 2L, 3L, 5L), open.map { it.page.id })     // Datenbank-Eintraege nicht im Baum
        assertEquals(1, open[1].depth)
        assertTrue(list[0].foreign)
    }

    @Test
    fun sharedListParsesOwnerAndUnread() {
        val s = """[{"id":7,"title":"Garten","icon":"🌱","parent_id":null,"kind":"page","updated":1.0,"tags":[],"role":"comment",
            "root":true,"shared":true,"pinned":false,"owner":{"name":"Anna Teilt","username":"anna","me":false},"unread":true}]"""
        val p = json.decodeFromString<List<PageMeta>>(s)[0]
        assertEquals("Anna Teilt", p.owner?.name)
        assertTrue(p.root && p.unread && p.shared && p.foreign)
        assertEquals("comment", p.role)
        val own = json.decodeFromString<List<PageMeta>>("""[{"id":1,"title":"x","shared":true,"unread":false}]""")[0]
        assertFalse(own.foreign)
        assertTrue(own.shared)
    }

    /** Pruefer: Entwuerfe fremder Seiten laedt der Hintergrund-Abgleich nie hoch (Freigabe evtl. entzogen) */
    @Test
    fun backgroundSyncOnlyOwnPages() {
        assertTrue(DraftSync.mayUpload(1, setOf(1, 2)))           // eigene (auch archivierte) Seite
        assertFalse(DraftSync.mayUpload(7, setOf(1, 2)))          // fremde Seite (nur in "Mit mir geteilt")
        assertFalse(DraftSync.mayUpload(1, null))                 // eigene Seiten noch nicht geladen: nichts senden
    }
}
