package io.github.yggdrasil_sh.saga.db

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

// Datenbanken (2.4): Schema, Ansichten und Zeilen aus GET /api/notes/{id}/rows - ohne Oberflaeche, damit Filter,
// Sortierung, Bearbeiten und das Zuordnen der Ansichten in Unit-Tests pruefbar sind. Alles tolerant: unbekannte Typen
// und Felder bleiben erhalten (das Schema wird beim Speichern unveraendert mit zurueckgeschickt).

internal fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
internal fun JsonObject.num(k: String): Double? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.toDoubleOrNull()
internal fun JsonElement?.prim(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

data class DbOption(val id: String, val name: String, val color: String)

data class DbProp(
    val id: String,
    val name: String,
    val type: String,
    val options: List<DbOption> = emptyList(),
    val fmt: String? = null,
    val show: String? = null,
    /** Relation: Ziel-Datenbank */
    val target: Long? = null,
    /** Berechnet: A op B */
    val a: String? = null,
    val b: String? = null,
    val op: String? = null,
) {
    fun option(id: String?): DbOption? = if (id == null) null else options.firstOrNull { it.id == id }
    fun optionIndex(id: String?): Int = options.indexOfFirst { it.id == id }
    /** Werte, die man in der App setzen kann (Erstellt/Bearbeitet/Berechnet: nur lesen) */
    val editable: Boolean get() = type in EDITABLE
    val isAuto: Boolean get() = type in AUTO
    val numLike: Boolean get() = type == "number" || type == "calc"

    companion object {
        /** Alle Typen, die der Server kennt (saga/notes.py PROP_TYPES) */
        val TYPES = listOf("text", "number", "select", "multi_select", "date", "checkbox", "url", "relation", "calc", "created", "edited")
        val EDITABLE = setOf("text", "number", "select", "multi_select", "date", "checkbox", "url", "relation", "email", "phone")
        val AUTO = setOf("created", "edited")
        val TITLE = DbProp("title", "", "text")

        fun parse(o: JsonObject): DbProp? {
            val id = o.str("id")?.ifEmpty { null } ?: return null
            val opts = (o["options"] as? JsonArray).orEmpty().mapNotNull { e ->
                val x = e as? JsonObject ?: return@mapNotNull null
                DbOption(x.str("id") ?: return@mapNotNull null, x.str("name").orEmpty(), x.str("color") ?: "gray")
            }
            return DbProp(
                id = id, name = o.str("name").orEmpty().ifEmpty { id }, type = o.str("type") ?: "text", options = opts,
                fmt = o.str("fmt") ?: o.str("format"), show = o.str("show"), target = o.num("target")?.toLong(),
                a = o.str("a"), b = o.str("b"), op = o.str("op"),
            )
        }
    }
}

data class DbFilter(val pid: String, val op: String, val value: String = "") {
    fun toJson(): JsonObject = JsonObject(mapOf("pid" to JsonPrimitive(pid), "op" to JsonPrimitive(op), "value" to JsonPrimitive(value)))
    /** Operator ohne Wert (ist leer, angehakt, heute ...) */
    val noValue: Boolean get() = op in NO_VALUE

    companion object {
        val NO_VALUE = setOf("is_empty", "not_empty", "checked", "unchecked", "today", "this_week", "this_month")
        fun parse(e: JsonElement): DbFilter? {
            val o = e as? JsonObject ?: return null
            return DbFilter(o.str("pid") ?: return null, o.str("op") ?: return null, (o["value"]).prim().orEmpty())
        }

        /** Operatoren je Typ wie im Web (OPS in static/pages.js), erster = Vorgabe */
        fun ops(type: String): List<String> = when (type) {
            "number", "calc" -> listOf("eq", "gt", "lt", "is_empty", "not_empty")
            "select" -> listOf("is", "is_not", "is_empty", "not_empty")
            "date" -> listOf("eq", "before", "after", "today", "this_week", "this_month", "is_empty", "not_empty")
            "created", "edited" -> listOf("after", "before", "eq", "today", "this_week", "this_month")
            "checkbox" -> listOf("checked", "unchecked")
            else -> listOf("contains", "not_contains", "is_empty", "not_empty")   // Text, URL, Mehrfachauswahl, Relation
        }
    }
}

data class DbSort(val pid: String, val desc: Boolean = false) {
    fun toJson(): JsonObject = JsonObject(mapOf("pid" to JsonPrimitive(pid), "dir" to JsonPrimitive(if (desc) "desc" else "asc")))

    companion object {
        fun parse(e: JsonElement?): DbSort? {
            val o = e as? JsonObject ?: return null
            return DbSort(o.str("pid") ?: return null, o.str("dir") == "desc")
        }
    }
}

/** Eine Ansicht (schema.views[i]) - raw bleibt vollstaendig erhalten (Breiten, Farben, Berechnungen ...) */
data class DbView(val raw: JsonObject) {
    val id: String get() = raw.str("id").orEmpty()
    val name: String get() = raw.str("name").orEmpty()
    /** Unbekannte Darstellung: Tabelle (wie renderDb im Web) */
    val type: String get() = raw.str("type")?.takeIf { it in TYPES } ?: "table"
    val filters: List<DbFilter> get() = (raw["filters"] as? JsonArray).orEmpty().mapNotNull(DbFilter::parse)
    /** bis zu 3 Stufen; altes Einzelfeld "sort" wird uebernommen (wie view_of auf dem Server) */
    val sorts: List<DbSort>
        get() = (raw["sorts"] as? JsonArray)?.mapNotNull(DbSort::parse)?.takeIf { it.isNotEmpty() }
            ?: listOfNotNull(DbSort.parse(raw["sort"]))
    val groupBy: String? get() = raw.str("group_by")
    val group: String? get() = raw.str("group")
    val dateBy: String? get() = raw.str("date_by")
    val hidden: Set<String> get() = (raw["hidden"] as? JsonArray).orEmpty().mapNotNull { it.prim() }.toSet()
    val order: List<String> get() = (raw["order"] as? JsonArray).orEmpty().mapNotNull { it.prim() }
    val colors: List<Pair<DbFilter, String>>
        get() = (raw["colors"] as? JsonArray).orEmpty().mapNotNull { e ->
            val f = DbFilter.parse(e) ?: return@mapNotNull null
            f to ((e as JsonObject).str("color") ?: return@mapNotNull null)
        }

    companion object {
        val TYPES = listOf("table", "board", "list", "gallery", "calendar")
    }
}

data class DbRow(
    val id: Long,
    val title: String,
    val icon: String? = null,
    val props: JsonObject = JsonObject(emptyMap()),
    val created: Double = 0.0,
    val updated: Double = 0.0,
    /** Titelbild oder erstes Bild der Seite (Galerie) */
    val preview: String? = null,
)

data class DbSchema(val raw: JsonObject) {
    val props: List<DbProp> = (raw["props"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(DbProp::parse) }
    /** Ohne gespeicherte Ansicht: eine Tabelle (wie der Server) */
    val views: List<DbView> = (raw["views"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::DbView) }
        .filter { it.id.isNotEmpty() }
        .ifEmpty { listOf(DbView(JsonObject(mapOf("id" to JsonPrimitive("tabelle"), "name" to JsonPrimitive(""), "type" to JsonPrimitive("table"))))) }
    /** gesperrt: Spalten/Ansichten/Filter geschuetzt, Eintraege frei */
    val locked: Boolean = (raw["locked"] as? JsonPrimitive)?.booleanOrNull == true
    val titleName: String? = raw.str("title_name")
    /** Zaehler des Servers je Schema-Speichern (aeltere Server: 0) */
    val srev: Int = (raw["srev"] as? JsonPrimitive)?.content?.toIntOrNull()?.coerceAtLeast(0) ?: 0

    fun prop(id: String?): DbProp? = if (id == "title") DbProp.TITLE else props.firstOrNull { it.id == id }
    /** Ansicht mit dieser ID, sonst die erste (curView im Web) */
    fun view(id: String?): DbView = views.firstOrNull { it.id == id } ?: views.first()

    /** Gruppier-Spalte wie group_prop() auf dem Server: Board group_by (sonst erste Auswahl), Tabelle/Liste group */
    fun groupProp(v: DbView): DbProp? {
        fun sel(id: String?) = props.firstOrNull { it.id == id && it.type == "select" }
        return when (v.type) {
            "board" -> sel(v.groupBy) ?: props.firstOrNull { it.type == "select" }
            "gallery", "calendar" -> null
            else -> sel(v.group)
        }
    }

    /** Datumsspalte des Kalenders: date_by, sonst die erste Datumsspalte */
    fun dateProp(v: DbView): DbProp? =
        props.firstOrNull { it.id == v.dateBy && (it.type == "date" || it.isAuto) } ?: props.firstOrNull { it.type == "date" }

    /** Spalten der Ansicht in ihrer Reihenfolge, ohne ausgeblendete */
    fun visibleProps(v: DbView): List<DbProp> {
        val order = v.order
        val sorted = if (order.isEmpty()) props else props.sortedBy { p -> order.indexOf(p.id).let { if (it < 0) Int.MAX_VALUE else it } }
        val hidden = v.hidden
        return sorted.filter { it.id !in hidden }
    }
}

data class DbGroup(val id: String?, val count: Int)

data class DbTemplate(val id: Long, val title: String, val icon: String?)

/**
 * Antwort von /rows. paged = der Server hat Ansicht/Filter/Seiten angewandt (2.2+, Felder total/matched); sonst
 * (2.1 oder Gegenstelle ohne die Parameter) kommen alle Zeilen und die App filtert/sortiert selbst (DbQuery).
 */
data class DbPage(
    val schema: DbSchema,
    val rows: List<DbRow>,
    val role: String = "owner",
    val total: Int? = null,
    val matched: Int? = null,
    val offset: Int = 0,
    val groups: List<DbGroup>? = null,
    val templates: List<DbTemplate> = emptyList(),
) {
    val paged: Boolean get() = total != null && matched != null
    val readOnly: Boolean get() = role == "view" || role == "comment"

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        fun parse(text: String): DbPage {
            val o = json.parseToJsonElement(text) as? JsonObject ?: throw IllegalArgumentException("rows")
            return parse(o)
        }

        fun parse(o: JsonObject): DbPage {
            val rows = (o["rows"] as? JsonArray).orEmpty().mapNotNull { e -> (e as? JsonObject)?.let(::row) }
            val groups = (o["groups"] as? JsonArray)?.mapNotNull { e ->
                val g = e as? JsonObject ?: return@mapNotNull null
                DbGroup(g["id"].prim(), g.num("count")?.toInt() ?: 0)
            }
            val templates = (o["templates"] as? JsonArray).orEmpty().mapNotNull { e ->
                val t = e as? JsonObject ?: return@mapNotNull null
                DbTemplate(t.num("id")?.toLong() ?: return@mapNotNull null, t.str("title").orEmpty(), t.str("icon")?.ifEmpty { null })
            }
            return DbPage(
                schema = DbSchema(o["schema"] as? JsonObject ?: JsonObject(emptyMap())), rows = rows,
                role = o.str("role") ?: "owner", total = o.num("total")?.toInt(), matched = o.num("matched")?.toInt(),
                offset = o.num("offset")?.toInt() ?: 0, groups = groups, templates = templates,
            )
        }

        fun row(x: JsonObject): DbRow? = DbRow(
            id = x.num("id")?.toLong() ?: return null,
            title = x.str("title").orEmpty(),
            icon = x.str("icon")?.ifEmpty { null },
            props = x["props"] as? JsonObject ?: JsonObject(emptyMap()),
            created = x.num("created") ?: 0.0,
            updated = x.num("updated") ?: 0.0,
            preview = (x.str("preview") ?: x.str("cover"))?.ifEmpty { null },
        )
    }
}
