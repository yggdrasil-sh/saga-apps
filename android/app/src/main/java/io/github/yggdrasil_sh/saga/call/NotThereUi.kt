package io.github.yggdrasil_sh.saga.call

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.ui.notifications.Avatar
import io.github.yggdrasil_sh.saga.ui.theme.SagaColors
import io.github.yggdrasil_sh.saga.ui.theme.SagaOutlinedButton
import io.github.yggdrasil_sh.saga.ui.theme.SagaTextButton
import io.github.yggdrasil_sh.saga.util.resolve
import kotlinx.coroutines.delay

// 2.5 (L7) "Erneut klingeln": Abschnitt "Nicht dabei" unter der Teilnehmerliste eines Gruppenanrufs (Mesh und Knoten) -
// Mitglieder ohne Geraet im Anruf mit Zustand und Knopf. Regeln: Invites (InviteLogic.kt).

/** Zustandstext */
@Composable
internal fun inviteLabel(st: Invites.St): String = stringResource(
    when (st) {
        Invites.St.RINGING -> R.string.inv_ringing
        Invites.St.DECLINED -> R.string.inv_declined
        Invites.St.MISSED -> R.string.inv_missed
        Invites.St.LEFT -> R.string.inv_left
        Invites.St.NONE -> R.string.inv_none
        Invites.St.REMOVED -> R.string.inv_removed
        Invites.St.OLD -> R.string.inv_old_hint
    },
)

/** Abschnitt "Nicht dabei" als Teil einer LazyColumn (nach den Teilnehmenden) */
internal fun LazyListScope.notThere(inv: Invites.Snapshot, onRing: (List<String>) -> Unit) {
    item(key = "inv-head") { NotThereHead(inv, onRing) }
}

/**
 * Kopf + Zeilen in einem Element: Fokus und Uhr (Ablauf "klingelt …" nach since + ring_s, auch ohne Ereignis) gemeinsam.
 * Nach einem Klick verschwindet der Knopf (vorlaeufig "klingelt …") - der Fokus geht auf die Ueberschrift.
 */
@Composable
private fun NotThereHead(inv: Invites.Snapshot, onRing: (List<String>) -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(inv, now) {
        val next = Invites.nextChangeMs(inv, System.currentTimeMillis()) ?: return@LaunchedEffect
        delay(next + 300)
        now = System.currentTimeMillis()
    }
    val rows = remember(inv, now) { Invites.rows(inv, maxOf(now, System.currentTimeMillis())) }
    val head = remember { FocusRequester() }
    val ring: (List<String>) -> Unit = { w ->
        onRing(w)
        try { head.requestFocus() } catch (_: IllegalStateException) {}
    }
    val all = Invites.ringAll(rows)
    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        // grosse Schrift bzw. schmale Breite: Knoepfe unter den Text statt daneben (Namen nicht abschneiden)
        val stacked = maxWidth < 260.dp * LocalDensity.current.fontScale
        Column(Modifier.fillMaxWidth()) {
            val allBtn: @Composable () -> Unit = {
                if (all.isNotEmpty()) {
                    val cd = stringResource(R.string.inv_ring_all_cd, all.size)
                    SagaTextButton(onClick = { ring(all) }, modifier = Modifier.semantics { contentDescription = cd }) {
                        Text(stringResource(R.string.inv_ring_all), maxLines = 2)
                    }
                }
            }
            val title: @Composable (Modifier) -> Unit = { m ->
                Text(
                    stringResource(R.string.inv_head, Invites.count(inv)), style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = m.padding(vertical = 6.dp).focusRequester(head).focusable().semantics { heading() },
                )
            }
            if (stacked) {
                title(Modifier.fillMaxWidth())
                allBtn()
            } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                title(Modifier.weight(1f))
                allBtn()
            }
            for (r in rows) InviteRow(r, stacked, ring)
            if (inv.more > 0) Text(
                stringResource(R.string.inv_more, inv.more), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp),
            )
            // Ergebnis bzw. Fehler des Klingelns (vorgelesen)
            val note = inv.note?.resolve()
            Box(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) {
                if (note != null) Text(
                    note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun InviteRow(r: Invites.Row, stacked: Boolean, onRing: (List<String>) -> Unit) {
    val name = r.x.name.ifEmpty { "?" }
    val text: @Composable (Modifier) -> Unit = { m ->
        Column(m) {
            Text(name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = if (stacked) 2 else 1,
                overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (r.st == Invites.St.RINGING) Box(
                    Modifier.padding(end = 6.dp).size(8.dp).clip(CircleShape).background(SagaColors.current.ok),
                )
                Text(inviteLabel(r.st), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (stacked) Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.clearAndSetSemantics {}) { Avatar(name, size = 36, avatar = r.x.avatar) }
        Column(Modifier.weight(1f)) {
            text(Modifier.fillMaxWidth())
            InviteButton(r, name, onRing)
        }
    } else Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.clearAndSetSemantics {}) { Avatar(name, size = 36, avatar = r.x.avatar) }
        text(Modifier.weight(1f))
        InviteButton(r, name, onRing)
    }
    HorizontalDivider()
}

@Composable
private fun InviteButton(r: Invites.Row, name: String, onRing: (List<String>) -> Unit) {
    when (r.btn) {
        Invites.Btn.NONE -> {}
        Invites.Btn.OLD -> {
            val cd = stringResource(R.string.inv_old_cd, name)
            SagaOutlinedButton(onClick = {}, enabled = false, modifier = Modifier.semantics { contentDescription = cd }) {
                Text(stringResource(R.string.inv_ring_again), maxLines = 2)
            }
        }
        else -> {
            val again = r.btn == Invites.Btn.RING_AGAIN
            val cd = stringResource(if (again) R.string.inv_ring_again_cd else R.string.inv_ring_cd, name)
            SagaOutlinedButton(onClick = { onRing(listOf(r.x.who)) }, modifier = Modifier.semantics { contentDescription = cd }) {
                Text(stringResource(if (again) R.string.inv_ring_again else R.string.inv_ring), maxLines = 2)
            }
        }
    }
}
