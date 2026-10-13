package io.github.yggdrasil_sh.saga.blocks

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.random.Random

/**
 * Seiteninhalt im Editor.js-Format: {"time":..., "version":..., "blocks":[{"id","type","data"}, ...]}.
 * Der Inhalt bleibt als JSON-Baum erhalten; die App aendert nur gezielt einzelne Werte (JsonTree),
 * unbekannte Bloecke und Felder werden 1:1 wieder gespeichert.
 */
object BlockDoc {
    class UnreadableException(msg: String) : Exception(msg)

    data class Parsed(val doc: JsonObject, val converted: Boolean)

    /**
     * format "blocks": JSON lesen - ist es kaputt, Ausnahme (dann darf die Seite NICHT bearbeitet/gespeichert werden).
     * Alte Textnotizen (format "text"): Zeilen -> Absaetze (wie iOS).
     */
    fun parse(body: String?, format: String?): Parsed {
        if (format == "blocks") {
            if (body.isNullOrBlank()) return Parsed(JsonObject(mapOf("blocks" to JsonArray(emptyList()))), false)
            val e = try {
                Json.parseToJsonElement(body)
            } catch (ex: Exception) {
                throw UnreadableException("Seiteninhalt nicht lesbar")
            }
            if (e !is JsonObject || e["blocks"] !is JsonArray) throw UnreadableException("Seiteninhalt nicht lesbar")
            return Parsed(e, false)
        }
        val paras = (body ?: "").split("\n").filter { it.isNotBlank() }.map { newBlock("paragraph", InlineHtml.escape(it)) }
        return Parsed(JsonObject(mapOf("blocks" to JsonArray(paras))), true)
    }

    fun serialize(doc: JsonObject): String = Json.encodeToString(JsonElement.serializer(), doc)

    fun withTime(doc: JsonObject, millis: Long): JsonObject = JsonObject(LinkedHashMap(doc).apply { put("time", JsonPrimitive(millis)) })

    fun blocks(doc: JsonObject): JsonArray = doc["blocks"] as? JsonArray ?: JsonArray(emptyList())

    private const val ID_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    fun newId(random: Random = Random.Default): String = (1..10).map { ID_CHARS[random.nextInt(ID_CHARS.length)] }.joinToString("")

    // ---------------------------------------------------------------- Felder lesen

    fun type(block: JsonElement?): String = ((block as? JsonObject)?.get("type") as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""
    fun id(block: JsonElement?): String? = ((block as? JsonObject)?.get("id") as? JsonPrimitive)?.takeIf { it.isString }?.content
    fun data(block: JsonElement?): JsonObject = (block as? JsonObject)?.get("data") as? JsonObject ?: JsonObject(emptyMap())
    fun str(obj: JsonObject?, key: String): String = (obj?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""
    fun int(obj: JsonObject?, key: String): Int? = (obj?.get(key) as? JsonPrimitive)?.content?.toDoubleOrNull()?.toInt()
    fun bool(obj: JsonObject?, key: String): Boolean = (obj?.get(key) as? JsonPrimitive)?.content == "true"

    // ---------------------------------------------------------------- Neue Bloecke

    private fun obj(vararg pairs: kotlin.Pair<String, JsonElement>) = JsonObject(linkedMapOf(*pairs))
    private fun s(v: String) = JsonPrimitive(v)

    fun newListItem(content: String, checklist: Boolean): JsonObject =
        obj("content" to s(content), "meta" to (if (checklist) obj("checked" to JsonPrimitive(false)) else obj()), "items" to JsonArray(emptyList()))

    /** Wie PageEditor.make in iOS: Typen checklist/unordered/ordered werden zu "list" mit passendem style */
    fun newBlock(type: String, text: String = "", level: Int = 2, id: String = newId()): JsonObject {
        val data: JsonObject = when (type) {
            "header" -> obj("text" to s(text), "level" to JsonPrimitive(level))
            "checklist", "unordered", "ordered" -> obj("style" to s(type), "meta" to obj(), "items" to JsonArray(listOf(newListItem(text, type == "checklist"))))
            "quote" -> obj("text" to s(text), "caption" to s(""), "alignment" to s("left"))
            "callout" -> obj("emoji" to s("💡"), "text" to s(text))
            "toggle" -> obj("title" to s(text), "open" to JsonPrimitive(true), "blocks" to JsonArray(listOf(newBlock("paragraph"))))
            "code" -> obj("code" to s(text))
            "delimiter" -> obj()
            else -> obj("text" to s(text))
        }
        val t = if (type in setOf("checklist", "unordered", "ordered")) "list" else type
        return obj("id" to s(id), "type" to s(t), "data" to data)
    }

    // ---------------------------------------------------------------- Umwandeln

    /** Haupttext eines Blocks als Inline-HTML (null = nicht umwandelbar) */
    fun mainText(block: JsonElement?): String? {
        val d = data(block)
        return when (type(block)) {
            "paragraph", "header", "quote", "callout" -> str(d, "text")
            "toggle" -> str(d, "title")
            "code" -> InlineHtml.escape(str(d, "code"))
            "list" -> ((d["items"] as? JsonArray)?.firstOrNull()).let { it as? JsonObject }?.let { str(it, "content") }
                ?: ((d["items"] as? JsonArray)?.firstOrNull() as? JsonPrimitive)?.content ?: ""
            "delimiter" -> ""
            else -> null
        }
    }

    /**
     * Block in einen anderen Typ umwandeln (ID bleibt). Liste -> andere Liste: nur der Stil wechselt,
     * alle Punkte bleiben. Nicht umwandelbare Bloecke: null.
     */
    fun convert(block: JsonObject, toType: String, level: Int = 2): JsonObject? {
        val from = type(block)
        val id = id(block) ?: newId()
        val listStyles = setOf("checklist", "unordered", "ordered")
        if (from == "list" && toType in listStyles) {
            val d = data(block)
            val nd = LinkedHashMap(d)
            nd["style"] = s(toType)
            return JsonObject(LinkedHashMap(block).apply { put("data", JsonObject(nd)) })
        }
        if (from == "header" && toType == "header") {
            val nd = LinkedHashMap(data(block)).apply { put("level", JsonPrimitive(level)) }
            return JsonObject(LinkedHashMap(block).apply { put("data", JsonObject(nd)) })
        }
        val text = mainText(block) ?: return null
        val t = if (toType == "code") InlineHtml.plain(text) else text
        return newBlock(toType, t, level, id)
    }

    /** Deutsche Namen fuer Bloecke, die (noch) nicht bearbeitet werden koennen */
    fun typeLabel(type: String): String = when (type) {
        "table" -> "Tabelle"
        "attaches" -> "Datei-Anhang"
        "database" -> "Datenbank-Ansicht"
        "tasks" -> "Aufgaben-Block"
        "calendar" -> "Kalender-Block"
        "buttons" -> "Schaltflächen"
        "embed" -> "Einbettung"
        "columns" -> "Spalten"
        "" -> "Unbekannter Block"
        else -> "„$type“"
    }
}
