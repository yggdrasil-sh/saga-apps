package io.github.yggdrasil_sh.saga.blocks

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * TT 2.5 Nr. 18: Verweise auf Seiten (`<a href="/notes#ID">Titel</a>`) zeigen - wie im Web (decorateLinks) - den
 * AKTUELLEN Titel der Zielseite. Eine Unterseite wird mit "Unbenannt" angelegt und erst danach benannt; ohne das blieb
 * der Verweis in der Elternseite fuer immer "Unbenannt". Der neue Text wird mit der naechsten Aenderung gespeichert.
 */
object NoteLinks {
    private val LINK = Regex("""<a href="/notes#([1-9][0-9]{0,17})"([^>]*)>([^<]*)</a>""")

    /** Linktexte in einem HTML-Stueck; titleOf(id) = aktueller Titel (null/leer = unbekannt, dann unveraendert) */
    fun refreshHtml(html: String, titleOf: (Long) -> String?): String {
        if (!html.contains("/notes#")) return html
        return LINK.replace(html) { m ->
            val id = m.groupValues[1].toLongOrNull() ?: return@replace m.value
            val t = titleOf(id)?.trim().orEmpty()
            if (t.isEmpty()) return@replace m.value
            val esc = InlineHtml.escape(t)
            if (esc == m.groupValues[3]) m.value else "<a href=\"/notes#${m.groupValues[1]}\"${m.groupValues[2]}>$esc</a>"
        }
    }

    /** Ganzes Dokument (alle Zeichenketten, auch in Listen, Tabellen, Spalten) */
    fun refresh(doc: JsonObject, titleOf: (Long) -> String?): JsonObject = walk(doc, titleOf) as JsonObject

    private fun walk(e: JsonElement, titleOf: (Long) -> String?): JsonElement = when (e) {
        is JsonObject -> {
            var changed = false
            val m = LinkedHashMap<String, JsonElement>(e.size)
            for ((k, v) in e) {
                val n = walk(v, titleOf)
                if (n !== v) changed = true
                m[k] = n
            }
            if (changed) JsonObject(m) else e
        }
        is JsonArray -> {
            var changed = false
            val l = e.map { v -> walk(v, titleOf).also { if (it !== v) changed = true } }
            if (changed) JsonArray(l) else e
        }
        is JsonPrimitive -> if (e.isString && e.content.contains("/notes#")) {
            val n = refreshHtml(e.content, titleOf)
            if (n == e.content) e else JsonPrimitive(n)
        } else e
    }
}
