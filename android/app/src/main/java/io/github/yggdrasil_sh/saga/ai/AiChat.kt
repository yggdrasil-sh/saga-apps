package io.github.yggdrasil_sh.saga.ai

import java.security.MessageDigest

/** Persona (2.1): welche gilt - nur wenn der Server sie liefert und der Assistent fuer das Konto an ist */
object AiPersonaLogic {
    fun effective(st: AiStatus?): AiPersona? = st?.persona?.takeIf { st.enabled && it.name.isNotBlank() }

    /** Pfad des Profilbilds: nur /api/ai/avatar des eigenen Servers (nie eine volle Adresse aus der Antwort) */
    fun avatarPathOk(p: String?): Boolean {
        val a = p?.trim().orEmpty()
        return a.startsWith("/api/ai/avatar") && !a.contains("..") && !a.contains("://")
    }

    /** Zwischenspeicher-Schluessel: gleiche Kennung (avatar_etag) = gleiches Bild, auch wenn sich ?v= aendert */
    fun cacheKey(p: AiPersona, tier: Int): String = "ai-avatar:" + (p.avatarEtag?.takeIf { it.isNotBlank() } ?: p.avatar.orEmpty()) + ":" + tier
}

/** Beleg-Pruefung einer empfangenen KI-Antwort */
enum class AiReplyCheck {
    /** keine KI-Antwort */
    NONE,
    /** Beleg passt: als KI-Antwort zeigen */
    OK,
    /** gibt sich als KI-Antwort aus, Beleg fehlt oder passt nicht: Warnung */
    BAD,
}

/**
 * KI im Chat - nur auf Zuruf (2.1, docs/APP-API-KI.md): reine Regeln fuer Erkennung, Verlauf und Beleg (Tests).
 */
object AiChatLogic {
    /** hoechstens so viele Nachrichten Verlauf je Frage (Server: mehr -> 400) */
    const val CTX_MAX = 12
    /** Zeichen je Verlaufs-Nachricht */
    const val LINE_MAX = 600
    /** Zeichen der Frage */
    const val Q_MAX = 1000

    data class Line(val name: String, val text: String)

    /** SHA-256 (hex) ueber die UTF-8-Bytes des Textes ohne Leerraum am Anfang/Ende */
    fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.trim().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    /**
     * Empfangene Nachricht pruefen: Inhalt traegt ai.reply (body), Server liefert ai {ref, sha256}. OK nur, wenn
     * body.ai.reply == ai.ref und sha256(trim(body.text)) == ai.sha256.
     */
    fun check(bodyReply: String?, bodyHasAi: Boolean, text: String?, ref: String?, sha: String?): AiReplyCheck {
        if (!bodyHasAi) return AiReplyCheck.NONE
        if (bodyReply.isNullOrEmpty() || ref.isNullOrEmpty() || sha.isNullOrEmpty() || text == null || bodyReply != ref) return AiReplyCheck.BAD
        return if (sha256(text).equals(sha, ignoreCase = true)) AiReplyCheck.OK else AiReplyCheck.BAD
    }

    /** "@Name": davor Anfang, Leerraum oder ( [ " '; Gross-/Kleinschreibung egal; danach kein Buchstabe/keine Ziffer/_ */
    fun mentionRegex(name: String): Regex =
        Regex("(?:^|(?<=[\\s(\\[\"']))@" + Regex.escape(name) + "(?![\\p{L}\\p{N}_])", setOf(RegexOption.IGNORE_CASE))

    fun mentions(text: String, name: String?): Boolean = !name.isNullOrBlank() && mentionRegex(name).containsMatchIn(text)

    /**
     * Verlauf vor der Frage: hoechstens [max] Nachrichten mit Text, aelteste zuerst, je hoechstens [LINE_MAX] Zeichen.
     * [msgs] in Chat-Reihenfolge (aelteste zuerst) als (id, Name, Text|null); die Frage selbst und alles danach zaehlt nicht.
     */
    fun history(msgs: List<Triple<String, String, String?>>, questionId: String, max: Int = CTX_MAX): List<Line> {
        val idx = msgs.indexOfFirst { it.first == questionId }
        val before = if (idx >= 0) msgs.subList(0, idx) else msgs.filter { it.first != questionId }
        return before.filter { !it.third.isNullOrBlank() }.takeLast(max.coerceIn(0, CTX_MAX))
            .map { Line(it.second.ifBlank { "?" }, it.third!!.take(LINE_MAX)) }
    }

    /** Frage fuer den Server (hoechstens Q_MAX Zeichen) */
    fun question(text: String): String = text.trim().take(Q_MAX)

    /**
     * Darf dieses Konto die KI im Chat erlauben/verbieten? freie Gruppe: Chat-Admin; Direkt-Chat: beide; Gruppen-Chat:
     * Saga-Admin; Seiten-Chat: Eigentuemer der Seite. Unbekannt (null) -> anbieten, der Server entscheidet (403).
     */
    fun mayToggle(kind: String, role: String?, sagaAdmin: Boolean?, pageOwner: Boolean?): Boolean = when (kind) {
        "free" -> role == "admin"
        "dm" -> true
        "group" -> sagaAdmin != false
        "page" -> pageOwner != false
        else -> false
    }

    /** "@" + Suchtext direkt vor dem Cursor -> (Start des "@", Suchtext) oder null */
    fun atQuery(text: String, cursor: Int): Pair<Int, String>? {
        val before = text.substring(0, cursor.coerceIn(0, text.length))
        val m = Regex("(^|\\s)@([^\\s@]{0,30})$").find(before) ?: return null
        val at = m.range.first + m.groupValues[1].length
        return at to m.groupValues[2]
    }

    /** KI als (erster) Vorschlag, wenn der Suchtext ein Anfang ihres Namens ist */
    fun suggestAi(query: String, name: String?): Boolean = !name.isNullOrBlank() && name.lowercase().startsWith(query.lowercase())
}
