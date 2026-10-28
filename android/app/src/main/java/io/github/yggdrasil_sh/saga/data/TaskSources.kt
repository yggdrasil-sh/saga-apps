package io.github.yggdrasil_sh.saga.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.io.File

/*
 * 2.5 E2 (tasksrc1, APP-API "Aufgabenquellen"): Datenbank-Zeilen in der Aufgabenliste. items kommen fertig sortiert vom
 * Server (keine eigene Mischlogik), Personen nur als Kennung (Namen aus people, nie Anmeldenamen).
 */

/** Eintrag der zusammengefuehrten Liste (Inbox-Aufgabe "t:<id>" oder Zeile einer Quelle "r:<id>") */
data class TaskEntry(
    val key: String,
    val kind: String,
    val id: Long,
    val src: Long? = null,
    val text: String = "",
    val due: String? = null,
    val time: String? = null,
    val dueEnd: String? = null,
    val prio: Int? = null,
    val done: Boolean = false,
    val overdue: Boolean = false,
    /** false = nur lesen (Haekchen ausgegraut) */
    val edit: Boolean = true,
    val assignees: List<String> = emptyList(),
    val repeat: String? = null,
) {
    val isRow: Boolean get() = kind == "row"
}

/** Abonnierte Quelle (GET /api/tasks?with_sources=1 bzw. /api/tasks/sources) */
data class TaskSource(
    val db: Long,
    val title: String = "",
    val icon: String = "",
    val role: String = "",
    val scope: String = "all",
    val own: Boolean = false,
    val count: Int = 0,
    val truncated: Boolean = false,
    val inactive: Boolean = false,
    val unmapped: Boolean = false,
    val noAssignee: Boolean = false,
    /** Zuordnung (roh); null = keine */
    val map: JsonObject? = null,
) {
    /** Ziel fuer "Neue Aufgabe": mit Zuordnung, aktiv, Rolle owner/edit */
    val canAdd: Boolean get() = map != null && !inactive && !unmapped && (role == "owner" || role == "edit")
    /** scope umschaltbar: nur mit Personen-Eigenschaft */
    val canAssigned: Boolean get() = map?.get("assignee")?.let { it !is JsonNull } == true
}

/** Person aus people */
data class TaskPerson(val name: String, val avatar: String? = null, val me: Boolean = false, val gone: Boolean = false)

data class TasksWithSources(
    val items: List<TaskEntry>,
    val sources: List<TaskSource>,
    val people: Map<String, TaskPerson>,
    /** /api/app/today: weitere Eintraege ueber die 20 hinaus */
    val more: Int = 0,
)

object TaskSourcesLogic {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun s(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
    private fun b(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.booleanOrNull == true
    private fun l(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toDoubleOrNull()?.toLong() }

    fun parseEntry(o: JsonObject): TaskEntry? {
        val key = s(o, "key") ?: return null
        val id = l(o, "id") ?: return null
        return TaskEntry(
            key = key, kind = s(o, "kind") ?: if (key.startsWith("r:")) "row" else "task", id = id, src = l(o, "src"),
            text = s(o, "text").orEmpty(), due = s(o, "due")?.ifEmpty { null }, time = s(o, "time")?.ifEmpty { null },
            dueEnd = s(o, "due_end")?.ifEmpty { null }, prio = (o["prio"] as? JsonPrimitive)?.intOrNull,
            done = b(o, "done"), overdue = b(o, "overdue"),
            edit = (o["edit"] as? JsonPrimitive)?.booleanOrNull ?: true,
            assignees = (o["assignees"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
            repeat = s(o, "repeat"),
        )
    }

    fun parseSource(o: JsonObject): TaskSource? {
        val db = l(o, "db") ?: return null
        return TaskSource(
            db = db, title = s(o, "title").orEmpty(), icon = s(o, "icon").orEmpty(), role = s(o, "role").orEmpty(),
            scope = s(o, "scope") ?: "all", own = b(o, "own"), count = (o["count"] as? JsonPrimitive)?.intOrNull ?: 0,
            truncated = b(o, "truncated"), inactive = b(o, "inactive"), unmapped = b(o, "unmapped"), noAssignee = b(o, "no_assignee"),
            map = o["map"] as? JsonObject,
        )
    }

    fun parsePeople(o: JsonObject?): Map<String, TaskPerson> = o.orEmpty().mapNotNull { (k, v) ->
        val p = v as? JsonObject ?: return@mapNotNull null
        k to TaskPerson(s(p, "name").orEmpty(), s(p, "avatar"), b(p, "me"), b(p, "gone"))
    }.toMap()

    /** Antwort von GET /api/tasks?with_sources=1 bzw. /api/app/today?sources=1; null = Server ohne Quellen (kein items) */
    fun parse(text: String): TasksWithSources? {
        val o = try { json.parseToJsonElement(text) as? JsonObject } catch (_: Exception) { null } ?: return null
        val items = o["items"] as? JsonArray ?: return null
        return TasksWithSources(
            items = items.mapNotNull { (it as? JsonObject)?.let(::parseEntry) },
            sources = (o["sources"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::parseSource) },
            people = parsePeople(o["people"] as? JsonObject),
            more = (o["more"] as? JsonPrimitive)?.intOrNull ?: 0,
        )
    }

    fun parseSources(text: String): List<TaskSource> {
        val o = try { json.parseToJsonElement(text) as? JsonObject } catch (_: Exception) { null } ?: return emptyList()
        return (o["sources"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::parseSource) }
    }

    /** Ziele fuer "Neue Aufgabe": Inbox (null) + Quellen mit Zuordnung, aktiv, Rolle owner/edit */
    fun targets(sources: List<TaskSource>): List<TaskSource> = sources.filter { it.canAdd }

    /** Namen der Zugewiesenen (nie Kennungen/Anmeldenamen zeigen: unbekannte Kennung faellt weg) */
    fun assigneeNames(e: TaskEntry, people: Map<String, TaskPerson>): List<String> = e.assignees.mapNotNull { people[it]?.name?.ifBlank { null } }

    /** Antwort auf Abhaken: prev (Wert der Erledigt-Eigenschaft vorher; null wenn nicht da) */
    fun prevOf(text: String): JsonElement? {
        val o = try { json.parseToJsonElement(text) as? JsonObject } catch (_: Exception) { null } ?: return null
        return if (o.containsKey("prev")) o["prev"] else null
    }

    /** GET /api/notes/{db}/tasksource -> (map gesetzt, schon abonniert) */
    fun sourceState(text: String): Pair<Boolean, Boolean> {
        val o = try { json.parseToJsonElement(text) as? JsonObject } catch (_: Exception) { null } ?: return false to false
        val map = o["map"] as? JsonObject
        val scope = (o["scope"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
        return (map != null) to (scope != null)
    }

    /** Tagesuebersicht (taskdue1) */
    data class Digest(val on: Boolean, val time: String, val tz: String)

    fun parseDigest(text: String): Digest {
        val o = try { json.parseToJsonElement(text) as? JsonObject } catch (_: Exception) { null } ?: return Digest(false, "07:30", "")
        return Digest(b(o, "on"), s(o, "time") ?: "07:30", s(o, "tz").orEmpty())
    }
}

/**
 * Offline-Warteschlange fuer Abhaken/Wieder-oeffnen (APP-API "Offline-Warteschlange (Apps)"):
 * - Schluessel je Eintrag (t:<id> / r:<id>), letzte Aktion gewinnt (abhaken + wieder oeffnen = nichts)
 * - prev (Wert vor dem Abhaken, aus der Antwort) merken und beim Wieder-Oeffnen als value mitschicken - nie pauschal open_value
 * - Nachholen: 403/404 -> verwerfen + neu laden; 400 -> verwerfen + neu laden (nicht wiederholen); Netz/5xx -> behalten
 * - nach jedem erfolgreichen Nachholen neu laden (die Reihenfolge rechnet der Server)
 */
class TaskQueue(
    private val dir: () -> File?,
    /** method, path, body -> Antwort; ApiException bei HTTP-Fehler, andere Exception = Netz */
    private val call: suspend (String, String, JsonElement) -> String,
    private val today: () -> String = { java.time.LocalDate.now().toString() },
) {
    data class Action(val key: String, val done: Boolean, val value: JsonElement? = null)

    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()
    private val _pending = MutableStateFlow<Map<String, Action>>(emptyMap())
    val pending: StateFlow<Map<String, Action>> = _pending
    /** prev je Zeile (aus der Antwort auf das Abhaken) */
    private val prevs = HashMap<String, JsonElement>()
    private var loaded: File? = null

    private fun file(): File? = dir()?.let { File(it, "taskqueue.json") }

    private fun ensure() {
        val f = file()
        if (loaded == f && f != null) return
        loaded = f
        prevs.clear()
        _pending.value = try {
            val o = f?.takeIf { it.isFile }?.readText()?.let { json.parseToJsonElement(it) as? JsonObject }
            (o?.get("prev") as? JsonObject)?.forEach { (k, v) -> prevs[k] = v }
            (o?.get("q") as? JsonObject).orEmpty().mapNotNull { (k, v) ->
                val a = v as? JsonObject ?: return@mapNotNull null
                k to Action(k, (a["done"] as? JsonPrimitive)?.booleanOrNull == true, a["value"])
            }.toMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun persist() {
        val f = file() ?: return
        try {
            f.parentFile?.mkdirs()
            val q = JsonObject(_pending.value.mapValues { (_, a) ->
                JsonObject(buildMap { put("done", JsonPrimitive(a.done)); a.value?.let { put("value", it) } })
            })
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(JsonObject(mapOf("q" to q, "prev" to JsonObject(prevs.toMap()))).toString())
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        } catch (_: Exception) {
        }
    }

    /** Vorher bekannter Wert einer Zeile (fuer Rueckgaengig) */
    suspend fun prevFor(key: String): JsonElement? = lock.withLock { ensure(); prevs[key] }

    /**
     * Aktion einreihen. Letzte Aktion gewinnt: steht das Gegenteil noch aus, heben sich beide auf (-> false = nichts zu
     * senden). Beim Wieder-Oeffnen einer Zeile wird prev als value mitgeschickt.
     */
    suspend fun put(key: String, done: Boolean, value: JsonElement? = null): Boolean = lock.withLock {
        ensure()
        val m = _pending.value.toMutableMap()
        val old = m[key]
        if (old != null && old.done != done) {
            m.remove(key)                                    // abhaken + wieder oeffnen (offline) = nichts
            _pending.value = m
            persist()
            return@withLock false
        }
        val v = if (!done && key.startsWith("r:")) (value ?: prevs[key]) else null
        m[key] = Action(key, done, v)
        _pending.value = m
        persist()
        true
    }

    enum class Outcome { SENT, KEPT, DROPPED }

    data class FlushResult(val sent: Int, val dropped: Int, val kept: Int, val lastPrev: Map<String, JsonElement?>) {
        /** Liste neu laden: nach jedem Erfolg und nach jedem Verwerfen */
        val reload: Boolean get() = sent > 0 || dropped > 0
    }

    /** Alles nachholen; Netzfehler/5xx behalten den Eintrag */
    suspend fun flush(): FlushResult {
        val list = lock.withLock { ensure(); _pending.value.values.toList() }
        var sent = 0
        var dropped = 0
        var kept = 0
        val got = HashMap<String, JsonElement?>()
        for (a in list) {
            when (val r = send(a)) {
                is Sent -> { sent++; got[a.key] = r.prev }
                Kept -> kept++
                Dropped -> dropped++
            }
        }
        return FlushResult(sent, dropped, kept, got)
    }

    private sealed interface Res
    private class Sent(val prev: JsonElement?) : Res
    private data object Kept : Res
    private data object Dropped : Res

    private suspend fun send(a: Action): Res {
        val id = a.key.substringAfter(':')
        val (path, body) = if (a.key.startsWith("r:")) {
            "api/tasks/src/$id" to JsonObject(buildMap { put("done", JsonPrimitive(a.done)); if (!a.done && a.value != null) put("value", a.value) })
        } else {
            "api/tasks/$id" to JsonObject(mapOf("done" to JsonPrimitive(a.done), "today" to JsonPrimitive(today())))
        }
        val text = try {
            call("PATCH", path, body)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            return if (e.code == 401 || e.code == 408 || e.code == 429 || e.code >= 500) Kept else { done(a, null); Dropped }
        } catch (_: Exception) {
            return Kept
        }
        val prev = if (a.done && a.key.startsWith("r:")) TaskSourcesLogic.prevOf(text) else null
        done(a, prev)
        return Sent(prev)
    }

    /** Gesendet/verworfen: nur entfernen, wenn inzwischen nichts Neues eingereiht wurde; prev merken bzw. vergessen */
    private suspend fun done(a: Action, prev: JsonElement?) = lock.withLock {
        ensure()
        val m = _pending.value.toMutableMap()
        if (m[a.key] == a) m.remove(a.key)
        _pending.value = m
        if (a.key.startsWith("r:")) {
            if (a.done && prev != null) prevs[a.key] = prev else if (!a.done) prevs.remove(a.key)
        }
        persist()
    }

    /** Abmelden/Kontowechsel: Datei gehoert zum Konto-Ordner, Speicher verwerfen */
    fun reset() {
        loaded = null
        prevs.clear()
        _pending.value = emptyMap()
    }
}
