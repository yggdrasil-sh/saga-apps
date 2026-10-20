package io.github.yggdrasil_sh.saga.call

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.util.resolve
import kotlinx.coroutines.delay

private val BAR_GREEN = Color(0xFF1E8E3E)

/**
 * Leiste "Anruf laeuft · m:ss – tippen zum Zurueckkehren" oben in der App (alle Bildschirme der Haupt-Activity; der
 * Anrufbildschirm ist eine eigene Activity und zeigt sie daher nie). Liegt unter der Statusleiste (statusBarsPadding);
 * der Inhalt darunter muss die Statusleisten-Abstaende als verbraucht markieren.
 */
@Composable
fun OngoingCallBar(st: CallUiState?, modifier: Modifier = Modifier) {
    if (!CallReturn.active(st)) return
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(st?.connectedAt) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val text = CallReturn.barText(st, now)?.resolve() ?: return
    val hint = stringResource(R.string.call_bar_tap)
    Surface(color = BAR_GREEN, contentColor = Color.White, modifier = modifier.fillMaxWidth()) {
        Row(
            Modifier.statusBarsPadding()
                .clickable(onClickLabel = stringResource(R.string.call_return), role = Role.Button) { CallActivity.open(context) }
                .heightIn(min = 40.dp).padding(horizontal = 16.dp, vertical = 8.dp)
                .semantics(mergeDescendants = true) { contentDescription = "$text. $hint" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(if (st!!.video) R.drawable.ic_videocam else R.drawable.ic_call), null, Modifier.size(18.dp))
            Text(
                "$text – $hint", style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

/** Hinweis im Chat, in dem der Anruf laeuft: "Zum Anruf zurueckkehren" */
@Composable
fun ReturnToCallRow(st: CallUiState?, chatId: Long, modifier: Modifier = Modifier) {
    if (!CallReturn.inChat(st, chatId)) return
    val context = LocalContext.current
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.clickable(role = Role.Button) { CallActivity.open(context) }
                .heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(if (st!!.video) R.drawable.ic_videocam else R.drawable.ic_call), null, Modifier.size(20.dp))
            Text(
                stringResource(R.string.call_return), style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}

/**
 * 2.4 E4/E5: Leiste auf anderen eigenen Geraeten - "Anruf laeuft auf <Geraet>" mit Hinzufuegen (Begleitgeraet, ohne
 * Mikrofon und Ton) und Hierher uebernehmen; Gruppenanruf ohne eigenes Geraet: "Gruppenanruf laeuft · n Geraete" mit
 * Beitreten. Geht es nicht (joinable false): Hinweis ("braucht Saga 2.4", "Der Anruf ist voll").
 */
@Composable
fun RunningCallBar(calls: CallManager, own: CallUiState?, modifier: Modifier = Modifier, chatId: Long? = null, statusBar: Boolean = false) {
    val bar by calls.run.collectAsState()
    val b = bar ?: return
    if (CallReturn.active(own)) return                    // telefoniere hier schon
    if (chatId != null && b.chatId != chatId) return
    val context = LocalContext.current
    val title = if (b.mineRunning) stringResource(R.string.room_running_on, b.onDevice)
    else stringResource(R.string.room_group_running, b.devices)
    var pendingRole by remember { mutableStateOf<String?>(null) }
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        val role = pendingRole ?: return@rememberLauncherForActivityResult
        pendingRole = null
        if (CallPermissions.missing(context, false).contains(android.Manifest.permission.RECORD_AUDIO)) {
            calls.notifyMicDenied()
        } else calls.join(b.callId, b.chatId, role, b.media == "video" && !CallPermissions.missing(context, true).contains(android.Manifest.permission.CAMERA))
    }
    fun go(role: String) {
        // Begleitgeraet: nicht nach dem Mikrofon fragen, solange es aus ist
        if (role == CallRoom.ROLE_COMPANION) {
            calls.join(b.callId, b.chatId, role, false)
            return
        }
        val need = CallPermissions.missing(context, b.media == "video")
        if (need.isEmpty()) calls.join(b.callId, b.chatId, role, b.media == "video") else {
            pendingRole = role
            perms.launch(need.toTypedArray())
        }
    }
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer, modifier = modifier.fillMaxWidth()) {
        Column((if (statusBar) Modifier.statusBarsPadding() else Modifier).padding(horizontal = 16.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) {}) {
                Icon(painterResource(if (b.mineRunning) R.drawable.ic_devices else R.drawable.ic_group), null, Modifier.size(18.dp))
                Text(
                    title, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 8.dp).weight(1f),
                )
            }
            if (!b.joinable) {
                Text(CallRoom.blockText(b.block, b.limit).resolve(), style = MaterialTheme.typography.labelMedium)
            } else Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                if (b.mineRunning) {
                    TextButton(onClick = { go(CallRoom.ROLE_COMPANION) }) { Text(stringResource(R.string.room_add_device)) }
                    TextButton(onClick = { go(CallRoom.ROLE_HANDOVER) }) { Text(stringResource(R.string.room_take_over)) }
                } else TextButton(onClick = { go(CallRoom.ROLE_MEMBER) }) { Text(stringResource(R.string.room_join)) }
            }
        }
    }
}
