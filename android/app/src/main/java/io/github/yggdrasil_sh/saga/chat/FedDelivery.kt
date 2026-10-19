package io.github.yggdrasil_sh.saga.chat

/**
 * 2.3: Zustellstand eigener Nachrichten in Chats ueber Servergrenzen - "⏳ wird zugestellt" darf nicht haengen bleiben.
 *
 * Ist das Heim des Chats ein anderer Server, legt der eigene Server die Nachricht erst als 'pending' an und meldet sie sofort
 * (chat.message) - die App laedt sie mit ⏳. Bestaetigt das Heim gleich (synchron), meldeten Server bis 2.3.0 das nicht mehr
 * als Ereignis. Daher: (1) Antwort von POST ohne `pending` = zugestellt, auch fuer eine erst danach geladene Kopie;
 * (2) Abgleich: solange eigene Nachrichten warten, ihren Stand beim Nachladen (gedrosselt) beim Server nachsehen - so heilen
 * auch schon haengende Nachrichten von selbst.
 */
object FedDelivery {
    const val PENDING = "pending"
    const val FAILED = "failed"

    /** Abgleich hoechstens so oft (ms), solange eigene Nachrichten warten */
    const val CHECK_EVERY_MS = 15_000L

    /** Antwort von POST /api/chats/{id}/messages: `pending: true` = wartet beim eigenen Server, sonst vom Heim bestaetigt */
    fun confirmedByPost(pending: Boolean): Boolean = !pending

    /** Neuer Zustand einer geladenen Nachricht. server: Stand laut Server (nur wenn fromServer); confirmed: per POST bestaetigt */
    fun state(current: String?, server: String?, fromServer: Boolean, confirmed: Boolean): String? {
        var s = if (fromServer) server?.takeIf { it == PENDING || it == FAILED } else current
        if (s == PENDING && current != PENDING) s = current        // nie zurueck auf "wird zugestellt" (aelterer Stand)
        return if (s == PENDING && confirmed) null else s
    }

    /** Aelteste noch wartende eigene Nachricht (seq) - Abgleich ab dort; null = nichts wartet */
    fun oldestPending(msgs: List<ChatMessage>): Long? = msgs.filter { it.mine && it.fstate == PENDING }.minOfOrNull { it.seq }

    /** Abgleich jetzt? (es wartet etwas und der letzte liegt lange genug zurueck) */
    fun due(msgs: List<ChatMessage>, lastCheck: Long, now: Long): Boolean = oldestPending(msgs) != null && now - lastCheck >= CHECK_EVERY_MS

    /**
     * Wartende eigene Nachrichten, die der Server im abgeglichenen Bereich nicht mehr hat (z. B. vom Heim mit "Empfaenger
     * geaendert" abgewiesen: die App hat neu versiegelt und unter neuer Kennung gesendet) -> Kennungen zum Entfernen.
     * Bereich: ab der aeltesten wartenden bis zur letzten gelieferten (more) bzw. bis zum Ende.
     */
    fun gone(msgs: List<ChatMessage>, server: Map<String, String?>, more: Boolean, maxSeq: Long): Set<String> =
        msgs.filter { it.mine && it.fstate == PENDING && it.id !in server && (!more || it.seq <= maxSeq) }.map { it.id }.toSet()

    /** Stand bekannter Nachrichten aus einer Serverliste (id -> fstate) uebernehmen -> geaenderte (Index, Nachricht) */
    fun apply(msgs: List<ChatMessage>, server: Map<String, String?>, confirmed: Set<String>): List<Pair<Int, ChatMessage>> =
        msgs.mapIndexedNotNull { i, m ->
            if (m.fstate == null && m.id !in server) return@mapIndexedNotNull null
            val n = state(m.fstate, server[m.id], m.id in server, m.id in confirmed)
            if (n != m.fstate) i to m.copy(fstate = n) else null
        }
}
