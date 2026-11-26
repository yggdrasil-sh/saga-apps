package io.github.yggdrasil_sh.saga.ui.saga

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.ai.AiStatus
import io.github.yggdrasil_sh.saga.ai.SagaLogic
import io.github.yggdrasil_sh.saga.ai.SagaPrefs
import io.github.yggdrasil_sh.saga.data.SagaRepository
import io.github.yggdrasil_sh.saga.ui.common.Chrome
import io.github.yggdrasil_sh.saga.ui.common.SagaTopBar
import io.github.yggdrasil_sh.saga.ui.common.TopBarAlign
import io.github.yggdrasil_sh.saga.ui.common.appLocale
import io.github.yggdrasil_sh.saga.ui.nav.AppNavigator
import io.github.yggdrasil_sh.saga.ui.nav.Dest
import io.github.yggdrasil_sh.saga.ui.theme.SagaButton
import io.github.yggdrasil_sh.saga.ui.theme.SagaIcon
import io.github.yggdrasil_sh.saga.ui.theme.SagaIcons
import io.github.yggdrasil_sh.saga.ui.theme.SagaOutlinedButton
import io.github.yggdrasil_sh.saga.ui.theme.SagaTextButton
import io.github.yggdrasil_sh.saga.util.UiText
import io.github.yggdrasil_sh.saga.util.resolve
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/**
 * Einstellungen › Saga (K-H 2268 Anrede/Lebhaftigkeit, K-I 2269 Ideen/Verlauf und "Meine Daten", M7): Einwilligung
 * widerrufen (ehrlicher Text, pausiert laufende Auftraege), Export teilen (JSON/Markdown), "Alles loeschen" mit Rueckfrage,
 * "Was mein Admin sehen kann". Gleiche Texte wie im Web.
 */
@Composable
fun SagaSettingsScreen(repo: SagaRepository, nav: AppNavigator, chrome: Chrome) {
    val st by repo.ai.status.collectAsState()
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val res = LocalResources.current
    var prefs by remember { mutableStateOf<SagaPrefs?>(null) }
    var error by remember { mutableStateOf<UiText?>(null) }
    var busy by remember { mutableStateOf(false) }
    var askWithdraw by remember { mutableStateOf(false) }
    var askDelete by remember { mutableStateOf(false) }
    var adminOpen by remember { mutableStateOf(false) }
    suspend fun load() {
        try {
            repo.ai.loadStatus()
            prefs = repo.saga.prefs()
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = repo.friendly(e)
        }
    }
    LaunchedEffect(Unit) { load() }
    fun put(body: JsonObject) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                prefs = repo.saga.putPrefs(body)
                repo.notify(UiText.Res(R.string.saga_saved))
                if (body.containsKey("history")) repo.ai.loadStatus()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                repo.notify(repo.friendly(e))
            } finally {
                busy = false
            }
        }
    }
    Scaffold(topBar = { SagaTopBar(stringResource(R.string.saga_nav), nav, chrome, contentMaxWidth = TopBarAlign.CONTENT) }) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 760.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                error?.let { Text(it.resolve(), color = MaterialTheme.colorScheme.error) }
                val s = st
                val p = prefs
                if (p != null && (p.enabled == false || !SagaLogic.available(s))) {
                    Text(stringResource(R.string.ai_off), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    return@Column
                }
                if (p == null || s == null) return@Column
                SagaSettingsBody(repo, nav, s, p, busy, ::put, onWithdraw = { askWithdraw = true }, onDelete = { askDelete = true },
                    adminOpen = adminOpen, onAdmin = { adminOpen = !adminOpen },
                    onExport = { fmt ->
                        scope.launch {
                            try {
                                val (name, bytes) = repo.saga.export(fmt, java.time.LocalDate.now().toString())
                                shareExport(ctx, name, bytes, SagaLogic.mimeOf(fmt), res.getString(R.string.saga_share))
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                repo.notify(repo.friendly(e))
                            }
                        }
                    })
            }
        }
    }
    val s = st
    if (askWithdraw && s != null) {
        val name = sagaName(s)
        val host = s.endpoint?.label?.ifBlank { null } ?: s.host ?: "–"
        AlertDialog(
            onDismissRequest = { askWithdraw = false },
            title = { Text(stringResource(R.string.saga_withdraw_q)) },
            text = { Text(stringResource(R.string.saga_withdraw_text, name, host)) },
            confirmButton = {
                SagaTextButton(onClick = {
                    askWithdraw = false
                    scope.launch {
                        try {
                            val n = repo.saga.withdraw()
                            repo.notify(if (n > 0) UiText.Res(R.string.saga_withdrawn_n, n) else UiText.Res(R.string.saga_withdrawn))
                            load()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            repo.notify(repo.friendly(e))
                        }
                    }
                }) { Text(stringResource(R.string.saga_withdraw_ok), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { SagaTextButton(onClick = { askWithdraw = false }) { Text(stringResource(R.string.saga_cancel)) } },
        )
    }
    if (askDelete && s != null) {
        AlertDialog(
            onDismissRequest = { askDelete = false },
            title = { Text(stringResource(R.string.saga_delete_all_q)) },
            text = { Text(stringResource(R.string.saga_delete_all_text, sagaName(s))) },
            confirmButton = {
                SagaTextButton(onClick = {
                    askDelete = false
                    scope.launch {
                        try {
                            repo.saga.deleteAll()
                            rememberedSessionReset(repo)
                            repo.notify(UiText.Res(R.string.saga_deleted))
                            load()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            repo.notify(repo.friendly(e))
                        }
                    }
                }) { Text(stringResource(R.string.saga_delete_all), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { SagaTextButton(onClick = { askDelete = false }) { Text(stringResource(R.string.saga_cancel)) } },
        )
    }
}

/** Nach "Alles loeschen": Gespraech und Auftraege im Speicher der App ebenfalls leeren */
private fun rememberedSessionReset(repo: SagaRepository) {
    val s = SagaSessions.of(repo, "${repo.server.value}|${repo.account.value}")
    s.turns.clear()
    s.stage = null
    s.refreshJobs()
}

@Composable
private fun SagaSettingsBody(
    repo: SagaRepository, nav: AppNavigator, st: AiStatus, p: SagaPrefs, busy: Boolean, put: (JsonObject) -> Unit,
    onWithdraw: () -> Unit, onDelete: () -> Unit, adminOpen: Boolean, onAdmin: () -> Unit, onExport: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val name = sagaName(st)
    val c = p.consent ?: st.consent
    val state = c?.state ?: "none"
    val host = st.endpoint?.label?.ifBlank { null } ?: st.host ?: "–"
    // Stand der Einwilligung
    Text(
        when (state) {
            "granted" -> stringResource(
                R.string.saga_since, name,
                c?.at?.let { android.text.format.DateFormat.getDateFormat(ctx).format(java.util.Date((it * 1000).toLong())) } ?: "–",
            )
            "withdrawn" -> stringResource(R.string.saga_withdrawn_state, name, host)
            "stale" -> stringResource(R.string.saga_stale_state, name)
            else -> stringResource(R.string.saga_none_state, name)
        },
        style = MaterialTheme.typography.bodyLarge,
    )
    if (state != "granted") {
        SagaButton(onClick = { nav.top(Dest.Saga) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.saga_meet, name)) }
    }
    // Wie die KI mit dir spricht (K-H): Anrede (nicht im Englischen) und hoechstens so lebhaft wie der Admin
    Heading(stringResource(R.string.saga_nav))
    Hint(stringResource(R.string.saga_set_how))
    val ch = p.choices
    val def = p.default
    if (ch != null && ch.salutation.isNotEmpty() && SagaLogic.salutationShown(appLocale().language)) {
        val names = mapOf("du" to stringResource(R.string.saga_du), "sie" to stringResource(R.string.saga_sie))
        Choice(stringResource(R.string.saga_salutation), p.salutation, ch.salutation, names, def?.salutation, busy) {
            put(SagaLogic.prefBody("salutation", it))
        }
    }
    if (ch != null && ch.lively.size > 1) {
        val names = mapOf(
            "sachlich" to stringResource(R.string.saga_sachlich), "warm" to stringResource(R.string.saga_warm),
            "verspielt" to stringResource(R.string.saga_verspielt),
        )
        Choice(stringResource(R.string.saga_lively), p.lively, ch.lively, names, def?.lively, busy) { put(SagaLogic.prefBody("lively", it)) }
    }
    Hint(stringResource(R.string.saga_set_drafts))
    if (state == "granted" && p.ideasAllowed) {
        val names = mapOf("off" to stringResource(R.string.saga_off), "today" to stringResource(R.string.saga_today_only))
        Choice(stringResource(R.string.saga_ideas, name), p.ideas ?: "off", listOf("off", "today"), names, null, busy) {
            put(SagaLogic.prefBody("ideas", it ?: "off"))
        }
    }
    if (p.historyAllowed) {
        val on = p.history == true
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(on, enabled = !busy, role = Role.Switch) { put(SagaLogic.prefBody("history", it)) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.saga_history), style = MaterialTheme.typography.bodyLarge)
                Hint(stringResource(R.string.saga_history_hint))
            }
            Switch(checked = on, onCheckedChange = null)
        }
    }
    // Meine Daten (M7)
    HorizontalDivider(Modifier.padding(vertical = 8.dp))
    Heading(stringResource(R.string.saga_mydata))
    Hint(stringResource(R.string.saga_mydata_hint, name))
    if (state == "granted" || state == "stale") {
        SagaOutlinedButton(onClick = onWithdraw, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.saga_withdraw)) }
    }
    SagaOutlinedButton(onClick = { onExport("json") }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        SagaIcon(SagaIcons.share, null, modifier = Modifier.padding(end = 8.dp))
        Text(stringResource(R.string.saga_export_json))
    }
    SagaOutlinedButton(onClick = { onExport("md") }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        SagaIcon(SagaIcons.share, null, modifier = Modifier.padding(end = 8.dp))
        Text(stringResource(R.string.saga_export_md))
    }
    SagaTextButton(onClick = onDelete, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(stringResource(R.string.saga_delete_all), color = MaterialTheme.colorScheme.error)
    }
    // Was mein Admin sehen kann (K19: ehrlicher Hinweis) - aufklappbar
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onAdmin),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.saga_admin), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        SagaIcon(if (adminOpen) SagaIcons.chevronUp else SagaIcons.chevronDown, null)
    }
    if (adminOpen) {
        listOf(
            stringResource(R.string.saga_admin_1, name), stringResource(R.string.saga_admin_2), stringResource(R.string.saga_admin_3),
            stringResource(R.string.saga_admin_4, name, host),
        ).forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
    Column(Modifier.padding(bottom = 24.dp)) {}
}

@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp).semantics { heading() })
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Auswahl als Optionsliste (48 dp je Zeile, TalkBack: Gruppe); def != null -> erste Zeile "Standard (…)" = null */
@Composable
private fun Choice(label: String, value: String?, options: List<String>, names: Map<String, String>, def: String?, busy: Boolean, onPick: (String?) -> Unit) {
    Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
    Column(Modifier.selectableGroup()) {
        val rows = buildList {
            if (def != null) add(null to stringResource(R.string.saga_default, names[def] ?: def))
            options.forEach { add(it to (names[it] ?: it)) }
        }
        rows.forEach { (v, text) ->
            val sel = value == v
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(sel, enabled = !busy, role = Role.RadioButton) { if (!sel) onPick(v) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = sel, onClick = null)
                Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp))
            }
        }
    }
}

/** Export ueber das Teilen-Blatt: Datei nur im Cache (saga-export/), Freigabe per FileProvider (nur lesen) */
private suspend fun shareExport(ctx: Context, name: String, bytes: ByteArray, mime: String, title: String) {
    val f = withContext(Dispatchers.IO) {
        val dir = java.io.File(ctx.cacheDir, "saga-export").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }                // alte Exporte nicht liegen lassen
        java.io.File(dir, name).also { it.writeBytes(bytes) }
    }
    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
    val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(send, title).addFlags(if (ctx is android.app.Activity) 0 else Intent.FLAG_ACTIVITY_NEW_TASK))
}
