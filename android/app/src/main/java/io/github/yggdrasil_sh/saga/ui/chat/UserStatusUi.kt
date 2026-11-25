package io.github.yggdrasil_sh.saga.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.chat.PeerStatus
import io.github.yggdrasil_sh.saga.chat.StatusTexts
import io.github.yggdrasil_sh.saga.chat.UserStatusLogic
import io.github.yggdrasil_sh.saga.chat.UserStatusView
import io.github.yggdrasil_sh.saga.data.SagaRepository
import io.github.yggdrasil_sh.saga.ui.common.appLocale
import io.github.yggdrasil_sh.saga.ui.theme.SagaButton
import io.github.yggdrasil_sh.saga.ui.theme.SagaColors
import io.github.yggdrasil_sh.saga.ui.theme.SagaTextButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.ZonedDateTime

// 2.6 (Bugs-DB 2029-7): eigener Status - Punkt, Abzeichen, Chip im Chat-Kopf, Blatt "Mein Status", Rueckfrage vor dem Anruf.
// Farben wie im Web (static/style.css .us-dot): Verfuegbar gruen, Beschaeftigt gelb, Nicht stoeren rot mit weissem Balken,
// Im Anruf Ring in der Akzentfarbe. Farbe nie allein: Name/Text steht immer daneben bzw. in der Beschreibung.

/** Punkt fuer einen Zustand (available | busy | dnd | in_call) - ohne eigene Beschreibung (steht im Text daneben) */
@Composable
fun StatusDot(state: String, size: Dp = 12.dp, modifier: Modifier = Modifier) {
    val c = SagaColors.current
    val accent = MaterialTheme.colorScheme.primary
    val surface = MaterialTheme.colorScheme.surface
    when (state) {
        "in_call" -> Box(modifier.size(size).background(surface, CircleShape).border(2.dp, accent, CircleShape).padding(size / 4).background(accent, CircleShape))
        "dnd" -> Box(
            modifier.size(size).background(c.down, CircleShape).drawBehind {
                val w = this.size.width
                drawLine(Color.White, Offset(w * 0.22f, w / 2), Offset(w * 0.78f, w / 2), strokeWidth = maxOf(2f, w / 7))
            },
        )
        "busy" -> Box(modifier.size(size).background(c.warn, CircleShape))
        else -> Box(modifier.size(size).background(c.up, CircleShape))
    }
}

/** Abzeichen unten rechts am Profilbild (Chatliste): Status vor Online-Punkt; Status steht zusaetzlich im Text */
@Composable
fun WithStatusBadge(status: PeerStatus?, online: Boolean, content: @Composable () -> Unit) {
    val st = UserStatusLogic.visible(status)
    if (st == null) {
        WithOnlineDot(online, content)
        return
    }
    Box {
        content()
        StatusDot(st.s, 16.dp, Modifier.align(Alignment.BottomEnd).border(2.dp, MaterialTheme.colorScheme.surface, CircleShape).padding(2.dp))
    }
}

/** Text des Partner-Status in der App-Sprache */
@Composable
fun peerStatusText(p: PeerStatus?, withText: Boolean): String {
    val ctx = LocalContext.current
    val loc = appLocale()
    return remember(p, withText, loc) { StatusTexts.peerText(ctx.resources, loc, p, withText) }
}

/** Status-Chip unter dem Kopf eines Direkt-Chats (Punkt + ganzer Text, hoechstens zwei Zeilen; liest sich als "Status: …") */
@Composable
fun PeerStatusChip(p: PeerStatus, modifier: Modifier = Modifier) {
    val full = peerStatusText(p, withText = true)
    val desc = stringResource(R.string.us_status_desc, full)
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (p.s == "dnd") MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f) else MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.semantics(mergeDescendants = true) { contentDescription = desc },
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusDot(p.s, 10.dp)
            Text(
                full, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
}

/** Zusatz zum eigenen Stand (Ende, Ruhezeit, im Anruf, vom Geraet) */
@Composable
fun ownStatusHint(repo: SagaRepository, v: UserStatusView): String {
    val ctx = LocalContext.current
    val loc = appLocale()
    val q = repo.push.info.collectAsState().value?.settings?.quiet
    val quietTo = q?.takeIf { it.on }?.to
    return remember(v, loc, quietTo) { StatusTexts.ownHint(ctx.resources, loc, v, quietTo) }
}

/** Rueckfrage vor dem Anruf: "{name} möchte gerade nicht gestört werden. Trotzdem anrufen?" */
@Composable
fun CallAnywayDialog(name: String, onCall: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { StatusDot("dnd", 24.dp) },
        title = { Text(stringResource(R.string.us_dnd)) },
        text = { Text(stringResource(R.string.us_ask_text, name)) },
        confirmButton = { SagaTextButton(onClick = { onDismiss(); onCall() }) { Text(stringResource(R.string.us_ask_ok)) } },
        dismissButton = { SagaTextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/**
 * Blatt "Mein Status": Zustand (Verfuegbar / Beschaeftigt / Nicht stoeren), Dauer (1 Stunde, bis 7:00, bis ich es
 * ausschalte; mit bestehendem Ende "wie bisher"), eigener Text (hoechstens limits.text Zeichen). 409 (anderswo geaendert):
 * Stand uebernehmen und still neu zeigen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserStatusSheet(repo: SagaRepository, onDismiss: () -> Unit, onReach: (() -> Unit)?) {
    val center = repo.chat.status
    val v0 by center.view.collectAsState()
    val v = v0 ?: run {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    LaunchedEffect(Unit) {
        try {
            center.load()
            if (center.view.value?.quiet == true) repo.push.load()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    // gewaehlte Werte; rev zaehlt bei 409 hoch -> neu aus dem uebernommenen Stand fuellen
    var rev by remember { mutableStateOf(0) }
    val nowSec = center.now()
    var state by rememberSaveable(rev) { mutableStateOf(v.state) }
    var dur by rememberSaveable(rev) { mutableStateOf(UserStatusLogic.defaultDur(v, nowSec)) }
    var text by rememberSaveable(rev) { mutableStateOf(UserStatusLogic.ownText(v)) }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    val ctx = LocalContext.current
    val loc = appLocale()
    val max = v.limits.text
    fun save() {
        if (busy) return
        busy = true
        err = null
        scope.launch {
            try {
                val r = center.set(state, dur, text)
                if (r.conflict) rev++ else onDismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                err = repo.friendly(e).asString(ctx)
            } finally {
                busy = false
            }
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.us_my_status), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            val hint = ownStatusHint(repo, v)
            val nowName = stringResource(StatusTexts.stateRes(UserStatusLogic.shown(v).state))
            Text(
                stringResource(R.string.us_now, nowName) + if (hint.isNotEmpty()) " · $hint" else "",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            SheetSection(stringResource(R.string.us_state))
            Column(Modifier.selectableGroup()) {
                UserStatusLogic.STATES.forEach { s ->
                    RadioLine(stringResource(StatusTexts.stateRes(s)), state == s, enabled = !busy, lead = { StatusDot(s, 14.dp) }) { state = s }
                }
            }
            SheetSection(stringResource(R.string.us_duration))
            val avail = state == "available"
            val t7 = UserStatusLogic.tomorrow7(ZonedDateTime.now(ZoneId.systemDefault()))
            val opts = buildList {
                if (UserStatusLogic.keepOffered(v, nowSec)) {
                    add(UserStatusLogic.Dur.KEEP to stringResource(R.string.us_dur_keep, StatusTexts.whenText(ctx.resources, loc, v.until!!)))
                }
                add(UserStatusLogic.Dur.H1 to stringResource(R.string.us_dur_1h))
                add(UserStatusLogic.Dur.T7 to stringResource(if (t7.second) R.string.us_dur_t7_today else R.string.us_dur_t7))
                add(UserStatusLogic.Dur.OFF to stringResource(R.string.us_dur_off))
            }
            Column(Modifier.selectableGroup()) {
                opts.forEach { (d, label) -> RadioLine(label, dur == d, enabled = !avail && !busy) { dur = d } }
            }
            val n = UserStatusLogic.count(text)
            val countText = stringResource(R.string.us_text_count, n, max)
            OutlinedTextField(
                value = text, onValueChange = { text = UserStatusLogic.clip(it, max) }, singleLine = true, enabled = !busy,
                label = { Text(stringResource(R.string.us_text_label)) },
                placeholder = { Text(stringResource(R.string.us_text_hint)) },
                supportingText = { Text(countText, color = if (n >= max) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { save() }),
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
            err?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (onReach != null) SagaTextButton(onClick = { onDismiss(); onReach() }) { Text(stringResource(R.string.us_reach_more)) }
                Spacer(Modifier.weight(1f))
                SagaTextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                Spacer(Modifier.width(8.dp))
                SagaButton(onClick = { save() }, enabled = !busy) { Text(stringResource(R.string.us_save)) }
            }
        }
    }
}

@Composable
private fun SheetSection(title: String) {
    Text(
        title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp).semantics { heading() },
    )
}

/** Auswahlzeile mit 48-dp-Trefferflaeche */
@Composable
private fun RadioLine(label: String, selected: Boolean, enabled: Boolean = true, lead: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(Modifier.width(12.dp))
        if (lead != null) {
            Box(Modifier.clearAndSetSemantics { }) { lead() }
            Spacer(Modifier.width(10.dp))
        }
        Text(
            label, style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        )
    }
}

/** Kurze Beschreibung des eigenen Status fuer Bedienungshilfen ("Status: Nicht stören, bis 14:30") */
@Composable
fun ownStatusDesc(repo: SagaRepository, v: UserStatusView): String {
    val name = stringResource(StatusTexts.stateRes(UserStatusLogic.shown(v).state))
    val hint = ownStatusHint(repo, v)
    return stringResource(R.string.us_status_desc, name) + if (hint.isNotEmpty()) ", $hint" else ""
}
