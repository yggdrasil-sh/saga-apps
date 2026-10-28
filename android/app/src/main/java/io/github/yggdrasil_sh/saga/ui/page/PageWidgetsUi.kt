package io.github.yggdrasil_sh.saga.ui.page

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import io.github.yggdrasil_sh.saga.data.habitCheck
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.blocks.Gestalten
import io.github.yggdrasil_sh.saga.blocks.PageWidgets
import io.github.yggdrasil_sh.saga.data.ApiException
import io.github.yggdrasil_sh.saga.data.SagaRepository
import io.github.yggdrasil_sh.saga.data.pageWidgetValues
import io.github.yggdrasil_sh.saga.ui.common.appLocale
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

private data class SizeSpec(val minHeight: Dp, val value: @Composable () -> TextStyle, val dial: Dp)

@Composable
private fun sizeSpec(size: String): SizeSpec = when (size) {
    "s" -> SizeSpec(64.dp, { MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold) }, 56.dp)
    "l" -> SizeSpec(150.dp, { MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.SemiBold) }, 132.dp)
    else -> SizeSpec(100.dp, { MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold) }, 92.dp)
}

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
    else -> stringResource(R.string.pw_kind_widget)
}

/** Block `widget`: Karte mit Titel und Wert; unbekannte Art -> Hinweis "neuere Version noetig" */
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
    val accent = s.accent?.let { Color(Gestalten.textArgb(it, dark)) } ?: cs.primary
    val card = s.style == "card"
    val bg = when {
        !card -> Color.Transparent
        s.accent != null -> Color(Gestalten.bgArgb(s.accent, dark)).copy(alpha = if (dark) 0.35f else 0.22f)
        else -> cs.surfaceContainer
    }
    val sz = sizeSpec(s.size)
    val cache = env.widgets
    if (s.serverValue) LaunchedEffect(Unit) { cache.ensure() }
    Column(
        Modifier.fillMaxWidth().heightIn(min = sz.minHeight)
            .then(if (card) Modifier.clip(RoundedCornerShape(12.dp)).background(bg).border(1.dp, cs.outlineVariant, RoundedCornerShape(12.dp)) else Modifier)
            .padding(horizontal = if (card) 14.dp else 2.dp, vertical = if (card) 12.dp else 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            s.title.ifEmpty { kindName(s.kind) }, style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        val all = cache.values
        val v = (all as? PageWidgets.Values.Ok)?.map?.get(s.id ?: "")
        // Fehler/Zustand der Server-Werte (nur fuer Arten, die der Server rechnet)
        val problem: String? = if (!s.serverValue) null else when {
            all == null -> ""
            s.localFallback -> v?.error?.let { errorText(it) }        // festes Zitat: sonst lokal nach pick
            all is PageWidgets.Values.Unsupported -> stringResource(R.string.pw_unsupported)
            all is PageWidgets.Values.OldServer -> stringResource(R.string.pw_old_server)
            all is PageWidgets.Values.Failed -> stringResource(R.string.pw_failed)
            v == null -> stringResource(R.string.pw_pending)
            v.error != null -> errorText(v.error)
            else -> null
        }
        when {
            problem == "" -> Text("…", style = sz.value(), color = cs.onSurfaceVariant)
            problem != null -> ProblemLine(problem)
            else -> when (s.kind) {
                "clock" -> ClockBody(s, sz, accent)
                "countdown" -> CountdownBody(s, sz, accent)
                "progress" -> ProgressBody(s, v, sz, accent)
                "metric" -> MetricBody(s, v, sz, accent)
                "quote" -> QuoteBody(s, v, sz)
                "weather" -> WeatherBody(v, sz, accent)
                "habit" -> HabitBody(s, v, env, accent)
                "upcoming" -> UpcomingBody(v, env)
                "chart" -> v?.data?.let { io.github.yggdrasil_sh.saga.ui.database.ChartBlock(io.github.yggdrasil_sh.saga.db.DbChart.parseResult(it), if (s.size == "l") 280.dp else if (s.size == "s") 150.dp else 210.dp, showTable = false) }
                    ?: ProblemLine(stringResource(R.string.pw_failed))
            }
        }
    }
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

@Composable
private fun ProblemLine(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Info, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 8.dp).size(18.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

@Composable
private fun ClockBody(s: PageWidgets.Spec, sz: SizeSpec, accent: Color) {
    val c = remember(s) { PageWidgets.clock(s.cfg) }
    val now = tickingNow(c.sec)
    val locale = appLocale()
    val text = PageWidgets.clockText(now, c, locale)
    val zoneLabel = PageWidgets.zoneLabel(c.tz)
    val date = PageWidgets.clockDate(now, c, locale)
    val sub = if (zoneLabel.isEmpty()) date else "$date · $zoneLabel"
    if (c.analog) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            AnalogDial(now.atZone(PageWidgets.zone(c.tz)), c.sec, sz.dial, accent, Modifier.semantics { contentDescription = text })
            Column {
                Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    } else {
        Text(text, style = sz.value(), color = accent)
        Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Einfache Zeigeruhr: Ring, 12 Striche, Stunden-/Minutenzeiger, optional Sekundenzeiger */
@Composable
private fun AnalogDial(t: java.time.ZonedDateTime, sec: Boolean, size: Dp, accent: Color, modifier: Modifier = Modifier) {
    val ring = MaterialTheme.colorScheme.outline
    val hand = MaterialTheme.colorScheme.onSurface
    val (h, m, sAngle) = PageWidgets.handAngles(t)
    Canvas(modifier.size(size)) {
        val r = this.size.minDimension / 2f
        val c = Offset(this.size.width / 2f, this.size.height / 2f)
        drawCircle(ring, r - 1.dp.toPx(), c, style = Stroke(1.5.dp.toPx()))
        fun at(deg: Float, len: Float): Offset {
            val rad = Math.toRadians(deg.toDouble() - 90.0)
            return Offset(c.x + (cos(rad) * len).toFloat(), c.y + (sin(rad) * len).toFloat())
        }
        for (i in 0 until 12) drawLine(ring, at(i * 30f, r * 0.80f), at(i * 30f, r * 0.92f), 1.5.dp.toPx(), StrokeCap.Round)
        drawLine(hand, c, at(h, r * 0.50f), 3.dp.toPx(), StrokeCap.Round)
        drawLine(hand, c, at(m, r * 0.75f), 2.dp.toPx(), StrokeCap.Round)
        if (sec) drawLine(accent, c, at(sAngle, r * 0.82f), 1.dp.toPx(), StrokeCap.Round)
        drawCircle(accent, 2.5.dp.toPx(), c)
    }
}

@Composable
private fun CountdownBody(s: PageWidgets.Spec, sz: SizeSpec, accent: Color) {
    val cfg = remember(s) { PageWidgets.countdownCfg(s.cfg) }
    val now = tickingNow(false)
    val cd = PageWidgets.countdown(cfg, now.atZone(ZoneId.systemDefault()))
    if (cd == null) {
        ProblemLine(stringResource(R.string.pw_err_bad_cfg))
        return
    }
    val main = when {
        cd.today -> stringResource(R.string.pw_cd_today)
        cd.done && cfg.since -> stringResource(R.string.pw_cd_not_started)
        cd.done -> stringResource(R.string.pw_cd_reached)
        cd.days != null -> pluralStringResource(if (cfg.since) R.plurals.pw_cd_since_days else R.plurals.pw_cd_until_days, cd.days.toInt(), cd.days.toInt())
        else -> stringResource(if (cfg.since) R.string.pw_cd_since_hm else R.string.pw_cd_until_hm, cd.hours, cd.minutes)
    }
    Text(main, style = sz.value(), color = accent)
    val locale = appLocale()
    val target = cfg.date?.let { d ->
        DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale).format(d) + (cfg.time?.let { " · " + DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(it) } ?: "")
    }
    if (target != null) Text(target, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ProgressBody(s: PageWidgets.Spec, v: PageWidgets.Value?, sz: SizeSpec, accent: Color) {
    val p = PageWidgets.progress(s, v)
    if (p == null) {
        ProblemLine(stringResource(R.string.pw_err_bad_cfg))
        return
    }
    val locale = appLocale()
    Text(PageWidgets.format(p.percent.toDouble(), "pct", locale), style = sz.value(), color = accent)
    LinearProgressIndicator(
        progress = { p.fraction }, color = accent, trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    )
    Text(
        stringResource(R.string.pw_of, PageWidgets.format(p.value, "num", locale), PageWidgets.format(p.max, "num", locale)),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun MetricBody(s: PageWidgets.Spec, v: PageWidgets.Value?, sz: SizeSpec, accent: Color) {
    val locale = appLocale()
    val fmt = PageWidgets.metricFmt(s, v)
    val value = v?.value
    Text(if (value == null) "–" else PageWidgets.format(value, fmt, locale), style = sz.value(), color = accent)
    val goal = PageWidgets.metricGoal(s)
    if (goal != null && value != null) {
        LinearProgressIndicator(
            progress = { (value / goal).coerceIn(0.0, 1.0).toFloat() }, color = accent,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        )
        Text(stringResource(R.string.pw_goal, PageWidgets.format(goal, fmt, locale)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun QuoteBody(s: PageWidgets.Spec, v: PageWidgets.Value?, sz: SizeSpec) {
    val today = LocalDate.now()
    // "open": einmal je Oeffnen der Seite zufaellig (nicht bei jedem Neuzeichnen)
    val text = remember(s, v, today) { PageWidgets.quoteText(s, v, today) }
    if (text == null) {
        ProblemLine(stringResource(R.string.pw_no_quotes))
        return
    }
    val style = when (s.size) {
        "s" -> MaterialTheme.typography.bodyMedium
        "l" -> MaterialTheme.typography.titleLarge
        else -> MaterialTheme.typography.bodyLarge
    }
    Text(text, style = style.copy(fontStyle = FontStyle.Italic), color = MaterialTheme.colorScheme.onSurface)
}

/** Unbekannte Art (neuere Server/Web): kleine Karte mit Hinweis und "Im Web oeffnen" */
@Composable
private fun NewerVersionCard(s: PageWidgets.Spec, env: BlockEnv) {
    val context = LocalContext.current
    val ed = env.editor
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Info, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 10.dp).size(20.dp))
        Column(Modifier.weight(1f)) {
            if (s.title.isNotEmpty()) Text(s.title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(stringResource(R.string.pw_newer), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!ed.isRemote) {
            TextButton(onClick = {
                ed.flush()
                io.github.yggdrasil_sh.saga.ui.common.openUrl(context, Gestalten.webUrl(env.repo.absoluteUrl("notes"), ed.id, s.id))
            }) { Text(stringResource(R.string.gest_open_web)) }
        }
    }
}


// ---------------------------------------------------------------- 2.5 Paket 4: Wetter, Gewohnheiten, Anstehendes

@Composable
private fun WeatherBody(v: PageWidgets.Value?, sz: SizeSpec, accent: Color) {
    val w = PageWidgets.weather(v?.data)
    if (w == null) {
        ProblemLine(stringResource(R.string.e1_weather_unavailable))
        return
    }
    val locale = appLocale()
    val unit = if (w.imperial) "°F" else "°C"
    fun t(x: Double?) = x?.let { java.text.NumberFormat.getIntegerInstance(locale).format(Math.round(it)) + "°" } ?: "–"
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(PageWidgets.weatherEmoji(w.code), style = sz.value())
        Column {
            Text((w.now?.let { t(it) } ?: "–") + unit.drop(1), style = sz.value(), color = accent)
            val sub = listOfNotNull(w.place.ifEmpty { null }, w.wind?.let { java.text.NumberFormat.getIntegerInstance(locale).format(Math.round(it)) + if (w.imperial) " mph" else " km/h" })
            if (sub.isNotEmpty()) Text(sub.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (w.days.size > 1) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            w.days.drop(1).forEach { d ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(d.day.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, locale), style = MaterialTheme.typography.labelSmall)
                    Text(PageWidgets.weatherEmoji(d.code))
                    Text(t(d.max) + " " + t(d.min), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    d.rain?.let { Text("$it %", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
    if (w.stale) Text(stringResource(R.string.e1_weather_stale), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
    // Pflicht: Quellenangabe (CC BY 4.0)
    Text(stringResource(R.string.e1_weather_source), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
}

@Composable
private fun HabitBody(s: PageWidgets.Spec, v: PageWidgets.Value?, env: BlockEnv, accent: Color) {
    val h = PageWidgets.habits(v?.data)
    if (h == null || h.habits.isEmpty()) {
        ProblemLine(stringResource(R.string.pw_err_bad_cfg))
        return
    }
    val locale = appLocale()
    val scope = rememberCoroutineScope()
    val id = s.id ?: return
    val canCheck = h.canCheck
    var failed by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.width(90.dp))
            h.days.takeLast(14).forEach { d ->
                Text(d.dayOfWeek.getDisplayName(java.time.format.TextStyle.NARROW, locale), style = MaterialTheme.typography.labelSmall,
                    color = if (d == h.today) accent else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
        h.habits.forEach { x ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.width(90.dp)) {
                    Text(x.name.ifEmpty { x.pid }, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(stringResource(R.string.e1_habit_stats, x.streak, x.pct), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val shown = h.days.takeLast(14)
                val offset = h.days.size - shown.size
                shown.forEachIndexed { j, d ->
                    val on = x.done.getOrNull(offset + j) == true
                    val desc = x.name + ", " + d.format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM).withLocale(locale)) +
                        ", " + stringResource(if (on) R.string.dbv_checked else R.string.dbv_unchecked)
                    Box(Modifier.weight(1f).aspectRatio(1f).padding(2.dp).clip(RoundedCornerShape(4.dp))
                        .background(if (on) accent else MaterialTheme.colorScheme.surfaceContainerHighest)
                        .then(if (canCheck) Modifier.clickable(role = androidx.compose.ui.semantics.Role.Checkbox) {
                            failed = false
                            val before = v ?: return@clickable
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
                        } else Modifier)
                        .semantics { contentDescription = desc })
                }
            }
        }
        if (failed) Text(stringResource(R.string.dbv_not_saved, ""), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        if (!h.canCheck) Text(stringResource(R.string.e1_habit_readonly), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

@Composable
private fun UpcomingBody(v: PageWidgets.Value?, env: BlockEnv) {
    val u = PageWidgets.upcoming(v?.data)
    if (u == null) {
        ProblemLine(stringResource(R.string.pw_failed))
        return
    }
    val locale = appLocale()
    val today = LocalDate.now()
    if (u.items.isEmpty()) Text(stringResource(R.string.e1_upcoming_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val timeFmt = remember(locale) { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale) }
    PageWidgets.upcomingGroups(u.items, today).forEach { (k, list) ->
        Text(
            when (k) {
                "overdue" -> stringResource(R.string.e1_overdue)
                "today" -> stringResource(R.string.e1_today)
                "tomorrow" -> stringResource(R.string.e1_tomorrow)
                else -> runCatching { LocalDate.parse(k).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale)) }.getOrDefault(k)
            },
            style = MaterialTheme.typography.labelLarge, color = if (k == "overdue") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        list.forEach { x ->
            val time = if (x.allday) null else io.github.yggdrasil_sh.saga.db.DbDate.parsePart(x.start)?.localTime(ZoneId.systemDefault())?.format(timeFmt)
            val icon = when (x.k) { "event" -> "🗓"; "task" -> "☐"; else -> "▪" }
            Row(
                Modifier.fillMaxWidth().then(if (x.note != null) Modifier.clickable { env.onLink("/notes#${x.note}") } else Modifier).padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(icon, style = MaterialTheme.typography.bodySmall)
                if (time != null) Text(time, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(x.title.ifEmpty { "–" }, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                x.cal?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, maxLines = 1) }
            }
        }
    }
    if (u.more > 0) Text(stringResource(R.string.e1_upcoming_more, u.more), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (u.errors > 0) Text(stringResource(R.string.e1_upcoming_errors), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
}
