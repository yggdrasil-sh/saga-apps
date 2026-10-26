package io.github.yggdrasil_sh.saga.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.data.BackupStatus
import io.github.yggdrasil_sh.saga.data.NcSettings
import io.github.yggdrasil_sh.saga.data.SagaRepository
import io.github.yggdrasil_sh.saga.data.backupStatus
import io.github.yggdrasil_sh.saga.data.ncCancel
import io.github.yggdrasil_sh.saga.data.ncConnect
import io.github.yggdrasil_sh.saga.data.ncDisconnect
import io.github.yggdrasil_sh.saga.data.ncSettings
import io.github.yggdrasil_sh.saga.ui.common.Chrome
import io.github.yggdrasil_sh.saga.ui.common.SagaTopBar
import io.github.yggdrasil_sh.saga.ui.common.dateTimeText
import io.github.yggdrasil_sh.saga.ui.common.openUrl
import io.github.yggdrasil_sh.saga.ui.nav.AppNavigator
import io.github.yggdrasil_sh.saga.ui.nav.Dest
import io.github.yggdrasil_sh.saga.util.UiText
import io.github.yggdrasil_sh.saga.util.resolve
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** Einstellungen -> "Nextcloud" (Saga 2.3): eigenes Konto verknuepfen (Login Flow v2 im Browser, die App fragt ueber den Server nach) bzw. trennen */
@Composable
fun NextcloudScreen(repo: SagaRepository, nav: AppNavigator, chrome: Chrome) {
    val context = LocalContext.current
    var st by remember { mutableStateOf<NcSettings?>(null) }
    var missing by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<Pair<UiText, Boolean>?>(null) }
    var url by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var askOff by remember { mutableStateOf(false) }

    suspend fun load() {
        try {
            val s = repo.ncSettings()
            if (s == null) missing = true else {
                st = s
                if (url == null) url = s.suggest.orEmpty()
                if (s.flow.message.isNotBlank()) msg = UiText.Raw(s.flow.message) to false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            msg = repo.friendly(e) to false
        }
    }
    LaunchedEffect(Unit) {
        load()
        while (true) {                                   // Anmeldung im Browser laeuft: nachfragen (wie im Web alle 2,5 s)
            delay(2500)
            if (st?.flow?.state == "wait") load()
        }
    }

    Scaffold(topBar = { SagaTopBar("Nextcloud", nav, chrome) }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            val w = Modifier.widthIn(max = 760.dp).fillMaxWidth()
            item { Hint(stringResource(R.string.nc_intro), w.padding(top = 8.dp)) }
            if (missing) {
                item { Hint(stringResource(R.string.nc_unsupported), w) }
                return@LazyColumn
            }
            val s = st ?: run {
                if (msg == null) item { CircularProgressIndicator(Modifier.padding(24.dp)) }
                msg?.let { m -> item { Hint(m.first.resolve(), w, error = !m.second) } }
                return@LazyColumn
            }
            if (!s.enabled) item { Hint(stringResource(R.string.nc_disabled), w, error = true) }
            when {
                s.flow.state == "wait" && !s.flow.login.isNullOrEmpty() -> {
                    item { Hint(stringResource(R.string.nc_flow_wait), w) }
                    item {
                        FlowRow(w.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { openUrl(context, s.flow.login.orEmpty(), inApp = true) }) { Text(stringResource(R.string.nc_sign_in) + " ↗") }
                            OutlinedButton(onClick = { repo.launchAction { repo.ncCancel(); load() } }) { Text(stringResource(R.string.action_cancel)) }
                        }
                    }
                }
                s.connected -> {
                    item {
                        ListItem(
                            headlineContent = { Text("✅ " + stringResource(R.string.nc_connected_as, s.user.orEmpty())) },
                            supportingContent = { Text(s.server.orEmpty().removePrefix("https://").removePrefix("http://")) },
                            modifier = w,
                        )
                    }
                    item { ListItem(headlineContent = { Text(stringResource(R.string.nc_since)) }, trailingContent = { Text(s.since?.let { dateTimeText((it * 1000).toLong(), true) } ?: "–") }, modifier = w) }
                    item { ListItem(headlineContent = { Text(stringResource(R.string.nc_used)) }, trailingContent = { Text(s.used?.let { dateTimeText((it * 1000).toLong(), true) } ?: "–") }, modifier = w) }
                    item { Hint(stringResource(R.string.nc_connected_hint), w) }
                    item {
                        OutlinedButton(onClick = { askOff = true }, modifier = w.padding(horizontal = 16.dp, vertical = 4.dp)) {
                            Text(stringResource(R.string.ub_disconnect), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                s.enabled -> {
                    item {
                        Column(w.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedTextField(
                                url.orEmpty(), { url = it }, label = { Text(stringResource(R.string.nc_address)) }, placeholder = { Text("https://cloud.example.org") },
                                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth(),
                            )
                            Button(onClick = {
                                busy = true
                                msg = UiText.Res(R.string.ub_checking) to true
                                repo.launchAction(onError = { e -> busy = false; msg = repo.friendly(e) to false }) {
                                    val flow = repo.ncConnect(url.orEmpty())
                                    msg = null
                                    busy = false
                                    if (flow.isNotEmpty()) openUrl(context, flow, inApp = true)
                                    load()
                                }
                            }, enabled = !busy && !url.isNullOrBlank()) { Text(stringResource(R.string.ub_connect)) }
                        }
                    }
                }
            }
            msg?.let { m -> item { Hint(m.first.resolve(), w, error = !m.second) } }
        }
    }
    if (askOff) {
        AlertDialog(
            onDismissRequest = { askOff = false },
            title = { Text(stringResource(R.string.nc_disconnect_title)) },
            text = { Text(stringResource(R.string.nc_disconnect_text)) },
            confirmButton = {
                TextButton(onClick = { askOff = false; repo.launchAction { repo.ncDisconnect(); msg = null; load() } }) {
                    Text(stringResource(R.string.ub_disconnect), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { askOff = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/**
 * Eintraege "Mein Backup" (Einstellungen -> Konto) und "Nextcloud" (Einstellungen -> Dienste, mit Ueberschrift
 * `heading`) mit Kurzstatus (Saga 2.3). Aeltere Server kennen die Endpunkte nicht -> nichts anzeigen.
 */
@Composable
fun AccountServicesRows(
    repo: SagaRepository, nav: AppNavigator, modifier: Modifier, reloadKey: Any? = null,
    backup: Boolean = true, nextcloud: Boolean = true, heading: (@Composable () -> Unit)? = null,
) {
    val showBackup = backup
    val showNc = nextcloud
    var bk by remember { mutableStateOf<BackupStatus?>(null) }
    var nc by remember { mutableStateOf<NcSettings?>(null) }
    LaunchedEffect(reloadKey) {
        if (showBackup) bk = try {
            repo.backupStatus()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (showNc) nc = try {
            repo.ncSettings()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }
    val ncShown = nc?.takeIf { it.enabled || it.connected }
    Column(modifier) {
        if (ncShown != null) heading?.invoke()
        bk?.let { b ->
            ListItem(
                headlineContent = { Text(stringResource(R.string.ub_title)) },
                supportingContent = {
                    Text(
                        when {
                            !b.allowed -> stringResource(R.string.ub_disabled_short)
                            b.running -> stringResource(R.string.ub_running_short)
                            b.lastOk != null -> stringResource(R.string.ub_last_ok_at, dateTimeText((b.lastOk * 1000).toLong(), true))
                            !b.ready -> stringResource(R.string.ub_not_set_up)
                            else -> stringResource(R.string.ub_none_yet)
                        },
                    )
                },
                modifier = Modifier.clickable { nav.push(Dest.Backup) },
            )
        }
        ncShown?.let { n ->
            ListItem(
                headlineContent = { Text("Nextcloud") },
                supportingContent = { Text(if (n.connected) stringResource(R.string.nc_connected_as, n.user.orEmpty()) else stringResource(R.string.nc_not_connected)) },
                modifier = Modifier.clickable { nav.push(Dest.Nextcloud) },
            )
        }
    }
}
