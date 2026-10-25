package io.github.yggdrasil_sh.saga.chat

import io.github.yggdrasil_sh.saga.blocks.InlineHtml
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Chat-Mehrfachauswahl (25.10.): Auswahl-Regeln, Kopiertext, Weiterleiten, "In Seite übernehmen", Löschen (für mich /
 * für alle) und der Hinweis, warum es (noch) keine Reaktionen gibt (x3_why). Rein und testbar - die Ansicht liefert
 * Namen, Zeiten und übersetzte Platzhalter.
 */
object ChatSelect {
    /** Weiterleiten: höchstens so viele Nachrichten auf einmal */
    const val FORWARD_MAX = 50
    /** Server-Grenzen je Aufruf (messages/delete bzw. hide) */
    const val DELETE_MAX = 100
    const val HIDE_MAX = 500

    /** Systemzeilen, Anruf-Einträge und nicht entschlüsselte Nachrichten sind nicht auswählbar */
    fun selectable(m: ChatMessage): Boolean = m.sys == null && m.call == null && m.content != null

    /** Antippen im Auswahlmodus: an/ab; nicht auswählbare bleiben unverändert */
    fun toggle(sel: Set<String>, m: ChatMessage): Set<String> = when {
        !selectable(m) -> sel
        m.id in sel -> sel - m.id
        else -> sel + m.id
    }

    /** Ausgewählte in Verlaufsreihenfolge (alt -> neu); verschwundene Kennungen fallen heraus */
    fun ordered(all: List<ChatMessage>, sel: Set<String>): List<ChatMessage> =
        all.filter { it.id in sel && selectable(it) }.sortedWith(compareBy({ it.seq }, { it.ts }))

    /** Auswahl bereinigen, wenn Nachrichten gelöscht/ausgeblendet wurden */
    fun prune(sel: Set<String>, all: List<ChatMessage>): Set<String> {
        val ids = all.mapTo(HashSet()) { it.id }
        return sel.filterTo(LinkedHashSet()) { it in ids }
    }

    /** "Für alle löschen" nur, wenn ALLE ausgewählten eigene sind (und der Chat Löschen zulässt) */
    fun canDeleteForAll(sel: List<ChatMessage>, allowed: Boolean): Boolean = allowed && sel.isNotEmpty() && sel.all { it.mine }

    /** Platzhalter für Anhänge (übersetzt von der Ansicht) */
    data class Labels(val image: String, val voice: String, val file: (String) -> String, val video: String = image)

    /** Text einer Nachricht ohne Seitenverweis-Kennungen: [[note:ID]] -> "📄 Titel" */
    fun plainText(text: String, noteTitle: (Long) -> String?): String =
        ChatFormat.NOTE_REF.replace(text) { mr -> "📄 " + (mr.groupValues[1].toLongOrNull()?.let(noteTitle) ?: "…") }

    /** Anhänge als Platzhalter ("[Bild]", "[Datei: name]", "[Sprachnachricht]") */
    fun attachmentMarks(c: ChatContent, l: Labels): List<String> = c.files.map { f ->
        when {
            f.isVoice -> "[${l.voice}]"
            f.isImage -> "[${l.image}]"
            f.isVideo -> "[${l.video}]"
            else -> "[${l.file(f.n)}]"
        }
    }

    /** Inhalt einer Nachricht als Klartext: Anhänge, dann Text */
    fun body(m: ChatMessage, l: Labels, noteTitle: (Long) -> String?): String {
        val c = m.content ?: return ""
        val parts = attachmentMarks(c, l) + listOfNotNull(plainText(c.text, noteTitle).takeIf { it.isNotBlank() })
        return parts.joinToString(" ")
    }

    /**
     * Kopiertext: eine Nachricht -> nur ihr Text; mehrere -> je Zeile "Name, Zeit: Text" in Verlaufsreihenfolge.
     * Mehrzeilige Texte bleiben mehrzeilig (Folgezeilen gehören zur selben Nachricht).
     */
    fun copyText(sel: List<ChatMessage>, l: Labels, nameOf: (ChatMessage) -> String, timeOf: (ChatMessage) -> String, noteTitle: (Long) -> String? = { null }): String {
        if (sel.size == 1) return body(sel[0], l, noteTitle)
        return sel.joinToString("\n") { m -> "${nameOf(m)}, ${timeOf(m)}: ${body(m, l, noteTitle)}" }
    }

    // ---------------------------------------------------------------- Weiterleiten

    /** Was von einer Nachricht weitergeleitet wird: Text (neu verschlüsselt) und neu hochzuladende Anhänge */
    data class ForwardPlan(val text: String, val files: List<ChatFile>, val dropped: Int)

    /**
     * Anhänge, die sauber neu hochgeladen werden können: v1-Dateien bis zur Größengrenze. saga-file/v2 (große
     * Videos), Sprachnachrichten und neuere Formate nicht - die zählen als "nicht weitergeleitet".
     */
    fun forwardable(f: ChatFile, fileMax: Long): Boolean = !f.isVoice && !f.unsupported && !f.isV2 && f.s in 1..fileMax

    fun forwardPlan(m: ChatMessage, fileMax: Long, droppedMark: String): ForwardPlan? {
        val c = m.content ?: return null
        val ok = c.files.filter { forwardable(it, fileMax) }
        val dropped = c.files.size - ok.size
        val text = listOfNotNull(c.text.takeIf { it.isNotBlank() }, droppedMark.takeIf { dropped > 0 }).joinToString("\n")
        if (text.isEmpty() && ok.isEmpty()) return null
        return ForwardPlan(text, ok, dropped)
    }

    /** Zielchats für das Weiterleiten: beschreibbar, keine offenen Anfragen, nicht der Ausgangs-Chat; Suche im Namen */
    fun forwardTargets(chats: List<ChatSummary>, from: Long, q: String, nameOf: (ChatSummary) -> String): List<ChatSummary> {
        val needle = q.trim().lowercase()
        return chats.filter { c ->
            c.id != from && !c.request && !c.readonly && c.archived != true &&
                (needle.isEmpty() || nameOf(c).lowercase().contains(needle))
        }
    }

    // ---------------------------------------------------------------- In Seite übernehmen

    /** Titel einer neuen Seite: "Chat <Name> – <Datum>" */
    fun pageTitle(chatName: String, date: String): String = "Chat $chatName – $date"

    /** Je Nachricht ein Absatz: "<b>Name</b> · Zeit" + Zeilenumbruch + Text (HTML, wie im Seiten-Editor) */
    fun pageParagraphs(sel: List<ChatMessage>, l: Labels, nameOf: (ChatMessage) -> String, timeOf: (ChatMessage) -> String, noteTitle: (Long) -> String? = { null }): List<String> =
        sel.map { m ->
            val head = "<b>" + InlineHtml.escape(nameOf(m)) + "</b> · " + InlineHtml.escape(timeOf(m))
            val b = body(m, l, noteTitle)
            if (b.isEmpty()) head else head + "<br>" + InlineHtml.escape(b).replace("\n", "<br>")
        }

    // ---------------------------------------------------------------- Löschen / Ausblenden (Server-Antworten)

    data class DeleteResult(val deleted: List<String>, val failed: Map<String, String>)

    /** POST messages/delete -> {"deleted": [...], "failed": {mid: grund}} (fehlende Felder = leer) */
    fun parseDelete(text: String): DeleteResult {
        val o = try {
            Json.parseToJsonElement(text).jsonObject
        } catch (_: Exception) {
            return DeleteResult(emptyList(), emptyMap())
        }
        val del = (o["deleted"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.orEmpty()
        val failed = (o["failed"] as? JsonObject)?.mapValues { (_, v) -> (v as? JsonPrimitive)?.content.orEmpty() }.orEmpty()
        return DeleteResult(del, failed)
    }

    /** POST hide -> {"hidden": n} */
    fun parseHidden(text: String): Int? = try {
        Json.parseToJsonElement(text).jsonObject["hidden"]?.jsonPrimitive?.longOrNull?.toInt()
    } catch (_: Exception) {
        null
    }

    /** Älterer Server ohne den Endpunkt: 404/405 -> Rückfall (Einzel-DELETE bzw. nur lokal ausblenden) */
    fun isMissingEndpoint(code: Int): Boolean = code == 404 || code == 405

    /** Live-Ereignis chat.hidden {chat_id, ids} -> ids */
    fun hiddenIds(data: JsonObject): List<String> = try {
        data["ids"]?.jsonArray?.mapNotNull { (it as? JsonPrimitive)?.content } .orEmpty()
    } catch (_: Exception) {
        emptyList()
    }

    /** Kennungen in Pakete der Server-Grenze teilen */
    fun chunks(ids: List<String>, max: Int): List<List<String>> = ids.distinct().chunked(max)
}

/** 2.5: Warten auf Geräte vor der Umstellung auf Stufe 3 (nur eigene Gerätenamen, sonst die Anzahl der Personen) */
@Serializable
data class X3Wait(
    val mine: List<String> = emptyList(),
    val others: Int = 0,
    /** Stichtag (Unix-Sekunden, ggf. mit Nachkommastellen), danach wird umgestellt */
    val until: Double? = null,
)

/** Warum es in diesem Chat (noch) keine Reaktionen/kein Bearbeiten gibt */
sealed interface X3Hint {
    data object Setting : X3Hint
    data class Devices(val mine: List<String>, val others: Int, val until: Long?) : X3Hint
    data class Server(val hosts: List<String>) : X3Hint
    data object Readonly : X3Hint
    data object Pending : X3Hint
    /** unbekannter Grund (neuerer Server) - allgemeiner Hinweis */
    data object Other : X3Hint

    companion object {
        /** Kein Hinweis, wenn x3_ok gilt oder der Server x3_why nicht kennt (Feld fehlt / null) */
        fun of(s: ChatSummary?): X3Hint? {
            if (s == null || s.x3Ok == true) return null
            return when (s.x3Why ?: return null) {
                "setting" -> Setting
                "devices" -> Devices(s.x3Wait?.mine.orEmpty().filter { it.isNotBlank() }, maxOf(0, s.x3Wait?.others ?: 0), s.x3Wait?.until?.takeIf { it > 0 }?.toLong())
                "server" -> Server(s.x3Hosts.filter { it.isNotBlank() })
                "readonly" -> Readonly
                "pending" -> Pending
                else -> Other
            }
        }
    }
}
