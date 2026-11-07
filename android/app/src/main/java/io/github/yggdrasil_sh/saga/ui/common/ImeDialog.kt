package io.github.yggdrasil_sh.saga.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.DialogProperties

/**
 * Design-Runde Android CH-05/A11Y-26: AlertDialogs mit Textfeld duerfen von der Tastatur nicht verdeckt werden.
 *
 * Verwendung:
 * ```
 * AlertDialog(
 *     properties = ImeDialog.properties,
 *     modifier = Modifier.imeDialog(),
 *     text = { ImeDialogColumn { OutlinedTextField(...) } },   // bzw. eine LazyColumn mit heightIn(max = ...)
 *     ...
 * )
 * ```
 * Das Dialogfenster bekommt die Einsaetze selbst (decorFitsSystemWindows = false). `imeDialog()` haelt den Dialog
 * dann oberhalb von Tastatur, Statusleiste und Navigationsleiste; der Text-Slot des AlertDialog (weight, fill = false)
 * schrumpft, die Knoepfe bleiben sichtbar. Den Rest uebernimmt ein scrollbarer Inhalt.
 */
object ImeDialog {
    /** Fenster-Eigenschaften fuer Dialoge mit Textfeld (Standardbreite bleibt) */
    val properties = DialogProperties(decorFitsSystemWindows = false)

    /** Dieselben Eigenschaften, aber Schliessen per Zurueck/aussen wie gewuenscht */
    fun properties(dismissOnBackPress: Boolean = true, dismissOnClickOutside: Boolean = true) =
        DialogProperties(dismissOnBackPress = dismissOnBackPress, dismissOnClickOutside = dismissOnClickOutside, decorFitsSystemWindows = false)
}

/** Fuer den `modifier` eines AlertDialog mit [ImeDialog.properties]: Abstand zu Tastatur und Systemleisten */
fun Modifier.imeDialog(): Modifier = this.safeDrawingPadding()

/** Scrollbarer Inhalt fuer den `text`-Slot eines AlertDialog (nicht um eine LazyColumn ohne Hoehengrenze legen) */
@Composable
fun ImeDialogColumn(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.verticalScroll(rememberScrollState()), content = content)
}
