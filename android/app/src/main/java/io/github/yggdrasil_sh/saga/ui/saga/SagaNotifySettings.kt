package io.github.yggdrasil_sh.saga.ui.saga

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.data.SagaRepository
import io.github.yggdrasil_sh.saga.push.JobNotifyLogic
import io.github.yggdrasil_sh.saga.ui.theme.SagaTextButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Stand von "Inhalt in Mitteilungen" und "Ruhe bis" aus GET /api/ai/prefs (rein, Tests) */
data class SagaNotifyPrefs(val content: Boolean, val quietUntil: Double?) {
    fun quietActive(nowSec: Double): Boolean = (quietUntil ?: 0.0) > nowSec

    companion object {
        fun of(o: JsonObject): SagaNotifyPrefs? {
            if ((o["enabled"] as? JsonPrimitive)?.content == "false") return null
            val c = (o["notify_content"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "unlocked"
            return SagaNotifyPrefs(c != "never", (o["notify_quiet_until"] as? JsonPrimitive)?.doubleOrNull)
        }
    }
}

/**
 * 2.6 (Bugs-DB 2270, K-C): Einstellungen › Saga › Mitteilungen - "Mitteilungen von {Persona}" (Bereich jobs in PUT
 * /api/push/settings cats), "Inhalt in Mitteilungen" (notify_content unlocked|never) und, solange aktiv, "Ruhe bis 07:00 ·
 * Wieder an" (notify_quiet_until null). Nur mit Server-Nutzlast 3. Eigene Datei (SagaSettings.kt teilt sich K-D).
 */
@Composable
fun SagaNotifyBlock(repo: SagaRepository, name: String) {
    val info by repo.push.info.collectAsState()
    var p by remember { mutableStateOf<SagaNotifyPrefs?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    suspend fun load() {
        try {
            p = SagaNotifyPrefs.of(Json.parseToJsonElement(repo.api.get("api/ai/prefs")) as JsonObject)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }
    LaunchedEffect(Unit) {
        if (repo.push.info.value == null) runCatching { repo.push.load() }
        load()
    }
    fun run(f: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                f()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                repo.notify(repo.friendly(e))
            } finally {
                load()
                busy = false
            }
        }
    }
    val i = info ?: return
    val prefs = p ?: return
    if (i.server.payload < 3) return
    HorizontalDivider(Modifier.padding(vertical = 8.dp))
    Text(
        stringResource(R.string.jp_set_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp).semantics { heading() },
    )
    if (JobNotifyLogic.cat in i.categories) {
        val on = i.settings.cats[JobNotifyLogic.cat] ?: true
        SwitchRow(stringResource(R.string.jp_set_from, name), null, on, busy) { v ->
            run { repo.push.putSettings(buildJsonObject { putJsonObject("cats") { put(JobNotifyLogic.cat, v) } }) }
        }
    }
    SwitchRow(
        stringResource(R.string.jp_set_content),
        stringResource(if (prefs.content) R.string.jp_set_content_on else R.string.jp_set_content_off), prefs.content, busy,
    ) { v -> run { repo.api.put("api/ai/prefs", buildJsonObject { put("notify_content", if (v) "unlocked" else "never") }) } }
    val until = prefs.quietUntil
    if (until != null && prefs.quietActive(System.currentTimeMillis() / 1000.0)) {
        val time = android.text.format.DateFormat.getTimeFormat(ctx).format(java.util.Date((until * 1000).toLong()))
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.jp_set_quiet, time), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            SagaTextButton(onClick = { run { repo.api.put("api/ai/prefs", buildJsonObject { put("notify_quiet_until", JsonNull) }) } }, enabled = !busy) {
                Text(stringResource(R.string.jp_set_quiet_off))
            }
        }
    }
    Text(stringResource(R.string.jp_set_hint, name), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SwitchRow(label: String, sub: String?, on: Boolean, busy: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(on, enabled = !busy, role = Role.Switch) { onChange(it) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = on, onCheckedChange = null)
    }
}
