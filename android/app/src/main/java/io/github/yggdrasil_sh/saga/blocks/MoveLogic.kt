package io.github.yggdrasil_sh.saga.blocks

import io.github.yggdrasil_sh.saga.data.PageMeta
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * 2.5 E2 (move1, APP-API "Bloecke auf eine andere Seite verschieben"): POST /api/notes/{a}/blocks/move - eine
 * Server-Transaktion, die App baut nur den Aufruf, das Rueckgaengig (Gegenrichtung) und die Fehlermeldungen.
 */
object MoveLogic {
    private val json = Json { ignoreUnknownKeys = true }

    data class Request(val from: Long, val ids: List<String>, val to: Long, val revA: Int, val revB: Int? = null, val after: String? = null, val at: String? = null) {
        fun body(): JsonObject = JsonObject(buildMap {
            put("ids", JsonArray(ids.map { JsonPrimitive(it) }))
            put("to", JsonPrimitive(to))
            put("rev_a", JsonPrimitive(revA))
            revB?.let { put("rev_b", JsonPrimitive(it)) }
            if (after != null) put("after", JsonPrimitive(after)) else put("at", JsonPrimitive(at ?: "end"))
        })
        val path: String get() = "api/notes/$from/blocks/move"
    }

    data class Result(val revA: Int?, val revB: Int?, val ids: List<String>, val renamed: Map<String, String>, val unsigned: Int, val filesCopied: Int)

    fun parse(text: String): Result {
        val o = try { json.parseToJsonElement(text) as? JsonObject } catch (_: Exception) { null } ?: return Result(null, null, emptyList(), emptyMap(), 0, 0)
        fun n(k: String): Int = (o[k] as? JsonPrimitive)?.intOrNull ?: ((o[k] as? JsonArray)?.size ?: 0)
        return Result(
            (o["rev_a"] as? JsonPrimitive)?.intOrNull, (o["rev_b"] as? JsonPrimitive)?.intOrNull,
            (o["ids"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
            (o["renamed"] as? JsonObject).orEmpty().mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }.toMap(),
            n("unsigned"), n("files_copied"),
        )
    }

    /** Ziele: eigene bzw. bearbeitbare Seiten dieses Servers (positive ID), keine Datenbanken, nicht die Seite selbst */
    fun targets(pages: List<PageMeta>, self: Long): List<PageMeta> = pages.filter { p ->
        p.id > 0 && p.id != self && !p.isDatabase && !p.archived && !p.template && !p.isRemote && !p.locked &&
            (p.role == null || p.role == "owner" || p.role == "edit")
    }.distinctBy { it.id }

    /** Menuepunkt nur fuer Seiten dieses Servers (fremde Server: ausblenden) */
    fun available(pageId: Long, features: Set<String>): Boolean = pageId > 0 && "move1" in features

    /**
     * Rueckgaengig = Gegenrichtung: dieselben (ggf. umbenannten) IDs zurueck auf die alte Seite, hinter den alten
     * Vorgaenger (oberste Ebene) bzw. an den Anfang.
     */
    fun undo(req: Request, res: Result, prevSibling: String?): Request {
        val ids = req.ids.map { res.renamed[it] ?: it }.let { if (res.ids.isNotEmpty()) res.ids else it }
        return Request(req.to, ids, req.from, res.revB ?: 0, res.revA, after = prevSibling, at = if (prevSibling == null) "start" else null)
    }

    /** Vorgaenger auf oberster Ebene des ersten gewaehlten Blocks (fuer Rueckgaengig); null = er stand ganz oben */
    fun prevSibling(doc: JsonObject, ids: List<String>): String? {
        val top = BlockDoc.blocks(doc)
        val first = top.indexOfFirst { BlockDoc.id(it) in ids }
        if (first <= 0) return null
        return BlockDoc.id(top[first - 1])
    }

    enum class Error { CONFLICT, FORBIDDEN, LOCKED, QUOTA, FEDERATED, NOT_FOUND, OTHER }

    fun error(code: Int, body: String): Error {
        val o = try { json.parseToJsonElement(body) as? JsonObject } catch (_: Exception) { null }
        return when {
            code == 409 -> Error.CONFLICT
            code == 403 -> Error.FORBIDDEN
            code == 423 -> Error.LOCKED
            code == 507 -> Error.QUOTA
            code == 400 && (o?.get("federated") as? JsonPrimitive)?.contentOrNull == "true" -> Error.FEDERATED
            code == 404 -> Error.NOT_FOUND
            else -> Error.OTHER
        }
    }

    /** Ausgewaehlte Bloecke (Pfade) -> IDs in Dokument-Reihenfolge, ohne Doppelte */
    fun idsOf(doc: JsonObject, paths: List<JsonPath>): List<String> =
        paths.mapNotNull { p -> (JsonTree.get(doc, p) as? JsonObject)?.let { BlockDoc.id(it) } }.distinct()

    @Suppress("unused")
    private fun JsonElement.str(): String? = (this as? JsonPrimitive)?.contentOrNull
}
