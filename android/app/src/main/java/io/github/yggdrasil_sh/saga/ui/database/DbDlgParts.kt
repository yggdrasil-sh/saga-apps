package io.github.yggdrasil_sh.saga.ui.database

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.data.SagaRepository
import io.github.yggdrasil_sh.saga.ui.theme.SagaIcon
import io.github.yggdrasil_sh.saga.ui.theme.SagaIcons
import io.github.yggdrasil_sh.saga.ui.theme.SagaTextButton
import io.github.yggdrasil_sh.saga.util.UiText

// Android-Design-Runde 3: gemeinsame Bausteine der Auswahl- und Regel-Dialoge (AND-ED-23 bis -25).

/**
 * ED-24: Knopfzeile aller Auswahl-Dialoge. Links abgesetzt "Leeren" bzw. "Löschen" (destruktiv in Fehlerfarbe),
 * rechts nur Abbrechen und - falls noetig - Uebernehmen/Speichern. Als einziger confirmButton eines AlertDialog nutzen
 * (dismissButton = null), damit die Zeile die volle Breite bekommt.
 */
@Composable
fun DbDlgButtons(
    onCancel: () -> Unit,
    clearLabel: String? = null,
    onClear: (() -> Unit)? = null,
    destructive: Boolean = false,
    confirmLabel: String? = null,
    onConfirm: (() -> Unit)? = null,
    confirmEnabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        if (onClear != null && clearLabel != null) {
            SagaTextButton(onClick = onClear) {
                Text(clearLabel, color = if (destructive) MaterialTheme.colorScheme.error else Color.Unspecified)
            }
        } else Spacer(Modifier.width(1.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            SagaTextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
            if (onConfirm != null) SagaTextButton(onClick = onConfirm, enabled = confirmEnabled) {
                Text(confirmLabel ?: stringResource(R.string.action_apply))
            }
        }
    }
}

/**
 * ED-25: Optionszeile als `ListItem` (mindestens 56 dp), links `RadioButton` (Einfachauswahl) bzw. `Checkbox`
 * (Mehrfachauswahl). Die ganze Zeile ist `selectable`/`toggleable` mit passender Rolle - Bedienungshilfen lesen
 * Inhalt und Zustand ("Aktiv, ausgewählt") ohne eigene Beschreibung.
 */
@Composable
fun DbDlgOptionRow(multi: Boolean, selected: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    val m = if (multi) Modifier.toggleable(value = selected, role = Role.Checkbox, onValueChange = { onClick() })
    else Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
    ListItem(
        headlineContent = content,
        leadingContent = { if (multi) Checkbox(checked = selected, onCheckedChange = null) else RadioButton(selected = selected, onClick = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).then(m),
    )
}

/** Schalter mit Beschriftung, die ganze Flaeche (mind. 48 dp) schaltet */
@Composable
fun DbDlgSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.heightIn(min = 48.dp).toggleable(value = checked, role = Role.Switch, onValueChange = onChange).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** ED-23: Auswahlfeld als M3 `ExposedDropdownMenuBox` (statt eigener Pillen); Beschriftung = wofuer das Feld ist */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> DbDlgDropdown(
    label: String, items: List<Pair<T, String>>, selected: T, onPick: (T) -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true,
) {
    var open by remember { mutableStateOf(false) }
    val shown = items.firstOrNull { it.first == selected }?.second.orEmpty()
    ExposedDropdownMenuBox(expanded = open && enabled, onExpandedChange = { if (enabled) open = it }, modifier = modifier) {
        OutlinedTextField(
            value = shown, onValueChange = {}, readOnly = true, enabled = enabled, singleLine = true,
            label = { Text(label) },
            trailingIcon = { SagaIcon(SagaIcons.caretDown, null, Modifier.rotate(if (open) 180f else 0f)) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled),
        )
        ExposedDropdownMenu(expanded = open && enabled, onDismissRequest = { open = false }) {
            items.forEach { (k, t) ->
                DropdownMenuItem(
                    text = { Text(t, maxLines = 2, overflow = TextOverflow.Ellipsis) }, onClick = { open = false; onPick(k) },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
}

/**
 * ED-24: Tippen ausserhalb verwirft immer. Gab es Aenderungen, meldet die App "Änderungen verworfen · Rückgängig"
 * (Rueckgaengig uebernimmt den verworfenen Stand). Ohne Repository (keine Snackbar erreichbar) fragt sie stattdessen
 * im Dialog nach ("Änderungen verwerfen?").
 */
@Stable
class DbDlgDiscard internal constructor(private val repo: SagaRepository?) {
    var asking by mutableStateOf(false)
        internal set

    /** Ausserhalb getippt / Zurueck: ohne Aenderung schliessen, sonst verwerfen mit Rueckgaengig bzw. nachfragen */
    fun dismiss(changed: Boolean, close: () -> Unit, undo: () -> Unit) {
        when {
            !changed -> close()
            repo != null -> {
                close()
                repo.notify(UiText.Res(R.string.r3d_discarded), UiText.Res(R.string.action_undo)) { undo() }
            }
            else -> asking = true
        }
    }
}

@Composable
fun rememberDbDlgDiscard(repo: SagaRepository? = LocalDbRepo.current): DbDlgDiscard = remember(repo) { DbDlgDiscard(repo) }

/** Rueckfrage (nur ohne Snackbar): Verwerfen schliesst den Dialog, Weiter bearbeiten bleibt */
@Composable
fun DbDlgDiscardAsk(d: DbDlgDiscard, close: () -> Unit) {
    if (!d.asking) return
    AlertDialog(
        onDismissRequest = { d.asking = false },
        title = { Text(stringResource(R.string.r3d_discard_q)) },
        confirmButton = {
            SagaTextButton(onClick = { d.asking = false; close() }) { Text(stringResource(R.string.r3d_discard), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { SagaTextButton(onClick = { d.asking = false }) { Text(stringResource(R.string.r3d_keep_editing)) } },
    )
}

/** Button-Inhalt "+ Text" */
@Composable
internal fun RowScope.PlusLabel(text: String) {
    SagaIcon(SagaIcons.plus, null)
    Text(text, modifier = Modifier.padding(start = 4.dp))
}
