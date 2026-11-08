package io.github.yggdrasil_sh.saga.ui.page

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.ui.theme.SagaIcon
import io.github.yggdrasil_sh.saga.ui.theme.SagaIcons
import io.github.yggdrasil_sh.saga.ui.theme.SagaSpace

/**
 * Design-Runde 6 (A11Y-24/ED-27): Kopf-Steuerung einer Seite, die als Seitenblatt neben einer Datenbank-Ansicht steht
 * (statt der App-Leiste mit Zurueck). [onPrev]/[onNext] null = am Rand bzw. Zeile nicht in der Ansicht (gesperrt).
 */
class RowSheetChrome(
    val onClose: () -> Unit,
    val onPrev: (() -> Unit)?,
    val onNext: (() -> Unit)?,
    val onOpenFull: () -> Unit,
)

/** Kopf des Seitenblatts: Schliessen, Vorige/Naechste Zeile, Titel, Aktionen der Seite, "In voller Groesse oeffnen" */
@Composable
internal fun RowSheetBar(title: String, titleVisible: Boolean, sheet: RowSheetChrome, actions: @Composable RowScope.() -> Unit) {
    val alpha by androidx.compose.animation.core.animateFloatAsState(
        if (titleVisible) 1f else 0f, io.github.yggdrasil_sh.saga.ui.theme.SagaMotion.current.defaultEffects(), label = "sheet-title",
    )
    Surface(color = MaterialTheme.colorScheme.surface) {
        androidx.compose.foundation.layout.Column {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = SagaSpace.xs), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = sheet.onClose) { SagaIcon(SagaIcons.x, stringResource(R.string.r6_sheet_close)) }
                IconButton(onClick = { sheet.onPrev?.invoke() }, enabled = sheet.onPrev != null) {
                    SagaIcon(SagaIcons.chevronUp, stringResource(R.string.r6_sheet_prev))
                }
                IconButton(onClick = { sheet.onNext?.invoke() }, enabled = sheet.onNext != null) {
                    SagaIcon(SagaIcons.chevronDown, stringResource(R.string.r6_sheet_next))
                }
                Text(
                    title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(horizontal = SagaSpace.xs).graphicsLayer { this.alpha = alpha }.semantics { heading() },
                )
                actions()
                IconButton(onClick = sheet.onOpenFull) { SagaIcon(SagaIcons.maximize, stringResource(R.string.r6_sheet_open_full)) }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

/**
 * Blatt rechts: Trennlinie + Seite der Zeile (dieselbe [PageScreen] wie im Vollbild, also dieselben Eigenschaften,
 * derselbe Editor und dieselben Repo-Wege). Zurueck schliesst zuerst das Blatt (BackHandler wird beim Oeffnen
 * angemeldet und liegt damit vor dem Stapel-Zurueck der App).
 */
@Composable
internal fun RowSheetPane(
    row: Long?,
    width: Dp,
    content: @Composable (Long) -> Unit,
    onBack: () -> Unit,
) {
    // beim Schliessen noch die zuletzt gezeigte Zeile ausblenden (statt leerem Blatt)
    var last by remember { mutableStateOf(row) }
    if (row != null) last = row
    val title = stringResource(R.string.r6_sheet_pane)
    AnimatedVisibility(
        visible = row != null,
        enter = expandHorizontally(expandFrom = Alignment.Start) + fadeIn(),
        exit = shrinkHorizontally(shrinkTowards = Alignment.Start) + fadeOut(),
    ) {
        BackHandler(enabled = row != null, onBack = onBack)
        Row(Modifier.fillMaxHeight().semantics { isTraversalGroup = true; paneTitle = title }) {
            VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Box(Modifier.width(width).fillMaxHeight()) {
                last?.let { r -> key(r) { content(r) } }
            }
        }
    }
}
