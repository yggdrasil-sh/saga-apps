package io.github.yggdrasil_sh.saga.ui.database

/**
 * Design-Runde 6 (A11Y-24/ED-27): Datenbank-Zeile als Seitenblatt rechts neben der Ansicht - reine Regeln, ohne Compose
 * testbar. Breiten in dp (Float wie [DbCompact]).
 */
object RowSheetLogic {
    /** Ab dieser verfuegbaren Breite der Seite (Inhaltsbereich neben Seitenleiste/Rail) oeffnen Zeilen als Blatt */
    const val MIN_DP = 840f
    const val SHEET_MIN_DP = 480f
    const val SHEET_MAX_DP = 560f
    /** Anteil der Breite fuer das Blatt, dazwischen [SHEET_MIN_DP]..[SHEET_MAX_DP] */
    const val SHEET_SHARE = 0.45f

    /** Blatt statt ganzer Seite? Nur Expanded (>= 840 dp); Telefon und 600-840 dp bleiben beim Vollbild */
    fun enabled(availableDp: Float): Boolean = availableDp >= MIN_DP

    /** Breite des Blatts: 45 % der Seite, mindestens 480, hoechstens 560 dp - links bleiben mindestens 280 dp Ansicht */
    fun sheetWidthDp(availableDp: Float): Float = (availableDp * SHEET_SHARE).coerceIn(SHEET_MIN_DP, SHEET_MAX_DP)

    /**
     * Vorige ([delta] = -1) bzw. naechste (+1) Zeile in der Reihenfolge der Ansicht ([ids]: aktuelle Sortierung und
     * Filterung, geladene Zeilen). Am Rand, ohne aktuelle Zeile in der Liste (weggefiltert, nicht geladen) oder bei
     * doppelten IDs nach dem ersten Vorkommen: null (Pfeil gesperrt).
     */
    fun neighbor(ids: List<Long>, current: Long, delta: Int): Long? {
        val i = ids.indexOf(current)
        if (i < 0) return null
        return ids.getOrNull(i + delta)
    }

    /** Was passiert mit einem offenen Blatt, wenn die Breite unter [MIN_DP] faellt (Drehen, Fenster verkleinert) */
    enum class Narrow { NOTHING, OPEN_AS_PAGE }

    /** Entscheidung: offene Zeile wird beim Schmalwerden als ganze Seite geoeffnet (Kontext bleibt, Zurueck fuehrt zur Ansicht) */
    fun onNarrow(sheetRow: Long?, enabled: Boolean): Narrow = if (sheetRow != null && !enabled) Narrow.OPEN_AS_PAGE else Narrow.NOTHING

    /** Wer bekommt "Zurueck"? Erst das Blatt, dann der Navigationsstapel */
    enum class Back { CLOSE_SHEET, NAVIGATION }

    fun back(sheetOpen: Boolean): Back = if (sheetOpen) Back.CLOSE_SHEET else Back.NAVIGATION

    /**
     * Reihenfolge der Zeilen, wie die Ansicht sie zeigt: Board spaltenweise in Spalten-Reihenfolge, Kalender die
     * Eintraege des angezeigten Monats bzw. der Woche, sonst (Tabelle, Liste, Galerie, Zeitleiste) die geladenen Zeilen
     * (Gruppen liefert der Server schon in Gruppen-Reihenfolge).
     */
    fun order(type: String?, rows: List<Long>, boardKeys: List<String?>, columns: Map<String?, List<Long>>, monthRows: List<Long>): List<Long> = when (type) {
        "board" -> {
            val keys = boardKeys.ifEmpty { columns.keys.toList() }
            val out = ArrayList<Long>()
            keys.forEach { k -> columns[k]?.let(out::addAll) }
            out.distinct()
        }
        "calendar" -> monthRows
        else -> rows
    }
}
