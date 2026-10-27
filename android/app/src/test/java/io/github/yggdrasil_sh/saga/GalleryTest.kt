package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.data.ApiException
import io.github.yggdrasil_sh.saga.data.Gallery
import io.github.yggdrasil_sh.saga.data.GalleryItem
import io.github.yggdrasil_sh.saga.data.GalleryLoad
import io.github.yggdrasil_sh.saga.data.GalleryState
import io.github.yggdrasil_sh.saga.data.PageMeta
import io.github.yggdrasil_sh.saga.data.Person
import io.github.yggdrasil_sh.saga.util.UiText
import io.github.yggdrasil_sh.saga.util.UiTextException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/** 2.5 Vorlagengalerie (einfache Auswahl, SPEC-galerie §5): Parsing, Gruppen, Anfrage, Fehlerfaelle */
class GalleryTest {
    private val sample = """
        {"items": [
          {"id": "wiki", "title": "Team-Wiki", "desc": "Startseite mit Inhaltsverzeichnis", "icon": "📚", "category": "team", "order": 40,
           "needs": [], "contents": [{"kind": "page", "title": "Onboarding", "icon": "👋"}]},
          {"id": "mein-tag", "title": "Mein Tag", "desc": "Widgets", "icon": "☀️", "category": "personal", "order": 10, "needs": ["calendar"],
           "contents": [{"kind": "database", "title": "Aufgaben", "icon": "✅"}, {"title": "Notizen"}], "neu": {"x": 1}},
          {"id": "aufgaben-projekte", "title": "Aufgaben & Projekte", "desc": "", "icon": "✅", "category": "work", "order": 20},
          {"id": "leseliste", "title": "Leseliste", "icon": "📖", "category": "personal", "order": 5},
          {"id": "rezepte", "title": "Rezepte", "category": "hobby"},
          {"title": "ohne id"},
          {"id": "", "title": "leere id"},
          "kein Objekt",
          {"id": "wiki", "title": "doppelt"}
        ]}
    """.trimIndent()

    private val friendly: (Throwable) -> UiText = { UiText.Raw("allgemein:" + it.message) }

    // ------------------------------------------------------------ Liste

    @Test fun parseListTolerant() {
        val l = Gallery.parseList(sample)
        assertEquals(listOf("wiki", "mein-tag", "aufgaben-projekte", "leseliste", "rezepte"), l.map { it.id })
        val tag = l[1]
        assertEquals("Mein Tag", tag.title)
        assertEquals("☀️", tag.icon)
        assertEquals("personal", tag.category)
        assertEquals(10, tag.order)
        assertEquals(listOf("calendar"), tag.needs)
        assertEquals(2, tag.contents.size)
        assertEquals("database", tag.contents[0].kind)
        assertEquals("page", tag.contents[1].kind)            // fehlende Art -> Seite
        assertEquals("", tag.contents[1].icon)
        assertEquals("Team-Wiki", l[0].title)                 // erstes Vorkommen gewinnt
    }

    @Test fun missingFieldsDefaults() {
        val r = Gallery.parseList("""{"items": [{"id": "x", "title": null, "order": "7", "category": null, "needs": "calendar", "contents": 3}]}""")[0]
        assertEquals("x", r.title)                            // ohne Titel: id
        assertEquals("", r.desc)
        assertEquals(7, r.order)                              // Zahl als Zeichenkette
        assertEquals(Gallery.OTHER, r.category)
        assertTrue(r.needs.isEmpty())
        assertTrue(r.contents.isEmpty())
        assertEquals(Int.MAX_VALUE, Gallery.parseList("""[{"id": "y", "order": true}]""")[0].order)
    }

    @Test fun parseListShapes() {
        assertEquals(1, Gallery.parseList("""[{"id": "a"}]""").size)           // blanke Liste
        assertTrue(Gallery.parseList("""{"items": []}""").isEmpty())
        assertTrue(Gallery.parseList("""{"other": 1}""").isEmpty())
        assertTrue(Gallery.parseList("""{"items": {"id": "a"}}""").isEmpty())
        assertEquals("work", Gallery.parseList("""[{"id": "a", "category": " WORK "}]""")[0].category)
    }

    @Test fun parseListBroken() {
        for (bad in listOf("", "<html>404</html>", "\"text\"", "42")) {
            try {
                Gallery.parseList(bad)
                fail("erwartet Fehler fuer $bad")
            } catch (e: UiTextException) {
                assertEquals(UiText.Res(R.string.gal_error_bad_answer), e.text)
            }
        }
    }

    @Test fun groupsOrder() {
        val g = Gallery.groups(Gallery.parseList(sample))
        assertEquals(listOf("personal", "work", "team", "other"), g.map { it.category })
        assertEquals(listOf("leseliste", "mein-tag"), g[0].items.map { it.id })     // nach order
        val tie = listOf(item("b", "Beta", "work", 1), item("a", "alpha", "work", 1), item("c", "Gamma", "work", 0))
        assertEquals(listOf("c", "a", "b"), Gallery.groups(tie)[0].items.map { it.id })
        assertTrue(Gallery.groups(emptyList()).isEmpty())
        assertEquals(listOf("team"), Gallery.groups(listOf(item("t", "T", "team", 1))).map { it.category })
    }

    // ------------------------------------------------------------ Anfrage

    @Test fun paths() {
        assertEquals("api/gallery?lang=de", Gallery.listPath("de"))
        assertEquals("api/gallery?lang=es", Gallery.listPath("es-MX"))
        assertEquals("api/gallery?lang=th", Gallery.listPath("TH"))
        assertEquals("api/gallery", Gallery.listPath(""))
        assertEquals("api/gallery", Gallery.listPath("d&e"))
        assertEquals("api/gallery/mein-tag/use", Gallery.usePath("mein-tag"))
        assertEquals("api/gallery/a%2Fb%20c%3F/use", Gallery.usePath("a/b c?"))
    }

    @Test fun useBody() {
        val a = Gallery.useBody(null, true)
        assertEquals(JsonNull, a["parent_id"])
        assertTrue(a["examples"]!!.jsonPrimitive.boolean)
        val b = Gallery.useBody(42L, false)
        assertEquals(42L, b["parent_id"]!!.jsonPrimitive.long)
        assertFalse(b["examples"]!!.jsonPrimitive.boolean)
        assertEquals("""{"parent_id":42,"examples":false}""", Json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), b))
    }

    @Test fun parseUse() {
        val r = Gallery.parseUse("""{"root": 12, "created": [12, 13, "14", 15.0, null, "x"], "extra": true}""")
        assertEquals(12L, r.root)
        assertEquals(listOf(12L, 13L, 14L, 15L), r.created)
        assertEquals(7L, Gallery.parseUse("""{"root": "7"}""").root)
        assertEquals(9L, Gallery.parseUse("""{"created": [9, 10]}""").root)    // ohne root: erste Seite
        for (bad in listOf("""{"created": []}""", """{"root": null}""", "[]", "", """{"root": true}""", """{"root": 1.5}""")) {
            try {
                Gallery.parseUse(bad)
                fail("erwartet Fehler fuer $bad")
            } catch (e: UiTextException) {
                assertEquals(UiText.Res(R.string.gal_error_bad_answer), e.text)
            }
        }
    }

    // ------------------------------------------------------------ Elternseite

    @Test fun canBeParent() {
        assertTrue(Gallery.canBeParent(PageMeta(id = 5)))
        assertFalse(Gallery.canBeParent(PageMeta(id = 5, kind = "database")))
        assertFalse(Gallery.canBeParent(PageMeta(id = -3)))                   // Seite eines anderen Servers
        val other = Person(name = "b", me = false)
        assertFalse(Gallery.canBeParent(PageMeta(id = 6, owner = other, role = "view")))
        assertTrue(Gallery.canBeParent(PageMeta(id = 6, owner = other, role = "edit")))
        assertTrue(Gallery.canBeParent(PageMeta(id = 7, owner = Person(name = "a", me = true))))
    }

    // ------------------------------------------------------------ Fehler

    @Test fun listErrors() {
        assertEquals(GalleryLoad.Unsupported, Gallery.classifyList(ApiException(404, UiText.Res(R.string.error_not_found), """{"error": "not found"}"""), friendly))
        assertEquals(GalleryLoad.Unsupported, Gallery.classifyList(ApiException(404, UiText.Raw("x"), "<html>"), friendly))
        val net = Gallery.classifyList(IOException("weg"), friendly)
        assertEquals(GalleryLoad.Failed(UiText.Raw("allgemein:weg")), net)
        val e500 = Gallery.classifyList(ApiException(500, UiText.Raw("x"), ""), friendly) as GalleryLoad.Failed
        assertEquals(UiText.Res(R.string.gal_error_server, 500), e500.text)
        val e401 = Gallery.classifyList(ApiException(401, UiText.Raw("anmelden"), ""), friendly) as GalleryLoad.Failed
        assertEquals(UiText.Raw("allgemein:HTTP 401: Raw(text=anmelden)"), e401.text)
    }

    @Test fun useErrors() {
        assertEquals(UiText.Res(R.string.gal_error_rate), Gallery.useError(429, """{"error": "rate limited"}"""))
        assertEquals(UiText.Res(R.string.gal_error_rate), Gallery.useError(429, """{"error": "Zu viele"}"""))      // immer eigener Text
        assertEquals(UiText.Res(R.string.gal_error_gone), Gallery.useError(404, ""))
        assertEquals(UiText.Res(R.string.gal_error_forbidden), Gallery.useError(403, """{"error": "forbidden"}"""))
        assertEquals(UiText.Res(R.string.gal_error_forbidden), Gallery.useError(403, "kein json"))
        assertEquals(UiText.Raw("Keine Bearbeitungsrechte an der Elternseite"), Gallery.useError(403, """{"error": "Keine Bearbeitungsrechte an der Elternseite"}"""))
        assertEquals(
            UiText.Raw("Unter föderiert geteilten Seiten noch nicht möglich"),
            Gallery.useError(400, """{"error": " Unter föderiert geteilten Seiten noch nicht möglich "}"""),
        )
        assertEquals(UiText.Res(R.string.gal_error_invalid), Gallery.useError(400, """{"error": ""}"""))
        assertEquals(UiText.Res(R.string.gal_error_invalid), Gallery.useError(400, """{"error": 5}"""))
        assertEquals(UiText.Res(R.string.gal_error_invalid), Gallery.useError(422, "{}"))
        assertEquals(UiText.Res(R.string.gal_error_server, 503), Gallery.useError(503, "Bad Gateway"))
        assertNull(Gallery.useError(401, """{"error": "unauthorized"}"""))
        assertNull(Gallery.useError(302, ""))
    }

    @Test fun friendlyUse() {
        assertEquals(UiText.Res(R.string.gal_error_rate), Gallery.friendlyUse(ApiException(429, UiText.Raw("x"), ""), friendly))
        assertEquals(UiText.Raw("allgemein:aus"), Gallery.friendlyUse(IOException("aus"), friendly))
        assertEquals(UiText.Raw("allgemein:kaputt"), Gallery.friendlyUse(IllegalStateException("kaputt"), friendly))
    }

    @Test fun serverMessage() {
        assertEquals("Nein", Gallery.serverMessage("""{"error": "Nein"}"""))
        assertNull(Gallery.serverMessage("""{"error": "  "}"""))
        assertNull(Gallery.serverMessage("""{"error": null}"""))
        assertNull(Gallery.serverMessage("""["error"]"""))
        assertNull(Gallery.serverMessage("oops"))
    }

    // ------------------------------------------------------------ Zustand je Server

    @Test fun stateRecord() {
        val s = GalleryState()
        val a = "https://a.example"
        assertTrue(s.available(a))
        assertFalse(s.available(""))
        assertNull(s.cached(a, "de"))
        s.record(a, "de", GalleryLoad.Ok(listOf(item("x", "X", "work", 1))))
        assertEquals(1, s.cached(a, "de")!!.size)
        assertNull(s.cached(a, "en"))
        s.record(a, "en", GalleryLoad.Failed(UiText.Raw("f")))           // Fehler aendert nichts
        assertNull(s.cached(a, "en"))
        assertTrue(s.available(a))
        s.record(a, "en", GalleryLoad.Unsupported)
        assertFalse(s.available(a))
        assertNull(s.cached(a, "de"))                                      // alle Sprachen dieses Servers weg
        assertTrue(s.available("https://b.example"))
        s.record(a, "de", GalleryLoad.Ok(emptyList()))                     // Server aktualisiert -> wieder da
        assertTrue(s.available(a))
        assertEquals(0, s.cached(a, "de")!!.size)
        s.clear()
        assertNull(s.cached(a, "de"))
        assertTrue(s.lists.value.isEmpty())
    }

    private fun item(id: String, title: String, cat: String, order: Int) =
        GalleryItem(id = id, title = title, desc = "", icon = "", category = cat, order = order)
}
