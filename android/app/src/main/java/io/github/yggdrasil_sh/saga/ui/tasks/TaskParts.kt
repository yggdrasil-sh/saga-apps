package io.github.yggdrasil_sh.saga.ui.tasks

import androidx.compose.ui.res.stringResource
import io.github.yggdrasil_sh.saga.R
import androidx.compose.foundation.clickable
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.yggdrasil_sh.saga.data.SagaRepository
import io.github.yggdrasil_sh.saga.data.TaskItem
import io.github.yggdrasil_sh.saga.ui.common.dueText
import io.github.yggdrasil_sh.saga.ui.common.todayIso
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Haken einer Aufgabe fuer Bedienungshilfen: Aufgabentext und die Aktion "Erledigen" bzw. "Wieder oeffnen" (N-07) */
@Composable
fun Modifier.taskCheckSemantics(text: String, done: Boolean, toggle: () -> Unit): Modifier {
    val action = stringResource(if (done) R.string.task_reopen_action else R.string.task_complete_action)
    return this.semantics {
        contentDescription = text
        onClick(label = action) { toggle(); true }
    }
}

/** Eine Aufgabe: Haken (erledigt/wieder offen), Text, Faelligkeit; antippen = bearbeiten */
@Composable
fun TaskRow(t: TaskItem, repo: SagaRepository, onEdit: (TaskItem) -> Unit) {
    val overdue = !t.done && (t.due ?: "9") < todayIso()
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .clickable(onClickLabel = stringResource(R.string.task_edit)) { onEdit(t) }
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = t.done,
            // im App-Scope: die Zeile verschwindet sofort aus "Heute" - ihr eigener Scope wuerde das Speichern abbrechen
            onCheckedChange = { v -> repo.toggleTask(t, v) },
            colors = if (t.prio == 1 && !t.done) CheckboxDefaults.colors(uncheckedColor = MaterialTheme.colorScheme.error) else CheckboxDefaults.colors(),
            // TalkBack: Aufgabentext + Zustand + Aktion "Erledigen"/"Wieder oeffnen" (N-07)
            modifier = Modifier.taskCheckSemantics(t.text, t.done) { repo.toggleTask(t, !t.done) },
        )
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(
                t.text, maxLines = 2, overflow = TextOverflow.Ellipsis,
                textDecoration = if (t.done) TextDecoration.LineThrough else null,
                color = if (t.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            val extra = listOfNotNull(
                t.due?.takeIf { it.isNotEmpty() && !t.done }?.let { dueText(it) },
                t.rep?.let { stringResource(R.string.task_repeats) },
                if (t.prio == 1) stringResource(R.string.task_high_priority) else null,
            )
            if (extra.isNotEmpty()) {
                Text(
                    extra.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                    color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Schnell-Eingabe: Text + optionale Faelligkeit */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickAddTask(
    repo: SagaRepository,
    modifier: Modifier = Modifier,
    defaultDue: String? = null,
    /** Widget "Neue Aufgabe": Feld fokussieren und Tastatur zeigen */
    focus: Boolean = false,
    onFocused: () -> Unit = {},
) {
    val focusRequester = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    androidx.compose.runtime.LaunchedEffect(focus) {
        if (focus) {
            kotlinx.coroutines.delay(250)                  // erst nach dem Einblenden der Liste
            try {
                focusRequester.requestFocus()
                keyboard?.show()
            } catch (_: IllegalStateException) {
            }
            onFocused()
        }
    }
    var text by remember { mutableStateOf("") }
    var due by remember { mutableStateOf(defaultDue) }
    var pick by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    fun add() {
        val t = text.trim()
        if (t.isEmpty() || busy) return
        busy = true
        val d = due
        text = ""
        due = defaultDue
        repo.launchAction(onError = { text = t; due = d; busy = false }, onSuccess = { busy = false }) { repo.addTask(t, d) }
    }
    Column(modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        OutlinedTextField(
            value = text, onValueChange = { text = it }, placeholder = { Text(stringResource(R.string.task_new_placeholder)) },
            singleLine = true, modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { add() }),
            trailingIcon = { IconButton(onClick = { add() }, enabled = text.isNotBlank() && !busy) { Icon(Icons.Filled.Add, stringResource(R.string.action_add)) } },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            AssistChip(
                onClick = { pick = true },
                label = { Text(due?.let { stringResource(R.string.task_due, dueText(it)) } ?: stringResource(R.string.task_due_chip)) },
                leadingIcon = { Icon(Icons.Filled.DateRange, null) },
            )
            if (due != null) IconButton(onClick = { due = null }) { Icon(Icons.Filled.Clear, stringResource(R.string.task_due_remove)) }
        }
    }
    if (pick) DueDatePicker(due, onDismiss = { pick = false }) { due = it; pick = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DueDatePicker(initial: String?, onDismiss: () -> Unit, onPick: (String?) -> Unit) {
    val initMillis = initial?.let { runCatching { LocalDate.parse(it).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }.getOrNull() }
        ?: LocalDate.now().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    val state = rememberDatePickerState(initialSelectedDateMillis = initMillis)
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                onPick(state.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString() })
            }) { Text(stringResource(R.string.action_apply)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    ) { DatePicker(state) }
}

/** Aufgabe bearbeiten: Text, Faelligkeit, Prioritaet, loeschen */
@Composable
fun TaskEditDialog(t: TaskItem, repo: SagaRepository, onClose: () -> Unit) {
    var text by remember { mutableStateOf(t.text) }
    var due by remember { mutableStateOf(t.due?.takeIf { it.isNotEmpty() }) }
    var prio by remember { mutableIntStateOf(t.prio ?: 2) }
    var pick by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    /** Dialog sofort schliessen, Aenderung im App-Scope speichern (Fehler als Meldung unten) */
    fun run(block: suspend () -> Unit) {
        onClose()
        repo.launchAction(block = block)
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.task_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text(stringResource(R.string.task_title)) }, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AssistChip(onClick = { pick = true }, label = { Text(due?.let { stringResource(R.string.task_due, dueText(it)) } ?: stringResource(R.string.task_no_due)) },
                        leadingIcon = { Icon(Icons.Filled.DateRange, null) })
                    if (due != null) IconButton(onClick = { due = null }) { Icon(Icons.Filled.Clear, stringResource(R.string.task_due_remove)) }
                }
                Text(stringResource(R.string.task_priority), style = MaterialTheme.typography.labelLarge)
                val labels = listOf(1 to stringResource(R.string.prio_high), 2 to stringResource(R.string.prio_normal), 3 to stringResource(R.string.prio_low))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    labels.forEachIndexed { i, (v, l) ->
                        SegmentedButton(selected = prio == v, onClick = { prio = v }, shape = SegmentedButtonDefaults.itemShape(i, labels.size)) { Text(l) }
                    }
                }
                if (t.rep != null) Text(stringResource(R.string.task_repeat_info, repeatName(t.rep)), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { confirmDelete = true }) { Text(stringResource(R.string.task_delete), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = { run { repo.updateTask(t.id, text.trim(), due, prio) } }, enabled = text.isNotBlank()) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.action_cancel)) } },
    )
    if (pick) DueDatePicker(due, onDismiss = { pick = false }) { due = it; pick = false }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.task_delete_confirm)) },
            confirmButton = { TextButton(onClick = { confirmDelete = false; run { repo.deleteTask(t.id) } }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
fun repeatName(r: String?): String = stringResource(
    when (r) {
        "daily" -> R.string.repeat_daily
        "weekdays" -> R.string.repeat_weekdays
        "weekly" -> R.string.repeat_weekly
        "monthly" -> R.string.repeat_monthly
        "yearly" -> R.string.repeat_yearly
        else -> R.string.repeat_never
    }
)
