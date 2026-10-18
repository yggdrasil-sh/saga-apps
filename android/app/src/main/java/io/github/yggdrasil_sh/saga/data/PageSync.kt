package io.github.yggdrasil_sh.saga.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Takt fuer den Abgleich der Seitenliste im Vordergrund. Ohne verbundenen Live-Kanal wie bisher jede Minute. Mit
 * Live-Kanal nur dann seltener (5 min, Sicherheitsnetz), wenn in dieser Anmeldung schon ein `notes.tree` kam - der Server
 * meldet Baum-Aenderungen dann selbst (die Serverversion allein reicht nicht: testing-Server melden schon 2.2.0, schicken
 * aber evtl. noch kein notes.tree). Die kleinen Listen (Mit mir geteilt, andere Server, Glocke, Chats, Heute) werden
 * unabhaengig davon weiter jede Minute abgeglichen (lightDue) - Gruppenwechsel/Verschieben aus geteilten Baeumen loesen
 * serverseitig nicht immer Ereignisse aus.
 */
object RefreshPolicy {
    /** ohne Live-Kanal bzw. noch kein notes.tree (wie bisher) */
    const val POLL_MS = 60_000L

    /** mit verbundenem Live-Kanal UND schon empfangenem notes.tree (Sicherheitsnetz) */
    const val POLL_LIVE_MS = 300_000L

    /** Pruefintervall der Schleife (kostet nichts ausser einem Vergleich) */
    const val TICK_MS = 10_000L

    fun now(): Long = System.nanoTime() / 1_000_000

    /**
     * Voller Abgleich faellig? sinceMs = Zeit seit dem letzten Abgleich (halber Tick Toleranz, damit 60 s nicht zu 70 s
     * werden); treeSeen = in dieser Anmeldung schon ein notes.tree empfangen.
     */
    fun due(sinceMs: Long, liveConnected: Boolean, treeSeen: Boolean): Boolean =
        sinceMs >= (if (liveConnected && treeSeen) POLL_LIVE_MS else POLL_MS) - TICK_MS / 2

    /** Kleine Listen (geteilt, Glocke, Chats, Heute) faellig? sinceMs = seit dem letzten vollen ODER kleinen Abgleich */
    fun lightDue(sinceMs: Long): Boolean = sinceMs >= POLL_MS - TICK_MS / 2

    /** Live-Kanal wieder verbunden (Netzwechsel, Aufwachen): sofort abgleichen, wenn der letzte Abgleich aelter als der 60-s-Takt ist */
    fun staleAfterReconnect(sinceMs: Long): Boolean = sinceMs >= POLL_MS - TICK_MS / 2
}

/**
 * Live-Ereignis `notes.tree {ids:[int], op, ops:[...], full:bool}` (docs/APP-API-LIVE.md): Seiten im Baum angelegt/
 * geaendert/verschoben/geloescht/archiviert/geteilt. `full: true` (zu viele Seiten) -> voller Abgleich. Wenige
 * angelegte/geaenderte Seiten werden gezielt nachgeladen (GET api/notes/{id}); alles andere (moved, trashed, restored,
 * archived, deleted, shared, mixed mit solchen Arten, unbekannte Arten, viele oder keine IDs) per vollem Abgleich.
 * Ereignisse kurz gesammelt (entprellt).
 */
object TreeEvents {
    const val EVENT = "notes.tree"
    const val DEBOUNCE_MS = 400L
    const val MAX_TARGETED = 20

    /** Arten, bei denen gezieltes Nachladen der IDs genau den Stand eines Neuladens ergibt */
    private val TARGETED = setOf("created", "updated")

    /** Pseudo-Art fuer "ganzen Baum neu laden" (full: true, keine IDs) */
    const val FULL = "*full"

    sealed interface Plan {
        data object Full : Plan
        data class Targeted(val ids: Set<Long>) : Plan
    }

    fun plan(ids: Collection<Long>, ops: Collection<String>): Plan =
        if (ids.isEmpty() || ids.toSet().size > MAX_TARGETED || ops.isEmpty() || ops.any { it.lowercase() !in TARGETED }) Plan.Full
        else Plan.Targeted(ids.toSet())

    data class Event(val ids: List<Long>, val ops: Set<String>, val full: Boolean)

    /**
     * {ids, op, ops, full} - tolerant: fehlende/fremde Felder, Zahlen als Text, Zusatzfelder werden ignoriert.
     * Arten = ops (falls vorhanden) plus op (ausser "mixed", das nur auf ops verweist); ohne beides -> "" (unbekannt).
     * full = true -> ops enthaelt FULL.
     */
    fun parse(data: JsonObject): Event {
        val ids = (data["ids"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.content?.toDoubleOrNull()?.toLong() }
        val op = (data["op"] as? JsonPrimitive)?.content.orEmpty()
        val list = (data["ops"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }
        val full = (data["full"] as? JsonPrimitive)?.booleanOrNull == true
        val ops = LinkedHashSet<String>()
        ops.addAll(list)
        if (op != "mixed" || list.isEmpty()) ops.add(op)
        if (full) ops.add(FULL)
        return Event(ids, ops, full)
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
