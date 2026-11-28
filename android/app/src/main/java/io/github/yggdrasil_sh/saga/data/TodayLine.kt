package io.github.yggdrasil_sh.saga.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicInteger

/*
 * 2.6 Tageszeile „Kurz gesagt“ auf „Heute“ (Bugs-DB 2271 K-D, Server docs/APP-API-KI.md §13). Reine Regeln (TodayLine,
 * mit JUnit gegen tests/today_vectors.json = test/resources/vectors/today_vectors.json, Format saga-today/v1) und der
 * Abruf (TodayLineService). Texte kommen fertig vom Server in der Sprache der Anfrage - hier werden keine Saetze gebaut;
 * Zustaende nur an Feldern, nie an Texten; `sentence` (fuer K-G) wird nicht gelesen; 4xx/5xx/alter Server -> nichts.
 * Bewusst ohne Regex (ICU auf dem Geraet != JVM).
 */

data class TlTarget(val type: String, val id: Long? = null, val filter: String? = null, val date: String? = null, val group: String? = null)
data class TlPart(val kind: String, val text: String, val n: Int? = null, val target: TlTarget? = null)
data class TlLine(val text: String, val a11y: String?, val parts: List<TlPart>)
data class TlWaiting(val n: Int?, val text: String, val target: TlTarget?)
/** events null = Kalender noch unbekannt (pending) */
data class TlCounts(val events: Int?, val over: Int?, val due: Int?, val waiting: Int?)
data class TlQuiet(val until: String?, val text: String, val undo: String)
data class TlAsk(val text: String, val yes: String, val no: String)

/** Antwort von GET/PUT /api/ai/today, POST|DELETE …/quiet bzw. `line` aus /api/app/today?line=1 (dort ohne ask/info) */
data class TodayLineState(
    val enabled: Boolean,
    val on: Boolean? = null,
    val day: String? = null,
    val tz: String? = null,
    val v: String? = null,
    val label: String? = null,
    val line: TlLine? = null,
    val waiting: TlWaiting? = null,
    val counts: TlCounts? = null,
    val quiet: TlQuiet? = null,
    val ask: TlAsk? = null,
    val pending: List<String> = emptyList(),
    val info: String? = null,
)

/** Was an der Stelle der Tageszeile zu sehen ist (Vektor-Feld `show`) */
data class TodayLineShow(
    val kurz: Kurz? = null,
    val pill: TlQuiet? = null,
    val ask: TlAsk? = null,
    val waiting: TlWaiting? = null,
) {
    data class Kurz(val label: String, val parts: List<TlPart>, val a11y: String)
    val isEmpty: Boolean get() = kurz == null && pill == null && ask == null && waiting == null
}

sealed interface TodayLineFetch {
    data class State(val s: TodayLineState) : TodayLineFetch
    /** 304: bisherigen Stand behalten */
    data object Keep : TodayLineFetch
    /** alter Server, Fehler, ohne Faehigkeit "ai": nichts zeigen */
    data object Empty : TodayLineFetch
}

/** Wohin ein Tipp fuehrt - oeffnet nur, hakt nie ab */
sealed interface TodayLineNav {
    data class Task(val id: Long) : TodayLineNav
    data class Row(val id: Long) : TodayLineNav
    data class Tasks(val filter: String) : TodayLineNav
    data class Events(val date: String?) : TodayLineNav
    data class Jobs(val group: String) : TodayLineNav
}

enum class TodayWidgetSize { SMALL, MEDIUM, LARGE, LOCK }

object TodayLine {
    /** Server-Merkmal (/api/version features): vorher wird /api/ai/today nicht gefragt */
    const val FEATURE = "aitoday1"
    /** Live `ai.today.changed {v}`: so lange entprellen */
    const val DEBOUNCE_MS = 400L
    private val json = Json { ignoreUnknownKeys = true }

    fun shouldFetch(features: Set<String>): Boolean = FEATURE in features

    /** IANA-Zone fuer `?tz=` - "/" bleibt, "+" (Etc/GMT+1) und alles andere wird kodiert */
    fun tzQuery(tz: String): String = buildString {
        for (b in tz.toByteArray(Charsets.UTF_8)) {
            val c = (b.toInt() and 0xFF).toChar()
            if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '/' || c == '_' || c == '-' || c == '.') append(c)
            else append('%').append("%02X".format(b.toInt() and 0xFF))
        }
    }
    fun path(tz: String) = "api/ai/today?tz=${tzQuery(tz)}"
    fun quietPath(tz: String) = "api/ai/today/quiet?tz=${tzQuery(tz)}"
    /** Widget (App-Schluessel hk_): aeltere Server ignorieren line/tz */
    fun widgetPath(tz: String) = "api/app/today?sources=1&line=1&tz=${tzQuery(tz)}"

    fun etag(v: String?): String? = v?.takeIf { it.isNotEmpty() }?.let { "\"$it\"" }

    // ---------------------------------------------------------------- Decodieren (tolerant: Feld fuer Feld)
    private fun JsonObject.s(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.i(k: String): Int? = (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
    private fun JsonObject.l(k: String): Long? = (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
    private fun JsonObject.b(k: String): Boolean? = (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
    private fun JsonObject.o(k: String): JsonObject? = this[k] as? JsonObject

    private fun target(o: JsonObject?): TlTarget? {
        val t = o?.s("type") ?: return null
        return TlTarget(t, o.l("id"), o.s("filter"), o.s("date"), o.s("group"))
    }

    fun parse(text: String?): TodayLineState? {
        if (text.isNullOrBlank()) return null
        val o = try { json.parseToJsonElement(text) as? JsonObject } catch (_: Exception) { null } ?: return null
        return parse(o)
    }

    fun parse(e: JsonElement?): TodayLineState? {
        val o = e as? JsonObject ?: return null
        val line = o.o("line")?.let { l ->
            val parts = (l["parts"] as? JsonArray).orEmpty().mapNotNull { p ->
                val po = p as? JsonObject ?: return@mapNotNull null
                val t = po.s("text")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                TlPart(po.s("kind").orEmpty(), t, po.i("n"), target(po.o("target")))
            }
            TlLine(l.s("text").orEmpty(), l.s("a11y"), parts)
        }
        return TodayLineState(
            enabled = o.b("enabled") ?: false,
            on = o.b("on"), day = o.s("day"), tz = o.s("tz"), v = o.s("v"), label = o.s("label"),
            line = line,
            waiting = o.o("waiting")?.let { w -> w.s("text")?.let { TlWaiting(w.i("n"), it, target(w.o("target"))) } },
            counts = o.o("counts")?.let { c -> TlCounts(c.i("events"), c.i("over"), c.i("due"), c.i("waiting")) },
            quiet = o.o("quiet")?.let { q -> val t = q.s("text"); val u = q.s("undo"); if (t != null && u != null) TlQuiet(q.s("until"), t, u) else null },
            ask = o.o("ask")?.let { a -> val t = a.s("text"); val y = a.s("yes"); val n = a.s("no"); if (t != null && y != null && n != null) TlAsk(t, y, n) else null },
            pending = (o["pending"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content },
            info = o.s("info"),
        )
    }

    /** `line` aus der Widget-Antwort /api/app/today?line=1 (aeltere Server: null) */
    fun widgetLine(appTodayText: String?): TodayLineState? {
        if (appTodayText.isNullOrBlank()) return null
        val o = try { json.parseToJsonElement(appTodayText) as? JsonObject } catch (_: Exception) { null } ?: return null
        return parse(o["line"])
    }

    fun result(status: Int, text: String?): TodayLineFetch {
        if (status == 304) return TodayLineFetch.Keep
        if (status !in 200..299) return TodayLineFetch.Empty
        val s = parse(text)?.takeIf { it.enabled } ?: return TodayLineFetch.Empty
        return TodayLineFetch.State(s)
    }

    /** Anzeige-Regeln (Vektoren `display`): Frage vor Pille vor Zeile; `waiting` eigene Zeile, auch bei aus/Ruhe */
    fun show(s: TodayLineState?): TodayLineShow {
        if (s == null || !s.enabled) return TodayLineShow()
        val waiting = s.waiting?.takeIf { it.text.isNotEmpty() }
        s.ask?.takeIf { it.text.isNotEmpty() }?.let { return TodayLineShow(ask = it, waiting = waiting) }
        s.quiet?.takeIf { it.text.isNotEmpty() }?.let { return TodayLineShow(pill = it, waiting = waiting) }
        val l = s.line
        val kurz = if (s.on != false && l != null && l.parts.isNotEmpty()) {
            TodayLineShow.Kurz(s.label.orEmpty(), l.parts, l.a11y?.takeIf { it.isNotEmpty() } ?: l.text)
        } else null
        return TodayLineShow(kurz = kurz, waiting = waiting)
    }

    /** Ziel -> Navigation; unbekannter Typ bzw. fehlende Kennung -> null = nicht antippbar */
    fun nav(t: TlTarget?): TodayLineNav? = when (t?.type) {
        "task" -> t.id?.let { TodayLineNav.Task(it) }
        "row" -> t.id?.let { TodayLineNav.Row(it) }
        "tasks" -> TodayLineNav.Tasks(t.filter ?: "today")
        "events" -> TodayLineNav.Events(t.date)
        "jobs" -> TodayLineNav.Jobs(t.group ?: "wartet_auf_dich")
        else -> null
    }

    /** Live: neu laden, wenn der Stand abweicht (ohne `v` sicherheitshalber auch) */
    fun liveNeedsReload(eventV: String?, own: String?): Boolean = eventV.isNullOrEmpty() || eventV != own

    /** Frage „Tageszeile ausblenden?“: Ja = aus und beantwortet, Nein = nur beantwortet */
    fun askBody(yes: Boolean): JsonObject = buildJsonObject {
        if (yes) put("on", false)
        put("asked", true)
    }

    // ---------------------------------------------------------------- Widget
    /** Glance-Groesse: Mittel ab 200 dp Breite und 100 dp Hoehe, Gross ab 220 dp Hoehe, sonst Klein */
    fun widgetSize(widthDp: Float, heightDp: Float): TodayWidgetSize = when {
        widthDp < 200f || heightDp < 100f -> TodayWidgetSize.SMALL
        heightDp >= 220f -> TodayWidgetSize.LARGE
        else -> TodayWidgetSize.MEDIUM
    }

    /** Mittel/Gross: fertiger Text der Zeile; Klein und Sperrbildschirm nie (nur Zahlen wie bisher); Ruhe/aus -> null */
    fun widgetText(s: TodayLineState?, size: TodayWidgetSize): String? {
        if (size != TodayWidgetSize.MEDIUM && size != TodayWidgetSize.LARGE) return null
        if (s == null || !s.enabled || s.on == false || s.quiet != null) return null
        val l = s.line ?: return null
        return l.text.ifEmpty { l.parts.joinToString(" · ") { it.text } }.ifEmpty { null }
    }

    /**
     * 2.6 K-G (2275): Zaehler „Wartet auf dich“ fuer Klein (wie iOS Klein/Sperrbildschirm) - nur die Zahl counts.waiting
     * (sonst waiting.n), nie Texte (RT-31); auch bei Ruhe/aus wie `waiting`. 0, ohne Faehigkeit ai, aelterer Server: null
     */
    fun widgetCount(s: TodayLineState?): Int? {
        if (s == null || !s.enabled) return null
        val n = s.counts?.waiting ?: s.waiting?.n ?: return null
        return n.takeIf { it > 0 }
    }

    /** Zahl im kleinen Widget: ab 100 „99+“ (Platz) */
    fun widgetCountText(n: Int): String = if (n > 99) "99+" else n.toString()

    /** „Wartet auf dich“ im Widget: nur Gross */
    fun widgetWaiting(s: TodayLineState?, size: TodayWidgetSize): String? =
        if (size == TodayWidgetSize.LARGE && s != null && s.enabled) s.waiting?.text?.ifEmpty { null } else null
}

/**
 * Abruf und Aktionen der Tageszeile fuer „Heute“ und Einstellungen › Saga. Neu laden bei Live `ai.today.changed`
 * (400 ms entprellt, nur bei anderem `v`) und beim Wiederverbinden (wenn schon etwas gezeigt wurde).
 */
class TodayLineService(private val repo: SagaRepository, private val scope: CoroutineScope) {
    val state = MutableStateFlow<TodayLineState?>(null)
    val busy = MutableStateFlow(false)
    /** „Wartet auf dich“ angetippt: Saga zeigt das Segment „Aufträge“ */
    val wantJobs = MutableStateFlow(false)
    private val gen = AtomicInteger(0)
    private var liveJob: Job? = null

    init {
        scope.launch {
            repo.live.events.collect { ev ->
                if (ev.type == "ai.today.changed" && TodayLine.liveNeedsReload(ev.str("v"), state.value?.v)) {
                    liveJob?.cancel()
                    liveJob = scope.launch { delay(TodayLine.DEBOUNCE_MS); load() }
                }
            }
        }
        scope.launch {
            var was = repo.live.connected.value
            repo.live.connected.collect { on ->
                if (on && !was && state.value != null) load()
                was = on
            }
        }
    }

    private fun tz(): String = java.time.ZoneId.systemDefault().id

    /** Stand holen (mit ETag -> 304 behaelt ihn); alter Server/Fehler -> nichts */
    suspend fun load() {
        if (!TodayLine.shouldFetch(repo.serverFeatures.value) || repo.needsLogin.value) {
            state.value = null
            return
        }
        val g = gen.incrementAndGet()
        val tz = tz()
        val cur = state.value
        val etag = if (cur != null && cur.tz == tz) TodayLine.etag(cur.v) else null
        val r = try {
            TodayLine.result(200, repo.api.request("GET", TodayLine.path(tz), headers = etag?.let { mapOf("If-None-Match" to it) }.orEmpty()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            TodayLine.result(e.code, null)
        } catch (_: Exception) {
            TodayLineFetch.Empty
        }
        if (g == gen.get()) apply(r)
    }

    fun reset() {
        gen.incrementAndGet()
        liveJob?.cancel()
        state.value = null
        wantJobs.value = false
    }

    /** ✕ bzw. Wischen: Ruhe bis morgen 05:00 auf allen Geraeten */
    suspend fun quiet() = send("POST", TodayLine.quietPath(tz()))
    /** „Wieder an“ an der Pille */
    suspend fun unquiet() = send("DELETE", TodayLine.quietPath(tz()))
    /** ⋯ „Tageszeile ausblenden“ bzw. Einstellungen › Saga */
    suspend fun setOn(on: Boolean) = send("PUT", TodayLine.path(tz()), buildJsonObject { put("on", on) })
    /** Frage „Tageszeile ausblenden?“ */
    suspend fun answer(yes: Boolean) = send("PUT", TodayLine.path(tz()), TodayLine.askBody(yes))

    private suspend fun send(method: String, path: String, body: JsonElement? = null) {
        if (busy.value) return
        busy.value = true
        val g = gen.incrementAndGet()
        try {
            val t = repo.api.request(method, path, body)
            if (g == gen.get()) apply(TodayLine.result(200, t))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            repo.notify(repo.friendly(e))
        } finally {
            busy.value = false
        }
    }

    private fun apply(r: TodayLineFetch) {
        when (r) {
            is TodayLineFetch.State -> state.value = r.s
            TodayLineFetch.Keep -> Unit
            TodayLineFetch.Empty -> state.value = null
        }
    }
}
