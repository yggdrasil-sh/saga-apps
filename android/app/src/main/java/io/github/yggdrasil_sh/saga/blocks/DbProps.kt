package io.github.yggdrasil_sh.saga.blocks

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * Eigenschaften einer Datenbank-Zeile (Notion-Stil, saga/notes.py) zum Lesen aufbereiten: Schema der Datenbank
 * {"props":[{id,name,type,options,format,...}]} + Werte der Zeile {id: wert}. Alles tolerant - unbekannte Typen und
 * unerwartete Formen (null, Zahl statt Text, Objekt statt Liste) werden als Text gezeigt statt die Seite scheitern zu lassen.
 */
object DbProps {
    data class Field(val id: String, val name: String, val type: String, val value: String)

    fun fields(
        schema: JsonObject?,
        props: JsonObject?,
        created: Double? = null,
        updated: Double? = null,
        titleOf: (Long) -> String? = { null },
        time: (Double) -> String = { it.toLong().toString() },
        day: (String) -> String = { it },
        number: (Double) -> String = ::num,
        locale: java.util.Locale = java.util.Locale.getDefault(),
    ): List<Field> {
        val defs = (schema?.get("props") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val values = props ?: JsonObject(emptyMap())
        val out = ArrayList<Field>()
        for (p in defs) {
            val id = BlockDoc.str(p, "id")
            if (id.isEmpty()) continue
            val type = BlockDoc.str(p, "type")
            val name = BlockDoc.str(p, "name").ifEmpty { id }
            val v = when (type) {
                "created" -> created?.let(time)
                "edited" -> updated?.let(time)
                "calc" -> calc(p, values)?.let { numberText(p, it, number, locale) }
                else -> format(p, type, values[id], titleOf, day, number, locale)
            }
            if (!v.isNullOrBlank()) out.add(Field(id, name, type, v))
        }
        return out
    }

    private fun option(p: JsonObject, id: String): String =
        (p["options"] as? JsonArray)?.firstNotNullOfOrNull { o -> (o as? JsonObject)?.takeIf { BlockDoc.str(it, "id") == id }?.let { BlockDoc.str(it, "name") } }
            ?: id

    private fun list(v: JsonElement?): List<JsonElement> = when (v) {
        null, is JsonNull -> emptyList()
        is JsonArray -> v
        else -> listOf(v)
    }

    private fun prim(v: JsonElement?): String? = (v as? JsonPrimitive)?.contentOrNull

    fun format(
        p: JsonObject, type: String, v: JsonElement?, titleOf: (Long) -> String? = { null },
        day: (String) -> String = { it }, number: (Double) -> String = ::num,
        locale: java.util.Locale = java.util.Locale.getDefault(),
    ): String? {
        if (v == null || v is JsonNull) return null
        return when (type) {
            "select" -> prim(v)?.let { option(p, it) }
            "multi_select" -> list(v).mapNotNull { prim(it)?.let { id -> option(p, id) } }.joinToString(", ")
            "checkbox" -> if ((v as? JsonPrimitive)?.booleanOrNull == true || prim(v) == "1") "☑" else "☐"
            "number" -> (v as? JsonPrimitive)?.doubleOrNull?.let { n -> numberText(p, n, number, locale) } ?: prim(v)
            "relation" -> list(v).mapNotNull { e -> prim(e)?.let { s -> s.toLongOrNull()?.let { titleOf(it) } ?: s } }.joinToString(", ")
            "date" -> when (v) {
                is JsonObject -> listOfNotNull(prim(v["start"]), prim(v["end"])).map(day).joinToString(" – ")
                else -> prim(v)?.let(day)
            }
            "text", "url" -> prim(v)?.let { InlineHtml.plain(it) }
            else -> when (v) {
                is JsonPrimitive -> v.contentOrNull?.let { InlineHtml.plain(it) }
                is JsonArray -> v.mapNotNull { prim(it) }.joinToString(", ")
                else -> null
            }
        }
    }

    /** Zahl nach dem Format der Eigenschaft (wie numText() im Web): Prozent ganzzahlig, Euro mit 2 Stellen */
    fun numberText(p: JsonObject, n: Double, number: (Double) -> String = ::num, locale: java.util.Locale = java.util.Locale.getDefault()): String =
        when (val f = BlockDoc.str(p, "fmt").ifEmpty { BlockDoc.str(p, "format") }) {
            "percent", "euro" -> NumFormat.prop(n, f, locale)
            else -> number(n)
        }

    /** Berechnete Eigenschaft wie saga/notes.py _calc_value (A / B, A in % von B, A * B, A + B, A - B) */
    fun calc(p: JsonObject, values: JsonObject): Double? {
        fun n(k: String): Double? = (values[BlockDoc.str(p, k)] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
        var a = n("a")
        var b = n("b")
        val op = BlockDoc.str(p, "op").ifEmpty { "div" }
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

    /** Zahl in der Schreibweise der Sprache (1,7857 statt 1.7857), hoechstens 4 Nachkommastellen */
    fun localNum(locale: java.util.Locale): (Double) -> String {
        val f = java.text.NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 4 }
        return { n -> if (n.isNaN() || n.isInfinite()) num(n) else f.format(n) }
    }

    /** Datum (ISO "2026-10-04", auch mit Uhrzeit) in der Schreibweise der Sprache; Unlesbares bleibt wie es ist */
    fun localDay(locale: java.util.Locale): (String) -> String = { iso ->
        try {
            if (iso.length > 10) {
                java.time.LocalDateTime.parse(iso.take(16))
                    .format(java.time.format.DateTimeFormatter.ofLocalizedDateTime(java.time.format.FormatStyle.MEDIUM, java.time.format.FormatStyle.SHORT).withLocale(locale))
            } else {
                java.time.LocalDate.parse(iso).format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM).withLocale(locale))
            }
        } catch (_: Exception) {
            iso
        }
    }

    fun num(n: Double): String = if (n == Math.floor(n) && !n.isInfinite() && Math.abs(n) < 1e15) n.toLong().toString() else n.toString()
}
