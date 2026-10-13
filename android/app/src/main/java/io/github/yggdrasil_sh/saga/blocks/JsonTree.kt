package io.github.yggdrasil_sh.saga.blocks

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/**
 * Pfad in einem JSON-Baum: Schluessel (String) oder Index (Int), z. B. ["blocks", 3, "data", "items", 0].
 * Alle Aenderungen erzeugen Kopien NUR entlang des Pfads - alles andere bleibt dasselbe Objekt
 * (unbekannte Felder und Bloecke gehen so nie verloren).
 */
typealias JsonPath = List<Any>

object JsonTree {
    fun get(root: JsonElement, path: JsonPath): JsonElement? {
        var cur: JsonElement = root
        for (step in path) {
            cur = when {
                step is String && cur is JsonObject -> cur[step] ?: return null
                step is Int && cur is JsonArray -> cur.getOrNull(step) ?: return null
                else -> return null
            }
        }
        return cur
    }

    /** Wert setzen; fehlende Objekte auf dem Weg werden angelegt */
    fun set(root: JsonElement, path: JsonPath, value: JsonElement): JsonElement {
        if (path.isEmpty()) return value
        val step = path[0]
        val rest = path.subList(1, path.size)
        return when {
            step is String && (root is JsonObject || root is JsonNull) -> {
                val obj = root as? JsonObject ?: JsonObject(emptyMap())
                val child = obj[step] ?: JsonObject(emptyMap())
                val m = LinkedHashMap(obj)
                m[step] = set(child, rest, value)
                JsonObject(m)
            }
            step is Int && root is JsonArray && step in root.indices -> {
                val l = root.toMutableList()
                l[step] = set(root[step], rest, value)
                JsonArray(l)
            }
            else -> throw IllegalArgumentException("Pfad passt nicht: $path")
        }
    }

    /** Element im Array (unter arrayPath) aendern */
    fun updateArray(root: JsonElement, arrayPath: JsonPath, change: (MutableList<JsonElement>) -> Unit): JsonElement {
        val arr = get(root, arrayPath) as? JsonArray ?: JsonArray(emptyList())
        val l = arr.toMutableList()
        change(l)
        return set(root, arrayPath, JsonArray(l))
    }

    fun insert(root: JsonElement, arrayPath: JsonPath, index: Int, value: JsonElement): JsonElement =
        updateArray(root, arrayPath) { it.add(index.coerceIn(0, it.size), value) }

    fun remove(root: JsonElement, arrayPath: JsonPath, index: Int): JsonElement =
        updateArray(root, arrayPath) { if (index in it.indices) it.removeAt(index) }

    /** Element um delta Plaetze verschieben (innerhalb desselben Arrays) */
    fun move(root: JsonElement, arrayPath: JsonPath, index: Int, delta: Int): JsonElement =
        updateArray(root, arrayPath) {
            val to = index + delta
            if (index in it.indices && to in it.indices) {
                val e = it.removeAt(index)
                it.add(to, e)
            }
        }
}
