package io.github.yggdrasil_sh.saga.ui.database

import io.github.yggdrasil_sh.saga.ui.theme.SagaTextButton
import io.github.yggdrasil_sh.saga.ui.theme.SagaRadius
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.pluralStringResource
import io.github.yggdrasil_sh.saga.blocks.WidgetDesign
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
        is DbChart.Response.Ok -> {
            // Kontextzeile wie im Web: Rechenart, Eigenschaft, Gruppierung, Anzahl Eintraege
            val spec = st.view?.chart
            fun sv(k: String) = (spec?.get(k) as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
            val props = st.schema?.props.orEmpty()
            val nameL = stringResource(R.string.dbv_name_col)
            fun nm(id: String) = if (id == "title") nameL else props.firstOrNull { it.id == id }?.name.orEmpty()
            val aggId = sv("agg").ifEmpty { "count" }
            val agg = io.github.yggdrasil_sh.saga.ui.page.widgetAggLabel(aggId)
            val ctx = chartContext(agg, if (aggId != "count" && sv("y").isNotEmpty()) nm(sv("y")) else "", if (sv("x").isNotEmpty()) nm(sv("x")) else "", r.chart)
            ChartBlock(r.chart, showTable = true, ctx = ctx, cap = agg)
        }
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

/** Farbe eines Eintrags aus WidgetDesign.chartColors ("other" grau, "one" Widget-Farbe bzw. blau) */
@Composable
private fun chartTokenColor(token: String, accent: Color?): Color = when (token) {
    WidgetDesign.OTHER -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
    WidgetDesign.ONE -> accent ?: chartColor("blue")
    else -> chartColor(token)
}

/** Kontextzeile ueber dem Diagramm: "Summe Betrag nach Kategorie · 8 Eintraege" (Namen nur, wenn bekannt) */
@Composable
fun chartContext(agg: String, yName: String, xName: String, c: DbChart.Result): String {
    var head = agg
    if (yName.isNotEmpty()) head += " $yName"
    if (xName.isNotEmpty() && c.type != "number") head = stringResource(R.string.wd_by, head, xName)
    val rows = c.rows ?: return head
    return head + " · " + pluralStringResource(R.plurals.wd_entries, rows, rows)
}

/**
 * Diagramm wie im Web (Widget-Design-Runde 9.11.): Kontextzeile, Zeichnung, Legende (Kreis/Ring als Liste mit Werten),
 * Hinweise, Werte als Tabelle. size: Widget-Groesse S/M/L (null = Datenbank-Ansicht, Hoehe nach Breite);
 * accent: bewusst gewaehlte Widget-Farbe (eine Reihe, Gesamtwert); cap: Rechenart unter dem Gesamtwert im Ring.
 */
@Composable
fun ChartBlock(c: DbChart.Result, showTable: Boolean, size: String? = null, accent: Color? = null, ctx: String? = null, cap: String? = null) {
    val cs = MaterialTheme.colorScheme
    val locale = appLocale()
    val emptyL = stringResource(R.string.e1_chart_empty)
    val otherL = stringResource(R.string.e1_chart_other)
    val labels = remember(c, locale) { c.cats.map { DbChart.catLabel(it, c.bucket, locale, emptyL, otherL) } }
    fun fmt(v: Double?): String = if (v == null) "–" else PageWidgets.format(v, c.fmt, locale)
    val summary = stringResource(R.string.e1_chart_summary, c.cats.size, fmt(c.total))
    val valueColor = accent ?: cs.onSurface
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!ctx.isNullOrEmpty()) Text(ctx, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (c.type == "number") {
            Text(
                fmt(c.total), style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
                color = valueColor, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
            )
            c.goal?.let { Text(stringResource(R.string.pw_goal, fmt(it)), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant) }
            return@Column
        }
        if (c.cats.isEmpty() || c.series.isEmpty()) {
            Text(stringResource(R.string.wd_no_entries), color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            return@Column
        }
        val multi = c.series.size > 1 && !c.pie
        val catTok = remember(c) { WidgetDesign.chartColors(c.cats.map { it.key to it.color }, one = !c.pie && !multi) }
        val serTok = remember(c) { WidgetDesign.chartColors(c.series.map { it.key to it.color }, one = false) }
        val catCol = catTok.map { chartTokenColor(it, accent) }
        val serCol = serTok.map { chartTokenColor(it, accent) }
        val tm = rememberTextMeasurer()
        val axisStyle = TextStyle(fontSize = 11.sp, color = cs.onSurfaceVariant, fontFeatureSettings = "tnum")
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val wDp = maxWidth.value
            val hDp = WidgetDesign.chartHeight(size, wDp)
            if (c.pie) {
                val ring = WidgetDesign.ring(wDp, hDp)
                val vals = c.series.first().values.map { (it ?: 0.0).coerceAtLeast(0.0) }
                val items = c.cats.indices.map { i -> Triple(catCol[i], labels[i], fmt(vals.getOrNull(i))) }
                val totalText = fmt(c.total ?: vals.sum())
                val big = TextStyle(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum", color = valueColor)
                val capStyle = TextStyle(fontSize = 11.sp, color = cs.onSurfaceVariant)
                val capText = cap ?: stringResource(R.string.wd_total)
                val donut = c.type == "donut"
                val canvas = @Composable {
                    // Nacharbeit 10: Tippen auf ein Segment zeigt Name, Wert und Anteil (Web chartSvg mark/showTip)
                    var tip by remember(c) { mutableStateOf<ChartTip?>(null) }
                    val sum = vals.filter { it > 0 }.sum()
                    Box {
                        Canvas(
                            Modifier.size(ring.diameter.dp).semantics { contentDescription = summary }.pointerInput(c, vals) {
                                val sz = this.size
                                val inset = 1.dp.toPx()
                                detectTapGestures { o ->
                                    val r = min(sz.width, sz.height) / 2f - inset
                                    val ri = if (donut) r * 0.62f else 0f
                                    val cx = sz.width / 2f
                                    val cy = sz.height / 2f
                                    val i = WidgetDesign.pieAt(o.x - cx, o.y - cy, r, ri, vals)
                                    tip = i?.let {
                                        val mid = Math.toRadians(WidgetDesign.pieMidDeg(vals, it) - 90.0)
                                        val rm = if (donut) (r + ri) / 2 else r * 0.6f
                                        ChartTip(
                                            Offset(cx + (kotlin.math.cos(mid) * rm).toFloat(), cy + (kotlin.math.sin(mid) * rm).toFloat()),
                                            labels[it], WidgetDesign.tipValue(fmt(vals[it]), PageWidgets.format(vals[it] / sum * 100.0, "pct", locale), null, ""), catCol[it],
                                        )
                                    }
                                }
                            },
                        ) {
                            drawRound(vals, catCol, donut, totalText, capText, tm, big, capStyle)
                        }
                        tip?.let { ChartTipPopup(it) { tip = null } }
                    }
                }
                if (ring.beside) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        canvas()
                        ChartLegendList(items, ring.legendMax, Modifier.weight(1f).widthIn(max = 320.dp))
                    }
                } else {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        canvas()
                        ChartLegendList(items, ring.legendMax, Modifier.fillMaxWidth())
                    }
                }
            } else {
                val tiny = wDp < 180f
                val goalL = stringResource(R.string.wd_goal_short)
                val gl = if (c.goal != null && !tiny) goalL + " " + WidgetDesign.axisText(c.goal, c.fmt, locale) else null
                val glSide = gl != null && !c.horizontal && wDp >= 300f
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (tiny) {
                        Text(fmt(c.total), style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"), color = valueColor, maxLines = 1)
                    }
                    val h = if (tiny) minOf(hDp, 72f) else hDp
                    val grid = cs.outlineVariant.copy(alpha = 0.7f)
                    val zeroC = cs.onSurfaceVariant.copy(alpha = 0.45f)
                    val goalC = cs.onSurfaceVariant
                    val cardC = cs.surfaceContainerLow
                    val oneCol = chartTokenColor(WidgetDesign.ONE, accent)
                    val nTicks = WidgetDesign.tickCount(wDp, h)
                    val axisFmt: (Double) -> String = { WidgetDesign.axisText(it, c.fmt, locale) }
                    val density = LocalDensity.current
                    val wPx = with(density) { wDp.dp.toPx() }
                    val hPx = with(density) { h.dp.toPx() }
                    // gleiche Flaeche wie beim Zeichnen: fuer Tippen (Tooltip) und TalkBack-Marken je Kategorie
                    val frame = remember(c, labels, wPx, hPx, tiny, gl, glSide, nTicks, density.density, axisStyle) {
                        xyFrame(c, labels, wPx, hPx, density.density, tm, axisStyle, tiny, gl, glSide, nTicks, axisFmt)
                    }
                    var tip by remember(c) { mutableStateOf<ChartTip?>(null) }
                    val totalWord = stringResource(R.string.wd_total)
                    Box(Modifier.fillMaxWidth().height(h.dp)) {
                        Canvas(
                            Modifier.fillMaxWidth().height(h.dp).semantics { contentDescription = summary }.pointerInput(c, frame) {
                                detectTapGestures { o -> tip = xyTip(c, frame, o, multi, catCol, serCol, oneCol, labels, totalWord) { fmt(it) } }
                            },
                        ) {
                            drawXY(c, multi, catCol, serCol, oneCol, labels, grid, zeroC, goalC, cardC, tm, axisStyle, tiny, gl, glSide, nTicks, axisFmt)
                        }
                        // TalkBack: je Kategorie eine Marke "Name: Wert" (bei mehreren Reihen alle Reihen) - Lage wie das Band
                        val band = with(density) { frame.band.toDp() }
                        frame.let { f ->
                            for (i in 0 until f.n) {
                                val desc = labels[i] + ": " + if (multi) c.series.joinToString(", ") { sr -> sr.label.ifEmpty { "–" } + " " + fmt(sr.values.getOrNull(i)) }
                                else fmt(c.series.firstOrNull()?.values?.getOrNull(i))
                                val at = with(density) { (f.start + f.band * i).toDp() }
                                val across = with(density) { (if (f.horiz) f.pl else f.pt).toDp() }
                                val acrossLen = with(density) { (if (f.horiz) f.pw else f.ph).toDp() }
                                Box(
                                    (if (f.horiz) Modifier.offset(across, at).size(acrossLen, band) else Modifier.offset(at, across).size(band, acrossLen))
                                        .semantics { contentDescription = desc },
                                )
                            }
                        }
                        tip?.let { ChartTipPopup(it) { tip = null } }
                    }
                    // Legende: mehrere Reihen; Ziellinie als Eintrag, wenn neben der Linie kein Platz ist
                    val lg = ArrayList<Pair<Color?, String>>()
                    if (multi) c.series.forEachIndexed { i, s -> lg += serCol[i] to s.label.ifEmpty { "–" } }
                    if (gl != null && !c.horizontal && !glSide) lg += null to gl
                    if (lg.isNotEmpty()) ChartLegendFlow(lg, if (wDp < 260f) 4 else 10)
                }
            }
        }
        if (c.other > 0) Text(stringResource(R.string.wd_other_note, c.other), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        if (c.truncated) Text(stringResource(R.string.wd_truncated), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        if (showTable) {
            var open by remember { mutableStateOf(false) }
            SagaTextButton(onClick = { open = !open }) { Text(stringResource(if (open) R.string.e1_chart_hide_values else R.string.e1_chart_values)) }
            if (open) ChartTable(c, labels) { fmt(it) }
        }
    }
}

/** Kreis/Ring-Legende: Liste Farbe | Name | Wert rechts, hoechstens max Zeilen ("+ n weitere") */
@Composable
private fun ChartLegendList(items: List<Triple<Color, String, String>>, max: Int, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val (shown, more) = WidgetDesign.legendCut(items.size, max)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.take(shown).forEach { (col, name, v) ->
            Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(col))
                Text(name, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(v, style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"), color = cs.onSurface, maxLines = 1, softWrap = false)
            }
        }
        if (more > 0) Text(stringResource(R.string.wd_more, more), style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic), color = cs.onSurfaceVariant)
    }
}

/** Legende mehrerer Reihen (ohne Werte); Farbe null = Ziellinie (gestrichelt) */
@Composable
private fun ChartLegendFlow(items: List<Pair<Color?, String>>, max: Int) {
    val cs = MaterialTheme.colorScheme
    val (shown, more) = WidgetDesign.legendCut(items.size, max)
    val dash = cs.onSurfaceVariant
    FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.take(shown).forEach { (col, l) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (col != null) Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(col))
                else Canvas(Modifier.size(12.dp, 10.dp)) {
                    drawLine(dash, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx())))
                }
                Text(l, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (more > 0) Text(stringResource(R.string.wd_more, more), style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic), color = cs.onSurfaceVariant)
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

/** Tooltip eines Diagramms: Ort (Pixel in der Zeichenflaeche), Kopf (Kategorie, ggf. Reihe), Wert, Farbe der Marke */
private data class ChartTip(val at: Offset, val name: String, val value: String, val color: Color)

/** Tooltip zur getippten Stelle eines Achsendiagramms (null = keine Marke dort): Reihe nach Ort im Band bzw. Stapel/Naehe */
private fun xyTip(
    c: DbChart.Result, f: XYFrame, o: Offset, multi: Boolean, catCol: List<Color>, serCol: List<Color>, oneCol: Color, labels: List<String>,
    totalWord: String, fmt: (Double?) -> String,
): ChartTip? {
    val along = if (f.horiz) o.y else o.x
    val across = if (f.horiz) o.x else o.y
    val i = WidgetDesign.bandAt(along, f.start, f.band, f.n) ?: return null
    val stack = c.stack && multi
    val line = c.type == "line" || c.type == "area"
    val col = c.series.map { it.values.getOrNull(i) }
    val si = when {
        !multi -> 0
        stack -> WidgetDesign.stackedSeriesAt(col, f.valueAt(across))
        line -> col.indices.filter { col[it] != null }.minByOrNull { abs(f.vpos(col[it]!!) - across) }
        else -> WidgetDesign.groupedSeriesAt(along, f.start + f.band * i, f.band, f.n, c.series.size)
    } ?: return null
    val v = col.getOrNull(si) ?: return null
    val top = if (stack) WidgetDesign.stackTop(col, si) else v
    val mid = f.start + f.band * (i + 0.5f)
    val alongAt = if (multi && !stack && !line) {
        val inner = WidgetDesign.barInner(f.band, f.n)
        f.start + f.band * i + (f.band - inner) / 2 + inner / c.series.size * (si + 0.5f)
    } else mid
    val at = if (f.horiz) Offset(f.vpos(top), f.start + f.band * i) else Offset(alongAt, if (line) f.vpos(top) else f.vpos(maxOf(top, 0.0)))
    val s = c.series[si]
    val total = if (stack && line) fmt(top) else null                       // Web: gestapelte Linie "Wert (Gesamt Summe)"
    return ChartTip(
        at, WidgetDesign.tipName(labels[i], s.label, multi), WidgetDesign.tipValue(fmt(v), null, total, totalWord),
        if (multi) serCol[si % serCol.size] else if (line) oneCol else catCol[i % catCol.size],
    )
}

/** Tooltip wie Web .ch-tip: Kaestchen ueber der Marke (passt es nicht, darunter), innerhalb der Diagrammbreite; Tippen ausserhalb schliesst */
@Composable
private fun ChartTipPopup(tip: ChartTip, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val gap = with(density) { 10.dp.roundToPx() }
    val below = with(density) { 14.dp.roundToPx() }
    val provider = remember(tip.at, gap, below) {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
                val ax = anchorBounds.left + tip.at.x
                val ay = anchorBounds.top + tip.at.y
                var x = (ax - popupContentSize.width / 2f).toInt()
                x = x.coerceAtMost(anchorBounds.right - popupContentSize.width).coerceAtLeast(anchorBounds.left)
                x = x.coerceAtMost(windowSize.width - popupContentSize.width).coerceAtLeast(0)
                var y = (ay - popupContentSize.height - gap).toInt()
                if (y < anchorBounds.top || y < 0) y = (ay + below).toInt()
                return IntOffset(x, y)
            }
        }
    }
    val shape = RoundedCornerShape(SagaRadius.s)
    Popup(popupPositionProvider = provider, onDismissRequest = onDismiss, properties = PopupProperties(focusable = false, dismissOnClickOutside = true)) {
        Column(
            Modifier.widthIn(max = 256.dp).shadow(6.dp, shape).clip(shape).background(cs.surfaceContainer).border(1.dp, cs.outlineVariant, shape)
                .padding(horizontal = 9.dp, vertical = 6.dp).clearAndSetSemantics { },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(tip.color))
                Text(tip.name, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(tip.value, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"), color = cs.onSurface, maxLines = 1)
        }
    }
}

/** Kreis bzw. Ring mit kleinen Fugen; im Ring Gesamtwert gross + Rechenart klein */
private fun DrawScope.drawRound(
    vals: List<Double>, colors: List<Color>, donut: Boolean, total: String, cap: String, tm: TextMeasurer, big: TextStyle, capStyle: TextStyle,
) {
    val sum = vals.sum()
    if (sum <= 0) return
    val d = min(size.width, size.height)
    val r = d / 2 - 1.dp.toPx()
    val ri = if (donut) r * 0.62f else 0f
    val c = Offset(size.width / 2, size.height / 2)
    val segs = vals.count { it > 0 }
    val gapDeg = if (segs > 1) Math.toDegrees(min(0.02, 1.2 / (r / density))).toFloat() else 0f
    var a = -90f
    vals.forEachIndexed { i, v ->
        if (v <= 0) return@forEachIndexed
        val sweep = (v / sum * 360).toFloat()
        val s0 = a + gapDeg / 2
        val sw = maxOf(0.01f, sweep - gapDeg)
        val col = colors[i % colors.size]
        if (donut) {
            val rm = (r + ri) / 2
            drawArc(col, s0, sw, false, Offset(c.x - rm, c.y - rm), Size(rm * 2, rm * 2), style = Stroke(r - ri))
        } else drawArc(col, s0, sw, true, Offset(c.x - r, c.y - r), Size(r * 2, r * 2))
        a += sweep
    }
    if (donut) {
        val riDp = ri / density
        val fs = WidgetDesign.ringValueSize(riDp, total)
        val m = tm.measure(total, big.copy(fontSize = (fs / fontScale).sp), maxLines = 1)
        val showCap = riDp > 34f
        val mc = if (showCap) tm.measure(cap, capStyle, maxLines = 1, overflow = TextOverflow.Ellipsis,
            constraints = androidx.compose.ui.unit.Constraints(maxWidth = (ri * 1.6f).toInt().coerceAtLeast(1))) else null
        val hTot = m.size.height + (mc?.size?.height ?: 0)
        drawText(m, topLeft = Offset(c.x - m.size.width / 2, c.y - hTot / 2f))
        if (mc != null) drawText(mc, topLeft = Offset(c.x - mc.size.width / 2, c.y - hTot / 2f + m.size.height))
    }
}

/** Zeichenflaeche eines Achsendiagramms (Pixel): Raender, Ticks, Baender - gemeinsam fuer Zeichnen, Tippen und TalkBack */
private class XYFrame(val pl: Float, val pt: Float, val pw: Float, val ph: Float, val ticks: List<Double>, val horiz: Boolean, val n: Int) {
    val tMin: Double get() = ticks.first()
    val tMax: Double get() = ticks.last()
    val band: Float get() = (if (horiz) ph else pw) / maxOf(1, n)
    /** Beginn der Baender (oben bei waagrechten, links bei senkrechten Balken) */
    val start: Float get() = if (horiz) pt else pl
    fun vpos(v: Double): Float = if (horiz) pl + ((v - tMin) / (tMax - tMin)).toFloat() * pw else pt + ph - ((v - tMin) / (tMax - tMin)).toFloat() * ph
    fun valueAt(p: Float): Double = if (horiz) tMin + (p - pl) / pw * (tMax - tMin) else tMin + (pt + ph - p) / ph * (tMax - tMin)
}

private fun xyFrame(
    c: DbChart.Result, labels: List<String>, width: Float, height: Float, dp: Float, tm: TextMeasurer, style: TextStyle, tiny: Boolean,
    gl: String?, glSide: Boolean, nTicks: Int, fmt: (Double) -> String,
): XYFrame {
    val (lo0, hi0) = c.yRange()
    val ticks = DbChart.niceTicks(lo0, hi0, n = nTicks, integer = c.integerValues)
    val horiz = c.horizontal
    val tlab = ticks.map(fmt)
    var pl: Float
    var pr = 8f * dp
    var pt = 8f * dp
    var pb = 20f * dp
    fun tw(s: String) = tm.measure(s, style, maxLines = 1).size.width.toFloat()
    if (tiny) {
        pl = 2f * dp; pr = 2f * dp; pt = 4f * dp; pb = 2f * dp
    } else if (horiz) {
        pl = min(width * 0.4f, (labels.maxOfOrNull { min(tw(it), 120f * dp) } ?: 0f) + 10f * dp)
        pr = maxOf(8f * dp, tw(tlab.last()) / 2 + 2f * dp)
    } else {
        pl = min(width * 0.4f, (tlab.maxOfOrNull { tw(it) } ?: 0f) + 10f * dp)
    }
    if (gl != null && horiz) pt = 18f * dp
    if (glSide && gl != null) pr = tw(gl) + 12f * dp
    return XYFrame(pl, pt, width - pl - pr, height - pt - pb, ticks, horiz, c.cats.size)
}

private fun DrawScope.drawXY(
    c: DbChart.Result, multi: Boolean, catCol: List<Color>, serCol: List<Color>, oneCol: Color, labels: List<String>, grid: Color, zeroC: Color, goalC: Color,
    cardC: Color, tm: TextMeasurer, style: TextStyle, tiny: Boolean, gl: String?, glSide: Boolean, nTicks: Int, fmt: (Double) -> String,
) {
    val stack = c.stack && multi
    val f = xyFrame(c, labels, size.width, size.height, density, tm, style, tiny, gl, glSide, nTicks, fmt)
    val ticks = f.ticks
    val horiz = f.horiz
    val tlab = ticks.map(fmt)
    val n = f.n
    val dp = density
    val pl = f.pl
    val pt = f.pt
    val pw = f.pw
    val ph = f.ph
    fun tw(s: String) = tm.measure(s, style, maxLines = 1).size.width.toFloat()
    fun vpos(v: Double): Float = f.vpos(v)
    if (!tiny) {
        ticks.forEachIndexed { j, t ->
            val p = vpos(t)
            val col = if (t == 0.0) zeroC else grid
            val m = tm.measure(tlab[j], style, maxLines = 1)
            if (horiz) {
                drawLine(col, Offset(p, pt), Offset(p, pt + ph), 1f)
                drawText(m, topLeft = Offset((p - m.size.width / 2).coerceIn(0f, size.width - m.size.width), size.height - m.size.height.toFloat()))
            } else {
                drawLine(col, Offset(pl, p), Offset(pl + pw, p), 1f)
                drawText(m, topLeft = Offset(pl - 6f * dp - m.size.width, p - m.size.height / 2))
            }
        }
    }
    val band = (if (horiz) ph else pw) / maxOf(1, n)
    if (!tiny) {
        var every = 1
        if (horiz) every = maxOf(1, kotlin.math.ceil(14f * dp / band).toInt())
        else {
            val w = labels.map { min(tw(it), 90f * dp) }
            fun fits(e: Int): Boolean {
                var i = 0
                while (i + e < n) {
                    if (band * e < (w[i] + w[i + e]) / 2 + 6f * dp) return false
                    i += e
                }
                return true
            }
            while (every < n && !fits(every)) every++
        }
        labels.forEachIndexed { i, l ->
            if (i % every != 0) return@forEachIndexed
            val mid = (if (horiz) pt else pl) + band * (i + 0.5f)
            val maxW = if (horiz) pl - 10f * dp else band * every - 6f * dp
            val m = tm.measure(l, style, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                constraints = androidx.compose.ui.unit.Constraints(maxWidth = maxW.toInt().coerceAtLeast(1)))
            if (horiz) drawText(m, topLeft = Offset(pl - 6f * dp - m.size.width, mid - m.size.height / 2))
            else drawText(m, topLeft = Offset(mid - m.size.width / 2, size.height - m.size.height.toFloat()))
        }
    }
    val line = (c.type == "line" || c.type == "area") && !(tiny && c.type != "line" && c.type != "area")
    if (!line) {
        val k = if (stack) 1 else c.series.size
        val inner = WidgetDesign.barInner(band, n)
        val bw = inner / k
        val gap = if (k > 1) min(2f * dp, bw * 0.15f) else 0f
        val rr = if (stack) min(2f * dp, (bw - gap) / 4) else min(4f * dp, (bw - gap) / 3)
        val base = vpos(0.0)
        val accP = DoubleArray(n)
        val accN = DoubleArray(n)
        c.series.forEachIndexed { si, s ->
            s.values.forEachIndexed { i, v ->
                if (v == null || i >= n) return@forEachIndexed
                val p0: Float
                val p1: Float
                val off: Float
                if (stack) {
                    val a = if (v >= 0) accP[i] else accN[i]
                    val b = a + v
                    if (v >= 0) accP[i] = b else accN[i] = b
                    p0 = vpos(a); p1 = vpos(b); off = (band - inner) / 2
                } else {
                    p0 = base; p1 = vpos(v); off = (band - inner) / 2 + si * bw
                }
                val st = (if (horiz) pt else pl) + band * i + off
                val col = if (multi) serCol[si % serCol.size] else catCol[i % catCol.size]
                val w = maxOf(1f, bw - gap)
                val len = abs(p1 - p0)
                val cr = CornerRadius(min(rr, len / 2))
                if (horiz) drawRoundRect(col, Offset(min(p0, p1), st), Size(len, w), cr)
                else drawRoundRect(col, Offset(st, min(p0, p1)), Size(w, len), cr)
                if (stack && len > 0) {
                    if (horiz) drawLine(cardC, Offset(maxOf(p0, p1), st), Offset(maxOf(p0, p1), st + w), 1f)
                    else drawLine(cardC, Offset(st, min(p0, p1)), Offset(st + w, min(p0, p1)), 1f)
                }
            }
        }
    } else {
        // Linie/Flaeche; gestapelte Flaeche: kumulierte Grundlinie je Kategorie (oberste Kurve = Summe)
        val cum = DoubleArray(n)
        val cumN = DoubleArray(n)
        val dots = n <= 24 && band >= 12f * dp && !tiny
        val baseLine = vpos(maxOf(f.tMin, 0.0))
        val layers = c.series.mapIndexed { si, s ->
            val pts = ArrayList<Triple<Offset, Float, Int>>()   // Punkt, Grundlinie, Index
            s.values.forEachIndexed { i, v ->
                if (i >= n) return@forEachIndexed
                val x = pl + band * (i + 0.5f)
                if (stack) {
                    val vv = v ?: 0.0
                    val acc = if (vv < 0) cumN else cum
                    val b0 = acc[i]
                    acc[i] += vv
                    pts += Triple(Offset(x, vpos(acc[i])), vpos(b0), i)
                } else if (v != null) pts += Triple(Offset(x, vpos(v)), baseLine, i)
            }
            si to pts
        }
        if (c.type == "area") layers.forEach { (si, pts) ->
            if (pts.isEmpty()) return@forEach
            val col = if (multi) serCol[si % serCol.size] else oneCol
            val p = Path().apply {
                moveTo(pts.first().first.x, pts.first().first.y)
                pts.drop(1).forEach { lineTo(it.first.x, it.first.y) }
                pts.reversed().forEach { lineTo(it.first.x, it.second) }
                close()
            }
            drawPath(p, col.copy(alpha = 0.2f))
        }
        layers.forEach { (si, pts) ->
            if (pts.isEmpty()) return@forEach
            val col = if (multi) serCol[si % serCol.size] else oneCol
            val p = Path().apply {
                moveTo(pts.first().first.x, pts.first().first.y)
                pts.drop(1).forEach { lineTo(it.first.x, it.first.y) }
            }
            drawPath(p, col, style = Stroke(2f * dp, cap = StrokeCap.Round, join = StrokeJoin.Round))
            if (dots) pts.forEach {
                drawCircle(cardC, 4.5f * dp, it.first)
                drawCircle(col, 3f * dp, it.first)
            }
        }
    }
    // Ziellinie: gestrichelt, gedaempft, mit "Ziel"
    if (gl != null && c.goal != null) {
        val g = vpos(c.goal)
        val dash = PathEffect.dashPathEffect(floatArrayOf(4f * dp, 4f * dp))
        if (horiz) drawLine(goalC, Offset(g, pt), Offset(g, pt + ph), 1.25f * dp, pathEffect = dash)
        else drawLine(goalC, Offset(pl, g), Offset(pl + pw, g), 1.25f * dp, pathEffect = dash)
        val m = tm.measure(gl, style, maxLines = 1)
        if (horiz) drawText(m, topLeft = Offset((g - m.size.width / 2).coerceIn(pl, size.width - m.size.width - 1), pt - 6f * dp - m.size.height))
        else if (glSide) drawText(m, topLeft = Offset(pl + pw + 6f * dp, g - m.size.height / 2))
    }
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
                            Box(Modifier.width(dayW * len).height(20.dp).clip(RoundedCornerShape(SagaRadius.s)).background(accent.copy(alpha = 0.75f))
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
