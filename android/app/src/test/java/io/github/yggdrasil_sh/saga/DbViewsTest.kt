package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.data.ApiException
import io.github.yggdrasil_sh.saga.db.DbFilter
import io.github.yggdrasil_sh.saga.db.DbPage
import io.github.yggdrasil_sh.saga.db.DbParams
import io.github.yggdrasil_sh.saga.db.DbQuery
import io.github.yggdrasil_sh.saga.db.DbRow
import io.github.yggdrasil_sh.saga.db.DbSchema
import io.github.yggdrasil_sh.saga.db.DbSort
import io.github.yggdrasil_sh.saga.db.DbStore
import io.github.yggdrasil_sh.saga.db.DbValues
import io.github.yggdrasil_sh.saga.db.DbViews
import io.github.yggdrasil_sh.saga.util.UiText
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.URLDecoder
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

/** 2.4 Datenbanken: Ansichten zuordnen, Filter/Sortierung wie im Web, Werte bearbeiten, Warteschlange offline */
class DbViewsTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun o(s: String) = json.parseToJsonElement(s).jsonObject

    private val schemaJson = """
        {"props":[
          {"id":"status","name":"Status","type":"select","options":[{"id":"todo","name":"Offen","color":"gray"},{"id":"doing","name":"In Arbeit","color":"blue"},{"id":"done","name":"Erledigt","color":"green"}]},
          {"id":"tags","name":"Tags","type":"multi_select","options":[{"id":"a","name":"Alpha","color":"red"},{"id":"b","name":"Beta","color":"blue"}]},
          {"id":"prio","name":"Priorität","type":"number"},
          {"id":"due","name":"Fällig","type":"date"},
          {"id":"ok","name":"Fertig","type":"checkbox"},
          {"id":"web","name":"Web","type":"url"},
          {"id":"rel","name":"Projekt","type":"relation","target":77},
          {"id":"half","name":"Hälfte","type":"calc","a":"prio","b":"two","op":"div"},
          {"id":"two","name":"Zwei","type":"number"},
          {"id":"cr","name":"Erstellt","type":"created"}
        ],
        "views":[
          {"id":"tabelle","name":"Tabelle","type":"table","widths":{"status":120}},
          {"id":"offen","name":"Offen","type":"list","filters":[{"pid":"status","op":"is","value":"todo"}],"sort":{"pid":"prio","dir":"desc"}},
          {"id":"board","name":"Board","type":"board","group_by":"status"},
          {"id":"x","name":"Neu","type":"zukunft"}
        ],"srev":4}
    """.trimIndent()
    private val schema = DbSchema(o(schemaJson))
    private val today = LocalDate.of(2026, 10, 21)                // Mittwoch
    private val q = DbQuery(schema, today, ZoneOffset.UTC) { id -> if (id == 5L) "Website" else null }

    private fun row(id: Long, title: String, props: String = "{}", created: Double = 0.0) = DbRow(id, title, props = o(props), created = created, updated = created)

    private val rows = listOf(
        row(1, "Angebot schreiben", """{"status":"todo","prio":3,"due":"2026-10-21","tags":["a"],"two":2}"""),
        row(2, "Rechnung", """{"status":"done","prio":1,"due":"2026-10-01","ok":true,"tags":["a","b"]}"""),
        row(3, "anruf", """{"status":"doing","prio":"7","due":"2026-10-25","rel":[5],"two":2}"""),
        row(4, "Ärger klären", """{"web":"https://example.org/x"}"""),
        row(5, "", """{"status":"gone","prio":0}"""),
    )

    private fun ids(rs: List<DbRow>) = rs.map { it.id }

    // ------------------------------------------------------------------ Ansichten zuordnen

    @Test
    fun viewsMapping() {
        assertEquals(listOf("tabelle", "offen", "board", "x"), schema.views.map { it.id })
        assertEquals("table", schema.view("x").type)                   // unbekannte Darstellung -> Tabelle
        assertEquals("tabelle", schema.view("gibtsnicht").id)          // unbekannte ID -> erste Ansicht
        val offen = schema.view("offen")
        assertEquals(listOf(DbFilter("status", "is", "todo")), offen.filters)
        assertEquals(listOf(DbSort("prio", desc = true)), offen.sorts) // altes Einzelfeld "sort"
        assertEquals("status", schema.groupProp(schema.view("board"))?.id)
        assertNull(schema.groupProp(schema.view("tabelle")))
        assertEquals("due", schema.dateProp(schema.view("tabelle"))?.id)
        // ohne Ansichten: eine Tabelle
        assertEquals("table", DbSchema(o("""{"props":[]}""")).views.single().type)
        // Board ohne group_by: erste Auswahl-Spalte wie im Web
        assertEquals("status", schema.groupProp(io.github.yggdrasil_sh.saga.db.DbView(o("""{"id":"b","type":"board"}""")))?.id)
    }

    @Test
    fun pageParse() {
        val p = DbPage.parse("""{"schema":$schemaJson,"rows":[{"id":9,"title":"A","props":{"prio":2},"updated":1.5,"created":1,"preview":"/files/x.png"}],
            "total":2000,"matched":37,"offset":100,"limit":100,"groups":[{"id":null,"count":2},{"id":"todo","count":35}],"role":"comment"}""")
        assertTrue(p.paged)
        assertTrue(p.readOnly)
        assertEquals(2000, p.total)
        assertEquals(37, p.matched)
        assertEquals(listOf(null, "todo"), p.groups?.map { it.id })
        assertEquals("/files/x.png", p.rows.single().preview)
        // 2.1-Server: ohne total/matched -> die App filtert selbst
        assertFalse(DbPage.parse("""{"schema":{"props":[]},"rows":[]}""").paged)
    }

    // ------------------------------------------------------------------ Filter

    private fun f(pid: String, op: String, v: String = "") = ids(q.filter(rows, listOf(DbFilter(pid, op, v))))

    @Test
    fun filterText() {
        assertEquals(listOf(1L), f("title", "contains", "ANGEBOT"))
        assertEquals(listOf(2L, 3L, 4L, 5L), f("title", "not_contains", "angebot"))
        assertEquals(listOf(5L), f("title", "is_empty"))
        assertEquals(listOf(4L), f("web", "contains", "example"))
        assertEquals(ids(rows), f("title", "contains", ""))              // ohne Wert: wirkt nicht
        assertEquals(ids(rows), f("geloescht", "contains", "x"))         // Spalte weg: wirkt nicht
    }

    @Test
    fun filterSelectAndMulti() {
        assertEquals(listOf(1L), f("status", "is", "todo"))
        assertEquals(listOf(2L, 3L, 4L, 5L), f("status", "is_not", "todo"))
        assertEquals(listOf(4L), f("status", "is_empty"))
        assertEquals(listOf(1L, 2L), f("tags", "contains", "a"))
        assertEquals(listOf(3L, 4L, 5L), f("tags", "not_contains", "a"))
        assertEquals(listOf(3L), f("rel", "contains", "5"))
    }

    @Test
    fun filterNumberDateCheckbox() {
        assertEquals(listOf(3L), f("prio", "eq", "7"))                   // "7" als Text zaehlt als Zahl
        assertEquals(listOf(1L, 3L), f("prio", "gt", "1"))
        assertEquals(listOf(2L, 5L), f("prio", "lt", "3"))
        assertEquals(listOf(4L), f("prio", "is_empty"))
        assertEquals(listOf(1L, 3L), f("half", "gt", "1"))              // Berechnet: 3/2, 7/2
        assertEquals(listOf(1L), f("due", "eq", "2026-10-21"))
        assertEquals(listOf(2L), f("due", "before", "2026-10-21"))
        assertEquals(listOf(3L), f("due", "after", "2026-10-21"))
        assertEquals(listOf(1L), f("due", "today"))
        assertEquals(listOf(1L, 3L), f("due", "this_week"))             // Mo 19.10. - So 25.10.
        assertEquals(listOf(1L, 2L, 3L), f("due", "this_month"))
        assertEquals(listOf(2L), f("ok", "checked"))
        assertEquals(listOf(1L, 3L, 4L, 5L), f("ok", "unchecked"))
    }

    @Test
    fun filterCreatedUsesDayOfDevice() {
        val ts = LocalDate.of(2026, 10, 21).atTime(10, 0).toEpochSecond(ZoneOffset.UTC).toDouble()
        val rs = listOf(row(1, "a", created = ts), row(2, "b", created = ts - 86400 * 3))
        assertEquals(listOf(1L), ids(q.filter(rs, listOf(DbFilter("cr", "today")))))
        assertEquals(listOf(1L), ids(q.filter(rs, listOf(DbFilter("cr", "eq", "2026-10-21")))))
        assertEquals(listOf(2L), ids(q.filter(rs, listOf(DbFilter("cr", "before", "2026-10-21")))))
    }

    @Test
    fun searchFindsOptionNamesAndRelations() {
        assertEquals(listOf(3L), ids(q.filter(rows, emptyList(), "in arbeit")))
        assertEquals(listOf(1L, 2L), ids(q.filter(rows, emptyList(), "alpha")))
        assertEquals(listOf(3L), ids(q.filter(rows, emptyList(), "website")))
    }

    // ------------------------------------------------------------------ Sortierung

    @Test
    fun sortLikeWeb() {
        // Auswahl nach Reihenfolge der Optionen; leer/unbekannt = 1e9 (wie sortKey im Web, also nicht "immer unten")
        assertEquals(listOf(1L, 3L, 2L, 4L, 5L), ids(q.sort(rows, listOf(DbSort("status")))))
        assertEquals(listOf(4L, 5L, 2L, 3L, 1L), ids(q.sort(rows, listOf(DbSort("status", true)))))
        // Zahlen (auch "7" als Text) - Leeres unten in beide Richtungen
        assertEquals(listOf(3L, 1L, 2L, 5L, 4L), ids(q.sort(rows, listOf(DbSort("prio", true)))))
        assertEquals(listOf(5L, 2L, 1L, 3L, 4L), ids(q.sort(rows, listOf(DbSort("prio")))))
        assertEquals(listOf(2L, 1L, 3L, 4L, 5L), ids(q.sort(rows, listOf(DbSort("due")))))
        assertEquals(listOf(3L, 1L, 2L, 4L, 5L), ids(q.sort(rows, listOf(DbSort("due", true)))))
        // Mehrfachauswahl nach Anzahl, Name klein geschrieben (Zeichencode: "Ä" nach "z")
        assertEquals(listOf(3L, 4L, 5L, 1L, 2L), ids(q.sort(rows, listOf(DbSort("tags")))))
        assertEquals(listOf(5L, 1L, 3L, 2L, 4L), ids(q.sort(rows, listOf(DbSort("title")))))
        // mehrere Stufen, sonst stabil
        val two = listOf(row(1, "b", """{"status":"todo"}"""), row(2, "a", """{"status":"todo"}"""), row(3, "c", """{"status":"done"}"""))
        assertEquals(listOf(2L, 1L, 3L), ids(q.sort(two, listOf(DbSort("status"), DbSort("title")))))
        assertEquals(listOf(1L, 2L, 3L), ids(q.sort(two, listOf(DbSort("status")))))
    }

    @Test
    fun applyViewAndGroups() {
        assertEquals(listOf(1L), ids(q.apply(rows, schema.view("offen"))))
        val g = q.groups(rows, schema.prop("status")!!)
        assertEquals(listOf(null, "todo", "doing", "done"), g.map { it.first.id })
        assertEquals(listOf(listOf(4L, 5L), listOf(1L), listOf(3L), listOf(2L)), g.map { ids(it.second) })
    }

    @Test
    fun newEntryTakesFilterValues() {
        val v = schema.view("offen")
        assertEquals(o("""{"status":"todo"}"""), q.defaults(v))
        val more = q.defaults(v, listOf(DbFilter("ok", "checked"), DbFilter("tags", "contains", "b"), DbFilter("prio", "gt", "3"), DbFilter("status", "is", "weg")))
        assertEquals(o("""{"ok":true,"tags":["b"]}"""), more)
    }

    // ------------------------------------------------------------------ Werte bearbeiten

    @Test
    fun numbersLikeWebParseNum() {
        val de = Locale.GERMANY
        val en = Locale.US
        assertEquals(1200.5, DbValues.parseNumber("1.200,5", de)!!, 0.0)
        assertEquals(1200.5, DbValues.parseNumber("1,200.5", en)!!, 0.0)
        assertEquals(2.5, DbValues.parseNumber("2,5", de)!!, 0.0)
        assertEquals(2.5, DbValues.parseNumber("2.5", de)!!, 0.0)
        assertEquals(1200.0, DbValues.parseNumber("1.200", de)!!, 0.0)
        assertEquals(1.2, DbValues.parseNumber("1.200", en)!!, 0.0)
        assertEquals(-3.0, DbValues.parseNumber("−3", de)!!, 0.0)
        assertNull(DbValues.parseNumber("abc", de))
        assertNull(DbValues.parseNumber("1e20", en))
        assertEquals(JsonPrimitive(12L), DbValues.number("12", de))
        assertEquals(JsonNull, DbValues.number(" ", de))
        assertNull(DbValues.number("x", de))
        assertEquals("2,5", DbValues.editText(JsonPrimitive(2.5), de))
        assertEquals("1200", DbValues.editText(JsonPrimitive(1200), de))
    }

    @Test
    fun editValues() {
        assertEquals(JsonNull, DbValues.select(JsonPrimitive("todo"), "todo"))       // noch einmal = abwaehlen
        assertEquals(JsonPrimitive("done"), DbValues.select(JsonPrimitive("todo"), "done"))
        assertEquals(JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b"))), DbValues.toggle(JsonArray(listOf(JsonPrimitive("a"))), "b"))
        assertEquals(JsonNull, DbValues.toggle(JsonArray(listOf(JsonPrimitive("a"))), "a"))
        assertEquals(JsonArray(listOf(JsonPrimitive(5L))), DbValues.toggleRelation(JsonNull, 5))
        assertEquals(JsonNull, DbValues.checkbox(false))
        assertEquals(JsonNull, DbValues.text(""))
        assertEquals(JsonPrimitive("2026-10-21"), DbValues.date(today))
        assertEquals(today, DbValues.parseDate(o("""{"start":"2026-10-21","end":"2026-10-22"}""")))
        val r = rows[0]
        assertFalse("status" in DbValues.apply(r, "status", JsonNull).props)
        assertFalse("tags" in DbValues.apply(r, "tags", JsonArray(emptyList())).props)
        assertEquals(JsonPrimitive("done"), DbValues.apply(r, "status", JsonPrimitive("done")).props["status"])
        assertEquals("mailto:a@b.de", DbValues.safeLink("email", "a@b.de"))
        assertEquals("tel:+491234", DbValues.safeLink("phone", "+49 12 34"))
        assertNull(DbValues.safeLink("url", "javascript:alert(1)"))
        assertEquals("https://x.org", DbValues.safeLink("url", "https://x.org"))
    }

    // ------------------------------------------------------------------ Ansichten bearbeiten

    @Test
    fun editViewsKeepsUnknownFields() {
        val s = o(schemaJson)
        val added = DbViews.add(s, "board", "Mein Board", id = "nb")
        val nb = DbSchema(added).view("nb")
        assertEquals("board", nb.type)
        assertEquals("status", nb.groupBy)                                // erste Auswahl-Spalte
        assertEquals("due", DbSchema(DbViews.add(s, "calendar", "K", id = "k")).view("k").dateBy)
        assertEquals("Umbenannt", DbSchema(DbViews.rename(s, "tabelle", "  Umbenannt ")).view("tabelle").name)
        assertEquals(s, DbViews.rename(s, "tabelle", "   "))
        val filtered = DbViews.setFilters(s, "tabelle", listOf(DbFilter("prio", "gt", "2")))
        val t = filtered["views"]!!.jsonArray[0].jsonObject
        assertEquals(o("""{"status":120}"""), t["widths"])                // Spaltenbreiten bleiben
        assertEquals(JsonPrimitive(4), filtered["srev"])                   // srev geht mit zum Server
        assertFalse("filters" in DbViews.setFilters(filtered, "tabelle", emptyList())["views"]!!.jsonArray[0].jsonObject)
        val sorted = DbSchema(DbViews.setSorts(s, "offen", listOf(DbSort("title"), DbSort("prio", true), DbSort("due"), DbSort("ok"))))
        assertEquals(3, sorted.view("offen").sorts.size)
        assertFalse("sort" in sorted.view("offen").raw)
        var one = s
        for (id in listOf("tabelle", "offen", "board", "x")) one = DbViews.delete(one, id)
        assertEquals(1, DbSchema(one).views.size)                        // die letzte Ansicht bleibt
        val hid = DbViews.setHidden(s, "tabelle", "prio", true)
        assertEquals(setOf("prio"), DbSchema(hid).view("tabelle").hidden)
        assertEquals(emptySet<String>(), DbSchema(DbViews.setHidden(hid, "tabelle", "prio", false)).view("tabelle").hidden)
        val (withOpt, oid) = DbViews.addOption(s, "status", "Wartet", id = "wait")
        assertEquals("wait", oid)
        assertEquals("Wartet", DbSchema(withOpt).prop("status")!!.option("wait")!!.name)
        assertEquals(listOf("tabelle", "board", "x"), DbSchema(DbViews.delete(s, "offen")).views.map { it.id })
        assertEquals("gallery", DbSchema(DbViews.setType(s, "tabelle", "gallery")).view("tabelle").type)
        assertEquals("status", DbSchema(DbViews.setKey(s, "tabelle", "group", "status")).view("tabelle").group)
        assertNull(DbSchema(DbViews.setKey(s, "board", "group_by", null)).view("board").groupBy)
        assertEquals(8, DbViews.newId().length)
    }

    @Test
    fun queryParams() {
        val qs = DbParams.query("offen", listOf(DbFilter("status", "is", "todo")), listOf(DbSort("prio", true)), q = " ä ", group = "", limit = 100, offset = 200,
            tz = "Europe/Berlin", today = today)
        val m = qs.split("&").associate { it.substringBefore("=") to URLDecoder.decode(it.substringAfter("="), "UTF-8") }
        assertEquals("offen", m["view"])
        assertEquals("""[{"pid":"status","op":"is","value":"todo"}]""", m["filters"])
        assertEquals("""[{"pid":"prio","dir":"desc"}]""", m["sorts"])
        assertEquals("ä", m["q"])
        assertEquals("", m["group"])
        assertEquals("100", m["limit"])
        assertEquals("200", m["offset"])
        assertEquals("Europe/Berlin", m["tz"])
        assertEquals("2026-10-21", m["today"])
        assertEquals("limit=5000", DbParams.query(null, limit = 99999))
        assertEquals(listOf(DbFilter("due", "after", "2026-09-30"), DbFilter("due", "before", "2026-11-01")), DbParams.monthFilters("due", today))
        assertEquals(DbFilter("status", "is_empty"), DbParams.columnFilter("status", null))
        // Monat per Filter genau wie der Server (after/before als Textvergleich)
        assertEquals(listOf(1L, 2L, 3L), ids(q.filter(rows, DbParams.monthFilters("due", today))))
    }

    // ------------------------------------------------------------------ Offline-Warteschlange

    @Test
    fun offlineQueueIsKeptAndFlushedLater() = runTest {
        val dir = Files.createTempDirectory("dbstore").toFile()
        var online = false
        val sent = ArrayList<String>()
        val call: suspend (String, String, JsonElement?) -> String = { m, p, b ->
            if (!online) throw java.io.IOException("offline")
            sent.add("$m $p $b")
            "{}"
        }
        val s1 = DbStore(dir, { "srv|anna" }, call)
        assertEquals(DbStore.Saved.QUEUED, s1.setProp(1, "status", JsonPrimitive("done")))
        assertEquals(DbStore.Saved.QUEUED, s1.setProp(1, "prio", JsonNull))
        assertEquals("done", (s1.overlay(rows.take(1)).single().props["status"] as JsonPrimitive).content)
        assertFalse("prio" in s1.overlay(rows.take(1)).single().props)
        // App neu gestartet: Warteschlange ist noch da (je Konto)
        val s2 = DbStore(dir, { "srv|anna" }, call)
        online = true
        s2.flush()
        assertEquals(listOf("""PATCH api/notes/1 {"props":{"status":"done","prio":null}}"""), sent)
        assertTrue(s2.pending.value.isEmpty())
        // anderes Konto sieht nichts
        online = false
        s2.setProp(2, "ok", JsonPrimitive(true))
        val other = DbStore(dir, { "srv|ben" }, call)
        other.flush()
        assertTrue(other.pending.value.isEmpty())
        dir.deleteRecursively()
    }

    @Test
    fun rejectedValueIsDropped() = runTest {
        val dir = Files.createTempDirectory("dbstore").toFile()
        val s = DbStore(dir, { "a" }) { _, _, _ -> throw ApiException(400, UiText.Raw("unbekannte Option")) }
        var failed = false
        try {
            s.setProp(1, "status", JsonPrimitive("weg"))
        } catch (_: ApiException) {
            failed = true
        }
        assertTrue(failed)
        assertTrue(s.pending.value.isEmpty())
        // Server gestoert (5xx): bleibt fuer spaeter
        val s5 = DbStore(dir, { "b" }) { _, _, _ -> throw ApiException(502, UiText.Raw("bad gateway")) }
        assertEquals(DbStore.Saved.QUEUED, s5.setProp(1, "status", JsonPrimitive("done")))
        assertEquals(1, s5.pending.value.size)
        dir.deleteRecursively()
    }

    @Test
    fun cachedRowsWhenOffline() = runTest {
        val dir = Files.createTempDirectory("dbstore").toFile()
        var online = true
        val s = DbStore(dir, { "a" }) { _, _, _ ->
            if (!online) throw java.io.IOException("offline")
            """{"schema":{"props":[]},"rows":[{"id":1,"title":"A"}],"total":1,"matched":1}"""
        }
        assertTrue(s.rows(9, "view=t&limit=100") is DbStore.Loaded.Fresh)
        online = false
        val c = s.rows(9, "view=t&limit=100")
        assertTrue(c is DbStore.Loaded.Cached)
        assertEquals("A", (c as DbStore.Loaded.Cached).page.rows.single().title)
        s.clearCache()
        var threw = false
        try {
            s.rows(9, "view=t&limit=100")
        } catch (_: java.io.IOException) {
            threw = true
        }
        assertTrue(threw)
        assertFalse(File(dir, "x").exists())
        dir.deleteRecursively()
    }

    @Test
    fun largeDatabaseIsQuick() {
        // 1000 Zeilen lokal filtern + sortieren (Fallback fuer alte Server) bleibt deutlich unter 200 ms
        val big = (1..1000).map { i -> row(i.toLong(), "Eintrag $i", """{"status":"${listOf("todo", "doing", "done")[i % 3]}","prio":${i % 17},"tags":["a"]}""") }
        val t0 = System.nanoTime()
        repeat(5) { q.apply(big, schema.view("offen")); q.sort(big, listOf(DbSort("prio", true), DbSort("title"))) }
        val ms = (System.nanoTime() - t0) / 5_000_000
        assertTrue("zu langsam: $ms ms", ms < 200)
    }
}
