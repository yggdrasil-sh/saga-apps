package io.github.yggdrasil_sh.saga.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.yggdrasil_sh.saga.BuildConfig
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.auth.PairLink
import io.github.yggdrasil_sh.saga.chat.ChatSetup
import io.github.yggdrasil_sh.saga.data.AppsResponse
import io.github.yggdrasil_sh.saga.data.DeviceInfo
import io.github.yggdrasil_sh.saga.data.SagaRepository
import io.github.yggdrasil_sh.saga.data.avatarState
import io.github.yggdrasil_sh.saga.ui.common.Chrome
import io.github.yggdrasil_sh.saga.ui.common.SagaTopBar
import io.github.yggdrasil_sh.saga.ui.common.dateTimeText
import io.github.yggdrasil_sh.saga.ui.common.epochDay
import io.github.yggdrasil_sh.saga.ui.nav.AppNavigator
import io.github.yggdrasil_sh.saga.ui.nav.Dest
import io.github.yggdrasil_sh.saga.ui.theme.SagaSectionTitle
import io.github.yggdrasil_sh.saga.ui.theme.sagaSegment
import io.github.yggdrasil_sh.saga.util.ShotMode
import io.github.yggdrasil_sh.saga.util.UiText
import io.github.yggdrasil_sh.saga.util.UpdateHint
import io.github.yggdrasil_sh.saga.util.resolve
import kotlinx.coroutines.CancellationException
import androidx.compose.foundation.gestures.scrollBy
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Einstellungen in drei Bereichen (2.5, wie iOS): Startliste mit Kopfzeile (Profilbild + Name -> Konto), Zeilen
 * "Einstellungen" und "Info" (Punkt bei bekanntem Update) und "Abmelden" ganz unten. Unterseiten:
 *  - Konto (SettingsAccountScreen): Anmeldung, Anzeigename, Profilbild, Anmeldung & Sicherheit, oeffentlicher Name,
 *    Adressbuecher verbundener Server, Mein Backup, Apps & Geraete
 *  - Einstellungen (SettingsGeneralScreen): Allgemein (Sprache) · Benachrichtigungen (Glocke, Mitteilungen/eigene
 *    Verbindung, Anruf-Hinweise) · Chat (Chats & Geraete, Datenschutz) · Dienste (Nextcloud)
 *  - Info (SettingsInfoScreen): Versionen, Kanal, Update-Hinweis, Lizenzen
 * Spruenge (?settings=backup, Glocke, Chat-Hinweise): SettingsNav.open / SettingsArea.
 */
@Composable
fun SettingsScreen(repo: SagaRepository, nav: AppNavigator, chrome: Chrome) {
    val server by repo.server.collectAsState()
    val account by repo.account.collectAsState()
    val signIns by repo.signInCount.collectAsState()
    val enabled by UpdateHintModel.enabled.collectAsState()
    val release by UpdateHintModel.release.collectAsState()
    val update = UpdateHintModel.available(enabled, release)
    var ownAvatar by remember { mutableStateOf(SettingsMemory.avatar) }
    var confirmLogout by remember { mutableStateOf(false) }
    LaunchedEffect(account, signIns) {
        ownAvatar = try {
            repo.avatarState().url.also { SettingsMemory.avatar = it }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            ownAvatar
        }
    }
    Scaffold(topBar = { SagaTopBar(stringResource(R.string.nav_settings), nav, chrome) }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            val w = Modifier.widthIn(max = 760.dp).fillMaxWidth()
            item(key = "head") {
                val host = ShotMode.host(PairLink.displayHost(server)).ifEmpty { "–" }
                ListItem(
                    headlineContent = {
                        Text(account.ifEmpty { stringResource(R.string.settings_account) }, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold))
                    },
                    supportingContent = { Text(host, maxLines = 1) },
                    leadingContent = { io.github.yggdrasil_sh.saga.ui.chat.ChatAvatar(account.ifEmpty { "?" }, 56.dp, avatar = ownAvatar) },
                    trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
                    colors = SEG,
                    modifier = w.padding(top = 8.dp).sagaSegment(0, 1, color = MaterialTheme.colorScheme.surfaceContainer)
                        .clickable(onClickLabel = stringResource(R.string.settings_account)) { nav.push(Dest.SettingsAccount) }
                        .padding(vertical = 6.dp),
                )
            }
            item(key = "areas") {
                Column(w.padding(top = 16.dp)) {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.nav_settings)) },
                        leadingContent = { Icon(Icons.Filled.Settings, null) },
                        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
                        colors = SEG, modifier = Modifier.sagaSegment(0, 2).clickable { nav.push(Dest.SettingsGeneral) },
                    )
                    val dotLabel = stringResource(R.string.settings_update_dot)
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.settings_info)) },
                        leadingContent = { Icon(Icons.Filled.Info, null) },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (update != null) {
                                    Box(Modifier.size(10.dp).background(MaterialTheme.colorScheme.error, CircleShape).semantics { contentDescription = dotLabel })
                                }
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                            }
                        },
                        colors = SEG, modifier = Modifier.sagaSegment(1, 2).clickable { nav.push(Dest.SettingsInfo) },
                    )
                }
            }
            item(key = "logout") {
                Column(w.padding(start = 16.dp, end = 16.dp, top = 28.dp, bottom = 16.dp)) {
                    // Design 2: Abmelden als tonaler Warnknopf ueber die ganze Breite (gut zu treffen, klar als Warnung)
                    FilledTonalButton(
                        onClick = { confirmLogout = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                    ) { Text(stringResource(R.string.sign_out)) }
                    Text(
                        stringResource(R.string.settings_sign_out_explainer),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
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

// ------------------------------------------------------------------------------------------------ Konto

/** Konto: Anmeldung, Name, Profilbild, Sicherheit, oeffentlicher Name, Foederation, Mein Backup, Apps & Geraete */
@Composable
fun SettingsAccountScreen(repo: SagaRepository, nav: AppNavigator, chrome: Chrome) {
    val server by repo.server.collectAsState()
    val account by repo.account.collectAsState()
    val signIns by repo.signInCount.collectAsState()
    // zuletzt geladene Geraeteliste sofort zeigen (sonst springt die Liste beim Zurueckkehren, Nr. 34)
    var apps by remember { mutableStateOf(SettingsMemory.apps.takeIf { SettingsMemory.appsKey == signIns }) }
    var appsError by remember { mutableStateOf<UiText?>(null) }
    var drop by remember { mutableStateOf<DeviceInfo?>(null) }
    var ownAvatar by remember { mutableStateOf(SettingsMemory.avatar) }
    val scope = rememberCoroutineScope()

    suspend fun loadApps() {
        try {
            apps = repo.apps()
            SettingsMemory.apps = apps
            SettingsMemory.appsKey = signIns
            appsError = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            appsError = repo.friendly(e)
        }
    }
    // Nach einer Kopplung/Anmeldung (gleicher Server, Ansicht bleibt) Geraeteliste neu laden (Nachtest N2)
    LaunchedEffect(signIns) { loadApps() }
    val listState = rememberAreaListState(SettingsArea.KONTO, signIns)
    Scaffold(topBar = { SagaTopBar(stringResource(R.string.settings_account), nav, chrome) }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), state = listState, horizontalAlignment = Alignment.CenterHorizontally) {
            val w = Modifier.widthIn(max = 760.dp).fillMaxWidth()
            item(key = "konto") {
                // Mit welchem Konto auf welchem Server angemeldet (Schutz vor unbemerktem Kontowechsel)
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_signed_in_as, account.ifEmpty { "–" })) },
                    supportingContent = { Text(stringResource(R.string.settings_on_server, ShotMode.host(PairLink.displayHost(server)).ifEmpty { "–" })) },
                    leadingContent = { io.github.yggdrasil_sh.saga.ui.chat.ChatAvatar(account.ifEmpty { "?" }, 48.dp, avatar = ownAvatar) },
                    colors = SEG, modifier = w.padding(top = 8.dp).sagaSegment(0, 1, color = MaterialTheme.colorScheme.surfaceContainer),
                )
            }
            item(key = "server-hint") {
                Text(
                    stringResource(R.string.settings_server_change_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = w.padding(start = 20.dp, end = 20.dp, top = 6.dp),
                )
            }
            // Bughunt D01: Anzeigename; 2.1: Profilbild und "Anmeldung & Sicherheit" (Verwalten im Browser)
            item(key = "name") { DisplayNameBlock(repo, w.padding(top = 8.dp), reloadKey = signIns) }
            item(key = "avatar") {
                ProfilePictureBlock(repo, account, w.padding(top = 8.dp), reloadKey = signIns) { ownAvatar = it?.url; SettingsMemory.avatar = it?.url }
            }
            item(key = "security") { SecurityBlock(repo, w, reloadKey = signIns) }
            // Veroeffentlichen: oeffentlicher Name; 2.3: "In Adressbuechern verbundener Server erscheinen"
            item(key = "public") { PublicNameBlock(repo, w.padding(top = 8.dp), reloadKey = signIns) }
            item(key = "federation") { FederationDirectoryBlock(repo, w, reloadKey = signIns) }
            // 2.3: "Mein Backup" (nur Server, die es koennen)
            item(key = "backup") { AccountServicesRows(repo, nav, w, reloadKey = signIns, nextcloud = false) }

            item(key = "apps") { SagaSectionTitle(stringResource(R.string.settings_apps_devices), w.padding(top = 12.dp)) }
            item(key = "apps-hint") {
                Text(
                    stringResource(R.string.settings_apps_explainer),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = w.padding(horizontal = 16.dp),
                )
            }
            appsError?.let { e -> item(key = "apps-error") { Text(e.resolve(), color = MaterialTheme.colorScheme.error, modifier = w.padding(16.dp)) } }
            apps?.let { a ->
                if (a.devices.isEmpty()) item(key = "apps-none") { Text(stringResource(R.string.settings_no_apps), modifier = w.padding(16.dp)) }
                itemsIndexed(a.devices, key = { _, it -> "dev-${it.id}" }) { i, d ->
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
                        // Nr. 33: dieses Geraet kennzeichnen; Abmelden hier ueber den normalen Weg (Startliste), nicht per Liste
                        overlineContent = if (d.current) ({ Text(stringResource(R.string.settings_device_current), color = MaterialTheme.colorScheme.primary) }) else null,
                        trailingContent = { if (!d.current) TextButton(onClick = { drop = d }) { Text(stringResource(R.string.sign_out), color = MaterialTheme.colorScheme.error) } },
                        colors = SEG, modifier = w.padding(top = if (i == 0) 8.dp else 0.dp).sagaSegment(i, a.devices.size),
                    )
                }
                if (a.keys.isNotEmpty()) {
                    item(key = "apps-keys") {
                        Text(
                            stringResource(R.string.settings_more_keys, a.keys.size), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = w.padding(16.dp),
                        )
                    }
                }
            }
            item(key = "end") { Box(Modifier.padding(bottom = 24.dp)) }
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
}

// ------------------------------------------------------------------------------------------------ Einstellungen

/** Allgemein (Sprache) · Benachrichtigungen (Glocke, Mitteilungen, Anrufe) · Chat (Geraete, Datenschutz) · Dienste (Nextcloud) */
@Composable
fun SettingsGeneralScreen(repo: SagaRepository, nav: AppNavigator, chrome: Chrome) {
    val signIns by repo.signInCount.collectAsState()
    var notifKinds by remember { mutableStateOf(SettingsMemory.kinds.takeIf { SettingsMemory.kindsKey == signIns } ?: emptyList()) }
    val pushSupported by repo.push.supported.collectAsState()
    val chatState by repo.chat.state.collectAsState()
    val chatMe by repo.chat.me.collectAsState()
    val callsOn by repo.calls.enabled.collectAsState()
    LaunchedEffect(signIns) {
        try {
            notifKinds = repo.live.settings().kinds
            SettingsMemory.kinds = notifKinds
            SettingsMemory.kindsKey = signIns
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
        repo.push.load()
        repo.chat.refresh()
        if (repo.calls.enabled.value == null) repo.calls.checkEnabled()
    }
    val listState = rememberAreaListState(SettingsArea.EINSTELLUNGEN, signIns)
    val features25 by repo.serverFeatures.collectAsState()
    Scaffold(topBar = { SagaTopBar(stringResource(R.string.nav_settings), nav, chrome) }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), state = listState, horizontalAlignment = Alignment.CenterHorizontally) {
            val w = Modifier.widthIn(max = 760.dp).fillMaxWidth()
            // Allgemein: Sprache der App (auf einem fremden/geteilten Geraet unabhaengig von der Geraetesprache)
            item(key = "g-general") { GroupHeading(stringResource(R.string.settings_group_general), w) }
            item(key = "sprache") { LanguageRow(w.sagaSegment(0, 1)) }

            // Benachrichtigungen: Glocke je Art, Mitteilungen (UnifiedPush bzw. eigene Verbindung), Anruf-Hinweise
            val callHints = callsOn == true
            if (notifKinds.isNotEmpty() || pushSupported || callHints) {
                item(key = "notifs") { GroupHeading(stringResource(R.string.notifications), w) }
            }
            if (notifKinds.isNotEmpty()) {
                itemsIndexed(notifKinds, key = { _, it -> "nk-" + it.kind }) { i, k ->
                    io.github.yggdrasil_sh.saga.ui.push.SwitchRow(stringResource(notifKindLabel(k.kind)), k.on, w.sagaSegment(i, notifKinds.size)) { on ->
                        repo.launchAction { notifKinds = repo.live.setKind(k.kind, on).kinds; SettingsMemory.kinds = notifKinds }
                    }
                }
            }
            if (pushSupported) {
                item(key = "push") {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.push_title)) },
                        supportingContent = { Text(stringResource(R.string.push_settings_hint)) },
                        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
                        colors = SEG, modifier = w.padding(top = if (notifKinds.isNotEmpty()) 10.dp else 0.dp).sagaSegment(0, 1).clickable { nav.push(Dest.Push) },
                    )
                }
            }
            // 2.5 E2 (taskdue1): taegliche Zusammenfassung der Aufgaben (Schalter + Uhrzeit)
            if ("taskdue1" in features25) item(key = "task-digest") { TaskDigestRow(repo, w) }
            if (callHints) item(key = "calls") { CallsFullScreenHint(w) }
            if (callHints) item(key = "calls-push") { CallsPushHint(repo.push, w) { nav.push(Dest.Push) } }

            // Chat: Chats & Geraete, Datenschutz (2.4: Gelesen-Bestaetigungen und Online-Status, aeltere Server: fehlt)
            val chatDev = chatState != ChatSetup.UNSUPPORTED && chatState != ChatSetup.OFF
            val rcFeat = chatState == ChatSetup.READY && io.github.yggdrasil_sh.saga.chat.ReceiptLogic.has(chatMe, "receipts") && chatMe?.privacy != null
            val onFeat = chatState == ChatSetup.READY && io.github.yggdrasil_sh.saga.chat.ReceiptLogic.has(chatMe, "online") && chatMe?.privacy != null
            if (chatDev || rcFeat || onFeat) item(key = "g-chat") { GroupHeading(stringResource(R.string.settings_group_chat), w) }
            if (chatDev) {
                item(key = "chats") {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.chat_devices)) },
                        supportingContent = { Text(stringResource(R.string.chat_devices_hint)) },
                        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
                        colors = SEG, modifier = w.sagaSegment(0, 1).clickable { nav.push(Dest.ChatDevices) },
                    )
                }
            }
            if (rcFeat || onFeat) {
                val pv = chatMe?.privacy ?: io.github.yggdrasil_sh.saga.chat.ChatPrivacy()
                item(key = "privacy") { SagaSectionTitle(stringResource(R.string.chat_privacy_title), w.padding(top = 4.dp)) }
                val pn = (if (rcFeat) 1 else 0) + (if (onFeat) 1 else 0)
                if (rcFeat) item(key = "privacy-receipts") {
                    io.github.yggdrasil_sh.saga.ui.push.SwitchRow(
                        stringResource(R.string.chat_privacy_receipts), pv.receipts, w.sagaSegment(0, pn), hint = stringResource(R.string.chat_privacy_receipts_hint),
                    ) { on -> repo.launchAction { repo.chat.setPrivacy(receipts = on) } }
                }
                if (onFeat) item(key = "privacy-online") {
                    io.github.yggdrasil_sh.saga.ui.push.SwitchRow(
                        stringResource(R.string.chat_privacy_online), pv.online, w.sagaSegment(pn - 1, pn), hint = stringResource(R.string.chat_privacy_online_hint),
                    ) { on -> repo.launchAction { repo.chat.setPrivacy(online = on) } }
                }
            }

            // Dienste: Nextcloud (2.3, nur Server, die es koennen); Immich/Kalender verwaltet Android im Web
            item(key = "nextcloud") { AccountServicesRows(repo, nav, w, reloadKey = signIns, backup = false, heading = { GroupHeading(stringResource(R.string.settings_group_services), Modifier) }) }
            item(key = "end") { Box(Modifier.padding(bottom = 24.dp)) }
        }
    }
}

/** Ueberschrift einer Gruppe von Abschnitten (Allgemein · Benachrichtigungen · Chat · Dienste) */
@Composable
private fun GroupHeading(text: String, modifier: Modifier) {
    SagaSectionTitle(text, modifier.padding(top = 14.dp, bottom = 2.dp))
}

// ------------------------------------------------------------------------------------------------ Info

/** Versionen, Kanal, Update-Hinweis (eine Schaltflaeche), Lizenzhinweise */
@Composable
fun SettingsInfoScreen(repo: SagaRepository, nav: AppNavigator, chrome: Chrome) {
    val serverVersion by repo.serverVersion.collectAsState()
    val minApp by repo.minApp.collectAsState()
    val label = UpdateHintModel.label
    LaunchedEffect(Unit) {
        if (repo.serverVersion.value == null) repo.checkVersion()
        UpdateHintModel.infoOpened()
    }
    val listState = rememberAreaListState(SettingsArea.INFO, repo.signInCount.collectAsState().value)
    Scaffold(topBar = { SagaTopBar(stringResource(R.string.settings_info), nav, chrome) }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), state = listState, horizontalAlignment = Alignment.CenterHorizontally) {
            val w = Modifier.widthIn(max = 760.dp).fillMaxWidth()
            item(key = "versions") { SagaSectionTitle(stringResource(R.string.settings_versions), w.padding(top = 8.dp)) }
            item(key = "app") {
                val shown = if (ShotMode.on && label == BuildConfig.VERSION_NAME) ShotMode.version("") else "Saga $label (${BuildConfig.VERSION_CODE})"
                ListItem(headlineContent = { Text(stringResource(R.string.settings_app)) }, trailingContent = { Text(shown) }, colors = SEG, modifier = w.sagaSegment(0, 3))
            }
            item(key = "server") {
                ListItem(headlineContent = { Text(stringResource(R.string.settings_server)) }, trailingContent = { Text(serverVersion?.let { "Saga $it" } ?: "–") }, colors = SEG, modifier = w.sagaSegment(1, 3))
            }
            item(key = "channel") {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_channel)) },
                    trailingContent = {
                        Text(stringResource(when (UpdateHint.channel(label)) {
                            "testing" -> R.string.settings_channel_testing
                            "stable" -> R.string.settings_channel_stable
                            else -> R.string.settings_channel_custom
                        }))
                    },
                    colors = SEG, modifier = w.sagaSegment(2, 3),
                )
            }
            if (minApp != null && repo.appTooOld) {
                item(key = "too-old") {
                    Text(
                        stringResource(R.string.settings_app_too_old, minApp ?: ""),
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = w.padding(horizontal = 20.dp, vertical = 6.dp),
                    )
                }
            }
            item(key = "update") { UpdateHintSection(w) }
            item(key = "licenses") { SagaSectionTitle(stringResource(R.string.settings_licenses), w.padding(top = 12.dp)) }
            item(key = "license-text") {
                Text(
                    stringResource(R.string.settings_license_text), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = w.padding(horizontal = 20.dp).padding(bottom = 24.dp),
                )
            }
        }
    }
}

/** Info -> Updates: Schalter, Statuszeile und EINE Schaltflaeche ("Nach Updates suchen" / "Pruefe …" / "Update") */
@Composable
private fun UpdateHintSection(modifier: Modifier) {
    val context = LocalContext.current
    val enabled by UpdateHintModel.enabled.collectAsState()
    val release by UpdateHintModel.release.collectAsState()
    val checked by UpdateHintModel.checked.collectAsState()
    val checking by UpdateHintModel.checking.collectAsState()
    val failed by UpdateHintModel.failed.collectAsState()
    val available = UpdateHintModel.available(enabled, release)
    Column(modifier) {
        SagaSectionTitle(stringResource(R.string.update_title), Modifier.padding(top = 12.dp))
        io.github.yggdrasil_sh.saga.ui.push.SwitchRow(
            stringResource(R.string.update_toggle), enabled, Modifier.sagaSegment(0, if (enabled) 2 else 1),
        ) { UpdateHintModel.setEnabled(it) }
        if (enabled) {
            Column(
                Modifier.sagaSegment(1, 2).padding(horizontal = 16.dp, vertical = 12.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                when (val s = UpdateHint.status(available, failed, checked)) {
                    is UpdateHint.Status.Available -> Text(
                        stringResource(R.string.update_available, s.version), style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    UpdateHint.Status.UpToDate -> Text(stringResource(R.string.update_latest), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    UpdateHint.Status.Failed -> Text(stringResource(R.string.update_failed), color = MaterialTheme.colorScheme.error)
                    UpdateHint.Status.None -> {}
                }
                checked?.let { c ->
                    Text(
                        stringResource(R.string.update_last_checked, dateTimeText(c, true)), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    when (UpdateHint.button(checking, available)) {
                        UpdateHint.Button.CHECKING -> OutlinedButton(onClick = {}, enabled = false) { Text(stringResource(R.string.update_checking)) }
                        UpdateHint.Button.CHECK -> OutlinedButton(onClick = { UpdateHintModel.checkNow() }) { Text(stringResource(R.string.update_check)) }
                        // "Update": APK (sonst Release-Seite) im Browser bzw. Download-Manager - nie als ausgeschriebene URL
                        UpdateHint.Button.UPDATE -> Button(onClick = {
                            available?.let(UpdateHint::action)?.let { io.github.yggdrasil_sh.saga.ui.common.openUrl(context, it) }
                        }) { Text(stringResource(R.string.update_button)) }
                    }
                }
            }
        }
        Text(
            stringResource(when (UpdateHint.channel(UpdateHintModel.label)) {
                "testing" -> R.string.update_footer_testing
                "stable" -> R.string.update_footer_stable
                else -> R.string.update_footer_custom
            }),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp),
        )
    }
}

// ------------------------------------------------------------------------------------------------ Spruenge

/** Spruenge in die Einstellungen (Mitteilung ?settings=…, Glocke): Startliste, Bereich, ggf. Unterseite + Zeile */
object SettingsNav {
    fun open(nav: AppNavigator, section: String?, replace: Boolean) {
        if (replace) nav.top(Dest.Settings) else nav.push(Dest.Settings)
        val area = SettingsArea.of(section) ?: return
        SettingsMemory.scrollTo = SettingsArea.scrollTarget(section)?.let { area to it }
        nav.push(dest(area))
        when (SettingsArea.sub(section)) {
            SettingsArea.Sub.BACKUP -> nav.push(Dest.Backup)
            SettingsArea.Sub.NEXTCLOUD -> nav.push(Dest.Nextcloud)
            null -> {}
        }
    }

    fun dest(a: SettingsArea): Dest = when (a) {
        SettingsArea.KONTO -> Dest.SettingsAccount
        SettingsArea.EINSTELLUNGEN -> Dest.SettingsGeneral
        SettingsArea.INFO -> Dest.SettingsInfo
    }
}

/**
 * Scrollstand eines Bereichs (Nr. 34: nach der Rueckkehr aus einer Unterseite wieder an derselben Stelle). Viele
 * Eintraege laden asynchron und aendern dabei ihre Hoehe: warten, bis die Liste wieder so viele Eintraege hat, dann den
 * beim Verlassen ersten ganz sichtbaren Eintrag oben hinstellen, bis ~2 s nachziehen - nie gegen den Nutzer.
 * Sprungziel (SettingsNav): zur Zeile mit diesem Schluessel scrollen, sobald sie da ist.
 */
@Composable
private fun rememberAreaListState(area: SettingsArea, signIns: Int): LazyListState {
    val listState = rememberLazyListState()
    LaunchedEffect(area, signIns) {
        val jump = SettingsMemory.scrollTo?.takeIf { it.first == area }?.second
        if (jump != null) {
            SettingsMemory.scrollTo = null
            // Zeilen laden ggf. erst nach: suchen, sonst eine Bildschirmhoehe weiter, bis ~3 s
            for (n in 0 until 30) {
                val i = indexOfKey(listState, jump)
                if (i != null) {
                    listState.animateScrollToItem(i)
                    return@LaunchedEffect
                }
                if (listState.canScrollForward) listState.scrollBy(listState.layoutInfo.viewportSize.height * 0.8f)
                delay(100)
            }
            return@LaunchedEffect
        }
        val saved = SettingsMemory.scroll[area]?.takeIf { it.key == signIns && it.index > 0 } ?: return@LaunchedEffect
        for (n in 0 until 30) {
            if (listState.layoutInfo.totalItemsCount >= saved.count) break
            delay(100)
        }
        listState.scrollToItem(saved.index)
        repeat(20) {
            delay(100)
            if (listState.isScrollInProgress) return@LaunchedEffect
            if (listState.firstVisibleItemIndex < saved.index && listState.canScrollForward) listState.scrollToItem(saved.index)
        }
    }
    DisposableEffect(area, signIns) {
        onDispose {
            val vis = listState.layoutInfo.visibleItemsInfo
            val anchor = vis.firstOrNull { it.offset >= 0 } ?: vis.firstOrNull()
            SettingsMemory.scroll[area] = SettingsMemory.Pos(signIns, anchor?.index ?: 0, listState.layoutInfo.totalItemsCount)
        }
    }
    return listState
}

/** Index der Zeile mit diesem Schluessel (nur unter den bisher gemessenen Eintraegen) */
private fun indexOfKey(s: LazyListState, key: String): Int? = s.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }?.index

/** Texte der Benachrichtigungs-Arten (GLOSSAR "Live-Kanal und Benachrichtigungen") */
fun notifKindLabel(kind: String): Int = when (kind) {
    "share.added" -> R.string.notify_kind_share_added
    "share.role" -> R.string.notify_kind_share_role
    "comment.new" -> R.string.notify_kind_comment_new
    "comment.reply" -> R.string.notify_kind_comment_reply
    "publish.withdrawn" -> R.string.notify_kind_publish_withdrawn
    "chat.message" -> R.string.notify_kind_chat_message
    "call", "call.missed" -> R.string.notify_kind_call_missed
    "backup.failed" -> R.string.notify_kind_backup_failed
    "userbackup.failed" -> R.string.notify_kind_userbackup_failed
    else -> R.string.notifications
}

/** Design 2: Listeneintraege ohne eigene Flaeche (die Kachel kommt von sagaSegment) */
private val SEG: ListItemColors
    @Composable get() = ListItemDefaults.colors(containerColor = Color.Transparent)

/** Merker der Einstellungen (nur im Speicher, je Anmeldung): Scrollstand, Geraeteliste, Mitteilungsarten, Sprungziel */
private object SettingsMemory {
    data class Pos(val key: Int, val index: Int, val count: Int)
    val scroll = HashMap<SettingsArea, Pos>()
    var apps: AppsResponse? = null
    var appsKey = -1
    var kinds: List<io.github.yggdrasil_sh.saga.live.NotifKind> = emptyList()
    var kindsKey = -1
    var avatar: String? = null
    var scrollTo: Pair<SettingsArea, String>? = null
}

/** 2.5 E2 (taskdue1): "Heute fällig"-Zusammenfassung an/aus und Uhrzeit (GET/PUT api/tasks/digest) */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun TaskDigestRow(repo: SagaRepository, w: Modifier) {
    var d by remember { mutableStateOf<io.github.yggdrasil_sh.saga.data.TaskSourcesLogic.Digest?>(null) }
    var pick by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        try {
            d = repo.taskDigest()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }
    val cur = d ?: return
    Column(w.padding(top = 10.dp)) {
        io.github.yggdrasil_sh.saga.ui.push.SwitchRow(
            stringResource(R.string.e2_digest), cur.on, Modifier.sagaSegment(0, if (cur.on) 2 else 1), hint = stringResource(R.string.e2_digest_hint),
        ) { on ->
            d = cur.copy(on = on)
            repo.launchAction(onError = { d = cur }) { repo.setTaskDigest(on = on) }
        }
        if (cur.on) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.e2_digest_time)) },
                trailingContent = { Text(cur.time, style = MaterialTheme.typography.titleMedium) },
                colors = SEG, modifier = Modifier.sagaSegment(1, 2).clickable { pick = true },
            )
        }
    }
    if (pick) {
        val (h, m) = cur.time.split(":").let { (it.getOrNull(0)?.toIntOrNull() ?: 7) to (it.getOrNull(1)?.toIntOrNull() ?: 30) }
        val st = androidx.compose.material3.rememberTimePickerState(h, m, is24Hour = true)
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pick = false },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    pick = false
                    val t = "%02d:%02d".format(st.hour, st.minute)
                    d = cur.copy(time = t)
                    repo.launchAction(onError = { d = cur }) { repo.setTaskDigest(time = t) }
                }) { Text(stringResource(R.string.action_apply)) }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { pick = false }) { Text(stringResource(R.string.action_cancel)) } },
            text = { androidx.compose.material3.TimePicker(st) },
        )
    }
}
