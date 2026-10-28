package io.github.yggdrasil_sh.saga.blocks

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * 2.5 P3 Stufe 1 ("Gleichzeitig bearbeiten", APP-API): blockweises Zusammenfuehren macht nur der Server - die App fuehrt
 * Buch (eingereichter Stand, Basis "sent", Delta-Uebernahme ?since, Konfliktbloecke). Reine Logik fuer Tests.
 */
object MergeLogic {
    private val json = Json { ignoreUnknownKeys = true }

    /** Hinweise aus "merged" der PATCH-Antwort */
    data class Merged(
        val from: Int?,
        val conflicts: List<String>,
        val restored: List<String>,
        val lost: List<String>,
        val titleLost: Boolean,
    )

    /** Antwort von PATCH /api/notes/{id} (alles optional; aeltere Server: nur ok/rev/updated) */
    data class SaveOutcome(
        val rev: Int?,
        /** null = ganz normal gespeichert */
        val merged: Merged? = null,
        /** neuer Inhalt (nur bei merged) */
        val body: String? = null,
        val title: String? = null,
        /** sync1: Durchschreiben je sid (ok|merged|denied|locked|gone|stale) */
        val synced: Map<String, String> = emptyMap(),
        /** sync1: neuer Stand der Originale je sid (-> src.rev) */
        val syncedRev: Map<String, Int> = emptyMap(),
    ) {
        /** sync1: nichts uebernommen (stale/denied/locked) -> Seite neu laden + Hinweis */
        val syncReload: Boolean get() = synced.values.any { it == "stale" || it == "denied" || it == "locked" }
    }

    private fun strList(e: JsonElement?): List<String> = (e as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

    fun parseSave(text: String): SaveOutcome {
        val o = try { json.parseToJsonElement(text) as? JsonObject } catch (_: Exception) { null } ?: return SaveOutcome(null)
        val rev = (o["rev"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toInt()
        val m = o["merged"] as? JsonObject
        val merged = m?.let {
            Merged(
                from = (it["from"] as? JsonPrimitive)?.intOrNull,
                conflicts = strList(it["conflicts"]), restored = strList(it["restored"]), lost = strList(it["lost"]),
                titleLost = (it["title_lost"] as? JsonPrimitive)?.booleanOrNull == true,
            )
        }
        val body = (o["body"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val title = (o["title"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val synced = (o["synced"] as? JsonObject).orEmpty().mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }.toMap()
        val srev = (o["synced_rev"] as? JsonObject).orEmpty().mapNotNull { (k, v) -> (v as? JsonPrimitive)?.intOrNull?.let { k to it } }.toMap()
        return SaveOutcome(rev, merged, if (merged != null) body else null, if (merged != null) title else null, synced, srev)
    }

    /** Felder des Speicherns: merge nur, wenn die Seite es kann (eigene Seite bzw. remote.caps merge1) und eine rev da ist */
    fun saveFields(rev: Int?, merge: Boolean, baseSent: Boolean): Map<String, JsonElement> {
        if (rev == null || !merge) return emptyMap()
        val m = linkedMapOf<String, JsonElement>("merge" to JsonPrimitive(1))
        if (baseSent) m["base"] = JsonPrimitive("sent")
        return m
    }

    /** Darf die Seite mit merge gespeichert werden? Eigene Seiten immer (der Server antwortet notfalls mit 409), fremde nur mit merge1 */
    fun canMerge(remote: Boolean, remoteCaps: Collection<String>): Boolean = !remote || "merge1" in remoteCaps

    /**
     * Rebase-Buchfuehrung nach einer Antwort mit "merged": Editor seit dem Senden unveraendert -> Inhalt uebernehmen;
     * weitergeschrieben -> lokal behalten, rev uebernehmen, naechstes Speichern mit base "sent".
     */
    enum class Rebase { ADOPT, KEEP_LOCAL_BASE_SENT, PLAIN }

    fun rebase(outcome: SaveOutcome, versionAtSend: Int, versionNow: Int): Rebase = when {
        outcome.merged == null -> Rebase.PLAIN
        versionAtSend == versionNow && outcome.body != null -> Rebase.ADOPT
        else -> Rebase.KEEP_LOCAL_BASE_SENT
    }

    // ---------------------------------------------------------------- Delta (?since)

    /** GET ?since=<rev>: delta {from, blocks} -> ganzes Dokument (unveraenderte Bloecke {"id"} aus base); null = ganz laden */
    fun applyDelta(base: JsonObject, delta: JsonObject?): JsonObject? {
        val arr = delta?.get("blocks") as? JsonArray ?: return null
        val have = HashMap<String, JsonObject>()
        for (b in BlockDoc.blocks(base)) {
            val o = b as? JsonObject ?: continue
            BlockDoc.id(o)?.let { have[it] = o }
        }
        val out = ArrayList<JsonElement>(arr.size)
        for (el in arr) {
            val o = el as? JsonObject ?: return null
            if (o.keys == setOf("id")) {
                val id = BlockDoc.id(o) ?: return null
                out.add(have[id] ?: return null)                       // Basis passt nicht: ganz laden
            } else {
                out.add(o)
            }
        }
        return JsonObject(LinkedHashMap(base).apply { put("blocks", JsonArray(out)) })
    }

    /** Delta aus der Antwort von GET ?since (null = ganzer body) */
    fun delta(page: JsonObject): JsonObject? = page["delta"] as? JsonObject

    // ---------------------------------------------------------------- Blockweise uebernehmen

    /**
     * Server-Stand blockweise uebernehmen: unveraenderte Bloecke bleiben dasselbe Objekt (Felder behalten Fokus und
     * Schreibmarke), geaenderte werden ersetzt. Ergebnis gleicht inhaltlich server.
     */
    fun adopt(local: JsonObject, server: JsonObject): JsonObject {
        val mine = HashMap<String, JsonElement>()
        fun index(arr: JsonArray) { for (b in arr) BlockDoc.id(b)?.let { mine[it] = b } }
        index(BlockDoc.blocks(local))
        val out = BlockDoc.blocks(server).map { b -> BlockDoc.id(b)?.let { id -> mine[id]?.takeIf { it == b } } ?: b }
        return JsonObject(LinkedHashMap(server).apply { put("blocks", JsonArray(out)) })
    }

    // ---------------------------------------------------------------- Konfliktbloecke (tunes.hkconflict)

    data class ConflictInfo(val by: String?, val name: String, val at: Double?, val of: String?)

    fun conflict(block: JsonElement?): ConflictInfo? {
        val t = ((block as? JsonObject)?.get("tunes") as? JsonObject)?.get("hkconflict") as? JsonObject ?: return null
        fun s(k: String) = (t[k] as? JsonPrimitive)?.contentOrNull
        return ConflictInfo(s("by"), s("name").orEmpty(), s("at")?.toDoubleOrNull(), s("of"))
    }

    /** hkconflict aus tunes entfernen (andere tunes bleiben; leeres tunes faellt weg) */
    fun withoutConflict(block: JsonObject): JsonObject {
        val tunes = block["tunes"] as? JsonObject ?: return block
        if ("hkconflict" !in tunes) return block
        val nt = LinkedHashMap(tunes).apply { remove("hkconflict") }
        return JsonObject(LinkedHashMap(block).apply { if (nt.isEmpty()) remove("tunes") else put("tunes", JsonObject(nt)) })
    }

    /** Uebernehmen: Original (of) loeschen, hkconflict am Konfliktblock entfernen. Pfade ueber die Block-ID (auch verschachtelt) */
    fun acceptConflict(doc: JsonObject, conflictId: String): JsonObject {
        val cp = find(doc, conflictId) ?: return doc
        val info = conflict(JsonTree.get(doc, cp)) ?: return doc
        var d = JsonTree.set(doc, cp, withoutConflict(JsonTree.get(doc, cp) as JsonObject)) as JsonObject
        val of = info.of
        if (of != null && of != conflictId) {
            val op = find(d, of)
            if (op != null) d = JsonTree.remove(d, op.dropLast(1), op.last() as Int) as JsonObject
        }
        return d
    }

    /** Verwerfen: Konfliktblock loeschen */
    fun discardConflict(doc: JsonObject, conflictId: String): JsonObject {
        val cp = find(doc, conflictId) ?: return doc
        return JsonTree.remove(doc, cp.dropLast(1), cp.last() as Int) as JsonObject
    }

    /** Pfad eines Blocks (oberste Ebene, Spalten, Toggles, synchronisierter Inhalt) */
    fun find(doc: JsonObject, id: String): JsonPath? {
        fun walk(arr: JsonArray, path: JsonPath, depth: Int): JsonPath? {
            if (depth > 8) return null
            arr.forEachIndexed { i, b ->
                val o = b as? JsonObject ?: return@forEachIndexed
                val p = path + i
                if (BlockDoc.id(o) == id) return p
                val d = BlockDoc.data(o)
                (d["blocks"] as? JsonArray)?.let { inner -> walk(inner, p + listOf("data", "blocks"), depth + 1)?.let { return it } }
                (d["cols"] as? JsonArray)?.forEachIndexed { ci, c ->
                    ((c as? JsonObject)?.get("blocks") as? JsonArray)?.let { inner ->
                        walk(inner, p + listOf("data", "cols", ci, "blocks"), depth + 1)?.let { return it }
                    }
                }
            }
            return null
        }
        return walk(BlockDoc.blocks(doc), listOf("blocks"), 0)
    }
}
