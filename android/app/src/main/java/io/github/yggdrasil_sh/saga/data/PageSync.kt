package io.github.yggdrasil_sh.saga.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * Takt fuer den Abgleich der Seitenliste im Vordergrund. Ohne verbundenen Live-Kanal wie bisher jede Minute; mit
 * Live-Kanal kommen Freigaben, Kommentare, Benachrichtigungen und Neustarts ("reset") ohnehin sofort - dann reicht
 * ein seltener Abgleich fuer im Web/auf anderen Geraeten Geaendertes (beim Zurueckholen der App wird immer abgeglichen).
 */
object RefreshPolicy {
    /** ohne Live-Kanal (wie bisher) */
    const val POLL_MS = 60_000L

    /** mit verbundenem Live-Kanal UND Server mit notes.tree (Sicherheitsnetz) */
    const val POLL_LIVE_MS = 300_000L

    /** Pruefintervall der Schleife (kostet nichts ausser einem Vergleich) */
    const val TICK_MS = 10_000L

    fun now(): Long = System.nanoTime() / 1_000_000

    /** Abgleich faellig? sinceMs = Zeit seit dem letzten Abgleich (halber Tick Toleranz, damit 60 s nicht zu 70 s werden) */
    fun due(sinceMs: Long, liveConnected: Boolean): Boolean =
        sinceMs >= (if (liveConnected) POLL_LIVE_MS else POLL_MS) - TICK_MS / 2

    /** Live-Kanal wieder verbunden (Netzwechsel, Aufwachen): sofort abgleichen, wenn der letzte Abgleich aelter als der 60-s-Takt ist */
    fun staleAfterReconnect(sinceMs: Long): Boolean = sinceMs >= POLL_MS - TICK_MS / 2
}

/**
 * Live-Ereignis `notes.tree {ids, op}` (Server 2.2): Seiten im Baum angelegt/geaendert/verschoben/geloescht/archiviert.
 * Wenige angelegte/geaenderte Seiten werden gezielt nachgeladen (GET api/notes/{id}); alles andere (Verschieben,
 * Papierkorb, Archiv, unbekannte Arten, viele oder keine IDs) per vollem Abgleich. Ereignisse kurz gesammelt (entprellt).
 */
object TreeEvents {
    const val EVENT = "notes.tree"
    const val MIN_SERVER = "2.2.0"
    const val DEBOUNCE_MS = 400L
    const val MAX_TARGETED = 20

    /** Arten, bei denen gezieltes Nachladen der IDs genau den Stand eines Neuladens ergibt */
    private val TARGETED = setOf("created", "create", "new", "changed", "change", "updated", "update", "renamed")

    sealed interface Plan {
        data object Full : Plan
        data class Targeted(val ids: Set<Long>) : Plan
    }

    fun plan(ids: Collection<Long>, ops: Collection<String>): Plan =
        if (ids.isEmpty() || ids.toSet().size > MAX_TARGETED || ops.any { it.lowercase() !in TARGETED }) Plan.Full
        else Plan.Targeted(ids.toSet())

    /** {ids: [int], op: "..."} - tolerant: fehlende/fremde Felder, Zahlen als Text, Zusatzfelder werden ignoriert */
    fun parse(data: JsonObject): Pair<List<Long>, String> {
        val ids = (data["ids"] as? kotlinx.serialization.json.JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.content?.toDoubleOrNull()?.toLong() }
        val op = (data["op"] as? JsonPrimitive)?.content.orEmpty()
        return ids to op
    }
}

/** Seitenliste aufbereiten (reine Funktionen, laufen auf Dispatchers.Default) */
object PageList {
    /** Eintrag ersetzen (Stelle bleibt) bzw. neu ans Ende (neue Seiten: sort = Anlegezeit) */
    fun upsert(list: List<PageMeta>, meta: PageMeta): List<PageMeta> {
        val i = list.indexOfFirst { it.id == meta.id }
        return if (i < 0) list + meta else list.toMutableList().also { it[i] = meta }
    }

    class Split(val active: List<PageMeta>, val archived: List<PageMeta>, val ids: Set<Long>)

    /** GET api/notes -> sichtbare (nicht archivierte) Seiten, archivierte, alle IDs */
    fun split(all: List<PageMeta>): Split {
        val active = ArrayList<PageMeta>(all.size)
        val archived = ArrayList<PageMeta>()
        val ids = HashSet<Long>(all.size * 2)
        for (p in all) {
            if (p.archived) archived.add(p) else active.add(p)
            ids.add(p.id)
        }
        return Split(active, archived, ids)
    }

    /** id und alle Nachfahren (linear ueber eine Map parent_id -> Kinder) */
    fun subtree(all: List<PageMeta>, id: Long): Set<Long> {
        val byParent = HashMap<Long, MutableList<Long>>()
        for (p in all) p.parentId?.let { byParent.getOrPut(it) { ArrayList() }.add(p.id) }
        val out = LinkedHashSet<Long>()
        val stack = ArrayDeque<Long>().apply { add(id) }
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            if (out.add(n)) byParent[n]?.let { stack.addAll(it) }
        }
        return out
    }

    /** Felder, die GET api/notes je Seite liefert (_note_meta + archived, locked, shared, unread) */
    private val LIST_KEYS = setOf("id", "title", "icon", "parent_id", "updated", "tags", "pinned", "template", "kind", "archived", "locked", "shared")

    /**
     * Eintrag der Seitenliste aus GET api/notes/{id} einer EIGENEN Seite - dieselben Werte, die ein Neuladen der Liste
     * zeigen wuerde ("unread" = ungelesene Kommentare von anderen).
     */
    fun metaFromDetail(json: Json, text: String): PageMeta {
        val o = json.parseToJsonElement(text) as JsonObject
        val list = JsonObject(o.filterKeys { it in LIST_KEYS })
        val unread = ((o["comments_unread"] as? JsonPrimitive)?.intOrNull ?: 0) > 0
        return json.decodeFromJsonElement(PageMeta.serializer(), list).copy(unread = unread)
    }
}
