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
    fun quickAddIndex(hasContinue: Boolean, events: Int): Int = 1 + (if (hasContinue) 1 else 0) + 1 + maxOf(1, events) + 1
}
