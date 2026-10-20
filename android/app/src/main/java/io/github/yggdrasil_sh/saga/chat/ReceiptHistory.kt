package io.github.yggdrasil_sh.saga.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Lesezeiten je Person (TT 2.4 P-06): Der Server liefert je Person nur die NEUESTE Bestaetigung. Wer nur die zeigt,
 * laesst die "Gelesen"-Zeit aelterer Nachrichten bei jeder neuen Bestaetigung (und bei jedem Oeffnen) weiterspringen.
 * Deshalb merkt sich die App die Bestaetigungen je Person (n -> ts aus der signierten Bestaetigung, nie die lokale Uhr)
 * und zeigt fuer eine Nachricht die Zeit der ERSTEN Bestaetigung, die sie abdeckt.
 */
object ReceiptHistory {
    const val MAX = 64

    /** Bestaetigung einreihen: sortiert nach n, je n die frueheste Zeit, hoechstens MAX (die neuesten) */
    fun merge(hist: List<ReadMark>, mark: ReadMark): List<ReadMark> {
        val old = hist.firstOrNull { it.n == mark.n }
        if (old != null && (mark.ts <= 0 || (old.ts in 1..mark.ts))) return hist
        val out = (hist.filter { it.n != mark.n } + mark).sortedBy { it.n }
        return if (out.size > MAX) out.takeLast(MAX) else out
    }

    /** Lesezeit der Nachricht an Position n: erste Bestaetigung mit n' >= n (null = nicht gelesen/unbekannt) */
    fun readAt(hist: List<ReadMark>, n: Long): Long? =
        if (n <= 0) null else hist.firstOrNull { it.n >= n }?.ts?.takeIf { it > 0 }

    fun encode(all: Map<String, List<ReadMark>>): String = buildJsonObject {
        all.forEach { (who, list) ->
            put(who, buildJsonArray { list.forEach { m -> add(buildJsonArray { add(JsonPrimitive(m.n)); add(JsonPrimitive(m.id)); add(JsonPrimitive(m.ts)) }) } })
        }
    }.toString()

    fun decode(s: String?): Map<String, List<ReadMark>> = try {
        val o = s?.let { Json.parseToJsonElement(it) as? JsonObject } ?: emptyMap()
        o.mapValues { (_, v) ->
            (v as? JsonArray).orEmpty().mapNotNull { e ->
                val a = e as? JsonArray ?: return@mapNotNull null
                val n = (a.getOrNull(0) as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
                val id = (a.getOrNull(1) as? JsonPrimitive)?.content ?: return@mapNotNull null
                val ts = (a.getOrNull(2) as? JsonPrimitive)?.longOrNull ?: 0L
                ReadMark(n, id, ts)
            }.sortedBy { it.n }
        }
    } catch (_: Exception) {
        emptyMap()
    }
}
