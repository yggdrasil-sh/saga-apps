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
internal fun JsonObject.bool(k: String): Boolean = (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull == true
internal fun JsonElement?.prim(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

/** group: Status-Gruppe todo/doing/done (nur bei status: true, 2.5 status1) */
data class DbOption(val id: String, val name: String, val color: String, val group: String? = null)

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
    /** 2.5 Formel/Rollup: Ergebnistyp (number/text/checkbox/date/list) - setzt der Server */
    val rtype: String? = null,
    /** 2.5 Status: Auswahl mit Gruppen */
    val status: Boolean = false,
    /** 2.5 Person: mehrere erlaubt (Standard ja) */
    val multi: Boolean = true,
    /** 2.5 Selbst-Relation: parent/children/blocked_by/blocking */
    val role: String? = null,
    /** 2.5 Datum: Vorgaben fuer die Eingabe (Uhrzeit/Ende-Feld zeigen) */
    val time: Boolean = false,
    val range: Boolean = false,
    /** 2.5 Dateien: hoechstens so viele je Zelle */
    val max: Int = 10,
) {
    fun option(id: String?): DbOption? = if (id == null) null else options.firstOrNull { it.id == id }
    fun optionIndex(id: String?): Int = options.indexOfFirst { it.id == id }
    /** Werte, die man in der App setzen kann (Erstellt/Bearbeitet/Berechnet: nur lesen) */
    val editable: Boolean get() = type in EDITABLE
    val isAuto: Boolean get() = type in AUTO
    /** 2.5: Formel/Rollup - Werte rechnet der Server, nie zurueckschreiben */
    val computed: Boolean get() = type == "formula" || type == "rollup"
    /** Typ fuer Anzeige/Filter/Sortierung: Formel/Rollup wie ihr Ergebnistyp (list wie Relation-Liste) */
    val effType: String get() = if (computed) (rtype ?: "text") else type
    val numLike: Boolean get() = type == "number" || type == "calc" || (computed && rtype == "number")
    /** Status-Optionen in Gruppen-Reihenfolge (Zu erledigen, In Arbeit, Erledigt), sonst Schema-Reihenfolge */
    fun optionsGrouped(): List<DbOption> = if (!status) options else STATUS_GROUPS.flatMap { g -> options.filter { (it.group ?: "todo") == g } }
    /** erste Option einer Status-Gruppe (Board-Spalte, Abhaken) */
    fun firstOfGroup(group: String): DbOption? = options.firstOrNull { (it.group ?: "todo") == group }
    /** Status-Gruppe des Werts (null = leer/unbekannt) */
    fun groupOf(optionId: String?): String? = option(optionId)?.let { it.group ?: "todo" }

    companion object {
        /** Alle Typen, die der Server kennt (saga/notes.py PROP_TYPES) */
        val TYPES = listOf("text", "number", "select", "multi_select", "date", "checkbox", "url", "relation", "calc", "created", "edited")
        val EDITABLE = setOf("text", "number", "select", "multi_select", "date", "checkbox", "url", "relation", "email", "phone", "person", "files")
        val STATUS_GROUPS = listOf("todo", "doing", "done")
        /** Gruppensymbol vor dem Namen: todo ○, doing ◐, done ● */
        fun groupSymbol(g: String?): String = when (g) {
            "doing" -> "◐"
            "done" -> "●"
            else -> "○"
        }
        val AUTO = setOf("created", "edited")
        val TITLE = DbProp("title", "", "text")

        fun parse(o: JsonObject): DbProp? {
            val id = o.str("id")?.ifEmpty { null } ?: return null
            val opts = (o["options"] as? JsonArray).orEmpty().mapNotNull { e ->
                val x = e as? JsonObject ?: return@mapNotNull null
                DbOption(x.str("id") ?: return@mapNotNull null, x.str("name").orEmpty(), x.str("color") ?: "gray", x.str("group")?.takeIf { it in STATUS_GROUPS })
            }
            return DbProp(
                id = id, name = o.str("name").orEmpty().ifEmpty { id }, type = o.str("type") ?: "text", options = opts,
                fmt = o.str("fmt") ?: o.str("format"), show = o.str("show"), target = o.num("target")?.toLong(),
                a = o.str("a"), b = o.str("b"), op = o.str("op"),
                rtype = o.str("rtype"), status = o.bool("status"), multi = (o["multi"] as? JsonPrimitive)?.booleanOrNull ?: true,
                role = o.str("role"), time = o.bool("time"), range = o.bool("range"), max = o.num("max")?.toInt()?.coerceIn(1, 20) ?: 10,
            )
        }
    }
}

data class DbFilter(val pid: String, val op: String, val value: String = "") {
    fun toJson(): JsonObject = JsonObject(mapOf("pid" to JsonPrimitive(pid), "op" to JsonPrimitive(op), "value" to JsonPrimitive(value)))
    /** Operator ohne Wert (ist leer, angehakt, heute ...) */
    val noValue: Boolean get() = op in NO_VALUE

    companion object {
        val NO_VALUE = setOf(
            "is_empty", "not_empty", "checked", "unchecked", "today", "this_week", "this_month", "me",
            // 2.5 relfilter1
            "yesterday", "tomorrow", "last_week", "next_week", "last_month", "next_month", "this_year", "overdue",
        )
        /** 2.5 relfilter1: relative Datumsfilter (nur anbieten, wenn der Server sie kennt) */
        val REL_DATE = listOf("yesterday", "tomorrow", "last_week", "next_week", "last_month", "next_month", "this_year", "overdue", "next_days", "past_days", "on_or_before", "on_or_after")
        fun parse(e: JsonElement): DbFilter? {
            val o = e as? JsonObject ?: return null
            return DbFilter(o.str("pid") ?: return null, o.str("op") ?: return null, (o["value"]).prim().orEmpty())
        }

        /** Operatoren je Typ wie im Web (OPS in static/pages.js), erster = Vorgabe */
        fun ops(type: String, features: Set<String> = emptySet()): List<String> {
            val rel = if ("relfilter1" in features) REL_DATE else emptyList()
            return when (type) {
                "number", "calc" -> listOf("eq", "gt", "lt", "is_empty", "not_empty")
                "select" -> listOf("is", "is_not", "is_empty", "not_empty")
                "date" -> listOf("eq", "before", "after", "today", "this_week", "this_month") + rel + listOf("is_empty", "not_empty")
                "created", "edited" -> listOf("after", "before", "eq", "today", "this_week", "this_month") + rel
                "checkbox" -> listOf("checked", "unchecked")
                "person" -> listOf("me", "contains", "not_contains", "is_empty", "not_empty")
                "files" -> listOf("is_empty", "not_empty")
                else -> listOf("contains", "not_contains", "is_empty", "not_empty")   // Text, URL, Mehrfachauswahl, Relation, Liste
            }
        }

        /** Operatoren einer Eigenschaft (Formel/Rollup wie ihr Ergebnistyp, Status zusaetzlich nach Gruppe) */
        fun ops(p: DbProp, features: Set<String> = emptySet()): List<String> {
            val base = ops(if (p.computed) (if (p.rtype == "number") "number" else p.rtype ?: "text") else p.type, features)
            return if (p.status && "status1" in features) base + "status_group" else base
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
    /** 2.5 tableopts1: Umbruch (alle Spalten bzw. nur diese), fixierte Spalten, Kalender-Woche */
    val wrap: Boolean get() = (raw["wrap"] as? JsonPrimitive)?.booleanOrNull == true
    val wrapCols: Set<String> get() = (raw["wrap_cols"] as? JsonArray).orEmpty().mapNotNull { it.prim() }.toSet()
    fun wraps(pid: String): Boolean = wrap || pid in wrapCols
    val freeze: Int get() = (raw["freeze"] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toInt()?.coerceIn(0, 3) ?: 0
    val week: Boolean get() = raw.str("range") == "week"
    /** 2.5 status1: Board/Gruppen nach Status-Gruppe statt je Option */
    val groupStatus: Boolean get() = (raw["group_status"] as? JsonPrimitive)?.booleanOrNull == true
    /** 2.5 subtasks1: Unteraufgaben-Darstellung {parent, mode} */
    val subMode: String? get() = (raw["sub"] as? JsonObject)?.str("mode")
    /** 2.5 chart1: Diagramm-Spezifikation (roh) */
    val chart: JsonObject? get() = raw["chart"] as? JsonObject
    /** 2.5 timeline1: Ende-Datum der Zeitleiste */
    val endBy: String? get() = raw.str("end_by")
    val colors: List<Pair<DbFilter, String>>
        get() = (raw["colors"] as? JsonArray).orEmpty().mapNotNull { e ->
            val f = DbFilter.parse(e) ?: return@mapNotNull null
            f to ((e as JsonObject).str("color") ?: return@mapNotNull null)
        }

    companion object {
        val TYPES = listOf("table", "board", "list", "gallery", "calendar", "chart", "timeline")
        /** Typen, die die App anlegen kann (Diagramm/Zeitleiste einrichten nur im Web) */
        val CREATABLE = listOf("table", "board", "list", "gallery", "calendar")
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
    /** 2.5: Laufzeitfehler je Formel-Zelle (pid -> Code) */
    val fxErr: Map<String, String> = emptyMap(),
    /** 2.5 subtasks1: Ebene (0 = oben) und Eltern-ID in verschachtelter Liste, "blockiert" */
    val depth: Int = 0,
    val subOf: Long? = null,
    val blocked: Boolean = false,
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
        // 2.5 dbgroup1/person1: nach Formel/Rollup/Person gruppiert - nur lesend, Werte und Gruppen kommen vom Server
        fun any(id: String?) = sel(id) ?: props.firstOrNull { it.id == id && (it.computed || it.type == "person") }
        return when (v.type) {
            "board" -> any(v.groupBy) ?: props.firstOrNull { it.type == "select" }
            "gallery", "calendar", "chart", "timeline" -> null
            else -> any(v.group)
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

data class DbGroup(val id: String?, val count: Int, val value: JsonElement? = null)

/** 2.5 person1: Eintrag im people-Verzeichnis einer Antwort (Kennung -> Anzeige); nie der Anmeldename */
data class DbPerson(val ref: String, val name: String, val avatar: String? = null, val me: Boolean = false, val remote: Boolean = false, val gone: Boolean = false) {
    companion object {
        fun parse(ref: String, o: JsonObject): DbPerson = DbPerson(
            ref, o.str("name").orEmpty(), o.str("avatar")?.ifEmpty { null }, o.bool("me"), o.bool("remote"), o.bool("gone"),
        )

        /** "people": {"~abc": {...}} */
        fun map(o: JsonElement?): Map<String, DbPerson> =
            (o as? JsonObject)?.mapNotNull { (k, v) -> (v as? JsonObject)?.let { k to parse(k, it) } }?.toMap().orEmpty()
    }
}

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
    /** 2.5 person1: Personen der Antwort */
    val people: Map<String, DbPerson> = emptyMap(),
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
                DbGroup(g["id"].prim(), g.num("count")?.toInt() ?: 0, g["value"])
            }
            val templates = (o["templates"] as? JsonArray).orEmpty().mapNotNull { e ->
                val t = e as? JsonObject ?: return@mapNotNull null
                DbTemplate(t.num("id")?.toLong() ?: return@mapNotNull null, t.str("title").orEmpty(), t.str("icon")?.ifEmpty { null })
            }
            return DbPage(
                schema = DbSchema(o["schema"] as? JsonObject ?: JsonObject(emptyMap())), rows = rows,
                role = o.str("role") ?: "owner", total = o.num("total")?.toInt(), matched = o.num("matched")?.toInt(),
                offset = o.num("offset")?.toInt() ?: 0, groups = groups, templates = templates, people = DbPerson.map(o["people"]),
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
            fxErr = (x["fx_err"] as? JsonObject)?.mapNotNull { (k, v) -> v.prim()?.let { k to it } }?.toMap().orEmpty(),
            depth = x.num("depth")?.toInt()?.coerceIn(0, 10) ?: 0,
            subOf = x.num("sub_of")?.toLong(),
            blocked = x.bool("blocked"),
        )
    }
}
