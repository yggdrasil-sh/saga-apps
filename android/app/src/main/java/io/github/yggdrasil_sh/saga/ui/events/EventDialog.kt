package io.github.yggdrasil_sh.saga.ui.events

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.data.CalEvent
import io.github.yggdrasil_sh.saga.data.CalTarget
import io.github.yggdrasil_sh.saga.data.EventForm
import io.github.yggdrasil_sh.saga.data.SagaRepository
import io.github.yggdrasil_sh.saga.data.calendarAccounts
import io.github.yggdrasil_sh.saga.data.deleteEvent
import io.github.yggdrasil_sh.saga.data.saveEvent
import io.github.yggdrasil_sh.saga.ui.common.appLocale
import io.github.yggdrasil_sh.saga.util.UiText
import io.github.yggdrasil_sh.saga.util.resolve
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit

/**
 * Termin anlegen/bearbeiten/loeschen (Saga 2.4, wie der Termin-Dialog im Web): CalDAV ueber den Server.
 * event == null: neuer Termin am Tag day. Serientermine und nur lesbare Kalender: nur ansehen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventDialog(repo: SagaRepository, event: CalEvent?, day: LocalDate, onDismiss: () -> Unit, onChanged: () -> Unit) {
    val ro = event != null && EventForm.readOnly(event)
    var form by remember(event) { mutableStateOf(if (event != null) EventForm.of(event) else EventForm.forDay(day)) }
    var targets by remember { mutableStateOf<List<CalTarget>?>(null) }
    var error by remember { mutableStateOf<UiText?>(null) }
    var busy by remember { mutableStateOf(false) }
    var askDelete by remember { mutableStateOf(false) }
    var pickDate by remember { mutableStateOf<Int?>(null) }        // 0 = Beginn, 1 = Ende
    var pickTime by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()
    val fallback = stringResource(R.string.ev_default_title)

    LaunchedEffect(Unit) {
        if (event != null) return@LaunchedEffect
        targets = try {
            EventForm.targets(repo.calendarAccounts())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = repo.friendly(e)
            emptyList()
        }
    }

    fun run(block: suspend () -> Unit, done: UiText) {
        busy = true
        error = null
        scope.launch {
            try {
                block()
                repo.notify(done)
                onChanged()
                onDismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = repo.friendly(e)
                busy = false
            }
        }
    }

    val loc = appLocale()
    val dateFmt = remember(loc) { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(loc) }
    val timeFmt = remember(loc) { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(loc) }
    val noTargets = event == null && targets?.isEmpty() == true

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(if (event == null) R.string.ev_new else if (ro) R.string.ev_view else R.string.ev_edit)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(form.summary, { form = form.copy(summary = it.take(300)) }, enabled = !ro, singleLine = true,
                    label = { Text(stringResource(R.string.ev_title)) }, placeholder = { Text(stringResource(R.string.ev_title_hint)) }, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    Text(stringResource(R.string.ev_allday), modifier = Modifier.weight(1f))
                    Switch(form.allday, { form = form.copy(allday = it).normalized() }, enabled = !ro)
                }
                Text(stringResource(R.string.ev_start), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                Row {
                    OutlinedButton(onClick = { pickDate = 0 }, enabled = !ro) { Text(form.startDate.format(dateFmt)) }
                    if (!form.allday) OutlinedButton(onClick = { pickTime = 0 }, enabled = !ro, modifier = Modifier.padding(start = 8.dp)) { Text(form.startTime.format(timeFmt)) }
                }
                Text(stringResource(R.string.ev_end), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                Row {
                    OutlinedButton(onClick = { pickDate = 1 }, enabled = !ro) { Text(form.endDate.format(dateFmt)) }
                    if (!form.allday) OutlinedButton(onClick = { pickTime = 1 }, enabled = !ro, modifier = Modifier.padding(start = 8.dp)) { Text(form.endTime.format(timeFmt)) }
                }
                if (event == null) {
                    val list = targets.orEmpty()
                    var open by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it && list.isNotEmpty() }, modifier = Modifier.padding(top = 8.dp)) {
                        OutlinedTextField(
                            list.getOrNull(form.target)?.name ?: if (targets == null) "…" else "", {}, readOnly = true,
                            label = { Text(stringResource(R.string.ev_calendar)) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
                            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, true),
                        )
                        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                            list.forEachIndexed { i, t ->
                                DropdownMenuItem(text = { Text(t.name) }, onClick = { form = form.copy(target = i); open = false })
                            }
                        }
                    }
                } else {
                    Text(stringResource(R.string.ev_calendar) + ": " + event.calendar.orEmpty(), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                }
                OutlinedTextField(form.location, { form = form.copy(location = it.take(300)) }, enabled = !ro, singleLine = true,
                    label = { Text(stringResource(R.string.ev_location)) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                OutlinedTextField(form.description, { form = form.copy(description = it.take(2000)) }, enabled = !ro, minLines = 2, maxLines = 6,
                    label = { Text(stringResource(R.string.ev_description)) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                if (ro) {
                    Text(stringResource(if (event?.series == true) R.string.ev_series_ro else R.string.ev_readonly), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                }
                if (noTargets) Text(stringResource(R.string.ev_no_calendar), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
                error?.let { Text(it.resolve(), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
            }
        },
        confirmButton = {
            if (!ro) TextButton(enabled = !busy && !noTargets && (event != null || targets != null), onClick = {
                run({ repo.saveEvent(form, targets?.getOrNull(form.target), event, fallback) }, UiText.Res(if (event == null) R.string.ev_created else R.string.ev_saved))
            }) { Text(stringResource(if (event == null) R.string.ev_create else R.string.ev_save)) }
        },
        dismissButton = {
            Row {
                if (event != null && !ro) TextButton(onClick = { askDelete = true }, enabled = !busy) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(if (ro) R.string.action_close else R.string.action_cancel)) }
            }
        },
    )

    if (askDelete && event != null) {
        AlertDialog(
            onDismissRequest = { askDelete = false },
            title = { Text(stringResource(R.string.ev_delete_title, event.summary.ifEmpty { fallback })) },
            text = { Text(stringResource(R.string.ev_delete_text)) },
            confirmButton = {
                TextButton(onClick = { askDelete = false; run({ repo.deleteEvent(event) }, UiText.Res(R.string.ev_deleted)) }) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { askDelete = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    pickDate?.let { which ->
        val init = if (which == 0) form.startDate else form.endDate
        val st = rememberDatePickerState(initialSelectedDateMillis = init.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { pickDate = null },
            confirmButton = {
                TextButton(onClick = {
                    st.selectedDateMillis?.let { ms ->
                        val d = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
                        form = (if (which == 0) {
                            val len = ChronoUnit.DAYS.between(form.startDate, form.endDate)
                            form.copy(startDate = d, endDate = d.plusDays(len.coerceAtLeast(0)))      // Dauer bleibt
                        } else form.copy(endDate = d)).normalized()
                    }
                    pickDate = null
                }) { Text(stringResource(R.string.action_apply)) }
            },
            dismissButton = { TextButton(onClick = { pickDate = null }) { Text(stringResource(R.string.action_cancel)) } },
        ) { DatePicker(st) }
    }
    pickTime?.let { which ->
        val init = if (which == 0) form.startTime else form.endTime
        val st = rememberTimePickerState(init.hour, init.minute, android.text.format.DateFormat.is24HourFormat(LocalContext.current))
        AlertDialog(
            onDismissRequest = { pickTime = null },
            text = { TimePicker(st) },
            confirmButton = {
                TextButton(onClick = {
                    val t = LocalTime.of(st.hour, st.minute)
                    form = (if (which == 0) {
                        val dur = Duration.between(form.startDate.atTime(form.startTime), form.endDate.atTime(form.endTime))
                        val ns = form.startDate.atTime(t)
                        val ne = ns.plus(if (dur.isNegative || dur.isZero) Duration.ofHours(1) else dur)
                        form.copy(startTime = t, endDate = ne.toLocalDate(), endTime = ne.toLocalTime())
                    } else form.copy(endTime = t)).normalized()
                    pickTime = null
                }) { Text(stringResource(R.string.action_apply)) }
            },
            dismissButton = { TextButton(onClick = { pickTime = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
