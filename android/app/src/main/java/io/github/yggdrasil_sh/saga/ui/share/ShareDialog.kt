package io.github.yggdrasil_sh.saga.ui.share

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.data.SagaRepository
import io.github.yggdrasil_sh.saga.data.SharedContent
import io.github.yggdrasil_sh.saga.util.UiText

/**
 * Teilen-Ziel (wie die iOS-Teilen-Erweiterung): Text oder Link aus einer anderen App als neue Seite (im Eingang)
 * oder als Aufgabe sichern. Speichern laeuft im App-Scope.
 */
@Composable
fun ShareDialog(repo: SagaRepository, content: SharedContent, onOpenPage: (Int) -> Unit) {
    var kind by remember(content) { mutableStateOf(if (content.url == null && content.text.length < 120 && !content.text.contains('\n')) "task" else "page") }
    var title by remember(content) { mutableStateOf(content.title) }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!busy) repo.pendingShare.value = null },
        title = { Text(stringResource(R.string.share_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val options = listOf("page" to stringResource(R.string.share_as_page), "task" to stringResource(R.string.share_as_task))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    options.forEachIndexed { i, (k, label) ->
                        SegmentedButton(selected = kind == k, onClick = { kind = k }, shape = SegmentedButtonDefaults.itemShape(i, options.size)) { Text(label) }
                    }
                }
                OutlinedTextField(
                    value = title, onValueChange = { title = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(if (kind == "task") R.string.task_title else R.string.share_page_title_label)) },
                    placeholder = { Text((content.url ?: content.text).take(80), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                )
                content.url?.let { Text("🔗 $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                if (content.text.isNotEmpty() && kind == "page") {
                    Text(content.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 6, overflow = TextOverflow.Ellipsis)
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                busy = true
                val k = kind
                val t = title.trim()
                repo.launchAction(onError = { busy = false }, onSuccess = { busy = false }) {
                    val id = repo.capture(k, t, content.text, content.url)
                    repo.pendingShare.value = null
                    if (id != null) repo.notify(UiText.Res(R.string.share_saved_page), UiText.Res(R.string.action_open)) { onOpenPage(id) }
                    else repo.notify(UiText.Res(if (k == "task") R.string.share_saved_task else R.string.share_saved_page))
                }
            }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = { repo.pendingShare.value = null }, enabled = !busy) { Text(stringResource(R.string.action_cancel)) } },
    )
}
