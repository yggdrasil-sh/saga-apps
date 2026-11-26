package io.github.yggdrasil_sh.saga.ui.today

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.data.SagaRepository
import io.github.yggdrasil_sh.saga.data.TlAsk
import io.github.yggdrasil_sh.saga.data.TlPart
import io.github.yggdrasil_sh.saga.data.TlQuiet
import io.github.yggdrasil_sh.saga.data.TlWaiting
import io.github.yggdrasil_sh.saga.data.TodayLine
import io.github.yggdrasil_sh.saga.data.TodayLineNav
import io.github.yggdrasil_sh.saga.data.TodayLineShow
import io.github.yggdrasil_sh.saga.ui.theme.LocalSagaAccent
import io.github.yggdrasil_sh.saga.ui.theme.SagaIcon
import io.github.yggdrasil_sh.saga.ui.theme.SagaIcons
import io.github.yggdrasil_sh.saga.ui.theme.SagaOutlinedButton
import io.github.yggdrasil_sh.saga.ui.theme.SagaSpace
import io.github.yggdrasil_sh.saga.ui.theme.SagaTextButton
import kotlinx.coroutines.launch

/*
 * 2.6 Tageszeile „Kurz gesagt“ im Kopf von „Heute“ unter der Zahlenzeile (Bugs-DB 2271 K-D, Regeln data/TodayLine.kt).
 * Teile als Knoepfe (>= 48 dp, oeffnen nur), daneben ✕ „Gerade nicht“ (auch Wischen) und ⋯ („Tageszeile ausblenden“,
 * „Warum sehe ich das?“). Ruhe: Pille gleicher Hoehe mit „Wieder an“; nach dem dritten ✕ einmal die Frage. „Wartet auf
 * dich“ eigene Zeile -> Saga › Aufträge. Keine KI-Marke, kein Live-Bereich, nie Fokus; Texte unveraendert vom Server.
 */

/** Mindesthoehe von Zeile, Pille und Frage (kein Springen beim ✕) */
private val lineMinHeight = 88.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TodayLineBlock(repo: SagaRepository, open: (TodayLineNav) -> Unit, modifier: Modifier = Modifier) {
    val svc = repo.todayLine
    val st by svc.state.collectAsState()
    val busy by svc.busy.collectAsState()
    val features by repo.serverFeatures.collectAsState()
    val on = TodayLine.FEATURE in features
    val aiSt by repo.ai.status.collectAsState()
    LaunchedEffect(on) { svc.load() }                     // beim Erscheinen von „Heute“ und wenn der Server das Merkmal meldet
    val show = TodayLine.show(st)
    // zuletzt gemessene Hoehe der Zeile: Pille und Frage bekommen dieselbe (kein Springen beim ✕)
    var lineH by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val rowMin = maxOf(lineMinHeight, with(density) { lineH.toDp() })
    if (show.isEmpty) return
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().padding(top = SagaSpace.s).testTag("today-line"), verticalArrangement = Arrangement.spacedBy(SagaSpace.xs)) {
        when {
            show.ask != null -> AskRow(show.ask, busy, rowMin) { yes -> scope.launch { svc.answer(yes) } }
            show.pill != null -> PillRow(show.pill, busy, rowMin) { scope.launch { svc.unquiet() } }
            show.kurz != null -> key(st?.v) {
                LineRow(
                    show.kurz, busy, open, onQuiet = { scope.launch { svc.quiet() } }, onHide = { scope.launch { svc.setOn(false) } }, onInfo = { info = true },
                    modifier = Modifier.onSizeChanged { lineH = it.height },
                )
            }
        }
        show.waiting?.let { w -> WaitingRow(w, jobs = io.github.yggdrasil_sh.saga.ai.SagaLogic.available(aiSt), open) }
    }
    if (info) {
        AlertDialog(
            onDismissRequest = { info = false },
            title = { Text(stringResource(R.string.tl_why)) },
            text = { Text(st?.info.orEmpty()) },
            confirmButton = { SagaTextButton(onClick = { info = false }) { Text(stringResource(R.string.tl_ok)) } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LineRow(
    k: TodayLineShow.Kurz, busy: Boolean, open: (TodayLineNav) -> Unit,
    onQuiet: () -> Unit, onHide: () -> Unit, onInfo: () -> Unit, modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val swipe = rememberSwipeToDismissBoxState()
    var menu by remember { mutableStateOf(false) }
    SwipeToDismissBox(
        state = swipe,
        modifier = modifier,
        enableDismissFromStartToEnd = false,
        onDismiss = {                                     // Wischen = ✕
            scope.launch { swipe.snapTo(SwipeToDismissBoxValue.Settled) }
            onQuiet()
        },
        backgroundContent = {
            Row(
                Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End,
            ) { SagaIcon(SagaIcons.moon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        },
    ) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxWidth().heightIn(min = lineMinHeight)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    // Vorlesetext der ganzen Zeile an der Ueberschrift (ohne Symbole)
                    Text(
                        k.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f).semantics { contentDescription = k.a11y },
                    )
                    IconButton(onClick = onQuiet, enabled = !busy, modifier = Modifier.size(SagaSpace.touch).testTag("today-line-x")) {
                        SagaIcon(SagaIcons.x, stringResource(R.string.tl_not_now), Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Box {
                        IconButton(onClick = { menu = true }, enabled = !busy, modifier = Modifier.size(SagaSpace.touch).testTag("today-line-more")) {
                            SagaIcon(SagaIcons.moreHoriz, stringResource(R.string.tl_options), Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.tl_hide)) }, onClick = { menu = false; onHide() },
                                leadingIcon = { SagaIcon(SagaIcons.eyeOff, null) }, modifier = Modifier.heightIn(min = SagaSpace.touch),
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.tl_why)) }, onClick = { menu = false; onInfo() },
                                leadingIcon = { SagaIcon(SagaIcons.info, null) }, modifier = Modifier.heightIn(min = SagaSpace.touch),
                            )
                        }
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(SagaSpace.s)) {
                    k.parts.forEachIndexed { i, p -> PartChip(p, i, open) }
                }
            }
        }
    }
}

@Composable
private fun PartChip(p: TlPart, i: Int, open: (TodayLineNav) -> Unit) {
    val n = TodayLine.nav(p.target)
    // Trefferflaeche >= 48 dp, sichtbare Pille darin
    Box(
        Modifier.heightIn(min = SagaSpace.touch).testTag("today-line-part-$i")
            .then(if (n != null) Modifier.clip(RoundedCornerShape(20.dp)).clickable(role = Role.Button) { open(n) } else Modifier),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            p.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun PillRow(q: TlQuiet, busy: Boolean, minH: androidx.compose.ui.unit.Dp, onUndo: () -> Unit) {
    Box(Modifier.fillMaxWidth().heightIn(min = minH), contentAlignment = Alignment.CenterStart) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(start = 16.dp, end = 4.dp).testTag("today-line-pill"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SagaIcon(SagaIcons.moon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(q.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f).padding(horizontal = SagaSpace.s))
            SagaTextButton(onClick = onUndo, enabled = !busy, modifier = Modifier.heightIn(min = SagaSpace.touch).testTag("today-line-undo")) { Text(q.undo) }
        }
    }
}

@Composable
private fun AskRow(a: TlAsk, busy: Boolean, minH: androidx.compose.ui.unit.Dp, onAnswer: (Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth().heightIn(min = minH).testTag("today-line-ask"), verticalArrangement = Arrangement.spacedBy(SagaSpace.xs)) {
        Text(a.text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        Row(horizontalArrangement = Arrangement.spacedBy(SagaSpace.s)) {
            SagaOutlinedButton(onClick = { onAnswer(true) }, enabled = !busy, modifier = Modifier.heightIn(min = SagaSpace.touch)) { Text(a.yes) }
            SagaOutlinedButton(onClick = { onAnswer(false) }, enabled = !busy, modifier = Modifier.heightIn(min = SagaSpace.touch)) { Text(a.no) }
        }
    }
}

@Composable
private fun WaitingRow(w: TlWaiting, jobs: Boolean, open: (TodayLineNav) -> Unit) {
    val n = if (jobs) TodayLine.nav(w.target ?: io.github.yggdrasil_sh.saga.data.TlTarget("jobs")) else null
    Box(
        Modifier.fillMaxWidth().heightIn(min = SagaSpace.touch).testTag("today-line-waiting")
            .then(if (n != null) Modifier.clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button) { open(n) } else Modifier),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(w.text, style = MaterialTheme.typography.bodyMedium, color = if (n != null) LocalSagaAccent.current.goldText else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
