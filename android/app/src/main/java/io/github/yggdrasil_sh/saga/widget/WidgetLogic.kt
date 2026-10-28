package io.github.yggdrasil_sh.saga.widget

import io.github.yggdrasil_sh.saga.blocks.BlockDoc
import io.github.yggdrasil_sh.saga.blocks.InlineHtml
import io.github.yggdrasil_sh.saga.data.CalEvent
import io.github.yggdrasil_sh.saga.data.TaskItem
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/*
 * Homescreen-Widgets (Android): reine Logik ohne Android-Abhaengigkeiten - Auswahl und Sortierung fuer "Heute",
 * Kuerzen des Seiteninhalts und die Zustaende (nicht angemeldet, laedt, offline, leer). Unit-Tests: WidgetLogicTest.
 */

/** Aufgabe im Widget-Zwischenspeicher (nur was das Widget zeigt) */
@Serializable
data class WTask(
    val id: Int, val text: String, val due: String? = null, val prio: Int? = null,
    /** 2.5 E2 (tasksrc1): Zeile einer Aufgabenquelle (id = Zeilen-ID), Quelle (Symbol/Titel), Uhrzeit, nur lesen */
    val row: Boolean = false, val srcIcon: String? = null, val srcTitle: String? = null, val time: String? = null,
    val edit: Boolean = true,
) {
    /** Schluessel fuer "gerade abgehakt": Zeilen negativ (Inbox-IDs und Zeilen-IDs koennen sich ueberschneiden) */
    val hideKey: Int get() = if (row) -id else id
}

/** Termin im Widget-Zwischenspeicher */
@Serializable
data class WEvent(
    val summary: String,
    val start: String,
    val end: String = "",
    val allday: Boolean = false,
    val color: String? = null,
    val location: String? = null,
)

/**
 * Zuletzt bekannter Stand fuer "Heute". day = Tag des Abrufs; Aufgaben bis einschliesslich day+1 und Termine fuer
 * day und day+1, damit das Widget auch offline nach Mitternacht noch Richtiges zeigt (gefiltert wird beim Anzeigen).
 */
@Serializable
data class TodaySnapshot(
    val day: String,
    val fetchedAt: Long,
    val tasks: List<WTask> = emptyList(),
    val events: List<WEvent> = emptyList(),
    /** Kalender war nicht lesbar (Aufgaben trotzdem da) */
    val eventsFailed: Boolean = false,
    /** 2.5 E2: Aufgaben fertig sortiert vom Server (mit Aufgabenquellen) - Reihenfolge nicht aendern */
    val ordered: Boolean = false,
)

/** Zuletzt bekannter Stand einer Seite */
@Serializable
data class PageSnapshot(
    val id: Long,
    val title: String,
    val icon: String? = null,
    val excerpt: String = "",
    val isDatabase: Boolean = false,
    val fetchedAt: Long = 0,
    /** Seite geloescht oder kein Zugriff mehr */
    val missing: Boolean = false,
)

/** Termin-Zeile fuer heute: allday = ganztaegig (keine Uhrzeit), sonst Startzeit in lokaler Zeit */
data class EventLine(val event: WEvent, val allday: Boolean, val start: ZonedDateTime?)

/** Aufgaben-Zeile fuer heute */
data class TaskLine(val task: WTask, val overdue: Boolean)

object TodayLogic {
    /** hoechste Prioritaet = 1; ohne Angabe wie "normal" (2, so legt die App neue Aufgaben an) */
    private fun prio(t: WTask) = t.prio ?: 2

    /** Aufgaben fuer den Zwischenspeicher: offen, ohne Datum oder mit Datum bis einschliesslich [until] */
    fun forSnapshot(open: List<TaskItem>, until: LocalDate): List<WTask> =
        open.asSequence()
            .filter { !it.done }
            .filter { t -> t.due.isNullOrBlank() || parseDay(t.due)?.let { !it.isAfter(until) } ?: false }
            .map { WTask(it.id, it.text, it.due, it.prio) }
            .toList()

    /** 2.5 E2: Eintraege aus /api/app/today?sources=1 (bzw. with_sources) fuer den Zwischenspeicher - Reihenfolge bleibt */
    fun forSnapshot(l: io.github.yggdrasil_sh.saga.data.TasksWithSources, until: LocalDate): List<WTask> {
        val src = l.sources.associateBy { it.db }
        return l.items.asSequence()
            .filter { !it.done }
            .filter { t -> t.due.isNullOrBlank() || parseDay(t.due)?.let { !it.isAfter(until) } ?: false }
            .map { e ->
                val s = e.src?.let { src[it] }
                WTask(e.id.toInt(), e.text, e.due, e.prio, row = e.isRow, srcIcon = s?.icon?.ifEmpty { null }, srcTitle = s?.title, time = e.time, edit = e.edit)
            }
            .toList()
    }

    /**
     * Aufgaben fuer "Heute" wie im iOS-Widget und in der Ansicht "Heute" der App: faellig heute, ueberfaellig oder
     * ohne Datum (spaetere nicht, ein unlesbares Datum auch nicht).
     * Reihenfolge: aeltestes Faelligkeitsdatum zuerst (ueberfaellige also vorn), ohne Datum zuletzt, dann Prioritaet, dann ID.
     * hidden = gerade im Widget abgehakt (der Server bestaetigt noch).
     */
    fun tasks(all: List<WTask>, today: LocalDate, hidden: Set<Int> = emptySet(), ordered: Boolean = false): List<TaskLine> =
        if (ordered) all.mapNotNull { t ->                     // 2.5 E2: Reihenfolge vom Server, nur heute/ueberfaellig/ohne Datum
            if (t.hideKey in hidden) return@mapNotNull null
            if (t.due.isNullOrBlank()) return@mapNotNull TaskLine(t, false)
            val d = parseDay(t.due) ?: return@mapNotNull null
            if (d.isAfter(today)) null else TaskLine(t, d.isBefore(today))
        } else all.mapNotNull { t ->
            if (t.hideKey in hidden) return@mapNotNull null
            if (t.due.isNullOrBlank()) return@mapNotNull Triple(t, LocalDate.MAX, false)
            val d = parseDay(t.due) ?: return@mapNotNull null
            if (d.isAfter(today)) null else Triple(t, d, d.isBefore(today))
        }
            .sortedWith(compareBy<Triple<WTask, LocalDate, Boolean>> { it.second }.thenBy { prio(it.first) }.thenBy { it.first.id })
            .map { TaskLine(it.first, it.third) }

    /**
     * Termine von heute: ganztaegige, die heute einschliessen (Ende exklusiv wie im Kalender), und zeitgebundene, die
     * heute stattfinden (auch wenn sie gestern begonnen haben). Ganztaegige zuerst, dann nach Beginn.
     */
    fun events(all: List<WEvent>, today: LocalDate, zone: ZoneId): List<EventLine> {
        val dayStart = today.atStartOfDay(zone)
        val dayEnd = today.plusDays(1).atStartOfDay(zone)
        val out = all.mapNotNull { e ->
            if (e.allday) {
                val s = parseDay(e.start) ?: return@mapNotNull null
                val en = parseDay(e.end)?.takeIf { it.isAfter(s) } ?: s.plusDays(1)
                if (!s.isAfter(today) && today.isBefore(en)) EventLine(e, true, null) else null
            } else {
                val s = parseTime(e.start, zone) ?: return@mapNotNull null
                val en = parseTime(e.end, zone)?.takeIf { !it.isBefore(s) } ?: s
                val inside = s.isBefore(dayEnd) && (en.isAfter(dayStart) || (en == s && !s.isBefore(dayStart)))
                if (inside) EventLine(e, false, s) else null
            }
        }
        return out.sortedWith(compareBy<EventLine> { if (it.allday) 0 else 1 }.thenBy { it.start?.toInstant() }.thenBy { it.event.summary })
    }

    fun parseDay(s: String?): LocalDate? = try {
        if (s.isNullOrBlank()) null else LocalDate.parse(s.take(10))
    } catch (_: Exception) {
        null
    }

    fun parseTime(s: String?, zone: ZoneId): ZonedDateTime? {
        if (s.isNullOrBlank()) return null
        return try {
            OffsetDateTime.parse(s).atZoneSameInstant(zone)
        } catch (_: Exception) {
            try {
                LocalDateTime.parse(s).atZone(zone)
            } catch (_: Exception) {
                null
            }
        }
    }
}

/** Was das Widget gerade zeigen soll */
sealed interface WidgetUi {
    /** Kein Konto auf dem Geraet (oder abgemeldet) */
    data object SignedOut : WidgetUi
    /** Angemeldet, aber noch nie geladen */
    data object Loading : WidgetUi
    /** Seiten-Widget ohne gewaehlte Seite */
    data object NotConfigured : WidgetUi
    /** Seite geloescht / kein Zugriff */
    data object Missing : WidgetUi
    /**
     * Inhalt. staleSince = Zeitpunkt des Stands, wenn er nicht frisch ist (offline bzw. letzter Abruf fehlgeschlagen
     * oder aelter als [WidgetStates.STALE_MS]); sonst null. empty = nichts anzuzeigen.
     */
    data class Content(val staleSince: Long?, val empty: Boolean, val offline: Boolean) : WidgetUi
}

object WidgetStates {
    /** Ab diesem Alter zeigt das Widget den Zeitstempel auch ohne Fehler ("Stand 14:30") */
    const val STALE_MS = 2 * 60 * 60 * 1000L

    private fun stale(fetchedAt: Long, failedAt: Long?, now: Long): Pair<Long?, Boolean> {
        val offline = failedAt != null && failedAt >= fetchedAt
        return (if (offline || now - fetchedAt > STALE_MS) fetchedAt else null) to offline
    }

    fun today(signedOut: Boolean, snap: TodaySnapshot?, failedAt: Long?, today: LocalDate, zone: ZoneId, now: Long, hidden: Set<Int> = emptySet()): WidgetUi {
        if (signedOut) return WidgetUi.SignedOut
        snap ?: return WidgetUi.Loading
        val empty = TodayLogic.tasks(snap.tasks, today, hidden, snap.ordered).isEmpty() && TodayLogic.events(snap.events, today, zone).isEmpty()
        val (since, offline) = stale(snap.fetchedAt, failedAt, now)
        return WidgetUi.Content(since, empty, offline)
    }

    fun page(signedOut: Boolean, pageId: Long?, snap: PageSnapshot?, failedAt: Long?, now: Long): WidgetUi {
        if (signedOut) return WidgetUi.SignedOut
        pageId ?: return WidgetUi.NotConfigured
        snap ?: return WidgetUi.Loading
        if (snap.missing) return WidgetUi.Missing
        val (since, offline) = stale(snap.fetchedAt, failedAt, now)
        return WidgetUi.Content(since, snap.excerpt.isBlank(), offline)
    }
}

/** Anfang des Seiteninhalts als schlichter Text */
object PageExcerpt {
    const val MAX = 600

    /** Symbol nur, wenn es ein Emoji ist (eigene Bilder laedt das Widget nicht) */
    fun emoji(icon: String?): String? = icon?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("/") && !it.contains("://") && it.codePointCount(0, it.length) <= 4 }

    /** Inhalt (Server-Feld body + format) -> Text; unlesbar -> "" */
    fun fromBody(body: String?, format: String?, max: Int = MAX): String {
        val doc = try {
            BlockDoc.parse(body, format?.ifEmpty { null } ?: "text").doc
        } catch (_: Exception) {
            return ""
        }
        val lines = ArrayList<String>()
        for (b in BlockDoc.blocks(doc)) {
            collect(b, lines)
            if (lines.sumOf { it.length + 1 } > max * 2) break
        }
        return truncate(lines.joinToString("\n"), max)
    }

    private fun collect(b: JsonElement, out: MutableList<String>) {
        val d = BlockDoc.data(b)
        when (BlockDoc.type(b)) {
            "paragraph", "header", "quote", "callout" -> add(out, BlockDoc.str(d, "text"))
            "toggle" -> add(out, BlockDoc.str(d, "title"))
            "code" -> add(out, BlockDoc.str(d, "code"), html = false)
            "list", "checklist" -> items(d["items"], out)
            "table" -> (d["content"] as? JsonArray)?.forEach { row ->
                (row as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content?.let(::plain) }?.filter { it.isNotBlank() }
                    ?.takeIf { it.isNotEmpty() }?.let { out.add(it.joinToString(" · ")) }
            }
        }
    }

    private fun items(arr: JsonElement?, out: MutableList<String>) {
        (arr as? JsonArray)?.forEach { it ->
            when (it) {
                is JsonPrimitive -> add(out, it.content, bullet = true)
                is JsonObject -> {
                    add(out, BlockDoc.str(it, "content").ifEmpty { BlockDoc.str(it, "text") }, bullet = true)
                    items(it["items"], out)
                }
                else -> {}
            }
        }
    }

    private fun plain(html: String): String = try {
        InlineHtml.plain(html)
    } catch (_: Exception) {
        html.replace(Regex("<[^>]*>"), "")
    }

    private fun add(out: MutableList<String>, text: String, html: Boolean = true, bullet: Boolean = false) {
        val t = (if (html) plain(text) else text).replace(' ', ' ').replace(Regex("[ \\t\\r\\f]+"), " ").replace(Regex("\\s*\\n\\s*"), "\n").trim()
        if (t.isNotEmpty()) out.add(if (bullet) "• $t" else t)
    }

    /** Auf hoechstens max Zeichen kuerzen - an einer Wortgrenze (wenn sinnvoll), mit "…" */
    fun truncate(text: String, max: Int): String {
        val t = text.trim()
        if (t.length <= max) return t
        val cut = t.take(max)
        val sp = cut.indexOfLast { it == ' ' || it == '\n' }
        val base = if (sp >= max * 2 / 3) cut.take(sp) else cut
        // kein halbes Ersatzzeichenpaar (Emoji) am Ende
        val safe = if (base.isNotEmpty() && Character.isHighSurrogate(base.last())) base.dropLast(1) else base
        return safe.trimEnd(' ', '\n', ',', ';', ':', '-', '·') + "…"
    }
}

/** Platz im Widget: wie viele Zeilen fuer Termine und Aufgaben (zusammen hoechstens rows) */
object TodayLayout {
    /**
     * Beide da: Termine bekommen hoechstens die Haelfte (aufgerundet), Aufgaben den Rest - was eine Seite nicht
     * braucht, bekommt die andere. Ergebnis: (Termine, Aufgaben).
     */
    fun split(rows: Int, events: Int, tasks: Int): Pair<Int, Int> {
        if (rows <= 0) return 0 to 0
        if (tasks == 0) return minOf(rows, events) to 0
        if (events == 0) return 0 to minOf(rows, tasks)
        val e = minOf(events, (rows + 1) / 2, rows - 1).coerceAtLeast(if (rows >= 2) 1 else 0)
        val t = minOf(tasks, rows - e)
        // Aufgaben brauchen weniger als ihren Teil: Rest an die Termine
        return minOf(events, rows - t) to t
    }

    /** Zeilen fuer eine Hoehe in dp (Kopf und Rand abgezogen) */
    fun rows(heightDp: Float, rowDp: Float = 26f, chromeDp: Float = 46f): Int = ((heightDp - chromeDp) / rowDp).toInt().coerceIn(1, 14)
}
