package io.github.yggdrasil_sh.saga.blocks

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlin.math.roundToLong

// Datenbank-Logik ohne Oberflaeche (wie Shared/DBQuery.swift in iOS) - filtert/sortiert GENAU wie das Web.

data class DbOption(val id: String, val name: String, val color: String?)

data class DbProp(
    val id: String,
    val name: String,
    val type: String,
    val options: List<DbOption> = emptyList(),
    val fmt: String? = null,
    val a: String? = null,
    val b: String? = null,
    val op: String? = null,
    val show: String? = null,
) {
    fun option(id: String?): DbOption? = options.firstOrNull { it.id == id }

    companion object {
        fun from(o: JsonObject) = DbProp(
            id = BlockDoc.str(o, "id"), name = BlockDoc.str(o, "name"), type = BlockDoc.str(o, "type").ifEmpty { "text" },
            options = (o["options"] as? JsonArray)?.mapNotNull { e ->
                (e as? JsonObject)?.let { DbOption(BlockDoc.str(it, "id"), BlockDoc.str(it, "name"), BlockDoc.str(it, "color").ifEmpty { null }) }
            } ?: emptyList(),
            fmt = BlockDoc.str(o, "fmt").ifEmpty { null },
            a = BlockDoc.str(o, "a").ifEmpty { null }, b = BlockDoc.str(o, "b").ifEmpty { null },
            op = BlockDoc.str(o, "op").ifEmpty { null }, show = BlockDoc.str(o, "show").ifEmpty { null },
        )
    }
}

/** Werte: String, Boolean, Double oder List<Any?> (Mehrfachauswahl, Relation) */
data class DbRow(
    val id: Int,
    val title: String,
    val icon: String?,
    val cover: String?,
    val props: Map<String, Any?>,
    val created: Double,
    val updated: Double,
)

data class DbTemplate(val id: Int, val title: String, val icon: String)

/** Ansicht einer Datenbank (Tabelle, Liste, Board, Galerie, Kalender) - der Rohwert bleibt fuer Unbekanntes erhalten */
class DbView(val raw: JsonObject) {
    val id: String get() = BlockDoc.str(raw, "id")
    val name: String get() = BlockDoc.str(raw, "name")
    val type: String get() = BlockDoc.str(raw, "type").ifEmpty { "table" }
    val open: String get() = BlockDoc.str(raw, "open").ifEmpty { "side" }
    val hidden: Set<String> get() = strings(raw["hidden"]).toSet()
    val groupBy: String? get() = BlockDoc.str(raw, "group_by").ifEmpty { null }
    val dateBy: String? get() = BlockDoc.str(raw, "date_by").ifEmpty { null }
    val card: String get() = BlockDoc.str(raw, "card")
    val filters: List<JsonObject> get() = (raw["filters"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
    val sorts: List<JsonObject> get() = (raw["sorts"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
    val colors: List<JsonObject> get() = (raw["colors"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()

    private fun strings(e: JsonElement?): List<String> =
        (e as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content } ?: emptyList()
}

data class DbQuery(
    val props: List<DbProp> = emptyList(),
    val views: List<DbView> = emptyList(),
    val rows: List<DbRow> = emptyList(),
    val templates: List<DbTemplate> = emptyList(),
) {
    fun prop(id: String?): DbProp? = props.firstOrNull { it.id == id }
    fun view(id: String?): DbView? = views.firstOrNull { it.id == id } ?: views.firstOrNull()

    fun value(p: DbProp, r: DbRow): Any? = when (p.type) {
        "calc" -> calc(p, r.props)
        "created" -> isoDay(r.created)
        "edited" -> isoDay(r.updated)
        else -> r.props[p.id]
    }

    /** Filter + Sortierung der Ansicht (wie matchFilter / sortedRows im Web) */
    fun rows(view: DbView?): List<DbRow> {
        val filters = view?.filters ?: emptyList()
        val out = rows.filter { r -> filters.all { match(r, it) } }
        val keys = (view?.sorts ?: emptyList()).mapNotNull { s ->
            val pid = BlockDoc.str(s, "pid")
            val p = if (pid == "title") null else prop(pid)
            if (pid != "title" && p == null) null else p to (if (BlockDoc.str(s, "dir") == "desc") -1 else 1)
        }
        if (keys.isEmpty()) return out
        return out.sortedWith { a, b ->
            for ((p, d) in keys) {
                val x = sortKey(p, a)
                val y = sortKey(p, b)
                if (x == y) continue
                if (x == null) return@sortedWith 1               // Leeres immer ans Ende
                if (y == null) return@sortedWith -1
                return@sortedWith x.compareTo(y) * d
            }
            0
        }
    }

    /** Bedingte Farbe der Ansicht: erste passende Regel (wie im Web) */
    fun colorName(r: DbRow, view: DbView?): String? =
        view?.colors?.firstOrNull { match(r, it) }?.let { BlockDoc.str(it, "color").ifEmpty { null } }

    /** Neuer Eintrag passt zu den Filtern der Ansicht ("Status ist Offen" -> Status = Offen), wie Notion */
    fun defaults(view: DbView?): Map<String, JsonElement> {
        val d = LinkedHashMap<String, JsonElement>()
        for (f in view?.filters ?: emptyList()) {
            val op = BlockDoc.str(f, "op")
            if (op != "is" && op != "eq" && op != "checked") continue
            val pid = BlockDoc.str(f, "pid")
            val p = (if (pid == "title") null else prop(pid)) ?: continue
            if (op == "checked") d[pid] = JsonPrimitive(true)
            else {
                val v = BlockDoc.str(f, "value")
                if (v.isNotEmpty()) d[pid] = if (p.type == "multi_select") JsonArray(listOf(JsonPrimitive(v))) else JsonPrimitive(v)
            }
        }
        return d
    }

    private fun sortKey(p: DbProp?, r: DbRow): String? {
        if (p == null) return r.title.lowercase()
        return when (val v = value(p, r)) {
            is String -> if (p.type == "select") p.option(v)?.let { "%04d".format(p.options.indexOf(it)) } else v.ifEmpty { null }?.lowercase()
            is Double -> "%020.4f".format(java.util.Locale.ROOT, v + 1e12)
            is Boolean -> if (v) "1" else "0"
            else -> null
        }
    }

    private fun match(r: DbRow, f: JsonObject): Boolean {
        val pid = BlockDoc.str(f, "pid")
        val op = BlockDoc.str(f, "op")
        val p = if (pid == "title") DbProp("title", "", "text") else prop(pid) ?: return true
        val v: Any? = if (pid == "title") r.title else value(p, r)
        val empty = when (v) {
            null -> true
            is String -> v.isEmpty()
            is Boolean -> !v
            is List<*> -> v.isEmpty()
            else -> false
        }
        when (op) {
            "is_empty" -> return empty
            "not_empty" -> return !empty
            "checked" -> return v == true
            "unchecked" -> return v != true
            "today", "this_week", "this_month" -> return !empty && relDate(op, v)
        }
        val fv = when (val raw = f["value"]) {
            null, is JsonNull -> return true
            is JsonPrimitive -> raw.content
            else -> raw.toString()
        }
        if (fv.isEmpty()) return true
        val arr = (v as? List<*>)?.map { it.toString() }
        val s = v as? String ?: v?.let { display(it) } ?: ""
        val num = v as? Double
        val fnum = fv.toDoubleOrNull()
        return when (op) {
            "contains" -> arr?.contains(fv) ?: s.lowercase().contains(fv.lowercase())
            "not_contains" -> arr?.let { !it.contains(fv) } ?: !s.lowercase().contains(fv.lowercase())
            "is" -> s == fv
            "is_not" -> s != fv
            "eq" -> if (p.type == "number" || p.type == "calc") num != null && fnum != null && num == fnum else s == fv
            "gt" -> num != null && fnum != null && num > fnum
            "lt" -> num != null && fnum != null && num < fnum
            "before" -> s.isNotEmpty() && s < fv
            "after" -> s.isNotEmpty() && s > fv
            else -> true
        }
    }

    private fun display(v: Any): String = if (v is Double && v == Math.floor(v) && !v.isInfinite()) v.toLong().toString() else v.toString()

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /** Antwort von /api/notes/{id}/rows */
        fun parse(text: String): DbQuery? {
            val j = try {
                json.parseToJsonElement(text) as? JsonObject
            } catch (_: Exception) {
                null
            } ?: return null
            val schema = j["schema"] as? JsonObject
            return DbQuery(
                props = (schema?.get("props") as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(DbProp::from) } ?: emptyList(),
                views = (schema?.get("views") as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(::DbView) } ?: emptyList(),
                rows = (j["rows"] as? JsonArray)?.mapNotNull { e ->
                    val r = e as? JsonObject ?: return@mapNotNull null
                    DbRow(
                        id = BlockDoc.int(r, "id") ?: return@mapNotNull null,
                        title = BlockDoc.str(r, "title"),
                        icon = BlockDoc.str(r, "icon").ifEmpty { null },
                        cover = BlockDoc.str(r, "preview").ifEmpty { BlockDoc.str(r, "cover") }.ifEmpty { null },   // Titelbild oder erstes Bild
                        props = (r["props"] as? JsonObject)?.mapValues { plain(it.value) }?.filterValues { it != null } ?: emptyMap(),
                        created = (r["created"] as? JsonPrimitive)?.doubleOrNull ?: 0.0,
                        updated = (r["updated"] as? JsonPrimitive)?.doubleOrNull ?: 0.0,
                    )
                } ?: emptyList(),
                templates = (j["templates"] as? JsonArray)?.mapNotNull { e ->
                    val t = e as? JsonObject ?: return@mapNotNull null
                    DbTemplate(BlockDoc.int(t, "id") ?: return@mapNotNull null, BlockDoc.str(t, "title"), BlockDoc.str(t, "icon"))
                } ?: emptyList(),
            )
        }

        /** JSON -> String / Boolean / Double / List (null fuer Unbekanntes) */
        fun plain(e: JsonElement?): Any? = when (e) {
            null, is JsonNull -> null
            is JsonPrimitive -> if (e.isString) e.content else e.booleanOrNull ?: e.doubleOrNull
            is JsonArray -> e.mapNotNull { plain(it) }
            else -> null
        }

        /** "Berechnet" (wie Web/Server): aus zwei Zahlen-Eigenschaften, nie gespeichert */
        fun calc(p: DbProp, props: Map<String, Any?>): Double? {
            fun num(k: String?): Double? = when (val v = k?.let { props[it] }) {
                is Double -> v
                is String -> v.toDoubleOrNull()
                else -> null
            }
            var x = num(p.a)
            var y = num(p.b)
            val op = p.op ?: "div"
            if (op == "add" || op == "sub") {
                if (x == null && y == null) return null
                x = x ?: 0.0
                y = y ?: 0.0
            }
            val a = x ?: return null
            val b = y ?: return null
            val v = when (op) {
                "div", "pct" -> if (b == 0.0) return null else a / b * (if (op == "pct") 100 else 1)
                "mul" -> a * b
                "add" -> a + b
                else -> a - b
            }
            return (v * 10000).roundToLong() / 10000.0
        }

        private fun isoDay(ts: Double): String = Instant.ofEpochMilli((ts * 1000).toLong()).atZone(ZoneId.systemDefault()).toLocalDate().toString()

        /** Relative Datumsfilter wie in Notion: heute / diese Woche (Mo–So) / diesen Monat */
        fun relDate(op: String, v: Any?, today: LocalDate = LocalDate.now()): Boolean {
            val day = when (v) {
                is String -> if (v.length >= 10) try {
                    LocalDate.parse(v.take(10))
                } catch (_: Exception) {
                    null
                } else null
                is Double -> Instant.ofEpochMilli((v * 1000).toLong()).atZone(ZoneId.systemDefault()).toLocalDate()
                else -> null
            } ?: return false
            return when (op) {
                "this_month" -> day.year == today.year && day.month == today.month
                "this_week" -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).let { mo -> !day.isBefore(mo) && day.isBefore(mo.plusDays(7)) }
                else -> day == today
            }
        }
    }
}
