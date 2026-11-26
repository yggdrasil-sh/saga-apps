package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.call.Meeting
import io.github.yggdrasil_sh.saga.call.SpeakerPick
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
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
 * Meeting-Ansicht (MEETING-SPEC.md §12): gemeinsame Vektoren des Servers (`tests/meeting_vectors.json`, unveraendert als
 * meeting_vectors.json uebernommen) - Kacheln (S1-S9, §4.1, §4.2 beide Richtungen), Layoutwahl, Buehne/Streifen/Raster,
 * Galerie-Raster, Seiten, Streifen, Sprecher-Hysterese, Leisten-Ausblenden und Groesse des eigenen Bilds.
 *
 * Seit Bugs-DB 2161 ohne Abweichungen: die Datei ist die eine Wahrheit fuer Web, iOS und Android (auch Layoutwahl je
 * Kachelfall `choice`, eigenes Bild nach der Kamera, Streifen 160 in der Praesentation bis 1440 dp).
 */
class MeetingVectorsTest {
    private val root: JsonObject = Json.parseToJsonElement(
        javaClass.classLoader!!.getResourceAsStream("meeting_vectors.json")!!.bufferedReader().readText(),
    ).jsonObject

    private fun JsonObject.s(k: String) = this[k]?.jsonPrimitive?.contentOrNull.orEmpty()
    private fun JsonObject.b(k: String) = this[k]?.jsonPrimitive?.booleanOrNull == true
    private fun JsonObject.sn(k: String) = (this[k] as? JsonNull)?.let { null } ?: this[k]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.strs(k: String) = this[k]!!.jsonArray.map { it.jsonPrimitive.content }
    private fun list(k: String) = root[k]!!.jsonArray.map { it.jsonObject }

    // ---------------------------------------------------------------- Kacheln

    /** roster (wie GET /api/calls/{id}) + media je Geraet -> Geraete der App */
    private fun devs(v: JsonObject): List<Meeting.Dev> {
        val media = v["media"]?.jsonObject
        return v["roster"]!!.jsonArray.map { it.jsonObject }.map { o ->
            val dev = o.s("device")
            val m = media?.get(dev)?.jsonObject ?: JsonObject(emptyMap())
            Meeting.Dev(
                who = o.s("who").ifEmpty { dev }, name = o.s("name"), device = dev, devName = o.s("dev_name"),
                companion = o.s("role") == "companion", me = o.b("me"), mic = m.b("mic"), cam = m.b("cam"), screen = m.b("screen"),
                level = m["level"]?.jsonPrimitive?.doubleOrNull ?: 0.0, sharedAt = m["screenAt"]?.jsonPrimitive?.longOrNull ?: 0,
            )
        }
    }

    private fun fmt(id: String, kind: String, dev: String, video: Boolean, devName: String) = "$id|$kind|$dev|${if (video) 1 else 0}|$devName"
    private fun fmt(t: Meeting.Tile) = fmt(t.id, t.kind.key, t.device, t.video, t.devName)
    private fun fmtE(e: JsonElement) = e.jsonObject.let { fmt(it.s("id"), it.s("kind"), it.s("dev"), it.b("video"), it.s("devName")) }

    /** Modell wie meetingTiles im Web; prefs.showNoVideo = false blendet wie MeetingLayer aus (Meeting.keepNoVideo) */
    private fun model(v: JsonObject): Meeting.Model {
        val prefs = v["prefs"]?.jsonObject ?: JsonObject(emptyMap())
        val m = Meeting.tiles(devs(v), prefs.s("self"))
        if (prefs["showNoVideo"]?.jsonPrimitive?.booleanOrNull != false) return m
        val spoke = prefs["spokeAt"]?.jsonObject?.mapValues { it.value.jsonPrimitive.long }.orEmpty()
        val now = prefs["now"]?.jsonPrimitive?.longOrNull ?: 0
        return m.copy(people = m.people.filter { Meeting.keepNoVideo(it, spoke, now) })
    }

    @Test
    fun meetingTiles() {
        val cases = list("meetingTiles")
        assertTrue(cases.size >= 17)
        for (v in cases) {
            val n = v.s("name")
            val m = model(v)
            // Web-Reihenfolge: Bildschirme (aelteste zuerst), Personen in Listenfolge, eigene Person zuletzt; die App haelt
            // die Bildschirme neueste zuerst (Buehne) - gleiche Zeit bleibt in Listenfolge
            val screensOldest = m.screens.withIndex().sortedWith(compareBy<IndexedValue<Meeting.Tile>> { it.value.sharedAt }.thenBy { it.index }).map { it.value }
            assertEquals(n, v["expect"]!!.jsonArray.map(::fmtE), (screensOldest + m.people).map(::fmt))
            assertEquals(n, v["roster"]!!.jsonArray.map { it.jsonObject.s("who") }.distinct().size, m.persons)
            v["hidden"]?.let { h ->
                val all = Meeting.tiles(devs(v), v["prefs"]!!.jsonObject.s("self")).people.map { it.who }.distinct()
                assertEquals(n, h.jsonArray.map { it.jsonPrimitive.content }, all.filter { w -> m.people.none { it.who == w } })
            }
            val view = Meeting.pickLayout(Meeting.Pref.entries.firstOrNull { it.key == v.s("choice") } ?: Meeting.Pref.AUTO, m)
            assertEquals(n, v.s("layout"), view.key)
            val a = v["arrange"]!!.jsonObject
            val pin = v.sn("pin")
            if (view == Meeting.View.GALLERY) {
                val prefs = v["prefs"]?.jsonObject
                val spoke = prefs?.get("spokeAt")?.jsonObject?.mapValues { it.value.jsonPrimitive.long }.orEmpty()
                assertEquals(n, a.strs("grid"), Meeting.galleryOrder(m, pin, spoke, prefs?.get("now")?.jsonPrimitive?.longOrNull ?: 0).map { it.id })
                assertEquals(n, null, a.sn("stage"))
                assertFalse(n, Meeting.selfFloats(view, m))
                continue
            }
            val st = Meeting.stage(view, m, pin, v.sn("speaker"))
            assertEquals(n, a.sn("stage"), st.stage?.id)
            assertEquals(n, a.strs("strip"), st.strip.map { it.id })
            assertEquals(n, a.b("float"), Meeting.selfFloats(view, m))
        }
    }

    // ---------------------------------------------------------------- Layout, Raster, Seiten, Streifen

    private fun countModel(people: Int, screens: Int) = Meeting.Model(
        people = (1..people).map { Meeting.Tile("p$it", Meeting.Kind.PERSON, "p$it", "d$it", "P$it", me = it == people, video = true) },
        screens = (1..screens).map { Meeting.Tile("s$it|d$it:scr", Meeting.Kind.SCREEN, "s$it", "d$it", "S$it", video = true, sharedAt = it.toLong()) },
        companions = emptyList(), selfSharing = false, persons = people,
    )

    @Test
    fun pickLayout() {
        val cases = list("pickLayout")
        assertTrue(cases.size >= 8)
        for (v in cases) {
            val n = v.s("name")
            // unbekannte Einstellung = Auto (Pref.of liest dagegen gespeicherte Altwerte, "grid" -> Galerie)
            val pref = Meeting.Pref.entries.firstOrNull { it.key == v.s("choice") } ?: Meeting.Pref.AUTO
            val got = Meeting.pickLayout(pref, countModel(v["people"]!!.jsonPrimitive.int, v["screens"]!!.jsonPrimitive.int), v.b("reduced")).key
            assertEquals(n, v.s("expect"), got)
        }
    }

    @Test
    fun galleryGridAndPages() {
        for (v in list("galleryGrid")) {
            val w = v["w"]!!.jsonPrimitive.float
            val h = v["h"]!!.jsonPrimitive.float
            val cnt = v["n"]!!.jsonPrimitive.int
            val key = "${w.toInt()}x${h.toInt()} $cnt"
            val g = Meeting.grid(w, h, cnt)
            assertEquals("grid $key", listOf(v["cols"]!!.jsonPrimitive.int, v["perPage"]!!.jsonPrimitive.int, v["video"]!!.jsonPrimitive.int), listOf(g.cols, g.perPage, g.videos))
        }
        for (v in list("pages")) {
            val pp = Meeting.pages(List(v["n"]!!.jsonPrimitive.int) { it }, v["perPage"]!!.jsonPrimitive.int)
            val page = v["page"]!!.jsonPrimitive.int.coerceIn(0, pp.size - 1)          // Seite wird begrenzt (MeetingLayer)
            assertEquals("pages $v", v["pages"]!!.jsonPrimitive.int, pp.size)
            assertEquals("pages $v", v["items"]!!.jsonPrimitive.int, pp[page].size)
            v["clamped"]?.let { assertEquals("pages $v", it.jsonPrimitive.int, page) }
        }
    }

    @Test
    fun stripSide() {
        for (v in list("stripSide")) {
            val w = v["w"]!!.jsonPrimitive.float
            val h = v["h"]!!.jsonPrimitive.float
            val key = "${w.toInt()}x${h.toInt()} ${v.s("layout")}"
            val r = Meeting.strip(w, h, v.s("layout") == "presentation")
            assertEquals("strip $key", v.s("side"), r.side.name.lowercase())
            assertEquals("strip $key", v["size"]!!.jsonPrimitive.float, r.size, 0.01f)
        }
    }

    // ---------------------------------------------------------------- Sprecher, Leisten, eigenes Bild

    @Test
    fun speakerHysteresis() {
        val cases = list("speakerHysteresis")
        assertTrue(cases.size >= 5)
        for (v in cases) {
            val n = v.s("name")
            val p = SpeakerPick(v.s("me"))
            val tones = v["tones"]!!.jsonArray.map { it.jsonObject }
            val step = v["step"]!!.jsonPrimitive.long
            val at = HashMap<Long, String?>()
            var prev: String? = null
            var changes = 0
            var t = 0L
            while (t <= v["until"]!!.jsonPrimitive.long) {
                // Ton = level zwischen from und to (ohne to), sonst kein Eintrag
                val lv = HashMap<String, Double>()
                for (x in tones) if (t >= x["from"]!!.jsonPrimitive.long && t < x["to"]!!.jsonPrimitive.long) {
                    lv[x.s("who")] = maxOf(lv[x.s("who")] ?: 0.0, x["level"]!!.jsonPrimitive.double)
                }
                val s = p.feed(lv, t)
                if (s != prev) { changes++; prev = s }
                at[t] = s
                t += step
            }
            for (e in v["expect"]!!.jsonArray.map { it.jsonObject }) {
                val et = e["t"]!!.jsonPrimitive.long
                assertEquals("$n t=$et", e.sn("stage"), at[et])
            }
            assertEquals("$n Wechsel", v["changes"]!!.jsonPrimitive.int, changes)
        }
    }

    @Test
    fun hideControls() {
        for (v in list("hideControls")) {
            assertEquals(
                "hide $v", v.b("expect"),
                Meeting.hideControls(if (v.b("idle")) Meeting.IDLE_MS else 0, v.s("phase") == "connected", v.b("video"), v.b("sr"), v.b("menu"), v.b("notice"), v.b("over")),
            )
        }
    }

    @Test
    fun selfPipSize() {
        val cases = list("selfPipSize")
        assertTrue(cases.size >= 13)
        for (v in cases) {
            val w = v["w"]!!.jsonPrimitive.double
            val h = v["h"]!!.jsonPrimitive.double
            val ar = v["ar"]!!.jsonPrimitive.double
            val size = v.s("size")
            val key = "${w.toInt()}x${h.toInt()} ${v["ar"]!!.jsonPrimitive.content} $size"
            val factor = Meeting.SIZE_FACTORS["SML".indexOf(size).let { if (it < 0) 1 else it }].toDouble()
            // ar 0 = unbekannt: Lage der Buehne, Verhaeltnis 16:9 bzw. 9:16 (fallback 0); avail = Hoehe der Buehne
            val (pw, ph) = Meeting.selfPipSizeD(ar.takeIf { it > 0 }, 0.0, w, h, factor)
            val e = v["expect"]!!.jsonArray.map { it.jsonPrimitive.double }
            assertEquals("pip $key", e[0], pw, 0.01)
            assertEquals("pip $key", e[1], ph, 0.01)
        }
        // Float-Huelle (MeetingUi) rechnet gleich
        assertEquals(120f to 90f, Meeting.selfPipSize(4f / 3f, 9f / 16f, 390f, 844f))
    }

    /** Bugs-DB 2125: Sprechring nur ab 3 verschiedenen Personen, Begleitgeraete zaehlen nicht (gemeinsame Vektoren) */
    @Test
    fun speakRing() {
        val cases = list("speakRing")
        assertTrue(cases.size >= 12)
        for (v in cases) {
            val ros = v["roster"]!!.jsonArray.map { it.jsonObject }.map { o ->
                Meeting.RingMember(o.s("device"), o.sn("who"), companion = o.s("role") == "companion", me = o.b("me"))
            }
            assertEquals("speakRing ${v.s("name")}", v.b("expect"), Meeting.speakRingOn(ros, v.sn("self")))
        }
    }

    /** 2125: Ablauf in der Meeting-Ansicht (Geraete wie CallManager.meetDevs, Model.ring aus Meeting.tiles) */
    @Test
    fun speakRingAblauf() {
        val me = Meeting.Dev("erik", "Erik", "me", "Pixel", me = true, mic = true)
        val comp = Meeting.Dev("erik", "Erik", "me2", "Tablet", companion = true, me = true)
        val anna = Meeting.Dev("anna", "Anna", "a1", "Laptop", mic = true, speaking = true)
        val annaComp = Meeting.Dev("anna", "Anna", "a2", "iPhone", companion = true)
        val ben = Meeting.Dev("ben", "Ben", "b1", "Laptop", mic = true)
        assertFalse("1:1", Meeting.tiles(listOf(anna, me), "me").ring)
        assertFalse("1:1 + eigenes Begleitgeraet", Meeting.tiles(listOf(anna, me, comp), "me").ring)
        assertFalse("1:1 + Begleitgeraet der anderen Person", Meeting.tiles(listOf(anna, annaComp, me), "me").ring)
        assertTrue("dritte Person kommt", Meeting.tiles(listOf(anna, ben, me, comp), "me").ring)
        assertFalse("dritte Person geht", Meeting.tiles(listOf(anna, me, comp), "me").ring)
        // Sprecher-Erkennung bleibt unabhaengig vom Ring (Symbol, TalkBack, Liste)
        assertTrue(Meeting.tiles(listOf(anna, me), "me").people.first { it.who == "anna" }.speaking)
        // dieses Geraet ist selbst das Begleitgeraet: zaehlt trotzdem als die eigene Person
        val here = Meeting.Dev("erik", "Erik", "me", "Pixel", companion = true, me = true)
        assertTrue(Meeting.speakRingOn(listOf(here, anna, ben)))
        assertFalse(Meeting.speakRingOn(listOf(here, anna)))
    }

    // ---------------------------------------------------------------- weitere Regeln (nur Android)

    /** §4.2 auf dem Hauptgeraet: Begleitgeraet im Chip, teilt es, kommt sein Bildschirm; eigene Freigabe = rote Leiste */
    @Test
    fun eigeneGeraeteChipUndLeiste() {
        val v = list("meetingTiles").first { it.s("name").startsWith("§4.2 auf dem eigenen Hauptgeraet") }
        val m = model(v)
        assertEquals(listOf("iPhone"), m.companions)
        assertFalse(m.selfSharing)
        assertEquals("iPhone", m.screens.single().devName)
        assertFalse(m.screens.single().me)                                           // Bild kommt vom Begleitgeraet
        assertTrue(Meeting.layoutButton(m))
        val own = list("meetingTiles").first { it.s("name").startsWith("§4.2 eigene Freigabe auf dem Begleitgeraet") }
        val mo = model(own)
        assertTrue(mo.selfSharing)
        assertTrue(mo.screens.isEmpty())
        assertEquals(listOf("MacBook"), mo.companions)
        // Bildschirm ueber den Knoten nicht abonnierbar: Kachel mit Hinweis statt Bild
        val na = Meeting.tiles(
            listOf(
                Meeting.Dev("a", "Anna", "a1", "Mac", me = true), Meeting.Dev("a", "Anna", "a2", "Pixel", companion = true, me = true, screen = true, screenNa = true),
                Meeting.Dev("b", "Ben", "b1"),
            ),
            "a1",
        )
        assertEquals(listOf("a|a2:scr|screen|a2|0|Pixel"), na.screens.map(::fmt))
    }

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
