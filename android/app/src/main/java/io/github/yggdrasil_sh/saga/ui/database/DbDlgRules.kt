package io.github.yggdrasil_sh.saga.ui.database

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.blocks.DbProps
import io.github.yggdrasil_sh.saga.data.SagaRepository
import io.github.yggdrasil_sh.saga.db.DbFilter
import io.github.yggdrasil_sh.saga.db.DbProp
import io.github.yggdrasil_sh.saga.db.DbQuery
import io.github.yggdrasil_sh.saga.db.DbSchema
import io.github.yggdrasil_sh.saga.db.DbSort
import io.github.yggdrasil_sh.saga.db.DbValues
import io.github.yggdrasil_sh.saga.db.DbView
import io.github.yggdrasil_sh.saga.ui.common.appLocale
import io.github.yggdrasil_sh.saga.ui.tasks.DueDatePicker
import io.github.yggdrasil_sh.saga.ui.theme.SagaButton
import io.github.yggdrasil_sh.saga.ui.theme.SagaIcon
import io.github.yggdrasil_sh.saga.ui.theme.SagaIcons
import io.github.yggdrasil_sh.saga.ui.theme.SagaOutlinedButton
import io.github.yggdrasil_sh.saga.ui.theme.SagaTextButton

// AND-ED-23: Filter und Sortieren als Bottom Sheet (Phone) bzw. Dialog (ab 600 dp), eine OutlinedCard je Regel mit
// Klartext-Kopf und ✕ oben rechts, Felder als ExposedDropdownMenuBox, unten "+ … hinzufügen" und "Übernehmen".
// ED-24: Wischen/ausserhalb tippen verwirft; bei Aenderungen "Änderungen verworfen · Rückgängig".

/** Spalten zum Filtern/Sortieren: Name + alle Eigenschaften */
@Composable
private fun propChoices(s: DbSchema): List<Pair<String, String>> =
    listOf("title" to (s.titleName ?: stringResource(R.string.dbv_name_col))) + s.props.map { it.id to it.name }

/** Bezeichnung einer Status-Gruppe (Filter "Status-Gruppe ist") */
@Composable
private fun statusGroupLabels(): List<Pair<String, String>> = listOf(
    "open" to stringResource(R.string.e1_status_open), "todo" to stringResource(R.string.e1_status_todo),
    "doing" to stringResource(R.string.e1_status_doing), "done" to stringResource(R.string.e1_status_done),
)

/** Behaelter: unter 600 dp ModalBottomSheet, sonst ein Dialog mit demselben Inhalt */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DbDlgRuleSheet(title: String, onDismiss: () -> Unit, footer: @Composable RowScope.() -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val compact = LocalConfiguration.current.screenWidthDp < 600
    val body = @Composable {
        Column(Modifier.fillMaxWidth()) {
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = if (compact) 0.dp else 20.dp, bottom = 8.dp)
                .semantics { heading() })
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp), content = content,
            )
            // R3-Nachbesserung (32): gleiche Raender wie die Karten (16 dp) - rechts die Knopfkante, links das "+" des
            // Textknopfs (der Textknopf hat selbst 12 dp Innenabstand)
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) { footer() }
        }
    }
    if (compact) {
        ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.navigationBarsPadding()) { body() }
        }
    } else {
        Dialog(onDismissRequest = onDismiss) {
            // R3-Nachbesserung (82): kein Autofokus - ohne Touch-Modus (Tablet mit Tastatur/Maus) setzte das Fenster den
            // Fokus auf das erste Feld, das dann umrandet startete. Die Tastatur-Navigation bleibt moeglich.
            val focus = androidx.compose.ui.platform.LocalFocusManager.current
            androidx.compose.runtime.LaunchedEffect(Unit) {
                androidx.compose.runtime.withFrameNanos { }
                androidx.compose.runtime.withFrameNanos { }
                focus.clearFocus()
            }
            Surface(Modifier.widthIn(max = 560.dp).heightIn(max = 640.dp), shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) { body() }
        }
    }
}

/** Eine Regel als Karte: Klartext-Kopf, ✕ oben rechts (nur bearbeitbar), darunter die Felder */
@Composable
private fun RuleCard(headline: String, editable: Boolean, onRemove: () -> Unit, fields: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 12.dp)) {
            Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(headline, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).semantics { heading() })
                if (editable) IconButton(onClick = onRemove) { SagaIcon(SagaIcons.x, stringResource(R.string.r3d_remove_rule, headline)) }
            }
            Column(Modifier.padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = fields)
        }
    }
}

@Composable
internal fun FilterSheet(
    s: DbSchema, v: DbView, editable: Boolean, features: Set<String>, repo: SagaRepository?,
    onApply: (List<DbFilter>) -> Unit, onDismiss: () -> Unit,
) {
    val fs = remember(v.id) { mutableStateListOf<DbFilter>().apply { addAll(v.filters) } }
    val choices = propChoices(s)
    val groups = statusGroupLabels()
    val locale = appLocale()
    val day = remember(locale) { DbProps.localDay(locale) }
    val discard = rememberDbDlgDiscard(repo)
    val changed = editable && DbDlg.rulesChanged(v.filters, fs.toList())
    DbDlgRuleSheet(
        stringResource(R.string.dbv_filter),
        onDismiss = { discard.dismiss(changed, close = onDismiss, undo = { onApply(fs.toList()) }) },
        footer = {
            if (editable && fs.size < 50) SagaTextButton(onClick = { fs.add(DbFilter("title", DbFilter.ops("text").first(), "")) }) {
                PlusLabel(stringResource(R.string.dbv_add_filter))
            }
            Spacer(Modifier.weight(1f))
            if (editable) SagaButton(onClick = { onApply(fs.toList()); onDismiss() }) { Text(stringResource(R.string.action_apply)) }
            else SagaTextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        },
    ) {
        if (!editable) Text(stringResource(R.string.dbv_locked), style = MaterialTheme.typography.bodySmall)
        if (fs.isEmpty()) Text(stringResource(R.string.r3d_no_filters), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
        fs.forEachIndexed { i, f ->
          // Zustand je Karte (Textfeld) neu, sobald eine Regel wegfaellt oder dazukommt - sonst zeigte nach "✕" die
          // naechste Karte den Text der entfernten Regel und Tippen ueberschriebe ihren Wert
          androidx.compose.runtime.key(i, fs.size) {
            val p = s.prop(f.pid) ?: DbProp.TITLE
            val pName = choices.firstOrNull { it.first == f.pid }?.second ?: f.pid
            val op = opLabel(f.op, p.effType)
            val value = DbDlg.filterValue(p, f, { g -> groups.firstOrNull { it.first == g }?.second ?: g }, day)
            RuleCard(DbDlg.filterHeadline(pName, op, value), editable, onRemove = { fs.removeAt(i) }) {
                DbDlgDropdown(stringResource(R.string.dbv_filter_prop), choices, f.pid, enabled = editable, onPick = { pid ->
                    if (pid != f.pid) fs[i] = DbFilter(pid, DbFilter.ops(s.prop(pid) ?: DbProp.TITLE, features).first(), "")
                })
                // unbekannter Operator (neuere Version) bleibt waehlbar und unveraendert
                val ops = DbFilter.ops(p, features).let { if (f.op in it) it else it + f.op }
                DbDlgDropdown(stringResource(R.string.dbv_filter_cond), ops.map { it to opLabel(it, p.effType) }, f.op, enabled = editable,
                    onPick = { o -> fs[i] = f.copy(op = o) })
                if (!f.noValue) FilterValueField(p, f, editable, groups, day) { fs[i] = f.copy(value = it) }
            }
          }
        }
    }
    DbDlgDiscardAsk(discard, close = onDismiss)
}

/** Wert eines Filters: Auswahl aus den Optionen, Datum per Kalender, sonst Text/Zahl */
@Composable
private fun FilterValueField(p: DbProp, f: DbFilter, editable: Boolean, groups: List<Pair<String, String>>, day: (String) -> String, onChange: (String) -> Unit) {
    val label = stringResource(R.string.dbv_filter_value)
    if (f.op == "status_group") {
        DbDlgDropdown(label, groups, f.value, enabled = editable, onPick = onChange)
        return
    }
    if (f.op == "next_days" || f.op == "past_days") {
        var text by remember(f.pid, f.op) { mutableStateOf(f.value) }
        OutlinedTextField(text, { t -> text = t.filter { it.isDigit() }.take(3); onChange(text) }, enabled = editable, singleLine = true,
            label = { Text(stringResource(R.string.e1_filter_days)) }, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        return
    }
    // Person "enthaelt": Kennung waehlen nur im Web; "ist mir zugewiesen" (me) braucht keinen Wert
    if (p.type == "person") return
    when (p.effType) {
        "select", "multi_select" -> DbDlgDropdown(label, p.options.map { it.id to it.name }, f.value, enabled = editable, onPick = onChange)
        "date", "created", "edited" -> {
            var pick by remember { mutableStateOf(false) }
            SagaOutlinedButton(onClick = { pick = true }, enabled = editable, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                SagaIcon(SagaIcons.calendar, null)
                Text(
                    if (f.value.isEmpty()) label else runCatching { day(f.value) }.getOrDefault(f.value),
                    modifier = Modifier.weight(1f).padding(start = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (pick) DueDatePicker(f.value.ifEmpty { null }, onDismiss = { pick = false }) { onChange(it.orEmpty()); pick = false }
        }
        "relation" -> {}
        else -> {
            val num = p.type == "number" || p.type == "calc"
            var text by remember(f.pid) { mutableStateOf(f.value) }
            OutlinedTextField(
                value = text, enabled = editable, singleLine = true, modifier = Modifier.fillMaxWidth(),
                label = { Text(label) },
                onValueChange = { t ->
                    text = t.take(200)
                    // Zahlenfilter: Eingabe in der Sprache der Oberflaeche, gespeichert als "1200.5" (wie numStore im Web)
                    onChange(if (num) DbValues.parseNumber(text)?.let { DbQuery.numText(it) } ?: text.trim() else text)
                },
                keyboardOptions = KeyboardOptions(keyboardType = if (num) KeyboardType.Decimal else KeyboardType.Text),
            )
        }
    }
}

@Composable
internal fun SortSheet(
    s: DbSchema, v: DbView, editable: Boolean, repo: SagaRepository?,
    onApply: (List<DbSort>) -> Unit, onDismiss: () -> Unit,
) {
    val ss = remember(v.id) { mutableStateListOf<DbSort>().apply { addAll(v.sorts) } }
    val choices = propChoices(s)
    val dirs = listOf(false to stringResource(R.string.dbv_asc), true to stringResource(R.string.dbv_desc))
    val discard = rememberDbDlgDiscard(repo)
    val changed = editable && DbDlg.rulesChanged(v.sorts, ss.toList())
    DbDlgRuleSheet(
        stringResource(R.string.dbv_sort),
        onDismiss = { discard.dismiss(changed, close = onDismiss, undo = { onApply(ss.toList()) }) },
        footer = {
            if (editable && ss.size < 3) SagaTextButton(onClick = { ss.add(DbSort("title")) }) { PlusLabel(stringResource(R.string.dbv_add_sort)) }
            Spacer(Modifier.weight(1f))
            if (editable) SagaButton(onClick = { onApply(ss.toList()); onDismiss() }) { Text(stringResource(R.string.action_apply)) }
            else SagaTextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        },
    ) {
        if (!editable) Text(stringResource(R.string.dbv_locked), style = MaterialTheme.typography.bodySmall)
        if (ss.isEmpty()) Text(stringResource(R.string.r3d_no_sorts), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
        ss.forEachIndexed { i, x ->
            val pName = choices.firstOrNull { it.first == x.pid }?.second ?: x.pid
            val dir = dirs.first { it.first == x.desc }.second
            RuleCard(DbDlg.sortHeadline(pName, dir), editable, onRemove = { ss.removeAt(i) }) {
                DbDlgDropdown(stringResource(R.string.dbv_filter_prop), choices, x.pid, enabled = editable, onPick = { ss[i] = x.copy(pid = it) })
                DbDlgDropdown(stringResource(R.string.r3d_sort_dir), dirs, x.desc, enabled = editable, onPick = { ss[i] = x.copy(desc = it) })
            }
        }
    }
    DbDlgDiscardAsk(discard, close = onDismiss)
}
