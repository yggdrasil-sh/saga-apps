package io.github.yggdrasil_sh.saga.ui.database

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.db.DbDate
import io.github.yggdrasil_sh.saga.db.DbProp
import io.github.yggdrasil_sh.saga.db.DbValues
import io.github.yggdrasil_sh.saga.ui.common.appLocale
import io.github.yggdrasil_sh.saga.ui.theme.SagaIcon
import io.github.yggdrasil_sh.saga.ui.theme.SagaIcons
import io.github.yggdrasil_sh.saga.ui.theme.SagaTextButton
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

private fun LocalDate.ms(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
private fun Long.day(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()
private fun LocalTime.min(): Int = hour * 60 + minute
private val NINE: LocalTime = LocalTime.of(9, 0)

/**
 * R3-Nachbesserung (36c): Zeitraum in einer kurzen Zeile in der Sprache der Oberflaeche, ohne Doppelungen und mit
 * Halbgeviertstrich ("2.–5. Nov. 2026", "Nov 2 – 5, 2026") - ICU-Intervallformat.
 */
internal fun rangeLabel(start: LocalDate, end: LocalDate, locale: java.util.Locale): String {
    val zone = ZoneId.systemDefault()
    fun at(d: LocalDate) = d.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
    val f = android.icu.text.DateIntervalFormat.getInstance(android.icu.text.DateFormat.YEAR_ABBR_MONTH_DAY, locale)
    return f.format(android.icu.util.DateInterval(at(start), at(end)))
}

/**
 * AND-ED-26: Datum direkt im Kalender. Oben Schnellwahl "Heute", "Morgen", "Nächste Woche" - ein Tipp uebernimmt
 * sofort (Datum aendern = 2 Taps: Zelle, Chip). Unter dem Kalender die Schalter "Mit Uhrzeit" und "Enddatum" (dann
 * `DateRangePicker`). Ohne datetime1 (aeltere Server) nur der reine Tag, ohne Schalter.
 * Knoepfe nach ED-24: links "Leeren", rechts Abbrechen und Uebernehmen; ausserhalb tippen verwirft.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DbDlgDateDialog(p: DbProp, value: JsonElement?, datetime: Boolean, onSet: (JsonElement?) -> Unit, onDismiss: () -> Unit) {
    val zone = remember { ZoneId.systemDefault() }
    val locale = appLocale()
    val old = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
    val v = remember(old) { if (datetime) DbDate.parse(old, zone) else null }
    val day0 = remember(value) { v?.start?.localDay(zone) ?: DbValues.parseDate(value) ?: LocalDate.now(zone) }
    val end0 = remember(value) { v?.end?.localDay(zone) }
    var startTime by remember { mutableStateOf(v?.start?.localTime(zone)) }
    var endTime by remember { mutableStateOf(v?.end?.localTime(zone)) }
    var withTime by remember { mutableStateOf(datetime && (startTime != null || (v == null && p.time))) }
    var withEnd by remember { mutableStateOf(datetime && (end0 != null || (v == null && p.range))) }
    var bad by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf<String?>(null) }
    val single = rememberDatePickerState(initialSelectedDateMillis = day0.ms())
    // R3-Nachbesserung (36c): Zeitraum im selben Kalender - Segmente "Start" / "Ende" waehlen, welches Ende ein Tipp setzt
    var rStart by remember { mutableStateOf(day0) }
    var rEnd by remember { mutableStateOf(end0?.takeIf { !it.isBefore(day0) } ?: day0) }
    var edge by remember { mutableStateOf(DbDlg.Edge.START) }
    LaunchedEffect(single) {
        snapshotFlow { single.selectedDateMillis }.collect { ms ->
            if (withEnd && ms != null) {
                val (a, b) = DbDlg.pickRange(rStart, rEnd, edge, ms.day())
                rStart = a
                rEnd = b
            }
        }
    }
    fun showEdge(e: DbDlg.Edge) {
        edge = e
        val d = DbDlg.shownDay(rStart, rEnd, e).ms()
        single.selectedDateMillis = d
        single.displayedMonthMillis = d
    }
    val timeFmt = remember(locale) { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale) }

    fun startDay(): LocalDate? = if (withEnd) rStart else single.selectedDateMillis?.day()
    fun endDay(): LocalDate? = if (withEnd) rEnd else null
    fun commit(start: LocalDate, end: LocalDate?) {
        if (!datetime) {
            onSet(DbValues.date(start)); onDismiss(); return
        }
        val st = if (withTime) startTime ?: NINE else null
        val et = if (withTime && end != null) endTime ?: st?.plusHours(1) else null
        if (!DbDlg.rangeValid(start, st?.min(), end, et?.min())) { bad = true; return }
        onSet(JsonPrimitive(DbDate.encode(start, st, end, et, zone, old, datetime1 = true)))
        onDismiss()
    }
    fun toggleEnd(on: Boolean) {
        bad = false
        if (on) {
            val s = single.selectedDateMillis?.day() ?: day0
            rStart = s
            rEnd = s
            withEnd = true
            showEdge(DbDlg.Edge.END)            // der Beginn steht schon - der naechste Tipp setzt das Ende
        } else {
            withEnd = false
            val s = rStart.ms()
            single.selectedDateMillis = s
            single.displayedMonthMillis = s
        }
    }

    val maxH = (LocalConfiguration.current.screenHeightDp - 32).coerceAtLeast(320).dp
    val bg = DatePickerDefaults.colors().containerColor
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.requiredWidth(360.dp).heightIn(max = maxH),
            shape = MaterialTheme.shapes.extraLarge, color = bg, tonalElevation = 6.dp,
        ) {
            Column {
                Text(p.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp))
                // Schnellwahl: uebernimmt sofort (ein vorhandenes Ende wandert mit, die Uhrzeit bleibt).
                // R3-Nachbesserung (36): eine Zeile ohne Symbole (der Kalender steht direkt darunter); reicht die Breite
                // nicht (grosse Schrift), laesst sie sich seitlich wischen - kein Text wird gekuerzt
                // Nachbesserung R3 (78): am Rand, hinter dem es weitergeht, weich ausblenden (wie die Ansichts-Pillen) statt
                // eines harten Schnitts mitten im Chip
                val quickScroll = rememberScrollState()
                val quickFade = with(androidx.compose.ui.platform.LocalDensity.current) { 28.dp.toPx() }
                Row(
                    Modifier.fillMaxWidth()
                        .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            val w = minOf(quickFade, size.width / 4)
                            if (quickScroll.value > 0) drawRect(
                                androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), startX = 0f, endX = w),
                                size = androidx.compose.ui.geometry.Size(w, size.height), blendMode = androidx.compose.ui.graphics.BlendMode.DstOut,
                            )
                            if (quickScroll.value < quickScroll.maxValue) drawRect(
                                androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), startX = size.width - w, endX = size.width),
                                topLeft = androidx.compose.ui.geometry.Offset(size.width - w, 0f), size = androidx.compose.ui.geometry.Size(w, size.height),
                                blendMode = androidx.compose.ui.graphics.BlendMode.DstOut,
                            )
                        }
                        .horizontalScroll(quickScroll).padding(horizontal = 20.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf(DbDlg.Quick.TODAY to R.string.e1_today, DbDlg.Quick.TOMORROW to R.string.e1_tomorrow, DbDlg.Quick.NEXT_WEEK to R.string.e1_next_week)
                        .forEach { (q, res) ->
                            AssistChip(onClick = {
                                val (s, e) = DbDlg.moveStart(startDay() ?: day0, endDay(), DbDlg.quickDay(q, LocalDate.now(zone)))
                                commit(s, e)
                            }, label = { Text(stringResource(res), maxLines = 1, softWrap = false) })
                        }
                }
                if (withEnd) {
                    // R3-Nachbesserung (36c): derselbe Kalender wie ohne Ende; Zeitraum kompakt "2.–5. Nov. 2026"
                    val rangeText = remember(rStart, rEnd, locale) { rangeLabel(rStart, rEnd, locale) }
                    Text(
                        rangeText, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                        listOf(DbDlg.Edge.START to R.string.r3d_seg_start, DbDlg.Edge.END to R.string.r3d_seg_end).forEachIndexed { i, (e, res) ->
                            SegmentedButton(
                                selected = edge == e, onClick = { showEdge(e) },
                                shape = SegmentedButtonDefaults.itemShape(i, 2), icon = {},
                            ) { Text(stringResource(res), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) }
                        }
                    }
                }
                Box(Modifier.weight(1f, fill = false)) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        DatePicker(single, title = null, headline = null, showModeToggle = false)
                    }
                }
                if (datetime) FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DbDlgSwitch(stringResource(R.string.e1_date_time), withTime, { on ->
                        bad = false
                        withTime = on
                        if (on && startTime == null) startTime = NINE
                    })
                    DbDlgSwitch(stringResource(R.string.e1_date_end), withEnd, ::toggleEnd)
                }
                if (datetime && withTime) Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val st = (startTime ?: NINE).format(timeFmt)
                    val stCd = stringResource(R.string.r3d_start_time, st)
                    AssistChip(onClick = { pickTime = "start" }, label = { Text(st) }, leadingIcon = { SagaIcon(SagaIcons.clock, null) },
                        modifier = Modifier.semantics { contentDescription = stCd })
                    if (withEnd) {
                        Text("–")
                        val et = (endTime ?: (startTime ?: NINE).plusHours(1)).format(timeFmt)
                        val etCd = stringResource(R.string.r3d_end_time, et)
                        AssistChip(onClick = { pickTime = "end" }, label = { Text(et) }, leadingIcon = { SagaIcon(SagaIcons.clock, null) },
                            modifier = Modifier.semantics { contentDescription = etCd })
                    }
                }
                if (bad) Text(
                    stringResource(R.string.e1_date_end_before), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                DbDlgButtons(
                    onCancel = onDismiss,
                    clearLabel = stringResource(R.string.dbv_clear),
                    onClear = if (value != null && value !is JsonNull) ({ onSet(JsonNull); onDismiss() }) else null,
                    onConfirm = { startDay()?.let { commit(it, endDay()) } },
                    confirmEnabled = startDay() != null,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp, top = 4.dp),
                )
            }
        }
    }
    pickTime?.let { k ->
        val t0 = (if (k == "start") startTime else endTime) ?: if (k == "start") NINE else (startTime ?: NINE).plusHours(1)
        val state = rememberTimePickerState(t0.hour, t0.minute, android.text.format.DateFormat.is24HourFormat(LocalContext.current))
        AlertDialog(
            onDismissRequest = { pickTime = null },
            text = { TimePicker(state) },
            confirmButton = {
                SagaTextButton(onClick = {
                    val t = LocalTime.of(state.hour, state.minute)
                    if (k == "start") startTime = t else endTime = t
                    bad = false
                    pickTime = null
                }) { Text(stringResource(R.string.action_apply)) }
            },
            dismissButton = { SagaTextButton(onClick = { pickTime = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
