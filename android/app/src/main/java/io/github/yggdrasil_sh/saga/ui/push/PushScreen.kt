package io.github.yggdrasil_sh.saga.ui.push

import android.Manifest
import android.app.TimePickerDialog
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.data.SagaRepository
import io.github.yggdrasil_sh.saga.push.Distributor
import io.github.yggdrasil_sh.saga.push.PushDevice
import io.github.yggdrasil_sh.saga.push.PushState
import io.github.yggdrasil_sh.saga.ui.common.Chrome
import io.github.yggdrasil_sh.saga.ui.common.SagaTopBar
import io.github.yggdrasil_sh.saga.ui.common.SectionHeader
import io.github.yggdrasil_sh.saga.ui.common.relativeTime
import io.github.yggdrasil_sh.saga.ui.nav.AppNavigator
import io.github.yggdrasil_sh.saga.util.UiText
import io.github.yggdrasil_sh.saga.util.resolve
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.time.ZoneId

/** Einstellungen -> Mitteilungen: dieses Geraet (UnifiedPush), worueber, Namen, Ruhezeiten, Test, Geraeteliste */
@Composable
fun PushScreen(repo: SagaRepository, nav: AppNavigator, chrome: Chrome) {
    val push = repo.push
    val info by push.info.collectAsState()
    val supported by push.supported.collectAsState()
    val state by push.state.collectAsState()
    val error by push.error.collectAsState()
    val context = LocalContext.current
    var choose by remember { mutableStateOf<List<Distributor>?>(null) }
    var noDistributor by remember { mutableStateOf(false) }
    var drop by remember { mutableStateOf<PushDevice?>(null) }
    LaunchedEffect(Unit) { push.load() }

    fun start() {
        val ds = push.distributors()
        when {
            ds.isEmpty() -> noDistributor = true
            ds.size == 1 -> repo.launchAction { push.enable(ds[0]) }
            else -> choose = ds
        }
    }
    // dauerhaft verweigert fragt Android nicht mehr: Weg in die Systemeinstellungen anbieten (BUG-109)
    val openSettings = { openNotificationSettings(context) }
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) start() else repo.notify(UiText.Res(R.string.push_permission_denied), UiText.Res(R.string.login_open_app_settings), openSettings)
    }
    // Berechtigung spaeter entzogen: Mitteilungen werden still verworfen - sichtbar machen (BUG-109)
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val resumed by lifecycle.currentStateFlow.collectAsState()
    val permissionMissing = remember(resumed) {
        Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    }

    Scaffold(topBar = { SagaTopBar(stringResource(R.string.push_title), nav, chrome) }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            val w = Modifier.widthIn(max = 760.dp).fillMaxWidth()
            if (!supported) {
                item { Text(stringResource(R.string.push_unsupported), modifier = w.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                return@LazyColumn
            }
            val srv = info?.server
            val available = srv?.enabled == true && srv.unifiedpush
            item { SectionHeader(stringResource(R.string.push_this_device), w) }
            item {
                // Hauptschalter und Zustandstext widersprechen sich nie: ohne Server-Unterstuetzung aus und gesperrt
                val on = PushUi.switchOn(available, state)
                ListItem(
                    headlineContent = { Text(stringResource(R.string.push_on_device)) },
                    supportingContent = {
                        Text(
                            when {
                                srv != null && !srv.enabled -> stringResource(R.string.push_server_off)
                                srv != null && !srv.unifiedpush -> stringResource(R.string.push_up_off)
                                state == PushState.REGISTERING -> stringResource(R.string.push_registering)
                                state == PushState.ON -> stringResource(R.string.push_on_via, push.local()?.distributor?.let { p -> push.distributors().firstOrNull { it.pkg == p }?.label ?: p }.orEmpty())
                                else -> stringResource(R.string.push_hint)
                            },
                        )
                    },
                    trailingContent = {
                        Switch(checked = on, onCheckedChange = null, enabled = available)
                    },
                    modifier = w.toggleable(value = on, enabled = available, role = Role.Switch) { want ->
                        if (!want) repo.launchAction { push.disable() }
                        else if (Build.VERSION.SDK_INT >= 33 &&
                            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                        ) askPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        else start()
                    },
                )
            }
            if (state == PushState.ON && permissionMissing) {
                item {
                    Column(w.padding(horizontal = 16.dp)) {
                        Text(stringResource(R.string.push_permission_revoked), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = openSettings) { Text(stringResource(R.string.login_open_app_settings)) }
                    }
                }
            }
            error?.let { e -> item { Text(e.resolve(), color = MaterialTheme.colorScheme.error, modifier = w.padding(horizontal = 16.dp)) } }
            if (noDistributor) {
                item {
                    Text(
                        stringResource(R.string.push_no_distributor), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = w.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
            val sub = push.local()?.subId
            if (state == PushState.ON && sub != null) {
                item {
                    OutlinedButton(onClick = {
                        repo.launchAction {
                            val r = push.test(sub)
                            repo.notify(if (r.result == "ok") UiText.Res(R.string.push_test_ok) else UiText.Res(R.string.push_test_fail, r.error ?: r.result))
                        }
                    }, modifier = Modifier.padding(start = 16.dp, top = 4.dp)) { Text(stringResource(R.string.push_test)) }
                }
            }

            val set = info?.settings
            // "Worüber?" nur bedienbar, wenn Mitteilungen auf diesem (oder einem anderen) Geraet eingerichtet sind
            val catsOn = PushUi.categoriesEnabled(srv?.enabled == true, state, info?.devices.orEmpty().size)
            if (set != null && srv?.enabled == true) {
                item { SectionHeader(stringResource(R.string.push_about_what), w) }
                if (!catsOn) {
                    item {
                        Text(
                            stringResource(R.string.push_cats_need_push), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = w.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                }
                val cats = listOf(
                    "share" to R.string.push_cat_share, "comment" to R.string.push_cat_comment,
                    "chat" to R.string.push_cat_chat, "publish" to R.string.push_cat_publish,
                )
                items(cats.filter { (c, _) -> info?.categories?.let { c in it } ?: true }, key = { it.first }) { (c, label) ->
                    val v = set.cats[c] ?: true
                    SwitchRow(stringResource(label), v, w, enabled = catsOn) { on ->
                        repo.launchAction { push.putSettings(buildJsonObject { putJsonObject("cats") { put(c, on) } }) }
                    }
                }
                item {
                    SwitchRow(stringResource(R.string.push_names), set.names, w, stringResource(R.string.push_names_hint), enabled = catsOn) { on ->
                        repo.launchAction { push.putSettings(buildJsonObject { put("names", on) }) }
                    }
                }
                item {
                    SwitchRow(stringResource(R.string.push_quiet), set.quiet.on, w, enabled = catsOn) { on ->
                        repo.launchAction {
                            push.putSettings(buildJsonObject {
                                putJsonObject("quiet") {
                                    put("on", on)
                                    put("from", set.quiet.from)
                                    put("to", set.quiet.to)
                                    put("tz", ZoneId.systemDefault().id)
                                }
                            })
                        }
                    }
                }
                if (set.quiet.on) {
                    item {
                        Row(w.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            fun pickTime(cur: String, field: String) {
                                val (h, m) = cur.split(":").let { (it.getOrNull(0)?.toIntOrNull() ?: 22) to (it.getOrNull(1)?.toIntOrNull() ?: 0) }
                                TimePickerDialog(context, { _, hh, mm ->
                                    val v = "%02d:%02d".format(hh, mm)
                                    repo.launchAction {
                                        push.putSettings(buildJsonObject {
                                            putJsonObject("quiet") {
                                                put("on", true)
                                                put("from", if (field == "from") v else set.quiet.from)
                                                put("to", if (field == "to") v else set.quiet.to)
                                                put("tz", ZoneId.systemDefault().id)
                                            }
                                        })
                                    }
                                }, h, m, android.text.format.DateFormat.is24HourFormat(context)).show()
                            }
                            Text(stringResource(R.string.push_quiet_from))
                            OutlinedButton(onClick = { pickTime(set.quiet.from, "from") }, enabled = catsOn) { Text(set.quiet.from) }
                            Text(stringResource(R.string.push_quiet_to))
                            OutlinedButton(onClick = { pickTime(set.quiet.to, "to") }, enabled = catsOn) { Text(set.quiet.to) }
                        }
                    }
                }
            }

            val devs = info?.devices.orEmpty()
            if (devs.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.push_devices), w) }
                items(devs, key = { it.id }) { d ->
                    ListItem(
                        headlineContent = { Text(d.name.ifEmpty { d.type } + if (d.thisDevice || d.id == sub) " · " + stringResource(R.string.chat_this_device) else "") },
                        supportingContent = {
                            Text(
                                listOfNotNull(
                                    d.where.ifEmpty { d.type },
                                    d.lastOk?.let { stringResource(R.string.push_last_ok, relativeTime(it)) },
                                    d.lastError?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.push_test_fail, it) },
                                ).joinToString(" · "),
                            )
                        },
                        trailingContent = { TextButton(onClick = { drop = d }) { Text(stringResource(R.string.chat_device_remove), color = MaterialTheme.colorScheme.error) } },
                        modifier = w,
                    )
                    HorizontalDivider(w.padding(start = 16.dp))
                }
            }
        }
    }

    choose?.let { ds ->
        AlertDialog(
            onDismissRequest = { choose = null },
            title = { Text(stringResource(R.string.push_choose_distributor)) },
            text = {
                Column {
                    ds.forEach { d ->
                        Text(d.label, modifier = Modifier.fillMaxWidth().clickable { choose = null; repo.launchAction { push.enable(d) } }.padding(vertical = 12.dp))
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { choose = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    drop?.let { d ->
        AlertDialog(
            onDismissRequest = { drop = null },
            title = { Text(stringResource(R.string.push_remove_title)) },
            text = { Text(stringResource(R.string.push_remove_text, d.name.ifEmpty { d.type })) },
            confirmButton = {
                TextButton(onClick = { drop = null; repo.launchAction { push.removeDevice(d.id) } }) {
                    Text(stringResource(R.string.chat_device_remove), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { drop = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
fun SwitchRow(label: String, value: Boolean, modifier: Modifier, hint: String? = null, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = hint?.let { { Text(it) } },
        trailingContent = { Switch(checked = value, onCheckedChange = null, enabled = enabled) },
        modifier = modifier.toggleable(value = value, enabled = enabled, role = Role.Switch, onValueChange = onChange),
    )
}

/** Zustand der Schalter (rein, getestet) */
object PushUi {
    /** Hauptschalter: nur an, wenn der Server UnifiedPush anbietet UND dieses Geraet angemeldet ist/wird */
    fun switchOn(available: Boolean, state: PushState): Boolean = available && (state == PushState.ON || state == PushState.REGISTERING)

    /** "Worüber?", Namen, Ruhezeiten: nur wenn Mitteilungen auf dem Server an sind und hier oder anderswo eingerichtet */
    fun categoriesEnabled(serverOn: Boolean, state: PushState, devices: Int): Boolean = serverOn && (state == PushState.ON || devices > 0)
}

/** Mitteilungs-Einstellungen dieser App im System (nach dauerhaft verweigerter/entzogener Berechtigung, BUG-109) */
private fun openNotificationSettings(context: android.content.Context) {
    val i = android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(i)
    } catch (_: Exception) {
        context.startActivity(
            android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", context.packageName, null))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
