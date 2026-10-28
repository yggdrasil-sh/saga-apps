package io.github.yggdrasil_sh.saga.db

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.round

/**
 * 2.5 chart1: Ergebnis eines Diagramms (GET /api/notes/{db}/chart bzw. Widget `data`) - gerechnet hat der Server, die
 * App zeichnet nur (eigene Compose-Canvas). Achsen mit "schoenen" Werten wie saga/chartsvg.py nice_ticks.
 */
object DbChart {
    data class Cat(val key: String?, val label: String, val color: String?, val start: String? = null)
    data class Series(val key: String, val label: String, val color: String?, val values: List<Double?>)

    data class Result(
        val type: String, val xKind: String, val bucket: String?, val cats: List<Cat>, val series: List<Series>,
        val total: Double?, val fmt: String, val other: Int, val truncated: Boolean, val rows: Int, val goal: Double?,
        val stack: Boolean, val cumulative: Boolean,
    ) {
        val pie: Boolean get() = type == "pie" || type == "donut"
        val horizontal: Boolean get() = type == "hbar"
        /** Groesster/kleinster Wert der Y-Achse (gestapelt: Summen je Kategorie), Ziellinie eingeschlossen */
        fun yRange(): Pair<Double, Double> {
            val vals = if (stack && series.size > 1 && type != "line") cats.indices.map { i -> series.sumOf { it.values.getOrNull(i) ?: 0.0 } }
            else series.flatMap { s -> s.values.filterNotNull() }
            val all = vals + listOfNotNull(goal)
            return (minOf(0.0, all.minOrNull() ?: 0.0)) to (maxOf(0.0, all.maxOrNull() ?: 0.0))
        }
        /** Alle Werte (und Ziel) ganzzahlig, kein Prozent -> Achse nur mit ganzen Werten (Anzahl: kein "0,2 / 0,4") */
        val integerValues: Boolean get() = fmt != "pct" &&
            (series.flatMap { s -> s.values.filterNotNull() } + listOfNotNull(goal)).all { it == kotlin.math.floor(it) && it.isFinite() }
    }

    sealed interface Response {
        data class Ok(val chart: Result) : Response
        /** bad_cfg, unsupported, no_access ... */
        data class Error(val code: String) : Response
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private fun JsonObject.s(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.d(k: String): Double? = (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() }
    private fun JsonObject.b(k: String): Boolean = (this[k] as? JsonPrimitive)?.booleanOrNull == true

    fun parseResult(o: JsonObject): Result {
        val cats = (o["cats"] as? JsonArray).orEmpty().mapNotNull { e ->
            val c = e as? JsonObject ?: return@mapNotNull null
            Cat(c.s("key"), c.s("label").orEmpty(), c.s("color"), c.s("start"))
        }
        val series = (o["series"] as? JsonArray).orEmpty().mapNotNull { e ->
            val x = e as? JsonObject ?: return@mapNotNull null
            Series(x.s("key").orEmpty(), x.s("label").orEmpty(), x.s("color"),
                (x["values"] as? JsonArray).orEmpty().map { v -> (v as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() } })
        }.take(11)
        return Result(
            type = o.s("type")?.takeIf { it in TYPES } ?: "bar", xKind = o.s("x_kind") ?: "cat", bucket = o.s("bucket"),
            cats = cats, series = series, total = o.d("total"), fmt = o.s("fmt")?.takeIf { it in setOf("num", "pct", "eur") } ?: "num",
            other = o.d("other")?.toInt() ?: 0, truncated = o.b("truncated"), rows = o.d("rows")?.toInt() ?: 0, goal = o.d("goal"),
            stack = o.b("stack"), cumulative = o.b("cumulative"),
        )
    }

    /** Antwort von GET /api/notes/{db}/chart */
    fun parseResponse(text: String): Response {
        val o = try {
            json.parseToJsonElement(text) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return Response.Error("bad_response")
        val c = o["chart"] as? JsonObject
        val err = o.s("error")
        if (c == null) return Response.Error(err ?: "bad_cfg")
        return Response.Ok(parseResult(c))
    }

    val TYPES = setOf("bar", "hbar", "line", "area", "pie", "donut", "number")

    /** Reihenfolge, wenn der Server keine Farbe schickt (color null) */
    val DEFAULT_COLORS = listOf("blue", "orange", "green", "purple", "pink", "yellow", "red", "brown")

    fun seriesColor(s: Series, i: Int): String = s.color ?: DEFAULT_COLORS[i % DEFAULT_COLORS.size]
    /** Kreis/Ring: Farbe je Kategorie */
    fun catColor(c: Cat, i: Int): String = c.color ?: DEFAULT_COLORS[i % DEFAULT_COLORS.size]

    /**
     * Schoene Achsenwerte (1/2/2,5/5 x 10^k), ca. n Schritte, [lo, hi] umschlossen - Vektoren tests/chart_ticks.json.
     * integer: nur ganze Werte (Schritte 1/2/5 x 10^k, mindestens 1)
     */
    fun niceTicks(lo0: Double, hi0: Double, n: Int = 5, integer: Boolean = false): List<Double> {
        var lo = lo0
        var hi = hi0
        if (lo == hi) {
            hi = if (lo >= 0) lo + 1 else lo
            lo = minOf(0.0, lo)
            if (lo == hi) hi = lo + 1
        }
        val raw = (hi - lo) / maxOf(1, n)
        val mag = 10.0.pow(floor(log10(raw)))
        val step = if (integer) maxOf(1.0, listOf(1.0, 2.0, 5.0, 10.0).map { it * mag }.first { it >= raw - 1e-12 })
        else listOf(1.0, 2.0, 2.5, 5.0, 10.0).map { it * mag }.first { it >= raw - 1e-12 }
        val a = floor(lo / step + 1e-9) * step
        val b = ceil(hi / step - 1e-9) * step
        val out = ArrayList<Double>()
        var x = a
        while (x <= b + step / 2 && out.size < 50) {
            out.add(round(x * 1e10) / 1e10)
            x += step
        }
        return out
    }

    /** Kategorie-Beschriftung: Datum lokal (Monat "Okt 2026", Tag "27.10.") - sonst das Label des Servers; leer -> fallback */
    fun catLabel(c: Cat, bucket: String?, locale: java.util.Locale, empty: String, other: String): String {
        if (c.key == null) return empty
        if (c.key == "__other__") return other
        val start = c.start?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return c.label
        return when (bucket) {
            "day" -> if (locale.language == "en") "${start.monthValue}/${start.dayOfMonth}" else "${start.dayOfMonth}.${start.monthValue}."
            "week" -> c.key.substringAfter('-')
            "month" -> java.time.format.DateTimeFormatter.ofPattern("MMM yy", locale).format(start)
            "year" -> start.year.toString()
            else -> c.label
        }
    }

    // ---------------------------------------------------------------- Zeitleiste (timeline1, nur lesen)

    data class Bar(val row: DbRow, val start: LocalDate, val end: LocalDate)

    /** Balken einer Zeitleiste: Start aus date_by, Ende aus end_by (oder Zeitraum im Startwert); ohne Datum -> undated */
    fun timeline(rows: List<DbRow>, view: DbView, schema: DbSchema, zone: ZoneId = ZoneId.systemDefault()): Pair<List<Bar>, List<DbRow>> {
        val sp = schema.props.firstOrNull { it.id == view.dateBy && it.type == "date" } ?: schema.props.firstOrNull { it.type == "date" }
        val ep = schema.props.firstOrNull { it.id == view.endBy && it.type == "date" }
        val bars = ArrayList<Bar>()
        val undated = ArrayList<DbRow>()
        for (r in rows) {
            val v = sp?.let { DbDate.parse(r.props[it.id].prim(), zone) }
            if (v == null) {
                undated.add(r); continue
            }
            val a = v.start.localDay(zone)
            val e = ep?.let { DbDate.parse(r.props[it.id].prim(), zone) }?.let { (it.end ?: it.start).localDay(zone) } ?: v.end?.localDay(zone) ?: a
            bars.add(Bar(r, a, if (e.isBefore(a)) a else e))
        }
        return bars.sortedWith(compareBy({ it.start }, { it.end })) to undated
    }

    /** Telefon: Liste nach Start gruppiert (Monat bzw. Woche je nach scale) */
    fun timelineGroups(bars: List<Bar>, scale: String?): List<Pair<LocalDate, List<Bar>>> =
        bars.groupBy { b ->
            when (scale) {
                "day", "week" -> b.start.minusDays((b.start.dayOfWeek.value - 1).toLong())
                "quarter" -> b.start.withMonth(((b.start.monthValue - 1) / 3) * 3 + 1).withDayOfMonth(1)
                else -> b.start.withDayOfMonth(1)
            }
        }.toSortedMap().map { it.key to it.value }
}
