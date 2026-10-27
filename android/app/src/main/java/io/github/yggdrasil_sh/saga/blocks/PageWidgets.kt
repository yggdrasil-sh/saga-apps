package io.github.yggdrasil_sh.saga.blocks

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import java.text.NumberFormat
import java.time.DateTimeException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.random.Random

/**
 * Saga 2.5 Widgets in Seiten, Etappe 1 (SPEC-widgets, Abschnitt 4): Block `widget` nur anzeigen.
 * Reine Logik ohne Compose - Lesen des Blocks, Antwort von GET /api/notes/{nid}/widgets, Uhr, Countdown, Fortschritt,
 * Zahlenformat, Zitat-Auswahl. Der Block selbst wird nie veraendert (Regel 0).
 * Name "PageWidgets", weil "Widgets" in der App die Home-Bildschirm-Widgets sind.
 */
object PageWidgets {
    val KINDS = setOf("clock", "countdown", "progress", "metric", "quote")

    data class Spec(
        val id: String?, val kind: String, val size: String, val style: String, val accent: String?, val title: String,
        val cfg: JsonObject,
    ) {
        val known: Boolean get() = kind in KINDS
        /** Holt die App den Wert vom Server (Etappe 1: progress/db, metric, jedes quote - auch feste Zitate waehlt der
         *  Server nach `pick`, damit die Auswahl wie im Web ist)? */
        val serverValue: Boolean get() = when (kind) {
            "metric", "quote" -> true
            "progress" -> str(cfg, "src") == "db"
            else -> false
        }
        /** Festes Zitat: ohne Server-Wert (alter Server, offline, Foederation) lokal nach `pick` */
        val localFallback: Boolean get() = kind == "quote" && str(cfg, "src") != "db" && quoteItems(cfg).isNotEmpty()
    }

    private fun str(o: JsonObject?, k: String): String = (o?.get(k) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""
    private fun num(e: JsonElement?): Double? = (e as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() }
    private fun bool(o: JsonObject?, k: String): Boolean = (o?.get(k) as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull == true

    /** Block lesen; fremde/fehlende Werte -> Standard (m, card, keine Farbe) */
    fun spec(block: JsonObject): Spec {
        val d = BlockDoc.data(block)
        val size = str(d, "size").takeIf { it in setOf("s", "m", "l") } ?: "m"
        val style = str(d, "style").takeIf { it == "plain" } ?: "card"
        val accent = str(d, "accent").takeIf { it in Gestalten.PALETTE }
        val title = InlineHtml.decodeEntities(str(d, "title")).trim().take(80)
        return Spec(BlockDoc.id(block), str(d, "kind"), size, style, accent, title, d["cfg"] as? JsonObject ?: JsonObject(emptyMap()))
    }

    /** Alle Widget-Bloecke der Seite (auch in Spalten und Toggles), Reihenfolge wie im Dokument */
    fun collect(blocks: JsonArray): List<JsonObject> {
        val out = ArrayList<JsonObject>()
        fun walk(arr: JsonArray?, depth: Int) {
            if (arr == null || depth > 8) return
            for (el in arr) {
                val b = el as? JsonObject ?: continue
                val d = BlockDoc.data(b)
                when (BlockDoc.type(b)) {
                    "widget" -> out += b
                    "toggle" -> walk(d["blocks"] as? JsonArray, depth + 1)
                    "columns" -> (d["cols"] as? JsonArray)?.forEach { c -> walk((c as? JsonObject)?.get("blocks") as? JsonArray, depth + 1) }
                }
            }
        }
        walk(blocks, 0)
        return out
    }

    /** Block-IDs der Widgets, deren Wert der Server rechnet - leer: kein Abruf noetig */
    fun serverIds(doc: JsonObject): Set<String> =
        collect(BlockDoc.blocks(doc)).map { spec(it) }.filter { it.known && it.serverValue }.mapNotNull { it.id }.toSet()

    // ---------------------------------------------------------------- Werte vom Server

    enum class Err { NO_ACCESS, GONE, BAD_CFG, TOO_BIG, OTHER }

    data class Value(
        val kind: String, val value: Double?, val max: Double?, val text: String?, val fmt: String?, val asOf: String?, val error: Err?,
    )

    /** Zustand aller Werte einer Seite */
    sealed interface Values {
        data class Ok(val map: Map<String, Value>) : Values
        /** Seite eines anderen Servers, der keine Widgets kann (`unsupported: true`) */
        data object Unsupported : Values
        /** Aelterer Server ohne den Endpunkt (404) */
        data object OldServer : Values
        /** Netz/sonstiger Fehler */
        data object Failed : Values
    }

    private fun err(s: String?): Err? = when (s) {
        null, "" -> null
        "no_access" -> Err.NO_ACCESS
        "gone" -> Err.GONE
        "bad_cfg" -> Err.BAD_CFG
        "too_big" -> Err.TOO_BIG
        else -> Err.OTHER
    }

    fun parseValue(o: JsonObject): Value {
        val e = o["error"] as? JsonPrimitive
        return Value(
            str(o, "kind"), num(o["value"]), num(o["max"]),
            (o["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
            str(o, "fmt").takeIf { it in setOf("num", "pct", "eur") },
            str(o, "as_of").ifEmpty { null },
            if (e == null || !e.isString) null else err(e.content),
        )
    }

    /** Antwort von GET /api/notes/{nid}/widgets; kaputtes JSON -> Failed */
    fun parseResponse(text: String): Values {
        val root = try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return Values.Failed
        if (bool(root, "unsupported")) return Values.Unsupported
        val w = root["widgets"] as? JsonObject ?: return Values.Ok(emptyMap())
        return Values.Ok(w.mapNotNull { (k, v) -> (v as? JsonObject)?.let { k to parseValue(it) } }.toMap())
    }

    /** Zeitzone des Betrachters fuer `?tz=` (nur IANA-Namen; sonst weglassen - der Server antwortet auf Ungueltiges mit 400) */
    fun tzParam(id: String): String? = id.takeIf { (it == "UTC" || '/' in it) && Regex("""[A-Za-z0-9_+\-/]{1,64}""").matches(it) && ".." !in it }

    /** HTTP-Fehler beim Abruf: 404 = aelterer Server, sonst Fehler */
    fun fromHttpError(code: Int): Values = if (code == 404) Values.OldServer else Values.Failed

    // ---------------------------------------------------------------- Uhr (lokal)

    /** IANA-Zone aus cfg.tz; "" oder ungueltig -> Zone des Betrachters */
    fun zone(tz: String, viewer: ZoneId = ZoneId.systemDefault()): ZoneId {
        if (tz.isBlank()) return viewer
        return try {
            ZoneId.of(tz.trim())
        } catch (_: DateTimeException) {
            viewer
        }
    }

    /** Kurzname der Zone fuer die Unterzeile ("America/New_York" -> "New York"); leer bei Betrachter-Zone */
    fun zoneLabel(tz: String): String {
        if (tz.isBlank()) return ""
        return try {
            ZoneId.of(tz.trim()).id.substringAfterLast('/').replace('_', ' ')
        } catch (_: DateTimeException) {
            ""
        }
    }

    data class Clock(val tz: String, val h24: Boolean, val sec: Boolean, val analog: Boolean)

    fun clock(cfg: JsonObject): Clock = Clock(str(cfg, "tz"), (cfg["h24"] as? JsonPrimitive)?.booleanOrNull ?: true, bool(cfg, "sec"), bool(cfg, "analog"))

    fun clockText(now: Instant, c: Clock, locale: Locale, viewer: ZoneId = ZoneId.systemDefault()): String {
        val t = now.atZone(zone(c.tz, viewer))
        val p = when {
            c.h24 && c.sec -> "HH:mm:ss"
            c.h24 -> "HH:mm"
            c.sec -> "h:mm:ss a"
            else -> "h:mm a"
        }
        return DateTimeFormatter.ofPattern(p, locale).format(t)
    }

    fun clockDate(now: Instant, c: Clock, locale: Locale, viewer: ZoneId = ZoneId.systemDefault()): String =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(now.atZone(zone(c.tz, viewer)))

    /** Zeigerwinkel in Grad (0 = 12 Uhr, im Uhrzeigersinn): Stunde, Minute, Sekunde */
    fun handAngles(t: ZonedDateTime): Triple<Float, Float, Float> {
        val s = t.second.toFloat()
        val m = t.minute + s / 60f
        val h = (t.hour % 12) + m / 60f
        return Triple(h * 30f, m * 6f, s * 6f)
    }

    /** Millisekunden bis zum naechsten Tick (volle Sekunde bzw. Minute) */
    fun nextTickMillis(nowMillis: Long, sec: Boolean): Long {
        val step = if (sec) 1_000L else 60_000L
        return step - Math.floorMod(nowMillis, step)
    }

    // ---------------------------------------------------------------- Countdown (lokal)

    data class Countdown(
        /** ganze Tage (unit days bzw. auto ab einem Tag) - sonst null */
        val days: Long?,
        val hours: Int, val minutes: Int,
        /** until: Ziel erreicht/ueberschritten; since: Start liegt noch in der Zukunft */
        val done: Boolean,
        /** Ziel ohne Uhrzeit ist heute */
        val today: Boolean,
    )

    data class CountdownCfg(val date: LocalDate?, val time: LocalTime?, val since: Boolean, val auto: Boolean)

    fun countdownCfg(cfg: JsonObject): CountdownCfg {
        val date = try {
            LocalDate.parse(str(cfg, "date"))
        } catch (_: Exception) {
            null
        }
        val time = str(cfg, "time").takeIf { Regex("""\d{2}:\d{2}""").matches(it) }?.let {
            try {
                LocalTime.parse(it)
            } catch (_: Exception) {
                null
            }
        }
        return CountdownCfg(date, time, str(cfg, "dir") == "since", str(cfg, "unit") == "auto")
    }

    /** null = kein gueltiges Datum (bad_cfg) */
    fun countdown(c: CountdownCfg, now: ZonedDateTime): Countdown? {
        val date = c.date ?: return null
        val today = now.toLocalDate()
        if (c.time == null) {
            // nur Datum: Kalendertage in der Zone des Betrachters
            val d = if (c.since) ChronoUnit.DAYS.between(date, today) else ChronoUnit.DAYS.between(today, date)
            return Countdown(maxOf(0, d), 0, 0, done = d < 0, today = d == 0L)
        }
        val target = ZonedDateTime.of(date, c.time, now.zone)
        val dur = if (c.since) Duration.between(target, now) else Duration.between(now, target)
        if (dur.isNegative || dur.isZero) return Countdown(if (c.auto) null else 0, 0, 0, done = true, today = false)
        val totalMin = dur.toMinutes()
        val days = totalMin / 1440
        if (!c.auto || days >= 1) return Countdown(days, 0, 0, done = false, today = days == 0L && date == today)
        return Countdown(null, (totalMin / 60).toInt(), (totalMin % 60).toInt(), done = false, today = false)
    }

    // ---------------------------------------------------------------- Fortschritt

    data class Progress(val value: Double, val max: Double) {
        val fraction: Float get() = if (max > 0) (value / max).coerceIn(0.0, 1.0).toFloat() else 0f
        /** echt gerechnet, auch ueber 100 (Balken deckelt ueber [fraction]) */
        val percent: Int get() = NumFormat.progressPercent(value, max)
    }

    /** fixed lokal (max > 0), db aus dem Server-Wert; null = kein Wert */
    fun progress(s: Spec, v: Value?): Progress? {
        if (str(s.cfg, "src") == "db") {
            val value = v?.value ?: return null
            val max = v.max ?: num(s.cfg["goal"]) ?: return null
            return if (max > 0) Progress(value, max) else null
        }
        val value = num(s.cfg["value"]) ?: return null
        val max = num(s.cfg["max"])?.takeIf { it > 0 } ?: return null
        return Progress(value, max)
    }

    // ---------------------------------------------------------------- Zahlenformat

    /** num: bis 2 Nachkommastellen; pct: Wert in ganzen Prozent (42,4 -> "42 %"); eur: Waehrung mit 2 Stellen, lokalisiert */
    fun format(value: Double, fmt: String?, locale: Locale): String = when (fmt) {
        "pct" -> NumberFormat.getPercentInstance(locale).apply { maximumFractionDigits = 0; roundingMode = java.math.RoundingMode.HALF_UP }.format(value / 100.0)
        "eur" -> NumFormat.euro(value, locale)
        else -> NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 2 }.format(value)
    }

    fun metricFmt(s: Spec, v: Value?): String = v?.fmt ?: str(s.cfg, "fmt").takeIf { it in setOf("num", "pct", "eur") } ?: "num"

    fun metricGoal(s: Spec): Double? = num(s.cfg["goal"])?.takeIf { it > 0 }

    // ---------------------------------------------------------------- Zitat

    fun quoteItems(cfg: JsonObject): List<String> =
        (cfg["items"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.trim()?.takeIf { t -> t.isNotEmpty() } }
            .take(20).map { it.take(300) }

    /** 32-Bit-FNV-1a ueber UTF-8 - stabil auf allen Geraeten */
    fun fnv1a(s: String): Long {
        var h = 0x811C9DC5L
        for (b in s.toByteArray(Charsets.UTF_8)) {
            h = h xor (b.toLong() and 0xFF)
            h = (h * 0x01000193L) and 0xFFFFFFFFL
        }
        return h
    }

    /**
     * Index des fixen Zitats: daily = deterministisch aus (Datum des Betrachters, Block-ID), open = zufaellig je Oeffnen.
     * -1 = keine Zitate.
     */
    fun quoteIndex(n: Int, pick: String, date: LocalDate, blockId: String?, rnd: Random = Random.Default): Int {
        if (n <= 0) return -1
        if (pick == "open") return rnd.nextInt(n)
        return (fnv1a(date.toString() + ":" + (blockId ?: "")) % n).toInt()
    }

    /** Zitattext: zuerst der Server-Text (gleiche Auswahl wie im Web); fest notfalls lokal nach `pick` */
    fun quoteText(s: Spec, v: Value?, date: LocalDate, rnd: Random = Random.Default): String? {
        v?.text?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        if (str(s.cfg, "src") == "db") return null
        val items = quoteItems(s.cfg)
        val i = quoteIndex(items.size, str(s.cfg, "pick"), date, s.id, rnd)
        return items.getOrNull(i)
    }
}
