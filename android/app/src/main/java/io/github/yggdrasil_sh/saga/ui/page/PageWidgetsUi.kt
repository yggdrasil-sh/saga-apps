package io.github.yggdrasil_sh.saga.ui.page

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.blocks.Gestalten
import io.github.yggdrasil_sh.saga.blocks.PageWidgets
import io.github.yggdrasil_sh.saga.blocks.WidgetDesign
import io.github.yggdrasil_sh.saga.data.ApiException
import io.github.yggdrasil_sh.saga.data.SagaRepository
import io.github.yggdrasil_sh.saga.data.habitCheck
import io.github.yggdrasil_sh.saga.data.pageWidgetValues
import io.github.yggdrasil_sh.saga.ui.common.appLocale
import io.github.yggdrasil_sh.saga.ui.theme.SagaColors
import io.github.yggdrasil_sh.saga.ui.theme.SagaIcon
import io.github.yggdrasil_sh.saga.ui.theme.SagaIcons
import io.github.yggdrasil_sh.saga.ui.theme.SagaRadius
import io.github.yggdrasil_sh.saga.ui.theme.SagaTextButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.cos
import kotlin.math.sin

// Saga 2.5 Widgets in Seiten, Etappe 1 (SPEC-widgets 4) - nur Anzeige; Logik in blocks/PageWidgets.kt

/**
 * Werte der Server-Widgets einer Seite (GET /api/notes/{nid}/widgets): ein Abruf fuer alle Karten, erneut beim
 * Zurueckkehren in den Vordergrund (force). Der Server merkt sich die Werte selbst 60 s.
 */
class PageWidgetCache(private val noteId: Long, private val repo: SagaRepository) {
    /** 2.5 habit1: einen Wert ersetzen (Antwort von .../habit oder lokal umgeschaltet) */
    fun replace(id: String, v: PageWidgets.Value) {
        val cur = values as? PageWidgets.Values.Ok ?: return
        values = PageWidgets.Values.Ok(cur.map + (id to v))
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var at = 0L
    var values by mutableStateOf<PageWidgets.Values?>(null)
        private set
    /** Mindestens eine Karte braucht Server-Werte (sonst kein Abruf beim Zurueckkehren) */
    var wanted = false
        private set

    fun ensure(force: Boolean = false) {
        wanted = true
        val now = System.currentTimeMillis()
        if (job?.isActive == true) return
        if (!force && values != null && now - at < 60_000) return
        at = now
        job = scope.launch {
            values = try {
                val text = repo.pageWidgetValues(noteId)
                withContext(Dispatchers.Default) { PageWidgets.parseResponse(text) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                PageWidgets.fromHttpError(e.code)
            } catch (_: Exception) {
                values?.takeIf { it is PageWidgets.Values.Ok } ?: PageWidgets.Values.Failed   // offline: alte Werte behalten
            }
        }
    }
}

/** In PageScreen: beim Zurueckkehren in den Vordergrund Werte neu holen (nur, wenn die Seite Server-Widgets hat) */
@Composable
fun PageWidgetsForeground(env: BlockEnv) {
    var started by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        if (started && env.widgets.wanted) env.widgets.ensure(force = true)
        started = true
    }
}

// ---------------------------------------------------------------- Widget-Design-Runde (9.11.): Karte wie im Web

/** Gleich hohe Karten einer Spalten-Reihe (nur, wenn je Spalte genau ein Widget steht): natuerliche Hoehen je Spalte */
class WidgetRowHeights {
    val heights = mutableStateMapOf<Int, Int>()
    val max: Int get() = heights.values.maxOrNull() ?: 0
}

data class WidgetRowSlot(val row: WidgetRowHeights, val index: Int)

val LocalWidgetRowSlot = compositionLocalOf<WidgetRowSlot?> { null }

/** Was die Inhalte je Art brauchen: Block, Innenbreite der Karte, Akzent (Balken/Ringe/Punkte) und Wertfarbe */
private class WgCtx(val s: PageWidgets.Spec, val widthDp: Float, val accent: Color, val valueColor: Color, val chosen: Boolean)

@Composable
private fun kindName(kind: String): String = when (kind) {
    "clock" -> stringResource(R.string.pw_kind_clock)
    "countdown" -> stringResource(R.string.pw_kind_countdown)
    "progress" -> stringResource(R.string.pw_kind_progress)
    "metric" -> stringResource(R.string.pw_kind_metric)
    "quote" -> stringResource(R.string.pw_kind_quote)
    "weather" -> stringResource(R.string.e1_kind_weather)
    "habit" -> stringResource(R.string.e1_kind_habit)
    "upcoming" -> stringResource(R.string.e1_kind_upcoming)
    "chart" -> stringResource(R.string.e1_kind_chart)
    "reading" -> stringResource(R.string.bo_kind_reading)
    else -> stringResource(R.string.pw_kind_widget)
}

/** Symbol der Art (Kopf 14 dp, Leerzustand 20 dp) */
private fun kindIcon(kind: String): Int = when (kind) {
    "clock" -> SagaIcons.clock
    "countdown" -> SagaIcons.timer
    "progress" -> SagaIcons.progress
    "quote" -> SagaIcons.quote
    "weather" -> SagaIcons.sun
    "habit" -> SagaIcons.today
    "upcoming" -> SagaIcons.list
    "reading" -> SagaIcons.file
    else -> SagaIcons.chart
}

@Composable
private fun hintText(kind: String): String? = when (kind) {
    "countdown" -> stringResource(R.string.wd_hint_countdown)
    "progress" -> stringResource(R.string.wd_hint_progress)
    "metric" -> stringResource(R.string.wd_hint_metric)
    "quote" -> stringResource(R.string.wd_hint_quote)
    "weather" -> stringResource(R.string.wd_hint_weather)
    "habit" -> stringResource(R.string.wd_hint_habit)
    "chart" -> stringResource(R.string.wd_hint_chart)
    "upcoming" -> stringResource(R.string.wd_hint_upcoming)
    "reading" -> stringResource(R.string.wd_hint_reading)
    else -> null
}

/** Rechenart als Text (Kennzahl, Diagramm) */
@Composable
fun widgetAggLabel(agg: String?): String = when (agg) {
    "sum" -> stringResource(R.string.wd_agg_sum)
    "avg" -> stringResource(R.string.wd_agg_avg)
    "min" -> stringResource(R.string.wd_agg_min)
    "max" -> stringResource(R.string.wd_agg_max)
    "pct_checked" -> stringResource(R.string.wd_agg_pct_checked)
    "open" -> stringResource(R.string.wd_agg_open)
    "due_today" -> stringResource(R.string.wd_agg_due_today)
    "overdue" -> stringResource(R.string.wd_agg_overdue)
    "done_week" -> stringResource(R.string.wd_agg_done_week)
    else -> stringResource(R.string.wd_agg_count)
}

/** Datumsformat nach Skelett (z. B. "EEEdMMM" -> "Mi., 11. Nov.") in der Sprache der App */
private fun bestFmt(locale: java.util.Locale, skeleton: String): DateTimeFormatter =
    DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton), locale)

/** Zustand eines Widgets fuer die Karte: eingerichtet, nicht eingerichtet (Leerzustand) oder Hinweis/Fehler */
private sealed interface WgState {
    data object Loading : WgState
    data object Unset : WgState
    data class Note(val text: String) : WgState
    data class Error(val err: PageWidgets.Err) : WgState
    data class Ok(val v: PageWidgets.Value?) : WgState
}

/** Block `widget`: Karte (Kopf, Wert, Kontext, Fuss) wie im Web; unbekannte Art -> Hinweis "neuere Version noetig" */
@Composable
fun PageWidgetBlock(block: JsonObject, env: BlockEnv) {
    val s = remember(block) { PageWidgets.spec(block) }
    val features by env.repo.serverFeatures.collectAsState()
    // Etappe-2-Arten nur mit passender Server-Faehigkeit (aeltere Server: "neuere Version noetig")
    if (!s.known || (s.kind in PageWidgets.KINDS2 && !PageWidgets.supported(s.kind, features) && features.isNotEmpty())) {
        NewerVersionCard(s, env)
        return
    }
    val cs = MaterialTheme.colorScheme
    val dark = cs.surface.luminance() < 0.5f
    // Akzent nur fuer Balken, Ringe, Punkte, Sonne, heutigen Tag; die gewaehlte Farbe auch fuer den Wert - nie die Karte
    val chosen = s.accent != null
    val accent = s.accent?.let { Color(Gestalten.textArgb(it, dark)) } ?: cs.primary
    val valueColor = if (chosen) accent else cs.onSurface
    val card = s.style == "card"
    val bx = WidgetDesign.box(s.size)
    val cache = env.widgets
    if (s.serverValue) LaunchedEffect(Unit) { cache.ensure() }
    val all = cache.values
    val v = (all as? PageWidgets.Values.Ok)?.map?.get(s.id ?: "")
    val state: WgState = when {
        !s.serverValue -> WgState.Ok(null)
        all == null -> WgState.Loading
        s.localFallback -> v?.error?.let { if (it == PageWidgets.Err.BAD_CFG) WgState.Unset else WgState.Error(it) } ?: WgState.Ok(v)
        all is PageWidgets.Values.Unsupported -> WgState.Note(stringResource(R.string.pw_unsupported))
        all is PageWidgets.Values.OldServer -> WgState.Note(stringResource(R.string.pw_old_server))
        all is PageWidgets.Values.Failed -> WgState.Note(stringResource(R.string.pw_failed))
        v == null -> WgState.Note(stringResource(R.string.pw_pending))
        v.error == PageWidgets.Err.BAD_CFG -> WgState.Unset
        v.error != null -> WgState.Error(v.error)
        else -> WgState.Ok(v)
    }.let { st ->
        // lokal erkennbar nicht eingerichtet: Countdown ohne Datum, fester Fortschritt ohne Ziel, Zitat ohne Text
        if (st !is WgState.Ok) st
        else when (s.kind) {
            "countdown" -> if (PageWidgets.countdownCfg(s.cfg).date == null) WgState.Unset else st
            "progress" -> if (PageWidgets.cfgStr(s, "src") != "db" && PageWidgets.progress(s, st.v) == null) WgState.Unset else st
            "quote" -> if (PageWidgets.quoteText(s, st.v, LocalDate.now()) == null) WgState.Unset else st
            "habit" -> if (PageWidgets.habits(st.v?.data)?.habits.isNullOrEmpty()) WgState.Unset else st
            else -> st
        }
    }
    val unset = state is WgState.Unset
    val shape = RoundedCornerShape(SagaRadius.md)
    val slot = LocalWidgetRowSlot.current
    val density = LocalDensity.current
    val rowMin = slot?.let { with(density) { it.row.max.toDp() } } ?: 0.dp
    val vPadPx = with(density) { bx.vPad.dp.roundToPx() }
    val context = LocalContext.current
    val ed = env.editor
    val canOpenWeb = ed.editable && !ed.isRemote
    val openWeb = {
        ed.flush()
        io.github.yggdrasil_sh.saga.ui.common.openUrl(context, Gestalten.webUrl(env.repo.absoluteUrl("notes"), ed.id, s.id))
    }
    BoxWithConstraints(
        Modifier.fillMaxWidth().heightIn(min = maxOf(bx.minHeight.dp, rowMin))
            .then(
                if (!card) Modifier
                else Modifier.clip(shape).background(cs.surfaceContainerLow)
                    .then(if (unset) Modifier.dashedBorder(cs.outline, SagaRadius.md) else Modifier.border(1.dp, cs.outlineVariant, shape)),
            )
            .padding(vertical = bx.vPad.dp, horizontal = if (card) bx.hPad.dp else 0.dp),
    ) {
        val w = WgCtx(s, maxWidth.value, accent, valueColor, chosen)
        Column(
            Modifier.fillMaxWidth().then(if (slot != null) Modifier.onSizeChanged { slot.row.heights[slot.index] = it.height + 2 * vPadPx } else Modifier),
            verticalArrangement = Arrangement.spacedBy(bx.gap.dp),
        ) {
            if (!unset) WgHead(s)
            when (state) {
                WgState.Loading -> Text("…", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                WgState.Unset -> WgEmpty(s.kind, canOpenWeb, openWeb)
                is WgState.Note -> WgNote(state.text)
                is WgState.Error -> {
                    WgError(errorText(state.err))
                    if (canOpenWeb && (state.err == PageWidgets.Err.GONE || state.err == PageWidgets.Err.NO_ACCESS)) {
                        SagaTextButton(onClick = openWeb) { Text(stringResource(R.string.pw_edit_web)) }
                    }
                }
                is WgState.Ok -> WgBody(w, state.v, env)
            }
        }
    }
}

@Composable
private fun WgBody(w: WgCtx, v: PageWidgets.Value?, env: BlockEnv) {
    val s = w.s
    when (s.kind) {
        "clock" -> ClockBody(w)
        "countdown" -> CountdownBody(w)
        "progress" -> ProgressBody(w, v)
        "metric" -> MetricBody(w, v)
        "quote" -> QuoteBody(w, v)
        "weather" -> WeatherBody(w, v)
        "habit" -> HabitBody(w, v, env)
        "upcoming" -> UpcomingBody(w, v, env)
        // 2.5 bookorbit1: Daten der betrachtenden Person; ohne Verbindung Hinweis mit Weg zu den Einstellungen
        "reading" -> io.github.yggdrasil_sh.saga.blocks.BookOrbit.reading(v?.data)?.let { r ->
            ReadingContent(
                r, env.repo, w.accent, onSettings = { env.repo.openSettingsRequest.value = "bookorbit" }, compact = s.size == "s",
                big = { wgValueStyle(w) }, valueColor = w.valueColor,
            )
        } ?: WgNote(stringResource(R.string.wd_no_values))
        "chart" -> v?.data?.let { data ->
            val c = remember(data) { io.github.yggdrasil_sh.saga.db.DbChart.parseResult(data) }
            val agg = widgetAggLabel(PageWidgets.cfgStr(s, "agg").ifEmpty { null })
            io.github.yggdrasil_sh.saga.ui.database.ChartBlock(
                c, showTable = false, size = s.size, accent = if (w.chosen) w.accent else null,
                ctx = io.github.yggdrasil_sh.saga.ui.database.chartContext(agg, if (PageWidgets.cfgStr(s, "agg") != "count") c.yName.orEmpty() else "", c.xName.orEmpty(), c), cap = agg,
            )
        } ?: WgNote(stringResource(R.string.wd_no_values))
    }
}

/** Kopf: Symbol der Art (14 dp, muted) + Titel (klein, 500, muted, einzeilig; L zweizeilig); leerer Titel -> Artname */
@Composable
private fun WgHead(s: PageWidgets.Spec) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        SagaIcon(kindIcon(s.kind), null, tint = cs.onSurfaceVariant, modifier = Modifier.size(14.dp))
        Text(
            s.title.ifEmpty { kindName(s.kind) }, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
            color = cs.onSurfaceVariant, maxLines = if (s.size == "l") 2 else 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false).semantics { heading() },
        )
    }
}

/** Nicht eingerichtet: Symbol der Art (20 dp), "Diagramm einrichten", Hilfe darunter - kein Warnzeichen */
@Composable
private fun WgEmpty(kind: String, canEdit: Boolean, onEdit: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().then(if (canEdit) Modifier.clickable(onClick = onEdit) else Modifier),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        SagaIcon(kindIcon(kind), null, tint = cs.onSurfaceVariant, modifier = Modifier.padding(bottom = 3.dp).size(20.dp))
        if (canEdit) {
            Text(stringResource(R.string.wd_setup, kindName(kind)), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium), color = cs.onSurface)
            hintText(kind)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant) }
        } else {
            Text(stringResource(R.string.wd_not_setup), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium), color = cs.onSurface)
        }
    }
}

@Composable
private fun WgNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Echter Fehler: Warnzeichen + Text in Signalrot (naechster Schritt darunter, falls moeglich) */
@Composable
private fun WgError(text: String) {
    Text("⚠ $text", style = MaterialTheme.typography.bodyMedium, color = SagaColors.current.down)
}

@Composable
private fun errorText(e: PageWidgets.Err): String = stringResource(
    when (e) {
        PageWidgets.Err.NO_ACCESS -> R.string.pw_err_no_access
        PageWidgets.Err.GONE -> R.string.pw_err_gone
        PageWidgets.Err.BAD_CFG -> R.string.pw_err_bad_cfg
        PageWidgets.Err.TOO_BIG -> R.string.pw_err_too_big
        PageWidgets.Err.DISABLED -> R.string.e1_weather_disabled
        PageWidgets.Err.UNAVAILABLE -> R.string.e1_weather_unavailable
        PageWidgets.Err.OTHER -> R.string.pw_failed
    },
)

private fun Modifier.dashedBorder(color: Color, radius: Dp): Modifier = drawBehind {
    val st = 1.dp.toPx()
    drawRoundRect(
        color, topLeft = Offset(st / 2, st / 2), size = Size(size.width - st, size.height - st), cornerRadius = CornerRadius(radius.toPx()),
        style = Stroke(st, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
    )
}

// ---------------------------------------------------------------- Wert, Kontext, Balken

@Composable
private fun wgValueStyle(w: WgCtx): TextStyle {
    val fs = WidgetDesign.valueSp(w.widthDp, w.s.size).sp
    return TextStyle(fontSize = fs, fontWeight = FontWeight.SemiBold, lineHeight = fs * 1.1f, letterSpacing = (-0.01).em, fontFeatureSettings = "tnum")
}

/** Teile eines Werts: (Text, klein) - klein = Einheit/Wort (0,55 em, muted) */
private fun valueParts(text: String): List<Pair<String, Boolean>> {
    val p = WidgetDesign.splitValue(text)
    return listOfNotNull(
        p.prefix.takeIf { it.isNotEmpty() }?.let { "$it " to true },
        p.main to false,
        p.unit.takeIf { it.isNotEmpty() }?.let { " $it" to true },
    )
}

/** Grosser Wert: Textfarbe (bzw. gewaehlte Farbe), SemiBold, tabellarische Ziffern, einzeilig - Schrift nach Kartenbreite */
@Composable
private fun WgValue(parts: List<Pair<String, Boolean>>, w: WgCtx, modifier: Modifier = Modifier) {
    val style = wgValueStyle(w)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val text = buildAnnotatedString {
        parts.forEach { (t, small) ->
            if (small) withStyle(SpanStyle(fontSize = style.fontSize * 0.55f, fontWeight = FontWeight.Medium, color = muted, letterSpacing = 0.sp)) { append(t) }
            else append(t)
        }
    }
    Text(text, modifier, style = style, color = w.valueColor, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun WgValue(text: String, w: WgCtx, modifier: Modifier = Modifier) = WgValue(valueParts(text), w, modifier)

/** Kontextzeile direkt unter dem Wert (muted, klein) */
@Composable
private fun WgSub(text: String, modifier: Modifier = Modifier, maxLines: Int = 2) {
    Text(text, modifier, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

/** Fuss (Quelle/Hinweis), sehr klein */
@Composable
private fun WgFoot(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * Balken 6 dp, Pill, Fuellung = Akzent (hoechstens voll). Spur: Web nimmt Textfarbe 10 %; die App bleibt bei der sichtbaren
 * Spur (SagaProgressTrack, >= 3:1 auf Karten - Entscheidung R2c, DesignR2cTest), damit der Balken auch leer erkennbar ist.
 */
@Composable
private fun WgBar(fraction: Float, accent: Color, desc: String) {
    val f = fraction.coerceIn(0f, 1f)
    val trackColor = io.github.yggdrasil_sh.saga.ui.theme.SagaProgressTrack
    Box(
        Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(SagaRadius.pill)).background(trackColor)
            .semantics { contentDescription = desc; progressBarRangeInfo = ProgressBarRangeInfo(f, 0f..1f) },
    ) {
        if (f > 0f) Box(Modifier.fillMaxWidth(f).fillMaxHeight().clip(RoundedCornerShape(SagaRadius.pill)).background(accent))
    }
}

/** Zeile unter dem Balken: Kontext links, Marker rechts ("+50 %" in Akzent bzw. "✓ Ziel erreicht") */
@Composable
private fun WgBarRow(left: String, raw: Double, w: WgCtx) {
    val locale = appLocale()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        WgSub(left, Modifier.weight(1f), maxLines = 1)
        val chip = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum")
        WidgetDesign.overPercent(raw)?.let { Text("+" + PageWidgets.format(it, "pct", locale), style = chip, color = w.accent, maxLines = 1) }
            ?: if (WidgetDesign.goalReached(raw)) Text("✓ " + stringResource(R.string.wd_goal_reached), style = chip, color = SagaColors.current.ok, maxLines = 1) else Unit
    }
}

/** Zeit, die im Vordergrund tickt (Sekunde bzw. Minute), im Hintergrund still */
@Composable
private fun tickingNow(sec: Boolean): Instant {
    var now by remember { mutableStateOf(Instant.now()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(sec) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                now = Instant.now()
                delay(PageWidgets.nextTickMillis(System.currentTimeMillis(), sec) + 5)
            }
        }
    }
    return now
}

// ---------------------------------------------------------------- Uhr

@Composable
private fun ClockBody(w: WgCtx) {
    val c = remember(w.s) { PageWidgets.clock(w.s.cfg) }
    val now = tickingNow(c.sec)
    val locale = appLocale()
    val zone = PageWidgets.zone(c.tz)
    val t = now.atZone(zone)
    val text = PageWidgets.clockText(now, c, locale)
    val city = PageWidgets.zoneLabel(c.tz)
    if (c.analog) {
        // Zifferblatt mittig, Digitalzeit klein darunter (mit Datum und Ort)
        val hm = PageWidgets.clockText(now, c.copy(sec = false), locale)
        val sub = listOf(hm, bestFmt(locale, "EEEdMMM").format(t), city).filter { it.isNotEmpty() }.joinToString(" · ")
        val face = when (w.s.size) { "s" -> 60f; "l" -> 128f; else -> 92f }
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            AnalogDial(t, c.sec, minOf(face, w.widthDp * 0.62f).dp, w.accent, Modifier.semantics { contentDescription = text })
            WgSub(sub)
        }
    } else {
        WgValue(text, w)
        WgSub(listOf(bestFmt(locale, "EEEEdMMMM").format(t), city).filter { it.isNotEmpty() }.joinToString(" · "))
    }
}

/** Zeigeruhr wie im Web: Flaeche, 12 Marken (Viertel kraeftiger), Zeiger in Textfarbe, Sekundenzeiger in Akzent */
@Composable
private fun AnalogDial(t: java.time.ZonedDateTime, sec: Boolean, size: Dp, accent: Color, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val fg = cs.onSurface
    val muted = cs.onSurfaceVariant
    val field = cs.outline
    val (h, m, sAngle) = PageWidgets.handAngles(t)
    Canvas(modifier.size(size)) {
        val u = this.size.minDimension / 100f
        val c = Offset(this.size.width / 2f, this.size.height / 2f)
        fun at(deg: Float, len: Float): Offset {
            val rad = Math.toRadians(deg.toDouble() - 90.0)
            return Offset(c.x + (cos(rad) * len * u).toFloat(), c.y + (sin(rad) * len * u).toFloat())
        }
        drawCircle(fg.copy(alpha = 0.03f), 46f * u, c)
        drawCircle(field, 46f * u, c, style = Stroke(1.5f * u))
        for (i in 0 until 12) {
            val q = i % 3 == 0
            drawLine(if (q) fg else muted, at(i * 30f, 42.5f), at(i * 30f, if (q) 35f else 38f), (if (q) 2.5f else 1.5f) * u, StrokeCap.Round)
        }
        drawLine(fg, at(h + 180f, 4f), at(h, 23f), 4.5f * u, StrokeCap.Round)
        drawLine(fg, at(m + 180f, 4f), at(m, 35f), 3f * u, StrokeCap.Round)
        if (sec) drawLine(accent, at(sAngle + 180f, 10f), at(sAngle, 38f), 1.2f * u, StrokeCap.Round)
        drawCircle(fg, 3f * u, c)
        if (sec) drawCircle(accent, 1.6f * u, c)
    }
}

// ---------------------------------------------------------------- Countdown

@Composable
private fun CountdownBody(w: WgCtx) {
    val cfg = remember(w.s) { PageWidgets.countdownCfg(w.s.cfg) }
    val now = tickingNow(false)
    val cd = PageWidgets.countdown(cfg, now.atZone(ZoneId.systemDefault())) ?: return
    val locale = appLocale()
    val nf = java.text.NumberFormat.getIntegerInstance(locale)
    when {
        // wie iOS: 0 Tage = "Heute"; vorbei = "Ziel erreicht" (0,75 der Wertgroesse), "seit" in der Zukunft = "Noch nicht erreicht" (muted)
        cd.today -> WgValue(listOf(stringResource(R.string.pw_cd_today) to false), w)
        cd.reached || cd.notYet -> {
            val st = wgValueStyle(w)
            Text(
                stringResource(if (cd.reached) R.string.wd_cd_reached else R.string.wd_cd_not_yet),
                style = st.copy(fontSize = st.fontSize * 0.75f, fontWeight = if (cd.reached) FontWeight.SemiBold else FontWeight.Normal),
                color = if (cd.reached) w.valueColor else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        cd.days != null -> {
            val n = cd.days.toInt()
            val unit = WidgetDesign.unitWord(pluralStringResource(R.plurals.wd_days, n, n), n.toString())
            WgValue(listOf(nf.format(cd.days) to false, " $unit" to true), w)
        }
        else -> WgValue(
            listOf(cd.hours.toString() to false, " " + stringResource(R.string.wd_hours) to true, " " + cd.minutes to false, " " + stringResource(R.string.wd_minutes) to true), w,
        )
    }
    val date = cfg.date ?: return
    val ds = bestFmt(locale, "EEEdMMMyyyy").format(date) + (cfg.time?.let { " · " + DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(it) } ?: "")
    WgSub(stringResource(if (cfg.since) R.string.wd_since else R.string.wd_until, ds))
}

// ---------------------------------------------------------------- Fortschritt, Kennzahl

@Composable
private fun ProgressBody(w: WgCtx, v: PageWidgets.Value?) {
    val p = PageWidgets.progress(w.s, v) ?: return
    val locale = appLocale()
    val raw = if (p.max > 0) maxOf(0.0, p.value / p.max * 100.0) else 0.0
    WgValue(PageWidgets.format(raw, "pct", locale), w)
    WgBar(p.fraction, w.accent, w.s.title.ifEmpty { kindName("progress") })
    val fmt = v?.fmt ?: "num"
    WgBarRow(stringResource(R.string.pw_of, PageWidgets.format(p.value, fmt, locale), PageWidgets.format(p.max, fmt, locale)), raw, w)
    // Quelle als Fuss wie Web (wg-f): Name der Datenbank, sobald der Server ihn liefert
    when (PageWidgets.cfgStr(w.s, "src")) {
        "db" -> WidgetDesign.srcContext("progress", "db", null, v?.srcName).takeIf { it.isNotEmpty() }?.let { WgFoot(it) }
        "tasks" -> WgFoot(stringResource(R.string.wd_saga_tasks))                  // Web srcName(): T('Saga-Aufgaben')
    }
}

@Composable
private fun MetricBody(w: WgCtx, v: PageWidgets.Value?) {
    val locale = appLocale()
    val s = w.s
    val fmt = PageWidgets.metricFmt(s, v)
    val value = v?.value
    WgValue(if (value == null) "–" else PageWidgets.format(value, fmt, locale), w)
    // Kontext "Rechenart · Quelle" wie Web (Name der Datenbank vom Server: src_name, nur mit Zugriff); weg, wenn gleich dem Titel
    val src = PageWidgets.cfgStr(s, "src")
    val agg = if (src == "tasks") widgetAggLabel(PageWidgets.cfgStr(s, "agg").ifEmpty { "open" })
    else widgetAggLabel(PageWidgets.cfgStr(s, "agg").ifEmpty { null })
    val ctx = WidgetDesign.srcContext("metric", src, agg, v?.srcName)
    if (ctx.isNotEmpty() && !WidgetDesign.sameText(ctx, s.title)) WgSub(ctx)
    val goal = v?.max ?: PageWidgets.metricGoal(s)
    if (goal != null && value != null) {
        val pm = if (goal != 0.0) maxOf(0.0, value / goal * 100.0) else 0.0
        WgBar((pm / 100.0).toFloat(), w.accent, stringResource(R.string.pw_goal, PageWidgets.format(goal, fmt, locale)))
        WgBarRow(stringResource(R.string.wd_pct_of_goal, PageWidgets.format(pm, "pct", locale), PageWidgets.format(goal, fmt, locale)), if (pm > 100.05) pm else 0.0, w)
    }
}

// ---------------------------------------------------------------- Zitat

@Composable
private fun QuoteBody(w: WgCtx, v: PageWidgets.Value?) {
    val today = LocalDate.now()
    // "open": einmal je Oeffnen der Seite zufaellig (nicht bei jedem Neuzeichnen)
    val raw = remember(w.s, v, today) { PageWidgets.quoteText(w.s, v, today) } ?: return
    val (text, src) = remember(raw) { WidgetDesign.quoteSplit(raw) }
    val cs = MaterialTheme.colorScheme
    val style = when (w.s.size) {
        "s" -> MaterialTheme.typography.bodyMedium
        "l" -> MaterialTheme.typography.titleLarge.copy(lineHeight = MaterialTheme.typography.titleLarge.fontSize * 1.45f)
        else -> MaterialTheme.typography.bodyLarge.copy(lineHeight = MaterialTheme.typography.bodyLarge.fontSize * 1.5f)
    }.copy(fontFamily = FontFamily.Serif, fontStyle = FontStyle.Normal)
    val (markSp, pad) = when (w.s.size) { "s" -> 32f to 20.dp; "l" -> 54f to 34.dp; else -> 42f to 26.dp }
    Box(Modifier.fillMaxWidth()) {
        Text(
            "“", style = TextStyle(fontFamily = FontFamily.Serif, fontSize = markSp.sp, lineHeight = markSp.sp), color = w.accent.copy(alpha = 0.85f),
            modifier = Modifier.offset(x = (-2).dp, y = (-6).dp).clearAndSetSemantics { },
        )
        Column(Modifier.padding(start = pad), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(text, style = style, color = cs.onSurface, maxLines = WidgetDesign.quoteLines(w.s.size), overflow = TextOverflow.Ellipsis)
            if (src != null) WgSub("— $src", maxLines = 1)
        }
    }
}

// ---------------------------------------------------------------- Wetter

@Composable
private fun weatherName(code: Int?): String {
    val c = code ?: return ""
    val t = listOf(0 to R.string.wd_wx_0, 1 to R.string.wd_wx_1, 2 to R.string.wd_wx_2, 3 to R.string.wd_wx_3, 45 to R.string.wd_wx_45,
        51 to R.string.wd_wx_51, 61 to R.string.wd_wx_61, 66 to R.string.wd_wx_66, 71 to R.string.wd_wx_71, 77 to R.string.wd_wx_77,
        80 to R.string.wd_wx_80, 85 to R.string.wd_wx_85, 95 to R.string.wd_wx_95)
    return t.lastOrNull { it.first <= c }?.let { stringResource(it.second) } ?: ""
}

@Composable
private fun WeatherBody(w: WgCtx, v: PageWidgets.Value?) {
    val x = PageWidgets.weather(v?.data)
    if (x == null) {
        WgError(stringResource(R.string.e1_weather_unavailable))
        return
    }
    val cs = MaterialTheme.colorScheme
    val locale = appLocale()
    val nf = java.text.NumberFormat.getIntegerInstance(locale)
    val unit = if (x.imperial) "°F" else "°C"
    fun deg(d: Double?) = d?.let { nf.format(Math.round(it)) + "°" } ?: "–"
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        WxIcon(x.code, 34.dp, w.accent)
        WgValue((x.now?.let { nf.format(Math.round(it)) } ?: "–") + " " + unit, w)
    }
    val sub = listOfNotNull(
        x.place.ifEmpty { null }, weatherName(x.code).ifEmpty { null },
        x.wind?.let { stringResource(R.string.wd_wind, nf.format(Math.round(it)), if (x.imperial) "mph" else "km/h").replace(Regex("(\\d) (mph|km/h)"), "$1 $2") },
    )
    if (sub.isNotEmpty()) WgSub(sub.joinToString(" · "))
    val days = x.days.drop(1)
    // TalkBack: je Tag eine Zeile ("Di., Regen, 12° / 5°, 40 %") statt Emoji und Zahlen einzeln
    val dayNames = days.map { weatherName(it.code) }
    val rainDesc = days.map { d -> d.rain?.takeIf { it > 0 }?.let { stringResource(R.string.wd_wx_rain_desc, it) } }
    fun dayDesc(i: Int): String {
        val d = days[i]
        return listOfNotNull(
            d.day.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, locale), dayNames[i].ifEmpty { null },
            deg(d.max) + " / " + deg(d.min), rainDesc[i],
        ).joinToString(", ")
    }
    if (days.isNotEmpty()) {
        val small = MaterialTheme.typography.bodySmall
        if (WidgetDesign.weatherAsList(w.widthDp)) {
            // schmal: Liste Tag | Symbol | Regen | max/min
            Column {
                days.forEachIndexed { i, d ->
                    if (i > 0) HorizontalDivider(color = cs.outlineVariant)
                    Row(Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = dayDesc(i) }.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(d.day.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, locale), style = small, color = cs.onSurfaceVariant, modifier = Modifier.width(34.dp), maxLines = 1)
                        Box(Modifier.width(22.dp), contentAlignment = Alignment.Center) { WxIcon(d.code, 20.dp, w.accent) }
                        Text(
                            if (WidgetDesign.weatherRain(w.widthDp) && (d.rain ?: 0) > 0) "💧" + d.rain + " %" else "",
                            style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f), maxLines = 1,
                        )
                        MaxMin(deg(d.max), deg(d.min), small)
                    }
                }
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                days.forEachIndexed { i, d ->
                    Column(
                        Modifier.weight(1f).clearAndSetSemantics { contentDescription = dayDesc(i) }.clip(RoundedCornerShape(SagaRadius.s)).background(cs.onSurface.copy(alpha = 0.04f)).padding(vertical = 5.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(d.day.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, locale), style = small, color = cs.onSurfaceVariant, maxLines = 1)
                        WxIcon(d.code, 24.dp, w.accent, Modifier.padding(vertical = 2.dp))
                        MaxMin(deg(d.max), deg(d.min), small)
                        Text(if ((d.rain ?: 0) > 0) "💧" + d.rain + " %" else "", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, maxLines = 1)
                    }
                }
            }
        }
    }
    // Pflicht: Quellenangabe (CC BY 4.0)
    WgFoot(stringResource(R.string.e1_weather_source) + if (x.stale) " · " + stringResource(R.string.wd_stale) else "")
}

/** Wetter-Linien-Symbol wie im Web (WidgetDesign.WX_SVG): Sonne/Blitz in Akzent, Wolke/Schnee in Schriftfarbe, Tropfen blau; stumm fuer TalkBack */
@Composable
private fun WxIcon(code: Int?, size: Dp, accent: Color, modifier: Modifier = Modifier) {
    val kind = WidgetDesign.wxKind(code)
    val fg = MaterialTheme.colorScheme.onSurface
    if (kind == null) {
        Text("·", modifier.clearAndSetSemantics { }, color = fg)
        return
    }
    val parts = remember(kind) { WidgetDesign.WX_SVG[kind].orEmpty().map { it.layer to PathParser().parsePathString(it.path).toPath() } }
    Canvas(modifier.size(size).clearAndSetSemantics { }) {
        val k = this.size.minDimension / 24f
        scale(k, k, pivot = Offset.Zero) {
            parts.forEach { (layer, path) ->
                val c = when (layer) { WidgetDesign.WxLayer.ACCENT -> accent; WidgetDesign.WxLayer.WET -> WX_WET; else -> fg }
                drawPath(path, c, style = Stroke(1.6f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}

/** Regentropfen-Blau wie Web .wx-wet */
private val WX_WET = Color(0xFF5AA9E6)

/** Flamme der Serie (Web WG_FLAME, 12 dp), stumm - die Zahl daneben beschreibt die Zeile */
@Composable
private fun FlameIcon(color: Color) {
    val path = remember { PathParser().parsePathString(WidgetDesign.FLAME).toPath() }
    Canvas(Modifier.size(12.dp).clearAndSetSemantics { }) {
        val k = size.minDimension / 24f
        scale(k, k, pivot = Offset.Zero) { drawPath(path, color) }
    }
}

@Composable
private fun MaxMin(max: String, min: String, style: TextStyle) {
    Text(
        buildAnnotatedString {
            append("$max ")
            withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) { append(min) }
        },
        style = style.copy(fontFeatureSettings = "tnum"), color = MaterialTheme.colorScheme.onSurface, maxLines = 1, softWrap = false,
    )
}

// ---------------------------------------------------------------- Gewohnheiten

@Composable
private fun HabitBody(w: WgCtx, v: PageWidgets.Value?, env: BlockEnv) {
    val h = PageWidgets.habits(v?.data) ?: return
    val cs = MaterialTheme.colorScheme
    val locale = appLocale()
    val scope = rememberCoroutineScope()
    val id = w.s.id ?: return
    val dark = cs.surface.luminance() < 0.5f
    var failed by remember { mutableStateOf(false) }
    val lay = WidgetDesign.habitLayout(h.days.size, w.widthDp, w.s.size)
    val shown = h.days.takeLast(lay.shown)
    val offset = h.days.size - shown.size
    val heat = lay.heat
    val gap = if (heat) 2.dp else 3.dp
    val maxCell = if (heat) (if (w.s.size == "l") 17.dp else 13.dp) else when (w.s.size) { "s" -> 19.dp; "l" -> 30.dp; else -> 22.dp }
    val nameW = (w.widthDp * 0.22f).coerceIn(80f, 144f).dp
    val statW = 104.dp
    val cellsW = if (lay.wide) w.widthDp.dp - nameW - statW - 20.dp else w.widthDp.dp
    val n = maxOf(1, shown.size)
    val cell = minOf(maxCell, (cellsW - gap * (n - 1)) / n)
    val arrangement = if (heat) Arrangement.spacedBy(gap) else Arrangement.SpaceBetween
    val dayFmt = remember(locale) { bestFmt(locale, "dMMM") }
    val labelStyle = MaterialTheme.typography.labelSmall
    @Composable
    fun Cells(content: @Composable (Int, LocalDate) -> Unit) {
        Row(Modifier.width(cellsW), horizontalArrangement = arrangement) { shown.forEachIndexed { j, d -> content(j, d) } }
    }
    @Composable
    fun Stats(x: PageWidgets.Habit, modifier: Modifier = Modifier) {
        val desc = pluralStringResource(R.plurals.wd_habit_rate, x.streak, x.pct, x.streak)
        Row(modifier.semantics(mergeDescendants = true) { contentDescription = desc }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End)) {
            Text(PageWidgets.format(x.pct.toDouble(), "pct", locale), style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"), color = cs.onSurface, maxLines = 1)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                FlameIcon(Color(Gestalten.textArgb("orange", dark)))
                Text(x.streak.toString(), style = labelStyle.copy(fontFeatureSettings = "tnum"), color = cs.onSurfaceVariant, maxLines = 1)
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(if (w.s.size == "s") 6.dp else if (w.s.size == "l") 12.dp else 9.dp)) {
        // Kopf: Wochentage (7 Tage) bzw. jeder 5. Tag (Heatmap); in der schmalen Heatmap (Kreise) keiner
        if (!(lay.cut == 7 && !heat && h.days.size > 7)) {
            Row(Modifier.fillMaxWidth().clearAndSetSemantics { }, verticalAlignment = Alignment.CenterVertically) {
                if (lay.wide) Spacer(Modifier.width(nameW + 10.dp))
                Cells { _, d ->
                    Text(
                        if (heat) WidgetDesign.heatLabel(d.dayOfMonth, d == h.today) else d.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, locale).take(2),
                        style = labelStyle, color = cs.onSurfaceVariant, maxLines = 1, softWrap = false,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.width(cell).wrapContentWidth(unbounded = true),
                    )
                }
            }
        }
        h.habits.forEach { x ->
            @Composable
            fun Name(modifier: Modifier) = Text(x.name.ifEmpty { x.pid }, style = MaterialTheme.typography.bodyMedium, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier)
            @Composable
            fun Row2() = Cells { j, d ->
                val on = x.done.getOrNull(offset + j) == true
                val isToday = d == h.today
                // abhakbar: Zustand sagt toggleable ("aktiviert"); nur lesend steht "erledigt/offen" in der Beschreibung
                val stateL = stringResource(if (on) R.string.wd_done else R.string.wd_open)
                val desc = x.name + ", " + d.format(dayFmt) + if (h.canCheck) "" else ": $stateL"
                val accent = w.accent
                val field = cs.outline
                val off = cs.onSurface.copy(alpha = 0.10f)
                Box(
                    Modifier.size(cell)
                        .drawBehind {
                            val r = size.minDimension / 2
                            if (isToday) {
                                val o = (if (heat) 1.dp else 1.5.dp).toPx() + 1.dp.toPx()
                                if (heat) drawRoundRect(accent, Offset(-o, -o), Size(size.width + 2 * o, size.height + 2 * o), CornerRadius(3.dp.toPx() + o), style = Stroke(2.dp.toPx()))
                                else drawCircle(accent, r + o, style = Stroke(2.dp.toPx()))
                            }
                            if (heat) drawRoundRect(if (on) accent else off, cornerRadius = CornerRadius(3.dp.toPx()))
                            else if (on) drawCircle(accent, r)
                            else drawCircle(field, r - 0.75.dp.toPx(), style = Stroke(1.5.dp.toPx()))
                        }
                        .then(
                            // Regel A11y: Kaestchen mit Zustand (TalkBack: "aktiviert"/"nicht aktiviert"), wie aria-pressed im Web
                            if (h.canCheck) Modifier.toggleable(value = on, role = androidx.compose.ui.semantics.Role.Checkbox) {
                                failed = false
                                val before = v ?: return@toggleable
                                env.widgets.replace(id, before.copy(data = habitsJson(before.data, PageWidgets.habitToggled(h, x.pid, d, !on))))
                                scope.launch {
                                    try {
                                        val r = env.repo.habitCheck(env.editor.id, id, PageWidgets.habitBody(x.pid, d, !on))
                                        PageWidgets.parseSingle(r)?.let { env.widgets.replace(id, it) }
                                    } catch (e: kotlinx.coroutines.CancellationException) {
                                        throw e
                                    } catch (_: Exception) {
                                        failed = true
                                        env.widgets.replace(id, before)
                                    }
                                }
                            } else Modifier,
                        )
                        .semantics { contentDescription = desc },
                )
            }
            if (lay.wide) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Name(Modifier.width(nameW))
                    Row2()
                    Stats(x, Modifier.width(statW))
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Name(Modifier.weight(1f))
                        Stats(x)
                    }
                    Row2()
                }
            }
        }
    }
    lay.cut?.let { WgFoot(stringResource(R.string.wd_last_days, it)) }
    if (failed) Text(stringResource(R.string.dbv_not_saved, ""), style = labelStyle, color = cs.error)
    if (!h.canCheck) WgFoot(stringResource(R.string.e1_habit_readonly))
}

// ---------------------------------------------------------------- Anstehendes

/** Farbe eines Termins: "#rrggbb", Saga-Palette oder Akzent */
private fun eventColor(c: String?, dark: Boolean, accent: Color): Color {
    c ?: return accent
    if (Regex("#[0-9a-fA-F]{6}").matches(c)) return Color(0xFF000000 or c.substring(1).toLong(16))
    if (c in Gestalten.PALETTE) return Color(Gestalten.textArgb(c, dark))
    return accent
}

@Composable
private fun UpcomingBody(w: WgCtx, v: PageWidgets.Value?, env: BlockEnv) {
    val u = PageWidgets.upcoming(v?.data)
    if (u == null) {
        WgNote(stringResource(R.string.wd_no_values))
        return
    }
    val cs = MaterialTheme.colorScheme
    val dark = cs.surface.luminance() < 0.5f
    val locale = appLocale()
    val today = LocalDate.now()
    val small = w.s.size == "s"
    val large = w.s.size == "l"
    // Groesse S: hoechstens 4 Eintraege ohne Tageskoepfe (Tag steht dann rechts)
    val items = if (small) u.items.take(4) else u.items
    val more = u.more + (u.items.size - items.size)
    if (items.isEmpty()) WgNote(pluralStringResource(R.plurals.wd_upcoming_none, u.days, u.days))
    val timeFmt = remember(locale) { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale) }
    val dmFmt = remember(locale) { bestFmt(locale, "dMMM") }
    val wdFmt = remember(locale) { DateTimeFormatter.ofPattern("EEE", locale) }
    val todayL = stringResource(R.string.e1_today)
    val tomorrowL = stringResource(R.string.e1_tomorrow)
    fun dayName(d: LocalDate?): String = when (d) {
        null -> ""
        today -> todayL
        today.plusDays(1) -> tomorrowL
        // wie im Web: Wochentag kurz ohne Punkt + ", " + Tag/Monat ("Mi, 11. Nov.") - Android setzt sonst "Mi.," (EEE)
        else -> d.format(wdFmt).trimEnd('.') + ", " + d.format(dmFmt)
    }
    val head = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.04.em)
    val hideTime = w.widthDp < 200f
    Column {
        PageWidgets.upcomingGroups(items, today).forEachIndexed { gi, (k, list) ->
            if (!small) {
                Text(
                    (if (k == "overdue") stringResource(R.string.e1_overdue) else dayName(list.first().day)).uppercase(locale),
                    style = head, color = if (k == "overdue") SagaColors.current.down else cs.onSurfaceVariant,
                    modifier = Modifier.padding(top = if (gi == 0) 0.dp else 7.dp, bottom = 2.dp).semantics { heading() },
                )
            }
            list.forEach { x ->
                val over = k == "overdue"
                val clock = if (x.allday) null else io.github.yggdrasil_sh.saga.db.DbDate.parsePart(x.start)?.localTime(ZoneId.systemDefault())?.format(timeFmt)
                var tm = when {
                    over -> x.day?.format(dmFmt) ?: ""
                    x.allday -> if (x.k == "event") stringResource(R.string.wd_allday) else ""
                    else -> clock ?: ""
                }
                if (small && !over) tm = dayName(x.day).substringBefore(',') + if (clock != null) " $clock" else ""
                val kindL = stringResource(if (x.k == "task") R.string.wd_task else R.string.wd_event)
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 28.dp).clip(RoundedCornerShape(SagaRadius.s))
                        .then(if (x.note != null) Modifier.clickable { env.onLink("/notes#${x.note}") } else Modifier)
                        .semantics(mergeDescendants = true) { contentDescription = "$kindL: " + x.title + (x.cal?.let { " · $it" } ?: "") + if (tm.isNotEmpty()) ", $tm" else "" }
                        .padding(vertical = 2.dp),
                    verticalAlignment = if (large) Alignment.Top else Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(Modifier.size(16.dp, if (large) 20.dp else 16.dp), contentAlignment = Alignment.Center) {
                        if (x.k == "task") Box(Modifier.size(12.dp).border(1.5.dp, cs.outline, CircleShape))
                        else Box(Modifier.size(8.dp).clip(CircleShape).background(eventColor(x.color, dark, w.accent)))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            x.title.ifEmpty { stringResource(R.string.page_untitled) }, style = MaterialTheme.typography.bodyMedium, color = cs.onSurface,
                            maxLines = if (large) 2 else 1, overflow = TextOverflow.Ellipsis,
                        )
                        if (large) x.cal?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                    if (tm.isNotEmpty() && (over || !hideTime)) {
                        Text(
                            tm, style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum", fontWeight = if (over) FontWeight.SemiBold else FontWeight.Normal),
                            color = if (over) SagaColors.current.down else cs.onSurfaceVariant, maxLines = 1, softWrap = false,
                        )
                    }
                }
            }
        }
    }
    if (more > 0) WgFoot(stringResource(R.string.wd_and_more, more))
    if (u.errors > 0) WgError(pluralStringResource(R.plurals.wd_sources_down, u.errors, u.errors))
}

/** Unbekannte Art (neuere Server/Web): kleine Karte mit Hinweis und "Im Web oeffnen" */
@Composable
private fun NewerVersionCard(s: PageWidgets.Spec, env: BlockEnv) {
    val context = LocalContext.current
    val ed = env.editor
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(SagaRadius.s)).background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(SagaRadius.s)).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SagaIcon(SagaIcons.info, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 10.dp).size(20.dp))
        Column(Modifier.weight(1f)) {
            if (s.title.isNotEmpty()) Text(s.title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(stringResource(R.string.pw_newer), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!ed.isRemote) {
            SagaTextButton(onClick = {
                ed.flush()
                io.github.yggdrasil_sh.saga.ui.common.openUrl(context, Gestalten.webUrl(env.repo.absoluteUrl("notes"), ed.id, s.id))
            }) { Text(stringResource(R.string.gest_open_web)) }
        }
    }
}


/** lokale Aenderung ins data-JSON zurueck (nur done-Listen), damit das Raster sofort umspringt */
private fun habitsJson(data: kotlinx.serialization.json.JsonObject?, h: PageWidgets.Habits): kotlinx.serialization.json.JsonObject? {
    data ?: return null
    val hs = (data["habits"] as? kotlinx.serialization.json.JsonArray).orEmpty().map { e ->
        val o = e as? kotlinx.serialization.json.JsonObject ?: return@map e
        val pid = (o["pid"] as? kotlinx.serialization.json.JsonPrimitive)?.content
        val x = h.habits.firstOrNull { it.pid == pid } ?: return@map o
        kotlinx.serialization.json.JsonObject(LinkedHashMap(o).apply { put("done", kotlinx.serialization.json.JsonArray(x.done.map { kotlinx.serialization.json.JsonPrimitive(it) })) })
    }
    return kotlinx.serialization.json.JsonObject(LinkedHashMap(data).apply { put("habits", kotlinx.serialization.json.JsonArray(hs)) })
}

