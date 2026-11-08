package io.github.yggdrasil_sh.saga.ui.chat

import io.github.yggdrasil_sh.saga.ui.theme.SagaOutlinedButton
import io.github.yggdrasil_sh.saga.ui.theme.SagaTextButton
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.dp
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.chat.ChatDeviceInfo
import io.github.yggdrasil_sh.saga.chat.ChatSetup
import io.github.yggdrasil_sh.saga.data.SagaRepository
import io.github.yggdrasil_sh.saga.ui.common.Chrome
import io.github.yggdrasil_sh.saga.ui.common.SagaTopBar
import io.github.yggdrasil_sh.saga.ui.common.SectionHeader
import io.github.yggdrasil_sh.saga.ui.common.epochDay
import io.github.yggdrasil_sh.saga.ui.common.relativeTime
import io.github.yggdrasil_sh.saga.ui.nav.AppNavigator
import io.github.yggdrasil_sh.saga.util.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Einstellungen -> Chats & Geraete: Geraete mit Chat-Schluesseln, entfernen, neuer Wiederherstellungsschluessel, neu beginnen */
@Composable
fun ChatDevicesScreen(repo: SagaRepository, nav: AppNavigator, chrome: Chrome) {
    val svc = repo.chat
    val st by svc.state.collectAsState()
    val me by svc.me.collectAsState()
    var drop by remember { mutableStateOf<ChatDeviceInfo?>(null) }
    var newKey by remember { mutableStateOf<String?>(null) }
    var keyBusy by remember { mutableStateOf(false) }
    var keyErr by remember { mutableStateOf<UiText?>(null) }
    var restart by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { svc.refresh() }
    val limit = io.github.yggdrasil_sh.saga.chat.ChatDeviceLimit.atLimit(me?.limits)

    Scaffold(topBar = { SagaTopBar(stringResource(R.string.chat_devices), nav, chrome) }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            when {
                newKey != null -> Column(
                    Modifier.widthIn(max = 560.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    RecoveryKeyConfirm(newKey!!, keyBusy, keyErr, onCancel = { newKey = null; keyErr = null }) {
                        keyBusy = true
                        keyErr = null
                        scope.launch {
                            try {
                                svc.commitRecovery(newKey!!)
                                newKey = null
                                repo.notify(UiText.Res(R.string.chat_recovery_saved))
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                keyErr = repo.friendly(e)
                            } finally {
                                keyBusy = false
                            }
                        }
                    }
                }
                restart -> Column(
                    Modifier.widthIn(max = 560.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) { StartOver(svc) { restart = false } }
                // 2.5: neues Geraet an der Geraetegrenze - hier ein altes Geraet entfernen (geht ohne Chat-Schluessel);
                // danach (unter der Grenze) wieder die Einrichtung
                st != ChatSetup.READY && !(st == ChatSetup.NEW_DEVICE && limit != null) -> ChatSetupView(svc, st)
                else -> LazyColumn(Modifier.widthIn(max = 760.dp).fillMaxSize()) {
                    if (st != ChatSetup.READY) {
                        item { Column(Modifier.padding(horizontal = 16.dp)) { limit?.let { DeviceLimitNotice(it, null) } } }
                    }
                    item {
                        Text(
                            stringResource(R.string.chat_devices_text), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp),
                        )
                    }
                    item { SectionHeader(stringResource(R.string.chat_devices_list)) }
                    // 2.5: "n von max Geraeten" (nur mit limits.devices_count)
                    me?.limits?.let { lm -> lm.devicesCount?.let { n -> lm to n } }?.let { (lm, n) ->
                        item {
                            Text(
                                pluralStringResource(R.plurals.r5_chat_devices_of, n, n, lm.devicesMax), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp),
                            )
                        }
                    }
                    items(me?.devices.orEmpty().filter { !it.revoked }, key = { it.id }) { d ->
                        val current = d.id == svc.deviceId
                        ListItem(
                            headlineContent = { Text(d.name.ifEmpty { "?" } + if (current) " · " + stringResource(R.string.chat_this_device) else "") },
                            supportingContent = {
                                Text(
                                    stringResource(R.string.chat_device_info, epochDay(d.created), d.lastSeen?.let { relativeTime(it) } ?: "–"),
                                )
                            },
                            // dieses Geraet nicht hier entfernen (dafuer: abmelden bzw. "Neu beginnen")
                            trailingContent = if (current) null else ({
                                SagaTextButton(onClick = { drop = d }) { Text(stringResource(R.string.chat_device_remove), color = MaterialTheme.colorScheme.error) }
                            }),
                        )
                    }
                    if (st == ChatSetup.READY) item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
                    if (st == ChatSetup.READY) item {
                        Column(Modifier.padding(16.dp)) {
                            SagaOutlinedButton(onClick = { newKey = svc.newRecoveryKey() }) { Text(stringResource(R.string.chat_new_recovery)) }
                            Text(
                                stringResource(R.string.chat_new_recovery_text), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp),
                            )
                            StartOverButton(Modifier.padding(top = 12.dp), start = true) { restart = true }
                        }
                    }
                }
            }
        }
    }
    drop?.let { d ->
        AlertDialog(
            onDismissRequest = { drop = null },
            title = { Text(stringResource(R.string.chat_device_remove_title)) },
            text = { Text(stringResource(R.string.chat_device_remove_text, d.name)) },
            confirmButton = {
                SagaTextButton(onClick = { drop = null; repo.launchAction { svc.removeDevice(d.id) } }) {
                    Text(stringResource(R.string.chat_device_remove), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { SagaTextButton(onClick = { drop = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
