package io.github.yggdrasil_sh.saga.ui.database

import io.github.yggdrasil_sh.saga.ui.theme.SagaOutlinedButton
import io.github.yggdrasil_sh.saga.ui.theme.SagaTextButton
import io.github.yggdrasil_sh.saga.ui.theme.SagaIcons
import io.github.yggdrasil_sh.saga.ui.theme.SagaIcon
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.db.DbFiles
import io.github.yggdrasil_sh.saga.db.DbPerson
import io.github.yggdrasil_sh.saga.db.DbProp
import io.github.yggdrasil_sh.saga.db.DbValues
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

// 2.5 Etappe 1: Personen, Dateien, Datum mit Uhrzeit/Zeitraum - Anzeige und Bearbeiten in Tabelle/Karte/Eintrag.

/** Dateien einer Zelle: Name als Chip (Symbol Bild bzw. Bueroklammer), Antippen oeffnet */
@Composable
fun FilesView(v: JsonElement?) {
    val files = DbFiles.parse(v)
    val repo = LocalDbRepo.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        files.forEach { f ->
            Row(
                Modifier.clickable(enabled = repo != null) { repo?.let { io.github.yggdrasil_sh.saga.ui.common.openUrl(ctx, it.absoluteUrl(f.url)) } }
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SagaIcon(if (f.image) SagaIcons.image else SagaIcons.paperclip, null, Modifier.padding(end = 4.dp).size(14.dp))
                Text(f.name.ifEmpty { "–" }, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Person waehlen: Suche ueber GET /api/notes/{db}/people?q= (nur Personen mit Zugriff); multi: mehrere */
@Composable
fun PersonDialog(
    p: DbProp, value: JsonElement?, fmt: DbFormat, search: suspend (String) -> List<DbPerson>,
    onSet: (JsonElement?) -> Unit, onDismiss: () -> Unit,
) {
    var cur by remember { mutableStateOf(DbValues.ids(value)) }
    var q by remember { mutableStateOf("") }
    var found by remember { mutableStateOf<List<DbPerson>?>(null) }
    var known by remember { mutableStateOf<Map<String, DbPerson>>(emptyMap()) }
    LaunchedEffect(q) {
        kotlinx.coroutines.delay(250)
        found = try {
            search(q.trim()).also { l -> known = known + l.associateBy { it.ref } }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
    }
    fun name(ref: String) = known[ref]?.name?.ifBlank { null } ?: fmt.personName(ref)
    fun toValue(): JsonElement = if (cur.isEmpty()) JsonNull else JsonArray(cur.map(::JsonPrimitive))
    // ED-24: ausserhalb tippen verwirft (mit "Rückgängig", falls die Auswahl geaendert war)
    val discard = rememberDbDlgDiscard()
    val changed = p.multi && DbDlg.changed(DbValues.ids(value), cur)
    AlertDialog(
        onDismissRequest = { discard.dismiss(changed, close = onDismiss, undo = { onSet(toValue()) }) },
        title = { Text(p.name) },
        text = {
            Column {
                if (cur.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                    cur.forEach { r ->
                        FilterChip(selected = true, onClick = { cur = cur - r }, label = { Text(name(r)) },
                            trailingIcon = { SagaIcon(SagaIcons.x, stringResource(R.string.e1_person_remove, name(r)), Modifier.size(16.dp)) })
                    }
                }
                OutlinedTextField(q, { q = it.take(80) }, singleLine = true, placeholder = { Text(stringResource(R.string.e1_person_search)) }, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.heightIn(max = 300.dp).padding(top = 8.dp)) {
                    val l = found
                    if (l == null) item { CircularProgressIndicator(Modifier.size(20.dp).padding(8.dp), strokeWidth = 2.dp) }
                    else if (l.isEmpty()) item { Text(stringResource(R.string.e1_person_none), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp)) }
                    else items(l, key = { it.ref }) { x ->
                        // ED-25: Radio (eine Person) bzw. Checkbox (mehrere); eine Person uebernimmt sofort
                        DbDlgOptionRow(p.multi, selected = x.ref in cur, onClick = {
                            if (p.multi) cur = if (x.ref in cur) cur - x.ref else (cur + x.ref).take(20)
                            else { onSet(JsonArray(listOf(JsonPrimitive(x.ref)))); onDismiss() }
                        }) {
                            Text(x.name.ifBlank { "–" } + if (x.me) " · " + stringResource(R.string.e1_person_me) else "", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        },
        confirmButton = {
            DbDlgButtons(
                onCancel = onDismiss,
                clearLabel = stringResource(R.string.dbv_clear),
                onClear = if (DbValues.ids(value).isNotEmpty()) ({ onSet(JsonNull); onDismiss() }) else null,
                onConfirm = if (p.multi) ({ onSet(toValue()); onDismiss() }) else null,
            )
        },
    )
    DbDlgDiscardAsk(discard, close = onDismiss)
}

/** Dateien bearbeiten: Liste (entfernen), "Datei hinzufuegen" laedt hoch (POST /api/files?note=) und setzt die ganze Liste */
@Composable
fun FilesDialog(
    p: DbProp, value: JsonElement?, upload: suspend (Uri) -> DbFiles.File?, onSet: (JsonElement?) -> Unit, onDismiss: () -> Unit,
) {
    var cur by remember { mutableStateOf(DbFiles.parse(value)) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        failed = false
        scope.launch {
            try {
                upload(uri)?.let { f -> cur = (cur + f).take(p.max) } ?: run { failed = true }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                failed = true
            } finally {
                busy = false
            }
        }
    }
    val discard = rememberDbDlgDiscard()
    val changed = DbDlg.changed(DbFiles.parse(value).map { it.url }, cur.map { it.url })
    AlertDialog(
        onDismissRequest = { if (!busy) discard.dismiss(changed, close = onDismiss, undo = { onSet(DbFiles.toValue(cur)) }) },
        title = { Text(p.name) },
        text = {
            Column {
                if (cur.isEmpty()) Text(stringResource(R.string.dbv_value_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                cur.forEach { f ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SagaIcon(if (f.image) SagaIcons.image else SagaIcons.paperclip, null, Modifier.padding(end = 8.dp).size(18.dp))
                        Text(f.name, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        IconButton(onClick = { cur = cur - f }) { SagaIcon(SagaIcons.x, stringResource(R.string.e1_file_remove, f.name)) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                    SagaOutlinedButton(onClick = { pick.launch("*/*") }, enabled = !busy && cur.size < p.max) {
                        SagaIcon(SagaIcons.plus, null)
                        Text(stringResource(R.string.e1_file_add))
                    }
                    if (busy) CircularProgressIndicator(Modifier.padding(start = 10.dp).size(20.dp), strokeWidth = 2.dp)
                }
                if (failed) Text(stringResource(R.string.image_upload_failed), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            DbDlgButtons(onCancel = onDismiss, onConfirm = { onSet(DbFiles.toValue(cur)); onDismiss() }, confirmEnabled = !busy)
        },
    )
    DbDlgDiscardAsk(discard, close = onDismiss)
}

// Datum mit Uhrzeit / Zeitraum (datetime1): seit Android-Design-Runde 3 in DbDlgDate.kt (DbDlgDateDialog, AND-ED-26).
