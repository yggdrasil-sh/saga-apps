package io.github.yggdrasil_sh.saga.ui.settings

import androidx.compose.ui.res.stringResource
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.util.UiText
import io.github.yggdrasil_sh.saga.util.resolve
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import io.github.yggdrasil_sh.saga.BuildConfig
import io.github.yggdrasil_sh.saga.data.AppsResponse
import io.github.yggdrasil_sh.saga.data.DeviceInfo
import io.github.yggdrasil_sh.saga.data.SagaRepository
import io.github.yggdrasil_sh.saga.ui.common.Chrome
import io.github.yggdrasil_sh.saga.ui.common.SagaTopBar
import io.github.yggdrasil_sh.saga.ui.common.SectionHeader
import io.github.yggdrasil_sh.saga.ui.common.epochDay
import io.github.yggdrasil_sh.saga.ui.nav.AppNavigator
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import io.github.yggdrasil_sh.saga.auth.PairLink
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon

/** Einstellungen: Server, Versionen, Apps & Geraete, Abmelden */
@Composable
fun SettingsScreen(repo: SagaRepository, nav: AppNavigator, chrome: Chrome) {
    val server by repo.server.collectAsState()
    val account by repo.account.collectAsState()
    val serverVersion by repo.serverVersion.collectAsState()
    val minApp by repo.minApp.collectAsState()
    var apps by remember { mutableStateOf<AppsResponse?>(null) }
    var appsError by remember { mutableStateOf<UiText?>(null) }
    var drop by remember { mutableStateOf<DeviceInfo?>(null) }
    var confirmLogout by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun loadApps() {
        try {
            apps = repo.apps()
            appsError = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            appsError = repo.friendly(e)
        }
    }
    LaunchedEffect(Unit) {
        if (serverVersion == null) repo.checkVersion()
        loadApps()
    }

    Scaffold(topBar = { SagaTopBar(stringResource(R.string.nav_settings), nav, chrome) }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            val w = Modifier.widthIn(max = 760.dp).fillMaxWidth()
            item { SectionHeader(stringResource(R.string.settings_account), w) }
            item {
                // Mit welchem Konto auf welchem Server angemeldet (Schutz vor unbemerktem Kontowechsel)
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_signed_in_as, account.ifEmpty { "–" })) },
                    supportingContent = { Text(stringResource(R.string.settings_on_server, PairLink.displayHost(server).ifEmpty { "–" })) },
                    leadingContent = { Icon(Icons.Filled.Person, null) },
                    modifier = w,
                )
            }
            item {
                Text(
                    stringResource(R.string.settings_server_change_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = w.padding(horizontal = 16.dp),
                )
            }

            item { SectionHeader(stringResource(R.string.settings_about), w) }
            item { ListItem(headlineContent = { Text(stringResource(R.string.settings_app)) }, trailingContent = { Text("Saga ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})") }, modifier = w) }
            item { ListItem(headlineContent = { Text(stringResource(R.string.settings_server)) }, trailingContent = { Text(serverVersion?.let { "Saga $it" } ?: "–") }, modifier = w) }
            if (minApp != null && repo.appTooOld) {
                item {
                    Text(
                        stringResource(R.string.settings_app_too_old, minApp ?: ""),
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = w.padding(horizontal = 16.dp),
                    )
                }
            }

            item { SectionHeader(stringResource(R.string.settings_apps_devices), w) }
            item {
                Text(
                    stringResource(R.string.settings_apps_explainer),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = w.padding(horizontal = 16.dp),
                )
            }
            appsError?.let { e -> item { Text(e.resolve(), color = MaterialTheme.colorScheme.error, modifier = w.padding(16.dp)) } }
            apps?.let { a ->
                if (a.devices.isEmpty()) item { Text(stringResource(R.string.settings_no_apps), modifier = w.padding(16.dp)) }
                items(a.devices, key = { "dev-${it.id}" }) { d ->
                    ListItem(
                        headlineContent = { Text(d.name) },
                        supportingContent = {
                            Text(
                                listOfNotNull(
                                    stringResource(R.string.settings_device_info, epochDay(d.created), epochDay(d.lastUsed)),
                                    if (d.key != null) stringResource(R.string.settings_device_with_key) else null,
                                ).joinToString(" · ")
                            )
                        },
                        trailingContent = { TextButton(onClick = { drop = d }) { Text(stringResource(R.string.sign_out), color = MaterialTheme.colorScheme.error) } },
                        modifier = w,
                    )
                }
                if (a.keys.isNotEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.settings_more_keys, a.keys.size), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = w.padding(16.dp),
                        )
                    }
                }
            }

            item { HorizontalDivider(w.padding(vertical = 12.dp)) }
            item {
                Column(w.padding(16.dp)) {
                    OutlinedButton(onClick = { confirmLogout = true }) { Text(stringResource(R.string.sign_out), color = MaterialTheme.colorScheme.error) }
                    Text(
                        stringResource(R.string.settings_sign_out_explainer),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }

    drop?.let { d ->
        AlertDialog(
            onDismissRequest = { drop = null },
            title = { Text(stringResource(R.string.settings_sign_out_device_title, d.name)) },
            text = { Text(stringResource(R.string.settings_sign_out_device_text)) },
            confirmButton = {
                TextButton(onClick = {
                    drop = null
                    scope.launch {
                        try {
                            repo.deleteDevice(d.id)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            appsError = repo.friendly(e)
                        }
                        loadApps()
                    }
                }) { Text(stringResource(R.string.sign_out), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { drop = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text(stringResource(R.string.settings_sign_out_this_device)) },
            confirmButton = {
                TextButton(onClick = { confirmLogout = false; repo.launchAction { repo.logout() } }) { Text(stringResource(R.string.sign_out), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
