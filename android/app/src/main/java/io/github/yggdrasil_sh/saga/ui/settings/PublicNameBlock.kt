package io.github.yggdrasil_sh.saga.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.data.Publish
import io.github.yggdrasil_sh.saga.ui.publish.ConfirmDialog
import io.github.yggdrasil_sh.saga.ui.publish.NameStatus
import io.github.yggdrasil_sh.saga.ui.publish.NameStatusText
import io.github.yggdrasil_sh.saga.ui.publish.PublicNameModel
import io.github.yggdrasil_sh.saga.ui.publish.PublishBackend
import io.github.yggdrasil_sh.saga.ui.publish.SLUG_KEYS
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Einstellungen -> Konto: "Oeffentlicher Name" (wie im Web eigener Block mit eigenem Knopf). Steht in jeder
 * oeffentlichen Adresse /@name/..., nie der Anmeldename. Aeltere Server ohne /api/account/public-name: ausgeblendet.
 */
@Composable
fun PublicNameBlock(backend: PublishBackend, modifier: Modifier = Modifier, reloadKey: Any? = null) {
    val model = remember(backend) { PublicNameModel(backend) }
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf(false) }
    LaunchedEffect(model, reloadKey) { model.load() }
    LaunchedEffect(model.input) {
        if (model.status == NameStatus.Checking) {
            delay(250)
            model.check()
        }
    }
    val info = model.info
    if (model.hidden || info == null) return
    fun save() {
        if (model.needsConfirm) confirm = true else scope.launch { model.save() }
    }

    Column(modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.publish_public_name), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
        // Status (Wird geprueft / Frei / Fehler / Gespeichert) direkt unter dem Feld
        val status = model.status
        OutlinedTextField(
            value = model.input, onValueChange = model::edit, singleLine = true, enabled = !model.busy,
            label = { Text(stringResource(R.string.publish_name_label)) },
            placeholder = info.suggested?.let { s -> { Text(s) } },
            isError = status is NameStatus.Bad,
            supportingText = if (status == NameStatus.Idle) null else ({ NameStatusText(status, MaterialTheme.typography.bodySmall) }),
            keyboardOptions = SLUG_KEYS,
            keyboardActions = KeyboardActions(onDone = { if (model.canSave) save() }),
            modifier = Modifier.fillMaxWidth(),
        )
        // live beim Tippen: "Neue Adresse: …/@neu (bisher …/@alt)"
        model.newAddress?.let { (new, old) ->
            Text(
                stringResource(R.string.publish_name_new_address, Publish.breakable(new), Publish.breakable(old)),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            info.name?.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.publish_name_hint_set, Publish.breakable(info.url.orEmpty())) }
                ?: stringResource(R.string.publish_name_hint_unset),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = { save() }, enabled = model.canSave, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.publish_name_save)) }
    }

    if (confirm) {
        ConfirmDialog(
            title = stringResource(R.string.publish_name_change_title),
            text = stringResource(R.string.publish_name_change_confirm),
            confirm = stringResource(R.string.publish_name_change),
            onConfirm = { confirm = false; scope.launch { model.save() } },
            onDismiss = { confirm = false },
        )
    }
}
