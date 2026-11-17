package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.data.CoverAbout
import io.github.yggdrasil_sh.saga.data.CoverGallery
import io.github.yggdrasil_sh.saga.data.CoverGalleryState
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2.5 Titelbild-Galerie (GET api/covers wie in docs/APP-API.md, Daten frei erfunden): Lesen, Gruppen, Setzen, Zufall, Ueber */
class CoverGalleryTest {
    private val sample = """
        {"covers": [
          {"id": "balke-nordkap", "url": "/covers/balke-nordkap.jpg", "thumb": "/covers/balke-nordkap.jpg?w=384", "title": "Das Nordkap im Mondlicht",
           "artist": "Peder Balke", "date": "1848", "source_url": "https://www.metmuseum.org/art/collection/search/441379",
           "source": "The Metropolitan Museum of Art, Open Access (CC0 1.0)", "group": "nordisch", "pos": 55, "builtin": true},
          {"id": "saga-wolf", "url": "/covers/saga-wolf.jpg", "thumb": "https://evil.example/x.jpg", "title": " Trost beim Wolf ",
           "artist": "", "date": "", "source_url": "javascript:alert(1)", "source": "KI-generiert für Saga", "group": "saga-gefaehrten", "pos": 20.5, "builtin": true},
          {"id": "u-0123456789ab", "url": "/covers/u-0123456789ab.jpg", "title": "", "group": "eigene", "pos": 140, "builtin": false},
          {"id": "x", "url": "/files/abc", "title": "fremd"},
          {"id": "y", "url": "/covers/../etc.jpg", "title": "boese"},
          {"id": "balke-nordkap", "url": "/covers/balke-nordkap.jpg", "title": "doppelt"},
          "kein Objekt",
          {"id": "ohne", "url": "/covers/ohne.jpg", "title": "Ohne Gruppe", "pos": true, "builtin": "true"}
        ],
         "groups": [{"id": "nordisch", "label": "Nordisch"}, {"id": "romantik", "label": "Romantik"},
                    {"id": "saga-gefaehrten", "label": "Saga · Gefährten"}, {"id": "", "label": "Weitere"}],
         "license": "CC0-1.0", "source": "The Metropolitan Museum of Art, Open Access (CC0 1.0)", "random_cover": true, "neu": 1}
    """.trimIndent()

    private val l = CoverGallery.parse(sample)!!

    @Test fun lesen() {
        assertEquals(listOf("balke-nordkap", "saga-wolf", "u-0123456789ab", "ohne"), l.covers.map { it.id })
        val b = l.covers[0]
        assertEquals("/covers/balke-nordkap.jpg?w=384", b.thumb)
        assertEquals(55.0, b.pos, 0.0)
        assertTrue(b.builtin)
        assertEquals("Peder Balke, 1848", b.credit)
        assertEquals("Das Nordkap im Mondlicht – Peder Balke, 1848", b.label)
        val w = l.covers[1]
        assertEquals("/covers/saga-wolf.jpg?w=384", w.thumb)                   // fremde Vorschauadresse verworfen
        assertEquals("Trost beim Wolf", w.title)
        assertEquals("Trost beim Wolf", w.label)
        assertEquals("", w.sourceUrl)                                          // javascript: verworfen
        assertEquals("KI-generiert für Saga", w.source)
        assertEquals(20.5, w.pos, 0.0)
        val u = l.covers[2]
        assertEquals("u-0123456789ab", u.title)
        assertEquals(100.0, u.pos, 0.0)
        assertFalse(u.builtin)
        assertEquals(50.0, l.covers[3].pos, 0.0)                               // Bool ist keine Position
        assertFalse(l.covers[3].builtin)                                       // "true" als Text zaehlt nicht
        assertEquals("CC0-1.0", l.license)
        assertTrue(l.source.startsWith("The Metropolitan"))
        assertNull(CoverGallery.parse("[]"))
        assertNull(CoverGallery.parse("{}"))
        assertNull(CoverGallery.parse("kaputt"))
        assertTrue(CoverGallery.parse("""{"covers": []}""")!!.covers.isEmpty())
        assertEquals(1.0, CoverGallery.pos(JsonPrimitive(1)), 0.0)
        assertEquals(50.0, CoverGallery.pos(JsonPrimitive("30")), 0.0)
        assertEquals(0.0, CoverGallery.pos(JsonPrimitive(-3)), 0.0)
    }

    @Test fun pfade() {
        assertTrue(CoverGallery.isGalleryPath("/covers/hokusai-welle.jpg"))
        assertTrue(CoverGallery.isGalleryPath("/covers/u-0123456789ab.jpg"))
        for (p in listOf("/files/abc", "/covers/Gross.jpg", "/covers/-a.jpg", "/covers/a.jpg?w=384", "/covers/.jpg", null,
            "/covers/" + "a".repeat(65) + ".jpg")) assertFalse(p.toString(), CoverGallery.isGalleryPath(p))
        assertEquals(true, CoverGallery.offered(200))
        assertEquals(false, CoverGallery.offered(404))
        assertEquals(false, CoverGallery.offered(403))
        assertNull(CoverGallery.offered(500))
        assertNull(CoverGallery.offered(0))
    }

    @Test fun gruppen() {
        val g = CoverGallery.grouped(l, remote = false, other = "Weitere")
        assertEquals(listOf("nordisch", "saga-gefaehrten", "eigene", ""), g.map { it.id })
        assertEquals(listOf("Nordisch", "Saga · Gefährten", "eigene", "Weitere"), g.map { it.label })
        assertEquals(listOf("ohne"), g[3].covers.map { it.id })
        val gr = CoverGallery.grouped(l, remote = true, other = "Weitere")
        assertTrue(gr.none { it.id == "eigene" })
        assertTrue(gr.flatMap { it.covers }.all { it.builtin })
        val noLabel = CoverGallery.parse("""{"covers": [{"url": "/covers/a.jpg", "group": ""}], "groups": []}""")!!
        assertEquals(listOf("Weitere"), CoverGallery.grouped(noLabel, false, "Weitere").map { it.label })
    }

    @Test fun setzen() {
        val b = l.covers[0]
        val f = CoverGallery.fields(b, null)!!
        assertEquals("/covers/balke-nordkap.jpg", f["cover"]!!.jsonPrimitive.content)
        assertEquals(55.0, f["cover_pos"]!!.jsonPrimitive.double, 0.0)
        assertNotNull(CoverGallery.fields(b, "/files/abc"))
        assertNull(CoverGallery.fields(b, "/covers/balke-nordkap.jpg"))         // schon gesetzt: nicht speichern
    }

    @Test fun zufall() {
        assertEquals("saga-wolf", CoverGallery.random(l, false, "/covers/balke-nordkap.jpg") { 0 }?.id)
        assertEquals("ohne", CoverGallery.random(l, false, null) { it + 5 }?.id)
        assertTrue(CoverGallery.random(l, true, null) { it - 1 }!!.builtin)
        val one = CoverGallery.parse("""{"covers": [{"url": "/covers/a.jpg"}]}""")!!
        assertNull(CoverGallery.random(one, false, "/covers/a.jpg"))
    }

    @Test fun ueber() {
        assertEquals(CoverAbout.Image(l.covers[1]), CoverGallery.about(l, "/covers/saga-wolf.jpg"))
        assertEquals(CoverAbout.Gone, CoverGallery.about(l, "/covers/versteckt.jpg"))
        assertEquals(CoverAbout.Gone, CoverGallery.about(null, "/covers/saga-wolf.jpg"))
        assertEquals(CoverAbout.None, CoverGallery.about(l, "/files/abc"))
        assertEquals(CoverAbout.None, CoverGallery.about(l, null))
    }

    @Test fun zustand() {
        val s = CoverGalleryState()
        s.record("https://a.example", "de", 500, null)
        assertNull(s.cached("https://a.example", "de"))
        assertTrue(s.unsupported.value.isEmpty())                              // offline/5xx: spaeter wieder
        s.record("https://a.example", "de", 200, l)
        assertEquals(l, s.cached("https://a.example", "de"))
        s.record("https://a.example", "de", 200, null)                         // unlesbar: alter Stand bleibt
        assertEquals(l, s.cached("https://a.example", "de"))
        s.record("https://a.example", "de", 404, null)                         // aelterer Server: ausblenden
        assertNull(s.cached("https://a.example", "de"))
        assertTrue("https://a.example" in s.unsupported.value)
        s.record("https://a.example", "en", 200, l)
        assertFalse("https://a.example" in s.unsupported.value)
    }
}
