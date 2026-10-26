package io.github.yggdrasil_sh.saga.ui.page

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
    else -> stringResource(R.string.pw_kind_widget)
}

/** Block `widget`: Karte mit Titel und Wert; unbekannte Art -> Hinweis "neuere Version noetig" */
@Composable
fun PageWidgetBlock(block: JsonObject, env: BlockEnv) {
    val s = remember(block) { PageWidgets.spec(block) }
    if (!s.known) {
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
