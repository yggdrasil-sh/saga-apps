package io.github.yggdrasil_sh.saga.ui.database

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.yggdrasil_sh.saga.ui.theme.LocalSagaAccent

/**
 * Design-Runde 6 (A11Y-24/ED-27): Zeile im Seitenblatt oeffnen. Die Seite (Datenbank-Seite oder Seite mit eingebetteter
 * Datenbank) stellt den Oeffner bereit, solange sie breit genug ist ([RowSheetLogic.enabled]); sonst null -> wie bisher
 * als ganze Seite. [order] liefert die Zeilen in der Reihenfolge der Ansicht (fuer "Vorige/Naechste Zeile"), [changed]
 * laedt die Ansicht nach, wenn das Blatt die Zeile wechselt oder schliesst (Titel/Inhalt dort geaendert).
 */
fun interface RowSheetOpener {
    fun open(rowId: Long, order: (() -> List<Long>)?, changed: (() -> Unit)?)
}

val LocalRowSheet = compositionLocalOf<RowSheetOpener?> { null }

/** Zeile, die gerade im Seitenblatt offen ist (Markierung in Tabelle/Liste/Board/Galerie/Kalender) */
val LocalDbSelectedRow = compositionLocalOf<Long?> { null }

/** Zeilen-IDs in der Reihenfolge, wie die aktuelle Ansicht sie zeigt (Sortierung/Filterung/Suche der Ansicht) */
fun DbViewState.orderedRowIds(): List<Long> = RowSheetLogic.order(
    view?.type, rows.map { it.id }, boardKeys, columns.mapValues { (_, c) -> c.rows.map { it.id } }, monthRows.map { it.id },
)

/** Oeffnen ueber das Blatt, wenn eine Seite es anbietet - Ziel ist eine Zeile dieser Datenbank, nicht die Datenbank selbst */
@Composable
internal fun rowOpener(st: DbViewState, fallback: (Long) -> Unit): (Long) -> Unit {
    val sheet = LocalRowSheet.current ?: return fallback
    return { id -> if (id == st.dbId) fallback(id) else sheet.open(id, { st.orderedRowIds() }) { st.reload(debounce = 600) } }
}

/** Markierung der im Blatt offenen Zeile (Hintergrund + "ausgewaehlt" fuer TalkBack) */
@Composable
internal fun Modifier.dbRowSelected(id: Long): Modifier {
    if (LocalDbSelectedRow.current != id) return this
    return this.background(LocalSagaAccent.current.goldSoft.copy(alpha = 0.55f)).semantics { selected = true }
}

/** Karten (Board, Galerie): Goldrand statt Flaeche */
@Composable
internal fun Modifier.dbCardSelected(id: Long, shape: Shape): Modifier {
    if (LocalDbSelectedRow.current != id) return this
    return this.border(2.dp, LocalSagaAccent.current.gold, shape).semantics { selected = true }
}
