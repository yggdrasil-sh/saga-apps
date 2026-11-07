package io.github.yggdrasil_sh.saga.ui.database

import io.github.yggdrasil_sh.saga.db.DbDate
import io.github.yggdrasil_sh.saga.db.DbProp
import java.time.LocalDate
import java.time.ZoneId
import java.time.chrono.IsoChronology
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Android-Design Runde 3 (ED-12/14/16/19/21): reine Entscheidungen fuer die kompakte Darstellung der Datenbank auf
 * dem Handy - ohne Compose, damit sie sich im Unit-Test pruefen lassen.
 */
object DbCompact {
    /** Ansichtsarten mit eigener Darstellung; alles andere (table, unbekannt) zeichnet die Tabelle */
    private val OWN = setOf("board", "list", "gallery", "calendar", "chart", "timeline")

    /** Ansicht wird als Tabelle gezeichnet (Tabelle oder unbekannte Art) */
    fun isTable(type: String): Boolean = type !in OWN

    /**
     * ED-12: Tabelle unter 600 dp (nicht [wide]) als kompakte Liste - ausser der Schalter "Als Tabelle" ist fuer
     * diese Ansicht an. Andere Ansichtsarten bleiben, wie sie sind.
     */
    fun compactList(type: String, wide: Boolean, asTable: Boolean): Boolean = isTable(type) && !wide && !asTable

    /** Schalter "Als Tabelle" anbieten: nur, wo er etwas aendert (Tabelle auf dem Handy) */
    fun offerTableToggle(type: String, wide: Boolean): Boolean = isTable(type) && !wide

    /** Merk-Schluessel (lokal, nie auf dem Server): je Vollbild-Datenbank bzw. eingebettetem Block und Ansicht */
    fun asTableKey(dbId: Long, embedKey: String?, viewId: String): String = "db_astable_" + (embedKey ?: "db_$dbId") + "_" + viewId

    /** Reihenfolge der Eigenschaften in der kompakten Zeile: Status/Auswahl, Datum, Person, Fortschritt/Zahl, Mehrfach */
    private fun rank(p: DbProp): Int = when (p.effType) {
        "select" -> if (p.status) 0 else 1
        "date" -> 2
        "person" -> 3
        "number", "calc" -> 4
        "multi_select" -> 5
        else -> -1
    }

    /**
     * ED-12: bis zu [max] sichtbare Eigenschaften fuer die kompakte Zeile - nur solche mit Wert (oder Formelfehler),
     * nach [rank] geordnet, bei gleichem Rang in der Reihenfolge der Ansicht.
     */
    fun compactProps(visible: List<DbProp>, hasValue: (DbProp) -> Boolean, max: Int = 3): List<DbProp> =
        visible.filter { rank(it) >= 0 && hasValue(it) }.sortedBy { rank(it) }.take(max)

    // ------------------------------------------------------------------ kurze Datumswerte

    private fun shortPattern(locale: Locale): String =
        DateTimeFormatterBuilder.getLocalizedDateTimePattern(FormatStyle.SHORT, null, IsoChronology.INSTANCE, locale)

    /** Muster ohne Jahr aus dem kurzen Muster der Sprache: "dd.MM.yy" -> "dd.MM.", "M/d/yy" -> "M/d", "y/MM/dd" -> "MM/dd" */
    internal fun noYearPattern(locale: Locale): String {
        val p = shortPattern(locale)
        val m = Regex("([^A-Za-z']*)y+([^A-Za-z']*)").find(p) ?: return p
        val head = p.substring(0, m.range.first)
        val tail = p.substring(m.range.last + 1)
        val before = m.groupValues[1]
        return when {
            head.isEmpty() -> tail.trimStart { !it.isLetter() && it != '\'' }
            tail.isEmpty() -> head + if (before.trim() == ".") "." else ""      // Deutsch/Russisch: "02.11."
            else -> head + before + tail
        }
    }

    /** kurzes Muster mit vierstelligem Jahr: "dd.MM.yyyy" */
    internal fun fullYearPattern(locale: Locale): String = shortPattern(locale).replace(Regex("y+"), "yyyy")

    /** ein Tag kurz: im laufenden Jahr ohne Jahr ("02.11."), sonst mit kurzem Jahr ("02.11.25") */
    fun shortDay(d: LocalDate, today: LocalDate, locale: Locale): String =
        d.format(DateTimeFormatter.ofPattern(if (d.year == today.year) noYearPattern(locale) else shortPattern(locale), locale))

    /**
     * ED-12/ED-19: Datumswert kurz. Zeitraum ueber mehrere Tage "16.11. → 18.12.2026" (Start ohne Jahr, wenn es dem
     * Ende gleicht; Ende mit vollem Jahr), Uhrzeit nach dem Tag, Zeitraum am selben Tag "02.11. 14:00 – 16:00".
     */
    fun shortDate(v: DbDate.Value, zone: ZoneId, today: LocalDate, locale: Locale): String {
        val tf = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
        val s = v.start.localDay(zone)
        val st = v.start.localTime(zone)
        val first = shortDay(s, today, locale) + (st?.let { " " + it.format(tf) } ?: "")
        val e = v.end ?: return first
        val ed = e.localDay(zone)
        val et = e.localTime(zone)
        if (ed == s) return first + (et?.let { " – " + it.format(tf) } ?: "")
        val startTxt = s.format(DateTimeFormatter.ofPattern(if (s.year == ed.year) noYearPattern(locale) else shortPattern(locale), locale)) +
            (st?.let { " " + it.format(tf) } ?: "")
        val endTxt = ed.format(DateTimeFormatter.ofPattern(fullYearPattern(locale), locale)) + (et?.let { " " + it.format(tf) } ?: "")
        return "$startTxt → $endTxt"
    }

    /** Wert ist ein Zeitraum ueber mehrere Tage (dann mit Kalender-Symbol statt Kurzlabel) */
    fun isRange(v: DbDate.Value, zone: ZoneId): Boolean = v.end != null && v.end.localDay(zone) != v.start.localDay(zone)

    // ------------------------------------------------------------------ Einrasten (Tabelle)

    /**
     * ED-14: Spaltenkanten (Pixel) des waagrechten Scrollbereichs - Beginn jeder Spalte, begrenzt auf [max], dazu das
     * Ende (max), damit man die letzte Spalte ganz sehen kann.
     */
    fun columnEdges(widths: List<Float>, max: Float): List<Float> {
        val out = ArrayList<Float>(widths.size + 1)
        var x = 0f
        for (w in widths) {
            if (x >= max) break
            out.add(x)
            x += w
        }
        if (out.isEmpty() || out.last() < max) out.add(max)
        return out
    }

    /**
     * ED-14: Ziel-Kante fuer das Einrasten ab [current]: ohne Schwung die naechste, mit Schwung (direction > 0 nach
     * rechts) die naechste in Wischrichtung. Rueckgabe: Abstand zum Ziel (Ziel - current).
     */
    fun snapOffset(edges: List<Float>, current: Float, direction: Float): Float {
        if (edges.isEmpty()) return 0f
        val target = when {
            direction > 0f -> edges.firstOrNull { it >= current - 0.5f } ?: edges.last()
            direction < 0f -> edges.lastOrNull { it <= current + 0.5f } ?: edges.first()
            else -> edges.minByOrNull { kotlin.math.abs(it - current) }!!
        }
        return target - current
    }

    // ------------------------------------------------------------------ Ansichten-Pillen

    /**
     * ED-16: Scroll-Position, damit die aktive Pille ([left]..[right] im Inhalt) ganz sichtbar ist - mit [margin] Abstand
     * zum ausgeblendeten Rand. Steht sie schon ganz im Bild, bleibt [scroll].
     */
    fun pillScroll(left: Int, right: Int, viewport: Int, scroll: Int, maxScroll: Int, margin: Int): Int {
        if (viewport <= 0) return scroll
        val target = when {
            right - left + 2 * margin >= viewport -> left - margin
            left < scroll + margin -> left - margin
            right > scroll + viewport - margin -> right - viewport + margin
            else -> scroll
        }
        return target.coerceIn(0, maxOf(0, maxScroll))
    }

    // ------------------------------------------------------------------ Kalender

    /** ED-21: vorausgewaehlter Tag der Agenda - heute, wenn der Monat ihn enthaelt, sonst der Erste des Monats */
    fun agendaDay(month: LocalDate, today: LocalDate): LocalDate =
        if (today.year == month.year && today.month == month.month) today else month.withDayOfMonth(1)

    /** ED-21: Punkte je Tag im kompakten Kalender (hoechstens 3) */
    fun dots(count: Int): Int = count.coerceIn(0, 3)

    /** ED-20: Board-Spaltenbreite - auf dem Handy Bildschirm − 48 dp (die naechste Spalte schaut hervor), sonst 280 dp */
    fun boardColumnWidth(widthDp: Float): Float = if (widthDp < 600f) maxOf(200f, widthDp - 48f) else 280f
}
