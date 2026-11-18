package io.github.yggdrasil_sh.saga.ui.page

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.data.SagaRepository
import io.github.yggdrasil_sh.saga.data.Siegel
import io.github.yggdrasil_sh.saga.ui.theme.SagaButton
import io.github.yggdrasil_sh.saga.ui.theme.SagaIcon
import io.github.yggdrasil_sh.saga.ui.theme.SagaIcons

// 2.5 (Bugs-DB 2156): Bereich "Saga-Siegel" im Symbol-Dialog - Farbwahl (Radiogruppe, zuletzt gewaehlte Farbe gemerkt),
// Raster der Motive (Namen aus GET api/siegel in der App-Sprache), Setzen per PATCH icon "/siegel/<farbe>/<motiv>.svg".
// Wie das Web (pages.js siegelSection); nur, wenn der Server `siegel1` meldet (Siegel.offered). Logik: data/Siegel.kt.

/** Farbpunkt neben dem Farbnamen (Grundton der Siegel; unbekannte Farbe eines neueren Servers: neutral) */
private fun dotColor(id: String): Color = when (id) {
    "weinrot" -> Color(0xFF7A1F2E)
    "smaragd" -> Color(0xFF1E6B4E)
    "elfenbein" -> Color(0xFFEDE3C8)
    else -> Color(0xFF9E9E9E)
}

/**
 * Auswahl der Siegel. [current]: jetziges Symbol (dessen Farbe ist vorgewaehlt, das Siegel markiert). [preview]: nur fuer
 * Bilder (Debug) - Liste fest, nichts laden.
 */
@Composable
internal fun SiegelPicker(
    repo: SagaRepository, current: String, onPick: (String) -> Unit, modifier: Modifier = Modifier, preview: Siegel.Listing? = null,
) {
    var listing by remember { mutableStateOf(preview ?: repo.siegelCached()) }
    var failed by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) {
        if (preview != null) return@LaunchedEffect
        failed = false
        val l = repo.siegelList()
        if (l != null) listing = l else failed = listing == null
    }
    val l = listing
    Column(modifier) {
        when {
            l == null && failed -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.siegel_failed), color = MaterialTheme.colorScheme.error)
                SagaButton(onClick = { reload++ }) { Text(stringResource(R.string.action_retry)) }
            }
            l == null -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else -> SiegelGrid(repo, l, current, onPick)
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SiegelGrid(repo: SagaRepository, l: Siegel.Listing, current: String, onPick: (String) -> Unit) {
    var color by remember(l) { mutableStateOf(Siegel.preselect(l, current, repo.siegel.lastColor)) }
    val cs = MaterialTheme.colorScheme
    val colorsLabel = stringResource(R.string.siegel_colors)
    // Farbwahl: Radiogruppe (TalkBack: "Farbe der Siegel", je Farbe ausgewaehlt/nicht ausgewaehlt); bricht um statt
    // die Namen abzuschneiden (schmaler Dialog, grosse Schrift)
    androidx.compose.foundation.layout.FlowRow(
        Modifier.fillMaxWidth().padding(bottom = 6.dp).selectableGroup().semantics { contentDescription = colorsLabel },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (c in l.colors) {
            val on = c.id == color
            val shape = RoundedCornerShape(999.dp)
            Row(
                Modifier.heightIn(min = 48.dp)
                    .selectable(selected = on, role = Role.RadioButton) { color = c.id; repo.siegel.lastColor = c.id },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier.background(if (on) cs.secondaryContainer else Color.Transparent, shape)
                        .border(1.dp, if (on) cs.secondary else cs.outlineVariant, shape)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (on) SagaIcon(SagaIcons.check, null, Modifier.size(14.dp), tint = cs.onSecondaryContainer)
                    else Box(Modifier.size(12.dp).background(dotColor(c.id), CircleShape).border(1.dp, cs.outline, CircleShape))
                    Text(
                        c.label, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        color = if (on) cs.onSecondaryContainer else cs.onSurface,
                    )
                }
            }
        }
    }
    val colorLabel = l.colorLabel(color)
    val big = LocalDensity.current.fontScale >= 1.5f
    val px = with(LocalDensity.current) { 44.dp.roundToPx() }
    LazyVerticalGrid(
        GridCells.Adaptive(if (big) 64.dp else 56.dp), Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(l.motifs, key = { it.id }) { m ->
            val path = Siegel.path(color, m.id)
            val on = current.trim() == path
            val name = stringResource(R.string.siegel_name, "${m.label} ($colorLabel)")
            val shape = RoundedCornerShape(12.dp)
            Box(
                Modifier.aspectRatio(1f).heightIn(min = 48.dp)
                    .background(if (on) cs.secondaryContainer else Color.Transparent, shape)
                    .border(if (on) 2.dp else 0.dp, if (on) cs.secondary else Color.Transparent, shape)
                    .clickable(role = Role.Button) { repo.siegel.lastColor = color; onPick(path) }
                    // TalkBack: "Siegel Rabe (Weinrot)", das jetzige Symbol als ausgewaehlt (wie aria-pressed im Web)
                    .semantics(mergeDescendants = true) { contentDescription = name; selected = on },
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(
                    model = repo.absoluteUrl(Siegel.imageUrl(path, px) ?: path), contentDescription = null,
                    contentScale = ContentScale.Fit, modifier = Modifier.size(44.dp),
                )
            }
        }
    }
}
