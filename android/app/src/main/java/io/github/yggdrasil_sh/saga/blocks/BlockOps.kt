package io.github.yggdrasil_sh.saga.blocks

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Saga 2.4 (Android-Editor): neue Bloecke wie im Web/iOS und Aenderungen an Tabelle und Spalten.
 * REGEL wie ueberall im Editor: nur gezielte Werte aendern, alle anderen Felder (auch unbekannte) bleiben.
 * Formate (Editor.js bzw. Saga-Werkzeuge in static/pages.js):
 *  - table:    {"withHeadings": bool, "content": [[html, ...], ...]}
 *  - columns:  {"cols": [{"blocks": [...]}, ...]}  (Web: 2-4 Spalten)
 *  - image:    {"file": {"url"}, "caption", "withBorder", "stretched", "withBackground"}
 *  - attaches: {"file": {"url", "name", "size", "extension"}, "title"}
 *  - embed:    {"url", "title"?, "kind": "youtube"?}
 *  - tasks {}, calendar {"view", "days"}, buttons {"buttons": [...]}, database {"db"}
 */
object BlockOps {
    private fun obj(vararg pairs: Pair<String, JsonElement>) = JsonObject(linkedMapOf(*pairs))
    private fun s(v: String) = JsonPrimitive(v)
    private fun block(type: String, data: JsonObject, id: String = BlockDoc.newId()) = obj("id" to s(id), "type" to s(type), "data" to data)

    fun table(rows: Int = 2, cols: Int = 2, headings: Boolean = true, id: String = BlockDoc.newId()): JsonObject =
        block("table", obj(
            "withHeadings" to JsonPrimitive(headings),
            "content" to JsonArray(List(rows.coerceAtLeast(1)) { JsonArray(List(cols.coerceAtLeast(1)) { s("") }) }),
        ), id)

    fun columns(n: Int, id: String = BlockDoc.newId()): JsonObject =
        block("columns", obj("cols" to JsonArray(List(n.coerceIn(2, 4)) { obj("blocks" to JsonArray(listOf(BlockDoc.newBlock("paragraph")))) })), id)

    fun image(url: String, id: String = BlockDoc.newId()): JsonObject = block("image", obj(
        "file" to obj("url" to s(url)), "caption" to s(""),
        "withBorder" to JsonPrimitive(false), "stretched" to JsonPrimitive(false), "withBackground" to JsonPrimitive(false),
    ), id)

    /** file = Antwort von POST api/files ("file": {url, name, size, extension, title}) */
    fun attaches(file: JsonObject, id: String = BlockDoc.newId()): JsonObject =
        block("attaches", obj("file" to file, "title" to s(BlockDoc.str(file, "name").ifEmpty { BlockDoc.str(file, "title") })), id)

    fun embed(url: String, title: String = "", id: String = BlockDoc.newId()): JsonObject {
        val m = linkedMapOf<String, JsonElement>("url" to s(url.trim()))
        if (title.isNotBlank()) m["title"] = s(title.trim())
        if (youtubeId(url) != null) m["kind"] = s("youtube")
        return block("embed", JsonObject(m), id)
    }

    fun tasks(id: String = BlockDoc.newId()): JsonObject = block("tasks", obj(), id)
    fun calendar(id: String = BlockDoc.newId()): JsonObject = block("calendar", obj("view" to s("list"), "days" to JsonPrimitive(14)), id)
    fun database(db: Long, id: String = BlockDoc.newId()): JsonObject = block("database", obj("db" to JsonPrimitive(db)), id)
    fun buttons(label: String, id: String = BlockDoc.newId()): JsonObject =
        block("buttons", obj("buttons" to JsonArray(listOf(obj("label" to s(label), "icon" to s("➕"), "action" to s(""))))), id)

    /** Absatz mit Verweis auf eine Seite (wie "Verweis"/"Seite" im Web) */
    fun pageLink(pageId: Long, title: String, icon: String = "", id: String = BlockDoc.newId()): JsonObject =
        BlockDoc.newBlock("paragraph", (if (icon.isNotEmpty()) "$icon " else "") + "<a href=\"/notes#$pageId\">" + InlineHtml.escape(title) + "</a>", id = id)

    /** gleicher Block mit anderer ID? (fuer "/": der Absatz wird ersetzt, seine ID bleibt) */
    fun withId(block: JsonObject, id: String): JsonObject = JsonObject(LinkedHashMap(block).apply { put("id", s(id)) })

    // ---------------------------------------------------------------- Einbetten

    private val YT = Regex("""(?:youtube(?:-nocookie)?\.com/(?:watch\?(?:.*&)?v=|embed/|shorts/|live/)|youtu\.be/)([A-Za-z0-9_-]{11})""")

    fun youtubeId(url: String): String? = YT.find(url)?.groupValues?.get(1)

    /** Nur http(s)-Adressen einbetten (wie im Web); ohne Schema -> https:// davor */
    fun normalizeUrl(input: String): String? {
        val t = input.trim()
        if (t.isEmpty() || t.any { it.isWhitespace() }) return null
        val u = if ("://" in t) t else "https://$t"
        val scheme = u.substringBefore("://").lowercase()
        if (scheme != "http" && scheme != "https") return null
        val host = u.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
        if (host.isEmpty() || '@' in host) return null
        if (!host.startsWith("[") && ':' in host && !host.substringAfterLast(':').let { p -> p.isNotEmpty() && p.all { it.isDigit() } }) return null
        return u
    }

    // ---------------------------------------------------------------- Tabelle

    private fun tableContent(block: JsonObject): List<JsonArray> =
        (BlockDoc.data(block)["content"] as? JsonArray)?.map { it as? JsonArray ?: JsonArray(emptyList()) } ?: emptyList()

    private fun withData(block: JsonObject, key: String, value: JsonElement): JsonObject {
        val d = LinkedHashMap(BlockDoc.data(block)).apply { put(key, value) }
        return JsonObject(LinkedHashMap(block).apply { put("data", JsonObject(d)) })
    }

    /** Zellen als Text (HTML); fehlende/fremde Zellen "" (nur zum Anzeigen - gespeichert wird nur Geaendertes) */
    fun cells(block: JsonObject): List<List<String>> = tableContent(block).map { row ->
        row.map { c -> (c as? JsonPrimitive)?.takeIf { it.isString }?.content ?: (c as? JsonPrimitive)?.content ?: "" }
    }

    fun columnCount(block: JsonObject): Int = tableContent(block).maxOfOrNull { it.size } ?: 0

    fun headings(block: JsonObject): Boolean = BlockDoc.bool(BlockDoc.data(block), "withHeadings")

    fun setHeadings(block: JsonObject, on: Boolean): JsonObject = withData(block, "withHeadings", JsonPrimitive(on))

    /** Zelle setzen; zu kurze Zeilen werden bis dorthin mit "" aufgefuellt */
    fun setCell(block: JsonObject, r: Int, c: Int, html: String): JsonObject {
        val rows = tableContent(block).toMutableList()
        if (r !in rows.indices || c < 0) return block
        val row = rows[r].toMutableList()
        while (row.size <= c) row.add(s(""))
        row[c] = s(html)
        rows[r] = JsonArray(row)
        return withData(block, "content", JsonArray(rows))
    }

    fun insertRow(block: JsonObject, at: Int): JsonObject {
        val rows = tableContent(block).toMutableList()
        val n = columnCount(block).coerceAtLeast(1)
        rows.add(at.coerceIn(0, rows.size), JsonArray(List(n) { s("") }))
        return withData(block, "content", JsonArray(rows))
    }

    /** Letzte Zeile bleibt (eine Tabelle ohne Zeilen loescht man als Block) */
    fun removeRow(block: JsonObject, r: Int): JsonObject {
        val rows = tableContent(block).toMutableList()
        if (rows.size <= 1 || r !in rows.indices) return block
        rows.removeAt(r)
        return withData(block, "content", JsonArray(rows))
    }

    /**
     * Aufbau einer Tabelle ("Zeilen x Spalten"). Die Zellfelder heissen nach ihrer Position (t<r>.<c>); aendert sich
     * der Aufbau, steht unter derselben Position eine andere Zelle - die Felder muessen dann neu aufsetzen (N-01).
     */
    fun tableShape(block: JsonObject): String = "${tableContent(block).size}x${columnCount(block)}"

    /** Position der Zelle (r, c), nachdem bei Zeile row bzw. Spalte col eingefuegt wurde (der Cursor bleibt in seiner Zelle) */
    fun cellAfterInsert(r: Int, c: Int, row: Int? = null, col: Int? = null): Pair<Int, Int> =
        (if (row != null && row <= r) r + 1 else r) to (if (col != null && col <= c) c + 1 else c)

    fun insertColumn(block: JsonObject, at: Int): JsonObject {
        val rows = tableContent(block).map { row ->
            val l = row.toMutableList()
            l.add(at.coerceIn(0, l.size), s(""))
            JsonArray(l)
        }
        return withData(block, "content", JsonArray(rows.ifEmpty { listOf(JsonArray(listOf(s("")))) }))
    }

    fun removeColumn(block: JsonObject, c: Int): JsonObject {
        if (columnCount(block) <= 1 || c < 0) return block
        val rows = tableContent(block).map { row ->
            val l = row.toMutableList()
            if (c in l.indices) l.removeAt(c)
            JsonArray(l)
        }
        return withData(block, "content", JsonArray(rows))
    }

    // ---------------------------------------------------------------- Spalten

    fun columnBlocksCount(block: JsonObject): Int = (BlockDoc.data(block)["cols"] as? JsonArray)?.size ?: 0

    /**
     * Anzahl der Spalten aendern (2-4). Mehr: leere Spalten anhaengen. Weniger: die Bloecke der wegfallenden Spalten
     * kommen ans Ende der letzten verbleibenden Spalte - nichts geht verloren (wie "Zusammenfuehren" im Web).
     */
    fun setColumnCount(block: JsonObject, n: Int): JsonObject {
        val want = n.coerceIn(2, 4)
        val cols = (BlockDoc.data(block)["cols"] as? JsonArray)?.toMutableList() ?: mutableListOf()
        if (cols.size == want) return block
        if (cols.size < want) {
            while (cols.size < want) cols.add(obj("blocks" to JsonArray(listOf(BlockDoc.newBlock("paragraph")))))
        } else {
            val moved = cols.subList(want, cols.size).flatMap { c -> ((c as? JsonObject)?.get("blocks") as? JsonArray).orEmpty() }
            val keep = cols.subList(0, want).toMutableList()
            val last = keep[want - 1] as? JsonObject ?: obj()
            val lastBlocks = (last["blocks"] as? JsonArray).orEmpty()
            keep[want - 1] = JsonObject(LinkedHashMap(last).apply { put("blocks", JsonArray(lastBlocks + moved)) })
            cols.clear()
            cols.addAll(keep)
        }
        return withData(block, "cols", JsonArray(cols))
    }

    fun isEmptyParagraph(b: JsonElement?): Boolean = BlockDoc.type(b) == "paragraph" && InlineHtml.plain(BlockDoc.str(BlockDoc.data(b), "text")).isBlank()

    // ---------------------------------------------------------------- Bild

    fun setImageFlag(block: JsonObject, key: String, on: Boolean): JsonObject = withData(block, key, JsonPrimitive(on))
}
