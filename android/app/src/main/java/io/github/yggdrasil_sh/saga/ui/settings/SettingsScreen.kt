package io.github.yggdrasil_sh.saga.ui.settings

import io.github.yggdrasil_sh.saga.ui.theme.SagaButton
import io.github.yggdrasil_sh.saga.ui.theme.SagaFilledTonalButton
import io.github.yggdrasil_sh.saga.ui.theme.SagaOutlinedButton
import io.github.yggdrasil_sh.saga.ui.theme.SagaTextButton
import io.github.yggdrasil_sh.saga.ui.theme.SagaIcons
import io.github.yggdrasil_sh.saga.ui.theme.SagaIcon
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
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
import io.github.yggdrasil_sh.saga.data.backupStatus
import io.github.yggdrasil_sh.saga.data.ncSettings
import io.github.yggdrasil_sh.saga.ui.common.Chrome
import io.github.yggdrasil_sh.saga.ui.common.SagaTopBar
import io.github.yggdrasil_sh.saga.ui.common.dateTimeText
import io.github.yggdrasil_sh.saga.ui.common.epochDay
import io.github.yggdrasil_sh.saga.ui.nav.AppNavigator
import io.github.yggdrasil_sh.saga.ui.nav.Dest
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
 * Einstellungen (Design-Runde 4, NAV-11/NAV-20): Hauptliste "Einstellungen" mit Kopf (Profilbild + Name -> Konto),
 * Allgemein (Sprache, Darstellung) und Kategorien mit ›:
 *  - Benachrichtigungen (SettingsNotificationsScreen): auf diesem Geraet (Systemmitteilungen, Android-Kanaele) und
 *    was Saga meldet (Server-Einstellungen, gruppiert), Anruf-Hinweise, Aufgaben-Zusammenfassung
 *  - Chats (SettingsChatsScreen): Chats & Geraete, gesperrte Personen, Linkvorschau, Datenschutz
 *  - Sicherung (BackupScreen) und Nextcloud - nur Server, die es koennen
 *  - Konto (SettingsAccountScreen): Anmeldung, Name, Profilbild, Sicherheit, oeffentlicher Name, Foederation,
 *    Apps & Geraete, ganz unten Abmelden (Textknopf in Fehlerfarbe, mit Rueckfrage)
 *  - Info (SettingsInfoScreen): Versionen, Kanal, Update-Hinweis, Diagnose, Lizenzen
 * Spruenge (?settings=backup, Glocke, Chat-Hinweise): SettingsNav.open / SettingsArea.
 */
@Composable
fun SettingsScreen(repo: SagaRepository, nav: AppNavigator, chrome: Chrome) {
    val server by repo.server.collectAsState()
    val account by repo.account.collectAsState()
    val signIns by repo.signInCount.collectAsState()
    val enabled by UpdateHintModel.enabled.collectAsState()
    val release by UpdateHintModel.release.collectAsState()
    val chatState by repo.chat.state.collectAsState()
    val update = UpdateHintModel.available(enabled, release)
    var ownAvatar by remember { mutableStateOf(SettingsMemory.avatar) }
    var backup by remember { mutableStateOf(SettingsMemory.backup.takeIf { SettingsMemory.servicesKey == signIns }) }
    var nc by remember { mutableStateOf(SettingsMemory.nc.takeIf { SettingsMemory.servicesKey == signIns }) }
    LaunchedEffect(account, signIns) {
        ownAvatar = try {
            repo.avatarState().url.also { SettingsMemory.avatar = it }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            ownAvatar
        }
    }
    // Sicherung und Nextcloud nur, wenn der Server sie anbietet (2.3); Chats: Zustand fuer die Kategorie
    LaunchedEffect(signIns) {
        backup = try {
            repo.backupStatus()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            backup
        }
        nc = try {
            repo.ncSettings()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            nc
        }
        SettingsMemory.backup = backup
        SettingsMemory.nc = nc
        SettingsMemory.servicesKey = signIns
        repo.chat.refresh()
    }
    val ncShown = nc?.takeIf { it.enabled || it.connected }
    val cats = SettingsLogic.categories(
        chats = chatState != ChatSetup.UNSUPPORTED && chatState != ChatSetup.OFF,
        backup = backup?.allowed == true,
        nextcloud = ncShown != null,
    )
    Scaffold(topBar = { SagaTopBar(stringResource(R.string.nav_settings), nav, chrome, contentMaxWidth = io.github.yggdrasil_sh.saga.ui.common.TopBarAlign.CONTENT) }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            val w = Modifier.widthIn(max = 760.dp).fillMaxWidth()
            // Kopf: wer ist angemeldet, wo (Antippen -> Konto)
            item(key = "head") {
                val host = ShotMode.host(PairLink.displayHost(server)).ifEmpty { "–" }
                ListItem(
                    headlineContent = {
                        Text(account.ifEmpty { stringResource(R.string.settings_account) }, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold))
                    },
                    supportingContent = { Text(host, maxLines = 1) },
                    leadingContent = { io.github.yggdrasil_sh.saga.ui.chat.ChatAvatar(account.ifEmpty { "?" }, 56.dp, avatar = ownAvatar) },
                    trailingContent = { SagaIcon(SagaIcons.chevronRight, null) },
                    colors = SEG,
                    modifier = w.padding(top = 8.dp).sagaSegment(0, 1, color = MaterialTheme.colorScheme.surfaceContainer)
                        .clickable(role = Role.Button, onClickLabel = stringResource(R.string.settings_account)) { openPage(nav, SettingsArea.KONTO) }
                        .padding(vertical = 6.dp),
                )
            }
            // Allgemein: Sprache und Darstellung direkt hier (das Kontomenue am Avatar ist nur ein Schnellweg)
            item(key = "g-general") { GroupHeading(stringResource(R.string.settings_group_general), w) }
            item(key = "sprache") { LanguageRow(w.sagaSegment(0, 2)) }
            item(key = "darstellung") { AppearanceRow(w.sagaSegment(1, 2)) }

            val groups = listOf(
                R.string.r4s_group_alerts to cats.filter { it == SettingsLogic.Category.NOTIFICATIONS || it == SettingsLogic.Category.CHATS },
                R.string.r4s_group_data to cats.filter { it == SettingsLogic.Category.BACKUP || it == SettingsLogic.Category.NEXTCLOUD },
                R.string.r4s_group_account to cats.filter { it == SettingsLogic.Category.ACCOUNT || it == SettingsLogic.Category.INFO },
            ).filter { it.second.isNotEmpty() }
            groups.forEach { (title, list) ->
                item(key = "g-$title") { GroupHeading(stringResource(title), w) }
                list.forEachIndexed { i, c ->
                    item(key = "cat-" + c.name.lowercase()) {
                        val m = w.sagaSegment(i, list.size)
                        when (c) {
                            SettingsLogic.Category.NOTIFICATIONS -> SettingsCategoryRow(
                                SagaIcons.bell, stringResource(R.string.notifications), m, summary = stringResource(R.string.r4s_cat_notifications_hint),
                            ) { openPage(nav, SettingsArea.BENACHRICHTIGUNGEN) }
                            SettingsLogic.Category.CHATS -> SettingsCategoryRow(
                                SagaIcons.chats, stringResource(R.string.r4s_chats), m, summary = stringResource(R.string.r4s_cat_chats_hint),
                            ) { openPage(nav, SettingsArea.CHATS) }
                            SettingsLogic.Category.BACKUP -> SettingsCategoryRow(
                                SagaIcons.archive, stringResource(R.string.r4s_backup), m,
                                summary = backup?.let { b ->
                                    when {
                                        b.running -> stringResource(R.string.ub_running_short)
                                        b.lastOk != null -> stringResource(R.string.ub_last_ok_at, dateTimeText((b.lastOk * 1000).toLong(), true))
                                        !b.ready -> stringResource(R.string.ub_not_set_up)
                                        else -> stringResource(R.string.ub_none_yet)
                                    }
                                },
                            ) { nav.push(Dest.Backup) }
                            SettingsLogic.Category.NEXTCLOUD -> SettingsCategoryRow(
                                SagaIcons.cloud, "Nextcloud", m,
                                summary = ncShown?.let { n -> if (n.connected) stringResource(R.string.nc_connected_as, n.user.orEmpty()) else stringResource(R.string.nc_not_connected) },
                            ) { nav.push(Dest.Nextcloud) }
                            SettingsLogic.Category.ACCOUNT -> SettingsCategoryRow(
                                SagaIcons.user, stringResource(R.string.settings_account), m, summary = stringResource(R.string.r4s_cat_account_hint),
                            ) { openPage(nav, SettingsArea.KONTO) }
                            SettingsLogic.Category.INFO -> {
                                val dotLabel = stringResource(R.string.settings_update_dot)
                                SettingsCategoryRow(
                                    SagaIcons.info, stringResource(R.string.settings_info), m, summary = stringResource(R.string.r4s_cat_info_hint),
                                    trailing = if (update != null) ({
                                        Box(
                                            Modifier.padding(end = 8.dp).size(10.dp).background(MaterialTheme.colorScheme.error, CircleShape)
                                                .semantics { contentDescription = dotLabel },
                                        )
                                    }) else null,
                                ) { openPage(nav, SettingsArea.INFO) }
                            }
                        }
                    }
                }
            }
            item(key = "end") { Box(Modifier.padding(bottom = 24.dp)) }
        }
    }
}

/** Unterseite oeffnen (Benachrichtigungen und Chats teilen sich das Ziel Dest.SettingsGeneral, siehe SettingsGeneralScreen) */
private fun openPage(nav: AppNavigator, area: SettingsArea) {
    nav.push(SettingsNav.dest(area))
}

// ------------------------------------------------------------------------------------------------ Konto

/** Konto: Anmeldung, Name, Profilbild, Sicherheit, oeffentlicher Name, Foederation, Apps & Geraete, Abmelden */
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
    var confirmLogout by remember { mutableStateOf(false) }
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
    Scaffold(topBar = { SagaTopBar(stringResource(R.string.settings_account), nav, chrome, contentMaxWidth = io.github.yggdrasil_sh.saga.ui.common.TopBarAlign.CONTENT) }) { pad ->
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
            // "Mein Backup" steht seit Design-Runde 4 als "Sicherung ›" in der Hauptliste

            item(key = "apps") { io.github.yggdrasil_sh.saga.ui.common.SagaWrapSectionTitle(stringResource(R.string.settings_apps_devices), w.padding(top = 12.dp)) }
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
                        trailingContent = { if (!d.current) SagaTextButton(onClick = { drop = d }) { Text(stringResource(R.string.sign_out), color = MaterialTheme.colorScheme.error) } },
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
            // NAV-11: Abmelden am Ende von "Konto" - Textknopf in Fehlerfarbe, mit Rueckfrage (keine grosse Flaeche)
            item(key = "logout") { SignOutRow(w.padding(top = 20.dp, bottom = 24.dp)) { confirmLogout = true } }
        }
    }
    if (confirmLogout) SignOutConfirmDialog(repo) { confirmLogout = false }
    drop?.let { d ->
        AlertDialog(
            onDismissRequest = { drop = null },
            title = { Text(stringResource(R.string.settings_sign_out_device_title, d.name)) },
            text = { Text(stringResource(R.string.settings_sign_out_device_text)) },
            confirmButton = {
                SagaTextButton(onClick = {
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
            dismissButton = { SagaTextButton(onClick = { drop = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

// ------------------------------------------------------------------------------------------------ Einstellungen

/**
 * Ziel Dest.SettingsGeneral: Unterseite "Benachrichtigungen" oder "Chats" (je nachdem, welche Kategorie zuletzt
 * geoeffnet wurde - SettingsNav.dest merkt es sich). Eigene Ziele je Unterseite brauchen Dest-Eintraege in ui/nav.
 */
@Composable
fun SettingsGeneralScreen(repo: SagaRepository, nav: AppNavigator, chrome: Chrome) {
    when (SettingsMemory.page) {
        SettingsArea.CHATS -> SettingsChatsScreen(repo, nav, chrome)
        else -> SettingsNotificationsScreen(repo, nav, chrome)
    }
}

/**
 * NAV-20 Benachrichtigungen: oben "Auf diesem Geraet" (Systemmitteilungen: Zustellweg/Ruhezeiten in Saga, Link in
 * die Android-Einstellungen der Kanaele), darunter "Was Saga dir meldet" (Server-Einstellungen, gelten auf allen
 * Geraeten) in Gruppen: Seiten & Kommentare · Erwaehnungen · Chats & Anrufe · Aufgaben · Weitere.
 */
@Composable
fun SettingsNotificationsScreen(repo: SagaRepository, nav: AppNavigator, chrome: Chrome) {
    val context = LocalContext.current
    val signIns by repo.signInCount.collectAsState()
    var notifKinds by remember { mutableStateOf(SettingsMemory.kinds.takeIf { SettingsMemory.kindsKey == signIns } ?: emptyList()) }
    val pushSupported by repo.push.supported.collectAsState()
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
        if (repo.calls.enabled.value == null) repo.calls.checkEnabled()
    }
    val listState = rememberAreaListState(SettingsArea.BENACHRICHTIGUNGEN, signIns)
    val features25 by repo.serverFeatures.collectAsState()
    Scaffold(topBar = { SagaTopBar(stringResource(R.string.notifications), nav, chrome, contentMaxWidth = io.github.yggdrasil_sh.saga.ui.common.TopBarAlign.CONTENT) }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), state = listState, horizontalAlignment = Alignment.CenterHorizontally) {
            val w = Modifier.widthIn(max = 760.dp).fillMaxWidth()
            // Auf diesem Geraet: Systemmitteilungen (Android) - was dieses Geraet anzeigt und wann es still ist
            item(key = "system-head") { GroupHeading(stringResource(R.string.r4s_on_device), w) }
            item(key = "system-hint") { Explainer(stringResource(R.string.r4s_on_device_hint), w) }
            val n = if (pushSupported) 2 else 1
            if (pushSupported) {
                item(key = "push") {
                    SettingsCategoryRow(
                        SagaIcons.smartphone, stringResource(R.string.r4s_system_notifications), w.sagaSegment(0, n),
                        summary = stringResource(R.string.push_settings_hint),
                    ) { nav.push(Dest.Push) }
                }
            }
            item(key = "system") {
                SettingsCategoryRow(
                    SagaIcons.settings, stringResource(R.string.r4s_android_channels), w.sagaSegment(n - 1, n),
                    summary = stringResource(R.string.r4s_android_channels_hint), external = true,
                ) { openSystemNotificationSettings(context) }
            }
            val callHints = callsOn == true
            if (callHints) item(key = "calls") { CallsFullScreenHint(w) }
            if (callHints) item(key = "calls-push") { CallsPushHint(repo.push, w) { nav.push(Dest.Push) } }

            // Was Saga dir meldet (Glocke; Server-Einstellung, gilt auf allen Geraeten)
            val digest = "taskdue1" in features25
            if (notifKinds.isNotEmpty() || digest) {
                item(key = "notifs") { GroupHeading(stringResource(R.string.r4s_what_saga_reports), w) }
                item(key = "notifs-hint") { Explainer(stringResource(R.string.r4s_what_saga_reports_hint), w) }
            }
            SettingsLogic.grouped(notifKinds) { it.kind }.forEach { (g, list) ->
                item(key = "ng-" + g.name.lowercase()) { SubHeading(stringResource(notifGroupLabel(g)), w) }
                list.forEachIndexed { i, k ->
                    item(key = "nk-" + k.kind) {
                        io.github.yggdrasil_sh.saga.ui.push.SwitchRow(stringResource(notifKindLabel(k.kind)), k.on, w.sagaSegment(i, list.size)) { on ->
                            repo.launchAction { notifKinds = repo.live.setKind(k.kind, on).kinds; SettingsMemory.kinds = notifKinds }
                        }
                    }
                }
            }
            // 2.5 E2 (taskdue1): taegliche Zusammenfassung der Aufgaben (Schalter + Uhrzeit)
            if (digest) {
                item(key = "ng-tasks") { SubHeading(stringResource(R.string.r4s_ngroup_tasks), w) }
                item(key = "digest") { TaskDigestRow(repo, w) }
            }
            item(key = "end") { Box(Modifier.padding(bottom = 24.dp)) }
        }
    }
}

/** Chats: Chats & Geraete, gesperrte Personen, Linkvorschau, Datenschutz (Gelesen-Bestaetigungen, Online-Status) */
@Composable
fun SettingsChatsScreen(repo: SagaRepository, nav: AppNavigator, chrome: Chrome) {
    val signIns by repo.signInCount.collectAsState()
    val chatState by repo.chat.state.collectAsState()
    val chatMe by repo.chat.me.collectAsState()
    LaunchedEffect(signIns) { repo.chat.refresh() }
    val listState = rememberAreaListState(SettingsArea.CHATS, signIns)
    Scaffold(topBar = { SagaTopBar(stringResource(R.string.r4s_chats), nav, chrome, contentMaxWidth = io.github.yggdrasil_sh.saga.ui.common.TopBarAlign.CONTENT) }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), state = listState, horizontalAlignment = Alignment.CenterHorizontally) {
            val w = Modifier.widthIn(max = 760.dp).fillMaxWidth()
            val chatDev = chatState != ChatSetup.UNSUPPORTED && chatState != ChatSetup.OFF
            val rcFeat = chatState == ChatSetup.READY && io.github.yggdrasil_sh.saga.chat.ReceiptLogic.has(chatMe, "receipts") && chatMe?.privacy != null
            val onFeat = chatState == ChatSetup.READY && io.github.yggdrasil_sh.saga.chat.ReceiptLogic.has(chatMe, "online") && chatMe?.privacy != null
            val stdF = repo.chat.std
            val std = chatState == ChatSetup.READY && (stdF.block || stdF.unfurl)
            // CH-07: "Vorschau in der Chat-Liste" (nur dieses Geraet), sobald Chats eingerichtet sind
            val pvRow = chatState == ChatSetup.READY
            if (chatState == ChatSetup.OFF || chatState == ChatSetup.UNSUPPORTED) {
                item(key = "off") { Explainer(stringResource(R.string.r4s_chats_off), w.padding(top = 12.dp)) }
            }
            if (chatDev || std || pvRow) item(key = "chats-head") { GroupHeading(stringResource(R.string.r4s_chats_devices_group), w) }
            val rows = (if (chatDev) 1 else 0) + (if (pvRow) 1 else 0) + (if (std && stdF.block) 1 else 0) + (if (std && stdF.unfurl) 1 else 0)
            var idx = 0
            if (chatDev) {
                val i = idx++
                item(key = "chats") {
                    SettingsCategoryRow(
                        SagaIcons.devices, stringResource(R.string.chat_devices), w.sagaSegment(i, rows), summary = stringResource(R.string.chat_devices_hint),
                    ) { nav.push(Dest.ChatDevices) }
                }
            }
            if (pvRow) {
                val i = idx++
                item(key = "list-preview") { io.github.yggdrasil_sh.saga.ui.chat.ListPreviewSettingRow(repo, w.sagaSegment(i, rows)) }
            }
            // 2.5 Chat-Standard: gesperrte Personen (block2), Linkvorschau (unfurl1, nur dieses Geraet)
            if (std && stdF.block) {
                val i = idx++
                item(key = "blocked") {
                    var open by remember { mutableStateOf(false) }
                    SettingsCategoryRow(SagaIcons.ban, stringResource(R.string.std_blocked_list), w.sagaSegment(i, rows)) { open = true }
                    if (open) io.github.yggdrasil_sh.saga.ui.chat.LocalBlocksDialog(repo) { open = false }
                }
            }
            if (std && stdF.unfurl) {
                val i = idx++
                item(key = "unfurl") {
                    var on by remember { mutableStateOf(repo.chat.unfurlOn) }
                    io.github.yggdrasil_sh.saga.ui.push.SwitchRow(
                        stringResource(R.string.std_unfurl_setting), on, w.sagaSegment(i, rows), hint = stringResource(R.string.std_unfurl_hint),
                    ) { v -> repo.chat.unfurlOn = v; on = v }
                }
            }
            if (rcFeat || onFeat) {
                val pv = chatMe?.privacy ?: io.github.yggdrasil_sh.saga.chat.ChatPrivacy()
                item(key = "privacy") { GroupHeading(stringResource(R.string.chat_privacy_title), w) }
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
            item(key = "end") { Box(Modifier.padding(bottom = 24.dp)) }
        }
    }
}

/** Erklaersatz unter einem Abschnittskopf */
@Composable
private fun Explainer(text: String, modifier: Modifier) {
    Text(
        text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
    )
}

/** Kleiner Unterkopf innerhalb eines Abschnitts (TalkBack: Ueberschrift) */
@Composable
private fun SubHeading(text: String, modifier: Modifier) {
    Text(
        text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 6.dp).semantics { heading() },
    )
}

/** Gruppen-Beschriftung der Benachrichtigungsarten */
private fun notifGroupLabel(g: SettingsLogic.NotifGroup): Int = when (g) {
    SettingsLogic.NotifGroup.PAGES -> R.string.r4s_ngroup_pages
    SettingsLogic.NotifGroup.MENTIONS -> R.string.r4s_ngroup_mentions
    SettingsLogic.NotifGroup.CHATS -> R.string.r4s_ngroup_chats
    SettingsLogic.NotifGroup.OTHER -> R.string.r4s_ngroup_other
}

/** Abschnittskopf (Goldbalken, TalkBack-Ueberschrift); bricht um statt "Systemmitteilungen au…" (R4-Nachbesserung 61) */
@Composable
private fun GroupHeading(text: String, modifier: Modifier) {
    io.github.yggdrasil_sh.saga.ui.common.SagaWrapSectionTitle(text, modifier.padding(top = 14.dp, bottom = 2.dp))
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
    Scaffold(topBar = { SagaTopBar(stringResource(R.string.settings_info), nav, chrome, contentMaxWidth = io.github.yggdrasil_sh.saga.ui.common.TopBarAlign.CONTENT) }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), state = listState, horizontalAlignment = Alignment.CenterHorizontally) {
            val w = Modifier.widthIn(max = 760.dp).fillMaxWidth()
            item(key = "versions") { io.github.yggdrasil_sh.saga.ui.common.SagaWrapSectionTitle(stringResource(R.string.settings_versions), w.padding(top = 8.dp)) }
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
            item(key = "diag") { DiagSection(w) }
            item(key = "licenses") { io.github.yggdrasil_sh.saga.ui.common.SagaWrapSectionTitle(stringResource(R.string.settings_licenses), w.padding(top = 12.dp)) }
            item(key = "license-text") {
                Text(
                    stringResource(R.string.settings_license_text), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = w.padding(horizontal = 20.dp).padding(bottom = 24.dp),
                )
            }
        }
    }
}

/**
 * 2.5 clientlog1: Info -> Hilfe -> "Diagnose senden" - Ringpuffer der letzten Ereignisse und optional ein Freitext an den
 * Server (POST api/client/diag); Antwort-Kennung D-XXXXX zum Kopieren. Ohne Feature ausgegraut mit Hinweis.
 */
@Composable
private fun DiagSection(modifier: Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val on by io.github.yggdrasil_sh.saga.diag.ClientLogger.on.collectAsState()
    var open by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<io.github.yggdrasil_sh.saga.diag.ClientLogger.DiagResult?>(null) }
    val enabled = on == true
    Column(modifier) {
        io.github.yggdrasil_sh.saga.ui.common.SagaWrapSectionTitle(stringResource(R.string.diag_title), Modifier.padding(top = 12.dp))
        Column(
            Modifier.sagaSegment(0, 1).padding(horizontal = 16.dp, vertical = 12.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(if (on == false) R.string.diag_off else R.string.diag_hint), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                SagaFilledTonalButton(onClick = { result = null; open = true }, enabled = enabled) { Text(stringResource(R.string.diag_send)) }
            }
        }
    }
    if (open) {
        val r = result
        AlertDialog(
            onDismissRequest = { if (!busy) { open = false; if (r is io.github.yggdrasil_sh.saga.diag.ClientLogger.DiagResult.Ok) note = "" } },
            title = { Text(stringResource(R.string.diag_send)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (r) {
                        is io.github.yggdrasil_sh.saga.diag.ClientLogger.DiagResult.Ok -> {
                            androidx.compose.foundation.text.selection.SelectionContainer {
                                Text(stringResource(R.string.diag_sent, r.id), style = MaterialTheme.typography.titleSmall)
                            }
                        }
                        else -> {
                            Text(stringResource(R.string.diag_hint), style = MaterialTheme.typography.bodySmall)
                            androidx.compose.material3.OutlinedTextField(
                                value = note, onValueChange = { note = it.take(io.github.yggdrasil_sh.saga.diag.ClientLog.NOTE_MAX) },
                                label = { Text(stringResource(R.string.diag_note)) }, enabled = !busy, minLines = 3, maxLines = 6,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(stringResource(R.string.diag_note_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (busy) Text(stringResource(R.string.diag_sending), style = MaterialTheme.typography.bodySmall)
                            (r as? io.github.yggdrasil_sh.saga.diag.ClientLogger.DiagResult.Failed)?.let { f ->
                                Text(
                                    when (f.code) {
                                        429 -> stringResource(R.string.diag_too_many)
                                        404 -> stringResource(R.string.diag_off)
                                        0 -> stringResource(R.string.diag_failed, stringResource(R.string.diag_net))
                                        else -> stringResource(R.string.diag_failed, "HTTP ${f.code}")
                                    },
                                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                if (r is io.github.yggdrasil_sh.saga.diag.ClientLogger.DiagResult.Ok) {
                    val copied = stringResource(R.string.diag_copied)
                    SagaTextButton(onClick = {
                        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("Saga", r.id))
                        if (android.os.Build.VERSION.SDK_INT < 33) android.widget.Toast.makeText(context, copied, android.widget.Toast.LENGTH_SHORT).show()
                    }) { Text(stringResource(R.string.diag_copy)) }
                } else {
                    SagaTextButton(enabled = !busy && enabled, onClick = {
                        busy = true
                        scope.launch {
                            result = io.github.yggdrasil_sh.saga.diag.ClientLogger.diag(note)
                            busy = false
                        }
                    }) { Text(stringResource(R.string.diag_send)) }
                }
            },
            dismissButton = {
                SagaTextButton(enabled = !busy, onClick = { open = false; if (r is io.github.yggdrasil_sh.saga.diag.ClientLogger.DiagResult.Ok) note = "" }) {
                    Text(stringResource(if (r is io.github.yggdrasil_sh.saga.diag.ClientLogger.DiagResult.Ok) R.string.action_done else R.string.action_cancel))
                }
            },
        )
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
        io.github.yggdrasil_sh.saga.ui.common.SagaWrapSectionTitle(stringResource(R.string.update_title), Modifier.padding(top = 12.dp))
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
                        UpdateHint.Button.CHECKING -> SagaOutlinedButton(onClick = {}, enabled = false) { Text(stringResource(R.string.update_checking)) }
                        UpdateHint.Button.CHECK -> SagaOutlinedButton(onClick = { UpdateHintModel.checkNow() }) { Text(stringResource(R.string.update_check)) }
                        // "Update": APK (sonst Release-Seite) im Browser bzw. Download-Manager - nie als ausgeschriebene URL
                        UpdateHint.Button.UPDATE -> SagaButton(onClick = {
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

/** Spruenge in die Einstellungen (Mitteilung ?settings=…, Glocke): Hauptliste, Bereich, ggf. Unterseite + Zeile */
object SettingsNav {
    fun open(nav: AppNavigator, section: String?, replace: Boolean) {
        if (replace) nav.top(Dest.Settings) else nav.push(Dest.Settings)
        SettingsArea.of(section)?.let { area ->
            SettingsMemory.scrollTo = SettingsArea.scrollTarget(section)?.let { area to it }
            nav.push(dest(area))
        }
        when (SettingsArea.sub(section)) {
            SettingsArea.Sub.BACKUP -> nav.push(Dest.Backup)
            SettingsArea.Sub.NEXTCLOUD -> nav.push(Dest.Nextcloud)
            null -> {}
        }
    }

    /** Ziel eines Bereichs (Benachrichtigungen und Chats mit eigenen Zielen) */
    fun dest(a: SettingsArea): Dest = when (a) {
        SettingsArea.KONTO -> Dest.SettingsAccount
        // eigene Ziele (ueberstehen Prozess-Neustart); Dest.SettingsGeneral bleibt fuer alte gespeicherte Staende
        SettingsArea.BENACHRICHTIGUNGEN -> { SettingsMemory.page = a; Dest.SettingsNotifications }
        SettingsArea.CHATS -> { SettingsMemory.page = a; Dest.SettingsChats }
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
    // 2.5 (Server notify.py KINDS): Datenbank-Zeile und Erwaehnungen - vorher stand dort nur "Benachrichtigungen"
    "chat.mention" -> R.string.notify_kind_chat_mention
    "db.assigned" -> R.string.notify_kind_db_assigned
    "page.mention" -> R.string.notify_kind_page_mention
    "comment.mention" -> R.string.notify_kind_comment_mention
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
    /** Unterseite hinter Dest.SettingsGeneral (Benachrichtigungen oder Chats) */
    var page: SettingsArea = SettingsArea.BENACHRICHTIGUNGEN
    var backup: io.github.yggdrasil_sh.saga.data.BackupStatus? = null
    var nc: io.github.yggdrasil_sh.saga.data.NcSettings? = null
    var servicesKey = -1
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
                io.github.yggdrasil_sh.saga.ui.theme.SagaTextButton(onClick = {
                    pick = false
                    val t = "%02d:%02d".format(st.hour, st.minute)
                    d = cur.copy(time = t)
                    repo.launchAction(onError = { d = cur }) { repo.setTaskDigest(time = t) }
                }) { Text(stringResource(R.string.action_apply)) }
            },
            dismissButton = { io.github.yggdrasil_sh.saga.ui.theme.SagaTextButton(onClick = { pick = false }) { Text(stringResource(R.string.action_cancel)) } },
            text = { androidx.compose.material3.TimePicker(st) },
        )
    }
}
