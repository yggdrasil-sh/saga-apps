package io.github.yggdrasil_sh.saga.graph

import io.github.yggdrasil_sh.saga.data.ApiException
import io.github.yggdrasil_sh.saga.data.SagaRepository
import io.github.yggdrasil_sh.saga.util.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import java.io.IOException

/*
 * 2.5 Graph der Seiten (wie Obsidian) - Daten. Quelle: GET /api/graph (saga/graph.py im Server):
 *   {nodes: [[id, titel, symbol, art, eigen, eltern]], edges: [[a, b, art]], tags: [[name, [ids]]], truncated}
 * art des Knotens: page | database | row; eigen 1 = eigene Seite, 0 = freigegeben; Kante: l = Verweis, c = Unterseite,
 * r = Relation. Kompakte Listen, weil es tausende sein koennen. Aeltere Server (vor 2.5) antworten mit 404.
 */

enum class NodeKind { PAGE, DATABASE, ROW, TAG }

data class GNode(val id: Long, val title: String, val icon: String, val kind: NodeKind, val own: Boolean, val parent: Long?)

/** kind: 'l' Verweis, 'c' Unterseite, 'r' Relation, 't' Tag (nur in der Anzeige, Tag-Knoten) */
data class GEdge(val a: Long, val b: Long, val kind: Char)

data class GraphData(
    val nodes: List<GNode>,
    val edges: List<GEdge>,
    val tags: List<Pair<String, List<Long>>>,
    val truncated: Boolean,
)

/** Ergebnis des Ladens - je ein Zustand der Ansicht */
sealed interface GraphLoad {
    data class Ok(val data: GraphData) : GraphLoad
    /** Server kennt /api/graph nicht (vor 2.5) */
    data object Unsupported : GraphLoad
    data object Offline : GraphLoad
    data class Failed(val text: UiText) : GraphLoad
}

object GraphJson {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Wirft bei kaputter Antwort (kein Objekt); einzelne kaputte Eintraege werden still uebergangen. */
    fun parse(text: String): GraphData {
        val root = json.parseToJsonElement(text) as? JsonObject ?: throw IllegalArgumentException("graph: no object")
        val nodes = ArrayList<GNode>()
        val seen = HashSet<Long>()
        (root["nodes"] as? JsonArray)?.forEach { el ->
            val a = el as? JsonArray ?: return@forEach
            val id = a.long(0) ?: return@forEach
            if (!seen.add(id)) return@forEach
            val kind = when (a.str(3)) {
                "database" -> NodeKind.DATABASE
                "row" -> NodeKind.ROW
                else -> NodeKind.PAGE
            }
            val ownEl = a.getOrNull(4) as? JsonPrimitive
            val own = ownEl?.booleanOrNull ?: ((ownEl?.longOrNull ?: 1L) != 0L)
            nodes.add(GNode(id, a.str(1).orEmpty(), a.str(2).orEmpty(), kind, own, a.long(5)))
        }
        val edges = ArrayList<GEdge>()
        (root["edges"] as? JsonArray)?.forEach { el ->
            val a = el as? JsonArray ?: return@forEach
            val s = a.long(0) ?: return@forEach
            val t = a.long(1) ?: return@forEach
            val k = a.str(2)?.firstOrNull() ?: 'l'
            if (s != t && s in seen && t in seen && k in "lcr") edges.add(GEdge(s, t, k))
        }
        val tags = ArrayList<Pair<String, List<Long>>>()
        (root["tags"] as? JsonArray)?.forEach { el ->
            val a = el as? JsonArray ?: return@forEach
            val name = a.str(0)?.takeIf { it.isNotBlank() } ?: return@forEach
            val ids = (a.getOrNull(1) as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.longOrNull?.takeIf { id -> id in seen } }.orEmpty()
            if (ids.isNotEmpty()) tags.add(name to ids)
        }
        val tr = (root["truncated"] as? JsonPrimitive)?.booleanOrNull ?: false
        return GraphData(nodes, edges, tags, tr)
    }

    private fun JsonArray.long(i: Int): Long? = (getOrNull(i) as? JsonPrimitive)?.takeIf { it !is JsonNull && !it.isString }?.longOrNull
    private fun JsonArray.str(i: Int): String? = (getOrNull(i) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
    @Suppress("unused") private fun JsonElement?.isNullish() = this == null || this is JsonNull
}

object GraphLoader {
    /** Laedt und deutet Fehler: 404 = Server zu alt, Netz = offline, sonst Meldung. */
    suspend fun load(get: suspend () -> String): GraphLoad = try {
        GraphLoad.Ok(GraphJson.parse(get()))
    } catch (e: CancellationException) {
        throw e
    } catch (e: ApiException) {
        if (e.code == 404) GraphLoad.Unsupported else GraphLoad.Failed(e.text)
    } catch (e: IOException) {
        GraphLoad.Offline
    } catch (e: Exception) {
        if (e.cause is IOException) GraphLoad.Offline else GraphLoad.Failed(SagaRepository.friendlyText(e))
    }
}
