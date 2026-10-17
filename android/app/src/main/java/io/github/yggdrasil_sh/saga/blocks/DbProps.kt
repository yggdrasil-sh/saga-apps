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
                "calc" -> calc(p, values)?.let { num(it) }
                else -> format(p, type, values[id], titleOf)
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

    fun format(p: JsonObject, type: String, v: JsonElement?, titleOf: (Long) -> String? = { null }): String? {
        if (v == null || v is JsonNull) return null
        return when (type) {
            "select" -> prim(v)?.let { option(p, it) }
            "multi_select" -> list(v).mapNotNull { prim(it)?.let { id -> option(p, id) } }.joinToString(", ")
            "checkbox" -> if ((v as? JsonPrimitive)?.booleanOrNull == true || prim(v) == "1") "☑" else "☐"
            "number" -> (v as? JsonPrimitive)?.doubleOrNull?.let { n ->
                when (BlockDoc.str(p, "fmt").ifEmpty { BlockDoc.str(p, "format") }) {   // wie numText() im Web
                    "percent" -> num(n) + " %"
                    "euro" -> java.text.NumberFormat.getCurrencyInstance().apply { currency = java.util.Currency.getInstance("EUR") }.format(n)
                    else -> num(n)
                }
            } ?: prim(v)
            "relation" -> list(v).mapNotNull { e -> prim(e)?.let { s -> s.toLongOrNull()?.let { titleOf(it) } ?: s } }.joinToString(", ")
            "date" -> when (v) {
                is JsonObject -> listOfNotNull(prim(v["start"]), prim(v["end"])).joinToString(" – ")
                else -> prim(v)
            }
            "text", "url" -> prim(v)?.let { InlineHtml.plain(it) }
            else -> when (v) {
                is JsonPrimitive -> v.contentOrNull?.let { InlineHtml.plain(it) }
                is JsonArray -> v.mapNotNull { prim(it) }.joinToString(", ")
                else -> null
            }
        }
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

    fun num(n: Double): String = if (n == Math.floor(n) && !n.isInfinite() && Math.abs(n) < 1e15) n.toLong().toString() else n.toString()
}
