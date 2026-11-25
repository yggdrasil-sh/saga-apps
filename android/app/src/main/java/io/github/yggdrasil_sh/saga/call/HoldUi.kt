package io.github.yggdrasil_sh.saga.call

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.ui.theme.SagaButton
import io.github.yggdrasil_sh.saga.ui.theme.SagaIcon
import io.github.yggdrasil_sh.saga.ui.theme.SagaIcons
import io.github.yggdrasil_sh.saga.ui.theme.SagaTextButton

/**
 * 2.6 (Bugs-DB 2029-7): Leiste fuer einen Anruf, der nicht klingelt (wie static/calls.js holdBar).
 * KNOCK: "{name} klopft an" (Anklopfen, waiting) - Ablehnen (besetzt) bzw. im Gespraech hier "Beenden und annehmen".
 * QUIET: "Anruf von {name} – stumm (Nicht stören|Ruhezeit)" (call.quiet) - Ausblenden, "Annehmen" nur ohne eigenes
 * Gespraech auf einem anderen Geraet, im Gespraech hier "Beenden und annehmen".
 */
data class HoldBar(
    val callId: String,
    val chatId: Long,
    val mode: Mode,
    val peer: CallPeer,
    val video: Boolean,
    /** dnd | quiet (Grund der stillen Leiste) */
    val why: String,
    /** das eigene Gespraech laeuft auf diesem Geraet */
    val here: Boolean,
    /** ein eigenes Gespraech laeuft auf einem anderen Geraet */
    val elsewhere: Boolean,
    val info: CallInfo,
    /** Klingelzeit insgesamt (s) ab [at] */
    val totalS: Double,
    val at: Long,
    /** "Beenden und annehmen" laeuft */
    val taking: Boolean = false,
    /** 2.6 (Bugs-DB 2387): Gespraech hier zu Ende, es wird gerade geprueft, ob woanders eines laeuft - noch kein "Annehmen" */
    val checking: Boolean = false,
) {
    enum class Mode { KNOCK, QUIET }

    /** Knoepfe (rein, Tests): Ablehnen | Ausblenden, dazu Beenden-und-annehmen bzw. Annehmen */
    val canTake: Boolean get() = here
    val canAccept: Boolean get() = !here && !elsewhere && !checking && mode == Mode.QUIET
    val canDecline: Boolean get() = mode == Mode.KNOCK
}

/** Leiste ueber der App bzw. oben im Anrufbildschirm (inCallScreen: nur, wenn das Gespraech hier laeuft) */
@Composable
fun HoldCallBar(calls: CallManager, modifier: Modifier = Modifier, statusBar: Boolean = false, inCallScreen: Boolean = false) {
    val h0 by calls.hold.collectAsState()
    val h = h0 ?: return
    if (inCallScreen && !h.here) return
    val context = LocalContext.current
    val name = h.peer.shown
    var pendingVideo by remember { mutableStateOf<Boolean?>(null) }
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        val v = pendingVideo ?: return@rememberLauncherForActivityResult
        pendingVideo = null
        if (CallPermissions.missing(context, false).contains(android.Manifest.permission.RECORD_AUDIO)) calls.notifyMicDenied()
        else calls.holdAccept(v && !CallPermissions.missing(context, true).contains(android.Manifest.permission.CAMERA))
    }
    fun accept() {
        val need = CallPermissions.missing(context, h.video)
        if (need.isEmpty()) calls.holdAccept(h.video) else {
            pendingVideo = h.video
            perms.launch(need.toTypedArray())
        }
    }
    val knock = h.mode == HoldBar.Mode.KNOCK
    val title = if (knock) stringResource(R.string.us_knock, name)
    else stringResource(if (h.why == "quiet") R.string.us_quiet_title_quiet else R.string.us_quiet_title_dnd, name)
    val sub = when {
        knock && h.here -> stringResource(if (h.video) R.string.us_knock_sub_video else R.string.us_knock_sub_audio)
        knock -> stringResource(R.string.us_elsewhere)
        else -> stringResource(if (h.video) R.string.us_word_video else R.string.us_word_call) + " · " +
            stringResource(if (h.why == "quiet") R.string.us_quiet_sub_quiet else R.string.us_quiet_sub_dnd)
    }
    val extra = if (!knock && !h.here && h.elsewhere) stringResource(R.string.us_elsewhere) else null
    Surface(
        color = if (knock) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = if (knock) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurface,
        shape = if (statusBar) RoundedCornerShape(0.dp) else RoundedCornerShape(16.dp),
        shadowElevation = if (statusBar) 0.dp else 6.dp,
        modifier = modifier.fillMaxWidth().semantics { paneTitle = title },
    ) {
        Column((if (statusBar) Modifier.statusBarsPadding() else Modifier).padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SagaIcon(if (h.video) SagaIcons.video else SagaIcons.phone, null)
                Column(
                    Modifier.weight(1f).padding(start = 12.dp).semantics(mergeDescendants = true) {
                        liveRegion = LiveRegionMode.Polite
                        contentDescription = listOfNotNull(title, sub, extra).joinToString(". ")
                    },
                ) {
                    Text(if (knock) title else name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(sub, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    extra?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                }
                if (!knock) IconButton(onClick = { calls.holdHide() }) { SagaIcon(SagaIcons.x, stringResource(R.string.us_hide)) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                if (h.canDecline) SagaTextButton(onClick = { calls.holdDecline() }, enabled = !h.taking) { Text(stringResource(R.string.us_decline)) }
                if (h.canTake) {
                    val d = stringResource(R.string.us_take_desc, name)
                    SagaButton(onClick = { calls.holdTake(h.video && !CallPermissions.missing(context, true).contains(android.Manifest.permission.CAMERA)) },
                        enabled = !h.taking, modifier = Modifier.semantics { contentDescription = d }) { Text(stringResource(R.string.us_take)) }
                } else if (h.canAccept) {
                    val d = stringResource(R.string.us_accept_desc, name)
                    SagaButton(onClick = { accept() }, modifier = Modifier.semantics { contentDescription = d }) { Text(stringResource(R.string.us_accept)) }
                }
            }
        }
    }
}
