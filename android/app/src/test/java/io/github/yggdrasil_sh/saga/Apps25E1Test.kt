package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.blocks.BlockDoc
import io.github.yggdrasil_sh.saga.blocks.BlockOps
import io.github.yggdrasil_sh.saga.blocks.Gestalten
import io.github.yggdrasil_sh.saga.blocks.InlineForms
import io.github.yggdrasil_sh.saga.blocks.InlineHtml
import io.github.yggdrasil_sh.saga.blocks.PageWidgets
import io.github.yggdrasil_sh.saga.blocks.RenderPreview
import io.github.yggdrasil_sh.saga.blocks.TableOpts
import io.github.yggdrasil_sh.saga.data.VersionInfo
import io.github.yggdrasil_sh.saga.data.parsePeopleSearch
import io.github.yggdrasil_sh.saga.db.DbChart
import io.github.yggdrasil_sh.saga.db.DbDate
import io.github.yggdrasil_sh.saga.db.DbFiles
import io.github.yggdrasil_sh.saga.db.DbFilter
import io.github.yggdrasil_sh.saga.db.DbPage
import io.github.yggdrasil_sh.saga.db.DbParams
import io.github.yggdrasil_sh.saga.db.DbProp
import io.github.yggdrasil_sh.saga.db.DbQuery
import io.github.yggdrasil_sh.saga.db.DbView
import io.github.yggdrasil_sh.saga.db.DbViews
import io.github.yggdrasil_sh.saga.db.RowMeta
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

/** Saga 2.5 Apps Etappe 1 (Android): Parser/Modelle gegen echte Server-Antworten (Fixtures aus testing 91f137d) */
class Apps25E1Test {
    private val json = Json { ignoreUnknownKeys = true }
    private fun res(n: String) = javaClass.classLoader!!.getResourceAsStream("e1/$n")!!.bufferedReader().readText()
    private val berlin = ZoneId.of("Europe/Berlin")

    // ---------------------------------------------------------------- Faehigkeiten

    @Test fun versionFeatures() {
        val v = json.decodeFromString(VersionInfo.serializer(), res("version.json"))
        assertTrue("formula1" in v.features && "chart1" in v.features && "datetime1" in v.features)
        // aeltere Server ohne features
        assertEquals(emptyList<String>(), json.decodeFromString(VersionInfo.serializer(), """{"version":"2.4.0"}""").features)
        assertTrue(PageWidgets.supported("clock", emptySet()))
        assertFalse(PageWidgets.supported("weather", setOf("chart1")))
        assertTrue(PageWidgets.supported("weather", setOf("weather1")))
    }

    // ---------------------------------------------------------------- Formeln, Rollups, Status, Unteraufgaben, Personen

    @Test fun formulaValuesAndErrors() {
        val p = DbPage.parse(res("rows_list.json"))
        val summe = p.schema.prop("summe")!!
        assertTrue(summe.computed)
        assertEquals("number", summe.rtype)
        assertTrue(summe.numLike)
        assertFalse(summe.editable)                                      // nie bearbeitbar ("im Web bearbeiten")
        val alpha = p.rows.first { it.title == "Alpha" }
        assertEquals(7.5, (alpha.props["summe"] as JsonPrimitive).content.toDouble(), 0.0)
        val beta = p.rows.first { it.title == "Beta" }
        assertEquals("num", beta.fxErr["quot"])                          // Division durch 0 -> #Fehler
        assertNull(beta.props["quot"])
        assertEquals("text", p.schema.prop("txt")!!.rtype)
        // Filter auf Formel wie Zahl
        assertEquals(listOf("eq", "gt", "lt", "is_empty", "not_empty"), DbFilter.ops(summe))
    }

    @Test fun patchCalcIsAppliedNotSent() {
        val resp = json.parseToJsonElement(res("patch_calc.json")).jsonObject
        val before = JsonObject(mapOf("menge" to JsonPrimitive(5), "summe" to JsonPrimitive(8)))
        val after = RowMeta.applySaved(before, resp)
        assertEquals("20", after["summe"]!!.jsonPrimitive.content)
        assertEquals("GAMMA", after["txt"]!!.jsonPrimitive.content)
        assertNull(after["wer"])                                         // values: null = leer
    }

    @Test fun statusGroupsSubtasksBlocked() {
        val p = DbPage.parse(res("rows_list.json"))
        val st = p.schema.prop("status")!!
        assertTrue(st.status)
        assertEquals(listOf("todo", "wait", "doing", "done"), st.optionsGrouped().map { it.id })
        assertEquals("todo", st.firstOfGroup("todo")!!.id)
        assertEquals("todo", st.groupOf("wait"))
        assertEquals("○", DbProp.groupSymbol("todo"))
        assertEquals("●", DbProp.groupSymbol("done"))
        // Abhaken ueber Status: erste Option der Gruppe done / todo
        assertEquals("done", DbViews.statusToggle(st, "doing", true)!!.jsonPrimitive.content)
        assertEquals("todo", DbViews.statusToggle(st, "done", false)!!.jsonPrimitive.content)
        assertNull(DbViews.statusToggle(st, "done", true))
        // Unteraufgaben verschachtelt, blockiert
        val beta = p.rows.first { it.title == "Beta" }
        assertEquals(1, beta.depth)
        assertEquals(p.rows.first { it.title == "Alpha" }.id, beta.subOf)
        assertTrue(p.rows.first { it.title == "Gamma" }.blocked)
        // lokale Filter status_group
        val q = DbQuery(p.schema)
        val gamma = p.rows.first { it.title == "Gamma" }
        assertTrue(q.matches(gamma, DbFilter("status", "status_group", "done")))
        assertFalse(q.matches(gamma, DbFilter("status", "status_group", "open")))
        assertTrue(q.matches(beta, DbFilter("status", "status_group", "open")))
        assertTrue(q.matches(beta, DbFilter("status", "status_group", "quatsch")))   // unbekannt: zaehlt nicht
    }

    @Test fun boardByStatusGroups() {
        val p = DbPage.parse(res("rows_board.json"))
        val v = p.schema.view("b")
        assertTrue(v.groupStatus)
        val gp = p.schema.groupProp(v)!!
        assertEquals(listOf(null, "todo", "doing", "done"), DbViews.boardKeys(gp, v))
        assertEquals(listOf(null, "todo", "doing", "done"), p.groups!!.map { it.id })
        assertEquals("todo", DbViews.cardKey(gp, v, "wait"))             // Option "Wartet" liegt in Gruppe todo
        assertEquals(DbFilter("status", "status_group", "doing"), DbParams.columnFilter("status", "doing", statusGroup = true))
        // Kartenfarben
        assertEquals("blue", DbQuery(p.schema).color(p.rows.first { it.title == "Alpha" }, v))
    }

    @Test fun peopleDirectoryNeverUsername() {
        val p = DbPage.parse(res("rows_list.json"))
        val me = p.people["~abcdefghijklmnop"]!!
        assertTrue(me.me)
        assertEquals("Anna Berg", me.name)
        val alpha = p.rows.first { it.title == "Alpha" }
        assertEquals(listOf("~abcdefghijklmnop"), (alpha.props["wer"] as JsonArray).map { it.jsonPrimitive.content })
        assertEquals(listOf("me", "contains", "not_contains", "is_empty", "not_empty"), DbFilter.ops(p.schema.prop("wer")!!))
        assertTrue(DbFilter("wer", "me").noValue)
        val found = parsePeopleSearch(res("people_search.json"))
        assertEquals("~abcdefghijklmnop", found.single().ref)
        assertFalse(res("people_search.json").contains("username"))
        // geloeschte Person
        val gone = DbPage.parse("""{"schema":{},"rows":[],"people":{"~zzzzzzzzzzzzzzzz":{"name":"","gone":true}}}""").people.values.single()
        assertTrue(gone.gone)
    }

    @Test fun filesValueRoundTrip() {
        val v = json.parseToJsonElement("""[{"url":"/files/abc","name":"a.pdf","size":12,"mime":"application/pdf","image":false},{"url":"/files/def","name":"b.png","image":true}]""")
        val fs = DbFiles.parse(v)
        assertEquals(2, fs.size)
        assertTrue(fs[1].image)
        // PATCH: nur url + name, ganze Liste
        assertEquals("""[{"url":"/files/abc","name":"a.pdf"},{"url":"/files/def","name":"b.png"}]""", DbFiles.toValue(fs).toString())
        assertEquals("null", DbFiles.toValue(emptyList()).toString())
        val up = DbFiles.fromUpload("""{"file":{"url":"/files/xyz"}}""", "c.txt", "text/plain")!!
        assertEquals("c.txt", up.name)
    }

    // ---------------------------------------------------------------- Datum mit Uhrzeit / Zeitraum

    @Test fun dateTimeParseDisplay() {
        val v = DbDate.parse("2026-10-27T14:30+01:00/2026-10-27T16:00+01:00", berlin)!!
        assertTrue(v.hasTime)
        assertEquals(LocalTime.of(14, 30), v.start.localTime(berlin))
        assertEquals("27.10.2026, 14:30 – 16:00".replace(",", ""), DbDate.display(v, berlin, Locale.GERMANY).replace(",", ""))
        // andere Zone des Betrachters: Wandzeit umgerechnet
        val ny = ZoneId.of("America/New_York")
        assertEquals(LocalTime.of(9, 30), v.start.localTime(ny))
        // 23:30+02:00 am 27. ist in Bangkok der 28.
        assertEquals(LocalDate.of(2026, 10, 28), DbDate.parse("2026-10-27T23:30+02:00")!!.start.localDay(ZoneId.of("Asia/Bangkok")))
        // Zeitraum ueber Tage -> jeder Tag im Kalender
        assertEquals(3, DbDate.days(DbDate.parse("2026-10-27/2026-10-29")!!, berlin).size)
        // Formel-Datum ohne Offset (Ortszeit in tz)
        assertEquals(LocalTime.of(8, 15), DbDate.parse("2026-10-27T08:15", berlin)!!.start.localTime(berlin))
        assertNull(DbDate.parse(""))
    }

    @Test fun dateTimeEncodeTagTag() {
        val d = LocalDate.of(2026, 10, 27)
        // Uhrzeit mit Offset des Geraets, ohne Sekunden
        assertEquals("2026-10-27T14:30+01:00", DbDate.encode(d, LocalTime.of(14, 30, 12), null, null, berlin, null, true))
        assertEquals("2026-07-01T09:00+02:00", DbDate.encode(LocalDate.of(2026, 7, 1), LocalTime.of(9, 0), null, null, berlin, null, true))
        // Zeitraum
        assertEquals("2026-10-27/2026-10-29", DbDate.encode(d, null, d.plusDays(2), null, berlin, null, true))
        // Uhrzeit bewusst entfernen (gleicher Tag, alter Wert hatte Uhrzeit) -> Tag/Tag
        assertEquals("2026-10-27/2026-10-27", DbDate.encode(d, null, null, null, berlin, "2026-10-27T14:30+01:00", true))
        // alter Wert war schon reiner Tag -> nur Tag
        assertEquals("2026-10-27", DbDate.encode(d, null, null, null, berlin, "2026-10-20", true))
        // aelterer Server ohne datetime1: nur Tag
        assertEquals("2026-10-27", DbDate.encode(d, LocalTime.of(10, 0), null, null, berlin, null, false))
        // Kalender verschieben: Uhrzeit und Ende wandern mit
        assertEquals("2026-10-30T14:30+01:00/2026-10-30T16:00+01:00", DbDate.shift("2026-10-27T14:30+01:00/2026-10-27T16:00+01:00", LocalDate.of(2026, 10, 30), berlin))
        // Sortierschluessel lokal
        assertEquals("2026-10-27T14:30", DbDate.sortKey("2026-10-27T13:30+00:00", berlin))
    }

    @Test fun relativeFiltersOfferedOnlyWithFeature() {
        val date = DbProp("d", "D", "date")
        assertFalse("overdue" in DbFilter.ops(date))
        assertTrue("overdue" in DbFilter.ops(date, setOf("relfilter1")))
        assertTrue(DbFilter("d", "overdue").noValue)
        assertFalse(DbFilter("d", "next_days", "7").noValue)
        val st = DbProp("s", "S", "select", status = true)
        assertTrue("status_group" in DbFilter.ops(st, setOf("status1")))
        assertFalse("status_group" in DbFilter.ops(st, emptySet()))
    }

    // ---------------------------------------------------------------- Ansichten, Block-Ansichten, Tabellen-Optionen

    @Test fun viewOptionsAndEmbeddedViews() {
        val p = DbPage.parse(res("rows_list.json"))
        val t = p.schema.view("t")
        assertEquals(2, t.freeze)
        assertTrue(t.wraps("title"))
        assertFalse(t.wraps("menge"))
        val (frozen, moving) = DbViews.frozenSplit(p.schema.visibleProps(t), t.freeze)
        assertEquals(1, frozen.size)
        assertEquals(p.schema.visibleProps(t).size - 1, moving.size)
        assertTrue(p.schema.view("k").week)
        assertEquals("chart", p.schema.view("c").type)
        // Zeitleiste kennt dieser Server noch nicht -> als Tabelle gespeichert; die App kennt den Typ
        assertTrue("timeline" in DbView.TYPES)
        // eingebettete DB: Block-Ansichten haben Vorrang, Startansicht data.view
        val page = json.parseToJsonElement(res("page_inline.json")).jsonObject
        val blocks = BlockDoc.blocks(json.parseToJsonElement(page["body"]!!.jsonPrimitive.content).jsonObject)
        val dbBlock = blocks.first { BlockDoc.type(it) == "database" }.jsonObject
        val local = DbViews.localViews(BlockDoc.data(dbBlock))
        assertEquals(listOf("v1"), local.map { it.id })
        val eff = DbViews.effectiveViews(p.schema, local)
        assertEquals("v1", DbViews.pick(eff, "b").id)                    // "b" gibt es im Block nicht -> erste
        assertEquals("v1", DbViews.startView(eff, null, "v1"))
        assertEquals(p.schema.views.map { it.id }, DbViews.effectiveViews(p.schema, emptyList()).map { it.id })
        // Block-Ansicht: Filter als Parameter, nie view=
        val q = DbParams.query(view = null, filters = local[0].filters, sorts = local[0].sorts, limit = 25, tz = "Europe/Berlin")
        assertTrue(q.contains("filters=") && q.contains("status_group") && !q.contains("view="))
        assertTrue(DbParams.chartQuery(p.schema.view("c"), false, "Europe/Berlin").startsWith("view=c"))
        assertTrue(DbParams.chartQuery(DbView(JsonObject(mapOf("id" to JsonPrimitive("x"), "type" to JsonPrimitive("chart"),
            "chart" to JsonObject(mapOf("type" to JsonPrimitive("bar")))))), true, null).startsWith("chart="))
    }

    @Test fun simpleTableOptions() {
        val page = json.parseToJsonElement(res("page_inline.json")).jsonObject
        val blocks = BlockDoc.blocks(json.parseToJsonElement(page["body"]!!.jsonPrimitive.content).jsonObject)
        val t = blocks.first { BlockDoc.type(it) == "table" }.jsonObject
        assertTrue(TableOpts.headCol(t))
        assertEquals(listOf(70f, 30f), TableOpts.widths(t, 2))
        assertNull(TableOpts.widths(t, 3))                               // Spalte hinzugefuegt -> gleich breit
        assertEquals("red", TableOpts.color(t, 0, 1))                    // Zelle vor Zeile/Spalte
        assertEquals("blue", TableOpts.color(t, 1, 0))
        assertNull(TableOpts.color(t, 0, 0))
        // Zelle bearbeiten: andere Felder bleiben
        val edited = BlockOps.setCell(t, 1, 1, "neu")
        val d = BlockDoc.data(edited)
        assertEquals(BlockDoc.data(t)["colors"], d["colors"])
        assertEquals(BlockDoc.data(t)["colWidths"], d["colWidths"])
        assertEquals(BlockDoc.data(t)["headCol"], d["headCol"])
    }

    // ---------------------------------------------------------------- Inline-Vertrag

    @Test fun inlineFormsPreservedOnEdit() {
        val page = json.parseToJsonElement(res("page_inline.json")).jsonObject
        val blocks = BlockDoc.blocks(json.parseToJsonElement(page["body"]!!.jsonPrimitive.content).jsonObject)
        val html = BlockDoc.str(BlockDoc.data(blocks[0].jsonObject), "text")
        val rt = InlineHtml.parse(html)
        val forms = rt.spans.mapNotNull { InlineForms.form(it) }
        assertTrue(InlineForms.Form.TextColor("red") in forms)
        assertTrue(InlineForms.Form.BgColor("blue") in forms)
        assertTrue(InlineForms.Form.Mention("~abcdefghijklmnop") in forms)
        assertTrue(InlineForms.Form.Math in forms)
        assertTrue(InlineForms.hasAtomic(html))
        // Text vorne ergaenzt: alle Tags samt Klasse bleiben
        val out = InlineHtml.toHtml(InlineHtml.applyEdit(rt, "Neu " + rt.text))
        listOf("""<span class="hk-c-red">rot</span>""", """<mark class="hk-bg-blue">blau</mark>""",
            """<span class="hk-mention" data-u="~abcdefghijklmnop">X</span>""", """<code class="hk-math">a^2</code>""").forEach { assertTrue(it, out.contains(it)) }
        // unveraendert -> bytegleich
        assertEquals(html, InlineHtml.toHtml(rt))
        assertTrue(InlineForms.CAPS.split(",").containsAll(listOf("inline2", "cols6")))
    }

    @Test fun mentionShowsCurrentName() {
        val rt = InlineHtml.parse("""Hallo <span class="hk-mention" data-u="~abcdefghijklmnop">Alt</span>, <b>gut</b>""")
        val shown = InlineForms.withMentionNames(rt) { if (it == "~abcdefghijklmnop") "Anna Berg" else null }
        assertEquals("Hallo @Anna Berg, gut", shown.text)
        val b = shown.spans.first { it.tag == "b" }
        assertEquals("gut", shown.text.substring(b.start, b.end))
        val m = shown.spans.first { it.tag == "span" }
        assertEquals("@Anna Berg", shown.text.substring(m.start, m.end))
        // unbekannt: Rueckfalltext
        assertEquals("Hallo @Alt, gut", InlineForms.withMentionNames(rt) { null }.text)
    }

    @Test fun paletteMatchesWebCss() {
        assertEquals(0xFFC22A2AL, InlineForms.textArgb("red", dark = false))   // --tc-red hell
        assertEquals(0xFFF28585L, InlineForms.textArgb("red", dark = true))    // --tc-red dunkel
        assertEquals(0x330B6E99L, InlineForms.bgArgb("blue", dark = false))     // --bc-blue 20 %
        assertNull(InlineForms.form(InlineHtml.parse("""<span class="hk-c-lila">x</span>""").spans.single()))
    }

    @Test fun mathMermaidRenderAndSynced() {
        val d = json.parseToJsonElement("""{"tex":"x","render":{"png":"/files/abc","h":"0123456789abcdef","w":31,"hgt":57,"stale":true}}""").jsonObject
        val r = RenderPreview.render(d)!!
        assertTrue(r.stale)
        assertEquals(31f / 57f, r.ratio!!, 0.001f)
        assertNull(RenderPreview.render(json.parseToJsonElement("""{"render":{"png":"https://evil.example/x.png"}}""").jsonObject))
        assertTrue(RenderPreview.isMermaid(json.parseToJsonElement("""{"language":"Mermaid"}""").jsonObject))
        // synced-Kopie: src und eingesetzter Inhalt werden nie gespeichert, unbekannte Schluessel bleiben
        val doc = json.parseToJsonElement("""{"blocks":[{"id":"s1","type":"synced","data":{"sid":"AAAAAAAAAAAAAAAA","x":1,
            "src":{"note":7,"role":"view"},"blocks":[{"type":"paragraph","data":{"text":"a"}}]}},
            {"id":"s2","type":"synced","data":{"sid":"BBBBBBBBBBBBBBBB","blocks":[]}}]}""").jsonObject
        val saved = RenderPreview.docForSave(doc)
        val b0 = BlockDoc.data(BlockDoc.blocks(saved)[0].jsonObject)
        assertEquals(setOf("sid", "x"), b0.keys)
        assertEquals(BlockDoc.blocks(doc)[1], BlockDoc.blocks(saved)[1])   // Original bleibt
        // ohne synced: identisches Objekt
        val plain = json.parseToJsonElement("""{"blocks":[{"type":"paragraph","data":{"text":"a"}}]}""").jsonObject
        assertTrue(RenderPreview.docForSave(plain) === plain)
    }

    @Test fun sixColumnsAndWrap() {
        val b = BlockOps.columns(2)
        assertEquals(6, BlockOps.columnBlocksCount(BlockOps.setColumnCount(b, 6)))
        assertEquals(6, BlockOps.columnBlocksCount(BlockOps.setColumnCount(b, 9)))
        assertEquals(6, Gestalten.columnsPerRow(6, 1100f))
        assertEquals(3, Gestalten.columnsPerRow(6, 600f))
        assertEquals(1, Gestalten.columnsPerRow(6, 100f))
    }

    // ---------------------------------------------------------------- Diagramme

    @Test fun niceTicksVectors() {
        val v = json.parseToJsonElement(res("chart_ticks.json")).jsonObject["cases"]!!.jsonArray
        for (c in v) {
            val o = c.jsonObject
            val want = o["want"]!!.jsonArray.map { it.jsonPrimitive.content.toDouble() }
            val got = DbChart.niceTicks(o["lo"]!!.jsonPrimitive.content.toDouble(), o["hi"]!!.jsonPrimitive.content.toDouble())
            assertEquals("$o", want, got)
        }
    }

    @Test fun niceTicksIntegerCounts() {
        assertEquals(listOf(0.0, 1.0), DbChart.niceTicks(0.0, 1.0, integer = true))
        assertEquals(listOf(0.0, 1.0, 2.0, 3.0), DbChart.niceTicks(0.0, 3.0, integer = true))
        assertEquals(listOf(0.0, 1.0), DbChart.niceTicks(0.0, 0.0, integer = true))
        assertEquals(listOf(0.0, 5.0, 10.0, 15.0), DbChart.niceTicks(0.0, 12.0, integer = true))
        assertEquals(DbChart.niceTicks(0.0, 100.0), DbChart.niceTicks(0.0, 100.0, integer = true))
        val one = DbChart.Result("bar", "cat", null, listOf(DbChart.Cat("a", "A", null)), listOf(DbChart.Series("", "", null, listOf(1.0))),
            1.0, "num", 0, false, 1, null, false, false)
        assertTrue(one.integerValues)
        assertFalse(one.copy(series = listOf(DbChart.Series("", "", null, listOf(0.5)))).integerValues)
        assertFalse(one.copy(fmt = "pct").integerValues)
    }

    @Test fun chartResponses() {
        val r = DbChart.parseResponse(res("chart_view.json")) as DbChart.Response.Ok
        assertEquals("bar", r.chart.type)
        assertEquals(listOf("todo", "doing", "done"), r.chart.cats.map { it.key })
        assertEquals(listOf(0.0, 3.0, 2.0), r.chart.series.single().values)
        assertEquals(5.0, r.chart.total!!, 0.0)
        assertEquals(0.0 to 3.0, r.chart.yRange())
        val line = (DbChart.parseResponse(res("chart_line.json")) as DbChart.Response.Ok).chart
        assertEquals("date", line.xKind)
        assertEquals("day", line.bucket)
        assertEquals(2, line.series.size)
        assertEquals(5.0, line.goal!!, 0.0)                              // Ziellinie zaehlt zum Bereich
        assertEquals(0.0 to 5.0, line.yRange())
        assertEquals("2026-10-26", line.cats[0].start)
        assertEquals("blue", DbChart.seriesColor(line.series[0], 0))     // color null -> Standardreihenfolge
        assertEquals("orange", DbChart.seriesColor(line.series[1], 1))
        assertTrue(DbChart.parseResponse("""{"chart":null,"error":"bad_cfg","view":"t"}""") == DbChart.Response.Error("bad_cfg"))
        assertTrue(DbChart.parseResponse("""{"chart":null,"error":"unsupported"}""") == DbChart.Response.Error("unsupported"))
        val stacked = DbChart.parseResult(json.parseToJsonElement("""{"type":"bar","stack":true,"cats":[{"key":"a","label":"A"}],
            "series":[{"key":"x","values":[2]},{"key":"y","values":[3]}]}""").jsonObject)
        assertEquals(0.0 to 5.0, stacked.yRange())
        assertEquals("Leer", DbChart.catLabel(DbChart.Cat(null, "", null), null, Locale.GERMAN, "Leer", "Andere"))
        assertEquals("Andere", DbChart.catLabel(DbChart.Cat("__other__", "x", null), null, Locale.GERMAN, "Leer", "Andere"))
    }

    @Test fun timelineBarsAndGroups() {
        val p = DbPage.parse(res("rows_list.json"))
        val v = DbView(JsonObject(mapOf("id" to JsonPrimitive("tl"), "type" to JsonPrimitive("timeline"), "date_by" to JsonPrimitive("wann"))))
        val (bars, undated) = DbChart.timeline(p.rows, v, p.schema, berlin)
        assertEquals(3, bars.size)
        assertTrue(undated.isEmpty())
        val gamma = bars.first { it.row.title == "Gamma" }
        assertEquals(LocalDate.of(2026, 10, 27), gamma.start)
        assertEquals(LocalDate.of(2026, 10, 29), gamma.end)
        assertEquals(1, DbChart.timelineGroups(bars, "month").size)
        assertEquals(LocalDate.of(2026, 10, 26), DbChart.timelineGroups(bars, "week").single().first)
    }

    // ---------------------------------------------------------------- Widgets Etappe 2

    @Test fun widgetsChartUpcomingWeather() {
        val all = PageWidgets.parseResponse(res("widgets.json")) as PageWidgets.Values.Ok
        val chart = all.map["w1"]!!
        assertEquals("donut", DbChart.parseResult(chart.data!!).type)
        val up = PageWidgets.upcoming(all.map["w2"]!!.data)!!
        assertEquals(0, up.items.size)
        assertEquals(PageWidgets.Err.DISABLED, all.map["w3"]!!.error)
        assertNull(all.map["w3"]!!.data)
        val w = PageWidgets.weather(json.parseToJsonElement("""{"now":{"t":12.3,"code":3,"wind":14},"days":[{"d":"2026-10-27","min":6,"max":13,"code":61,"rain":70}],
            "units":"metric","stale":true,"place":"Hamburg"}""").jsonObject)!!
        assertTrue(w.stale)
        assertEquals("rain", PageWidgets.weatherKind(61))
        assertEquals("fog", PageWidgets.weatherKind(48))
        assertEquals("thunder", PageWidgets.weatherKind(99))
        assertEquals("clear", PageWidgets.weatherKind(0))
        val items = PageWidgets.upcoming(json.parseToJsonElement("""{"items":[{"k":"task","title":"A","start":"2026-10-26","overdue":true,"allday":true},
            {"k":"event","title":"B","start":"2026-10-28T14:00+01:00","allday":false},{"k":"row","title":"C","start":"2026-10-29","note":4711}],"more":2}""").jsonObject)!!
        val groups = PageWidgets.upcomingGroups(items.items, LocalDate.of(2026, 10, 28))
        assertEquals(listOf("overdue", "today", "tomorrow"), groups.map { it.first })
        assertEquals(4711L, items.items[2].note)
        assertEquals(2, items.more)
    }

    @Test fun habitsParseAndToggle() {
        val all = PageWidgets.parseResponse(res("widgets_habit.json")) as PageWidgets.Values.Ok
        val h = PageWidgets.habits(all.map["h1"]!!.data)!!
        assertEquals(7, h.days.size)
        assertTrue(h.canCheck)
        assertEquals(LocalDate.of(2026, 10, 28), h.today)
        val sport = h.habits.first { it.pid == "sport" }
        assertEquals(1, sport.streak)
        assertTrue(sport.done.last())
        val t = PageWidgets.habitToggled(h, "lesen", LocalDate.of(2026, 10, 28), true)
        assertTrue(t.habits.first { it.pid == "lesen" }.done.last())
        assertEquals("""{"pid":"lesen","day":"2026-10-28","on":true}""", PageWidgets.habitBody("lesen", LocalDate.of(2026, 10, 28), true).toString())
        assertNotNull(PageWidgets.parseSingle("""{"widget":{"kind":"habit","value":2,"max":2,"data":{"days":[],"habits":[]}}}"""))
    }

    @Test fun calendarDaysWithRanges() {
        val p = DbPage.parse(res("rows_list.json"))
        val dp = p.schema.prop("wann")!!
        val m = DbViews.calendarDays(p.rows, dp, DbQuery(p.schema, zone = berlin), berlin)
        assertEquals(setOf("Alpha", "Gamma"), m["2026-10-27"]!!.map { it.title }.toSet())
        assertEquals(setOf("Beta", "Gamma"), m["2026-10-28"]!!.map { it.title }.toSet())
        assertEquals(listOf("Gamma"), m["2026-10-29"]!!.map { it.title })
        assertEquals(LocalDate.of(2026, 10, 26), DbViews.weekStart(LocalDate.of(2026, 10, 28)))
    }
}
