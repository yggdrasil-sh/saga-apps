package io.github.yggdrasil_sh.saga.ui.services

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.data.BookmarkLogic
import io.github.yggdrasil_sh.saga.data.SagaRepository
import io.github.yggdrasil_sh.saga.data.ServiceItem
import io.github.yggdrasil_sh.saga.data.addBookmark
import io.github.yggdrasil_sh.saga.data.deleteBookmark
import io.github.yggdrasil_sh.saga.data.updateBookmark
import io.github.yggdrasil_sh.saga.util.UiText
import io.github.yggdrasil_sh.saga.util.resolve
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Eigenes Lesezeichen anlegen (item == null) bzw. bearbeiten/loeschen - wie "Lesezeichen" im Web (Saga 2.4) */
@Composable
fun BookmarkDialog(repo: SagaRepository, item: ServiceItem?, defaultCategory: String, onDismiss: () -> Unit, onChanged: () -> Unit) {
    var name by remember { mutableStateOf(item?.name.orEmpty()) }
    var url by remember { mutableStateOf(item?.url.orEmpty()) }
    var category by remember { mutableStateOf(item?.category ?: defaultCategory) }
    var icon by remember { mutableStateOf(item?.icon.orEmpty()) }
    var error by remember { mutableStateOf<UiText?>(null) }
    var busy by remember { mutableStateOf(false) }
    var askDelete by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val id = item?.let { BookmarkLogic.idOf(it) }
    val urlOk = BookmarkLogic.validUrl(url)

    fun run(block: suspend () -> Unit, done: UiText) {
        busy = true
        error = null
        scope.launch {
            try {
                block()
                repo.notify(done)
                onChanged()
                onDismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = repo.friendly(e)
                busy = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(if (item == null) R.string.bm_new else R.string.bm_edit)) },
        text = {
            Column {
                OutlinedTextField(url, { url = it }, singleLine = true, label = { Text(stringResource(R.string.link_address_label)) },
                    isError = url.isNotBlank() && !urlOk, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(name, { name = it.take(80) }, singleLine = true, label = { Text(stringResource(R.string.bm_name)) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                OutlinedTextField(category, { category = it.take(40) }, singleLine = true, label = { Text(stringResource(R.string.bm_category)) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                OutlinedTextField(icon, { icon = it.take(64) }, singleLine = true, label = { Text(stringResource(R.string.bm_icon)) },
                    supportingText = { Text(stringResource(R.string.bm_icon_hint)) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                error?.let { Text(it.resolve(), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
            }
        },
        confirmButton = {
            TextButton(enabled = urlOk && !busy, onClick = {
                val body = BookmarkLogic.payload(name, url, category, icon)
                if (id == null) run({ repo.addBookmark(body) }, UiText.Res(R.string.bm_added))
                else run({ repo.updateBookmark(id, body) }, UiText.Res(R.string.bm_saved))
            }) { Text(stringResource(if (item == null) R.string.bm_add else R.string.ev_save)) }
        },
        dismissButton = {
            Row {
                if (id != null) TextButton(onClick = { askDelete = true }, enabled = !busy) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
    if (askDelete && id != null) {
        AlertDialog(
            onDismissRequest = { askDelete = false },
            title = { Text(stringResource(R.string.bm_delete_title, item.name)) },
            confirmButton = {
                TextButton(onClick = { askDelete = false; run({ repo.deleteBookmark(id) }, UiText.Res(R.string.bm_deleted)) }) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { askDelete = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
