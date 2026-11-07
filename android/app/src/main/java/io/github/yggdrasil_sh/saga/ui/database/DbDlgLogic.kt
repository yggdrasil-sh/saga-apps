package io.github.yggdrasil_sh.saga.ui.database

import io.github.yggdrasil_sh.saga.db.DbFilter
import io.github.yggdrasil_sh.saga.db.DbProp
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * Android-Design-Runde 3 (AND-ED-23 bis -26): reine Logik der Auswahl- und Regel-Dialoge, ohne Oberflaeche.
 *
 * Eine Regel fuer alle Auswahl-Dialoge (ED-24): Einfachauswahl uebernimmt sofort. Tippen ausserhalb (oder Zurueck,
 * Wischen am Sheet) verwirft immer. Gab es in einer Mehrfachauswahl bzw. in Filter/Sortieren Aenderungen, meldet die
 * App "Aenderungen verworfen · Rueckgaengig" - Rueckgaengig uebernimmt den verworfenen Stand doch noch.
 */
object DbDlg {
    /** Trenner der Klartext-Kopfzeile ("Status · ist nicht · Abgeschlossen") */
    const val SEP = " · "

    /** Platzhalter fuer einen noch fehlenden Wert ("Name · enthält · …") */
    const val MISSING = "…"

    /** Teile zu einer Kopfzeile verbinden, leere Teile entfallen */
    fun headline(parts: List<String?>): String = parts.mapNotNull { it?.trim()?.ifEmpty { null } }.joinToString(SEP)

    /**
     * Wert eines Filters im Klartext: Option per Name, Status-Gruppe per Bezeichnung, Datum in der Sprache der
     * Oberflaeche. null = die Bedingung hat keinen Wert (ist leer, heute, mir zugewiesen ...).
     */
    fun filterValue(p: DbProp, f: DbFilter, statusLabel: (String) -> String, day: (String) -> String): String? {
        if (f.noValue) return null
        val v = f.value.trim()
        if (f.op == "status_group") return if (v.isEmpty()) MISSING else statusLabel(v)
        if (f.op == "next_days" || f.op == "past_days") return v.ifEmpty { MISSING }
        // Person "enthält" waehlt man nur im Web, Relationen haben in der App keinen Filterwert
        if (p.type == "person" || p.effType == "relation") return null
        return when (p.effType) {
            "select", "multi_select" -> if (v.isEmpty()) MISSING else p.option(v)?.name ?: v
            "date", "created", "edited" -> if (v.isEmpty()) MISSING else runCatching { day(v) }.getOrNull()?.ifEmpty { null } ?: v
            else -> v.ifEmpty { MISSING }
        }
    }

    /** Kopfzeile einer Filterkarte: "Status · ist nicht · Abgeschlossen" */
    fun filterHeadline(propName: String, opLabel: String, value: String?): String = headline(listOf(propName, opLabel, value))

    /** Kopfzeile einer Sortierkarte: "Datum · Absteigend" */
    fun sortHeadline(propName: String, dirLabel: String): String = headline(listOf(propName, dirLabel))

    /** Hat die Mehrfachauswahl sich geaendert? (Reihenfolge egal, doppelte zaehlen nicht) */
    fun <T> changed(initial: Collection<T>, current: Collection<T>): Boolean = initial.toSet() != current.toSet()

    /** Haben sich die Regeln (Filter/Sortierung) geaendert? Hier zaehlt die Reihenfolge (erste Sortierung gewinnt). */
    fun <T> rulesChanged(initial: List<T>, current: List<T>): Boolean = initial != current

    /** Schnellwahl im Datum-Dialog (wie die Faelligkeit bei Aufgaben) */
    enum class Quick { TODAY, TOMORROW, NEXT_WEEK }

    /** Tag der Schnellwahl: heute, morgen, Montag der naechsten Woche (wie der Filter "nächste Woche" im Web) */
    fun quickDay(q: Quick, today: LocalDate): LocalDate = when (q) {
        Quick.TODAY -> today
        Quick.TOMORROW -> today.plusDays(1)
        Quick.NEXT_WEEK -> today.with(TemporalAdjusters.next(DayOfWeek.MONDAY))
    }

    /** Neuer Beginn: ein vorhandenes Ende wandert um gleich viele Tage mit (Dauer bleibt) */
    fun moveStart(start: LocalDate, end: LocalDate?, newStart: LocalDate): Pair<LocalDate, LocalDate?> =
        newStart to end?.plusDays(ChronoUnit.DAYS.between(start, newStart))

    /** Zeitraum gueltig? Ende nicht vor dem Beginn (am selben Tag: Endzeit nicht vor der Startzeit) */
    fun rangeValid(start: LocalDate, startMin: Int?, end: LocalDate?, endMin: Int?): Boolean = when {
        end == null -> true
        end.isBefore(start) -> false
        end == start && startMin != null && endMin != null -> endMin >= startMin
        else -> true
    }
}
