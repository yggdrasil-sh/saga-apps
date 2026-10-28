package io.github.yggdrasil_sh.saga.ui.database

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.blocks.InlineForms
import io.github.yggdrasil_sh.saga.blocks.PageWidgets
import io.github.yggdrasil_sh.saga.data.SagaRepository
import io.github.yggdrasil_sh.saga.db.DbChart
import io.github.yggdrasil_sh.saga.ui.common.appLocale
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.abs
import kotlin.math.min

// 2.5 chart1: Diagramme mit eigener Compose-Canvas (keine Bibliothek) - Balken (auch waagrecht, gestapelt), Linie,
// Flaeche, Kreis, Ring, Zahl; Ziellinie; Achsen mit schoenen Werten (DbChart.niceTicks). Werte zusaetzlich als Tabelle.

@Composable
fun chartColor(name: String): Color = Color(InlineForms.textArgb(name, MaterialTheme.colorScheme.surface.luminance() < 0.5f))

/** Diagramm-Ansicht der Datenbank (Ergebnis vom Server) */
@Composable
fun DbChartView(st: DbViewState, repo: SagaRepository, wide: Boolean) {
    when (val r = st.chart) {
        null -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        is DbChart.Response.Error -> ChartProblem(r.code)
        is DbChart.Response.Ok -> ChartBlock(r.chart, if (wide) 320.dp else 240.dp, showTable = true)
    }
}

@Composable
fun ChartProblem(code: String) {
    Text(
        stringResource(
            when (code) {
                "unsupported" -> R.string.pw_unsupported
                "no_access" -> R.string.pw_err_no_access
                "bad_cfg" -> R.string.e1_chart_bad
                else -> R.string.pw_failed
            },
        ),
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp),
    )
}

/** Diagramm + Legende (+ Werte-Tabelle aufklappbar) */
@Composable
fun ChartBlock(c: DbChart.Result, height: Dp, showTable: Boolean) {
    val locale = appLocale()
    val emptyL = stringResource(R.string.e1_chart_empty)
    val otherL = stringResource(R.string.e1_chart_other)
    val labels = remember(c, locale) { c.cats.map { DbChart.catLabel(it, c.bucket, locale, emptyL, otherL) } }
    fun fmt(v: Double?): String = if (v == null) "–" else PageWidgets.format(v, c.fmt, locale)
    val summary = stringResource(R.string.e1_chart_summary, c.cats.size, fmt(c.total))
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (c.type == "number") {
            Text(fmt(c.total), style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
            c.goal?.let { Text(stringResource(R.string.pw_goal, fmt(it)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            return@Column
        }
        if (c.cats.isEmpty()) {
            Text(stringResource(R.string.dbv_empty_view), color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        val colors = if (c.pie) c.cats.mapIndexed { i, x -> chartColor(DbChart.catColor(x, i)) } else c.series.mapIndexed { i, x -> chartColor(DbChart.seriesColor(x, i)) }
        val grid = MaterialTheme.colorScheme.outlineVariant
        val axis = MaterialTheme.colorScheme.onSurfaceVariant
        val goalColor = MaterialTheme.colorScheme.error
        val tm = rememberTextMeasurer()
        val small = MaterialTheme.typography.labelSmall.copy(color = axis, fontSize = 10.sp)
        Box(Modifier.fillMaxWidth().height(height).semantics { contentDescription = summary }) {
            Canvas(Modifier.fillMaxWidth().height(height)) {
                if (c.pie) drawPie(c, colors, c.type == "donut")
                else drawXY(c, colors, labels, grid, axis, goalColor, tm, small) { fmt(it) }
            }
        }
        // Legende: Kreis -> Kategorien, mehrere Reihen -> Reihen
        val legend = if (c.pie) labels.mapIndexed { i, l -> l to colors[i] } else if (c.series.size > 1) c.series.mapIndexed { i, sr -> sr.label.ifEmpty { "–" } to colors[i] } else emptyList()
        if (legend.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                legend.forEach { (l, col) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(col))
                        Text(" $l", style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        if (c.truncated || c.other > 0) Text(stringResource(R.string.e1_chart_truncated), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (showTable) {
            var open by remember { mutableStateOf(false) }
            TextButton(onClick = { open = !open }) { Text(stringResource(if (open) R.string.e1_chart_hide_values else R.string.e1_chart_values)) }
            if (open) ChartTable(c, labels) { fmt(it) }
        }
    }
}

/** Werte als Tabelle (Barrierefreiheit) */
@Composable
fun ChartTable(c: DbChart.Result, labels: List<String>, fmt: (Double?) -> String) {
    Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        Row {
            Text("", Modifier.width(140.dp))
            c.series.forEach { s -> Text(s.label.ifEmpty { "∑" }, Modifier.width(90.dp), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        HorizontalDivider()
        labels.forEachIndexed { i, l ->
            Row(Modifier.padding(vertical = 2.dp)) {
                Text(l, Modifier.width(140.dp), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                c.series.forEach { s -> Text(fmt(s.values.getOrNull(i)), Modifier.width(90.dp), style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

private fun DrawScope.drawPie(c: DbChart.Result, colors: List<Color>, donut: Boolean) {
    val vals = c.series.firstOrNull()?.values.orEmpty().map { (it ?: 0.0).coerceAtLeast(0.0) }
    val sum = vals.sum()
    if (sum <= 0) return
    val d = min(size.width, size.height) * 0.92f
    val tl = Offset((size.width - d) / 2, (size.height - d) / 2)
    var a = -90f
    vals.forEachIndexed { i, v ->
        val sweep = (v / sum * 360).toFloat()
        if (sweep > 0f) {
            if (donut) drawArc(colors[i % colors.size], a, sweep, false, tl + Offset(d * 0.11f, d * 0.11f), Size(d * 0.78f, d * 0.78f), style = Stroke(d * 0.22f))
            else drawArc(colors[i % colors.size], a, sweep, true, tl, Size(d, d))
        }
        a += sweep
    }
}

private fun DrawScope.drawXY(
    c: DbChart.Result, colors: List<Color>, labels: List<String>, grid: Color, axis: Color, goalColor: Color,
    tm: TextMeasurer, style: TextStyle, fmt: (Double) -> String,
) {
    val (lo, hi) = c.yRange()
    val ticks = DbChart.niceTicks(lo, hi, integer = c.integerValues)
    val tMin = ticks.first()
    val tMax = ticks.last()
    val horiz = c.horizontal
    val labelW = if (horiz) min(size.width * 0.32f, 120.dp.toPx()) else ticks.maxOf { tm.measure(fmt(it), style).size.width }.toFloat() + 6.dp.toPx()
    val bottom = 18.dp.toPx()
    val left = labelW
    val plotW = size.width - left - 4.dp.toPx()
    val plotH = size.height - bottom - 4.dp.toPx()
    val n = c.cats.size
    fun valPos(v: Double): Float = ((v - tMin) / (tMax - tMin)).toFloat()
    // Raster + Achsenwerte
    ticks.forEach { t ->
        val f = valPos(t)
        if (horiz) {
            val x = left + f * plotW
            drawLine(grid, Offset(x, 0f), Offset(x, plotH), 1f)
            val m = tm.measure(fmt(t), style)
            drawText(m, topLeft = Offset((x - m.size.width / 2).coerceIn(left, size.width - m.size.width), plotH + 2.dp.toPx()))
        } else {
            val y = plotH - f * plotH
            drawLine(grid, Offset(left, y), Offset(left + plotW, y), 1f)
            val m = tm.measure(fmt(t), style)
            drawText(m, topLeft = Offset(left - m.size.width - 4.dp.toPx(), y - m.size.height / 2))
        }
    }
    val zero = valPos(0.0)
    val slot = (if (horiz) plotH else plotW) / maxOf(1, n)
    val line = c.type == "line" || c.type == "area"
    if (line) {
        c.series.forEachIndexed { si, s ->
            val col = colors[si % colors.size]
            val pts = s.values.mapIndexedNotNull { i, v -> v?.let { Offset(left + slot * (i + 0.5f), plotH - valPos(it) * plotH) } }
            if (pts.isEmpty()) return@forEachIndexed
            if (c.type == "area" && pts.size > 1) {
                val p = Path().apply {
                    moveTo(pts.first().x, plotH - zero * plotH)
                    pts.forEach { lineTo(it.x, it.y) }
                    lineTo(pts.last().x, plotH - zero * plotH)
                    close()
                }
                drawPath(p, col.copy(alpha = 0.22f))
            }
            for (i in 1 until pts.size) drawLine(col, pts[i - 1], pts[i], 2.2.dp.toPx())
            pts.forEach { drawCircle(col, 2.5.dp.toPx(), it) }
        }
    } else {
        val stacked = c.stack && c.series.size > 1
        val groups = if (stacked) 1 else c.series.size
        val barW = slot * 0.72f / maxOf(1, groups)
        for (i in 0 until n) {
            var acc = 0.0
            c.series.forEachIndexed { si, s ->
                val v = s.values.getOrNull(i) ?: return@forEachIndexed
                val col = colors[si % colors.size]
                val a0 = if (stacked) acc else 0.0
                val a1 = a0 + v
                acc = a1
                val f0 = valPos(a0)
                val f1 = valPos(a1)
                val off = slot * 0.14f + (if (stacked) 0f else si * barW)
                if (horiz) {
                    val y = slot * i + off
                    drawRect(col, Offset(left + min(f0, f1) * plotW, y), Size(abs(f1 - f0) * plotW, barW))
                } else {
                    val x = left + slot * i + off
                    drawRect(col, Offset(x, plotH - maxOf(f0, f1) * plotH), Size(barW, abs(f1 - f0) * plotH))
                }
            }
        }
    }
    // Kategorie-Beschriftung (jede k-te, damit nichts ueberlappt)
    val step = if (horiz) 1 else maxOf(1, (n * 46.dp.toPx() / plotW).toInt() + 1)
    labels.forEachIndexed { i, l ->
        if (i % step != 0) return@forEachIndexed
        val m = tm.measure(l, style, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
            constraints = androidx.compose.ui.unit.Constraints(maxWidth = (if (horiz) labelW - 4.dp.toPx() else slot * step).toInt().coerceAtLeast(1)))
        if (horiz) drawText(m, topLeft = Offset(0f, slot * i + slot / 2 - m.size.height / 2))
        else drawText(m, topLeft = Offset(left + slot * i + slot / 2 - m.size.width / 2, plotH + 2.dp.toPx()))
    }
    // Ziellinie
    c.goal?.let { g ->
        val f = valPos(g)
        val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))
        if (horiz) drawLine(goalColor, Offset(left + f * plotW, 0f), Offset(left + f * plotW, plotH), 1.5.dp.toPx(), pathEffect = dash)
        else drawLine(goalColor, Offset(left, plotH - f * plotH), Offset(left + plotW, plotH - f * plotH), 1.5.dp.toPx(), pathEffect = dash)
    }
    // Achse
    if (horiz) drawLine(axis, Offset(left + zero * plotW, 0f), Offset(left + zero * plotW, plotH), 1f)
    else drawLine(axis, Offset(left, plotH - zero * plotH), Offset(left + plotW, plotH - zero * plotH), 1f)
}

// ---------------------------------------------------------------- Zeitleiste (timeline1, nur lesen)

@Composable
fun DbTimeline(st: DbViewState, repo: SagaRepository, onOpen: (Long) -> Unit, wide: Boolean) {
    val s = st.schema ?: return
    val v = st.view ?: return
    val (bars, undated) = remember(st.rows, s, v) { DbChart.timeline(st.rows, v, s) }
    val locale = appLocale()
    val dayFmt = remember(locale) { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale) }
    val scale = v.raw["scale"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.e1_timeline_readonly), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (bars.isEmpty() && undated.isEmpty()) Text(stringResource(R.string.dbv_empty_view), color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (wide && bars.isNotEmpty()) {
            // Tablet: waagrecht, ein Balken je Zeile ueber der Tagesachse
            val first = bars.minOf { it.start }
            val last = bars.maxOf { it.end }
            val days = (java.time.temporal.ChronoUnit.DAYS.between(first, last) + 1).toInt().coerceIn(1, 3660)
            val dayW = when (scale) { "day" -> 36.dp; "month" -> 6.dp; "quarter" -> 2.5.dp; else -> 14.dp }
            val accent = MaterialTheme.colorScheme.primary
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.width(180.dp)) {
                    Box(Modifier.height(22.dp))
                    bars.forEach { b ->
                        Text(b.row.title.ifEmpty { stringResource(R.string.page_untitled) }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.height(32.dp).fillMaxWidth().clickable(onClickLabel = stringResource(R.string.dbv_open_entry)) { onOpen(b.row.id) }.padding(vertical = 6.dp))
                    }
                }
                Column(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                    Row(Modifier.height(22.dp)) {
                        var d = first
                        while (!d.isAfter(last)) {
                            val mark = when (scale) { "day" -> true; "week", null -> d.dayOfWeek == java.time.DayOfWeek.MONDAY; else -> d.dayOfMonth == 1 }
                            Box(Modifier.width(dayW)) {
                                if (mark) Text(if (scale == "month" || scale == "quarter") d.month.getDisplayName(java.time.format.TextStyle.SHORT, locale) else "${d.dayOfMonth}.${d.monthValue}.",
                                    style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false)
                            }
                            d = d.plusDays(1)
                        }
                    }
                    bars.forEach { b ->
                        val off = java.time.temporal.ChronoUnit.DAYS.between(first, b.start).toInt()
                        val len = (java.time.temporal.ChronoUnit.DAYS.between(b.start, b.end) + 1).toInt()
                        val desc = b.row.title + ": " + b.start.format(dayFmt) + " – " + b.end.format(dayFmt)
                        Row(Modifier.height(32.dp).width(dayW * days), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.width(dayW * off))
                            Box(Modifier.width(dayW * len).height(20.dp).clip(RoundedCornerShape(6.dp)).background(accent.copy(alpha = 0.75f))
                                .clickable(onClickLabel = stringResource(R.string.dbv_open_entry)) { onOpen(b.row.id) }.semantics { contentDescription = desc })
                        }
                    }
                }
            }
        } else {
            // Telefon: Liste nach Start gruppiert
            DbChart.timelineGroups(bars, scale).forEach { (start, list) ->
                Text(if (scale == "day" || scale == "week") stringResource(R.string.e1_week_of, start.format(dayFmt))
                    else start.month.getDisplayName(java.time.format.TextStyle.FULL_STANDALONE, locale) + " " + start.year,
                    style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp).semantics { heading() })
                list.forEach { b ->
                    Row(Modifier.fillMaxWidth().clickable(onClickLabel = stringResource(R.string.dbv_open_entry)) { onOpen(b.row.id) }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        io.github.yggdrasil_sh.saga.ui.common.PageIcon(b.row.icon, repo, size = 18.dp)
                        Column(Modifier.weight(1f)) {
                            Text(b.row.title.ifEmpty { stringResource(R.string.page_untitled) }, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(if (b.start == b.end) b.start.format(dayFmt) else b.start.format(dayFmt) + " – " + b.end.format(dayFmt),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
        if (undated.isNotEmpty()) {
            Text(stringResource(R.string.e1_timeline_undated), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 10.dp).semantics { heading() })
            undated.forEach { r ->
                Text(r.title.ifEmpty { stringResource(R.string.page_untitled) }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().clickable(onClickLabel = stringResource(R.string.dbv_open_entry)) { onOpen(r.id) }.padding(vertical = 8.dp))
            }
        }
    }
}
