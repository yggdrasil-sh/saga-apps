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

    /** Pruefer R5: was gespeichert wird - bei "Nur Art" ohne Text */
    fun forMode(p: ListPreview?, mode: Mode): ListPreview? = if (mode == Mode.KIND) p?.copy(text = "") else p

    /** Pruefer R5: beim Umschalten auf "Nur Art" alle gemerkten Texte verwerfen */
    fun stripTexts(all: Map<Long, ListPreview>): Map<Long, ListPreview> =
        if (all.values.none { it.text.isNotEmpty() }) all else all.mapValues { (_, p) -> p.copy(text = "") }

    /** Eintrag merken (neueste je Chat), Gesamtzahl begrenzen: die zuletzt gemerkten bleiben */
    fun put(all: Map<Long, ListPreview>, chat: Long, p: ListPreview?): Map<Long, ListPreview> {
        val m = LinkedHashMap(all)
        m.remove(chat)
        if (p != null) m[chat] = p
        return if (m.size <= KEEP_MAX) m else m.entries.toList().takeLast(KEEP_MAX).associate { it.key to it.value }
    }
}

/**
 * CH-10: ⋮-Menue der Unterhaltung - hoechstens [MAX] Eintraege, Linien-Symbole, Trenner vor destruktiven. Seltenes
 * (Sicherheitsnummer, Automatisch loeschen, Medien, Sperren/Melden, Umbenennen) steht in der Chat-Info bzw. Gruppeninfo.
 */
object ChatMenuLogic {
    const val MAX = 6

    enum class Item(val destructive: Boolean = false) { INFO, SELECT, MUTE, AI, ARCHIVE, LEAVE(true), DELETE(true) }

    data class Flags(
        /** freie Gruppe (Verlassen) */
        val free: Boolean = false,
        /** KI an-/ausschalten erlaubt */
        val ai: Boolean = false,
        /** Archiv/Loeschen kann der Server */
        val canArchive: Boolean = false,
    )

    fun items(f: Flags): List<Item> {
        val top = mutableListOf(Item.INFO, Item.SELECT, Item.MUTE)
        if (f.ai) top += Item.AI
        if (f.canArchive) top += Item.ARCHIVE
        val bad = listOfNotNull(Item.LEAVE.takeIf { f.free }, Item.DELETE.takeIf { f.canArchive })
        // zu viele: Archivieren weicht (steht in Chat-Info/Gruppeninfo), dann die KI (ebenso)
        for (drop in listOf(Item.ARCHIVE, Item.AI)) if (top.size + bad.size > MAX) top.remove(drop)
        return top + bad
    }

    /** Trenner vor dem ersten destruktiven Eintrag (nur, wenn davor etwas steht) */
    fun dividerBefore(items: List<Item>, i: Int): Boolean = i > 0 && items[i].destructive && !items[i - 1].destructive
}

/** CH-10: Chat-Info (Direkt-Chats und Chats ohne Gruppeninfo) - welche Zeilen gezeigt werden */
object ChatInfoLogic {
    enum class Row { MEDIA, MEMBERS, MUTE, RETENTION, SAFETY, RENAME, ARCHIVE, BLOCK, REPORT, LEAVE }

    data class Flags(
        val dm: Boolean, val media: Boolean, val canArchive: Boolean, val free: Boolean, val canRename: Boolean,
        /** Sperren moeglich (block2, Person dieses Servers) bzw. Melden (report1, es gibt Nachrichten des Gegenuebers) */
        val block: Boolean, val report: Boolean, val hasOthers: Boolean,
    )

    fun rows(f: Flags): List<Row> = buildList {
        if (f.media) add(Row.MEDIA)
        if (!f.dm) add(Row.MEMBERS)
        add(Row.MUTE)
        add(Row.RETENTION)
        if (f.hasOthers) add(Row.SAFETY)
        if (f.free && f.canRename) add(Row.RENAME)
        if (f.canArchive) add(Row.ARCHIVE)
        if (f.dm && f.block) add(Row.BLOCK)
        if (f.report) add(Row.REPORT)
        if (f.free) add(Row.LEAVE)
    }

    /** R5-Nachbesserung: Breite der Chat-Info auf grossen Bildschirmen (Zeilen und Trennlinien gleich breit, zentriert) */
    const val MAX_WIDTH_DP = 640

    enum class Mute { ALL, MENTIONS, OFF, UNTIL, ALWAYS }

    /** Zustand fuer den Untertitel von "Stummschalten" */
    fun muteState(c: ChatSummary, nowSec: Double, mute2: Boolean): Mute {
        if (!mute2) return if (c.muted) Mute.ALWAYS else Mute.ALL
        return when (ChatStd.muteState(c, nowSec)) {
            ChatStd.MuteState.Always -> Mute.ALWAYS
            is ChatStd.MuteState.Until -> Mute.UNTIL
            ChatStd.MuteState.Off -> when (c.notify) {
                "mentions" -> Mute.MENTIONS
                "none" -> Mute.OFF
                else -> Mute.ALL
            }
        }
    }

    /** rot unten (eigener Abschnitt) */
    fun danger(r: Row): Boolean = r == Row.BLOCK || r == Row.REPORT || r == Row.LEAVE
}

/** CH-11: Kacheln des Anhang-Menues (Bottom Sheet) in fester Reihenfolge */
object AttachLogic {
    enum class Tile { GALLERY, CAMERA, VIDEO_CAMERA, FILE, PAGE, IMMICH, PASTE }

    fun tiles(camera: Boolean, immich: Boolean, clipImage: Boolean): List<Tile> = buildList {
        add(Tile.GALLERY)                    // Fotos und Videos (PickMultipleVisualMedia)
        if (camera) { add(Tile.CAMERA); add(Tile.VIDEO_CAMERA) }
        add(Tile.FILE)
        add(Tile.PAGE)
        if (immich) add(Tile.IMMICH)
        if (clipImage) add(Tile.PASTE)
    }
}

/** CH-12: gewaehlte Anhaenge ueber dem Eingabefeld - Bilder als Vorschaureihe, Dateien als Chip */
object PendingLogic {
    fun thumb(mime: String): Boolean = mime.startsWith("image/") && mime != "image/svg+xml"

    fun images(files: List<PendingFile>): List<PendingFile> = files.filter { thumb(it.mime) }
    fun others(files: List<PendingFile>): List<PendingFile> = files.filter { !thumb(it.mime) }

    /** Platzhalter "Bildunterschrift hinzufügen", sobald ein Bild oder Video gewaehlt ist (sonst der uebliche) */
    fun caption(files: List<PendingFile>, videos: Int): Boolean = videos > 0 || files.any { thumb(it.mime) }

    /** inSampleSize fuer eine Vorschau von etwa target px (Zweierpotenz, mindestens 1) */
    fun sample(w: Int, h: Int, target: Int): Int {
        var n = 1
        while (w / (n * 2) >= target && h / (n * 2) >= target) n *= 2
        return n
    }
}

/** CH-26: Blase nach rechts wischen = Antworten */
object SwipeReply {
    const val THRESHOLD_DP = 64f
    const val MAX_DP = 96f

    /** nicht im Auswahlmodus und nur fuer Nachrichten, auf die man antworten kann */
    fun enabled(selecting: Boolean, canReply: Boolean): Boolean = !selecting && canReply

    /** neue Verschiebung: nur nach rechts, hoechstens max */
    fun drag(cur: Float, delta: Float, max: Float): Float = (cur + delta).coerceIn(0f, max)

    /** Schwelle gerade erreicht (einmal Haptik) */
    fun crossed(before: Float, after: Float, threshold: Float): Boolean = before < threshold && after >= threshold

    /** beim Loslassen antworten? */
    fun fires(x: Float, threshold: Float): Boolean = x >= threshold

    /** Sichtbarkeit des Antwort-Symbols 0..1 */
    fun progress(x: Float, threshold: Float): Float = if (threshold <= 0f) 0f else (x / threshold).coerceIn(0f, 1f)
}
