package io.github.yggdrasil_sh.saga.ui.settings

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

/** Einstellungen: Server, Versionen, Apps & Geraete, Abmelden */
@Composable
fun SettingsScreen(repo: SagaRepository, nav: AppNavigator, chrome: Chrome) {
    val server by repo.server.collectAsState()
    val serverVersion by repo.serverVersion.collectAsState()
    val minApp by repo.minApp.collectAsState()
    var apps by remember { mutableStateOf<AppsResponse?>(null) }
    var appsError by remember { mutableStateOf<String?>(null) }
    var drop by remember { mutableStateOf<DeviceInfo?>(null) }
    var confirmLogout by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun loadApps() {
        try {
            apps = repo.apps()
            appsError = null
        } catch (e: Exception) {
            appsError = repo.friendly(e)
        }
    }
    LaunchedEffect(Unit) {
        if (serverVersion == null) repo.checkVersion()
        loadApps()
    }

    Scaffold(topBar = { SagaTopBar("Einstellungen", nav, chrome) }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            val w = Modifier.widthIn(max = 760.dp).fillMaxWidth()
            item { SectionHeader("Server", w) }
            item { ListItem(headlineContent = { Text(server.ifEmpty { "–" }) }, supportingContent = { Text("Zum Wechseln abmelden und neu anmelden") }, modifier = w) }

            item { SectionHeader("Über", w) }
            item { ListItem(headlineContent = { Text("App") }, trailingContent = { Text("Saga ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})") }, modifier = w) }
            item { ListItem(headlineContent = { Text("Server") }, trailingContent = { Text(serverVersion?.let { "Saga $it" } ?: "–") }, modifier = w) }
            if (minApp != null && repo.appTooOld) {
                item {
                    Text(
                        "Diese App ist älter als der Server erwartet (ab $minApp). Bitte die App aktualisieren.",
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = w.padding(horizontal = 16.dp),
                    )
                }
            }

            item { SectionHeader("Apps & Geräte", w) }
            item {
                Text(
                    "Angemeldete Apps bleiben angemeldet. Zu jeder gehört ein kleiner Schlüssel für Widget, Teilen und Kurzbefehle. " +
                        "Verlorenes Gerät → abmelden, dann ist beides weg.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = w.padding(horizontal = 16.dp),
                )
            }
            appsError?.let { e -> item { Text(e, color = MaterialTheme.colorScheme.error, modifier = w.padding(16.dp)) } }
            apps?.let { a ->
                if (a.devices.isEmpty()) item { Text("Keine App angemeldet.", modifier = w.padding(16.dp)) }
                items(a.devices, key = { "dev-${it.id}" }) { d ->
                    ListItem(
                        headlineContent = { Text(d.name) },
                        supportingContent = {
                            Text("angemeldet ${epochDay(d.created)} · zuletzt ${epochDay(d.lastUsed)}" + if (d.key != null) " · mit Widget-Schlüssel" else "")
                        },
                        trailingContent = { TextButton(onClick = { drop = d }) { Text("Abmelden", color = MaterialTheme.colorScheme.error) } },
                        modifier = w,
                    )
                }
                if (a.keys.isNotEmpty()) {
                    item {
                        Text(
                            "Weitere Schlüssel: ${a.keys.size} (verwalten im Web)", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = w.padding(16.dp),
                        )
                    }
                }
            }

            item { HorizontalDivider(w.padding(vertical = 12.dp)) }
            item {
                Column(w.padding(16.dp)) {
                    OutlinedButton(onClick = { confirmLogout = true }) { Text("Abmelden", color = MaterialTheme.colorScheme.error) }
                    Text(
                        "Löscht die Anmeldung dieses Geräts am Server und die Daten in der App.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }

    drop?.let { d ->
        AlertDialog(
            onDismissRequest = { drop = null },
            title = { Text("„${d.name}“ abmelden?") },
            text = { Text("Die App muss sich neu anmelden; Widget und Teilen verlieren den Zugang.") },
            confirmButton = {
                TextButton(onClick = {
                    drop = null
                    scope.launch {
                        try {
                            repo.deleteDevice(d.id)
                        } catch (e: Exception) {
                            appsError = repo.friendly(e)
                        }
                        loadApps()
                    }
                }) { Text("Abmelden", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { drop = null }) { Text("Abbrechen") } },
        )
    }
    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("Auf diesem Gerät abmelden?") },
            confirmButton = {
                TextButton(onClick = { confirmLogout = false; scope.launch { repo.logout() } }) { Text("Abmelden", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("Abbrechen") } },
        )
    }
}
