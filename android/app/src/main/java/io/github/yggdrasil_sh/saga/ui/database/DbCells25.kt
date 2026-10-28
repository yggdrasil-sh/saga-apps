package io.github.yggdrasil_sh.saga.ui.database

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.db.DbDate
import io.github.yggdrasil_sh.saga.db.DbFiles
import io.github.yggdrasil_sh.saga.db.DbPerson
import io.github.yggdrasil_sh.saga.db.DbProp
import io.github.yggdrasil_sh.saga.db.DbValues
import io.github.yggdrasil_sh.saga.ui.common.appLocale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

// 2.5 Etappe 1: Personen, Dateien, Datum mit Uhrzeit/Zeitraum - Anzeige und Bearbeiten in Tabelle/Karte/Eintrag.

/** Dateien einer Zelle: Name als Chip (Bild mit 🖼), Antippen oeffnet */
@Composable
fun FilesView(v: JsonElement?) {
    val files = DbFiles.parse(v)
    val repo = LocalDbRepo.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        files.forEach { f ->
            Text(
                (if (f.image) "🖼 " else "📎 ") + f.name.ifEmpty { "–" }, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable(enabled = repo != null) { repo?.let { io.github.yggdrasil_sh.saga.ui.common.openUrl(ctx, it.absoluteUrl(f.url)) } }
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            )
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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(p.name) },
        text = {
            Column {
                if (cur.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                    cur.forEach { r ->
                        FilterChip(selected = true, onClick = { cur = cur - r }, label = { Text(name(r)) },
                            trailingIcon = { Icon(Icons.Filled.Close, stringResource(R.string.e1_person_remove, name(r)), Modifier.size(16.dp)) })
                    }
                }
                OutlinedTextField(q, { q = it.take(80) }, singleLine = true, placeholder = { Text(stringResource(R.string.e1_person_search)) }, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.heightIn(max = 300.dp).padding(top = 8.dp)) {
                    val l = found
                    if (l == null) item { CircularProgressIndicator(Modifier.size(20.dp).padding(8.dp), strokeWidth = 2.dp) }
                    else if (l.isEmpty()) item { Text(stringResource(R.string.e1_person_none), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp)) }
                    else items(l, key = { it.ref }) { x ->
                        val on = x.ref in cur
                        Row(
                            Modifier.fillMaxWidth().clickable(role = if (p.multi) Role.Checkbox else Role.RadioButton) {
                                if (p.multi) cur = if (on) cur - x.ref else (cur + x.ref).take(20)
                                else { onSet(JsonArray(listOf(JsonPrimitive(x.ref)))); onDismiss() }
                            }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(x.name.ifBlank { "–" } + if (x.me) " · " + stringResource(R.string.e1_person_me) else "", modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (on) Icon(Icons.Filled.Check, stringResource(R.string.dbv_checked), tint = MaterialTheme.colorScheme.primary)
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSet(if (cur.isEmpty()) JsonNull else JsonArray(cur.map(::JsonPrimitive))); onDismiss() }) { Text(stringResource(R.string.action_apply)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(p.name) },
        text = {
            Column {
                if (cur.isEmpty()) Text(stringResource(R.string.dbv_value_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                cur.forEach { f ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text((if (f.image) "🖼 " else "📎 ") + f.name, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        IconButton(onClick = { cur = cur - f }) { Icon(Icons.Filled.Close, stringResource(R.string.e1_file_remove, f.name)) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                    OutlinedButton(onClick = { pick.launch("*/*") }, enabled = !busy && cur.size < p.max) {
                        Icon(Icons.Filled.Add, null)
                        Text(stringResource(R.string.e1_file_add))
                    }
                    if (busy) CircularProgressIndicator(Modifier.padding(start = 10.dp).size(20.dp), strokeWidth = 2.dp)
                }
                if (failed) Text(stringResource(R.string.image_upload_failed), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = { onSet(DbFiles.toValue(cur)); onDismiss() }) { Text(stringResource(R.string.action_apply)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/**
 * Datum mit Uhrzeit / Zeitraum (datetime1): Tag, optional Uhrzeit, optional Ende. "Uhrzeit entfernen" bei gleichem Tag
 * schickt `Tag/Tag` (der Server speichert dann den reinen Tag).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateTimeDialog(p: DbProp, value: JsonElement?, onSet: (JsonElement?) -> Unit, onDismiss: () -> Unit) {
    val zone = remember { ZoneId.systemDefault() }
    val locale = appLocale()
    val old = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
    val v = remember(old) { DbDate.parse(old, zone) }
    var start by remember { mutableStateOf(v?.start?.localDay(zone) ?: LocalDate.now(zone)) }
    var startTime by remember { mutableStateOf(v?.start?.localTime(zone)) }
    var end by remember { mutableStateOf(v?.end?.localDay(zone)) }
    var endTime by remember { mutableStateOf(v?.end?.localTime(zone)) }
    var withTime by remember { mutableStateOf(startTime != null || (v == null && p.time)) }
    var withEnd by remember { mutableStateOf(end != null || (v == null && p.range)) }
    var picking by remember { mutableStateOf<String?>(null) }
    val dayFmt = remember(locale) { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale) }
    val timeFmt = remember(locale) { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(p.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { picking = "start" }) { Text(start.format(dayFmt)) }
                    if (withTime) OutlinedButton(onClick = { picking = "startTime" }) { Text((startTime ?: LocalTime.of(9, 0)).format(timeFmt)) }
                }
                if (withEnd) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("–")
                    OutlinedButton(onClick = { picking = "end" }) { Text((end ?: start).format(dayFmt)) }
                    if (withTime) OutlinedButton(onClick = { picking = "endTime" }) { Text((endTime ?: (startTime ?: LocalTime.of(9, 0)).plusHours(1)).format(timeFmt)) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.e1_date_time), modifier = Modifier.weight(1f))
                    Switch(withTime, { withTime = it; if (it && startTime == null) startTime = LocalTime.of(9, 0) })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.e1_date_end), modifier = Modifier.weight(1f))
                    Switch(withEnd, { withEnd = it; if (it && end == null) end = start })
                }
                if (end != null && withEnd && end!!.isBefore(start)) Text(stringResource(R.string.e1_date_end_before), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val st = if (withTime) startTime ?: LocalTime.of(9, 0) else null
                val e = if (withEnd) (end ?: start) else null
                val et = if (withTime && e != null) (endTime ?: st?.plusHours(1)) else null
                if (e != null && (e.isBefore(start) || (e == start && et != null && st != null && et.isBefore(st)))) return@TextButton
                onSet(JsonPrimitive(DbDate.encode(start, st, e, et, zone, old, datetime1 = true)))
                onDismiss()
            }) { Text(stringResource(R.string.action_apply)) }
        },
        dismissButton = {
            Row {
                if (old != null) TextButton(onClick = { onSet(JsonNull); onDismiss() }) { Text(stringResource(R.string.dbv_clear)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
    when (val k = picking) {
        "start", "end" -> {
            val init = (if (k == "start") start else end ?: start).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            val state = rememberDatePickerState(initialSelectedDateMillis = init)
            DatePickerDialog(
                onDismissRequest = { picking = null },
                confirmButton = {
                    TextButton(onClick = {
                        state.selectedDateMillis?.let { ms ->
                            val d = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
                            if (k == "start") { val shift = java.time.temporal.ChronoUnit.DAYS.between(start, d); start = d; end = end?.plusDays(shift) } else end = d
                        }
                        picking = null
                    }) { Text(stringResource(R.string.action_apply)) }
                },
                dismissButton = { TextButton(onClick = { picking = null }) { Text(stringResource(R.string.action_cancel)) } },
            ) { DatePicker(state) }
        }
        "startTime", "endTime" -> {
            val t0 = (if (k == "startTime") startTime else endTime) ?: LocalTime.of(9, 0)
            val state = rememberTimePickerState(t0.hour, t0.minute, android.text.format.DateFormat.is24HourFormat(androidx.compose.ui.platform.LocalContext.current))
            AlertDialog(
                onDismissRequest = { picking = null },
                text = { TimePicker(state) },
                confirmButton = {
                    TextButton(onClick = {
                        val t = LocalTime.of(state.hour, state.minute)
                        if (k == "startTime") startTime = t else endTime = t
                        picking = null
                    }) { Text(stringResource(R.string.action_apply)) }
                },
                dismissButton = { TextButton(onClick = { picking = null }) { Text(stringResource(R.string.action_cancel)) } },
            )
        }
    }
}
