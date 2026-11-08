package io.github.yggdrasil_sh.saga.ui.today

import io.github.yggdrasil_sh.saga.data.PageMeta

/** Design-Runde 4 (NAV-13): reine Logik der Heute-Seite (Gruss mit Vorname, "Weitermachen"), ohne Compose testbar */
object TodayLogic {
    /** Hoechstens so viele Karten unter "Weitermachen" (Web: hz-cards) */
    const val CONTINUE_MAX = 5

    /**
     * Vorname fuer den Gruss wie im Web (`dashboard.py`: erstes Wort des Anzeigenamens). Ist der Anzeigename eine
     * E-Mail-Adresse, zaehlt nur der Teil vor dem @. Leer -> null (dann Gruss ohne Namen).
     */
    fun firstName(display: String?): String? {
        val first = display?.trim()?.split(Regex("\\s+"))?.firstOrNull().orEmpty()
        return first.substringBefore('@').trim().takeIf { it.isNotEmpty() }
    }

    /**
     * "Weitermachen": zuletzt geoeffnete Seiten in der Reihenfolge der lokalen Liste (`settings.recent`, neueste
     * zuerst). Eigene Seiten haben Vorrang vor gleichen IDs aus "Mit mir geteilt"; Seiten, die es nicht mehr gibt
     * (geloescht, kein Zugriff), archivierte Seiten und Vorlagen fallen weg; jede Seite hoechstens einmal.
     */
    fun continuePages(recent: List<Long>, pages: List<PageMeta>, shared: List<PageMeta> = emptyList(), max: Int = CONTINUE_MAX): List<PageMeta> {
        if (recent.isEmpty() || max <= 0) return emptyList()
        val byId = HashMap<Long, PageMeta>(pages.size + shared.size)
        shared.forEach { byId[it.id] = it }
        pages.forEach { byId[it.id] = it }
        val seen = HashSet<Long>()
        return recent.asSequence()
            .filter { seen.add(it) }
            .mapNotNull { byId[it] }
            .filter { !it.archived && !it.template }
            .take(max)
            .toList()
    }

    /**
     * Index des Eintrags "Neue Aufgabe" in der Liste der Heute-Seite (Widget "Neue Aufgabe" springt dorthin):
     * Kopf (mit Schnellaktionen), ggf. "Weitermachen", Kopf Termine, Termine (leer = eine Zeile), Kopf Aufgaben.
     */
    fun quickAddIndex(hasContinue: Boolean, events: Int, twoColumns: Boolean = false): Int =
        if (twoColumns) 1 + (if (hasContinue) 1 else 0)            // R6: Spalten stehen in einem Eintrag, die Eingabe oben rechts
        else 1 + (if (hasContinue) 1 else 0) + 1 + maxOf(1, events) + 1

    // ---------------------------------------------------------------- Design-Runde 6 (A11Y-23): Tablet zweispaltig

    /** Ab dieser verfuegbaren Inhaltsbreite (dp) zwei Spalten: links Termine, rechts Aufgaben + Schnelleingabe */
    const val TWO_COLUMN_MIN_DP = 840f
    /** Hoechstbreite der sichtbaren Inhalte einer Spalte (Karten, Zeilen) */
    const val COLUMN_MAX_DP = 480f
    /** Seitlicher Rand je Spalte (= SagaSpace.gutter); zwischen den Spalten liegen damit 2 x 16 = 32 dp (SagaSpace.xxl) */
    const val COLUMN_GUTTER_DP = 16f
    /** Lesebreite der Einspalten-Darstellung (= SagaSpace.readingWidth) */
    const val ONE_COLUMN_MAX_DP = 760f

    fun twoColumns(availableDp: Float): Boolean = availableDp >= TWO_COLUMN_MIN_DP

    /** Hoechstbreite einer Spalte samt ihrer Raender (480 + 2 x 16 = 512 dp) */
    const val COLUMN_BOX_DP = COLUMN_MAX_DP + 2 * COLUMN_GUTTER_DP

    /**
     * Gesamtbreite des Inhaltsblocks der Heute-Seite (mittig, inkl. seitlicher Raender) - eine Stelle fuer alles, was
     * daran fluchten soll (Kopf, Weitermachen, Titel der App-Leiste): zweispaltig 2 x 512 = 1024 dp, sonst 760 dp,
     * jeweils hoechstens die verfuegbare Breite. Linke Inhaltskante = (verfuegbar - Ergebnis) / 2 + [COLUMN_GUTTER_DP].
     */
    fun contentWidthDp(availableDp: Float): Float =
        minOf(availableDp, if (twoColumns(availableDp)) 2 * COLUMN_BOX_DP else ONE_COLUMN_MAX_DP)
}
