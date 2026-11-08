package io.github.yggdrasil_sh.saga.chat

/**
 * Android-Design-Runde 5 (Chats & Anrufe, Chat-Oberflaeche): reine Regeln fuer "Neuer Chat" (CH-06) - ohne Android,
 * mit Unit-Tests (DesignR5ChatTest).
 */
object NewChatLogic {
    /** Person (Direkt-Chat), mehrere Personen (neue Gruppe) bzw. Gruppe aus einem eigenen Team */
    enum class Mode { PERSON, GROUP, TEAM }

    /** Was statt Treffern unter dem Suchfeld steht */
    enum class Empty { NONE, TOO_SHORT, NO_HITS }

    /**
     * Leerzustand: ohne Eingabe nichts (Kontakte und feste Eintraege stehen da), zu kurz fuer den Server (aeltere Server
     * erst ab 3 Zeichen) -> Regel als Leerzustand, nach der Antwort ohne Treffer und ohne name@server-Hinweis -> "Keine Treffer".
     */
    fun empty(q: String, min: Int, loadedFor: String?, hits: Int, fedHint: Boolean): Empty {
        val t = q.trim()
        return when {
            t.isEmpty() -> Empty.NONE
            t.length < min -> Empty.TOO_SHORT
            loadedFor != q || fedHint -> Empty.NONE
            hits == 0 -> Empty.NO_HITS
            else -> Empty.NONE
        }
    }

    /** Feste Eintraege oben ("Neue Gruppe", ggf. "Gruppe aus deinen Teams") nur im Personen-Modus ohne Eingabe */
    fun showFixed(mode: Mode, q: String): Boolean = mode == Mode.PERSON && q.isBlank()

    /**
     * Teams fuer "Gruppe aus deinen Teams": ohne Eingabe die Teams, aus denen es schon Gruppen-Chats gibt (lokal bekannt),
     * mit Eingabe dazu die Treffer des Servers (eigene Gruppen) - ohne Doppelte, nach Namen sortiert, gefiltert nach q.
     */
    fun teams(chats: List<ChatSummary>, found: List<PeopleGroup>, q: String): List<String> {
        val needle = q.trim().lowercase()
        val local = chats.filter { it.kind == "group" }.mapNotNull { it.group?.takeIf { g -> g.isNotBlank() } }
        return (local + found.map { it.name }.filter { it.isNotBlank() })
            .distinct()
            .filter { needle.isEmpty() || it.lowercase().contains(needle) }
            .sortedBy { it.lowercase() }
    }

    /** "Erstellen" in "Neue Gruppe": mindestens eine Person (dieses oder eines anderen Servers) */
    fun canCreate(mode: Mode, picked: Int, pickedRemote: Int): Boolean = mode == Mode.GROUP && picked + pickedRemote > 0
}

/**
 * CH-07: Vorschau der letzten Nachricht in der Chat-Liste. Quelle ist nur der lokale, schon entschluesselte Stand (die
 * zuletzt in diesem Chat geladenen Nachrichten) - in der Liste wird nichts entschluesselt. Gespeichert wird wenig
 * (Art, gekuerzter Text, Absendername) und nur verschluesselt wie Entwuerfe (ChatStore, beim Abmelden geloescht).
 */
@kotlinx.serialization.Serializable
data class ListPreview(
    /** Position der gezeigten Nachricht und bis wohin der Stand reicht (Server-last_seq beim Merken) */
    val seq: Long = 0,
    val upTo: Long = 0,
    /** text | image | video | voice | file | call | missed (Anruf verpasst) */
    val kind: String = "text",
    val text: String = "",
    /** Absender (nur Gruppen, nie bei eigenen) */
    val from: String? = null,
    val mine: Boolean = false,
    /** Sprachnachricht: Dauer in s; Anruf: video */
    val dur: Int = 0,
    val video: Boolean = false,
    /** laeuft ab (Serverzeit, s) - danach nicht mehr zeigen */
    val exp: Double? = null,
)

object ListPreviewLogic {
    const val TEXT_MAX = 140
    const val KEEP_MAX = 400

    /** Einstellung "Vorschau in der Chat-Liste" */
    enum class Mode(val key: String) {
        TEXT("text"), KIND("kind");
        companion object { fun of(s: String?): Mode = entries.firstOrNull { it.key == s } ?: TEXT }
    }

    /** Texte der Ansicht (uebersetzt) - Platzhalter %1$s fuer Namen bzw. Dauer */
    data class Labels(
        val you: String, val message: String, val photo: String, val video: String, val voice: String, val voiceDur: String,
        val file: String, val call: String, val videoCall: String, val missed: String, val missedVideo: String, val page: String,
    )

    /** Text fuer die Liste: ohne Seitenverweis-Kennungen, ohne Zeilenumbrueche, gekuerzt */
    fun clean(text: String, noteTitle: (Long) -> String?, page: String): String {
        val t = ChatFormat.NOTE_REF.replace(text) { mr -> mr.groupValues[1].toLongOrNull()?.let(noteTitle)?.takeIf { it.isNotBlank() } ?: page }
        val one = t.replace(Regex("\\s+"), " ").trim()
        return if (one.length > TEXT_MAX) one.take(TEXT_MAX - 1).trimEnd() + "…" else one
    }

    /** Taugt die Nachricht als Vorschau? (keine Systemzeilen, Grabsteine, unlesbaren oder Steuer-Nachrichten) */
    fun shows(m: ChatMessage): Boolean = when {
        m.tomb || m.sys != null -> false
        m.call != null -> true
        else -> m.content?.let { c -> c.type == "text" && (c.text.isNotBlank() || c.files.isNotEmpty()) } == true
    }

    /**
     * Vorschau aus den geladenen Nachrichten (alt -> neu): die letzte zeigbare; upTo = bekannter Stand des Chats.
     * null = nichts Zeigbares (dann zeigt die Liste die Art des Chats).
     */
    fun of(
        messages: List<ChatMessage>, me: String, upTo: Long, group: Boolean, nameOf: (ChatMessage) -> String,
        noteTitle: (Long) -> String? = { null }, page: String = "",
    ): ListPreview? {
        val m = messages.lastOrNull { shows(it) } ?: return null
        val call = m.call
        if (call != null) {
            val missed = io.github.yggdrasil_sh.saga.call.CallLogic.missed(call, me)
            return ListPreview(m.seq, upTo, if (missed) "missed" else "call", video = call.media == "video", mine = call.from == me, exp = m.expiresAt)
        }
        val c = m.content ?: return null
        val f = c.files.firstOrNull { !it.unsupported } ?: c.files.firstOrNull()
        val kind = when {
            f == null -> "text"
            f.isVoice -> "voice"
            f.isImage -> "image"
            f.isVideo -> "video"
            else -> "file"
        }
        return ListPreview(
            seq = m.seq, upTo = upTo, kind = kind, text = clean(c.text, noteTitle, page),
            from = if (group && !m.mine) nameOf(m).trim().take(40).ifEmpty { null } else null,
            mine = m.mine, dur = if (kind == "voice") (f?.dur ?: 0.0).toInt().coerceAtLeast(0) else 0, exp = m.expiresAt,
        )
    }

    /** Gilt die Vorschau noch? Nur, wenn sie den neuesten Stand der Liste abdeckt und nicht abgelaufen/geloescht ist. */
    fun valid(p: ListPreview?, c: ChatSummary, nowSec: Double): Boolean =
        p != null && p.upTo >= c.lastSeq && c.lastSeq > 0 && p.seq > c.clearedSeq && (p.exp == null || p.exp > nowSec)

    /** "0:12", "1:05", "61:00" */
    fun dur(sec: Int): String = "%d:%02d".format(sec.coerceAtLeast(0) / 60, sec.coerceAtLeast(0) % 60)

    /** Zeile fuer die Liste: "Du: …", in Gruppen "Name: …", Art (Foto, Sprachnachricht 0:12, Verpasster Anruf …) */
    fun render(p: ListPreview, mode: Mode, l: Labels): String {
        val what = when (p.kind) {
            "image" -> l.photo
            "video" -> l.video
            "voice" -> if (p.dur > 0) l.voiceDur.replace("%1\$s", dur(p.dur)) else l.voice
            "file" -> l.file
            "call" -> if (p.video) l.videoCall else l.call
            "missed" -> if (p.video) l.missedVideo else l.missed
            else -> l.message
        }
        if (p.kind == "call" || p.kind == "missed") return what
        val body = when {
            mode == Mode.KIND -> what
            p.kind == "text" -> p.text.ifEmpty { what }
            p.text.isNotEmpty() && p.kind != "voice" -> "$what · ${p.text}"
            else -> what
        }
        val who = if (p.mine) l.you else p.from
        return if (who.isNullOrEmpty()) body else "$who: $body"
    }

    /** Eintrag merken (neueste je Chat), Gesamtzahl begrenzen: die zuletzt gemerkten bleiben */
    fun put(all: Map<Long, ListPreview>, chat: Long, p: ListPreview?): Map<Long, ListPreview> {
        val m = LinkedHashMap(all)
        m.remove(chat)
        if (p != null) m[chat] = p
        return if (m.size <= KEEP_MAX) m else m.entries.toList().takeLast(KEEP_MAX).associate { it.key to it.value }
    }
}
