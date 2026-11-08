package io.github.yggdrasil_sh.saga.ui.notifications

import io.github.yggdrasil_sh.saga.live.NotifItem

/**
 * Design-Runde 4 (NAV-03/22): Benachrichtigungen gebuendelt wie im Web - eine Zeile je Gespraechseinheit
 * (group_key "chat:<id>", "call:<chat>", "page:<seite>") und Lesestand. Der Server buendelt (?group=1: ids, counts,
 * group_key); was live dazukommt oder beim Blaettern nachkommt, fuegt [regroup] mit denselben Regeln hinzu
 * (static/live.js absorb/regroup/dropIds). Alte Server ohne group_key: jede Zeile bleibt einzeln.
 * Rein (Tests).
 */
object NotifGroups {
    /** Alle Benachrichtigungen einer Zeile, neueste zuerst */
    fun idsOf(it: NotifItem): List<Long> = it.ids.ifEmpty { listOf(it.id) }

    fun countsOf(it: NotifItem): List<Int> {
        val ids = idsOf(it)
        return when {
            it.counts.size == ids.size -> it.counts
            ids.size == 1 -> listOf(maxOf(1, it.count))
            else -> ids.map { 1 }
        }
    }

    /** Neueste Benachrichtigung der Zeile (Sortierung) */
    fun head(it: NotifItem): Long = idsOf(it).maxOrNull() ?: it.id

    fun isGroup(it: NotifItem): Boolean = idsOf(it).size > 1

    /** Gespraechseinheit ("chat", "call", "page") oder null */
    fun unit(it: NotifItem): String? = it.groupKey?.substringBefore(':')?.ifEmpty { null }

    /** Namen der Zeile (neueste zuerst) */
    fun who(it: NotifItem): List<String> = it.actors.ifEmpty { listOfNotNull(it.actor?.name?.takeIf { n -> n.isNotBlank() }) }

    /** "A, B, C …" (hoechstens 3 Namen wie Web/Server) */
    fun namesShort(names: List<String>, max: Int = 3): String = names.take(max).joinToString(", ") + if (names.size > max) " …" else ""

    /** it in g aufnehmen; die neuere Zeile wird Kopf (alle ihre Felder), ids/counts/actors vereinigt */
    fun absorb(g: NotifItem, it: NotifItem): NotifItem {
        val gi = idsOf(g); val ii = idsOf(it)
        val newer = head(it) > head(g)
        val headRow = if (newer) it else g
        val ids = if (newer) ii + gi else gi + ii
        val cts = if (newer) countsOf(it) + countsOf(g) else countsOf(g) + countsOf(it)
        val outIds = ArrayList<Long>(); val outCts = ArrayList<Int>()
        ids.forEachIndexed { i, id -> if (id !in outIds) { outIds += id; outCts += cts[i] } }
        val nm = (if (newer) who(it) + who(g) else who(g) + who(it)).distinct()
        val media = if (g.media == it.media) g.media else if (g.media != null || it.media != null) "audio" else null
        val first = listOfNotNull(g.first ?: g.created, it.first ?: it.created).minOrNull()
        return headRow.copy(
            ids = outIds, counts = outCts, count = outCts.sum(),
            actors = if (nm.size > 1) nm else emptyList(),
            mention = g.mention || it.mention || g.kind == "comment.mention" || it.kind == "comment.mention",
            media = media, first = first,
        )
    }

    /** Gleiche Einheit und gleicher Lesestand -> eine Zeile; neueste zuerst */
    fun regroup(list: List<NotifItem>): List<NotifItem> {
        val out = ArrayList<NotifItem>()
        val at = HashMap<String, Int>()
        for (it in list) {
            val k = it.groupKey
            if (k.isNullOrEmpty()) { out += it; continue }
            val key = k + if (it.read) "|r" else "|u"
            val i = at[key]
            if (i == null) { at[key] = out.size; out += it } else out[i] = absorb(out[i], it)
        }
        return out.sortedByDescending { head(it) }
    }

    /** Ersetzte/geloeschte Benachrichtigungen aus den Zeilen nehmen (leere Zeilen fallen weg) */
    fun dropIds(list: List<NotifItem>, gone: Set<Long>): List<NotifItem> = list.mapNotNull { row ->
        val ids = idsOf(row)
        if (ids.none { it in gone }) return@mapNotNull row
        val cts = countsOf(row)
        val keep = ids.indices.filter { ids[it] !in gone }
        if (keep.isEmpty()) return@mapNotNull null
        val kIds = keep.map { ids[it] }; val kCts = keep.map { cts[it] }
        row.copy(id = if (row.id in gone) kIds.first() else row.id, ids = kIds, counts = kCts, count = kCts.sum())
    }

    /** Live: neue Benachrichtigung in die Zeile ihrer Einheit (neuer Kopf); replaces und eigene id vorher heraus */
    fun insert(list: List<NotifItem>, item: NotifItem, replaces: Long?): List<NotifItem> =
        regroup(listOf(item) + dropIds(list, setOfNotNull(item.id, replaces)))

    /** Ergebnis von [markRead]: neue Liste, ob eine Zeile nur teilweise gelesen ist (neu laden), gelesene Einheiten */
    data class Marked(val list: List<NotifItem>, val partial: Boolean, val readKeys: Set<String>)

    /** Gelesen: ganze Zeilen, deren ids alle dabei sind, oder deren Einheit in groups steht; all = alle */
    fun markRead(list: List<NotifItem>, ids: Set<Long>, groups: Set<String>, all: Boolean): Marked {
        var partial = false
        val keys = HashSet<String>(groups)
        val out = list.map { row ->
            if (row.read) return@map row
            val rIds = idsOf(row)
            val hit = rIds.count { it in ids }
            val done = all || (row.groupKey != null && row.groupKey in groups) || hit == rIds.size
            if (done) {
                row.groupKey?.let { keys += it }
                row.copy(read = true)
            } else {
                if (hit > 0) partial = true
                row
            }
        }
        return Marked(regroup(out), partial, keys)
    }

    /** Abschnitte: "Neu" (ungelesen) und "Früher" (gelesen), je neueste zuerst */
    fun sections(list: List<NotifItem>): Pair<List<NotifItem>, List<NotifItem>> = list.partition { !it.read }

    /** Welcher Satz eine gebuendelte Zeile beschreibt (Texte: strings_r4notify.xml, wie saga/notify.py _group_text) */
    enum class Sentence { MESSAGES, MESSAGES_FROM, CALLS, VIDEO_CALLS, COMMENTS, COMMENTS_MENTION }

    /** null = keine Gruppe, der fertige Satz des Servers (text) gilt */
    fun sentence(it: NotifItem): Sentence? {
        if (!isGroup(it) || it.groupKey == null) return null
        return when (unit(it)) {
            "chat" -> if (it.chat?.kind.let { k -> k != null && k != "dm" } && who(it).isNotEmpty()) Sentence.MESSAGES_FROM else Sentence.MESSAGES
            "call" -> if (it.media == "video") Sentence.VIDEO_CALLS else Sentence.CALLS
            else -> if (it.mention || it.kind == "comment.mention") Sentence.COMMENTS_MENTION else Sentence.COMMENTS
        }
    }

    /** Zeit fuer eine Zeile: die neueste (Kopf) */
    fun time(it: NotifItem): Double? = it.created
}
