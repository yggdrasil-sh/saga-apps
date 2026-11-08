package io.github.yggdrasil_sh.saga.push

/**
 * 2.5 (Push-Nutzlast 2, Aktualisierungen): Der Server meldet still, dass sich der Glocken-Eintrag eines Chats geaendert
 * hat (Nachricht fuer alle geloescht) - {update: true, kind, tag "chat-<id>", n = noch ungelesene, removed bei n 0}.
 * Hier steht, wie die App ihre schon gezeigte Chat-Mitteilung anpasst: Zeilen der MessagingStyle-Mitteilung kuerzen,
 * Zahl der neutralen Mitteilung setzen oder schliessen. Nie etwas Neues zeigen (keine Mitteilung da -> nichts).
 * Rein (Tests); die Anzeige macht Notifier.update.
 */
object NotifyUpdate {
    /** Zeile der Mitteilung: count > 0 = so viele Nachrichten (eine Zeile mit Zahl), 0 = Erwaehnung; sender = Person */
    data class Line(val count: Int, val sender: String?)

    sealed interface Plan {
        /** nichts aendern */
        data object Keep : Plan

        /** Mitteilung schliessen */
        data object Cancel : Plan

        /** MessagingStyle: diese Zeilen (Index in der bisherigen Liste, neue Zahl) bleiben, in dieser Reihenfolge */
        data class Lines(val keep: List<Pair<Int, Int>>) : Plan

        /** neutrale Mitteilung (ohne Namen): neue Zahl */
        data class Count(val n: Int) : Plan
    }

    private fun isMention(kind: String) = kind == "chat.mention"

    /**
     * MessagingStyle-Mitteilung (mit Namen, lokaler Stand). Nur die Zeilen der gemeldeten Art zaehlen (Nachrichten bzw.
     * Erwaehnungen - beide teilen sich den tag des Chats). Ist mehr gezeigt als noch ungelesen ist, wird von der
     * aeltesten Zeile an gekuerzt - ausser die neueste Zeile ist von jemand anderem als dem, den der Server jetzt als
     * neuesten Absender nennt (actor), und dieser steht in einer Zeile: dann war die neueste Nachricht die geloeschte.
     */
    fun lines(lines: List<Line>, kind: String, n: Int, actor: String?): Plan {
        if (lines.isEmpty()) return Plan.Keep
        val mention = isMention(kind)
        val target = maxOf(0, n)
        val idx = lines.indices.filter { if (mention) lines[it].count == 0 else lines[it].count > 0 }
        val have = if (mention) idx.size else idx.sumOf { lines[it].count }
        if (have <= target) return Plan.Keep
        val a = actor?.trim()?.ifEmpty { null }
        val newest = idx.lastOrNull()?.let { lines[it].sender }
        val fromNewest = a != null && newest != null && newest != a && idx.any { lines[it].sender == a }
        var excess = have - target
        val counts = lines.map { it.count }.toMutableList()
        val drop = HashSet<Int>()
        for (i in if (fromNewest) idx.reversed() else idx) {
            if (excess <= 0) break
            if (mention) {
                drop += i
                excess--
            } else {
                val take = minOf(counts[i], excess)
                counts[i] -= take
                excess -= take
                if (counts[i] == 0) drop += i
            }
        }
        val keep = lines.indices.filter { it !in drop }.map { it to counts[it] }
        return if (keep.isEmpty()) Plan.Cancel else Plan.Lines(keep)
    }

    /**
     * Neutrale Mitteilung (ohne Namen: nur "Neue Nachricht"/"n neue Nachrichten" bzw. der Erwaehnungs-Satz).
     * [lastMention] = sie zeigt gerade eine Erwaehnung; eine Aktualisierung der anderen Art laesst sie, wie sie ist.
     */
    fun neutral(lastMention: Boolean, count: Int, kind: String, n: Int): Plan {
        if (isMention(kind) != lastMention) return Plan.Keep
        if (n <= 0) return Plan.Cancel
        if (lastMention) return Plan.Keep
        return if (n == count) Plan.Keep else Plan.Count(n)
    }

    /**
     * Zahlen je Zeile einer Mitteilung aus der Zeit vor diesem Stand (ohne gespeicherte Zahlen): jede Zeile eine
     * Nachricht, die letzte mit der gemerkten Zahl (0 = Erwaehnung).
     */
    fun defaultCounts(size: Int, lastN: Int, lastMention: Boolean): List<Int> =
        List(size) { i -> if (i < size - 1) 1 else if (lastMention) 0 else maxOf(1, lastN) }

    /** Zahlen je Zeile nach einer neuen Nachricht: wie MessagingStyle (gleiche Person ersetzt die letzte Zeile, hoechstens max) */
    fun nextCounts(prev: List<Int>, replaceLast: Boolean, newCount: Int, max: Int): List<Int> =
        (if (replaceLast) prev.dropLast(1) else prev).takeLast(maxOf(0, max - 1)) + newCount
}
