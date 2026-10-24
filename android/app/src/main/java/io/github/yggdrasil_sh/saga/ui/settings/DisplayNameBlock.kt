package io.github.yggdrasil_sh.saga.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.data.SagaRepository
import io.github.yggdrasil_sh.saga.util.UiText
import io.github.yggdrasil_sh.saga.util.resolve
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** Bughunt D01: eigener Anzeigename (GET/PATCH /api/account) - optional, sehen alle, mit denen man chattet */
object DisplayName {
    /** Eigener Anzeigename aus /api/account: name == username heisst "keiner" */
    data class Info(val name: String, val canEdit: Boolean, val email: String? = null)

    const val MAX = 120

    fun parse(raw: String): Info? {
        val o = try { Json.parseToJsonElement(raw) as? JsonObject } catch (_: Exception) { null } ?: return null
        val u = (o["username"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val n = (o["name"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        return Info(prefill(u, n), (o["can_edit"] as? JsonPrimitive)?.booleanOrNull == true, (o["email"] as? JsonPrimitive)?.contentOrNull)
    }

    fun prefill(username: String, name: String?): String = name?.trim()?.takeIf { it.isNotEmpty() && it != username }.orEmpty()

    suspend fun load(repo: SagaRepository): Info? = parse(repo.api.get("api/account"))

    /** Namen speichern; die E-Mail geht unveraendert mit (aeltere Server setzen sonst beide Felder) */
    fun body(name: String, info: Info): JsonObject = buildJsonObject {
        put("name", name.trim().take(MAX))
        info.email?.let { put("email", it) }
    }

    suspend fun save(repo: SagaRepository, name: String, info: Info) {
        repo.api.patch("api/account", body(name, info))
    }
}

/** Eingabefeld mit Hinweis (Konto-Einstellungen und Chat-Einrichtung) */
@Composable
fun DisplayNameField(value: String, onChange: (String) -> Unit, enabled: Boolean, modifier: Modifier = Modifier, onDone: (() -> Unit)? = null) {
    OutlinedTextField(
        value = value, onValueChange = { onChange(it.take(DisplayName.MAX)) }, singleLine = true, enabled = enabled,
        label = { Text(stringResource(R.string.display_name_label)) },
        supportingText = { Text(stringResource(R.string.display_name_hint)) },
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = if (onDone != null) ImeAction.Done else ImeAction.Default),
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
        modifier = modifier.fillMaxWidth(),
    )
}

/** Block im Konto: nur bei Konten, deren Name Saga selbst verwaltet (lokal; LDAP/SSO: der Anmeldedienst) */
@Composable
fun DisplayNameBlock(repo: SagaRepository, modifier: Modifier = Modifier, reloadKey: Any? = null) {
    var info by remember { mutableStateOf<DisplayName.Info?>(null) }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<UiText?>(null) }
    var bad by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(repo, reloadKey) {
        info = try {
            DisplayName.load(repo)?.also { input = it.name }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }
    val i = info
    if (i == null || !i.canEdit) return
    fun save() {
        busy = true
        msg = null
        scope.launch {
            try {
                DisplayName.save(repo, input, i)
                info = i.copy(name = input.trim())
                bad = false
                msg = UiText.Res(R.string.display_name_saved)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                bad = true
                msg = repo.friendly(e)
            } finally {
                busy = false
            }
        }
    }
    Column(modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.display_name_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
        DisplayNameField(input, { input = it; msg = null }, enabled = !busy, onDone = { if (input.trim() != i.name) save() })
        msg?.let {
            Text(
                it.resolve(), style = MaterialTheme.typography.bodySmall,
                color = if (bad) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        Button(onClick = { save() }, enabled = !busy && input.trim() != i.name, modifier = Modifier.align(Alignment.End)) {
            Text(stringResource(R.string.action_save))
        }
    }
}
