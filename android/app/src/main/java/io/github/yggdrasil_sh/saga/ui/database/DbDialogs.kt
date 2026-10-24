package io.github.yggdrasil_sh.saga.ui.database

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.data.SagaRepository
import io.github.yggdrasil_sh.saga.db.DbFilter
import io.github.yggdrasil_sh.saga.db.DbProp
import io.github.yggdrasil_sh.saga.db.DbSchema
import io.github.yggdrasil_sh.saga.db.DbSort
import io.github.yggdrasil_sh.saga.db.DbView
import io.github.yggdrasil_sh.saga.db.DbViews
import io.github.yggdrasil_sh.saga.ui.tasks.DueDatePicker

/** Dialoge der Datenbank-Ansicht - ausserhalb der LazyColumn (beim Scrollen bleiben sie offen) */
@Composable
fun DatabaseDialogs(st: DbViewState, repo: SagaRepository) {
    st.editing?.let { (row, prop) ->
        val cur = st.rows.firstOrNull { it.id == row.id } ?: st.monthRows.firstOrNull { it.id == row.id } ?: row
        PropValueEditor(
            p = prop, value = cur.props[prop.id],
            onSet = { v -> st.setProp(cur, prop, v) },
            onDismiss = { st.editing = null },
            onNewOption = if (st.readOnly) null else { name -> st.addOption(cur, prop, name, prop.type == "multi_select") },
            relationRows = prop.target?.let { t -> { q: String -> repo.dbStore.search(t, q) } },
        )
    }
    val s = st.schema ?: return
    val v = s.view(st.viewId)
    when (st.sheet) {
        "filter" -> FilterDialog(s, v, st.canEditViews, onApply = { st.setFilters(it) }, onDismiss = { st.sheet = null })
        "sort" -> SortDialog(s, v, st.canEditViews, onApply = { st.setSorts(it) }, onDismiss = { st.sheet = null })
        "view" -> ViewDialog(st, s, v, onDismiss = { st.sheet = null })
        "new_view" -> NewViewDialog(onCreate = { type, name -> st.addView(type, name) }, onDismiss = { st.sheet = null })
    }
}

/** Auswahlfeld (Dropdown) mit Beschriftung */
@Composable
fun <T> Picker(label: String, items: List<Pair<T, String>>, selected: T, onPick: (T) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text(items.firstOrNull { it.first == selected }?.second ?: label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Icon(Icons.Filled.ArrowDropDown, label)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            items.forEach { (k, t) -> DropdownMenuItem(text = { Text(t) }, onClick = { open = false; onPick(k) }) }
        }
    }
}

@Composable
fun opLabel(op: String, type: String): String = when (op) {
    "contains" -> stringResource(R.string.dbv_op_contains)
    "not_contains" -> stringResource(R.string.dbv_op_not_contains)
    "is_empty" -> stringResource(R.string.dbv_op_is_empty)
    "not_empty" -> stringResource(R.string.dbv_op_not_empty)
    "is" -> stringResource(R.string.dbv_op_is)
    "is_not" -> stringResource(R.string.dbv_op_is_not)
    "eq" -> if (type == "number" || type == "calc") "=" else stringResource(R.string.dbv_op_on)
    "gt" -> ">"
    "lt" -> "<"
    "before" -> stringResource(R.string.dbv_op_before)
    "after" -> stringResource(R.string.dbv_op_after)
    "today" -> stringResource(R.string.dbv_op_today)
    "this_week" -> stringResource(R.string.dbv_op_this_week)
    "this_month" -> stringResource(R.string.dbv_op_this_month)
    "checked" -> stringResource(R.string.dbv_op_checked)
    "unchecked" -> stringResource(R.string.dbv_op_unchecked)
    else -> op
}

/** Spalten zum Filtern/Sortieren: Name + alle Eigenschaften */
@Composable
private fun propChoices(s: DbSchema): List<Pair<String, String>> =
    listOf("title" to (s.titleName ?: stringResource(R.string.dbv_name_col))) + s.props.map { it.id to it.name }

@Composable
private fun FilterDialog(s: DbSchema, v: DbView, editable: Boolean, onApply: (List<DbFilter>) -> Unit, onDismiss: () -> Unit) {
    val fs = remember(v.id) { mutableStateListOf<DbFilter>().apply { addAll(v.filters) } }
    val choices = propChoices(s)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dbv_filter)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!editable) Text(stringResource(R.string.dbv_locked), style = MaterialTheme.typography.bodySmall)
                fs.forEachIndexed { i, f ->
                    val p = s.prop(f.pid) ?: DbProp.TITLE
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Picker(stringResource(R.string.dbv_view_name), choices, f.pid, enabled = editable, onPick = { pid ->
                                val t = s.prop(pid)?.type ?: "text"
                                fs[i] = DbFilter(pid, DbFilter.ops(t).first(), "")
                            }, modifier = Modifier.weight(1f))
                            if (editable) IconButton(onClick = { fs.removeAt(i) }) { Icon(Icons.Filled.Close, stringResource(R.string.dbv_remove)) }
                        }
                        Picker("", DbFilter.ops(p.type).map { it to opLabel(it, p.type) }, f.op, enabled = editable, onPick = { op -> fs[i] = f.copy(op = op) }, modifier = Modifier.fillMaxWidth())
                        if (!f.noValue) FilterValue(p, f, editable) { fs[i] = f.copy(value = it) }
                    }
                    HorizontalDivider()
                }
                if (editable && fs.size < 50) {
                    TextButton(onClick = { fs.add(DbFilter("title", DbFilter.ops("text").first(), "")) }) {
                        Icon(Icons.Filled.Add, null)
                        Text(stringResource(R.string.dbv_add_filter))
                    }
                }
            }
        },
        confirmButton = {
            if (editable) TextButton(onClick = { onApply(fs.toList()); onDismiss() }) { Text(stringResource(R.string.action_apply)) }
            else TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
        dismissButton = { if (editable) TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Wert eines Filters: Auswahl aus den Optionen, Datum per Kalender, sonst Text/Zahl */
@Composable
private fun FilterValue(p: DbProp, f: DbFilter, editable: Boolean, onChange: (String) -> Unit) {
    when (p.type) {
        "select", "multi_select" -> Picker(stringResource(R.string.dbv_value_empty), p.options.map { it.id to it.name }, f.value, enabled = editable, onPick = onChange, modifier = Modifier.fillMaxWidth())
        "date", "created", "edited" -> {
            var pick by remember { mutableStateOf(false) }
            OutlinedButton(onClick = { pick = true }, enabled = editable, modifier = Modifier.fillMaxWidth()) {
                Text(f.value.ifEmpty { stringResource(R.string.dbv_value_empty) })
            }
            if (pick) DueDatePicker(f.value.ifEmpty { null }, onDismiss = { pick = false }) { onChange(it.orEmpty()); pick = false }
        }
        "relation" -> {}
        else -> {
            val num = p.type == "number" || p.type == "calc"
            var text by remember(f.pid) { mutableStateOf(f.value) }
            OutlinedTextField(
                value = text, enabled = editable, singleLine = true, modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.dbv_filter_value)) },          // Nr. 28: Feld beschriften
                onValueChange = { t ->
                    text = t.take(200)
                    // Zahlenfilter: Eingabe in der Sprache der Oberflaeche, gespeichert als "1200.5" (wie numStore im Web)
                    onChange(if (num) io.github.yggdrasil_sh.saga.db.DbValues.parseNumber(text)?.let { io.github.yggdrasil_sh.saga.db.DbQuery.numText(it) } ?: text.trim() else text)
                },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = if (num) androidx.compose.ui.text.input.KeyboardType.Decimal else androidx.compose.ui.text.input.KeyboardType.Text),
            )
        }
    }
}

@Composable
private fun SortDialog(s: DbSchema, v: DbView, editable: Boolean, onApply: (List<DbSort>) -> Unit, onDismiss: () -> Unit) {
    val ss = remember(v.id) { mutableStateListOf<DbSort>().apply { addAll(v.sorts) } }
    val choices = propChoices(s)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dbv_sort)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ss.forEachIndexed { i, x ->
                    // N-06: Eigenschaft und Richtung untereinander in voller Breite (nebeneinander abgeschnitten: "Dat…", "Abs…")
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Picker("", choices, x.pid, enabled = editable, onPick = { ss[i] = x.copy(pid = it) }, modifier = Modifier.fillMaxWidth())
                            Picker("", listOf(false to stringResource(R.string.dbv_asc), true to stringResource(R.string.dbv_desc)), x.desc, enabled = editable,
                                onPick = { ss[i] = x.copy(desc = it) }, modifier = Modifier.fillMaxWidth())
                        }
                        if (editable) IconButton(onClick = { ss.removeAt(i) }) { Icon(Icons.Filled.Close, stringResource(R.string.dbv_remove)) }
                    }
                }
                if (editable && ss.size < 3) {
                    TextButton(onClick = { ss.add(DbSort("title")) }) {
                        Icon(Icons.Filled.Add, null)
                        Text(stringResource(R.string.dbv_add_sort))
                    }
                }
            }
        },
        confirmButton = {
            if (editable) TextButton(onClick = { onApply(ss.toList()); onDismiss() }) { Text(stringResource(R.string.action_apply)) }
            else TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
        dismissButton = { if (editable) TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun ViewDialog(st: DbViewState, s: DbSchema, v: DbView, onDismiss: () -> Unit) {
    var name by remember(v.id) { mutableStateOf(v.name) }
    var confirmDelete by remember { mutableStateOf(false) }
    val selects = s.props.filter { it.type == "select" }
    val dates = s.props.filter { it.type == "date" || it.isAuto }
    AlertDialog(
        onDismissRequest = { onDismiss() },
        title = { Text(stringResource(R.string.dbv_view_settings)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it.take(40) }, singleLine = true, label = { Text(stringResource(R.string.dbv_view_name)) }, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.dbv_view_layout), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DbView.TYPES.forEach { t ->
                        FilterChip(selected = v.type == t, onClick = { st.editSchema { DbViews.setType(it, v.id, t) } }, label = { Text(viewIcon(t) + " " + viewLabel(t)) })
                    }
                }
                when (v.type) {
                    "board" -> if (selects.isNotEmpty()) {
                        Text(stringResource(R.string.dbv_view_group_by), style = MaterialTheme.typography.labelLarge)
                        Picker("", selects.map { it.id to it.name }, s.groupProp(v)?.id, onPick = { id -> st.editSchema { DbViews.setKey(it, v.id, "group_by", id) } }, modifier = Modifier.fillMaxWidth())
                    }
                    "table", "list" -> if (selects.isNotEmpty()) {
                        Text(stringResource(R.string.dbv_view_group), style = MaterialTheme.typography.labelLarge)
                        Picker("", listOf<Pair<String?, String>>(null to stringResource(R.string.dbv_view_no_group)) + selects.map { it.id to it.name }, v.group,
                            onPick = { id -> st.editSchema { DbViews.setKey(it, v.id, "group", id) } }, modifier = Modifier.fillMaxWidth())
                    }
                    "calendar" -> if (dates.isNotEmpty()) {
                        Text(stringResource(R.string.dbv_view_date_by), style = MaterialTheme.typography.labelLarge)
                        Picker("", dates.map { it.id to it.name }, s.dateProp(v)?.id, onPick = { id -> st.editSchema { DbViews.setKey(it, v.id, "date_by", id) } }, modifier = Modifier.fillMaxWidth())
                    }
                }
                Text(stringResource(R.string.dbv_view_props), style = MaterialTheme.typography.labelLarge)
                val hidden = v.hidden
                s.props.forEach { p ->
                    val on = p.id !in hidden
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(role = Role.Checkbox) { st.editSchema { DbViews.setHidden(it, v.id, p.id, on) } },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = on, onCheckedChange = null)
                        Text(p.name + "  ·  " + typeName(p.type), modifier = Modifier.padding(start = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (s.views.size > 1) {
                    HorizontalDivider()
                    TextButton(onClick = { confirmDelete = true }) { Text(stringResource(R.string.dbv_view_delete), color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.trim() != v.name && name.isNotBlank()) st.editSchema { DbViews.rename(it, v.id, name) }
                onDismiss()
            }) { Text(stringResource(R.string.action_apply)) }
        },
    )
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            text = { Text(stringResource(R.string.dbv_view_delete_q, v.name.ifEmpty { viewLabel(v.type) })) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDismiss(); st.editSchema { DbViews.delete(it, v.id) } }) {
                    Text(stringResource(R.string.dbv_view_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun NewViewDialog(onCreate: (String, String) -> Unit, onDismiss: () -> Unit) {
    var type by remember { mutableStateOf("table") }
    val defaultName = viewLabel(type)
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dbv_new_view)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DbView.TYPES.forEach { t -> FilterChip(selected = type == t, onClick = { type = t }, label = { Text(viewIcon(t) + " " + viewLabel(t)) }) }
                }
                OutlinedTextField(name, { name = it.take(40) }, singleLine = true, label = { Text(stringResource(R.string.dbv_view_name)) },
                    placeholder = { Text(defaultName) }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { onCreate(type, name.ifBlank { defaultName }); onDismiss() }) { Text(stringResource(R.string.action_apply)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
