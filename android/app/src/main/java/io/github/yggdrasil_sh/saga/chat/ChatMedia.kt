package io.github.yggdrasil_sh.saga.chat

import kotlin.math.max

/**
 * Medien im Chat (2.5 Medien 1): Bildbetrachter in der App (alle Bilder des Chats in Verlaufsreihenfolge), Zitat mit
 * Vorschaubild und Grenzen fuer Zoomen/Verschieben. Rein und testbar - die Ansicht liefert die uebersetzten Texte.
 */
object ChatMedia {
    /** Zoom im Betrachter: von ganz (1) bis hoechstens so weit; Doppeltippen springt auf [ZOOM_DOUBLE] */
    const val ZOOM_MAX = 5f
    const val ZOOM_DOUBLE = 2.5f
    /** Betrachter: laengste Seite des dekodierten Bilds (Speicher, Canvas-Grenze) */
    const val VIEW_MAX_SIDE = 4096
    /** saga-file/v2-Bilder nur bis zu dieser Groesse ganz in den Speicher holen (sonst bleibt das Vorschaubild) */
    const val V2_VIEW_MAX = 64L * 1024 * 1024

    /** Ein Bild im Betrachter: Nachricht + Anhang */
    data class Item(val msgId: String, val file: ChatFile)

    /**
     * Bild, das in der App angezeigt wird (statt "Öffnen mit"): Bild-Typ, lesbares Format, nicht als Sprachnachricht;
     * grosse v2-Bilder ohne Vorschaubild zeigt der Verlauf als Datei - die bleiben bei "Öffnen mit".
     */
    fun viewable(f: ChatFile): Boolean = f.isImage && !f.unsupported && !f.isVoice && !(f.isV2 && f.th == null)

    /**
     * Alle Bilder des Chats fuer den Betrachter, in Verlaufsreihenfolge (alt -> neu, Reihenfolge der Liste; innerhalb einer
     * Nachricht die der Anhaenge). Ohne ausgeblendete, geloeschte, nicht entschluesselte, System- und Anruf-Eintraege.
     */
    fun gallery(msgs: List<ChatMessage>, hidden: Set<String> = emptySet(), skip: (ChatMessage) -> Boolean = { false }): List<Item> =
        msgs.filter { m -> m.id !in hidden && !m.tomb && m.sys == null && m.call == null && m.content != null && !skip(m) }
            .flatMap { m -> m.content!!.files.filter(::viewable).map { Item(m.id, it) } }

    /** Position des angetippten Bilds in [items]; nicht (mehr) da -> erstes Bild derselben Nachricht, sonst 0 */
    fun indexOf(items: List<Item>, msgId: String, fileId: String): Int {
        val i = items.indexOfFirst { it.msgId == msgId && it.file.id == fileId }
        if (i >= 0) return i
        return items.indexOfFirst { it.msgId == msgId }.coerceAtLeast(0)
    }

    /** Zaehler "n / m" (1-basiert) */
    fun counter(index: Int, count: Int): String = if (count <= 0) "" else "${index.coerceIn(0, count - 1) + 1} / $count"

    // ---------------------------------------------------------------- Zitat (Antwort)

    /** Uebersetzte Platzhalter fuer Zitate ohne Text */
    data class Labels(val photo: String, val video: String, val voice: String)

    /**
     * Kurztext eines zitierten Anhangs, wenn die Nachricht keinen Text hat: "Foto", "Video", "Sprachnachricht" bzw. der
     * Dateiname (erster Anhang). Mit Text bzw. ohne Anhang: null (dann gilt der Text).
     */
    fun attachmentLabel(c: ChatContent?, l: Labels): String? {
        if (c == null || c.text.isNotBlank()) return null
        val f = c.files.firstOrNull() ?: return null
        return when {
            f.isVoice -> l.voice
            f.isImage -> l.photo
            f.isVideo -> l.video
            else -> f.n
        }
    }

    /** Anhang, dessen Vorschaubild im Zitat steht: erstes Bild bzw. Video mit Vorschaubild (sonst keins) */
    fun quoteThumb(c: ChatContent?): ChatFile? = c?.files?.firstOrNull { f ->
        !f.unsupported && !f.isVoice && ((f.isImage && !(f.isV2 && f.th == null)) || (f.isVideo && f.th != null))
    }

    // ---------------------------------------------------------------- Zoomen / Verschieben

    /**
     * Groesse des eingepassten Bilds (ContentScale.Fit) in einem Feld boxW x boxH. Unbekannte Masse -> ganzes Feld.
     */
    fun fitted(imgW: Int, imgH: Int, boxW: Float, boxH: Float): Pair<Float, Float> {
        if (imgW <= 0 || imgH <= 0 || boxW <= 0f || boxH <= 0f) return boxW.coerceAtLeast(0f) to boxH.coerceAtLeast(0f)
        val f = minOf(boxW / imgW, boxH / imgH)
        return imgW * f to imgH * f
    }

    /** Wie weit das gezoomte Bild in einer Richtung verschoben werden darf (Raender bleiben im Feld; nicht gezoomt: 0) */
    fun maxPan(content: Float, box: Float, scale: Float): Float = max(0f, (content * scale - box) / 2f)

    /** Verschiebung auf die erlaubten Grenzen begrenzen */
    fun clampPan(offset: Float, content: Float, box: Float, scale: Float): Float {
        val m = maxPan(content, box, scale)
        return offset.coerceIn(-m, m)
    }

    /** Zoomfaktor begrenzen (1 .. ZOOM_MAX) */
    fun clampZoom(z: Float): Float = if (z.isNaN()) 1f else z.coerceIn(1f, ZOOM_MAX)

    /**
     * Verschiebung nach einer Zoom-Aenderung um den Punkt [focus] (relativ zur Feldmitte): der Punkt unter den Fingern
     * bleibt stehen. -> neue Verschiebung (noch unbegrenzt)
     */
    fun zoomAround(offset: Float, focus: Float, oldScale: Float, newScale: Float): Float {
        if (oldScale <= 0f) return offset
        return focus - (focus - offset) * (newScale / oldScale)
    }

    /** Gezoomt? (dann blaettert der Betrachter nicht - Verschieben hat Vorrang) */
    fun zoomed(scale: Float): Boolean = scale > 1.01f
}
