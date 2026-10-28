package io.github.yggdrasil_sh.saga.blocks

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * 2.5 E2 (sync1, APP-API "Synchronisierte Bloecke"): Inhalt synchronisierter KOPIEN in der App bearbeiten. Bearbeitbar,
 * wenn src.role edit/owner und nicht gone/noaccess/missing. Zurueck geht nur eine wirklich geaenderte Kopie (mit
 * unveraendertem src, mindestens src.rev); alle anderen wie bisher nur als {sid}.
 */
object SyncEdit {
    /** Kopie mit Inhalt, die diese Person bearbeiten darf */
    fun editable(data: JsonObject): Boolean {
        val src = data["src"] as? JsonObject ?: return false
        if ((src["original"] as? JsonPrimitive)?.booleanOrNull == true) return false
        if (listOf("gone", "noaccess", "missing").any { (src[it] as? JsonPrimitive)?.booleanOrNull == true }) return false
        val role = (src["role"] as? JsonPrimitive)?.contentOrNull
        return (role == "edit" || role == "owner") && data["blocks"] is JsonArray
    }

    /** Original dieser Seite (Inhalt lebt hier) - bearbeitbar wie ein Toggle */
    fun isOriginal(data: JsonObject): Boolean =
        ((data["src"] as? JsonObject)?.get("original") as? JsonPrimitive)?.booleanOrNull == true && data["blocks"] is JsonArray

    private fun each(doc: JsonObject, f: (JsonObject) -> Unit) {
        fun walk(arr: JsonArray, depth: Int) {
            if (depth > 8) return
            for (el in arr) {
                val b = el as? JsonObject ?: continue
                val d = BlockDoc.data(b)
                if (BlockDoc.type(b) == "synced") f(b)
                (d["blocks"] as? JsonArray)?.takeIf { BlockDoc.type(b) == "toggle" }?.let { walk(it, depth + 1) }
                (d["cols"] as? JsonArray)?.forEach { c -> ((c as? JsonObject)?.get("blocks") as? JsonArray)?.let { walk(it, depth + 1) } }
            }
        }
        walk(BlockDoc.blocks(doc), 0)
    }

    /** sid -> Inhalt aller bearbeitbaren Kopien (Stand beim Laden) */
    fun contents(doc: JsonObject): Map<String, JsonArray> {
        val m = LinkedHashMap<String, JsonArray>()
        each(doc) { b ->
            val d = BlockDoc.data(b)
            val sid = BlockDoc.str(d, "sid")
            if (sid.isNotEmpty() && editable(d)) m[sid] = d["blocks"] as JsonArray
        }
        return m
    }

    /** Kopien, deren Inhalt sich gegenueber dem geladenen Stand geaendert hat */
    fun edited(doc: JsonObject, loaded: Map<String, JsonArray>): Set<String> {
        val out = HashSet<String>()
        each(doc) { b ->
            val d = BlockDoc.data(b)
            val sid = BlockDoc.str(d, "sid")
            val old = loaded[sid]
            if (old != null && editable(d) && d["blocks"] != old) out.add(sid)
        }
        return out
    }

    /** Antwort synced_rev -> src.rev der Kopien (sonst kaeme das naechste Durchschreiben mit veralteter Basis) */
    fun withSrcRev(doc: JsonObject, revs: Map<String, Int>): JsonObject {
        if (revs.isEmpty()) return doc
        fun fix(arr: JsonArray, depth: Int): JsonArray = JsonArray(arr.map { el ->
            val b = el as? JsonObject ?: return@map el
            if (depth > 8) return@map b
            var d = BlockDoc.data(b)
            if (BlockDoc.type(b) == "synced") {
                val r = revs[BlockDoc.str(d, "sid")]
                val src = d["src"] as? JsonObject
                if (r != null && src != null) d = JsonObject(LinkedHashMap(d).apply { put("src", JsonObject(LinkedHashMap(src).apply { put("rev", JsonPrimitive(r)) })) })
            } else if (BlockDoc.type(b) == "toggle") {
                (d["blocks"] as? JsonArray)?.let { d = JsonObject(LinkedHashMap(d).apply { put("blocks", fix(it, depth + 1)) }) }
            } else if (BlockDoc.type(b) == "columns") {
                (d["cols"] as? JsonArray)?.let { cols ->
                    d = JsonObject(LinkedHashMap(d).apply {
                        put("cols", JsonArray(cols.map { c ->
                            val co = c as? JsonObject ?: return@map c
                            val ib = co["blocks"] as? JsonArray ?: return@map co
                            JsonObject(LinkedHashMap(co).apply { put("blocks", fix(ib, depth + 1)) })
                        }))
                    })
                }
            }
            if (d === BlockDoc.data(b)) b else JsonObject(LinkedHashMap(b).apply { put("data", d as JsonElement) })
        })
        return JsonObject(LinkedHashMap(doc).apply { put("blocks", fix(BlockDoc.blocks(doc), 0)) })
    }
}
