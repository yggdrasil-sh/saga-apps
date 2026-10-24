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
import androidx.compose.material3.Icon
import androidx.compose.foundation.clickable
import io.github.yggdrasil_sh.saga.chat.ChatSetup
import io.github.yggdrasil_sh.saga.ui.nav.Dest

/** Einstellungen: Server, Versionen, Apps & Geraete, Abmelden */
@Composable
fun SettingsScreen(repo: SagaRepository, nav: AppNavigator, chrome: Chrome) {
    val server by repo.server.collectAsState()
    val account by repo.account.collectAsState()
    val serverVersion by repo.serverVersion.collectAsState()
    val minApp by repo.minApp.collectAsState()
    // zuletzt geladene Geraeteliste sofort zeigen (sonst springt die Liste beim Zurueckkehren, Nr. 34)
    var apps by remember { mutableStateOf(SettingsScroll.apps.takeIf { SettingsScroll.key == repo.signInCount.value }) }
    var appsError by remember { mutableStateOf<UiText?>(null) }
    var drop by remember { mutableStateOf<DeviceInfo?>(null) }
    var confirmLogout by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var notifKinds by remember { mutableStateOf<List<io.github.yggdrasil_sh.saga.live.NotifKind>>(emptyList()) }
    val pushSupported by repo.push.supported.collectAsState()
    val chatState by repo.chat.state.collectAsState()
    val chatMe by repo.chat.me.collectAsState()
    var ownAvatar by remember { mutableStateOf<String?>(null) }
    val callsOn by repo.calls.enabled.collectAsState()
    LaunchedEffect(Unit) {
        try {
            notifKinds = repo.live.settings().kinds
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
        repo.push.load()
        repo.chat.refresh()
        if (repo.calls.enabled.value == null) repo.calls.checkEnabled()
    }

    suspend fun loadApps() {
        try {
            apps = repo.apps()
            SettingsScroll.apps = apps
            appsError = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            appsError = repo.friendly(e)
        }
    }
    // Nach einer Kopplung/Anmeldung (gleicher Server, Ansicht bleibt) Geraeteliste und Version neu laden (Nachtest N2)
    val signIns by repo.signInCount.collectAsState()
    LaunchedEffect(signIns) {
        if (repo.serverVersion.value == null) repo.checkVersion()
        loadApps()
    }

    // Nr. 34: nach der Rueckkehr aus einer Unterseite (Mitteilungen, Backup ...) wieder an derselben Stelle.
    // Viele Eintraege laden erst asynchron (Kontobloecke, Mitteilungsarten, Push, Chat) und aendern dabei ihre Hoehe.
    // Daher: warten, bis die Liste wieder so viele Eintraege hat, und den ersten beim Verlassen ganz sichtbaren
    // Eintrag oben hinstellen (nicht weiter zurueck – sonst kommen die noch leeren Kontobloecke ins Bild und schieben
    // beim Laden alles nach unten). Bis ~2 s nachziehen, falls Nachladen die Liste zuruecksetzt; nie gegen den Nutzer.
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(signIns) {
        if (SettingsScroll.key != signIns || SettingsScroll.index <= 0) return@LaunchedEffect
        val idx = SettingsScroll.index
        val want = SettingsScroll.count
        for (n in 0 until 30) {
            if (listState.layoutInfo.totalItemsCount >= want) break
            kotlinx.coroutines.delay(100)
        }
        listState.scrollToItem(idx)
        repeat(20) {
            kotlinx.coroutines.delay(100)
            if (listState.isScrollInProgress) return@LaunchedEffect
            if (listState.firstVisibleItemIndex < idx && listState.canScrollForward) listState.scrollToItem(idx)
        }
    }
    androidx.compose.runtime.DisposableEffect(signIns) {
        onDispose {
            val vis = listState.layoutInfo.visibleItemsInfo
            val anchor = vis.firstOrNull { it.offset >= 0 } ?: vis.firstOrNull()
            SettingsScroll.key = signIns
            SettingsScroll.count = listState.layoutInfo.totalItemsCount
            SettingsScroll.index = anchor?.index ?: 0
        }
    }
    Scaffold(topBar = { SagaTopBar(stringResource(R.string.nav_settings), nav, chrome) }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), state = listState, horizontalAlignment = Alignment.CenterHorizontally) {
            val w = Modifier.widthIn(max = 760.dp).fillMaxWidth()
            // Sprache der App ganz oben (gut auffindbar; auf einem fremden/geteilten Geraet unabhaengig von der Geraetesprache)
            item { LanguageRow(w.padding(top = 4.dp)) }
            item { SectionHeader(stringResource(R.string.settings_account), w) }
            item {
                // Mit welchem Konto auf welchem Server angemeldet (Schutz vor unbemerktem Kontowechsel)
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_signed_in_as, account.ifEmpty { "–" })) },
                    supportingContent = { Text(stringResource(R.string.settings_on_server, io.github.yggdrasil_sh.saga.util.ShotMode.host(PairLink.displayHost(server)).ifEmpty { "–" })) },
                    leadingContent = {
                        // 2.1: eigenes Profilbild (sonst Initialen)
                        io.github.yggdrasil_sh.saga.ui.notifications.Avatar(account.ifEmpty { "?" }, size = 40, avatar = ownAvatar)
                    },
                    modifier = w,
                )
            }
            item {
                Text(
                    stringResource(R.string.settings_server_change_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = w.padding(horizontal = 16.dp),
                )
            }
            // 2.1: Profilbild (hochladen mit Zuschnitt) und "Anmeldung & Sicherheit" (Verwalten im Browser)
            // Bughunt D01: Anzeigename (optional, sehen alle, mit denen man chattet)
            item { DisplayNameBlock(repo, w.padding(top = 8.dp), reloadKey = signIns) }
            item { ProfilePictureBlock(repo, account, w.padding(top = 8.dp), reloadKey = signIns) { ownAvatar = it?.url } }
            item { SecurityBlock(repo, w, reloadKey = signIns) }
            // Veroeffentlichen: oeffentlicher Name als eigener Block im Konto (nach Kopplung neu laden)
            item { PublicNameBlock(repo, w.padding(top = 8.dp), reloadKey = signIns) }
            // 2.3: "In Adressbuechern verbundener Server erscheinen" (nur bei verbundenen Servern)
            item { FederationDirectoryBlock(repo, w, reloadKey = signIns) }
            // 2.3: "Mein Backup" und "Nextcloud" (nur Server, die es koennen)
            item { AccountServicesRows(repo, nav, w, reloadKey = signIns) }

            // Saga 2.0: Benachrichtigungen (Glocke) je Art, Mitteilungen (Push), Chats & Geraete
            if (notifKinds.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.notifications), w) }
                items(notifKinds, key = { "nk-" + it.kind }) { k ->
                    io.github.yggdrasil_sh.saga.ui.push.SwitchRow(stringResource(notifKindLabel(k.kind)), k.on, w) { on ->
                        repo.launchAction { notifKinds = repo.live.setKind(k.kind, on).kinds }
                    }
                }
            }
            if (pushSupported) {
                item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.push_title)) },
                        supportingContent = { Text(stringResource(R.string.push_settings_hint)) },
                        modifier = w.clickable { nav.push(Dest.Push) },
                    )
                }
            }
            if (chatState != ChatSetup.UNSUPPORTED && chatState != ChatSetup.OFF) {
                item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.chat_devices)) },
                        supportingContent = { Text(stringResource(R.string.chat_devices_hint)) },
                        modifier = w.clickable { nav.push(Dest.ChatDevices) },
                    )
                }
            }

            // 2.4: Datenschutz - Gelesen-Bestaetigungen und Online-Status, je getrennt, gegenseitig (aeltere Server: fehlt)
            val rcFeat = chatState == ChatSetup.READY && io.github.yggdrasil_sh.saga.chat.ReceiptLogic.has(chatMe, "receipts") && chatMe?.privacy != null
            val onFeat = chatState == ChatSetup.READY && io.github.yggdrasil_sh.saga.chat.ReceiptLogic.has(chatMe, "online") && chatMe?.privacy != null
            if (rcFeat || onFeat) {
                val pv = chatMe?.privacy ?: io.github.yggdrasil_sh.saga.chat.ChatPrivacy()
                item { SectionHeader(stringResource(R.string.chat_privacy_title), w) }
                if (rcFeat) item(key = "privacy-receipts") {
                    io.github.yggdrasil_sh.saga.ui.push.SwitchRow(
                        stringResource(R.string.chat_privacy_receipts), pv.receipts, w, hint = stringResource(R.string.chat_privacy_receipts_hint),
                    ) { on -> repo.launchAction { repo.chat.setPrivacy(receipts = on) } }
                }
                if (onFeat) item(key = "privacy-online") {
                    io.github.yggdrasil_sh.saga.ui.push.SwitchRow(
                        stringResource(R.string.chat_privacy_online), pv.online, w, hint = stringResource(R.string.chat_privacy_online_hint),
                    ) { on -> repo.launchAction { repo.chat.setPrivacy(online = on) } }
                }
            }

            if (callsOn == true) item { CallsFullScreenHint(w) }
            if (callsOn == true) item { CallsPushHint(repo.push, w) { nav.push(Dest.Push) } }

            item { SectionHeader(stringResource(R.string.settings_about), w) }
            item { ListItem(headlineContent = { Text(stringResource(R.string.settings_app)) }, trailingContent = { Text(io.github.yggdrasil_sh.saga.util.ShotMode.version("Saga ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")) }, modifier = w) }
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
                        // Nr. 33: dieses Geraet kennzeichnen; Abmelden hier ueber den normalen Weg (Konto oben), nicht per Liste
                        overlineContent = if (d.current) ({ Text(stringResource(R.string.settings_device_current), color = MaterialTheme.colorScheme.primary) }) else null,
                        trailingContent = { if (!d.current) TextButton(onClick = { drop = d }) { Text(stringResource(R.string.sign_out), color = MaterialTheme.colorScheme.error) } },
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

/** Scrollstand der Einstellungen (nur im Speicher, je Anmeldung) */
private object SettingsScroll {
    var key = -1
    var index = 0
    var count = 0
    var apps: AppsResponse? = null
}
