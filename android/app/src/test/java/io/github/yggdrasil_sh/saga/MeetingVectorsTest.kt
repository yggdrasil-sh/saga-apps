package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.call.Meeting
import io.github.yggdrasil_sh.saga.call.SpeakerPick
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Meeting-Ansicht (MEETING-SPEC.md §12): gemeinsame Vektoren meeting_vectors.json - Personen/Kacheln (S2, S3, S9 …),
 * Layoutwahl, Galerie-Raster, Streifen, Groesse des eigenen Bilds, Leisten-Ausblenden und Sprecher-Hysterese (S5).
 */
class MeetingVectorsTest {
    private val root: JsonObject = Json.parseToJsonElement(
        javaClass.classLoader!!.getResourceAsStream("meeting_vectors.json")!!.bufferedReader().readText(),
    ).jsonObject

    private fun JsonObject.s(k: String) = this[k]?.jsonPrimitive?.contentOrNull.orEmpty()
    private fun JsonObject.b(k: String) = this[k]?.jsonPrimitive?.booleanOrNull == true
    private fun JsonObject.sn(k: String) = (this[k] as? JsonNull)?.let { null } ?: this[k]?.jsonPrimitive?.contentOrNull

    private fun devs(a: JsonArray) = a.map { it.jsonObject }.map { o ->
        Meeting.Dev(
            who = o.s("who"), name = o.s("name"), device = o.s("device"), devName = o.s("dev_name"),
            companion = o.s("role") == "companion", me = o.b("me"), mic = o.b("mic"), cam = o.b("cam"), screen = o.b("screen"),
            sharedAt = o["shared_at"]?.jsonPrimitive?.longOrNull ?: 0,
        )
    }

    private fun fmt(t: Meeting.Tile) = "${t.id}|${t.kind.key}|${t.device}|${if (t.video) 1 else 0}" + if (t.devName.isNotEmpty()) "|${t.devName}" else ""

    @Test
    fun meetingTiles() {
        val list = root["meetingTiles"]!!.jsonArray
        assertTrue(list.size >= 8)
        for (v in list.map { it.jsonObject }) {
            val n = v.s("name")
            val e = v["expect"]!!.jsonObject
            val m = Meeting.tiles(devs(v["legs"]!!.jsonArray), v.s("my_device"))
            assertEquals(n, e["people"]!!.jsonArray.map { it.jsonPrimitive.content }, m.people.map(::fmt))
            assertEquals(n, e["screens"]!!.jsonArray.map { it.jsonPrimitive.content }, m.screens.map(::fmt))
            assertEquals(n, e["companions"]!!.jsonArray.map { it.jsonPrimitive.content }, m.companions)
            assertEquals(n, e["persons"]!!.jsonPrimitive.int, m.persons)
            assertEquals(n, e.b("self_sharing"), m.selfSharing)
            val view = Meeting.pickLayout(Meeting.Pref.AUTO, m)
            assertEquals(n, e.s("layout"), view.key)
            e.sn("layout_reduced")?.let { assertEquals(n, it, Meeting.pickLayout(Meeting.Pref.AUTO, m, reducedMotion = true).key) }
            assertEquals(n, e.b("layout_button"), Meeting.layoutButton(m))
            assertEquals(n, e.b("self_floats"), Meeting.selfFloats(view, m))
            val st = Meeting.stage(view, m, v.sn("pin"), v.sn("speaker"))
            assertEquals(n, e.sn("stage"), st.stage?.id)
            if (view != Meeting.View.GALLERY) assertEquals(n, e["strip"]!!.jsonArray.map { it.jsonPrimitive.content }, st.strip.map { it.id })
        }
    }

    @Test
    fun speakerHysteresis() {
        val list = root["speakerHysteresis"]!!.jsonArray
        assertTrue(list.size >= 4)
        for (v in list.map { it.jsonObject }) {
            val p = SpeakerPick(v.s("self"))
            for (s in v["steps"]!!.jsonArray.map { it.jsonObject }) {
                val t = s["t"]!!.jsonPrimitive.long
                val lv = s["levels"]!!.jsonObject.mapValues { it.value.jsonPrimitive.double }
                val got = p.feed(lv, t)
                assertEquals("${v.s("name")} t=$t", s.sn("stage"), got)
            }
        }
    }

    @Test
    fun gridAndStrip() {
        for (g in root["grid"]!!.jsonArray.map { it.jsonArray }) {
            val r = Meeting.grid(g[0].jsonPrimitive.float, g[1].jsonPrimitive.float, g[2].jsonPrimitive.int)
            val e = g[3].jsonArray.map { it.jsonPrimitive.int }
            assertEquals("grid $g", e, listOf(r.cols, r.perPage, r.videos))
        }
        for (g in root["strip"]!!.jsonArray.map { it.jsonArray }) {
            val r = Meeting.strip(g[0].jsonPrimitive.float, g[1].jsonPrimitive.float)
            val e = g[2].jsonArray
            assertEquals("strip $g", e[0].jsonPrimitive.content, r.side.name.lowercase())
            assertEquals("strip $g", e[1].jsonPrimitive.float, r.size, 0.01f)
            assertEquals("strip $g", e[2].jsonPrimitive.int, r.videos)
        }
    }

    @Test
    fun selfPipSize() {
        for (v in root["selfPipSize"]!!.jsonArray.map { it.jsonObject }) {
            val (w, h) = Meeting.selfPipSize(
                v["aspect"]?.jsonPrimitive?.doubleOrNull?.toFloat(), v["fallback"]!!.jsonPrimitive.float, v["width"]!!.jsonPrimitive.float,
                v["avail"]!!.jsonPrimitive.float, v["factor"]!!.jsonPrimitive.float,
            )
            val e = v["expect"]!!.jsonArray.map { it.jsonPrimitive.float }
            assertEquals("pip $v", e[0], w, 0.01f)
            assertEquals("pip $v", e[1], h, 0.01f)
        }
    }

    @Test
    fun hideControls() {
        for (v in root["hideControls"]!!.jsonArray.map { it.jsonObject }) {
            assertEquals(
                "hide $v", v["hide"]!!.jsonPrimitive.boolean,
                Meeting.hideControls(v["idle"]!!.jsonPrimitive.long, v.b("connected"), v.b("video"), v.b("sr"), v.b("menu"), v.b("notice"), v.b("over")),
            )
        }
    }

    // ---------------------------------------------------------------- weitere Regeln (nur Android)

    @Test
    fun galerieReihenfolgeUndSeiten() {
        val d = listOf(
            Meeting.Dev("zoe", "Zoe", "z1"), Meeting.Dev("ben", "Ben", "b1", cam = true), Meeting.Dev("al", "Al", "a1"),
            Meeting.Dev("cem", "Cem", "c1"), Meeting.Dev("me", "Ich", "m1", me = true, cam = true),
        )
        val m = Meeting.tiles(d, "m1")
        val o = Meeting.galleryOrder(m, pin = null, spokeAt = mapOf("cem" to 9_000L), now = 10_000L)
        assertEquals(listOf("ben", "cem", "al", "zoe", "me"), o.map { it.id })       // Video, Sprecher 30 s, alphabetisch, ich
        val p = Meeting.galleryOrder(m, pin = "zoe", spokeAt = emptyMap(), now = 0)
        assertEquals("zoe", p.first().id)
        assertEquals(listOf(listOf(1, 2), listOf(3)), Meeting.pages(listOf(1, 2, 3), 2))
        assertEquals(1, Meeting.pages(emptyList<Int>(), 6).size)
    }

    @Test
    fun videoVorrang() {
        val d = (1..6).map { Meeting.Dev("p$it", "P$it", "d$it", cam = true) } + Meeting.Dev("me", "Ich", "m", me = true, cam = true)
        val m = Meeting.tiles(d, "m")
        val v = Meeting.videoSlots(m.people, 4, pin = "p6", active = "p5", spokeAt = mapOf("p3" to 5L))
        assertEquals(setOf("p6", "p5", "p3", "p1", "me"), v)                        // eigenes Bild zaehlt nicht mit
    }

    @Test
    fun sprechringMitNachlauf() {
        val p = SpeakerPick("me")
        p.feed(mapOf("a" to 0.2), 0)
        assertFalse(p.speaking("a", 0))
        p.feed(mapOf("a" to 0.2), 330)
        assertTrue(p.speaking("a", 330))
        p.feed(mapOf("a" to 0.0), 660)
        assertTrue(p.speaking("a", 660))                                            // Nachlauf 600 ms
        p.feed(mapOf("a" to 0.0), 1320)
        assertFalse(p.speaking("a", 1320))
    }

    @Test
    fun layoutEinstellung() {
        val m = Meeting.tiles(listOf(Meeting.Dev("a", "A", "a1"), Meeting.Dev("me", "Ich", "m", me = true)), "m")
        assertEquals(Meeting.View.GALLERY, Meeting.pickLayout(Meeting.Pref.GALLERY, m))
        assertEquals(Meeting.View.SPEAKER, Meeting.pickLayout(Meeting.Pref.PRESENTATION, m))      // niemand teilt
        assertEquals(Meeting.Pref.GALLERY, Meeting.Pref.of("grid"))                              // alter Wert
        assertEquals(Meeting.Pref.AUTO, Meeting.Pref.of(null))
        assertEquals(1f, Meeting.zoom(0.5f))
        assertEquals(4f, Meeting.zoom(9f))
        assertEquals(50f, Meeting.pan(80f, 2f, 100f))
    }
}
