package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.blocks.BlockDoc
import io.github.yggdrasil_sh.saga.blocks.BlockOps
import io.github.yggdrasil_sh.saga.blocks.Subpages
import io.github.yggdrasil_sh.saga.data.IconPath
import io.github.yggdrasil_sh.saga.data.Siegel
import io.github.yggdrasil_sh.saga.data.SiegelState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2.5 (Bugs-DB 2156): Saga-Siegel als Seitensymbol (Server docs/APP-API.md "Saga-Siegel als Seitensymbol (2.5)") -
 * Pfad, PNG-Adresse mit ?w=, Liste GET api/siegel, Name, Vorauswahl, Angebot (siegel1/remote.caps), Stellen, die Symbole
 * als Text zeigen.
 */
class SiegelTest {
    private val listing = """
        {"colors": [{"id": "weinrot", "label": "Weinrot"}, {"id": "smaragd", "label": "Smaragd"}, {"id": "elfenbein", "label": "Elfenbein"},
                    {"id": "Boese", "label": "x"}, {"id": "smaragd", "label": "doppelt"}, "kein Objekt"],
         "motifs": [{"id": "yggdrasil", "label": "Yggdrasil"}, {"id": "rabe", "label": " Rabe "}, {"id": "teetasse"},
                    {"id": "../x", "label": "boese"}, {"id": 5, "label": "Zahl"}],
         "path": "/siegel/{color}/{motif}.svg", "sizes": [64, 128, 256], "neu": true}
    """.trimIndent()

    @Test fun pfadErkennen() {
        assertEquals(Siegel.Ref("smaragd", "rabe"), Siegel.parse("/siegel/smaragd/rabe.svg"))
        assertEquals(Siegel.Ref("weinrot", "yggdrasil"), Siegel.parse("  /siegel/weinrot/yggdrasil.svg \n"))
        // kein Siegel: andere Endung, Abfrage, Anker, Grossbuchstaben, Pfad-Tricks, fremder Host, Teilpfad
        for (bad in listOf("/siegel/smaragd/rabe.png", "/siegel/smaragd/rabe.svg?w=64", "/siegel/smaragd/rabe.svg#x", "/siegel/Smaragd/rabe.svg",
                "/siegel/smaragd/../rabe.svg", "//evil.example/siegel/smaragd/rabe.svg", "https://evil.example/siegel/smaragd/rabe.svg",
                "/siegel/", "/siegel/smaragd/", "/files/siegel/smaragd/rabe.svg", "🦉", "", null, "/siegel/smaragd/r.svg")) {
            assertNull(bad, Siegel.parse(bad))
            assertFalse(bad, Siegel.isSiegel(bad))
        }
        assertEquals("/siegel/elfenbein/katze.svg", Siegel.path("elfenbein", "katze"))
        assertEquals("/siegel/elfenbein/katze.svg", Siegel.Ref("elfenbein", "katze").path)
    }

    @Test fun pngStufen() {
        assertEquals(64, Siegel.stage(1))
        assertEquals(64, Siegel.stage(64))
        assertEquals(128, Siegel.stage(65))
        assertEquals(128, Siegel.stage(128))
        assertEquals(256, Siegel.stage(129))
        assertEquals(256, Siegel.stage(4000))
        assertEquals("/siegel/smaragd/rabe.svg?w=128", Siegel.imageUrl(" /siegel/smaragd/rabe.svg", 66))
        assertEquals("/siegel/weinrot/horn.svg?w=256", Siegel.imageUrl("/siegel/weinrot/horn.svg", 216))
        assertNull(Siegel.imageUrl("/files/abc", 64))
    }

    @Test fun symbolArten() {
        // eigenes Bild wie bisher mit ?w=160, vorhandene Parameter bleiben; Siegel als PNG; Emoji/Text kein Bild
        assertEquals("/files/abc?w=160", IconPath.imageUrl("/files/abc", 60))
        assertEquals("/api/fed/files/-5/abc?w=160", IconPath.imageUrl(" /api/fed/files/-5/abc ", 60))
        assertEquals("/files/abc?w=64", IconPath.imageUrl("/files/abc?w=64", 60))
        assertEquals("/siegel/elfenbein/teetasse.svg?w=64", IconPath.imageUrl("/siegel/elfenbein/teetasse.svg", 60))
        assertNull(IconPath.imageUrl("📖", 60))
        assertNull(IconPath.imageUrl("/siegel/smaragd/rabe.png", 60))
        assertTrue(IconPath.isImage("/siegel/smaragd/rabe.svg"))
        assertTrue(IconPath.isImage("/files/x"))
        assertFalse(IconPath.isImage("📖"))
        // als Text: nur Emoji/Text - Pfade nie (auch keine unbekannten wie bisher "/siegel/")
        assertEquals("📖", IconPath.text(" 📖 "))
        for (p in listOf("/siegel/smaragd/rabe.svg", "/files/x", "/siegel/", "", null)) assertNull(p, IconPath.text(p))
    }

    @Test fun angebot() {
        val own = setOf("siegel1", "covers1")
        assertTrue(Siegel.offered(own, remote = false, remoteCaps = emptySet()))
        assertFalse("aelterer eigener Server", Siegel.offered(setOf("covers1"), false, emptySet()))
        assertTrue(Siegel.offered(own, true, setOf("db", "siegel1")))
        assertFalse("fremder Server ohne siegel1 -> 409", Siegel.offered(own, true, setOf("db")))
        assertFalse("eigener Server zu alt, fremder kann es", Siegel.offered(emptySet(), true, setOf("siegel1")))
    }

    @Test fun listeLesen() {
        val l = Siegel.parseListing(listing)!!
        assertEquals(listOf("weinrot", "smaragd", "elfenbein"), l.colors.map { it.id })
        assertEquals(listOf("yggdrasil", "rabe", "teetasse"), l.motifs.map { it.id })
        assertEquals("Rabe", l.motifLabel("rabe"))
        assertEquals("teetasse", l.motifLabel("teetasse"))          // ohne Namen: ID
        assertEquals("Smaragd", l.colorLabel("smaragd"))
        assertNull(Siegel.parseListing("kein json"))
        assertNull(Siegel.parseListing("""{"colors": [], "motifs": [{"id": "rabe"}]}"""))
        assertNull(Siegel.parseListing("""[1, 2]"""))
    }

    @Test fun nameUndVorauswahl() {
        val l = Siegel.parseListing(listing)!!
        assertEquals("Rabe (Smaragd)", Siegel.label(l, "/siegel/smaragd/rabe.svg"))
        assertNull(Siegel.label(null, "/siegel/smaragd/rabe.svg"))
        assertNull(Siegel.label(l, "📖"))
        // jetziges Siegel vor zuletzt gewaehlter Farbe vor erster Farbe; unbekannte Farben zaehlen nicht
        assertEquals("elfenbein", Siegel.preselect(l, "/siegel/elfenbein/rabe.svg", "smaragd"))
        assertEquals("smaragd", Siegel.preselect(l, "📖", "smaragd"))
        assertEquals("weinrot", Siegel.preselect(l, "/siegel/violett/rabe.svg", "blau"))
        assertEquals("weinrot", Siegel.preselect(l, null, null))
    }

    @Test fun zustandJeServer() {
        val st = SiegelState()
        val l = Siegel.parseListing(listing)!!
        st.record("https://a.example", "de", 200, l)
        assertEquals(l, st.cached("https://a.example", "de"))
        assertNull(st.cached("https://a.example", "en"))
        st.record("https://a.example", "de", 503, null)               // spaeter wieder: nichts aendern
        assertEquals(l, st.cached("https://a.example", "de"))
        st.record("https://a.example", "de", 404, null)               // aelterer Server
        assertNull(st.cached("https://a.example", "de"))
        assertTrue("https://a.example" in st.unsupported.value)
        st.record("https://a.example", "de", 200, l)
        assertFalse("https://a.example" in st.unsupported.value)
        st.clear()
        assertNull(st.cached("https://a.example", "de"))
    }

    @Test fun unterseitenBlockZeigtSiegel() {
        val d = Json.parseToJsonElement(
            """{"items":[{"id":1,"title":"A","icon":"/siegel/smaragd/rabe.svg","kind":"page"},
                {"id":2,"title":"B","icon":" /siegel/weinrot/horn.svg ","kind":"page"},
                {"id":3,"title":"C","icon":"/siegel/smaragd/rabe.svg?w=64","kind":"page"},
                {"id":4,"title":"D","icon":"/files/abc","kind":"page"}], "total": 4}""",
        ) as JsonObject
        val items = Subpages.data(d)!!.items
        assertEquals(listOf("/siegel/smaragd/rabe.svg", "/siegel/weinrot/horn.svg", "", "/files/abc"), items.map { it.icon })
    }

    @Test fun seitenverweisOhnePfadAlsText() {
        fun text(b: JsonObject) = BlockDoc.str(BlockDoc.data(b), "text")
        assertEquals("📄 <a href=\"/notes#5\">A</a>", text(BlockOps.pageLink(5, "A", "📄")))
        assertEquals("<a href=\"/notes#5\">A</a>", text(BlockOps.pageLink(5, "A", "/siegel/smaragd/rabe.svg")))
        assertEquals("<a href=\"/notes#5\">A</a>", text(BlockOps.pageLink(5, "A", "/files/abc")))
    }
}
