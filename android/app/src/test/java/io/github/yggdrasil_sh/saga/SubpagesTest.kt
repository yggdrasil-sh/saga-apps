package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.blocks.PageWidgets
import io.github.yggdrasil_sh.saga.blocks.Subpages
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2.5 Widget-Art `subpages` (Unterseiten als Liste oder Galerie): Block, cfg, Werte, Spalten, Bildadressen */
class SubpagesTest {
    private fun obj(s: String) = Json.parseToJsonElement(s) as JsonObject
    private fun block(data: String) = obj("""{"id":"b7","type":"widget","data":$data}""")

    @Test
    fun artUndFaehigkeit() {
        assertTrue(PageWidgets.supported("subpages", setOf("subpages1")))
        assertFalse(PageWidgets.supported("subpages", setOf("widgets2", "bookorbit1")))
        val s = PageWidgets.spec(block("""{"v":1,"kind":"subpages","size":"m","title":"Leseliste","cfg":{"view":"gallery","image":"content"}}"""))
        assertTrue(s.known)
        assertTrue(s.serverValue)
        assertFalse(s.localFallback)
        assertEquals("Leseliste", s.title)
        assertEquals(setOf("b7"), PageWidgets.serverIds(obj("""{"blocks":[{"id":"b7","type":"widget","data":{"kind":"subpages"}}]}""")))
    }

    @Test
    fun rahmenStandardIstOhne() {
        // subpages: ohne style rahmenlos, nur "card" bekommt den Rahmen - die uebrigen Arten bleiben bei "card" als Standard
        assertEquals("plain", PageWidgets.spec(block("""{"kind":"subpages"}""")).style)
        assertEquals("plain", PageWidgets.spec(block("""{"kind":"subpages","style":"glas"}""")).style)
        assertEquals("card", PageWidgets.spec(block("""{"kind":"subpages","style":"card"}""")).style)
        assertEquals("card", PageWidgets.spec(block("""{"kind":"clock"}""")).style)
        assertEquals("plain", PageWidgets.spec(block("""{"kind":"clock","style":"plain"}""")).style)
    }

    @Test
    fun cfgUnbekannteWerte() {
        assertEquals(Subpages.Cfg(false, "cover"), Subpages.cfg(null))
        assertEquals(Subpages.Cfg(false, "cover"), Subpages.cfg(obj("{}")))
        assertEquals(Subpages.Cfg(true, "content"), Subpages.cfg(obj("""{"view":"gallery","image":"content"}""")))
        assertEquals(Subpages.Cfg(true, "none"), Subpages.cfg(obj("""{"view":"gallery","image":"none"}""")))
        assertEquals(Subpages.Cfg(false, "cover"), Subpages.cfg(obj("""{"view":"board","image":"poster"}""")))
        assertEquals(Subpages.Cfg(false, "cover"), Subpages.cfg(obj("""{"view":1,"image":null}""")))
    }

    @Test
    fun werteWieInDerDoku() {
        val v = PageWidgets.parseValue(obj("""{"kind":"subpages","value":6,"max":null,"text":null,"fmt":null,"as_of":"2026-11-09T20:00:00Z","error":null,
            "data":{"items":[{"id":812,"title":"Der Nebelturm","icon":"📖","img":"/files/0123456789abcdef01234567","kind":"page"},
                             {"id":813,"title":"Leseliste","icon":"","img":null,"kind":"database"}],
                    "total":6,"more":4,"can_add":true}}"""))
        val d = Subpages.data(v.data)!!
        assertEquals(2, d.items.size)
        assertEquals(Subpages.Item(812, "Der Nebelturm", "📖", "/files/0123456789abcdef01234567", false), d.items[0])
        assertEquals(Subpages.Item(813, "Leseliste", "", null, true), d.items[1])
        assertEquals(6, d.total)
        assertEquals(4, d.more)
        assertTrue(d.canAdd)
    }

    @Test
    fun werteFehlendeUndFremdeFelder() {
        assertNull(Subpages.data(null))
        assertNull(Subpages.data(obj("{}")))
        assertNull(Subpages.data(obj("""{"items":"x"}""")))
        val leer = Subpages.data(obj("""{"items":[]}"""))!!
        assertTrue(leer.items.isEmpty())
        assertEquals(0, leer.total)
        assertEquals(0, leer.more)
        assertFalse(leer.canAdd)
        val d = Subpages.data(obj("""{"items":[
            {"id":-5000000012,"title":"  Fern\n geteilt ","img":"/api/fed/files/-5000000012/abc","kind":"page","neu":{"x":1}},
            {"id":"77","kind":"kanban"},
            {"title":"ohne ID"}, {"id":0,"title":"null"}, {"id":"x"}, 7, null,
            {"id":77,"title":"doppelt"},
            {"id":9,"title":"Fremd","img":"https://example.org/a.jpg","icon":5},
            {"id":10,"img":"//example.org/a.jpg"}, {"id":11,"img":"/files/../api/x"}, {"id":12,"img":"/covers/berge-1.jpg"}],
            "total":"viele","more":-3,"can_add":"true"}"""))!!
        assertEquals(listOf(-5000000012L, 77L, 9L, 10L, 11L, 12L), d.items.map { it.id })
        assertEquals("Fern geteilt", d.items[0].title)
        assertEquals("/api/fed/files/-5000000012/abc", d.items[0].img)
        assertEquals("", d.items[1].title)                      // leer = "Unbenannt" zeigen
        assertFalse(d.items[1].database)                        // unbekannte Art = Seite
        assertEquals("", d.items[2].icon)
        assertNull(d.items[2].img)                              // nie fremde Hosts laden
        assertNull(d.items[3].img)
        assertNull(d.items[4].img)
        assertEquals("/covers/berge-1.jpg", d.items[5].img)
        assertEquals(0, d.more)
        assertEquals(6, d.total)                                // mindestens die gelieferten
        assertFalse(d.canAdd)                                   // nur echtes true
        assertEquals(200, Subpages.data(obj("""{"items":[{"id":1,"title":"${"a".repeat(300)}"}]}"""))!!.items[0].title.length)
    }

    @Test
    fun spalten() {
        // Handy hochkant (Inhaltsbreite ~328-380 dp): immer zwei, egal welche Groesse/Bildquelle
        for (size in listOf("s", "m", "l")) for (img in listOf("cover", "content", "none")) {
            assertEquals(2, Subpages.columns(328f, size, img))
            assertEquals(2, Subpages.columns(520f, size, img))
            assertTrue(Subpages.columns(728f, size, img) >= 2)
        }
        // Tablet (Lesebreite 728 dp): Hochformat-Karten M vier, S fuenf, L drei; Titelbild M drei, L zwei
        assertEquals(4, Subpages.columns(728f, "m", "content"))
        assertEquals(5, Subpages.columns(728f, "s", "content"))
        assertEquals(3, Subpages.columns(728f, "l", "content"))
        assertEquals(3, Subpages.columns(728f, "m", "cover"))
        assertEquals(2, Subpages.columns(728f, "l", "cover"))
        assertEquals(3, Subpages.columns(728f, "m", "none"))
        // unbekannte Groesse wie M; sehr schmal eine Spalte; grosse Schrift weniger Spalten; nie 0
        assertEquals(Subpages.columns(728f, "m", "content"), Subpages.columns(728f, "xl", "content"))
        assertEquals(1, Subpages.columns(150f, "m", "content"))
        assertEquals(3, Subpages.columns(728f, "m", "content", fontScale = 2f))
        assertEquals(2, Subpages.columns(0f, "m", "content"))
        assertEquals(2, Subpages.columns(Float.NaN, "m", "content"))
        assertEquals(12, Subpages.columns(100_000f, "s", "content"))
    }

    @Test
    fun bildflaecheUndAdressen() {
        assertEquals(2f / 3f, Subpages.aspect("content")!!, 0.0001f)
        assertEquals(16f / 9f, Subpages.aspect("cover")!!, 0.0001f)
        assertNull(Subpages.aspect("none"))
        assertEquals("/files/abc?w=400", Subpages.sized("/files/abc", Subpages.thumbWidth("content")))
        assertEquals("/files/abc?w=640", Subpages.sized("/files/abc", Subpages.thumbWidth("cover")))
        assertEquals("/api/fed/files/-12/abc?w=400", Subpages.sized("/api/fed/files/-12/abc", 400))
        assertEquals("/covers/berge-1.jpg?w=640", Subpages.sized("/covers/berge-1.jpg", 640))
        assertEquals("/covers/Berge.png", Subpages.sized("/covers/Berge.png", 640))
        assertEquals("/files/abc?w=96", Subpages.sized("/files/abc?w=96", 640))
        assertEquals("/notes#812", Subpages.href(812))
        assertEquals("/notes#-5000000012", Subpages.href(-5000000012))
    }

    @Test
    fun platzhalterNurInDerGalerieMitBildflaeche() {
        val ohne = Subpages.Item(1, "A", "", null, false)
        val mit = ohne.copy(img = "/files/abc")
        assertTrue(Subpages.placeholder(Subpages.Cfg(true, "content"), ohne))
        assertTrue(Subpages.placeholder(Subpages.Cfg(true, "cover"), ohne))
        assertFalse(Subpages.placeholder(Subpages.Cfg(true, "content"), mit))
        assertFalse(Subpages.placeholder(Subpages.Cfg(true, "none"), ohne))
        assertFalse(Subpages.placeholder(Subpages.Cfg(false, "cover"), ohne))
    }

    @Test
    fun foederationOhneFaehigkeit() {
        val r = PageWidgets.parseResponse("""{"widgets":{},"unsupported_kinds":["subpages",7]}""")
        assertEquals(PageWidgets.Values.Ok(emptyMap(), setOf("subpages")), r)
        assertEquals(PageWidgets.Values.Ok(emptyMap()), PageWidgets.parseResponse("""{"widgets":{},"unsupported_kinds":"subpages"}"""))
        assertEquals(PageWidgets.Values.Ok(emptyMap(), setOf("subpages")), PageWidgets.parseResponse("""{"unsupported_kinds":["subpages"]}"""))
        val ok = PageWidgets.parseResponse("""{"widgets":{"b7":{"kind":"subpages","value":0,"data":{"items":[],"total":0,"more":0,"can_add":false}}}}""") as PageWidgets.Values.Ok
        assertNotNull(Subpages.data(ok.map.getValue("b7").data))
        assertTrue(ok.unsupportedKinds.isEmpty())
    }
}
