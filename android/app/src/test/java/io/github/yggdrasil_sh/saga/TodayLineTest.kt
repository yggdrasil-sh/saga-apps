package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.data.TlQuiet
import io.github.yggdrasil_sh.saga.data.TlTarget
import io.github.yggdrasil_sh.saga.data.TodayLine
import io.github.yggdrasil_sh.saga.data.TodayLineFetch
import io.github.yggdrasil_sh.saga.data.TodayLineNav
import io.github.yggdrasil_sh.saga.data.TodayLineShow
import io.github.yggdrasil_sh.saga.data.TodayWidgetSize
import io.github.yggdrasil_sh.saga.widget.TodaySnapshot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2.6 Tageszeile (Bugs-DB 2271 K-D, data/TodayLine.kt) gegen die Server-Vektoren vectors/today_vectors.json (Kopie von
 * tests/today_vectors.json, Format saga-today/v1): jede `response` decodieren und die Anzeige gegen `show` pruefen; dazu
 * 304, alter Server (404), Bearer 403, Ziele, Live, Pfade, Widget (Mittel/Gross/Klein/Sperre), Snapshot.
 */
class TodayLineTest {
    private fun res(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()
    private val vec: JsonObject by lazy { Json.parseToJsonElement(res("vectors/today_vectors.json")).jsonObject }
    private val cases: List<JsonObject> get() = vec["cases"]!!.jsonArray.map { it.jsonObject }
    private fun case(id: String) = cases.first { (it["id"] as JsonPrimitive).content == id }
    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
    private fun text(e: JsonElement?): String? = e?.takeIf { it !is JsonNull }?.toString()

    /** show-Feld des Vektors mit der Anzeige vergleichen (Texte unveraendert vom Server) */
    private fun same(got: TodayLineShow, want: JsonObject, id: String) {
        val k = want["kurz"] as? JsonObject
        if (k != null) {
            assertEquals("$id label", k.str("label"), got.kurz?.label)
            assertEquals("$id Teile", (k["parts"] as JsonArray).map { (it as JsonPrimitive).content }, got.kurz?.parts?.map { it.text })
            assertEquals("$id a11y", k.str("a11y"), got.kurz?.a11y)
        } else assertNull("$id keine Zeile", got.kurz)
        val p = want["pill"] as? JsonObject
        if (p != null) {
            assertEquals("$id Pille", p.str("text"), got.pill?.text)
            assertEquals("$id Wieder an", p.str("undo"), got.pill?.undo)
        } else assertNull("$id keine Pille", got.pill)
        val a = want["ask"] as? JsonObject
        if (a != null) {
            assertEquals("$id Frage", listOf(a.str("text"), a.str("yes"), a.str("no")), listOf(got.ask?.text, got.ask?.yes, got.ask?.no))
        } else assertNull("$id keine Frage", got.ask)
        assertEquals("$id Wartet auf dich", want.str("waiting"), got.waiting?.text)
    }

    @Test fun vectorsFormat() {
        assertEquals("saga-today/v1", vec.str("format"))
        assertTrue(cases.size >= 21)
    }

    @Test fun allVectorsShow() {
        val normal = TodayLine.parse(text(case("normal")["response"]))
        assertEquals((case("normal")["headers"] as JsonObject).str("ETag"), TodayLine.etag(normal?.v))
        var n = 0
        for (c in cases) {
            val id = c.str("id")!!
            val req = c["request"]!!.jsonObject
            val status = (c["status"] as JsonPrimitive).intOrNull!!
            val show = c["show"]!!.jsonObject
            if (req.str("path")!!.startsWith("/api/app/today")) { n++; continue }      // widgetVectors
            val r = TodayLine.result(status, text(c["response"]))
            val st = when (r) {
                is TodayLineFetch.State -> r.s
                TodayLineFetch.Keep -> normal                                             // 304: bisheriger Stand bleibt
                TodayLineFetch.Empty -> null
            }
            if (status == 304) assertEquals(id, TodayLineFetch.Keep, r)
            if (status >= 400) assertEquals(id, TodayLineFetch.Empty, r)
            val got = TodayLine.show(st)
            same(got, show, id)
            // sentence nie zeigen
            (c["response"] as? JsonObject)?.str("sentence")?.let { sen ->
                assertFalse("$id sentence", got.kurz?.parts?.any { it.text == sen } == true || got.kurz?.a11y == sen)
            }
            val etag = (c["headers"] as? JsonObject)?.str("ETag")
            if (etag != null && status == 200) assertEquals("$id ETag", etag, TodayLine.etag(st?.v))
            n++
        }
        assertEquals(cases.size, n)
    }

    @Test fun widgetVectors() {
        var n = 0
        for (c in cases.filter { it["request"]!!.jsonObject.str("path")!!.startsWith("/api/app/today") }) {
            val id = c.str("id")!!
            val show = c["show"]!!.jsonObject
            val l = TodayLine.widgetLine(text(c["response"]))
            assertEquals("$id mittel", show.str("medium"), TodayLine.widgetText(l, TodayWidgetSize.MEDIUM))
            assertEquals("$id gross", show.str("medium"), TodayLine.widgetText(l, TodayWidgetSize.LARGE))
            assertEquals("$id klein nur Zahlen", "counts", show.str("small"))
            assertNull("$id klein", TodayLine.widgetText(l, TodayWidgetSize.SMALL))
            assertEquals("$id Sperre nur Zahlen", "counts", show.str("lock"))
            assertNull("$id Sperre", TodayLine.widgetText(l, TodayWidgetSize.LOCK))
            assertEquals("$id wartet (gross)", show.str("waiting"), TodayLine.widgetWaiting(l, TodayWidgetSize.LARGE))
            assertNull("$id wartet nicht mittel", TodayLine.widgetWaiting(l, TodayWidgetSize.MEDIUM))
            if (l?.enabled == true) assertNotNull("$id counts", l.counts)
            n++
        }
        assertEquals(4, n)
        assertNull(TodayLine.widgetLine("""{"date":"2026-07-07","events":[],"tasks":[]}"""))
        assertNull(TodayLine.widgetText(TodayLine.widgetLine("""{"line":"x"}"""), TodayWidgetSize.MEDIUM))
        assertNull(TodayLine.widgetLine("kaputt"))
    }

    @Test fun widgetSizes() {
        assertEquals(TodayWidgetSize.SMALL, TodayLine.widgetSize(150f, 150f))
        assertEquals(TodayWidgetSize.SMALL, TodayLine.widgetSize(300f, 80f))
        assertEquals(TodayWidgetSize.MEDIUM, TodayLine.widgetSize(300f, 150f))
        assertEquals(TodayWidgetSize.LARGE, TodayLine.widgetSize(300f, 300f))
    }

    @Test fun snapshotKeepsOldFormat() {
        val j = Json { ignoreUnknownKeys = true }
        val old = j.decodeFromString(TodaySnapshot.serializer(), """{"day":"2026-07-07","fetchedAt":1}""")
        assertNull(old.line)
        val s = TodaySnapshot(day = "2026-07-07", fetchedAt = 1, line = "a · b", waiting = "1 Auftrag wartet auf dich ›")
        assertEquals(s, j.decodeFromString(TodaySnapshot.serializer(), j.encodeToString(TodaySnapshot.serializer(), s)))
    }

    @Test fun fieldsNotTexts() {
        assertEquals(TodayLineFetch.Empty, TodayLine.result(200, """{"enabled": false}"""))
        assertEquals(TodayLineFetch.Empty, TodayLine.result(500, text(case("normal")["response"])))
        assertEquals(TodayLineFetch.Empty, TodayLine.result(200, "kaputt"))
        assertEquals(TodayLineFetch.Keep, TodayLine.result(304, null))
        val normal = TodayLine.parse(text(case("normal")["response"]))!!
        val aus = TodayLine.show(normal.copy(on = false))
        assertNull(aus.kurz)
        assertNotNull(aus.waiting)
        val both = TodayLine.show(normal.copy(quiet = TlQuiet(null, "x", "y")))
        assertNull(both.kurz)
        assertEquals("x", both.pill?.text)
        val u = TodayLine.parse(
            """{"enabled":true,"on":true,"label":"L","ideas":[1],"neu":{"x":1},"line":{"text":"a · b","parts":[{"kind":"x","text":"a"},{"text":5},{"kind":"due","text":"b","target":{"type":"zukunft","id":1}}]}}""",
        )!!
        assertEquals(2, u.line!!.parts.size)
        assertNull(TodayLine.nav(u.line!!.parts[0].target))
        assertNull(TodayLine.nav(u.line!!.parts[1].target))
    }

    @Test fun targetsOpenOnly() {
        val normal = TodayLine.parse(text(case("normal")["response"]))!!
        assertEquals(
            listOf(TodayLineNav.Events("2026-07-07"), TodayLineNav.Task(7), TodayLineNav.Row(512)),
            normal.line!!.parts.map { TodayLine.nav(it.target) },
        )
        assertEquals(TodayLineNav.Jobs("wartet_auf_dich"), TodayLine.nav(normal.waiting!!.target))
        assertEquals(TodayLineNav.Tasks("overdue"), TodayLine.nav(TlTarget("tasks", filter = "overdue")))
        assertNull(TodayLine.nav(TlTarget("task")))
    }

    @Test fun liveAndPaths() {
        val live = vec["live"]!!.jsonObject
        assertEquals("ai.today.changed", live.str("event"))
        assertEquals(TodayLine.DEBOUNCE_MS, (live["debounce_ms"] as JsonPrimitive).content.toLong())
        val lv = live["data"]!!.jsonObject.str("v")
        assertFalse(TodayLine.liveNeedsReload(lv, TodayLine.parse(text(case("normal")["response"]))?.v))
        assertTrue(TodayLine.liveNeedsReload(lv, "000000000000"))
        assertTrue(TodayLine.liveNeedsReload(null, "x"))
        assertEquals("api/ai/today?tz=Europe/Berlin", TodayLine.path("Europe/Berlin"))
        assertEquals("api/ai/today/quiet?tz=Etc/GMT%2B1", TodayLine.quietPath("Etc/GMT+1"))
        assertEquals("api/app/today?sources=1&line=1&tz=America/Argentina/Buenos_Aires", TodayLine.widgetPath("America/Argentina/Buenos_Aires"))
        assertEquals("""{"on":false,"asked":true}""", TodayLine.askBody(true).toString())
        assertEquals("""{"asked":true}""", TodayLine.askBody(false).toString())
        assertFalse(TodayLine.shouldFetch(setOf("tasksrc1")))
        assertTrue(TodayLine.shouldFetch(setOf("aitoday1")))
    }
}
