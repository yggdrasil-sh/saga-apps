package io.github.yggdrasil_sh.saga.ui.tasks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.data.SagaRepository
import io.github.yggdrasil_sh.saga.data.TaskEntry
import io.github.yggdrasil_sh.saga.data.TaskPerson
import io.github.yggdrasil_sh.saga.data.TaskSource
import io.github.yggdrasil_sh.saga.data.TaskSourcesLogic
import io.github.yggdrasil_sh.saga.ui.common.dueText
import kotlinx.coroutines.CancellationException

/** Zeile der zusammengefuehrten Liste: Haken (ausgegraut bei edit false), Text, Quelle, Datum/Uhrzeit/Zeitraum, Prioritaet, Zugewiesene */
@Composable
fun SourceTaskRow(e: TaskEntry, source: TaskSource?, people: Map<String, TaskPerson>, repo: SagaRepository, onOpen: (TaskEntry) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .clickable(onClickLabel = stringResource(if (e.isRow) R.string.e2_task_open_row else R.string.task_edit)) { onOpen(e) }
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = e.done, enabled = e.edit,
            onCheckedChange = { v -> repo.toggleEntry(e, v) },
            colors = if (e.prio == 1 && !e.done) CheckboxDefaults.colors(uncheckedColor = MaterialTheme.colorScheme.error) else CheckboxDefaults.colors(),
            modifier = Modifier.taskCheckSemantics(e.text, e.done) { repo.toggleEntry(e, !e.done) },
        )
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(
                e.text.ifBlank { stringResource(R.string.page_untitled) }, maxLines = 2, overflow = TextOverflow.Ellipsis,
                textDecoration = if (e.done) TextDecoration.LineThrough else null,
                color = if (e.done || !e.edit) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            val extra = listOfNotNull(
                source?.let { (it.icon.ifEmpty { "☑️" }) + " " + it.title.ifBlank { stringResource(R.string.page_untitled) } },
                dueLabel(e),
                e.repeat?.let { stringResource(R.string.task_repeats) },
                if (e.prio == 1) stringResource(R.string.task_high_priority) else null,
                TaskSourcesLogic.assigneeNames(e, people).takeIf { it.isNotEmpty() }?.joinToString(", ")?.let { "👤 $it" },
                if (!e.edit) stringResource(R.string.e2_task_readonly) else null,
            )
            if (extra.isNotEmpty()) {
                Text(
                    extra.joinToString(" · "), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = if (e.overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** "Morgen 09:30" bzw. "28.10. – 30.10." (Zeitraum) */
@Composable
private fun dueLabel(e: TaskEntry): String? {
    val d = e.due ?: return null
    val base = dueText(d)
    return when {
        e.dueEnd != null && e.dueEnd != d -> "$base – ${dueText(e.dueEnd)}"
        e.time != null -> "$base ${e.time}"
        else -> base
    }
}

/** Ziel einer neuen Aufgabe: Inbox (null) oder eine Quelle */
@Composable
fun TargetChip(targets: List<TaskSource>, current: Long?, onPick: (Long?) -> Unit) {
    if (targets.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    val cur = targets.firstOrNull { it.db == current }
    androidx.compose.foundation.layout.Box {
        AssistChip(
            onClick = { open = true },
            label = { Text(cur?.let { (it.icon.ifEmpty { "☑️" }) + " " + it.title } ?: stringResource(R.string.e2_task_inbox), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, null) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.e2_task_inbox)) }, onClick = { open = false; onPick(null) })
            targets.forEach { s ->
                DropdownMenuItem(text = { Text((s.icon.ifEmpty { "☑️" }) + " " + s.title.ifBlank { stringResource(R.string.page_untitled) }) }, onClick = { open = false; onPick(s.db) })
            }
        }
    }
}

/** Quellen verwalten: Liste, scope all/assigned umschalten, Abo entfernen */
@Composable
fun TaskSourcesDialog(repo: SagaRepository, onOpen: (Long) -> Unit, onClose: () -> Unit) {
    var list by remember { mutableStateOf<List<TaskSource>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }
    val errText = stringResource(R.string.e2_task_sources_failed)
    LaunchedEffect(reload) {
        try {
            list = repo.taskSources()
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            error = errText
        }
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.e2_task_sources)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                val l = list
                if (l != null && l.isEmpty()) Text(stringResource(R.string.e2_task_sources_empty), style = MaterialTheme.typography.bodyMedium)
                if (l != null) LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(l, key = { it.db }) { s ->
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    (s.icon.ifEmpty { "☑️" }) + " " + s.title.ifBlank { stringResource(R.string.page_untitled) },
                                    style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f).clickable { onClose(); onOpen(s.db) },
                                )
                                IconButton(onClick = {
                                    repo.launchAction(onSuccess = { reload++ }) { repo.removeTaskSource(s.db) }
                                }) { Icon(Icons.Filled.Delete, stringResource(R.string.e2_task_source_remove)) }
                            }
                            val state = when {
                                s.inactive -> stringResource(R.string.e2_task_source_inactive)
                                s.unmapped -> stringResource(R.string.e2_task_source_unmapped)
                                s.noAssignee -> stringResource(R.string.e2_task_source_no_assignee)
                                else -> null
                            }
                            state?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                            if (s.canAssigned && !s.inactive && !s.unmapped) {
                                val opts = listOf("all" to stringResource(R.string.e2_task_scope_all), "assigned" to stringResource(R.string.e2_task_scope_assigned))
                                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                                    opts.forEachIndexed { i, (v, label) ->
                                        SegmentedButton(selected = s.scope == v, shape = SegmentedButtonDefaults.itemShape(i, opts.size), onClick = {
                                            if (s.scope != v) repo.launchAction(onSuccess = { reload++ }) { repo.setTaskSourceScope(s.db, v) }
                                        }) { Text(label, maxLines = 1) }
                                    }
                                }
                            }
                        }
                    }
                }
                Text(stringResource(R.string.e2_task_sources_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.action_done)) } },
    )
}
