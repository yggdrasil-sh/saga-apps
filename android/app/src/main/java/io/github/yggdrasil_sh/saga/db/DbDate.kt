package io.github.yggdrasil_sh.saga.db

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * 2.5 datetime1 (APP-API "Datum mit Uhrzeit / Zeitraum"): Datumswerte sind Strings - Tag `2026-10-27`, Zeitpunkt mit
 * Offset `2026-10-27T14:30+01:00` oder Zeitraum `start/end` (Teile gemischt). Formel-Daten kommen auch als Ortszeit
 * ohne Offset (`2026-10-27T14:30`, schon in der Zeitzone der Anfrage). Angezeigt wird in der Zone des Betrachters.
 */
object DbDate {
    /** Ein Zeitpunkt oder ein ganzer Tag (time == null) */
    data class Part(val day: LocalDate, val time: OffsetDateTime? = null) {
        /** Tag in der Zone des Betrachters (ein Zeitpunkt kann dort auf einen anderen Tag fallen) */
        fun localDay(zone: ZoneId): LocalDate = time?.atZoneSameInstant(zone)?.toLocalDate() ?: day
        fun localTime(zone: ZoneId): LocalTime? = time?.atZoneSameInstant(zone)?.toLocalTime()
    }

    data class Value(val start: Part, val end: Part? = null) {
        val hasTime: Boolean get() = start.time != null || end?.time != null
        val isPlainDay: Boolean get() = end == null && start.time == null
    }

    fun parsePart(s: String, zone: ZoneId = ZoneId.systemDefault()): Part? {
        val t = s.trim()
        if (t.length < 10) return null
        return try {
            if (t.length == 10) return Part(LocalDate.parse(t))
            val iso = t.replace(' ', 'T')
            val hasOffset = iso.length > 16 && (iso.endsWith("Z") || Regex("[+-]\\d{2}:?\\d{2}$").containsMatchIn(iso.substring(16)))
            if (hasOffset) {
                val norm = Regex("([+-]\\d{2})(\\d{2})$").replace(iso) { "${it.groupValues[1]}:${it.groupValues[2]}" }
                val odt = OffsetDateTime.parse(norm)
                Part(odt.toLocalDate(), odt)
            } else {
                val ldt = LocalDateTime.parse(iso.take(16))                  // Ortszeit (Formel): in der Zone des Betrachters
                Part(ldt.toLocalDate(), ldt.atZone(zone).toOffsetDateTime())
            }
        } catch (_: Exception) {
            try {
                Part(LocalDate.parse(t.take(10)))                              // unbekannte Form: wenigstens der Tag
            } catch (_: Exception) {
                null
            }
        }
    }

    fun parse(s: String?, zone: ZoneId = ZoneId.systemDefault()): Value? {
        val t = s?.trim()?.ifEmpty { null } ?: return null
        val i = t.indexOf('/')
        if (i < 0) return parsePart(t, zone)?.let { Value(it) }
        val a = parsePart(t.substring(0, i), zone) ?: return null
        val b = parsePart(t.substring(i + 1), zone)
        return Value(a, b?.takeIf { it != a })
    }

    /** Lesbar in der Zone des Betrachters: "27.10.2026 14:30 – 16:00" bzw. "27.10.2026 – 29.10.2026" */
    fun display(v: Value, zone: ZoneId, locale: Locale): String {
        val dayFmt = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
        val timeFmt = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
        fun part(p: Part, withDay: Boolean = true): String {
            val d = p.localDay(zone)
            val tm = p.localTime(zone)
            return listOfNotNull(if (withDay) d.format(dayFmt) else null, tm?.format(timeFmt)).joinToString(" ")
        }
        val e = v.end ?: return part(v.start)
        val sameDay = v.start.localDay(zone) == e.localDay(zone)
        return if (sameDay && e.time != null) part(v.start) + " – " + part(e, withDay = false) else part(v.start) + " – " + part(e)
    }

    fun display(s: String?, zone: ZoneId, locale: Locale): String? = parse(s, zone)?.let { display(it, zone, locale) }

    /** Alle Tage (Zone des Betrachters), die der Wert beruehrt - fuer Kalender, hoechstens 62 */
    fun days(v: Value, zone: ZoneId): List<LocalDate> {
        val a = v.start.localDay(zone)
        val b = v.end?.localDay(zone)?.takeIf { !it.isBefore(a) } ?: return listOf(a)
        val out = ArrayList<LocalDate>()
        var d = a
        while (!d.isAfter(b) && out.size < 62) {
            out.add(d)
            d = d.plusDays(1)
        }
        return out
    }

    private val ISO_MIN = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")

    private fun offsetText(o: ZoneOffset): String = if (o.totalSeconds == 0) "+00:00" else o.id

    /** Ein Teil zum Schreiben: Tag, oder Wandzeit des Geraets mit dessen Offset (ohne Sekunden) */
    fun encodePart(day: LocalDate, time: LocalTime?, zone: ZoneId): String {
        if (time == null) return day.toString()
        val ldt = LocalDateTime.of(day, time.withSecond(0).withNano(0))
        return ldt.format(ISO_MIN) + offsetText(zone.rules.getOffset(ldt))
    }

    /**
     * Wert zum Schreiben. Reiner Tag: hatte der alte Wert Uhrzeit/Ende, `Tag/Tag` (der Server behielte sonst bei gleichem
     * Starttag die alte Uhrzeit) - nur mit datetime1; aeltere Server bekommen immer nur den Tag.
     */
    fun encode(
        start: LocalDate, startTime: LocalTime?, end: LocalDate?, endTime: LocalTime?, zone: ZoneId,
        old: String?, datetime1: Boolean,
    ): String {
        if (!datetime1) return start.toString()
        val a = encodePart(start, startTime, zone)
        val b = end?.let { encodePart(it, endTime, zone) }
        if (b == null || b == a) {
            val prev = parse(old, zone)
            return if (startTime == null && prev != null && !prev.isPlainDay) "$a/$a" else a
        }
        return "$a/$b"
    }

    /** Kalender: Wert auf einen anderen Tag schieben - Uhrzeit und Ende wandern mit (Dauer bleibt) */
    fun shift(old: String, to: LocalDate, zone: ZoneId): String? {
        val v = parse(old, zone) ?: return null
        val delta = java.time.temporal.ChronoUnit.DAYS.between(v.start.localDay(zone), to)
        fun mv(p: Part): String = if (p.time == null) p.day.plusDays(delta).toString()
        else p.time.atZoneSameInstant(zone).plusDays(delta).let { z -> z.toLocalDateTime().format(ISO_MIN) + offsetText(z.offset) }
        val a = mv(v.start)
        return v.end?.let { "$a/${mv(it)}" } ?: a
    }

    /** Sortier-/Vergleichsschluessel wie Server/Web: Start als lokales `YYYY-MM-DDTHH:MM` in tz */
    fun sortKey(s: String?, zone: ZoneId): String? {
        val v = parse(s, zone) ?: return null
        val t = v.start.time ?: return v.start.day.toString()
        return t.atZoneSameInstant(zone).toLocalDateTime().format(ISO_MIN)
    }
}
