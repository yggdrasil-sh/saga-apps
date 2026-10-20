package io.github.yggdrasil_sh.saga.db

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.net.URLEncoder
import java.time.LocalDate
import java.util.Locale

/** Werte bearbeiten: Eingaben in gespeicherte Werte umwandeln (wie setProp/_clean_props) und Zeilen lokal anpassen */
object DbValues {
    /** Zahl aus einer Eingabe in der Sprache der Oberflaeche ("1.200,5" de, "1,200.5" en); auch "1200.5" geht immer */
    fun parseNumber(input: String, locale: Locale = Locale.getDefault()): Double? {
        // genau wie HK.parseNum im Web (static/common.js)
        val s = input.replace(Regex("[\\s'’  ]"), "").replace(Regex("^−"), "-")
        if (s.isEmpty()) return null
        val dec = java.text.DecimalFormatSymbols.getInstance(locale).decimalSeparator
        val v: Double? = when {
            Regex("^[+-]?\\d+$").matches(s) -> s.toDouble()
            s.contains('.') && s.contains(',') -> {
                val d = if (s.lastIndexOf('.') > s.lastIndexOf(',')) '.' else ','
                val g = if (d == '.') ',' else '.'
                if (Regex("^[+-]?\\d{1,3}(\\$g\\d{3})+\\$d\\d*$").matches(s)) s.replace(g.toString(), "").replace(d, '.').toDoubleOrNull() else null
            }
            !s.contains('.') && !s.contains(',') -> if (Regex("^[+-]?(\\d+\\.?\\d*|\\.\\d+)(e[+-]?\\d+)?$", RegexOption.IGNORE_CASE).matches(s)) s.toDoubleOrNull() else null
            else -> {
                val c = if (s.contains('.')) '.' else ','
                val n = s.count { it == c }
                val grouped = Regex("^[+-]?\\d{1,3}(\\$c\\d{3})+$").matches(s)
                when {
                    grouped && (c != dec || n > 1) -> s.replace(c.toString(), "").toDoubleOrNull()
                    n == 1 && Regex("^[+-]?(\\d+[.,]\\d*|[.,]\\d+)$").matches(s) -> s.replace(c, '.').toDoubleOrNull()
                    else -> null
                }
            }
        }
        return v?.takeIf { !it.isNaN() && !it.isInfinite() && Math.abs(it) <= 1e15 }
    }

    /** Zahl zum Bearbeiten in der Sprache der Oberflaeche ohne Tausendertrennung (editNum im Web) */
    fun editText(v: JsonElement?, locale: Locale = Locale.getDefault()): String {
        val n = (v as? JsonPrimitive)?.content?.toDoubleOrNull() ?: return v.prim().orEmpty()
        val s = DbQuery.numText(n)
        return if (s.contains('E') || s.contains('e')) s else s.replace('.', java.text.DecimalFormatSymbols.getInstance(locale).decimalSeparator)
    }

    /** Text/URL/E-Mail/Telefon: leer = Wert entfernen; Text hoechstens 2000 Zeichen wie der Server */
    fun text(input: String): JsonElement = input.take(2000).let { if (it.isEmpty()) JsonNull else JsonPrimitive(it) }

    fun number(input: String, locale: Locale = Locale.getDefault()): JsonElement? =
        if (input.isBlank()) JsonNull else parseNumber(input, locale)?.let { if (it == Math.floor(it)) JsonPrimitive(it.toLong()) else JsonPrimitive(it) }

    fun date(d: LocalDate?): JsonElement = d?.let { JsonPrimitive(it.toString()) } ?: JsonNull

    fun parseDate(v: JsonElement?): LocalDate? = try {
        (v as? JsonObject)?.get("start").prim()?.let { LocalDate.parse(it.take(10)) } ?: v.prim()?.let { LocalDate.parse(it.take(10)) }
    } catch (_: Exception) {
        null
    }

    fun checkbox(on: Boolean): JsonElement = if (on) JsonPrimitive(true) else JsonNull

    /** Auswahl: dieselbe Option noch einmal = abwaehlen (wie im Web) */
    fun select(cur: JsonElement?, id: String): JsonElement = if (cur.prim() == id) JsonNull else JsonPrimitive(id)

    fun ids(v: JsonElement?): List<String> = (v as? JsonArray)?.mapNotNull { it.prim() } ?: listOfNotNull(v.prim())

    /** Mehrfachauswahl/Relation: Eintrag an- bzw. abwaehlen; leer = Wert entfernen */
    fun toggle(cur: JsonElement?, id: String): JsonElement {
        val a = ids(cur).toMutableList()
        if (!a.remove(id)) a.add(id)
        return if (a.isEmpty()) JsonNull else JsonArray(a.map(::JsonPrimitive))
    }

    fun toggleRelation(cur: JsonElement?, id: Long): JsonElement {
        val a = (cur as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull?.toLong() }.orEmpty().toMutableList()
        if (!a.remove(id)) a.add(id)
        return if (a.isEmpty()) JsonNull else JsonArray(a.map(::JsonPrimitive))
    }

    fun relationIds(v: JsonElement?): List<Long> = (v as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content?.toDoubleOrNull()?.toLong() }.orEmpty()

    /** Leere Werte (null, "", false, []) entfernt der Server - lokal genauso */
    fun isCleared(v: JsonElement?): Boolean = v == null || v is JsonNull || (v is JsonPrimitive && v.isString && v.content.isEmpty()) ||
        (v is JsonPrimitive && !v.isString && v.content == "false") || (v is JsonArray && v.isEmpty())

    /** Zeile lokal aendern (optimistisch, vor/ohne Antwort des Servers) */
    fun apply(r: DbRow, pid: String, v: JsonElement?): DbRow {
        val m = LinkedHashMap(r.props)
        if (isCleared(v)) m.remove(pid) else m[pid] = v!!
        return r.copy(props = JsonObject(m))
    }

    /** Link nur fuer http(s)/mailto/tel - nie javascript: o. ae. */
    fun safeLink(type: String, value: String): String? {
        val v = value.trim()
        return when (type) {
            "email" -> if (Regex("^[^@\\s]+@[^@\\s]+$").matches(v)) "mailto:$v" else null
            "phone" -> v.filter { it.isDigit() || it == '+' }.takeIf { it.length >= 3 }?.let { "tel:$it" }
            else -> v.takeIf { it.startsWith("https://", true) || it.startsWith("http://", true) || it.startsWith("mailto:", true) }
        }
    }
}

/** Ansichten bearbeiten: neues Schema (raw) mit geaenderter Ansicht - alle unbekannten Felder bleiben erhalten */
object DbViews {
    private const val CHARS = "abcdefghijklmnopqrstuvwxyz0123456789"

    fun newId(rnd: java.util.Random = java.util.Random()): String = String(CharArray(8) { CHARS[rnd.nextInt(CHARS.length)] })

    private fun views(s: JsonObject): List<JsonObject> = (s["views"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        .ifEmpty { DbSchema(s).views.map { it.raw } }

    private fun withViews(s: JsonObject, vs: List<JsonObject>): JsonObject = JsonObject(LinkedHashMap(s).apply { put("views", JsonArray(vs)) })

    /** Eine Ansicht aendern (unbekannte ID: Schema unveraendert) */
    fun edit(s: JsonObject, id: String, f: (MutableMap<String, JsonElement>) -> Unit): JsonObject =
        withViews(s, views(s).map { v -> if (v.str("id") == id) JsonObject(LinkedHashMap<String, JsonElement>(v).apply(f)) else v })

    /** Neue Ansicht wie addView im Web: Board nach der ersten Auswahl, Kalender nach dem ersten Datum */
    fun add(s: JsonObject, type: String, name: String, id: String = newId()): JsonObject {
        val schema = DbSchema(s)
        val v = LinkedHashMap<String, JsonElement>()
        v["id"] = JsonPrimitive(id)
        v["name"] = JsonPrimitive(name.trim().take(40))
        v["type"] = JsonPrimitive(if (type in DbView.TYPES) type else "table")
        if (type == "board") schema.props.firstOrNull { it.type == "select" }?.let { v["group_by"] = JsonPrimitive(it.id) }
        if (type == "calendar") schema.props.firstOrNull { it.type == "date" }?.let { v["date_by"] = JsonPrimitive(it.id) }
        return withViews(s, views(s) + JsonObject(v))
    }

    fun rename(s: JsonObject, id: String, name: String): JsonObject {
        val n = name.trim().take(40)
        return if (n.isEmpty()) s else edit(s, id) { it["name"] = JsonPrimitive(n) }
    }

    /** Die letzte Ansicht bleibt (eine Datenbank hat immer mindestens eine) */
    fun delete(s: JsonObject, id: String): JsonObject {
        val vs = views(s)
        return if (vs.size <= 1) s else withViews(s, vs.filter { it.str("id") != id })
    }

    fun setType(s: JsonObject, id: String, type: String): JsonObject {
        val schema = DbSchema(s)
        return edit(s, id) { v ->
            v["type"] = JsonPrimitive(type)
            if (type == "board" && v["group_by"] == null) schema.props.firstOrNull { it.type == "select" }?.let { v["group_by"] = JsonPrimitive(it.id) }
            if (type == "calendar" && v["date_by"] == null) schema.props.firstOrNull { it.type == "date" }?.let { v["date_by"] = JsonPrimitive(it.id) }
        }
    }

    fun setFilters(s: JsonObject, id: String, fs: List<DbFilter>): JsonObject = edit(s, id) { v ->
        if (fs.isEmpty()) v.remove("filters") else v["filters"] = JsonArray(fs.take(50).map { it.toJson() })
    }

    /** bis zu 3 Stufen (wie der Server); das alte Einzelfeld "sort" faellt weg */
    fun setSorts(s: JsonObject, id: String, ss: List<DbSort>): JsonObject = edit(s, id) { v ->
        v.remove("sort")
        if (ss.isEmpty()) v.remove("sorts") else v["sorts"] = JsonArray(ss.take(3).map { it.toJson() })
    }

    /** Board: Spalten nach; Tabelle/Liste: Gruppieren (null = nicht gruppieren); Kalender: Datum aus */
    fun setKey(s: JsonObject, id: String, key: String, value: String?): JsonObject = edit(s, id) { v ->
        if (value.isNullOrEmpty()) v.remove(key) else v[key] = JsonPrimitive(value)
    }

    fun setHidden(s: JsonObject, id: String, pid: String, hidden: Boolean): JsonObject = edit(s, id) { v ->
        val cur = (v["hidden"] as? JsonArray).orEmpty().mapNotNull { it.prim() }.toMutableList()
        if (hidden) {
            if (pid !in cur) cur.add(pid)
        } else cur.remove(pid)
        if (cur.isEmpty()) v.remove("hidden") else v["hidden"] = JsonArray(cur.map(::JsonPrimitive))
    }

    /** Neue Option fuer Auswahl/Mehrfachauswahl (Name eingetippt) -> (Schema, Option-ID) */
    fun addOption(s: JsonObject, pid: String, name: String, id: String = newId()): Pair<JsonObject, String> {
        val colors = listOf("gray", "brown", "orange", "yellow", "green", "blue", "purple", "pink", "red")
        val props = (s["props"] as? JsonArray).orEmpty().map { e ->
            val p = e as? JsonObject ?: return@map e
            if (p.str("id") != pid) return@map p
            val opts = (p["options"] as? JsonArray).orEmpty()
            val o = JsonObject(mapOf("id" to JsonPrimitive(id), "name" to JsonPrimitive(name.trim().take(40)), "color" to JsonPrimitive(colors[opts.size % colors.size])))
            JsonObject(LinkedHashMap(p).apply { put("options", JsonArray(opts + o)) })
        }
        return JsonObject(LinkedHashMap(s).apply { put("props", JsonArray(props)) }) to id
    }
}

/** Abfrage an GET /api/notes/{id}/rows (APP-API "Datenbank-Zeilen") */
object DbParams {
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    fun query(
        view: String?,
        filters: List<DbFilter>? = null,
        sorts: List<DbSort>? = null,
        q: String = "",
        group: String? = null,
        limit: Int? = null,
        offset: Int = 0,
        tz: String? = null,
        today: LocalDate? = null,
    ): String {
        val p = ArrayList<String>()
        if (view != null) p.add("view=" + enc(view))
        if (filters != null) p.add("filters=" + enc(JsonArray(filters.map { it.toJson() }).toString()))
        if (sorts != null) p.add("sorts=" + enc(JsonArray(sorts.map { it.toJson() }).toString()))
        if (q.isNotBlank()) p.add("q=" + enc(q.trim().take(500)))
        if (group != null) p.add("group=" + enc(group))
        if (limit != null) p.add("limit=" + limit.coerceIn(1, 5000))
        if (offset > 0) p.add("offset=$offset")
        if (tz != null) p.add("tz=" + enc(tz))
        if (today != null) p.add("today=$today")
        return p.joinToString("&")
    }

    /** Kalender: nur die Eintraege eines Monats (Datum nach dem Vortag und vor dem Folgemonat) */
    fun monthFilters(dateProp: String, month: LocalDate): List<DbFilter> {
        val first = month.withDayOfMonth(1)
        return listOf(DbFilter(dateProp, "after", first.minusDays(1).toString()), DbFilter(dateProp, "before", first.plusMonths(1).toString()))
    }

    /** Board: eine Spalte (Option oder "Ohne") */
    fun columnFilter(groupProp: String, option: String?): DbFilter =
        if (option == null) DbFilter(groupProp, "is_empty") else DbFilter(groupProp, "is", option)
}
