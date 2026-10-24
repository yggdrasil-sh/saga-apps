package io.github.yggdrasil_sh.saga.data

import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.util.UiText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/*
 * Saga 2.4 (Android): Seiten verschieben, Papierkorb, Termine anlegen/bearbeiten/loeschen, eigene Lesezeichen.
 * Gleiche Server-Endpunkte wie Web und iOS (keine neuen):
 *  POST api/notes/{id}/move {parent_id, index}           GET api/trash, POST api/trash/{id}/restore,
 *  DELETE api/trash/{id}, POST api/trash/empty            GET api/settings/calendars,
 *  POST/PATCH api/calendar/events, POST api/calendar/events/delete   POST/PATCH/DELETE api/bookmarks[/{id}]
 */

// ---------------------------------------------------------------- Papierkorb

/** Eintrag im Papierkorb (oberste geloeschte Seite eines Loeschvorgangs) */
@Serializable
data class TrashItem(
    val id: Long,
    val title: String = "",
    val icon: String? = null,
    val kind: String? = null,
    @SerialName("deleted_at") val deletedAt: Double = 0.0,
    val subpages: Int = 0,
    @SerialName("purge_at") val purgeAt: Double? = null,
)

/** Zustand der Papierkorb-Ansicht (ohne Compose testbar) */
data class TrashState(
    val items: List<TrashItem>? = null,
    val busy: Set<Long> = emptySet(),
    val error: UiText? = null,
) {
    val loading: Boolean get() = items == null && error == null
    val empty: Boolean get() = items?.isEmpty() == true

    fun loaded(list: List<TrashItem>) = copy(items = list.sortedByDescending { it.deletedAt }, error = null, busy = emptySet())
    fun failed(e: UiText) = copy(error = e, busy = emptySet())
    fun working(id: Long) = copy(busy = busy + id)
    /** wiederhergestellt oder endgueltig geloescht: Eintrag weg */
    fun removed(id: Long) = copy(items = items?.filter { it.id != id }, busy = busy - id)
    fun idle(id: Long) = copy(busy = busy - id)
    fun emptied() = copy(items = emptyList(), busy = emptySet())

    companion object {
        /**
         * Volle Tage bis zum endgueltigen Loeschen (abgerundet, mindestens 1 solange noch Zeit ist, nie negativ); null =
         * Server nennt keinen Termin. Nr. 21: aufgerundet stand nach 22 Stunden noch "in 30 Tagen".
         */
        fun daysLeft(item: TrashItem, nowSec: Double): Int? {
            val p = item.purgeAt ?: return null
            val left = (p - nowSec) / 86400.0
            return if (left <= 0) 0 else maxOf(1, kotlin.math.floor(left).toInt())
        }
    }
}

// ---------------------------------------------------------------- Verschieben

/**
 * Wohin darf eine Seite verschoben werden? Regeln wie der Server (pages.note_move):
 *  - Eigentuemer: frei innerhalb der eigenen Seiten, auch auf die oberste Ebene; nie in sich selbst oder eine eigene
 *    Unterseite, nie in eine Datenbank (deren Zeilen sind keine Unterseiten)
 *  - Bearbeiten (geteilte Seite eines anderen): nur innerhalb desselben geteilten Baums (gleicher Eigentuemer, Ziel
 *    ebenfalls bearbeitbar), nicht auf die oberste Ebene und nicht die oberste geteilte Seite selbst. Ob die Freigaben
 *    ueber Ziel und Herkunft gleich sind, prueft der Server (Meldung wird angezeigt).
 *  - Seiten anderer Saga-Instanzen (Foederation) und Datenbank-Zeilen: in der App nicht.
 */
object MoveLogic {
    /** Ziel: null = oberste Ebene */
    data class Target(val id: Long?, val page: PageMeta?, val depth: Int, val current: Boolean)

    fun canMove(p: PageMeta, all: List<PageMeta>): Boolean {
        if (p.isRemote) return false
        val parent = p.parentId?.let { pid -> all.firstOrNull { it.id == pid } }
        if (parent?.isDatabase == true) return false                     // Datenbank-Zeile
        if (!p.foreign) return true
        return p.role == "edit" && p.parentId != null && !p.root
    }

    /** IDs der Seite und aller Unterseiten */
    fun subtree(id: Long, all: List<PageMeta>): Set<Long> {
        val kids = all.groupBy { it.parentId }
        val out = LinkedHashSet<Long>()
        val stack = ArrayDeque(listOf(id))
        while (stack.isNotEmpty()) {
            val x = stack.removeLast()
            if (out.add(x)) kids[x]?.forEach { stack.addLast(it.id) }
        }
        return out
    }

    /**
     * Ziele als eingerueckte Liste (Baum-Reihenfolge). own = eigene Seiten, shared = "Mit mir geteilt" (dieser Server).
     */
    fun targets(p: PageMeta, own: List<PageMeta>, shared: List<PageMeta>): List<Target> {
        val foreign = p.foreign
        val pool = if (!foreign) own.filter { !it.archived } else shared.filter { it.owner?.username == p.owner?.username && !it.isRemote }
        if (!canMove(p, if (foreign) shared else own)) return emptyList()
        val blocked = subtree(p.id, pool)
        val ids = pool.map { it.id }.toSet()
        val kids = pool.groupBy { it.parentId?.takeIf { pid -> pid in ids } }
        val out = ArrayList<Target>()
        if (!foreign) out.add(Target(null, null, 0, p.parentId == null))
        fun walk(parent: Long?, depth: Int) {
            for (c in kids[parent].orEmpty().sortedBy { it.title.lowercase() }) {
                if (c.id in blocked) continue
                val ok = !c.isDatabase && (!foreign || c.role == "edit" || c.role == "owner")
                if (ok) out.add(Target(c.id, c, depth, c.id == p.parentId))
                if (!c.isDatabase && depth < 12) walk(c.id, depth + 1)
            }
        }
        walk(null, if (foreign) 0 else 1)
        return out
    }

    /** Suche in den Zielen (Titel); leer = alle */
    fun filter(list: List<Target>, q: String, untitled: String): List<Target> {
        val t = q.trim().lowercase()
        if (t.isEmpty()) return list
        return list.filter { (it.page?.title?.ifEmpty { untitled } ?: "").lowercase().contains(t) }
    }
}

// ---------------------------------------------------------------- Termine

@Serializable
data class CalInfo(val href: String = "", val name: String = "", val color: String? = null, val show: Boolean = true, val writable: Boolean = true)

@Serializable
data class CalAccount(val id: Int, val label: String = "", val username: String = "", val calendars: List<CalInfo> = emptyList())

@Serializable
data class CalAccounts(val accounts: List<CalAccount> = emptyList())

/** Beschreibbarer, angezeigter Kalender (Auswahl im Termin-Dialog) */
data class CalTarget(val account: Int, val cal: String, val name: String)

/**
 * Termin-Formular (wie der Termin-Dialog im Web): ganztaegig -> Datum, Ende inklusive angezeigt und exklusiv
 * gesendet; sonst lokale Zeit mit Zeitzone (der Server verlangt eine Zeitzone).
 */
data class EventForm(
    val summary: String = "",
    val allday: Boolean = false,
    val startDate: LocalDate = LocalDate.now(),
    val startTime: LocalTime = LocalTime.of(9, 0),
    val endDate: LocalDate = LocalDate.now(),
    val endTime: LocalTime = LocalTime.of(10, 0),
    val location: String = "",
    val description: String = "",
    val target: Int = 0,
) {
    /** Ende vor Beginn? (dann nachziehen wie im Web) */
    fun normalized(): EventForm {
        if (allday) return if (endDate < startDate) copy(endDate = startDate) else this
        val s = LocalDateTime.of(startDate, startTime)
        val e = LocalDateTime.of(endDate, endTime)
        if (!e.isAfter(s)) {
            val ne = s.plusHours(1)
            return copy(endDate = ne.toLocalDate(), endTime = ne.toLocalTime())
        }
        return this
    }

    fun payload(fallbackTitle: String, zone: ZoneId = ZoneId.systemDefault()): JsonObject {
        val f = normalized()
        return buildJsonObject {
            put("summary", f.summary.trim().ifEmpty { fallbackTitle })
            put("allday", f.allday)
            put("location", f.location.trim())
            put("description", f.description.trim())
            if (f.allday) {
                put("start", f.startDate.toString())
                put("end", f.endDate.plusDays(1).toString())             // exklusiv (iCalendar)
            } else {
                put("start", LocalDateTime.of(f.startDate, f.startTime).atZone(zone).toOffsetDateTime().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
                put("end", LocalDateTime.of(f.endDate, f.endTime).atZone(zone).toOffsetDateTime().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
            }
        }
    }

    companion object {
        /** Neuer Termin an einem Tag (Standard 9-10 Uhr) */
        fun forDay(d: LocalDate) = EventForm(startDate = d, endDate = d)

        /** Vorhandenen Termin ins Formular (Ende ganztaegig inklusive) */
        fun of(e: CalEvent, zone: ZoneId = ZoneId.systemDefault()): EventForm {
            if (e.allday) {
                val s = runCatching { LocalDate.parse(e.start.take(10)) }.getOrElse { LocalDate.now() }
                val en = runCatching { LocalDate.parse(e.end.take(10)).minusDays(1) }.getOrElse { s }
                return EventForm(e.summary, true, s, LocalTime.of(9, 0), if (en < s) s else en, LocalTime.of(10, 0), e.location.orEmpty(), e.description.orEmpty())
            }
            val s = parseLocal(e.start, zone) ?: LocalDateTime.now()
            val en = parseLocal(e.end, zone) ?: s.plusHours(1)
            return EventForm(e.summary, false, s.toLocalDate(), s.toLocalTime(), en.toLocalDate(), en.toLocalTime(), e.location.orEmpty(), e.description.orEmpty())
        }

        fun parseLocal(iso: String, zone: ZoneId): LocalDateTime? = runCatching {
            OffsetDateTime.parse(iso).atZoneSameInstant(zone).toLocalDateTime()
        }.getOrNull() ?: runCatching { LocalDateTime.parse(iso) }.getOrNull()

        /** Serientermine und nur lesbare Kalender: nur ansehen (wie im Web) */
        fun readOnly(e: CalEvent): Boolean = e.series == true || e.writable == false || e.href.isNullOrEmpty() || e.account == null

        fun targets(accs: List<CalAccount>): List<CalTarget> = accs.flatMap { a ->
            a.calendars.filter { it.show && it.writable }.map { c -> CalTarget(a.id, c.href, (a.label.ifEmpty { a.username }) + " · " + c.name) }
        }
    }
}

// ---------------------------------------------------------------- Lesezeichen

/** Eigenes Lesezeichen: ID aus dem Schluessel "bm{id}" (GET api/services, own=true) */
object BookmarkLogic {
    fun idOf(s: ServiceItem): Long? = if (s.own) s.key.removePrefix("bm").toLongOrNull() else null

    fun payload(name: String, url: String, category: String, icon: String): JsonObject = buildJsonObject {
        put("name", name.trim())
        put("url", url.trim())
        put("category", category.trim())
        put("icon", icon.trim().lowercase())
    }

    /** Vorpruefung wie der Server (_bm_input): http(s), Host; ohne Schema https:// */
    fun validUrl(u: String): Boolean {
        val t = u.trim()
        if (t.isEmpty() || t.any { it.isWhitespace() }) return false
        val full = if ("://" in t) t else "https://$t"
        val scheme = full.substringBefore("://").lowercase()
        val host = full.substringAfter("://").substringBefore('/').substringBefore('?')
        return (scheme == "http" || scheme == "https") && host.isNotEmpty() && '@' !in host && portOk(host)
    }

    /** "host:port" nur mit Ziffern als Port ("https://javascript:alert(1)" lehnt auch der Server ab) */
    fun portOk(host: String): Boolean {
        if (host.startsWith("[")) return true                          // IPv6
        if (':' !in host) return true
        val port = host.substringAfterLast(':')
        return port.isNotEmpty() && port.all { it.isDigit() }
    }
}

// ---------------------------------------------------------------- Server-Aufrufe

private suspend inline fun <reified T> SagaRepository.dec(s: String): T = withContext(Dispatchers.Default) { json.decodeFromString<T>(s) }

/** Seite verschieben (parent null = oberste Ebene), danach Baum abgleichen */
suspend fun SagaRepository.movePage(id: Long, parent: Long?, index: Int = 0) {
    api.post("api/notes/$id/move", buildJsonObject {
        if (parent == null) put("parent_id", JsonNull) else put("parent_id", parent)
        put("index", index)
    })
    refresh(join = false)
    if (sharedPages.value.any { it.id == id }) loadShared()
}

suspend fun SagaRepository.trashList(): List<TrashItem> = dec(api.get("api/trash"))

suspend fun SagaRepository.trashRestore(id: Long) = restorePage(id)

suspend fun SagaRepository.trashPurge(id: Long) {
    api.delete("api/trash/$id")
}

suspend fun SagaRepository.trashEmpty() {
    api.post("api/trash/empty")
}

suspend fun SagaRepository.calendarAccounts(): List<CalAccount> = dec<CalAccounts>(api.get("api/settings/calendars")).accounts

/** Termin anlegen (event == null) oder aendern */
suspend fun SagaRepository.saveEvent(form: EventForm, target: CalTarget?, event: CalEvent?, fallbackTitle: String) {
    val p = LinkedHashMap(form.payload(fallbackTitle))
    if (event != null) {
        p["account"] = JsonPrimitive(event.account ?: 0)
        p["cal"] = JsonPrimitive(event.cal.orEmpty())
        p["href"] = JsonPrimitive(event.href.orEmpty())
        p["etag"] = JsonPrimitive(event.etag.orEmpty())
        event.uid?.let { p["uid"] = JsonPrimitive(it) }
        api.patch("api/calendar/events", JsonObject(p))
    } else {
        val t = target ?: throw ApiException(400, UiText.Res(R.string.ev_no_calendar))
        p["account"] = JsonPrimitive(t.account)
        p["cal"] = JsonPrimitive(t.cal)
        api.post("api/calendar/events", JsonObject(p))
    }
    loadTodayEvents()
}

suspend fun SagaRepository.deleteEvent(e: CalEvent) {
    api.post("api/calendar/events/delete", buildJsonObject {
        put("account", e.account ?: 0)
        put("cal", e.cal.orEmpty())
        put("href", e.href.orEmpty())
        put("etag", e.etag.orEmpty())
    })
    loadTodayEvents()
}

suspend fun SagaRepository.addBookmark(body: JsonObject) {
    api.post("api/bookmarks", body)
}

suspend fun SagaRepository.updateBookmark(id: Long, body: JsonObject) {
    api.patch("api/bookmarks/$id", body)
}

suspend fun SagaRepository.deleteBookmark(id: Long) {
    api.delete("api/bookmarks/$id")
}

/** Datei fuer einen Datei-Block hochladen (POST api/files, Feld "file") -> "file"-Objekt der Antwort */
suspend fun SagaRepository.uploadPageFile(data: ByteArray, name: String, mime: String, note: Long?): JsonObject {
    val r = api.postMultipart("api/files", "file", name, mime, data, note)
    val o = withContext(Dispatchers.Default) { json.parseToJsonElement(r) as? JsonObject }
    val f = o?.get("file") as? JsonObject
    if (f == null || (f["url"] as? JsonPrimitive)?.content.isNullOrBlank()) throw ApiException(500, UiText.Res(R.string.image_upload_failed))
    return f
}

/** Foto aus Immich als eigene Datei uebernehmen -> /files/... */
suspend fun SagaRepository.immichImport(assetId: String, note: Long?): String {
    val r = api.post("api/immich/import", buildJsonObject {
        put("id", assetId)
        if (note != null) put("note", note)
    })
    val o = withContext(Dispatchers.Default) { json.parseToJsonElement(r) as? JsonObject }
    return (o?.get("url") as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: throw ApiException(500, UiText.Res(R.string.image_upload_failed))
}

/** Neue Datenbank als Unterseite (wie "/Datenbank" in Web/iOS) -> ID */
suspend fun SagaRepository.newDatabase(parent: Long, title: String): Long {
    val r = api.post("api/notes", buildJsonObject {
        put("kind", "database")
        put("title", title)
        put("icon", "🗃️")
        put("parent_id", parent)
    })
    val id = dec<NewId>(r).id
    refresh(join = false)
    return id
}
