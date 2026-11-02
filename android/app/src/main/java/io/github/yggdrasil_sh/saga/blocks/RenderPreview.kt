package io.github.yggdrasil_sh.saga.blocks

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/** 2.5 P6-1/P6-2: Vorschaubild `data.render` (Mathe, Mermaid) und synchronisierte Bloecke - reine Logik */
object RenderPreview {
    const val TEX_MAX = 4000

    data class Render(val png: String, val w: Int?, val hgt: Int?, val stale: Boolean) {
        /** Seitenverhaeltnis fuer den Platzhalter */
        val ratio: Float? get() = if (w != null && hgt != null && w > 0 && hgt > 0) w.toFloat() / hgt else null
    }

    /** Bild nur aus eigener Datei-Adresse (/files/ bzw. /api/fed/files/) - nie fremde Adressen */
    fun render(d: JsonObject): Render? {
        val r = d["render"] as? JsonObject ?: return null
        val png = (r["png"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        if (!(png.startsWith("/files/") || png.startsWith("/api/fed/files/")) || png.contains("..")) return null
        fun n(k: String) = (r[k] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.toInt()?.takeIf { it > 0 }
        return Render(png, n("w"), n("hgt"), (r["stale"] as? JsonPrimitive)?.booleanOrNull == true)
    }

    fun isMermaid(d: JsonObject): Boolean = BlockDoc.str(d, "language").equals("mermaid", ignoreCase = true)

    /**
     * Labor 2.11. (Verdacht #4): Mathe-Block zeigte beim ersten Oeffnen nur den TeX-Quelltext. Das Vorschaubild erzeugt
     * nur das Web (3 s nach der letzten Eingabe bzw. beim naechsten Oeffnen) - eine eben im Web angelegte Seite hat es oft
     * noch nicht. true = die Seite hat Mathe/Mermaid mit Quelltext, aber ohne Bild (auch in Spalten/Toggles): einmal kurz
     * danach still neu laden.
     */
    fun missingRender(doc: JsonObject): Boolean {
        fun walk(arr: JsonArray, depth: Int): Boolean = depth <= 8 && arr.any { el ->
            val b = el as? JsonObject ?: return@any false
            val d = BlockDoc.data(b)
            when (BlockDoc.type(b)) {
                "math" -> BlockDoc.str(d, "tex").isNotBlank() && render(d) == null
                "code" -> isMermaid(d) && BlockDoc.str(d, "code").isNotBlank() && render(d) == null
                "toggle" -> (d["blocks"] as? JsonArray)?.let { walk(it, depth + 1) } == true
                "columns" -> (d["cols"] as? JsonArray).orEmpty().any { c -> ((c as? JsonObject)?.get("blocks") as? JsonArray)?.let { walk(it, depth + 1) } == true }
                else -> false
            }
        }
        return walk(BlockDoc.blocks(doc), 0)
    }

    /** so lange nach dem Laden warten, bevor einmal still neu geladen wird */
    const val RECHECK_MS = 6000L

    data class SyncedSrc(val note: Long?, val role: String?)

    /** data.src setzt nur der Server beim Lesen (Kopie): {note, role} */
    fun syncedSource(d: JsonObject): SyncedSrc? {
        val s = d["src"] as? JsonObject ?: return null
        return SyncedSrc((s["note"] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toLong(), (s["role"] as? JsonPrimitive)?.content)
    }

    fun syncedHasBlocks(d: JsonObject): Boolean = (d["blocks"] as? JsonArray) != null

    /**
     * Speichern: `data.src` nie zurueckschreiben; bei einer Kopie (src gesetzt) auch nicht den eingesetzten Inhalt -
     * gespeichert wird nur {sid} (+ unbekannte Schluessel). Das Original (src.original, Inhalt lebt hier) behaelt seinen
     * Inhalt (E2: vorher fiel er weg - Datenverlust). sync1 (2.5 E2): eine in der App bearbeitete Kopie (sid in edited)
     * geht mit bearbeitetem data.blocks und unveraendertem data.src zurueck; der Server schreibt sie ins Original.
     */
    fun syncedForSave(block: JsonObject, edited: Set<String> = emptySet()): JsonObject {
        val d = BlockDoc.data(block)
        val src = d["src"] as? JsonObject ?: return block
        val original = (src["original"] as? JsonPrimitive)?.booleanOrNull == true
        val sid = BlockDoc.str(d, "sid")
        if (!original && sid.isNotEmpty() && sid in edited && d["blocks"] is JsonArray) return block
        val nd = LinkedHashMap(d).apply { remove("src"); if (!original) remove("blocks") }
        return JsonObject(LinkedHashMap(block).apply { put("data", JsonObject(nd)) })
    }

    /** Ganze Seite zum Speichern: synchronisierte Kopien (auch in Spalten/Toggles) ohne eingesetzten Inhalt/src */
    fun docForSave(doc: JsonObject, edited: Set<String> = emptySet()): JsonObject {
        var changed = false
        fun walk(arr: JsonArray, depth: Int): JsonArray = JsonArray(arr.map { el ->
            val b = el as? JsonObject ?: return@map el
            if (depth > 8) return@map b
            val t = BlockDoc.type(b)
            var nb = if (t == "synced") syncedForSave(b, edited).also { if (it !== b) changed = true } else b
            val d = BlockDoc.data(nb)
            when (t) {
                "toggle" -> (d["blocks"] as? JsonArray)?.let { inner -> nb = JsonObject(LinkedHashMap(nb).apply { put("data", JsonObject(LinkedHashMap(d).apply { put("blocks", walk(inner, depth + 1)) })) }) }
                "columns" -> (d["cols"] as? JsonArray)?.let { cols ->
                    val nc = JsonArray(cols.map { c ->
                        val co = c as? JsonObject ?: return@map c
                        val ib = co["blocks"] as? JsonArray ?: return@map co
                        JsonObject(LinkedHashMap(co).apply { put("blocks", walk(ib, depth + 1)) })
                    })
                    nb = JsonObject(LinkedHashMap(nb).apply { put("data", JsonObject(LinkedHashMap(d).apply { put("cols", nc) })) })
                }
            }
            nb
        })
        val out = JsonObject(LinkedHashMap(doc).apply { put("blocks", walk(BlockDoc.blocks(doc), 0)) })
        return if (changed) out else doc
    }
}
