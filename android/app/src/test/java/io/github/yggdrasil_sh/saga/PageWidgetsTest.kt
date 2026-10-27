package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.blocks.BlockDoc
import io.github.yggdrasil_sh.saga.blocks.PageWidgets
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.random.Random

/** Saga 2.5 Widgets in Seiten, Etappe 1 (SPEC-widgets 4): Lesen, Antwort, Uhr, Countdown, Fortschritt, Format, Zitat, Regel 0 */
@OptIn(ExperimentalCoroutinesApi::class)
class PageWidgetsTest {
    private fun obj(s: String) = Json.parseToJsonElement(s) as JsonObject
    private val berlin = ZoneId.of("Europe/Berlin")

    // ---------------------------------------------------------------- Block lesen

    @Test
    fun specStandardsUndGrenzen() {
        val s = PageWidgets.spec(obj("""{"id":"w1","type":"widget","data":{"v":1,"kind":"metric","cfg":{"agg":"count"}}}"""))
        assertEquals("metric", s.kind)
        assertEquals("m", s.size)
        assertEquals("card", s.style)
        assertNull(s.accent)
        assertEquals("", s.title)
        assertTrue(s.known)
        assertTrue(s.serverValue)
        val t = PageWidgets.spec(obj("""{"id":"w2","type":"widget","data":{"kind":"clock","size":"xl","style":"plain","accent":"lime","title":"${"x".repeat(100)}"}}"""))
        assertEquals("m", t.size)
        assertEquals("plain", t.style)
        assertNull(t.accent)                                                 // nur Palette
        assertEquals(80, t.title.length)
        assertFalse(t.serverValue)
        val u = PageWidgets.spec(obj("""{"id":"w3","type":"widget","data":{"kind":"chart","accent":"blue","size":"l"}}"""))
        assertFalse(u.known)                                                 // -> "neuere Version noetig"
        assertEquals("blue", u.accent)
        assertEquals("l", u.size)
    }

    @Test
    fun serverWerteNurFuerDbArten() {
        fun sv(kind: String, cfg: String) = PageWidgets.spec(obj("""{"id":"a","type":"widget","data":{"kind":"$kind","cfg":$cfg}}""")).serverValue
        assertTrue(sv("progress", """{"src":"db"}"""))
        assertFalse(sv("progress", """{"src":"fixed","value":1,"max":2}"""))
        assertTrue(sv("quote", """{"src":"db"}"""))
        assertTrue(sv("quote", """{"src":"fixed","items":["a"]}"""))            // Auswahl vom Server (wie Web)
        assertFalse(sv("countdown", """{}"""))
    }

    @Test
    fun widgetsInSpaltenUndToggles() {
        val doc = obj(
            """{"blocks":[{"id":"a","type":"widget","data":{"kind":"metric"}},""" +
                """{"id":"c","type":"columns","data":{"cols":[{"blocks":[{"id":"b","type":"widget","data":{"kind":"quote","cfg":{"src":"db"}}}]},""" +
                """{"blocks":[{"id":"t","type":"toggle","data":{"blocks":[{"id":"d","type":"widget","data":{"kind":"clock"}},{"id":"e","type":"widget","data":{"kind":"progress","cfg":{"src":"db"}}}]}}]}]}},""" +
                """{"id":"p","type":"paragraph","data":{"text":"x"}}]}""",
        )
        assertEquals(listOf("a", "b", "d", "e"), PageWidgets.collect(BlockDoc.blocks(doc)).map { BlockDoc.id(it) })
        assertEquals(setOf("a", "b", "e"), PageWidgets.serverIds(doc))
    }

    @Test
    fun festesZitatZuerstVomServerUndTz() {
        val d = LocalDate.of(2026, 10, 27)
        val fixed = PageWidgets.spec(obj("""{"id":"q","type":"widget","data":{"kind":"quote","cfg":{"src":"fixed","items":["a","b","c"],"pick":"daily"}}}"""))
        assertTrue(fixed.localFallback)
        val srv = PageWidgets.Value("quote", null, null, "b", null, null, null)
        assertEquals("b", PageWidgets.quoteText(fixed, srv, d))
        assertNotNull(PageWidgets.quoteText(fixed, null, d))                 // ohne Server-Wert lokal
        assertFalse(PageWidgets.spec(obj("""{"id":"q","type":"widget","data":{"kind":"quote","cfg":{"src":"db"}}}""")).localFallback)
        assertEquals("Europe/Berlin", PageWidgets.tzParam("Europe/Berlin"))
        assertEquals("UTC", PageWidgets.tzParam("UTC"))
        assertNull(PageWidgets.tzParam("GMT+05:30"))
        assertNull(PageWidgets.tzParam("../etc"))
    }

    // ---------------------------------------------------------------- Antwort GET /api/notes/{nid}/widgets

    @Test
    fun antwortLesen() {
        val r = PageWidgets.parseResponse(
            """{"widgets":{"w1":{"kind":"metric","value":12.5,"max":null,"text":null,"fmt":"eur","as_of":"2026-10-27T10:00:00Z","error":null},""" +
                """"w2":{"kind":"quote","value":null,"max":null,"text":"Carpe diem","fmt":null,"as_of":"x","error":null},""" +
                """"w3":{"kind":"metric","value":null,"error":"no_access"},"w4":{"kind":"metric","error":"gone"},""" +
                """"w5":{"kind":"progress","error":"bad_cfg"},"w6":{"kind":"metric","error":"too_big"},"w7":{"kind":"metric","error":"neu"}}}""",
        ) as PageWidgets.Values.Ok
        val w1 = r.map.getValue("w1")
        assertEquals(12.5, w1.value!!, 0.0)
        assertEquals("eur", w1.fmt)
        assertNull(w1.error)
        assertEquals("Carpe diem", r.map.getValue("w2").text)
        assertEquals(PageWidgets.Err.NO_ACCESS, r.map.getValue("w3").error)
        assertEquals(PageWidgets.Err.GONE, r.map.getValue("w4").error)
        assertEquals(PageWidgets.Err.BAD_CFG, r.map.getValue("w5").error)
        assertEquals(PageWidgets.Err.TOO_BIG, r.map.getValue("w6").error)
        assertEquals(PageWidgets.Err.OTHER, r.map.getValue("w7").error)
    }

    @Test
    fun antwortSonderfaelle() {
        assertEquals(PageWidgets.Values.Unsupported, PageWidgets.parseResponse("""{"widgets":{},"unsupported":true}"""))
        assertEquals(PageWidgets.Values.Ok(emptyMap()), PageWidgets.parseResponse("""{"widgets":{}}"""))
        assertEquals(PageWidgets.Values.Ok(emptyMap()), PageWidgets.parseResponse("""{}"""))
        assertEquals(PageWidgets.Values.Failed, PageWidgets.parseResponse("<html>"))
        assertEquals(PageWidgets.Values.OldServer, PageWidgets.fromHttpError(404))
        assertEquals(PageWidgets.Values.Failed, PageWidgets.fromHttpError(500))
        // Zahl als Text ist kein Wert
        val r = PageWidgets.parseResponse("""{"widgets":{"a":{"kind":"metric","value":"3"}}}""") as PageWidgets.Values.Ok
        assertNull(r.map.getValue("a").value)
    }

    // ---------------------------------------------------------------- Uhr

    @Test
    fun uhrText() {
        val t = Instant.parse("2026-10-27T13:05:09Z")
        val c24 = PageWidgets.clock(obj("""{"tz":"Europe/Berlin","h24":true,"sec":false}"""))
        assertEquals("14:05", PageWidgets.clockText(t, c24, Locale.GERMANY))
        assertEquals("14:05:09", PageWidgets.clockText(t, c24.copy(sec = true), Locale.GERMANY))
        val c12 = PageWidgets.clock(obj("""{"tz":"America/New_York","h24":false}"""))
        assertEquals("9:05 AM", PageWidgets.clockText(t, c12, Locale.US))
        // "" = Zone des Betrachters, ungueltige Zone ebenso
        val cv = PageWidgets.clock(obj("""{"tz":"","h24":true}"""))
        assertEquals("22:05", PageWidgets.clockText(t, cv, Locale.GERMANY, ZoneId.of("Asia/Tokyo")))
        assertEquals(ZoneId.of("Asia/Tokyo"), PageWidgets.zone("Mars/Olympus", ZoneId.of("Asia/Tokyo")))
        assertEquals("New York", PageWidgets.zoneLabel("America/New_York"))
        assertEquals("", PageWidgets.zoneLabel(""))
        // h24 fehlt -> 24 h
        assertTrue(PageWidgets.clock(obj("""{}""")).h24)
    }

    @Test
    fun uhrZeigerUndTakt() {
        val (h, m, s) = PageWidgets.handAngles(ZonedDateTime.of(2026, 10, 27, 15, 30, 0, 0, berlin))
        assertEquals(105f, h, 0.01f)                                         // 3:30 -> 3,5 * 30
        assertEquals(180f, m, 0.01f)
        assertEquals(0f, s, 0.01f)
        assertEquals(400L, PageWidgets.nextTickMillis(10_600, sec = true))
        assertEquals(50_000L, PageWidgets.nextTickMillis(70_000, sec = false))
    }

    // ---------------------------------------------------------------- Countdown

    private fun cd(cfg: String, now: ZonedDateTime) = PageWidgets.countdown(PageWidgets.countdownCfg(obj(cfg)), now)

    @Test
    fun countdownTage() {
        val now = ZonedDateTime.of(2026, 10, 27, 23, 30, 0, 0, berlin)
        val a = cd("""{"date":"2026-12-24","time":"","dir":"until","unit":"days"}""", now)!!
        assertEquals(58L, a.days)
        assertFalse(a.done)
        val heute = cd("""{"date":"2026-10-27","dir":"until"}""", now)!!
        assertTrue(heute.today)
        assertFalse(heute.done)
        val vorbei = cd("""{"date":"2026-10-01","dir":"until"}""", now)!!
        assertTrue(vorbei.done)
        val seit = cd("""{"date":"2026-10-01","dir":"since"}""", now)!!
        assertEquals(26L, seit.days)
        assertTrue(cd("""{"date":"2027-01-01","dir":"since"}""", now)!!.done)  // noch nicht begonnen
        assertNull(cd("""{"date":"27.10.2026"}""", now))                        // bad_cfg
    }

    @Test
    fun countdownAutoStundenMinuten() {
        val now = ZonedDateTime.of(2026, 10, 27, 10, 15, 0, 0, berlin)
        val a = cd("""{"date":"2026-10-27","time":"18:45","dir":"until","unit":"auto"}""", now)!!
        assertNull(a.days)
        assertEquals(8, a.hours)
        assertEquals(30, a.minutes)
        // ab einem Tag wieder Tage
        assertEquals(2L, cd("""{"date":"2026-10-29","time":"18:45","unit":"auto"}""", now)!!.days)
        // unit days mit Uhrzeit: volle Tage
        assertEquals(0L, cd("""{"date":"2026-10-27","time":"18:45","unit":"days"}""", now)!!.days)
        // Zeitpunkt erreicht
        assertTrue(cd("""{"date":"2026-10-27","time":"09:00","unit":"auto"}""", now)!!.done)
        // seit (auto)
        val s = cd("""{"date":"2026-10-27","time":"09:00","dir":"since","unit":"auto"}""", now)!!
        assertEquals(1, s.hours)
        assertEquals(15, s.minutes)
    }

    // ---------------------------------------------------------------- Fortschritt

    private fun spec(kind: String, cfg: String) = PageWidgets.spec(obj("""{"id":"w","type":"widget","data":{"kind":"$kind","cfg":$cfg}}"""))
    private fun value(v: Double?, max: Double? = null, text: String? = null) = PageWidgets.Value("x", v, max, text, null, null, null)

    @Test
    fun fortschritt() {
        val f = PageWidgets.progress(spec("progress", """{"src":"fixed","value":3,"max":4}"""), null)!!
        assertEquals(75, f.percent)
        assertEquals(0.75f, f.fraction, 0.001f)
        assertEquals(225, PageWidgets.progress(spec("progress", """{"src":"fixed","value":9,"max":4}"""), null)!!.percent)   // Fix #10: echt, nicht gedeckelt
        assertNull(PageWidgets.progress(spec("progress", """{"src":"fixed","value":1,"max":0}"""), null))
        // db: Wert und max vom Server, sonst goal
        val db = spec("progress", """{"src":"db","mode":"sum","goal":200}""")
        assertEquals(25, PageWidgets.progress(db, value(50.0, 200.0))!!.percent)
        assertEquals(50, PageWidgets.progress(db, value(100.0))!!.percent)
        assertNull(PageWidgets.progress(db, null))
    }

    // ---------------------------------------------------------------- Zahlenformat

    @Test
    fun formatLokalisiert() {
        assertEquals("1.234,5", PageWidgets.format(1234.5, "num", Locale.GERMANY))
        assertEquals("1,234.5", PageWidgets.format(1234.5, "num", Locale.US))
        assertEquals("3", PageWidgets.format(3.0, "num", Locale.US))
        assertEquals("42%", PageWidgets.format(42.0, "pct", Locale.US))
        assertTrue(PageWidgets.format(42.0, "pct", Locale.GERMANY).startsWith("42"))
        val eurDe = PageWidgets.format(1234.5, "eur", Locale.GERMANY)
        assertTrue(eurDe, eurDe.contains("1.234,50") && eurDe.contains("€"))
        val eurUs = PageWidgets.format(1234.5, "eur", Locale.US)
        assertTrue(eurUs, eurUs.contains("1,234.50") && eurUs.contains("€"))
        // fmt aus der Antwort gewinnt, sonst cfg, sonst num
        val s = spec("metric", """{"fmt":"pct"}""")
        assertEquals("pct", PageWidgets.metricFmt(s, null))
        assertEquals("eur", PageWidgets.metricFmt(s, PageWidgets.Value("metric", 1.0, null, null, "eur", null, null)))
        assertEquals("num", PageWidgets.metricFmt(spec("metric", """{"fmt":"bogus"}"""), null))
        assertEquals(10.0, PageWidgets.metricGoal(spec("metric", """{"goal":10}"""))!!, 0.0)
        assertNull(PageWidgets.metricGoal(spec("metric", """{"goal":0}""")))
    }

    // ---------------------------------------------------------------- Zitat

    @Test
    fun zitatTaeglichDeterministisch() {
        assertEquals(0x811C9DC5L, PageWidgets.fnv1a(""))
        assertEquals(0xE40C292CL, PageWidgets.fnv1a("a"))                    // FNV-1a-Testvektor
        val d = LocalDate.of(2026, 10, 27)
        val i1 = PageWidgets.quoteIndex(5, "daily", d, "w1")
        assertEquals(i1, PageWidgets.quoteIndex(5, "daily", d, "w1"))
        assertTrue(i1 in 0 until 5)
        // ueber viele Tage werden verschiedene Zitate gewaehlt
        val seen = (0 until 30).map { PageWidgets.quoteIndex(5, "daily", d.plusDays(it.toLong()), "w1") }.toSet()
        assertTrue(seen.size > 1)
        assertEquals(-1, PageWidgets.quoteIndex(0, "daily", d, "w1"))
        assertEquals(2, PageWidgets.quoteIndex(5, "open", d, "w1", object : Random() { override fun nextBits(bitCount: Int) = 0; override fun nextInt(until: Int) = 2 }))
    }

    @Test
    fun zitatText() {
        val d = LocalDate.of(2026, 10, 27)
        val fixed = spec("quote", """{"src":"fixed","items":["  Eins ","", 3, "Zwei"],"pick":"daily"}""")
        assertEquals(listOf("Eins", "Zwei"), PageWidgets.quoteItems(fixed.cfg))
        assertNotNull(PageWidgets.quoteText(fixed, null, d))
        assertNull(PageWidgets.quoteText(spec("quote", """{"src":"fixed","items":[]}"""), null, d))
        val db = spec("quote", """{"src":"db","pick":"daily"}""")
        assertEquals("Vom Server", PageWidgets.quoteText(db, value(null, text = " Vom Server "), d))
        assertNull(PageWidgets.quoteText(db, null, d))
        assertEquals(20, PageWidgets.quoteItems(obj("""{"items":[${(1..30).joinToString(",") { "\"q$it\"" }}]}""")).size)
    }

    // ---------------------------------------------------------------- Regel 0

    private class Fake(var body: String) : PageBackend {
        var rev = 1
        val drafts = MemoryDrafts()
        override val currentServer = "https://s.example.org"
        override val signedOut = false
        override fun draftsForCurrent(): Drafts = drafts
        override suspend fun <T> withPageLock(id: Long, block: suspend () -> T): T = block()
        override suspend fun loadPage(id: Long): JsonObject =
            buildJsonObject { put("id", id); put("title", "T"); put("body", body); put("format", "blocks"); put("rev", rev) }
        override suspend fun savePage(id: Long, title: String, body: String?, rev: Int?): Int? {
            this.body = body ?: this.body
            return ++this.rev
        }
        override suspend fun createPageWith(title: String, body: String): Long = 2
        override suspend fun setPinned(id: Long, pinned: Boolean) {}
        override suspend fun setLocked(id: Long, locked: Boolean) {}
        override fun updateTitleLocal(id: Long, title: String) {}
        override fun visited(id: Long) {}
        override fun friendly(e: Throwable): UiText = UiText.Raw("?")
    }

    @Test
    fun rundreiseWidgetsUnveraendert() = runTest {
        val page = """{"time":1,"blocks":[""" +
            """{"id":"w1","type":"widget","data":{"v":1,"kind":"metric","size":"l","style":"card","accent":"blue","title":"Offen","cfg":{"db":12,"view":"v1","agg":"sum","prop":"p","fmt":"eur","goal":100,"zukunft":[1]}},"tunes":{"x":1}},""" +
            """{"id":"p1","type":"paragraph","data":{"text":"Eins"}},""" +
            """{"id":"c1","type":"columns","data":{"cols":[{"w":0.5,"blocks":[{"id":"w2","type":"widget","data":{"kind":"clock","cfg":{"tz":"Asia/Tokyo","h24":false,"sec":true,"analog":true}}}]},""" +
            """{"w":0.5,"blocks":[{"id":"w3","type":"widget","data":{"kind":"sparkline","cfg":{"neu":"ja"}}}]}]}},""" +
            """{"id":"t1","type":"toggle","data":{"title":"T","blocks":[{"id":"w4","type":"widget","data":{"kind":"quote","cfg":{"src":"fixed","items":["a","b"],"pick":"open"}}}]}}""" +
            """]}"""
        val srv = Fake(page)
        val ed = PageEditor(1, srv, backgroundScope, io = StandardTestDispatcher(testScheduler), cpu = StandardTestDispatcher(testScheduler), clock = { 5 })
        ed.load()
        ed.setString(listOf("blocks", 1, "data", "text"), "Eins geändert")
        advanceTimeBy(30_000); runCurrent()
        val before = BlockDoc.blocks(obj(page))
        val after = BlockDoc.blocks(obj(srv.body))
        assertEquals(before.size, after.size)
        for (i in before.indices) if (i != 1) assertEquals("Block $i unverändert", before[i], after[i])
        assertEquals("Eins geändert", BlockDoc.str(BlockDoc.data(after[1]), "text"))
    }
}
