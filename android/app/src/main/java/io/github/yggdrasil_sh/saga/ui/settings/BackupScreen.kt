package io.github.yggdrasil_sh.saga.ui.settings

import android.app.TimePickerDialog
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.data.BackupLogic
import io.github.yggdrasil_sh.saga.data.BackupStatus
import io.github.yggdrasil_sh.saga.data.GeneratedKey
import io.github.yggdrasil_sh.saga.data.RemoteBackup
import io.github.yggdrasil_sh.saga.data.SagaRepository
import io.github.yggdrasil_sh.saga.data.backupConnect
import io.github.yggdrasil_sh.saga.data.backupDisconnect
import io.github.yggdrasil_sh.saga.data.backupFlowCancel
import io.github.yggdrasil_sh.saga.data.backupKeyAdd
import io.github.yggdrasil_sh.saga.data.backupKeyConfirm
import io.github.yggdrasil_sh.saga.data.backupKeyDelete
import io.github.yggdrasil_sh.saga.data.backupKeyDrop
import io.github.yggdrasil_sh.saga.data.backupKeyGenerate
import io.github.yggdrasil_sh.saga.data.backupRemote
import io.github.yggdrasil_sh.saga.data.backupRun
import io.github.yggdrasil_sh.saga.data.backupSettings
import io.github.yggdrasil_sh.saga.data.backupStatus
import io.github.yggdrasil_sh.saga.data.backupTest
import io.github.yggdrasil_sh.saga.ui.common.Chrome
import io.github.yggdrasil_sh.saga.ui.common.SagaTopBar
import io.github.yggdrasil_sh.saga.ui.common.SectionHeader
import io.github.yggdrasil_sh.saga.ui.common.dateTimeText
import io.github.yggdrasil_sh.saga.ui.common.openUrl
import io.github.yggdrasil_sh.saga.ui.nav.AppNavigator
import io.github.yggdrasil_sh.saga.ui.push.SwitchRow
import io.github.yggdrasil_sh.saga.util.UiText
import io.github.yggdrasil_sh.saga.util.resolve
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** Zeitpunkt (Sekunden seit 1970) mit Datum und Uhrzeit */
@Composable
private fun whenText(ts: Double?): String = ts?.let { dateTimeText((it * 1000).toLong(), withYear = true) } ?: "–"

/** Ueberschrift eines Abschnitts innerhalb einer Einstellungsseite */
@Composable
internal fun PartTitle(text: String, modifier: Modifier) {
    Text(
        text, style = MaterialTheme.typography.titleSmall,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp).semantics { heading() },
    )
}

@Composable
internal fun Hint(text: String, modifier: Modifier, error: Boolean = false) {
    Text(
        text, style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

/** Meldung unter einem Abschnitt: (Text, ok?) */
private data class Msg(val text: UiText, val ok: Boolean)

/**
 * Einstellungen -> "Mein Backup" (Saga 2.3, GET /api/account/backup): Status, Jetzt sichern, Ziel verbinden (Nextcloud per
 * Login Flow v2 oder WebDAV), Schluessel, Inhalt (nur wenn der Server Kategorien/Ausschluesse liefert), Zeitplan.
 * Einspielen: Hinweis aufs Web. Der private Schluessel wird nur einmal gezeigt und nie gespeichert.
 */
@Composable
fun BackupScreen(repo: SagaRepository, nav: AppNavigator, chrome: Chrome) {
    val context = LocalContext.current
    var st by remember { mutableStateOf<BackupStatus?>(null) }
    var missing by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<UiText?>(null) }
    var statusMsg by remember { mutableStateOf<Msg?>(null) }
    var targetMsg by remember { mutableStateOf<Msg?>(null) }
    var keyMsg by remember { mutableStateOf<Msg?>(null) }
    var contentMsg by remember { mutableStateOf<Msg?>(null) }
    var planMsg by remember { mutableStateOf<Msg?>(null) }
    var remote by remember { mutableStateOf<List<RemoteBackup>?>(null) }
    var newKey by remember { mutableStateOf<GeneratedKey?>(null) }
    var askDisconnect by remember { mutableStateOf(false) }
    var askKeyDelete by remember { mutableStateOf<String?>(null) }
    var pickPage by remember { mutableStateOf(false) }
    // Eingaben
    var url by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pw by remember { mutableStateOf("") }
    var folder by remember { mutableStateOf("Saga-Backup") }
    var pub by remember { mutableStateOf("") }
    val cats = remember { mutableStateMapOf<String, Boolean>() }
    val exclude = remember { mutableStateListOf<Long>() }
    var schedule by remember { mutableStateOf("off") }
    var time by remember { mutableStateOf("03:00") }
    var weekday by remember { mutableIntStateOf(0) }
    var keep by remember { mutableStateOf("7") }
    var formFor by remember { mutableStateOf<BackupStatus?>(null) }

    fun take(s: BackupStatus, form: Boolean) {
        st = s
        if (form || formFor == null) {
            cats.clear(); s.cats?.let { cats.putAll(it) }
            exclude.clear(); s.exclude?.let { exclude.addAll(it) }
            schedule = s.schedule; time = s.time; weekday = s.weekday; keep = s.keep.toString()
            formFor = s
        }
    }

    suspend fun load(form: Boolean = false) {
        try {
            val s = repo.backupStatus(pages = true)
            if (s == null) missing = true else take(s, form)
            loadError = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            loadError = repo.friendly(e)
        }
    }

    LaunchedEffect(Unit) {
        load(form = true)
        // laufendes Backup bzw. wartende Nextcloud-Anmeldung: alle 3 s nachfragen, solange die Ansicht offen ist
        while (true) {
            delay(3000)
            if (BackupLogic.shouldPoll(st)) load()
        }
    }

    fun act(onMsg: (Msg?) -> Unit, okText: UiText? = null, block: suspend () -> Unit) {
        repo.launchAction(onError = { e -> onMsg(Msg(repo.friendly(e), false)) }, onSuccess = { okText?.let { onMsg(Msg(it, true)) } }) {
            onMsg(null)
            block()
        }
    }

    val labels = mapOf(
        "pages" to R.string.ub_cat_pages, "templates" to R.string.ub_cat_templates, "archive" to R.string.ub_cat_archive,
        "trash" to R.string.ub_cat_trash, "versions" to R.string.ub_cat_versions, "files" to R.string.ub_cat_files,
        "comments" to R.string.ub_cat_comments, "comments_others" to R.string.ub_cat_comments_others, "tasks" to R.string.ub_cat_tasks,
        "bookmarks" to R.string.ub_cat_bookmarks, "settings" to R.string.ub_cat_settings,
    )
    val weekdays = listOf(
        R.string.ub_mon, R.string.ub_tue, R.string.ub_wed, R.string.ub_thu, R.string.ub_fri, R.string.ub_sat, R.string.ub_sun,
    )

    Scaffold(topBar = { SagaTopBar(stringResource(R.string.ub_title), nav, chrome) }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            val w = Modifier.widthIn(max = 760.dp).fillMaxWidth()
            item { Hint(stringResource(R.string.ub_intro), w.padding(top = 8.dp)) }
            loadError?.let { e -> item { Hint(e.resolve(), w, error = true) } }
            if (missing) {
                item { Hint(stringResource(R.string.ub_unsupported), w) }
                return@LazyColumn
            }
            val s = st ?: run {
                if (loadError == null) item { CircularProgressIndicator(Modifier.padding(24.dp)) }
                return@LazyColumn
            }
            if (!s.allowed) {
                item { Hint(stringResource(R.string.ub_disabled), w, error = true) }
                return@LazyColumn
            }

            // ---------------- Status
            item { SectionHeader(stringResource(R.string.ub_status), w) }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.ub_last_ok)) },
                    trailingContent = { Text(if (s.lastOk != null) whenText(s.lastOk) else stringResource(R.string.ub_none_yet)) }, modifier = w,
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.ub_next)) },
                    trailingContent = { Text(if (s.next != null) whenText(s.next) else stringResource(R.string.ub_schedule_off_short)) }, modifier = w,
                )
            }
            val last = s.runs.firstOrNull()
            if (s.running) item { Hint("⏳ " + stringResource(R.string.ub_running, last?.step?.ifBlank { null } ?: "…"), w) }
            BackupLogic.lastError(s)?.let { e -> item { Hint("✗ $e", w, error = true) } }
            item {
                FlowRow(w.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        act({ statusMsg = it }, UiText.Res(R.string.ub_started)) { repo.backupRun(); delay(1500); load() }
                    }, enabled = s.ready && !s.running) { Text(stringResource(R.string.ub_run_now)) }
                    OutlinedButton(onClick = { act({ statusMsg = it }) { remote = null; remote = repo.backupRemote() } }, enabled = s.target != null) {
                        Text(stringResource(R.string.ub_show_remote))
                    }
                }
            }
            statusMsg?.let { m -> item { Hint(m.text.resolve(), w, error = !m.ok) } }
            if (!s.ready) item { Hint(stringResource(R.string.ub_not_ready), w) }
            remote?.let { list ->
                if (list.isEmpty()) item { Hint(stringResource(R.string.ub_remote_none), w) }
                items(list, key = { "r-" + it.name }) { r ->
                    ListItem(
                        headlineContent = { Text(r.name, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium) },
                        supportingContent = { Text(BackupLogic.size(r.size) + if (r.complete) "" else " · " + stringResource(R.string.ub_incomplete)) },
                        modifier = w,
                    )
                }
            }
            if (s.runs.isNotEmpty()) {
                items(s.runs.take(5), key = { "run-" + it.started + it.name }) { r ->
                    val txt = whenText(r.started) + " · " + when {
                        r.ok -> "✓ " + BackupLogic.size(r.size)
                        r.finished != null -> "✗ " + r.error
                        else -> "⏳ " + r.step.ifBlank { "…" }
                    }
                    Hint(txt, w, error = !r.ok && r.finished != null)
                }
            }

            // ---------------- Ziel
            item { SectionHeader(stringResource(R.string.ub_target), w) }
            val t = s.target
            when {
                s.flow.state == "wait" && !s.flow.login.isNullOrEmpty() -> {
                    item { Hint(stringResource(R.string.nc_flow_wait), w) }
                    item {
                        FlowRow(w.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { openUrl(context, s.flow.login.orEmpty()) }) { Text(stringResource(R.string.nc_sign_in) + " ↗") }
                            OutlinedButton(onClick = { act({ targetMsg = it }) { repo.backupFlowCancel(); load() } }) { Text(stringResource(R.string.action_cancel)) }
                        }
                    }
                }
                t != null -> {
                    item {
                        Hint(
                            listOfNotNull(
                                if (t.kind == "nextcloud") "Nextcloud" else "WebDAV", t.address, t.user.ifEmpty { null },
                                stringResource(R.string.ub_folder_named, t.folder),
                                if (t.flow) stringResource(R.string.ub_app_password) else null,
                            ).joinToString(" · "),
                            w,
                        )
                    }
                    if (!t.allowed) item { Hint(stringResource(R.string.ub_target_not_allowed), w, error = true) }
                    item {
                        FlowRow(w.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                repo.launchAction(onError = { e -> targetMsg = Msg(repo.friendly(e), false) }) {
                                    targetMsg = Msg(UiText.Res(R.string.ub_checking), true)
                                    val free = repo.backupTest()
                                    targetMsg = Msg(UiText.Res(R.string.ub_test_ok, free?.let { BackupLogic.size(it) } ?: UiText.Res(R.string.ub_unknown)), true)
                                }
                            }) { Text(stringResource(R.string.ub_test)) }
                            OutlinedButton(onClick = { askDisconnect = true }) { Text(stringResource(R.string.ub_disconnect), color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
                else -> {
                    item { Hint(stringResource(R.string.ub_target_hint), w) }
                    item {
                        Column(w.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedTextField(url, { url = it }, label = { Text(stringResource(R.string.ub_address)) }, placeholder = { Text("https://cloud.example.org") },
                                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(user, { user = it }, label = { Text(stringResource(R.string.ub_user)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(pw, { pw = it }, label = { Text(stringResource(R.string.ub_password)) }, singleLine = true,
                                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(folder, { folder = it }, label = { Text(stringResource(R.string.ub_folder)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            Button(onClick = {
                                act({ targetMsg = it }) {
                                    targetMsg = Msg(UiText.Res(R.string.ub_checking), true)
                                    val flow = repo.backupConnect(url, user, pw, folder)
                                    pw = ""
                                    targetMsg = null
                                    if (flow != null) openUrl(context, flow)
                                    load()
                                }
                            }, enabled = url.isNotBlank()) { Text(stringResource(R.string.ub_connect)) }
                        }
                    }
                }
            }
            if (s.flow.message.isNotBlank()) item { Hint(s.flow.message, w, error = true) }
            else if (s.flow.state == "ok") item { Hint(stringResource(R.string.ub_nc_connected), w) }
            targetMsg?.let { m -> item { Hint(m.text.resolve(), w, error = !m.ok) } }

            // ---------------- Verschluesselung
            item { SectionHeader(stringResource(R.string.ub_encryption), w) }
            if (s.keys.isEmpty()) item { Hint(stringResource(R.string.ub_no_key), w) }
            items(s.keys, key = { "k-" + it.fp }) { k ->
                ListItem(
                    headlineContent = { Text(k.short, fontFamily = FontFamily.Monospace) },
                    supportingContent = { Text(k.fp, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) },
                    trailingContent = { TextButton(onClick = { askKeyDelete = k.fp }) { Text(stringResource(R.string.ub_remove), color = MaterialTheme.colorScheme.error) } },
                    modifier = w,
                )
            }
            // erzeugter Schluessel wartet auf Bestaetigung, ist hier aber nicht (mehr) bekannt (z. B. im Web erzeugt)
            if (s.pendingKey != null && newKey == null) {
                item {
                    Row(w.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.ub_key_pending), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = { act({ keyMsg = it }) { repo.backupKeyDrop(); load() } }) { Text(stringResource(R.string.ub_discard)) }
                    }
                }
            }
            item {
                Column(w.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { act({ keyMsg = it }) { newKey = repo.backupKeyGenerate() } }, enabled = s.ageOk) { Text(stringResource(R.string.ub_key_generate)) }
                    OutlinedTextField(pub, { pub = it }, label = { Text(stringResource(R.string.ub_key_own)) }, placeholder = { Text("age1…") },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace), minLines = 2, modifier = Modifier.fillMaxWidth())
                    OutlinedButton(onClick = { act({ keyMsg = it }) { repo.backupKeyAdd(pub); pub = ""; load() } }, enabled = pub.isNotBlank()) { Text(stringResource(R.string.ub_add)) }
                }
            }
            keyMsg?.let { m -> item { Hint(m.text.resolve(), w, error = !m.ok) } }

            // ---------------- Inhalt (nur wenn der Server Kategorien/Ausschluesse kennt)
            if (s.cats != null || s.exclude != null) {
                item { SectionHeader(stringResource(R.string.ub_content), w) }
                if (s.cats != null) {
                    items(BackupLogic.CATS.filter { it in s.cats }, key = { "c-$it" }) { c ->
                        SwitchRow(
                            stringResource(labels[c] ?: R.string.ub_title), cats[c] == true, w,
                            hint = if (c == "comments_others") stringResource(R.string.ub_cat_comments_others_hint) else null,
                        ) { cats[c] = it }
                    }
                    item { Hint(stringResource(R.string.ub_never_included), w) }
                }
                if (s.exclude != null && s.pages != null) {
                    item { PartTitle(stringResource(R.string.ub_exclude_title), w) }
                    val byId = s.pages.associateBy { it.id }
                    if (exclude.isEmpty()) item { Hint(stringResource(R.string.ub_exclude_none), w) }
                    items(exclude.toList(), key = { "x-$it" }) { id ->
                        val p = byId[id]
                        ListItem(
                            headlineContent = { Text((p?.icon?.takeIf { it.isNotEmpty() }?.let { "$it " } ?: "") + (p?.title?.ifEmpty { null } ?: if (p == null) "#$id" else stringResource(R.string.page_untitled))) },
                            supportingContent = { Text(stringResource(R.string.ub_with_subpages)) },
                            trailingContent = { TextButton(onClick = { exclude.remove(id) }) { Text(stringResource(R.string.ub_remove)) } },
                            modifier = w,
                        )
                    }
                    item { TextButton(onClick = { pickPage = true }, modifier = w.padding(horizontal = 8.dp)) { Text(stringResource(R.string.ub_exclude_add)) } }
                }
                item {
                    Button(onClick = {
                        act({ contentMsg = it }, UiText.Res(R.string.ub_saved)) {
                            take(repo.backupSettings(BackupLogic.contentBody(cats.toMap(), exclude.toList())), form = false)
                        }
                    }, modifier = w.padding(horizontal = 16.dp, vertical = 4.dp)) { Text(stringResource(R.string.action_save)) }
                }
                contentMsg?.let { m -> item { Hint(m.text.resolve(), w, error = !m.ok) } }
            }

            // ---------------- Zeitplan und Aufbewahrung
            item { SectionHeader(stringResource(R.string.ub_plan), w) }
            item {
                Column(w) {
                    listOf("off" to R.string.ub_sched_off, "weekly" to R.string.ub_sched_weekly, "daily" to R.string.ub_sched_daily).forEach { (v, label) ->
                        val allowed = BackupLogic.scheduleAllowed(v, s.limits)
                        Row(
                            Modifier.fillMaxWidth().selectable(selected = schedule == v, enabled = allowed, role = Role.RadioButton) { schedule = v }
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = schedule == v, onClick = null, enabled = allowed)
                            Text(stringResource(label), modifier = Modifier.padding(start = 12.dp),
                                color = if (allowed) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline)
                        }
                    }
                }
            }
            item {
                Row(w.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.ub_time))
                    OutlinedButton(onClick = {
                        val (h, m) = time.split(":").let { (it.getOrNull(0)?.toIntOrNull() ?: 3) to (it.getOrNull(1)?.toIntOrNull() ?: 0) }
                        TimePickerDialog(context, { _, hh, mm -> time = "%02d:%02d".format(hh, mm) }, h, m, android.text.format.DateFormat.is24HourFormat(context)).show()
                    }) { Text(time) }
                    if (schedule == "weekly") {
                        var open by remember { mutableStateOf(false) }
                        Column {
                            OutlinedButton(onClick = { open = true }) { Text(stringResource(weekdays[weekday.coerceIn(0, 6)])) }
                            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                                weekdays.forEachIndexed { i, r -> DropdownMenuItem(text = { Text(stringResource(r)) }, onClick = { weekday = i; open = false }) }
                            }
                        }
                    }
                }
            }
            item {
                OutlinedTextField(
                    keep, { v -> keep = v.filter { it.isDigit() }.take(2) }, label = { Text(stringResource(R.string.ub_keep)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = !BackupLogic.keepValid(keep.toIntOrNull()),
                    modifier = w.padding(horizontal = 16.dp),
                )
            }
            item { Hint(stringResource(R.string.ub_keep_hint, s.limits.maxMb), w) }
            item {
                Button(onClick = {
                    act({ planMsg = it }, UiText.Res(R.string.ub_saved)) {
                        take(repo.backupSettings(BackupLogic.settingsBody(schedule, time, weekday, keep.toIntOrNull() ?: s.keep)), form = false)
                    }
                }, enabled = BackupLogic.timeValid(time) && BackupLogic.keepValid(keep.toIntOrNull()), modifier = w.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Text(stringResource(R.string.action_save))
                }
            }
            planMsg?.let { m -> item { Hint(m.text.resolve(), w, error = !m.ok) } }

            // ---------------- Einspielen: im Web
            item { SectionHeader(stringResource(R.string.ub_restore), w) }
            item { Hint(stringResource(R.string.ub_restore_web), w) }
            item {
                OutlinedButton(onClick = { openUrl(context, repo.absoluteUrl("/?settings=backup")) }, modifier = w.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Text(stringResource(R.string.action_open_in_browser))
                }
            }
            item { HorizontalDivider(w.padding(vertical = 16.dp)) }
        }
    }

    newKey?.let { k -> NewKeyDialog(k, onDone = { last6, onErr ->
        repo.launchAction(onError = { e -> onErr(repo.friendly(e)) }) {
            repo.backupKeyConfirm(last6)
            newKey = null
            keyMsg = null
            load()
        }
    }, onDrop = {
        newKey = null
        act({ keyMsg = it }) { repo.backupKeyDrop(); load() }
    }) }
    if (askDisconnect) {
        AlertDialog(
            onDismissRequest = { askDisconnect = false },
            title = { Text(stringResource(R.string.ub_disconnect_title)) },
            text = { Text(stringResource(R.string.ub_disconnect_text)) },
            confirmButton = {
                TextButton(onClick = { askDisconnect = false; act({ targetMsg = it }) { repo.backupDisconnect(); load() } }) {
                    Text(stringResource(R.string.ub_disconnect), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { askDisconnect = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    askKeyDelete?.let { fp ->
        AlertDialog(
            onDismissRequest = { askKeyDelete = null },
            title = { Text(stringResource(R.string.ub_key_remove_title)) },
            text = { Text(stringResource(R.string.ub_key_remove_text)) },
            confirmButton = {
                TextButton(onClick = { askKeyDelete = null; act({ keyMsg = it }) { repo.backupKeyDelete(fp); load() } }) {
                    Text(stringResource(R.string.ub_remove), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { askKeyDelete = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (pickPage) {
        val tree = remember(st) { BackupLogic.pageTree(st?.pages.orEmpty()) }
        AlertDialog(
            onDismissRequest = { pickPage = false },
            title = { Text(stringResource(R.string.ub_exclude_add)) },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(tree, key = { it.first.id }) { (p, depth) ->
                        val on = p.id in exclude
                        Text(
                            "  ".repeat(depth) + (p.icon.takeIf { it.isNotEmpty() }?.let { "$it " } ?: "") + p.title.ifEmpty { stringResource(R.string.page_untitled) } + if (on) "  ✓" else "",
                            modifier = Modifier.fillMaxWidth().selectable(selected = on, role = Role.Checkbox) {
                                if (!on) exclude.add(p.id)
                                pickPage = false
                            }.padding(vertical = 10.dp),
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickPage = false }) { Text(stringResource(R.string.action_close)) } },
        )
    }
}

/**
 * Neuer privater Schluessel: nur jetzt sichtbar (Teilen/Kopieren), nie gespeichert. Bestaetigen mit den letzten 6 Zeichen.
 * Der Dialog schliesst nicht durch Tippen daneben (sonst waere der Schluessel weg).
 */
@Composable
private fun NewKeyDialog(k: GeneratedKey, onDone: (String, (UiText) -> Unit) -> Unit, onDrop: () -> Unit) {
    val context = LocalContext.current
    var last6 by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<UiText?>(null) }
    AlertDialog(
        onDismissRequest = {},
        properties = androidx.compose.ui.window.DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.ub_key_new_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.ub_key_new_text), style = MaterialTheme.typography.bodyMedium)
                SelectionContainer {
                    Text(k.private, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { shareSecret(context, k) }) { Text(stringResource(R.string.ub_key_share)) }
                    OutlinedButton(onClick = { copySecret(context, k.private) }) { Text(stringResource(R.string.ub_key_copy)) }
                }
                Text(stringResource(R.string.ub_key_store_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(last6, { last6 = it.trim().take(6) }, label = { Text(stringResource(R.string.ub_key_last6)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                err?.let { Text(it.resolve(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = { err = null; onDone(last6) { err = it } }, enabled = BackupLogic.last6Valid(last6)) { Text(stringResource(R.string.ub_key_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDrop) { Text(stringResource(R.string.ub_discard)) } },
    )
}

/** Teilen (z. B. an einen Passwort-Manager oder "In Dateien speichern") - nur Text, nichts bleibt in der App */
private fun shareSecret(context: Context, k: GeneratedKey) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, k.private).putExtra(Intent.EXTRA_TITLE, k.filename)
    context.startActivity(Intent.createChooser(send, k.filename).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** In die Zwischenablage, als vertraulich markiert (keine Vorschau im System, Android 13+) */
private fun copySecret(context: Context, text: String) {
    val cm = context.getSystemService(ClipboardManager::class.java) ?: return
    val clip = ClipData.newPlainText("Saga", text)
    if (Build.VERSION.SDK_INT >= 24) {
        clip.description.extras = PersistableBundle().apply {
            putBoolean(if (Build.VERSION.SDK_INT >= 33) ClipDescription.EXTRA_IS_SENSITIVE else "android.content.extra.IS_SENSITIVE", true)
        }
    }
    cm.setPrimaryClip(clip)
}
