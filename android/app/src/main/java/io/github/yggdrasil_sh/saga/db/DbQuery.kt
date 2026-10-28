package io.github.yggdrasil_sh.saga.db

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Filter, Suche, Sortierung und Gruppen einer Ansicht GENAU wie im Web (static/pages.js matchFilter / sortedRows /
 * eachGrouped, serverseitig saga/dbquery.py). Die App nutzt das nur, wenn der Server die Ansicht nicht selbst anwendet
 * (Server 2.1, Gegenstelle ohne Parameter) und fuer offline geaenderte Werte; sonst liefert der Server fertige Seiten.
 */
class DbQuery(
    val schema: DbSchema,
    /** Tagesdatum des Geraets (heute / diese Woche / diesen Monat) */
    private val today: LocalDate = LocalDate.now(),
    private val zone: ZoneId = ZoneId.systemDefault(),
    /** 2.5 person1: Anzeigename einer Kennung (Sortierung/Suche) */
    private val personName: (String) -> String? = { null },
    /** Titel verknuepfter Eintraege (Suche) */
    private val label: (Long) -> String? = { null },
) {
    /** Wert einer Spalte: Berechnet frisch, Erstellt/Bearbeitet = Zeitstempel der Zeile, sonst gespeicherter Wert */
    fun value(p: DbProp, r: DbRow): JsonElement? = when {
        p.id == "title" -> JsonPrimitive(r.title)
        p.type == "calc" -> calc(p, r.props)?.let(::JsonPrimitive)
        p.type == "created" -> JsonPrimitive(r.created)
        p.type == "edited" -> JsonPrimitive(r.updated)
        else -> r.props[p.id]?.takeIf { it !is JsonNull }
    }

    fun isoDay(ts: Double): String = Instant.ofEpochMilli((ts * 1000).toLong()).atZone(zone).toLocalDate().toString()

    // ------------------------------------------------------------------ Filter

    fun matches(r: DbRow, f: DbFilter): Boolean {
        val p = schema.prop(f.pid) ?: return true                    // Spalte geloescht: Filter wirkt nicht
        var v = value(p, r)
        if (p.isAuto && truthy(v)) v = JsonPrimitive(isoDay((v as JsonPrimitive).doubleOrNull ?: 0.0))
        val empty = isEmpty(v)
        when (f.op) {
            "is_empty" -> return empty
            "not_empty" -> return !empty
            "checked" -> return truthy(v)
            "unchecked" -> return !truthy(v)
            "today", "this_week", "this_month" -> return !empty && relDate(f.op, v.prim())
            // 2.5 status1: nach Status-Gruppe; leere Zeilen passen nie, unbekannter Wert -> Filter zaehlt nicht
            "status_group" -> {
                if (f.value !in setOf("todo", "doing", "done", "open")) return true
                val g = p.groupOf(v.prim()) ?: return false
                return if (f.value == "open") g != "done" else g == f.value
            }
        }
        if (f.value.isEmpty()) return true                          // unvollstaendiger Filter: alles
        val fv = f.value
        return when (f.op) {
            "contains", "not_contains" -> {
                // Relation ohne Verknuepfung (fehlt, null, Einzelwert) = leere Liste - wie saga/dbquery.py und static/pages.js (82e26d6)
                if (p.type == "relation" && v !is JsonArray) v = JsonArray(emptyList())
                val hit = if (v is JsonArray) {
                    if (p.type == "relation") fv.toDoubleOrNull().let { n -> n != null && v.any { (it as? JsonPrimitive)?.doubleOrNull == n } }
                    else v.any { (it as? JsonPrimitive)?.takeIf { e -> e.isString }?.content == fv }
                } else (if (truthy(v)) text(v) else "").lowercase().contains(fv.lowercase())
                if (f.op == "contains") hit else !hit
            }
            "is" -> (v as? JsonPrimitive)?.isString == true && v.content == fv
            "is_not" -> !((v as? JsonPrimitive)?.isString == true && v.content == fv)
            "eq" -> if (p.numLike) number(v).let { n -> n != null && n == jsNumber(fv) } else (v as? JsonPrimitive)?.isString == true && v.content == fv
            "gt" -> v != null && number(v).let { n -> n != null && jsNumber(fv).let { x -> x != null && x < n } }
            "lt" -> v != null && number(v).let { n -> n != null && jsNumber(fv).let { x -> x != null && n < x } }
            "before" -> truthy(v) && less(v!!, JsonPrimitive(fv))
            "after" -> truthy(v) && less(JsonPrimitive(fv), v!!)
            else -> true
        }
    }

    /** Suche wie rowText: Name, Werte, Namen der Optionen, Titel verknuepfter Eintraege */
    fun rowText(r: DbRow): String {
        val parts = ArrayList<String>()
        parts.add(r.title)
        for (p in schema.props) {
            val v = r.props[p.id] ?: continue
            if (v is JsonNull) continue
            when (p.type) {
                "relation" -> (v as? JsonArray)?.forEach { e -> (e as? JsonPrimitive)?.doubleOrNull?.toLong()?.let(label)?.let { parts.add(it) } }
                "select", "multi_select" -> (if (v is JsonArray) v else listOf(v)).forEach { e -> p.option(e.prim())?.let { parts.add(it.name) } }
                else -> parts.add(text(v))
            }
        }
        return parts.joinToString(" ").lowercase()
    }

    fun filter(rows: List<DbRow>, filters: List<DbFilter>, q: String = ""): List<DbRow> {
        val needle = q.trim().lowercase()
        return rows.filter { r -> filters.all { matches(r, it) } && (needle.isEmpty() || rowText(r).contains(needle)) }
    }

    // ------------------------------------------------------------------ Sortierung

    /** Sortierschluessel (sortKey): Auswahl nach Reihenfolge der Optionen, Mehrfach/Relation nach Anzahl, Text klein */
    fun sortKey(p: DbProp?, r: DbRow): Any? {
        if (p == null || p.id == "title") return r.title.lowercase()
        val v = value(p, r)
        return when (p.type) {
            "date" -> DbDate.sortKey(v.prim(), zone)?.lowercase()
            "person" -> (v as? JsonArray)?.firstOrNull()?.prim()?.let { personName(it)?.lowercase() ?: it }
            "select" -> p.optionIndex(v.prim()).let { if (it < 0 || (v as? JsonPrimitive)?.isString != true) 1e9 else it.toDouble() }
            "multi_select", "relation" -> ((v as? JsonArray)?.size ?: 0).toDouble()
            "checkbox" -> if (truthy(v)) 1.0 else 0.0
            else -> when (v) {
                null, is JsonNull -> null
                is JsonPrimitive -> if (v.isString) v.content.lowercase() else (v.booleanOrNull?.let { if (it) 1.0 else 0.0 } ?: v.doubleOrNull)
                else -> null
            }
        }
    }

    /** Stabile Sortierung; Leeres immer unten, egal in welche Richtung */
    fun sort(rows: List<DbRow>, sorts: List<DbSort>): List<DbRow> {
        val keys = sorts.mapNotNull { s -> if (s.pid == "title") null to s.desc else schema.props.firstOrNull { it.id == s.pid }?.let { it to s.desc } }
        if (keys.isEmpty() || rows.size < 2) return rows
        val cache = HashMap<Long, Array<Any?>>(rows.size * 2)
        fun k(r: DbRow) = cache.getOrPut(r.id) { Array(keys.size) { i -> sortKey(keys[i].first, r) } }
        return rows.sortedWith { a, b ->
            val ka = k(a)
            val kb = k(b)
            for (i in keys.indices) {
                val x = ka[i]
                val y = kb[i]
                if (x == y) continue
                if (x == null) return@sortedWith 1
                if (y == null) return@sortedWith -1
                val lt = when {
                    x is String && y is String -> x < y
                    else -> ((x as? Double) ?: (x as? String)?.toDoubleOrNull() ?: Double.NaN) < ((y as? Double) ?: (y as? String)?.toDoubleOrNull() ?: Double.NaN)
                }
                return@sortedWith (if (lt) -1 else 1) * (if (keys[i].second) -1 else 1)
            }
            0
        }
    }

    /** Ansicht anwenden: Filter + Suche, dann Sortierung */
    fun apply(rows: List<DbRow>, view: DbView, q: String = "", filters: List<DbFilter> = view.filters, sorts: List<DbSort> = view.sorts): List<DbRow> =
        sort(filter(rows, filters, q), sorts)

    /** Gruppen wie eachGrouped/renderBoard: zuerst "Ohne", dann je Option in Schema-Reihenfolge (auch leere) */
    fun groups(rows: List<DbRow>, g: DbProp): List<Pair<DbGroup, List<DbRow>>> {
        val known = g.options.map { it.id }.toSet()
        val none = rows.filter { r -> (r.props[g.id] as? JsonPrimitive)?.takeIf { it.isString }?.content !in known }
        return listOf(DbGroup(null, none.size) to none) + g.options.map { o ->
            val rs = rows.filter { r -> (r.props[g.id] as? JsonPrimitive)?.takeIf { it.isString }?.content == o.id }
            DbGroup(o.id, rs.size) to rs
        }
    }

    /** Bedingte Farbe der Ansicht: erste passende Regel (Farbname) */
    fun color(r: DbRow, view: DbView): String? = view.colors.firstOrNull { (f, _) -> matches(r, f) }?.second

    /** Neuer Eintrag passt zu den Filtern der Ansicht ("Status ist Offen" -> Status = Offen), wie in den anderen Apps */
    fun defaults(view: DbView, filters: List<DbFilter> = view.filters): JsonObject {
        val out = LinkedHashMap<String, JsonElement>()
        for (f in filters) {
            val p = schema.props.firstOrNull { it.id == f.pid } ?: continue
            if (!p.editable) continue
            when {
                f.op == "checked" && p.type == "checkbox" -> out[p.id] = JsonPrimitive(true)
                f.value.isEmpty() -> {}
                f.op == "is" && p.type == "select" && p.option(f.value) != null -> out[p.id] = JsonPrimitive(f.value)
                f.op == "contains" && p.type == "multi_select" && p.option(f.value) != null -> out[p.id] = JsonArray(listOf(JsonPrimitive(f.value)))
                f.op == "eq" && p.type == "number" -> f.value.toDoubleOrNull()?.let { out[p.id] = JsonPrimitive(it) }
                f.op == "eq" && p.type == "date" -> out[p.id] = JsonPrimitive(f.value)
            }
        }
        return JsonObject(out)
    }

    // ------------------------------------------------------------------ Hilfen (JavaScript-Semantik)

    private fun relDate(op: String, iso: String?): Boolean {
        val x = try {
            LocalDate.parse((iso ?: return false).take(10))
        } catch (_: Exception) {
            return false
        }
        val (a, b) = when (op) {
            "this_month" -> today.withDayOfMonth(1).let { it to it.plusMonths(1) }
            "this_week" -> today.minusDays((today.dayOfWeek.value - 1).toLong()).let { it to it.plusDays(7) }
            else -> today to today.plusDays(1)
        }
        return !x.isBefore(a) && x.isBefore(b)
    }

    companion object {
        fun truthy(v: JsonElement?): Boolean = when (v) {
            null, is JsonNull -> false
            is JsonPrimitive -> if (v.isString) v.content.isNotEmpty() else v.booleanOrNull ?: (v.doubleOrNull?.let { it != 0.0 && !it.isNaN() } ?: false)
            else -> true                                               // Liste/Objekt (auch leer) wie in JavaScript
        }

        fun isEmpty(v: JsonElement?): Boolean = when (v) {
            null, is JsonNull -> true
            is JsonArray -> v.isEmpty()
            is JsonPrimitive -> (v.isString && v.content.isEmpty()) || (!v.isString && v.booleanOrNull == false)
            else -> false
        }

        /** Number(x) in JavaScript fuer die ueblichen Faelle (leer/Text -> NaN als null) */
        fun jsNumber(s: String): Double? = s.trim().let { if (it.isEmpty()) 0.0 else it.toDoubleOrNull() }

        fun number(v: JsonElement?): Double? = when (v) {
            is JsonPrimitive -> if (v.isString) jsNumber(v.content) else v.booleanOrNull?.let { if (it) 1.0 else 0.0 } ?: v.doubleOrNull
            else -> null
        }

        fun text(v: JsonElement?): String = when (v) {
            null, is JsonNull -> ""
            is JsonPrimitive -> if (v.isString) v.content else v.doubleOrNull?.let(::numText) ?: v.content
            is JsonArray -> v.joinToString(",") { text(it) }
            else -> "[object Object]"
        }

        fun numText(n: Double): String = if (n == Math.floor(n) && !n.isInfinite() && Math.abs(n) < 1e15) n.toLong().toString() else n.toString()

        private fun less(a: JsonElement, b: JsonElement): Boolean {
            val x = a as? JsonPrimitive ?: return false
            val y = b as? JsonPrimitive ?: return false
            if (x.isString && y.isString) return x.content < y.content
            val nx = number(x) ?: return false
            val ny = number(y) ?: return false
            return nx < ny
        }

        /** Berechnete Eigenschaft (A / B, A in % von B, A * B, A + B, A - B) wie im Web, gerundet auf 4 Stellen */
        fun calc(p: DbProp, values: JsonObject): Double? {
            fun n(k: String?): Double? = k?.let { (values[it] as? JsonPrimitive)?.takeIf { e -> e !is JsonNull && e.content.isNotEmpty() } }?.let { number(it) }
            var a = n(p.a)
            var b = n(p.b)
            val op = p.op?.ifEmpty { null } ?: "div"
            if (op == "add" || op == "sub") {
                if (a == null && b == null) return null
                a = a ?: 0.0
                b = b ?: 0.0
            } else if (a == null || b == null) return null
            val r = when (op) {
                "div", "pct" -> if (b == 0.0) return null else a / b * (if (op == "pct") 100 else 1)
                "mul" -> a * b
                "add" -> a + b
                else -> a - b
            }
            return Math.round(r * 10000) / 10000.0
        }
    }
}
