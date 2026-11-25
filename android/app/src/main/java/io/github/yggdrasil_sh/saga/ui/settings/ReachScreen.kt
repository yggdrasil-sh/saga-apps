package io.github.yggdrasil_sh.saga.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.chat.StatusException
import io.github.yggdrasil_sh.saga.chat.StatusFocus
import io.github.yggdrasil_sh.saga.chat.UserStatusLogic
import io.github.yggdrasil_sh.saga.data.SagaRepository
import io.github.yggdrasil_sh.saga.ui.common.SagaTopBar
import io.github.yggdrasil_sh.saga.ui.nav.AppNavigator
import io.github.yggdrasil_sh.saga.ui.common.Chrome
import io.github.yggdrasil_sh.saga.ui.push.SwitchRow
import io.github.yggdrasil_sh.saga.ui.theme.SagaIcon
import io.github.yggdrasil_sh.saga.ui.theme.SagaIcons
import io.github.yggdrasil_sh.saga.ui.theme.sagaSegment
import io.github.yggdrasil_sh.saga.util.PersonName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/**
 * 2.6 (Bugs-DB 2029-7): Einstellungen -> Benachrichtigungen -> Erreichbarkeit (wie static/push.js "Erreichbarkeit"):
 * prefs des eigenen Status (PUT /api/account/status/prefs, nur das geaenderte Feld), Ausnahmen (Personen und eigene
 * Gruppen, hoechstens limits.exceptions; ersetzt die ganze Liste) und - nur auf diesem Geraet - "System-Nicht-stoeren
 * uebernehmen". Fehler: Wert zuruecksetzen, Meldung unter der Liste.
 */
@Composable
fun ReachScreen(repo: SagaRepository, nav: AppNavigator, chrome: Chrome) {
    val center = repo.chat.status
    val v by center.view.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var msg by remember { mutableStateOf<String?>(null) }
    var msgErr by remember { mutableStateOf(false) }
    var focusOn by remember { mutableStateOf(StatusFocus.enabled(context)) }
    LaunchedEffect(Unit) {
        try {
            center.load()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }
    val saved = stringResource(R.string.us_saved)
    fun save(body: JsonObject) {
        msg = null
        scope.launch {
            try {
                center.prefs(body)
                msg = saved
                msgErr = false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                msg = repo.friendly(e).asString(context)
                msgErr = true
            }
        }
    }
    Scaffold(topBar = { SagaTopBar(stringResource(R.string.us_reach), nav, chrome, contentMaxWidth = io.github.yggdrasil_sh.saga.ui.common.TopBarAlign.CONTENT) }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            val w = Modifier.widthIn(max = 760.dp).fillMaxWidth()
            val view = v
            item(key = "hint") { Hint(stringResource(R.string.us_reach_hint), w.padding(top = 8.dp)) }
            // Auf diesem Geraet: System-Nicht-stoeren (nur Zustand an/aus, nie der Name des Modus)
            item(key = "dev-head") { Heading(stringResource(R.string.us_on_device), w) }
            item(key = "focus") {
                SwitchRow(stringResource(R.string.us_focus_switch), focusOn, w.sagaSegment(0, 1), stringResource(R.string.us_focus_switch_hint)) { on ->
                    focusOn = on
                    StatusFocus.setEnabled(context, repo, repo.appScope, on)
                }
            }
            if (view == null) return@LazyColumn
            val p = view.prefs
            item(key = "acc-head") { Heading(stringResource(R.string.us_reach_server), w) }
            item(key = "quiet_dnd") {
                SwitchRow(stringResource(R.string.us_p_quiet_dnd), p.quietDnd, w.sagaSegment(0, 3), stringResource(R.string.us_p_quiet_dnd_hint)) {
                    save(UserStatusLogic.prefBody("quiet_dnd", it))
                }
            }
            item(key = "quiet_digest") {
                SwitchRow(stringResource(R.string.us_p_quiet_digest), p.quietDigest, w.sagaSegment(1, 3), stringResource(R.string.us_p_quiet_digest_hint)) {
                    save(UserStatusLogic.prefBody("quiet_digest", it))
                }
            }
            item(key = "mention") {
                SwitchRow(stringResource(R.string.us_p_mention), p.mentionBreak, w.sagaSegment(2, 3), stringResource(R.string.us_p_mention_hint)) {
                    save(UserStatusLogic.prefBody("mention_break", it))
                }
            }
            item(key = "calls-gap") { Box(Modifier.padding(top = 12.dp)) }
            item(key = "in_call") {
                Choice(
                    stringResource(R.string.us_p_in_call), stringResource(R.string.us_p_in_call_hint), p.inCall,
                    listOf("knock" to stringResource(R.string.us_p_knock), "busy" to stringResource(R.string.us_p_busy_report)), w.sagaSegment(0, 4),
                ) { save(UserStatusLogic.prefBody("in_call", it)) }
            }
            item(key = "show_call") {
                SwitchRow(stringResource(R.string.us_p_show_call), p.showCall, w.sagaSegment(1, 4), stringResource(R.string.us_p_show_call_hint)) {
                    save(UserStatusLogic.prefBody("show_call", it))
                }
            }
            item(key = "busy") {
                Choice(
                    stringResource(R.string.us_p_busy), stringResource(R.string.us_p_busy_hint), p.busy,
                    listOf("show" to stringResource(R.string.us_p_busy_show), "silent" to stringResource(R.string.us_p_busy_silent)), w.sagaSegment(2, 4),
                ) { save(UserStatusLogic.prefBody("busy", it)) }
            }
            item(key = "repeat") {
                Column(w.sagaSegment(3, 4)) {
                    SwitchRow(stringResource(R.string.us_p_repeat), p.repeat, Modifier.fillMaxWidth(), stringResource(R.string.us_p_repeat_hint, p.repeatS)) {
                        save(UserStatusLogic.prefBody("repeat", it))
                    }
                    RepeatField(p.repeatS, p.repeat, view.limits) { n -> save(UserStatusLogic.prefBody("repeat_s", n)) }
                }
            }
            item(key = "exc-head") { Heading(stringResource(R.string.us_exc), w) }
            item(key = "exc-hint") { Hint(stringResource(R.string.us_exc_hint), w) }
            val list = view.exceptions
            if (list.isEmpty()) item(key = "exc-none") { Hint(stringResource(R.string.us_exc_none), w.padding(top = 4.dp)) }
            list.forEachIndexed { i, x ->
                item(key = "exc-" + x.type + ":" + (x.ref ?: x.name)) {
                    val name = if (x.group) x.name.orEmpty() else PersonName.shown(x.name, x.ref)      // D01: nie die rohe Kennung
                    val rm = stringResource(R.string.us_exc_remove, name)
                    ListItem(
                        headlineContent = { Text(name) },
                        supportingContent = if (x.group) ({ Text(stringResource(R.string.us_exc_group)) }) else null,
                        leadingContent = { SagaIcon(if (x.group) SagaIcons.users else SagaIcons.user, null) },
                        trailingContent = {
                            IconButton(onClick = { save(UserStatusLogic.exceptionsBody(UserStatusLogic.removeException(center.view.value?.exceptions ?: list, x), view.limits.exceptions)) }) {
                                SagaIcon(SagaIcons.x, rm)
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = w.sagaSegment(i, list.size),
                    )
                }
            }
            item(key = "exc-n") { Hint(stringResource(R.string.us_exc_n, list.size, view.limits.exceptions), w.padding(top = 4.dp)) }
            if (list.size < view.limits.exceptions) item(key = "exc-add") {
                ExceptionSearch(repo, w) { x ->
                    val cur = center.view.value?.exceptions ?: list
                    save(UserStatusLogic.exceptionsBody(UserStatusLogic.addException(cur, x), view.limits.exceptions))
                }
            }
            item(key = "msg") {
                msg?.let {
                    Text(
                        it, color = if (msgErr) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = w.padding(horizontal = 20.dp, vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
            item(key = "end") { Box(Modifier.padding(bottom = 24.dp)) }
        }
    }
}

@Composable
private fun Heading(text: String, modifier: Modifier) {
    io.github.yggdrasil_sh.saga.ui.common.SagaWrapSectionTitle(text, modifier.padding(top = 14.dp, bottom = 2.dp))
}

@Composable
private fun Hint(text: String, modifier: Modifier) {
    Text(
        text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
    )
}

/** Auswahl mit zwei Werten als Radio-Gruppe (48 dp je Zeile) */
@Composable
private fun Choice(label: String, hint: String, value: String, opts: List<Pair<String, String>>, modifier: Modifier, onPick: (String) -> Unit) {
    Column(modifier.padding(vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 16.dp).semantics { heading() })
        Text(hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
        Column(Modifier.selectableGroup().padding(top = 4.dp)) {
            opts.forEach { (k, l) ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = value == k, role = Role.RadioButton) { if (value != k) onPick(k) }
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    RadioButton(selected = value == k, onClick = null)
                    Text(l)
                }
            }
        }
    }
}

/** Sekunden fuer Wiederholungsanrufe (Grenzen des Servers), speichern bei "Fertig" */
@Composable
private fun RepeatField(cur: Int, enabled: Boolean, l: io.github.yggdrasil_sh.saga.chat.StatusLimitsU, onSave: (Int) -> Unit) {
    var text by remember(cur) { mutableStateOf(cur.toString()) }
    val bad = text.isNotEmpty() && UserStatusLogic.repeatSeconds(text, l) == null
    val desc = stringResource(R.string.us_p_repeat_s_desc)
    OutlinedTextField(
        value = text, onValueChange = { t -> text = t.filter { it.isDigit() }.take(5) }, singleLine = true, enabled = enabled,
        label = { Text(stringResource(R.string.us_p_repeat_s, l.repeatMin, l.repeatMax)) },
        isError = bad,
        supportingText = if (bad) ({ Text(stringResource(R.string.us_p_repeat_bad, l.repeatMin, l.repeatMax)) }) else null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { UserStatusLogic.repeatSeconds(text, l)?.takeIf { it != cur }?.let(onSave) }),
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp).semantics { contentDescription = desc },
    )
}

/** Ausnahme hinzufuegen: Personen (auch anderer Server) und eigene Gruppen suchen (GET /api/share/targets) bzw. Kennung direkt eingeben */
@Composable
private fun ExceptionSearch(repo: SagaRepository, modifier: Modifier, onAdd: (StatusException) -> Unit) {
    var q by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<UserStatusLogic.ExcHit>?>(null) }
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }
    val groupWord = stringResource(R.string.us_exc_group)
    val desc = stringResource(R.string.us_exc_add_desc)
    Column(modifier.padding(horizontal = 16.dp)) {
        OutlinedTextField(
            value = q,
            onValueChange = { t ->
                q = t
                job?.cancel()
                val s = t.trim()
                if (s.isEmpty()) {
                    hits = null
                    return@OutlinedTextField
                }
                job = scope.launch {
                    delay(250)
                    // Bugs-DB 2387: Personen anderer Server (target '@~x@host' bzw. handle) und direkte Kennung wie im Web
                    val r = try {
                        repo.shareTargets(s)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                    hits = UserStatusLogic.exceptionHits(r, s, groupWord)
                }
            },
            singleLine = true,
            label = { Text(stringResource(R.string.us_exc_search)) },
            leadingIcon = { SagaIcon(SagaIcons.search, null) },
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = desc },
        )
        hits?.let { list ->
            if (list.isEmpty()) {
                Text(stringResource(R.string.us_exc_no_hits), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
            }
            list.take(8).forEach { (x, sub, title) ->
                ListItem(
                    headlineContent = { Text(title ?: if (x.group) x.name.orEmpty() else PersonName.shown(x.name, x.ref)) },
                    supportingContent = sub?.takeIf { it.isNotEmpty() }?.let { { Text(it) } },
                    leadingContent = { SagaIcon(if (x.group) SagaIcons.users else SagaIcons.user, null) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) {
                        q = ""
                        hits = null
                        onAdd(x)
                    },
                )
            }
        }
    }
}
