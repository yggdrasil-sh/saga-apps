package io.github.yggdrasil_sh.saga.ui.database

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.data.SagaRepository
import io.github.yggdrasil_sh.saga.db.DbDate
import io.github.yggdrasil_sh.saga.db.DbProp
import io.github.yggdrasil_sh.saga.db.DbQuery
import io.github.yggdrasil_sh.saga.db.DbRow
import io.github.yggdrasil_sh.saga.db.DbView
import io.github.yggdrasil_sh.saga.ui.common.PageIcon
import io.github.yggdrasil_sh.saga.ui.common.appLocale
import io.github.yggdrasil_sh.saga.ui.theme.SagaIcon
import io.github.yggdrasil_sh.saga.ui.theme.SagaIcons
import kotlinx.serialization.json.JsonElement
import java.time.LocalDate

/**
 * ED-12/ED-19: ein Wert kurz - Datum als "Start 02.11." (Kurzlabel, [labeled]) bzw. Zeitraum "16.11. → 18.12.2026"
 * mit Kalender-Symbol; alles andere wie [DbValueView] (Status-Chip ueber OptChip, Person, Fortschrittsbalken).
 */
@Composable
internal fun CompactValue(p: DbProp, v: JsonElement?, fmt: DbFormat, err: String? = null, labeled: Boolean = true, maxLines: Int = 1) {
    val raw = if (p.type == "date") DbQuery.text(v).ifEmpty { null } else null
    val zone = fmt.zone
    val dv = remember(raw, zone) { raw?.let { DbDate.parse(it, zone) } }
    if (dv == null) {
        DbValueView(p, v, fmt, maxLines = maxLines, err = err)
        return
    }
    val locale = appLocale()
    val today = LocalDate.now(zone)
    val text = remember(dv, locale, today) { DbCompact.shortDate(dv, zone, today, locale) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (DbCompact.isRange(dv, zone)) {
            // Zeitraum: Symbol statt Kurzlabel; der Name bleibt fuer Bedienungshilfen in der Zeile (zusammengefasst)
            SagaIcon(SagaIcons.calendar, if (labeled) p.name else null, Modifier.padding(end = 4.dp).size(14.dp), tint = muted)
        } else if (labeled) {
            Text(p.name, style = MaterialTheme.typography.labelSmall, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(end = 4.dp))
        }
        Text(text, style = MaterialTheme.typography.labelMedium, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * ED-12: Zeile der kompakten Liste (Tabelle unter 600 dp) - Titel in bodyLarge, darunter bis zu 3 sichtbare
 * Eigenschaften als FlowRow. Ein Tipp oeffnet den Eintrag wie in der Tabelle.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CompactTableRow(st: DbViewState, repo: SagaRepository, r: DbRow, v: DbView, onOpen: (Long) -> Unit) {
    val s = st.schema ?: return
    val fmt = rememberDbFormat({ id -> repo.page(id)?.title }, st.people)
    val q = DbQuery(s)
    val picks = DbCompact.compactProps(s.visibleProps(v), { p -> !DbQuery.isEmpty(q.value(p, r)) || r.fxErr[p.id] != null })
    val tint = rowColor(st, r, v)
    Column(Modifier.fillMaxWidth().then(if (tint != null) Modifier.background(tint) else Modifier)) {
        Column(
            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .clickable(onClickLabel = stringResource(R.string.dbv_open_entry)) { onOpen(r.id) }
                .padding(horizontal = 4.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PageIcon(r.icon, repo, size = 20.dp)
                Text(r.title.ifEmpty { stringResource(R.string.page_untitled) }, style = MaterialTheme.typography.bodyLarge, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                    color = if (r.title.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                if (r.blocked) BlockedMark()
            }
            if (picks.isNotEmpty()) FlowRow(
                Modifier.padding(start = 28.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                picks.forEach { p -> CompactValue(p, q.value(p, r), fmt, err = r.fxErr[p.id]) }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    }
}

/** ED-12: Eintrag "Als Tabelle" im Ansichten-Menue (Schalter, lokal je Ansicht) */
@Composable
internal fun AsTableMenuItem(on: Boolean, onChange: (Boolean) -> Unit) {
    androidx.compose.material3.DropdownMenuItem(
        text = { Text(stringResource(R.string.dbv_as_table)) },
        leadingIcon = { SagaIcon(SagaIcons.table, null, Modifier.size(18.dp)) },
        trailingIcon = { androidx.compose.material3.Switch(checked = on, onCheckedChange = null, modifier = Modifier.clearAndSetSemantics { }) },
        onClick = { onChange(!on) },
        modifier = Modifier.semantics {
            role = androidx.compose.ui.semantics.Role.Switch
            toggleableState = androidx.compose.ui.state.ToggleableState(on)
        },
    )
}

/** ED-12 (eingebettet): Umschalter unter der Datenbank - "Als Tabelle" bzw. zurueck "Als Liste" */
@Composable
internal fun AsTableToggleButton(on: Boolean, onChange: (Boolean) -> Unit) {
    io.github.yggdrasil_sh.saga.ui.theme.SagaTextButton(onClick = { onChange(!on) }) {
        SagaIcon(if (on) SagaIcons.list else SagaIcons.table, null, Modifier.padding(end = 6.dp).size(18.dp))
        Text(stringResource(if (on) R.string.dbv_as_list else R.string.dbv_as_table))
    }
}

/**
 * ED-14: waagrechtes Wischen der Tabelle rastet an Spaltenkanten ein (ganze Spalten statt Bruchstuecken wie "2026" am
 * Rand der fixierten Spalte). Schwung laeuft natuerlich aus, danach geht es zur naechsten Kante in Wischrichtung.
 */
@Composable
internal fun rememberColumnSnap(state: androidx.compose.foundation.ScrollState, widths: List<androidx.compose.ui.unit.Dp>): androidx.compose.foundation.gestures.FlingBehavior {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val decay = androidx.compose.animation.rememberSplineBasedDecay<Float>()
    return remember(state, widths, density, decay) {
        val px = with(density) { widths.map { it.toPx() } }
        val provider = object : androidx.compose.foundation.gestures.snapping.SnapLayoutInfoProvider {
            override fun calculateApproachOffset(velocity: Float, decayOffset: Float): Float = decayOffset
            override fun calculateSnapOffset(velocity: Float): Float {
                val edges = DbCompact.columnEdges(px, state.maxValue.toFloat())
                val dir = if (kotlin.math.abs(velocity) < 400f) 0f else velocity
                return DbCompact.snapOffset(edges, state.value.toFloat(), dir)
            }
        }
        androidx.compose.foundation.gestures.snapping.snapFlingBehavior(
            provider, decay, androidx.compose.animation.core.spring(stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow),
        )
    }
}

/** ED-14: ab Scroll > 0 an der fixierten Titelspalte eine 1-dp-Linie (outlineVariant) und ein leichter Schatten */
@Composable
internal fun Modifier.frozenEdge(state: androidx.compose.foundation.ScrollState): Modifier {
    val line = MaterialTheme.colorScheme.outlineVariant
    val shade = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    return this.then(Modifier.drawWithContent {
        drawContent()
        if (state.value > 0) {
            val w = 4.dp.toPx()
            drawRect(
                androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(shade, shade.copy(alpha = 0f)), startX = 0f, endX = w),
                size = androidx.compose.ui.geometry.Size(w, size.height),
            )
            drawLine(line, androidx.compose.ui.geometry.Offset(0.5f, 0f), androidx.compose.ui.geometry.Offset(0.5f, size.height), strokeWidth = 1.dp.toPx())
        }
    })
}
