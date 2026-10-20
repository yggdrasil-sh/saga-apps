package io.github.yggdrasil_sh.saga.chat

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

// Chat-Grundlagen (Saga 2.5, Konzept Chat B1/B2; Server saga/chat_x3.py, docs/APP-API.md "Chat-Stufe 3"): reine Logik
// ohne Krypto und UI (JVM-Tests) - Faehigkeiten und Grenzen des Servers, Reaktionen (Form, je Person die neueste, Chips),
// Bearbeiten (wer, wann, wie oft; Pruefung gegen das Original). Gegenstueck zu ios/App/ChatX3Logic.swift und
// static/chat-crypto.js (reactBody, editCheck).

/** Grenzen aus GET /api/chat/me (x3_limits); fehlt das Feld, gelten die Werte des Konzepts */
@Serializable
data class X3Limits(
    @SerialName("edit_window") val editWindow: Double = 86_400.0,
    @SerialName("edit_max") val editMax: Int = 20,
    @SerialName("emoji_max") val emojiMax: Int = 32,
    @SerialName("react_ct_max") val reactCtMax: Int = 400,
    @SerialName("react_rate") val reactRate: Int = 120,
    @SerialName("voice_max_s") val voiceMaxS: Double = 900.0,
    @SerialName("voice_kbps") val voiceKbps: Int = 24,
    @SerialName("voice_max_bytes") val voiceMaxBytes: Long = 4L * 1_048_576,
)

/** Reaktion wie in GET .../messages (rx), chat.react und PUT .../reaction: {by (Handle), dev, ts (ms), ct, sig} */
@Serializable
data class Reaction(val by: String = "", val dev: String = "", val ts: Long = 0, val ct: String = "", val sig: String = "")

/** Geoeffnete Reaktion einer Person ("" = zurueckgenommen) */
data class OpenedReaction(val by: String, val e: String, val ts: Long)

/** Chip unter der Blase: Emoji, Zahl, eigene dabei, wer (Handles in Reihenfolge der Reaktion) */
data class ReactionChip(val e: String, val n: Int, val mine: Boolean, val who: List<String>)

enum class EditReject(val code: String) { EDIT_OF_MISMATCH("edit_of_mismatch"), OTHER_SENDER("other_sender"), AI_ORIGINAL("ai_original"), WINDOW("window") }

object ChatX3 {
    /** Schnellleiste (Konzept B1, Web: 👍 ❤️ 😂 😮 😢 🙏) */
    val QUICK = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")

    /** Entprellen: nur die letzte Wahl innerhalb von 500 ms geht raus */
    const val DEBOUNCE_MS = 500L

    // ---------------------------------------------------------------- Faehigkeiten

    fun has(me: ChatMe?, feature: String): Boolean = me?.features?.contains(feature) == true

    /** Reaktionen/Bearbeiten/Sprachnachrichten in diesem Chat: Server meldet x3_ok (Format v2, angenommen, beschreibbar) */
    fun chatOk(s: ChatSummary?): Boolean = s != null && s.x3Ok == true && !s.request && !s.readonly

    // ---------------------------------------------------------------- Reaktionen

    /** Je Person nur die neueste (ts) - der Server haelt ohnehin nur eine; sortiert nach Zeit, dann Handle */
    fun newest(items: List<Reaction>): List<Reaction> {
        val by = LinkedHashMap<String, Reaction>()
        for (r in items) {
            val o = by[r.by]
            if (o == null || o.ts <= r.ts) by[r.by] = r
        }
        return by.values.sortedWith(compareBy<Reaction> { it.ts }.thenBy { it.by })
    }

    /** Live/Nachladen in den Bestand einmischen (neuere ts ersetzt, aeltere nicht) */
    fun merge(old: List<Reaction>, new: List<Reaction>): List<Reaction> = newest(old + new)

    /** Inhalt kompakt wie JSON.stringify({e, ts}) - genau diese Bytes werden verschluesselt */
    fun reactBodyJson(emoji: String, ts: Long): String = "{\"e\":" + jsString(emoji) + ",\"ts\":" + ts + "}"

    /** Wie JSON.stringify fuer Texte: nur Steuerzeichen, " und \ werden maskiert (UTF-8 bleibt, anders als json.dumps) */
    fun jsString(s: String): String {
        val sb = StringBuilder(s.length + 8).append('"')
        for (ch in s) when (ch) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            '\b' -> sb.append("\\b")
            '\u000c' -> sb.append("\\f")
            else -> if (ch.code < 0x20) sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
        }
        return sb.append('"').toString()
    }

    private val json = Json { isLenient = false }

    private fun badChar(cp: Int) = cp < 0x20 || cp in 0x7f..0x9f

    /** Entschluesselter Inhalt -> Emoji ("" = zurueckgenommen) oder null; wie react_body_ok in saga/chat_v3.py */
    fun reactBody(pt: String, ts: Long): String? {
        val o = try {
            json.parseToJsonElement(pt) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return null
        if (o.keys != setOf("e", "ts")) return null
        val t = o["ts"] as? JsonPrimitive ?: return null
        if (t.isString || t.content.contains('.') || t.content.contains('e', true) || t.longOrNull != ts) return null
        val e = (o["e"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        if (e.toByteArray(Charsets.UTF_8).size > 32 || e.codePoints().anyMatch { badChar(it) }) return null
        return e
    }

    /**
     * Anzahl der Grapheme (vereinfacht fuer Emojis, ohne ICU - laeuft auch im JVM-Test): Verbinder (ZWJ), Variations-
     * selektoren, Hauttoene, Tag-Zeichen und Keycap haengen am vorigen Zeichen, Flaggen sind Paare.
     */
    fun graphemes(s: String): Int {
        var n = 0
        var joinNext = false
        var ri = 0
        val cps = s.codePoints().toArray()
        for (cp in cps) {
            val ext = cp == 0x200d || cp in 0xfe00..0xfe0f || cp in 0x1f3fb..0x1f3ff || cp in 0xe0020..0xe007f || cp == 0x20e3 ||
                cp in 0x0300..0x036f
            when {
                cp == 0x200d -> joinNext = true
                ext -> {}
                joinNext -> joinNext = false
                cp in 0x1f1e6..0x1f1ff -> {
                    if (ri % 2 == 0) n++
                    ri++
                    continue
                }
                else -> n++
            }
            if (cp !in 0x1f1e6..0x1f1ff) ri = 0
        }
        return n
    }

    /** Auswahl gueltig? genau ein Graphem, hoechstens 32 Bytes, keine Steuerzeichen */
    fun emojiOk(e: String): Boolean =
        e.isNotEmpty() && e.toByteArray(Charsets.UTF_8).size <= 32 && e.codePoints().noneMatch { badChar(it) } && graphemes(e) == 1

    /** Chips in der Reihenfolge der ersten Reaktion je Emoji (wie im Web); zurueckgenommene ("") zaehlen nicht */
    fun chips(list: List<OpenedReaction>, me: String): List<ReactionChip> {
        val order = ArrayList<String>()
        val who = HashMap<String, MutableList<String>>()
        for (x in list.sortedWith(compareBy<OpenedReaction> { it.ts }.thenBy { it.by })) {
            if (x.e.isEmpty()) continue
            if (x.e !in who) order.add(x.e)
            who.getOrPut(x.e) { ArrayList() }.add(x.by)
        }
        return order.map { e -> val w = who[e]!!; ReactionChip(e, w.size, me in w, w) }
    }

    /** Meine aktuelle Reaktion */
    fun mine(list: List<OpenedReaction>, me: String): String? = list.firstOrNull { it.by == me && it.e.isNotEmpty() }?.e

    /** Tippen auf ein Emoji (Leiste oder Chip): gleiches nochmal = zuruecknehmen */
    fun toggle(current: String?, picked: String): String = if (current == picked) "" else picked

    /** Eigene Wahl sofort zeigen (vor der Antwort des Servers) */
    fun applyLocal(list: List<OpenedReaction>, me: String, e: String, ts: Long): List<OpenedReaction> =
        list.filter { it.by != me } + (if (e.isNotEmpty()) listOf(OpenedReaction(me, e, ts)) else emptyList())

    // ---------------------------------------------------------------- Bearbeiten

    /**
     * Darf ich diese Nachricht bearbeiten? Nur eigene (v2, eigener Handle), mit Text bzw. Bildunterschrift, keine KI-Antwort,
     * keine Sprachnachricht, zugestellt, im Fenster (Serverzeit created) und hoechstens editMax-mal
     */
    fun canEdit(mine: Boolean, hasText: Boolean, ai: Boolean, voice: Boolean, pending: Boolean, created: Double, editN: Int, now: Double, l: X3Limits): Boolean =
        mine && hasText && !ai && !voice && !pending && now - created < l.editWindow && editN < l.editMax

    /** Bearbeitung gegen das Original pruefen (wie edit_check in saga/chat_v3.py); body = entschluesselter Inhalt {edit, t} */
    fun editCheck(
        origId: String, origSender: String?, origAi: Boolean, origCreated: Double,
        editOf: String?, editSender: String?, editCreated: Double, body: JsonObject?, window: Double = 86_400.0,
    ): EditReject? {
        val t = (body?.get("t") as? JsonPrimitive)?.takeIf { it.isString }
        val be = (body?.get("edit") as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (body == null || t == null || be == null || be != editOf || editOf != origId) return EditReject.EDIT_OF_MISMATCH
        if (editSender.isNullOrEmpty() || editSender != origSender) return EditReject.OTHER_SENDER
        if (origAi) return EditReject.AI_ORIGINAL
        if (editCreated - origCreated > window) return EditReject.WINDOW
        return null
    }

    /** Inhalt einer Bearbeitung (kompakt): {"edit": <msg_id>, "t": <text>} */
    fun editBody(msgId: String, text: String): String = "{\"edit\":" + jsString(msgId) + ",\"t\":" + jsString(text) + "}"

    /** Text einer gueltigen Bearbeitung aus dem Inhalt */
    fun editText(body: JsonObject?): String? = (body?.get("t") as? JsonPrimitive)?.takeIf { it.isString }?.content
}
