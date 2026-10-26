package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.blocks.BlockDoc
import io.github.yggdrasil_sh.saga.blocks.Gestalten
import io.github.yggdrasil_sh.saga.data.Drafts
import io.github.yggdrasil_sh.saga.data.MemoryDrafts
import io.github.yggdrasil_sh.saga.ui.page.PageBackend
import io.github.yggdrasil_sh.saga.ui.page.PageEditor
import io.github.yggdrasil_sh.saga.util.UiText
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Saga 2.5 "Seite gestalten" (SPEC-gestalten): Regel 0, G1, G2, G3, G9, G10, R */
@OptIn(ExperimentalCoroutinesApi::class)
class GestaltenTest {
    private fun obj(s: String) = Json.parseToJsonElement(s) as JsonObject

    /** Seite mit allen neuen Feldern, fremden tunes-Schluesseln und unbekannten data-Schluesseln (auch verschachtelt) */
    private val page = """{"time":1,"blocks":[""" +
        """{"id":"p1","type":"paragraph","data":{"text":"Eins","zukunft":{"a":[1,2]}},"tunes":{"hkstyle":{"c":"red","bg":true},"fremd":{"x":1}}},""" +
        """{"id":"p2","type":"paragraph","data":{"text":"Zwei"}},""" +
        """{"id":"c1","type":"columns","data":{"cols":[{"w":0.7,"blocks":[{"id":"n1","type":"header","data":{"text":"H","level":2,"neu":true},"tunes":{"hkstyle":{"c":"blue"}}}]},{"w":0.3,"blocks":[]}]}},""" +
        """{"id":"i1","type":"image","data":{"file":{"url":"/files/x"},"width":40,"align":"right","extra":"bleibt"}},""" +
        """{"id":"t1","type":"toggle","data":{"title":"T","level":2,"blocks":[{"id":"u1","type":"zukunftsblock","data":{"k":"v"},"tunes":{"irgendwas":1}}]}},""" +
        """{"id":"x1","type":"widget","data":{"q":1}}""" +
        """]}"""

    private class Fake(var body: String) : PageBackend {
        var rev = 1
        val patches = ArrayList<JsonObject>()
        val extra: Map<String, JsonElement> = mapOf("font" to JsonPrimitive("serif"), "small" to JsonPrimitive(true))
        val drafts = MemoryDrafts()
        override val currentServer = "https://s.example.org"
        override val signedOut = false
        override fun draftsForCurrent(): Drafts = drafts
        override suspend fun <T> withPageLock(id: Long, block: suspend () -> T): T = block()
        override suspend fun loadPage(id: Long): JsonObject = JsonObject(
            LinkedHashMap(buildJsonObject { put("id", id); put("title", "T"); put("body", body); put("format", "blocks"); put("rev", rev) }).apply { putAll(extra) },
        )
        override suspend fun savePage(id: Long, title: String, body: String?, rev: Int?): Int? {
            this.body = body ?: this.body
            return ++this.rev
        }
        override suspend fun createPageWith(title: String, body: String): Long = 2
        override suspend fun setPinned(id: Long, pinned: Boolean) {}
        override suspend fun setLocked(id: Long, locked: Boolean) {}
        override suspend fun patchPage(id: Long, body: JsonObject) { patches += body }
        override fun updateTitleLocal(id: Long, title: String) {}
        override fun visited(id: Long) {}
        override fun friendly(e: Throwable): UiText = UiText.Raw("?")
    }

    private fun strip(d: JsonObject): JsonObject = JsonObject(LinkedHashMap(d).apply { remove("time") })

    // ---------------------------------------------------------------- Regel 0

    @Test
    fun rundreiseBehaeltTunesUndUnbekannteFelder() = runTest {
        val srv = Fake(page)
        val ed = PageEditor(1, srv, backgroundScope, io = StandardTestDispatcher(testScheduler), cpu = StandardTestDispatcher(testScheduler), clock = { 5 })
        ed.load()
        ed.setString(listOf("blocks", 1, "data", "text"), "Zwei geändert")    // anderen Block ändern
        advanceTimeBy(30_000); runCurrent()
        val saved = obj(srv.body)
        val before = obj(page)
        val sb = BlockDoc.blocks(saved)
        val bb = BlockDoc.blocks(before)
        assertEquals(bb.size, sb.size)
        for (i in bb.indices) if (i != 1) assertEquals("Block $i unverändert", bb[i], sb[i])
        assertEquals("Zwei geändert", BlockDoc.str(BlockDoc.data(sb[1]), "text"))
        // tunes inkl. fremder Schluessel, unbekannte data-Felder, verschachtelt
        assertEquals(obj("""{"hkstyle":{"c":"red","bg":true},"fremd":{"x":1}}"""), (sb[0] as JsonObject)["tunes"])
        assertTrue(srv.body.contains(""""irgendwas":1"""))
        assertTrue(srv.body.contains(""""neu":true"""))
        assertTrue(srv.body.contains(""""w":0.7"""))
        assertEquals(strip(before).toString().replace("Zwei", "Zwei geändert"), strip(saved).toString())
    }

    @Test
    fun farbeSetzenBehaeltFremdeTunes() = runTest {
        val srv = Fake(page)
        val ed = PageEditor(1, srv, backgroundScope, io = StandardTestDispatcher(testScheduler), cpu = StandardTestDispatcher(testScheduler), clock = { 5 })
        ed.load()
        ed.setBlockStyle(listOf("blocks", 0), "green", false)
        advanceTimeBy(30_000); runCurrent()
        val b0 = BlockDoc.blocks(obj(srv.body))[0] as JsonObject
        assertEquals(obj("""{"hkstyle":{"c":"green","bg":false},"fremd":{"x":1}}"""), b0["tunes"])
        assertEquals(obj("""{"text":"Eins","zukunft":{"a":[1,2]}}"""), b0["data"])
        // Standard: hkstyle weg, fremder Schluessel bleibt
        ed.setBlockStyle(listOf("blocks", 0), null, false)
        advanceTimeBy(30_000); runCurrent()
        assertEquals(obj("""{"fremd":{"x":1}}"""), (BlockDoc.blocks(obj(srv.body))[0] as JsonObject)["tunes"])
        // nicht erlaubter Typ (Bild): nichts
        val img = BlockDoc.blocks(obj(srv.body))[3]
        ed.setBlockStyle(listOf("blocks", 3), "red", true)
        advanceTimeBy(30_000); runCurrent()
        assertEquals(img, BlockDoc.blocks(obj(srv.body))[3])
    }

    @Test
    fun setStyleOhneTunesUndEntfernen() {
        val b = obj("""{"id":"a","type":"quote","data":{"text":"x"}}""")
        val s = Gestalten.setStyle(b, "purple", true)
        assertEquals(Gestalten.Style("purple", true), Gestalten.style(s))
        assertEquals(b, Gestalten.setStyle(s, null, false))                // tunes faellt ganz weg
        assertEquals(b, Gestalten.setStyle(b, "lime", false))              // keine freien Farben
    }

    // ---------------------------------------------------------------- G9 Schrift

    @Test
    fun seitenSchriftLesenUndPatchen() = runTest {
        val srv = Fake(page)
        val ed = PageEditor(1, srv, backgroundScope, io = StandardTestDispatcher(testScheduler), cpu = StandardTestDispatcher(testScheduler), clock = { 5 })
        ed.load()
        assertEquals("serif", ed.font)
        assertTrue(ed.small)
        assertTrue(ed.styleSupported)
        ed.setFont(null)
        ed.setSmall(false)
        assertNull(ed.font)
        assertFalse(ed.small)
        assertEquals(listOf(obj("""{"font":"default"}"""), obj("""{"small":false}""")), srv.patches)
        ed.setFont("mono")
        assertEquals(obj("""{"font":"mono"}"""), srv.patches.last())
    }

    @Test
    fun seitenFelderBegrenzt() {
        assertNull(Gestalten.pageFont(obj("""{"font":"comic"}""")))
        assertNull(Gestalten.pageFont(obj("""{"font":null}""")))
        assertEquals("mono", Gestalten.pageFont(obj("""{"font":"mono"}""")))
        assertTrue(Gestalten.pageSmall(obj("""{"small":1}""")))
        assertFalse(Gestalten.pageSmall(obj("""{"small":0}""")))
        assertFalse(Gestalten.pageSmall(obj("""{}""")))
        assertFalse(Gestalten.pageStyleSupported(obj("""{"wide":true}""")))   // aelterer Server: kein Menue
        assertTrue(Gestalten.pageStyleSupported(obj("""{"font":null,"small":false}""")))
    }

    // ---------------------------------------------------------------- G1 Spalten

    @Test
    fun spaltenGewichte() {
        val w = Gestalten.columnWeights(obj("""{"type":"columns","data":{"cols":[{"w":0.7,"blocks":[]},{"w":0.3,"blocks":[]}]}}"""))!!
        assertEquals(0.7f, w[0], 0.001f)
        assertEquals(0.3f, w[1], 0.001f)
        // eine Spalte ohne w -> alle gleich breit
        assertNull(Gestalten.columnWeights(obj("""{"type":"columns","data":{"cols":[{"w":0.7},{}]}}""")))
        assertNull(Gestalten.columnWeights(obj("""{"type":"columns","data":{"cols":[{"w":0.7},{"w":"0.3"}]}}""")))
        assertNull(Gestalten.columnWeights(obj("""{"type":"columns","data":{"cols":[{"w":1},{"w":-1}]}}""")))
        // fremder Server: nicht normiert, winzige Spalte -> mind. 0,15 (dann neu normiert)
        val v = Gestalten.columnWeights(obj("""{"type":"columns","data":{"cols":[{"w":9},{"w":0.1}]}}"""))!!
        assertEquals(1f, v.sum(), 0.001f)
        assertTrue(v[1] >= 0.13f)
    }

    // ---------------------------------------------------------------- G2 Bild/Player

    @Test
    fun bildBreiteUndAusrichtung() {
        val d = obj("""{"width":40,"align":"right"}""")
        assertEquals(0.4f, Gestalten.mediaFraction(d, phone = false)!!, 0.001f)
        assertEquals(0.5f, Gestalten.mediaFraction(d, phone = true)!!, 0.001f)       // Telefon mind. 50 %
        assertEquals("right", Gestalten.align(d))
        assertEquals("center", Gestalten.align(obj("""{"align":"justify"}""")))
        assertEquals(1f, Gestalten.mediaFraction(obj("""{"width":40,"stretched":true}"""), false)!!, 0f)   // stretched gewinnt
        assertNull(Gestalten.mediaFraction(obj("""{}"""), false))
        assertEquals(10, Gestalten.widthPercent(obj("""{"width":3}""")))
        assertEquals(100, Gestalten.widthPercent(obj("""{"width":250}""")))
        assertEquals(100, Gestalten.widthPercent(obj("""{"width":1e300}""")))
        assertEquals(100, Gestalten.widthPercent(obj("""{"width":4294967306}""")))
        assertEquals(10, Gestalten.widthPercent(obj("""{"width":-1e300}""")))
        assertNull(Gestalten.widthPercent(obj("""{"width":"50"}""")))
        assertTrue(Gestalten.isPlayerEmbed(obj("""{"url":"https://youtu.be/abcdefghijk"}""")))
        assertFalse(Gestalten.isPlayerEmbed(obj("""{"url":"https://example.org/x"}""")))
    }

    // ---------------------------------------------------------------- G3 Farbe

    @Test
    fun blockFarbeNurPaletteUndErlaubteTypen() {
        assertEquals(Gestalten.Style("red", true), Gestalten.style(obj("""{"type":"paragraph","tunes":{"hkstyle":{"c":"red","bg":true}}}""")))
        assertEquals(Gestalten.Style("blue", false), Gestalten.style(obj("""{"type":"header","tunes":{"hkstyle":{"c":"blue"}}}""")))
        assertNull(Gestalten.style(obj("""{"type":"paragraph","tunes":{"hkstyle":{"c":"#ff0000"}}}""")))
        assertNull(Gestalten.style(obj("""{"type":"image","tunes":{"hkstyle":{"c":"red"}}}""")))
        assertNull(Gestalten.style(obj("""{"type":"paragraph","tunes":{"hkstyle":"red"}}""")))
        for (t in listOf("paragraph", "header", "list", "quote", "toggle", "callout", "checklist")) assertTrue(Gestalten.canStyle(obj("""{"type":"$t"}""")))
    }

    @Test
    fun farbwerteLesbarHellUndDunkel() {
        fun lum(argb: Long): Double {
            fun ch(s: Int): Double {
                val v = ((argb shr s) and 0xFF) / 255.0
                return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
            }
            return 0.2126 * ch(16) + 0.7152 * ch(8) + 0.0722 * ch(0)
        }
        fun contrast(a: Long, b: Long): Double {
            val la = lum(a) + 0.05; val lb = lum(b) + 0.05
            return maxOf(la, lb) / minOf(la, lb)
        }
        val light = 0xFFFBF9F5L; val dark = 0xFF0F141CL                    // Seitenflaeche hell/dunkel (Theme.kt)
        for (c in Gestalten.PALETTE) {
            assertTrue("$c hell", contrast(Gestalten.textArgb(c, false), light) >= 4.5)
            assertTrue("$c dunkel", contrast(Gestalten.textArgb(c, true), dark) >= 4.5)
            assertTrue((Gestalten.bgArgb(c, false) ushr 24) in 1..254)        // durchscheinend
        }
        assertEquals(Gestalten.textArgb("gray", false), Gestalten.textArgb("unbekannt", false))
    }

    // ---------------------------------------------------------------- G10 / R

    @Test
    fun toggleEbene() {
        assertEquals(0, Gestalten.toggleLevel(obj("""{}""")))
        assertEquals(2, Gestalten.toggleLevel(obj("""{"level":2}""")))
        assertEquals(3, Gestalten.toggleLevel(obj("""{"level":7}""")))
        assertEquals(0, Gestalten.toggleLevel(obj("""{"level":-1}""")))
        assertEquals(0, Gestalten.toggleLevel(obj("""{"level":"2"}""")))
        assertEquals(3, Gestalten.toggleLevel(obj("""{"level":1e300}""")))      // riesige Werte: begrenzt, nicht abgeschnitten
        assertEquals(0, Gestalten.toggleLevel(obj("""{"level":-1e300}""")))
    }

    @Test
    fun rueckfallKarteWebLink() {
        assertEquals("https://s.example.org/notes#12:ab%2Fc d".replace(" ", "%20"), Gestalten.webUrl("https://s.example.org/notes", 12, "ab/c d"))
        assertEquals("https://s.example.org/notes#12", Gestalten.webUrl("https://s.example.org/notes", 12, null))
        assertEquals("https://s.example.org/notes#12", Gestalten.webUrl("https://s.example.org/notes", 12, ""))
        // unbekannter Block bleibt im Dokument unveraendert (Regel 0)
        val doc = BlockDoc.parse(page, "blocks").doc
        assertEquals(obj("""{"id":"x1","type":"widget","data":{"q":1}}"""), (doc["blocks"] as JsonArray)[5])
    }
}
